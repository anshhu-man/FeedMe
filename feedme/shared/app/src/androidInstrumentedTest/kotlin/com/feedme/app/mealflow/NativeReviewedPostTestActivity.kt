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
import com.feedme.mealflow.social.ReviewedPostEntry

/** Separate instrumentation-only host. It borrows actual retained controllers and the actual
 * Android session/store. Recreation does not restore, prepare, send or manufacture consent.
 * The existing production progress host and legacy 49 native methods remain untouched. */
class NativeReviewedPostTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val owner = checkNotNull(NativeReviewedPostHostOwner.current)
        setContent {
            FeedMeTheme {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Text("SYNTHETIC ACCOUNT / POLICY / SERVICE · REAL NATIVE STORE",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    FeedMeReviewedPostFlow(owner.entry) { enabled, back ->
                        BackHandler(enabled = enabled, onBack = back)
                    }
                }
            }
        }
    }
}

/** Installed and removed on Main only by the exact fixture. No SavedState or proof constructor. */
internal class NativeReviewedPostHostOwner(val entry: ReviewedPostEntry) {
    companion object { var current: NativeReviewedPostHostOwner? = null }
}
