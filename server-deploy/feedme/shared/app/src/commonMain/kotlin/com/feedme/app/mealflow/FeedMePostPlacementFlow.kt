package com.feedme.app.mealflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feedme.app.FeedMeTheme
import com.feedme.app.blueprint.BlueprintConfirmationScreen
import com.feedme.app.blueprint.BlueprintConfirmationState
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.mealflow.postplacement.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/** Original POST actions use the existing confirmation/recovery presentation. Mounting
 * performs no read or write. The parent clears the old source before leaving exactly once. */
@Composable
fun FeedMePostPlacementFlow(controller: PostPlacementController, onClose: () -> Unit,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
    hostIsCurrent: () -> Boolean = { true }) {
    val state = controller.states.collectAsState().value
    val scope = rememberCoroutineScope()
    val currentHost by rememberUpdatedState(hostIsCurrent)
    val currentClose by rememberUpdatedState(onClose)
    val host = remember(controller) { MealFlowHostActions { currentHost() } }
    DisposableEffect(host) { onDispose { host.retire() } }
    val visit = remember(controller, state) { PostPlacementVisit() }
    DisposableEffect(visit) { onDispose { visit.retire() } }
    val renderedEpoch = visit.epoch
    var running by remember(controller) { mutableStateOf(false) }
    var localFailure by remember(controller) { mutableStateOf<FailureReason?>(null) }
    val busy = running || state.phase in setOf(PostPlacementPhase.LOADING, PostPlacementPhase.UPDATING)
    fun attached() = host.isCurrent() && visit.attached && controller.states.value === state
    fun current() = attached() && controller.isCurrent(state)
    fun underlayCurrent() = current() && visit.epoch === renderedEpoch && !visit.more
    fun moreCurrent() = current() && visit.epoch === renderedEpoch && visit.more
    fun closeMore() { if (attached() && visit.more) visit.change() }
    fun act(admission: () -> Boolean = ::underlayCurrent, work: suspend () -> PortResult<PostPlacementState>) {
        if (busy || !admission()) return
        running = true; localFailure = null
        val job = host.launch(scope) {
            try {
                if (!admission()) return@launch
                // Retire this exact modal/underlay admission before the owner publishes
                // loading. Do not reuse the old presentation predicate after that change.
                visit.change()
                val result = host.await(work)
                host.run { localFailure = (result as? PortResult.Failure)?.reason }
            } finally { if (host.isCurrent() && currentCoroutineContext().isActive) running = false }
        }
        if (job == null) running = false
    }
    fun back() {
        // Back is local departure, including during an uncertain held request. The root,
        // not this child, owns source redaction and the controller's single leave call.
        if (!attached()) return
        if (visit.more) closeMore()
        else if (!busy && state.review != null && current()) act { controller.backReview(state) }
        else currentClose()
    }
    platformBackHandler(state.phase != PostPlacementPhase.HIDDEN && host.isCurrent(), ::back)
    if (state.phase == PostPlacementPhase.HIDDEN) return
    val visible = current() && state.phase != PostPlacementPhase.UNAVAILABLE
    val review = state.review.takeIf { visible }
    val pending = state.pending.takeIf { visible }
    val receipt = state.receipt.takeIf { visible }
    val notice = postPlacementNotice(state) + postPlacementFailure(localFailure ?: state.failureReason)
        ?.let { "\n\n$it" }.orEmpty()

    if (review != null && state.phase == PostPlacementPhase.REVIEW) {
        val keep = review.keepOnPlate
        val model = BlueprintConfirmationState(
            actionLabel = if (keep) "Keep on my Plate" else "Remove from my Plate",
            affectedSummary = review.caption?.takeIf { it.isNotBlank() } ?: "Your selected post",
            consequences = listOf(
                "Only Plate placement changes. The audience, caption, photos and recipe attachment stay the same.",
                if (keep) "This does not publish a new post or extend its Today expiry."
                else "This does not delete the post. An active Today appearance keeps its existing expiry.",
                if (review.retryOriginal) "This retries only your retained original change. Its earlier outcome may be unknown."
                else "Nothing changes until you confirm. A changed post needs a new review, not an automatic replacement."),
            canConfirm = underlayCurrent() && !busy, canCancel = underlayCurrent() && !busy,
            busy = busy, status = notice)
        BlueprintConfirmationScreen(model, onConfirm = { expected ->
            if (expected === model && model.confirmEnabled) act {
                if (review.retryOriginal) controller.retryOriginal(review) else controller.confirm(review)
            }
        }, onCancel = { expected -> if (expected === model && model.cancelEnabled) back() },
            heading = if (keep) "Keep on\nmy Plate?" else "Remove from\nmy Plate?",
            confirmLabel = if (review.retryOriginal) "Retry original change" else if (keep) "Keep on my Plate" else "Remove from my Plate")
        return
    }

    FeedMeTheme {
        AlertDialog(onDismissRequest = ::back, title = { Text("My Plate placement") }, text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(notice)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (visible) {
                    Text("This changes Plate placement only, not the post's audience or other content.")
                    if (receipt != null) Text("This may be a historical acknowledgement. Close and explicitly reopen or refresh the source to check its current placement.")
                    if (pending != null) Text("Your original change is retained. Back does not undo a request already sent. Nothing retries automatically.")
                    if (!busy && pending != null) TextButton(onClick = {
                        if (underlayCurrent()) visit.change(more = true)
                    }) { Text("More · retained change") }
                }
            }
        }, confirmButton = {
            if (visible && state.phase == PostPlacementPhase.READY && state.post != null &&
                state.desiredKeepOnPlate != null && pending == null && receipt == null)
                TextButton(onClick = { act { controller.prepare(state) } }, enabled = underlayCurrent() && !busy) {
                    Text("Review placement change")
                }
            else TextButton(onClick = ::back, enabled = attached()) { Text("Back") }
        }, dismissButton = {
            if (state.phase == PostPlacementPhase.READY) TextButton(onClick = ::back, enabled = attached()) { Text("Not now") }
        })
        if (visit.more && moreCurrent()) AlertDialog(onDismissRequest = ::closeMore,
            title = { Text("Retained placement change") }, text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (pending?.keepOnPlate == true) "Original action: keep on my Plate." else "Original action: remove from my Plate.")
                    Text("Recovery uses the same post, reviewed version and request. It does not submit another placement change.")
                    if (pending?.receiptReady == true) TextButton(onClick = {
                        act(::moreCurrent) { controller.recoverReceipt(state) }
                    }, enabled = !busy) { Text("Recover confirmed receipt") }
                    if (pending?.canRetry == true) TextButton(onClick = {
                        act(::moreCurrent) { controller.prepareRetry(state) }
                    }, enabled = !busy) { Text("Review exact retry") }
                    Text("A conflict or unavailable result is not success. Close this view without replacing the original.")
                }
            }, confirmButton = { TextButton(onClick = ::closeMore) { Text("Close actions") } })
    }
}

