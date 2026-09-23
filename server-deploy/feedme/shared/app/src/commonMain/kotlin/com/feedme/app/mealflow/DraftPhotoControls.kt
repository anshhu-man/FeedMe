package com.feedme.app.mealflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feedme.mealflow.social.DraftLocalPhoto
import com.feedme.mealflow.social.LocalPostDraft
import com.feedme.mealflow.social.PostDraftController
import com.feedme.core.ports.PortResult
import kotlinx.coroutines.launch

@Composable
internal expect fun DraftPhotoControls(controller: PostDraftController, selected: LocalPostDraft, enabled: Boolean)

@Composable
internal fun DraftPhotoContent(selected: LocalPostDraft, enabled: Boolean, message: String?,
    onAdd: (() -> Unit)?, onRemove: (String) -> Unit, preview: @Composable (DraftLocalPhoto) -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Photos on this device", style = MaterialTheme.typography.titleMedium)
        Text("Photos stay in this device's private draft storage. Nothing is uploaded or posted.",
            style = MaterialTheme.typography.bodyMedium)
        selected.localPhotos.forEachIndexed { index, photo ->
            Text("Photo ${index + 1} · ${photo.width} × ${photo.height}", style = MaterialTheme.typography.bodyMedium)
            preview(photo)
            TextButton(enabled = enabled, modifier = Modifier.heightIn(min = 48.dp), onClick = { onRemove(photo.assetId) }) {
                Text("Remove photo ${index + 1} from this draft")
            }
        }
        if (onAdd != null) Button(enabled = enabled, modifier = Modifier.heightIn(min = 48.dp), onClick = onAdd) { Text("Choose a photo") }
        if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun UnavailableDraftPhotoControls(controller: PostDraftController, selected: LocalPostDraft, enabled: Boolean) {
    val scope = rememberCoroutineScope()
    var removing by remember(controller) { mutableStateOf(false) }
    var result by remember(controller, selected.clientDraftId) { mutableStateOf<String?>(null) }
    DraftPhotoContent(selected, enabled && !removing,
        listOfNotNull("Photo selection is not connected on this platform.", result).joinToString(" "), null, { assetId ->
        if (enabled && !removing) {
            removing = true
            scope.launch {
                try {
                    result = when (controller.removePhoto(selected.clientDraftId, selected.localRevision, assetId)) {
                        is PortResult.Value -> "Photo removed from this draft."
                        is PortResult.Failure -> "This draft changed or storage could not confirm removal. Review the current draft."
                    }
                } finally { removing = false }
            }
        }
    })
}
