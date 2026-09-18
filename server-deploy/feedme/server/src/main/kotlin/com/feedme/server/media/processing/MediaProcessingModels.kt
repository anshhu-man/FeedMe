package com.feedme.server.media.processing

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import com.feedme.server.media.LEGACY_MEDIA_PROTOCOL

/** Codec output is private data, never readiness, moderation, publication or an object-store grant. */
interface PhotoCodec {
    val revision: String
    fun decode(source: ByteArray, contentType: String): PhotoDecodeResult
}
enum class PhotoVariant(val wire: String) { THUMBNAIL("thumbnail"), DISPLAY("display") }
enum class PhotoRejection { MALFORMED_IMAGE, UNSUPPORTED_FORMAT, IMAGE_LIMIT_EXCEEDED }
enum class PhotoCodecUnavailable { NOT_CONFIGURED, AT_CAPACITY, PROCESS_TIMEOUT, PROCESS_FAILED }
sealed class PhotoDecodeResult {
    class Decoded(variants: List<EncodedPhotoVariant>) : PhotoDecodeResult() {
        private val values = variants.toList()
        val variants get() = values.toList()
        override fun toString() = "Decoded(<redacted>)"
    }
    class Rejected(val reason: PhotoRejection) : PhotoDecodeResult()
    class Unavailable(val reason: PhotoCodecUnavailable) : PhotoDecodeResult()
}
class EncodedPhotoVariant(val variant: PhotoVariant, val contentType: String, val width: Int, val height: Int, bytes: ByteArray) {
    private val value = bytes.copyOf()
    val byteCount: Int get() = value.size
    fun copyBytes(): ByteArray = value.copyOf()
    override fun toString() = "EncodedPhotoVariant(<redacted>)"
}

/** No operational defaults. Resource/provider failures never become a content verdict. */
class MediaProcessingPolicy(val revision: String, val codecRevision: String, val leaseSeconds: Int,
    val maxAttempts: Int, val retrySeconds: Int, val writeAcceptanceSeconds: Int,
    val maxSourceBytes: Int, val maxDerivativeBytes: Int, val maxCombinedDerivativeBytes: Int,
    val maxDimension: Int, val maxObjectVersionBytes: Int, val maxVersionsPerKey: Int, val maxManifestBytes: Int) {
    init {
        require(revision.matches(REVISION) && codecRevision.matches(REVISION))
        require(leaseSeconds in 1..300 && maxAttempts in 1..20 && retrySeconds in 1..3600 && writeAcceptanceSeconds in 1..300)
        require(maxSourceBytes in 1..10_000_000 && maxDerivativeBytes in 1..10_000_000 && maxCombinedDerivativeBytes in 1..20_000_000)
        require(maxCombinedDerivativeBytes >= maxDerivativeBytes && maxDimension in 1..100_000)
        require(maxObjectVersionBytes in 1..4096 && maxVersionsPerKey in 1..1000 && maxManifestBytes in 1..65536)
    }
    override fun toString() = "MediaProcessingPolicy(<redacted>)"
}

enum class MediaProcessingFailureCode { INVALID_EVENT, CONFLICT, STALE_LEASE, NOT_CONFIGURED,
    STORAGE_UNAVAILABLE, OBJECT_UNAVAILABLE, OBJECT_MISMATCH, SAFETY_UNAVAILABLE, LIMIT_EXCEEDED }
class MediaProcessingFailure(val code: MediaProcessingFailureCode) : RuntimeException("Media processing unavailable: ${code.name}")

