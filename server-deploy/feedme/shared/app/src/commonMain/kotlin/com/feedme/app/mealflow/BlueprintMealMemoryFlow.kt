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
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.mealflow.memory.*
import com.feedme.sync.CommandPhase

/** Original personal-meal screens over the actual account controller. The parent owns the
 * operation scope; rendering, opening a local chooser and Back never dispatch a mutation.
 * A returned history row is an observation, not authority to reuse its recipe or contents. */
@Composable
internal fun BlueprintMealMemoryFlow(
    controller: MealMemoryController,
    state: MealMemoryState,
    busy: Boolean,
    isCurrent: () -> Boolean,
    onAction: (suspend () -> Unit) -> Unit,
    onBack: () -> Unit,
    onCookbook: ((MealMemoryState, () -> Boolean) -> Unit)? = null,
    onPreferences: ((MealMemoryState, () -> Boolean) -> Unit)? = null,
    ingredientLabels: Map<String, String> = emptyMap(),
    onOpenReuse: ((MealMemoryState, MealReuseSelection) -> Unit)? = null,
    reuseOpenStatus: String? = null,
    departureAllowed: (() -> Boolean)? = null,
    cookbookOpenStatus: String? = null,
    preferencesOpenStatus: String? = null,
    onSocial: ((BlueprintScreenId, MealMemoryState, () -> Boolean) -> Unit)? = null,
    socialOpenStatus: String? = null,
    onPausePersonalization: ((MealMemoryState, () -> Boolean) -> Unit)? = null,
    personalizationPreferences: BlueprintPreferenceReference? = null,
    personalizationPaused: Boolean? = null,
    personalizationStatus: String? = null,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> },
) {
    val hostCurrent by rememberUpdatedState(isCurrent)
    val departure by rememberUpdatedState(departureAllowed)
    var attached by remember(controller) { mutableStateOf(true) }
    DisposableEffect(controller) { onDispose { attached = false } }
    fun current() = attached && hostCurrent() && controller.isCurrent(state)
    fun act(work: suspend () -> Unit) {
        if (!busy && current()) onAction { if (current()) work() }
    }
    fun canDepart() = attached && controller.states.value === state &&
        state.screen != MealMemoryScreen.HIDDEN && state.phase != MealMemoryPhase.WORKING &&
        (departure?.invoke() ?: current())
    val loading = busy || state.phase in setOf(MealMemoryPhase.LOADING, MealMemoryPhase.WORKING)
    val editable = !loading && state.pending == null && state.review == null &&
        state.phase !in setOf(MealMemoryPhase.UNAVAILABLE, MealMemoryPhase.EXPIRED) && current()
    var tools by remember(controller, state.screen) { mutableStateOf(false) }
    var presentationVisit by remember(controller, state.screen) { mutableStateOf(Any()) }
    val renderedVisit = presentationVisit
    DisposableEffect(controller, state.screen) {
        onDispose { tools = false; presentationVisit = Any() }
    }
    var feedbackChooser by remember(controller, state.screen) { mutableStateOf<BlueprintFeedbackScope?>(null) }
    var ingredientChooser by remember(controller, state.screen) { mutableStateOf(false) }
    var reuseResults by remember(controller, state.screen) { mutableStateOf(false) }
    var displayedReuseReceipt by remember(controller, state.accountId, state.routeCookSessionId, state.routePlanId) {
        mutableStateOf<MealReuseOption?>(null)
    }
    var reuseSelectionIssue by remember(controller, state.accountId, state.routeCookSessionId, state.routePlanId) { mutableStateOf<String?>(null) }
    var ingredientQuery by remember(controller, state.accountId, state.routeCookSessionId, state.routePlanId) { mutableStateOf("") }
    var reuseEffort by remember(controller, state.accountId, state.routeCookSessionId, state.routePlanId) { mutableStateOf<BlueprintReuseEffort?>(null) }
    // Route IDs retain only this local editor's identity. Expiry may clear observed server
    // content, but must not erase an unsent note or implicitly save it.
    val feedback = remember(controller, state.accountId, state.routeCookSessionId, state.routePlanId) {
        MemoryFeedbackBuffer(state.feedback)
    }
    val memoryEdit = remember(controller, state.accountId, state.routeMemoryId) { MemoryStrengthBuffer() }
    var discardMemoryEdit by remember(controller, state.accountId, state.routeMemoryId) { mutableStateOf(false) }
    // This is deliberately presentation-only. The receiving owner checks the exact state
    // before leaving Memory; intentional leave must not be confused with stale authority.
    fun backgroundVisitCurrent() = attached && presentationVisit === renderedVisit && !tools &&
        feedbackChooser == null && !ingredientChooser && !discardMemoryEdit
    fun backgroundCurrent() = current() && backgroundVisitCurrent()
    fun toolsCurrent() = attached && presentationVisit === renderedVisit && tools && current()
    fun openTools() {
        if (backgroundCurrent()) { presentationVisit = Any(); tools = true }
    }
    fun closeTools() {
        if (attached && presentationVisit === renderedVisit && tools) {
            tools = false; presentationVisit = Any()
        }
    }
    fun toolAction(work: suspend () -> Unit) {
        if (!busy && toolsCurrent()) {
            tools = false; presentationVisit = Any()
            val admittedVisit = presentationVisit
            onAction {
                if (attached && presentationVisit === admittedVisit && !tools && current()) work()
            }
        }
    }
    fun canOpenCookbook() = onCookbook != null && backgroundCurrent() && !busy &&
        state.screen == MealMemoryScreen.MEMORY && state.review == null && state.pending == null &&
        state.phase !in setOf(MealMemoryPhase.LOADING, MealMemoryPhase.WORKING, MealMemoryPhase.UNAVAILABLE) &&
        !memoryEdit.dirty
    fun openCookbook() {
        if (canOpenCookbook()) onCookbook?.invoke(state, ::backgroundVisitCurrent)
    }
    fun canOpenPreferences() = onPreferences != null && backgroundCurrent() && !busy &&
        state.screen == MealMemoryScreen.MEMORY && state.review == null && state.pending == null &&
        state.phase !in setOf(MealMemoryPhase.LOADING, MealMemoryPhase.WORKING, MealMemoryPhase.UNAVAILABLE) &&
        !memoryEdit.dirty
    fun openPreferences() {
        if (canOpenPreferences()) onPreferences?.invoke(state, ::backgroundVisitCurrent)
    }
    fun canReviewPersonalization() = onPausePersonalization != null && backgroundCurrent() && !busy &&
        state.screen == MealMemoryScreen.MEMORY && state.review == null && state.pending == null &&
        state.phase !in setOf(MealMemoryPhase.LOADING, MealMemoryPhase.WORKING, MealMemoryPhase.UNAVAILABLE) && !memoryEdit.dirty
    fun reviewPersonalization() {
        if (canReviewPersonalization()) onPausePersonalization?.invoke(state, ::backgroundVisitCurrent)
    }
    val socialDestinations = setOf(BlueprintScreenId.TODAY, BlueprintScreenId.INBOX, BlueprintScreenId.PROFILE_PLATE)
    fun canOpenSocial(destination: BlueprintScreenId) = onSocial != null && destination in socialDestinations &&
        backgroundCurrent() && !busy && state.screen == MealMemoryScreen.MEMORY &&
        state.review == null && state.pending == null &&
        state.phase !in setOf(MealMemoryPhase.LOADING, MealMemoryPhase.WORKING, MealMemoryPhase.UNAVAILABLE) &&
        !memoryEdit.dirty
    fun openSocial(destination: BlueprintScreenId) {
        if (canOpenSocial(destination)) onSocial?.invoke(destination, state, ::backgroundVisitCurrent)
    }
    val back = {
        if (presentationVisit === renderedVisit && canDepart()) when {
            tools -> closeTools()
            feedbackChooser != null -> feedbackChooser = null
            ingredientChooser -> ingredientChooser = false
            state.screen == MealMemoryScreen.MEMORY_DETAIL && current() && memoryEdit.dirty &&
                state.review == null && state.pending == null -> discardMemoryEdit = true
            else -> onBack()
        }
    }
    platformBackHandler(canDepart(), back)
    // Losing private authority must redact content, not trap the user on a disabled Back.
    // Departure is local-only and is separately fenced by the retained host/controller.
    if (!current()) {
        val unavailable = BlueprintMemoryState(controls = BlueprintLibraryControls(
            enabled = canDepart(), contentUnavailable = true,
            message = "Taste notes are unavailable for this account. Back does not erase retained requests."))
        BlueprintMemoryScreen(unavailable, onAction = {}, onBack = back, onMore = {}, onNavigate = {})
        return
    }
    SideEffect {
        if (current()) {
            state.feedback?.let { feedback.observeReceipt(it,
                state.acknowledgedOperation in setOf("createFeedback", "updateFeedback")) }
            if (state.acknowledgedOperation == "deleteFeedback") feedback.acceptDeletion()
            state.selectedMemory?.let { memoryEdit.observe(it, state.acknowledgedOperation == "updateMemory") }
            if (state.screen == MealMemoryScreen.REUSE && state.acknowledgedOperation == "createReuseOptions")
                state.reuseOptions.firstOrNull()?.let { first ->
                    if (displayedReuseReceipt !== first) {
                        displayedReuseReceipt = first; reuseResults = true; reuseSelectionIssue = null
                    }
                }
        }
    }
    val message = memoryFlowMessage(state, loading)
    val review = state.review
    val pending = state.pending
    if (review != null) {
        val label = memoryOperationLabel(review.operationId, review.retryOriginal)
        val page = BlueprintConfirmationState(actionLabel = label,
            affectedSummary = memoryAffected(state, review.targetId, review.version),
            consequences = memoryReviewConsequences(review), canConfirm = current(),
            canCancel = current(), busy = loading,
            status = "Only this exact reviewed action will be submitted. Back does not submit or erase an original request.")
        BlueprintConfirmationScreen(page, heading = "Your choice.\nYour control.", confirmLabel = label,
            onConfirm = { rendered -> if (rendered === page && page.confirmEnabled && current() &&
                state.review === review) act { controller.confirm(review) } },
            onCancel = { rendered -> if (rendered === page && page.cancelEnabled) back() })
        return
    }
    if (pending != null) {
        val page = BlueprintConfirmationState(actionLabel = "Review original action",
            affectedSummary = memoryAffected(state, pending.targetId, pending.version),
            consequences = listOf("The original ${memoryOperationLabel(pending.operationId, false).lowercase()} request is retained.",
                "Its outcome may still be unresolved. A new request will not replace it.",
                "Back only leaves this screen; it does not cancel an already submitted request."),
            canConfirm = current() && pending.phase != CommandPhase.RECEIPT_READY,
            canCancel = current(), busy = loading,
            status = message + "\nAttempts recorded: ${pending.attempts?.toString() ?: "not yet observed"}.")
        BlueprintConfirmationScreen(page, heading = "Keep the\noriginal.", confirmLabel = "Review original retry",
            onConfirm = { rendered -> if (rendered === page && page.confirmEnabled) act { controller.prepareRetry(state) } },
            onCancel = { rendered -> if (rendered === page && page.cancelEnabled) back() },
            reviewLinks = { TextButton(enabled = !loading && current(),
                onClick = { act { controller.recoverReceipt(state) } }) { Text("Check the retained result") } })
        return
    }
    if (state.screen == MealMemoryScreen.REUSE && reuseResults && state.reuseOptions.isNotEmpty()) {
        BlueprintReuseRecommendations(state, loading, ::current, ingredientLabels,
            notice = listOfNotNull(reuseSelectionIssue, reuseOpenStatus).joinToString("\n\n").takeIf { it.isNotBlank() },
            onOpen = onOpenReuse?.let { open -> { option ->
                if (current() && !loading && state.reuseOptions.any { it === option }) {
                    when (val selected = controller.selectReuse(option.recipeVersionId, state)) {
                        is PortResult.Value -> if (current() && controller.isCurrent(selected.value)) {
                            reuseSelectionIssue = null; open(state, selected.value)
                        }
                        is PortResult.Failure -> reuseSelectionIssue = "This option is no longer available for this exact observation. Return to your inputs and refresh explicitly."
                    }
                }
            } },
            onBackToInputs = { if (current()) { reuseResults = false; reuseSelectionIssue = null } })
        return
    }
    when (state.screen) {
        MealMemoryScreen.FEEDBACK -> {
            val recipe = (state.plan?.recipeSnapshot as? WireField.Value)?.value
            val meal = state.cooking?.takeIf { it.status == "completed" && it.planId == state.plan?.id }
                ?.let { cook -> recipe?.let { BlueprintCompletedMeal(cook.id.value, it.title) } }
            val page = BlueprintFeedbackState(meal = meal, reaction = feedback.reaction,
                note = feedback.note, scope = feedback.scope, target = feedback.targetLabel(ingredientLabels),
                detailsExpanded = feedback.details, overflowExpanded = feedback.overflow,
                previousFeedbackId = state.feedback?.id, previousFeedbackVersion = state.feedback?.version?.toLongOrNull(),
                enabled = current(), allowEdit = editable && meal != null,
                allowedActions = buildSet {
                    add(BlueprintCookingAction.FEEDBACK_BACK); add(BlueprintCookingAction.SKIP_FEEDBACK)
                    add(BlueprintCookingAction.FEEDBACK_COOK)
                    if (editable && meal != null && feedback.hasInput) add(BlueprintCookingAction.SAVE_FEEDBACK)
                    if (editable && state.feedback != null) {
                        add(BlueprintCookingAction.EDIT_FEEDBACK); add(BlueprintCookingAction.REVIEW_REMOVE_FEEDBACK)
                    }
                }, status = listOfNotNull(message.takeIf { it.isNotBlank() }, feedback.signalSummary(), feedback.validationMessage,
                    if (state.feedback != null) "This feedback keeps its original target. Independent taste, effort and repeat signals are preserved when you edit one choice." else null).joinToString("\n\n"),
                privacyNote = "Private account feedback",
                memoryNote = "Only the taste, effort and repeat choices you give can shape taste notes. Free-text notes are not interpreted as new preferences. Saving feedback does not share a post.")
            BlueprintFeedbackScreen(page, onAction = { rendered, action ->
                if (rendered === page && current() && page.allows(action)) when (action) {
                    BlueprintCookingAction.FEEDBACK_BACK, BlueprintCookingAction.SKIP_FEEDBACK,
                    BlueprintCookingAction.FEEDBACK_COOK -> back()
                    BlueprintCookingAction.SAVE_FEEDBACK -> feedback.input()?.let { input -> act { controller.prepareFeedback(input, state) } }
                    BlueprintCookingAction.EDIT_FEEDBACK -> { feedback.useSavedTarget(); feedback.details = true; feedback.overflow = false }
                    BlueprintCookingAction.REVIEW_REMOVE_FEEDBACK -> act { controller.prepareDeleteFeedback(state) }
                    else -> Unit
                }
            }, onEdit = { rendered, edit ->
                if (rendered === page && current()) when (edit) {
                    is BlueprintFeedbackEdit.Details -> feedback.details = edit.expanded
                    is BlueprintFeedbackEdit.Overflow -> {
                        feedback.overflow = edit.expanded
                        if (edit.expanded) openTools() else closeTools()
                    }
                    is BlueprintFeedbackEdit.Reaction -> if (page.canEdit) feedback.choose(edit.value)
                    is BlueprintFeedbackEdit.Note -> if (page.canEdit) feedback.editNote(edit.value)
                    is BlueprintFeedbackEdit.Scope -> if (page.canEdit && state.feedback == null) {
                        if (edit.value == BlueprintFeedbackScope.WHOLE_MEAL) feedback.selectTarget(MealFeedbackTarget.WholeMeal)
                        else feedbackChooser = edit.value
                    }
                    is BlueprintFeedbackEdit.Target -> if (page.canEdit && state.feedback == null &&
                        feedback.scope != BlueprintFeedbackScope.WHOLE_MEAL) feedbackChooser = feedback.scope
                }
            })
        }
        MealMemoryScreen.MEMORY -> {
            val context = state.accountId?.let { BlueprintLibraryContext(it, state.revision) }
            val page = BlueprintMemoryState(context = context,
                memories = state.memories.map { memoryCard(it, ingredientLabels) },
                preferences = personalizationPreferences, personalizationPaused = personalizationPaused,
                pauseReviewAvailable = canReviewPersonalization(),
                controls = BlueprintLibraryControls(enabled = backgroundCurrent(), loading = loading,
                    contentUnavailable = state.phase in setOf(MealMemoryPhase.UNAVAILABLE, MealMemoryPhase.ERROR, MealMemoryPhase.EXPIRED),
                    allowedActions = buildSet {
                        if (editable) add(BlueprintLibraryAction.INSPECT_MEMORY)
                        if (canOpenCookbook()) add(BlueprintLibraryAction.RETURN_TO_SAVES)
                        if (canOpenPreferences()) add(BlueprintLibraryAction.EDIT_FOOD_PREFERENCES)
                        if (canReviewPersonalization()) add(BlueprintLibraryAction.PAUSE_PERSONALIZATION)
                    }, allowedNavigation = buildSet {
                        add(BlueprintScreenId.HOME)
                        if (canOpenCookbook()) add(BlueprintScreenId.COOKBOOK)
                        addAll(socialDestinations.filter(::canOpenSocial))
                    }, message = listOfNotNull(message, cookbookOpenStatus, preferencesOpenStatus, socialOpenStatus,
                        personalizationStatus ?: "Pause learned suggestions opens a current settings check and explicit review. Nothing changes just by opening it.")
                        .filter { it.isNotBlank() }.joinToString("\n\n")))
            BlueprintMemoryScreen(page, onAction = { intent ->
                if (backgroundCurrent() && intent.context === context) when (intent.action) {
                    BlueprintLibraryAction.INSPECT_MEMORY -> if (editable) state.memories.singleOrNull {
                        it.id == intent.memory?.id && it.version == intent.memory?.version
                    }?.let { memory -> act { if (backgroundCurrent()) controller.openMemory(memory.id, state) } }
                    BlueprintLibraryAction.RETURN_TO_SAVES -> openCookbook()
                    BlueprintLibraryAction.EDIT_FOOD_PREFERENCES -> openPreferences()
                    BlueprintLibraryAction.PAUSE_PERSONALIZATION -> reviewPersonalization()
                    else -> Unit
                }
            }, onBack = back, onMore = ::openTools, onNavigate = { destination ->
                if (backgroundCurrent() && page.controls.permitsNavigation(destination)) when (destination) {
                    BlueprintScreenId.HOME -> back()
                    BlueprintScreenId.COOKBOOK -> openCookbook()
                    BlueprintScreenId.TODAY, BlueprintScreenId.INBOX, BlueprintScreenId.PROFILE_PLATE -> openSocial(destination)
                    else -> Unit
                }
            })
        }
        MealMemoryScreen.MEMORY_DETAIL -> {
            val selected = state.selectedMemory
            val context = state.accountId?.let { BlueprintLibraryContext(it, state.revision) }
            val page = BlueprintMemoryDetailState(context = context,
                memory = selected?.let { memoryCard(it, ingredientLabels) }, strength = memoryEdit.choice,
                controls = BlueprintLibraryControls(enabled = backgroundCurrent(), loading = loading,
                    contentUnavailable = state.phase in setOf(MealMemoryPhase.UNAVAILABLE, MealMemoryPhase.ERROR, MealMemoryPhase.EXPIRED),
                    allowedActions = if (editable && selected != null) buildSet {
                        add(BlueprintLibraryAction.FORGET_MEMORY)
                        if (memoryEdit.choice != null && memoryEdit.choice != memoryStrength(selected) &&
                            memoryEdit.sourceVersion == selected.version) add(BlueprintLibraryAction.UPDATE_MEMORY)
                    } else emptySet(), editableFields = if (editable) setOf("strength") else emptySet(),
                    message = listOf(message, if (memoryEdit.dirty && selected != null && memoryEdit.sourceVersion != selected.version)
                        "Your unsaved choice is retained from an earlier observation. Choose its strength again to review a change against the current version."
                        else "").filter { it.isNotBlank() }.joinToString("\n\n").takeIf { it.isNotBlank() }))
            BlueprintMemoryDetailScreen(page, onStrength = { observedContext, ref, value ->
                if (backgroundCurrent() && editable && observedContext === context && ref == page.memory?.reference && selected != null)
                    memoryEdit.choose(value, selected.version)
            }, onAction = { intent ->
                if (backgroundCurrent() && editable && intent.context === context && intent.memory == page.memory?.reference) when (intent.action) {
                    BlueprintLibraryAction.UPDATE_MEMORY -> intent.strength?.let { chosen -> act {
                        if (backgroundCurrent()) controller.prepareUpdateMemory(chosen != BlueprintMemoryStrength.NEUTRAL, when (chosen) {
                            BlueprintMemoryStrength.PREFER -> "prefer"
                            BlueprintMemoryStrength.NEUTRAL -> "neutral"
                            BlueprintMemoryStrength.AVOID -> "show_less"
                        }, state)
                    } }
                    BlueprintLibraryAction.FORGET_MEMORY -> act { if (backgroundCurrent()) controller.prepareDeleteMemory(state) }
                    else -> Unit
                }
            }, onBack = back, onMore = ::openTools, onNavigate = {})
        }
        MealMemoryScreen.REUSE -> {
            val page = BlueprintReuseState(ingredient = state.selectedIngredient?.name ?: ingredientQuery,
                effort = reuseEffort, enabled = current(), allowEdit = editable,
                allowedActions = buildSet {
                    add(BlueprintCookingAction.REUSE_BACK); add(BlueprintCookingAction.NOT_NOW)
                    add(BlueprintCookingAction.REUSE_COOK)
                    if (editable && state.reuseEnabled && state.selectedIngredient != null && reuseEffort != null)
                        add(BlueprintCookingAction.FIND_NEXT_USE)
                }, status = listOf(message, if (!state.reuseEnabled)
                    "Next-use recommendations are not connected in this configuration. No replacement meal has been generated."
                    else "Select an ingredient explicitly from the ingredient field. Find its next act reviews a separate next-use request; it does not change your current meal.",
                    if (state.reuseOptions.isNotEmpty()) "${state.reuseOptions.size} next-use option(s) were returned. Open the ingredient field to view the actual results." else "")
                    .filter { it.isNotBlank() }.joinToString("\n\n"))
            BlueprintReuseScreen(page, onAction = { rendered, action ->
                if (rendered === page && current() && page.allows(action)) when (action) {
                    BlueprintCookingAction.REUSE_BACK, BlueprintCookingAction.NOT_NOW, BlueprintCookingAction.REUSE_COOK -> back()
                    BlueprintCookingAction.FIND_NEXT_USE -> reuseEffort?.let { effort -> act {
                        controller.prepareReuse(if (effort == BlueprintReuseEffort.NONE) MealReuseEffort.NONE else MealReuseEffort.A_LITTLE, state)
                    } }
                    else -> Unit
                }
            }, onEdit = { rendered, edit ->
                if (rendered === page && current() && page.canEdit) when (edit) {
                    is BlueprintReuseEdit.Ingredient -> { ingredientQuery = edit.value; ingredientChooser = true }
                    is BlueprintReuseEdit.Effort -> reuseEffort = edit.value
                }
            })
        }
        MealMemoryScreen.HIDDEN -> Unit
    }
    if (tools && current()) FeedMeTheme {
        AlertDialog(onDismissRequest = ::closeTools, title = { Text("Your meal notes") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(message.ifBlank { "Nothing is sent until you choose an action." })
                cookbookOpenStatus?.takeIf { state.screen == MealMemoryScreen.MEMORY && it.isNotBlank() }?.let { Text(it) }
                preferencesOpenStatus?.takeIf { state.screen == MealMemoryScreen.MEMORY && it.isNotBlank() }?.let { Text(it) }
                socialOpenStatus?.takeIf { state.screen == MealMemoryScreen.MEMORY && it.isNotBlank() }?.let { Text(it) }
                personalizationStatus?.takeIf { state.screen == MealMemoryScreen.MEMORY && it.isNotBlank() }?.let { Text(it) }
                if (state.screen != MealMemoryScreen.FEEDBACK || state.feedback == null)
                    TextButton(enabled = !loading && toolsCurrent(), onClick = { toolAction { controller.refresh(state) } }) { Text("Refresh this observation") }
                else Text("The retained feedback result is editable here. Refreshing a meal does not fetch a newer feedback version.")
                if (state.screen == MealMemoryScreen.MEMORY && state.hasMore) TextButton(enabled = !loading && !state.pageLimitReached && toolsCurrent(),
                    onClick = { toolAction { controller.loadMore(state) } }) { Text("Load more taste notes") }
                if (state.screen == MealMemoryScreen.FEEDBACK && state.feedback != null) TextButton(enabled = editable && toolsCurrent(),
                    onClick = { toolAction { controller.prepareDeleteFeedback(state) } }) { Text("Review removing this feedback") }
                if (state.pageLimitReached) Text("This view reached its configured page limit. No additional rows have been guessed.")
            }
        }, confirmButton = { TextButton(onClick = ::closeTools) { Text("Done") } })
    }
    if (discardMemoryEdit && current()) FeedMeTheme {
        AlertDialog(onDismissRequest = { discardMemoryEdit = false },
            title = { Text("Leave this change?") },
            text = { Text("Your unsaved strength choice will be discarded. Your saved taste note and any retained request stay unchanged.") },
            confirmButton = { TextButton(enabled = canDepart(), onClick = {
                if (canDepart()) { discardMemoryEdit = false; memoryEdit.discard(); onBack() }
            }) { Text("Discard change") } },
            dismissButton = { TextButton(onClick = { discardMemoryEdit = false }) { Text("Keep editing") } })
    }
    val targetScope = feedbackChooser
    if (targetScope != null && current()) {
        val recipe = (state.plan?.recipeSnapshot as? WireField.Value)?.value
        val options: List<Pair<String, MealFeedbackTarget>> = when (targetScope) {
            BlueprintFeedbackScope.INGREDIENT -> recipe?.ingredients.orEmpty().map { amount ->
                (ingredientLabels[amount.ingredientId.value] ?: "Ingredient ${amount.ingredientId.value}") to MealFeedbackTarget.Ingredient(amount.ingredientId.value)
            }
            BlueprintFeedbackScope.TEXTURE -> listOf("Crunch" to "crunch", "Fresh" to "fresh", "Creamy" to "creamy", "Heat" to "heat")
                .filter { it.second in recipe?.tasteTags.orEmpty() }.map { it.first to MealFeedbackTarget.Taste(it.second) }
            BlueprintFeedbackScope.PREPARATION_EFFORT -> listOf("Chopping" to "chopping", "Active cooking" to "activeCooking", "Cleanup" to "cleanup")
                .map { it.first to MealFeedbackTarget.Preparation(it.second) }
            BlueprintFeedbackScope.WHOLE_MEAL -> emptyList()
        }
        FeedMeTheme { AlertDialog(onDismissRequest = { feedbackChooser = null }, title = { Text("Choose the exact target") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This choice applies only to the selected target, not every ingredient in your meal.")
                if (options.isEmpty()) Text("No supported targets are available for this meal.")
                options.forEach { (label, target) -> TextButton(enabled = editable, onClick = {
                    if (current() && editable && state.feedback == null) { feedback.selectTarget(target); feedbackChooser = null }
                }) { Text(label) } }
            }
        }, confirmButton = { TextButton(onClick = { feedbackChooser = null }) { Text("Back") } }) }
    }
    if (ingredientChooser && current()) FeedMeTheme {
        AlertDialog(onDismissRequest = { ingredientChooser = false }, title = { Text("Choose what you have") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("A recipe ingredient is not proof that you still have it. Choose only food you have and know is safe.")
                OutlinedTextField(ingredientQuery, { if (editable) ingredientQuery = it }, label = { Text("Ingredient name") }, enabled = editable)
                TextButton(enabled = editable && state.reuseEnabled && ingredientQuery.isNotBlank() && ingredientQuery.length <= 100, onClick = {
                    val query = ingredientQuery
                    act { controller.searchIngredients(query, state) }
                }) { Text("Search ingredients") }
                state.ingredients.forEach { ingredient -> TextButton(enabled = editable, onClick = {
                    if (current() && editable) {
                        ingredientChooser = false
                        act { controller.selectIngredient(ingredient.id, state) }
                    }
                }) { Text(ingredient.name) } }
                if (state.ingredientQuery != null && state.ingredients.isEmpty() && !loading)
                    Text("No matching ingredients were returned for that search.")
                if (state.reuseOptions.isNotEmpty()) TextButton(onClick = { ingredientChooser = false; reuseResults = true }) { Text("View returned next-use options") }
                TextButton(enabled = !loading, onClick = { act { controller.refresh(state) } }) { Text("Refresh meal context") }
                if (message.isNotBlank()) Text(message)
            }
        }, confirmButton = { TextButton(onClick = { ingredientChooser = false }) { Text("Back") } })
    }
}

