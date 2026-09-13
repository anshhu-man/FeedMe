package com.feedme.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feedme.core.ports.*
import com.feedme.storage.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Separate isolated test-APK invocation. Actual runtime and four public native owners, with
 * synthetic fixtures only: verifier, credential reads, identity allocation and OS effects fail.
 * Response-loss wrappers run after real native success; they are not SQLite VFS/power-loss faults.
 */
@RunWith(AndroidJUnit4::class)
class AndroidCompositeSetupAbortTest {
    @Test fun explicitConfirmationAbortsEveryPreparedThroughSealedStageWithoutPublishingOrClosingOwners() {
        for (stage in Stage.entries) integration {
            prepare(stage); openRuntime()
            val before = sandbox.fileSnapshot(); val aliases = sandbox.aliases()
            val original = control.read().checked()!!
            val proposal = runtime!!.preparePendingSetupAbort().checked()
            assertFalse(proposal.toString().contains(SCOPE.actorId))
            assertFalse(proposal.toString().contains(OPERATION))
            failure(runtime!!.retryPendingSetupAbort(), FailureReason.CONFLICT)
            sandbox.assertFilesEqual(before); assertEquals(aliases, sandbox.aliases()); assertNoEffects()
            assertFalse((RetirementCodec.decode(control.read().checked()!!.payload) as RetirementState.PendingSetup).abortRequested)
            runtime!!.confirmPendingSetupAbort(proposal).checked()
            assertCompleted()
            assertTrue(control.read().checked()!!.revision > original.revision)
            assertEquals(listOf("work", "credential"), resourceEvents)
            assertTrue(sandbox.dataOwnerAliases().isEmpty()); assertTrue(sandbox.credentialAliases().isEmpty())
            assertEquals(PrivateSessionPhase.STARTUP, runtime!!.phase())
            // Parent-owned handles remain open and usable for metadata, never implicitly reset.
            assertNull(credentials.state().checked().owner)
            assertEquals(StateActivationStatus.ABORTED, data.inspectPlannedActivation(SCOPE, details.dataPlan).checked().status)
            assertTrue(SessionWorkCodec.decode(work.read().checked()!!.payload) is SessionWorkState.SetupAborted)
            val completedFiles = sandbox.fileSnapshot(); val completedAliases = sandbox.aliases()
            failure(runtime!!.confirmPendingSetupAbort(proposal), FailureReason.STALE_SESSION)
            sandbox.assertFilesEqual(completedFiles); assertEquals(completedAliases, sandbox.aliases()); assertNoEffects()
        }
    }

    @Test fun selectedMissingAndDamagedCredentialMaterialCanBeExplicitlyAbortedWithoutSecretReads() {
        for (damage in CredentialDiagnosticDamage.entries) integration {
            prepare(Stage.SEALED)
            closeStores(); sandbox.damageSelectedCredentials(damage); openStores(); openRuntime()
            val proposal = runtime!!.preparePendingSetupAbort().checked()
            assertTrue(resourceEvents.isEmpty()); assertNoEffects()
            runtime!!.confirmPendingSetupAbort(proposal).checked()
            assertCompleted(); assertEquals(listOf("work", "credential"), resourceEvents); assertNoEffects()
        }
    }

    @Test fun changedControlWorkOrBindingEvidenceInvalidatesProposalBeforeAnyCleanup() {
        for (change in listOf("control", "work", "binding")) integration {
            prepare(Stage.SEALED); openRuntime()
            val proposal = runtime!!.preparePendingSetupAbort().checked()
            when (change) {
                "control" -> control.read().checked()!!.let { control.compareAndSet(it.revision, it.payload).checked() }
                "work" -> work.read().checked()!!.let { work.compareAndSet(it.revision, it.payload).checked() }
                else -> data.bindPlannedActivation(SCOPE, details.dataPlan, 2, bindingPayload()).checked()
            }
            val before = sandbox.fileSnapshot(); val aliases = sandbox.aliases()
            failure(runtime!!.confirmPendingSetupAbort(proposal), FailureReason.CONFLICT)
            sandbox.assertFilesEqual(before); assertEquals(aliases, sandbox.aliases())
            assertTrue(resourceEvents.isEmpty()); assertNoEffects()
            assertFalse((RetirementCodec.decode(control.read().checked()!!.payload) as RetirementState.PendingSetup).abortRequested)
        }
    }

