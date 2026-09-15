package com.feedme.mealflow

import com.feedme.core.ports.*
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Deny-only bridge for an unsaved form belonging to this exact meal controller. Call invalidate
 * synchronously before editing local fields. Only the controller can acknowledge a persisted
 * draft; neither a UI boolean nor a matching readback can acknowledge an uncertain write.
 * All calls use the session's serialized application dispatcher. This is not copy/recipe rights.
 */
@OptIn(ExperimentalAtomicApi::class)
class MealDraftReadiness internal constructor(
    private val owner: MealRequestController,
    private val access: AuthenticatedMealPlanningAccess,
    private val boundary: SessionBoundary,
) {
    private class Fence(val lease: SessionLease, val origin: String, val boundary: SessionBoundary, val store: PrivateStateStore) {
        var generation = Any()
        var dirty = false
        var uncertain = false
        var record: PrivateRecord? = null
        var subscription: SessionInvalidationSubscription? = null
    }
    internal class Ticket internal constructor(internal val generation: Any, internal val record: PrivateRecord) {
        override fun toString() = "MealDraftTicket(<redacted>)"
    }
    internal class Attempt internal constructor(internal val generation: Any, internal val mayAcknowledge: Boolean) {
        internal var wrote = false
        internal var observed: PrivateRecord? = null
    }
    private var closed = false
    private val fence = acquireFence()
    private fun acquireFence(): Fence {
        val candidate = Fence(access.lease, access.origin, boundary, access.store)
        if (!boundary.isCurrent(access.lease)) return candidate
        while (true) {
            val old = fences.load()
            old.firstOrNull { it.lease === access.lease && it.origin == access.origin &&
                it.boundary === boundary && it.store === access.store }?.let { return it }
            if (!fences.compareAndSet(old, old + candidate)) continue
            val subscription = boundary.onInvalidated(access.lease) {
                candidate.generation = Any(); candidate.record = null
                removeFence(candidate); candidate.subscription = null
            }
            if (boundary.isCurrent(access.lease) && candidate in fences.load()) candidate.subscription = subscription
            else { removeFence(candidate); subscription.close() }
            return candidate
        }
    }

    /** Invalidates only; it cannot grant readiness or replace the persisted draft. */
    fun invalidate() {
        if (closed || !boundary.isCurrent(access.lease)) return
        fence.generation = Any(); fence.dirty = true; fence.record = null
    }
    val isReady: Boolean get() = !closed && boundary.isCurrent(access.lease) &&
        !fence.dirty && !fence.uncertain && fence.record != null

    internal fun matches(controller: MealRequestController, candidate: AuthenticatedMealPlanningAccess,
        candidateBoundary: SessionBoundary) = owner === controller && boundary === candidateBoundary &&
        access === candidate

    internal fun begin(edit: Boolean): Attempt = Attempt(fence.generation, edit || (!fence.dirty && !fence.uncertain))
    internal fun beforeWrite(attempt: Attempt) {
        if (attempt.generation !== fence.generation) mealFail(FailureReason.CONFLICT)
        attempt.wrote = true; fence.uncertain = true; fence.record = null
    }
    internal fun observed(attempt: Attempt, record: PrivateRecord?) { attempt.observed = record }
    internal fun succeeded(attempt: Attempt) {
        if (closed || !boundary.isCurrent(access.lease) || attempt.generation !== fence.generation || !attempt.mayAcknowledge) return
        // A clean initial restore is an observation of retained state. It is never allowed to
        // release an earlier dirty/uncertain fence, which survives controller replacement.
        if (!attempt.wrote && (fence.dirty || fence.uncertain)) return
        fence.dirty = false; fence.uncertain = false; fence.record = attempt.observed
    }
    internal fun failed(attempt: Attempt) {
        if (attempt.wrote && attempt.generation === fence.generation &&
            (fence.record == null || attempt.observed?.let { same(it, fence.record!!) } == true)) {
            fence.uncertain = true; fence.record = null
        }
    }
    internal fun capture(): Ticket {
        if (!isReady) mealFail(if (!boundary.isCurrent(access.lease) || closed) FailureReason.STALE_SESSION else FailureReason.CONFLICT)
        return Ticket(fence.generation, fence.record ?: mealFail(FailureReason.CONFLICT))
    }
    internal fun check(ticket: Ticket, record: PrivateRecord) {
        if (!isReady || ticket.generation !== fence.generation || !same(ticket.record, record) ||
            fence.record?.let { same(it, record) } != true) mealFail(FailureReason.CONFLICT)
    }
    internal fun close() { closed = true /* fence remains until ACK or the actual lease invalidates */ }
    override fun toString() = "MealDraftReadiness(<redacted>)"
    private fun same(a: PrivateRecord, b: PrivateRecord) = a.revision == b.revision &&
        a.schemaVersion == b.schemaVersion && a.payload.copyForCodec().contentEquals(b.payload.copyForCodec())
    private companion object {
        val fences = AtomicReference<List<Fence>>(emptyList())
        fun removeFence(entry: Fence) { while (true) {
            val old = fences.load(); if (entry !in old || fences.compareAndSet(old, old.filterNot { it === entry })) return
        } }
    }
}
