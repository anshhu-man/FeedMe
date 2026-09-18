package com.feedme.server.catalog

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.planning.PlanningContentKind
import com.feedme.planning.PlanningEnergy
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import java.math.BigDecimal
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

internal fun recipeReference(value: String, maximum: Int) {
    require(value.isNotBlank() && value.length <= maximum && value.none(Char::isISOControl))
    value.encodeToByteArray(throwOnInvalidSequence = true)
}

internal fun validateRecipeEntry(recipe: JsonObject, review: JsonObject, rights: String, recall: RecipeRecall?) {
    val bytes = recipe.toString().encodeToByteArray(throwOnInvalidSequence = true)
    require(bytes.size <= 65_536 && recipeValidator.validateSchema("RecipeVersion", bytes) == BodyValidationResult.Valid)
    // Optional wire fields are mandatory publication evidence here; reject absence before
    // dereferencing, without manufacturing a license, estimate or review default.
    require(recipe.keys.containsAll(setOf("reviewedAt", "contentLicense", "estimateBasis", "preparationTags")))
    val version = recipe.getValue("version").jsonPrimitive.content.toBigDecimal().longValueExact()
    require(version > 0 && Instant.parse(recipe.text("updatedAt")) >= Instant.parse(recipe.text("createdAt")))
    require(recipe.text("reviewStatus") in setOf("published", "retired", "recalled"))
    require(recipe.containsKey("reviewedAt") && Instant.parse(recipe.text("reviewedAt")) <= Instant.parse(recipe.text("updatedAt")))
    require(recipe.text("contentLicense") in setOf("catalogRedistributable", "privateCopyOnly", "creatorOriginal"))
    require(recipe.text("estimateBasis") in setOf("reviewerEstimate", "pilotObserved"))
    recipeReference(rights, 256)
    val ingredients = recipe.getValue("ingredients").jsonArray.map { it.jsonObject }
    val ingredientIds = ingredients.map { UUID.fromString(it.text("ingredientId")) }
    require(ingredientIds.isNotEmpty() && ingredientIds.distinct().size == ingredientIds.size)
    val equipment = recipe.strings("equipmentIds"); require(equipment.distinct().size == equipment.size)
    val steps = recipe.getValue("steps").jsonArray.map { it.jsonObject }
    require(steps.isNotEmpty() && steps.map { it.text("stepId") }.distinct().size == steps.size)
    steps.forEachIndexed { index, step ->
        require(step.getValue("position").jsonPrimitive.content.toBigDecimal().compareTo(BigDecimal(index + 1)) == 0)
        recipeReference(step.text("stepId"), 256); recipeReference(step.text("instruction"), 16_384)
        require(step.strings("ingredientIds").all { UUID.fromString(it) in ingredientIds })
        require(step.strings("requiredEquipmentIds").all(equipment::contains))
    }
    require(recipe.getValue("activeMinutes").jsonPrimitive.content.toBigDecimal() <= recipe.getValue("totalMinutes").jsonPrimitive.content.toBigDecimal())
    require(review.keys == REVIEW_FIELDS || review.keys == REVIEW_FIELDS + "simplificationSources")
    decodeRecipeSimplificationSources(review)
    recipeReference(review.text("reviewReference"), 256); recipeReference(review.text("policyVersion"), 128)
    PlanningContentKind.valueOf(review.text("kind")); val energy = PlanningEnergy.valueOf(review.text("minimumEnergy"))
    for (field in REVIEW_BOOLEAN_FIELDS) review.boolean(field)
    for (field in listOf("compatibleBaseTypes", "scalableUnits")) {
        val values = review.strings(field); require(values.size <= 128 && values.distinct().size == values.size)
        values.forEach { recipeReference(it, 128) }
    }
    require(recipe.containsKey("preparationTags"))
    val heating = review.boolean("heatingRequired")
    require(("noHeat" in recipe.strings("preparationTags")) == !heating)
    require(energy != PlanningEnergy.ASSEMBLE || (!heating && !review.boolean("substantialPreparation")))
    if (recipe.text("reviewStatus") == "recalled") {
        require(recall != null && recipe.text("recallReasonCode") == recall.reasonCode)
        require(recall.effectiveAt <= Instant.parse(recipe.text("updatedAt")))
    } else require(recall == null && !recipe.containsKey("recallReasonCode"))
}