    @Test fun missingAbortCapabilityAndChangedConfigurationNeverFallBackToLegacyCleanup() {
        for (configChanged in listOf(false, true)) integration {
            prepare(Stage.SEALED)
            openRuntime(if (configChanged) OTHER_CONFIGURATION else CONFIGURATION, capability = configChanged)
            val before = sandbox.fileSnapshot(); val aliases = sandbox.aliases()
            failure(runtime!!.preparePendingSetupAbort(), if (configChanged) FailureReason.CONFLICT else FailureReason.NOT_CONFIGURED)
            sandbox.assertFilesEqual(before); assertEquals(aliases, sandbox.aliases())
            assertTrue(resourceEvents.isEmpty()); assertNoEffects()
        }
    }

    @Test fun failedOrLostConfirmationWriteCannotAuthorizeEvenTheFirstNativeAbort() {
        for (lostAfterCommit in listOf(false, true)) integration {
            prepare(Stage.SEALED); openRuntime()
            val proposal = runtime!!.preparePendingSetupAbort().checked()
            val original = control.read().checked()!!
            val privateBefore = privateFiles(); val aliases = sandbox.aliases()
            confirmationFailure = if (lostAfterCommit) AckFailure.AFTER else AckFailure.BEFORE
            failure(runtime!!.confirmPendingSetupAbort(proposal), if (lostAfterCommit) FailureReason.OUTCOME_UNKNOWN else FailureReason.STORAGE_FAILURE)
            assertPrivateFiles(privateBefore); assertEquals(aliases, sandbox.aliases())
            assertTrue(resourceEvents.isEmpty()); assertNoEffects()
            val observed = control.read().checked()!!
            val pending = RetirementCodec.decode(observed.payload) as RetirementState.PendingSetup
            assertEquals(lostAfterCommit, pending.abortRequested)
            assertEquals(original.revision + if (lostAfterCommit) 1 else 0, observed.revision)
            if (lostAfterCommit) runtime!!.retryPendingSetupAbort().checked()
            else runtime!!.confirmPendingSetupAbort(runtime!!.preparePendingSetupAbort().checked()).checked()
            assertCompleted(); assertEquals(listOf("work", "credential"), resourceEvents); assertNoEffects()
        }
    }

    @Test fun visibleWorkAbortAndRepeatedLostReplayAcknowledgementCannotAdvanceToPrivateCleanup() = integration {
        prepare(Stage.SEALED); openRuntime()
        val proposal = runtime!!.preparePendingSetupAbort().checked()
        workLosses = 2
        failure(runtime!!.confirmPendingSetupAbort(proposal), FailureReason.OUTCOME_UNKNOWN)
        assertRequested(); assertEquals(listOf("work"), resourceEvents)
        assertEquals(StateActivationStatus.SELECTED_NONEMPTY, data.inspectPlannedActivation(SCOPE, details.dataPlan).checked().status)
        assertEquals(CredentialCreateRecoveryStatus.SELECTED, credentials.inspectPlannedCreate(SCOPE, details.credentialPlan).checked().status)
        val first = work.read().checked()!!
        failure(runtime!!.retryPendingSetupAbort(), FailureReason.OUTCOME_UNKNOWN)
        val replay = work.read().checked()!!
        assertEquals(first.revision + 1, replay.revision); assertBytes(first.payload, replay.payload)
        assertEquals(listOf("work", "work"), resourceEvents)
        assertEquals(StateActivationStatus.SELECTED_NONEMPTY, data.inspectPlannedActivation(SCOPE, details.dataPlan).checked().status)
        runtime!!.retryPendingSetupAbort().checked()
        assertEquals(replay.revision + 1, work.read().checked()!!.revision)
        assertEquals(listOf("work", "work", "work", "credential"), resourceEvents)
        assertCompleted(); assertNoEffects()
    }

