package com.feedme.server.catalog

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import java.math.BigDecimal
import java.util.UUID
import kotlinx.serialization.json.*

/** Immutable single-edge content, NOT a review decision, current publication or access grant.
 * Canonical Substitution status/version/timestamps belong to the separate lifecycle record.
 * A changed definition needs a new edge ID. No applicability is inferred for another source,
 * serving point, chained edge or ingredient unit conversion. */
class RecipeSubstitutionDefinition(document: JsonObject) {
    private val retained = substitutionCopy(document)
    val id: UUID
    val fromIngredientId: UUID
    val toIngredientId: UUID
    val sourceRecipeVersionId: UUID
    val targetRecipeVersionId: UUID
    val sourceMaterialSha256: String
    val targetMaterialSha256: String
    val ratio: BigDecimal
    val comparisonServings: BigDecimal
    val reviewReference: String
    val policyVersion: String
    val explanation: String
    private val versions: List<UUID>
    private val tags: List<String>
    private val steps: List<JsonObject>
    val document: JsonObject get() = substitutionCopy(retained)
    val recipeVersionIds: List<UUID> get() = versions.toList()
    val preservedTags: List<String> get() = tags.toList()
    val requiredStepChanges: List<JsonObject> get() = steps.map(::substitutionCopy)
    val sha256: String

    init {
        try {
            require(retained.keys == DEFINITION_FIELDS)
            id = retained.substitutionId("id")
            fromIngredientId = retained.substitutionId("fromIngredientId")
            toIngredientId = retained.substitutionId("toIngredientId")
            require(fromIngredientId != toIngredientId)
            sourceRecipeVersionId = retained.substitutionId("sourceRecipeVersionId")
            targetRecipeVersionId = retained.substitutionId("targetRecipeVersionId")
            require(sourceRecipeVersionId != targetRecipeVersionId)
            sourceMaterialSha256 = retained.substitutionText("sourceMaterialSha256")
            targetMaterialSha256 = retained.substitutionText("targetMaterialSha256")
            require(listOf(sourceMaterialSha256, targetMaterialSha256).all { it.matches(Regex("[0-9a-f]{64}")) })
            ratio = retained.substitutionNumber("ratio")
            require(ratio >= BigDecimal("0.001"))
            comparisonServings = retained.substitutionNumber("comparisonServings")
            require(comparisonServings >= BigDecimal("0.1"))
            reviewReference = retained.substitutionText("reviewReference").also { recipeReference(it, 256) }
            policyVersion = retained.substitutionText("policyVersion").also { recipeReference(it, 128) }
            explanation = retained.substitutionText("explanation").also { recipeReference(it, 1_000) }
            versions = retained.substitutionArray("recipeVersionIds").map { substitutionId(it) }
            // One complete source/target proof cannot assert applicability to another source.
            require(versions == listOf(sourceRecipeVersionId))
            tags = retained.substitutionArray("preservedTags").map { substitutionText(it).also { tag -> recipeReference(tag, 128) } }
            require(tags.distinct().size == tags.size)
            steps = retained.substitutionArray("requiredStepChanges").map {
                require(it is JsonObject)
                require(substitutionValidator.validateSchema("RecipeStep", it.toString().toByteArray(Charsets.UTF_8)) == BodyValidationResult.Valid)
                recipeReference(it.substitutionText("stepId"), 256)
                recipeReference(it.substitutionText("instruction"), 16_384)
                it
            }
            require(steps.map { it.substitutionText("stepId") }.distinct().size == steps.size)
            sha256 = catalogSha(recipeJsonIdentity(retained))
        } catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid substitution definition") }
        catch (_: ArithmeticException) { throw IllegalArgumentException("Invalid substitution definition") }
        catch (_: NoSuchElementException) { throw IllegalArgumentException("Invalid substitution definition") }
    }

    override fun toString() = "RecipeSubstitutionDefinition(<redacted>)"
}

/** Exact whole-target difference at the one reviewed serving point. Detached recipes preserve
 * all authored quantities, instructions and effort; this result does not authorize their use. */
