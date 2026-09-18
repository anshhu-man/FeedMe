package com.feedme.server.catalog

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import java.math.BigInteger
import java.time.DateTimeException
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

/** One independently versioned edge, separate from either immutable recipe. This value is
 * structural evidence only: neither the word `reviewed` nor constructing it grants editorial,
 * publication, recipe-read or planning authority. A future durable owner must validate the
 * resolved pair and exact approval under its actual transaction before accepting this record.
 *
 * Material changes require a NEW edge ID. The canonical version numbers lifecycle revisions,
 * not a second mutable copy of recipe content. Recalling this edge does not recall its recipes
 * or another edge targeting the same recipe. Historical records must remain retained. */
class RecipeSubstitutionRecord(
    val definition: RecipeSubstitutionDefinition,
    val version: BigInteger,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val recall: RecipeRecall? = null,
) {
    private val retainedDocument: String
    private val retainedPublic: String
    val document: JsonObject get() = Json.parseToJsonElement(retainedDocument).jsonObject
    val publicDocument: JsonObject get() = Json.parseToJsonElement(retainedPublic).jsonObject
    val sha256: String

    init {
        require(version.signum() > 0 && version.toString().length <= 128)
        require(status in setOf("draft", "reviewed", "recalled") && updatedAt >= createdAt)
        if (status == "recalled") require(recall != null && recall.effectiveAt in createdAt..updatedAt)
        else require(recall == null)
        val public = buildJsonObject {
            val material = definition.document
            for (name in PUBLIC_MATERIAL_FIELDS) put(name, material.getValue(name))
            put("version", JsonPrimitive(version)); put("status", status)
            put("createdAt", createdAt.toString()); put("updatedAt", updatedAt.toString())
        }
        retainedPublic = public.toString()
        require(validator.validateSchema("Substitution", retainedPublic.encodeToByteArray()) == BodyValidationResult.Valid)
        retainedDocument = buildJsonObject {
            put("formatVersion", 1); put("definition", definition.document)
            put("version", JsonPrimitive(version)); put("status", status)
            put("createdAt", createdAt.toString()); put("updatedAt", updatedAt.toString())
            put("recall", recall?.document() ?: JsonNull)
        }.toString()
        require(retainedDocument.encodeToByteArray(throwOnInvalidSequence = true).size <= MAX_BYTES)
        sha256 = catalogSha(retainedDocument)
    }

    override fun toString() = "RecipeSubstitutionRecord(<redacted>)"

    companion object {
        const val MAX_BYTES = 131_072
        private val PUBLIC_MATERIAL_FIELDS = listOf("id", "fromIngredientId", "toIngredientId",
            "recipeVersionIds", "ratio", "preservedTags", "requiredStepChanges")
        private val validator by lazy { ContractBodyValidator.bundled() }
    }
}

/** Strict retained format, including duplicate-key/depth/size refusal. This is not a public
 * request parser and never upgrades a public Substitution into reviewed internal evidence. */
internal fun decodeRecipeSubstitutionRecord(text: String): RecipeSubstitutionRecord = try {
    decodeSubstitutionRecord(text)
} catch (_: IllegalArgumentException) {
    throw IllegalArgumentException("Invalid substitution record")
} catch (_: DateTimeException) {
    throw IllegalArgumentException("Invalid substitution record")
} catch (_: NoSuchElementException) {
    throw IllegalArgumentException("Invalid substitution record")
}

private fun decodeSubstitutionRecord(text: String): RecipeSubstitutionRecord {
    val wire = WireDocument.decode(text.encodeToByteArray(throwOnInvalidSequence = true),
        WireLimits(RecipeSubstitutionRecord.MAX_BYTES, 32))
    val root = Json.parseToJsonElement(wire.encodeUtf8().decodeToString()).jsonObject
    require(root.keys == setOf("formatVersion", "definition", "version", "status", "createdAt", "updatedAt", "recall"))
    require(root["formatVersion"] == JsonPrimitive(1))
    val version = root.getValue("version").jsonPrimitive
    require(!version.isString && version.content.matches(Regex("[1-9][0-9]{0,127}")))
    val recall = root.getValue("recall").takeUnless { it == JsonNull }?.jsonObject?.let {
        require(it.keys == setOf("recallId", "reasonCode", "effectiveAt"))
        val id = it.lifecycleText("recallId")
        require(UUID.fromString(id).toString() == id)
        RecipeRecall(UUID.fromString(id), it.lifecycleText("reasonCode"), Instant.parse(it.lifecycleText("effectiveAt")))
    }
    val record = RecipeSubstitutionRecord(RecipeSubstitutionDefinition(root.getValue("definition").jsonObject),
        version.content.toBigInteger(), root.lifecycleText("status"), Instant.parse(root.lifecycleText("createdAt")),
        Instant.parse(root.lifecycleText("updatedAt")), recall)
    require(record.document.toString() == text)
    return record
}

/** Same edge only; a caller must retain the old original and check the real current head.
 * Exact replay is allowed even after withdrawal, but is not permission to reactivate it.
 * All genuine changes increment exactly once. Withdrawal is terminal and independently
 * reversible only by publishing a separately reviewed NEW edge ID, never overwriting history. */
internal fun validateRecipeSubstitutionSuccessor(before: RecipeSubstitutionRecord?, after: RecipeSubstitutionRecord) {
    if (before == null) {
        require(after.version == BigInteger.ONE && after.status in setOf("draft", "reviewed"))
        require(after.createdAt == after.updatedAt)
        return
    }
    require(before.definition.document == after.definition.document && before.createdAt == after.createdAt)
    if (before.document == after.document) return
    require(after.version == before.version + BigInteger.ONE && after.updatedAt >= before.updatedAt)
    require(before.status != "recalled")
    require(after.status in if (before.status == "draft") setOf("reviewed", "recalled") else setOf("recalled"))
    if (after.recall != null) require(after.recall.effectiveAt >= before.updatedAt)
}

private fun JsonObject.lifecycleText(name: String): String = getValue(name).jsonPrimitive.let {
    require(it.isString); it.content
}
