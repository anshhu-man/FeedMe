package com.feedme.android

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.feedme.mealflow.notifications.NotificationDevicePermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Device permission only. Never registers a token, changes account preferences or sends a
 * notification. The single non-private installation marker prevents repeated native prompts;
 * current permission is always observed from Android, not inferred from that marker. */
internal class AndroidFeedMeNotificationPermission(
    private val activity: ComponentActivity,
    private val isCurrent: (EntryState) -> Boolean,
    private val onResult: (EntryState) -> Unit,
) : AutoCloseable {
    private val preferences = activity.getSharedPreferences("feedme-notification-permission", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(NotificationDevicePermission.UNKNOWN)
    val states = mutable.asStateFlow()
    private var pending: EntryState? = null
    private var closed = false
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        val original = pending
        pending = null
        refresh()
        if (!closed && original != null && isCurrent(original)) onResult(original)
    }

    fun refresh() {
        if (closed) return
        mutable.value = try {
            val manager = activity.getSystemService(NotificationManager::class.java)
            val allowed = manager?.areNotificationsEnabled() == true &&
                (Build.VERSION.SDK_INT < 33 || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            when {
                allowed -> NotificationDevicePermission.GRANTED
                Build.VERSION.SDK_INT >= 33 && !preferences.getBoolean("requested", false) -> NotificationDevicePermission.NOT_REQUESTED
                else -> NotificationDevicePermission.DENIED
            }
        } catch (_: RuntimeException) { NotificationDevicePermission.UNAVAILABLE }
    }

    fun request(expected: EntryState) {
        if (closed || pending != null || expected.screen != EntryScreen.ACCOUNT_NOTIFICATION_PERMISSION || !isCurrent(expected)) return
        refresh()
        if (Build.VERSION.SDK_INT < 33 || mutable.value != NotificationDevicePermission.NOT_REQUESTED) return
        try {
            // Persist before dispatch. A cancelled/swiped-away prompt is still a prompt;
            // the explicit system-settings action remains available without another one.
            if (!preferences.edit().putBoolean("requested", true).commit()) {
                mutable.value = NotificationDevicePermission.UNAVAILABLE
                return
            }
            pending = expected
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (_: RuntimeException) {
            pending = null
            mutable.value = NotificationDevicePermission.UNAVAILABLE
        }
    }

    fun openSettings(expected: EntryState) {
        if (closed || pending != null || !isCurrent(expected)) return
        try {
            activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
        } catch (_: RuntimeException) { mutable.value = NotificationDevicePermission.UNAVAILABLE }
    }

    override fun close() {
        if (closed) return
        closed = true; pending = null; launcher.unregister()
        mutable.value = NotificationDevicePermission.UNKNOWN
    }
}
