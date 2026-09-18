package com.feedme.server.catalog

import com.feedme.server.db.*
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Reusable bounded persistence, NOT a populated/production-ready catalog or route adapter.
 * All former recipe-version IDs stay in each successor for the current planner's pin lookup.
 * 128 is that planner's snapshot limit, not a suitable lifetime global catalog capacity.
 * Exhaustion is refused; selected-candidate paging plus targeted historical-pin lookup require
 * a later reviewed adapter change. Nothing evicts history or silently filters recall records.
 * No caches, worker, private owner, saved-copy grant, U06 review or U08 rollout is supplied. */
class RecipeCatalogStore(val environment: String, private val transactions: PgTransactions,
    private val authority: RecipePublicationAuthority) {
    private val outbox = OutboxStore(transactions)
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    fun publish(original: RecipeCatalogRelease): RecipePublicationReceipt = safe {
        transactions.run { c ->
            checkCompatibility(c)
            authority.lockPublication(c, environment, original)
            if (original.expectedRevision == 0L) c.prepareStatement(
                "INSERT INTO catalog.recipe_heads(environment,revision,release_id) VALUES(?,0,NULL) ON CONFLICT DO NOTHING").use {
                it.setString(1, environment); it.executeUpdate()
            }
            val head = head(c, true) ?: fail(RecipeCatalogFailureCode.REVISION_CONFLICT)
            val retained = release(c, original.releaseId)
            if (retained != null) {
                if (retained.original.requestSha256 != original.requestSha256 || retained.original.exactDocument != original.exactDocument)
                    fail(RecipeCatalogFailureCode.ORIGINAL_MISMATCH)
                authority.revalidatePublication(c, environment, original)
                RecipePublicationReceipt(original.releaseId, retained.revision, original.requestSha256, true)
            } else {
                if (head.first != original.expectedRevision) fail(RecipeCatalogFailureCode.REVISION_CONFLICT)
                val previous = head.second?.let { release(c, it) ?: fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE) }
                try { validateRecipeSuccessor(previous?.original, original) }
                catch (_: IllegalArgumentException) { fail(RecipeCatalogFailureCode.INVALID_RELEASE) }
                c.prepareStatement("SELECT taxonomy_sha256 FROM catalog.recipe_releases WHERE environment=? AND taxonomy_revision=?").use {
                    it.setString(1, environment); it.setString(2, original.taxonomyRevision)
                    it.executeQuery().use { rows -> while (rows.next()) if (rows.getString(1) != original.taxonomySha256) fail(RecipeCatalogFailureCode.INVALID_RELEASE) }
                }
                val now = c.createStatement().use { it.executeQuery("SELECT clock_timestamp()").use { r -> check(r.next()); r.getObject(1, OffsetDateTime::class.java).toInstant() } }
                if (original.entries.any { java.time.Instant.parse(it.recipe.getValue("updatedAt").jsonPrimitive.content) > now ||
                        it.recall?.effectiveAt?.let { at -> at > now } == true }) fail(RecipeCatalogFailureCode.INVALID_RELEASE)
                requireRecipeStorageBounds(c, original.entries)
                val revision = original.expectedRevision + 1
                c.prepareStatement("INSERT INTO catalog.recipe_releases(environment,release_id,revision,predecessor_revision,request_sha256,content_sha256,taxonomy_revision,taxonomy_sha256,exact_document) VALUES(?,?,?,?,?,?,?,?,?)").use {
                    it.owner(original.releaseId); it.setLong(3, revision); it.setLong(4, original.expectedRevision)
                    it.setString(5, original.requestSha256); it.setString(6, original.contentSha256)
                    it.setString(7, original.taxonomyRevision); it.setString(8, original.taxonomySha256); it.setString(9, original.exactDocument)
                    check(it.executeUpdate() == 1)
                }
                for (entry in original.entries.sortedBy { it.recipeVersionId.toString() }) c.prepareStatement(
                    "INSERT INTO catalog.recipe_release_entries(environment,release_id,recipe_version_id,entry) VALUES(?,?,?,?::jsonb)").use {
                    it.owner(original.releaseId); it.setObject(3, entry.recipeVersionId); it.setString(4, entry.document().toString()); check(it.executeUpdate() == 1)
                }
                for (ingredient in original.ingredients.sortedBy { it.ingredientId.toString() }) c.prepareStatement(
                    "INSERT INTO catalog.recipe_release_compositions(environment,release_id,ingredient_id,component_ids) VALUES(?,?,?,?::jsonb)").use {
                    it.owner(original.releaseId); it.setObject(3, ingredient.ingredientId)
                    it.setString(4, ingredient.document().getValue("componentIds").toString()); check(it.executeUpdate() == 1)
                }
                c.prepareStatement("UPDATE catalog.recipe_heads SET revision=?,release_id=? WHERE environment=? AND revision=?").use {
                    it.setLong(1, revision); it.setObject(2, original.releaseId); it.setString(3, environment); it.setLong(4, head.first); check(it.executeUpdate() == 1)
                }
                val written = release(c, original.releaseId) ?: fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
                if (written.revision != revision || written.original.exactDocument != original.exactDocument) fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
                events(c, previous?.original, original, revision)
                // After every row/head/event wait, BEFORE committing any publication or ACK.
                authority.revalidatePublication(c, environment, original)
                RecipePublicationReceipt(original.releaseId, revision, original.requestSha256, false)
            }
        }
    }

    /** Schema-only; no head creation, content initialization, rights decision or row selection. */
    fun checkCompatibility(connection: Connection) = safe {
        requireTransaction(connection)
        val expected = RecipeCatalogStore::class.java.getResourceAsStream("/db/migration/V015__recipe_catalog.sql")
            ?.use { catalogSha(it.readBytes().decodeToString()) } ?: fail(RecipeCatalogFailureCode.NOT_CONFIGURED)
        connection.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=15").use {
            it.executeQuery().use { r -> if (!r.next() || r.getString(1) != expected || r.next()) fail(RecipeCatalogFailureCode.NOT_CONFIGURED) }
        }
        connection.createStatement().use { it.executeQuery("SELECT h.revision,h.release_id,r.predecessor_revision,r.request_sha256,r.content_sha256,r.taxonomy_revision,r.taxonomy_sha256,r.exact_document,r.published_at,e.recipe_version_id,e.entry,c.ingredient_id,c.component_ids FROM catalog.recipe_heads h JOIN catalog.recipe_releases r ON r.environment=h.environment JOIN catalog.recipe_release_entries e ON e.environment=r.environment JOIN catalog.recipe_release_compositions c ON c.environment=r.environment WHERE false").close() }
    }

    /** DB-only structural evidence. Caller has already locked actual identity/inputs/rights.
     * Holds the current head FOR SHARE through its final check; publishers take FOR UPDATE.
     * A document returned here is not an offline/public manifest or an access capability. */
    fun lockCurrent(connection: Connection): RecipeCatalogSnapshot = safe {
        checkCompatibility(connection)
        val head = head(connection, false) ?: fail(RecipeCatalogFailureCode.NOT_CONFIGURED)
        val id = head.second ?: fail(RecipeCatalogFailureCode.NOT_CONFIGURED)
        val result = release(connection, id) ?: fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
        if (head.first != result.revision) fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
        result
    }

    private fun events(c: Connection, previous: RecipeCatalogRelease?, next: RecipeCatalogRelease, revision: Long) {
        val old = previous?.entries?.associateBy { it.recipeVersionId }.orEmpty()
        for (entry in next.entries) {
            val before = old[entry.recipeVersionId]
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
            } else continue // No canonical retirement event exists; never relabel it as publication.
            outbox.append(c, EventDraft(UUID.randomUUID(), type, 1, "recipe_version", entry.recipeVersionId, entry.version,
                "catalog", next.releaseId.toString(), next.releaseId, body))
        }
    }

    private fun head(c: Connection, exclusive: Boolean): Pair<Long, UUID?>? = c.prepareStatement(
        "SELECT revision,release_id FROM catalog.recipe_heads WHERE environment=? FOR ${if (exclusive) "UPDATE" else "SHARE"}").use {
        it.setString(1, environment); it.executeQuery().use { r -> if (!r.next()) null else r.getLong(1) to r.getObject(2, UUID::class.java) }
    }
    private fun release(c: Connection, id: UUID): RecipeCatalogSnapshot? {
        val result = c.prepareStatement("SELECT revision,predecessor_revision,request_sha256,content_sha256,taxonomy_revision,taxonomy_sha256,exact_document FROM catalog.recipe_releases WHERE environment=? AND release_id=? FOR SHARE").use {
            it.owner(id); it.executeQuery().use { r ->
                if (!r.next()) null else {
                    val original = decodeRecipeRelease(r.getString(7))
                    if (original.releaseId != id || original.expectedRevision != r.getLong(2) || original.expectedRevision + 1 != r.getLong(1) ||
                        original.requestSha256 != r.getString(3) || original.contentSha256 != r.getString(4) ||
                        original.taxonomyRevision != r.getString(5) || original.taxonomySha256 != r.getString(6)) fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
                    RecipeCatalogSnapshot(r.getLong(1), original)
                }
            }
        } ?: return null
        val expected = result.original.entries.associateBy { it.recipeVersionId }
        c.prepareStatement("SELECT recipe_version_id,entry FROM catalog.recipe_release_entries WHERE environment=? AND release_id=? ORDER BY recipe_version_id LIMIT 129 FOR SHARE").use {
            it.owner(id); it.executeQuery().use { r ->
                var count = 0
                while (r.next()) {
                    val entry = expected[r.getObject(1, UUID::class.java)] ?: fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
                    if (recipeJsonIdentity(Json.parseToJsonElement(r.getString(2))) != recipeJsonIdentity(entry.document())) fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
                    count++
                }
                if (count != expected.size) fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
            }
        }
        val taxonomy = result.original.ingredients.associateBy { it.ingredientId }
        c.prepareStatement("SELECT ingredient_id,component_ids FROM catalog.recipe_release_compositions WHERE environment=? AND release_id=? ORDER BY ingredient_id LIMIT 1025 FOR SHARE").use {
            it.owner(id); it.executeQuery().use { r ->
                var count = 0
                while (r.next()) {
                    val ingredient = taxonomy[r.getObject(1, UUID::class.java)] ?: fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
                    if (Json.parseToJsonElement(r.getString(2)) != ingredient.document().getValue("componentIds")) fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
                    count++
                }
                if (count != taxonomy.size) fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE)
            }
        }
        return result
    }
    private fun PreparedStatement.owner(id: UUID) { setString(1, environment); setObject(2, id) }
    private fun requireTransaction(c: Connection) { require(!c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED) }
    private fun fail(code: RecipeCatalogFailureCode): Nothing = throw RecipeCatalogFailure(code)
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (f: RecipeCatalogFailure) { throw f }
        catch (f: CommitOutcomeUnknown) { throw f }
        catch (f: CancellationException) { throw f }
        catch (f: InterruptedException) { Thread.currentThread().interrupt(); throw f }
        catch (_: Exception) { fail(RecipeCatalogFailureCode.STORAGE_UNAVAILABLE) }
    override fun toString() = "RecipeCatalogStore(<redacted>)"
}
