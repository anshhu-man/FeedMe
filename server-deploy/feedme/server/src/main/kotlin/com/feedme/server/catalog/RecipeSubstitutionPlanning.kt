package com.feedme.server.catalog

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.planning.DeterministicPlanner
import com.feedme.planning.IngredientComposition
import com.feedme.planning.PlanningCatalog
import com.feedme.planning.PlanningContext
import com.feedme.planning.PlanningDecision
import com.feedme.planning.PlanningPolicy
import com.feedme.planning.PlanningStatus
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import java.util.UUID
import kotlinx.serialization.json.*

/** One structural candidate evaluation, NOT a complete catalog search or permission to
 * publish, offer, copy or persist a Plan. A NO_MATCH describes only this supplied target.
 * Original request bytes are retained; only a READY decision has a substitution change.
 * The decision's recipe is the complete exact planner-materialized target, never a patch
 * that a client must apply to the source. No recipe/catalog input is rewritten. */
class RecipeSubstitutionPlanningResult internal constructor(
    val originalRequest: WireDocument,
    val record: RecipeSubstitutionRecord,
    val decision: PlanningDecision,
    val comparison: RecipeSubstitutionComparison?,
    changes: List<WireDocument>,
) {
    private val retainedChanges = changes.toList()
    val changes: List<WireDocument> get() = retainedChanges.toList()
    override fun toString() = "RecipeSubstitutionPlanningResult(<redacted>)"
}

/** Pure F01 candidate adapter. Supplied lifecycle/review records are structural inputs,
 * not evidence of current publication, source access, private input authority or rights.
 * A future transaction-owned edge/catalog reader must establish and recheck all of those
 * and perform complete selection before a service may use this result.
 *
 * This first bounded seam supports exactly one reviewed substitution at its explicit
 * serving point. Social posts and saved copies need their own real source resolver; their
 * references are never dropped. No graph traversal, second swap, transformation, relaxed
 * hard filter, availability inference, persistence, HTTP route or authority callback exists.
 */
class RecipeSubstitutionPlanning(private val policy: PlanningPolicy) {
    fun evaluate(request: WireDocument, context: PlanningContext, source: RecipeCatalogEntry,
        target: RecipeCatalogEntry, record: RecipeSubstitutionRecord, catalogRevision: String,
        taxonomyRevision: String, ingredients: List<RecipeIngredientComposition>,
    ): PortResult<RecipeSubstitutionPlanningResult> {
        if (validator.validateSchema("PlanRequest", request.encodeUtf8()) != BodyValidationResult.Valid)
            return PortResult.Failure(FailureReason.INVALID_DATA)
        val input = Json.parseToJsonElement(request.encodeUtf8().decodeToString()).jsonObject
        val sourceFields = SOURCE_FIELDS.filter(input::containsKey)
        if (sourceFields.size > 1) return PortResult.Failure(FailureReason.INVALID_DATA)
        if (input.containsKey("sourcePostId") || input.containsKey("savedRecipeId"))
            return PortResult.Failure(FailureReason.NOT_CONFIGURED)
        if (input["intent"]?.jsonPrimitive?.content != "makeMine" ||
            input["sourceRecipeVersionId"]?.jsonPrimitive?.content?.let(UUID::fromString) != source.recipeVersionId)
            return PortResult.Failure(FailureReason.INVALID_DATA)
        if (record.status != "reviewed" || source.status != "published" || target.status != "published" ||
            !source.review.getValue("freeCatalogEligible").jsonPrimitive.boolean ||
            !target.review.getValue("freeCatalogEligible").jsonPrimitive.boolean)
            return PortResult.Failure(FailureReason.UNAVAILABLE)
        if (record.definition.policyVersion != policy.version ||
            source.review.getValue("policyVersion").jsonPrimitive.content != policy.version ||
            target.review.getValue("policyVersion").jsonPrimitive.content != policy.version)
            return PortResult.Failure(FailureReason.NOT_CONFIGURED)
        val comparison = try {
            val checked = validateRecipeSubstitutionPair(source, target, record.definition)
            val servings = input.getValue("constraints").jsonObject.getValue("servings").jsonPrimitive.content.toBigDecimal()
            require(servings.compareTo(checked.comparisonServings) == 0)
            checked
        } catch (_: IllegalArgumentException) {
            return PortResult.Failure(FailureReason.INVALID_DATA)
        }

        // Remove ONLY the verified exact original pin in this local evaluation copy. Without
        // that narrow rewrite the ordinary planner correctly rejects every different version.
        // All constraints, intent, interpretation and preference confirmation remain intact.
        val effective = WireDocument.parse(JsonObject(input - "sourceRecipeVersionId").toString())
        val catalog = PlanningCatalog(catalogRevision, taxonomyRevision, listOf(recipePlanningCandidate(target)),
            ingredients.map { IngredientComposition(it.ingredientId.toString(), it.componentIds?.map(UUID::toString)?.toSet()) })
        val planned = when (val result = DeterministicPlanner(policy).plan(effective, context, catalog)) {
            is PortResult.Failure -> return result
            is PortResult.Value -> result.value.decision
        }
        val ready = planned.status == PlanningStatus.READY
        val changes = if (ready) listOf(WireDocument.parse(buildJsonObject {
            put("fromIngredientId", record.definition.fromIngredientId.toString())
            put("toIngredientId", record.definition.toIngredientId.toString())
            put("substitutionId", record.definition.id.toString())
            put("explanation", record.definition.explanation)
        }.toString())) else emptyList()
        return PortResult.Value(RecipeSubstitutionPlanningResult(request, record, planned,
            comparison.takeIf { ready }, changes))
    }

    override fun toString() = "RecipeSubstitutionPlanning(<redacted>)"
    private companion object {
        val SOURCE_FIELDS = listOf("sourceRecipeVersionId", "sourcePostId", "savedRecipeId")
        val validator by lazy { ContractBodyValidator.bundled() }
    }
}
