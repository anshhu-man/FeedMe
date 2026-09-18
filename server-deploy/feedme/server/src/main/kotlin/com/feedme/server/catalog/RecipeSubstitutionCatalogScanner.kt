package com.feedme.server.catalog

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.planning.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import java.math.BigDecimal
import java.util.UUID
import kotlinx.serialization.json.*

/** Per-operation work admission, not a lifetime catalog limit. Edge counts concern the
 * exact source/target pairs inspected; recipe traversal includes every lifecycle state. */
class RecipeSubstitutionScanBudget(val recipeBudget: PlanningScanBudget,
    val maxEdges: Long, val maxEdgePages: Long) {
    override fun toString() = "RecipeSubstitutionScanBudget(<redacted>)"
}

/** Complete structural selection under the original live transaction, not a Plan, source
 * access, private-input or copy grant. Only READY has a selected pair and canonical changes.
 * Originals remain exact; the future service owns parent If-Match/receipts and acceptance. */
class RecipeSubstitutionScanResult internal constructor(val originalRequest: WireDocument,
    val originalAdaptation: WireDocument?, val decision: PlanningDecision,
    val source: RecipeCatalogVersion, val selection: RecipeSubstitutionCatalogPair?,
    changes: List<WireDocument>, val traversedCount: Long, val eligibleCount: Long,
    val inspectedEdgeCount: Long, val edgePageCount: Long) {
    private val retainedChanges = changes.toList()
    val changes: List<WireDocument> get() = retainedChanges.toList()
    override fun toString() = "RecipeSubstitutionScanResult(<redacted>)"
}

/** F01/F10 selection from BOTH real heads in one RecipeSubstitutionReadView. No arbitrary
 * edge list, accepting authority, child transaction, access resolver or HTTP route exists.
 * The owner must supply actual authorized parent/private inputs and revalidate at commit.
 *
 * Traverse recipes once in recipe-version order; for each in-scope target, traverse all
 * current exact-pair edge pages, including withdrawn/draft records. Multiple edges never
 * duplicate a recipe's rank. Lowest canonical edge UUID breaks equal-target proof ties;
 * actual shared-engine rank chooses between targets. Only bounded pages and one winner
 * are retained. Late errors, deadlines and budget exhaustion cannot return a prefix winner.
 *
 * AdaptRequest is optional for a direct Make Mine request. With a parent the future owner
 * must derive mode/source/preference evidence from that exact authorized parent, supplying
 * complete edited constraints; this scanner never fabricates a preference acknowledgement.
 * requestedReplacementId denotes the replacement INGREDIENT, not an edge/recipe identifier.
 */
