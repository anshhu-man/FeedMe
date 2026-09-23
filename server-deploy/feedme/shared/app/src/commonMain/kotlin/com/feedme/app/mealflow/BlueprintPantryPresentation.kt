package com.feedme.app.mealflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.feedme.app.blueprint.*
import com.feedme.contracts.WireDocument
import com.feedme.mealflow.*
import kotlinx.serialization.json.*
import kotlin.time.Instant

internal fun blueprintPantryHealthy(state: KitchenInputState): Boolean =
    state.phase in setOf(KitchenInputPhase.EDITING, KitchenInputPhase.READY) &&
        state.issue == KitchenInputIssue.NONE && state.failureReason == null &&
        state.draftAcknowledged && state.pantryDrafts.isEmpty() && state.pending.isEmpty()

internal fun blueprintPantryIngredientIsCurrent(picker: IngredientPickerState, ingredient: IngredientOption,
    query: String): Boolean = picker.searchPhase == IngredientPickerPhase.READY &&
    picker.issue == IngredientPickerIssue.NONE && picker.searchQuery == query &&
    !ingredient.historical && picker.searchResults.count { it.id.equals(ingredient.id, true) } == 1 &&
    picker.searchResults.any { it === ingredient }

/** Explicit user report only. Neither this timestamp nor a pantry row certifies food safety. */
internal fun blueprintPantryWrite(current: WireDocument?, id: String, availability: BlueprintIngredientAvailability,
    nowMillis: Long): WireDocument {
    val presence = when (availability) {
        BlueprintIngredientAvailability.CONFIRMED -> "available"
        BlueprintIngredientAvailability.USUALLY_HAVE -> "usuallyHave"
        BlueprintIngredientAvailability.UNAVAILABLE -> "out"
    }
    val base = kitchenPantryDraft(current, null, id, presence)
    if (availability != BlueprintIngredientAvailability.CONFIRMED) return base
    require(nowMillis >= 0)
    return WireDocument.parse(JsonObject(kitchenObject(base) + mapOf(
        "confirmationStatus" to JsonPrimitive("confirmed"),
        "confirmedAt" to JsonPrimitive(Instant.fromEpochMilliseconds(nowMillis).toString()))).toString())
}

internal fun blueprintPantryRows(state: KitchenInputState, picker: IngredientPickerState): List<BlueprintPantryItem>? {
    if (!blueprintPantryHealthy(state)) return null
    return try {
        val ids = state.pantryItems.map(::kitchenIngredientId)
        if (ids.distinct().size != ids.size) return null
        state.pantryItems.map { row ->
            val value = kitchenObject(row); val id = kitchenIngredientId(row)
            val presence = value["presence"]?.jsonPrimitive?.content.orEmpty()
            val confirmation = value["confirmationStatus"]?.jsonPrimitive?.contentOrNull
            val kind = when {
                presence == "out" -> BlueprintIngredientAvailability.UNAVAILABLE
                presence == "usuallyHave" -> BlueprintIngredientAvailability.USUALLY_HAVE
                presence == "available" && confirmation == "confirmed" -> BlueprintIngredientAvailability.CONFIRMED
                else -> null
            }
            val confirmed = value["confirmedAt"]?.jsonPrimitive?.contentOrNull
            BlueprintPantryItem(id,
                picker.knownIngredients.singleOrNull { it.id.equals(id, true) }?.name ?: "Ingredient label unavailable ($id)",
                listOfNotNull(if (id !in state.observedPantryIngredientIds) "Saved observation · check again" else null,
                    confirmed?.let { "Reported confirmation: $it" }).joinToString(" · "), kind,
                if (kind == null) kitchenPresenceLabel(presence) else null)
        }
    } catch (_: Exception) { null }
}

/** Explicit loaded-subset selection, never an assertion that the pantry is complete. Only
 * the owner supplies current catalog IDs; no timestamp is renewed and no pantry fact changes. */
