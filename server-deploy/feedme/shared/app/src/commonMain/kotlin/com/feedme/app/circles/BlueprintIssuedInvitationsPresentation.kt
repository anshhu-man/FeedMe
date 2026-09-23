package com.feedme.app.circles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.feedme.app.blueprint.*
import com.feedme.contracts.WireField
import com.feedme.mealflow.circles.*

/** The original ticket is a presentation of actual local issue receipts, not a live inventory.
 * Pending originals and confirmation/recovery screens stay with their existing owner UI. */
@Composable
internal fun RetainedBlueprintIssuedInvitations(
    state: CircleIssuedInvitationsState, enabled: Boolean, maximumLifetimeHours: Int,
    shareAvailable: Boolean, shareConfigured: Boolean, shareNotice: String?,
    canStartNew: Boolean, isCurrent: () -> Boolean,
    onReviewIssue: (Int) -> Unit, onStartNew: () -> Unit,
    onShare: () -> Unit, onRevoke: (CircleIssuedInvitation) -> Unit, onBack: () -> Unit,
): Boolean {
    if (state.screen != CircleIssuedInvitationsScreen.HISTORY || state.pending != null || state.completionPending ||
        state.failureReason != null || state.phase !in setOf(CircleIssuedInvitationsPhase.IDLE,
            CircleIssuedInvitationsPhase.READY, CircleIssuedInvitationsPhase.ACKNOWLEDGED)) return false
    val context = remember(state) { BlueprintCommunityContext("retained-issued-invitations", "exact-render-observation") }
    var chosen by remember(state) { mutableStateOf(state.result) }
    var tools by remember(state) { mutableStateOf(false) }
    var lifetime by remember(state) { mutableStateOf<String?>(null) }
    var lifetimeError by remember(state) { mutableStateOf(false) }
    val issued = chosen?.takeIf { it === state.result || state.invitations.any { row -> row === it } }
    val circle = (issued?.circle ?: state.circle)?.blueprintSummary(context)
    val shareable = issued != null && issued === state.result &&
        state.completionAcknowledged && state.completionOperation == CircleIssuedInvitationOperation.ISSUE &&
        !issued.revocationAcknowledged && shareConfigured && shareAvailable
    val status = buildList {
        if (state.completionAcknowledged) add(when {
            state.result == null -> "The unsent original was discarded. No existing invitation was revoked."
            state.completionOperation == CircleIssuedInvitationOperation.REVOKE -> "Revocation confirmed on this device. No existing member was removed."
            else -> "Link creation confirmed. Sharing and joining are not confirmed."
        })
        if (state.capacityReached) add("Local invitation history is full. Existing originals are preserved; nothing is removed automatically.")
        shareNotice?.let(::add)
        if (!shareConfigured) add(CIRCLE_ISSUED_SHARE_UNAVAILABLE)
    }.joinToString("\n").ifEmpty { null }
    val model = BlueprintInviteState(context, circle,
        issued?.takeIf { circle != null }?.let { BlueprintIssuedInvitation(
            BlueprintCommunityRef(BlueprintCommunityKind.INVITATION, it.id, it.version), circle!!.binding,
            shareAvailable = shareable, status = "Last confirmed: ${circleStatusLabel(it.status)}. Expires ${circleInvitationExpiry(WireField.Value(it.expiresAt))}." +
                if (it.revocationAcknowledged) " Revocation confirmed on this device." else " Current use or changes elsewhere are not checked here.") },
        BlueprintCommunityControls(loading = !enabled, status = status, allowedActionIds = if (!enabled) emptySet() else buildSet {
            if (circle != null) add(BlueprintCircleAction.INVITE_BACK.id)
            if ((issued == null || issued === state.result) &&
                (state.canReviewIssue && !state.capacityReached || state.completionAcknowledged && canStartNew)) add(BlueprintCircleAction.CREATE_LINK.id)
            if (shareable) add(BlueprintCircleAction.SHARE_LINK.id)
            if (issued != null && !issued.revocationAcknowledged) add(BlueprintCircleAction.REVOKE_LINK.id)
        }, actionMessages = mapOf(BlueprintCircleAction.CREATE_LINK.id to
            "Creating and revoking each require their own review. Only the newly delivered link can be shared; retained history never recovers a link.")))
    BlueprintInviteScreen(model, onAction = { intent ->
        if (isCurrent() && intent.context === context) {
            val admitted = model.intent(intent.action)
            if (admitted != null && admitted.circle == intent.circle && admitted.invitation == intent.invitation) when (intent.action) {
                BlueprintCircleAction.CREATE_LINK -> if (state.canReviewIssue) { lifetime = ""; lifetimeError = false } else onStartNew()
                BlueprintCircleAction.SHARE_LINK -> onShare()
                BlueprintCircleAction.REVOKE_LINK -> issued?.let(onRevoke)
                BlueprintCircleAction.INVITE_BACK -> onBack()
                else -> Unit
            }
        }
    }, onBack = { if (isCurrent()) onBack() }, onMore = { if (isCurrent()) tools = true }, onNavigate = {})
    if (lifetime != null) AlertDialog(onDismissRequest = { lifetime = null }, title = { Text("Choose an invite lifetime") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.circle?.let { Text(it.name, style = BlueprintType.FieldLabel) }
            Text(CIRCLE_ISSUED_SINGLE_USE_NOTICE, style = BlueprintType.Small)
            OutlinedTextField(lifetime.orEmpty(), { if (enabled && isCurrent() && it.length <= 3) { lifetime = it; lifetimeError = false } },
                label = { Text("Expires in hours") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = enabled, singleLine = true, isError = lifetimeError,
                supportingText = { Text("Choose 1–$maximumLifetimeHours hours.") })
            Text("Next, review the invitation. No link has been created or sent.", style = BlueprintType.Small)
        }
    }, confirmButton = { TextButton(onClick = {
        if (enabled && isCurrent()) {
            val hours = circleIssuedLifetime(lifetime.orEmpty(), maximumLifetimeHours)
            if (hours == null) lifetimeError = true else { lifetime = null; onReviewIssue(hours) }
        }
    }, enabled = enabled) { Text("Review invite") } }, dismissButton = { TextButton(onClick = { lifetime = null }) { Text("Cancel") } })
    if (tools) AlertDialog(onDismissRequest = { tools = false }, title = { Text(CIRCLE_ISSUED_HISTORY_TITLE) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(CIRCLE_ISSUED_HISTORY_NOTICE, style = BlueprintType.Small)
            if (state.circle != null || state.result != null) TextButton(onClick = {
                if (enabled && isCurrent()) { chosen = state.result; tools = false }
            }, enabled = enabled) { Text("Return to current invitation") }
            if (state.invitations.isEmpty()) Text("No invitations recorded on this device.")
            state.invitations.forEach { row ->
                Text(row.circle.name, style = BlueprintType.FieldLabel)
                Text("Last confirmed: ${circleStatusLabel(row.status)}. Expires ${circleInvitationExpiry(WireField.Value(row.expiresAt))}.", style = BlueprintType.Small)
                if (row.revocationAcknowledged) Text("Revocation confirmed on this device.", style = BlueprintType.Small)
                TextButton(onClick = { if (enabled && isCurrent()) { chosen = row; tools = false } }, enabled = enabled) { Text("Select invitation details") }
            }
            Text("Selecting history never recovers or shares a link. Current use or revocation elsewhere is not checked here.", style = BlueprintType.Small)
        }
    }, confirmButton = { TextButton(onClick = { tools = false }) { Text("Close history") } })
    return true
}
