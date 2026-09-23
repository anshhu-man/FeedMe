package com.feedme.app.circles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/** Original CONFIRM_ACTION, backed by one actual selected member and its retained command.
 * No reads/writes on mount; the root retains the controller and owns departure exactly once. */
@Composable
fun FeedMeCircleMemberRemovalFlow(controller: CircleMemberRemovalController, onClose: () -> Unit,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
    hostIsCurrent: () -> Boolean = { true }) {
    val state by controller.states.collectAsState()
    val scope = rememberCoroutineScope()
    val currentHost by rememberUpdatedState(hostIsCurrent)
    val currentClose by rememberUpdatedState(onClose)
    val host = remember(controller) { MealFlowHostActions { currentHost() } }
    DisposableEffect(host) { onDispose { host.retire() } }
    var running by remember(controller) { mutableStateOf(false) }
    var localFailure by remember(controller) { mutableStateOf<FailureReason?>(null) }
    val busy = running || state.phase == CircleMemberRemovalPhase.BUSY
    fun current() = host.isCurrent() && controller.isCurrent(state)
    fun act(action: suspend () -> PortResult<CircleMemberRemovalState>) {
        if (!current() || busy) return
        running = true; localFailure = null
        val job = host.launch(scope) {
            try {
                if (!controller.isCurrent(state)) return@launch
                val result = host.await(action)
                host.run { localFailure = (result as? PortResult.Failure)?.reason }
            } finally { if (host.isCurrent() && currentCoroutineContext().isActive) running = false }
        }
        if (job == null) running = false
    }
    fun back() {
        if (!host.isCurrent() || controller.states.value !== state) return
        if (state.screen == CircleMemberRemovalScreen.REVIEW && !busy && current())
            act { controller.backFromReview(state) }
        else currentClose()
    }
    // A mounted root destination always has local Back, even before open reaches its dispatcher
    // or when an unavailable owner cannot perform any private work.
    platformBackHandler(true, ::back)
    val target = state.target
    val review = state.review
    val selection = target?.let {
        val member = it.memberName?.takeIf(String::isNotBlank) ?: "Account ${it.userId}"
        val circle = it.circleName?.takeIf(String::isNotBlank) ?: "Circle ${it.circleId}"
        "$member from $circle\nAccount: ${it.userId}\nCircle: ${it.circleId}\nMember version: ${it.memberVersion}"
    }
    val status = buildList {
        removalFailureCopy(localFailure ?: state.failureReason)?.let(::add)
        when {
            busy -> add("Keeping and sending only the exact confirmed request. Leaving does not cancel an operation already received by the server.")
            state.phase == CircleMemberRemovalPhase.UNAVAILABLE -> add("This account session is unavailable. Private membership details are hidden.")
            state.acknowledged -> add("The server confirmed this membership removal and the receipt is acknowledged on this device. This is not a fresh roster or permission to read the circle.")
            state.screen == CircleMemberRemovalScreen.REVIEW && review?.kind == CircleMemberRemovalReviewKind.RETRY_ORIGINAL ->
                add("A previous attempt may have reached the server. Retry sends only the same circle, account, member version and request key. It never creates a replacement removal.")
            state.screen == CircleMemberRemovalScreen.REVIEW -> add("Remove member now sends this exact reviewed removal. Until then, no request is sent.")
            state.pending != null -> add("The exact original is retained. Its outcome is not assumed. Review the original request or recover its confirmed receipt below; nothing retries automatically.")
            state.phase == CircleMemberRemovalPhase.EXPIRED -> add("This confirmation expired. Review again only from a still-current selection, or return to the circle and refresh its members.")
            target != null -> add("Review this exact member before confirming removal. Roles and versions shown are observations; the server independently authorizes the command.")
            else -> add("No member is selected. Return to a current circle roster to choose a member, or explicitly restore a retained original below.")
        }
    }.joinToString("\n\n")
    val model = BlueprintConfirmationState(
        actionLabel = target?.let { "Remove circle member" }, affectedSummary = selection,
        consequences = if (target == null) emptyList() else listOf(
            "Removes only this membership. It does not delete the person’s account or posts.",
            "Circle owners cannot be removed here. Ownership transfer and leaving yourself are separate actions."),
        canConfirm = current() && !busy && target != null && !state.acknowledged &&
            (state.screen == CircleMemberRemovalScreen.REVIEW && review != null || state.pending == null),
        canCancel = host.isCurrent(), busy = busy, status = status)
    BlueprintConfirmationScreen(model,
        onConfirm = { expected ->
            if (expected === model && model.confirmEnabled && current()) act {
                when {
                    state.screen != CircleMemberRemovalScreen.REVIEW -> controller.prepareRemoval(state)
                    review?.kind == CircleMemberRemovalReviewKind.REMOVE -> controller.confirmRemoval(review)
                    review?.kind == CircleMemberRemovalReviewKind.RETRY_ORIGINAL -> controller.retryOriginal(review)
                    else -> PortResult.Failure(FailureReason.CONFLICT)
                }
            }
        }, onCancel = { expected -> if (expected === model && model.cancelEnabled) back() },
        heading = when {
            state.acknowledged -> "Membership\nremoved."
            state.pending != null && review == null -> "Keep the\noriginal request."
            else -> "Remove this\ncircle member?"
        }, confirmLabel = when {
            state.screen != CircleMemberRemovalScreen.REVIEW -> "Review member removal"
            review?.kind == CircleMemberRemovalReviewKind.RETRY_ORIGINAL -> "Retry original removal"
            else -> "Remove member now"
        }, reviewLinks = {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.pending != null && state.screen != CircleMemberRemovalScreen.REVIEW) {
                    if (state.pending!!.phase == CommandPhase.RECEIPT_READY) {
                        TextButton(onClick = { act { controller.recoverReceipt(state) } }, enabled = current() && !busy) { Text("Recover confirmed receipt") }
                    } else {
                        TextButton(onClick = { act { controller.prepareRetry(state) } }, enabled = current() && !busy) { Text("Review original request") }
                    }
                }
                if (target == null && state.phase != CircleMemberRemovalPhase.UNAVAILABLE) {
                    TextButton(onClick = { act { controller.open() } }, enabled = current() && !busy) { Text("Restore retained request") }
                }
                // Original scaffold disables its Cancel while busy. This explicit local Back
                // remains available and never claims to undo a possibly submitted command.
                if (busy || state.acknowledged) TextButton(onClick = ::back, enabled = host.isCurrent()) {
                    Text(if (busy) "Back · keep any sent request" else "Back to circles")
                }
            }
        })
}

private fun removalFailureCopy(reason: FailureReason?): String? = when (reason) {
    null -> null
    FailureReason.OFFLINE -> "You’re offline. No automatic retry runs when connectivity returns."
    FailureReason.OUTCOME_UNKNOWN -> "The outcome is uncertain. Keep this original request for exact recovery."
    FailureReason.UNAUTHENTICATED, FailureReason.STALE_SESSION -> "This account session is no longer available."
    FailureReason.CONFLICT -> "The selection, review or exact original changed. Do not assume membership was removed."
    FailureReason.FORBIDDEN -> "This account cannot perform the selected removal."
    FailureReason.RATE_LIMITED -> "The server asked you to wait. Nothing retries automatically."
    else -> "Removal was not confirmed. The exact original remains the recovery source if it was retained."
}
