package com.feedme.server.runtime

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Application/listener-owned, nonblocking product-work budget. No queue, timer, authentication,
 * readiness or retry promise. The HTTP owner retains each permit until its entire call
 * pipeline unwinds; caller cancellation cannot release another call's permit. */
class ServiceRequestAdmission(val maximumInFlight: Int) {
    private val admitted = AtomicInteger()

    init { require(maximumInFlight in 1..4096) { "Invalid request admission limit" } }

    fun tryAcquire(): Permit? {
        while (true) {
            val observed = admitted.get()
            if (observed >= maximumInFlight) return null
            if (admitted.compareAndSet(observed, observed + 1)) return Permit(admitted)
        }
    }

    class Permit internal constructor(private val admitted: AtomicInteger) : AutoCloseable {
        private val released = AtomicBoolean()
        override fun close() {
            if (released.compareAndSet(false, true)) admitted.decrementAndGet()
        }
        override fun toString() = "ServiceRequestPermit(<redacted>)"
    }

    override fun toString() = "ServiceRequestAdmission(<redacted>)"
}
