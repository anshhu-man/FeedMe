package com.feedme.mealflow.timers

import android.os.Handler
import android.os.Looper
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.kitchen.CookingTimerClock
import com.feedme.kitchen.CookingTimerClockReading
import com.feedme.kitchen.CookingTimerReducer
import com.feedme.session.NativeWorkKind
import com.feedme.session.NativeWorkTicket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Enqueued is not delivered; a blocked/unknown callback is never silently retried. */
enum class AndroidForegroundTimerPhase {
    ENQUEUED, SUSPENDED, CHECKING, DELIVERED, BLOCKED, CLOCK_UNCERTAIN, OUTCOME_UNKNOWN, CANCELLING,
}

/** Diagnostic only. No native ticket, private content, lease or scheduling authority is exposed. */
class AndroidForegroundTimerObservation(val phase: AndroidForegroundTimerPhase) {
    override fun toString() = "AndroidForegroundTimerObservation(phase=$phase)"
}

/**
 * Main-thread Handler mechanism, internal to the native adapter. This is not an authentication
 * seam: its only production gate is the adapter's exact SessionCookingTimers.runLocalEffect.
 * Handler time is uptime-based and can run late after sleep; each wake rechecks elapsed time.
 * Queue success is only callback installation, never a wakeup, deadline or delivery guarantee.
 */
