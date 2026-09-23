package com.feedme.app.mealflow

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.feedme.app.blueprint.*
import com.feedme.mealflow.MealMode

/** UI attachment lifetime only; form values remain owned by the actual meal experience. */
internal class BlueprintMealRequestVisit {
    private var active = true
    var token: Any by mutableStateOf(Any())
        private set
    var manual: Boolean by mutableStateOf(false)
        private set
    private var refinement = false

    fun current(expected: Any, hostCurrent: Boolean): Boolean = active && expected === token && hostCurrent
    fun backgroundCurrent(expected: Any, hostCurrent: Boolean): Boolean = !manual && !refinement && current(expected, hostCurrent)
    fun claim(expected: Any, hostCurrent: Boolean): Boolean {
        if (!current(expected, hostCurrent)) return false
        token = Any()
        return true
    }
    fun openManual(expected: Any, hostCurrent: Boolean): Boolean {
        if (manual || !claim(expected, hostCurrent)) return false
        manual = true
        return true
    }
    fun closeManual(expected: Any, hostCurrent: Boolean): Boolean {
        if (!manual || !claim(expected, hostCurrent)) return false
        manual = false
        return true
    }
    /** Exact local child departure remains possible after private authority is lost. */
    fun returnFromChild(expected: Any): Boolean {
        if (!active || !manual || expected !== token) return false
        token = Any()
        return true
    }
    fun refinementChanged(open: Boolean) {
        if (active && refinement != open) {
            refinement = open
            token = Any()
        }
    }
    fun retire() { active = false }
}

private class BlueprintFoodPreferenceVisit(val host: MealPreferenceEditorHost, val ticket: Any,
    val form: MealFormState)

/** Only an explicitly configured, current retained owner enables interpretation. The
 * separate manual matching path remains available; text itself is never ingredient IDs. */
internal fun blueprintMealRequestPresentation(form: MealFormState, enabled: Boolean = false,
    allowCheckIngredients: Boolean = false, interpretation: MealInterpretationState? = null,
    interpretationAvailable: Boolean = false,
    allowedNavigation: Set<BlueprintScreenId> = emptySet()): BlueprintRequestState? {
    val values = form.values ?: return null
    val textAvailable = interpretationAvailable && interpretation?.text != null && interpretation.proposal == null
    val manualFind = interpretation?.text.orEmpty().isEmpty() && interpretation?.proposal == null &&
        interpretation?.busy != true && blueprintManualMealFindAvailable(values)
    val status = when {
        !interpretationAvailable -> "Text matching isn’t available yet. Use your selected ingredients with Find my dinner, or open More for all manual choices. Draft edits are not saved or submitted automatically."
        interpretation?.busy == true -> "Checking your words. No draft has been changed and no meal has been requested."
        interpretation?.failure != null -> mealInterpretationFailureText(interpretation.failure)
        manualFind -> "Your selected ingredients and limits are ready for an explicit meal request. Find my dinner checks them with your current preferences; More keeps every manual choice available."
        else -> "Review interpreted details before applying them. Confirm only updates this unsaved draft; choose Find my dinner again to request a meal. More opens all manual choices."
    }
    return BlueprintRequestState(request = interpretation?.text.takeIf { interpretationAvailable }.orEmpty(), mode = when (values.mode) {
        MealMode.AUTO -> null
        MealMode.COOK -> BlueprintMealMode.COOK
        MealMode.ASSEMBLE -> BlueprintMealMode.ASSEMBLE
        MealMode.IMPROVE -> BlueprintMealMode.IMPROVE
    }, baseMeal = values.baseDescription,
        enabled = enabled && !form.busy && form.failure == null,
        allowFindMeal = textAvailable && interpretation?.busy == false,
        allowManualFind = manualFind,
        allowRequestText = textAvailable, allowCheckIngredients = allowCheckIngredients,
        allowedNavigation = allowedNavigation, allowMore = true,
        status = status)
}

