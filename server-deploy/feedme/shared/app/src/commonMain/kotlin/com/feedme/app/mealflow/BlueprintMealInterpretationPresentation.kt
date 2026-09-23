package com.feedme.app.mealflow

import androidx.compose.runtime.Composable
import com.feedme.app.blueprint.BlueprintConfirmationScreen
import com.feedme.app.blueprint.BlueprintConfirmationState
import com.feedme.core.ports.FailureReason
import com.feedme.mealflow.MealEnergy
import com.feedme.mealflow.MealInterpretationProposal
import com.feedme.mealflow.MealInterpretationUnresolvedReason

/** Only a review projection. The exact owner-issued proposal and target still govern Confirm. */
internal fun blueprintMealInterpretationPresentation(proposal: MealInterpretationProposal, values: MealFormValues,
    canConfirm: Boolean = false, busy: Boolean = false, failure: FailureReason? = null): BlueprintConfirmationState {
    val names = proposal.ingredientOptions.associateBy { it.id }
    val summary = buildString {
        append("Your words\n"); append(proposal.originalText)
        append("\n\nSuggested ingredients · replace this meal’s selected ingredients\n")
        if (proposal.suggestedIngredientIds.isEmpty()) append("No ingredients identified.")
        else proposal.suggestedIngredientIds.forEachIndexed { index, id ->
            if (index > 0) append('\n')
            append(names[id]?.name ?: "Ingredient label unavailable"); append(" · "); append(id)
        }
        append("\n\nSuggested limits\nEnergy: ")
        append(proposal.energy?.let(::interpretationEnergy) ?: "Not mentioned · keep ${interpretationEnergy(values.energy)}")
        append("\nTotal minutes: "); append(proposal.maxTotalMinutes?.toString() ?: "Not mentioned · keep ${limit(values.totalMinutes)}")
        append("\nHands-on minutes: "); append(proposal.maxActiveMinutes?.toString() ?: "Not mentioned · keep ${limit(values.activeMinutes)}")
        append("\n\nStill unchanged\nMode: "); append(values.mode.name.lowercase())
        append("\nServings: "); append(values.servings)
        append("\nCleanup minutes: "); append(limit(values.cleanupMinutes))
        append("\nEquipment IDs: "); append(values.equipmentIds.joinToString().ifEmpty { "None selected" })
        append("\nExplicit exclusion IDs: "); append(values.exclusions.joinToString().ifEmpty { "None entered" })
        append("\nTaste choices: "); append(values.tasteTags.joinToString().ifEmpty { "None selected" })
        append("\nPrepared meal: "); append(values.baseDescription.ifEmpty { "Not entered" })
        append("\nPrepared-meal status: "); append(values.basePreparation.name.lowercase().replace('_', ' '))
        append("\nPrepared-meal ingredient IDs: ")
        append(values.baseIngredientIds?.joinToString()?.ifEmpty { "None entered" } ?: "Not confirmed")
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
    return BlueprintConfirmationState(actionLabel = "Use these details in my draft", affectedSummary = summary,
        consequences = listOf("Confirm changes only this unsaved meal draft. It does not save, find a meal or start cooking.",
            "After confirming, explicitly choose Find my dinner to request a meal. More keeps all manual choices and saving available.",
            "Ingredient names are suggestions, not proof of availability, freshness or dietary safety. Saved preferences remain in force."),
        canConfirm = canConfirm && proposal.requiresUserConfirmation && proposal.unresolvedIngredients.isEmpty(),
        canCancel = true, busy = busy,
        status = failure?.let(::mealInterpretationFailureText) ?: if (proposal.unresolvedIngredients.isNotEmpty())
            "Cancel to correct your words or use the manual ingredient picker. Nothing has been applied."
        else if (!canConfirm) "These details cannot be applied to the current draft. Cancel and review your inputs." else null)
}

internal fun mealInterpretationFailureText(reason: FailureReason): String = when (reason) {
    FailureReason.OFFLINE -> "You’re offline. Your words are retained; try again when connected or use manual ingredients."
    FailureReason.INVALID_DATA -> "Those details could not be verified. Check your words or use the manual ingredient picker."
    FailureReason.CONFLICT -> "Your draft or route changed. Review your current inputs before trying again."
    FailureReason.UNAUTHENTICATED, FailureReason.STALE_SESSION, FailureReason.FORBIDDEN -> "Access changed. Return to your account; no interpreted details were applied."
    FailureReason.RATE_LIMITED -> "Text matching is temporarily limited. Retry explicitly later or use manual ingredients."
    FailureReason.NOT_CONFIGURED -> "Text matching isn’t configured here. Use More for manual ingredients and Find a meal."
    else -> "Text matching is unavailable. Your draft is unchanged; try again explicitly or use manual ingredients."
}

internal fun blueprintMealInterpretationReviewState(proposal: MealInterpretationProposal, values: MealFormValues,
    current: Boolean, canConfirm: Boolean = false, busy: Boolean = false,
    failure: FailureReason? = null): BlueprintConfirmationState =
    if (!current) BlueprintConfirmationState(canConfirm = false,
        status = "Your draft or route changed. The old interpretation is hidden. Cancel to review the current request.")
    else blueprintMealInterpretationPresentation(proposal, values, canConfirm, busy, failure)

@Composable
internal fun BlueprintMealInterpretationReview(state: MealInterpretationState, form: MealFormState,
    actions: MealScreenActions) {
    val proposal = state.proposal ?: return
    val values = form.values ?: return
    val shown = blueprintMealInterpretationReviewState(proposal, values, current = actions.interpretationCurrent(),
        canConfirm = actions.confirmInterpretation != null && actions.interpretationCurrent(),
        busy = state.busy, failure = state.failure)
    BlueprintConfirmationScreen(shown,
        onConfirm = { exact -> if (exact === shown && shown.confirmEnabled && actions.interpretationCurrent()) actions.confirmInterpretation?.invoke() },
        onCancel = { exact -> if (exact === shown && shown.cancelEnabled && actions.blueprintCurrent()) actions.dismissInterpretation?.invoke() })
}

private fun interpretationEnergy(value: MealEnergy): String = when (value) {
    MealEnergy.ASSEMBLE -> "assemble only"
    MealEnergy.LITTLE -> "a little cooking"
    MealEnergy.HAPPY -> "happy to cook"
}
private fun limit(value: String) = value.ifEmpty { "no limit entered" }
