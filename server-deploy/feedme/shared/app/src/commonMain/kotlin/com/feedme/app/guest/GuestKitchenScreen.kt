package com.feedme.app.guest

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.feedme.app.FeedMeColors
import com.feedme.app.FeedMeSectionHeading
import com.feedme.app.FeedMeStatusLabel
import com.feedme.app.FeedMeTheme
import com.feedme.app.FeedMeWordmark

enum class GuestKitchenTab(val label: String) {
    COOK("Cook"), TODAY("Today"), SAVED("Saved"), INBOX("Inbox"), PROFILE("Profile"),
}

internal fun GuestPreparation.choiceLabel(): String = when (this) {
    GuestPreparation.ASSEMBLE -> "Assemble only"
    GuestPreparation.LITTLE -> "A little cooking"
    GuestPreparation.HAPPY -> "Happy to cook"
}

internal fun GuestPreparation?.choiceHint(): String = when (this) {
    null -> "Choose how much preparation feels doable."
    GuestPreparation.ASSEMBLE -> "No heating or substantial prep."
    GuestPreparation.LITTLE -> "Light preparation."
    GuestPreparation.HAPPY -> "More involved cooking is OK."
}

internal fun GuestCleanupLimit.choiceLabel(): String = when (this) {
    GuestCleanupLimit.ZERO_MINUTES -> "0 min"
    GuestCleanupLimit.FIVE_MINUTES -> "Up to 5 min"
    GuestCleanupLimit.TEN_MINUTES -> "Up to 10 min"
    GuestCleanupLimit.UNLIMITED -> "No limit"
}

/** Explains missing explicit inputs without interpreting legacy energy/cleanup values. */
internal fun guestMissingChoices(draft: GuestKitchenDraft): String? {
    val missing = buildList {
        if (draft.ingredientsText.isBlank()) add("add ingredients")
        if (draft.preparation == null) add("choose preparation")
        if (draft.cleanupLimit == null) add("choose a cleanup limit")
        if (draft.servings == null) add("choose servings")
    }
    if (missing.isEmpty()) return null
    val choices = if (missing.size == 1) missing.single()
        else missing.dropLast(1).joinToString(", ") + " and " + missing.last()
    return "To continue: $choices."
}

internal const val GUEST_MATCHING_UNAVAILABLE =
    "Your ingredient names haven’t been matched to a reviewed catalog. Meal choices aren’t available in this build, so no recipe was selected or request sent."

