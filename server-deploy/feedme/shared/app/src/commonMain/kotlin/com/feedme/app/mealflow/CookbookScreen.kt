package com.feedme.app.mealflow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.feedme.app.FeedMeColors
import com.feedme.app.blueprint.*
import com.feedme.contracts.RecipeVersionWire
import com.feedme.contracts.SavedRecipeWire
import com.feedme.contracts.IngredientAmountWire
import com.feedme.kitchen.SavedRecipeAvailability
import com.feedme.kitchen.SavedRecipeSnapshot
import com.feedme.mealflow.*

/** SavedRecipe remains SavedRecipe: no forged Plan identity, constraints, selection or cook grant. */
class SavedRecipePresentation internal constructor(private val saved: SavedRecipeWire?,
    private val availability: SavedRecipeAvailability, private val localRevision: Long?, private val etag: String? = null) {
    constructor(observation: SavedRecipeSnapshot) : this(observation.savedRecipe, observation.availability, observation.localRevision, observation.etag)
    val recipe: RecipeVersionWire? get() = saved?.snapshot?.takeIf {
        availability == SavedRecipeAvailability.AVAILABLE && !saved.recalled && it.reviewStatus != "recalled"
    }
    val title get() = if (contentVisible) saved?.title ?: "Saved recipe unavailable" else "Saved recipe unavailable"
    val downloaded get() = localRevision != null
    val contentVisible get() = recipe != null
    /** Retained attribution is readable content, not current copy/cooking permission. */
    val provenanceLines: List<String> get() {
        val retainedRecipe = recipe ?: return emptyList()
        val retained = saved ?: return emptyList()
        return buildList {
            add("Saved source type: ${retained.sourceType}")
            retained.creatorLabel.valueOrNull()?.let { add("Recorded creator: $it") }
            retained.contentLicense.valueOrNull()?.let { add("Saved-copy license: $it") }
            add("Saved-copy version: ${retained.version.jsonToken}")
            add("Recipe version: ${retainedRecipe.version.jsonToken}")
            retainedRecipe.reviewerLabel.valueOrNull()?.let { add("Recorded reviewer: $it") }
            retainedRecipe.reviewedAt.valueOrNull()?.let { add("Recorded review date: $it") }
            retainedRecipe.contentLicense.valueOrNull()?.let { add("Recipe license: $it") }
        }
    }
    /** Advisory affordance only. The controller must freshly mint exact removal evidence. */
    val removalReviewAvailable get() = contentVisible ||
        (saved == null && downloaded && etag != null && availability in setOf(SavedRecipeAvailability.RECALLED, SavedRecipeAvailability.UNAVAILABLE))
    val notice get() = if (downloaded) "Downloaded on this device · historical copy, not a current cooking or rights check"
        else "Server observation only · not downloaded on this device"
    override fun toString() = "SavedRecipePresentation(<redacted>)"
}
internal fun cookbookAcknowledgementText(state: CookbookState) =
    if (state.makeAgainAcknowledgedPlanId != null && state.serverAcknowledged && state.pending == null)
        "Meal saved and your Make Again preference recorded. Nothing was shared."
    else if (state.pending?.markMakeAgain == true)
        "Your original Save and Make Again request is retained. Both actions need one confirmed receipt; the outcome is not confirmed yet."
    else cookbookAcknowledgementText(state.pending?.finalizationRequired == true, state.serverAcknowledged)
internal fun cookbookAcknowledgementText(finalizationRequired: Boolean, acknowledged: Boolean): String = when {
    finalizationRequired -> "Application acknowledgement is unresolved. Retry the original action; reading this recipe does not repair it."
    acknowledged -> "The original server receipt and local application were acknowledged."
    else -> "Historical or read-only observation. No new Save or deletion has been acknowledged by this view."
}
internal fun cookbookMoreLabel(localOnly: Boolean): String =
    if (localOnly) "Show more downloaded meals" else "Next saved meals page"
