package com.feedme.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feedme.app.FeedMeTheme
import com.feedme.app.blueprint.*
import com.feedme.core.ports.FailureReason
import com.feedme.session.AccountDeletionState

internal fun entryAccountControlsPresentation(screen: EntryScreen, enabled: Boolean,
    deletionConfigured: Boolean, privacyAvailable: Boolean, blockedConfigured: Boolean = false,
    sessionsConfigured: Boolean = false, signOutAvailable: Boolean = false, notificationsConfigured: Boolean = false,
    kitchenConfigured: Boolean = false, profileConfigured: Boolean = false, memoryConfigured: Boolean = false,
    exportConfigured: Boolean = false, planConfigured: Boolean = false): BlueprintPreferenceState {
    require(screen in setOf(EntryScreen.ACCOUNT_SETTINGS, EntryScreen.ACCOUNT_PRIVACY))
    val page = if (screen == EntryScreen.ACCOUNT_SETTINGS) BlueprintPreferencePage.SETTINGS else BlueprintPreferencePage.PRIVACY
    return BlueprintPreferenceState(page, enabledActionIds = if (!enabled) emptySet() else buildSet {
        add("${page.name}.back")
        if (page == BlueprintPreferencePage.SETTINGS) {
            if (profileConfigured) add("SETTINGS.01")
            if (memoryConfigured) add("SETTINGS.04")
            if (planConfigured) add("SETTINGS.09")
            add("SETTINGS.05"); add("SETTINGS.10")
            if (sessionsConfigured) add("SETTINGS.07")
            if (signOutAvailable) add("SETTINGS.12")
            if (notificationsConfigured) add("SETTINGS.06")
            if (kitchenConfigured) { add("SETTINGS.02"); add("SETTINGS.03") }
        } else {
            if (blockedConfigured) add("PRIVACY.01")
            if (exportConfigured) add("PRIVACY.02")
            if (deletionConfigured) add("PRIVACY.03")
            if (privacyAvailable) add("PRIVACY.06")
        }
    }, notice = "Help opens an email draft. Account removal is separate from cooking setup. Other settings shown here are not connected yet.")
}

/** Existing original screens with honest empty/unavailable values, not prototype selections. */
@Composable
internal fun EntryAccountControlsScreen(screen: EntryScreen, enabled: Boolean, deletionConfigured: Boolean,
    privacyAvailable: Boolean, onAction: (String) -> Unit, onSupport: (() -> Unit)? = null,
    blockedConfigured: Boolean = false, sessionsConfigured: Boolean = false, signOutAvailable: Boolean = false,
    notificationsConfigured: Boolean = false, kitchenConfigured: Boolean = false, profileConfigured: Boolean = false,
    memoryConfigured: Boolean = false, exportConfigured: Boolean = false, planConfigured: Boolean = false) {
    val view = entryAccountControlsPresentation(screen, enabled, deletionConfigured, privacyAvailable, blockedConfigured, sessionsConfigured, signOutAvailable, notificationsConfigured, kitchenConfigured, profileConfigured, memoryConfigured, exportConfigured, planConfigured)
    var more by remember { mutableStateOf(false) }
    BackHandler(enabled) { onAction("${view.page.name}.back") }
    BlueprintPreferenceScreen(view, onFieldChange = { _, _ -> }, onAction = { action ->
        if (action in view.enabledActionIds) onAction(action)
    }, onMore = if (enabled && onSupport != null) { { more = true } } else null)
    if (more && enabled && onSupport != null) FeedMeTheme {
        AlertDialog(onDismissRequest = { more = false }, title = { Text("More options") },
            text = { OutlinedButton(onClick = { more = false; onSupport() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Help and support") } },
            confirmButton = { TextButton(onClick = { more = false }) { Text("Close") } })
    }
}

@Composable
internal fun EntryDeletionOpening(failure: FailureReason?, onBack: () -> Unit) {
    val view = BlueprintAccountControlsState(BlueprintAccountControlPage.DELETE_ACCOUNT,
        phase = if (failure == null) BlueprintAccountControlPhase.LOADING else BlueprintAccountControlPhase.UNAVAILABLE,
        enabledActionIds = setOf("DELETE_ACCOUNT.back"),
        statusMessage = if (failure == null) "Opening this device’s account request. Nothing is being sent."
            else "The saved account request could not be opened. No new request has been sent.")
    BackHandler(true, onBack)
    BlueprintAccountControlsScreen(view, onEvent = { event ->
        if (event is BlueprintAccountControlEvent.Action && event.expected === view && event.actionId == "DELETE_ACCOUNT.back") onBack()
    })
}

/** Secondary host chrome only. Local receipt recovery/cleanup never retries remote deletion. */
@Composable
internal fun EntryDeletionRecoveryMenu(state: AccountDeletionState,
    exact: Boolean, onAction: (EntryDeletionLocalAction) -> Unit,
    onSupport: (() -> String)? = null, onDismiss: () -> Unit) {
    var supportNotice by remember(state) { mutableStateOf<String?>(null) }
    FeedMeTheme { AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Account request") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("These actions only recover a saved receipt or finish cleanup on this device. Continue to sign in keeps the receipt in protected storage. Remote deletion may still be pending.")
            for (action in EntryDeletionLocalAction.entries) if (entryDeletionLocalActionAvailable(true, state.busy,
                    state.receipt != null, state.screen, action, state.archivePending)) {
                OutlinedButton(onClick = { onAction(action) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(when (action) {
                        EntryDeletionLocalAction.RECOVER_RECEIPT -> "Recover saved receipt"
                        EntryDeletionLocalAction.FINISH_LOCAL_CLEANUP -> "Finish this device’s cleanup"
                        EntryDeletionLocalAction.CONTINUE_TO_SIGN_IN -> "Continue to sign in"
                    })
                }
            }
            if (onSupport != null && entryDeletionSupportAvailable(exact, state.busy,
                    state.receipt != null, state.screen)) {
                OutlinedButton(onClick = { supportNotice = onSupport() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Contact support with receipt")
                }
            }
            supportNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }) }
}
