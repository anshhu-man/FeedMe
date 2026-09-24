package com.feedme.android

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import com.feedme.core.ports.*
import com.feedme.session.*
import com.feedme.app.onboarding.OnboardingPreferencesFormMemory
import com.feedme.app.mealflow.MealFlowExperience
import com.feedme.mealflow.MealOperationIds
import java.io.FileNotFoundException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process ownership survives Activity recreation AND a failed final close. No account I/O
 * starts in Application.onCreate. An unfinished owner is retained for an explicit close retry. */
class FeedMeApplication : Application() {
    private val defaultAccountEntry = lazy { AccountEntry(this) }
    private var testAccountEntry: AccountEntry? = null
    internal val accountEntry: AccountEntry get() = testAccountEntry ?: defaultAccountEntry.value
    private var retainedGuestEntry: GuestEntry? = null
    internal val guestEntry: GuestEntry get() {
        check(Looper.myLooper() == Looper.getMainLooper())
        return retainedGuestEntry ?: GuestEntry(this).also { retainedGuestEntry = it }
    }
    internal val googleOAuthCallbacks = GoogleOAuthBrowserOwner(android.os.SystemClock::elapsedRealtime)

    /** A fully acknowledged guest-to-account handoff may discard only that closed process
     * owner. The next intentional guest entry receives a new composition; old Activities can
     * neither reacquire it nor close its replacement. */
    internal fun releaseGuestEntry(expected: GuestEntry) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(retainedGuestEntry === expected && expected.closedForReplacement())
        retainedGuestEntry = null
        expected.disposeClosed()
    }

    /** Instrumentation-only typed composition. No Intent, asset replacement, injected native
     * owner, accepting verifier or route setter. Existing owners/attachments must be closed. */
    internal fun installAccountEntryForTests(composition: AccountEntryTestComposition): AccountEntry {
        check(BuildConfig.DEBUG && Looper.myLooper() == Looper.getMainLooper())
        check(testAccountEntry == null && (!defaultAccountEntry.isInitialized() || defaultAccountEntry.value.quiescentForTests()))
        check(composition.context.applicationContext === composition.context)
        check(composition.context.packageName == packageName)
        check(composition.context.noBackupFilesDir.canonicalFile != noBackupFilesDir.canonicalFile)
        return AccountEntry(this, composition).also { testAccountEntry = it }
    }

    internal fun removeAccountEntryForTests(expected: AccountEntry) {
        check(BuildConfig.DEBUG && Looper.myLooper() == Looper.getMainLooper())
        check(testAccountEntry === expected && expected.quiescentForTests())
        expected.disposeTestComposition()
        testAccountEntry = null
    }
}

/** A suspension can delay a real native observation but cannot supply or modify its result.
 * This internal descriptor has no production loader and is rejected outside debug builds. */
internal class AccountEntryTestComposition(val context: Context, val configuration: AccountConfiguration,
    val clock: EpochClock, val connectivity: ConnectivityPort, val beforeReceiptInspection: suspend () -> Unit = {})

internal enum class EntryScreen { WELCOME, OPENING, EMAIL, PASSWORD_RECOVERY, ACCOUNT_RECOVERY, PROFILE, FOOD_PREFERENCES, EQUIPMENT,
    OPTIONAL_CHECKPOINT, ROUTE_SELECTION, RECOVERY, WORKING, SETUP_REMAINING, PRODUCT_REQUIRED,
    PRIVATE_MEAL, PRIVATE_SIGN_OUT, ACCOUNT_SETTINGS, ACCOUNT_PRIVACY, ACCOUNT_BLOCKED, ACCOUNT_SESSIONS,
    ACCOUNT_NOTIFICATIONS, ACCOUNT_NOTIFICATION_PERMISSION, ACCOUNT_FOOD_SETTINGS, ACCOUNT_EQUIPMENT_SETTINGS,
    ACCOUNT_PROFILE_EDIT, ACCOUNT_MEMORY, ACCOUNT_EXPORT, ACCOUNT_PLAN,
    ACCOUNT_SUPPORT, ACCOUNT_DELETION, CLOSE_REQUIRED, CLOSING }
internal data class EntryDecisionIntent(val prompt: OnboardingOptionalPrompt, val choice: OnboardingDecisionChoice)
internal class EntryMealDestination(val handoff: EmailAccountPrivateHandoff,
    val connection: EmailAccountPrivateConnection, val experience: MealFlowExperience,
    val reviewedIntegration: NativeReviewedKitchenIntegration? = null) {
    override fun toString() = "EntryMealDestination(<redacted>)"
}
private typealias EntryMealDelivery = EntryPrivateDelivery<EmailAccountState, EmailAccountPrivateHandoff,
    EmailAccountPrivateConnection, EntryMealDestination>
internal class EntryState(val screen: EntryScreen, val coordinator: EmailAccountCoordinator? = null,
    val profile: EmailAccountProfileHandoff? = null, val failure: FailureReason? = null,
    val preferences: EmailAccountPreferencesHandoff? = null, val prompt: OnboardingOptionalPrompt? = null,
    val intent: EntryDecisionIntent? = null, val form: OnboardingPreferencesFormMemory? = null,
    val freshDecision: Boolean = false, val meal: EntryMealDestination? = null,
    val deletion: AndroidAccountDeletionHandle? = null, val support: EntrySupportDraft? = null,
    val passwordRecovery: AccountPasswordRecoveryController? = null) {
    override fun toString() = "EntryState(screen=$screen, failure=$failure, details=<redacted>)"
}

internal class EntryAccountRefreshState(val busy: Boolean = false,
    val phase: AccountCredentialRefreshPhase? = null, val failure: FailureReason? = null) {
    override fun toString() = "EntryAccountRefreshState(busy=$busy, phase=$phase, failure=$failure)"
}

internal class EntryDeviceSignOutState(val busy: Boolean = false, val failure: FailureReason? = null)
internal class EntryDeletionBrowser(val request: GoogleOAuthCredentialRequest, val close: () -> Unit)

/** Only Activity callbacks on the main thread use this gate. Old attachments cannot close,
 * navigate, or dispatch through the current one; recreation may retain the underlying owner. */
internal class EntryAttachmentGate {
    private val current = MutableStateFlow<Any?>(null)
    val states = current.asStateFlow()
    fun attach(): Any = Any().also { current.value = it }
    fun owns(token: Any) = current.value === token
    fun detach(token: Any): Boolean {
        if (!owns(token)) return false
        current.value = null
        return true
    }
}

internal class AccountEntry(private val application: Application, private val testComposition: AccountEntryTestComposition? = null) {
    init { check(testComposition == null || BuildConfig.DEBUG) }
    private val nativeContext: Context = testComposition?.context ?: application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val attachments = EntryAttachmentGate()
    val attachmentStates = attachments.states
    private var operation: Any? = null
    private var owner: AndroidEmailAccountOwner? = null
    private var cookingTimers: AccountCookingTimers? = null
    private var foregroundToken: Any? = null
    private var privateDelivery: EntryMealDelivery? = null
    private var privateDeliveryJob: Job? = null
    private var refreshJob: Job? = null
    private var signOutJob: Job? = null
    private var deletionJob: Job? = null
    private var deletionHandle: AndroidAccountDeletionHandle? = null
    private var deletionRoute: Any? = null
    private var deletionBrowserToken: Any? = null
    private var deletionBrowserFactory: (() -> EntryDeletionBrowser)? = null
    private var accountSettingsReturn: EntryState? = null
    private var notificationsReturnToMeal = false
    private var passwordRecoveryRoute: Any? = null
    private var passwordRecoveryOwner: AccountPasswordRecoveryController? = null
    private var supportReturn: EntryScreen? = null
    private val signOutMutable = MutableStateFlow(EntryDeviceSignOutState())
    val signOutStates = signOutMutable.asStateFlow()
    private val refreshMutable = MutableStateFlow(EntryAccountRefreshState())
    val refreshStates = refreshMutable.asStateFlow()
    private val mutable = MutableStateFlow(EntryState(EntryScreen.WELCOME))
    val states = mutable.asStateFlow()
    val clock = testComposition?.clock ?: EpochClock { System.currentTimeMillis() }
    val configuration: AccountConfiguration? = testComposition?.configuration ?: loadFeedMeConfiguration(application)
    private val connectivity = testComposition?.connectivity ?: ConnectivityPort {
        try {
            val manager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.activeNetwork
            if (network == null) Connectivity.OFFLINE else {
                val capabilities = manager.getNetworkCapabilities(network)
                if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
                    Connectivity.ONLINE else Connectivity.UNKNOWN
            }
        } catch (_: Exception) { Connectivity.UNKNOWN }
    }

    fun attach(): Any {
        mainThread(); foregroundToken = null
        return attachments.attach().also { cookingTimers?.foreground?.attach(it) }
    }
    fun attached(token: Any): Boolean = attachments.owns(token)

    fun foreground(token: Any, resumed: Boolean) {
        mainThread()
        if (!attachments.owns(token)) return
        foregroundToken = if (resumed) token else null
        cookingTimers?.foreground?.foreground(token, resumed)
    }

    fun detach(token: Any, changingConfiguration: Boolean) {
        mainThread()
        if (!attachments.owns(token)) return
        cookingTimers?.foreground?.detach(token); foregroundToken = null
        if (deletionBrowserToken === token) {
            deletionBrowserToken = null; deletionBrowserFactory = null
            if (!changingConfiguration || deletionHandle?.isReceiptOnly != true) {
                deletionHandle?.invalidate(); deletionJob?.cancel()
            }
        }
        if (attachments.detach(token) && !changingConfiguration) {
            if (owner != null) closeOwned()
            else if (mutable.value.screen == EntryScreen.ACCOUNT_SUPPORT) {
                supportReturn = null
                mutable.value = EntryState(EntryScreen.WELCOME)
            }
        }
    }

