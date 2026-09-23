package com.feedme.app.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feedme.app.blueprint.*
import com.feedme.core.ports.FailureReason
import com.feedme.mealflow.reports.*

internal class BlueprintReportActions(
    val back: () -> Unit,
    val reason: (String) -> Unit,
    val description: (String) -> Unit,
    val review: () -> Unit,
    val keep: () -> Unit,
    val confirm: (ReportReview) -> Unit,
    val reviewOriginal: () -> Unit,
    val reviewDiscard: () -> Unit,
    val checkStatus: () -> Unit,
    val replace: () -> Unit,
    val alsoBlock: (Boolean) -> Unit,
    val block: ((() -> Boolean) -> Unit)? = null,
)

private fun blueprintReportReason(value: String?): BlueprintReportReason? = when (value) {
    "harassment" -> BlueprintReportReason.HARASSMENT
    "unsafeFood" -> BlueprintReportReason.UNSAFE_CONTENT
    "privacy" -> BlueprintReportReason.PRIVACY
    "spam" -> BlueprintReportReason.SPAM
    "other" -> BlueprintReportReason.OTHER
    else -> null
}
private fun BlueprintReportReason.wireReason(): String = when (this) {
    BlueprintReportReason.HARASSMENT -> "harassment"
    BlueprintReportReason.UNSAFE_CONTENT -> "unsafeFood"
    BlueprintReportReason.PRIVACY -> "privacy"
    BlueprintReportReason.SPAM -> "spam"
    BlueprintReportReason.OTHER -> "other"
}

/** Original REPORT chrome, backed only by the retained reporting owner. Render revisions
 * correlate an exact local observation; neither they nor displayed IDs are server grants. */
