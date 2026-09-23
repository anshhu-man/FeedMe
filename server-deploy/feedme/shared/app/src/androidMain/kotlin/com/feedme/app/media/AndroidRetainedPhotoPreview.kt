package com.feedme.app.media

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.os.Build
import androidx.annotation.RequiresApi
import com.feedme.mealflow.social.DraftLocalPhoto
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException

/** Exact descriptor checks plus complete-codec admission; encrypted retention alone is not
 * proof that arbitrary stored JPEG entropy can be decoded completely. Never writes a cache. */
internal fun decodeRetainedPhotoPreview(bytes: ByteArray, photo: DraftLocalPhoto,
    apiLevel: Int = Build.VERSION.SDK_INT, checkCancellation: () -> Unit = {}): Bitmap? {
    if (bytes.size != photo.byteCount || bytes.size !in 1..DraftLocalPhoto.MAX_BYTES) return null
    return try {
        val policy = AndroidPhotoImportPolicy(maxWidth = DraftLocalPhoto.MAX_DIMENSION,
            maxHeight = DraftLocalPhoto.MAX_DIMENSION, maxPixels = DraftLocalPhoto.MAX_PIXELS)
        val container = AndroidPhotoContainerValidator(policy, checkCancellation).inspect(bytes, photo.mimeType)
        if (container.width != photo.width || container.height != photo.height || container.orientation != 1) return null
        checkCancellation()
        val bitmap = if (Build.VERSION.SDK_INT >= 28 && apiLevel >= 28) strictPreview(bytes, photo)
            else decodeCompleteLegacyPhoto(bytes, photo.mimeType, policy, 256, checkCancellation)
        try { checkCancellation(); bitmap } catch (failure: Throwable) { bitmap.recycle(); throw failure }
    } catch (_: IOException) { null }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: RuntimeException) { null }
}

@RequiresApi(28)
private fun strictPreview(bytes: ByteArray, photo: DraftLocalPhoto): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        require(info.size.width == photo.width && info.size.height == photo.height)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        val edge = maxOf(photo.width, photo.height)
        if (edge > 256) decoder.setTargetSize(maxOf(1, photo.width * 256 / edge), maxOf(1, photo.height * 256 / edge))
        decoder.setOnPartialImageListener { false }
    }
