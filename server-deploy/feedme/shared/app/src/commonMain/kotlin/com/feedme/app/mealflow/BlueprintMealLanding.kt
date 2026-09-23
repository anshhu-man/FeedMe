package com.feedme.app.mealflow

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feedme.app.AccountSettingsHostAction
import com.feedme.app.FeedMeTheme
import com.feedme.app.blueprint.BlueprintDiscoveryAction
import com.feedme.app.blueprint.BlueprintDiscoveryControls
import com.feedme.app.blueprint.BlueprintHomeScreen
import com.feedme.app.blueprint.BlueprintHomeState
import com.feedme.app.blueprint.BlueprintScreenId
import com.feedme.mealflow.MealFlowIssue
import com.feedme.mealflow.MealFlowPhase
import com.feedme.mealflow.MealFlowScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local retained navigation only. Neither this token nor its route grants account access. */
internal class BlueprintMealLandingVisit internal constructor(val home: Boolean, val active: Boolean,
    val offline: Boolean = false) {
    override fun toString() = "BlueprintMealLandingVisit(home=$home, active=$active, offline=$offline)"
}

/** One experience owns this across Activity attachments. Each departure consumes the exact
 * rendered token, including a child-screen visit that later returns to the same Home. */
internal class BlueprintMealLandingOwner {
    private val mutable = MutableStateFlow(BlueprintMealLandingVisit(home = true, active = true))
    val states: StateFlow<BlueprintMealLandingVisit> = mutable.asStateFlow()

    fun current(expected: BlueprintMealLandingVisit, hostCurrent: Boolean): Boolean =
        hostCurrent && expected.active && mutable.value === expected

    fun showRequest(expected: BlueprintMealLandingVisit, hostCurrent: Boolean): Boolean =
        expected.home && move(expected, hostCurrent, home = false)

    fun showHome(expected: BlueprintMealLandingVisit, hostCurrent: Boolean): Boolean =
        (!expected.home || expected.offline) && move(expected, hostCurrent, home = true)

    fun openOffline(expected: BlueprintMealLandingVisit, hostCurrent: Boolean): Boolean =
        expected.home && !expected.offline && move(expected, hostCurrent, home = true, offline = true)

    fun closeOffline(expected: BlueprintMealLandingVisit, hostCurrent: Boolean): Boolean =
        expected.home && expected.offline && move(expected, hostCurrent, home = true)

    /** Claim an outgoing real route without changing which landing to return to. */
    fun claim(expected: BlueprintMealLandingVisit, hostCurrent: Boolean): Boolean =
        move(expected, hostCurrent, expected.home, expected.offline)

    fun invalidate() {
        mutable.value = BlueprintMealLandingVisit(home = false, active = false)
    }

    private fun move(expected: BlueprintMealLandingVisit, hostCurrent: Boolean, home: Boolean,
        offline: Boolean = false): Boolean = current(expected, hostCurrent) &&
        mutable.compareAndSet(expected, BlueprintMealLandingVisit(home, active = true, offline = offline))
}

/** Never cover an error, original command, source-specific request or comparison with Home.
 * Ordinary dirty text is deliberately allowed: local navigation neither saves nor discards it. */
internal fun blueprintMealLandingEligible(meal: MealScreenState, form: MealFormState): Boolean {
    val values = form.values ?: return false
    return meal.screen == MealFlowScreen.REQUEST &&
        meal.phase in setOf(MealFlowPhase.EDITING, MealFlowPhase.OFFLINE_DRAFT) &&
        meal.issue == MealFlowIssue.NONE && meal.failureReason == null &&
        !meal.pendingMatchesDraft && meal.retryAtMillis == null &&
        meal.proposal == null && meal.adaptation == null && meal.pendingAdaptation == null &&
        meal.rootSource == null && meal.rootProposal == null && meal.pendingRootDraft == null &&
        !form.busy && form.failure == null && values.savedRecipeId == null &&
        values.sourceRecipeVersionId == null && values.sourcePostId == null && values.sourcePostVersion == null && !values.savedMakeMine
}

