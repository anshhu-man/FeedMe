package com.feedme.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Exported only for the exact package-specific callback. No account owner is constructed,
 * no credentials are accepted from Intent extras and no URL is logged or saved. */
class SupabaseOAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val received = intent
            val raw = if (received?.action == Intent.ACTION_VIEW) received.dataString else null
            // Clear the Activity's reference before any owner callback or task navigation.
            intent = Intent()
            if (raw != null) (application as FeedMeApplication).googleOAuthCallbacks.receive(raw)
        } catch (_: Exception) {
            // No raw URI, auth code or exception details reach UI/diagnostics.
        } finally { finish() }
    }
}
