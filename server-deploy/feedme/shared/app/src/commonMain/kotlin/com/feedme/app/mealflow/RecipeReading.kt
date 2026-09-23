package com.feedme.app.mealflow

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.feedme.contracts.IngredientAmountWire
import com.feedme.contracts.RecipeStepWire
import com.feedme.contracts.RecipeVersionWire

/** Reading an already admitted recipe never creates a cooking/Save capability. Keep original
 * ingredient ordering and numeric lexemes; UUID casing does not identify a different food.
 * This is a display join, not a substitute for canonical recipe/reference validation. */
internal fun recipeStepIngredients(recipe: RecipeVersionWire, step: RecipeStepWire): List<IngredientAmountWire> =
    recipe.ingredients.filter { ingredient ->
        step.ingredientIds.any { it.value.equals(ingredient.ingredientId.value, ignoreCase = true) }
    }

/** Conflicting case aliases are unresolved rather than silently choosing a label. */
internal fun recipeIngredientLabel(id: String, names: Map<String, String>): String? =
    names.entries.filter { it.key.equals(id, ignoreCase = true) }.map { it.value }.distinct()
        .singleOrNull()?.takeIf { it.isNotBlank() }

internal fun recipeEquipmentLabel(id: String, choices: MealInputChoices?): String =
    choices?.equipment?.firstOrNull { it.id == id }?.label ?: "Unresolved equipment ($id)"

/** Exact retained metadata only. Caller gates recipe visibility and owns safety-step labeling.
 * A recorded duration is neither a started timer nor a food-safety/doneness claim. */
@Composable
internal fun RecipeStepMetadata(recipe: RecipeVersionWire, step: RecipeStepWire,
    ingredientLine: (IngredientAmountWire) -> String, choices: MealInputChoices? = null) {
    recipeStepIngredients(recipe, step).forEach { Text(ingredientLine(it), style = MaterialTheme.typography.bodyMedium) }
    step.durationSeconds.valueOrNull()?.let {
        Text("Suggested duration: ${it.jsonToken} seconds · manage timers separately", style = MaterialTheme.typography.bodySmall)
    }
    if (step.requiredEquipmentIds.isNotEmpty()) Text(
        "Uses: " + step.requiredEquipmentIds.joinToString { recipeEquipmentLabel(it, choices) },
        style = MaterialTheme.typography.bodySmall)
}
