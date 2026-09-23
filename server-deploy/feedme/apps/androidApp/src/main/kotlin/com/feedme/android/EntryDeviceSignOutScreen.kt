package com.feedme.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.feedme.app.FeedMeColors
import com.feedme.app.FeedMeTheme
import com.feedme.app.FeedMeWordmark
import com.feedme.session.PrivateAccountSignOutPhase
import com.feedme.session.PrivateAccountSignOutState

/** A real, device-only removal flow. No remote revocation or account deletion is implied. */
@Composable
internal fun EntryDeviceSignOutScreen(state: PrivateAccountSignOutState, host: EntryDeviceSignOutState,
    enabled: Boolean, onAction: (EntryDeviceSignOutAction) -> Unit, onClose: () -> Unit) {
    val busy = host.busy || state.phase == PrivateAccountSignOutPhase.WORKING
    val reviewing = state.phase == PrivateAccountSignOutPhase.REVIEW
    val goBack = {
        if (enabled && !busy) {
            if (reviewing) onAction(EntryDeviceSignOutAction.CANCEL) else onClose()
        }
    }
    BackHandler(true, goBack)
    FeedMeTheme {
        Column(Modifier.fillMaxSize().background(FeedMeColors.Paper).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            FeedMeWordmark(compact = true)
            Text(when (state.phase) {
                PrivateAccountSignOutPhase.IDLE -> "Sign out on this device"
                PrivateAccountSignOutPhase.REVIEW -> "Ready to sign out?"
                PrivateAccountSignOutPhase.WORKING -> "Signing out safely"
                PrivateAccountSignOutPhase.RECOVERY_REQUIRED -> "Finish signing out"
                PrivateAccountSignOutPhase.COMPLETE -> "You’re signed out"
            }, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text(when (state.phase) {
                PrivateAccountSignOutPhase.IDLE -> "Review this device’s saved account before confirming. Nothing is removed just by opening this screen."
                PrivateAccountSignOutPhase.REVIEW -> "This removes your sign-in, private downloads, drafts and cooking reminders from this device. Unsynced work will be lost."
                PrivateAccountSignOutPhase.WORKING -> "Removing this device’s sign-in, private data and scheduled work."
                PrivateAccountSignOutPhase.RECOVERY_REQUIRED -> "Removal hasn’t been confirmed. Retry to finish the same sign-out; this will not create a new request."
                PrivateAccountSignOutPhase.COMPLETE -> "Your sign-in, private data and cooking reminders have been removed from this device."
            }, style = MaterialTheme.typography.bodyLarge)
            Text("Your FeedMe account and meals already saved online are not deleted. Other devices stay signed in. Terms records may be kept on this device.",
                style = MaterialTheme.typography.bodyMedium, color = FeedMeColors.Muted)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!busy && (host.failure != null || state.failureReason != null)) Text(
                "The last step could not be confirmed. Review or retry before assuming this device is signed out.",
                style = MaterialTheme.typography.bodyMedium)
            fun can(action: EntryDeviceSignOutAction) = enabled &&
                entryDeviceSignOutActionAvailable(true, busy, state.phase, action)
            when (state.phase) {
                PrivateAccountSignOutPhase.IDLE -> Button(onClick = { onAction(EntryDeviceSignOutAction.PREPARE) },
                    enabled = can(EntryDeviceSignOutAction.PREPARE), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text("Review sign-out")
                }
                PrivateAccountSignOutPhase.REVIEW -> {
                    Button(onClick = { onAction(EntryDeviceSignOutAction.CONFIRM) },
                        enabled = can(EntryDeviceSignOutAction.CONFIRM), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text("Sign out on this device")
                    }
                    OutlinedButton(onClick = { onAction(EntryDeviceSignOutAction.CANCEL) },
                        enabled = can(EntryDeviceSignOutAction.CANCEL), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text("Keep cooking")
                    }
                }
                PrivateAccountSignOutPhase.RECOVERY_REQUIRED -> Button(onClick = { onAction(EntryDeviceSignOutAction.RETRY) },
                    enabled = can(EntryDeviceSignOutAction.RETRY), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text("Retry sign-out")
                }
                PrivateAccountSignOutPhase.COMPLETE -> Button(onClick = onClose, enabled = enabled && !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Back to sign-in") }
                PrivateAccountSignOutPhase.WORKING -> Unit
            }
            if (state.phase in setOf(PrivateAccountSignOutPhase.IDLE, PrivateAccountSignOutPhase.REVIEW,
                    PrivateAccountSignOutPhase.RECOVERY_REQUIRED)) {
                TextButton(onClick = onClose, enabled = enabled && !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Close for now") }
                Text("Closing does not cancel a removal already started or sign you out by itself.",
                    style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
            }
        }
    }
}
