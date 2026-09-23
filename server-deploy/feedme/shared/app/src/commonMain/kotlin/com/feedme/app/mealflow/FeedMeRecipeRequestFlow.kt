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
import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.mealflow.reciperequests.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Original RECIPE_REQUEST / ATTACH_RECIPE / REVIEW_ATTACHMENT over current account reads.
 * No effect sends or loads. Choosing a source does not share it, attach it to a post or grant copies. */
@Composable
fun FeedMeRecipeRequestFlow(controller: RecipeRequestController, hostIsCurrent: () -> Boolean, onClose: () -> Unit,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit, onOpenThread: ((String) -> Unit)? = null) {
    val observed by controller.states.collectAsState(); val state = observed
    val host by rememberUpdatedState(hostIsCurrent); val scope = rememberCoroutineScope()
    var attached by remember(controller) { mutableStateOf(true) }
    DisposableEffect(controller) { onDispose { attached = false } }
    var busy by remember(controller) { mutableStateOf(false) }; var more by remember(controller) { mutableStateOf(false) }
    var choose by remember(controller) { mutableStateOf(false) }; var page by remember(controller) { mutableStateOf(0) }
    var note by remember(controller, state.routeRequestId ?: state.routePostId) { mutableStateOf(state.note) }
    var reviewedSource by remember(controller) { mutableStateOf<RecipeResponseSource?>(null) }
    var checkedSource by remember(controller) { mutableStateOf<RecipeResponseSource?>(null) }
    var acknowledged by remember(controller) { mutableStateOf<RecipeRequestReceipt?>(null) }
    var leaveFailure by remember(controller) { mutableStateOf(false) }
    fun current() = attached && host() && controller.isCurrent(state)
    val sending = state.phase == RecipeRequestPhase.SENDING; val loading = busy || state.phase == RecipeRequestPhase.LOADING
    val ready = current() && state.phase == RecipeRequestPhase.READY && state.pending == null && !loading
    SideEffect { if (current()) {
        if (state.phase == RecipeRequestPhase.UNAVAILABLE) { note = ""; reviewedSource = null; checkedSource = null }
        state.receipt?.let { if (it !== acknowledged) { note = state.note; acknowledged = it; page = 0 } }
    } }
    fun act(action: suspend () -> Unit) { if (current() && !busy) { busy = true
        scope.launch { try { if (current()) action() } finally { busy = false } } } }
    fun leave(threadId: String? = null) {
        if (!current() || sending) return
        val expected = state; val retainedNote = note
        scope.launch { withContext(NonCancellable) {
            when (val result = controller.leave(expected, retainedNote.takeIf { expected.routePostId != null || expected.routeRequestId != null })) {
                is PortResult.Value -> if (controller.isCurrent(result.value) && result.value.phase == RecipeRequestPhase.HIDDEN) {
                    if (threadId != null && onOpenThread != null) onOpenThread(threadId) else onClose()
                }
                is PortResult.Failure -> if (attached) leaveFailure = true
            }
        } }
    }
    fun back() { if (!current() || sending) return
        when { choose -> choose = false; more -> more = false
            state.review != null -> act { controller.backReview(state) }
            page > 0 -> { page = 0; checkedSource = null }
            else -> leave() }
    }
    platformBackHandler(state.phase != RecipeRequestPhase.HIDDEN, ::back)
    val review = state.review.takeIf { current() }
    if (review != null) {
        val label = when (review.operationId) { "requestRecipe" -> "Send this recipe request"
            "cancelRecipeRequest" -> "Cancel your pending request"
            else -> if (review.decision == "fulfill") "Send this recipe response" else "Decline this request" }
        val model = BlueprintConfirmationState(if (review.retryOriginal) "Retry original: $label" else label,
            listOfNotNull(review.postId?.let { "Source post $it" }, review.requestId?.let { "Request $it · version ${review.version}" },
                review.recipeTitle, review.recipeVersionId?.let { "Recipe version $it" }, review.message.takeIf { it.isNotEmpty() }).joinToString("\n\n"),
            listOf("Only this explicit request or response is sent. It does not attach a recipe to the post, permit reposting or create a private-copy grant.",
                "The server checks current participants, blocks, source access and recipe availability.",
                if (review.retryOriginal) "The exact original key, version and content are retried; no replacement or earlier success is assumed."
                else "Nothing is shown as completed until its server response and local receipt are confirmed."),
            canConfirm = current() && !loading && !sending, canCancel = current() && !loading && !sending, busy = loading || sending)
        BlueprintConfirmationScreen(model, onConfirm = { if (it === model && model.confirmEnabled) act { controller.confirm(review) } },
            onCancel = { if (it === model && model.cancelEnabled) act { controller.backReview(state) } },
            heading = "Your choice.\nShared properly.", confirmLabel = if (review.retryOriginal) "Retry original" else "Confirm")
        return
    }
    val record = state.request.takeIf { current() }; val source = state.selectedRecipe.takeIf { current() }
    val author = record != null && record.authorId == state.accountId
    val pending = record?.status == "pending"
    val notice = recipeRequestNotice(state) + if (leaveFailure) "\nCould not leave safely. Your note remains here." else ""
    val identity = if (record != null && state.accountId != null) BlueprintAuthoringIdentity(state.accountId!!, record.id, record.version.toLong()) else null
    val authoring = identity?.let { BlueprintAuthoringContext(it, BlueprintAuthoringMode.FULFILL_REQUEST, current = ready && author && pending) }
    if (page == 1) {
        val model = BlueprintAttachRecipeState(context = authoring, controls = BlueprintAuthoringControls(
            actions = buildSet { add(BlueprintAuthoringAction.ATTACH_BACK); if (ready && author && pending) add(BlueprintAuthoringAction.PICK_SAVED_RECIPE) },
            moreEnabled = current()), status = "$notice\nChoose a Saved catalog recipe. Personal recipes, private copied recipes and edited snapshots cannot be used for this response.")
        BlueprintAttachRecipeScreen(model, onAction = { expected, action -> if (expected === model && current() && model.allows(action)) when (action) {
            BlueprintAuthoringAction.ATTACH_BACK -> back()
            BlueprintAuthoringAction.PICK_SAVED_RECIPE -> { choose = true; act { controller.loadRecipes(state) } }
            else -> Unit
        } }, onEdit = { _, _ -> }, onMore = { if (current()) more = true })
    } else if (page == 2) {
        val recipe = if (source != null && identity != null) BlueprintAuthoringAttachment(identity, null, null, source.recipe.title,
            recipeVersionId = source.recipe.id.value, sourceLabel = "Current published catalog recipe", servingsLabel = source.recipe.servings.jsonToken,
            ingredients = source.recipe.ingredients.map { ingredient -> BlueprintAttachmentIngredient("Ingredient ${ingredient.ingredientId.value}",
                "${ingredient.quantity.jsonToken} ${ingredient.unit}") },
            preparationNotes = source.recipe.steps.joinToString("\n") { it.instruction },
            canUseForResponse = ready && author && pending,
            sourceReference = BlueprintAttachmentSourceReference.RecipeVersion(source.recipe.id.value, source.recipe.version.jsonToken)) else null
        val model = BlueprintReviewAttachmentState(authoring, recipe, source != null && checkedSource === source,
            BlueprintAuthoringControls(actions = buildSet { add(BlueprintAuthoringAction.REVIEW_BACK); add(BlueprintAuthoringAction.CANCEL_RESPONSE_RECIPE)
                if (ready && source != null) add(BlueprintAuthoringAction.USE_RESPONSE_RECIPE) },
                editableFields = if (ready && source != null) setOf("confirmed") else emptySet(), moreEnabled = current()),
            "$notice\nReview the actual quantities and steps. Ingredient IDs are shown when no verified ingredient-name catalogue is supplied. Using this selection does not send it.")
        BlueprintReviewAttachmentScreen(model, onAction = { expected, action -> if (expected === model && current() && model.allows(action)) when (action) {
            BlueprintAuthoringAction.USE_RESPONSE_RECIPE -> { reviewedSource = source; page = 0 }
            BlueprintAuthoringAction.REVIEW_BACK -> { page = 1; checkedSource = null }
            BlueprintAuthoringAction.CANCEL_RESPONSE_RECIPE -> { page = 0; checkedSource = null; reviewedSource = null }
            else -> Unit
        } }, onEdit = { expected, edit -> if (expected === model && current() && model.canEdit("confirmed") && edit is BlueprintAuthoringEdit.Toggle && edit.field == "confirmed")
            checkedSource = source.takeIf { edit.value } }, onMore = { if (current()) more = true })
    } else {
        val context = state.accountId?.takeIf { current() }?.let { BlueprintCommunityContext(it, state.revision) }
        val post = state.post.takeIf { current() }?.let { BlueprintCommunityRef(BlueprintCommunityKind.POST,
            requestUiText(it, "id"), requestUiNumber(it, "version")) }
        val thread = state.thread.takeIf { current() }?.let { BlueprintCommunityRef(BlueprintCommunityKind.THREAD,
            requestUiText(it, "id"), requestUiNumber(it, "version")) }
        val request = if (record != null && thread != null) BlueprintPendingRecipeRequest(
            BlueprintCommunityRef(BlueprintCommunityKind.REQUEST, record.id, record.version), thread, post) else null
        val response = if (source != null && reviewedSource === source && request != null) BlueprintReviewedRecipeResponse(request.request, request.thread,
            BlueprintMealIdentity(source.recipe.recipeId.value, source.recipe.id.value), state.revision) else null
        val model = BlueprintRecipeRequestState(context, if (author) BlueprintRecipeRequestMode.FULFILL_REQUEST else BlueprintRecipeRequestMode.REQUESTER,
            sourcePost = post, request = request, note = note, response = response,
            controls = BlueprintCommunityControls(enabled = current(), loading = loading || sending, unavailable = context == null,
                editableFields = if (ready && (record == null || pending)) setOf("message") else emptySet(),
                allowedActionIds = buildSet {
                    if (current() && !sending) add(BlueprintConversationAction.CANCEL_REQUEST_DRAFT.id)
                    if (ready && record == null && post != null) add(BlueprintConversationAction.SEND_REQUEST.id)
                    if (ready && pending && author) { add(BlueprintConversationAction.ANSWER_RECIPE.id); add(BlueprintConversationAction.DECLINE_REQUEST.id)
                        if (response != null) add(BlueprintConversationAction.SEND_RESPONSE.id) }
                    if (ready && pending && record?.requesterId == state.accountId) add(BlueprintConversationAction.CANCEL_SENT_REQUEST.id)
                    if (ready && request != null && onOpenThread != null) add(BlueprintConversationAction.BACK_TO_THREAD.id)
                }, status = notice + state.post?.let { "\nSource post: ${requestUiText(it, "caption")}" }.orEmpty()))
        BlueprintRecipeRequestScreen(model, onNote = { expected, value -> if (expected === model.context && ready && value.length <= 500) note = value },
            onAction = { intent -> if (current() && intent.context === model.context && model.intent(intent.action) != null) when (intent.action) {
                BlueprintConversationAction.SEND_REQUEST -> act { controller.prepareCreate(note, state) }
                BlueprintConversationAction.ANSWER_RECIPE -> { page = 1; checkedSource = null }
                BlueprintConversationAction.SEND_RESPONSE -> if (reviewedSource === source) act { controller.prepareRespond(true, note, state) }
                BlueprintConversationAction.DECLINE_REQUEST -> act { controller.prepareRespond(false, note, state) }
                BlueprintConversationAction.CANCEL_SENT_REQUEST -> act { controller.prepareCancel(state) }
                BlueprintConversationAction.BACK_TO_THREAD -> record?.threadId?.let(::leave)
                BlueprintConversationAction.CANCEL_REQUEST_DRAFT -> back()
                else -> Unit
            } }, onBack = ::back, onMore = { if (current()) more = true }, onNavigate = {})
    }
    if (choose && current()) FeedMeTheme { AlertDialog(onDismissRequest = { choose = false }, title = { Text("Choose a Saved catalog recipe") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (loading) Text("Reading your current saved recipes…")
            else if (state.savedLoaded && state.savedRecipes.isEmpty()) Text("No saved recipes were returned.")
            state.savedRecipes.forEach { saved -> TextButton(enabled = ready && !saved.recalled && saved.sourceType == "catalog" &&
                (saved.contentLicense as? WireField.Value)?.value == "catalogRedistributable", onClick = {
                act { when (controller.chooseRecipe(saved.id.value, state)) {
                    is PortResult.Value -> { choose = false; page = 2; checkedSource = null }
                    is PortResult.Failure -> Unit
                } }
            }) { Text(saved.title) } }
            if (state.hasMore) TextButton(enabled = ready, onClick = { act { controller.loadMoreRecipes(state) } }) { Text("Load more") }
            if (state.pageLimitReached) Text("This bounded selection reached its page limit.")
            Text("Only a rechecked published catalog source can be used; a private saved copy is not permission to share it.")
        }
    }, confirmButton = { TextButton(onClick = { choose = false }) { Text("Back") } }) }
    if (more && current()) FeedMeTheme { AlertDialog(onDismissRequest = { more = false }, title = { Text("Recipe request status") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(notice)
            state.pending?.let { original -> Text("Original ${original.operationId}: ${original.requestId ?: original.postId}")
                Text("Status: ${original.phase?.name ?: "Registration unconfirmed"}")
                if (original.canRetry) TextButton(enabled = !loading && !sending, onClick = { more = false; act { controller.prepareRetry(state) } }) { Text("Review original retry") }
                if (original.receiptReady) TextButton(enabled = !loading && !sending, onClick = { more = false; act { controller.recoverReceipt(state) } }) { Text("Confirm retained receipt") }
                Text("Back preserves this original. It does not cancel, replace or resend it.") }
            if (state.pending == null) TextButton(enabled = !loading && !sending, onClick = { more = false; act { controller.refresh(state, note) } }) { Text("Refresh current request") }
            (state.receipt?.request?.threadId ?: record?.threadId)?.let { id -> if (onOpenThread != null) TextButton(enabled = !sending && !loading,
                onClick = { leave(id) }) { Text("Open conversation") } }
            Text("Personal recipe entry and recent private cooking snapshots are not enabled for response publication. Notes stay in this account session until sent or cleared.")
        }
    }, confirmButton = { TextButton(onClick = { more = false }) { Text("Done") } }) }
}
private fun requestUiText(doc: WireDocument, key: String) = (doc.field(key) as? WireField.Value)?.value?.stringOrNull().orEmpty()
private fun requestUiNumber(doc: WireDocument, key: String) = (doc.field(key) as? WireField.Value)?.value?.numberTokenOrNull().orEmpty()
private fun recipeRequestNotice(state: RecipeRequestState): String = when {
    state.pending != null -> "A retained original needs attention. More shows its actual status and recovery."
    state.phase == RecipeRequestPhase.COMPLETE -> when (state.receipt?.operationId) {
        "requestRecipe" -> "Request confirmed. The author can choose whether to answer."
        "cancelRecipeRequest" -> "Cancellation confirmed. No message is impersonated or recalled."
        "respondToRecipeRequest" -> "Response confirmed. This does not create a save or repost permission."
        else -> "The outcome is not confirmed."
    }
    state.phase == RecipeRequestPhase.LOADING -> "Reading current request information. Back can leave this read."
    state.phase == RecipeRequestPhase.SENDING -> "Finishing your confirmed original. No success is assumed yet."
    state.phase == RecipeRequestPhase.EXPIRED -> "This view expired. Your note is kept; refresh before acting."
    state.phase == RecipeRequestPhase.ERROR -> "Current request information is unavailable (${state.failureReason}). Nothing is retried automatically."
    state.phase == RecipeRequestPhase.UNAVAILABLE -> "The account session is unavailable. Private information is hidden."
    state.request != null -> "Current request: ${state.request?.status}. A response needs its own confirmation."
    else -> "Ask without pressure. A request is not permission to copy or repost."
}
