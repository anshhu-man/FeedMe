package com.feedme.server.catalog

import com.feedme.server.db.CommitOutcomeUnknown
import com.feedme.server.db.EventDraft
import com.feedme.server.db.OutboxStore
import com.feedme.server.db.PgTransactions
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Independent append-only edge publication, never an account/Plan/source-access service.
 * Lock order is mandatory editorial authority -> actual recipe head -> edge head. Each write
 * resolves the complete pair from the real recipe journal, records its actual catalog anchor,
 * appends the original and event atomically, and rechecks authority before commit. There is
 * no accepting default publisher. Constructor wiring does not install an HTTP handler.
 *
 * Bounded commands/pages do not cap retained history. Integrity queries scan history and may
 * hit existing database deadlines; failure never returns a partial catalog or false no-match. */
class RecipeSubstitutionJournal(val environment: String, private val transactions: PgTransactions,
    private val catalog: RecipeCatalogJournal, private val authority: RecipeSubstitutionPublicationAuthority) {
    private val outbox = OutboxStore(transactions)
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(catalog.environment == environment)
    }

    fun publish(original: RecipeSubstitutionPublication): RecipeSubstitutionPublicationReceipt = substitutionSafe {
        transactions.run { c ->
            checkCompatibility(c)
            val guard = Guard(c)
            authority.lockPublication(c, environment, original); guard.check()
            val recipes = catalog.openView(c)
            if (original.expectedRevision == 0L) c.prepareStatement(
                "INSERT INTO catalog.substitution_heads(environment,revision,publication_id) VALUES(?,0,NULL) ON CONFLICT DO NOTHING").use {
                it.setString(1, environment); it.executeUpdate()
            }
            val beforeHead = head(c, true) ?: substitutionFail(RecipeSubstitutionFailureCode.REVISION_CONFLICT)
            checkHistory(c, beforeHead)
            val retained = original(c, original.publicationId)
            if (retained != null) {
                if (retained.original.exactDocument != original.exactDocument)
                    substitutionFail(RecipeSubstitutionFailureCode.ORIGINAL_MISMATCH)
                // Historical replay acknowledges its original write, not current eligibility.
                validateAnchor(c, recipes, retained)
                historicalPair(recipes, retained)
                authority.revalidatePublication(c, environment, original); guard.check()
                recipes.checkCurrent(); requireHead(c, beforeHead); checkHistory(c, beforeHead)
                if (original(c, original.publicationId)?.original?.exactDocument != original.exactDocument)
                    substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE)
                guard.check()
                RecipeSubstitutionPublicationReceipt(original.publicationId, retained.revision, original.requestSha256, true)
            } else {
                if (beforeHead.revision != original.expectedRevision)
                    substitutionFail(RecipeSubstitutionFailureCode.REVISION_CONFLICT)
                val old = reference(c, original.record.definition.id, beforeHead.revision)?.let {
                    checked(c, recipes, it)
                }
                try {
                    validateRecipeSubstitutionSuccessor(old?.record, original.record)
                    // A different command may not append a second copy of the same revision.
                    require(old?.record?.document != original.record.document)
                } catch (_: IllegalArgumentException) { substitutionFail(RecipeSubstitutionFailureCode.INVALID_PUBLICATION) }
                validateNewPair(recipes, original.record)
                if (original.record.updatedAt > databaseNow(c)) substitutionFail(RecipeSubstitutionFailureCode.INVALID_PUBLICATION)
                val revision = original.expectedRevision + 1
                c.prepareStatement("INSERT INTO catalog.substitution_publications(environment,publication_id,revision," +
                    "predecessor_revision,edge_id,edge_version,definition_sha256,record_sha256,request_sha256,exact_document," +
                    "catalog_revision,catalog_release_id,catalog_request_sha256) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)").use {
                    it.owner(original.publicationId); it.setLong(3, revision); it.setLong(4, original.expectedRevision)
                    it.setObject(5, original.record.definition.id); it.setBigDecimal(6, original.record.version.toBigDecimal())
                    it.setString(7, original.record.definition.sha256); it.setString(8, original.record.sha256)
                    it.setString(9, original.requestSha256); it.setString(10, original.exactDocument)
                    it.setLong(11, recipes.revision); it.setObject(12, recipes.releaseId); it.setString(13, recipes.requestSha256)
                    check(it.executeUpdate() == 1)
                }
                c.prepareStatement("UPDATE catalog.substitution_heads SET revision=?,publication_id=? WHERE environment=? AND revision=?").use {
                    it.setLong(1, revision); it.setObject(2, original.publicationId); it.setString(3, environment)
                    it.setLong(4, beforeHead.revision); check(it.executeUpdate() == 1)
                }
                val event = if (original.record.status != "draft") {
                    EventDraft(UUID.randomUUID(), "catalog.substitution.reviewed.v1", 1,
                        "substitution", original.record.definition.id, original.record.version.longValueExact(), "catalog",
                        original.publicationId.toString(), original.publicationId, buildJsonObject {
                            put("substitutionId", original.record.definition.id.toString())
                            put("reviewId", original.reviewId.toString()); put("status", original.record.status)
                        })
                } else null
                val eventSnapshot = event?.let { outbox.append(c, it); requireNewEvent(c, it) }
                val afterHead = Head(revision, original.publicationId)
                val written = original(c, original.publicationId) ?: substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE)
                if (written.original.exactDocument != original.exactDocument)
                    substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE)
                validateAnchor(c, recipes, written); historicalPair(recipes, written)
                authority.revalidatePublication(c, environment, original); guard.check()
                recipes.checkCurrent(); requireHead(c, afterHead); checkHistory(c, afterHead)
                validateNewPair(recipes, original.record)
                if (original(c, original.publicationId)?.original?.exactDocument != original.exactDocument)
                    substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE)
                event?.let { requireNewEvent(c, it, eventSnapshot) }
                guard.check()
                RecipeSubstitutionPublicationReceipt(original.publicationId, revision, original.requestSha256, false)
            }
        }
    }

    fun checkCompatibility(c: Connection): Unit = substitutionSafe {
        localTransaction(c)
        val expected = RecipeSubstitutionJournal::class.java.getResourceAsStream("/db/migration/V025__recipe_substitutions.sql")
            ?.use { catalogSha(it.readBytes().decodeToString()) } ?: substitutionFail(RecipeSubstitutionFailureCode.NOT_CONFIGURED)
        c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=25").use {
            it.executeQuery().use { rows ->
                if (!rows.next() || rows.getString(1) != expected || rows.next()) substitutionFail(RecipeSubstitutionFailureCode.NOT_CONFIGURED)
            }
        }
        c.createStatement().use { it.executeQuery("SELECT environment,revision,publication_id FROM catalog.substitution_heads WHERE false").close() }
    }

    /** Opens only under the caller's live READ COMMITTED transaction; both actual heads are
     * held FOR SHARE until it ends. Opening does not initialize an empty registry. */
    fun openView(c: Connection): RecipeSubstitutionReadView = substitutionSafe {
        checkCompatibility(c)
        val guard = Guard(c)
        val recipes = catalog.openView(c)
        val current = head(c, false) ?: substitutionFail(RecipeSubstitutionFailureCode.NOT_CONFIGURED)
        val id = current.publicationId ?: substitutionFail(RecipeSubstitutionFailureCode.NOT_CONFIGURED)
        checkHistory(c, current)
        val source = original(c, id) ?: substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE)
        if (source.revision != current.revision) substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE)
        validateAnchor(c, recipes, source); historicalPair(recipes, source)
        val count = c.prepareStatement("SELECT count(DISTINCT edge_id) FROM catalog.substitution_publications WHERE environment=? AND revision<=?").use {
            it.setString(1, environment); it.setLong(2, current.revision)
            it.executeQuery().use { rows -> check(rows.next()); rows.getLong(1) }
        }
        guard.check(); recipes.checkCurrent()
        RecipeSubstitutionReadView(this, c, guard, recipes, current.revision, id, source.original.requestSha256, count)
    }

    internal fun <T> read(view: RecipeSubstitutionReadView, action: () -> T): T = substitutionSafe {
        check(view.owner === this)
        view.guard.check(); view.recipes.checkCurrent()
        val expected = Head(view.revision, view.publicationId)
        requireHead(view.connection, expected); checkHistory(view.connection, expected)
        val source = original(view.connection, view.publicationId) ?: substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE)
        if (source.original.requestSha256 != view.requestSha256) substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE)
        action().also { view.guard.check(); view.recipes.checkCurrent(); requireHead(view.connection, expected) }
    }

    internal fun lookup(view: RecipeSubstitutionReadView, id: UUID, revision: Long): RecipeSubstitutionVersion? {
        require(revision in 1..view.revision)
        return reference(view.connection, id, revision)?.let { checked(view.connection, view.recipes, it) }
    }

    internal fun page(view: RecipeSubstitutionReadView, after: UUID?, limit: Int): RecipeSubstitutionPage {
        require(limit in 1..128)
        val refs = view.connection.prepareStatement("SELECT DISTINCT ON(edge_id) edge_id,publication_id,revision " +
            "FROM catalog.substitution_publications WHERE environment=? AND revision<=? " +
            "AND (?::uuid IS NULL OR edge_id>?) ORDER BY edge_id,revision DESC LIMIT ?").use {
            it.setString(1, environment); it.setLong(2, view.revision); it.setObject(3, after); it.setObject(4, after); it.setInt(5, limit + 1)
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(Reference(rows.getObject(1, UUID::class.java),
                rows.getObject(2, UUID::class.java), rows.getLong(3))) } }
        }
        return checkedPage(view, refs, limit)
    }

    internal fun pageForPair(view: RecipeSubstitutionReadView, sourceRecipeVersionId: UUID,
        targetRecipeVersionId: UUID, after: UUID?, limit: Int): RecipeSubstitutionPage {
        require(limit in 1..128)
        // Select each edge's latest record before filtering. Never resurrect an older reviewed
        // revision by filtering lifecycle/status first, or truncate the universe before matching.
        // UUID comparison/order remains PostgreSQL's existing keyset order, not JVM signed order.
        val refs = view.connection.prepareStatement("SELECT edge_id,publication_id,revision FROM (" +
            "SELECT DISTINCT ON(edge_id) edge_id,publication_id,revision,exact_document " +
            "FROM catalog.substitution_publications WHERE environment=? AND revision<=? " +
            "AND (?::uuid IS NULL OR edge_id>?) ORDER BY edge_id,revision DESC) AS latest " +
            "WHERE exact_document::jsonb->'record'->'definition'->>'sourceRecipeVersionId'=?::text " +
            "AND exact_document::jsonb->'record'->'definition'->>'targetRecipeVersionId'=?::text " +
            "ORDER BY edge_id LIMIT ?").use {
            it.setString(1, environment); it.setLong(2, view.revision); it.setObject(3, after); it.setObject(4, after)
            it.setObject(5, sourceRecipeVersionId); it.setObject(6, targetRecipeVersionId); it.setInt(7, limit + 1)
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(Reference(rows.getObject(1, UUID::class.java),
                rows.getObject(2, UUID::class.java), rows.getLong(3))) } }
        }
        return checkedPage(view, refs, limit).also { page ->
            if (page.entries.any { it.record.definition.sourceRecipeVersionId != sourceRecipeVersionId ||
                    it.record.definition.targetRecipeVersionId != targetRecipeVersionId })
                substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE)
        }
    }

    private fun checkedPage(view: RecipeSubstitutionReadView, refs: List<Reference>, limit: Int): RecipeSubstitutionPage {
        var bytes = 0L
        val entries = refs.take(limit).map { ref ->
            checked(view.connection, view.recipes, ref).also {
                bytes += it.record.document.toString().encodeToByteArray(throwOnInvalidSequence = true).size + 512L
                check(bytes <= MAX_PAGE_BYTES)
            }
        }
        return RecipeSubstitutionPage(entries, if (refs.size > limit) entries.last().record.definition.id else null)
    }

    internal fun resolvePair(view: RecipeSubstitutionReadView, edgeId: UUID): RecipeSubstitutionCatalogPair? {
        val edge = lookup(view, edgeId, view.revision) ?: return null
        val source = view.recipes.lookupCurrent(edge.record.definition.sourceRecipeVersionId)
            ?: substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE)
        val target = view.recipes.lookupCurrent(edge.record.definition.targetRecipeVersionId)
            ?: substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE)
        val comparison = try { validateRecipeSubstitutionPair(source.entry, target.entry, edge.record.definition) }
            catch (_: IllegalArgumentException) { substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE) }
        return RecipeSubstitutionCatalogPair(edge, source, target, comparison)
    }

    private fun validateNewPair(recipes: RecipeCatalogReadView, record: RecipeSubstitutionRecord) {
        val source = recipes.lookupCurrent(record.definition.sourceRecipeVersionId)
            ?: substitutionFail(RecipeSubstitutionFailureCode.INVALID_PUBLICATION)
        val target = recipes.lookupCurrent(record.definition.targetRecipeVersionId)
            ?: substitutionFail(RecipeSubstitutionFailureCode.INVALID_PUBLICATION)
        // Withdrawal must remain possible after the underlying recipe is retired/recalled.
        if (record.status != "recalled" && (source.entry.status != "published" || target.entry.status != "published"))
            substitutionFail(RecipeSubstitutionFailureCode.INVALID_PUBLICATION)
        try { validateRecipeSubstitutionPair(source.entry, target.entry, record.definition) }
        catch (_: IllegalArgumentException) { substitutionFail(RecipeSubstitutionFailureCode.INVALID_PUBLICATION) }
    }

    private fun historicalPair(recipes: RecipeCatalogReadView, row: Row) {
        val source = recipes.lookupAt(row.original.record.definition.sourceRecipeVersionId, row.catalogRevision)
            ?: substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE)
        val target = recipes.lookupAt(row.original.record.definition.targetRecipeVersionId, row.catalogRevision)
            ?: substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE)
        try { validateRecipeSubstitutionPair(source.entry, target.entry, row.original.record.definition) }
        catch (_: IllegalArgumentException) { substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE) }
    }

    /** Resolve an actual entry introduced by the anchored release. The existing journal
     * verifies that release's exact original and complete projections; a foreign row with
     * matching header strings alone cannot become publication provenance. */
    private fun validateAnchor(c: Connection, recipes: RecipeCatalogReadView, row: Row) {
        val entryId = c.prepareStatement("SELECT recipe_version_id FROM catalog.recipe_release_entries WHERE environment=? AND release_id=? ORDER BY recipe_version_id LIMIT 1").use {
            it.owner(row.catalogReleaseId); it.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        } ?: substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE)
        val anchor = recipes.lookupAt(entryId, row.catalogRevision) ?: substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE)
        if (anchor.releaseId != row.catalogReleaseId || anchor.revision != row.catalogRevision || anchor.requestSha256 != row.catalogRequestSha256)
            substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE)
    }

    private fun checked(c: Connection, recipes: RecipeCatalogReadView, ref: Reference): RecipeSubstitutionVersion {
        val row = original(c, ref.publicationId) ?: substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE)
        if (row.original.record.definition.id != ref.edgeId || row.revision != ref.revision)
            substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE)
        validateAnchor(c, recipes, row); historicalPair(recipes, row)
        return RecipeSubstitutionVersion(row.original.record, row.original.publicationId, row.revision,
            row.original.requestSha256, row.catalogRevision, row.catalogReleaseId, row.catalogRequestSha256)
    }

    private fun original(c: Connection, id: UUID): Row? = c.prepareStatement("SELECT exact_document,revision,predecessor_revision," +
        "edge_id,edge_version,definition_sha256,record_sha256,request_sha256,catalog_revision,catalog_release_id,catalog_request_sha256 " +
        "FROM catalog.substitution_publications WHERE environment=? AND publication_id=? FOR SHARE").use {
        it.owner(id); it.executeQuery().use { rows -> if (!rows.next()) null else {
            val source = try { decodeRecipeSubstitutionPublication(rows.getString(1)) }
                catch (_: IllegalArgumentException) { substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE) }
            val revision = rows.getLong(2)
            if (source.publicationId != id || source.expectedRevision != rows.getLong(3) || source.expectedRevision + 1 != revision ||
                source.record.definition.id != rows.getObject(4, UUID::class.java) || source.record.version != rows.getBigDecimal(5).toBigIntegerExact() ||
                source.record.definition.sha256 != rows.getString(6) || source.record.sha256 != rows.getString(7) || source.requestSha256 != rows.getString(8))
                substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE)
            Row(source, revision, rows.getLong(9), rows.getObject(10, UUID::class.java), rows.getString(11))
        } }
    }

    private fun reference(c: Connection, id: UUID, revision: Long): Reference? = c.prepareStatement(
        "SELECT publication_id,revision FROM catalog.substitution_publications WHERE environment=? AND edge_id=? AND revision<=? ORDER BY revision DESC LIMIT 1").use {
        it.owner(id); it.setLong(3, revision); it.executeQuery().use { rows ->
            if (rows.next()) Reference(id, rows.getObject(1, UUID::class.java), rows.getLong(2)) else null
        }
    }

    private fun checkHistory(c: Connection, expected: Head) {
        c.prepareStatement("SELECT count(*),coalesce(min(revision),0),coalesce(max(revision),0) " +
            "FROM catalog.substitution_publications WHERE environment=?").use {
            it.setString(1, environment); it.executeQuery().use { rows ->
                check(rows.next())
                if (rows.getLong(1) != expected.revision || rows.getLong(2) != (if (expected.revision == 0L) 0L else 1L) ||
                    rows.getLong(3) != expected.revision) substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE)
            }
        }
        val invalid = c.prepareStatement("SELECT EXISTS(SELECT 1 FROM catalog.substitution_publications WHERE environment=? AND " +
            "((exact_document::jsonb->>'publicationId')::uuid IS DISTINCT FROM publication_id OR " +
            "(exact_document::jsonb->>'expectedRevision')::numeric IS DISTINCT FROM predecessor_revision OR " +
            "(exact_document::jsonb->'record'->'definition'->>'id')::uuid IS DISTINCT FROM edge_id OR " +
            "(exact_document::jsonb->'record'->>'version')::numeric IS DISTINCT FROM edge_version)) OR " +
            "EXISTS(SELECT 1 FROM catalog.substitution_publications WHERE environment=? GROUP BY edge_id " +
            "HAVING min(edge_version)<>1 OR max(edge_version)<>count(*))").use {
            it.setString(1, environment); it.setString(2, environment)
            it.executeQuery().use { rows -> check(rows.next()); rows.getBoolean(1) }
        }
        if (invalid) substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE)
    }

    private fun head(c: Connection, exclusive: Boolean): Head? = c.prepareStatement(
        "SELECT revision,publication_id FROM catalog.substitution_heads WHERE environment=? FOR ${if (exclusive) "UPDATE" else "SHARE"}").use {
        it.setString(1, environment); it.executeQuery().use { rows ->
            if (rows.next()) Head(rows.getLong(1), rows.getObject(2, UUID::class.java)) else null
        }
    }
    private fun requireHead(c: Connection, expected: Head) {
        if (head(c, false) != expected) substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE)
    }
    /** New event only: consumers may legitimately change delivery metadata after commit.
     * Historical original replay does not demand an undelivered event or publish it again. */
    private fun requireNewEvent(c: Connection, event: EventDraft, expected: String? = null): String =
        c.prepareStatement("SELECT event_type,schema_version,aggregate_type,aggregate_id,aggregate_version," +
            "producer,correlation_id,causation_id,payload,to_jsonb(o)::text FROM platform.outbox o WHERE event_id=? FOR SHARE").use {
            it.setObject(1, event.eventId); it.executeQuery().use { rows ->
                if (!rows.next() || rows.getString(1) != event.eventType || rows.getInt(2) != event.schemaVersion ||
                    rows.getString(3) != event.aggregateType || rows.getObject(4, UUID::class.java) != event.aggregateId ||
                    rows.getLong(5) != event.aggregateVersion || rows.getString(6) != event.producer ||
                    rows.getString(7) != event.correlationId || rows.getObject(8, UUID::class.java) != event.causationId ||
                    recipeJsonIdentity(Json.parseToJsonElement(rows.getString(9))) != recipeJsonIdentity(event.data))
                    substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE)
                val complete = Json.parseToJsonElement(rows.getString(10)).jsonObject
                val snapshot = recipeJsonIdentity(complete)
                if (complete["attempts"] != JsonPrimitive(0) || listOf("published_at", "lease_token", "lease_expires_at",
                        "quarantined_at", "last_failure_code").any { complete[it] != JsonNull } ||
                    (expected != null && snapshot != expected) || rows.next())
                    substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE)
                snapshot
            }
        }
    private fun PreparedStatement.owner(id: UUID) { setString(1, environment); setObject(2, id) }
    private data class Head(val revision: Long, val publicationId: UUID?)
    private data class Reference(val edgeId: UUID, val publicationId: UUID, val revision: Long)
    private data class Row(val original: RecipeSubstitutionPublication, val revision: Long,
        val catalogRevision: Long, val catalogReleaseId: UUID, val catalogRequestSha256: String)

    internal class Guard(private val c: Connection) {
        private val thread = Thread.currentThread()
        private val id = transactionId(c)
        init { localTransaction(c) }
        fun check() {
            check(Thread.currentThread() === thread); localTransaction(c); check(transactionId(c) == id)
        }
    }
    override fun toString() = "RecipeSubstitutionJournal(<redacted>)"
    companion object { const val MAX_PAGE_BYTES = 2_097_152 }
}

