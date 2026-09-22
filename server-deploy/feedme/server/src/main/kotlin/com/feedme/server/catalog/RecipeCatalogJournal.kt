package com.feedme.server.catalog

import com.feedme.server.db.*
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Append-only bounded changes on the SAME catalog head as V015. Original format-1
 * publications remain verbatim; no aggregate format-1 original is synthesized. Publication
 * batches are bounded, not lifetime history. No planner, account adapter, route or copy grant
 * is enabled. Paging bounds client memory; SQL integrity/count queries still scale with history. */
class RecipeCatalogJournal(val environment: String, private val transactions: PgTransactions,
    private val authority: RecipeChangesetPublicationAuthority) {
    private val outbox = OutboxStore(transactions)
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    fun publish(change: RecipeCatalogChangeset): RecipePublicationReceipt = safe {
        transactions.run { c -> publishInTransaction(c, change) }
    }

    /** Same authoritative write kernel for a larger owned transaction. No nested
     * connection/commit; exact authority and outbox checks remain mandatory. */
    internal fun publishInTransaction(c: Connection, change: RecipeCatalogChangeset): RecipePublicationReceipt = run {
            transaction(c)
            checkCompatibility(c)
            authority.lockPublication(c, environment, change)
            if (change.expectedRevision == 0L) c.prepareStatement(
                "INSERT INTO catalog.recipe_heads(environment,revision,release_id) VALUES(?,0,NULL) ON CONFLICT DO NOTHING").use {
                it.setString(1, environment); it.executeUpdate()
            }
            val head = head(c, true) ?: fail(RecipeCatalogFailureCode.REVISION_CONFLICT)
            checkHistory(c, head.revision)
            val retained = original(c, change.releaseId)
            if (retained != null) {
                if (retained.requestSha256 != change.requestSha256 || retained.exactDocument != change.exactDocument)
                    fail(RecipeCatalogFailureCode.ORIGINAL_MISMATCH)
                transaction(c)
                authority.revalidatePublication(c, environment, change)
                transaction(c)
                RecipePublicationReceipt(change.releaseId, retained.expectedRevision + 1, change.requestSha256, true)
            } else {
                if (head.revision != change.expectedRevision) fail(RecipeCatalogFailureCode.REVISION_CONFLICT)
                head.releaseId?.let { if (original(c, it)?.expectedRevision?.plus(1) != head.revision) fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE) }
                val previous = change.entries.associate { it.recipeVersionId to readVersion(c, it.recipeVersionId, head.revision)?.entry }
                try { change.entries.forEach { validateRecipeEntrySuccessor(previous[it.recipeVersionId], it) } }
                catch (_: IllegalArgumentException) { fail(RecipeCatalogFailureCode.INVALID_RELEASE) }
                val overlay = change.entries.associateBy { it.recipeVersionId }
                // Read and verify actual originals outside the submitted-content validation
                // catch: damaged retained storage must not be blamed on the new publication.
                val retainedSources = change.entries.filter { previous[it.recipeVersionId] == null }
                    .flatMap { it.simplificationSources }.map { it.sourceRecipeVersionId }
                    .distinct().filterNot(overlay::containsKey)
                    .associateWith { readVersion(c, it, head.revision)?.entry }
                try {
                    validateNewRecipeSimplifications(change.entries, previous) { sourceId ->
                        overlay[sourceId] ?: retainedSources[sourceId]
                    }
                }
                catch (_: IllegalArgumentException) { fail(RecipeCatalogFailureCode.INVALID_RELEASE) }
                requireTaxonomyIdentity(c, change)
                // Every unchanged retained version must remain resolvable under the complete
                // new taxonomy, including retired/recalled records used by historical pins.
                val known = change.ingredients.map { it.ingredientId }.toSet()
                var after: UUID? = null
                do {
                    val refs = references(c, head.revision, after, 128)
                    for (ref in refs) if (ref.id !in previous) {
                        val old = checkedVersion(c, ref).entry
                        if (old.recipe.getValue("ingredients").jsonArray.any {
                            UUID.fromString(it.jsonObject.getValue("ingredientId").jsonPrimitive.content) !in known
                        }) fail(RecipeCatalogFailureCode.INVALID_RELEASE)
                    }
                    after = refs.lastOrNull()?.id
                } while (refs.size == 128)
                val now = now(c)
                if (change.entries.any { Instant.parse(it.recipe.getValue("updatedAt").jsonPrimitive.content) > now ||
                        it.recall?.effectiveAt?.let { at -> at > now } == true }) fail(RecipeCatalogFailureCode.INVALID_RELEASE)
                requireRecipeStorageBounds(c, change.entries)
                val revision = change.expectedRevision + 1
                c.prepareStatement("INSERT INTO catalog.recipe_releases(environment,release_id,revision,predecessor_revision,request_sha256,content_sha256,taxonomy_revision,taxonomy_sha256,exact_document) VALUES(?,?,?,?,?,?,?,?,?)").use {
                    it.owner(change.releaseId); it.setLong(3, revision); it.setLong(4, change.expectedRevision)
                    it.setString(5, change.requestSha256); it.setString(6, change.contentSha256)
                    it.setString(7, change.taxonomyRevision); it.setString(8, change.taxonomySha256); it.setString(9, change.exactDocument)
                    check(it.executeUpdate() == 1)
                }
                for (entry in change.entries.sortedBy { it.recipeVersionId.toString() }) c.prepareStatement(
                    "INSERT INTO catalog.recipe_release_entries(environment,release_id,recipe_version_id,entry) VALUES(?,?,?,?::jsonb)").use {
                    it.owner(change.releaseId); it.setObject(3, entry.recipeVersionId); it.setString(4, entry.document().toString()); check(it.executeUpdate() == 1)
                }
                for (ingredient in change.ingredients.sortedBy { it.ingredientId.toString() }) c.prepareStatement(
                    "INSERT INTO catalog.recipe_release_compositions(environment,release_id,ingredient_id,component_ids) VALUES(?,?,?,?::jsonb)").use {
                    it.owner(change.releaseId); it.setObject(3, ingredient.ingredientId)
                    it.setString(4, ingredient.document().getValue("componentIds").toString()); check(it.executeUpdate() == 1)
                }
                c.prepareStatement("UPDATE catalog.recipe_heads SET revision=?,release_id=? WHERE environment=? AND revision=?").use {
                    it.setLong(1, revision); it.setObject(2, change.releaseId); it.setString(3, environment); it.setLong(4, head.revision)
                    check(it.executeUpdate() == 1)
                }
                val written = original(c, change.releaseId) ?: fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
                if (written.exactDocument != change.exactDocument) fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
                checkHistory(c, revision)
                events(c, previous, change, revision)
                transaction(c)
                authority.revalidatePublication(c, environment, change)
                transaction(c)
                RecipePublicationReceipt(change.releaseId, revision, change.requestSha256, false)
            }
    }

    /** No catalog initialization or authority decision. Both immutable migration pins required. */
    fun checkCompatibility(c: Connection) = safe {
        transaction(c)
        for ((version, name) in listOf(15 to "V015__recipe_catalog.sql", 16 to "V016__recipe_catalog_history.sql")) {
            val expected = RecipeCatalogJournal::class.java.getResourceAsStream("/db/migration/$name")
                ?.use { catalogSha(it.readBytes().decodeToString()) } ?: fail(RecipeCatalogFailureCode.NOT_CONFIGURED)
            c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=?").use {
                it.setInt(1, version); it.executeQuery().use { rows ->
                    if (!rows.next() || rows.getString(1) != expected || rows.next()) fail(RecipeCatalogFailureCode.NOT_CONFIGURED)
                }
            }
        }
        c.createStatement().use { it.executeQuery("SELECT environment,recipe_version_id,revision,release_id FROM catalog.recipe_version_history WHERE false").close() }
    }

    fun openView(c: Connection): RecipeCatalogReadView = safe {
        checkCompatibility(c)
        val head = head(c, false) ?: fail(RecipeCatalogFailureCode.NOT_CONFIGURED)
        val id = head.releaseId ?: fail(RecipeCatalogFailureCode.NOT_CONFIGURED)
        val source = original(c, id) ?: fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
        if (source.expectedRevision + 1 != head.revision) fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
        checkHistory(c, head.revision)
        val count = countVersions(c, head.revision)
        RecipeCatalogReadView(this, c, transactionId(c), Thread.currentThread(), head.revision, id,
            source.requestSha256, source.taxonomyRevision, source.taxonomySha256, count, source.ingredients)
    }

    internal fun <T> read(view: RecipeCatalogReadView, c: Connection, id: Long, thread: Thread, action: () -> T): T = safe {
        check(Thread.currentThread() === thread)
        transaction(c)
        check(transactionId(c) == id)
        val current = head(c, false) ?: fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
        check(current.revision == view.revision && current.releaseId == view.releaseId)
        val source = original(c, view.releaseId) ?: fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
        check(source.requestSha256 == view.requestSha256 && source.taxonomyRevision == view.taxonomyRevision && source.taxonomySha256 == view.taxonomySha256)
        checkHistory(c, view.revision)
        action().also { check(transactionId(c) == id); transaction(c) }
    }

    internal fun readAnchor(c: Connection, currentRevision: Long, releaseId: UUID, revision: Long,
        requestSha256: String, taxonomyRevision: String, taxonomySha256: String, versionCount: Long): RecipeCatalogAnchor {
        require(revision in 1..currentRevision && versionCount >= 0)
        require(requestSha256.matches(Regex("[0-9a-f]{64}")) && taxonomySha256.matches(Regex("[0-9a-f]{64}")))
        // original() decodes the actual bounded v1/v2 bytes, recomputes every stored hash,
        // and compares this release's full entry/composition projections. In particular,
        // use its taxonomy, never the current view's potentially later composition.
        val source = original(c, releaseId) ?: fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
        if (source.expectedRevision + 1 != revision || source.requestSha256 != requestSha256 ||
            source.taxonomyRevision != taxonomyRevision || source.taxonomySha256 != taxonomySha256)
            fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
        checkHistory(c, revision)
        val count = countVersions(c, revision)
        if (count != versionCount) fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
        return RecipeCatalogAnchor(source.releaseId, revision, source.requestSha256, source.taxonomyRevision,
            source.taxonomySha256, count, source.ingredients)
    }

    private fun countVersions(c: Connection, revision: Long): Long = c.prepareStatement(
        "SELECT count(DISTINCT recipe_version_id) FROM catalog.recipe_version_history WHERE environment=? AND revision<=?").use {
        it.setString(1, environment); it.setLong(2, revision)
        it.executeQuery().use { rows -> check(rows.next()); rows.getLong(1) }
    }

    internal fun readPage(c: Connection, revision: Long, after: UUID?, limit: Int): RecipeCatalogPage {
        require(limit in 1..128)
        val refs = references(c, revision, after, limit + 1)
        val entries = ArrayList<RecipeCatalogVersion>(minOf(limit, refs.size))
        var bytes = 0L
        for (ref in refs.take(limit)) {
            val value = checkedVersion(c, ref)
            bytes += value.entry.document().toString().encodeToByteArray(throwOnInvalidSequence = true).size + 256L
            check(bytes <= MAX_PAGE_BYTES)
            entries += value
        }
        return RecipeCatalogPage(entries, if (refs.size > limit) entries.last().entry.recipeVersionId else null)
    }

    internal fun readVersion(c: Connection, id: UUID, revision: Long): RecipeCatalogVersion? {
        val ref = c.prepareStatement("SELECT recipe_version_id,revision,release_id FROM catalog.recipe_version_history WHERE environment=? AND recipe_version_id=? AND revision<=? ORDER BY revision DESC LIMIT 1").use {
            it.setString(1, environment); it.setObject(2, id); it.setLong(3, revision)
            it.executeQuery().use { rows -> if (rows.next()) Reference(rows.getObject(1, UUID::class.java), rows.getLong(2), rows.getObject(3, UUID::class.java)) else null }
        }
        return ref?.let { checkedVersion(c, it) }
    }

    /** Exact pair at one real revision. Missing edges return null; broken retained references
     * are storage failures. Both entries retain lifecycle/provenance, not an eligibility grant. */
    internal fun readSimplification(c: Connection, revision: Long, sourceId: UUID,
        targetId: UUID): RecipeCatalogSimplification? {
        val target = readVersion(c, targetId, revision) ?: return null
        val evidence = target.entry.simplificationSources.singleOrNull { it.sourceRecipeVersionId == sourceId } ?: return null
        val source = readVersion(c, sourceId, revision) ?: fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
        val comparison = try { validateRecipeSimplificationPair(source.entry, target.entry, evidence) }
            catch (_: IllegalArgumentException) { fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE) }
        return RecipeCatalogSimplification(source, target, comparison)
    }

    private fun references(c: Connection, revision: Long, after: UUID?, limit: Int): List<Reference> = c.prepareStatement(
        "SELECT DISTINCT ON (recipe_version_id) recipe_version_id,revision,release_id FROM catalog.recipe_version_history " +
            "WHERE environment=? AND revision<=? AND (?::uuid IS NULL OR recipe_version_id>?) ORDER BY recipe_version_id,revision DESC LIMIT ?").use {
        it.setString(1, environment); it.setLong(2, revision); it.setObject(3, after); it.setObject(4, after); it.setInt(5, limit)
        it.executeQuery().use { rows -> buildList { while (rows.next()) add(Reference(rows.getObject(1, UUID::class.java), rows.getLong(2), rows.getObject(3, UUID::class.java))) } }
    }

    private fun checkedVersion(c: Connection, ref: Reference): RecipeCatalogVersion {
        val source = original(c, ref.releaseId) ?: fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
        if (source.expectedRevision + 1 != ref.revision) fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
        val entry = source.entries.singleOrNull { it.recipeVersionId == ref.id } ?: fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
        return RecipeCatalogVersion(entry, ref.releaseId, ref.revision, source.requestSha256)
    }

    /** Missing index entries must not masquerade as complete enumeration. All checks execute
     * under the common head lock; normal writes/backfill maintain this same immutable mapping. */
    private fun checkHistory(c: Connection, revision: Long) {
        // Counts from original arrays prevent deleting BOTH a source projection and its
        // history row from turning a retained version into apparent global exhaustion.
        // This is intentionally O(history) database work, not an O(page) claim. Existing
        // transaction statement timeouts refuse the operation; they never return a prefix.
        val counts = c.prepareStatement("SELECT " +
            "(SELECT coalesce(sum(jsonb_array_length(exact_document::jsonb->'content'->'entries')),0) FROM catalog.recipe_releases WHERE environment=? AND revision<=?)," +
            "(SELECT count(*) FROM catalog.recipe_release_entries e JOIN catalog.recipe_releases r ON r.environment=e.environment AND r.release_id=e.release_id WHERE e.environment=? AND r.revision<=?)," +
            "(SELECT count(*) FROM catalog.recipe_version_history WHERE environment=? AND revision<=?)").use {
            for (offset in listOf(1, 3, 5)) { it.setString(offset, environment); it.setLong(offset + 1, revision) }
            it.executeQuery().use { rows -> check(rows.next()); listOf(rows.getLong(1), rows.getLong(2), rows.getLong(3)) }
        }
        if (counts.distinct().size != 1) fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
        val invalid = c.prepareStatement("SELECT EXISTS(SELECT 1 FROM catalog.recipe_release_entries e JOIN catalog.recipe_releases r ON r.environment=e.environment AND r.release_id=e.release_id " +
            "LEFT JOIN catalog.recipe_version_history h ON h.environment=e.environment AND h.recipe_version_id=e.recipe_version_id AND h.release_id=e.release_id AND h.revision=r.revision " +
            "WHERE e.environment=? AND r.revision<=? AND h.recipe_version_id IS NULL) OR EXISTS(SELECT 1 FROM catalog.recipe_version_history h " +
            "LEFT JOIN catalog.recipe_releases r ON r.environment=h.environment AND r.release_id=h.release_id AND r.revision=h.revision " +
            "LEFT JOIN catalog.recipe_release_entries e ON e.environment=h.environment AND e.release_id=h.release_id AND e.recipe_version_id=h.recipe_version_id " +
            "WHERE h.environment=? AND h.revision<=? AND (r.release_id IS NULL OR e.recipe_version_id IS NULL))").use {
            it.setString(1, environment); it.setLong(2, revision); it.setString(3, environment); it.setLong(4, revision)
            it.executeQuery().use { rows -> check(rows.next()); rows.getBoolean(1) }
        }
        if (invalid) fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
    }

    private fun original(c: Connection, id: UUID): RecipeJournalOriginal? {
        val result = c.prepareStatement("SELECT revision,predecessor_revision,request_sha256,content_sha256,taxonomy_revision,taxonomy_sha256,exact_document FROM catalog.recipe_releases WHERE environment=? AND release_id=? FOR SHARE").use {
            it.owner(id); it.executeQuery().use { rows -> if (!rows.next()) null else RecipeJournalOriginal.decode(rows.getString(7)).also { source ->
                if (source.releaseId != id || source.expectedRevision != rows.getLong(2) || source.expectedRevision + 1 != rows.getLong(1) ||
                    source.requestSha256 != rows.getString(3) || source.contentSha256 != rows.getString(4) ||
                    source.taxonomyRevision != rows.getString(5) || source.taxonomySha256 != rows.getString(6)) fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
            } }
        } ?: return null
        val expected = result.entries.associateBy { it.recipeVersionId }
        c.prepareStatement("SELECT recipe_version_id,entry FROM catalog.recipe_release_entries WHERE environment=? AND release_id=? ORDER BY recipe_version_id LIMIT 129 FOR SHARE").use {
            it.owner(id); it.executeQuery().use { rows ->
                var count = 0
                while (rows.next()) {
                    val entry = expected[rows.getObject(1, UUID::class.java)] ?: fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
                    if (recipeJsonIdentity(Json.parseToJsonElement(rows.getString(2))) != recipeJsonIdentity(entry.document())) fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
                    count++
                }
                if (count != expected.size) fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
            }
        }
        val taxonomy = result.ingredients.associateBy { it.ingredientId }
        c.prepareStatement("SELECT ingredient_id,component_ids FROM catalog.recipe_release_compositions WHERE environment=? AND release_id=? ORDER BY ingredient_id LIMIT 1025 FOR SHARE").use {
            it.owner(id); it.executeQuery().use { rows ->
                var count = 0
                while (rows.next()) {
                    val ingredient = taxonomy[rows.getObject(1, UUID::class.java)] ?: fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
                    if (Json.parseToJsonElement(rows.getString(2)) != ingredient.document().getValue("componentIds")) fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
                    count++
                }
                if (count != taxonomy.size) fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
            }
        }
        return result
    }

    private fun requireTaxonomyIdentity(c: Connection, change: RecipeCatalogChangeset) {
        val mismatch = c.prepareStatement("SELECT EXISTS(SELECT 1 FROM catalog.recipe_releases WHERE environment=? AND taxonomy_revision=? AND taxonomy_sha256<>?)").use {
            it.setString(1, environment); it.setString(2, change.taxonomyRevision); it.setString(3, change.taxonomySha256)
            it.executeQuery().use { rows -> check(rows.next()); rows.getBoolean(1) }
        }
        if (mismatch) fail(RecipeCatalogFailureCode.INVALID_RELEASE)
    }

    private fun events(c: Connection, previous: Map<UUID, RecipeCatalogEntry?>, next: RecipeCatalogChangeset, revision: Long) {
        for (entry in next.entries) {
            val before = previous[entry.recipeVersionId]
            val type: String
            val body: JsonObject
            if (before == null) {
                type = "catalog.recipe.published.v1"
                body = buildJsonObject {
                    put("recipeId", entry.recipe.getValue("recipeId")); put("recipeVersionId", entry.recipeVersionId.toString())
                    put("catalogRevision", revision.toString())
                }
            } else if (before.status != "recalled" && entry.status == "recalled") {
                type = "catalog.recipe.recalled.v1"
                val recall = checkNotNull(entry.recall)
                body = buildJsonObject {
                    put("recipeVersionId", entry.recipeVersionId.toString()); put("recallId", recall.recallId.toString())
                    put("reasonCode", recall.reasonCode); put("effectiveAt", recall.effectiveAt.toString())
                }
            } else continue
            outbox.append(c, EventDraft(UUID.randomUUID(), type, 1, "recipe_version", entry.recipeVersionId, entry.version,
                "catalog", next.releaseId.toString(), next.releaseId, body))
        }
    }

    private fun head(c: Connection, exclusive: Boolean): Head? = c.prepareStatement(
        "SELECT revision,release_id FROM catalog.recipe_heads WHERE environment=? FOR ${if (exclusive) "UPDATE" else "SHARE"}").use {
        it.setString(1, environment); it.executeQuery().use { rows -> if (rows.next()) Head(rows.getLong(1), rows.getObject(2, UUID::class.java)) else null }
    }
    private fun transaction(c: Connection) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Recipe journal interrupted")
        check(!c.isClosed && !c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
    }
    private fun transactionId(c: Connection) = c.createStatement().use { it.executeQuery("SELECT txid_current()").use { rows -> check(rows.next()); rows.getLong(1) } }
    private fun now(c: Connection) = c.createStatement().use { it.executeQuery("SELECT clock_timestamp()").use { rows -> check(rows.next()); rows.getObject(1, OffsetDateTime::class.java).toInstant() } }
    private fun PreparedStatement.owner(id: UUID) { setString(1, environment); setObject(2, id) }
    private data class Head(val revision: Long, val releaseId: UUID?)
    private data class Reference(val id: UUID, val revision: Long, val releaseId: UUID)
    private fun fail(code: RecipeCatalogFailureCode): Nothing = throw RecipeCatalogFailure(code)
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: RecipeCatalogFailure) { throw failure }
        catch (failure: CommitOutcomeUnknown) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (failure: java.sql.SQLException) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Recipe journal interrupted")
            if (failure.sqlState in setOf("40001", "40P01")) throw failure
            fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
        }
        catch (_: Exception) { fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE) }
    override fun toString() = "RecipeCatalogJournal(<redacted>)"
    companion object { const val MAX_PAGE_BYTES = 2_097_152 }
}
