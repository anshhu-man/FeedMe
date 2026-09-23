package com.feedme.app.mealflow

import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import com.feedme.app.blueprint.BlueprintConfirmationScreen
import com.feedme.app.blueprint.BlueprintConfirmationState

/** Original CONFIRM_ACTION design with one exact, expiring private-copy selection. */
@Composable
internal fun FeedMePostSaveReview(experience: MealFlowExperience, review: SocialRecipeSaveReview,
    form: MealFormState, hostIsCurrent: () -> Boolean, onConfirm: () -> Unit, onCancel: () -> Unit,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit) {
    fun current() = hostIsCurrent() && experience.postSaveReview.value === review
    var expired by remember(review) { mutableStateOf(!review.prepared.isCurrent) }
    LaunchedEffect(review) {
        while (review.prepared.isCurrent) delay(1_000L)
        expired = true
    }
    val valid = !expired && current() && review.prepared.isCurrent
    val view = BlueprintConfirmationState(
        actionLabel = "Save recipe privately",
        affectedSummary = if (valid) review.prepared.recipe.title else "Recipe selection expired",
        consequences = if (valid) listOf(
            "From ${review.prepared.creatorLabel}.",
            "Save a private recipe copy in Saved. This does not publish or share a post.",
            "${review.prepared.recipe.totalMinutes.jsonToken} min total · ${review.prepared.recipe.activeMinutes.jsonToken} min hands-on.",
            "Copy policy version ${review.prepared.grantPolicyVersion}. Nothing is marked Make Again.") else
            listOf("Return to the post and choose Save again to check current availability. No new save is authorized here."),
        canConfirm = valid, canCancel = current(), busy = form.busy,
        status = if (form.failure != null) "Couldn’t complete this action. Any retained original is kept for recovery." else null)
    platformBackHandler(current() && !form.busy) { if (current() && !experience.forms.value.busy) onCancel() }
    BlueprintConfirmationScreen(view,
        onConfirm = { if (it === view && view.confirmEnabled && current() && review.prepared.isCurrent) onConfirm() },
        onCancel = { if (it === view && view.cancelEnabled && current()) onCancel() },
        heading = "Keep this\nrecipe?", confirmLabel = "Save recipe")
}
