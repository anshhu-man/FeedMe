package com.feedme.app.mealflow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.feedme.app.FeedMeColors
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
    /** Advisory affordance only. The controller must freshly mint exact removal evidence. */
    val removalReviewAvailable get() = contentVisible ||
        (saved == null && downloaded && etag != null && availability in setOf(SavedRecipeAvailability.RECALLED, SavedRecipeAvailability.UNAVAILABLE))
    val notice get() = if (downloaded) "Downloaded on this device · historical copy, not a current cooking or rights check"
        else "Server observation only · not downloaded on this device"
    override fun toString() = "SavedRecipePresentation(<redacted>)"
}
internal fun cookbookAcknowledgementText(state: CookbookState) = cookbookAcknowledgementText(state.pending?.finalizationRequired == true, state.serverAcknowledged)
internal fun cookbookAcknowledgementText(finalizationRequired: Boolean, acknowledged: Boolean): String = when {
    finalizationRequired -> "Application acknowledgement is unresolved. Retry the original action; reading this recipe does not repair it."
    acknowledged -> "The original server receipt and local application were acknowledged."
    else -> "Historical or read-only observation. No new Save or deletion has been acknowledged by this view."
}
internal fun cookbookRemovalDescription(title: String?, contentUnavailable: Boolean): String =
    if (contentUnavailable) "Remove this unavailable saved copy using its exact retained version. Instructions and attribution stay hidden. Its source and existing cooking progress are not erased. A version conflict needs new review, never an automatic rebase."
    else "Remove ${title ?: "this copy"} from your cookbook using this exact observed version. Its source and existing cooking progress are not erased. A version conflict needs new review, never an automatic rebase."
internal fun savedIngredientLine(ingredient: IngredientAmountWire, names: Map<String, String>): String {
    val name = names[ingredient.ingredientId.value] ?: "Label unavailable · ${ingredient.ingredientId.value}"
    return "${ingredient.quantity.jsonToken} ${ingredient.unit} $name" +
        (ingredient.preparation.valueOrNull()?.let { " · $it" } ?: "") + if (ingredient.optional) " · optional" else ""
}
class CookbookScreenActions(val back: () -> Unit, val query: (String) -> Unit, val search: () -> Unit,
    val local: () -> Unit, val more: () -> Unit, val open: (String) -> Unit,
    val refresh: () -> Unit, val download: () -> Unit, val delete: (String) -> Unit,
    val retry: () -> Unit, val discard: () -> Unit)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RetainedCookbookScreen(state: CookbookState, query: String, picker: MealPickerPresentation,
    busy: Boolean, actions: CookbookScreenActions) {
    val scroll = key(state.screen, state.selected?.id) { rememberScrollState() }
    Box(Modifier.fillMaxSize().background(FeedMeColors.Paper).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(scroll)
        .padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.Center) {
            TextButton(onClick = actions.back, modifier = Modifier.heightIn(min = 48.dp)) { Text("← Back") }
            Text("FeedMe", style = MaterialTheme.typography.titleLarge)
            Pill("COOKBOOK", FeedMeColors.Lime)
        }
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("KEEP THE GOOD ONES.", color = FeedMeColors.Blue, style = MaterialTheme.typography.labelMedium)
            Text(if (state.screen == CookbookScreen.DETAIL) "Your saved copy." else "Good meals,\nkept close.",
                style = MaterialTheme.typography.displayMedium, modifier = Modifier.semantics { heading() })
            Text("Your private recipe collection. Opening a copy never starts cooking, saves changes or shares it.",
                style = MaterialTheme.typography.bodyMedium, color = FeedMeColors.Muted)
        }
        if (busy || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.failureReason?.let { InfoCard("Action not completed", "${it.name}. An unresolved original action is retained; no new key or version is substituted.") }
        state.pending?.let { pending ->
            Surface(color = FeedMeColors.SoftBlue, shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Original cookbook action", style = MaterialTheme.typography.titleMedium)
                    Text("${pending.operationId} · ${pending.phase} · ${pending.issue}", style = MaterialTheme.typography.bodySmall)
                    Text(cookbookAcknowledgementText(state), style = MaterialTheme.typography.bodyMedium)
                    if (pending.canRetry) Primary(if (pending.operationId == "deleteSavedRecipe") "Retry original deletion" else "Retry original Save", !busy, actions.retry)
                    if (pending.canDiscardUnsent) CookbookSecondary("Discard unsent cookbook action", !busy, actions.discard)
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
                    SectionTitle("02", "Saved instructions")
                    recipe.steps.forEach { step ->
                        Surface(color = if (step.mandatorySafetyStep) FeedMeColors.SoftBlue else Color.White,
                            shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
                            Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(step.position.jsonToken, style = MaterialTheme.typography.titleLarge, color = FeedMeColors.Blue)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    if (step.mandatorySafetyStep) Text("Required safety step", color = FeedMeColors.Blue, style = MaterialTheme.typography.labelLarge)
                                    Text(step.instruction, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                }
                SectionTitle("03", "Manage this copy")
                CookbookSecondary("Refresh saved recipe", !busy, actions.refresh)
                CookbookSecondary("Download this saved copy", !busy && view.contentVisible, actions.download)
                CookbookSecondary("Review removal from cookbook", !busy && state.pending == null && view.removalReviewAvailable) { actions.delete(selected.id) }
                InfoCard("Separate next steps", "Cooking a saved copy, Make Again, custom collections, Save undo, sharing and offline manifest verification are not connected here.")
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
            if (state.items.isEmpty()) Surface(color = FeedMeColors.SoftLime, shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.fillMaxWidth().padding(26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Room for your\nnext favourite.", style = MaterialTheme.typography.headlineLarge)
                    Text("No copies in this view", style = MaterialTheme.typography.titleMedium)
                    Text("Try another search or switch between online and downloaded copies. Other pages or server copies may still exist.",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.items.forEachIndexed { index, row ->
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
            if (state.hasMore) CookbookSecondary("Next cookbook page", !busy, actions.more)
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
