package com.feedme.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import com.feedme.app.blueprint.BlueprintWelcomeScreen
import com.feedme.app.guest.GuestAdultAdmissionScreen
import com.feedme.app.guest.GuestDraftPhase
import com.feedme.app.guest.GuestKitchenDraftController
import com.feedme.app.guest.GuestKitchenScreen
import com.feedme.app.guest.GuestKitchenTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private enum class IosEntryRoute { WELCOME, ADULT_ADMISSION, GUEST_KITCHEN }

/**
 * Truthful iOS entry while its native credential/session owners are still incomplete.
 *
 * This deliberately renders the current shared guest-first product instead of the historical
 * sample runtime. Local guest choices use the device-only iOS Keychain owner; no account,
 * catalog or recipe request is performed and no successful match is reported. Native iOS
 * account authority can replace these closed callbacks without changing the shared presentation
 * contract.
 */
@OptIn(FlowPreview::class)
@Composable
private fun IosFeedMeEntry() {
    var route by remember { mutableStateOf(IosEntryRoute.WELCOME) }
    var welcomeStatus by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableStateOf(GuestKitchenTab.COOK) }
    var matchingUnavailable by remember { mutableStateOf(false) }
    val draftOwner = remember {
        GuestKitchenDraftController(IosGuestDraftStore(), Dispatchers.Default)
    }
    val draftState by draftOwner.states.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(route, draftOwner) {
        if (route == IosEntryRoute.GUEST_KITCHEN &&
            draftOwner.states.value.phase in setOf(GuestDraftPhase.NEW, GuestDraftPhase.BLOCKED)) {
            draftOwner.load()
        }
    }
    LaunchedEffect(draftOwner) {
        draftOwner.states
            .map { state -> state.draft.takeIf { state.phase == GuestDraftPhase.READY && state.dirty } }
            .distinctUntilChanged()
            .debounce(300)
            .collect { pending ->
                if (pending != null && draftOwner.states.value.draft == pending &&
                    draftOwner.states.value.phase == GuestDraftPhase.READY &&
                    draftOwner.states.value.dirty) draftOwner.save()
            }
    }
    DisposableEffect(draftOwner) { onDispose { draftOwner.close() } }

    fun accountUnavailable() {
        welcomeStatus = "Account sign-in is not connected on iOS yet. No account or session was created."
    }

    when (route) {
        IosEntryRoute.WELCOME -> BlueprintWelcomeScreen(
            onSignup = ::accountUnavailable,
            onLogin = ::accountUnavailable,
            onGuest = {
                welcomeStatus = null
                route = IosEntryRoute.ADULT_ADMISSION
            },
            onApple = ::accountUnavailable,
            onGoogle = ::accountUnavailable,
            onMore = ::accountUnavailable,
            status = welcomeStatus,
        )
        IosEntryRoute.ADULT_ADMISSION -> GuestAdultAdmissionScreen(
            onConfirm = { route = IosEntryRoute.GUEST_KITCHEN },
            onBack = { route = IosEntryRoute.WELCOME },
        )
        IosEntryRoute.GUEST_KITCHEN -> GuestKitchenScreen(
            state = draftState,
            selectedTab = selectedTab,
            matchingUnavailable = matchingUnavailable,
            matchingConnected = false,
            onDraftChange = { draft ->
                draftOwner.update(draft)
                matchingUnavailable = false
            },
            onFindMeal = {
                matchingUnavailable = true
                scope.launch { draftOwner.save() }
            },
            onRetry = { matchingUnavailable = true },
            onTabChange = { selectedTab = it },
            onAccount = {
                accountUnavailable()
                selectedTab = GuestKitchenTab.COOK
                route = IosEntryRoute.WELCOME
            },
            platformBackHandler = { _, _ -> },
        )
    }
}

fun MainViewController() = ComposeUIViewController { IosFeedMeEntry() }
