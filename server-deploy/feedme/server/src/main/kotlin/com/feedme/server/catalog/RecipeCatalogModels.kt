package com.feedme.server.catalog

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

/** Explicit reviewed composition, not inferred from an ingredient name or an absent list.
 * Null means unknown; an empty list means an explicitly asserted atomic ingredient. */
class RecipeIngredientComposition(val ingredientId: UUID, componentIds: List<UUID>?) {
    private val components = componentIds?.toList()
    val componentIds: List<UUID>? get() = components?.toList()
    init { require(components == null || (components.size <= 128 && components.distinct().size == components.size)) }
    internal fun document() = buildJsonObject {
        put("ingredientId", ingredientId.toString())
        put("componentIds", components?.sortedBy(UUID::toString)?.let { JsonArray(it.map { id -> JsonPrimitive(id.toString()) }) } ?: JsonNull)
    }
    override fun toString() = "RecipeIngredientComposition(<redacted>)"
}

/** Retained original recall metadata; not authorization to recall or notify anyone. */
class RecipeRecall(val recallId: UUID, val reasonCode: String, val effectiveAt: Instant) {
    init { recipeReference(reasonCode, 128) }
    internal fun document() = buildJsonObject {
        put("recallId", recallId.toString()); put("reasonCode", reasonCode); put("effectiveAt", effectiveAt.toString())
    }
    override fun toString() = "RecipeRecall(<redacted>)"
}

/** Canonical recipe plus separate exact editorial evidence and a rights-evidence reference.
 * These are comparison inputs for mandatory publication authority, never self-certification.
 * In particular, contentLicense and rightsReference do not issue saved-copy permission. */
class RecipeCatalogEntry(recipe: JsonObject, review: JsonObject, val rightsReference: String,
    val recall: RecipeRecall? = null) {
    val recipe: JsonObject = Json.parseToJsonElement(recipe.toString()).jsonObject
    val review: JsonObject = Json.parseToJsonElement(review.toString()).jsonObject
    val recipeVersionId: UUID
    init {
        validateRecipeEntry(this.recipe, this.review, rightsReference, recall)
        recipeVersionId = UUID.fromString(this.recipe.getValue("id").jsonPrimitive.content)
    }
    internal val version: Long get() = recipe.getValue("version").jsonPrimitive.content.toBigDecimal().longValueExact()
    internal val status: String get() = recipe.getValue("reviewStatus").jsonPrimitive.content
    val materialSha256: String get() = recipeMaterialSha256(recipe)
    val simplificationSources: List<RecipeSimplificationSource> get() = decodeRecipeSimplificationSources(review)
    internal fun document() = buildJsonObject {
        put("recipe", recipe); put("review", review); put("rightsReference", rightsReference)
        put("recall", recall?.document() ?: JsonNull)
    }
    override fun toString() = "RecipeCatalogEntry(<redacted>)"
}

/** Finite full snapshot. Historical version IDs may not be evicted to make room: the bounded
 * first slice refuses exhaustion rather than silently breaking existing plans/cooking pins.
 * A new materialized instruction/body or review requires a new recipe-version UUID. */
