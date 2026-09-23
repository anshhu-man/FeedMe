package com.feedme.app.mealflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feedme.app.FeedMeTheme
import com.feedme.app.blueprint.BlueprintPreferencePage
import com.feedme.app.blueprint.BlueprintPreferenceScreen
import com.feedme.app.blueprint.BlueprintPreferenceState
import com.feedme.app.onboarding.OnboardingPreferenceChoice
import com.feedme.app.onboarding.OnboardingPreferenceChoices
import com.feedme.app.onboarding.PreferenceChoiceAvailability
import com.feedme.app.onboarding.preferencesChoiceRows
import com.feedme.contracts.WireDocument
import com.feedme.core.ports.PortResult
import com.feedme.mealflow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/** Saved-preference mode over the existing kitchen owner. Mounting neither reads nor sends. Every choice
 * edits its acknowledged encrypted draft; a reviewed save retains the existing exact command.
 * This borrowed view never closes a controller or discards an original when returning to its host. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedMeKitchenPreferencesFlow(
    experience: MealFlowExperience,
    page: BlueprintPreferencePage,
    choices: OnboardingPreferenceChoices?,
    hostIsCurrent: () -> Boolean,
    onClose: () -> Unit,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
    returnLabel: String = "Settings",
) {
    require(page == BlueprintPreferencePage.FOOD_PREFS || page == BlueprintPreferencePage.EQUIPMENT)
    val kitchen = experience.kitchen
    val ingredients = experience.ingredients
    val observed by kitchen.states.collectAsState()
    val observedPicker by ingredients.states.collectAsState()
    val state = observed
    val picker = observedPicker
    val scope = rememberCoroutineScope()
    val currentHost by rememberUpdatedState(hostIsCurrent)
    val closeHost by rememberUpdatedState(onClose)
    var attached by remember(experience, page) { mutableStateOf(true) }
    var busy by remember(experience, page) { mutableStateOf(false) }
    var message by remember(experience, page) { mutableStateOf<String?>(null) }
    var servingsInput by remember(experience, page) { mutableStateOf<String?>(null) }
    var query by remember(experience, page) { mutableStateOf("") }
    var ingredientField by remember(experience, page) { mutableStateOf<String?>(null) }
    var more by remember(experience, page) { mutableStateOf(false) }
    var leaving by remember(experience, page) { mutableStateOf(false) }
    var review by remember(experience, page, state) { mutableStateOf<KitchenInputState?>(null) }
    var discardDraft by remember(experience, page, state) { mutableStateOf<KitchenInputState?>(null) }
    var discardOriginal by remember(experience, page, state) { mutableStateOf<KitchenInputPending?>(null) }
    DisposableEffect(experience, page) { onDispose { attached = false } }

    fun visible() = attached && currentHost()
    fun current(expected: KitchenInputState) = visible() && kitchen.states.value === expected &&
        expected.phase != KitchenInputPhase.UNAVAILABLE
    fun act(expected: KitchenInputState, action: suspend () -> Unit) {
        if (busy || !current(expected) || expected.phase == KitchenInputPhase.LOADING) return
        busy = true
        message = null
        scope.launch {
            try { if (current(expected)) action() }
            finally { busy = false }
        }
    }
    fun notice(result: PortResult<*>) {
        if (visible() && result is PortResult.Failure) message = kitchenFailureMessage(result.reason)
    }
    fun edit(patch: WireDocument) = act(state) { notice(kitchen.editPreferences(patch)) }
    fun back() {
        if (!attached) return
        // The native close callback checks route identity independently of private authority.
        // Losing the session hides all values, but must never trap the unavailable screen.
        if (!currentHost()) { closeHost(); return }
        when {
            review != null -> review = null
            discardDraft != null -> discardDraft = null
            discardOriginal != null -> discardOriginal = null
            ingredientField != null -> ingredientField = null
            more -> more = false
            servingsInput != null || busy -> leaving = true
            else -> closeHost()
        }
    }
    fun keepServings(expected: KitchenInputState, leaveAfter: Boolean) {
        val value = settingsServings(servingsInput)
        if (value == null) {
            message = "Enter a serving count of at least 0.1. Leaving the field blank does not clear your saved default."
            return
        }
        val patch = settingsPreferencePatch(expected.preferenceDraft, "defaultServings", value)
        act(expected) {
            val result = kitchen.editPreferences(patch)
            notice(result)
            if (visible() && result is PortResult.Value && kitchen.states.value === result.value && result.value.draftAcknowledged) {
                servingsInput = null
                leaving = false
                if (leaveAfter) closeHost()
            }
        }
    }
    platformBackHandler(attached, ::back)

    val available = visible() && state.phase != KitchenInputPhase.UNAVAILABLE
    DisposableEffect(available) {
        if (!available) {
            servingsInput = null; query = ""; ingredientField = null; more = false
            review = null; discardDraft = null; discardOriginal = null; leaving = false; message = null
        }
        onDispose { }
    }
    val pending = if (available) state.pending.filter { it.operationId == "updatePreferences" } else emptyList()
    val editable = available && !busy && state.phase != KitchenInputPhase.LOADING &&
        state.preferences != null && state.draftAcknowledged && pending.isEmpty()
    val pageReview = if (available) kitchenPreferencePageReview(page, state.preferenceDraft) else null
    val canReview = editable && pageReview != null && !state.preferencesHistorical &&
        state.failureReason == null && servingsInput == null
    val status = buildList {
        add("Saved preferences · choices are kept in your local kitchen draft. The main button reviews this page’s changes; Back returns to $returnLabel without sending.")
        if (!available) add("Private preferences are unavailable. Use Back to return to $returnLabel.")
        else {
            if (state.preferences == null) add("Load your current preferences using More. Missing values are not empty choices.")
            if (state.preferencesHistorical) add("Saved observations are historical. Load current preferences before reviewing a save.")
            if (state.preferenceDraft != null) add(if (state.draftAcknowledged) "A local draft is retained; it is not a server-applied change."
                else "Local draft acknowledgement is missing. Preserve the draft and check its status before saving.")
            if (state.preferenceDraft != null && pageReview == null)
                add("The retained changes belong to other preference fields. There are no changes on this page to save.")
            else if (pageReview?.retainedDraft != null)
                add("Other preference changes will stay in your local draft when you save this page. They remain unfinished and block new meal planning until saved or explicitly discarded.")
            if (pending.isNotEmpty()) add("An original save is retained. More shows its exact change and explicit recovery; no replacement is sent.")
            kitchenIssueMessage(state.issue)?.let(::add)
            kitchenFailureMessage(state.failureReason)?.let(::add)
            message?.let(::add)
        }
    }.joinToString("\n\n")
    BlueprintPreferenceScreen(
        state = BlueprintPreferenceState(page, enabledActionIds = buildSet {
            if (attached) { add("${page.name}.back"); add("${page.name}.02") }
            if (canReview) add("${page.name}.01")
        }, busy = busy || state.phase == KitchenInputPhase.LOADING, notice = status),
        onFieldChange = { _, _ -> },
        onAction = { action -> when (action) {
            "${page.name}.back", "${page.name}.02" -> back()
            "${page.name}.01" -> if (canReview && current(state)) review = state
        } },
        onMore = if (available) ({ if (visible()) more = true }) else null,
        formContent = {
            if (!available) Text("Your private choices are hidden.")
            else if (state.preferences == null) Text("Current preferences have not been loaded.")
            else if (page == BlueprintPreferencePage.FOOD_PREFS) {
                SettingsChoiceRows("Eating preferences", "dietaryPatterns", state, choices?.dietaryPatterns, editable) { field, id ->
                    edit(settingsTogglePreference(state, field, id))
                }
                HorizontalDivider()
                SettingsIngredientSelections("Ingredient exclusions", "hardExcludedIngredientIds", state, picker, editable,
                    remove = { id -> edit(settingsTogglePreference(state, "hardExcludedIngredientIds", id)) },
                    search = { if (editable && current(state)) ingredientField = "hardExcludedIngredientIds" })
                SettingsIngredientSelections("Dislikes", "dislikedIngredientIds", state, picker, editable,
                    remove = { id -> edit(settingsTogglePreference(state, "dislikedIngredientIds", id)) },
                    search = { if (editable && current(state)) ingredientField = "dislikedIngredientIds" })
            } else {
                SettingsChoiceRows("Equipment", "equipmentIds", state, choices?.equipment, editable) { field, id ->
                    edit(settingsTogglePreference(state, field, id))
                }
                val savedServings = settingsPreferenceValue(state, "defaultServings")?.jsonPrimitive?.content.orEmpty()
                OutlinedTextField(value = servingsInput ?: savedServings,
                    onValueChange = { if (editable && current(state) && it.length <= 24) servingsInput = it },
                    label = { Text("Usual serving count (optional)") }, enabled = editable, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                if (servingsInput != null) {
                    Text("This typed value has not been kept locally yet.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { keepServings(state, false) }, enabled = editable) { Text("Keep serving count in draft") }
                    TextButton(onClick = { if (current(state)) servingsInput = null }, enabled = !busy) { Text("Undo typed serving count") }
                }
            }
        },
    )

    // All dialogs are projected only while this exact host still owns the private observation.
    FeedMeTheme {
        if (available && ingredientField != null) {
            val field = ingredientField!!
            ModalBottomSheet(onDismissRequest = { if (visible()) ingredientField = null }) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (field == "hardExcludedIngredientIds") "Choose an ingredient to exclude" else "Choose an ingredient you dislike", style = MaterialTheme.typography.titleLarge)
                    Text("Choose a real catalog result. Typing a name does not select an ingredient.")
                    OutlinedTextField(query, { if (!busy && it.length <= 100) query = it }, singleLine = true,
                        label = { Text("Search ingredients") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                    Button(onClick = {
                        val searched = query
                        act(state) { notice(ingredients.search(searched)) }
                    }, enabled = editable && query.isNotBlank()) { Text("Search") }
                    if (picker.searchQuery != query) Text("Shown results are from the last explicit search.", style = MaterialTheme.typography.bodySmall)
                    when (picker.searchPhase) {
                        IngredientPickerPhase.LOADING -> Text("Searching…")
                        IngredientPickerPhase.EMPTY -> Text("No matching catalog ingredients were returned.")
                        IngredientPickerPhase.OFFLINE -> Text("Offline. Cached labels do not establish current catalog choices.")
                        IngredientPickerPhase.ERROR -> Text("The search did not complete. You can search again explicitly.")
                        IngredientPickerPhase.UNAVAILABLE -> Text("Ingredient search is unavailable.")
                        else -> Unit
                    }
                    picker.searchResults.forEach { row ->
                        val selected = kitchenPreferenceSelection(state.preferences, state.preferenceDraft, field).any { it.equals(row.id, true) }
                        OutlinedButton(onClick = {
                            if (ingredients.states.value === picker && picker.searchResults.any { it === row } && !row.historical && !selected)
                                edit(settingsTogglePreference(state, field, row.id))
                        }, enabled = editable && !row.historical && !selected && picker.searchPhase == IngredientPickerPhase.READY,
                            modifier = Modifier.fillMaxWidth()) { Text(row.name + if (selected) " · selected" else if (row.historical) " · historical" else "") }
                    }
                    if (picker.searchHasMore) TextButton(onClick = {
                        if (ingredients.states.value === picker) act(state) { notice(ingredients.nextSearchPage()) }
                    }, enabled = editable && picker.searchPhase == IngredientPickerPhase.READY) { Text("More catalog results") }
                    TextButton(onClick = { if (visible()) ingredientField = null }) { Text("Done") }
                }
            }
        }
        if (available && more && ingredientField == null) {
            ModalBottomSheet(onDismissRequest = { if (visible()) more = false }) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Kitchen settings", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { act(state) { notice(kitchen.loadPreferences()) } }, enabled = !busy && state.phase != KitchenInputPhase.LOADING) { Text("Load current preferences") }
                    TextButton(onClick = { act(state) { notice(kitchen.restore()) } }, enabled = !busy && state.phase != KitchenInputPhase.LOADING) { Text("Check retained draft and command status") }
                    val ingredientIds = listOf("hardExcludedIngredientIds", "dislikedIngredientIds")
                        .flatMap { kitchenPreferenceSelection(state.preferences, state.preferenceDraft, it) }.distinct()
                    if (ingredientIds.isNotEmpty()) TextButton(onClick = {
                        act(state) { notice(ingredients.resolveCurrentLabels(ingredientIds)) }
                    }, enabled = !busy) { Text("Look up selected ingredient labels") }
                    state.preferenceDraft?.let { draft ->
                        SettingsPreferenceSummary("Retained local changes", draft, picker, choices)
                        TextButton(onClick = { if (!busy && current(state)) discardDraft = state }, enabled = !busy) { Text("Discard this local draft…") }
                    }
                    pending.forEach { original ->
                        HorizontalDivider()
                        Text("Original preference save", style = MaterialTheme.typography.titleMedium)
                        Text(kitchenCommandMessage(original.phase, original.issue))
                        SettingsPreferenceSummary("Original base", original.base, picker, choices)
                        SettingsPreferenceSummary("Exact retained change", original.body, picker, choices)
                        if (original.phase == KitchenInputCommandPhase.NEEDS_RESOLUTION)
                            SettingsPreferenceSummary("Current observed candidate · not a rebase", state.preferences, picker, choices)
                        TextButton(onClick = {
                            act(state) { notice(kitchen.synchronize(original.commandId)) }
                        }, enabled = !busy && original.canSynchronize) { Text(if (original.phase == KitchenInputCommandPhase.RECEIPT_READY || original.phase == KitchenInputCommandPhase.APPLIED)
                            "Recover original receipt" else "Synchronize original save") }
                        if (original.canDiscardUnsent) TextButton(onClick = {
                            if (!busy && current(state)) discardOriginal = original
                        }, enabled = !busy) { Text("Return never-sent original to local draft…") }
                    }
                    if (pending.isEmpty() && state.preferenceDraft == null) Text("No local preference change is retained.")
                    TextButton(onClick = { if (visible()) more = false }) { Text("Done") }
                }
            }
        }
        val reviewed = review
        val reviewedPage = reviewed?.let { kitchenPreferencePageReview(page, it.preferenceDraft) }
        if (available && reviewed != null && reviewedPage != null && state === reviewed) AlertDialog(
            onDismissRequest = { if (!busy) review = null }, title = { Text("Save these kitchen changes?") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This sends one version-checked save of this page’s reviewed changes only. Other retained fields stay in your local draft. An uncertain result keeps the same original.")
                SettingsPreferenceSummary("Current observed preferences", reviewed.preferences, picker, choices)
                SettingsPreferenceSummary("Exact changes to save", reviewedPage.body, picker, choices)
                reviewedPage.retainedDraft?.let {
                    SettingsPreferenceSummary("Kept locally · not included in this save", it, picker, choices)
                }
            } },
            confirmButton = { TextButton(onClick = {
                if (canReview && current(reviewed)) {
                    review = null
                    act(reviewed) {
                        val retained = kitchen.savePreferences(reviewedPage.page, reviewed)
                        notice(retained)
                        if (retained is PortResult.Value && current(retained.value)) {
                            val original = retained.value.pending.singleOrNull { it.operationId == "updatePreferences" }
                            if (original != null && original.canSynchronize &&
                                settingsSamePreferenceDocument(original.body, reviewedPage.body) &&
                                settingsSamePreferenceDocument(original.base, reviewed.preferences) &&
                                settingsSamePreferenceDocument(retained.value.preferenceDraft, reviewedPage.retainedDraft))
                                notice(kitchen.synchronize(original.commandId))
                            else if (original != null) message = "The original is retained but has not been sent. Review its exact base and change in More before synchronizing."
                        }
                    }
                }
            }, enabled = canReview) { Text("Save reviewed changes") } },
            dismissButton = { TextButton(onClick = { review = null }, enabled = !busy) { Text("Keep editing") } },
        )
        val discarded = discardDraft
        if (available && discarded != null && state === discarded) AlertDialog(
            onDismissRequest = { if (!busy) discardDraft = null }, title = { Text("Discard this local draft?") },
            text = { Text("This removes only the editable preference draft. Saved server values and any original pending command remain unchanged.") },
            confirmButton = { TextButton(onClick = {
                discardDraft = null
                act(discarded) { notice(kitchen.discardPreferenceDraft()) }
            }, enabled = !busy) { Text("Discard local draft") } },
            dismissButton = { TextButton(onClick = { discardDraft = null }) { Text("Keep draft") } },
        )
        val unsent = discardOriginal
        if (available && unsent != null && pending.any { it === unsent }) AlertDialog(
            onDismissRequest = { if (!busy) discardOriginal = null }, title = { Text("Stop this never-sent original?") },
            text = { Text("Only an original with no attempt can be stopped. Its fields return to the local draft without replacing newer draft choices; other retained fields stay unchanged. This does not undo a server change.") },
            confirmButton = { TextButton(onClick = {
                discardOriginal = null
                act(state) { notice(kitchen.discardUnsent(unsent.commandId)) }
            }, enabled = !busy && unsent.canDiscardUnsent) { Text("Return to draft") } },
            dismissButton = { TextButton(onClick = { discardOriginal = null }) { Text("Keep original") } },
        )
        if (visible() && leaving) AlertDialog(
            onDismissRequest = { leaving = false }, title = { Text("Return to $returnLabel?") },
            text = { Text(if (busy) "An action is still in progress. Leaving may interrupt it; its retained draft or original must be checked later. No server outcome is inferred."
                else "The typed serving count has not been kept. Existing encrypted drafts and original saves stay retained whichever option you choose.") },
            confirmButton = { TextButton(onClick = { if (visible()) { servingsInput = null; closeHost() } }) { Text(if (busy) "Leave and retain evidence" else "Discard typed count and leave") } },
            dismissButton = { Column {
                if (available && !busy && servingsInput != null) TextButton(onClick = { keepServings(state, true) }) { Text("Keep count locally and leave") }
                TextButton(onClick = { leaving = false }) { Text("Stay here") }
            } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsChoiceRows(title: String, field: String, state: KitchenInputState,
    choices: List<OnboardingPreferenceChoice>?, enabled: Boolean, toggle: (String, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        val selected = kitchenPreferenceSelection(state.preferences, state.preferenceDraft, field)
        if (choices == null) Text("Choice labels are not configured. Retained choices remain visible and removable.", style = MaterialTheme.typography.bodySmall)
        if (selected.isEmpty()) Text("No choices selected in the observed values or draft.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            preferencesChoiceRows(selected, choices).forEach { row ->
                FilterChip(selected = row.selected, onClick = { toggle(field, row.id) },
                    enabled = enabled && (row.selected || row.canAdd), label = { Text(row.label + when (row.availability) {
                        PreferenceChoiceAvailability.AVAILABLE -> ""
                        PreferenceChoiceAvailability.RETIRED -> " · no longer offered"
                        PreferenceChoiceAvailability.UNRESOLVED -> " · label unavailable"
                    }) })
            }
        }
    }
}

@Composable
private fun SettingsIngredientSelections(title: String, field: String, state: KitchenInputState,
    picker: IngredientPickerState, enabled: Boolean, remove: (String) -> Unit, search: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        val selected = kitchenPreferenceSelection(state.preferences, state.preferenceDraft, field)
        if (selected.isEmpty()) Text("None selected in the observed values or draft.", style = MaterialTheme.typography.bodySmall)
        selected.forEach { id -> TextButton(onClick = { remove(id) }, enabled = enabled) {
            Text("${settingsIngredientLabel(id, picker)} · remove")
        } }
        OutlinedButton(onClick = search, enabled = enabled) { Text("Find an ingredient") }
    }
}

@Composable
private fun SettingsPreferenceSummary(title: String, document: WireDocument?, picker: IngredientPickerState,
    choices: OnboardingPreferenceChoices?) {
    val values = settingsObject(document)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (document == null) Text("Not observed.", style = MaterialTheme.typography.bodySmall)
        else values.filterKeys { it in settingsPreferenceFields }.forEach { (field, value) ->
            val rendered = if (value is JsonArray) value.joinToString { item ->
                val id = item.jsonPrimitive.content
                when (field) {
                    "hardExcludedIngredientIds", "dislikedIngredientIds" -> settingsIngredientLabel(id, picker)
                    "dietaryPatterns" -> choices?.dietaryPatterns?.firstOrNull { it.id == id }?.label ?: "Unlisted choice ($id)"
                    "equipmentIds" -> choices?.equipment?.firstOrNull { it.id == id }?.label ?: "Unlisted choice ($id)"
                    else -> id
                }
            }.ifEmpty { "None (explicit empty list)" } else value.jsonPrimitive.content
            Text("${settingsPreferenceFields.getValue(field)}: $rendered", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private val settingsPreferenceFields = mapOf("hardExcludedIngredientIds" to "Exclusions",
    "dietaryPatterns" to "Eating preferences", "dislikedIngredientIds" to "Dislikes", "equipmentIds" to "Equipment",
    "preferredTasteTags" to "Preferred tastes", "defaultEnergy" to "Usual energy", "defaultServings" to "Usual servings", "consentVersion" to "Consent version")
private fun settingsObject(document: WireDocument?): JsonObject = document?.let {
    Json.parseToJsonElement(it.encodeUtf8().decodeToString()).jsonObject
} ?: JsonObject(emptyMap())
private fun settingsPreferenceValue(state: KitchenInputState, field: String): JsonElement? =
    settingsObject(state.preferenceDraft)[field] ?: settingsObject(state.preferences)[field]
private fun settingsPreferencePatch(draft: WireDocument?, field: String, value: JsonElement): WireDocument =
    WireDocument.parse(JsonObject(settingsObject(draft) + (field to value)).toString())
private fun settingsSamePreferenceDocument(first: WireDocument?, second: WireDocument?): Boolean =
    if (first == null || second == null) first == null && second == null
    else settingsObject(first) == settingsObject(second)
private fun settingsTogglePreference(state: KitchenInputState, field: String, id: String): WireDocument {
    require(field in setOf("dietaryPatterns", "equipmentIds", "hardExcludedIngredientIds", "dislikedIngredientIds"))
    val selected = kitchenPreferenceSelection(state.preferences, state.preferenceDraft, field)
    fun same(value: String) = value.equals(id, ignoreCase = field.endsWith("IngredientIds"))
    val changed = if (selected.any(::same)) selected.filterNot(::same) else selected + id
    return settingsPreferencePatch(state.preferenceDraft, field, JsonArray(changed.map(::JsonPrimitive)))
}
private fun settingsIngredientLabel(id: String, picker: IngredientPickerState): String =
    picker.knownIngredients.firstOrNull { it.id.equals(id, true) }?.let { it.name + if (it.historical) " (historical label)" else "" }
        ?: "Ingredient label unavailable ($id)"
private fun settingsServings(text: String?): JsonPrimitive? {
    val value = text?.trim() ?: return null
    if (value.length > 24 || !Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?").matches(value)) return null
    val number = value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.1 } ?: return null
    if (number <= 0) return null
    return Json.parseToJsonElement(value).jsonPrimitive
}
