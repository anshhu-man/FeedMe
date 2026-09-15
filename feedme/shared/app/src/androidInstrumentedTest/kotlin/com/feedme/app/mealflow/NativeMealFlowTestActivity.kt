package com.feedme.app.mealflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feedme.app.FeedMeTheme

/** Test-owned retained composition. Activity recreation borrows the same actual experience;
 * only the test's final cleanup closes it and the real native session. Never in a shipped root. */
class NativeMealFlowTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val owner = checkNotNull(NativeMealFlowHostOwner.current)
        setContent {
            FeedMeTheme {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Text("SYNTHETIC ACCOUNT / TRANSPORT · REAL NATIVE STORE", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    FeedMeMealFlow(owner.experience, { owner.exitCount++ }) { enabled, back ->
                        BackHandler(enabled = enabled, onBack = back)
                    }
                }
            }
        }
    }
}

/** Installed, observed and removed on Main by each isolated test; not SavedState or persistence. */
internal class NativeMealFlowHostOwner(val experience: MealFlowExperience) {
    var exitCount = 0
    companion object { var current: NativeMealFlowHostOwner? = null }
}
