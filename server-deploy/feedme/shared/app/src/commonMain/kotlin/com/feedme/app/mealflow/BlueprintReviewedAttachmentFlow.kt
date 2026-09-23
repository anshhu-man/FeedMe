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
import com.feedme.contracts.WireField
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.mealflow.social.*

private enum class AttachmentPage { ATTACH, REVIEW, AUDIENCE, PERMISSION }
private enum class AttachmentPicker { SAVED, CIRCLES }

/** Original attachment/audience/copy-choice screens. Partial choices remain in RAM until
 * the actual coordinator retains one complete canonical composer edit. No server Save or
 * Publish is dispatched here; that later action has its own authority and original receipt. */
@Composable
internal fun BlueprintReviewedAttachmentFlow(
    controller: ReviewedAttachmentController,
    state: ReviewedAttachmentState,
    busy: Boolean,
    retaining: Boolean,
    isCurrent: () -> Boolean,
    onAction: (suspend () -> Unit) -> Unit,
    onRetentionAction: (suspend () -> Unit) -> Unit,
    onBack: () -> Unit,
    onRetained: (PostDraftState) -> Unit,
    recentCookSessionId: String? = null,
    recentPlanId: String? = null,
    ingredientLabels: Map<String, String> = emptyMap(),
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> },
) {
    val liveCurrent by rememberUpdatedState(isCurrent)
    var attached by remember(controller) { mutableStateOf(true) }
    DisposableEffect(controller) { onDispose { attached = false } }
    fun current() = attached && liveCurrent() && controller.isCurrent(state)
    fun act(retention: Boolean = false, work: suspend () -> Unit) {
        if (!busy && current()) {
            val dispatch = if (retention) onRetentionAction else onAction
            dispatch { if (current()) work() }
        }
    }
    val ready = state.phase == ReviewedAttachmentPhase.READY && current()
    val loading = busy || state.phase == ReviewedAttachmentPhase.LOADING
    val editable = ready && !loading
    var page by remember(controller, state.clientDraftId) { mutableStateOf(AttachmentPage.ATTACH) }
    var picker by remember(controller, state.clientDraftId) { mutableStateOf<AttachmentPicker?>(null) }
    var tools by remember(controller, state.clientDraftId) { mutableStateOf(false) }
    var toolsVisit by remember(controller, state.clientDraftId) { mutableStateOf(Any()) }
    val expectedToolsVisit = toolsVisit
    DisposableEffect(controller, state.clientDraftId) { onDispose { tools = false; toolsVisit = Any() } }
    // The visit survives the intended owner read, but never a local close/reopen or disposal.
    // Result handling checks this local lifetime plus the returned owner state, not the
    // obsolete pre-read state captured by current().
    fun sameToolsVisit() = attached && tools && toolsVisit === expectedToolsVisit
    fun toolsCurrent() = sameToolsVisit() && current()
    fun closeTools() { if (sameToolsVisit()) { tools = false; toolsVisit = Any() } }
    fun toolRead(work: suspend () -> Unit) {
        if (toolsCurrent()) act { if (toolsCurrent()) work() }
    }
    var leaveReview by remember(controller, state.clientDraftId) { mutableStateOf(false) }
    var changed by remember(controller, state.clientDraftId) { mutableStateOf(false) }
    var confirmedSource by remember(controller, state.clientDraftId) { mutableStateOf<ReviewedAttachmentSource?>(null) }
    var onlyYou by remember(controller, state.clientDraftId) { mutableStateOf<Boolean?>(null) }
    var selectedCircleIds by remember(controller, state.clientDraftId) { mutableStateOf<List<String>>(emptyList()) }
    var keep by remember(controller, state.clientDraftId) { mutableStateOf<Boolean?>(null) }
    var allow by remember(controller, state.clientDraftId) { mutableStateOf<Boolean?>(null) }
    var acceptedDisclosure by remember(controller, state.clientDraftId) { mutableStateOf<PublicationDisclosure?>(null) }
    var hydrated by remember(controller, state.clientDraftId) { mutableStateOf(false) }
    SideEffect {
        // Restore actual previous choices once. Expiry/refetch never silently resets edits.
        if (current() && !hydrated && state.selection != null) {
            state.selection?.choices?.let { prior ->
                if (!changed) {
                    onlyYou = prior.audience === PublicationAudience.OnlyYou
                    selectedCircleIds = (prior.audience as? PublicationAudience.Circles)?.orderedCircleIds.orEmpty()
                    keep = prior.keepOnPlate; allow = prior.allowRecipeSaves
                }
            }
            hydrated = true
        }
    }
    val identity = state.accountId?.takeIf { it.isNotBlank() }?.let { account ->
        val id = state.clientDraftId; val revision = state.localRevision
        if (id != null && revision != null && revision > 0) BlueprintAuthoringIdentity(account, id, revision) else null
    }
    val context = identity?.let { BlueprintAuthoringContext(it, BlueprintAuthoringMode.DRAFT, current = ready) }
    val savedSuggestion = state.recipeSourceSuggestion?.sourceKind == DraftRecipeSourceKind.SAVED_RECIPE
    val suggestionReviewLabel = if (savedSuggestion) "Review saved recipe" else "Review this meal"
    val source = state.source
    val recipe = if (ready && identity != null && source != null)
        reviewedAttachmentPresentation(identity, source, ingredientLabels) else null
    val detailsConfirmed = state.attachmentChosen && recipe != null && source != null && confirmedSource === source
    val noneConfirmed = state.attachmentChosen && source == null && ready
    val attachmentChosen = detailsConfirmed || noneConfirmed
    val activeCircles = state.circles.filter { it.status == "active" && it.role in setOf("owner", "admin", "member") }
    val circleSelectionCurrent = state.circlesLoaded && selectedCircleIds.isNotEmpty() &&
        selectedCircleIds.all { id -> activeCircles.count { it.id == id } == 1 }
    val audience: PublicationAudience? = when {
        onlyYou == true -> PublicationAudience.OnlyYou
        onlyYou == false && circleSelectionCurrent -> PublicationAudience.Circles(selectedCircleIds)
        else -> null
    }
    val disclosureConfirmed = state.disclosure != null && acceptedDisclosure === state.disclosure
    val canRetain = editable && attachmentChosen && audience != null && keep != null && allow != null &&
        disclosureConfirmed && (allow != true || source?.copyChoiceAvailable == true && detailsConfirmed)
    val message = attachmentFlowMessage(state, loading)
    fun back() {
        if (!current() || retaining) return
        if (tools) { closeTools(); return }
        // Reads may be retired by leaving; an acknowledged choice edit must finish under
        // the wrapper. Unsaved choices still need the ordinary leave confirmation.
        if (loading) {
            if (leaveReview) leaveReview = false
            else if (changed) leaveReview = true
            else onBack()
            return
        }
        when {
            picker != null -> picker = null
            leaveReview -> leaveReview = false
            page == AttachmentPage.PERMISSION -> page = AttachmentPage.AUDIENCE
            page == AttachmentPage.AUDIENCE -> page = if (noneConfirmed) AttachmentPage.ATTACH else AttachmentPage.REVIEW
            page == AttachmentPage.REVIEW -> page = AttachmentPage.ATTACH
            changed -> leaveReview = true
            else -> onBack()
        }
    }
    platformBackHandler(true, ::back)
    if (leaveReview) {
        val review = BlueprintConfirmationState(actionLabel = "Leave these unsaved choices",
            affectedSummary = "Your draft’s retained choices have not been changed by this review.",
            consequences = listOf("Discard only these in-memory recipe, audience and copy choices. Existing server drafts and publication requests are unchanged."),
            canConfirm = current(), canCancel = current(), busy = retaining)
        BlueprintConfirmationScreen(review, heading = "Leave it\nfor now?", confirmLabel = "Leave review",
            onConfirm = { rendered -> if (rendered === review && review.confirmEnabled && current()) onBack() },
            onCancel = { rendered -> if (rendered === review && review.cancelEnabled && current()) leaveReview = false })
        return
    }
    fun openTools() {
        if (current() && !tools && toolsVisit === expectedToolsVisit) { toolsVisit = Any(); tools = true }
    }
    when (page) {
        AttachmentPage.ATTACH -> {
            val model = BlueprintAttachRecipeState(context, controls = BlueprintAuthoringControls(
                enabled = current(), actions = buildSet {
                    add(BlueprintAuthoringAction.ATTACH_BACK)
                    if (editable) {
                        add(BlueprintAuthoringAction.PICK_SAVED_RECIPE); add(BlueprintAuthoringAction.WITHOUT_RECIPE)
                        if (recentCookSessionId != null && recentPlanId != null) add(BlueprintAuthoringAction.PICK_RECENT_COOK)
                    }
                }, moreEnabled = current()),
                status = "$message\n\n" + if (state.recipeSourceSuggestion != null)
                    "This draft retains ${if (savedSuggestion) "a saved recipe" else "a recipe"} reference. Open More → $suggestionReviewLabel to check it before attaching. You can also choose another source or continue without one."
                else "Pick a saved recipe or an actual completed cook. Entered recipes need structured ingredient review; free text is not converted into recipe IDs.")
            BlueprintAttachRecipeScreen(model, onAction = { rendered, action ->
                if (rendered === model && current() && model.allows(action)) when (action) {
                    BlueprintAuthoringAction.ATTACH_BACK -> back()
                    BlueprintAuthoringAction.PICK_SAVED_RECIPE -> {
                        picker = AttachmentPicker.SAVED; act { controller.loadSaved(state) }
                    }
                    BlueprintAuthoringAction.PICK_RECENT_COOK -> if (recentCookSessionId != null && recentPlanId != null) act {
                        when (val result = controller.chooseCompletedCook(recentCookSessionId, recentPlanId, state)) {
                            is PortResult.Value -> if (attached && controller.isCurrent(result.value)) {
                                confirmedSource = null; allow = null; changed = true; page = AttachmentPage.REVIEW
                            }
                            is PortResult.Failure -> Unit
                        }
                    }
                    BlueprintAuthoringAction.WITHOUT_RECIPE -> act {
                        when (val result = controller.chooseNone(state)) {
                            is PortResult.Value -> if (attached && controller.isCurrent(result.value) && result.value.source == null) {
                                confirmedSource = null; allow = false; changed = true; page = AttachmentPage.AUDIENCE
                            }
                            is PortResult.Failure -> Unit
                        }
                    }
                    else -> Unit
                }
            }, onEdit = { _, _ -> }, onMore = { rendered -> if (rendered === model) openTools() })
        }
        AttachmentPage.REVIEW -> {
            val model = BlueprintReviewAttachmentState(context, recipe, detailsConfirmed,
                controls = BlueprintAuthoringControls(enabled = current(), actions = buildSet {
                    add(BlueprintAuthoringAction.REVIEW_BACK)
                    if (editable && source != null) {
                        add(BlueprintAuthoringAction.CONFIRM_ATTACHMENT); add(BlueprintAuthoringAction.REMOVE_ATTACHMENT)
                    }
                }, editableFields = if (editable && source != null) setOf("confirmed") else emptySet(), moreEnabled = current()),
                status = "$message\n\nReview the actual source below. Confirming selects it for this composer; it does not publish, grant copies or change the original recipe.")
            BlueprintReviewAttachmentScreen(model, onAction = { rendered, action ->
                if (rendered === model && current() && model.allows(action)) when (action) {
                    BlueprintAuthoringAction.REVIEW_BACK -> back()
                    BlueprintAuthoringAction.CONFIRM_ATTACHMENT -> { changed = true; page = AttachmentPage.AUDIENCE }
                    BlueprintAuthoringAction.REMOVE_ATTACHMENT -> act {
                        when (val result = controller.chooseNone(state)) {
                            is PortResult.Value -> if (attached && controller.isCurrent(result.value) && result.value.source == null) {
                                confirmedSource = null; allow = false; changed = true; page = AttachmentPage.AUDIENCE
                            }
                            is PortResult.Failure -> Unit
                        }
                    }
                    else -> Unit
                }
            }, onEdit = { rendered, edit -> if (rendered === model && current() && edit is BlueprintAuthoringEdit.Toggle &&
                edit.field == "confirmed" && model.canEdit(edit.field)) {
                confirmedSource = source.takeIf { edit.value }; changed = true
            } }, onMore = { rendered -> if (rendered === model) openTools() })
        }
        AttachmentPage.AUDIENCE -> {
            val choices = if (identity == null) emptyList() else buildList {
                add(BlueprintAudienceChoice(identity, BlueprintAudienceKind.ONLY_YOU, current = ready))
                if (circleSelectionCurrent) add(BlueprintAudienceChoice(identity, BlueprintAudienceKind.SELECTED_CIRCLES,
                    selectedCircleIds, current = ready))
            }
            val chosen = choices.singleOrNull { if (onlyYou == true) it.kind == BlueprintAudienceKind.ONLY_YOU
                else onlyYou == false && it.kind == BlueprintAudienceKind.SELECTED_CIRCLES }
            val model = BlueprintAudienceState(context, choices, chosen, keep == true,
                controls = BlueprintAuthoringControls(enabled = current(), actions = buildSet {
                    add(BlueprintAuthoringAction.AUDIENCE_BACK)
                    if (editable) {
                        add(BlueprintAuthoringAction.MANAGE_CIRCLES)
                        if (attachmentChosen && audience != null && keep != null) add(BlueprintAuthoringAction.USE_AUDIENCE)
                    }
                }, editableFields = if (editable) setOf("audience", "keep") else emptySet(), moreEnabled = current()),
                status = listOf(message, "Choose an audience explicitly. Circle choices are checked again before any publication.",
                    if (keep == null) "Choose your Plate setting. More also lets you explicitly leave it off." else
                        if (keep == true) "Keep on My Plate selected." else "Do not keep on My Plate selected.").joinToString("\n\n"))
            BlueprintAudienceScreen(model, onAction = { rendered, action ->
                if (rendered === model && current() && model.allows(action)) when (action) {
                    BlueprintAuthoringAction.AUDIENCE_BACK -> back()
                    BlueprintAuthoringAction.MANAGE_CIRCLES -> { picker = AttachmentPicker.CIRCLES; act { controller.loadCircles(state) } }
                    BlueprintAuthoringAction.USE_AUDIENCE -> page = AttachmentPage.PERMISSION
                    else -> Unit
                }
            }, onEdit = { rendered, edit -> if (rendered === model && current()) when (edit) {
                is BlueprintAuthoringEdit.Audience -> if (model.canSelect(edit.choice)) {
                    onlyYou = edit.choice.kind == BlueprintAudienceKind.ONLY_YOU; changed = true
                }
                is BlueprintAuthoringEdit.Toggle -> if (edit.field == "keep" && model.canEdit(edit.field)) { keep = edit.value; changed = true }
                else -> Unit
            } }, onMore = { rendered -> if (rendered === model) openTools() })
        }
        AttachmentPage.PERMISSION -> {
            val model = BlueprintSavePermissionState(context, recipe, allow == true,
                controls = BlueprintAuthoringControls(enabled = current(), actions = buildSet {
                    add(BlueprintAuthoringAction.PERMISSION_BACK); add(BlueprintAuthoringAction.PERMISSION_RECIPE_DETAILS)
                    if (canRetain) add(BlueprintAuthoringAction.CONFIRM_PERMISSION)
                }, editableFields = if (editable && recipe != null) setOf("allow") else emptySet(), moreEnabled = current()),
                status = listOf(message,
                    if (noneConfirmed) "No recipe selected; private recipe saves are off." else if (allow == null)
                        "Choose whether to allow private recipe copies. More lets you explicitly keep this off." else
                        if (allow == true) "Allow copies selected; the server must still authorize each save." else "Private recipe saves are off.",
                    if (!disclosureConfirmed) "Read and acknowledge the current save disclosure in More." else "Current disclosure acknowledged for this review.",
                    "Confirming keeps these complete choices on this device. Server Save and Publish need separate confirmation.").joinToString("\n\n"),
                attachmentAbsent = noneConfirmed)
            BlueprintSavePermissionScreen(model, onAction = { rendered, action ->
                if (rendered === model && current() && model.allows(action)) when (action) {
                    BlueprintAuthoringAction.PERMISSION_BACK -> back()
                    BlueprintAuthoringAction.PERMISSION_RECIPE_DETAILS -> page = if (noneConfirmed) AttachmentPage.ATTACH else AttachmentPage.REVIEW
                    BlueprintAuthoringAction.CONFIRM_PERMISSION -> if (canRetain) act(retention = true) {
                        val targetAudience = audience ?: return@act
                        val targetKeep = keep ?: return@act
                        val targetAllow = allow ?: return@act
                        when (val result = controller.retainChoices(state, source, emptyList(), targetAudience, targetKeep,
                            targetAllow, detailsConfirmed = attachmentChosen, disclosureConfirmed = disclosureConfirmed)) {
                            is PortResult.Value -> if (attached && result.value.selected?.clientDraftId == state.clientDraftId &&
                                result.value.selected?.localAcknowledged == true) onRetained(result.value)
                            is PortResult.Failure -> Unit
                        }
                    }
                    else -> Unit
                }
            }, onEdit = { rendered, edit -> if (rendered === model && current() && edit is BlueprintAuthoringEdit.Toggle &&
                edit.field == "allow" && model.canEdit(edit.field) && (!edit.value || source?.copyChoiceAvailable == true)) {
                allow = edit.value; changed = true
            } }, onMore = { rendered -> if (rendered === model) openTools() })
        }
    }
    if (tools && current()) FeedMeTheme {
        AlertDialog(onDismissRequest = { if (toolsCurrent()) closeTools() }, title = { Text("Recipe and sharing choices") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(message)
                if (state.recipeSourceSuggestion != null) {
                    Text(if (savedSuggestion)
                        "This draft keeps your saved recipe reference. Reviewing it checks the current saved copy and recipe; it does not attach or publish automatically."
                        else "This draft keeps the recipe reference you chose. Reviewing it checks the current recipe; it does not attach or publish automatically.")
                    TextButton(enabled = editable, onClick = { toolRead {
                        when (val result = controller.chooseRecipeSuggestion(state)) {
                            is PortResult.Value -> if (sameToolsVisit() && controller.isCurrent(result.value) && result.value.source != null) {
                                confirmedSource = null; allow = null; changed = true
                                closeTools(); page = AttachmentPage.REVIEW
                            }
                            is PortResult.Failure -> Unit
                        }
                    } }) { Text(suggestionReviewLabel) }
                }
                TextButton(enabled = !loading, onClick = { toolRead { controller.refresh(state) } }) { Text("Refresh current prerequisites") }
                if (page == AttachmentPage.AUDIENCE) {
                    TextButton(enabled = editable, onClick = { if (toolsCurrent()) { keep = false; changed = true; closeTools() } }) { Text("Do not keep on My Plate") }
                    TextButton(enabled = editable, onClick = { if (toolsCurrent()) { keep = true; changed = true; closeTools() } }) { Text("Keep on My Plate") }
                }
                if (page == AttachmentPage.PERMISSION) {
                    TextButton(enabled = editable, onClick = { if (toolsCurrent()) { allow = false; changed = true } }) { Text("Do not allow private copies") }
                    state.disclosure?.let { disclosure ->
                        Text("Current disclosure · ${disclosure.version}")
                        Text(disclosure.text)
                        TextButton(enabled = editable, onClick = { if (toolsCurrent() && state.disclosure === disclosure) {
                            acceptedDisclosure = disclosure; changed = true
                        } }) { Text(if (acceptedDisclosure === disclosure) "Acknowledged" else "I understand this disclosure") }
                    }
                }
                Text("Recipe source IDs, a completed cook and a saved copy are not publication receipts. Entered recipes and source-text edits are not connected here.")
            }
        }, confirmButton = { TextButton(onClick = { if (toolsCurrent()) closeTools() }) { Text("Done") } })
    }
    if (picker != null && current()) FeedMeTheme {
        val kind = picker
        AlertDialog(onDismissRequest = { picker = null }, title = { Text(if (kind == AttachmentPicker.SAVED) "Choose a saved recipe" else "Choose invited circles") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(message)
                if (kind == AttachmentPicker.SAVED) {
                    if (state.savedLoaded && state.savedRecipes.isEmpty()) Text("No saved recipes were returned.")
                    state.savedRecipes.forEach { saved -> TextButton(enabled = editable, onClick = { act {
                        when (val result = controller.chooseSaved(saved.id.value, state)) {
                            is PortResult.Value -> if (attached && controller.isCurrent(result.value)) {
                                picker = null; confirmedSource = null; allow = null; changed = true; page = AttachmentPage.REVIEW
                            }
                            is PortResult.Failure -> Unit
                        }
                    } }) { Text(saved.title) } }
                    if (state.hasMoreSaved) TextButton(enabled = editable, onClick = { act { controller.loadMoreSaved(state) } }) { Text("More saved recipes") }
                    if (state.savedPageLimitReached) Text("Only the loaded saved recipes are shown.")
                } else {
                    if (state.circlesLoaded && activeCircles.isEmpty()) Text("No active circles were returned.")
                    activeCircles.forEach { circle -> FilterChip(selected = circle.id in selectedCircleIds,
                        enabled = editable, onClick = { if (current()) {
                            selectedCircleIds = if (circle.id in selectedCircleIds) selectedCircleIds.filterNot { it == circle.id }
                                else selectedCircleIds + circle.id
                            onlyYou = false; changed = true
                        } }, label = { Text(circle.name) }) }
                    if (state.hasMoreCircles) TextButton(enabled = editable, onClick = { act { controller.loadMoreCircles(state) } }) { Text("More circles") }
                    if (state.circlePageLimitReached) Text("Only the loaded circles are shown.")
                    Text("Only selected returned circles are requested. No names or membership have been inferred.")
                }
            }
        }, confirmButton = { TextButton(onClick = { picker = null }) { Text("Done") } })
    }
}

