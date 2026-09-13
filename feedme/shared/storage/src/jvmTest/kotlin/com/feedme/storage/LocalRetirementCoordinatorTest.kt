package com.feedme.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireField
import com.feedme.core.ports.*
import com.feedme.session.*
import java.nio.file.Files
import java.nio.file.Path
import java.lang.reflect.InvocationTargetException
import java.security.SecureRandom
import java.util.Comparator
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Two real encrypted SQLite databases; credential/native-work providers remain explicit fakes. */
class LocalRetirementCoordinatorTest {
    @Test fun captureAndRestoreReadinessDoNotRetireKeysWorkOrCredentials() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            f.activate()
            val before = f.controlRecord()
            val dataKeys = f.dataVault.keys.keys.toSet()
            val controlKeys = f.controlVault.keys.keys.toSet()
            val captured = coordinatorValue(f.coordinator().capture(f.lease, ORIGIN, CREDENTIAL))
            assertTrue(coordinatorValue(f.coordinator().restorationAllowed()))
            assertTrue(coordinatorValue(f.coordinator().restorationAllowed()))
            assertSame(f.lease, f.boundary.current())
            assertEquals(before.revision, f.controlRecord().revision)
            assertContentEquals(before.payload.copyForCodec(), f.controlRecord().payload.copyForCodec())
            assertEquals(dataKeys, f.dataVault.keys.keys)
            assertEquals(controlKeys, f.controlVault.keys.keys)
            assertTrue(f.workCalls.isEmpty())
            assertTrue(f.credentialCalls.isEmpty())
            assertTrue(f.dataVault.deleted.isEmpty())
            assertFalse(captured.toString().contains(OWNER.actorId))
            assertFalse(captured.toString().contains(CREDENTIAL))
            assertRecord(f.store)
            // No expiry/401 input exists on this local coordinator: read/capture cannot implicitly
            // translate a provider event or elapsed time into explicit owner retirement.
        }
    }

    @Test fun logoutClearsBoundaryBeforeFirstIoAndPersistsBarrierBeforeEveryCleanup() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            f.activate()
            val coordinator = f.coordinator()
            val binding = coordinatorValue(coordinator.capture(f.lease, ORIGIN, CREDENTIAL))
            var firstIoSeen = false
            f.controlConnection.beforeStep = {
                assertNull(f.boundary.current(), "Logout must clear process authority before control I/O")
                firstIoSeen = true
            }
            f.work = { scope, origin ->
                assertTrue(firstIoSeen)
                assertNull(f.boundary.current())
                assertEquals(OWNER, scope); assertEquals(ORIGIN, origin)
                assertPending(f.controlRecord(), emptySet())
                assertTrue(f.dataVault.keys.isNotEmpty())
                PortResult.Value(Unit)
            }
            f.credentials = { scope, incarnation ->
                assertNull(f.boundary.current())
                assertEquals(OWNER, scope); assertEquals(CREDENTIAL, incarnation)
                assertPending(f.controlRecord(), setOf("NATIVE_WORK"))
                PortResult.Value(Unit)
            }
            f.dataVault.beforeDelete = { assertNull(f.boundary.current()) }
            val result = coordinatorValue(coordinator.retire(binding, OPERATION))
            assertEquals(LocalRetirementPhase.COMPLETE, result.phase)
            assertEquals(OPERATION, result.operationId)
            assertTrue(result.remaining.isEmpty())
            assertTrue(result.failures.isEmpty())
            assertTrue(f.dataVault.keys.isEmpty())
            assertEquals(1, f.controlVault.keys.size)
            assertNull(coordinatorValue(f.data.resume(OWNER)))
            assertTrue(coordinatorValue(coordinator.restorationAllowed()))
            assertEquals(listOf(OWNER to ORIGIN), f.workCalls)
            assertEquals(listOf(OWNER to CREDENTIAL), f.credentialCalls)
        }
    }

    @Test fun partialNativeFailureKeepsDurableBarrierWhileIndependentDataCleanupCompletes() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            f.activate()
            val coordinator = f.coordinator()
            val binding = coordinatorValue(coordinator.capture(f.lease, ORIGIN, CREDENTIAL))
            f.credentials = { _, _ -> PortResult.Failure(FailureReason.NOT_CONFIGURED) }
            val result = coordinatorValue(coordinator.retire(binding, OPERATION))
            assertEquals(LocalRetirementPhase.PENDING, result.phase)
            assertEquals(setOf(RetirementStep.CREDENTIALS), result.remaining)
            assertEquals(mapOf(RetirementStep.CREDENTIALS to FailureReason.NOT_CONFIGURED), result.failures)
            assertFalse(coordinatorValue(coordinator.restorationAllowed()))
            assertNull(f.boundary.current())
            assertTrue(f.dataVault.keys.isEmpty())
            assertNull(coordinatorValue(f.data.resume(OWNER)))
            assertPending(f.controlRecord(), setOf("NATIVE_WORK", "PRIVATE_DATA"))
            assertEquals(1, f.controlVault.keys.size)
        }
    }

    @Test fun closeBothDatabasesAndNewBoundaryRecoversOnlyExactCapturedNativeTargetsWithoutNewKeys() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            f.activate()
            val binding = coordinatorValue(f.coordinator().capture(f.lease, ORIGIN, CREDENTIAL))
            f.work = { _, _ -> PortResult.Failure(FailureReason.UNAVAILABLE) }
            f.credentials = { _, _ -> PortResult.Failure(FailureReason.STORAGE_FAILURE) }
            assertEquals(LocalRetirementPhase.PENDING, coordinatorValue(f.coordinator().retire(binding, OPERATION)).phase)
            val pendingBytes = f.controlRecord().payload.copyForCodec()
            val dataCreates = f.dataVault.creates
            val controlCreates = f.controlVault.creates
            f.reopen()
            assertContentEquals(pendingBytes, f.controlRecord().payload.copyForCodec())
            f.work = { scope, origin -> assertEquals(OWNER, scope); assertEquals(ORIGIN, origin); PortResult.Value(Unit) }
            f.credentials = { scope, incarnation -> assertEquals(OWNER, scope); assertEquals(CREDENTIAL, incarnation); PortResult.Value(Unit) }
            val restored = f.coordinator()
            assertFalse(coordinatorValue(restored.restorationAllowed()))
            val recovered = coordinatorValue(restored.recover())
            assertEquals(LocalRetirementPhase.COMPLETE, recovered.phase)
            assertEquals(OPERATION, recovered.operationId)
            assertNull(f.boundary.current())
            assertTrue(coordinatorValue(restored.restorationAllowed()))
            assertEquals(List(2) { OWNER to ORIGIN }, f.workCalls)
            assertEquals(List(2) { OWNER to CREDENTIAL }, f.credentialCalls)
            assertEquals(dataCreates, f.dataVault.creates)
            assertEquals(controlCreates, f.controlVault.creates)
            assertTrue(f.dataVault.keys.isEmpty())
            assertEquals(1, f.controlVault.keys.size)
            assertNull(coordinatorValue(f.data.resume(OWNER)))
        }
    }

    @Test fun privateDataPrecommitFailureReopensMissingKeyThenRecoversAuthenticatedTargetFromControlLedger() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            f.activate()
            val coordinator = f.coordinator()
            val binding = coordinatorValue(coordinator.capture(f.lease, ORIGIN, CREDENTIAL))
            f.dataConnection.nextWriteCommitFault = CoordinatorCommitFault.BEFORE
            val pending = coordinatorValue(coordinator.retire(binding, OPERATION))
            assertEquals(LocalRetirementPhase.PENDING, pending.phase)
            assertEquals(setOf(RetirementStep.PRIVATE_DATA), pending.remaining)
            assertEquals(FailureReason.STORAGE_FAILURE, pending.failures[RetirementStep.PRIVATE_DATA])
            assertTrue(f.dataVault.keys.isEmpty())
            assertPending(f.controlRecord(), setOf("NATIVE_WORK", "CREDENTIALS"))
            val target = stringField(ledger(f.controlRecord()), "dataTarget")
            assertEquals(StateRetirementTarget.ENCODED_SIZE * 2, target.length)
            f.reopen()
            assertIs<PortResult.Failure>(f.data.resume(OWNER))
            val workCalls = f.workCalls.size
            val credentialCalls = f.credentialCalls.size
            assertEquals(LocalRetirementPhase.COMPLETE, coordinatorValue(f.coordinator().recover()).phase)
            assertEquals(workCalls, f.workCalls.size)
            assertEquals(credentialCalls, f.credentialCalls.size)
            assertNull(coordinatorValue(f.data.resume(OWNER)))
            assertEquals(1, f.dataVault.creates)
            assertEquals(1, f.controlVault.creates)
        }
    }

    @Test fun everyUnknownControlCommitIsReconciledByExactPayloadWithoutRepeatedNativeEffects() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            f.activate()
            val coordinator = f.coordinator()
            val binding = coordinatorValue(coordinator.capture(f.lease, ORIGIN, CREDENTIAL))
            f.controlConnection.failEveryWriteCommitAfter = true
            val result = coordinatorValue(coordinator.retire(binding, OPERATION))
            f.controlConnection.failEveryWriteCommitAfter = false
            assertEquals(LocalRetirementPhase.COMPLETE, result.phase)
            assertEquals(5, f.controlConnection.faultsInjected)
            assertEquals(6L, f.controlRecord().revision)
            assertEquals(OPERATION, stringField(ledger(f.controlRecord()), "operationId"))
            assertEquals(1, f.workCalls.size)
            assertEquals(1, f.credentialCalls.size)
            assertEquals(1, f.dataVault.deleted.size)
            f.reopen()
            assertEquals(LocalRetirementPhase.COMPLETE, coordinatorValue(f.coordinator().recover()).phase)
            assertEquals(1, f.workCalls.size)
            assertEquals(1, f.credentialCalls.size)
        }
    }

    @Test fun failureToPersistInitialBarrierClearsAuthorityButDoesNotStartCleanup() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            f.activate()
            val coordinator = f.coordinator()
            val binding = coordinatorValue(coordinator.capture(f.lease, ORIGIN, CREDENTIAL))
            val before = f.controlRecord()
            val keys = f.dataVault.keys.keys.toSet()
            f.controlConnection.nextWriteCommitFault = CoordinatorCommitFault.BEFORE
            failure(FailureReason.STORAGE_FAILURE, coordinator.retire(binding, OPERATION))
            assertNull(f.boundary.current())
            assertTrue(f.workCalls.isEmpty())
            assertTrue(f.credentialCalls.isEmpty())
            assertEquals(keys, f.dataVault.keys.keys)
            assertEquals(before.revision, f.controlRecord().revision)
            assertContentEquals(before.payload.copyForCodec(), f.controlRecord().payload.copyForCodec())
            assertFalse(coordinatorValue(coordinator.restorationAllowed()))
            val replacementCoordinator = f.coordinator()
            assertFalse(coordinatorValue(replacementCoordinator.restorationAllowed()))
            val recovered = coordinatorValue(replacementCoordinator.recover())
            assertEquals(LocalRetirementPhase.COMPLETE, recovered.phase)
            assertEquals(OPERATION, recovered.operationId)
            assertEquals(listOf(OWNER to ORIGIN), f.workCalls)
            assertEquals(listOf(OWNER to CREDENTIAL), f.credentialCalls)
            assertTrue(coordinatorValue(replacementCoordinator.restorationAllowed()))
        }
    }

    @Test fun failedFirstControlReadLeavesSameBoundaryLatchThatRecoversExactIntentFromOlderIdleRecord() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            f.activate()
            val coordinator = f.coordinator()
            val binding = coordinatorValue(coordinator.capture(f.lease, ORIGIN, CREDENTIAL))
            f.controlConnection.failNextRead = true
            failure(FailureReason.STORAGE_FAILURE, coordinator.retire(binding, OPERATION))
            assertNull(f.boundary.current())
            assertTrue(f.workCalls.isEmpty())
            assertTrue(f.credentialCalls.isEmpty())
            assertEquals("idle", stringField(ledger(f.controlRecord()), "state"))
            assertFalse(coordinatorValue(f.coordinator().restorationAllowed()))
            val recovered = coordinatorValue(f.coordinator().recover())
            assertEquals(LocalRetirementPhase.COMPLETE, recovered.phase)
            assertEquals(OPERATION, recovered.operationId)
            assertEquals(1, f.dataVault.creates)
            assertEquals(listOf(OWNER to ORIGIN), f.workCalls)
        }
    }

    @Test fun suspendedOldIdleReadCannotPermitRestoreAfterAnotherCoordinatorInstallsRetirementLatch() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            f.activate()
            val retiring = f.coordinator()
            val binding = coordinatorValue(retiring.capture(f.lease, ORIGIN, CREDENTIAL))
            val oldIdleRead = CompletableDeferred<Unit>()
            val releaseOldIdle = CompletableDeferred<Unit>()
            val waitingControl = object : SessionControlStore {
                override suspend fun read(): PortResult<SessionControlRecord?> {
                    val result = f.control.read()
                    val record = assertNotNull(coordinatorValue(result))
                    assertEquals("idle", stringField(ledger(record), "state"))
                    oldIdleRead.complete(Unit)
                    releaseOldIdle.await()
                    return result
                }
                override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> =
                    error("Restoration readiness must not write control state")
            }
            val reader = f.coordinator(waitingControl)
            val oldPermission = async { reader.restorationAllowed() }
            oldIdleRead.await()
            f.controlConnection.nextWriteCommitFault = CoordinatorCommitFault.BEFORE
            failure(FailureReason.STORAGE_FAILURE, retiring.retire(binding, OPERATION))
            assertNull(f.boundary.current())
            assertEquals("idle", stringField(ledger(f.controlRecord()), "state"))
            assertTrue(f.workCalls.isEmpty())
            assertTrue(f.credentialCalls.isEmpty())
            releaseOldIdle.complete(Unit)
            assertFalse(coordinatorValue(oldPermission.await()), "A stale idle snapshot cannot override the new process latch")
            assertFalse(coordinatorValue(f.coordinator().restorationAllowed()))
            val recovered = coordinatorValue(f.coordinator().recover())
            assertEquals(LocalRetirementPhase.COMPLETE, recovered.phase)
            assertEquals(OPERATION, recovered.operationId)
            assertEquals(listOf(OWNER to ORIGIN), f.workCalls)
            assertEquals(listOf(OWNER to CREDENTIAL), f.credentialCalls)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun retirementFencesLeaseBeforeWaitingForItsOwnReadinessOrCaptureMutex() = runTest {
        for (holdingCapture in listOf(false, true)) {
            withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
                f.activate()
                val readEntered = CompletableDeferred<Unit>()
                val releaseRead = CompletableDeferred<Unit>()
                var pauseNextRead = false
                val waitingControl = object : SessionControlStore {
                    override suspend fun read(): PortResult<SessionControlRecord?> {
                        val result = f.control.read()
                        if (pauseNextRead) {
                            pauseNextRead = false
                            assertEquals("idle", stringField(ledger(assertNotNull(coordinatorValue(result))), "state"))
                            readEntered.complete(Unit)
                            releaseRead.await()
                        }
                        return result
                    }
                    override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> =
                        f.control.compareAndSet(expectedRevision, payload)
                }
                val coordinator = f.coordinator(waitingControl)
                val binding = coordinatorValue(coordinator.capture(f.lease, ORIGIN, CREDENTIAL))
                val original = f.controlRecord()
                pauseNextRead = true
                val readiness = if (!holdingCapture) async { coordinator.restorationAllowed() } else null
                val recapture = if (holdingCapture) async { coordinator.capture(f.lease, ORIGIN, CREDENTIAL) } else null
                readEntered.await()
                val retiring = async { coordinator.retire(binding, OPERATION) }
                runCurrent()

                assertFalse(retiring.isCompleted, "The durable retirement pass is still waiting for its coordinator mutex")
                assertNull(f.boundary.current(), "Logout must fence leased repositories/transport before waiting for that mutex")
                assertFalse(coordinatorValue(f.coordinator().restorationAllowed()), "Other coordinators must see the pre-mutex process latch")
                assertEquals(original.revision, f.controlRecord().revision)
                assertContentEquals(original.payload.copyForCodec(), f.controlRecord().payload.copyForCodec())
                assertTrue(f.workCalls.isEmpty())
                assertTrue(f.credentialCalls.isEmpty())

                releaseRead.complete(Unit)
                if (readiness != null) assertFalse(coordinatorValue(readiness.await()), "An old idle read cannot grant restoration")
                if (recapture != null) failure(FailureReason.STALE_SESSION, recapture.await())
                val completed = coordinatorValue(retiring.await())
                assertEquals(LocalRetirementPhase.COMPLETE, completed.phase)
                assertEquals(OPERATION, completed.operationId)
                assertNull(f.boundary.current())
                assertEquals(listOf(OWNER to ORIGIN), f.workCalls)
                assertEquals(listOf(OWNER to CREDENTIAL), f.credentialCalls)
                assertTrue(f.dataVault.keys.isEmpty())
            }
        }
    }

    @Test fun cancellationAtFirstControlIoKeepsPreinstalledLatchAndExactIntentForSameBoundaryRecovery() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            f.activate()
            val binding = coordinatorValue(f.coordinator().capture(f.lease, ORIGIN, CREDENTIAL))
            val firstIoEntered = CompletableDeferred<Unit>()
            val cancellableControl = object : SessionControlStore {
                override suspend fun read(): PortResult<SessionControlRecord?> {
                    assertNull(f.boundary.current())
                    firstIoEntered.complete(Unit)
                    awaitCancellation()
                }
                override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> =
                    error("Cancelled initial read cannot reach durable CAS")
            }
            val retiring = f.coordinator(cancellableControl)
            val pending = async { retiring.retire(binding, OPERATION) }
            firstIoEntered.await()
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertNull(f.boundary.current())
            assertEquals("idle", stringField(ledger(f.controlRecord()), "state"))
            assertTrue(f.workCalls.isEmpty())
            assertTrue(f.credentialCalls.isEmpty())
            assertEquals(1, f.dataVault.keys.size)
            assertFalse(coordinatorValue(f.coordinator().restorationAllowed()))
            val recovered = coordinatorValue(f.coordinator().recover())
            assertEquals(LocalRetirementPhase.COMPLETE, recovered.phase)
            assertEquals(OPERATION, recovered.operationId)
            assertEquals(listOf(OWNER to ORIGIN), f.workCalls)
            assertEquals(listOf(OWNER to CREDENTIAL), f.credentialCalls)
            assertEquals(1, f.dataVault.creates)
        }
    }

    @Test fun lostCompletedAcknowledgementAndFailedReconcileReadKeepLatchUntilExactCompleteIsObserved() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            f.activate()
            val coordinator = f.coordinator()
            val binding = coordinatorValue(coordinator.capture(f.lease, ORIGIN, CREDENTIAL))
            f.controlConnection.faultOnWriteCommit = f.controlConnection.writeCommits + 5
            f.controlConnection.failReadAfterFault = true
            failure(FailureReason.STORAGE_FAILURE, coordinator.retire(binding, OPERATION))
            assertEquals("complete", stringField(ledger(f.controlRecord()), "state"))
            assertFalse(coordinatorValue(f.coordinator().restorationAllowed()))
            assertEquals(1, f.workCalls.size)
            assertEquals(1, f.credentialCalls.size)
            val recovered = coordinatorValue(f.coordinator().recover())
            assertEquals(LocalRetirementPhase.COMPLETE, recovered.phase)
            assertEquals(OPERATION, recovered.operationId)
            assertTrue(coordinatorValue(f.coordinator().restorationAllowed()))
            assertEquals(1, f.workCalls.size)
            assertEquals(1, f.credentialCalls.size)
            assertEquals(6L, f.controlRecord().revision)
        }
    }

    @Test fun staleCapturedLeaseCannotClearNewIdentityOrRetireNewOwnerIncarnation() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            f.activate()
            val coordinator = f.coordinator()
            val oldBinding = coordinatorValue(coordinator.capture(f.lease, ORIGIN, CREDENTIAL))
            assertEquals(LocalRetirementPhase.COMPLETE, coordinatorValue(coordinator.retire(oldBinding, OPERATION)).phase)
            f.activate()
            val newLease = f.lease
            val newKeys = f.dataVault.keys.keys.toSet()
            val calls = f.workCalls.size to f.credentialCalls.size
            val record = f.controlRecord()
            failure(FailureReason.STALE_SESSION, coordinator.retire(oldBinding, OPERATION2))
            assertSame(newLease, f.boundary.current())
            assertEquals(newKeys, f.dataVault.keys.keys)
            assertEquals(calls, f.workCalls.size to f.credentialCalls.size)
            assertEquals(record.revision, f.controlRecord().revision)
            assertRecord(f.store)
        }
    }

    @Test fun pendingBarrierRejectsNewBindingAndDoesNotInventReplacementCleanupTargets() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            f.activate()
            val coordinator = f.coordinator()
            val captured = coordinatorValue(coordinator.capture(f.lease, ORIGIN, CREDENTIAL))
            f.credentials = { _, _ -> PortResult.Failure(FailureReason.UNAVAILABLE) }
            coordinatorValue(coordinator.retire(captured, OPERATION))
            // A mistaken composition call cannot overwrite pending retirement, even if another
            // caller has manually activated process metadata without consulting the restore gate.
            val accidentalLease = f.boundary.activate(OWNER)
            val before = f.controlRecord()
            failure(FailureReason.CONFLICT, coordinator.capture(accidentalLease, ORIGIN2, CREDENTIAL2))
            assertContentEquals(before.payload.copyForCodec(), f.controlRecord().payload.copyForCodec())
            assertFalse(coordinatorValue(coordinator.restorationAllowed()))
            assertEquals(1, f.dataVault.creates)
        }
    }

    @Test fun lastCompletedOperationIdCannotBeReusedToClearFreshSessionAuthority() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            f.activate()
            val first = coordinatorValue(f.coordinator().capture(f.lease, ORIGIN, CREDENTIAL))
            coordinatorValue(f.coordinator().retire(first, OPERATION))
            f.activate()
            val currentLease = f.lease
            val fresh = coordinatorValue(f.coordinator().capture(currentLease, ORIGIN2, CREDENTIAL2))
            val keys = f.dataVault.keys.keys.toSet()
            val before = f.controlRecord()
            failure(FailureReason.CONFLICT, f.coordinator().retire(fresh, OPERATION))
            assertSame(currentLease, f.boundary.current())
            assertEquals(keys, f.dataVault.keys.keys)
            assertEquals(before.revision, f.controlRecord().revision)
            assertEquals(1, f.workCalls.size)
            assertRecord(f.store)
        }
    }

    @Test fun completionRemovesOwnerOriginCredentialAndDataTargetFromCurrentControlPayload() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            f.activate()
            val coordinator = f.coordinator()
            val binding = coordinatorValue(coordinator.capture(f.lease, ORIGIN, CREDENTIAL))
            var target = ""
            f.work = { _, _ -> target = stringField(ledger(f.controlRecord()), "dataTarget"); PortResult.Value(Unit) }
            coordinatorValue(coordinator.retire(binding, OPERATION))
            val record = f.controlRecord()
            val root = ledger(record)
            assertEquals("complete", stringField(root, "state"))
            assertEquals(OPERATION, stringField(root, "operationId"))
            for (field in listOf("scope", "origin", "credentialIncarnation", "dataTarget", "done"))
                assertIs<WireField.Missing>(root.field(field))
            val text = record.payload.copyForCodec().decodeToString()
            for (private in listOf(OWNER.actorId, OWNER.environment, ORIGIN, CREDENTIAL, target)) assertFalse(text.contains(private))
            val revision = record.revision
            assertEquals(LocalRetirementPhase.COMPLETE, coordinatorValue(coordinator.recover()).phase)
            assertEquals(revision, f.controlRecord().revision)
            f.assertControlHasNoPlaintext(listOf(OWNER.actorId, ORIGIN, CREDENTIAL, target))
        }
    }

    @Test fun malformedEncryptedControlPayloadFailsClosedWithoutCleanupOrAutomaticReset() = runTest {
        for (corrupt in listOf("{\"version\":2,\"state\":\"idle\"}", "{\"version\":1,\"state\":\"idle\",\"state\":\"complete\"}",
            "{\"version\":1,\"state\":\"unknown\"}", "{\"version\":1,\"state\":\"idle\",\"extra\":true}")) {
            withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
                f.activate()
                val initial = f.controlRecord()
                coordinatorValue(f.control.compareAndSet(initial.revision, bytes(corrupt)))
                val coordinator = f.coordinator()
                failure(FailureReason.INVALID_DATA, coordinator.restorationAllowed())
                failure(FailureReason.INVALID_DATA, coordinator.recover())
                assertNull(f.boundary.current())
                assertTrue(f.workCalls.isEmpty())
                assertTrue(f.credentialCalls.isEmpty())
                assertEquals(1, f.dataVault.keys.size)
                assertEquals(corrupt, f.controlRecord().payload.copyForCodec().decodeToString())
                f.reopen()
                failure(FailureReason.INVALID_DATA, f.coordinator().restorationAllowed())
                assertEquals(1, f.controlVault.creates)
            }
        }
    }

    @Test fun missingOrCiphertextCorruptControlRecordCannotPermitRestoreOrStartCleanup() = runTest {
        for (sql in listOf("DELETE FROM feedme_records", "UPDATE feedme_records SET payload=zeroblob(29)")) {
            withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
                f.activate()
                val coordinator = f.coordinator()
                val binding = coordinatorValue(coordinator.capture(f.lease, ORIGIN, CREDENTIAL))
                f.rawControl(sql)
                failure(FailureReason.STORAGE_FAILURE, coordinator.restorationAllowed())
                failure(FailureReason.STORAGE_FAILURE, coordinator.retire(binding, OPERATION))
                failure(FailureReason.STORAGE_FAILURE, coordinator.recover())
                assertNull(f.boundary.current())
                assertTrue(f.workCalls.isEmpty())
                assertTrue(f.credentialCalls.isEmpty())
                assertEquals(1, f.dataVault.keys.size)
                coordinatorValue(f.control.close())
                failure(FailureReason.STORAGE_FAILURE, f.openExistingControl())
                assertEquals(1, f.controlVault.creates)
            }
        }
    }

    @Test fun missingIndependentControlKeyDoesNotRecreateItOrDeletePrivateOwnerKeys() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            f.activate()
            val coordinator = f.coordinator()
            val binding = coordinatorValue(coordinator.capture(f.lease, ORIGIN, CREDENTIAL))
            f.controlVault.keys.clear()
            failure(FailureReason.STORAGE_FAILURE, coordinator.restorationAllowed())
            failure(FailureReason.STORAGE_FAILURE, coordinator.retire(binding, OPERATION))
            assertTrue(f.workCalls.isEmpty())
            assertTrue(f.credentialCalls.isEmpty())
            assertEquals(1, f.dataVault.keys.size)
            assertEquals(1, f.controlVault.creates)
            coordinatorValue(f.control.close())
            failure(FailureReason.STORAGE_FAILURE, f.openExistingControl())
            assertEquals(1, f.controlVault.creates)
        }
    }

    @Test fun nativeExceptionIsSanitizedAsPendingWhileIndependentStepsStillComplete() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            f.activate()
            val coordinator = f.coordinator()
            val binding = coordinatorValue(coordinator.capture(f.lease, ORIGIN, CREDENTIAL))
            f.work = { _, _ -> error("private native token failure details") }
            val result = coordinatorValue(coordinator.retire(binding, OPERATION))
            assertEquals(LocalRetirementPhase.PENDING, result.phase)
            assertEquals(setOf(RetirementStep.NATIVE_WORK), result.remaining)
            assertEquals(mapOf(RetirementStep.NATIVE_WORK to FailureReason.STORAGE_FAILURE), result.failures)
            assertFalse(result.toString().contains("private native"))
            assertFalse(f.controlRecord().payload.copyForCodec().decodeToString().contains("private native"))
            assertTrue(f.dataVault.keys.isEmpty())
            assertEquals(1, f.credentialCalls.size)
            assertFalse(coordinatorValue(coordinator.restorationAllowed()))
        }
    }

    @Test fun cancellationDuringNativeCleanupLeavesBarrierForExactRecoveryAfterReopen() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            f.activate()
            val coordinator = f.coordinator()
            val binding = coordinatorValue(coordinator.capture(f.lease, ORIGIN, CREDENTIAL))
            val entered = CompletableDeferred<Unit>()
            f.work = { _, _ -> assertPending(f.controlRecord(), emptySet()); entered.complete(Unit); awaitCancellation() }
            val pending = async { coordinator.retire(binding, OPERATION) }
            entered.await()
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertNull(f.boundary.current())
            assertTrue(f.credentialCalls.isEmpty())
            assertEquals(1, f.dataVault.keys.size)
            assertFalse(coordinatorValue(coordinator.restorationAllowed()))
            assertPending(f.controlRecord(), emptySet())
            f.reopen()
            f.work = { _, _ -> PortResult.Value(Unit) }
            assertEquals(LocalRetirementPhase.COMPLETE, coordinatorValue(f.coordinator().recover()).phase)
            assertEquals(List(2) { OWNER to ORIGIN }, f.workCalls)
            assertEquals(listOf(OWNER to CREDENTIAL), f.credentialCalls)
            assertTrue(f.dataVault.keys.isEmpty())
            assertEquals(1, f.dataVault.creates)
        }
    }

    @Test fun captureWithoutPrivateDataOwnerRejectsUnconfiguredSessionWithoutCreatingCleanupTargets() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            f.lease = f.boundary.activate(OWNER)
            val coordinator = f.coordinator()
            failure(FailureReason.NOT_CONFIGURED, coordinator.capture(f.lease, ORIGIN, CREDENTIAL))
            assertEquals(0, f.dataVault.creates)
            assertTrue(f.dataVault.keys.isEmpty())
            assertTrue(f.workCalls.isEmpty())
            assertTrue(f.credentialCalls.isEmpty())
            assertSame(f.lease, f.boundary.current())
            assertEquals("idle", stringField(ledger(f.controlRecord()), "state"))
        }
    }

    @Test fun confirmedEmptySetupDiscardSupportsEveryAnchoredOptionalTargetCombination() = runTest {
        for (origin in listOf(null, ORIGIN)) for (credential in listOf(null, CREDENTIAL)) {
            if (origin == null && credential == null) continue
            for (hasData in listOf(false, true)) withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
                val target = if (hasData) {
                    coordinatorValue(f.data.activate(OWNER))
                    coordinatorValue(f.data.captureRetirement(OWNER))!!
                } else null
                val expected = f.controlRecord()
                val result = coordinatorValue(discardSetup(f.coordinator(supportsEmptyWork = true), expected, target, origin, credential))
                assertEquals(LocalRetirementPhase.COMPLETE, result.phase)
                assertEquals(OPERATION, result.operationId)
                assertTrue(result.remaining.isEmpty())
                assertNull(f.boundary.current())
                assertEquals(if (origin == null) emptyList() else listOf(OWNER to origin), f.emptyWorkCalls)
                assertEquals(if (credential == null) emptyList() else listOf(OWNER to credential), f.credentialCalls)
                assertTrue(f.workCalls.isEmpty())
                assertEquals(if (hasData) 1 else 0, f.dataVault.deleted.size)
                assertTrue(f.dataVault.keys.isEmpty())
                assertEquals("complete", stringField(ledger(f.controlRecord()), "state"))
            }
        }
    }

    @Test fun setupDiscardPersistsDistinctBarrierBeforeAnyExactEmptyCleanupWithoutActivatingLease() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            coordinatorValue(f.data.activate(OWNER))
            val target = coordinatorValue(f.data.captureRetirement(OWNER))!!
            f.emptyWork = { scope, origin ->
                assertEquals(OWNER, scope); assertEquals(ORIGIN, origin)
                assertNull(f.boundary.current())
                assertSetupPending(f.controlRecord(), emptySet())
                PortResult.Value(Unit)
            }
            f.credentials = { scope, incarnation ->
                assertEquals(OWNER, scope); assertEquals(CREDENTIAL, incarnation)
                assertNull(f.boundary.current())
                assertSetupPending(f.controlRecord(), setOf("NATIVE_WORK"))
                PortResult.Value(Unit)
            }
            f.dataVault.beforeDelete = { assertNull(f.boundary.current()) }
            assertEquals(LocalRetirementPhase.COMPLETE, coordinatorValue(discardSetup(
                f.coordinator(supportsEmptyWork = true), f.controlRecord(), target)).phase)
            assertTrue(f.workCalls.isEmpty())
            assertEquals(1, f.controlVault.keys.size)
            assertNull(f.boundary.current())
        }
    }

    @Test fun setupDiscardNeverFallsBackToOrdinaryWorkRetirementWhenEmptyCapabilityIsUnavailable() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            coordinatorValue(f.data.activate(OWNER))
            val target = coordinatorValue(f.data.captureRetirement(OWNER))!!
            f.work = { _, _ -> fail("Empty discard must not use the ordinary work retirement port") }
            val pending = coordinatorValue(discardSetup(f.coordinator(), f.controlRecord(), target))
            assertEquals(setOf(RetirementStep.NATIVE_WORK), pending.remaining)
            assertEquals(FailureReason.NOT_CONFIGURED, pending.failures[RetirementStep.NATIVE_WORK])
            assertTrue(f.workCalls.isEmpty())
            assertTrue(f.emptyWorkCalls.isEmpty())
            assertEquals(listOf(OWNER to CREDENTIAL), f.credentialCalls)
            assertTrue(f.dataVault.keys.isEmpty())
            assertSetupPending(f.controlRecord(), setOf("CREDENTIALS", "PRIVATE_DATA"))
            f.reopen()
            assertEquals(LocalRetirementPhase.COMPLETE, coordinatorValue(f.coordinator(supportsEmptyWork = true).recover()).phase)
            assertEquals(listOf(OWNER to ORIGIN), f.emptyWorkCalls)
            assertEquals(1, f.credentialCalls.size)
            assertTrue(f.workCalls.isEmpty())
        }
    }

    @Test fun setupDiscardRefusesNonemptyOrTombstonedDataBeforeAnyOtherCleanup() = runTest {
        for (tombstone in listOf(false, true)) withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            f.activate()
            f.boundary.clear()
            if (tombstone) {
                val record = coordinatorValue(f.store.read(OWNER, KEY))!!
                coordinatorValue(f.store.commit(OWNER, listOf(StoreMutation.Delete(KEY, record.revision))))
            }
            val target = coordinatorValue(f.data.captureRetirement(OWNER))!!
            failure(FailureReason.CONFLICT, discardSetup(f.coordinator(supportsEmptyWork = true), f.controlRecord(), target))
            assertSetupPending(f.controlRecord(), emptySet())
            assertTrue(f.emptyWorkCalls.isEmpty())
            assertTrue(f.workCalls.isEmpty())
            assertTrue(f.credentialCalls.isEmpty())
            assertTrue(f.dataVault.deleted.isEmpty())
            assertFalse(coordinatorValue(f.coordinator().restorationAllowed()))
            assertTrue(coordinatorValue(f.data.inspectOwnerState(OWNER)).hasRecords)
        }
    }

    @Test fun setupDiscardFirstBarrierFailurePreservesAllArtifactsAndSameProcessRecoveryUsesOriginalTargets() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            coordinatorValue(f.data.activate(OWNER))
            val target = coordinatorValue(f.data.captureRetirement(OWNER))!!
            val expected = f.controlRecord()
            f.controlConnection.nextWriteCommitFault = CoordinatorCommitFault.BEFORE
            val coordinator = f.coordinator(supportsEmptyWork = true)
            failure(FailureReason.STORAGE_FAILURE, discardSetup(coordinator, expected, target))
            assertEquals(expected.revision, f.controlRecord().revision)
            assertContentEquals(expected.payload.copyForCodec(), f.controlRecord().payload.copyForCodec())
            assertTrue(f.emptyWorkCalls.isEmpty()); assertTrue(f.credentialCalls.isEmpty()); assertTrue(f.dataVault.deleted.isEmpty())
            assertFalse(coordinatorValue(coordinator.restorationAllowed()))
            assertEquals(LocalRetirementPhase.COMPLETE, coordinatorValue(coordinator.recover()).phase)
            assertEquals(listOf(OWNER to ORIGIN), f.emptyWorkCalls)
            assertEquals(listOf(OWNER to CREDENTIAL), f.credentialCalls)
            assertTrue(f.dataVault.keys.isEmpty())
        }
    }

    @Test fun setupDiscardEveryLostControlAcknowledgementReconcilesExactPayloadWithoutRepeatingCleanup() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            coordinatorValue(f.data.activate(OWNER))
            val target = coordinatorValue(f.data.captureRetirement(OWNER))!!
            val before = f.controlConnection.writeCommits
            f.controlConnection.failEveryWriteCommitAfter = true
            assertEquals(LocalRetirementPhase.COMPLETE, coordinatorValue(discardSetup(
                f.coordinator(supportsEmptyWork = true), f.controlRecord(), target)).phase)
            assertEquals(5, f.controlConnection.writeCommits - before)
            assertEquals(5, f.controlConnection.faultsInjected)
            assertEquals(listOf(OWNER to ORIGIN), f.emptyWorkCalls)
            assertEquals(listOf(OWNER to CREDENTIAL), f.credentialCalls)
            assertEquals(1, f.dataVault.deleted.size)
        }
    }

    @Test fun setupDiscardCheckpointFailureRetriesOnlyTheUnacknowledgedExactEmptyStep() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            var failCheckpoint = true
            f.emptyWork = { _, _ ->
                if (failCheckpoint) { failCheckpoint = false; f.controlConnection.nextWriteCommitFault = CoordinatorCommitFault.BEFORE }
                PortResult.Value(Unit)
            }
            failure(FailureReason.STORAGE_FAILURE, discardSetup(f.coordinator(supportsEmptyWork = true), f.controlRecord(),
                target = null, credential = null))
            assertSetupPending(f.controlRecord(), emptySet(), credential = null, hasData = false)
            assertTrue(f.credentialCalls.isEmpty()); assertTrue(f.dataVault.deleted.isEmpty())
            f.reopen()
            assertEquals(LocalRetirementPhase.COMPLETE, coordinatorValue(f.coordinator(supportsEmptyWork = true).recover()).phase)
            assertEquals(List(2) { OWNER to ORIGIN }, f.emptyWorkCalls)
            assertTrue(f.workCalls.isEmpty())
            assertTrue(f.dataVault.keys.isEmpty())
        }
    }

    @Test fun setupDiscardCancellationRetainsDistinctBarrierAndRecoversAfterReopenWithoutCredentialsOrLease() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            coordinatorValue(f.data.activate(OWNER))
            val target = coordinatorValue(f.data.captureRetirement(OWNER))!!
            val entered = CompletableDeferred<Unit>()
            f.emptyWork = { _, _ -> assertSetupPending(f.controlRecord(), emptySet()); entered.complete(Unit); awaitCancellation() }
            val discarding = async { discardSetup(f.coordinator(supportsEmptyWork = true), f.controlRecord(), target) }
            entered.await()
            discarding.cancelAndJoin()
            assertTrue(discarding.isCancelled)
            assertTrue(f.credentialCalls.isEmpty()); assertTrue(f.dataVault.deleted.isEmpty())
            assertNull(f.boundary.current())
            f.reopen()
            f.emptyWork = { _, _ -> PortResult.Value(Unit) }
            assertFalse(coordinatorValue(f.coordinator().restorationAllowed()))
            assertEquals(LocalRetirementPhase.COMPLETE, coordinatorValue(f.coordinator(supportsEmptyWork = true).recover()).phase)
            assertEquals(List(2) { OWNER to ORIGIN }, f.emptyWorkCalls)
            assertEquals(listOf(OWNER to CREDENTIAL), f.credentialCalls)
            assertTrue(f.dataVault.keys.isEmpty())
        }
    }

    @Test fun setupDiscardWrongScopeOrUnauthenticatedDataTargetCannotTouchNativeResources() = runTest {
        for (wrongScope in listOf(false, true)) withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            coordinatorValue(f.data.activate(OWNER))
            val original = coordinatorValue(f.data.captureRetirement(OWNER))!!
            val target = if (wrongScope) original else StateRetirementTarget(original.copyForStorage().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            failure(if (wrongScope) FailureReason.STALE_SESSION else FailureReason.INVALID_DATA,
                discardSetup(f.coordinator(supportsEmptyWork = true), f.controlRecord(), target,
                    scope = if (wrongScope) OWNER.copy(actorId = "other-owner") else OWNER))
            assertTrue(f.emptyWorkCalls.isEmpty()); assertTrue(f.workCalls.isEmpty()); assertTrue(f.credentialCalls.isEmpty())
            assertTrue(f.dataVault.deleted.isEmpty())
            assertEquals(1, f.dataVault.keys.size)
        }
    }

    @Test fun setupDiscardRejectsActiveBoundaryAndChangedControlWithoutInstallingCleanupIntent() = runTest {
        for (change in listOf("lease", "revision", "bytes")) withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            val expected = f.controlRecord()
            val provided = if (change == "bytes") SessionControlRecord(expected.revision,
                bytes("{\"version\":1,\"state\":\"complete\",\"operationId\":\"$OPERATION2\"}")) else expected
            if (change == "lease") f.lease = f.boundary.activate(OWNER)
            if (change == "revision") coordinatorValue(f.control.compareAndSet(expected.revision, expected.payload))
            val before = f.controlRecord()
            val coordinator = f.coordinator(supportsEmptyWork = true)
            failure(FailureReason.CONFLICT, discardSetup(coordinator, provided, origin = null))
            assertContentEquals(before.payload.copyForCodec(), f.controlRecord().payload.copyForCodec())
            assertEquals(before.revision, f.controlRecord().revision)
            assertTrue(f.emptyWorkCalls.isEmpty()); assertTrue(f.credentialCalls.isEmpty()); assertTrue(f.dataVault.deleted.isEmpty())
            if (change == "lease") assertSame(f.lease, f.boundary.current())
            assertTrue(coordinatorValue(coordinator.restorationAllowed()))
        }
    }

    @Test fun setupDiscardRejectsPriorCompletedOperationAndExistingPendingBarrierRatherThanReplacingThem() = runTest {
        for (pending in listOf(false, true)) withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            val coordinator = f.coordinator(supportsEmptyWork = true)
            if (pending) {
                f.credentials = { _, _ -> PortResult.Failure(FailureReason.UNAVAILABLE) }
                assertEquals(LocalRetirementPhase.PENDING, coordinatorValue(discardSetup(coordinator, f.controlRecord(), origin = null)).phase)
            } else assertEquals(LocalRetirementPhase.COMPLETE, coordinatorValue(discardSetup(coordinator, f.controlRecord(), origin = null)).phase)
            val expected = f.controlRecord()
            val calls = f.credentialCalls.size
            failure(FailureReason.CONFLICT, discardSetup(coordinator, expected, origin = null))
            assertEquals(calls, f.credentialCalls.size)
            assertEquals(expected.revision, f.controlRecord().revision)
            assertContentEquals(expected.payload.copyForCodec(), f.controlRecord().payload.copyForCodec())
        }
    }

    @Test fun setupDiscardProcessIntentCannotBeReinterpretedAsOrdinaryLogoutWithTheSameIds() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            coordinatorValue(f.data.activate(OWNER))
            val target = coordinatorValue(f.data.captureRetirement(OWNER))!!
            val expected = f.controlRecord()
            f.controlConnection.nextWriteCommitFault = CoordinatorCommitFault.BEFORE
            val coordinator = f.coordinator(supportsEmptyWork = true)
            failure(FailureReason.STORAGE_FAILURE, discardSetup(coordinator, expected, target))
            val pending = """{"version":1,"state":"pending","operationId":"$OPERATION","scope":{"environment":"${OWNER.environment}","actorKind":"ACCOUNT","actorId":"${OWNER.actorId}"},"origin":"$ORIGIN","credentialIncarnation":"$CREDENTIAL","dataTarget":"${target.copyForStorage().joinToString("") { "%02x".format(it.toInt() and 255) }}","done":[]}"""
            coordinatorValue(f.control.compareAndSet(expected.revision, bytes(pending)))
            failure(FailureReason.CONFLICT, coordinator.recover())
            assertTrue(f.workCalls.isEmpty()); assertTrue(f.emptyWorkCalls.isEmpty()); assertTrue(f.credentialCalls.isEmpty())
            assertTrue(f.dataVault.deleted.isEmpty())
        }
    }

    @Test fun setupDiscardUnknownBarrierWithUnavailableReadbackPerformsNoCleanupUntilExactRecovery() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            val expected = f.controlRecord()
            f.controlConnection.nextWriteCommitFault = CoordinatorCommitFault.AFTER
            f.controlConnection.failReadAfterFault = true
            val coordinator = f.coordinator(supportsEmptyWork = true)
            assertIs<PortResult.Failure>(discardSetup(coordinator, expected, origin = null))
            assertTrue(f.credentialCalls.isEmpty()); assertTrue(f.emptyWorkCalls.isEmpty()); assertTrue(f.dataVault.deleted.isEmpty())
            assertSetupPending(f.controlRecord(), emptySet(), origin = null, hasData = false)
            assertEquals(LocalRetirementPhase.COMPLETE, coordinatorValue(coordinator.recover()).phase)
            assertEquals(listOf(OWNER to CREDENTIAL), f.credentialCalls)
        }
    }

    @Test fun setupDiscardDataCommitLossRecoversEmptyMissingKeyTargetWithoutRepeatingNativeSteps() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            coordinatorValue(f.data.activate(OWNER))
            val target = coordinatorValue(f.data.captureRetirement(OWNER))!!
            f.dataConnection.nextWriteCommitFault = CoordinatorCommitFault.BEFORE
            val result = coordinatorValue(discardSetup(f.coordinator(supportsEmptyWork = true), f.controlRecord(), target))
            assertEquals(setOf(RetirementStep.PRIVATE_DATA), result.remaining)
            assertTrue(f.dataVault.keys.isEmpty())
            assertSetupPending(f.controlRecord(), setOf("NATIVE_WORK", "CREDENTIALS"))
            f.reopen()
            assertEquals(LocalRetirementPhase.COMPLETE, coordinatorValue(f.coordinator(supportsEmptyWork = true).recover()).phase)
            assertEquals(listOf(OWNER to ORIGIN), f.emptyWorkCalls)
            assertEquals(listOf(OWNER to CREDENTIAL), f.credentialCalls)
            assertNull(coordinatorValue(f.data.inspectOwnerState(OWNER)).target)
            assertEquals(1, f.dataVault.creates)
        }
    }

    @Test fun setupDiscardEmptyWorkFailureRemainsPendingWhileIndependentOptionalCredentialCleanupCompletes() = runTest {
        for (throws in listOf(false, true)) withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            f.emptyWork = { _, _ -> if (throws) error("private-work-failure-details") else PortResult.Failure(FailureReason.CONFLICT) }
            val result = coordinatorValue(discardSetup(f.coordinator(supportsEmptyWork = true), f.controlRecord()))
            assertEquals(LocalRetirementPhase.PENDING, result.phase)
            assertEquals(setOf(RetirementStep.NATIVE_WORK), result.remaining)
            assertEquals(if (throws) FailureReason.STORAGE_FAILURE else FailureReason.CONFLICT, result.failures[RetirementStep.NATIVE_WORK])
            assertEquals(listOf(OWNER to CREDENTIAL), f.credentialCalls)
            assertTrue(f.workCalls.isEmpty())
            assertTrue(f.dataVault.deleted.isEmpty())
            assertSetupPending(f.controlRecord(), setOf("CREDENTIALS"), hasData = false)
            assertFalse(result.toString().contains("private-work"))
        }
    }

    @Test fun setupDiscardRecoveryCannotRetargetNewerSameScopePrivateOwner() = runTest {
        withCoordinatorFixture(StandardTestDispatcher(testScheduler)) { f ->
            coordinatorValue(f.data.activate(OWNER))
            val target = coordinatorValue(f.data.captureRetirement(OWNER))!!
            f.emptyWork = { _, _ -> PortResult.Failure(FailureReason.UNAVAILABLE) }
            assertEquals(LocalRetirementPhase.PENDING, coordinatorValue(discardSetup(
                f.coordinator(supportsEmptyWork = true), f.controlRecord(), target)).phase)
            val newStore = coordinatorValue(f.data.activate(OWNER))
            coordinatorValue(newStore.commit(OWNER, listOf(StoreMutation.Put(KEY, null, 1, bytes("private-state")))))
            val keys = f.dataVault.keys.keys.toSet()
            val emptyCalls = f.emptyWorkCalls.size
            failure(FailureReason.STALE_SESSION, f.coordinator(supportsEmptyWork = true).recover())
            assertEquals(keys, f.dataVault.keys.keys)
            assertEquals(emptyCalls, f.emptyWorkCalls.size)
            assertRecord(newStore)
            assertEquals(1, f.credentialCalls.size)
            assertNull(f.boundary.current())
        }
    }

    private suspend fun withCoordinatorFixture(dispatcher: CoroutineDispatcher, block: suspend (CoordinatorFixture) -> Unit) {
        val fixture = CoordinatorFixture(dispatcher)
        try { fixture.start(); block(fixture) } finally { fixture.close() }
    }
    companion object {
        private val OWNER = StorageScope("retirement-coordinator-test", ActorKind.ACCOUNT, "private-coordinator-owner-827e")
        private val KEY = RecordKey("coordinator-private-draft", "private-record-91fc")
        private const val ORIGIN = "123e4567-e89b-12d3-a456-426614174011"
        private const val ORIGIN2 = "123e4567-e89b-12d3-a456-426614174012"
        private const val CREDENTIAL = "123e4567-e89b-12d3-a456-426614174021"
        private const val CREDENTIAL2 = "123e4567-e89b-12d3-a456-426614174022"
        private const val OPERATION = "123e4567-e89b-12d3-a456-426614174031"
        private const val OPERATION2 = "123e4567-e89b-12d3-a456-426614174032"
        private fun bytes(text: String) = PrivateBytes(text.encodeToByteArray())
        private fun ledger(record: SessionControlRecord) = WireDocument.decode(record.payload.copyForCodec())
        private fun stringField(root: WireDocument, name: String): String = assertNotNull(assertIs<WireField.Value<WireDocument>>(root.field(name)).value.stringOrNull())
        private fun assertPending(record: SessionControlRecord, done: Set<String>) {
            val root = ledger(record)
            assertEquals("pending", stringField(root, "state"))
            assertEquals(OPERATION, stringField(root, "operationId"))
            assertEquals(ORIGIN, stringField(root, "origin"))
            assertEquals(CREDENTIAL, stringField(root, "credentialIncarnation"))
            val scope = assertIs<WireField.Value<WireDocument>>(root.field("scope")).value
            assertEquals(OWNER.actorId, stringField(scope, "actorId"))
            val values = assertNotNull(assertIs<WireField.Value<WireDocument>>(root.field("done")).value.elementsOrNull())
            assertEquals(done, values.map { assertNotNull(it.stringOrNull()) }.toSet())
        }
        private suspend fun assertRecord(store: PrivateStateStore) = assertContentEquals("private-state".encodeToByteArray(),
            assertNotNull(coordinatorValue(store.read(OWNER, KEY))).payload.copyForCodec())
        private fun failure(reason: FailureReason, result: PortResult<*>) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)

        /** Invoke the session-internal confirmation boundary without widening production access. */
        private suspend fun discardSetup(coordinator: LocalRetirementCoordinator, expected: SessionControlRecord,
            target: StateRetirementTarget? = null, origin: String? = ORIGIN, credential: String? = CREDENTIAL,
            scope: StorageScope = OWNER, operation: String = OPERATION): PortResult<LocalRetirementProgress> {
            val type = Class.forName("com.feedme.session.RetirementState\$SetupDiscardPending")
            val state = type.getDeclaredConstructor(String::class.java, StorageScope::class.java, String::class.java,
                String::class.java, StateRetirementTarget::class.java, Set::class.java)
                .newInstance(operation, scope, origin, credential, target, emptySet<RetirementStep>())
            val method = LocalRetirementCoordinator::class.java.declaredMethods.single {
                it.name.startsWith("discardSetup") && it.parameterCount == 3
            }
            return suspendCoroutineUninterceptedOrReturn { continuation ->
                try { method.invoke(coordinator, state, expected, continuation) }
                catch (failure: InvocationTargetException) { throw failure.targetException }
            }
        }

        private fun assertSetupPending(record: SessionControlRecord, done: Set<String>, origin: String? = ORIGIN,
            credential: String? = CREDENTIAL, hasData: Boolean = true) {
            val root = ledger(record)
            assertEquals("setup-discard-pending", stringField(root, "state"))
            assertEquals(OPERATION, stringField(root, "operationId"))
            if (origin == null) assertSame(WireField.Null, root.field("origin")) else assertEquals(origin, stringField(root, "origin"))
            if (credential == null) assertSame(WireField.Null, root.field("credentialIncarnation")) else assertEquals(credential, stringField(root, "credentialIncarnation"))
            if (!hasData) assertSame(WireField.Null, root.field("dataTarget"))
            val values = assertNotNull(assertIs<WireField.Value<WireDocument>>(root.field("done")).value.elementsOrNull())
            assertEquals(done, values.map { assertNotNull(it.stringOrNull()) }.toSet())
        }

        private class CoordinatorFixture(val dispatcher: CoroutineDispatcher) {
            private val directory = Files.createTempDirectory("feedme-local-retirement-integration-")
            private val dataFile = directory.resolve("data.sqlite")
            private val controlFile = directory.resolve("control.sqlite")
            val dataVault = CoordinatorVault()
            val controlVault = CoordinatorVault()
            lateinit var data: EncryptedStateDatabase
            lateinit var control: EncryptedSessionControlStore
            lateinit var dataConnection: CoordinatorConnection
            lateinit var controlConnection: CoordinatorConnection
            private val openedData = mutableListOf<EncryptedStateDatabase>()
            private val openedControl = mutableListOf<EncryptedSessionControlStore>()
            var boundary = SessionBoundary()
            lateinit var lease: SessionLease
            lateinit var store: PrivateStateStore
            val workCalls = mutableListOf<Pair<StorageScope, String>>()
            val credentialCalls = mutableListOf<Pair<StorageScope, String>>()
            val emptyWorkCalls = mutableListOf<Pair<StorageScope, String>>()
            var work: suspend (StorageScope, String) -> PortResult<Unit> = { _, _ -> PortResult.Value(Unit) }
            var emptyWork: suspend (StorageScope, String) -> PortResult<Unit> = { _, _ -> PortResult.Value(Unit) }
            var credentials: suspend (StorageScope, String) -> PortResult<Unit> = { _, _ -> PortResult.Value(Unit) }
            suspend fun start() {
                data = openData()
                control = coordinatorValue(openControl(true))
            }
            suspend fun activate() {
                store = coordinatorValue(data.activate(OWNER))
                coordinatorValue(store.commit(OWNER, listOf(StoreMutation.Put(KEY, null, 1, bytes("private-state")))))
                lease = boundary.activate(OWNER)
            }
            fun coordinator(controlOverride: SessionControlStore = control, supportsEmptyWork: Boolean = false) = LocalRetirementCoordinator(controlOverride, data, boundary, dispatcher,
                CredentialRetirementPort { scope, incarnation -> credentialCalls += scope to incarnation; credentials(scope, incarnation) },
                if (supportsEmptyWork) object : EmptySessionWorkRetirementPort {
                    override suspend fun retire(scope: StorageScope, originBinding: String): PortResult<Unit> {
                        workCalls += scope to originBinding
                        return work(scope, originBinding)
                    }
                    override suspend fun retireEmpty(scope: StorageScope, originBinding: String): PortResult<Unit> {
                        emptyWorkCalls += scope to originBinding
                        return emptyWork(scope, originBinding)
                    }
                } else SessionWorkRetirementPort { scope, origin -> workCalls += scope to origin; work(scope, origin) })
            suspend fun controlRecord(): SessionControlRecord = assertNotNull(coordinatorValue(control.read()))
            suspend fun reopen() {
                coordinatorValue(control.close())
                coordinatorValue(data.close())
                boundary = SessionBoundary()
                data = openData()
                control = coordinatorValue(openControl(false))
            }
            suspend fun openExistingControl(): PortResult<EncryptedSessionControlStore> = openControl(false)
            private suspend fun openData(): EncryptedStateDatabase {
                dataConnection = CoordinatorConnection(BundledSQLiteDriver().open(dataFile.toString()))
                return coordinatorValue(EncryptedStateDatabase.open(dataConnection, dataVault, dispatcher)).also { openedData += it }
            }
            private suspend fun openControl(initialize: Boolean): PortResult<EncryptedSessionControlStore> {
                controlConnection = CoordinatorConnection(BundledSQLiteDriver().open(controlFile.toString()))
                val db = coordinatorValue(EncryptedStateDatabase.open(controlConnection, controlVault, dispatcher))
                return EncryptedSessionControlStore.open(db, initialize).also { if (it is PortResult.Value) openedControl += it.value }
            }
            fun rawControl(sql: String) { BundledSQLiteDriver().open(controlFile.toString()).use { connection ->
                connection.prepare(sql).use { statement -> while (statement.step()) Unit }
            } }
            fun assertControlHasNoPlaintext(markers: List<String>) {
                val raw = Files.readAllBytes(controlFile)
                for (marker in markers) assertFalse(raw.coordinatorContains(marker.encodeToByteArray()), "Private target visible in control SQLite")
            }
            suspend fun close() {
                try {
                    openedControl.forEach { it.close() }
                    openedData.forEach { it.close() }
                } finally { Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
            }
        }
    }
}