internal fun blueprintPantryMealValues(values: MealFormValues, rows: List<WireDocument>,
    currentCatalogIds: List<String>, selectedIds: List<String>, stricterExclusions: List<String>,
    preferencesPending: Boolean): MealFormValues? {
    return try {
        val selected = selectedIds.map { it.lowercase() }
        val rowIds = rows.map { kitchenIngredientId(it).lowercase() }
        val catalogIds = currentCatalogIds.map { it.lowercase() }
        val exclusions = values.exclusions + stricterExclusions.filter { extra ->
            values.exclusions.none { it.equals(extra, true) }
        }.distinctBy { it.lowercase() }
        if (selected.isEmpty() || selected.size > 128 || selected.distinct().size != selected.size ||
            rowIds.distinct().size != rowIds.size || exclusions.size > 128 ||
            selected.any { id -> exclusions.any { it.equals(id, true) } }) null
        else {
            val ids = selected.map { id ->
                if (catalogIds.count { it == id } != 1) return null
                val row = rows.singleOrNull { kitchenIngredientId(it).equals(id, true) } ?: return null
                val value = kitchenObject(row)
                if (value["presence"]?.jsonPrimitive?.contentOrNull != "available" ||
                    value["confirmationStatus"]?.jsonPrimitive?.contentOrNull != "confirmed") return null
                val at = value["confirmedAt"]?.jsonPrimitive?.contentOrNull ?: return null
                Instant.parse(at) // A retained observation, not a freshness or food-safety proof.
                kitchenIngredientId(row)
            }
            values.copy(ingredientIds = ids, exclusions = exclusions,
                preferencesPendingSync = values.preferencesPendingSync || preferencesPending)
        }
    } catch (_: Exception) { null }
}

internal fun blueprintPantryMealValues(state: KitchenInputState, picker: IngredientPickerState,
    values: MealFormValues, selectedIds: List<String>): MealFormValues? {
    if (!blueprintPantryHealthy(state) || state.preferencesHistorical ||
        selectedIds.any { id -> state.observedPantryIngredientIds.none { it.equals(id, true) } }) return null
    return blueprintPantryMealValues(values, state.pantryItems,
        picker.knownIngredients.filter { !it.historical && it.name.isNotBlank() }.map { it.id }, selectedIds,
        state.stricterExclusionIds, state.preferencesPending || state.preferenceDraft != null)
}

internal fun blueprintPantryStatus(state: KitchenInputState, picker: IngredientPickerState): String {
    if (state.preferencesHistorical) return "Saved food preferences. Open More to refresh your pantry before choosing ingredients."
    val ids = state.pantryItems.map(::kitchenIngredientId).filter { it in state.observedPantryIngredientIds }
    val unresolved = ids.filter { id -> picker.knownIngredients.singleOrNull {
        it.id.equals(id, true) && !it.historical && it.name.isNotBlank()
    } == null }
    val sameRead = ids.map(String::lowercase).toSet() == picker.requestedLabelIds.toSet()
    if (unresolved.isNotEmpty() && sameRead) return when {
        picker.labelPhase == IngredientPickerPhase.LOADING -> "Loading ingredient names… Your meal selections stay unchanged."
        picker.labelPhase in setOf(IngredientPickerPhase.READY, IngredientPickerPhase.EMPTY) ->
            "Some ingredients are unavailable in the current catalog. Choose the confirmed, named ingredients."
        else -> "Some ingredient names could not be checked. Open More and reload your pantry to retry."
    }
    return if (state.pantryItems.any { kitchenIngredientId(it) !in state.observedPantryIngredientIds })
        "Some saved reports have not been refreshed. Select only confirmed ingredients from the current loaded rows. More has refresh controls."
    else "Select confirmed ingredients for your request. Uncertain, unavailable, excluded or unresolved ingredients cannot be selected. More has refresh controls."
}

/** Real owner-backed normal pantry view. Lookup is explicit and never silently picks the
 * first result. Original reconciliation/editor tools remain behind the source More control. */