/** Structural button admission only. Leave exact numeric validation to the existing
 * controller, including fractional servings/exponents; never parse them through Double. */
internal fun blueprintManualMealFindAvailable(values: MealFormValues): Boolean =
    values.ingredientIds.isNotEmpty() && values.savedRecipeId == null &&
        values.sourceRecipeVersionId == null && !values.savedMakeMine && !values.preferencesPendingSync &&
        values.servings.isNotBlank() &&
        (values.mode != MealMode.IMPROVE || values.baseDescription.isNotBlank())

/** No suspension may split this sequence. Opening the local slot first keeps its exact
 * render valid; immediately consuming the interpretation prevents a late reply covering it.
 * Both actions occur on the same serialized UI dispatcher before a response can interleave. */
internal fun blueprintMealRequestChildEntry(accept: () -> Boolean, open: () -> Unit, dismiss: () -> Unit): Boolean {
    if (!accept()) return false
    open()
    dismiss()
    return true
}

/** Change only the field actually edited; all source identities, constraints and base
 * composition/preparation remain unchanged. AUTO is displayed as unselected, not guessed. */
internal fun blueprintMealRequestEdit(before: MealFormValues, event: BlueprintRequestEvent): MealFormValues? = when (event) {
    is BlueprintRequestEvent.ModeChanged -> before.copy(mode = when (event.value) {
        BlueprintMealMode.COOK -> MealMode.COOK
        BlueprintMealMode.ASSEMBLE -> MealMode.ASSEMBLE
        BlueprintMealMode.IMPROVE -> MealMode.IMPROVE
    })
    is BlueprintRequestEvent.BaseMealChanged -> if (event.value.length <= 1000) before.copy(baseDescription = event.value) else null
    else -> null
}

/** Controlled adapter, not an interpreter or a newly admitted flow. The host must pass the
 * unchanged real manual form in [manualContent], including its normal errors and Find action.
 * It MUST bind the supplied `isCurrent` to every manual callback, checking it both at click
 * and inside queued actions, in addition to the existing exact controller/lease admission.
 * `onBack` returns from that manual view without saving or discarding the owned draft.
 * Do not integrate by passing unguarded MealScreenActions into the manual slot.
 */
