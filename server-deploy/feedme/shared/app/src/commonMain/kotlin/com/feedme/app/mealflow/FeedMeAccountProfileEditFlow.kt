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
import com.feedme.mealflow.profile.*
import kotlinx.coroutines.launch

/** Original PROFILE_SETUP layout applied to the actual Settings account. No setup advancement,
 * guest merge, fabricated avatar, implicit load/send, or acknowledgement from optimistic input. */
@Composable
fun FeedMeAccountProfileEditFlow(controller: AccountProfileEditController, hostIsCurrent: () -> Boolean,
    onClose: () -> Unit, platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
    returnLabel: String = "Settings") {
    val observed by controller.states.collectAsState()
    val state = observed
    val host by rememberUpdatedState(hostIsCurrent)
    val close by rememberUpdatedState(onClose)
    val scope = rememberCoroutineScope()
    var attached by remember(controller) { mutableStateOf(true) }
    var busy by remember(controller) { mutableStateOf(false) }
    var more by remember(controller) { mutableStateOf(false) }
    var leaveReview by remember(controller) { mutableStateOf(false) }
    var reloadReview by remember(controller) { mutableStateOf(false) }
    DisposableEffect(controller) { onDispose { attached = false } }
    fun current() = attached && host() && controller.isCurrent(state)
    val working = state.phase == AccountProfileEditPhase.SAVING
    val loading = busy || state.phase == AccountProfileEditPhase.LOADING
    val edit = current() && !loading && !working && state.pending == null && state.review == null && state.profile != null && state.draft != null
    fun act(block: suspend () -> Unit) {
        if (!current() || busy || working) return
        busy = true
        scope.launch { try { if (current()) block() } finally { busy = false } }
    }
    fun back() {
        if (!attached) return
        // Native close callback independently matches the exact route/attachment. Cleanup Back
        // remains usable when provider/account authority is gone; no write is authorized here.
        if (!current()) { close(); return }
        if (working || busy) return
        when {
            leaveReview -> leaveReview = false
            reloadReview -> reloadReview = false
            more -> more = false
            state.review != null -> act { controller.backReview(state) }
            state.dirty && state.pending == null -> leaveReview = true
            else -> close()
        }
    }
    platformBackHandler(attached, ::back)
    SideEffect { if (!current()) { more = false; leaveReview = false; reloadReview = false } }

    val review = state.review.takeIf { current() }
    if (review != null) {
        val resolving = review.resolveRejection
        val model = BlueprintConfirmationState(
            actionLabel = if (resolving) "Return the rejected edit to a draft" else "Save profile changes",
            affectedSummary = "Display name: ${review.draft.displayName}\nHandle: @${review.draft.handle}",
            consequences = if (resolving) listOf(
                "The retained server response confirms this original did not apply (${review.rejectionCode}).",
                "Preserve its exact request and rejection, then return these fields to a draft based on the newly read profile.",
                "No replacement save is sent. Edit and review again before any new command.",
            ) else listOf(
                "Only your display name and handle change. Bio, avatar, age/Terms eligibility and onboarding remain unchanged.",
                if (review.retryOriginal) "Retry the exact retained command key, body and version; the previous outcome is not assumed to have failed."
                else "This sends the reviewed fields against profile version ${review.version}. A changed profile or unavailable handle may reject it.",
                "A save is confirmed only after its actual server receipt is validated and acknowledged on this device.",
            ), canConfirm = current() && !loading && !working, canCancel = current() && !loading && !working,
            busy = loading || working,
        )
        BlueprintConfirmationScreen(model, heading = if (resolving) "Review this\nrejected save." else "Your profile.\nYour changes.",
            confirmLabel = if (resolving) "Return to editing" else if (review.retryOriginal) "Retry original save" else "Save profile",
            onConfirm = { if (it === model && it.confirmEnabled) act { controller.confirm(review) } },
            onCancel = { if (it === model && it.cancelEnabled) act { controller.backReview(state) } })
        return
    }

    val draft = state.draft.takeIf { current() }
    val model = BlueprintAccountSetupState(BlueprintAccountSetupPage.PROFILE_SETUP,
        fields = BlueprintAccountSetupFields(displayName = draft?.displayName.orEmpty(), handle = draft?.handle.orEmpty()),
        enabledActions = buildSet {
            if (attached && (!current() || !working && !busy)) { add(BlueprintAccountSetupAction.BACK); add(BlueprintAccountSetupAction.SET_PREFERENCES_LATER) }
            if (current() && !loading && !working) add(BlueprintAccountSetupAction.MORE)
            if (edit && state.dirty) add(BlueprintAccountSetupAction.CONTINUE_PROFILE)
        }, editableFields = if (edit) setOf(BlueprintAccountSetupField.DISPLAY_NAME, BlueprintAccountSetupField.HANDLE) else emptySet(),
        busy = loading || working, notice = if (!current()) "Profile editing is unavailable. You can return to $returnLabel." else profileNotice(state, returnLabel),
        profileEditing = true,
    )
    BlueprintAccountSetupScreen(model,
        onFieldsChange = { next -> if (edit) act { controller.edit(AccountProfileEditDraft(next.displayName, next.handle), state) } },
        onAction = { action -> if (action in model.enabledActions) when (action) {
            BlueprintAccountSetupAction.BACK, BlueprintAccountSetupAction.SET_PREFERENCES_LATER -> back()
            BlueprintAccountSetupAction.MORE -> if (current()) more = true
            BlueprintAccountSetupAction.CONTINUE_PROFILE -> act { controller.prepareSave(state) }
            else -> Unit
        } })

    if (more && current()) FeedMeTheme {
        AlertDialog(onDismissRequest = { more = false }, title = { Text("Profile changes") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(profileNotice(state, returnLabel))
                Text("Use a display name up to 50 characters and a handle with 3–24 lowercase letters, numbers or underscores. Fields are never silently trimmed or renamed.")
                state.pending?.let { pending ->
                    Text("Original profile version: ${pending.version}. ${pending.phase?.name ?: "Retained original"} · ${pending.issue?.name ?: "Unconfirmed"}.")
                    if (pending.canRetry) TextButton(enabled = !loading && !working, onClick = { more = false; act { controller.prepareRetry(state) } }) { Text("Review original retry") }
                    if (pending.receiptReady) TextButton(enabled = !loading && !working, onClick = { more = false; act { controller.applyReceipt(state) } }) { Text("Confirm retained receipt") }
                    if (pending.canResolve) TextButton(enabled = !loading && !working, onClick = { more = false; act { controller.prepareResolve(state) } }) { Text("Review proven rejection") }
                    if (pending.attempts == 0) TextButton(enabled = !loading && !working, onClick = { more = false; act { controller.discardUnsent(state) } }) { Text("Cancel never-sent original") }
                    if (!pending.canRetry && !pending.receiptReady && !pending.canResolve)
                        Text("This original still needs resolution. An unknown response is not proof of failure and will not be replaced with a new key.")
                }
                if (state.pending == null) TextButton(enabled = !loading && !working, onClick = {
                    if (state.dirty) reloadReview = true else { more = false; act { controller.refresh(state) } }
                }) { Text(if (state.dirty) "Discard draft and reload…" else "Reload current profile") }
            }
        }, confirmButton = { TextButton(onClick = { more = false }) { Text("Done") } })
    }
    if (leaveReview && current()) FeedMeTheme {
        AlertDialog(onDismissRequest = { leaveReview = false }, title = { Text("Leave without saving?") },
            text = { Text("Only this unsubmitted draft will be discarded. No profile request is sent. Any previously confirmed command remains protected in account-scoped storage.") },
            confirmButton = { TextButton(enabled = !loading && !working, onClick = {
                leaveReview = false
                act {
                    val result = controller.discardDraft(state)
                    if (result is PortResult.Value && attached && host() && controller.isCurrent(result.value)) close()
                }
            }) { Text("Discard draft and leave") } },
            dismissButton = { TextButton(onClick = { leaveReview = false }) { Text("Keep editing") } })
    }
    if (reloadReview && current()) FeedMeTheme {
        AlertDialog(onDismissRequest = { reloadReview = false }, title = { Text("Replace the unsaved draft?") },
            text = { Text("Read the current profile and replace only the unsubmitted form. Confirmed or attempted originals cannot be discarded this way.") },
            confirmButton = { TextButton(enabled = !loading && !working, onClick = {
                reloadReview = false; more = false; act { controller.discardDraftAndReload(state) }
            }) { Text("Discard and reload") } }, dismissButton = { TextButton(onClick = { reloadReview = false }) { Text("Keep draft") } })
    }
}

private fun profileNotice(state: AccountProfileEditState, returnLabel: String): String = when {
    state.pending != null -> "A profile save is retained with its exact key, fields and version. Open More to review its real recovery state."
    state.receipt != null -> "Profile save confirmed by the original server receipt, version ${state.receipt!!.version}. Future profile reads still check current authority."
    state.phase == AccountProfileEditPhase.SAVING -> "Saving the reviewed original. No success is assumed while the response is pending."
    state.phase == AccountProfileEditPhase.LOADING -> "Loading your actual account profile or retained work."
    state.failureReason != null -> "Could not save or refresh (${state.failureReason}). The current draft or original is retained; no acknowledgement is assumed."
    state.phase in setOf(AccountProfileEditPhase.HIDDEN, AccountProfileEditPhase.UNAVAILABLE) -> "Profile editing is unavailable. You can return to $returnLabel."
    state.dirty -> "Unsaved changes. Review before saving; Back asks before discarding this draft."
    else -> "Edit your existing display name and handle. This does not restart onboarding or change your bio, avatar, Terms or eligibility."
}