/** Strict, compact retained format: original bytes are checked before JSON projection. */
internal fun decodeRecipeRelease(text: String): RecipeCatalogRelease {
    val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    val root = Json.parseToJsonElement(WireDocument.decode(bytes, WireLimits(RecipeCatalogRelease.MAX_BYTES, 32)).encodeUtf8().decodeToString()).jsonObject
    require(root.keys == setOf("releaseId", "expectedRevision", "publisherId", "reviewerId", "publicationReference", "content"))
    val content = root.getValue("content").jsonObject
    require(content.keys == setOf("formatVersion", "taxonomyRevision", "ingredients", "entries") && content["formatVersion"] == JsonPrimitive(1))
    val ingredients = content.getValue("ingredients").jsonArray.map { raw ->
        val value = raw.jsonObject; require(value.keys == setOf("ingredientId", "componentIds"))
        RecipeIngredientComposition(UUID.fromString(value.text("ingredientId")), value["componentIds"].takeUnless { it == JsonNull }
            ?.jsonArray?.map { UUID.fromString(it.jsonPrimitive.let { p -> require(p.isString); p.content }) })
    }
    val entries = content.getValue("entries").jsonArray.map { raw ->
        val value = raw.jsonObject; require(value.keys == setOf("recipe", "review", "rightsReference", "recall"))
        val recall = value["recall"].takeUnless { it == JsonNull }?.jsonObject?.let {
            require(it.keys == setOf("recallId", "reasonCode", "effectiveAt"))
            RecipeRecall(UUID.fromString(it.text("recallId")), it.text("reasonCode"), Instant.parse(it.text("effectiveAt")))
        }
        RecipeCatalogEntry(value.getValue("recipe").jsonObject, value.getValue("review").jsonObject, value.text("rightsReference"), recall)
    }
    val result = RecipeCatalogRelease(UUID.fromString(root.text("releaseId")), root.getValue("expectedRevision").jsonPrimitive.let {
        require(!it.isString); it.content.toBigDecimal().longValueExact()
    }, UUID.fromString(root.text("publisherId")), UUID.fromString(root.text("reviewerId")), root.text("publicationReference"),
        content.text("taxonomyRevision"), ingredients, entries)
    require(result.exactDocument == text)
    return result
}

internal fun validateRecipeSuccessor(previous: RecipeCatalogRelease?, next: RecipeCatalogRelease) {
    val old = previous?.entries?.associateBy { it.recipeVersionId }.orEmpty()
    val entries = next.entries.associateBy { it.recipeVersionId }
    require(entries.keys.containsAll(old.keys))
    if (previous != null && previous.taxonomyRevision == next.taxonomyRevision) require(previous.taxonomySha256 == next.taxonomySha256)
    for ((id, value) in entries) validateRecipeEntrySuccessor(old[id], value)
    validateNewRecipeSimplifications(next.entries, old) { entries[it] }
}

/** Called under the publication head lock after lifecycle validation. Only introducing a new
 * target requires currently published sources; an immutable old relationship must not prevent
 * later retirement/recall or exact historical replay. Source resolution is the submitted overlay
 * plus real retained history, never a caller-supplied prefix of matching candidates. */
internal fun validateNewRecipeSimplifications(entries: List<RecipeCatalogEntry>,
    previous: Map<UUID, RecipeCatalogEntry?>, resolve: (UUID) -> RecipeCatalogEntry?) {
    for (target in entries) if (previous[target.recipeVersionId] == null) {
        for (evidence in target.simplificationSources) {
            val source = requireNotNull(resolve(evidence.sourceRecipeVersionId))
            require(source.status == "published")
            validateRecipeSimplificationPair(source, target, evidence)
        }
    }
}

