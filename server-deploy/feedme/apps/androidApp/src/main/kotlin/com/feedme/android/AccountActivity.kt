package com.feedme.android

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.feedme.app.*
import com.feedme.app.blueprint.*
import com.feedme.app.onboarding.*
import com.feedme.app.mealflow.FeedMeMealFlow
import com.feedme.session.EmailAccountScreen
import com.feedme.session.EmailAccountState
import com.feedme.session.AccountDeletionState

class AccountActivity : ComponentActivity() {
    private lateinit var entry: AccountEntry
    private lateinit var attachment: Any
    private var googleOAuthRequest: AndroidSupabaseOAuth? = null
    private var deletionOAuthRequest: AndroidSupabaseOAuth? = null
    private lateinit var notificationPermission: AndroidFeedMeNotificationPermission

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableFeedMeEdgeToEdge()
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        entry = (application as FeedMeApplication).accountEntry
        attachment = entry.attach()
        notificationPermission = AndroidFeedMeNotificationPermission(this,
            isCurrent = { expected -> entry.notificationControlsUsable(attachment, expected) },
            onResult = { expected -> entry.returnFromNotificationPermission(attachment, expected) })
        notificationPermission.refresh()
        var deletionFactory: (() -> EntryDeletionBrowser)? = null
        entry.configuration?.let { config -> config.googleOAuthRedirectUrl?.let { redirect ->
            if (redirect == "$packageName://auth/callback") googleOAuthRequest = AndroidSupabaseOAuth(
                this, (application as FeedMeApplication).googleOAuthCallbacks,
                config.googleOAuthProviderIssuer, redirect,
                isCurrent = { entry.attached(attachment) && entry.states.value.screen == EntryScreen.EMAIL },
            )
            if (redirect == "$packageName://auth/callback" && config.accountDeletion != null)
                deletionFactory = {
                    deletionOAuthRequest?.close()
                    val browser = AndroidSupabaseOAuth(this, (application as FeedMeApplication).googleOAuthCallbacks,
                        config.googleOAuthProviderIssuer, redirect,
                        isCurrent = { entry.deletionBrowserCurrent(attachment) })
                    deletionOAuthRequest = browser
                    EntryDeletionBrowser(browser, browser::close)
                }
        } }
        // Every attachment can view/recover a retained receipt, even with OAuth actions disabled.
        entry.bindDeletionBrowser(attachment, deletionFactory)
        setContent { Content() }
    }

    override fun onDestroy() {
        if (::notificationPermission.isInitialized) notificationPermission.close()
        googleOAuthRequest?.close()
        deletionOAuthRequest?.close()
        if (::attachment.isInitialized) entry.detach(attachment, isChangingConfigurations)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::notificationPermission.isInitialized) notificationPermission.refresh()
        googleOAuthRequest?.returned()
        deletionOAuthRequest?.returned()
        if (::attachment.isInitialized) entry.foreground(attachment, true)
    }

    override fun onPause() {
        googleOAuthRequest?.backgrounded()
        deletionOAuthRequest?.backgrounded()
        if (::attachment.isInitialized) entry.foreground(attachment, false)
        super.onPause()
    }

    @Composable
    private fun Content() {
        val observedState by entry.states.collectAsState()
        val state = observedState // Capture this exact rendering, not a later delegated value.
        val currentAttachment by entry.attachmentStates.collectAsState()
        val notificationPermissionState by notificationPermission.states.collectAsState()
        var legalUnavailable by remember { mutableStateOf(false) }
        var googleReviewRequested by remember { mutableStateOf(false) }
        var deletionOptions by remember(state) { mutableStateOf<AccountDeletionState?>(null) }
        val config = entry.configuration
        // The selected product path is Supabase browser OAuth. Never fall back to the
        // historical Credential Manager implementation when OAuth is unconfigured.
        val googleRequest = googleOAuthRequest
        // Observable removal clears old form composition; dynamic host guards below cover
        // the interval before recomposition and any queued callbacks from that attachment.
        if (currentAttachment !== attachment) return
        val accountSettings: (() -> Unit)? = if (entry.accountSettingsAvailable(attachment, state))
            { { entry.openAccountSettings(attachment, state) } } else null
        fun legal(url: String) {
            if (!entry.attached(attachment) || entry.states.value !== state) return
            try { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
            catch (_: ActivityNotFoundException) { legalUnavailable = true }
            catch (_: SecurityException) { legalUnavailable = true }
        }
        fun supportEmail() {
            if (!entry.supportCurrent(attachment, state)) return
            val message = state.support?.message ?: return
            if (!validSupportMessage(message)) return
            val notice = try {
                // Only explicit draft text leaves FeedMe. No account/session identifiers,
                // receipt, diagnostics, attachments or device details are added.
                startActivity(Intent(Intent.ACTION_SENDTO, android.net.Uri.fromParts("mailto", FEEDME_SUPPORT_EMAIL, null))
                    .putExtra(Intent.EXTRA_SUBJECT, "FeedMe support")
                    .putExtra(Intent.EXTRA_TEXT, message))
                "Email draft opened. Send it from your email app when you’re ready. No in-app ticket has been created."
            } catch (_: ActivityNotFoundException) {
                "No email app could open. Use More → Copy support address, then contact us from your preferred email service."
            } catch (_: SecurityException) {
                "Your device could not open email. Use More → Copy support address instead."
            }
            entry.supportNotice(attachment, state, notice)
        }
        fun copySupportAddress() {
            if (!entry.supportCurrent(attachment, state)) return
            val notice = try {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("FeedMe support", FEEDME_SUPPORT_EMAIL))
                "Support address copied. Your message has not been sent."
            } catch (_: RuntimeException) { "The address could not be copied. You can email $FEEDME_SUPPORT_EMAIL." }
            entry.supportNotice(attachment, state, notice)
        }
        fun deletionSupportEmail(rendered: AccountDeletionState): String {
            val child = state.deletion
            val exact = child != null && entry.deletionRouteCurrent(attachment, state) &&
                child.controller.isCurrentState(rendered)
            if (!entryDeletionSupportAvailable(exact, rendered.busy, rendered.receipt != null, rendered.screen))
                return "This saved receipt is no longer available in the current account-request view."
            val message = rendered.receipt?.jobId?.let(::entryDeletionSupportMessage)
                ?: return "This saved receipt could not be prepared for support."
            return try {
                // This explicit action shares only the opaque deletion receipt and fixed user-
                // visible text. It never attaches a token, account ID, device ID or diagnostics.
                startActivity(Intent(Intent.ACTION_SENDTO,
                    android.net.Uri.fromParts("mailto", FEEDME_SUPPORT_EMAIL, null))
                    .putExtra(Intent.EXTRA_SUBJECT, "FeedMe account deletion receipt")
                    .putExtra(Intent.EXTRA_TEXT, message))
                "Email draft opened. Review and send it from your email app when you’re ready."
            } catch (_: ActivityNotFoundException) {
                "No email app could open. Email $FEEDME_SUPPORT_EMAIL and include the receipt shown on this screen."
            } catch (_: SecurityException) {
                "Your device could not open email. Email $FEEDME_SUPPORT_EMAIL and include the receipt shown on this screen."
            }
        }
        when (state.screen) {
            EntryScreen.ACCOUNT_SETTINGS, EntryScreen.ACCOUNT_PRIVACY -> {
                val conversations = entry.accountConversationController(attachment, state)
                if (state.screen == EntryScreen.ACCOUNT_PRIVACY && conversations != null) {
                    com.feedme.app.mealflow.FeedMeThreadFlow(conversations,
                        hostIsCurrent = { entry.accountControlsCurrent(attachment, state) },
                        onClose = { entry.accountControlsAction(attachment, state, "PRIVACY.back") },
                        platformBackHandler = { enabled, back -> BackHandler(enabled, back) },
                        privacyExternalActions = buildSet {
                            if (entry.blockedAccountsAvailable(attachment, state)) add("PRIVACY.01")
                            if (entry.accountExportAvailable(attachment, state)) add("PRIVACY.02")
                            if (config?.accountDeletion != null) add("PRIVACY.03")
                            if (config != null) add("PRIVACY.06")
                        },
                        onPrivacyAction = { action ->
                            if (entry.accountControlsCurrent(attachment, state)) {
                                if (action == "PRIVACY.06" && config != null) {
                                    legal(config.privacyUrl)
                                    entry.reloadAccountPrivacy(attachment, state)
                                } else entry.accountControlsAction(attachment, state, action)
                            }
                        })
                } else EntryAccountControlsScreen(
                state.screen, enabled = entry.accountControlsCurrent(attachment, state),
                deletionConfigured = config?.accountDeletion != null, privacyAvailable = config != null,
                blockedConfigured = entry.blockedAccountsAvailable(attachment, state),
                sessionsConfigured = entry.accountSessionsAvailable(attachment, state),
                signOutAvailable = entry.accountDeviceSignOutAvailable(attachment, state),
                notificationsConfigured = entry.accountNotificationsAvailable(attachment, state),
                kitchenConfigured = entry.accountKitchenSettingsAvailable(attachment, state),
                profileConfigured = entry.accountProfileEditAvailable(attachment, state),
                memoryConfigured = entry.accountMemoryAvailable(attachment, state),
                exportConfigured = entry.accountExportAvailable(attachment, state),
                onAction = { action ->
                    if (action == "PRIVACY.06" && config != null && entry.accountControlsCurrent(attachment, state)) legal(config.privacyUrl)
                    else entry.accountControlsAction(attachment, state, action)
                }, onSupport = { entry.openAccountSupport(attachment, state) })
            }
            EntryScreen.ACCOUNT_EXPORT -> state.meal?.experience?.accountExports?.let { controller ->
                val saver = rememberAccountExportSaver(this, hostIsCurrent = { entry.accountExportUsable(attachment, state) })
                com.feedme.app.mealflow.FeedMeAccountExportFlow(controller,
                    hostIsCurrent = { entry.accountExportUsable(attachment, state) },
                    onClose = { entry.closeAccountExport(attachment, state) },
                    onDownload = saver.save, downloadBusy = saver.busy, downloadStatus = saver.status,
                    platformBackHandler = { enabled, back -> BackHandler(enabled, back) })
            }
            EntryScreen.ACCOUNT_MEMORY -> state.meal?.experience?.mealMemory?.let { controller ->
                com.feedme.app.mealflow.FeedMeTasteMemoryFlow(controller,
                    hostIsCurrent = { entry.accountMemoryUsable(attachment, state) },
                    onClose = { entry.closeAccountMemory(attachment, state) },
                    platformBackHandler = { enabled, back -> BackHandler(enabled, back) })
            }
            EntryScreen.ACCOUNT_PROFILE_EDIT -> state.meal?.experience?.profileEdit?.let { controller ->
                com.feedme.app.mealflow.FeedMeAccountProfileEditFlow(controller,
                    hostIsCurrent = { entry.accountProfileEditUsable(attachment, state) },
                    onClose = { entry.closeAccountProfileEdit(attachment, state) },
                    platformBackHandler = { enabled, back -> BackHandler(enabled, back) })
            }
            EntryScreen.ACCOUNT_BLOCKED -> state.meal?.experience?.blocks?.let { controller ->
                com.feedme.app.blocks.FeedMeBlocksFlow(controller,
                    onClose = { entry.closeBlockedAccounts(attachment, state) },
                    platformBackHandler = { enabled, back -> BackHandler(enabled, back) },
                    hostIsCurrent = { entry.blockedAccountsRouteCurrent(attachment, state) })
            }
            EntryScreen.ACCOUNT_SESSIONS -> state.meal?.experience?.sessionControls?.let { controller ->
                com.feedme.app.mealflow.FeedMeSessionControlsFlow(controller,
                    hostIsCurrent = { entry.accountSessionsRouteCurrent(attachment, state) },
                    onClose = { entry.closeAccountSessions(attachment, state) },
                    platformBackHandler = { enabled, back -> BackHandler(enabled, back) })
            }
            EntryScreen.ACCOUNT_FOOD_SETTINGS, EntryScreen.ACCOUNT_EQUIPMENT_SETTINGS -> state.meal?.let { meal ->
                com.feedme.app.mealflow.FeedMeKitchenPreferencesFlow(meal.experience,
                    page = if (state.screen == EntryScreen.ACCOUNT_FOOD_SETTINGS) BlueprintPreferencePage.FOOD_PREFS else BlueprintPreferencePage.EQUIPMENT,
                    choices = config?.preferenceChoices,
                    hostIsCurrent = { entry.accountKitchenControlsUsable(attachment, state) },
                    onClose = { entry.closeAccountKitchenSettings(attachment, state) },
                    platformBackHandler = { enabled, back -> BackHandler(enabled, back) })
            }
            EntryScreen.ACCOUNT_NOTIFICATIONS -> state.meal?.experience?.notifications?.let { controller ->
                com.feedme.app.mealflow.FeedMeNotificationsFlow(controller,
                    hostIsCurrent = { entry.notificationControlsCurrent(attachment, state) },
                    onClose = { entry.closeAccountNotifications(attachment, state) },
                    permission = notificationPermissionState,
                    onOpenPermission = { entry.openNotificationPermission(attachment, state) },
                    onOpenSystemSettings = { notificationPermission.openSettings(state) },
                    platformBackHandler = { enabled, back -> BackHandler(enabled, back) })
            }
            EntryScreen.ACCOUNT_NOTIFICATION_PERMISSION -> {
                BackHandler(entry.notificationControlsCurrent(attachment, state)) {
                    entry.returnFromNotificationPermission(attachment, state)
                }
                com.feedme.app.mealflow.FeedMeNotificationPermissionScreen(
                    permission = notificationPermissionState,
                    hostIsCurrent = { entry.notificationControlsCurrent(attachment, state) },
                    onBack = { entry.returnFromNotificationPermission(attachment, state) },
                    onEnable = { notificationPermission.request(state) },
                    onMaybeLater = { entry.closeAccountNotifications(attachment, state, returnHome = true) },
                    onOpenSystemSettings = { notificationPermission.openSettings(state) })
            }
            EntryScreen.ACCOUNT_SUPPORT -> state.support?.let { draft ->
                EntrySupportScreen(draft, enabled = entry.supportCurrent(attachment, state),
                    privacyAvailable = entry.supportHasAccountPrivacy(attachment, state) || config != null,
                    cookingAvailable = entry.supportCookingAvailable(attachment, state),
                    onMessage = { entry.editSupport(attachment, state, it) },
                    onAction = { action -> when (action) {
                        "SUPPORT.back" -> entry.closeSupport(attachment, state)
                        "SUPPORT.01" -> if (draft.message.isNotBlank()) supportEmail()
                        "SUPPORT.02" -> if (entry.supportHasAccountPrivacy(attachment, state)) entry.supportPrivacy(attachment, state)
                            else if (config != null && entry.supportCurrent(attachment, state)) legal(config.privacyUrl)
                        "SUPPORT.04" -> if (entry.supportCooking(attachment, state)) startActivity(Intent(this, GuestActivity::class.java))
                    } }, onOpenEmail = ::supportEmail, onCopyAddress = ::copySupportAddress)
            }
            EntryScreen.ACCOUNT_DELETION -> {
                val child = state.deletion
                if (child == null) EntryDeletionOpening(state.failure,
                    onBack = { entry.closeDeletion(attachment, state, null) })
                else FeedMeAccountDeletionFlow(child.controller,
                    onClose = { entry.closeDeletion(attachment, state, it) },
                    onAccepted = { entry.deletionLocalAction(attachment, state, it, EntryDeletionLocalAction.FINISH_LOCAL_CLEANUP) },
                    hostIsCurrent = { entry.deletionRouteCurrent(attachment, state) },
                    platformBackHandler = { enabled, back -> BackHandler(enabled, back) },
                    onMore = { deletionOptions = it })
                deletionOptions?.let { rendered ->
                    val exact = child != null && entry.deletionRouteCurrent(attachment, state) && child.controller.isCurrentState(rendered)
                    if (exact) EntryDeletionRecoveryMenu(rendered,
                        exact = exact,
                        onAction = { action -> deletionOptions = null; entry.deletionLocalAction(attachment, state, rendered, action) },
                        onSupport = { deletionSupportEmail(rendered) },
                        onDismiss = { deletionOptions = null })
                }
            }
            EntryScreen.ACCOUNT_RECOVERY -> state.coordinator?.let { coordinator ->
                val observedAccount by coordinator.states.collectAsState()
                val account = observedAccount // Exact rendered request input, not a later flow read.
                EntryAccountRecovery(
                    enabled = entryReturningRecoveryAvailable(
                        entry.attached(attachment) && entry.states.value === state, state.screen,
                        coordinator.isCurrentState(account), account.screen, account.busy, account.privateHandoffReady),
                    localActionsEnabled = entry.attached(attachment) && entry.states.value === state &&
                        coordinator.isCurrentState(account) && !account.busy,
                    account = account, onRecover = { entry.recoverPrivate(attachment, state, it) },
                    onSetup = { entry.resumeAccountSetup(attachment, state) },
                    onBack = { entry.back(attachment, state) }, onAccountSettings = accountSettings)
            }
            EntryScreen.PRIVATE_MEAL -> state.meal?.let { meal ->
                val observedRefresh by entry.refreshStates.collectAsState()
                val refresh = observedRefresh
                Column(Modifier.fillMaxSize()) {
                    FeedMeTheme {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("Timer alerts work while FeedMe is open.", modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall)
                            if (meal.connection.deviceSignOut != null) TextButton(
                                onClick = { entry.openDeviceSignOut(attachment, state) },
                                enabled = !refresh.busy && entry.privateRouteCurrent(attachment, state),
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("Sign out") }
                        }
                    }
                    if (config?.accountRefreshConfigured == true) FeedMeTheme {
                        Column(Modifier.fillMaxWidth().background(FeedMeColors.Paper).statusBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 4.dp)) {
                            TextButton(onClick = { entry.refreshAccount(attachment, state, refresh) },
                                enabled = entryAccountRefreshAvailable(entry.privateRouteCurrent(attachment, state),
                                    true, refresh.busy, refresh.phase), modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(if (refresh.busy) "Refreshing account…" else "Refresh account connection")
                            }
                            when {
                                refresh.busy -> Text("Your saved meal work stays on this device.", style = MaterialTheme.typography.bodySmall)
                                refresh.failure == com.feedme.core.ports.FailureReason.OFFLINE ->
                                    Text("You’re offline. Connect before refreshing.", style = MaterialTheme.typography.bodySmall)
                                refresh.failure != null -> Text("The account connection could not be confirmed. Close safely; your saved work is kept.",
                                    style = MaterialTheme.typography.bodySmall)
                                refresh.phase == com.feedme.session.AccountCredentialRefreshPhase.ACKNOWLEDGED ->
                                    Text("Account connection refreshed.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        val invitationShare = remember(meal, attachment, state) {
                            com.feedme.app.circles.AndroidCircleInvitationSharePort(this@AccountActivity) {
                                entry.privateRouteCurrent(attachment, state)
                            }
                        }
                        FeedMeMealFlow(experience = meal.experience, onExit = { entry.back(attachment, state) },
                            invitationSharePort = invitationShare,
                            platformBackHandler = { enabled, back -> BackHandler(enabled, back) },
                            hostIsCurrent = { entry.privateRouteCurrent(attachment, state) }, onAccountSettings = accountSettings,
                            onNotificationSettings = if (entry.notificationsFromMealAvailable(attachment, state))
                                ({ entry.openNotificationsFromMeal(attachment, state) }) else null,
                            preferenceChoices = config?.preferenceChoices)
                    }
                }
            }
            EntryScreen.PRIVATE_SIGN_OUT -> state.meal?.connection?.deviceSignOut?.let { controller ->
                val signOut = controller.states.collectAsState().value
                val host = entry.signOutStates.collectAsState().value
                EntryDeviceSignOutScreen(signOut, host,
                    enabled = entry.deviceSignOutRouteCurrent(attachment, state),
                    onAction = { entry.deviceSignOutAction(attachment, state, signOut, it) },
                    onClose = { entry.closeDeviceSignOut(attachment, state, signOut) })
            }
            EntryScreen.PASSWORD_RECOVERY -> state.passwordRecovery?.let { recovery ->
                FeedMePasswordRecoveryFlow(recovery,
                    hostIsCurrent = { entry.passwordRecoveryCurrent(attachment, state) },
                    onBackToLogin = { entry.closePasswordRecovery(attachment, state) },
                    platformBackHandler = { enabled, back -> BackHandler(enabled, back) })
            } ?: run {
                val recoveryView = BlueprintAccountSetupState(BlueprintAccountSetupPage.AUTH_RESET,
                    enabledActions = setOf(BlueprintAccountSetupAction.BACK),
                    notice = "Password recovery is unavailable. Return to sign in; no reset request was sent.")
                BackHandler(true) { entry.closePasswordRecovery(attachment, state) }
                BlueprintAccountSetupScreen(recoveryView, onFieldsChange = {}, onAction = {
                    if (it == BlueprintAccountSetupAction.BACK) entry.closePasswordRecovery(attachment, state)
                })
            }
            EntryScreen.EMAIL -> if (config != null && state.coordinator != null) FeedMeEmailAccountFlow(
                coordinator = state.coordinator, policy = config.form, clock = entry.clock,
                onBack = { entry.back(attachment, state) },
                onTerms = { legal(config.termsUrl) }, onPrivacy = { legal(config.privacyUrl) },
                onProfile = { entry.showProfile(attachment, state, it) },
                onReady = { entry.continueReadyBootstrap(attachment, state, it) },
                platformBackHandler = { enabled, back -> BackHandler(enabled, back) },
                hostIsCurrent = { entry.attached(attachment) && entry.states.value === state },
                onAccountTermsUrl = { url -> legal(url) },
                googleOAuthCredentialRequest = googleRequest, reviewGoogleOnEntry = googleReviewRequested,
                onAccountSettings = accountSettings,
                onPasswordRecovery = if (entry.passwordRecoveryAvailable(attachment, state))
                    ({ entry.openPasswordRecovery(attachment, state) }) else null)
            EntryScreen.PROFILE -> state.profile?.let { profile -> FeedMeOnboardingProfileFlow(
                controller = profile.controller, clock = entry.clock,
                onBack = { entry.back(attachment, state) }, onPreferences = { entry.openPreferences(attachment, state, it) },
                platformBackHandler = { enabled, back -> BackHandler(enabled, back) },
                hostIsCurrent = { entry.attached(attachment) && entry.states.value === state }, onAccountSettings = accountSettings,
                onOptionalLater = { entry.setOptionalPreferencesLater(attachment, state, it) }) }
            EntryScreen.FOOD_PREFERENCES, EntryScreen.EQUIPMENT -> state.preferences?.let { preferences ->
                val prompt = state.prompt
                val form = state.form
                if (prompt != null && form != null) FeedMeOnboardingPreferencesFlow(
                    controller = preferences.controller, prompt = prompt, choices = config?.preferenceChoices,
                    formMemory = form, clock = entry.clock, onBack = { entry.back(attachment, state) },
                    onDecision = { saved, choice -> entry.openDecision(attachment, state, saved, choice) },
                    platformBackHandler = { enabled, back -> BackHandler(enabled, back) },
                    hostIsCurrent = { entry.attached(attachment) && entry.states.value === state })
            }
            EntryScreen.OPTIONAL_CHECKPOINT -> state.profile?.let { profile -> state.intent?.let { intent ->
                FeedMeOptionalDecisionFlow(controller = profile.controller, prompt = intent.prompt, choice = intent.choice,
                    clock = entry.clock, freshDecision = state.freshDecision, onBack = { entry.back(attachment, state) },
                    onContinue = { entry.continueCheckpoint(attachment, state, it) },
                    platformBackHandler = { enabled, back -> BackHandler(enabled, back) },
                    hostIsCurrent = { entry.attached(attachment) && entry.states.value === state })
            } }
            EntryScreen.ROUTE_SELECTION, EntryScreen.RECOVERY, EntryScreen.WORKING, EntryScreen.SETUP_REMAINING,
            EntryScreen.PRODUCT_REQUIRED ->
                EntryRecovery(state,
                    onLoad = { entry.loadSetupRoute(attachment, state) },
                    onProfile = { entry.recoverProfileRoute(attachment, state) },
                    onPreferences = { entry.recoverPreferenceWork(attachment, state) },
                    onBack = { entry.back(attachment, state) })
            else -> EntryLanding(state, config != null,
                onLogin = { googleReviewRequested = false; entry.start(attachment, state, EmailAccountScreen.LOGIN) },
                onSignup = { googleReviewRequested = false; entry.start(attachment, state, EmailAccountScreen.SIGNUP) },
                onGoogle = googleRequest?.let { {
                    if (entry.attached(attachment) && entry.states.value === state && state.screen == EntryScreen.WELCOME) {
                        googleReviewRequested = true
                        entry.start(attachment, state, EmailAccountScreen.LOGIN)
                    }
                } },
                onGuest = {
                    if (entry.attached(attachment) && entry.states.value === state && state.screen == EntryScreen.WELCOME)
                        startActivity(Intent(this, GuestActivity::class.java))
                },
                documentUrls = if (config == null) emptyMap() else mapOf(
                    BlueprintLegalDocument.PRIVACY to config.privacyUrl,
                    BlueprintLegalDocument.TERMS to config.termsUrl),
                onOpenDocument = { url -> legal(url) },
                onCopyDocument = { url ->
                    if (entry.attached(attachment) && entry.states.value === state && config != null &&
                        url in setOf(config.termsUrl, config.privacyUrl)) {
                        try { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("FeedMe document", url)) }
                        catch (_: RuntimeException) { legalUnavailable = true }
                    }
                },
                onSupport = { entry.openAccountSupport(attachment, state) },
                onClose = { entry.back(attachment, state) })
        }
        if (legalUnavailable) FeedMeTheme { AlertDialog(onDismissRequest = { legalUnavailable = false },
            title = { Text("Document action unavailable") },
            text = { Text("This document action could not be completed. Please try again.") },
            confirmButton = { TextButton(onClick = { legalUnavailable = false }) { Text("Got it") } }) }
    }
}

