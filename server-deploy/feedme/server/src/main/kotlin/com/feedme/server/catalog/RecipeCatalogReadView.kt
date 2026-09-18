package com.feedme.server.catalog

import java.sql.Connection
import java.util.UUID

/** One checked immutable source entry, including its actual publication provenance.
 * This is structural evidence, not editorial, private-read or saved-copy authority. */
class RecipeCatalogVersion internal constructor(val entry: RecipeCatalogEntry, val releaseId: UUID,
    val revision: Long, val requestSha256: String) {
    override fun toString() = "RecipeCatalogVersion(<redacted>)"
}

/** Checked structural pair with both real publication provenances. It neither authorizes a
 * plan nor certifies current eligibility; consumers must recheck status, inputs, constraints,
 * rights, requested comparison servings and the still-live transaction before proposing it. */
class RecipeCatalogSimplification internal constructor(val source: RecipeCatalogVersion,
    val target: RecipeCatalogVersion, val comparison: RecipeSimplificationComparison) {
    override fun toString() = "RecipeCatalogSimplification(<redacted>)"
}

/** A bounded traversal page, never a complete PlanningEvidenceSnapshot. Null nextAfter
 * means the checked keyset is exhausted; byte-limit failures return no partial page. */
class RecipeCatalogPage internal constructor(entries: List<RecipeCatalogVersion>, val nextAfter: UUID?) {
    private val retained = entries.toList()
    val entries: List<RecipeCatalogVersion> get() = retained.toList()
    override fun toString() = "RecipeCatalogPage(<redacted>)"
}

/** Same-connection, same-transaction/thread read view. Its head FOR SHARE lock is owned by
 * the caller's transaction; commit/rollback ends this view even if that connection is reused.
 * All statuses are retained. Filtering this page never certifies global candidate exhaustion.
 * The taxonomy remains bounded at 1024; the retained recipe-version history has no 128 cap.
 * A default page can exceed the 2MiB response bound for large entries: request a smaller limit
 * explicitly. No automatic truncation, stream, HTTP cursor, cache or planning activation exists. */
class RecipeCatalogReadView internal constructor(
    private val journal: RecipeCatalogJournal,
    private val connection: Connection,
    private val transactionId: Long,
    private val thread: Thread,
    val revision: Long,
    val releaseId: UUID,
    val requestSha256: String,
    val taxonomyRevision: String,
    val taxonomySha256: String,
    val versionCount: Long,
    ingredients: List<RecipeIngredientComposition>,
) {
    private val retainedIngredients = ingredients.toList()
    val ingredients: List<RecipeIngredientComposition> get() = retainedIngredients.toList()

    /** Recheck the same transaction/thread and exact pinned source after caller callbacks.
     * This is catalog-read currentness only, not private-input or publication authority. */
    fun checkCurrent(): Unit = journal.read(this, connection, transactionId, thread) { Unit }

    /** Verify every expected anchor field against the actual retained historical original.
     * The same current transaction/thread guards apply as for page and lookup operations.
     * Returned metadata can escape, but is not a current receipt or authority grant. */
    fun verifyAnchor(releaseId: UUID, revision: Long, requestSha256: String,
        taxonomyRevision: String, taxonomySha256: String, versionCount: Long): RecipeCatalogAnchor =
        journal.read(this, connection, transactionId, thread) {
            journal.readAnchor(connection, this.revision, releaseId, revision, requestSha256,
                taxonomyRevision, taxonomySha256, versionCount)
        }

    /** Open an actual historical traversal under THIS live current-head transaction. Expected
     * metadata is verified against its real retained original; an escaped RecipeCatalogAnchor
     * cannot be supplied as a substitute. This view's own page/current lookup never changes. */
    fun openHistorical(releaseId: UUID, revision: Long, requestSha256: String,
        taxonomyRevision: String, taxonomySha256: String, versionCount: Long): RecipeCatalogHistoricalReadView =
        journal.read(this, connection, transactionId, thread) {
            val anchor = journal.readAnchor(connection, this.revision, releaseId, revision, requestSha256,
                taxonomyRevision, taxonomySha256, versionCount)
            RecipeCatalogHistoricalReadView(this, journal, connection, transactionId, thread, anchor)
        }

    fun page(after: UUID? = null, limit: Int = 32): RecipeCatalogPage = journal.read(this, connection, transactionId, thread) {
        journal.readPage(connection, revision, after, limit)
    }
    fun lookupAt(id: UUID, revision: Long): RecipeCatalogVersion? = journal.read(this, connection, transactionId, thread) {
        require(revision in 1..this.revision)
        journal.readVersion(connection, id, revision)
    }
    fun lookupCurrent(id: UUID): RecipeCatalogVersion? = lookupAt(id, revision)
    fun simplification(sourceId: UUID, targetId: UUID): RecipeCatalogSimplification? =
        journal.read(this, connection, transactionId, thread) {
            journal.readSimplification(connection, revision, sourceId, targetId)
        }
    override fun toString() = "RecipeCatalogReadView(<redacted>)"
}