@Composable
internal fun RetainedBlueprintReport(
    state: ReportState, buffer: ReportFormSnapshot?, reporterAccountId: String,
    actionable: Boolean, busy: Boolean, failure: FailureReason?, fieldError: String?,
    actions: BlueprintReportActions, isCurrent: () -> Boolean,
    blockOpenStatus: String? = null,
) {
    var tools by remember(state, buffer) { mutableStateOf(false) }
    var visit by remember(state, buffer) { mutableStateOf(Any()) }
    var attached by remember(state, buffer) { mutableStateOf(true) }
    DisposableEffect(state, buffer) { onDispose { attached = false; visit = Any() } }
    fun closeTools() { tools = false; visit = Any() }
    fun current() = attached && isCurrent()
    val input = when (state.screen) {
        ReportScreen.REVIEW -> state.review?.input
        ReportScreen.RECOVERY -> state.pending?.input
        else -> state.input
    }
    val material = state.screen !in setOf(ReportScreen.HIDDEN, ReportScreen.UNAVAILABLE)
    val targetKind = when (input?.targetType) {
        "user" -> BlueprintControlReferenceKind.USER
        "post" -> BlueprintControlReferenceKind.POST
        "message" -> BlueprintControlReferenceKind.MESSAGE
        else -> null
    }
    val context = remember(state, buffer, reporterAccountId) {
        if (!material) null else BlueprintAccountControlContext(
            BlueprintControlReference(BlueprintControlReferenceKind.ACCOUNT, reporterAccountId, "retained-report-owner"),
            "exact-report-render:${buffer?.generation ?: "read-only"}",
            if (targetKind != null && input != null) BlueprintControlReference(targetKind, input.targetId, "retained-report-target") else null)
    }
    val editable = actionable && state.screen == ReportScreen.FORM && buffer != null
    val reason = if (state.screen == ReportScreen.FORM && buffer != null) buffer.reason else input?.reason
    val description = if (state.screen == ReportScreen.FORM && buffer != null) buffer.description else input?.description.orEmpty()
    val alsoBlock = if (state.screen == ReportScreen.FORM && buffer != null) buffer.alsoBlock else input?.alsoBlock == true
    val blockTarget = input?.blockTargetUserId?.takeIf { it != reporterAccountId && it.isNotBlank() }?.let {
        BlueprintControlReference(BlueprintControlReferenceKind.USER, it, "retained-report-author")
    }
    val canChooseBlock = editable && blockTarget != null && actions.block != null
    val canReviewBlock = actionable && state.screen == ReportScreen.RESULT && state.result != null &&
        state.completionAcknowledged && alsoBlock && blockTarget != null && actions.block != null
    val review = state.review?.takeIf { it.isCurrentForNavigation }
    val receipt = (state.latestStatus ?: state.result)?.takeIf { state.screen == ReportScreen.RESULT && material }
    val status = buildList {
        reportFailureText(failure ?: state.failureReason)?.let(::add)
        blockOpenStatus?.let(::add)
        when (state.screen) {
            ReportScreen.FORM -> {
                add("Review comes next; nothing is sent yet.")
                add(if (buffer?.dirty == true) "Typing stays in memory until Keep draft, review or Back."
                    else "Back keeps your draft; it does not send it.")
                state.nextTargetLabel?.let { add("Your current draft is kept. More has the next target.") }
            }
            ReportScreen.REVIEW -> add(when (review?.kind) {
                ReportReviewKind.CREATE -> "Review these exact details. Submit report now sends this report. A receipt means received, not reviewed."
                ReportReviewKind.RETRY_ORIGINAL -> "These are your exact original details. More → Retry original report checks or retries that same request, never a second report."
                ReportReviewKind.DISCARD_UNSENT -> "These are the unsent original details. More → Discard unsent report removes only that unsent request; it does not withdraw a received report."
                null -> "This review changed. Go back and review the current report. Nothing is sent from this view."
            })
            ReportScreen.RECOVERY -> {
                add(reportPendingText(state.pending?.attempts))
                add("Nothing retries automatically. Open More to review the original report or an available unsent discard.")
                state.nextTargetLabel?.let { add("A next target is retained. Finish the original before starting another report.") }
            }
            ReportScreen.RESULT -> {
                if (receipt == null) add(if (state.completionAcknowledged) "Unsent report discarded. Discarding did not send a report."
                    else "The result is unavailable. Keep the original request until its outcome is clear.")
                else {
                    add(reportStatusPresentation(receipt.status).text)
                    add("Status is not checked automatically. Use More → Check status for an explicit update.")
                    if (!state.completionAcknowledged) add("The reply is kept, but finishing it on this device is not yet confirmed.")
                }
            }
            else -> Unit
        }
        if (material && reason == "unsafeFood") add("Unsafe content: unsafe food advice.")
        if (material && alsoBlock) add("After the report is received, review blocking ${input?.blockTargetLabel ?: "this account"} separately. Reporting does not block anyone.")
    }.joinToString("\n\n")
    val model = BlueprintAccountControlsState(BlueprintAccountControlPage.REPORT,
        phase = when {
            !material -> BlueprintAccountControlPhase.UNAVAILABLE
            busy -> BlueprintAccountControlPhase.WORKING
            state.screen == ReportScreen.FORM && buffer == null -> BlueprintAccountControlPhase.LOADING
            state.screen == ReportScreen.RECOVERY -> BlueprintAccountControlPhase.UNKNOWN
            else -> BlueprintAccountControlPhase.READY
        }, context = context,
        enabledActionIds = buildSet {
            if (!busy) { add("REPORT.back"); add("REPORT.02") }
            if (editable && fieldError == null && reportCanReview(reason, description) ||
                actionable && state.screen == ReportScreen.REVIEW && review?.kind == ReportReviewKind.CREATE) add("REPORT.01")
        }, editableFields = if (editable) buildSet {
            add(BlueprintAccountControlField.REPORT_REASON); add(BlueprintAccountControlField.REPORT_CONTEXT)
            if (canChooseBlock) add(BlueprintAccountControlField.REPORT_BLOCK)
        } else emptySet(),
        fields = BlueprintAccountControlFields(reason = blueprintReportReason(reason), context = description, alsoBlock = alsoBlock),
        policies = if (blockTarget != null) setOf(BlueprintAccountPolicyFact.BLOCKING_EFFECTS) else emptySet(),
        reportBlockTarget = blockTarget,
        fieldErrors = if (fieldError != null && state.screen == ReportScreen.FORM) mapOf(BlueprintAccountControlField.REPORT_CONTEXT to fieldError) else emptyMap(),
        statusMessage = status,
        selectedPerson = context?.target?.takeIf { it.kind == BlueprintControlReferenceKind.USER }?.let { BlueprintControlPerson(it, input!!.targetLabel) },
        selectedPost = context?.target?.takeIf { it.kind == BlueprintControlReferenceKind.POST }?.let { BlueprintControlPost(it, input!!.targetLabel) },
        receipt = receipt?.let {
            val copy = reportStatusPresentation(it.status)
            BlueprintControlReceipt(BlueprintControlReference(BlueprintControlReferenceKind.RECEIPT, it.id, it.version.toString()),
                copy.title, (if (state.latestStatus != null) "Latest checked status. " else "Submission receipt. ") + copy.text)
        })
    BlueprintAccountControlsScreen(model, onEvent = { event ->
        if (current() && !tools && event.expected === model) when (event) {
            is BlueprintAccountControlEvent.Action -> if (model.admits(event.actionId)) when (event.actionId) {
                "REPORT.back", "REPORT.02" -> actions.back()
                "REPORT.01" -> if (state.screen == ReportScreen.FORM) actions.review() else review?.let(actions.confirm)
                else -> Unit
            }
            is BlueprintAccountControlEvent.FieldChanged -> if (model.accepts(event.field, event.value)) when (event.field) {
                BlueprintAccountControlField.REPORT_REASON -> (event.value as? BlueprintAccountControlValue.Reason)?.let { actions.reason(it.value.wireReason()) }
                BlueprintAccountControlField.REPORT_CONTEXT -> (event.value as? BlueprintAccountControlValue.Text)?.let { actions.description(it.value) }
                BlueprintAccountControlField.REPORT_BLOCK -> (event.value as? BlueprintAccountControlValue.Toggle)?.let { actions.alsoBlock(it.value) }
                else -> Unit
            }
        }
    }, onMore = { if (current() && !busy && !tools) { visit = Any(); tools = true } })
    if (tools) {
        val shownVisit = visit
        fun toolsCurrent() = current() && tools && visit === shownVisit
        AlertDialog(onDismissRequest = ::closeTools, title = { Text("Report actions") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            fun selected(action: () -> Unit) { if (toolsCurrent() && !busy) { closeTools(); action() } }
            when (state.screen) {
                ReportScreen.FORM -> {
                    TextButton(onClick = { selected(actions.keep) }, enabled = editable) { Text("Keep draft") }
                    state.nextTargetLabel?.let { next -> TextButton(onClick = { selected(actions.replace) }, enabled = editable) { Text("Start report for $next") } }
                }
                ReportScreen.REVIEW -> if (review != null) TextButton(onClick = { selected { actions.confirm(review) } }, enabled = actionable) {
                    Text(when (review.kind) { ReportReviewKind.CREATE -> "Send report"; ReportReviewKind.RETRY_ORIGINAL -> "Retry original report"; ReportReviewKind.DISCARD_UNSENT -> "Discard unsent report" })
                }
                ReportScreen.RECOVERY -> {
                    TextButton(onClick = { selected(actions.reviewOriginal) }, enabled = actionable && state.pending != null) { Text("Review original report") }
                    if (state.pending?.canDiscardUnsent == true) TextButton(onClick = { selected(actions.reviewDiscard) }, enabled = actionable) { Text("Review unsent discard") }
                }
                ReportScreen.RESULT -> {
                    if (canReviewBlock) TextButton(onClick = {
                        if (toolsCurrent() && !busy) actions.block?.invoke { toolsCurrent() && !busy }
                    }, enabled = canReviewBlock) { Text("Review blocking this account") }
                    if (receipt != null) TextButton(onClick = { selected(actions.checkStatus) }, enabled = actionable && state.completionAcknowledged) { Text("Check status") }
                    state.nextTargetLabel?.let { next -> TextButton(onClick = { selected(actions.replace) }, enabled = actionable && state.completionAcknowledged) { Text("Start report for $next") } }
                }
                else -> Unit
            }
            Text("Do not include passwords or sensitive personal details. Unsafe content covers unsafe food advice.", style = BlueprintType.Small)
            Text("Sending a report does not block anyone. If selected, blocking needs its own confirmation after the report is received. A report receipt does not confirm moderation action. Nothing retries automatically.", style = BlueprintType.Small)
        }
    }, confirmButton = { TextButton(onClick = ::closeTools) { Text("Close actions") } })
    }
}
