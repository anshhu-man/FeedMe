package com.feedme.server.http

import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock

/** Process-local diagnostic counters, not a durable audit, delivery acknowledgement or
 * readiness signal. Concurrent snapshots may span multiple counter updates. Delivered
 * means only that the destination returned successfully, not that a collector persisted it.
 * Dropped includes rejected offers and previously accepted records discarded on shutdown.
 */
data class HttpDiagnosticSnapshot(
    val accepted: Long,
    val delivered: Long,
    val failed: Long,
    val dropped: Long,
    val discarded: Long,
    val queued: Int,
    val inFlight: Boolean,
    val closed: Boolean,
    val terminated: Boolean,
    val shutdownTimedOut: Boolean,
)

/** One owned daemon and a finite queue of already-redacted immutable observations.
 * Request threads never wait for queue capacity, a queue lock, the destination or shutdown.
 * Queue-lock contention drops an observation too: diagnostics cannot delay product work.
 *
 * close fences late offers and drains within its explicit deadline. At expiry it discards
 * queued records without interrupting the destination. A noninterruptible destination may
 * retain its single in-flight record and daemon beyond that deadline; snapshot reports this
 * honestly. A briefly contended queue is still bounded and is discarded when the writer
 * next obtains it. Neither the destination nor global stdout is closed by this owner.
 */
class BoundedHttpObservationSink(
    private val capacity: Int,
    private val shutdownMillis: Long,
    private val destination: HttpObservationSink,
) : HttpObservationSink, AutoCloseable {
    private val gate = ReentrantLock()
    private val queue = ArrayDeque<HttpObservation>()
    private val closed = AtomicBoolean()
    private val closeStarted = AtomicBoolean()
    private val discardRemaining = AtomicBoolean()
    private val timedOut = AtomicBoolean()
    private val accepted = AtomicLong()
    private val delivered = AtomicLong()
    private val failed = AtomicLong()
    private val dropped = AtomicLong()
    private val discarded = AtomicLong()
    private val queued = AtomicInteger()
    private val inFlight = AtomicBoolean()
    private val writer: Thread

    init {
        require(capacity in 1..4096 && shutdownMillis in 1..5000) { "Invalid diagnostic bounds" }
        writer = Thread(::writeLoop, "feedme-http-diagnostics").apply {
            isDaemon = true
            // Fatal failures end this writer, but never leak a destination exception/stack.
            // writeLoop records failure, fences offers and discards its queue first.
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> }
        }
        writer.start()
    }

    override fun record(observation: HttpObservation) {
        if (closed.get() || !gate.tryLock()) { dropped.incrementBounded(); return }
        var offered = false
        try {
            if (closed.get() || queue.size >= capacity) dropped.incrementBounded()
            else {
                queue.addLast(observation)
                queued.incrementAndGet()
                accepted.incrementBounded()
                offered = true
            }
        } finally { gate.unlock() }
        if (offered) LockSupport.unpark(writer)
    }

    fun snapshot() = HttpDiagnosticSnapshot(accepted.get(), delivered.get(), failed.get(), dropped.get(),
        discarded.get(), queued.get(), inFlight.get(), closed.get(), !writer.isAlive, timedOut.get())

    override fun close() {
        // Every returning close call has fenced future offers, even if another closer
        // has won ownership but has not yet been scheduled again.
        closed.set(true)
        // A concurrent/duplicate closer neither joins another closer nor starts a new deadline.
        if (!closeStarted.compareAndSet(false, true)) return
        LockSupport.unpark(writer)
        if (Thread.currentThread() === writer) {
            discardRemaining.set(true); discardWithoutWaiting(); return
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(shutdownMillis)
        var interrupted = Thread.interrupted()
        try {
            while (writer.isAlive) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) break
                try { writer.join(remaining / 1_000_000, (remaining % 1_000_000).toInt()) }
                catch (_: InterruptedException) { interrupted = true }
            }
            if (writer.isAlive) {
                timedOut.set(true)
                discardRemaining.set(true)
                discardWithoutWaiting()
                LockSupport.unpark(writer)
            }
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }

    private fun writeLoop() {
        try {
            while (true) {
                val next = take()
                if (next.observation == null) {
                    if (next.stopped) return
                    // unpark has a single retained permit; offers do not accumulate an
                    // unbounded semaphore count while the writer is busy.
                    LockSupport.park(this)
                    if (Thread.currentThread().isInterrupted) return
                    continue
                }
                try {
                    destination.record(next.observation)
                    delivered.incrementBounded()
                } catch (_: InterruptedException) {
                    failed.incrementBounded(); Thread.currentThread().interrupt(); return
                } catch (_: Exception) {
                    // A destination's own cancellation/exception is a diagnostic failure,
                    // not cancellation of the request whose observation was enqueued.
                    failed.incrementBounded()
                } catch (fatal: Error) {
                    failed.incrementBounded(); throw fatal
                } finally { inFlight.set(false) }
                if (Thread.currentThread().isInterrupted) return
            }
        } finally {
            // Fence BEFORE acquiring the queue: an offer already inside its tiny critical
            // section may finish, but cannot be stranded behind a terminated writer.
            closed.set(true); discardRemaining.set(true)
            gate.lock()
            try { discardQueue() } finally { gate.unlock() }
        }
    }

    private data class Next(val observation: HttpObservation?, val stopped: Boolean)

    private fun take(): Next {
        gate.lock()
        try {
            if (discardRemaining.get()) discardQueue()
            val next = queue.pollFirst()?.also { queued.decrementAndGet(); inFlight.set(true) }
            // Decide empty+closed under the same gate as enqueue, not after releasing it.
            // Otherwise an accepted observation racing close could miss a healthy drain.
            return Next(next, next == null && closed.get())
        } finally { gate.unlock() }
    }

    private fun discardWithoutWaiting() {
        if (!gate.tryLock()) return
        try { discardQueue() } finally { gate.unlock() }
    }

    private fun discardQueue() {
        while (queue.pollFirst() != null) {
            queued.decrementAndGet(); discarded.incrementBounded(); dropped.incrementBounded()
        }
    }

    override fun toString() = "BoundedHttpObservationSink(<redacted>)"
}

private fun AtomicLong.incrementBounded() = updateAndGet { if (it == Long.MAX_VALUE) it else it + 1 }

/** Modest local runtime engineering bounds, not production capacity sizing or launch policy. */
internal fun createRuntimeHttpDiagnostics() = BoundedHttpObservationSink(
    capacity = 256,
    shutdownMillis = 1000,
    destination = JsonLineHttpObservationSink(),
)
