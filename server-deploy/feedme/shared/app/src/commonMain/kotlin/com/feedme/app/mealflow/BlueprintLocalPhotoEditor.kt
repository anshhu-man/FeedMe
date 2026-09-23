package com.feedme.app.mealflow

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import com.feedme.app.blueprint.*
import com.feedme.core.ports.PortResult
import com.feedme.mealflow.social.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal expect fun rememberBlueprintRetainedPhoto(controller: PostDraftController, selected: LocalPostDraft,
    identity: BlueprintAuthoringIdentity): BlueprintAuthoringPreview.Actual?

/** Owned above conditional editor/recovery UI; typing is memory-only until explicit Save. */
internal class BlueprintLocalPhotoTextBuffer(selected: LocalPostDraft) {
    val draftId = selected.clientDraftId
    var revision by mutableStateOf(selected.localRevision)
        private set
    private var retainedCaption by mutableStateOf(selected.caption)
    private var retainedAlt by mutableStateOf(selected.altText.orEmpty())
    var caption by mutableStateOf(selected.caption)
    var alt by mutableStateOf(selected.altText.orEmpty())
    var saving by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    val dirty get() = caption != retainedCaption || alt != retainedAlt

    fun accept(selected: LocalPostDraft) {
        if (selected.clientDraftId != draftId || !selected.localAcknowledged) return
        revision = selected.localRevision
        retainedCaption = selected.caption; retainedAlt = selected.altText.orEmpty()
        caption = retainedCaption; alt = retainedAlt
    }

