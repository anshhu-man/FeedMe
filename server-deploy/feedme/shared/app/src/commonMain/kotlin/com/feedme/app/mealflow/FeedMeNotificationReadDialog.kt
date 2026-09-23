package com.feedme.app.mealflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feedme.app.FeedMeTheme
import com.feedme.core.ports.FailureReason
import com.feedme.mealflow.notifications.*
import com.feedme.sync.CommandPhase
import kotlinx.coroutines.launch

/** Original Inbox actions use a separate explicit confirmation/recovery, not optimistic read dots. */
@Composable
internal fun FeedMeNotificationReadDialog(controller: NotificationReadController, hostIsCurrent: () -> Boolean,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit) {
    val state = controller.states.collectAsState().value
    if (state.phase == NotificationReadPhase.HIDDEN) return
    val scope = rememberCoroutineScope()
    fun current() = hostIsCurrent() && controller.isCurrent(state)
    fun close() {
        if (controller.states.value === state && state.phase != NotificationReadPhase.SAVING)
            scope.launch { controller.leave(state) }
    }
    fun act(action: suspend () -> Unit) { if (current()) scope.launch { if (current()) action() } }
    platformBackHandler(true, ::close)
    val visible = current() && state.phase != NotificationReadPhase.UNAVAILABLE
    val review = state.review.takeIf { visible }
    val pending = state.pending.takeIf { visible }
    FeedMeTheme {
        AlertDialog(onDismissRequest = ::close, title = { Text("Notification read status") }, text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This changes notification read status only. It does not mark messages in a conversation as read.")
                when {
                    !visible -> Text("This account view is no longer current. No new read acknowledgement can be sent here.")
                    state.phase == NotificationReadPhase.LOADING -> { Text("Checking the retained read request…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    state.phase == NotificationReadPhase.SAVING -> { Text("Retaining and checking this exact read acknowledgement…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    state.phase == NotificationReadPhase.REVIEW && review != null -> {
                        if (review.notificationId != null) Text("Mark the selected notification as read?")
                        else Text("Mark notifications created through this server-observed cutoff as read: ${review.throughCreatedAt}. This includes eligible notifications not shown on the current page; newer ones remain unread.")
                        if (review.retryOriginal) Text("Retrying the retained original uses the same key and exact target/cutoff. It does not create a newer acknowledgement.")
                        Text("The server checks your current account and access. Opening a post or conversation alone does not send this command.")
                    }
                    state.phase == NotificationReadPhase.COMPLETE && state.receipt != null ->
                        Text("The server acknowledged the original notification read request. This can be a historical replay receipt. Close and explicitly refresh Inbox to see current read status.")
                    pending != null -> {
                        Text("The original read request is retained. Its outcome may be unknown; no replacement target, cutoff or key will be sent.")
                        pending.throughCreatedAt?.let { Text("Original server cutoff: $it") }
                        if (pending.receiptReady) OutlinedButton(onClick = { act { controller.applyReceipt(state) } }) { Text("Apply retained receipt") }
                        if (pending.canRetry) OutlinedButton(onClick = { act { controller.prepareRetry(state) } }) { Text("Review exact retry") }
                        if (pending.phase == null || pending.phase in setOf(CommandPhase.READY, CommandPhase.AWAITING_CONFIRMATION))
                            TextButton(onClick = { act { controller.discardUnsent(state) } }) { Text("Discard only if never sent") }
                    }
                    else -> Text("No read acknowledgement is confirmed. Close, refresh Inbox, or explicitly check retained read requests again.")
                }
                state.failureReason?.takeIf { visible }?.let { Text(when (it) {
                    FailureReason.OUTCOME_UNKNOWN, FailureReason.STORAGE_FAILURE -> "The outcome is not confirmed. Keep the retained original and use its explicit recovery."
                    FailureReason.OFFLINE -> "Reconnect before reviewing the exact retry. Nothing will resend automatically."
                    FailureReason.NOT_FOUND -> "No accessible retained notification read request was found."
                    FailureReason.RATE_LIMITED -> "Wait before reviewing the exact retry."
                    else -> "This read acknowledgement is unavailable or no longer current. Refresh Inbox or review the retained original; no success is assumed."
                }) }
            }
        }, confirmButton = {
            if (review != null && state.phase == NotificationReadPhase.REVIEW)
                TextButton(onClick = { act { controller.confirm(review) } }, enabled = current()) {
                    Text(if (review.retryOriginal) "Retry original" else "Mark read")
                }
            else TextButton(onClick = ::close, enabled = state.phase != NotificationReadPhase.SAVING) { Text("Close") }
        }, dismissButton = {
            if (review != null) TextButton(onClick = ::close) { Text("Not now") }
        })
    }
}
