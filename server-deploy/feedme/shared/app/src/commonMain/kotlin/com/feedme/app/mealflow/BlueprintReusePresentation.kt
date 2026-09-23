package com.feedme.app.mealflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feedme.app.FeedMeTheme
import com.feedme.app.blueprint.*
import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireField
import com.feedme.mealflow.MealFlowPhase
import com.feedme.mealflow.MealFlowScreen
import com.feedme.mealflow.memory.MealMemoryState
import com.feedme.mealflow.memory.MealReuseOption

/** Original RECOMMENDATIONS over actual canonical next-use options. An option supplies
 * no recipeId, recipe instructions, Plan, total time, pantry proof or cooking/save rights. */
@Composable
internal fun BlueprintReuseRecommendations(
    state: MealMemoryState,
    busy: Boolean,
    isCurrent: () -> Boolean,
    ingredientLabels: Map<String, String>,
    notice: String?,
    onOpen: ((MealReuseOption) -> Unit)?,
    onBackToInputs: () -> Unit,
) {
    val liveCurrent by rememberUpdatedState(isCurrent)
    var attached by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { attached = false } }
    fun current() = attached && liveCurrent() && isCurrent()
    var selectedId by remember(state.accountId, state.routeCookSessionId, state.routePlanId) { mutableStateOf<String?>(null) }
    var details by remember(state.accountId, state.routeCookSessionId, state.routePlanId) { mutableStateOf(false) }
    val options = state.reuseOptions
    val option = options.singleOrNull { it.recipeVersionId == selectedId } ?: options.firstOrNull()
    val shared = option?.let { reuseSharedIngredients(it, ingredientLabels) }.orEmpty()
    val page = BlueprintRecommendationsState(
        meal = option?.let {
            BlueprintMealCard(
                // Null means the provider did not supply this identity. No handler uses it
                // as a route; the real client must resolve the recipeId before getRecipeVersion.
                identity = BlueprintMealIdentity(recipeId = null, recipeVersionId = it.recipeVersionId),
                title = it.title,
                description = "${it.extraPreparationMinutes} extra preparation minutes" +
                    if (shared.isEmpty()) "" else "\nShared ingredients: ${shared.joinToString()}",
                badge = "Next-use option",
            )
        },
        matchReason = shared.takeIf { it.isNotEmpty() }?.let { "Returned shared ingredients: ${it.joinToString()}." },
        unknowns = listOf("Extra prep is not total cooking time. Open the full recipe for quantities and steps.",
            "Check that you still have the ingredients and have stored them safely."),
        controls = BlueprintDiscoveryControls(enabled = current(), loading = busy,
            contentUnavailable = !current(), allowedActions = buildSet {
                if (!busy) {
                    add(BlueprintDiscoveryAction.MATCH_EDIT)
                    if (option != null) {
                        add(BlueprintDiscoveryAction.MATCH_EXPLANATION)
                        if (onOpen != null) add(BlueprintDiscoveryAction.MATCH_RECIPE)
                        if (options.size > 1) add(BlueprintDiscoveryAction.MATCH_ALTERNATIVE)
                    }
                }
            }, message = listOfNotNull(notice, if (busy) "Opening the recipe. Your current meal stays as it is."
                else "${options.size} next-use idea${if (options.size == 1) "" else "s"}. Open one to see the full recipe.").joinToString("\n\n"),
            actionMessages = mapOf(BlueprintDiscoveryAction.MATCH_ADAPT to "Open the recipe first. This suggestion is not yet a meal plan.")),
    )
    BlueprintRecommendationsScreen(page, onAction = { action ->
        if (current() && page.controls.permits(BlueprintScreenId.RECOMMENDATIONS, action, option != null)) when (action) {
            BlueprintDiscoveryAction.MATCH_RECIPE -> option?.let { exact ->
                if (state.reuseOptions.any { it === exact }) onOpen?.invoke(exact)
            }
            BlueprintDiscoveryAction.MATCH_ALTERNATIVE -> option?.let { exact ->
                val index = options.indexOfFirst { it === exact }
                if (index >= 0 && options.size > 1) selectedId = options[(index + 1) % options.size].recipeVersionId
            }
            BlueprintDiscoveryAction.MATCH_EXPLANATION -> details = true
            BlueprintDiscoveryAction.MATCH_EDIT -> onBackToInputs()
            else -> Unit
        }
    }, onBack = { if (current()) onBackToInputs() },
        onMore = { if (current()) details = true }, onNavigate = {})
    if (details && current()) FeedMeTheme {
        AlertDialog(onDismissRequest = { details = false }, title = { Text("The returned options") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("These are only the options returned for your explicit next-use request. Browsing them changes no meal or pantry data.")
                options.forEach { candidate ->
                    TextButton(enabled = !busy, onClick = {
                        if (current() && state.reuseOptions.any { it === candidate }) {
                            selectedId = candidate.recipeVersionId; details = false
                        }
                    }) { Text(candidate.title) }
                    Text("${candidate.extraPreparationMinutes} extra preparation minutes")
                    val ingredients = reuseSharedIngredients(candidate, ingredientLabels)
                    if (ingredients.isNotEmpty()) Text("Shared: ${ingredients.joinToString()}")
                }
                if (state.hasMore) Text("The server indicated additional options. No unreturned option has been added to this view.")
                Text("Opening one resolves its real recipe identity and reads the recipe separately. No full recipe or permission is inferred from these short suggestions.")
            }
        }, confirmButton = { TextButton(onClick = { details = false }) { Text("Done") } })
    }
}

