package com.feedme.app.mealflow

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feedme.app.FeedMeTheme
import com.feedme.app.blueprint.*
import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireField
import com.feedme.core.ports.*
import com.feedme.mealflow.remix.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Original REMIX_TRAIL. Actual nodes are read observations, not invented versioned Posts.
 * Opening a node hands its exact lifetime-bound selection to a fresh getPost lookup. */
@Composable
fun FeedMeRemixTrailFlow(controller: RemixTrailController, hostIsCurrent: () -> Boolean,
    onClose: () -> Unit, onOpenPost: (RemixTrailState, RemixPostSelection) -> Unit,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> },
    onNavigate: ((BlueprintScreenId) -> Unit)? = null,
    openPostFailure: FailureReason? = null, postOpening: Boolean = false) {
    val observed by controller.states.collectAsState(); val state = observed
    val host by rememberUpdatedState(hostIsCurrent); val scope = rememberCoroutineScope()
    var attached by remember(controller) { mutableStateOf(true) }
    DisposableEffect(controller) { onDispose { attached = false } }
    var more by remember(controller) { mutableStateOf(false) }
    var busy by remember(controller) { mutableStateOf(false) }
    var leaveFailure by remember(controller) { mutableStateOf(false) }
    fun current() = attached && host() && controller.isCurrent(state)
    // Local departure needs attachment + exact presentation identity, not permission to
    // read private data. An invalidated account must still be able to dismiss this flow.
    fun canLeave() = attached && controller.states.value === state && state.phase != RemixTrailPhase.HIDDEN
    fun leave(destination: BlueprintScreenId? = null) {
        if (!canLeave() || destination != null && !current()) return
        scope.launch { withContext(NonCancellable) {
            when (val result = controller.leave(state)) {
                is PortResult.Value -> if (controller.states.value === result.value && result.value.phase == RemixTrailPhase.HIDDEN) {
                    if (destination == null) onClose() else onNavigate?.invoke(destination)
                }
                is PortResult.Failure -> if (attached) leaveFailure = true
            }
        } }
    }
    fun back() { if (more && current()) more = false else leave() }
    fun act(action: suspend () -> Unit) {
        if (!current() || busy || postOpening) return
        busy = true; scope.launch { try { if (current()) action() } finally { busy = false } }
    }
    platformBackHandler(canLeave(), ::back)
    val ready = current() && state.phase in setOf(RemixTrailPhase.READY, RemixTrailPhase.EMPTY)
    val actual = if (ready) state.nodes else emptyList()
    val versions = actual.mapNotNull { node ->
        val author = node.author ?: return@mapNotNull null
        val userId = author.remixText("userId") ?: return@mapNotNull null
        val name = author.remixText("displayName")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        BlueprintRemixVersion(label = if (node.postId == state.rootPostId) "STARTING POST" else "SHARED VERSION",
            observation = BlueprintRemixObservation(node.postId, BlueprintSocialPerson(userId, name), node.title?.takeIf(String::isNotBlank) ?: "Shared plate"),
            actions = if (!busy && !postOpening) setOf(BlueprintAuthoringAction.OPEN_VERSION) else emptySet())
    }
    val root = state.rootPost.takeIf { ready }
    val rootAuthor = root?.document?.remixMember("author")?.remixText("userId")
    val rootVersion = root?.version?.toLongOrNull()?.takeIf { it > 0 }
    val context = if (root != null && rootAuthor != null && rootVersion != null)
        BlueprintAuthoringContext(BlueprintAuthoringIdentity(rootAuthor, root.id, rootVersion), BlueprintAuthoringMode.READ_TRAIL, true) else null
    val navigation = mapOf(BlueprintAuthoringAction.REMIX_COOK to BlueprintScreenId.HOME,
        BlueprintAuthoringAction.REMIX_COOKBOOK to BlueprintScreenId.COOKBOOK,
        BlueprintAuthoringAction.REMIX_TODAY to BlueprintScreenId.TODAY,
        BlueprintAuthoringAction.REMIX_INBOX to BlueprintScreenId.INBOX,
        BlueprintAuthoringAction.REMIX_PLATE to BlueprintScreenId.PROFILE_PLATE)
    val status = buildList {
        add(when {
            postOpening -> "Checking access to this post…"
            state.phase == RemixTrailPhase.LOADING -> "Loading the versions shared with you…"
            state.phase == RemixTrailPhase.EXPIRED -> "This trail needs a fresh check. Use More → Refresh."
            state.phase == RemixTrailPhase.ERROR -> if (state.failureReason == FailureReason.OFFLINE)
                "You’re offline. No remote trail is cached here." else "This trail couldn’t be loaded. Use More → Refresh to try again."
            state.phase == RemixTrailPhase.UNAVAILABLE -> "This trail is unavailable for this account or service."
            state.phase == RemixTrailPhase.EMPTY -> "No visible versions were returned on this page."
            else -> "Open a shared version to check its current post."
        })
        add("Private or unavailable history may be omitted. This is a bounded view, not a complete history or a count of takes.")
        if (versions.size != actual.size) add("Some returned entries lack display details and are not shown.")
        if (state.pageLimitReached) add("This view reached its page limit.")
        if (openPostFailure != null) add("That post could not be opened. No recipe or media access was granted.")
        if (leaveFailure) add("Couldn’t leave this view. Try Back again.")
        state.retryAfterSeconds?.let { add("Wait $it seconds before trying again.") }
    }.joinToString("\n\n")
    val model = BlueprintRemixTrailState(context, versions, hasMore = ready && state.hasMore && !busy && !postOpening,
        controls = BlueprintAuthoringControls(enabled = canLeave(), moreEnabled = current(), actions = buildSet {
            if (canLeave()) add(BlueprintAuthoringAction.REMIX_BACK)
            if (ready && !busy && !postOpening) { add(BlueprintAuthoringAction.OPEN_VERSION); if (state.hasMore) add(BlueprintAuthoringAction.MORE_VERSIONS) }
            if (onNavigate != null && current() && canLeave() && !postOpening) addAll(navigation.keys)
        }), status = status)
    BlueprintRemixTrailScreen(model, onAction = { expected, action, version ->
        if (expected === model) when {
            action == BlueprintAuthoringAction.REMIX_BACK && model.allows(action) -> back()
            action == BlueprintAuthoringAction.MORE_VERSIONS && model.allows(action) -> act { controller.loadMore(state) }
            action == BlueprintAuthoringAction.OPEN_VERSION && model.allows(action, version) -> {
                val node = actual.singleOrNull { it.postId == version?.postId }
                if (node != null) act {
                    when (val selection = controller.select(node, state)) {
                        is PortResult.Value -> if (current()) onOpenPost(state, selection.value)
                        is PortResult.Failure -> Unit
                    }
                }
            }
            action in navigation && model.allows(action) -> leave(navigation.getValue(action))
        }
    }, onMore = { if (it === model && attached && host()) more = true })
    if (more && attached && host()) FeedMeTheme {
        AlertDialog(onDismissRequest = { more = false }, title = { Text("This trail") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("The trail shows authorized ancestry only. A thumbnail ID does not grant photo access; opening a post checks again. Making or sharing a version needs its own recipe and publication checks.")
                OutlinedButton(onClick = { more = false; act { controller.refresh(state) } },
                    enabled = current() && !busy && !postOpening && state.phase != RemixTrailPhase.LOADING,
                    modifier = Modifier.fillMaxWidth()) { Text("Refresh") }
                if (state.hasMore) OutlinedButton(onClick = { more = false; act { controller.loadMore(state) } },
                    enabled = ready && !busy && !postOpening, modifier = Modifier.fillMaxWidth()) { Text("More shared versions") }
            }
        }, confirmButton = { TextButton(onClick = { more = false }) { Text("Close") } })
    }
}

private fun WireDocument.remixMember(name: String): WireDocument? = (field(name) as? WireField.Value)?.value
private fun WireDocument.remixText(name: String): String? = remixMember(name)?.stringOrNull()
