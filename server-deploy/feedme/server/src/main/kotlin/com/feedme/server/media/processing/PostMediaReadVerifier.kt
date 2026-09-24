package com.feedme.server.media.processing

import com.feedme.server.media.LEGACY_MEDIA_PROTOCOL
import com.feedme.server.media.SUPABASE_MEDIA_PROTOCOL
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Exact stored processing material, NOT a safety, viewer, publication or object-access grant.
 * Only the supplied transaction's structural reader can construct it. The caller must still
 * require current MediaSafetyRecords and its own account/audience/content authority, keeping
 * all locks and the earliest safety deadline through its final response check. */
internal class VerifiedPostMediaMaterial internal constructor(
    val source: MediaProcessingSource,
    val jobId: UUID,
    val policyRevision: String,
    val codecRevision: String,
    outputs: List<MediaDerivativeIntent>,
    val mediaVersion: Long,
    val derivatives: JsonObject,
) {
    val outputs = outputs.toList()
    val stage: String get() = if (source.storageProtocol == SUPABASE_MEDIA_PROTOCOL) "privateHeld" else "ready"
    val safetyMediaVersion: Long get() = if (source.storageProtocol == SUPABASE_MEDIA_PROTOCOL) source.completionVersion else mediaVersion
    override fun toString() = "VerifiedPostMediaMaterial(<redacted>)"
}

/** Viewer-purpose, database-only structural verification. It deliberately does not impersonate
 * the media owner or require a former uploader's device/draft-edit eligibility. Lock order is
 * asset -> job -> ordered intents; NOWAIT prevents a post reader reversing publication locks.
 * No transaction is opened/committed here, and no provider or object store is contacted.
 * Supabase requires its separate V056 digest-readiness checkpoint and original private-held
 * safety record. An object path/digest is never interpreted as a provider version. */
