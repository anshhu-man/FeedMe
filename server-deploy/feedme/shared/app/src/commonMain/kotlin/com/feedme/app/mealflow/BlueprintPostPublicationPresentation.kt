package com.feedme.app.mealflow

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.feedme.app.blueprint.*
import com.feedme.contracts.WireDocument
import com.feedme.core.ports.FailureReason
import com.feedme.mealflow.social.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/** Original PUBLISH_STATUS over the real composer. Rendering never restores, retries or
 * publishes. The existing detailed owner keeps exact consent, original recovery and receipt
 * application; this screen never treats an upload or historical Post as a new publication. */
@Composable
internal fun BlueprintPostPublicationPresentation(controller: PostComposerController, drafts: PostDraftController,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
    actionAllowed: () -> Boolean, onBack: () -> Unit) {
    // Capture the rendered snapshots, not delegated getters that would make stale callbacks
    // compare the latest values with themselves after a controller emission.
    val state = controller.states.collectAsState().value
    val draftState = drafts.states.collectAsState().value
    val latestAllowed by rememberUpdatedState(actionAllowed)
    val latestBack by rememberUpdatedState(onBack)
    var attached by remember(controller, drafts) { mutableStateOf(true) }
    DisposableEffect(controller, drafts) { onDispose { attached = false } }
    var details by remember(controller) { mutableStateOf(false) }
    var detailsOrigin by remember(controller) { mutableStateOf<PostComposerState?>(null) }
    var more by remember(controller) { mutableStateOf(false) }
    var leaving by remember(controller) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val privateVisible = state.phase != PostComposerPhase.UNAVAILABLE && draftState.phase != PostDraftPhase.UNAVAILABLE
    fun exactView() = attached && controller.states.value === state && drafts.states.value === draftState
    fun openDetails() {
        if (!exactView() || !privateVisible || leaving) return
        more = false; detailsOrigin = state; details = true
    }
    fun back() {
        if (!attached || leaving) return
        if (more) { more = false; return }
        leaving = true
        // Invalidate a live review without cancelling/replacing an allocated original. No
        // authority predicate blocks local departure after a session has been retired.
        scope.launch {
            try { controller.dismissReview() }
            finally { if (attached) latestBack() }
        }
    }

    // A genuinely delivered terminal result returns from full consent to the original status
    // layout. Merely opening historical details never promotes an earlier receipt to success.
    val newAcknowledgement = details && state !== detailsOrigin && state.acknowledged &&
        state.phase in setOf(PostComposerPhase.PUBLISHED, PostComposerPhase.CANCELLED_UNSENT)
    if (newAcknowledgement) SideEffect { details = false; detailsOrigin = null }
    if (details && !newAcknowledgement) {
        FeedMePostComposerScreen(controller, drafts, platformBackHandler,
            actionAllowed = { attached && details && !leaving && latestAllowed() }) {
            if (attached) { details = false; detailsOrigin = null }
        }
        return
    }

    val review = state.review
    val reviewSelection = Triple(draftState.screen, draftState.selected?.clientDraftId, draftState.selected?.localRevision)
    val selectionAtReview = remember(review?.token) { reviewSelection }
    val reviewedLocal = review?.snapshot?.reviewedLocal
    val retainedLocal = reviewedLocal?.let { expected ->
        draftState.localDrafts.singleOrNull { it.clientDraftId == expected.clientDraftId }
    }
    fun exactReview() = exactView() && privateVisible && review != null && review.isCurrentForNavigation &&
        selectionAtReview == Triple(drafts.states.value.screen, drafts.states.value.selected?.clientDraftId,
            drafts.states.value.selected?.localRevision) && reviewedLocal != null &&
        retainedLocal?.localRevision == reviewedLocal.localRevision &&
        (drafts.states.value.selected == null || drafts.states.value.selected?.clientDraftId == reviewedLocal.clientDraftId)
    val reviewCurrent = exactReview()
    val model = publicationStatusPresentation(state, privateVisible, reviewCurrent,
        canReview = reviewCurrent && latestAllowed() && !leaving, canOpenDetails = privateVisible && !leaving,
        leaving = leaving)
    platformBackHandler(true, ::back)
    BlueprintPublishStatusScreen(model, onAction = { observed, action ->
        if (observed === model && model.allows(action)) when (action) {
            BlueprintAuthoringAction.PUBLISH_BACK, BlueprintAuthoringAction.LEAVE_WITH_DRAFT,
            BlueprintAuthoringAction.EDIT_BEFORE_SHARING -> if (attached) back()
            BlueprintAuthoringAction.PUBLISH -> if (!more && !leaving && latestAllowed() && exactReview()) openDetails()
            else -> Unit // Upload/status actions belong to the separate retained-photo owner.
        }
    }, onMore = { observed ->
        if (observed === model && model.controls.moreEnabled && exactView() && !leaving) more = true
    })
    if (more && privateVisible) AlertDialog(onDismissRequest = { more = false },
        title = { Text("Publication details") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Publication and photo upload are separate. Opening details does not send or retry a post.")
                Text("An uncertain original is kept unchanged. Back cannot undo a publication already accepted by the server.")
                TextButton(onClick = ::openDetails) {
                    Text(when {
                        state.review != null -> "Review exact publication"
                        state.pending != null || state.allocation != null || state.phase == PostComposerPhase.ERROR -> "Open original recovery"
                        else -> "Open retained publication details"
                    })
                }
            }
        }, confirmButton = { TextButton(onClick = { more = false }) { Text("Close") } })
}

