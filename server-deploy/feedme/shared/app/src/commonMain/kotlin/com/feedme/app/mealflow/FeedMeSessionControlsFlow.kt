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
import com.feedme.mealflow.sessioncontrols.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Original SESSIONS with explicit actual-row selection in More. Nothing revokes on mount,
 * selection, Back, a missing row or a failed request. A provider session is not a Google account. */
@Composable
fun FeedMeSessionControlsFlow(controller: SessionControlsController, hostIsCurrent: () -> Boolean,
    onClose: () -> Unit, platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit) {
    val observed by controller.states.collectAsState(); val state = observed
    val host by rememberUpdatedState(hostIsCurrent); val scope = rememberCoroutineScope()
    var attached by remember(controller) { mutableStateOf(true) }
    DisposableEffect(controller) { onDispose { attached = false } }
    var busy by remember(controller) { mutableStateOf(false) }; var more by remember(controller) { mutableStateOf(false) }
    var leaveFailure by remember(controller) { mutableStateOf(false) }
    fun current() = attached && host() && controller.isCurrent(state)
    fun canDepart() = attached && host() && controller.states.value === state && state.phase != SessionControlsPhase.REVOKING
    val working = state.phase == SessionControlsPhase.REVOKING
    val loading = busy || state.phase == SessionControlsPhase.LOADING
    val ready = current() && state.phase in setOf(SessionControlsPhase.READY, SessionControlsPhase.EMPTY) && !loading && state.pending == null
    fun act(work: suspend () -> Unit) { if (current() && !busy) { busy = true
        scope.launch { try { if (current()) work() } finally { busy = false } } } }
    fun leave() {
        if (!canDepart()) return
        val expected = state
        scope.launch { withContext(NonCancellable) {
            when (val result = controller.leave(expected)) {
                // The parent checks its native route. Invalidated private owners can still
                // leave without pretending their lease became usable again.
                is PortResult.Value -> if (controller.states.value === result.value && result.value.phase == SessionControlsPhase.HIDDEN) onClose()
                is PortResult.Failure -> if (attached) leaveFailure = true
            }
        } }
    }
    fun back() { if (!canDepart()) return
        when { more -> more = false; state.review != null && current() -> act { controller.backReview(state) }; else -> leave() } }
    platformBackHandler(state.phase != SessionControlsPhase.HIDDEN, ::back)
    val review = state.review.takeIf { current() }
    if (review != null) {
        val model = BlueprintConfirmationState(
            if (review.retryOriginal) "Retry this session revocation" else "Revoke the selected device session",
            listOfNotNull(review.label, review.platform, review.lastSeenAt?.let { "Last seen $it" },
                "FeedMe session ${review.sessionId} · version ${review.version}").joinToString("\n"),
            listOf("This revokes the selected registered FeedMe session and its linked Supabase refresh session. It does not sign out every device or your Google account.",
                "FeedMe access through that device session is blocked. Already-issued provider access tokens used outside FeedMe may remain valid until expiry. Downloaded data is not remotely erased.",
                if (review.retryOriginal) "Only this exact retained key, session and version are retried; the earlier outcome is not assumed."
                else "The current device is not revoked here. A changed target or version is not silently substituted."),
            canConfirm = current() && !loading && !working, canCancel = current() && !loading && !working,
            busy = loading || working)
        BlueprintConfirmationScreen(model, onConfirm = { if (it === model && model.confirmEnabled) act { controller.confirm(review) } },
            onCancel = { if (it === model && model.cancelEnabled) act { controller.backReview(state) } },
            heading = "Remove this\naccess?", confirmLabel = if (review.retryOriginal) "Retry original" else "Revoke selected session")
        return
    }
    val selected = state.selected.takeIf { current() }; val currentSession = state.currentSession.takeIf { current() }
    fun projection(row: RegisteredSession) = BlueprintControlSession(
        BlueprintControlReference(BlueprintControlReferenceKind.SESSION, row.id, row.version), row.label.ifBlank { "Registered ${row.platform} device" },
        "${row.platform} · last seen ${row.lastSeenAt}" + row.revokedAt?.let { " · revoked $it" }.orEmpty(), row.isCurrent)
    val selectedView = selected?.let(::projection)
    val notice = sessionControlsNotice(state) + if (leaveFailure) "\nCould not leave safely. Try Back again." else ""
    val model = BlueprintAccountControlsState(BlueprintAccountControlPage.SESSIONS,
        phase = when (state.phase) {
            SessionControlsPhase.LOADING -> BlueprintAccountControlPhase.LOADING
            SessionControlsPhase.REVOKING -> BlueprintAccountControlPhase.WORKING
            SessionControlsPhase.RECOVERY -> BlueprintAccountControlPhase.UNKNOWN
            SessionControlsPhase.EMPTY -> BlueprintAccountControlPhase.EMPTY
            SessionControlsPhase.ERROR, SessionControlsPhase.EXPIRED -> BlueprintAccountControlPhase.ERROR
            SessionControlsPhase.HIDDEN, SessionControlsPhase.UNAVAILABLE -> BlueprintAccountControlPhase.UNAVAILABLE
            else -> BlueprintAccountControlPhase.READY
        }, context = if (current() && state.accountId != null && state.accountVersion != null) BlueprintAccountControlContext(
            BlueprintControlReference(BlueprintControlReferenceKind.ACCOUNT, state.accountId!!, state.accountVersion!!), state.revision, selectedView?.reference) else null,
        currentSession = currentSession?.let(::projection), selectedSession = selectedView,
        enabledActionIds = buildSet { if (canDepart()) add("SESSIONS.back")
            if (ready && selected != null && !selected.isCurrent && selected.revokedAt == null) add("SESSIONS.01") },
        policies = if (ready && selected != null && !selected.isCurrent && selected.revokedAt == null) setOf(BlueprintAccountPolicyFact.SESSION_REVOCATION) else emptySet(),
        statusMessage = notice)
    BlueprintAccountControlsScreen(model, onEvent = { event -> if (event.expected === model && event is BlueprintAccountControlEvent.Action && model.admits(event.actionId)) when (event.actionId) {
        "SESSIONS.back" -> back()
        "SESSIONS.01" -> if (current()) act { controller.prepareRevoke(state) }
        else -> Unit
    } }, onMore = { if (current()) more = true })
    if (more && current()) FeedMeTheme { AlertDialog(onDismissRequest = { more = false }, title = { Text("Your registered devices") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(notice)
            if (state.loaded && state.sessions.isEmpty()) Text("No device rows were returned in this view.")
            state.sessions.forEach { row -> TextButton(enabled = ready, onClick = { act {
                when (controller.select(row, state)) { is PortResult.Value -> more = false; is PortResult.Failure -> Unit }
            } }) { Column(Modifier.fillMaxWidth()) {
                Text(row.label.ifBlank { "Registered ${row.platform} device" })
                Text("${row.platform} · ${if (row.isCurrent) "current device" else "another registered device"} · last seen ${row.lastSeenAt}")
                row.revokedAt?.let { Text("Revoked $it") }
            } } }
            state.pending?.let { original ->
                Text("Retained session ${original.sessionId} · matched version ${original.version}")
                Text("Status: ${original.phase?.name ?: "Original retained; registration unconfirmed"}")
                if (original.canRetry) TextButton(enabled = !loading && !working, onClick = { more = false; act { controller.prepareRetry(state) } }) { Text("Review original retry") }
                if (original.receiptReady) TextButton(enabled = !loading && !working, onClick = { more = false; act { controller.recoverReceipt(state) } }) { Text("Confirm retained receipt") }
                Text("Back retains this original. It does not cancel or replace the action.")
            }
            state.receipt?.let { receipt -> Text("Confirmed session ${receipt.sessionId}; the original matched version ${receipt.matchedVersion}. This is not an invented updated device record or a global sign-out receipt.") }
            if (state.pending == null && state.phase != SessionControlsPhase.UNAVAILABLE) TextButton(enabled = !loading && !working,
                onClick = { more = false; act { controller.refresh(state) } }) { Text("Refresh registered sessions") }
            if (state.hasMore) TextButton(enabled = ready, onClick = { act { controller.loadMore(state) } }) { Text("Load more devices") }
            if (state.pageLimitReached) Text("This view reached its page limit. Refresh starts a new bounded read.")
            Text("Use the existing sign-out action for this device. Sign out everywhere is unavailable until its separate fresh-authentication and provider-wide revocation flow is connected.")
        }
    }, confirmButton = { TextButton(onClick = { more = false }) { Text("Done") } }) }
}
private fun sessionControlsNotice(state: SessionControlsState): String = when {
    state.pending != null -> "An original revocation needs attention. More shows its actual status and safe recovery."
    state.phase == SessionControlsPhase.COMPLETE && state.receipt != null -> "Selected session revocation confirmed. Refresh for current device rows; no global Google sign-out was performed."
    state.phase == SessionControlsPhase.REVOKING -> "Finishing your confirmed original. No revocation success is assumed yet."
    state.phase == SessionControlsPhase.LOADING -> "Reading current registered sessions. Back can leave this read."
    state.phase == SessionControlsPhase.EXPIRED -> "This observation expired. Refresh before choosing a device."
    state.phase == SessionControlsPhase.UNAVAILABLE -> "This account session is unavailable. Private device details are hidden."
    state.phase == SessionControlsPhase.ERROR -> when (state.failureReason) {
        FailureReason.FORBIDDEN -> "This target cannot be revoked here. The current device or its shared provider session is protected."
        FailureReason.CONFLICT -> "The session or original version changed. No replacement is sent automatically."
        FailureReason.NOT_CONFIGURED -> "The complete session-revocation service is not connected. App-only success is not assumed."
        FailureReason.OFFLINE -> "Connect to read or revoke a session. Nothing is retried automatically."
        FailureReason.OUTCOME_UNKNOWN, FailureReason.STORAGE_FAILURE -> "The result is unconfirmed. Keep the original and use its recovery controls."
        else -> "Current session information is unavailable. A missing row is not proof of revocation."
    }
    state.selected?.isCurrent == true -> "This is your current device. Use the existing sign-out action; revocation from this screen is disabled."
    state.selected?.revokedAt != null -> "The server lists this session as already revoked. No new action is required."
    else -> "More → choose a current registered device, then review before revoking its access."
}
