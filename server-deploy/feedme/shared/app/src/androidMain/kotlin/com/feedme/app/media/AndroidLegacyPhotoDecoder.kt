package com.feedme.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace

/** BitmapFactory alone can return partial JPEGs. Admit complete bounded coding data first.
 * PNG uses the existing full chunk/CRC/zlib scanline checks; JPEG uses baseline entropy checks.
 * Native decoding is in-process, not a hard memory/time sandbox. Caller owns the returned bitmap. */
internal fun decodeCompleteLegacyPhoto(source: ByteArray, contentType: String,
    policy: AndroidPhotoImportPolicy, maxEdge: Int, checkCancellation: () -> Unit = {}): Bitmap {
    require(maxEdge in 1..policy.outputMaxEdge)
    val container = AndroidPhotoContainerValidator(policy, checkCancellation).inspect(source, contentType)
    if (contentType == "image/jpeg") validateBaselineJpeg(source, policy, checkCancellation)
    checkCancellation()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
    photoValid(bounds.outWidth == container.width && bounds.outHeight == container.height && bounds.outMimeType == contentType)
    var sample = 1
    while ((container.width + sample - 1) / sample > maxEdge ||
        (container.height + sample - 1) / sample > maxEdge) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        inScaled = false
    }
    checkCancellation()
    val bitmap = BitmapFactory.decodeByteArray(source, 0, source.size, options)
        ?: throw PhotoValidationException(ContainerFailure.MALFORMED)
    try {
        checkCancellation()
        // Platform codecs may round fractional sampled dimensions up or down, not return an
        // arbitrary smaller raster. The selected sample already guarantees both upper bounds.
        photoValid(bitmap.width in maxOf(1, container.width / sample)..((container.width + sample - 1) / sample) &&
            bitmap.height in maxOf(1, container.height / sample)..((container.height + sample - 1) / sample))
        return bitmap
    } catch (failure: Throwable) {
        bitmap.recycle()
        throw failure
    }
}
