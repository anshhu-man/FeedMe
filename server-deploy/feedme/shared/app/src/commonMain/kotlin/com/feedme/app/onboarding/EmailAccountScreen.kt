package com.feedme.app.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.feedme.app.*
import com.feedme.app.blueprint.BlueprintAppBar
import com.feedme.app.blueprint.BlueprintConfirmationScreen
import com.feedme.app.blueprint.BlueprintConfirmationState
import com.feedme.core.FeedMeAdultPolicy
import com.feedme.core.ports.EpochClock
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.SecretText
import com.feedme.session.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Configured email flow, separate from the demo. The native root owns the coordinator lifetime
 * and must close it synchronously on account/configuration retirement. Mount/recreation sends
 * nothing. One-use profile/private continuation follows an explicit button, not a StateFlow side effect.
 * The root must keep this coordinator alive while its restricted profile child is displayed.
 */
@Composable
fun FeedMeEmailAccountFlow(
    coordinator: EmailAccountCoordinator,
    policy: EmailAccountFormPolicy,
    clock: EpochClock,
    onBack: () -> Unit,
    onTerms: () -> Unit,
    onPrivacy: () -> Unit,
    onProfile: (EmailAccountProfileHandoff) -> Unit,
    onReady: (EmailAccountState) -> Unit,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
    hostIsCurrent: () -> Boolean = { true },
    onAccountTermsUrl: (String) -> Unit = {},
    googleCredentialRequest: GoogleAccountCredentialRequest? = null,
    reviewGoogleOnEntry: Boolean = false,
    googleOAuthCredentialRequest: GoogleOAuthCredentialRequest? = null,
    onAccountSettings: (() -> Unit)? = null,
    onPasswordRecovery: (() -> Unit)? = null,
) {
    val observedState by coordinator.states.collectAsState()
    val state = observedState // Exact rendering; queued callbacks must not read a newer state.
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val actions = remember(coordinator) { EmailUiActionGate() }
    val hostCurrent by rememberUpdatedState(hostIsCurrent)
    val latestAccountSettings by rememberUpdatedState(onAccountSettings)
    val latestPasswordRecovery by rememberUpdatedState(onPasswordRecovery)
    val latestGoogleNative by rememberUpdatedState(googleCredentialRequest)
    val latestGoogleOAuth by rememberUpdatedState(googleOAuthCredentialRequest)
    val googleSelection = remember(googleCredentialRequest, googleOAuthCredentialRequest) {
        EmailGoogleSelection.select(googleCredentialRequest, googleOAuthCredentialRequest)
    }
    fun googleSelectionCurrent() = googleSelection?.matches(latestGoogleNative, latestGoogleOAuth) == true
    var attached by remember(coordinator) { mutableStateOf(true) }
    var claimed by remember(coordinator) { mutableStateOf(false) }
    var leaving by remember(coordinator) { mutableStateOf(false) }
    var handedOff by remember(coordinator) { mutableStateOf(false) }
    var closeFailure by remember(coordinator) { mutableStateOf(false) }
    var input by remember(coordinator, state.screen) { mutableStateOf(EmailFormInput()) }
    var validate by remember(coordinator, state.screen) { mutableStateOf(false) }
    var showAccountTools by remember(coordinator, state.screen) { mutableStateOf(false) }
    var accountSettingsAction by remember(coordinator, state) { mutableStateOf<AccountSettingsHostAction?>(null) }
    val renderedAccountSettingsAction = accountSettingsAction
    var unavailableNotice by remember(coordinator, state.screen) { mutableStateOf<String?>(null) }
    // Welcome may open a review, never the provider picker or an account request.
    // This is attachment-only intent: recreation never restores affirmative consent.
    var googleReview by remember(coordinator, googleSelection) {
        mutableStateOf<Any?>(if (reviewGoogleOnEntry && state.screen == EmailAccountScreen.LOGIN && googleSelection != null) Any() else null)
    }
    // Attachment-only identity of an actual explicit OAuth coordinator call. This marker
    // contains no code, verifier, email or native callback/identity assertion.
    var googleCallback by remember(coordinator, googleSelection) { mutableStateOf<Any?>(null) }
    var now by remember(coordinator, clock) { mutableStateOf(runCatching { clock.nowMillis() }.getOrDefault(-1)) }
    val current = hostCurrent() && coordinator.isCurrentState(state) && state.screen != EmailAccountScreen.CLOSED
    val enabled = attached && current && !state.busy && !claimed && !leaving && !handedOff
    val errors = emailFormErrors(state.screen, input)
    val resendSeconds = emailResendSeconds(now, state.resendAtMillis)

    DisposableEffect(coordinator) { onDispose { attached = false; actions.retire() } }
    SideEffect {
        if (!current || leaving || handedOff) { input = EmailFormInput(); unavailableNotice = null; googleReview = null }
        // Busy/progress emits a new coordinator state. An older transient render must not
        // erase the still-running call marker before collectAsState receives that emission.
        if (!hostCurrent() || leaving || handedOff || state.screen == EmailAccountScreen.CLOSED || !googleSelectionCurrent()) googleCallback = null
    }
    LaunchedEffect(coordinator, clock, state.resendAtMillis) {
        now = runCatching { clock.nowMillis() }.getOrDefault(-1)
        while (hostCurrent() && state.screen == EmailAccountScreen.VERIFY && emailResendSeconds(now, state.resendAtMillis)?.let { it > 0 } == true) {
            delay(500)
            now = runCatching { clock.nowMillis() }.getOrDefault(-1)
        }
    }
    fun canAct() = hostCurrent() && attached && !claimed && !actions.busy && !leaving && !handedOff &&
        !state.busy && coordinator.isCurrentState(state) && state.screen != EmailAccountScreen.CLOSED
    fun act(action: suspend () -> Unit) {
        if (!canAct()) return
        val ticket = actions.claim() ?: return
        claimed = true
        focus.clearFocus()
        scope.launch {
            try {
                if (hostCurrent() && actions.owns(ticket) && attached && !leaving && !handedOff && coordinator.isCurrentState(state)) action()
            } finally { if (actions.release(ticket) && attached) claimed = false }
        }
    }
    fun submit() {
        if (!canAct()) return
        validate = true
        val captured = input
        if (!emailFormErrors(state.screen, captured).valid) return
        val expected = state
        // Drop editable password/code references at dispatch. Failed requests never refill secrets.
        input = input.withoutSecrets(); validate = false
        act {
            when (expected.screen) {
                EmailAccountScreen.SIGNUP -> coordinator.signUp(SecretText(captured.email), SecretText(captured.password),
                    SecretText(captured.confirmation), captured.termsAccepted, captured.ageConfirmed, expected)
                EmailAccountScreen.LOGIN -> coordinator.login(SecretText(captured.email), SecretText(captured.password), expected)
                EmailAccountScreen.VERIFY -> coordinator.verifyCode(SecretText(captured.code), expected)
                else -> Unit
            }
        }
    }
    fun leave() {
        if (!hostCurrent() || !attached || leaving || handedOff) return
        leaving = true; closeFailure = false; actions.retire(); input = EmailFormInput()
        focus.clearFocus()
        scope.launch {
            if (!hostCurrent() || !attached || !leaving || handedOff) return@launch
            // CLOSED is synchronous redaction, not an acknowledged child cleanup. Explicit
            // Back may retry cleanup after failure without restarting any provider work.
            val result = coordinator.close()
            if (hostCurrent() && attached && leaving && !handedOff) {
                if (result is PortResult.Value) { handedOff = true; onBack() }
                else { closeFailure = true; leaving = false }
            }
        }
    }
    fun edit(next: EmailFormInput) { if (canAct()) input = next }
    platformBackHandler(hostCurrent() && attached && !handedOff) {
        if (googleReview != null && canAct()) googleReview = null else leave()
    }

    val reviewToken = googleReview
    if (reviewToken != null && current && googleSelection != null &&
        state.screen in setOf(EmailAccountScreen.LOGIN, EmailAccountScreen.SIGNUP)) {
        val selection = googleSelection
        val review = BlueprintConfirmationState(
            actionLabel = "Continue with Google",
            affectedSummary = FeedMeAdultPolicy.AGE_DECLARATION,
            consequences = listOf("By continuing, I accept FeedMe’s Terms and acknowledge its Privacy Policy.",
                "Google sign-in is handled through Supabase. Choosing Cancel sends no sign-in request."),
            canConfirm = enabled, canCancel = enabled,
        )
        FeedMeTheme {
            BlueprintConfirmationScreen(review,
                heading = "Before\nyou join.", confirmLabel = "Agree and continue with Google",
                reviewLinks = {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { if (googleReview === reviewToken && googleSelectionCurrent() && canAct()) onTerms() }, enabled = enabled) { Text("Terms") }
                        TextButton(onClick = { if (googleReview === reviewToken && googleSelectionCurrent() && canAct()) onPrivacy() }, enabled = enabled) { Text("Privacy") }
                    }
                },
                onConfirm = { selected ->
                    if (selected === review && googleReview === reviewToken && googleSelectionCurrent() && canAct()) {
                        googleReview = null; input = EmailFormInput(); validate = false
                        act {
                            if (googleSelectionCurrent()) {
                                val callback = if (googleOAuthCredentialRequest != null) Any() else null
                                googleCallback = callback
                                try { selection.confirm(coordinator, state) }
                                finally { if (callback != null && googleCallback === callback) googleCallback = null }
                            }
                        }
                    }
                },
                onCancel = { selected -> if (selected === review && googleReview === reviewToken && googleSelectionCurrent() && canAct()) googleReview = null },
            )
        }
        return
    }

    val callbackToken = googleCallback
    if (callbackToken != null && current && attached && !leaving && !handedOff && googleSelectionCurrent()) {
        BlueprintGoogleOAuthCallback(canCancel = true,
            current = { googleCallback === callbackToken && hostCurrent() && attached && !leaving && !handedOff &&
                googleSelectionCurrent() && coordinator.isCurrentState(state) },
            onCancel = ::leave)
        return
    }

    FeedMeTheme {
        Box(Modifier.fillMaxSize().background(FeedMeColors.Paper).safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            val blueprintPage = when (state.screen) {
                EmailAccountScreen.SIGNUP -> BlueprintAuthPage.SIGNUP
                EmailAccountScreen.LOGIN -> BlueprintAuthPage.LOGIN
                EmailAccountScreen.VERIFY -> BlueprintAuthPage.VERIFY
                else -> null
            }
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                BlueprintAppBar(title = "Your FeedMe", onBack = ::leave,
                    onMore = { if (canAct()) {
                        showAccountTools = !showAccountTools
                        accountSettingsAction = if (showAccountTools) latestAccountSettings?.let { AccountSettingsHostAction(state, it) } else null
                    } })
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(if (blueprintPage == null) 16.dp else 0.dp)) {
                val title = when (state.screen) {
                    EmailAccountScreen.SIGNUP -> "Your place at the table."
                    EmailAccountScreen.LOGIN -> "Hey, welcome back."
                    EmailAccountScreen.VERIFY -> "Check your inbox."
                    EmailAccountScreen.BOOTSTRAP_RECOVERY -> "Let’s finish connecting."
                    EmailAccountScreen.PROFILE -> "Make it yours."
                    EmailAccountScreen.PRIVATE_READY -> "You’re connected."
                    EmailAccountScreen.READY_RECOVERY -> "Your saved account needs a check."
                    EmailAccountScreen.TERMS_REQUIRED, EmailAccountScreen.TERMS_REVIEW -> "Review your account Terms."
                    EmailAccountScreen.LOCAL_SIGN_OUT_REVIEW -> "Sign out on this device?"
                    EmailAccountScreen.LOCAL_SIGN_OUT_RECOVERY -> "Finish signing out."
                    EmailAccountScreen.LOCAL_SIGNED_OUT -> "Signed out on this device."
                    EmailAccountScreen.PREFERENCES, EmailAccountScreen.PREFERENCES_RECOVERY -> "Continue your setup."
                    EmailAccountScreen.CLOSED -> "Account connection closed"
                }
                if (blueprintPage == null) Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                if (closeFailure) AccountNotice("Account details are hidden, but closing saved setup was not confirmed. Choose Back to retry cleanup. No account request will be sent.")
                if (!current) {
                    AccountNotice("Go back to reconnect. Account details are hidden; no request is sent automatically.")
                } else {
                    if (state.busy || claimed) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Connecting securely…") }
                    if (state.signupExpired) AccountNotice("This saved signup has expired. Choose Change email to clear it and start again. Nothing is verified or resent automatically.")
                    else if (state.signupRecoveryRequired && state.failureReason == com.feedme.core.ports.FailureReason.OUTCOME_UNKNOWN)
                        AccountNotice("Saving signup on this device was not confirmed. Resume signup checks only the saved local continuation.")
                    else if (state.screen == EmailAccountScreen.LOCAL_SIGN_OUT_RECOVERY && state.failureReason != null)
                        AccountNotice("Local sign-out was not fully confirmed. Finish sign-out retries only the saved cleanup; it does not send an account or Terms request.")
                    else emailFailureText(state.failureReason)?.let { AccountNotice(it) }
                    if (state.providerOutcomeUnknown) AccountNotice("The request outcome was not confirmed. Nothing retries automatically. For email verification, check your inbox before trying again.")
                    unavailableNotice?.let { message ->
                        AccountNotice(message)
                        TextButton(onClick = { if (canAct()) unavailableNotice = null }, enabled = enabled) { Text("Dismiss") }
                    }
                    // Optional native navigation, never a login, save, confirmation or proof.
                    if (showAccountTools && onAccountSettings != null && renderedAccountSettingsAction != null)
                        TextButton(onClick = {
                            if (accountSettingsAction === renderedAccountSettingsAction) {
                                val navigate = renderedAccountSettingsAction.claim(state, latestAccountSettings,
                                    canAct() && googleReview == null)
                                if (navigate != null) {
                                    accountSettingsAction = null; showAccountTools = false
                                    input = input.withoutSecrets(); focus.clearFocus()
                                    navigate()
                                }
                            }
                        }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Account & privacy") }
                    if (state.signupRecoveryRequired) {
                        AccountNotice("Saved signup needs a local recovery check before verification or resend. Resume signup sends no account or email request.")
                        AccountButton("Resume signup", enabled) { act { coordinator.resumeSignup(state) } }
                    }
                    when (state.screen) {
                        EmailAccountScreen.LOGIN, EmailAccountScreen.SIGNUP -> {
                            val signup = state.screen == EmailAccountScreen.SIGNUP
                            BlueprintAuthForm(
                                page = if (signup) BlueprintAuthPage.SIGNUP else BlueprintAuthPage.LOGIN,
                                input = input.blueprint(), onInputChange = { edit(it.emailInput()) }, enabled = enabled,
                                ageDeclaration = policy.ageDeclaration, passwordGuidance = policy.passwordGuidance,
                                errors = if (validate) errors.blueprint() else BlueprintAuthErrors(),
                                onSubmit = ::submit,
                                onSwitch = { act { coordinator.select(if (signup) EmailAccountScreen.LOGIN else EmailAccountScreen.SIGNUP, state) } },
                                onUnavailable = { if (canAct()) unavailableNotice = it },
                                onGoogle = googleSelection?.let { { if (googleSelectionCurrent() && canAct()) { input = input.withoutSecrets(); googleReview = Any() } } },
                                onPasswordRecovery = if (signup || onPasswordRecovery == null) null else {
                                    {
                                        if (canAct() && state.screen == EmailAccountScreen.LOGIN && latestPasswordRecovery === onPasswordRecovery) {
                                            input = EmailFormInput(); focus.clearFocus()
                                            latestPasswordRecovery?.invoke()
                                        }
                                    }
                                },
                            )
                            if (showAccountTools && signup && !state.signupRecoveryRequired) TextButton(onClick = { act { coordinator.resumeSignup(state) } },
                                enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Resume signup") }
                            if (showAccountTools && !signup) FeedMeDetails("Already started account setup?") {
                                Text("Resume signup reads the pending email and cooldown saved on this device. It does not verify or resend a code.")
                                if (!state.signupRecoveryRequired) AccountButton("Resume signup", enabled) { act { coordinator.resumeSignup(state) } }
                                Text("Resume account setup checks saved setup and current Terms online. Your saved drafts stay; it does not create another account.")
                                AccountButton("Resume account setup", enabled) { act { coordinator.restoreProfile(state) } }
                                Text("Resume saved setup checks the provider and may resend the same saved setup request. Choose it only to continue work you already started.")
                                AccountButton("Resume saved setup", enabled) { act { coordinator.resumeBootstrap(state) } }
                            }
                        }
                        EmailAccountScreen.VERIFY -> {
                            BlueprintAuthForm(page = BlueprintAuthPage.VERIFY, input = input.blueprint(),
                                onInputChange = { edit(it.emailInput()) }, enabled = enabled,
                                ageDeclaration = policy.ageDeclaration, passwordGuidance = policy.passwordGuidance,
                                errors = if (validate) errors.blueprint() else BlueprintAuthErrors(), maskedEmail = state.maskedEmail,
                                verificationEnabled = enabled && !state.signupRecoveryRequired && !state.signupExpired,
                                resendLabel = when { resendSeconds == null -> "Resend unavailable"; resendSeconds > 0 -> "Resend in ${resendSeconds}s"; else -> "Send a new code" },
                                resendEnabled = enabled && !state.signupRecoveryRequired && !state.signupExpired && resendSeconds == 0L,
                                onSubmit = ::submit, onResend = { act { coordinator.resend(state) } },
                                onChangeEmail = { act { coordinator.changeEmail(state) } })
                            if (showAccountTools) Text("Your pending email and cooldown are encrypted on this device. Passwords and codes are not saved. Resume signup restores this context only; it never verifies or resends.", style = MaterialTheme.typography.bodySmall)
                        }
                        EmailAccountScreen.BOOTSTRAP_RECOVERY -> {
                            Text("Account setup needs an explicit recovery check. Resume checks the provider and may resend the same saved setup request. It does not create a second account request. A verified ready account will offer a separate Continue button.")
                            AccountButton("Resume saved setup", enabled) { act { coordinator.resumeBootstrap(state) } }
                            Text("Already completed the connection step? Resume account setup checks current Terms and keeps your saved drafts without resending account setup.")
                            AccountButton("Resume account setup", enabled) { act { coordinator.restoreProfile(state) } }
                        }
                        EmailAccountScreen.PROFILE -> {
                            Text("Your account connection is ready for restricted profile setup. Choose your display name and handle next.")
                            AccountButton("Set up profile", enabled) {
                                act {
                                    val result = coordinator.takeProfile(state)
                                    if (result is PortResult.Value && hostCurrent() && attached && !leaving && !handedOff && result.value.isCurrentForNavigation) {
                                        handedOff = true; input = EmailFormInput()
                                        if (hostCurrent() && attached && !leaving && result.value.isCurrentForNavigation) onProfile(result.value)
                                    }
                                }
                            }
                        }
                        EmailAccountScreen.PRIVATE_READY -> {
                            Text("Your account is connected. Continue when you’re ready to open your kitchen.")
                            AccountButton("Continue", enabled && state.privateHandoffReady && state.failureReason == null) {
                                act {
                                    if (hostCurrent() && attached && !leaving && !handedOff && coordinator.isCurrentState(state) &&
                                        state.privateHandoffReady && state.failureReason == null) {
                                        input = EmailFormInput()
                                        // Host checks approved product configuration BEFORE the
                                        // actual one-use takePrivate/connection delivery path.
                                        onReady(state)
                                    }
                                }
                            }
                        }
                        EmailAccountScreen.READY_RECOVERY -> {
                            Text("Check your saved account before opening your kitchen. If needed, this also renews its sign-in securely. Your saved work stays on this device.")
                            Button(onClick = { act { coordinator.recoverReady(state) } }, enabled = enabled,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text("Check saved account")
                            }
                        }
                        EmailAccountScreen.TERMS_REQUIRED -> {
                            Text("Your saved account needs a current Terms review. Your existing account and saved work are kept; reviewing does not open private account access.")
                            AccountButton("Review current Terms", enabled) { act { coordinator.reviewTerms(state) } }
                        }
                        EmailAccountScreen.TERMS_REVIEW -> {
                            val selected = coordinator.currentTerms(state)
                            if (selected is PortResult.Value) {
                                val controller = selected.value
                                FeedMeAccountTermsReview(controller,
                                    accountIsCurrent = {
                                        hostCurrent() && attached && !leaving && !handedOff && !claimed &&
                                            coordinator.isCurrentState(state) &&
                                            (coordinator.currentTerms(state) as? PortResult.Value<AccountTermsController>)?.value === controller
                                    },
                                    onFinish = { reviewed ->
                                        if (controller.isCurrentState(reviewed) && reviewed.failureReason == null)
                                            act { coordinator.finishTerms(state, reviewed) }
                                    },
                                    onAccountTermsUrl = { url ->
                                        if (canAct() &&
                                            (coordinator.currentTerms(state) as? PortResult.Value<AccountTermsController>)?.value === controller)
                                            onAccountTermsUrl(url)
                                    })
                            } else if (!state.busy && !claimed) {
                                AccountNotice("This Terms review is no longer available. Go back to reconnect; your saved acceptance is kept.")
                            }
                        }
                        EmailAccountScreen.LOCAL_SIGN_OUT_REVIEW -> {
                            Text("Nothing is removed until you confirm. This action works without an internet connection.")
                        }
                        EmailAccountScreen.LOCAL_SIGN_OUT_RECOVERY -> {
                            Text("Your sign-out is kept on this device. Account access stays closed while its local cleanup is unfinished.")
                            AccountButton("Finish sign-out on this device", enabled) { act { coordinator.retryLocalSignOut(state) } }
                        }
                        EmailAccountScreen.LOCAL_SIGNED_OUT -> {
                            Text("Local account credentials and private data have been removed. Retained acceptance evidence is kept separately. This did not revoke your server session or cancel a request already sent.")
                            AccountButton("Done", enabled, ::leave)
                        }
                        EmailAccountScreen.PREFERENCES, EmailAccountScreen.PREFERENCES_RECOVERY -> {
                            // Exhaustive fallback only: the containing native host must supply
                            // the actual preferences route and retain this coordinator.
                            Text("Food preferences are not available in this host yet. Your saved setup stays on this device.")
                        }
                        EmailAccountScreen.CLOSED -> Unit
                    }
                    if ((blueprintPage == null || showAccountTools) && state.screen in setOf(EmailAccountScreen.LOGIN, EmailAccountScreen.BOOTSTRAP_RECOVERY,
                            EmailAccountScreen.READY_RECOVERY, EmailAccountScreen.TERMS_REQUIRED, EmailAccountScreen.TERMS_REVIEW)) {
                        TextButton(onClick = { act { coordinator.prepareLocalSignOut(state) } }, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Sign out on this device") }
                    }
                    if ((blueprintPage == null || showAccountTools) && state.screen in setOf(EmailAccountScreen.LOGIN, EmailAccountScreen.BOOTSTRAP_RECOVERY, EmailAccountScreen.READY_RECOVERY)) {
                        TextButton(onClick = { act { coordinator.retryLocalSignOut(state) } }, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Finish sign-out on this device") }
                    }
                    if ((blueprintPage == null || showAccountTools) && state.screen !in setOf(EmailAccountScreen.TERMS_REQUIRED, EmailAccountScreen.TERMS_REVIEW,
                            EmailAccountScreen.LOCAL_SIGN_OUT_REVIEW, EmailAccountScreen.LOCAL_SIGN_OUT_RECOVERY, EmailAccountScreen.LOCAL_SIGNED_OUT)) {
                        TextButton(onClick = { if (canAct()) onTerms() }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Read Terms") }
                        TextButton(onClick = { if (canAct()) onPrivacy() }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Read Privacy Policy") }
                    }
                }
            }
            }
            if (current && state.screen == EmailAccountScreen.LOCAL_SIGN_OUT_REVIEW) AlertDialog(
                onDismissRequest = { if (canAct()) act { coordinator.cancelLocalSignOut(state) } },
                title = { Text("Sign out on this device?") },
                text = { Text("This removes this device’s account credentials and private data, and stops its account work. Unresolved Terms acceptance evidence is kept. It does not revoke your server session or cancel a request already sent.") },
                confirmButton = { TextButton(enabled = enabled, onClick = { act { coordinator.confirmLocalSignOut(state) } },
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Sign out on this device") } },
                dismissButton = { TextButton(enabled = enabled, onClick = { act { coordinator.cancelLocalSignOut(state) } },
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Stay signed in") } },
            )
        }
    }
}

private fun EmailFormInput.blueprint() = BlueprintAuthInput(email, password, confirmation, code, termsAccepted, ageConfirmed)
private fun BlueprintAuthInput.emailInput() = EmailFormInput(email, password, confirmation, code, termsAccepted, ageConfirmed)
private fun EmailFormErrors.blueprint() = BlueprintAuthErrors(email, password, confirmation, code, declarations)

@Composable
private fun AccountButton(label: String, enabled: Boolean, action: () -> Unit) {
    Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), contentPadding = PaddingValues(16.dp)) { Text(label) }
}

@Composable
private fun AccountNotice(text: String) {
    Surface(color = FeedMeColors.SoftBlue, shape = MaterialTheme.shapes.medium) {
        Text(text, Modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
    }
}
