package com.feedme.server.catalog

import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.kitchen.VerifiedKitchenPrincipal
import java.security.MessageDigest
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

/** Explicit deterministic search choice. Literal, case-sensitive prefix matching; no locale,
 * case folding, alias expansion or database extension is silently selected. */
enum class IngredientSearchMode(val wire: String) { LITERAL_PREFIX_V1("literal-prefix-v1") }

class IngredientCatalogLimits(val maxReleaseBytes: Int, val maxIngredients: Int,
    val cursorLifetimeSeconds: Int) {
    init { require(maxReleaseBytes in 1..1_048_576); require(maxIngredients in 1..1024); require(cursorLifetimeSeconds in 1..3600) }
    override fun toString() = "IngredientCatalogLimits(<redacted>)"
}

/** These are reviewed release assertions, NOT proof obtained from an Ingredient payload.
 * All three must be true for search/fresh selection; retained IDs are not re-selected. */
class IngredientReleaseItem(ingredient: JsonObject, val reviewed: Boolean,
    val published: Boolean, val freeAccess: Boolean) {
    val ingredient: JsonObject = Json.parseToJsonElement(ingredient.toString()).jsonObject
    internal val id: UUID = UUID.fromString(this.ingredient.getValue("id").jsonPrimitive.content)
    internal fun document() = buildJsonObject {
        put("ingredient", ingredient); put("reviewed", reviewed); put("published", published); put("freeAccess", freeAccess)
    }
    override fun toString() = "IngredientReleaseItem(<redacted>)"
}

/** Owner-supplied original intent, never an authenticated staff principal. The mandatory
 * authority must authenticate/authorize these facts against its own current DB records.
 * Keep this exact release ID/predecessor/content after any unknown publication outcome. */
class IngredientReleaseRequest(val releaseId: UUID, val expectedRevision: Long,
    val publisherId: UUID, val reviewerId: UUID, val reviewReference: String,
    val rightsReference: String, val publicationPolicyVersion: String,
    val searchMode: IngredientSearchMode, items: List<IngredientReleaseItem>) {
    val items = items.toList()
    val contentSha256: String
    val requestSha256: String
    internal val exactDocument: String
    init {
        require(expectedRevision in 0 until Long.MAX_VALUE)
        require(publisherId != reviewerId) { "Independent review is required" }
        listOf(reviewReference, rightsReference, publicationPolicyVersion).forEach(::checkedReference)
        require(this.items.size <= 1024)
        require(this.items.map { it.id }.distinct().size == this.items.size)
        this.items.forEach { checkedIngredient(it.ingredient) }
        val content = buildJsonObject {
            put("formatVersion", 1); put("searchMode", searchMode.wire)
            put("publicationPolicyVersion", publicationPolicyVersion)
            put("items", JsonArray(this@IngredientReleaseRequest.items.sortedBy { it.id.toString() }.map { it.document() }))
        }
        contentSha256 = catalogSha(content.toString())
        exactDocument = buildJsonObject {
            put("content", content); put("releaseId", releaseId.toString()); put("expectedRevision", expectedRevision)
            put("publisherId", publisherId.toString()); put("reviewerId", reviewerId.toString())
            put("reviewReference", reviewReference); put("rightsReference", rightsReference)
        }.toString()
        require(exactDocument.encodeToByteArray().size <= 1_048_576)
        requestSha256 = catalogSha(exactDocument)
    }
    override fun toString() = "IngredientReleaseRequest(<redacted>)"
}

/** Current publication authority MUST be implemented by the trusted owner. No accepting
 * implementation/default is provided. Both callbacks use the SAME transaction, database
 * only; lockPublication precedes the catalog head and all release rows. Lock actual owner,
 * independent review, exact content hash, rights and current policy facts through commit.
 * revalidatePublication must also run for identical historical replay and after head waits.
 * UUIDs/references above are comparison inputs, never authentication or rights evidence. */
interface IngredientPublicationAuthority {
    fun lockPublication(connection: Connection, environment: String, original: IngredientReleaseRequest)
    fun revalidatePublication(connection: Connection, environment: String, original: IngredientReleaseRequest)
}

