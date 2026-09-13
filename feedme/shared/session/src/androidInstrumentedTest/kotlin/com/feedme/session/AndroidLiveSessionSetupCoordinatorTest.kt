package com.feedme.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feedme.core.ports.*
import com.feedme.storage.*
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Separately invoked, isolated native component tests. Real encrypted control, credential,
 * private-data and work stores; wrapper failures model lost application acknowledgements only.
 * No provider, session lease, application access, OS installation, hard kill or power-loss proof.
 */
@RunWith(AndroidJUnit4::class)
class AndroidLiveSessionSetupCoordinatorTest {
    @Test fun threeRealPlansAreJournalledBeforeCredentialDataAndWorkSelectionWithoutGrantingAccess() = live {
        coordinator.begin(verified()).liveValue()
        val stored = pending()
        val decoded = SessionSetupPlanCodec.decode(stored.plan.copyForStorage())
        assertEquals(SCOPE, decoded.scope)
        assertEquals(CONFIGURATION, decoded.configurationBinding)
        assertFalse(stored.abortRequested)
        assertEquals(listOf("credential", "data", "work"), resources.planned)
        assertEquals(listOf("credential", "data", "work"), resources.selected)
        assertEquals(1, operationIds)
        assertEquals(1, originIds)
        assertEquals(1, countedControl.writes.size)
        assertEquals(2L, control.read().liveValue()!!.revision)
        assertSelected()
        assertNoAccessOrNativeWork()
        val clear = control.read().liveValue()!!.payload.copyForCodec().decodeToString()
        for (secret in listOf(ACCESS, REFRESH)) {
            assertFalse(clear.contains(secret))
            assertFalse(clear.contains(hex(secret.encodeToByteArray())))
        }
    }

    @Test fun failedJournalWriteLeavesAllThreeNativePlansUnselectedAndRetriesWithoutReplanning() = live {
        val before = resourceFiles()
        val aliases = sandbox.aliases()
        countedControl.failure = ControlFailure.BEFORE_WRITE
        assertFailure(coordinator.begin(verified()), FailureReason.STORAGE_FAILURE)
        assertEquals(RetirementState.Idle, RetirementCodec.decode(control.read().liveValue()!!.payload))
        assertEquals(emptyList<String>(), resources.selected)
        assertUnselected()
        assertResourcesEqual(before)
        assertEquals(aliases, sandbox.aliases())
        val intended = countedControl.writes.single()
        coordinator.retry().liveValue()
        assertBytes(intended, countedControl.writes.last())
        assertEquals(2, countedControl.writes.size)
        assertPlannedOnlyOnce()
        assertSelected()
        assertNoAccessOrNativeWork()
    }

    @Test fun successfulControlCommitWithLostReceiptCannotSelectUntilExactFreshAcknowledgement() = live {
        countedControl.failure = ControlFailure.AFTER_WRITE
        assertFailure(coordinator.begin(verified()), FailureReason.OUTCOME_UNKNOWN)
        val original = control.read().liveValue()!!
        val plan = pending().plan.copyForStorage()
        assertEquals(2L, original.revision)
        assertEquals(emptyList<String>(), resources.selected)
        assertUnselected()
        coordinator.retry().liveValue()
        val current = control.read().liveValue()!!
        assertEquals(original.revision + 1, current.revision)
        assertBytes(original.payload, current.payload)
        assertBytes(plan, pending().plan.copyForStorage())
        assertPlannedOnlyOnce()
        assertSelected()
        assertNoAccessOrNativeWork()
    }

    @Test fun failedExactReadbackAfterControlCommitCannotAuthorizeSelectionOrReplanning() = live {
        countedControl.failure = ControlFailure.READBACK
        assertFailure(coordinator.begin(verified()), FailureReason.STORAGE_FAILURE)
        val original = control.read().liveValue()!!
        assertEquals(emptyList<String>(), resources.selected)
        assertUnselected()
        coordinator.retry().liveValue()
        assertEquals(original.revision + 1, control.read().liveValue()!!.revision)
        assertBytes(original.payload, control.read().liveValue()!!.payload)
        assertPlannedOnlyOnce()
        assertSelected()
        assertNoAccessOrNativeWork()
    }

