package com.feedme.app.mealflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feedme.app.blueprint.*
import com.feedme.core.ports.PortResult
import com.feedme.mealflow.social.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The parent draft flow retains this panel across controller pending/recovery changes.
 * Constructing or rendering it performs no inspection, upload, polling or persistence. */
internal class BlueprintPhotoUploadPanel(
    private val controller: PostDraftController,
    private val scope: CoroutineScope,
    val ownerId: String,
    val draftId: String,
    val revision: Long,
    val assetId: String,
    val preview: BlueprintAuthoringPreview.Actual?,
    private val attached: () -> Boolean,
    private val onAttached: (PostDraftState) -> Unit,
) {
    val controls = controller.photoUploads
    var view by mutableStateOf<PostDraftPhotoUploadView?>(null)
        private set
    var review by mutableStateOf<PreparedPostDraftPhotoAction?>(null)
        private set
    var observation by mutableStateOf<PostDraftPhotoStatusView?>(null)
        private set
    var initialUseStatus by mutableStateOf<PostDraftPhotoStatusView?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    fun current(): Boolean {
        val state = controller.states.value
        return attached() && state.phase != PostDraftPhase.UNAVAILABLE && state.screen == PostDraftScreen.EDITOR &&
            state.selected?.let { it.clientDraftId == draftId && it.localRevision == revision &&
                it.localPhotos.any { photo -> photo.assetId == assetId } } == true
    }
    fun selected(): LocalPostDraft? = controller.states.value.selected?.takeIf { current() }
    fun currentView() = view?.takeIf { current() && controls.isCurrent(it) }
    fun currentObservation() = observation?.takeIf { current() && controls.isCurrent(it) }
    fun dismissReview() { review = null }
    fun dismissInitialChoices() { initialUseStatus = null }

    private fun act(block: suspend () -> Unit) {
        if (busy || !current() || !controls.configured) return
        busy = true; notice = null
        scope.launch {
            try { if (current()) block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current()) notice = "This action could not be confirmed. Inspect the retained upload before continuing." }
            finally { busy = false }
        }
    }

    /** Called only by explicit entry or Inspect; never by composition or a timer. */
    fun inspect() = act {
        review = null; observation = null; initialUseStatus = null
        val expected = controller.states.value
        when (val result = controls.inspect(expected, assetId)) {
            is PortResult.Value -> if (current() && controls.isCurrent(result.value)) view = result.value
            is PortResult.Failure -> if (current()) {
                view = null; notice = "The retained upload could not be inspected. Nothing was sent by this inspection."
            }
        }
    }

    fun prepare(kind: PostDraftPhotoActionKind) {
        val exact = currentView() ?: return
        val allowed = when (kind) {
            PostDraftPhotoActionKind.UPLOAD, PostDraftPhotoActionKind.RECONCILE -> exact.canUploadOrReconcile
            PostDraftPhotoActionKind.CANCEL -> exact.canCancel
            PostDraftPhotoActionKind.RETRY_CANCELLATION -> exact.canReviewCancellationRetry
            PostDraftPhotoActionKind.USE_UPLOADED_PHOTO -> false
        }
        if (!allowed) return
        act {
            if (!controls.isCurrent(exact)) return@act
            val result = when (kind) {
                PostDraftPhotoActionKind.UPLOAD, PostDraftPhotoActionKind.RECONCILE -> controls.prepareUpload(exact)
                PostDraftPhotoActionKind.CANCEL -> controls.prepareCancel(exact)
                PostDraftPhotoActionKind.RETRY_CANCELLATION -> controls.prepareCancellationRetry(exact)
                PostDraftPhotoActionKind.USE_UPLOADED_PHOTO -> return@act
            }
            when (result) {
                is PortResult.Value -> if (current() && controls.isCurrent(result.value)) review = result.value
                is PortResult.Failure -> if (current()) notice = "This review is unavailable. Inspect the retained upload again; no different request was sent."
            }
        }
    }

    fun confirm(exact: PreparedPostDraftPhotoAction) {
        if (review !== exact || !controls.isCurrent(exact)) return
        act {
            if (review !== exact || !controls.isCurrent(exact)) return@act
            review = null; observation = null
            val result = controls.confirm(exact)
            if (exact.kind == PostDraftPhotoActionKind.USE_UPLOADED_PHOTO && result is PortResult.Value && attached() &&
                controller.states.value === result.value && result.value.screen == PostDraftScreen.EDITOR &&
                result.value.phase == PostDraftPhase.READY && result.value.pending == null &&
                result.value.selected?.let { it.clientDraftId == draftId && it.localRevision == revision + 1 &&
                    it.localAcknowledged && it.uploadedPhotos.any { photo -> photo.assetId == assetId } } == true) {
                onAttached(result.value)
                return@act
            }
            if (current()) {
                view = null
                notice = if (result is PortResult.Value)
                    "The action returned. Inspect the retained upload to see its result. No publication is confirmed."
                else "The action is not confirmed. Inspect its retained original before continuing. Do not upload a replacement."
            }
        }
    }

    fun reviewUse(status: PostDraftPhotoStatusView) {
        if (busy || !current() || !controls.isCurrent(status) || !status.canUseUploadedPhoto) return
        if (status.requiresInitialChoices) initialUseStatus = status else prepareUse(status, null)
    }

    fun prepareUse(status: PostDraftPhotoStatusView, choices: PostDraftPhotoUseChoices?) {
        if (!current() || !controls.isCurrent(status) || !status.canUseUploadedPhoto ||
            status.requiresInitialChoices != (choices != null)) return
        if (choices != null && initialUseStatus !== status) return
        initialUseStatus = null
        act {
            if (!controls.isCurrent(status)) return@act
            when (val result = controls.prepareUseUploadedPhoto(status, choices)) {
                is PortResult.Value -> if (current() && controls.isCurrent(result.value)) review = result.value
                is PortResult.Failure -> if (current()) {
                    observation = null; view = null
                    notice = "This photo could not be reviewed for use. Inspect the original upload and check current server status again. No attachment or publication is confirmed."
                }
            }
        }
    }

    fun observe() {
        val exact = currentView()?.takeIf { it.canObserveStatus } ?: return
        act {
            if (!controls.isCurrent(exact)) return@act
            when (val result = controls.observeStatus(exact)) {
                is PortResult.Value -> if (current() && controls.isCurrent(result.value)) observation = result.value
                is PortResult.Failure -> if (current()) {
                    observation = null; notice = "Server media status could not be confirmed. No automatic retry or publication was started."
                }
            }
        }
    }
}

