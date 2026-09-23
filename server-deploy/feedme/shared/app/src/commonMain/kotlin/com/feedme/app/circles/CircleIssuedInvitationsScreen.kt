package com.feedme.app.circles

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.feedme.app.*
import com.feedme.contracts.WireField
import com.feedme.core.ports.*
import com.feedme.mealflow.circles.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private class IssuedLinkView(val link: CircleIssuedInvitationLink, val state: CircleIssuedInvitationsState)

/** Explicit owner actions only: mounting never opens, issues, revokes or takes a link.
 * The platform share port belongs to this UI attachment, not to the retained session.
 * No URL enters rememberSaveable, a clipboard, logs or local invitation metadata. */
@Composable
@OptIn(ExperimentalAtomicApi::class)
fun FeedMeCircleIssuedInvitationsFlow(controller: CircleIssuedInvitationsController,
    onStartNew: ((CircleIssuedInvitationsState) -> Unit)? = null,
    newCircleName: String? = null,
    handoffBusy: Boolean = false, handoffFailure: FailureReason? = null,
    sharePort: CircleInvitationSharePort? = null,
    hostIsCurrent: () -> Boolean = { true },
    blueprintIsCurrent: (() -> Boolean)? = null,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit) {
    val state by controller.states.collectAsState()
    val expected = state
    val scope = rememberCoroutineScope()
    val currentHost by rememberUpdatedState(hostIsCurrent)
    val currentBlueprintHost by rememberUpdatedState(blueprintIsCurrent)
    fun current() = currentHost() && controller.states.value === expected
    var running by remember(controller) { mutableStateOf(false) }
    var lifetime by remember(controller) { mutableStateOf("") }
    var lifetimeError by remember(controller) { mutableStateOf(false) }
    var failure by remember(controller) { mutableStateOf<Pair<CircleIssuedInvitationsState, FailureReason>?>(null) }
    var replacing by remember(controller) { mutableStateOf<CircleIssuedInvitationsState?>(null) }
    var displayed by remember(controller) { mutableStateOf<IssuedLinkView?>(null) }
    var request by remember(controller) { mutableStateOf<CircleInvitationShareRequest?>(null) }
    var shareAttempted by remember(controller) { mutableStateOf(false) }
    var shareNotice by remember(controller) { mutableStateOf<String?>(null) }
    val linkGeneration = remember(controller) { AtomicReference(Any()) }
    fun hideLink() { linkGeneration.store(Any()); request?.invalidate(); request = null; displayed = null; shareNotice = null; shareAttempted = false }
    DisposableEffect(controller, sharePort) { onDispose { hideLink() } }
    // Clock-only presentation expiry. No restore, transport, queue or ID operation.
    LaunchedEffect(displayed) {
        val shown = displayed
        while (isActive && shown != null && displayed === shown) {
            if (!currentHost() || controller.states.value !== shown.state || !shown.link.isCurrentForNavigation) { hideLink(); break }
            delay(1000)
        }
    }
    fun act(action: suspend () -> PortResult<CircleIssuedInvitationsState>) {
        if (running || handoffBusy || !circleIssuedCanAct(expected.screen, expected.phase) || !current()) return
        running = true; failure = null; hideLink()
        scope.launch {
            try {
                if (current()) {
                    val result = action()
                    if (result is PortResult.Failure && current()) failure = expected to result.reason
                }
            } finally { running = false }
        }
    }
    fun confirmReview(review: CircleIssuedInvitationReview) {
        if (review.isCurrentForNavigation) act {
            if (controller.states.value !== expected || expected.review !== review || !review.isCurrentForNavigation)
                PortResult.Failure(FailureReason.CONFLICT)
            else when (review.kind) {
                CircleIssuedInvitationReviewKind.ISSUE -> controller.confirmIssue(review)
                CircleIssuedInvitationReviewKind.REVOKE -> controller.confirmRevoke(review)
                CircleIssuedInvitationReviewKind.RETRY_ORIGINAL -> controller.retryOriginal(review)
                CircleIssuedInvitationReviewKind.DISCARD_UNSENT -> controller.discardUnsent(review)
                CircleIssuedInvitationReviewKind.ACKNOWLEDGE_COMPLETION -> controller.confirmCompletion(review)
            }
        }
    }
    fun share(shown: IssuedLinkView) {
        val port = sharePort ?: return
        if (shareAttempted || !currentHost() || displayed !== shown || controller.states.value !== shown.state || !shown.link.isCurrentForNavigation) return
        shareAttempted = true
        val owned = CircleInvitationShareRequest(shown.link) {
            currentHost() && displayed === shown && controller.states.value === shown.state
        }
        request = owned
        scope.launch {
            try {
                val result = circleInvitationOpenChooser(port, owned)
                if (currentHost() && request === owned && displayed === shown && controller.states.value === shown.state && shown.link.isCurrentForNavigation) {
                    shareNotice = when (result) {
                        is PortResult.Failure -> "The share sheet could not be confirmed as opened. No delivery is confirmed."
                        is PortResult.Value -> when (result.value) {
                            CircleInvitationShareOutcome.OPENED -> if (owned.chooserLaunched) CIRCLE_ISSUED_NOT_DELIVERED else "Sharing did not launch from this view. No delivery is confirmed."
                            CircleInvitationShareOutcome.CANCELLED -> CIRCLE_ISSUED_SHARE_CANCELLED
                        }
                    }
                }
            } finally { owned.invalidate(); if (request === owned) request = null }
        }
    }
    fun takeLink(shareAfter: Boolean) {
        if (running || handoffBusy || !current()) return
        running = true; failure = null
        val taking = linkGeneration.load()
        scope.launch {
            try {
                if (!current()) return@launch
                when (val taken = controller.takeLink(expected)) {
                    is PortResult.Failure -> if (current()) failure = expected to taken.reason
                    is PortResult.Value -> {
                        val after = controller.states.value
                        if (currentHost() && taking === linkGeneration.load() && taken.value.isCurrentForNavigation &&
                            after.screen == CircleIssuedInvitationsScreen.HISTORY && after.completionAcknowledged) {
                            hideLink()
                            val shown = IssuedLinkView(taken.value, after)
                            displayed = shown
                            if (shareAfter) share(shown)
                        }
                    }
                }
            } finally { running = false }
        }
    }
    val back = {
        if (current()) {
            hideLink()
            if (replacing != null) replacing = null else scope.launch { if (current()) controller.back(expected) }
        }
        Unit
    }
    platformBackHandler(currentHost() && state.screen != CircleIssuedInvitationsScreen.HIDDEN, back)
    if (state.screen == CircleIssuedInvitationsScreen.HIDDEN) return
    val enabled = currentHost() && !running && !handoffBusy && circleIssuedCanAct(state.screen, state.phase)
    val currentLink = displayed?.takeIf { it.state === state && it.link.isCurrentForNavigation }
    val originalShown = currentBlueprintHost != null && handoffFailure == null && failure?.first !== state &&
        (RetainedBlueprintIssuedInvitationConfirmation(state, enabled,
            busy = running || handoffBusy || state.phase == CircleIssuedInvitationsPhase.BUSY,
            isCurrent = { current() && currentBlueprintHost?.invoke() == true },
            onConfirm = ::confirmReview, onBack = back) ||
        RetainedBlueprintIssuedInvitations(state, enabled, controller.maxLifetimeHours,
            shareAvailable = !shareAttempted && (state.linkAvailable || currentLink != null),
            shareConfigured = sharePort != null, shareNotice = shareNotice,
            canStartNew = onStartNew != null && newCircleName != null,
            isCurrent = { current() && currentBlueprintHost?.invoke() == true },
            onReviewIssue = { hours -> act { controller.reviewIssue(hours, expected) } },
            onStartNew = { if (current()) { hideLink(); replacing = expected } },
            onShare = { if (current()) { if (currentLink != null) share(currentLink) else takeLink(true) } },
            onRevoke = { issued -> act { controller.reviewRevoke(issued, expected) } }, onBack = back))
    if (!originalShown) IssuedFrame(back, if (state.screen == CircleIssuedInvitationsScreen.REVIEW) "Back to invitations" else "Back to circles") {
        if (state.phase == CircleIssuedInvitationsPhase.UNAVAILABLE) {
            IssuedNotice("Account unavailable", "Private invitation details and links are hidden. Go back and check your account connection.")
        } else {
            circleIssuedFailure(failure?.takeIf { it.first === state }?.second ?: state.failureReason)
                ?.let { IssuedNotice("Couldn’t confirm that", it) }
            handoffFailure?.let { IssuedNotice("Couldn’t open a new invitation", circleIssuedFailure(it).orEmpty()) }
            if (running || handoffBusy || state.phase == CircleIssuedInvitationsPhase.BUSY) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.screen == CircleIssuedInvitationsScreen.REVIEW) {
                val review = state.review
                if (review != null && review.isCurrentForNavigation) {
                    Text(circleIssuedReviewTitle(review.kind), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    IssuedCircle(review.circle)
                    review.expiresInHours?.let { Text("Requested lifetime: ${circleIssuedHours(it)}") }
                    review.issued?.let { IssuedMetadata(it) }
                    Text(review.consentText, style = MaterialTheme.typography.bodyMedium)
                    Text(circleIssuedReviewNotice(review.kind), style = MaterialTheme.typography.bodyMedium)
                    review.original?.let { IssuedOriginal(it) }
                    IssuedPrimary(circleIssuedConfirmLabel(review.kind), enabled) {
                        confirmReview(review)
                    }
                } else IssuedNotice("Review changed", "Go back and review again. This older view cannot confirm an action.")
            } else {
                if (state.completionAcknowledged) {
                    val title = when {
                        state.result == null -> "Unsent request discarded"
                        state.completionOperation == CircleIssuedInvitationOperation.REVOKE -> "Revocation confirmed"
                        else -> "Invite link created"
                    }
                    IssuedNotice(title, when {
                        state.result == null -> "That original request was never sent. An existing invitation has not been revoked."
                        state.completionOperation == CircleIssuedInvitationOperation.REVOKE -> "This invitation’s revocation is confirmed on this device. No existing member was removed."
                        else -> "Creation is confirmed on this device. This does not mean the link was shared or that anyone joined."
                    })
                    if (state.linkAvailable && currentLink == null) IssuedPrimary("Show invite link", enabled) {
                        takeLink(false)
                    }
                    currentLink?.let { shown ->
                        Text("Your single-use invite link", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        // Transient render value only. The ticket, not this String, is retained.
                        (shown.link.useIfCurrent { it } as? PortResult.Value)?.value?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(CIRCLE_ISSUED_LINK_NOTICE, style = MaterialTheme.typography.bodyMedium)
                        if (sharePort == null) Text(CIRCLE_ISSUED_SHARE_UNAVAILABLE)
                        else {
                            Text(CIRCLE_ISSUED_SHARE_NOTICE, style = MaterialTheme.typography.bodyMedium)
                            IssuedPrimary(CIRCLE_ISSUED_SHARE_LABEL, enabled && !shareAttempted) {
                                share(shown)
                            }
                        }
                        shareNotice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                    if (!state.linkAvailable && currentLink == null && state.completionOperation == CircleIssuedInvitationOperation.ISSUE && state.result != null)
                        Text("The link is no longer available in this view. Confirming retained history does not recover a shareable link.")
                    if (onStartNew != null && newCircleName != null && state.pending == null) IssuedSecondary("Create another invite", enabled) {
                        hideLink(); replacing = expected
                    } else Text("To create another invite, go back, open the intended circle and choose Invite your people.")
                }
                if (state.completionPending) {
                    IssuedNotice("Retained result", "Confirm the saved result here before another action. This local confirmation never recovers a link or sends again.")
                    IssuedPrimary("Review retained result", enabled) { act { controller.reviewCompletion(expected) } }
                }
                state.pending?.let { original ->
                    IssuedOriginal(original)
                    IssuedPrimary("Review original request", enabled) { act { controller.reviewOriginal(expected) } }
                    if (original.canDiscardUnsent) IssuedSecondary("Review unsent discard", enabled) { act { controller.reviewDiscardUnsent(expected) } }
                }
                if (state.canReviewIssue) {
                    state.circle?.let { circle ->
                        IssuedCircle(circle)
                        Text(CIRCLE_ISSUED_SINGLE_USE_NOTICE, style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(lifetime, { if (enabled && it.length <= 3) { lifetime = it; lifetimeError = false } },
                            label = { Text("Expires in hours") }, enabled = enabled, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            supportingText = { Text("Choose 1–${controller.maxLifetimeHours} hours.") },
                            isError = lifetimeError, modifier = Modifier.fillMaxWidth())
                        IssuedPrimary("Review invite link", enabled && !state.capacityReached) {
                            val hours = circleIssuedLifetime(lifetime, controller.maxLifetimeHours)
                            if (hours == null) lifetimeError = true else act { controller.reviewIssue(hours, expected) }
                        }
                    }
                } else if (state.pending == null && !state.completionAcknowledged && !state.completionPending) {
                    IssuedNotice("Your local invitation history", "Open a circle and choose Invite your people to create a link. No request starts when history opens.")
                }
                if (state.capacityReached) IssuedNotice("Local invitation history is full", "This device cannot keep another invitation safely. Existing originals and history are preserved; nothing is removed automatically.")
                Text(CIRCLE_ISSUED_HISTORY_TITLE, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(CIRCLE_ISSUED_HISTORY_NOTICE, style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
                if (state.invitations.isEmpty()) Text("No invitations recorded on this device.")
                state.invitations.forEachIndexed { index, issued ->
                    key(issued.id) {
                        IssuedMetadata(issued)
                        if (!issued.revocationAcknowledged && state.pending == null && !state.completionPending)
                            IssuedSecondary("Review revoke: invitation ${index + 1}", enabled) {
                                act { controller.reviewRevoke(issued, expected) }
                            }
                    }
                }
            }
        }
    }
    replacing?.takeIf { it === state && state.phase != CircleIssuedInvitationsPhase.UNAVAILABLE }?.let { selected ->
        FeedMeTheme {
            AlertDialog(onDismissRequest = { replacing = null }, title = { Text("Create another invite?") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    newCircleName?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    Text("Keep the completed result in local history and freshly check the circle named above. A new link still needs its own review and Create invite link confirmation.")
                } },
                confirmButton = { TextButton(onClick = {
                    replacing = null
                    if (currentHost() && controller.states.value === selected && selected.completionAcknowledged && selected.pending == null) onStartNew?.invoke(selected)
                }) { Text("Check circle for new invite") } },
                dismissButton = { TextButton(onClick = { replacing = null }) { Text("Keep current result") } })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun IssuedFrame(back: () -> Unit, backLabel: String, content: @Composable ColumnScope.() -> Unit) {
    FeedMeTheme {
        Box(Modifier.fillMaxSize().background(FeedMeColors.Paper).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = back, modifier = Modifier.heightIn(min = 48.dp)) { Text(backLabel) }
                    FeedMeWordmark(compact = true)
                }
                Text(CIRCLE_ISSUED_TITLE, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                content()
            }
        }
    }
}
@Composable private fun IssuedCircle(circle: CircleSnapshot) {
    Text("Circle", style = MaterialTheme.typography.labelMedium, color = FeedMeColors.Muted)
    Text(circle.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
}
@Composable private fun IssuedOriginal(original: CircleIssuedInvitationPending) {
    IssuedNotice(if (original.operation == CircleIssuedInvitationOperation.ISSUE) "Original link request" else "Original revocation request",
        circleCreatePendingText(original.queuePhase, original.attempts, original.recoveryRequired))
    IssuedCircle(original.circle)
    original.expiresInHours?.let { Text("Original requested lifetime: ${circleIssuedHours(it)}") }
    original.issued?.let { IssuedMetadata(it) }
}
@Composable private fun IssuedMetadata(issued: CircleIssuedInvitation) {
    Surface(color = FeedMeColors.Surface, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(issued.circle.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text("Last confirmed details", style = MaterialTheme.typography.labelMedium, color = FeedMeColors.Muted)
            Text("Invitation updated: ${circleInvitationExpiry(WireField.Value(issued.updatedAt))}", style = MaterialTheme.typography.bodySmall)
            Text("Status when issued: ${circleStatusLabel(issued.status)}", style = MaterialTheme.typography.bodyMedium)
            Text("Expires: ${circleInvitationExpiry(WireField.Value(issued.expiresAt))}", style = MaterialTheme.typography.bodyMedium)
            if (issued.revocationAcknowledged) Text("Revocation confirmed on this device. No existing member was removed.")
            else Text("Current use or revocation elsewhere is not checked here.", style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
        }
    }
}
@Composable private fun IssuedNotice(title: String, message: String) {
    Surface(color = FeedMeColors.SoftBlue, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
@Composable private fun IssuedPrimary(label: String, enabled: Boolean, action: () -> Unit) {
    Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(label) }
}
@Composable private fun IssuedSecondary(label: String, enabled: Boolean, action: () -> Unit) {
    OutlinedButton(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(label) }
}
