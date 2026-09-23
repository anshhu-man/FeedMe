package com.feedme.app.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

/** Bounded in-process importer. It does not persist a URI, request a grant, write a file, or upload.
 * Byte/raster admission is not a hard wall-clock or native-decoder memory sandbox. */
class AndroidSelectedPhotoImporter(val policy: AndroidPhotoImportPolicy = AndroidPhotoImportPolicy()) {
    suspend fun import(resolver: ContentResolver, selected: AndroidPickedPhoto): AndroidPhotoImportResult {
        if (selected.uri.scheme != "content") return AndroidPhotoImportResult.Failed(AndroidPhotoImportFailure.PROVIDER_UNAVAILABLE)
        val context = currentCoroutineContext()
        return try {
            runInterruptible(Dispatchers.IO) {
                resolver.openInputStream(selected.uri)?.use { input ->
                    importStream(input, Build.VERSION.SDK_INT) { context.ensureActive() }
                } ?: AndroidPhotoImportResult.Failed(AndroidPhotoImportFailure.PROVIDER_UNAVAILABLE)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            AndroidPhotoImportResult.Failed(AndroidPhotoImportFailure.PROVIDER_UNAVAILABLE)
        } catch (_: IOException) {
            AndroidPhotoImportResult.Failed(AndroidPhotoImportFailure.PROVIDER_UNAVAILABLE)
        } catch (_: RuntimeException) {
            AndroidPhotoImportResult.Failed(AndroidPhotoImportFailure.PROVIDER_UNAVAILABLE)
        }
    }

    internal fun importStream(input: InputStream, apiLevel: Int, checkCancellation: () -> Unit = {}): AndroidPhotoImportResult {
        var source: ByteArray? = null
        return try {
            source = readBoundedPhoto(input, policy.maxSourceBytes, checkCancellation)
            normalize(source, apiLevel, checkCancellation)
        } catch (failure: PhotoValidationException) {
            AndroidPhotoImportResult.Failed(when (failure.reason) {
                ContainerFailure.MALFORMED -> AndroidPhotoImportFailure.MALFORMED
                ContainerFailure.UNSUPPORTED -> AndroidPhotoImportFailure.UNSUPPORTED_FORMAT
                ContainerFailure.LIMIT -> AndroidPhotoImportFailure.LIMIT_EXCEEDED
            })
        } finally {
            source?.fill(0)
        }
    }

    private fun normalize(source: ByteArray, apiLevel: Int, checkCancellation: () -> Unit): AndroidPhotoImportResult {
        checkCancellation()
        val contentType = when {
            source.size >= 8 && source.take(8).toByteArray().contentEquals(AndroidPhotoContainerValidator.PNG_SIGNATURE) -> "image/png"
            source.size >= 2 && source[0] == 255.toByte() && source[1] == 216.toByte() -> "image/jpeg"
            else -> throw PhotoValidationException(ContainerFailure.UNSUPPORTED)
        }
        val container = AndroidPhotoContainerValidator(policy, checkCancellation).inspect(source, contentType)
        val sanitized = stripSourceMetadata(source, container.format)
        val size = targetSize(container.width, container.height)
        var decoded: Bitmap? = null
        var oriented: Bitmap? = null
        var normalized: Bitmap? = null
        try {
            checkCancellation()
            decoded = if (Build.VERSION.SDK_INT >= 28 && apiLevel >= 28) decodeStrict(sanitized, container, size)
                else decodeCompleteLegacyPhoto(sanitized, contentType, policy, policy.outputMaxEdge, checkCancellation)
            checkCancellation()
            photoValid(decoded.width in 1..policy.outputMaxEdge && decoded.height in 1..policy.outputMaxEdge)
            oriented = orient(decoded, container.orientation)
            normalized = createBitmap(oriented.width, oriented.height, Bitmap.Config.ARGB_8888)
            Canvas(normalized).apply {
                drawColor(Color.WHITE) // explicitly flatten PNG alpha; never inherit source EXIF
                drawBitmap(oriented, 0f, 0f, null)
            }
            for (quality in intArrayOf(90, 75, 60)) {
                checkCancellation()
                val output = PhotoCappedOutput(policy.maxOutputBytes)
                val compressed = try { normalized.compress(Bitmap.CompressFormat.JPEG, quality, output) }
                catch (_: PhotoOutputLimit) { false }
                if (compressed && !output.exceeded) {
                    val bytes = output.bytes()
                    photoValid(bytes.isNotEmpty())
                    checkCancellation()
                    return AndroidPhotoImportResult.Imported(AndroidImportedPhoto(normalized.width, normalized.height, bytes))
                }
                if (!output.exceeded) throw PhotoValidationException(ContainerFailure.MALFORMED)
            }
            return AndroidPhotoImportResult.Failed(AndroidPhotoImportFailure.LIMIT_EXCEEDED)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PhotoValidationException) {
            throw failure
        } catch (_: IOException) {
            return AndroidPhotoImportResult.Failed(AndroidPhotoImportFailure.MALFORMED)
        } catch (_: RuntimeException) {
            return AndroidPhotoImportResult.Failed(AndroidPhotoImportFailure.MALFORMED)
        } finally {
            sanitized.fill(0)
            normalized?.recycle()
            if (oriented !== decoded) oriented?.recycle()
            decoded?.recycle()
        }
    }

    private fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        val edge = maxOf(width, height)
        if (edge <= policy.outputMaxEdge) return width to height
        return maxOf(1, (width.toLong() * policy.outputMaxEdge / edge).toInt()) to
            maxOf(1, (height.toLong() * policy.outputMaxEdge / edge).toInt())
    }