    fun start(token: Any, expected: EntryState, screen: EmailAccountScreen) {
        mainThread()
        if (!attachments.owns(token) || mutable.value !== expected || operation != null || owner != null ||
            expected.screen != EntryScreen.WELCOME || screen !in setOf(EmailAccountScreen.LOGIN, EmailAccountScreen.SIGNUP)) return
        val config = configuration ?: return
        val ticket = Any(); operation = ticket
        val timers = AccountCookingTimers(nativeContext, clock) { connection, experience ->
            val state = mutable.value
            val meal = state.meal
            val host = foregroundToken
            host != null && meal != null && meal.connection === connection && meal.experience === experience &&
                privateRouteCurrent(host, state)
        }
        cookingTimers = timers
        timers.foreground.attach(token)
        timers.foreground.foreground(token, foregroundToken === token)
        val selected = AndroidEmailAccountOwner.create(nativeContext, config.bootstrap, Dispatchers.Main.immediate,
            clock, connectivity, config::forInstallation, timers::open, config.accountDeletion)
        owner = selected // Retain BEFORE the first suspension, including a failed open.
        mutable.value = EntryState(EntryScreen.OPENING)
        scope.launch {
            try {
                val opened = selected.open()
                if (!owns(ticket, selected)) return@launch
                if (opened is PortResult.Failure) { failOpen(opened.reason); return@launch }
                // Read-only receipt interception precedes coordinator recovery/sign-in. A
                // retained original is never mistaken for an empty installation or retried.
                testComposition?.beforeReceiptInspection?.invoke()
                if (!owns(ticket, selected)) return@launch
                val deletion = selected.hasRetainedAccountDeletion()
                if (!owns(ticket, selected)) return@launch
                if (deletion is PortResult.Failure) { failOpen(deletion.reason); return@launch }
                if ((deletion as PortResult.Value).value) {
                    val destination = EntryState(EntryScreen.ACCOUNT_DELETION)
                    mutable.value = destination; operation = null
                    // Recreation may have replaced the attachment while native open was
                    // suspended. Its current browser binding owns this local handoff.
                    deletionBrowserToken?.takeIf(attachments::owns)?.let { openDeletion(it, destination) }
                    return@launch
                }
                val recovered = selected.recover()
                if (!owns(ticket, selected)) return@launch
                if (recovered is PortResult.Failure) { failOpen(recovered.reason); return@launch }
                val coordinator = (recovered as PortResult.Value).value
                // A locally inspected archive is only a route hint. Returning private proof
                // requires its separate explicit action; mounting this chooser does no I/O.
                if (screen == EmailAccountScreen.SIGNUP && selected.states.value.phase == AndroidEmailAccountPhase.AUTH_OR_RECOVERY) {
                    val result = coordinator.select(screen, coordinator.states.value)
                    if (!owns(ticket, selected)) return@launch
                    if (result is PortResult.Failure) { failOpen(result.reason); return@launch }
                }
                if (!selected.isCurrent(coordinator)) { failOpen(FailureReason.STALE_SESSION); return@launch }
                val destination = entryAccountStartDestination(selected.states.value.phase)
                    ?: run { failOpen(FailureReason.STALE_SESSION); return@launch }
                mutable.value = EntryState(destination, coordinator)
            } catch (cancelled: CancellationException) {
                if (owns(ticket, selected)) failOpen(FailureReason.OUTCOME_UNKNOWN)
                throw cancelled
            } catch (_: Exception) {
                if (owns(ticket, selected)) failOpen(FailureReason.UNAVAILABLE)
            } finally { if (operation === ticket) operation = null }
        }
    }

    /** Each browser owner belongs to one Activity attachment and one deletion handle. The
     * factory is forgotten on detach; only the independent native receipt survives restart. */
    fun bindDeletionBrowser(token: Any, factory: (() -> EntryDeletionBrowser)? = null) {
        mainThread()
        if (!attachments.owns(token)) return
        deletionBrowserToken = token; deletionBrowserFactory = factory
        if (mutable.value.screen == EntryScreen.ACCOUNT_DELETION) openDeletion(token, mutable.value, reattach = true)
    }

    fun deletionBrowserCurrent(token: Any): Boolean = attachments.owns(token) &&
        deletionBrowserToken === token && mutable.value.screen == EntryScreen.ACCOUNT_DELETION &&
        deletionRoute != null && owner != null

    fun accountSettingsAvailable(token: Any, expected: EntryState): Boolean = entryAccountSettingsAvailable(
        configuration != null, attachments.owns(token), mutable.value === expected,
        operation != null || refreshMutable.value.busy, owner != null, expected.screen)

    fun passwordRecoveryAvailable(token: Any, expected: EntryState): Boolean {
        if (!attachments.owns(token) || mutable.value !== expected || expected.screen != EntryScreen.EMAIL ||
            operation != null || passwordRecoveryOwner != null || configuration?.passwordRecoveryPolicy == null) return false
        val coordinator = expected.coordinator ?: return false
        val observed = coordinator.states.value
        return owner?.isCurrent(coordinator) == true && observed.screen == EmailAccountScreen.LOGIN &&
            !observed.busy && !observed.bootstrapRecoveryRequired && !observed.signupRecoveryRequired && !observed.privateHandoffReady
    }

    fun openPasswordRecovery(token: Any, expected: EntryState) {
        mainThread()
        if (!passwordRecoveryAvailable(token, expected)) return
        val selected = owner ?: return
        val coordinator = expected.coordinator ?: return
        val route = Any(); passwordRecoveryRoute = route
        mutable.value = EntryState(EntryScreen.PASSWORD_RECOVERY, coordinator)
        try {
            val recovery = checkNotNull(configuration).createPasswordRecovery(Dispatchers.Main.immediate, clock, connectivity) {
                passwordRecoveryRoute === route && owner === selected && selected.isCurrent(coordinator) &&
                    mutable.value.screen == EntryScreen.PASSWORD_RECOVERY && mutable.value.coordinator === coordinator
            }
            passwordRecoveryOwner = recovery
            mutable.value = EntryState(EntryScreen.PASSWORD_RECOVERY, coordinator, passwordRecovery = recovery)
        } catch (_: Exception) {
            passwordRecoveryRoute = null
            mutable.value = EntryState(EntryScreen.PASSWORD_RECOVERY, coordinator, failure = FailureReason.NOT_CONFIGURED)
        }
    }

    fun passwordRecoveryCurrent(token: Any, expected: EntryState): Boolean =
        attachments.owns(token) && mutable.value === expected && expected.screen == EntryScreen.PASSWORD_RECOVERY &&
            expected.coordinator?.let { owner?.isCurrent(it) } == true && operation == null

    fun closePasswordRecovery(token: Any, expected: EntryState) {
        mainThread()
        if (!attachments.owns(token) || mutable.value !== expected || expected.screen != EntryScreen.PASSWORD_RECOVERY) return
        passwordRecoveryRoute = null
        passwordRecoveryOwner?.close(); passwordRecoveryOwner = null
        val coordinator = expected.coordinator
        if (coordinator == null || owner?.isCurrent(coordinator) != true) { closeOwned(); return }
        // Recovery never enters the ordinary runtime. Return only to the existing fresh
        // login form; no bootstrap, credentials, profile or private handoff is synthesized.
        mutable.value = EntryState(EntryScreen.EMAIL, coordinator)
    }

    fun openAccountSettings(token: Any, expected: EntryState) {
        mainThread()
        if (!accountSettingsAvailable(token, expected)) return
        cookingTimers?.pauseForAccountReview()
        accountSettingsReturn = expected
        notificationsReturnToMeal = false
        mutable.value = EntryState(EntryScreen.ACCOUNT_SETTINGS)
    }

    fun accountControlsCurrent(token: Any, expected: EntryState): Boolean = attachments.owns(token) &&
        mutable.value === expected && owner != null && operation == null &&
        expected.screen in setOf(EntryScreen.ACCOUNT_SETTINGS, EntryScreen.ACCOUNT_PRIVACY)

    fun accountControlsAction(token: Any, expected: EntryState, action: String) {
        mainThread()
        if (!accountControlsCurrent(token, expected)) return
        when {
            expected.screen == EntryScreen.ACCOUNT_SETTINGS && action == "SETTINGS.01" -> openAccountProfileEdit(token, expected)
            expected.screen == EntryScreen.ACCOUNT_SETTINGS && action == "SETTINGS.04" -> openAccountMemory(token, expected)
            expected.screen == EntryScreen.ACCOUNT_SETTINGS && action == "SETTINGS.05" ->
                openAccountPrivacy(token)
            expected.screen == EntryScreen.ACCOUNT_SETTINGS && action == "SETTINGS.10" -> openAccountSupport(token, expected)
            expected.screen == EntryScreen.ACCOUNT_SETTINGS && action == "SETTINGS.07" -> openAccountSessions(token, expected)
            expected.screen == EntryScreen.ACCOUNT_SETTINGS && action == "SETTINGS.09" -> openAccountPlan(token, expected)
            expected.screen == EntryScreen.ACCOUNT_SETTINGS && action == "SETTINGS.12" -> openAccountDeviceSignOut(token, expected)
            expected.screen == EntryScreen.ACCOUNT_SETTINGS && action == "SETTINGS.06" -> openAccountNotifications(token, expected)
            expected.screen == EntryScreen.ACCOUNT_SETTINGS && action == "SETTINGS.02" -> openAccountKitchenSettings(token, expected, equipment = false)
            expected.screen == EntryScreen.ACCOUNT_SETTINGS && action == "SETTINGS.03" -> openAccountKitchenSettings(token, expected, equipment = true)
            expected.screen == EntryScreen.ACCOUNT_PRIVACY && action == "PRIVACY.back" ->
                mutable.value = EntryState(EntryScreen.ACCOUNT_SETTINGS)
            expected.screen == EntryScreen.ACCOUNT_PRIVACY && action == "PRIVACY.01" -> openBlockedAccounts(token, expected)
            expected.screen == EntryScreen.ACCOUNT_PRIVACY && action == "PRIVACY.02" -> openAccountExport(token, expected)
            expected.screen == EntryScreen.ACCOUNT_PRIVACY && action == "PRIVACY.03" -> openDeletion(token, expected)
            expected.screen == EntryScreen.ACCOUNT_SETTINGS && action == "SETTINGS.back" -> returnFromAccountSettings(token, expected)
        }
    }

    fun blockedAccountsAvailable(token: Any, expected: EntryState): Boolean = accountControlsCurrent(token, expected) &&
        accountSettingsReturn?.meal?.experience?.blocks != null

    fun accountSessionsAvailable(token: Any, expected: EntryState): Boolean = accountControlsCurrent(token, expected) &&
        accountSettingsReturn?.meal?.experience?.sessionControls != null

    fun accountNotificationsAvailable(token: Any, expected: EntryState): Boolean = accountControlsCurrent(token, expected) &&
        accountSettingsReturn?.meal?.experience?.notifications != null

    fun accountProfileEditAvailable(token: Any, expected: EntryState): Boolean =
        accountControlsCurrent(token, expected) && expected.screen == EntryScreen.ACCOUNT_SETTINGS &&
            accountSettingsReturn?.meal?.experience?.profileEdit != null

    fun accountMemoryAvailable(token: Any, expected: EntryState): Boolean =
        accountControlsCurrent(token, expected) && expected.screen == EntryScreen.ACCOUNT_SETTINGS &&
            accountSettingsReturn?.meal?.experience?.mealMemory != null

    fun accountPlanAvailable(token: Any, expected: EntryState): Boolean =
        accountControlsCurrent(token, expected) && expected.screen == EntryScreen.ACCOUNT_SETTINGS &&
            accountSettingsReturn?.meal != null

    private fun openAccountPlan(token: Any, expected: EntryState) {
        if (!accountPlanAvailable(token, expected)) return
        mutable.value = EntryState(EntryScreen.ACCOUNT_PLAN, meal = accountSettingsReturn?.meal)
    }

    fun accountPlanCurrent(token: Any, expected: EntryState): Boolean =
        attachments.owns(token) && mutable.value === expected && owner != null && operation == null &&
            expected.screen == EntryScreen.ACCOUNT_PLAN && expected.meal != null &&
            accountSettingsReturn?.meal === expected.meal

    fun accountPlanUsable(token: Any, expected: EntryState): Boolean {
        if (!accountPlanCurrent(token, expected)) return false
        val source = accountSettingsReturn ?: return false
        val meal = expected.meal ?: return false
        return source.coordinator?.let { owner?.isCurrent(it) } == true &&
            meal.connection.boundary.isCurrent(meal.connection.access.lease)
    }