/** Detached display only. Identity comes from the exact live review/original or actual returned
 * Post + ETag. No arbitrary history row, invented version, preview, progress or safety flag. */
private fun publicationStatusPresentation(state: PostComposerState, visible: Boolean, reviewCurrent: Boolean,
    canReview: Boolean, canOpenDetails: Boolean, leaving: Boolean): BlueprintPublishStatusState {
    val review = state.review?.takeIf { visible && reviewCurrent }
    val original = if (!visible) null else state.retry?.original ?: state.unsentCancellation?.original ?:
        state.pending?.let { pending -> state.history.singleOrNull { it.original.commandId == pending.commandId }?.original }
    val returned = if (visible && state.phase == PostComposerPhase.PUBLISHED) publicationStatusObject(state.exactCanonicalPost) else null
    val returnedVersion = state.etag?.let { Regex("\"([1-9][0-9]{0,18})\"").matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }
    val returnedId = returned.publicationStatusText("id")
    val returnedOwner = (returned?.get("author") as? JsonObject).publicationStatusText("userId")
    val returnedIdentity = if (returnedId != null && returnedOwner != null && returnedVersion != null)
        BlueprintAuthoringIdentity(returnedOwner, returnedId, returnedVersion) else null
    val identity = when {
        returnedIdentity != null -> returnedIdentity
        review != null -> BlueprintAuthoringIdentity(review.snapshot.publisherUserId,
            review.snapshot.reviewedLocal.clientDraftId, review.snapshot.reviewedLocal.localRevision)
        original != null -> BlueprintAuthoringIdentity(original.originalCanonicalUserId,
            original.clientDraftId, original.originalReviewedLocal.localRevision)
        else -> null
    }
    val receipt = if (visible && state.acknowledged && state.phase == PostComposerPhase.PUBLISHED &&
        returned.publicationStatusText("status") == "published" && returnedIdentity != null)
        BlueprintPublicationReceipt(returnedIdentity, returnedIdentity.resourceId, returnedIdentity.revision,
            "Publication confirmed for this exact post. Current audience access can change.") else null
    val body = returned ?: publicationStatusObject(review?.snapshot?.exactProposedPostWrite ?: original?.exactOriginalPostWrite)
    val audience = body?.get("audience") as? JsonObject
    val saves = if (returned != null) (body?.get("savePolicy") as? JsonObject)?.get("allowFutureSaves")
        else body?.get("allowRecipeSaves")
    val media = body?.get("mediaIds") as? JsonArray
    val phase = when {
        receipt != null -> BlueprintPublishPhase.PUBLISHED
        state.pending != null || state.allocation != null || state.phase == PostComposerPhase.PUBLISHED ||
            state.failure == FailureReason.OUTCOME_UNKNOWN -> BlueprintPublishPhase.OUTCOME_UNKNOWN
        state.phase in setOf(PostComposerPhase.ERROR, PostComposerPhase.UNAVAILABLE, PostComposerPhase.CANCELLED_UNSENT) -> BlueprintPublishPhase.FAILED
        else -> BlueprintPublishPhase.REVIEW
    }
    val status = when {
        !visible -> "This account view is unavailable. Private publication details are hidden; Back remains available."
        leaving -> "Returning to the draft. This does not cancel a publication already accepted by the server."
        state.failure != null -> publicationFailureText(state.failure)
        state.phase in setOf(PostComposerPhase.PUBLISHED, PostComposerPhase.CANCELLED_UNSENT) -> publicationOutcomeText(state.phase, state.acknowledged)
        state.review != null && !reviewCurrent -> "This review is no longer current. Return to the draft and prepare a fresh review; nothing is sent by Back."
        review != null -> "The Publish button opens your exact content and disclosure for confirmation. Nothing is sent until you confirm there."
        state.retry != null -> "An exact-original retry review is retained. Open publication details to review and confirm; newer draft edits will not replace it."
        state.unsentCancellation != null -> "An unsent-cancellation review is retained. Open publication details to confirm; Back does not cancel the original."
        state.pending?.phase == "RECEIPT_READY" -> "A result awaits application on this device. Open original recovery; it is not yet shown as a confirmed publication."
        state.pending != null -> "The original publication is retained. Open original recovery for an explicit retry or reconciliation; no automatic retry occurs."
        state.allocation != null -> "Publication request allocation is unresolved. Open original recovery; do not create a replacement."
        else -> "No current publication receipt is selected. More opens retained history and explicit recovery; historical results are not a new acknowledgement."
    }
    return BlueprintPublishStatusState(
        context = identity?.let { BlueprintAuthoringContext(it, BlueprintAuthoringMode.DRAFT, visible) },
        caption = body.publicationStatusText("caption").orEmpty(), phase = phase, receipt = receipt,
        mediaStatus = when {
            media == null -> null
            media.isEmpty() -> "No media is included in this publication. Retained photo uploads are separate."
            else -> "${media.size} media item(s) in this exact publication; no current upload or safety status is inferred."
        },
        audienceLabel = when (audience.publicationStatusText("kind")) {
            "self" -> "Only you"
            "circles" -> (audience?.get("circleIds") as? JsonArray)?.let { "${it.size} explicitly selected circle(s)" }
            else -> null
        },
        savePermissionLabel = when ((saves as? JsonPrimitive)?.booleanOrNull) {
            true -> "Recipe saves allowed in these exact choices; no repost permission."
            false -> "Recipe saves off in these exact choices."
            null -> null
        },
        retentionLabel = when ((body?.get("keepOnPlate") as? JsonPrimitive)?.booleanOrNull) {
            true -> "Keep on your Plate, as selected."
            false -> "Do not keep on your Plate, as selected."
            null -> null
        },
        controls = BlueprintAuthoringControls(actions = buildSet {
            add(BlueprintAuthoringAction.PUBLISH_BACK)
            if (identity != null && visible && !leaving) {
                if (phase != BlueprintPublishPhase.PUBLISHED) add(BlueprintAuthoringAction.LEAVE_WITH_DRAFT)
                if (review != null) add(BlueprintAuthoringAction.EDIT_BEFORE_SHARING)
                if (canReview) add(BlueprintAuthoringAction.PUBLISH)
            }
        }, moreEnabled = canOpenDetails),
        status = status, exactComposerReviewAvailable = canReview,
        publicationNote = when {
            !visible -> "Private publication details are hidden. Leaving this screen does not send or cancel a post."
            receipt != null -> "This exact publication is confirmed. Audience and access remain subject to current permissions."
            phase == BlueprintPublishPhase.OUTCOME_UNKNOWN -> "Publication is not confirmed here. Keep the exact original; do not start a replacement."
            state.phase == PostComposerPhase.CANCELLED_UNSENT && state.acknowledged -> "Only the confirmed unsent original was cancelled. This does not erase any earlier post."
            review != null -> "Review these exact choices before confirming. Uploading a photo alone never publishes a meal."
            else -> "Uploads and retained history do not establish a new publication. Open the original details before deciding what to do next."
        })
}

private fun publicationStatusObject(document: WireDocument?): JsonObject? = document?.let {
    runCatching { Json.parseToJsonElement(it.encodeUtf8().decodeToString()) as? JsonObject }.getOrNull()
}
private fun JsonObject?.publicationStatusText(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
