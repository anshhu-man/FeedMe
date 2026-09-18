package com.feedme.server.catalog

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import java.nio.charset.CharacterCodingException
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.json.*

/** Exact immutable publication request, not an editorial approval or a recipe-use grant.
 * The environment is supplied by the actual transaction owner, never by this document.
 * reviewId identifies the real approval being checked; it is not derived from the command,
 * reviewer, reference or edge ID. Historical replay must retain these exact original bytes. */
class RecipeSubstitutionPublication(
    val publicationId: UUID,
    val expectedRevision: Long,
    val publisherId: UUID,
    val reviewerId: UUID,
    val publicationReference: String,
    val reviewId: UUID,
    val record: RecipeSubstitutionRecord,
) {
    internal val exactDocument: String
    val requestSha256: String

    init {
        try {
            require(expectedRevision in 0 until Long.MAX_VALUE && publisherId != reviewerId)
            recipeReference(publicationReference, 256)
            exactDocument = buildJsonObject {
                put("publicationId", publicationId.toString()); put("expectedRevision", expectedRevision)
                put("publisherId", publisherId.toString()); put("reviewerId", reviewerId.toString())
                put("publicationReference", publicationReference); put("reviewId", reviewId.toString())
                put("record", record.document)
            }.toString()
            require(exactDocument.encodeToByteArray(throwOnInvalidSequence = true).size <= MAX_BYTES)
            requestSha256 = catalogSha(exactDocument)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid substitution publication")
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("Invalid substitution publication")
        }
    }

    override fun toString() = "RecipeSubstitutionPublication(<redacted>)"

    companion object { const val MAX_BYTES = 262_144 }
}

/** Strict retained codec, not an accepting public request parser. It rejects duplicate keys,
 * extra/missing fields, noncanonical UUIDs, revision spellings and any change in exact bytes.
 * The nested record retains its existing strict lifecycle/definition codec and resource bound. */
internal fun decodeRecipeSubstitutionPublication(text: String): RecipeSubstitutionPublication = try {
    val wire = WireDocument.parse(text, WireLimits(RecipeSubstitutionPublication.MAX_BYTES, 40))
    val root = Json.parseToJsonElement(wire.encodeUtf8().decodeToString()).jsonObject
    require(root.keys == setOf("publicationId", "expectedRevision", "publisherId", "reviewerId",
        "publicationReference", "reviewId", "record"))
    val revision = root.getValue("expectedRevision").jsonPrimitive
    require(!revision.isString && revision.content.matches(Regex("0|[1-9][0-9]{0,18}")))
    val original = RecipeSubstitutionPublication(root.publicationUuid("publicationId"),
        revision.content.toLong(), root.publicationUuid("publisherId"), root.publicationUuid("reviewerId"),
        root.publicationText("publicationReference"), root.publicationUuid("reviewId"),
        decodeRecipeSubstitutionRecord(root.getValue("record").jsonObject.toString()))
    require(original.exactDocument == text)
    original
} catch (_: IllegalArgumentException) {
    throw IllegalArgumentException("Invalid substitution publication")
} catch (_: NoSuchElementException) {
    throw IllegalArgumentException("Invalid substitution publication")
}

/** Mandatory real approval checks on the SAME supplied connection/transaction. Lock the
 * authenticated publisher, distinct reviewer, exact reviewId and original content/policy/
 * rights approval before publication heads; retain locks through the final check. Revalidate
 * current time/revocation after all writes/waits, including replay of a historical original.
 * Neither method may commit, perform network I/O, substitute a different original, infer
 * review from IDs/status, or issue planning/read/saved-copy authority. No accepting default. */
interface RecipeSubstitutionPublicationAuthority {
    fun lockPublication(connection: Connection, environment: String, original: RecipeSubstitutionPublication)
    fun revalidatePublication(connection: Connection, environment: String, original: RecipeSubstitutionPublication)
}

class RecipeSubstitutionPublicationReceipt internal constructor(
    val publicationId: UUID,
    val revision: Long,
    val requestSha256: String,
    val replayed: Boolean,
) {
    override fun toString() = "RecipeSubstitutionPublicationReceipt(<redacted>)"
}

enum class RecipeSubstitutionFailureCode {
    NOT_CONFIGURED, AUTHORITY_DENIED, INVALID_PUBLICATION, ORIGINAL_MISMATCH, REVISION_CONFLICT, STORAGE_UNAVAILABLE,
}

class RecipeSubstitutionFailure(val code: RecipeSubstitutionFailureCode) :
    RuntimeException("Recipe substitution unavailable: ${code.name}")

private fun JsonObject.publicationText(name: String): String = getValue(name).jsonPrimitive.let {
    require(it.isString); it.content
}

private fun JsonObject.publicationUuid(name: String): UUID {
    val text = publicationText(name)
    return UUID.fromString(text).also { require(it.toString() == text) }
}