private class PostPlacementVisit {
    var attached = true
        private set
    var epoch by mutableStateOf<Any>(Any())
        private set
    var more by mutableStateOf(false)
        private set
    fun change(more: Boolean = false) { this.more = more; epoch = Any() }
    fun retire() { attached = false; change() }
}

private fun postPlacementNotice(state: PostPlacementState): String = when {
    state.phase == PostPlacementPhase.UNAVAILABLE -> "This account session is unavailable. Private post details are hidden."
    state.phase == PostPlacementPhase.LOADING -> "Checking the current post and any retained placement request…"
    state.phase == PostPlacementPhase.UPDATING -> "Keeping and sending your confirmed change. No success is assumed yet."
    state.phase == PostPlacementPhase.COMPLETE && state.receipt != null ->
        if (state.receipt!!.keepOnPlate) "The server acknowledged the original request to keep this post on your Plate."
        else "The server acknowledged the original request to remove this post from your Plate. This is not post deletion."
    state.pending != null -> "A retained placement change needs attention. Review its exact original or recover an available receipt."
    state.phase == PostPlacementPhase.EXPIRED -> "This observation expired. Go Back and explicitly reopen the source before starting a new change."
    state.phase == PostPlacementPhase.REVIEW -> "Review this exact change before confirming."
    state.phase == PostPlacementPhase.READY && (state.post == null || state.desiredKeepOnPlate == null) ->
        "No retained placement change was found. Close and select an actual post to start a new change."
    state.phase == PostPlacementPhase.READY -> if (state.desiredKeepOnPlate == true)
        "Review keeping this post on your Plate. Nothing has changed yet."
        else "Review removing this post from your Plate. Nothing has changed yet."
    else -> "No placement change is confirmed. Back keeps any retained original."
}

private fun postPlacementFailure(reason: FailureReason?): String? = when (reason) {
    null -> null
    FailureReason.OUTCOME_UNKNOWN, FailureReason.STORAGE_FAILURE -> "The outcome is not confirmed. Keep the original and use its recovery controls."
    FailureReason.OFFLINE -> "Reconnect before reviewing a retry. Nothing sends automatically."
    FailureReason.CONFLICT -> "The post or retained request changed. No replacement is submitted automatically."
    FailureReason.FORBIDDEN, FailureReason.NOT_FOUND -> "The post is not available for this change. That does not confirm a previous request's outcome."
    FailureReason.RATE_LIMITED -> "Please wait before trying the exact original again."
    FailureReason.STALE_SESSION, FailureReason.UNAUTHENTICATED -> "This account session is no longer available."
    FailureReason.NOT_CONFIGURED -> "Plate placement is not connected for this session."
    else -> "The placement change could not be confirmed. No success is assumed."
}
