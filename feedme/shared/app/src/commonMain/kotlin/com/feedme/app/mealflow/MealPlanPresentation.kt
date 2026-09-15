package com.feedme.app.mealflow

import com.feedme.contracts.*
import com.feedme.mealflow.MealFlowIssue
import com.feedme.mealflow.MealFlowPhase
import com.feedme.mealflow.*
import com.feedme.core.ports.FailureReason

/** Detached view state, not a session/plan capability. Internal construction is for projection
 * and presentation tests only; the public host always projects the actual retained controller. */
class MealScreenState internal constructor(val phase: MealFlowPhase, val screen: MealFlowScreen,
    val issue: MealFlowIssue, val failureReason: FailureReason?, val plan: PlanWire?,
    val historical: Boolean, val alternativesAvailable: Boolean, val previousAvailable: Boolean,
    val pendingMatchesDraft: Boolean, val retryAtMillis: Long?) {
    override fun toString() = "MealScreenState(phase=$phase, private=<redacted>)"
    companion object {
        fun from(state: MealRequestState) = MealScreenState(state.phase, state.screen, state.issue, state.failureReason,
            state.plan?.plan, state.plan?.historical ?: true, state.alternativesAvailable,
            state.plan?.let { current -> state.history.indexOfFirst { it.plan.id == current.plan.id } > 0 } ?: false,
            state.pendingMatchesDraft, state.retryAtMillis)
    }
}
class IngredientRow internal constructor(val id: String, val name: String, val historical: Boolean) {
    override fun toString() = "IngredientRow(<redacted>)"
}
class PantryRow internal constructor(val ingredientId: String, val name: String?, val presence: String,
    val confirmationStatus: WireField<String>, val confirmedAt: WireField<String>, val historical: Boolean) {
    override fun toString() = "PantryRow(<redacted>)"
}
class MealPickerPresentation internal constructor(val searchPhase: IngredientPickerPhase,
    val searchResults: List<IngredientRow>, val knownIngredients: List<IngredientRow>, val pantryItems: List<PantryRow>,
    val searchHasMore: Boolean, val pantryHasMore: Boolean, val issue: IngredientPickerIssue,
    val retryAfterSeconds: Long?) {
    override fun toString() = "MealPickerPresentation(<redacted>)"
    companion object {
        fun from(state: IngredientPickerState) = MealPickerPresentation(state.searchPhase,
            state.searchResults.map { IngredientRow(it.id, it.name, it.historical) },
            state.knownIngredients.map { IngredientRow(it.id, it.name, it.historical) },
            state.pantryItems.map { PantryRow(it.ingredientId, it.resolvedIngredient?.name, it.presence,
                it.confirmationStatus, it.confirmedAt, it.historical) }, state.searchHasMore, state.pantryHasMore,
            state.issue, state.retryAfterSeconds)
    }
}

/** Display projections only; no authorization, recipe transformation, numeric rounding or IDs. */
class MealPlanPresentation(val plan: PlanWire, val historical: Boolean, labels: Map<String, String>) {
    private val names = labels.toMap()
    val recipe: RecipeVersionWire? get() = (plan.recipeSnapshot as? WireField.Value)?.value
    val title get() = recipe?.title ?: when (plan.status) {
        "needsConfirmation" -> "A little clarity first."
        "noMatch" -> "No fit this time."
        "recalled" -> "This recipe is unavailable."
        else -> "Your meal plan"
    }
    val recipeVisible get() = plan.status == "ready" && recipe != null && recipe?.reviewStatus !in setOf("recalled", "retired")
    fun ingredientName(id: String): String? = names.entries.firstOrNull { it.key.equals(id, ignoreCase = true) }?.value
    fun ingredientLine(ingredient: IngredientAmountWire): String = buildString {
        append(ingredient.quantity.jsonToken); append(' '); append(ingredient.unit); append(" · ")
        append(ingredientName(ingredient.ingredientId.value) ?: "Ingredient label unavailable (${ingredient.ingredientId.value})")
        if (ingredient.optional) append(" · optional")
        (ingredient.preparation as? WireField.Value)?.value?.let { append(" · "); append(it) }
    }
    val unresolvedIngredientCount get() = (recipe?.ingredients.orEmpty() + plan.missingIngredients)
        .map { it.ingredientId.value }.distinct().count { ingredientName(it) == null }
    val reasons get() = plan.reasons.mapNotNull { it.text("label") }
    val changes get() = plan.changes.mapNotNull { it.text("explanation") }
    override fun toString() = "MealPlanPresentation(<redacted>)"
}

internal fun WireDocument.text(name: String) = (field(name) as? WireField.Value)?.value?.stringOrNull()
internal fun <T> WireField<T>.valueOrNull(): T? = (this as? WireField.Value)?.value

fun mealPhaseMessage(phase: MealFlowPhase): Pair<String, String> = when (phase) {
    MealFlowPhase.EDITING -> "Your dinner, your pace." to "Choose what you have and what feels doable."
    MealFlowPhase.LOADING -> "Working on it." to "Your saved request stays intact. Back is still available."
    MealFlowPhase.NEEDS_CONFIRMATION -> "One quick check." to "Review the missing or changed inputs. Nothing has been assumed for you."
    MealFlowPhase.NO_MATCH -> "No fit this time." to "Try changing your time, equipment or ingredients. Your exclusions stay in place."
    MealFlowPhase.READY -> "A meal that fits." to "Review the ingredients, effort and any missing items."
    MealFlowPhase.OFFLINE_DRAFT -> "Offline, not forgotten." to "Save a valid draft on this device. Matching waits for your explicit action online."
    MealFlowPhase.RESOLVING -> "Checking that last request." to "It may have reached the server. Retry keeps the original request and key; it does not send your new edits."
    MealFlowPhase.ERROR -> "That needs a second look." to "Your input is retained. Review it before trying again."
    MealFlowPhase.UNAVAILABLE -> "This kitchen is unavailable." to "Return to your account or recovery screen. This view cannot restore access."
}

fun mealIssueMessage(issue: MealFlowIssue): String? = when (issue) {
    MealFlowIssue.NONE -> null
    MealFlowIssue.CONTEXT_REQUIRED -> "Load your preferences and pantry before matching."
    MealFlowIssue.PREFERENCES_PENDING -> "Your preference changes are waiting to sync. Resolve those before matching."
    MealFlowIssue.CONTEXT_CHANGED -> "Your inputs changed. Review them; an earlier unresolved command must be reconciled before a new request."
    MealFlowIssue.REPLAY_EXPIRED -> "The retry window ended. The original command is retained; do not recreate it with a new key. Account recovery/support integration is still required."
    MealFlowIssue.RETRY_LATER -> "A retry is not available yet. Your original request is retained."
    MealFlowIssue.REQUEST_UNRESOLVED -> "The original request still needs a confirmed outcome. Edited inputs have not replaced it."
    MealFlowIssue.INVALID_REPLY -> "The response could not be verified. No unverified recipe is shown as a new success."
    MealFlowIssue.STORAGE -> "Your device could not confirm the save. Keep this screen open and try again."
    MealFlowIssue.SESSION_UNAVAILABLE -> "Your session changed or ended. Private details have been hidden."
    MealFlowIssue.DRAFT_EXPIRED -> "The retained draft expired. Start a new draft; unresolved commands are not silently discarded."
    MealFlowIssue.NO_MORE_ALTERNATIVES -> "No further alternative is available for these inputs. You can revisit a saved candidate or edit the request."
}
