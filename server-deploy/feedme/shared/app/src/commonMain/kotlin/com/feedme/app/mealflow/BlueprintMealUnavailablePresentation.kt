package com.feedme.app.mealflow

import androidx.compose.runtime.*
import com.feedme.app.blueprint.*
import com.feedme.core.ports.FailureReason
import com.feedme.mealflow.MealFlowIssue
import com.feedme.mealflow.MealFlowPhase
import com.feedme.mealflow.MealFlowScreen

/** Local-only departure after native account authority is lost. The native callback owns
 * exact route/attachment validation and cleanup; no server permission is revived here. */
@Composable
internal fun BlueprintRetiredMealScreen(isRetired: () -> Boolean, onExit: () -> Unit,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit) {
    val latestRetired by rememberUpdatedState(isRetired)
    var attached by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { attached = false } }
    fun leave() { if (attached && latestRetired()) onExit() }
    val page = BlueprintUnavailableState(enabled = attached, canLogin = true,
        status = "This account view is no longer available. Go back to sign in. Saved recovery commands remain protected.")
    platformBackHandler(attached, ::leave)
    BlueprintUnavailableScreen(page, onAction = { expected, action ->
        if (expected === page && action == BlueprintUnavailableAction.LOG_IN_AGAIN) leave()
    },
        onBack = { expected -> if (expected === page) leave() }, onNavigate = { _, _ -> })
}

/** Original system screens for definitive failures with no retained meal/recovery to hide.
 * This is presentation only: it never probes connectivity, submits a replacement request,
 * or treats the existence of a Saved/cooking callback as evidence of downloaded content. */
@Composable
internal fun BlueprintMealUnavailableScreen(
    meal: MealScreenState,
    form: MealFormState,
    actions: MealScreenActions,
): Boolean {
    val kind = mealSystemFailure(meal, form, actions) ?: return false
    val recovery = actions.offlineRecovery
    val latestRecovery by rememberUpdatedState(recovery)
    val liveCurrent by rememberUpdatedState(actions.blueprintCurrent)
    var attached by remember(meal, form) { mutableStateOf(true) }
    DisposableEffect(meal, form) { onDispose { attached = false } }
    fun presentationCurrent() = attached
    fun current() = presentationCurrent() && liveCurrent() && actions.blueprintCurrent()

    val failure = meal.failureReason ?: form.failure
    val accessUnavailable = meal.phase == MealFlowPhase.UNAVAILABLE ||
        meal.issue == MealFlowIssue.SESSION_UNAVAILABLE || form.values == null ||
        failure in setOf(FailureReason.UNAUTHENTICATED, FailureReason.STALE_SESSION, FailureReason.FORBIDDEN)
    // Each real destination performs its own admission. A tab is not evidence of a
    // connection, downloaded content or permission to display a private feed.
    val navigation = blueprintMealTabDestinations(form, actions, available = !accessUnavailable)
    fun navigate(screen: BlueprintScreenId) {
        if (current() && latestRecovery?.busy != true) dispatchBlueprintMealTab(screen, navigation, actions)
    }

    when (kind) {
        MealSystemFailure.OFFLINE -> {
            val page = blueprintOfflineRecoveryPresentation(recovery, enabled = current(), navigation = navigation)
            BlueprintOfflineScreen(page,
                onAction = { rendered, action ->
                    if (rendered === page) dispatchBlueprintOfflineRecovery(page, action, recovery,
                        current = { current() && latestRecovery?.busy != true }, continuation = ::presentationCurrent)
                },
                onBack = { rendered -> if (rendered === page && current()) actions.back() },
                onNavigate = { rendered, screen -> if (rendered === page) navigate(screen) })
        }
        MealSystemFailure.UNAVAILABLE -> {
            val page = BlueprintUnavailableState(
                enabled = current(),
                // On this exact route, Back is the existing backToDraft owner. It does not
                // request an alternative, choose a recipe or replace a retained command.
                canChooseMeal = !accessUnavailable && meal.screen == MealFlowScreen.RECOMMENDATIONS,
                status = when {
                    accessUnavailable -> "This meal view isn't available. Go back to your account."
                    failure == FailureReason.NOT_CONFIGURED -> "This feature isn't connected yet. Go back to your kitchen."
                    else -> "This meal isn't available right now. Go back to choose what to do next."
                },
                navigation = navigation,
            )
            BlueprintUnavailableScreen(page,
                onAction = { rendered, action ->
                    if (rendered === page && current() && page.allows(action) &&
                        action == BlueprintUnavailableAction.CHOOSE_ANOTHER_MEAL) actions.back()
                },
                onBack = { rendered -> if (rendered === page && current()) actions.back() },
                onNavigate = { rendered, screen -> if (rendered === page) navigate(screen) })
        }
    }
    return true
}