private fun <T> coordinatorValue(result: PortResult<T>): T = when (result) {
    is PortResult.Value -> result.value
    is PortResult.Failure -> fail("Expected coordinator success, got ${result.reason}")
}

private enum class CoordinatorCommitFault { BEFORE, AFTER }
private class CoordinatorConnection(private val delegate: SQLiteConnection) : SQLiteConnection by delegate {
    var nextWriteCommitFault: CoordinatorCommitFault? = null
    var failEveryWriteCommitAfter = false
    var faultsInjected = 0
    var writeCommits = 0
    var faultOnWriteCommit: Int? = null
    var failReadAfterFault = false
    var failNextRead = false
    var beforeStep: (() -> Unit)? = null
    private var writeTransaction = false
    override fun prepare(sql: String): SQLiteStatement {
        val statement = delegate.prepare(sql)
        val normalized = sql.trim().uppercase()
        return object : SQLiteStatement by statement {
            override fun step(): Boolean {
                beforeStep?.invoke()
                if (normalized.startsWith("BEGIN")) writeTransaction = normalized == "BEGIN IMMEDIATE"
                if (!writeTransaction && normalized.startsWith("SELECT") && failNextRead) {
                    failNextRead = false
                    error("Injected control record read failure")
                }
                val commit = normalized.startsWith("COMMIT")
                if (commit && writeTransaction) writeCommits++
                val fault = if (commit && writeTransaction) {
                    nextWriteCommitFault.also { nextWriteCommitFault = null }
                        ?: if (failEveryWriteCommitAfter || faultOnWriteCommit == writeCommits) CoordinatorCommitFault.AFTER else null
                } else null
                if (fault != null) faultsInjected++
                if (fault == CoordinatorCommitFault.BEFORE) error("Injected exact control CAS precommit failure")
                val value = statement.step()
                if (commit || normalized.startsWith("ROLLBACK")) writeTransaction = false
                if (fault == CoordinatorCommitFault.AFTER) {
                    if (failReadAfterFault) failNextRead = true
                    error("Injected exact control CAS committed response loss")
                }
                return value
            }
        }
    }
}

