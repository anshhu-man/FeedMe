package com.feedme.android

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.feedme.app.guest.GuestAdultAdmissionScreen
import com.feedme.app.guest.GuestKitchenScreen
import com.feedme.app.mealflow.FeedMeMealFlow

/** The non-exported launcher admits the current adult declaration before attaching private
 * guest storage/session state. Account entry stays separate and no synthetic result is used. */
class GuestActivity : ComponentActivity() {
    private lateinit var entry: GuestEntry
    private var attachment: Any? = null
    private var adultAdmitted by mutableStateOf(false)
    private var accountLaunchPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableFeedMeEdgeToEdge()
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        entry = (application as FeedMeApplication).guestEntry
        setContent {
            if (!adultAdmitted) {
                BackHandler(true) { finish() }
                GuestAdultAdmissionScreen(onConfirm = {
                    if (!adultAdmitted && attachment == null) {
                        attachment = entry.attach()
                        adultAdmitted = true
                    }
                }, onBack = { finish() })
                return@setContent
            }
            val token = attachment ?: return@setContent
            val state by entry.states.collectAsState()
            val tab by entry.tabs.collectAsState()
            val matchingUnavailable by entry.matchingUnavailable.collectAsState()
            val mealState by entry.mealStates.collectAsState()
            val currentAttachment by entry.attachmentStates.collectAsState()
            if (currentAttachment !== token) return@setContent
            val route = mealState.route
            if (route != null && mealState.phase == GuestMealPhase.VISIBLE) {
                FeedMeMealFlow(experience = route.experience,
                    onExit = { entry.hideMeal(token, mealState) },
                    platformBackHandler = { enabled, back -> BackHandler(enabled, back) },
                    hostIsCurrent = { entry.mealRouteCurrent(token, mealState) })
                return@setContent
            }
            GuestKitchenScreen(state = state, selectedTab = tab, matchingUnavailable = matchingUnavailable,
                matchingConnected = route != null && mealState.phase !in setOf(
                    GuestMealPhase.CLOSING, GuestMealPhase.CLOSE_FAILED, GuestMealPhase.CLOSED),
                matchingBusy = mealState.phase in setOf(GuestMealPhase.OPENING, GuestMealPhase.MATCHING,
                    GuestMealPhase.CLOSING),
                onDraftChange = { entry.update(token, it) },
                onFindMeal = { entry.findMeal(token) }, onRetry = { entry.retry(token) },
                onTabChange = { entry.select(token, it) },
                onAccount = {
                    if (entry.attached(token) && !accountLaunchPending) {
                        entry.flush(token)
                        entry.switchToAccount(token) {
                            if (entry.attached(token) && !accountLaunchPending) {
                                accountLaunchPending = true
                                startActivity(Intent(this, AccountActivity::class.java))
                                finish()
                            }
                        }
                    }
                }, platformBackHandler = { enabled, back -> BackHandler(enabled, back) })
        }
    }

    override fun onResume() {
        super.onResume()
        accountLaunchPending = false
    }

    override fun onStop() {
        attachment?.let(entry::flush)
        super.onStop()
    }

    override fun onDestroy() {
        attachment?.let(entry::detach)
        attachment = null
        if (accountLaunchPending && entry.closedForReplacement())
            (application as FeedMeApplication).releaseGuestEntry(entry)
        super.onDestroy()
    }
}
