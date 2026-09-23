package com.feedme.app.mealflow

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import com.feedme.app.FeedMeTheme
import com.feedme.mealflow.social.LocalPostDraft
import com.feedme.mealflow.social.PostDraftState

/** A rendered selection, not a mutation permit. The controller rechecks the exact
 * current journal and owns acknowledgement/recovery; no media identifier is invented. */
internal class DraftUploadedPhotoRemovalReview(val state: PostDraftState, val local: LocalPostDraft,
    val assetId: String, val position: Int) {
    var attempted by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
}

@Composable
internal fun DraftUploadedPhotoRemovalDialog(review: DraftUploadedPhotoRemovalReview,
    current: () -> Boolean, confirm: () -> Unit, close: () -> Unit) {
    FeedMeTheme {
        AlertDialog(onDismissRequest = { if (!review.busy) close() },
            title = { Text("Remove photo from draft?") },
            text = { Column {
                when {
                    review.busy -> Text("Saving the updated draft on this device…")
                    review.attempted -> Text(review.message ?: "The change is not confirmed. Close and use the draft’s recovery options.")
                    !current() -> Text("This draft changed. Close and select the photo again; nothing will be removed here.")
                    else -> Text("Remove uploaded photo ${review.position} from this draft’s selection. Other photos and your text stay unchanged.")
                }
                Text("Upload history and stored photo bytes are retained. This does not delete a server photo, change a saved server draft or edit an existing post.")
            } },
            confirmButton = {
                if (!review.attempted) TextButton(onClick = confirm, enabled = current() && !review.busy) { Text("Remove from draft") }
                else TextButton(onClick = close, enabled = !review.busy) { Text("Close") }
            },
            dismissButton = {
                if (!review.attempted) TextButton(onClick = close) { Text("Keep photo") }
            })
    }
}
