package com.feedme.app.mealflow

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feedme.app.FeedMeTheme
import com.feedme.app.blueprint.*
import com.feedme.core.ports.PortResult
import com.feedme.mealflow.exports.*
import kotlinx.coroutines.launch

/** Original EXPORT and CONFIRM_ACTION designs. Rendering never requests, polls or downloads. */
@Composable
fun FeedMeAccountExportFlow(controller: AccountExportController, hostIsCurrent: () -> Boolean,
    onClose: () -> Unit, onDownload: ((PreparedAccountExportDownload) -> Unit)? = null,
    downloadBusy: Boolean = false, downloadStatus: String? = null,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> }) {
    val state by controller.states.collectAsState()
    val captured = state
    val host by rememberUpdatedState(hostIsCurrent)
    var attached by remember(controller) { mutableStateOf(true) }
    DisposableEffect(controller) { onDispose { attached = false } }
    val scope = rememberCoroutineScope()
    var more by remember(controller) { mutableStateOf(false) }
    fun current() = attached && host() && controller.isCurrent(captured)
    val working = captured.phase == AccountExportPhase.REQUESTING || downloadBusy
    val busy = captured.phase == AccountExportPhase.LOADING || working
    fun act(block: suspend () -> Unit) { if (current() && !busy) scope.launch { if (current()) block() } }
    fun back() {
        if (working) return
        if (captured.phase == AccountExportPhase.LOADING && attached) onClose()
        else if (current() && captured.review != null) act { controller.backReview(captured) }
        else if (attached) onClose()
    }
    platformBackHandler(attached && !working, ::back)
    val review = captured.review.takeIf { current() }
    if (review != null) {
        val page = BlueprintConfirmationState(actionLabel = if (review.retryOriginal) "Retry original export request" else "Request private export",
            affectedSummary = "Your eligible FeedMe account data · JSON",
            consequences = listOf("Includes eligible profile, preferences, recipes, cooking and your own sharing records.",
                "Other people’s private content, credentials and private operational records are excluded.",
                "Photos and videos are not included in this JSON export.",
                "Requesting starts a private job. Downloading needs a separate action and a location you choose.",
                if (review.retryOriginal) "Reuse only the retained original request. Its previous outcome is not assumed to have failed."
                else "A recent sign-in is checked by the server. No new export is requested until you confirm."),
            canConfirm = current() && !busy, canCancel = current() && !busy, busy = busy)
        BlueprintConfirmationScreen(page,
            onConfirm = { if (it === page && page.confirmEnabled) act { controller.confirm(review) } },
            onCancel = { if (it === page && page.cancelEnabled) back() },
            heading = "Your data.\nYour choice.", confirmLabel = if (review.retryOriginal) "Retry original request" else "Request export")
        return
    }
    val job = captured.job.takeIf { current() }
    // A receipt-selected job has no resource ETag yet. This opaque selection revision is
    // used only for UI callback fencing; it is never sent as a server version or If-Match.
    val jobRef = job?.let { BlueprintControlReference(BlueprintControlReferenceKind.EXPORT_JOB, it.id,
        it.version ?: "receipt:${captured.receipt?.commandId ?: captured.revision}") }
    val context = if (current() && captured.accountId != null && captured.accountVersion != null)
        BlueprintAccountControlContext(BlueprintControlReference(BlueprintControlReferenceKind.ACCOUNT,
            captured.accountId!!, captured.accountVersion!!), captured.revision, job = jobRef) else null
    val page = BlueprintAccountControlsState(BlueprintAccountControlPage.EXPORT,
        phase = when {
            !current() || captured.phase == AccountExportPhase.UNAVAILABLE -> BlueprintAccountControlPhase.UNAVAILABLE
            busy -> BlueprintAccountControlPhase.WORKING
            captured.pending != null -> BlueprintAccountControlPhase.UNKNOWN
            captured.phase in setOf(AccountExportPhase.ERROR, AccountExportPhase.EXPIRED) -> BlueprintAccountControlPhase.ERROR
            else -> BlueprintAccountControlPhase.READY
        }, context = context,
        policies = if (context == null) emptySet() else setOf(BlueprintAccountPolicyFact.EXPORT_SCOPE, BlueprintAccountPolicyFact.EXPORT_ACCESS),
        enabledActionIds = buildSet {
            if (!working) add("EXPORT.back")
            if (current() && !busy) {
                if (captured.canRequest) add("EXPORT.01")
                if (captured.canCheckStatus) add("EXPORT.02")
                if (job?.downloadReady == true && captured.downloadConfigured && onDownload != null) add("EXPORT.03")
            }
        },
        exportJob = if (job != null && jobRef != null) BlueprintControlExport(jobRef,
            statusLabel = when (job.status) { "pending" -> "Export requested"; "running" -> "Preparing your export"
                "complete" -> "Export prepared"; "failed" -> "Export could not be prepared"; else -> "Export status unavailable" },
            detail = if (job.downloadReady) "Ready for an explicit private download. The link is short-lived."
                else "Choose Check export status for a fresh observation. This screen does not poll automatically.", downloadReady = job.downloadReady) else null,
        statusMessage = listOfNotNull(exportStatus(captured), downloadStatus).joinToString("\n\n"))
    BlueprintAccountControlsScreen(page, onEvent = { event ->
        if (event.expected === page && event is BlueprintAccountControlEvent.Action && page.admits(event.actionId)) when (event.actionId) {
            "EXPORT.back" -> back()
            "EXPORT.01" -> act { controller.prepareRequest(captured) }
            "EXPORT.02" -> act { controller.checkStatus(captured) }
            "EXPORT.03" -> act {
                when (val selected = controller.prepareDownload(captured)) {
                    is PortResult.Failure -> Unit
                    is PortResult.Value -> if (attached && host() && selected.value.isCurrent && onDownload != null)
                        onDownload(selected.value) else selected.value.close()
                }
            }
        }
    }, onMore = { if (current() && !busy) more = true })
    if (more && current()) FeedMeTheme {
        AlertDialog(onDismissRequest = { more = false }, title = { Text("Your export") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(exportStatus(captured))
                if (captured.canCheckStatus) TextButton(onClick = { more = false; act { controller.checkStatus(captured) } }, enabled = !busy) { Text("Check current export status") }
                else if (captured.pending == null) TextButton(onClick = { more = false; act { controller.open() } }, enabled = !busy) { Text("Refresh account export view") }
                captured.pending?.let { pending ->
                    if (pending.canRetry) TextButton(onClick = { more = false; act { controller.prepareRetry(captured) } }, enabled = !busy) { Text("Review original retry") }
                    if (pending.receiptReady) TextButton(onClick = { more = false; act { controller.recoverReceipt(captured) } }, enabled = !busy) { Text("Recover retained receipt") }
                    Text("An unresolved original stays protected. A new request cannot replace it.")
                }
                Text("This download is JSON data, not photos or videos. Files you save outside FeedMe are under your control.")
            } }, confirmButton = { TextButton(onClick = { more = false }) { Text("Close") } })
    }
}

private fun exportStatus(state: AccountExportState): String = when {
    state.reauthenticationRequired -> "A recent sign-in is required before this request can continue. The original stays protected here. Returning to Settings does not retry or replace it."
    state.phase == AccountExportPhase.EXPIRED -> "This export or observation has expired. Check the current status before requesting another."
    state.pending != null -> "Your original export request is retained. Use More to review its retry or recover a receipt."
    state.failureReason != null -> "Couldn’t complete this action. No export or download is marked ready without confirmation."
    state.phase == AccountExportPhase.LOADING -> "Loading your account export state…"
    state.phase == AccountExportPhase.REQUESTING -> "Sending your reviewed export request…"
    state.phase == AccountExportPhase.UNAVAILABLE -> "Export is unavailable for this account or configuration. Back returns to Privacy."
    state.job != null -> "Job status is a recorded observation. Downloading checks current access again."
    else -> "Request a private JSON copy of eligible account data. Photos and videos are not included."
}
