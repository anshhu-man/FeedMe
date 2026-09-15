package com.feedme.mealflow.timers

import com.feedme.kitchen.*
import kotlin.test.*

class CookingTimerFlowModelsTest {
    @Test fun explicitDurationAcceptsOnlyBoundedIntegralSeconds() {
        for ((text, seconds) in listOf("1" to 1L, " 60 " to 60L, "00060" to 60L, "86400" to 86400L))
            assertEquals(seconds, CookingTimerDurationInput.seconds(text))
    }
    @Test fun fractionSignExponentBlankAndOverflowNeverInventTimerDurations() {
        for (text in listOf("", " ", "0", "86401", "999999", "1.5", "1e2", "+60", "-1", "NaN", "60 seconds"))
            assertNull(CookingTimerDurationInput.seconds(text))
    }
    @Test fun snapshotDetachesListsAndPreservesExactLongRevision() {
        val steps = mutableListOf(CookingTimerStep("pinned-step", "Private instruction", 60))
        val timers = mutableListOf(CookingTimerView("private-timer", 7, CookingTimerObservation(CookingTimerTiming.DUE, 0),
            CookingTimerAlertPhase.ARMED, true))
        val snapshot = CookingTimerSnapshot("private-session", Long.MAX_VALUE - 1, "private-plan", "private-recipe",
            "pinned-step", CookingAvailability.AVAILABLE, CookingStatus.ACTIVE, true, 2, steps, timers)
        steps.clear(); timers.clear()
        assertEquals(Long.MAX_VALUE - 1, snapshot.localRevision); assertEquals(1, snapshot.steps.size); assertEquals(1, snapshot.timers.size)
        runCatching { (snapshot.steps as MutableList<CookingTimerStep>).clear() }
        assertEquals(1, snapshot.steps.size)
    }
    @Test fun dueAndInstallAcknowledgementRemainSeparateObservations() {
        val view = CookingTimerView("timer", 4, CookingTimerObservation(CookingTimerTiming.DUE, 0),
            CookingTimerAlertPhase.ARMED, true, deliveryPhase = CookingTimerDeliveryPhase.BLOCKED)
        assertTrue(view.alertAcknowledgedInThisOwner)
        assertEquals(CookingTimerTiming.DUE, view.timing!!.timing)
        assertEquals(CookingTimerDeliveryPhase.BLOCKED, view.deliveryPhase)
    }
    @Test fun cleanupPreservesOriginalUnacknowledgedCommandAndRevision() {
        val original = CookingTimerPendingAction("private-session", "private-timer", 99, "private-command", CookingTimerChangeKind.START)
        val cleaned = original.cancelled()
        assertFalse(original.cancellationAcknowledged); assertTrue(cleaned.cancellationAcknowledged)
        assertEquals(original.commandId, cleaned.commandId); assertEquals(original.expectedLocalRevision, cleaned.expectedLocalRevision)
        assertEquals(original.kind, cleaned.kind)
    }
    @Test fun dueProjectionRequiresExactCurrentGenerationAndAcknowledgedCanonicalRunningTimer() {
        val observation = CookingTimerDueObservation()
        val due = CookingTimerDue("session", "timer", 4)
        observation.record(due)
        // Native delivery is the proof of due: a cached clock observation need not yet say DUE.
        assertSame(due, observation.current(dueSnapshot()))
        for (snapshot in listOf(null, dueSnapshot(session = "other"), dueSnapshot(timer = "other"),
            dueSnapshot(generation = 5), dueSnapshot(status = "paused"), dueSnapshot(status = null),
            dueSnapshot(phase = CookingTimerAlertPhase.CANCELLING), dueSnapshot(acknowledged = false),
            dueSnapshot(available = false), dueSnapshot(originMatches = false))) assertNull(observation.current(snapshot))
    }
    @Test fun exactMutationOrCleanupClearsOldDueEvenIfOriginalSnapshotIsStillCached() {
        val observation = CookingTimerDueObservation()
        val due = CookingTimerDue("session", "timer", 4)
        observation.record(due)
        observation.invalidate("session", "sibling")
        observation.invalidate("other", "timer")
        assertSame(due, observation.current(dueSnapshot()))
        observation.invalidate("session", "timer")
        assertNull(observation.current(dueSnapshot()))
        assertNull(observation.current(dueSnapshot(generation = 5)))
        observation.record(CookingTimerDue("session", "timer", 5))
        assertEquals(5L, observation.current(dueSnapshot(generation = 5))!!.generation)
        observation.clear(); assertNull(observation.current(dueSnapshot(generation = 5)))
    }
    @Test fun allPrivateTimerUiModelsHaveRedactedDiagnostics() {
        val step = CookingTimerStep("private-step", "Private instruction", null)
        val due = CookingTimerDue("private-session", "private-timer", 1)
        val pending = CookingTimerPendingAction("private-session", "private-timer", 1, "private-command", CookingTimerChangeKind.CANCEL)
        val state = CookingTimerFlowState(true, false, null, "private-step", "60", pending, due, false, false,
            CookingTimerFlowIssue.ACTION_NOT_ACKNOWLEDGED)
        for (item in listOf(step, due, pending, state)) for (secret in listOf("private-session", "private-timer", "private-command", "Private instruction"))
            assertFalse(item.toString().contains(secret))
    }

    private fun dueSnapshot(session: String = "session", timer: String = "timer", generation: Long = 4,
        status: String? = "running", phase: CookingTimerAlertPhase = CookingTimerAlertPhase.ARMED,
        acknowledged: Boolean = true, available: Boolean = true, originMatches: Boolean = true) =
        CookingTimerSnapshot(session, 10, "plan", "recipe", "step",
            if (available) CookingAvailability.AVAILABLE else CookingAvailability.RECALLED,
            CookingStatus.ACTIVE, originMatches, 0, emptyList(), listOf(CookingTimerView(timer, generation,
                CookingTimerObservation(CookingTimerTiming.RUNNING, 10), phase, acknowledged, status = status)))
}
