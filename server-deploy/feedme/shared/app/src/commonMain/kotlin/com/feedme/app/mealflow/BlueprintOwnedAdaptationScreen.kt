package com.feedme.app.mealflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.feedme.app.FeedMeTheme
import com.feedme.app.blueprint.*
import com.feedme.mealflow.*

/** Local child lifetime only; changing saved preferences never changes this adaptation. */
private class BlueprintAdaptationPreferenceVisit(val host: MealPreferenceEditorHost,
    private val form: MealFormState, private val adaptation: AdaptationFormState) {
    private var active = true
    fun current(observedForm: MealFormState, observedAdaptation: AdaptationFormState?,
        eligible: Boolean, hostCurrent: Boolean): Boolean {
        if (observedForm !== form || observedAdaptation !== adaptation || !eligible || !hostCurrent) active = false
        return active
    }
    fun retire() { active = false }
    override fun toString() = "BlueprintAdaptationPreferenceVisit(<redacted>)"
}

/** Exact RAM-only consent to stage a request draft. The captured port retains the real
 * owner/parent admission and acknowledgment; this review cannot clear sources or send. */
private class BlueprintAdaptationNewMealReview(val action: () -> Unit,
    private val form: MealFormState, private val adaptation: AdaptationFormState,
    private val originCurrent: () -> Boolean) {
    val values = checkNotNull(adaptation.values)
    private var active = true
    fun current(observedForm: MealFormState, observedAdaptation: AdaptationFormState?,
        eligible: Boolean, hostCurrent: Boolean): Boolean {
        if (observedForm !== form || observedAdaptation !== adaptation || !eligible || !hostCurrent || !originCurrent())
            active = false
        return active
    }
    fun retire() { active = false }
    override fun toString() = "BlueprintAdaptationNewMealReview(<redacted>)"
}

