package com.feedme.server.catalog

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

/** A requested positive copy permission, not permission merely because it can be constructed.
 * Publication must authenticate the exact original against independent rights approval and the
 * actual catalog. Explicit deadlines and guest/scaling flags are never supplied by defaults. */
class RecipeCopyGrant private constructor(
    val grantId: UUID, val recipeVersionId: UUID, val sourceReleaseId: UUID,
    val sourceRevision: Long, val sourceRequestSha256: String, val sourceSha256: String,
    val publisherId: UUID, val reviewerId: UUID, val authorizationReference: String,
    val contentLicense: String, val allowGuest: Boolean, val allowReviewedScaling: Boolean,
    val notBefore: Instant, val newCopiesUntil: Instant, val retainedCopiesUntil: Instant,
) {
    constructor(source: RecipeCatalogVersion, grantId: UUID, publisherId: UUID, reviewerId: UUID,
        authorizationReference: String, contentLicense: String, allowGuest: Boolean,
        allowReviewedScaling: Boolean, notBefore: Instant, newCopiesUntil: Instant,
        retainedCopiesUntil: Instant) : this(grantId, source.entry.recipeVersionId, source.releaseId,
        source.revision, source.requestSha256, recipeCopySourceSha256(source.entry), publisherId,
        reviewerId, authorizationReference, contentLicense, allowGuest, allowReviewedScaling,
        notBefore, newCopiesUntil, retainedCopiesUntil)

    internal val exactDocument: String
    val requestSha256: String
    init {
        require(sourceRevision > 0 && publisherId != reviewerId)
        require(sourceRequestSha256.matches(COPY_HASH) && sourceSha256.matches(COPY_HASH))
        recipeReference(authorizationReference, 256)
        require(contentLicense in setOf("catalogRedistributable", "privateCopyOnly"))
        require(listOf(notBefore, newCopiesUntil, retainedCopiesUntil).all { it.nano % 1_000_000 == 0 })
        require(notBefore < newCopiesUntil && newCopiesUntil <= retainedCopiesUntil)
        exactDocument = buildJsonObject {
            put("formatVersion", 1); put("grantId", grantId.toString()); put("recipeVersionId", recipeVersionId.toString())
            put("sourceReleaseId", sourceReleaseId.toString()); put("sourceRevision", sourceRevision)
            put("sourceRequestSha256", sourceRequestSha256); put("sourceSha256", sourceSha256)
            put("publisherId", publisherId.toString()); put("reviewerId", reviewerId.toString())
            put("authorizationReference", authorizationReference); put("contentLicense", contentLicense)
            put("allowGuest", allowGuest); put("allowReviewedScaling", allowReviewedScaling)
            put("notBefore", notBefore.toString()); put("newCopiesUntil", newCopiesUntil.toString())
            put("retainedCopiesUntil", retainedCopiesUntil.toString())
        }.toString()
        require(exactDocument.encodeToByteArray(throwOnInvalidSequence = true).size <= COPY_DOCUMENT_BYTES)
        requestSha256 = catalogSha(exactDocument)
    }
    internal fun evidence(environment: String) = buildJsonObject {
        put("formatVersion", 1); put("environment", environment); put("grantId", grantId.toString())
        put("requestSha256", requestSha256); put("recipeVersionId", recipeVersionId.toString())
        put("sourceReleaseId", sourceReleaseId.toString()); put("sourceRevision", sourceRevision)
        put("sourceRequestSha256", sourceRequestSha256); put("sourceSha256", sourceSha256)
    }
    override fun toString() = "RecipeCopyGrant(<redacted>)"
    companion object {
        internal fun decode(text: String): RecipeCopyGrant {
            val value = copyDocument(text)
            val result = RecipeCopyGrant(value.uuid("grantId"), value.uuid("recipeVersionId"), value.uuid("sourceReleaseId"),
                value.getValue("sourceRevision").jsonPrimitive.let { require(!it.isString); it.content.toLong() },
                value.text("sourceRequestSha256"), value.text("sourceSha256"), value.uuid("publisherId"), value.uuid("reviewerId"),
                value.text("authorizationReference"), value.text("contentLicense"), value.bool("allowGuest"),
                value.bool("allowReviewedScaling"), Instant.parse(value.text("notBefore")),
                Instant.parse(value.text("newCopiesUntil")), Instant.parse(value.text("retainedCopiesUntil")))
            require(result.exactDocument == text)
            return result
        }
    }
}