    fun save(controller: PostDraftController, scope: CoroutineScope) {
        val observed = controller.states.value
        val selected = observed.selected ?: return
        if (saving || observed.busy || selected.clientDraftId != draftId || selected.localRevision != revision ||
            !selected.localAcknowledged || selected.serverAssociated || selected.publicationHeld) return
        val expectedRevision = revision
        val submittedCaption = caption; val submittedAlt = alt
        saving = true; message = null
        scope.launch {
            try {
                when (val result = controller.editLocalText(draftId, expectedRevision, submittedCaption, submittedAlt)) {
                    is PortResult.Failure -> message = "The text was not confirmed saved. Your typed text is still here."
                    is PortResult.Value -> {
                        val kept = result.value.selected
                        if (kept != null && kept.clientDraftId == draftId && kept.localAcknowledged &&
                            kept.caption == submittedCaption && kept.altText.orEmpty() == submittedAlt) {
                            accept(kept); message = "Draft saved privately on this device."
                        } else message = "Saving needs confirmation. Your typed text is still here."
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            finally { saving = false }
        }
    }
}

/** Original EDIT_MEDIA uses only the actual retained image and local editor operations.
 * A selected local asset is not a server media ID or upload/safety/publication approval. */
@Composable
internal fun RetainedBlueprintLocalPhotoEditor(controller: PostDraftController, state: PostDraftState,
    ownerId: String?, capture: BlueprintPhotoCaptureActions, text: BlueprintLocalPhotoTextBuffer?,
    scope: CoroutineScope,
    onPhotoUpload: ((LocalPostDraft, String, BlueprintAuthoringPreview.Actual?) -> Unit)? = null,
    onEditChoices: ((LocalPostDraft) -> Unit)? = null,
    onReviewPublication: ((LocalPostDraft) -> Unit)? = null,
    onRemoveUploadedPhoto: ((LocalPostDraft, String) -> Unit)? = null,
    actionsAvailable: () -> Boolean = { true },
    back: () -> Unit): Boolean {
    val selected = state.selected ?: return false
    if (text == null || text.draftId != selected.clientDraftId) return false
    val textValidation = text.message != null && state.phase == PostDraftPhase.ERROR && state.issue == PostDraftIssue.INVALID_INPUT
    if (ownerId.isNullOrBlank() || state.screen != PostDraftScreen.EDITOR ||
        state.journalFormat != PostDraftJournalFormat.CURRENT || (state.phase != PostDraftPhase.READY && !textValidation) ||
        (state.issue != PostDraftIssue.NONE && !textValidation) || (state.failureReason != null && !textValidation) || state.pending != null ||
        state.reviewedAllocation != null || state.publicationHold != null || state.reviewedSave != null ||
        state.reviewedRetry != null || state.unsubmittedRemainders.isNotEmpty() || state.discardConfirmation != null ||
        !selected.localAcknowledged || selected.serverAssociated || selected.publicationHeld ||
        (selected.localPhotos.isEmpty() && selected.uploadedPhotos.isEmpty())) return false
    val identity = BlueprintAuthoringIdentity(ownerId, selected.clientDraftId, selected.localRevision)
    val preview = rememberBlueprintRetainedPhoto(controller, selected, identity)
    // Borrow the outer flow scope: an edit's own PENDING state must not dispose/cancel
    // its coroutine merely because the established recovery UI temporarily takes over.
    var tools by remember(controller, selected.clientDraftId) { mutableStateOf(false) }
    val editable = !state.busy && !capture.working && !text.saving && text.revision == selected.localRevision
    fun current() = actionsAvailable() && controller.states.value === state
    val model = BlueprintEditMediaState(BlueprintAuthoringContext(identity, BlueprintAuthoringMode.DRAFT, current = true),
        media = preview, caption = text.caption, alt = text.alt,
        controls = BlueprintAuthoringControls(actions = buildSet {
            add(BlueprintAuthoringAction.EDIT_BACK)
            if (editable) add(BlueprintAuthoringAction.SAVE_DRAFT)
            if (editable && !text.dirty && onEditChoices != null) add(BlueprintAuthoringAction.EDIT_ATTACH)
            if (editable && !text.dirty && selected.localPhotos.isEmpty() && onReviewPublication != null)
                add(BlueprintAuthoringAction.REVIEW_PUBLISH)
        }, editableFields = if (editable) setOf("caption", "alt") else emptySet(), moreEnabled = editable),
        status = (if (textValidation) "Check your caption and image description, then save again. Your typed text is still here."
            else if (text.saving) "Saving this draft on your device…"
            else if (text.dirty) "Unsaved text. Tap Save draft to keep your caption and description on this device."
            else text.message ?: capture.message ?: if (selected.uploadedPhotos.isNotEmpty())
                "Uploaded photo staged in this draft on this device. Your retained preview is not publication. Review choices before a separate Save or Publish."
            else if (onEditChoices != null)
                "Local copy retained. Add the recipe to review source, audience and private-copy choices. Server Save and Publish are separate actions."
            else if (onPhotoUpload != null)
                "Local copy retained. More opens a separate photo-upload review. Publishing is not connected."
            else "Local copy retained. Upload controls are unavailable in this configuration; any earlier upload outcome is unchanged. Cropping and publishing are not connected here.") +
            (if (selected.recipeSourceSuggestion != null) "\n\nRecipe reference retained. In recipe choices, open More to review it before attaching." else "") +
            (if (preview != null && selected.uploadedPhotos.any { it.assetId == preview.mediaId })
                "\nPreview: a previously staged uploaded photo. Other local photos still need their own upload and use review."
            else ""))
    BlueprintEditMediaScreen(model, onAction = { observed, action ->
        if (observed === model && current() && model.allows(action)) when (action) {
            BlueprintAuthoringAction.EDIT_BACK -> back()
            BlueprintAuthoringAction.SAVE_DRAFT -> text.save(controller, scope)
            BlueprintAuthoringAction.EDIT_ATTACH -> if (!text.dirty) onEditChoices?.invoke(selected)
            BlueprintAuthoringAction.REVIEW_PUBLISH -> if (!text.dirty && selected.localPhotos.isEmpty()) onReviewPublication?.invoke(selected)
            else -> Unit
        }
    }, onEdit = { observed, edit ->
        if (observed === model && current() && edit is BlueprintAuthoringEdit.Text && model.canEdit(edit.field)) {
            when (edit.field) {
                "caption" -> text.caption = edit.value
                "alt" -> text.alt = edit.value
            }
        }
    }, onMore = { observed -> if (observed === model && current() && editable) tools = true })
    if (tools) AlertDialog(onDismissRequest = { tools = false }, title = { Text("Photos on this device") },
        text = { Column {
            if (selected.uploadedPhotos.isNotEmpty()) Text("${selected.uploadedPhotos.size} uploaded photo(s) are staged in this draft. Removing one changes only this draft’s selection; stored bytes and upload history remain.")
            selected.uploadedPhotos.forEachIndexed { index, photo ->
                if (onRemoveUploadedPhoto != null) TextButton(enabled = editable && !text.dirty, onClick = {
                    if (current() && !text.dirty) { tools = false; onRemoveUploadedPhoto(selected, photo.assetId) }
                }) { Text("Remove uploaded photo ${index + 1} from draft") }
            }
            if (selected.localPhotos.isNotEmpty()) Text("${selected.localPhotos.size} local photo(s) below are not staged as uploaded media. Each has its own retained upload status and separate Use review.")
            Text(if (text.dirty) "Save your caption and description before changing photos or opening upload status."
                else if (onPhotoUpload != null) "Photo upload has its own review and status. An upload never publishes your draft."
                else "These are local copies. Upload controls are unavailable here; this does not establish whether an earlier upload reached storage. Removing a local copy does not confirm remote deletion.")
            selected.localPhotos.forEachIndexed { index, photo ->
                if (onPhotoUpload != null) TextButton(enabled = editable && !text.dirty, onClick = {
                    if (current() && !text.dirty) {
                        tools = false
                        onPhotoUpload(selected, photo.assetId, preview?.takeIf { it.mediaId == photo.assetId })
                    }
                }) { Text("Unstaged photo ${index + 1}: upload / status") }
                TextButton(enabled = editable && !text.dirty, onClick = {
                    if (current()) scope.launch { if (current()) controller.removePhoto(selected.clientDraftId, selected.localRevision, photo.assetId) }
                }) { Text("Remove unstaged photo ${index + 1}") }
            }
            TextButton(enabled = editable && !text.dirty && capture.available, onClick = {
                if (current()) { tools = false; capture.choose(selected) }
            }) { Text("Choose another photo") }
        } }, confirmButton = { TextButton(onClick = { tools = false }) { Text("Done") } })
    return true
}
