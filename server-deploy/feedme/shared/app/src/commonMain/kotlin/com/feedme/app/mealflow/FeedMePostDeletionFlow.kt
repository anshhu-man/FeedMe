package com.feedme.app.mealflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feedme.app.FeedMeTheme
import com.feedme.app.blueprint.*
import com.feedme.core.ports.*
import com.feedme.mealflow.postdeletion.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Original DELETE_POST plus exact second confirmation. Server deletion/cleanup is never
 * inferred from navigation, an HTTP failure, a missing post or a locally retained original. */
@Composable
fun FeedMePostDeletionFlow(controller: PostDeletionController, hostIsCurrent: () -> Boolean,
    onClose: () -> Unit, platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
    onDeleted: ((String) -> Unit)? = null) {
    val observed by controller.states.collectAsState()
    val state = observed
    val hostCurrent by rememberUpdatedState(hostIsCurrent)
    val scope = rememberCoroutineScope()
    var attached by remember(controller) { mutableStateOf(true) }
    DisposableEffect(controller) { onDispose { attached = false } }
    var busy by remember(controller) { mutableStateOf(false) }
    var more by remember(controller) { mutableStateOf(false) }
    var confirmedPost by remember(controller) { mutableStateOf<DeletablePost?>(null) }
    var leaveFailure by remember(controller) { mutableStateOf(false) }
    fun current() = attached && hostCurrent() && controller.isCurrent(state)
    val deleting = state.phase == PostDeletionPhase.DELETING
    val loading = busy || state.phase == PostDeletionPhase.LOADING
    fun act(work: suspend () -> Unit) {
        if (!busy && current()) {
            busy = true
            scope.launch { try { if (current()) work() } finally { busy = false } }
        }
    }
    fun leave() {
        if (!current() || deleting) return
        val expected = state
        val deleted = state.receipt?.postId.takeIf { state.phase == PostDeletionPhase.COMPLETE }
        scope.launch { withContext(NonCancellable) {
            when (val result = controller.leave(expected)) {
                is PortResult.Value -> if (controller.isCurrent(result.value) && result.value.phase == PostDeletionPhase.HIDDEN) {
                    // Do not reuse the old screen predicate after HIDDEN. Parent callbacks
                    // independently guard their retained social source before navigation.
                    if (deleted != null && onDeleted != null) onDeleted(deleted) else onClose()
                }
                is PortResult.Failure -> if (attached) leaveFailure = true
            }
        } }
    }
    fun back() {
        if (!current() || deleting) return
        when { more -> more = false
            state.review != null -> act { controller.backReview(state) }
            else -> leave() }
    }
    platformBackHandler(state.phase != PostDeletionPhase.HIDDEN, ::back)
    val review = state.review.takeIf { current() }
    if (review != null) {
        val model = BlueprintConfirmationState(
            actionLabel = if (review.retryOriginal) "Retry the original post deletion" else "Delete the selected post",
            affectedSummary = "Post ${review.postId} · version ${review.version}" + (review.caption?.let { "\n\n$it" } ?: ""),
            consequences = listOf(
                "A confirmed deletion denies new delivery of this post and schedules its attached media for cleanup. It does not prove provider erasure has finished.",
                "Already-authorized private recipe copies and screenshots are not recalled by this action. Separate safety or legal restrictions still apply.",
                if (review.retryOriginal) "Only the retained request key, post and version are retried. The earlier outcome is not assumed. No new version is substituted."
                    else "This request uses exactly the reviewed post version. A changed version requires separate reconciliation, not an automatic replacement."),
            canConfirm = current() && !loading && !deleting, canCancel = current() && !loading && !deleting,
            busy = loading || deleting)
        BlueprintConfirmationScreen(model, onConfirm = { rendered ->
            if (rendered === model && model.confirmEnabled) act { controller.confirm(review) }
        }, onCancel = { rendered -> if (rendered === model && model.cancelEnabled) act { controller.backReview(state) } },
            heading = "Delete this\npost?", confirmLabel = if (review.retryOriginal) "Retry original deletion" else "Confirm deletion")
        return
    }
    val post = state.post.takeIf { current() }
    val reference = post?.let { BlueprintControlReference(BlueprintControlReferenceKind.POST, it.id, it.version) }
    val ready = current() && state.phase == PostDeletionPhase.READY && post != null && !loading && state.pending == null
    val model = BlueprintAccountControlsState(BlueprintAccountControlPage.DELETE_POST,
        phase = when (state.phase) {
            PostDeletionPhase.LOADING -> BlueprintAccountControlPhase.LOADING
            PostDeletionPhase.DELETING -> BlueprintAccountControlPhase.WORKING
            PostDeletionPhase.RECOVERY -> BlueprintAccountControlPhase.UNKNOWN
            PostDeletionPhase.ERROR, PostDeletionPhase.EXPIRED -> BlueprintAccountControlPhase.ERROR
            PostDeletionPhase.HIDDEN, PostDeletionPhase.UNAVAILABLE -> BlueprintAccountControlPhase.UNAVAILABLE
            else -> BlueprintAccountControlPhase.READY
        }, context = if (post != null && reference != null) BlueprintAccountControlContext(
            BlueprintControlReference(BlueprintControlReferenceKind.ACCOUNT, post.accountId, post.accountVersion), state.revision, reference) else null,
        enabledActionIds = buildSet {
            if (current() && !deleting) { add("DELETE_POST.back"); add("DELETE_POST.02") }
            if (ready && confirmedPost === post) add("DELETE_POST.01")
        }, editableFields = if (ready) setOf(BlueprintAccountControlField.DELETE_CONFIRM) else emptySet(),
        fields = BlueprintAccountControlFields(deleteConfirmed = post != null && confirmedPost === post),
        // These are the implemented canonical operation's semantics, not copy counts,
        // current grant claims, completed physical erasure or permission to delete.
        policies = if (post != null) setOf(BlueprintAccountPolicyFact.POST_REMOVAL, BlueprintAccountPolicyFact.RETAINED_RECIPE_COPIES) else emptySet(),
        selectedPost = if (post != null && reference != null) BlueprintControlPost(reference,
            post.caption.ifBlank { "Your selected post" }, "Current version ${post.version}") else null,
        statusMessage = postDeletionNotice(state) + if (leaveFailure) "\nCould not leave the current view safely. Try Back again." else "")
    BlueprintAccountControlsScreen(model, onEvent = { event ->
        if (event.expected === model && current()) when (event) {
            is BlueprintAccountControlEvent.FieldChanged -> if (event.field == BlueprintAccountControlField.DELETE_CONFIRM &&
                event.value is BlueprintAccountControlValue.Toggle && model.accepts(event.field, event.value))
                confirmedPost = post.takeIf { event.value.value }
            is BlueprintAccountControlEvent.Action -> if (model.admits(event.actionId)) when (event.actionId) {
                "DELETE_POST.back", "DELETE_POST.02" -> back()
                "DELETE_POST.01" -> if (ready && confirmedPost === post) act { controller.prepareDelete(state) }
                else -> Unit
            }
        }
    }, onMore = { if (current()) more = true })
    if (more && current()) FeedMeTheme {
        AlertDialog(onDismissRequest = { more = false }, title = { Text("Post deletion status") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(postDeletionNotice(state))
                state.pending?.let { original ->
                    Text("Original post ${original.postId} · version ${original.version}")
                    Text("Status: ${original.phase?.name ?: "Original retained; registration unconfirmed"}")
                    if (original.canRetry) TextButton(enabled = !loading && !deleting, onClick = {
                        more = false; act { controller.prepareRetry(state) }
                    }) { Text("Review retry of original") }
                    if (original.receiptReady) TextButton(enabled = !loading && !deleting, onClick = {
                        more = false; act { controller.recoverReceipt(state) }
                    }) { Text("Confirm retained receipt") }
                    Text("Back keeps this original. It does not cancel, resend or replace it. A version or permission conflict needs reconciliation.")
                }
                state.receipt?.let { receipt ->
                    Text("Confirmed original: ${receipt.commandId}")
                    Text("Deleted post ${receipt.postId}; the original matched version ${receipt.deletedVersion}. This is not the new tombstone version, and media cleanup completion is not established by this receipt.")
                }
                if (state.pending == null && state.receipt == null) TextButton(enabled = !loading && !deleting, onClick = {
                    more = false; act { controller.refresh(state) }
                }) { Text("Refresh current post") }
            }
        }, confirmButton = { TextButton(onClick = { more = false }) { Text("Done") } })
    }
}

