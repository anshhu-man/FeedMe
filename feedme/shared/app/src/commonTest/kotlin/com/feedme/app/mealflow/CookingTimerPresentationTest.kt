package com.feedme.app.mealflow

import com.feedme.core.ports.*
import com.feedme.kitchen.*
import com.feedme.mealflow.CookingFlowPhase
import com.feedme.mealflow.timers.*
import kotlin.test.*

/** Rendering/consent tests only. These observations never grant native timer authority. */
class CookingTimerPresentationTest {
    @Test fun durationFormattingRoundsVisibleFractionUpWithoutReportingEarlyExpiry() {
        assertEquals("0:01 remaining (estimate)", timerRemaining(1))
        assertEquals("0:01 remaining (estimate)", timerRemaining(999))
        assertEquals("1:00 remaining (estimate)", timerRemaining(59_001))
        assertEquals("1:00 remaining (estimate)", timerRemaining(60_000))
    }
    @Test fun zeroIsAnEstimatedDeadlineNotACompletionOrFoodReadinessClaim() {
        assertEquals("0:00 remaining (estimate)", timerRemaining(0))
        assertEquals("Estimated time reached", timerTiming(CookingTimerTiming.DUE))
        assertFalse(timerTiming(CookingTimerTiming.DUE).contains("cooked"))
    }
    @Test fun unavailableAndNegativeTimeNeverBecomeZeroOrAResumedCountdown() {
        assertEquals("Remaining time uncertain", timerRemaining(null))
        assertEquals("Remaining time uncertain", timerRemaining(-1))
        assertEquals("Timing uncertain", timerTiming(null))
        assertEquals("Timing uncertain", timerTiming(CookingTimerTiming.UNCERTAIN))
    }
    @Test fun maximumLongFormattingDoesNotOverflowIntoNegativeTime() {
        val text = timerRemaining(Long.MAX_VALUE)
        assertTrue(text.endsWith("remaining (estimate)")); assertFalse(text.startsWith("-"))
    }
    @Test fun runningAndPausedLabelsRemainDifferent() {
        assertEquals("Running on this device", timerTiming(CookingTimerTiming.RUNNING))
        assertEquals("Paused", timerTiming(CookingTimerTiming.PAUSED))
    }
    @Test fun timerLabelUsesPinnedStepIdNotTimerIdOrPositionGuess() {
        val row = timerRow(timer(step = "step-b"), mapOf("step-a" to "First exact instruction", "step-b" to "Second exact instruction"), false)
        assertEquals("Second exact instruction", row.label)
        assertEquals("opaque-timer", row.id); assertFalse(row.label.contains("opaque-timer"))
    }
    @Test fun unknownOrIneligibleLabelsStayUnavailableInsteadOfLeakingIdentifier() {
        assertEquals("Pinned step label unavailable", timerRow(timer(step = "unknown-private-step"), mapOf("step-a" to "First"), false).label)
        assertEquals("Pinned step label unavailable", timerRow(timer(), emptyMap(), false).label)
    }
    @Test fun missingAnchorAndCanonicalTimerRowsRetainHonestUncertainty() {
        val row = timerRow(timer(timing = null, phase = CookingTimerAlertPhase.QUIET), emptyMap(), false)
        assertEquals("Timing uncertain", row.timing); assertEquals("Remaining time uncertain", row.remaining)
        assertFalse(row.cleanupAvailable)
    }
    @Test fun onlyQuietCleanupHistoryWithoutCanonicalTimerIsOmittedFromEditableRows() {
        assertFalse(shouldRenderTimer(timer(phase = CookingTimerAlertPhase.QUIET, status = null, acknowledged = false), null))
        assertTrue(shouldRenderTimer(timer(phase = CookingTimerAlertPhase.QUIET, timing = null, acknowledged = false), null))
        assertTrue(shouldRenderTimer(timer(phase = CookingTimerAlertPhase.QUIET, status = "paused", acknowledged = false), null))
        for (phase in CookingTimerAlertPhase.entries.filter { it != CookingTimerAlertPhase.QUIET })
            assertTrue(shouldRenderTimer(timer(phase = phase, status = null, timing = null, acknowledged = false), null))
    }
    @Test fun quietObservationNeverHidesTheRetainedUnacknowledgedOriginalAction() {
        val quiet = timer(phase = CookingTimerAlertPhase.QUIET, status = null, acknowledged = false)
        assertTrue(shouldRenderTimer(quiet, "opaque-timer"))
        assertFalse(shouldRenderTimer(quiet, "other-pending-timer"))
        assertTrue(assertNotNull(timerIssue(CookingTimerFlowIssue.ACTION_NOT_ACKNOWLEDGED)).contains("identity is retained"))
    }
    @Test fun hiddenDueBannerCannotJoinAnOldEventToAnotherOrPreparedCookingPin() {
        assertTrue(dueMatches())
        assertFalse(dueMatches(due = null)); assertFalse(dueMatches(current = null))
        assertFalse(dueMatches(current = "another-cook")); assertFalse(dueMatches(exactPlan = false))
        assertFalse(dueMatches(pendingStart = true))
        for (phase in CookingFlowPhase.entries.filter { it != CookingFlowPhase.COOKING })
            assertFalse(dueMatches(phase = phase))
    }
    @Test fun hiddenDueBannerRequiresCurrentActiveAvailableOriginNotHistoricalTimerEligibility() {
        assertFalse(dueMatches(status = null)); assertFalse(dueMatches(availability = null))
        for (status in CookingStatus.entries.filter { it != CookingStatus.ACTIVE }) assertFalse(dueMatches(status = status))
        for (availability in CookingAvailability.entries.filter { it != CookingAvailability.AVAILABLE })
            assertFalse(dueMatches(availability = availability))
        assertFalse(dueMatches(origin = false)); assertTrue(dueMatches())
    }
    @Test fun clockEstimateAndActualNativeDeliveryRemainSeparate() {
        val row = timerRow(timer(timing = CookingTimerObservation(CookingTimerTiming.DUE, 0), delivery = CookingTimerDeliveryPhase.SUSPENDED), emptyMap(), false)
        assertEquals("Estimated time reached", row.timing)
        assertEquals("Alert waiting for foreground", row.alert)
    }
    @Test fun onlyActualDeliveryObservationCanUseDeliveredCopy() {
        for (phase in CookingTimerDeliveryPhase.entries) {
            val text = timerDelivery(phase, true)
            assertEquals(phase == CookingTimerDeliveryPhase.DELIVERED, text.contains("Due event delivered"))
        }
    }
    @Test fun visibleArmedStateWithoutThisOwnerAcknowledgementCannotClaimDelivery() {
        for (phase in CookingTimerDeliveryPhase.entries)
            assertEquals("No acknowledged alert in this owner", timerDelivery(phase, false))
    }
    @Test fun blockedUncertainAndCancellingDeliveryNeverUseScheduledSuccessCopy() {
        for (phase in listOf(CookingTimerDeliveryPhase.BLOCKED, CookingTimerDeliveryPhase.CLOCK_UNCERTAIN,
            CookingTimerDeliveryPhase.OUTCOME_UNKNOWN, CookingTimerDeliveryPhase.CANCELLING)) {
            val text = timerDelivery(phase, true)
            assertFalse(text.contains("scheduled")); assertFalse(text.contains("delivered"))
        }
    }
    @Test fun alertCleanupIsExplicitlySeparateFromOriginalMutationAcknowledgement() {
        val text = assertNotNull(timerIssue(CookingTimerFlowIssue.ALERT_CLEANUP_ACKNOWLEDGED))
        assertTrue(text.contains("original unacknowledged")); assertTrue(text.contains("remains unresolved"))
        assertTrue(assertNotNull(timerIssue(CookingTimerFlowIssue.ACTION_NOT_ACKNOWLEDGED)).contains("do not start a replacement"))
    }
    @Test fun allTimerIssuesHaveSafeActionableCopyWithoutRawIdentifiers() {
        assertNull(timerIssue(CookingTimerFlowIssue.NONE))
        CookingTimerFlowIssue.entries.filter { it != CookingTimerFlowIssue.NONE }.forEach { assertFalse(timerIssue(it).isNullOrBlank()) }
        assertFalse(timerRow(timer(), emptyMap(), false).toString().contains("opaque-timer"))
        assertFalse(CookingTimerRemoval("private-session", "private-plan", 5, "private-timer").toString().contains("private-plan"))
    }
    @Test fun cleanupAvailabilityDoesNotImplyOrdinaryTimerEditPermission() {
        assertTrue(timerRow(timer(phase = CookingTimerAlertPhase.CANCELLING), emptyMap(), false).cleanupAvailable)
        assertFalse(timerRow(timer(phase = CookingTimerAlertPhase.CANCELLING), emptyMap(), true).cleanupAvailable)
        assertFalse(timerRow(timer(phase = CookingTimerAlertPhase.QUIET), emptyMap(), false).cleanupAvailable)
    }
    @Test fun removalConsentUsesExactObjectNotAnIdenticalReconstructedTicket() {
        val f = owner(); f.ui.present("session", "plan", 5, "timer")
        val original = assertNotNull(f.ui.states.value)
        assertTrue(f.ui.owns(original)); assertFalse(f.ui.owns(CookingTimerRemoval("session", "plan", 5, "timer")))
        f.ui.close()
    }
    @Test fun replacingOrDismissingRemovalConsentInvalidatesTheOldDialog() {
        val f = owner(); f.ui.present("session", "plan", 5, "first"); val old = assertNotNull(f.ui.states.value)
        f.ui.present("session", "plan", 6, "second"); val current = assertNotNull(f.ui.states.value)
        assertFalse(f.ui.owns(old)); assertTrue(f.ui.owns(current))
        f.ui.dismiss(); assertFalse(f.ui.owns(current)); assertNull(f.ui.states.value); f.ui.close()
    }
    @Test fun actualLeaseInvalidationSynchronouslyHidesConsentAndPreservesNewerLease() {
        val f = owner(); f.ui.present("session", "plan", 5, "timer"); val old = assertNotNull(f.ui.states.value)
        val next = f.boundary.activate(f.scope)
        assertNull(f.ui.states.value); assertFalse(f.ui.owns(old))
        f.ui.present("new-private", "new-private", 1, "new-private"); assertNull(f.ui.states.value)
        f.ui.close(); assertTrue(f.boundary.isCurrent(next)); f.boundary.clear()
    }
    @Test fun closingConsentOwnerDoesNotCloseSessionOrAllowDialogResurrection() {
        val f = owner(); f.ui.present("session", "plan", 5, "timer"); f.ui.close(); f.ui.close()
        f.ui.present("session", "plan", 5, "timer"); assertNull(f.ui.states.value); assertTrue(f.boundary.isCurrent(f.lease)); f.boundary.clear()
    }
    @Test fun freshStaleConsentOwnerNeverExposesPrivateFields() {
        val f = owner(); f.boundary.clear(); val ui = CookingTimerUiOwner(f.boundary, f.lease)
        ui.present("private-session", "private-plan", 5, "private-timer"); assertNull(ui.states.value); ui.close(); f.ui.close()
    }

