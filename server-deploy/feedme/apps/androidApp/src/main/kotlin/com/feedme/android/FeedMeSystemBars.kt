package com.feedme.android

import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/** FeedMe currently renders a light-only theme, even when the device is in night mode.
 * Keep system icons dark without changing edge-to-edge layout or the AndroidX navigation
 * fallback: API 27/28 use a light scrim; API 29+ let the platform protect three-button
 * navigation while gesture navigation remains transparent.
 */
internal fun ComponentActivity.enableFeedMeEdgeToEdge() {
    // Do this before AndroidX reads decorView. On Android 15 a preserved window can
    // install the default inset listener before decor regeneration enables enforced
    // edge-to-edge; a later setter then returns without clearing that listener.
    // Callers must not force decor installation before entering this helper.
    if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.light(
            if (Build.VERSION.SDK_INT >= 29) Color.TRANSPARENT else LIGHT_NAVIGATION_SCRIM,
            DARK_NAVIGATION_FALLBACK,
        ),
    )
    // SystemBarStyle.light disables this AndroidX default. Retain the platform's existing
    // three-button contrast protection; it does not add a gesture-navigation scrim.
    if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = true
}

private const val LIGHT_NAVIGATION_SCRIM: Int = -419430401 // AndroidX light fallback: 0xE6FFFFFF.
private const val DARK_NAVIGATION_FALLBACK: Int = -2145707237 // AndroidX dark fallback: 0x801B1B1B.
