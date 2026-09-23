package com.feedme.app.circles

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import com.feedme.app.blueprint.*

/** Original PEOPLE_PICKER presentation, restricted to one F28 conversation recipient.
 * Use returns a typed selection for a separate review. It never sends a command. */
@Composable
fun FeedMeCircleDirectPeoplePicker(owner: CircleDirectPeoplePicker,
    onSelected: (CircleDirectRecipientSelection) -> Unit,
    onCancel: () -> Unit,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
) {
    // Observe invalidation of the borrowed roster, not just local check-box changes.
    val observation by owner.observations.collectAsState()
    val context = remember(owner) { BlueprintCommunityContext("circle-direct-recipient", "exact-roster-observation") }
    val picker = remember(owner) { BlueprintCommunityPicker("F28:direct-thread", "single-person-local-selection") }
    var attached by remember(owner) { mutableStateOf(true) }
    DisposableEffect(owner) { onDispose { attached = false } }
    var tools by remember(owner) { mutableStateOf(false) }
    val current = attached && observation === owner.source && owner.isCurrent
    val circle = owner.source.selected?.takeIf { current }?.blueprintSummary(context)?.binding
    val rows = if (circle != null) owner.people.map { member ->
        member to BlueprintEligiblePerson(
            BlueprintPeopleSelection(circle, BlueprintMemberBinding(
                BlueprintCommunityRef(BlueprintCommunityKind.USER, member.userId,
                    "observed-membership:${member.id}:${member.version}"), member.version, context.revision)),
            BlueprintSocialPerson(member.userId, member.displayName),
            "${circleRoleLabel(member.role)} · Contact permission checked when you continue")
    } else emptyList()
    val selected = rows.singleOrNull { it.first === owner.selectedMember }?.second?.selection
    val status = when {
        !current -> "This member view changed. Go back and refresh before choosing someone."
        owner.awaitingHandoff -> "Opening the conversation review. Nothing has been sent."
        rows.isEmpty() -> "No other active members are loaded. Go back to refresh or load more members."
        else -> "Choose one person from the loaded members. You’ll review before starting a conversation; contact settings may prevent it."
    }
    val model = BlueprintPeoplePickerState(context, picker,
        owner.source.selected?.name?.takeIf { current }, rows.map { it.second }, listOfNotNull(selected),
        BlueprintCommunityControls(loading = owner.awaitingHandoff, unavailable = !current,
            allowedActionIds = buildSet {
                add(BlueprintConversationAction.CANCEL_PEOPLE.id)
                if (current && !owner.awaitingHandoff && selected != null) add(BlueprintConversationAction.USE_PEOPLE.id)
            }, editableFields = if (current && !owner.awaitingHandoff) setOf("people") else emptySet(), status = status))
    fun cancel() { if (attached) { owner.close(); onCancel() } }
    platformBackHandler(attached) { cancel() }
    BlueprintPeoplePickerScreen(model,
        onSelect = { suppliedContext, suppliedPicker, selection ->
            if (attached && owner.isCurrent && suppliedContext === context && suppliedPicker === picker && model.controls.edit("people"))
                rows.singleOrNull { it.second.selection == selection }?.first?.let(owner::select)
        }, onAction = { intent ->
            if (attached && intent.context === context && intent.picker === picker) when (intent.action) {
                BlueprintConversationAction.CANCEL_PEOPLE -> cancel()
                BlueprintConversationAction.USE_PEOPLE -> {
                    val admitted = model.intent(intent.action)
                    if (owner.isCurrent && admitted?.people == intent.people && intent.people.size == 1)
                        owner.confirm()?.takeIf { it.isCurrentForNavigation }?.let(onSelected)
                }
                else -> Unit
            }
        }, onBack = { cancel() }, onMore = { if (attached) tools = true }, onNavigate = {})
    if (tools) AlertDialog(onDismissRequest = { tools = false }, title = { Text("Choose one person") },
        text = { Text("This picker uses only the loaded circle roster. It does not import contacts, send invitations or send a message. Return to members to refresh or load more people.") },
        confirmButton = { TextButton(onClick = { tools = false }) { Text("Close") } })
}
