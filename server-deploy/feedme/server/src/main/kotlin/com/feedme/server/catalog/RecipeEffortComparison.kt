package com.feedme.server.catalog

import java.math.BigDecimal
import java.util.UUID

/** Literal effort comparison, not a reviewed same-meal relationship or selection authority.
 * Different-meal fallback may use this only with explicit permission and current eligibility.
 * The exact source versions and serving point remain attached; unknown cleanup stays unknown. */
class RecipeEffortComparison internal constructor(
    val sourceRecipeVersionId: UUID,
    val targetRecipeVersionId: UUID,
    val sourceMaterialSha256: String,
    val targetMaterialSha256: String,
    val comparisonServings: BigDecimal,
    val before: RecipeSimplificationEffort,
    val after: RecipeSimplificationEffort,
    addedEquipmentIds: List<String>,
    removedEquipmentIds: List<String>,
    improvedDimensions: List<String>,
    regressedDimensions: List<String>,
) {
    private val added = addedEquipmentIds.toList()
    private val removed = removedEquipmentIds.toList()
    private val improved = improvedDimensions.toList()
    private val regressed = regressedDimensions.toList()
    val addedEquipmentIds: List<String> get() = added.toList()
    val removedEquipmentIds: List<String> get() = removed.toList()
    val improvedDimensions: List<String> get() = improved.toList()
    val regressedDimensions: List<String> get() = regressed.toList()

    /** Improvement on the requested dimension, not weighted superiority or no tradeoffs. */
    fun improves(goal: String): Boolean = when (goal) {
        "lessPrep" -> "activeMinutes" in improved
        "lessTime" -> "totalMinutes" in improved
        "lessCleanup" -> "cleanupMinutes" in improved || "utensilCount" in improved
        "overall" -> improved.isNotEmpty()
        else -> throw IllegalArgumentException("Unsupported simplification goal")
    }

    override fun toString() = "RecipeEffortComparison(<redacted>)"
}

internal fun RecipeSimplificationComparison.effortComparison() = RecipeEffortComparison(
    sourceRecipeVersionId, targetRecipeVersionId, sourceMaterialSha256, targetMaterialSha256,
    comparisonServings, before, after, addedEquipmentIds, removedEquipmentIds, improvedDimensions, regressedDimensions,
)
