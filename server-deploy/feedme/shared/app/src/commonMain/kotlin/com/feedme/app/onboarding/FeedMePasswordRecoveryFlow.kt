package com.feedme.app.onboarding

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalFocusManager
import com.feedme.app.blueprint.*
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.SecretText
import com.feedme.session.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Original AUTH_RESET/AUTH_RESET_CONFIRM, with the existing CONFIRM_ACTION for an exact
 * password-change review. This adapter neither stores tokens nor performs mount/restore I/O.
 * Text is attachment-local (not rememberSaveable); the native host owns the controller lifetime. */
@Composable
fun FeedMePasswordRecoveryFlow(
    controller: AccountPasswordRecoveryController,
    hostIsCurrent: () -> Boolean,
    onBackToLogin: () -> Unit,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
) {
    val observed by controller.states.collectAsState()
    val state = observed
    val scope = rememberCoroutineScope()
    val hostCurrent by rememberUpdatedState(hostIsCurrent)
    val backToLogin by rememberUpdatedState(onBackToLogin)
    val focus = LocalFocusManager.current
    var attached by remember(controller) { mutableStateOf(true) }
    var claimed by remember(controller) { mutableStateOf(false) }
    var fields by remember(controller, state.phase) { mutableStateOf(BlueprintAccountSetupFields()) }
    var errors by remember(controller, state.phase) { mutableStateOf<Map<BlueprintAccountSetupField, String>>(emptyMap()) }
    var pulse by remember(controller, state) { mutableStateOf(0) }
    DisposableEffect(controller) { onDispose { attached = false; fields = BlueprintAccountSetupFields(); errors = emptyMap() } }
    // Presentation-only cooldown clock. No provider request, verification or resend is automatic.
    LaunchedEffect(controller, state) {
        while (state.phase in setOf(PasswordRecoveryPhase.CONFIRM_CODE, PasswordRecoveryPhase.OUTCOME_UNKNOWN) &&
            hostCurrent() && controller.isCurrent(state) && !controller.canResend(state)) {
            delay(500); pulse++
        }
    }
    val current = attached && hostCurrent() && controller.isCurrent(state)
    val enabled = current && !state.busy && !claimed
    val mayResend = pulse.let { enabled && controller.canResend(state) }
    fun currentAction(): Boolean = attached && hostCurrent() && controller.isCurrent(state) && !claimed
    fun act(block: suspend () -> Unit) {
        if (!currentAction() || state.busy) return
        claimed = true; focus.clearFocus()
        scope.launch {
            try { if (attached && hostCurrent() && controller.isCurrent(state)) block() }
            finally { claimed = false }
        }
    }
    fun leave() {
        // Navigation is cleanup, not provider authority. Native callback rechecks its exact
        // EntryState/attachment even after this flow's credential/config authority is lost.
        if (!attached) return
        fields = BlueprintAccountSetupFields(); errors = emptyMap(); focus.clearFocus()
        backToLogin()
    }
    fun back() {
        if (!attached) return
        when {
            state.phase == PasswordRecoveryPhase.REVIEW && currentAction() -> act { controller.cancelReview(state) }
            state.phase == PasswordRecoveryPhase.CONFIRM_CODE && currentAction() -> act { controller.startOver(state) }
            else -> leave()
        }
    }
    platformBackHandler(attached, ::back)
    SideEffect { if (!current) { fields = BlueprintAccountSetupFields(); errors = emptyMap() } }

    if (state.phase == PasswordRecoveryPhase.REVIEW && state.review != null && current) {
        val original = checkNotNull(state.review)
        val review = BlueprintConfirmationState(
            actionLabel = "Set my new password",
            affectedSummary = "Change the password for the email entered in this recovery flow.",
            consequences = listOf(
                "Your recovery code and new password are sent securely to the configured Supabase authentication service.",
                "FeedMe does not sign you in or save these recovery credentials. Sign in again after a confirmed change.",
                "Other-session revocation follows the provider’s configuration; this flow does not promise a global sign-out.",
            ),
            canConfirm = enabled, canCancel = enabled,
            status = failureCopy(state.failureReason),
        )
        BlueprintConfirmationScreen(review, heading = "Set your\nnew password?", confirmLabel = "Set my new password",
            onConfirm = { exact -> if (exact === review && currentAction()) act { controller.confirm(original) } },
            onCancel = { exact -> if (exact === review && currentAction()) act { controller.cancelReview(state) } })
        return
    }

    val requestPage = state.phase in setOf(PasswordRecoveryPhase.REQUEST_CODE, PasswordRecoveryPhase.SENDING)
    val page = if (requestPage) BlueprintAccountSetupPage.AUTH_RESET else BlueprintAccountSetupPage.AUTH_RESET_CONFIRM
    val actions = buildSet {
        if (attached) add(BlueprintAccountSetupAction.BACK)
        if (requestPage && attached) add(BlueprintAccountSetupAction.BACK_TO_LOGIN)
        if (enabled && state.phase == PasswordRecoveryPhase.REQUEST_CODE) add(BlueprintAccountSetupAction.SEND_RESET_CODE)
        if (enabled && state.phase == PasswordRecoveryPhase.CONFIRM_CODE) add(BlueprintAccountSetupAction.SAVE_NEW_PASSWORD)
        if (mayResend) add(BlueprintAccountSetupAction.RESEND_RESET_CODE)
    }
    val editable = when {
        !enabled -> emptySet()
        state.phase == PasswordRecoveryPhase.REQUEST_CODE -> setOf(BlueprintAccountSetupField.EMAIL)
        state.phase == PasswordRecoveryPhase.CONFIRM_CODE -> buildSet {
            if (!state.codeVerified) add(BlueprintAccountSetupField.RESET_CODE)
            add(BlueprintAccountSetupField.NEW_PASSWORD); add(BlueprintAccountSetupField.CONFIRMATION)
        }
        else -> emptySet()
    }
    val notice = when {
        !current -> "This recovery flow is no longer available. Go back to sign in."
        state.phase == PasswordRecoveryPhase.SENDING -> "Requesting recovery instructions. This does not confirm an account exists or an email was delivered."
        state.phase == PasswordRecoveryPhase.SAVING -> "Checking the recovery code and submitting your reviewed password change. Leaving cannot undo a request already sent."
        state.phase == PasswordRecoveryPhase.COMPLETE -> "Supabase confirmed the password update. Go back and sign in again; FeedMe has not signed you in or saved recovery credentials."
        state.phase == PasswordRecoveryPhase.OUTCOME_UNKNOWN -> "The password-change outcome could not be confirmed. It may have succeeded. Go back and try signing in, or explicitly request a new recovery code after the cooldown. The previous change will not be resent."
        state.failureReason != null -> failureCopy(state.failureReason)
        state.codeVerified -> "Your recovery code was verified for this short-lived flow. Enter and review a replacement password."
        state.requestAccepted -> "Recovery request accepted. If this email can receive recovery instructions, use the six-digit code when it arrives. Acceptance does not confirm delivery or account existence."
        else -> "Google sign-in remains available. This recovery flow is for an email password and requires a recovery email containing a six-digit code."
    }
    val rendered = BlueprintAccountSetupState(page, fields, actions, editable, errors, state.busy, notice,
        recoveryPreservesAccountPrivacy = true,
        passwordGuidance = "The authentication provider enforces its current password rules. Your code and password remain only in this flow’s memory; closing or restarting does not preserve them.")
    BlueprintAccountSetupScreen(rendered,
        onFieldsChange = { next ->
            if (currentAction() && !state.busy) {
                // Reject overlong edits, never silently truncate/normalize credentials.
                if (next.email.length <= 320 && next.resetCode.length <= 6 &&
                    next.newPassword.length <= 4096 && next.confirmation.length <= 4096) {
                    fields = next; errors = emptyMap()
                }
            }
        },
        onAction = { action ->
            if (action in actions) when (action) {
                BlueprintAccountSetupAction.BACK -> back()
                BlueprintAccountSetupAction.BACK_TO_LOGIN -> leave()
                BlueprintAccountSetupAction.SEND_RESET_CODE -> {
                    val email = fields.email
                    if (email.encodeToByteArray().size !in 3..320 || '@' !in email || email.any(Char::isISOControl))
                        errors = mapOf(BlueprintAccountSetupField.EMAIL to "Enter a valid email address.")
                    else act { controller.sendCode(SecretText(email), state) }
                }
                BlueprintAccountSetupAction.SAVE_NEW_PASSWORD -> {
                    val captured = fields
                    val invalid = buildMap {
                        if (!state.codeVerified && !captured.resetCode.matches(Regex("[0-9]{6}")))
                            put(BlueprintAccountSetupField.RESET_CODE, "Enter the six-digit recovery code.")
                        if (captured.newPassword.encodeToByteArray().size !in 1..4096)
                            put(BlueprintAccountSetupField.NEW_PASSWORD, "Enter a password within the supported length.")
                        if (captured.newPassword != captured.confirmation)
                            put(BlueprintAccountSetupField.CONFIRMATION, "The passwords must match.")
                    }
                    errors = invalid
                    if (invalid.isEmpty()) {
                        fields = BlueprintAccountSetupFields()
                        act { controller.preparePassword(if (state.codeVerified) null else SecretText(captured.resetCode),
                            SecretText(captured.newPassword), SecretText(captured.confirmation), state) }
                    }
                }
                BlueprintAccountSetupAction.RESEND_RESET_CODE -> {
                    fields = BlueprintAccountSetupFields(); act { controller.resend(state) }
                }
                else -> Unit
            }
        })
}

private fun failureCopy(reason: FailureReason?): String? = when (reason) {
    null -> null
    FailureReason.OFFLINE -> "Connect to the internet and choose the action again. No automatic retry will occur."
    FailureReason.RATE_LIMITED -> "Wait before requesting another code or trying again. The provider may require a longer cooldown."
    FailureReason.INVALID_DATA -> "The code, password or confirmation was not accepted. Check the fields; provider password rules still apply."
    FailureReason.UNAUTHENTICATED -> "This review or recovery session has expired. Enter the fields again or explicitly request another code."
    FailureReason.OUTCOME_UNKNOWN -> "The request outcome is unknown. If recovery instructions arrive, use their code; this screen does not confirm email delivery."
    else -> "Recovery could not be confirmed. No account-existence or email-delivery information is available."
}
