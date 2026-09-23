package com.feedme.app.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feedme.app.blueprint.*
import com.feedme.app.mealflow.MealFlowHostActions
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.mealflow.blocks.*
import com.feedme.sync.CommandPhase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/** Original BLOCKED layout, with bounded actual rows in its More sheet. Mounting never reads
 * or sends. Account IDs are intentionally shown: the canonical block list has no display names.
 * The parent owns departure/leave exactly once; this attachment never retires the account. */
@Composable
fun FeedMeBlocksFlow(controller: BlocksController, onClose: () -> Unit,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
    hostIsCurrent: () -> Boolean = { true }) {
    val state = controller.states.collectAsState().value
    val scope = rememberCoroutineScope()
    val currentHost by rememberUpdatedState(hostIsCurrent)
    val currentClose by rememberUpdatedState(onClose)
    val host = remember(controller) { MealFlowHostActions { currentHost() } }
    DisposableEffect(host) { onDispose { host.retire() } }
    var running by remember(controller) { mutableStateOf(false) }
    var tools by remember(controller, state) { mutableStateOf(false) }
    var toolsVisit by remember(controller, state) { mutableStateOf(Any()) }
    var localFailure by remember(controller) { mutableStateOf<FailureReason?>(null) }
    val busy = running || state.phase in setOf(BlocksPhase.LOADING, BlocksPhase.WORKING)
    fun current() = host.isCurrent() && controller.isCurrent(state)
    fun closeTools() { tools = false; toolsVisit = Any() }
    fun act(admission: () -> Boolean = { !tools }, action: suspend () -> PortResult<BlocksState>) {
        if (!current() || busy || !admission()) return
        running = true; localFailure = null
        val job = host.launch(scope) {
            try {
                if (!controller.isCurrent(state) || !admission()) return@launch
                closeTools()
                val result = host.await(action)
                host.run { localFailure = (result as? PortResult.Failure)?.reason }
            } finally { if (host.isCurrent() && currentCoroutineContext().isActive) running = false }
        }
        if (job == null) running = false
    }
    fun back() {
        // Departure remains available during a held request. The root's exact-state leave
        // cancels only this controller's nested flight and keeps its encrypted original.
        if (!host.isCurrent() || controller.states.value !== state) return
        if (tools) closeTools()
        else if (state.screen == BlocksScreen.REVIEW && !busy) act { controller.backFromReview(state) }
        else currentClose()
    }
    // The parent mounts this only for its active BLOCKED destination. Initial open
    // can be superseded by Activity reattachment or fail before claiming the queue;
    // keep redacted chrome and local departure instead of a blank, trapped route.
    platformBackHandler(host.isCurrent(), ::back)

    val review = state.review
    val target = when {
        review != null -> review.targetUserId to (review.version ?: "block-source:${state.revision}")
        state.blockTargetUserId != null -> state.blockTargetUserId!! to "block-source:${state.revision}"
        state.selected != null -> state.selected!!.targetUserId to state.selected!!.version
        else -> null
    }
    val reference = target?.let { BlueprintControlReference(BlueprintControlReferenceKind.USER, it.first, it.second) }
    val context = state.accountId?.let { account -> BlueprintAccountControlContext(
        BlueprintControlReference(BlueprintControlReferenceKind.ACCOUNT, account, "verified-account-session"),
        "blocked-render:${state.revision}", reference)
    }
    val status = buildList {
        blockFailureCopy(localFailure ?: state.failureReason)?.let(::add)
        when (state.phase) {
            BlocksPhase.IDLE -> add(if (state.blockTargetUserId != null) "Review the selected account before blocking. Your report was a separate action. Nothing is blocked by opening this screen."
                else "Open More → Refresh blocked accounts to load this private list.")
            BlocksPhase.LOADING -> add("Loading your current account and blocked list. No block or unblock request is sent.")
            BlocksPhase.WORKING -> add("Keeping and sending your exact confirmed request. Leaving does not undo a request already sent.")
            BlocksPhase.EXPIRED -> add("This list or review expired and its details are hidden. Use More → Refresh blocked accounts.")
            BlocksPhase.RECOVERY -> add("An original ${if (state.pending?.operationId == "blockUser") "block" else "unblock"} request is retained. Its outcome is not being guessed. Open More to recover its receipt or review the same request.")
            BlocksPhase.COMPLETE -> add(if (state.blockedUserId != null) {
                if (state.blockAcknowledgementHistorical) "This is the retained acknowledgement of the earlier block, not a current blocked-list check. It will not block the account again. Refresh for current status."
                else "The server acknowledged this block. No notification of the block is sent to the account. This receipt is not a current blocked-list check; refresh for current status."
            } else "The server confirmed unblocking this account. Your old invitations, memberships and audience access were not restored. Refresh to load the current list.")
            BlocksPhase.UNAVAILABLE -> add("This account session is unavailable. Private details are hidden.")
            BlocksPhase.ERROR -> add("The list was not replaced with an empty success. Any retained original remains available for exact recovery.")
            else -> Unit
        }
        if (state.selfProfile != null) {
            add("${state.items.size} loaded blocked account${if (state.items.size == 1) "" else "s"}. This is a loaded-page count, not an unverified total.")
            add("Open More to select an account. The API provides account IDs, not profile names.")
        }
        if (state.pageLimitReached) add("The bounded list limit is reached. Refresh starts again; this does not mean there are no more accounts.")
        else if (state.hasMore) add("More blocked accounts are available through More → Load more.")
        if (state.pending != null) add("No automatic retry. A retry uses the exact original account, request and any required version; conflicts never create a replacement request.")
    }.joinToString("\n\n")
    val model = BlueprintAccountControlsState(BlueprintAccountControlPage.BLOCKED,
        phase = when {
            state.phase == BlocksPhase.UNAVAILABLE -> BlueprintAccountControlPhase.UNAVAILABLE
            busy -> BlueprintAccountControlPhase.WORKING
            state.phase == BlocksPhase.EMPTY -> BlueprintAccountControlPhase.EMPTY
            state.phase == BlocksPhase.RECOVERY -> BlueprintAccountControlPhase.UNKNOWN
            state.phase in setOf(BlocksPhase.ERROR, BlocksPhase.EXPIRED) -> BlueprintAccountControlPhase.ERROR
            else -> BlueprintAccountControlPhase.READY
        }, context = context,
        enabledActionIds = buildSet {
            add("BLOCKED.back")
            if (!busy && !tools && current() && state.screen == BlocksScreen.LIST && state.selected != null && state.pending == null && state.blockTargetUserId == null)
                add("BLOCKED.01")
            if (!busy && !tools && current() && state.screen == BlocksScreen.LIST && state.blockTargetUserId != null && state.pending == null && state.blockedUserId == null)
                add("BLOCKED.02")
        }, policies = if (state.accountId == null) emptySet() else setOf(
            BlueprintAccountPolicyFact.PRIVATE_BLOCK_LIST, BlueprintAccountPolicyFact.UNBLOCK_DOES_NOT_RESTORE_ACCESS,
            BlueprintAccountPolicyFact.BLOCKING_EFFECTS),
        statusMessage = status,
        selectedPerson = reference?.let { BlueprintControlPerson(it,
            if (state.blockTargetUserId == it.id) state.blockTargetLabel ?: "Account ${it.id}" else "Account ${it.id}",
            isBlocked = if (review?.operationId == "blockUser" || state.blockTargetUserId == it.id) null else true) },
        blockActionAvailable = state.blockTargetUserId != null && state.pending == null && state.blockedUserId == null,
        blockedCount = state.items.size.takeIf { state.selfProfile != null })
    BlueprintAccountControlsScreen(model, onEvent = { event ->
        if (event.expected === model && host.isCurrent() && controller.states.value === state &&
            event is BlueprintAccountControlEvent.Action && model.admits(event.actionId)) {
            when (event.actionId) {
                "BLOCKED.back" -> back()
                "BLOCKED.01" -> act { controller.prepareUnblock(state) }
                "BLOCKED.02" -> act { controller.prepareBlock(state) }
            }
        }
    }, onMore = { if (current() && !busy && !tools && state.screen != BlocksScreen.REVIEW) { toolsVisit = Any(); tools = true } })

    if (state.screen == BlocksScreen.REVIEW && review != null && !busy && current()) {
        AlertDialog(onDismissRequest = { act { controller.backFromReview(state) } },
            title = { Text(when (review.kind) {
                BlockReviewKind.BLOCK -> "Block this account?"
                BlockReviewKind.UNBLOCK -> "Unblock this account?"
                BlockReviewKind.RETRY_ORIGINAL -> if (review.operationId == "blockUser") "Retry the original block?" else "Retry the original unblock?"
            }) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Account ${review.targetUserId}")
                review.version?.let { Text("Reviewed block version $it.") }
                if (review.operationId == "blockUser") Text("Blocking stops new interactions and access where the block applies. It does not erase delivered messages or private copies, and no block notification is sent. Reporting remains a separate action.")
                else Text("Unblocking does not restore previous invitations, memberships or audience access.")
                if (review.kind == BlockReviewKind.RETRY_ORIGINAL)
                    Text("Only the exact original request can be retried. A previous attempt may already have reached the server.")
                else Text(if (review.kind == BlockReviewKind.BLOCK) "Nothing is sent until you select Block now." else "Nothing is sent until you select Unblock now.")
            } }, confirmButton = {
                TextButton(onClick = { act {
                    when (review.kind) {
                        BlockReviewKind.BLOCK -> controller.confirmBlock(review)
                        BlockReviewKind.UNBLOCK -> controller.confirmUnblock(review)
                        BlockReviewKind.RETRY_ORIGINAL -> controller.retryOriginal(review)
                    }
                } }) { Text(when (review.kind) { BlockReviewKind.BLOCK -> "Block now"; BlockReviewKind.UNBLOCK -> "Unblock now"; BlockReviewKind.RETRY_ORIGINAL -> "Retry original request" }) }
            }, dismissButton = { TextButton(onClick = { act { controller.backFromReview(state) } }) { Text("Not now") } })
    }
    if (tools) {
        val shownVisit = toolsVisit
        fun toolsCurrent() = tools && toolsVisit === shownVisit
        AlertDialog(onDismissRequest = ::closeTools, title = { Text("Blocked accounts") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = { act(::toolsCurrent) {
                if (state.screen == BlocksScreen.HIDDEN) controller.open() else controller.refresh(state)
            } }, enabled = !busy && state.phase != BlocksPhase.UNAVAILABLE) {
                Text("Refresh blocked accounts")
            }
            if (state.pending == null) state.items.forEach { row ->
                TextButton(onClick = { act(::toolsCurrent) { controller.select(row.id, state) } }, enabled = !busy) {
                    Text("Select account ${row.targetUserId}")
                }
            }
            if (state.hasMore) TextButton(onClick = { act(::toolsCurrent) { controller.loadMore(state) } }, enabled = !busy && !state.pageLimitReached) { Text("Load more") }
            state.pending?.let { pending ->
                Text("Original ${if (pending.operationId == "blockUser") "block" else "unblock"}: account ${pending.targetUserId}" +
                    pending.version?.let { "; reviewed version $it." }.orEmpty())
                if (pending.phase == CommandPhase.RECEIPT_READY) {
                    TextButton(onClick = { act(::toolsCurrent) { controller.recoverReceipt(state) } }, enabled = !busy) { Text("Recover confirmed receipt") }
                } else {
                    TextButton(onClick = { act(::toolsCurrent) { controller.prepareRetry(state) } }, enabled = !busy) { Text("Review original request") }
                }
                Text("Back keeps this encrypted request. This screen never replaces its key or assumes the action succeeded.")
            }
            Text("Blocking starts only from an actual account selected in the app. Opening or refreshing this list does not block anyone.")
        }
    }, confirmButton = { TextButton(onClick = ::closeTools) { Text("Close actions") } })
    }
}

private fun blockFailureCopy(reason: FailureReason?): String? = when (reason) {
    null -> null
    FailureReason.OFFLINE -> "You’re offline. Nothing is sent automatically when connectivity returns."
    FailureReason.OUTCOME_UNKNOWN -> "The result is uncertain. Keep the original request and use its recovery actions."
    FailureReason.UNAUTHENTICATED, FailureReason.STALE_SESSION -> "This account session is no longer available."
    FailureReason.CONFLICT -> "The exact version, review or retained request changed. Refresh or recover the existing original; do not assume it succeeded."
    FailureReason.RATE_LIMITED -> "The server asked you to wait. No automatic retry is scheduled."
    else -> "This action could not be confirmed. No block or unblock success is assumed without a verified receipt."
}
