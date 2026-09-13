package com.feedme.session

import com.feedme.core.ports.*
import com.feedme.storage.StateActivationInspection
import com.feedme.storage.StateActivationPlan
import com.feedme.storage.StateActivationStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Recording protocol fixtures prove ordering and fences, not native authentication or fsync. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSessionSetupCoordinatorTest {
    @Test fun allPlansAndPreflightPrecedeOneRetainedIntentAndThreeOrderedSelections() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.coordinator.begin(account()))
        val firstWrite = f.events.indexOf("control.cas")
        for (name in listOf("planCredential", "planData", "planWork", "inspectData", "inspectWork"))
            assertTrue(f.events.indexOf(name) in 0 until firstWrite, name)
        assertEquals(listOf("selectCredential", "selectData", "selectWork"), f.selections())
        assertTrue(firstWrite < f.events.indexOf("selectCredential"))
        assertEquals(1, f.ids)
        assertEquals(listOf("planCredential", "planData", "planWork"), f.plans())
        val pending = f.pending()
        val plan = SessionSetupPlanCodec.decode(pending.plan.copyForStorage())
        assertEquals(ACCOUNT, plan.scope)
        assertEquals(CONFIGURATION, plan.configurationBinding)
        assertEquals(OPERATION, plan.operationId)
        assertFalse(pending.abortRequested)
        assertNull(f.boundary.current())
        assertTrue(f.control.written.all { RetirementCodec.decode(it.payload) is RetirementState.PendingSetup })
        assertEquals(StateActivationStatus.SELECTED_EMPTY, f.dataStatus)
        assertEquals(SessionWorkOriginPlanStatus.SELECTED, f.workStatus)
    }

    @Test fun accountAndGuestRemainExactDistinctVerifiedScopes() = runTest {
        for (credentials in listOf(account(), guest())) {
            val f = Fixture(StandardTestDispatcher(testScheduler))
            value(f.coordinator.begin(credentials))
            val record = SessionSetupPlanCodec.decode(f.pending().plan.copyForStorage())
            assertEquals(credentials.scope, record.scope)
            assertEquals(credentials.scope, f.slot.owner)
            assertEquals(credentials.scope, SessionWorkOriginPlanCodec.decode(record.workOriginPlan.copyForStorage()).scope)
            assertSame(credentials, f.selectedCredentials.single())
            assertNull(f.boundary.current())
        }
    }

    @Test fun successfulSelectionRemainsPendingAndExactReplayNeverReplansOrClearsIt() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.coordinator.begin(account()))
        val previous = f.control.record!!
        val plans = f.plans()
        value(f.coordinator.retry())
        assertEquals(plans, f.plans())
        assertEquals(1, f.ids)
        assertTrue(f.control.record!!.revision > previous.revision)
        assertBytes(previous.payload, f.control.record!!.payload)
        assertEquals(List(2) { listOf("selectCredential", "selectData", "selectWork") }.flatten(), f.selections())
        assertTrue(f.selectedCredentials.all { it === f.selectedCredentials.first() })
        assertFalse(f.pending().abortRequested)
        assertNull(f.boundary.current())
    }

    @Test fun invalidConfigurationAndCredentialShapeCannotReachPlanningOrWrites() = runTest {
        for (configuration in listOf("", "a".repeat(63), "A".repeat(64), "g".repeat(64))) {
            val f = Fixture(StandardTestDispatcher(testScheduler))
            val result = runCatching { f.newCoordinator(configuration) }
            if (result.isSuccess) failure(result.getOrThrow().begin(account()))
            assertTrue(f.events.isEmpty())
        }
        val f = Fixture(StandardTestDispatcher(testScheduler))
        failure(f.coordinator.begin(account(device = null)))
        assertTrue(f.plans().isEmpty()); assertEquals(0, f.control.writes); assertTrue(f.selections().isEmpty())
    }

    @Test fun activeBoundaryOrStaleOwnerRejectsBeforeResourceOrControlAccess() = runTest {
        for (active in listOf(false, true)) {
            val f = Fixture(StandardTestDispatcher(testScheduler))
            if (active) f.boundary.activate(ACCOUNT) else f.current = false
            failure(f.coordinator.begin(account()))
            failure(f.coordinator.retry())
            assertTrue(f.events.isEmpty())
        }
    }

    @Test fun processRetirementLatchStopsIdleControlBeforeAnySetupEffect() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.retiring = true
        failure(f.coordinator.begin(account()))
        assertTrue(f.plans().isEmpty()); assertTrue(f.selections().isEmpty()); assertEquals(0, f.control.writes)
    }

    @Test fun everyPendingControlKindAndMalformedOrMissingControlBlocksNewSetup() = runTest {
        for (kind in 0..5) {
            val f = Fixture(StandardTestDispatcher(testScheduler))
            f.control.record = when (kind) {
                0 -> SessionControlRecord(11, RetirementCodec.encode(RetirementState.PendingCreate(credentialPlan(), false)))
                1 -> SessionControlRecord(11, RetirementCodec.encode(RetirementState.PendingCreate(credentialPlan(), true)))
                2 -> SessionControlRecord(11, RetirementCodec.encode(RetirementState.SetupDiscardPending(OPERATION, ACCOUNT, ORIGIN, null, null, emptySet())))
                3 -> SessionControlRecord(11, RetirementCodec.encode(RetirementState.PendingSetup(composite(), false)))
                4 -> SessionControlRecord(11, bytes("{\"version\":1,\"state\":\"future\"}"))
                else -> null
            }
            val before = f.control.record
            failure(f.coordinator.begin(account()))
            assertSame(before, f.control.record); assertEquals(0, f.control.writes)
            assertTrue(f.plans().isEmpty()); assertTrue(f.selections().isEmpty())
        }
    }

    @Test fun occupiedGuestAccountAndOrdinaryWorkNeverBecomeNewSetupPredecessors() = runTest {
        for (kind in 0..5) {
            val f = Fixture(StandardTestDispatcher(testScheduler))
            when (kind) {
                0 -> f.slot = CredentialSlotState(3, ACCOUNT, CREDENTIAL)
                1 -> f.slot = CredentialSlotState(3, GUEST, CREDENTIAL)
                2 -> f.workSnapshot = SessionWorkSnapshot(7, ACCOUNT, ORIGIN, false, emptyList())
                3 -> f.workSnapshot = SessionWorkSnapshot(7, GUEST, ORIGIN, false, emptyList())
                4 -> f.workSnapshot = SessionWorkSnapshot(7, ACCOUNT, ORIGIN, true, emptyList())
                5 -> f.workSnapshot = SessionWorkSnapshot(7, ACCOUNT, ORIGIN, false,
                    listOf(NativeWorkStatus(NativeWorkTicket(OPERATION, NativeWorkKind.TIMER), NativeWorkPhase.RESERVED)))
            }
            failure(f.coordinator.begin(account()))
            assertEquals(0, f.control.writes); assertTrue(f.selections().isEmpty())
        }
    }

    @Test fun failuresDuringAnyPlanningOrPreflightOperationLeaveNoJournalOrSelections() = runTest {
        for (operation in listOf("credentialState", "workState", "planCredential", "planData", "planWork", "inspectData", "inspectWork")) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.failures[operation] = FailureReason.STORAGE_FAILURE
            failure(f.coordinator.begin(account()))
            assertEquals(0, f.control.writes, operation); assertTrue(f.selections().isEmpty(), operation)
            assertEquals(RetirementState.Idle, RetirementCodec.decode(f.control.record!!.payload))
        }
    }

    @Test fun nonemptyOrConsumedDataPreflightRejectsWithoutCredentialSelection() = runTest {
        for (status in listOf(StateActivationStatus.SELECTED_NONEMPTY, StateActivationStatus.ABORTING, StateActivationStatus.ABORTED)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.dataStatus = status
            failure(f.coordinator.begin(account()))
            assertEquals(0, f.control.writes); assertTrue(f.selections().isEmpty())
        }
    }

    @Test fun abortedOrSealedWorkStopsLiveRetryBeforeAnyAcknowledgementOrSelection() = runTest {
        for (status in listOf(SessionWorkOriginPlanStatus.ABORTED, SessionWorkOriginPlanStatus.SEALED)) {
            val f = Fixture(StandardTestDispatcher(testScheduler))
            value(f.coordinator.begin(account()))
            f.workStatus = status
            val control = f.control.record!!; val writes = f.control.writes
            val selections = f.selections(); val plans = f.plans()
            failure(f.coordinator.retry())
            assertSame(control, f.control.record); assertEquals(writes, f.control.writes)
            assertEquals(selections, f.selections()); assertEquals(plans, f.plans())
            assertEquals(1, f.ids); assertNull(f.boundary.current())
        }
    }

    @Test fun consumedWorkObservedBetweenSelectionsStopsTheNextNativeEffect() = runTest {
        for (status in listOf(SessionWorkOriginPlanStatus.ABORTED, SessionWorkOriginPlanStatus.SEALED)) {
            for (barrier in listOf("selectCredential", "selectData")) {
                val f = Fixture(StandardTestDispatcher(testScheduler))
                f.after = { if (it == barrier) f.workStatus = status }
                failure(f.coordinator.begin(account()))
                assertEquals(if (barrier == "selectCredential") listOf("selectCredential")
                    else listOf("selectCredential", "selectData"), f.selections())
                assertEquals(1, f.control.writes); assertFalse(f.pending().abortRequested)
                assertNull(f.boundary.current())
            }
        }
    }

    @Test fun malformedPlanRevisionScopeAndOperationIdentityStopBeforeJournal() = runTest {
        for (variant in 0..3) {
            val f = Fixture(StandardTestDispatcher(testScheduler))
            when (variant) {
                0 -> f.credentialPlanOverride = credentialPlan(expected = 4)
                1 -> f.workPlanOverride = workPlan(ACCOUNT.copy(actorId = "other-owner"))
                2 -> f.workPlanOverride = workPlan(expected = 8)
                3 -> f.id = "not-a-native-uuid"
            }
            failure(f.coordinator.begin(account()))
            assertEquals(0, f.control.writes); assertTrue(f.selections().isEmpty())
        }
    }

    @Test fun failedJournalCasBeforeCommitRetriesOriginalPredecessorWithoutNewPlans() = runTest {
        for (reason in listOf(FailureReason.STORAGE_FAILURE, FailureReason.OUTCOME_UNKNOWN)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); val predecessor = f.control.record!!
            f.control.beforeFailures[1] = reason
            failure(f.coordinator.begin(account()))
            assertSame(predecessor, f.control.record); assertTrue(f.selections().isEmpty())
            val planned = f.plans()
            value(f.coordinator.retry())
            assertEquals(planned, f.plans()); assertEquals(1, f.ids)
            assertEquals(listOf("selectCredential", "selectData", "selectWork"), f.selections())
            assertFalse(f.pending().abortRequested)
        }
    }

    @Test fun failedJournalCasAfterCommitNeverPromotesReadbackAndRetryRequiresNewAck() = runTest {
        for (reason in listOf(FailureReason.OUTCOME_UNKNOWN, FailureReason.STORAGE_FAILURE)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.control.afterFailures[1] = reason
            failure(f.coordinator.begin(account()))
            assertTrue(f.selections().isEmpty())
            val visible = f.control.record!!
            f.before = { if (it.startsWith("select")) assertTrue(f.control.record!!.revision > visible.revision) }
            value(f.coordinator.retry())
            assertBytes(visible.payload, f.control.record!!.payload)
            assertEquals(1, f.ids); assertEquals(3, f.plans().size)
        }
    }

    @Test fun falseSuccessReceiptOrMissingExactReadbackCannotAuthorizeAnySelection() = runTest {
        for (variant in 0..3) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); val before = f.control.record!!
            when (variant) {
                0 -> f.control.receipt = { SessionControlRecord(it.revision - 1, it.payload) }
                1 -> f.control.receipt = { SessionControlRecord(it.revision, bytes("{\"version\":1,\"state\":\"idle\"}")) }
                2 -> f.control.afterCas = { f.control.record = before }
                3 -> f.control.afterCas = { f.control.readFailure = FailureReason.STORAGE_FAILURE }
            }
            failure(f.coordinator.begin(account()))
            assertTrue(f.selections().isEmpty())
        }
    }

    @Test fun eachSelectionFailureStopsLaterEffectsAndReplaysOnlyOriginalIntent() = runTest {
        for ((index, operation) in listOf("selectCredential", "selectData", "selectWork").withIndex()) {
            for (reason in listOf(FailureReason.OUTCOME_UNKNOWN, FailureReason.STORAGE_FAILURE)) {
                val f = Fixture(StandardTestDispatcher(testScheduler)); f.failures[operation] = reason
                failure(f.coordinator.begin(account()))
                assertEquals(listOf("selectCredential", "selectData", "selectWork").take(index + 1), f.selections())
                val retained = f.control.record!!
                f.failures.clear()
                value(f.coordinator.retry())
                assertBytes(retained.payload, f.control.record!!.payload)
                assertTrue(f.control.record!!.revision > retained.revision)
                assertEquals(1, f.ids); assertEquals(3, f.plans().size)
                assertEquals(listOf("selectCredential", "selectData", "selectWork"), f.selections().takeLast(3))
            }
        }
    }

    @Test fun credentialSuccessMustMatchExactPlanScopeIncarnationRevisionAndPayload() = runTest {
        for (variant in 0..5) {
            val f = Fixture(StandardTestDispatcher(testScheduler))
            f.credentialReceipt = { snapshot -> when (variant) {
                0 -> CredentialSnapshot(ORIGIN, snapshot.revision, snapshot.credentials)
                1 -> CredentialSnapshot(snapshot.incarnation, snapshot.revision + 1, snapshot.credentials)
                2 -> CredentialSnapshot(snapshot.incarnation, snapshot.revision, account(scope = ACCOUNT.copy(actorId = "other-owner")))
                3 -> CredentialSnapshot(snapshot.incarnation, snapshot.revision, account(access = "changed-private-access"))
                4 -> CredentialSnapshot(snapshot.incarnation, snapshot.revision, account(refresh = "changed-private-refresh"))
                else -> CredentialSnapshot(snapshot.incarnation, snapshot.revision, account(device = ORIGIN))
            } }
            failure(f.coordinator.begin(account()))
            assertEquals(listOf("selectCredential"), f.selections())
            assertFalse(f.pending().abortRequested)
        }
    }

    @Test fun retryRejectsReplacementJournalAbortRequestAndAdvancedTerminalPredecessor() = runTest {
        for (variant in 0..5) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.failures["selectData"] = FailureReason.STORAGE_FAILURE
            failure(f.coordinator.begin(account()))
            val pending = f.pending(); val record = f.control.record!!
            val replacement = when (variant) {
                0 -> RetirementCodec.encode(RetirementState.PendingSetup(pending.plan, true))
                1 -> RetirementCodec.encode(RetirementState.PendingSetup(composite(operation = ORIGIN), false))
                2 -> RetirementCodec.encode(RetirementState.PendingCreate(credentialPlan(), false))
                3 -> RetirementCodec.encode(RetirementState.Idle)
                4 -> RetirementCodec.encode(RetirementState.Complete(ORIGIN))
                else -> bytes("{\"version\":1,\"state\":\"unknown\"}")
            }
            f.control.record = SessionControlRecord(record.revision + 1, replacement)
            val writes = f.control.writes; val calls = f.selections()
            failure(f.coordinator.retry())
            assertEquals(writes, f.control.writes); assertEquals(calls, f.selections())
            assertBytes(replacement, f.control.record!!.payload)
        }
    }

    @Test fun changedOriginalPredecessorCannotBeReplacedAfterUncommittedJournalFailure() = runTest {
        for (advance in listOf(false, true)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.control.beforeFailures[1] = FailureReason.STORAGE_FAILURE
            failure(f.coordinator.begin(account()))
            val original = f.control.record!!
            f.control.record = if (advance) SessionControlRecord(original.revision + 1, original.payload)
                else SessionControlRecord(original.revision, RetirementCodec.encode(RetirementState.Complete(ORIGIN)))
            val writes = f.control.writes
            failure(f.coordinator.retry())
            assertEquals(writes, f.control.writes); assertTrue(f.selections().isEmpty())
        }
    }

    @Test fun newCoordinatorCannotRecoverVerifiedCredentialsFromPersistedCompositeIntent() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); value(f.coordinator.begin(account()))
        val before = f.control.record!!; val calls = f.events.toList()
        val reopened = f.newCoordinator()
        failure(reopened.retry())
        failure(reopened.begin(account()))
        assertEquals(1, f.ids); assertEquals(3, f.plans().size)
        assertEquals(calls.filter { it.startsWith("select") }, f.selections())
        assertSame(before, f.control.record)
    }

    @Test fun secondBeginNeverReplacesRetainedAttemptWithNewIdentityOrNewOperation() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.failures["selectData"] = FailureReason.STORAGE_FAILURE
        failure(f.coordinator.begin(account()))
        val before = f.control.record!!; val calls = f.events.toList()
        failure(f.coordinator.begin(guest()))
        assertSame(before, f.control.record); assertEquals(calls, f.events)
        f.failures.clear(); value(f.coordinator.retry())
        assertEquals(ACCOUNT, SessionSetupPlanCodec.decode(f.pending().plan.copyForStorage()).scope)
        assertEquals(1, f.ids)
    }

    @Test fun controlReplacementAfterEachSelectionStopsTheNextEffectAndNeverOverwrites() = runTest {
        for (operation in listOf("selectCredential", "selectData", "selectWork")) {
            val f = Fixture(StandardTestDispatcher(testScheduler))
            f.after = { if (it == operation) f.control.record = SessionControlRecord(f.control.record!!.revision + 1,
                RetirementCodec.encode(RetirementState.PendingSetup(composite(operation = ORIGIN), true))) }
            failure(f.coordinator.begin(account()))
            val count = listOf("selectCredential", "selectData", "selectWork").indexOf(operation) + 1
            assertEquals(count, f.selections().size)
            assertTrue(f.pending().abortRequested)
        }
    }

    @Test fun processRetirementOrNewLeaseAfterAwaitFencesRemainingSelections() = runTest {
        for (operation in listOf("planData", "inspectWork", "selectCredential", "selectData", "selectWork")) {
            for (lease in listOf(false, true)) {
                val f = Fixture(StandardTestDispatcher(testScheduler))
                f.after = { if (it == operation) { if (lease) f.boundary.activate(ACCOUNT) else f.retiring = true } }
                failure(f.coordinator.begin(account()))
                val expected = if (operation.startsWith("select")) listOf("selectCredential", "selectData", "selectWork").indexOf(operation) + 1 else 0
                assertEquals(expected, f.selections().size, operation)
                if (lease) assertNotNull(f.boundary.current()) // Coordinator must not erase a newer identity.
            }
        }
    }

    @Test fun coroutineCancellationAtEveryPlanningBarrierAndSelectionDropsRetryAuthority() = runTest {
        for (operation in listOf("planCredential", "planData", "planWork", "inspectData", "inspectWork", "control.cas", "selectCredential", "selectData", "selectWork")) {
            val f = Fixture(StandardTestDispatcher(testScheduler))
            f.after = { if (it == operation) throw CancellationException("private-cancel-marker") }
            assertFailsWith<CancellationException> { f.coordinator.begin(account()) }
            f.after = { }
            val calls = f.selections(); val writes = f.control.writes; val before = f.control.record
            failure(f.coordinator.retry())
            assertEquals(calls, f.selections()); assertEquals(writes, f.control.writes); assertSame(before, f.control.record)
            assertNull(f.boundary.current())
        }
    }

    @Test fun explicitCancelFencesSuspendedLateSelectionAndDoesNotEraseRetainedIntent() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.after = { if (it == "selectCredential") { entered.complete(Unit); release.await() } }
        val selecting = async { f.coordinator.begin(account()) }
        entered.await()
        val before = f.control.record!!
        value(f.coordinator.cancel())
        release.complete(Unit)
        failure(selecting.await())
        failure(f.coordinator.retry())
        assertEquals(listOf("selectCredential"), f.selections())
        assertSame(before, f.control.record); assertNull(f.boundary.current())
    }

    @Test fun callerCancellationCannotLetNonCancellableLateNativeReplyContinue() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.after = { if (it == "selectData") withContext(NonCancellable) { entered.complete(Unit); release.await() } }
        val selecting = async { f.coordinator.begin(account()) }
        entered.await()
        val before = f.control.record!!
        selecting.cancel(); release.complete(Unit)
        assertFailsWith<CancellationException> { selecting.await() }
        failure(f.coordinator.retry())
        assertEquals(listOf("selectCredential", "selectData"), f.selections())
        assertSame(before, f.control.record); assertNull(f.boundary.current())
    }

    @Test fun cancellationAtSuccessfulDispatcherReturnDropsLiveRetryAuthority() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val scheduledCaller = StandardTestDispatcher(testScheduler)
        var armed = false
        val caller = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                // No fixture suspends once armed: this dispatch is the successful withContext
                // return to its caller, after the coordinator's final in-body checks.
                if (armed) context[Job]?.cancel()
                scheduledCaller.dispatch(context, block)
            }
        }
        f.after = { if (it == "selectWork") armed = true }
        val selecting = async(caller) { f.coordinator.begin(account()) }
        assertFailsWith<CancellationException> { selecting.await() }
        val before = f.control.record!!
        val effects = f.selections(); val writes = f.control.writes
        failure(f.coordinator.retry())
        assertEquals(effects, f.selections()); assertEquals(writes, f.control.writes)
        assertSame(before, f.control.record); assertFalse(f.pending().abortRequested)
        assertNull(f.boundary.current())
    }

    @Test fun cancellingQueuedRetryCannotInvalidateTheDifferentRunningAttempt() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.after = { if (it == "selectCredential") { entered.complete(Unit); release.await() } }
        val selecting = async { f.coordinator.begin(account()) }
        entered.await()
        val queued = async { f.coordinator.retry() }
        runCurrent()
        queued.cancel()
        runCurrent()
        assertFailsWith<CancellationException> { queued.await() }
        release.complete(Unit)
        value(selecting.await())
        assertEquals(listOf("selectCredential", "selectData", "selectWork"), f.selections())
        assertFalse(f.pending().abortRequested)
        f.after = { }
        value(f.coordinator.retry())
        assertEquals(6, f.selections().size)
        assertEquals(1, f.ids)
    }

    @Test fun closeFencesSuspendedPreparationAndIsPermanentWithoutOwningNativeCleanup() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.after = { if (it == "planWork") { entered.complete(Unit); release.await() } }
        val preparing = async { f.coordinator.begin(account()) }
        entered.await()
        value(f.coordinator.close()); value(f.coordinator.close())
        release.complete(Unit)
        failure(preparing.await())
        val before = f.events.toList()
        failure(f.coordinator.begin(account())); failure(f.coordinator.retry())
        assertEquals(before, f.events); assertEquals(0, f.control.writes); assertTrue(f.selections().isEmpty())
    }

    @Test fun thrownPrivateErrorsAreSanitizedAndNeverSelectLaterResources() = runTest {
        for (operation in listOf("control.read", "planCredential", "planData", "inspectWork", "selectCredential", "selectData", "selectWork")) {
            val f = Fixture(StandardTestDispatcher(testScheduler))
            f.before = { if (it == operation) error("private-exception-access-refresh-owner") }
            val failure = failure(f.coordinator.begin(account()))
            assertFalse(failure.toString().contains("private-exception"))
            assertNull(f.boundary.current())
            val expected = if (operation.startsWith("select")) listOf("selectCredential", "selectData", "selectWork").indexOf(operation) + 1 else 0
            assertEquals(expected, f.selections().size)
        }
    }

    private class Fixture(private val dispatcher: CoroutineDispatcher) : LiveSessionSetupResources {
        val events = mutableListOf<String>()
        val boundary = SessionBoundary()
        val control = Control(this)
        val coordinator: LiveSessionSetupCoordinator = newCoordinator()
        var before: suspend (String) -> Unit = { }
        var after: suspend (String) -> Unit = { }
        val failures = mutableMapOf<String, FailureReason>()
        var current = true
        var retiring = false
        var ids = 0
        var id = OPERATION
        var slot = CredentialSlotState(3, null, null)
        var workSnapshot = SessionWorkSnapshot(7, null, null, false, emptyList())
        var dataStatus = StateActivationStatus.PREPARED
        var workStatus = SessionWorkOriginPlanStatus.PREPARED
        var credentialPlanOverride: CredentialCreatePlan? = null
        var workPlanOverride: SessionWorkOriginPlan? = null
        var credentialReceipt: (CredentialSnapshot) -> CredentialSnapshot = { it }
        val selectedCredentials = mutableListOf<StoredCredentials>()
        private var plannedScope: StorageScope? = null
        private var plannedCredential: CredentialCreatePlan? = null
        private var plannedData: StateActivationPlan? = null
        private var plannedWork: SessionWorkOriginPlan? = null

        fun newCoordinator(configuration: String = CONFIGURATION) = LiveSessionSetupCoordinator(control, this,
            boundary, dispatcher, configuration, NativeWorkIdSource { ids++; id },
            processRetirementPending = { retiring }, isCurrent = { current })
        fun pending() = assertIs<RetirementState.PendingSetup>(RetirementCodec.decode(control.record!!.payload))
        fun plans() = events.filter { it.startsWith("plan") }
        fun selections() = events.filter { it.startsWith("select") }
        suspend fun <T> operation(name: String, action: () -> T): PortResult<T> {
            events += name
            before(name)
            failures[name]?.let { return PortResult.Failure(it) }
            val result = action()
            after(name)
            return PortResult.Value(result)
        }
        override suspend fun credentialState() = operation("credentialState") { slot }
        override suspend fun workState() = operation("workState") { workSnapshot }
        override suspend fun planCredential(expectedRevision: Long, credentials: StoredCredentials) = operation("planCredential") {
            assertEquals(slot.revision, expectedRevision); assertNull(slot.owner)
            plannedScope = credentials.scope
            (credentialPlanOverride ?: credentialPlan(expectedRevision)).also { plannedCredential = it }
        }
        override suspend fun planData(scope: StorageScope) = operation("planData") {
            assertEquals(plannedScope, scope)
            dataPlan().also { plannedData = it }
        }
        override suspend fun planWork(scope: StorageScope, expectedRevision: Long) = operation("planWork") {
            assertEquals(plannedScope, scope); assertEquals(workSnapshot.revision, expectedRevision)
            (workPlanOverride ?: workPlan(scope, expectedRevision)).also { plannedWork = it }
        }
        override suspend fun inspectData(scope: StorageScope, plan: StateActivationPlan) = operation("inspectData") {
            assertEquals(plannedScope, scope); assertContentEquals(plannedData!!.copyForStorage(), plan.copyForStorage())
            StateActivationInspection(dataStatus)
        }
        override suspend fun inspectWork(plan: SessionWorkOriginPlan) = operation("inspectWork") {
            assertBytes(plannedWork!!.copyForStorage(), plan.copyForStorage()); workStatus
        }
        override suspend fun selectCredential(plan: CredentialCreatePlan, credentials: StoredCredentials) = operation("selectCredential") {
            assertBarrier()
            assertBytes(plannedCredential!!.copyForStorage(), plan.copyForStorage())
            assertEquals(plannedScope, credentials.scope)
            selectedCredentials += credentials
            val decoded = CredentialCreatePlanCodec.decode(plan.copyForStorage())
            slot = CredentialSlotState(decoded.snapshotRevision, credentials.scope, decoded.incarnation)
            credentialReceipt(CredentialSnapshot(decoded.incarnation, decoded.snapshotRevision, credentials))
        }
        override suspend fun selectData(scope: StorageScope, plan: StateActivationPlan) = operation("selectData") {
            assertBarrier(); assertEquals(plannedScope, scope)
            assertContentEquals(plannedData!!.copyForStorage(), plan.copyForStorage())
            dataStatus = StateActivationStatus.SELECTED_EMPTY
        }
        override suspend fun selectWork(plan: SessionWorkOriginPlan) = operation("selectWork") {
            assertBarrier(); assertBytes(plannedWork!!.copyForStorage(), plan.copyForStorage())
            workStatus = SessionWorkOriginPlanStatus.SELECTED
        }
        private fun assertBarrier() {
            assertFalse(pending().abortRequested)
            assertNull(boundary.current())
            assertTrue(control.writes > 0)
        }
    }

    private class Control(private val f: Fixture) : SessionControlStore {
        var record: SessionControlRecord? = SessionControlRecord(11, RetirementCodec.encode(RetirementState.Idle))
        var writes = 0
        val written = mutableListOf<SessionControlRecord>()
        val beforeFailures = mutableMapOf<Int, FailureReason>()
        val afterFailures = mutableMapOf<Int, FailureReason>()
        var readFailure: FailureReason? = null
        var receipt: (SessionControlRecord) -> SessionControlRecord = { it }
        var afterCas: () -> Unit = { }
        override suspend fun read(): PortResult<SessionControlRecord?> {
            f.events += "control.read"; f.before("control.read")
            readFailure?.let { return PortResult.Failure(it) }
            val current = record
            f.after("control.read")
            return PortResult.Value(current)
        }
        override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> {
            writes++; f.events += "control.cas"; f.before("control.cas")
            beforeFailures[writes]?.let { return PortResult.Failure(it) }
            if (expectedRevision != record?.revision) return PortResult.Failure(FailureReason.CONFLICT)
            val updated = SessionControlRecord((record?.revision ?: 0) + 1, PrivateBytes(payload.copyForCodec()))
            record = updated; written += updated
            afterCas(); f.after("control.cas")
            afterFailures[writes]?.let { return PortResult.Failure(it) }
            return PortResult.Value(receipt(updated))
        }
    }

    companion object {
        private val ACCOUNT = StorageScope("live-setup-test", ActorKind.ACCOUNT, "private-live-owner")
        private val GUEST = ACCOUNT.copy(actorKind = ActorKind.GUEST, actorId = "private-guest-owner")
        private val CONFIGURATION = "d".repeat(64)
        private const val OPERATION = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        private const val CREDENTIAL = "11111111-2222-4333-8444-555555555555"
        private const val ORIGIN = "22222222-3333-4444-8555-666666666666"
        private const val DEVICE = "33333333-4444-4555-8666-777777777777"
        private fun account(scope: StorageScope = ACCOUNT, access: String = "private-access", refresh: String = "private-refresh", device: String? = DEVICE) =
            StoredCredentials.Account(scope, SecretText(access), SecretText(refresh), 500_000, device?.let(::SecretText))
        private fun guest() = StoredCredentials.Guest(GUEST, SecretText(CREDENTIAL), SecretText("private-guest-token"), 500_000)
        private fun credentialPlan(expected: Long = 3) = CredentialCreatePlan.create(CredentialCreatePlanRecord(expected,
            CREDENTIAL, "a".repeat(64), "b".repeat(64), "c".repeat(64)))
        private fun workPlan(scope: StorageScope = ACCOUNT, expected: Long = 7) = SessionWorkOriginPlan.create(
            SessionWorkOriginPlanRecord(expected, scope, ORIGIN, PrivateBytes(ByteArray(64))))
        private fun dataPlan() = StateActivationPlan(ByteArray(170).apply {
            this[0] = 1
            for (index in 2..65) this[index] = 'a'.code.toByte()
            for (index in 106..137) this[index] = 'b'.code.toByte()
        })
        private fun composite(operation: String = OPERATION) = SessionSetupPlan.create(SessionSetupPlanRecord(operation,
            ACCOUNT, CONFIGURATION, credentialPlan(), dataPlan(), workPlan()))
        private fun bytes(raw: String) = PrivateBytes(raw.encodeToByteArray())
        private fun assertBytes(expected: PrivateBytes, actual: PrivateBytes) = assertContentEquals(expected.copyForCodec(), actual.copyForCodec())
        private fun failure(result: PortResult<*>) = assertIs<PortResult.Failure>(result)
        private fun <T> value(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
    }
}
