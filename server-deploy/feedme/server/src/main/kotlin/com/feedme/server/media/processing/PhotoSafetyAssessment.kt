package com.feedme.server.media.processing

import com.feedme.server.media.processing.codec.PhotoContainerValidator
import com.feedme.server.media.processing.codec.PhotoProcessingPolicy
import com.feedme.server.media.processing.codec.PhotoValidationException
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64

/** Positive proof that the private derivatives passed the exact local decode/re-encode CDR
 * profile. It is not content moderation and grants no storage or publication authority. */
internal class PhotoContentDisarmEvidence(
    val revision: String,
    val sourceSha256: String,
    derivativeSha256: Map<PhotoVariant, String>,
    val assessedAt: Instant,
) {
    val derivativeSha256 = derivativeSha256.toMap()
    init {
        require(revision.matches(REVISION) && sourceSha256.matches(HASH))
        require(this.derivativeSha256.keys == PhotoVariant.entries.toSet())
        require(this.derivativeSha256.values.all { it.matches(HASH) })
    }
    override fun toString() = "PhotoContentDisarmEvidence(<redacted>)"
}

internal sealed interface PhotoContentDisarmResult {
    class Approved(val evidence: PhotoContentDisarmEvidence) : PhotoContentDisarmResult
    data object Unavailable : PhotoContentDisarmResult
}

internal fun interface PhotoContentDisarm {
    fun assess(source: MediaProcessingSource, derivatives: List<EncodedPhotoVariant>): PhotoContentDisarmResult
}

/**
 * Revalidates the only bytes that can leave private source storage. The source has already been
 * parsed in an isolated child, decoded to pixels, metadata-discarded, resized and re-encoded by
 * the pinned [PhotoProcessingPolicy]. This second parent-side pass permits only exact-profile PNG
 * containers with IHDR/IDAT/IEND, complete zlib data, no metadata, no trailing bytes and the two
 * required variants. Original source bytes are never published by the media pipeline.
 */
internal class StrictDecodedPhotoContentDisarm(
    private val policy: PhotoProcessingPolicy,
    private val clock: Clock,
) : PhotoContentDisarm {
    private val validator = PhotoContainerValidator(policy)

    override fun assess(source: MediaProcessingSource,
        derivatives: List<EncodedPhotoVariant>): PhotoContentDisarmResult {
        if (source.contentType !in setOf("image/jpeg", "image/png") ||
            source.bytes !in 1..policy.maxSourceBytes.toLong() || !source.sha256.matches(HASH) ||
            derivatives.size != PhotoVariant.entries.size ||
            derivatives.map { it.variant }.toSet() != PhotoVariant.entries.toSet())
            return PhotoContentDisarmResult.Unavailable

        val hashes = linkedMapOf<PhotoVariant, String>()
        var combined = 0L
        return try {
            for (variant in PhotoVariant.entries) {
                val derivative = derivatives.single { it.variant == variant }
                val maximumEdge = if (variant == PhotoVariant.THUMBNAIL) policy.thumbnailEdge else policy.displayEdge
                if (derivative.contentType != policy.outputContentType ||
                    derivative.width !in 1..maximumEdge || derivative.height !in 1..maximumEdge ||
                    derivative.byteCount !in 1..policy.maxDerivativeBytes)
                    return PhotoContentDisarmResult.Unavailable
                combined += derivative.byteCount
                if (combined > policy.maxCombinedDerivativeBytes) return PhotoContentDisarmResult.Unavailable
                val bytes = derivative.copyBytes()
                try {
                    val inspected = validator.inspect(bytes, policy.outputContentType, derivative = true)
                    if (inspected.width != derivative.width || inspected.height != derivative.height ||
                        inspected.orientation != 1)
                        return PhotoContentDisarmResult.Unavailable
                    hashes[variant] = sha256(bytes)
                } finally { bytes.fill(0) }
            }
            PhotoContentDisarmResult.Approved(PhotoContentDisarmEvidence(
                revision = "${policy.revision}-cdr",
                sourceSha256 = source.sha256,
                derivativeSha256 = hashes,
                assessedAt = clock.instant(),
            ))
        } catch (_: PhotoValidationException) {
            PhotoContentDisarmResult.Unavailable
        } catch (_: IllegalArgumentException) {
            PhotoContentDisarmResult.Unavailable
        }
    }

    override fun toString() = "StrictDecodedPhotoContentDisarm(<redacted>)"
}