/** Original ADAPT/VARIANT layouts, projected only from this rendered owner's snapshots.
 * No request/ID/read on composition. The existing ports keep exact proposal and request
 * ownership; display identities never become an alternative route or command authority. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BlueprintOwnedAdaptationScreen(meal: MealScreenState, picker: MealPickerPresentation,
    form: MealFormState, choices: MealInputChoices, actions: MealScreenActions, adaptation: AdaptationFormState?) {
    val mine = meal.screen in setOf(MealFlowScreen.ADAPT_MINE, MealFlowScreen.VARIANT_MINE)
    val requestPage = meal.screen in setOf(MealFlowScreen.ADAPT, MealFlowScreen.ADAPT_MINE)
    val unavailable = form.values == null || meal.phase == MealFlowPhase.UNAVAILABLE || meal.issue == MealFlowIssue.SESSION_UNAVAILABLE
    val status = mealStatusPresentation(meal, form, null)
    val pending = status.originalRequestRetained || meal.pendingAdaptation != null
    val adaptationInterpretation = actions.adaptationInterpretation
    val interpretationActive = mine && (adaptationInterpretation?.busy == true || adaptationInterpretation?.proposal != null)
    val idle = !form.busy && meal.phase !in setOf(MealFlowPhase.LOADING, MealFlowPhase.RESOLVING)
    val editable = !unavailable && idle && !pending && !interpretationActive
    val labels = picker.knownIngredients.associate { it.id to it.name }
    val originalPlan = if (mine) adaptation?.parent ?: meal.adaptation?.parent else meal.proposal?.parent
    val original = if (unavailable) null else (originalPlan?.let { MealPlanPresentation(it.plan, it.historical, labels) }
        ?: meal.plan?.let { MealPlanPresentation(it, meal.historical, labels) })
    val detail = original?.let { ownedBlueprintRecipe(it, choices) }
    val currentHost by rememberUpdatedState(actions.blueprintCurrent)
    var attached by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { attached = false } }
    var refinement by remember { mutableStateOf<BlueprintAdaptationRefinementVisit?>(null) }
    var ingredientVisit by remember { mutableStateOf<BlueprintAdaptationIngredientsVisit?>(null) }
    var preferenceVisit by remember { mutableStateOf<BlueprintAdaptationPreferenceVisit?>(null) }
    var newMealReview by remember { mutableStateOf<BlueprintAdaptationNewMealReview?>(null) }
    var interaction by remember { mutableStateOf(Any()) }
    val renderedInteraction = interaction
    fun originCurrent() = attached && currentHost() && actions.blueprintCurrent()
    fun current() = refinement == null && ingredientVisit == null && preferenceVisit == null && newMealReview == null &&
        interaction === renderedInteraction && originCurrent()
    var details by remember(meal.screen, meal.plan?.id?.value, meal.adaptation, meal.proposal) { mutableStateOf<String?>(null) }
    var goal by remember(meal.screen, meal.plan?.id?.value, meal.plan?.version?.jsonToken) { mutableStateOf(SimplificationGoal.OVERALL) }
    var differentMeal by remember(meal.screen, meal.plan?.id?.value, meal.plan?.version?.jsonToken) { mutableStateOf(false) }
    val values = adaptation?.values
    if (requestPage && mine && values != null && adaptationInterpretation?.proposal != null) {
        BlueprintAdaptationInterpretationReview(adaptationInterpretation, values, actions)
        return
    }
    val refinementAvailable = editable && blueprintAdaptationRefinementAvailable(meal, form, adaptation) &&
        actions.adaptation != null
    fun preferenceCurrent(expected: BlueprintAdaptationPreferenceVisit) =
        attached && preferenceVisit === expected && refinement == null && ingredientVisit == null && details == null &&
            expected.current(form, adaptation, blueprintAdaptationRefinementAvailable(meal, form, adaptation),
                originCurrent() && expected.host.current())
    fun openPreferences(provider: MealPreferenceEditorHost?) {
        val source = adaptation ?: return
        if (provider == null || !current() || !refinementAvailable || details != null || !provider.current()) return
        val next = BlueprintAdaptationPreferenceVisit(provider, form, source)
        interaction = Any(); preferenceVisit = next
        provider.open { preferenceCurrent(next) }
    }
    fun openRefinement(page: BlueprintAdaptationRefinementPage) {
        val source = adaptation ?: return
        if (!current() || !refinementAvailable || details != null) return
        interaction = Any()
        refinement = BlueprintAdaptationRefinementVisit(page, source, form, ::originCurrent)
    }
    fun openIngredients() {
        val source = adaptation ?: return
        val host = actions.adaptation?.ingredients ?: return
        if (!current() || !refinementAvailable || details != null || !host.current()) return
        interaction = Any()
        ingredientVisit = BlueprintAdaptationIngredientsVisit(host, form, source)
    }
    fun reviewNewMeal() {
        val source = adaptation ?: return
        val port = actions.adaptation?.planNewMeal ?: return
        if (!current() || !refinementAvailable || details == null) return
        val review = BlueprintAdaptationNewMealReview(port, form, source, ::originCurrent)
        details = null; interaction = Any(); newMealReview = review
    }
    // Full-detail callback objects can outlive their sheet. They must not act through a
    // newer refinement overlay, even before Compose has rendered its disabled underlay.
    val guardedAdaptation = actions.adaptation?.let { owner -> AdaptationScreenActions(
        open = { if (current()) owner.open() }, edit = { change -> if (current()) owner.edit(change) },
        searchText = { text -> if (current()) owner.searchText(text) }, search = { if (current()) owner.search() },
        moreIngredients = { if (current()) owner.moreIngredients() }, request = {
            if (current() && adaptationInterpretation?.text.isNullOrBlank() &&
                adaptation?.values?.meal?.totalMinutes?.let(::adaptationRequiredTotalMinutesValid) == true)
                owner.request()
        },
        reopen = { if (current()) owner.reopen() }, keepOriginal = { if (current()) owner.keepOriginal() },
        accept = { if (current()) owner.accept() }, editRequest = { if (current()) owner.editRequest() },
        viewOriginal = { if (current()) owner.viewOriginal() }, retry = { if (current()) owner.retry() },
        missingIngredient = owner.missingIngredient?.let { callback -> { if (current()) callback() } },
    ) }
    val requestMinutes = if (mine) values?.meal?.totalMinutes else form.values?.totalMinutes
    val requestTimeValid = requestMinutes?.let(::adaptationRequiredTotalMinutesValid) == true
    val interpretationText = if (mine) adaptationInterpretation?.text.orEmpty() else ""
    val hasInterpretationText = interpretationText.isNotBlank()
    val canInterpret = mine && editable && !form.dirty && detail != null && values != null &&
        adaptation?.visible == true && hasInterpretationText && actions.interpretAdaptation != null &&
        actions.adaptationInterpretationCurrent()
    val canRequest = editable && !form.dirty && detail != null && requestTimeValid && !hasInterpretationText && (if (mine)
        adaptation?.visible == true && values != null && actions.adaptation != null &&
            (values.reason != MealAdaptationReason.MISSING_INGREDIENT || values.replaceIngredientId != null)
        else actions.requestEasier != null)
    val message = buildList {
        if (unavailable) add("This meal is unavailable. Use Back to leave.")
        else {
            status.notices.forEach { add("${it.title}: ${it.message}") }
            adaptation?.failure?.let { add(mealFailureText(it)) }
            if (pending) add("Your original request is retained. More options has its exact retry; changing inputs does not replace it.")
            if (form.dirty) add("Your main meal draft has unsaved changes. Finish that draft before requesting another version.")
            if (requestPage && mine && (actions.foodPreferences != null || actions.equipmentPreferences != null))
                add("Food preferences and Change equipment open saved kitchen settings. They do not change this version’s explicit inputs or recheck its parent meal; changed settings may require a new meal request.")
            if (requestPage) add(if (mine && actions.adaptationInterpretationText != null)
                "Describe a change or use the choices below. Typed changes are reviewed before they can update this unsent form; they never request a version automatically. Time is required as a positive whole number before the final request."
                else if (mine) "Text matching is not configured here. Use the choices below to edit your version. Time is required as a positive whole number."
                else "Choose what should get easier under My energy. Existing ingredient, equipment and taste limits stay fixed. Return to your meal request to edit them. Time is required as a positive whole number.")
            if (requestPage && mine && adaptationInterpretation?.busy == true)
                add("Checking your words. Nothing has been applied or sent.")
            adaptationInterpretation?.failure?.let { if (mine) add(mealInterpretationFailureText(it)) }
            if (requestPage && !requestTimeValid && !hasInterpretationText)
                add("Enter Time available before Make my version can be requested. Nothing has been sent.")
            else if (requestPage && hasInterpretationText)
                add("Choose Make my version to review the suggested ingredient, energy and time changes first. Confirming that review only updates this form.")
            else {
                add("Your original stays selected until you choose this version. Cooking, saving and sharing remain separate.")
                if (mine) meal.adaptation?.let { add(AdaptationPresentation.from(it, labels, choices).statusMessage) }
                else meal.proposal?.child?.plan?.status?.let { state -> when (state) {
                    "noMatch" -> add("No supported easier option was returned. More options keeps your original and full response available.")
                    "needsConfirmation" -> add("Check the response and missing ingredients under More. No replacement is selected.")
                    else -> Unit
                } }
            }
        }
    }.joinToString("\n\n")
    val controls = BlueprintDiscoveryControls(enabled = current(),
        loading = form.busy || meal.phase == MealFlowPhase.LOADING || adaptationInterpretation?.busy == true,
        contentUnavailable = unavailable, allowedActions = buildSet {
            if (requestPage && editable) {
                if (canInterpret || canRequest) add(BlueprintDiscoveryAction.ADAPT_CREATE)
                if (detail != null) add(BlueprintDiscoveryAction.ADAPT_KEEP_ORIGINAL)
                if (!mine || refinementAvailable) add(BlueprintDiscoveryAction.ADAPT_EFFORT)
                if (mine && adaptation?.visible == true && actions.adaptation != null) {
                    if (refinementAvailable && actions.adaptation.ingredients?.current?.invoke() == true)
                        add(BlueprintDiscoveryAction.ADAPT_INGREDIENTS)
                    if (refinementAvailable) add(BlueprintDiscoveryAction.ADAPT_TASTE)
                    if (refinementAvailable && actions.foodPreferences?.current?.invoke() == true)
                        add(BlueprintDiscoveryAction.ADAPT_PREFERENCES)
                    if (refinementAvailable && actions.equipmentPreferences?.current?.invoke() == true)
                        add(BlueprintDiscoveryAction.ADAPT_EQUIPMENT)
                }
            }
        }, message = message.takeIf { it.isNotBlank() }, allowMore = !unavailable)
    val openDetails: (String) -> Unit = { title -> if (current() && !unavailable) details = title }
    val back = { if (current()) actions.back() }
    if (requestPage) {
        val summary = if (unavailable) "" else if (mine && values != null) buildString {
            append(if (values.reason == MealAdaptationReason.MISSING_INGREDIENT) "Replace a missing ingredient" else "Make it mine")
            append(" · ${values.meal.servings} servings · ")
            append(when (values.meal.energy) {
                MealEnergy.ASSEMBLE -> "Barely any energy"
                MealEnergy.LITTLE -> "A little effort"
                MealEnergy.HAPPY -> "Happy to cook"
            })
            append("\n${values.meal.ingredientIds.size} available ingredients · ${values.meal.equipmentIds.size} equipment choices · ${values.meal.exclusions.size} version exclusions\n")
            append(mealRefinementSummary(values.meal, choices))
            values.replaceIngredientId?.let { id -> append("\nMissing: ${original?.ingredientName(id) ?: "Ingredient label unavailable ($id)"}") }
            values.requestedReplacementId?.let { id -> append("\nRequested instead: ${labels[id] ?: "Ingredient label unavailable ($id)"}") }
            values.retainTasteTag?.let { tag -> append("\nKeep: ${choices.tastes.firstOrNull { it.id == tag }?.label ?: tag}") }
        } else "${simplificationGoalLabel(goal)}" + if (differentMeal) " · a different meal is okay" else " · keep the same meal"
        val page = BlueprintAdaptState(detail?.card, if (mine) interpretationText else summary,
            if (unavailable) "" else if (mine) values?.meal?.totalMinutes.orEmpty() else form.values?.totalMinutes.orEmpty(), controls,
            changeEditable = mine && editable && adaptation?.visible == true &&
                actions.adaptationInterpretationText != null && actions.adaptationInterpretationCurrent(),
            minutesEditable = mine && editable && adaptation?.visible == true,
            structuredRequestReady = canInterpret || canRequest)
        BlueprintAdaptScreen(page, onChange = { text ->
            if (current() && page.changeEditable) actions.adaptationInterpretationText?.invoke(text)
        }, onMinutesChange = { text ->
            if (current() && page.minutesEditable) actions.adaptation?.edit?.invoke { it.copy(meal = it.meal.copy(totalMinutes = text)) }
        }, onAction = { action ->
            if (current() && page.controls.permits(BlueprintScreenId.ADAPT, action, page.visibleOriginal != null)) when (action) {
                BlueprintDiscoveryAction.ADAPT_CREATE -> if (page.canCreate) {
                    if (mine && hasInterpretationText) actions.interpretAdaptation?.invoke()
                    else if (mine) actions.adaptation?.request?.invoke()
                    else actions.requestEasier?.invoke(goal, differentMeal)
                }
                BlueprintDiscoveryAction.ADAPT_KEEP_ORIGINAL -> if (mine) actions.adaptation?.viewOriginal?.invoke() else actions.back()
                BlueprintDiscoveryAction.ADAPT_INGREDIENTS -> openIngredients()
                BlueprintDiscoveryAction.ADAPT_PREFERENCES -> openPreferences(actions.foodPreferences)
                BlueprintDiscoveryAction.ADAPT_EFFORT -> if (mine) openRefinement(BlueprintAdaptationRefinementPage.EFFORT)
                    else openDetails("My energy")
                BlueprintDiscoveryAction.ADAPT_TASTE -> if (mine) openRefinement(BlueprintAdaptationRefinementPage.TASTE)
                    else openDetails("My vibe")
                BlueprintDiscoveryAction.ADAPT_EQUIPMENT -> openPreferences(actions.equipmentPreferences)
                else -> Unit
            }
        }, onBack = back, onMore = { openDetails("Version details & recovery") }, onNavigate = {})
    } else {
        val comparison = if (unavailable) null else ownedBlueprintComparison(meal, picker, choices)
        val canAccept = editable && !form.dirty && comparison != null &&
            (if (mine) actions.adaptation != null else actions.acceptEasier != null)
        val page = BlueprintVariantState(comparison, controls.copy(allowedActions = buildSet {
            if (editable) {
                if (canAccept) add(BlueprintDiscoveryAction.VARIANT_ACCEPT)
                if (mine && actions.adaptation != null || !mine && actions.keepOriginal != null)
                    add(BlueprintDiscoveryAction.VARIANT_ORIGINAL)
                if (mine && actions.adaptation != null || !mine && actions.editEasierLimits != null)
                    add(BlueprintDiscoveryAction.VARIANT_EDIT)
            }
        }))
        BlueprintVariantScreen(page, onAction = { action ->
            if (current() && page.controls.permits(BlueprintScreenId.VARIANT, action, page.visibleComparison != null)) when (action) {
                BlueprintDiscoveryAction.VARIANT_ACCEPT -> if (canAccept) {
                    if (mine) actions.adaptation?.accept?.invoke() else actions.acceptEasier?.invoke()
                }
                BlueprintDiscoveryAction.VARIANT_ORIGINAL -> if (mine) actions.adaptation?.viewOriginal?.invoke() else actions.keepOriginal?.invoke()
                BlueprintDiscoveryAction.VARIANT_EDIT -> if (mine) actions.adaptation?.editRequest?.invoke() else actions.editEasierLimits?.invoke()
                else -> Unit // Another swap is not silently turned into another/new request.
            }
        }, onBack = back, onMore = { openDetails("Version details & recovery") }, onNavigate = {})
    }
    if (details != null && current() && !unavailable) FeedMeTheme {
        AlertDialog(onDismissRequest = { if (current()) details = null }, title = { Text(details!!) },
            text = { Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                status.notices.forEach { InfoCard(it.title, it.message) }
                if (pending) {
                    Text("Retry uses only the original retained request and key.")
                    meal.retryAtMillis?.let { Text("Earliest retry: ${kotlin.time.Instant.fromEpochMilliseconds(it)}") }
                    TextButton(onClick = { if (current()) { if (mine) actions.adaptation?.retry?.invoke() else actions.retry() } },
                        enabled = !form.busy && (!mine || actions.adaptation != null) && meal.issue !in setOf(
                            MealFlowIssue.REPLAY_EXPIRED, MealFlowIssue.CONTEXT_CHANGED, MealFlowIssue.PREFERENCES_PENDING)) { Text("Retry original request") }
                }
                IngredientNameLookup(mealLabelIds(meal), picker, form.busy, actions.loadIngredientNames?.let { load ->
                    { if (current()) load() } })
                if (requestPage && mine) {
                    if (actions.adaptation?.planNewMeal != null) TextButton(onClick = ::reviewNewMeal,
                        enabled = refinementAvailable && current()) { Text("Plan a new meal with these inputs…") }
                    else if (actions.reviewPlanSource != null) {
                        TextButton(onClick = {
                            if (current() && refinementAvailable) {
                                details = null
                                actions.reviewPlanSource.invoke()
                            }
                        }, enabled = refinementAvailable && current()) { Text("Review source for a new version") }
                        Text("Recheck the original recipe, then choose Make Mine to review these meal inputs. Your current plan stays selected; no request or ingredient swap is sent. Back from source review returns to your main request with these edits retained separately.")
                    }
                    else if (refinementAvailable) Text(
                        "A fresh source review is needed before these inputs can become a new meal request. No source or original request is cleared here.")
                    AdaptationRequestScreen(meal, form, adaptation, picker, choices, guardedAdaptation)
                }
                else if (requestPage) {
                    Text("What would help most?")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SimplificationGoal.entries.forEach { option -> FilterChip(selected = goal == option,
                            onClick = { if (current() && editable) goal = option }, enabled = editable,
                            label = { Text(simplificationGoalLabel(option)) }) }
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(differentMeal, enabled = editable,
                        role = Role.Checkbox, onValueChange = { if (current()) differentMeal = it })) {
                        Checkbox(differentMeal, null, enabled = editable)
                        Text("A different meal is okay if no simpler version fits.", Modifier.weight(1f))
                    }
                    Text("Close this sheet, then choose Make my version to send the request.")
                    TextButton(onClick = { if (current() && editable) actions.back() }, enabled = editable) { Text("Return to the meal without submitting") }
                    actions.editEasierLimits?.let { editLimits ->
                        TextButton(onClick = { if (current() && editable) editLimits() }, enabled = editable) { Text("Edit existing limits") }
                    }
                    original?.let { PlanCard(it, true, choices) }
                } else if (mine) AdaptationProposalScreen(meal, form, picker, choices, guardedAdaptation)
                else SimplificationProposalCard(meal, form, picker, choices, actions)
            } }, confirmButton = { TextButton(onClick = { if (current()) details = null }) { Text("Done") } })
    }
    refinement?.let { exact ->
        fun childCurrent() = refinement === exact && ingredientVisit == null &&
            exact.current(meal, form, adaptation, originCurrent())
        fun closeChild() {
            if (refinement !== exact) return
            exact.retire(); refinement = null; interaction = Any()
        }
        BlueprintAdaptationRefinementDialog(exact, choices, ::childCurrent, ::closeChild) { next ->
            if (childCurrent()) {
                actions.adaptation?.edit?.invoke { before ->
                    if (!childCurrent() || before != exact.values) before
                    else blueprintAdaptationRefinementEdit(before, next, exact.page)
                }
                closeChild()
            }
        }
    }
    ingredientVisit?.let { exact ->
        fun childCurrent() = attached && ingredientVisit === exact && refinement == null &&
            preferenceVisit == null && newMealReview == null && details == null && exact.current(meal, form, adaptation)
        fun closeIngredients() {
            if (ingredientVisit !== exact) return
            exact.retire(); ingredientVisit = null; interaction = Any()
        }
        // This dedicated host deliberately permits picker snapshots to change during reads;
        // the ordinary parent action closure also captures the old picker and cannot do so.
        BlueprintAdaptationIngredientsDialog(exact, ::childCurrent, ::closeIngredients)
    }
    preferenceVisit?.let { exact ->
        DisposableEffect(exact) { onDispose { exact.retire() } }
        fun closePreferences() {
            if (preferenceVisit !== exact) return
            exact.retire(); preferenceVisit = null; interaction = Any()
        }
        // The child owns Back/review/unsaved-preference handling. Dismissing this outer
        // window must not bypass it; its exact local close remains usable after revocation.
        Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                exact.host.content({ preferenceCurrent(exact) }, ::closePreferences)
            }
        }
    }
    newMealReview?.let { exact ->
        DisposableEffect(exact) { onDispose { exact.retire() } }
        fun reviewCurrent() = attached && newMealReview === exact && refinement == null && ingredientVisit == null && preferenceVisit == null &&
            details == null && exact.current(form, adaptation,
                blueprintAdaptationRefinementAvailable(meal, form, adaptation), originCurrent())
        fun closeReview() {
            if (newMealReview !== exact) return
            exact.retire(); newMealReview = null; interaction = Any()
        }
        val allowed = reviewCurrent()
        AlertDialog(onDismissRequest = ::closeReview,
            title = { Text("Replace your meal request inputs?") },
            text = {
                if (!allowed) Text("This review is no longer current. Go back without changing either draft.")
                else Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Use all of this version’s meal inputs in the main request: meal mode and base meal, ingredients, equipment, servings, energy, time and cleanup limits, tastes and exclusions.")
                    Text(mealOptionsSummary(exact.values.meal, choices))
                    Text(mealRefinementSummary(exact.values.meal, choices))
                    Text("This replaces the main request inputs. Your original plan and Make Mine metadata stay retained separately. A missing-ingredient swap intent is not executed.")
                    Text("No meal request is sent. Review the Request screen, then choose Find my dinner when you’re ready.")
                }
            },
            dismissButton = { TextButton(onClick = ::closeReview) { Text("Cancel") } },
            confirmButton = { TextButton(enabled = allowed, onClick = {
                if (reviewCurrent()) {
                    // Retire the exact consent before dispatch so repeated callbacks cannot
                    // enqueue another staging operation. The raw port rechecks its owner.
                    closeReview()
                    exact.action()
                }
            }) { Text("Use these inputs") } })
    }
}

/** Exact tokens stay in the full detail/comparison; compact Int-only metric chips are
 * omitted rather than rounding fractional servings or normalising recorded numbers. */
