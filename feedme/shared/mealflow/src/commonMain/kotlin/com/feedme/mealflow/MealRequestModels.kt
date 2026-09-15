package com.feedme.mealflow

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.session.PrivateSessionAccess
import com.feedme.session.PrivateSessionAccessMode

enum class MealMode { AUTO, COOK, ASSEMBLE, IMPROVE }
enum class MealEnergy { ASSEMBLE, LITTLE, HAPPY }
enum class BasePreparation { ALREADY_PREPARED, PARTIALLY_PREPARED, UNKNOWN }

/** Explicit input only; neither the description nor selected IDs certify complete composition. */
class ManualBaseMeal(val description: String, val preparation: BasePreparation, ingredientIds: List<String>?) {
    private val ids = ingredientIds?.toList()
    val ingredientIds get() = ids?.toList()
    override fun toString() = "ManualBaseMeal(<redacted>)"
}

/** Manual controls only. No text interpretation, source grant, household field or recipe body. */
class ManualMealDraft(
    val mode: MealMode, val energy: MealEnergy, val servings: String,
    ingredientIds: List<String>, equipmentIds: List<String>, hardExcludedIngredientIds: List<String>,
    tasteTags: List<String>, val maxTotalMinutes: String? = null, val maxActiveMinutes: String? = null,
    val baseMeal: ManualBaseMeal? = null, val preferencesPendingSync: Boolean = false,
) {
    private val ingredients = ingredientIds.toList(); private val equipment = equipmentIds.toList()
    private val exclusions = hardExcludedIngredientIds.toList(); private val tastes = tasteTags.toList()
    val ingredientIds get() = ingredients.toList()
    val equipmentIds get() = equipment.toList()
    val hardExcludedIngredientIds get() = exclusions.toList()
    val tasteTags get() = tastes.toList()
    override fun toString() = "ManualMealDraft(<redacted>)"
}

/** This caller must supply native globally unique command UUIDs; no timestamp/random fallback.
 * Local seven-day deduplication is bounded and is not a forever-uniqueness proof. */
fun interface MealOperationIds { suspend fun next(): String }

/** Product retention is explicit, not a legal claim. Replay is capped below seven-day receipts.
 * Resolved issued IDs remain for seven days; capacity throttles temporarily until expiry. This
 * relies on the supplied device clock, rejects observed rollback, and cannot prove wall time. */
class MealFlowPolicy(val draftRetentionMillis: Long, val replayWindowMillis: Long, val historyLimit: Int,
    val issuedIdCapacity: Int) {
    init {
        require(draftRetentionMillis in 1..2_592_000_000L)
        require(replayWindowMillis in 1..518_400_000L)
        require(historyLimit in 1..20)
        require(issuedIdCapacity in 1..4096)
    }
    override fun toString() = "MealFlowPolicy(<redacted>)"
}

/** Borrowed verified session composition; no credential values escape or stores get activated.
 * Required transport must implement canonical current backend authorization. Construction itself
 * does not authenticate a backend, configure a provider, or upgrade an offline-private session. */
class AuthenticatedMealPlanningAccess internal constructor(
    internal val lease: SessionLease, internal val origin: String,
    internal val store: PrivateStateStore, internal val transport: AccountTransport,
    internal val onlineAllowed: Boolean,
) {
    override fun toString() = "AuthenticatedMealPlanningAccess(<redacted>)"
    companion object {
        fun fromSession(session: PrivateSessionAccess, transport: AccountTransport): AuthenticatedMealPlanningAccess =
            AuthenticatedMealPlanningAccess(session.lease, session.originBinding, session.store, transport,
                session.mode == PrivateSessionAccessMode.ONLINE)
    }
}

enum class MealFlowPhase { EDITING, LOADING, NEEDS_CONFIRMATION, NO_MATCH, READY, OFFLINE_DRAFT, RESOLVING, ERROR, UNAVAILABLE }
enum class MealFlowScreen { REQUEST, RECOMMENDATIONS, RECIPE }
enum class MealFlowIssue { NONE, CONTEXT_REQUIRED, PREFERENCES_PENDING, CONTEXT_CHANGED, REPLAY_EXPIRED, RETRY_LATER,
    REQUEST_UNRESOLVED, INVALID_REPLY, STORAGE, SESSION_UNAVAILABLE, DRAFT_EXPIRED, NO_MORE_ALTERNATIVES }

class MealPlanSnapshot internal constructor(val plan: PlanWire, val etag: String?, val receivedAtMillis: Long,
    /** Cached facts are historical, never a fresh recall/authorization/ingredient guarantee. */
    val historical: Boolean) {
    override fun toString() = "MealPlanSnapshot(<redacted>)"
}

class MealRequestState internal constructor(
    val phase: MealFlowPhase, val screen: MealFlowScreen, val draft: ManualMealDraft?,
    val preferences: WireDocument?, val pantryPage: WireDocument?, val plan: MealPlanSnapshot?,
    history: List<MealPlanSnapshot>, val issue: MealFlowIssue, val failureReason: FailureReason?,
    val alternativesAvailable: Boolean, val pendingMatchesDraft: Boolean,
    /** Earliest explicit retry time from Retry-After or temporary local issued-ID capacity. */
    val retryAtMillis: Long? = null,
) {
    private val prior = history.toList()
    val history get() = prior.toList()
    override fun toString() = "MealRequestState(phase=$phase, details=<redacted>)"
    internal companion object {
        fun unavailable() = MealRequestState(MealFlowPhase.UNAVAILABLE, MealFlowScreen.REQUEST, null,
            null, null, null, emptyList(), MealFlowIssue.SESSION_UNAVAILABLE, FailureReason.STALE_SESSION, false, false)
    }
}
