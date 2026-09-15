package com.feedme.mealflow.social

import com.feedme.core.ports.*
import com.feedme.mealflow.*
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Current-format evidence retains the complete snapshot, not a text-only projection. The
 * predecessor list contains only actually witnessed candidates, collapsed at the next read.
 * Construction/registry membership does not establish commit, archive or caller delivery. */
internal class PostDraftCurrentEdit(val proposed: DraftLocalSnapshotV1, predecessors: List<DraftLocalSnapshotV1?>) {
    /** Purpose label only. It allows a NEW explicitly reviewed retention transaction after
     * an uncertain retention attempt, never reconstruction of its acknowledgement. */
    var isRestoredRetention: Boolean = false
        private set
    companion object {
        fun restoredRetention(snapshot: DraftLocalSnapshotV1) =
            PostDraftCurrentEdit(snapshot, listOf(snapshot)).also { it.isRestoredRetention = true }
    }
    private var retainedPredecessors = bounded(predecessors)
    var predecessors: List<DraftLocalSnapshotV1?>
        get() = retainedPredecessors.toList()
        set(value) { retainedPredecessors = bounded(value) }
    var mutation: StoreMutation.Put? = null
    var delivery: PostDraftCurrentDelivery? = null
    private fun bounded(values: List<DraftLocalSnapshotV1?>): List<DraftLocalSnapshotV1?> {
        if (values.size !in 1..2) mealFail(FailureReason.CONFLICT)
        return values.toList()
    }
    override fun toString() = "PostDraftCurrentEdit(<redacted>)"
}

/** Only an actual owner-prepared schema2 mutation and the actual queue archive may be held.
 * The owner still checks both exact bytes/revisions on each explicit finalization attempt. */
internal class PostDraftCurrentApply(val original: PostDraftCommandV2, val mutation: StoreMutation.Put,
    val receiptRevision: Long, val unsent: Boolean) {
    var archive: StoreMutation.Put? = null
    var delivery: PostDraftCurrentDelivery? = null
    override fun toString() = "PostDraftCurrentApply(<redacted>)"
}

/** Separate typed current-format caller-tail ticket. No suspension, queue interpretation,
 * schema conversion or acknowledgement from a decoded completion is possible here. */
@OptIn(ExperimentalAtomicApi::class)
internal class PostDraftCurrentDelivery {
    private sealed interface State
    private data object Pending : State
    private class Armed(val edit: PostDraftCurrentEdit?, val apply: PostDraftCurrentApply?, val publication: Any) : State
    private class Delivered(val edit: PostDraftCurrentEdit?, val apply: PostDraftCurrentApply?) : State
    private data object Revoked : State
    private val state = AtomicReference<State>(Pending)
    fun arm(edit: PostDraftCurrentEdit?, apply: PostDraftCurrentApply?, publication: Any) =
        state.compareAndSet(Pending, Armed(edit, apply, publication))
    fun deliver(edit: PostDraftCurrentEdit?, apply: PostDraftCurrentApply?, publication: Any): Boolean {
        val current = state.load() as? Armed ?: return false
        return current.edit === edit && current.apply === apply && current.publication === publication &&
            state.compareAndSet(current, Delivered(edit, apply))
    }
    fun delivered(edit: PostDraftCurrentEdit) = (state.load() as? Delivered)?.edit === edit
    fun delivered(apply: PostDraftCurrentApply) = (state.load() as? Delivered)?.apply === apply
    fun revoke() { while (true) {
        val old = state.load()
        if (old is Delivered || old === Revoked || state.compareAndSet(old, Revoked)) return
    } }
}

/** Exact lease/store/boundary/raw-origin registry. Controller close is not lease invalidation.
 * This is evidence retention only; readback, fresh changed CAS and caller fences stay mandatory.
 * Observation/redaction data deliberately remains in the existing shared PostDraftHeld registry. */
@OptIn(ExperimentalAtomicApi::class)
internal object PostDraftCurrentHeld {
    private class Held(val access: AuthenticatedMealPlanningAccess, val boundary: SessionBoundary) {
        val edit = AtomicReference<PostDraftCurrentEdit?>(null)
        val apply = AtomicReference<PostDraftCurrentApply?>(null)
        var subscription: SessionInvalidationSubscription? = null
        fun matches(actual: AuthenticatedMealPlanningAccess, actualBoundary: SessionBoundary) =
            access.lease === actual.lease && access.store === actual.store && access.origin == actual.origin && boundary === actualBoundary
    }
    private val held = AtomicReference<List<Held>>(emptyList())
    private fun find(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary) =
        held.load().singleOrNull { it.matches(access, boundary) }
    private fun holder(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary): Held {
        if (!boundary.isCurrent(access.lease)) mealFail(FailureReason.STALE_SESSION)
        while (true) {
            val old = held.load()
            old.singleOrNull { it.matches(access, boundary) }?.let { return it }
            val next = Held(access, boundary)
            if (!held.compareAndSet(old, old + next)) continue
            val subscription = boundary.onInvalidated(access.lease) { clear(access, boundary) }
            if (boundary.isCurrent(access.lease) && next in held.load()) next.subscription = subscription
            else { clear(access, boundary); subscription.close(); mealFail(FailureReason.STALE_SESSION) }
            return next
        }
    }
    fun edit(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary) = find(access, boundary)?.edit?.load()
    fun apply(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary) = find(access, boundary)?.apply?.load()
    fun retainEdit(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary, value: PostDraftCurrentEdit) {
        holder(access, boundary).edit.store(value)
    }
    fun retainApply(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary, value: PostDraftCurrentApply) {
        holder(access, boundary).apply.store(value)
    }
    fun clearEdit(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary, value: PostDraftCurrentEdit) {
        find(access, boundary)?.edit?.compareAndSet(value, null)
    }
    fun clearApply(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary, value: PostDraftCurrentApply) {
        find(access, boundary)?.apply?.compareAndSet(value, null)
    }
    fun clear(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary) { while (true) {
        val old = held.load(); val removed = old.filter { it.matches(access, boundary) }
        if (removed.isEmpty()) return
        if (held.compareAndSet(old, old - removed.toSet())) { removed.forEach { it.subscription?.close() }; return }
    } }
}
