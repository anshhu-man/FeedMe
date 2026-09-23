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
import com.feedme.contracts.WireField
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.mealflow.reactions.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

internal fun reactionKindLabel(kind: String): String? = when (kind) {
    "heart" -> "Heart"
    "looksDoable" -> "Looks doable"
    "makingThis" -> "Making this"
    "yum" -> "Yum"
    else -> null
}
private val reactionKinds = listOf("heart", "looksDoable", "makingThis", "yum")

/** Explicit reaction choice/confirmation over the retained owner. No work on composition;
 * the parent owns source redaction and the one leave call, including uncertain outcomes. */
@Composable
fun FeedMeReactionFlow(controller: ReactionController, onClose: () -> Unit,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
    hostIsCurrent: () -> Boolean = { true }) {
    val state = controller.states.collectAsState().value
    val scope = rememberCoroutineScope()
    val currentHost by rememberUpdatedState(hostIsCurrent)
    val currentClose by rememberUpdatedState(onClose)
    val host = remember(controller) { MealFlowHostActions { currentHost() } }
    DisposableEffect(host) { onDispose { host.retire() } }
    val visit = remember(controller, state) { ReactionVisit() }
    DisposableEffect(visit) { onDispose { visit.retire() } }
    val renderedEpoch = visit.epoch
    var running by remember(controller) { mutableStateOf(false) }
    var localFailure by remember(controller) { mutableStateOf<FailureReason?>(null) }
    val busy = running || state.phase in setOf(ReactionPhase.LOADING, ReactionPhase.UPDATING)
    fun attached() = host.isCurrent() && visit.attached && controller.states.value === state
    fun current() = attached() && controller.isCurrent(state)
    fun underlayCurrent() = current() && visit.epoch === renderedEpoch && !visit.more
    fun moreCurrent() = current() && visit.epoch === renderedEpoch && visit.more
    fun closeMore() { if (attached() && visit.more) visit.change() }
    fun act(admission: () -> Boolean = ::underlayCurrent, work: suspend () -> PortResult<ReactionState>) {
        if (busy || !admission()) return
        running = true; localFailure = null
        val job = host.launch(scope) {
            try {
                if (!admission()) return@launch
                visit.change()
                val result = host.await(work)
                host.run { localFailure = (result as? PortResult.Failure)?.reason }
            } finally { if (host.isCurrent() && currentCoroutineContext().isActive) running = false }
        }
        if (job == null) running = false
    }
    fun back() {
        if (!attached()) return
        if (visit.more) closeMore()
        else if (!busy && state.review != null && current()) act { controller.backReview(state) }
        else currentClose()
    }
    platformBackHandler(state.phase != ReactionPhase.HIDDEN && host.isCurrent(), ::back)
    if (state.phase == ReactionPhase.HIDDEN) return
    val visible = current() && state.phase != ReactionPhase.UNAVAILABLE
    val review = state.review.takeIf { visible }
    val pending = state.pending.takeIf { visible }
    val receipt = state.receipt.takeIf { visible }
    val own = state.currentReaction.takeIf { visible }
    val ready = visible && state.phase == ReactionPhase.READY && state.post != null && pending == null && receipt == null
    val canSet = ready && (state.post?.field("capabilities") as? WireField.Value)?.value?.elementsOrNull()
        ?.any { it.stringOrNull() == "react" } == true
    val canRemoveAcknowledged = visible && state.phase == ReactionPhase.COMPLETE && state.canRemoveAcknowledged
    val notice = reactionNotice(state) + reactionFailure(localFailure ?: state.failureReason)?.let { "\n\n$it" }.orEmpty()

    if (review != null && state.phase == ReactionPhase.REVIEW) {
        val remove = review.action == ReactionAction.REMOVE
        val label = review.kind?.let(::reactionKindLabel)
        val model = BlueprintConfirmationState(
            actionLabel = if (remove) "Remove my reaction" else label?.let { "React: $it" },
            affectedSummary = review.caption?.takeIf { it.isNotBlank() } ?: "Reaction on post ${review.postId}",
            consequences = buildList {
                add(if (remove) "This removes only your reaction. It does not delete or change the post."
                    else "This sets your one reaction to $label. It does not change the post or send a private message.")
                if (review.historicalRemoval) add("This uses your acknowledged reaction's original version, not a current post view. If that reaction changed, the removal may be refused; no newer version is substituted.")
                if (review.retryOriginal) add("Only your exact retained original is retried. Back cannot undo a request already sent.")
                else add("Nothing is sent until you confirm. Counts are checked only when you explicitly refresh the source.")
            }, canConfirm = underlayCurrent() && !busy && (remove || label != null),
            canCancel = underlayCurrent() && !busy, busy = busy, status = notice)
        BlueprintConfirmationScreen(model, onConfirm = { expected ->
            if (expected === model && model.confirmEnabled) act {
                if (review.retryOriginal) controller.retryOriginal(review) else controller.confirm(review)
            }
        }, onCancel = { expected -> if (expected === model && model.cancelEnabled) back() },
            heading = if (remove) "Remove my\nreaction?" else "Send this\nreaction?",
            confirmLabel = if (review.retryOriginal) "Retry original reaction" else if (remove) "Remove my reaction" else "Confirm reaction")
        return
    }

    FeedMeTheme {
        AlertDialog(onDismissRequest = ::back, title = { Text("Your reaction") }, text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(notice)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (visible) {
                    own?.let { Text("Your observed reaction: ${reactionKindLabel(it.kind)}") }
                    if (ready && !busy) {
                        if (canSet) {
                            Text("Choose one reaction to review. A separate confirmation sends it.")
                            reactionKinds.forEach { kind ->
                                TextButton(onClick = { act { controller.prepareSet(kind, state) } },
                                    enabled = underlayCurrent() && own?.kind != kind) { Text(checkNotNull(reactionKindLabel(kind))) }
                            }
                        } else Text("New reaction choices are unavailable in this view.")
                        if (own != null) TextButton(onClick = { act { controller.prepareRemove(state) } },
                            enabled = underlayCurrent()) { Text("Review removing my reaction") }
                    }
                    if (receipt != null) {
                        Text("This is an acknowledgement of the original request, not current reaction counts or fresh post access. Close and explicitly refresh the source for current status.")
                        if (canRemoveAcknowledged) TextButton(onClick = { act { controller.prepareRemove(state) } },
                            enabled = underlayCurrent() && !busy) { Text("Review removal of acknowledged reaction") }
                    }
                    if (pending != null) {
                        Text("The original request is retained. Nothing retries automatically, and Back does not undo a request already sent.")
                        if (!busy) TextButton(onClick = { if (underlayCurrent()) visit.change(more = true) }) {
                            Text("More · retained reaction")
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = ::back, enabled = attached()) { Text("Back") } })
        if (visit.more && moreCurrent()) AlertDialog(onDismissRequest = ::closeMore,
            title = { Text("Retained reaction") }, text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (pending?.action == ReactionAction.REMOVE) "Original action: remove your reaction."
                        else "Original reaction: ${pending?.kind?.let(::reactionKindLabel) ?: "unavailable"}.")
                    Text("Recovery uses the same account, post, request and any original reaction version. It does not submit another choice.")
                    if (pending?.receiptReady == true) TextButton(onClick = {
                        act(::moreCurrent) { controller.recoverReceipt(state) }
                    }, enabled = !busy) { Text("Recover confirmed receipt") }
                    if (pending?.canRetry == true) TextButton(onClick = {
                        act(::moreCurrent) { controller.prepareRetry(state) }
                    }, enabled = !busy) { Text("Review exact retry") }
                    Text("A conflict or missing post does not prove the original outcome. No newer version or replacement request is sent automatically.")
                }
            }, confirmButton = { TextButton(onClick = ::closeMore) { Text("Close actions") } })
    }
}

