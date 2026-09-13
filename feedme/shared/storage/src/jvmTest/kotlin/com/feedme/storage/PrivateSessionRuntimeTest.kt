package com.feedme.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireField
import com.feedme.core.ports.*
import com.feedme.session.*
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Comparator
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Three actual encrypted SQLite files; identity verification/credentials/native effects are fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class PrivateSessionRuntimeTest {
    @Test fun openAndRecoveryDoNotSignInOrInitializeAnOwnerBeforeExplicitVerifiedCreate() = runTest {
        fixture { f ->
            assertEquals(PrivateSessionPhase.STARTUP, f.runtime.phase())
            assertNull(f.runtime.currentAccess())
            assertNull(f.boundary.current())
            failure(FailureReason.CONFLICT, f.runtime.create())
            assertEquals(0, f.acquireCalls)
            assertEquals(PrivateSessionPhase.SIGNED_OUT, value(f.runtime.recover()))
            assertEquals(0, f.acquireCalls)
            assertEquals(0, f.restoreCalls)
            assertEquals(0, f.credentials.creates)
            assertEquals(0, f.dataVault.creates)
            assertEquals("idle", field(value(f.workControl.read())!!.payload, "state"))
        }
    }

    @Test fun verifiedCreatePersistsExactEncryptedBindingAndPublishesOnlyBoundedLeasedViews() = runTest {
        fixture { f ->
            val access = f.create()
            assertEquals(PrivateSessionPhase.ACTIVE, f.runtime.phase())
            assertSame(access, f.runtime.currentAccess())
            assertSame(access.lease, f.boundary.current())
            assertEquals(ACCOUNT, access.scope)
            assertEquals(PrivateSessionAccessMode.ONLINE, access.mode)
            assertEquals(1, f.acquireCalls)
            assertEquals(1, f.credentials.creates)
            assertEquals(1, f.dataVault.creates)
            val binding = f.binding(ACCOUNT)
            assertEquals(1, binding.revision)
            assertEquals(CONFIGURATION, field(binding.payload, "configurationBinding"))
            assertEquals(access.originBinding, field(binding.payload, "originBinding"))
            assertEquals(f.credentials.current!!.incarnation, field(binding.payload, "credentialIncarnation"))
            assertEquals(hex(value(f.data.captureRetirement(ACCOUNT))!!.copyForStorage()), field(binding.payload, "dataTarget"))
            val transported = assertIs<StoredCredentials.Account>(value(access.credentials.read(ACCOUNT)))
            assertNull(transported.refreshToken)
            assertEquals(DEVICE, transported.deviceSessionId!!.use { it })
            val marker = "private-runtime-draft-2bfe80"
            value(access.store.commit(ACCOUNT, listOf(StoreMutation.Put(DRAFT, null, 1, bytes(marker)))))
            assertEquals(marker, value(access.store.read(ACCOUNT, DRAFT))!!.payload.copyForCodec().decodeToString())
            for (view in listOf(access, access.credentials, binding.payload)) {
                assertFalse(view.toString().contains(ACCOUNT.actorId))
                assertFalse(view.toString().contains(access.originBinding))
                assertFalse(view.toString().contains("refresh-runtime-secret"))
            }
            f.closeRuntimeAndStores()
            f.assertNoPlaintext(listOf(marker, ACCOUNT.actorId, access.originBinding, "access-runtime-secret", "refresh-runtime-secret"))
        }
    }

    @Test fun closeReopenRestoreKeepsOriginalOriginDataAndCredentialIncarnationsWithoutNewWrites() = runTest {
        fixture { f ->
            val first = f.create()
            value(first.store.commit(ACCOUNT, listOf(StoreMutation.Put(DRAFT, null, 1, bytes("offline-owner-draft")))))
            val binding = f.binding(ACCOUNT)
            val oldCredential = f.credentials.current!!
            val work = value(f.workControl.read())!!
            f.reopen()
            assertEquals(PrivateSessionPhase.STARTUP, f.runtime.phase())
            assertEquals(PrivateSessionPhase.RESTORE_REQUIRED, value(f.runtime.recover()))
            assertNull(f.boundary.current())
            val restored = value(f.runtime.restore())
            assertNotSame(first.lease, restored.lease)
            assertEquals(first.originBinding, restored.originBinding)
            assertEquals(oldCredential.incarnation, f.credentials.current!!.incarnation)
            assertEquals(oldCredential.revision, f.credentials.current!!.revision)
            assertEquals(1, f.credentials.creates)
            assertEquals(1, f.dataVault.creates)
            assertEquals(1, f.acquireCalls)
            assertEquals(1, f.restoreCalls)
            assertContentEquals(binding.payload.copyForCodec(), f.binding(ACCOUNT).payload.copyForCodec())
            assertEquals(work.revision, value(f.workControl.read())!!.revision)
            assertEquals("offline-owner-draft", value(restored.store.read(ACCOUNT, DRAFT))!!.payload.copyForCodec().decodeToString())
            assertIs<PortResult.Failure>(first.store.read(ACCOUNT, DRAFT))
            assertIs<PortResult.Failure>(first.credentials.read(ACCOUNT))
        }
    }

    @Test fun explicitRetirementCancelsExactWorkErasesOwnerAndCredentialsAndAllowsFreshLogin() = runTest {
        fixture { f ->
            val old = f.create()
            value(old.store.commit(ACCOUNT, listOf(StoreMutation.Put(DRAFT, null, 1, bytes("old-owner-draft")))))
            val timer = value(f.runtime.install(old, NativeWorkKind.TIMER, "timer") { PortResult.Value(Unit) })
            val oldIncarnation = f.credentials.current!!.incarnation
            val result = value(f.runtime.retire(old, OPERATION))
            assertEquals(LocalRetirementPhase.COMPLETE, result.phase)
            assertEquals(PrivateSessionPhase.SIGNED_OUT, f.runtime.phase())
            assertNull(f.boundary.current())
            assertNull(f.runtime.currentAccess())
            assertEquals(listOf(timer.id), f.cancelled.map { it.id })
            assertEquals(listOf(ACCOUNT to oldIncarnation), f.credentials.retired)
            assertNull(f.credentials.current)
            assertTrue(f.dataVault.keys.isEmpty())
            assertNull(value(f.data.resume(ACCOUNT)))
            assertEquals("idle", field(value(f.workControl.read())!!.payload, "state"))
            failure(FailureReason.STALE_SESSION, old.store.read(ACCOUNT, DRAFT))
            failure(FailureReason.STALE_SESSION, old.credentials.read(ACCOUNT))
            val fresh = value(f.runtime.create())
            assertNotEquals(old.originBinding, fresh.originBinding)
            assertNotEquals(oldIncarnation, f.credentials.current!!.incarnation)
            failure(FailureReason.STALE_SESSION, f.runtime.retire(old, UUID.randomUUID().toString()))
            assertSame(fresh, f.runtime.currentAccess())
            assertNull(value(fresh.store.read(ACCOUNT, DRAFT)))
            assertEquals(1, f.credentials.retired.size)
        }
    }

    @Test fun reservedActivationRecordCannotBeReadOverwrittenDeletedOrIncludedInMixedBatch() = runTest {
        fixture { f ->
            val access = f.create()
            val original = f.binding(ACCOUNT)
            failure(FailureReason.FORBIDDEN, access.store.read(ACCOUNT, BINDING))
            failure(FailureReason.FORBIDDEN, access.store.commit(ACCOUNT, listOf(StoreMutation.Put(BINDING, original.revision, 1, bytes("replacement")))))
            failure(FailureReason.FORBIDDEN, access.store.commit(ACCOUNT, listOf(StoreMutation.Delete(BINDING, original.revision))))
            failure(FailureReason.FORBIDDEN, access.store.commit(ACCOUNT, listOf(
                StoreMutation.Put(DRAFT, null, 1, bytes("must-not-partially-commit")),
                StoreMutation.Delete(BINDING, original.revision),
            )))
            assertNull(value(access.store.read(ACCOUNT, DRAFT)))
            assertContentEquals(original.payload.copyForCodec(), f.binding(ACCOUNT).payload.copyForCodec())
            failure(FailureReason.NOT_CONFIGURED, access.store.eraseScope(ACCOUNT))
            failure(FailureReason.NOT_CONFIGURED, access.credentials.replace(ACCOUNT, account()))
            failure(FailureReason.NOT_CONFIGURED, access.credentials.erase(ACCOUNT))
            assertEquals(1, f.credentials.creates)
        }
    }

    @Test fun callerBatchMutationDuringControlReadCannotInjectReservedBindingDeletion() = runTest {
        fixture { f ->
            val access = f.create()
            val binding = f.binding(ACCOUNT)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var pause = true
            f.afterControlRead = { if (pause) { pause = false; entered.complete(Unit); release.await() } }
            val batch = mutableListOf<StoreMutation>(StoreMutation.Put(DRAFT, null, 1, bytes("captured-original-intent")))
            val writing = async { access.store.commit(ACCOUNT, batch) }
            entered.await()
            batch.clear()
            batch += StoreMutation.Delete(BINDING, binding.revision)
            release.complete(Unit)
            value(writing.await())
            assertEquals("captured-original-intent", value(access.store.read(ACCOUNT, DRAFT))!!.payload.copyForCodec().decodeToString())
            assertContentEquals(binding.payload.copyForCodec(), f.binding(ACCOUNT).payload.copyForCodec())
        }
    }

    @Test fun offlineRestorationKeepsPrivateDraftsAndTimersButExposesNoTransportTokensOrWorkers() = runTest {
        fixture { f ->
            val original = f.create()
            value(original.store.commit(ACCOUNT, listOf(StoreMutation.Put(DRAFT, null, 1, bytes("offline-progress")))))
            val worker = value(f.runtime.install(original, NativeWorkKind.WORKER, "worker") { PortResult.Value(Unit) })
            f.credentials.replaceForTest(account(expires = 0))
            f.restoreMode = PrivateSessionAccessMode.OFFLINE_PRIVATE
            f.reopen()
            assertEquals(PrivateSessionPhase.RESTORE_REQUIRED, value(f.runtime.recover()))
            val offline = value(f.runtime.restore())
            assertEquals(PrivateSessionAccessMode.OFFLINE_PRIVATE, offline.mode)
            assertEquals(original.originBinding, offline.originBinding)
            assertEquals("offline-progress", value(offline.store.read(ACCOUNT, DRAFT))!!.payload.copyForCodec().decodeToString())
            val secureReads = f.credentials.reads
            failure(FailureReason.OFFLINE, offline.credentials.read(ACCOUNT))
            assertEquals(secureReads, f.credentials.reads)
            failure(FailureReason.OFFLINE, f.runtime.install(offline, NativeWorkKind.WORKER, "new-worker") { fail("Offline worker installer ran") })
            failure(FailureReason.STALE_SESSION, f.runtime.runLocalEffect(worker) { fail("Offline private mode ran worker effect") })
            val timer = value(f.runtime.install(offline, NativeWorkKind.TIMER, "offline-timer") { PortResult.Value(Unit) })
            var effects = 0
            value(f.runtime.runLocalEffect(timer) { effects++; PortResult.Value(Unit) })
            assertEquals(1, effects)
            assertEquals(1, f.dataVault.creates)
            assertEquals(1, f.credentials.creates)
        }
    }

    @Test fun configurationCredentialWorkAndDataIncarnationMismatchRejectBeforeCredentialReadOrVerifier() = runTest {
        for (mismatch in listOf("configuration", "credential", "work", "data")) fixture { f ->
            val old = f.create()
            val binding = f.binding(ACCOUNT)
            value(f.runtime.close())
            when (mismatch) {
                "credential" -> f.credentials.replaceForTest(account(), uuid(9000))
                "work" -> {
                    val record = value(f.workControl.read())!!
                    val changed = record.payload.copyForCodec().decodeToString().replace(old.originBinding, uuid(9001))
                    value(f.workControl.compareAndSet(record.revision, bytes(changed)))
                }
                "data" -> {
                    val owner = value(f.data.resume(ACCOUNT))!!
                    value(owner.eraseScope(ACCOUNT))
                    val replacement = value(f.data.activate(ACCOUNT))
                    // Replaying an old encrypted binding into a new active generation cannot
                    // turn its authentic-but-retired data target into restoration authority.
                    value(replacement.commit(ACCOUNT, listOf(StoreMutation.Put(BINDING, null, 1, binding.payload))))
                }
            }
            val creates = f.dataVault.creates
            f.reopen(if (mismatch == "configuration") OTHER_CONFIGURATION else CONFIGURATION)
            assertEquals(PrivateSessionPhase.RESTORE_REQUIRED, value(f.runtime.recover()))
            val secureReads = f.credentials.reads
            failure(FailureReason.STALE_SESSION, f.runtime.restore())
            assertEquals(secureReads, f.credentials.reads)
            assertEquals(0, f.restoreCalls)
            assertNull(f.runtime.currentAccess())
            assertNull(f.boundary.current())
            assertEquals(PrivateSessionPhase.RECOVERY_REQUIRED, f.runtime.phase())
            assertEquals(creates, f.dataVault.creates)
            assertEquals(1, f.credentials.creates)
            assertTrue(f.cancelled.isEmpty())
        }
    }

    @Test fun missingCorruptOrFutureSchemaBindingCannotBeRebuiltFromMatchingIndependentScopes() = runTest {
        for (damage in listOf("missing", "payload", "schema")) fixture { f ->
            f.create()
            val binding = f.binding(ACCOUNT)
            val rawOwner = value(f.data.resume(ACCOUNT))!!
            when (damage) {
                "missing" -> value(rawOwner.commit(ACCOUNT, listOf(StoreMutation.Delete(BINDING, binding.revision))))
                "payload" -> f.replaceBinding(ACCOUNT, bytes("{\"version\":2,\"private\":\"not-authority\"}"))
                "schema" -> f.replaceBinding(ACCOUNT, binding.payload, 2)
            }
            f.reopen()
            assertEquals(PrivateSessionPhase.RESTORE_REQUIRED, value(f.runtime.recover()))
            val secureReads = f.credentials.reads
            failure(FailureReason.STORAGE_FAILURE, f.runtime.restore())
            assertEquals(0, f.restoreCalls)
            assertEquals(secureReads, f.credentials.reads)
            assertNull(f.boundary.current())
            assertEquals(PrivateSessionPhase.RECOVERY_REQUIRED, f.runtime.phase())
            assertEquals(1, f.credentials.creates)
            assertEquals(1, f.dataVault.creates)
            assertTrue(f.credentials.retired.isEmpty())
        }
    }

    @Test fun existingGuestIsNeverOverwrittenByAnotherAcquireOrRestorationShortcut() = runTest {
        fixture { f ->
            f.acquire = { PortResult.Value(guest()) }
            val guest = f.create()
            assertEquals(GUEST, guest.scope)
            val incarnation = f.credentials.current!!.incarnation
            f.acquire = { PortResult.Value(account()) }
            failure(FailureReason.CONFLICT, f.runtime.create())
            f.reopen()
            assertEquals(PrivateSessionPhase.RESTORE_REQUIRED, value(f.runtime.recover()))
            failure(FailureReason.CONFLICT, f.runtime.create())
            assertEquals(1, f.acquireCalls)
            assertEquals(1, f.credentials.creates)
            assertEquals(incarnation, f.credentials.current!!.incarnation)
            assertEquals(GUEST, value(f.runtime.restore()).scope)
            assertTrue(f.credentials.retired.isEmpty())
        }
    }

    @Test fun pendingRetirementBlocksCreateRestoreAndRecoversAfterPrivateDataWasAlreadyErased() = runTest {
        fixture { f ->
            val access = f.create()
            val ticket = value(f.runtime.install(access, NativeWorkKind.TIMER, "timer") { PortResult.Value(Unit) })
            f.cancel = { PortResult.Failure(FailureReason.UNAVAILABLE) }
            val pending = value(f.runtime.retire(access, OPERATION))
            assertEquals(LocalRetirementPhase.PENDING, pending.phase)
            assertEquals(setOf(RetirementStep.NATIVE_WORK), pending.remaining)
            assertNull(f.credentials.current)
            assertTrue(f.dataVault.keys.isEmpty())
            f.reopen()
            assertEquals(PrivateSessionPhase.RECOVERY_REQUIRED, value(f.runtime.recover()))
            failure(FailureReason.CONFLICT, f.runtime.create())
            failure(FailureReason.CONFLICT, f.runtime.restore())
            assertEquals(1, f.acquireCalls)
            assertEquals(0, f.restoreCalls)
            assertEquals(1, f.dataVault.creates)
            assertEquals(1, f.credentials.creates)
            f.cancel = { PortResult.Value(Unit) }
            assertEquals(PrivateSessionPhase.SIGNED_OUT, value(f.runtime.recover()))
            assertEquals(listOf(ticket.id, ticket.id, ticket.id), f.cancelled.map { it.id })
            assertNull(value(f.data.resume(ACCOUNT)))
            assertNull(f.boundary.current())
            assertEquals(1, f.credentials.retired.size)
        }
    }

    @Test fun cancellationOrCloseDuringVerificationCannotPublishItsLateResultOrWriteInitialState() = runTest {
        for (closing in listOf(false, true)) fixture { f ->
            value(f.runtime.recover())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.acquire = { entered.complete(Unit); release.await(); PortResult.Value(account()) }
            val creating = async { f.runtime.create() }
            entered.await()
            assertEquals(PrivateSessionPhase.VERIFYING, f.runtime.phase())
            assertNull(f.runtime.currentAccess())
            if (closing) value(f.runtime.close()) else value(f.runtime.cancelVerification())
            release.complete(Unit)
            assertIs<PortResult.Failure>(creating.await())
            assertEquals(if (closing) PrivateSessionPhase.CLOSED else PrivateSessionPhase.STARTUP, f.runtime.phase())
            assertNull(f.boundary.current())
            assertEquals(0, f.credentials.creates)
            assertEquals(0, f.dataVault.creates)
            assertEquals("idle", field(value(f.workControl.read())!!.payload, "state"))
            if (!closing) assertEquals(PrivateSessionPhase.SIGNED_OUT, value(f.runtime.recover()))
        }
    }

    @Test fun cancellationAfterCredentialCommitLeavesPartialSetupBlockedWithoutAutomaticCompletionOrErase() = runTest {
        fixture { f ->
            value(f.runtime.recover())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.credentials.afterCreate = { entered.complete(Unit); release.await() }
            val creating = async { f.runtime.create() }
            entered.await()
            value(f.runtime.cancelVerification())
            release.complete(Unit)
            failure(FailureReason.STALE_SESSION, creating.await())
            assertEquals(PrivateSessionPhase.RECOVERY_REQUIRED, f.runtime.phase())
            assertNotNull(f.credentials.current)
            assertEquals(1, f.credentials.creates)
            assertEquals(0, f.dataVault.creates)
            assertEquals("idle", field(value(f.workControl.read())!!.payload, "state"))
            assertEquals("credential-create-pending", field(value(f.control.read())!!.payload, "state"))
            f.reopen()
            failure(FailureReason.CONFLICT, f.runtime.recover())
            assertEquals(PrivateSessionPhase.RECOVERY_REQUIRED, f.runtime.phase())
            failure(FailureReason.CONFLICT, f.runtime.create())
            failure(FailureReason.CONFLICT, f.runtime.restore())
            assertEquals(1, f.acquireCalls)
            assertTrue(f.credentials.retired.isEmpty())
        }
    }

    @Test fun lostBindingCommitAcknowledgementReadsBackExactRecordWithoutAnotherCredentialOrOrigin() = runTest {
        fixture { f ->
            f.nextIdHook = { f.dataConnection.failNextWriteCommitAfter = true; f.nextIdHook = { } }
            val access = f.create()
            assertEquals(PrivateSessionPhase.ACTIVE, f.runtime.phase())
            assertEquals(access.originBinding, field(f.binding(ACCOUNT).payload, "originBinding"))
            assertEquals(1, f.binding(ACCOUNT).revision)
            assertEquals(1, f.credentials.creates)
            assertEquals(1, f.dataVault.creates)
            assertEquals(2, value(f.workControl.read())!!.revision)
            f.reopen()
            value(f.runtime.recover())
            assertEquals(access.originBinding, value(f.runtime.restore()).originBinding)
            assertEquals(1, f.credentials.creates)
        }
    }

    @Test fun failedBindingCommitDoesNotPublishOrAutoHealAnAlreadyCreatedOwnerOnRestart() = runTest {
        fixture { f ->
            value(f.runtime.recover())
            f.nextIdHook = { f.dataConnection.failNextWriteCommitBefore = true; f.nextIdHook = { } }
            failure(FailureReason.STORAGE_FAILURE, f.runtime.create())
            assertEquals(PrivateSessionPhase.RECOVERY_REQUIRED, f.runtime.phase())
            assertNull(f.runtime.currentAccess())
            assertNull(f.boundary.current())
            assertEquals(1, f.credentials.creates)
            assertEquals(1, f.dataVault.creates)
            val owner = value(f.data.resume(ACCOUNT))!!
            assertNull(value(owner.read(ACCOUNT, BINDING)))
            f.reopen()
            assertEquals(PrivateSessionPhase.RESTORE_REQUIRED, value(f.runtime.recover()))
            failure(FailureReason.STORAGE_FAILURE, f.runtime.restore())
            assertEquals(0, f.restoreCalls)
            assertEquals(1, f.credentials.creates)
            assertEquals(1, f.dataVault.creates)
            assertTrue(f.credentials.retired.isEmpty())
        }
    }

    @Test fun retirementDuringDelayedWorkAdmissionClosesLeaseBeforeRegistryWaitAndDoesNotDeadlock() = runTest {
        fixture { f ->
            val access = f.create()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var pause = true
            f.afterControlRead = { if (pause) { pause = false; entered.complete(Unit); release.await() } }
            val installing = async { f.runtime.install(access, NativeWorkKind.TIMER, "timer") { fail("Old admission installed native work") } }
            entered.await()
            val retiring = async { f.runtime.retire(access, OPERATION) }
            runCurrent()
            assertNull(f.boundary.current())
            assertNull(f.runtime.currentAccess())
            assertFalse(retiring.isCompleted)
            release.complete(Unit)
            failure(FailureReason.STALE_SESSION, installing.await())
            assertEquals(LocalRetirementPhase.COMPLETE, value(retiring.await()).phase)
            assertTrue(f.cancelled.isEmpty())
            assertEquals(PrivateSessionPhase.SIGNED_OUT, f.runtime.phase())
        }
    }

    @Test fun delayedCredentialReadCannotPublishOldSecretsAfterLocalRetirement() = runTest {
        fixture { f ->
            val access = f.create()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.credentials.afterRead = { entered.complete(Unit); release.await() }
            val reading = async { access.credentials.read(ACCOUNT) }
            entered.await()
            assertEquals(LocalRetirementPhase.COMPLETE, value(f.runtime.retire(access, OPERATION)).phase)
            assertNull(f.boundary.current())
            release.complete(Unit)
            failure(FailureReason.STALE_SESSION, reading.await())
            assertNull(f.credentials.current)
        }
    }

    @Test fun closingDuringBlockedNativeRetirementCannotBeOverwrittenByItsLateCompletion() = runTest {
        fixture { f ->
            val access = f.create()
            value(f.runtime.install(access, NativeWorkKind.TIMER, "timer") { PortResult.Value(Unit) })
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.cancel = { entered.complete(Unit); release.await(); PortResult.Value(Unit) }
            val retiring = async { f.runtime.retire(access, OPERATION) }
            entered.await()
            val closing = async { f.runtime.close() }
            runCurrent()
            assertEquals(PrivateSessionPhase.CLOSED, f.runtime.phase())
            assertFalse(closing.isCompleted)
            release.complete(Unit)
            assertIs<PortResult.Failure>(retiring.await())
            value(closing.await())
            assertEquals(PrivateSessionPhase.CLOSED, f.runtime.phase())
            assertNull(f.runtime.currentAccess())
            assertNull(f.boundary.current())
            failure(FailureReason.STORAGE_FAILURE, f.runtime.create())
            assertEquals(1, f.acquireCalls)
        }
    }

    @Test fun missingRegisteredDeviceSessionOrExistingUnboundDataOwnerCannotCreateFreshAuthority() = runTest {
        for (existingOwner in listOf(false, true)) fixture { f ->
            if (existingOwner) value(f.data.activate(ACCOUNT))
            else f.acquire = { PortResult.Value(account().copy(deviceSessionId = null)) }
            value(f.runtime.recover())
            failure(if (existingOwner) FailureReason.CONFLICT else FailureReason.NOT_CONFIGURED, f.runtime.create())
            assertNull(f.boundary.current())
            assertNull(f.runtime.currentAccess())
            assertEquals(0, f.credentials.creates)
            assertEquals(if (existingOwner) 1 else 0, f.dataVault.creates)
            assertEquals("idle", field(value(f.workControl.read())!!.payload, "state"))
        }
    }

    @Test fun restorationVerificationFailurePreservesPrivateBindingAndRequiresExplicitRecovery() = runTest {
        fixture { f ->
            val initial = f.create()
            val binding = f.binding(ACCOUNT)
            f.reopen()
            value(f.runtime.recover())
            f.restore = { PortResult.Failure(FailureReason.UNAUTHENTICATED) }
            failure(FailureReason.UNAUTHENTICATED, f.runtime.restore())
            assertNull(f.runtime.currentAccess())
            assertNull(f.boundary.current())
            assertEquals(PrivateSessionPhase.RECOVERY_REQUIRED, f.runtime.phase())
            assertEquals(initial.originBinding, field(value(f.workControl.read())!!.payload, "origin"))
            assertContentEquals(binding.payload.copyForCodec(), f.binding(ACCOUNT).payload.copyForCodec())
            assertTrue(f.credentials.retired.isEmpty())
            assertEquals(1, f.dataVault.keys.size)
            assertEquals(1, f.credentials.creates)
        }
    }

    @Test fun recoveryInspectionEmptyMetadataDoesNotClaimAbsenceOfUnselectedPrivateOwnerData() = runTest {
        for (unselectedOwner in listOf(false, true)) fixture { f ->
            if (unselectedOwner) value(value(f.data.activate(ACCOUNT)).commit(ACCOUNT,
                listOf(StoreMutation.Put(DRAFT, null, 1, bytes("unselected-owner-draft")))))
            val before = f.nonObservationCounts()
            repeat(3) {
                val report = diagnostic(SessionRecoveryFinding.METADATA_EMPTY, f.runtime.inspectRecovery())
                assertEquals(SessionRecoveryNextStep.RECHECK_STARTUP, report.nextStep)
                assertEquals(PrivateSessionPhase.STARTUP, f.runtime.phase())
                assertNull(f.boundary.current())
            }
            assertEquals(before, f.nonObservationCounts())
            if (unselectedOwner) assertNotNull(value(f.data.inspectRecord(ACCOUNT, DRAFT)).record)
        }
    }

    @Test fun recoveryInspectionCoherentAccountAndGuestBindingsRequireVerificationWithoutReadingSecrets() = runTest {
        for (guestOwner in listOf(false, true)) fixture { f ->
            if (guestOwner) f.acquire = { PortResult.Value(guest()) }
            val access = f.create()
            val owner = access.scope
            val binding = f.binding(owner)
            f.reopen()
            // Authenticated slot metadata does not prove that the encrypted token blob is usable.
            f.credentials.readFailure = FailureReason.STORAGE_FAILURE
            val before = f.nonObservationCounts()
            repeat(3) {
                val report = diagnostic(SessionRecoveryFinding.VERIFICATION_REQUIRED, f.runtime.inspectRecovery())
                assertEquals(SessionRecoveryNextStep.VERIFY_PREVIOUS_IDENTITY, report.nextStep)
                assertEquals(PrivateSessionPhase.STARTUP, f.runtime.phase())
                assertNull(f.runtime.currentAccess())
                assertNull(f.boundary.current())
            }
            assertEquals(before, f.nonObservationCounts())
            assertEquals(owner, f.credentials.current!!.scope)
            assertContentEquals(binding.payload.copyForCodec(), value(f.data.inspectRecord(owner, BINDING)).record!!.payload.copyForCodec())
        }
    }

    @Test fun recoveryInspectionDoesNotCancelReservedOrCancellingWorkUnderAnOtherwiseExactBinding() = runTest {
        for (phase in listOf("RESERVED", "CANCELLING")) fixture { f ->
            val access = f.create()
            value(f.runtime.install(access, NativeWorkKind.TIMER, "private-timer") { PortResult.Value(Unit) })
            f.rewriteWork { it.replace("\"phase\":\"INSTALLED\"", "\"phase\":\"$phase\"") }
            f.reopen()
            val record = value(f.workControl.read())!!
            val before = f.nonObservationCounts()
            diagnostic(SessionRecoveryFinding.VERIFICATION_REQUIRED, f.runtime.inspectRecovery())
            assertEquals(before, f.nonObservationCounts())
            assertContentEquals(record.payload.copyForCodec(), value(f.workControl.read())!!.payload.copyForCodec())
            assertEquals(record.revision, value(f.workControl.read())!!.revision)
            assertTrue(f.cancelled.isEmpty())
        }
    }

    @Test fun recoveryInspectionClassifiesPartialInitialSelectionsWithoutCompletingOrErasingThem() = runTest {
        for (partial in listOf("credential-only", "credential-and-data", "work-only", "retiring-without-barrier")) fixture { f ->
            if (partial == "credential-only") value(f.credentials.create(1, account())) else {
                f.create()
                when (partial) {
                    "credential-and-data" -> f.rewriteWork { "{\"version\":1,\"state\":\"idle\"}" }
                    "work-only" -> value(f.credentials.retire(ACCOUNT, f.credentials.current!!.incarnation))
                    "retiring-without-barrier" -> f.rewriteWork { it.replace("\"state\":\"active\"", "\"state\":\"retiring\"") }
                }
                f.reopen()
            }
            val before = f.nonObservationCounts()
            val report = diagnostic(SessionRecoveryFinding.PARTIAL_STATE, f.runtime.inspectRecovery())
            assertEquals(SessionRecoveryNextStep.PRESERVE_FOR_REPAIR, report.nextStep)
            assertEquals(before, f.nonObservationCounts())
            assertEquals(PrivateSessionPhase.STARTUP, f.runtime.phase())
            assertNull(f.boundary.current())
        }
    }

    @Test fun recoveryInspectionDoesNotJoinDifferentCredentialAndWorkScopes() = runTest {
        fixture { f ->
            f.create()
            f.credentials.replaceForTest(account().copy(scope = ACCOUNT.copy(actorId = "different-private-owner")))
            f.reopen()
            val before = f.nonObservationCounts()
            diagnostic(SessionRecoveryFinding.SCOPE_MISMATCH, f.runtime.inspectRecovery())
            assertEquals(before, f.nonObservationCounts())
            assertNull(f.boundary.current())
        }
    }

    @Test fun recoveryInspectionMissingDataDoesNotRecreateAnErasedOwner() = runTest {
        fixture { f ->
            f.create()
            value(value(f.data.resume(ACCOUNT))!!.eraseScope(ACCOUNT))
            f.reopen()
            val before = f.nonObservationCounts()
            diagnostic(SessionRecoveryFinding.DATA_MISSING, f.runtime.inspectRecovery())
            assertEquals(before, f.nonObservationCounts())
            assertTrue(f.dataVault.keys.isEmpty())
            assertNotNull(f.credentials.current)
        }
    }

    @Test fun recoveryInspectionDistinguishesMissingBindingFromMalformedDuplicateAndFutureSchema() = runTest {
        for (damage in listOf("missing", "malformed", "duplicate", "schema", "future-version")) fixture { f ->
            f.create()
            val binding = f.binding(ACCOUNT)
            when (damage) {
                "missing" -> value(value(f.data.resume(ACCOUNT))!!.commit(ACCOUNT, listOf(StoreMutation.Delete(BINDING, binding.revision))))
                "malformed" -> f.replaceBinding(ACCOUNT, bytes("not-json-private-owner-data"))
                "duplicate" -> f.replaceBinding(ACCOUNT, bytes(binding.payload.copyForCodec().decodeToString().replace("\"version\":1", "\"version\":1,\"version\":1")))
                "schema" -> f.replaceBinding(ACCOUNT, binding.payload, 2)
                "future-version" -> f.replaceBinding(ACCOUNT, bytes(binding.payload.copyForCodec().decodeToString().replace("\"version\":1", "\"version\":2")))
            }
            f.reopen()
            val before = f.nonObservationCounts()
            diagnostic(if (damage == "missing") SessionRecoveryFinding.BINDING_MISSING else SessionRecoveryFinding.BINDING_INVALID,
                f.runtime.inspectRecovery())
            assertEquals(before, f.nonObservationCounts())
            assertEquals(1, f.credentials.creates)
        }
    }

    @Test fun recoveryInspectionRejectsConfigurationAndEveryExactBindingDimensionBeforeSecretAccess() = runTest {
        for (mismatch in listOf("configuration", "scope", "credential", "work", "data")) fixture { f ->
            val old = f.create()
            val binding = f.binding(ACCOUNT)
            when (mismatch) {
                "scope" -> f.replaceBinding(ACCOUNT, bytes(binding.payload.copyForCodec().decodeToString().replace(ACCOUNT.actorId, "different-private-owner")))
                "credential" -> f.credentials.replaceForTest(account(), uuid(9500))
                "work" -> f.rewriteWork { it.replace(old.originBinding, uuid(9501)) }
                "data" -> {
                    value(value(f.data.resume(ACCOUNT))!!.eraseScope(ACCOUNT))
                    value(value(f.data.activate(ACCOUNT)).commit(ACCOUNT, listOf(StoreMutation.Put(BINDING, null, 1, binding.payload))))
                }
            }
            f.reopen(if (mismatch == "configuration") OTHER_CONFIGURATION else CONFIGURATION)
            val before = f.nonObservationCounts()
            diagnostic(if (mismatch == "configuration") SessionRecoveryFinding.CONFIGURATION_CHANGED else SessionRecoveryFinding.BINDING_MISMATCH,
                f.runtime.inspectRecovery())
            assertEquals(before, f.nonObservationCounts())
            assertNull(f.runtime.currentAccess())
            assertNull(f.boundary.current())
        }
    }

    @Test fun recoveryInspectionUnavailableControlShortCircuitsAllLowerEvidenceAndNeverRepairsIt() = runTest {
        for (damage in listOf("missing", "failure", "throw", "malformed", "future")) fixture { f ->
            f.controlReadOverride = {
                when (damage) {
                    "missing" -> PortResult.Value(null)
                    "failure" -> PortResult.Failure(FailureReason.UNAVAILABLE)
                    "throw" -> error("private-path-and-owner-must-not-escape")
                    "malformed" -> PortResult.Value(SessionControlRecord(1, bytes("invalid-private-retirement-record")))
                    else -> PortResult.Value(SessionControlRecord(1, bytes("{\"version\":2,\"state\":\"idle\"}")))
                }
            }
            val lower = listOf(f.credentials.states, f.workReads)
            val before = f.nonObservationCounts()
            val report = diagnostic(SessionRecoveryFinding.EVIDENCE_UNAVAILABLE, f.runtime.inspectRecovery(), SessionRecoveryComponent.CONTROL)
            assertEquals(if (damage == "failure") FailureReason.UNAVAILABLE else FailureReason.STORAGE_FAILURE, report.failureReason)
            assertEquals(SessionRecoveryNextStep.RECHECK_EVIDENCE, report.nextStep)
            assertEquals(lower, listOf(f.credentials.states, f.workReads))
            assertEquals(before, f.nonObservationCounts())
        }
    }

    @Test fun recoveryInspectionUnavailableCredentialOrWorkMetadataIsNotClassifiedAsEmpty() = runTest {
        for (damage in listOf("credential-failure", "credential-throw", "work-failure", "work-missing", "work-corrupt")) fixture { f ->
            when (damage) {
                "credential-failure" -> f.credentials.stateOverride = { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
                "credential-throw" -> f.credentials.stateOverride = { error("private-credential-locator") }
                "work-failure" -> f.workReadOverride = { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
                "work-missing" -> f.workReadOverride = { PortResult.Value(null) }
                "work-corrupt" -> f.workReadOverride = { PortResult.Value(SessionControlRecord(1, bytes("invalid-private-work-record"))) }
            }
            val before = f.nonObservationCounts()
            diagnostic(SessionRecoveryFinding.EVIDENCE_UNAVAILABLE, f.runtime.inspectRecovery(),
                if (damage.startsWith("credential")) SessionRecoveryComponent.CREDENTIAL_METADATA else SessionRecoveryComponent.WORK_METADATA)
            assertEquals(before, f.nonObservationCounts())
            assertNull(f.boundary.current())
        }
    }

    @Test fun recoveryInspectionUnreadablePrivateCiphertextOrKeyPreservesEvidenceWithoutReset() = runTest {
        for (missingKey in listOf(false, true)) fixture { f ->
            f.create()
            f.reopen()
            if (missingKey) f.dataVault.keys.clear() else f.dataConnection.prepare(
                "UPDATE feedme_records SET payload=zeroblob(length(payload))").use { it.step() }
            val before = f.nonObservationCounts()
            diagnostic(SessionRecoveryFinding.EVIDENCE_UNAVAILABLE, f.runtime.inspectRecovery(), SessionRecoveryComponent.PRIVATE_BINDING)
            assertEquals(before, f.nonObservationCounts())
            assertNotNull(f.credentials.current)
            assertNull(f.boundary.current())
        }
    }

    @Test fun recoveryInspectionPendingRetirementSurvivesErasedPrivateDataWithoutReadingOrCancelingLowerStores() = runTest {
        fixture { f ->
            val access = f.create()
            value(f.runtime.install(access, NativeWorkKind.TIMER, "pending-private-timer") { PortResult.Value(Unit) })
            f.cancel = { PortResult.Failure(FailureReason.UNAVAILABLE) }
            assertEquals(LocalRetirementPhase.PENDING, value(f.runtime.retire(access, OPERATION)).phase)
            assertNull(f.credentials.current)
            assertTrue(f.dataVault.keys.isEmpty())
            f.reopen()
            f.credentials.stateOverride = { fail("Pending diagnostics read credential metadata") }
            f.workReadOverride = { fail("Pending diagnostics read work metadata") }
            val lower = listOf(f.credentials.states, f.workReads)
            val before = f.nonObservationCounts()
            val record = value(f.control.read())!!
            val report = diagnostic(SessionRecoveryFinding.RETIREMENT_PENDING, f.runtime.inspectRecovery())
            assertEquals(SessionRecoveryNextStep.RETRY_EXISTING_RETIREMENT, report.nextStep)
            assertEquals(lower, listOf(f.credentials.states, f.workReads))
            assertEquals(before, f.nonObservationCounts())
            assertEquals(record.revision, value(f.control.read())!!.revision)
            assertEquals(PrivateSessionPhase.STARTUP, f.runtime.phase())
        }
    }

    @Test fun recoveryInspectionProcessOnlyRetirementFenceWinsOverIdleOrUnreadableDurableControl() = runTest {
        fixture { f ->
            val access = f.create()
            f.controlWriteFailure = FailureReason.STORAGE_FAILURE
            failure(FailureReason.STORAGE_FAILURE, f.runtime.retire(access, OPERATION))
            assertEquals("complete", field(value(f.control.read())!!.payload, "state"))
            assertEquals(PrivateSessionPhase.RECOVERY_REQUIRED, f.runtime.phase())
            val before = f.nonObservationCounts()
            val reads = f.metadataReadCounts()
            diagnostic(SessionRecoveryFinding.RETIREMENT_PENDING, f.runtime.inspectRecovery())
            f.controlReadOverride = { fail("Process retirement fence must win without disk access") }
            diagnostic(SessionRecoveryFinding.RETIREMENT_PENDING, f.runtime.inspectRecovery())
            assertEquals(reads, f.metadataReadCounts())
            assertEquals(before, f.nonObservationCounts())
            assertNotNull(f.credentials.current)
            assertEquals(1, f.dataVault.keys.size)
        }
    }

    @Test fun recoveryInspectionReverseChecksDetectControlCredentialWorkAndDataChanges() = runTest {
        for (changed in listOf("control", "credential", "work", "data")) fixture { f ->
            f.create()
            f.reopen()
            var once = true
            when (changed) {
                "control" -> f.afterControlRead = { if (once) { once = false
                    val old = value(f.control.read())!!
                    value(f.control.compareAndSet(old.revision, old.payload))
                } }
                "credential" -> f.credentials.afterState = { if (once) { once = false; f.credentials.replaceForTest(account()) } }
                "work" -> f.afterWorkRead = { if (once) { once = false; f.rewriteWork { it } } }
                "data" -> f.dataConnection.afterNextReadCommit = {
                    // Independent test damage occurs after the first atomic read transaction.
                    f.dataConnection.prepare("DELETE FROM feedme_records").use { it.step() }
                }
            }
            val secureReads = f.credentials.reads
            val report = diagnostic(SessionRecoveryFinding.EVIDENCE_CHANGED, f.runtime.inspectRecovery())
            assertEquals(SessionRecoveryNextStep.RECHECK_EVIDENCE, report.nextStep)
            assertEquals(secureReads, f.credentials.reads)
            assertNull(f.boundary.current())
            assertEquals(PrivateSessionPhase.STARTUP, f.runtime.phase())
        }
    }

    @Test fun recoveryInspectionReverseReadFailureCannotReturnAnEarlierCoherentReport() = runTest {
        for (component in listOf("control", "credential", "work")) fixture { f ->
            f.create()
            f.reopen()
            var reads = 0
            when (component) {
                "control" -> f.controlReadOverride = { if (++reads == 1) f.control.read() else PortResult.Failure(FailureReason.UNAVAILABLE) }
                "credential" -> f.credentials.stateOverride = {
                    if (++reads == 1) PortResult.Value(CredentialSlotState(f.credentials.revision, ACCOUNT, f.credentials.current!!.incarnation))
                    else PortResult.Failure(FailureReason.UNAVAILABLE)
                }
                "work" -> f.workReadOverride = { if (++reads == 1) f.workControl.read() else PortResult.Failure(FailureReason.UNAVAILABLE) }
            }
            val before = f.nonObservationCounts()
            val report = diagnostic(SessionRecoveryFinding.EVIDENCE_UNAVAILABLE, f.runtime.inspectRecovery(), when (component) {
                "control" -> SessionRecoveryComponent.CONTROL
                "credential" -> SessionRecoveryComponent.CREDENTIAL_METADATA
                else -> SessionRecoveryComponent.WORK_METADATA
            })
            assertEquals(FailureReason.UNAVAILABLE, report.failureReason)
            assertEquals(before, f.nonObservationCounts())
        }
    }

    @Test fun recoveryInspectionLateDurableRetirementWinsOverEarlierCoherentLowerEvidence() = runTest {
        fixture { f ->
            val access = f.create()
            val target = hex(value(f.data.captureRetirement(ACCOUNT))!!.copyForStorage())
            val incarnation = f.credentials.current!!.incarnation
            f.reopen()
            var once = true
            f.afterWorkRead = { if (once) {
                once = false
                val record = value(f.control.read())!!
                val pending = """{"version":1,"state":"pending","operationId":"$OPERATION","scope":{"environment":"${ACCOUNT.environment}","actorKind":"ACCOUNT","actorId":"${ACCOUNT.actorId}"},"origin":"${access.originBinding}","credentialIncarnation":"$incarnation","dataTarget":"$target","done":[]}"""
                value(f.control.compareAndSet(record.revision, bytes(pending)))
            } }
            val secureReads = f.credentials.reads
            diagnostic(SessionRecoveryFinding.RETIREMENT_PENDING, f.runtime.inspectRecovery())
            assertEquals(secureReads, f.credentials.reads)
            assertTrue(f.cancelled.isEmpty())
            assertTrue(f.credentials.retired.isEmpty())
            assertEquals(PrivateSessionPhase.STARTUP, f.runtime.phase())
        }
    }

    @Test fun recoveryInspectionBusyOrClosedRuntimeRejectsBeforeDiagnosticIO() = runTest {
        for (phase in listOf("active", "verifying", "composing", "retiring", "closed")) fixture { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val operation = when (phase) {
                "verifying" -> {
                    value(f.runtime.recover())
                    f.acquire = { entered.complete(Unit); release.await(); PortResult.Value(account()) }
                    async { f.runtime.create() }
                }
                "composing" -> {
                    value(f.runtime.recover())
                    var once = true
                    f.afterControlRead = { if (once && f.runtime.phase() == PrivateSessionPhase.COMPOSING) {
                        once = false; entered.complete(Unit); release.await()
                    } }
                    async { f.runtime.create() }
                }
                "retiring" -> {
                    val access = f.create()
                    value(f.runtime.install(access, NativeWorkKind.TIMER, "busy-timer") { PortResult.Value(Unit) })
                    f.cancel = { entered.complete(Unit); release.await(); PortResult.Value(Unit) }
                    async { f.runtime.retire(access, OPERATION) }
                }
                else -> {
                    f.create()
                    if (phase == "closed") value(f.runtime.close())
                    null
                }
            }
            if (operation != null) entered.await()
            val reads = f.metadataReadCounts()
            val before = f.nonObservationCounts()
            failure(if (phase == "closed") FailureReason.STORAGE_FAILURE else FailureReason.CONFLICT, f.runtime.inspectRecovery())
            assertEquals(reads, f.metadataReadCounts())
            assertEquals(before, f.nonObservationCounts())
            release.complete(Unit)
            operation?.await()?.let { assertIs<PortResult.Value<*>>(it) }
        }
    }

    @Test fun recoveryInspectionCloseAtEverySuspendingMetadataReadCannotPublishOldReport() = runTest {
        for (site in listOf("control", "credential", "work")) fixture { f ->
            f.create()
            f.reopen()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var once = true
            val hook: suspend () -> Unit = { if (once) { once = false; entered.complete(Unit); release.await() } }
            when (site) {
                "control" -> f.afterControlRead = hook
                "credential" -> f.credentials.afterState = hook
                else -> f.afterWorkRead = hook
            }
            val inspecting = async { f.runtime.inspectRecovery() }
            entered.await()
            val closing = async { f.runtime.close() }
            runCurrent()
            assertEquals(PrivateSessionPhase.CLOSED, f.runtime.phase())
            release.complete(Unit)
            failure(FailureReason.STORAGE_FAILURE, inspecting.await())
            value(closing.await())
            assertNull(f.boundary.current())
            assertEquals(1, f.credentials.creates)
            assertTrue(f.credentials.retired.isEmpty())
        }
    }

    @Test fun recoveryInspectionCancellationPropagatesWithoutChangingStateOrHoldingTheGate() = runTest {
        for (site in listOf("control", "credential", "work")) fixture { f ->
            f.create()
            f.reopen()
            val entered = CompletableDeferred<Unit>()
            val never = CompletableDeferred<Unit>()
            var once = true
            val hook: suspend () -> Unit = { if (once) { once = false; entered.complete(Unit); never.await() } }
            when (site) {
                "control" -> f.afterControlRead = hook
                "credential" -> f.credentials.afterState = hook
                else -> f.afterWorkRead = hook
            }
            val before = f.nonObservationCounts()
            val inspecting = async { f.runtime.inspectRecovery() }
            entered.await()
            inspecting.cancel()
            assertFailsWith<CancellationException> { inspecting.await() }
            assertEquals(PrivateSessionPhase.STARTUP, f.runtime.phase())
            assertNull(f.boundary.current())
            diagnostic(SessionRecoveryFinding.VERIFICATION_REQUIRED, f.runtime.inspectRecovery())
            assertEquals(before, f.nonObservationCounts())
        }
    }

    @Test fun recoveryInspectionQueuedBehindAnotherLifecycleGenerationRejectsEvenWhenPhaseReturnsToSameValue() = runTest {
        fixture { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var once = true
            f.afterControlRead = { if (once) { once = false; entered.complete(Unit); release.await() } }
            val firstRecovery = async { f.runtime.recover() }
            entered.await()
            assertEquals(PrivateSessionPhase.RECOVERY_REQUIRED, f.runtime.phase())
            val secondRecovery = async { f.runtime.recover() }
            runCurrent()
            val inspecting = async { f.runtime.inspectRecovery() }
            runCurrent()
            assertFalse(inspecting.isCompleted)
            release.complete(Unit)
            assertEquals(PrivateSessionPhase.SIGNED_OUT, value(firstRecovery.await()))
            assertEquals(PrivateSessionPhase.SIGNED_OUT, value(secondRecovery.await()))
            failure(FailureReason.STALE_SESSION, inspecting.await())
            diagnostic(SessionRecoveryFinding.METADATA_EMPTY, f.runtime.inspectRecovery())
            assertNull(f.boundary.current())
        }
    }

    @Test fun recoveryInspectionRejectsUnexpectedBoundaryActivationWithoutClearingTheNewLease() = runTest {
        fixture { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var once = true
            f.afterControlRead = { if (once) { once = false; entered.complete(Unit); release.await() } }
            val inspecting = async { f.runtime.inspectRecovery() }
            entered.await()
            val externalLease = f.boundary.activate(ACCOUNT)
            release.complete(Unit)
            failure(FailureReason.CONFLICT, inspecting.await())
            assertSame(externalLease, f.boundary.current())
            assertEquals(PrivateSessionPhase.STARTUP, f.runtime.phase())
            assertEquals(0, f.credentials.creates)
            f.boundary.clear()
        }
    }

    @Test fun recoveryDiagnosticProjectionContainsOnlyBoundedFindingsNotIdentityOrRepairCapabilities() = runTest {
        fixture { f ->
            val access = f.create()
            val incarnation = f.credentials.current!!.incarnation
            val target = hex(value(f.data.captureRetirement(ACCOUNT))!!.copyForStorage())
            f.reopen(OTHER_CONFIGURATION)
            val changed = diagnostic(SessionRecoveryFinding.CONFIGURATION_CHANGED, f.runtime.inspectRecovery())
            assertEquals(SessionRecoveryNextStep.PRESERVE_FOR_REPAIR, changed.nextStep)
            f.controlReadOverride = { error("${ACCOUNT.actorId}/$incarnation/private-path/access-runtime-secret") }
            val unreadable = diagnostic(SessionRecoveryFinding.EVIDENCE_UNAVAILABLE, f.runtime.inspectRecovery(), SessionRecoveryComponent.CONTROL)
            for (report in listOf(changed, unreadable)) {
                for (privateValue in listOf(ACCOUNT.actorId, ACCOUNT.environment, access.originBinding, incarnation,
                    CONFIGURATION, OTHER_CONFIGURATION, target, "access-runtime-secret", "refresh-runtime-secret", "private-path"))
                    assertFalse(report.toString().contains(privateValue))
                assertEquals(setOf("finding", "component", "failureReason"), report.javaClass.declaredFields
                    .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet())
            }
            assertNull(f.boundary.current())
        }
    }

    @Test fun discardPreparationIsReadOnlyRedactedAndDescribesOnlySelectedEmptyFragments() = runTest {
        for (credentials in listOf(false, true)) for (work in listOf(false, true)) {
            if (!credentials && !work) continue
            for (data in listOf(false, true)) fixture { f ->
                f.seedEmptySetup(credentials, data, work)
                val before = f.nonObservationCounts()
                repeat(2) {
                    val proposal = value(f.runtime.prepareInterruptedSetupDiscard())
                    assertEquals(credentials, proposal.hasCredentials)
                    assertEquals(work, proposal.hasEmptyWork)
                    assertEquals(data, proposal.hasEmptyPrivateStorage)
                    assertEquals("PreparedSetupDiscard(<redacted>)", proposal.toString())
                    assertEquals(PrivateSessionPhase.STARTUP, f.runtime.phase())
                    assertNull(f.boundary.current())
                    assertEquals(before, f.nonObservationCounts())
                    assertEquals("idle", field(value(f.control.read())!!.payload, "state"))
                }
            }
        }
    }

    @Test fun confirmedDiscardErasesOnlyOptionalCapturedFragmentsAndRequiresFreshRecovery() = runTest {
        for (credentials in listOf(false, true)) for (work in listOf(false, true)) {
            if (!credentials && !work) continue
            for (data in listOf(false, true)) fixture { f ->
                f.seedEmptySetup(credentials, data, work)
                val capturedCredential = f.credentials.current?.incarnation
                val foreignScope = ACCOUNT.copy(actorId = "unselected-private-owner")
                val foreign = value(f.data.activate(foreignScope))
                value(foreign.commit(foreignScope, listOf(StoreMutation.Put(DRAFT, null, 1, bytes("preserved-foreign-state")))))
                val proposal = value(f.runtime.prepareInterruptedSetupDiscard())
                val completed = value(f.runtime.confirmInterruptedSetupDiscard(proposal, OPERATION))
                assertEquals(LocalRetirementPhase.COMPLETE, completed.phase)
                assertTrue(completed.remaining.isEmpty())
                assertEquals(PrivateSessionPhase.STARTUP, f.runtime.phase())
                assertNull(f.boundary.current())
                assertNull(f.credentials.current)
                assertNull(value(f.data.resume(ACCOUNT)))
                assertEquals(1, f.dataVault.keys.size)
                assertEquals("preserved-foreign-state", value(foreign.read(foreignScope, DRAFT))!!.payload.copyForCodec().decodeToString())
                assertEquals(if (credentials) listOf(ACCOUNT to capturedCredential) else emptyList(), f.credentials.retired)
                assertEquals("idle", field(value(f.workControl.read())!!.payload, "state"))
                assertEquals(0, f.credentials.reads)
                assertEquals(0, f.acquireCalls + f.restoreCalls)
                assertTrue(f.cancelled.isEmpty())
                assertEquals(PrivateSessionPhase.SIGNED_OUT, value(f.runtime.recover()))
            }
        }
    }

    @Test fun discardCannotInferAuthorityFromEmptyMetadataOrUnselectedPrivateOwner() = runTest {
        for (withUnknownOwner in listOf(false, true)) fixture { f ->
            if (withUnknownOwner) value(f.data.activate(ACCOUNT))
            val before = f.nonObservationCounts()
            failure(FailureReason.NOT_CONFIGURED, f.runtime.prepareInterruptedSetupDiscard())
            assertEquals(before, f.nonObservationCounts())
            assertEquals(PrivateSessionPhase.STARTUP, f.runtime.phase())
            assertNull(f.boundary.current())
        }
    }

    @Test fun discardRejectsPrivateRowsTombstonesAndAnyActivationBinding() = runTest {
        for (kind in listOf("row", "tombstone", "binding")) fixture { f ->
            f.seedEmptySetup()
            val store = value(f.data.resume(ACCOUNT))!!
            val key = if (kind == "binding") BINDING else DRAFT
            val revision = value(store.commit(ACCOUNT, listOf(StoreMutation.Put(key, null, 1, bytes("must-not-discard")))))[key]!!
            if (kind == "tombstone") value(store.commit(ACCOUNT, listOf(StoreMutation.Delete(key, revision))))
            val before = f.nonObservationCounts()
            failure(FailureReason.CONFLICT, f.runtime.prepareInterruptedSetupDiscard())
            assertEquals(before, f.nonObservationCounts())
            assertNotNull(f.credentials.current)
            assertEquals(1, f.dataVault.keys.size)
            assertEquals("active", field(value(f.workControl.read())!!.payload, "state"))
        }
    }

    @Test fun discardRejectsMismatchedRetiringOrPopulatedWork() = runTest {
        for (kind in listOf("scope", "retiring", "entry")) fixture { f ->
            f.seedEmptySetup()
            f.setWork(scope = if (kind == "scope") ACCOUNT.copy(actorId = "another-owner") else ACCOUNT,
                retiring = kind == "retiring", withEntry = kind == "entry")
            val before = f.nonObservationCounts()
            failure(FailureReason.CONFLICT, f.runtime.prepareInterruptedSetupDiscard())
            assertEquals(before, f.nonObservationCounts())
            assertTrue(f.cancelled.isEmpty())
            assertNotNull(f.credentials.current)
        }
    }

    @Test fun discardPreparationRejectsActiveVerifyingComposingClosedAndPreviouslyComposedSessions() = runTest {
        fixture { f ->
            f.create()
            var before = f.nonObservationCounts()
            failure(FailureReason.CONFLICT, f.runtime.prepareInterruptedSetupDiscard())
            assertEquals(before, f.nonObservationCounts())
            f.reopen()
            before = f.nonObservationCounts()
            // Inactive with a coherent activation binding is a prior session, never empty setup.
            failure(FailureReason.CONFLICT, f.runtime.prepareInterruptedSetupDiscard())
            assertEquals(before, f.nonObservationCounts())
            value(f.runtime.close())
            failure(FailureReason.STORAGE_FAILURE, f.runtime.prepareInterruptedSetupDiscard())
        }
        for (phase in listOf(PrivateSessionPhase.VERIFYING, PrivateSessionPhase.COMPOSING)) fixture { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var paused = false
            if (phase == PrivateSessionPhase.VERIFYING) f.acquire = {
                entered.complete(Unit); release.await(); PortResult.Value(account())
            } else f.afterWorkRead = {
                if (!paused && f.runtime.phase() == phase) {
                    paused = true; entered.complete(Unit); release.await()
                }
            }
            value(f.runtime.recover())
            val creating = async { f.runtime.create() }
            entered.await()
            assertEquals(phase, f.runtime.phase())
            val before = f.nonObservationCounts()
            failure(FailureReason.CONFLICT, f.runtime.prepareInterruptedSetupDiscard())
            assertEquals(before, f.nonObservationCounts())
            value(f.runtime.cancelVerification())
            release.complete(Unit)
            failure(FailureReason.STALE_SESSION, creating.await())
        }
    }

    @Test fun discardConfirmationRejectsChangedControlRevisionOrBytes() = runTest {
        for (change in listOf("revision", "bytes")) fixture { f ->
            f.seedEmptySetup()
            val proposal = value(f.runtime.prepareInterruptedSetupDiscard())
            val record = value(f.control.read())!!
            if (change == "revision") value(f.control.compareAndSet(record.revision, record.payload))
            else f.controlReadOverride = { PortResult.Value(SessionControlRecord(record.revision,
                bytes("""{"version":1,"state":"complete","operationId":"${uuid(8001)}"}"""))) }
            val before = f.nonObservationCounts()
            failure(FailureReason.CONFLICT, f.runtime.confirmInterruptedSetupDiscard(proposal, OPERATION))
            assertEquals(before, f.nonObservationCounts())
            assertEquals(PrivateSessionPhase.STARTUP, f.runtime.phase())
        }
    }

    @Test fun discardConfirmationRejectsRefreshedOrReplacedCredentialIncarnation() = runTest {
        for (newIncarnation in listOf(false, true)) fixture { f ->
            f.seedEmptySetup()
            val proposal = value(f.runtime.prepareInterruptedSetupDiscard())
            f.credentials.replaceForTest(account(), if (newIncarnation) uuid(8002) else f.credentials.current!!.incarnation)
            val preserved = f.credentials.current
            val before = f.nonObservationCounts()
            failure(FailureReason.CONFLICT, f.runtime.confirmInterruptedSetupDiscard(proposal, OPERATION))
            assertEquals(before, f.nonObservationCounts())
            assertSame(preserved, f.credentials.current)
            assertTrue(f.credentials.retired.isEmpty())
        }
    }

    @Test fun discardConfirmationRejectsChangedWorkRevisionOriginAndEntries() = runTest {
        for (change in listOf("revision", "origin", "entries", "retiring")) fixture { f ->
            f.seedEmptySetup()
            val proposal = value(f.runtime.prepareInterruptedSetupDiscard())
            f.setWork(origin = if (change == "origin") uuid(8003) else uuid(7000),
                retiring = change == "retiring", withEntry = change == "entries")
            val before = f.nonObservationCounts()
            failure(FailureReason.CONFLICT, f.runtime.confirmInterruptedSetupDiscard(proposal, OPERATION))
            assertEquals(before, f.nonObservationCounts())
            assertTrue(f.cancelled.isEmpty())
        }
    }

    @Test fun discardConfirmationRejectsRecreatedOwnerOrNewRowsAndTombstones() = runTest {
        for (change in listOf("generation", "row", "tombstone")) fixture { f ->
            f.seedEmptySetup()
            val proposal = value(f.runtime.prepareInterruptedSetupDiscard())
            val store = value(f.data.resume(ACCOUNT))!!
            if (change == "generation") {
                value(store.eraseScope(ACCOUNT)); value(f.data.activate(ACCOUNT))
            } else {
                val revision = value(store.commit(ACCOUNT, listOf(StoreMutation.Put(DRAFT, null, 1, bytes("new-private-state")))))[DRAFT]!!
                if (change == "tombstone") value(store.commit(ACCOUNT, listOf(StoreMutation.Delete(DRAFT, revision))))
            }
            val before = f.nonObservationCounts()
            failure(FailureReason.CONFLICT, f.runtime.confirmInterruptedSetupDiscard(proposal, OPERATION))
            assertEquals(before, f.nonObservationCounts())
            assertNotNull(f.credentials.current)
            assertEquals(1, f.dataVault.keys.size)
        }
    }

    @Test fun discardPreparationRejectsEvidenceChangedDuringReadBracketing() = runTest {
        for (point in listOf("control", "slot", "work", "data")) fixture { f ->
            f.seedEmptySetup()
            var once = true
            when (point) {
                "control" -> f.afterControlRead = { if (once) {
                    once = false
                    val record = value(f.control.read())!!
                    value(f.control.compareAndSet(record.revision, record.payload))
                } }
                "slot" -> f.credentials.afterState = { if (once) { once = false; f.credentials.replaceForTest(account()) } }
                "work" -> f.afterWorkRead = { if (once) { once = false; f.setWork(origin = uuid(8004)) } }
                else -> f.dataConnection.afterNextReadCommit = { f.credentials.replaceForTest(account()) }
            }
            failure(FailureReason.CONFLICT, f.runtime.prepareInterruptedSetupDiscard())
            assertEquals(0, f.credentials.reads + f.acquireCalls + f.restoreCalls + f.controlWrites + f.workWrites)
            assertTrue(f.credentials.retired.isEmpty())
            assertTrue(f.cancelled.isEmpty())
            assertEquals(0, f.dataVault.deletes)
            assertEquals(PrivateSessionPhase.STARTUP, f.runtime.phase())
        }
    }

    @Test fun discardConfirmationRechecksEvidenceInsideBothReadBrackets() = runTest {
        for (controlRead in listOf(1, 3)) fixture { f ->
            f.seedEmptySetup()
            val proposal = value(f.runtime.prepareInterruptedSetupDiscard())
            var reads = 0
            f.afterControlRead = { if (++reads == controlRead) f.credentials.replaceForTest(account()) }
            failure(FailureReason.CONFLICT, f.runtime.confirmInterruptedSetupDiscard(proposal, OPERATION))
            assertEquals(0, f.credentials.reads + f.acquireCalls + f.restoreCalls + f.controlWrites + f.workWrites)
            assertTrue(f.credentials.retired.isEmpty())
            assertTrue(f.cancelled.isEmpty())
            assertEquals(0, f.dataVault.deletes)
        }
    }

    @Test fun discardProposalCannotCrossRuntimeRecoveryOrReplayAndRequiresValidNewOperationId() = runTest {
        for (change in listOf("reopen", "recover", "replay", "malformed", "completed-id")) fixture { f ->
            f.seedEmptySetup()
            if (change == "completed-id") {
                val record = value(f.control.read())!!
                value(f.control.compareAndSet(record.revision, bytes("""{"version":1,"state":"complete","operationId":"$OPERATION"}""")))
            }
            val proposal = value(f.runtime.prepareInterruptedSetupDiscard())
            when (change) {
                "reopen" -> f.reopen()
                "recover" -> value(f.runtime.recover())
                "replay" -> value(f.runtime.confirmInterruptedSetupDiscard(proposal, OPERATION))
            }
            val before = f.nonObservationCounts()
            val expected = when (change) {
                "malformed" -> FailureReason.INVALID_DATA
                "completed-id" -> FailureReason.CONFLICT
                else -> FailureReason.STALE_SESSION
            }
            failure(expected, f.runtime.confirmInterruptedSetupDiscard(proposal, if (change == "malformed") "not-an-operation-id" else OPERATION))
            assertEquals(before, f.nonObservationCounts())
        }
        fixture { f ->
            f.seedEmptySetup()
            val proposal = value(f.runtime.prepareInterruptedSetupDiscard())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var once = true
            f.afterControlRead = { if (once) { once = false; entered.complete(Unit); release.await() } }
            val confirming = async { f.runtime.confirmInterruptedSetupDiscard(proposal, OPERATION) }
            entered.await()
            value(f.runtime.close())
            release.complete(Unit)
            failure(FailureReason.STORAGE_FAILURE, confirming.await())
            assertTrue(f.credentials.retired.isEmpty())
            assertTrue(f.cancelled.isEmpty())
            assertEquals(0, f.dataVault.deletes)
        }
    }

    @Test fun discardBarrierFailureRetainsProcessFenceUntilExplicitRecoveryRetry() = runTest {
        fixture { f ->
            f.seedEmptySetup()
            val original = f.credentials.current!!
            val proposal = value(f.runtime.prepareInterruptedSetupDiscard())
            f.controlWriteFailure = FailureReason.STORAGE_FAILURE
            failure(FailureReason.STORAGE_FAILURE, f.runtime.confirmInterruptedSetupDiscard(proposal, OPERATION))
            assertEquals(PrivateSessionPhase.RECOVERY_REQUIRED, f.runtime.phase())
            assertNull(f.boundary.current())
            assertSame(original, f.credentials.current)
            assertTrue(f.credentials.retired.isEmpty())
            assertTrue(f.cancelled.isEmpty())
            assertEquals(0, f.dataVault.deletes)
            diagnostic(SessionRecoveryFinding.RETIREMENT_PENDING, f.runtime.inspectRecovery())
            failure(FailureReason.CONFLICT, f.runtime.prepareInterruptedSetupDiscard())
            failure(FailureReason.STALE_SESSION, f.runtime.confirmInterruptedSetupDiscard(proposal, OPERATION))
            f.controlWriteFailure = null
            assertEquals(PrivateSessionPhase.SIGNED_OUT, value(f.runtime.recover()))
            assertEquals(listOf(ACCOUNT to original.incarnation), f.credentials.retired)
            assertTrue(f.dataVault.keys.isEmpty())
            assertTrue(f.cancelled.isEmpty())
            assertEquals(0, f.credentials.reads + f.acquireCalls + f.restoreCalls)
        }
    }

    @Test fun discardCancellationAfterExactCredentialErasureReplaysDurableTargetsAfterReopen() = runTest {
        fixture { f ->
            f.seedEmptySetup()
            val original = f.credentials.current!!
            val proposal = value(f.runtime.prepareInterruptedSetupDiscard())
            f.credentials.afterRetire = { throw CancellationException("Controlled post-erasure cancellation") }
            val confirming = async { f.runtime.confirmInterruptedSetupDiscard(proposal, OPERATION) }
            assertFailsWith<CancellationException> { confirming.await() }
            assertNull(f.credentials.current)
            assertEquals(PrivateSessionPhase.RECOVERY_REQUIRED, f.runtime.phase())
            assertNotEquals("idle", field(value(f.control.read())!!.payload, "state"))
            assertNotEquals("complete", field(value(f.control.read())!!.payload, "state"))
            assertEquals(1, f.dataVault.keys.size)
            f.credentials.afterRetire = { }
            f.reopen()
            assertNull(f.boundary.current())
            diagnostic(SessionRecoveryFinding.RETIREMENT_PENDING, f.runtime.inspectRecovery())
            assertEquals(PrivateSessionPhase.SIGNED_OUT, value(f.runtime.recover()))
            assertTrue(f.dataVault.keys.isEmpty())
            assertNull(f.credentials.current)
            assertEquals(setOf(ACCOUNT to original.incarnation), f.credentials.retired.toSet())
            assertTrue(f.cancelled.isEmpty())
            assertEquals(0, f.credentials.reads + f.acquireCalls + f.restoreCalls)
        }
    }

    @Test fun plannedCreatePersistsIndependentIntentBeforeCredentialWriteAndClearsBeforeOwnerSetup() = runTest {
        fixture { f ->
            f.credentials.beforePlannedCommit = {
                val pending = value(f.control.read())!!
                assertEquals("credential-create-pending", field(pending.payload, "state"))
                assertEquals(0, f.dataVault.creates)
                assertEquals("idle", field(value(f.workControl.read())!!.payload, "state"))
            }
            f.credentials.afterCreate = {
                assertEquals("credential-create-pending", field(value(f.control.read())!!.payload, "state"))
                assertEquals(0, f.dataVault.creates)
            }
            val access = f.create()
            val completed = value(f.control.read())!!
            assertEquals("complete", field(completed.payload, "state"))
            assertEquals(f.credentials.current!!.incarnation, field(completed.payload, "operationId"))
            assertEquals(1, f.credentials.plans); assertEquals(1, f.credentials.plannedCommits)
            f.assertNoPlaintext(listOf(ACCOUNT.actorId, "access-runtime-secret", "refresh-runtime-secret"))
            f.reopen()
            assertEquals(PrivateSessionPhase.RESTORE_REQUIRED, value(f.runtime.recover()))
            assertEquals(access.originBinding, value(f.runtime.restore()).originBinding)
            assertEquals(1, f.credentials.plans); assertEquals(1, f.credentials.plannedCommits)
        }
    }

    @Test fun plannedNativeUnknownPersistsUnconfirmedPlanAcrossReopenAndEveryStartupGateStaysClosed() = runTest {
        fixture { f ->
            value(f.runtime.recover())
            f.credentials.plannedCommitFailure = FailureReason.OUTCOME_UNKNOWN
            failure(FailureReason.OUTCOME_UNKNOWN, f.runtime.create())
            val original = value(f.control.read())!!
            assertEquals("credential-create-pending", field(original.payload, "state"))
            assertNull(f.credentials.current); assertEquals(0, f.dataVault.creates)
            assertEquals("idle", field(value(f.workControl.read())!!.payload, "state"))
            f.reopen()
            val before = f.nonObservationCounts()
            val lower = listOf(f.credentials.states, f.workReads)
            diagnostic(SessionRecoveryFinding.PARTIAL_STATE, f.runtime.inspectRecovery())
            assertEquals(lower, listOf(f.credentials.states, f.workReads))
            failure(FailureReason.CONFLICT, f.runtime.recover())
            failure(FailureReason.CONFLICT, f.runtime.create())
            failure(FailureReason.CONFLICT, f.runtime.restore())
            failure(FailureReason.CONFLICT, f.runtime.prepareInterruptedSetupDiscard())
            assertEquals(before, f.nonObservationCounts())
            val recovery = CredentialCreateCoordinator(f.control, f.boundary, StandardTestDispatcher(testScheduler),
                CredentialCreateRecoveryFactory { fail("Inspection or unconfirmed recovery opened native handle") })
            assertFalse(value(recovery.inspectPending())!!.abortRequested)
            failure(FailureReason.CONFLICT, recovery.recoverAbort())
            assertContentEquals(original.payload.copyForCodec(), value(f.control.read())!!.payload.copyForCodec())
            assertEquals(original.revision, value(f.control.read())!!.revision)
        }
    }

    @Test fun credentialPlanBarrierFailureNeverWritesCredentialsDataOrWork() = runTest {
        fixture { f ->
            value(f.runtime.recover())
            f.controlWriteFailure = FailureReason.STORAGE_FAILURE
            failure(FailureReason.STORAGE_FAILURE, f.runtime.create())
            assertEquals(1, f.credentials.plans); assertEquals(0, f.credentials.plannedCommits)
            assertNull(f.credentials.current); assertEquals(0, f.dataVault.creates)
            assertEquals("idle", field(value(f.control.read())!!.payload, "state"))
            assertEquals("idle", field(value(f.workControl.read())!!.payload, "state"))
            f.controlWriteFailure = null
            f.reopen()
            assertEquals(PrivateSessionPhase.SIGNED_OUT, value(f.runtime.recover()))
            assertNull(f.boundary.current())
        }
    }

    @Test fun failedPlanCompletionPreventsDataWorkAndRequiresExplicitExactAbortBeforeNewSetup() = runTest {
        fixture { f ->
            value(f.runtime.recover())
            f.credentials.afterCreate = { f.controlWriteFailure = FailureReason.STORAGE_FAILURE }
            failure(FailureReason.STORAGE_FAILURE, f.runtime.create())
            val original = f.credentials.current!!
            assertEquals("credential-create-pending", field(value(f.control.read())!!.payload, "state"))
            assertEquals(0, f.dataVault.creates)
            assertEquals("idle", field(value(f.workControl.read())!!.payload, "state"))
            f.controlWriteFailure = null; f.credentials.afterCreate = { }
            f.reopen()
            failure(FailureReason.CONFLICT, f.runtime.recover())
            var aborts = 0; var closes = 0
            val recovery = CredentialCreateCoordinator(f.control, f.boundary, StandardTestDispatcher(testScheduler),
                CredentialCreateRecoveryFactory { plan ->
                    assertEquals(original.incarnation, field(plan.copyForStorage(), "incarnation"))
                    PortResult.Value(object : CredentialCreateRecoveryHandle {
                        override suspend fun inspect() = PortResult.Value(if (f.credentials.current == null)
                            CredentialCreateRecoveryStatus.ABORTED else CredentialCreateRecoveryStatus.SELECTED)
                        override suspend fun abort(): PortResult<Unit> { aborts++; return f.credentials.retire(original.scope, original.incarnation) }
                        override suspend fun close(): PortResult<Unit> { closes++; return PortResult.Value(Unit) }
                    })
                })
            value(recovery.requestAbort(value(recovery.inspectPending())!!))
            assertEquals(1, aborts); assertEquals(1, closes)
            assertEquals(listOf(original.scope to original.incarnation), f.credentials.retired)
            assertEquals(0, f.credentials.reads)
            assertEquals(PrivateSessionPhase.SIGNED_OUT, value(f.runtime.recover()))
            val created = value(f.runtime.create())
            assertEquals(ACCOUNT, created.scope)
            assertNotEquals(original.incarnation, f.credentials.current!!.incarnation)
        }
    }

    @Test fun legacyCredentialStoreCannotUseRuntimeCreateFallbackOrEvenInvokeVerifier() = runTest {
        fixture { f ->
            f.legacyCredentialsOnly = true
            f.reopen()
            value(f.runtime.recover())
            failure(FailureReason.NOT_CONFIGURED, f.runtime.create())
            assertEquals(0, f.acquireCalls); assertEquals(0, f.credentials.plans)
            assertEquals(0, f.credentials.creates); assertEquals(0, f.dataVault.creates)
            assertEquals("idle", field(value(f.control.read())!!.payload, "state"))
        }
    }

    @Test fun cancellingAfterPlanButBeforeJournalNeverWritesNativeCredentialMaterial() = runTest {
        fixture { f ->
            value(f.runtime.recover())
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.credentials.afterPlan = { entered.complete(Unit); release.await() }
            val creating = async { f.runtime.create() }
            entered.await(); value(f.runtime.cancelVerification()); release.complete(Unit)
            failure(FailureReason.STALE_SESSION, creating.await())
            assertEquals(1, f.credentials.plans); assertEquals(0, f.credentials.plannedCommits)
            assertEquals(0, f.controlWrites); assertEquals(0, f.dataVault.creates)
            assertNull(f.credentials.current)
            f.reopen()
            assertEquals(PrivateSessionPhase.SIGNED_OUT, value(f.runtime.recover()))
        }
    }

    @Test fun exactAmbiguousPlanAndCompletionSqliteCommitsDoNotDuplicateNativeCreate() = runTest {
        fixture { f ->
            f.controlAfterWriteFailures[1] = FailureReason.OUTCOME_UNKNOWN
            f.controlAfterWriteFailures[2] = FailureReason.OUTCOME_UNKNOWN
            val access = f.create()
            assertEquals(1, f.credentials.plans); assertEquals(1, f.credentials.plannedCommits)
            assertEquals(2, f.controlWrites)
            assertEquals("complete", field(value(f.control.read())!!.payload, "state"))
            f.reopen()
            assertEquals(PrivateSessionPhase.RESTORE_REQUIRED, value(f.runtime.recover()))
            assertEquals(access.originBinding, value(f.runtime.restore()).originBinding)
            assertEquals(1, f.credentials.creates)
        }
    }

    private suspend fun TestScope.fixture(block: suspend (RuntimeFixture) -> Unit) {
        val f = RuntimeFixture(StandardTestDispatcher(testScheduler))
        try { f.start(); block(f) } finally { f.close() }
    }

    private class RuntimeFixture(private val dispatcher: CoroutineDispatcher) {
        private val directory = Files.createTempDirectory("feedme-runtime-integration-")
        private val dataFile = directory.resolve("data.sqlite")
        private val controlFile = directory.resolve("control.sqlite")
        private val workFile = directory.resolve("work.sqlite")
        val dataVault = RuntimeTestVault()
        private val controlVault = RuntimeTestVault()
        private val workVault = RuntimeTestVault()
        lateinit var data: EncryptedStateDatabase
        lateinit var dataConnection: RuntimeTestConnection
        lateinit var control: EncryptedSessionControlStore
        lateinit var workControl: EncryptedSessionWorkStore
        lateinit var runtime: PrivateSessionRuntime
        var boundary = SessionBoundary()
        val credentials = RuntimeTestCredentials()
        var legacyCredentialsOnly = false
        val cancelled = mutableListOf<NativeWorkTicket>()
        var cancel: suspend (NativeWorkTicket) -> PortResult<Unit> = { PortResult.Value(Unit) }
        var acquire: suspend () -> PortResult<StoredCredentials> = { PortResult.Value(account()) }
        var restoreMode = PrivateSessionAccessMode.ONLINE
        var restore: suspend (CredentialSnapshot) -> PortResult<PrivateSessionAccessMode> = { PortResult.Value(restoreMode) }
        var acquireCalls = 0
        var restoreCalls = 0
        var afterControlRead: suspend () -> Unit = { }
        var afterWorkRead: suspend () -> Unit = { }
        var controlReadOverride: (suspend () -> PortResult<SessionControlRecord?>)? = null
        var workReadOverride: (suspend () -> PortResult<SessionControlRecord?>)? = null
        var controlWriteFailure: FailureReason? = null
        val controlAfterWriteFailures = mutableMapOf<Int, FailureReason>()
        var controlReads = 0
        var workReads = 0
        var controlWrites = 0
        var workWrites = 0
        var nextIdHook: () -> Unit = { }
        private var nextId = 0
        private val dataManagers = mutableListOf<EncryptedStateDatabase>()
        private val controlManagers = mutableListOf<EncryptedSessionControlStore>()
        private val workManagers = mutableListOf<EncryptedSessionWorkStore>()
        private val runtimes = mutableListOf<PrivateSessionRuntime>()

        suspend fun start() { openStores(true); openRuntime(CONFIGURATION) }
        suspend fun create(): PrivateSessionAccess {
            assertEquals(PrivateSessionPhase.SIGNED_OUT, value(runtime.recover()))
            return value(runtime.create())
        }
        suspend fun seedEmptySetup(withCredentials: Boolean = true, withData: Boolean = true, withWork: Boolean = true) {
            if (withCredentials) value(credentials.create(credentials.revision, account()))
            if (withData) value(data.activate(ACCOUNT))
            if (withWork) setWork()
        }
        suspend fun setWork(scope: StorageScope = ACCOUNT, origin: String = uuid(7000), retiring: Boolean = false, withEntry: Boolean = false) {
            val entries = if (withEntry) """[{"id":"${uuid(7001)}","kind":"TIMER","logicalId":"existing-timer","phase":"INSTALLED"}]""" else "[]"
            val state = if (retiring) "retiring" else "active"
            val payload = bytes("""{"version":1,"state":"$state","scope":{"environment":"${scope.environment}","actorKind":"${scope.actorKind.name}","actorId":"${scope.actorId}"},"origin":"$origin","entries":$entries}""")
            val record = value(workControl.read())!!
            value(workControl.compareAndSet(record.revision, payload))
        }
        suspend fun reopen(configuration: String = CONFIGURATION) {
            closeRuntimeAndStores()
            boundary = SessionBoundary()
            openStores(false)
            openRuntime(configuration)
        }
        private suspend fun openStores(initialize: Boolean) {
            dataConnection = RuntimeTestConnection(BundledSQLiteDriver().open(dataFile.toString()))
            data = value(EncryptedStateDatabase.open(dataConnection, dataVault, dispatcher)).also { dataManagers += it }
            val controlDatabase = value(EncryptedStateDatabase.open(BundledSQLiteDriver().open(controlFile.toString()), controlVault, dispatcher))
            control = value(EncryptedSessionControlStore.open(controlDatabase, initialize)).also { controlManagers += it }
            val workDatabase = value(EncryptedStateDatabase.open(BundledSQLiteDriver().open(workFile.toString()), workVault, dispatcher))
            workControl = value(EncryptedSessionWorkStore.open(workDatabase, initialize)).also { workManagers += it }
        }
        private suspend fun openRuntime(configuration: String) {
            val guardedControl = object : SessionControlStore {
                override suspend fun read(): PortResult<SessionControlRecord?> {
                    controlReads++
                    val result = controlReadOverride?.invoke() ?: control.read()
                    afterControlRead()
                    return result
                }
                override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> {
                    controlWrites++
                    controlWriteFailure?.let { return PortResult.Failure(it) }
                    val result = control.compareAndSet(expectedRevision, payload)
                    return controlAfterWriteFailures[controlWrites]?.let { PortResult.Failure(it) } ?: result
                }
            }
            val guardedWork = object : SessionControlStore {
                override suspend fun read(): PortResult<SessionControlRecord?> {
                    workReads++
                    val result = workReadOverride?.invoke() ?: workControl.read()
                    afterWorkRead()
                    return result
                }
                override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> {
                    workWrites++
                    return workControl.compareAndSet(expectedRevision, payload)
                }
            }
            val selectedCredentials = if (legacyCredentialsOnly) object : IncarnationCredentialStore by credentials { } else credentials
            runtime = value(PrivateSessionRuntime.open(guardedControl, guardedWork, data, selectedCredentials, boundary, dispatcher, configuration,
                object : NativeSessionVerifier {
                    override suspend fun acquire(): PortResult<StoredCredentials> { acquireCalls++; return this@RuntimeFixture.acquire() }
                    override suspend fun restore(snapshot: CredentialSnapshot): PortResult<PrivateSessionAccessMode> { restoreCalls++; return this@RuntimeFixture.restore(snapshot) }
                }, NativeWorkCancellationPort { cancelled += it; cancel(it) },
                NativeWorkIdSource { nextIdHook(); uuid(1_000 + ++nextId) },
                NativeWorkExecutionPolicy { _, _, _, _ -> PortResult.Value(true) })).also { runtimes += it }
        }
        suspend fun binding(scope: StorageScope): PrivateRecord = value(value(data.resume(scope))!!.read(scope, BINDING))!!
        suspend fun replaceBinding(scope: StorageScope, payload: PrivateBytes, schema: Int = 1) {
            val store = value(data.resume(scope))!!
            val previous = value(store.read(scope, BINDING))!!
            value(store.commit(scope, listOf(StoreMutation.Put(BINDING, previous.revision, schema, payload))))
        }
        suspend fun rewriteWork(transform: (String) -> String) {
            val record = value(workControl.read())!!
            value(workControl.compareAndSet(record.revision, bytes(transform(record.payload.copyForCodec().decodeToString()))))
        }
        fun nonObservationCounts() = listOf(acquireCalls, restoreCalls, credentials.reads, credentials.creates, credentials.plans, credentials.plannedCommits,
            credentials.replacements, credentials.retired.size, cancelled.size, nextId,
            controlWrites, workWrites, dataConnection.writeStatements, dataVault.creates, dataVault.deletes,
            dataVault.seals, controlVault.creates, controlVault.deletes, controlVault.seals,
            workVault.creates, workVault.deletes, workVault.seals)
        fun metadataReadCounts() = listOf(controlReads, credentials.states, workReads)
        suspend fun closeRuntimeAndStores() {
            value(runtime.close()); value(workControl.close()); value(control.close()); value(data.close())
        }
        fun assertNoPlaintext(markers: List<String>) {
            Files.list(directory).use { files -> files.filter { Files.isRegularFile(it) }.forEach { file ->
                val raw = Files.readAllBytes(file)
                for (marker in markers) for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE)) {
                    val needle = marker.toByteArray(charset)
                    assertFalse(raw.indices.any { start -> start + needle.size <= raw.size && needle.indices.all { raw[start + it] == needle[it] } })
                }
            } }
        }
        suspend fun close() {
            try {
                runtimes.asReversed().forEach { it.close() }
                workManagers.asReversed().forEach { it.close() }
                controlManagers.asReversed().forEach { it.close() }
                dataManagers.asReversed().forEach { it.close() }
            } finally {
                Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
            }
        }
    }

    /** Test fake only. Reflection avoids adding a production constructor/authority API for tests. */
    private class RuntimeTestCredentials : PlannedCredentialCreateStore {
        var current: CredentialSnapshot? = null
        var revision = 1L
        var creates = 0
        var reads = 0
        var states = 0
        var replacements = 0
        var plans = 0
        var plannedCommits = 0
        var plannedCommitFailure: FailureReason? = null
        var afterPlan: suspend () -> Unit = { }
        var beforePlannedCommit: suspend () -> Unit = { }
        private var planned: Triple<CredentialCreatePlan, String, StoredCredentials>? = null
        private var plannedRevision = 0L
        var stateOverride: (suspend () -> PortResult<CredentialSlotState>)? = null
        var readFailure: FailureReason? = null
        var afterState: suspend () -> Unit = { }
        var afterCreate: suspend () -> Unit = { }
        var afterRead: suspend () -> Unit = { }
        var afterRetire: suspend () -> Unit = { }
        val retired = mutableListOf<Pair<StorageScope, String>>()
        override suspend fun state(): PortResult<CredentialSlotState> {
            states++
            val result = stateOverride?.invoke() ?: PortResult.Value(CredentialSlotState(revision, current?.scope, current?.incarnation))
            afterState()
            return result
        }
        override suspend fun read(scope: StorageScope): PortResult<CredentialSnapshot?> {
            reads++
            readFailure?.let { return PortResult.Failure(it) }
            val result = current?.takeIf { it.scope == scope }
            afterRead()
            return PortResult.Value(result)
        }
        override suspend fun create(expectedSlotRevision: Long, credentials: StoredCredentials): PortResult<CredentialSnapshot> {
            if (current != null || revision != expectedSlotRevision) return PortResult.Failure(FailureReason.CONFLICT)
            val snapshot = snapshot(uuid(2_000 + ++creates), ++revision, credentials)
            current = snapshot
            afterCreate()
            return PortResult.Value(snapshot)
        }
        override suspend fun planCreate(expectedSlotRevision: Long, credentials: StoredCredentials): PortResult<CredentialCreatePlan> {
            if (current != null || revision != expectedSlotRevision) return PortResult.Failure(FailureReason.CONFLICT)
            val incarnation = uuid(2_000 + creates + ++plans)
            val plan = value(CredentialCreatePlan.fromStorage(bytes("""{"version":1,"purpose":"credential-create","expectedSlotRevision":$expectedSlotRevision,"incarnation":"$incarnation","target":"${"a".repeat(64)}","payloadMac":"${"b".repeat(64)}","authenticationMac":"${"c".repeat(64)}"}""")))
            planned = Triple(plan, incarnation, credentials)
            plannedRevision = expectedSlotRevision
            afterPlan()
            return PortResult.Value(plan)
        }
        override suspend fun commitPlannedCreate(plan: CredentialCreatePlan, credentials: StoredCredentials): PortResult<CredentialSnapshot> {
            plannedCommits++
            beforePlannedCommit()
            plannedCommitFailure?.let { return PortResult.Failure(it) }
            val selected = planned ?: return PortResult.Failure(FailureReason.CONFLICT)
            if (current != null || selected.third !== credentials ||
                !selected.first.copyForStorage().copyForCodec().contentEquals(plan.copyForStorage().copyForCodec()))
                return PortResult.Failure(FailureReason.CONFLICT)
            if (revision != plannedRevision) return PortResult.Failure(FailureReason.CONFLICT)
            creates++
            val snapshot = snapshot(selected.second, ++revision, credentials)
            current = snapshot
            afterCreate()
            return PortResult.Value(snapshot)
        }
        override suspend fun replace(expected: CredentialSnapshot, credentials: StoredCredentials): PortResult<CredentialSnapshot> {
            replacements++
            val previous = current ?: return PortResult.Failure(FailureReason.CONFLICT)
            if (previous.scope != expected.scope || previous.incarnation != expected.incarnation || previous.revision != expected.revision)
                return PortResult.Failure(FailureReason.CONFLICT)
            if (credentials.scope != previous.scope || credentials::class != previous.credentials::class) return PortResult.Failure(FailureReason.INVALID_DATA)
            if (credentials is StoredCredentials.Account && previous.credentials is StoredCredentials.Account) {
                val prior = previous.credentials as StoredCredentials.Account
                if (prior.deviceSessionId?.use { it } != credentials.deviceSessionId?.use { it }) return PortResult.Failure(FailureReason.INVALID_DATA)
            }
            if (credentials is StoredCredentials.Guest && previous.credentials is StoredCredentials.Guest) {
                val prior = previous.credentials as StoredCredentials.Guest
                if (prior.guestSessionId.use { it } != credentials.guestSessionId.use { it }) return PortResult.Failure(FailureReason.INVALID_DATA)
            }
            current = snapshot(previous.incarnation, ++revision, credentials)
            return PortResult.Value(current!!)
        }
        override suspend fun attachDeviceSession(expected: CredentialSnapshot, deviceSessionId: SecretText): PortResult<CredentialSnapshot> =
            PortResult.Failure(FailureReason.NOT_CONFIGURED)
        override suspend fun retire(scope: StorageScope, credentialIncarnation: String): PortResult<Unit> {
            retired += scope to credentialIncarnation
            if (current?.scope == scope && current?.incarnation == credentialIncarnation) { current = null; revision++ }
            afterRetire()
            return PortResult.Value(Unit)
        }
        fun replaceForTest(credentials: StoredCredentials, incarnation: String = current!!.incarnation) {
            current = snapshot(incarnation, ++revision, credentials)
        }
        private fun snapshot(incarnation: String, revision: Long, credentials: StoredCredentials): CredentialSnapshot =
            CredentialSnapshot::class.java.getDeclaredConstructor(String::class.java, java.lang.Long.TYPE, StoredCredentials::class.java)
                .newInstance(incarnation, revision, credentials)
    }

    private class RuntimeTestConnection(private val delegate: SQLiteConnection) : SQLiteConnection by delegate {
        var failNextWriteCommitAfter = false
        var failNextWriteCommitBefore = false
        var afterNextReadCommit: (() -> Unit)? = null
        var writeStatements = 0
        private var write = false
        override fun prepare(sql: String): SQLiteStatement {
            val normalized = sql.trim().uppercase()
            val statement = delegate.prepare(sql)
            return object : SQLiteStatement by statement {
                override fun step(): Boolean {
                    if (normalized.startsWith("BEGIN")) write = normalized == "BEGIN IMMEDIATE"
                    val commit = normalized == "COMMIT" && write
                    val readCommit = normalized == "COMMIT" && !write
                    if (normalized.startsWith("INSERT ") || normalized.startsWith("UPDATE ") || normalized.startsWith("DELETE ")) writeStatements++
                    if (commit && failNextWriteCommitBefore) { failNextWriteCommitBefore = false; error("Injected runtime binding precommit failure") }
                    val result = statement.step()
                    if (normalized == "COMMIT" || normalized == "ROLLBACK") write = false
                    if (commit && failNextWriteCommitAfter) { failNextWriteCommitAfter = false; error("Injected runtime binding acknowledgement failure") }
                    if (readCommit) afterNextReadCommit?.also { afterNextReadCommit = null }?.invoke()
                    return result
                }
            }
        }
    }

    private class RuntimeTestVault : StateVault {
        private val random = SecureRandom()
        private val index = SecretKeySpec(ByteArray(32).also(random::nextBytes), "HmacSHA256")
        val keys = mutableMapOf<String, SecretKey>()
        var creates = 0
        var deletes = 0
        var seals = 0
        override fun index(input: ByteArray) = Mac.getInstance("HmacSHA256").run { init(index); doFinal(input) }
        override fun createOwnerKey(): String = UUID.randomUUID().toString().replace("-", "").also { id ->
            creates++
            keys[id] = KeyGenerator.getInstance("AES").apply { init(256, random) }.generateKey()
        }
        override fun hasOwnerKey(keyId: String) = keyId in keys
        override fun deleteOwnerKey(keyId: String) { deletes++; keys.remove(keyId) }
        override fun seal(keyId: String, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            seals++
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
    }

    companion object {
        private val ACCOUNT = StorageScope("runtime-fixture", ActorKind.ACCOUNT, "private-runtime-owner")
        private val GUEST = ACCOUNT.copy(actorKind = ActorKind.GUEST)
        private val BINDING = RecordKey("session-activation", "binding-v1")
        private val DRAFT = RecordKey("runtime-draft", "one")
        private val CONFIGURATION = "a".repeat(64)
        private val OTHER_CONFIGURATION = "b".repeat(64)
        private const val DEVICE = "123e4567-e89b-12d3-a456-426614174801"
        private const val OPERATION = "123e4567-e89b-12d3-a456-426614174802"
        private fun uuid(number: Int) = "00000000-0000-4000-8000-${number.toString().padStart(12, '0')}"
        private fun account(expires: Long = Long.MAX_VALUE) = StoredCredentials.Account(ACCOUNT, SecretText("access-runtime-secret"), SecretText("refresh-runtime-secret"), expires, SecretText(DEVICE))
        private fun guest() = StoredCredentials.Guest(GUEST, SecretText(uuid(3000)), SecretText("guest-runtime-secret"), Long.MAX_VALUE)
        private fun bytes(value: String) = PrivateBytes(value.encodeToByteArray())
        private fun field(payload: PrivateBytes, name: String) = assertNotNull(assertIs<WireField.Value<WireDocument>>(WireDocument.decode(payload.copyForCodec()).field(name)).value.stringOrNull())
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun <T> value(result: PortResult<T>): T = when (result) {
            is PortResult.Value -> result.value
            is PortResult.Failure -> fail("Expected runtime success, got ${result.reason}")
        }
        private fun failure(reason: FailureReason, result: PortResult<*>) { assertEquals(reason, assertIs<PortResult.Failure>(result).reason) }
        private fun diagnostic(finding: SessionRecoveryFinding, result: PortResult<SessionRecoveryReport>,
            component: SessionRecoveryComponent? = null): SessionRecoveryReport = value(result).also {
            assertEquals(finding, it.finding)
            assertEquals(component, it.component)
            if (finding != SessionRecoveryFinding.EVIDENCE_UNAVAILABLE) assertNull(it.failureReason)
        }
    }
}
