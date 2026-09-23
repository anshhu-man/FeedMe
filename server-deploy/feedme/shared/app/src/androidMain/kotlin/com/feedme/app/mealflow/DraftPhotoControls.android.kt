package com.feedme.app.mealflow

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.feedme.app.media.*
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.PrivateBytes
import com.feedme.mealflow.social.DraftLocalPhoto
import com.feedme.mealflow.social.LocalDraftPhotoInput
import com.feedme.mealflow.social.LocalPostDraft
import com.feedme.mealflow.social.PostDraftController
import com.feedme.mealflow.social.PreparedLocalPhotoSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Composable
internal actual fun DraftPhotoControls(controller: PostDraftController, selected: LocalPostDraft, enabled: Boolean) {
    val scope = rememberCoroutineScope()
    val resolver = LocalContext.current.contentResolver
    val importer = remember { AndroidSelectedPhotoImporter() }
    val previewReads = remember(controller) { Mutex() }
    var working by remember(controller) { mutableStateOf(false) }
    var message by remember(controller, selected.clientDraftId) { mutableStateOf<String?>(null) }
    val picker = rememberAndroidPhotoPicker { target, result ->
        val token = target as? PreparedLocalPhotoSelection
        if (token != null) scope.launch {
            try {
                when (result) {
                    is AndroidPhotoPickResult.Selected -> when (val imported = importer.import(resolver, result.photo)) {
                        is AndroidPhotoImportResult.Imported -> {
                            val photo = imported.photo
                            val bytes = photo.copyBytes()
                            val input = try { LocalDraftPhotoInput(photo.contentType, photo.width, photo.height, PrivateBytes(bytes)) }
                                finally { bytes.fill(0) }
                            message = when (controller.attachPhoto(token, input)) {
                                is PortResult.Value -> "Photo kept in this device's private draft. It has not been uploaded."
                                is PortResult.Failure -> "This draft changed or storage could not confirm the photo. Review the draft before trying again."
                            }
                        }
                        is AndroidPhotoImportResult.Failed -> {
                            controller.cancelPhotoSelection(token)
                            message = photoImportMessage(imported.reason)
                        }
                    }
                    AndroidPhotoPickResult.Cancelled -> { controller.cancelPhotoSelection(token); message = null }
                    AndroidPhotoPickResult.Unavailable -> {
                        controller.cancelPhotoSelection(token)
                        message = "The system photo picker is unavailable. No photo was added."
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                controller.cancelPhotoSelection(token)
                message = "The photo could not be imported. No upload was attempted."
            } finally { working = false }
        }
    }
    DraftPhotoContent(selected, enabled && !working, if (working) "Choosing or preparing your photo…" else message,
        onAdd = {
            if (!working && enabled) {
                working = true; message = null
                scope.launch {
                    when (val prepared = controller.preparePhotoSelection(selected.clientDraftId, selected.localRevision)) {
                        is PortResult.Failure -> { working = false; message = "Review the current draft before choosing a photo." }
                        is PortResult.Value -> if (!picker.launch(prepared.value)) {
                            controller.cancelPhotoSelection(prepared.value); working = false
                            message = "Another photo selection is already open."
                        }
                    }
                }
            }
        },
        onRemove = { assetId ->
            if (!working && enabled) {
                working = true
                scope.launch {
                    try {
                        message = when (controller.removePhoto(selected.clientDraftId, selected.localRevision, assetId)) {
                            is PortResult.Value -> "Photo removed from this draft."
                            is PortResult.Failure -> "This draft changed or storage could not confirm removal. Review the current draft."
                        }
                    } finally { working = false }
                }
            }
        },
        preview = { photo -> RetainedDraftPhotoPreview(controller, selected, photo, previewReads) })
}

internal fun photoImportMessage(reason: AndroidPhotoImportFailure): String = when (reason) {
    AndroidPhotoImportFailure.PROVIDER_UNAVAILABLE -> "This photo is no longer available from its provider. Choose it again."
    AndroidPhotoImportFailure.MALFORMED -> "This photo could not be read completely. Choose a different JPEG or PNG."
    AndroidPhotoImportFailure.UNSUPPORTED_FORMAT -> "This image format is not supported on this device. Try another JPEG or PNG."
    AndroidPhotoImportFailure.UNSUPPORTED_DECODER -> "This image cannot be decoded on this device. Try another JPEG or PNG."
    AndroidPhotoImportFailure.LIMIT_EXCEEDED -> "This photo exceeds the local import limit. Choose a smaller image."
}

@Composable
private fun RetainedDraftPhotoPreview(controller: PostDraftController, selected: LocalPostDraft, photo: DraftLocalPhoto, reads: Mutex) {
    // Exact revision-bound encrypted read. No filename, URI, image cache or saveable bitmap state.
    var preview by remember(controller, selected.clientDraftId, selected.localRevision, photo.assetId) { mutableStateOf<Bitmap?>(null) }
    var unavailable by remember(controller, selected.clientDraftId, selected.localRevision, photo.assetId) { mutableStateOf(false) }
    LaunchedEffect(controller, selected.clientDraftId, selected.localRevision, photo.assetId) {
        // Controller delivery ownership is shared; finish each exact read before admitting the next.
        when (val retained = reads.withLock { controller.readPhoto(selected.clientDraftId, selected.localRevision, photo.assetId) }) {
            is PortResult.Failure -> unavailable = true
            is PortResult.Value -> {
                val bitmap = withContext(Dispatchers.Default) {
                    val bytes = retained.value.copyForCodec()
                    try { decodeRetainedPhotoPreview(bytes, photo) { coroutineContext.ensureActive() } }
                    finally { bytes.fill(0) }
                }
                coroutineContext.ensureActive()
                preview = bitmap; unavailable = bitmap == null
            }
        }
    }
    val bitmap = preview
    if (bitmap != null) Image(bitmap.asImageBitmap(), "Photo retained in this private draft",
        modifier = Modifier.sizeIn(maxWidth = 256.dp, maxHeight = 256.dp).heightIn(min = 48.dp), contentScale = ContentScale.Fit)
    else if (unavailable) Text("Preview unavailable. The retained photo has not been uploaded.")
    // Bitmap is memory-only and becomes unreachable with this composition. Do not recycle while
    // Compose's render thread might still hold a submitted image; no disk image loader is used.
}