    @Test fun lostCredentialSelectionReceiptRetainsExactPlanAndStopsLaterNativeSelections() = live {
        resources.failAfter = "credential"
        assertFailure(coordinator.begin(verified()), FailureReason.OUTCOME_UNKNOWN)
        val original = pending().plan.copyForStorage()
        assertEquals(listOf("credential"), resources.selected)
        assertEquals(SCOPE, credentials.state().liveValue().owner)
        assertNull(data.inspectOwnerState(SCOPE).liveValue().target)
        assertEquals(SessionWorkState.Idle, SessionWorkCodec.decode(work.read().liveValue()!!.payload))
        val credentialAliases = sandbox.credentialAliases()
        coordinator.retry().liveValue()
        assertBytes(original, pending().plan.copyForStorage())
        assertEquals(listOf("credential", "credential", "data", "work"), resources.selected)
        assertEquals(credentialAliases, sandbox.credentialAliases())
        assertPlannedOnlyOnce()
        assertSelected()
        assertNoAccessOrNativeWork()
    }

    @Test fun lostDataSelectionReceiptRetainsExactEmptyOwnerAndDoesNotSelectWorkEarly() = live {
        resources.failAfter = "data"
        assertFailure(coordinator.begin(verified()), FailureReason.OUTCOME_UNKNOWN)
        val original = pending().plan.copyForStorage()
        val target = data.inspectOwnerState(SCOPE).liveValue().target!!.copyForStorage()
        val aliases = sandbox.aliases()
        assertEquals(listOf("credential", "data"), resources.selected)
        assertEquals(SessionWorkState.Idle, SessionWorkCodec.decode(work.read().liveValue()!!.payload))
        coordinator.retry().liveValue()
        assertArrayEquals(target, data.inspectOwnerState(SCOPE).liveValue().target!!.copyForStorage())
        assertBytes(original, pending().plan.copyForStorage())
        assertEquals(aliases, sandbox.aliases())
        assertEquals(listOf("credential", "data", "credential", "data", "work"), resources.selected)
        assertPlannedOnlyOnce()
        assertSelected()
        assertNoAccessOrNativeWork()
    }

    @Test fun lostWorkSelectionReceiptRequiresExactSelectedReplayAndKeepsCompositeBarrier() = live {
        resources.failAfter = "work"
        assertFailure(coordinator.begin(verified()), FailureReason.OUTCOME_UNKNOWN)
        val originalPlan = pending().plan.copyForStorage()
        val originalWork = work.read().liveValue()!!
        val aliases = sandbox.aliases()
        assertEquals(listOf("credential", "data", "work"), resources.selected)
        coordinator.retry().liveValue()
        val currentWork = work.read().liveValue()!!
        assertEquals(originalWork.revision + 1, currentWork.revision)
        assertBytes(originalWork.payload, currentWork.payload)
        assertBytes(originalPlan, pending().plan.copyForStorage())
        assertEquals(aliases, sandbox.aliases())
        assertPlannedOnlyOnce()
        assertSelected()
        assertNoAccessOrNativeWork()
    }

    @Test fun freshCoordinatorCannotContinuePersistedPlanWithoutOriginalVerifiedInMemoryAttempt() = live {
        resources.failAfter = "credential"
        assertFailure(coordinator.begin(verified()), FailureReason.OUTCOME_UNKNOWN)
        val original = control.read().liveValue()!!
        coordinator.close().liveValue()
        val replacement = newCoordinator()
        val before = resources.selected.toList()
        val planned = resources.planned.toList()
        try {
            assertFailure(replacement.retry(), FailureReason.NOT_CONFIGURED)
            assertFailure(replacement.begin(verified()), FailureReason.CONFLICT)
        } finally { replacement.close().liveValue() }
        assertEquals(before, resources.selected)
        assertEquals(planned, resources.planned)
        assertEquals(original.revision, control.read().liveValue()!!.revision)
        assertBytes(original.payload, control.read().liveValue()!!.payload)
        assertNoAccessOrNativeWork()
    }

