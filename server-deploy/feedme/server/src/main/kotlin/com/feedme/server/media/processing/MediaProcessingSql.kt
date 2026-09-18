package com.feedme.server.media.processing

import com.feedme.server.db.CommitOutcomeUnknown
import com.feedme.server.media.*
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal fun processingCurrent() { if (Thread.currentThread().isInterrupted) throw InterruptedException("Media processing interrupted") }
internal fun <T> processingSafe(action: () -> T): T = try { processingCurrent(); action().also { processingCurrent() } }
    catch (e: MediaProcessingFailure) { throw e } catch (e: CommitOutcomeUnknown) { throw e }
    catch (e: CancellationException) { throw e } catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
    catch (_: Exception) { processingFail(MediaProcessingFailureCode.STORAGE_UNAVAILABLE) }
internal fun <T> pq(c: Connection, sql: String, bind: PreparedStatement.() -> Unit = {}, read: (ResultSet) -> T): T {
    processingCurrent(); return c.prepareStatement(sql).use { it.bind(); it.executeQuery().use(read) }
}
internal fun px(c: Connection, sql: String, bind: PreparedStatement.() -> Unit = {}): Int {
    processingCurrent(); return c.prepareStatement(sql).use { it.bind(); it.executeUpdate() }
}
internal fun PreparedStatement.owner(o: MediaProcessingOwner, start: Int = 1) { setString(start, o.environment); setObject(start + 1, o.ownerId) }
internal fun pt(t: Instant): OffsetDateTime = OffsetDateTime.ofInstant(t, ZoneOffset.UTC)
internal fun pi(r: ResultSet, name: String): Instant = r.getObject(name, OffsetDateTime::class.java).toInstant()
internal fun pnow(c: Connection): Instant = pq(c, "SELECT clock_timestamp() AS now") { it.next(); pi(it, "now") }
internal fun ptext(s: String, max: Int) {
    if (s.isEmpty() || s.any { Character.isISOControl(it) } || s.encodeToByteArray(throwOnInvalidSequence = true).size > max)
        processingFail(MediaProcessingFailureCode.OBJECT_MISMATCH)
}
internal fun canonicalMedia(v: JsonElement): String = when (v) {
    is JsonObject -> v.toSortedMap().entries.joinToString(",", "{", "}") { (k, x) -> "${JsonPrimitive(k)}:${canonicalMedia(x)}" }
    is JsonArray -> v.joinToString(",", "[", "]") { canonicalMedia(it) }
    is JsonPrimitive -> v.toString()
}
internal fun MediaProcessingSource.document(): JsonObject = buildJsonObject {
    put("environment", owner.environment); put("owner", owner.ownerId.toString()); put("media", mediaId.toString())
    put("draft", draftId.toString()); put("draftGeneration", draftGeneration); put("completion", completionKey.toString())
    put("version", completionVersion); put("acceptedAt", acceptedAt.toString()); put("key", objectKey)
    if (storageProtocol == LEGACY_MEDIA_PROTOCOL) put("objectVersion", checkNotNull(objectVersionId))
    else { put("sourceFormat",2); put("protocol",storageProtocol); put("bucket",checkNotNull(bucket)) }
    put("sha256", sha256); put("bytes", bytes); put("type", contentType)
    put("deadline", reservationDeadline.toString())
}
internal fun processingSource(json: String): MediaProcessingSource {
    val j = Json.parseToJsonElement(json).jsonObject
    fun s(k: String) = j.getValue(k).jsonPrimitive.content
    val protocol = j["protocol"]?.jsonPrimitive?.content ?: LEGACY_MEDIA_PROTOCOL
    val bucket = j["bucket"]?.jsonPrimitive?.content
    val objectVersion = j["objectVersion"]?.jsonPrimitive?.content
    if ((protocol == LEGACY_MEDIA_PROTOCOL && (objectVersion.isNullOrEmpty() || bucket != null || "sourceFormat" in j)) ||
        (protocol == SUPABASE_MEDIA_PROTOCOL && (objectVersion != null || bucket == null ||
            !bucket.matches(Regex("[a-z0-9][a-z0-9_-]{0,62}")) || j["sourceFormat"] != JsonPrimitive(2))) ||
        protocol !in setOf(LEGACY_MEDIA_PROTOCOL,SUPABASE_MEDIA_PROTOCOL)) processingFail(MediaProcessingFailureCode.CONFLICT)
    val v = MediaProcessingSource(MediaProcessingOwner(s("environment"), UUID.fromString(s("owner"))), UUID.fromString(s("media")),
        UUID.fromString(s("draft")), s("draftGeneration").toLong(), UUID.fromString(s("completion")), s("version").toLong(),
        Instant.parse(s("acceptedAt")), s("key"), objectVersion, s("sha256"), s("bytes").toLong(), s("type"), Instant.parse(s("deadline")),protocol,bucket)
    if (v.document() != j || v.draftGeneration <= 0 || v.completionVersion != 2L || !v.sha256.matches(HASH) ||
        v.bytes !in 1..10_000_000 || v.contentType !in setOf("image/jpeg", "image/png", "image/heic") || v.acceptedAt >= v.reservationDeadline)
        processingFail(MediaProcessingFailureCode.CONFLICT)
    if (protocol == SUPABASE_MEDIA_PROTOCOL) try {
        SupabaseObjectRequest(v.owner.environment,v.owner.ownerId,v.mediaId,v.objectKey,v.bytes,v.contentType,v.sha256)
    } catch (_: IllegalArgumentException) { processingFail(MediaProcessingFailureCode.CONFLICT) }
    return v
}
internal fun processingSource(r: ResultSet): MediaProcessingSource = MediaProcessingSource(
    MediaProcessingOwner(r.getString("environment"), r.getObject("owner_user_id", UUID::class.java)), r.getObject("id", UUID::class.java),
    r.getObject("client_draft_id", UUID::class.java), r.getLong("draft_generation"), r.getObject("completion_key", UUID::class.java),
    r.getLong("completion_version"), pi(r, "completion_accepted_at"), r.getString("quarantine_key"), r.getString("quarantine_version_id"),
    r.getString("expected_sha256"), r.getLong("expected_bytes"), r.getString("content_type"), pi(r, "reservation_expires_at"),
    r.getString("storage_protocol"),r.getString("storage_bucket"))