    private class Owner {
        val scope = StorageScope("synthetic", ActorKind.ACCOUNT, "owner")
        val boundary = SessionBoundary(); val lease = boundary.activate(scope); val ui = CookingTimerUiOwner(boundary, lease)
    }
    private fun owner() = Owner()
    private fun dueMatches(due: String? = "cook", current: String? = "cook", phase: CookingFlowPhase = CookingFlowPhase.COOKING,
        status: CookingStatus? = CookingStatus.ACTIVE, availability: CookingAvailability? = CookingAvailability.AVAILABLE,
        origin: Boolean = true, exactPlan: Boolean = true, pendingStart: Boolean = false) =
        timerDueMatchesCurrent(due, current, phase, status, availability, origin, exactPlan, pendingStart)
    private fun timer(step: String? = "step-a", timing: CookingTimerObservation? = CookingTimerObservation(CookingTimerTiming.RUNNING, 60_000),
        phase: CookingTimerAlertPhase = CookingTimerAlertPhase.ARMED,
        delivery: CookingTimerDeliveryPhase = CookingTimerDeliveryPhase.ENQUEUED,
        status: String? = "running", acknowledged: Boolean = true) = CookingTimerView("opaque-timer", 1, timing, phase, acknowledged,
        step, status, 60, delivery)
}
