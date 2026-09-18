package com.feedme.server.media

import java.io.InputStream
import java.time.Instant
import java.util.UUID

/** Explicit provider protocol, not a provider object version or an authorization grant. */
const val SUPABASE_MEDIA_PROTOCOL = "supabaseSignedUploadV1"
const val LEGACY_MEDIA_PROTOCOL = "legacyVersioned"

/**
 * Required, explicitly configured external I/O. No accepting/default implementation.
 * Call only OUTSIDE principal/draft/media database transactions. The store records a durable
 * issuance intent before minting, then rechecks current authority before delivering a capability.
 *
 * A signed token admits a non-upsert write to an owned destination; it does not enforce FeedMe's
 * byte count, MIME or hash. verify/read must download complete bounded bytes and independently
 * check all three. A key or digest must NEVER be presented as an immutable provider version.
 * No derivative readiness, publication, write-settlement or deletion promise is supplied here.
 */
interface SupabaseMediaStorage {
    val bucket: String
    fun mint(request: SupabaseUploadRequest): SupabaseUploadCapability
    fun verify(request: SupabaseObjectRequest): VerifiedSupabaseObject
    /** Complete, bounded, digest-verified bytes; processor independently rehashes each read. */
    fun read(request: SupabaseObjectRequest): InputStream
}

class SupabaseUploadRequest(val environment: String, val ownerId: UUID, val mediaId: UUID,
    val objectKey: String, val expectedBytes: Long, val contentType: String, val sha256: String,
    val expiresNoLaterThan: Instant) {
    init { validateSupabaseBinding(environment, mediaId, objectKey, expectedBytes, contentType, sha256) }
    override fun toString() = "SupabaseUploadRequest(<redacted>)"
}

class SupabaseObjectRequest(val environment: String, val ownerId: UUID, val mediaId: UUID,
    val objectKey: String, val expectedBytes: Long, val contentType: String, val sha256: String) {
    init { validateSupabaseBinding(environment, mediaId, objectKey, expectedBytes, contentType, sha256) }
    override fun toString() = "SupabaseObjectRequest(<redacted>)"
}

class SupabaseUploadCapability(val url: String, val expiresAt: Instant) {
    override fun toString() = "SupabaseUploadCapability(<redacted>)"
}

/** Exact observed content only, not moderation evidence or immutable-version attestation. */
class VerifiedSupabaseObject(val objectKey: String, val bytes: Long, val contentType: String, val sha256: String) {
    override fun toString() = "VerifiedSupabaseObject(<redacted>)"
}

private fun validateSupabaseBinding(environment: String, mediaId: UUID, objectKey: String,
    expectedBytes: Long, contentType: String, sha256: String) {
    require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
    require(expectedBytes in 1..10_000_000 && contentType in setOf("image/jpeg", "image/png"))
    require(sha256.matches(Regex("[0-9a-f]{64}")))
    // Server-generated destinations only. No encoded slash, traversal, query or caller namespace.
    val parts = objectKey.split('/')
    require(parts.size == 4 && parts[0] == "quarantine" && parts[1] == environment && parts[3] == mediaId.toString())
    require(parts[2].matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
}
