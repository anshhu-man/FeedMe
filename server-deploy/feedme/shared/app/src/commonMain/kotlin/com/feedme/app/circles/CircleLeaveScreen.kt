package com.feedme.app.circles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.feedme.app.*
import com.feedme.core.ports.*
import com.feedme.mealflow.circles.*
import kotlinx.coroutines.launch

/** Mounting never restores/sends. All callbacks bind the rendered state; Back stays available
 * during a held request and preserves exact-original recovery. No destructive default action. */
@Composable
fun FeedMeCircleLeaveFlow(controller: CircleLeaveController,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
    hostIsCurrent: () -> Boolean = { true }) {
    val state by controller.states.collectAsState()
    val expected = state
    val scope = rememberCoroutineScope()
    val currentHost by rememberUpdatedState(hostIsCurrent)
    var attached by remember(controller) { mutableStateOf(true) }
    DisposableEffect(controller) { onDispose { attached = false } }
    fun current() = attached && currentHost() && controller.states.value === expected
    var running by remember(controller) { mutableStateOf(false) }
    fun act(action: suspend () -> PortResult<CircleLeaveState>) {
        if (running || !current() || !circleLeaveCanAct(expected)) return
        running = true
        scope.launch { try { if (current()) action() } finally { if (attached && currentHost()) running = false } }
    }
    val back = { if (current()) scope.launch { if (current()) controller.back(expected) }; Unit }
    platformBackHandler(current() && state.screen != CircleLeaveScreen.HIDDEN, back)
    if (state.screen == CircleLeaveScreen.HIDDEN) return
    if (state.screen == CircleLeaveScreen.REVIEW) {
        RetainedCircleLeaveConfirmation(expected, running || state.phase == CircleLeavePhase.BUSY,
            isCurrent = ::current, onConfirm = { review -> act {
                if (expected.review !== review || !review.isCurrentForNavigation) PortResult.Failure(FailureReason.CONFLICT)
                else when (review.kind) {
                    CircleLeaveReviewKind.LEAVE, CircleLeaveReviewKind.DELETE -> controller.confirmLeave(review)
                    CircleLeaveReviewKind.RETRY_ORIGINAL -> controller.retryOriginal(review)
                    CircleLeaveReviewKind.DISCARD_UNSENT -> controller.discardUnsent(review)
                    CircleLeaveReviewKind.ACKNOWLEDGE_COMPLETION -> controller.confirmCompletion(review)
                }
            } }, onBack = back)
        return
    }
    FeedMeTheme {
        Column(Modifier.fillMaxSize().background(FeedMeColors.Paper).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = back, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
            FeedMeWordmark(compact = true)
            Text("Your circle. Your choice.", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            if (state.phase == CircleLeavePhase.UNAVAILABLE) {
                Text("This account session is unavailable. Private circle details are hidden.")
            } else {
                state.circle?.let { circle ->
                    Surface(color = FeedMeColors.Surface, shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Circle", style = MaterialTheme.typography.labelMedium)
                            Text(circle.name.ifEmpty { "Unnamed circle" }, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
                circleLeaveFailureText(state.failureReason, state.action)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (running || state.phase == CircleLeavePhase.BUSY) LinearProgressIndicator(Modifier.fillMaxWidth())
                val enabled = !running && circleLeaveCanAct(state)
                if (state.screen == CircleLeaveScreen.REVIEW) {
                    val review = state.review
                    if (review == null || !review.isCurrentForNavigation) Text("This review changed. Go back and review again.")
                    else {
                        Text(review.consentText)
                        Text(circleLeaveReviewNotice(review.kind))
                        Button(onClick = { act {
                            if (controller.states.value !== expected || expected.review !== review || !review.isCurrentForNavigation)
                                PortResult.Failure(FailureReason.CONFLICT)
                            else when (review.kind) {
                                CircleLeaveReviewKind.LEAVE, CircleLeaveReviewKind.DELETE -> controller.confirmLeave(review)
                                CircleLeaveReviewKind.RETRY_ORIGINAL -> controller.retryOriginal(review)
                                CircleLeaveReviewKind.DISCARD_UNSENT -> controller.discardUnsent(review)
                                CircleLeaveReviewKind.ACKNOWLEDGE_COMPLETION -> controller.confirmCompletion(review)
                            }
                        } }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Text(circleLeaveConfirmLabel(review.kind))
                        }
                        OutlinedButton(onClick = back, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Keep my place for now") }
                    }
                } else {
                    state.result?.let { Text(if (state.action == CircleDepartureAction.DELETE) {
                        if (it.acknowledged) "This circle was deleted. Your account and posts retained for another audience were not deleted."
                        else "A successful deletion reply is retained. Review it before confirming the result on this device."
                    } else if (it.acknowledged) "You left this circle. Your posts and account were not deleted."
                        else "A successful leave reply is retained. Review it before confirming the result on this device.") }
                    if (state.completionAcknowledged && state.result == null) Text("Unsent request discarded. Your membership was not changed.")
                    if (state.completionPending) Button(onClick = { act { controller.reviewCompletion(expected) } }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Review retained result") }
                    state.pending?.let { pending ->
                        Text(circleCreatePendingText(pending.queuePhase, pending.attempts, pending.recoveryRequired))
                        Text("Only the original circle and request can be recovered. Nothing retries automatically.")
                        Button(onClick = { act { controller.reviewOriginal(expected) } }, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Review original request") }
                        if (pending.canDiscardUnsent) OutlinedButton(onClick = { act { controller.reviewDiscardUnsent(expected) } }, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Review unsent discard") }
                    }
                    if (state.nextCircle != null && state.completionAcknowledged) {
                        Text("Selected next: ${state.nextCircle!!.name}")
                        OutlinedButton(onClick = { act { controller.startNew(expected) } }, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Use selected circle for a new review") }
                    }
                    if (state.pending == null && state.result == null && !state.completionPending && !state.completionAcknowledged) {
                        if (state.circle == null) Text(if (state.action == CircleDepartureAction.DELETE)
                            "No deletion request is retained. Open an owned circle to review deleting it."
                            else "No leave request is retained. Open a circle to review leaving it.")
                        else {
                            Text(if (state.action == CircleDepartureAction.DELETE) CIRCLE_DELETE_CONSENT else CIRCLE_LEAVE_CONSENT)
                            OutlinedButton(onClick = { act { controller.reviewLeave(expected) } }, enabled = enabled,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                                Text(if (state.action == CircleDepartureAction.DELETE) "Review deletion" else "Review leaving")
                            }
                        }
                    }
                }
            }
        }
    }
}
