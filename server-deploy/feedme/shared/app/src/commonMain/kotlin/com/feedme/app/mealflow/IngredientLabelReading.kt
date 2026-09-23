package com.feedme.app.mealflow

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feedme.contracts.PlanWire
import com.feedme.contracts.RecipeVersionWire
import com.feedme.mealflow.*

/** Readable source facts only. IDs are not a grant to obtain private or withdrawn content. */
internal fun recipeLabelIds(recipe: RecipeVersionWire): List<String> =
    (recipe.ingredients.map { it.ingredientId.value } + recipe.steps.flatMap { it.ingredientIds.map { id -> id.value } })
        .map(String::lowercase).distinct()

internal fun planLabelIds(plan: PlanWire): List<String> {
    val view = MealPlanPresentation(plan, true, emptyMap())
    if (!view.recipeVisible) return emptyList()
    return (recipeLabelIds(view.recipe!!) + plan.missingIngredients.map { it.ingredientId.value.lowercase() }).distinct()
}

internal fun cookingLabelIds(view: CookingScreenState): List<String> {
    if (!view.instructionsVisible && !view.preparedRecipeVisible) return emptyList()
    val recipe = view.recipe ?: return emptyList()
    // A retained cooking pin may legitimately expose a retired recipe. Use its own
    // visibility rules rather than imposing the new-recommendation lifecycle here.
    return (recipeLabelIds(recipe) + view.plan!!.missingIngredients.map { it.ingredientId.value.lowercase() }).distinct()
}

internal fun mealLabelIds(meal: MealScreenState): List<String> = when (meal.screen) {
    MealFlowScreen.RECOMMENDATIONS, MealFlowScreen.RECIPE -> meal.plan?.let(::planLabelIds).orEmpty()
    MealFlowScreen.VARIANT -> meal.proposal?.let { planLabelIds(it.parent.plan) + planLabelIds(it.child.plan) }.orEmpty()
    MealFlowScreen.VARIANT_MINE -> meal.adaptation?.let { planLabelIds(it.parent.plan) + planLabelIds(it.child.plan) }.orEmpty()
    MealFlowScreen.CATALOG_RECIPE, MealFlowScreen.SAVED_RECIPE, MealFlowScreen.POST_RECIPE, MealFlowScreen.ROOT_MAKE_MINE -> meal.rootSource?.recipe?.let(::recipeLabelIds).orEmpty()
    MealFlowScreen.ROOT_VARIANT -> meal.rootProposal?.let { recipeLabelIds(it.source.recipe) + planLabelIds(it.child.plan) }.orEmpty()
    else -> emptyList()
}.distinct()

internal fun missingLabelIds(ids: List<String>, picker: MealPickerPresentation): List<String> {
    val names = picker.knownIngredients.associate { it.id to it.name }
    return ids.filter { recipeIngredientLabel(it, names) == null }
}

internal fun ingredientLookupMessage(ids: List<String>, picker: MealPickerPresentation): String? {
    val missing = missingLabelIds(ids, picker)
    if (missing.isEmpty()) return null
    val sameRead = ids.toSet() == picker.requestedLabelIds.toSet()
    if (!sameRead) return "${missing.size} ingredient name(s) need loading."
    return when {
        picker.labelPhase == IngredientPickerPhase.LOADING -> "Loading this recipe’s ingredient names…"
        picker.labelIssue == IngredientPickerIssue.OFFLINE -> "You’re offline. Downloaded names stay available; reconnect to load the rest."
        picker.labelIssue == IngredientPickerIssue.RETRY_LATER -> "Names could not load yet. Try again later." +
            (picker.labelRetryAfterSeconds?.let { " Server delay: $it seconds." } ?: "")
        picker.labelIssue == IngredientPickerIssue.INVALID_IDS -> "These ingredient references cannot be loaded within the configured lookup limit. No names have been guessed."
        picker.labelPhase == IngredientPickerPhase.ERROR || picker.labelPhase == IngredientPickerPhase.UNAVAILABLE ->
            "Ingredient names could not be loaded. Your recipe and cooking progress are unchanged."
        picker.labelPhase == IngredientPickerPhase.READY || picker.labelPhase == IngredientPickerPhase.EMPTY ->
            "${missing.size} name(s) remain unavailable in the current catalog. Don’t guess which ingredients they are."
        else -> "${missing.size} ingredient name(s) need loading."
    }
}

@Composable
internal fun IngredientNameLookup(ids: List<String>, picker: MealPickerPresentation, busy: Boolean, load: (() -> Unit)?) {
    val message = ingredientLookupMessage(ids, picker) ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, style = MaterialTheme.typography.bodySmall)
        load?.let {
            OutlinedButton(onClick = it, enabled = !busy && picker.labelPhase != IngredientPickerPhase.LOADING,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Load ingredient names") }
        }
    }
}
