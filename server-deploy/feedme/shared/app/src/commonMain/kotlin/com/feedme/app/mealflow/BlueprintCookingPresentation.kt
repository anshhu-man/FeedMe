package com.feedme.app.mealflow

import com.feedme.app.blueprint.*
import com.feedme.kitchen.CookingStatus
import com.feedme.mealflow.CookingFlowPhase
import com.feedme.mealflow.CookingFlowScreen
import com.feedme.mealflow.MealFlowPhase
import com.feedme.mealflow.MealFlowScreen
import com.feedme.mealflow.MealFlowIssue

/** Lossless view adapter for the actual retained cooking owner. No session is created here.
 * Recovery/paused/unknown states retain the existing explicit-action reader. */
internal fun blueprintCookingState(view: CookingScreenState, labels: Map<String, String>,
    choices: MealInputChoices, busy: Boolean, hasAdvance: Boolean, hasTimers: Boolean,
    hasPauseAndLeave: Boolean = false): BlueprintCookState? {
    if (view.screen != CookingFlowScreen.COOK || view.phase != CookingFlowPhase.COOKING ||
        view.status != CookingStatus.ACTIVE || !view.instructionsVisible || view.failure != null ||
        view.starting || view.sessionId.isNullOrBlank()) return null
    val plan = view.plan ?: return null
    val recipe = view.recipe ?: return null
    val ids = recipe.steps.map { it.stepId.value }
    if (ids.any { it.isBlank() } || ids.distinct().size != ids.size ||
        recipe.steps.withIndex().any { (index, step) -> step.position.jsonToken != (index + 1).toString() }) return null
    // A malformed join is not silently dropped to make the new card fit.
    if (recipe.steps.any { step -> step.ingredientIds.any { id ->
        recipe.ingredients.count { it.ingredientId.value.equals(id.value, true) } != 1
    } }) return null
    val meal = MealPlanPresentation(plan, view.historical, labels)
    val allowed = buildSet {
        add(BlueprintCookingAction.COOK_BACK)
        if (!busy) {
            add(BlueprintCookingAction.INGREDIENTS)
            if (hasTimers && view.timerManagementVisible) add(BlueprintCookingAction.STEP_TIMER)
            if (view.canEdit) {
                if (hasAdvance) add(BlueprintCookingAction.NEXT_STEP)
                add(BlueprintCookingAction.PREVIOUS_STEP)
            }
            if (view.canStop && hasPauseAndLeave) add(BlueprintCookingAction.PAUSE_AND_LEAVE)
            if (view.canComplete) add(BlueprintCookingAction.FINISH_MEAL)
        }
    }
    return BlueprintCookState(sessionId = view.sessionId, mealTitle = recipe.title,
        steps = recipe.steps.map { step -> BlueprintCookingStep(
            id = step.stepId.value, title = step.instruction,
            instruction = buildList {
                if (step.mandatorySafetyStep) add("Required safety step")
                step.durationSeconds.valueOrNull()?.let { add("Suggested duration: ${it.jsonToken} seconds · manage timers separately") }
            }.joinToString("\n"),
            quantity = recipeStepIngredients(recipe, step).takeIf { it.isNotEmpty() }?.joinToString("\n", transform = meal::ingredientLine),
            equipment = step.requiredEquipmentIds.takeIf { it.isNotEmpty() }?.joinToString { recipeEquipmentLabel(it, choices) },
        ) }, currentStepId = view.currentStepId, completedStepIds = view.completedStepIds.toSet(),
        allowedActions = allowed,
        status = buildList {
            if (busy) add("Saving your action…")
            cookingIssueMessage(view.issue)?.let(::add)
            if (view.localPendingCount > 0) add("${view.localPendingCount} local action(s) waiting to sync. Open More to sync explicitly.")
            if (view.pending.isNotEmpty()) add("An original action needs review in More.")
        }.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        provenanceNote = "Marked progress is not a food-safety guarantee. Follow every required safety step." +
            if (view.historical) " Retained instructions are not a fresh recall or server-state check." else "",
    )
}

/** Whether cached navigation can reveal ordinary Home without covering an unresolved
 * original, source-specific request, proposal or failure. Never clears any main-meal data. */
