package com.feedme.storage

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.kitchen.*
import com.feedme.mealflow.*
import com.feedme.mealflow.timers.*
import com.feedme.session.*
import com.feedme.sync.CommandPhase
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Actual runtime/three encrypted SQLite databases; synthetic credential/provider/JCA fixtures.
 * Scheduler/cancellation ports record requested effects, not Android delivery or permission proof.
 * Fault hooks lose application receipts AFTER actual SQLite commits; they are not VFS faults.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionCookingTimersStorageTest {
    @Test fun coherentTimerSnapshotPairsExactRevisionPinnedStepsAndCanonicalRowsWithoutWrites() = runTest { fixture { f ->
        f.start()
        val before = f.mappingCommits; val revision = f.snapshot().localRevision
        val observed = value(f.timers.observe(SESSION))
        assertEquals(revision, observed.localRevision); assertEquals(PLAN, observed.planId); assertEquals(VERSION, observed.recipeVersionId)
        assertEquals(listOf("step-one", "step-two"), observed.steps.map { it.stepId })
        val timer = observed.timers.single(); assertEquals("step-one", timer.stepId); assertEquals(60L, timer.durationSeconds)
        assertEquals("running", timer.status); assertEquals(1, observed.pendingCommandCount)
        assertEquals(before, f.mappingCommits); assertEquals(1, f.scheduled.size)
    } }
    @Test fun recalledTimerObservationRetainsRowsButNeverProjectsPinnedInstructions() = runTest { fixture { f ->
        f.start(); f.recalled = true; assertIs<PortResult.Failure>(f.kitchen.cooking.download(f.access.lease, SESSION))
        val observed = value(f.timers.observe(SESSION))
        assertEquals(CookingAvailability.RECALLED, observed.availability); assertTrue(observed.steps.isEmpty()); assertEquals(1, observed.timers.size)
    } }
    @Test fun timerUiOpenTickDurationAndBackNeverWriteAllocateOrSchedule() = runTest { fixture { f ->
        val ui = f.ui(); val writes = f.mappingCommits; val ids = f.flowIdCalls
        value(ui.setDurationText("60")); repeat(3) { value(ui.tick()) }
        assertEquals(0, value(ui.tick()).snapshot!!.timers.size)
        value(ui.back()); val reads = f.rt.controlReads; value(ui.tick())
        assertEquals(reads, f.rt.controlReads); assertEquals(writes, f.mappingCommits); assertEquals(ids, f.flowIdCalls)
        assertTrue(f.scheduled.isEmpty()); assertTrue(f.rt.cancelled.isEmpty()); assertFalse(ui.states.value.visible)
    } }
    @Test fun timerUiStartUsesActualCookingGateAndAtomicIntentBeforeScheduling() = runTest { fixture { f ->
        val ui = f.ui(); value(ui.setDurationText("60")); val before = f.snapshot().localRevision
        f.beforeSchedule = {
            val pending = assertNotNull(ui.states.value.pendingAction)
            assertEquals(before, pending.expectedLocalRevision)
            assertEquals(listOf(pending.commandId), f.snapshot().pendingCommandIds)
        }
        val result = value(ui.start())
        assertNull(result.pendingAction); assertEquals(2, f.flowIdCalls)
        assertTrue(result.snapshot!!.localRevision > before); assertTrue(result.snapshot!!.timers.single().alertAcknowledgedInThisOwner)
        assertFalse(f.cookingFlow.states.value.serverAcknowledged); assertEquals(1, f.scheduled.size)
    } }
    @Test fun oneExplicitTimerStartWaitsForReadWithoutBusyFlickerDuplicateOrRebasedInput() = runTest { fixture { f ->
        val ui = f.ui(); value(ui.setDurationText("60")); val writes = f.mappingCommits
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.rt.afterControlRead = { f.rt.afterControlRead = {}; entered.complete(Unit); release.await() }
        val read = async { ui.tick() }; entered.await()
        assertFalse(ui.states.value.busy); assertTrue(ui.states.value.canStartOrResume)
        val first = async { ui.start() }; runCurrent()
        assertFalse(first.isCompleted); assertTrue(ui.states.value.busy)
        assertEquals(0, f.flowIdCalls); assertEquals(writes, f.mappingCommits)
        assertIs<PortResult.Failure>(ui.start()); assertIs<PortResult.Failure>(ui.setDurationText("99"))
        val reads = f.rt.controlReads; value(ui.tick()); assertEquals(reads, f.rt.controlReads)
        release.complete(Unit); value(read.await()); value(first.await())
        assertEquals(2, f.flowIdCalls); assertEquals(1, f.scheduled.size)
        assertEquals(60L, ui.states.value.snapshot!!.timers.single().durationSeconds)
        assertEquals(1, f.snapshot().pendingCommandIds.size); assertNull(ui.states.value.pendingAction)
    } }
    @Test fun timerActionWaitingForReadRejectsNewRevisionInsteadOfRebasingIntent() = runTest { fixture { f ->
        val ui = f.ui(); value(ui.setDurationText("60")); val before = f.snapshot()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.rt.afterControlRead = { f.rt.afterControlRead = {}; entered.complete(Unit); release.await() }
        val read = async { ui.tick() }; entered.await(); val action = async { ui.start() }; runCurrent()
        value(f.kitchen.cooking.edit(f.access.lease, SESSION, before.localRevision, id(901), CookingEdit.MoveTo("step-two")))
        release.complete(Unit); value(read.await())
        assertEquals(FailureReason.CONFLICT, assertIs<PortResult.Failure>(action.await()).reason)
        assertEquals(0, f.flowIdCalls); assertTrue(f.scheduled.isEmpty()); assertNull(ui.states.value.pendingAction)
        assertEquals(listOf(id(901)), f.snapshot().pendingCommandIds)
        assertEquals("60", ui.states.value.durationText)
    } }
    @Test fun navigationCloseAndLeaseInvalidationFenceTimerActionWaitingForRead() = runTest {
        for (fence in listOf("back", "close", "lease")) fixture { f ->
            val ui = f.ui(); value(ui.setDurationText("60")); val writes = f.mappingCommits
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.rt.afterControlRead = { f.rt.afterControlRead = {}; entered.complete(Unit); release.await() }
            val read = async { ui.tick() }; entered.await(); val action = async { ui.start() }; runCurrent()
            when (fence) { "back" -> value(ui.back()); "close" -> value(ui.close()); else -> f.rt.boundary.clear() }
            assertFalse(ui.states.value.busy); assertFalse(ui.states.value.visible)
            release.complete(Unit); assertIs<PortResult.Failure>(read.await())
            assertEquals(FailureReason.STALE_SESSION, assertIs<PortResult.Failure>(action.await()).reason)
            assertEquals(0, f.flowIdCalls); assertEquals(writes, f.mappingCommits); assertTrue(f.scheduled.isEmpty())
        }
    }
    @Test fun cancelledWaitingTimerActionReleasesOnlyItsReservationAndLeavesReadAlive() = runTest { fixture { f ->
        val ui = f.ui(); value(ui.setDurationText("60")); val writes = f.mappingCommits
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.rt.afterControlRead = { f.rt.afterControlRead = {}; entered.complete(Unit); release.await() }
        val read = async { ui.tick() }; entered.await(); val action = async { ui.start() }; runCurrent()
        action.cancel(); assertFailsWith<CancellationException> { action.await() }
        assertFalse(read.isCompleted); assertFalse(ui.states.value.busy)
        assertEquals(0, f.flowIdCalls); assertEquals(writes, f.mappingCommits); assertTrue(f.scheduled.isEmpty())
        release.complete(Unit); value(read.await()); value(ui.start())
        assertEquals(2, f.flowIdCalls); assertEquals(1, f.scheduled.size)
    } }
    @Test fun failedOrCancelledPredecessorReadCannotAdmitTimerActionFromCachedObservation() = runTest {
        for (cancel in listOf(false, true)) fixture { f ->
            val ui = f.ui(); value(ui.setDurationText("60")); val writes = f.mappingCommits
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.rt.afterControlRead = {
                f.rt.afterControlRead = {}; entered.complete(Unit); release.await()
                if (!cancel) error("Synthetic read failure before any mutation")
            }
            val read = async { ui.tick() }; entered.await(); val action = async { ui.start() }; runCurrent()
            if (cancel) { read.cancel(); assertFailsWith<CancellationException> { read.await() } }
            else { release.complete(Unit); assertIs<PortResult.Failure>(read.await()) }
            assertIs<PortResult.Failure>(action.await()); release.complete(Unit)
            assertEquals(0, f.flowIdCalls); assertEquals(writes, f.mappingCommits); assertTrue(f.scheduled.isEmpty())
            assertNull(ui.states.value.pendingAction)
        }
    }
    @Test fun activeTimerMutationStillRejectsSecondActionAndCoalescesReadTicks() = runTest { fixture { f ->
        val ui = f.ui(); value(ui.setDurationText("60"))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var once = true
        f.afterMappingCommit = { if (once) { once = false; f.rt.afterControlRead = {
            f.rt.afterControlRead = {}; entered.complete(Unit); release.await()
        } } }
        val action = async { ui.start() }; entered.await(); val ids = f.flowIdCalls; val writes = f.mappingCommits
        assertIs<PortResult.Failure>(ui.start()); assertEquals(ids, f.flowIdCalls)
        val reads = f.rt.controlReads; value(ui.tick()); assertEquals(reads, f.rt.controlReads); assertEquals(writes, f.mappingCommits)
        release.complete(Unit); value(action.await())
        assertEquals(2, f.flowIdCalls); assertEquals(1, f.scheduled.size); assertNull(ui.states.value.pendingAction)
    } }
    @Test fun timerUiPauseResumeResetAndCancelRetainSiblingAndUseDistinctExplicitCommands() = runTest { fixture { f ->
        val ui = f.ui(); value(ui.setDurationText("60")); value(ui.start()); val first = ui.states.value.snapshot!!.timers.single().timerId
        value(ui.start()); val sibling = ui.states.value.snapshot!!.timers.last().timerId
        value(ui.pause(first)); value(ui.resume(first)); value(ui.reset(first)); value(ui.cancel(first))
        val snapshot = assertNotNull(ui.states.value.snapshot)
        assertEquals(listOf(sibling), f.snapshot().progress.timers.map { it.timerId.value })
        assertEquals(listOf(sibling), snapshot.timers.filter { it.status != null }.map { it.timerId })
        val removed = snapshot.timers.single { it.timerId == first }
        assertNull(removed.status); assertEquals(CookingTimerAlertPhase.QUIET, removed.alertPhase)
        assertFalse(removed.alertAcknowledgedInThisOwner); assertEquals(CookingTimerDeliveryPhase.NOT_INSTALLED, removed.deliveryPhase)
        assertEquals(6, snapshot.pendingCommandCount)
        assertEquals(3, f.scheduled.size); assertEquals(2, f.rt.cancelled.size)
        assertEquals(6, f.snapshot().pendingCommandIds.distinct().size)
    } }
    @Test fun invalidTimerUiDurationDoesNotAllocateAnIdentityOrAttemptMutation() = runTest { fixture { f ->
        val ui = f.ui(); val writes = f.mappingCommits
        for (text in listOf("", "-1", "1.2", "86401")) {
            value(ui.setDurationText(text)); assertIs<PortResult.Failure>(ui.start()); assertNull(ui.states.value.pendingAction)
        }
        assertEquals(0, f.flowIdCalls); assertEquals(writes, f.mappingCommits); assertTrue(f.scheduled.isEmpty())
    } }
    @Test fun timerUiLostDomainReplyRetainsOriginalActionThroughTicksBackAndExactCleanup() = runTest { fixture { f ->
        val ui = f.ui(); value(ui.setDurationText("60")); f.loseMappingCommit = f.mappingCommits + 1
        assertIs<PortResult.Failure>(ui.start()); val original = assertNotNull(ui.states.value.pendingAction)
        assertEquals(listOf(original.commandId), f.snapshot().pendingCommandIds); assertTrue(f.scheduled.isEmpty())
        val ids = f.flowIdCalls; value(ui.tick()); value(ui.back()); value(ui.open(SESSION))
        assertEquals(original.commandId, ui.states.value.pendingAction!!.commandId)
        assertIs<PortResult.Failure>(ui.start()); assertEquals(ids, f.flowIdCalls)
        value(ui.cancelAlert(original.timerId))
        assertTrue(ui.states.value.pendingAction!!.cancellationAcknowledged)
        assertEquals(original.commandId, ui.states.value.pendingAction!!.commandId); assertTrue(f.scheduled.isEmpty())
        assertEquals(CookingTimerFlowIssue.ALERT_CLEANUP_ACKNOWLEDGED, ui.states.value.issue)
        assertFalse(ui.states.value.canStartOrResume)
    } }
    @Test fun timerUiUnknownSchedulerAndCancellationRepliesNeverBecomeNewInstalls() = runTest { fixture { f ->
        val ui = f.ui(); value(ui.setDurationText("60")); f.scheduleResult = PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
        // Failed installation immediately attempts compensation. Keep that first exact cleanup
        // unknown too, so the later explicit cleanup really encounters an unresolved ticket.
        f.rt.cancel = { PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
        assertIs<PortResult.Failure>(ui.start()); val pending = assertNotNull(ui.states.value.pendingAction)
        assertEquals(1, f.scheduled.size); assertFalse(pending.cancellationAcknowledged)
        assertIs<PortResult.Failure>(ui.cancelAlert(pending.timerId)); assertFalse(ui.states.value.pendingAction!!.cancellationAcknowledged)
        f.rt.cancel = { PortResult.Value(Unit) }; value(ui.cancelAlert(pending.timerId))
        assertTrue(ui.states.value.pendingAction!!.cancellationAcknowledged); assertEquals(1, f.scheduled.size)
        assertEquals(pending.commandId, ui.states.value.pendingAction!!.commandId)
        assertFalse(ui.states.value.canStartOrResume)
    } }
    @Test fun timerExpiringAfterActualDomainCommitCannotBecomeInstalledOrLoseItsOriginalAction() = runTest { fixture { f ->
        val ui = f.ui(); value(ui.setDurationText("3"))
        val originalRevision = f.snapshot().localRevision
        val firstCommit = f.mappingCommits + 1
        f.afterMappingCommit = { count -> if (count == firstCommit) f.advance(3_000) }
        assertEquals(FailureReason.CONFLICT, assertIs<PortResult.Failure>(ui.start()).reason)
        val pending = assertNotNull(ui.states.value.pendingAction)
        assertEquals(originalRevision, pending.expectedLocalRevision)
        val canonical = f.snapshot()
        assertTrue(canonical.localRevision > originalRevision)
        assertEquals(listOf(pending.commandId), canonical.pendingCommandIds)
        assertEquals(pending.timerId, canonical.progress.timers.single().timerId.value)
        assertEquals("running", canonical.progress.timers.single().status)
        val observed = value(f.timers.observe(SESSION)).timers.single()
        assertEquals(CookingTimerTiming.DUE, observed.timing!!.timing)
        assertEquals(CookingTimerAlertPhase.MAPPED, observed.alertPhase)
        assertFalse(observed.alertAcknowledgedInThisOwner); assertTrue(f.scheduled.isEmpty())
        val ids = f.flowIdCalls
        value(ui.tick()); assertIs<PortResult.Failure>(ui.start())
        assertEquals(ids, f.flowIdCalls); assertEquals(pending.commandId, ui.states.value.pendingAction!!.commandId)
        assertTrue(f.scheduled.isEmpty())
        value(ui.cancelAlert(pending.timerId))
        assertEquals(pending.commandId, ui.states.value.pendingAction!!.commandId)
        assertTrue(ui.states.value.pendingAction!!.cancellationAcknowledged)
        assertEquals(listOf(pending.commandId), f.snapshot().pendingCommandIds)
        assertFalse(ui.states.value.canStartOrResume); assertTrue(f.scheduled.isEmpty())
    } }
    @Test fun timerUiBackOrCloseAfterActualDomainCommitCannotInstallFromLateReturn() = runTest {
        for (target in listOf("timer-back", "timer-close", "cooking-back", "cooking-close")) fixture { f ->
            val ui = f.ui(); value(ui.setDurationText("60")); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var once = true
            f.afterMappingCommit = { if (once) { once = false; f.rt.afterControlRead = {
                f.rt.afterControlRead = {}; entered.complete(Unit); release.await()
            } } }
            val attempt = async { ui.start() }; entered.await()
            when (target) {
                "timer-back" -> value(ui.back())
                "timer-close" -> value(ui.close())
                "cooking-back" -> value(f.cookingFlow.backToRecipe())
                else -> value(f.cookingFlow.close())
            }
            release.complete(Unit); assertIs<PortResult.Failure>(attempt.await())
            assertEquals(1, f.snapshot().pendingCommandIds.size); assertTrue(f.scheduled.isEmpty())
            if (target == "timer-back") assertNotNull(ui.states.value.pendingAction)
        }
    }
    @Test fun cancelledTimerUiMutationRetainsCommittedOriginalWithoutLateNativeEffects() = runTest { fixture { f ->
        val ui = f.ui(); value(ui.setDurationText("60")); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var once = true
        f.afterMappingCommit = { if (once) { once = false; f.rt.afterControlRead = {
            f.rt.afterControlRead = {}; entered.complete(Unit); release.await()
        } } }
        val attempt = async { ui.start() }; entered.await(); attempt.cancel(); release.complete(Unit)
        assertFailsWith<CancellationException> { attempt.await() }
        assertEquals(1, f.snapshot().pendingCommandIds.size); assertNotNull(ui.states.value.pendingAction); assertTrue(f.scheduled.isEmpty())
    } }
    @Test fun timerUiClosedOwnerCannotBeReplacedToForgetItsUnknownAction() = runTest { fixture { f ->
        val ui = f.ui(); value(ui.setDurationText("60")); f.loseMappingCommit = f.mappingCommits + 1
        assertIs<PortResult.Failure>(ui.start()); value(ui.close())
        assertIs<PortResult.Failure>(CookingTimerFlowController.create(f.access, f.rt.boundary, f.dispatcher,
            f.timers, f.cookingFlow, MealOperationIds { fail("replacement allocated ID") }))
        assertTrue(f.scheduled.isEmpty()); assertNotNull(f.rt.runtime.currentAccess())
    } }
    @Test fun timerUiMismatchedActualControllerStoreIsRejectedBeforeClaimOrEffects() = runTest { fixture { first -> fixture { second ->
        second.ui()
        assertEquals(FailureReason.STALE_SESSION, assertIs<PortResult.Failure>(CookingTimerFlowController.create(first.access,
            first.rt.boundary, first.dispatcher, first.timers, second.cookingFlow, MealOperationIds { fail("foreign ID") })).reason)
        assertTrue(first.scheduled.isEmpty()); assertTrue(second.scheduled.isEmpty())
        first.ui() // Failed foreign pairing did not consume the genuine facade claim.
    } } }
    @Test fun timerUiFreshContextRejectsChangedLocalRevisionWithoutMutationOrIds() = runTest { fixture { f ->
        val ui = f.ui(); value(ui.setDurationText("60"))
        value(f.kitchen.cooking.edit(f.access.lease, SESSION, f.snapshot().localRevision, id(900), CookingEdit.MoveTo("step-two")))
        val writes = f.mappingCommits
        assertIs<PortResult.Failure>(ui.start()); assertEquals(writes, f.mappingCommits); assertEquals(0, f.flowIdCalls)
        assertNull(ui.states.value.pendingAction); assertTrue(f.scheduled.isEmpty())
        value(ui.tick()); value(ui.start()); assertEquals(1, f.scheduled.size)
    } }
    @Test fun timerUiInvalidationSynchronouslyRedactsPrivateProjectionAndDeniesActions() = runTest { fixture { f ->
        val ui = f.ui(); value(ui.setDurationText("60")); f.rt.boundary.clear()
        assertNull(ui.states.value.snapshot); assertNull(ui.states.value.pendingAction); assertFalse(ui.states.value.visible)
        assertIs<PortResult.Failure>(ui.tick()); assertIs<PortResult.Failure>(ui.start()); assertEquals(0, f.flowIdCalls)
    } }
    @Test fun actualPendingPreferenceDraftBlocksTimerStartBeforeIdentityAllocation() = runTest { fixture { f ->
        val ui = f.ui(); value(ui.setDurationText("60")); val preferences = f.preferences()
        try {
            value(preferences.editPreferences(WireDocument.parse("""{"hardExcludedIngredientIds":[]}""")))
            val writes = f.mappingCommits
            assertIs<PortResult.Failure>(ui.start()); assertEquals(0, f.flowIdCalls)
            assertEquals(writes, f.mappingCommits); assertTrue(f.scheduled.isEmpty()); assertNull(ui.states.value.pendingAction)
        } finally { preferences.close() }
    } }
    @Test fun preferenceChangeAfterDomainCommitIsRecheckedBeforeAnyTimerSchedule() = runTest { fixture { f ->
        val ui = f.ui(); value(ui.setDurationText("60")); val preferences = f.preferences(); var once = true
        f.afterMappingCommit = { if (once) { once = false; f.rt.afterControlRead = {
            f.rt.afterControlRead = {}
            value(preferences.editPreferences(WireDocument.parse("""{"hardExcludedIngredientIds":[]}""")))
        } } }
        try {
            assertIs<PortResult.Failure>(ui.start()); assertNotNull(ui.states.value.pendingAction)
            assertEquals(1, f.snapshot().pendingCommandIds.size); assertTrue(f.scheduled.isEmpty())
        } finally { preferences.close() }
    } }
    @Test fun actualPendingPreferenceDraftStillPermitsExactPauseResetCancelSafeStops() = runTest { fixture { f ->
        val ui = f.ui(); value(ui.setDurationText("60")); value(ui.start()); val timer = ui.states.value.snapshot!!.timers.single().timerId
        val preferences = f.preferences()
        try {
            value(preferences.editPreferences(WireDocument.parse("""{"hardExcludedIngredientIds":[]}""")))
            value(ui.pause(timer)); value(ui.reset(timer)); value(ui.cancel(timer))
            assertTrue(f.snapshot().progress.timers.isEmpty())
            val removed = ui.states.value.snapshot!!.timers.single()
            assertEquals(timer, removed.timerId); assertNull(removed.status)
            assertEquals(CookingTimerAlertPhase.QUIET, removed.alertPhase); assertFalse(removed.alertAcknowledgedInThisOwner)
            assertEquals(CookingTimerDeliveryPhase.NOT_INSTALLED, removed.deliveryPhase)
            assertEquals(1, f.scheduled.size); assertEquals(1, f.rt.cancelled.size)
        } finally { preferences.close() }
    } }
    @Test fun policyDeniesBeforeBindingAndRejectsMismatchedRuntimePolicy() = runTest { fixture { f ->
        val other = CookingTimerExecutionPolicy()
        assertFalse(value(other.allowed(f.access.scope, f.access.originBinding, "untrusted", ticket(99))))
        assertIs<PortResult.Failure>(SessionCookingTimers.create(f.rt.runtime, f.access, f.kitchen, f.rt.boundary, f.dispatcher, f.clock, f.scheduler, other))
        assertTrue(f.scheduled.isEmpty()); assertTrue(f.rt.cancelled.isEmpty())
    } }
    @Test fun atomicCookingIntentAndExactTicketMappingPrecedeSchedulerWithRealReservedLedger() = runTest { fixture { f ->
        f.beforeSchedule = { native ->
            assertEquals("MAPPED", f.slot("phase"))
            assertEquals(native.id, f.slot("ticket"))
            val work = value(f.rt.workControl.read())!!.payload.copyForCodec().decodeToString()
            assertTrue(work.contains("RESERVED")); assertTrue(work.contains(native.id))
            val body = value(f.access.store.read(f.access.scope, RecordKey("feedme.kitchen.cook.action-body", id(10))))!!.payload.copyForCodec().decodeToString()
            assertTrue(body.contains("timers")); assertFalse(body.contains(native.id)); assertFalse(body.contains("continuity"))
        }
        val view = f.start(); assertTrue(view.single().alertAcknowledgedInThisOwner)
        assertEquals("ARMED", f.slot("phase"))
        assertEquals(listOf(id(10)), f.snapshot().pendingCommandIds)
    } }
    @Test fun onlyCurrentDueDesiredTicketCanProduceRuntimeLocalEffect() = runTest { fixture { f ->
        f.start(); val native = f.scheduled.single(); var effects = 0
        assertIs<PortResult.Failure>(f.timers.runLocalEffect(native) { effects++; PortResult.Value(Unit) })
        f.advance(60_000); value(f.timers.runLocalEffect(native) { effects++; PortResult.Value(Unit) })
        assertEquals(1, effects)
        assertIs<PortResult.Failure>(f.timers.runLocalEffect(ticket(999)) { effects++; PortResult.Value(Unit) }); assertEquals(1, effects)
    } }
    @Test fun clockJumpUnknownContinuityAndRecallRemainFailClosed() = runTest { fixture { f ->
        f.start(); val native = f.scheduled.single(); f.advance(60_000); f.epoch += 10_000
        assertIs<PortResult.Failure>(f.timers.runLocalEffect(native) { fail("clock jump cannot deliver") })
        f.epoch -= 10_000; f.continuity = null
        assertIs<PortResult.Failure>(f.timers.runLocalEffect(native) { fail("missing continuity cannot deliver") })
        f.continuity = "synthetic-continuity"; f.recalled = true
        assertIs<PortResult.Failure>(f.kitchen.cooking.download(f.access.lease, SESSION))
        assertIs<PortResult.Failure>(f.timers.runLocalEffect(native) { fail("known recall cannot deliver") })
        value(f.timers.cancelAlert(SESSION, TIMER)); assertEquals(native.id, f.rt.cancelled.last().id)
    } }
    @Test fun pausePersistsNonrunningGenerationBeforeExactCancellationAndResumeUsesRemaining() = runTest { fixture { f ->
        f.start(); val original = f.scheduled.single(); f.advance(10_500)
        f.rt.cancel = { native ->
            assertEquals(original.id, native.id); assertEquals("paused", f.snapshot().progress.timers.single().status)
            assertEquals("CANCELLING", f.slot("phase"))
            assertFalse(value(f.policy.allowed(f.access.scope, f.access.originBinding, "cook-timer:$SESSION:$TIMER", native)))
            PortResult.Value(Unit)
        }
        f.change(CookingTimerAction.Pause(TIMER), 11); assertEquals(1, f.scheduled.size)
        f.rt.cancel = { PortResult.Value(Unit) }
        f.change(CookingTimerAction.Resume(TIMER), 12)
        assertEquals(2, f.scheduled.size); assertNotEquals(original.id, f.scheduled.last().id)
        assertEquals(50_000L, f.deadlines.last() - f.epoch)
    } }
    @Test fun resetAndCancelPreserveSiblingAndDoNotScheduleReplacement() = runTest { fixture { f ->
        f.start(); f.change(CookingTimerAction.Start(OTHER, "step-two", 120), 11)
        val sibling = f.scheduled.last().id
        f.change(CookingTimerAction.Reset(TIMER), 12); f.change(CookingTimerAction.Cancel(TIMER), 13)
        assertEquals(2, f.scheduled.size); assertEquals(listOf(OTHER), f.snapshot().progress.timers.map { it.timerId.value })
        assertFalse(f.rt.cancelled.any { it.id == sibling })
    } }
    @Test fun successfulReadBracketRacesRecheckTheExactSiblingBeforeOneWorkAcknowledgementAndEffect() = runTest {
        for (afterJournal in listOf(false, true)) fixture { f ->
            f.start(); f.change(CookingTimerAction.Start(OTHER, "step-two", 120), 11); f.advance(120_000)
            val native = f.scheduled.last(); val costs = f.readCosts()
            // One independent control check precedes the policy. Measured real repository read
            // costs locate either its snapshot/metadata bracket or its post-journal bracket.
            val trigger = 1 + if (afterJournal) costs.load + costs.eligibility + 1 else costs.snapshot + 1
            val before = f.snapshot().localRevision; val workWrites = f.rt.workWrites
            var injections = 0; var reads = 0
            f.rt.afterControlRead = {
                if (++reads == trigger) {
                    f.rt.afterControlRead = {}
                    f.reackMappingFromPeer(); injections++
                }
            }
            var effects = 0
            value(f.timers.runLocalEffect(native) { effects++; PortResult.Value(Unit) })
            assertEquals(1, injections); assertEquals(before + 1, f.snapshot().localRevision)
            assertEquals(1, effects); assertEquals(workWrites + 1, f.rt.workWrites)
            assertTrue(value(f.timers.inspect(SESSION)).single { it.timerId == OTHER }.alertAcknowledgedInThisOwner)
            assertEquals(2, f.scheduled.size); assertTrue(f.rt.cancelled.isEmpty())
        }
    }
    @Test fun repeatedSuccessfulReadRevisionRacesStopAtTheBoundWithoutWorkWritesOrEffects() = runTest { fixture { f ->
        f.start(); f.advance(60_000); val costs = f.readCosts(); val workWrites = f.rt.workWrites
        val before = f.snapshot().localRevision
        var reads = 0; var injections = 0; var insidePeer = false
        f.rt.afterControlRead = {
            if (!insidePeer) {
                reads++
                if (reads == 1 + injections * costs.load + costs.snapshot + 1) {
                    insidePeer = true
                    try { f.reackMappingFromPeer(); injections++ } finally { insidePeer = false }
                }
            }
        }
        var effects = 0
        val result = f.timers.runLocalEffect(f.scheduled.single()) { effects++; PortResult.Value(Unit) }
        f.rt.afterControlRead = {}
        assertEquals(FailureReason.CONFLICT, assertIs<PortResult.Failure>(result).reason)
        assertEquals(3, injections); assertEquals(before + 3, f.snapshot().localRevision)
        assertEquals(0, effects); assertEquals(workWrites, f.rt.workWrites)
    } }
    @Test fun readRaceNeverReauthorizesChangedDesiredProofRecallOrAnInvalidatedLease() = runTest {
        for (change in listOf("generation", "recall", "lease")) fixture { f ->
            f.start(); f.advance(60_000); val costs = f.readCosts(); val workWrites = f.rt.workWrites
            var reads = 0; var injections = 0
            f.rt.afterControlRead = {
                if (++reads == 1 + costs.snapshot + 1) {
                    f.rt.afterControlRead = {}
                    val transform: (String) -> String = if (change == "generation") {
                        { raw -> raw.replace("\"generation\":1", "\"generation\":2") }
                    } else { { raw -> raw } }
                    f.reackMappingFromPeer(transform)
                    if (change == "recall") {
                        f.recalled = true
                        assertIs<PortResult.Failure>(f.peer().cooking.download(f.access.lease, SESSION))
                    }
                    if (change == "lease") f.rt.boundary.clear()
                    injections++
                }
            }
            var effects = 0
            assertIs<PortResult.Failure>(f.timers.runLocalEffect(f.scheduled.single()) { effects++; PortResult.Value(Unit) })
            assertEquals(1, injections); assertEquals(0, effects); assertEquals(workWrites, f.rt.workWrites)
        }
    }
    @Test fun genericReadFailuresAndUnknownWorkCommitAcknowledgementsAreNeverAutomaticallyRetried() = runTest {
        for (reason in listOf(FailureReason.CONFLICT, FailureReason.STORAGE_FAILURE, FailureReason.OUTCOME_UNKNOWN)) fixture { f ->
            f.start(); f.advance(60_000); val workWrites = f.rt.workWrites
            var reads = 0; var effects = 0
            f.rt.controlReadOverride = {
                // Permit the runtime's independent barrier, fail the first policy data read.
                if (++reads == 2) PortResult.Failure(reason) else f.rt.control.read()
            }
            assertEquals(reason, assertIs<PortResult.Failure>(f.timers.runLocalEffect(f.scheduled.single()) {
                effects++; PortResult.Value(Unit)
            }).reason)
            f.rt.controlReadOverride = null
            assertEquals(2, reads); assertEquals(0, effects); assertEquals(workWrites, f.rt.workWrites)
        }
        fixture { f ->
            f.start(); f.advance(60_000); val workWrites = f.rt.workWrites
            var acknowledgements = 0; var effects = 0
            f.rt.afterWorkWrite = { _, _ -> acknowledgements++; FailureReason.OUTCOME_UNKNOWN }
            assertEquals(FailureReason.OUTCOME_UNKNOWN, assertIs<PortResult.Failure>(f.timers.runLocalEffect(f.scheduled.single()) {
                effects++; PortResult.Value(Unit)
            }).reason)
            assertEquals(1, acknowledgements); assertEquals(workWrites + 1, f.rt.workWrites); assertEquals(0, effects)
        }
    }
    @Test fun lostDomainAcknowledgementCannotInstallAndExplicitReconcileNeverRestartsIt() = runTest { fixture { f ->
        f.loseMappingCommit = 1
        assertIs<PortResult.Failure>(f.startResult()); assertTrue(f.scheduled.isEmpty())
        assertEquals(listOf(id(10)), f.snapshot().pendingCommandIds); assertEquals("DESIRED", f.slot("phase"))
        value(f.timers.cancelAlert(SESSION, TIMER)); assertTrue(f.scheduled.isEmpty()); assertEquals("QUIET", f.slot("phase"))
        assertFalse(value(f.timers.inspect(SESSION)).single().alertAcknowledgedInThisOwner)
    } }
    @Test fun lostExactMappingAcknowledgementNeverCallsSchedulerAndKeepsExactCleanupIdentity() = runTest { fixture { f ->
        f.loseMappingCommit = 2
        assertIs<PortResult.Failure>(f.startResult()); assertTrue(f.scheduled.isEmpty())
        val captured = f.slot("ticket")
        assertEquals(captured, f.rt.cancelled.single().id)
        value(f.timers.cancelAlert(SESSION, TIMER)); assertTrue(f.scheduled.isEmpty())
        assertEquals("QUIET", f.slot("phase"))
    } }
    @Test fun lostArmedAcknowledgementCannotPromoteVisibleMappingIntoCallbackPermission() = runTest { fixture { f ->
        f.loseMappingCommit = 3
        assertIs<PortResult.Failure>(f.startResult()); assertEquals(1, f.scheduled.size)
        assertEquals("ARMED", f.slot("phase")); f.advance(60_000)
        assertIs<PortResult.Failure>(f.timers.runLocalEffect(f.scheduled.single()) { fail("unacknowledged mapping") })
        value(f.timers.cancelAlert(SESSION, TIMER)); assertEquals(f.scheduled.single().id, f.rt.cancelled.last().id)
    } }
    @Test fun schedulerFailureAndRetryNeverAllocateReplacementTicket() = runTest { fixture { f ->
        f.scheduleResult = PortResult.Failure(FailureReason.UNAVAILABLE)
        assertIs<PortResult.Failure>(f.startResult()); assertEquals(1, f.scheduled.size)
        assertIs<PortResult.Failure>(f.changeResult(CookingTimerAction.Pause(TIMER), 11))
        value(f.timers.cancelAlert(SESSION, TIMER)); assertEquals(1, f.scheduled.size)
        assertTrue(f.rt.cancelled.all { it.id == f.scheduled.single().id })
    } }
    @Test fun unknownCancellationKeepsSameTicketAndBlocksReplacementUntilFreshAck() = runTest { fixture { f ->
        f.start(); val native = f.scheduled.single(); f.rt.cancel = { PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
        assertIs<PortResult.Failure>(f.changeResult(CookingTimerAction.Pause(TIMER), 11))
        assertIs<PortResult.Failure>(f.timers.cancelAlert(SESSION, TIMER)); assertEquals("CANCELLING", f.slot("phase"))
        assertIs<PortResult.Failure>(f.changeResult(CookingTimerAction.Resume(TIMER), 12)); assertEquals(1, f.scheduled.size)
        f.rt.cancel = { PortResult.Value(Unit) }; value(f.timers.cancelAlert(SESSION, TIMER))
        assertTrue(f.rt.cancelled.all { it.id == native.id }); assertEquals("QUIET", f.slot("phase"))
    } }
    @Test fun invalidationDuringSuspendedNativeInstallCannotPublishOrDeliverLateSuccess() = runTest { fixture { f ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.beforeSchedule = { entered.complete(Unit); release.await() }
        val running = async { f.startResult() }; entered.await(); f.rt.boundary.clear(); release.complete(Unit)
        assertIs<PortResult.Failure>(running.await()); assertIs<PortResult.Failure>(f.timers.inspect(SESSION))
        assertIs<PortResult.Failure>(f.timers.runLocalEffect(f.scheduled.single()) { fail("late stale effect") })
    } }
    @Test fun facadeCloseAtActualMappingCommitBoundaryPreventsSchedulingAfterItsLateReturn() = runTest { fixture { f ->
        f.beforeMappingCommit = { count -> if (count == 2) f.timers.close() }
        assertIs<PortResult.Failure>(f.startResult()); assertTrue(f.scheduled.isEmpty())
    } }
    @Test fun unresolvedQueueReceiptBlocksNewTimerMutationsAndDoesNotGuessAnEtag() = runTest { fixture { f ->
        f.start(); value(f.kitchen.cooking.materializeNext(f.access.lease, SESSION)); f.rejectPatch = true
        assertEquals(CommandPhase.NEEDS_RESOLUTION, value(f.kitchen.commands.dispatchNext(f.access.lease))!!.phase)
        val before = f.snapshot().localRevision
        assertIs<PortResult.Failure>(f.changeResult(CookingTimerAction.Start(OTHER, "step-two", 120), 11))
        assertEquals(before, f.snapshot().localRevision); assertEquals(1, f.scheduled.size)
    } }
    @Test fun mismatchedCurrentTimerBytesDenyCallbackAndPermitOnlyExactCleanup() = runTest { fixture { f ->
        f.start(); val native = f.scheduled.single(); val current = f.snapshot()
        value(f.kitchen.cooking.edit(f.access.lease, SESSION, current.localRevision, id(11), CookingEdit.ReplaceTimers(emptyList())))
        f.advance(60_000); assertIs<PortResult.Failure>(f.timers.runLocalEffect(native) { fail("changed domain") })
        value(f.timers.cancelAlert(SESSION, TIMER)); assertEquals(native.id, f.rt.cancelled.last().id)
    } }
    @Test fun materializedActionWithMissingJournalCannotBecomeUnmaterializedPermission() = runTest { fixture { f ->
        f.start(); value(f.kitchen.cooking.materializeNext(f.access.lease, SESSION))
        val commandKeys = listOf(RecordKey("feedme.command.metadata", id(10)), RecordKey("feedme.command.request", id(10)))
        for (key in commandKeys) value(f.access.store.read(f.access.scope, key))?.let { record ->
            value(f.access.store.commit(f.access.scope, listOf(StoreMutation.Delete(key, record.revision))))
        }
        assertIs<PortResult.Failure>(f.changeResult(CookingTimerAction.Start(OTHER, "step-two", 120), 11))
        f.advance(60_000)
        assertIs<PortResult.Failure>(f.timers.runLocalEffect(f.scheduled.single()) { fail("missing queue cannot authorize") })
        assertEquals(1, f.scheduled.size)
    } }
    @Test fun recallOrNewPauseDuringSuspendedWorkAcknowledgementCannotProduceAnOldEffect() = runTest {
        for (recall in listOf(false, true)) fixture { f ->
            f.start(); f.advance(60_000)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var first = true
            f.rt.beforeWorkWrite = { _, _ -> if (first) { first = false; entered.complete(Unit); release.await() }; null }
            var effects = 0
            val delivery = async { f.timers.runLocalEffect(f.scheduled.single()) { effects++; PortResult.Value(Unit) } }
            entered.await()
            val changed = if (recall) {
                f.recalled = true; assertIs<PortResult.Failure>(f.kitchen.cooking.download(f.access.lease, SESSION)); null
            } else {
                val committed = CompletableDeferred<Unit>()
                f.afterMappingCommit = { count -> if (count == 4) committed.complete(Unit) }
                async { f.changeResult(CookingTimerAction.Pause(TIMER), 11) }.also { committed.await() }
            }
            release.complete(Unit)
            assertIs<PortResult.Failure>(delivery.await()); changed?.await()?.let { assertIs<PortResult.Value<*>>(it) }
            assertEquals(0, effects)
        }
    }
    @Test fun sameScopeDifferentStoreOrBoundaryCannotBindTheRuntimeTimerPolicy() = runTest { fixture { f ->
        val delegated = object : PrivateStateStore by f.access.store {}
        val foreign = PrivateKitchenSession(f.access.scope, delegated, f.rt.boundary, f.dispatcher, EpochClock { f.epoch },
            object : AccountTransport { override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> = error("No transport") }, f.access.originBinding)
        assertFalse(foreign.matchesComposition(f.access.scope, f.access.store, f.rt.boundary, f.access.originBinding))
        assertIs<PortResult.Failure>(SessionCookingTimers.create(f.rt.runtime, f.access, foreign, f.rt.boundary, f.dispatcher, f.clock, f.scheduler, f.policy))
        assertTrue(f.scheduled.isEmpty())
    } }
    @Test fun changedSameTicketGenerationCannotBeReportedOrDeliveredAsAcknowledged() = runTest { fixture { f ->
        f.start(); val old = value(f.kitchen.cooking.readTimerMetadata(f.access.lease, SESSION))!!
        val changed = old.record.payload.copyForCodec().decodeToString().replace("\"generation\":1", "\"generation\":2")
        value(f.kitchen.cooking.acknowledgeTimerMetadata(f.access.lease, SESSION, f.snapshot().localRevision, old.record, PrivateBytes(changed.encodeToByteArray())))
        assertFalse(value(f.timers.inspect(SESSION)).single().alertAcknowledgedInThisOwner)
        f.advance(60_000); assertIs<PortResult.Failure>(f.timers.runLocalEffect(f.scheduled.single()) { fail("changed desired generation") })
    } }
    @Test fun canonicalTimerWithoutPrivateMappingRemainsVisibleButNeverGainsAlertAuthority() = runTest { fixture { f ->
        val before = f.snapshot()
        val timer = WireDocument.parse("""{"timerId":"$TIMER","stepId":"step-one","status":"running","durationSeconds":60,"endAt":"2027-01-15T08:01:00Z"}""")
        value(f.kitchen.cooking.edit(f.access.lease, SESSION, before.localRevision, id(10), CookingEdit.ReplaceTimers(listOf(timer))))
        val view = value(f.timers.inspect(SESSION)).single()
        assertEquals(TIMER, view.timerId); assertEquals(0L, view.generation)
        assertEquals(CookingTimerTiming.UNCERTAIN, view.timing!!.timing); assertFalse(view.alertAcknowledgedInThisOwner)
        assertNull(value(f.kitchen.cooking.readTimerMetadata(f.access.lease, SESSION))); assertTrue(f.scheduled.isEmpty())
    } }
    @Test fun closedPolicyCannotRebindAndWrongRuntimeAccessCannotGainAuthority() = runTest { fixture { f ->
        f.start(); f.timers.close()
        assertIs<PortResult.Failure>(SessionCookingTimers.create(f.rt.runtime, f.access, f.kitchen, f.rt.boundary, f.dispatcher, f.clock, f.scheduler, f.policy))
        val other = PrivateSessionRuntimeTest.RuntimeFixture(f.dispatcher, CookingTimerExecutionPolicy())
        try { other.start(); val otherAccess = other.create()
            assertIs<PortResult.Failure>(SessionCookingTimers.create(f.rt.runtime, otherAccess, f.kitchen, other.boundary, f.dispatcher, f.clock, f.scheduler, f.policy))
        } finally { other.close() }
        assertEquals(1, f.scheduled.size)
    } }

    private suspend fun TestScope.fixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture(StandardTestDispatcher(testScheduler)); try { f.open(); block(f) } finally { f.close() }
    }
    private class Fixture(val dispatcher: CoroutineDispatcher) {
        val policy = CookingTimerExecutionPolicy()
        val rt = PrivateSessionRuntimeTest.RuntimeFixture(dispatcher, policy)
        lateinit var access: PrivateSessionAccess; lateinit var kitchen: PrivateKitchenSession; lateinit var timers: SessionCookingTimers
        var epoch = 1_800_000_000_000L; var elapsed = 1_000L; var continuity: String? = "synthetic-continuity"
        val clock = CookingTimerClock { CookingTimerClockReading(epoch, elapsed, continuity) }
        val scheduled = mutableListOf<NativeWorkTicket>(); val deadlines = mutableListOf<Long>()
        var beforeSchedule: suspend (NativeWorkTicket) -> Unit = {}; var scheduleResult: PortResult<Unit> = PortResult.Value(Unit)
        val scheduler = CookingTimerScheduler { ticket, deadline -> scheduled += ticket; deadlines += deadline; beforeSchedule(ticket); scheduleResult }
        var mappingCommits = 0; var loseMappingCommit = 0
        var beforeMappingCommit: (Int) -> Unit = {}
        var afterMappingCommit: (Int) -> Unit = {}
        var recalled = false; var rejectPatch = false
        lateinit var cookingFlow: CookingFlowController
        private var timerUi: CookingTimerFlowController? = null
        var flowIdCalls = 0
        fun preferences() = KitchenInputController(AuthenticatedMealPlanningAccess.fromSession(access, transport()),
            rt.boundary, dispatcher, EpochClock { epoch }, ConnectivityPort { Connectivity.ONLINE },
            MealOperationIds { id(999) }, KitchenInputPolicy(20, 3, 128, 32, 262_144))
        suspend fun ui(): CookingTimerFlowController {
            timerUi?.let { return it }
            cookingFlow = CookingFlowController(AuthenticatedMealPlanningAccess.fromSession(access, transport()), rt.boundary,
                dispatcher, EpochClock { epoch }, ConnectivityPort { Connectivity.ONLINE }, MealOperationIds { id(800) },
                CookingFlowPolicy(300_000, 262_144, 131_072))
            value(cookingFlow.open(SESSION))
            val created = value(CookingTimerFlowController.create(access, rt.boundary, dispatcher, timers, cookingFlow,
                MealOperationIds { id(100 + flowIdCalls++) }))
            timerUi = created; value(created.open(SESSION)); return created
        }
        suspend fun open() {
            rt.start(); access = rt.create()
            kitchen = peer()
            value(kitchen.cooking.download(access.lease, SESSION))
            timers = value(SessionCookingTimers.create(rt.runtime, access, kitchen, rt.boundary, dispatcher, clock, scheduler, policy))
            rt.dataConnection.beforeRecordCommit = { mappingCommits++; beforeMappingCommit(mappingCommits) }
            rt.dataConnection.afterRecordCommit = {
                afterMappingCommit(mappingCommits)
                if (mappingCommits == loseMappingCommit) error("Synthetic loss after actual SQLite commit")
            }
        }
        // A second real repository on the exact retained access supplies the concurrent SQL
        // acknowledgement. It neither changes the runtime pairing nor supplies a fake store.
        fun peer() = PrivateKitchenSession(access.scope, access.store, rt.boundary, dispatcher, EpochClock { epoch }, transport(), access.originBinding)
        fun transport() = object : AccountTransport {
                override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> = when {
                    call.operationId == "getPlan" && recalled -> problem(410, "RECIPE_RECALLED")
                    call.operationId == "updateCookSession" && rejectPatch -> problem(412, "VERSION_CONFLICT")
                    call.operationId == "getPlan" -> reply(PLAN_BODY)
                    call.operationId == "getCookSession" -> reply(SESSION_BODY)
                    else -> PortResult.Failure(FailureReason.NOT_CONFIGURED)
                }
            }
        class ReadCosts(val snapshot: Int, val load: Int, val eligibility: Int)
        suspend fun readCosts(): ReadCosts {
            suspend fun count(action: suspend () -> Unit): Int {
                val before = rt.controlReads; action(); return rt.controlReads - before
            }
            val snapshot = count { snapshot() }
            val metadata = count { value(kitchen.cooking.readTimerMetadata(access.lease, SESSION)) }
            val eligibility = count { value(kitchen.cooking.timerExecutionCheck(access.lease, SESSION)) }
            return ReadCosts(snapshot, snapshot * 2 + metadata, eligibility)
        }
        suspend fun reackMappingFromPeer(transform: (String) -> String = { it }) {
            val actual = peer().cooking
            val before = checkNotNull(value(actual.read(access.lease, SESSION)))
            val mapping = checkNotNull(value(actual.readTimerMetadata(access.lease, SESSION)))
            val changed = PrivateBytes(transform(mapping.record.payload.copyForCodec().decodeToString()).encodeToByteArray())
            val result = value(actual.acknowledgeTimerMetadata(access.lease, SESSION, before.localRevision, mapping.record, changed))
            assertEquals(mapping.record.revision + 1, result.record.revision)
        }
        suspend fun snapshot() = value(kitchen.cooking.read(access.lease, SESSION))!!
        suspend fun startResult() = timers.change(SESSION, snapshot().localRevision, id(10), CookingTimerAction.Start(TIMER, "step-one", 60))
        suspend fun start() = value(startResult())
        suspend fun changeResult(action: CookingTimerAction, command: Int) = timers.change(SESSION, snapshot().localRevision, id(command), action)
        suspend fun change(action: CookingTimerAction, command: Int) = value(changeResult(action, command))
        suspend fun slot(field: String): String {
            val document = WireDocument.decode(value(kitchen.cooking.readTimerMetadata(access.lease, SESSION))!!.record.payload.copyForCodec())
            val slots = assertNotNull(assertIs<WireField.Value<WireDocument>>(document.field("slots")).value.elementsOrNull())
            val slot = slots.single { assertIs<WireField.Value<WireDocument>>(it.field("id")).value.stringOrNull() == TIMER }
            return assertNotNull(assertIs<WireField.Value<WireDocument>>(slot.field(field)).value.stringOrNull())
        }
        fun advance(millis: Long) { epoch += millis; elapsed += millis }
        suspend fun close() { timerUi?.close(); if (::cookingFlow.isInitialized) cookingFlow.close(); if (::timers.isInitialized) timers.close(); rt.close() }
    }
    companion object {
        private const val SESSION = "00000000-0000-4000-8000-000000000001"
        private const val PLAN = "00000000-0000-4000-8000-000000000002"
        private const val VERSION = "00000000-0000-4000-8000-000000000003"
        private const val INGREDIENT = "00000000-0000-4000-8000-000000000004"
        private const val TIMER = "00000000-0000-4000-8000-000000000005"
        private const val OTHER = "00000000-0000-4000-8000-000000000006"
        private const val SESSION_BODY = """{"id":"$SESSION","version":1,"createdAt":"2026-09-13T07:00:00Z","updatedAt":"2026-09-13T08:00:00Z","planId":"$PLAN","status":"active","currentStepId":"step-one","completedStepIds":[],"deviceSequence":0,"timers":[]}"""
        private const val PLAN_BODY = """{"id":"$PLAN","version":1,"createdAt":"2026-09-13T07:00:00Z","updatedAt":"2026-09-13T08:00:00Z","recipeVersionId":"$VERSION","mode":"cook","status":"ready","constraints":{"ingredientIds":["$INGREDIENT"],"energy":"little","equipmentIds":["bowl"],"servings":1,"hardExcludedIngredientIds":[]},"recipeSnapshot":{"id":"$VERSION","recipeId":"$VERSION","version":1,"createdAt":"2026-09-13T07:00:00Z","updatedAt":"2026-09-13T08:00:00Z","title":"Synthetic timer fixture","reviewStatus":"published","servings":1,"activeMinutes":5,"totalMinutes":5,"utensilCount":1,"equipmentIds":["bowl"],"modes":["cook"],"tasteTags":[],"ingredients":[{"ingredientId":"$INGREDIENT","quantity":1,"unit":"g","optional":false}],"steps":[{"stepId":"step-one","position":1,"instruction":"Synthetic only","ingredientIds":["$INGREDIENT"],"requiredEquipmentIds":["bowl"],"mandatorySafetyStep":false},{"stepId":"step-two","position":2,"instruction":"Synthetic only","ingredientIds":["$INGREDIENT"],"requiredEquipmentIds":["bowl"],"mandatorySafetyStep":false}]},"missingIngredients":[],"changes":[],"reasons":[],"catalogRevision":"synthetic"}"""
        private fun id(n: Int) = "00000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
        private fun ticket(n: Int) = value(NativeWorkTicket.fromNativeIdentity(id(n), NativeWorkKind.TIMER))
        private fun reply(body: String) = PortResult.Value(ApiReply(200, PrivateBytes(body.encodeToByteArray()), etag = "\"1\"", contentType = "application/json"))
        private fun problem(status: Int, code: String) = PortResult.Value(ApiReply(status, PrivateBytes("""{"type":"https://example.test/problem","title":"Synthetic failure","status":$status,"code":"$code","traceId":"synthetic"}""".encodeToByteArray()), contentType = "application/problem+json"))
        private fun <T> value(result: PortResult<T>): T = when (result) { is PortResult.Value -> result.value; is PortResult.Failure -> fail("Expected success, got ${result.reason}") }
    }
}
