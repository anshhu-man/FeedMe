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
import com.feedme.app.guest.GuestKitchenScreen

/** The launcher opens local cooking choices, never an account or a synthetic session. */
class GuestActivity : ComponentActivity() {
    private lateinit var entry: GuestEntry
    private lateinit var attachment: Any
    private var accountLaunchPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableFeedMeEdgeToEdge()
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        entry = (application as FeedMeApplication).guestEntry
        attachment = entry.attach()
        setContent {
            val state by entry.states.collectAsState()
            val tab by entry.tabs.collectAsState()
            val matchingUnavailable by entry.matchingUnavailable.collectAsState()
            val currentAttachment by entry.attachmentStates.collectAsState()
            if (currentAttachment !== attachment) return@setContent
            GuestKitchenScreen(state = state, selectedTab = tab, matchingUnavailable = matchingUnavailable,
                onDraftChange = { entry.update(attachment, it) },
                onFindMeal = { entry.findMeal(attachment) }, onRetry = { entry.retry(attachment) },
                onTabChange = { entry.select(attachment, it) },
                onAccount = {
                    if (entry.attached(attachment) && !accountLaunchPending) {
                        accountLaunchPending = true
                        entry.flush(attachment)
                        startActivity(Intent(this, AccountActivity::class.java))
                    }
                }, platformBackHandler = { enabled, back -> BackHandler(enabled, back) })
        }
    }

    override fun onResume() {
        super.onResume()
        accountLaunchPending = false
    }

    override fun onStop() {
        if (::attachment.isInitialized) entry.flush(attachment)
        super.onStop()
    }

    override fun onDestroy() {
        if (::attachment.isInitialized) entry.detach(attachment)
        super.onDestroy()
    }
}