internal fun blueprintCookingHomeEligible(meal: MealScreenState, form: MealFormState): Boolean {
    val values = form.values ?: return false
    return meal.screen in setOf(MealFlowScreen.REQUEST, MealFlowScreen.RECOMMENDATIONS, MealFlowScreen.RECIPE) &&
        meal.phase in setOf(MealFlowPhase.EDITING, MealFlowPhase.OFFLINE_DRAFT, MealFlowPhase.READY) &&
        meal.issue == MealFlowIssue.NONE && meal.failureReason == null &&
        !meal.pendingMatchesDraft && meal.retryAtMillis == null &&
        meal.proposal == null && meal.adaptation == null && meal.pendingAdaptation == null &&
        meal.rootSource == null && meal.rootProposal == null && meal.pendingRootDraft == null &&
        !form.busy && form.failure == null && values.savedRecipeId == null &&
        values.sourceRecipeVersionId == null && values.sourcePostId == null && values.sourcePostVersion == null && !values.savedMakeMine
}

internal fun blueprintCompletedState(view: CookingScreenState, busy: Boolean,
    hasCookbook: Boolean, hasFeedback: Boolean = false, hasReuse: Boolean = false,
    hasShare: Boolean = false, hasMakeAgain: Boolean = false, doneForNowAvailable: Boolean = true,
    todayAvailable: Boolean = false, inboxAvailable: Boolean = false, profileAvailable: Boolean = false): BlueprintMealDoneState? {
    if (view.screen != CookingFlowScreen.COOK || !view.done || !view.instructionsVisible ||
        view.sessionId.isNullOrBlank()) return null
    val recipe = view.recipe ?: return null
    return BlueprintMealDoneState(BlueprintCompletedMeal(view.sessionId, recipe.title),
        allowedActions = buildSet {
            add(BlueprintCookingAction.DONE_BACK)
            if (doneForNowAvailable) add(BlueprintCookingAction.DONE_FOR_NOW)
            add(BlueprintCookingAction.DONE_COOK)
            if (!busy && todayAvailable) add(BlueprintCookingAction.DONE_TODAY)
            if (!busy && inboxAvailable) add(BlueprintCookingAction.DONE_INBOX)
            if (!busy && profileAvailable) add(BlueprintCookingAction.DONE_PLATE)
            if (!busy && hasCookbook) add(BlueprintCookingAction.DONE_COOKBOOK)
            if (!busy && hasFeedback) add(BlueprintCookingAction.QUICK_THOUGHT)
            if (!busy && hasReuse) add(BlueprintCookingAction.REUSE_INGREDIENT)
            if (!busy && hasShare) add(BlueprintCookingAction.SHARE_TAKE)
            if (!busy && hasMakeAgain) add(BlueprintCookingAction.MAKE_AGAIN)
        }, status = view.completionText)
}

/** Navigation never borrows a newer state; the caller additionally checks its actual host owner. */
internal fun dispatchBlueprintCooking(state: BlueprintCookState, action: BlueprintCookingAction,
    current: Boolean, actions: CookingScreenActions) {
    if (!current || !state.allows(action)) return
    when (action) {
        BlueprintCookingAction.COOK_BACK, BlueprintCookingAction.INGREDIENTS -> actions.back()
        BlueprintCookingAction.NEXT_STEP -> actions.advance?.invoke()
        BlueprintCookingAction.PREVIOUS_STEP -> state.steps.getOrNull(state.currentIndex - 1)?.let { actions.move(it.id) }
        BlueprintCookingAction.STEP_TIMER -> actions.timers?.invoke()
        BlueprintCookingAction.PAUSE_AND_LEAVE -> actions.pauseAndLeave?.invoke()
        BlueprintCookingAction.FINISH_MEAL -> actions.complete() // Existing explicit completion confirmation.
        else -> Unit
    }
}

internal fun dispatchBlueprintCompletion(state: BlueprintMealDoneState, action: BlueprintCookingAction,
    current: Boolean, actions: CookingScreenActions, continuation: () -> Boolean = { current }) {
    if (!current || !state.allows(action)) return
    when (action) {
        BlueprintCookingAction.DONE_BACK, BlueprintCookingAction.DONE_COOK -> actions.leave()
        BlueprintCookingAction.DONE_FOR_NOW -> actions.doneForNow?.invoke(continuation) ?: actions.leave()
        BlueprintCookingAction.DONE_TODAY -> actions.today?.invoke(continuation)
        BlueprintCookingAction.DONE_INBOX -> actions.inbox?.invoke(continuation)
        BlueprintCookingAction.DONE_PLATE -> actions.profile?.invoke(continuation)
        BlueprintCookingAction.DONE_COOKBOOK -> actions.cookbook?.invoke()
        BlueprintCookingAction.QUICK_THOUGHT -> actions.feedback?.invoke()
        BlueprintCookingAction.REUSE_INGREDIENT -> actions.reuse?.invoke()
        BlueprintCookingAction.SHARE_TAKE -> actions.share?.invoke()
        BlueprintCookingAction.MAKE_AGAIN -> actions.makeAgain?.invoke()
        else -> Unit
    }
}