@Composable
private fun EntryLanding(state: EntryState, configured: Boolean,
    onLogin: () -> Unit, onSignup: () -> Unit, onGuest: () -> Unit,
    onGoogle: (() -> Unit)?,
    documentUrls: Map<BlueprintLegalDocument, String>, onOpenDocument: (String) -> Unit,
    onCopyDocument: (String) -> Unit, onSupport: () -> Unit, onClose: () -> Unit) {
    val working = state.screen in setOf(EntryScreen.OPENING, EntryScreen.CLOSING)
    var notice by remember { mutableStateOf<String?>(null) }
    var unconfiguredPage by remember { mutableStateOf<BlueprintAuthPage?>(null) }
    var moreOpen by remember { mutableStateOf(false) }
    var legalDocument by remember { mutableStateOf<BlueprintLegalDocument?>(null) }
    BackHandler(state.screen != EntryScreen.WELCOME, onClose)
    if (state.screen == EntryScreen.WELCOME || working) {
        if (legalDocument != null && !working) {
            BackHandler(true) { legalDocument = null }
            val selected = requireNotNull(legalDocument)
            val url = documentUrls[selected]
            val legalState = BlueprintLegalState(selectedDocument = selected, canOpen = url != null, canCopyUrl = url != null,
                status = if (url == null) "This document is not configured for the connected app yet." else "Open the published document for its current full text.")
            BlueprintLegalScreen(legalState,
                onSelect = { expected, document -> if (legalDocument == selected && expected === legalState) legalDocument = document },
                onOpen = { expected -> if (legalDocument == selected && expected === legalState && url != null) onOpenDocument(url) },
                onCopyUrl = { expected -> if (legalDocument == selected && expected === legalState && url != null) onCopyDocument(url) },
                onBack = { legalDocument = null })
        } else if (unconfiguredPage != null && !configured) {
            BackHandler(true) { unconfiguredPage = null }
            FeedMeTheme {
                Column(Modifier.fillMaxSize().background(FeedMeColors.Paper).safeDrawingPadding().imePadding()) {
                    BlueprintAppBar("Your FeedMe", onBack = { unconfiguredPage = null }, onMore = { moreOpen = true })
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
                        Spacer(Modifier.height(10.dp))
                        BlueprintAuthForm(page = requireNotNull(unconfiguredPage), enabled = false,
                            ageDeclaration = "Age eligibility is not configured in this development build.",
                            passwordGuidance = "Account connection is not configured. Please do not enter a real password.",
                            onSwitch = { unconfiguredPage = if (unconfiguredPage == BlueprintAuthPage.LOGIN) BlueprintAuthPage.SIGNUP else BlueprintAuthPage.LOGIN },
                            onUnavailable = { notice = it })
                        Spacer(Modifier.height(16.dp))
                        Text("Sign-in is not connected in this build yet. These fields are read-only; no credentials are collected or sent.",
                            style = BlueprintType.Body, color = FeedMeColors.Muted)
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        } else BlueprintWelcomeScreen(
            onSignup = { if (configured) onSignup() else unconfiguredPage = BlueprintAuthPage.SIGNUP },
            onLogin = { if (configured) onLogin() else unconfiguredPage = BlueprintAuthPage.LOGIN },
            onGuest = onGuest,
            onApple = { notice = "Apple sign-in is not connected yet. No account request has been sent." },
            onGoogle = { onGoogle?.invoke() ?: run { notice = "Google sign-in is not connected yet. No account request has been sent." } },
            onMore = { moreOpen = true },
            enabled = !working,
            status = if (working) if (state.screen == EntryScreen.OPENING) "Opening secure account setup…" else "Closing account setup safely…" else null,
            onCancel = if (state.screen == EntryScreen.OPENING) onClose else null)
        if (moreOpen) FeedMeTheme {
            AlertDialog(onDismissRequest = { moreOpen = false }, title = { Text("More options") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BlueprintButton("Terms and privacy", {
                        moreOpen = false; legalDocument = BlueprintLegalDocument.PRIVACY
                    }, Modifier.fillMaxWidth(), icon = "shield")
                    BlueprintButton("Help and support", { moreOpen = false; onSupport() }, Modifier.fillMaxWidth(), icon = "chat", enabled = !working)
                } },
                confirmButton = { TextButton(onClick = { moreOpen = false }) { Text("Close") } })
        }
        notice?.let { message -> FeedMeTheme {
            AlertDialog(onDismissRequest = { notice = null }, title = { Text("FeedMe") }, text = { Text(message) },
                confirmButton = { TextButton(onClick = { notice = null }) { Text("Got it") } })
        } }
        return
    }
    FeedMeTheme {
        Column(Modifier.fillMaxSize().background(FeedMeColors.Paper).safeDrawingPadding()
            .imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            FeedMeWordmark()
            Text("GOOD FOOD.\nLESS EFFORT.", style = MaterialTheme.typography.displayMedium,
                modifier = Modifier.semantics { heading() })
            Text("Your kitchen, your people, your next good bite.", style = MaterialTheme.typography.bodyLarge)
            Surface(color = FeedMeColors.Lime, shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("A little cooking. A lot of you.", style = MaterialTheme.typography.titleLarge)
                    Text("Make Mine · Today · My Plate · Kitchen circles")
                }
            }
            Surface(color = FeedMeColors.SoftBlue, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Connected development build", style = MaterialTheme.typography.titleMedium)
                    Text("This development build connects account setup and, only with an approved product configuration and current verified private session, the real meal-request flow. Live service availability, native acceptance and release checks remain separate. Social destinations are not connected here; this is not a store release.")
                    if (!configured) Text("Account connection is not configured. No sign-in request will be sent. The builder needs to supply the approved public client configuration.")
                }
            }
            when {
                working -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (state.screen == EntryScreen.OPENING) "Opening secure account setup…" else "Closing account setup safely…")
                    if (state.screen == EntryScreen.OPENING) TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                }
                state.screen == EntryScreen.CLOSE_REQUIRED -> {
                    Text("Account connection needs attention", style = MaterialTheme.typography.titleLarge)
                    Text("The last step could not be confirmed. Close this connection before trying again. Saved setup is not deleted or sent automatically.")
                    Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Close connection") }
                }
                else -> {
                    Button(onClick = onSignup, enabled = configured, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Create an account") }
                    OutlinedButton(onClick = onLogin, enabled = configured, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Log in or resume setup") }
                }
            }
            Text("Support: hazloteams3@gmail.com", style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
        }
    }
}

