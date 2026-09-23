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
import com.feedme.core.ports.*
import com.feedme.mealflow.conversation.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Original THREAD/PRIVACY and exact confirmation UI. No effect performs I/O or retries.
 * Unsent replies survive Back in the actual controller's account-session RAM buffer. */
@Composable
fun FeedMeThreadFlow(controller: ThreadController, hostIsCurrent: () -> Boolean, onClose: () -> Unit,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
    onPrivacyAction: ((String) -> Unit)? = null, privacyExternalActions: Set<String> = emptySet(),
    onOpenRecipeRequest: ((ThreadState, String) -> Unit)? = null,
    onReportMessage: ((ThreadState, ThreadMessage, () -> Boolean) -> Unit)? = null,
    reportEntryFailure: FailureReason? = null) {
    val observed by controller.states.collectAsState()
    val state = observed
    val scope = rememberCoroutineScope()
    val hostCurrent by rememberUpdatedState(hostIsCurrent)
    var attached by remember(controller) { mutableStateOf(true) }
    DisposableEffect(controller) { onDispose { attached = false } }
    var busy by remember(controller) { mutableStateOf(false) }
    var more by remember(controller, state.routeThreadId, state.screen) { mutableStateOf(false) }
    var visit by remember(controller, state.routeThreadId, state.screen) { mutableStateOf(Any()) }
    val renderedVisit = visit
    DisposableEffect(controller, state.routeThreadId, state.screen) { onDispose { more = false; visit = Any() } }
    var text by remember(controller, state.routeThreadId) { mutableStateOf(state.draft) }
    var acknowledged by remember(controller) { mutableStateOf<ThreadMessage?>(null) }
    var contact by remember(controller, state.screen) { mutableStateOf<Boolean?>(null) }
    var contactBasis by remember(controller, state.screen) { mutableStateOf<String?>(null) }
    var contactOriginal by remember(controller, state.screen) { mutableStateOf<Boolean?>(null) }
    var requests by remember(controller, state.screen) { mutableStateOf<Boolean?>(null) }
    var requestsOriginal by remember(controller, state.screen) { mutableStateOf<Boolean?>(null) }
    var requestsBasis by remember(controller, state.screen) { mutableStateOf<String?>(null) }
    var selectedMessageId by remember(controller, state.routeThreadId) { mutableStateOf<String?>(null) }
    var updatedPrivacy by remember(controller) { mutableStateOf<ConversationPrivacySnapshot?>(null) }
    var leaveAction by remember(controller) { mutableStateOf<String?>(null) }
    var localFailure by remember(controller) { mutableStateOf<FailureReason?>(null) }
    fun current() = attached && hostCurrent() && controller.isCurrent(state)
    // This raw local visit deliberately survives editDraft's new ThreadState. Root consumes
    // it before opening Report, whose intentional overlay then disposes this UI attachment.
    fun backgroundVisitCurrent() = attached && hostCurrent() && visit === renderedVisit && !more && leaveAction == null
    fun backgroundCurrent() = current() && backgroundVisitCurrent()
    fun moreCurrent() = current() && visit === renderedVisit && more && leaveAction == null
    fun openMore() { if (backgroundCurrent()) { visit = Any(); more = true } }
    fun closeMore() { if (moreCurrent()) { more = false; visit = Any() } }
    fun moreAction(action: () -> Unit) { if (moreCurrent()) { closeMore(); action() } }
    val sending = state.phase == ThreadPhase.SENDING
    val loading = busy || state.phase == ThreadPhase.LOADING
    val ready = current() && state.phase == ThreadPhase.READY
    val contactDirty = contact != null && contact != contactOriginal || requests != null && requests != requestsOriginal
    SideEffect {
        if (current()) {
            if (state.phase == ThreadPhase.UNAVAILABLE) { text = ""; contact = null; contactOriginal = null; contactBasis = null
                requests = null; requestsOriginal = null; requestsBasis = null }
            state.privacy?.let { actual -> if (contact == null) {
                contact = actual.allowCircleMemberMessages; contactOriginal = contact; contactBasis = actual.version
            }; if (requests == null) { requests = actual.allowRecipeRequests; requestsOriginal = requests; requestsBasis = actual.version } }
            state.updatedPrivacy?.let { actual -> if (updatedPrivacy !== actual) {
                contact = actual.allowCircleMemberMessages; contactOriginal = contact; contactBasis = actual.version; updatedPrivacy = actual
                requests = actual.allowRecipeRequests; requestsOriginal = requests; requestsBasis = actual.version
            } }
            state.receipt?.let { receipt -> if (acknowledged !== receipt) {
                if (text == receipt.text) text = ""
                acknowledged = receipt
            } }
        }
    }
    fun act(work: suspend () -> Unit) {
        if (!busy && current()) {
            busy = true; localFailure = null
            scope.launch { try { if (current()) work() } finally { busy = false } }
        }
    }
    fun leave(action: String = "back") {
        if (!current() || sending) return
        val expected = state
        val unsent = text.takeIf { state.screen == ThreadScreen.THREAD && state.routeThreadId != null }
        // Completion of read-only navigation must not disappear when HIDDEN unmounts this UI.
        scope.launch { withContext(NonCancellable) {
            when (val result = controller.leave(expected, unsent)) {
                is PortResult.Value -> if (hostCurrent() && controller.states.value === result.value && result.value.phase == ThreadPhase.HIDDEN) {
                    if (action == "back") onClose() else onPrivacyAction?.invoke(action)
                }
                is PortResult.Failure -> if (attached) localFailure = result.reason
            }
        } }
    }
    fun navigateAway(action: String = "back") {
        if (!current() || sending) return
        if (state.screen == ThreadScreen.PRIVACY && contactDirty) leaveAction = action else leave(action)
    }
    fun back() {
        if (!current() || visit !== renderedVisit || sending) return
        when {
            leaveAction != null -> leaveAction = null
            more -> closeMore()
            state.review != null -> act { controller.backReview(state) }
            else -> navigateAway()
        }
    }
    platformBackHandler(state.phase != ThreadPhase.HIDDEN, ::back)
    val departure = leaveAction
    if (departure != null) {
        val model = BlueprintConfirmationState("Leave unsaved privacy choices", "Your current server setting stays unchanged.",
            listOf("Discard only this unsaved contact-setting change. Any already-confirmed original remains recoverable."),
            canConfirm = current() && !sending, canCancel = current() && !sending)
        BlueprintConfirmationScreen(model, onConfirm = { if (it === model && model.confirmEnabled) leave(departure) },
            onCancel = { if (it === model) leaveAction = null }, heading = "Leave this\nchange?", confirmLabel = "Leave without saving")
        return
    }
    val review = state.review.takeIf { current() }
    if (review != null) {
        val description = when (review.operationId) {
            "sendMessage" -> "Send to conversation ${review.threadId}:\n\n${review.text}"
            "createThread" -> "Start a private conversation with the selected account: ${review.recipientUserId}"
            else -> (if (review.allowCircleMemberMessages == true) "Allow eligible current circle members to start meal conversations."
                else "Do not allow new meal conversations from circle members.") + review.allowRecipeRequests?.let {
                    if (it) "\nSeparately allow eligible people to request recipes from your posts." else "\nDo not accept new recipe requests."
                }.orEmpty()
        }
        val model = BlueprintConfirmationState(
            actionLabel = if (review.retryOriginal) "Retry the retained original" else when (review.operationId) {
                "sendMessage" -> "Send this reply"; "createThread" -> "Start private conversation"; else -> "Save contact preference" },
            affectedSummary = description,
            consequences = listOf(if (review.operationId == "sendMessage") "This text leaves your device for the conversation’s participants. No recipe, photo or private food preferences are attached."
                else "The server checks current membership, blocks and privacy. No message is sent by this action.",
                if (review.retryOriginal) "This retries the same retained request, not a replacement. Its earlier outcome is not inferred." else "Nothing is applied until the server response and local receipt are confirmed."),
            canConfirm = current() && !loading && !sending, canCancel = current() && !loading && !sending, busy = loading || sending)
        BlueprintConfirmationScreen(model, onConfirm = { if (it === model && model.confirmEnabled) act { controller.confirm(review) } },
            onCancel = { if (it === model && model.cancelEnabled) act { controller.backReview(state) } },
            heading = if (review.operationId == "sendMessage") "Send this\nreply?" else "Your choice.\nYour call.",
            confirmLabel = if (review.retryOriginal) "Retry original" else if (review.operationId == "sendMessage") "Send reply" else "Confirm")
        return
    }
    val notice = threadNotice(state) +
        (if (localFailure != null) "\nAction not completed. Your unsent changes are still here." else "") +
        (if (reportEntryFailure != null) "\nReport not opened. Your unsent reply is still here; nothing was submitted." else "")
    if (state.screen == ThreadScreen.PRIVACY) {
        val actual = state.privacy.takeIf { current() }
        val model = BlueprintPreferenceState(BlueprintPreferencePage.PRIVACY,
            values = buildMap { if (current() && state.phase != ThreadPhase.UNAVAILABLE) contact?.let {
                put("contact", BlueprintPreferenceValue.Choice(if (it) "Current circle members" else "Nobody new")) } },
            enabledActionIds = buildSet {
                if (current() && !sending) {
                    add("PRIVACY.back")
                    if (onPrivacyAction != null) addAll(privacyExternalActions.intersect(setOf("PRIVACY.01", "PRIVACY.02", "PRIVACY.03", "PRIVACY.04", "PRIVACY.05", "PRIVACY.06")))
                }
                if (ready && !loading && state.pending == null && actual != null && contact != null && requests != null &&
                    contactBasis == actual.version && requestsBasis == actual.version && contactDirty) add("PRIVACY.07")
            }, editableFieldIds = if (ready && !loading && state.pending == null) setOf("contact") else emptySet(),
            notice = notice + "\nMore contains the separate recipe-request preference. Other privacy fields are unchanged." +
                if (contactDirty && actual != null && (contactBasis != actual.version || requestsBasis != actual.version)) "\nThe server version changed. Choose again against the current setting." else "",
            busy = sending, privacyDesignConfirmed = actual != null)
        BlueprintPreferenceScreen(model, onFieldChange = { field, value ->
            if (current() && ready && !loading && field == "contact" && value is BlueprintPreferenceValue.Choice && actual != null) {
                contact = value.label == "Current circle members"; contactBasis = actual.version
                contactOriginal = actual.allowCircleMemberMessages
            }
        }, onAction = { action ->
            if (current() && action in model.enabledActionIds) when (action) {
                "PRIVACY.back" -> back()
                "PRIVACY.07" -> contact?.let { proposed -> act { controller.preparePrivacy(proposed, state, requests) } }
                else -> navigateAway(action)
            }
        }, onMore = ::openMore)
    } else {
        val actual = state.thread.takeIf { current() }
        val reference = actual?.let { BlueprintCommunityRef(BlueprintCommunityKind.THREAD, it.id, it.version) }
        val context = state.accountId?.takeIf { current() }?.let { BlueprintCommunityContext(it, state.revision) }
        val selectedMessage = state.messages.singleOrNull { it.id == selectedMessageId }
        val reportMessage = selectedMessage?.takeIf { onReportMessage != null && controller.canReportMessage(state, it) }
        val renderedReply = text
        val requestTarget = if (reference != null && selectedMessage != null && onOpenRecipeRequest != null)
            selectedMessage.recipeRequestId?.takeIf { controller.canOpenRecipeRequest(state, it) }?.let { id ->
                BlueprintObservedRecipeRequest(id, BlueprintCommunityRef(BlueprintCommunityKind.MESSAGE, selectedMessage.id, selectedMessage.version), reference)
            } else null
        val model = BlueprintThreadState(context, reference, messages = if (reference == null) emptyList() else state.messages.map { message ->
            BlueprintThreadMessage(BlueprintCommunityRef(BlueprintCommunityKind.MESSAGE, message.id, message.version), reference,
                BlueprintSocialPerson(message.senderId, message.senderName),
                if (message.availability == "removed") "Message removed" else message.text,
                outgoing = message.senderId == state.accountId, timeLabel = message.createdAt)
        }, draft = renderedReply, selectedMessage = selectedMessage?.let { BlueprintCommunityRef(BlueprintCommunityKind.MESSAGE, it.id, it.version) },
            observedRequest = requestTarget, controls = BlueprintCommunityControls(enabled = backgroundCurrent(), loading = loading || sending,
            unavailable = actual == null, editableFields = if (ready && !loading && state.pending == null) setOf("message", "selectedMessage") else emptySet(),
            allowedActionIds = buildSet { if (ready && !loading && state.pending == null && text.isNotBlank()) add(BlueprintConversationAction.SEND_REPLY.id)
                if (ready && !loading && reportMessage != null) add(BlueprintConversationAction.REPORT_MESSAGE.id)
                if (ready && !loading && requestTarget != null) add(BlueprintConversationAction.REVIEW_REQUEST.id) },
            status = notice))
        BlueprintThreadScreen(model, onDraft = { ctx, thread, value ->
            if (backgroundCurrent() && ctx === model.context && thread === model.thread && model.controls.edit("message") && value.length <= 2_000) {
                text = value; visit = Any()
            }
        }, onSelectMessage = { ctx, thread, message -> if (backgroundCurrent() && ctx === model.context && thread === model.thread &&
            model.controls.edit("selectedMessage") && model.messages.any { it.reference == message }) {
                selectedMessageId = message.id; visit = Any()
            } }, onAction = { intent ->
            if (backgroundCurrent() && intent.context === model.context && intent.thread === model.thread && model.intent(intent.action) != null) when (intent.action) {
                BlueprintConversationAction.SEND_REPLY -> act { controller.prepareSend(text, state) }
                BlueprintConversationAction.REPORT_MESSAGE -> reportMessage?.let { message ->
                    if (intent.target == model.selectedMessage) {
                        fun reportVisitCurrent() = backgroundVisitCurrent() && selectedMessageId == message.id && text == renderedReply
                        act {
                            if (reportVisitCurrent() && controller.canReportMessage(state, message)) {
                                // Retain only the exact displayed unsent reply. The actual row
                                // must survive this local publication by object identity.
                                when (val retained = controller.editDraft(renderedReply, state)) {
                                    is PortResult.Value -> if (reportVisitCurrent() && retained.value.draft == renderedReply &&
                                        controller.canReportMessage(retained.value, message))
                                        onReportMessage?.invoke(retained.value, message, ::reportVisitCurrent)
                                    is PortResult.Failure -> if (reportVisitCurrent()) localFailure = retained.reason
                                }
                            }
                        }
                    }
                }
                BlueprintConversationAction.REVIEW_REQUEST -> intent.observedRequest?.requestId?.let { id -> act {
                    // Retain unsent text before handing off; the returned state is the exact current card observation.
                    when (val retained = controller.editDraft(text, state)) {
                        is PortResult.Value -> if (attached && hostCurrent() && controller.canOpenRecipeRequest(retained.value, id)) onOpenRecipeRequest?.invoke(retained.value, id)
                        is PortResult.Failure -> localFailure = retained.reason
                    }
                } }
                else -> Unit
            }
        }, onBack = ::back, onMore = ::openMore, onNavigate = {})
    }
    if (more && current()) FeedMeTheme {
        AlertDialog(onDismissRequest = ::closeMore, title = { Text("Conversation controls") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(notice)
                state.pending?.let { original ->
                    Text("Retained action: ${original.operationId}")
                    if (original.text.isNotEmpty()) Text(original.text)
                    Text("Status: ${original.phase?.name ?: "Original retained; registration unconfirmed"}")
                    if (original.canRetry) TextButton(enabled = !loading && !sending, onClick = {
                        moreAction { act { controller.prepareRetry(state) } }
                    }) { Text("Review retry of original") }
                    if (original.receiptReady) TextButton(enabled = !loading && !sending, onClick = {
                        moreAction { act { controller.recoverReceipt(state) } }
                    }) { Text("Confirm retained receipt") }
                    Text("Leaving keeps this original. It does not cancel or send it, and does not create a replacement.")
                }
                state.createdThread?.let { created -> TextButton(enabled = !loading && !sending, onClick = {
                    moreAction { act { controller.open(created.id) } }
                }) { Text("Open conversation") } }
                if (state.pending == null && state.screen != ThreadScreen.START) TextButton(enabled = !loading && !sending, onClick = {
                    moreAction { act { controller.refresh(state, text.takeIf { state.screen == ThreadScreen.THREAD }) } }
                }) { Text("Refresh current information") }
                if (state.hasMore) TextButton(enabled = !loading && !sending, onClick = {
                    moreAction { act { controller.loadMore(state, text) } }
                }) { Text("Load more messages") }
                if (state.pageLimitReached) Text("This view reached its page limit. Refresh requests a new bounded page.")
                state.privacy?.let { actual -> TextButton(enabled = !loading && !sending, onClick = {
                    if (moreCurrent()) { contact = actual.allowCircleMemberMessages; contactOriginal = contact; contactBasis = actual.version
                        requests = actual.allowRecipeRequests; requestsOriginal = requests; requestsBasis = actual.version }
                }) { Text("Use the current server contact setting") } }
                if (state.screen == ThreadScreen.PRIVACY) state.privacy?.let { actual ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = requests == true, enabled = ready && !loading && state.pending == null, onCheckedChange = { selected ->
                            if (moreCurrent() && ready) { requests = selected; requestsOriginal = actual.allowRecipeRequests; requestsBasis = actual.version }
                        }); Text("Allow recipe requests from eligible people", Modifier.weight(1f))
                    }
                    Text("This is separate from private messages. It does not share a recipe automatically. Close this menu, then Save privacy choices to review both settings.")
                }
                Text("Unsent reply text is kept only for this signed-in app session. Confirmed originals use private device storage. There are no automatic retries or read receipts.")
                Text("Select an available recipe-request message, then Review recipe request to read its current state. Recipe cards do not grant saving or reposting. Group conversations and mute controls are not connected here.")
            }
        }, confirmButton = { TextButton(onClick = ::closeMore) { Text("Done") } })
    }
}

