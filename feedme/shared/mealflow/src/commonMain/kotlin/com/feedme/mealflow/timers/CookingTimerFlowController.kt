package com.feedme.mealflow.timers

import com.feedme.core.ports.*
import com.feedme.kitchen.CookingTimerAction
import com.feedme.mealflow.CookingFlowController
import com.feedme.mealflow.MealOperationIds
import com.feedme.session.PrivateSessionAccess
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

/** Retained timer-page owner on the actual session dispatcher. Borrows the real cooking
 * controller/facade; no raw store, accepting gate, transport, scheduler or timer engine is created.
 * Back/tick never write. Failed attempted mutations retain their original identity; cleanup does
 * not promote the earlier result into success. There is no generic unknown-action replay API.
 */
class CookingTimerFlowController private constructor(
    private val access: PrivateSessionAccess, private val boundary: SessionBoundary,
    private val dispatcher: CoroutineDispatcher, private val timers: SessionCookingTimers,
    private val cooking: CookingFlowController, private val ids: MealOperationIds,
) {
    private val mutex = Mutex()
    // Internal coherent-read completion, not a mutation/durability or caller receipt. Return-only
    // cancellation cannot undo the read another explicit action independently revalidates.
    private class ReadAdmission { var succeeded = false }
    private var activeRead: ReadAdmission? = null
    private var waitingAction: Any? = null
    private var generation = Any()
    private var closed = false
    private var visible = false
    private var busy = false
    private var observed: CookingTimerSnapshot? = null
    private var selectedSession: String? = null
    private var step: String? = null
    private var duration = ""
    private var pending: CookingTimerPendingAction? = null
    private val due = CookingTimerDueObservation()
    private var issue = CookingTimerFlowIssue.NONE
    private var failure: FailureReason? = null
    private val mutable = MutableStateFlow(CookingTimerFlowState.empty())
    val states: StateFlow<CookingTimerFlowState> = mutable.asStateFlow()
    private val subscription = boundary.onInvalidated(access.lease) { closed = true; redact() }

    suspend fun open(sessionId: String, stepId: String? = null): PortResult<CookingTimerFlowState> = operation { token ->
        val id = CookingTimerLedger.id(sessionId)
        if (pending?.sessionId?.let { it != id } == true) fail(FailureReason.CONFLICT)
        val next = fresh(id, token)
        val selected = stepId ?: next.currentStepId
        if (next.steps.isNotEmpty() && next.steps.none { it.stepId == selected }) fail(FailureReason.CONFLICT)
        observed = next; selectedSession = next.sessionId; step = selected; visible = true
        duration = next.steps.singleOrNull { it.stepId == selected }?.suggestedDurationSeconds?.toString().orEmpty()
        publish()
    }

    /** Coalesces ticks while an action/read is in flight. Hidden pages cause no storage reads. */
    suspend fun tick(): PortResult<CookingTimerFlowState> = operation(coalesce = true) { token ->
        if (visible) selectedSession?.let { observed = fresh(it, token) }
        publish()
    }

    suspend fun setDurationText(text: String): PortResult<CookingTimerFlowState> = operation { _ ->
        if (!visible || pending != null || text.length > 16) fail(FailureReason.CONFLICT)
        duration = text; publish()
    }

    suspend fun start(): PortResult<CookingTimerFlowState> = change(CookingTimerChangeKind.START, null)
    suspend fun pause(timerId: String): PortResult<CookingTimerFlowState> = change(CookingTimerChangeKind.PAUSE, timerId)
    suspend fun resume(timerId: String): PortResult<CookingTimerFlowState> = change(CookingTimerChangeKind.RESUME, timerId)
    suspend fun reset(timerId: String): PortResult<CookingTimerFlowState> = change(CookingTimerChangeKind.RESET, timerId)
    /** Caller obtains explicit removal confirmation before invoking this exact selected action. */
    suspend fun cancel(timerId: String): PortResult<CookingTimerFlowState> = change(CookingTimerChangeKind.CANCEL, timerId)

    /** Exact alert cleanup is separate from the original domain result. No command/new ID/send.
     * The original unresolved action remains visible even after cleanup acknowledges.
     */
    suspend fun cancelAlert(timerId: String): PortResult<CookingTimerFlowState> = operation { token ->
        val snapshot = observed
        val sessionId = snapshot?.sessionId ?: pending?.sessionId ?: fail(FailureReason.CONFLICT)
        val id = CookingTimerLedger.id(timerId)
        if (!visible || (snapshot?.timers?.none { it.timerId == id } != false && pending?.timerId != id)) fail(FailureReason.CONFLICT)
        due.invalidate(sessionId, id); publish()
        val lifetime = CookingTimerFlowLifetime { requireLive(); if (generation !== token) fail(FailureReason.STALE_SESSION) }
        val result = withContext(lifetime) { timers.cancelAlert(sessionId, id) }; check(token); value(result)
        observed = fresh(sessionId, token)
        pending?.takeIf { it.sessionId == sessionId && it.timerId == id }?.let { pending = it.cancelled() }
        issue = CookingTimerFlowIssue.ALERT_CLEANUP_ACKNOWLEDGED; failure = null; publish()
    }

    /** Immediate navigation and local operation fence, not pause/cancel/discard or owner close. */
    suspend fun back(): PortResult<CookingTimerFlowState> = withContext(dispatcher) {
        currentCoroutineContext().ensureActive(); requireLive()
        generation = Any(); visible = false; busy = false; PortResult.Value(publish())
    }

    /** Called synchronously ONLY by the actual native runtime-gated due effect, on this owner
     * dispatcher. Navigation does not revoke an acknowledged timer. No automatic domain write.
     */
    fun onDue(identity: CookingTimerDue): PortResult<Unit> {
        if (closed || !boundary.isCurrent(access.lease)) return PortResult.Failure(FailureReason.STALE_SESSION)
        due.record(identity); publish(); return PortResult.Value(Unit)
    }

    /** Permanently fences this claimed UI owner, but does not close/retire borrowed resources. */
    suspend fun close(): PortResult<Unit> = withContext(dispatcher) {
        closed = true; redact(); subscription.close(); PortResult.Value(Unit)
    }

    private suspend fun change(kind: CookingTimerChangeKind, timerId: String?): PortResult<CookingTimerFlowState> = operation { token ->
        if (!visible || pending != null) fail(FailureReason.CONFLICT)
        val prior = observed ?: fail(FailureReason.CONFLICT)
        val selectedStep = step ?: fail(FailureReason.CONFLICT)
        val seconds = if (kind == CookingTimerChangeKind.START) CookingTimerDurationInput.seconds(duration)
            ?: run { issue = CookingTimerFlowIssue.INVALID_DURATION; fail(FailureReason.INVALID_DATA) } else null
        val current = fresh(prior.sessionId, token)
        if (current.localRevision != prior.localRevision || current.planId != prior.planId ||
            current.recipeVersionId != prior.recipeVersionId) fail(FailureReason.CONFLICT)
        // Advisory rejection before allocating IDs; the fixed cooking admission independently
        // repeats its actual selection/preferences checks before mutation and native scheduling.
        if (kind in setOf(CookingTimerChangeKind.START, CookingTimerChangeKind.RESUME)) {
            if (!cooking.states.value.canEdit) fail(FailureReason.CONFLICT)
        } else if (!cooking.states.value.canStop) fail(FailureReason.CONFLICT)
        val id = if (kind == CookingTimerChangeKind.START) newId(token) else CookingTimerLedger.id(checkNotNull(timerId))
        if (kind != CookingTimerChangeKind.START && current.timers.none { it.timerId == id }) fail(FailureReason.NOT_FOUND)
        due.invalidate(current.sessionId, id)
        val command = newId(token)
        if (command == id) fail(FailureReason.CONFLICT)
        val action = when (kind) {
            CookingTimerChangeKind.START -> CookingTimerAction.Start(id, selectedStep, checkNotNull(seconds))
            CookingTimerChangeKind.PAUSE -> CookingTimerAction.Pause(id)
            CookingTimerChangeKind.RESUME -> CookingTimerAction.Resume(id)
            CookingTimerChangeKind.RESET -> CookingTimerAction.Reset(id)
            CookingTimerChangeKind.CANCEL -> CookingTimerAction.Cancel(id)
        }
        val retained = CookingTimerPendingAction(current.sessionId, id, current.localRevision, command, kind)
        val lifetime = CookingTimerFlowLifetime { requireLive(); if (generation !== token) fail(FailureReason.STALE_SESSION) }
        pending = retained; publish()
        try {
            val result = withContext(lifetime) { cooking.applyTimerAction(timers, current.sessionId, current.localRevision, command, action) }
            check(token)
            val acknowledged = value(result)
            if (acknowledged.sessionId != current.sessionId || acknowledged.planId != current.planId ||
                acknowledged.localRevision <= current.localRevision) fail(FailureReason.CONFLICT)
            observed = acknowledged; pending = null; issue = CookingTimerFlowIssue.NONE; failure = null
        } catch (error: Exception) {
            if (!lifetime.mutationAttempted) pending = null
            else { pending = retained; issue = CookingTimerFlowIssue.ACTION_NOT_ACKNOWLEDGED }
            throw error
        }
        publish()
    }

    private suspend fun fresh(sessionId: String, token: Any): CookingTimerSnapshot {
        check(token); val result = cooking.inspectTimers(timers, sessionId); check(token); return value(result)
    }
    private suspend fun newId(token: Any): String { check(token); val result = ids.next(); check(token); return CookingTimerLedger.id(result) }
    private fun requireLive() { if (closed || !boundary.isCurrent(access.lease)) fail(FailureReason.STALE_SESSION) }
    private suspend fun check(token: Any) { currentCoroutineContext().ensureActive(); requireLive(); if (generation !== token) fail(FailureReason.STALE_SESSION) }
    private fun publish(): CookingTimerFlowState {
        if (closed || !boundary.isCurrent(access.lease)) return CookingTimerFlowState.unavailable().also { mutable.value = it }
        return CookingTimerFlowState(visible, busy, observed, step, duration, pending, due.current(observed),
            visible && !busy && pending == null && cooking.states.value.canEdit,
            visible && !busy && pending == null && cooking.states.value.canStop,
            issue, failure).also { mutable.value = it }
    }
    private fun redact() { generation = Any(); visible = false; busy = false; observed = null; selectedSession = null; step = null; duration = ""; due.clear(); mutable.value = CookingTimerFlowState.unavailable() }

    /** Immutable UI intent context, captured before waiting for a read. Method arguments (action
     * kind/target/session/text) are already immutable closure values. A refreshed observation may
     * change clock/delivery estimates, but never rebase this action's revision, pin or input. */
    private class IntentContext(val visible: Boolean, val session: String?, val step: String?, val duration: String,
        val pending: CookingTimerPendingAction?, val snapshot: CookingTimerSnapshot?)
    private fun captureIntent() = IntentContext(visible, selectedSession, step, duration, pending, observed)
    private fun matchesIntent(intent: IntentContext): Boolean = visible == intent.visible && selectedSession == intent.session &&
        step == intent.step && duration == intent.duration && pending === intent.pending && samePin(intent.snapshot, observed)
    private fun samePin(a: CookingTimerSnapshot?, b: CookingTimerSnapshot?): Boolean {
        if (a == null || b == null) return a === b
        return a.sessionId == b.sessionId && a.localRevision == b.localRevision && a.planId == b.planId &&
            a.recipeVersionId == b.recipeVersionId && a.currentStepId == b.currentStepId && a.availability == b.availability &&
            a.status == b.status && a.originMatches == b.originMatches && a.pendingCommandCount == b.pendingCommandCount &&
            a.steps.map { Triple(it.stepId, it.instruction, it.suggestedDurationSeconds) } ==
                b.steps.map { Triple(it.stepId, it.instruction, it.suggestedDurationSeconds) } &&
            a.timers.map { listOf(it.timerId, it.generation, it.stepId, it.status, it.durationSeconds, it.alertPhase, it.alertAcknowledgedInThisOwner) } ==
                b.timers.map { listOf(it.timerId, it.generation, it.stepId, it.status, it.durationSeconds, it.alertPhase, it.alertAcknowledgedInThisOwner) }
    }
    private suspend fun operation(coalesce: Boolean = false, action: suspend (Any) -> CookingTimerFlowState): PortResult<CookingTimerFlowState> {
        var admitted: Any? = null
        val result = withContext(dispatcher) {
            currentCoroutineContext().ensureActive()
            try { requireLive() } catch (e: TimerFailure) { return@withContext PortResult.Failure(e.reason) }
            if (waitingAction != null) return@withContext if (coalesce) PortResult.Value(mutable.value) else PortResult.Failure(FailureReason.CONFLICT)
            val token = generation
            val intent = if (coalesce) null else captureIntent()
            var locked = false
            var reservation: Any? = null
            var read: ReadAdmission? = null
            try {
                locked = mutex.tryLock()
                if (!locked) {
                    if (coalesce) return@withContext PortResult.Value(mutable.value)
                    val predecessor = activeRead ?: return@withContext PortResult.Failure(FailureReason.CONFLICT)
                    reservation = Any(); waitingAction = reservation; busy = true; publish()
                    // Never cancel the read or wait behind another mutation. One reserved explicit
                    // action owns this wait; cancellation releases only its reservation.
                    mutex.lock(); locked = true
                    check(token)
                    if (!predecessor.succeeded || !matchesIntent(checkNotNull(intent))) fail(FailureReason.CONFLICT)
                    waitingAction = null
                }
                admitted = token
                if (coalesce) { read = ReadAdmission(); activeRead = read }
                busy = !coalesce
                if (!coalesce) publish()
                check(token); val result = action(token); check(token)
                read?.succeeded = true
                PortResult.Value(result)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: TimerFailure) {
                if (token === generation && !closed && boundary.isCurrent(access.lease)) {
                    failure = e.reason
                    if (e.reason in setOf(FailureReason.STALE_SESSION, FailureReason.FORBIDDEN, FailureReason.CONFLICT)) observed = null
                    if (pending != null) issue = CookingTimerFlowIssue.ACTION_NOT_ACKNOWLEDGED
                    else if (issue != CookingTimerFlowIssue.INVALID_DURATION) issue = CookingTimerFlowIssue.CONTEXT_CHANGED
                    publish()
                }
                PortResult.Failure(e.reason)
            }
            catch (_: Exception) { failure = FailureReason.STORAGE_FAILURE; publish(); PortResult.Failure(FailureReason.STORAGE_FAILURE) }
            finally {
                if (reservation != null && waitingAction === reservation) waitingAction = null
                if (locked) {
                    if (activeRead === read) activeRead = null
                    busy = token === generation && waitingAction != null
                    mutex.unlock(); publish()
                } else if (reservation != null) {
                    busy = false; publish()
                }
            }
        }
        currentCoroutineContext().ensureActive()
        return if (closed || !boundary.isCurrent(access.lease) || (admitted != null && generation !== admitted)) PortResult.Failure(FailureReason.STALE_SESSION)
            else if (result is PortResult.Value) PortResult.Value(mutable.value) else result
    }

    companion object {
        /** No storage creation/identity allocation. The facade can be claimed by one retained UI
         * owner only, so closing/replacing an editor cannot discard an unknown action barrier.
         */
        suspend fun create(access: PrivateSessionAccess, boundary: SessionBoundary, dispatcher: CoroutineDispatcher,
            timers: SessionCookingTimers, cooking: CookingFlowController, ids: MealOperationIds): PortResult<CookingTimerFlowController> {
            var created: CookingTimerFlowController? = null
            try {
                val result = withContext(dispatcher) {
                currentCoroutineContext().ensureActive()
                if (!boundary.isCurrent(access.lease) || !timers.matchesComposition(access.store, boundary, access.scope, access.originBinding) ||
                    !cooking.matchesTimerComposition(timers)) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
                when (val claimed = timers.claimFlow()) {
                    is PortResult.Failure -> claimed
                    is PortResult.Value -> PortResult.Value(CookingTimerFlowController(access, boundary, dispatcher, timers, cooking, ids).also { created = it })
                }
            }
                currentCoroutineContext().ensureActive()
                return if (created?.closed == true || !boundary.isCurrent(access.lease)) PortResult.Failure(FailureReason.STALE_SESSION) else result
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + dispatcher) { created?.close() }
                throw cancelled
            }
        }
    }
}