class RecipeSubstitutionCatalogScanner(private val view: RecipeSubstitutionReadView,
    private val policy: PlanningPolicy) {
    fun scan(request: WireDocument, context: PlanningContext, budget: RecipeSubstitutionScanBudget,
        pageSize: Int = 32, adaptation: WireDocument? = null): PortResult<RecipeSubstitutionScanResult> = try {
        view.checkCurrent()
        if (pageSize !in 1..128 || budget.maxEdges < 0 || budget.maxEdgePages < 1)
            scanFail(FailureReason.INVALID_DATA)
        val input = when (val decoded = substitutionSelectionInput(request, adaptation)) {
            is PortResult.Failure -> scanFail(decoded.reason)
            is PortResult.Value -> decoded.value
        }
        // F10's separate retention intent must not bypass the policy gate merely because
        // heat is absent from constraints.tasteTags. Do not rewrite the exact constraints.
        if (input.retainTasteTag == "heat" && !policy.heatEnabled) scanFail(FailureReason.NOT_CONFIGURED)
        val parent = view.recipes.lookupCurrent(input.sourceId) ?: scanFail(FailureReason.UNAVAILABLE)
        if (parent.entry.status != "published" || !parent.entry.review.getValue("freeCatalogEligible").jsonPrimitive.boolean)
            scanFail(FailureReason.UNAVAILABLE)
        if (parent.entry.review.getValue("policyVersion").jsonPrimitive.content != policy.version)
            scanFail(FailureReason.NOT_CONFIGURED)
        if (input.replaceIngredientId != null && parent.entry.recipe.getValue("ingredients").jsonArray.none {
                UUID.fromString(it.jsonObject.getValue("ingredientId").jsonPrimitive.content) == input.replaceIngredientId })
            scanFail(FailureReason.INVALID_DATA)
        if (input.retainTasteTag != null && parent.entry.recipe.getValue("tasteTags").jsonArray.none {
                it.jsonPrimitive.content == input.retainTasteTag }) scanFail(FailureReason.INVALID_DATA)

        var edgeCount = 0L
        var edgePages = 0L
        var classified: RecipeSubstitutionCatalogPair? = null
        var best: RecipeSubstitutionCatalogPair? = null
        var bestRank: PlanningRank? = null
        val recipes = RecipePlanningCatalogSource(view.recipes)
        fun classify(candidate: PlanningCandidate): PlanningScanPreference {
            classified = null
            val target = recipes.sourceOf(candidate)
            val id = target.entry.recipeVersionId
            if (id == input.sourceId || id in input.excludedTargets || !available(target.entry))
                return PlanningScanPreference.EXCLUDED
            var after: UUID? = null
            var selected: RecipeSubstitutionVersion? = null
            do {
                if (edgePages >= budget.maxEdgePages) scanFail(FailureReason.UNAVAILABLE)
                edgePages++
                val page = view.pageForPair(input.sourceId, id, after, pageSize)
                if (page.entries.size.toLong() > budget.maxEdges - edgeCount) scanFail(FailureReason.UNAVAILABLE)
                edgeCount += page.entries.size
                for (edge in page.entries) {
                    val definition = edge.record.definition
                    if (edge.record.status != "reviewed" || definition.policyVersion != policy.version ||
                        definition.comparisonServings.compareTo(input.servings) != 0 ||
                        (input.replaceIngredientId != null && definition.fromIngredientId != input.replaceIngredientId) ||
                        (input.requestedReplacementId != null && definition.toIngredientId != input.requestedReplacementId) ||
                        (input.retainTasteTag != null && input.retainTasteTag !in definition.preservedTags)) continue
                    if (selected == null || definition.id.toString() < selected.record.definition.id.toString()) selected = edge
                }
                after = page.nextAfter
            } while (after != null)
            val edge = selected ?: return PlanningScanPreference.EXCLUDED
            val pair = view.resolvePair(edge.record.definition.id) ?: storageFailure()
            if (pair.edge.publicationId != edge.publicationId || pair.edge.requestSha256 != edge.requestSha256 ||
                pair.source.entry.materialSha256 != parent.entry.materialSha256 ||
                pair.target.entry.materialSha256 != target.entry.materialSha256) storageFailure()
            classified = pair
            return PlanningScanPreference.PRIMARY
        }
        val scanned = StreamingPlanner(policy).scanWithRequiredAvailability(input.effectiveRequest, context,
            recipes.header, recipes, budget.recipeBudget, pageSize, classify = ::classify,
            requiredAvailable = { classified?.let { setOf(it.edge.record.definition.toIngredientId.toString()) } ?: emptySet() },
            onEligible = { candidate, rank, _ ->
                val pair = classified ?: storageFailure()
                if (UUID.fromString(candidate.recipe.id.value) != pair.target.entry.recipeVersionId) storageFailure()
                if (bestRank == null || rank < checkNotNull(bestRank)) { bestRank = rank; best = pair }
            })
        val result = when (scanned) {
            is PortResult.Failure -> scanFail(scanned.reason)
            is PortResult.Value -> scanned.value
        }
        view.checkCurrent()
        val selection = if (result.decision.status == PlanningStatus.READY) {
            val chosen = best ?: storageFailure()
            val current = view.resolvePair(chosen.edge.record.definition.id) ?: storageFailure()
            if (current.edge.requestSha256 != chosen.edge.requestSha256 || current.edge.revision != chosen.edge.revision ||
                current.edge.record.status != "reviewed" || !available(current.source.entry) || !available(current.target.entry) ||
                result.decision.recipe?.id?.value?.let(UUID::fromString) != current.target.entry.recipeVersionId) storageFailure()
            current
        } else null
        val changes = selection?.let { pair -> listOf(WireDocument.parse(buildJsonObject {
            val definition = pair.edge.record.definition
            put("fromIngredientId", definition.fromIngredientId.toString())
            put("toIngredientId", definition.toIngredientId.toString())
            put("substitutionId", definition.id.toString()); put("explanation", definition.explanation)
        }.toString())) } ?: emptyList()
        view.checkCurrent()
        PortResult.Value(RecipeSubstitutionScanResult(request, adaptation, result.decision, parent, selection,
            changes, result.traversedCount, result.eligibleCount, edgeCount, edgePages))
    } catch (failure: SubstitutionScanFailure) { PortResult.Failure(failure.reason) }
    catch (failure: RecipeCatalogFailure) {
        throw RecipeSubstitutionFailure(if (failure.code == RecipeCatalogFailureCode.NOT_CONFIGURED)
            RecipeSubstitutionFailureCode.NOT_CONFIGURED else RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE)
    }

    private fun available(entry: RecipeCatalogEntry) = entry.status == "published" &&
        entry.review.getValue("freeCatalogEligible").jsonPrimitive.boolean &&
        entry.review.getValue("policyVersion").jsonPrimitive.content == policy.version
    override fun toString() = "RecipeSubstitutionCatalogScanner(<redacted>)"
}

