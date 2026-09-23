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
import com.feedme.contracts.*
import com.feedme.mealflow.*

/** Only rendered events. Source records and returned Plans remain different types. */
class CatalogRecipeActions(val open: () -> Unit, val query: (String) -> Unit,
    val browse: () -> Unit, val more: () -> Unit, val recipe: (MealCatalogRecipe) -> Unit,
    val refresh: () -> Unit, val makeMine: () -> Unit,
    val edit: ((MealFormValues) -> MealFormValues) -> Unit,
    val ingredientQuery: (String) -> Unit, val ingredients: () -> Unit, val moreIngredients: () -> Unit,
    val request: () -> Unit, val retry: () -> Unit, val accept: () -> Unit,
    val keep: () -> Unit, val reopen: () -> Unit, val editRequest: () -> Unit,
    val sourceReviewNotice: String? = null)

internal fun catalogEffort(recipe: RecipeVersionWire) =
    "${recipe.totalMinutes.jsonToken} min total · ${recipe.activeMinutes.jsonToken} min hands-on · " +
        (recipe.cleanupMinutes.valueOrNull()?.jsonToken?.let { "$it min cleanup" } ?: "Cleanup not recorded")

@Composable
internal fun CatalogRecipeScreen(meal: MealScreenState, picker: MealPickerPresentation, form: MealFormState,
    root: RootRecipeFormState?, choices: MealInputChoices, query: String, actions: CatalogRecipeActions?) {
    // The host presents one source-status notice, including any distinct failure/retry state.
    if (mealStatusPresentation(meal, form, root).sourceUnavailable) return
    if (actions == null) { InfoCard("Browse unavailable", "Return to your meal. Nothing has changed."); return }
    actions.sourceReviewNotice?.let { InfoCard("Your edits are retained", it) }
    val idle = !form.busy && meal.phase !in setOf(MealFlowPhase.LOADING, MealFlowPhase.RESOLVING)
    val labels = picker.knownIngredients.associate { it.id to it.name }
    when (meal.screen) {
        MealFlowScreen.CATALOG -> {
            Text("Find a starting point.", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text("Browse recipes, then make one fit what you have.", color = FeedMeColors.Muted)
            RootInput("Search recipes · optional", query, idle, actions.query)
            Primary(if (query.isBlank()) "Browse recipes" else "Search recipes", idle && meal.pendingRootDraft == null && meal.directRecipeMakeMineAvailable, actions.browse)
            val page = meal.catalog
            if (page == null) Text("Nothing loads until you choose Browse. Your current meal stays selected.", style = MaterialTheme.typography.bodySmall)
            else {
                Text(if (page.query.isBlank()) "Recipe ideas" else "Results for “${page.query}”", style = MaterialTheme.typography.titleMedium)
                if (page.items.isEmpty()) {
                    if (page.nextCursor != null) InfoCard("More recipes to check", "This page has no visible matches. Choose More recipes to continue the search.")
                    else InfoCard("No recipes here yet", "Try a different search. We won’t invent a match.")
                }
                page.items.forEach { item ->
                    Surface(color = FeedMeColors.Surface, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(item.recipe.title, style = MaterialTheme.typography.titleLarge)
                            Text(catalogEffort(item.recipe), style = MaterialTheme.typography.bodyMedium)
                            Text("${item.recipe.ingredients.size} ingredients · ${item.recipe.servings.jsonToken} servings", color = FeedMeColors.Muted)
                            OutlinedButton(onClick = { actions.recipe(item) }, enabled = idle && meal.pendingRootDraft == null,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("View recipe") }
                        }
                    }
                }
                if (page.nextCursor != null) TextButton(onClick = actions.more, enabled = idle && query.trim() == page.query,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("More recipes") }
            }
            if (meal.pendingRootDraft != null) {
                InfoCard("Earlier request retained", "Review or retry that exact request before choosing a different recipe.")
                Primary("Return to retained request", !form.busy, actions.open)
            }
        }
        MealFlowScreen.CATALOG_RECIPE, MealFlowScreen.SAVED_RECIPE, MealFlowScreen.POST_RECIPE -> {
            val source = meal.rootSource
            if (source == null) { InfoCard("Recipe unavailable", "Return to Browse and choose a recipe."); return }
            SourceRecipeHeader(source.recipe)
            SavedRootProvenance(source)
            if (meal.rootProposal != null) {
                Primary("Review your version", idle, actions.reopen)
                Text("A returned comparison is retained. Your current meal has not been replaced.", color = FeedMeColors.Muted)
            } else {
                Primary("Make Mine", idle && !form.dirty && meal.rootMakeMineAvailable, actions.makeMine)
                Text("Choose your ingredients, time and energy. Compare a version before using it.", color = FeedMeColors.Muted)
                TextButton(onClick = actions.refresh, enabled = idle && meal.rootMakeMineAvailable,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(if (source.savedRecipeId == null) "Check this recipe again" else "Check this saved copy again") }
            }
            CatalogRecipeReading(source.recipe, labels, choices)
            Text("This is a recipe reference, not a meal selected for cooking. Make Mine checks the source and your saved exclusions again.", style = MaterialTheme.typography.bodySmall)
        }
        MealFlowScreen.ROOT_MAKE_MINE -> RootRecipeRequest(meal, picker, form, root, choices, actions)
        MealFlowScreen.ROOT_VARIANT -> {
            val proposal = meal.rootProposal
            if (proposal == null) { InfoCard("No comparison available", "Your current meal is unchanged."); return }
            val view = MealPlanPresentation(proposal.child.plan, proposal.child.historical, labels)
            Text("Your recipe. Your way.", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            InfoCard("Nothing replaced yet", "${proposal.source.recipe.title} is your reference. " +
                (if (meal.plan != null) "Your current meal stays unchanged until you choose this version. " else "No meal is selected by this comparison. ") +
                "Your saved preferences are never changed here.")
            SavedRootProvenance(proposal.source)
            if (view.recipeVisible) {
                Primary("Use this version", idle && !form.dirty, actions.accept)
                val proposed = view.recipe!!; val original = proposal.source.recipe
                InfoCard("Original → your version", listOf(
                    simplificationEffortLine("Total", original.totalMinutes.jsonToken, proposed.totalMinutes.jsonToken),
                    simplificationEffortLine("Hands-on", original.activeMinutes.jsonToken, proposed.activeMinutes.jsonToken),
                    simplificationEffortLine("Cleanup", original.cleanupMinutes.valueOrNull()?.jsonToken, proposed.cleanupMinutes.valueOrNull()?.jsonToken),
                    simplificationEffortLine("Servings", original.servings.jsonToken, proposed.servings.jsonToken, ""),
                ).joinToString("\n"))
                Text("Recorded estimates for each recipe, not a guarantee or a per-serving comparison.", style = MaterialTheme.typography.bodySmall)
                if (view.changes.isNotEmpty()) InfoCard("What changed", view.changes.joinToString("\n"))
                PlanCard(view, false, choices)
                FeedMeDetails("Read your complete version") { PlanCard(view, true, choices) }
            } else {
                InfoCard(if (proposal.child.plan.status == "needsConfirmation") "A little clarity first" else "No matching version yet",
                    "No version has been selected. Review your ingredients and limits, or return to your original.")
                if (view.reasons.isNotEmpty()) InfoCard("Why", view.reasons.joinToString("\n"))
                if (proposal.child.plan.missingIngredients.isNotEmpty()) InfoCard("Check these ingredients",
                    proposal.child.plan.missingIngredients.joinToString("\n", transform = view::ingredientLine))
            }
            OutlinedButton(onClick = actions.keep, enabled = idle, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(if (proposal.source.savedRecipeId != null) "Keep my saved recipe" else if (meal.plan == null) "Keep browsing" else "Keep my current meal")
            }
            TextButton(onClick = actions.editRequest, enabled = idle && !form.dirty && meal.rootMakeMineAvailable,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Discard this comparison and edit") }
            Text("Using a version selects it only. Cooking, Save and sharing each need a separate action.", color = FeedMeColors.Muted)
            FeedMeDetails("Read the original reference") { CatalogRecipeReading(proposal.source.recipe, labels, choices) }
        }
        else -> Unit
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RootRecipeRequest(meal: MealScreenState, picker: MealPickerPresentation, form: MealFormState,
    root: RootRecipeFormState?, choices: MealInputChoices, actions: CatalogRecipeActions) {
    val values = root?.values; val source = currentRootRecipeFormSource(meal, root)
    if (root == null || values == null || source == null) return
    val editable = root.visible && !form.busy && meal.pendingRootDraft == null && meal.rootProposal == null &&
        meal.phase !in setOf(MealFlowPhase.LOADING, MealFlowPhase.RESOLVING)
    val names = picker.knownIngredients.associate { it.id to it.name }
    fun name(id: String) = recipeIngredientLabel(id, names) ?: "Ingredient label unavailable ($id)"
    Text("Make ${source.recipe.title} yours.", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    SavedRootProvenance(source)
    Text(if (meal.pendingRootDraft != null) "These are the exact sent choices. Retry never replaces them with new edits."
        else "Change only what matters. Your current meal stays untouched.", color = FeedMeColors.Muted)
    Text(mealOptionsSummary(values, choices), style = MaterialTheme.typography.bodySmall)
    Primary("Find my version", editable && !form.dirty && meal.rootMakeMineAvailable, actions.request)
    SectionTitle("01", "What do you have?")
    RootInput("Search ingredients", root.searchText, editable, actions.ingredientQuery)
    OutlinedButton(onClick = actions.ingredients, enabled = editable && root.searchText.isNotBlank(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Search ingredients") }
    if (picker.searchPhase == IngredientPickerPhase.LOADING) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (picker.issue != IngredientPickerIssue.NONE) Text("Ingredient search is ${picker.issue.name.lowercase().replace('_', ' ')}. Nothing was assumed.")
    if (picker.searchPhase == IngredientPickerPhase.EMPTY) Text("No ingredient matches. Try another search.")
    picker.searchResults.forEach { item ->
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(value = item.id in values.ingredientIds,
            enabled = editable, role = Role.Checkbox, onValueChange = { selected -> actions.edit { current ->
                current.copy(ingredientIds = if (selected) (current.ingredientIds + item.id).distinct() else current.ingredientIds - item.id) } }),
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(item.id in values.ingredientIds, null, enabled = editable)
            Text("I have · ${item.name}", Modifier.weight(1f))
        }
    }
    if (picker.searchHasMore) TextButton(onClick = actions.moreIngredients, enabled = editable,
        modifier = Modifier.heightIn(min = 48.dp)) { Text("More ingredient matches") }
    if (values.ingredientIds.isEmpty()) Text("Select what you actually have. Recipe ingredients are never assumed available.", color = FeedMeColors.Muted)
    values.ingredientIds.forEach { id -> TextButton(onClick = { actions.edit { it.copy(ingredientIds = it.ingredientIds - id) } },
        enabled = editable, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove · ${name(id)}") } }
    SectionTitle("02", "Make it doable.")
    RootInput("Total minutes · optional", values.totalMinutes, editable) { text -> actions.edit { it.copy(totalMinutes = text) } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MealEnergy.entries.forEach { energy -> FilterChip(values.energy == energy,
            { actions.edit { it.copy(energy = energy) } }, enabled = editable, modifier = Modifier.heightIn(min = 48.dp),
            label = { Text(when (energy) { MealEnergy.ASSEMBLE -> "Low energy"; MealEnergy.LITTLE -> "A little effort"; MealEnergy.HAPPY -> "Happy to cook" }) }) }
    }
    RootInput("Cleanup minutes · optional", values.cleanupMinutes, editable) { text -> actions.edit { it.copy(cleanupMinutes = text) } }
    RootInput("Servings", values.servings, editable) { text -> actions.edit { it.copy(servings = text) } }
    Text("Blank means no entered limit. Zero means zero minutes.", style = MaterialTheme.typography.bodySmall)
    FeedMeDetails("Equipment, exclusions & more") {
        RootChoices("Equipment available", choices.equipment, values.equipmentIds, editable) { id ->
            actions.edit { it.copy(equipmentIds = rootToggle(it.equipmentIds, id)) } }
        RootInput("Hands-on minutes · optional", values.activeMinutes, editable) { text -> actions.edit { it.copy(activeMinutes = text) } }
        RootChoices("Taste preferences", choices.tastes, values.tasteTags, editable) { id -> actions.edit { it.copy(tasteTags = rootToggle(it.tasteTags, id)) } }
        Text("Saved hard exclusions always apply. These choices don’t change your saved preferences.")
        values.exclusions.forEach { id -> TextButton(onClick = { actions.edit { it.copy(exclusions = it.exclusions - id) } },
            enabled = editable, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove request exclusion · ${name(id)}") } }
        picker.searchResults.filter { it.id !in values.exclusions }.forEach { item -> TextButton(onClick = {
            actions.edit { it.copy(exclusions = (it.exclusions + item.id).distinct()) } }, enabled = editable,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("Exclude · ${item.name}") } }
    }
    FeedMeDetails("Original recipe") { CatalogRecipeReading(source.recipe, names, choices) }
}

@Composable private fun SavedRootProvenance(source: MealRootSource) {
    if (source is MealSavedRootSource) {
        InfoCard("From your Saved copy", source.savedRecipe.title +
            " · Saved version ${source.savedRecipe.version.jsonToken}. Your original copy stays in Saved; using a version never overwrites it.")
        Text("These are the retained recipe quantities. Current adaptation and new-copy permissions are checked separately; original photos and conversations are not included.",
            color = FeedMeColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun RootInput(label: String, value: String, enabled: Boolean, change: (String) -> Unit) {
    OutlinedTextField(value, change, enabled = enabled, singleLine = true, label = { Text(label) },
        shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp))
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun RootChoices(label: String, choices: List<MealInputChoice>, selected: List<String>, enabled: Boolean, toggle: (String) -> Unit) {
    Text(label, style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { option -> FilterChip(option.id in selected, { toggle(option.id) }, enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp), label = { Text(option.label) }) }
    }
    selected.filter { id -> choices.none { it.id == id } }.forEach { id ->
        TextButton(onClick = { toggle(id) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Remove retained choice · $id")
        }
    }
}
private fun rootToggle(values: List<String>, id: String) = if (id in values) values - id else values + id
@Composable private fun SourceRecipeHeader(recipe: RecipeVersionWire) {
    Surface(color = FeedMeColors.Lime, shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("YOUR STARTING POINT", style = MaterialTheme.typography.labelMedium)
            Text(recipe.title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
            Text(catalogEffort(recipe)); Text("${recipe.servings.jsonToken} servings")
        }
    }
}
@Composable private fun CatalogRecipeReading(recipe: RecipeVersionWire, names: Map<String, String>, choices: MealInputChoices) {
    fun ingredient(item: IngredientAmountWire) = "${item.quantity.jsonToken} ${item.unit} · " +
        (recipeIngredientLabel(item.ingredientId.value, names) ?: "Ingredient label unavailable (${item.ingredientId.value})") +
        (if (item.optional) " · optional" else "") + (item.preparation.valueOrNull()?.let { " · $it" } ?: "")
    Text("Ingredients", style = MaterialTheme.typography.titleMedium)
    recipe.ingredients.forEach { Text(ingredient(it)) }
    Text("Equipment: " + recipe.equipmentIds.joinToString { recipeEquipmentLabel(it, choices) })
    Text("Steps", style = MaterialTheme.typography.titleMedium)
    recipe.steps.forEach { step ->
        Surface(color = if (step.mandatorySafetyStep) FeedMeColors.Lilac else FeedMeColors.Surface, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Step ${step.position.jsonToken}" + if (step.mandatorySafetyStep) " · Required safety step" else "", style = MaterialTheme.typography.titleMedium)
                Text(step.instruction); RecipeStepMetadata(recipe, step, ::ingredient, choices)
            }
        }
    }
}
