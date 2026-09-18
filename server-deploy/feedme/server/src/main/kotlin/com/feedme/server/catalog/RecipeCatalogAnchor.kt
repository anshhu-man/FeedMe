package com.feedme.server.catalog

import java.util.UUID

/** Exact historical catalog metadata checked against a retained format-1 or format-2
 * publication and its original taxonomy. This is immutable structural evidence only: an
 * escaped instance is NOT a current transaction receipt, content/rights approval or private
 * access grant. Callers still need a current owning read view and independent authority; use
 * that view's lookupAt(id, revision) for exact historical material and lookupCurrent(id) for
 * separate current lifecycle checks. No current taxonomy is substituted into this value.
 *
 * requestSha256 binds the actual bounded publication, not a fabricated aggregate snapshot
 * or a cryptographic digest of the whole historical candidate universe. versionCount is the
 * checked distinct-version count at this revision; it does not certify a completed scan. */
class RecipeCatalogAnchor internal constructor(
    val releaseId: UUID,
    val revision: Long,
    val requestSha256: String,
    val taxonomyRevision: String,
    val taxonomySha256: String,
    val versionCount: Long,
    ingredients: List<RecipeIngredientComposition>,
) {
    private val retainedIngredients = ingredients.toList()
    val ingredients: List<RecipeIngredientComposition> get() = retainedIngredients.toList()
    override fun toString() = "RecipeCatalogAnchor(<redacted>)"
}