    fun closeAccountPlan(token: Any, expected: EntryState) {
        mainThread()
        if (!accountPlanCurrent(token, expected)) return
        if (!accountPlanUsable(token, expected)) { closeOwned(); return }
        mutable.value = EntryState(EntryScreen.ACCOUNT_SETTINGS)
    }

    fun accountExportAvailable(token: Any, expected: EntryState): Boolean =
        accountControlsCurrent(token, expected) && expected.screen == EntryScreen.ACCOUNT_PRIVACY &&
            accountSettingsReturn?.meal?.experience?.accountExports != null

    private fun openAccountExport(token: Any, expected: EntryState) {
        if (!accountExportAvailable(token, expected)) return
        val meal = accountSettingsReturn?.meal ?: return
        val page = EntryState(EntryScreen.ACCOUNT_EXPORT, meal = meal)
        mutable.value = page
        scope.launch { if (accountExportUsable(token, page)) meal.experience.accountExports?.open() }
    }

    fun accountExportCurrent(token: Any, expected: EntryState): Boolean =
        attachments.owns(token) && mutable.value === expected && owner != null && operation == null &&
            expected.screen == EntryScreen.ACCOUNT_EXPORT && expected.meal != null && accountSettingsReturn?.meal === expected.meal

    fun accountExportUsable(token: Any, expected: EntryState): Boolean {
        if (!accountExportCurrent(token, expected)) return false
        val source = accountSettingsReturn ?: return false
        val meal = expected.meal ?: return false
        return source.coordinator?.let { owner?.isCurrent(it) } == true &&
            meal.connection.boundary.isCurrent(meal.connection.access.lease) && !refreshMutable.value.busy
    }

    fun closeAccountExport(token: Any, expected: EntryState) {
        mainThread()
        if (!accountExportCurrent(token, expected)) return
        if (!accountExportUsable(token, expected)) { closeOwned(); return }
        val controller = expected.meal?.experience?.accountExports ?: return
        val observed = controller.states.value
        scope.launch {
            if (!accountExportCurrent(token, expected) || controller.states.value !== observed) return@launch
            if (controller.leave(observed) is PortResult.Value && accountExportCurrent(token, expected)) {
                if (!accountExportUsable(token, expected)) { closeOwned(); return@launch }
                mutable.value = EntryState(EntryScreen.ACCOUNT_PRIVACY)
            }
        }
    }

    private fun openAccountMemory(token: Any, expected: EntryState) {
        if (!accountMemoryAvailable(token, expected)) return
        val meal = accountSettingsReturn?.meal ?: return
        val page = EntryState(EntryScreen.ACCOUNT_MEMORY, meal = meal)
        mutable.value = page
        scope.launch { if (accountMemoryUsable(token, page)) meal.experience.mealMemory?.openHistory() }
    }

    fun accountMemoryCurrent(token: Any, expected: EntryState): Boolean =
        attachments.owns(token) && mutable.value === expected && owner != null && operation == null &&
            expected.screen == EntryScreen.ACCOUNT_MEMORY && expected.meal != null &&
            accountSettingsReturn?.meal === expected.meal

    fun accountMemoryUsable(token: Any, expected: EntryState): Boolean {
        if (!accountMemoryCurrent(token, expected)) return false
        val source = accountSettingsReturn ?: return false
        val meal = expected.meal ?: return false
        return source.coordinator?.let { owner?.isCurrent(it) } == true &&
            meal.connection.boundary.isCurrent(meal.connection.access.lease)
    }

    fun closeAccountMemory(token: Any, expected: EntryState) {
        mainThread()
        if (!accountMemoryCurrent(token, expected)) return
        if (!accountMemoryUsable(token, expected)) { closeOwned(); return }
        mutable.value = EntryState(EntryScreen.ACCOUNT_SETTINGS)
    }

    private fun openAccountProfileEdit(token: Any, expected: EntryState) {
        if (!accountProfileEditAvailable(token, expected)) return
        val meal = accountSettingsReturn?.meal ?: return
        val page = EntryState(EntryScreen.ACCOUNT_PROFILE_EDIT, meal = meal)
        mutable.value = page
        scope.launch { if (accountProfileEditUsable(token, page)) meal.experience.profileEdit?.open() }
    }

    fun accountProfileEditCurrent(token: Any, expected: EntryState): Boolean =
        attachments.owns(token) && mutable.value === expected && owner != null && operation == null &&
            expected.screen == EntryScreen.ACCOUNT_PROFILE_EDIT && expected.meal != null &&
            accountSettingsReturn?.meal === expected.meal

    fun accountProfileEditUsable(token: Any, expected: EntryState): Boolean {
        if (!accountProfileEditCurrent(token, expected)) return false
        val source = accountSettingsReturn ?: return false
        val meal = expected.meal ?: return false
        return source.coordinator?.let { owner?.isCurrent(it) } == true &&
            meal.connection.boundary.isCurrent(meal.connection.access.lease)
    }

    fun closeAccountProfileEdit(token: Any, expected: EntryState) {
        mainThread()
        if (!accountProfileEditCurrent(token, expected)) return
        if (!accountProfileEditUsable(token, expected)) { closeOwned(); return }
        val controller = expected.meal?.experience?.profileEdit ?: return
        val observed = controller.states.value
        // The shared flow has resolved its unsaved-input review, but the native owner
        // retires the exact child once. Queued originals remain protected on departure.
        scope.launch {
            if (!accountProfileEditCurrent(token, expected) || controller.states.value !== observed) return@launch
            val result = controller.leave(observed)
            if (result is PortResult.Value && controller.states.value === result.value &&
                result.value.phase == com.feedme.mealflow.profile.AccountProfileEditPhase.HIDDEN &&
                accountProfileEditCurrent(token, expected)) {
                if (!accountProfileEditUsable(token, expected)) { closeOwned(); return@launch }
                mutable.value = EntryState(EntryScreen.ACCOUNT_SETTINGS)
            }
        }
    }

    fun notificationsFromMealAvailable(token: Any, expected: EntryState): Boolean =
        privateRouteCurrent(token, expected) && accountSettingsAvailable(token, expected) &&
            !refreshMutable.value.busy && expected.meal?.experience?.notifications != null

    fun openNotificationsFromMeal(token: Any, expected: EntryState) {
        mainThread()
        if (!notificationsFromMealAvailable(token, expected)) return
        openAccountSettings(token, expected)
        val settings = mutable.value
        if (settings.screen != EntryScreen.ACCOUNT_SETTINGS || accountSettingsReturn !== expected) return
        openAccountNotifications(token, settings)
        notificationsReturnToMeal = mutable.value.screen == EntryScreen.ACCOUNT_NOTIFICATIONS
    }

    fun accountKitchenSettingsAvailable(token: Any, expected: EntryState): Boolean =
        accountControlsCurrent(token, expected) && expected.screen == EntryScreen.ACCOUNT_SETTINGS &&
            accountSettingsReturn?.meal != null

    private fun openAccountKitchenSettings(token: Any, expected: EntryState, equipment: Boolean) {
        if (!accountKitchenSettingsAvailable(token, expected)) return
        val meal = accountSettingsReturn?.meal ?: return
        val page = EntryState(if (equipment) EntryScreen.ACCOUNT_EQUIPMENT_SETTINGS else EntryScreen.ACCOUNT_FOOD_SETTINGS, meal = meal)
        mutable.value = page
        // This explicit Settings action owns the read. Composition/rotation does not repeat
        // it; neither restoring a local draft nor observing preferences submits a save.
        scope.launch {
            if (!accountKitchenControlsUsable(token, page)) return@launch
            val kitchen = meal.experience.kitchen
            if (kitchen.restore() is PortResult.Value && accountKitchenControlsUsable(token, page)) kitchen.loadPreferences()
        }
    }

    fun accountKitchenControlsCurrent(token: Any, expected: EntryState): Boolean =
        attachments.owns(token) && mutable.value === expected && owner != null && operation == null &&
            expected.screen in setOf(EntryScreen.ACCOUNT_FOOD_SETTINGS, EntryScreen.ACCOUNT_EQUIPMENT_SETTINGS) &&
            expected.meal != null && accountSettingsReturn?.meal === expected.meal

    fun accountKitchenControlsUsable(token: Any, expected: EntryState): Boolean {
        if (!accountKitchenControlsCurrent(token, expected)) return false
        val source = accountSettingsReturn ?: return false
        val meal = expected.meal ?: return false
        return source.coordinator?.let { owner?.isCurrent(it) } == true &&
            meal.connection.boundary.isCurrent(meal.connection.access.lease)
    }

    fun closeAccountKitchenSettings(token: Any, expected: EntryState) {
        mainThread()
        if (!accountKitchenControlsCurrent(token, expected)) return
        if (!accountKitchenControlsUsable(token, expected)) { closeOwned(); return }
        // The flow resolves only its unretained form before calling this. The retained
        // kitchen draft and every original command remain owned by the same account.
        mutable.value = EntryState(EntryScreen.ACCOUNT_SETTINGS)
    }

    private fun openAccountNotifications(token: Any, expected: EntryState) {
        if (!accountNotificationsAvailable(token, expected)) return
        val meal = accountSettingsReturn?.meal ?: return
        val page = EntryState(EntryScreen.ACCOUNT_NOTIFICATIONS, meal = meal)
        mutable.value = page
        scope.launch { if (notificationControlsUsable(token, page)) meal.experience.notifications?.open() }
    }

    fun notificationControlsCurrent(token: Any, expected: EntryState): Boolean =
        attachments.owns(token) && mutable.value === expected && owner != null && operation == null &&
            expected.screen in setOf(EntryScreen.ACCOUNT_NOTIFICATIONS, EntryScreen.ACCOUNT_NOTIFICATION_PERMISSION) &&
            expected.meal != null && accountSettingsReturn?.meal === expected.meal

    fun notificationControlsUsable(token: Any, expected: EntryState): Boolean {
        if (!notificationControlsCurrent(token, expected)) return false
        val source = accountSettingsReturn ?: return false
        val meal = expected.meal ?: return false
        return source.coordinator?.let { owner?.isCurrent(it) } == true &&
            meal.connection.boundary.isCurrent(meal.connection.access.lease)
    }

    fun openNotificationPermission(token: Any, expected: EntryState) {
        mainThread()
        if (!notificationControlsUsable(token, expected) || expected.screen != EntryScreen.ACCOUNT_NOTIFICATIONS) return
        // The same settings owner remains mounted logically; unsaved choices are not re-read.
        mutable.value = EntryState(EntryScreen.ACCOUNT_NOTIFICATION_PERMISSION, meal = expected.meal)
    }

    fun returnFromNotificationPermission(token: Any, expected: EntryState) {
        mainThread()
        if (!notificationControlsCurrent(token, expected) || expected.screen != EntryScreen.ACCOUNT_NOTIFICATION_PERMISSION) return
        mutable.value = EntryState(EntryScreen.ACCOUNT_NOTIFICATIONS, meal = expected.meal)
    }

