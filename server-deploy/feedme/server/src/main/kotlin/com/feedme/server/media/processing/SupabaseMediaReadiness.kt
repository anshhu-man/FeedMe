package com.feedme.server.media.processing

import com.feedme.server.media.SUPABASE_MEDIA_PROTOCOL
import java.sql.Connection
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

/** Explicit fresh read phase over already acknowledged private outputs. No write capability. */
class SupabaseReadyInspection internal constructor(internal val lease: MediaProcessingLease,
    outputs: List<MediaDerivativeIntent>, internal val startedAt: Instant) {
    val outputs = outputs.toList()
    override fun toString() = "SupabaseReadyInspection(<redacted>)"
}

/** Digest readiness is application content binding, never an immutable provider version.
 * The manifest stays server-private. Delivery must independently check the actual complete
 * bytes against this manifest and current viewer/safety authority before disclosing bytes. */
internal object SupabaseMediaReadiness {
    /** Nonmutating local-package/guard/actual-role probe. No grant is installed here. */
    fun checkCompatibility(c: Connection) {
        check(!c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
        c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=56").use { s ->
            s.executeQuery().use { check(it.next() && it.getString(1) == checksum && !it.next()) }
        }
        c.createStatement().use { s ->
            s.executeQuery("SELECT * FROM platform.media_digest_readiness WHERE false FOR SHARE NOWAIT").use { check(!it.next()) }
            s.executeQuery("SELECT c.relkind='r' AND c.relrowsecurity AND c.relforcerowsecurity " +
                "AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid) " +
                "AND NOT EXISTS(SELECT 1 FROM pg_catalog.aclexplode(coalesce(c.relacl,pg_catalog.acldefault('r',c.relowner))) a WHERE a.grantee=0) " +
                "FROM pg_catalog.pg_class c WHERE c.oid='platform.media_digest_readiness'::pg_catalog.regclass").use { check(it.next() && it.getBoolean(1) && !it.next()) }
        }
        for ((name, tag) in guardTags) c.prepareStatement("SELECT p.prosrc,p.prosecdef,p.prokind,p.prorettype='pg_catalog.trigger'::pg_catalog.regtype,p.proconfig," +
            "NOT EXISTS(SELECT 1 FROM pg_catalog.aclexplode(coalesce(p.proacl,pg_catalog.acldefault('f',p.proowner))) a WHERE a.grantee<>p.proowner) " +
            "FROM pg_catalog.pg_proc p WHERE p.oid=pg_catalog.to_regprocedure(?)").use { s ->
            s.setString(1, "platform.$name()"); s.executeQuery().use { r ->
                check(r.next() && r.getString(1) == migration.substringAfter("\$$tag\$").substringBefore("\$$tag\$") && r.getBoolean(2) && r.getString(3) == "f" && r.getBoolean(4))
                check((r.getArray(5).array as Array<*>).map { it.toString().replace(" ", "") }.toSet() == setOf("search_path=pg_catalog,pg_temp", "row_security=off") && r.getBoolean(6) && !r.next())
            }
        }
        for ((table, name, type, function, deferred) in attachments) c.prepareStatement("SELECT tgtype,tgenabled,tgisinternal,tgfoid=pg_catalog.to_regprocedure(?),tgqual IS NULL,tgnargs=0,tgdeferrable,tginitdeferred " +
            "FROM pg_catalog.pg_trigger WHERE tgrelid=pg_catalog.to_regclass(?) AND tgname=?").use { s ->
            s.setString(1, "platform.$function()"); s.setString(2, "platform.$table"); s.setString(3, name)
            s.executeQuery().use { r -> check(r.next() && r.getInt(1) == type && r.getString(2) == "O" && !r.getBoolean(3) && (4..6).all(r::getBoolean) && r.getBoolean(7) == deferred && r.getBoolean(8) == deferred && !r.next()) }
        }
    }
    fun manifest(source: MediaProcessingSource, outputs: List<MediaDerivativeIntent>): JsonObject {
        if (source.storageProtocol != SUPABASE_MEDIA_PROTOCOL || source.bucket == null || source.objectVersionId != null ||
            outputs.size != 2 || outputs.map { it.variant }.toSet() != PhotoVariant.entries.toSet()) conflict()
        val result = buildJsonObject {
            put("version", 2); put("protocol", SUPABASE_MEDIA_PROTOCOL); put("bucket", source.bucket)
            put("variants", JsonArray(outputs.sortedBy { it.variant.wire }.map { output ->
                if (output.source.document() != source.document() || output.storageProtocol != source.storageProtocol ||
                    output.bucket != source.bucket || output.objectVersionId != null || output.writeAttemptedAt == null ||
                    output.acknowledgedAt == null || output.writeAttemptedAt >= output.acceptanceDeadline ||
                    output.acknowledgedAt < output.writeAttemptedAt ||
                    output.objectKey != "derivatives/${source.owner.environment}/${source.mediaId}/${output.id}") conflict()
                buildJsonObject {
                    put("variant", output.variant.wire); put("key", output.objectKey); put("sha256", output.sha256)
                    put("bytes", output.bytes); put("contentType", output.contentType); put("width", output.width); put("height", output.height)
                }
            }))
        }
        validateManifest(result, source.owner.environment, source.mediaId, source.bucket)
        return result
    }
    fun validateManifest(value: JsonObject, environment: String, mediaId: UUID, bucket: String?) {
        if (bucket == null || !bucket.matches(Regex("[a-z0-9][a-z0-9_-]{0,62}")) ||
            value.keys != setOf("version", "protocol", "bucket", "variants") || value["version"] != JsonPrimitive(2) ||
            value["protocol"] != JsonPrimitive(SUPABASE_MEDIA_PROTOCOL) || value["bucket"] != JsonPrimitive(bucket) ||
            value.toString().encodeToByteArray().size > 65536) conflict()
        val rows = value["variants"] as? JsonArray ?: conflict()
        if (rows.size != 2) conflict()
        val names = mutableSetOf<String>()
        for (entry in rows) {
            val row = entry as? JsonObject ?: conflict()
            if (row.keys != setOf("variant", "key", "sha256", "bytes", "contentType", "width", "height")) conflict()
            fun text(name: String) = (row[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: conflict()
            fun number(name: String) = (row[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: conflict()
            if (text("variant") !in setOf("thumbnail", "display") || !names.add(text("variant")) ||
                !text("key").matches(Regex("derivatives/${Regex.escape(environment)}/$mediaId/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) ||
                !text("sha256").matches(HASH) || number("bytes") !in 1..10_000_000 ||
                text("contentType") != "image/png" || number("width") !in 1..65535 || number("height") !in 1..65535) conflict()
        }
    }
    fun record(c: Connection, lease: MediaProcessingLease, outputs: List<MediaDerivativeIntent>, safety: MediaSafetyCurrent,
        observedAt: Instant, mediaVersion: Long, eventId: UUID) {
        val value = manifest(lease.source, outputs); val text = value.toString()
        if (observedAt > pnow(c) || mediaVersion != lease.source.completionVersion + 1) conflict()
        if (px(c, "INSERT INTO platform.media_digest_readiness(job_id,environment,owner_user_id,media_id,media_version,lease_token,lease_generation," +
            "safety_record_sha256,manifest_text,manifest_sha256,inspection_started_at,event_id,ready_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,clock_timestamp())", {
            setObject(1, lease.jobId); owner(lease.source.owner, 2); setObject(4, lease.source.mediaId); setLong(5, mediaVersion)
            setObject(6, lease.token); setLong(7, lease.generation); setString(8, safety.recordSha256); setString(9, text)
            setString(10, processingHash(text.encodeToByteArray())); setObject(11, pt(observedAt)); setObject(12, eventId)
        }) != 1) conflict()
    }
    fun verify(c: Connection, source: MediaProcessingSource, jobId: UUID, mediaVersion: Long, expected: JsonObject) {
        validateManifest(expected, source.owner.environment, source.mediaId, source.bucket)
        pq(c, "SELECT d.*,s.record_sha256 AS actual_safety,j.terminal_token,j.terminal_generation,j.terminal_event_id,j.terminal_at," +
            "h.acknowledged_at AS held_at FROM platform.media_digest_readiness d " +
            "JOIN platform.media_safety_records s ON s.job_id=d.job_id JOIN platform.media_processing_jobs j ON j.id=d.job_id " +
            "JOIN platform.media_private_materializations h ON h.job_id=d.job_id WHERE d.job_id=? FOR SHARE OF d NOWAIT", { setObject(1, jobId) }) { r ->
            if (!r.next()) conflict()
            val text = r.getString("manifest_text")
            if (r.getString("environment") != source.owner.environment || r.getObject("owner_user_id", UUID::class.java) != source.owner.ownerId ||
                r.getObject("media_id", UUID::class.java) != source.mediaId || r.getLong("media_version") != mediaVersion ||
                processingHash(text.encodeToByteArray()) != r.getString("manifest_sha256") || Json.parseToJsonElement(text) != expected ||
                r.getString("safety_record_sha256") != r.getString("actual_safety") ||
                r.getObject("lease_token") != r.getObject("terminal_token") || r.getLong("lease_generation") != r.getLong("terminal_generation") ||
                r.getObject("event_id") != r.getObject("terminal_event_id") ||
                pi(r, "inspection_started_at") < pi(r, "held_at") || pi(r, "inspection_started_at") > pi(r, "ready_at") ||
                pi(r, "ready_at") > pi(r, "terminal_at") || r.next()) conflict()
        }
    }
    private fun conflict(): Nothing = processingFail(MediaProcessingFailureCode.CONFLICT)
    private val migration by lazy { checkNotNull(SupabaseMediaReadiness::class.java.getResourceAsStream("/db/migration/V056__supabase_digest_readiness.sql")).use { it.readBytes().decodeToString() } }
    private val checksum by lazy { MessageDigest.getInstance("SHA-256").digest(migration.encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) } }
    private val guardTags = mapOf("guard_media_digest_readiness" to "feedme_digest_ready", "require_media_digest_ready_checkpoint" to "feedme_digest_checkpoint", "guard_supabase_ready_asset" to "feedme_digest_asset")
    private data class Attachment(val table: String, val name: String, val type: Int, val function: String, val deferred: Boolean = false)
    private val attachments = listOf(
        Attachment("media_digest_readiness", "media_digest_ready_original", 31, "guard_media_digest_readiness"),
        Attachment("media_digest_readiness", "media_digest_ready_retained", 34, "guard_media_digest_readiness"),
        Attachment("media_digest_readiness", "media_digest_ready_checkpoint", 5, "require_media_digest_ready_checkpoint", true),
        Attachment("media_assets", "media_supabase_ready", 23, "guard_supabase_ready_asset"),
    )
}