@Composable
internal fun BlueprintPhotoUploadFlow(panel: BlueprintPhotoUploadPanel, state: PostDraftState, onBack: () -> Unit) {
    var tools by remember(panel) { mutableStateOf(false) }
    val current = panel.current() && state.screen == PostDraftScreen.EDITOR && state.phase != PostDraftPhase.UNAVAILABLE
    val initial = panel.initialUseStatus?.takeIf { current && panel.controls.isCurrent(it) }
    if (initial != null) {
        var onlyMe by remember(panel, initial) { mutableStateOf(false) }
        var plate by remember(panel, initial) { mutableStateOf<Boolean?>(null) }
        var noSaves by remember(panel, initial) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = panel::dismissInitialChoices, title = { Text("Initial draft choices") }, text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This text-only draft has no sharing choices yet. Choose them explicitly before reviewing the uploaded photo. Nothing is published or sent by these choices.")
                Row { RadioButton(onlyMe, onClick = { onlyMe = true }); Text("Only me") }
                Text("You can change the audience later in the existing sharing review.")
                Row { RadioButton(plate == true, onClick = { plate = true }); Text("Keep on My Plate") }
                Row { RadioButton(plate == false, onClick = { plate = false }); Text("Do not keep on My Plate") }
                Row { RadioButton(noSaves, onClick = { noSaves = true }); Text("Do not allow recipe saves") }
                Text("No recipe is attached to this text-only draft, so recipe saves cannot be enabled. The later Save or Publish review still loads its current disclosure.")
            }
        }, confirmButton = {
            TextButton(enabled = onlyMe && plate != null && noSaves && !panel.busy, onClick = {
                val keep = plate
                if (onlyMe && keep != null && noSaves && panel.current() && panel.initialUseStatus === initial && panel.controls.isCurrent(initial))
                    panel.prepareUse(initial, PostDraftPhotoUseChoices(PublicationAudience.OnlyYou, keep, false))
            }) { Text("Review using photo") }
        }, dismissButton = { TextButton(onClick = panel::dismissInitialChoices) { Text("Not now") } })
    }
    val review = panel.review?.takeIf { current && panel.controls.isCurrent(it) }
    if (review != null) {
        val label = when (review.kind) {
            PostDraftPhotoActionKind.UPLOAD -> "Upload this photo"
            PostDraftPhotoActionKind.RECONCILE -> "Continue the exact upload"
            PostDraftPhotoActionKind.CANCEL -> "Cancel this photo upload"
            PostDraftPhotoActionKind.RETRY_CANCELLATION -> "Retry this cancellation"
            PostDraftPhotoActionKind.USE_UPLOADED_PHOTO -> "Use this uploaded photo"
        }
        val retryFactsPresent = review.kind != PostDraftPhotoActionKind.RETRY_CANCELLATION ||
            (review.previousCancellationVersion != null && review.reviewedMediaId != null &&
                review.reviewedMediaVersion != null && review.reviewedMediaStatus != null)
        val shown = BlueprintConfirmationState(actionLabel = label,
            affectedSummary = "Selected photo in this private draft" +
                (review.view.byteCount?.let { " · $it bytes" } ?: ""),
            consequences = buildList {
                add(when (review.kind) {
                PostDraftPhotoActionKind.UPLOAD -> "The selected photo bytes leave this device for FeedMe’s private storage. Uploading does not publish or share the photo with other users."
                PostDraftPhotoActionKind.RECONCILE -> "Continue only the retained original. A photo PUT with an unknown outcome is not repeated."
                PostDraftPhotoActionKind.CANCEL -> "Cancel only this retained upload. Your local draft remains. A server tombstone is not proof of physical storage deletion."
                PostDraftPhotoActionKind.RETRY_CANCELLATION -> "Review a new conditional cancellation for this same media. The previous cancellation attempt remains unresolved. This is not a replacement photo upload."
                PostDraftPhotoActionKind.USE_UPLOADED_PHOTO -> "Stage this exact ready media ID in this draft on this device. Preserve the encrypted local photo and upload history. This does not save the draft to the server, select a recipe, publish or share it."
                })
                if (review.kind == PostDraftPhotoActionKind.RETRY_CANCELLATION) {
                    review.previousCancellationVersion?.let { add("Previous cancellation version: $it.") }
                    review.reviewedMediaId?.let { add("Media selected by the new server observation: $it.") }
                    review.reviewedMediaVersion?.let { add("New conditional cancellation version: $it.") }
                    review.reviewedMediaStatus?.let { add("New server observation: ${photoMediaStatus(it)}.") }
                }
                if (review.kind == PostDraftPhotoActionKind.USE_UPLOADED_PHOTO) {
                    review.reviewedMediaId?.let { add("Ready photo observed for this exact upload: $it.") }
                    review.reviewedMediaVersion?.let { add("Current media version reviewed: $it.") }
                    review.initialChoices?.let {
                        add("Initial audience: Only me. " + (if (it.keepOnPlate) "Keep on My Plate." else "Do not keep on My Plate.") + " Recipe saves are not allowed.")
                        add("You can change these choices afterward in the existing sharing review. A current disclosure is still required for a later Save or Publish.")
                    } ?: add("All existing audience, retention, recipe, source and recipe-save choices stay unchanged.")
                }
                add("Audience, recipe permissions and publication are not granted by this action.")
                add("Cancel returns to status without sending this action.")
            },
            canConfirm = !panel.busy && retryFactsPresent, canCancel = !panel.busy, busy = panel.busy,
            status = panel.notice)
        BlueprintConfirmationScreen(shown, heading = "Review\nthis photo action.", confirmLabel = label,
            onConfirm = { observed ->
                if (observed === shown && shown.confirmEnabled && panel.current() && panel.review === review &&
                    panel.controls.isCurrent(review)) panel.confirm(review)
            }, onCancel = { observed -> if (observed === shown && shown.cancelEnabled) panel.dismissReview() })
        return
    }
    val view = panel.currentView()
    val observation = panel.currentObservation()
    val selected = panel.selected()
    val identity = BlueprintAuthoringIdentity(panel.ownerId, panel.draftId, panel.revision)
    val phase = when {
        observation?.status == PostDraftPhotoMediaStatus.REJECTED -> BlueprintPublishPhase.FAILED
        observation?.status == PostDraftPhotoMediaStatus.PROCESSING || view?.phase == PostDraftPhotoUploadViewPhase.PROCESSING -> BlueprintPublishPhase.PROCESSING
        view?.phase in setOf(PostDraftPhotoUploadViewPhase.PREPARE_ATTEMPTED, PostDraftPhotoUploadViewPhase.PUT_ATTEMPTED,
            PostDraftPhotoUploadViewPhase.COMPLETE_ATTEMPTED, PostDraftPhotoUploadViewPhase.CANCELLATION_REQUESTED,
            PostDraftPhotoUploadViewPhase.CANCELLATION_ATTEMPTED) -> BlueprintPublishPhase.OUTCOME_UNKNOWN
        else -> BlueprintPublishPhase.REVIEW
    }
    val model = BlueprintPublishStatusState(
        context = BlueprintAuthoringContext(identity, BlueprintAuthoringMode.DRAFT, current),
        media = panel.preview?.takeIf { current && it.identity == identity && it.mediaId == panel.assetId },
        caption = selected?.caption.orEmpty(), phase = phase,
        // No published receipt, readiness, invented percentage or server-media identity.
        mediaStatus = observation?.let { "Server observation: ${photoMediaStatus(it.status)}. Upload is not publication." }
            ?: view?.let { photoUploadPhase(it.phase) } ?: "Inspect the retained upload for its current state.",
        audienceLabel = "Not selected for publication", savePermissionLabel = "Not granted here",
        retentionLabel = "Private local draft; nothing is published",
        controls = BlueprintAuthoringControls(actions = buildSet {
            add(BlueprintAuthoringAction.PUBLISH_BACK)
            if (current) {
                add(BlueprintAuthoringAction.LEAVE_WITH_DRAFT)
                if (!panel.busy) {
                    add(BlueprintAuthoringAction.EDIT_BEFORE_SHARING)
                    if (view?.canUploadOrReconcile == true) add(BlueprintAuthoringAction.RETRY_UPLOAD)
                    if (view?.canObserveStatus == true) add(BlueprintAuthoringAction.CHECK_MEDIA)
                }
            }
        }, moreEnabled = current && !panel.busy && initial == null),
        status = if (!current) "This draft or account changed. Its photo details are hidden; go back."
            else if (panel.busy) "This photo action is in progress. Back only leaves this screen; it does not cancel an already-confirmed upload."
            else panel.notice ?: "Use More to inspect, review upload, check server status or explicitly use a ready photo. Save and Publish remain separate reviews.")
    BlueprintPublishStatusScreen(model, onAction = { observed, action ->
        if (observed === model && model.allows(action)) when (action) {
            BlueprintAuthoringAction.PUBLISH_BACK, BlueprintAuthoringAction.LEAVE_WITH_DRAFT,
            BlueprintAuthoringAction.EDIT_BEFORE_SHARING -> onBack()
            BlueprintAuthoringAction.RETRY_UPLOAD -> if (panel.current()) panel.prepare(PostDraftPhotoActionKind.RECONCILE)
            BlueprintAuthoringAction.CHECK_MEDIA -> if (panel.current()) panel.observe()
            else -> Unit
        }
    }, onMore = { observed -> if (observed === model && model.controls.moreEnabled && panel.current()) tools = true })
    if (tools) AlertDialog(onDismissRequest = { tools = false }, title = { Text("Photo upload and status") },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            Text("Every upload or cancellation requires its own review. Server status is checked only when you ask.")
            TextButton(enabled = current && !panel.busy, onClick = { tools = false; panel.inspect() }) { Text("Inspect retained upload") }
            if (view?.canUploadOrReconcile == true) TextButton(enabled = !panel.busy, onClick = {
                tools = false; panel.prepare(PostDraftPhotoActionKind.UPLOAD)
            }) { Text(if (view.phase == PostDraftPhotoUploadViewPhase.LOCAL_ONLY) "Review photo upload" else "Review exact original") }
            if (view?.canObserveStatus == true) TextButton(enabled = !panel.busy, onClick = { tools = false; panel.observe() }) { Text("Check server media status") }
            if (observation?.canUseUploadedPhoto == true) TextButton(enabled = !panel.busy, onClick = {
                if (panel.current() && panel.controls.isCurrent(observation)) { tools = false; panel.reviewUse(observation) }
            }) { Text("Use this uploaded photo") }
            if (view?.canCancel == true) TextButton(enabled = !panel.busy, onClick = {
                tools = false; panel.prepare(PostDraftPhotoActionKind.CANCEL)
            }) { Text("Review cancellation") }
            if (view?.canReviewCancellationRetry == true) TextButton(enabled = !panel.busy, onClick = {
                tools = false; panel.prepare(PostDraftPhotoActionKind.RETRY_CANCELLATION)
            }) { Text("Review cancellation retry") }
        } }, confirmButton = { TextButton(onClick = { tools = false }) { Text("Close") } })
}

