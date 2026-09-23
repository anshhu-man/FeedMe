package com.feedme.app.mealflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.feedme.app.FeedMeColors
import com.feedme.app.FeedMeDetails
import com.feedme.app.FeedMeWordmark
import com.feedme.app.blueprint.BlueprintIngredientAvailability
import com.feedme.contracts.WireDocument
import com.feedme.core.ports.FailureReason
import com.feedme.mealflow.*
import kotlinx.serialization.json.*

enum class KitchenInputPage { PANTRY, PREFERENCES }

/** Presentation callbacks only. The retained controller owns validation, revision and command authority. */
class KitchenInputScreenActions(
    val back: () -> Unit,
    val searchText: (String) -> Unit,
    val search: () -> Unit,
    val moreIngredients: () -> Unit,
    val loadPreferences: () -> Unit,
    val loadPantry: () -> Unit,
    val morePantry: () -> Unit,
    val editPreferences: (WireDocument) -> Unit,
    val discardPreferenceDraft: () -> Unit,
    val savePreferences: () -> Unit,
    val editPantry: (WireDocument) -> Unit,
    val discardPantryDraft: (String) -> Unit,
    val savePantry: (String) -> Unit,
    val removePantry: (String) -> Unit,
    val synchronize: (String) -> Unit,
    val discardUnsent: (String) -> Unit,
    val blueprintIsCurrent: () -> Boolean = { false },
    val blueprintOwnerKey: Any? = null,
    val blueprintSearch: (() -> Unit)? = null,
    val blueprintMoreIngredients: (() -> Unit)? = null,
    val blueprintAdd: ((IngredientOption, BlueprintIngredientAvailability) -> Unit)? = null,
    val blueprintRemove: ((WireDocument) -> Unit)? = null,
    val blueprintMealValues: MealFormValues? = null,
    val blueprintUseIngredients: ((List<String>) -> Unit)? = null,
    val blueprintSetEffort: (() -> Unit)? = null,
    val blueprintRefinementOpen: Boolean = false,
)