    @Test fun lostCredentialAbortReceiptRetainsConfirmedJournalAndFreshRetryReacknowledgesAllResources() = integration {
        prepare(Stage.SEALED); openRuntime()
        val proposal = runtime!!.preparePendingSetupAbort().checked()
        credentialLosses = 1
        failure(runtime!!.confirmPendingSetupAbort(proposal), FailureReason.OUTCOME_UNKNOWN)
        assertRequested(); assertNativeAborted()
        val workBefore = work.read().checked()!!
        val dataBefore = data.inspectPlannedActivationState(SCOPE, details.dataPlan).checked().fingerprint
        runtime!!.retryPendingSetupAbort().checked()
        assertEquals(workBefore.revision + 1, work.read().checked()!!.revision)
        assertFalse(dataBefore.copyForCodec().contentEquals(data.inspectPlannedActivationState(SCOPE, details.dataPlan).checked().fingerprint.copyForCodec()))
        assertEquals(listOf("work", "credential", "work", "credential"), resourceEvents)
        assertCompleted(); assertNoEffects()
    }

    @Test fun lostFinalControlReceiptNeedsExactLiveRetryAndFreshRuntimeCannotInferItsAuthority() {
        for (reopenRuntime in listOf(false, true)) integration {
            prepare(Stage.SEALED); openRuntime()
            completeLosses = 1
            failure(runtime!!.confirmPendingSetupAbort(runtime!!.preparePendingSetupAbort().checked()), FailureReason.OUTCOME_UNKNOWN)
            val visible = control.read().checked()!!
            assertEquals(OPERATION, (RetirementCodec.decode(visible.payload) as RetirementState.Complete).operationId)
            assertNativeAborted(); assertNoEffects()
            if (reopenRuntime) {
                runtime!!.close().checked(); runtime = null; openRuntime()
                val before = sandbox.fileSnapshot(); val aliases = sandbox.aliases()
                failure(runtime!!.retryPendingSetupAbort(), FailureReason.CONFLICT)
                sandbox.assertFilesEqual(before); assertEquals(aliases, sandbox.aliases())
            } else {
                runtime!!.retryPendingSetupAbort().checked()
                val acknowledged = control.read().checked()!!
                assertTrue(acknowledged.revision > visible.revision); assertBytes(visible.payload, acknowledged.payload)
                assertCompleted()
            }
            assertNoEffects()
        }
    }

    @Test fun closedGenerationAndCancelledOrClosedSuspendedConfirmationCannotBorrowProposalAuthority() {
        for (action in listOf("new-runtime", "cancel", "close")) integration {
            prepare(Stage.SEALED); openRuntime()
            val proposal = runtime!!.preparePendingSetupAbort().checked()
            val before = sandbox.fileSnapshot(); val aliases = sandbox.aliases()
            if (action == "new-runtime") {
                runtime!!.close().checked(); runtime = null; openRuntime()
                failure(runtime!!.confirmPendingSetupAbort(proposal), FailureReason.STALE_SESSION)
            } else {
                val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var once = true
                afterCredentialInspection = { if (once) { once = false; entered.complete(Unit); release.await() } }
                coroutineScope {
                    val pending = async { runtime!!.confirmPendingSetupAbort(proposal) }
                    entered.await()
                    if (action == "cancel") {
                        pending.cancel(); release.complete(Unit)
                        try { pending.await(); fail("Cancelled confirmation returned a receipt") } catch (_: CancellationException) { }
                    } else {
                        val closing = async(start = CoroutineStart.UNDISPATCHED) { runtime!!.close() }
                        assertEquals(PrivateSessionPhase.CLOSED, runtime!!.phase())
                        release.complete(Unit); failure(pending.await(), FailureReason.STORAGE_FAILURE); closing.await().checked()
                    }
                }
            }
            sandbox.assertFilesEqual(before); assertEquals(aliases, sandbox.aliases())
            assertTrue(resourceEvents.isEmpty()); assertNoEffects()
        }
    }

