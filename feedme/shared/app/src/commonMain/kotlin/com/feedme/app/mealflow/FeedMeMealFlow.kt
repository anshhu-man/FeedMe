package com.feedme.app.mealflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.feedme.app.FeedMeColors
import com.feedme.app.FeedMeTheme
import com.feedme.app.FeedMeDetails
import com.feedme.app.FeedMeWordmark
import com.feedme.contracts.*
import com.feedme.core.ports.PortResult
import com.feedme.mealflow.*
import com.feedme.mealflow.social.PostDraftScreen
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private class MealExitIntent(val from: MealFlowScreen)

/** Real retained-controller host, never a demo runtime or authentication entry point.
 * The application owns experience lifetime. Detaching/recreating UI does NOT close native state.
 * onExit must navigate through the owning app root and close experience on actual disposal. */
@Composable
fun FeedMeMealFlow(experience: MealFlowExperience, onExit: () -> Unit,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit) {
    val meal by experience.meals.states.collectAsState()
    val picker by experience.ingredients.states.collectAsState()
    val form by experience.forms.collectAsState()
    val kitchen by experience.kitchen.states.collectAsState()
    val kitchenPage by experience.kitchenPage.collectAsState()
    val cooking by experience.cooking.states.collectAsState()
    val cookingNavigation by experience.cookingNavigation.collectAsState()
    val cookbook by experience.cookbook.states.collectAsState()
    val cookbookQuery by experience.cookbookQuery.collectAsState()
    val timerOwner by experience.timerController.collectAsState()
    val timerState = timerOwner?.states?.collectAsState()?.value
    val timerConfirmation by experience.timerConfirmation.collectAsState()
    val postDraftOwner = experience.postDrafts
    val postDraftState = postDraftOwner?.states?.collectAsState()?.value
    val reviewedPostOwner = experience.reviewedPosts
    val reviewedPostNavigation by experience.reviewedPostsNavigation.collectAsState()
    val scope = rememberCoroutineScope()
    var exitIntent by remember(experience) { mutableStateOf<MealExitIntent?>(null) }
    LaunchedEffect(experience) { experience.restore() }
    LaunchedEffect(experience, timerOwner) {
        while (isActive) {
            delay(1000)
            if (timerOwner?.states?.value?.visible == true) experience.tickTimers()
        }
    }
    fun leave(from: MealFlowScreen) {
        if (from == MealFlowScreen.REQUEST) onExit() else scope.launch { experience.back(from) }
    }
    fun back() {
        if (experience.cookbook.states.value.deleteConfirmation != null) { scope.launch { experience.dismissSavedRecipeDelete() }; return }
        if (experience.timerConfirmation.value != null) { experience.dismissTimerRemoval(); return }
        if (experience.cookingNavigation.value.confirmation != null) { experience.dismissCookingConfirmation(); return }
        if (experience.timers?.states?.value?.visible == true) { scope.launch { experience.backFromTimers() }; return }
        if (experience.cookbook.states.value.screen != CookbookScreen.HIDDEN) { scope.launch { experience.backFromCookbook() }; return }
        if (experience.kitchenPage.value != null) { exitIntent = null; experience.leaveKitchen(); return }
        if (experience.cookingNavigation.value.visible) {
            if (experience.cooking.states.value.screen == CookingFlowScreen.COOK) scope.launch { experience.backFromCooking() }
            else { experience.leaveCooking(); scope.launch { experience.backFromCooking() } }
            return
        }
        val from = experience.meals.states.value.screen
        if (experience.forms.value.dirty) exitIntent = MealExitIntent(from) else { exitIntent = null; leave(from) }
    }
    // Explicit experience navigation, never selected-root visibility, owns this wrapper's
    // lifetime. Applying a publication may hide/remove its draft while result/recovery UI
    // must remain mounted. Attachment and Back never restore or migrate legacy records.
    if (reviewedPostOwner != null && reviewedPostNavigation.visible && form.values != null) {
        if (reviewedPostNavigation.opening) {
            val leavePosts = { scope.launch { experience.leavePostDrafts() }; Unit }
            platformBackHandler(true, leavePosts)
            FeedMeTheme {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextButton(onClick = leavePosts, modifier = Modifier.heightIn(min = 48.dp)) { Text("← Back") }
                    Text("Opening retained drafts", style = MaterialTheme.typography.titleLarge)
                    Text("Reading local history does not Save, Publish or upgrade an older draft.")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                reviewedPostNavigation.failure?.let {
                    Text(reviewedEntryFailureText(it).orEmpty(), Modifier.fillMaxWidth().padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                Box(Modifier.weight(1f)) {
                    FeedMeReviewedPostFlow(reviewedPostOwner, platformBackHandler,
                        ReviewedPostLegacyFormatUiPolicy.CURRENT_FORMAT_ONLY,
                        onExit = { scope.launch { experience.leavePostDrafts() } })
                }
            }
        }
        return
    }
    if (reviewedPostOwner == null && postDraftOwner != null && postDraftState?.screen != PostDraftScreen.HIDDEN && form.values != null) {
        FeedMePostDraftFlow(postDraftOwner, platformBackHandler)
        return
    }
    platformBackHandler(true, ::back)
    val actions = MealScreenActions(
        back = ::back, edit = experience::edit, searchText = experience::searchText,
        search = { scope.launch { experience.search() } }, moreIngredients = { scope.launch { experience.moreIngredients() } },
        pantry = { scope.launch { experience.pantry() } }, morePantry = { scope.launch { experience.morePantry() } },
        context = { scope.launch { experience.refreshContext() } }, saveDraft = { scope.launch { experience.saveDraft() } },
        find = { scope.launch { experience.findMeal() } }, retry = { scope.launch { experience.retryOriginal() } },
        alternative = { scope.launch { experience.alternative() } }, previous = { scope.launch { experience.previous() } },
        recipe = { scope.launch { experience.recipe() } },
        kitchen = experience::openKitchen,
        cook = { scope.launch { experience.prepareCooking() } },
        retainedCooking = if (cooking.phase != CookingFlowPhase.IDLE) experience::showCooking else null,
        cookbook = { scope.launch { experience.openCookbook() } },
        save = { scope.launch { experience.saveSelectedRecipe() } },
        drafts = if (postDraftOwner == null) null else { { scope.launch { experience.openPostDrafts() }; Unit } },
    )
    FeedMeTheme {
      Box(Modifier.fillMaxSize()) {
        val inputPage = kitchenPage
        if (timerState?.visible == true && form.values != null) CookingTimerScreen(CookingTimerScreenState.from(timerState),
            CookingTimerScreenActions(back = ::back, duration = { scope.launch { experience.timerDuration(it) } },
                start = { scope.launch { experience.startTimer() } }, pause = { scope.launch { experience.pauseTimer(it) } },
                resume = { scope.launch { experience.resumeTimer(it) } }, reset = { scope.launch { experience.resetTimer(it) } },
                remove = experience::requestTimerRemoval, cleanup = { scope.launch { experience.cancelTimerAlert(it) } },
                refresh = { scope.launch { experience.tickTimers() } }))
        else if (cookbook.screen != CookbookScreen.HIDDEN && form.values != null) RetainedCookbookScreen(cookbook,
            cookbookQuery, MealPickerPresentation.from(picker), form.busy, CookbookScreenActions(
                back = ::back, query = experience::cookbookSearchText,
                search = { scope.launch { experience.searchCookbook() } }, local = { scope.launch { experience.searchDownloadedCookbook() } },
                more = { scope.launch { experience.moreCookbook() } }, open = { scope.launch { experience.openSavedRecipe(it) } },
                refresh = { scope.launch { experience.refreshSavedRecipe() } }, download = { scope.launch { experience.downloadSavedRecipe() } },
                delete = { scope.launch { experience.prepareSavedRecipeDelete(it) } }, retry = { scope.launch { experience.retryCookbookOriginal() } },
                discard = { scope.launch { experience.discardCookbookUnsent() } }))
        else if (inputPage != null && form.values != null) KitchenInputScreen(inputPage, kitchen,
            MealPickerPresentation.from(picker), experience.choices, form.busy, form.searchText,
            KitchenInputScreenActions(
                back = ::back, searchText = experience::searchText,
                search = { scope.launch { experience.search() } }, moreIngredients = { scope.launch { experience.moreIngredients() } },
                loadPreferences = { scope.launch { experience.loadKitchenPreferences() } },
                loadPantry = { scope.launch { experience.loadKitchenPantry() } }, morePantry = { scope.launch { experience.moreKitchenPantry() } },
                editPreferences = { scope.launch { experience.editKitchenPreferences(it) } },
                discardPreferenceDraft = { scope.launch { experience.discardKitchenPreferences() } },
                savePreferences = { scope.launch { experience.saveKitchenPreferences() } },
                editPantry = { scope.launch { experience.editKitchenPantry(it) } },
                discardPantryDraft = { scope.launch { experience.discardKitchenPantry(it) } },
                savePantry = { scope.launch { experience.saveKitchenPantry(it) } },
                removePantry = { scope.launch { experience.removeKitchenPantry(it) } },
                synchronize = { scope.launch { experience.synchronizeKitchen(it) } },
                discardUnsent = { scope.launch { experience.discardUnsentKitchen(it) } },
            ))
        else if (cookingNavigation.visible && form.values != null) RetainedCookingScreen(CookingScreenState.from(cooking),
            MealPickerPresentation.from(picker), experience.choices, form.busy, CookingScreenActions(
                back = ::back, leave = experience::leaveCooking, reviewStart = experience::reviewCookingStart,
                retryStart = { scope.launch { experience.retryCookingStart() } }, discardStart = { scope.launch { experience.discardCookingStart() } },
                readRetained = { scope.launch { experience.readRetainedCooking() } }, reopen = { scope.launch { experience.reopenCooking() } },
                refresh = { scope.launch { experience.refreshCooking() } }, synchronize = { scope.launch { experience.synchronizeCooking() } },
                move = { scope.launch { experience.moveCooking(it) } }, mark = { scope.launch { experience.completeCookingStep(it) } },
                pause = { scope.launch { experience.pauseCooking() } }, resume = { scope.launch { experience.resumeCooking() } },
                abandon = { experience.requestCookingEnd(CookingConfirmationKind.ABANDON) },
                complete = { experience.requestCookingEnd(CookingConfirmationKind.COMPLETE) },
                timers = if (timerOwner == null) null else { { scope.launch { experience.openTimers() }; Unit } },
                cookbook = { scope.launch { experience.openCookbook() } },
                save = if (!form.dirty && cooking.plan?.id?.value != null && cooking.plan?.id?.value == meal.plan?.plan?.id?.value)
                    { { scope.launch { experience.saveCookingRecipe() }; Unit } } else null,
            ))
        else MealJourneyScreen(MealScreenState.from(meal), MealPickerPresentation.from(picker), form, experience.choices, actions)
        if (timerState != null && !timerState.visible && form.values != null && showCurrentTimerDue(timerState, cooking)) {
            Snackbar(Modifier.align(Alignment.BottomCenter).safeDrawingPadding(),
                containerColor = FeedMeColors.Ink, contentColor = FeedMeColors.Paper, action = {
                TextButton(modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = FeedMeColors.Lime), onClick = { scope.launch {
                    if (showCurrentTimerDue(experience.timers?.states?.value, experience.cooking.states.value)) experience.openTimers()
                } }) { Text("View timers") }
            }) { Text("A timer reached its estimated end. No cooking step was advanced.") }
        }
        val removal = timerConfirmation
        val deletion = cookbook.deleteConfirmation
        if (deletion != null && cookbook.screen == CookbookScreen.DETAIL && form.values != null) {
            AlertDialog(onDismissRequest = { scope.launch { experience.dismissSavedRecipeDelete() } },
                title = { Text("Remove this saved copy?") },
                text = { Text(cookbookRemovalDescription(deletion.title, deletion.contentUnavailable)) },
                confirmButton = { TextButton(enabled = !form.busy, onClick = { scope.launch { experience.confirmSavedRecipeDelete(deletion) } }) { Text("Confirm cookbook removal") } },
                dismissButton = { TextButton(onClick = { scope.launch { experience.dismissSavedRecipeDelete() } }) { Text("Keep saved copy") } })
        }
        val timerPin = timerState?.snapshot
        if (removal != null && timerState?.visible == true && timerState.canStop && form.values != null &&
            timerPin != null && timerPin.sessionId == removal.sessionId && timerPin.planId == removal.planId && timerPin.localRevision == removal.revision &&
            timerPin.timers.any { it.timerId == removal.timerId }) {
            AlertDialog(onDismissRequest = experience::dismissTimerRemoval,
                title = { Text("Remove this timer?") },
                text = { Text("Remove this exact timer from local cooking progress and cancel its alert. This does not finish the step or meal; server sync remains explicit.") },
                confirmButton = { TextButton(enabled = !timerState.busy && !form.busy,
                    onClick = { scope.launch { experience.confirmTimerRemoval(removal) } }) { Text("Confirm timer removal") } },
                dismissButton = { TextButton(onClick = experience::dismissTimerRemoval) { Text("Keep timer") } })
        }
        val confirmation = cookingNavigation.confirmation
        if (confirmation != null && form.values != null) {
            // START never falls back to the old selected cooking pin. A stale ticket shows no dialog.
            val exact = cooking.plan?.takeIf { it.id.value == confirmation.planId }
            if (exact != null) AlertDialog(onDismissRequest = experience::dismissCookingConfirmation,
                title = { Text(when (confirmation.kind) {
                    CookingConfirmationKind.START -> "Start this exact plan?"
                    CookingConfirmationKind.ABANDON -> "Stop this session?"
                    CookingConfirmationKind.COMPLETE -> "Are you done cooking?"
                }) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(exact.recipeSnapshot.valueOrNull()?.title ?: "Exact retained plan")
                        Text("Plan ${exact.id.value} · ${exact.recipeSnapshot.valueOrNull()?.servings?.jsonToken ?: "unavailable"} servings")
                        Text(when (confirmation.kind) {
                            CookingConfirmationKind.START -> "Confirming explicitly creates a server cooking session for this plan. Check the ingredients and any missing essentials; this does not certify food safety."
                            CookingConfirmationKind.ABANDON -> "This records an explicit stop on this device. Back alone never stops a session; sync remains a separate action."
                            CookingConfirmationKind.COMPLETE -> "This records your completion on this device. It does not Save, Make Again, share or automatically synchronize."
                        })
                        if (confirmation.kind == CookingConfirmationKind.START && exact.missingIngredients.isNotEmpty()) {
                            val presentation = MealPlanPresentation(exact, true, picker.knownIngredients.associate { it.id to it.name })
                            Text("Missing ingredients: " + exact.missingIngredients.joinToString("\n", transform = presentation::ingredientLine))
                        }
                    }
                },
                confirmButton = { TextButton(enabled = !form.busy, onClick = { scope.launch { experience.confirmCooking(confirmation) } }) {
                    Text(when (confirmation.kind) {
                        CookingConfirmationKind.START -> "Confirm cooking start"
                        CookingConfirmationKind.ABANDON -> "Confirm stop"
                        CookingConfirmationKind.COMPLETE -> "Confirm completion"
                    })
                } }, dismissButton = { TextButton(onClick = experience::dismissCookingConfirmation) { Text("Not now") } })
        }
        if (exitIntent != null && form.values != null) AlertDialog(onDismissRequest = { exitIntent = null },
            title = { Text("Keep your changes?") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text("Unfinished edits are only in memory. Saving a valid draft keeps it on this device; it does not submit a meal.")
              Button(onClick = { exitIntent = null },
                  modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Keep editing") }
              OutlinedButton(enabled = !form.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                val request = exitIntent
                val ticket = experience.exitTicket()
                scope.launch {
                    val saved = experience.saveDraft()
                    // The suspended save does not own later edits, a dismissed dialog or a new session.
                    if (saved is PortResult.Value && request != null && exitIntent === request &&
                        ticket != null && experience.canExitAfterSave(ticket)) { exitIntent = null; leave(request.from) }
                }
              }) { Text("Save draft and go back") }
              TextButton(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                val from = exitIntent?.from ?: return@TextButton
                experience.discardUnsavedEdits(); exitIntent = null; leave(from)
            }) {
                Text("Discard unsaved edits")
              }
            } }, confirmButton = {})
      }
    }
}

