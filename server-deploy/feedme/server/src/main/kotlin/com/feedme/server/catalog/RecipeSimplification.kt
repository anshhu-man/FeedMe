package com.feedme.server.catalog

import java.math.BigDecimal
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Exact editorial relationship retained inside the target's immutable review. Parsing this
 * metadata is not editorial approval, current eligibility, private access or a copy grant. */
class RecipeSimplificationSource internal constructor(
    val sourceRecipeVersionId: UUID,
    val sourceMaterialSha256: String,
    val reviewReference: String,
    val comparisonServings: BigDecimal,
    goals: List<String>,
    val explanation: String,
) {
    private val retainedGoals = goals.toList()
    val goals: List<String> get() = retainedGoals.toList()
    override fun toString() = "RecipeSimplificationSource(<redacted>)"
}

/** Literal reviewed estimates at the relationship's explicit serving point. Null is unknown;
 * quantities and durations are never divided, multiplied, rounded or filled in here. */
class RecipeSimplificationEffort internal constructor(
    val activeMinutes: BigDecimal,
    val totalMinutes: BigDecimal,
    val cleanupMinutes: BigDecimal?,
    val utensilCount: BigDecimal,
) {
    override fun toString() = "RecipeSimplificationEffort(<redacted>)"
}

/** Structural comparison of two resolved immutable versions, not permission to offer either.
 * `overall` means a documented improvement, NOT weighted superiority or no tradeoffs. All
 * literal estimates and equipment changes remain available for required tradeoff disclosure. */
class RecipeSimplificationComparison internal constructor(
    val sourceRecipeVersionId: UUID,
    val targetRecipeVersionId: UUID,
    val sourceMaterialSha256: String,
    val targetMaterialSha256: String,
    val evidence: RecipeSimplificationSource,
    val before: RecipeSimplificationEffort,
    val after: RecipeSimplificationEffort,
    addedEquipmentIds: List<String>,
    removedEquipmentIds: List<String>,
    improvedDimensions: List<String>,
    regressedDimensions: List<String>,
) {
    val comparisonServings: BigDecimal get() = evidence.comparisonServings
    private val retainedAddedEquipment = addedEquipmentIds.toList()
    private val retainedRemovedEquipment = removedEquipmentIds.toList()
    private val retainedImprovements = improvedDimensions.toList()
    private val retainedRegressions = regressedDimensions.toList()
    val addedEquipmentIds: List<String> get() = retainedAddedEquipment.toList()
    val removedEquipmentIds: List<String> get() = retainedRemovedEquipment.toList()
    val improvedDimensions: List<String> get() = retainedImprovements.toList()
    val regressedDimensions: List<String> get() = retainedRegressions.toList()
    override fun toString() = "RecipeSimplificationComparison(<redacted>)"
}

/** Absence preserves the legacy review document exactly. Present evidence is strict and
 * bounded; an empty/null list is not a second spelling of absence. */
internal fun decodeRecipeSimplificationSources(review: JsonObject): List<RecipeSimplificationSource> {
    val raw = review[SIMPLIFICATION_SOURCES] ?: return emptyList()
    require(raw is JsonArray && raw.size in 1..16)
    val sources = raw.map { element ->
        require(element is JsonObject && element.keys == SOURCE_FIELDS)
        val idText = element.simplificationText("sourceRecipeVersionId")
        val id = UUID.fromString(idText)
        require(id.toString() == idText)
        val material = element.simplificationText("sourceMaterialSha256")
        require(material.matches(Regex("[0-9a-f]{64}")))
        val reference = element.simplificationText("reviewReference")
        recipeReference(reference, 256)
        val servings = element.simplificationNumber("comparisonServings")
        require(servings >= MINIMUM_SERVINGS)
        val goals = element.getValue("goals")
        require(goals is JsonArray && goals.size in 1..SIMPLIFICATION_GOALS.size)
        val decodedGoals = goals.map { value ->
            require(value is JsonPrimitive && value.isString && value.content in SIMPLIFICATION_GOALS)
            value.content
        }
        require(decodedGoals.distinct().size == decodedGoals.size)
        val explanation = element.simplificationText("explanation")
        recipeReference(explanation, 1_000)
        RecipeSimplificationSource(id, material, reference, servings, decodedGoals, explanation)
    }
    require(sources.map { it.sourceRecipeVersionId }.distinct().size == sources.size)
    return sources
}

/** Caller must resolve both actual catalog entries under its real publication/read owner.
 * Status checks are deliberately separate: immutable historical evidence must survive a later
 * retirement or recall. The relationship applies only at its explicit comparisonServings;
 * this function does not materialize quantities or certify other servings/constraints. */
internal fun validateRecipeSimplificationPair(
    source: RecipeCatalogEntry,
    target: RecipeCatalogEntry,
    evidence: RecipeSimplificationSource,
): RecipeSimplificationComparison {
    require(source.recipeVersionId != target.recipeVersionId)
    require(source.recipeVersionId == evidence.sourceRecipeVersionId)
    require(source.materialSha256 == evidence.sourceMaterialSha256)
    require(UUID.fromString(source.recipe.simplificationText("recipeId")) ==
        UUID.fromString(target.recipe.simplificationText("recipeId")))
    // A caller-constructed relationship or one decoded from a different review is not the
    // retained target's evidence. Compare all fields, not merely the source version ID.
    require(decodeRecipeSimplificationSources(target.review).singleOrNull {
        it.sourceRecipeVersionId == evidence.sourceRecipeVersionId
    }?.sameEvidence(evidence) == true)
    val comparison = compareRecipeEffort(source, target, evidence.comparisonServings)
    for (goal in evidence.goals) require(comparison.improves(goal))
    return RecipeSimplificationComparison(source.recipeVersionId, target.recipeVersionId,
        source.materialSha256, target.materialSha256, evidence, comparison.before, comparison.after,
        comparison.addedEquipmentIds, comparison.removedEquipmentIds,
        comparison.improvedDimensions, comparison.regressedDimensions)
}

