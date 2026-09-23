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
import com.feedme.mealflow.IngredientPickerPhase
import com.feedme.mealflow.IngredientPickerState
import kotlinx.coroutines.flow.StateFlow

/** The actual owner supplies currentness, real lookup snapshots and an exact local edit.
 * No callback grants pantry write, meal request or ingredient safety authority. */
class MealAdaptationIngredientsHost(
    val current: () -> Boolean,
    val states: StateFlow<IngredientPickerState>,
    val search: (String, () -> Boolean) -> Unit,
    val more: (IngredientPickerState, () -> Boolean) -> Unit,
    val apply: (List<String>, () -> Boolean) -> Boolean,
    val platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
)

/** Picker reads may change only the picker. The exact parent/main/adaptation still owns
 * this visit, and a hidden/replaced child can never borrow its successor's Apply. */
internal class BlueprintAdaptationIngredientsVisit(val host: MealAdaptationIngredientsHost,
    val form: MealFormState, val adaptation: AdaptationFormState) {
    val values = checkNotNull(adaptation.values)
    private val parent = checkNotNull(adaptation.parent)
    private var active = true

    fun current(meal: MealScreenState, observedForm: MealFormState,
        observedAdaptation: AdaptationFormState?): Boolean {
        val plan = meal.plan
        val valid = active && host.current() && observedForm === form && observedAdaptation === adaptation &&
            blueprintAdaptationRefinementAvailable(meal, form, adaptation) && plan != null &&
            plan.document.encodeUtf8().contentEquals(parent.plan.document.encodeUtf8())
        if (!valid) active = false
        return valid
    }
    fun retire() { active = false }
    override fun toString() = "BlueprintAdaptationIngredientsVisit(<redacted>)"
}

/** Original PANTRY layout over buffered version ingredients. Merely opening this dialog
 * performs no lookup; its unsupported pantry/effort controls never dispatch elsewhere. */
