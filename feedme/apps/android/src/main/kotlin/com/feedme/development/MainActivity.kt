package com.feedme.development

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import com.feedme.app.FeedMeApp
import com.feedme.core.DemoKitchenRuntime
import com.feedme.core.ports.EpochClock
import kotlin.time.Clock

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val demoRuntime = DemoKitchenRuntime(EpochClock { Clock.System.now().toEpochMilliseconds() })
        setContent { FeedMeApp(demoRuntime) { enabled, action -> BackHandler(enabled, onBack = action) } }
    }
}
