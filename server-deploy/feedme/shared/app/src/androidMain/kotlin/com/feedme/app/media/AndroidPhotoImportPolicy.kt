package com.feedme.app.media

/** Local import admission limits, not an upload grant or a decoder process/RSS sandbox. */
data class AndroidPhotoImportPolicy(
    val maxSourceBytes: Int = 10_000_000,
    val maxWidth: Int = 16_384,
    val maxHeight: Int = 16_384,
    val maxPixels: Long = 24_000_000,
    val outputMaxEdge: Int = 2_048,
    val maxOutputBytes: Int = 786_432,
) {
    init {
        require(maxSourceBytes in 1..10_000_000)
        require(maxWidth in 1..16_384 && maxHeight in 1..16_384)
        require(maxPixels in 1..24_000_000)
        require(outputMaxEdge in 1..2_048)
        require(maxOutputBytes in 1..786_432)
    }

    internal val maxDerivativeBytes get() = maxOutputBytes
    internal val maxContainerParts = 4_096
    internal val maxMetadataBytes get() = minOf(maxSourceBytes, 1_048_576)
    internal val maxExifEntries = 256
    // Exact PNG scanlines are streamed, never allocated as one decoded source raster.
    internal val maxWorkingBytes get() = maxPixels * 8 + maxHeight * 8L
    internal fun checkRaster(width: Int, height: Int, sourceBytes: Int) {
        photoLimit(width in 1..maxWidth && height in 1..maxHeight)
        photoLimit(width.toLong() * height <= maxPixels && sourceBytes <= maxSourceBytes)
    }
}

enum class AndroidPhotoImportFailure {
    PROVIDER_UNAVAILABLE, MALFORMED, UNSUPPORTED_FORMAT, UNSUPPORTED_DECODER, LIMIT_EXCEEDED,
}

sealed interface AndroidPhotoImportResult {
    class Imported(val photo: AndroidImportedPhoto) : AndroidPhotoImportResult {
        override fun toString() = "AndroidPhotoImportResult.Imported(<redacted>)"
    }
    data class Failed(val reason: AndroidPhotoImportFailure) : AndroidPhotoImportResult
}

/** Owns normalized, metadata-free JPEG bytes. No Uri, filename, source EXIF, or media ID. */
class AndroidImportedPhoto internal constructor(val width: Int, val height: Int, private val bytes: ByteArray) {
    val contentType: String get() = "image/jpeg"
    val byteCount: Int get() = bytes.size
    fun copyBytes(): ByteArray = bytes.copyOf()
    override fun toString() = "AndroidImportedPhoto(<redacted>)"
}

internal enum class ContainerFailure { MALFORMED, UNSUPPORTED, LIMIT }
internal class PhotoValidationException(val reason: ContainerFailure) : RuntimeException(reason.name, null, false, false)
internal fun photoValid(condition: Boolean) { if (!condition) throw PhotoValidationException(ContainerFailure.MALFORMED) }
internal fun photoSupported(condition: Boolean) { if (!condition) throw PhotoValidationException(ContainerFailure.UNSUPPORTED) }
internal fun photoLimit(condition: Boolean) { if (!condition) throw PhotoValidationException(ContainerFailure.LIMIT) }
internal data class PhotoContainer(val format: String, val width: Int, val height: Int, val orientation: Int, val alpha: Boolean)
