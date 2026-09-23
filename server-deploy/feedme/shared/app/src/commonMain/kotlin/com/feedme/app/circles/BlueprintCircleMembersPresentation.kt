package com.feedme.app.circles

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
import com.feedme.mealflow.circles.*

/** Membership identity/version are retained as render correlation only. The response has no
 * account version or membership-generation grant; these bindings never authorize a write. */
private fun CircleMemberSnapshot.blueprintMember(context: BlueprintCommunityContext) = BlueprintCircleMember(
    BlueprintMemberBinding(BlueprintCommunityRef(BlueprintCommunityKind.USER, userId,
        "observed-membership:$id:$version"), version, context.revision),
    BlueprintSocialPerson(userId, displayName), circleRoleLabel(role), circleStatusLabel(status), role == "owner")

@Composable
internal fun RetainedBlueprintCircleMembers(state: CirclesState, actions: CirclesScreenActions,
    isCurrent: () -> Boolean): Boolean {
    if (state.screen != CirclesScreen.MEMBERS) return false
    val context = remember(state) { BlueprintCommunityContext("retained-circle-members", "exact-render-observation") }
    var selected by remember(state) { mutableStateOf<BlueprintMemberBinding?>(null) }
    var tools by remember(state) { mutableStateOf(false) }
    val visible = circlesContentVisible(state.screen, state.phase) && state.failureReason == null
    val circle = state.selected?.takeIf { visible && it.status == "active" }?.blueprintSummary(context)
    val members = if (circle != null) state.members.map { it.blueprintMember(context) } else emptyList()
    val selectedMember = state.members.singleOrNull { row -> members.singleOrNull { it.binding == selected }?.person?.userId == row.userId }
    val busy = actions.issuedBusy || actions.reportBusy || actions.removalBusy || actions.transferBusy || state.phase == CirclesPhase.LOADING
    val canInvite = !busy && circle != null && state.selected?.role?.let(::circleEditRoleVisible) == true && actions.issueInvitation != null
    val canReport = !busy && selectedMember != null && actions.report != null && actions.canReport(selectedMember)
    val canRemove = !busy && selectedMember != null && actions.removeMember != null && actions.canRemoveMember(selectedMember)
    val canTransfer = !busy && selectedMember != null && actions.transferOwnership != null && actions.canTransferOwnership(selectedMember)
    val status = buildList {
        circlesStatusMessage(state.screen, state.phase, state.failureReason)?.let { add("${it.first}. ${it.second}") }
        if (visible) add(circleMembersLoadedText(members.size))
        if (state.pageLimitReached) add(CIRCLES_PAGE_LIMIT_NOTICE)
        circleIssuedFailure(actions.issuedFailure)?.let(::add)
        if (actions.reportFailure != null) add("Couldn’t open reporting. Any original report is kept; nothing was sent.")
        if (actions.removalFailure != null) add("Couldn’t open member removal. Any retained original is kept; no new removal was sent.")
        if (actions.transferFailure != null) add("Couldn’t open ownership transfer. Any original request stays kept; no replacement was sent.")
        if (visible && circle == null) add("Current circle details are unavailable. Refresh before selecting a member.")
    }.joinToString("\n").ifEmpty { null }
    val model = BlueprintCircleMembersState(context, circle, members, selected, BlueprintCommunityControls(
        loading = busy, unavailable = state.phase == CirclesPhase.UNAVAILABLE,
        allowedActionIds = buildSet {
            if (canInvite) add(BlueprintCircleAction.INVITE_MEMBER.id)
            if (canReport) add(BlueprintCircleAction.REPORT_MEMBER.id)
            if (canRemove) add(BlueprintCircleAction.REMOVE_MEMBER.id)
            if (canTransfer) add(BlueprintCircleAction.TRANSFER_OWNER.id)
        }, editableFields = if (visible && !busy) setOf("member") else emptySet(),
        allowedNavigation = if (busy) emptySet() else actions.tabNavigation?.destinations.orEmpty().intersect(
            setOf(BlueprintScreenId.HOME, BlueprintScreenId.TODAY, BlueprintScreenId.COOKBOOK,
                BlueprintScreenId.INBOX, BlueprintScreenId.PROFILE_PLATE)),
        status = listOfNotNull(status, actions.navigationNotice?.takeIf(String::isNotBlank)).joinToString("\n\n").takeIf(String::isNotBlank),
        actionMessages = mapOf(BlueprintCircleAction.REMOVE_MEMBER.id to
            "Select an active non-owner member you can manage to review removal. Displayed roles are not a write grant.",
            BlueprintCircleAction.TRANSFER_OWNER.id to "Only the current owner can review transfer to an active member. You become a member after transfer.")))
    BlueprintCircleMembersScreen(model,
        onSelect = { ctx, binding, member ->
            if (isCurrent() && ctx === context && model.visibleCircle?.binding == binding &&
                model.controls.edit("member") && model.visibleMembers.count { it.binding == member } == 1) selected = member
        }, onAction = { intent ->
            if (isCurrent() && intent.context === context) {
                val admitted = model.intent(intent.action, intent.member)
                if (admitted != null && admitted.circle == intent.circle && admitted.member == intent.member) when (intent.action) {
                    BlueprintCircleAction.INVITE_MEMBER -> actions.issueInvitation?.invoke()
                    BlueprintCircleAction.REPORT_MEMBER -> selectedMember?.takeIf { actions.canReport(it) }?.let { actions.report?.invoke(it) }
                    BlueprintCircleAction.REMOVE_MEMBER -> selectedMember?.takeIf { actions.canRemoveMember(it) }?.let { actions.removeMember?.invoke(it) }
                    BlueprintCircleAction.TRANSFER_OWNER -> selectedMember?.takeIf { actions.canTransferOwnership(it) }?.let { actions.transferOwnership?.invoke(it) }
                    else -> Unit
                }
            }
        }, onBack = { if (isCurrent()) actions.back() }, onMore = { if (isCurrent()) tools = true },
        onNavigate = { dispatchBlueprintCircleTab(model.controls, state, it, isCurrent,
            actions.tabNavigation, CirclesScreen.MEMBERS) })
    if (tools) AlertDialog(onDismissRequest = { tools = false }, title = { Text("Member tools") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            actions.chooseDirectRecipient?.let { choose -> TextButton(onClick = {
                if (isCurrent()) { tools = false; choose() }
            }, enabled = !busy) { Text("Choose someone to message") } }
            TextButton(onClick = { if (isCurrent()) { tools = false; actions.refresh() } },
                enabled = !busy && circlesCanRead(state.screen, state.phase)) { Text("Refresh members") }
            if (circlesCanLoadMore(state.screen, state.phase, state.hasMore)) TextButton(onClick = {
                if (isCurrent()) { tools = false; actions.more() }
            }, enabled = !busy) { Text("Load more members") }
            actions.resumeReport?.let { resume -> TextButton(onClick = {
                if (isCurrent()) { tools = false; resume() }
            }, enabled = !actions.reportBusy) { Text("Resume report") } }
            actions.resumeMemberRemoval?.let { resume -> TextButton(onClick = {
                if (isCurrent()) { tools = false; resume() }
            }, enabled = !actions.removalBusy) { Text("Resume member removal") } }
            actions.resumeOwnershipTransfer?.let { resume -> TextButton(onClick = {
                if (isCurrent()) { tools = false; resume() }
            }, enabled = !busy) { Text("Resume ownership transfer") } }
            Text("Only loaded members are shown. Select an actual member before opening a report; nothing is sent automatically.", style = BlueprintType.Small)
        }
    }, confirmButton = { TextButton(onClick = { tools = false }) { Text("Close tools") } })
    return true
}
