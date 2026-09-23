package com.feedme.app.mealflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.feedme.app.FeedMeColors
import com.feedme.app.FeedMeDetails
import com.feedme.app.blueprint.BlueprintTimerEdit
import com.feedme.app.blueprint.BlueprintTimerScreen
import com.feedme.mealflow.timers.CookingTimerFlowIssue

/** UI events only; none supplies a native ticket or substitutes for controller preflight. */
class CookingTimerScreenActions(val back: () -> Unit, val duration: (String) -> Unit, val start: () -> Unit,
    val pause: (String) -> Unit, val resume: (String) -> Unit, val reset: (String) -> Unit,
    val remove: (String) -> Unit, val cleanup: (String) -> Unit, val refresh: () -> Unit,
    val blueprintIsCurrent: () -> Boolean = { false }, val blueprintOwnerKey: Any? = null)

/** Original TIMER presentation over the actual retained owner. The legacy recovery reader is
 * intentionally used in every unresolved/unknown state, and remains accessible through More. */
@Composable
fun CookingTimerScreen(view: CookingTimerScreenState, actions: CookingTimerScreenActions) {
    var selected by remember(actions.blueprintOwnerKey, view.sessionId, view.planId, view.selectedStepId) { mutableStateOf<BlueprintTimerSelection?>(null) }
    var overflow by remember(actions.blueprintOwnerKey, view.sessionId, view.planId, view.selectedStepId) { mutableStateOf(false) }
    var toolsVisible by remember(actions.blueprintOwnerKey, view.sessionId, view.planId, view.selectedStepId) { mutableStateOf(false) }
    var interaction by remember(actions.blueprintOwnerKey, view.sessionId, view.planId, view.selectedStepId) { mutableStateOf(Any()) }
    val renderedInteraction = interaction
    val presentation = if (actions.blueprintIsCurrent()) blueprintTimerPresentation(view, selected, overflow) else null
    fun current() = actions.blueprintIsCurrent() && interaction === renderedInteraction && !toolsVisible
    fun toolsCurrent() = actions.blueprintIsCurrent() && interaction === renderedInteraction && toolsVisible
    fun closeTools() {
        if (toolsCurrent()) {
            toolsVisible = false; interaction = Any()
        }
    }
    if (presentation == null) RetainedTimerTools(view, actions)
    else {
        BlueprintTimerScreen(presentation.blueprint, { rendered, action ->
            if (rendered === presentation.blueprint) dispatchBlueprintTimer(presentation, action, current(), actions)
        }, { rendered, edit ->
            if (rendered === presentation.blueprint && current()) when (edit) {
                is BlueprintTimerEdit.Duration -> dispatchBlueprintTimerDuration(presentation, edit, true, actions)
                is BlueprintTimerEdit.Select -> blueprintTimerSelection(presentation, edit, true)?.let { selected = it; interaction = Any() }
                is BlueprintTimerEdit.Overflow -> { overflow = edit.expanded; interaction = Any() }
            }
        }, onMore = { if (current()) { toolsVisible = true; interaction = Any() } })
        if (toolsVisible) Dialog(onDismissRequest = ::closeTools,
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = FeedMeColors.Paper) {
                RetainedTimerTools(view, guardCookingTimerTools(actions, ::toolsCurrent), ::closeTools)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RetainedTimerTools(view: CookingTimerScreenState, actions: CookingTimerScreenActions, closeTools: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().widthIn(max = 720.dp).verticalScroll(rememberScrollState())
        .padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(if (view.rows.isEmpty()) 18.dp else 12.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = closeTools ?: actions.back, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (closeTools == null) "← Back to cooking" else "← Back to timer") }
            Pill("TIMER", FeedMeColors.Lime)
        }
        Text("A little less clock-watching.", style = if (view.rows.isEmpty()) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() })
        Text("Timers are estimates, not food-safety checks. Back doesn’t pause or cancel them.",
            style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
        if (view.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        timerIssue(view.issue)?.let { InfoCard("Timer action status", it) }
        view.failure?.let { InfoCard("Not acknowledged", kitchenFailureMessage(it) ?: "The timer action could not be confirmed.") }
        if (view.due) InfoCard("Timer reached its estimated end", "Check your food and the pinned instructions yourself. No step was advanced or meal completed.")
        if (!view.eligible) InfoCard("Timer controls unavailable", "No eligible current cooking pin is exposed. Timing or alert cleanup observations do not grant cooking permission.")
        else if (view.rows.isEmpty()) {
            InfoCard("For this step", view.stepLabel ?: "Step label unavailable")
            TimerSetup(view, actions)
        }
        if (view.rows.isEmpty()) InfoCard("No timers yet", "Choose a duration and tap Start timer. Typing or selecting a duration won’t start anything.")
        view.rows.forEachIndexed { index, row ->
            Surface(shape = RoundedCornerShape(28.dp), color = FeedMeColors.Surface, border = BorderStroke(1.dp, FeedMeColors.Line)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("TIMER ${index + 1}", FeedMeColors.SoftBlue)
                        Pill(row.timing, if (row.status == "running") FeedMeColors.SoftLime else FeedMeColors.Line)
                    }
                    Text(row.remaining, style = MaterialTheme.typography.headlineLarge, color = FeedMeColors.Blue)
                    Text(row.label, style = MaterialTheme.typography.bodyMedium)
                    Text(row.alert, style = MaterialTheme.typography.bodySmall)
                    val editable = !view.busy && !view.pending
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (row.status == "running") Button(onClick = { actions.pause(row.id) }, enabled = editable && view.canStop,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("Pause timer ${index + 1}") }
                        if (row.status == "paused") Button(onClick = { actions.resume(row.id) }, enabled = editable && view.canStart,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("Resume timer ${index + 1}") }
                        OutlinedButton(onClick = { actions.reset(row.id) }, enabled = editable && view.canStop,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset timer ${index + 1}") }
                        TextButton(onClick = { actions.remove(row.id) }, enabled = editable && view.canStop,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove timer ${index + 1}") }
                    }
                    if (row.cleanupAvailable && !(view.cleanupAcknowledged && row.id == view.pendingTimerId))
                        TextButton(onClick = { actions.cleanup(row.id) }, enabled = !view.busy,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel alert only · timer ${index + 1}") }
                }
            }
        }
        if (view.pending && view.pendingTimerId != null && view.rows.none { it.id == view.pendingTimerId } && !view.cleanupAcknowledged)
            OutlinedButton(onClick = { actions.cleanup(view.pendingTimerId) }, enabled = !view.busy,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel original alert only") }
        if (view.eligible && view.rows.isNotEmpty()) FeedMeDetails("Add another timer") {
            Text("For this step", style = MaterialTheme.typography.titleSmall)
            Text(view.stepLabel ?: "Step label unavailable")
            TimerSetup(view, actions)
        }
        OutlinedButton(onClick = actions.refresh, enabled = !view.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Refresh timer observation") }
        InfoCard("Sync when you choose", "Timer changes stay on this device until you return to cooking and choose Sync this cooking session. This page never sends automatically.")
        Text("Keep an eye on your food. Background alarms and exact alert delivery aren’t guaranteed.", style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
        FeedMeDetails("How timer estimates work") {
            Text("Countdowns are local observations. Foreground alerts depend on this device’s connected timer service. Clock or process changes can make remaining time uncertain.")
            Text("A timer never advances a step or finishes cooking for you.")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun TimerSetup(view: CookingTimerScreenState, actions: CookingTimerScreenActions) {
    val durationError = if (view.issue == CookingTimerFlowIssue.INVALID_DURATION) timerIssue(view.issue) else null
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(value = view.durationText, onValueChange = actions.duration,
            enabled = !view.busy && !view.pending, label = { Text("Timer duration · seconds") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = durationError != null,
            supportingText = durationError?.let { message -> { Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } },
            singleLine = true, modifier = Modifier.fillMaxWidth().semantics { durationError?.let { error(it) } }, shape = RoundedCornerShape(18.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("60" to "1 min", "180" to "3 min", "300" to "5 min").forEach { (seconds, label) ->
                FilterChip(selected = view.durationText == seconds, onClick = { actions.duration(seconds) },
                    enabled = !view.busy && !view.pending, modifier = Modifier.heightIn(min = 48.dp), label = { Text(label) })
            }
        }
        Primary("Start timer", view.canStart && !view.busy && !view.pending, actions.start)
    }
}
