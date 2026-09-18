package com.feedme.server.catalog

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.planning.PlanningContext
import com.feedme.planning.PlanningDecision
import com.feedme.planning.PlanningPolicy
import com.feedme.planning.PlanningScanBudget
import com.feedme.planning.PlanningScanPreference
import com.feedme.planning.PlanningStatus
import com.feedme.planning.StreamingPlanner
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import java.util.UUID
import kotlinx.serialization.json.*

enum class RecipeSimplificationSelectionKind { REVIEWED_VARIANT, DIFFERENT_MEAL }

/** Exact current catalog sources, not an authorized parent/child Plan or cooking transition.
 * A different meal deliberately has no fabricated same-meal relationship. */
class RecipeSimplificationSelection internal constructor(
    val target: RecipeCatalogVersion,
    val kind: RecipeSimplificationSelectionKind,
    val comparison: RecipeEffortComparison,
    val reviewedRelationship: RecipeCatalogSimplification?,
) {
    override fun toString() = "RecipeSimplificationSelection(<redacted>)"
}

/** A completed structural selection, still provisional to the owning service's transaction.
 * selection is present only for READY, while decision owns canonical constraints and the
 * exact materialized/scaled snapshot. Original catalog versions are never rewritten. */
class RecipeSimplificationScanResult internal constructor(
    val decision: PlanningDecision,
    val traversedCount: Long,
    val eligibleCount: Long,
    val source: RecipeCatalogVersion,
    val selection: RecipeSimplificationSelection?,
) {
    override fun toString() = "RecipeSimplificationScanResult(<redacted>)"
}

/** F08 selection over the actual live current journal, not an account/guest/HTTP service.
 * The future owner must bind the source to its authorized parent Plan and exact stored
 * snapshot/scaling proof, check If-Match, provide current private inputs/rights, persist an
 * immutable child plus original command receipt, and revalidate authority before commit.
 * A source UUID or returned object is never that authority.
 *
 * All catalog rows participate in bounded complete traversal. Only direct reviewed variants
 * at this explicit serving point enter the preferred scope. A different recipe family is a
 * fallback only with explicit consent and a demonstrated improvement. Both scopes use the
 * existing exact hard filters/materialization/rank; no weaker alternative meal is admitted.
 * No caller-supplied review callback, unbounded candidate cache or guest expansion exists. */
class RecipeSimplificationCatalogScanner(private val view: RecipeCatalogReadView,
    private val policy: PlanningPolicy) {
    fun scan(request: WireDocument, context: PlanningContext, sourceRecipeVersionId: UUID,
        goal: String, allowDifferentMeal: Boolean, budget: PlanningScanBudget,
        pageSize: Int = 32): PortResult<RecipeSimplificationScanResult> {
        view.checkCurrent()
        if (goal !in GOALS || validator.validateSchema("PlanRequest", request.encodeUtf8()) != BodyValidationResult.Valid)
            return PortResult.Failure(FailureReason.INVALID_DATA)
        val document = Json.parseToJsonElement(request.encodeUtf8().decodeToString()).jsonObject
        // Unsupported source ownership cannot be bypassed by dropping those fields.
        if (document.containsKey("sourcePostId") || document.containsKey("savedRecipeId"))
            return PortResult.Failure(FailureReason.NOT_CONFIGURED)
        if (document["sourceRecipeVersionId"]?.jsonPrimitive?.content?.let { UUID.fromString(it) != sourceRecipeVersionId } == true)
            return PortResult.Failure(FailureReason.INVALID_DATA)
        val servings = document.getValue("constraints").jsonObject.getValue("servings").jsonPrimitive.content.toBigDecimal()
        val parent = view.lookupCurrent(sourceRecipeVersionId)
            ?: return PortResult.Failure(FailureReason.UNAVAILABLE)
        if (!available(parent.entry)) return PortResult.Failure(FailureReason.UNAVAILABLE)
        // The original must remain readable/reviewed, but need not fit the newly tightened
        // limits. Only remove its verified exact-match pin; preserve all other request fields.
        val effectiveRequest = WireDocument.parse(JsonObject(document - "sourceRecipeVersionId").toString())
        val family = UUID.fromString(parent.entry.recipe.getValue("recipeId").jsonPrimitive.content)

        fun classify(version: RecipeCatalogVersion): RecipeSimplificationSelection? {
            val target = version.entry
            if (target.recipeVersionId == sourceRecipeVersionId || !available(target)) return null
            if (UUID.fromString(target.recipe.getValue("recipeId").jsonPrimitive.content) == family) {
                val edge = target.simplificationSources.singleOrNull { it.sourceRecipeVersionId == sourceRecipeVersionId }
                    ?: return null
                if (goal !in edge.goals || edge.comparisonServings.compareTo(servings) != 0) return null
                val relationship = view.simplification(sourceRecipeVersionId, target.recipeVersionId)
                    ?: throw RecipeCatalogFailure(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
                return RecipeSimplificationSelection(relationship.target, RecipeSimplificationSelectionKind.REVIEWED_VARIANT,
                    relationship.comparison.effortComparison(), relationship)
            }
            if (!allowDifferentMeal) return null
            // Unsupported serving evidence excludes this fallback; it cannot certify a new
            // scaling range. No I/O/authority failure is caught by this local comparison check.
            val comparison = try { compareRecipeEffort(parent.entry, target, servings) }
                catch (_: IllegalArgumentException) { return null }
            if (!comparison.improves(goal)) return null
            return RecipeSimplificationSelection(version, RecipeSimplificationSelectionKind.DIFFERENT_MEAL, comparison, null)
        }

        val source = RecipePlanningCatalogSource(view)
        val scanned = StreamingPlanner(policy).scanPreferred(effectiveRequest, context, source.header, source, budget, pageSize,
            classify = { candidate ->
                when (classify(source.sourceOf(candidate))?.kind) {
                    RecipeSimplificationSelectionKind.REVIEWED_VARIANT -> PlanningScanPreference.PRIMARY
                    RecipeSimplificationSelectionKind.DIFFERENT_MEAL -> PlanningScanPreference.EXPLICIT_FALLBACK
                    null -> PlanningScanPreference.EXCLUDED
                }
            })
        val result = when (scanned) {
            is PortResult.Failure -> return scanned
            is PortResult.Value -> scanned.value
        }
        source.checkCurrent()
        val selected = if (result.decision.status == PlanningStatus.READY) {
            val id = UUID.fromString(checkNotNull(result.decision.recipe).id.value)
            val version = view.lookupCurrent(id) ?: throw RecipeCatalogFailure(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
            classify(version) ?: throw RecipeCatalogFailure(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
        } else null
        source.checkCurrent()
        return PortResult.Value(RecipeSimplificationScanResult(result.decision, result.traversedCount,
            result.eligibleCount, parent, selected))
    }

    private fun available(entry: RecipeCatalogEntry): Boolean = entry.status == "published" &&
        entry.review.getValue("policyVersion").jsonPrimitive.content == policy.version &&
        entry.review.getValue("freeCatalogEligible").jsonPrimitive.boolean

    override fun toString() = "RecipeSimplificationCatalogScanner(<redacted>)"
    companion object {
        private val GOALS = setOf("lessPrep", "lessCleanup", "lessTime", "overall")
        private val validator by lazy { ContractBodyValidator.bundled() }
    }
}
