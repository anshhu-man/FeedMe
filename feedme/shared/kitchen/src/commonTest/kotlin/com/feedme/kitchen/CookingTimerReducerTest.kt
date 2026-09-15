package com.feedme.kitchen

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import kotlin.test.*

class CookingTimerReducerTest {
    @Test fun startUsesCanonicalUtcAndDoesNotAdvanceAnyStep() {
        val state = start(); val raw = state.timers.single().encodeUtf8().decodeToString()
        assertTrue(raw.contains("\"stepId\":\"step-one\"")); assertTrue(raw.contains("1970-01-01T00:01:10Z"))
        assertEquals(60_000L, state.anchor!!.remainingMillis)
    }
    @Test fun continuousElapsedClockControlsRemainingAndDue() {
        val state = start()
        assertEquals(48_000L, observe(state, now(22_000, 13_000)).remainingMillis)
        assertEquals(CookingTimerTiming.DUE, observe(state, now(70_000, 61_000)).timing)
    }
    @Test fun wallClockJumpAndRollbackAreUncertainNotFullDurationRestart() {
        val state = start()
        for (time in listOf(now(200_000, 2_000), now(9_000, 2_000))) {
            val result = observe(state, time); assertEquals(CookingTimerTiming.UNCERTAIN, result.timing); assertNull(result.remainingMillis)
        }
    }
    @Test fun missingDifferentContinuityAndElapsedRollbackAreUncertain() {
        val state = start()
        for (time in listOf(now(11_000, 2_000, null), now(11_000, 2_000, "other"), now(10_000, 0)))
            assertEquals(CookingTimerTiming.UNCERTAIN, observe(state, time).timing)
        assertEquals(CookingTimerTiming.UNCERTAIN, value(CookingTimerReducer.observe(state.timers.single(), null, now())).timing)
    }
    @Test fun pauseRoundsUpFractionThenResumeUsesRemainingNotOriginalDuration() {
        val state = start()
        val paused = reduce(state, CookingTimerAction.Pause(ID), now(11_501, 2_501))
        assertNull(paused.anchor); assertTrue(text(paused).contains("\"pausedRemainingSeconds\":59"))
        val resumed = reduce(paused, CookingTimerAction.Resume(ID), now(20_000, 11_000))
        assertEquals(59_000L, resumed.anchor!!.remainingMillis)
    }
    @Test fun resetIsPausedFullDurationAndDoesNotStartAnAlert() {
        val reset = reduce(start(), CookingTimerAction.Reset(ID), now())
        assertNull(reset.anchor); assertTrue(text(reset).contains("\"status\":\"paused\"")); assertFalse(text(reset).contains("endAt"))
    }
    @Test fun cancellationRemovesOnlyExactTimerAndPreservesSiblingWire() {
        val state = start(); val other = WireDocument.parse(text(state).replace(ID, OTHER))
        val result = value(CookingTimerReducer.reduce(state.timers + other, state.anchor, CookingTimerAction.Cancel(ID), STEPS, now()))
        assertContentEquals(other.encodeUtf8(), result.timers.single().encodeUtf8())
    }
    @Test fun unknownClockPauseAndResumeWithoutContinuityCannotInferRemaining() {
        assertIs<PortResult.Failure>(CookingTimerReducer.reduce(start().timers, start().anchor, CookingTimerAction.Pause(ID), STEPS, now(11_000, 2_000, null)))
        val paused = reduce(start(), CookingTimerAction.Reset(ID), now())
        assertIs<PortResult.Failure>(CookingTimerReducer.reduce(paused.timers, null, CookingTimerAction.Resume(ID), STEPS, now(11_000, 2_000, null)))
    }
    @Test fun invalidDurationCountsOverflowAndUnknownStepsAreRejected() {
        for (duration in listOf(0L, -1L, 86_401L, Long.MAX_VALUE)) assertIs<PortResult.Failure>(CookingTimerReducer.reduce(emptyList(), null, CookingTimerAction.Start(ID, "step-one", duration), STEPS, now()))
        assertIs<PortResult.Failure>(CookingTimerReducer.reduce(emptyList(), null, CookingTimerAction.Start(ID, "foreign", 30), STEPS, now()))
        assertIs<PortResult.Failure>(CookingTimerReducer.reduce(emptyList(), null, CookingTimerAction.Start(ID, "step-one", 30), STEPS, now(253402300799000, 1)))
    }
    @Test fun duplicateIdentityAndCapacityCannotOverwriteExistingTimers() {
        val state = start()
        assertIs<PortResult.Failure>(CookingTimerReducer.reduce(state.timers + state.timers, state.anchor, CookingTimerAction.Cancel(ID), STEPS, now()))
        assertIs<PortResult.Failure>(CookingTimerReducer.reduce(state.timers, state.anchor, CookingTimerAction.Start(ID, "step-one", 1), STEPS, now()))
        val full = (1..32).map { WireDocument.parse(text(state).replace(ID, "00000000-0000-4000-8000-${it.toString().padStart(12, '0')}")) }
        assertIs<PortResult.Failure>(CookingTimerReducer.reduce(full, null, CookingTimerAction.Start(ID, "step-one", 1), STEPS, now()))
    }
    @Test fun invalidFractionalRemainingAndGreaterThanDurationFail() {
        val paused = text(reduce(start(), CookingTimerAction.Reset(ID), now()))
        for (number in listOf("0.5", "61", "1e100")) assertIs<PortResult.Failure>(CookingTimerReducer.observe(WireDocument.parse(paused.replace("\"pausedRemainingSeconds\":60", "\"pausedRemainingSeconds\":$number")), null, now()))
    }
    @Test fun integralExponentFieldsAreAcceptedWithoutRewritingSiblingValues() {
        val paused = WireDocument.parse(text(reduce(start(), CookingTimerAction.Reset(ID), now())).replace("\"durationSeconds\":60", "\"durationSeconds\":6e1"))
        assertEquals(60_000L, value(CookingTimerReducer.observe(paused, null, now())).remainingMillis)
    }
    @Test fun zeroRemainingPauseCannotResumeAndIsNeverAutomaticCookingCompletion() {
        val paused = reduce(start(), CookingTimerAction.Pause(ID), now(70_000, 61_000))
        assertEquals(CookingTimerTiming.PAUSED, value(CookingTimerReducer.observe(paused.timers.single(), null, now())).timing)
        assertIs<PortResult.Failure>(CookingTimerReducer.reduce(paused.timers, null, CookingTimerAction.Resume(ID), STEPS, now()))
        assertFalse(text(paused).contains("completed"))
    }
    @Test fun mismatchedAnchorDeadlineDoesNotBecomeClockAuthorityAndMetadataIsRedacted() {
        val state = start(); val anchor = CookingTimerAnchor(11_000, 1_000, "boot-proof", 60_000)
        assertEquals(CookingTimerTiming.UNCERTAIN, value(CookingTimerReducer.observe(state.timers.single(), anchor, now())).timing)
        assertFalse(anchor.toString().contains("boot-proof")); assertFalse(now().toString().contains("boot-proof"))
    }
    private fun start() = value(CookingTimerReducer.reduce(emptyList(), null, CookingTimerAction.Start(ID, "step-one", 60), STEPS, now()))
    private fun reduce(state: CookingTimerReduction, action: CookingTimerAction, now: CookingTimerClockReading) = value(CookingTimerReducer.reduce(state.timers, state.anchor, action, STEPS, now))
    private fun observe(state: CookingTimerReduction, now: CookingTimerClockReading) = value(CookingTimerReducer.observe(state.timers.single(), state.anchor, now))
    private fun text(state: CookingTimerReduction) = state.timers.single().encodeUtf8().decodeToString()
    private fun now(epoch: Long = 10_000, elapsed: Long = 1_000, continuity: String? = "boot-proof") = CookingTimerClockReading(epoch, elapsed, continuity)
    private fun <T> value(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
    companion object { const val ID = "10000000-0000-4000-8000-000000000001"; const val OTHER = "10000000-0000-4000-8000-000000000002"; val STEPS = setOf("step-one", "step-two") }
}
