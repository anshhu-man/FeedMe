package com.feedme.app.circles

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.feedme.app.FeedMeColors
import com.feedme.app.FeedMeStatusLabel
import com.feedme.app.FeedMeTheme
import com.feedme.app.FeedMeWordmark
import com.feedme.app.blueprint.BlueprintScreenId
import com.feedme.app.release.V1MobileReleaseScope
import com.feedme.mealflow.circles.*
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.SecretText
import kotlinx.coroutines.launch

/** Explicit owner-admitted tabs only. The host must recheck the supplied local-visit
 * continuation inside queued work; this descriptor grants no data or membership access. */
class CirclesTabNavigation(destinations: Set<BlueprintScreenId>,
    val navigate: (CirclesState, BlueprintScreenId, () -> Boolean) -> Unit) {
    private val admitted = V1MobileReleaseScope.admitScreens(destinations)
    val destinations: Set<BlueprintScreenId> get() = admitted.toSet()
}

/** Three actual read-only controller screens. Recomposition/reattachment never starts a
 * request. The caller owns and closes this controller, not this composable. */
@Composable
fun FeedMeCirclesFlow(controller: CirclesController,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit) =
    FeedMeCirclesFlow(controller, null, platformBackHandler)

/** Optional configured creation entry; the original two-argument read-only API is unchanged. */
@Composable
fun FeedMeCirclesFlow(controller: CirclesController, onStartCircle: ((CirclesState) -> Unit)?,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit) =
    FeedMeCirclesFlow(controller, onStartCircle, null, null, platformBackHandler = platformBackHandler)

/** Separately configured editing. Callbacks carry the exact rendered read observation;
 * displayed role is not a write grant. Existing overloads retain read/create behavior. */
@Composable
fun FeedMeCirclesFlow(controller: CirclesController, onStartCircle: ((CirclesState) -> Unit)?,
    onEditCircle: ((CirclesState) -> Unit)?, onResumeEdit: ((CirclesState) -> Unit)?,
    editEntryBusy: Boolean = false, editEntryFailure: FailureReason? = null,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit) =
    FeedMeCirclesFlow(controller, onStartCircle, onEditCircle, onResumeEdit, editEntryBusy, editEntryFailure,
        onResumeInvitation = null, platformBackHandler = platformBackHandler)

/** Optional retained invitation recovery; existing read/create/edit overloads are unchanged. */
@Composable
fun FeedMeCirclesFlow(controller: CirclesController, onStartCircle: ((CirclesState) -> Unit)?,
    onEditCircle: ((CirclesState) -> Unit)?, onResumeEdit: ((CirclesState) -> Unit)?,
    editEntryBusy: Boolean = false, editEntryFailure: FailureReason? = null,
    onResumeInvitation: ((CirclesState) -> Unit)?, invitationEntryBusy: Boolean = false,
    invitationEntryFailure: FailureReason? = null,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit) =
    FeedMeCirclesFlow(controller, onStartCircle, onEditCircle, onResumeEdit, editEntryBusy, editEntryFailure,
        onResumeInvitation, invitationEntryBusy, invitationEntryFailure, null, null,
        platformBackHandler = platformBackHandler)