    fun closeAccountNotifications(token: Any, expected: EntryState, returnHome: Boolean = false) {
        mainThread()
        if (!notificationControlsCurrent(token, expected)) return
        if (!notificationControlsUsable(token, expected)) { closeOwned(); return }
        val controller = expected.meal?.experience?.notifications ?: return
        val observed = controller.states.value
        scope.launch {
            if (notificationControlsCurrent(token, expected) && controller.states.value === observed &&
                controller.leave(observed) is PortResult.Value && notificationControlsCurrent(token, expected)) {
                if (!notificationControlsUsable(token, expected)) { closeOwned(); return@launch }
                val settings = EntryState(EntryScreen.ACCOUNT_SETTINGS)
                mutable.value = settings
                val returnToSource = returnHome || notificationsReturnToMeal
                notificationsReturnToMeal = false
                if (returnToSource) returnFromAccountSettings(token, settings)
            }
        }
    }

    fun accountDeviceSignOutAvailable(token: Any, expected: EntryState): Boolean {
        if (!accountControlsCurrent(token, expected) || expected.screen != EntryScreen.ACCOUNT_SETTINGS || refreshMutable.value.busy) return false
        val source = accountSettingsReturn ?: return false
        val meal = source.meal ?: return false
        return source.coordinator?.let { owner?.isCurrent(it) } == true && meal.connection.deviceSignOut != null &&
            meal.connection.boundary.isCurrent(meal.connection.access.lease)
    }

    private fun openAccountDeviceSignOut(token: Any, expected: EntryState) {
        if (!accountDeviceSignOutAvailable(token, expected)) return
        val source = accountSettingsReturn ?: return
        // A fresh native route joins the already-owned sign-out review. This action does
        // not send logout, destroy a session or revive pre-Settings callbacks.
        val destination = EntryState(EntryScreen.PRIVATE_MEAL, source.coordinator, meal = source.meal)
        mutable.value = destination
        accountSettingsReturn = null
        openDeviceSignOut(token, destination)
    }

    private fun openAccountSessions(token: Any, expected: EntryState) {
        if (!accountSessionsAvailable(token, expected)) return
        val meal = accountSettingsReturn?.meal ?: return
        val page = EntryState(EntryScreen.ACCOUNT_SESSIONS, meal = meal)
        mutable.value = page
        scope.launch { if (accountSessionsCurrent(token, page)) meal.experience.sessionControls?.open() }
    }

    fun accountSessionsRouteCurrent(token: Any, expected: EntryState): Boolean {
        val source = accountSettingsReturn ?: return false
        val meal = expected.meal ?: return false
        return attachments.owns(token) && mutable.value === expected && owner != null && operation == null &&
            expected.screen == EntryScreen.ACCOUNT_SESSIONS && source.meal === meal
    }

    private fun accountSessionsCurrent(token: Any, expected: EntryState): Boolean {
        if (!accountSessionsRouteCurrent(token, expected)) return false
        val source = accountSettingsReturn ?: return false
        val meal = expected.meal ?: return false
        return source.coordinator?.let { owner?.isCurrent(it) } == true &&
            meal.connection.boundary.isCurrent(meal.connection.access.lease)
    }

    fun closeAccountSessions(token: Any, expected: EntryState) {
        mainThread()
        if (!accountSessionsRouteCurrent(token, expected)) return
        if (!accountSessionsCurrent(token, expected)) { closeOwned(); return }
        // The child explicitly leaves first; a stale Close cannot abandon an in-flight write.
        if (expected.meal?.experience?.sessionControls?.states?.value?.phase !=
            com.feedme.mealflow.sessioncontrols.SessionControlsPhase.HIDDEN) return
        mutable.value = EntryState(EntryScreen.ACCOUNT_SETTINGS)
    }

    fun accountConversationController(token: Any, expected: EntryState): com.feedme.mealflow.conversation.ThreadController? =
        if (accountControlsCurrent(token, expected)) accountSettingsReturn?.meal?.experience?.conversations else null

    fun reloadAccountPrivacy(token: Any, expected: EntryState) {
        mainThread()
        if (accountControlsCurrent(token, expected) && expected.screen == EntryScreen.ACCOUNT_PRIVACY) openAccountPrivacy(token)
    }

    /** Entered only from an explicit current navigation action, never from composition. */
    private fun openAccountPrivacy(token: Any) {
        val page = EntryState(EntryScreen.ACCOUNT_PRIVACY)
        mutable.value = page
        val controller = accountSettingsReturn?.meal?.experience?.conversations ?: return
        scope.launch { if (accountControlsCurrent(token, page)) controller.openPrivacy() }
    }

    private fun openBlockedAccounts(token: Any, expected: EntryState) {
        if (!blockedAccountsAvailable(token, expected)) return
        val meal = accountSettingsReturn?.meal ?: return
        val page = EntryState(EntryScreen.ACCOUNT_BLOCKED, meal = meal)
        mutable.value = page
        scope.launch {
            // The controller renders its own redacted unavailable state after invalidation.
            if (blockedAccountsRouteCurrent(token, page)) meal.experience.blocks?.open()
        }
    }

    fun blockedAccountsRouteCurrent(token: Any, expected: EntryState): Boolean {
        val source = accountSettingsReturn ?: return false
        val meal = expected.meal ?: return false
        return attachments.owns(token) && mutable.value === expected && owner != null && operation == null &&
            expected.screen == EntryScreen.ACCOUNT_BLOCKED && source.meal === meal
    }

    fun blockedAccountsCurrent(token: Any, expected: EntryState): Boolean {
        if (!blockedAccountsRouteCurrent(token, expected)) return false
        val source = accountSettingsReturn ?: return false
        val meal = expected.meal ?: return false
        return source.coordinator?.let { owner?.isCurrent(it) } == true &&
            meal.connection.boundary.isCurrent(meal.connection.access.lease)
    }

    fun closeBlockedAccounts(token: Any, expected: EntryState) {
        mainThread()
        if (!blockedAccountsRouteCurrent(token, expected)) return
        // Local departure remains possible after revocation; it never grants a private read.
        if (!blockedAccountsCurrent(token, expected)) { closeOwned(); return }
        val controller = expected.meal?.experience?.blocks ?: return
        val observed = controller.states.value
        scope.launch {
            if (blockedAccountsCurrent(token, expected) && controller.states.value === observed &&
                controller.leave(observed) is PortResult.Value && blockedAccountsCurrent(token, expected))
                openAccountPrivacy(token)
        }
    }

    /** Support is a local email draft, available without sign-in or deletion configuration. */
    fun supportAvailable(token: Any, expected: EntryState): Boolean = attachments.owns(token) &&
        mutable.value === expected && operation == null && expected.screen in setOf(
            EntryScreen.WELCOME, EntryScreen.ACCOUNT_SETTINGS, EntryScreen.ACCOUNT_PRIVACY)

    fun openAccountSupport(token: Any, expected: EntryState) {
        mainThread()
        if (!supportAvailable(token, expected)) return
        supportReturn = expected.screen
        mutable.value = EntryState(EntryScreen.ACCOUNT_SUPPORT, support = EntrySupportDraft())
    }

    fun supportCurrent(token: Any, expected: EntryState): Boolean = attachments.owns(token) &&
        mutable.value === expected && operation == null && expected.screen == EntryScreen.ACCOUNT_SUPPORT && expected.support != null

    fun editSupport(token: Any, expected: EntryState, message: String) {
        mainThread()
        if (!supportCurrent(token, expected)) return
        val draft = checkNotNull(expected.support)
        mutable.value = EntryState(EntryScreen.ACCOUNT_SUPPORT, support = if (validSupportMessage(message)) EntrySupportDraft(message)
            else EntrySupportDraft(draft.message, "Keep your message under $SUPPORT_MESSAGE_LIMIT characters and use plain text."))
    }

    fun supportNotice(token: Any, expected: EntryState, notice: String) {
        mainThread()
        if (supportCurrent(token, expected)) mutable.value = EntryState(EntryScreen.ACCOUNT_SUPPORT,
            support = EntrySupportDraft(checkNotNull(expected.support).message, notice))
    }

    fun closeSupport(token: Any, expected: EntryState) {
        mainThread()
        if (!supportCurrent(token, expected)) return
        val destination = supportReturn ?: EntryScreen.WELCOME
        supportReturn = null
        mutable.value = EntryState(destination)
    }

    fun supportHasAccountPrivacy(token: Any, expected: EntryState): Boolean = supportCurrent(token, expected) &&
        owner != null && supportReturn in setOf(EntryScreen.ACCOUNT_SETTINGS, EntryScreen.ACCOUNT_PRIVACY)

    fun supportPrivacy(token: Any, expected: EntryState) {
        mainThread()
        if (!supportHasAccountPrivacy(token, expected)) return
        supportReturn = null
        openAccountPrivacy(token)
    }

    fun supportCookingAvailable(token: Any, expected: EntryState): Boolean = supportCurrent(token, expected) &&
        (supportReturn == EntryScreen.WELCOME || accountSettingsReturn?.screen == EntryScreen.PRIVATE_MEAL)

    /** True requests the already-existing guest cooking Activity, not account admission. */
    fun supportCooking(token: Any, expected: EntryState): Boolean {
        mainThread()
        if (!supportCookingAvailable(token, expected)) return false
        if (supportReturn == EntryScreen.WELCOME) { closeSupport(token, expected); return true }
        supportReturn = null
        val settings = EntryState(EntryScreen.ACCOUNT_SETTINGS)
        mutable.value = settings
        returnFromAccountSettings(token, settings)
        return false
    }

    private fun returnFromAccountSettings(token: Any, expected: EntryState) {
        val selected = owner ?: return
        val source = accountSettingsReturn ?: run { closeOwned(); return }
        val ticket = Any(); operation = ticket
        scope.launch {
            try {
                val ready = source.coordinator?.let(selected::isCurrent) == true &&
                    (source.profile?.isCurrentForNavigation ?: true) &&
                    (source.preferences?.isCurrentForNavigation ?: true) &&
                    (source.meal?.connection?.isCurrentForNavigation() ?: true)
                if (!owns(ticket, selected) || !attachments.owns(token) || mutable.value !== expected) return@launch
                if (!ready) { closeOwned(); return@launch }
                // A fresh route identity prevents old pre-settings callbacks becoming current.
                mutable.value = EntryState(source.screen, source.coordinator, source.profile, source.failure,
                    source.preferences, source.prompt, source.intent, source.form, source.freshDecision, source.meal)
                accountSettingsReturn = null; operation = null
                if (source.screen == EntryScreen.PRIVATE_MEAL && cookingTimers?.resumeAfterAccountReview() is PortResult.Failure)
                    failOpen(FailureReason.UNAVAILABLE)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (owns(ticket, selected) && mutable.value === expected) failOpen(FailureReason.STALE_SESSION)
            } finally {
                if (operation === ticket) {
                    operation = null
                    // A replaced Activity must observe admission reopening even when its
                    // predecessor's suspended Back action cannot deliver private navigation.
                    if (mutable.value === expected) mutable.value = EntryState(expected.screen)
                }
            }
        }
    }