    @Test fun selectedPrivateRecordsIncludingTombstonesBlockRetryBeforeAnyFurtherSelection() = live {
        resources.failAfter = "data"
        assertFailure(coordinator.begin(verified()), FailureReason.OUTCOME_UNKNOWN)
        val original = pending().plan.copyForStorage()
        val store = data.resume(SCOPE).liveValue()!!
        val key = RecordKey("native-live-setup", "private-row")
        val revision = store.commit(SCOPE, listOf(StoreMutation.Put(key, null, 1,
            PrivateBytes("private-test-canary".encodeToByteArray())))).liveValue().getValue(key)!!
        val before = resources.selected.toList()
        assertFailure(coordinator.retry(), FailureReason.CONFLICT)
        assertEquals(before, resources.selected)
        store.commit(SCOPE, listOf(StoreMutation.Delete(key, revision))).liveValue()
        assertFailure(coordinator.retry(), FailureReason.CONFLICT)
        assertEquals(before, resources.selected)
        assertTrue(data.inspectOwnerState(SCOPE).liveValue().hasRecords)
        assertBytes(original, pending().plan.copyForStorage())
        assertNoAccessOrNativeWork()
    }

    @Test fun ordinaryWorkOriginCannotBeCrossJoinedEvenWhenItCopiesTheExactPlannedOrigin() = live {
        resources.failAfter = "credential"
        assertFailure(coordinator.begin(verified()), FailureReason.OUTCOME_UNKNOWN)
        val original = pending().plan.copyForStorage()
        val plan = SessionSetupPlanCodec.decode(original)
        val origin = SessionWorkOriginPlanCodec.decode(plan.workOriginPlan.copyForStorage()).origin
        val current = work.read().liveValue()!!
        work.compareAndSet(current.revision,
            SessionWorkCodec.encode(SessionWorkState.Origin(SCOPE, origin, false, emptyList()))).liveValue()
        val before = resources.selected.toList()
        assertFailure(coordinator.retry(), FailureReason.STALE_SESSION)
        assertEquals(before, resources.selected)
        assertNull(data.inspectOwnerState(SCOPE).liveValue().target)
        assertBytes(original, pending().plan.copyForStorage())
        assertNoAccessOrNativeWork()
    }

    @Test fun cancelAndCloseDoNotClearTheExactPendingJournalOrGrantResourceCleanupAuthority() = live {
        resources.failAfter = "credential"
        assertFailure(coordinator.begin(verified()), FailureReason.OUTCOME_UNKNOWN)
        val original = control.read().liveValue()!!
        val files = sandbox.fileSnapshot()
        val aliases = sandbox.aliases()
        val before = resources.selected.toList()
        coordinator.cancel().liveValue()
        assertFailure(coordinator.retry(), FailureReason.NOT_CONFIGURED)
        coordinator.close().liveValue()
        coordinator.close().liveValue()
        assertFailure(coordinator.retry(), FailureReason.STORAGE_FAILURE)
        assertEquals(before, resources.selected)
        assertEquals(original.revision, control.read().liveValue()!!.revision)
        assertBytes(original.payload, control.read().liveValue()!!.payload)
        sandbox.assertFilesEqual(files)
        assertEquals(aliases, sandbox.aliases())
        assertNoAccessOrNativeWork()
    }

