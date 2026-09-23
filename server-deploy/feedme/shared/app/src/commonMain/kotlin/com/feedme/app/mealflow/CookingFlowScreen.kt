package com.feedme.app.mealflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.feedme.app.blueprint.BlueprintCookScreen
import com.feedme.app.blueprint.BlueprintMealDoneScreen
import com.feedme.app.FeedMeColors
import com.feedme.app.FeedMeDetails
import com.feedme.app.FeedMeWordmark
import com.feedme.kitchen.CookingStatus
import com.feedme.mealflow.CookingFlowPhase
import com.feedme.mealflow.CookingFlowScreen

class CookingScreenActions(val back: () -> Unit, val leave: () -> Unit, val reviewStart: () -> Unit,
    val retryStart: () -> Unit, val discardStart: () -> Unit, val readRetained: () -> Unit,
    val reopen: () -> Unit, val refresh: () -> Unit, val synchronize: () -> Unit,
    val move: (String) -> Unit, val mark: (String) -> Unit, val pause: () -> Unit, val resume: () -> Unit,
    val abandon: () -> Unit, val complete: () -> Unit, val timers: (() -> Unit)? = null,
    val cookbook: (() -> Unit)? = null, val save: (() -> Unit)? = null,
    val loadIngredientNames: (() -> Unit)? = null, val advance: (() -> Unit)? = null,
    val blueprintIsCurrent: () -> Boolean = { false }, val pauseAndLeave: (() -> Unit)? = null,
    val feedback: (() -> Unit)? = null, val reuse: (() -> Unit)? = null,
    val history: (() -> Unit)? = null, val share: (() -> Unit)? = null,
    val makeAgain: (() -> Unit)? = null,
    val doneForNow: ((() -> Boolean) -> Unit)? = null,
    val today: ((() -> Boolean) -> Unit)? = null,
    val inbox: ((() -> Boolean) -> Unit)? = null,
    val profile: ((() -> Boolean) -> Unit)? = null)

/** Closing More creates a new visit instead of reviving a captured completion callback. */
private class CookingToolsVisit {
    private var active = true
    var token: Any by mutableStateOf(Any())
        private set
    var visible: Boolean by mutableStateOf(false)
        private set
    fun current(expected: Any, tools: Boolean) = active && token === expected && visible == tools
    fun open(expected: Any) { if (current(expected, false)) { token = Any(); visible = true } }
    fun close(expected: Any) { if (current(expected, true)) { token = Any(); visible = false } }
    fun retire() { active = false }
}