/** Separate instances are essential: retiring owner keys cannot destroy the control-plane key. */
private class CoordinatorVault : StateVault {
    private val random = SecureRandom()
    private val indexKey = SecretKeySpec(ByteArray(32).also(random::nextBytes), "HmacSHA256")
    val keys = mutableMapOf<String, SecretKey>()
    val deleted = mutableListOf<String>()
    var creates = 0
    var beforeDelete: (() -> Unit)? = null
    override fun index(input: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(indexKey); doFinal(input) }
    override fun createOwnerKey(): String {
        creates++
        return UUID.randomUUID().toString().replace("-", "").also { id -> keys[id] = KeyGenerator.getInstance("AES").apply { init(256, random) }.generateKey() }
    }
    override fun hasOwnerKey(keyId: String): Boolean = keyId in keys
    override fun seal(keyId: String, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keys[keyId] ?: throw StateVaultException(), GCMParameterSpec(128, nonce))
        cipher.updateAAD(associatedData)
        return byteArrayOf(1) + nonce + cipher.doFinal(plaintext)
    }
    override fun open(keyId: String, ciphertext: ByteArray, associatedData: ByteArray): ByteArray = try {
        if (ciphertext.size < 29 || ciphertext[0] != 1.toByte()) throw StateVaultException()
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, keys[keyId] ?: throw StateVaultException(), GCMParameterSpec(128, ciphertext.copyOfRange(1, 13)))
            updateAAD(associatedData)
            doFinal(ciphertext, 13, ciphertext.size - 13)
        }
    } catch (_: Exception) { throw StateVaultException() }
    override fun deleteOwnerKey(keyId: String) { beforeDelete?.invoke(); deleted += keyId; keys.remove(keyId) }
}

private fun ByteArray.coordinatorContains(needle: ByteArray): Boolean {
    if (needle.size > size) return false
    for (start in 0..size - needle.size) if (needle.indices.all { this[start + it] == needle[it] }) return true
    return false
}
