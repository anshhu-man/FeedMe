package com.feedme.app

import androidx.compose.ui.window.ComposeUIViewController
import com.feedme.core.DemoKitchenRuntime
import com.feedme.core.ports.EpochClock
import kotlin.time.Clock

fun MainViewController() = DemoKitchenRuntime(EpochClock { Clock.System.now().toEpochMilliseconds() }).let { runtime ->
    ComposeUIViewController { FeedMeApp(runtime) }
}