/** Caller already holds actual principal/draft/media locks. Preserves V007's exact deletion receipt. */
internal object MediaProcessingDeletion {
    fun capture(c: Connection, environment: String, ownerId: UUID, mediaId: UUID, deletedVersion: Long) {
        val o = MediaProcessingOwner(environment, ownerId)
        pq(c, "SELECT id,state FROM platform.media_processing_jobs WHERE environment=? AND owner_user_id=? AND media_id=? FOR UPDATE",
            { owner(o); setObject(3, mediaId) }) { r ->
            if (r.next()) {
                val job = r.getObject("id", UUID::class.java)
                if (r.getString("state") !in setOf("ready", "rejected", "cancelled")) px(c,
                    "UPDATE platform.media_processing_jobs SET state='cancelled',terminal_token=lease_token,terminal_generation=lease_generation,terminal_media_version=?,terminal_at=clock_timestamp(),lease_token=NULL,lease_expires_at=NULL WHERE id=?",
                    { setLong(1, deletedVersion); setObject(2, job) })
                px(c, "UPDATE platform.media_derivative_intents SET cleanup_required=true WHERE job_id=?", { setObject(1, job) })
                pq(c, "SELECT id,object_key,acceptance_deadline FROM platform.media_derivative_intents WHERE job_id=? ORDER BY variant FOR UPDATE", { setObject(1, job) }) { rows ->
                    while (rows.next()) cleanupTarget(c, o, mediaId, rows.getString("object_key"), pi(rows, "acceptance_deadline"), rows.getObject("id", UUID::class.java))
                }
            }
        }
        pq(c, "SELECT quarantine_key,final_sweep_after,derivative_set FROM platform.media_cleanup_jobs WHERE environment=? AND owner_user_id=? AND media_id=? FOR UPDATE",
            { owner(o); setObject(3, mediaId) }) { r ->
            if (!r.next()) processingFail(MediaProcessingFailureCode.CONFLICT)
            cleanupTarget(c, o, mediaId, r.getString("quarantine_key"), pi(r, "final_sweep_after"), null)
            r.getString("derivative_set")?.let { json ->
                val variants = Json.parseToJsonElement(json).jsonObject.getValue("variants").jsonArray
                for (v in variants) {
                    val key = v.jsonObject.getValue("key").jsonPrimitive.content
                    val intent = pq(c, "SELECT id,acceptance_deadline FROM platform.media_derivative_intents WHERE object_key=?", { setString(1, key) }) {
                        if (it.next()) it.getObject("id", UUID::class.java) to pi(it, "acceptance_deadline") else null
                    }
                    cleanupTarget(c, o, mediaId, key, intent?.second ?: pnow(c), intent?.first)
                }
            }
        }
    }
}
internal fun cleanupTarget(c: Connection, o: MediaProcessingOwner, media: UUID, key: String, horizon: Instant, intent: UUID?) {
    val mode = pq(c,"SELECT storage_protocol,storage_bucket FROM platform.media_assets WHERE environment=? AND owner_user_id=? AND id=? FOR UPDATE",
        { owner(o);setObject(3,media) }) { if(!it.next()) processingFail(MediaProcessingFailureCode.CONFLICT);it.getString(1) to it.getString(2) }
    px(c, "INSERT INTO platform.media_processing_cleanup(id,environment,owner_user_id,media_id,object_key,derivative_intent_id,not_before,available_at,state,storage_protocol,storage_bucket) VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(object_key) DO NOTHING",
        { setObject(1, UUID.randomUUID()); owner(o, 2); setObject(4, media); setString(5, key); setObject(6, intent); setObject(7, pt(horizon)); setObject(8, pt(horizon))
          setString(9,if(mode.first==SUPABASE_MEDIA_PROTOCOL)"quarantined" else "pending");setString(10,mode.first);setString(11,mode.second) })
    pq(c, "SELECT environment,owner_user_id,media_id,derivative_intent_id,not_before,storage_protocol,storage_bucket FROM platform.media_processing_cleanup WHERE object_key=? FOR UPDATE", { setString(1, key) }) {
        if (!it.next()) processingFail(MediaProcessingFailureCode.CONFLICT)
        val actualIntent: UUID? = it.getObject("derivative_intent_id", UUID::class.java)
        if (it.getString("environment") != o.environment || it.getObject("owner_user_id", UUID::class.java) != o.ownerId ||
            it.getObject("media_id", UUID::class.java) != media || actualIntent != intent ||
            (intent != null && pi(it, "not_before") != horizon) || it.getString("storage_protocol")!=mode.first ||
            it.getString("storage_bucket")!=mode.second) processingFail(MediaProcessingFailureCode.CONFLICT)
    }
}
