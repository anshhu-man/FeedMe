package com.feedme.app.reports

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.feedme.app.*
import com.feedme.app.mealflow.MealFlowHostActions
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.mealflow.reports.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

private class ReportReplacementIntent(val state: ReportState, val form: ReportFormSnapshot?,
    val label: String, val kind: ReportReplacementKind)

/** Mounting never opens, restores, creates an ID, or sends. The account owner retains both
 * controller and form; the attachment gate protects queued callbacks and awaited results. */
@Composable
fun FeedMeReportFlow(
    controller: ReportController,
    form: ReportFormMemory,
    onExit: () -> Unit,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
    hostIsCurrent: () -> Boolean = { true },
    reporterAccountId: String? = null,
    blueprintIsCurrent: (() -> Boolean)? = null,
    onBlock: ((ReportState, () -> Boolean) -> Unit)? = null,
    blockOpenStatus: String? = null,
) {
    val state = controller.states.collectAsState().value
    val rememberedForm = form.states.collectAsState().value
    val scope = rememberCoroutineScope()
    val currentHost by rememberUpdatedState(hostIsCurrent)
    val currentExit by rememberUpdatedState(onExit)
    val currentBlueprintHost by rememberUpdatedState(blueprintIsCurrent)
    val currentBlock by rememberUpdatedState(onBlock)
    val host = remember(controller, form) { MealFlowHostActions { currentHost() } }
    DisposableEffect(host) { onDispose { host.retire() } }
    var running by remember(controller) { mutableStateOf(false) }
    var localFailure by remember(controller) { mutableStateOf<FailureReason?>(null) }
    var fieldError by remember(controller, state.input?.targetId) { mutableStateOf<String?>(null) }
    var replacement by remember(controller) { mutableStateOf<ReportReplacementIntent?>(null) }
    val input = state.input
    SideEffect {
        if (host.isCurrent()) {
            if (state.screen == ReportScreen.UNAVAILABLE) form.clear()
            else if (!running && state.screen == ReportScreen.FORM && input != null) {
                form.bind(controller, input)
            }
        }
    }
    val buffer = rememberedForm?.takeIf {
        input != null && form.current(controller, input.targetType, input.targetId, input.targetLabel) === it
    }
    val busy = running || state.phase == ReportPhase.WORKING
    val actionable = !busy && replacement == null && state.screen !in setOf(ReportScreen.HIDDEN, ReportScreen.UNAVAILABLE)

    fun act(keepDraft: Boolean = false, confirmation: ReportReplacementIntent? = null,
        onSuccess: (ReportState) -> Unit = {}, action: suspend (ReportState) -> PortResult<ReportState>) {
        if (!host.isCurrent() || running || busy || controller.states.value.phase == ReportPhase.WORKING ||
            controller.states.value !== state || replacement !== confirmation) return
        val expectedForm = buffer
        if (keepDraft && (expectedForm == null || !form.isCurrent(expectedForm))) return
        running = true
        localFailure = null
        val job = host.launch(scope) {
            try {
                if (controller.states.value !== state || replacement !== confirmation ||
                    keepDraft && !form.isCurrent(expectedForm!!)) return@launch
                // Text equality is not a durable acknowledgement after an uncertain write.
                // Every explicit Keep/Review/Back rechecks the exact retained draft.
                val kept = if (keepDraft) host.await {
                    checkNotNull(expectedForm)
                    controller.edit(expectedForm.reason, expectedForm.description, expectedForm.alsoBlock, state)
                } else PortResult.Value(state)
                val result = when (kept) {
                    is PortResult.Failure -> kept
                    is PortResult.Value -> {
                        if (keepDraft && !form.isCurrent(expectedForm!!)) PortResult.Failure(FailureReason.CONFLICT)
                        else {
                            val acknowledged = kept.value.input
                            val exactAck = !keepDraft || acknowledged != null && expectedForm != null &&
                                acknowledged.targetType == expectedForm.targetType && acknowledged.targetId == expectedForm.targetId &&
                                acknowledged.targetLabel == expectedForm.targetLabel && acknowledged.reason == expectedForm.reason &&
                                acknowledged.description == expectedForm.description && acknowledged.alsoBlock == expectedForm.alsoBlock &&
                                acknowledged.blockTargetUserId == expectedForm.blockTargetUserId && acknowledged.blockTargetLabel == expectedForm.blockTargetLabel
                            if (!exactAck) PortResult.Failure(FailureReason.CONFLICT)
                            else {
                                if (keepDraft) {
                                    // Acknowledgement happens before Review changes the screen. Waiting
                                    // for a FORM recomposition would leave acknowledged text falsely dirty.
                                    checkNotNull(acknowledged)
                                    form.bind(controller, acknowledged)
                                }
                                host.await { action(kept.value) }
                            }
                        }
                    }
                }
                host.run {
                    localFailure = (result as? PortResult.Failure)?.reason
                    if (result is PortResult.Value && controller.states.value === result.value) {
                        onSuccess(result.value)
                        if (result.value.screen == ReportScreen.HIDDEN) currentExit()
                    }
                }
            } finally {
                // A retired/cancelled attachment never repaints its successor.
                if (host.isCurrent() && currentCoroutineContext().isActive) running = false
            }
        }
        if (job == null) running = false
    }

    fun dismissReplacement(expected: ReportReplacementIntent) {
        if (host.isCurrent() && !running && controller.states.value.phase != ReportPhase.WORKING &&
            replacement === expected) replacement = null
    }
    val back: () -> Unit = {
        if (!running && !busy && host.isCurrent()) {
            val current = replacement
            if (current != null) dismissReplacement(current)
            else act(keepDraft = state.screen == ReportScreen.FORM && buffer != null) { controller.back(it) }
        }
    }
    fun openBlock(presentationCurrent: () -> Boolean) {
        fun current() = host.isCurrent() && !running && !busy && replacement == null &&
            controller.states.value === state && controller.canPrepareBlockTarget(state) && presentationCurrent()
        if (current()) currentBlock?.invoke(state, ::current)
    }
    platformBackHandler(state.screen != ReportScreen.HIDDEN && host.isCurrent(), back)
    if (state.screen == ReportScreen.HIDDEN || !host.isCurrent()) return

    FeedMeTheme {
        val originalShown = currentBlueprintHost != null && !reporterAccountId.isNullOrBlank()
        if (originalShown) RetainedBlueprintReport(state, buffer, checkNotNull(reporterAccountId),
            actionable, busy, localFailure, fieldError,
            actions = BlueprintReportActions(
                back = back,
                reason = { reason -> host.run {
                    if (controller.states.value === state && !running && !busy && replacement == null && buffer != null && form.isCurrent(buffer)) {
                        form.edit(buffer, reason, buffer.description)
                        fieldError = null
                    }
                } },
                description = { description -> host.run {
                    if (controller.states.value === state && !running && !busy && replacement == null && buffer != null && form.isCurrent(buffer)) {
                        fieldError = reportDescriptionError(description)
                        if (fieldError == null) form.edit(buffer, buffer.reason, description)
                    }
                } },
                alsoBlock = { selected -> host.run {
                    if (controller.states.value === state && !running && !busy && replacement == null && buffer != null &&
                        form.isCurrent(buffer) && onBlock != null && buffer.blockTargetUserId != reporterAccountId) {
                        form.edit(buffer, buffer.reason, buffer.description, selected)
                    }
                } },
                block = if (onBlock == null) null else ::openBlock,
                review = { act(keepDraft = true) { controller.review(it) } },
                keep = { act(keepDraft = true) { PortResult.Value(it) } },
                confirm = { review -> act { current ->
                    if (current.review !== review || !review.isCurrentForNavigation) PortResult.Failure(FailureReason.CONFLICT)
                    else when (review.kind) {
                        ReportReviewKind.CREATE -> controller.confirm(review)
                        ReportReviewKind.RETRY_ORIGINAL -> controller.retryOriginal(review)
                        ReportReviewKind.DISCARD_UNSENT -> controller.discardUnsent(review)
                    }
                } },
                reviewOriginal = { act { controller.reviewRetry(it) } },
                reviewDiscard = { act { controller.reviewDiscardUnsent(it) } },
                checkStatus = { act { controller.refreshStatus(it) } },
                replace = {
                    val next = state.nextTargetLabel
                    if (next != null && state.screen == ReportScreen.FORM) {
                        act(keepDraft = true, onSuccess = { kept ->
                            val keptInput = kept.input
                            if (keptInput != null && kept.nextTargetLabel == next) {
                                form.bind(controller, keptInput)
                                replacement = ReportReplacementIntent(kept,
                                    form.current(controller, keptInput.targetType, keptInput.targetId, keptInput.targetLabel),
                                    next, ReportReplacementKind.KEPT_DRAFT)
                            }
                        }) { PortResult.Value(it) }
                    } else if (next != null && state.screen == ReportScreen.RESULT && state.completionAcknowledged) host.run {
                        if (controller.states.value === state && !running && !busy && replacement == null)
                            replacement = ReportReplacementIntent(state, null, next, ReportReplacementKind.COMPLETED_REPORT)
                    }
                }),
            isCurrent = { host.isCurrent() && currentBlueprintHost?.invoke() == true && controller.states.value === state &&
                (state.screen != ReportScreen.FORM || buffer == null || form.isCurrent(buffer)) },
            blockOpenStatus = blockOpenStatus)
        else Box(Modifier.fillMaxSize().background(FeedMeColors.Paper).safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = back, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (state.screen == ReportScreen.REVIEW && state.review?.kind == ReportReviewKind.CREATE) "Back to draft" else "Back")
                }
                FeedMeWordmark(compact = true)
                if (state.screen == ReportScreen.UNAVAILABLE) {
                    ReportHeading("Reporting unavailable")
                    ReportNotice("Private details are hidden", reportFailureText(state.failureReason)
                        ?: "Check your account connection, then return to the item you want to report.")
                } else {
                    reportFailureText(localFailure ?: state.failureReason)?.let { ReportNotice("Couldn’t confirm that", it, error = true) }
                    blockOpenStatus?.let { ReportNotice("Blocking", it) }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    when (state.screen) {
                        ReportScreen.FORM -> {
                            if (input == null || buffer == null) {
                                ReportHeading("Report an issue")
                                Text("Open reporting from the person or item you want to report.")
                            } else {
                                ReportHeading("Report ${input.targetLabel}")
                                Text("Tell us what’s wrong. You’ll review everything before sending.", color = FeedMeColors.Muted)
                                state.nextTargetLabel?.let { next ->
                                    ReportNotice("Another person selected", "Your draft for ${input.targetLabel} is still here. Replace it only when you’re ready.")
                                    ReportSecondary("Start report for $next", actionable) {
                                        act(keepDraft = true, onSuccess = { kept ->
                                            val keptInput = kept.input
                                            if (keptInput != null && kept.nextTargetLabel == next) {
                                                form.bind(controller, keptInput)
                                                replacement = ReportReplacementIntent(kept,
                                                    form.current(controller, keptInput.targetType, keptInput.targetId, keptInput.targetLabel),
                                                    next, ReportReplacementKind.KEPT_DRAFT)
                                            }
                                        }) { PortResult.Value(it) }
                                    }
                                }
                                Text("Choose a reason", style = MaterialTheme.typography.titleMedium)
                                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    reportReasons.forEach { reason ->
                                        val selected = buffer.reason == reason.value
                                        Surface(color = if (selected) FeedMeColors.SoftBlue else FeedMeColors.Surface,
                                            shape = RoundedCornerShape(18.dp),
                                            border = BorderStroke(1.dp, if (selected) FeedMeColors.Blue else FeedMeColors.Line)) {
                                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected,
                                                enabled = actionable, role = Role.RadioButton, onClick = {
                                                    host.run {
                                                        if (controller.states.value === state && !running && !busy && replacement == null && form.isCurrent(buffer)) {
                                                            form.edit(buffer, reason.value, buffer.description)
                                                            fieldError = null
                                                        }
                                                    }
                                                }).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                                RadioButton(selected, onClick = null, enabled = actionable)
                                                Spacer(Modifier.width(10.dp))
                                                Text(reason.label, style = MaterialTheme.typography.bodyLarge)
                                            }
                                        }
                                    }
                                }
                                OutlinedTextField(value = buffer.description, onValueChange = { text -> host.run {
                                    if (controller.states.value === state && !running && !busy && replacement == null && form.isCurrent(buffer)) {
                                        fieldError = reportDescriptionError(text)
                                        if (fieldError == null) form.edit(buffer, buffer.reason, text)
                                    }
                                } }, enabled = actionable, label = { Text("What happened? (optional)") }, minLines = 3,
                                    supportingText = { Text(fieldError ?: "${reportTextLength(buffer.description) ?: 0} / 1,000 characters") },
                                    isError = fieldError != null, modifier = Modifier.fillMaxWidth())
                                Text("Don’t include passwords or sensitive personal details.", style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
                                if (onBlock != null && buffer.blockTargetUserId != null && buffer.blockTargetUserId != reporterAccountId) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = buffer.alsoBlock, enabled = actionable, onCheckedChange = { selected -> host.run {
                                            if (controller.states.value === state && !running && !busy && replacement == null && form.isCurrent(buffer))
                                                form.edit(buffer, buffer.reason, buffer.description, selected)
                                        } })
                                        Text("Also block this account")
                                    }
                                    Text("Blocking needs a separate confirmation after your report is received. Nothing is blocked by this choice.", style = MaterialTheme.typography.bodySmall)
                                }
                                Text(if (buffer.dirty) "Typing is kept in memory. Keep draft, Review report, or Back keeps it on this device."
                                    else "Back keeps your place. It does not send a report.", style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
                                ReportPrimary("Review report", actionable && reportCanReview(buffer.reason, buffer.description)) {
                                    act(keepDraft = true) { controller.review(it) }
                                }
                                ReportSecondary("Keep draft", actionable) { act(keepDraft = true) { PortResult.Value(it) } }
                            }
                        }
                        ReportScreen.REVIEW -> {
                            val review = state.review
                            if (review == null || !review.isCurrentForNavigation) ReportNotice("Review changed", "Go back and review the current report. Nothing has been sent from this view.")
                            else {
                                ReportHeading(when (review.kind) {
                                    ReportReviewKind.CREATE -> "Ready to send?"
                                    ReportReviewKind.RETRY_ORIGINAL -> "Your original report"
                                    ReportReviewKind.DISCARD_UNSENT -> "Discard this unsent report?"
                                })
                                ReportInputSummary(review.input)
                                Text(when (review.kind) {
                                    ReportReviewKind.CREATE -> "Send only when these details are right. A receipt means received, not reviewed."
                                    ReportReviewKind.RETRY_ORIGINAL -> "Keep these exact original details. This checks or retries the same report; it does not create a second report."
                                    ReportReviewKind.DISCARD_UNSENT -> "Remove only the unsent request. This does not withdraw any report already received by the service."
                                }, style = MaterialTheme.typography.bodyMedium)
                                ReportPrimary(when (review.kind) {
                                    ReportReviewKind.CREATE -> "Send report"
                                    ReportReviewKind.RETRY_ORIGINAL -> "Retry original report"
                                    ReportReviewKind.DISCARD_UNSENT -> "Discard unsent report"
                                }, actionable) { act { current ->
                                    if (current.review !== review || !review.isCurrentForNavigation) PortResult.Failure(FailureReason.CONFLICT)
                                    else when (review.kind) {
                                        ReportReviewKind.CREATE -> controller.confirm(review)
                                        ReportReviewKind.RETRY_ORIGINAL -> controller.retryOriginal(review)
                                        ReportReviewKind.DISCARD_UNSENT -> controller.discardUnsent(review)
                                    }
                                } }
                                ReportSecondary("Not now", !busy, back)
                            }
                        }
                        ReportScreen.RESULT -> {
                            val result = state.latestStatus ?: state.result
                            if (result == null) {
                                ReportHeading(if (state.completionAcknowledged) "Unsent report discarded" else "Result unavailable")
                                Text(if (state.completionAcknowledged) "No report was sent by discarding this request." else "Keep the original request until its outcome is clear.")
                            } else {
                                val copy = reportStatusPresentation(result.status)
                                ReportHeading(copy.title)
                                ReportNotice(if (state.latestStatus != null) "Latest checked status" else "Submission receipt", copy.text)
                                Text("Status changes are not checked automatically.", style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
                                Surface(color = FeedMeColors.Surface, shape = RoundedCornerShape(22.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(reportReasonLabel(result.reason), style = MaterialTheme.typography.titleMedium)
                                        result.description?.takeIf { it.isNotEmpty() }?.let { Text(it) }
                                    }
                                }
                                ReportPrimary("Check status", actionable && state.completionAcknowledged) { act { controller.refreshStatus(it) } }
                                if (onBlock != null && controller.canPrepareBlockTarget(state)) ReportSecondary("Review blocking this account", actionable) {
                                    openBlock { host.isCurrent() && controller.states.value === state }
                                }
                                if (!state.completionAcknowledged) Text("The reply is kept, but finishing it on this device is not yet confirmed.")
                            }
                            state.nextTargetLabel?.let { next ->
                                ReportSecondary("Start report for $next", actionable && state.completionAcknowledged) {
                                    host.run {
                                        if (controller.states.value === state && !running && !busy && replacement == null)
                                            replacement = ReportReplacementIntent(state, null, next, ReportReplacementKind.COMPLETED_REPORT)
                                    }
                                }
                            }
                            ReportSecondary("Done", !busy, back)
                        }
                        ReportScreen.RECOVERY -> {
                            ReportHeading("Keep the original")
                            val pending = state.pending
                            Text(reportPendingText(pending?.attempts))
                            pending?.let { ReportInputSummary(it.input) }
                            Text("Nothing retries automatically. A retry keeps the same report and original request.", style = MaterialTheme.typography.bodyMedium)
                            state.nextTargetLabel?.let { Text("Selected next: $it. Finish checking the original report before starting another draft.",
                                style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted) }
                            ReportPrimary("Review original report", actionable && pending != null) { act { controller.reviewRetry(it) } }
                            if (pending?.canDiscardUnsent == true) ReportSecondary("Review unsent discard", actionable) { act { controller.reviewDiscardUnsent(it) } }
                        }
                        ReportScreen.HIDDEN, ReportScreen.UNAVAILABLE -> Unit
                    }
                }
            }
        }
        val confirmation = replacement
        if (confirmation != null && state.screen !in setOf(ReportScreen.HIDDEN, ReportScreen.UNAVAILABLE)) {
            val exact = state === confirmation.state && state.nextTargetLabel == confirmation.label &&
                (confirmation.form == null || form.isCurrent(confirmation.form))
            AlertDialog(onDismissRequest = { dismissReplacement(confirmation) },
                title = { Text(if (confirmation.kind == ReportReplacementKind.KEPT_DRAFT) "Replace this draft?" else "Start another report?") },
                text = { Text(if (exact) reportReplacementNotice(confirmation.kind, state.input?.targetLabel.orEmpty(), confirmation.label)
                    else "This selection changed. Keep the current report and choose the person again.") },
                confirmButton = {
                    TextButton(enabled = exact && !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                        if (exact) act(confirmation = confirmation, onSuccess = { replacement = null }) { current ->
                            if (confirmation.form != null && !form.isCurrent(confirmation.form)) PortResult.Failure(FailureReason.CONFLICT)
                            else when (confirmation.kind) {
                                ReportReplacementKind.KEPT_DRAFT -> controller.replaceDraft(current)
                                ReportReplacementKind.COMPLETED_REPORT -> controller.startNew(current)
                            }
                        }
                    }) { Text(if (confirmation.kind == ReportReplacementKind.KEPT_DRAFT) "Replace draft" else "Start new draft") }
                },
                dismissButton = {
                    TextButton(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                        dismissReplacement(confirmation)
                    }) { Text("Keep current report") }
                })
        }
    }
}

@Composable private fun ReportHeading(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
}

@Composable private fun ReportInputSummary(input: ReportInput) {
    Surface(color = FeedMeColors.Surface, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Reporting", style = MaterialTheme.typography.labelMedium, color = FeedMeColors.Muted)
            Text(input.targetLabel, style = MaterialTheme.typography.titleLarge)
            Text(reportReasonLabel(input.reason), style = MaterialTheme.typography.titleMedium)
            Text(input.description.ifEmpty { "No description added." }, style = MaterialTheme.typography.bodyMedium)
            if (input.alsoBlock) Text("Review blocking ${input.blockTargetLabel ?: "this account"} separately after the report is received. This report does not block anyone.",
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable private fun ReportNotice(title: String, text: String, error: Boolean = false) {
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else FeedMeColors.SoftBlue, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable private fun ReportPrimary(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(text) }
}

@Composable private fun ReportSecondary(text: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(text) }
}
