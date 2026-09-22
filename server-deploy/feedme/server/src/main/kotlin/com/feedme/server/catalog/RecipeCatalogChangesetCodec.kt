package com.feedme.server.catalog

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

/** Strict format-2 decoder. Format 1 remains exclusively the existing full-release codec. */
internal fun decodeRecipeChangeset(text: String): RecipeCatalogChangeset {
    val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    val root = Json.parseToJsonElement(WireDocument.decode(bytes, WireLimits(RecipeCatalogChangeset.MAX_BYTES, 32))
        .encodeUtf8().decodeToString()).jsonObject
    val fields = setOf("releaseId", "expectedRevision", "publisherId", "reviewerId", "publicationReference", "content")
    require(root.keys == fields || root.keys == fields + "recallActorId")
    val content = root.getValue("content").jsonObject
    require(content.keys == setOf("formatVersion", "taxonomyRevision", "ingredients", "entries") && content["formatVersion"] == JsonPrimitive(2))
    val ingredients = content.getValue("ingredients").jsonArray.map { raw ->
        val value = raw.jsonObject; require(value.keys == setOf("ingredientId", "componentIds"))
        RecipeIngredientComposition(UUID.fromString(value.changeText("ingredientId")), value["componentIds"].takeUnless { it == JsonNull }
            ?.jsonArray?.map { UUID.fromString(it.jsonPrimitive.let { p -> require(p.isString); p.content }) })
    }
    val entries = content.getValue("entries").jsonArray.map { raw ->
        val value = raw.jsonObject; require(value.keys == setOf("recipe", "review", "rightsReference", "recall"))
        val recall = value["recall"].takeUnless { it == JsonNull }?.jsonObject?.let {
            require(it.keys == setOf("recallId", "reasonCode", "effectiveAt"))
            RecipeRecall(UUID.fromString(it.changeText("recallId")), it.changeText("reasonCode"), Instant.parse(it.changeText("effectiveAt")))
        }
        RecipeCatalogEntry(value.getValue("recipe").jsonObject, value.getValue("review").jsonObject, value.changeText("rightsReference"), recall)
    }
    val result = RecipeCatalogChangeset(UUID.fromString(root.changeText("releaseId")), root.getValue("expectedRevision").jsonPrimitive.let {
        require(!it.isString); it.content.toBigDecimal().longValueExact()
    }, UUID.fromString(root.changeText("publisherId")), UUID.fromString(root.changeText("reviewerId")), root.changeText("publicationReference"),
        content.changeText("taxonomyRevision"), ingredients, entries,
        root["recallActorId"]?.let { UUID.fromString(root.changeText("recallActorId")) })
    require(result.exactDocument == text)
    return result
}

/** One real retained publication of either version. Never constructs a synthetic full release
 * from changes. Every field below comes from that publication's independently checked bytes. */
internal class RecipeJournalOriginal private constructor(val releaseId: UUID, val expectedRevision: Long,
    val requestSha256: String, val contentSha256: String, val taxonomyRevision: String, val taxonomySha256: String,
    val ingredients: List<RecipeIngredientComposition>, val entries: List<RecipeCatalogEntry>, val exactDocument: String) {
    companion object {
        fun decode(text: String): RecipeJournalOriginal {
            val root = Json.parseToJsonElement(WireDocument.decode(text.encodeToByteArray(throwOnInvalidSequence = true),
                WireLimits(RecipeCatalogChangeset.MAX_BYTES, 32)).encodeUtf8().decodeToString()).jsonObject
            return when (root.getValue("content").jsonObject["formatVersion"]) {
                JsonPrimitive(1) -> decodeRecipeRelease(text).let { RecipeJournalOriginal(it.releaseId, it.expectedRevision,
                    it.requestSha256, it.contentSha256, it.taxonomyRevision, it.taxonomySha256, it.ingredients, it.entries, it.exactDocument) }
                JsonPrimitive(2) -> decodeRecipeChangeset(text).let { RecipeJournalOriginal(it.releaseId, it.expectedRevision,
                    it.requestSha256, it.contentSha256, it.taxonomyRevision, it.taxonomySha256, it.ingredients, it.entries, it.exactDocument) }
                else -> throw IllegalArgumentException("Unknown recipe journal format")
            }
        }
    }
    override fun toString() = "RecipeJournalOriginal(<redacted>)"
}

private fun JsonObject.changeText(name: String) = getValue(name).jsonPrimitive.let { require(it.isString); it.content }