private fun ownedBlueprintRecipe(view: MealPlanPresentation, choices: MealInputChoices): BlueprintRecipeDetail? {
    val recipe = view.recipe?.takeIf { view.recipeVisible } ?: return null
    fun integer(token: String) = token.toIntOrNull()?.takeIf { it.toString() == token }
    val card = BlueprintMealCard(BlueprintMealIdentity(recipe.recipeId.value, recipe.id.value, view.plan.id.value),
        recipe.title, recipe.summary.valueOrNull().orEmpty(), metrics = BlueprintMealMetrics(integer(recipe.totalMinutes.jsonToken),
            integer(recipe.activeMinutes.jsonToken), recipe.cleanupMinutes.valueOrNull()?.jsonToken?.let { "$it min" } ?: "unknown",
            integer(recipe.servings.jsonToken)), badge = if (view.historical) "Retained plan" else "Plan snapshot")
    return BlueprintRecipeDetail(card,
        recipe.ingredients.map { BlueprintRecipeIngredient(
            (view.ingredientName(it.ingredientId.value) ?: "Ingredient label unavailable (${it.ingredientId.value})") +
                it.preparation.valueOrNull()?.let { prep -> " · $prep" }.orEmpty() + if (it.optional) " · optional" else "",
            "${it.quantity.jsonToken} ${it.unit}") },
        recipe.steps.map { BlueprintRecipeStep("Step ${it.position.jsonToken}" + if (it.mandatorySafetyStep) " · Required safety step" else "", it.instruction) },
        BlueprintRecipeProvenance("Plan snapshot · catalog ${view.plan.catalogRevision}",
            "Recorded review: ${recipe.reviewStatus}", "Plan ${view.plan.version.jsonToken} · recipe ${recipe.version.jsonToken}",
            recipe.contentLicense.valueOrNull()), recipe.equipmentIds.map { recipeEquipmentLabel(it, choices) },
        safetyNotes = listOf(view.effortSummary, "Recorded servings: ${recipe.servings.jsonToken}") + view.plan.missingIngredients.map(view::ingredientLine))
}

