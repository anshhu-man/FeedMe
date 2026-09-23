package com.feedme.app.mealflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feedme.app.blueprint.*
import com.feedme.mealflow.*

/** Presentation of an already-issued plan, never a recipe parser, selector or capability. */
internal class BlueprintMealFlowPresentation(
    val original: MealPlanPresentation,
    val recommendations: BlueprintRecommendationsState,
    val recipe: BlueprintRecipeState,
    val status: MealStatusPresentation,
    val retryEnabled: Boolean,
) {
    override fun toString() = "BlueprintMealFlowPresentation(<redacted>)"
}

private const val COOKING_PREVIEW = "Start cooking checks availability before you confirm. Saving a new copy has its own permission check; cooking from Saved doesn’t require another save. Nothing records repeat-preference feedback or shares a post."
private const val HISTORICAL_PLAN = "This retained plan does not prove current recipe rights, recall status, ingredient availability or permission to cook/save."

/** The blueprint metrics use Int. Fall back to the existing exact-token reader rather than
 * normalising decimals, fractional servings, exponents or large numbers to a different value. */
private fun exactBlueprintInt(token: String): Int? = token.toIntOrNull()?.takeIf { it.toString() == token }

internal fun blueprintMealFlowPresentation(meal: MealScreenState, picker: MealPickerPresentation,
    form: MealFormState, choices: MealInputChoices, actions: MealScreenActions): BlueprintMealFlowPresentation? {
    if (meal.screen !in setOf(MealFlowScreen.RECOMMENDATIONS, MealFlowScreen.RECIPE) ||
        form.values == null || meal.phase == MealFlowPhase.UNAVAILABLE || meal.issue == MealFlowIssue.SESSION_UNAVAILABLE) return null
    val plan = meal.plan ?: return null
    val view = MealPlanPresentation(plan, meal.historical, picker.knownIngredients.associate { it.id to it.name })
    if (!view.recipeVisible) return null
    val recipe = view.recipe ?: return null
    val metrics = BlueprintMealMetrics(
        totalMinutes = exactBlueprintInt(recipe.totalMinutes.jsonToken) ?: return null,
        activeMinutes = exactBlueprintInt(recipe.activeMinutes.jsonToken) ?: return null,
        cleanup = recipe.cleanupMinutes.valueOrNull()?.jsonToken?.let { "$it min" } ?: "unknown",
        servings = exactBlueprintInt(recipe.servings.jsonToken) ?: return null,
    )
    val status = mealStatusPresentation(meal, form, null)
    val message = buildList {
        if (meal.historical) add("Retained plan · historical. $HISTORICAL_PLAN")
        if (form.dirty) add("Unsaved edits: these are not the request already sent to the server. Save a valid draft to keep them on this device.")
        status.notices.forEach { add("${it.title}: ${it.message}") }
        if (status.originalRequestRetained) add(originalRequestCopy(meal))
        ingredientLookupMessage(mealLabelIds(meal), picker)?.let(::add)
    }.joinToString("\n\n")
    val freshAction = !form.busy && !form.dirty && meal.phase !in setOf(MealFlowPhase.LOADING, MealFlowPhase.RESOLVING)
    val allowed = buildSet {
        if (freshAction) {
            add(BlueprintDiscoveryAction.MATCH_RECIPE)
            add(BlueprintDiscoveryAction.MATCH_EDIT)
            if (meal.alternativesAvailable) add(BlueprintDiscoveryAction.MATCH_ALTERNATIVE)
            if (meal.simplificationAvailable && actions.easier != null) add(BlueprintDiscoveryAction.MATCH_SIMPLIFY)
            if (meal.adaptationAvailable && actions.adaptation != null) {
                add(BlueprintDiscoveryAction.MATCH_ADAPT); add(BlueprintDiscoveryAction.RECIPE_ADAPT)
                if (actions.adaptation.missingIngredient != null) add(BlueprintDiscoveryAction.RECIPE_MISSING_INGREDIENT)
            }
            if (actions.cook != null) add(BlueprintDiscoveryAction.RECIPE_COOK)
            if (actions.save != null) add(BlueprintDiscoveryAction.RECIPE_SAVE)
            if (meal.phase == MealFlowPhase.READY && actions.share != null) add(BlueprintDiscoveryAction.RECIPE_SHARE)
        }
        if (!form.busy && view.reasons.isNotEmpty()) add(BlueprintDiscoveryAction.MATCH_EXPLANATION)
    }
    val controls = BlueprintDiscoveryControls(loading = form.busy || meal.phase == MealFlowPhase.LOADING,
        allowedActions = allowed, message = message.takeIf(String::isNotBlank),
        allowedNavigation = blueprintMealTabDestinations(form, actions))
    val card = BlueprintMealCard(
        identity = BlueprintMealIdentity(recipe.recipeId.value, recipe.id.value, planId = plan.id.value),
        title = recipe.title, description = recipe.summary.valueOrNull().orEmpty(), metrics = metrics,
        // No verified image/recipe association exists in this flow. Keep the illustration label.
        badge = if (meal.historical) "Retained plan" else "Plan snapshot",
    )
    val missing = plan.missingIngredients.map(view::ingredientLine)
    val detail = BlueprintRecipeDetail(card,
        ingredients = recipe.ingredients.map { ingredient ->
            BlueprintRecipeIngredient(
                name = (view.ingredientName(ingredient.ingredientId.value)
                    ?: "Ingredient label unavailable (${ingredient.ingredientId.value})") +
                    (if (ingredient.optional) " · optional" else "") +
                    ingredient.preparation.valueOrNull()?.let { " · $it" }.orEmpty(),
                amount = "${ingredient.quantity.jsonToken} ${ingredient.unit}",
                // A selected ingredient is not proof of current pantry availability.
                availability = null,
            )
        },
        steps = recipe.steps.map { step ->
            BlueprintRecipeStep("Step ${step.position.jsonToken}" + if (step.mandatorySafetyStep) " · Required safety step" else "",
                buildList {
                    add(step.instruction)
                    recipeStepIngredients(recipe, step).forEach { add("Ingredient: ${view.ingredientLine(it)}") }
                    step.durationSeconds.valueOrNull()?.let { add("Suggested duration: ${it.jsonToken} seconds · manage timers separately") }
                    if (step.requiredEquipmentIds.isNotEmpty()) add("Uses: " + step.requiredEquipmentIds.joinToString { recipeEquipmentLabel(it, choices) })
                }.joinToString("\n"))
        },
        provenance = BlueprintRecipeProvenance(
            source = "Plan snapshot · catalog ${plan.catalogRevision}",
            review = "Recorded review status: ${recipe.reviewStatus}" +
                recipe.reviewerLabel.valueOrNull()?.let { " · Recorded reviewer: $it" }.orEmpty(),
            version = "Plan revision ${plan.version.jsonToken} · recipe revision ${recipe.version.jsonToken}",
            licenseSummary = recipe.contentLicense.valueOrNull()?.let { "Recorded license: $it" },
        ),
        equipment = recipe.equipmentIds.map { recipeEquipmentLabel(it, choices) },
        safetyNotes = buildList {
            if (missing.isNotEmpty()) add("Missing items:\n${missing.joinToString("\n")}")
            if (view.unresolvedIngredientCount > 0) add("Unknown ingredient names are shown explicitly. Do not substitute a guess.")
            recipe.waitingMinutes.valueOrNull()?.let { add("Waiting: ${it.jsonToken} min") }
            recipe.cleanupMinutes.valueOrNull()?.let { add("Cleanup: ${it.jsonToken} min") }
            recipe.estimateNote.valueOrNull()?.let { add("Estimate notes: $it") }
            if (view.reasons.isNotEmpty()) add("Why this fits:\n${view.reasons.joinToString("\n")}")
            if (view.changes.isNotEmpty()) add("What changed:\n${view.changes.joinToString("\n")}")
        })
    return BlueprintMealFlowPresentation(view,
        BlueprintRecommendationsState(card, view.reasons.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            unknowns = buildList {
                if (missing.isNotEmpty()) add("Missing items:\n${missing.joinToString("\n")}")
                if (view.unresolvedIngredientCount > 0) add("Unknown ingredient names are shown explicitly. Do not substitute a guess.")
            }, controls = controls),
        BlueprintRecipeState(detail, controls = controls), status,
        retryEnabled = status.originalRequestRetained && !form.busy &&
            (meal.pendingAdaptation == null || actions.adaptation != null) &&
            meal.issue !in setOf(MealFlowIssue.REPLAY_EXPIRED, MealFlowIssue.CONTEXT_CHANGED, MealFlowIssue.PREFERENCES_PENDING))
}