/** HOME.08 is an explicit local visit, not a fabricated meal error. Its original Offline
 * layout shares the checked recovery ports while the exact Home draft stays untouched. */
@Composable
internal fun BlueprintHomeOfflineRecoveryScreen(visit: BlueprintMealLandingVisit,
    meal: MealScreenState, form: MealFormState, actions: MealScreenActions, onBack: () -> Unit) {
    val recovery = actions.offlineRecovery
    val latestRecovery by rememberUpdatedState(recovery)
    val liveCurrent by rememberUpdatedState(actions.blueprintCurrent)
    var attached by remember(visit, meal, form) { mutableStateOf(true) }
    DisposableEffect(visit, meal, form) { onDispose { attached = false } }
    // Busy/status descriptor changes are not departures. The root consumes this raw
    // lifetime alongside the exact account, Home visit and real controller snapshots.
    fun presentationCurrent() = attached && visit.active && visit.home && visit.offline
    fun current() = presentationCurrent() && liveCurrent() && actions.blueprintCurrent() &&
        blueprintMealLandingEligible(meal, form) && meal.phase == MealFlowPhase.OFFLINE_DRAFT
    val navigation = buildSet {
        add(BlueprintScreenId.HOME)
        if (recovery?.busy != true && recovery?.savedMealsAvailable == true && recovery.openSaved != null)
            add(BlueprintScreenId.COOKBOOK)
    }
    val page = blueprintOfflineRecoveryPresentation(recovery, enabled = current()).copy(navigation = navigation)
    fun dispatch(action: BlueprintOfflineAction) {
        dispatchBlueprintOfflineRecovery(page, action, recovery,
            current = { current() && latestRecovery?.busy != true }, continuation = ::presentationCurrent)
    }
    BlueprintOfflineScreen(page,
        onAction = { rendered, action -> if (rendered === page) dispatch(action) },
        onBack = { rendered -> if (rendered === page && current()) onBack() },
        onNavigate = { rendered, destination ->
            if (rendered === page && current() && destination in page.navigation) when (destination) {
                BlueprintScreenId.HOME -> onBack()
                BlueprintScreenId.COOKBOOK -> dispatch(BlueprintOfflineAction.OPEN_SAVED_MEALS)
                else -> Unit
            }
        })
}

/** Checked owner observations and ports are both necessary. No inspection, connectivity
 * probe or mutation occurs while projecting the original three recovery buttons. */
internal fun blueprintOfflineRecoveryPresentation(recovery: MealOfflineRecovery?, enabled: Boolean = false,
    navigation: Set<BlueprintScreenId> = emptySet()): BlueprintOfflineState {
    val ready = recovery?.busy != true
    return BlueprintOfflineState(
        savedMealsAvailable = recovery?.savedMealsAvailable?.let { it && ready && recovery.openSaved != null },
        cookingSessionAvailable = recovery?.cookingSessionAvailable?.let { it && ready && recovery.resumeCooking != null },
        canRetryConnection = ready && recovery?.retryConnection != null,
        // Keep local Back available during a check; actions and destination tabs are
        // independently disabled until that exact recovery observation settles.
        enabled = enabled,
        status = recovery?.status ?: "You're offline. Downloaded meals haven't been checked yet. Back keeps your draft.",
        navigation = navigation.takeIf { ready }.orEmpty(),
    )
}

/** Raw local lifetime survives only until this render is replaced/disposed. The root
 * consumes it with its exact account/owner snapshots before intentional navigation. */