internal fun blueprintMealLandingPresentation(meal: MealScreenState, form: MealFormState,
    actions: MealScreenActions, enabled: Boolean = false, accountSettingsAvailable: Boolean = false,
    socialNavigationAvailable: Boolean = false): BlueprintHomeState {
    val available = enabled && blueprintMealLandingEligible(meal, form)
    val allowed = if (!available) emptySet() else buildSet {
        add(BlueprintDiscoveryAction.HOME_REQUEST)
        if (actions.kitchen != null) add(BlueprintDiscoveryAction.HOME_PANTRY)
        if (socialNavigationAvailable) add(BlueprintDiscoveryAction.HOME_TODAY)
        if (actions.cookbook != null) add(BlueprintDiscoveryAction.HOME_SAVES)
        if (actions.retainedCooking != null) add(BlueprintDiscoveryAction.HOME_RESUME)
        if (meal.phase == MealFlowPhase.OFFLINE_DRAFT && actions.openOfflineRecovery != null)
            add(BlueprintDiscoveryAction.HOME_CONNECTION)
    }
    return BlueprintHomeState(
        // No inferred saved recommendations, catalog reads or private artwork on entry.
        resumableCookingLabel = if (BlueprintDiscoveryAction.HOME_RESUME in allowed) "Open your retained cooking session." else null,
        offline = meal.phase == MealFlowPhase.OFFLINE_DRAFT,
        controls = BlueprintDiscoveryControls(enabled = available, allowedActions = allowed, allowMore = available && accountSettingsAvailable,
            message = "Open All saves to load meals saved on this device.",
            allowedNavigation = if (!available) emptySet() else buildSet {
                add(BlueprintScreenId.HOME)
                if (actions.cookbook != null) add(BlueprintScreenId.COOKBOOK)
                if (socialNavigationAvailable) {
                    add(BlueprintScreenId.TODAY); add(BlueprintScreenId.INBOX); add(BlueprintScreenId.PROFILE_PLATE)
                }
            }),
    )
}

/** Shared by the real composable and protocol tests; ports still perform their own admission. */
internal fun blueprintMealLandingAction(owner: BlueprintMealLandingOwner, visit: BlueprintMealLandingVisit,
    meal: MealScreenState, form: MealFormState, actions: MealScreenActions, action: BlueprintDiscoveryAction): Boolean {
    if (!visit.home || visit.offline || !blueprintMealLandingEligible(meal, form)) return false
    val presentation = blueprintMealLandingPresentation(meal, form, actions, enabled = true)
    if (!presentation.controls.permits(BlueprintScreenId.HOME, action, hasMeal = false)) return false
    // The real host opens the explicit Offline visit. Do not pre-consume its exact Home
    // snapshot or save dirty input merely to inspect local recovery availability.
    if (action == BlueprintDiscoveryAction.HOME_CONNECTION) {
        if (!owner.current(visit, actions.blueprintCurrent())) return false
        val callback = actions.openOfflineRecovery ?: return false
        callback()
        return true
    }
    if (action == BlueprintDiscoveryAction.HOME_REQUEST) return owner.showRequest(visit, actions.blueprintCurrent())
    if (!owner.claim(visit, actions.blueprintCurrent())) return false
    when (action) {
        BlueprintDiscoveryAction.HOME_PANTRY -> actions.kitchen?.invoke(KitchenInputPage.PANTRY)
        BlueprintDiscoveryAction.HOME_SAVES -> actions.cookbook?.invoke()
        BlueprintDiscoveryAction.HOME_RESUME -> actions.retainedCooking?.invoke()
        else -> return false
    }
    return true
}

internal fun blueprintMealLandingNavigate(owner: BlueprintMealLandingOwner, visit: BlueprintMealLandingVisit,
    meal: MealScreenState, form: MealFormState, actions: MealScreenActions, destination: BlueprintScreenId): Boolean =
    when (destination) {
        BlueprintScreenId.HOME -> visit.home && !visit.offline && blueprintMealLandingEligible(meal, form) &&
            owner.claim(visit, actions.blueprintCurrent())
        BlueprintScreenId.COOKBOOK -> blueprintMealLandingAction(owner, visit, meal, form, actions, BlueprintDiscoveryAction.HOME_SAVES)
        else -> false
    }

/** The real Social entry owns the landing claim before its read. Do not consume that
 * visit here or substitute a newer meal/form when an old Home callback arrives. */
internal fun blueprintMealLandingSocialNavigate(owner: BlueprintMealLandingOwner, visit: BlueprintMealLandingVisit,
    meal: MealScreenState, form: MealFormState, actions: MealScreenActions,
    destination: BlueprintScreenId, callback: ((BlueprintScreenId) -> Unit)?): Boolean {
    if (callback == null || destination !in setOf(BlueprintScreenId.TODAY, BlueprintScreenId.INBOX,
            BlueprintScreenId.PROFILE_PLATE) || !visit.home || visit.offline || !blueprintMealLandingEligible(meal, form) ||
        !owner.current(visit, actions.blueprintCurrent())) return false
    callback(destination)
    return true
}