private class ReactionVisit {
    var attached = true
        private set
    var epoch by mutableStateOf<Any>(Any())
        private set
    var more by mutableStateOf(false)
        private set
    fun change(more: Boolean = false) { this.more = more; epoch = Any() }
    fun retire() { attached = false; change() }
}

private fun reactionNotice(state: ReactionState): String = when {
    state.phase == ReactionPhase.UNAVAILABLE -> "This account session is unavailable. Private reaction details are hidden."
    state.phase == ReactionPhase.LOADING -> "Checking this local selection and any retained reaction request…"
    state.phase == ReactionPhase.UPDATING -> "Keeping and sending your confirmed request. No success is assumed yet."
    state.phase == ReactionPhase.COMPLETE && state.receipt != null -> if (state.receipt!!.action == ReactionAction.REMOVE)
        "The server acknowledged the original request to remove your reaction."
        else "The server acknowledged your original ${state.receipt!!.kind?.let(::reactionKindLabel) ?: "reaction"} request."
    state.pending != null -> "A retained reaction request needs attention. Review its exact original or recover an available receipt."
    state.phase == ReactionPhase.EXPIRED -> "This source observation expired. Go Back and explicitly reopen the source before choosing a new reaction."
    state.phase == ReactionPhase.REVIEW -> "Review the exact reaction before confirming."
    state.phase == ReactionPhase.READY && state.post == null -> "No retained reaction was found. Close and select an actual post to start a new reaction."
    state.phase == ReactionPhase.READY -> "No reaction is sent by opening this view."
    else -> "No reaction outcome is confirmed. Back keeps any retained original."
}

private fun reactionFailure(reason: FailureReason?): String? = when (reason) {
    null -> null
    FailureReason.OUTCOME_UNKNOWN, FailureReason.STORAGE_FAILURE -> "The outcome is not confirmed. Keep the original and use its recovery controls."
    FailureReason.OFFLINE -> "Reconnect before reviewing an exact retry. Nothing sends automatically."
    FailureReason.CONFLICT -> "The reaction or original changed. No replacement is submitted automatically."
    FailureReason.FORBIDDEN, FailureReason.NOT_FOUND -> "This action is unavailable. That does not confirm a previous request's outcome."
    FailureReason.RATE_LIMITED -> "Please wait before trying the exact original again."
    FailureReason.STALE_SESSION, FailureReason.UNAUTHENTICATED -> "This account session is no longer available."
    FailureReason.NOT_CONFIGURED -> "Reactions are not connected for this session."
    else -> "The reaction outcome could not be confirmed. No success is assumed."
}
