package com.feedme.app.circles

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feedme.app.blueprint.BlueprintConfirmationScreen
import com.feedme.app.blueprint.BlueprintConfirmationState
import com.feedme.app.mealflow.MealFlowHostActions
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.mealflow.circles.*
import com.feedme.sync.CommandPhase

/** Original confirmation design. Navigation selects a real member; only an explicit
 * reviewed confirmation sends the ownership command. The parent owns departure. */
@Composable
fun FeedMeCircleOwnershipTransferFlow(controller: CircleOwnershipTransferController,
    onClose: () -> Unit, platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
    hostIsCurrent: () -> Boolean = { true }) {
    val state by controller.states.collectAsState()
    val scope = rememberCoroutineScope()
    val currentHost by rememberUpdatedState(hostIsCurrent)
    val currentClose by rememberUpdatedState(onClose)
    val host = remember(controller) { MealFlowHostActions { currentHost() } }
    DisposableEffect(host) { onDispose { host.retire() } }
    var running by remember(controller) { mutableStateOf(false) }
    var localFailure by remember(controller) { mutableStateOf<FailureReason?>(null) }
    val busy = running || state.phase == CircleOwnershipTransferPhase.BUSY
    fun current() = host.isCurrent() && controller.isCurrent(state)
    fun act(action: suspend () -> PortResult<CircleOwnershipTransferState>) {
        if (!current() || busy) return
        running = true; localFailure = null
        val job = host.launch(scope) {
            try {
                if (!controller.isCurrent(state)) return@launch
                val result = host.await(action)
                host.run { localFailure = (result as? PortResult.Failure)?.reason }
            // An acknowledged transfer can retire sibling observations and therefore
            // this render's host. Local busy cleanup must still run after that handoff.
            } finally { running = false }
        }
        if (job == null) running = false
    }
    fun back() {
        if (!host.isCurrent() || controller.states.value !== state) return
        if (state.screen == CircleOwnershipTransferScreen.REVIEW && !busy && current())
            act { controller.backFromReview(state) }
        else currentClose()
    }
    platformBackHandler(true, ::back)
    val target = state.target
    val review = state.review
    val affected = target?.let {
        "${it.memberName?.takeIf(String::isNotBlank) ?: "Account ${it.userId}"} · " +
            "${it.circleName?.takeIf(String::isNotBlank) ?: "Circle ${it.circleId}"}\n" +
            "New owner: ${it.userId}\nCircle version: ${it.circleVersion}"
    }
    val status = buildList {
        transferFailureCopy(localFailure ?: state.failureReason)?.let(::add)
        when {
            busy -> add("Working on this exact request. Going back cannot undo a transfer already accepted by the server.")
            state.phase == CircleOwnershipTransferPhase.UNAVAILABLE -> add("Your session is unavailable. Private circle details are hidden.")
            state.acknowledged -> add("Transfer confirmed. You are now a member. Refresh the circle to see current roles.")
            review?.kind == CircleOwnershipTransferReviewKind.RETRY_ORIGINAL -> add("Retry keeps the original circle, new owner, version and request key. It does not make a replacement request.")
            state.pending != null -> add("This request’s outcome is not yet confirmed. Keep the original for recovery below.")
            state.phase == CircleOwnershipTransferPhase.EXPIRED -> add("This review expired. Return to the circle and refresh before selecting again.")
            state.screen == CircleOwnershipTransferScreen.REVIEW -> add("Nothing transfers until you choose Transfer ownership now.")
            target != null -> add("Review this member and the change to your role before continuing.")
            else -> add("Choose an active member from your circle, or restore a retained request below.")
        }
    }.joinToString("\n\n")
    val page = BlueprintConfirmationState(actionLabel = target?.let { "Transfer circle ownership" },
        affectedSummary = affected, consequences = if (target == null) emptyList() else listOf(
            "The selected member becomes the circle’s owner. You become a member, not an admin.",
            "You cannot reverse this yourself. The new owner controls future ownership changes.",
            "This does not delete anyone’s account or posts."),
        canConfirm = current() && !busy && target != null && !state.acknowledged &&
            (state.screen == CircleOwnershipTransferScreen.REVIEW && review != null || state.pending == null),
        canCancel = host.isCurrent(), busy = busy, status = status)
    BlueprintConfirmationScreen(page,
        onConfirm = { expected -> if (expected === page && page.confirmEnabled && current()) act {
            when {
                state.screen != CircleOwnershipTransferScreen.REVIEW -> controller.prepareTransfer(state)
                review?.kind == CircleOwnershipTransferReviewKind.TRANSFER -> controller.confirmTransfer(review)
                review?.kind == CircleOwnershipTransferReviewKind.RETRY_ORIGINAL -> controller.retryOriginal(review)
                else -> PortResult.Failure(FailureReason.CONFLICT)
            }
        } }, onCancel = { expected -> if (expected === page && page.cancelEnabled) back() },
        heading = when {
            state.acknowledged -> "Ownership\ntransferred."
            state.pending != null && review == null -> "Keep your\noriginal request."
            else -> "A new owner.\nYour choice."
        }, confirmLabel = when {
            state.screen != CircleOwnershipTransferScreen.REVIEW -> "Review ownership transfer"
            review?.kind == CircleOwnershipTransferReviewKind.RETRY_ORIGINAL -> "Retry original transfer"
            else -> "Transfer ownership now"
        }, reviewLinks = {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.pending != null && state.screen != CircleOwnershipTransferScreen.REVIEW) {
                    if (state.pending!!.phase == CommandPhase.RECEIPT_READY)
                        TextButton(onClick = { act { controller.recoverReceipt(state) } }, enabled = current() && !busy) { Text("Recover confirmed receipt") }
                    else TextButton(onClick = { act { controller.prepareRetry(state) } }, enabled = current() && !busy) { Text("Review original request") }
                }
                if (target == null && state.phase != CircleOwnershipTransferPhase.UNAVAILABLE)
                    TextButton(onClick = { act { controller.open() } }, enabled = current() && !busy) { Text("Restore retained request") }
                if (busy || state.acknowledged) TextButton(onClick = ::back, enabled = host.isCurrent()) {
                    Text(if (busy) "Back · keep any sent request" else "Back to circles")
                }
            }
        })
}

private fun transferFailureCopy(reason: FailureReason?): String? = when (reason) {
    null -> null
    FailureReason.OFFLINE -> "You’re offline. Nothing retries automatically."
    FailureReason.OUTCOME_UNKNOWN -> "The outcome is uncertain. Keep the original request."
    FailureReason.UNAUTHENTICATED, FailureReason.STALE_SESSION -> "Your account session is no longer available."
    FailureReason.CONFLICT -> "The circle, selection or request changed. Do not assume ownership transferred."
    FailureReason.FORBIDDEN -> "You cannot transfer ownership to this member."
    FailureReason.RATE_LIMITED -> "Please wait before trying the original request again."
    else -> "Transfer was not confirmed. Any retained original remains available for recovery."
}
