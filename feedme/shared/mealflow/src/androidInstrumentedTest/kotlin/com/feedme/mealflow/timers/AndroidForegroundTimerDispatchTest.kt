package com.feedme.mealflow.timers

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.kitchen.CookingTimerClock
import com.feedme.kitchen.CookingTimerClockReading
import com.feedme.session.NativeWorkKind
import com.feedme.session.NativeWorkTicket
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

/** Real Android main Handler tests; synthetic clock/gate cases test dispatch mechanics only.
 * The separate integration class proves the real runtime/domain gate. No notification is posted.
 */
@RunWith(AndroidJUnit4::class)
class AndroidForegroundTimerDispatchTest {
    @Test fun nativeClockUsesActualWallElapsedAndSameProcessOnlyContinuity() {
        val beforeEpoch = System.currentTimeMillis(); val beforeElapsed = SystemClock.elapsedRealtime()
        val a = AndroidProcessCookingTimerClock().read(); val b = AndroidProcessCookingTimerClock().read()
        assertTrue(a.epochMillis in beforeEpoch..System.currentTimeMillis())
        assertTrue(a.elapsedMillis in beforeElapsed..SystemClock.elapsedRealtime())
        assertTrue(b.elapsedMillis >= a.elapsedMillis)
        assertNotNull(a.continuity); assertEquals(a.continuity, b.continuity)
        assertFalse(AndroidProcessCookingTimerClock().toString().contains(a.continuity!!))
    }

    @Test fun installationIsSeparateFromDueDeliveryAndDoesNotFireEarly() = check { f ->
        value(f.dispatch.enqueue(ticket(1), f.epoch + 50))
        assertEquals(AndroidForegroundTimerPhase.ENQUEUED, f.phase(1)); assertEquals(0, f.effects)
        delay(70); assertEquals(0, f.effects) // uptime passed, explicit elapsed evidence did not.
        f.advance(50); f.await(1, AndroidForegroundTimerPhase.DELIVERED)
        assertEquals(1, f.effects); delay(80); assertEquals(1, f.effects)
    }

    @Test fun stoppedForegroundNeverDeliversAndSameOwnerResumeRechecksDueState() = check { f ->
        value(f.dispatch.enqueue(ticket(1), f.epoch + 10)); value(f.dispatch.setForeground(false))
        f.advance(10); delay(40); assertEquals(0, f.effects)
        assertEquals(AndroidForegroundTimerPhase.SUSPENDED, f.phase(1))
        value(f.dispatch.setForeground(true)); f.await(1, AndroidForegroundTimerPhase.DELIVERED)
        assertEquals(1, f.effects)
    }

    @Test fun exactCancellationPreservesSiblingAndRetainsBarrierUntilAcknowledged() = check { f ->
        value(f.dispatch.enqueue(ticket(1), f.epoch + 20)); value(f.dispatch.enqueue(ticket(2), f.epoch + 20))
        f.dispatch.fence(ticket(1)); assertEquals(AndroidForegroundTimerPhase.CANCELLING, f.phase(1))
        f.advance(20); f.await(2, AndroidForegroundTimerPhase.DELIVERED)
        assertEquals(listOf(ticket(2).id), f.delivered)
        f.dispatch.acknowledgeCancellation(ticket(1))
        assertEquals(FailureReason.NOT_FOUND, assertIs<PortResult.Failure>(f.dispatch.observe(ticket(1))).reason)
    }

    @Test fun failureToAcknowledgeLocalCancellationCannotPermitIdentityReuse() = check { f ->
        value(f.dispatch.enqueue(ticket(1), f.epoch + 10)); f.dispatch.fence(ticket(1))
        assertEquals(FailureReason.CONFLICT, assertIs<PortResult.Failure>(f.dispatch.enqueue(ticket(1), f.epoch + 10)).reason)
        value(f.dispatch.setForeground(false)); value(f.dispatch.setForeground(true)); f.advance(10); delay(40)
        assertEquals(0, f.effects); assertEquals(AndroidForegroundTimerPhase.CANCELLING, f.phase(1))
    }

    @Test fun clockJumpUnknownContinuityAndElapsedRegressionAreNotGuessed() = runBlocking {
        for (mode in 0..2) fixture { f ->
            value(f.dispatch.enqueue(ticket(1), f.epoch + 10))
            when (mode) { 0 -> { f.advance(10); f.epoch += 5_000 }; 1 -> f.continuity = null; else -> f.elapsed-- }
            f.await(1, AndroidForegroundTimerPhase.CLOCK_UNCERTAIN); assertEquals(0, f.effects)
            value(f.dispatch.setForeground(false)); value(f.dispatch.setForeground(true)); delay(30)
            assertEquals(0, f.effects)
        }
    }

    @Test fun finalEffectRejectsForegroundLossWhileRuntimeGateIsSuspended() = check { f ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.gate = { _, effect -> withContext(NonCancellable) { entered.complete(Unit); release.await(); effect() } }
        value(f.dispatch.enqueue(ticket(1), f.epoch + 10)); f.advance(10); entered.await()
        value(f.dispatch.setForeground(false)); release.complete(Unit); delay(40)
        assertEquals(0, f.effects); assertEquals(AndroidForegroundTimerPhase.SUSPENDED, f.phase(1))
    }

    @Test fun finalEffectRejectsExactCancellationDuringSuspendedRuntimeGate() = check { f ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.gate = { _, effect -> withContext(NonCancellable) { entered.complete(Unit); release.await(); effect() } }
        value(f.dispatch.enqueue(ticket(1), f.epoch + 10)); f.advance(10); entered.await()
        f.dispatch.fence(ticket(1)); release.complete(Unit); delay(40)
        assertEquals(0, f.effects); assertEquals(AndroidForegroundTimerPhase.CANCELLING, f.phase(1))
    }

