package com.feedme.app.mealflow

import com.feedme.core.ports.*
import com.feedme.mealflow.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Editable session-local values. Sent requests and returned proposals are separately durable. */
data class AdaptationFormValues(val meal: MealFormValues,
    val reason: MealAdaptationReason = MealAdaptationReason.MAKE_MINE,
    val replaceIngredientId: String? = null, val requestedReplacementId: String? = null,
    val retainTasteTag: String? = null) {
    internal fun detached() = copy(meal = meal.detached())
    override fun toString() = "AdaptationFormValues(<redacted>)"
    companion object {
        fun from(proposal: MealAdaptationProposal) = AdaptationFormValues(MealFormValues.from(proposal.draft),
            proposal.reason, proposal.replaceIngredientId, proposal.requestedReplacementId, proposal.retainTasteTag)
        fun from(request: MealAdaptationRequest) = AdaptationFormValues(MealFormValues.from(request.draft),
            request.reason, request.replaceIngredientId, request.requestedReplacementId, request.retainTasteTag)
    }
}

class AdaptationFormState internal constructor(values: AdaptationFormValues?, val parent: MealPlanSnapshot?,
    val returnScreen: MealFlowScreen = MealFlowScreen.RECOMMENDATIONS, val visible: Boolean = false,
    val dirty: Boolean = false, val searchText: String = "", val failure: FailureReason? = null) {
    private val copy = values?.detached()
    val values get() = copy?.detached()
    override fun toString() = "AdaptationFormState(visible=$visible, private=<redacted>)"
}

/** Same serialized identity dispatcher as the experience. No store, transport, request keys,
 * native resource or main-draft readiness is acquired or changed by this owner. */
internal class AdaptationFormOwner(private val boundary: SessionBoundary, private val lease: SessionLease) {
    private val mutable = MutableStateFlow(AdaptationFormState(null, null))
    val states = mutable.asStateFlow()
    private var closed = false
    private val subscription = boundary.onInvalidated(lease) { redact() }
    fun current() = !closed && boundary.isCurrent(lease)
    fun matches(expected: AdaptationFormState) = current() && mutable.value === expected
    fun open(parent: MealPlanSnapshot, values: AdaptationFormValues, returnScreen: MealFlowScreen,
        visible: Boolean = true, reuse: Boolean = false): AdaptationFormState {
        if (!current()) { redact(); return mutable.value }
        val before = mutable.value
        val retained = reuse && before.parent?.let { sameAdaptationParent(it, parent) } == true
        val next = AdaptationFormState(if (retained) before.values else values, parent, returnScreen, visible,
            if (retained) before.dirty else false, if (retained) before.searchText else "")
        mutable.value = next
        return next
    }
    fun edit(expected: AdaptationFormState, transform: (AdaptationFormValues) -> AdaptationFormValues): PortResult<AdaptationFormState> {
        if (!matches(expected) || !expected.visible) return failure()
        val before = expected.values ?: return failure()
        val next = try { transform(before).detached() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return PortResult.Failure(FailureReason.INVALID_DATA) }
        // A caller transform may synchronously navigate, edit again or retire this lease.
        if (!matches(expected) || !expected.visible) return failure()
        val old = before.meal; val meal = next.meal
        if (!bounded(next) || meal.mode != old.mode || meal.baseDescription != old.baseDescription ||
            meal.basePreparation != old.basePreparation || meal.baseIngredientIds != old.baseIngredientIds ||
            meal.preferencesPendingSync != old.preferencesPendingSync || meal.savedRecipeId != old.savedRecipeId ||
            meal.sourceRecipeVersionId != old.sourceRecipeVersionId || meal.savedMakeMine != old.savedMakeMine)
            return PortResult.Failure(FailureReason.INVALID_DATA)
        return PortResult.Value(AdaptationFormState(next, expected.parent, expected.returnScreen, true, true,
            expected.searchText).also { mutable.value = it })
    }
    fun searchText(expected: AdaptationFormState, text: String): PortResult<AdaptationFormState> {
        if (!matches(expected) || !expected.visible) return failure()
        if (text.length > 200 || text.any(Char::isISOControl)) return PortResult.Failure(FailureReason.INVALID_DATA)
        return PortResult.Value(AdaptationFormState(expected.values, expected.parent, expected.returnScreen,
            true, expected.dirty, text).also { mutable.value = it })
    }
    fun leave() {
        if (!current()) { redact(); return }
        val before = mutable.value
        mutable.value = AdaptationFormState(before.values, before.parent, before.returnScreen, false,
            before.dirty, before.searchText, before.failure)
    }
    fun fail(reason: FailureReason) {
        if (!current()) { redact(); return }
        val before = mutable.value
        mutable.value = AdaptationFormState(before.values, before.parent, before.returnScreen, before.visible,
            before.dirty, before.searchText, reason)
    }
    private fun failure() = PortResult.Failure(if (current()) FailureReason.CONFLICT else FailureReason.STALE_SESSION)
    private fun redact() { mutable.value = AdaptationFormState(null, null, failure = FailureReason.STALE_SESSION) }
    fun close() { redact(); closed = true; subscription.close() }
    private fun bounded(values: AdaptationFormValues): Boolean {
        val meal = values.meal
        return listOf(meal.servings, meal.totalMinutes, meal.activeMinutes, meal.cleanupMinutes).all { it.length <= 128 } &&
            listOf(meal.ingredientIds, meal.equipmentIds, meal.exclusions, meal.tasteTags).all { list ->
                list.size <= 128 && list.all { it.isNotBlank() && it.length <= 200 && !it.any(Char::isISOControl) } } &&
            listOfNotNull(values.replaceIngredientId, values.requestedReplacementId, values.retainTasteTag)
                .all { it.isNotBlank() && it.length <= 128 && !it.any(Char::isISOControl) }
    }
}

internal fun sameAdaptationParent(first: MealPlanSnapshot, second: MealPlanSnapshot) =
    first.plan.document.encodeUtf8().contentEquals(second.plan.document.encodeUtf8()) && first.etag == second.etag
