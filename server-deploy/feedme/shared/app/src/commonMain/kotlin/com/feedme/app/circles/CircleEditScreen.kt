package com.feedme.app.circles

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.feedme.app.*
import com.feedme.core.ports.*
import com.feedme.mealflow.circles.*
import kotlinx.coroutines.launch

/** Actual retained controller UI. No restore, review, command ID or send is initiated by
 * mounting/recreating. Unkept typing is RAM-only; Keep draft/Review/Back retain it explicitly. */
@Composable
fun FeedMeCircleEditFlow(controller: CircleEditController,
    onOpenCircle: ((CircleEditState) -> Unit)? = null,
    onReviewConflict: ((CircleEditState) -> Unit)? = null,
    onEditLatest: ((CircleEditState) -> Unit)? = null,
    handoffBusy: Boolean = false, handoffFailure: FailureReason? = null,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
    hostIsCurrent: () -> Boolean = { true }) {
    val state by controller.states.collectAsState()
    val scope = rememberCoroutineScope()
    val expected = state
    val currentHost by rememberUpdatedState(hostIsCurrent)
    var attached by remember(controller) { mutableStateOf(true) }
    DisposableEffect(controller) { onDispose { attached = false } }
    fun current() = attached && currentHost() && controller.states.value === expected
    var name by remember(controller, state.localRevision) { mutableStateOf(state.input.name) }
    var description by remember(controller, state.localRevision) { mutableStateOf(state.input.description.orEmpty()) }
    var includeDescription by remember(controller, state.localRevision) { mutableStateOf(state.input.description != null) }
    var running by remember(controller) { mutableStateOf(false) }
    var failure by remember(controller) { mutableStateOf<FailureReason?>(null) }
    var validate by remember(controller, state.localRevision) { mutableStateOf(false) }
    var replace by remember(controller) { mutableStateOf<CircleEditState?>(null) }
    var discardTyping by remember(controller) { mutableStateOf<CircleEditState?>(null) }
    var leaveWhileKeeping by remember(controller) { mutableStateOf<CircleEditState?>(null) }
    val input = CircleEditInput(name, if (includeDescription) description else null)
    val changed = !sameCircleEditInput(input, state.input)
    val editable = current() && circleEditCanEdit(state.screen, state.phase) && !running && !handoffBusy
    val fieldErrors = circleEditFieldErrors(input)
    val completion = circleEditCompletion(state.completionAcknowledged, state.pending != null)
    fun act(keep: Boolean = false, action: suspend (CircleEditState) -> PortResult<CircleEditState>) {
        if (running || handoffBusy || !current() || state.phase == CircleEditPhase.UNAVAILABLE) return
        running = true; failure = null
        scope.launch {
            try {
                if (!current()) return@launch
                val kept = if (keep && (changed || !expected.localAcknowledged)) controller.edit(input, expected)
                    else PortResult.Value(expected)
                val result = when (kept) {
                    is PortResult.Failure -> kept
                    is PortResult.Value -> if (!attached || !currentHost() || controller.states.value !== kept.value)
                        PortResult.Failure(FailureReason.STALE_SESSION)
                    else if (keep && !kept.value.localAcknowledged)
                        PortResult.Failure(FailureReason.CONFLICT) else action(kept.value)
                }
                if (attached && currentHost()) failure = (result as? PortResult.Failure)?.reason
            } finally { if (attached && currentHost()) running = false }
        }
    }
    val back: () -> Unit = back@ {
        if (!current()) return@back
        if (replace != null || discardTyping != null || leaveWhileKeeping != null) {
            replace = null; discardTyping = null; leaveWhileKeeping = null
        } else if (changed && (running || state.phase == CircleEditPhase.BUSY)) {
            // Do not silently abandon unacknowledged RAM typing when Back cancels a Keep.
            leaveWhileKeeping = expected
        } else if (state.screen == CircleEditScreen.REVIEW || state.phase == CircleEditPhase.BUSY || running || !changed) {
            // Back never queues behind confirmation/network I/O or reconstructs a local ACK.
            scope.launch { if (current()) controller.back(expected) }
        } else act(keep = true) { controller.back(it) }
        Unit
    }
    platformBackHandler(current() && state.screen != CircleEditScreen.HIDDEN, back)
    if (state.screen == CircleEditScreen.HIDDEN) return
    FeedMeTheme {
        if (state.screen == CircleEditScreen.REVIEW) RetainedCircleEditConfirmation(expected,
            running || handoffBusy || state.phase == CircleEditPhase.BUSY, failure, handoffFailure,
            isCurrent = ::current, onConfirm = { review -> act { latest ->
                if (latest.review !== review || !review.isCurrentForNavigation) PortResult.Failure(FailureReason.CONFLICT)
                else when (review.kind) {
                    CircleEditReviewKind.UPDATE -> controller.confirmUpdate(review)
                    CircleEditReviewKind.RESOLVE_VERSION_CONFLICT -> controller.resolveConflict(review)
                    CircleEditReviewKind.RETRY_ORIGINAL -> controller.retryOriginal(review)
                    CircleEditReviewKind.DISCARD_UNSENT -> controller.discardUnsent(review)
                }
            } }, onBack = back, details = { review ->
                Text(if (review.kind == CircleEditReviewKind.RESOLVE_VERSION_CONFLICT) "Your kept draft" else "Changes in this request",
                    style = MaterialTheme.typography.titleMedium)
                EditInputSummary(review.input)
                review.freshBaseline?.let { EditBaselineSummary("Latest circle details", it) }
                EditBaselineSummary("Original starting point", review.baseline)
                if (review.kind == CircleEditReviewKind.RESOLVE_VERSION_CONFLICT) review.original?.let {
                    Text("Rejected original changes", style = MaterialTheme.typography.titleMedium)
                    EditInputSummary(it.input)
                }
                review.original?.let { Text(circleEditPendingText(it), style = MaterialTheme.typography.bodyMedium) }
            })
        else Box(Modifier.fillMaxSize().background(FeedMeColors.Paper).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = back, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (state.screen == CircleEditScreen.REVIEW) "Back to draft" else "Back to circles")
                }
                FeedMeWordmark(compact = true)
                Text("Edit circle details", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                if (state.phase == CircleEditPhase.UNAVAILABLE) {
                    EditNotice("Account unavailable", "Private details are hidden. Go back to your kitchen and check your account connection.")
                } else {
                    circleEditFailureText(failure ?: state.failureReason)?.let { EditNotice("Couldn’t confirm that", it) }
                    handoffFailure?.let { EditNotice("Couldn’t load circle details", circleEditFailureText(it).orEmpty()) }
                    if (handoffBusy) { Text("Loading current circle details…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (state.phase == CircleEditPhase.BUSY || running) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.screen == CircleEditScreen.REVIEW) {
                        val review = state.review
                        if (review != null && circleEditReviewVisible(state.screen, state.phase, review.isCurrentForNavigation)) {
                            Text(circleEditReviewTitle(review.kind), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                            Text(circleEditReviewNotice(review.kind), style = MaterialTheme.typography.bodyMedium)
                            Text(if (review.kind == CircleEditReviewKind.RESOLVE_VERSION_CONFLICT) "Your kept draft" else "Changes in this request", style = MaterialTheme.typography.titleMedium)
                            EditInputSummary(review.input)
                            review.freshBaseline?.let { fresh ->
                                EditBaselineSummary("Latest circle details", fresh)
                            }
                            EditBaselineSummary("Original starting point", review.baseline)
                            if (review.kind == CircleEditReviewKind.RESOLVE_VERSION_CONFLICT) review.original?.let {
                                Text("Rejected original changes", style = MaterialTheme.typography.titleMedium)
                                EditInputSummary(it.input)
                            }
                            review.original?.let {
                                Text(circleEditPendingText(it),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            EditPrimary(circleEditConfirmLabel(review.kind), !running && state.phase != CircleEditPhase.BUSY) {
                                if (review.isCurrentForNavigation) act { current ->
                                    if (current.review !== review || !review.isCurrentForNavigation) PortResult.Failure(FailureReason.CONFLICT)
                                    else when (review.kind) {
                                        CircleEditReviewKind.UPDATE -> controller.confirmUpdate(review)
                                        CircleEditReviewKind.RESOLVE_VERSION_CONFLICT -> controller.resolveConflict(review)
                                        CircleEditReviewKind.RETRY_ORIGINAL -> controller.retryOriginal(review)
                                        CircleEditReviewKind.DISCARD_UNSENT -> controller.discardUnsent(review)
                                    }
                                }
                            }
                        } else EditNotice("Review changed", "Go back to your draft and review again. Nothing is confirmed from this older view.")
                    } else {
                        state.result?.let { result ->
                            EditNotice(if (result.acknowledged) "Circle details updated" else "Retained update result",
                                if (result.acknowledged) "Your update request is confirmed on this device."
                                else "This result is from an earlier session. It is not a new confirmation or current membership check.")
                            Text(result.circle.name, style = MaterialTheme.typography.titleLarge)
                            circleDescription(result.circle.description)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                            if (onOpenCircle != null && result.isCurrentForNavigation) {
                                EditPrimary("Open circle", !handoffBusy && !running && state.phase != CircleEditPhase.BUSY) {
                                    if (current() && result.isCurrentForNavigation) onOpenCircle(expected)
                                }
                            }
                        }
                        if (completion) {
                            if (state.result == null) EditNotice("Unsent request discarded", "Your draft is still kept. The circle was not changed by discarding the unsent request.")
                            Text("Kept draft", style = MaterialTheme.typography.titleMedium)
                            EditInputSummary(state.input)
                            if (onEditLatest != null && state.target?.isCurrentForNavigation == true)
                                EditSecondary("Edit latest details", editable) { replace = expected }
                        }
                        state.resolvedOriginal?.let {
                            EditNotice("Original version conflict resolved", "The rejected original remains separate. This is not confirmation that your changes were saved.")
                            EditInputSummary(it.input)
                        }
                        if (!completion && state.baseline != null && (state.result == null || state.pending != null)) {
                        Text("Update the name and description exactly as you want them.", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(value = name, onValueChange = { name = it }, enabled = editable,
                            label = { Text("Circle name") }, supportingText = {
                                Text(if (validate) fieldErrors.name ?: "1–60 characters" else "1–60 characters")
                            }, isError = validate && fieldErrors.name != null, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = includeDescription,
                            enabled = editable, role = Role.Checkbox, onValueChange = { includeDescription = it }),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = includeDescription, onCheckedChange = null, enabled = editable)
                            Text("Include a description", style = MaterialTheme.typography.bodyMedium)
                        }
                        Text("Unchecked removes any existing description. Included but blank keeps an empty description.", style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
                        if (includeDescription) OutlinedTextField(value = description, onValueChange = { description = it }, enabled = editable,
                            label = { Text("Description") }, supportingText = {
                                Text(if (validate) fieldErrors.description ?: "Up to 300 characters" else "Up to 300 characters")
                            }, isError = validate && fieldErrors.description != null, modifier = Modifier.fillMaxWidth())
                        Text(when { changed -> "Not kept yet. Keep draft, Review changes, or Back will keep this text on the device."
                            state.localAcknowledged -> "Draft kept on this device."
                            else -> "Keep draft confirms this text on the device." },
                            style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
                        EditSecondary("Keep draft", editable && (changed || !state.localAcknowledged)) {
                            act(keep = true) { PortResult.Value(it) }
                        }
                        if (changed) EditSecondary("Discard typing", editable) { discardTyping = expected }
                        if (state.pending == null && state.result == null && !state.completionAcknowledged) EditPrimary("Review changes", editable) {
                            validate = true
                            if (fieldErrors.valid) act(keep = true) { controller.reviewUpdate(it) }
                        }
                        state.pending?.let { original ->
                            EditNotice("Original request", circleEditPendingText(original))
                            EditInputSummary(original.input)
                            if (original.localRevision != state.localRevision)
                                Text("Your current draft is separate from this original request.", style = MaterialTheme.typography.bodyMedium)
                            if (changed) Text("Keep or discard your typing before reviewing the original request.", style = MaterialTheme.typography.bodyMedium)
                            EditPrimary("Review original request", editable && !changed) { act { controller.reviewOriginal(it) } }
                            if (original.canReviewConflict && onReviewConflict != null && state.target?.isCurrentForNavigation == true)
                                EditSecondary("Review latest circle details", editable && !changed && state.localAcknowledged) {
                                    if (current()) onReviewConflict(expected)
                                }
                            if (original.canReviewConflict && !state.localAcknowledged)
                                Text("Keep draft before reviewing the latest circle details.", style = MaterialTheme.typography.bodyMedium)
                            if (original.canDiscardUnsent) EditSecondary("Review unsent discard", editable && !changed) { act { controller.reviewDiscardUnsent(it) } }
                        }
                        }
                        Text("Inviting friends, joining circles and sharing posts aren’t connected here yet.",
                            style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
                        if (state.baseline == null && state.pending == null && state.result == null)
                            EditNotice("No retained edit", "Open a current circle and choose Edit circle details to start. No changes have been sent.")
                    }
                }
            }
        }
        replace?.takeIf { it === state && state.phase != CircleEditPhase.UNAVAILABLE }?.let { selected ->
            AlertDialog(onDismissRequest = { replace = null }, title = { Text("Replace the kept draft?") },
                text = { Text("Load the latest circle details and use them instead of the kept draft shown here. This does not send changes or delete the circle.") },
                confirmButton = { TextButton(onClick = { replace = null; if (current() && controller.states.value === selected && selected.target?.isCurrentForNavigation == true) onEditLatest?.invoke(selected) }) { Text("Load latest details") } },
                dismissButton = { TextButton(onClick = { replace = null }) { Text("Keep this draft") } })
        }
        discardTyping?.takeIf { it === state && state.phase != CircleEditPhase.UNAVAILABLE }?.let { selected ->
            AlertDialog(onDismissRequest = { discardTyping = null }, title = { Text("Discard unkept typing?") },
                text = { Text("Only the text not yet kept on this screen is discarded. Your retained draft and original request stay unchanged.") },
                confirmButton = { TextButton(onClick = {
                    discardTyping = null
                    if (current() && controller.states.value === selected) {
                        name = selected.input.name; description = selected.input.description.orEmpty()
                        includeDescription = selected.input.description != null
                    }
                }) { Text("Discard typing") } },
                dismissButton = { TextButton(onClick = { discardTyping = null }) { Text("Keep editing") } })
        }
        leaveWhileKeeping?.takeIf { it === state && state.phase != CircleEditPhase.UNAVAILABLE }?.let { selected ->
            AlertDialog(onDismissRequest = { leaveWhileKeeping = null }, title = { Text("Go back before the draft is kept?") },
                text = { Text("Your latest typing may not be kept yet. Going back does not undo anything already kept.") },
                confirmButton = { TextButton(onClick = { leaveWhileKeeping = null
                    if (current() && controller.states.value === selected) scope.launch { if (current()) controller.back(selected) }
                }) { Text("Go back now") } },
                dismissButton = { TextButton(onClick = { leaveWhileKeeping = null }) { Text("Keep waiting") } })
        }
    }
}

@Composable
private fun EditInputSummary(input: CircleEditInput) {
    Surface(color = FeedMeColors.Surface, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Circle name", style = MaterialTheme.typography.labelMedium, color = FeedMeColors.Muted)
            Text(input.name, style = MaterialTheme.typography.titleLarge)
            Text("Description", style = MaterialTheme.typography.labelMedium, color = FeedMeColors.Muted)
            Text(circleEditDescriptionText(input.description), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
@Composable private fun EditNotice(title: String, message: String) {
    Surface(color = FeedMeColors.SoftBlue, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
@Composable private fun EditPrimary(text: String, enabled: Boolean, action: () -> Unit) {
    Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(text) }
}
@Composable private fun EditSecondary(text: String, enabled: Boolean, action: () -> Unit) {
    OutlinedButton(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(text) }
}

@Composable
private fun EditBaselineSummary(title: String, baseline: CircleSnapshot) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    Surface(color = FeedMeColors.SoftLime, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(baseline.name, style = MaterialTheme.typography.titleLarge)
            Text(when (val field = baseline.description) {
                is com.feedme.contracts.WireField.Value -> field.value.ifEmpty { "Description included, left blank" }
                com.feedme.contracts.WireField.Missing -> "No description"
                com.feedme.contracts.WireField.Null -> "Description unavailable"
            }, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
