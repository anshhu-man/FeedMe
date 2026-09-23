package com.feedme.app.mealflow

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.feedme.app.blueprint.*
import com.feedme.mealflow.MealFlowPhase
import com.feedme.mealflow.MealFlowScreen

internal enum class BlueprintAdaptationRefinementPage { EFFORT, TASTE }

/** Copies only the fields owned by the selected refinement page. The parent ADAPT host
 * must use this projection rather than copying the dialog result wholesale: the dialog
 * owns no ingredient, equipment, source or saved-recipe authority. */
internal fun blueprintAdaptationRefinementEdit(before: AdaptationFormValues, next: MealFormValues,
    page: BlueprintAdaptationRefinementPage): AdaptationFormValues = before.copy(meal = when (page) {
    BlueprintAdaptationRefinementPage.EFFORT -> before.meal.copy(
        energy = next.energy,
        totalMinutes = next.totalMinutes,
        activeMinutes = next.activeMinutes,
        requiredPreparationTags = next.requiredPreparationTags.toList(),
    )
    BlueprintAdaptationRefinementPage.TASTE -> before.meal.copy(tasteTags = next.tasteTags.toList())
})

/** One local refinement of this exact adaptation and immutable parent, not a new request
 * or a main-meal form. The captured host rejects changed raw meal/navigation/session state. */
internal class BlueprintAdaptationRefinementVisit(
    val page: BlueprintAdaptationRefinementPage,
    val adaptation: AdaptationFormState,
    val form: MealFormState,
    private val originCurrent: () -> Boolean,
) {
    val values: AdaptationFormValues = checkNotNull(adaptation.values)
    private val parent = checkNotNull(adaptation.parent)
    private var active = true

    fun current(meal: MealScreenState, observedForm: MealFormState,
        observedAdaptation: AdaptationFormState?, hostCurrent: Boolean): Boolean {
        val plan = meal.plan
        val valid = active && hostCurrent && originCurrent() && observedForm === form &&
            observedAdaptation === adaptation && blueprintAdaptationRefinementAvailable(meal, form, adaptation) &&
            plan != null && plan.id == parent.plan.id && plan.version.jsonToken == parent.plan.version.jsonToken &&
            plan.document.encodeUtf8().contentEquals(parent.plan.document.encodeUtf8())
        if (!valid) active = false
        return valid
    }

    fun retire() { active = false }
    override fun toString() = "BlueprintAdaptationRefinementVisit(<redacted>)"
}

internal fun blueprintAdaptationRefinementAvailable(meal: MealScreenState, form: MealFormState,
    adaptation: AdaptationFormState?): Boolean =
    meal.screen == MealFlowScreen.ADAPT_MINE && form.values != null && !form.busy && !form.dirty &&
        meal.phase !in setOf(MealFlowPhase.UNAVAILABLE, MealFlowPhase.LOADING, MealFlowPhase.RESOLVING) &&
        !mealStatusPresentation(meal, form, null).originalRequestRetained && meal.pendingAdaptation == null &&
        adaptation?.visible == true && adaptation.values != null && adaptation.parent != null &&
        meal.plan?.status == "ready" &&
        meal.plan.document.encodeUtf8().contentEquals(adaptation.parent.plan.document.encodeUtf8())

/** Original EFFORT/TASTE layouts over a buffered adaptation copy. Back never edits the
 * owner. Apply is the only callback carrying changes; there is no read, Save or request. */
@Composable
internal fun BlueprintAdaptationRefinementDialog(visit: BlueprintAdaptationRefinementVisit,
    choices: MealInputChoices, isCurrent: () -> Boolean, onClose: () -> Unit,
    onApply: (MealFormValues) -> Unit) {
    var attached by remember(visit) { mutableStateOf(true) }
    var ticket by remember(visit) { mutableStateOf(Any()) }
    var effort by remember(visit) { mutableStateOf(blueprintEffortPresentation(visit.values.meal, enabled = true)) }
    var taste by remember(visit) { mutableStateOf(blueprintTastePresentation(visit.values.meal, choices, enabled = true)) }
    DisposableEffect(visit) { onDispose { attached = false; visit.retire() } }
    val renderedTicket = ticket
    fun claim(): Boolean {
        if (!attached || ticket !== renderedTicket || !isCurrent()) return false
        ticket = Any()
        return true
    }
    fun close() {
        if (attached && ticket === renderedTicket) onClose()
    }
    if (!isCurrent()) {
        SideEffect { if (attached) onClose() }
        return
    }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            when (visit.page) {
                BlueprintAdaptationRefinementPage.EFFORT -> {
                    val shown = effort.copy(allowApply = blueprintEffortTimesValid(effort.minutes, effort.activeMinutes) &&
                        effort.effort != null && effort.cleanup in BlueprintCleanupChoice.entries,
                        status = "Only this version’s energy, times and reviewed preparation style change on Apply. Blank or unsupported times stay visible; Apply requires a positive total time. Equipment stays unchanged.")
                    BlueprintEffortScreen(shown, onEvent = { event -> when (event) {
                        is BlueprintEffortEvent.EffortChanged -> if (claim()) effort = shown.copy(effort = event.value)
                        is BlueprintEffortEvent.MinutesChanged -> if (event.value.length <= 128 && claim()) effort = shown.copy(minutes = event.value)
                        is BlueprintEffortEvent.ActiveMinutesChanged -> if (event.value.length <= 128 && claim()) effort = shown.copy(activeMinutes = event.value)
                        is BlueprintEffortEvent.CleanupChanged -> if (event.value in shown.allowedCleanupChoices.orEmpty() && claim())
                            effort = shown.copy(cleanup = event.value, allowApply = true)
                        BlueprintEffortEvent.ChangeEquipment -> Unit
                        BlueprintEffortEvent.BackToRequest -> close()
                        BlueprintEffortEvent.Apply -> {
                            val next = blueprintEffortEdit(visit.values.meal, shown)
                            if (next != null && claim()) onApply(next)
                        }
                    } }, onBack = ::close, onMore = {}, onNavigate = {})
                }
                BlueprintAdaptationRefinementPage.TASTE -> {
                    val shown = taste
                    BlueprintTasteScreen(shown, onEvent = { event -> when (event) {
                        is BlueprintTasteEvent.TasteChanged -> if (event.value in blueprintAvailableTastes(choices) && claim())
                            taste = shown.copy(taste = event.value, allowApply = true)
                        BlueprintTasteEvent.AnythingWorks -> close()
                        BlueprintTasteEvent.Apply -> {
                            val next = shown.taste?.let { blueprintTasteEdit(visit.values.meal, it, choices) }
                            if (shown.allowApply && next != null && claim()) onApply(next)
                        }
                    } }, onBack = ::close, onMore = {}, onNavigate = {})
                }
            }
        }
    }
}
