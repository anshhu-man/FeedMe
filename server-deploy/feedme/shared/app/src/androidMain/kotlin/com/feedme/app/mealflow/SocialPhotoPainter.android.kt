package com.feedme.app.mealflow

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import com.feedme.app.media.AndroidPhotoImportPolicy
import com.feedme.app.media.decodeCompleteLegacyPhoto
import com.feedme.mealflow.social.SocialPhoto
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Serialize bounded native codec work; neither this nor the in-process codec is an OS sandbox.
private val socialPhotoDecode = Mutex()

@Composable
internal actual fun rememberSocialPhotoPainter(photo: SocialPhoto?, displayCurrent: () -> Boolean): Painter? {
    val latestCurrent by rememberUpdatedState(displayCurrent)
    var bitmap by remember(photo) { mutableStateOf<Bitmap?>(null) }
    var attached by remember(photo) { mutableStateOf(true) }
    DisposableEffect(photo) {
        attached = true
        onDispose { attached = false; bitmap?.recycle(); bitmap = null }
    }
    LaunchedEffect(photo) {
        if (photo == null || !photo.isCurrent() || !latestCurrent()) return@LaunchedEffect
        var candidate: Bitmap? = null
        try {
            socialPhotoDecode.withLock {
                withContext(Dispatchers.Default) {
                    val bytes = photo.copyForDecoder() ?: return@withContext
                    try {
                        require(bytes.size in 1..4194304)
                        val context = currentCoroutineContext()
                        candidate = decodeCompleteLegacyPhoto(bytes, "image/png",
                            AndroidPhotoImportPolicy(maxSourceBytes = 4194304, maxWidth = 2048,
                                maxHeight = 2048, maxPixels = 4194304, outputMaxEdge = 512), 512) {
                            context.ensureActive(); require(photo.isCurrent())
                        }
                    } finally { bytes.fill(0) }
                }
            }
            ensureActive()
            if (attached && photo.isCurrent() && latestCurrent()) { bitmap = candidate; candidate = null }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Unsupported or invalid image remains the original empty media slot. */ }
        finally { candidate?.recycle() }
    }
    val actual = bitmap
    return if (photo == null || actual == null || actual.isRecycled || !photo.isCurrent() || !displayCurrent()) null
    else remember(photo, actual) { CurrentSocialPhotoPainter(actual, photo) { attached && latestCurrent() } }
}

/** Draw-time lifecycle fence also prevents a cached Compose painter from displaying expired
 * material between timer redaction and the next recomposition. */
private class CurrentSocialPhotoPainter(private val bitmap: Bitmap, private val photo: SocialPhoto,
    private val current: () -> Boolean) : Painter() {
    private val delegate = BitmapPainter(bitmap.asImageBitmap())
    override val intrinsicSize: Size get() = delegate.intrinsicSize
    override fun DrawScope.onDraw() {
        if (!bitmap.isRecycled && photo.isCurrent() && current()) with(delegate) { draw(size) }
    }
}
