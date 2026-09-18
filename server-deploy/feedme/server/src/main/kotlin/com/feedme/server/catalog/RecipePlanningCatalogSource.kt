package com.feedme.server.catalog

import com.feedme.contracts.RecipeVersionWire
import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.planning.IngredientComposition
import com.feedme.planning.PlanningCandidate
import com.feedme.planning.PlanningCandidateBatch
import com.feedme.planning.PlanningCandidateSource
import com.feedme.planning.PlanningCatalogHeader
import com.feedme.planning.PlanningContentKind
import com.feedme.planning.PlanningEnergy
import com.feedme.planning.ReviewedPlanningEvidence
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Structural projection of one real, transaction-owned current or historical catalog view. No lifecycle state is
 * filtered, and an unknown ingredient composition stays unknown. The header is immutable
 * metadata, not a currentness or complete-traversal receipt. The owning scanner must call
 * [checkCurrent] after its final callback before accepting a result; private-input, policy,
 * publication and saved-copy authorization remain separate responsibilities. Historical
 * construction retains that anchor's taxonomy/count; it never substitutes current material.
 *
 * Provenance is retained for only the latest bounded page. It binds exact candidate objects,
 * not IDs or reconstructed/scaled recipe bodies. Failed/next reads retire the previous page;
 * no unbounded candidate cache or independently reusable continuation is created. */
class RecipePlanningCatalogSource private constructor(
    private val view: RecipeCatalogReadView?, private val historical: RecipeCatalogHistoricalReadView?,
) : PlanningCandidateSource {
    constructor(view: RecipeCatalogReadView) : this(view, null)
    constructor(view: RecipeCatalogHistoricalReadView) : this(null, view)

    private val revision = view?.revision ?: checkNotNull(historical).revision
    private val taxonomyRevision = view?.taxonomyRevision ?: checkNotNull(historical).taxonomyRevision
    val header = PlanningCatalogHeader(revision.toString(), taxonomyRevision,
        view?.versionCount ?: checkNotNull(historical).versionCount,
        (view?.ingredients ?: checkNotNull(historical).ingredients).map { ingredient -> IngredientComposition(ingredient.ingredientId.toString(),
            ingredient.componentIds?.map(UUID::toString)?.toSet()) })

    private var lastPage: List<Pair<PlanningCandidate, RecipeCatalogVersion>> = emptyList()

    override fun read(after: String?, limit: Int): PlanningCandidateBatch {
        // Do not let a foreign thread/expired view replace the actual owner's page evidence.
        checkCurrent()
        lastPage = emptyList()
        val key = recipePlanningProjection {
            after?.let { value -> require(value.length == 36); UUID.fromString(value).also { require(it.toString() == value) } }
        }
        // The view owns its exception contract, including cancellation/unknown outcomes.
        val page = if (view != null) view.page(key, limit) else checkNotNull(historical).page(key, limit)
        return recipePlanningProjection {
            val pairs = page.entries.map { version -> recipePlanningCandidate(version.entry) to version }
            val batch = PlanningCandidateBatch(revision.toString(), taxonomyRevision, after,
                pairs.map { it.first }, page.nextAfter?.toString())
            lastPage = pairs
            batch
        }
    }

    fun checkCurrent() { if (view != null) view.checkCurrent() else checkNotNull(historical).checkCurrent() }

    /** Only the exact unmodified object delivered in the latest page has this provenance.
     * A current view plus an ID match alone cannot authenticate a caller-supplied candidate. */
    fun sourceOf(candidate: PlanningCandidate): RecipeCatalogVersion {
        checkCurrent()
        return lastPage.singleOrNull { it.first === candidate }?.second
            ?: throw RecipeCatalogFailure(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
    }

    override fun toString() = "RecipePlanningCatalogSource(<redacted>)"
}

/** Exact bounded stored recipe/review projection only; no inferred review or rights flags. */
internal fun recipePlanningCandidate(entry: RecipeCatalogEntry): PlanningCandidate = recipePlanningProjection {
    val review = entry.review
    PlanningCandidate(RecipeVersionWire.from(WireDocument.parse(entry.recipe.toString(), WireLimits(65_536, 32))),
        ReviewedPlanningEvidence(
            review.planningText("reviewReference"), review.planningText("policyVersion"),
            PlanningContentKind.valueOf(review.planningText("kind")),
            PlanningEnergy.valueOf(review.planningText("minimumEnergy")),
            review.planningBoolean("heatingRequired"), review.planningBoolean("substantialPreparation"),
            review.planningBoolean("freeCatalogEligible"), review.planningStrings("compatibleBaseTypes").toSet(),
            review.planningBoolean("linearQuantityScalingReviewed"), review.planningBoolean("stepsValidForScalingRange"),
            review.planningBoolean("effortValidForScalingRange"), review.planningStrings("scalableUnits").toSet()))
}

private fun JsonObject.planningText(name: String): String = getValue(name).jsonPrimitive.let {
    require(it.isString); it.content
}
private fun JsonObject.planningBoolean(name: String): Boolean = getValue(name).jsonPrimitive.let {
    require(!it.isString); it.boolean
}
private fun JsonObject.planningStrings(name: String): List<String> = getValue(name).jsonArray.map {
    it.jsonPrimitive.let { value -> require(value.isString); value.content }
}

private inline fun <T> recipePlanningProjection(action: () -> T): T = try { action() }
    catch (failure: RecipeCatalogFailure) { throw failure }
    catch (failure: CancellationException) { throw failure }
    catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
    catch (_: Exception) { throw RecipeCatalogFailure(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE) }