    @Test fun malformedJournalAdditionalRowsTombstonesAndUsedWorkBlockConfirmationWithoutEffects() {
        for (damage in listOf("control", "row", "tombstone", "binding", "used-work")) integration {
            prepare(Stage.SEALED)
            when (damage) {
                "control" -> control.compareAndSet(control.read().checked()!!.revision,
                    PrivateBytes("{\"version\":1,\"state\":\"session-setup-pending\"}".encodeToByteArray())).checked()
                "used-work" -> {
                    val old = work.read().checked()!!
                    val origin = SessionWorkCodec.decode(old.payload) as SessionWorkState.Origin
                    work.compareAndSet(old.revision, SessionWorkCodec.encode(origin.used())).checked()
                }
                else -> {
                    val store = data.resume(SCOPE).checked()!!
                    if (damage == "binding") {
                        val previous = store.read(SCOPE, BINDING).checked()!!
                        val parsed = SessionActivationCodec.decode(previous.payload)
                        val wrong = SessionActivationRecord(parsed.scope, parsed.credentialIncarnation, parsed.originBinding,
                            parsed.dataTarget, OTHER_CONFIGURATION, parsed.setupOperationId)
                        store.commit(SCOPE, listOf(StoreMutation.Put(BINDING, previous.revision, 2, SessionActivationCodec.encode(wrong)))).checked()
                    } else {
                        val revision = store.commit(SCOPE, listOf(StoreMutation.Put(DRAFT, null, 1, PrivateBytes("private-draft".encodeToByteArray())))).checked()[DRAFT]!!
                        if (damage == "tombstone") store.commit(SCOPE, listOf(StoreMutation.Delete(DRAFT, revision))).checked()
                    }
                }
            }
            openRuntime()
            val before = sandbox.fileSnapshot(); val aliases = sandbox.aliases()
            assertTrue(runtime!!.preparePendingSetupAbort() is PortResult.Failure)
            sandbox.assertFilesEqual(before); assertEquals(aliases, sandbox.aliases())
            assertTrue(resourceEvents.isEmpty()); assertNoEffects()
        }
    }