private fun postDeletionNotice(state: PostDeletionState): String = when {
    state.phase == PostDeletionPhase.DELETING -> "Finishing the confirmed original. No deletion success is assumed yet."
    state.pending != null -> "A retained deletion needs attention. More shows the original status and safe recovery."
    state.phase == PostDeletionPhase.COMPLETE && state.receipt != null ->
        "Post deletion confirmed. New delivery is denied and media cleanup is scheduled; physical erasure is not confirmed. Use Back to leave this result."
    state.phase == PostDeletionPhase.LOADING -> "Checking your account and the current post. Back can leave this read."
    state.phase == PostDeletionPhase.EXPIRED -> "This review expired. Refresh the post and review its current version."
    state.phase == PostDeletionPhase.UNAVAILABLE -> "The account session is unavailable. Private post content is hidden."
    state.phase == PostDeletionPhase.ERROR -> when (state.failureReason) {
        FailureReason.NOT_FOUND -> "The post is unavailable. Its absence does not confirm your deletion request."
        FailureReason.FORBIDDEN -> "This account cannot currently delete this post."
        FailureReason.OFFLINE -> "Connect to review or send the original. Nothing is retried automatically."
        FailureReason.CONFLICT -> "The post or original changed. No replacement version is submitted automatically."
        FailureReason.NOT_CONFIGURED -> "Post deletion is not connected in this configuration."
        FailureReason.OUTCOME_UNKNOWN, FailureReason.STORAGE_FAILURE -> "The outcome is not confirmed. Keep the original and use its recovery controls."
        FailureReason.RATE_LIMITED -> "Wait before trying again. No retry is scheduled automatically."
        else -> "The deletion action could not be confirmed. No success is assumed."
    }
    else -> "Review this exact post. Checking the box does not send deletion; a separate confirmation follows."
}