/** Guest-only presentation. The host owns the draft, encrypted persistence, tab restoration
 * and action lifetimes. Attachment, recomposition and tab changes never load, save, match,
 * sign in or publish. This slice has no reviewed recipe or connected matching capability. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuestKitchenScreen(
    state: GuestKitchenDraftState,
    selectedTab: GuestKitchenTab,
    matchingUnavailable: Boolean,
    onDraftChange: (GuestKitchenDraft) -> Unit,
    onFindMeal: () -> Unit,
    onRetry: () -> Unit,
    onTabChange: (GuestKitchenTab) -> Unit,
    onAccount: () -> Unit,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    // Keep each destination's place when a user briefly checks another tab. These states
    // also use Compose's normal saved-state restoration; no draft or account I/O is added.
    val tabScrollStates = GuestKitchenTab.entries.map { rememberScrollState() }
    val selectTab: (GuestKitchenTab) -> Unit = { tab -> keyboard?.hide(); onTabChange(tab) }
    platformBackHandler(selectedTab != GuestKitchenTab.COOK) { selectTab(GuestKitchenTab.COOK) }
    FeedMeTheme {
        Scaffold(
            modifier = Modifier.safeDrawingPadding().imePadding(),
            containerColor = FeedMeColors.Paper,
            bottomBar = { GuestKitchenNavigation(selectedTab, selectTab) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                key(selectedTab) {
                    Column(Modifier.widthIn(max = 620.dp).fillMaxWidth().verticalScroll(tabScrollStates[selectedTab.ordinal])
                        .padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()) {
                            FeedMeWordmark(compact = true)
                            FeedMeStatusLabel("Guest kitchen", FeedMeColors.SoftLime)
                        }
                        if (selectedTab == GuestKitchenTab.COOK) {
                            CookInputs(state, matchingUnavailable, onDraftChange,
                                onFindMeal = { keyboard?.hide(); onFindMeal() }, onRetry = onRetry)
                        } else GuestSecondaryPage(selectedTab, onAccount) { selectTab(GuestKitchenTab.COOK) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuestKitchenNavigation(current: GuestKitchenTab, select: (GuestKitchenTab) -> Unit) {
    Surface(color = FeedMeColors.Paper, shadowElevation = 5.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            GuestKitchenTab.entries.forEach { tab ->
                TextButton(onClick = { select(tab) },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).semantics {
                        role = Role.Tab; selected = current == tab
                    },
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 10.dp),
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = if (current == tab) FeedMeColors.Lime else Color.Transparent,
                        contentColor = FeedMeColors.Ink,
                    )) {
                    Text(tab.label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CookInputs(state: GuestKitchenDraftState, matchingUnavailable: Boolean,
    onDraftChange: (GuestKitchenDraft) -> Unit, onFindMeal: () -> Unit, onRetry: () -> Unit) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val primaryView = remember { BringIntoViewRequester() }
    val ingredientsView = remember { BringIntoViewRequester() }
    val preparationView = remember { BringIntoViewRequester() }
    val cleanupView = remember { BringIntoViewRequester() }
    val servingsView = remember { BringIntoViewRequester() }
    val ingredientsFocus = remember { FocusRequester() }
    val preparationFocus = remember { FocusRequester() }
    val cleanupFocus = remember { FocusRequester() }
    val servingsFocus = remember { FocusRequester() }
    val primaryAction = guestPrimaryAction(state)
    val currentAction by rememberUpdatedState(primaryAction)
    fun moveTo(action: GuestKitchenPrimaryAction, view: BringIntoViewRequester, focus: FocusRequester) {
        if (action != GuestKitchenPrimaryAction.ADD_INGREDIENTS) keyboard?.hide()
        scope.launch {
            view.bringIntoView()
            // Relocation can suspend while an edit or failed save changes the form.
            // A stale navigation request must not focus a now-disabled choice.
            if (currentAction == action) focus.requestFocus()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("Dinner.\nWith what you have.", style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() })
        Text("Start with what you have and what feels doable. No account needed.",
            style = MaterialTheme.typography.bodyLarge, color = FeedMeColors.Muted)
    }
    GuestDraftStatus(state, onRetry)
    val draft = state.draft
    val editable = state.phase in setOf(GuestDraftPhase.READY, GuestDraftPhase.SAVING)
    if (draft != null) {
        var inputError by remember { mutableStateOf<String?>(null) }
        Column(Modifier.fillMaxWidth().bringIntoViewRequester(primaryView), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                when (val action = currentAction) {
                    GuestKitchenPrimaryAction.ADD_INGREDIENTS -> moveTo(action, ingredientsView, ingredientsFocus)
                    GuestKitchenPrimaryAction.CHOOSE_PREPARATION -> moveTo(action, preparationView, preparationFocus)
                    GuestKitchenPrimaryAction.CHOOSE_CLEANUP -> moveTo(action, cleanupView, cleanupFocus)
                    GuestKitchenPrimaryAction.CHOOSE_SERVINGS -> moveTo(action, servingsView, servingsFocus)
                    GuestKitchenPrimaryAction.FIND_MEAL -> onFindMeal()
                    GuestKitchenPrimaryAction.UNAVAILABLE -> Unit
                }
            }, enabled = primaryAction != GuestKitchenPrimaryAction.UNAVAILABLE,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) {
                Text(primaryAction.label, style = MaterialTheme.typography.titleMedium)
            }
            Text("Meal matching is not connected in this build.", style = MaterialTheme.typography.bodySmall,
                color = FeedMeColors.Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            if (matchingUnavailable) GuestMatchingUnavailable()
        }
        GuestInputCard {
            FeedMeSectionHeading("What’s in your kitchen?", "A few ingredients are enough to start.")
            OutlinedTextField(
                value = draft.ingredientsText,
                onValueChange = change@{ text ->
                    val next = try { draft.copy(ingredientsText = text) }
                    catch (_: IllegalArgumentException) {
                        inputError = "That edit couldn’t be kept. Use up to ${GuestKitchenDraft.MAX_INGREDIENT_CHARACTERS} characters of ordinary text."
                        return@change
                    }
                    inputError = null
                    onDraftChange(next)
                },
                enabled = editable,
                modifier = Modifier.fillMaxWidth().bringIntoViewRequester(ingredientsView).focusRequester(ingredientsFocus),
                label = { Text("Ingredients you have") },
                placeholder = { Text("For example: rice, eggs, tomatoes") },
                supportingText = { Text(inputError ?: "Up to ${GuestKitchenDraft.MAX_INGREDIENT_CHARACTERS} characters. Nothing is uploaded.") },
                isError = inputError != null,
                minLines = 3,
                maxLines = 5,
                shape = RoundedCornerShape(16.dp),
            )
            Text("This is your note, not a checked pantry or an allergy-safety check.",
                style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
        }
        GuestInputCard {
            FeedMeSectionHeading("Make it fit your evening")
            GuestChoiceLabel("Total time available")
            FlowRow(modifier = Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GuestKitchenDraft.ALLOWED_MINUTES.forEach { minutes ->
                    GuestChoice("$minutes min", draft.minutes == minutes, editable) { onDraftChange(draft.copy(minutes = minutes)) }
                }
            }
            GuestChoiceLabel("Preparation")
            FlowRow(modifier = Modifier.selectableGroup().bringIntoViewRequester(preparationView),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GuestPreparation.entries.forEach { value ->
                    GuestChoice(value.choiceLabel(), draft.preparation == value, editable,
                        modifier = if (value == (draft.preparation ?: GuestPreparation.entries.first())) Modifier.focusRequester(preparationFocus) else Modifier) {
                        onDraftChange(draft.copy(preparation = value))
                    }
                }
            }
            Text(draft.preparation.choiceHint(), style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
            GuestChoiceLabel("Cleanup time")
            FlowRow(modifier = Modifier.selectableGroup().bringIntoViewRequester(cleanupView),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GuestCleanupLimit.entries.forEach { value ->
                    GuestChoice(value.choiceLabel(), draft.cleanupLimit == value, editable,
                        modifier = if (value == (draft.cleanupLimit ?: GuestCleanupLimit.entries.first())) Modifier.focusRequester(cleanupFocus) else Modifier) {
                        onDraftChange(draft.copy(cleanupLimit = value))
                    }
                }
            }
            if (draft.cleanupLimit == null) Text("Choose a cleanup-time limit, or explicitly choose no limit.",
                style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
            GuestChoiceLabel("Servings")
            FlowRow(modifier = Modifier.selectableGroup().bringIntoViewRequester(servingsView),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GuestKitchenDraft.ALLOWED_SERVINGS.forEach { servings ->
                    GuestChoice(if (servings == 1) "1 serving" else "$servings servings", draft.servings == servings, editable,
                        modifier = if (servings == (draft.servings ?: GuestKitchenDraft.ALLOWED_SERVINGS.first)) Modifier.focusRequester(servingsFocus) else Modifier) {
                        onDraftChange(draft.copy(servings = servings))
                    }
                }
            }
            if (draft.servings == null) Text("Choose how many servings to make.",
                style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
        }
        guestMissingChoices(draft)?.let { message ->
            Text(message, style = MaterialTheme.typography.bodyMedium, color = FeedMeColors.Muted,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        Button(onClick = {
            if (currentAction == GuestKitchenPrimaryAction.FIND_MEAL) {
                onFindMeal()
                scope.launch { primaryView.bringIntoView() }
            }
        }, enabled = guestCanFindMeal(state),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) {
            Text("Find me a meal", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun GuestMatchingUnavailable() {
    Surface(color = FeedMeColors.SoftBlue, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No meal chosen yet", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            Text(GUEST_MATCHING_UNAVAILABLE, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun GuestDraftStatus(state: GuestKitchenDraftState, retry: () -> Unit) {
    val message = when {
        state.phase == GuestDraftPhase.CLOSED -> "This kitchen is closed. Reopen it to continue."
        state.phase in setOf(GuestDraftPhase.NEW, GuestDraftPhase.LOADING) -> "Opening your local kitchen…"
        state.issue == GuestDraftIssue.FUTURE_VERSION -> "This draft needs a newer app version. It hasn’t been replaced."
        state.issue == GuestDraftIssue.CORRUPT -> "Your earlier draft couldn’t be read. It hasn’t been replaced."
        state.issue == GuestDraftIssue.OUTCOME_UNKNOWN -> "We couldn’t confirm the last save. Retry before leaving."
        state.issue != null && state.draft == null -> "Your local draft couldn’t be opened. Try again; nothing has been reset."
        state.issue != null -> "Your changes aren’t confirmed as saved. Try again before leaving."
        state.phase == GuestDraftPhase.SAVING || state.dirty -> "Saving your changes on this device…"
        state.phase == GuestDraftPhase.READY && state.hasSavedDraft -> "Saved on this device"
        state.phase == GuestDraftPhase.BLOCKED -> "Your local draft needs attention before you can continue."
        else -> "Choose what works for you. Your inputs stay on this device."
    }
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, style = MaterialTheme.typography.bodyMedium,
            color = if (state.issue != null) MaterialTheme.colorScheme.error else FeedMeColors.Muted)
        if (state.phase in setOf(GuestDraftPhase.NEW, GuestDraftPhase.LOADING, GuestDraftPhase.SAVING))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.issue != null && state.phase in setOf(GuestDraftPhase.READY, GuestDraftPhase.BLOCKED))
            OutlinedButton(onClick = retry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry local draft") }
    }
}

@Composable
private fun GuestSecondaryPage(tab: GuestKitchenTab, onAccount: () -> Unit, onCook: () -> Unit) {
    val title = when (tab) {
        GuestKitchenTab.TODAY -> "Real meals.\nYour people."
        GuestKitchenTab.SAVED -> "Worth making\nagain."
        GuestKitchenTab.INBOX -> "A little kitchen\nconversation."
        GuestKitchenTab.PROFILE -> "Your kitchen.\nYour call."
        GuestKitchenTab.COOK -> return
    }
    Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
    GuestInputCard {
        if (tab == GuestKitchenTab.PROFILE) {
            Text("You’re cooking as a guest", style = MaterialTheme.typography.titleLarge)
            Text("Your cooking inputs stay on this device. An account isn’t required to use this kitchen.", style = MaterialTheme.typography.bodyLarge)
            Text("Account setup opens separately. Your guest inputs won’t be uploaded or merged automatically.",
                style = MaterialTheme.typography.bodyMedium, color = FeedMeColors.Muted)
            OutlinedButton(onClick = onAccount, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(16.dp)) { Text("Sign-in options") }
        } else {
            FeedMeStatusLabel("Not connected yet", FeedMeColors.SoftBlue)
            Text(when (tab) {
                GuestKitchenTab.TODAY -> "Casual meals from your kitchen circles belong here. There’s no connected feed or live sharing in this build."
                GuestKitchenTab.SAVED -> "Keep meals you want to make again. Recipe saving isn’t connected to this guest kitchen yet; no saved recipes are shown here."
                GuestKitchenTab.INBOX -> "Recipe replies and conversations belong here. Messaging isn’t connected yet."
                else -> ""
            }, style = MaterialTheme.typography.bodyLarge)
        }
    }
    TextButton(onClick = onCook, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Back to Cook") }
}

@Composable
private fun GuestInputCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = FeedMeColors.Surface, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun GuestChoiceLabel(label: String) {
    Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun GuestChoice(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // These choices replace one value, not independent filters. Keep exactly one selectable
    // action/role per chip so touch, keyboard and accessibility all use the same callback.
    Surface(modifier = modifier.heightIn(min = 48.dp).selectable(
        selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        color = if (selected) FeedMeColors.Lime else FeedMeColors.Surface,
        contentColor = if (enabled) FeedMeColors.Ink else FeedMeColors.Ink.copy(alpha = 0.38f),
        border = BorderStroke(1.dp, if (selected) FeedMeColors.Ink else FeedMeColors.Muted),
        shape = RoundedCornerShape(14.dp)) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