private fun originalRequestCopy(meal: MealScreenState): String = "Original request retained. " +
    if (meal.pendingRootDraft != null) "Your sent Make Mine inputs are retained separately. The current meal is unchanged."
    else if (meal.pendingMatchesDraft) "The retained command matches your last saved draft."
    else "Your edited draft does not replace the earlier unresolved command."

/** A closed action, another screen's action, or a detached render never borrows current state. */
internal fun dispatchBlueprintMealAction(screen: MealFlowScreen, projection: BlueprintMealFlowPresentation,
    action: BlueprintDiscoveryAction, actions: MealScreenActions, explanation: () -> Unit) {
    if (!actions.blueprintCurrent()) return
    val id = if (screen == MealFlowScreen.RECIPE) BlueprintScreenId.RECIPE else if (screen == MealFlowScreen.RECOMMENDATIONS)
        BlueprintScreenId.RECOMMENDATIONS else return
    val controls = if (screen == MealFlowScreen.RECIPE) projection.recipe.controls else projection.recommendations.controls
    if (!controls.permits(id, action, true)) return
    when (action) {
        BlueprintDiscoveryAction.MATCH_RECIPE -> actions.recipe()
        BlueprintDiscoveryAction.MATCH_EDIT -> actions.back()
        BlueprintDiscoveryAction.MATCH_ALTERNATIVE -> actions.alternative()
        BlueprintDiscoveryAction.MATCH_SIMPLIFY -> actions.easier?.invoke()
        BlueprintDiscoveryAction.MATCH_ADAPT, BlueprintDiscoveryAction.RECIPE_ADAPT -> actions.adaptation?.open?.invoke()
        BlueprintDiscoveryAction.RECIPE_MISSING_INGREDIENT -> actions.adaptation?.missingIngredient?.invoke()
        BlueprintDiscoveryAction.MATCH_EXPLANATION -> explanation()
        BlueprintDiscoveryAction.RECIPE_COOK -> actions.cook?.invoke()
        BlueprintDiscoveryAction.RECIPE_SAVE -> actions.save?.invoke()
        BlueprintDiscoveryAction.RECIPE_SHARE -> actions.share?.invoke()
        else -> Unit
    }
}

