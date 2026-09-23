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
import com.feedme.app.blueprint.*
import com.feedme.core.ports.*
import com.feedme.mealflow.circles.*
import kotlinx.coroutines.launch

/** Actual retained controller UI. No restore, review, command ID or send is initiated by
 * mounting/recreating. Unkept typing is RAM-only; Keep draft/Review/Back retain it explicitly. */
@Composable
fun FeedMeCircleCreateFlow(controller: CircleCreateController,
    onOpenCircle: ((CircleCreateState) -> Unit)? = null,
    openingCircle: Boolean = false, openCircleFailure: FailureReason? = null,
    blueprintIsCurrent: (() -> Boolean)? = null,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit) {
    val state by controller.states.collectAsState()
    val scope = rememberCoroutineScope()
    val expected = state
    val blueprintHost by rememberUpdatedState(blueprintIsCurrent)
    fun current() = controller.states.value === expected && blueprintHost?.invoke() != false
    var name by remember(controller, state.localRevision) { mutableStateOf(state.input.name) }
    var description by remember(controller, state.localRevision) { mutableStateOf(state.input.description.orEmpty()) }
    var includeDescription by remember(controller, state.localRevision) { mutableStateOf(state.input.description != null) }
    var running by remember(controller) { mutableStateOf(false) }
    var failure by remember(controller) { mutableStateOf<FailureReason?>(null) }
    var validate by remember(controller, state.localRevision) { mutableStateOf(false) }
    var replace by remember(controller) { mutableStateOf<CircleCreateState?>(null) }
    var discardTyping by remember(controller) { mutableStateOf<CircleCreateState?>(null) }
    var leaveWhileKeeping by remember(controller) { mutableStateOf<CircleCreateState?>(null) }
    var blueprintTools by remember(controller, state) { mutableStateOf(false) }
    val input = CircleCreateInput(name, if (includeDescription) description else null)
    val changed = !sameCircleCreateInput(input, state.input)
    val editable = circleCreateCanEdit(state.screen, state.phase) && !running
    val fieldErrors = circleCreateFieldErrors(input)
    val completion = circleCreateCompletion(state.completionAcknowledged, state.pending != null)
    fun act(keep: Boolean = false, action: suspend (CircleCreateState) -> PortResult<CircleCreateState>) {
        if (running || state.phase == CircleCreatePhase.UNAVAILABLE || !current()) return
        scope.launch {
            if (!current()) { failure = FailureReason.CONFLICT; return@launch }
            running = true; failure = null
            try {
                val kept = if (keep && (changed || !expected.localAcknowledged)) controller.edit(input, expected)
                    else PortResult.Value(expected)
                val result = when (kept) {
                    is PortResult.Failure -> kept
                    is PortResult.Value -> if (blueprintHost?.invoke() == false) PortResult.Failure(FailureReason.STALE_SESSION)
                    else if (keep && !kept.value.localAcknowledged)
                        PortResult.Failure(FailureReason.CONFLICT) else action(kept.value)
                }
                failure = (result as? PortResult.Failure)?.reason
            } finally { running = false }
        }
    }
    val back = {
        if (!current()) Unit
        else if (blueprintTools) blueprintTools = false
        else if (replace != null || discardTyping != null || leaveWhileKeeping != null) {
            replace = null; discardTyping = null; leaveWhileKeeping = null
        } else if (changed && (running || state.phase == CircleCreatePhase.BUSY)) {
            // Do not silently abandon unacknowledged RAM typing when Back cancels a Keep.
            leaveWhileKeeping = expected
        } else if (state.screen == CircleCreateScreen.REVIEW || state.phase == CircleCreatePhase.BUSY || running || !changed) {
            // Back never queues behind confirmation/network I/O or reconstructs a local ACK.
            scope.launch { if (current()) controller.back(expected) }
        } else act(keep = true) { controller.back(it) }
        Unit
    }
    platformBackHandler(state.screen != CircleCreateScreen.HIDDEN, back)
    if (state.screen == CircleCreateScreen.HIDDEN) return
    FeedMeTheme {
        val originalForm = blueprintHost != null && circleCreateBlueprintEligible(state.screen, state.phase,
            state.pending != null, state.result != null, completion, failure != null || state.failureReason != null || openCircleFailure != null)
        if (originalForm) {
            val context = remember(state, input, includeDescription) { BlueprintCommunityContext("retained-circle-creation", "exact-rendered-draft") }
            val renderedName = name
            val renderedDescription = description
            val renderedIncludeDescription = includeDescription
            val displayedDescription = if (includeDescription) description else ""
            val model = BlueprintCircleCreateState(context, BlueprintCircleDraft(name, displayedDescription), BlueprintCommunityControls(
                loading = running, allowedActionIds = buildSet {
                    add(BlueprintCircleAction.CREATE_CANCEL.id)
                    if (editable) add(BlueprintCircleAction.CREATE_SUBMIT.id)
                }, editableFields = if (editable) setOf("name", "description") else emptySet(),
                status = if (validate && !fieldErrors.valid) listOfNotNull(fieldErrors.name, fieldErrors.description).joinToString(" ") else null))
            fun formCurrent() = current() && name == renderedName && description == renderedDescription && includeDescription == renderedIncludeDescription
            BlueprintCircleCreateScreen(model, onDraft = { actualContext, draft ->
                if (actualContext === context && editable && formCurrent()) {
                    val descriptionChanged = draft.description != displayedDescription
                    includeDescription = circleBlueprintDescriptionIncluded(displayedDescription, draft.description, includeDescription)
                    name = draft.name
                    if (descriptionChanged) description = draft.description
                }
            }, onAction = { intent ->
                if (intent.context === context && intent.draft == model.draft && formCurrent() && model.intent(intent.action) != null) {
                    when (intent.action) {
                        BlueprintCircleAction.CREATE_SUBMIT -> { validate = true; if (fieldErrors.valid) act(keep = true) { controller.reviewCreate(it) } }
                        BlueprintCircleAction.CREATE_CANCEL -> back()
                        else -> Unit
                    }
                }
            }, onBack = back, onMore = { if (formCurrent()) blueprintTools = true }, onNavigate = {})
            if (blueprintTools) AlertDialog(onDismissRequest = { blueprintTools = false }, title = { Text("Circle draft") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (changed) "Your typing is not kept yet." else if (state.localAcknowledged) "Draft kept on this device." else "Keep draft confirms this text on the device.")
                    Text("Create circle opens a review. It does not send a creation request until you confirm that review.")
                    TextButton(onClick = { if (formCurrent()) { blueprintTools = false; act(keep = true) { PortResult.Value(it) } } }, enabled = editable && (changed || !state.localAcknowledged)) { Text("Keep draft") }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(includeDescription, enabled = editable, role = Role.Checkbox,
                        onValueChange = { if (formCurrent()) includeDescription = it }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(includeDescription, onCheckedChange = null, enabled = editable); Text("Add a description (optional)")
                    }
                    if (changed) TextButton(onClick = { if (formCurrent()) { blueprintTools = false; discardTyping = expected } }, enabled = editable) { Text("Discard typing") }
                } }, confirmButton = { TextButton(onClick = { blueprintTools = false }) { Text("Close tools") } })
        } else {
        Box(Modifier.fillMaxSize().background(FeedMeColors.Paper).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = back, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (state.screen == CircleCreateScreen.REVIEW) "Back to draft" else "Back to circles")
                }
                FeedMeWordmark(compact = true)
                Text("Start a circle", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                if (state.phase == CircleCreatePhase.UNAVAILABLE) {
                    CreateNotice("Account unavailable", "Private details are hidden. Go back to your kitchen and check your account connection.")
                } else {
                    circleCreateFailureText(failure ?: state.failureReason)?.let { CreateNotice("Couldn’t confirm that", it) }
                    openCircleFailure?.let { CreateNotice("Couldn’t open the circle", "The details could not be loaded. Your creation result is unchanged. You can choose Open circle again.") }
                    if (openingCircle) { Text("Opening circle details…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (state.phase == CircleCreatePhase.BUSY || running) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.screen == CircleCreateScreen.REVIEW) {
                        val review = state.review
                        if (review != null && circleCreateReviewVisible(state.screen, state.phase, review.isCurrentForNavigation)) {
                            Text(circleCreateReviewTitle(review.kind), style = MaterialTheme.typography.titleLarge)
                            Text(circleCreateReviewNotice(review.kind), style = MaterialTheme.typography.bodyMedium)
                            CreateInputSummary(review.input)
                            review.original?.let {
                                Text(circleCreatePendingText(it.queuePhase, it.attempts, it.recoveryRequired),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            CreatePrimary(circleCreateConfirmLabel(review.kind), !running && state.phase != CircleCreatePhase.BUSY) {
                                if (review.isCurrentForNavigation) act { current ->
                                    if (current.review !== review || !review.isCurrentForNavigation) PortResult.Failure(FailureReason.CONFLICT)
                                    else when (review.kind) {
                                        CircleCreateReviewKind.CREATE -> controller.confirmCreate(review)
                                        CircleCreateReviewKind.RETRY_ORIGINAL -> controller.retryOriginal(review)
                                        CircleCreateReviewKind.DISCARD_UNSENT -> controller.discardUnsent(review)
                                    }
                                }
                            }
                        } else CreateNotice("Review changed", "Go back to your draft and review again. Nothing is confirmed from this older view.")
                    } else {
                        state.result?.let { result ->
                            CreateNotice(if (result.acknowledged) "Circle created" else "Retained circle result",
                                if (result.acknowledged) "Your creation request is confirmed on this device."
                                else "This result is from an earlier session. It is not a new confirmation or current membership check.")
                            Text(result.circle.name, style = MaterialTheme.typography.titleLarge)
                            circleDescription(result.circle.description)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                            if (onOpenCircle != null && result.isCurrentForNavigation) {
                                CreatePrimary("Open circle", !openingCircle && !running && state.phase != CircleCreatePhase.BUSY) {
                                    if (current() && result.isCurrentForNavigation) onOpenCircle(expected)
                                }
                            }
                        }
                        if (completion) {
                            if (state.result == null) CreateNotice("Unsent request discarded", "Your draft is still kept. Start a new draft only when you’re ready to replace it.")
                            Text("Kept draft", style = MaterialTheme.typography.titleMedium)
                            CreateInputSummary(state.input)
                            CreateSecondary(if (state.result == null) "Start new draft" else "Create another circle", editable) { replace = expected }
                        }
                        if (!completion && (state.result == null || state.pending != null)) {
                        Text("Choose a name your friends will recognize.", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(value = name, onValueChange = { name = it }, enabled = editable,
                            label = { Text("Circle name") }, supportingText = {
                                Text(if (validate) fieldErrors.name ?: "1–60 characters" else "1–60 characters")
                            }, isError = validate && fieldErrors.name != null, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = includeDescription,
                            enabled = editable, role = Role.Checkbox, onValueChange = { includeDescription = it }),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = includeDescription, onCheckedChange = null, enabled = editable)
                            Text("Add a description (optional)", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (includeDescription) OutlinedTextField(value = description, onValueChange = { description = it }, enabled = editable,
                            label = { Text("Description") }, supportingText = {
                                Text(if (validate) fieldErrors.description ?: "Up to 300 characters" else "Up to 300 characters")
                            }, isError = validate && fieldErrors.description != null, modifier = Modifier.fillMaxWidth())
                        Text(when { changed -> "Not kept yet. Keep draft, Review circle, or Back will keep this text on the device."
                            state.localAcknowledged -> "Draft kept on this device."
                            else -> "Keep draft confirms this text on the device." },
                            style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
                        CreateSecondary("Keep draft", editable && (changed || !state.localAcknowledged)) {
                            act(keep = true) { PortResult.Value(it) }
                        }
                        if (changed) CreateSecondary("Discard typing", editable) { discardTyping = expected }
                        if (state.pending == null && state.result == null) CreatePrimary("Review circle", editable) {
                            validate = true
                            if (fieldErrors.valid) act(keep = true) { controller.reviewCreate(it) }
                        }
                        state.pending?.let { original ->
                            CreateNotice("Original request", circleCreatePendingText(original.queuePhase, original.attempts, original.recoveryRequired))
                            CreateInputSummary(original.input)
                            if (original.localRevision != state.localRevision)
                                Text("Your current draft is separate from this original request.", style = MaterialTheme.typography.bodyMedium)
                            if (changed) Text("Keep or discard your typing before reviewing the original request.", style = MaterialTheme.typography.bodyMedium)
                            CreatePrimary("Review original request", editable && !changed) { act { controller.reviewOriginal(it) } }
                            if (original.canDiscardUnsent) CreateSecondary("Review unsent discard", editable && !changed) { act { controller.reviewDiscardUnsent(it) } }
                        }
                        }
                        Text("Inviting friends, joining circles and sharing posts aren’t connected here yet.",
                            style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
                    }
                }
            }
        }
        }
        replace?.takeIf { it === state && state.phase != CircleCreatePhase.UNAVAILABLE }?.let { selected ->
            AlertDialog(onDismissRequest = { replace = null }, title = { Text("Start a new draft?") },
                text = { Text("The kept draft shown on this screen will be replaced with an empty one. No circle is deleted.") },
                confirmButton = { TextButton(onClick = { replace = null; if (current() && controller.states.value === selected) act { controller.startNew(it) } }) { Text("Start new draft") } },
                dismissButton = { TextButton(onClick = { replace = null }) { Text("Keep this draft") } })
        }
        discardTyping?.takeIf { it === state && state.phase != CircleCreatePhase.UNAVAILABLE }?.let { selected ->
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
        leaveWhileKeeping?.takeIf { it === state && state.phase != CircleCreatePhase.UNAVAILABLE }?.let { selected ->
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
private fun CreateInputSummary(input: CircleCreateInput) {
    Surface(color = FeedMeColors.Surface, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Circle name", style = MaterialTheme.typography.labelMedium, color = FeedMeColors.Muted)
            Text(input.name, style = MaterialTheme.typography.titleLarge)
            Text("Description", style = MaterialTheme.typography.labelMedium, color = FeedMeColors.Muted)
            Text(circleCreateDescriptionText(input.description), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
@Composable private fun CreateNotice(title: String, message: String) {
    Surface(color = FeedMeColors.SoftBlue, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium); Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
@Composable private fun CreatePrimary(text: String, enabled: Boolean, action: () -> Unit) {
    Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(text) }
}
@Composable private fun CreateSecondary(text: String, enabled: Boolean, action: () -> Unit) {
    OutlinedButton(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(text) }
}