/** Presentation events, not capabilities or transport operations. No default success handlers. */
class MealScreenActions(val back: () -> Unit, val edit: ((MealFormValues) -> MealFormValues) -> Unit,
    val searchText: (String) -> Unit, val search: () -> Unit, val moreIngredients: () -> Unit,
    val pantry: () -> Unit, val morePantry: () -> Unit, val context: () -> Unit, val saveDraft: () -> Unit,
    val find: () -> Unit, val retry: () -> Unit, val alternative: () -> Unit, val previous: () -> Unit, val recipe: () -> Unit,
    val kitchen: ((KitchenInputPage) -> Unit)? = null, val cook: (() -> Unit)? = null,
    val retainedCooking: (() -> Unit)? = null, val cookbook: (() -> Unit)? = null, val save: (() -> Unit)? = null,
    val drafts: (() -> Unit)? = null)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MealJourneyScreen(meal: MealScreenState, picker: MealPickerPresentation, form: MealFormState,
    choices: MealInputChoices, actions: MealScreenActions) {
    val unavailable = form.values == null || meal.phase == MealFlowPhase.UNAVAILABLE
    val status = mealPhaseMessage(meal.phase)
    Column(Modifier.fillMaxSize().safeDrawingPadding().widthIn(max = 720.dp).verticalScroll(rememberScrollState())
        .padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = actions.back, modifier = Modifier.heightIn(min = 48.dp)) { Text("← Back") }
            FeedMeWordmark(compact = true)
            Pill(if (unavailable) "UNAVAILABLE" else "YOUR KITCHEN", FeedMeColors.Lime)
        }
        if (meal.screen == MealFlowScreen.RECOMMENDATIONS && !unavailable) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(status.first, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                Text(status.second, color = FeedMeColors.Muted)
            }
        } else Hero(if (meal.screen == MealFlowScreen.REQUEST) "LESS EFFORT. MORE YOU." else "YOUR NEXT GOOD MEAL.",
            if (meal.screen == MealFlowScreen.REQUEST && !unavailable) "What’s the\ndinner vibe?" else status.first,
            if (meal.screen == MealFlowScreen.REQUEST && meal.phase == MealFlowPhase.EDITING && !unavailable)
                "A little energy or a lot. Start with what feels doable; choose the ingredients you’ve checked."
            else status.second)
        mealIssueMessage(meal.issue)?.let { InfoCard("Heads up", it) }
        if (form.failure != null && !unavailable) InfoCard("Not completed", failureText(form.failure))
        if (meal.failureReason != null && !unavailable) InfoCard("Request status", failureText(meal.failureReason))
        if (form.busy || meal.phase == MealFlowPhase.LOADING) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (unavailable) {
            InfoCard("Access required", "This screen cannot sign you in, repair your account or approve a recipe. Use Back to return.")
        } else {
            val values = form.values!!
            if (meal.screen != MealFlowScreen.RECOMMENDATIONS) MealJourneyShortcuts(actions)
            if (form.dirty) InfoCard("Unsaved edits", "These edits are not the request already sent to the server. Save a valid draft to keep them on this device.")
            if (meal.phase == MealFlowPhase.RESOLVING || meal.issue in setOf(MealFlowIssue.REQUEST_UNRESOLVED,
                    MealFlowIssue.REPLAY_EXPIRED, MealFlowIssue.RETRY_LATER)) {
                InfoCard("Original request retained", if (meal.pendingMatchesDraft) "The retained command matches your last saved draft."
                    else "Your edited draft does not replace the earlier unresolved command.")
                meal.retryAtMillis?.let { Text("Earliest retry: ${kotlin.time.Instant.fromEpochMilliseconds(it)}", style = MaterialTheme.typography.bodySmall) }
                Primary("Retry original request", !form.busy && meal.issue !in setOf(MealFlowIssue.REPLAY_EXPIRED,
                    MealFlowIssue.CONTEXT_CHANGED, MealFlowIssue.PREFERENCES_PENDING), actions.retry)
            }
            when (meal.screen) {
                MealFlowScreen.REQUEST -> RequestFields(values, form, picker, choices, actions)
                MealFlowScreen.RECOMMENDATIONS, MealFlowScreen.RECIPE -> {
                    val plan = meal.plan
                    if (plan == null) InfoCard("No plan yet", "Return to your draft to find a meal.")
                    else {
                        val presentation = MealPlanPresentation(plan, meal.historical,
                            picker.knownIngredients.associate { it.id to it.name })
                        PlanCard(presentation, meal.screen == MealFlowScreen.RECIPE, choices,
                            recipeAction = actions.recipe.takeIf { meal.screen == MealFlowScreen.RECOMMENDATIONS },
                            recipeEnabled = !form.busy && !form.dirty && presentation.recipeVisible)
                        if (meal.screen == MealFlowScreen.RECOMMENDATIONS) {
                            OutlinedButton(onClick = actions.alternative, enabled = !form.busy && !form.dirty && meal.alternativesAvailable,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Show another option") }
                            TextButton(onClick = actions.previous, enabled = !form.busy && meal.previousAvailable) {
                                Text("Previous option")
                            }
                        }
                        if (meal.screen == MealFlowScreen.RECIPE) actions.cook?.let {
                            Primary("Cook this plan", !form.busy && !form.dirty && presentation.recipeVisible, it)
                        }
                        if (meal.screen == MealFlowScreen.RECIPE) actions.save?.let {
                            OutlinedButton(onClick = it, enabled = !form.busy && !form.dirty && presentation.recipeVisible,
                                modifier = Modifier.fillMaxWidth()) { Text("Save this plan to cookbook") }
                        }
                        InfoCard("Preview, not cooking permission", "Cook this plan performs current eligibility checks before a separate confirmation. Save is explicit and separate from Make Again and sharing. Your plan never posts itself.")
                    }
                }
            }
            if (meal.screen == MealFlowScreen.RECOMMENDATIONS) MealJourneyShortcuts(actions)
        }
        Text("Made for real life. No perfect-kitchen energy required.", style = MaterialTheme.typography.bodySmall,
            color = FeedMeColors.Muted, modifier = Modifier.padding(vertical = 12.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MealJourneyShortcuts(actions: MealScreenActions) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        actions.retainedCooking?.let { OutlinedButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) { Text("View retained cooking") } }
        actions.cookbook?.let { TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open my cookbook") } }
        actions.drafts?.let { TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) { Text("My private drafts") } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RequestFields(values: MealFormValues, form: MealFormState, picker: MealPickerPresentation,
    choices: MealInputChoices, actions: MealScreenActions) {
    SectionTitle("01", "Make it feel doable.")
    Text("What kind of meal?", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MealMode.entries.forEach { mode -> FilterChip(selected = values.mode == mode,
            onClick = { actions.edit { it.copy(mode = mode) } }, modifier = Modifier.heightIn(min = 48.dp), label = { Text(modeLabel(mode)) }) }
    }
    if (values.mode == MealMode.IMPROVE) {
        Input("What’s already prepared?", values.baseDescription, { text -> actions.edit { it.copy(baseDescription = text) } }, singleLine = false)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasePreparation.entries.forEach { preparation -> FilterChip(values.basePreparation == preparation,
                { actions.edit { it.copy(basePreparation = preparation) } }, modifier = Modifier.heightIn(min = 48.dp), label = { Text(baseLabel(preparation)) }) }
        }
        InfoCard("No ingredient guessing", if (values.baseIngredientIds == null) "The ingredients in this existing meal are not confirmed. Its description alone cannot establish composition or safety."
            else "The existing meal’s ingredient IDs are retained from your saved explicit input. Availability choices below do not replace them.")
    }
    Text("Your energy today", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MealEnergy.entries.forEach { energy -> FilterChip(values.energy == energy,
            { actions.edit { it.copy(energy = energy) } }, modifier = Modifier.heightIn(min = 48.dp), label = { Text(energyLabel(energy)) }) }
    }
    Input("Servings", values.servings, { text -> actions.edit { it.copy(servings = text) } })
    ChoiceSection("Equipment you can use", choices.equipment, values.equipmentIds) { id -> actions.edit { it.copy(equipmentIds = toggle(it.equipmentIds, id)) } }
    Text(mealRefinementSummary(values, choices), style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
    FeedMeDetails("Time & taste · optional") {
        Input("Total minutes · optional", values.totalMinutes, { text -> actions.edit { it.copy(totalMinutes = text) } })
        Input("Hands-on minutes · optional", values.activeMinutes, { text -> actions.edit { it.copy(activeMinutes = text) } })
        ChoiceSection("Taste · optional", choices.tastes, values.tasteTags) { id -> actions.edit { it.copy(tasteTags = toggle(it.tasteTags, id)) } }
    }
    SectionTitle("02", "What have you got?")
    actions.kitchen?.let { open ->
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { open(KitchenInputPage.PANTRY) }, enabled = !form.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Edit pantry") }
            OutlinedButton(onClick = { open(KitchenInputPage.PREFERENCES) }, enabled = !form.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Food preferences") }
        }
    }
    Text("Pick ingredients you’ve checked for this meal. Pantry reports don’t confirm freshness or allergy safety.", color = FeedMeColors.Muted)
    Input("Search ingredient names", form.searchText, actions.searchText)
    OutlinedButton(onClick = actions.search, enabled = !form.busy && form.searchText.isNotBlank(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Search ingredients") }
    if (picker.searchPhase == IngredientPickerPhase.LOADING) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (picker.issue != IngredientPickerIssue.NONE) InfoCard("Ingredient lookup", pickerMessage(picker))
    if (picker.searchPhase == IngredientPickerPhase.EMPTY) Text("No ingredient matches. Try another name; nothing has been added.")
    picker.searchResults.forEach { option ->
        Surface(color = FeedMeColors.Surface, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(value = option.id in values.ingredientIds, role = Role.Checkbox,
                    onValueChange = { actions.edit { form -> form.copy(ingredientIds = toggle(form.ingredientIds, option.id)) } }),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = option.id in values.ingredientIds,
                        onCheckedChange = null)
                    Text(option.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                }
                if (option.historical) Text("Previously fetched label", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { actions.edit { it.copy(exclusions = toggle(it.exclusions, option.id)) } }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (option.id in values.exclusions) "Remove explicit exclusion" else "Exclude this ingredient")
                }
            }
        }
    }
    if (picker.searchHasMore) TextButton(enabled = !form.busy, onClick = actions.moreIngredients, modifier = Modifier.heightIn(min = 48.dp)) { Text("More ingredient matches") }
    if (values.ingredientIds.isNotEmpty()) {
        Text("Selected for this meal", style = MaterialTheme.typography.titleMedium)
        values.ingredientIds.forEach { id ->
            TextButton(onClick = { actions.edit { it.copy(ingredientIds = it.ingredientIds.filterNot { selected -> selected == id }) } }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Remove · ${picker.knownIngredients.firstOrNull { it.id == id }?.name ?: "Label unavailable ($id)"}")
            }
        }
    }
    if (values.exclusions.isNotEmpty()) InfoCard("Your explicit exclusions", values.exclusions.joinToString("\n") { id ->
        picker.knownIngredients.firstOrNull { it.id == id }?.name ?: "Label unavailable ($id)"
    })
    InfoCard("Saved preferences stay in force", "Matching also applies your saved hard exclusions. Removing an explicit exclusion here does not remove a saved preference.")
    OutlinedButton(onClick = actions.pantry, enabled = !form.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Check pantry reports") }
    picker.pantryItems.forEach { item ->
        InfoCard(item.name ?: "Ingredient label unavailable (${item.ingredientId})",
            "Reported: ${item.presence} · confirmation: ${item.confirmationStatus.valueOrNull() ?: "not recorded"}" +
                (item.confirmedAt.valueOrNull()?.let { "\nConfirmed at: $it" } ?: "\nNo confirmation time") +
                if (item.historical) "\nHistorical report, not confirmed for this meal." else "\nCheck it yourself before selecting it for this meal.")
    }
    if (picker.pantryHasMore) TextButton(enabled = !form.busy, onClick = actions.morePantry, modifier = Modifier.heightIn(min = 48.dp)) { Text("More pantry reports") }
    SectionTitle("03", "Let’s find your fit.")
    OutlinedButton(onClick = actions.context, enabled = !form.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Refresh preferences and pantry") }
    OutlinedButton(onClick = actions.saveDraft, enabled = !form.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save draft on this device") }
    Primary("Find a meal  ↗", !form.busy, actions.find)
    Text("Only your selected ingredients and choices are used. Free-text interpretation and new dietary presets aren’t connected yet.", style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
}

@Composable
private fun PlanCard(view: MealPlanPresentation, full: Boolean, choices: MealInputChoices,
    recipeAction: (() -> Unit)? = null, recipeEnabled: Boolean = false) {
    val recipe = view.recipe
    Surface(color = FeedMeColors.Lime, shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (view.historical) "RETAINED PLAN · HISTORICAL" else "PLAN SNAPSHOT", style = MaterialTheme.typography.labelMedium)
            Text(view.title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
            recipe?.summary?.valueOrNull()?.let { Text(it) }
            if (recipe != null) Text("${recipe.totalMinutes.jsonToken} min total · ${recipe.activeMinutes.jsonToken} min active · ${recipe.servings.jsonToken} servings")
            Text("Mode: ${view.plan.mode.valueOrNull() ?: "not resolved"} · status: ${view.plan.status}", style = MaterialTheme.typography.bodySmall)
            if (!full) recipeAction?.let { Primary("View recipe", recipeEnabled, it) }
        }
    }
    if (view.historical) InfoCard("Past facts, not a fresh check", "This retained plan does not prove current recipe rights, recall status, ingredient availability or permission to cook/save.")
    if (view.unresolvedIngredientCount > 0) InfoCard("Ingredient names still needed", "${view.unresolvedIngredientCount} ingredient label(s) are unavailable. No name has been guessed. Fetch the catalog labels before relying on this recipe.")
    if (view.plan.missingIngredients.isNotEmpty()) InfoCard("Missing items", view.plan.missingIngredients.joinToString("\n", transform = view::ingredientLine))
    if (view.reasons.isNotEmpty()) InfoCard("Why this fits", view.reasons.joinToString("\n"))
    if (view.changes.isNotEmpty()) InfoCard("What changed", view.changes.joinToString("\n"))
    if (full && recipe != null && view.recipeVisible) {
        SectionTitle("01", "Ingredients, exactly as planned.")
        recipe.ingredients.forEach { Text(view.ingredientLine(it)) }
        Text("Equipment: " + recipe.equipmentIds.joinToString { id -> choices.equipment.firstOrNull { it.id == id }?.label ?: "Unresolved equipment ($id)" })
        recipe.waitingMinutes.valueOrNull()?.let { Text("Waiting: ${it.jsonToken} min") }
        recipe.cleanupMinutes.valueOrNull()?.let { Text("Cleanup: ${it.jsonToken} min") }
        recipe.estimateNote.valueOrNull()?.let { InfoCard("Estimate notes", it) }
        SectionTitle("02", "Read the whole plan.")
        recipe.steps.forEach { step ->
            Surface(color = if (step.mandatorySafetyStep) FeedMeColors.Lilac else FeedMeColors.Line,
                shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Step ${step.position.jsonToken}" + if (step.mandatorySafetyStep) " · Required safety step" else "", style = MaterialTheme.typography.titleMedium)
                    Text(step.instruction)
                    step.durationSeconds.valueOrNull()?.let { Text("Suggested duration: ${it.jsonToken} seconds · manage timers separately", style = MaterialTheme.typography.bodySmall) }
                    if (step.requiredEquipmentIds.isNotEmpty()) Text("Uses: " + step.requiredEquipmentIds.joinToString { id ->
                        choices.equipment.firstOrNull { it.id == id }?.label ?: "Unresolved equipment ($id)"
                    }, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        FeedMeDetails("Recipe source & version") {
            Text("Plan revision ${view.plan.version.jsonToken} · recipe revision ${recipe.version.jsonToken}\nCatalog ${view.plan.catalogRevision}", style = MaterialTheme.typography.bodySmall)
            recipe.reviewerLabel.valueOrNull()?.let { Text("Recorded reviewer: $it", style = MaterialTheme.typography.bodySmall) }
            recipe.contentLicense.valueOrNull()?.let { Text("Recorded license: $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable internal fun Hero(eyebrow: String, title: String, copy: String) {
    Surface(color = FeedMeColors.Blue, shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(eyebrow, color = FeedMeColors.Lime, style = MaterialTheme.typography.labelMedium)
            Text(title, color = Color.White, style = MaterialTheme.typography.displayMedium, modifier = Modifier.semantics { heading() })
            Text(copy, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
@Composable internal fun Pill(text: String, color: Color) { Surface(color = color, shape = RoundedCornerShape(50)) {
    Text(text, Modifier.padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
} }
@Composable internal fun SectionTitle(number: String, title: String) {
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill(number, FeedMeColors.SoftBlue); Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
    }
}
@Composable internal fun InfoCard(title: String, text: String) {
    Surface(color = FeedMeColors.Surface, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium); Text(text, style = MaterialTheme.typography.bodyMedium, color = FeedMeColors.Muted)
        }
    }
}
@Composable private fun Input(label: String, value: String, onChange: (String) -> Unit, singleLine: Boolean = true) {
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = singleLine,
        shape = RoundedCornerShape(18.dp))
}
@Composable internal fun Primary(label: String, enabled: Boolean, action: () -> Unit) {
    Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) { Text(label) }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ChoiceSection(title: String, choices: List<MealInputChoice>, selected: List<String>, toggle: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (choices.isEmpty()) Text("No configured choices available.", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        choices.forEach { FilterChip(it.id in selected, { toggle(it.id) }, modifier = Modifier.heightIn(min = 48.dp), label = { Text(it.label) }) }
    }
    selected.filter { id -> choices.none { it.id == id } }.forEach { id -> Text("Retained unresolved choice ($id)", style = MaterialTheme.typography.bodySmall) }
}
/** Render entered refinements even while their optional editor is collapsed. No validation or
 * semantic default is inferred from empty or malformed text. */
internal fun mealRefinementSummary(values: MealFormValues, choices: MealInputChoices): String {
    val parts = listOfNotNull(values.totalMinutes.takeIf { it.isNotEmpty() }?.let { "Total minutes entered: $it" },
        values.activeMinutes.takeIf { it.isNotEmpty() }?.let { "Hands-on minutes entered: $it" },
        values.tasteTags.takeIf { it.isNotEmpty() }?.joinToString { id ->
            choices.tastes.firstOrNull { it.id == id }?.label ?: "Unresolved taste ($id)"
        })
    return if (parts.isEmpty()) "Time & taste: not set" else parts.joinToString(" · ")
}
private fun toggle(values: List<String>, id: String) = if (id in values) values.filterNot { it == id } else values + id
private fun modeLabel(mode: MealMode) = when (mode) { MealMode.AUTO -> "Find my fit"; MealMode.COOK -> "Cook"; MealMode.ASSEMBLE -> "Assemble"; MealMode.IMPROVE -> "Improve a meal" }
private fun energyLabel(energy: MealEnergy) = when (energy) { MealEnergy.ASSEMBLE -> "Barely any energy"; MealEnergy.LITTLE -> "A little effort"; MealEnergy.HAPPY -> "Happy to cook" }
private fun baseLabel(value: BasePreparation) = when (value) { BasePreparation.ALREADY_PREPARED -> "Already prepared"; BasePreparation.PARTIALLY_PREPARED -> "Partly prepared"; BasePreparation.UNKNOWN -> "Not sure" }
private fun pickerMessage(state: MealPickerPresentation) = when (state.issue) {
    IngredientPickerIssue.NONE -> ""
    IngredientPickerIssue.OFFLINE -> "You’re offline. Any retained labels are historical; no ingredient has been selected automatically."
    IngredientPickerIssue.INVALID_QUERY -> "Enter a supported ingredient search. Your text has not become an ingredient ID."
    IngredientPickerIssue.CACHE_EXPIRED -> "Previously fetched labels expired. Search again online."
    IngredientPickerIssue.PAGE_LIMIT -> "This lookup reached its page limit. Refine the search; unfetched items are not known to be absent."
    IngredientPickerIssue.CONTEXT_CHANGED -> "The catalog or pantry changed while loading. Refresh before using the results."
    IngredientPickerIssue.INVALID_REPLY -> "The lookup response could not be verified."
    IngredientPickerIssue.STORAGE -> "The ingredient-label cache could not be saved."
    IngredientPickerIssue.SESSION_UNAVAILABLE -> "Your session changed. Private lookup state is unavailable."
    IngredientPickerIssue.RETRY_LATER -> "Try again later" + (state.retryAfterSeconds?.let { " (server delay: $it seconds)." } ?: ".")
}
private fun failureText(reason: com.feedme.core.ports.FailureReason) = when (reason) {
    com.feedme.core.ports.FailureReason.INVALID_DATA -> "Check your input. Servings must be at least 0.1; minutes must be positive whole numbers. Selected IDs and choices must be supported."
    com.feedme.core.ports.FailureReason.OUTCOME_UNKNOWN -> "The previous action may have completed. Keep the original command and use its explicit retry."
    com.feedme.core.ports.FailureReason.OFFLINE -> "Reconnect when you’re ready. Nothing was submitted automatically."
    com.feedme.core.ports.FailureReason.CONFLICT -> "The action conflicts with current or pending state. Review your input and the retained request."
    com.feedme.core.ports.FailureReason.STORAGE_FAILURE -> "The device could not acknowledge this change. It is not marked saved."
    com.feedme.core.ports.FailureReason.RATE_LIMITED -> "Please wait before trying again; keep your original request."
    else -> "This action is unavailable. No account access, recipe permission or completed result is implied."
}
