package com.feedme.mealflow

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.FailureReason

enum class KitchenInputPhase { EDITING, LOADING, READY, PENDING, CONFLICT, OFFLINE, ERROR, UNAVAILABLE }
enum class KitchenInputIssue { NONE, LOAD_REQUIRED, OFFLINE, PENDING_SYNC, RECONCILIATION_REQUIRED,
    INVALID_INPUT, STORAGE, OUTCOME_UNKNOWN, SESSION_UNAVAILABLE, RETRY_LATER, PAGE_LIMIT }
enum class KitchenInputCommandPhase { READY, IN_FLIGHT, RETRY_WAIT, AWAITING_CONFIRMATION,
    NEEDS_RESOLUTION, RECEIPT_READY, APPLIED, DISCARDED }

/** Explicit operational bounds, not a catalog/provider or default retention decision. */
class KitchenInputPolicy(val pageSize: Int, val maxPantryPages: Int, val maxPantryItems: Int, val maxDrafts: Int,
    /** Required integration profile agreed with the actual backend, NOT a canonical global cap. */
    val maxResponseBytes: Int) {
    init { require(pageSize in 1..50 && maxPantryPages in 1..10 && maxPantryItems in 1..512 && maxDrafts in 1..128 &&
        maxResponseBytes in 1024..262_144) }
    override fun toString() = "KitchenInputPolicy(<redacted>)"
}

/** Exact retained intention and journal status. Exposing these values grants no new-key rebase,
 * remote success or permission to drop attempted evidence. `base` is the original owned snapshot;
 * current server candidates remain separately visible on KitchenInputState. */
class KitchenInputPending internal constructor(val commandId: String, val operationId: String,
    val ingredientId: String?, val body: WireDocument?, val ifMatch: String?, val base: WireDocument?,
    val phase: KitchenInputCommandPhase, val attempts: Int, val issue: String, val retryAtMillis: Long,
    val canSynchronize: Boolean, val canDiscardUnsent: Boolean) {
    override fun toString() = "KitchenInputPending(operationId=$operationId, phase=$phase, details=<redacted>)"
}

/** Private current/draft/pending values are deliberately distinct. Pantry facts never become
 * today's confirmed meal ingredients. All collections are detached; documents are immutable. */
class KitchenInputState internal constructor(val phase: KitchenInputPhase,
    val preferences: WireDocument?, val preferenceDraft: WireDocument?,
    pantryItems: List<WireDocument>, pantryDrafts: List<WireDocument>, pending: List<KitchenInputPending>,
    val preferencesPending: Boolean, stricterExclusionIds: List<String>, val pantryHasMore: Boolean,
    val historical: Boolean, val draftAcknowledged: Boolean,
    val issue: KitchenInputIssue = KitchenInputIssue.NONE, val failureReason: FailureReason? = null) {
    private val rows = pantryItems.toList(); private val drafts = pantryDrafts.toList()
    private val commands = pending.toList(); private val exclusions = stricterExclusionIds.toList()
    val pantryItems get() = rows.toList()
    val pantryDrafts get() = drafts.toList()
    val pending get() = commands.toList()
    val stricterExclusionIds get() = exclusions.toList()
    override fun toString() = "KitchenInputState(phase=$phase, details=<redacted>)"
    internal companion object {
        fun empty() = KitchenInputState(KitchenInputPhase.EDITING, null, null, emptyList(), emptyList(), emptyList(),
            false, emptyList(), false, true, true)
        fun unavailable() = KitchenInputState(KitchenInputPhase.UNAVAILABLE, null, null, emptyList(), emptyList(), emptyList(),
            true, emptyList(), false, true, false, KitchenInputIssue.SESSION_UNAVAILABLE, FailureReason.STALE_SESSION)
    }
}
