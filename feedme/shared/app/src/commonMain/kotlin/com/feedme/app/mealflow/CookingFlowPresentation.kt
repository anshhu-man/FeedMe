package com.feedme.app.mealflow

import com.feedme.contracts.PlanWire
import com.feedme.core.ports.FailureReason
import com.feedme.kitchen.CookingStatus
import com.feedme.mealflow.*

/** Detached display projection only. The public host supplies actual controller observations. */
class CookingScreenState internal constructor(val phase: CookingFlowPhase, val screen: CookingFlowScreen,
    val plan: PlanWire?, val sessionId: String?, val status: CookingStatus?, val currentStepId: String?,
    completed: List<String>, val localPendingCount: Int, val historical: Boolean, val serverAcknowledged: Boolean,
    val canEdit: Boolean, val canStop: Boolean, val canComplete: Boolean,
    val issue: CookingFlowIssue, val failure: FailureReason?, pending: List<CookingPendingRow>) {
    val completedStepIds = completed.toList()
    val pending = pending.toList()
    val recipe get() = plan?.recipeSnapshot?.valueOrNull()
    val steps get() = recipe?.steps.orEmpty()
    val currentStep get() = steps.singleOrNull { it.stepId.value == currentStepId }
    val instructionsVisible get() = sessionId != null && plan?.status == "ready" && recipe?.reviewStatus in setOf("published", "retired") &&
        issue !in setOf(CookingFlowIssue.RECALLED, CookingFlowIssue.PERSONAL_UNREVIEWED, CookingFlowIssue.DOWNLOAD_INCOMPLETE,
            CookingFlowIssue.SESSION_UNAVAILABLE) && currentStep != null
    val done get() = phase == CookingFlowPhase.COMPLETED && status == CookingStatus.COMPLETED &&
        issue !in setOf(CookingFlowIssue.RECALLED, CookingFlowIssue.ORIGIN_CHANGED, CookingFlowIssue.RECONCILIATION_REQUIRED) && failure == null
    val completionText get() = when {
        !done -> null
        localPendingCount > 0 || pending.isNotEmpty() -> "Finished on this device · waiting to sync"
        serverAcknowledged -> "Completion acknowledged by the server"
        else -> "Retained completed session · historical observation"
    }
    val starting get() = phase == CookingFlowPhase.START_CONFIRMATION || pending.any { it.operationId == "createCookSession" }
    val hasStartRetry get() = pending.any { it.operationId == "createCookSession" && it.canRetry }
    val canDiscardStart get() = phase == CookingFlowPhase.START_CONFIRMATION ||
        pending.any { it.operationId == "createCookSession" && it.canDiscard }
    val canSync get() = sessionId != null && !starting && (localPendingCount > 0 || pending.any { it.canRetry })
    override fun toString() = "CookingScreenState(phase=$phase, private=<redacted>)"
    companion object {
        fun from(state: CookingFlowState): CookingScreenState {
            // A prior session can coexist with a new PREPARED proposal. Never join their content.
            val pin = state.cooking?.takeIf { state.plan?.id == it.plan.id && state.phase != CookingFlowPhase.START_CONFIRMATION }
            return CookingScreenState(state.phase, state.screen, state.plan, pin?.id, pin?.progress?.status,
                pin?.progress?.currentStepId, pin?.progress?.completedStepIds.orEmpty(), pin?.pendingCommandIds?.size ?: 0,
                state.historical, state.serverAcknowledged, state.canEdit, state.canStop, state.canComplete,
                state.issue, state.failureReason, state.pending.map { CookingPendingRow(it.commandId, it.operationId,
                    it.phase, it.attempts, it.issue, it.retryAtMillis, it.canRetry, it.canDiscardUnsent) })
        }
    }
}
class CookingPendingRow internal constructor(val id: String, val operationId: String, val phase: String,
    val attempts: Int, val issue: String, val retryAtMillis: Long, val canRetry: Boolean, val canDiscard: Boolean) {
    val message get() = if (phase == "FINALIZATION_REQUIRED") "The original start still needs its local acknowledgement. Retry the original start; no new POST is required."
        else "${operationId}: $phase · attempts $attempts · $issue"
    override fun toString() = "CookingPendingRow(phase=$phase, private=<redacted>)"
}

internal fun cookingIssueMessage(issue: CookingFlowIssue): String? = when (issue) {
    CookingFlowIssue.NONE -> null
    CookingFlowIssue.CONFIRM_START -> "Review this exact plan before creating a cooking session. Nothing has started yet."
    CookingFlowIssue.PENDING_SYNC -> "Local intent is retained. Synchronization happens only when you explicitly request it."
    CookingFlowIssue.CONTEXT_CHANGED -> "The saved request, recipe or preferences changed. The original intent has not been replaced."
    CookingFlowIssue.PREFERENCES_PENDING -> "Resolve your preference changes before continuing cooking. Safe pause or stop can remain available."
    CookingFlowIssue.DOWNLOAD_INCOMPLETE -> "The complete owned cooking bundle is unavailable. Instructions are not ready for offline use."
    CookingFlowIssue.RECALLED -> "This recipe is recalled. Its instructions are hidden; do not continue from an old preview."
    CookingFlowIssue.PERSONAL_UNREVIEWED -> "This unreviewed content is not eligible for guided cooking."
    CookingFlowIssue.ORIGIN_CHANGED -> "This retained session belongs to a different origin. It is read-only here."
    CookingFlowIssue.RECONCILIATION_REQUIRED -> "The original action needs reconciliation. No new key or guessed progress replaces it."
    CookingFlowIssue.OFFLINE -> "You are offline. Retained local progress is not a server acknowledgement."
    CookingFlowIssue.RETRY_LATER -> "Wait for the original command's retry time. Nothing retries automatically."
    CookingFlowIssue.INVALID_INPUT -> "This cooking action is not valid for the current pinned session."
    CookingFlowIssue.STORAGE -> "The device could not acknowledge this action. It is not marked saved."
    CookingFlowIssue.OUTCOME_UNKNOWN -> "The action may have completed. Keep the original command and use its explicit retry."
    CookingFlowIssue.SESSION_UNAVAILABLE -> "Your session changed. Private cooking details are hidden."
}
