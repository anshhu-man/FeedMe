package com.feedme.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feedme.core.ports.*
import com.feedme.storage.*
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real four-store native runtime publication in an isolated test UID, with a synthetic verifier.
 * Control/work wrapper failures represent lost application acknowledgements, not engine sync
 * faults. The separate storage VFS suite covers binding sync errors. No provider or OS effects.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSessionSetupPublicationTest {
    @Test fun realRuntimePublishesOnlyAfterExactCompositeBindingWorkSealAndControlAcknowledgement() = publication {
        val access = create()
        assertSame(access.lease, boundary.current())
        assertSame(access, runtime.currentAccess())
        val binding = binding()
        assertEquals(2, binding.schemaVersion)
        val record = SessionActivationCodec.decode(binding.payload)
        val complete = RetirementCodec.decode(control.read().publicationValue()!!.payload) as RetirementState.Complete
        assertEquals(complete.operationId, record.setupOperationId)
        assertEquals(access.originBinding, record.originBinding)
        assertEquals(access.retirement.credentialIncarnation, record.credentialIncarnation)
        assertArrayEquals(access.retirement.dataTarget.copyForStorage(), record.dataTarget.copyForStorage())
        assertEquals(listOf("credential-plan", "work-plan", "credential-select", "work-select",
            "credential-select", "work-seal", "complete-write", "work-resume"), events)
        assertEquals(1, acquisitions); assertEquals(0, restorations)
        assertEquals(1, credentialPlans); assertEquals(2, credentialSelections)
        assertEquals(2, generatedIds)
        assertEquals(0, nativeEffects)
        assertEquals(1, sandbox.dataOwnerAliases().size)
        assertEquals(1, sandbox.credentialAliases().size)
        val state = SessionWorkCodec.decode(work.read().publicationValue()!!.payload) as SessionWorkState.Origin
        assertNull(state.setupPlan)
        assertEquals(record.originBinding, state.origin)
        assertTrue(state.entries.isEmpty())
        val transported = access.credentials.read(SCOPE).publicationValue() as StoredCredentials.Account
        assertNull(transported.refreshToken)
        assertNotNull(transported.deviceSessionId)
    }

    @Test fun lostCompleteReceiptLeavesNoLeaseAndSameLiveRetryAcknowledgesBindingAgainWithoutReplanning() = publication {
        controlFailure = FailurePoint.AFTER_COMPLETE
        reject(FailureReason.OUTCOME_UNKNOWN, createResult())
        val prior = binding()
        val complete = control.read().publicationValue()!!
        val aliases = sandbox.aliases()
        assertNoAccess()
        assertEquals("complete", stateName(complete.payload))
        assertSealed()
        val access = runtime.retryCreate().publicationValue()
        assertEquals(prior.revision + 1, binding().revision)
        assertBytes(prior.payload, binding().payload)
        assertTrue(control.read().publicationValue()!!.revision > complete.revision)
        assertEquals(aliases, sandbox.aliases())
        assertEquals(1, acquisitions); assertEquals(1, credentialPlans); assertEquals(2, generatedIds)
        assertSame(access, runtime.currentAccess())
        assertEquals(0, nativeEffects)
    }

    @Test fun refusedCompleteWriteKeepsOriginalPendingPlanUntilExactBindingAndSealRetrySucceeds() = publication {
        controlFailure = FailurePoint.BEFORE_COMPLETE
        reject(FailureReason.STORAGE_FAILURE, createResult())
        val pending = control.read().publicationValue()!!
        val originalPlan = (RetirementCodec.decode(pending.payload) as RetirementState.PendingSetup).plan
        val prior = binding()
        assertNoAccess(); assertSealed()
        runtime.retryCreate().publicationValue()
        assertEquals(prior.revision + 1, binding().revision)
        assertBytes(prior.payload, binding().payload)
        val operation = SessionSetupPlanCodec.decode(originalPlan.copyForStorage()).operationId
        assertEquals(operation, (RetirementCodec.decode(control.read().publicationValue()!!.payload) as RetirementState.Complete).operationId)
        assertEquals(1, acquisitions); assertEquals(1, credentialPlans); assertEquals(2, generatedIds)
        assertEquals(0, nativeEffects)
    }

    @Test fun sealFailureBeforeOrAfterNativeWriteNeverPublishesAndExactRetryRewritesBinding() {
        for (after in listOf(false, true)) publication {
            failSealAfter = after
            reject(if (after) FailureReason.OUTCOME_UNKNOWN else FailureReason.STORAGE_FAILURE, createResult())
            val prior = binding()
            val pending = control.read().publicationValue()!!
            assertEquals("session-setup-pending", stateName(pending.payload))
            assertNoAccess()
            val workState = SessionWorkCodec.decode(work.read().publicationValue()!!.payload)
            assertTrue(if (after) workState is SessionWorkState.Origin else workState is SessionWorkState.SetupSelected)
            runtime.retryCreate().publicationValue()
            assertEquals(prior.revision + 1, binding().revision)
            assertBytes(prior.payload, binding().payload)
            assertEquals(1, acquisitions); assertEquals(1, credentialPlans)
            assertEquals(0, nativeEffects)
        }
    }

    @Test fun failedCompleteReadbackCannotPromoteVisibleNativeControlIntoPublicationAuthority() = publication {
        controlFailure = FailurePoint.COMPLETE_READBACK
        reject(FailureReason.STORAGE_FAILURE, createResult())
        val prior = binding()
        val complete = control.read().publicationValue()!!
        assertEquals("complete", stateName(complete.payload))
        assertNoAccess(); assertSealed()
        runtime.retryCreate().publicationValue()
        assertEquals(prior.revision + 1, binding().revision)
        assertBytes(prior.payload, binding().payload)
        assertTrue(control.read().publicationValue()!!.revision > complete.revision)
        assertEquals(1, acquisitions); assertEquals(1, credentialPlans)
        assertEquals(0, nativeEffects)
    }

    @Test fun cancellationAfterNativeCompleteCommitCannotPublishItsLateReceiptOrKeepLiveRetryAuthority() = publication {
        coroutineScope {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            afterComplete = { entered.complete(Unit); release.await() }
            val creating = async { createResult() }
            entered.await()
            assertNoAccess()
            runtime.cancelVerification().publicationValue()
            release.complete(Unit)
            assertTrue(creating.await() is PortResult.Failure)
            assertTrue(runtime.retryCreate() is PortResult.Failure)
            assertNoAccess()
            assertEquals("complete", stateName(control.read().publicationValue()!!.payload))
            afterComplete = { }
        }
        val aliases = sandbox.aliases()
        closeStores(); open(restoreOnly = true)
        assertEquals(PrivateSessionPhase.RESTORE_REQUIRED, runtime.recover().publicationValue())
        assertNoAccess()
        runtime.restore().publicationValue()
        assertEquals(aliases, sandbox.aliases())
        assertEquals(1, acquisitions); assertEquals(1, restorations); assertEquals(2, generatedIds)
        assertEquals(0, nativeEffects)
    }

    @Test fun closingDuringCompleteCommitKeepsLateResultClosedAndRequiresFreshNativeRestore() = publication {
        coroutineScope {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            afterComplete = { entered.complete(Unit); release.await() }
            val creating = async { createResult() }
            entered.await()
            assertNoAccess()
            runtime.close().publicationValue()
            release.complete(Unit)
            assertTrue(creating.await() is PortResult.Failure)
            assertEquals(PrivateSessionPhase.CLOSED, runtime.phase())
            assertNoAccess()
            afterComplete = { }
        }
        val prior = binding()
        closeStores(); open(restoreOnly = true)
        assertEquals(PrivateSessionPhase.RESTORE_REQUIRED, runtime.recover().publicationValue())
        assertNoAccess()
        runtime.restore().publicationValue()
        assertBytes(prior.payload, binding().payload)
        assertEquals(1, acquisitions); assertEquals(1, restorations); assertEquals(0, nativeEffects)
    }

    @Test fun wrongCompletedOperationCannotJoinAnOtherwiseMatchingNativeVersionTwoBinding() = publication {
        create(); closeStores(); open(restoreOnly = true)
        val current = control.read().publicationValue()!!
        control.compareAndSet(current.revision,
            RetirementCodec.encode(RetirementState.Complete(UUID.randomUUID().toString()))).publicationValue()
        assertEquals(PrivateSessionPhase.RESTORE_REQUIRED, runtime.recover().publicationValue())
        // Recovery legitimately acknowledges Complete. The failed restore itself must preserve
        // this post-recovery native evidence and reject before any credential/provider read.
        val files = sandbox.fileSnapshot()
        val aliases = sandbox.aliases()
        val reads = credentialReads
        reject(FailureReason.CONFLICT, runtime.restore())
        assertEquals(reads, credentialReads)
        assertEquals(0, restorations)
        assertNoAccess()
        sandbox.assertFilesEqual(files)
        assertEquals(aliases, sandbox.aliases())
        assertEquals(0, nativeEffects)
    }

    @Test fun pendingNativeBindingAfterRestartCannotRetryRestoreOrFallBackToCredentialOnlyCleanup() = publication {
        controlFailure = FailurePoint.BEFORE_COMPLETE
        reject(FailureReason.STORAGE_FAILURE, createResult())
        val original = control.read().publicationValue()!!
        val aliases = sandbox.aliases()
        closeStores(); open(restoreOnly = true)
        val files = sandbox.fileSnapshot()
        val reads = credentialReads
        reject(FailureReason.CONFLICT, runtime.recover())
        assertTrue(runtime.retryCreate() is PortResult.Failure)
        reject(FailureReason.CONFLICT, runtime.restore())
        reject(FailureReason.CONFLICT, runtime.prepareInterruptedSetupDiscard())
        val legacy = CredentialCreateCoordinator(control, boundary, Dispatchers.Main.immediate,
            CredentialCreateRecoveryFactory { throw AssertionError("Composite journal cannot authorize legacy cleanup") })
        reject(FailureReason.CONFLICT, legacy.inspectPending())
        reject(FailureReason.CONFLICT, legacy.recoverAbort())
        assertEquals(reads, credentialReads)
        assertEquals(1, acquisitions); assertEquals(0, restorations)
        assertNoAccess()
        assertBytes(original.payload, control.read().publicationValue()!!.payload)
        assertEquals(original.revision, control.read().publicationValue()!!.revision)
        assertEquals(aliases, sandbox.aliases())
        sandbox.assertFilesEqual(files)
        assertEquals(0, nativeEffects)
    }

    private fun publication(block: suspend Fixture.() -> Unit) = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val sandbox = AndroidRetirementIntegrationSandbox.create(InstrumentationRegistry.getInstrumentation().targetContext)
            val fixture = Fixture(sandbox)
            try { fixture.open(); fixture.block() }
            finally { withContext(NonCancellable) { fixture.finish() } }
        }
    }

    private enum class FailurePoint { BEFORE_COMPLETE, AFTER_COMPLETE, COMPLETE_READBACK }

    private class Fixture(val sandbox: AndroidRetirementIntegrationSandbox) {
        lateinit var data: EncryptedStateDatabase
        lateinit var control: EncryptedSessionControlStore
        lateinit var work: EncryptedSessionWorkStore
        lateinit var credentials: AndroidCredentialStore
        lateinit var runtime: PrivateSessionRuntime
        var boundary = SessionBoundary()
        var acquisitions = 0
        var restorations = 0
        var credentialPlans = 0
        var credentialSelections = 0
        var credentialReads = 0
        var generatedIds = 0
        var nativeEffects = 0
        val events = mutableListOf<String>()
        var controlFailure: FailurePoint? = null
        var failSealAfter: Boolean? = null
        var afterComplete: suspend () -> Unit = { }
        private var acknowledgedControlRevision = 0L
        private var awaitingReadback: SessionControlRecord? = null
        private var failedReadback = false
        private var originalPlan: SessionSetupPlan? = null
        private var closeFailed = false
        private val closers = mutableListOf<suspend () -> PortResult<Unit>>()

        suspend fun open(restoreOnly: Boolean = false) {
            check(closers.isEmpty())
            boundary = SessionBoundary()
            acknowledgedControlRevision = 0; awaitingReadback = null; failedReadback = false
            data = AndroidStateDatabase.open(sandbox.context).publicationValue().also { v -> closers += { v.close() } }
            control = AndroidSessionControlStore.open(sandbox.context).publicationValue().also { v -> closers += { v.close() } }
            work = AndroidSessionWorkStore.open(sandbox.context).publicationValue().also { v -> closers += { v.close() } }
            credentials = AndroidCredentialStore.open(sandbox.context).publicationValue().also { v -> closers += { v.close() } }
            val nativeControl = control
            val nativeWork = work
            val nativeCredentials = credentials
            val countedControl = object : SessionControlStore {
                override suspend fun read(): PortResult<SessionControlRecord?> {
                    if (failedReadback) {
                        failedReadback = false; awaitingReadback = null
                        return PortResult.Failure(FailureReason.STORAGE_FAILURE)
                    }
                    val result = nativeControl.read()
                    val receipt = awaitingReadback
                    if (receipt != null && result is PortResult.Value) {
                        val actual = result.value
                        if (actual != null && actual.revision == receipt.revision && sameBytes(actual.payload, receipt.payload))
                            acknowledgedControlRevision = actual.revision
                        awaitingReadback = null
                    }
                    return result
                }

                override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> {
                    awaitingReadback = null
                    assertNull("Control acknowledgement must precede any new lease", boundary.current())
                    val before = nativeControl.read().publicationValue()!!
                    val completing = stateName(before.payload) == "session-setup-pending" && stateName(payload) == "complete"
                    if (completing) {
                        events += "complete-write"
                        assertSealed()
                        val activation = SessionActivationCodec.decode(binding().payload)
                        assertEquals(activation.setupOperationId, (RetirementCodec.decode(payload) as RetirementState.Complete).operationId)
                    }
                    val injected = if (stateName(payload) == "complete") controlFailure.also { controlFailure = null } else null
                    if (injected == FailurePoint.BEFORE_COMPLETE) return PortResult.Failure(FailureReason.STORAGE_FAILURE)
                    val result = nativeControl.compareAndSet(expectedRevision, payload)
                    if (result is PortResult.Value) {
                        if (completing) afterComplete()
                        if (injected == FailurePoint.AFTER_COMPLETE) return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
                        awaitingReadback = result.value
                        failedReadback = injected == FailurePoint.COMPLETE_READBACK
                    }
                    return result
                }
            }
            val countedWork = object : SessionControlStore, WorkOriginPlanAuthentication by nativeWork {
                override suspend fun read() = nativeWork.read()
                override suspend fun signOriginPlan(expected: SessionControlRecord, proposal: PrivateBytes): PortResult<PrivateBytes> {
                    events += "work-plan"
                    assertNull(boundary.current())
                    return nativeWork.signOriginPlan(expected, proposal)
                }
                override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> {
                    val before = nativeWork.read().publicationValue()!!
                    val prior = SessionWorkCodec.decode(before.payload)
                    val next = SessionWorkCodec.decode(payload)
                    val sealing = prior is SessionWorkState.SetupSelected && next is SessionWorkState.Origin
                    if (next is SessionWorkState.SetupSelected) {
                        events += "work-select"
                        assertNull(boundary.current())
                        assertPendingAcknowledged()
                        val owner = data.inspectOwnerState(SCOPE).publicationValue()
                        assertNotNull(owner.target); assertFalse(owner.hasRecords)
                    } else if (sealing || (next is SessionWorkState.Origin && next.setupPlan != null)) {
                        events += "work-seal"
                        assertNull(boundary.current())
                        assertEquals(2, binding().schemaVersion)
                        assertNotNull(SessionActivationCodec.decode(binding().payload).setupOperationId)
                    } else if (prior is SessionWorkState.Origin && next is SessionWorkState.Origin) {
                        if (prior.setupPlan != null) events += "work-resume"
                        val currentControl = nativeControl.read().publicationValue()!!
                        assertEquals("complete", stateName(currentControl.payload))
                        assertEquals(currentControl.revision, acknowledgedControlRevision)
                        assertNotNull(boundary.current())
                    }
                    val failAfter = if (sealing) failSealAfter.also { failSealAfter = null } else null
                    if (failAfter == false) return PortResult.Failure(FailureReason.STORAGE_FAILURE)
                    val result = nativeWork.compareAndSet(expectedRevision, payload)
                    if (result is PortResult.Value && failAfter == true) return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
                    return result
                }
            }
            val countedCredentials = object : PlannedCredentialCreateStore by nativeCredentials {
                override suspend fun read(scope: StorageScope): PortResult<CredentialSnapshot?> {
                    credentialReads++
                    return nativeCredentials.read(scope)
                }
                override suspend fun planCreate(expectedSlotRevision: Long, credentials: StoredCredentials): PortResult<CredentialCreatePlan> {
                    check(!restoreOnly)
                    credentialPlans++; events += "credential-plan"
                    assertNull(boundary.current())
                    return nativeCredentials.planCreate(expectedSlotRevision, credentials)
                }
                override suspend fun commitPlannedCreate(plan: CredentialCreatePlan, credentials: StoredCredentials): PortResult<CredentialSnapshot> {
                    check(!restoreOnly)
                    credentialSelections++; events += "credential-select"
                    assertNull(boundary.current())
                    val current = nativeControl.read().publicationValue()!!
                    val pending = RetirementCodec.decode(current.payload)
                    val expected = if (pending is RetirementState.PendingSetup) {
                        assertPendingAcknowledged()
                        originalPlan = pending.plan
                        SessionSetupPlanCodec.decode(pending.plan.copyForStorage())
                    } else {
                        // An exact visible Complete can only re-ack already-selected resources;
                        // new binding/work/control acknowledgements still precede publication.
                        val retained = SessionSetupPlanCodec.decode(checkNotNull(originalPlan).copyForStorage())
                        assertEquals(retained.operationId, (pending as RetirementState.Complete).operationId)
                        assertSealed()
                        retained
                    }
                    assertBytes(expected.credentialPlan.copyForStorage(), plan.copyForStorage())
                    return nativeCredentials.commitPlannedCreate(plan, credentials)
                }
            }
            runtime = PrivateSessionRuntime.open(countedControl, countedWork, data, countedCredentials, boundary,
                Dispatchers.Main.immediate, CONFIGURATION,
                object : NativeSessionVerifier {
                    override suspend fun acquire(): PortResult<StoredCredentials> {
                        check(!restoreOnly); acquisitions++
                        return PortResult.Value(verified())
                    }
                    override suspend fun restore(snapshot: CredentialSnapshot): PortResult<PrivateSessionAccessMode> {
                        restorations++
                        assertNull(boundary.current())
                        assertEquals(SCOPE, snapshot.scope)
                        return PortResult.Value(PrivateSessionAccessMode.ONLINE)
                    }
                }, NativeWorkCancellationPort { forbiddenEffect() },
                NativeWorkIdSource { check(!restoreOnly); generatedIds++; UUID.randomUUID().toString() },
                NativeWorkExecutionPolicy { _, _, _, _ -> forbiddenEffect() },
            ).publicationValue().also { v -> closers += { v.close() } }
        }

        suspend fun createResult(): PortResult<PrivateSessionAccess> {
            assertEquals(PrivateSessionPhase.SIGNED_OUT, runtime.recover().publicationValue())
            return runtime.create()
        }
        suspend fun create() = createResult().publicationValue()
        suspend fun binding() = checkNotNull(data.inspectRecord(SCOPE, BINDING).publicationValue().record)
        suspend fun assertNoAccess() {
            assertNull(boundary.current()); assertNull(runtime.currentAccess())
        }
        suspend fun assertSealed() {
            val state = SessionWorkCodec.decode(work.read().publicationValue()!!.payload) as SessionWorkState.Origin
            assertNotNull(state.setupPlan); assertFalse(state.retiring); assertTrue(state.entries.isEmpty())
        }
        private suspend fun assertPendingAcknowledged(): RetirementState.PendingSetup {
            val current = control.read().publicationValue()!!
            assertEquals(current.revision, acknowledgedControlRevision)
            return (RetirementCodec.decode(current.payload) as RetirementState.PendingSetup).also { assertFalse(it.abortRequested) }
        }
        private fun forbiddenEffect(): Nothing {
            nativeEffects++
            throw AssertionError("Publication fixture cannot schedule or execute native work")
        }
        suspend fun closeStores() {
            var failed = false
            for (close in closers.asReversed()) try { if (close() is PortResult.Failure) failed = true } catch (_: Exception) { failed = true }
            closers.clear()
            if (failed) closeFailed = true
            if (closeFailed) {
                sandbox.releasePreservingFixture()
                throw AssertionError("Preserving exact fixture after unacknowledged native close")
            }
        }
        suspend fun finish() {
            try { closeStores() } catch (failure: Throwable) { sandbox.releasePreservingFixture(); throw failure }
            withContext(Dispatchers.IO) { sandbox.removeOwnedFixture() }
        }
    }

    private companion object {
        val SCOPE = StorageScope("native-publication", ActorKind.ACCOUNT, "synthetic-publication-owner")
        val BINDING = RecordKey("session-activation", "binding-v1")
        val CONFIGURATION = "9".repeat(64)
        fun verified() = StoredCredentials.Account(SCOPE, SecretText("synthetic-publication-access"),
            SecretText("synthetic-publication-refresh"), Long.MAX_VALUE,
            SecretText("00000000-0000-4000-8000-000000000981"))
        fun stateName(payload: PrivateBytes) = when (RetirementCodec.decode(payload)) {
            RetirementState.Idle -> "idle"
            is RetirementState.Complete -> "complete"
            is RetirementState.PendingSetup -> "session-setup-pending"
            else -> "other"
        }
        fun sameBytes(a: PrivateBytes, b: PrivateBytes) = a.copyForCodec().contentEquals(b.copyForCodec())
        fun assertBytes(a: PrivateBytes, b: PrivateBytes) = assertArrayEquals(a.copyForCodec(), b.copyForCodec())
        fun reject(reason: FailureReason, result: PortResult<*>) = assertEquals(PortResult.Failure(reason), result)
        fun <T> PortResult<T>.publicationValue(): T = when (this) {
            is PortResult.Value -> value
            is PortResult.Failure -> throw AssertionError("Expected native publication value, got $reason")
        }
    }
}
