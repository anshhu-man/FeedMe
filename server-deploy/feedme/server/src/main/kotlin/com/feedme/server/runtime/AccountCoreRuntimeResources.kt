package com.feedme.server.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Fixed public failure, with no connection, configuration or cleanup cause attached. */
class AccountCoreRuntimeFailure : IllegalStateException("Account core runtime unavailable")

/** Owns only resources explicitly installed before listener publication. The database and
 * catalogs are borrowed and are deliberately absent from this lifetime's close surface.
 *
 * Drain refuses new admission before stopping the listener. Already admitted work may finish
 * until the bounded deadline; timeout never means rollback and never invokes shutdownNow.
 * Every acquired resource is attempted once even after cancellation/fatal cleanup failures.
 * STOPPED/latch indicate that this close attempt finished, not that an overdue task rolled back
 * or a listener whose stop failed is proven absent. Such an attempt always reports failure.
 */
internal class AccountCoreRuntimeResources(
    private val executor: ExecutorService,
    private val lifecycle: ServiceLifecycle,
    private val closeDispatcher: () -> Unit,
    private val listenerStopped: CountDownLatch,
    private val drainMillis: Long = 60_000,
) : AutoCloseable {
    init { require(drainMillis in 1..60_000) }

    var assembly: AutoCloseable? = null
    // Optional separately configured owner. Never create/start privileged work here.
    // Retire it before the borrowed DB dispatcher is drained or provider keys are closed.
    var backgroundWorker: AutoCloseable? = null
    var stopListener: (() -> Unit)? = null
    var diagnostics: AutoCloseable? = null
    private var closed = false

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        lifecycle.beginDraining()
        var failed = false
        var interrupted = Thread.interrupted()
        var fatal: Error? = null
        var cancellation: CancellationException? = null
        var interruption: InterruptedException? = null
        fun retain(failure: Throwable) {
            failed = true
            when (failure) {
                is Error -> if (fatal == null) fatal = failure
                is CancellationException -> if (cancellation == null) cancellation = failure
                is InterruptedException -> {
                    if (interruption == null) interruption = failure
                    interrupted = true
                    Thread.interrupted() // Cleanup still gets the remainder of its bounded drain.
                }
            }
        }
        fun cleanup(action: () -> Unit) { try { action() } catch (failure: Throwable) { retain(failure) } }
        try {
            cleanup { stopListener?.invoke() }
            cleanup { backgroundWorker?.close() }
            try {
                executor.shutdown()
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(drainMillis)
                while (!executor.isTerminated) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) { failed = true; break }
                    try {
                        if (!executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) { failed = true; break }
                    } catch (failure: InterruptedException) { retain(failure) }
                }
            } catch (failure: Throwable) { retain(failure) }
        } finally {
            cleanup { assembly?.close() }
            cleanup(closeDispatcher)
            cleanup { diagnostics?.close() }
            lifecycle.markStopped()
            listenerStopped.countDown()
            interrupted = Thread.interrupted() || interrupted
            if (interrupted) Thread.currentThread().interrupt()
        }
        // Never turn fatal/cancellation/interruption control flow into a successful stop or
        // a sanitized ordinary failure. An ordinary failure never displaces these signals.
        fatal?.let { throw it }
        cancellation?.let { throw it }
        interruption?.let { throw it }
        if (failed) throw AccountCoreRuntimeFailure()
    }
}