@Composable
internal fun BlueprintAccountPantryScreen(state: KitchenInputState, picker: IngredientPickerState,
    busy: Boolean, query: String, rows: List<BlueprintPantryItem>, actions: KitchenInputScreenActions,
    tools: @Composable (KitchenInputScreenActions) -> Unit) {
    var choice by remember(actions.blueprintOwnerKey) { mutableStateOf<IngredientOption?>(null) }
    var availability by remember(actions.blueprintOwnerKey) { mutableStateOf<BlueprintIngredientAvailability?>(null) }
    var selectedId by remember(actions.blueprintOwnerKey) { mutableStateOf<String?>(null) }
    // Ignore only EFFORT's three local fields in this equality key. This copy is never an
    // edited/sent meal: actual values still own validation and Use ingredients. Every other
    // source change, pantry/catalog refresh or reopen still requires a new explicit selection.
    val selectionSource = actions.blueprintMealValues?.copy(
        energy = MealEnergy.LITTLE, totalMinutes = "", activeMinutes = "")
    var mealIds by remember(actions.blueprintOwnerKey, state, picker, selectionSource) { mutableStateOf(emptyList<String>()) }
    var showTools by remember(actions.blueprintOwnerKey) { mutableStateOf(false) }
    var showLookup by remember(actions.blueprintOwnerKey) { mutableStateOf(false) }
    var deleting by remember(state) { mutableStateOf<WireDocument?>(null) }
    var interaction by remember(actions.blueprintOwnerKey) { mutableStateOf(Any()) }
    val renderedInteraction = interaction
    val renderedAvailability = availability
    val currentChoice = choice?.takeIf { blueprintPantryIngredientIsCurrent(picker, it, query) }
    val selected = state.pantryItems.singleOrNull { kitchenIngredientId(it) == selectedId }
    val values = actions.blueprintMealValues
    val selectableIds = if (values == null) emptySet() else rows.filter {
        blueprintPantryMealValues(state, picker, values, listOf(it.id)) != null
    }.map { it.id }.toSet()
    val selection = if (values == null) null else blueprintPantryMealValues(state, picker, values, mealIds)
    fun current() = !actions.blueprintRefinementOpen && actions.blueprintIsCurrent() && interaction === renderedInteraction
    fun mainCurrent() = current() && !showLookup && !showTools && deleting == null
    fun lookupCurrent() = current() && showLookup && !showTools && deleting == null
    fun toolsCurrent() = current() && showTools && !showLookup && deleting == null
    fun closeLookup() { if (lookupCurrent()) { showLookup = false; interaction = Any() } }
    fun closeTools() { if (toolsCurrent()) { showTools = false; interaction = Any() } }
    val view = BlueprintPantryState(ingredient = query, availability = availability, items = rows,
        selectedItemId = selectedId, enabled = !busy && !actions.blueprintRefinementOpen,
        allowAdd = actions.blueprintAdd != null, allowRemove = selected != null && actions.blueprintRemove != null,
        allowUseIngredients = actions.blueprintUseIngredients != null && selection != null,
        mealIngredientIds = mealIds.toSet(), mealSelectableIds = selectableIds,
        allowSetEffort = actions.blueprintSetEffort != null, allowedNavigation = emptySet(),
        status = blueprintPantryStatus(state, picker))
    BlueprintPantryScreen(view, onEvent = { event -> if (mainCurrent()) when (event) {
        is BlueprintPantryEvent.IngredientChanged -> { choice = null; interaction = Any(); actions.searchText(event.value) }
        is BlueprintPantryEvent.AvailabilityChanged -> { availability = event.value; interaction = Any() }
        is BlueprintPantryEvent.SelectIngredient -> if (rows.count { it.id == event.id } == 1) {
            selectedId = event.id
            if (event.id in selectableIds) mealIds = if (event.id in mealIds) mealIds - event.id else mealIds + event.id
            interaction = Any()
        }
        BlueprintPantryEvent.AddOrUpdate -> if (view.canAdd) {
            if (currentChoice == null) { showLookup = true; interaction = Any(); actions.blueprintSearch?.invoke() }
            else renderedAvailability?.let { interaction = Any(); actions.blueprintAdd?.invoke(currentChoice, it) }
        }
        is BlueprintPantryEvent.RemoveIngredient -> if (view.canRemove && event.id == selectedId) { deleting = selected; interaction = Any() }
        BlueprintPantryEvent.UseIngredients -> if (view.allowUseIngredients) {
            val exactIds = mealIds.toList(); interaction = Any(); actions.blueprintUseIngredients?.invoke(exactIds)
        }
        BlueprintPantryEvent.SetEffort -> if (!busy && view.allowSetEffort) {
            interaction = Any(); actions.blueprintSetEffort?.invoke()
        }
    } }, onBack = { if (mainCurrent()) { interaction = Any(); actions.back() } },
        onMore = { if (mainCurrent()) { showTools = true; interaction = Any() } }, onNavigate = {})
    if (showLookup && current()) AlertDialog(onDismissRequest = ::closeLookup,
        title = { Text("Choose the ingredient") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Choose a catalog match, then tap Add to my kitchen. Looking up a name does not add it.")
                if (busy || picker.searchPhase == IngredientPickerPhase.LOADING) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (picker.searchQuery == query && picker.searchPhase == IngredientPickerPhase.READY)
                    picker.searchResults.forEach { option -> TextButton(onClick = {
                        if (lookupCurrent() && blueprintPantryIngredientIsCurrent(picker, option, query)) {
                            choice = option; showLookup = false; interaction = Any()
                        }
                    }, enabled = !busy && blueprintPantryIngredientIsCurrent(picker, option, query)) { Text(option.name) } }
                if (!busy && picker.searchPhase != IngredientPickerPhase.READY) Text("No confirmed match is available. Try another ingredient name or open More for lookup details.")
                if (!busy && picker.searchHasMore) TextButton(enabled = actions.blueprintMoreIngredients != null,
                    onClick = { if (lookupCurrent()) { interaction = Any(); actions.blueprintMoreIngredients?.invoke() } }) { Text("More matches") }
            }
        }, confirmButton = { TextButton(onClick = ::closeLookup) { Text("Back") } })
    val exact = deleting
    fun deleteCurrent() = exact != null && current() && deleting === exact && !showTools && !showLookup
    fun closeDelete() { if (deleteCurrent()) { deleting = null; interaction = Any() } }
    if (exact != null && current()) AlertDialog(onDismissRequest = ::closeDelete,
        title = { Text("Remove this pantry item?") },
        text = { Text("Remove ${rows.singleOrNull { it.id == kitchenIngredientId(exact) }?.name.orEmpty()} from your account pantry? This does not change your meal's selected ingredients.") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            if (deleteCurrent() && state.pantryItems.any { it === exact }) { deleting = null; interaction = Any(); actions.blueprintRemove?.invoke(exact) }
        }) { Text("Remove") } }, dismissButton = { TextButton(onClick = ::closeDelete) { Text("Keep") } })
    if (showTools && current()) Dialog(onDismissRequest = ::closeTools, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { Column {
            TextButton(onClick = ::closeTools) { Text("Back to pantry") }
            Box(Modifier.weight(1f)) {
                tools(guardedPantryTools(actions) {
                    if (!toolsCurrent()) false else { interaction = Any(); true }
                })
            }
        } }
    }
}

