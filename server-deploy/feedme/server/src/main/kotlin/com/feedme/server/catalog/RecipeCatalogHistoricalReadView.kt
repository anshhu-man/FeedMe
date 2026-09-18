package com.feedme.server.catalog

import java.sql.Connection
import java.util.UUID

/** Historical material on the actual parent view's still-live connection/transaction/thread.
 * Only RecipeCatalogReadView.openHistorical constructs this after verifying all six anchor
 * fields against the retained publication, original taxonomy and distinct version count.
 * Every operation re-enters the parent's current-head guard; closing/committing/rolling back
 * that transaction invalidates this child too, even when the JDBC connection is reused.
 *
 * Pages preserve every lifecycle state at the historical anchor and exclude newer versions.
 * Historical published/free status is NOT current recipe access: an owning consumer must
 * separately inspect the parent's current material and its actual guest/account authority.
 * There is no arbitrary source/currentness callback, connection replacement or fake pin.
 */
class RecipeCatalogHistoricalReadView internal constructor(
    private val parent: RecipeCatalogReadView,
    private val journal: RecipeCatalogJournal,
    private val connection: Connection,
    private val transactionId: Long,
    private val thread: Thread,
    anchor: RecipeCatalogAnchor,
) {
    val revision: Long = anchor.revision
    val releaseId: UUID = anchor.releaseId
    val requestSha256: String = anchor.requestSha256
    val taxonomyRevision: String = anchor.taxonomyRevision
    val taxonomySha256: String = anchor.taxonomySha256
    val versionCount: Long = anchor.versionCount
    private val retainedIngredients = anchor.ingredients
    val ingredients: List<RecipeIngredientComposition> get() = retainedIngredients.toList()

    /** Live parent transaction check, not certification of current eligibility of old content. */
    fun checkCurrent(): Unit = journal.read(parent, connection, transactionId, thread) { Unit }

    fun page(after: UUID? = null, limit: Int = 32): RecipeCatalogPage =
        journal.read(parent, connection, transactionId, thread) {
            journal.readPage(connection, revision, after, limit)
        }

    fun lookup(id: UUID): RecipeCatalogVersion? = journal.read(parent, connection, transactionId, thread) {
        journal.readVersion(connection, id, revision)
    }

    /** Historical relationship only; recalled/retired sources remain auditable, not offerable. */
    fun simplification(sourceId: UUID, targetId: UUID): RecipeCatalogSimplification? =
        journal.read(parent, connection, transactionId, thread) {
            journal.readSimplification(connection, revision, sourceId, targetId)
        }

    override fun toString() = "RecipeCatalogHistoricalReadView(<redacted>)"
}
