package com.feedme.app.mealflow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.feedme.app.blueprint.*
import com.feedme.mealflow.social.LocalPostDraft
import com.feedme.mealflow.social.PostDraftController
import com.feedme.mealflow.social.PostDraftIssue
import com.feedme.mealflow.social.PostDraftJournalFormat
import com.feedme.mealflow.social.PostDraftPhase
import com.feedme.mealflow.social.PostDraftScreen
import com.feedme.mealflow.social.PostDraftState

internal class BlueprintPhotoCaptureActions(val available: Boolean, val working: Boolean,
    val message: String?, val choose: (LocalPostDraft) -> Unit)

/** The platform picker owner remains mounted across capture → editor. Only the explicit
 * From photos action prepares a revision-bound token and launches a system picker. */
@Composable
internal expect fun rememberBlueprintPhotoCapture(controller: PostDraftController): BlueprintPhotoCaptureActions

/** Original CAPTURE presentation bound to a real retained draft, not a synthetic author.
 * Existing pending, rejected, legacy and recovery states stay with the established draft UI.
 * ownerId is supplied from the current native session; it is never a server authorization.
 */
@Composable
internal fun RetainedBlueprintLocalPhotoCapture(controller: PostDraftController, state: PostDraftState,
    ownerId: String?, actions: BlueprintPhotoCaptureActions, back: () -> Unit): Boolean {
    val selected = state.selected ?: return false
    var editorRequested by remember(controller, selected.clientDraftId) { mutableStateOf(false) }
    if (ownerId.isNullOrBlank() || editorRequested || state.screen != PostDraftScreen.EDITOR ||
        state.journalFormat != PostDraftJournalFormat.CURRENT || state.phase != PostDraftPhase.READY ||
        state.busy || state.issue != PostDraftIssue.NONE || state.failureReason != null ||
        state.pending != null || state.reviewedAllocation != null || state.publicationHold != null ||
        state.reviewedSave != null || state.reviewedRetry != null || state.unsubmittedRemainders.isNotEmpty() ||
        state.discardConfirmation != null || !selected.localAcknowledged || selected.serverAssociated ||
        selected.publicationHeld || selected.localPhotos.isNotEmpty() || selected.uploadedPhotos.isNotEmpty()) return false
    val identity = BlueprintAuthoringIdentity(ownerId, selected.clientDraftId, selected.localRevision)
    val model = BlueprintCaptureState(BlueprintAuthoringContext(identity, BlueprintAuthoringMode.DRAFT, current = true),
        resumableDraft = identity,
        controls = BlueprintAuthoringControls(actions = buildSet {
            add(BlueprintAuthoringAction.CAPTURE_BACK)
            add(BlueprintAuthoringAction.CANCEL_CAPTURE)
            if (!actions.working) {
                add(BlueprintAuthoringAction.RESUME_DRAFT)
                if (actions.available) add(BlueprintAuthoringAction.CHOOSE_PHOTO)
            }
        }), status = listOfNotNull(
            if (selected.recipeSourceSuggestion != null) "Recipe reference retained. Review it before attaching; nothing has been posted." else null,
            actions.message ?: if (actions.working) "Preparing your photo…" else
                "Choosing a photo keeps a local copy in this private draft. Upload/status is a separate review when configured. Camera, clips and publishing are not connected here."
        ).joinToString("\n\n"))
    BlueprintCaptureScreen(model, onAction = { observed, action ->
        if (observed !== model || controller.states.value !== state || !model.allows(action)) return@BlueprintCaptureScreen
        when (action) {
            BlueprintAuthoringAction.CAPTURE_BACK, BlueprintAuthoringAction.CANCEL_CAPTURE -> back()
            BlueprintAuthoringAction.CHOOSE_PHOTO -> actions.choose(selected)
            BlueprintAuthoringAction.RESUME_DRAFT -> editorRequested = true
            else -> Unit
        }
    }, onMore = {})
    return true
}