internal fun cookbookRemovalDescription(title: String?, contentUnavailable: Boolean): String =
    if (contentUnavailable) "Remove this unavailable saved copy using its exact retained version. Instructions and attribution stay hidden. Its source and existing cooking progress are not erased. A version conflict needs new review, never an automatic rebase."
    else "Remove ${title ?: "this copy"} from Saved using this exact observed version. Its source and existing cooking progress are not erased. A version conflict needs new review, never an automatic rebase."
internal fun cookbookNextStepsText(cookAgainConnected: Boolean, shareRecipeConnected: Boolean = false): String = if (shareRecipeConnected)
    (if (cookAgainConnected) "Cook again starts a fresh meal review; it does not start cooking. "
        else "Cooking a saved copy is not connected here. ") +
        "Share my take starts a separate local draft with a saved recipe reference. Attachment, audience and publication each need review; nothing is shared automatically."
    else if (cookAgainConnected)
    "Cook again starts a fresh meal review; it does not start cooking or share anything. Repeat-preference feedback, Save undo, sharing and offline manifest verification remain separate. Collections have their own review flow when connected."
    else "Cooking a saved copy, Make Again, Save undo, sharing and offline manifest verification are not connected here. Collections have their own review flow when connected."
internal fun savedIngredientLine(ingredient: IngredientAmountWire, names: Map<String, String>): String {
    val name = recipeIngredientLabel(ingredient.ingredientId.value, names) ?: "Label unavailable · ${ingredient.ingredientId.value}"
    return "${ingredient.quantity.jsonToken} ${ingredient.unit} $name" +
        (ingredient.preparation.valueOrNull()?.let { " · $it" } ?: "") + if (ingredient.optional) " · optional" else ""
}
class CookbookScreenActions(val back: () -> Unit, val query: (String) -> Unit, val search: () -> Unit,
    val local: () -> Unit, val more: () -> Unit, val open: (String) -> Unit,
    val refresh: () -> Unit, val download: () -> Unit, val delete: (String) -> Unit,
    val retry: () -> Unit, val discard: () -> Unit, val loadIngredientNames: (() -> Unit)? = null,
    val cookAgain: (() -> Unit)? = null, val makeMine: (() -> Unit)? = null,
    val blueprintIsCurrent: () -> Boolean = { false }, val collections: (() -> Unit)? = null,
    val memory: (() -> Unit)? = null, val tabNavigation: MealTabNavigation? = null,
    /** The configured host rechecks the exact local list visit again inside queued navigation. */
    val guardedTabNavigate: ((BlueprintScreenId, () -> Boolean) -> Unit)? = null,
    /** Original recipe actions retain the exact local underlay visit across host scheduling. */
    val guardedRecipeAction: ((BlueprintDiscoveryAction, () -> Boolean) -> Unit)? = null,
    /** Opens a separate local draft/source review; never a publication callback. */
    val shareRecipe: (() -> Unit)? = null)

/** More is a local child visit. Closing it never revives earlier list/tool callbacks. */
private class CookbookToolsVisit {
    private var active = true
    var token: Any by mutableStateOf(Any())
        private set
    var visible: Boolean by mutableStateOf(false)
        private set
    fun current(expected: Any, tools: Boolean) = active && token === expected && visible == tools
    fun open(expected: Any) {
        if (current(expected, false)) { token = Any(); visible = true }
    }
    fun close(expected: Any) {
        if (current(expected, true)) { token = Any(); visible = false }
    }
    fun retire() { active = false }
}

/** Local visit fencing supplements, never replaces, the real owner/query/lease checks. */
private fun CookbookScreenActions.forTools(current: () -> Boolean): CookbookScreenActions {
    val original = this
    fun allowed() = current() && original.blueprintIsCurrent()
    fun run(action: () -> Unit) { if (allowed()) action() }
    fun recipeAction(intent: BlueprintDiscoveryAction, action: () -> Unit) {
        if (!allowed()) return
        val guarded = original.guardedRecipeAction
        if (guarded != null) guarded(intent, ::allowed) else action()
    }
    return CookbookScreenActions(back = { run(original.back) }, query = { if (allowed()) original.query(it) },
        search = { run(original.search) }, local = { run(original.local) }, more = { run(original.more) },
        open = { if (allowed()) original.open(it) }, refresh = { run(original.refresh) },
        download = { run(original.download) }, delete = { if (allowed()) original.delete(it) },
        retry = { run(original.retry) }, discard = { run(original.discard) },
        loadIngredientNames = original.loadIngredientNames?.let { action -> { run(action) } },
        cookAgain = original.cookAgain?.let { action -> { recipeAction(BlueprintDiscoveryAction.RECIPE_COOK, action) } },
        makeMine = original.makeMine?.let { action -> { recipeAction(BlueprintDiscoveryAction.RECIPE_ADAPT, action) } },
        blueprintIsCurrent = ::allowed,
        collections = original.collections?.let { action -> { run(action) } },
        memory = original.memory?.let { action -> { run(action) } },
        shareRecipe = original.shareRecipe?.takeIf { original.guardedRecipeAction != null }?.let { action ->
            { recipeAction(BlueprintDiscoveryAction.RECIPE_SHARE, action) }
        })
}