    private fun integration(block: suspend Fixture.() -> Unit) = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val sandbox = AndroidRetirementIntegrationSandbox.create(InstrumentationRegistry.getInstrumentation().targetContext)
            val fixture = Fixture(sandbox)
            try { fixture.openStores(); fixture.block() } finally { withContext(NonCancellable) { fixture.finish() } }
        }
    }

    private enum class Stage { PREPARED, CREDENTIAL, DATA, WORK, BOUND, SEALED }
    private enum class AckFailure { NONE, BEFORE, AFTER }

    private class Fixture(val sandbox: AndroidRetirementIntegrationSandbox) {
        lateinit var data: EncryptedStateDatabase
        lateinit var control: EncryptedSessionControlStore
        lateinit var work: EncryptedSessionWorkStore
        lateinit var credentials: AndroidCredentialStore
        lateinit var plan: SessionSetupPlan
        lateinit var details: SessionSetupPlanRecord
        var runtime: PrivateSessionRuntime? = null
        var boundary = SessionBoundary()
        var confirmationFailure = AckFailure.NONE
        var workLosses = 0
        var credentialLosses = 0
        var completeLosses = 0
        val resourceEvents = mutableListOf<String>()
        var afterCredentialInspection: suspend () -> Unit = { }
        private var effects = 0
        private var closeFailed = false
        private val closers = mutableListOf<suspend () -> PortResult<Unit>>()

        suspend fun openStores() {
            check(closers.isEmpty()); boundary = SessionBoundary()
            data = AndroidStateDatabase.open(sandbox.context).checked().also { v -> closers += { v.close() } }
            control = AndroidSessionControlStore.open(sandbox.context).checked().also { v -> closers += { v.close() } }
            work = AndroidSessionWorkStore.open(sandbox.context).checked().also { v -> closers += { v.close() } }
            credentials = AndroidCredentialStore.open(sandbox.context).checked().also { v -> closers += { v.close() } }
        }

        suspend fun prepare(stage: Stage) {
            val identity = StoredCredentials.Account(SCOPE, SecretText(ACCESS), SecretText(REFRESH), Long.MAX_VALUE, SecretText(DEVICE))
            val credential = credentials.planCreate(credentials.state().checked().revision, identity).checked()
            val dataPlan = data.planActivation(SCOPE).checked()
            val planner = SessionWorkRegistry.open(work, boundary, Dispatchers.Main.immediate,
                NativeWorkCancellationPort { forbidden() }, NativeWorkIdSource { ORIGIN },
                NativeWorkAdmissionPolicy { forbidden() }, NativeWorkExecutionPolicy { _, _, _, _ -> forbidden() }).checked()
            try {
                val workPlan = planner.planOrigin(SCOPE, work.read().checked()!!.revision).checked()
                details = SessionSetupPlanRecord(OPERATION, SCOPE, CONFIGURATION, credential, dataPlan, workPlan)
                plan = SessionSetupPlan.create(details)
                control.compareAndSet(control.read().checked()!!.revision, RetirementCodec.encode(RetirementState.PendingSetup(plan, false))).checked()
                if (stage >= Stage.CREDENTIAL) credentials.commitPlannedCreate(credential, identity).checked()
                if (stage >= Stage.DATA) data.commitPlannedActivation(SCOPE, dataPlan).checked()
                if (stage >= Stage.WORK) planner.selectOrigin(workPlan).checked()
                if (stage >= Stage.BOUND) data.bindPlannedActivation(SCOPE, dataPlan, 2, bindingPayload()).checked()
                if (stage >= Stage.SEALED) planner.sealOrigin(workPlan).checked()
            } finally { planner.close().checked() }
            assertNull(boundary.current())
        }

        suspend fun bindingPayload(): PrivateBytes {
            val target = data.inspectPlannedBinding(SCOPE, details.dataPlan).checked().target!!
            val incarnation = CredentialCreatePlanCodec.decode(details.credentialPlan.copyForStorage()).incarnation
            return SessionActivationCodec.encode(SessionActivationRecord(SCOPE, incarnation, ORIGIN, target, CONFIGURATION, OPERATION))
        }

        suspend fun openRuntime(configuration: String = CONFIGURATION, capability: Boolean = true) {
            val originalData = data.inspectPlannedActivationState(SCOPE, details.dataPlan).checked()
            val originalCredential = credentials.inspectPlannedCreate(SCOPE, details.credentialPlan).checked()
            val observedCredentials = object : PlannedCredentialCreateStore by credentials, CredentialCreatePlanInspection {
                override suspend fun inspectPlannedCreate(scope: StorageScope, plan: CredentialCreatePlan): PortResult<CredentialCreatePlanObservation> {
                    val result = credentials.inspectPlannedCreate(scope, plan)
                    afterCredentialInspection(); return result
                }
                override suspend fun read(scope: StorageScope): PortResult<CredentialSnapshot?> = forbidden()
                override suspend fun create(expectedSlotRevision: Long, credentials: StoredCredentials): PortResult<CredentialSnapshot> = forbidden()
                override suspend fun planCreate(expectedSlotRevision: Long, credentials: StoredCredentials): PortResult<CredentialCreatePlan> = forbidden()
                override suspend fun commitPlannedCreate(plan: CredentialCreatePlan, credentials: StoredCredentials): PortResult<CredentialSnapshot> = forbidden()
                override suspend fun retire(scope: StorageScope, credentialIncarnation: String): PortResult<Unit> = forbidden()
                override suspend fun replace(expected: CredentialSnapshot, credentials: StoredCredentials): PortResult<CredentialSnapshot> = forbidden()
                override suspend fun attachDeviceSession(expected: CredentialSnapshot, deviceSessionId: SecretText): PortResult<CredentialSnapshot> = forbidden()
            }
            val capable = object : PlannedCredentialCreateStore by observedCredentials,
                CredentialCreatePlanInspection by observedCredentials, CredentialCreatePlanAbort {
                override suspend fun abortPlannedCreate(scope: StorageScope, plan: CredentialCreatePlan): PortResult<Unit> {
                    assertRequested(); assertBytes(details.credentialPlan.copyForStorage(), plan.copyForStorage())
                    assertEquals(SCOPE, scope)
                    assertTrue(SessionWorkCodec.decode(work.read().checked()!!.payload) is SessionWorkState.SetupAborted)
                    assertEquals(StateActivationStatus.ABORTED, data.inspectPlannedActivation(SCOPE, details.dataPlan).checked().status)
                    resourceEvents += "credential"
                    val result = credentials.abortPlannedCreate(scope, plan)
                    if (result is PortResult.Value && credentialLosses > 0) {
                        credentialLosses--; return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
                    }
                    return result
                }
            }
            val countedControl = object : SessionControlStore {
                override suspend fun read() = control.read()
                override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> {
                    val state = RetirementCodec.decode(payload)
                    val confirming = state is RetirementState.PendingSetup && state.abortRequested
                    val fault = if (confirming) confirmationFailure.also { confirmationFailure = AckFailure.NONE } else AckFailure.NONE
                    if (fault == AckFailure.BEFORE) return PortResult.Failure(FailureReason.STORAGE_FAILURE)
                    if (state is RetirementState.Complete) { assertEquals(OPERATION, state.operationId); assertNativeAborted() }
                    val result = control.compareAndSet(expectedRevision, payload)
                    if (result is PortResult.Value && fault == AckFailure.AFTER) return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
                    if (result is PortResult.Value && state is RetirementState.Complete && completeLosses > 0) {
                        completeLosses--; return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
                    }
                    return result
                }
            }
            val countedWork = object : SessionControlStore, WorkOriginPlanAuthentication by work {
                override suspend fun read() = work.read()
                override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> {
                    assertRequested()
                    val consumed = SessionWorkCodec.decode(payload) as? SessionWorkState.SetupAborted
                        ?: throw AssertionError("Only exact setup abort may mutate this native work fixture")
                    assertBytes(details.workOriginPlan.copyForStorage(), consumed.plan.copyForStorage())
                    if (resourceEvents.isEmpty()) {
                        // The first work write must precede either downstream component's
                        // cleanup, not merely precede the final credential callback.
                        val currentData = data.inspectPlannedActivationState(SCOPE, details.dataPlan).checked()
                        val currentCredential = credentials.inspectPlannedCreate(SCOPE, details.credentialPlan).checked()
                        assertEquals(originalData.status, currentData.status)
                        assertBytes(originalData.fingerprint, currentData.fingerprint)
                        assertEquals(originalCredential.status, currentCredential.status)
                        assertBytes(originalCredential.fingerprint, currentCredential.fingerprint)
                    }
                    resourceEvents += "work"
                    val result = work.compareAndSet(expectedRevision, payload)
                    if (result is PortResult.Value && workLosses > 0) {
                        workLosses--; return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
                    }
                    return result
                }
                override suspend fun signOriginPlan(expected: SessionControlRecord, proposal: PrivateBytes): PortResult<PrivateBytes> = forbidden()
            }
            runtime = PrivateSessionRuntime.open(countedControl, countedWork, data,
                if (capability) capable else observedCredentials, boundary, Dispatchers.Main.immediate,
                configuration, object : NativeSessionVerifier {
                    override suspend fun acquire(): PortResult<StoredCredentials> = forbidden()
                    override suspend fun restore(snapshot: CredentialSnapshot): PortResult<PrivateSessionAccessMode> = forbidden()
                }, NativeWorkCancellationPort { forbidden() }, NativeWorkIdSource { forbidden() },
                NativeWorkExecutionPolicy { _, _, _, _ -> forbidden() }).checked()
        }

        suspend fun assertRequested() {
            val requested = RetirementCodec.decode(control.read().checked()!!.payload) as? RetirementState.PendingSetup
                ?: throw AssertionError("Native cleanup requires exact durable pending control")
            assertTrue(requested.abortRequested); assertBytes(plan.copyForStorage(), requested.plan.copyForStorage())
        }
        suspend fun assertNativeAborted() {
            assertEquals(CredentialCreateRecoveryStatus.ABORTED, credentials.inspectPlannedCreate(SCOPE, details.credentialPlan).checked().status)
            assertEquals(StateActivationStatus.ABORTED, data.inspectPlannedActivation(SCOPE, details.dataPlan).checked().status)
            val consumed = SessionWorkCodec.decode(work.read().checked()!!.payload) as SessionWorkState.SetupAborted
            assertBytes(details.workOriginPlan.copyForStorage(), consumed.plan.copyForStorage())
        }
        suspend fun assertCompleted() {
            assertNativeAborted()
            assertEquals(OPERATION, (RetirementCodec.decode(control.read().checked()!!.payload) as RetirementState.Complete).operationId)
            assertNoEffects()
        }
        fun privateFiles() = sandbox.fileSnapshot().filterKeys { !it.startsWith("feedme-session-control/") }
        fun assertPrivateFiles(expected: Map<String, ByteArray>) {
            val actual = privateFiles(); assertEquals(expected.keys, actual.keys)
            expected.forEach { (name, bytes) -> assertArrayEquals(name, bytes, actual.getValue(name)) }
        }
        fun forbidden(): Nothing { effects++; throw AssertionError("Composite abort attempted a provider/secret/identity/native-work effect") }
        suspend fun assertNoEffects() {
            assertEquals(0, effects); assertNull(boundary.current()); assertNull(runtime?.currentAccess())
        }
        suspend fun closeStores() {
            try {
                runtime?.let { if (it.close() !is PortResult.Value) closeFailed = true }; runtime = null
                closers.asReversed().forEach { if (it() !is PortResult.Value) closeFailed = true }
                if (!closeFailed) closers.clear()
                assertFalse("Keep exact native evidence when any close fails", closeFailed)
            } catch (failure: Throwable) { closeFailed = true; throw failure }
        }
        suspend fun finish() {
            try { closeStores() } finally {
                if (closeFailed) sandbox.releasePreservingFixture() else sandbox.removeOwnedFixture()
            }
        }
    }

    companion object {
        private val SCOPE = StorageScope("native-composite-abort", ActorKind.ACCOUNT, "private-abort-owner")
        private val BINDING = RecordKey("session-activation", "binding-v1")
        private val DRAFT = RecordKey("draft", "private-extra")
        private val CONFIGURATION = "b7".repeat(32)
        private val OTHER_CONFIGURATION = "c8".repeat(32)
        private const val OPERATION = "00000000-0000-4000-8000-000000000991"
        private const val ORIGIN = "00000000-0000-4000-8000-000000000992"
        private const val DEVICE = "00000000-0000-4000-8000-000000000993"
        private const val ACCESS = "private-composite-abort-access"
        private const val REFRESH = "private-composite-abort-refresh"
        private fun assertBytes(a: PrivateBytes, b: PrivateBytes) = assertArrayEquals(a.copyForCodec(), b.copyForCodec())
        private fun failure(result: PortResult<*>, reason: FailureReason) {
            assertTrue(result is PortResult.Failure); assertEquals(reason, (result as PortResult.Failure).reason)
            assertNull(result.retryAfterSeconds)
        }
        private fun <T> PortResult<T>.checked(): T = when (this) {
            is PortResult.Value -> value
            is PortResult.Failure -> throw AssertionError("Native composite fixture failed: $reason")
        }
    }
}
