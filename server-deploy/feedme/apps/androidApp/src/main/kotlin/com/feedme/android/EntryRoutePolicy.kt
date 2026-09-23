package com.feedme.android

import com.feedme.session.AccountCredentialRefreshPhase

import com.feedme.session.OnboardingOptionalPrompt
import com.feedme.session.AndroidEmailAccountPhase
import com.feedme.session.EmailAccountScreen
import com.feedme.session.AndroidSavedAccountKind

/** Routing only. Actual owner/getMe/native acknowledgement checks remain at each call site. */
internal fun entryRouteCallbackCurrent(attached: Boolean, expected: Any, current: Any,
    busy: Boolean, ownerCurrent: Boolean) = attached && expected === current && !busy && ownerCurrent

/** Local owner recovery chooses an inert surface, not an authorization or a private route. */
internal fun entryAccountStartDestination(phase: AndroidEmailAccountPhase): EntryScreen? = when (phase) {
    AndroidEmailAccountPhase.AUTH_OR_RECOVERY -> EntryScreen.EMAIL
    AndroidEmailAccountPhase.RECOVERY_ONLY -> EntryScreen.ACCOUNT_RECOVERY
    else -> null
}

/** UI rejection only; the actual coordinator and current-token runtime repeat all proof. */
internal fun entryReturningRecoveryAvailable(routeCurrent: Boolean, route: EntryScreen,
    exactAccountCurrent: Boolean, accountScreen: EmailAccountScreen, busy: Boolean,
    privateHandoffReady: Boolean): Boolean = routeCurrent && route == EntryScreen.ACCOUNT_RECOVERY &&
    exactAccountCurrent && !busy && !privateHandoffReady &&
    accountScreen in setOf(EmailAccountScreen.LOGIN, EmailAccountScreen.BOOTSTRAP_RECOVERY)

/** A locally prepared V8 source reaches only its existing explicit check/Terms UI. */
internal fun entrySavedAccountDestination(kind: AndroidSavedAccountKind, exactAccountCurrent: Boolean,
    accountScreen: EmailAccountScreen, busy: Boolean, privateHandoffReady: Boolean): EntryScreen? {
    if (!exactAccountCurrent || busy || privateHandoffReady) return null
    return when (kind) {
        AndroidSavedAccountKind.ONBOARDING_PRIVATE -> if (accountScreen in setOf(
            EmailAccountScreen.LOGIN, EmailAccountScreen.BOOTSTRAP_RECOVERY)) EntryScreen.ACCOUNT_RECOVERY else null
        AndroidSavedAccountKind.READY_PRIVATE -> if (accountScreen == EmailAccountScreen.READY_RECOVERY)
            EntryScreen.EMAIL else null
    }
}

/** Losing the Activity attachment must not hide a genuine retained recovery source. Only
 * the explicit existing check is exposed; failure never starts recovery or private access. */
internal fun entrySavedAccountFailureDestination(ownerCurrent: Boolean,
    accountScreen: EmailAccountScreen): EntryScreen = when {
    !ownerCurrent -> EntryScreen.CLOSE_REQUIRED
    accountScreen == EmailAccountScreen.READY_RECOVERY -> EntryScreen.EMAIL
    else -> EntryScreen.ACCOUNT_RECOVERY
}

/** An explicit click on the exact delivered bootstrap-ready rendering, never a route hint. */
internal fun entryBootstrapReadyAvailable(routeCurrent: Boolean, route: EntryScreen,
    exactAccountCurrent: Boolean, accountScreen: EmailAccountScreen, busy: Boolean,
    privateHandoffReady: Boolean): Boolean = routeCurrent && route == EntryScreen.EMAIL &&
    exactAccountCurrent && accountScreen == EmailAccountScreen.PRIVATE_READY && !busy && privateHandoffReady

internal fun entryAccountRefreshAvailable(routeCurrent: Boolean, configured: Boolean, busy: Boolean,
    phase: AccountCredentialRefreshPhase?): Boolean = routeCurrent && configured && !busy &&
    (phase == null || phase in setOf(AccountCredentialRefreshPhase.READY, AccountCredentialRefreshPhase.ACKNOWLEDGED,
        AccountCredentialRefreshPhase.NOT_DISPATCHED, AccountCredentialRefreshPhase.CANCELLED_BEFORE_EXCHANGE))

internal fun entryAcknowledgedPrompt(profileCompletion: Boolean, optionalCompletion: Boolean,
    step: String?): OnboardingOptionalPrompt? = when {
    profileCompletion && step == "preferences" -> OnboardingOptionalPrompt.FOOD_PREFERENCES
    optionalCompletion && step == "equipment" -> OnboardingOptionalPrompt.EQUIPMENT
    else -> null
}

internal fun entryCheckpointDestination(prompt: OnboardingOptionalPrompt, step: String?): EntryScreen? = when {
    prompt == OnboardingOptionalPrompt.FOOD_PREFERENCES && step == "equipment" -> EntryScreen.EQUIPMENT
    prompt == OnboardingOptionalPrompt.EQUIPMENT && step == "ready" -> EntryScreen.SETUP_REMAINING
    else -> null
}

/** Local unresolved work stays at its actual recovery surface before any fresh route load. */
internal fun entryRestoredProfileDestination(optionalOriginal: Boolean, recoveryRequired: Boolean,
    unresolvedOriginal: Boolean, step: String?): EntryScreen = when {
    optionalOriginal -> EntryScreen.OPTIONAL_CHECKPOINT
    recoveryRequired || unresolvedOriginal -> EntryScreen.PROFILE
    step in setOf("preferences", "equipment", "ready") -> EntryScreen.ROUTE_SELECTION
    else -> EntryScreen.PROFILE
}

/** Requires a just-delivered exact current getMe observation; never interprets a saved step
 * as a fresh observation or reinterprets an existing optional original as a new choice. */
internal fun entryFreshStepDestination(current: Boolean, fresh: Boolean, optionalOriginal: Boolean,
    step: String?): EntryScreen? = if (!current || !fresh || optionalOriginal) null else when (step) {
    "profile" -> EntryScreen.PROFILE
    "preferences" -> EntryScreen.FOOD_PREFERENCES
    "equipment" -> EntryScreen.EQUIPMENT
    "ready" -> EntryScreen.SETUP_REMAINING
    else -> null
}
