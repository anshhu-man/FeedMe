package com.feedme.app.mealflow

import com.feedme.core.ports.*
import com.feedme.mealflow.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal val ROOT_RECIPE_SCREENS = setOf(MealFlowScreen.CATALOG, MealFlowScreen.CATALOG_RECIPE,
    MealFlowScreen.SAVED_RECIPE, MealFlowScreen.POST_RECIPE, MealFlowScreen.ROOT_MAKE_MINE, MealFlowScreen.ROOT_VARIANT)

/** Session-local edits, separate from the current meal and exact durable sent command. */
class RootRecipeFormState internal constructor(values: MealFormValues?, val source: MealRootSource?,
    val visible: Boolean = false, val dirty: Boolean = false, val searchText: String = "") {
    private val copy = values?.detached()
    val values get() = copy?.detached()
    override fun toString() = "RootRecipeFormState(visible=$visible, private=<redacted>)"
}
internal fun sameCatalogSource(first: MealCatalogRecipe, second: MealCatalogRecipe) =
    first.recipe.document.encodeUtf8().contentEquals(second.recipe.document.encodeUtf8()) && first.etag == second.etag
internal fun sameRootSource(first: MealRootSource, second: MealRootSource) =
    first.savedRecipeId == second.savedRecipeId && first.sourcePostId == second.sourcePostId && first.etag == second.etag &&
        first.recipe.document.encodeUtf8().contentEquals(second.recipe.document.encodeUtf8()) &&
        ((first as? MealSavedRootSource)?.savedRecipe?.document?.encodeUtf8()?.toList() ==
            (second as? MealSavedRootSource)?.savedRecipe?.document?.encodeUtf8()?.toList())
/** A retained form is not authority to redisplay a source removed from the current controller
 * projection. This also covers a frame where the two StateFlows update separately. */
internal fun currentRootRecipeFormSource(meal: MealScreenState, root: RootRecipeFormState?): MealRootSource? =
    meal.rootSource?.takeIf { current -> root?.source?.let { sameRootSource(it, current) } == true }

internal class RootRecipeFormOwner(private val boundary: SessionBoundary, private val lease: SessionLease) {
    private val mutable = MutableStateFlow(RootRecipeFormState(null, null))
    val states = mutable.asStateFlow()
    private var closed = false
    private class ExpiredPostInputs(val postId: String, val version: String, val values: MealFormValues?,
        val dirty: Boolean, val searchText: String)
    private var expiredPostInputs: ExpiredPostInputs? = null
    private val subscription = boundary.onInvalidated(lease) { redact() }
    private fun current() = !closed && boundary.isCurrent(lease)
    fun matches(expected: RootRecipeFormState) = current() && mutable.value === expected
    fun open(source: MealRootSource, values: MealFormValues, visible: Boolean, reuse: Boolean = false) {
        if (!current()) { redact(); return }
        val before = mutable.value
        val keep = reuse && before.source?.let { sameRootSource(it, source) } == true
        val expired = expiredPostInputs?.takeIf { reuse && source is MealPostRootSource &&
            it.postId == source.postId && it.version == source.postVersion }
        mutable.value = RootRecipeFormState(if (keep) before.values else expired?.values ?: values, source, visible,
            if (keep) before.dirty else expired?.dirty == true, if (keep) before.searchText else expired?.searchText.orEmpty())
        expiredPostInputs = null
    }
    fun edit(expected: RootRecipeFormState, transform: (MealFormValues) -> MealFormValues): PortResult<RootRecipeFormState> {
        if (!matches(expected) || !expected.visible) return failure()
        val before = expected.values ?: return failure()
        val next = try { transform(before).detached() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return PortResult.Failure(FailureReason.INVALID_DATA) }
        if (!matches(expected)) return failure()
        if (next.savedRecipeId != before.savedRecipeId || next.savedMakeMine != before.savedMakeMine ||
            next.sourceRecipeVersionId != before.sourceRecipeVersionId ||
            next.sourcePostId != before.sourcePostId || next.sourcePostVersion != before.sourcePostVersion ||
            next.mode != before.mode || next.baseDescription != before.baseDescription ||
            next.basePreparation != before.basePreparation || next.baseIngredientIds != before.baseIngredientIds ||
            next.preferencesPendingSync != before.preferencesPendingSync ||
            listOf(next.servings, next.totalMinutes, next.activeMinutes, next.cleanupMinutes).any { it.length > 128 } ||
            listOf(next.ingredientIds, next.equipmentIds, next.exclusions, next.tasteTags).any { values ->
                values.size > 128 || values.any { it.isBlank() || it.length > 200 || it.any(Char::isISOControl) } })
            return PortResult.Failure(FailureReason.INVALID_DATA)
        return PortResult.Value(RootRecipeFormState(next, expected.source, true, true, expected.searchText).also { mutable.value = it })
    }
    fun searchText(expected: RootRecipeFormState, value: String): PortResult<RootRecipeFormState> {
        if (!matches(expected) || !expected.visible) return failure()
        if (value.length > 200 || value.any(Char::isISOControl)) return PortResult.Failure(FailureReason.INVALID_DATA)
        return PortResult.Value(RootRecipeFormState(expected.values, expected.source, true, expected.dirty, value).also { mutable.value = it })
    }
    fun leave() {
        if (!current()) { redact(); return }
        val before = mutable.value
        mutable.value = RootRecipeFormState(before.values, before.source, false, before.dirty, before.searchText)
    }
    /** Redact session-local display only; durable source/request/proposal records are owned
     * by MealRequestController and are never discarded by this projection. */
    fun reconcileSource(source: MealRootSource?) {
        val old = mutable.value.source ?: return
        if (!current()) { redact(); return }
        if (source == null && old is MealPostRootSource) {
            val before = mutable.value
            // A source expiry hides recipe instructions, not the user's unsent constraints.
            // Keep only their inputs and exact origin key; restoring needs a fresh source.
            expiredPostInputs = ExpiredPostInputs(old.postId, old.postVersion, before.values, before.dirty, before.searchText)
            mutable.value = RootRecipeFormState(before.values, null, false, before.dirty, before.searchText)
        } else if (source == null || !sameRootSource(old, source)) redact()
    }
    private fun failure() = PortResult.Failure(if (current()) FailureReason.CONFLICT else FailureReason.STALE_SESSION)
    private fun redact() { expiredPostInputs = null; mutable.value = RootRecipeFormState(null, null) }
    fun close() { redact(); closed = true; subscription.close() }
}