private fun localTransaction(c: Connection) {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("Substitution journal interrupted")
    check(!c.isClosed && !c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
}
private fun transactionId(c: Connection): Long {
    localTransaction(c)
    return c.createStatement().use { it.executeQuery("SELECT txid_current()").use { rows -> check(rows.next()); rows.getLong(1) } }
}
private fun databaseNow(c: Connection) = c.createStatement().use {
    it.executeQuery("SELECT clock_timestamp()").use { rows -> check(rows.next()); rows.getObject(1, OffsetDateTime::class.java).toInstant() }
}
private fun substitutionFail(code: RecipeSubstitutionFailureCode): Nothing = throw RecipeSubstitutionFailure(code)
private fun <T> substitutionSafe(action: () -> T): T = try { action() }
catch (failure: RecipeSubstitutionFailure) { throw failure }
catch (failure: RecipeCatalogFailure) { substitutionFail(if (failure.code == RecipeCatalogFailureCode.NOT_CONFIGURED)
    RecipeSubstitutionFailureCode.NOT_CONFIGURED else RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE) }
catch (failure: CommitOutcomeUnknown) { throw failure }
catch (failure: CancellationException) { throw failure }
catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
catch (failure: SQLException) {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("Substitution journal interrupted")
    if (failure.sqlState in setOf("40001", "40P01")) throw failure
    substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE)
}
catch (_: Exception) { substitutionFail(RecipeSubstitutionFailureCode.STORAGE_UNAVAILABLE) }