internal class RecipeSubstitutionSelectionInput(val sourceId: UUID, val effectiveRequest: WireDocument,
    val servings: BigDecimal, val replaceIngredientId: UUID?, val requestedReplacementId: UUID?,
    val retainTasteTag: String?, excludedTargets: Set<UUID>) {
    val excludedTargets = excludedTargets.toSet()
    override fun toString() = "RecipeSubstitutionSelectionInput(<redacted>)"
}

/** Structural request checks only. Neither input document proves parent/source authority. */
internal fun substitutionSelectionInput(request: WireDocument, adaptation: WireDocument?): PortResult<RecipeSubstitutionSelectionInput> = try {
    val validator = substitutionSelectionValidator
    if (validator.validateSchema("PlanRequest", request.encodeUtf8()) != BodyValidationResult.Valid)
        scanFail(FailureReason.INVALID_DATA)
    val root = Json.parseToJsonElement(request.encodeUtf8().decodeToString()).jsonObject
    if (listOf("sourceRecipeVersionId", "sourcePostId", "savedRecipeId").count(root::containsKey) > 1)
        scanFail(FailureReason.INVALID_DATA)
    if (root.containsKey("sourcePostId") || root.containsKey("savedRecipeId")) scanFail(FailureReason.NOT_CONFIGURED)
    if (root["intent"]?.jsonPrimitive?.content != "makeMine" || !root.containsKey("sourceRecipeVersionId"))
        scanFail(FailureReason.INVALID_DATA)
    val constraints = root.getValue("constraints").jsonObject
    val adapted = adaptation?.let {
        if (validator.validateSchema("AdaptRequest", it.encodeUtf8()) != BodyValidationResult.Valid)
            scanFail(FailureReason.INVALID_DATA)
        Json.parseToJsonElement(it.encodeUtf8().decodeToString()).jsonObject.also { doc ->
            if (doc.keys.any { key -> key !in setOf("constraints", "reason", "replaceIngredientId",
                    "requestedReplacementId", "retainTasteTag", "excludeRecipeVersionIds") } ||
                doc.getValue("reason").jsonPrimitive.content !in setOf("makeMine", "missingIngredient"))
                scanFail(FailureReason.NOT_CONFIGURED)
            if (recipeJsonIdentity(doc.getValue("constraints")) != recipeJsonIdentity(constraints))
                scanFail(FailureReason.INVALID_DATA)
        }
    }
    val from = adapted?.get("replaceIngredientId")?.jsonPrimitive?.content?.let(UUID::fromString)
    val to = adapted?.get("requestedReplacementId")?.jsonPrimitive?.content?.let(UUID::fromString)
    val taste = adapted?.get("retainTasteTag")?.jsonPrimitive?.content
    if ((to != null && (from == null || to == from)) || (taste != null && (taste.isBlank() || taste.length > 128)))
        scanFail(FailureReason.INVALID_DATA)
    if (adapted?.get("reason")?.jsonPrimitive?.content == "missingIngredient" &&
        (from == null || constraints.getValue("ingredientIds").jsonArray.any { UUID.fromString(it.jsonPrimitive.content) == from }))
        scanFail(FailureReason.INVALID_DATA)
    val excludedValues = adapted?.get("excludeRecipeVersionIds")?.jsonArray?.map { UUID.fromString(it.jsonPrimitive.content) }.orEmpty()
    val excluded = excludedValues.toSet()
    if (excluded.size != excludedValues.size) scanFail(FailureReason.INVALID_DATA)
    PortResult.Value(RecipeSubstitutionSelectionInput(UUID.fromString(root.getValue("sourceRecipeVersionId").jsonPrimitive.content),
        WireDocument.parse(JsonObject(root - "sourceRecipeVersionId").toString()),
        constraints.getValue("servings").jsonPrimitive.content.toBigDecimal(), from, to, taste, excluded))
} catch (failure: SubstitutionScanFailure) { PortResult.Failure(failure.reason) }
catch (_: IllegalArgumentException) { PortResult.Failure(FailureReason.INVALID_DATA) }
catch (_: NoSuchElementException) { PortResult.Failure(FailureReason.INVALID_DATA) }

private class SubstitutionScanFailure(val reason: FailureReason) : RuntimeException("Substitution scan unavailable")
private val substitutionSelectionValidator by lazy { ContractBodyValidator.bundled() }
private fun scanFail(reason: FailureReason): Nothing = throw SubstitutionScanFailure(reason)
private fun storageFailure(): Nothing = throw RecipeSubstitutionFailure(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE)