private fun photoMediaStatus(status: PostDraftPhotoMediaStatus): String = when (status) {
    PostDraftPhotoMediaStatus.AWAITING_UPLOAD -> "waiting for photo"
    PostDraftPhotoMediaStatus.PROCESSING -> "processing"
    PostDraftPhotoMediaStatus.READY -> "ready media; publication is still not authorized here"
    PostDraftPhotoMediaStatus.REJECTED -> "rejected"
    PostDraftPhotoMediaStatus.DELETED -> "deleted media record"
}

private fun photoUploadPhase(phase: PostDraftPhotoUploadViewPhase): String = when (phase) {
    PostDraftPhotoUploadViewPhase.NO_SELECTION -> "No photo is selected."
    PostDraftPhotoUploadViewPhase.LOCAL_ONLY -> "Photo retained on this device; upload has not started."
    PostDraftPhotoUploadViewPhase.RETAINED -> "Upload intent retained locally; not a server receipt."
    PostDraftPhotoUploadViewPhase.PREPARE_ATTEMPTED -> "Upload preparation attempted; reconcile this exact original."
    PostDraftPhotoUploadViewPhase.RESERVED -> "Server reservation observed; photo upload is not confirmed."
    PostDraftPhotoUploadViewPhase.PUT_ATTEMPTED -> "Photo PUT attempted; do not repeat it after an unknown outcome."
    PostDraftPhotoUploadViewPhase.COMPLETE_ATTEMPTED -> "Completion request attempted; inspect the exact original."
    PostDraftPhotoUploadViewPhase.PROCESSING -> "Server processing was observed; readiness and publication are not confirmed."
    PostDraftPhotoUploadViewPhase.CANCELLATION_REQUESTED -> "Cancellation retained; server cancellation is not confirmed."
    PostDraftPhotoUploadViewPhase.CANCELLATION_ATTEMPTED -> "Cancellation attempted; inspect the original before retrying."
    PostDraftPhotoUploadViewPhase.LOCAL_CANCELLED -> "Cancelled locally before preparation; no publication."
    PostDraftPhotoUploadViewPhase.REMOTE_TOMBSTONED -> "Server media tombstone observed; physical deletion is not confirmed here."
    PostDraftPhotoUploadViewPhase.READY_ATTACHED -> "Uploaded photo staged in this local draft. No Save or publication is confirmed."
}