internal class PostMediaReadVerifier(private val environment: String) {
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    fun verify(c: Connection, ownerId: UUID, mediaId: UUID, mediaVersion: Long,
        expectedDerivatives: JsonObject): VerifiedPostMediaMaterial = try {
        processingCurrent()
        if (c.isClosed || c.autoCommit || c.transactionIsolation != Connection.TRANSACTION_READ_COMMITTED || mediaVersion <= 0)
            conflict()
        // Refuse malformed caller material before touching database state. Protocol-specific
        // source binding is repeated below after the locked asset identifies the trusted bucket.
        expectedManifest(expectedDerivatives, mediaId)
        val sourceAndUpdated = read(c, "SELECT * FROM platform.media_assets WHERE environment=? " +
            "AND owner_user_id=? AND id=? FOR SHARE NOWAIT", {
            setString(1, environment); setObject(2, ownerId); setObject(3, mediaId)
        }) { r ->
            if (!r.next()) conflict()
            // Reuse the exact source codec, not only a row's READY label or its completion ID.
            val source = processingSource(processingSource(r).document().toString())
            val digestMode = source.storageProtocol == SUPABASE_MEDIA_PROTOCOL
            if ((!digestMode && (source.storageProtocol != LEGACY_MEDIA_PROTOCOL || source.bucket != null)) ||
                source.owner.environment != environment || source.owner.ownerId != ownerId || source.mediaId != mediaId ||
                r.getString("kind") != "photo" || r.getString("state") != "ready" ||
                r.getLong("version") != mediaVersion || mediaVersion != source.completionVersion + 1 ||
                r.getString("rejection_code") != null || r.getObject("deletion_key") != null ||
                r.getString("cleanup_manifest_hash") != null) conflict()
            ptext(source.objectKey, 200)
            if (digestMode) SupabaseMediaReadiness.validateManifest(expectedDerivatives, environment, mediaId, source.bucket)
            else { manifest(expectedDerivatives); ptext(checkNotNull(source.objectVersionId), 4096) }
            val stored = r.getString("derivative_set")?.let { boundedObject(it, 65536) } ?: conflict()
            if (digestMode) SupabaseMediaReadiness.validateManifest(stored, environment, mediaId, source.bucket) else manifest(stored)
            if (stored != expectedDerivatives) conflict()
            val created = pi(r, "created_at"); val updated = pi(r, "updated_at")
            if (created > source.acceptedAt || source.acceptedAt > updated || r.next()) conflict()
            source to updated
        }
        val source = sourceAndUpdated.first
        val job = read(c, "SELECT * FROM platform.media_processing_jobs WHERE environment=? " +
            "AND owner_user_id=? AND media_id=? FOR SHARE NOWAIT", {
            setString(1, environment); setObject(2, ownerId); setObject(3, mediaId)
        }) { r ->
            if (!r.next()) conflict()
            val retained = boundedObject(r.getString("source"), 16384)
            if (processingSource(retained.toString()).document() != source.document() ||
                r.getString("environment") != environment || r.getObject("owner_user_id", UUID::class.java) != ownerId ||
                r.getObject("media_id", UUID::class.java) != mediaId || r.getString("state") != "ready" ||
                r.getLong("terminal_media_version") != mediaVersion || r.getObject("terminal_token") == null ||
                r.getLong("terminal_generation") <= 0 || r.getLong("terminal_generation") != r.getLong("lease_generation") ||
                r.getInt("attempts") <= 0 || r.getObject("terminal_event_id") == null ||
                r.getObject("lease_token") != null || r.getObject("lease_expires_at") != null) conflict()
            val policy = r.getString("policy_revision"); val codec = r.getString("codec_revision")
            if (!policy.matches(REVISION) || !codec.matches(REVISION)) conflict()
            val created = pi(r, "created_at"); val terminal = pi(r, "terminal_at")
            if (created < source.acceptedAt || terminal < created || terminal < sourceAndUpdated.second) conflict()
            Job(r.getObject("id", UUID::class.java), policy, codec, created, terminal).also { if (r.next()) conflict() }
        }
        val outputs = read(c, "SELECT * FROM platform.media_derivative_intents WHERE job_id=? " +
            "ORDER BY variant LIMIT 3 FOR SHARE NOWAIT", { setObject(1, job.id) }) { r -> buildList {
            while (r.next()) {
                if (size >= 2 || r.getObject("job_id", UUID::class.java) != job.id || r.getBoolean("cleanup_required")) conflict()
                val variant = PhotoVariant.entries.singleOrNull { it.wire == r.getString("variant") } ?: conflict()
                val id = r.getObject("id", UUID::class.java)
                val key = r.getString("object_key"); val version = r.getString("object_version_id")
                val hash = r.getString("sha256"); val bytes = r.getLong("bytes")
                val type = r.getString("content_type"); val width = r.getInt("width"); val height = r.getInt("height")
                val created = pi(r, "created_at"); val deadline = pi(r, "acceptance_deadline")
                val acknowledged = r.getObject("acknowledged_at", OffsetDateTime::class.java)?.toInstant() ?: conflict()
                val attempted = r.getObject("write_attempted_at", OffsetDateTime::class.java)?.toInstant()
                if (r.getString("storage_protocol") != source.storageProtocol || r.getString("storage_bucket") != source.bucket ||
                    key != "derivatives/$environment/$mediaId/$id" ||
                    !hash.matches(HASH) || bytes !in 1..10_000_000 || type !in setOf("image/png", "image/jpeg") ||
                    width !in 1..100_000 || height !in 1..100_000 || created < job.createdAt ||
                    deadline <= created || acknowledged < created || acknowledged > job.terminalAt) conflict()
                if (source.storageProtocol == SUPABASE_MEDIA_PROTOCOL) {
                    if (version != null || attempted == null || attempted < created || attempted >= deadline || acknowledged < attempted) conflict()
                } else {
                    if (attempted != null || version == null) conflict()
                    ptext(version, 4096)
                }
                add(MediaDerivativeIntent(id, job.id, source, variant, key, hash, bytes, type, width, height,
                    deadline, version, source.storageProtocol, source.bucket, attempted, acknowledged))
            }
        } }
        if (outputs.size != 2 || outputs.map { it.variant }.toSet() != PhotoVariant.entries.toSet()) conflict()
        val actual = if (source.storageProtocol == SUPABASE_MEDIA_PROTOCOL) SupabaseMediaReadiness.manifest(source, outputs) else buildJsonObject {
            put("version", 1)
            put("variants", JsonArray(outputs.map { buildJsonObject {
                put("variant", it.variant.wire); put("key", it.objectKey); put("objectVersionId", it.objectVersionId)
            } }))
        }
        if (actual != expectedDerivatives) conflict()
        if (source.storageProtocol == SUPABASE_MEDIA_PROTOCOL) SupabaseMediaReadiness.verify(c, source, job.id, mediaVersion, actual)
        processingCurrent()
        VerifiedPostMediaMaterial(source, job.id, job.policyRevision, job.codecRevision, outputs, mediaVersion, actual)
    } catch (failure: SQLException) {
        if (failure.sqlState != "55P03") throw failure
        // Keep cleanup failures visible so PgTransactions never retries an uncertain cleanup.
        throw SQLException("Post media read dependency contended", "40001").also { retry ->
            failure.suppressed.forEach(retry::addSuppressed)
        }
    } catch (failure: MediaProcessingFailure) { throw failure
    } catch (failure: CancellationException) { throw failure
    } catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure
    } catch (failure: Exception) {
        throw MediaProcessingFailure(MediaProcessingFailureCode.STORAGE_UNAVAILABLE).also { unavailable ->
            failure.suppressed.forEach(unavailable::addSuppressed)
        }
    }

    private fun expectedManifest(value: JsonObject, mediaId: UUID) {
        if (value.toString().encodeToByteArray(throwOnInvalidSequence = true).size > 65536) conflict()
        when ((value["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull) {
            1 -> manifest(value)
            2 -> {
                val bucket = (value["bucket"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: conflict()
                SupabaseMediaReadiness.validateManifest(value, environment, mediaId, bucket)
            }
            else -> conflict()
        }
    }

    private fun manifest(value: JsonObject) {
        if (value.toString().encodeToByteArray(throwOnInvalidSequence = true).size > 65536 ||
            value.keys != setOf("version", "variants") || value["version"] != JsonPrimitive(1)) conflict()
        val variants = value["variants"] as? JsonArray ?: conflict()
        if (variants.size != 2) conflict()
        val names = mutableSetOf<String>()
        for (entry in variants) {
            val item = entry as? JsonObject ?: conflict()
            if (item.keys != setOf("variant", "key", "objectVersionId")) conflict()
            fun text(name: String): String = (item[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: conflict()
            if (text("variant") !in setOf("display", "thumbnail") || !names.add(text("variant"))) conflict()
            ptext(text("key"), 200); ptext(text("objectVersionId"), 4096)
        }
    }
    private fun boundedObject(value: String, bytes: Int): JsonObject {
        if (value.encodeToByteArray(throwOnInvalidSequence = true).size > bytes) conflict()
        return Json.parseToJsonElement(value).jsonObject
    }
    private fun <T> read(c: Connection, sql: String, bind: PreparedStatement.() -> Unit,
        result: (ResultSet) -> T): T {
        processingCurrent()
        return c.prepareStatement(sql).use { statement -> statement.bind(); statement.executeQuery().use(result) }
    }
    private class Job(val id: UUID, val policyRevision: String, val codecRevision: String,
        val createdAt: Instant, val terminalAt: Instant)
    private fun conflict(): Nothing = processingFail(MediaProcessingFailureCode.CONFLICT)
}
