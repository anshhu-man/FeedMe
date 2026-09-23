package com.feedme.app.circles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feedme.app.FeedMeColors
import com.feedme.app.blueprint.*
import com.feedme.contracts.WireField
import com.feedme.mealflow.circles.*

/** Render identity only: membershipRevision below names this exact read observation, NOT a
 * server membership generation. The actual controller/host and expected state still admit every
 * action. No member identities, meals, or complete-roster claims are manufactured from a count. */
internal fun CircleSnapshot.blueprintSummary(context: BlueprintCommunityContext) = BlueprintCircleSummary(
    BlueprintCircleBinding(BlueprintCommunityRef(BlueprintCommunityKind.CIRCLE, id, version), context.revision),
    name, circleDescription(description).orEmpty(), memberCount = circleMemberCountText(memberCount))

internal fun blueprintCirclesList(state: CirclesState, context: BlueprintCommunityContext,
    create: Boolean, issued: Boolean, busy: Boolean = false, join: Boolean = false,
    allowedNavigation: Set<BlueprintScreenId> = emptySet(), navigationNotice: String? = null): BlueprintCirclesState? {
    if (state.screen != CirclesScreen.LIST || !circlesContentVisible(state.screen, state.phase) || state.failureReason != null) return null
    val circles = state.circles.map { it.blueprintSummary(context) }
    if (circles.map { it.binding.circle.id }.distinct().size != circles.size) return null
    return BlueprintCirclesState(context, circles, controls = BlueprintCommunityControls(loading = busy,
        allowedActionIds = if (busy) emptySet() else buildSet {
            add(BlueprintCircleAction.OPEN.id)
            if (create) add(BlueprintCircleAction.CREATE.id)
            if (join) add(BlueprintCircleAction.JOIN.id)
            if (issued) add(BlueprintCircleAction.MANAGE_INVITES.id)
        }, allowedNavigation = if (busy || state.phase !in setOf(CirclesPhase.READY, CirclesPhase.EMPTY)) emptySet()
            else allowedNavigation.intersect(setOf(BlueprintScreenId.HOME, BlueprintScreenId.TODAY,
                BlueprintScreenId.COOKBOOK, BlueprintScreenId.INBOX, BlueprintScreenId.PROFILE_PLATE)),
        status = listOfNotNull(if (state.pageLimitReached) CIRCLES_PAGE_LIMIT_NOTICE else null,
            navigationNotice?.takeIf(String::isNotBlank)).joinToString("\n\n").takeIf(String::isNotBlank)))
}

internal fun blueprintCircleDetail(state: CirclesState, context: BlueprintCommunityContext,
    issue: Boolean, edit: Boolean, leave: Boolean, delete: Boolean = false, busy: Boolean = false,
    allowedNavigation: Set<BlueprintScreenId> = emptySet(), navigationNotice: String? = null): BlueprintCircleState? {
    if (state.screen != CirclesScreen.DETAIL || state.phase != CirclesPhase.READY || state.failureReason != null) return null
    val selected = state.selected?.takeIf { it.status == "active" } ?: return null
    return BlueprintCircleState(context, selected.blueprintSummary(context), controls = BlueprintCommunityControls(loading = busy,
        allowedActionIds = if (busy) emptySet() else buildSet {
            add(BlueprintCircleAction.MEMBERS.id)
            if (issue && circleEditRoleVisible(selected.role)) add(BlueprintCircleAction.INVITE_FRIEND.id)
            if (edit && circleEditRoleVisible(selected.role)) add(BlueprintCircleAction.EDIT.id)
            if (leave && circleLeaveRoleVisible(selected.role)) add(BlueprintCircleAction.LEAVE.id)
            if (delete && selected.role == "owner") add(BlueprintCircleAction.DELETE.id)
        }, allowedNavigation = if (busy) emptySet() else allowedNavigation.intersect(setOf(BlueprintScreenId.HOME,
            BlueprintScreenId.TODAY, BlueprintScreenId.COOKBOOK, BlueprintScreenId.INBOX, BlueprintScreenId.PROFILE_PLATE)),
        status = navigationNotice?.takeIf(String::isNotBlank)))
}

internal fun dispatchBlueprintCircleList(model: BlueprintCirclesState, intent: BlueprintCircleIntent,
    current: Boolean, open: (String) -> Unit, create: () -> Unit, issued: () -> Unit,
    join: (() -> Unit)? = null) {
    if (!current || intent.context !== model.context) return
    val admitted = model.intent(intent.action, intent.circle) ?: return
    if (admitted.circle != intent.circle || admitted.picker != intent.picker) return
    when (intent.action) {
        BlueprintCircleAction.OPEN -> intent.circle?.let { open(it.circle.id) }
        BlueprintCircleAction.CREATE -> create()
        BlueprintCircleAction.JOIN -> join?.invoke()
        BlueprintCircleAction.MANAGE_INVITES -> issued()
        else -> Unit
    }
}

