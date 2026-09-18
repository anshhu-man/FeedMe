package com.feedme.server.runtime

import java.util.concurrent.atomic.AtomicReference

enum class ServiceLifecycleState { STARTING, SERVING, DRAINING, STOPPED }

/**
 * Process-local request admission, not a product-readiness or authorization proof.
 *
 * Transitions are one-way. Draining refuses later admissions but does not cancel work
 * already admitted, wait for its completion, or close its resources. The owning server
 * supplies its own bounded graceful shutdown and resource ordering.
 */
class ServiceLifecycle {
    private val current = AtomicReference(ServiceLifecycleState.STARTING)

    val state: ServiceLifecycleState get() = current.get()

    /** Returns true only for the one successful STARTING -> SERVING transition. */
    fun startServing(): Boolean = current.compareAndSet(
        ServiceLifecycleState.STARTING,
        ServiceLifecycleState.SERVING,
    )

    /** May retire startup too; a late startup callback cannot revive this instance. */
    fun beginDraining(): Boolean {
        while (true) {
            val observed = current.get()
            when (observed) {
                ServiceLifecycleState.DRAINING, ServiceLifecycleState.STOPPED -> return false
                ServiceLifecycleState.STARTING, ServiceLifecycleState.SERVING ->
                    if (current.compareAndSet(observed, ServiceLifecycleState.DRAINING)) return true
            }
        }
    }

    /** Terminal even when startup or a drain notification has not yet completed. */
    fun markStopped(): Boolean = current.getAndSet(ServiceLifecycleState.STOPPED) != ServiceLifecycleState.STOPPED

    /**
     * Linearizes admission at this single atomic read. Call once immediately before
     * dispatch; true admits that operation even if draining starts before it finishes.
     * The result is not a reusable ticket or a guarantee about future admissions.
     */
    fun tryAdmit(): Boolean = current.get() == ServiceLifecycleState.SERVING
}
