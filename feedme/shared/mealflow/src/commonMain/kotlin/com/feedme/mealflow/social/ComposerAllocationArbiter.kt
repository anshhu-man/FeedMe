package com.feedme.mealflow.social

import com.feedme.core.ports.*
import com.feedme.mealflow.*
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Opaque actual shared reservation, not an ID, review, write permit or acknowledgement. */
internal class ComposerAllocationSlot internal constructor() {
    override fun toString() = "ComposerAllocationSlot(<redacted>)"
}

/** ONE concrete atomic arbitration point for the two reviewed composer ID-allocation lanes.
 * Both real registries reserve here BEFORE calling their suspending ID provider, then install
 * their richer exact local reservation without another await. A slot lasts through returned
 * unregistered original, uncertain enqueue and abandoned-but-unsettled provider invocation.
 * Release is only exact durable registration or explicit unreturned abandonment AFTER actual
 * provider settlement (or a known local installation failure before provider invocation).
 *
 * One slot per purpose and no shared root across purposes are checked in the SAME CAS. Thus
 * two unrelated roots may progress, but same-root Save/Publish cannot both pass checks before
 * suspending. This does not lock unrelated local edits, allocate IDs, own a queue, read a store,
 * or grant admission. Callers still enforce actual current lease/permit and full row capacity.
 * ALL APIs run on the owning serialized identity dispatcher: boundary currentness, listener
 * registration and listener disposal are not arbitrary-thread APIs. The CAS is shared state
 * arbitration, not a promise that reserve/release may be called from a Job cancellation thread.
 * Such callbacks only mark their operation cancelled; registry cleanup occurs later on the
 * identity dispatcher (or its same-dispatcher boundary invalidation callback).
 */
@OptIn(ExperimentalAtomicApi::class)
internal object ComposerAllocationArbiter {
    private enum class Purpose { REVIEWED_SAVE, PUBLICATION }
    private class Claim(val token: ComposerAllocationSlot, val access: AuthenticatedMealPlanningAccess,
        val boundary: SessionBoundary, val root: String, val purpose: Purpose) {
        val store = access.store
        val lease = access.lease
        val origin = access.origin
        val environment = access.lease.scope.environment
        var subscription: SessionInvalidationSubscription? = null
        fun matches(actual: AuthenticatedMealPlanningAccess, actualBoundary: SessionBoundary): Boolean =
            access === actual && store === actual.store && lease === actual.lease && boundary === actualBoundary &&
                origin == actual.origin && environment == actual.lease.scope.environment
    }
    private val claims = AtomicReference<List<Claim>>(emptyList())

    fun reserveReviewedSave(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary,
        clientDraftId: String): ComposerAllocationSlot = reserve(access, boundary, clientDraftId, Purpose.REVIEWED_SAVE)
    fun reservePublication(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary,
        clientDraftId: String): ComposerAllocationSlot = reserve(access, boundary, clientDraftId, Purpose.PUBLICATION)

    /** Pure exact-session/root exclusion data; success is not currentness or permission. The
     * actual reserve still performs simultaneous arbitration, never trusts a prior false read. */
    fun hasReviewedSave(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary, clientDraftId: String): Boolean =
        has(access, boundary, clientDraftId, Purpose.REVIEWED_SAVE)
    fun hasPublication(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary, clientDraftId: String): Boolean =
        has(access, boundary, clientDraftId, Purpose.PUBLICATION)

    /** Only the exact returned slot can be removed; a constructor lookalike does nothing.
     * Registry owners call this in a no-await tail at the documented terminal evidence point.
     * Idempotence prevents double disposal or a stale old slot releasing its successor. */
    fun release(slot: ComposerAllocationSlot) {
        while (true) {
            val before = claims.load()
            val found = before.singleOrNull { it.token === slot } ?: return
            if (claims.compareAndSet(before, before - found)) {
                found.subscription?.close(); found.subscription = null
                return
            }
        }
    }

    private fun reserve(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary,
        clientDraftId: String, purpose: Purpose): ComposerAllocationSlot {
        val root = publicationRecordId(clientDraftId)
        if (!boundary.isCurrent(access.lease)) mealFail(FailureReason.STALE_SESSION)
        val next = Claim(ComposerAllocationSlot(), access, boundary, root, purpose)
        while (true) {
            val before = claims.load()
            if (before.any { it.matches(access, boundary) && (it.purpose == purpose || it.root == root) })
                mealFail(FailureReason.CONFLICT)
            if (claims.compareAndSet(before, before + next)) break
        }
        val installed = boundary.onInvalidated(access.lease) { release(next.token) }
        if (!boundary.isCurrent(access.lease) || claims.load().none { it === next }) {
            installed.close(); release(next.token); mealFail(FailureReason.STALE_SESSION)
        }
        next.subscription = installed
        // A concurrent invalidation/release can win between the first check and assignment.
        // Recheck after publishing the handle so that branch cannot leak its listener.
        if (!boundary.isCurrent(access.lease) || claims.load().none { it === next }) {
            installed.close(); next.subscription = null; release(next.token); mealFail(FailureReason.STALE_SESSION)
        }
        return next.token
    }
    private fun has(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary, root: String, purpose: Purpose): Boolean =
        claims.load().any { it.matches(access, boundary) && it.root == root && it.purpose == purpose }
}
