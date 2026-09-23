package com.feedme.app.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.feedme.app.FeedMeColors
import com.feedme.session.AccountTermsController
import com.feedme.session.AccountTermsScreen
import com.feedme.session.AccountTermsState
import kotlinx.coroutines.launch

/** Mounting only observes RAM state; every read/send/retry is explicit. The parent owns
 * finish on its longer-lived scope because finishing removes this child from composition. */
@Composable
internal fun FeedMeAccountTermsReview(
    controller: AccountTermsController,
    accountIsCurrent: () -> Boolean,
    onFinish: (AccountTermsState) -> Unit,
    onAccountTermsUrl: (String) -> Unit,
) {
    val observed by controller.states.collectAsState()
    val state = observed // Exact rendered state, not a later delegated value in callbacks.
    val hostCurrent by rememberUpdatedState(accountIsCurrent)
    val scope = rememberCoroutineScope()
    val actions = remember(controller) { EmailUiActionGate() }
    var attached by remember(controller) { mutableStateOf(true) }
    var claimed by remember(controller) { mutableStateOf(false) }
    // Any new state/notice requires a new affirmative choice; never saved on restoration.
    var accepted by remember(controller, state) { mutableStateOf(false) }
    val current = hostCurrent() && controller.isCurrentState(state) && state.screen != AccountTermsScreen.CLOSED
    val enabled = attached && current && !state.busy && !claimed
    DisposableEffect(controller) { onDispose { attached = false; actions.retire() } }
    SideEffect { if (!current || state.busy || claimed) accepted = false }
    fun canAct() = attached && hostCurrent() && !claimed && !actions.busy && !state.busy &&
        controller.isCurrentState(state) && state.screen != AccountTermsScreen.CLOSED
    fun act(action: suspend () -> Unit) {
        if (!canAct()) return
        val ticket = actions.claim() ?: return
        claimed = true; accepted = false
        scope.launch {
            try {
                if (attached && hostCurrent() && actions.owns(ticket) && controller.isCurrentState(state)) action()
            } finally { if (actions.release(ticket) && attached) claimed = false }
        }
    }
    fun open(url: String) {
        if (!canAct()) return
        val notice = state.notice ?: return
        if (url != notice.termsUrl && url != notice.privacyUrl) return
        if (attached && hostCurrent() && controller.isCurrentState(state)) onAccountTermsUrl(url)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!current) {
            Text("Terms review is no longer current. Go back to check your account connection. Saved requests are kept.")
        } else {
            if (state.busy || claimed) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Checking Terms securely…")
            }
            accountTermsFailureText(state.failureReason)?.let { message ->
                Surface(color = FeedMeColors.SoftBlue, shape = MaterialTheme.shapes.medium) {
                    Text(message, Modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
            state.notice?.let { notice ->
                Text("Terms version: ${notice.termsVersion}", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { open(notice.termsUrl) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Column(Modifier.fillMaxWidth()) { Text("Read these Terms"); Text(notice.termsUrl, style = MaterialTheme.typography.bodySmall) }
                }
                TextButton(onClick = { open(notice.privacyUrl) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Column(Modifier.fillMaxWidth()) { Text("Read this Privacy Policy"); Text(notice.privacyUrl, style = MaterialTheme.typography.bodySmall) }
                }
            }
            when (state.screen) {
                AccountTermsScreen.NEW -> {
                    Text("Load the current notice from your account service. Nothing is accepted by opening this screen.")
                    TermsButton("Load current Terms", enabled) { act { controller.load(state) } }
                }
                AccountTermsScreen.REVIEW -> {
                    Text("Read the linked notice before deciding. This changes only your Terms acceptance; your saved meals and account setup stay in place.")
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(accepted, enabled = enabled,
                        role = Role.Checkbox, onValueChange = { if (canAct()) accepted = it }),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Checkbox(accepted, onCheckedChange = null, enabled = enabled)
                        Text("I accept these Terms.", Modifier.weight(1f))
                    }
                    TermsButton("Accept these Terms", enabled && accepted && state.notice != null) {
                        if (accepted && canAct()) act { controller.accept(state, explicitlyAccepted = true) }
                    }
                }
                AccountTermsScreen.RESUME -> {
                    Text("A saved acceptance needs confirmation. Retry uses the same saved notice and request; it does not create a replacement acceptance.")
                    TermsButton("Retry saved acceptance", enabled) { act { controller.resume(state) } }
                    TextButton(onClick = { act { controller.load(state) } }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Reload saved status") }
                }
                AccountTermsScreen.ACCEPTED, AccountTermsScreen.CURRENT -> {
                    Text(if (state.screen == AccountTermsScreen.ACCEPTED) "Your Terms acceptance is confirmed."
                        else "Your account already has the current Terms acceptance.")
                    Text("Continue to check your account again. Terms acceptance alone does not open your kitchen or complete other requirements.")
                    TermsButton("Continue account check", enabled && state.failureReason == null) {
                        if (canAct()) { accepted = false; onFinish(state) }
                    }
                }
                AccountTermsScreen.CLOSED -> Unit
            }
        }
    }
}

@Composable
private fun TermsButton(label: String, enabled: Boolean, action: () -> Unit) {
    Button(action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        contentPadding = PaddingValues(16.dp)) { Text(label) }
}