/** No effects on attachment: loading, editing, sending and deletion are all explicit actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KitchenInputScreen(screen: KitchenInputPage, state: KitchenInputState,
    picker: MealPickerPresentation, choices: MealInputChoices, busy: Boolean,
    searchText: String, actions: KitchenInputScreenActions, blueprintPicker: IngredientPickerState? = null) {
    val rows = if (screen == KitchenInputPage.PANTRY && blueprintPicker != null && actions.blueprintIsCurrent())
        blueprintPantryRows(state, blueprintPicker) else null
    if (rows != null && blueprintPicker != null) {
        BlueprintAccountPantryScreen(state, blueprintPicker, busy, searchText, rows, actions) { guardedActions ->
            RetainedKitchenInputScreen(screen, state, picker, choices, busy, searchText, guardedActions)
        }
    } else RetainedKitchenInputScreen(screen, state, picker, choices, busy, searchText, actions)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RetainedKitchenInputScreen(screen: KitchenInputPage, state: KitchenInputState,
    picker: MealPickerPresentation, choices: MealInputChoices, busy: Boolean,
    searchText: String, actions: KitchenInputScreenActions) {
    val unavailable = state.phase == KitchenInputPhase.UNAVAILABLE
    // Any newer controller observation discards this UI-only proposal. It is not a deletion token.
    var removal by remember(screen, state) { mutableStateOf<String?>(null) }
    var removalInteraction by remember(screen, state) { mutableStateOf(Any()) }
    val phase = kitchenPhaseMessage(state.phase, state.issue)
    val pending = state.pending.filter {
        (screen == KitchenInputPage.PREFERENCES) == (it.operationId == "updatePreferences")
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().widthIn(max = 720.dp)
        .verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = actions.back, modifier = Modifier.heightIn(min = 48.dp)) { Text("← Back") }
            FeedMeWordmark(compact = true)
        }
        Text(if (screen == KitchenInputPage.PANTRY) "A rough pantry.\nA simpler dinner." else "Food that fits you.",
            style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        if (kitchenUsesCompactStatus(state.phase, state.issue, state.failureReason))
            KitchenInlineNote(phase.first, phase.second)
        else KitchenNote(phase.first, phase.second)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        kitchenFailureMessage(state.failureReason)?.let { KitchenNote("Not completed", it) }
        if (unavailable) {
            Text("Return to your account or recovery screen. This editor cannot restore access.")
        } else {
            if (state.historical) Text("Saved local observations · not a fresh server check", style = MaterialTheme.typography.bodySmall)
            kitchenIssueMessage(state.issue)?.let { Text(it) }
            if (!state.draftAcknowledged) KitchenNote("Draft not acknowledged",
                "A local write has not been acknowledged. Nothing here proves that this draft is safely stored or sent.")
            if (screen == KitchenInputPage.PREFERENCES) {
                KitchenInlineNote("Exclusions are not dislikes",
                    "Hard exclusions block matching ingredients. Dislikes change ranking only. Neither guarantees allergy safety.")
                if (state.preferenceDraft != null) PreferenceSummary("Local preference draft · not server applied",
                    state.preferenceDraft, picker, choices)
                PreferenceSummary("Current saved preferences", state.preferences, picker, choices)
                OutlinedButton(onClick = actions.loadPreferences, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Load current preferences") }
                if (state.preferencesPending) KitchenNote("Preferences still pending",
                    "Planning must not fall back to older, more permissive preferences while this change is unresolved.")
                if (state.stricterExclusionIds.isNotEmpty()) Text("Retained stricter exclusions: " +
                    state.stricterExclusionIds.joinToString { kitchenIngredientLabel(it, picker.knownIngredients) })
                if (state.preferences == null) Text("Load your current preference record before editing. Missing values are not empty preferences.")
            } else {
                KitchenInlineNote("Rough availability only",
                    "Reports aren’t meal selections. Check the ingredient, freshness and safety yourself before using it.")
                OutlinedButton(onClick = actions.loadPantry, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Load current pantry") }
                if (state.pantryItems.isEmpty()) KitchenInlineNote("Start small", "No pantry items in the loaded pages. Use the search below or load your current pantry; this doesn’t mean your whole pantry is empty.")
                Text("Your pantry reports", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                val currentById = state.pantryItems.associateBy(::kitchenIngredientId)
                val draftsById = state.pantryDrafts.associateBy(::kitchenIngredientId)
                (currentById.keys + draftsById.keys).forEach { id ->
                    val current = currentById[id]
                    val draft = draftsById[id]
                    val hasPending = state.pending.any { it.ingredientId?.equals(id, ignoreCase = true) == true }
                    KitchenCard {
                        Text(kitchenIngredientLabel(id, picker.knownIngredients), style = MaterialTheme.typography.titleMedium)
                        if (draft != null) PantrySummary("Local draft · not server applied", draft)
                        PantrySummary("Current saved report", current)
                        PantryChoices(id, current, draft, !busy && !hasPending, actions.editPantry)
                        if (draft != null) {
                            Button(onClick = { actions.savePantry(id) }, enabled = !busy && !hasPending && state.draftAcknowledged,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text("Save pantry change")
                            }
                            TextButton(onClick = { actions.discardPantryDraft(id) }, enabled = !busy && !hasPending, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("Discard local pantry draft")
                            }
                        }
                        if (current != null) TextButton(onClick = { removal = id; removalInteraction = Any() }, enabled = !busy && !hasPending && draft == null,
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Remove pantry item…")
                        }
                    }
                }
                if (state.pantryHasMore) OutlinedButton(onClick = actions.morePantry, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Load more pantry") }
                KitchenIngredientSearch(screen, state, picker, busy, searchText, actions)
            }

            if (screen == KitchenInputPage.PREFERENCES && state.preferences != null) {
                val enabled = !busy && pending.isEmpty()
                listOf("hardExcludedIngredientIds" to "Review hard exclusions", "dislikedIngredientIds" to "Review dislikes").forEach { (field, title) ->
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    kitchenPreferenceSelection(state.preferences, state.preferenceDraft, field).forEach { id ->
                        TextButton(onClick = { actions.editPreferences(kitchenTogglePreference(state.preferences, state.preferenceDraft, field, id)) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Remove: ${kitchenIngredientLabel(id, picker.knownIngredients)}")
                        }
                    }
                }
                Text("Equipment", style = MaterialTheme.typography.titleLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    choices.equipment.forEach { choice -> PreferenceToggle(choice.label, "equipmentIds", choice.id,
                        state.preferences, state.preferenceDraft, enabled, actions.editPreferences) }
                }
                kitchenPreferenceSelection(state.preferences, state.preferenceDraft, "equipmentIds")
                    .filter { id -> choices.equipment.none { it.id == id } }.forEach { id ->
                        TextButton(onClick = { actions.editPreferences(kitchenTogglePreference(state.preferences, state.preferenceDraft, "equipmentIds", id)) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Remove unavailable equipment label ($id)")
                        }
                    }
                val selectedTastes = kitchenPreferenceSelection(state.preferences, state.preferenceDraft, "preferredTasteTags")
                Text("Preferred tastes: " + selectedTastes.joinToString { id -> choices.tastes.firstOrNull { it.id == id }?.label
                    ?: "Taste label unavailable ($id)" }.ifEmpty { "none recorded" }, color = FeedMeColors.Muted)
                FeedMeDetails("Preferred tastes · optional") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        choices.tastes.forEach { choice -> PreferenceToggle(choice.label, "preferredTasteTags", choice.id,
                            state.preferences, state.preferenceDraft, enabled, actions.editPreferences) }
                    }
                }
                kitchenPreferenceSelection(state.preferences, state.preferenceDraft, "preferredTasteTags")
                    .filter { id -> choices.tastes.none { it.id == id } }.forEach { id ->
                        TextButton(onClick = { actions.editPreferences(kitchenTogglePreference(state.preferences, state.preferenceDraft, "preferredTasteTags", id)) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Remove unavailable taste label ($id)")
                        }
                    }
                Text("Eating preferences", style = MaterialTheme.typography.titleLarge)
                Text("Existing dietary IDs: " + kitchenPreferenceSelection(state.preferences, state.preferenceDraft, "dietaryPatterns")
                    .takeIf { it.isNotEmpty() }?.joinToString().orEmpty().ifEmpty { "none recorded" })
                Text("New dietary presets aren’t connected here. Existing choices stay unchanged.", style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
                if (state.preferenceDraft != null) {
                    Button(onClick = actions.savePreferences, enabled = enabled && state.draftAcknowledged,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save preference change") }
                    TextButton(onClick = actions.discardPreferenceDraft, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Discard local preference draft") }
                }
            }

            if (screen == KitchenInputPage.PREFERENCES) KitchenIngredientSearch(screen, state, picker, busy, searchText, actions)

            pending.forEach { command ->
                KitchenCard {
                    Text("Retained change · ${kitchenCommandTitle(command.operationId)}", style = MaterialTheme.typography.titleMedium)
                    Text(kitchenCommandMessage(command.phase, command.issue))
                    if (command.operationId == "updatePreferences") {
                        PreferenceSummary("Original base", command.base, picker, choices)
                        PreferenceSummary("Original immutable patch", command.body, picker, choices)
                        PreferenceSummary("Current saved candidate · not an automatic rebase", state.preferences, picker, choices)
                    } else {
                        command.ingredientId?.let { Text(kitchenIngredientLabel(it, picker.knownIngredients)) }
                        PantrySummary("Original base", command.base)
                        if (command.body != null) PantrySummary("Original immutable change", command.body)
                        else Text("Original intention: remove this pantry item")
                        PantrySummary("Current saved candidate · not an automatic rebase",
                            state.pantryItems.firstOrNull { kitchenIngredientId(it).equals(command.ingredientId, ignoreCase = true) })
                    }
                    Text("Attempts: ${command.attempts}. A visible value is not a successful write acknowledgement.")
                    if (command.phase == KitchenInputCommandPhase.RETRY_WAIT)
                        Text("The retained retry deadline is enforced by the controller; this screen cannot override it.")
                    Button(onClick = { actions.synchronize(command.commandId) }, enabled = !busy && command.canSynchronize,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Synchronize original change")
                    }
                    TextButton(onClick = { actions.discardUnsent(command.commandId) }, enabled = !busy && command.canDiscardUnsent, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Discard never-sent change")
                    }
                    if (!command.canDiscardUnsent) Text("Attempted evidence cannot be discarded here. No replacement command is inferred.")
                }
            }
        }
    }
    val removing = removal
    val renderedRemoval = removalInteraction
    fun closeRemoval() { if (removal == removing && removalInteraction === renderedRemoval) { removal = null; removalInteraction = Any() } }
    if (!unavailable && removing != null) AlertDialog(onDismissRequest = ::closeRemoval,
        title = { Text("Remove this pantry item?") },
        text = { Text("Remove ${kitchenIngredientLabel(removing, picker.knownIngredients)} from your account pantry. This does not change a meal’s confirmed ingredient snapshot. A queued removal is not proof of server deletion.") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            if (!busy && removal == removing && removalInteraction === renderedRemoval) {
                removal = null; removalInteraction = Any(); actions.removePantry(removing)
            }
        }) { Text("Confirm pantry removal") } },
        dismissButton = { TextButton(onClick = ::closeRemoval) { Text("Keep pantry item") } })
}

@Composable private fun KitchenCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = FeedMeColors.Surface, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable private fun KitchenNote(title: String, body: String) = KitchenCard {
    Text(title, style = MaterialTheme.typography.titleMedium); Text(body, color = FeedMeColors.Muted)
}
/** Visual hierarchy only: this does not infer a successful write or change action eligibility. */
internal fun kitchenUsesCompactStatus(phase: KitchenInputPhase, issue: KitchenInputIssue, failure: FailureReason?): Boolean =
    phase in setOf(KitchenInputPhase.READY, KitchenInputPhase.EDITING) && issue == KitchenInputIssue.NONE && failure == null

