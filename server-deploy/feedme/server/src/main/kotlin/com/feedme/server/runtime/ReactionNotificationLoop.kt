package com.feedme.server.runtime

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

/** Owns scheduling only, never authority, grants, event selection or receipt semantics.
 * Construction is inert. The runtime explicitly starts this after successful admission
 * and listener binding; runOnce must consume a finite, authorized, deduplicated DB batch.
 *
 * There is one coroutine, so batches cannot overlap. A normal failure (including an
 * unknown commit result) waits for the next ordinary poll; it never gets an inner retry
 * or a replacement event/notification ID here. No exception/payload is logged.
 *
 * close must run outside the borrowed database dispatcher. It prevents new batches,
 * cancels delays/suspensions, then bounded-joins before that dispatcher or its authorities
 * may be closed. A blocking JDBC batch may finish; cancellation is not rollback proof.
 */
internal class ReactionNotificationLoop(
    dispatcher: CoroutineDispatcher,
    private val pollIntervalMillis: Long = 1_000,
    private val stopTimeoutMillis: Long = 60_000,
    private val runOnce: suspend () -> Unit,
) : AutoCloseable {
    init { require(pollIntervalMillis in 1_000..60_000); require(stopTimeoutMillis in 1..60_000) }
    private val monitor = Any()
    private val stopped = AtomicBoolean(false)
    private val terminal = AtomicReference<Throwable?>(null)
    // Unexpected fatal/interruption signals must not reach a default coroutine logger.
    // Retain the exact control-flow signal for close; normal failures are caught below.
    private val errors = CoroutineExceptionHandler { _, failure -> terminal.compareAndSet(null, failure) }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + errors)
    private var job: Job? = null

    /** Scheduling liveness only; never notification-delivery or dependency-health proof. */
    val isRunning: Boolean get() = synchronized(monitor) { !stopped.get() && job?.isActive == true }

    fun start() {
        val pending = synchronized(monitor) {
            check(!stopped.get() && job == null) { "Reaction notification loop cannot restart" }
            scope.launch(start = CoroutineStart.LAZY) {
                while (currentCoroutineContext().isActive && !stopped.get()) {
                    var failed = false
                    try {
                        currentCoroutineContext().ensureActive()
                        if (stopped.get()) break
                        if (Thread.currentThread().isInterrupted) throw InterruptedException("Reaction notification loop interrupted")
                        runOnce()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt(); throw interrupted
                    } catch (_: Exception) {
                        // PgTransactions preserves its interruption/control-flow signals.
                        // Ordinary SQL/domain/unknown-outcome failures get bounded backoff.
                        if (Thread.currentThread().isInterrupted) throw InterruptedException("Reaction notification loop interrupted")
                        failed = true
                    }
                    currentCoroutineContext().ensureActive()
                    if (stopped.get()) break
                    delay(if (failed) maxOf(pollIntervalMillis, 5_000L) else pollIntervalMillis)
                }
            }.also { job = it }
        }
        // A concurrent close may cancel this lazy job before start; it cannot revive it.
        pending.start()
    }

    override fun close() {
        val retiring = synchronized(monitor) {
            if (!stopped.compareAndSet(false, true)) return
            scope.cancel("Reaction notification loop stopped")
            job
        }
        try {
            if (retiring != null) runBlocking { withTimeout(stopTimeoutMillis) { retiring.join() } }
        } catch (_: TimeoutCancellationException) {
            throw ReactionNotificationLoopFailure()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt(); throw interrupted
        }
        when (val failure = terminal.get()) {
            null -> Unit
            is Error -> throw failure
            is CancellationException -> throw failure
            is InterruptedException -> { Thread.currentThread().interrupt(); throw failure }
            else -> throw ReactionNotificationLoopFailure()
        }
    }
}

/** Fixed diagnostic only; no JDBC/configuration/event cause or suppressed detail. */
internal class ReactionNotificationLoopFailure : IllegalStateException("Reaction notification loop unavailable")