/** RECIPE for an actual controller-issued catalog source, including an explicitly opened
 * reuse option. It deliberately cannot call Save or Cook before the ordinary Plan flow. */
@Composable
internal fun BlueprintOwnedCatalogRecipeScreen(
    meal: MealScreenState,
    picker: MealPickerPresentation,
    form: MealFormState,
    choices: MealInputChoices,
    actions: MealScreenActions,
): Boolean {
    if (meal.screen !in setOf(MealFlowScreen.CATALOG_RECIPE, MealFlowScreen.POST_RECIPE)) return false
    val source = meal.rootSource ?: return false
    val recipe = source.recipe
    val catalog = actions.catalog ?: return false
    val status = mealStatusPresentation(meal, form, null)
    if (status.sourceUnavailable || status.originalRequestRetained || recipe.reviewStatus != "published") return false
    if (recipe.steps.any { step -> step.ingredientIds.any { id ->
        recipe.ingredients.count { it.ingredientId == id } != 1
    } }) return false
    val currentHost by rememberUpdatedState(actions.blueprintCurrent)
    var attached by remember(source) { mutableStateOf(true) }
    DisposableEffect(source) { onDispose { attached = false } }
    fun current() = attached && currentHost() && actions.blueprintCurrent()
    var details by remember(source) { mutableStateOf(false) }
    val labels = picker.knownIngredients.associate { it.id to it.name }
    fun ingredientName(id: String) = labels[id] ?: "Ingredient label unavailable ($id)"
    val idle = !form.busy && meal.phase !in setOf(MealFlowPhase.LOADING, MealFlowPhase.RESOLVING)
    val mayPlan = idle && !form.dirty && meal.pendingRootDraft == null && meal.rootProposal == null && meal.rootMakeMineAvailable
    val mayRefresh = idle && !form.dirty && meal.pendingRootDraft == null && meal.rootProposal == null
    val card = BlueprintMealCard(BlueprintMealIdentity(recipe.recipeId.value, recipe.id.value), recipe.title,
        description = listOf(recipe.summary.valueOrNull().orEmpty(), catalogEffort(recipe), "${recipe.servings.jsonToken} servings")
            .filter { it.isNotBlank() }.joinToString("\n"),
        metrics = BlueprintMealMetrics(
            totalMinutes = reuseExactInt(recipe.totalMinutes.jsonToken),
            activeMinutes = reuseExactInt(recipe.activeMinutes.jsonToken),
            cleanup = recipe.cleanupMinutes.valueOrNull()?.jsonToken?.let { "$it min" },
            servings = reuseExactInt(recipe.servings.jsonToken),
        ), badge = if (source.historical) "Retained recipe reference" else "Recipe reference")
    val detail = BlueprintRecipeDetail(card,
        ingredients = recipe.ingredients.map { ingredient -> BlueprintRecipeIngredient(
            ingredientName(ingredient.ingredientId.value) + (if (ingredient.optional) " · optional" else "") +
                ingredient.preparation.valueOrNull()?.let { " · $it" }.orEmpty(),
            "${ingredient.quantity.jsonToken} ${ingredient.unit}", availability = null) },
        steps = recipe.steps.map { step -> BlueprintRecipeStep(
            "Step ${step.position.jsonToken}" + if (step.mandatorySafetyStep) " · Required safety step" else "",
            buildList {
                add(step.instruction)
                recipeStepIngredients(recipe, step).forEach { ingredient ->
                    add("Ingredient: ${ingredientName(ingredient.ingredientId.value)} · ${ingredient.quantity.jsonToken} ${ingredient.unit}")
                }
                step.durationSeconds.valueOrNull()?.let { add("Suggested duration: ${it.jsonToken} seconds · timers are separate") }
                if (step.requiredEquipmentIds.isNotEmpty()) add("Uses: " + step.requiredEquipmentIds.joinToString { recipeEquipmentLabel(it, choices) })
            }.joinToString("\n")) },
        provenance = BlueprintRecipeProvenance(if (meal.postSource != null) "Shared recipe reference" else "Catalog recipe reference",
            "Recorded review status: ${recipe.reviewStatus}" + recipe.reviewerLabel.valueOrNull()?.let { " · Recorded reviewer: $it" }.orEmpty(),
            "Recipe revision ${recipe.version.jsonToken}",
            recipe.contentLicense.valueOrNull()?.let { "Recorded license: $it" }),
        equipment = recipe.equipmentIds.map { recipeEquipmentLabel(it, choices) },
        safetyNotes = buildList {
            add("This is a recipe reference, not a selected cooking plan. It does not establish current pantry availability or freshness.")
            if (source.historical) add("This retained observation is not a fresh recall, rights or availability check.")
            recipe.waitingMinutes.valueOrNull()?.let { add("Waiting: ${it.jsonToken} minutes") }
            recipe.estimateNote.valueOrNull()?.let { add("Estimate notes: $it") }
        })
    val page = BlueprintRecipeState(detail, controls = BlueprintDiscoveryControls(enabled = current(), loading = !idle,
        allowedActions = if (mayPlan) setOf(BlueprintDiscoveryAction.RECIPE_ADAPT) else emptySet(),
        message = buildList {
            add("Your current meal is unchanged. Use Make Mine to check ingredients and limits before saving or cooking.")
            catalog.sourceReviewNotice?.let(::add)
            if (!meal.rootMakeMineAvailable) add("Make Mine isn't enabled in this configuration.")
            if (form.dirty) add("Your meal draft has unsaved edits. Finish those edits before requesting a version.")
            if (meal.rootProposal != null) add("A previous comparison is retained. Review it in More before requesting another.")
            status.notices.forEach { add("${it.title}: ${it.message}") }
        }.joinToString("\n\n"),
        actionMessages = mapOf(BlueprintDiscoveryAction.RECIPE_COOK to "Choose a valid meal plan before starting to cook.",
            BlueprintDiscoveryAction.RECIPE_SAVE to "Saving requires a separately authorized selected plan; no save has been made here.")))
    BlueprintRecipeScreen(page, onAction = { action ->
        if (current() && page.controls.permits(BlueprintScreenId.RECIPE, action, true) && action == BlueprintDiscoveryAction.RECIPE_ADAPT)
            catalog.makeMine()
    }, onBack = { if (current()) actions.back() }, onMore = { if (current()) details = true }, onNavigate = {})
    if (details && current()) FeedMeTheme {
        AlertDialog(onDismissRequest = { details = false }, title = { Text("Recipe reference") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(card.description)
                Text("Opening a recipe is not starting a cook, making a saved copy or granting redistribution rights.")
                status.notices.forEach { Text("${it.title}: ${it.message}") }
                if (meal.rootProposal != null) TextButton(enabled = idle, onClick = {
                    if (current()) { details = false; catalog.reopen() }
                }) { Text("Review retained comparison") }
                TextButton(enabled = mayRefresh, onClick = {
                    if (current()) { details = false; catalog.refresh() }
                }) { Text("Check this recipe again") }
            }
        }, confirmButton = { TextButton(onClick = { details = false }) { Text("Done") } })
    }
    return true
}

private fun reuseSharedIngredients(option: MealReuseOption, labels: Map<String, String>): List<String> =
    ((option.document.field("sharedIngredientIds") as? WireField.Value)?.value?.elementsOrNull()).orEmpty()
        .mapNotNull(WireDocument::stringOrNull).map { labels[it] ?: "Ingredient $it" }

/** The original metric row accepts integers only; exact noninteger text remains above. */
private fun reuseExactInt(token: String): Int? = token.toIntOrNull()?.takeIf { it.toString() == token }
