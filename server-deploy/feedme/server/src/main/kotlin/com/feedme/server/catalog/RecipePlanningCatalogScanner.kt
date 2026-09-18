package com.feedme.server.catalog

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.PortResult
import com.feedme.planning.PlanningContext
import com.feedme.planning.PlanningPolicy
import com.feedme.planning.PlanningRank
import com.feedme.planning.PlanningScanBudget
import com.feedme.planning.PlanningScanResult
import com.feedme.planning.StreamingPlanner

/** Actual catalog-history integration, not an account or HTTP planning service.
 * The caller already owns the view's same transaction and current private-input/content
 * authority. A historical overload recomputes the old complete ranking, NOT current recipe
 * eligibility; its parent still owns the current-head lock. The supplied context is not certified by this adapter. No connection is
 * opened/committed here and no recipe, review, free-access or private permission is invented.
 *
 * Rank callbacks receive the actual checked source original, never the scaled plan-local
 * recipe or an ID-only lookup. They remain provisional until complete traversal and the
 * final view check. A durable caller must use the SAME transaction and roll it back after
 * failure, cancellation or incomplete scans, then separately recheck actual authority.
 */
class RecipePlanningCatalogScanner private constructor(private val view: RecipeCatalogReadView?,
    private val historical: RecipeCatalogHistoricalReadView?, private val policy: PlanningPolicy) {
    constructor(view: RecipeCatalogReadView, policy: PlanningPolicy) : this(view, null, policy)
    constructor(view: RecipeCatalogHistoricalReadView, policy: PlanningPolicy) : this(null, view, policy)
    fun scan(request: WireDocument, context: PlanningContext, budget: PlanningScanBudget,
        pageSize: Int = 32, onEligible: (RecipeCatalogVersion, PlanningRank) -> Unit = { _, _ -> },
    ): PortResult<PlanningScanResult> {
        val source = if (view != null) RecipePlanningCatalogSource(view) else RecipePlanningCatalogSource(checkNotNull(historical))
        source.checkCurrent()
        val result = StreamingPlanner(policy).scan(request, context, source.header, source, budget, pageSize) { candidate, rank ->
            onEligible(source.sourceOf(candidate), rank)
        }
        // The last sink may have waited, been interrupted or violated connection ownership.
        // Never hand off a successful result from a view whose transaction already ended.
        if (result is PortResult.Value) source.checkCurrent()
        return result
    }
    override fun toString() = "RecipePlanningCatalogScanner(<redacted>)"
}
