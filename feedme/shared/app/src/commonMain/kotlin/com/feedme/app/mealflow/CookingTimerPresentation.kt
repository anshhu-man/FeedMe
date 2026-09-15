package com.feedme.app.mealflow

import com.feedme.core.ports.*
import com.feedme.kitchen.CookingAvailability
import com.feedme.kitchen.CookingStatus
import com.feedme.kitchen.CookingTimerTiming
import com.feedme.mealflow.CookingFlowPhase
import com.feedme.mealflow.CookingFlowState
import com.feedme.mealflow.timers.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** View consent only, not a native ticket or permission to rebase a changed timer. */
class CookingTimerRemoval internal constructor(internal val sessionId: String, internal val planId: String,
    internal val revision: Long, internal val timerId: String) {
    override fun toString() = "CookingTimerRemoval(<redacted>)"
}
internal class CookingTimerUiOwner(private val boundary: SessionBoundary, private val lease: SessionLease) {
    private val mutable = MutableStateFlow<CookingTimerRemoval?>(null)
    val states = mutable.asStateFlow()
    private var closed = false
    private val subscription = boundary.onInvalidated(lease) { mutable.value = null }
    fun present(session: String, plan: String, revision: Long, timer: String) {
        if (!closed && boundary.isCurrent(lease)) mutable.value = CookingTimerRemoval(session, plan, revision, timer)
    }
    fun owns(intent: CookingTimerRemoval) = !closed && boundary.isCurrent(lease) && mutable.value === intent
    fun dismiss() { mutable.value = null }
    fun close() { closed = true; dismiss(); subscription.close() }
}

/** Detached render data. Labels come only from an eligible pinned step, never an ID lookup guess. */
class CookingTimerRow internal constructor(val id: String, val label: String, val timing: String,
    val remaining: String, val alert: String, val status: String?, val cleanupAvailable: Boolean) {
    override fun toString() = "CookingTimerRow(private=<redacted>)"
}
class CookingTimerScreenState internal constructor(val visible: Boolean, val busy: Boolean, val eligible: Boolean,
    val stepLabel: String?, val durationText: String, rows: List<CookingTimerRow>,
    val canStart: Boolean, val canStop: Boolean, val pending: Boolean, val pendingTimerId: String?,
    val cleanupAcknowledged: Boolean, val issue: CookingTimerFlowIssue, val failure: FailureReason?, val due: Boolean) {
    val rows = rows.toList()
    override fun toString() = "CookingTimerScreenState(visible=$visible, private=<redacted>)"
    companion object {
        fun from(state: CookingTimerFlowState): CookingTimerScreenState {
            val pin = state.snapshot
            val eligible = pin?.availability == CookingAvailability.AVAILABLE && pin.originMatches
            val labels = if (eligible) pin?.steps.orEmpty().associate { it.stepId to it.instruction } else emptyMap()
            return CookingTimerScreenState(state.visible, state.busy, eligible,
                labels[state.selectedStepId], state.durationText, pin?.timers.orEmpty()
                    .filter { shouldRenderTimer(it, state.pendingAction?.timerId) }
                    .map { timerRow(it, labels, state.busy) },
                state.canStartOrResume && eligible, state.canStop && eligible,
                state.pendingAction != null, state.pendingAction?.timerId, state.pendingAction?.cancellationAcknowledged == true,
                state.issue, state.failureReason, state.due?.sessionId == pin?.sessionId && state.due != null)
        }
    }
}

/** QUIET without a canonical row is retained cleanup history, not an editable timer. Keep an
 * unresolved original action visible even if its later observation already looks quiet. The
 * ARMED-only acknowledgement flag is not a cleanup receipt and is deliberately not consulted.
 */
internal fun shouldRenderTimer(view: CookingTimerView, pendingTimerId: String?): Boolean =
    view.status != null || view.alertPhase != CookingTimerAlertPhase.QUIET || view.timerId == pendingTimerId

/** Hidden TIMER is intentionally not polled. Join a retained event to the actual cooking
 * controller, not that hidden page's old snapshot. This affects rendering only, not evidence.
 */
