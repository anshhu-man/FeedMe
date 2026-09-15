package com.feedme.mealflow

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireField
import com.feedme.core.ports.FailureReason

enum class IngredientPickerPhase { IDLE, LOADING, READY, EMPTY, OFFLINE, ERROR, UNAVAILABLE }
enum class IngredientPickerIssue { NONE, OFFLINE, INVALID_QUERY, CACHE_EXPIRED, PAGE_LIMIT,
    CONTEXT_CHANGED, INVALID_REPLY, STORAGE, SESSION_UNAVAILABLE, RETRY_LATER }

/** Explicit resource/retention policy, not a provider, ownership or freshness guarantee. */
class IngredientPickerPolicy(val pageSize: Int, val maxPages: Int, val maxCachedIngredients: Int,
    val cacheRetentionMillis: Long) {
    init {
        require(pageSize in 1..50 && maxPages in 1..10 && maxCachedIngredients in 1..512)
        require(cacheRetentionMillis in 1..2_592_000_000L)
    }
    override fun toString() = "IngredientPickerPolicy(<redacted>)"
}

/** Actual catalog metadata. Selection still requires an explicit meal-draft action; neither a
 * catalog label nor a pantry report certifies freshness, safe storage or allergen safety. */
class IngredientOption internal constructor(val id: String, val name: String, aliases: List<String>,
    val category: String, val document: WireDocument, val checkedAtMillis: Long,
    val historical: Boolean) {
    private val copiedAliases = aliases.toList()
    val aliases get() = copiedAliases.toList()
    override fun toString() = "IngredientOption(<redacted>)"
}

/** Exact pantry wire facts, not a second stock model. Missing labels remain unresolved; never
 * substitute arbitrary names or promote usuallyHave/uncertain into confirmed current-meal IDs. */
class PantryOption internal constructor(val id: String, val ingredientId: String, val presence: String,
    val confirmationStatus: WireField<String>, val confirmedAt: WireField<String>, val staple: Boolean,
    val resolvedIngredient: IngredientOption?, val document: WireDocument, val checkedAtMillis: Long?,
    val historical: Boolean) {
    override fun toString() = "PantryOption(<redacted>)"
}

/** Read-only presentation. Collecting state neither fetches, edits pantry nor submits a meal. */
class IngredientPickerState internal constructor(val searchQuery: String,
    val searchPhase: IngredientPickerPhase, val pantryPhase: IngredientPickerPhase,
    searchResults: List<IngredientOption>, pantryItems: List<PantryOption>, knownIngredients: List<IngredientOption>,
    val searchHasMore: Boolean, val pantryHasMore: Boolean,
    val issue: IngredientPickerIssue = IngredientPickerIssue.NONE, val failureReason: FailureReason? = null,
    val retryAfterSeconds: Long? = null) {
    private val results = searchResults.toList(); private val pantry = pantryItems.toList(); private val known = knownIngredients.toList()
    val searchResults get() = results.toList()
    val pantryItems get() = pantry.toList()
    val knownIngredients get() = known.toList()
    override fun toString() = "IngredientPickerState(searchPhase=$searchPhase, pantryPhase=$pantryPhase, details=<redacted>)"
    internal companion object {
        fun empty() = IngredientPickerState("", IngredientPickerPhase.IDLE, IngredientPickerPhase.IDLE,
            emptyList(), emptyList(), emptyList(), false, false)
        fun unavailable() = IngredientPickerState("", IngredientPickerPhase.UNAVAILABLE, IngredientPickerPhase.UNAVAILABLE,
            emptyList(), emptyList(), emptyList(), false, false, IngredientPickerIssue.SESSION_UNAVAILABLE, FailureReason.STALE_SESSION)
    }
}
