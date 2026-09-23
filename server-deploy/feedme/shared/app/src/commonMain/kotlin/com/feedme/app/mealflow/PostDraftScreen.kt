package com.feedme.app.mealflow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.feedme.app.FeedMeColors
import com.feedme.app.FeedMeDetails
import com.feedme.app.FeedMeTheme
import com.feedme.core.ports.PortResult
import com.feedme.mealflow.social.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun draftAcknowledgementText(localAcknowledged: Boolean, serverAcknowledged: Boolean,
    textMatchesServer: Boolean, finalizationRequired: Boolean,
    serverAssociated: Boolean = false, serverContentAvailable: Boolean = true): String = when {
    finalizationRequired -> "The original action needs local confirmation. Retry that original action when available; refreshing is not confirmation."
    !localAcknowledged -> "Your latest edit is still here, but storage has not confirmed it. Keep this session open and retry retaining the text."
    serverAssociated && !serverContentAvailable -> "Server content is unavailable. Your local text is retained. Refresh the server status or review discard."
    serverAcknowledged && textMatchesServer -> "Saved privately on the server. Nothing has been posted."
    serverAcknowledged -> "The earlier text was saved. Your newer edits are still local."
    textMatchesServer -> "Matches the last observed server draft. This view is not a new save confirmation."
    else -> "Kept on this device. Save to server is a separate action."
}

internal fun draftTextValidationError(issue: PostDraftIssue): String? = if (issue == PostDraftIssue.INVALID_INPUT)
    "Edit not applied. Check your text: captions and image descriptions each support up to 500 Unicode characters. Your previous text is unchanged."
else null

internal fun draftIssueText(issue: PostDraftIssue): String? = when (issue) {
    PostDraftIssue.NONE, PostDraftIssue.CONFIRM_DISCARD -> null
    PostDraftIssue.ORIGINAL_PENDING -> "An earlier action is unresolved. Retry its original request before starting another server change."
    PostDraftIssue.LOCAL_UNACKNOWLEDGED -> "Local storage has not confirmed the latest edit. Your text has not been treated as saved."
    PostDraftIssue.CONTEXT_CHANGED -> "This draft changed while the action was in progress. Review it again."
    PostDraftIssue.RECONCILIATION_REQUIRED -> "The earlier outcome needs review. A fresh read cannot prove that action was acknowledged."
    PostDraftIssue.CAPACITY -> "This device's draft storage limit has been reached. Nothing was automatically removed."
    PostDraftIssue.OFFLINE -> "You are offline. Local drafting stays available; reconnecting does not submit anything."
    PostDraftIssue.INVALID_INPUT -> "Check your text and try again. Captions and image descriptions each support up to 500 Unicode characters."
    PostDraftIssue.DATA_UNVERIFIED -> "This data could not be verified. No new save has been confirmed. Refresh or retry the original action when available."
    PostDraftIssue.STORAGE -> "Storage could not confirm this action. Your original request has not been replaced."
    PostDraftIssue.SESSION_UNAVAILABLE -> "This account session is no longer available. Private draft content is hidden."
}

internal fun canReviewServerDraftDiscard(associated: Boolean, observedStatus: String?): Boolean =
    associated && (observedStatus == null || observedStatus in setOf("draft", "expired"))

/** A visible original-action card already explains this one issue. Keep unrelated feedback,
 * including local storage, invalid edits and session loss, even while an original is pending. */
internal fun draftHeadsUpText(issue: PostDraftIssue, hasPending: Boolean): String? =
    if (issue == PostDraftIssue.ORIGINAL_PENDING && hasPending) null else draftIssueText(issue)

internal data class DraftPendingMessage(val title: String, val detail: String)

/** Display only: these strings grant no retry/cancellation permission or acknowledgement.
 * Unknown inputs are never echoed into the UI or treated as successful/unsent actions. */
internal fun draftPendingMessage(operationId: String, phase: String, issue: String,
    finalizationRequired: Boolean, canDiscardUnsent: Boolean): DraftPendingMessage {
    val action = when (operationId) {
        "createPostDraft", "updatePostDraft" -> "Private save"
        "deletePostDraft" -> "Server discard"
        else -> "Draft action"
    }
    return when {
        phase == "HISTORICAL_COMPLETION" -> DraftPendingMessage("$action needs review",
            "An earlier result is stored, but this session cannot confirm it. Opening or refreshing a draft does not complete the original action.")
        finalizationRequired -> DraftPendingMessage("$action needs local confirmation",
            "Your device has not confirmed the original action. Retry that same action when available; refreshing is not confirmation.")
        phase in setOf("DISPATCH_UNOBSERVED", "IN_FLIGHT", "AWAITING_CONFIRMATION") || issue == "OUTCOME_UNKNOWN" ->
            DraftPendingMessage("$action is not confirmed",
                "The original request may have reached the server. Its result is not confirmed here. Retry only that original action when available.")
        phase in setOf("RECEIPT_READY", "APPLIED", "DISCARDED", "DELIVERY_PENDING") ->
            DraftPendingMessage("$action needs local confirmation",
                "The original action still needs confirmation on this device. This status alone does not confirm a server change.")
        phase == "NEEDS_RESOLUTION" -> DraftPendingMessage("$action needs attention",
            "This action cannot continue yet. Its original request is retained; refreshing does not confirm it or create a replacement request.")
        phase == "RETRY_WAIT" -> DraftPendingMessage("$action is waiting to retry",
            "The original request is retained. Retry it when available; reconnecting does not send it automatically.")
        phase == "READY" && canDiscardUnsent && issue == "NONE" &&
            operationId in setOf("createPostDraft", "updatePostDraft", "deletePostDraft") -> DraftPendingMessage("$action has not been sent",
            "The original request is kept on this device. You can cancel this unsent action. Opening this screen does not send it.")
        phase == "READY" -> DraftPendingMessage("$action is waiting",
            "The original request is retained. Opening this screen does not send it or confirm that it was completed.")
        else -> DraftPendingMessage("$action needs review",
            "The original request is retained, but its current status is unavailable. Opening or refreshing a draft does not confirm or retry it.")
    }
}