@Composable
internal fun BlueprintMealRequest(meal: MealScreenState, form: MealFormState, choices: MealInputChoices,
    actions: MealScreenActions,
    manualContent: @Composable (onBack: () -> Unit, isCurrent: () -> Boolean, onFoodPreferences: (() -> Unit)?) -> Unit,
    actionFactory: ((isCurrent: () -> Boolean) -> MealScreenActions)? = null,
    modifier: Modifier = Modifier) {
    if (!blueprintMealLandingEligible(meal, form)) {
        // Pending originals, sources and errors keep the actual recovery presentation.
        manualContent(actions.back, actions.blueprintCurrent, null)
        return
    }
    val interpretation = actions.interpretation
    if (interpretation?.proposal != null && actions.blueprintCurrent()) {
        BlueprintMealInterpretationReview(interpretation, form, actions)
        return
    }
    val visit = remember { BlueprintMealRequestVisit() }
    var foodVisit by remember(visit) { mutableStateOf<BlueprintFoodPreferenceVisit?>(null) }
    DisposableEffect(visit) { onDispose { visit.retire() } }
    BlueprintMealRefinementControls(form, choices, actions, content = { open, refinementOpen ->
        SideEffect { visit.refinementChanged(refinementOpen) }
        val token = visit.token
        val manual = visit.manual
        fun backgroundCurrent() = !manual && !refinementOpen && !visit.manual &&
            visit.current(token, actions.blueprintCurrent())
        fun claim() = backgroundCurrent() && visit.claim(token, actions.blueprintCurrent())
        fun claimedActions(): MealScreenActions? {
            if (actionFactory == null || !claim()) return null
            val claimed = visit.token
            return actionFactory { visit.backgroundCurrent(claimed, actions.blueprintCurrent()) }
        }
        fun openChild(page: BlueprintMealRefinementPage) {
            blueprintMealRequestChildEntry(::claim, { open(page) }, { actions.dismissInterpretation?.invoke() })
        }
        val original = form.values ?: return@BlueprintMealRefinementControls
        val interpretationAvailable = actions.interpretationText != null && actions.interpret != null && actions.interpretationCurrent()
        val presentation = blueprintMealRequestPresentation(form, backgroundCurrent(), actions.kitchen != null && actionFactory != null,
            interpretation, interpretationAvailable,
            blueprintMealTabDestinations(form, actions, available = actionFactory != null))?.let {
                if (actionFactory == null) it.copy(allowFindMeal = false, allowManualFind = false) else it
            }
            ?: return@BlueprintMealRefinementControls
        BlueprintRequestScreen(presentation, onEvent = { event ->
            when (event) {
                is BlueprintRequestEvent.ModeChanged, is BlueprintRequestEvent.BaseMealChanged -> {
                    val next = blueprintMealRequestEdit(original, event)
                    if (next != null && claim()) actions.edit { before -> if (before == original) next else before }
                }
                BlueprintRequestEvent.CheckIngredients -> claimedActions()?.kitchen?.invoke(KitchenInputPage.PANTRY)
                BlueprintRequestEvent.SetEffort -> openChild(BlueprintMealRefinementPage.EFFORT)
                BlueprintRequestEvent.ChooseTaste -> openChild(BlueprintMealRefinementPage.TASTE)
                is BlueprintRequestEvent.RequestChanged -> if (presentation.allowRequestText &&
                    actions.interpretationCurrent() && claim()) actions.interpretationText?.invoke(event.value)
                BlueprintRequestEvent.FindMeal -> if (presentation.canFindMeal) {
                    if (presentation.allowManualFind) {
                        claimedActions()?.find?.invoke()
                    } else if (actions.interpretationCurrent()) claimedActions()?.interpret?.invoke()
                }
            }
        }, onBack = { if (claim()) actions.back() },
            onMore = { blueprintMealRequestChildEntry(
                { backgroundCurrent() && visit.openManual(token, actions.blueprintCurrent()) }, {},
                { actions.dismissInterpretation?.invoke() }) },
            onNavigate = { destination ->
                val admitted = presentation.allowedNavigation.orEmpty()
                if (presentation.enabled && destination in admitted)
                    claimedActions()?.let { dispatchBlueprintMealTab(destination, admitted, it) }
            }, modifier = modifier)
        if (manual) {
            val food = foodVisit
            val current = { foodVisit == null && visit.manual && visit.current(token, actions.blueprintCurrent()) }
            val close = {
                if (foodVisit == null && visit.manual && visit.current(token, true))
                    visit.closeManual(token, true)
                Unit
            }
            fun foodCurrent(expected: BlueprintFoodPreferenceVisit) = foodVisit === expected &&
                expected.form === form && visit.manual && visit.current(expected.ticket, actions.blueprintCurrent()) &&
                expected.host.current()
            fun closeFood(expected: BlueprintFoodPreferenceVisit) {
                if (foodVisit !== expected) return
                if (visit.returnFromChild(expected.ticket)) foodVisit = null
            }
            val openFood: (() -> Unit)? = actions.foodPreferences?.let { provider -> {
                if (current() && provider.current() && visit.claim(token, actions.blueprintCurrent())) {
                    val next = BlueprintFoodPreferenceVisit(provider, visit.token, form)
                    foodVisit = next
                    provider.open { foodCurrent(next) }
                }
            } }
            Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(Modifier.fillMaxSize()) { manualContent(close, current, openFood) }
            }
            if (food != null) {
                // Keep the actual manual meal form composed beneath the preference child.
                // Its guarded actions are disabled; returning does not save or reset it.
                Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    Surface(Modifier.fillMaxSize()) {
                        food.host.content({ foodCurrent(food) }, { closeFood(food) })
                    }
                }
            }
        }
    })
}