internal fun showCurrentTimerDue(timer: CookingTimerFlowState?, cooking: CookingFlowState): Boolean {
    val pin = cooking.cooking
    return timerDueMatchesCurrent(timer?.due?.sessionId, pin?.id, cooking.phase, pin?.progress?.status,
        pin?.availability, pin?.originMatches == true, pin != null && cooking.plan?.id == pin.plan.id,
        cooking.pending.any { it.operationId == "createCookSession" })
}
internal fun timerDueMatchesCurrent(dueSession: String?, currentSession: String?, phase: CookingFlowPhase,
    status: CookingStatus?, availability: CookingAvailability?, originMatches: Boolean,
    exactPlan: Boolean, pendingStart: Boolean): Boolean = dueSession != null && dueSession == currentSession &&
    phase == CookingFlowPhase.COOKING && status == CookingStatus.ACTIVE && availability == CookingAvailability.AVAILABLE &&
    originMatches && exactPlan && !pendingStart

internal fun timerRow(view: CookingTimerView, eligibleLabels: Map<String, String>, busy: Boolean): CookingTimerRow =
    CookingTimerRow(view.timerId, eligibleLabels[view.stepId] ?: "Pinned step label unavailable", timerTiming(view.timing?.timing),
        timerRemaining(view.timing?.remainingMillis), timerDelivery(view.deliveryPhase, view.alertAcknowledgedInThisOwner),
        view.status, view.alertPhase != CookingTimerAlertPhase.QUIET && !busy)

internal fun timerRemaining(millis: Long?): String {
    if (millis == null || millis < 0) return "Remaining time uncertain"
    val seconds = millis / 1000 + if (millis % 1000 > 0) 1 else 0
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} remaining (estimate)"
}
internal fun timerTiming(timing: CookingTimerTiming?) = when (timing) {
    CookingTimerTiming.RUNNING -> "Running on this device"
    CookingTimerTiming.PAUSED -> "Paused"
    CookingTimerTiming.DUE -> "Estimated time reached"
    CookingTimerTiming.UNCERTAIN, null -> "Timing uncertain"
}
internal fun timerDelivery(phase: CookingTimerDeliveryPhase, acknowledged: Boolean): String = when {
    !acknowledged -> "No acknowledged alert in this owner"
    else -> when (phase) {
        CookingTimerDeliveryPhase.UNAVAILABLE -> "Alert delivery unavailable"
        CookingTimerDeliveryPhase.NOT_INSTALLED -> "Alert not installed"
        CookingTimerDeliveryPhase.ENQUEUED -> "Foreground alert scheduled"
        CookingTimerDeliveryPhase.SUSPENDED -> "Alert waiting for foreground"
        CookingTimerDeliveryPhase.CHECKING -> "Rechecking alert eligibility"
        CookingTimerDeliveryPhase.DELIVERED -> "Due event delivered in this owner"
        CookingTimerDeliveryPhase.BLOCKED -> "Alert blocked by current checks"
        CookingTimerDeliveryPhase.CLOCK_UNCERTAIN -> "Alert clock continuity uncertain"
        CookingTimerDeliveryPhase.OUTCOME_UNKNOWN -> "Alert delivery outcome unknown"
        CookingTimerDeliveryPhase.CANCELLING -> "Alert cancellation unresolved"
    }
}

internal fun timerIssue(issue: CookingTimerFlowIssue): String? = when (issue) {
    CookingTimerFlowIssue.NONE -> null
    CookingTimerFlowIssue.ACTION_NOT_ACKNOWLEDGED -> "The original timer action may have changed local state. Its identity is retained; do not start a replacement. Alert cleanup is separate and does not acknowledge that action."
    CookingTimerFlowIssue.ALERT_CLEANUP_ACKNOWLEDGED -> "Exact alert cleanup was acknowledged. Any original unacknowledged timer action remains unresolved."
    CookingTimerFlowIssue.INVALID_DURATION -> "Enter a whole number of seconds from 1 to 86400. Nothing starts while you type."
    CookingTimerFlowIssue.CONTEXT_CHANGED -> "The pinned session, timer revision or eligibility changed. Refresh the observation; no command is silently rebased."
    CookingTimerFlowIssue.SESSION_UNAVAILABLE -> "The private session changed. Timer details are hidden."
}
