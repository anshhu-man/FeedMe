package com.feedme.server.catalog

import java.sql.Connection
import java.util.UUID

/** Exact retained publication provenance, not current authority or a copy/Plan grant. */
class RecipeSubstitutionVersion internal constructor(val record: RecipeSubstitutionRecord,
    val publicationId: UUID, val revision: Long, val requestSha256: String,
    val catalogRevision: Long, val catalogReleaseId: UUID, val catalogRequestSha256: String) {
    override fun toString() = "RecipeSubstitutionVersion(<redacted>)"
}

class RecipeSubstitutionPage internal constructor(entries: List<RecipeSubstitutionVersion>, val nextAfter: UUID?) {
    private val retained = entries.toList()
    val entries: List<RecipeSubstitutionVersion> get() = retained.toList()
    override fun toString() = "RecipeSubstitutionPage(<redacted>)"
}

/** Structural pair resolved from the real current journal. Deliberately preserves lifecycle:
 * a recalled edge/recipe remains auditable but cannot be offered as a new ready adaptation. */
class RecipeSubstitutionCatalogPair internal constructor(val edge: RecipeSubstitutionVersion,
    val source: RecipeCatalogVersion, val target: RecipeCatalogVersion, val comparison: RecipeSubstitutionComparison) {
    override fun toString() = "RecipeSubstitutionCatalogPair(<redacted>)"
}

/** Actual same-connection/transaction/thread traversal retaining all statuses. Every operation
 * checks both pinned heads. No standalone snapshot, automatic filtering, source permission,
 * global-selection result or private account data is provided by this view. */
class RecipeSubstitutionReadView internal constructor(internal val owner: RecipeSubstitutionJournal,
    internal val connection: Connection, internal val guard: RecipeSubstitutionJournal.Guard,
    internal val recipes: RecipeCatalogReadView, val revision: Long, val publicationId: UUID,
    val requestSha256: String, val edgeCount: Long) {
    val catalogRevision: Long get() = recipes.revision
    val catalogReleaseId: UUID get() = recipes.releaseId
    fun checkCurrent(): Unit = owner.read(this) { Unit }
    fun lookupCurrent(id: UUID): RecipeSubstitutionVersion? = lookupAt(id, revision)
    fun lookupAt(id: UUID, revision: Long): RecipeSubstitutionVersion? = owner.read(this) { owner.lookup(this, id, revision) }
    fun page(after: UUID? = null, limit: Int = 32): RecipeSubstitutionPage = owner.read(this) { owner.page(this, after, limit) }
    /** Latest retained edge records for the exact immutable pair, including draft/recalled.
     * A page is provenance, not eligibility; callers must exhaust it before claiming no edge. */
    fun pageForPair(sourceRecipeVersionId: UUID, targetRecipeVersionId: UUID, after: UUID? = null,
        limit: Int = 32): RecipeSubstitutionPage = owner.read(this) {
        owner.pageForPair(this, sourceRecipeVersionId, targetRecipeVersionId, after, limit)
    }
    fun resolvePair(edgeId: UUID): RecipeSubstitutionCatalogPair? = owner.read(this) { owner.resolvePair(this, edgeId) }
    override fun toString() = "RecipeSubstitutionReadView(<redacted>)"
}