@Composable
private fun EntryRecovery(state: EntryState, onLoad: () -> Unit, onProfile: () -> Unit,
    onPreferences: () -> Unit, onBack: () -> Unit) {
    BackHandler(true, onBack)
    FeedMeTheme {
        Column(Modifier.fillMaxSize().background(FeedMeColors.Paper).safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
            FeedMeWordmark(compact = true)
            when (state.screen) {
                EntryScreen.WORKING -> {
                    Text("Checking your account", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Saved choices and original requests stay on this device. Back closes this connection safely.")
                }
                EntryScreen.SETUP_REMAINING -> {
                    Text("More setup is still required", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    Text("A saved setup step is not enough to open cooking. Finish and confirm the kitchen-setup step first. If this device previously finished setup, close this connection and choose ‘Continue with saved account’ when it is offered. Your current sign-in and account requirements still need to be checked online.")
                    Text("Your saved choices are retained. Nothing is sent automatically.")
                }
                EntryScreen.PRODUCT_REQUIRED -> {
                    Text("Cooking connection needs configuration", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    Text("This build has no approved meal-service policies and reviewed choices. The builder must supply them and configure the actual backend before the meal flow can be used.")
                    Text("This action has not activated an account or checked a saved sign-in. Your records and saved choices remain on this device. There is no demo fallback.")
                }
                EntryScreen.ROUTE_SELECTION -> {
                    Text("Resume your setup", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    Text("Load the current profile online to find the correct food or equipment step. A saved step on this device is not assumed current, and loading does not send a save or confirm an original request.")
                    Button(onClick = onLoad, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Load current setup step") }
                    OutlinedButton(onClick = onProfile, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Reopen saved profile or checkpoint") }
                }
                else -> {
                    Text("Saved setup needs attention", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    Text("The last transition was not confirmed. Reopen locally before choosing what to do next. An original request is kept unchanged; any retry needs a separate explicit review.")
                    if (state.failure != null) Text("This connection could not confirm that action. No completion is assumed.")
                    Button(onClick = onProfile, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Reopen saved profile or checkpoint") }
                    OutlinedButton(onClick = onPreferences, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Recover saved preference work") }
                    Text("Preference recovery is local only. It does not load, save, retry or navigate onward. Reopen the profile separately after recovery.")
                }
            }
        }
    }
}

/** An explicit choice only. No effects, provider calls, recovery or navigation on mounting. */
@Composable
private fun EntryAccountRecovery(enabled: Boolean, localActionsEnabled: Boolean, account: EmailAccountState,
    onRecover: (EmailAccountState) -> Unit, onSetup: () -> Unit, onBack: () -> Unit,
    onAccountSettings: (() -> Unit)? = null) {
    BackHandler(true, onBack)
    FeedMeTheme {
        Column(Modifier.fillMaxSize().background(FeedMeColors.Paper).safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
            FeedMeWordmark(compact = true)
            Text("Continue your account", style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() })
            Text("This device has saved account data. Opening this screen has not signed you in or sent anything online.")
            Text("If you finished setup on this device, connect to check your saved sign-in and current account requirements before opening cooking.")
            Button(onClick = { onRecover(account) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Continue with saved account") }
            Text("If setup was interrupted, reopen it to review what is still needed. Nothing is retried automatically.")
            OutlinedButton(onClick = onSetup, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Resume account setup") }
            OutlinedButton(onClick = onSetup, enabled = localActionsEnabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Device sign-out options") }
            onAccountSettings?.let { action ->
                OutlinedButton(onClick = action, enabled = localActionsEnabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Account & privacy") }
            }
            Text("Device sign-out works offline. Open its options to review local removal or finish an interrupted sign-out. Nothing is removed until you confirm; this does not revoke a remote session or cancel a request already sent.",
                style = MaterialTheme.typography.bodySmall, color = FeedMeColors.Muted)
        }
    }
}