/** More is explicit optional host chrome, never a Profile tab alias or canonical mutation. */
internal fun blueprintMealAccountSettings(owner: BlueprintMealLandingOwner, visit: BlueprintMealLandingVisit,
    meal: MealScreenState, form: MealFormState, actions: MealScreenActions, ticket: AccountSettingsHostAction,
    callback: (() -> Unit)?): Boolean {
    if (!visit.home || visit.offline || !blueprintMealLandingEligible(meal, form) || !owner.current(visit, actions.blueprintCurrent())) return false
    val selected = ticket.claim(visit, callback, true) ?: return false
    if (!owner.claim(visit, actions.blueprintCurrent())) return false
    selected()
    return true
}

/** Exact original HOME presentation. Attachment/restore alone causes no reads or commands. */
private class BlueprintHomeMoreActions(val settings: AccountSettingsHostAction?, val history: AccountSettingsHostAction?)

@Composable
internal fun BlueprintMealLanding(owner: BlueprintMealLandingOwner, visit: BlueprintMealLandingVisit,
    meal: MealScreenState, form: MealFormState, actions: MealScreenActions, modifier: Modifier = Modifier,
    onAccountSettings: (() -> Unit)? = null,
    onSocialNavigate: ((BlueprintScreenId) -> Unit)? = null,
    onHistory: (() -> Unit)? = null) {
    val latestAccountSettings by rememberUpdatedState(onAccountSettings)
    val latestHistory by rememberUpdatedState(onHistory)
    val latestSocialNavigate by rememberUpdatedState(onSocialNavigate)
    var menu by remember(owner, visit) { mutableStateOf<BlueprintHomeMoreActions?>(null) }
    var presentationVisit by remember(owner, visit) { mutableStateOf(Any()) }
    var attached by remember(owner) { mutableStateOf(true) }
    DisposableEffect(owner) { onDispose { attached = false } }
    val renderedMenu = menu
    val renderedVisit = presentationVisit
    val enabled = visit.home && !visit.offline && owner.current(visit, actions.blueprintCurrent())
    BlueprintHomeScreen(blueprintMealLandingPresentation(meal, form, actions, enabled, onAccountSettings != null || onHistory != null,
        onSocialNavigate != null),
        onAction = homeAction@ { action ->
            if (!attached || presentationVisit !== renderedVisit || renderedMenu != null || menu != null) return@homeAction
            if (action == BlueprintDiscoveryAction.HOME_TODAY) {
                if (attached) blueprintMealLandingSocialNavigate(owner, visit, meal, form, actions,
                    BlueprintScreenId.TODAY, latestSocialNavigate)
            } else blueprintMealLandingAction(owner, visit, meal, form, actions, action)
        },
        onMore = {
            if (attached && presentationVisit === renderedVisit && menu == null && visit.home && !visit.offline &&
                owner.current(visit, actions.blueprintCurrent()) && blueprintMealLandingEligible(meal, form)) {
                presentationVisit = Any()
                menu = BlueprintHomeMoreActions(latestAccountSettings?.let { AccountSettingsHostAction(visit, it) },
                    latestHistory?.let { AccountSettingsHostAction(visit, it) })
            }
        },
        onNavigate = homeNavigate@ { destination ->
            if (!attached || presentationVisit !== renderedVisit || renderedMenu != null || menu != null) return@homeNavigate
            if (destination in setOf(BlueprintScreenId.TODAY, BlueprintScreenId.INBOX, BlueprintScreenId.PROFILE_PLATE)) {
                if (attached) blueprintMealLandingSocialNavigate(owner, visit, meal, form, actions,
                    destination, latestSocialNavigate)
            } else blueprintMealLandingNavigate(owner, visit, meal, form, actions, destination)
        },
        modifier = modifier)
    if (renderedMenu != null && attached && enabled) FeedMeTheme {
        AlertDialog(onDismissRequest = { if (menu === renderedMenu) { presentationVisit = Any(); menu = null } },
            title = { Text("More options") },
            text = { Column {
                if (renderedMenu.history != null && onHistory != null) TextButton(onClick = {
                    if (attached && menu === renderedMenu) {
                        presentationVisit = Any()
                        menu = null
                        blueprintMealAccountSettings(owner, visit, meal, form, actions, renderedMenu.history, latestHistory)
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("What works for you") }
                if (renderedMenu.settings != null && onAccountSettings != null) TextButton(onClick = {
                    if (attached && menu === renderedMenu) {
                        presentationVisit = Any()
                        menu = null
                        blueprintMealAccountSettings(owner, visit, meal, form, actions, renderedMenu.settings, latestAccountSettings)
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Account & privacy") }
            } },
            confirmButton = { TextButton(onClick = { if (menu === renderedMenu) { presentationVisit = Any(); menu = null } }) { Text("Close") } })
    }
}