/** Compact, fully visible context; unresolved action warnings keep their full cards. */
@Composable private fun KitchenInlineNote(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(body, style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
    }
}
@Composable private fun PreferenceToggle(label: String, field: String, id: String,
    current: WireDocument?, draft: WireDocument?, enabled: Boolean, edit: (WireDocument) -> Unit) {
    FilterChip(selected = kitchenPreferenceSelection(current, draft, field).any { kitchenSameSelection(field, it, id) },
        onClick = { edit(kitchenTogglePreference(current, draft, field, id)) }, enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp), label = { Text(label) })
}
@Composable private fun PreferenceSummary(title: String, document: WireDocument?, picker: MealPickerPresentation, choices: MealInputChoices) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (document == null) { Text("No observed value"); return@Column }
        val value = kitchenObject(document)
        value["version"]?.let { Text("Observed revision: $it") }
        listOf("hardExcludedIngredientIds" to "Hard exclusions", "dislikedIngredientIds" to "Dislikes",
            "equipmentIds" to "Equipment", "preferredTasteTags" to "Preferred tastes", "dietaryPatterns" to "Dietary IDs").forEach { (field, label) ->
            if (field in value) {
                val ids = kitchenStrings(value[field])
                val names = ids.map { id -> when (field) {
                    "hardExcludedIngredientIds", "dislikedIngredientIds" -> kitchenIngredientLabel(id, picker.knownIngredients)
                    "equipmentIds" -> choices.equipment.firstOrNull { it.id == id }?.label ?: "Equipment label unavailable ($id)"
                    "preferredTasteTags" -> choices.tastes.firstOrNull { it.id == id }?.label ?: "Taste label unavailable ($id)"
                    else -> id
                } }
                Text("$label: ${names.joinToString().ifEmpty { "none recorded" }}")
            }
        }
        value["defaultEnergy"]?.let { Text("Existing default energy: ${it.jsonPrimitive.content}") }
        value["defaultServings"]?.let { Text("Existing default servings: $it") }
    }
}
@Composable private fun PantrySummary(title: String, document: WireDocument?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (document == null) { Text("No observed value"); return@Column }
        val value = kitchenObject(document)
        value["version"]?.let { Text("Observed revision: $it") }
        Text("Report: ${kitchenPresenceLabel(value["presence"]?.jsonPrimitive?.content.orEmpty())}")
        value["confirmationStatus"]?.let { Text("Recorded confirmation state: ${it.jsonPrimitive.content}") }
        value["confirmedAt"]?.takeIf { it != JsonNull }?.let { Text("Previously recorded confirmation time: ${it.jsonPrimitive.content}") }
        value["staple"]?.let { Text("Recorded staple: $it") }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun PantryChoices(id: String, current: WireDocument?, draft: WireDocument?, enabled: Boolean,
    edit: (WireDocument) -> Unit) {
    Text("What’s the situation?", style = MaterialTheme.typography.titleSmall)
    val selected = kitchenObject(draft ?: current)["presence"]?.jsonPrimitive?.content
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("available", "low", "out", "usuallyHave", "uncertain").forEach { presence ->
            FilterChip(selected = selected == presence, onClick = { edit(kitchenPantryDraft(current, draft, id, presence)) },
                enabled = enabled, modifier = Modifier.heightIn(min = 48.dp), label = { Text(kitchenPresenceLabel(presence)) })
        }
    }
    Text("A report only—not confirmation for this meal.", style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun KitchenIngredientSearch(screen: KitchenInputPage, state: KitchenInputState,
    picker: MealPickerPresentation, busy: Boolean, searchText: String, actions: KitchenInputScreenActions) {
    Text(if (screen == KitchenInputPage.PANTRY) "Add a pantry report" else "Find an ingredient", style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() })
    OutlinedTextField(searchText, actions.searchText, label = { Text("Search ingredient names") },
        singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp))
    OutlinedButton(onClick = actions.search, enabled = !busy && searchText.isNotBlank(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Search ingredients") }
    if (picker.searchPhase == IngredientPickerPhase.LOADING) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (picker.searchPhase == IngredientPickerPhase.EMPTY) Text("No ingredient matches. Try another name; nothing was selected.")
    if (picker.issue != IngredientPickerIssue.NONE) KitchenNote("Ingredient lookup needs attention", "Results are incomplete or unavailable. Retained labels aren’t a fresh catalog check.")
    picker.searchResults.forEach { ingredient ->
        KitchenCard {
            Text(ingredient.name, style = MaterialTheme.typography.titleMedium)
            if (ingredient.historical) Text("Previously fetched catalog label", style = MaterialTheme.typography.bodySmall)
            if (screen == KitchenInputPage.PREFERENCES) {
                val enabled = !busy && state.preferences != null && state.pending.none { it.operationId == "updatePreferences" }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PreferenceToggle("Hard exclude", "hardExcludedIngredientIds", ingredient.id,
                        state.preferences, state.preferenceDraft, enabled, actions.editPreferences)
                    PreferenceToggle("Dislike only", "dislikedIngredientIds", ingredient.id,
                        state.preferences, state.preferenceDraft, enabled, actions.editPreferences)
                }
            } else {
                val current = state.pantryItems.firstOrNull { kitchenIngredientId(it).equals(ingredient.id, ignoreCase = true) }
                val draft = state.pantryDrafts.firstOrNull { kitchenIngredientId(it).equals(ingredient.id, ignoreCase = true) }
                PantryChoices(ingredient.id, current, draft,
                    !busy && state.pending.none { it.ingredientId?.equals(ingredient.id, ignoreCase = true) == true }, actions.editPantry)
            }
        }
    }
    if (picker.searchHasMore) OutlinedButton(onClick = actions.moreIngredients, enabled = !busy,
        modifier = Modifier.heightIn(min = 48.dp)) { Text("More ingredient matches") }
}

internal fun kitchenObject(document: WireDocument?): JsonObject = document?.let {
    Json.parseToJsonElement(it.encodeUtf8().decodeToString()).jsonObject
} ?: JsonObject(emptyMap())
private fun kitchenStrings(value: JsonElement?): List<String> = (value as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
internal fun kitchenIngredientId(document: WireDocument): String = kitchenObject(document).getValue("ingredientId").jsonPrimitive.content.lowercase()
internal fun kitchenIngredientLabel(id: String, known: List<IngredientRow>): String =
    known.firstOrNull { it.id.equals(id, ignoreCase = true) }?.name ?: "Ingredient label unavailable ($id)"
internal fun kitchenPreferenceSelection(current: WireDocument?, draft: WireDocument?, field: String): List<String> {
    val patch = kitchenObject(draft)
    return kitchenStrings(if (field in patch) patch[field] else kitchenObject(current)[field])
}
internal fun kitchenTogglePreference(current: WireDocument?, draft: WireDocument?, field: String, id: String): WireDocument {
    require(field in setOf("hardExcludedIngredientIds", "dislikedIngredientIds", "equipmentIds", "preferredTasteTags"))
    val prior = kitchenPreferenceSelection(current, draft, field)
    val next = if (prior.any { kitchenSameSelection(field, it, id) }) prior.filterNot { kitchenSameSelection(field, it, id) } else prior + id
    // Merge only the patch. Preserve every other patch field; never copy current DTO metadata or
    // consent into a write, nor erase an unedited current field by replacing the whole document.
    return WireDocument.parse(JsonObject(kitchenObject(draft) + (field to JsonArray(next.map(::JsonPrimitive)))).toString())
}
private fun kitchenSameSelection(field: String, first: String, second: String) =
    first.equals(second, ignoreCase = field.endsWith("IngredientIds"))
internal fun kitchenPantryDraft(current: WireDocument?, draft: WireDocument?, id: String, presence: String): WireDocument {
    require(presence in setOf("available", "low", "out", "usuallyHave", "uncertain"))
    val base = kitchenObject(current)
    val patch = kitchenObject(draft)
    listOf(base, patch).filter { it.isNotEmpty() }.forEach { require(it["ingredientId"]?.jsonPrimitive?.content?.equals(id, ignoreCase = true) == true) }
    val retained = (base + patch).filterKeys { it !in setOf("id", "version", "createdAt", "updatedAt", "expectedVersion", "quantity", "unit") }
    val status = when (presence) { "usuallyHave" -> "usual"; "out" -> "unavailable"; else -> "uncertain" }
    return WireDocument.parse(JsonObject(retained + mapOf("ingredientId" to JsonPrimitive(id), "presence" to JsonPrimitive(presence),
        "confirmationStatus" to JsonPrimitive(status), "confirmedAt" to JsonNull)).toString())
}
internal fun kitchenPresenceLabel(value: String): String = when (value) {
    "available" -> "Reported available"; "low" -> "Reported low"; "out" -> "Not available"
    "usuallyHave" -> "Usually have · still uncertain"; "uncertain" -> "Not sure"
    else -> "Availability not recorded"
}
internal fun kitchenCommandTitle(operation: String) = when (operation) {
    "updatePreferences" -> "preferences"; "upsertPantryItem" -> "pantry report"; "removePantryItem" -> "pantry removal"
    else -> "unavailable operation"
}
internal fun kitchenCommandMessage(phase: KitchenInputCommandPhase, issue: String? = null) = when (phase) {
    KitchenInputCommandPhase.READY -> "Original change retained. Synchronize only when offered."
    KitchenInputCommandPhase.IN_FLIGHT -> "An attempt is in progress; its outcome is not yet acknowledged."
    KitchenInputCommandPhase.RETRY_WAIT -> "Waiting for the original change’s retry deadline."
    KitchenInputCommandPhase.AWAITING_CONFIRMATION -> "The outcome may be unknown. Original evidence is retained for reconciliation."
    KitchenInputCommandPhase.NEEDS_RESOLUTION -> "The original base and current candidate differ. No automatic overwrite or new-key rebase."
    KitchenInputCommandPhase.RECEIPT_READY -> "A response is retained; local application still needs acknowledgement."
    KitchenInputCommandPhase.APPLIED -> when (issue) {
        "OUTCOME_UNKNOWN" -> "Finalization required: the applied journal state is visible, but local acknowledgement is still missing. Synchronize the original change to request a fresh acknowledgement."
        "DOMAIN_RECHECK_REQUIRED" -> "Finalization is blocked: current local values differ from the retained result. The visible applied journal state is not a completed local acknowledgement."
        null, "NONE" -> "This change has an acknowledged applied receipt."
        else -> "An applied journal state is visible, but its acknowledgement remains unresolved. Review the retained evidence."
    }
    KitchenInputCommandPhase.DISCARDED -> "This never-sent change was explicitly discarded."
}
internal fun kitchenPhaseMessage(phase: KitchenInputPhase, issue: KitchenInputIssue): Pair<String, String> {
    if (phase == KitchenInputPhase.UNAVAILABLE) return "This kitchen is unavailable" to "Private values are hidden. This screen cannot re-authenticate or approve recovery."
    if (issue == KitchenInputIssue.OUTCOME_UNKNOWN) return "Outcome unknown" to "Keep the original change. A visible server value or retry does not prove that the prior write failed."
    return when (phase) {
        KitchenInputPhase.EDITING -> "Review your kitchen inputs" to "Editing a local draft does not apply a server change."
        KitchenInputPhase.LOADING -> "Checking current values" to "Retained drafts and original pending changes stay separate."
        KitchenInputPhase.READY -> "Observed values available" to "Only an acknowledged change is marked applied. Review whether these are historical observations."
        KitchenInputPhase.PENDING -> "Change pending" to "Use the original retained change. No automatic sending or replacement occurs here."
        KitchenInputPhase.CONFLICT -> "Resolve the difference" to "Compare the original base, immutable change and current candidate. This editor does not silently merge them."
        KitchenInputPhase.OFFLINE -> "Offline · local draft only" to "A retained draft is not a server acknowledgement. Reconnect and explicitly synchronize when offered."
        KitchenInputPhase.ERROR -> "Change not completed" to "Retain the draft and original evidence. Do not assume rollback or remote success."
        KitchenInputPhase.UNAVAILABLE -> "This kitchen is unavailable" to "Private values are hidden. This screen cannot re-authenticate or approve recovery."
    }
}
internal fun kitchenIssueMessage(issue: KitchenInputIssue): String? = when (issue) {
    KitchenInputIssue.NONE -> null
    KitchenInputIssue.LOAD_REQUIRED -> "Load current values explicitly before saving a versioned change."
    KitchenInputIssue.OFFLINE -> "Offline observations do not establish current server values."
    KitchenInputIssue.PENDING_SYNC -> "A retained change still needs synchronization or reconciliation."
    KitchenInputIssue.RECONCILIATION_REQUIRED -> "Original intent and current values need explicit resolution; no automatic rebase is available."
    KitchenInputIssue.INVALID_INPUT -> "The draft is not valid for the requested operation."
    KitchenInputIssue.STORAGE -> "Local storage acknowledgement is missing. Preserve the original evidence."
    KitchenInputIssue.OUTCOME_UNKNOWN -> "Keep the original change and its original request identity."
    KitchenInputIssue.SESSION_UNAVAILABLE -> "Current private session access is unavailable."
    KitchenInputIssue.RETRY_LATER -> "A retry deadline still applies; no automatic retry is scheduled."
    KitchenInputIssue.PAGE_LIMIT -> "The configured page limit was reached. Unloaded pantry items are not known to be absent."
}
internal fun kitchenFailureMessage(reason: FailureReason?): String? = when (reason) {
    null -> null
    FailureReason.OUTCOME_UNKNOWN -> "The write may have happened. Keep its original evidence; do not replace it with a new command."
    FailureReason.OFFLINE -> "Network access is unavailable. Local retention is not remote success."
    FailureReason.CONFLICT -> "The observed revision or pending intent changed. Review both values; no overwrite was authorized."
    FailureReason.INVALID_DATA -> "The input was not accepted. Review the retained draft."
    FailureReason.RATE_LIMITED -> "Wait for the retained retry policy; no timer or automatic retry was created."
    FailureReason.STORAGE_FAILURE -> "Local storage did not acknowledge this action. Preserve the original state."
    FailureReason.STALE_SESSION, FailureReason.UNAUTHENTICATED, FailureReason.FORBIDDEN -> "Current session access is required. No credential or recovery action is offered here."
    FailureReason.NOT_CONFIGURED -> "This operation needs its configured native or server integration."
    FailureReason.NOT_FOUND -> "The requested current item was not available. This does not prove a pending deletion succeeded."
    FailureReason.UNAVAILABLE -> "The operation is unavailable. No success was inferred."
}