class RecipeSubstitutionComparison internal constructor(source: RecipeCatalogEntry, target: RecipeCatalogEntry,
    definition: RecipeSubstitutionDefinition, val effort: RecipeEffortComparison,
    sourceAmount: JsonObject, targetAmount: JsonObject,
    addedSteps: List<String>, removedSteps: List<String>, changedSteps: List<String>,
) {
    private val original = detachedEntry(source)
    private val variant = detachedEntry(target)
    private val edge = definition.document
    private val oldAmount = substitutionCopy(sourceAmount)
    private val newAmount = substitutionCopy(targetAmount)
    private val added = addedSteps.toList()
    private val removed = removedSteps.toList()
    private val changed = changedSteps.toList()
    val source: RecipeCatalogEntry get() = detachedEntry(original)
    val target: RecipeCatalogEntry get() = detachedEntry(variant)
    val definition: RecipeSubstitutionDefinition get() = RecipeSubstitutionDefinition(edge)
    val comparisonServings: BigDecimal get() = effort.comparisonServings
    /** Quantities are scaled exactly to comparisonServings, never rounded or unit-converted. */
    val sourceAmount: JsonObject get() = substitutionCopy(oldAmount)
    val targetAmount: JsonObject get() = substitutionCopy(newAmount)
    val addedStepIds: List<String> get() = added.toList()
    val removedStepIds: List<String> get() = removed.toList()
    val changedStepIds: List<String> get() = changed.toList()
    val addedEquipmentIds: List<String> get() = effort.addedEquipmentIds
    val removedEquipmentIds: List<String> get() = effort.removedEquipmentIds
    override fun toString() = "RecipeSubstitutionComparison(<redacted>)"
}

/** Parse persisted immutable bytes before projecting JSON (duplicate keys and trailing data
 * are invalid). Object-key order and equivalent decimal spellings share a content hash. */
internal fun decodeRecipeSubstitutionDefinition(text: String): RecipeSubstitutionDefinition = substitutionCheck {
    val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    require(bytes.size <= MAX_DEFINITION_BYTES)
    val checked = WireDocument.decode(bytes, WireLimits(MAX_DEFINITION_BYTES, 24))
    RecipeSubstitutionDefinition(Json.parseToJsonElement(checked.encodeUtf8().decodeToString()).jsonObject)
}

/** Actual owners must resolve these exact entries and separately check independent approval,
 * current source/edge/target lifecycle, rights, taxonomy, preferences and all hard constraints.
 * Historical retirement/recall does not rewrite this structural definition. */
internal fun validateRecipeSubstitutionPair(source: RecipeCatalogEntry, target: RecipeCatalogEntry,
    definition: RecipeSubstitutionDefinition): RecipeSubstitutionComparison = substitutionCheck {
    require(source.recipeVersionId == definition.sourceRecipeVersionId && target.recipeVersionId == definition.targetRecipeVersionId)
    require(source.materialSha256 == definition.sourceMaterialSha256 && target.materialSha256 == definition.targetMaterialSha256)
    require(source.recipe.substitutionId("recipeId") == target.recipe.substitutionId("recipeId"))
    require(source.review.substitutionText("policyVersion") == definition.policyVersion &&
        target.review.substitutionText("policyVersion") == definition.policyVersion)
    val point = definition.comparisonServings
    // Reuse actual editorial serving/range/unit prerequisites. This does not extend the edge
    // to the rest of either recipe's range or scale its literal duration estimates.
    val effort = compareRecipeEffort(source, target, point)
    val before = amountsAt(source, point)
    val after = amountsAt(target, point)
    val from = definition.fromIngredientId
    val to = definition.toIngredientId
    require(before.keys - after.keys == setOf(from) && after.keys - before.keys == setOf(to))
    for (id in before.keys intersect after.keys) require(recipeJsonIdentity(before.getValue(id)) == recipeJsonIdentity(after.getValue(id)))
    val oldAmount = before.getValue(from)
    val newAmount = after.getValue(to)
    require(oldAmount.substitutionText("unit") == newAmount.substitutionText("unit"))
    val oldQuantity = oldAmount.substitutionNumber("quantity")
    val newQuantity = newAmount.substitutionNumber("quantity")
    require(oldQuantity.signum() > 0 && newQuantity.signum() > 0)
    require(oldQuantity.multiply(definition.ratio).compareTo(newQuantity) == 0)
    val sourceTags = source.recipe.substitutionArray("tasteTags").map(::substitutionText).toSet()
    val targetTags = target.recipe.substitutionArray("tasteTags").map(::substitutionText).toSet()
    require(definition.preservedTags.all { it in sourceTags && it in targetTags })
    val oldSteps = source.recipe.substitutionArray("steps").map { it.jsonObject }.associateBy { it.substitutionText("stepId") }
    val newSteps = target.recipe.substitutionArray("steps").map { it.jsonObject }.associateBy { it.substitutionText("stepId") }
    val changed = newSteps.filter { (id, step) -> oldSteps[id]?.let { recipeJsonIdentity(it) != recipeJsonIdentity(step) } == true }.keys
    val added = newSteps.keys - oldSteps.keys
    val removed = oldSteps.keys - newSteps.keys
    val actualRewrites = newSteps.filterKeys { it in changed || it in added }.values.toList()
    require(actualRewrites.size == definition.requiredStepChanges.size)
    require(actualRewrites.zip(definition.requiredStepChanges).all { (actual, declared) -> recipeJsonIdentity(actual) == recipeJsonIdentity(declared) })
    RecipeSubstitutionComparison(source, target, definition, effort, oldAmount, newAmount,
        added.toList(), removed.toList(), changed.toList())
}

