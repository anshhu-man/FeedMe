package com.feedme.development.progress

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.feedme.app.FeedMeColors
import com.feedme.app.FeedMeTheme
import com.feedme.app.FeedMeWordmark
import com.feedme.app.FeedMeStatusLabel
import com.feedme.app.FeedMeDetails
import com.feedme.app.FeedMeWelcomePhoto
import com.feedme.app.mealflow.FeedMeMealFlow
import com.feedme.core.ports.PortResult

/** No account proof, store or cooking state is reconstructed from Intent/SavedState. */
class ProgressActivity : ComponentActivity() {
    private var foregroundHost: ProgressForegroundHost? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.WHITE),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.WHITE),
        )
        val app = application as ProgressApplication
        foregroundHost = app.owner.attachForegroundHost()
        setContent {
            FeedMeTheme {
                ProgressRoot(app) { app.run(app.owner::close) { result -> if (result is PortResult.Value) finish() } }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        foregroundHost?.let { (application as ProgressApplication).owner.setForegroundHost(it, true) }
    }
    override fun onStop() {
        foregroundHost?.let { (application as ProgressApplication).owner.setForegroundHost(it, false) }
        super.onStop()
    }
    override fun onDestroy() {
        foregroundHost?.let { (application as ProgressApplication).owner.detachForegroundHost(it) }
        foregroundHost = null
        super.onDestroy()
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ProgressRoot(app: ProgressApplication, exit: () -> Unit) {
    val owner = app.owner
    val state by owner.states.collectAsState()
    val experience by owner.experience.collectAsState()
    var optionsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(owner) {
        if (owner.states.value.phase == ProgressHostPhase.NEW) app.run(owner::inspectStartup)
    }
    fun run(action: suspend () -> PortResult<Unit>) = app.run(action)
    val current = experience
    if (current == null) BackHandler(enabled = state.phase != ProgressHostPhase.CLOSING, onBack = exit)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.Center) {
                FeedMeStatusLabel(if (state.serviceOffline) "Demo preview · offline" else "Preview · demo data")
                Box {
                    TextButton(onClick = { optionsOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Options") }
                    DropdownMenu(expanded = optionsOpen, onDismissRequest = { optionsOpen = false }) {
                        if (current != null && state.phase == ProgressHostPhase.ACTIVE) {
                            DropdownMenuItem(text = { Text(if (state.serviceOffline) "Use local demo service" else "Try offline mode") },
                                onClick = { optionsOpen = false; owner.setServiceOffline(!state.serviceOffline) })
                            DropdownMenuItem(text = { Text("Reset preview") }, enabled = !state.busy,
                                onClick = { optionsOpen = false; owner.requestReset() })
                        }
                        DropdownMenuItem(text = { Text("Exit and keep progress") }, enabled = !state.busy,
                            onClick = { optionsOpen = false; exit() })
                    }
                }
            }
            if (current != null && state.phase == ProgressHostPhase.ACTIVE) {
                Box(Modifier.weight(1f)) {
                    FeedMeMealFlow(current, exit) { enabled, action -> BackHandler(enabled, onBack = action) }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    FeedMeWordmark()
                    Text(if (state.phase == ProgressHostPhase.RESUME_AVAILABLE) "Back for\nanother bite?" else "Good food.\nLess effort.",
                        style = MaterialTheme.typography.displayMedium, modifier = Modifier.semantics { heading() })
                    Text("A little help with dinner. A little more room for life.",
                        style = MaterialTheme.typography.bodyLarge, color = FeedMeColors.Muted)
                    if (state.phase in setOf(ProgressHostPhase.START_AVAILABLE, ProgressHostPhase.RESUME_AVAILABLE)) {
                        Box(Modifier.fillMaxWidth()) {
                            FeedMeWelcomePhoto()
                            Box(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                                FeedMeStatusLabel("Your pace. Your plate.", FeedMeColors.Lime)
                            }
                        }
                    }
                    Text(when (state.phase) {
                        ProgressHostPhase.NEW, ProgressHostPhase.CHECKING -> "Checking for your saved progress…"
                        ProgressHostPhase.START_AVAILABLE -> "Try meal ideas, step-by-step cooking and your cookbook with demo data. No live account needed."
                        ProgressHostPhase.RESUME_AVAILABLE -> "Your local progress is here. Continue when you’re ready."
                        ProgressHostPhase.SETUP_RECOVERY -> "Setup was interrupted. Review it before continuing; nothing has been erased."
                        ProgressHostPhase.RECOVERY_REQUIRED -> "The last step needs your attention. Your existing data has not been reset."
                        ProgressHostPhase.CLOSING -> "Safely closing your session… Please keep the app open."
                        ProgressHostPhase.CLOSE_ONLY -> "We couldn’t finish closing. Retry close before starting again."
                        ProgressHostPhase.CLOSED -> "You’re safely out. Saved progress stays here unless you confirmed a reset or discarded an interrupted setup."
                        ProgressHostPhase.UNSUPPORTED -> "This preview needs Android 8.1 or newer. No session has been opened."
                        ProgressHostPhase.ACTIVE -> "This view is unavailable. Exit safely before trying again."
                    }, style = MaterialTheme.typography.bodyMedium)
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.failure?.let { Text("That step couldn’t be confirmed. Your progress is not marked complete.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    when (state.phase) {
                        ProgressHostPhase.START_AVAILABLE -> ProgressAction("Start preview", !state.busy) { run(owner::start) }
                        ProgressHostPhase.RESUME_AVAILABLE -> ProgressAction("Resume local preview", !state.busy) { run(owner::resume) }
                        ProgressHostPhase.SETUP_RECOVERY -> {
                            if (!state.recoveryOpen) ProgressAction("Inspect interrupted setup", !state.busy) { run(owner::openSetupRecovery) }
                            else if (!state.canRetryAbort) ProgressAction("Prepare setup discard", !state.busy) { run(owner::prepareSetupAbort) }
                        }
                        ProgressHostPhase.CLOSED -> ProgressAction("Inspect retained state", !state.busy) { run(owner::inspectStartup) }
                        else -> Unit
                    }
                    if (state.canRetryCreate && state.phase == ProgressHostPhase.RECOVERY_REQUIRED)
                        ProgressAction("Retry original setup", !state.busy) { run(owner::retryCreate) }
                    if (state.canRetryAbort && state.phase in setOf(ProgressHostPhase.SETUP_RECOVERY, ProgressHostPhase.RECOVERY_REQUIRED))
                        ProgressAction("Retry confirmed setup discard", !state.busy) { run(owner::retrySetupAbort) }
                    if (state.canRetryRetirement && state.phase == ProgressHostPhase.RECOVERY_REQUIRED)
                        ProgressAction("Retry confirmed reset", !state.busy) { run(owner::retryRetirement) }
                    if (state.canReset && state.phase == ProgressHostPhase.RECOVERY_REQUIRED)
                        ProgressAction("Reset verified synthetic identity", !state.busy, owner::requestReset)
                    if (state.phase !in setOf(ProgressHostPhase.CLOSING, ProgressHostPhase.UNSUPPORTED))
                        OutlinedButton(onClick = exit, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Text(if (state.phase == ProgressHostPhase.CLOSE_ONLY) "Retry close" else "Exit without reset")
                        }
                    Text("Preview only: demo account, recipes and service. Live login and sharing aren’t connected. Timers work only while the app is open; they don’t confirm food is safe.",
                        style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
                    FeedMeDetails("About this preview") {
                        Text("Your draft, cooking and cookbook actions use encrypted storage on this device. No meal is submitted or started automatically. Demo recipes are not approved cooking or food-safety advice.",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Cookbook Save, browsing and confirmed removal work with the local demo service. Make Again, photos, live accounts and social sharing are not connected.",
                            style = MaterialTheme.typography.bodySmall)
                        state.failure?.let { Text("Diagnostic: ${it.name}", style = MaterialTheme.typography.bodySmall) }
                        state.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
    val ticket = state.confirmation
    if (ticket != null) AlertDialog(
        onDismissRequest = owner::dismissConfirmation,
        title = { Text(if (ticket.kind == ProgressConfirmationKind.RESET) "Reset this synthetic preview?" else "Discard the original interrupted setup?") },
        text = { Text(if (ticket.kind == ProgressConfirmationKind.RESET)
            "This explicitly retires the current synthetic account's encrypted local drafts, pending actions, cooking and local service records. " +
                "It is not remote logout. Exit instead to keep your progress."
            else "Only the original authenticated pending setup can be discarded. No normal login or unrelated account is selected. " +
                "A failed acknowledgement remains an explicit retry or close gate.") },
        dismissButton = { TextButton(onClick = owner::dismissConfirmation) { Text("Keep retained state") } },
        confirmButton = { TextButton(enabled = !state.busy, onClick = {
            run { if (ticket.kind == ProgressConfirmationKind.RESET) owner.confirmReset(ticket) else owner.confirmSetupAbort(ticket) }
        }) { Text(if (ticket.kind == ProgressConfirmationKind.RESET) "Confirm reset" else "Confirm setup discard") } },
    )
}

@Composable
private fun ProgressAction(label: String, enabled: Boolean, action: () -> Unit) {
    Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(18.dp)) { Text(label) }
}
