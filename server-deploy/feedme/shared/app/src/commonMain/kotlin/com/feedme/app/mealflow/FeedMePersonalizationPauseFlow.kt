package com.feedme.app.mealflow

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import com.feedme.app.blueprint.*
import com.feedme.contracts.WireField
import com.feedme.contracts.WireDocument
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.mealflow.KitchenInputPending
import com.feedme.mealflow.KitchenInputPhase
import com.feedme.mealflow.KitchenInputState
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

internal fun isPersonalizationPauseOriginal(original: KitchenInputPending): Boolean {
    val body = original.body ?: return false
    return original.operationId == "updatePreferences" &&
        Json.parseToJsonElement(body.encodeUtf8().decodeToString()).jsonObject.keys == setOf("personalizationEnabled") &&
        (body.field("personalizationEnabled") as? WireField.Value)?.value?.booleanOrNull() == false
}

internal fun samePersonalizationDocument(first: WireDocument?, second: WireDocument?): Boolean =
    if (first == null || second == null) first == null && second == null
    else first.encodeUtf8().contentEquals(second.encodeUtf8())

/** A server setting observation, not a capability, local draft, or promise that ranking is enabled. */
internal fun observedPersonalization(state: KitchenInputState): Boolean? = state.personalizationEnabled.takeIf {
    !state.preferencesHistorical && state.draftAcknowledged && state.failureReason == null &&
        state.phase !in setOf(KitchenInputPhase.LOADING, KitchenInputPhase.OFFLINE, KitchenInputPhase.UNAVAILABLE) &&
        state.pending.none { it.operationId == "updatePreferences" }
}

internal fun personalizationReference(state: KitchenInputState): BlueprintPreferenceReference? {
    if (observedPersonalization(state) == null) return null
    val body = state.preferences ?: return null
    val id = (body.field("id") as? WireField.Value)?.value?.stringOrNull() ?: return null
    val version = (body.field("version") as? WireField.Value)?.value?.numberTokenOrNull() ?: return null
    return BlueprintPreferenceReference(id, version)
}

/** Original confirmation layout over the real encrypted preference original. Rendering never
 * reads or sends. Closing keeps every local draft and any unresolved original intact. */
@Composable
internal fun FeedMePersonalizationPauseFlow(
    experience: MealFlowExperience,
    hostIsCurrent: () -> Boolean,
    initialLoaded: Boolean,
    initialFailure: FailureReason?,
    onClose: () -> Unit,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
) {
    val kitchen = experience.kitchen
    val state = kitchen.states.collectAsState().value
    val currentHost by rememberUpdatedState(hostIsCurrent)
    val closeHost by rememberUpdatedState(onClose)
    val scope = rememberCoroutineScope()
    var attached by remember(experience) { mutableStateOf(true) }
    var busy by remember(experience) { mutableStateOf(false) }
    var readHere by remember(experience) { mutableStateOf(false) }
    var resultNotice by remember(experience) { mutableStateOf<String?>(null) }
    DisposableEffect(experience) { onDispose { attached = false } }
    fun visitCurrent() = attached && currentHost()
    fun current() = visitCurrent() && kitchen.states.value === state
    fun close() { if (attached) closeHost() }
    fun act(operation: suspend () -> PortResult<KitchenInputState>) {
        if (!current() || busy || state.phase == KitchenInputPhase.LOADING) return
        busy = true; resultNotice = null
        scope.launch {
            try {
                if (!current()) return@launch
                val result = operation()
                if (!visitCurrent()) return@launch
                if (result is PortResult.Failure) resultNotice = "Change not confirmed. Your pending change and other edits are kept. Check again or retry."
                else {
                    val actual = (result as PortResult.Value).value
                    if (kitchen.states.value === actual && !actual.preferencesHistorical && actual.failureReason == null &&
                        actual.phase != KitchenInputPhase.OFFLINE) readHere = true
                }
            } finally { busy = false }
        }
    }
    val visible = visitCurrent()
    val loaded = initialLoaded || readHere
    val pending = state.pending.filter { it.operationId == "updatePreferences" }.takeIf { visible }.orEmpty()
    val original = pending.singleOrNull()?.takeIf(::isPersonalizationPauseOriginal)
    val observed = if (visible && loaded) observedPersonalization(state) else null
    val otherPauseDraft = state.preferenceDraft?.field("personalizationEnabled").let { it != null && it != WireField.Missing }
    val loading = busy || state.phase == KitchenInputPhase.LOADING || !loaded && initialFailure == null
    val canPause = visible && loaded && !loading && pending.isEmpty() && observed == true && !otherPauseDraft
    val canRetry = visible && !loading && original?.canSynchronize == true
    val status = when {
        !visible -> "This account view is no longer current. Back keeps your pending changes."
        loading -> "Checking your settings…"
        original != null -> "Your pause is not confirmed yet. Retry keeps the same pending change."
        pending.isNotEmpty() -> "Another settings change needs attention. Return to Food preferences to resolve it first."
        observed == false -> "Last checked: learned suggestions are paused. Your current meal is unchanged."
        observed == true && !otherPauseDraft -> "Pause learned suggestions for future meals. This applies whenever learned suggestions are available."
        otherPauseDraft -> "An unsaved personalization edit is kept. Resolve it in Food preferences first."
        else -> "Couldn’t confirm the current setting. Check again before changing it."
    }
    val page = BlueprintConfirmationState(actionLabel = if (original == null) "Pause learned suggestions" else "Retry pause",
        affectedSummary = "Your learned suggestions for future meals.",
        consequences = listOf("Dietary choices, Saved meals and feedback stay unchanged.",
            "Other unsaved edits are kept, not sent.",
            "Back keeps any pending change."),
        canConfirm = canPause || canRetry, canCancel = attached, busy = loading,
        status = listOfNotNull(status, resultNotice, initialFailure?.takeIf { !readHere }?.let {
            "The settings check didn’t finish. You can try again."
        }).joinToString("\n\n"))
    platformBackHandler(attached, ::close)
    BlueprintConfirmationScreen(page, heading = "Your taste.\nYour control.",
        confirmLabel = if (original == null) "Pause learned suggestions" else "Retry pause",
        onConfirm = { rendered ->
            if (rendered === page && page.confirmEnabled && current()) {
                if (original != null && canRetry) act { experience.synchronizeMemoryPersonalization(state, original, ::visitCurrent) }
                else if (canPause) act { experience.pauseMemoryPersonalization(state, ::visitCurrent) }
            }
        }, onCancel = { rendered -> if (rendered === page) close() },
        reviewLinks = {
            TextButton(enabled = visible && !busy && state.phase != KitchenInputPhase.LOADING,
                onClick = { act { experience.loadMemoryPersonalization(state, ::visitCurrent) } }) {
                Text("Check current settings")
            }
        })
}