internal fun savedCookingFailureText(reason: com.feedme.core.ports.FailureReason): String = when (reason) {
    com.feedme.core.ports.FailureReason.CONFLICT ->
        "Finish or resolve your current meal first, then try again. Save or discard unfinished edits before switching."
    com.feedme.core.ports.FailureReason.OFFLINE ->
        "You can review your saved copy offline. Connect before checking it for a new cook."
    com.feedme.core.ports.FailureReason.STALE_SESSION, com.feedme.core.ports.FailureReason.UNAUTHENTICATED ->
        "Your account connection needs attention. This saved copy has not started cooking."
    else -> "This saved recipe could not be selected. Nothing has started cooking; try again after checking the saved copy."
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RetainedCookbookScreen(state: CookbookState, query: String, picker: MealPickerPresentation,
    busy: Boolean, actions: CookbookScreenActions, choices: MealInputChoices? = null,
    savedCookingFailure: com.feedme.core.ports.FailureReason? = null,
    savedMakeMineFailure: com.feedme.core.ports.FailureReason? = null,
    navigationNotice: String? = null) {
    // Context object identity is a render fence only. The real account/controller remains the owner.
    val context = remember(state, query) { BlueprintLibraryContext("retained-cookbook", "current-render") }
    val toolsVisit = remember(state.screen) { CookbookToolsVisit() }
    DisposableEffect(toolsVisit) { onDispose { toolsVisit.retire() } }
    val visitToken = toolsVisit.token
    fun underlayCurrent() = toolsVisit.current(visitToken, false) && actions.blueprintIsCurrent()
    val base = if (actions.blueprintIsCurrent() && savedCookingFailure == null && savedMakeMineFailure == null)
        blueprintCookbookState(state, query, busy, context, memoryAvailable = actions.memory != null,
            allowedNavigation = actions.tabNavigation?.destinations) else null
    val canCollections = !busy && !state.busy && state.pending == null && state.deleteConfirmation == null &&
        actions.collections != null && actions.blueprintIsCurrent()
    val projected = base?.let { original -> original.copy(availableTabs = if (canCollections)
        setOf(BlueprintLibraryTab.ALL, BlueprintLibraryTab.COLLECTIONS) else setOf(BlueprintLibraryTab.ALL),
        controls = original.controls.copy(enabled = original.controls.enabled && !toolsVisit.visible,
            message = listOfNotNull(original.controls.message, navigationNotice?.takeIf(String::isNotBlank))
                .joinToString("\n").takeIf(String::isNotBlank),
            editableFields = original.controls.editableFields +
            if (canCollections) setOf("tab") else emptySet())) }
    val recipe = if (actions.blueprintIsCurrent() && savedCookingFailure == null && savedMakeMineFailure == null)
        blueprintSavedRecipeState(state, picker, busy, choices, actions)?.let { original ->
            original.copy(controls = original.controls.copy(enabled = original.controls.enabled && !toolsVisit.visible,
                message = listOfNotNull(original.controls.message, navigationNotice?.takeIf(String::isNotBlank))
                    .joinToString("\n\n").takeIf(String::isNotBlank)))
        } else null
    if (projected != null || recipe != null) {
        val tools = { if (underlayCurrent()) toolsVisit.open(visitToken) }
        if (projected != null) BlueprintCookbookScreen(projected,
            onSearch = { rendered, value -> if (rendered === context && underlayCurrent()) actions.query(value) },
            onTab = { rendered, tab -> if (rendered === context && canCollections && underlayCurrent() &&
                tab == BlueprintLibraryTab.COLLECTIONS) actions.collections?.invoke() },
            onAction = { dispatchBlueprintSaved(projected, it, underlayCurrent(), actions.open, null, actions.memory) },
            onBack = { if (underlayCurrent()) actions.back() }, onMore = tools,
            onNavigate = { destination -> dispatchBlueprintCookbookTab(projected, destination, ::underlayCurrent, actions) },
            onSubmitSearch = { if (underlayCurrent()) {
                if (state.localOnly) actions.local() else actions.search()
            } })
        else if (recipe != null) BlueprintRecipeScreen(recipe,
            onAction = { dispatchBlueprintSavedRecipe(recipe, it, ::underlayCurrent, actions) },
            onBack = { if (underlayCurrent()) actions.back() }, onMore = tools,
            // Saved-detail departure is Back to the owned list, not a borrowed main-meal tab grant.
            onNavigate = {})
        if (toolsVisit.visible) Dialog(onDismissRequest = { toolsVisit.close(visitToken) },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = FeedMeColors.Paper) {
                RetainedCookbookTools(state, query, picker, busy,
                    actions.forTools { toolsVisit.current(visitToken, true) }, choices, savedCookingFailure,
                    savedMakeMineFailure, navigationNotice) { toolsVisit.close(visitToken) }
            }
        }
    } else RetainedCookbookTools(state, query, picker, busy, actions, choices, savedCookingFailure, savedMakeMineFailure,
        navigationNotice)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RetainedCookbookTools(state: CookbookState, query: String, picker: MealPickerPresentation,
    busy: Boolean, actions: CookbookScreenActions, choices: MealInputChoices?,
    savedCookingFailure: com.feedme.core.ports.FailureReason?, savedMakeMineFailure: com.feedme.core.ports.FailureReason?,
    navigationNotice: String? = null,
    closeTools: (() -> Unit)? = null) {
    // Expanding downloaded results preserves position. A new search/source or remote
    // page starts at the top instead of inheriting the previous page's bottom offset.
    val remotePage = if (state.localOnly) null else state.items.map { it.id }
    val scroll = key(state.screen, state.selected?.id, state.localOnly, state.query, remotePage) { rememberScrollState() }
    var viewSort by remember { mutableStateOf(CookbookViewSort.AS_RECEIVED) }
    Box(Modifier.fillMaxSize().background(FeedMeColors.Paper).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(scroll)
        .padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.Center) {
            TextButton(onClick = closeTools ?: actions.back, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (closeTools == null) "← Back" else if (state.screen == CookbookScreen.DETAIL)
                    "← Back to saved recipe" else "← Back to Cookbook")
            }
            Text("FeedMe", style = MaterialTheme.typography.titleLarge)
            Pill("SAVED", FeedMeColors.Lime)
        }
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("KEEP THE GOOD ONES.", color = FeedMeColors.Blue, style = MaterialTheme.typography.labelMedium)
            Text(if (state.screen == CookbookScreen.DETAIL) "Your saved copy." else "Good meals,\nkept close.",
                style = MaterialTheme.typography.displayMedium, modifier = Modifier.semantics { heading() })
            Text("Your private recipe collection. Opening a copy never starts cooking, saves changes or shares it.",
                style = MaterialTheme.typography.bodyMedium, color = FeedMeColors.Muted)
        }
        if (busy || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        actions.collections?.let { openCollections ->
            CookbookSecondary("Collections", !busy && !state.busy && state.pending == null &&
                state.deleteConfirmation == null && actions.blueprintIsCurrent()) {
                if (actions.blueprintIsCurrent() && !busy && !state.busy && state.pending == null && state.deleteConfirmation == null)
                    openCollections()
            }
        }
        savedCookingFailure?.let { InfoCard("Before cooking again", savedCookingFailureText(it)) }
        savedMakeMineFailure?.let { InfoCard("Make Mine not opened", savedCookingFailureText(it)) }
        navigationNotice?.takeIf(String::isNotBlank)?.let { InfoCard("Tab not opened", it) }
        state.failureReason?.let { InfoCard("Action not completed", "${it.name}. An unresolved original action is retained; no new key or version is substituted.") }
        state.pending?.let { pending ->
            Surface(color = FeedMeColors.SoftBlue, shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Original Saved action", style = MaterialTheme.typography.titleMedium)
                    Text("${pending.operationId} · ${pending.phase} · ${pending.issue}", style = MaterialTheme.typography.bodySmall)
                    Text(cookbookAcknowledgementText(state), style = MaterialTheme.typography.bodyMedium)
                    if (pending.canRetry) Primary(if (pending.operationId == "deleteSavedRecipe") "Retry original deletion" else "Retry original Save", !busy, actions.retry)
                    if (pending.canDiscardUnsent) CookbookSecondary("Discard unsent Saved action", !busy, actions.discard)
                }
            }
        }
        if (state.screen == CookbookScreen.DETAIL) {
            val selected = state.selected
            if (selected == null) InfoCard("Copy unavailable", "This view has no eligible saved recipe. Back remains available.")
            else {
                val view = SavedRecipePresentation(selected)
                val recipe = view.recipe
                Surface(color = FeedMeColors.SoftLime, shape = RoundedCornerShape(28.dp)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(if (view.downloaded) "IN YOUR COLLECTION · DOWNLOADED" else "IN YOUR COLLECTION · ONLINE",
                            style = MaterialTheme.typography.labelMedium)
                        Text(view.title, style = MaterialTheme.typography.headlineLarge)
                        if (recipe != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("${recipe.servings.jsonToken} servings", Color.White)
                            Pill("${recipe.activeMinutes.jsonToken} min active", Color.White)
                            Pill("${recipe.totalMinutes.jsonToken} min total", Color.White)
                        }
                        Text(view.notice, style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
                    }
                }
                if (recipe == null) InfoCard("Instructions unavailable", "A recalled, redacted or damaged copy cannot expose instructions here.")
                else {
                    actions.cookAgain?.let { cookAgain ->
                        Primary("Cook again", !busy && !state.busy && state.pending == null && state.deleteConfirmation == null, cookAgain)
                        Text("Review today’s ingredients, time and energy. A fresh check comes before cooking.",
                            style = MaterialTheme.typography.bodyMedium, color = FeedMeColors.Muted)
                    }
                    actions.makeMine?.let { makeMine ->
                        CookbookSecondary("Make Mine", !busy && !state.busy && state.pending == null && state.deleteConfirmation == null, makeMine)
                        Text("Make a version for today. Your saved copy and current meal stay unchanged until you choose a version.",
                            style = MaterialTheme.typography.bodyMedium, color = FeedMeColors.Muted)
                    }
                    IngredientNameLookup(recipeLabelIds(recipe), picker, busy, actions.loadIngredientNames)
                    SectionTitle("01", "Ingredients")
                    val names = picker.knownIngredients.associate { it.id to it.name }
                    Surface(color = Color.White, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
                        Column(Modifier.padding(horizontal = 20.dp)) {
                            recipe.ingredients.forEachIndexed { index, ingredient ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text("+", color = FeedMeColors.Blue, fontWeight = FontWeight.Bold)
                                    Text(savedIngredientLine(ingredient, names), style = MaterialTheme.typography.bodyLarge)
                                }
                                if (index < recipe.ingredients.lastIndex) HorizontalDivider(color = FeedMeColors.Line)
                            }
                        }
                    }
                    SectionTitle("02", "Equipment")
                    Text(if (recipe.equipmentIds.isEmpty()) "No equipment listed in this saved recipe."
                        else recipe.equipmentIds.joinToString { recipeEquipmentLabel(it, choices) },
                        style = MaterialTheme.typography.bodyLarge)
                    SectionTitle("03", "Saved instructions")
                    recipe.steps.forEach { step ->
                        Surface(color = if (step.mandatorySafetyStep) FeedMeColors.SoftBlue else Color.White,
                            shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
                            Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(step.position.jsonToken, style = MaterialTheme.typography.titleLarge, color = FeedMeColors.Blue)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    if (step.mandatorySafetyStep) Text("Required safety step", color = FeedMeColors.Blue, style = MaterialTheme.typography.labelLarge)
                                    Text(step.instruction, style = MaterialTheme.typography.bodyLarge)
                                    RecipeStepMetadata(recipe, step, { savedIngredientLine(it, names) }, choices)
                                }
                            }
                        }
                    }
                    SectionTitle("04", "Saved source & version")
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        view.provenanceLines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text("These details describe the retained copy. They do not establish current permission to cook, copy or share it.",
                            style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
                    }
                }
                SectionTitle(if (recipe == null) "01" else "05", "Manage this copy")
                CookbookSecondary("Refresh saved recipe", !busy, actions.refresh)
                CookbookSecondary("Download this saved copy", !busy && view.contentVisible, actions.download)
                CookbookSecondary("Review removal from Saved", !busy && state.pending == null && view.removalReviewAvailable) { actions.delete(selected.id) }
                InfoCard("Separate next steps", cookbookNextStepsText(actions.cookAgain != null, actions.shareRecipe != null))
            }
        } else {
            Surface(color = Color.White, shape = RoundedCornerShape(26.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = query, onValueChange = actions.query, label = { Text("Search saved recipes") },
                        placeholder = { Text("Find a keeper…") }, shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Primary("Search my cookbook online", !busy, actions.search)
                    CookbookSecondary("Search downloaded copies", !busy, actions.local)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(if (state.localOnly) "ON THIS DEVICE" else "ONLINE COLLECTION", FeedMeColors.Lime)
                Pill("${state.items.size} in this view", Color.White)
            }
            Text(if (state.localOnly) "Downloaded copies only" else "One bounded server page · rows are not automatically downloaded",
                style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
            if (state.items.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sort this view", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = viewSort == CookbookViewSort.AS_RECEIVED,
                        onClick = { viewSort = CookbookViewSort.AS_RECEIVED },
                        enabled = !busy && !state.busy, label = { Text("As received") }, modifier = Modifier.heightIn(min = 48.dp))
                    FilterChip(selected = viewSort == CookbookViewSort.TITLE_ASCENDING,
                        onClick = { viewSort = CookbookViewSort.TITLE_ASCENDING },
                        enabled = !busy && !state.busy, label = { Text("Title A–Z") }, modifier = Modifier.heightIn(min = 48.dp))
                }
                Text("Only the recipes shown here are sorted. Unavailable copies stay where they are.",
                    style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
            }
            if (state.items.isEmpty()) Surface(color = FeedMeColors.SoftLime, shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.fillMaxWidth().padding(26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Room for your\nnext favourite.", style = MaterialTheme.typography.headlineLarge)
                    Text("No copies in this view", style = MaterialTheme.typography.titleMedium)
                    Text("Try another search or switch between online and downloaded copies. Other pages or server copies may still exist.",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            sortedCookbookView(state.items, viewSort).forEachIndexed { index, row ->
                key(row.id) {
                    val view = SavedRecipePresentation(row)
                    Card(onClick = { actions.open(row.id) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
                        shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, FeedMeColors.Line)) {
                        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text((index + 1).toString().padStart(2, '0'), style = MaterialTheme.typography.titleLarge, color = FeedMeColors.Blue)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(view.title, style = MaterialTheme.typography.titleMedium)
                                Text(if (view.downloaded) "Downloaded copy" else "Server observation", style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
                            }
                            Text("↗", style = MaterialTheme.typography.titleLarge, color = FeedMeColors.Blue)
                        }
                    }
                }
            }
            if (state.hasMore) CookbookSecondary(cookbookMoreLabel(state.localOnly), !busy && !state.busy, actions.more)
        }
        if (state.pending == null) Text(cookbookAcknowledgementText(state), style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
    }
    }
}

@Composable
private fun CookbookSecondary(label: String, enabled: Boolean, action: () -> Unit) {
    OutlinedButton(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        border = BorderStroke(1.dp, FeedMeColors.Line), colors = ButtonDefaults.outlinedButtonColors(contentColor = FeedMeColors.Ink)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