@Composable
internal fun BlueprintAdaptationIngredientsDialog(visit: BlueprintAdaptationIngredientsVisit,
    isCurrent: () -> Boolean, onClose: () -> Unit) {
    val picker = visit.host.states.collectAsState().value
    var attached by remember(visit) { mutableStateOf(true) }
    var interaction by remember(visit) { mutableStateOf(Any()) }
    var query by remember(visit) { mutableStateOf(visit.adaptation.searchText) }
    var selected by remember(visit) { mutableStateOf(visit.values.meal.ingredientIds.toList()) }
    var lookup by remember(visit) { mutableStateOf(false) }
    var notApplied by remember(visit) { mutableStateOf(false) }
    DisposableEffect(visit) { onDispose { attached = false; visit.retire() } }
    val renderedInteraction = interaction
    fun current() = attached && isCurrent()
    fun claim(): Boolean {
        if (!current() || interaction !== renderedInteraction) return false
        interaction = Any()
        return true
    }
    fun close() { if (attached) onClose() }
    fun back() { if (lookup) { lookup = false; interaction = Any() } else close() }

    val original = visit.values.meal.ingredientIds
    val exclusions = visit.values.meal.exclusions
    fun originalId(id: String) = original.any { it.equals(id, true) }
    fun excluded(id: String) = exclusions.any { it.equals(id, true) }
    val results = picker.searchResults.takeIf {
        picker.searchPhase == IngredientPickerPhase.READY && picker.searchQuery == query
    }.orEmpty().filter { !it.historical && it.name.isNotBlank() }
    fun knownCurrent(id: String) = picker.knownIngredients.singleOrNull {
        it.id.equals(id, true) && !it.historical && it.name.isNotBlank()
    } != null
    val allIds = (original + selected + results.map { it.id }).distinctBy { it.lowercase() }
    val selectable = allIds.filter { id ->
        selected.any { it.equals(id, true) } || !excluded(id) &&
            (originalId(id) || results.count { it.id.equals(id, true) } == 1 && knownCurrent(id))
    }.toSet()
    val validSelection = selected.size <= 128 && selected.distinctBy { it.lowercase() }.size == selected.size &&
        selected.all { originalId(it) || !excluded(it) && knownCurrent(it) }
    val rows = allIds.map { id ->
        val known = picker.knownIngredients.singleOrNull { it.id.equals(id, true) }
        BlueprintPantryItem(id, known?.name?.takeIf { it.isNotBlank() } ?: "Ingredient label unavailable ($id)",
            when {
                excluded(id) -> "Excluded in this version · remove it from the selection"
                originalId(id) -> "Retained version input · select or deselect here"
                !knownCurrent(id) -> "Selected here · look up this ingredient again before applying"
                else -> "Catalog ingredient · select only if you have it"
            }, availability = null, availabilityLabel = "Version input")
    }
    val available = current()
    val status = buildList {
        add("Choose ingredients only for this Make Mine version. Use these ingredients applies the selection; Back cancels it. Your stored pantry, main request and swap choice stay unchanged.")
        add("Type a name, then open More to search. Availability, Add to my kitchen and Set my energy are not used here.")
        when {
            picker.searchPhase == IngredientPickerPhase.LOADING -> add("Searching the ingredient catalog…")
            picker.searchQuery == query && picker.searchPhase == IngredientPickerPhase.EMPTY -> add("No matching ingredient was returned. Try another name.")
        }
        picker.failureReason?.let { add(mealFailureText(it)) }
        if (!validSelection) add("Some selected ingredients need another lookup before Apply.")
        if (notApplied) add("Nothing was applied. Your local selection is still here; check the current version before trying again.")
    }.joinToString("\n\n")

    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        visit.host.platformBackHandler(attached, ::back)
        Surface(Modifier.fillMaxSize()) {
            if (!available) {
                // Preserve the scratch owner but redact private labels after authority loss.
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("This ingredient selection is no longer current.")
                    Text("Go back without changing the version, main request or pantry.")
                    TextButton(onClick = ::close) { Text("Back to Make Mine") }
                }
            } else {
                val shown = BlueprintPantryState(ingredient = query, items = rows, enabled = !lookup,
                    allowAdd = false, allowRemove = false, allowUseIngredients = validSelection,
                    status = status, allowSetEffort = false, allowedNavigation = emptySet(),
                    mealIngredientIds = selected.toSet(), mealSelectableIds = selectable, allowAvailability = false)
                BlueprintPantryScreen(shown, onEvent = { event -> if (!lookup) when (event) {
                    is BlueprintPantryEvent.IngredientChanged -> if (event.value.length <= 200 && claim()) {
                        query = event.value; notApplied = false
                    }
                    is BlueprintPantryEvent.SelectIngredient -> if (event.id in selectable && claim()) {
                        val present = selected.firstOrNull { it.equals(event.id, true) }
                        selected = if (present != null) selected.filterNot { it.equals(event.id, true) }
                            else if (selected.size < 128) selected + event.id else selected
                        notApplied = false
                    }
                    BlueprintPantryEvent.UseIngredients -> if (validSelection && claim()) {
                        // The port rechecks this same live child and performs one synchronous
                        // ingredient-only edit. Retire only after actual local acceptance.
                        if (visit.host.apply(selected.toList(), ::current)) close() else notApplied = true
                    }
                    is BlueprintPantryEvent.AvailabilityChanged, BlueprintPantryEvent.AddOrUpdate,
                    is BlueprintPantryEvent.RemoveIngredient, BlueprintPantryEvent.SetEffort -> Unit
                } }, onBack = ::back, onMore = { if (!lookup && claim()) lookup = true }, onNavigate = {})
            }
        }
        if (lookup && available) AlertDialog(onDismissRequest = { lookup = false; interaction = Any() },
            title = { Text("Find version ingredients") },
            text = { Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Search uses the real ingredient catalog. Results appear in Your quick check; select them there. No pantry item is created.")
                OutlinedTextField(query, onValueChange = { value -> if (value.length <= 200 && claim()) query = value },
                    label = { Text("Ingredient name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (picker.searchQuery == query && picker.searchHasMore && picker.searchPhase == IngredientPickerPhase.READY)
                    TextButton(onClick = {
                        if (claim()) { lookup = false; visit.host.more(picker, ::current) }
                    }) { Text("More matches") }
            } }, dismissButton = { TextButton(onClick = { lookup = false; interaction = Any() }) { Text("Back") } },
            confirmButton = { TextButton(enabled = query.isNotBlank() && picker.searchPhase != IngredientPickerPhase.LOADING,
                onClick = { if (query.isNotBlank() && claim()) { lookup = false; visit.host.search(query, ::current) } }) { Text("Search") } })
    }
}
