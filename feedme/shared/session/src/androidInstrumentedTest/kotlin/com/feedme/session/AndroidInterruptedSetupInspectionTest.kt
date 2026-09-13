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
 * Separate isolated test-APK invocation. Public native stores and real authenticated plans;
 * deliberate fixture selections create the evidence, never the read-only diagnostic. No provider,
 * OS work, process-kill, public orphan-recovery or physical durability claim is made here.
 */
@RunWith(AndroidJUnit4::class)
class AndroidInterruptedSetupInspectionTest {
    @Test fun allNativePreparedSelectedBoundAndSealedStagesAreObservedWithoutAnyEffects() {
        for (stage in Stage.entries) inspection {
            prepare(stage); openRuntime()
            val files = sandbox.fileSnapshot(); val aliases = sandbox.aliases()
            val original = control.read().checked()!!
            repeat(3) { assertStage(stage, report(InterruptedSetupFinding.UNCONFIRMED_SETUP)) }
            assertEquals(original.revision, control.read().checked()!!.revision)
            assertBytes(original.payload, control.read().checked()!!.payload)
            sandbox.assertFilesEqual(files); assertEquals(aliases, sandbox.aliases()); assertNoEffects()
        }
    }

    @Test fun realPlansAndSelectionEvidenceSurviveNativeCloseReopenWithoutAcquiringIdentity() {
        for (stage in listOf(Stage.PREPARED, Stage.DATA, Stage.WORK, Stage.BOUND, Stage.SEALED)) inspection {
            prepare(stage)
            val original = control.read().checked()!!
            closeStores(); openStores(); openRuntime()
            val files = sandbox.fileSnapshot(); val aliases = sandbox.aliases()
            assertStage(stage, report(InterruptedSetupFinding.UNCONFIRMED_SETUP))
            reject(FailureReason.CONFLICT, runtime!!.retryCreate())
            assertEquals(PrivateSessionPhase.STARTUP, runtime!!.phase())
            assertEquals(original.revision, control.read().checked()!!.revision)
            sandbox.assertFilesEqual(files); assertEquals(aliases, sandbox.aliases()); assertNoEffects()
        }
    }

    @Test fun selectedMissingOrDamagedCredentialsRemainOnlyMetadataEvidenceWithoutSecretReads() {
        for (damage in CredentialDiagnosticDamage.entries) inspection {
            prepare(Stage.SEALED)
            closeStores(); sandbox.damageSelectedCredentials(damage); openStores(); openRuntime()
            val files = sandbox.fileSnapshot(); val aliases = sandbox.aliases()
            assertStage(Stage.SEALED, report(InterruptedSetupFinding.UNCONFIRMED_SETUP))
            sandbox.assertFilesEqual(files); assertEquals(aliases, sandbox.aliases()); assertNoEffects()
        }
    }

    @Test fun configurationMismatchAndMissingInspectionCapabilityNeverFallBackToCredentialReads() {
        for (configurationMismatch in listOf(false, true)) inspection {
            prepare(Stage.PREPARED)
            openRuntime(if (configurationMismatch) OTHER_CONFIGURATION else CONFIGURATION, capability = configurationMismatch)
            val files = sandbox.fileSnapshot(); val aliases = sandbox.aliases()
            val result = report(if (configurationMismatch) InterruptedSetupFinding.CONFIGURATION_CHANGED else InterruptedSetupFinding.EVIDENCE_UNAVAILABLE)
            if (!configurationMismatch) {
                assertEquals(InterruptedSetupComponent.CREDENTIAL_METADATA, result.component)
                assertEquals(FailureReason.NOT_CONFIGURED, result.failureReason)
            }
            assertEquals(0, credentialInspections)
            sandbox.assertFilesEqual(files); assertEquals(aliases, sandbox.aliases()); assertNoEffects()
        }
    }

    @Test fun malformedControlAndForeignAuthenticatedCredentialPlanArePreservedWithoutProbeFallback() {
        for (badControl in listOf(false, true)) inspection {
            prepare(Stage.PREPARED)
            val payload = if (badControl) PrivateBytes("{\"version\":1,\"state\":\"session-setup-pending\"}".encodeToByteArray()) else {
                val foreign = StoredCredentials.Account(OTHER_SCOPE, SecretText(ACCESS), SecretText(REFRESH), Long.MAX_VALUE, SecretText(DEVICE))
                val foreignPlan = credentials.planCreate(credentials.state().checked().revision, foreign).checked()
                RetirementCodec.encode(RetirementState.PendingSetup(SessionSetupPlan.create(details.copy(credentialPlan = foreignPlan)), false))
            }
            control.compareAndSet(control.read().checked()!!.revision, payload).checked()
            openRuntime()
            val files = sandbox.fileSnapshot(); val aliases = sandbox.aliases()
            val result = report(if (badControl) InterruptedSetupFinding.EVIDENCE_UNAVAILABLE else InterruptedSetupFinding.RESOURCE_MISMATCH)
            assertEquals(if (badControl) InterruptedSetupComponent.CONTROL else InterruptedSetupComponent.CREDENTIAL_METADATA, result.component)
            sandbox.assertFilesEqual(files); assertEquals(aliases, sandbox.aliases()); assertNoEffects()
        }
    }

