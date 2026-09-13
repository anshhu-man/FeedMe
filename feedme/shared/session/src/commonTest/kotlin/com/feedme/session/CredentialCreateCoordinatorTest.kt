package com.feedme.session

import com.feedme.core.ports.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Protocol fakes prove ordering and exact receipts; Android tests separately prove native durability. */
@OptIn(ExperimentalCoroutinesApi::class)
class CredentialCreateCoordinatorTest {
    @Test fun idleAndCompletedHaveNoProposalAndInspectionNeverOpensNativeRecovery() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        assertNull(value(f.coordinator.inspectPending()))
        f.control.set(RetirementState.Complete(ID))
        assertNull(value(f.coordinator.inspectPending()))
        assertEquals(0, f.control.writes)
        assertEquals(0, f.opens)
    }

    @Test fun proposalIsOpaqueDetachedRedactedAndNotAutomaticAbortPermission() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.seed()
        val proposal = value(f.coordinator.inspectPending())!!
        assertFalse(proposal.abortRequested)
        for (secret in listOf(ID, "a".repeat(64), "private-account", "private-access")) assertFalse(proposal.toString().contains(secret))
        failure(FailureReason.CONFLICT, f.coordinator.recoverAbort())
        assertEquals(0, f.opens); assertEquals(0, f.control.writes)
        assertFalse(f.pending().abortRequested)
    }

    @Test fun ordinaryCleanupIsNotMistakenForNoPendingCredentialWork() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.control.set(RetirementState.SetupDiscardPending(ID, ACCOUNT, null, ID, null, emptySet()))
        failure(FailureReason.CONFLICT, f.coordinator.inspectPending())
        failure(FailureReason.CONFLICT, f.coordinator.recoverAbort())
        assertEquals(0, f.opens); assertEquals(0, f.control.writes)
    }

    @Test fun confirmedAbortPersistsExactPlanBeforeNativeAndCompletesOnlyAfterClose() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.seed()
        f.afterOpen = { assertTrue(f.pending().abortRequested); assertEquals(1, f.control.writes) }
        f.afterClose = { assertTrue(f.pending().abortRequested); assertEquals(1, f.control.writes) }
        value(f.coordinator.requestAbort(value(f.coordinator.inspectPending())!!))
        assertEquals(ID, assertIs<RetirementState.Complete>(f.state()).operationId)
        assertEquals(listOf("open", "inspect", "abort", "inspect", "close"), f.events)
        assertEquals(2, f.control.writes)
        assertNull(value(f.coordinator.inspectPending()))
    }

    @Test fun everyInitialNativeStatusIncludingAbortedRequiresIdempotentDurableAbortAck() = runTest {
        for (status in CredentialCreateRecoveryStatus.entries) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.seed(true); f.status = status
            value(f.coordinator.recoverAbort())
            assertEquals(1, f.aborts, status.name)
            assertEquals(1, f.closes)
            assertIs<RetirementState.Complete>(f.state())
        }
    }

    @Test fun alreadyAbortedReadDoesNotReplaceFailedDurabilityReacknowledgement() = runTest {
        for (reason in listOf(FailureReason.OUTCOME_UNKNOWN, FailureReason.STORAGE_FAILURE)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.seed(true)
            f.status = CredentialCreateRecoveryStatus.ABORTED; f.abortFailure = reason
            failure(reason, f.coordinator.recoverAbort())
            assertTrue(f.pending().abortRequested)
            assertEquals(1, f.aborts); assertEquals(1, f.closes); assertEquals(1, f.control.writes)
        }
    }

    @Test fun requestBarrierFailureBeforeCommitDoesNotOpenNativeOrInferConfirmation() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.seed()
        f.control.beforeFailures[1] = FailureReason.STORAGE_FAILURE
        failure(FailureReason.STORAGE_FAILURE, f.coordinator.requestAbort(value(f.coordinator.inspectPending())!!))
        assertFalse(f.pending().abortRequested); assertEquals(0, f.opens)
        failure(FailureReason.CONFLICT, f.coordinator.recoverAbort())
    }

    @Test fun unknownBarrierNeverAllowsAbortEvenWhenExactConfirmationIsVisible() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.seed()
        f.control.beforeFailures[1] = FailureReason.OUTCOME_UNKNOWN
        failure(FailureReason.OUTCOME_UNKNOWN, f.coordinator.requestAbort(value(f.coordinator.inspectPending())!!))
        assertFalse(f.pending().abortRequested); assertEquals(0, f.opens)
        f.control.afterFailures[2] = FailureReason.OUTCOME_UNKNOWN
        failure(FailureReason.OUTCOME_UNKNOWN, f.coordinator.requestAbort(value(f.coordinator.inspectPending())!!))
        assertTrue(f.pending().abortRequested); assertEquals(0, f.opens)
        val unknownRevision = f.control.record!!.revision
        f.afterOpen = { assertEquals(unknownRevision + 1, f.control.record!!.revision) }
        value(f.reopened().recoverAbort())
        assertEquals(1, f.opens); assertIs<RetirementState.Complete>(f.state())
    }

    @Test fun malformedSuccessfulBarrierReceiptCannotAuthorizeNativeAbort() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.seed()
        f.control.receipt = { _, expected, _ -> SessionControlRecord(expected, RetirementCodec.encode(RetirementState.Idle)) }
        failure(FailureReason.STORAGE_FAILURE, f.coordinator.requestAbort(value(f.coordinator.inspectPending())!!))
        assertTrue(f.pending().abortRequested); assertEquals(0, f.opens)
    }

    @Test fun successReceiptWithoutExactDurableReadbackNeverOpensNative() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.seed()
        val previous = f.control.record!!
        f.control.afterCas = { _, _ -> f.control.record = previous }
        failure(FailureReason.OUTCOME_UNKNOWN, f.coordinator.requestAbort(value(f.coordinator.inspectPending())!!))
        assertFalse(f.pending().abortRequested); assertEquals(0, f.opens)
    }

    @Test fun unreadableBarrierReadbackPreservesIntentAndRequiresExplicitRecovery() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.seed()
        f.control.afterCas = { _, _ -> f.control.readFailure = FailureReason.STORAGE_FAILURE }
        failure(FailureReason.STORAGE_FAILURE, f.coordinator.requestAbort(value(f.coordinator.inspectPending())!!))
        assertTrue(f.pending().abortRequested); assertEquals(0, f.opens)
        f.control.afterCas = { _, _ -> }; f.control.readFailure = null
        value(f.reopened().recoverAbort())
        assertIs<RetirementState.Complete>(f.state())
    }

    @Test fun staleAndForeignProposalsCannotAbortNewerControlRecordEvenSamePlan() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.seed()
        val proposal = value(f.coordinator.inspectPending())!!
        failure(FailureReason.STALE_SESSION, f.reopened().requestAbort(proposal))
        f.control.set(RetirementState.PendingCreate(plan(), false))
        failure(FailureReason.CONFLICT, f.coordinator.requestAbort(proposal))
        assertEquals(0, f.opens); assertEquals(0, f.control.writes)
    }

    @Test fun activeIdentityBlocksEveryRecoveryEntryBeforeControlReadOrNativeOpen() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.seed()
        val proposal = value(f.coordinator.inspectPending())!!
        val reads = f.control.reads
        f.boundary.activate(ACCOUNT)
        failure(FailureReason.STALE_SESSION, f.coordinator.inspectPending())
        failure(FailureReason.STALE_SESSION, f.coordinator.requestAbort(proposal))
        failure(FailureReason.STALE_SESSION, f.coordinator.recoverAbort())
        assertEquals(reads, f.control.reads); assertEquals(0, f.opens); assertEquals(0, f.control.writes)
    }

    @Test fun identityActivatedDuringControlReadCannotReturnProposalOrWriteBarrier() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.seed()
        f.control.afterRead = { f.boundary.activate(ACCOUNT) }
        failure(FailureReason.STALE_SESSION, f.coordinator.inspectPending())
        assertEquals(0, f.control.writes); assertEquals(0, f.opens)
    }

    @Test fun identityActivatedDuringBarrierCommitLeavesRequestedPlanWithoutNativeEffects() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.seed()
        f.control.afterCas = { _, _ -> f.boundary.activate(ACCOUNT) }
        failure(FailureReason.STALE_SESSION, f.coordinator.requestAbort(value(f.coordinator.inspectPending())!!))
        assertTrue(f.pending().abortRequested); assertEquals(0, f.opens)
    }

    @Test fun handleReturnedAfterIdentityActivationIsClosedWithoutInspectOrAbort() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.seed(true)
        f.afterOpen = { f.boundary.activate(ACCOUNT) }
        failure(FailureReason.STALE_SESSION, f.coordinator.recoverAbort())
        assertEquals(listOf("open", "close"), f.events)
        assertTrue(f.pending().abortRequested)
    }

    @Test fun identityActivatedDuringInspectOrAbortPreventsControlCompletionAndStillCloses() = runTest {
        for (atAbort in listOf(false, true)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.seed(true)
            if (atAbort) f.afterAbort = { f.boundary.activate(ACCOUNT) }
            else f.afterInspect = { f.boundary.activate(ACCOUNT) }
            failure(FailureReason.STALE_SESSION, f.coordinator.recoverAbort())
            assertEquals(if (atAbort) 1 else 0, f.aborts)
            assertEquals(1, f.closes); assertTrue(f.pending().abortRequested)
        }
    }

    @Test fun nativeOpenInspectAndAbortFailuresPreserveExactRequestedPlan() = runTest {
        for (stage in listOf("open", "inspect", "abort")) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.seed(true)
            val original = f.control.record!!
            when (stage) {
                "open" -> f.openFailure = FailureReason.STORAGE_FAILURE
                "inspect" -> f.inspectFailure = FailureReason.STORAGE_FAILURE
                else -> f.abortFailure = FailureReason.OUTCOME_UNKNOWN
            }
            failure(if (stage == "abort") FailureReason.OUTCOME_UNKNOWN else FailureReason.STORAGE_FAILURE, f.coordinator.recoverAbort())
            assertEquals(original.revision + 1, f.control.record!!.revision)
            assertContentEquals(original.payload.copyForCodec(), f.control.record!!.payload.copyForCodec())
            assertEquals(if (stage == "open") 0 else 1, f.closes)
        }
    }

    @Test fun closeFailureRequiresNewHandleAndFreshAbortAcknowledgementBeforeCompletion() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.seed(true)
        f.closeFailure = FailureReason.STORAGE_FAILURE
        failure(FailureReason.STORAGE_FAILURE, f.coordinator.recoverAbort())
        assertTrue(f.pending().abortRequested); assertEquals(1, f.control.writes)
        f.closeFailure = null
        value(f.reopened().recoverAbort())
        assertEquals(2, f.opens); assertEquals(2, f.aborts); assertEquals(2, f.closes)
        assertIs<RetirementState.Complete>(f.state())
    }

    @Test fun controlChangeDuringNativeInspectOrCloseCannotBeOverwritten() = runTest {
        for (duringClose in listOf(false, true)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); f.seed(true)
            val change: suspend () -> Unit = { f.control.set(RetirementState.PendingCreate(plan(OTHER), false)) }
            if (duringClose) f.afterClose = change else f.afterInspect = change
            failure(FailureReason.CONFLICT, f.coordinator.recoverAbort())
            assertEquals(OTHER, CredentialCreatePlanCodec.decode(f.pending().plan.copyForStorage()).incarnation)
            assertFalse(f.pending().abortRequested)
            assertEquals(if (duringClose) 1 else 0, f.aborts); assertEquals(1, f.closes)
            assertEquals(1, f.control.writes)
        }
    }

    @Test fun callerCancellationDuringAbortClosesAndDurableRequestedPlanSurvivesForReopen() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.seed(true)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.afterAbort = { entered.complete(Unit); release.await() }
        val running = async { f.coordinator.recoverAbort() }
        entered.await(); running.cancel(); runCurrent()
        assertFailsWith<CancellationException> { running.await() }
        assertEquals(1, f.closes); assertTrue(f.pending().abortRequested)
        f.afterAbort = { }
        value(f.reopened().recoverAbort())
        assertEquals(2, f.aborts); assertIs<RetirementState.Complete>(f.state())
    }

    @Test fun callerCancellationCannotInterruptAcknowledgedHandleCloseOrClearPending() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.seed(true)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.afterClose = { entered.complete(Unit); release.await() }
        val running = async { f.coordinator.recoverAbort() }
        entered.await(); running.cancel(); runCurrent()
        assertFalse(running.isCompleted)
        release.complete(Unit); runCurrent()
        assertFailsWith<CancellationException> { running.await() }
        assertTrue(f.pending().abortRequested); assertEquals(1, f.closes)
    }

    @Test fun unknownCompletionCannotReturnSuccessEvenWhenCompletedOperationIsVisible() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.seed(true)
        f.control.afterFailures[2] = FailureReason.OUTCOME_UNKNOWN
        failure(FailureReason.OUTCOME_UNKNOWN, f.coordinator.recoverAbort())
        assertEquals(ID, assertIs<RetirementState.Complete>(f.state()).operationId)
        assertEquals(1, f.aborts)
    }

    @Test fun missingCorruptAndUnknownControlNeverBecomeEmptyPermission() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        for (record in listOf(null, SessionControlRecord(1, bytes("{}")), SessionControlRecord(1, bytes("{\"version\":1,\"state\":\"future\"}")))) {
            f.control.record = record
            failure(FailureReason.STORAGE_FAILURE, f.coordinator.inspectPending())
            failure(FailureReason.STORAGE_FAILURE, f.coordinator.recoverAbort())
        }
        assertEquals(0, f.opens); assertEquals(0, f.control.writes)
    }

    @Test fun liveCreateWritesAndReadsExactPendingBeforeNativeAndClearsOnlyVerifiedReceipt() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val store = PlannedStore()
        store.beforeCommit = {
            assertFalse(f.pending().abortRequested)
            assertContentEquals(store.plan.copyForStorage().copyForCodec(), f.pending().plan.copyForStorage().copyForCodec())
            assertEquals(1, f.control.writes)
        }
        val snapshot = value(commitCredentialCreate(f.control, store, 1, account()) { })
        assertEquals(ID, snapshot.incarnation); assertEquals(2, snapshot.revision)
        assertEquals(listOf("plan", "commit"), store.events)
        assertEquals(ID, assertIs<RetirementState.Complete>(f.state()).operationId)
        assertEquals(0, store.legacyCreates); assertEquals(2, f.control.writes)
    }

    @Test fun liveCreateCannotPassExistingPendingOrFailedPlanOrFailedBarrier() = runTest {
        for (stage in listOf("pending", "plan", "barrier")) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); val store = PlannedStore()
            when (stage) {
                "pending" -> f.seed()
                "plan" -> store.planFailure = FailureReason.NOT_CONFIGURED
                else -> f.control.beforeFailures[1] = FailureReason.STORAGE_FAILURE
            }
            val expected = when (stage) { "pending" -> FailureReason.CONFLICT; "plan" -> FailureReason.NOT_CONFIGURED; else -> FailureReason.STORAGE_FAILURE }
            failure(expected, commitCredentialCreate(f.control, store, 1, account()) { })
            assertFalse(store.events.contains("commit")); assertEquals(0, store.legacyCreates)
            if (stage == "pending") assertTrue(store.events.isEmpty())
        }
    }

    @Test fun liveNativeFailureOrUnknownLeavesExactUnconfirmedPlanWithNoLegacyFallback() = runTest {
        for (reason in listOf(FailureReason.STORAGE_FAILURE, FailureReason.OUTCOME_UNKNOWN, FailureReason.CONFLICT)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); val store = PlannedStore(); store.commitFailure = reason
            failure(reason, commitCredentialCreate(f.control, store, 1, account()) { })
            assertFalse(f.pending().abortRequested)
            assertEquals(1, f.control.writes); assertEquals(0, store.legacyCreates)
            assertEquals(listOf("plan", "commit"), store.events)
            failure(FailureReason.CONFLICT, f.coordinator.recoverAbort())
        }
    }

    @Test fun liveReceiptMustMatchExactOwnerIncarnationRevisionAndEveryCredentialField() = runTest {
        val replacements = listOf(
            CredentialSnapshot(OTHER, 2, account()), CredentialSnapshot(ID, 3, account()),
            CredentialSnapshot(ID, 2, account(ACCOUNT.copy(actorId = "different-owner"))),
            CredentialSnapshot(ID, 2, account(access = "changed-access")),
            CredentialSnapshot(ID, 2, account(refresh = "changed-refresh")),
            CredentialSnapshot(ID, 2, account(expiry = 999)),
        )
        for (replacement in replacements) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); val store = PlannedStore(); store.receipt = replacement
            failure(FailureReason.STALE_SESSION, commitCredentialCreate(f.control, store, 1, account()) { })
            assertFalse(f.pending().abortRequested); assertEquals(1, f.control.writes)
        }
    }

    @Test fun liveCreateRejectsPlanWrongRevisionAndControlChangeDuringPlanningWithoutCommit() = runTest {
        for (wrongRevision in listOf(false, true)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); val store = PlannedStore()
            if (wrongRevision) store.plan = plan(revision = 2)
            else store.afterPlan = { f.control.set(RetirementState.Complete(OTHER)) }
            failure(FailureReason.CONFLICT, commitCredentialCreate(f.control, store, 1, account()) { })
            assertEquals(listOf("plan"), store.events); assertEquals(0, f.control.writes)
        }
    }

    @Test fun liveCreateRequiresReadbackEvenWhenBarrierReportsSuccessfulReceipt() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val store = PlannedStore()
        val previous = f.control.record!!
        f.control.afterCas = { _, _ -> f.control.record = previous }
        failure(FailureReason.OUTCOME_UNKNOWN, commitCredentialCreate(f.control, store, 1, account()) { })
        assertEquals(listOf("plan"), store.events)
    }

    @Test fun liveCreateCancellationAfterNativeCommitKeepsPendingAndNeverCompletesItAutomatically() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val store = PlannedStore()
        store.beforeCommit = { throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> { commitCredentialCreate(f.control, store, 1, account()) { } }
        assertFalse(f.pending().abortRequested); assertEquals(1, f.control.writes)
    }

    @Test fun liveCreateUnknownPendingWriteDoesNotInvokeNativeCommitOrInventAnotherPlan() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val store = PlannedStore()
        f.control.afterFailures[1] = FailureReason.OUTCOME_UNKNOWN
        failure(FailureReason.OUTCOME_UNKNOWN, commitCredentialCreate(f.control, store, 1, account()) { })
        assertEquals(listOf("plan"), store.events)
        assertFalse(f.pending().abortRequested)
        failure(FailureReason.CONFLICT, commitCredentialCreate(f.control, store, 1, account()) { })
        assertEquals(listOf("plan"), store.events)
    }

    @Test fun liveCreateUnknownCompletionNeverPublishesCredentialSnapshot() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val store = PlannedStore()
        f.control.afterFailures[2] = FailureReason.OUTCOME_UNKNOWN
        failure(FailureReason.OUTCOME_UNKNOWN, commitCredentialCreate(f.control, store, 1, account()) { })
        assertEquals(listOf("plan", "commit"), store.events)
        assertEquals(ID, assertIs<RetirementState.Complete>(f.state()).operationId)
    }

    @Test fun repeatedRecoveryBarrierFailureNeverOpensEvenAlreadyAbortedHandle() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.seed(true)
        f.status = CredentialCreateRecoveryStatus.ABORTED
        val original = f.pending().plan.copyForStorage().copyForCodec()
        for (attempt in 1..3) {
            f.control.afterFailures[attempt] = FailureReason.OUTCOME_UNKNOWN
            failure(FailureReason.OUTCOME_UNKNOWN, f.reopened().recoverAbort())
            assertEquals(0, f.opens)
            assertContentEquals(original, f.pending().plan.copyForStorage().copyForCodec())
        }
        value(f.reopened().recoverAbort())
        assertEquals(1, f.opens); assertEquals(1, f.aborts)
    }

    @Test fun recoveryAcknowledgementPreservesExactAcceptedPayloadBytesUntilCompletion() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.seed(true)
        val prior = f.control.record!!
        val original = " \n" + prior.payload.copyForCodec().decodeToString() + "\n  "
        f.control.record = SessionControlRecord(prior.revision, bytes(original))
        f.afterOpen = {
            assertEquals(prior.revision + 1, f.control.record!!.revision)
            assertEquals(original, f.control.record!!.payload.copyForCodec().decodeToString())
        }
        value(f.coordinator.recoverAbort())
        assertEquals(1, f.aborts)
        assertIs<RetirementState.Complete>(f.state())
    }

    @Test fun cancellationReturnedFromConfirmationCasPreventsNativeOpen() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.seed()
        val proposal = value(f.coordinator.inspectPending())!!
        f.control.afterCas = { _, _ -> currentCoroutineContext().cancel() }
        val running = async { f.coordinator.requestAbort(proposal) }
        assertFailsWith<CancellationException> { running.await() }
        assertTrue(f.pending().abortRequested); assertEquals(0, f.opens)
    }

    @Test fun cancellationReturnedFromRecoveryOpenClosesWithoutAbortOrCompletion() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.seed(true)
        f.afterOpen = { currentCoroutineContext().cancel() }
        val running = async { f.coordinator.recoverAbort() }
        assertFailsWith<CancellationException> { running.await() }
        assertEquals(listOf("open", "close"), f.events)
        assertTrue(f.pending().abortRequested)
    }

    @Test fun liveCreateCancellationReturnedFromPendingCasPreventsNativeCommit() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val store = PlannedStore()
        f.control.afterCas = { _, _ -> currentCoroutineContext().cancel() }
        val running = async { commitCredentialCreate(f.control, store, 1, account()) { } }
        assertFailsWith<CancellationException> { running.await() }
        assertEquals(listOf("plan"), store.events)
        assertFalse(f.pending().abortRequested)
    }

    private class Fixture(private val dispatcher: CoroutineDispatcher) {
        val control = Control(); val boundary = SessionBoundary()
        val events = mutableListOf<String>()
        var status = CredentialCreateRecoveryStatus.PREPARED
        var opens = 0; var aborts = 0; var closes = 0
        var openFailure: FailureReason? = null; var inspectFailure: FailureReason? = null
        var abortFailure: FailureReason? = null; var closeFailure: FailureReason? = null
        var afterOpen: suspend () -> Unit = { }; var afterInspect: suspend () -> Unit = { }
        var afterAbort: suspend () -> Unit = { }; var afterClose: suspend () -> Unit = { }
        private val factory = CredentialCreateRecoveryFactory { selected ->
            events += "open"; opens++
            assertContentEquals(plan().copyForStorage().copyForCodec(), selected.copyForStorage().copyForCodec())
            openFailure?.let { return@CredentialCreateRecoveryFactory PortResult.Failure(it) }
            afterOpen()
            PortResult.Value(object : CredentialCreateRecoveryHandle {
                override suspend fun inspect(): PortResult<CredentialCreateRecoveryStatus> {
                    events += "inspect"; val observed = status; afterInspect()
                    return inspectFailure?.let { PortResult.Failure(it) } ?: PortResult.Value(observed)
                }
                override suspend fun abort(): PortResult<Unit> {
                    events += "abort"; aborts++
                    abortFailure?.let { return PortResult.Failure(it) }
                    status = CredentialCreateRecoveryStatus.ABORTED; afterAbort(); return PortResult.Value(Unit)
                }
                override suspend fun close(): PortResult<Unit> {
                    events += "close"; closes++; afterClose()
                    return closeFailure?.let { PortResult.Failure(it) } ?: PortResult.Value(Unit)
                }
            })
        }
        val coordinator = reopened()
        fun reopened() = CredentialCreateCoordinator(control, boundary, dispatcher, factory)
        fun seed(requested: Boolean = false) = control.set(RetirementState.PendingCreate(plan(), requested))
        fun state() = RetirementCodec.decode(control.record!!.payload)
        fun pending() = assertIs<RetirementState.PendingCreate>(state())
    }

    private class Control : SessionControlStore {
        var record: SessionControlRecord? = SessionControlRecord(1, RetirementCodec.encode(RetirementState.Idle))
        var reads = 0; var writes = 0
        var readFailure: FailureReason? = null
        val beforeFailures = mutableMapOf<Int, FailureReason>(); val afterFailures = mutableMapOf<Int, FailureReason>()
        var afterRead: suspend () -> Unit = { }
        var afterCas: suspend (Int, PrivateBytes) -> Unit = { _, _ -> }
        var receipt: ((SessionControlRecord, Long, PrivateBytes) -> SessionControlRecord)? = null
        override suspend fun read(): PortResult<SessionControlRecord?> {
            reads++; val observed = record?.let { SessionControlRecord(it.revision, PrivateBytes(it.payload.copyForCodec())) }
            afterRead(); return readFailure?.let { PortResult.Failure(it) } ?: PortResult.Value(observed)
        }
        override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> {
            val attempt = ++writes
            beforeFailures[attempt]?.let { return PortResult.Failure(it) }
            if (record?.revision != expectedRevision || expectedRevision == null) return PortResult.Failure(FailureReason.CONFLICT)
            val stored = SessionControlRecord(expectedRevision + 1, PrivateBytes(payload.copyForCodec()))
            record = stored; afterCas(attempt, payload)
            afterFailures[attempt]?.let { return PortResult.Failure(it) }
            return PortResult.Value(receipt?.invoke(stored, expectedRevision, payload) ?: stored)
        }
        fun set(state: RetirementState) { record = SessionControlRecord((record?.revision ?: 0) + 1, RetirementCodec.encode(state)) }
    }

    private class PlannedStore : PlannedCredentialCreateStore {
        val events = mutableListOf<String>(); var legacyCreates = 0
        var plan = plan(); var planFailure: FailureReason? = null; var commitFailure: FailureReason? = null
        var receipt: CredentialSnapshot? = null
        var afterPlan: suspend () -> Unit = { }; var beforeCommit: suspend () -> Unit = { }
        override suspend fun planCreate(expectedSlotRevision: Long, credentials: StoredCredentials): PortResult<CredentialCreatePlan> {
            events += "plan"; afterPlan(); return planFailure?.let { PortResult.Failure(it) } ?: PortResult.Value(plan)
        }
        override suspend fun commitPlannedCreate(plan: CredentialCreatePlan, credentials: StoredCredentials): PortResult<CredentialSnapshot> {
            events += "commit"; beforeCommit()
            return commitFailure?.let { PortResult.Failure(it) } ?: PortResult.Value(receipt ?: CredentialSnapshot(ID, 2, credentials))
        }
        override suspend fun state() = PortResult.Value(CredentialSlotState(1, null, null))
        override suspend fun read(scope: StorageScope): PortResult<CredentialSnapshot?> = fail("Orchestration decrypted credentials")
        override suspend fun create(expectedSlotRevision: Long, credentials: StoredCredentials): PortResult<CredentialSnapshot> { legacyCreates++; return PortResult.Failure(FailureReason.NOT_CONFIGURED) }
        override suspend fun replace(expected: CredentialSnapshot, credentials: StoredCredentials): PortResult<CredentialSnapshot> = fail("Unexpected refresh")
        override suspend fun attachDeviceSession(expected: CredentialSnapshot, deviceSessionId: SecretText): PortResult<CredentialSnapshot> = fail("Unexpected device bootstrap")
        override suspend fun retire(scope: StorageScope, credentialIncarnation: String): PortResult<Unit> = fail("Unplanned retirement")
    }

    companion object {
        private const val ID = "123e4567-e89b-12d3-a456-426614174000"
        private const val OTHER = "123e4567-e89b-12d3-a456-426614174001"
        private val ACCOUNT = StorageScope("test", ActorKind.ACCOUNT, "private-account")
        private fun account(scope: StorageScope = ACCOUNT, access: String = "private-access", refresh: String = "private-refresh", expiry: Long = 100) =
            StoredCredentials.Account(scope, SecretText(access), SecretText(refresh), expiry, SecretText(OTHER))
        private fun plan(id: String = ID, revision: Long = 1) = CredentialCreatePlan.create(
            CredentialCreatePlanRecord(revision, id, "a".repeat(64), "b".repeat(64), "c".repeat(64)))
        private fun bytes(raw: String) = PrivateBytes(raw.encodeToByteArray())
        private fun <T> value(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
        private fun failure(reason: FailureReason, result: PortResult<*>) { assertEquals(reason, assertIs<PortResult.Failure>(result).reason) }
    }
}