/** Shared exact lifecycle rule for full snapshots and bounded journal changes. */
internal fun validateRecipeEntrySuccessor(before: RecipeCatalogEntry?, value: RecipeCatalogEntry) {
    if (before == null) { require(value.status == "published"); return }
    require(before.recipe - LIFECYCLE_FIELDS == value.recipe - LIFECYCLE_FIELDS)
    require(before.review == value.review && before.rightsReference == value.rightsReference)
    if (before.document() == value.document()) return
    require(before.version < Long.MAX_VALUE && value.version == before.version + 1)
    require(Instant.parse(value.recipe.text("updatedAt")) >= Instant.parse(before.recipe.text("updatedAt")))
    require(before.status != "recalled")
    require(value.status in if (before.status == "retired") setOf("retired", "recalled") else setOf("published", "retired", "recalled"))
}

/** JSONB may normalize number spellings; compare exact decimal values, never Double. */
internal fun recipeJsonIdentity(value: JsonElement): String = when (value) {
    is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { (k, v) -> "${JsonPrimitive(k)}:${recipeJsonIdentity(v)}" }
    is JsonArray -> value.joinToString(",", "[", "]", transform = ::recipeJsonIdentity)
    is JsonPrimitive -> if (value.isString || value == JsonNull || value.booleanOrNull != null) value.toString()
        else value.content.toBigDecimal().stripTrailingZeros().toString()
}

/** Stable binding for a reviewed relationship. Lifecycle updates and JSONB number spellings
 * cannot change it; recipe material (including family, servings, steps and effort) can. Review
 * is independently immutable per UUID and retained in the exact approved publication. */
internal fun recipeMaterialSha256(recipe: JsonObject): String =
    catalogSha(recipeJsonIdentity(JsonObject(recipe - LIFECYCLE_FIELDS)))

/** Explicit legacy first-plan projection only. Simplification consumers must use exact catalog
 * originals/guarded journal reads, not this v1 format that cannot represent pair provenance. */
internal fun legacyPlanningReview(review: JsonObject): JsonObject =
    JsonObject(review.filterKeys { it in REVIEW_FIELDS })

/** Match V015's actual JSONB representation bound, including PostgreSQL's spaces, Unicode and
 * numeric normalization. Compact JVM bytes are not the stored size. SQL failures remain storage
 * failures; only an observed oversized entry is rejected as submitted content. No writes here. */
internal fun requireRecipeStorageBounds(connection: Connection, entries: List<RecipeCatalogEntry>) {
    connection.prepareStatement("SELECT octet_length(?::jsonb::text)").use { statement ->
        for (entry in entries) {
            statement.setString(1, entry.document().toString())
            statement.executeQuery().use { rows ->
                check(rows.next())
                if (rows.getLong(1) > 131_072L) throw RecipeCatalogFailure(RecipeCatalogFailureCode.INVALID_RELEASE)
                check(!rows.next())
            }
        }
    }
}
private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.let { require(it.isString); it.content }
private fun JsonObject.strings(name: String) = getValue(name).jsonArray.map { it.jsonPrimitive.let { p -> require(p.isString); p.content } }
private fun JsonObject.boolean(name: String) = getValue(name).jsonPrimitive.let { require(!it.isString); it.boolean }
private val recipeValidator by lazy { ContractBodyValidator.bundled() }
private val LIFECYCLE_FIELDS = setOf("version", "updatedAt", "reviewStatus", "recallReasonCode", "reviewedAt", "reviewerLabel")
private val REVIEW_BOOLEAN_FIELDS = setOf("heatingRequired", "substantialPreparation", "freeCatalogEligible", "linearQuantityScalingReviewed", "stepsValidForScalingRange", "effortValidForScalingRange")
private val REVIEW_FIELDS = REVIEW_BOOLEAN_FIELDS + setOf("reviewReference", "policyVersion", "kind", "minimumEnergy", "compatibleBaseTypes", "scalableUnits")