private class MemoryFeedbackBuffer(snapshot: MealFeedbackSnapshot?) {
    var taste by mutableStateOf(snapshot?.taste)
    var effort by mutableStateOf(snapshot?.effort)
    var makeAgain by mutableStateOf(snapshot?.makeAgain)
    var note by mutableStateOf(snapshot?.note.orEmpty())
        private set
    var target by mutableStateOf<MealFeedbackTarget?>(if (snapshot == null) MealFeedbackTarget.WholeMeal else memoryFeedbackTarget(snapshot.document))
        private set
    var reaction by mutableStateOf(when {
        snapshot?.taste == "loved" -> BlueprintFeedbackReaction.LOVED_IT
        snapshot?.taste == "notForMe" -> BlueprintFeedbackReaction.NOT_MY_TASTE
        snapshot?.effort == "tooMuch" -> BlueprintFeedbackReaction.TOO_MUCH_PREP
        else -> null
    })
    var details by mutableStateOf(false)
    var overflow by mutableStateOf(false)
    private var receiptKey = snapshot?.let { "${it.id}:${it.version}" }
    private var tasteEdited = false
    private var effortEdited = false
    private var noteEdited = false
    private var targetEdited = false
    private var savedTarget = snapshot?.let { memoryFeedbackTarget(it.document) }
    private var targetConflict by mutableStateOf(false)
    /** First durable hydration is not a new save. It fills only unedited fields; the user's
     * unsent text survives the read. A different saved target cannot silently replace a choice. */
    fun observeReceipt(snapshot: MealFeedbackSnapshot, acknowledged: Boolean) {
        if (acknowledged) { acceptReceipt(snapshot); return }
        if (receiptKey != null) return
        receiptKey = "${snapshot.id}:${snapshot.version}"
        if (!tasteEdited) taste = snapshot.taste
        if (!effortEdited) effort = snapshot.effort
        makeAgain = snapshot.makeAgain
        if (!noteEdited) note = snapshot.note.orEmpty()
        savedTarget = memoryFeedbackTarget(snapshot.document)
        if (!targetEdited) target = savedTarget
        else targetConflict = memoryFeedbackTargetKey(target) != memoryFeedbackTargetKey(savedTarget)
        if (!tasteEdited && !effortEdited) updateReaction()
    }
    private fun acceptReceipt(snapshot: MealFeedbackSnapshot) {
        val key = "${snapshot.id}:${snapshot.version}"
        if (receiptKey == key) return
        receiptKey = key
        taste = snapshot.taste; effort = snapshot.effort; makeAgain = snapshot.makeAgain
        note = snapshot.note.orEmpty(); target = memoryFeedbackTarget(snapshot.document)
        savedTarget = target; targetConflict = false
        tasteEdited = false; effortEdited = false; noteEdited = false; targetEdited = false
        updateReaction()
    }
    private fun updateReaction() {
        reaction = when {
            taste == "loved" -> BlueprintFeedbackReaction.LOVED_IT
            taste == "notForMe" -> BlueprintFeedbackReaction.NOT_MY_TASTE
            effort == "tooMuch" -> BlueprintFeedbackReaction.TOO_MUCH_PREP
            else -> null
        }
    }
    fun acceptDeletion() {
        if (receiptKey == "deleted") return
        receiptKey = "deleted"
        taste = null; effort = null; makeAgain = null; note = ""; reaction = null
        target = MealFeedbackTarget.WholeMeal; savedTarget = null; targetConflict = false
        tasteEdited = false; effortEdited = false; noteEdited = false; targetEdited = false
    }
    fun editNote(value: String) { note = value; noteEdited = true }
    fun selectTarget(value: MealFeedbackTarget) { target = value; targetEdited = true }
    fun useSavedTarget() {
        if (receiptKey != null && receiptKey != "deleted") {
            target = savedTarget; targetConflict = false; targetEdited = false
        }
    }
    val scope get() = when (target) {
        MealFeedbackTarget.WholeMeal -> BlueprintFeedbackScope.WHOLE_MEAL
        is MealFeedbackTarget.Ingredient -> BlueprintFeedbackScope.INGREDIENT
        is MealFeedbackTarget.Taste -> BlueprintFeedbackScope.TEXTURE
        is MealFeedbackTarget.Preparation -> BlueprintFeedbackScope.PREPARATION_EFFORT
        null -> null
    }
    val validationMessage get() = when {
        targetConflict -> "An existing feedback result was restored for a different target (${memoryFeedbackTargetKey(savedTarget) ?: "unavailable"}). Choose Edit previous feedback to keep its original target; your unsent note and signal choices will remain."
        target == null -> "The exact existing feedback target cannot be edited from this screen. It has not been replaced with a whole-meal target."
        note.length > 300 -> "Keep your optional note to 300 characters. Your text has not been truncated."
        note.any { it.isISOControl() && it != '\n' } -> "Your note contains unsupported control characters. Remove them before saving."
        else -> null
    }
    val hasInput get() = validationMessage == null && (taste != null || effort != null || makeAgain != null || note.isNotBlank())
    fun choose(value: BlueprintFeedbackReaction) {
        reaction = value
        when (value) {
            BlueprintFeedbackReaction.LOVED_IT -> { taste = "loved"; tasteEdited = true }
            BlueprintFeedbackReaction.NOT_MY_TASTE -> { taste = "notForMe"; tasteEdited = true }
            BlueprintFeedbackReaction.TOO_MUCH_PREP -> { effort = "tooMuch"; effortEdited = true }
        }
    }
    fun input() = if (hasInput) target?.let { MealFeedbackInput(it, taste, effort, makeAgain, note) } else null
    fun targetLabel(labels: Map<String, String>) = when (val selected = target) {
        MealFeedbackTarget.WholeMeal -> "Whole meal"
        is MealFeedbackTarget.Ingredient -> labels[selected.ingredientId] ?: "Ingredient ${selected.ingredientId}"
        is MealFeedbackTarget.Taste -> selected.tag
        is MealFeedbackTarget.Preparation -> selected.tag
        null -> "Exact target unavailable"
    }
    fun signalSummary(): String? = buildList {
        taste?.let { add("Taste: " + when (it) { "loved" -> "loved it"; "okay" -> "okay"; "notForMe" -> "not my taste"; else -> it }) }
        effort?.let { add("Effort: " + when (it) { "easy" -> "easy"; "manageable" -> "manageable"; "tooMuch" -> "too much"; else -> it }) }
        makeAgain?.let { add("Make again: ${if (it) "yes" else "no"}") }
    }.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun memoryFeedbackTargetKey(target: MealFeedbackTarget?): String? = when (target) {
    MealFeedbackTarget.WholeMeal -> "whole meal"
    is MealFeedbackTarget.Ingredient -> "ingredient ${target.ingredientId}"
    is MealFeedbackTarget.Taste -> "taste ${target.tag}"
    is MealFeedbackTarget.Preparation -> "preparation ${target.tag}"
    null -> null
}

private class MemoryStrengthBuffer {
    var choice by mutableStateOf<BlueprintMemoryStrength?>(null)
        private set
    var sourceVersion by mutableStateOf<String?>(null)
        private set
    var dirty by mutableStateOf(false)
        private set
    private var receiptVersion: String? = null
    fun observe(snapshot: MealMemorySnapshot, acknowledged: Boolean) {
        val newReceipt = acknowledged && receiptVersion != snapshot.version
        if (sourceVersion == null || !dirty || newReceipt) {
            choice = memoryStrength(snapshot); sourceVersion = snapshot.version; dirty = false
        }
        if (acknowledged) receiptVersion = snapshot.version
    }
    fun choose(value: BlueprintMemoryStrength, version: String) {
        choice = value; sourceVersion = version; dirty = true
    }
    fun discard() { choice = null; sourceVersion = null; dirty = false }
}

private fun memoryFeedbackTarget(document: WireDocument): MealFeedbackTarget? {
    val target = memoryWireField(document, "target") ?: return null
    return when (memoryWireText(target, "kind")) {
        "cookSession" -> MealFeedbackTarget.WholeMeal
        "ingredient" -> memoryWireText(target, "resourceId")?.let { MealFeedbackTarget.Ingredient(it) }
        "taste" -> memoryWireText(target, "tag")?.let { MealFeedbackTarget.Taste(it) }
        "preparation" -> memoryWireText(target, "tag")?.let { MealFeedbackTarget.Preparation(it) }
        else -> null
    }
}

private fun memoryStrength(memory: MealMemorySnapshot): BlueprintMemoryStrength = if (!memory.enabled)
    BlueprintMemoryStrength.NEUTRAL else when (memory.value) {
        "prefer" -> BlueprintMemoryStrength.PREFER
        "show_less" -> BlueprintMemoryStrength.AVOID
        else -> BlueprintMemoryStrength.NEUTRAL
    }

private fun memoryCard(memory: MealMemorySnapshot, labels: Map<String, String>): BlueprintLibraryMemory {
    val sourceIds = memoryWireStrings(memory.document, "sourceFeedbackIds")
    val context = memoryWireField(memory.document, "context")
    val tags = buildList {
        add(if (memory.enabled) "Enabled" else "Disabled")
        context?.let { source ->
            memoryWireText(source, "ingredientId")?.let { add(labels[it] ?: "Ingredient $it") }
            memoryWireText(source, "tasteTag")?.let(::add)
            memoryWireText(source, "effortAspect")?.let(::add)
            memoryWireText(source, "recipeVersionId")?.let { add("Recipe version $it") }
        }
    }
    return BlueprintLibraryMemory(BlueprintMemoryReference(memory.id, memory.version), memory.label,
        when (memory.kind) { "taste" -> "Taste"; "effort" -> "Effort"; "repeat" -> "Make again"; else -> memory.kind },
        sourceDescription = "From ${sourceIds.size} explicit feedback source${if (sourceIds.size == 1) "" else "s"}. " +
            "This is an account observation; it does not prove that the source recipe is currently available.", tags = tags)
}

private fun memoryFlowMessage(state: MealMemoryState, loading: Boolean): String = buildList {
    if (loading) add("Checking your selected action… No new result is confirmed yet.")
    state.failureReason?.let { reason -> add(when (reason) {
        FailureReason.INVALID_DATA -> "Check the selected target and choices. No unsupported target or altered request was accepted."
        FailureReason.OUTCOME_UNKNOWN -> "The result is not known yet. Keep the retained original; do not send a replacement."
        FailureReason.OFFLINE -> "You’re offline. Reconnect and choose an action explicitly."
        FailureReason.CONFLICT -> "This observation changed or conflicts with a retained action. Refresh or review that original action."
        FailureReason.STORAGE_FAILURE -> "This device could not acknowledge the result. It is not marked saved."
        FailureReason.RATE_LIMITED -> "Please wait before trying again. Your original action is retained when present."
        FailureReason.NOT_CONFIGURED -> "This feature is not connected in the current configuration. No result was fabricated."
        else -> "This action is unavailable in the current connection. No completed result is implied."
    }) }
    if (state.phase == MealMemoryPhase.EXPIRED) add("This observation expired. Refresh explicitly before changing it.")
    state.acknowledgedOperation?.let { add("Confirmed result: ${memoryOperationLabel(it, false)}.") }
}.joinToString("\n\n")

private fun memoryOperationLabel(operation: String, retry: Boolean): String = (if (retry) "Retry original · " else "") + when (operation) {
    "createFeedback" -> "Save feedback"
    "updateFeedback" -> "Update feedback"
    "deleteFeedback" -> "Remove feedback"
    "updateMemory" -> "Update taste note"
    "deleteMemory" -> "Forget taste note"
    "createReuseOptions" -> "Find next-use options"
    else -> "Review meal action"
}

private fun memoryAffected(state: MealMemoryState, targetId: String?, version: String?): String = buildList {
    (state.plan?.recipeSnapshot as? WireField.Value)?.value?.title?.let(::add)
    state.selectedMemory?.label?.let(::add)
    targetId?.let { add("Selected item: $it") }
    version?.let { add("Observed version: $it") }
    if (isEmpty()) add("Your selected account meal action")
}.joinToString("\n")

private fun memoryReviewConsequences(review: MealMemoryReview): List<String> = buildList {
    add(when (review.operationId) {
        "deleteFeedback" -> "Remove only this feedback at its observed version. Taste-note projection may need a later refresh."
        "deleteMemory" -> "Forget this exact taste note. This does not delete its source feedback, change exclusions, or delete a recipe."
        "updateMemory" -> "Change how this specific taste note is used. It cannot override ingredient exclusions."
        "createReuseOptions" -> "Ask for next-use options using the reviewed ingredient and meal limits. This does not select a meal, start cooking, or verify freshness."
        else -> "Save your explicit private feedback. This does not publish a photo or a social post."
    })
    review.request?.let { body ->
        listOf("taste", "effort", "value", "note").forEach { key -> memoryWireText(body, key)?.let { add("${key.replaceFirstChar { it.uppercase() }}: $it") } }
        listOf("makeAgain", "enabled").forEach { key -> memoryWireField(body, key)?.booleanOrNull()?.let { add("$key: ${if (it) "yes" else "no"}") } }
        memoryWireField(body, "target")?.let { target ->
            memoryWireText(target, "kind")?.let { add("Target: $it") }
            memoryWireText(target, "resourceId")?.let { add("Exact target: $it") }
            memoryWireText(target, "tag")?.let { add("Target choice: $it") }
        }
        if (review.operationId == "createReuseOptions") {
            val ids = memoryWireStrings(body, "ingredientIds")
            if (ids.isNotEmpty()) add("Selected ingredient IDs: ${ids.joinToString()}")
            memoryWireField(body, "constraints")?.let { constraints ->
                memoryWireText(constraints, "energy")?.let { add("Effort choice: $it") }
                listOf("totalMinutes", "activeMinutes", "cleanupMinutes", "servings").forEach { key ->
                    memoryWireField(constraints, key)?.numberTokenOrNull()?.let { add("$key: $it") }
                }
            }
        }
    }
    if (review.retryOriginal) add("Retry uses the same retained original identity and request, not the latest edited form.")
}

private fun memoryWireField(document: WireDocument, name: String): WireDocument? =
    (document.field(name) as? WireField.Value)?.value
private fun memoryWireText(document: WireDocument, name: String): String? = memoryWireField(document, name)?.stringOrNull()
private fun memoryWireStrings(document: WireDocument, name: String): List<String> =
    memoryWireField(document, name)?.elementsOrNull().orEmpty().mapNotNull { it.stringOrNull() }
