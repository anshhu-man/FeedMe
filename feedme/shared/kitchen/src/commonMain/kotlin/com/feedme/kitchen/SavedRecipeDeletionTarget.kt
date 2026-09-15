package com.feedme.kitchen

import com.feedme.core.ports.*
import kotlinx.serialization.json.*

/**
 * Removal-only observation minted by one repository for one exact live lease. It contains no
 * recipe body or display label. This is neither server ownership authorization nor user consent.
 * Decoding [evidence] cannot reconstruct this live target.
 */
class SavedRecipeDeletionTarget internal constructor(
    internal val repository: Any,
    internal val lease: SessionLease,
    val evidence: SavedRecipeDeletionEvidence,
) {
    val id: String get() = evidence.id
    val etag: String get() = evidence.etag
    override fun toString() = "SavedRecipeDeletionTarget(<redacted>)"
}

/**
 * Detached original-intent correlation, not a signed proof, fresh target, consent or receipt ACK.
 * The existing authenticated queue/composition owns its scope/store/origin. Every repository use
 * revalidates these exact fingerprints against that composition's local records; decoding alone
 * authorizes nothing. Never log or use this evidence to disclose the retained recipe body.
 */
class SavedRecipeDeletionEvidence internal constructor(
    internal val scopeDigest: String,
    val id: String,
    val etag: String,
    internal val version: String,
    internal val recipeVersionId: String,
    internal val bodyRevision: Long,
    internal val bodyDigest: String,
    internal val metadataRevision: Long,
    internal val metadataDigest: String,
    internal val indexRevision: Long,
    internal val indexDigest: String,
) {
    fun encode(): PrivateBytes = PrivateJson.encode(buildJsonObject {
        put("schemaVersion", 1); put("scopeDigest", scopeDigest); put("id", id); put("etag", etag)
        put("version", version); put("recipeVersionId", recipeVersionId)
        put("bodyRevision", bodyRevision); put("bodyDigest", bodyDigest)
        put("metadataRevision", metadataRevision); put("metadataDigest", metadataDigest)
        put("indexRevision", indexRevision); put("indexDigest", indexDigest)
    })

    override fun toString() = "SavedRecipeDeletionEvidence(<redacted>)"

    companion object {
        const val MAX_BYTES = 8192
        private val FIELDS = setOf("schemaVersion", "scopeDigest", "id", "etag", "version", "recipeVersionId",
            "bodyRevision", "bodyDigest", "metadataRevision", "metadataDigest", "indexRevision", "indexDigest")

        /** Strict bounded canonical local format. Structural validity is not live authority. */
        fun decode(bytes: PrivateBytes): PortResult<SavedRecipeDeletionEvidence> = try {
            val raw = bytes.copyForCodec()
            if (raw.size !in 1..MAX_BYTES) PrivateJson.invalid()
            val fields = PrivateJson.decode(bytes, FIELDS)
            if (fields.getValue("schemaVersion") != JsonPrimitive(1)) PrivateJson.invalid()
            val version = PrivateJson.string(fields.getValue("version"))
            if (version.length !in 1..4096 || !version.matches(Regex("[1-9][0-9]*"))) PrivateJson.invalid()
            val etag = PrivateJson.string(fields.getValue("etag"))
            if (etag.length > 256 || !etag.matches(Regex("\"[0-9]+\"")) ||
                etag.substring(1, etag.length - 1).trimStart('0').ifEmpty { "0" } != version) PrivateJson.invalid()
            fun positive(name: String) = PrivateJson.long(fields.getValue(name)).also { if (it <= 0) PrivateJson.invalid() }
            val result = SavedRecipeDeletionEvidence(PrivateJson.hash(fields.getValue("scopeDigest")),
                PrivateJson.uuid(fields.getValue("id")), etag, version, PrivateJson.uuid(fields.getValue("recipeVersionId")),
                positive("bodyRevision"), PrivateJson.hash(fields.getValue("bodyDigest")),
                positive("metadataRevision"), PrivateJson.hash(fields.getValue("metadataDigest")),
                positive("indexRevision"), PrivateJson.hash(fields.getValue("indexDigest")))
            if (!raw.contentEquals(result.encode().copyForCodec())) PrivateJson.invalid()
            PortResult.Value(result)
        } catch (_: Exception) { PortResult.Failure(FailureReason.INVALID_DATA) }
    }
}