private fun reviewedAttachmentPresentation(identity: BlueprintAuthoringIdentity, source: ReviewedAttachmentSource,
    ingredientLabels: Map<String, String>): BlueprintAuthoringAttachment? {
    val recipe = source.recipe
    val reference = when (val chosen = source.source) {
        is PublicationAttachmentSource.RecipeVersion -> BlueprintAttachmentSourceReference.RecipeVersion(chosen.recipeVersionId, recipe.version.jsonToken)
        is PublicationAttachmentSource.Plan -> BlueprintAttachmentSourceReference.Plan(chosen.planId, source.sourceVersion)
        is PublicationAttachmentSource.Personal -> return null
    }
    return BlueprintAuthoringAttachment(identity, attachmentId = null, version = null, title = recipe.title,
        recipeVersionId = recipe.id.value,
        sourceLabel = when (source.origin) {
            ReviewedAttachmentOrigin.SAVED_RECIPE -> "Current catalog source · saved recipe"
            ReviewedAttachmentOrigin.COMPLETED_COOK -> "Current catalog source · completed cook"
            ReviewedAttachmentOrigin.RECIPE_SUGGESTION -> "Current catalog source · selected meal"
        },
        servingsLabel = "${recipe.servings.jsonToken} servings",
        ingredients = recipe.ingredients.map { ingredient -> BlueprintAttachmentIngredient(
            (ingredientLabels[ingredient.ingredientId.value] ?: "Ingredient label unavailable (${ingredient.ingredientId.value})") +
                ((ingredient.preparation as? WireField.Value)?.value?.let { " · $it" } ?: "") + if (ingredient.optional) " · optional" else "",
            "${ingredient.quantity.jsonToken} ${ingredient.unit}") },
        preparationNotes = recipe.steps.joinToString("\n\n") { step ->
            "${step.position.jsonToken}. ${step.instruction}" + if (step.mandatorySafetyStep) "\nRequired safety step." else ""
        }, sourceCredit = (source.sourceDocument.field("creatorLabel") as? WireField.Value)?.value?.stringOrNull(),
        canRedistribute = source.catalogRedistributionObserved, canGrantCopies = source.copyChoiceAvailable,
        sourceReference = reference)
}

