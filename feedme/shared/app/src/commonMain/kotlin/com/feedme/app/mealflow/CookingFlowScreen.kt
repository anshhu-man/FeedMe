package com.feedme.app.mealflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
    val cookbook: (() -> Unit)? = null, val save: (() -> Unit)? = null)

/** Actual pinned cooking projection. Timer entry requires a real configured owner; no reducer,
 * scheduler, separate Save/Make Again or sample instructions are created by this screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RetainedCookingScreen(view: CookingScreenState, picker: MealPickerPresentation, choices: MealInputChoices,
    busy: Boolean, actions: CookingScreenActions) {
    // A newly entered cooking screen starts at its heading, not the prepared recipe's
    // review-button offset. Ordinary progress/acknowledgement updates keep the reading position.
    val scroll = key(view.screen, view.sessionId) { rememberScrollState() }
    // Keep the current step/completion at the same level when a retained session pauses or ends.
    val compactCook = view.screen == CookingFlowScreen.COOK && view.sessionId != null
    val terminalSession = view.status == CookingStatus.COMPLETED || view.status == CookingStatus.ABANDONED
    val primaryTerminalSync = terminalSession && view.sessionId != null && view.canSync
    Column(Modifier.fillMaxSize().safeDrawingPadding().widthIn(max = 720.dp).verticalScroll(scroll)
        .padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(if (compactCook) 12.dp else 18.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = actions.back, modifier = Modifier.heightIn(min = 48.dp)) { Text("← Back") }
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
        if (primaryTerminalSync) Primary("Sync this cooking session", !busy && view.canSync, actions.synchronize)
        val plan = view.plan
        val recipe = view.recipe
        if (plan == null) InfoCard("Instructions unavailable", "No eligible pinned recipe is exposed here. Read retained state or use Back; nothing starts automatically.")
        else {
            Text(recipe?.title ?: "Retained plan",
                style = if (compactCook) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            if (!compactCook) recipe?.let { Text("${it.servings.jsonToken} servings · ${it.totalMinutes.jsonToken} min total", style = MaterialTheme.typography.bodyMedium, color = FeedMeColors.Muted) }
            if (view.phase == CookingFlowPhase.START_CONFIRMATION) {
                InfoCard("Prepared, not started", "Check this plan and any missing ingredients. Cooking starts only after your confirmation.")
                Primary("Review prepared start", !busy, actions.reviewStart)
            } else if (view.screen == CookingFlowScreen.RECIPE && view.sessionId != null) {
                InfoCard("Your session’s recipe", "This stays with your cooking session, even if you choose another meal elsewhere.")
                Primary("Return to cooking", true, actions.reopen)
            }
            if (recipe != null && view.instructionsVisible) {
                val meal = MealPlanPresentation(plan, view.historical, picker.knownIngredients.associate { it.id to it.name })
                if (view.screen == CookingFlowScreen.COOK && !view.done && view.status != CookingStatus.ABANDONED) {
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
                            recipe.ingredients.filter { it.ingredientId in step.ingredientIds }.forEach { Text(meal.ingredientLine(it)) }
                            step.durationSeconds.valueOrNull()?.let {
                                Text("Suggested duration: ${it.jsonToken} seconds · manage timers separately",
                                    style = if (compactCook) MaterialTheme.typography.bodySmall else LocalTextStyle.current)
                            }
                            Primary("Mark this step complete", !busy && view.canEdit && step.stepId.value !in view.completedStepIds,
                                { actions.mark(step.stepId.value) })
                            actions.timers?.let { open ->
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
                Text("Equipment: " + recipe.equipmentIds.joinToString { id -> choices.equipment.firstOrNull { it.id == id }?.label ?: "Unresolved equipment ($id)" })
                if (view.screen == CookingFlowScreen.RECIPE) recipe.steps.forEach { Text("Step ${it.position.jsonToken}: ${it.instruction}") }
            } else if (view.sessionId != null) InfoCard("No usable step", "The exact current step or eligible instructions are unavailable. No step index has been guessed.")
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
        actions.cookbook?.let { TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open my cookbook") } }
        if (view.instructionsVisible && !view.starting) actions.save?.let {
            OutlinedButton(onClick = it, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save this plan to cookbook") }
        }
        Text("Marked progress is not a food-safety guarantee. Follow every required safety step.", style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
        if (actions.timers == null) InfoCard("Timers unavailable", "Timers are not connected for this cooking session.")
        FeedMeDetails("About this cooking session") {
            if (compactCook) {
                Text("Go at your own pace. Back never ends your session.")
                recipe?.let { Text("${it.servings.jsonToken} servings · ${it.totalMinutes.jsonToken} min total",
                    style = MaterialTheme.typography.bodyMedium, color = FeedMeColors.Muted) }
            }
            Text("Save, sync and finishing are separate actions. Finishing never saves a recipe or shares a post. Make Again, feedback and sharing are not connected here.")
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