/** Actual pinned cooking projection. Timer entry requires a real configured owner; no reducer,
 * scheduler, separate Save/Make Again or sample instructions are created by this screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RetainedCookingScreen(view: CookingScreenState, picker: MealPickerPresentation, choices: MealInputChoices,
    busy: Boolean, actions: CookingScreenActions, savedAction: CookingSavedActionNotice? = null,
    navigationNotice: String? = null) {
    val cook = if (actions.blueprintIsCurrent()) blueprintCookingState(view,
        picker.knownIngredients.associate { it.id to it.name }, choices, busy, actions.advance != null,
        actions.timers != null, actions.pauseAndLeave != null) else null
    val done = if (actions.blueprintIsCurrent()) blueprintCompletedState(view, busy, actions.cookbook != null,
        actions.feedback != null, actions.reuse != null, actions.share != null, actions.makeAgain != null,
        actions.doneForNow == null || !busy, todayAvailable = actions.today != null,
        inboxAvailable = actions.inbox != null, profileAvailable = actions.profile != null)?.let { original -> original.copy(
            status = listOfNotNull(original.status, navigationNotice).joinToString("\n").takeIf(String::isNotBlank)) } else null
    val tools = remember(view.sessionId, view.phase, view.currentStepId) { CookingToolsVisit() }
    DisposableEffect(tools) { onDispose { tools.retire() } }
    val visit = tools.token
    fun underlayCurrent() = tools.current(visit, false) && actions.blueprintIsCurrent()
    if (cook != null || done != null) {
        val more = { if (underlayCurrent()) tools.open(visit) }
        if (cook != null) BlueprintCookScreen(cook, { rendered, action ->
            if (rendered === cook) dispatchBlueprintCooking(rendered, action, underlayCurrent(), actions)
        }, onMore = more)
        else if (done != null) BlueprintMealDoneScreen(done, { rendered, action ->
            if (rendered === done) dispatchBlueprintCompletion(rendered, action, underlayCurrent(), actions, ::underlayCurrent)
        }, onMore = more)
        if (tools.visible) Dialog(onDismissRequest = { tools.close(visit) },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = FeedMeColors.Paper) {
                RetainedCookingTools(view, picker, choices, busy, actions, savedAction) { tools.close(visit) }
            }
        }
    } else RetainedCookingTools(view, picker, choices, busy, actions, savedAction)
}

/** Original recovery/acknowledgement controls remain available behind the HTML's More button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RetainedCookingTools(view: CookingScreenState, picker: MealPickerPresentation, choices: MealInputChoices,
    busy: Boolean, actions: CookingScreenActions, savedAction: CookingSavedActionNotice? = null,
    closeTools: (() -> Unit)? = null) {
    // A newly entered cooking screen starts at its heading, not the prepared recipe's
    // review-button offset. Ordinary progress/acknowledgement updates keep the reading position.
    val scroll = key(view.screen, view.sessionId, view.plan?.id?.value) { rememberScrollState() }
    // Keep the current step/completion at the same level when a retained session pauses or ends.
    val compactCook = view.screen == CookingFlowScreen.COOK && view.sessionId != null
    val terminalSession = view.status == CookingStatus.COMPLETED || view.status == CookingStatus.ABANDONED
    val primaryTerminalSync = terminalSession && view.sessionId != null && view.canSync
    val guidedStepVisible = view.instructionsVisible && view.screen == CookingFlowScreen.COOK && !terminalSession
    Column(Modifier.fillMaxSize().safeDrawingPadding().widthIn(max = 720.dp).verticalScroll(scroll)
        .padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(if (compactCook) 12.dp else 18.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = closeTools ?: actions.back, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (closeTools == null) "← Back" else "← Back to cooking")
            }
            FeedMeWordmark(compact = true)
            Pill(if (view.done) "MEAL DONE" else if (view.screen == CookingFlowScreen.RECIPE) "PINNED RECIPE" else "COOK", FeedMeColors.Lime)
        }
        if (!compactCook) Text("YOUR MEAL. YOUR PACE.", style = MaterialTheme.typography.labelMedium, color = FeedMeColors.Blue)
        Text(when {
            view.done -> "Done, on your terms."
            view.phase == CookingFlowPhase.START_CONFIRMATION -> "One clear yes."
            view.phase == CookingFlowPhase.ABANDONED -> "Stopped, not forgotten."
            view.screen == CookingFlowScreen.RECIPE -> "Your cooking plan."
            else -> "One step at a time."
        }, style = when {
            compactCook && terminalSession -> MaterialTheme.typography.headlineMedium
            compactCook -> MaterialTheme.typography.titleLarge
            else -> MaterialTheme.typography.headlineLarge
        },
            modifier = Modifier.semantics { heading() })
        if (!compactCook) Text("Go at your own pace. Back never ends your session.", color = FeedMeColors.Muted)
        cookingIssueMessage(view.issue)?.let { InfoCard("Cooking status", it) }
        view.failure?.let { InfoCard("Action not acknowledged", kitchenFailureMessage(it) ?: "Keep your original action and try again when available.") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        view.completionText?.let { InfoCard("Completion status", it) }
        actions.history?.let { history ->
            TextButton(enabled = !busy, onClick = history, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("What works for you")
            }
        }
        if (primaryTerminalSync) Primary("Sync this cooking session", !busy && view.canSync, actions.synchronize)
        val plan = view.plan
        val recipe = view.recipe
        if (plan == null) InfoCard("Instructions unavailable", "No eligible pinned recipe is exposed here. Read retained state or use Back; nothing starts automatically.")
        else {
            val meal = MealPlanPresentation(plan, view.historical, picker.knownIngredients.associate { it.id to it.name })
            Text(recipe?.title ?: "Retained plan",
                style = if (compactCook) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            if (!compactCook) recipe?.let { Text("${it.servings.jsonToken} servings · ${it.totalMinutes.jsonToken} min total", style = MaterialTheme.typography.bodyMedium, color = FeedMeColors.Muted) }
            if (view.phase == CookingFlowPhase.START_CONFIRMATION) {
                InfoCard("Prepared, not started", "Check this plan and any missing ingredients. Cooking starts only after your confirmation.")
                if (view.preparedRecipeVisible && plan.missingIngredients.isNotEmpty())
                    InfoCard("Missing items before cooking", plan.missingIngredients.joinToString("\n", transform = meal::ingredientLine))
                Primary("Review prepared start", !busy && view.preparedRecipeVisible, actions.reviewStart)
            } else if (view.screen == CookingFlowScreen.RECIPE && view.sessionId != null) {
                InfoCard("Your session’s recipe", "This stays with your cooking session, even if you choose another meal elsewhere.")
                Primary("Return to cooking", true, actions.reopen)
            }
            if (recipe != null && (view.instructionsVisible || view.preparedRecipeVisible)) {
                IngredientNameLookup(cookingLabelIds(view), picker, busy, actions.loadIngredientNames)
                if (guidedStepVisible) {
                    val step = view.currentStep!!
                    val completed = view.steps.count { it.stepId.value in view.completedStepIds }
                    Column(verticalArrangement = Arrangement.spacedBy(if (compactCook) 6.dp else 18.dp)) {
                        Text(cookingProgressLabel(completed, view.steps.size), style = MaterialTheme.typography.labelLarge, color = FeedMeColors.Blue)
                        LinearProgressIndicator(progress = { if (view.steps.isEmpty()) 0f else completed.toFloat() / view.steps.size },
                            modifier = Modifier.fillMaxWidth().height(6.dp), color = FeedMeColors.Blue, trackColor = FeedMeColors.Line)
                    }
                    Surface(color = FeedMeColors.Surface, shape = RoundedCornerShape(28.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
                        Column(Modifier.fillMaxWidth().padding(if (compactCook) 20.dp else 24.dp),
                            verticalArrangement = Arrangement.spacedBy(if (compactCook) 12.dp else 16.dp)) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Pill("STEP ${step.position.jsonToken}", FeedMeColors.SoftBlue)
                                if (step.mandatorySafetyStep) Pill("Required safety step", FeedMeColors.Lime)
                            }
                            Text(step.instruction, style = MaterialTheme.typography.titleLarge)
                            RecipeStepMetadata(recipe, step, meal::ingredientLine, choices)
                            Primary("Mark this step complete", !busy && view.canEdit && step.stepId.value !in view.completedStepIds,
                                { actions.mark(step.stepId.value) })
                            actions.timers?.takeIf { view.timerManagementVisible }?.let { open ->
                                OutlinedButton(onClick = open, enabled = !busy && !view.starting,
                                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Timers for this step") }
                            }
                            Text(if (step.stepId.value in view.completedStepIds) "Marked complete on this device" else "Not marked complete", color = FeedMeColors.Muted, style = MaterialTheme.typography.bodySmall)
                            val next = view.steps.getOrNull(view.steps.indexOf(step) + 1)
                            if (step.stepId.value in view.completedStepIds && next != null)
                                OutlinedButton(onClick = { actions.move(next.stepId.value) }, enabled = !busy && view.canEdit,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Go to step ${next.position.jsonToken}") }
                        }
                    }
                    Text("Choose a step · ${view.steps.size} in this plan", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        view.steps.forEach { stepOption ->
                            FilterChip(selected = stepOption.stepId.value == view.currentStepId,
                                onClick = { actions.move(stepOption.stepId.value) }, enabled = !busy && view.canEdit && stepOption.stepId.value != view.currentStepId,
                                modifier = Modifier.heightIn(min = 48.dp), label = {
                                    Text("Step ${stepOption.position.jsonToken}" + if (stepOption.stepId.value in view.completedStepIds) " · marked complete" else "")
                                })
                        }
                    }
                }
                SectionTitle("•", "Ingredients, always in reach.")
                recipe.ingredients.forEach { Text(meal.ingredientLine(it)) }
                Text("Equipment: " + recipe.equipmentIds.joinToString { recipeEquipmentLabel(it, choices) })
                if (view.screen == CookingFlowScreen.RECIPE) {
                    SectionTitle("•", "Read the whole plan.")
                    recipe.steps.forEach { step ->
                        Surface(color = if (step.mandatorySafetyStep) FeedMeColors.SoftBlue else FeedMeColors.Surface,
                            shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Step ${step.position.jsonToken}" + if (step.mandatorySafetyStep) " · Required safety step" else "",
                                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                                Text(step.instruction)
                                RecipeStepMetadata(recipe, step, meal::ingredientLine, choices)
                            }
                        }
                    }
                }
            } else if (view.sessionId != null) InfoCard("No usable step", "The exact current step or eligible instructions are unavailable. No step index has been guessed.")
        }
        if (view.timerManagementVisible && !guidedStepVisible) actions.timers?.let { open ->
            OutlinedButton(onClick = open, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Manage retained timers")
            }
            Text("View this session’s timer observations or explicitly cancel an alert. Opening this page does not start, resume or cancel a timer.",
                style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
        }
        if (view.historical) InfoCard("Historical observation", "Retained content and progress are not a fresh recall, rights or server-state check.")
        if (view.localPendingCount > 0) InfoCard("Progress on this device", "${view.localPendingCount} action(s) waiting to sync. Choose Sync when you’re ready; nothing sends automatically.")
        view.pending.forEach { pending ->
            InfoCard("Original action retained", cookingPendingSummary(pending))
            FeedMeDetails("Original action details") { Text(pending.message, style = MaterialTheme.typography.bodySmall) }
            if (pending.retryAtMillis > 0) Text("Retry time: ${kotlin.time.Instant.fromEpochMilliseconds(pending.retryAtMillis)}")
        }
        if (view.hasStartRetry) Primary("Retry original cooking start", !busy, actions.retryStart)
        if (view.canDiscardStart) OutlinedButton(onClick = actions.discardStart, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Discard unsent cooking start") }
        if (view.sessionId != null) {
            if (view.screen == CookingFlowScreen.COOK && !terminalSession) {
                if (view.status == CookingStatus.PAUSED) Primary("Resume cooking", !busy && view.canEdit, actions.resume)
                else OutlinedButton(onClick = actions.pause, enabled = !busy && view.canStop && view.status == CookingStatus.ACTIVE,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Pause cooking") }
                OutlinedButton(onClick = actions.abandon, enabled = !busy && view.canStop, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Stop this cooking session") }
                if (view.steps.isNotEmpty() && view.steps.all { it.stepId.value in view.completedStepIds })
                    Primary("Finish cooking", !busy && view.canComplete, actions.complete)
                else OutlinedButton(onClick = actions.complete, enabled = !busy && view.canComplete,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Finish cooking") }
            }
            if (!primaryTerminalSync) OutlinedButton(onClick = actions.synchronize, enabled = !busy && view.canSync,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Sync this cooking session") }
            OutlinedButton(onClick = actions.refresh, enabled = !busy && !view.starting, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Refresh downloaded session") }
        }
        OutlinedButton(onClick = actions.readRetained, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Read retained cooking state") }
        savedAction?.let { notice ->
            InfoCard(notice.title, notice.detail)
            actions.cookbook?.let { review ->
                TextButton(onClick = review, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(notice.reviewLabel) }
            }
        }
        if (savedAction == null) actions.cookbook?.let { TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open Saved") } }
        if (view.instructionsVisible && !view.starting) actions.save?.let {
            OutlinedButton(onClick = it, enabled = !busy && savedAction == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (savedAction == null) "Save this meal" else "Review pending Save first")
            }
            Text("Cooking from Saved doesn’t require another save. Saving a new copy needs a separate permission check.",
                style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
        }
        Text("Marked progress is not a food-safety guarantee. Follow every required safety step.", style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
        if (actions.timers == null) InfoCard("Timers unavailable", "Timers are not connected for this cooking session.")
        FeedMeDetails("About this cooking session") {
            if (compactCook) {
                Text("Go at your own pace. Back never ends your session.")
                recipe?.let { Text("${it.servings.jsonToken} servings · ${it.totalMinutes.jsonToken} min total",
                    style = MaterialTheme.typography.bodyMedium, color = FeedMeColors.Muted) }
            }
            Text("Save, sync and finishing are separate actions. Finishing never saves a recipe or shares a post.")
            if (actions.makeAgain != null) Text("I’d make this again saves this meal and records your repeat preference together. Nothing is shared.")
            else Text("Make Again is unavailable for this meal or connection. Ordinary Save does not record a repeat preference.")
            if (actions.share != null) Text("Share my take starts a blank private draft. Your meal is not attached automatically; choosing a photo, uploading and publishing remain separate actions. Existing draft recovery takes priority.")
            Text("Timers start only when you choose. Leaving a screen does not cancel them.")
            view.plan?.let { Text("Pinned plan: ${it.id.value}", style = MaterialTheme.typography.bodySmall) }
        }
        TextButton(onClick = actions.leave, modifier = Modifier.heightIn(min = 48.dp)) { Text("Return to the meal journey") }
    }
}

/** Display only: completion is bounded to the pinned plan, never inferred food readiness. */
internal fun cookingProgressLabel(completed: Int, total: Int): String =
    if (total <= 0) "Step progress unavailable" else "${completed.coerceIn(0, total)} of $total steps marked complete"

internal fun cookingPendingSummary(pending: CookingPendingRow): String = when (pending.phase) {
    "FINALIZATION_REQUIRED" -> pending.message
    "READY" -> "Your original action is queued. Sending still needs your explicit action and current checks."
    "IN_FLIGHT" -> "An attempt is in progress. Its result is not confirmed yet."
    "RETRY_WAIT" -> "Waiting for the original action’s retry time. Nothing retries automatically."
    "AWAITING_CONFIRMATION" -> "This action may have completed. Keep the original and use its retry; don’t start a replacement."
    "NEEDS_RESOLUTION" -> "The original action needs attention. It has not been replaced or automatically merged."
    "RECEIPT_READY" -> "A response is retained. Applying it locally still needs acknowledgement."
    else -> "Review this retained action before continuing. A visible status alone does not confirm it completed."
}