/** Issued invitations are separately configured; current role is an observation, not a grant. */
@Composable
fun FeedMeCirclesFlow(controller: CirclesController, onStartCircle: ((CirclesState) -> Unit)?,
    onEditCircle: ((CirclesState) -> Unit)?, onResumeEdit: ((CirclesState) -> Unit)?,
    editEntryBusy: Boolean = false, editEntryFailure: FailureReason? = null,
    onResumeInvitation: ((CirclesState) -> Unit)?, invitationEntryBusy: Boolean = false,
    invitationEntryFailure: FailureReason? = null,
    onIssueInvitation: ((CirclesState) -> Unit)?, onResumeIssuedInvitations: ((CirclesState) -> Unit)?,
    issuedEntryBusy: Boolean = false, issuedEntryFailure: FailureReason? = null,
    onLeaveCircle: ((CirclesState) -> Unit)? = null, onResumeLeave: ((CirclesState) -> Unit)? = null,
    leaveEntryBusy: Boolean = false, leaveEntryFailure: FailureReason? = null,
    onDeleteCircle: ((CirclesState) -> Unit)? = null, onResumeDelete: ((CirclesState) -> Unit)? = null,
    deleteEntryBusy: Boolean = false, deleteEntryFailure: FailureReason? = null,
    onReportMember: ((CirclesState, CircleMemberSnapshot) -> Unit)? = null,
    onResumeReport: ((CirclesState) -> Unit)? = null,
    reportEntryBusy: Boolean = false, reportEntryFailure: FailureReason? = null,
    hostIsCurrent: () -> Boolean = { true },
    blueprintIsCurrent: (() -> Boolean)? = null,
    onOpenInvitationLink: ((CirclesState, SecretText) -> Unit)? = null,
    onRemoveMember: ((CirclesState, CircleMemberSnapshot) -> Unit)? = null,
    onResumeMemberRemoval: ((CirclesState) -> Unit)? = null,
    removalEntryBusy: Boolean = false, removalEntryFailure: FailureReason? = null,
    onChooseDirectRecipient: ((CirclesState) -> Unit)? = null,
    onTransferOwnership: ((CirclesState, CircleMemberSnapshot) -> Unit)? = null,
    onResumeOwnershipTransfer: ((CirclesState) -> Unit)? = null,
    transferEntryBusy: Boolean = false, transferEntryFailure: FailureReason? = null,
    tabNavigation: CirclesTabNavigation? = null, navigationNotice: String? = null,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit) {
    val state by controller.states.collectAsState()
    val scope = rememberCoroutineScope()
    // Every closure pins this rendered state. Never substitute states.value after suspension.
    val expected = state
    val currentHost by rememberUpdatedState(hostIsCurrent)
    val currentBlueprintHost by rememberUpdatedState(blueprintIsCurrent)
    val currentTabs by rememberUpdatedState(tabNavigation)
    // A pasted invitation is a capability. This attachment owns only transient editor text;
    // never save it, read the clipboard, or restore it when the exact observation changes.
    var invitationLink by remember(controller, state) { mutableStateOf<String?>(null) }
    var invitationLinkError by remember(controller, state) { mutableStateOf(false) }
    var invitationVisit by remember(controller, state) { mutableStateOf(Any()) }
    val expectedInvitationVisit = invitationVisit
    fun clearInvitationLink() { invitationLink = null; invitationLinkError = false; invitationVisit = Any() }
    DisposableEffect(controller, state) { onDispose { clearInvitationLink() } }
    fun current() = currentHost() && controller.states.value === expected
    fun backgroundCurrent() = current() && invitationLink == null && invitationVisit === expectedInvitationVisit
    fun canOpenInvitation() = current() && expected.screen == CirclesScreen.LIST &&
        expected.phase !in setOf(CirclesPhase.LOADING, CirclesPhase.UNAVAILABLE) && !invitationEntryBusy
    fun invitationCurrent() = canOpenInvitation() && invitationLink != null && invitationVisit === expectedInvitationVisit
    val back = {
        if (current() && invitationVisit === expectedInvitationVisit) {
            if (invitationLink != null) clearInvitationLink()
            else if (backgroundCurrent()) scope.launch { if (backgroundCurrent()) controller.back(expected) }
        }
        Unit
    }
    platformBackHandler(currentHost() && state.screen != CirclesScreen.HIDDEN, back)
    if (state.screen == CirclesScreen.HIDDEN) return
    FeedMeTheme {
        val actions = CirclesScreenActions(
            back = back,
            tabNavigation = tabNavigation?.takeIf {
                expected.screen in setOf(CirclesScreen.LIST, CirclesScreen.DETAIL, CirclesScreen.MEMBERS) &&
                    expected.phase in setOf(CirclesPhase.READY, CirclesPhase.EMPTY) &&
                    expected.failureReason == null && !editEntryBusy && !invitationEntryBusy && !issuedEntryBusy &&
                    !leaveEntryBusy && !deleteEntryBusy && !reportEntryBusy && !removalEntryBusy && !transferEntryBusy
            }?.let { tabs -> CirclesTabNavigation(tabs.destinations) { rendered, destination, presentationCurrent ->
                fun admitted() = rendered === expected && backgroundCurrent() && currentTabs === tabs &&
                    currentBlueprintHost?.invoke() == true && destination in tabs.destinations && presentationCurrent()
                if (admitted()) tabs.navigate(expected, destination, ::admitted)
            } },
            navigationNotice = navigationNotice,
            start = onStartCircle?.let { { if (current()) it(expected) } },
            edit = onEditCircle?.takeUnless { editEntryBusy }?.let { { if (current()) it(expected) } },
            resumeEdit = onResumeEdit?.takeUnless { editEntryBusy }?.let { { if (current()) it(expected) } },
            editFailure = editEntryFailure,
            resumeInvitation = onResumeInvitation?.takeUnless { invitationEntryBusy }?.let { { if (current()) it(expected) } },
            invitationFailure = invitationEntryFailure,
            openInvitationLink = onOpenInvitationLink?.takeIf { canOpenInvitation() && backgroundCurrent() }?.let { {
                if (canOpenInvitation() && backgroundCurrent()) {
                    invitationVisit = Any(); invitationLink = ""; invitationLinkError = false
                }
            } },
            issueInvitation = onIssueInvitation?.let { { if (!issuedEntryBusy && current()) it(expected) } },
            resumeIssued = onResumeIssuedInvitations?.let { { if (!issuedEntryBusy && current()) it(expected) } },
            issuedBusy = issuedEntryBusy, issuedFailure = issuedEntryFailure,
            leave = onLeaveCircle?.let { { if (!leaveEntryBusy && current()) it(expected) } },
            resumeLeave = onResumeLeave?.let { { if (!leaveEntryBusy && current()) it(expected) } },
            leaveBusy = leaveEntryBusy, leaveFailure = leaveEntryFailure,
            delete = onDeleteCircle?.let { { if (!deleteEntryBusy && current()) it(expected) } },
            resumeDelete = onResumeDelete?.let { { if (!deleteEntryBusy && current()) it(expected) } },
            deleteBusy = deleteEntryBusy, deleteFailure = deleteEntryFailure,
            report = onReportMember?.let { report -> { member ->
                if (!reportEntryBusy && current() && controller.canReportMember(member, expected)) report(expected, member)
            } },
            canReport = { member -> !reportEntryBusy && current() && controller.canReportMember(member, expected) },
            resumeReport = onResumeReport?.let { { if (!reportEntryBusy && current()) it(expected) } },
            reportBusy = reportEntryBusy, reportFailure = reportEntryFailure,
            removeMember = onRemoveMember?.let { remove -> { member ->
                if (!removalEntryBusy && current() && controller.canRemoveMember(member, expected)) remove(expected, member)
            } },
            canRemoveMember = { member -> !removalEntryBusy && current() && controller.canRemoveMember(member, expected) },
            resumeMemberRemoval = onResumeMemberRemoval?.let { { if (!removalEntryBusy && current()) it(expected) } },
            removalBusy = removalEntryBusy, removalFailure = removalEntryFailure,
            transferOwnership = onTransferOwnership?.let { transfer -> { member ->
                if (!transferEntryBusy && current() && controller.canTransferOwnership(member, expected)) transfer(expected, member)
            } },
            canTransferOwnership = { member -> !transferEntryBusy && current() && controller.canTransferOwnership(member, expected) },
            resumeOwnershipTransfer = onResumeOwnershipTransfer?.let { { if (!transferEntryBusy && current()) it(expected) } },
            transferBusy = transferEntryBusy, transferFailure = transferEntryFailure,
            chooseDirectRecipient = onChooseDirectRecipient?.takeIf {
                expected.screen == CirclesScreen.MEMBERS && expected.phase == CirclesPhase.READY &&
                    expected.failureReason == null && expected.selected?.status == "active"
            }?.let { choose -> { if (current()) choose(expected) } },
            refresh = { scope.launch {
                if (!current()) return@launch
                when (expected.screen) {
                    CirclesScreen.LIST -> controller.refreshList(expected)
                    CirclesScreen.DETAIL -> controller.refreshCircle(expected)
                    CirclesScreen.MEMBERS -> controller.refreshMembers(expected)
                    else -> Unit
                }
            } },
            open = { id -> scope.launch { if (current()) controller.openCircle(id, expected) } },
            members = { scope.launch { if (current()) controller.openMembers(expected) } },
            more = { scope.launch {
                if (!current()) return@launch
                when (expected.screen) {
                    CirclesScreen.LIST -> controller.loadMoreCircles(expected)
                    CirclesScreen.MEMBERS -> controller.loadMoreMembers(expected)
                    else -> Unit
                }
            } },
        )
        val originalShown = currentBlueprintHost != null &&
            (RetainedBlueprintCircleMembers(state, actions,
                isCurrent = { backgroundCurrent() && currentBlueprintHost?.invoke() == true }) ||
                RetainedBlueprintCircles(state, actions,
                    isCurrent = { backgroundCurrent() && currentBlueprintHost?.invoke() == true }))
        if (!originalShown) CirclesContent(state, actions)
        if (invitationLink != null) AlertDialog(onDismissRequest = { if (invitationCurrent()) clearInvitationLink() },
            title = { Text("Open invitation link") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Paste a FeedMe invitation from someone you know. Opening it checks the preview; it does not join the circle.")
                    OutlinedTextField(invitationLink.orEmpty(), { next ->
                        if (invitationCurrent()) {
                            if (next.length <= 8_192) { invitationLink = next; invitationLinkError = false }
                            else { invitationLink = ""; invitationLinkError = true }
                        }
                    }, label = { Text("Invitation link") }, enabled = invitationCurrent(), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), isError = invitationLinkError,
                        supportingText = { if (invitationLinkError) Text("Paste one non-empty link, up to 8192 characters, without line breaks or control characters.") })
                    Text("The pasted link is cleared when you open it or leave this dialog.", style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = { TextButton(onClick = {
                if (invitationCurrent()) {
                    val text = invitationLink.orEmpty()
                    if (text.isBlank() || text.length > 8_192 || text.any(Char::isISOControl)) invitationLinkError = true
                    else {
                        val secret = SecretText(text)
                        clearInvitationLink()
                        if (canOpenInvitation()) onOpenInvitationLink?.invoke(expected, secret)
                    }
                }
            }, enabled = invitationCurrent()) { Text("Open preview") } }, dismissButton = {
                TextButton(onClick = { if (invitationCurrent()) clearInvitationLink() }) { Text("Cancel") }
            })
    }
}

internal class CirclesScreenActions(val back: () -> Unit, val refresh: () -> Unit,
    val open: (String) -> Unit, val members: () -> Unit, val more: () -> Unit,
    val start: (() -> Unit)? = null, val edit: (() -> Unit)? = null, val resumeEdit: (() -> Unit)? = null,
    val editFailure: FailureReason? = null, val resumeInvitation: (() -> Unit)? = null,
    val invitationFailure: FailureReason? = null, val issueInvitation: (() -> Unit)? = null,
    val resumeIssued: (() -> Unit)? = null, val issuedBusy: Boolean = false, val issuedFailure: FailureReason? = null,
    val leave: (() -> Unit)? = null, val resumeLeave: (() -> Unit)? = null,
    val leaveBusy: Boolean = false, val leaveFailure: FailureReason? = null,
    val delete: (() -> Unit)? = null, val resumeDelete: (() -> Unit)? = null,
    val deleteBusy: Boolean = false, val deleteFailure: FailureReason? = null,
    val report: ((CircleMemberSnapshot) -> Unit)? = null,
    val canReport: (CircleMemberSnapshot) -> Boolean = { false }, val resumeReport: (() -> Unit)? = null,
    val reportBusy: Boolean = false, val reportFailure: FailureReason? = null,
    val openInvitationLink: (() -> Unit)? = null,
    val removeMember: ((CircleMemberSnapshot) -> Unit)? = null,
    val canRemoveMember: (CircleMemberSnapshot) -> Boolean = { false },
    val resumeMemberRemoval: (() -> Unit)? = null,
    val removalBusy: Boolean = false, val removalFailure: FailureReason? = null,
    val chooseDirectRecipient: (() -> Unit)? = null,
    val transferOwnership: ((CircleMemberSnapshot) -> Unit)? = null,
    val canTransferOwnership: (CircleMemberSnapshot) -> Boolean = { false },
    val resumeOwnershipTransfer: (() -> Unit)? = null,
    val transferBusy: Boolean = false, val transferFailure: FailureReason? = null,
    val tabNavigation: CirclesTabNavigation? = null, val navigationNotice: String? = null)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CirclesContent(state: CirclesState, actions: CirclesScreenActions) {
    val contentVisible = circlesContentVisible(state.screen, state.phase)
    // Revocation, loading or failure cannot leak a retained name into the header.
    val selected = state.selected.takeIf { contentVisible }
    val scroll = key(state.screen, selected?.id) { rememberScrollState() }
    Box(Modifier.fillMaxSize().background(FeedMeColors.Paper).safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(scroll)
            .padding(horizontal = 22.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = actions.back, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(circlesBackLabel(state.screen))
                }
                FeedMeWordmark(compact = true)
            }
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                FeedMeStatusLabel("KITCHEN CIRCLES", FeedMeColors.Lime)
                Text(circlesTitle(state.screen), style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.semantics { heading() })
                Text(if (state.screen == CirclesScreen.LIST) "Good food starts with good company."
                    else "A little more familiar. Still on your terms.",
                    style = MaterialTheme.typography.bodyMedium, color = FeedMeColors.Muted)
            }
            val notice = circlesStatusMessage(state.screen, state.phase, state.failureReason)
            if (notice != null) CircleNotice(notice.first, notice.second,
                if (state.phase in setOf(CirclesPhase.ERROR, CirclesPhase.UNAVAILABLE)) FeedMeColors.SoftBlue else FeedMeColors.SoftLime)
            actions.navigationNotice?.takeIf(String::isNotBlank)?.let { CircleNotice("Tab not opened", it) }
            if (state.phase == CirclesPhase.LOADING) LinearProgressIndicator(Modifier.fillMaxWidth())
            actions.resumeReport?.let { resume ->
                TextButton(onClick = resume, enabled = !actions.reportBusy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Resume report") }
            }
            if (actions.reportBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (actions.reportFailure != null) CircleNotice("Couldn’t open reporting",
                "Try again from this view. Any original report is kept; nothing was sent.")
            actions.resumeMemberRemoval?.let { resume ->
                TextButton(onClick = resume, enabled = !actions.removalBusy,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Resume member removal") }
            }
            if (actions.removalBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (actions.removalFailure != null) CircleNotice("Couldn’t open member removal",
                "Any original request is kept. No new removal was sent; use the retained request's recovery actions.")
            if (state.phase != CirclesPhase.UNAVAILABLE) circleEditFailureText(actions.editFailure)?.let {
                CircleNotice("Couldn’t open circle editing", it)
            }
            if (state.phase != CirclesPhase.UNAVAILABLE) circleInvitationFailureText(actions.invitationFailure)?.let {
                CircleNotice("Couldn’t open invitation recovery", it)
            }
            if (state.phase != CirclesPhase.UNAVAILABLE) circleIssuedFailure(actions.issuedFailure)?.let {
                CircleNotice("Couldn’t open issued invitations", it)
            }
            if (actions.issuedBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (actions.leaveBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.phase != CirclesPhase.UNAVAILABLE) circleLeaveFailureText(actions.leaveFailure)?.let {
                CircleNotice("Couldn’t open leave review", it)
            }
            if (actions.deleteBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.phase != CirclesPhase.UNAVAILABLE) circleLeaveFailureText(actions.deleteFailure, CircleDepartureAction.DELETE)?.let {
                CircleNotice("Couldn’t open circle deletion", it)
            }
            if (state.screen == CirclesScreen.LIST && circlesCanRead(state.screen, state.phase)) {
                actions.openInvitationLink?.let { open ->
                    OutlinedButton(onClick = open, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Open invitation link") }
                }
                actions.start?.let { start ->
                    Button(onClick = start, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Start a circle") }
                }
                actions.resumeInvitation?.let { resume ->
                    OutlinedButton(onClick = resume, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Resume join request") }
                }
                actions.resumeIssued?.let { resume ->
                    OutlinedButton(onClick = resume, enabled = !actions.issuedBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Invitations issued on this device") }
                }
                actions.resumeEdit?.let { resume ->
                    OutlinedButton(onClick = resume, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Resume circle edits") }
                }
                actions.resumeLeave?.let { resume ->
                    OutlinedButton(onClick = resume, enabled = !actions.leaveBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Resume leave request") }
                }
                actions.resumeDelete?.let { resume ->
                    OutlinedButton(onClick = resume, enabled = !actions.deleteBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Resume circle deletion") }
                }
            }
            if (circlesCanRead(state.screen, state.phase)) {
                OutlinedButton(onClick = actions.refresh, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(circlesRefreshLabel(state.screen))
                }
            }
            state.retryAfterSeconds?.takeIf { state.phase == CirclesPhase.ERROR }?.let {
                Text("The service asked you to wait $it seconds before retrying. Nothing retries automatically.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (contentVisible) when (state.screen) {
                CirclesScreen.LIST -> {
                    Text(circlesLoadedText(state.circles.size),
                        style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
                    state.circles.forEachIndexed { index, circle -> CircleCard(circle, actions.open, index + 1) }
                }
                CirclesScreen.DETAIL -> {
                    if (selected == null) CircleNotice("Circle unavailable", "No current circle details are available. Back and Refresh remain available.")
                    else {
                        CircleIdentity(selected)
                        actions.edit?.takeIf { state.phase == CirclesPhase.READY && selected.status == "active" && circleEditRoleVisible(selected.role) }?.let { edit ->
                            Button(onClick = edit, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Edit circle details") }
                        }
                        actions.issueInvitation?.takeIf { state.phase == CirclesPhase.READY && selected.status == "active" && circleEditRoleVisible(selected.role) }?.let { issue ->
                            Button(onClick = issue, enabled = !actions.issuedBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(CIRCLE_ISSUED_TITLE) }
                        }
                        Button(onClick = actions.members, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("View members") }
                        if (actions.leave != null && state.phase == CirclesPhase.READY && selected.status == "active") {
                            if (circleLeaveRoleVisible(selected.role)) OutlinedButton(onClick = actions.leave, enabled = !actions.leaveBusy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Review leaving circle") }
                            else if (selected.role == "owner") Text("Circle owners need to transfer ownership before leaving.", style = MaterialTheme.typography.bodySmall)
                        }
                        if (actions.delete != null && state.phase == CirclesPhase.READY && selected.status == "active" && selected.role == "owner") {
                            OutlinedButton(onClick = actions.delete, enabled = !actions.deleteBusy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Delete my circle") }
                        }
                    }
                }
                CirclesScreen.MEMBERS -> {
                    selected?.let {
                        Text(it.name.ifEmpty { "Unnamed circle" }, style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { heading() })
                    }
                    Text(circleMembersLoadedText(state.members.size),
                        style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
                    state.members.forEach { member -> key(member.id) {
                        MemberCard(member, actions.report?.takeIf { actions.canReport(member) }?.let { report -> { report(member) } })
                    } }
                }
                else -> Unit
            }
            if (circlesCanLoadMore(state.screen, state.phase, state.hasMore)) {
                OutlinedButton(onClick = actions.more, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(if (state.screen == CirclesScreen.MEMBERS) "Load more members" else "Load more circles")
                }
            }
            if (contentVisible && state.pageLimitReached) CircleNotice("More may be available",
                CIRCLES_PAGE_LIMIT_NOTICE)
            Text(if (actions.leave != null || actions.resumeLeave != null) "Leaving requires a separate review. It removes only your membership, not your posts or account."
                else if (actions.issueInvitation != null || actions.resumeIssued != null) "Invitations do not automatically share a post or remove a member."
                else if (actions.start == null && actions.edit == null) CIRCLES_READ_NOTICE else "Sharing and member changes aren’t available here yet.",
                style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
            CircleNotice("What’s next", if (actions.leave != null || actions.resumeLeave != null)
                "Leaving is optional. Managing another member, transferring ownership and closing a circle are separate actions."
                else if (actions.issueInvitation != null || actions.resumeIssued != null)
                "Share a link only through an explicitly connected device share sheet. Post sharing and member changes remain separate."
                else if (actions.resumeInvitation != null) CIRCLE_INVITATION_SCOPE_NOTICE
                else if (actions.start == null && actions.edit == null) CIRCLES_NOT_CONNECTED
                else "Inviting friends, joining circles and sharing posts aren’t connected here yet.")
        }
    }
}

@Composable
private fun CircleCard(circle: CircleSnapshot, open: (String) -> Unit, ordinal: Int) {
    Surface(color = FeedMeColors.Surface, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
        Column(Modifier.fillMaxWidth().padding(19.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            CircleIdentity(circle)
            val label = circleOpenLabel(circle.name)
            Button(onClick = { open(circle.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .semantics { contentDescription = "$label. Circle $ordinal in this view" }) { Text(label) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CircleIdentity(circle: CircleSnapshot) {
    Text(circle.name.ifEmpty { "Unnamed circle" }, style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() })
    circleDescription(circle.description)?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = FeedMeColors.Muted)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FeedMeStatusLabel("Your role: ${circleRoleLabel(circle.role)}", FeedMeColors.SoftBlue)
        FeedMeStatusLabel(circleMemberCountText(circle.memberCount), FeedMeColors.SoftLime)
        FeedMeStatusLabel(circleStatusLabel(circle.status), FeedMeColors.Surface)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemberCard(member: CircleMemberSnapshot, report: (() -> Unit)? = null) {
    Surface(color = FeedMeColors.Surface, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, FeedMeColors.Line)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(member.displayName.ifEmpty { "Unnamed member" }, style = MaterialTheme.typography.titleMedium)
            Text("Handle: ${member.handle}", style = MaterialTheme.typography.bodyMedium, color = FeedMeColors.Muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FeedMeStatusLabel(circleRoleLabel(member.role), FeedMeColors.SoftBlue)
                FeedMeStatusLabel(circleStatusLabel(member.status), FeedMeColors.SoftLime)
            }
            report?.let { TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) { Text("Report this person") } }
        }
    }
}

@Composable
private fun CircleNotice(title: String, message: String, color: androidx.compose.ui.graphics.Color = FeedMeColors.SoftBlue) {
    Surface(color = color, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
