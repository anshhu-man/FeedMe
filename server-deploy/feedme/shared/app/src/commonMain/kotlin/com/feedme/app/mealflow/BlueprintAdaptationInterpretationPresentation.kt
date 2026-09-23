package com.feedme.app.mealflow

import androidx.compose.runtime.Composable
import com.feedme.app.blueprint.BlueprintConfirmationScreen
import com.feedme.app.blueprint.BlueprintConfirmationState
import com.feedme.core.ports.FailureReason
import com.feedme.mealflow.MealEnergy
import com.feedme.mealflow.MealInterpretationProposal
import com.feedme.mealflow.MealInterpretationUnresolvedReason

/** Review-only projection for ADAPT. The owner still fences the exact proposal and form. */
internal fun blueprintAdaptationInterpretationPresentation(proposal: MealInterpretationProposal,
    values: AdaptationFormValues, canConfirm: Boolean = false, busy: Boolean = false,
    failure: FailureReason? = null): BlueprintConfirmationState {
    val names = proposal.ingredientOptions.associateBy { it.id }
    val meal = values.meal
    val summary = buildString {
        append("Your words\n"); append(proposal.originalText)
        append("\n\nProposed Make Mine changes\nIngredients: ")
        if (proposal.suggestedIngredientIds.isEmpty()) append("Not mentioned · keep the current selection")
        else proposal.suggestedIngredientIds.forEachIndexed { index, id ->
            if (index > 0) append("\n")
            append(names[id]?.name ?: "Ingredient label unavailable"); append(" · "); append(id)
        }
        append("\nEnergy: ")
        append(proposal.energy?.let(::adaptationInterpretationEnergy)
            ?: "Not mentioned · keep ${adaptationInterpretationEnergy(meal.energy)}")
        append("\nTotal minutes: ")
        append(proposal.maxTotalMinutes?.toString() ?: "Not mentioned · keep ${adaptationLimit(meal.totalMinutes)}")
        append("\nHands-on minutes: ")
        append(proposal.maxActiveMinutes?.toString() ?: "Not mentioned · keep ${adaptationLimit(meal.activeMinutes)}")
        append("\n\nStill unchanged")
        append("\nServings: "); append(meal.servings)
        append("\nCleanup minutes: "); append(adaptationLimit(meal.cleanupMinutes))
        append("\nPreparation style: "); append(meal.requiredPreparationTags.joinToString().ifEmpty { "Any" })
        append("\nEquipment IDs: "); append(meal.equipmentIds.joinToString().ifEmpty { "None selected" })
        append("\nExplicit exclusion IDs: "); append(meal.exclusions.joinToString().ifEmpty { "None entered" })
        append("\nTaste choices: "); append(meal.tasteTags.joinToString().ifEmpty { "None selected" })
        append("\nAdaptation reason: "); append(values.reason.name.lowercase().replace('_', ' '))
        values.replaceIngredientId?.let { append("\nMissing ingredient ID: "); append(it) }
        values.requestedReplacementId?.let { append("\nRequested replacement ID: "); append(it) }
        values.retainTasteTag?.let { append("\nRetained taste: "); append(it) }
        if (proposal.unresolvedIngredients.isNotEmpty()) {
            append("\n\nNeeds your correction\n")
            proposal.unresolvedIngredients.forEachIndexed { index, item ->
                if (index > 0) append('\n')
                append(item.text); append(" · ")
                append(when (item.reason) {
                    MealInterpretationUnresolvedReason.UNKNOWN -> "not identified"
                    MealInterpretationUnresolvedReason.AMBIGUOUS -> "more than one possible ingredient"
                })
            }
        }
    }
    return BlueprintConfirmationState(
        actionLabel = "Apply these details to my Make Mine form",
        affectedSummary = summary,
        consequences = listOf(
            "Confirm changes only this unsent Make Mine form. It does not request a version, save, cook or share.",
            "After confirming, review the form and explicitly choose Make my version to send it.",
            "Ingredient matches are suggestions, not proof of availability, freshness or dietary safety. Your exclusions remain fixed.",
        ),
        canConfirm = canConfirm && proposal.requiresUserConfirmation && proposal.unresolvedIngredients.isEmpty(),
        canCancel = true,
        busy = busy,
        status = failure?.let(::mealInterpretationFailureText) ?: when {
            proposal.unresolvedIngredients.isNotEmpty() ->
                "Cancel to correct your words or use My ingredients. Nothing has been applied."
            !canConfirm -> "These details cannot be applied to the current Make Mine form. Cancel and review it."
            else -> null
        },
    )
}

@Composable
internal fun BlueprintAdaptationInterpretationReview(state: AdaptationInterpretationState,
    values: AdaptationFormValues, actions: MealScreenActions) {
    val proposal = state.proposal ?: return
    val current = actions.adaptationInterpretationCurrent()
    val shown = if (!current) BlueprintConfirmationState(canConfirm = false,
        status = "Your Make Mine form or route changed. The old interpretation is hidden. Cancel to review the current form.")
    else blueprintAdaptationInterpretationPresentation(proposal, values,
        canConfirm = actions.confirmAdaptationInterpretation != null && current,
        busy = state.busy, failure = state.failure)
    BlueprintConfirmationScreen(shown, heading = "Review your\nMake Mine changes.",
        confirmLabel = "Apply to my form",
        onConfirm = { exact ->
            if (exact === shown && shown.confirmEnabled && actions.adaptationInterpretationCurrent())
                actions.confirmAdaptationInterpretation?.invoke()
        },
        onCancel = { exact ->
            if (exact === shown && shown.cancelEnabled && actions.blueprintCurrent())
                actions.dismissAdaptationInterpretation?.invoke()
        })
}

private fun adaptationInterpretationEnergy(value: MealEnergy): String = when (value) {
    MealEnergy.ASSEMBLE -> "assemble only"
    MealEnergy.LITTLE -> "a little cooking"
    MealEnergy.HAPPY -> "happy to cook"
}

private fun adaptationLimit(value: String) = value.ifEmpty { "no limit entered" }