class RecipeCatalogRelease(val releaseId: UUID, val expectedRevision: Long,
    val publisherId: UUID, val reviewerId: UUID, val publicationReference: String,
    val taxonomyRevision: String, ingredients: List<RecipeIngredientComposition>, entries: List<RecipeCatalogEntry>) {
    private val retainedIngredients = ingredients.toList()
    private val retainedEntries = entries.toList()
    val ingredients: List<RecipeIngredientComposition> get() = retainedIngredients.toList()
    val entries: List<RecipeCatalogEntry> get() = retainedEntries.toList()
    val contentSha256: String
    val taxonomySha256: String
    val requestSha256: String
    internal val exactDocument: String
    init {
        require(expectedRevision in 0 until Long.MAX_VALUE && publisherId != reviewerId)
        recipeReference(publicationReference, 256); recipeReference(taxonomyRevision, 128)
        require(retainedIngredients.size <= 1024 && retainedEntries.size in 1..128)
        val ids = retainedIngredients.map { it.ingredientId }.toSet()
        require(ids.size == retainedIngredients.size)
        require(retainedEntries.map { it.recipeVersionId }.distinct().size == retainedEntries.size)
        require(retainedIngredients.all { it.componentIds?.all(ids::contains) != false })
        require(retainedEntries.all { entry -> entry.recipe.getValue("ingredients").jsonArray.all {
            UUID.fromString(it.jsonObject.getValue("ingredientId").jsonPrimitive.content) in ids
        } })
        val taxonomy = JsonArray(retainedIngredients.sortedBy { it.ingredientId.toString() }.map { it.document() })
        taxonomySha256 = catalogSha(taxonomy.toString())
        val content = buildJsonObject {
            put("formatVersion", 1); put("taxonomyRevision", taxonomyRevision); put("ingredients", taxonomy)
            put("entries", JsonArray(retainedEntries.sortedBy { it.recipeVersionId.toString() }.map { it.document() }))
        }
        contentSha256 = catalogSha(content.toString())
        exactDocument = buildJsonObject {
            put("releaseId", releaseId.toString()); put("expectedRevision", expectedRevision)
            put("publisherId", publisherId.toString()); put("reviewerId", reviewerId.toString())
            put("publicationReference", publicationReference); put("content", content)
        }.toString()
        require(exactDocument.encodeToByteArray(throwOnInvalidSequence = true).size <= MAX_BYTES)
        requestSha256 = catalogSha(exactDocument)
    }
    override fun toString() = "RecipeCatalogRelease(<redacted>)"
    companion object { const val MAX_BYTES = 1_048_576 }
}

/** Actual operator/staff and editorial/rights checks are mandatory on the SAME transaction.
 * Lock the authenticated publication owner, independent reviewer, exact content approval,
 * rights and current policy before the catalog head; retain those locks through final check.
 * Both methods must recheck current time/revocation, including historical original replay.
 * No callback may commit, perform network I/O, rewrite content or infer review/rights.
 * There is deliberately no default implementation or HTTP/staff authentication adapter. */
interface RecipePublicationAuthority {
    fun lockPublication(connection: Connection, environment: String, original: RecipeCatalogRelease)
    fun revalidatePublication(connection: Connection, environment: String, original: RecipeCatalogRelease)
}

class RecipePublicationReceipt internal constructor(val releaseId: UUID, val revision: Long,
    val requestSha256: String, val replayed: Boolean) {
    override fun toString() = "RecipePublicationReceipt(<redacted>)"
}

/** Locked evidence only. Does not implement PlanningAuthority or issue AuthorizedRecipeCopy.
 * Caller must keep its own identity/input/rights locks, including the final current check. */
class RecipeCatalogSnapshot internal constructor(val revision: Long, val original: RecipeCatalogRelease) {
    fun planningCatalogDocument(): JsonObject = buildJsonObject {
        put("revision", revision.toString()); put("taxonomyRevision", original.taxonomyRevision)
        put("ingredients", JsonArray(original.ingredients.sortedBy { it.ingredientId.toString() }.map { it.document() }))
        // Do not filter retired/recalled history: downstream exact pins must see its status.
        put("candidates", JsonArray(original.entries.sortedBy { it.recipeVersionId.toString() }.map { entry ->
            // This legacy first-plan format has no relationship provenance. The exact original
            // above retains it; F08 reads checked pairs through the live catalog journal instead.
            buildJsonObject { put("recipe", entry.recipe); put("review", legacyPlanningReview(entry.review)) }
        }))
    }
    override fun toString() = "RecipeCatalogSnapshot(<redacted>)"
}

enum class RecipeCatalogFailureCode { NOT_CONFIGURED, AUTHORITY_DENIED, INVALID_RELEASE,
    ORIGINAL_MISMATCH, REVISION_CONFLICT, STORAGE_UNAVAILABLE }
class RecipeCatalogFailure(val code: RecipeCatalogFailureCode) : RuntimeException("Recipe catalog unavailable: ${code.name}")