internal data class ComposedPhotoSafetyPolicy(
    val revision: String = "feedme-photo-cdr-moderation-v1",
    val requiredDisarmRevision: String,
    val requiredModerationRevision: String = "cloudflare-moondream-photo-moderation-v1",
    val maximumObservationAgeSeconds: Long = 30,
    val evidenceValiditySeconds: Long = 300,
) {
    init {
        require(revision.matches(REVISION) && requiredDisarmRevision.matches(REVISION) &&
            requiredModerationRevision.matches(REVISION))
        require(maximumObservationAgeSeconds in 1..300 && evidenceValiditySeconds in 1..3_600)
    }
}

/**
 * Complete local safety composition for publishable derivatives. CDR is evaluated first; only its
 * validated DISPLAY bytes may cross to the content provider. An approval is issued only when both
 * observations are current, revision-pinned and hash-identical to the exact pipeline outputs.
 */
internal class ComposedPhotoSafetyAssessment(
    private val disarm: PhotoContentDisarm,
    private val moderation: PhotoContentModeration,
    private val policy: ComposedPhotoSafetyPolicy,
    private val clock: Clock,
) : MediaSafetyAssessment {
    override fun assess(source: MediaProcessingSource,
        derivatives: List<EncodedPhotoVariant>): MediaSafetyResult {
        val cdr = when (val result = disarm.assess(source, derivatives)) {
            is PhotoContentDisarmResult.Approved -> result.evidence
            PhotoContentDisarmResult.Unavailable -> return MediaSafetyResult.Unavailable
        }
        if (cdr.revision != policy.requiredDisarmRevision || cdr.sourceSha256 != source.sha256 ||
            cdr.derivativeSha256.keys != PhotoVariant.entries.toSet()) return MediaSafetyResult.Unavailable
        val display = derivatives.singleOrNull { it.variant == PhotoVariant.DISPLAY }
            ?: return MediaSafetyResult.Unavailable
        val content = when (val result = moderation.assess(display)) {
            is PhotoContentModerationResult.Approved -> result
            is PhotoContentModerationResult.Rejected ->
                return MediaSafetyResult.Rejected(MediaSafetyRejection.CONTENT_REJECTED)
            is PhotoContentModerationResult.Unavailable -> return MediaSafetyResult.Unavailable
        }
        val now = clock.instant()
        if (content.revision != policy.requiredModerationRevision ||
            content.observationSha256 != cdr.derivativeSha256[PhotoVariant.DISPLAY] ||
            !current(cdr.assessedAt, now) || !current(content.assessedAt, now))
            return MediaSafetyResult.Unavailable

        val receipt = receipt(source.sha256, cdr.derivativeSha256, cdr.revision, content.revision, now)
        return MediaSafetyResult.Approved(MediaSafetyEvidence(
            receiptId = receipt,
            revision = policy.revision,
            sourceSha256 = source.sha256,
            derivativeSha256 = cdr.derivativeSha256,
            assessedAt = now,
            validUntil = now.plusSeconds(policy.evidenceValiditySeconds),
        ))
    }

    private fun current(observed: Instant, now: Instant) = !observed.isAfter(now) &&
        !observed.isBefore(now.minusSeconds(policy.maximumObservationAgeSeconds))

    override fun toString() = "ComposedPhotoSafetyAssessment(<redacted>)"
}

private fun receipt(source: String, derivatives: Map<PhotoVariant, String>, disarmRevision: String,
    moderationRevision: String, assessedAt: Instant): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (value in listOf(source, derivatives.getValue(PhotoVariant.THUMBNAIL),
        derivatives.getValue(PhotoVariant.DISPLAY), disarmRevision, moderationRevision,
        assessedAt.toString())) {
        digest.update(value.toByteArray(Charsets.UTF_8))
        digest.update(0)
    }
    return "ps_" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest())
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }
