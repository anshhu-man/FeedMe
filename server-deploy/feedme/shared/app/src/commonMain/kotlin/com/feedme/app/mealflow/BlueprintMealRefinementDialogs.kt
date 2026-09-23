package com.feedme.app.mealflow

import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.feedme.app.blueprint.*

internal enum class BlueprintMealRefinementPage { EFFORT, TASTE }
private class BlueprintMealRefinementSlot(val visit: BlueprintMealRefinementVisit?)
private class BlueprintEquipmentPreferenceVisit(val host: MealPreferenceEditorHost, val ticket: Any)

/** Rejection-only dialog lifetime. It grants no account, persistence or network authority. */
internal class BlueprintMealRefinementVisit(val form: MealFormState, val page: BlueprintMealRefinementPage) {
    private var active = true
    var ticket: Any by mutableStateOf(Any())
        private set

    fun current(expected: Any, observed: MealFormState, hostCurrent: Boolean): Boolean {
        if (!hostCurrent || observed !== form || form.busy || form.values == null) active = false
        return active && expected === ticket
    }

    fun claim(expected: Any, observed: MealFormState, hostCurrent: Boolean): Boolean {
        if (!current(expected, observed, hostCurrent)) return false
        ticket = Any()
        return true
    }

    fun retire() { active = false }
    override fun toString() = "BlueprintMealRefinementVisit(<redacted>)"
}

/** Original EFFORT/TASTE presentations editing only the already-owned local meal draft.
 * The normal Find action still owns validation, durable acknowledgment and plan creation. */
@Composable
internal fun BlueprintMealRefinementControls(form: MealFormState, choices: MealInputChoices,
    actions: MealScreenActions,
    content: (@Composable (open: (BlueprintMealRefinementPage) -> Unit, refinementOpen: Boolean) -> Unit)? = null) =
    BlueprintMealRefinementControls(form, choices, actions.blueprintCurrent, actions.edit,
        actions.equipmentPreferences, content)

/** The Pantry host supplies its own exact visit/form guard and local edit port. The
 * existing dialog still owns input, Back and one Apply; no navigation or read is implied. */