/** Mandatory policy for fields whose authoritative registries are not implemented here.
 * Called on every fresh PATCH, with actual locked prior fields and the exact proposed resource.
 * Own dietary/equipment/consent and other governed choices, distinguish unchanged historical
 * values from fresh selection, never infer consent or expand/rewrite the user's fields.
 * No default, sample policy, readiness decision or public adapter is supplied. */
fun interface IngredientPreferencePolicy {
    fun validate(connection: Connection, principal: VerifiedKitchenPrincipal,
        previousFields: JsonObject, proposed: JsonObject)
}

class IngredientPublicationReceipt internal constructor(val releaseId: UUID, val revision: Long,
    val requestSha256: String, val replayed: Boolean) {
    override fun toString() = "IngredientPublicationReceipt(<redacted>)"
}

enum class IngredientCatalogFailureCode { NOT_CONFIGURED, INVALID_RELEASE, AUTHORITY_DENIED,
    ORIGINAL_MISMATCH, REVISION_CONFLICT, STORAGE_UNAVAILABLE }
class IngredientCatalogFailure(val code: IngredientCatalogFailureCode) : RuntimeException("Ingredient catalog unavailable: ${code.name}")

internal val ingredientValidator by lazy { ContractBodyValidator.bundled() }
internal fun checkedIngredient(value: JsonObject) {
    require(ingredientValidator.validateSchema("Ingredient", value.toString().encodeToByteArray(throwOnInvalidSequence = true)) == BodyValidationResult.Valid)
    val version = value.getValue("version").jsonPrimitive.content.toBigDecimal().longValueExact()
    require(version > 0)
    require(Instant.parse(value.getValue("updatedAt").jsonPrimitive.content) >= Instant.parse(value.getValue("createdAt").jsonPrimitive.content))
    require(value.getValue("aliases").jsonArray.size <= 256)
    // Bounds belong to this explicit persisted format, not new API schema semantics.
    val lookup = listOf(value.getValue("name").jsonPrimitive.content) + value.getValue("aliases").jsonArray.map { it.jsonPrimitive.content }
    lookup.forEach { require(it.encodeToByteArray(throwOnInvalidSequence = true).size <= 4096 && it.none(Char::isISOControl)) }
}
internal fun checkedReference(value: String) { require(value.length in 1..256 && value.none(Char::isISOControl)) }
internal fun catalogSha(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray(throwOnInvalidSequence = true))
    .joinToString("") { "%02x".format(it) }

internal fun decodeRelease(text: String): IngredientReleaseRequest {
    val root = Json.parseToJsonElement(text).jsonObject
    require(root.keys == setOf("content", "releaseId", "expectedRevision", "publisherId", "reviewerId", "reviewReference", "rightsReference"))
    val content = root.getValue("content").jsonObject
    require(content.keys == setOf("formatVersion", "searchMode", "publicationPolicyVersion", "items") && content["formatVersion"] == JsonPrimitive(1))
    val entries = content.getValue("items").jsonArray.map {
        val item = it.jsonObject
        require(item.keys == setOf("ingredient", "reviewed", "published", "freeAccess"))
        IngredientReleaseItem(item.getValue("ingredient").jsonObject, item.getValue("reviewed").jsonPrimitive.boolean,
            item.getValue("published").jsonPrimitive.boolean, item.getValue("freeAccess").jsonPrimitive.boolean)
    }
    val result = IngredientReleaseRequest(UUID.fromString(root.getValue("releaseId").jsonPrimitive.content),
        root.getValue("expectedRevision").jsonPrimitive.long, UUID.fromString(root.getValue("publisherId").jsonPrimitive.content),
        UUID.fromString(root.getValue("reviewerId").jsonPrimitive.content), root.getValue("reviewReference").jsonPrimitive.content,
        root.getValue("rightsReference").jsonPrimitive.content, content.getValue("publicationPolicyVersion").jsonPrimitive.content,
        IngredientSearchMode.entries.single { it.wire == content.getValue("searchMode").jsonPrimitive.content }, entries)
    // Exact compact form also rejects duplicate keys, escaped aliases and unsupported fields.
    require(result.exactDocument == text)
    return result
}