private class LocalDraftRemoval(val clientId: String, val revision: Long)

/** Compact, content-sized introduction leaves editing/retry controls in reach. No fixed height,
 * clipping or maxLines: enlarged system text can wrap and the whole page remains scrollable. */
@Composable
private fun DraftIntro(editor: Boolean, publicationConnected: Boolean) {
    Surface(color = FeedMeColors.Blue, contentColor = Color.White, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("NO PRESSURE TO POST.", style = MaterialTheme.typography.labelMedium)
            Text(if (editor) "Keep the moment." else "Your kitchen, unfiltered.",
                style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text(if (publicationConnected) "Save keeps your draft private. Publishing is a separate review and confirmation." else "Private drafts — nothing is shared here. Save keeps it private; publishing is not connected here.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Called only with the return from an explicit Back action. Exact reference identity avoids
 * an old return hiding a newer navigation state. This is not a mutation/receipt capability. */
internal fun draftBackExitReady(returnedState: Any?, currentState: Any, returnedScreen: PostDraftScreen?): Boolean =
    returnedState != null && returnedState === currentState && returnedScreen == PostDraftScreen.HIDDEN

/** Presentation restriction only. Exact current owner observation is required; a selected
 * text projection or a previously rendered format cannot grant mutation controls. */
internal fun reviewedDraftMutationControlsAllowed(currentFormatOnly: Boolean, format: PostDraftJournalFormat): Boolean =
    !currentFormatOnly || format == PostDraftJournalFormat.CURRENT

/** The caller retains the real controller. UI attachment does not restore, fetch, save, upload or
 * publish. Unsaved editor buffers stay in memory, never saved instance state or logs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedMePostDraftFlow(controller: PostDraftController,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
    onReviewPrivateSave: (suspend (String, Long) -> Unit)? = null,
    onReviewPublication: (suspend (String, Long) -> Unit)? = null,
    onOpenPublications: (() -> Unit)? = null,
    onEditChoices: (suspend (String, Long) -> Unit)? = null,
    hasRetainedPublication: Boolean = false,
    onReviewRestoredLocalRetention: ((String, Long) -> Unit)? = null,
    onExit: (() -> Unit)? = null,
    currentFormatOnly: Boolean = false,
    localPhotoOwnerId: String? = null) {
    val state = controller.states.collectAsState().value
    val scope = rememberCoroutineScope()
    // Keep this native picker owner mounted through the selected draft's capture/editor
    // transitions. No picker, import, persistence or upload starts during composition.
    val capture = if (localPhotoOwnerId != null) rememberBlueprintPhotoCapture(controller) else null
    val currentPhotoOwner by rememberUpdatedState(localPhotoOwnerId)
    var photoPanel by remember(controller) { mutableStateOf<BlueprintPhotoUploadPanel?>(null) }
    var uploadedPhotoRemoval by remember(controller) { mutableStateOf<DraftUploadedPhotoRemovalReview?>(null) }
    val localPhotoText = remember(controller, localPhotoOwnerId, state.selected?.clientDraftId) {
        if (localPhotoOwnerId != null) state.selected?.let(::BlueprintLocalPhotoTextBuffer) else null
    }
    LaunchedEffect(localPhotoText, state.selected?.localRevision, state.selected?.localAcknowledged, localPhotoText?.saving) {
        val selectedText = state.selected
        if (localPhotoText != null && selectedText != null && !localPhotoText.saving && selectedText.localAcknowledged &&
            (!localPhotoText.dirty || (selectedText.caption == localPhotoText.caption && selectedText.altText.orEmpty() == localPhotoText.alt)))
            localPhotoText.accept(selectedText)
    }
    var leaveUnsavedText by remember(controller, state.selected?.clientDraftId) { mutableStateOf(false) }
    val photoUploadConfigured = localPhotoOwnerId != null && controller.photoUploads.configured
    fun openPhotoUpload(selectedPhoto: LocalPostDraft, assetId: String,
        preview: com.feedme.app.blueprint.BlueprintAuthoringPreview.Actual?) {
        val account = currentPhotoOwner ?: return
        val observed = controller.states.value
        if (uploadedPhotoRemoval != null || !controller.photoUploads.configured || observed.busy || observed.phase == PostDraftPhase.UNAVAILABLE ||
            observed.screen != PostDraftScreen.EDITOR || localPhotoText?.dirty == true || localPhotoText?.saving == true ||
            observed.selected?.let { it.clientDraftId == selectedPhoto.clientDraftId && it.localRevision == selectedPhoto.localRevision &&
                it.localPhotos.any { photo -> photo.assetId == assetId } } != true) return
        lateinit var panel: BlueprintPhotoUploadPanel
        panel = BlueprintPhotoUploadPanel(controller, scope, account, selectedPhoto.clientDraftId,
            selectedPhoto.localRevision, assetId, preview,
            attached = { photoPanel === panel && currentPhotoOwner == account },
            onAttached = { acknowledged ->
                if (photoPanel === panel && currentPhotoOwner == account && controller.states.value === acknowledged &&
                    acknowledged.screen == PostDraftScreen.EDITOR && acknowledged.phase == PostDraftPhase.READY &&
                    acknowledged.selected?.let { it.clientDraftId == selectedPhoto.clientDraftId &&
                        it.localRevision == selectedPhoto.localRevision + 1 && it.localAcknowledged &&
                        it.uploadedPhotos.any { photo -> photo.assetId == assetId } } == true)
                    photoPanel = null
            })
        photoPanel = panel
        panel.inspect() // Explicit entry action only; mounting the panel performs no I/O.
    }
    val currentFormatRestriction by rememberUpdatedState(currentFormatOnly)
    fun writableNow() = uploadedPhotoRemoval == null &&
        reviewedDraftMutationControlsAllowed(currentFormatRestriction, controller.states.value.journalFormat)
    fun removablePhotoCurrent(observed: PostDraftState, local: LocalPostDraft, assetId: String): Boolean =
        controller.states.value === observed && observed.selected === local &&
            reviewedDraftMutationControlsAllowed(currentFormatRestriction, observed.journalFormat) &&
            observed.journalFormat == PostDraftJournalFormat.CURRENT && !observed.busy &&
            observed.screen == PostDraftScreen.EDITOR && observed.phase == PostDraftPhase.READY &&
            observed.issue == PostDraftIssue.NONE && observed.failureReason == null &&
            local.localAcknowledged && !local.publicationHeld &&
            (!local.serverAssociated || local.server?.status == "draft") &&
            local.uploadedPhotos.count { it.assetId == assetId } == 1 &&
            observed.pending == null && observed.reviewedAllocation == null && observed.publicationHold == null &&
            observed.reviewedSave == null && observed.reviewedRetry == null && observed.discardConfirmation == null &&
            observed.unsubmittedRemainders.isEmpty() && photoPanel == null && capture?.working != true &&
            localPhotoText?.dirty != true && localPhotoText?.saving != true
    fun reviewUploadedPhotoRemoval(local: LocalPostDraft, assetId: String) {
        val observed = controller.states.value
        if (!writableNow() || !removablePhotoCurrent(observed, local, assetId)) return
        uploadedPhotoRemoval = DraftUploadedPhotoRemovalReview(observed, local, assetId,
            local.uploadedPhotos.indexOfFirst { it.assetId == assetId } + 1)
    }
    fun confirmUploadedPhotoRemoval(review: DraftUploadedPhotoRemovalReview) {
        if (uploadedPhotoRemoval !== review || review.attempted ||
            !removablePhotoCurrent(review.state, review.local, review.assetId)) return
        review.attempted = true; review.busy = true
        scope.launch {
            try {
                if (uploadedPhotoRemoval !== review || !removablePhotoCurrent(review.state, review.local, review.assetId)) {
                    review.message = "This draft changed before the action started. Nothing was sent."; return@launch
                }
                val result = controller.removeUploadedPhoto(review.local.clientDraftId, review.local.localRevision, review.assetId)
                val actual = (result as? PortResult.Value)?.value
                val kept = actual?.selected
                review.message = if (actual != null && controller.states.value === actual && kept != null &&
                    actual.phase == PostDraftPhase.READY && kept.clientDraftId == review.local.clientDraftId &&
                    review.local.localRevision != Long.MAX_VALUE && kept.localRevision == review.local.localRevision + 1 &&
                    kept.localAcknowledged && kept.uploadedPhotos.none { it.assetId == review.assetId })
                    "Photo removed from this draft on this device. No server change or deletion was performed."
                else "The local change is not confirmed. Close and use the draft’s retained-change recovery; do not repeat the upload."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { review.message = "The local change is not confirmed. Close and use the draft’s recovery options." }
            finally { review.busy = false }
        }
    }
    fun openChoices(local: LocalPostDraft) {
        val edit = onEditChoices ?: return
        val observed = controller.states.value
        if (!writableNow() || observed.busy || observed.phase != PostDraftPhase.READY || observed.screen != PostDraftScreen.EDITOR ||
            observed.selected?.let { it.clientDraftId == local.clientDraftId && it.localRevision == local.localRevision &&
                it.localAcknowledged && !it.publicationHeld } != true || observed.pending != null ||
            observed.reviewedAllocation != null || observed.publicationHold != null ||
            localPhotoText?.dirty == true || localPhotoText?.saving == true) return
        scope.launch {
            if (controller.states.value === observed && writableNow() && localPhotoText?.dirty != true && localPhotoText?.saving != true)
                edit(local.clientDraftId, local.localRevision)
        }
    }
    fun openPhotoPublication(local: LocalPostDraft) {
        val review = onReviewPublication ?: return
        val observed = controller.states.value
        if (!writableNow() || observed.selected !== local || observed.busy || observed.phase != PostDraftPhase.READY ||
            observed.screen != PostDraftScreen.EDITOR || !local.localAcknowledged || local.publicationHeld ||
            local.localPhotos.isNotEmpty() || observed.pending != null || observed.reviewedAllocation != null ||
            observed.publicationHold != null || observed.reviewedSave != null || observed.reviewedRetry != null ||
            photoPanel != null || localPhotoText?.dirty == true || localPhotoText?.saving == true) return
        scope.launch {
            if (controller.states.value === observed && writableNow() && photoPanel == null &&
                localPhotoText?.dirty != true && localPhotoText?.saving != true)
                review(local.clientDraftId, local.localRevision)
        }
    }
    fun mutate(action: suspend () -> Unit) {
        scope.launch { if (writableNow()) action() }
    }
    var removal by remember(controller) { mutableStateOf<LocalDraftRemoval?>(null) }
    var abandon by remember(controller) { mutableStateOf<PreparedReviewedDraftAllocationAbandon?>(null) }
    fun back(discardBufferedText: Boolean = false) {
        uploadedPhotoRemoval?.let { if (!it.busy) uploadedPhotoRemoval = null; return }
        photoPanel?.let { panel ->
            if (panel.review != null) panel.dismissReview()
            else if (panel.initialUseStatus != null) panel.dismissInitialChoices()
            else photoPanel = null
            return
        }
        if (!discardBufferedText && localPhotoText?.dirty == true && !localPhotoText.saving) {
            leaveUnsavedText = true; return
        }
        if (abandon != null) { abandon = null; return }
        if (removal != null) { removal = null; return }
        // Explicit navigation remains possible after session redaction; no failed controller
        // result is converted into success, and no data operation is needed to leave the page.
        if (controller.states.value.phase == PostDraftPhase.UNAVAILABLE && onExit != null) { onExit(); return }
        scope.launch {
            if (controller.states.value.discardConfirmation != null) controller.dismissServerDiscard()
            else when (val result = controller.back()) {
                is PortResult.Value -> if (draftBackExitReady(result.value, controller.states.value, result.value.screen)) onExit?.invoke()
                is PortResult.Failure -> Unit
            }
        }
    }
    platformBackHandler(true) { back() }
    uploadedPhotoRemoval?.let { review ->
        DraftUploadedPhotoRemovalDialog(review,
            current = { uploadedPhotoRemoval === review && removablePhotoCurrent(review.state, review.local, review.assetId) },
            confirm = { confirmUploadedPhotoRemoval(review) },
            close = { if (uploadedPhotoRemoval === review && !review.busy) uploadedPhotoRemoval = null })
        return
    }
    val enabled = !state.busy && state.phase != PostDraftPhase.UNAVAILABLE
    val selected = state.selected
    val editable = selected != null && (!selected.serverAssociated || selected.server?.status == "draft")
    val textError = draftTextValidationError(state.issue)
    LaunchedEffect(selected?.clientDraftId, selected?.localRevision, selected?.serverAssociated, state.screen) {
        val ticket = removal
        if (ticket != null && (selected?.clientDraftId != ticket.clientId || selected.localRevision != ticket.revision ||
                selected.serverAssociated || state.screen != PostDraftScreen.EDITOR)) removal = null
    }
    if (leaveUnsavedText) AlertDialog(onDismissRequest = { leaveUnsavedText = false },
        title = { Text("Keep editing?") }, text = { Text("Your latest caption and description have not been saved. Your last saved draft and photos will stay on this device.") },
        confirmButton = { TextButton(onClick = { leaveUnsavedText = false }) { Text("Keep editing") } },
        dismissButton = { TextButton(onClick = { leaveUnsavedText = false; back(discardBufferedText = true) }) { Text("Leave without saving") } })
    photoPanel?.let { panel ->
        BlueprintPhotoUploadFlow(panel, state) { back() }
        return
    }
    if (capture != null && RetainedBlueprintLocalPhotoCapture(controller, state, localPhotoOwnerId, capture) { back() }) return
    if (capture != null && RetainedBlueprintLocalPhotoEditor(controller, state, localPhotoOwnerId, capture, localPhotoText, scope,
            onPhotoUpload = if (photoUploadConfigured) ::openPhotoUpload else null,
            onEditChoices = if (onEditChoices != null) ::openChoices else null,
            onReviewPublication = if (onReviewPublication != null) ::openPhotoPublication else null,
            onRemoveUploadedPhoto = ::reviewUploadedPhotoRemoval,
            actionsAvailable = { uploadedPhotoRemoval == null && photoPanel == null }) { back() }) return
    val scroll = key(state.screen, selected?.clientDraftId) { rememberScrollState() }
    FeedMeTheme {
        Box(Modifier.fillMaxSize().background(FeedMeColors.Paper).safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(scroll)
            .padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.Center) {
                TextButton(onClick = { back() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("← Back") }
                Text("FeedMe", style = MaterialTheme.typography.titleLarge)
                Pill("DRAFTS", FeedMeColors.Lime)
            }
            if (!reviewedDraftMutationControlsAllowed(currentFormatOnly, state.journalFormat)) {
                PostReviewSection(if (state.journalFormat == PostDraftJournalFormat.LEGACY) "Your older drafts are kept" else "Checking draft format",
                    if (state.journalFormat == PostDraftJournalFormat.LEGACY)
                        "This preview can show older drafts, but cannot edit, Save, discard or upgrade them. Nothing is changed by opening this page. Back remains available."
                    else "Editing stays unavailable until an actual draft read identifies the format. No format, Save or publication is inferred from this view.")
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                draftHeadsUpText(state.issue, state.pending != null)?.let { InfoCard("A quick heads-up", it) }
                state.failureReason?.let { PostReviewSection("Draft action unavailable", "No success was inferred. Your retained data has not been replaced.") }
                state.pending?.let {
                    val warning = draftPendingMessage(it.operationId, it.phase, it.issue, it.finalizationRequired, it.canDiscardUnsent)
                    PostReviewSection(warning.title, warning.detail)
                    FeedMeDetails("Retained action details") {
                        PostReviewValue("Action", it.operationId); PostReviewValue("Phase", it.phase)
                        PostReviewValue("Request ID", it.commandId); PostReviewValue("Attempts", it.attempts.toString())
                    }
                }
                state.reviewedAllocation?.let { PostReviewSection("An unresolved private Save is retained",
                    "Its ${it.phase.name} state is unchanged. This read-only preview cannot abandon, retry or confirm it.") }
                state.publicationHold?.let { PostReviewSection("An earlier publication is retained",
                    "The original and any newer local changes remain separate. Opening this read-only page does not confirm either.") }
                if (state.phase != PostDraftPhase.UNAVAILABLE && (hasRetainedPublication || state.publicationHold != null))
                    onOpenPublications?.let { readHistory -> DraftSecondary("Review retained publication", enabled, readHistory) }
                state.unsubmittedRemainders.forEach { PostReviewSection("Newer changes were not submitted",
                    "Retained newer revision ${it.newerLocalRevision} remains separate from the closed original. It is not a new editable draft.") }
                val shown = selected?.let(::listOf) ?: state.localDrafts
                shown.forEach { local ->
                    PostReviewSection("Retained draft") {
                        PostReviewValue("Caption", exactReviewText(kotlinx.serialization.json.JsonPrimitive(local.caption)))
                        PostReviewValue("Image description", local.altText?.let { exactReviewText(kotlinx.serialization.json.JsonPrimitive(it)) } ?: "Not included")
                        local.server?.let {
                            PostContentReview(it.document, "Associated private server draft — historical")
                            PostExactEvidence("Full associated draft details", it.document)
                        }
                        if (selected == null) DraftSecondary("Open retained draft", enabled) {
                            scope.launch { controller.openLocal(local.clientDraftId) }
                        }
                    }
                }
                if (shown.isEmpty()) Text("No retained local draft is shown.", style = MaterialTheme.typography.bodyMedium)
                state.remoteItems.forEach { PostContentReview(it.document, "Previously loaded server draft — historical") }
            } else {
            DraftIntro(state.screen == PostDraftScreen.EDITOR, onReviewPublication != null)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            draftHeadsUpText(state.issue, state.pending != null)?.let { InfoCard("A quick heads-up", it) }
            if (state.failureReason != null && state.issue == PostDraftIssue.NONE)
                InfoCard("Action not completed", "Try the original action again when available. Your text and any uncertain request are not silently replaced.")
            state.reviewedAllocation?.let { allocation ->
                val detail = when (allocation.phase) {
                    ReviewedDraftAllocationPhase.ID_PROVIDER_UNRESOLVED -> "The request-ID provider has not returned an original. This is not an attempted server Save. Back does not abandon it."
                    ReviewedDraftAllocationPhase.ABANDONED_WAITING_FOR_PROVIDER -> "This unreturned request was fenced. The provider must settle before another allocation can start; a late return cannot send it."
                    ReviewedDraftAllocationPhase.ORIGINAL_REGISTRATION_UNRESOLVED -> "The exact original is retained, but registration is not confirmed. Review that original; do not create a replacement."
                }
                PostReviewSection("Private Save allocation", detail) {
                    allocation.abandonToken?.let { token ->
                        DraftSecondary("Review unreturned request", state.phase != PostDraftPhase.UNAVAILABLE) { if (writableNow()) abandon = token }
                    }
                    if (allocation.phase == ReviewedDraftAllocationPhase.ORIGINAL_REGISTRATION_UNRESOLVED)
                        DraftSecondary("Review original Save", enabled) { mutate { controller.prepareReviewedOriginalRetry() } }
                }
            }
            state.publicationHold?.let { hold ->
                PostReviewSection("An earlier publication is retained", if (hold.hasNewerLocalChanges)
                    "Its original and your newer local changes are separate. Nothing here replaces the original or confirms publication."
                    else "The retained publication has not been confirmed by opening this draft.") {
                    FeedMeDetails("Earlier publication version details") { PostReviewValue("Reviewed local revision", hold.reviewedLocalRevision.toString()) }
                    onOpenPublications?.let { open -> DraftSecondary("Review retained publication", enabled, open) }
                }
            }
            if (hasRetainedPublication && state.publicationHold == null)
                PostReviewSection("Publication history is retained", "A publication original or result is retained separately from this draft view. Opening it does not send or acknowledge it.") {
                    onOpenPublications?.let { open -> DraftSecondary("Review retained publication", enabled, open) }
                }
            state.unsubmittedRemainders.forEach { remainder ->
                PostReviewSection("Newer changes were not submitted", "These changes are retained separately from the closed original. They are not an editable new draft. Full remainder review and retain-as-new are not connected here.") {
                    FeedMeDetails("Retained change details") {
                        PostReviewValue("Original draft", remainder.closedClientDraftId)
                        PostReviewValue("Reviewed revision", remainder.reviewedLocalRevision.toString())
                        PostReviewValue("Retained newer revision", remainder.newerLocalRevision.toString())
                    }
                }
            }
            state.pending?.takeIf { state.reviewedSave == null && state.reviewedRetry == null }?.let { pending ->
                val message = draftPendingMessage(pending.operationId, pending.phase, pending.issue,
                    pending.finalizationRequired, pending.canDiscardUnsent)
                Surface(color = FeedMeColors.SoftBlue, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(message.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        Text(message.detail, style = MaterialTheme.typography.bodyMedium)
                        if (pending.canRetry) Primary(if (pending.requiresReviewedRetry) "Review original Save" else "Retry original action", enabled) {
                            mutate { if (pending.requiresReviewedRetry) controller.prepareReviewedOriginalRetry() else controller.retryOriginal() }
                        }
                        if (pending.canDiscardUnsent) DraftSecondary("Cancel unsent server action", enabled) { mutate { controller.discardUnsent() } }
                    }
                }
            }
            when {
                state.reviewedSave != null -> state.reviewedSave?.let { review ->
                    key(review.token) { ReviewedPrivateSaveReview(review, enabled) { mutate { controller.confirmReviewedSave(review.token) } } }
                }
                state.reviewedRetry != null -> state.reviewedRetry?.let { review ->
                    key(review.token) { ReviewedPrivateOriginalReview(review, enabled) { mutate { controller.confirmReviewedOriginalRetry(review.token) } } }
                }
                else -> when (state.screen) {
                PostDraftScreen.EDITOR -> {
                    if (selected == null) InfoCard("Draft unavailable", "There is no eligible draft to show. Back remains available.")
                    else {
                        selected.recipeSourceSuggestion?.let { source ->
                            val savedSource = source.sourceKind == DraftRecipeSourceKind.SAVED_RECIPE
                            PostReviewSection(if (savedSource) "Saved recipe reference retained" else "Recipe reference retained",
                                if (savedSource) "Open recipe and sharing choices, then More → Review saved recipe before attaching it. Nothing was attached or posted automatically."
                                else "Open recipe and sharing choices, then More to review this meal before attaching it. Nothing was attached or posted automatically.")
                        }
                        if (selected.uploadedPhotos.isNotEmpty()) {
                            PostReviewSection("Uploaded photos in this draft",
                                "Removing a selection does not delete stored bytes or change a server draft or existing post.")
                            selected.uploadedPhotos.forEachIndexed { index, photo ->
                                DraftSecondary("Remove uploaded photo ${index + 1} from draft",
                                    removablePhotoCurrent(state, selected, photo.assetId)) {
                                    reviewUploadedPhotoRemoval(selected, photo.assetId)
                                }
                            }
                        }
                        if (photoUploadConfigured && selected.localPhotos.isNotEmpty()) {
                            PostReviewSection("Photo upload and status", "Inspect the retained original before continuing an upload or cancellation. This does not publish your draft.")
                            selected.localPhotos.forEachIndexed { index, photo ->
                                DraftSecondary("Photo ${index + 1}: upload / status", enabled && localPhotoText?.dirty != true && localPhotoText?.saving != true) {
                                    openPhotoUpload(selected, photo.assetId, null)
                                }
                            }
                        }
                        if (state.journalFormat == PostDraftJournalFormat.CURRENT)
                            DraftPhotoControls(controller, selected, enabled && editable && selected.localAcknowledged &&
                                state.pending == null && state.reviewedAllocation == null && !selected.publicationHeld)
                        Text("The little story", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        if (textError != null) Text(textError, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        OutlinedTextField(value = selected.caption, onValueChange = { text -> mutate { controller.editCaption(selected.clientDraftId, text) } },
                            enabled = editable, label = { Text("What did you make?") }, placeholder = { Text("Dinner happened. That's a win.") },
                            shape = RoundedCornerShape(20.dp), colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.White, unfocusedContainerColor = Color.White),
                            isError = textError != null, minLines = 4, modifier = Modifier.fillMaxWidth().semantics {
                                if (textError != null) error(textError)
                            })
                        OutlinedTextField(value = selected.altText.orEmpty(), onValueChange = { text -> mutate {
                            controller.editAltText(selected.clientDraftId, text)
                        } }, enabled = editable, isError = textError != null, label = { Text("Image description (optional)") },
                            supportingText = { Text("Describe the meal for someone who cannot see the photo.") },
                            shape = RoundedCornerShape(20.dp), colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.White, unfocusedContainerColor = Color.White),
                            minLines = 2, modifier = Modifier.fillMaxWidth().semantics { if (textError != null) error(textError) })
                        Surface(color = Color.White, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text("Where this lives", style = MaterialTheme.typography.titleMedium)
                                Text(if (selected.requiresReviewedSave) reviewedDraftAcknowledgementText(selected.localAcknowledged,
                                    state.serverAcknowledged, state.pending?.finalizationRequired == true) else draftAcknowledgementText(selected.localAcknowledged, state.serverAcknowledged,
                                    selected.textMatchesServer, state.pending?.finalizationRequired == true,
                                    selected.serverAssociated, selected.server != null), style = MaterialTheme.typography.bodyMedium, color = FeedMeColors.Muted)
                            }
                        }
                        if (!selected.localAcknowledged && editable) DraftSecondary("Retry keeping this text on device", enabled) {
                            mutate { controller.editCaption(selected.clientDraftId, selected.caption) }
                        }
                        if (restoredRetentionReviewOffered(onReviewRestoredLocalRetention != null, state.screen,
                                selected.localAcknowledged)) {
                            PostReviewSection("Changes restored after reopening?",
                                "Review the complete retained changes before keeping them on this device. This is a separate recovery action, not a text edit, private Save or publication. An unresolved edit from the current session may need its existing retry instead.") {
                                DraftSecondary("Review restored local changes", enabled) {
                                    if (writableNow()) onReviewRestoredLocalRetention?.invoke(selected.clientDraftId, selected.localRevision)
                                }
                            }
                        }
                        onEditChoices?.let {
                            DraftSecondary("Review recipe, audience & saves", enabled && editable && selected.localAcknowledged &&
                                state.pending == null && state.reviewedAllocation == null && !selected.publicationHeld &&
                                localPhotoText?.dirty != true && localPhotoText?.saving != true) {
                                openChoices(selected)
                            }
                        }
                        val canSave = enabled && editable && selected.localAcknowledged && state.pending == null &&
                            state.reviewedAllocation == null && !selected.publicationHeld && selected.localPhotos.isEmpty()
                        if (selected.localPhotos.isNotEmpty()) PostReviewSection("Local photo copies",
                            "These copies are retained in your draft. Their presence does not establish upload or publication status. Upload and cancellation require their separate review; removing a local copy does not confirm remote deletion.")
                        if (localPhotoOwnerId != null) PostReviewSection("Local draft retained",
                            "This editor keeps local text and photo copies. Inspect photo upload/status when available to review any retained upload. Server Save and publishing are not connected in this release.")
                        else when (draftSaveUiRoute(selected.requiresReviewedSave, onReviewPrivateSave != null)) {
                            DraftSaveUiRoute.LEGACY_SAVE -> Primary("Save privately to server", canSave) { mutate { controller.saveExplicitly() } }
                            DraftSaveUiRoute.REVIEWED_SAVE -> Primary("Review private Save", canSave) {
                                mutate { onReviewPrivateSave?.invoke(selected.clientDraftId, selected.localRevision) }
                            }
                            DraftSaveUiRoute.REVIEW_NOT_CONNECTED -> PostReviewSection("A full private-Save review is required",
                                "This draft includes more than text. The reviewed route is not connected here, so the text-only Save cannot submit or drop those choices.")
                        }
                        onReviewPublication?.let { reviewPublication ->
                            DraftSecondary("Review publication", enabled && selected.localAcknowledged && state.pending == null &&
                                state.reviewedAllocation == null && !selected.publicationHeld && selected.localPhotos.isEmpty()) {
                                mutate { reviewPublication(selected.clientDraftId, selected.localRevision) }
                            }
                        }
                        if (selected.serverAssociated) {
                            Text("Server status: ${selected.server?.status ?: "content unavailable"}", style = MaterialTheme.typography.bodySmall)
                            DraftSecondary("Refresh server draft", enabled) { mutate { controller.refreshRemote() } }
                            DraftSecondary("Review server discard", enabled && state.pending == null &&
                                    selected.localPhotos.isEmpty() &&
                                    canReviewServerDraftDiscard(selected.serverAssociated, selected.server?.status)) {
                                mutate { controller.prepareServerDiscard() }
                            }
                        }
                        if (!selected.serverAssociated) TextButton(enabled = enabled && state.pending == null && selected.localPhotos.isEmpty(),
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = { if (writableNow()) removal = LocalDraftRemoval(selected.clientDraftId, selected.localRevision) }) { Text("Remove local draft") }
                        Text(if (onReviewPublication == null) "Back keeps the draft; it does not cancel an upload already confirmed. Local Save never posts to Today or My Plate. Photo upload/status is a separate action when configured; publication is not connected here."
                            else "Back keeps the draft; it does not cancel an upload already confirmed. Private Save never publishes. Audience, attachments and recipe-save permissions are shown in the separate full review; photo upload/status is separate when configured.",
                            style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
                    }
                }
                PostDraftScreen.LOCAL_LIST, PostDraftScreen.REMOTE_LIST -> {
                    Primary("Start a fresh draft", enabled) { mutate { controller.newLocalDraft() } }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = state.screen == PostDraftScreen.LOCAL_LIST, enabled = enabled,
                            onClick = { mutate { controller.restoreLocal() } }, modifier = Modifier.heightIn(min = 48.dp),
                            label = { Text("On this device") }, shape = RoundedCornerShape(14.dp),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = FeedMeColors.Lime, selectedLabelColor = FeedMeColors.Ink))
                        FilterChip(selected = state.screen == PostDraftScreen.REMOTE_LIST, enabled = enabled && localPhotoOwnerId == null,
                            onClick = { mutate { controller.listRemote() } }, modifier = Modifier.heightIn(min = 48.dp),
                            label = { Text("Load server drafts") }, shape = RoundedCornerShape(14.dp),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = FeedMeColors.Lime, selectedLabelColor = FeedMeColors.Ink))
                    }
                    if (state.screen == PostDraftScreen.LOCAL_LIST) {
                        Text("Private on this device", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        if (state.localDrafts.isEmpty()) DraftEmpty("Blank page. Zero pressure.", "Start with a caption. Keep it private for as long as you need. Drafts are not automatically removed here.")
                        state.localDrafts.forEach { local ->
                            DraftListCard(local.caption.ifBlank { "Untitled kitchen moment" },
                                if (local.localAcknowledged) "Retained on device" else "Local confirmation needed", enabled) {
                                scope.launch { controller.openLocal(local.clientDraftId) }
                            }
                        }
                    } else {
                        Text("Server drafts", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        Text("One page at a time. Loading a page does not download or save its drafts.", style = MaterialTheme.typography.bodySmall)
                        if (state.remoteItems.isEmpty()) DraftEmpty("Nothing on this page", "Other pages or local drafts may still exist.")
                        state.remoteItems.forEach { remote ->
                            DraftListCard(remote.caption.ifBlank { "Untitled kitchen moment" }, "${remote.status} · Open server draft", enabled) {
                                mutate { controller.refreshRemote(remote.id) }
                            }
                        }
                        if (state.hasMore) DraftSecondary("Next drafts page", enabled) { mutate { controller.nextRemotePage() } }
                    }
                }
                PostDraftScreen.HIDDEN -> Unit
                }
            }
            }
        }
        }
        val abandonToken = abandon
        if (writableNow() && abandonToken != null && state.reviewedAllocation?.abandonToken === abandonToken && state.phase != PostDraftPhase.UNAVAILABLE)
            PostAbandonDialog("Abandon this unreturned private Save request?", onDismiss = { abandon = null }) {
                mutate { controller.abandonUnreturnedReviewedAllocation(abandonToken) }; abandon = null
            }
        val ticket = state.discardConfirmation
        if (writableNow() && ticket != null && selected != null && state.phase != PostDraftPhase.UNAVAILABLE) AlertDialog(
            onDismissRequest = { scope.launch { controller.dismissServerDiscard() } }, title = { Text("Discard this server draft?") },
            text = { Text("Confirming discards this exact draft version and removes its local draft. Unused uploads may be queued for cleanup; this is not proof they have already been erased. Nothing is posted.") },
            confirmButton = { TextButton(enabled = enabled, onClick = { mutate { controller.confirmServerDiscard(ticket) } }) { Text("Confirm server discard") } },
            dismissButton = { TextButton(onClick = { scope.launch { controller.dismissServerDiscard() } }) { Text("Keep drafting") } })
        val localTicket = removal
        if (writableNow() && localTicket != null && selected?.clientDraftId == localTicket.clientId && selected.localRevision == localTicket.revision &&
            !selected.serverAssociated && state.pending == null && state.phase != PostDraftPhase.UNAVAILABLE) AlertDialog(
            onDismissRequest = { removal = null }, title = { Text("Remove this local draft?") },
            text = { Text("This removes the draft from this device. It does not discard a server draft or cancel an attempted request.") },
            confirmButton = { TextButton(enabled = enabled, onClick = {
                val current = controller.states.value
                val currentLocal = current.selected
                if (currentLocal?.clientDraftId == localTicket.clientId && currentLocal.localRevision == localTicket.revision &&
                    !currentLocal.serverAssociated && current.pending == null) mutate { controller.discardLocal(localTicket.clientId, localTicket.revision) }
                removal = null
            }) { Text("Confirm local removal") } }, dismissButton = { TextButton(onClick = { removal = null }) { Text("Keep draft") } })
    }
}

@Composable
private fun DraftSecondary(label: String, enabled: Boolean, action: () -> Unit) {
    OutlinedButton(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        border = BorderStroke(1.dp, FeedMeColors.Line), colors = ButtonDefaults.outlinedButtonColors(contentColor = FeedMeColors.Ink)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun DraftListCard(title: String, status: String, enabled: Boolean, open: () -> Unit) {
    Card(onClick = open, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
        shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, FeedMeColors.Line)) {
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(status, style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
            }
            Text("↗", style = MaterialTheme.typography.titleLarge, color = FeedMeColors.Blue)
        }
    }
}

@Composable
private fun DraftEmpty(title: String, detail: String) {
    Surface(color = FeedMeColors.SoftLime, shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("YOURS, UNTIL YOU SAY OTHERWISE.", style = MaterialTheme.typography.labelMedium)
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
