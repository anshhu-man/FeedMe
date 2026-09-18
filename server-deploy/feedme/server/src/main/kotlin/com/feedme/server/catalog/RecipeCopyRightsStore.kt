package com.feedme.server.catalog

import com.feedme.server.db.CommitOutcomeUnknown
import com.feedme.server.db.PgTransactions
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Exact source-based positive rights registry. Publication/revocation have mandatory separate
 * authority; readers do not authenticate a private principal. The real private transaction owner
 * must call its returned handle again after final authorization and before committing any copy. */
class RecipeCopyRightsStore(val environment: String, private val transactions: PgTransactions,
    private val journal: RecipeCatalogJournal, private val authority: RecipeCopyRightsPublicationAuthority) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(journal.environment == environment)
    }
    internal fun isBoundTo(expectedEnvironment: String, expectedTransactions: PgTransactions,
        expectedJournal: RecipeCatalogJournal): Boolean = environment == expectedEnvironment &&
        transactions === expectedTransactions && journal === expectedJournal

    fun publish(original: RecipeCopyGrant): RecipeCopyRightsReceipt = copySafe {
        transactions.run { c ->
            checkCompatibility(c)
            val guard = Guard(c)
            authority.lockPublication(c, environment, original); guard.check(c)
            val view = journal.openView(c)
            lockOriginal(c, original.grantId)
            val before = readGrant(c, original.grantId, exclusive = true)
            if (before != null && before.original.exactDocument != original.exactDocument)
                copyFail(RecipeCopyRightsFailureCode.ORIGINAL_MISMATCH)
            val source = originalSource(view, original)
            currentSource(view, original, guest = original.allowGuest, fresh = before == null)
            if (original.allowReviewedScaling && !reviewedScaling(source.entry)) copyFail(RecipeCopyRightsFailureCode.NOT_ALLOWED)
            if (before == null) {
                if (now(c) >= original.newCopiesUntil) copyFail(RecipeCopyRightsFailureCode.EXPIRED)
                c.prepareStatement("INSERT INTO catalog.recipe_copy_grants(environment,grant_id,recipe_version_id," +
                    "source_release_id,source_revision,source_request_sha256,source_sha256,request_sha256,exact_document," +
                    "content_license,allow_guest,allow_reviewed_scaling,not_before,new_copies_until,retained_copies_until) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use {
                    it.owner(original.grantId); it.setObject(3, original.recipeVersionId); it.setObject(4, original.sourceReleaseId)
                    it.setLong(5, original.sourceRevision); it.setString(6, original.sourceRequestSha256); it.setString(7, original.sourceSha256)
                    it.setString(8, original.requestSha256); it.setString(9, original.exactDocument); it.setString(10, original.contentLicense)
                    it.setBoolean(11, original.allowGuest); it.setBoolean(12, original.allowReviewedScaling)
                    it.instant(13, original.notBefore); it.instant(14, original.newCopiesUntil); it.instant(15, original.retainedCopiesUntil)
                    check(it.executeUpdate() == 1)
                }
            }
            val retained = readGrant(c, original.grantId) ?: copyFail(RecipeCopyRightsFailureCode.STORAGE_UNAVAILABLE)
            if (retained.original.exactDocument != original.exactDocument || (before != null && before != retained))
                copyFail(RecipeCopyRightsFailureCode.STORAGE_UNAVAILABLE)
            authority.revalidatePublication(c, environment, original); guard.check(c)
            view.checkCurrent(); currentSource(view, original, guest = original.allowGuest, fresh = before == null)
            if (readGrant(c, original.grantId) != retained) copyFail(RecipeCopyRightsFailureCode.STORAGE_UNAVAILABLE)
            guard.check(c)
            if (before == null && now(c) >= original.newCopiesUntil) copyFail(RecipeCopyRightsFailureCode.EXPIRED)
            localTransaction(c)
            RecipeCopyRightsReceipt(original.grantId, original.grantId, original.requestSha256, before != null)
        }
    }

    fun revoke(original: RecipeCopyRevocation): RecipeCopyRightsReceipt = copySafe {
        transactions.run { c -> revoke(c, original) }
    }

    /** Same mandatory administrative authority; no nested transaction or accepting bypass.
     * Also lets an already-authorized same-transaction revocation invalidate a pending copy. */
    internal fun revoke(c: Connection, original: RecipeCopyRevocation): RecipeCopyRightsReceipt = copySafe {
        checkCompatibility(c)
        val guard = Guard(c)
        authority.lockRevocation(c, environment, original); guard.check(c)
        lockOriginal(c, original.grantId)
        val grant = readGrant(c, original.grantId, exclusive = true) ?: copyFail(RecipeCopyRightsFailureCode.NOT_ALLOWED)
        val before = readRevocation(c, original.grantId)
        if (before != null && before.original.exactDocument != original.exactDocument)
            copyFail(RecipeCopyRightsFailureCode.ORIGINAL_MISMATCH)
        if (before == null) c.prepareStatement("INSERT INTO catalog.recipe_copy_revocations(environment,grant_id,revocation_id,request_sha256,exact_document) VALUES(?,?,?,?,?)").use {
            it.owner(original.grantId); it.setObject(3, original.revocationId); it.setString(4, original.requestSha256)
            it.setString(5, original.exactDocument); check(it.executeUpdate() == 1)
        }
        val retained = readRevocation(c, original.grantId) ?: copyFail(RecipeCopyRightsFailureCode.STORAGE_UNAVAILABLE)
        if (retained.original.exactDocument != original.exactDocument || (before != null && before != retained))
            copyFail(RecipeCopyRightsFailureCode.STORAGE_UNAVAILABLE)
        authority.revalidateRevocation(c, environment, original); guard.check(c)
        if (readGrant(c, original.grantId) != grant || readRevocation(c, original.grantId) != retained)
            copyFail(RecipeCopyRightsFailureCode.STORAGE_UNAVAILABLE)
        guard.check(c)
        RecipeCopyRightsReceipt(original.grantId, original.revocationId, original.requestSha256, before != null)
    }

    /** Reads only; no transaction, default grant, Plan lookup or principal authentication. */
    fun openNew(c: Connection, recipeVersionId: UUID, guest: Boolean): RecipeCopyRightsHandle = copySafe {
        checkCompatibility(c)
        val guard = Guard(c)
        val view = journal.openView(c)
        val current = view.lookupCurrent(recipeVersionId) ?: copyFail(RecipeCopyRightsFailureCode.NOT_ALLOWED)
        requireStatus(current.entry, guest, fresh = true)
        val id = c.prepareStatement("SELECT g.grant_id FROM catalog.recipe_copy_grants g " +
            "WHERE g.environment=? AND g.recipe_version_id=? AND g.source_sha256=? AND (?=false OR g.allow_guest) " +
            "AND g.not_before<=clock_timestamp() AND g.new_copies_until>clock_timestamp() " +
            "AND NOT EXISTS(SELECT 1 FROM catalog.recipe_copy_revocations r WHERE r.environment=g.environment AND r.grant_id=g.grant_id) " +
            "ORDER BY g.created_at DESC,g.grant_id LIMIT 1").use {
            it.setString(1, environment); it.setObject(2, recipeVersionId); it.setString(3, recipeCopySourceSha256(current.entry)); it.setBoolean(4, guest)
            it.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        } ?: copyFail(RecipeCopyRightsFailureCode.NOT_ALLOWED)
        bind(c, guard, view, id, guest, fresh = true, expectedEvidence = null)
    }

    /** Exact retained grant, never selection of a replacement grant. Ordinary retirement is
     * allowed, but expiry, revocation, recall, guest ineligibility or material drift deny access. */
    fun openExisting(c: Connection, evidence: JsonObject, guest: Boolean): RecipeCopyRightsHandle = copySafe {
        checkCompatibility(c)
        val guard = Guard(c)
        val encoded = evidence.toString().encodeToByteArray(throwOnInvalidSequence = true)
        if (encoded.size > COPY_DOCUMENT_BYTES) copyFail(RecipeCopyRightsFailureCode.NOT_ALLOWED)
        val id = try { evidence.getValue("grantId").jsonPrimitive.let {
            require(it.isString); UUID.fromString(it.content).also { id -> require(id.toString() == it.content) }
        } } catch (_: Exception) { copyFail(RecipeCopyRightsFailureCode.NOT_ALLOWED) }
        bind(c, guard, journal.openView(c), id, guest, fresh = false, expectedEvidence = evidence)
    }

    private fun bind(c: Connection, guard: Guard, view: RecipeCatalogReadView, id: UUID,
        guest: Boolean, fresh: Boolean, expectedEvidence: JsonObject?): RecipeCopyRightsHandle {
        // The append-only revocation row cannot itself lock an absent predecessor. Share the
        // SAME grant advisory lock as both administrative writers, before locking its row.
        lockOriginal(c, id, shared = true)
        val row = readGrant(c, id) ?: copyFail(RecipeCopyRightsFailureCode.NOT_ALLOWED)
        val actualEvidence = row.original.evidence(environment)
        if (expectedEvidence != null && recipeJsonIdentity(expectedEvidence) != recipeJsonIdentity(actualEvidence))
            copyFail(RecipeCopyRightsFailureCode.NOT_ALLOWED)
        val source = originalSource(view, row.original)
        val binding = Binding(c, guard, view, row, source, guest, fresh)
        val result = RecipeCopyRightsHandle(this, binding)
        revalidate(c, result)
        return result
    }

    internal fun revalidate(c: Connection, handle: RecipeCopyRightsHandle): Unit = copySafe {
        val b = handle.binding
        if (handle.owner !== this || c !== b.connection || !b.active.compareAndSet(false, true))
            copyFail(RecipeCopyRightsFailureCode.STORAGE_UNAVAILABLE)
        try {
            if (b.failed) copyFail(RecipeCopyRightsFailureCode.STORAGE_UNAVAILABLE)
            b.guard.check(c); b.view.checkCurrent()
            val row = readGrant(c, b.row.original.grantId) ?: copyFail(RecipeCopyRightsFailureCode.NOT_ALLOWED)
            if (row != b.row) copyFail(RecipeCopyRightsFailureCode.STORAGE_UNAVAILABLE)
            if (readRevocation(c, row.original.grantId) != null) copyFail(RecipeCopyRightsFailureCode.NOT_ALLOWED)
            val original = originalSource(b.view, row.original)
            if (original.releaseId != b.source.releaseId || original.revision != b.source.revision ||
                original.requestSha256 != b.source.requestSha256 || original.entry.document() != b.source.entry.document())
                copyFail(RecipeCopyRightsFailureCode.STORAGE_UNAVAILABLE)
            currentSource(b.view, row.original, b.guest, b.fresh)
            if (b.guest && !row.original.allowGuest) copyFail(RecipeCopyRightsFailureCode.NOT_ALLOWED)
            if (row.original.allowReviewedScaling && !reviewedScaling(original.entry)) copyFail(RecipeCopyRightsFailureCode.NOT_ALLOWED)
            b.view.checkCurrent(); b.guard.check(c)
            if (b.observationRevision == Long.MAX_VALUE) copyFail(RecipeCopyRightsFailureCode.STORAGE_UNAVAILABLE)
            val at = now(c)
            if (at < row.original.notBefore || at >= if (b.fresh) row.original.newCopiesUntil else row.original.retainedCopiesUntil)
                copyFail(RecipeCopyRightsFailureCode.EXPIRED)
            if (b.lastAcceptedAt?.let { at < it } == true) copyFail(RecipeCopyRightsFailureCode.STORAGE_UNAVAILABLE)
            localTransaction(c)
            b.lastAcceptedAt = at
            b.observationRevision++
        } catch (failure: Throwable) { b.failed = true; throw failure }
        finally { b.active.set(false) }
    }

    /** Rejection-only final clock fence. The private owner supplies its actual final accepted
     * database time AFTER its remaining awaits. Does no SQL and cannot replace revalidate,
     * authenticate a user, change a deadline, or establish any new source/rights permission. */
    internal fun checkAt(c: Connection, handle: RecipeCopyRightsHandle, at: Instant): Unit = copySafe {
        val b = handle.binding
        val observed = b.lastAcceptedAt
        val revision = b.observationRevision
        if (handle.owner !== this || observed == null || b.failed || b.active.get())
            copyFail(RecipeCopyRightsFailureCode.STORAGE_UNAVAILABLE)
        b.guard.checkLocal(c)
        if (at < observed) copyFail(RecipeCopyRightsFailureCode.STORAGE_UNAVAILABLE)
        val original = b.row.original
        if (at < original.notBefore || at >= if (b.fresh) original.newCopiesUntil else original.retainedCopiesUntil)
            copyFail(RecipeCopyRightsFailureCode.EXPIRED)
        // Connection property callbacks must not retire or replace the checked observation.
        if (b.failed || b.active.get() || b.observationRevision != revision || b.lastAcceptedAt != observed)
            copyFail(RecipeCopyRightsFailureCode.STORAGE_UNAVAILABLE)
    }

    fun checkCompatibility(c: Connection): Unit = copySafe {
        transaction(c)
        val expected = RecipeCopyRightsStore::class.java.getResourceAsStream("/db/migration/V021__recipe_copy_rights.sql")
            ?.use { catalogSha(it.readBytes().decodeToString()) } ?: copyFail(RecipeCopyRightsFailureCode.NOT_CONFIGURED)
        c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=21").use {
            it.executeQuery().use { rows -> if (!rows.next() || rows.getString(1) != expected || rows.next())
                copyFail(RecipeCopyRightsFailureCode.NOT_CONFIGURED) }
        }
    }

    private fun originalSource(view: RecipeCatalogReadView, original: RecipeCopyGrant): RecipeCatalogVersion {
        if (original.sourceRevision > view.revision) copyFail(RecipeCopyRightsFailureCode.NOT_ALLOWED)
        val source = view.lookupAt(original.recipeVersionId, original.sourceRevision) ?: copyFail(RecipeCopyRightsFailureCode.NOT_ALLOWED)
        if (source.releaseId != original.sourceReleaseId || source.revision != original.sourceRevision ||
            source.requestSha256 != original.sourceRequestSha256 || recipeCopySourceSha256(source.entry) != original.sourceSha256 ||
            source.entry.status != "published") copyFail(RecipeCopyRightsFailureCode.NOT_ALLOWED)
        return source
    }
    private fun currentSource(view: RecipeCatalogReadView, original: RecipeCopyGrant, guest: Boolean, fresh: Boolean) {
        val current = view.lookupCurrent(original.recipeVersionId) ?: copyFail(RecipeCopyRightsFailureCode.NOT_ALLOWED)
        requireStatus(current.entry, guest, fresh)
        if (recipeCopySourceSha256(current.entry) != original.sourceSha256) copyFail(RecipeCopyRightsFailureCode.NOT_ALLOWED)
    }
    private fun requireStatus(entry: RecipeCatalogEntry, guest: Boolean, fresh: Boolean) {
        if (entry.status == "recalled" || entry.recall != null) copyFail(RecipeCopyRightsFailureCode.RECALLED)
        if ((fresh && entry.status != "published") || (!fresh && entry.status !in setOf("published", "retired")) ||
            (guest && entry.review["freeCatalogEligible"] != JsonPrimitive(true))) copyFail(RecipeCopyRightsFailureCode.NOT_ALLOWED)
    }
    private fun reviewedScaling(entry: RecipeCatalogEntry): Boolean = listOf("linearQuantityScalingReviewed",
        "stepsValidForScalingRange", "effortValidForScalingRange").all { entry.review[it] == JsonPrimitive(true) }

    private fun readGrant(c: Connection, id: UUID, exclusive: Boolean = false): GrantRow? = c.prepareStatement(
        "SELECT * FROM catalog.recipe_copy_grants WHERE environment=? AND grant_id=? FOR ${if (exclusive) "UPDATE" else "SHARE"}").use {
        it.owner(id); it.executeQuery().use { r -> if (!r.next()) null else {
            val original = RecipeCopyGrant.decode(r.getString("exact_document"))
            if (r.getString("environment") != environment || r.getObject("grant_id", UUID::class.java) != id || original.grantId != id ||
                r.getObject("recipe_version_id", UUID::class.java) != original.recipeVersionId ||
                r.getObject("source_release_id", UUID::class.java) != original.sourceReleaseId || r.getLong("source_revision") != original.sourceRevision ||
                r.getString("source_request_sha256") != original.sourceRequestSha256 || r.getString("source_sha256") != original.sourceSha256 ||
                r.getString("request_sha256") != original.requestSha256 || r.getString("content_license") != original.contentLicense ||
                r.getBoolean("allow_guest") != original.allowGuest || r.getBoolean("allow_reviewed_scaling") != original.allowReviewedScaling ||
                r.getObject("not_before", OffsetDateTime::class.java).toInstant() != original.notBefore ||
                r.getObject("new_copies_until", OffsetDateTime::class.java).toInstant() != original.newCopiesUntil ||
                r.getObject("retained_copies_until", OffsetDateTime::class.java).toInstant() != original.retainedCopiesUntil)
                copyFail(RecipeCopyRightsFailureCode.STORAGE_UNAVAILABLE)
            val result = GrantRow(original, r.getObject("created_at", OffsetDateTime::class.java).toInstant())
            if (r.next()) copyFail(RecipeCopyRightsFailureCode.STORAGE_UNAVAILABLE)
            result
        } }
    }
    private fun readRevocation(c: Connection, id: UUID): RevocationRow? = c.prepareStatement(
        "SELECT * FROM catalog.recipe_copy_revocations WHERE environment=? AND grant_id=? FOR SHARE").use {
        it.owner(id); it.executeQuery().use { r -> if (!r.next()) null else {
            val original = RecipeCopyRevocation.decode(r.getString("exact_document"))
            if (r.getString("environment") != environment || r.getObject("grant_id", UUID::class.java) != id || original.grantId != id ||
                r.getObject("revocation_id", UUID::class.java) != original.revocationId || r.getString("request_sha256") != original.requestSha256)
                copyFail(RecipeCopyRightsFailureCode.STORAGE_UNAVAILABLE)
            val result = RevocationRow(original, r.getObject("revoked_at", OffsetDateTime::class.java).toInstant())
            if (r.next()) copyFail(RecipeCopyRightsFailureCode.STORAGE_UNAVAILABLE)
            result
        } }
    }
    private fun lockOriginal(c: Connection, id: UUID, shared: Boolean = false) = c.prepareStatement(
        "SELECT pg_advisory_xact_lock${if (shared) "_shared" else ""}(hashtextextended(?,0))").use {
        it.setString(1, "recipe-copy:$environment:$id"); it.execute()
    }
    private fun PreparedStatement.owner(id: UUID) { setString(1, environment); setObject(2, id) }
    private fun PreparedStatement.instant(index: Int, value: Instant) = setObject(index, value.atOffset(java.time.ZoneOffset.UTC))
    internal class Binding(val connection: Connection, val guard: Guard, val view: RecipeCatalogReadView,
        val row: GrantRow, val source: RecipeCatalogVersion, val guest: Boolean, val fresh: Boolean) {
        val active = AtomicBoolean(false)
        @Volatile var failed = false
        @Volatile var lastAcceptedAt: Instant? = null
        @Volatile var observationRevision: Long = 0
    }
    internal class Guard(private val connection: Connection) {
        private val thread = Thread.currentThread()
        private val id: Long
        init { transaction(connection); id = transactionId(connection) }
        fun check(c: Connection) {
            checkLocal(c)
            // Pgjdbc executes SHOW when reading isolation. This belongs ONLY in the
            // queryful pre-clock guard, never the final local delivery/deadline fence.
            if (c.transactionIsolation != Connection.TRANSACTION_READ_COMMITTED)
                copyFail(RecipeCopyRightsFailureCode.STORAGE_UNAVAILABLE)
            if (transactionId(c) != id) copyFail(RecipeCopyRightsFailureCode.STORAGE_UNAVAILABLE)
        }
        fun checkLocal(c: Connection) {
            if (c !== connection || Thread.currentThread() !== thread) copyFail(RecipeCopyRightsFailureCode.STORAGE_UNAVAILABLE)
            localTransaction(c)
        }
    }
    internal class GrantRow(val original: RecipeCopyGrant, val createdAt: Instant) {
        override fun equals(other: Any?): Boolean = other is GrantRow && original.exactDocument == other.original.exactDocument && createdAt == other.createdAt
        override fun hashCode() = 31 * original.requestSha256.hashCode() + createdAt.hashCode()
    }
    private class RevocationRow(val original: RecipeCopyRevocation, val revokedAt: Instant) {
        override fun equals(other: Any?): Boolean = other is RevocationRow && original.exactDocument == other.original.exactDocument && revokedAt == other.revokedAt
        override fun hashCode() = 31 * original.requestSha256.hashCode() + revokedAt.hashCode()
    }
    override fun toString() = "RecipeCopyRightsStore(<redacted>)"
}

