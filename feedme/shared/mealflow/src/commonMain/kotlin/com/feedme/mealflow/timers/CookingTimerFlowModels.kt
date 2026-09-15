package com.feedme.mealflow.timers

import com.feedme.core.ports.FailureReason
import com.feedme.kitchen.CookingTimerReducer
import com.feedme.kitchen.CookingAvailability
import com.feedme.kitchen.CookingStatus

enum class CookingTimerFlowIssue { NONE, ACTION_NOT_ACKNOWLEDGED, ALERT_CLEANUP_ACKNOWLEDGED,
    INVALID_DURATION, CONTEXT_CHANGED, SESSION_UNAVAILABLE }
enum class CookingTimerChangeKind { START, PAUSE, RESUME, RESET, CANCEL }

/** Original explicit identity is retained after any attempted but unacknowledged mutation.
 * This is not a retry/rebase token or proof that the command succeeded. Do not display IDs.
 */
class CookingTimerPendingAction internal constructor(val sessionId: String, val timerId: String,
    val expectedLocalRevision: Long, val commandId: String, val kind: CookingTimerChangeKind,
    val cancellationAcknowledged: Boolean = false) {
    override fun toString() = "CookingTimerPendingAction(kind=$kind, private=<redacted>)"
    internal fun cancelled() = CookingTimerPendingAction(sessionId, timerId, expectedLocalRevision,
        commandId, kind, true)
}

class CookingTimerFlowState internal constructor(val visible: Boolean, val busy: Boolean,
    val snapshot: CookingTimerSnapshot?, val selectedStepId: String?, val durationText: String,
    val pendingAction: CookingTimerPendingAction?, val due: CookingTimerDue?,
    val canStartOrResume: Boolean, val canStop: Boolean, val issue: CookingTimerFlowIssue,
    val failureReason: FailureReason? = null) {
    override fun toString() = "CookingTimerFlowState(visible=$visible, busy=$busy, issue=$issue, private=<redacted>)"
    internal companion object {
        fun empty() = CookingTimerFlowState(false, false, null, null, "", null, null, false, false, CookingTimerFlowIssue.NONE)
        fun unavailable() = CookingTimerFlowState(false, false, null, null, "", null, null, false, false,
            CookingTimerFlowIssue.SESSION_UNAVAILABLE, FailureReason.STALE_SESSION)
    }
}

/** UI syntax only. The existing reducer remains authoritative for range/clock/step semantics. */
object CookingTimerDurationInput {
    fun seconds(text: String): Long? = text.trim().takeIf { it.matches(Regex("[0-9]{1,5}")) }
        ?.toLongOrNull()?.takeIf { it in 1..CookingTimerReducer.MAX_DURATION_SECONDS }
}

/** Retained delivery observation, never scheduling or domain authority. A native-gated due
 * event belongs to one exact desired generation; cleanup or a new intent cannot reuse it.
 */
internal class CookingTimerDueObservation {
    private var identity: CookingTimerDue? = null
    fun record(value: CookingTimerDue) { identity = value }
    fun clear() { identity = null }
    fun invalidate(sessionId: String, timerId: String) {
        if (identity?.let { it.sessionId == sessionId && it.timerId == timerId } == true) clear()
    }
    fun current(snapshot: CookingTimerSnapshot?): CookingTimerDue? = identity?.takeIf { due ->
        snapshot != null && snapshot.sessionId == due.sessionId && snapshot.originMatches &&
            snapshot.availability == CookingAvailability.AVAILABLE && snapshot.status == CookingStatus.ACTIVE &&
            snapshot.timers.any { it.timerId == due.timerId && it.generation == due.generation &&
                it.status == "running" && it.alertPhase == CookingTimerAlertPhase.ARMED && it.alertAcknowledgedInThisOwner }
    }
}