    @RequiresApi(28)
    private fun decodeStrict(source: ByteArray, container: PhotoContainer, size: Pair<Int, Int>): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(source))) { decoder, info, _ ->
            photoValid(info.size.width == container.width && info.size.height == container.height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            decoder.setTargetSize(size.first, size.second)
            decoder.setOnPartialImageListener { false }
        }

    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            1 -> return bitmap
            2 -> matrix.setScale(-1f, 1f)
            3 -> matrix.setRotate(180f)
            4 -> matrix.setScale(1f, -1f)
            5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            6 -> matrix.setRotate(90f)
            7 -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
            8 -> matrix.setRotate(270f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}

internal fun readBoundedPhoto(input: InputStream, limit: Int, checkCancellation: () -> Unit = {}): ByteArray {
    val output = ByteArrayOutputStream(minOf(8192, limit))
    val buffer = ByteArray(minOf(8192, limit + 1))
    try {
        while (true) {
            checkCancellation()
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
            if (count < 0) break
            if (count == 0) {
                val next = input.read()
                if (next < 0) break
                photoLimit(output.size() < limit)
                output.write(next)
            } else {
                photoLimit(count <= limit - output.size())
                output.write(buffer, 0, count)
            }
        }
        photoValid(output.size() > 0)
        return output.toByteArray()
    } finally { buffer.fill(0) }
}

/** Called only after complete bounded container validation. Keeps coding data, not metadata. */
internal fun stripSourceMetadata(source: ByteArray, format: String): ByteArray {
    val out = ByteArrayOutputStream(source.size)
    if (format == "png") {
        out.write(source, 0, 8)
        var pos = 8
        while (pos < source.size) {
            val length = AndroidPhotoContainerValidator.uint32(source, pos).toInt()
            val type = String(source, pos + 4, 4, Charsets.US_ASCII)
            if (type in setOf("IHDR", "PLTE", "tRNS", "IDAT", "IEND")) out.write(source, pos, length + 12)
            pos += length + 12
        }
    } else {
        out.write(source, 0, 2)
        var pos = 2; var entropy = false
        while (pos < source.size) {
            if (entropy) {
                val begin = pos
                while (pos < source.size) {
                    if (AndroidPhotoContainerValidator.u8(source, pos++) != 255) continue
                    while (pos < source.size && AndroidPhotoContainerValidator.u8(source, pos) == 255) pos++
                    val marker = AndroidPhotoContainerValidator.u8(source, pos)
                    if (marker == 0 || marker in 208..215) { pos++; continue }
                    pos--; entropy = false; break
                }
                out.write(source, begin, pos - begin)
            }
            val begin = pos++
            while (AndroidPhotoContainerValidator.u8(source, pos) == 255) pos++
            val marker = AndroidPhotoContainerValidator.u8(source, pos++)
            if (marker == 217) { out.write(source, begin, pos - begin); break }
            val length = AndroidPhotoContainerValidator.uint16(source, pos)
            if (marker !in 224..239 && marker != 254) out.write(source, begin, pos + length - begin)
            pos += length
            if (marker == 218) entropy = true
        }
    }
    return out.toByteArray()
}

private class PhotoOutputLimit : IOException()
private class PhotoCappedOutput(private val limit: Int) : OutputStream() {
    private val output = ByteArrayOutputStream(minOf(8192, limit))
    var exceeded = false
        private set
    override fun write(value: Int) {
        if (output.size() >= limit) { exceeded = true; throw PhotoOutputLimit() }
        output.write(value)
    }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (length > limit - output.size()) { exceeded = true; throw PhotoOutputLimit() }
        output.write(bytes, offset, length)
    }
    fun bytes(): ByteArray = output.toByteArray()
}