internal class AndroidForegroundTimerDispatch(
    private val handler: Handler,
    private val scope: CoroutineScope,
    private val clock: CookingTimerClock,
    private val gate: suspend (NativeWorkTicket, () -> PortResult<Unit>) -> PortResult<Unit>,
    private val effect: (NativeWorkTicket) -> PortResult<Unit>,
) {
    private val entries = mutableMapOf<String, Entry>()
    private var foreground = false
    private var closed = false
    private var lifecycle = 0L

    private inner class Entry(val ticket: NativeWorkTicket, val anchor: CookingTimerClockReading, val remaining: Long) {
        var phase = AndroidForegroundTimerPhase.ENQUEUED
        var effectStarted = false
        var job: Job? = null
        val wake = Runnable { tick(this) }
    }

    fun enqueue(ticket: NativeWorkTicket, deadlineMillis: Long): PortResult<Unit> {
        main()
        if (closed) return failure(FailureReason.STALE_SESSION)
        if (!foreground) return failure(FailureReason.UNAVAILABLE)
        if (ticket.kind != NativeWorkKind.TIMER) return failure(FailureReason.INVALID_DATA)
        if (entries.containsKey(ticket.id)) return failure(FailureReason.CONFLICT)
        if (entries.size >= CookingTimerReducer.MAX_TIMERS) return failure(FailureReason.CONFLICT)
        val now = try { clock.read() } catch (_: Exception) { return failure(FailureReason.UNAVAILABLE) }
        val remaining = deadlineMillis - now.epochMillis
        if (now.continuity == null || deadlineMillis !in 0..253402300799999L ||
            remaining !in 1..CookingTimerReducer.MAX_DURATION_SECONDS * 1000) return failure(FailureReason.INVALID_DATA)
        val entry = Entry(ticket, now, remaining)
        entries[ticket.id] = entry // Retain exact identity even if posting fails or its reply is lost.
        if (!post(entry, minOf(remaining, POLL_MILLIS))) {
            entry.phase = AndroidForegroundTimerPhase.BLOCKED
            return failure(FailureReason.UNAVAILABLE)
        }
        return PortResult.Value(Unit)
    }

    fun setForeground(value: Boolean): PortResult<Unit> {
        main()
        if (closed) return failure(FailureReason.STALE_SESSION)
        if (foreground == value) return PortResult.Value(Unit)
        foreground = value
        lifecycle++
        entries.values.forEach { entry ->
            if (!value && entry.phase in setOf(AndroidForegroundTimerPhase.ENQUEUED, AndroidForegroundTimerPhase.CHECKING)) {
                // The synchronous effect can finish before its enclosing durable gate returns.
                // Losing foreground in that window must never turn it into a resumable callback.
                entry.phase = if (entry.effectStarted) AndroidForegroundTimerPhase.OUTCOME_UNKNOWN
                    else AndroidForegroundTimerPhase.SUSPENDED
                handler.removeCallbacks(entry.wake)
                entry.job?.cancel(); entry.job = null
            } else if (value && entry.phase == AndroidForegroundTimerPhase.SUSPENDED) {
                // Same live process, exact retained callback only. Never reconstruct from disk.
                entry.phase = AndroidForegroundTimerPhase.ENQUEUED
                if (!post(entry, 1)) entry.phase = AndroidForegroundTimerPhase.BLOCKED
            }
        }
        return PortResult.Value(Unit)
    }

    fun observe(ticket: NativeWorkTicket): PortResult<AndroidForegroundTimerObservation> {
        main()
        if (ticket.kind != NativeWorkKind.TIMER) return failure(FailureReason.INVALID_DATA)
        val entry = entries[ticket.id] ?: return failure(FailureReason.NOT_FOUND)
        return PortResult.Value(AndroidForegroundTimerObservation(entry.phase))
    }

    /** Fence/remove the exact local callback before any suspending downstream cancellation. */
    fun fence(ticket: NativeWorkTicket) {
        main()
        entries[ticket.id]?.let { entry ->
            entry.phase = AndroidForegroundTimerPhase.CANCELLING
            handler.removeCallbacks(entry.wake)
            entry.job?.cancel(); entry.job = null
        }
    }

    /** Called only after the real exact alarm/notification/worker cancellation acknowledges. */
    fun acknowledgeCancellation(ticket: NativeWorkTicket) {
        main()
        entries.remove(ticket.id)?.let { handler.removeCallbacks(it.wake); it.job?.cancel() }
    }

    fun close() {
        main()
        if (closed) return
        closed = true; foreground = false; lifecycle++
        entries.values.forEach { entry ->
            entry.phase = AndroidForegroundTimerPhase.CANCELLING
            handler.removeCallbacks(entry.wake); entry.job?.cancel(); entry.job = null
        }
        // Keep exact entries until native cancellation acknowledges; close does not claim it did.
    }

    private fun tick(entry: Entry) {
        main()
        if (closed || !foreground || entries[entry.ticket.id] !== entry || entry.phase != AndroidForegroundTimerPhase.ENQUEUED) return
        val now = try { clock.read() } catch (_: Exception) {
            entry.phase = AndroidForegroundTimerPhase.CLOCK_UNCERTAIN; return
        }
        val elapsed = now.elapsedMillis - entry.anchor.elapsedMillis
        val wall = now.epochMillis - entry.anchor.epochMillis
        if (now.continuity == null || now.continuity != entry.anchor.continuity || elapsed < 0 || wall < 0 ||
            (if (wall >= elapsed) wall - elapsed else elapsed - wall) > CookingTimerReducer.CLOCK_DRIFT_MILLIS) {
            entry.phase = AndroidForegroundTimerPhase.CLOCK_UNCERTAIN; return
        }
        val remaining = entry.remaining - minOf(elapsed, entry.remaining)
        if (remaining > 0) {
            if (!post(entry, minOf(remaining, POLL_MILLIS))) entry.phase = AndroidForegroundTimerPhase.BLOCKED
            return
        }
        entry.phase = AndroidForegroundTimerPhase.CHECKING
        val captured = lifecycle
        entry.job = scope.launch {
            var invoked = false
            var effectResult: PortResult<Unit>? = null
            try {
                val result = gate(entry.ticket) {
                    // The runtime gate may have suspended at its durable ledger barrier.
                    // Recheck this exact local callback synchronously inside the final effect.
                    if (closed || !foreground || lifecycle != captured || entries[entry.ticket.id] !== entry ||
                        entry.phase != AndroidForegroundTimerPhase.CHECKING || invoked) failure(FailureReason.STALE_SESSION)
                    else {
                        invoked = true
                        entry.effectStarted = true
                        effect(entry.ticket).also { effectResult = it }
                    }
                }
                if (entries[entry.ticket.id] === entry && entry.phase == AndroidForegroundTimerPhase.CHECKING && lifecycle == captured) {
                    entry.phase = when {
                        invoked && effectResult is PortResult.Value && result is PortResult.Value -> AndroidForegroundTimerPhase.DELIVERED
                        invoked -> AndroidForegroundTimerPhase.OUTCOME_UNKNOWN
                        else -> AndroidForegroundTimerPhase.BLOCKED
                    }
                }
            } catch (cancelled: CancellationException) {
                if (entries[entry.ticket.id] === entry && entry.phase == AndroidForegroundTimerPhase.CHECKING && lifecycle == captured)
                    entry.phase = if (invoked) AndroidForegroundTimerPhase.OUTCOME_UNKNOWN else AndroidForegroundTimerPhase.BLOCKED
                throw cancelled
            } catch (_: Exception) {
                if (entries[entry.ticket.id] === entry && entry.phase == AndroidForegroundTimerPhase.CHECKING && lifecycle == captured)
                    entry.phase = if (invoked) AndroidForegroundTimerPhase.OUTCOME_UNKNOWN else AndroidForegroundTimerPhase.BLOCKED
            }
        }
    }

    private fun post(entry: Entry, delay: Long): Boolean = handler.postDelayed(entry.wake, delay)
    private fun main() { check(Looper.myLooper() === handler.looper && handler.looper === Looper.getMainLooper()) { "Foreground timer requires main owner" } }
    private fun failure(reason: FailureReason) = PortResult.Failure(reason)
    private companion object { const val POLL_MILLIS = 1_000L }
}