internal fun navigateBlueprintMeal(projection: BlueprintMealFlowPresentation, destination: BlueprintScreenId,
    actions: MealScreenActions) {
    if (projection.recipe.controls.permitsNavigation(destination))
        dispatchBlueprintMealTab(destination, projection.recipe.controls.allowedNavigation, actions)
}

/** Full original layouts plus reachable exact-plan recovery, without nesting their scroll view. */
@Composable
internal fun BlueprintIssuedMealScreen(meal: MealScreenState, picker: MealPickerPresentation, form: MealFormState,
    choices: MealInputChoices, actions: MealScreenActions, projection: BlueprintMealFlowPresentation) {
    var details by remember(meal.screen, meal.plan?.id?.value) { mutableStateOf(false) }
    val openDetails = { if (actions.blueprintCurrent()) details = true }
    val run: (() -> Unit) -> Unit = { action -> if (actions.blueprintCurrent()) action() }
    val retry = { run { if (meal.pendingAdaptation != null) actions.adaptation?.retry?.invoke() else actions.retry() } }
    Column(Modifier.fillMaxSize()) {
        val dispatch: (BlueprintDiscoveryAction) -> Unit = { dispatchBlueprintMealAction(meal.screen, projection, it, actions, openDetails) }
        val navigate: (BlueprintScreenId) -> Unit = { navigateBlueprintMeal(projection, it, actions) }
        if (meal.screen == MealFlowScreen.RECIPE) BlueprintRecipeScreen(projection.recipe, dispatch,
            { run(actions.back) }, openDetails, navigate, Modifier.weight(1f))
        else BlueprintRecommendationsScreen(projection.recommendations, dispatch,
            { run(actions.back) }, openDetails, navigate, Modifier.weight(1f))
        if (projection.status.originalRequestRetained) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 4.dp)) {
                meal.retryAtMillis?.let { Text("Earliest retry: ${kotlin.time.Instant.fromEpochMilliseconds(it)}", style = MaterialTheme.typography.bodySmall) }
                Button(onClick = retry, enabled = projection.retryEnabled, modifier = Modifier.fillMaxWidth()) { Text("Retry original request") }
            }
        }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("Plan details & recovery") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                projection.status.notices.forEach { InfoCard(it.title, it.message) }
                if (projection.status.originalRequestRetained) {
                    Text(originalRequestCopy(meal))
                    Button(onClick = retry, enabled = projection.retryEnabled) { Text("Retry original request") }
                }
                IngredientNameLookup(mealLabelIds(meal), picker, form.busy, actions.loadIngredientNames?.let { { run(it) } })
                PlanCard(projection.original, full = false, choices = choices)
                Text(COOKING_PREVIEW)
                if (meal.screen == MealFlowScreen.RECOMMENDATIONS) TextButton(onClick = { run(actions.previous) },
                    enabled = !form.busy && meal.previousAvailable) { Text("Previous option") }
                if (meal.screen == MealFlowScreen.RECIPE && meal.simplificationAvailable) actions.easier?.let {
                    TextButton(onClick = { run(it) }, enabled = !form.busy && !form.dirty) { Text("Make it easier") }
                }
                if (meal.adaptation != null) actions.adaptation?.let {
                    TextButton(onClick = { run(it.reopen) }, enabled = !form.busy) { Text("Review my saved version") }
                }
                if (meal.proposal != null) actions.reopenEasier?.let {
                    TextButton(onClick = { run(it) }, enabled = !form.busy) { Text("Review saved proposal") }
                }
                actions.reviewPlanSource?.let { review ->
                    TextButton(onClick = { run(review) }, enabled = !form.busy && !form.dirty) {
                        Text("Review source for a new version")
                    }
                    Text("Recheck the original recipe before making another version. Your current plan stays selected.")
                }
                actions.catalog?.let {
                    TextButton(onClick = { run(it.open) }, enabled = !form.busy && !form.dirty) {
                        Text(if (meal.savedSource != null) "Return to Saved · Make Mine" else if (meal.catalogSource != null || meal.rootProposal != null)
                            "Return to Browse & Make Mine" else "Browse recipes · Make Mine")
                    }
                }
                listOf("View retained cooking" to actions.retainedCooking, "Saved" to actions.cookbook,
                    "My private drafts" to actions.drafts, "Open my circles" to actions.circles, "Resume report" to actions.reports)
                    .forEach { (label, action) -> if (action != null) TextButton(onClick = { run(action) }, enabled = !form.busy) { Text(label) } }
            }
        }, confirmButton = { TextButton(onClick = { details = false }) { Text("Close") } })
}
