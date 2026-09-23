package com.feedme.app.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.feedme.app.*
import com.feedme.core.ports.EpochClock
import com.feedme.core.ports.PortResult
import com.feedme.session.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The continuation panel within FOOD_PREFS/EQUIPMENT. Mounting is inert. Display intent and
 * [freshDecision] are only UI routing facts: the same runtime must independently admit the exact
 * decision. A restored original never gains permission to create another decision from this UI.
 * The parent retains the controller; composition disposal only retires this screen's callbacks. */
@Composable
fun FeedMeOptionalDecisionFlow(
    controller: OnboardingProfileController,
    prompt: OnboardingOptionalPrompt,
    choice: OnboardingDecisionChoice,
    clock: EpochClock,
    onBack: () -> Unit,
    onContinue: (OnboardingProfileState) -> Unit,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
    freshDecision: Boolean = false,
    hostIsCurrent: () -> Boolean = { true },
) {
    val hostCurrent by rememberUpdatedState(hostIsCurrent)
    val observedState by controller.states.collectAsState()
    val state = observedState // Queued callbacks must keep the exact rendered state.
    val actions = remember(controller, prompt, choice, freshDecision) {
        OptionalDecisionActions(controller, prompt, choice, freshDecision)
    }
    val observedUi by actions.states.collectAsState()
    val ui = observedUi
    val navigation = remember(actions) { ProfileActionGate() }
    val scope = rememberCoroutineScope()
    var attached by remember(actions) { mutableStateOf(true) }
    var leaving by remember(actions) { mutableStateOf(false) }
    var navigating by remember(actions) { mutableStateOf(false) }
    var closeFailure by remember(actions) { mutableStateOf(false) }
    var now by remember(actions, clock) { mutableStateOf(decisionDisplayTime(clock)) }
    val visible = attached && hostCurrent() && controller.isCurrentState(state) && state.screen != OnboardingProfileScreen.UNAVAILABLE
    val busy = ui.busy || leaving || navigating
    val original = state.originalDecision
    val matching = decisionIntentMatches(prompt, choice, original?.prompt, original?.choice)
    val completed = matching && controller.isCurrentOptionalCompletion(state)
    val primary = if (completed) null else decisionPrimary(state.status, state.recoveryRequired,
        state.completionAcknowledged, state.originalIntent, freshDecision)
    val review = ui.review
    val reviewCurrent = review != null && controller.isCurrentReview(review)
    val retryReady = profileRetryReady(now, state.retryAtMillis)

    DisposableEffect(actions) {
        onDispose { attached = false; actions.retire(); navigation.retire() }
    }
    SideEffect { if (review != null && !reviewCurrent && !ui.busy) actions.dismissReview() }
    LaunchedEffect(actions, clock, state.retryAtMillis, review) {
        now = decisionDisplayTime(clock)
        while ((review != null && controller.isCurrentReview(review)) ||
            state.retryAtMillis?.let { now >= 0 && now < it } == true) {
            delay(500); now = decisionDisplayTime(clock)
        }
    }

    fun next(result: OnboardingProfileState, call: OptionalDecisionCall? = null) {
        if (!attached || !hostCurrent() || leaving || navigating ||
            call?.let { !actions.canDeliver(it, result) } == true || !controller.isCurrentOptionalCompletion(result)) return
        val actual = result.originalDecision
        if (!decisionIntentMatches(prompt, choice, actual?.prompt, actual?.choice)) return
        val ticket = navigation.claim(result, controller.states.value) ?: return
        navigating = true
        if (attached && hostCurrent() && !leaving && navigation.running(ticket) &&
            controller.isCurrentOptionalCompletion(result) && call?.let { actions.canDeliver(it, result) } != false)
            onContinue(result)
        else navigating = false
    }
    fun act(action: OptionalDecisionAction) {
        if (!visible || busy || !attached || !hostCurrent() || leaving || navigating) return
        val call = actions.claim(action, state) ?: return
        scope.launch {
            if (!attached || !hostCurrent()) return@launch
            val result = actions.execute(call)
            if (result != null && actions.canDeliver(call, result)) next(result, call)
        }
    }
    fun leave() {
        if (!attached || !hostCurrent() || leaving || navigating) return
        leaving = true; closeFailure = false; actions.retire(); navigation.retire()
        scope.launch {
            try {
                if (!hostCurrent()) return@launch
                when (controller.back()) {
                    is PortResult.Value -> if (attached && hostCurrent()) onBack()
                    is PortResult.Failure -> if (attached && hostCurrent()) { leaving = false; closeFailure = true }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (attached && hostCurrent()) { leaving = false; closeFailure = true } }
        }
    }
    val back: () -> Unit = {
        if (review != null && !busy) actions.dismissReview() else leave()
    }
    platformBackHandler(attached && hostCurrent() && !navigating, back)

    FeedMeTheme {
        Box(Modifier.fillMaxSize().background(FeedMeColors.Paper).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 14.dp).testTag("${preferencesScreenId(prompt)}.checkpoint"),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                TextButton(onClick = back, enabled = !leaving && !navigating, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
                FeedMeWordmark(compact = true)
                Text(preferencesTitle(prompt), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                if (!visible) {
                    DecisionNotice("Account connection changed", "Go back to restore account setup. Saved choices and any pending decision are kept; nothing is sent automatically.")
                } else {
                    Surface(color = FeedMeColors.SoftLime, shape = RoundedCornerShape(28.dp)) {
                        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(if (completed) "Step confirmed" else if (choice == OnboardingDecisionChoice.SKIPPED) "Keep it simple" else "Your choices, your way",
                                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                            Text(decisionPurpose(prompt, choice))
                            Text(if (completed) "This setup decision is confirmed. Continue to the next part of setup."
                                else "Continue confirms this setup step. It does not submit a new preference draft.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Checking your setup…") }
                    preferencesFailureText(ui.failure ?: state.failureReason)?.let { DecisionNotice("Couldn’t finish this step", it) }
                    when {
                        completed -> DecisionButton("Continue", !busy) { next(state) }
                        primary == OptionalDecisionAction.CHECKPOINT -> DecisionButton("Continue", !busy) { act(primary) }
                        primary == OptionalDecisionAction.RECOVER -> {
                            DecisionNotice("Restore your last result", "Confirm the retained result through local recovery before continuing. This does not resend the decision.")
                            DecisionButton("Restore saved progress", !busy) { act(primary) }
                        }
                        primary == OptionalDecisionAction.REVIEW_ORIGINAL && matching -> {
                            DecisionNotice("Check your last attempt", "Your previous decision is still pending. Review that exact decision before trying it again.")
                            DecisionButton("Review previous decision", !busy && retryReady) { act(primary) }
                            if (!retryReady) Text("Please wait before reviewing the same decision again.")
                        }
                        else -> DecisionNotice("Setup needs another look", "This saved decision cannot be continued from this view. Go back to reopen setup and review the current step. Your original request remains available.")
                    }
                    FeedMeDetails("What happens here?") {
                        Text("Saving preferences and confirming an optional setup step are separate actions. A skipped step never supplies empty choices or defaults.")
                        Text("If a connection drops, restore the saved result or review the original decision. Leaving does not retry or discard it.")
                        Text("Other account, eligibility and app setup requirements still apply. This confirmation does not unlock the rest of the app by itself.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (closeFailure) DecisionNotice("Couldn’t close this step", "Use Back again to finish closing. No navigation or new save was confirmed.")
            }
        }
        if (visible && review != null && reviewCurrent) AlertDialog(
            onDismissRequest = { if (!busy) actions.dismissReview() },
            title = { Text("Review previous decision") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(decisionPurpose(prompt, choice))
                Text("Retry only this original decision. No newer preference draft or profile text will be sent.")
            } },
            confirmButton = { TextButton(onClick = { act(OptionalDecisionAction.CONFIRM_ORIGINAL) }, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry this decision") } },
            dismissButton = { TextButton(onClick = { actions.dismissReview() }, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Not now") } },
        )
    }
}

private fun decisionDisplayTime(clock: EpochClock) = try { clock.nowMillis() } catch (_: Exception) { -1L }

@Composable
private fun DecisionNotice(title: String, body: String) {
    Surface(color = FeedMeColors.Surface, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DecisionButton(label: String, enabled: Boolean, action: () -> Unit) {
    Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(label) }
}
