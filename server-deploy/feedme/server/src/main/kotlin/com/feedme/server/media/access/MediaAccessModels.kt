package com.feedme.server.media.access

import com.feedme.server.media.processing.MediaDerivativeIntent
import com.feedme.server.media.processing.document
import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonObject

/** One explicitly configured public backend origin. This does not configure a bucket,
 * worker, safety service, proxy cache or cross-instance capability store. */
class AccountMediaAccessPolicy(val enabled: Boolean, val deliveryOrigin: String,
    val lifetimeSeconds: Int, val maxObjectBytes: Int, val maxRetainedBytes: Long,
    val maxItems: Int, val maxConcurrentFetches: Int) {
    init {
        val origin = URI(deliveryOrigin)
        require(deliveryOrigin.length <= 253 && origin.scheme == "https" && origin.host != null &&
            origin.host == origin.host.lowercase() && origin.host.contains('.') &&
            origin.host.matches(Regex("[a-z0-9.-]+")) && origin.port == -1 &&
            origin.rawUserInfo == null && origin.rawQuery == null && origin.rawFragment == null &&
            origin.rawPath.isEmpty() && deliveryOrigin == "https://${origin.host}")
        require(lifetimeSeconds in 1..60 && maxObjectBytes in 1..10_000_000 &&
            maxRetainedBytes in (2L * maxObjectBytes)..268_435_456L && maxItems in 1..1024 &&
            maxConcurrentFetches in 1..8)
    }
    override fun toString() = "AccountMediaAccessPolicy(<redacted>)"
}

enum class MediaAccessFailureCode(val status: Int) {
    INPUT_INVALID(422), UNAUTHENTICATED(401), MEDIA_UNAVAILABLE(404), CAPACITY_EXCEEDED(429),
    NOT_CONFIGURED(503), STORAGE_UNAVAILABLE(503),
}
class MediaAccessFailure(val code: MediaAccessFailureCode) : RuntimeException("Media access unavailable: ${code.name}")

/** Actual locked viewer/post/media observation, never a caller-selected object descriptor. */
internal class PostMediaAccessObservation(val viewerId: UUID, val ownerId: UUID, val postId: UUID,
    val postVersion: Long, val aclVersion: Long, val mediaVersion: Long, val surface: String,
    private val post: JsonObject, val output: MediaDerivativeIntent, val validUntil: Instant,
    val observedAt: Instant) {
    fun sameBinding(other: PostMediaAccessObservation): Boolean = viewerId == other.viewerId &&
        ownerId == other.ownerId && postId == other.postId && postVersion == other.postVersion &&
        aclVersion == other.aclVersion && mediaVersion == other.mediaVersion && surface == other.surface &&
        post == other.post && output.id == other.output.id && output.jobId == other.output.jobId &&
        output.source.document() == other.output.source.document() && output.variant == other.output.variant &&
        output.objectKey == other.output.objectKey && output.sha256 == other.output.sha256 &&
        output.bytes == other.output.bytes && output.contentType == other.output.contentType &&
        output.width == other.output.width && output.height == other.output.height &&
        output.storageProtocol == other.output.storageProtocol && output.bucket == other.output.bucket &&
        output.objectVersionId == other.output.objectVersionId && output.acceptanceDeadline == other.output.acceptanceDeadline &&
        output.writeAttemptedAt == other.output.writeAttemptedAt && output.acknowledgedAt == other.output.acknowledgedAt
    override fun toString() = "PostMediaAccessObservation(<redacted>)"
}
