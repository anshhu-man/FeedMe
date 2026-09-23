package com.feedme.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feedme.app.FeedMeTheme
import com.feedme.app.blueprint.*

internal const val FEEDME_SUPPORT_EMAIL = "hazloteams3@gmail.com"
internal const val SUPPORT_MESSAGE_LIMIT = 4_000

/** Process-memory draft only. No account/device metadata, persistence or automatic upload. */
internal class EntrySupportDraft(val message: String = "", val notice: String? = null) {
    override fun toString() = "EntrySupportDraft(<redacted>)"
}

internal fun validSupportMessage(value: String): Boolean = value.length <= SUPPORT_MESSAGE_LIMIT &&
    value.none { it.isISOControl() && it != '\n' && it != '\t' }

@Composable
internal fun EntrySupportScreen(draft: EntrySupportDraft, enabled: Boolean, privacyAvailable: Boolean,
    cookingAvailable: Boolean, onMessage: (String) -> Unit, onAction: (String) -> Unit,
    onOpenEmail: () -> Unit, onCopyAddress: () -> Unit) {
    var more by remember { mutableStateOf(false) }
    val view = BlueprintAccountControlsState(BlueprintAccountControlPage.SUPPORT,
        phase = BlueprintAccountControlPhase.READY, supportEmailDraft = true,
        fields = BlueprintAccountControlFields(supportMessage = draft.message),
        editableFields = if (enabled) setOf(BlueprintAccountControlField.SUPPORT_MESSAGE) else emptySet(),
        enabledActionIds = if (!enabled) emptySet() else buildSet {
            add("SUPPORT.back")
            if (draft.message.isNotBlank() && validSupportMessage(draft.message)) add("SUPPORT.01")
            if (privacyAvailable) add("SUPPORT.02")
            if (cookingAvailable) add("SUPPORT.04")
        }, statusMessage = draft.notice ?: "Email $FEEDME_SUPPORT_EMAIL. Only the message you write is included. Please leave out passwords, payment details and other private information.")
    BackHandler(enabled) { onAction("SUPPORT.back") }
    BlueprintAccountControlsScreen(view, onMore = if (enabled) { { more = true } } else null,
        onEvent = { event ->
            if (enabled && event.expected === view) when (event) {
                is BlueprintAccountControlEvent.Action -> if (event.actionId in view.enabledActionIds) onAction(event.actionId)
                is BlueprintAccountControlEvent.FieldChanged -> if (event.field == BlueprintAccountControlField.SUPPORT_MESSAGE) {
                    (event.value as? BlueprintAccountControlValue.Text)?.let { onMessage(it.value) }
                }
            }
        })
    if (more && enabled) FeedMeTheme {
        AlertDialog(onDismissRequest = { more = false }, title = { Text("Contact FeedMe") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(FEEDME_SUPPORT_EMAIL)
                OutlinedButton(onClick = { more = false; onOpenEmail() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Open email app")
                }
                OutlinedButton(onClick = { more = false; onCopyAddress() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Copy support address")
                }
            } }, confirmButton = { TextButton(onClick = { more = false }) { Text("Close") } })
    }
}
