package com.feedme.planning

import com.feedme.contracts.CanonicalBodyValidator
import com.feedme.contracts.CanonicalFormats
import com.feedme.contracts.WireDocument
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult

/** An exact caller-pinned catalog anchor, not a provider, approval or authorization grant.
 * The trusted source must bind the full taxonomy and distinct-version count to this same
 * immutable anchor. Count/IDs verify traversal structure, not the truth of external content. */
class PlanningCatalogHeader(val revision: String, val taxonomyRevision: String,
    val expectedCandidateCount: Long, ingredients: List<IngredientComposition>) {
    private val retainedIngredients = ingredients.toList()
    internal val ingredients: List<IngredientComposition> get() = retainedIngredients.toList()
    override fun toString() = "PlanningCatalogHeader(<redacted>)"
}

/** All lifecycle states must participate in traversal; filtering before paging/counting can
 * resurrect an old publication or falsely certify exhaustion. after echoes the requested
 * normalized UUID; nextAfter is the normalized last ID, or null only at actual exhaustion. */
class PlanningCandidateBatch(val revision: String, val taxonomyRevision: String, val after: String?,
    candidates: List<PlanningCandidate>, val nextAfter: String?) {
    private val retainedCandidates = candidates.toList()
    internal val candidates: List<PlanningCandidate> get() = retainedCandidates.toList()
    override fun toString() = "PlanningCandidateBatch(<redacted>)"
}

/** Implementations own bounded I/O, cancellation/deadlines, current authority and the pinned
 * transaction. Exceptions must abort the owning operation, never become an empty last page. */
fun interface PlanningCandidateSource {
    fun read(after: String?, limit: Int): PlanningCandidateBatch
}

/** A completed pure traversal only. eligibleCount includes only ready candidates in the
 * selected scope (both included tiers for scanPreferred); traversedCount includes every entry.
 * This is not a persisted ranking manifest, proof of
 * ownership/current authority, serializable continuation, or permission to issue a Plan. */
class PlanningScanResult internal constructor(val decision: PlanningDecision,
    val traversedCount: Long, val eligibleCount: Long) {
    override fun toString() = "PlanningScanResult(<redacted>)"
}

/** Explicit operation budget chosen by the owning service, not a lifetime catalog cap.
 * Exceeding it refuses the complete operation; no partial recommendation is returned. */
class PlanningScanBudget(val maxCandidates: Long, val maxPages: Long) {
    override fun toString() = "PlanningScanBudget(<redacted>)"
}

/** Complete, bounded-memory filter-before-rank selection over a trusted pinned catalog.
 * Retains one bounded page and at most three best evaluations per included tier (one tier for
 * scan, two for scanPreferred), not all candidate IDs/bodies.
 * An explicit operation budget refuses excessive work instead of masquerading as catalog
 * exhaustion. Source deadlines/cancellation and the eventual same-transaction manifest
 * writer remain mandatory production responsibilities.
 *
 * onEligible emits UNSORTED exact rank rows as candidates are evaluated. Rows emitted before
 * a later error are provisional: a durable caller MUST write them into its owning transaction
 * and roll back on any failure, exception or cancellation. Only a Value result certifies this
 * structural scan completed; even that is not authorization or durable publication. No rank
 * callback is invoked for rejected/uncertain candidates or input-confirmation outcomes.
 */