/** Exact append-only original. Revoking is an independent mandatory authority decision. */
class RecipeCopyRevocation(val grantId: UUID, val revocationId: UUID, val operatorId: UUID,
    val authorizationReference: String) {
    internal val exactDocument: String
    val requestSha256: String
    init {
        recipeReference(authorizationReference, 256)
        exactDocument = buildJsonObject {
            put("formatVersion", 1); put("grantId", grantId.toString()); put("revocationId", revocationId.toString())
            put("operatorId", operatorId.toString()); put("authorizationReference", authorizationReference)
        }.toString()
        requestSha256 = catalogSha(exactDocument)
    }
    override fun toString() = "RecipeCopyRevocation(<redacted>)"
    companion object {
        internal fun decode(text: String): RecipeCopyRevocation {
            val value = copyDocument(text)
            val result = RecipeCopyRevocation(value.uuid("grantId"), value.uuid("revocationId"),
                value.uuid("operatorId"), value.text("authorizationReference"))
            require(result.exactDocument == text)
            return result
        }
    }
}

/** No accepting implementation/default. Authenticate independent current operator/reviewer
 * approval for the EXACT request before catalog/rights locks, then recheck after writes/waits.
 * Same transaction only: no network, commit, content replacement or inferred license grant. */
interface RecipeCopyRightsPublicationAuthority {
    fun lockPublication(connection: Connection, environment: String, original: RecipeCopyGrant)
    fun revalidatePublication(connection: Connection, environment: String, original: RecipeCopyGrant)
    fun lockRevocation(connection: Connection, environment: String, original: RecipeCopyRevocation)
    fun revalidateRevocation(connection: Connection, environment: String, original: RecipeCopyRevocation)
}

class RecipeCopyRightsReceipt internal constructor(val grantId: UUID, val originalId: UUID,
    val requestSha256: String, val replayed: Boolean) {
    override fun toString() = "RecipeCopyRightsReceipt(<redacted>)"
}

enum class RecipeCopyRightsFailureCode { NOT_CONFIGURED, AUTHORITY_DENIED, NOT_ALLOWED, EXPIRED,
    RECALLED, ORIGINAL_MISMATCH, STORAGE_UNAVAILABLE }
class RecipeCopyRightsFailure(val code: RecipeCopyRightsFailureCode) : RuntimeException("Recipe copy unavailable: ${code.name}")

/** These exact lifecycle fields are the only mutable fields allowed by RecipeCatalogCodec.
 * Recipe material, editorial review, license and independent rights reference remain bound. */
internal fun recipeCopySourceSha256(entry: RecipeCatalogEntry): String = catalogSha(recipeJsonIdentity(buildJsonObject {
    put("recipe", JsonObject(entry.recipe - setOf("version", "updatedAt", "reviewStatus", "recallReasonCode", "reviewedAt", "reviewerLabel")))
    put("review", entry.review); put("rightsReference", entry.rightsReference)
}))
private fun copyDocument(text: String): JsonObject = Json.parseToJsonElement(WireDocument.decode(
    text.encodeToByteArray(throwOnInvalidSequence = true), WireLimits(COPY_DOCUMENT_BYTES, 8)).encodeUtf8().decodeToString()).jsonObject
private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.let { require(it.isString); it.content }
private fun JsonObject.uuid(name: String) = UUID.fromString(text(name)).also { require(it.toString() == text(name)) }
private fun JsonObject.bool(name: String) = getValue(name).jsonPrimitive.let { require(!it.isString); it.boolean }
internal val COPY_HASH = Regex("[0-9a-f]{64}")
internal const val COPY_DOCUMENT_BYTES = 8192