private fun amountsAt(entry: RecipeCatalogEntry, servings: BigDecimal): Map<UUID, JsonObject> {
    val base = entry.recipe.substitutionNumber("servings")
    return entry.recipe.substitutionArray("ingredients").associate { raw ->
        val amount = raw.jsonObject
        val quantity = amount.substitutionNumber("quantity").multiply(servings).divide(base)
        require(quantity.precision() <= 384 && quantity.scale() in -384..384)
        amount.substitutionId("ingredientId") to JsonObject(amount + ("quantity" to Json.parseToJsonElement(quantity.toString())))
    }
}

private fun detachedEntry(entry: RecipeCatalogEntry) = RecipeCatalogEntry(entry.recipe, entry.review, entry.rightsReference, entry.recall)
private fun substitutionCopy(value: JsonObject): JsonObject = substitutionCheck {
    val text = value.toString()
    require(text.length <= MAX_DEFINITION_BYTES)
    val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    require(bytes.size <= MAX_DEFINITION_BYTES)
    Json.parseToJsonElement(WireDocument.decode(bytes, WireLimits(MAX_DEFINITION_BYTES, 24)).encodeUtf8().decodeToString()).jsonObject
}
private fun substitutionText(value: JsonElement): String = (value as? JsonPrimitive)?.let { require(it.isString); it.content }
    ?: throw IllegalArgumentException("Invalid substitution definition")
private fun substitutionId(value: JsonElement): UUID = substitutionText(value).let { text ->
    UUID.fromString(text).also { require(it.toString() == text) }
}
private fun JsonObject.substitutionId(name: String) = substitutionId(getValue(name))
private fun JsonObject.substitutionText(name: String) = substitutionText(getValue(name))
private fun JsonObject.substitutionArray(name: String): JsonArray = (getValue(name) as? JsonArray)?.also { require(it.size <= 128) }
    ?: throw IllegalArgumentException("Invalid substitution definition")
private fun JsonObject.substitutionNumber(name: String): BigDecimal {
    val value = getValue(name).jsonPrimitive
    require(!value.isString && value.content.length <= 128)
    return value.content.toBigDecimal().also { require(it.precision() <= 128 && it.scale() in -128..128) }
}
private inline fun <T> substitutionCheck(block: () -> T): T = try { block() }
catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid substitution definition") }
catch (_: ArithmeticException) { throw IllegalArgumentException("Invalid substitution definition") }
catch (_: NoSuchElementException) { throw IllegalArgumentException("Invalid substitution definition") }

private val substitutionValidator by lazy { ContractBodyValidator.bundled() }
private const val MAX_DEFINITION_BYTES = 131_072
private val DEFINITION_FIELDS = setOf("id", "fromIngredientId", "toIngredientId", "recipeVersionIds", "ratio", "preservedTags",
    "requiredStepChanges", "sourceRecipeVersionId", "targetRecipeVersionId", "sourceMaterialSha256", "targetMaterialSha256",
    "reviewReference", "policyVersion", "comparisonServings", "explanation")
