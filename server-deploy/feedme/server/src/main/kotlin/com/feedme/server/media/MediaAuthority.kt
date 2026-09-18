package com.feedme.server.media

import java.sql.Connection
import java.time.Instant
import java.util.UUID

/** Only actual verified account/device output. Guest and caller-selected owner identities are not accepted. */
class VerifiedMediaAccount(val environment: String, val accountId: UUID, val deviceSessionId: UUID) {
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }
    override fun toString() = "VerifiedMediaAccount(<redacted>)"
}

/**
 * Mandatory current database authority; no accepting/default implementation.
 * Callbacks use the supplied transaction and never commit, perform network I/O or log identifiers.
 * lockPrincipal locks current eligible account/device binding, shared with revocation/deletion.
 * Order is principal, command receipt, clientDraftId lifecycle, media. All future draft/publication/
 * attachment/deletion writers MUST share these locks. The lifecycle key is NOT a social PostDraft.
 * lockDraftLifecycle returns a positive stable incarnation (not each caption-edit revision), checking
 * any actual server draft's ownership and terminal state. forUpload=true also requires draft upload
 * eligibility; false permits owned observation/deletion even after draft discard or upload shutdown.
 * requireUploadEnabled checks current accepted community terms, moderation/account restrictions,
 * upload kill switch and configured admission limits. Only newReservation=true may consume quota.
 * requireUnattached locks actual attachment/publication authority and rejects any attached media.
 */
interface MediaAuthority {
    fun lockPrincipal(connection: Connection, actor: VerifiedMediaAccount)
    fun requireUploadEnabled(connection: Connection, actor: VerifiedMediaAccount, newReservation: Boolean)
    fun lockDraftLifecycle(connection: Connection, actor: VerifiedMediaAccount, clientDraftId: UUID, forUpload: Boolean): Long
    fun requireUnattached(connection: Connection, actor: VerifiedMediaAccount, mediaId: UUID)
}

/** All operational limits are explicit; canonical photo formats may be supported only when truly decodable. */
class MediaServicePolicy(val maxSourceBytes: Long, supportedContentTypes: Set<String>,
    val reservationLifetimeSeconds: Int, val capabilityLifetimeSeconds: Int,
    val maxCapabilityBytes: Int, val maxResponseBytes: Int, val maxObjectVersionBytes: Int,
    uploadOrigins: Set<String>) {
    val supportedContentTypes = supportedContentTypes.toSet()
    val uploadOrigins = uploadOrigins.toSet()
    init {
        require(maxSourceBytes in 1..10_000_000)
        require(this.supportedContentTypes.isNotEmpty() && this.supportedContentTypes.all { it in setOf("image/jpeg", "image/png", "image/heic") })
        require(reservationLifetimeSeconds in 1..86400 && capabilityLifetimeSeconds in 1..reservationLifetimeSeconds)
        require(maxCapabilityBytes in 1..65536 && maxResponseBytes in 1..262144 && maxObjectVersionBytes in 1..4096)
        require(this.uploadOrigins.isNotEmpty() && this.uploadOrigins.size <= 8)
        this.uploadOrigins.forEach { require(mediaOrigin(it) == it) }
    }
    override fun toString() = "MediaServicePolicy(<redacted>)"
}

enum class MediaFailureCode(val status: Int) {
    INPUT_INVALID(422), UNAUTHENTICATED(401), FORBIDDEN(403), MEDIA_UNAVAILABLE(404),
    MEDIA_CONFLICT(409), VERSION_CONFLICT(412), UPLOAD_EXPIRED(410), MEDIA_TOO_LARGE(422),
    UNSUPPORTED_FORMAT(422), OBJECT_MISMATCH(409), DRAFT_UNAVAILABLE(409), MEDIA_ATTACHED(409),
    NOT_CONFIGURED(503), STORAGE_UNAVAILABLE(503), OBJECT_VERIFICATION_UNAVAILABLE(503),
    CAPABILITY_UNAVAILABLE(503), RESPONSE_TOO_LARGE(422),
}
class MediaFailure(val code: MediaFailureCode) : RuntimeException("Media operation unavailable: ${code.name}")

/** Private immutable reservation constraints; never a client-controlled object key or public read grant. */
class MediaUploadAuthorization(val environment: String, val ownerId: UUID, val mediaId: UUID,
    val clientDraftId: UUID, val reservationVersion: Long, val objectKey: String,
    val contentType: String, val expectedBytes: Long, val sha256: String, val expiresAt: Instant) {
    override fun toString() = "MediaUploadAuthorization(<redacted>)"
}

class MediaObjectVerificationRequest(val environment: String, val ownerId: UUID, val mediaId: UUID,
    val objectKey: String, val objectVersionId: String, val expectedBytes: Long, val contentType: String, val sha256: String) {
    override fun toString() = "MediaObjectVerificationRequest(<redacted>)"
}

/** Actual immutable private-object evidence. Matching metadata is NOT an image safety/moderation result. */
class VerifiedMediaObject(val objectKey: String, val objectVersionId: String, val bytes: Long,
    val contentType: String, val sha256: String) {
    override fun toString() = "VerifiedMediaObject(<redacted>)"
}

/** External bounded I/O, never inside the database transaction. Verify the exact immutable version only. */
fun interface MediaObjectVerifier { fun verify(request: MediaObjectVerificationRequest): VerifiedMediaObject }

internal fun mediaOrigin(value: String): String? = try {
    val uri = java.net.URI(value)
    if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null) null
    else "https://${uri.rawAuthority}"
} catch (_: Exception) { null }