private fun ownedBlueprintComparison(meal: MealScreenState, picker: MealPickerPresentation,
    choices: MealInputChoices): BlueprintMealComparison? {
    val labels = picker.knownIngredients.associate { it.id to it.name }
    if (meal.screen == MealFlowScreen.VARIANT_MINE) {
        val proposal = meal.adaptation ?: return null
        val view = AdaptationPresentation.from(proposal, labels, choices)
        val comparison = proposal.comparison?.takeIf { view.canCompare } ?: return null
        return BlueprintMealComparison(ownedBlueprintRecipe(view.original, choices) ?: return null,
            ownedBlueprintRecipe(view.proposed, choices) ?: return null,
            ownedEffortChanges(comparison.before, comparison.after) +
                (view.ingredientRows + view.stepRows).map { BlueprintMealChange(it.before, it.after, it.title) },
            reasons = view.reasons + view.serverChanges + view.equipmentLines,
            uncertainties = listOf(view.comparisonNotice, view.missingIngredientStatus) + view.missingIngredientLines)
    }
    val proposal = meal.proposal ?: return null
    val comparison = proposal.comparison ?: return null
    val original = MealPlanPresentation(proposal.parent.plan, proposal.parent.historical, labels)
    val proposed = MealPlanPresentation(proposal.child.plan, proposal.child.historical, labels)
    return BlueprintMealComparison(ownedBlueprintRecipe(original, choices) ?: return null,
        ownedBlueprintRecipe(proposed, choices) ?: return null,
        changes = ownedEffortChanges(comparison.before, comparison.after) + listOf(
            BlueprintMealChange(original.previewIngredients.joinToString("\n"), proposed.previewIngredients.joinToString("\n"), "Recorded ingredient amounts and preparation")),
        reasons = listOf("Requested: ${simplificationGoalLabel(proposal.goal)}",
            if (comparison.sameMeal) "Same meal, different version." else "A different meal.") + proposed.reasons + proposed.changes,
        uncertainties = buildList {
            add("These are retained snapshot differences, not current permission to cook or save. Read complete recipes under More.")
            if (comparison.regressedDimensions.isNotEmpty()) add("Tradeoff: ${comparison.regressedDimensions.joinToString(transform = ::simplificationDimensionLabel)} increase.")
            addAll(proposed.plan.missingIngredients.map(proposed::ingredientLine))
        })
}

private fun ownedEffortChanges(before: SimplificationEffortSnapshot, after: SimplificationEffortSnapshot) = listOf(
    BlueprintMealChange("${before.activeMinutes} min", "${after.activeMinutes} min", "Hands-on time"),
    BlueprintMealChange("${before.totalMinutes} min", "${after.totalMinutes} min", "Total time"),
    BlueprintMealChange(before.cleanupMinutes?.let { "$it min" } ?: "Unknown", after.cleanupMinutes?.let { "$it min" } ?: "Unknown", "Cleanup"),
    BlueprintMealChange(before.utensilCount, after.utensilCount, "Utensils"),
    BlueprintMealChange(before.servings, after.servings, "Servings"),
    BlueprintMealChange(simplificationEnergyLabel(before.energy), simplificationEnergyLabel(after.energy), "Energy"),
    BlueprintMealChange(simplificationModeLabel(before.mode), simplificationModeLabel(after.mode), "Meal mode"),
)
