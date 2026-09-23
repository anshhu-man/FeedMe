package com.feedme.app.mealflow

import com.feedme.app.blueprint.*
import com.feedme.mealflow.CookbookIssue
import com.feedme.mealflow.CookbookPhase
import com.feedme.mealflow.CookbookScreen
import com.feedme.mealflow.CookbookState

/** A readable Saved snapshot in the original RECIPE layout, never a fabricated Plan or
 * permission to cook, copy or share. Management and exact-token recovery stay in More. */
internal fun blueprintSavedRecipeState(state: CookbookState, picker: MealPickerPresentation,
    busy: Boolean, choices: MealInputChoices?, actions: CookbookScreenActions): BlueprintRecipeState? {
    if (state.screen != CookbookScreen.DETAIL || busy || state.busy || state.pending != null ||
        state.deleteConfirmation != null || state.failureReason != null || state.issue != CookbookIssue.NONE ||
        state.phase !in setOf(CookbookPhase.IDLE, CookbookPhase.READY)) return null
    val selected = state.selected ?: return null
    val saved = selected.savedRecipe ?: return null
    if (selected.id != saved.id.value) return null
    val view = SavedRecipePresentation(selected)
    val recipe = view.recipe ?: return null
    val names = picker.knownIngredients.associate { it.id to it.name }
    val metrics = BlueprintMealMetrics(
        totalMinutes = exactSavedMetric(recipe.totalMinutes.jsonToken) ?: return null,
        activeMinutes = exactSavedMetric(recipe.activeMinutes.jsonToken) ?: return null,
        cleanup = recipe.cleanupMinutes.valueOrNull()?.jsonToken?.let { "$it min" } ?: "unknown",
        servings = exactSavedMetric(recipe.servings.jsonToken) ?: return null,
    )
    val detail = BlueprintRecipeDetail(
        card = BlueprintMealCard(
            identity = BlueprintMealIdentity(recipe.recipeId.value, recipe.id.value, savedRecipeId = selected.id),
            title = view.title, description = recipe.summary.valueOrNull().orEmpty(), metrics = metrics,
            badge = if (view.downloaded) "Downloaded · historical"
                else if (state.historical) "Historical observation" else "Saved copy",
        ),
        ingredients = recipe.ingredients.map { ingredient ->
            BlueprintRecipeIngredient(
                name = (recipeIngredientLabel(ingredient.ingredientId.value, names)
                    ?: "Ingredient label unavailable (${ingredient.ingredientId.value})") +
                    (if (ingredient.optional) " · optional" else "") +
                    ingredient.preparation.valueOrNull()?.let { " · $it" }.orEmpty(),
                amount = "${ingredient.quantity.jsonToken} ${ingredient.unit}",
                // Saved ingredients do not establish what is available in today's pantry.
                availability = null,
            )
        },
        steps = recipe.steps.map { step ->
            BlueprintRecipeStep("Step ${step.position.jsonToken}" +
                if (step.mandatorySafetyStep) " · Required safety step" else "",
                buildList {
                    add(step.instruction)
                    recipeStepIngredients(recipe, step).forEach { add("Ingredient: ${savedIngredientLine(it, names)}") }
                    step.durationSeconds.valueOrNull()?.let {
                        add("Suggested duration: ${it.jsonToken} seconds · manage timers separately")
                    }
                    if (step.requiredEquipmentIds.isNotEmpty()) add("Uses: " +
                        step.requiredEquipmentIds.joinToString { recipeEquipmentLabel(it, choices) })
                }.joinToString("\n"))
        },
        provenance = BlueprintRecipeProvenance(
            source = "Saved copy · ${saved.sourceType}" +
                saved.creatorLabel.valueOrNull()?.let { " · Recorded creator: $it" }.orEmpty(),
            review = "Recorded review status: ${recipe.reviewStatus}" +
                recipe.reviewerLabel.valueOrNull()?.let { " · Recorded reviewer: $it" }.orEmpty() +
                recipe.reviewedAt.valueOrNull()?.let { " · Recorded review date: $it" }.orEmpty(),
            version = "Saved-copy revision ${saved.version.jsonToken} · recipe revision ${recipe.version.jsonToken}",
            licenseSummary = buildList {
                saved.contentLicense.valueOrNull()?.let { add("Saved-copy license: $it") }
                recipe.contentLicense.valueOrNull()?.let { add("Recorded recipe license: $it") }
            }.joinToString("\n").takeIf(String::isNotBlank),
        ),
        equipment = recipe.equipmentIds.map { recipeEquipmentLabel(it, choices) },
        safetyNotes = buildList {
            add("This saved copy is not a selected cooking plan or a current rights, recall or pantry-availability check.")
            if (recipeLabelIds(recipe).any { recipeIngredientLabel(it, names) == null })
                add("Unknown ingredient names are shown explicitly. Load names in More; do not substitute a guess.")
            recipe.waitingMinutes.valueOrNull()?.let { add("Waiting: ${it.jsonToken} min") }
            recipe.cleanupMinutes.valueOrNull()?.let { add("Cleanup: ${it.jsonToken} min") }
            recipe.estimateNote.valueOrNull()?.let { add("Estimate notes: $it") }
        },
    )
    return BlueprintRecipeState(detail, controls = BlueprintDiscoveryControls(
        allowedActions = buildSet {
            if (actions.cookAgain != null) add(BlueprintDiscoveryAction.RECIPE_COOK)
            if (actions.makeMine != null) add(BlueprintDiscoveryAction.RECIPE_ADAPT)
            if (actions.shareRecipe != null && actions.guardedRecipeAction != null) add(BlueprintDiscoveryAction.RECIPE_SHARE)
        },
        message = buildList {
            add(view.notice)
            add("Refresh, download and removal are in More. Your saved copy and current meal are unchanged by this view.")
            ingredientLookupMessage(recipeLabelIds(recipe), picker)?.let(::add)
        }.joinToString("\n\n"),
        actionMessages = mapOf(
            BlueprintDiscoveryAction.RECIPE_COOK to "Review today's ingredients, time and energy first. A fresh check comes before cooking.",
            BlueprintDiscoveryAction.RECIPE_ADAPT to "Check this saved source before requesting your own version. Nothing is replaced automatically.",
            BlueprintDiscoveryAction.RECIPE_SAVE to "This is your existing saved copy; opening it does not make another Save.",
            BlueprintDiscoveryAction.RECIPE_SHARE to if (actions.shareRecipe != null && actions.guardedRecipeAction != null)
                "Start a private local draft with this saved recipe reference. Review the source, attachment and audience separately; nothing is posted automatically."
                else "Sharing this saved copy is not connected here. No post or sharing permission is created.",
        ),
    ))
}

/** Preserve unsupported numeric lexemes in the existing reader instead of rounding them. */
private fun exactSavedMetric(token: String): Int? = token.toIntOrNull()?.takeIf { it.toString() == token }

internal fun dispatchBlueprintSavedRecipe(state: BlueprintRecipeState, action: BlueprintDiscoveryAction,
    current: () -> Boolean, actions: CookbookScreenActions) {
    fun admitted() = current() && actions.blueprintIsCurrent() &&
        state.controls.permits(BlueprintScreenId.RECIPE, action, state.visibleRecipe != null)
    if (!admitted()) return
    val callback = when (action) {
        BlueprintDiscoveryAction.RECIPE_COOK -> actions.cookAgain
        BlueprintDiscoveryAction.RECIPE_ADAPT -> actions.makeMine
        BlueprintDiscoveryAction.RECIPE_SHARE -> actions.shareRecipe
        else -> null
    } ?: return
    val guarded = actions.guardedRecipeAction
    if (guarded != null) guarded(action, ::admitted)
    else if (action != BlueprintDiscoveryAction.RECIPE_SHARE) callback()
}