/** Per-attempt locked evidence, not a bearer token or permission for arbitrary recipe bytes.
 * source is the grant's actual ORIGINAL published recipe, even after ordinary retirement.
 * Callers must separately prove any materialized servings change against reviewed scaling. */
class RecipeCopyRightsHandle internal constructor(internal val owner: RecipeCopyRightsStore,
    internal val binding: RecipeCopyRightsStore.Binding) {
    val grantId: UUID get() = binding.row.original.grantId
    val recipeVersionId: UUID get() = binding.row.original.recipeVersionId
    val contentLicense: String get() = binding.row.original.contentLicense
    val allowReviewedScaling: Boolean get() = binding.row.original.allowReviewedScaling
    val evidence: JsonObject get() = binding.row.original.evidence(owner.environment)
    val source: RecipeCatalogVersion get() = binding.source
    fun revalidate(connection: Connection) = owner.revalidate(connection, this)
    fun checkAt(connection: Connection, acceptedAt: Instant) = owner.checkAt(connection, this, acceptedAt)
    override fun toString() = "RecipeCopyRightsHandle(<redacted>)"
}

private fun transaction(c: Connection) {
    localTransaction(c)
    if (c.transactionIsolation != Connection.TRANSACTION_READ_COMMITTED)
        copyFail(RecipeCopyRightsFailureCode.STORAGE_UNAVAILABLE)
}
private fun localTransaction(c: Connection) {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("Recipe copy interrupted")
    if (c.isClosed || c.autoCommit)
        copyFail(RecipeCopyRightsFailureCode.STORAGE_UNAVAILABLE)
}
private fun transactionId(c: Connection): Long = c.createStatement().use {
    it.executeQuery("SELECT txid_current()").use { r -> check(r.next()); r.getLong(1) }
}
private fun now(c: Connection): Instant = c.createStatement().use {
    it.executeQuery("SELECT clock_timestamp()").use { r -> check(r.next()); r.getObject(1, OffsetDateTime::class.java).toInstant() }
}
private fun copyFail(code: RecipeCopyRightsFailureCode): Nothing = throw RecipeCopyRightsFailure(code)
private fun <T> copySafe(action: () -> T): T = try { action() }
    catch (failure: RecipeCopyRightsFailure) { throw failure }
    catch (failure: RecipeCatalogFailure) { copyFail(if (failure.code == RecipeCatalogFailureCode.NOT_CONFIGURED)
        RecipeCopyRightsFailureCode.NOT_CONFIGURED else RecipeCopyRightsFailureCode.STORAGE_UNAVAILABLE) }
    catch (failure: CommitOutcomeUnknown) { throw failure }
    catch (failure: CancellationException) { throw failure }
    catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
    catch (failure: SQLException) { if (failure.sqlState in setOf("40001", "40P01")) throw failure
        else copyFail(RecipeCopyRightsFailureCode.STORAGE_UNAVAILABLE) }
    catch (_: Exception) { copyFail(RecipeCopyRightsFailureCode.STORAGE_UNAVAILABLE) }
