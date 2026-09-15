package com.feedme.mealflow.social

import com.feedme.core.ports.*
import com.feedme.mealflow.*
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Bounded actual allocation retention, NOT queue/commit/consent/receipt authority. Only one
 * allocation may be unresolved for an exact retained lease/store/boundary/raw origin. A slot
 * is reserved before the suspending ID provider; cancellation cannot silently allocate again.
 * No returned ID is invented. Unknown provider completion remains explicitly unresolved.
 * Controller replacement preserves this evidence; actual lease invalidation clears it. */
@OptIn(ExperimentalAtomicApi::class)
internal object ReviewedDraftSaveHeld {
    class Allocation internal constructor(val clientId: String, val localRevision: Long, internal val sharedSlot: ComposerAllocationSlot) {
        private sealed interface State
        private class Awaiting(val settled: Boolean) : State
        private class Returned(val original: PostDraftCommandV2.ReviewedPatch, val enqueueMayHaveBegun: Boolean) : State
        private class Abandoned(val settled: Boolean) : State
        private val retained = AtomicReference<State>(Awaiting(false))
        val abandonToken = PreparedReviewedDraftAllocationAbandon()
        val original: PostDraftCommandV2.ReviewedPatch? get() = (retained.load() as? Returned)?.original
        val awaiting: Boolean get() = retained.load() is Awaiting
        val canInvokeProvider: Boolean get() = (retained.load() as? Awaiting)?.settled == false
        val abandoned: Boolean get() = retained.load() is Abandoned
        internal fun capture(value: PostDraftCommandV2.ReviewedPatch): Boolean {
            val prior = retained.load() as? Awaiting ?: return false
            return !prior.settled && value.clientId == clientId && value.localRevision == localRevision &&
                retained.compareAndSet(prior, Returned(value, false))
        }
        internal fun abandon(): Boolean {
            val prior = retained.load() as? Awaiting ?: return false
            return retained.compareAndSet(prior, Abandoned(prior.settled))
        }
        internal fun settled(): Boolean {
            while (true) {
                val prior = retained.load()
                val next = when (prior) {
                    is Awaiting -> Awaiting(true)
                    is Abandoned -> Abandoned(true)
                    is Returned -> return false
                }
                if (retained.compareAndSet(prior, next)) return next is Abandoned
            }
        }
        internal fun removable() = (retained.load() as? Abandoned)?.settled == true
        internal fun beginEnqueue(original: PostDraftCommandV2.ReviewedPatch) {
            while (true) {
                val prior = retained.load() as? Returned ?: mealFail(FailureReason.CONFLICT)
                if (prior.original !== original) mealFail(FailureReason.CONFLICT)
                if (prior.enqueueMayHaveBegun || retained.compareAndSet(prior, Returned(original, true))) return
            }
        }
        override fun toString() = "ReviewedDraftAllocation(<redacted>)"
    }
    private class Held(val access: AuthenticatedMealPlanningAccess, val boundary: SessionBoundary, val allocation: Allocation) {
        var subscription: SessionInvalidationSubscription? = null
        fun matches(actual: AuthenticatedMealPlanningAccess, actualBoundary: SessionBoundary) =
            access === actual && access.lease === actual.lease && access.store === actual.store &&
                access.origin == actual.origin && boundary === actualBoundary && access.lease.scope.environment == actual.lease.scope.environment
    }
    private val retained = AtomicReference<List<Held>>(emptyList())
    private fun find(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary) =
        retained.load().singleOrNull { it.matches(access, boundary) }
    fun pending(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary): Allocation? = find(access, boundary)?.allocation
    fun reserve(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary, candidate: PostDraftCommandV2.ReviewedPatch): Allocation {
        if (!boundary.isCurrent(access.lease)) mealFail(FailureReason.STALE_SESSION)
        val slot = ComposerAllocationArbiter.reserveReviewedSave(access, boundary, candidate.clientId)
        // Install the richer reservation without an await after shared cross-family arbitration.
        // If local installation fails before provider invocation, release this exact shared slot.
        try {
        while (true) {
            val old = retained.load()
            if (old.any { it.matches(access, boundary) }) mealFail(FailureReason.CONFLICT)
            val next = Held(access, boundary, Allocation(candidate.clientId, candidate.localRevision, slot))
            if (!retained.compareAndSet(old, old + next)) continue
            val subscription = boundary.onInvalidated(access.lease) { clear(access, boundary) }
            if (!boundary.isCurrent(access.lease) || next !in retained.load()) {
                clear(access, boundary); subscription.close(); mealFail(FailureReason.STALE_SESSION)
            }
            next.subscription = subscription
            if (!boundary.isCurrent(access.lease) || next !in retained.load()) {
                clear(access, boundary); subscription.close(); mealFail(FailureReason.STALE_SESSION)
            }
            return next.allocation
        }
        } catch (failure: Throwable) { ComposerAllocationArbiter.release(slot); throw failure }
    }
    /** Called immediately after an actually returned ID, before another await/currentness
     * check. The slot remains even if its caller was cancelled; it grants no dispatch right. */
    fun capture(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary, allocation: Allocation,
        original: PostDraftCommandV2.ReviewedPatch): Boolean {
        if (!boundary.isCurrent(access.lease) || find(access, boundary)?.allocation !== allocation) mealFail(FailureReason.STALE_SESSION)
        return allocation.capture(original)
    }
    fun abandon(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary, token: PreparedReviewedDraftAllocationAbandon): Boolean {
        if (!boundary.isCurrent(access.lease)) mealFail(FailureReason.STALE_SESSION)
        val allocation = find(access, boundary)?.allocation ?: return false
        if (allocation.abandonToken !== token || !allocation.abandon()) return false
        if (allocation.removable()) release(access, boundary, allocation)
        return true
    }
    fun sourceSettled(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary, allocation: Allocation) {
        if (find(access, boundary)?.allocation === allocation && allocation.settled()) release(access, boundary, allocation)
    }
    fun beginEnqueue(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary,
        allocation: Allocation, original: PostDraftCommandV2.ReviewedPatch) {
        if (!boundary.isCurrent(access.lease) || find(access, boundary)?.allocation !== allocation) mealFail(FailureReason.STALE_SESSION)
        allocation.beginEnqueue(original)
    }
    /** Only the controller's exact domain+queue observation may retire registration evidence.
     * This never marks delivery; the durable original and actual held-apply proofs take over. */
    fun registered(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary, allocation: Allocation) {
        if (allocation.original == null) mealFail(FailureReason.CONFLICT)
        release(access, boundary, allocation)
    }
    private fun release(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary, allocation: Allocation) {
        while (true) {
            val old = retained.load()
            val match = old.singleOrNull { it.matches(access, boundary) && it.allocation === allocation } ?: return
            if (retained.compareAndSet(old, old - match)) {
                ComposerAllocationArbiter.release(match.allocation.sharedSlot); match.subscription?.close(); return
            }
        }
    }
    private fun clear(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary) {
        while (true) {
            val old = retained.load(); val matching = old.filter { it.matches(access, boundary) }
            if (matching.isEmpty()) return
            if (retained.compareAndSet(old, old - matching.toSet())) {
                matching.forEach { ComposerAllocationArbiter.release(it.allocation.sharedSlot); it.subscription?.close() }; return
            }
        }
    }
}
