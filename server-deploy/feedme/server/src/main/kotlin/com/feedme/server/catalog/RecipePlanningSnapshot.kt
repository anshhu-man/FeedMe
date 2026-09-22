package com.feedme.server.catalog

import java.util.UUID
import kotlinx.serialization.json.*

/** Complete current catalog projection for the bounded v1/v2 account planner. Journal
 * deltas are not complete catalogs: traverse the checked view, including unchanged,
 * retired and recalled versions. Never turn a partial page into a complete ranking.
 * Retain the legacy release's 1 MiB catalog budget, leaving room for private input
 * evidence inside the planner's 2 MiB envelope. Delta publication must not bypass it.
 * Larger histories require the separate streaming/manifest planning adapter; refuse
 * them explicitly here rather than silently changing the existing evidence format.
 * This document is structural evidence only, not account, editorial or copy authority.
 */
internal fun RecipeCatalogReadView.planningCatalogDocument(): JsonObject {
    checkCurrent()
    if (versionCount > 128) throw RecipeCatalogFailure(RecipeCatalogFailureCode.NOT_CONFIGURED)
    val entries = mutableListOf<RecipeCatalogEntry>()
    var after: UUID? = null
    do {
        val page = page(after, 8)
        entries += page.entries.map { it.entry }
        if (entries.size.toLong() > versionCount || entries.size > 128)
            throw RecipeCatalogFailure(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
        after = page.nextAfter
    } while (after != null)
    if (entries.size.toLong() != versionCount)
        throw RecipeCatalogFailure(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
    checkCurrent()
    val document = buildJsonObject {
        put("revision", revision.toString()); put("taxonomyRevision", taxonomyRevision)
        put("ingredients", JsonArray(ingredients.sortedBy { it.ingredientId.toString() }.map { it.document() }))
        put("candidates", JsonArray(entries.sortedBy { it.recipeVersionId.toString() }.map { entry ->
            buildJsonObject { put("recipe", entry.recipe); put("review", legacyPlanningReview(entry.review)) }
        }))
    }
    if (document.toString().encodeToByteArray(throwOnInvalidSequence = true).size > RecipeCatalogRelease.MAX_BYTES)
        throw RecipeCatalogFailure(RecipeCatalogFailureCode.NOT_CONFIGURED)
    return document
}