class StreamingPlanner(policy: PlanningPolicy,
    validator: CanonicalBodyValidator = CanonicalBodyValidator.bundled()) {
    private val planner = DeterministicPlanner(policy, validator)

    fun scan(request: WireDocument, context: PlanningContext, header: PlanningCatalogHeader,
        source: PlanningCandidateSource, budget: PlanningScanBudget, pageSize: Int = 32,
        onEligible: (PlanningCandidate, PlanningRank) -> Unit = { _, _ -> },
    ): PortResult<PlanningScanResult> = scanInternal(request, context, header, source, budget, pageSize,
        false, { PlanningScanPreference.PRIMARY }, { emptySet() }, { candidate, rank, _ -> onEligible(candidate, rank) })

    /** Full traversal with explicit selection tiers. The classifier supplies selection metadata,
     * never permission or reviewed-content proof. The real catalog owner must derive it from
     * exact checked originals and explicit fallback choice. All candidates, even excluded ones,
     * count toward ordering/EOF and structural validation. Classification/source/sink exceptions
     * escape unchanged, and rank callbacks remain provisional until complete success.
     *
     * Ready primary precedes ready fallback, then uncertain primary precedes uncertain fallback.
     * Request-level confirmation takes precedence over all tiers. Excluded content contributes
     * no missing ingredients or issue explanations; no ordinary global winner is substituted. */
    fun scanPreferred(request: WireDocument, context: PlanningContext, header: PlanningCatalogHeader,
        source: PlanningCandidateSource, budget: PlanningScanBudget, pageSize: Int = 32,
        classify: (PlanningCandidate) -> PlanningScanPreference,
        onEligible: (PlanningCandidate, PlanningRank, PlanningScanPreference) -> Unit = { _, _, _ -> },
    ): PortResult<PlanningScanResult> = scanInternal(request, context, header, source, budget, pageSize,
        true, classify, { emptySet() }, onEligible)

    /** Complete preferred traversal with an extra, rejecting-only availability requirement.
     * This can require an optional replacement to be available without rewriting its authored
     * optional flag, recipe, constraints or preferences. It cannot confirm an ingredient or
     * relax any existing filter. IDs must be canonical, at most 128, and present in the actual
     * candidate even when excluded or the request already needs confirmation.
     * Both callbacks run outside input-validation protection, classify before requiredAvailable;
     * source/classifier/requirement/sink failures and cancellation escape unchanged. */
    fun scanWithRequiredAvailability(request: WireDocument, context: PlanningContext, header: PlanningCatalogHeader,
        source: PlanningCandidateSource, budget: PlanningScanBudget, pageSize: Int = 32,
        classify: (PlanningCandidate) -> PlanningScanPreference,
        requiredAvailable: (PlanningCandidate) -> Set<String>,
        onEligible: (PlanningCandidate, PlanningRank, PlanningScanPreference) -> Unit = { _, _, _ -> },
    ): PortResult<PlanningScanResult> = scanInternal(request, context, header, source, budget, pageSize,
        true, classify, requiredAvailable, onEligible)

    private fun scanInternal(request: WireDocument, context: PlanningContext, header: PlanningCatalogHeader,
        source: PlanningCandidateSource, budget: PlanningScanBudget, pageSize: Int, preferred: Boolean,
        classify: (PlanningCandidate) -> PlanningScanPreference,
        requiredAvailable: (PlanningCandidate) -> Set<String>,
        onEligible: (PlanningCandidate, PlanningRank, PlanningScanPreference) -> Unit,
    ): PortResult<PlanningScanResult> = try {
        if (pageSize !in 1..MAX_PAGE_CANDIDATES || header.expectedCandidateCount < 0 ||
            budget.maxCandidates < 0 || budget.maxPages < 1)
            fail(FailureReason.INVALID_DATA)
        if (header.expectedCandidateCount > budget.maxCandidates) fail(FailureReason.UNAVAILABLE)
        val catalog = PlanningCatalog(header.revision, header.taxonomyRevision, emptyList(), header.ingredients)
        val evaluator = (if (preferred) planner.preparePreferredScan(request, context, catalog)
            else planner.prepareScan(request, context, catalog)).unwrap()
        var after: String? = null
        var traversed = 0L
        var eligible = 0L
        var pages = 0L
        while (true) {
            // Do not catch source/sink exceptions: an incomplete scan cannot be success,
            // and the owning transaction must see the original cancellation/failure.
            if (pages >= budget.maxPages) fail(FailureReason.UNAVAILABLE)
            pages++
            val batch = source.read(after, pageSize)
            if (batch.revision != header.revision || batch.taxonomyRevision != header.taxonomyRevision || batch.after != after)
                fail(FailureReason.CONFLICT)
            val candidates = batch.candidates
            if (candidates.size > pageSize || candidates.isEmpty() && batch.nextAfter != null)
                fail(FailureReason.INVALID_DATA)
            if (candidates.size.toLong() > header.expectedCandidateCount - traversed)
                fail(FailureReason.CONFLICT)
            var previous = after
            var pageBytes = 0L
            fun chargeText(value: String) {
                // UTF-8 needs at least this many UTF-16 code units. Refuse an oversized
                // caller-supplied review string before allocating its encoded byte array.
                if (value.length.toLong() > MAX_PAGE_BYTES - pageBytes) fail(FailureReason.INVALID_DATA)
                pageBytes += value.encodeToByteArray().size.toLong()
                if (pageBytes > MAX_PAGE_BYTES) fail(FailureReason.INVALID_DATA)
            }
            for (candidate in candidates) {
                val id = candidate.recipe.id.value.lowercase()
                if (!CanonicalFormats.accepts("uuid", id)) fail(FailureReason.INVALID_DATA)
                if (previous != null && id <= previous) fail(FailureReason.CONFLICT)
                previous = id
                // Bounded aggregate payload accounting, not an HTTP serialization format.
                // Include all variable review strings as well as exact recipe UTF-8 bytes.
                val evidence = candidate.evidence
                if (evidence.baseTypes.size > 128 || evidence.units.size > 128) fail(FailureReason.INVALID_DATA)
                val recipeBytes = candidate.recipe.document.encodeUtf8().size
                if (recipeBytes > 65_536) fail(FailureReason.INVALID_DATA)
                pageBytes += recipeBytes.toLong() + 256L
                if (pageBytes > MAX_PAGE_BYTES) fail(FailureReason.INVALID_DATA)
                chargeText(evidence.reviewReference)
                chargeText(evidence.policyVersion)
                for (value in evidence.baseTypes) chargeText(value)
                for (value in evidence.units) chargeText(value)
            }
            val next = batch.nextAfter
            if (next != null && (next != previous || !CanonicalFormats.accepts("uuid", next) || next != next.lowercase()))
                fail(FailureReason.CONFLICT)
            val observed = traversed + candidates.size
            if (next == null && observed != header.expectedCandidateCount ||
                next != null && observed >= header.expectedCandidateCount)
                fail(FailureReason.CONFLICT)
            for (candidate in candidates) {
                // Outside the evaluator's input-validation protection: a failed actual source
                // classifier must abort the owning operation, never become a benign no-match.
                val preference = classify(candidate)
                val required = requiredAvailable(candidate)
                val rank = evaluator.accept(candidate, preference, required).unwrap()
                if (rank != null) {
                    onEligible(candidate, rank, preference)
                    eligible++ // Bounded by traversed <= the nonnegative Long header count.
                }
            }
            traversed = observed
            if (next == null) break
            after = next
        }
        PortResult.Value(PlanningScanResult(evaluator.finish().unwrap(), traversed, eligible))
    } catch (failure: ScanFailure) {
        PortResult.Failure(failure.reason)
    }

    private fun <T> PortResult<T>.unwrap(): T = when (this) {
        is PortResult.Value -> value
        is PortResult.Failure -> fail(reason)
    }
    private fun fail(reason: FailureReason): Nothing = throw ScanFailure(reason)
    private class ScanFailure(val reason: FailureReason) : Exception("Planning scan unavailable")
    override fun toString() = "StreamingPlanner(<redacted>)"
    companion object {
        const val MAX_PAGE_CANDIDATES = 128
        const val MAX_PAGE_BYTES = 2_097_152L
    }
}