    private fun live(block: suspend Fixture.() -> Unit) = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val sandbox = AndroidRetirementIntegrationSandbox.create(
                InstrumentationRegistry.getInstrumentation().targetContext)
            val fixture = Fixture(sandbox)
            try { fixture.open(); fixture.block() }
            finally { withContext(NonCancellable) { fixture.finish() } }
        }
    }

    private class Fixture(val sandbox: AndroidRetirementIntegrationSandbox) {
        lateinit var data: EncryptedStateDatabase
        lateinit var control: EncryptedSessionControlStore
        lateinit var work: EncryptedSessionWorkStore
        lateinit var credentials: AndroidCredentialStore
        lateinit var registry: SessionWorkRegistry
        lateinit var countedControl: CountingControl
        lateinit var resources: RecordingResources
        lateinit var coordinator: LiveSessionSetupCoordinator
        val boundary = SessionBoundary()
        var operationIds = 0
        var originIds = 0
        private var nativeWorkCalls = 0
        private val closers = mutableListOf<suspend () -> PortResult<Unit>>()

        suspend fun open() {
            data = AndroidStateDatabase.open(sandbox.context).liveValue().also { value -> closers += { value.close() } }
            control = AndroidSessionControlStore.open(sandbox.context).liveValue().also { value -> closers += { value.close() } }
            work = AndroidSessionWorkStore.open(sandbox.context).liveValue().also { value -> closers += { value.close() } }
            credentials = AndroidCredentialStore.open(sandbox.context).liveValue().also { value -> closers += { value.close() } }
            countedControl = CountingControl(control)
            registry = SessionWorkRegistry.open(work, boundary, Dispatchers.Main.immediate,
                NativeWorkCancellationPort { forbiddenNativeWork() },
                NativeWorkIdSource { originIds++; UUID.randomUUID().toString() },
                NativeWorkAdmissionPolicy { forbiddenNativeWork() },
                NativeWorkExecutionPolicy { _, _, _, _ -> forbiddenNativeWork() },
            ).liveValue().also { value -> closers += { value.close() } }
            resources = RecordingResources(this, NativeLiveSessionSetupResources(credentials, data, registry))
            coordinator = newCoordinator().also { value -> closers += { value.close() } }
            assertUnselected()
        }

        fun newCoordinator() = LiveSessionSetupCoordinator(countedControl, resources, boundary,
            Dispatchers.Main.immediate, CONFIGURATION, NativeWorkIdSource {
                operationIds++
                UUID.randomUUID().toString()
            })

        suspend fun pending(): RetirementState.PendingSetup {
            val state = RetirementCodec.decode(control.read().liveValue()!!.payload)
            assertTrue("Known plans must stay in the independent composite barrier", state is RetirementState.PendingSetup)
            return state as RetirementState.PendingSetup
        }

        suspend fun assertUnselected() {
            val slot = credentials.state().liveValue()
            assertEquals(1L, slot.revision)
            assertNull(slot.owner); assertNull(slot.incarnation)
            val owner = data.inspectOwnerState(SCOPE).liveValue()
            assertNull(owner.target); assertFalse(owner.hasRecords)
            assertTrue(sandbox.dataOwnerAliases().isEmpty())
            assertTrue(sandbox.credentialAliases().isEmpty())
            val workRecord = work.read().liveValue()!!
            assertEquals(1L, workRecord.revision)
            assertEquals(SessionWorkState.Idle, SessionWorkCodec.decode(workRecord.payload))
            assertNoAccessOrNativeWork()
        }

        suspend fun assertSelected() {
            val plan = SessionSetupPlanCodec.decode(pending().plan.copyForStorage())
            val credentialPlan = CredentialCreatePlanCodec.decode(plan.credentialPlan.copyForStorage())
            val slot = credentials.state().liveValue()
            assertEquals(SCOPE, slot.owner)
            assertEquals(credentialPlan.incarnation, slot.incarnation)
            assertEquals(credentialPlan.snapshotRevision, slot.revision)
            assertEquals(1, sandbox.credentialAliases().size)
            assertEquals(StateActivationStatus.SELECTED_EMPTY,
                data.inspectPlannedActivation(SCOPE, plan.dataPlan).liveValue().status)
            val owner = data.inspectOwnerState(SCOPE).liveValue()
            assertNotNull(owner.target); assertFalse(owner.hasRecords)
            assertEquals(1, sandbox.dataOwnerAliases().size)
            assertEquals(SessionWorkOriginPlanStatus.SELECTED, registry.inspectOrigin(plan.workOriginPlan).liveValue())
            val selected = SessionWorkCodec.decode(work.read().liveValue()!!.payload) as SessionWorkState.SetupSelected
            assertBytes(plan.workOriginPlan.copyForStorage(), selected.plan.copyForStorage())
            assertFailure(registry.snapshot(), FailureReason.CONFLICT)
            assertFalse(pending().abortRequested)
            assertNoAccessOrNativeWork()
        }

        fun assertPlannedOnlyOnce() {
            assertEquals(listOf("credential", "data", "work"), resources.planned)
            assertEquals(1, operationIds)
            assertEquals(1, originIds)
        }

        fun assertNoAccessOrNativeWork() {
            assertNull("Selection-only coordinator must not synthesize a lease", boundary.current())
            assertEquals(0, nativeWorkCalls)
        }

        private fun forbiddenNativeWork(): Nothing {
            nativeWorkCalls++
            throw AssertionError("Live selection stage cannot invoke OS or lease admission effects")
        }

        fun resourceFiles() = sandbox.fileSnapshot().filterKeys { !it.startsWith("feedme-session-control/") }
        fun assertResourcesEqual(expected: Map<String, ByteArray>) {
            val actual = resourceFiles()
            assertEquals(expected.keys, actual.keys)
            expected.forEach { (name, bytes) -> assertArrayEquals("Native resource bytes changed", bytes, actual.getValue(name)) }
        }

        suspend fun finish() {
            var failed = false
            for (close in closers.asReversed()) {
                try { if (close() is PortResult.Failure) failed = true }
                catch (_: Exception) { failed = true }
            }
            closers.clear()
            if (failed) {
                sandbox.releasePreservingFixture()
                throw AssertionError("Retaining exact native fixture after an unacknowledged close")
            }
            withContext(Dispatchers.IO) { sandbox.removeOwnedFixture() }
        }
    }

    /** All mutations still use native stores; only their application-level acknowledgements fail. */
    private class RecordingResources(
        private val fixture: Fixture,
        private val native: LiveSessionSetupResources,
    ) : LiveSessionSetupResources by native {
        val planned = mutableListOf<String>()
        val selected = mutableListOf<String>()
        var failAfter: String? = null
        private var credentialPlan: CredentialCreatePlan? = null
        private var dataPlan: StateActivationPlan? = null
        private var workPlan: SessionWorkOriginPlan? = null

        override suspend fun planCredential(expectedRevision: Long, credentials: StoredCredentials): PortResult<CredentialCreatePlan> =
            plan("credential") { native.planCredential(expectedRevision, credentials) }.also {
                if (it is PortResult.Value) credentialPlan = it.value
            }

        override suspend fun planData(scope: StorageScope): PortResult<StateActivationPlan> =
            plan("data") { native.planData(scope) }.also { if (it is PortResult.Value) dataPlan = it.value }

        override suspend fun planWork(scope: StorageScope, expectedRevision: Long): PortResult<SessionWorkOriginPlan> =
            plan("work") { native.planWork(scope, expectedRevision) }.also { if (it is PortResult.Value) workPlan = it.value }

        override suspend fun selectCredential(plan: CredentialCreatePlan, credentials: StoredCredentials): PortResult<CredentialSnapshot> {
            assertBytes(checkNotNull(credentialPlan).copyForStorage(), plan.copyForStorage())
            return select("credential") { native.selectCredential(plan, credentials) }
        }

        override suspend fun selectData(scope: StorageScope, plan: StateActivationPlan): PortResult<Unit> {
            assertEquals(SCOPE, scope)
            assertArrayEquals(checkNotNull(dataPlan).copyForStorage(), plan.copyForStorage())
            return select("data") { native.selectData(scope, plan) }
        }

        override suspend fun selectWork(plan: SessionWorkOriginPlan): PortResult<Unit> {
            assertBytes(checkNotNull(workPlan).copyForStorage(), plan.copyForStorage())
            return select("work") { native.selectWork(plan) }
        }

        private suspend fun <T> plan(name: String, operation: suspend () -> PortResult<T>): PortResult<T> {
            fixture.assertNoAccessOrNativeWork()
            assertEquals(0, fixture.countedControl.writes.size)
            assertTrue(selected.isEmpty())
            val files = fixture.sandbox.fileSnapshot()
            val aliases = fixture.sandbox.aliases()
            planned += name
            val result = operation()
            fixture.sandbox.assertFilesEqual(files)
            assertEquals(aliases, fixture.sandbox.aliases())
            fixture.assertNoAccessOrNativeWork()
            return result
        }

        private suspend fun <T> select(name: String, operation: suspend () -> PortResult<T>): PortResult<T> {
            fixture.assertNoAccessOrNativeWork()
            assertEquals(listOf("credential", "data", "work"), planned)
            val current = fixture.control.read().liveValue()!!
            assertEquals("A fresh successful changed CAS and exact readback must precede selection",
                current.revision, fixture.countedControl.acknowledgedRevision)
            val pending = RetirementCodec.decode(current.payload) as RetirementState.PendingSetup
            assertFalse(pending.abortRequested)
            val record = SessionSetupPlanCodec.decode(pending.plan.copyForStorage())
            assertEquals(SCOPE, record.scope)
            assertEquals(CONFIGURATION, record.configurationBinding)
            assertBytes(checkNotNull(credentialPlan).copyForStorage(), record.credentialPlan.copyForStorage())
            assertArrayEquals(checkNotNull(dataPlan).copyForStorage(), record.dataPlan.copyForStorage())
            assertBytes(checkNotNull(workPlan).copyForStorage(), record.workOriginPlan.copyForStorage())
            selected += name
            val result = operation()
            fixture.assertNoAccessOrNativeWork()
            if (result is PortResult.Value && failAfter == name) {
                failAfter = null
                // The native command returned success. This is NOT an injected engine sync error.
                return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
            }
            return result
        }
    }

    private enum class ControlFailure { BEFORE_WRITE, AFTER_WRITE, READBACK }

    private class CountingControl(private val native: SessionControlStore) : SessionControlStore {
        val writes = mutableListOf<PrivateBytes>()
        var failure: ControlFailure? = null
        var acknowledgedRevision = 0L
            private set
        private var awaitingReadback: SessionControlRecord? = null
        private var failReadback = false

        override suspend fun read(): PortResult<SessionControlRecord?> {
            if (failReadback) {
                failReadback = false
                awaitingReadback = null
                return PortResult.Failure(FailureReason.STORAGE_FAILURE)
            }
            val result = native.read()
            val receipt = awaitingReadback
            if (receipt != null && result is PortResult.Value) {
                val current = result.value
                if (current != null && current.revision == receipt.revision &&
                    current.payload.copyForCodec().contentEquals(receipt.payload.copyForCodec())) {
                    acknowledgedRevision = current.revision
                }
                awaitingReadback = null
            }
            return result
        }

        override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> {
            writes += PrivateBytes(payload.copyForCodec())
            val injected = failure
            failure = null
            awaitingReadback = null
            if (injected == ControlFailure.BEFORE_WRITE) return PortResult.Failure(FailureReason.STORAGE_FAILURE)
            val result = native.compareAndSet(expectedRevision, payload)
            if (result is PortResult.Value) {
                if (injected == ControlFailure.AFTER_WRITE) return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
                awaitingReadback = result.value
                failReadback = injected == ControlFailure.READBACK
            }
            return result
        }
    }

    private companion object {
        val SCOPE = StorageScope("native-live-setup", ActorKind.ACCOUNT, "test-only-live-owner")
        val CONFIGURATION = "e".repeat(64)
        const val ACCESS = "test-only-live-access-canary"
        const val REFRESH = "test-only-live-refresh-canary"
        fun verified() = StoredCredentials.Account(SCOPE, SecretText(ACCESS), SecretText(REFRESH), Long.MAX_VALUE,
            SecretText("00000000-0000-4000-8000-000000000471"))
        fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        fun assertBytes(expected: PrivateBytes, actual: PrivateBytes) = assertArrayEquals(expected.copyForCodec(), actual.copyForCodec())
        fun assertFailure(value: PortResult<*>, reason: FailureReason) = assertEquals(PortResult.Failure(reason), value)
        fun <T> PortResult<T>.liveValue(): T = when (this) {
            is PortResult.Value -> value
            is PortResult.Failure -> throw AssertionError("Expected native live-setup value, got $reason")
        }
    }
}