/** Rejection-only local dialog fence. The original callbacks still own controller/dispatch
 * admission; these wrappers cannot make a stale caller current or acquire a newer pantry. */
private fun guardedPantryTools(actions: KitchenInputScreenActions, claim: () -> Boolean) = KitchenInputScreenActions(
    back = { if (claim()) actions.back() }, searchText = { if (claim()) actions.searchText(it) },
    search = { if (claim()) actions.search() }, moreIngredients = { if (claim()) actions.moreIngredients() },
    loadPreferences = { if (claim()) actions.loadPreferences() }, loadPantry = { if (claim()) actions.loadPantry() },
    morePantry = { if (claim()) actions.morePantry() }, editPreferences = { if (claim()) actions.editPreferences(it) },
    discardPreferenceDraft = { if (claim()) actions.discardPreferenceDraft() }, savePreferences = { if (claim()) actions.savePreferences() },
    editPantry = { if (claim()) actions.editPantry(it) }, discardPantryDraft = { if (claim()) actions.discardPantryDraft(it) },
    savePantry = { if (claim()) actions.savePantry(it) }, removePantry = { if (claim()) actions.removePantry(it) },
    synchronize = { if (claim()) actions.synchronize(it) }, discardUnsent = { if (claim()) actions.discardUnsent(it) })