private fun attachmentFlowMessage(state: ReviewedAttachmentState, loading: Boolean): String = if (loading) "Checking your draft and selected source…" else when (state.phase) {
    ReviewedAttachmentPhase.EXPIRED -> "This observation expired. Refresh in More before keeping choices."
    ReviewedAttachmentPhase.UNAVAILABLE -> "Sharing prerequisites are unavailable. Back leaves your draft unchanged."
    ReviewedAttachmentPhase.RETAINED -> "Complete composer choices were retained on this device. Nothing was published."
    ReviewedAttachmentPhase.ERROR -> when (state.failureReason) {
        FailureReason.NOT_CONFIGURED -> "A required sharing prerequisite is not connected. No attachment or publication success is implied."
        FailureReason.OFFLINE -> "Connect before checking sources and current sharing choices."
        FailureReason.CONFLICT -> "The draft or selected source changed. Refresh and review it again."
        FailureReason.STORAGE_FAILURE, FailureReason.OUTCOME_UNKNOWN -> "Retention is not confirmed. Return to the draft’s existing recovery controls before making another change."
        FailureReason.RATE_LIMITED -> "Please wait before trying this read again."
        else -> "This source or choice could not be verified. Your retained draft is unchanged unless its own recovery shows an acknowledged result."
    }
    else -> "Selections are private until a separate, explicitly confirmed publication."
}