    @Test fun blockedRuntimeGateNeverBecomesDeliveredOrAutomaticallyRetries() = check { f ->
        f.gate = { _, _ -> PortResult.Failure(FailureReason.STALE_SESSION) }
        value(f.dispatch.enqueue(ticket(1), f.epoch + 10)); f.advance(10)
        f.await(1, AndroidForegroundTimerPhase.BLOCKED); assertEquals(0, f.effects)
        f.gate = { _, effect -> effect() }; value(f.dispatch.setForeground(false)); value(f.dispatch.setForeground(true)); delay(30)
        assertEquals(0, f.effects)
    }

    @Test fun unknownEffectOutcomeIsNeverReplayedAsASecondAlert() = check { f ->
        f.effectResult = PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
        value(f.dispatch.enqueue(ticket(1), f.epoch + 10)); f.advance(10)
        f.await(1, AndroidForegroundTimerPhase.OUTCOME_UNKNOWN); assertEquals(1, f.effects)
        value(f.dispatch.setForeground(false)); value(f.dispatch.setForeground(true)); delay(40)
        assertEquals(1, f.effects)
    }

    @Test fun foregroundLossAfterEffectBeforeGateAcknowledgementCannotReplayOnResume() = check { f ->
        val invoked = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.gate = { _, effect ->
            val result = effect(); invoked.complete(Unit)
            withContext(NonCancellable) { release.await() }
            result
        }
        value(f.dispatch.enqueue(ticket(1), f.epoch + 10)); f.advance(10); invoked.await()
        assertEquals(1, f.effects)
        value(f.dispatch.setForeground(false))
        assertEquals(AndroidForegroundTimerPhase.OUTCOME_UNKNOWN, f.phase(1))
        value(f.dispatch.setForeground(true)); release.complete(Unit); delay(50)
        assertEquals(1, f.effects)
        assertEquals(AndroidForegroundTimerPhase.OUTCOME_UNKNOWN, f.phase(1))
    }

    @Test fun unsupportedIdentityBoundsDuplicateAndCapacityCannotReplaceCallbacks() = check { f ->
        assertIs<PortResult.Failure>(f.dispatch.enqueue(value(NativeWorkTicket.fromNativeIdentity(ticket(1).id, NativeWorkKind.WORKER)), f.epoch + 10))
        for (deadline in listOf(f.epoch, -1, Long.MAX_VALUE, f.epoch + 86_400_001))
            assertIs<PortResult.Failure>(f.dispatch.enqueue(ticket(1), deadline))
        for (id in 1..32) value(f.dispatch.enqueue(ticket(id), f.epoch + 60_000))
        assertIs<PortResult.Failure>(f.dispatch.enqueue(ticket(1), f.epoch + 60_000))
        assertIs<PortResult.Failure>(f.dispatch.enqueue(ticket(33), f.epoch + 60_000))
        assertEquals(0, f.effects)
    }

    @Test fun closedOwnerCannotDeliverResumeOrReplaceButExactCleanupRemainsAvailable() = check { f ->
        value(f.dispatch.enqueue(ticket(1), f.epoch + 10)); f.dispatch.close(); f.advance(10); delay(30)
        assertEquals(0, f.effects); assertIs<PortResult.Failure>(f.dispatch.setForeground(true))
        assertIs<PortResult.Failure>(f.dispatch.enqueue(ticket(2), f.epoch + 10))
        assertEquals(AndroidForegroundTimerPhase.CANCELLING, f.phase(1))
        f.dispatch.fence(ticket(1)); f.dispatch.acknowledgeCancellation(ticket(1))
        assertIs<PortResult.Failure>(f.dispatch.observe(ticket(1)))
    }

    private fun check(block: suspend (Fixture) -> Unit) = runBlocking { fixture(block) }
    private suspend fun fixture(block: suspend (Fixture) -> Unit) = withContext(Dispatchers.Main.immediate) {
        val f = Fixture()
        try { withTimeout(8_000) { block(f) } } finally { f.dispatch.close(); f.scope.cancel() }
    }
    private class Fixture {
        var epoch = 1_850_000_000_000L; var elapsed = 1_000L; var continuity: String? = "synthetic-continuity"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var gate: suspend (NativeWorkTicket, () -> PortResult<Unit>) -> PortResult<Unit> = { _, effect -> effect() }
        var effectResult: PortResult<Unit> = PortResult.Value(Unit)
        val delivered = mutableListOf<String>(); val effects get() = delivered.size
        val dispatch = AndroidForegroundTimerDispatch(Handler(Looper.getMainLooper()), scope,
            CookingTimerClock { CookingTimerClockReading(epoch, elapsed, continuity) },
            { ticket, effect -> gate(ticket, effect) }, { ticket -> delivered += ticket.id; effectResult })
        init { value(dispatch.setForeground(true)) }
        fun advance(millis: Long) { epoch += millis; elapsed += millis }
        fun phase(id: Int) = value(dispatch.observe(ticket(id))).phase
        suspend fun await(id: Int, target: AndroidForegroundTimerPhase) = withTimeout(5_000) { while (phase(id) != target) delay(10) }
    }
    companion object {
        private fun ticket(n: Int) = value(NativeWorkTicket.fromNativeIdentity("00000000-0000-4000-8000-${n.toString().padStart(12, '0')}", NativeWorkKind.TIMER))
        private fun <T> value(result: PortResult<T>): T = when (result) { is PortResult.Value -> result.value; is PortResult.Failure -> error("Expected value: ${result.reason}") }
    }
}
