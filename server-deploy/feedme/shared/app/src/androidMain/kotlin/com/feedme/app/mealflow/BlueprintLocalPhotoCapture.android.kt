package com.feedme.app.mealflow

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import com.feedme.app.blueprint.BlueprintAuthoringIdentity
import com.feedme.app.blueprint.BlueprintAuthoringMediaKind
import com.feedme.app.blueprint.BlueprintAuthoringPreview
import com.feedme.app.media.*
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.PrivateBytes
import com.feedme.mealflow.social.LocalDraftPhotoInput
import com.feedme.mealflow.social.LocalPostDraft
import com.feedme.mealflow.social.PostDraftController
import com.feedme.mealflow.social.PostDraftPhase
import com.feedme.mealflow.social.PreparedLocalPhotoSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class CapturePhotoSelection(val draftId: String, val revision: Long, val token: PreparedLocalPhotoSelection)

@Composable
internal actual fun rememberBlueprintPhotoCapture(controller: PostDraftController): BlueprintPhotoCaptureActions = key(controller) {
    val scope = rememberCoroutineScope()
    val resolver = LocalContext.current.contentResolver
    val importer = remember { AndroidSelectedPhotoImporter() }
    val selectedDraftId = controller.states.collectAsState().value.selected?.clientDraftId
    var working by remember { mutableStateOf(false) }
    var message by remember(selectedDraftId) { mutableStateOf<String?>(null) }
    fun current(selection: CapturePhotoSelection): Boolean {
        val state = controller.states.value
        return state.phase != PostDraftPhase.UNAVAILABLE && state.selected?.let {
            it.clientDraftId == selection.draftId && it.localRevision == selection.revision
        } == true
    }
    val picker = rememberAndroidPhotoPicker { target, result ->
        val selection = target as? CapturePhotoSelection
        if (selection != null) scope.launch {
            try {
                if (!current(selection)) { controller.cancelPhotoSelection(selection.token); return@launch }
                when (result) {
                    is AndroidPhotoPickResult.Selected -> when (val imported = importer.import(resolver, result.photo)) {
                        is AndroidPhotoImportResult.Imported -> {
                            val photo = imported.photo
                            val bytes = photo.copyBytes()
                            val input = try { LocalDraftPhotoInput(photo.contentType, photo.width, photo.height, PrivateBytes(bytes)) }
                                finally { bytes.fill(0) }
                            message = when (controller.attachPhoto(selection.token, input)) {
                                is PortResult.Value -> "Photo kept in this device's private draft. It has not been uploaded."
                                is PortResult.Failure -> "The draft changed or storage could not confirm the photo. Review the draft before trying again."
                            }
                        }
                        is AndroidPhotoImportResult.Failed -> {
                            controller.cancelPhotoSelection(selection.token)
                            message = photoImportMessage(imported.reason)
                        }
                    }
                    AndroidPhotoPickResult.Cancelled -> { controller.cancelPhotoSelection(selection.token); message = null }
                    AndroidPhotoPickResult.Unavailable -> {
                        controller.cancelPhotoSelection(selection.token)
                        message = "The system photo picker is unavailable. No photo was added."
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: RuntimeException) {
                controller.cancelPhotoSelection(selection.token)
                message = "The photo could not be imported. No upload was attempted."
            } finally { working = false }
        }
    }
    BlueprintPhotoCaptureActions(true, working, message) { selected: LocalPostDraft ->
        val observed = controller.states.value
        if (!working && !observed.busy && observed.phase != PostDraftPhase.UNAVAILABLE &&
            observed.selected?.let { it.clientDraftId == selected.clientDraftId && it.localRevision == selected.localRevision } == true) {
            working = true; message = null
            scope.launch {
                when (val prepared = controller.preparePhotoSelection(selected.clientDraftId, selected.localRevision)) {
                    is PortResult.Failure -> { working = false; message = "Review the current draft before choosing a photo." }
                    is PortResult.Value -> if (!picker.launch(CapturePhotoSelection(selected.clientDraftId, selected.localRevision, prepared.value))) {
                        controller.cancelPhotoSelection(prepared.value); working = false
                        message = "Another photo selection is already open."
                    }
                }
            }
        }
    }
}

@Composable
internal actual fun rememberBlueprintRetainedPhoto(controller: PostDraftController, selected: LocalPostDraft,
    identity: BlueprintAuthoringIdentity): BlueprintAuthoringPreview.Actual? {
    val photo = (selected.uploadedPhotos + selected.localPhotos).firstOrNull() ?: return null
    var bitmap by remember(controller, selected.clientDraftId, selected.localRevision, photo.assetId) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(controller, selected.clientDraftId, selected.localRevision, photo.assetId) {
        when (val retained = controller.readPhoto(selected.clientDraftId, selected.localRevision, photo.assetId)) {
            is PortResult.Failure -> Unit
            is PortResult.Value -> {
                val decoded = withContext(Dispatchers.Default) {
                    val bytes = retained.value.copyForCodec()
                    try { decodeRetainedPhotoPreview(bytes, photo, checkCancellation = { coroutineContext.ensureActive() }) }
                    finally { bytes.fill(0) }
                }
                coroutineContext.ensureActive()
                val current = controller.states.value
                if (current.phase != PostDraftPhase.UNAVAILABLE && current.selected?.let {
                        it.clientDraftId == selected.clientDraftId && it.localRevision == selected.localRevision
                    } == true) bitmap = decoded
            }
        }
    }
    // Memory-only painter; never a URI loader/cache. Do not recycle a Bitmap while Compose's
    // render thread may retain it. A different exact revision immediately loses this painter.
    val actual = bitmap ?: return null
    val painter = remember(actual) { BitmapPainter(actual.asImageBitmap()) }
    return BlueprintAuthoringPreview.Actual(identity, photo.assetId, selected.localRevision,
        BlueprintAuthoringMediaKind.PHOTO, painter, selected.altText?.takeIf { it.isNotBlank() } ?: "Photo kept in this private draft")
}
