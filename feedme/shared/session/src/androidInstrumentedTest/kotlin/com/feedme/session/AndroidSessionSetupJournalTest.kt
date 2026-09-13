package com.feedme.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feedme.core.ports.*
import com.feedme.storage.AndroidSessionControlStore
import com.feedme.storage.AndroidSessionWorkStore
import com.feedme.storage.AndroidStateDatabase
import com.feedme.storage.EncryptedSessionControlStore
import com.feedme.storage.EncryptedSessionWorkStore
import com.feedme.storage.EncryptedStateDatabase
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Run separately in the isolated session test APK. Real public native stores and authenticated
 * credential/data/work plans, but no resource selection, provider identity, lease or OS work.
 * An encrypted record is bounded codec/persistence evidence, not the composite live coordinator.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSessionSetupJournalTest {
    @Test fun realNativePlansRoundTripThroughIndependentEncryptedControlWithoutSelectingResources() = journal {
        val plans = prepare()
        val original = plans.copyForStorage()
        val aliases = sandbox.aliases()
        val resourceFiles = resourceFiles()
        val stored = write(RetirementState.PendingSetup(plans, false))
        val clear = stored.payload.copyForCodec().decodeToString()
        assertTrue(clear.contains("\"state\":\"session-setup-pending\""))
        for (secret in listOf(ACCESS, REFRESH)) {
            assertFalse(clear.contains(secret))
            assertFalse(clear.contains(hex(secret.encodeToByteArray())))
            assertFalse(original.copyForCodec().decodeToString().contains(secret))
        }
        assertResourcesEqual(resourceFiles)
        assertUnselected()
        closeStores()
        openStores()
        val reopened = control.read().journalValue()!!
        assertEquals(stored.revision, reopened.revision)
        assertBytes(stored.payload, reopened.payload)
        val state = RetirementCodec.decode(reopened.payload) as RetirementState.PendingSetup
        assertFalse(state.abortRequested)
        assertBytes(original, state.plan.copyForStorage())
        val decoded = SessionSetupPlanCodec.decode(state.plan.copyForStorage())
        val before = SessionSetupPlanCodec.decode(original)
        assertEquals(OPERATION, decoded.operationId)
        assertEquals(SCOPE, decoded.scope)
        assertEquals(CONFIGURATION, decoded.configurationBinding)
        assertBytes(before.credentialPlan.copyForStorage(), decoded.credentialPlan.copyForStorage())
        assertArrayEquals(before.dataPlan.copyForStorage(), decoded.dataPlan.copyForStorage())
        assertBytes(before.workOriginPlan.copyForStorage(), decoded.workOriginPlan.copyForStorage())
        assertEquals(aliases, sandbox.aliases())
        assertResourcesEqual(resourceFiles)
        assertUnselected()
        assertNoEffects()
    }

    @Test fun explicitAbortFlagPersistsAsMetadataOnlyAndNeverSelectsOrCleansAnyPlannedResource() = journal {
        val plan = prepare()
        val original = plan.copyForStorage()
        val aliases = sandbox.aliases()
        val resources = resourceFiles()
        val pending = write(RetirementState.PendingSetup(plan, false))
        val requested = write(RetirementState.PendingSetup(plan, true))
        assertEquals(pending.revision + 1, requested.revision)
        closeStores()
        openStores()
        val state = RetirementCodec.decode(control.read().journalValue()!!.payload) as RetirementState.PendingSetup
        assertTrue(state.abortRequested)
        assertBytes(original, state.plan.copyForStorage())
        for (privateValue in listOf(SCOPE.environment, SCOPE.actorId, OPERATION, CONFIGURATION)) {
            assertFalse(state.toString().contains(privateValue))
            assertFalse(state.plan.toString().contains(privateValue))
        }
        assertEquals(aliases, sandbox.aliases())
        assertResourcesEqual(resources)
        assertUnselected()
        assertNoEffects()
    }

    @Test fun malformedCompositeControlIsRejectedWithoutLegacyFallbackWritesOrNativeEffects() = journal {
        val plan = prepare()
        val valid = RetirementCodec.encode(RetirementState.PendingSetup(plan, false)).copyForCodec().decodeToString()
        val planHex = hex(plan.copyForStorage().copyForCodec())
        val malformed = listOf(
            valid.replace("\"abortRequested\":false", "\"abortRequested\":\"false\""),
            valid.dropLast(1) + ",\"unexpected\":1}",
            valid.replace("session-setup-pending", "future-setup-state"),
            valid.replace(planHex, planHex.dropLast(2)),
            valid.replace("\"version\":1", "\"version\":1,\"version\":1"),
        )
        for (raw in malformed) {
            assertNotEquals(valid, raw)
            writeRaw(PrivateBytes(raw.encodeToByteArray()))
            assertTrue(runCatching { RetirementCodec.decode(control.read().journalValue()!!.payload) }.isFailure)
            val files = sandbox.fileSnapshot()
            val aliases = sandbox.aliases()
            countedControl.writes = 0
            assertFailure(credentialRecovery().inspectPending(), FailureReason.STORAGE_FAILURE)
            assertFailure(credentialRecovery().recoverAbort(), FailureReason.STORAGE_FAILURE)
            assertFailure(retirement().recover(), FailureReason.INVALID_DATA)
            assertEquals(0, countedControl.writes)
            sandbox.assertFilesEqual(files)
            assertEquals(aliases, sandbox.aliases())
            assertUnselected()
            assertNoEffects()
        }
    }

    @Test fun legacyCredentialAbortCannotConsumeEitherUnconfirmedOrConfirmedCompositeJournal() = journal {
        val plan = prepare()
        for (requested in listOf(false, true)) {
            write(RetirementState.PendingSetup(plan, requested))
            val files = sandbox.fileSnapshot()
            val aliases = sandbox.aliases()
            countedControl.writes = 0
            val legacy = credentialRecovery()
            assertFailure(legacy.inspectPending(), FailureReason.CONFLICT)
            assertFailure(legacy.recoverAbort(), FailureReason.CONFLICT)
            assertEquals(0, countedControl.writes)
            sandbox.assertFilesEqual(files)
            assertEquals(aliases, sandbox.aliases())
            assertUnselected()
            assertNoEffects()
        }
    }

    @Test fun ordinaryRetirementCannotOverwriteCompositeSetupOrTreatItAsEmptyDiscard() = journal {
        val plan = prepare()
        for (requested in listOf(false, true)) {
            write(RetirementState.PendingSetup(plan, requested))
            val files = sandbox.fileSnapshot()
            val aliases = sandbox.aliases()
            countedControl.writes = 0
            val legacy = retirement()
            assertFalse(legacy.restorationAllowed().journalValue())
            assertFailure(legacy.recover(), FailureReason.CONFLICT)
            assertFalse(legacy.restorationAllowed().journalValue())
            assertEquals(0, countedControl.writes)
            sandbox.assertFilesEqual(files)
            assertEquals(aliases, sandbox.aliases())
            assertUnselected()
            assertNoEffects()
        }
    }

    @Test fun startupRuntimeReportsPartialCompositeStateWithoutProviderReadbackRepairOrAccess() = journal {
        val plan = prepare()
        write(RetirementState.PendingSetup(plan, true))
        openRuntime()
        val files = sandbox.fileSnapshot()
        val aliases = sandbox.aliases()
        countedControl.writes = 0
        val runtime = checkNotNull(runtime)
        val diagnostic = runtime.inspectRecovery().journalValue()
        assertEquals(SessionRecoveryFinding.PARTIAL_STATE, diagnostic.finding)
        assertEquals(SessionRecoveryNextStep.PRESERVE_FOR_REPAIR, diagnostic.nextStep)
        assertFailure(runtime.recover(), FailureReason.CONFLICT)
        assertEquals(PrivateSessionPhase.RECOVERY_REQUIRED, runtime.phase())
        assertNull(runtime.currentAccess())
        assertFailure(runtime.prepareInterruptedSetupDiscard(), FailureReason.CONFLICT)
        assertEquals(0, countedControl.writes)
        sandbox.assertFilesEqual(files)
        assertEquals(aliases, sandbox.aliases())
        assertUnselected()
        assertNoEffects()
    }

    @Test fun compositeBarrierIntroducedAfterSignedOutBlocksProviderAcquisitionAndLiveCreate() = journal {
        val plan = prepare()
        openRuntime()
        val runtime = checkNotNull(runtime)
        assertEquals(PrivateSessionPhase.SIGNED_OUT, runtime.recover().journalValue())
        write(RetirementState.PendingSetup(plan, false))
        val files = sandbox.fileSnapshot()
        val aliases = sandbox.aliases()
        countedControl.writes = 0
        assertFailure(runtime.create(), FailureReason.CONFLICT)
        assertNull(runtime.currentAccess())
        assertEquals(SessionRecoveryFinding.PARTIAL_STATE, runtime.inspectRecovery().journalValue().finding)
        assertEquals(0, countedControl.writes)
        sandbox.assertFilesEqual(files)
        assertEquals(aliases, sandbox.aliases())
        assertUnselected()
        assertNoEffects()
    }

    private fun journal(block: suspend Fixture.() -> Unit) = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val sandbox = AndroidRetirementIntegrationSandbox.create(base)
            val fixture = Fixture(sandbox)
            try { fixture.openStores(); fixture.block() }
            finally { withContext(NonCancellable) { fixture.finish() } }
        }
    }

    private class Fixture(val sandbox: AndroidRetirementIntegrationSandbox) {
        lateinit var data: EncryptedStateDatabase
        lateinit var control: EncryptedSessionControlStore
        lateinit var work: EncryptedSessionWorkStore
        lateinit var credentials: AndroidCredentialStore
        lateinit var countedControl: CountingControl
        var boundary = SessionBoundary()
        var runtime: PrivateSessionRuntime? = null
        private var registry: SessionWorkRegistry? = null
        private val closers = mutableListOf<suspend () -> PortResult<Unit>>()
        private var closeFailed = false
        private var effects = 0
        private var providerCalls = 0
        private var credentialReads = 0

        suspend fun openStores() {
            check(closers.isEmpty())
            boundary = SessionBoundary()
            data = AndroidStateDatabase.open(sandbox.context).journalValue().also { value -> closers += { value.close() } }
            control = AndroidSessionControlStore.open(sandbox.context).journalValue().also { value -> closers += { value.close() } }
            work = AndroidSessionWorkStore.open(sandbox.context).journalValue().also { value -> closers += { value.close() } }
            credentials = AndroidCredentialStore.open(sandbox.context).journalValue().also { value -> closers += { value.close() } }
            countedControl = CountingControl(control)
        }

        suspend fun prepare(): SessionSetupPlan {
            val credentialPlan = credentials.planCreate(credentials.state().journalValue().revision,
                StoredCredentials.Account(SCOPE, SecretText(ACCESS), SecretText(REFRESH), Long.MAX_VALUE,
                    SecretText("00000000-0000-4000-8000-000000000391"))).journalValue()
            val dataPlan = data.planActivation(SCOPE).journalValue()
            val planner = SessionWorkRegistry.open(work, boundary, Dispatchers.Main.immediate,
                NativeWorkCancellationPort { forbiddenEffect() }, NativeWorkIdSource { UUID.randomUUID().toString() },
                NativeWorkAdmissionPolicy { forbiddenEffect() }, NativeWorkExecutionPolicy { _, _, _, _ -> forbiddenEffect() },
            ).journalValue().also { registry = it }
            val workPlan = planner.planOrigin(SCOPE, work.read().journalValue()!!.revision).journalValue()
            val plan = SessionSetupPlan.create(SessionSetupPlanRecord(OPERATION, SCOPE, CONFIGURATION,
                credentialPlan, dataPlan, workPlan))
            planner.close().journalValue()
            registry = null
            assertUnselected()
            return plan
        }

        suspend fun write(state: RetirementState) = writeRaw(RetirementCodec.encode(state))
        suspend fun writeRaw(payload: PrivateBytes): SessionControlRecord = countedControl.compareAndSet(
            control.read().journalValue()!!.revision, payload,
        ).journalValue()

        fun credentialRecovery() = CredentialCreateCoordinator(countedControl, boundary, Dispatchers.Main.immediate,
            CredentialCreateRecoveryFactory { forbiddenEffect() })

        fun retirement() = LocalRetirementCoordinator(countedControl, data, boundary, Dispatchers.Main.immediate,
            CredentialRetirementPort { _, _ -> forbiddenEffect() }, SessionWorkRetirementPort { _, _ -> forbiddenEffect() })

        suspend fun openRuntime() {
            check(registry == null && runtime == null)
            val countedCredentials = object : PlannedCredentialCreateStore by credentials {
                override suspend fun read(scope: StorageScope): PortResult<CredentialSnapshot?> {
                    credentialReads++
                    throw AssertionError("Composite startup journal must not read credentials")
                }
                override suspend fun planCreate(expectedSlotRevision: Long, credentials: StoredCredentials): PortResult<CredentialCreatePlan> =
                    forbiddenEffect()
                override suspend fun commitPlannedCreate(plan: CredentialCreatePlan, credentials: StoredCredentials): PortResult<CredentialSnapshot> =
                    forbiddenEffect()
                override suspend fun retire(scope: StorageScope, credentialIncarnation: String): PortResult<Unit> = forbiddenEffect()
            }
            val verifier = object : NativeSessionVerifier {
                override suspend fun acquire(): PortResult<StoredCredentials> {
                    providerCalls++
                    throw AssertionError("Pending composite journal must block provider acquisition")
                }
                override suspend fun restore(snapshot: CredentialSnapshot): PortResult<PrivateSessionAccessMode> {
                    providerCalls++
                    throw AssertionError("Pending composite journal cannot verify or restore identity")
                }
            }
            runtime = PrivateSessionRuntime.open(countedControl, work, data, countedCredentials, boundary,
                Dispatchers.Main.immediate, CONFIGURATION, verifier,
                NativeWorkCancellationPort { forbiddenEffect() }, NativeWorkIdSource { forbiddenEffect() },
                NativeWorkExecutionPolicy { _, _, _, _ -> forbiddenEffect() },
            ).journalValue()
        }

        private fun forbiddenEffect(): Nothing {
            effects++
            throw AssertionError("Journal-only fixture cannot authorize resource effects or cleanup")
        }

        suspend fun assertUnselected() {
            val slot = credentials.state().journalValue()
            assertEquals(1L, slot.revision)
            assertNull(slot.owner); assertNull(slot.incarnation)
            val owner = data.inspectOwnerState(SCOPE).journalValue()
            assertNull(owner.target); assertFalse(owner.hasRecords)
            assertTrue(sandbox.dataOwnerAliases().isEmpty())
            assertTrue(sandbox.credentialAliases().isEmpty())
            val workRecord = work.read().journalValue()!!
            assertEquals(1L, workRecord.revision)
            assertEquals(SessionWorkState.Idle, SessionWorkCodec.decode(workRecord.payload))
            assertNull(boundary.current())
        }

        fun assertNoEffects() {
            assertEquals(0, effects)
            assertEquals(0, providerCalls)
            assertEquals(0, credentialReads)
            assertNull(boundary.current())
        }

        fun resourceFiles() = sandbox.fileSnapshot().filterKeys { !it.startsWith("feedme-session-control/") }
        fun assertResourcesEqual(expected: Map<String, ByteArray>) {
            val actual = resourceFiles()
            assertEquals(expected.keys, actual.keys)
            expected.forEach { (name, bytes) -> assertArrayEquals("Unselected resource bytes changed", bytes, actual.getValue(name)) }
        }

        suspend fun closeStores() {
            var failed = false
            val currentRuntime = runtime; runtime = null
            if (currentRuntime != null) try { if (currentRuntime.close() is PortResult.Failure) failed = true } catch (_: Exception) { failed = true }
            val currentRegistry = registry; registry = null
            if (currentRegistry != null) try { if (currentRegistry.close() is PortResult.Failure) failed = true } catch (_: Exception) { failed = true }
            val pending = closers.asReversed().toList()
            closers.clear()
            for (close in pending) try { if (close() is PortResult.Failure) failed = true } catch (_: Exception) { failed = true }
            if (failed) closeFailed = true
            check(!failed) { "Native journal fixture close was not acknowledged" }
        }

        suspend fun finish() {
            try { closeStores() } catch (failure: Throwable) {
                sandbox.releasePreservingFixture()
                throw failure
            }
            if (closeFailed) {
                sandbox.releasePreservingFixture()
                throw AssertionError("Retaining exact fixture after failed native close")
            }
            withContext(Dispatchers.IO) { sandbox.removeOwnedFixture() }
        }
    }

    private class CountingControl(private val native: SessionControlStore) : SessionControlStore {
        var writes = 0
        override suspend fun read() = native.read()
        override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> {
            writes++
            return native.compareAndSet(expectedRevision, payload)
        }
    }

    private companion object {
        val SCOPE = StorageScope("native-composite-journal", ActorKind.ACCOUNT, "test-only-composite-owner")
        const val OPERATION = "00000000-0000-4000-8000-000000000392"
        val CONFIGURATION = "c".repeat(64)
        const val ACCESS = "test-only-composite-access-canary"
        const val REFRESH = "test-only-composite-refresh-canary"
        fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        fun assertBytes(expected: PrivateBytes, actual: PrivateBytes) = assertArrayEquals(expected.copyForCodec(), actual.copyForCodec())
        fun assertFailure(value: PortResult<*>, reason: FailureReason) = assertEquals(PortResult.Failure(reason), value)
        fun <T> PortResult<T>.journalValue(): T = when (this) {
            is PortResult.Value -> value
            is PortResult.Failure -> throw AssertionError("Expected native journal value, got $reason")
        }
    }
}
