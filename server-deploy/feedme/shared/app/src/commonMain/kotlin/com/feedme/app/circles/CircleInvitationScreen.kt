package com.feedme.app.circles

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.feedme.app.*
import com.feedme.core.ports.*
import com.feedme.mealflow.circles.*
import kotlinx.coroutines.launch

/** Public read-only route. Mounting, recreation and an optional real login callback never
 * redeem a token. The owning app explicitly calls openLink; no URL/token is a UI field. */
@Composable
fun FeedMeCircleInvitationPreviewFlow(controller: CircleInvitationPreviewController,
    onReviewJoining: ((CircleInvitationPreviewState) -> Unit)? = null,
    onSignIn: ((CircleInvitationSelection) -> Unit)? = null,
    requiresSignIn: Boolean = true,
    handoffBusy: Boolean = false, handoffFailure: FailureReason? = null,
    blueprintIsCurrent: (() -> Boolean)? = null,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit) {
    val state by controller.states.collectAsState()
    val expected = state
    val scope = rememberCoroutineScope()
    val blueprintHost by rememberUpdatedState(blueprintIsCurrent)
    fun current() = controller.states.value === expected && blueprintHost?.invoke() != false
    var tools by remember(controller, state) { mutableStateOf(false) }
    val back = { if (current()) { if (tools) tools = false else scope.launch { if (current()) controller.back(expected) } }; Unit }
    platformBackHandler(state.screen != CircleInvitationPreviewScreen.HIDDEN, back)
    if (state.screen == CircleInvitationPreviewScreen.HIDDEN) return
    val selection = state.selection
    val display = state.preview?.let { circlePublicPreviewDisplay(state.phase, selection?.isCurrentForNavigation == true, it.targetName, it.inviterLabel) }
    if (blueprintHost != null && display != null && handoffFailure == null) {
        RetainedInvitationBlueprint(display, primaryEnabled = !handoffBusy && (onReviewJoining != null || requiresSignIn && onSignIn != null),
            requiresSignIn = onReviewJoining == null && requiresSignIn, busy = handoffBusy,
            onPrimary = {
                if (current() && !handoffBusy && selection?.isCurrentForNavigation == true) {
                    if (onReviewJoining != null) onReviewJoining(expected)
                    else if (requiresSignIn) onSignIn?.invoke(selection)
                }
            }, onBack = back, onMore = { if (current()) tools = true })
        if (tools) FeedMeTheme {
            AlertDialog(onDismissRequest = { tools = false }, title = { Text("Invitation details") },
                text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.preview?.let { InvitationSummary(it) }
                    Text(CIRCLE_INVITATION_PUBLIC_NOTICE); Text(CIRCLE_INVITATION_AUDIENCE); Text(CIRCLE_INVITATION_HISTORY)
                    Text("Not this time only closes this invitation on this device. It does not notify the sender.")
                    TextButton(onClick = { if (current() && !handoffBusy) { tools = false; scope.launch { if (current()) controller.refresh(expected) } } }, enabled = !handoffBusy) { Text("Refresh invitation") }
                } }, confirmButton = { TextButton(onClick = { tools = false }) { Text("Close details") } })
        }
        return
    }
    InvitationFrame(back, "Back") {
        when (state.phase) {
            CircleInvitationPreviewPhase.LOADING -> {
                InvitationNotice("Checking invitation", "Only the public preview is being read. You can go back while we check.")
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            CircleInvitationPreviewPhase.UNAVAILABLE -> InvitationNotice("Invitation unavailable", CIRCLE_INVITATION_UNAVAILABLE)
            CircleInvitationPreviewPhase.OFFLINE -> InvitationNotice("You’re offline", "Reconnect to check this invitation. A previous preview cannot confirm that joining is available.")
            CircleInvitationPreviewPhase.ERROR -> InvitationNotice("Couldn’t check the invitation", circleInvitationFailureText(state.failureReason).orEmpty())
            CircleInvitationPreviewPhase.IDLE -> InvitationNotice("Ready when you are", "Open an invitation link from someone you know. No invitation is accepted here automatically.")
            CircleInvitationPreviewPhase.READY -> {
                state.preview?.let { preview ->
                    InvitationSummary(preview)
                    Text(CIRCLE_INVITATION_PUBLIC_NOTICE, style = MaterialTheme.typography.bodyMedium)
                    Text(CIRCLE_INVITATION_AUDIENCE, style = MaterialTheme.typography.bodyMedium)
                    Text(CIRCLE_INVITATION_HISTORY, style = MaterialTheme.typography.bodyMedium)
                    val selection = expected.selection
                    if (selection != null && selection.isCurrentForNavigation) {
                        if (onReviewJoining != null) {
                            Text(CIRCLE_INVITATION_ACCOUNT_NOTICE, style = MaterialTheme.typography.bodyMedium)
                            InvitationPrimary("Review joining", !handoffBusy) {
                                if (current() && selection.isCurrentForNavigation) onReviewJoining(expected)
                            }
                        } else if (requiresSignIn) {
                            Text(CIRCLE_INVITATION_SIGN_IN_NOTICE, style = MaterialTheme.typography.bodyMedium)
                            if (onSignIn != null) InvitationPrimary("Sign in to review", !handoffBusy) {
                                if (current() && selection.isCurrentForNavigation) onSignIn(selection)
                            } else Text(CIRCLE_INVITATION_LOGIN_UNAVAILABLE, style = MaterialTheme.typography.bodyMedium)
                        } else InvitationNotice("Joining unavailable", "Joining invitations is not connected for this account session.")
                    } else InvitationNotice("Preview changed", "Refresh the invitation before reviewing. Nothing has been joined.")
                }
            }
        }
        if (handoffBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        handoffFailure?.let { InvitationNotice("Couldn’t open account review", circleInvitationFailureText(it).orEmpty()) }
        if (state.phase in setOf(CircleInvitationPreviewPhase.READY, CircleInvitationPreviewPhase.OFFLINE)) InvitationSecondary("Refresh invitation", !handoffBusy) {
            if (current()) scope.launch { if (current()) controller.refresh(expected) }
        }
        InvitationSecondary("Decline", true, back)
        Text("Decline only closes this invitation on this device. It does not notify the sender.", style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
    }
}

/** Actual account-bound owner. No open/restore/ID/send effect is tied to composition.
 * Back is local and remains available during a held request; pending originals are preserved. */
@Composable
fun FeedMeCircleInvitationFlow(controller: CircleInvitationController,
    onOpenCircle: ((CircleInvitationState) -> Unit)? = null,
    onUseInvitation: ((CircleInvitationState) -> Unit)? = null,
    handoffBusy: Boolean = false, handoffFailure: FailureReason? = null,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit) {
    val state by controller.states.collectAsState()
    val expected = state
    val scope = rememberCoroutineScope()
    var running by remember(controller) { mutableStateOf(false) }
    var failure by remember(controller) { mutableStateOf<Pair<CircleInvitationState, FailureReason>?>(null) }
    var replace by remember(controller) { mutableStateOf<CircleInvitationState?>(null) }
    fun act(action: suspend () -> PortResult<CircleInvitationState>) {
        if (running || handoffBusy || !circleInvitationCanAct(expected.screen, expected.phase) || controller.states.value !== expected) return
        running = true; failure = null
        scope.launch {
            try {
                if (controller.states.value === expected) {
                    val result = action()
                    if (result is PortResult.Failure && controller.states.value === expected) failure = expected to result.reason
                }
            } finally { running = false }
        }
    }
    val back = { if (replace != null) replace = null else scope.launch { controller.back(expected) }; Unit }
    platformBackHandler(state.screen != CircleInvitationScreen.HIDDEN, back)
    if (state.screen == CircleInvitationScreen.HIDDEN) return
    InvitationFrame(back, if (state.screen == CircleInvitationScreen.REVIEW) "Back to invitation" else "Back") {
        if (state.phase == CircleInvitationPhase.UNAVAILABLE) {
            InvitationNotice("Account unavailable", "Private invitation details are hidden. Go back and check your account connection.")
        } else {
            circleInvitationFailureText(failure?.takeIf { it.first === state }?.second ?: state.failureReason)
                ?.let { InvitationNotice("Couldn’t confirm that", it) }
            handoffFailure?.let { InvitationNotice("Couldn’t open the circle", circleInvitationFailureText(it).orEmpty()) }
            if (running || handoffBusy || state.phase == CircleInvitationPhase.BUSY) LinearProgressIndicator(Modifier.fillMaxWidth())
            val enabled = !running && !handoffBusy && circleInvitationCanAct(state.screen, state.phase)
            if (state.screen == CircleInvitationScreen.REVIEW) {
                val review = state.review
                if (review != null && circleInvitationReviewVisible(state.screen, state.phase, review.isCurrentForNavigation)) {
                    Text(circleInvitationReviewTitle(review.kind), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    InvitationSummary(review.preview)
                    Text(review.consentText, style = MaterialTheme.typography.bodyMedium)
                    Text(circleInvitationReviewNotice(review.kind), style = MaterialTheme.typography.bodyMedium)
                    review.original?.let { Text(circleInvitationPendingText(it), style = MaterialTheme.typography.bodyMedium) }
                    InvitationPrimary(circleInvitationConfirmLabel(review.kind), enabled) {
                        if (review.isCurrentForNavigation) act {
                            if (controller.states.value !== expected || expected.review !== review || !review.isCurrentForNavigation)
                                PortResult.Failure(FailureReason.CONFLICT)
                            else when (review.kind) {
                                CircleInvitationReviewKind.ACCEPT -> controller.confirmAccept(review)
                                CircleInvitationReviewKind.RETRY_ORIGINAL -> controller.retryOriginal(review)
                                CircleInvitationReviewKind.DISCARD_UNSENT -> controller.discardUnsent(review)
                                CircleInvitationReviewKind.ACKNOWLEDGE_COMPLETION -> controller.confirmCompletion(review)
                            }
                        }
                    }
                } else InvitationNotice("Review changed", "Go back and review again. This older view cannot confirm joining.")
            } else {
                state.result?.let { result ->
                    InvitationNotice(if (result.acknowledged) "Join request confirmed" else "Retained join result",
                        if (result.acknowledged) "Your original join request is confirmed on this device. Open the circle to check current access."
                        else "This result was saved earlier. Confirm it here before opening the circle to check current access.")
                    state.preview?.let { InvitationSummary(it) }
                    if (onOpenCircle != null && result.isCurrentForNavigation) InvitationPrimary("Open circle", enabled) {
                        if (controller.states.value === expected && result.isCurrentForNavigation) onOpenCircle(expected)
                    }
                }
                if (state.completionAcknowledged && state.pending == null && state.result == null)
                    InvitationNotice("Unsent join request discarded", "No join was sent by that original request. The sender’s invitation has not been revoked.")
                state.nextPreview?.let { next ->
                    Text("Previewed invitation", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    InvitationSummary(next)
                    if (state.completionAcknowledged && state.pending == null && onUseInvitation != null)
                        InvitationSecondary("Review this invitation instead", enabled) { replace = expected }
                    else Text("Finish the original request or confirm its retained result first. This invitation does not replace it.",
                        style = MaterialTheme.typography.bodyMedium)
                }
                if (state.completionPending) {
                    Text("A retained result needs an explicit confirmation on this device. This does not send another join request.", style = MaterialTheme.typography.bodyMedium)
                    InvitationPrimary("Review retained result", enabled) { act { controller.reviewCompletion(expected) } }
                }
                state.pending?.let { original ->
                    InvitationNotice("Original join request", circleInvitationPendingText(original))
                    InvitationSummary(original.preview)
                    Text("Only this original invitation is retried. A different link does not replace it.", style = MaterialTheme.typography.bodyMedium)
                    InvitationPrimary("Review original join request", enabled) { act { controller.reviewOriginal(expected) } }
                    if (original.canDiscardUnsent) InvitationSecondary("Review unsent discard", enabled) {
                        act { controller.reviewDiscardUnsent(expected) }
                    }
                }
                if (state.pending == null && state.result == null && !state.completionPending && !state.completionAcknowledged) {
                    val preview = state.preview
                    if (preview == null) InvitationNotice("No retained join request", "Open an invitation link to check its public preview. Nothing has been joined automatically.")
                    else {
                        InvitationSummary(preview)
                        Text(CIRCLE_INVITATION_ACCOUNT_NOTICE, style = MaterialTheme.typography.bodyMedium)
                        Text(CIRCLE_INVITATION_AUDIENCE, style = MaterialTheme.typography.bodyMedium)
                        Text(CIRCLE_INVITATION_HISTORY, style = MaterialTheme.typography.bodyMedium)
                        InvitationPrimary("Review joining", enabled) { act { controller.reviewAccept(expected) } }
                    }
                }
                Text(CIRCLE_INVITATION_SCOPE_NOTICE, style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
            }
        }
    }
    replace?.takeIf { it === state && state.phase != CircleInvitationPhase.UNAVAILABLE }?.let { selected ->
        FeedMeTheme {
            AlertDialog(onDismissRequest = { replace = null },
                title = { Text("Review this invitation instead?") },
                text = { Text("The completed request stays completed. Use the previewed invitation for a new review. You still need to choose Join circle separately.") },
                confirmButton = { TextButton(onClick = {
                    replace = null
                    if (controller.states.value === selected && selected.completionAcknowledged && selected.pending == null)
                        onUseInvitation?.invoke(selected)
                }) { Text("Review invitation") } },
                dismissButton = { TextButton(onClick = { replace = null }) { Text("Keep current result") } })
        }
    }
}

@Composable
private fun InvitationFrame(back: () -> Unit, backLabel: String, content: @Composable ColumnScope.() -> Unit) {
    FeedMeTheme {
        Box(Modifier.fillMaxSize().background(FeedMeColors.Paper).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = back, modifier = Modifier.heightIn(min = 48.dp)) { Text(backLabel) }
                FeedMeWordmark(compact = true)
                Text(CIRCLE_INVITATION_TITLE, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                content()
            }
        }
    }
}
@Composable
private fun InvitationSummary(preview: CircleInvitationPreview) {
    Surface(color = FeedMeColors.Surface, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Circle", style = MaterialTheme.typography.labelMedium, color = FeedMeColors.Muted)
            Text(circleInvitationDisplay(preview.targetName, "Circle name unavailable", "Circle name left blank"), style = MaterialTheme.typography.titleLarge)
            Text("Invited by", style = MaterialTheme.typography.labelMedium, color = FeedMeColors.Muted)
            Text(circleInvitationDisplay(preview.inviterLabel, "Inviter unavailable", "Inviter label left blank"), style = MaterialTheme.typography.bodyLarge)
            Text("Invitation expires", style = MaterialTheme.typography.labelMedium, color = FeedMeColors.Muted)
            Text(circleInvitationExpiry(preview.expiresAt), style = MaterialTheme.typography.bodyMedium)
            (preview.expiresAt as? com.feedme.contracts.WireField.Value)?.let { source ->
                FeedMeDetails("Exact expiry") { Text(source.value, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
@Composable private fun InvitationNotice(title: String, message: String) {
    Surface(color = FeedMeColors.SoftBlue, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
@Composable private fun InvitationPrimary(label: String, enabled: Boolean, action: () -> Unit) {
    Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(label) }
}
@Composable private fun InvitationSecondary(label: String, enabled: Boolean, action: () -> Unit) {
    OutlinedButton(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(label) }
}
