package com.feedme.server.catalog

import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.json.*

/** Bounded publication DELTA, never a complete planning candidate snapshot. Untouched recipe
 * versions remain in the journal. Taxonomy is the complete bounded taxonomy for this revision.
 * These exact review/rights references require current external publication authority. */
class RecipeCatalogChangeset(val releaseId: UUID, val expectedRevision: Long,
    val publisherId: UUID, val reviewerId: UUID, val publicationReference: String,
    val taxonomyRevision: String, ingredients: List<RecipeIngredientComposition>, entries: List<RecipeCatalogEntry>,
    /** For withdrawal only: publisher/reviewer retain the material's original
     * provenance, while this explicit actor performed the current recall. */
    val recallActorId: UUID? = null) {
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
        require(recallActorId == null || retainedEntries.all { it.status == "recalled" && it.recall != null })
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
            put("formatVersion", 2); put("taxonomyRevision", taxonomyRevision); put("ingredients", taxonomy)
            put("entries", JsonArray(retainedEntries.sortedBy { it.recipeVersionId.toString() }.map { it.document() }))
        }
        contentSha256 = catalogSha(content.toString())
        exactDocument = buildJsonObject {
            put("releaseId", releaseId.toString()); put("expectedRevision", expectedRevision)
            put("publisherId", publisherId.toString()); put("reviewerId", reviewerId.toString())
            put("publicationReference", publicationReference); put("content", content)
            // Omitted on every prior format-2 original; never reinterpret old bytes.
            recallActorId?.let { put("recallActorId", it.toString()) }
        }.toString()
        require(exactDocument.encodeToByteArray(throwOnInvalidSequence = true).size <= MAX_BYTES)
        requestSha256 = catalogSha(exactDocument)
    }
    override fun toString() = "RecipeCatalogChangeset(<redacted>)"
    companion object { const val MAX_BYTES = 1_048_576 }
}

/** Mandatory exact-original, same-transaction publication authority; no accepting default.
 * Acquire current publisher/reviewer, content and rights approval before the head lock and
 * revalidate after all writes/events/waits, including historical original replay. No network,
 * commit, inferred review, saved-copy grant or substitution of the supplied change is allowed. */
interface RecipeChangesetPublicationAuthority {
    fun lockPublication(connection: Connection, environment: String, original: RecipeCatalogChangeset)
    fun revalidatePublication(connection: Connection, environment: String, original: RecipeCatalogChangeset)
}