internal fun dispatchBlueprintOfflineRecovery(page: BlueprintOfflineState, action: BlueprintOfflineAction,
    recovery: MealOfflineRecovery?, current: () -> Boolean, continuation: () -> Boolean): Boolean {
    if (recovery == null || recovery.busy || !current() || !continuation() || !page.allows(action)) return false
    val callback = when (action) {
        BlueprintOfflineAction.OPEN_SAVED_MEALS -> recovery.openSaved.takeIf { recovery.savedMealsAvailable == true }
        BlueprintOfflineAction.RESUME_COOKING -> recovery.resumeCooking.takeIf { recovery.cookingSessionAvailable == true }
        BlueprintOfflineAction.RETRY_CONNECTION -> recovery.retryConnection
    } ?: return false
    callback(continuation)
    return true
}

private enum class MealSystemFailure { OFFLINE, UNAVAILABLE }

private fun mealSystemFailure(
    meal: MealScreenState,
    form: MealFormState,
    actions: MealScreenActions,
): MealSystemFailure? {
    // Catalog/source/adaptation screens have their own retained inputs and recovery owners.
    if (meal.screen !in setOf(MealFlowScreen.REQUEST, MealFlowScreen.RECOMMENDATIONS, MealFlowScreen.RECIPE) ||
        meal.phase !in setOf(MealFlowPhase.ERROR, MealFlowPhase.UNAVAILABLE) || form.busy || form.dirty) return null
    val recovery = actions.offlineRecovery
    val resumableOffline = meal.phase == MealFlowPhase.ERROR && (meal.failureReason ?: form.failure) == FailureReason.OFFLINE &&
        recovery?.cookingSessionAvailable == true && recovery.resumeCooking != null
    if (actions.retainedCooking != null && !resumableOffline) return null
    if (meal.plan != null || meal.rootSource != null || meal.catalog != null || meal.proposal != null ||
        meal.adaptation != null || meal.rootProposal != null || meal.pendingAdaptation != null ||
        meal.pendingRootDraft != null || meal.previousAvailable || meal.alternativesAvailable ||
        meal.pendingMatchesDraft || meal.retryAtMillis != null) return null
    val values = form.values
    if (values?.savedRecipeId != null || values?.sourceRecipeVersionId != null || values?.sourcePostId != null || values?.savedMakeMine == true) return null
    val interpretation = actions.interpretation
    if (interpretation?.busy == true || interpretation?.proposal != null || !interpretation?.text.isNullOrBlank()) return null
    // Preserve expired originals, request reconciliation, context repair and local storage
    // failures in the existing owner UI. STORAGE can also be the controller's generic
    // bucket for a definitive denial/offline read, so the actual reason is checked below.
    if (meal.issue !in setOf(MealFlowIssue.NONE, MealFlowIssue.STORAGE,
            MealFlowIssue.INVALID_REPLY, MealFlowIssue.SESSION_UNAVAILABLE)) return null
    if (meal.failureReason != null && form.failure != null && meal.failureReason != form.failure) return null
    val failure = meal.failureReason ?: form.failure
    if (failure in setOf(FailureReason.OUTCOME_UNKNOWN, FailureReason.STORAGE_FAILURE,
            FailureReason.CONFLICT, FailureReason.RATE_LIMITED, FailureReason.INVALID_DATA)) return null
    if (meal.phase == MealFlowPhase.ERROR && failure == FailureReason.OFFLINE &&
        meal.issue != MealFlowIssue.SESSION_UNAVAILABLE) return MealSystemFailure.OFFLINE
    if (failure == FailureReason.OFFLINE) return null
    if (meal.phase == MealFlowPhase.UNAVAILABLE || meal.issue == MealFlowIssue.SESSION_UNAVAILABLE ||
        failure in setOf(FailureReason.NOT_CONFIGURED, FailureReason.UNAUTHENTICATED, FailureReason.FORBIDDEN,
            FailureReason.NOT_FOUND, FailureReason.UNAVAILABLE, FailureReason.STALE_SESSION))
        return MealSystemFailure.UNAVAILABLE
    return null
}