    private fun openDeletion(token: Any, expected: EntryState, reattach: Boolean = false) {
        mainThread()
        if (!attachments.owns(token) || mutable.value !== expected || owner == null ||
            (!reattach && operation != null) || expected.screen !in setOf(EntryScreen.ACCOUNT_PRIVACY, EntryScreen.ACCOUNT_DELETION)) return
        val selected = owner ?: return
        // A local-only job has no Activity dependency. Preserve its exact original and receipt
        // across recreation, including a rename that committed before its result was delivered.
        if (reattach && expected.deletion != null && expected.deletion === deletionHandle &&
            expected.deletion.isReceiptOnly && expected.deletion.isCurrentForDelivery()) return
        val factory = deletionBrowserFactory?.takeIf { deletionBrowserToken === token }
        val previousJob = deletionJob; previousJob?.cancel()
        val previous = deletionHandle; previous?.invalidate(); deletionHandle = null
        val route = Any(); deletionRoute = route
        val ticket = Any(); operation = ticket
        val pending = EntryState(EntryScreen.ACCOUNT_DELETION)
        mutable.value = pending
        fun current() = owner === selected && deletionRoute === route && mutable.value.screen == EntryScreen.ACCOUNT_DELETION
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var browser: EntryDeletionBrowser? = null
            var handedOff = false
            try {
                previousJob?.join()
                previous?.let { value(it.close()) }
                if (!owns(ticket, selected) || !attachments.owns(token) || !current()) return@launch
                val retained = value(selected.hasRetainedAccountDeletion())
                if (!owns(ticket, selected) || !attachments.owns(token) || !current()) return@launch
                val child = if (retained) value(selected.openRetainedAccountDeletion(::current)) else {
                    if (configuration?.accountDeletion == null || factory == null)
                        throw EntryRouteFailure(FailureReason.NOT_CONFIGURED)
                    browser = factory()
                    value(selected.openAccountDeletion(browser.request, browser.close, ::current))
                }
                handedOff = true; deletionHandle = child // Retain before loading or any other await.
                if (!owns(ticket, selected) || !attachments.owns(token) || !current()) { child.invalidate(); return@launch }
                val destination = EntryState(EntryScreen.ACCOUNT_DELETION, deletion = child)
                mutable.value = destination
                val loaded = child.controller.load(child.controller.states.value)
                if (owns(ticket, selected) && current() && mutable.value === destination && loaded is PortResult.Failure)
                    mutable.value = EntryState(EntryScreen.ACCOUNT_DELETION, failure = loaded.reason, deletion = child)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (owns(ticket, selected) && current()) mutable.value = EntryState(EntryScreen.ACCOUNT_DELETION,
                    failure = (failure as? EntryRouteFailure)?.reason ?: FailureReason.UNAVAILABLE, deletion = deletionHandle)
            } finally {
                if (!handedOff) try { browser?.close?.invoke() } catch (_: Exception) { }
                if (operation === ticket) operation = null
                if (deletionJob === currentCoroutineContext()[Job]) deletionJob = null
            }
        }
        deletionJob = job; job.start()
    }

    fun deletionRouteCurrent(token: Any, expected: EntryState): Boolean = attachments.owns(token) &&
        mutable.value === expected && expected.screen == EntryScreen.ACCOUNT_DELETION && owner != null &&
        expected.deletion != null && deletionHandle === expected.deletion &&
        expected.deletion.isCurrentForDelivery()

    fun deletionLocalAction(token: Any, expected: EntryState, rendered: AccountDeletionState, action: EntryDeletionLocalAction) {
        mainThread()
        val child = expected.deletion ?: return
        if (operation != null || !deletionRouteCurrent(token, expected) || !entryDeletionLocalActionAvailable(
                child.controller.isCurrentState(rendered), rendered.busy, rendered.receipt != null, rendered.screen, action,
                rendered.archivePending)) return
        val selected = owner ?: return
        if (action == EntryDeletionLocalAction.CONTINUE_TO_SIGN_IN && child.sealForLocalRecovery(rendered) !is PortResult.Value) return
        val ticket = Any(); operation = ticket
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!owns(ticket, selected) || mutable.value !== expected) return@launch
                when (action) {
                    EntryDeletionLocalAction.RECOVER_RECEIPT -> child.controller.recoverReceipt(rendered)
                    EntryDeletionLocalAction.FINISH_LOCAL_CLEANUP -> child.controller.retireAccepted(rendered)
                    EntryDeletionLocalAction.CONTINUE_TO_SIGN_IN -> {
                        val result = child.controller.archiveCompleted(rendered)
                        if (result is PortResult.Value && result.value.screen == AccountDeletionScreen.ARCHIVED &&
                            result.value.failureReason == null && child.controller.isCurrentState(result.value) &&
                            owns(ticket, selected) && mutable.value === expected) {
                            // Do not make closeOwned join/cancel this executing child job.
                            if (deletionJob === currentCoroutineContext()[Job]) deletionJob = null
                            operation = null
                            closeOwned() // Fresh sign-in is admitted only after acknowledged owner shutdown.
                        }
                    }
                }
            } finally {
                if (operation === ticket) operation = null
                if (deletionJob === currentCoroutineContext()[Job]) deletionJob = null
            }
        }
        deletionJob = job; job.start()
    }

    fun closeDeletion(token: Any, expected: EntryState, rendered: AccountDeletionState?) {
        mainThread()
        if (!attachments.owns(token) || mutable.value !== expected || expected.screen != EntryScreen.ACCOUNT_DELETION) return
        val child = expected.deletion
        if (rendered != null && (child == null || !child.controller.isCurrentState(rendered))) return
        if (rendered != null && entryDeletionCanReturnToPrivacy(rendered.screen, rendered.busy) && operation == null) {
            deletionRoute = null; child?.invalidate()
            openAccountPrivacy(token)
        } else closeOwned() // Never reactivate a possibly revoked account or infer remote cancellation.
    }

    /** Switch to the existing explicit unfinished-setup controls without reading or sending. */
    fun resumeAccountSetup(token: Any, expected: EntryState) {
        mainThread()
        if (!routeCurrent(token, expected) || expected.screen != EntryScreen.ACCOUNT_RECOVERY) return
        mutable.value = EntryState(EntryScreen.EMAIL, expected.coordinator)
    }

    /** Exact rendered coordinator state, never a stored ready flag, is the request input.
     * Real product configuration is checked before recovery or one-use handoff consumption. */
    fun recoverPrivate(token: Any, expected: EntryState, account: EmailAccountState) {
        mainThread()
        val coordinator = expected.coordinator ?: return
        if (!entryReturningRecoveryAvailable(routeCurrent(token, expected), expected.screen,
                coordinator.isCurrentState(account), account.screen, account.busy, account.privateHandoffReady) ||
            privateDelivery != null) return
        val product = configuration?.privateMeal
        if (product == null) {
            mutable.value = EntryState(EntryScreen.PRODUCT_REQUIRED, coordinator, failure = FailureReason.NOT_CONFIGURED)
            return
        }
        val selected = owner ?: return
        val ticket = Any(); operation = ticket
        val pending = EntryState(EntryScreen.WORKING, coordinator)
        mutable.value = pending
        scope.launch {
            try {
                val prepared = value(selected.prepareSavedAccount(coordinator, account))
                if (!owns(ticket, selected)) return@launch
                requireEntry(attachments.owns(token) && mutable.value === pending && selected.isCurrent(coordinator))
                val destination = entrySavedAccountDestination(prepared.kind,
                    coordinator.isCurrentState(prepared.state), prepared.state.screen,
                    prepared.state.busy, prepared.state.privateHandoffReady)
                    ?: throw EntryRouteFailure(FailureReason.STALE_SESSION)
                val next = EntryState(destination, coordinator)
                mutable.value = next
                // V8 preparation only exposed the existing explicit current check / Terms
                // route. Never route a kind hint into unrestricted bootstrap replay.
                if (prepared.kind == AndroidSavedAccountKind.ONBOARDING_PRIVATE) {
                    operation = null
                    deliverPrivate(token, next, product) {
                        if (!coordinator.isCurrentState(prepared.state)) PortResult.Failure(FailureReason.STALE_SESSION)
                        else coordinator.recoverPrivate(prepared.state)
                    }
                }
            } catch (cancelled: CancellationException) {
                if (owns(ticket, selected)) mutable.value = EntryState(entrySavedAccountFailureDestination(
                    selected.isCurrent(coordinator), coordinator.states.value.screen),
                    coordinator, failure = FailureReason.OUTCOME_UNKNOWN)
                throw cancelled
            } catch (failed: Exception) {
                if (owns(ticket, selected)) mutable.value = EntryState(entrySavedAccountFailureDestination(
                    selected.isCurrent(coordinator), coordinator.states.value.screen),
                    coordinator, failure = (failed as? EntryRouteFailure)?.reason ?: FailureReason.UNAVAILABLE)
            } finally { if (operation === ticket) operation = null }
        }
    }

    fun showProfile(token: Any, expected: EntryState, handoff: EmailAccountProfileHandoff) {
        mainThread()
        val coordinator = expected.coordinator ?: return
        if (!attachments.owns(token) || mutable.value !== expected || expected.screen != EntryScreen.EMAIL ||
            owner?.isCurrent(coordinator) != true || !handoff.isCurrentForNavigation || operation != null) return
        mutable.value = profileRoute(coordinator, handoff)
    }

    /** Actual login/resume already delivered this ready state. The click consumes it only
     * after configuration checks; it never repeats auth/bootstrap or fabricates a profile. */
    fun continueReadyBootstrap(token: Any, expected: EntryState, account: EmailAccountState) {
        mainThread()
        val coordinator = expected.coordinator ?: return
        if (!entryBootstrapReadyAvailable(routeCurrent(token, expected), expected.screen,
                coordinator.isCurrentState(account), account.screen, account.busy, account.privateHandoffReady) ||
            account.failureReason != null || privateDelivery != null) return
        val product = configuration?.privateMeal
        if (product == null) {
            mutable.value = EntryState(EntryScreen.PRODUCT_REQUIRED, coordinator, failure = FailureReason.NOT_CONFIGURED)
            return
        }
        deliverPrivate(token, expected, product) {
            if (!coordinator.isCurrentState(account) || !account.privateHandoffReady || account.failureReason != null)
                PortResult.Failure(FailureReason.STALE_SESSION)
            else PortResult.Value(account)
        }
    }

    fun openPreferences(token: Any, expected: EntryState, completion: OnboardingProfileState) {
        mainThread()
        val profile = expected.profile ?: return
        val prompt = entryAcknowledgedPrompt(profile.controller.isCurrentPreferencesCompletion(completion),
            profile.controller.isCurrentOptionalCompletion(completion), completion.onboardingStep) ?: return
        transition(token, expected) { coordinator ->
            requireEntry(profile.isCurrentForNavigation && profile.controller.isCurrentState(completion))
            val result = value(coordinator.openPreferences(profile, completion, coordinator.states.value))
            val handoff = value(coordinator.takePreferences(result))
            preferencesRoute(coordinator, handoff, prompt)
        }
    }

    /** PROFILE_SETUP.02 is one explicit shortcut after the required profile save. Every
     * optional checkpoint still belongs to its real restricted owner and durable command.
     * Recreation/recovery never reruns this shortcut or invents a ready private session. */
    fun setOptionalPreferencesLater(token: Any, expected: EntryState, completion: OnboardingProfileState) {
        mainThread()
        val profile = expected.profile ?: return
        if (expected.screen != EntryScreen.PROFILE || !routeCurrent(token, expected) ||
            !profile.isCurrentForNavigation || !profile.controller.isCurrentPreferencesCompletion(completion) ||
            privateDelivery != null) return
        val selected = owner ?: return
        val coordinator = expected.coordinator ?: return
        val ticket = Any(); operation = ticket
        val pending = EntryState(EntryScreen.WORKING, coordinator)
        mutable.value = pending
        fun current() = owns(ticket, selected) && attachments.owns(token) &&
            mutable.value === pending && selected.isCurrent(coordinator)
        scope.launch {
            try {
                requireEntry(current())
                val completed = value(EmailAccountOptionalLaterFlow.complete(coordinator, profile, completion, ::current))
                requireEntry(current() && completed.profile.isCurrentForNavigation &&
                    completed.profile.controller.isCurrentOptionalCompletion(completed.completion) &&
                    completed.completion.originalDecision?.prompt == OnboardingOptionalPrompt.EQUIPMENT &&
                    completed.completion.originalDecision?.choice == OnboardingDecisionChoice.SKIPPED)
                val next = EntryState(EntryScreen.OPTIONAL_CHECKPOINT, coordinator, completed.profile,
                    intent = EntryDecisionIntent(OnboardingOptionalPrompt.EQUIPMENT, OnboardingDecisionChoice.SKIPPED),
                    freshDecision = false)
                mutable.value = next
                if (operation === ticket) operation = null
                // This uses the same configured, verified delivery as the ordinary setup
                // flow. The final checkpoint ACK alone never grants private access.
                completePrivate(token, next, completed.completion)
            } catch (cancelled: CancellationException) {
                if (owns(ticket, selected)) mutable.value = EntryState(EntryScreen.RECOVERY, coordinator,
                    failure = FailureReason.OUTCOME_UNKNOWN)
                throw cancelled
            } catch (failure: Exception) {
                if (owns(ticket, selected)) mutable.value = if (selected.isCurrent(coordinator))
                    EntryState(EntryScreen.RECOVERY, coordinator,
                        failure = (failure as? EntryRouteFailure)?.reason ?: FailureReason.UNAVAILABLE)
                else EntryState(EntryScreen.CLOSE_REQUIRED, failure = FailureReason.STALE_SESSION)
            } finally {
                if (operation === ticket) operation = null
            }
        }
    }

    fun openDecision(token: Any, expected: EntryState, saved: OnboardingPreferencesState, choice: OnboardingDecisionChoice) {
        mainThread()
        val preferences = expected.preferences ?: return
        val prompt = expected.prompt ?: return
        val form = expected.form ?: return
        if (!form.isBoundTo(preferences.controller, prompt) || form.hasUnkeptChanges) return
        transition(token, expected) { coordinator ->
            requireEntry(preferences.isCurrentForNavigation && preferences.controller.isCurrentState(saved) && !form.hasUnkeptChanges)
            val result = value(coordinator.openDecision(preferences, saved, prompt, choice, coordinator.states.value))
            // The runtime has fenced the source before acquiring its new restricted child.
            form.clear()
            val profile = value(coordinator.takeProfile(result))
            EntryState(EntryScreen.OPTIONAL_CHECKPOINT, coordinator, profile,
                intent = EntryDecisionIntent(prompt, choice), freshDecision = true)
        }
    }

    fun continueCheckpoint(token: Any, expected: EntryState, completed: OnboardingProfileState) {
        mainThread()
        val profile = expected.profile ?: return
        val intent = expected.intent ?: return
        if (!routeCurrent(token, expected) || !profile.isCurrentForNavigation ||
            !profile.controller.isCurrentOptionalCompletion(completed) ||
            completed.originalDecision?.prompt != intent.prompt || completed.originalDecision?.choice != intent.choice) return
        when (entryCheckpointDestination(intent.prompt, completed.onboardingStep)) {
            EntryScreen.EQUIPMENT -> openPreferences(token, expected, completed)
            EntryScreen.SETUP_REMAINING -> completePrivate(token, expected, completed)
            else -> Unit
        }
    }

    /** Only the exact delivered equipment receipt enters here. A GET/saved ready step still
     * routes to SETUP_REMAINING. Missing client product inputs are refused BEFORE promotion. */
    private fun completePrivate(token: Any, expected: EntryState, completed: OnboardingProfileState) {
        if (!routeCurrent(token, expected) || privateDelivery != null) return
        val coordinator = expected.coordinator ?: return
        val profile = expected.profile ?: return
        val intent = expected.intent ?: return
        if (intent.prompt != OnboardingOptionalPrompt.EQUIPMENT || completed.onboardingStep != "ready" ||
            !profile.isCurrentForNavigation || !profile.controller.isCurrentOptionalCompletion(completed) ||
            completed.originalDecision?.prompt != intent.prompt || completed.originalDecision?.choice != intent.choice) return
        val product = configuration?.privateMeal
        if (product == null) {
            mutable.value = EntryState(EntryScreen.PRODUCT_REQUIRED, coordinator, profile,
                failure = FailureReason.NOT_CONFIGURED, intent = intent)
            return
        }
        val account = coordinator.states.value
        deliverPrivate(token, expected, product) {
            if (!profile.isCurrentForNavigation || !profile.controller.isCurrentOptionalCompletion(completed))
                PortResult.Failure(FailureReason.STALE_SESSION)
            else coordinator.completeReady(profile, completed, account)
        }
    }

    /** Actual coordinator operations share this exact retained delivery/cleanup
     * owner. Neither a route hint nor a recovered-state Boolean constructs private access. */
    private fun deliverPrivate(token: Any, expected: EntryState, product: PrivateMealConfiguration,
        acquireReady: suspend () -> PortResult<EmailAccountState>) {
        if (!routeCurrent(token, expected) || privateDelivery != null) return
        val selected = owner ?: return
        val coordinator = expected.coordinator ?: return
        val timers = cookingTimers ?: return
        val ticket = Any(); operation = ticket
        val pending = EntryState(EntryScreen.WORKING, coordinator)
        mutable.value = pending
        fun current() = owns(ticket, selected) && attachments.owns(token) && mutable.value === pending && selected.isCurrent(coordinator)
        var exactHandoff: EmailAccountPrivateHandoff? = null
        val delivery = EntryPrivateDelivery(object : EntryPrivateOperations<EmailAccountState, EmailAccountPrivateHandoff,
                EmailAccountPrivateConnection, EntryMealDestination> {
            override suspend fun complete(): PortResult<EmailAccountState> {
                if (!current()) return PortResult.Failure(FailureReason.STALE_SESSION)
                return when (val result = acquireReady()) {
                    is PortResult.Failure -> result
                    is PortResult.Value -> if (result.value.privateHandoffReady && result.value.failureReason == null)
                        result else PortResult.Failure(result.value.failureReason ?: FailureReason.CONFLICT)
                }
            }
            override suspend fun take(ready: EmailAccountState) = coordinator.takePrivate(ready).also {
                if (it is PortResult.Value) exactHandoff = it.value
            }
            override suspend fun handoffCurrent(handoff: EmailAccountPrivateHandoff) = handoff.isCurrentForNavigation()
            override suspend fun connect(handoff: EmailAccountPrivateHandoff) = selected.openPrivateConnection(coordinator, handoff)
            override suspend fun connectionCurrent(connection: EmailAccountPrivateConnection) = connection.isCurrentForNavigation()
            override fun currentNow(handoff: EmailAccountPrivateHandoff, connection: EmailAccountPrivateConnection) =
                current() && exactHandoff === handoff && handoff.access === connection.access &&
                    handoff.access.mode == PrivateSessionAccessMode.ONLINE &&
                    connection.boundary.isCurrent(handoff.access.lease)
            override fun create(connection: EmailAccountPrivateConnection): EntryMealDestination {
                val handoff = checkNotNull(exactHandoff)
                requireEntry(currentNow(handoff, connection))
                val publicationPolicy = com.feedme.mealflow.social.PostPublicationClientPolicy(1048576, 65536, 262144,
                    64, 512, 64, 4, 50, 256, 65536, 65536, 60000)
                val attachmentPolicy = com.feedme.mealflow.social.ReviewedAttachmentPolicy(12, 10, 262144, 60000)
                var reviewedIntegration: NativeReviewedKitchenIntegration? = null
                val experience = try { MealFlowExperience.fromSession(connection.access, connection.transport, connection.boundary,
                    Dispatchers.Main.immediate, clock, connectivity, MealOperationIds { UUID.randomUUID().toString() },
                    product.meals, product.ingredients, product.choices, product.kitchen, product.cooking, product.cookbook,
                    draftPolicy = com.feedme.mealflow.social.PostDraftClientPolicy(12, 1048576, 262144, 512, 512, 20, 200, 60000),
                    localPhotoPolicy = com.feedme.mealflow.social.LocalPhotoDraftPolicy(
                        com.feedme.mealflow.social.PostPublicationClientPolicy(1048576, 65536, 262144, 64, 512, 64,
                            4, 50, 256, 65536, 65536, 60000)),
                    circlesPolicy = com.feedme.mealflow.circles.CirclesReadPolicy(20, 10, 262144),
                    circleCreatePolicy = com.feedme.mealflow.circles.CircleCreatePolicy(524288, 65536, 60000),
                    circleEditPolicy = com.feedme.mealflow.circles.CircleEditPolicy(1048576, 32768, 60000),
                    circleLeavePolicy = com.feedme.mealflow.circles.CircleLeavePolicy(524288, 32768, 60000),
                    invitationPreview = configuration?.circleInvitationLinkBase?.let { linkBase ->
                        com.feedme.app.mealflow.CircleInvitationPreviewConfiguration(connection.invitationPreviewTransport,
                            com.feedme.mealflow.circles.CircleInvitationPreviewPolicy(setOf(linkBase), 32768, 60000))
                    },
                    circleInvitationPolicy = configuration?.circleInvitationLinkBase?.let {
                        com.feedme.mealflow.circles.CircleInvitationPolicy(524288, 32768, 60000)
                    },
                    circleIssuedInvitationsPolicy = configuration?.circleInvitationLinkBase?.let { linkBase ->
                        com.feedme.mealflow.circles.CircleIssuedInvitationsPolicy(4, 168, 4194304, 32768, 60000, linkBase)
                    },
                    reportsPolicy = com.feedme.mealflow.reports.ReportClientPolicy(65_536, 60_000L),
                    mealInterpretationEnabled = product.mealInterpretationEnabled,
                    socialReadPolicy = com.feedme.mealflow.social.SocialReadPolicy(20, 10, 262144,
                        notificationReadAcknowledgementsEnabled = product.notificationReadAcknowledgementsEnabled),
                    photoDeliveryPort = connection.photoDeliveryPort,
                    socialPhotoPolicy = connection.photoDeliveryPort?.let {
                        com.feedme.mealflow.social.SocialPhotoPolicy(12, 4_194_304, 16_777_216, 60_000)
                    },
                    blocksPolicy = com.feedme.mealflow.blocks.BlocksPolicy(20, 10, 262144, 60000),
                    circleMemberRemovalPolicy = com.feedme.mealflow.circles.CircleMemberRemovalPolicy(262144, 60000),
                    circleOwnershipTransferPolicy = com.feedme.mealflow.circles.CircleOwnershipTransferPolicy(262144, 60000),
                    photoUploadPort = connection.photoUploadPort,
                    memoryPolicy = com.feedme.mealflow.memory.MealMemoryPolicy(20, 10, 262144, 60000, reuseEnabled = true),
                    collectionPolicy = com.feedme.mealflow.collections.CollectionReadPolicy(12, 10, 262144),
                    conversationPolicy = com.feedme.mealflow.conversation.ThreadPolicy(12, 10, 262144, 60000, 60000),
                    postDeletionPolicy = com.feedme.mealflow.postdeletion.PostDeletionPolicy(262144, 60000, 60000),
                    postPlacementPolicy = com.feedme.mealflow.postplacement.PostPlacementPolicy(262144, 60000),
                    reactionPolicy = com.feedme.mealflow.reactions.ReactionPolicy(262144, 60000),
                    recipeRequestPolicy = com.feedme.mealflow.reciperequests.RecipeRequestPolicy(12, 10, 262144, 60000, 60000),
                    sessionControlsPolicy = com.feedme.mealflow.sessioncontrols.SessionControlsPolicy(12, 10, 262144, 60000, 60000),
                    notificationSettingsPolicy = com.feedme.mealflow.notifications.NotificationSettingsPolicy(262144, 60000, 60000),
                    remixTrailPolicy = com.feedme.mealflow.remix.RemixTrailPolicy(12, 10, 262144, 60000),
                    profileEditPolicy = com.feedme.mealflow.profile.AccountProfileEditPolicy(16384, 60000, 60000),
                    accountExportPolicy = if (product.accountExportEnabled) com.feedme.mealflow.exports.AccountExportPolicy() else null,
                    exportDeliveryPort = if (product.accountExportEnabled) connection.exportDeliveryPort else null,
                    reviewedPostsConfiguration = com.feedme.app.mealflow.ReviewedPostsConfiguration(publicationPolicy,
                        attachmentPolicy) { access -> NativeReviewedKitchenIntegration(connection, access, clock,
                            connectivity, attachmentPolicy).also { reviewedIntegration = it } })
                } catch (failure: Exception) { reviewedIntegration?.close(); throw failure }
                // Account-only reads/reporting reuse this actual private connection. Client limits
                // are not a backend activation; held services still refuse public requests.
                return EntryMealDestination(handoff, connection, experience, reviewedIntegration)
            }
            override suspend fun prepareProduct(product: EntryMealDestination): PortResult<Unit> =
                timers.attach(product.connection, product.experience, MealOperationIds { UUID.randomUUID().toString() })
            override suspend fun closeProduct(product: EntryMealDestination): PortResult<Unit> {
                product.reviewedIntegration?.close()
                timers.close()
                return product.experience.close()
            }
            override suspend fun closeConnection(connection: EmailAccountPrivateConnection) = connection.close()
            override suspend fun abandonHandoff(handoff: EmailAccountPrivateHandoff) = coordinator.abandonPrivate(handoff)
            override suspend fun abandonReady(ready: EmailAccountState) = coordinator.abandonReady(ready)
        }, ::current)
        privateDelivery = delivery // Retained before any claim/connection/factory suspension.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                var published: EntryState? = null
                val result = delivery.deliver { destination ->
                    val delivered = EntryState(EntryScreen.PRIVATE_MEAL, coordinator, meal = destination)
                    published = delivered
                    mutable.value = delivered
                }
                if (result is PortResult.Value) {
                    val delivered = published
                    val meal = delivered?.meal
                    val activation = if (delivered != null && meal != null && owns(ticket, selected) &&
                        attachments.owns(token) && mutable.value === delivered && selected.isCurrent(coordinator) &&
                        meal.handoff === exactHandoff && meal.connection.access === meal.handoff.access &&
                        meal.connection.boundary.isCurrent(meal.handoff.access.lease))
                        timers.activateForeground(meal.connection, meal.experience)
                    else PortResult.Failure(FailureReason.STALE_SESSION)
                    if (activation is PortResult.Failure) {
                        timers.close()
                        if (owns(ticket, selected) && mutable.value === delivered)
                            mutable.value = EntryState(EntryScreen.CLOSE_REQUIRED, failure = activation.reason)
                    }
                }
                if (result is PortResult.Failure && owns(ticket, selected))
                    mutable.value = EntryState(EntryScreen.CLOSE_REQUIRED, failure = result.reason)
            } catch (cancelled: CancellationException) {
                if (owns(ticket, selected)) mutable.value = EntryState(EntryScreen.CLOSE_REQUIRED, failure = FailureReason.OUTCOME_UNKNOWN)
                throw cancelled
            } finally {
                if (operation === ticket) operation = null
                if (privateDeliveryJob === currentCoroutineContext()[Job]) privateDeliveryJob = null
            }
        }
        privateDeliveryJob = job
        job.start()
    }

    /** Caller is the serialized native UI owner. The guarded transport still performs its
     * own exact coordinator/access checks for EVERY request; this is only a UI rejection gate. */
    fun privateRouteCurrent(token: Any, expected: EntryState): Boolean {
        mainThread()
        val meal = expected.meal ?: return false
        return expected.screen == EntryScreen.PRIVATE_MEAL && routeCurrent(token, expected) &&
            meal.connection.access === meal.handoff.access && meal.connection.boundary.isCurrent(meal.handoff.access.lease)
    }

    /** Opens a confirmation surface only. Back and ordinary connection close never sign out. */
    fun openDeviceSignOut(token: Any, expected: EntryState) {
        mainThread()
        if (!privateRouteCurrent(token, expected) || refreshMutable.value.busy) return
        val controller = expected.meal?.connection?.deviceSignOut ?: return
        cookingTimers?.pauseForAccountReview()
        val next = EntryState(EntryScreen.PRIVATE_SIGN_OUT, expected.coordinator, meal = expected.meal)
        signOutMutable.value = EntryDeviceSignOutState()
        mutable.value = next
        deviceSignOutAction(token, next, controller.states.value, EntryDeviceSignOutAction.PREPARE)
    }

    /** Once removal starts its own exact controller remains usable after the private lease
     * retires. This route check grants no private access and never opens the meal screen. */
    fun deviceSignOutRouteCurrent(token: Any, expected: EntryState): Boolean {
        mainThread()
        return entryDeviceSignOutRouteCurrent(attachments.owns(token), expected, mutable.value,
            expected.screen, owner != null, operation != null, expected.meal?.connection?.deviceSignOut != null)
    }

    fun deviceSignOutAction(token: Any, expected: EntryState, rendered: PrivateAccountSignOutState,
        action: EntryDeviceSignOutAction) {
        mainThread()
        if (!deviceSignOutRouteCurrent(token, expected)) return
        val selected = owner ?: return
        val meal = expected.meal ?: return
        val controller = meal.connection.deviceSignOut ?: return
        if (!entryDeviceSignOutActionAvailable(controller.states.value === rendered, signOutMutable.value.busy,
                rendered.phase, action)) return
        val ticket = Any(); operation = ticket
        val pending = EntryDeviceSignOutState(busy = true)
        signOutMutable.value = pending
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!owns(ticket, selected) || mutable.value !== expected) return@launch
                val result = when (action) {
                    EntryDeviceSignOutAction.PREPARE -> controller.prepare(rendered)
                    EntryDeviceSignOutAction.CANCEL -> controller.cancel(rendered)
                    EntryDeviceSignOutAction.CONFIRM -> controller.confirm(rendered)
                    EntryDeviceSignOutAction.RETRY -> controller.retry(rendered)
                }
                if (!owns(ticket, selected) || mutable.value !== expected) return@launch
                signOutMutable.value = EntryDeviceSignOutState(failure = (result as? PortResult.Failure)?.reason)
                // Only an acknowledged, pre-dispatch cancellation may return to cooking.
                if (action == EntryDeviceSignOutAction.CANCEL && result is PortResult.Value &&
                    controller.states.value.phase == PrivateAccountSignOutPhase.IDLE && attachments.owns(token) &&
                    meal.connection.isCurrentForNavigation() && owns(ticket, selected) &&
                    mutable.value === expected && attachments.owns(token)) {
                    mutable.value = EntryState(EntryScreen.PRIVATE_MEAL, expected.coordinator, meal = meal)
                    // Restore callbacks only after the exact route is visible again. Current
                    // Activity foreground ownership is still enforced by the timer gate.
                    operation = null
                    val resumed = cookingTimers?.resumeAfterAccountReview()
                    if (resumed is PortResult.Failure) failOpen(resumed.reason)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (owns(ticket, selected) && mutable.value === expected)
                    signOutMutable.value = EntryDeviceSignOutState(failure = FailureReason.OUTCOME_UNKNOWN)
            } finally {
                if (signOutMutable.value === pending)
                    signOutMutable.value = EntryDeviceSignOutState(failure = FailureReason.OUTCOME_UNKNOWN)
                if (operation === ticket) operation = null
                if (signOutJob === currentCoroutineContext()[Job]) signOutJob = null
            }
        }
        signOutJob = job; job.start()
    }

    fun closeDeviceSignOut(token: Any, expected: EntryState, rendered: PrivateAccountSignOutState) {
        mainThread()
        if (!deviceSignOutRouteCurrent(token, expected) || signOutMutable.value.busy ||
            expected.meal?.connection?.deviceSignOut?.states?.value !== rendered ||
            rendered.phase !in setOf(PrivateAccountSignOutPhase.IDLE, PrivateAccountSignOutPhase.REVIEW, PrivateAccountSignOutPhase.RECOVERY_REQUIRED,
                PrivateAccountSignOutPhase.COMPLETE)) return
        closeOwned() // Closing is not a cancellation or an acknowledgement of account removal.
    }

    /** Explicit rendered intent, never an expiry timer, mount effect or transport retry.
     * Local meal work stays owned while the runtime gates network credential delivery. */
    fun refreshAccount(token: Any, expected: EntryState, expectedRefresh: EntryAccountRefreshState) {
        mainThread()
        if (refreshMutable.value !== expectedRefresh || !entryAccountRefreshAvailable(
                privateRouteCurrent(token, expected), configuration?.accountRefreshConfigured == true,
                expectedRefresh.busy, expectedRefresh.phase)) return
        val connection = expected.meal?.connection ?: return
        val pending = EntryAccountRefreshState(busy = true)
        refreshMutable.value = pending
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // Offline UI retry has not entered provider identity verification or rotation.
                if (connectivity.current() != Connectivity.ONLINE) {
                    if (refreshMutable.value === pending && privateRouteCurrent(token, expected))
                        refreshMutable.value = EntryAccountRefreshState(phase = expectedRefresh.phase, failure = FailureReason.OFFLINE)
                    return@launch
                }
                if (!privateRouteCurrent(token, expected) || refreshMutable.value !== pending) return@launch
                val result = connection.refreshAccountCredentials()
                if (!privateRouteCurrent(token, expected) || refreshMutable.value !== pending) return@launch
                val observed = connection.inspectAccountCredentialRefresh()
                if (!privateRouteCurrent(token, expected) || refreshMutable.value !== pending) return@launch
                refreshMutable.value = EntryAccountRefreshState(
                    phase = (observed as? PortResult.Value)?.value?.phase
                        ?: AccountCredentialRefreshPhase.UNAVAILABLE,
                    failure = (result as? PortResult.Failure)?.reason)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (refreshMutable.value === pending) refreshMutable.value = EntryAccountRefreshState(
                    phase = AccountCredentialRefreshPhase.UNAVAILABLE, failure = FailureReason.OUTCOME_UNKNOWN)
            } finally {
                // A recreated attachment cannot reinterpret an undelivered rotation as unsent.
                if (refreshMutable.value === pending) refreshMutable.value = EntryAccountRefreshState(
                    phase = AccountCredentialRefreshPhase.ACKNOWLEDGEMENT_UNDELIVERED, failure = FailureReason.OUTCOME_UNKNOWN)
                if (refreshJob === currentCoroutineContext()[Job]) refreshJob = null
            }
        }
        refreshJob = job; job.start()
    }

    /** A route observation, never a receipt ACK or a private-runtime admission. This explicit
     * gesture is the only parent action that loads getMe; construction/recreation is inert. */
    fun loadSetupRoute(token: Any, expected: EntryState) {
        mainThread()
        val profile = expected.profile ?: return
        transition(token, expected) { coordinator ->
            requireEntry(profile.isCurrentForNavigation)
            val current = value(profile.controller.refresh(profile.controller.states.value))
            requireEntry(!current.recoveryRequired && (current.original == null || current.status in setOf(
                OnboardingProfileStatus.APPLIED, OnboardingProfileStatus.REJECTED, OnboardingProfileStatus.DISCARDED)))
            val destination = entryFreshStepDestination(profile.controller.isCurrentState(current), current.baselineFresh,
                current.originalDecision != null, current.onboardingStep) ?: throw EntryRouteFailure(FailureReason.CONFLICT)
            when (destination) {
                EntryScreen.FOOD_PREFERENCES, EntryScreen.EQUIPMENT -> {
                    val prompt = if (destination == EntryScreen.FOOD_PREFERENCES) OnboardingOptionalPrompt.FOOD_PREFERENCES else OnboardingOptionalPrompt.EQUIPMENT
                    val restored = value(coordinator.restorePreferences(coordinator.states.value))
                    preferencesRoute(coordinator, value(coordinator.takePreferences(restored)), prompt)
                }
                EntryScreen.SETUP_REMAINING -> EntryState(destination, coordinator)
                EntryScreen.PROFILE -> EntryState(destination, coordinator, profile)
                else -> throw EntryRouteFailure(FailureReason.CONFLICT)
            }
        }
    }

    /** Local-only explicit reopen, with retained optional originals routed by their actual
     * safe summary. A step alone never invents an answered/skipped decision. */
    fun recoverProfileRoute(token: Any, expected: EntryState) = transition(token, expected) { coordinator ->
        val restored = value(coordinator.restoreOptionalDecision(coordinator.states.value))
        profileRoute(coordinator, value(coordinator.takeProfile(restored)))
    }

    /** Repair the preference sibling before opening profile if its pending bytes block that
     * sibling. No HTTP: an uncertain original remains retained for its separate review/retry. */
    fun recoverPreferenceWork(token: Any, expected: EntryState) = transition(token, expected) { coordinator ->
        val restored = value(coordinator.restorePreferences(coordinator.states.value))
        val preferences = value(coordinator.takePreferences(restored))
        value(preferences.controller.recover(preferences.controller.states.value))
        EntryState(EntryScreen.RECOVERY, coordinator)
    }

    private fun profileRoute(coordinator: EmailAccountCoordinator, handoff: EmailAccountProfileHandoff): EntryState {
        requireEntry(handoff.isCurrentForNavigation)
        val state = handoff.controller.states.value
        val decision = state.originalDecision
        val destination = entryRestoredProfileDestination(decision != null, state.recoveryRequired,
            state.original != null && state.status !in setOf(OnboardingProfileStatus.APPLIED,
                OnboardingProfileStatus.REJECTED, OnboardingProfileStatus.DISCARDED), state.onboardingStep)
        return EntryState(destination, coordinator, handoff,
            intent = decision?.let { EntryDecisionIntent(it.prompt, it.choice) })
    }

    private fun preferencesRoute(coordinator: EmailAccountCoordinator, handoff: EmailAccountPreferencesHandoff,
        prompt: OnboardingOptionalPrompt): EntryState {
        requireEntry(handoff.isCurrentForNavigation)
        return EntryState(if (prompt == OnboardingOptionalPrompt.FOOD_PREFERENCES) EntryScreen.FOOD_PREFERENCES else EntryScreen.EQUIPMENT,
            coordinator, preferences = handoff, prompt = prompt, form = OnboardingPreferencesFormMemory(handoff.controller, prompt))
    }

    private fun routeCurrent(token: Any, expected: EntryState): Boolean = entryRouteCallbackCurrent(
        attachments.owns(token), expected, mutable.value, operation != null, expected.coordinator?.let { owner?.isCurrent(it) } == true)

    private fun transition(token: Any, expected: EntryState, work: suspend (EmailAccountCoordinator) -> EntryState) {
        mainThread()
        if (!routeCurrent(token, expected)) return
        val selected = owner ?: return
        val coordinator = expected.coordinator ?: return
        val ticket = Any(); operation = ticket
        val pending = EntryState(EntryScreen.WORKING, coordinator)
        mutable.value = pending
        scope.launch {
            try {
                requireEntry(owns(ticket, selected) && attachments.owns(token) && mutable.value === pending)
                val next = work(coordinator)
                if (!owns(ticket, selected)) return@launch
                if (!attachments.owns(token) || mutable.value !== pending) {
                    mutable.value = EntryState(EntryScreen.RECOVERY, coordinator, failure = FailureReason.STALE_SESSION)
                } else if (!selected.isCurrent(coordinator)) failOpen(FailureReason.STALE_SESSION)
                else mutable.value = next
            } catch (cancelled: CancellationException) {
                if (owns(ticket, selected)) mutable.value = EntryState(EntryScreen.RECOVERY, coordinator, failure = FailureReason.OUTCOME_UNKNOWN)
                throw cancelled
            } catch (failure: Exception) {
                if (owns(ticket, selected)) mutable.value = if (selected.isCurrent(coordinator))
                    EntryState(EntryScreen.RECOVERY, coordinator, failure = (failure as? EntryRouteFailure)?.reason ?: FailureReason.UNAVAILABLE)
                else EntryState(EntryScreen.CLOSE_REQUIRED, failure = FailureReason.STALE_SESSION)
            } finally {
                // This memory is private RAM, not the durable draft. Drop it only after the
                // source was actually fenced; Activity recreation alone never clears it.
                expected.preferences?.let { if (!it.controller.isCurrentState(it.controller.states.value)) expected.form?.clear() }
                if (operation === ticket) operation = null
            }
        }
    }

    private fun requireEntry(condition: Boolean) { if (!condition) throw EntryRouteFailure(FailureReason.STALE_SESSION) }
    private fun <T> value(result: PortResult<T>): T = when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> throw EntryRouteFailure(result.reason)
    }

    fun back(token: Any, expected: EntryState) {
        mainThread()
        if (!attachments.owns(token) || mutable.value !== expected) return
        // Shared Back may already have closed the child; identity, not child admission, owns cleanup.
        closeOwned()
    }

    private fun closeOwned() {
        val selected = owner ?: return
        if (mutable.value.screen == EntryScreen.CLOSING) return
        cookingTimers?.close()
        passwordRecoveryRoute = null
        passwordRecoveryOwner?.close(); passwordRecoveryOwner = null
        deletionRoute = null; accountSettingsReturn = null; supportReturn = null; notificationsReturnToMeal = false
        deletionHandle?.invalidate()
        selected.retire()
        val pendingPrivate = privateDeliveryJob
        val product = privateDelivery
        product?.retire()
        pendingPrivate?.cancel()
        val pendingRefresh = refreshJob
        pendingRefresh?.cancel()
        val pendingSignOut = signOutJob
        pendingSignOut?.cancel()
        val pendingDeletion = deletionJob
        pendingDeletion?.cancel()
        mutable.value.form?.clear()
        val ticket = Any(); operation = ticket
        mutable.value = EntryState(EntryScreen.CLOSING) // Remove references before awaiting cleanup.
        scope.launch {
            val result = try {
                // Join exact acquisition cleanup before dependent close. A lost old callback
                // cannot create a client after this native owner has been released.
                pendingPrivate?.join()
                pendingRefresh?.join()
                pendingSignOut?.join()
                pendingDeletion?.join()
                val dependent = product?.closeBeforeOwner() ?: PortResult.Value(Unit)
                if (dependent is PortResult.Failure) dependent else selected.close()
            } catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
            if (!owns(ticket, selected)) return@launch
            when (result) {
                is PortResult.Value -> {
                    owner = null; privateDelivery = null; privateDeliveryJob = null; refreshJob = null; signOutJob = null
                    deletionHandle = null; deletionJob = null
                    cookingTimers = null
                    signOutMutable.value = EntryDeviceSignOutState()
                    refreshMutable.value = EntryAccountRefreshState(); mutable.value = EntryState(EntryScreen.WELCOME)
                }
                is PortResult.Failure -> mutable.value = EntryState(EntryScreen.CLOSE_REQUIRED, failure = result.reason)
            }
            operation = null
        }
    }

    private fun owns(ticket: Any, selected: AndroidEmailAccountOwner) = operation === ticket && owner === selected
    private fun failOpen(reason: FailureReason) {
        mutable.value = EntryState(EntryScreen.CLOSE_REQUIRED, failure = reason)
    }
    private fun mainThread() { check(Looper.myLooper() == Looper.getMainLooper()) }
    internal fun quiescentForTests(): Boolean {
        check(BuildConfig.DEBUG); mainThread()
        return owner == null && operation == null && attachments.states.value == null &&
            mutable.value.screen == EntryScreen.WELCOME &&
            scope.coroutineContext[Job]?.children?.none { it.isActive } != false
    }
    internal fun disposeTestComposition() {
        check(testComposition != null && quiescentForTests())
        scope.cancel()
    }
}

/** One bounded, inert public-client configuration loader shared by account and guest entry.
 * Missing/malformed bytes remain unconfigured; raw values never enter diagnostics or UI. */
internal fun loadFeedMeConfiguration(application: Application): AccountConfiguration? = try {
    application.assets.open("feedme-config.json").use { input ->
        val bytes = readAccountConfiguration(input)
        try { AccountConfiguration.parse(bytes, BuildConfig.VERSION_NAME) } finally { bytes.fill(0) }
    }
} catch (_: FileNotFoundException) { null }
catch (_: Exception) { null }

private class EntryRouteFailure(val reason: FailureReason) : RuntimeException("Restricted entry action refused")