/** Preserve the exact rendered list and local More visit through the root's queued handoff. */
internal fun dispatchBlueprintCirclesTab(model: BlueprintCirclesState, state: CirclesState,
    destination: BlueprintScreenId, current: () -> Boolean, navigation: CirclesTabNavigation?) {
    dispatchBlueprintCircleTab(model.controls, state, destination, current, navigation, CirclesScreen.LIST)
}
internal fun dispatchBlueprintCircleTab(controls: BlueprintCommunityControls, state: CirclesState,
    destination: BlueprintScreenId, current: () -> Boolean, navigation: CirclesTabNavigation?,
    screen: CirclesScreen = state.screen) {
    val tabs = navigation ?: return
    fun admitted() = current() && state.screen == screen && controls.navigate(destination) && destination in tabs.destinations
    if (admitted()) tabs.navigate(state, destination, ::admitted)
}
internal fun dispatchBlueprintCircleDetail(model: BlueprintCircleState, intent: BlueprintCircleIntent,
    current: Boolean, members: () -> Unit, issue: () -> Unit, edit: () -> Unit, leave: () -> Unit,
    delete: () -> Unit = {}) {
    if (!current || intent.context !== model.context) return
    val admitted = model.intent(intent.action) ?: return
    if (admitted.circle != intent.circle) return
    when (intent.action) {
        BlueprintCircleAction.MEMBERS -> members()
        BlueprintCircleAction.INVITE_FRIEND -> issue()
        BlueprintCircleAction.EDIT -> edit()
        BlueprintCircleAction.LEAVE -> leave()
        BlueprintCircleAction.DELETE -> delete()
        else -> Unit
    }
}

/** Only an unsent editable draft takes the original form. Real reviews, originals and receipts
 * remain on the existing recovery UI; Create circle here asks for review, never sends a command. */
internal fun circleCreateBlueprintEligible(screen: CircleCreateScreen, phase: CircleCreatePhase,
    pending: Boolean, result: Boolean, completion: Boolean, failure: Boolean) =
    screen == CircleCreateScreen.FORM && phase in setOf(CircleCreatePhase.IDLE, CircleCreatePhase.READY) &&
        !pending && !result && !completion && !failure

/** Preserve absent description until its field is explicitly changed, including clearing it. */
internal fun circleBlueprintDescriptionIncluded(previous: String, next: String, included: Boolean) = included || previous != next

@Composable
internal fun RetainedBlueprintCircles(state: CirclesState, actions: CirclesScreenActions,
    isCurrent: () -> Boolean): Boolean {
    val context = remember(state) { BlueprintCommunityContext("retained-circles", "exact-render-observation") }
    val busy = actions.issuedBusy || actions.leaveBusy || actions.deleteBusy || actions.reportBusy || actions.removalBusy || actions.transferBusy
    if (actions.editFailure != null || actions.invitationFailure != null || actions.issuedFailure != null || actions.leaveFailure != null || actions.deleteFailure != null || actions.reportFailure != null) return false
    val list = blueprintCirclesList(state, context, actions.start != null, actions.resumeIssued != null, busy,
        join = actions.openInvitationLink != null, allowedNavigation = actions.tabNavigation?.destinations.orEmpty(),
        navigationNotice = actions.navigationNotice)
    val detail = blueprintCircleDetail(state, context, actions.issueInvitation != null, actions.edit != null,
        actions.leave != null, actions.delete != null, busy, actions.tabNavigation?.destinations.orEmpty(), actions.navigationNotice)
    if (list == null && detail == null) return false
    var tools by remember(state) { mutableStateOf(false) }
    var visit by remember(state) { mutableStateOf(Any()) }
    DisposableEffect(state) { onDispose { tools = false; visit = Any() } }
    val expectedVisit = visit
    fun backgroundCurrent() = isCurrent() && !tools && visit === expectedVisit
    fun toolsCurrent() = isCurrent() && tools && visit === expectedVisit
    fun closeTools() { tools = false; visit = Any() }
    fun toolAction(action: () -> Unit) { if (toolsCurrent()) { closeTools(); action() } }
    val more = { if (backgroundCurrent()) { visit = Any(); tools = true }; Unit }
    if (list != null) {
        val visible = list.copy(controls = list.controls.copy(enabled = list.controls.enabled && !tools && isCurrent()))
        BlueprintCirclesScreen(visible, onAction = { intent ->
            dispatchBlueprintCircleList(visible, intent, backgroundCurrent(), actions.open, { actions.start?.invoke() },
                { actions.resumeIssued?.invoke() }, actions.openInvitationLink)
        }, onBack = { if (backgroundCurrent()) actions.back() }, onMore = more,
            onNavigate = { dispatchBlueprintCirclesTab(visible, state, it, ::backgroundCurrent, actions.tabNavigation) })
    }
    else if (detail != null) BlueprintCircleScreen(detail, onAction = { intent ->
        dispatchBlueprintCircleDetail(detail, intent, backgroundCurrent(), actions.members,
            { actions.issueInvitation?.invoke() }, { actions.edit?.invoke() }, { actions.leave?.invoke() }, { actions.delete?.invoke() })
    }, onBack = { if (backgroundCurrent()) actions.back() }, onMore = more,
        onNavigate = { dispatchBlueprintCircleTab(detail.controls, state, it, ::backgroundCurrent,
            actions.tabNavigation, CirclesScreen.DETAIL) })
    if (tools) AlertDialog(onDismissRequest = { if (toolsCurrent()) closeTools() }, title = { Text("Circle tools") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            actions.navigationNotice?.takeIf(String::isNotBlank)?.let { Text(it) }
            TextButton(onClick = { toolAction(actions.refresh) }) { Text(circlesRefreshLabel(state.screen)) }
            if (circlesCanLoadMore(state.screen, state.phase, state.hasMore)) TextButton(onClick = { toolAction(actions.more) }) { Text("Load more circles") }
            if (state.screen == CirclesScreen.LIST) actions.openInvitationLink?.let { action ->
                TextButton(onClick = { toolAction(action) }) { Text("Open invitation link") }
            }
            actions.resumeInvitation?.let { action -> TextButton(onClick = { toolAction(action) }) { Text("Resume join request") } }
            actions.resumeIssued?.let { action -> TextButton(onClick = { toolAction(action) }, enabled = !actions.issuedBusy) { Text("Invitations issued on this device") } }
            actions.resumeEdit?.let { action -> TextButton(onClick = { toolAction(action) }) { Text("Resume circle edits") } }
            actions.resumeLeave?.let { action -> TextButton(onClick = { toolAction(action) }, enabled = !actions.leaveBusy) { Text("Resume leave request") } }
            actions.resumeDelete?.let { action -> TextButton(onClick = { toolAction(action) }, enabled = !actions.deleteBusy) { Text("Resume circle deletion") } }
            actions.resumeReport?.let { action -> TextButton(onClick = { toolAction(action) }, enabled = !actions.reportBusy) { Text("Resume report") } }
            actions.removalFailure?.let { Text("Member removal could not open. Any original is retained; no new removal was sent.") }
            actions.resumeMemberRemoval?.let { action -> TextButton(onClick = { toolAction(action) }, enabled = !actions.removalBusy) { Text("Resume member removal") } }
            Text("Shared meals and group-planning actions are not connected in this view. Leaving still needs its separate review.", style = BlueprintType.Small)
        } }, confirmButton = { TextButton(onClick = { if (toolsCurrent()) closeTools() }) { Text("Close tools") } })
    return true
}

