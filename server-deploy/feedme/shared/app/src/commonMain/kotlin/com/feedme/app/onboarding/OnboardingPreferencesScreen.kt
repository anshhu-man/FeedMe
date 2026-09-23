package com.feedme.app.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.feedme.app.*
import com.feedme.app.blueprint.BlueprintAppBar
import com.feedme.app.blueprint.BlueprintPreferenceContent
import com.feedme.app.blueprint.BlueprintPreferencePage
import com.feedme.app.blueprint.BlueprintPreferenceState
import com.feedme.core.ports.*
import com.feedme.session.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Borrowed FOOD_PREFS/EQUIPMENT child. Mount, disposal and recreation perform no I/O.
 * The parent retains [formMemory] for this exact child/prompt across Activity recreation and
 * clears it on account retirement. Neither a form nor a callback constructs answer authority.
 */
@Composable
fun FeedMeOnboardingPreferencesFlow(
    controller: OnboardingPreferencesController,
    prompt: OnboardingOptionalPrompt,
    choices: OnboardingPreferenceChoices?,
    formMemory: OnboardingPreferencesFormMemory,
    clock: EpochClock,
    onBack: () -> Unit,
    onDecision: (OnboardingPreferencesState, OnboardingDecisionChoice) -> Unit,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
    hostIsCurrent: () -> Boolean = { true },
) {
    val hostCurrent by rememberUpdatedState(hostIsCurrent)
    val decisionCallback by rememberUpdatedState(onDecision)
    val backCallback by rememberUpdatedState(onBack)
    val observedState by controller.states.collectAsState()
    val state = observedState // Every callback belongs to this exact rendered state.
    val observedMemory by formMemory.states.collectAsState()
    val memory = observedMemory
    val actions = remember(controller, prompt, formMemory) { OnboardingPreferencesActions(controller, prompt) }
    val navigation = remember(controller, prompt, formMemory) { ProfileActionGate() }
    val actionUi by actions.states.collectAsState()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var attached by remember(controller, formMemory) { mutableStateOf(true) }
    var leaving by remember(controller, formMemory) { mutableStateOf(false) }
    var navigating by remember(controller, formMemory) { mutableStateOf(false) }
    var leaveQuestion by remember(controller, formMemory) { mutableStateOf(false) }
    var skipQuestion by remember(controller, formMemory) { mutableStateOf(false) }
    var discardQuestion by remember(controller, formMemory) { mutableStateOf<OnboardingPreferencesState?>(null) }
    var closeFailure by remember(controller, formMemory) { mutableStateOf(false) }
    var showPreferenceTools by remember(controller, prompt, formMemory) { mutableStateOf(false) }
    var now by remember(controller, clock) { mutableStateOf(preferenceDisplayTime(clock)) }
    val visible = hostCurrent() && formMemory.isBoundTo(controller, prompt) && !memory.retired &&
        state.screen != OnboardingPreferencesScreen.UNAVAILABLE && controller.isCurrentState(state)
    val busy = actionUi.busy || leaving || navigating
    val editable = visible && !busy && !state.recoveryRequired
    val fields = memory.buffer.input
    val validation = preferencesValidation(prompt, fields)
    val terminal = state.status in setOf(OnboardingPreferencesStatus.APPLIED, OnboardingPreferencesStatus.DISCARDED, OnboardingPreferencesStatus.REJECTED)
    val needsRecovery = state.recoveryRequired || state.status == OnboardingPreferencesStatus.RETAINED_RESPONSE ||
        (terminal && !state.completionAcknowledged)
    val canSkip = !needsRecovery && (state.status == OnboardingPreferencesStatus.LOCAL || terminal)
    val samePromptApplied = state.status == OnboardingPreferencesStatus.APPLIED && preferenceOriginalForPrompt(state.original, prompt)
    val previousPromptApplied = state.status == OnboardingPreferencesStatus.APPLIED && !samePromptApplied
    val review = actionUi.review
    val reviewCurrent = review != null && controller.isCurrentReview(review)
    val blueprint = preferencesBlueprintState(prompt, state.status, needsRecovery, visible, busy,
        state.baselineFresh, validation.canSave, canSkip && validation.canKeep)

    DisposableEffect(actions, navigation) {
        onDispose { attached = false; actions.retire(); navigation.retire() }
    }
    // Observe only exact live native projections. No automatic Keep/GET; dirty RAM survives.
    SideEffect {
        val draft = preferencesFormInput(state.draft)
        if (visible && controller.isCurrentState(state) && formMemory.states.value.buffer.base != draft) formMemory.observe(draft)
        if (!visible) { leaveQuestion = false; skipQuestion = false; discardQuestion = null; showPreferenceTools = false }
        if (review != null && !reviewCurrent && !actionUi.busy) actions.dismissReview()
    }
    LaunchedEffect(controller, clock, state.retryAtMillis, review) {
        now = preferenceDisplayTime(clock)
        while ((review != null && controller.isCurrentReview(review)) ||
            (state.retryAtMillis?.let { now >= 0 && now < it } == true)) {
            delay(500); now = preferenceDisplayTime(clock)
        }
    }
    fun current() = attached && hostCurrent() && formMemory.isBoundTo(controller, prompt) && !formMemory.states.value.retired
    fun deliver(selected: OnboardingPreferencesState, choice: OnboardingDecisionChoice) {
        if (!current() || leaving || navigating || formMemory.states.value.buffer.dirty || !controller.isCurrentState(selected)) return
        if (choice == OnboardingDecisionChoice.ANSWERED &&
            (selected.status != OnboardingPreferencesStatus.APPLIED || !selected.completionAcknowledged || !preferenceOriginalForPrompt(selected.original, prompt))) return
        val ticket = navigation.claim(selected, controller.states.value) ?: return
        navigating = true
        focus.clearFocus()
        // No suspension between the final attachment/state check and the parent callback.
        if (current() && !leaving && !formMemory.states.value.buffer.dirty && navigation.running(ticket) && controller.isCurrentState(selected))
            decisionCallback(selected, choice) else navigating = false
    }
    fun leave() {
        if (!attached || !hostCurrent() || leaving || navigating) return
        leaving = true; leaveQuestion = false; skipQuestion = false; discardQuestion = null; closeFailure = false
        actions.retire(); navigation.retire(); focus.clearFocus()
        scope.launch {
            try {
                if (!attached || !hostCurrent()) return@launch
                when (controller.back()) {
                    is PortResult.Value -> if (attached && hostCurrent()) backCallback()
                    is PortResult.Failure -> if (attached && hostCurrent()) { leaving = false; closeFailure = true }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (attached && hostCurrent()) { leaving = false; closeFailure = true } }
        }
    }
    fun act(action: PreferencesAction, keepThenBack: Boolean = false, keepThenSkip: Boolean = false) {
        if (!current() || leaving || navigating || actions.states.value.busy || !controller.isCurrentState(state)) return
        // A queued click cannot silently adopt a newer RAM form/query that was not rendered.
        val latest = formMemory.states.value
        if (latest.buffer.input != fields || (action == PreferencesAction.SEARCH && latest.query != memory.query)) return
        val captured = fields
        val checked = preferencesValidation(prompt, captured)
        if ((action == PreferencesAction.KEEP && !checked.canKeep) || (action == PreferencesAction.SAVE && !checked.canSave)) return
        val call = actions.claim(action, state, if (action in setOf(PreferencesAction.KEEP, PreferencesAction.SAVE)) captured else null,
            if (action == PreferencesAction.SEARCH) memory.query else null) ?: return
        focus.clearFocus()
        scope.launch {
            if (!current()) return@launch
            val result = actions.execute(call)
            if (result != null && current() && !leaving && !navigating && actions.canDeliver(call, result)) {
                if (action in setOf(PreferencesAction.KEEP, PreferencesAction.SAVE)) formMemory.kept(captured)
                if (keepThenBack && !formMemory.states.value.buffer.dirty) leave()
                else if (keepThenSkip && !formMemory.states.value.buffer.dirty) deliver(result, OnboardingDecisionChoice.SKIPPED)
                else if (action == PreferencesAction.SAVE && !formMemory.states.value.buffer.dirty) deliver(result, OnboardingDecisionChoice.ANSWERED)
            }
        }
    }
    fun edit(next: OnboardingPreferencesFields) {
        if (current() && !leaving && !navigating && !actions.states.value.busy && !state.recoveryRequired && controller.isCurrentState(state))
            if (formMemory.states.value.buffer.input == fields) formMemory.edit(next)
    }
    fun requestSkip() {
        if (current() && !leaving && !navigating && !actions.states.value.busy && canSkip && validation.canKeep &&
            controller.isCurrentState(state) && formMemory.states.value.buffer.input == fields) skipQuestion = true
    }
    val back: () -> Unit = {
        if (attached && hostCurrent() && !navigating) when {
            leaveQuestion -> leaveQuestion = false
            skipQuestion -> skipQuestion = false
            discardQuestion != null -> discardQuestion = null
            review != null && !actions.states.value.busy -> actions.dismissReview()
            visible && formMemory.states.value.buffer.dirty -> leaveQuestion = true
            else -> leave()
        }
    }
    platformBackHandler(attached && hostCurrent() && !navigating, back)

    FeedMeTheme {
        Box(Modifier.fillMaxSize().background(FeedMeColors.Paper).safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
            if (blueprint != null) BlueprintAppBar(title = "Kitchen", onBack = back,
                backEnabled = attached && hostCurrent() && !navigating,
                onMore = {
                    if (current() && !leaving && !navigating && controller.isCurrentState(state)) showPreferenceTools = !showPreferenceTools
                }, moreEnabled = !busy)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (blueprint == null) {
                TextButton(enabled = attached && hostCurrent() && !navigating, onClick = back, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
                FeedMeWordmark(compact = true)
                FeedMeStatusLabel("Optional setup", if (prompt == OnboardingOptionalPrompt.FOOD_PREFERENCES) FeedMeColors.SoftLime else FeedMeColors.Lilac)
                Text(preferencesTitle(prompt),
                    style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                }
                if (!visible) PreferenceNotice("Account unavailable", "Your choices are hidden. Go back to check your account connection. Saved work is not sent automatically.")
                else {
                    if (blueprint == null) Text(preferencesIntro(prompt))
                    if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Working on your choices…", Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    preferenceFailure(actionUi.failure ?: state.failureReason)?.let { PreferenceNotice("Couldn’t confirm that", it, error = true) }
                    if (state.status != OnboardingPreferencesStatus.LOCAL || needsRecovery)
                        PreferenceNotice(if (previousPromptApplied && !needsRecovery) "Start this optional step" else preferenceStatusTitle(state.status, needsRecovery),
                            if (previousPromptApplied && !needsRecovery) "The previous step’s save remains kept. Load current choices, then start a new draft for this step. Nothing sends automatically."
                            else preferenceStatusBody(state.status, needsRecovery))
                    val canonicalForm: @Composable () -> Unit = {
                        OnboardingPreferencesForm(prompt, fields, state.preferences, choices, state.ingredientSearch, memory.query, editable,
                            onEdit = ::edit, onQuery = { if (current() && !leaving && !navigating && !actions.states.value.busy && controller.isCurrentState(state)) formMemory.setQuery(it) },
                            onSearch = { act(PreferencesAction.SEARCH) }, onMore = { act(PreferencesAction.MORE) }, servingsError = validation.servingsError,
                            embedded = blueprint != null)
                    }
                    if (blueprint != null) {
                        BlueprintPreferenceContent(blueprint, onFieldChange = { _, _ ->
                            // The actual form below owns canonical IDs and null/empty semantics.
                            // Raw prototype choice labels never become a preference patch.
                        }, onAction = { id ->
                            when (id) {
                                "${blueprint.page.name}.01" -> act(PreferencesAction.SAVE)
                                "${blueprint.page.name}.02" -> requestSkip()
                            }
                        }, formContent = {
                            if (!state.baselineFresh) {
                                PreferencePrimary("Load current choices", !busy) { act(PreferencesAction.LOAD) }
                                Text("Load the current version before saving. Your typing stays here.", style = MaterialTheme.typography.bodySmall)
                            }
                            canonicalForm()
                        })
                    } else canonicalForm()
                    validation.formError?.let { Text(it, color = if (validation.canKeep) FeedMeColors.Muted else MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    if (memory.buffer.dirty) Text("Changes not kept yet.", style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
                    else if (actionUi.keptAt === state) Text("Draft kept on this device.", style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
                    when {
                        needsRecovery -> PreferencePrimary("Recover saved work", !busy) { act(PreferencesAction.RECOVER) }
                        state.status in setOf(OnboardingPreferencesStatus.PREPARED, OnboardingPreferencesStatus.UNCERTAIN) -> {
                            PreferencePrimary("Review original save", !busy && now >= 0 && (state.retryAtMillis?.let { now >= it } != false)) { act(PreferencesAction.REVIEW_ORIGINAL) }
                            Text("Retry sends only the original request. Your draft above stays separate.", style = MaterialTheme.typography.bodySmall)
                        }
                        samePromptApplied -> {
                            PreferencePrimary("Continue", !busy && !memory.buffer.dirty) {
                                deliver(state, OnboardingDecisionChoice.ANSWERED)
                            }
                            if (memory.buffer.dirty) Text("Keep your newer changes before continuing. They will not replace the saved answer.")
                        }
                        state.status == OnboardingPreferencesStatus.LOCAL -> {
                            if (blueprint == null && !state.baselineFresh) {
                                PreferencePrimary("Load current choices", !busy) { act(PreferencesAction.LOAD) }
                                Text("Load the current version before saving. Your typing stays here.", style = MaterialTheme.typography.bodySmall)
                            }
                            if (blueprint == null) PreferencePrimary(preferencesSaveLabel(prompt), !busy && state.baselineFresh && validation.canSave) { act(PreferencesAction.SAVE) }
                        }
                        else -> when {
                            !state.baselineFresh -> {
                                PreferencePrimary("Load current choices", !busy) { act(PreferencesAction.LOAD) }
                                Text("Load the current version before starting this step’s save. Your newer changes stay in the form.")
                            }
                            memory.buffer.dirty -> {
                                PreferencePrimary("Keep changes on device", !busy && validation.canKeep) { act(PreferencesAction.KEEP) }
                                Text("Keep your changes locally before starting a new draft. This does not send them or change the original save.")
                            }
                            else -> {
                                PreferencePrimary(if (previousPromptApplied) "Start this step’s draft" else "Edit your draft", !busy) { act(PreferencesAction.START_NEW) }
                                Text("Start a new draft using the current loaded version. Nothing is sent until you choose ${preferencesSaveLabel(prompt)}.")
                            }
                        }
                    }
                    if (blueprint == null) PreferenceSecondary(preferencesSkipLabel(prompt), !busy && canSkip && validation.canKeep) { requestSkip() }
                    if (blueprint == null || showPreferenceTools) FeedMeDetails("Saved choices and recovery") {
                        PreferenceSecondary("Keep on device", editable && validation.canKeep) { act(PreferencesAction.KEEP) }
                        state.preferences?.let { PreferenceOriginalFields(if (state.baselineFresh) "Last loaded choices" else "Retained choices", it, state.ingredientSearch?.items.orEmpty()) }
                        state.original?.let { PreferenceOriginalFields("Original save — separate from your draft", it, state.ingredientSearch?.items.orEmpty()) }
                        if (!needsRecovery) PreferenceSecondary("Load current choices", !busy) { act(PreferencesAction.LOAD) }
                        if (terminal && state.completionAcknowledged)
                            PreferenceSecondary("Edit your draft", !busy && state.baselineFresh && !memory.buffer.dirty) { act(PreferencesAction.START_NEW) }
                        if (state.status == OnboardingPreferencesStatus.PREPARED && !needsRecovery)
                            PreferenceSecondary("Discard unsent save", !busy) { discardQuestion = state }
                        Text("Keep on device changes only your local draft. Save sends only this prompt’s fields. Skip records a skipped step without sending preference changes.")
                    }
                }
                if (closeFailure) PreferenceNotice("Couldn’t close this step", "Use Back again to finish closing. No navigation or new save was confirmed.", error = true)
            }
            }
        }
        if (visible && review != null && reviewCurrent) AlertDialog(onDismissRequest = { if (!busy) actions.dismissReview() },
            title = { Text("Review original save") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Retry only the original choices below. Your newer draft is not sent by this action.")
                    PreferenceOriginalFields("Original choices", review.fields, state.ingredientSearch?.items.orEmpty())
                    PreferenceOriginalFields("Separate current draft", fields, state.ingredientSearch?.items.orEmpty())
                }
            }, confirmButton = { TextButton(enabled = !busy, onClick = { act(PreferencesAction.CONFIRM_ORIGINAL) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry original save") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { actions.dismissReview() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep editing") } })
        if (visible && skipQuestion) AlertDialog(onDismissRequest = { skipQuestion = false }, title = { Text("Skip this optional step?") },
            text = { Text(if (memory.buffer.dirty) "Keep your typing on this device, then record Skip. Your choices will not be sent."
                else "Record Skip without sending preference changes. Any kept draft remains on this device.") },
            confirmButton = { TextButton(enabled = !busy && canSkip && validation.canKeep, onClick = {
                skipQuestion = false
                if (formMemory.states.value.buffer.dirty) act(PreferencesAction.KEEP, keepThenSkip = true)
                else deliver(state, OnboardingDecisionChoice.SKIPPED)
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (memory.buffer.dirty) "Keep and skip" else "Skip") } },
            dismissButton = { TextButton(onClick = { skipQuestion = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep editing") } })
        discardQuestion?.takeIf { visible && it === state }?.let {
            AlertDialog(onDismissRequest = { discardQuestion = null }, title = { Text("Discard only the unsent save?") },
                text = { Text("Your kept draft and newer typing remain. This does not discard a request that may have reached the service.") },
                confirmButton = { TextButton(enabled = !busy, onClick = { discardQuestion = null; act(PreferencesAction.DISCARD_UNSENT) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Discard unsent save") } },
                dismissButton = { TextButton(onClick = { discardQuestion = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep this save") } })
        }
        if (visible && leaveQuestion) AlertDialog(onDismissRequest = { leaveQuestion = false }, title = { Text("Keep your changes before leaving?") },
            text = { Text("Only changes not kept yet can be lost. An original save stays available for recovery. Leaving never sends or discards it.") },
            confirmButton = { TextButton(enabled = !busy && validation.canKeep, onClick = { act(PreferencesAction.KEEP, keepThenBack = true) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep and back") } },
            dismissButton = { Column {
                // Parent drops this RAM only after child close ACK; failed close retains it.
                TextButton(onClick = { leave() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Leave without keeping changes") }
                TextButton(onClick = { leaveQuestion = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep editing") }
            } })
    }
}

/** Only the ordinary local form borrows the original presentation. No draft values are converted:
 * real ingredient IDs, multi-selections, exact serving tokens and omission stay in the form owner.
 */
internal fun preferencesBlueprintState(
    prompt: OnboardingOptionalPrompt,
    status: OnboardingPreferencesStatus,
    recoveryRequired: Boolean,
    visible: Boolean,
    busy: Boolean,
    baselineFresh: Boolean,
    canSave: Boolean,
    canSkip: Boolean,
): BlueprintPreferenceState? {
    if (!visible || recoveryRequired || status != OnboardingPreferencesStatus.LOCAL) return null
    val page = when (prompt) {
        OnboardingOptionalPrompt.FOOD_PREFERENCES -> BlueprintPreferencePage.FOOD_PREFS
        OnboardingOptionalPrompt.EQUIPMENT -> BlueprintPreferencePage.EQUIPMENT
    }
    return BlueprintPreferenceState(page = page, busy = busy, enabledActionIds = buildSet {
        if (!busy && baselineFresh && canSave) add("${page.name}.01")
        if (!busy && canSkip) add("${page.name}.02")
    })
}

/** Pure form renderer, with exact untrimmed values and explicit empty choices. */
@Composable
internal fun OnboardingPreferencesForm(prompt: OnboardingOptionalPrompt, fields: OnboardingPreferencesFields,
    saved: OnboardingPreferencesFields?, choices: OnboardingPreferenceChoices?, search: OnboardingIngredientSearch?, query: String, enabled: Boolean,
    onEdit: (OnboardingPreferencesFields) -> Unit, onQuery: (String) -> Unit, onSearch: () -> Unit, onMore: () -> Unit,
    servingsError: String?, embedded: Boolean = false) {
    val focus = LocalFocusManager.current
    // A saved field is only the explicit edit's basis. Merely showing it never supplies a patch key.
    val excluded = fields.hardExcludedIngredientIds ?: saved?.hardExcludedIngredientIds
    val disliked = fields.dislikedIngredientIds ?: saved?.dislikedIngredientIds
    if (prompt == OnboardingOptionalPrompt.FOOD_PREFERENCES) {
        IngredientSelection("Exclude ingredients", "Choose ingredients you want left out.", excluded, fields.hardExcludedIngredientIds != null, search?.items.orEmpty(), enabled,
            "Choose no exclusions", embedded) { onEdit(fields.copy(hardExcludedIngredientIds = it)) }
        PreferenceSelection("Eating preferences", "Choose any that fit.", fields.dietaryPatterns ?: saved?.dietaryPatterns, fields.dietaryPatterns != null, choices?.dietaryPatterns, enabled,
            "Choose no eating preference", embedded) { onEdit(fields.copy(dietaryPatterns = it)) }
        IngredientSelection("Taste dislikes", "Ingredients you would rather avoid for taste.", disliked, fields.dislikedIngredientIds != null, search?.items.orEmpty(), enabled,
            "Choose no taste dislikes", embedded) { onEdit(fields.copy(dislikedIngredientIds = it)) }
        PreferenceCard(embedded) {
            FeedMeSectionHeading("Find an ingredient", "Search, then choose Exclude or Dislike. A missing result never removes a saved selection.")
            OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(), enabled = enabled, label = { Text("Ingredient search") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { if (enabled) onSearch() }))
            PreferenceSecondary("Search", enabled) { onSearch() }
            search?.let { observed ->
                Text("Results for: ${observed.query}", style = MaterialTheme.typography.titleMedium)
                if (observed.items.isEmpty()) Text("No matches returned. Your saved selections are unchanged.")
                observed.items.forEach { ingredient ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(ingredient.name, style = MaterialTheme.typography.titleMedium)
                        if (ingredient.aliases.isNotEmpty()) Text(ingredient.aliases.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                        PreferenceSecondary("Exclude ${ingredient.name}", enabled && excluded.orEmpty().none { it.equals(ingredient.id, true) }) {
                            onEdit(fields.copy(hardExcludedIngredientIds = excluded.orEmpty() + ingredient.id))
                        }
                        PreferenceSecondary("Dislike ${ingredient.name}", enabled && disliked.orEmpty().none { it.equals(ingredient.id, true) }) {
                            onEdit(fields.copy(dislikedIngredientIds = disliked.orEmpty() + ingredient.id))
                        }
                    }
                }
                if (observed.hasMore) PreferenceSecondary("Load more results", enabled) { onMore() }
            }
            Text("These choices do not confirm allergy safety. Choosing no exclusions does not declare that you have no allergies.", style = MaterialTheme.typography.bodySmall)
        }
    } else {
        PreferenceSelection("Equipment", "Select the tools available in your kitchen.", fields.equipmentIds ?: saved?.equipmentIds, fields.equipmentIds != null, choices?.equipment, enabled,
            "Choose no equipment", embedded) { onEdit(fields.copy(equipmentIds = it)) }
        PreferenceCard(embedded) {
            FeedMeSectionHeading("Usual servings", "Optional. Enter the amount you usually cook; the exact number you type is kept.")
            OutlinedTextField(fields.defaultServings.orEmpty(), { onEdit(fields.copy(defaultServings = it)) }, Modifier.fillMaxWidth(),
                enabled = enabled, label = { Text("Servings") }, isError = servingsError != null,
                supportingText = { Text(servingsError ?: if (fields.defaultServings == null) "Not answered." else "Use a number of at least 0.1.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
            PreferenceSecondary("Leave servings unanswered", enabled && fields.defaultServings != null) { onEdit(fields.copy(defaultServings = null)) }
        }
    }
}

private fun preferenceDisplayTime(clock: EpochClock) = try { clock.nowMillis() } catch (_: Exception) { -1L }
private fun preferenceOriginalForPrompt(fields: OnboardingPreferencesFields?, prompt: OnboardingOptionalPrompt): Boolean = fields != null &&
    fields.preferredTasteTags == null && fields.defaultEnergy == null && fields.consentVersion == null && when (prompt) {
        OnboardingOptionalPrompt.FOOD_PREFERENCES -> fields.equipmentIds == null && fields.defaultServings == null &&
            (fields.hardExcludedIngredientIds != null || fields.dietaryPatterns != null || fields.dislikedIngredientIds != null)
        OnboardingOptionalPrompt.EQUIPMENT -> fields.hardExcludedIngredientIds == null && fields.dietaryPatterns == null && fields.dislikedIngredientIds == null &&
            (fields.equipmentIds != null || fields.defaultServings != null)
    }
private fun preferenceFailure(reason: FailureReason?): String? = when (reason) {
    null -> null
    FailureReason.OFFLINE -> "You’re offline. Keep a draft on this device, then reconnect to load or save."
    FailureReason.INVALID_DATA -> "Check your choices and number format. Nothing is trimmed or replaced automatically."
    FailureReason.RATE_LIMITED -> "Please wait before retrying the same request."
    FailureReason.STALE_SESSION, FailureReason.UNAUTHENTICATED, FailureReason.FORBIDDEN -> "This account connection is unavailable. Go back to check it."
    FailureReason.CONFLICT -> "This view changed. Check the saved work and current choices before trying again."
    else -> "The action could not be confirmed. Keep any original request until its outcome is clear."
}
private fun preferenceStatusTitle(status: OnboardingPreferencesStatus, recovery: Boolean) = when {
    recovery -> "Recover saved work first"
    status == OnboardingPreferencesStatus.PREPARED -> "This save hasn’t been sent"
    status == OnboardingPreferencesStatus.UNCERTAIN -> "This save isn’t confirmed"
    status == OnboardingPreferencesStatus.APPLIED -> "Choices saved"
    status == OnboardingPreferencesStatus.REJECTED -> "Your choices changed elsewhere"
    status == OnboardingPreferencesStatus.DISCARDED -> "Unsent save discarded"
    else -> "Your saved choices"
}
private fun preferenceStatusBody(status: OnboardingPreferencesStatus, recovery: Boolean) = when {
    recovery -> "Explicit recovery checks the work on this device. It does not send or retry a save."
    status == OnboardingPreferencesStatus.PREPARED -> "Review the exact original or discard only its unsent request. Newer draft changes stay separate."
    status == OnboardingPreferencesStatus.UNCERTAIN -> "The service may have received the original. Review it before retrying; do not replace it with your newer draft."
    status == OnboardingPreferencesStatus.APPLIED -> "Continue to record this saved answer for the optional step. This does not grant account access or complete other requirements."
    status == OnboardingPreferencesStatus.REJECTED -> "The original was not applied because its starting version changed. Load current choices, compare, then start a new save."
    status == OnboardingPreferencesStatus.DISCARDED -> "Only the unsent request was discarded. Your kept draft and newer typing remain."
    else -> "No save is sent automatically."
}

/** Stateless controls: only explicit callbacks change RAM; no controller or I/O here. */
@Composable
private fun PreferenceSelection(title: String, description: String, selected: List<String>?, supplied: Boolean,
    choices: List<OnboardingPreferenceChoice>?, enabled: Boolean, emptyLabel: String, embedded: Boolean,
    onChange: (List<String>) -> Unit) {
    PreferenceCard(embedded) {
        FeedMeSectionHeading(title, description)
        if (choices == null) Text("These choices are not configured yet. Existing selections stay kept; you can skip this optional step.")
        else choices.forEach { choice ->
            val id = choice.id; val label = choice.label
            val checked = selected?.contains(id) == true
            val choiceEnabled = enabled && (choice.selectable || checked)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = choiceEnabled,
                role = Role.Checkbox, onValueChange = { onChange(if (it) selected.orEmpty() + id else selected.orEmpty() - id) }),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = null, enabled = choiceEnabled)
                Text(label, Modifier.weight(1f).padding(vertical = 10.dp))
            }
        }
        val known = choices.orEmpty().map { it.id }.toSet()
        selected.orEmpty().filterNot { it in known }.forEach { id ->
            PreferenceUnresolved(id, enabled) { onChange(selected.orEmpty() - id) }
        }
        Text(when { !supplied -> "Saved choices shown; no new answer kept for this section."; selected == null -> "Not answered."; selected.isEmpty() -> "Explicitly none selected."; else -> "${selected.size} selected." },
            style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
        PreferenceSecondary(emptyLabel, enabled && choices != null) { onChange(emptyList()) }
    }
}

@Composable
private fun IngredientSelection(title: String, description: String, selected: List<String>?, supplied: Boolean,
    results: List<OnboardingIngredient>, enabled: Boolean, emptyLabel: String, embedded: Boolean, onChange: (List<String>) -> Unit) {
    PreferenceCard(embedded) {
        FeedMeSectionHeading(title, description)
        if (!supplied) Text("Saved choices shown; no new answer kept for this section.", style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
        else if (selected == null) Text("Not answered.", style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
        else if (selected.isEmpty()) Text("Explicitly none selected.", style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
        selected.orEmpty().forEach { id ->
            val label = results.firstOrNull { it.id.equals(id, true) }?.name
            if (label == null) PreferenceUnresolved(id, enabled) { onChange(selected.orEmpty().filterNot { it.equals(id, true) }) }
            else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f))
                TextButton(enabled = enabled, onClick = { onChange(selected.orEmpty().filterNot { it.equals(id, true) }) },
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove $label") }
            }
        }
        PreferenceSecondary(emptyLabel, enabled) { onChange(emptyList()) }
    }
}

@Composable
private fun PreferenceUnresolved(id: String, enabled: Boolean, onRemove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Saved selection — name unavailable", style = MaterialTheme.typography.bodyMedium)
        Text(id, style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = enabled, onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove selection $id") }
    }
}

@Composable
private fun PreferenceOriginalFields(title: String, fields: OnboardingPreferencesFields, results: List<OnboardingIngredient>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        fun ingredientLabel(id: String) = results.firstOrNull { it.id.equals(id, true) }?.let { "${it.name} ($id)" } ?: id
        @Composable fun values(label: String, list: List<String>?, labelFor: (String) -> String = { it }) {
            list?.let { Text("$label: ${if (it.isEmpty()) "explicitly none" else it.joinToString(", ", transform = labelFor)}") }
        }
        values("Exclude ingredients", fields.hardExcludedIngredientIds, ::ingredientLabel)
        values("Eating preferences", fields.dietaryPatterns)
        values("Taste dislikes", fields.dislikedIngredientIds, ::ingredientLabel)
        values("Equipment", fields.equipmentIds)
        values("Taste tags", fields.preferredTasteTags)
        fields.defaultServings?.let { Text("Servings: $it") }
        fields.defaultEnergy?.let { Text("Cooking energy: $it") }
        fields.consentVersion?.let { Text("Retained consent version: $it") }
    }
}

@Composable
private fun PreferenceCard(embedded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    if (embedded) {
        // The original form card already supplies its surface and inset. Keep the actual canonical
        // controls without nesting another padded card or changing their callbacks and values.
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    } else Surface(color = FeedMeColors.Surface, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun PreferenceNotice(title: String, body: String, error: Boolean = false) {
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else FeedMeColors.SoftBlue,
        shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(body)
        }
    }
}

@Composable
private fun PreferencePrimary(label: String, enabled: Boolean, action: () -> Unit) {
    Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(label) }
}

@Composable
private fun PreferenceSecondary(label: String, enabled: Boolean, action: () -> Unit) {
    OutlinedButton(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
}
