package com.feedme.app.mealflow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.feedme.app.FeedMeColors
import com.feedme.app.FeedMeDetails
import com.feedme.core.ports.FailureReason
import com.feedme.mealflow.*

/** UI events only. The host binds every callback to the exact visible meal/form state. */
class AdaptationScreenActions(val open: () -> Unit,
    val edit: ((AdaptationFormValues) -> AdaptationFormValues) -> Unit,
    val searchText: (String) -> Unit, val search: () -> Unit, val moreIngredients: () -> Unit,
    val request: () -> Unit, val reopen: () -> Unit, val keepOriginal: () -> Unit,
    val accept: () -> Unit, val editRequest: () -> Unit, val viewOriginal: () -> Unit, val retry: () -> Unit,
    val missingIngredient: (() -> Unit)? = null,
    /** Explicit reviewed staging of a new request; never submits or rewrites the old Plan. */
    val planNewMeal: (() -> Unit)? = null,
    val ingredients: MealAdaptationIngredientsHost? = null)

/** The retained ADAPT design marks Time available as required. Accept only the exact
 * positive whole-minute spelling that the integer wire field can preserve. */
internal fun adaptationRequiredTotalMinutesValid(text: String): Boolean =
    text.toIntOrNull()?.let { it > 0 && it.toString() == text } == true

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdaptationRequestScreen(meal: MealScreenState, form: MealFormState, adaptation: AdaptationFormState?,
    picker: MealPickerPresentation, choices: MealInputChoices, actions: AdaptationScreenActions?) {
    val values = adaptation?.values
    val parent = adaptation?.parent
    if (values == null || parent == null || actions == null) {
        InfoCard("No editable request", "Your original meal is unchanged. Return to that meal to start an explicit request.")
        return
    }
    val labels = picker.knownIngredients.associate { it.id.lowercase() to it.name }
    val original = MealPlanPresentation(parent.plan, parent.historical, labels)
    val input = values.meal
    val pending = meal.pendingAdaptation != null || meal.phase == MealFlowPhase.RESOLVING
    val editable = adaptation.visible && !form.busy && !pending && meal.phase != MealFlowPhase.LOADING
    val needsMissingChoice = values.reason == MealAdaptationReason.MISSING_INGREDIENT && values.replaceIngredientId == null
    val timeValid = adaptationRequiredTotalMinutesValid(input.totalMinutes)
    val canRequest = editable && !form.dirty && !needsMissingChoice && timeValid
    fun name(id: String) = labels[id.lowercase()] ?: "Ingredient label unavailable ($id)"
    Text("For ${original.title}", style = MaterialTheme.typography.titleMedium)
    Text("${input.servings} servings · ${if (timeValid) "${input.totalMinutes} min available" else "Time required"} · ${energy(input.energy)}",
        style = MaterialTheme.typography.bodyMedium)
    Text("${input.equipmentIds.size} equipment choices · ${input.exclusions.size} explicit exclusions · ${input.tasteTags.size} taste preferences",
        style = MaterialTheme.typography.bodySmall)
    Text(if (pending) "The sent inputs are retained below. Retry uses the same request; it does not make a new version."
        else "Changes stay here until requested. Your original form, pantry and saved preferences are not edited.", color = FeedMeColors.Muted)
    adaptation.failure?.let { InfoCard("Request not completed", adaptationFailure(it)) }
    if (needsMissingChoice) Text("Choose the missing ingredient below before requesting a replacement.", color = FeedMeColors.Muted)
    if (!timeValid) Text("Enter time available as a positive whole number of minutes before requesting a version.", color = FeedMeColors.Muted)
    Primary(if (values.reason == MealAdaptationReason.MISSING_INGREDIENT) "Find a replacement version" else "Find my version", canRequest) {
        if (canRequest) actions.request()
    }
    if (!pending) TextButton(onClick = actions.viewOriginal, enabled = editable,
        modifier = Modifier.heightIn(min = 48.dp)) { Text("View original meal") }

    SectionTitle("01", "What should change?")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(selected = values.reason == MealAdaptationReason.MAKE_MINE,
            onClick = { actions.edit { it.copy(reason = MealAdaptationReason.MAKE_MINE) } }, enabled = editable,
            modifier = Modifier.heightIn(min = 48.dp), label = { Text("Make it mine") })
        FilterChip(selected = values.reason == MealAdaptationReason.MISSING_INGREDIENT,
            onClick = { actions.edit { it.copy(reason = MealAdaptationReason.MISSING_INGREDIENT) } }, enabled = editable,
            modifier = Modifier.heightIn(min = 48.dp), label = { Text("I'm missing an ingredient") })
    }
    AdaptationInput("Time available *", input.totalMinutes, editable) { text -> actions.edit { it.copy(meal = it.meal.copy(totalMinutes = text)) } }
    AdaptationInput("Servings for this version", input.servings, editable) { text -> actions.edit { it.copy(meal = it.meal.copy(servings = text)) } }
    Text("Your energy", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MealEnergy.entries.forEach { option -> FilterChip(selected = input.energy == option,
            onClick = { actions.edit { it.copy(meal = it.meal.copy(energy = option)) } }, enabled = editable,
            modifier = Modifier.heightIn(min = 48.dp), label = { Text(energy(option)) }) }
    }
    if (values.reason == MealAdaptationReason.MISSING_INGREDIENT || values.replaceIngredientId != null) {
        Text("Which original ingredient is missing?", style = MaterialTheme.typography.titleMedium)
        Text("Choosing one removes it only from this proposed version’s available ingredients.", style = MaterialTheme.typography.bodySmall)
        original.recipe?.ingredients.orEmpty().forEach { ingredient ->
            val id = ingredient.ingredientId.value
            FilterChip(selected = values.replaceIngredientId?.equals(id, ignoreCase = true) == true, enabled = editable,
                onClick = { actions.edit { current -> current.copy(replaceIngredientId = id,
                    requestedReplacementId = null, meal = current.meal.copy(ingredientIds = current.meal.ingredientIds.filterNot { it.equals(id, ignoreCase = true) })) } },
                modifier = Modifier.heightIn(min = 48.dp), label = { Text("Missing · ${name(id)}") })
        }
        values.replaceIngredientId?.let { id ->
            Text("Replacing: ${name(id)}")
            TextButton(onClick = { actions.edit { it.copy(reason = MealAdaptationReason.MAKE_MINE, replaceIngredientId = null,
                requestedReplacementId = null, retainTasteTag = null) } },
                enabled = editable, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear replacement request") }
        }
        values.requestedReplacementId?.let { id ->
            Text("Requested instead: ${name(id)}")
            TextButton(onClick = { actions.edit { it.copy(requestedReplacementId = null) } }, enabled = editable,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Let the request find a replacement") }
        }
        if (original.recipe?.tasteTags?.isNotEmpty() == true) {
            Text("Keep an original taste · optional", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                original.recipe!!.tasteTags.forEach { tag -> FilterChip(selected = values.retainTasteTag == tag, enabled = editable,
                    onClick = { actions.edit { it.copy(retainTasteTag = if (it.retainTasteTag == tag) null else tag) } },
                    modifier = Modifier.heightIn(min = 48.dp), label = { Text("Keep ${choices.tastes.firstOrNull { it.id == tag }?.label ?: tag}") }) }
            }
        }
    }
    SectionTitle("02", "What can you use instead?")
    Text("Select only ingredients you have checked for this meal. Search text alone never becomes an ingredient or replacement.", color = FeedMeColors.Muted)
    AdaptationInput("Search ingredients for this version", adaptation.searchText, editable, actions.searchText)
    OutlinedButton(onClick = actions.search, enabled = editable && adaptation.searchText.isNotBlank(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Search replacement ingredients") }
    if (picker.searchPhase == IngredientPickerPhase.LOADING) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (picker.issue != IngredientPickerIssue.NONE) InfoCard("Ingredient search", "Search is ${picker.issue.name.lowercase().replace('_', ' ')}. Retained labels are not a fresh availability check.")
    if (picker.searchPhase == IngredientPickerPhase.EMPTY) Text("No matches. Refine the search; nothing was selected.")
    picker.searchResults.forEach { option ->
        val isMissing = values.reason == MealAdaptationReason.MISSING_INGREDIENT && values.replaceIngredientId?.equals(option.id, true) == true
        Surface(color = FeedMeColors.Surface, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(value = option.id in input.ingredientIds,
                    enabled = editable && !isMissing, role = Role.Checkbox,
                    onValueChange = { selected -> actions.edit { current -> current.copy(meal = current.meal.copy(ingredientIds =
                        if (selected) (current.meal.ingredientIds + option.id).distinct() else current.meal.ingredientIds.filterNot { it == option.id })) } }),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = option.id in input.ingredientIds, onCheckedChange = null, enabled = editable && !isMissing)
                    Text("I have · ${option.name}", Modifier.weight(1f))
                }
                if (option.historical) Text("Previously fetched label", style = MaterialTheme.typography.bodySmall)
                if (values.replaceIngredientId != null) TextButton(enabled = editable &&
                    original.recipe?.ingredients.orEmpty().none { it.ingredientId.value.equals(option.id, true) },
                    onClick = { actions.edit { current -> current.copy(requestedReplacementId = option.id,
                        meal = current.meal.copy(ingredientIds = (current.meal.ingredientIds + option.id).distinct())) } },
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("I have this instead · ${option.name}") }
            }
        }
    }
    if (picker.searchHasMore) TextButton(onClick = actions.moreIngredients, enabled = editable,
        modifier = Modifier.heightIn(min = 48.dp)) { Text("More replacement ingredients") }
    if (input.ingredientIds.isNotEmpty()) Text("Confirmed for this version", style = MaterialTheme.typography.titleMedium)
    input.ingredientIds.forEach { id -> TextButton(onClick = { actions.edit { current -> current.copy(meal = current.meal.copy(
        ingredientIds = current.meal.ingredientIds.filterNot { it == id })) } }, enabled = editable,
        modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove from this version · ${name(id)}") } }

    Text(mealRefinementSummary(input, choices), style = MaterialTheme.typography.bodySmall)
    FeedMeDetails("Equipment, exclusions & more limits") {
        AdaptationChoices("Equipment available", choices.equipment, input.equipmentIds, editable) { id, selected -> actions.edit {
            it.copy(meal = it.meal.copy(equipmentIds = chosen(it.meal.equipmentIds, id, selected))) } }
        AdaptationInput("Hands-on minutes for this version · optional", input.activeMinutes, editable) { text -> actions.edit { it.copy(meal = it.meal.copy(activeMinutes = text)) } }
        AdaptationInput("Cleanup minutes for this version · optional", input.cleanupMinutes, editable) { text -> actions.edit { it.copy(meal = it.meal.copy(cleanupMinutes = text)) } }
        Text("Blank means no entered limit. Zero means zero minutes.", style = MaterialTheme.typography.bodySmall)
        AdaptationChoices("Taste preferences for this version", choices.tastes, input.tasteTags, editable) { id, selected -> actions.edit {
            it.copy(meal = it.meal.copy(tasteTags = chosen(it.meal.tasteTags, id, selected))) } }
        Text("Explicit exclusions", style = MaterialTheme.typography.titleMedium)
        Text("Saved hard exclusions also apply. Removing a choice here never removes a saved preference.")
        input.exclusions.forEach { id -> TextButton(onClick = { actions.edit { current -> current.copy(meal = current.meal.copy(
            exclusions = current.meal.exclusions.filterNot { it == id })) } }, enabled = editable,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove version exclusion · ${name(id)}") } }
        picker.searchResults.filter { it.id !in input.exclusions }.forEach { option -> TextButton(onClick = { actions.edit {
            it.copy(meal = it.meal.copy(exclusions = (it.meal.exclusions + option.id).distinct())) } }, enabled = editable,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("Exclude from this version · ${option.name}") } }
        Text("Original meal mode and prepared-base details stay unchanged. Start a separate meal request to change them.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun AdaptationProposalScreen(meal: MealScreenState, form: MealFormState, picker: MealPickerPresentation,
    choices: MealInputChoices, actions: AdaptationScreenActions?) {
    val proposal = meal.adaptation
    if (proposal == null || actions == null) {
        InfoCard("No retained version", "Your original meal is unchanged. Go back to review it.")
        return
    }
    val view = AdaptationPresentation.from(proposal, picker.knownIngredients.associate { it.id to it.name }, choices)
    val idle = !form.busy && meal.phase !in setOf(MealFlowPhase.RESOLVING, MealFlowPhase.LOADING) && meal.pendingAdaptation == null
    Text(view.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    InfoCard("Your original stays selected", view.originalNotice)
    Text(view.statusMessage)
    if (view.canCompare) Primary("Use this version", idle && !form.dirty, actions.accept)
    OutlinedButton(onClick = actions.keepOriginal, enabled = idle,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Keep original meal") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = actions.viewOriginal, enabled = idle, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("View original") }
        TextButton(onClick = actions.editRequest, enabled = idle, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Edit this request") }
    }
    InfoCard("Cooking and saving stay separate", "Use this version changes only the selected meal. It does not cook, save, share, or replace a running cook’s steps and timers.")
    if (view.canCompare) {
        Text(view.proposed.title, style = MaterialTheme.typography.headlineMedium)
        InfoCard("Original → proposed", view.effortLines.joinToString("\n"))
        if (view.serverChanges.isNotEmpty()) InfoCard(view.serverChangesLabel, view.serverChanges.joinToString("\n"))
        if (view.reasons.isNotEmpty()) InfoCard("Recorded reasons", view.reasons.joinToString("\n"))
        Text(view.comparisonNotice, style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
        InfoCard("Missing ingredients", (listOf(view.missingIngredientStatus) + view.missingIngredientLines).joinToString("\n"))
        SectionTitle("01", "Ingredients: before and after.")
        if (view.ingredientRows.isEmpty()) Text("No ingredient amount or preparation changes recorded.")
        view.ingredientRows.forEach { DifferenceRow(it) }
        InfoCard("Equipment changes", view.equipmentLines.joinToString("\n"))
        SectionTitle("02", "Preparation: before and after.")
        if (view.stepRows.isEmpty()) Text("No step changes recorded.")
        view.stepRows.forEach { DifferenceRow(it) }
        FeedMeDetails("Read complete proposed recipe") { PlanCard(view.proposed, true, choices) }
    } else {
        if (view.reasons.isNotEmpty()) InfoCard("Recorded response", view.reasons.joinToString("\n"))
        if (view.missingIngredientLines.isNotEmpty()) InfoCard("Ingredients needing confirmation",
            (listOf(view.missingIngredientStatus) + view.missingIngredientLines).joinToString("\n"))
        InfoCard("No automatic replacement", "Keep your original or edit the request. No recipe or reviewed substitute is invented from a missing result.")
    }
    FeedMeDetails("Read complete original recipe") { PlanCard(view.original, true, choices) }
}

@Composable private fun DifferenceRow(row: AdaptationDisplayRow) {
    Surface(color = FeedMeColors.Surface, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
        Column(Modifier.fillMaxWidth().padding(16.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.title, style = MaterialTheme.typography.titleMedium)
            Text("Original: ${row.before}"); Text("Proposed: ${row.after}")
        }
    }
}
@Composable private fun AdaptationInput(label: String, value: String, enabled: Boolean, change: (String) -> Unit) {
    OutlinedTextField(value, change, enabled = enabled, label = { Text(label) }, singleLine = true,
        shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp))
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun AdaptationChoices(title: String, choices: List<MealInputChoice>, selected: List<String>, enabled: Boolean,
    change: (String, Boolean) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { option -> FilterChip(selected = option.id in selected, enabled = enabled,
            onClick = { change(option.id, option.id !in selected) }, modifier = Modifier.heightIn(min = 48.dp), label = { Text(option.label) }) }
    }
    selected.filter { id -> choices.none { it.id == id } }.forEach { id ->
        TextButton(onClick = { change(id, false) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove unavailable choice · $id") }
    }
}
private fun chosen(values: List<String>, id: String, selected: Boolean) = if (selected) (values + id).distinct() else values.filterNot { it == id }
private fun energy(value: MealEnergy) = when (value) { MealEnergy.ASSEMBLE -> "Barely any energy"; MealEnergy.LITTLE -> "A little effort"; MealEnergy.HAPPY -> "Happy to cook" }
private fun adaptationFailure(reason: FailureReason) = when (reason) {
    FailureReason.INVALID_DATA -> "Check the entered limits and replacement choices. Nothing was selected."
    FailureReason.CONFLICT -> "The meal or inputs changed. Review the original before requesting again."
    FailureReason.NOT_CONFIGURED -> "This request is not available in the current runtime. Your original remains selected."
    else -> "The action could not be completed. Your original meal is unchanged; no new version is assumed."
}