private fun threadNotice(state: ThreadState): String = when {
    state.phase == ThreadPhase.SENDING -> "Finishing the confirmed original. Keep this view open; no success is assumed."
    state.pending != null -> "An original ${state.pending?.operationId} needs attention. More shows its real status and safe recovery."
    state.phase == ThreadPhase.COMPLETE -> when (state.completedOperation) {
        "sendMessage" -> "Reply confirmed by the server and retained receipt. Refresh to read the current conversation."
        "createThread" -> "Conversation confirmed. More → Open conversation reads its current messages."
        "updatePrivacySettings" -> "Contact preference saved. Refresh to read the current settings."
        else -> "Action needs current confirmation."
    }
    state.phase == ThreadPhase.LOADING -> "Loading current private information… Back can leave this read."
    state.phase == ThreadPhase.EXPIRED -> "This view expired. Your unsent text is retained; refresh before sending."
    state.phase == ThreadPhase.UNAVAILABLE -> "This account session is unavailable. Private content is hidden."
    state.phase == ThreadPhase.ERROR -> when (state.failureReason) {
        FailureReason.OFFLINE -> "Connect to load this conversation. Nothing was sent automatically."
        FailureReason.FORBIDDEN -> "Current membership, blocks or contact privacy do not allow this action."
        FailureReason.NOT_FOUND -> "This conversation is unavailable."
        FailureReason.NOT_CONFIGURED -> "This conversation service is not connected."
        FailureReason.STORAGE_FAILURE, FailureReason.OUTCOME_UNKNOWN -> "The outcome is not confirmed. Keep the original and use its recovery controls."
        FailureReason.RATE_LIMITED -> "Please wait before trying again. No automatic retry is scheduled."
        else -> "Current information could not be loaded. No empty history or success is assumed."
    }
    state.screen == ThreadScreen.PRIVACY -> "Your current contact choice. Changing it requires a separate confirmation."
    state.phase == ThreadPhase.READY && !state.messagesLoaded -> "Conversation checked. Message history has not been loaded; More → Refresh reads it."
    else -> "Private conversation. A reply sends only your text; opening this view does not mark messages read."
}
