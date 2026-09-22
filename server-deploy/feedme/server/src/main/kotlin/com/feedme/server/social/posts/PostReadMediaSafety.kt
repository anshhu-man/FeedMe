package com.feedme.server.social.posts

import com.feedme.server.media.processing.MediaProcessingFailure
import com.feedme.server.media.processing.MediaProcessingFailureCode
import com.feedme.server.media.processing.MediaSafetyRecords
import com.feedme.server.media.processing.PostMediaReadVerifier
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime

/** Explicit deployment policy, not a value learned from a post, READY flag or old receipt. */
class PostReadMediaSafetyPolicy(
    val processingRevision: String,
    val codecRevision: String,
    val safetyRevision: String,
) {
    init {
        require(listOf(processingRevision, codecRevision, safetyRevision).all {
            it.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}"))
        })
    }
    override fun toString() = "PostReadMediaSafetyPolicy(<redacted>)"
}

/** Media-only dependency for the mandatory PostReadContentAuthority, not a substitute for
 * current account/audience, post moderation, attachment rights or feature authorization.
 * Uses the reader's transaction and returns only the earliest actual assessment deadline.
 * No object key, original bytes, provider capability or author's device is issued or invented.
 * A private Supabase HOLD alone remains unavailable; V056 readiness must also verify.
 */
class PostReadMediaSafety(environment: String, private val policy: PostReadMediaSafetyPolicy) {
    private val verifier = PostMediaReadVerifier(environment)

    fun requireCurrent(connection: Connection, material: PostReadMaterial): Instant? {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Post media read interrupted")
        require(!connection.isClosed && !connection.autoCommit &&
            connection.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
        if (material.media.map { it.id }.distinct().size != material.media.size)
            throw PostReadFailure(PostReadFailureCode.STORAGE_UNAVAILABLE)
        var earliest: Instant? = null
        try {
            for (media in material.media.sortedBy { it.id.toString() }) {
                val exact = verifier.verify(connection, material.ownerId, media.id, media.version, media.derivatives)
                if (exact.policyRevision != policy.processingRevision || exact.codecRevision != policy.codecRevision)
                    throw PostReadFailure(PostReadFailureCode.POST_UNAVAILABLE)
                val current = MediaSafetyRecords.requireCurrent(connection, exact.source, exact.jobId,
                    policy.processingRevision, policy.codecRevision, policy.safetyRevision,
                    exact.outputs, exact.stage, exact.safetyMediaVersion)
                earliest = earliest?.let { minOf(it, current.validUntil) } ?: current.validUntil
            }
            // Later media locks may wait after an earlier grant was checked. The owning
            // post reader additionally intersects this deadline with all its other grants.
            if (earliest != null) {
                val at = connection.prepareStatement("SELECT clock_timestamp()").use { statement ->
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        rows.getObject(1, OffsetDateTime::class.java).toInstant()
                    }
                }
                if (at >= earliest) throw PostReadFailure(PostReadFailureCode.POST_UNAVAILABLE)
            }
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Post media read interrupted")
            return earliest
        } catch (failure: MediaProcessingFailure) {
            throw PostReadFailure(when (failure.code) {
                MediaProcessingFailureCode.CONFLICT, MediaProcessingFailureCode.OBJECT_MISMATCH,
                MediaProcessingFailureCode.SAFETY_UNAVAILABLE -> PostReadFailureCode.POST_UNAVAILABLE
                MediaProcessingFailureCode.NOT_CONFIGURED -> PostReadFailureCode.NOT_CONFIGURED
                else -> PostReadFailureCode.STORAGE_UNAVAILABLE
            }).also { mapped -> failure.suppressed.forEach(mapped::addSuppressed) }
        }
    }

    override fun toString() = "PostReadMediaSafety(<redacted>)"
}