class MediaProcessingOwner(val environment: String, val ownerId: UUID) {
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }
    override fun toString() = "MediaProcessingOwner(<redacted>)"
}
/** Database-pinned immutable input, not caller-supplied object access. */
class MediaProcessingSource internal constructor(val owner: MediaProcessingOwner, val mediaId: UUID,
    val draftId: UUID, val draftGeneration: Long, val completionKey: UUID, val completionVersion: Long,
    val acceptedAt: Instant, val objectKey: String, val objectVersionId: String?, val sha256: String,
    val bytes: Long, val contentType: String, val reservationDeadline: Instant,
    val storageProtocol: String = LEGACY_MEDIA_PROTOCOL, val bucket: String? = null) {
    override fun toString() = "MediaProcessingSource(<redacted>)"
}
class MediaProcessingLease internal constructor(val jobId: UUID, val token: UUID, val generation: Long,
    val attempt: Int, val source: MediaProcessingSource) {
    override fun toString() = "MediaProcessingLease(<redacted>)"
}
class MediaDerivativeIntent internal constructor(val id: UUID, val jobId: UUID, val source: MediaProcessingSource,
    val variant: PhotoVariant, val objectKey: String, val sha256: String, val bytes: Long,
    val contentType: String, val width: Int, val height: Int, val acceptanceDeadline: Instant,
    val objectVersionId: String?, val storageProtocol: String = source.storageProtocol,
    val bucket: String? = source.bucket, val writeAttemptedAt: Instant? = null, val acknowledgedAt: Instant? = null) {
    override fun toString() = "MediaDerivativeIntent(<redacted>)"
}
class MediaDerivativeReceipt(val objectKey: String, val objectVersionId: String, val sha256: String,
    val bytes: Long, val contentType: String) {
    override fun toString() = "MediaDerivativeReceipt(<redacted>)"
}
enum class MediaProcessingTerminal { READY, REJECTED, CANCELLED }
class MediaProcessingReceipt internal constructor(val jobId: UUID, val result: MediaProcessingTerminal,
    val mediaVersion: Long, val eventId: UUID?) {
    override fun toString() = "MediaProcessingReceipt(<redacted>)"
}
enum class MediaSafetyRejection { MALWARE_DETECTED, CONTENT_REJECTED }
/** Mandatory adapter evidence asserts BOTH malware and content approval for these exact hashes.
 * It is revalidated under the current lifecycle/policy lock before READY; construction alone grants nothing. */
class MediaSafetyEvidence(val receiptId: String, val revision: String, val sourceSha256: String,
    derivativeSha256: Map<PhotoVariant, String>, val assessedAt: Instant, val validUntil: Instant) {
    val derivativeSha256 = derivativeSha256.toMap()
    init {
        require(receiptId.matches(Regex("[A-Za-z0-9_-]{1,128}")) && revision.matches(REVISION))
        require(sourceSha256.matches(HASH) && this.derivativeSha256.keys == PhotoVariant.entries.toSet())
        require(this.derivativeSha256.values.all { it.matches(HASH) } && validUntil > assessedAt)
    }
    override fun toString() = "MediaSafetyEvidence(<redacted>)"
}
sealed class MediaSafetyResult {
    class Approved(val evidence: MediaSafetyEvidence) : MediaSafetyResult()
    class Rejected(val reason: MediaSafetyRejection) : MediaSafetyResult()
    data object Pending : MediaSafetyResult()
    data object Unavailable : MediaSafetyResult()
}
enum class MediaWorkerPurpose { PROCESS, CLEANUP }
class MediaCleanupLease internal constructor(val id: UUID, val token: UUID, val generation: Long,
    val owner: MediaProcessingOwner, val mediaId: UUID, val objectKey: String, val notBefore: Instant,
    val derivativeIntentId: UUID?, val storageProtocol: String = LEGACY_MEDIA_PROTOCOL, val bucket: String? = null) {
    override fun toString() = "MediaCleanupLease(<redacted>)"
}
/** Authoritative, bounded exact-key inventory AFTER all issued write acceptances have expired.
 * A provider adapter must not return this while a late write can still be accepted. */
class SettledMediaVersions(val objectKey: String, versionIds: List<String>) {
    val versionIds = versionIds.toList()
    override fun toString() = "SettledMediaVersions(<redacted>)"
}
internal val REVISION = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}")
internal val HASH = Regex("[0-9a-f]{64}")
internal fun processingHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun processingFail(code: MediaProcessingFailureCode): Nothing = throw MediaProcessingFailure(code)
