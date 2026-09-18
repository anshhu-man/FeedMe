package com.feedme.server.catalog

import com.feedme.server.db.CommitOutcomeUnknown
import com.feedme.server.db.PgTransactions
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Trusted DB publication boundary, not an HTTP/staff authentication API. Explicit owner
 * authority is required even for identical historical replay. A release is an immutable
 * full snapshot; publication/retirement always creates a new original, never edits old rows.
 * No consumer invalidation event exists yet for ingredient releases: do not attach cached
 * planning/cooking consumers until that separate integration is implemented. */
class IngredientCatalogStore(val environment: String, private val transactions: PgTransactions,
    private val authority: IngredientPublicationAuthority, internal val limits: IngredientCatalogLimits,
    internal val searchMode: IngredientSearchMode) {
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    fun publish(original: IngredientReleaseRequest): IngredientPublicationReceipt = safe {
        if (original.items.size > limits.maxIngredients || original.exactDocument.encodeToByteArray().size > limits.maxReleaseBytes ||
            original.searchMode != searchMode) fail(IngredientCatalogFailureCode.INVALID_RELEASE)
        transactions.run { c ->
            checkCompatibility(c)
            authority.lockPublication(c, environment, original)
            // The ONLY empty-head creator is this explicitly authorized first publication.
            // A failed transaction never leaves an initialized-but-empty public catalog.
            if (original.expectedRevision == 0L) c.prepareStatement(
                "INSERT INTO catalog.ingredient_heads(environment,revision,release_id) VALUES(?,0,NULL) ON CONFLICT DO NOTHING").use {
                it.setString(1, environment); it.executeUpdate()
            }
            val head = head(c, exclusive = true) ?: fail(IngredientCatalogFailureCode.REVISION_CONFLICT)
            val retained = release(c, original.releaseId)
            if (retained != null) {
                if (retained.original.requestSha256 != original.requestSha256 || retained.original.exactDocument != original.exactDocument)
                    fail(IngredientCatalogFailureCode.ORIGINAL_MISMATCH)
                authority.revalidatePublication(c, environment, original)
                // Historical acknowledgement never moves a newer publication head backwards.
                IngredientPublicationReceipt(original.releaseId, retained.revision, original.requestSha256, true)
            } else {
                if (head.first != original.expectedRevision) fail(IngredientCatalogFailureCode.REVISION_CONFLICT)
                head.second?.let { previous ->
                    val old = release(c, previous) ?: fail(IngredientCatalogFailureCode.STORAGE_UNAVAILABLE)
                    validateSuccessor(old.original, original)
                }
                val next = original.expectedRevision + 1
                c.prepareStatement("INSERT INTO catalog.ingredient_releases(environment,release_id,revision,predecessor_revision,request_sha256,content_sha256,exact_document) VALUES(?,?,?,?,?,?,?)").use {
                    it.setString(1, environment); it.setObject(2, original.releaseId); it.setLong(3, next); it.setLong(4, original.expectedRevision)
                    it.setString(5, original.requestSha256); it.setString(6, original.contentSha256); it.setString(7, original.exactDocument)
                    check(it.executeUpdate() == 1)
                }
                for (item in original.items.sortedBy { it.id.toString() }) {
                    c.prepareStatement("INSERT INTO catalog.ingredient_release_items(environment,release_id,ingredient_id,body,reviewed,published,free_access) VALUES(?,?,?,?::jsonb,?,?,?)").use {
                        it.owner(original.releaseId); it.setObject(3, item.id); it.setString(4, item.ingredient.toString())
                        it.setBoolean(5, item.reviewed); it.setBoolean(6, item.published); it.setBoolean(7, item.freeAccess); check(it.executeUpdate() == 1)
                    }
                    aliases(item).forEachIndexed { index, value ->
                        c.prepareStatement("INSERT INTO catalog.ingredient_release_aliases(environment,release_id,ingredient_id,position,literal_text) VALUES(?,?,?,?,?)").use {
                            it.owner(original.releaseId); it.setObject(3, item.id); it.setInt(4, index); it.setString(5, value); check(it.executeUpdate() == 1)
                        }
                    }
                }
                c.prepareStatement("UPDATE catalog.ingredient_heads SET revision=?,release_id=? WHERE environment=? AND revision=?").use {
                    it.setLong(1, next); it.setObject(2, original.releaseId); it.setString(3, environment); it.setLong(4, head.first); check(it.executeUpdate() == 1)
                }
                val written = release(c, original.releaseId) ?: fail(IngredientCatalogFailureCode.STORAGE_UNAVAILABLE)
                if (written.revision != next || written.original.exactDocument != original.exactDocument) fail(IngredientCatalogFailureCode.STORAGE_UNAVAILABLE)
                authority.revalidatePublication(c, environment, original)
                IngredientPublicationReceipt(original.releaseId, next, original.requestSha256, false)
            }
        }
    }

    /** Schema-only admission; never creates/repairs a head or locks choice rows. */
    fun checkCompatibility(connection: Connection) = safe {
        require(!connection.autoCommit)
        val checksum = IngredientCatalogStore::class.java.getResourceAsStream("/db/migration/V013__ingredient_catalog.sql")
            ?.use { catalogSha(it.readBytes().decodeToString()) } ?: fail(IngredientCatalogFailureCode.NOT_CONFIGURED)
        connection.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=13").use {
            it.executeQuery().use { r -> if (!r.next() || r.getString(1) != checksum || r.next()) fail(IngredientCatalogFailureCode.NOT_CONFIGURED) }
        }
        connection.createStatement().use { s ->
            s.executeQuery("SELECT h.revision,h.release_id,r.predecessor_revision,r.request_sha256,r.content_sha256,r.exact_document,r.published_at," +
                "i.ingredient_id,i.body,i.reviewed,i.published,i.free_access,a.position,a.literal_text FROM catalog.ingredient_heads h " +
                "JOIN catalog.ingredient_releases r ON r.environment=h.environment JOIN catalog.ingredient_release_items i ON i.environment=r.environment " +
                "JOIN catalog.ingredient_release_aliases a ON a.environment=i.environment WHERE false").close()
        }
    }

    /** Caller already owns current principal proof. This snapshot itself grants no access.
     * Shared head lock is retained through the caller's validation/search and final principal
     * check; publishers take its exclusive counterpart before changing selectable facts. */
    internal fun current(connection: Connection): Snapshot = safe {
        require(!connection.autoCommit)
        val head = head(connection, exclusive = false) ?: fail(IngredientCatalogFailureCode.NOT_CONFIGURED)
        val id = head.second ?: fail(IngredientCatalogFailureCode.NOT_CONFIGURED)
        val value = release(connection, id) ?: fail(IngredientCatalogFailureCode.STORAGE_UNAVAILABLE)
        if (head.first != value.revision || value.original.searchMode != searchMode) fail(IngredientCatalogFailureCode.NOT_CONFIGURED)
        value
    }

    internal class Snapshot(val revision: Long, val original: IngredientReleaseRequest)
    private fun validateSuccessor(previous: IngredientReleaseRequest, proposed: IngredientReleaseRequest) {
        val next = proposed.items.associateBy { it.id }
        for (old in previous.items) {
            // Retirement keeps the canonical identity; deleting/reintroducing an ID may not
            // reset its version or creation time. Capacity exhaustion needs an explicit later
            // retention design, not silent eviction of published history.
            val item = next[old.id] ?: fail(IngredientCatalogFailureCode.INVALID_RELEASE)
            val before = old.ingredient; val after = item.ingredient
            val oldVersion = before.getValue("version").jsonPrimitive.content.toBigDecimal().longValueExact()
            val newVersion = after.getValue("version").jsonPrimitive.content.toBigDecimal().longValueExact()
            val same = before - "version" == after - "version"
            if (before["createdAt"] != after["createdAt"] || newVersion < oldVersion ||
                (newVersion == oldVersion && !same) ||
                java.time.Instant.parse(after.getValue("updatedAt").jsonPrimitive.content) < java.time.Instant.parse(before.getValue("updatedAt").jsonPrimitive.content))
                fail(IngredientCatalogFailureCode.INVALID_RELEASE)
        }
    }
    private fun head(c: Connection, exclusive: Boolean): Pair<Long, UUID?>? = c.prepareStatement(
        "SELECT revision,release_id FROM catalog.ingredient_heads WHERE environment=? FOR ${if (exclusive) "UPDATE" else "SHARE"}").use {
        it.setString(1, environment); it.executeQuery().use { r -> if (!r.next()) null else r.getLong(1) to r.getObject(2, UUID::class.java) }
    }
    private fun release(c: Connection, id: UUID): Snapshot? {
        val snapshot = c.prepareStatement("SELECT revision,predecessor_revision,request_sha256,content_sha256,exact_document FROM catalog.ingredient_releases WHERE environment=? AND release_id=? FOR SHARE").use { s ->
            s.owner(id); s.executeQuery().use { r ->
                if (!r.next()) null else {
                    val text = r.getString(5)
                    if (text.encodeToByteArray().size > limits.maxReleaseBytes) fail(IngredientCatalogFailureCode.STORAGE_UNAVAILABLE)
                    val original = decodeRelease(text)
                    if (original.releaseId != id || original.expectedRevision != r.getLong(2) || original.expectedRevision + 1 != r.getLong(1) ||
                        original.requestSha256 != r.getString(3) || original.contentSha256 != r.getString(4) || original.items.size > limits.maxIngredients)
                        fail(IngredientCatalogFailureCode.STORAGE_UNAVAILABLE)
                    Snapshot(r.getLong(1), original)
                }
            }
        } ?: return null
        val expected = snapshot.original.items.associateBy { it.id }
        c.prepareStatement("SELECT ingredient_id,body,reviewed,published,free_access FROM catalog.ingredient_release_items WHERE environment=? AND release_id=? ORDER BY ingredient_id FOR SHARE").use { s ->
            s.owner(id); s.executeQuery().use { r ->
                var count = 0
                while (r.next()) {
                    val item = expected[r.getObject(1, UUID::class.java)] ?: fail(IngredientCatalogFailureCode.STORAGE_UNAVAILABLE)
                    // JSONB numeric spelling may differ; the canonical Ingredient only has an
                    // integral version. Compare its exact integer value, never through Double.
                    val body = Json.parseToJsonElement(r.getString(2)).jsonObject
                    val sameVersion = body.getValue("version").jsonPrimitive.content.toBigDecimal().compareTo(item.ingredient.getValue("version").jsonPrimitive.content.toBigDecimal()) == 0
                    if (!sameVersion || body - "version" != item.ingredient - "version" || r.getBoolean(3) != item.reviewed ||
                        r.getBoolean(4) != item.published || r.getBoolean(5) != item.freeAccess) fail(IngredientCatalogFailureCode.STORAGE_UNAVAILABLE)
                    count++
                }
                if (count != expected.size) fail(IngredientCatalogFailureCode.STORAGE_UNAVAILABLE)
            }
        }
        val actualAliases = mutableMapOf<UUID, MutableList<String>>()
        c.prepareStatement("SELECT ingredient_id,position,literal_text FROM catalog.ingredient_release_aliases WHERE environment=? AND release_id=? ORDER BY ingredient_id,position FOR SHARE").use { s ->
            s.owner(id); s.executeQuery().use { r -> while (r.next()) {
                val rows = actualAliases.getOrPut(r.getObject(1, UUID::class.java)) { mutableListOf() }
                if (r.getInt(2) != rows.size || rows.size > 256) fail(IngredientCatalogFailureCode.STORAGE_UNAVAILABLE)
                rows += r.getString(3)
            } }
        }
        if (actualAliases != expected.mapValues { aliases(it.value) }) fail(IngredientCatalogFailureCode.STORAGE_UNAVAILABLE)
        return snapshot
    }
    private fun aliases(item: IngredientReleaseItem) = listOf(item.ingredient.getValue("name").jsonPrimitive.content) +
        item.ingredient.getValue("aliases").jsonArray.map { it.jsonPrimitive.content }
    private fun PreparedStatement.owner(id: UUID) { setString(1, environment); setObject(2, id) }
    private fun fail(code: IngredientCatalogFailureCode): Nothing = throw IngredientCatalogFailure(code)
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (f: IngredientCatalogFailure) { throw f }
        catch (f: CommitOutcomeUnknown) { throw f }
        catch (f: CancellationException) { throw f }
        catch (f: InterruptedException) { Thread.currentThread().interrupt(); throw f }
        catch (f: java.sql.SQLException) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Ingredient catalog interrupted")
            if (f.sqlState in setOf("40001", "40P01")) throw f
            fail(IngredientCatalogFailureCode.STORAGE_UNAVAILABLE)
        }
        catch (_: Exception) { fail(IngredientCatalogFailureCode.STORAGE_UNAVAILABLE) }
    override fun toString() = "IngredientCatalogStore(<redacted>)"
}
