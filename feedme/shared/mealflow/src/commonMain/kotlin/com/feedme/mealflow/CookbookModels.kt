package com.feedme.mealflow

import com.feedme.core.ports.FailureReason
import com.feedme.kitchen.SavedRecipeSnapshot
import com.feedme.kitchen.SavedRecipeDeletionTarget

enum class CookbookScreen { HIDDEN, LIST, DETAIL }
enum class CookbookPhase { IDLE, LOADING, READY, PENDING, OFFLINE, ERROR, UNAVAILABLE }
enum class CookbookIssue { NONE, PENDING_ORIGINAL, CONFIRM_DELETE, CONTEXT_CHANGED, RECALLED,
    LOCAL_CAPACITY, RECONCILIATION_REQUIRED, OFFLINE, INVALID_INPUT, STORAGE, SESSION_UNAVAILABLE }
class CookbookPolicy(val pageSize: Int, val confirmationMillis: Long) {
    init { require(pageSize in 1..50 && confirmationMillis in 1..300_000) }
}
class CookbookPending internal constructor(val commandId: String, val operationId: String, val phase: String,
    val attempts: Int, val issue: String, val canRetry: Boolean, val canDiscardUnsent: Boolean,
    val finalizationRequired: Boolean) {
    override fun toString() = "CookbookPending(operation=$operationId, phase=$phase, details=<redacted>)"
}
/** Exact deletion consent, not a general write capability. A refresh/navigation/new consent fences it. */
class PreparedCookbookDelete internal constructor(internal val owner: Any, internal val generation: Any,
    internal val snapshot: SavedRecipeSnapshot, internal val created: Long,
    internal val localTarget: SavedRecipeDeletionTarget? = null) {
    /** Removal-only consent never discloses cached recalled/redacted content or attribution. */
    val contentUnavailable: Boolean get() = localTarget != null
    val title: String? get() = if (contentUnavailable) null else snapshot.savedRecipe?.title
    override fun toString() = "PreparedCookbookDelete(<redacted>)"
}
/** Remote observations are distinct from downloaded local bundles. Neither grants copy/cook rights. */
class CookbookState internal constructor(val screen: CookbookScreen, val phase: CookbookPhase,
    val query: String?, items: List<SavedRecipeSnapshot>, val selected: SavedRecipeSnapshot?,
    val hasMore: Boolean, val localOnly: Boolean, val historical: Boolean, val busy: Boolean,
    val pending: CookbookPending?, val deleteConfirmation: PreparedCookbookDelete?,
    val serverAcknowledged: Boolean, val issue: CookbookIssue, val failureReason: FailureReason? = null) {
    private val entries = items.toList()
    val items get() = entries.toList()
    override fun toString() = "CookbookState(screen=$screen, phase=$phase, details=<redacted>)"
    internal companion object {
        fun empty() = CookbookState(CookbookScreen.HIDDEN, CookbookPhase.IDLE, null, emptyList(), null,
            false, false, true, false, null, null, false, CookbookIssue.NONE)
        fun unavailable() = CookbookState(CookbookScreen.HIDDEN, CookbookPhase.UNAVAILABLE, null, emptyList(), null,
            false, false, true, false, null, null, false, CookbookIssue.SESSION_UNAVAILABLE, FailureReason.STALE_SESSION)
    }
}
