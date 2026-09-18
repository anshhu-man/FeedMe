package com.feedme.planning

import com.feedme.core.ports.PortResult

/** Pure selection scope/precedence, not review, rights or current-content authority. An actual
 * catalog adapter must derive this from its own checked originals and explicit user choice. */
enum class PlanningScanPreference { PRIMARY, EXPLICIT_FALLBACK, EXCLUDED }

/** Exact pure-engine ordering metadata, not a catalog grant, candidate-completeness proof or
 * persisted continuation. Decimal text is canonical and compared without floating point. */
class PlanningRank internal constructor(
    val recipeVersionId: String,
    val recipeId: String,
    val tasteMatches: Int,
    val dislikedIngredients: Int,
    val confirmedIngredients: Int,
    val activeMinutes: String?,
    val cleanupMinutes: String?,
) : Comparable<PlanningRank> {
    private val active = activeMinutes?.let { checkNotNull(PlanningDecimal.parse(it)) }
    private val cleanup = cleanupMinutes?.let { checkNotNull(PlanningDecimal.parse(it)) }

    override fun compareTo(other: PlanningRank): Int =
        compareValues(other.tasteMatches, tasteMatches).takeUnless { it == 0 }
            ?: compareValues(dislikedIngredients, other.dislikedIngredients).takeUnless { it == 0 }
            ?: compareValues(other.confirmedIngredients, confirmedIngredients).takeUnless { it == 0 }
            ?: compareValues(active, other.active).takeUnless { it == 0 }
            ?: compareValues(cleanup == null, other.cleanup == null).takeUnless { it == 0 }
            ?: compareValues(cleanup, other.cleanup).takeUnless { it == 0 }
            ?: recipeId.compareTo(other.recipeId).takeUnless { it == 0 }
            ?: recipeVersionId.compareTo(other.recipeVersionId)

    override fun toString() = "PlanningRank(<redacted>)"
}

/** Internal bounded accumulator. The traversal owner must validate ordering/uniqueness,
 * page binding, total count and actual EOF, and must retire the scan on any failure before
 * calling finish. finish alone never certifies a complete or authorized catalog traversal. */
internal class PlanningScanEvaluator(
    private val acceptCandidate: (PlanningCandidate, PlanningScanPreference, Set<String>) -> PortResult<PlanningRank?>,
    private val finishScan: () -> PortResult<PlanningDecision>,
) {
    fun accept(candidate: PlanningCandidate, preference: PlanningScanPreference = PlanningScanPreference.PRIMARY,
        requiredAvailable: Set<String> = emptySet()):
        PortResult<PlanningRank?> = acceptCandidate(candidate, preference, requiredAvailable)
    fun finish(): PortResult<PlanningDecision> = finishScan()
    override fun toString() = "PlanningScanEvaluator(<redacted>)"
}
