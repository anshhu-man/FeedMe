package com.feedme.app.media

import android.net.Uri
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.util.UUID

/** An ephemeral provider handle. Never put it in saved state, logs, or a draft record. */
class AndroidPickedPhoto internal constructor(internal val uri: Uri) {
    override fun toString() = "AndroidPickedPhoto(<redacted>)"
}

sealed interface AndroidPhotoPickResult {
    class Selected(val photo: AndroidPickedPhoto) : AndroidPhotoPickResult {
        override fun toString() = "AndroidPhotoPickResult.Selected(<redacted>)"
    }
    data object Cancelled : AndroidPhotoPickResult
    data object Unavailable : AndroidPhotoPickResult
}

/** One ephemeral, identity-bound request. The owner must still admit the returned target. */
class AndroidPhotoPicker internal constructor(
    private val owner: AndroidPhotoPickOwner,
    private val launchSystemPicker: () -> Unit,
    private val deliver: (Any, AndroidPhotoPickResult) -> Unit,
) {
    /** False means busy/disposed; this target was not adopted and receives no callback. */
    fun launch(target: Any): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!owner.begin(target)) return false
        try {
            launchSystemPicker()
        } catch (_: RuntimeException) {
            owner.finish(AndroidPhotoPickResult.Unavailable, deliver)
        }
        return true
    }
}

/** Uses selected-only Photo Picker access (or AndroidX's document fallback), no broad permission. */
@Composable
fun rememberAndroidPhotoPicker(onResult: (Any, AndroidPhotoPickResult) -> Unit): AndroidPhotoPicker {
    val deliver = rememberUpdatedState(onResult)
    val registry = LocalActivityResultRegistryOwner.current?.activityResultRegistry
    val owner = remember(registry) { AndroidPhotoPickOwner() } // deliberately not saveable
    val slot = remember(owner) { PhotoLauncherSlot() }
    DisposableEffect(owner, registry) {
        slot.launcher = registry?.let { registerPhotoPicker(it, owner) { token, result -> deliver.value(token, result) } }
        onDispose {
            slot.launcher?.unregister(); slot.launcher = null
            owner.dispose { token, result -> deliver.value(token, result) }
        }
    }
    return remember(owner, slot) {
        AndroidPhotoPicker(owner, { checkNotNull(slot.launcher).launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }) {
            token, result -> deliver.value(token, result)
        }
    }
}

/** Direct non-lifecycle registration is valid after STARTED and explicitly unregistered above.
 * Unlike rememberLauncherForActivityResult, this key is NEVER restored: an old activity result
 * cannot be delivered to a newly created owner which has already accepted a different target. */
internal fun registerPhotoPicker(registry: ActivityResultRegistry, owner: AndroidPhotoPickOwner,
    deliver: (Any, AndroidPhotoPickResult) -> Unit): ActivityResultLauncher<PickVisualMediaRequest> =
    registry.register(owner.registrationKey, PickVisualMedia()) { uri ->
        owner.finish(if (uri == null) AndroidPhotoPickResult.Cancelled
            else if (uri.scheme == "content") AndroidPhotoPickResult.Selected(AndroidPickedPhoto(uri))
            else AndroidPhotoPickResult.Unavailable, deliver)
    }

private class PhotoLauncherSlot { var launcher: ActivityResultLauncher<PickVisualMediaRequest>? = null }

internal class AndroidPhotoPickOwner {
    val registrationKey = "feedme-photo-" + UUID.randomUUID().toString()
    private var pending: Any? = null
    private var disposed = false
    fun begin(target: Any): Boolean {
        if (disposed || pending != null) return false
        pending = target
        return true
    }
    fun finish(result: AndroidPhotoPickResult, deliver: (Any, AndroidPhotoPickResult) -> Unit) {
        val target = pending ?: return
        pending = null // clear before callback so explicit subsequent requests are possible
        deliver(target, result)
    }
    fun dispose(deliver: (Any, AndroidPhotoPickResult) -> Unit) {
        disposed = true
        finish(AndroidPhotoPickResult.Cancelled, deliver)
    }
}