/** No same-meal or editorial relationship is inferred. The owning selector independently
 * verifies current catalog provenance, goal, explicit fallback permission and hard constraints.
 * Unsupported serving evidence throws; the caller must not manufacture a scaled comparison. */
internal fun compareRecipeEffort(source: RecipeCatalogEntry, target: RecipeCatalogEntry,
    servings: BigDecimal): RecipeEffortComparison {
    requireServingEvidence(source, servings)
    requireServingEvidence(target, servings)
    val before = source.recipe.simplificationEffort()
    val after = target.recipe.simplificationEffort()
    val dimensions = listOf(
        Triple("activeMinutes", before.activeMinutes, after.activeMinutes),
        Triple("totalMinutes", before.totalMinutes, after.totalMinutes),
        Triple("cleanupMinutes", before.cleanupMinutes, after.cleanupMinutes),
        Triple("utensilCount", before.utensilCount, after.utensilCount),
    )
    val improvements = dimensions.filter { (_, old, new) -> old != null && new != null && new < old }.map { it.first }
    val regressions = dimensions.filter { (_, old, new) -> old != null && new != null && new > old }.map { it.first }
    val oldEquipment = source.recipe.simplificationStrings("equipmentIds").toSet()
    val newEquipment = target.recipe.simplificationStrings("equipmentIds").toSet()
    return RecipeEffortComparison(source.recipeVersionId, target.recipeVersionId,
        source.materialSha256, target.materialSha256, servings, before, after,
        (newEquipment - oldEquipment).sorted(), (oldEquipment - newEquipment).sorted(), improvements, regressions)
}

private fun requireServingEvidence(entry: RecipeCatalogEntry, servings: BigDecimal) {
    require(servings >= MINIMUM_SERVINGS)
    val recipe = entry.recipe
    val base = recipe.simplificationNumber("servings")
    require(base >= MINIMUM_SERVINGS)
    if (base.compareTo(servings) == 0) return
    // These are the same editorial scaling prerequisites as DeterministicPlanner. The pure
    // planner must still prove exact supported quantity arithmetic when producing a Plan.
    require(recipe.containsKey("scalingMin") && recipe.containsKey("scalingMax"))
    val minimum = recipe.simplificationNumber("scalingMin")
    val maximum = recipe.simplificationNumber("scalingMax")
    require(minimum >= MINIMUM_SERVINGS && minimum <= maximum)
    require(base >= minimum && base <= maximum && servings >= minimum && servings <= maximum)
    for (field in SCALING_BOOLEAN_FIELDS) {
        val value = entry.review.getValue(field).jsonPrimitive
        require(!value.isString && value.boolean)
    }
    val units = entry.review.simplificationStrings("scalableUnits").toSet()
    require(recipe.getValue("ingredients").jsonArray.all { it.jsonObject.simplificationText("unit") in units })
}

private fun RecipeSimplificationSource.sameEvidence(other: RecipeSimplificationSource): Boolean =
    sourceRecipeVersionId == other.sourceRecipeVersionId && sourceMaterialSha256 == other.sourceMaterialSha256 &&
        reviewReference == other.reviewReference && comparisonServings.compareTo(other.comparisonServings) == 0 &&
        goals == other.goals && explanation == other.explanation

private fun JsonObject.simplificationEffort(): RecipeSimplificationEffort = RecipeSimplificationEffort(
    simplificationInteger("activeMinutes"), simplificationInteger("totalMinutes"),
    if (containsKey("cleanupMinutes")) simplificationInteger("cleanupMinutes") else null,
    simplificationInteger("utensilCount"),
)

private fun JsonObject.simplificationInteger(name: String): BigDecimal = simplificationNumber(name).also {
    require(it.signum() >= 0 && it.stripTrailingZeros().scale() <= 0)
}

private fun JsonObject.simplificationNumber(name: String): BigDecimal {
    val value = getValue(name).jsonPrimitive
    require(!value.isString)
    return value.content.toBigDecimal()
}

private fun JsonObject.simplificationText(name: String): String = getValue(name).jsonPrimitive.let {
    require(it.isString); it.content
}

private fun JsonObject.simplificationStrings(name: String): List<String> = getValue(name).jsonArray.map {
    it.jsonPrimitive.let { value -> require(value.isString); value.content }
}

private const val SIMPLIFICATION_SOURCES = "simplificationSources"
private val MINIMUM_SERVINGS = BigDecimal("0.1")
private val SOURCE_FIELDS = setOf("sourceRecipeVersionId", "sourceMaterialSha256", "reviewReference", "comparisonServings", "goals", "explanation")
private val SIMPLIFICATION_GOALS = setOf("lessPrep", "lessCleanup", "lessTime", "overall")
private val SCALING_BOOLEAN_FIELDS = setOf("linearQuantityScalingReviewed", "stepsValidForScalingRange", "effortValidForScalingRange")