@Composable
internal fun BlueprintMealRefinementControls(form: MealFormState, choices: MealInputChoices,
    current: () -> Boolean, edit: ((MealFormValues) -> MealFormValues) -> Unit,
    equipmentPreferences: MealPreferenceEditorHost? = null,
    content: (@Composable (open: (BlueprintMealRefinementPage) -> Unit, refinementOpen: Boolean) -> Unit)? = null) {
    var slot by remember(form) { mutableStateOf(BlueprintMealRefinementSlot(null)) }
    val observedSlot = slot
    val observedVisit = observedSlot.visit
    fun open(page: BlueprintMealRefinementPage) {
        if (slot !== observedSlot || observedVisit != null || form.busy || form.values == null || !current()) return
        slot = BlueprintMealRefinementSlot(BlueprintMealRefinementVisit(form, page))
    }
    val enabled = !form.busy && form.values != null && current()
    if (content != null) content(::open, observedVisit != null)
    else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { open(BlueprintMealRefinementPage.EFFORT) }, enabled = enabled,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Set my energy") }
        OutlinedButton(onClick = { open(BlueprintMealRefinementPage.TASTE) }, enabled = enabled,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Choose my taste") }
    }
    val exact = observedVisit ?: return
    DisposableEffect(exact) { onDispose { exact.retire() } }
    // Keep the unsent input at the refinement visit, not inside the conditionally shown
    // EFFORT screen: loading/saving saved equipment must not reset these fields.
    var effortInput by remember(exact) { mutableStateOf(blueprintEffortPresentation(form, enabled = true)) }
    var equipmentVisit by remember(exact) { mutableStateOf<BlueprintEquipmentPreferenceVisit?>(null) }
    val child = equipmentVisit
    val token = exact.ticket
    fun continuationCurrent() = child?.host?.current?.invoke() ?: current()
    fun claim() = child == null && slot === observedSlot && exact.claim(token, form, current())
    fun close() {
        // Local departure is not a private action. An invalidated host must not trap Back.
        if (slot === observedSlot && token === exact.ticket && equipmentVisit == null) {
            exact.retire(); slot = BlueprintMealRefinementSlot(null)
        }
    }
    if (!exact.current(token, form, continuationCurrent())) {
        SideEffect { if (slot === observedSlot) slot = BlueprintMealRefinementSlot(null) }
        return
    }
    val original = form.values ?: return
    fun childCurrent(expected: BlueprintEquipmentPreferenceVisit): Boolean =
        slot === observedSlot && equipmentVisit === expected &&
            exact.current(expected.ticket, form, expected.host.current())
    fun closeChild(expected: BlueprintEquipmentPreferenceVisit) {
        if (slot !== observedSlot || equipmentVisit !== expected) return
        // Rotate while the relaxed continuation is still in force, before the current
        // Pantry render supplies its new strict action on return.
        val retained = exact.claim(expected.ticket, form, expected.host.current())
        equipmentVisit = null
        if (!retained) { exact.retire(); slot = BlueprintMealRefinementSlot(null) }
    }
    Dialog(onDismissRequest = { if (child == null) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            if (child != null) child.host.content({ childCurrent(child) }, { closeChild(child) })
            else when (exact.page) {
                BlueprintMealRefinementPage.EFFORT -> {
                    val shown = effortInput?.let { it.copy(allowApply =
                        blueprintEffortTimesValid(it.minutes, it.activeMinutes) && it.effort != null &&
                            it.cleanup in BlueprintCleanupChoice.entries,
                        allowEquipment = equipmentPreferences?.current?.invoke() == true,
                        status = blueprintEffortPresentation(form, enabled = true,
                            equipmentAvailable = equipmentPreferences != null)?.status) }
                        ?: return@Surface
                    BlueprintEffortScreen(shown, onEvent = { event ->
                        when (event) {
                            is BlueprintEffortEvent.EffortChanged -> if (claim()) effortInput = shown.copy(effort = event.value)
                            is BlueprintEffortEvent.MinutesChanged -> if (event.value.length <= 128 && claim()) effortInput = shown.copy(minutes = event.value)
                            is BlueprintEffortEvent.ActiveMinutesChanged -> if (event.value.length <= 128 && claim()) effortInput = shown.copy(activeMinutes = event.value)
                            is BlueprintEffortEvent.CleanupChanged -> if (event.value in shown.allowedCleanupChoices.orEmpty() && claim())
                                effortInput = shown.copy(cleanup = event.value, allowApply = true)
                            BlueprintEffortEvent.ChangeEquipment -> {
                                val provider = equipmentPreferences
                                if (provider != null && provider.current() && claim()) {
                                    val next = BlueprintEquipmentPreferenceVisit(provider, exact.ticket)
                                    equipmentVisit = next
                                    provider.open { childCurrent(next) }
                                }
                            }
                            BlueprintEffortEvent.BackToRequest -> close()
                            BlueprintEffortEvent.Apply -> {
                                val next = blueprintEffortEdit(original, shown)
                                if (next != null && claim()) {
                                    exact.retire(); slot = BlueprintMealRefinementSlot(null)
                                    edit { before -> if (before == original) next else before }
                                }
                            }
                        }
                    }, onBack = ::close, onMore = {}, onNavigate = {})
                }
                BlueprintMealRefinementPage.TASTE -> {
                    var input by remember(exact) { mutableStateOf(blueprintTastePresentation(form, choices, enabled = true)) }
                    val shown = input ?: return@Surface
                    BlueprintTasteScreen(shown, onEvent = { event ->
                        when (event) {
                            is BlueprintTasteEvent.TasteChanged -> if (event.value in blueprintAvailableTastes(choices) && claim())
                                input = shown.copy(taste = event.value, allowApply = true)
                            BlueprintTasteEvent.AnythingWorks -> close() // Original TASTE.02 is Back, not consent to erase tags.
                            BlueprintTasteEvent.Apply -> {
                                val choice = shown.taste
                                val next = choice?.let { blueprintTasteEdit(original, it, choices) }
                                if (shown.allowApply && next != null && claim()) {
                                    exact.retire(); slot = BlueprintMealRefinementSlot(null)
                                    edit { before -> if (before == original) next else before }
                                }
                            }
                        }
                    }, onBack = ::close, onMore = {}, onNavigate = {})
                }
            }
        }
    }
}
