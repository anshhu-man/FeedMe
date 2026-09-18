package com.feedme.server.media.processing.codec

/** Explicit application limits, not a new canonical upload contract or paid quota.
 * The first writer profile is PNG: RGB/ARGB pixels, no inherited metadata. */
data class PhotoProcessingPolicy(
    val revision: String,
    val maxSourceBytes: Int,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxPixels: Long,
    val maxWorkingBytes: Long,
    val maxContainerParts: Int,
    val maxMetadataBytes: Int,
    val maxExifEntries: Int,
    val thumbnailEdge: Int,
    val displayEdge: Int,
    val maxDerivativeBytes: Int,
    val maxCombinedDerivativeBytes: Int,
    val outputContentType: String,
) {
    init {
        require(revision.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")))
        require(maxSourceBytes in 1..10_000_000)
        require(maxWidth in 1..65_535 && maxHeight in 1..65_535)
        require(maxPixels in 1..maxWidth.toLong() * maxHeight)
        require(maxWorkingBytes in 1..Int.MAX_VALUE.toLong())
        require(maxContainerParts in 1..65_536 && maxMetadataBytes in 0..maxSourceBytes)
        require(maxExifEntries in 1..4096)
        require(thumbnailEdge in 1..displayEdge && displayEdge <= maxOf(maxWidth, maxHeight))
        require(maxDerivativeBytes in 1..100_000_000)
        require(maxCombinedDerivativeBytes.toLong() in maxDerivativeBytes.toLong()..2L * maxDerivativeBytes)
        require(outputContentType == "image/png")
    }

    internal fun checkRaster(width: Int, height: Int, sourceBytes: Int) {
        photoLimit(width in 1..maxWidth && height in 1..maxHeight)
        val pixels = width.toLong() * height
        photoLimit(pixels <= maxPixels)
        // Conservative simultaneous raster/conversion/scanline/encoded-buffer allowance.
        // This is an admission estimate, NOT an RSS sandbox; the child also has a heap cap.
        photoLimit(pixels * 32 + sourceBytes.toLong() * 3 + maxCombinedDerivativeBytes.toLong() * 3 <= maxWorkingBytes)
    }
}

internal enum class ContainerFailure { MALFORMED, UNSUPPORTED, LIMIT }
internal class PhotoValidationException(val reason: ContainerFailure) : RuntimeException(reason.name, null, false, false)
internal fun photoValid(condition: Boolean) { if (!condition) throw PhotoValidationException(ContainerFailure.MALFORMED) }
internal fun photoSupported(condition: Boolean) { if (!condition) throw PhotoValidationException(ContainerFailure.UNSUPPORTED) }
internal fun photoLimit(condition: Boolean) { if (!condition) throw PhotoValidationException(ContainerFailure.LIMIT) }

internal data class PhotoContainer(
    val format: String,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val alpha: Boolean,
)