    @Test fun privateRowsTombstonesWrongBindingsAndUsedWorkCannotBeReportedAsCoherentPendingSetup() {
        for (damage in listOf("row", "tombstone", "binding", "used-work")) inspection {
            prepare(Stage.SEALED)
            when (damage) {
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
                        val changed = SessionActivationRecord(parsed.scope, parsed.credentialIncarnation, parsed.originBinding,
                            parsed.dataTarget, OTHER_CONFIGURATION, parsed.setupOperationId)
                        store.commit(SCOPE, listOf(StoreMutation.Put(BINDING, previous.revision, 2, SessionActivationCodec.encode(changed)))).checked()
                    } else {
                        val revision = store.commit(SCOPE, listOf(StoreMutation.Put(DRAFT, null, 1, PrivateBytes("private-draft".encodeToByteArray())))).checked()[DRAFT]!!
                        if (damage == "tombstone") store.commit(SCOPE, listOf(StoreMutation.Delete(DRAFT, revision))).checked()
                    }
                }
            }
            openRuntime()
            val files = sandbox.fileSnapshot(); val aliases = sandbox.aliases()
            report(InterruptedSetupFinding.RESOURCE_MISMATCH)
            sandbox.assertFilesEqual(files); assertEquals(aliases, sandbox.aliases()); assertNoEffects()
        }
    }

    @Test fun exactControlRevisionBracketRejectsNativeChangesAfterEarlierMetadataObservation() = inspection {
        prepare(Stage.SEALED); openRuntime()
        val before = control.read().checked()!!
        var once = true
        afterCredentialInspection = { if (once) {
            once = false; control.compareAndSet(before.revision, before.payload).checked()
        } }
        report(InterruptedSetupFinding.EVIDENCE_CHANGED)
        assertEquals(before.revision + 1, control.read().checked()!!.revision)
        assertBytes(before.payload, control.read().checked()!!.payload)
        afterCredentialInspection = { }
        val files = sandbox.fileSnapshot(); val aliases = sandbox.aliases()
        assertStage(Stage.SEALED, report(InterruptedSetupFinding.UNCONFIRMED_SETUP))
        sandbox.assertFilesEqual(files); assertEquals(aliases, sandbox.aliases()); assertNoEffects()
    }

    @Test fun closeCancellationAndForeignLeaseFenceSuspendedNativeInspectionWithoutCleaningAnything() {
        for (action in listOf("close", "cancel", "lease")) inspection {
            prepare(Stage.SEALED); openRuntime()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var once = true
            afterCredentialInspection = { if (once) { once = false; entered.complete(Unit); release.await() } }
            val files = sandbox.fileSnapshot(); val aliases = sandbox.aliases()
            coroutineScope {
                val pending = async { runtime!!.inspectInterruptedSetup() }
                entered.await()
                when (action) {
                    "close" -> {
                        val closing = async(start = CoroutineStart.UNDISPATCHED) { runtime!!.close() }
                        assertEquals(PrivateSessionPhase.CLOSED, runtime!!.phase())
                        release.complete(Unit); reject(FailureReason.STORAGE_FAILURE, pending.await()); closing.await().checked()
                    }
                    "cancel" -> {
                        pending.cancel(); release.complete(Unit)
                        try { pending.await(); fail("Cancelled inspection returned a report") } catch (_: CancellationException) { }
                        assertEquals(PrivateSessionPhase.STARTUP, runtime!!.phase())
                        assertStage(Stage.SEALED, report(InterruptedSetupFinding.UNCONFIRMED_SETUP))
                    }
                    else -> {
                        val lease = boundary.activate(SCOPE)
                        release.complete(Unit); reject(FailureReason.CONFLICT, pending.await())
                        assertSame(lease, boundary.current()); boundary.clear()
                    }
                }
            }
            sandbox.assertFilesEqual(files); assertEquals(aliases, sandbox.aliases()); assertNoEffects()
        }
    }

    @Test fun abortRequestedReportIsRedactedAndDoesNotBecomeAConfirmationOrLegacyCleanupCapability() = inspection {
        prepare(Stage.SEALED)
        val prior = control.read().checked()!!
        control.compareAndSet(prior.revision, RetirementCodec.encode(RetirementState.PendingSetup(plan, true))).checked()
        openRuntime()
        val files = sandbox.fileSnapshot(); val aliases = sandbox.aliases()
        val result = report(InterruptedSetupFinding.ABORT_REQUESTED)
        assertStage(Stage.SEALED, result)
        assertEquals(SessionRecoveryNextStep.PRESERVE_FOR_REPAIR, result.nextStep)
        for (privateValue in listOf(SCOPE.environment, SCOPE.actorId, OPERATION, CONFIGURATION, ACCESS, REFRESH,
            CredentialCreatePlanCodec.decode(details.credentialPlan.copyForStorage()).incarnation,
            SessionWorkOriginPlanCodec.decode(details.workOriginPlan.copyForStorage()).origin)) assertFalse(result.toString().contains(privateValue))
        val legacy = CredentialCreateCoordinator(control, boundary, Dispatchers.Main.immediate,
            CredentialCreateRecoveryFactory { forbidden() })
        reject(FailureReason.CONFLICT, legacy.inspectPending()); reject(FailureReason.CONFLICT, legacy.recoverAbort())
        reject(FailureReason.CONFLICT, runtime!!.prepareInterruptedSetupDiscard())
        sandbox.assertFilesEqual(files); assertEquals(aliases, sandbox.aliases()); assertNoEffects()
    }

    private fun inspection(block: suspend Fixture.() -> Unit) = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val sandbox = AndroidRetirementIntegrationSandbox.create(InstrumentationRegistry.getInstrumentation().targetContext)
            val f = Fixture(sandbox)
            try { f.openStores(); f.block() } finally { withContext(NonCancellable) { f.finish() } }
        }
    }

    private enum class Stage { PREPARED, CREDENTIAL, DATA, WORK, BOUND, SEALED }

    private class Fixture(val sandbox: AndroidRetirementIntegrationSandbox) {
        lateinit var data: EncryptedStateDatabase
        lateinit var control: EncryptedSessionControlStore
        lateinit var work: EncryptedSessionWorkStore
        lateinit var credentials: AndroidCredentialStore
        lateinit var plan: SessionSetupPlan
        lateinit var details: SessionSetupPlanRecord
        var runtime: PrivateSessionRuntime? = null
        var boundary = SessionBoundary()
        var credentialInspections = 0
        var afterCredentialInspection: suspend () -> Unit = { }
        private var effects = 0
        private var closeFailed = false
        private val closers = mutableListOf<suspend () -> PortResult<Unit>>()

        suspend fun openStores() {
            check(closers.isEmpty())
            boundary = SessionBoundary()
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
                if (stage >= Stage.BOUND) {
                    val target = data.inspectPlannedBinding(SCOPE, dataPlan).checked().target!!
                    val incarnation = CredentialCreatePlanCodec.decode(credential.copyForStorage()).incarnation
                    data.bindPlannedActivation(SCOPE, dataPlan, 2,
                        SessionActivationCodec.encode(SessionActivationRecord(SCOPE, incarnation, ORIGIN, target, CONFIGURATION, OPERATION))).checked()
                }
                if (stage >= Stage.SEALED) planner.sealOrigin(workPlan).checked()
            } finally { planner.close().checked() }
            assertNull(boundary.current())
        }

        suspend fun openRuntime(configuration: String = CONFIGURATION, capability: Boolean = true) {
            val counted = object : PlannedCredentialCreateStore by credentials, CredentialCreatePlanInspection {
                override suspend fun inspectPlannedCreate(scope: StorageScope, plan: CredentialCreatePlan): PortResult<CredentialCreatePlanObservation> {
                    credentialInspections++
                    val result = credentials.inspectPlannedCreate(scope, plan)
                    afterCredentialInspection()
                    return result
                }
                override suspend fun read(scope: StorageScope): PortResult<CredentialSnapshot?> = forbidden()
                override suspend fun create(expectedSlotRevision: Long, credentials: StoredCredentials): PortResult<CredentialSnapshot> = forbidden()
                override suspend fun planCreate(expectedSlotRevision: Long, credentials: StoredCredentials): PortResult<CredentialCreatePlan> = forbidden()
                override suspend fun commitPlannedCreate(plan: CredentialCreatePlan, credentials: StoredCredentials): PortResult<CredentialSnapshot> = forbidden()
                override suspend fun retire(scope: StorageScope, credentialIncarnation: String): PortResult<Unit> = forbidden()
                override suspend fun replace(expected: CredentialSnapshot, credentials: StoredCredentials): PortResult<CredentialSnapshot> = forbidden()
                override suspend fun attachDeviceSession(expected: CredentialSnapshot, deviceSessionId: SecretText): PortResult<CredentialSnapshot> = forbidden()
            }
            val credentialOwner: IncarnationCredentialStore = if (capability) counted else object : PlannedCredentialCreateStore by counted { }
            val readOnlyControl = object : SessionControlStore {
                override suspend fun read() = control.read()
                override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> = forbidden()
            }
            val readOnlyWork = object : SessionControlStore, WorkOriginPlanAuthentication by work {
                override suspend fun read() = work.read()
                override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> = forbidden()
                override suspend fun signOriginPlan(expected: SessionControlRecord, proposal: PrivateBytes): PortResult<PrivateBytes> = forbidden()
            }
            runtime = PrivateSessionRuntime.open(readOnlyControl, readOnlyWork, data, credentialOwner, boundary,
                Dispatchers.Main.immediate, configuration, object : NativeSessionVerifier {
                    override suspend fun acquire(): PortResult<StoredCredentials> = forbidden()
                    override suspend fun restore(snapshot: CredentialSnapshot): PortResult<PrivateSessionAccessMode> = forbidden()
                }, NativeWorkCancellationPort { forbidden() }, NativeWorkIdSource { forbidden() },
                NativeWorkExecutionPolicy { _, _, _, _ -> forbidden() }).checked()
        }

        suspend fun report(finding: InterruptedSetupFinding): InterruptedSetupReport = runtime!!.inspectInterruptedSetup().checked().also {
            assertEquals(finding, it.finding)
            if (finding !in setOf(InterruptedSetupFinding.UNCONFIRMED_SETUP, InterruptedSetupFinding.ABORT_REQUESTED)) {
                assertNull(it.credentialStage); assertNull(it.dataStage); assertNull(it.workStage)
            }
        }
        fun forbidden(): Nothing { effects++; throw AssertionError("Read-only interrupted setup inspection attempted an effect or secret read") }
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
        private val SCOPE = StorageScope("native-interrupted-inspection", ActorKind.ACCOUNT, "private-inspection-owner")
        private val OTHER_SCOPE = StorageScope("native-interrupted-inspection", ActorKind.ACCOUNT, "different-private-owner")
        private val BINDING = RecordKey("session-activation", "binding-v1")
        private val DRAFT = RecordKey("draft", "private-extra")
        private const val CONFIGURATION = "d7d7d7d7d7d7d7d7d7d7d7d7d7d7d7d7d7d7d7d7d7d7d7d7d7d7d7d7d7d7d7d7"
        private val OTHER_CONFIGURATION = "e".repeat(64)
        private const val OPERATION = "00000000-0000-4000-8000-000000000891"
        private const val ORIGIN = "00000000-0000-4000-8000-000000000892"
        private const val DEVICE = "00000000-0000-4000-8000-000000000893"
        private const val ACCESS = "private-interrupted-native-access"
        private const val REFRESH = "private-interrupted-native-refresh"
        private fun assertStage(stage: Stage, report: InterruptedSetupReport) {
            assertEquals(if (stage == Stage.PREPARED) CredentialCreateRecoveryStatus.PREPARED else CredentialCreateRecoveryStatus.SELECTED, report.credentialStage)
            assertEquals(when (stage) {
                Stage.PREPARED, Stage.CREDENTIAL -> InterruptedSetupDataStage.PREPARED
                Stage.DATA, Stage.WORK -> InterruptedSetupDataStage.SELECTED_EMPTY
                else -> InterruptedSetupDataStage.BOUND
            }, report.dataStage)
            assertEquals(when (stage) {
                Stage.PREPARED, Stage.CREDENTIAL, Stage.DATA -> SessionWorkOriginPlanStatus.PREPARED
                Stage.WORK, Stage.BOUND -> SessionWorkOriginPlanStatus.SELECTED
                Stage.SEALED -> SessionWorkOriginPlanStatus.SEALED
            }, report.workStage)
        }
        private fun assertBytes(a: PrivateBytes, b: PrivateBytes) = assertArrayEquals(a.copyForCodec(), b.copyForCodec())
        private fun reject(reason: FailureReason, result: PortResult<*>) {
            assertTrue(result is PortResult.Failure); assertEquals(reason, (result as PortResult.Failure).reason)
        }
        private fun <T> PortResult<T>.checked(): T = when (this) {
            is PortResult.Value -> value
            is PortResult.Failure -> throw AssertionError("Native diagnostic fixture failed: $reason")
        }
    }
}