/** The canonical public preview intentionally has labels, not an inviter UUID or invitation ID.
 * This display never forges either. Its callbacks retain the actual opaque selection separately. */
internal data class CirclePublicPreviewDisplay(val circleName: String, val inviterLabel: String) {
    override fun toString() = "CirclePublicPreviewDisplay(<redacted>)"
}
internal fun circlePublicPreviewDisplay(phase: CircleInvitationPreviewPhase, selectionCurrent: Boolean,
    targetName: WireField<String>, inviterLabel: WireField<String>): CirclePublicPreviewDisplay? {
    if (phase != CircleInvitationPreviewPhase.READY || !selectionCurrent) return null
    val name = (targetName as? WireField.Value)?.value?.takeIf { it.isNotBlank() } ?: return null
    val label = (inviterLabel as? WireField.Value)?.value?.takeIf { it.isNotBlank() } ?: return null
    return CirclePublicPreviewDisplay(name, label)
}

@Composable
internal fun RetainedInvitationBlueprint(display: CirclePublicPreviewDisplay, primaryEnabled: Boolean,
    requiresSignIn: Boolean, busy: Boolean, onPrimary: () -> Unit, onBack: () -> Unit, onMore: () -> Unit) {
    CommunityScaffold(BlueprintScreenId.INVITE_ACCEPT, BlueprintCommunityControls(loading = busy), onBack, onMore, {}, Modifier, navigation = false) {
        BlueprintHero("THERE’S A SEAT FOR YOU", "Dinner ideas.\nOn the house.", "A little invite into someone’s kitchen circle.")
        Column(Modifier.fillMaxWidth().background(FeedMeColors.Lilac, RoundedCornerShape(24.dp)).padding(26.dp, 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(23.dp)) {
            Box(Modifier.size(56.dp).background(FeedMeColors.SoftBlue, RoundedCornerShape(50)), contentAlignment = Alignment.Center) { BlueprintIcon("user", Modifier.size(28.dp)) }
            Text("${display.inviterLabel} invited you to", style = BlueprintType.Body)
            Text(display.circleName, fontSize = 46.sp, lineHeight = 44.62.sp, fontWeight = FontWeight.Black, letterSpacing = (-2).sp, textAlign = TextAlign.Center)
            CommunityPill("INVITED ONLY")
        }
        BlueprintButton("Take my seat", onPrimary, Modifier.fillMaxWidth(), "primary", "people", primaryEnabled)
        BlueprintButton("Not this time", onBack, Modifier.fillMaxWidth(), "ghost")
        Text(if (requiresSignIn) "Sign in to review joining. Nothing is joined automatically." else "Next, review joining before any join request is sent.", style = BlueprintType.Small, color = FeedMeColors.Muted)
        CommunityNote("Joining does not publish your meals or share your private food preferences.")
    }
}
