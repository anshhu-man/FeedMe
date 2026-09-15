package com.feedme.mealflow

import com.feedme.contracts.PlanWire
import com.feedme.core.ports.FailureReason
import com.feedme.kitchen.CookingSnapshot

enum class CookingFlowPhase { IDLE, START_CONFIRMATION, START_PENDING, DOWNLOADING, COOKING,
    PAUSED, COMPLETED, ABANDONED, CONFLICT, OFFLINE, ERROR, UNAVAILABLE }
enum class CookingFlowScreen { RECIPE, COOK }
enum class CookingFlowIssue { NONE, CONFIRM_START, PENDING_SYNC, CONTEXT_CHANGED, PREFERENCES_PENDING,
    DOWNLOAD_INCOMPLETE, RECALLED, PERSONAL_UNREVIEWED, ORIGIN_CHANGED, RECONCILIATION_REQUIRED,
    OFFLINE, RETRY_LATER, INVALID_INPUT, STORAGE, OUTCOME_UNKNOWN, SESSION_UNAVAILABLE }

/** Explicit integration profile, not a canonical global response limit or offline grant.
 * The backend/transport owner must agree these bounds before composing a live service. */
class CookingFlowPolicy(val confirmationMillis: Long, val maxSessionBytes: Int, val maxPreferencesBytes: Int) {
    init { require(confirmationMillis in 1..300_000 && maxSessionBytes in 1024..262_144 && maxPreferencesBytes in 1024..131_072) }
    override fun toString() = "CookingFlowPolicy(<redacted>)"
}

/** Queue status is advisory. IDs are original exact command references, not retry/rebase grants. */
class CookingFlowPending internal constructor(val commandId: String, val operationId: String,
    val phase: String, val attempts: Int, val issue: String, val retryAtMillis: Long,
    val canRetry: Boolean, val canDiscardUnsent: Boolean) {
    override fun toString() = "CookingFlowPending(operationId=$operationId, phase=$phase, details=<redacted>)"
}

/** No recalled/unreviewed instructions are projected. Retained immutable snapshots are historical
 * observations; a successful download is local integrity evidence, NOT a server-signed manifest.
 * Completed local progress can still have a pending server acknowledgement. During a prepared
 * start, plan is that exact proposal (or null when blocked); cooking may still observe a previous
 * selected pin. Confirmation must never substitute that previous pin's plan for this proposal. */
class CookingFlowState internal constructor(val phase: CookingFlowPhase, val screen: CookingFlowScreen,
    val plan: PlanWire?, val cooking: CookingSnapshot?, pending: List<CookingFlowPending>,
    val canEdit: Boolean, val canStop: Boolean, val canComplete: Boolean,
    val historical: Boolean, val serverAcknowledged: Boolean, val issue: CookingFlowIssue,
    val failureReason: FailureReason? = null) {
    private val commands = pending.toList()
    val pending get() = commands.toList()
    override fun toString() = "CookingFlowState(phase=$phase, details=<redacted>)"
    internal companion object {
        fun empty() = CookingFlowState(CookingFlowPhase.IDLE, CookingFlowScreen.RECIPE, null, null, emptyList(),
            false, false, false, true, false, CookingFlowIssue.NONE)
        fun unavailable() = CookingFlowState(CookingFlowPhase.UNAVAILABLE, CookingFlowScreen.RECIPE, null, null, emptyList(),
            false, false, false, true, false, CookingFlowIssue.SESSION_UNAVAILABLE, FailureReason.STALE_SESSION)
    }
}
