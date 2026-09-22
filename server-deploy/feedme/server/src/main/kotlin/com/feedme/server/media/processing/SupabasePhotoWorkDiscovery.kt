package com.feedme.server.media.processing

import com.feedme.server.db.CommittedEvent
import com.feedme.server.db.EventDraft
import com.feedme.server.db.PgTransactions
import com.feedme.server.media.SUPABASE_MEDIA_PROTOCOL
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Read-only discovery of actual retained facts. It never claims/acks the general outbox relay,
 * fabricates upload events, inserts a second inbox, or treats scan position as work authority.
 * In-memory keyset rotation prevents one unavailable account from starving later candidates.
 * Process restart merely rescans; the existing processing inbox supplies durable deduplication. */
internal class SupabasePhotoWorkDiscovery(private val config: SupabasePhotoWorkerConfiguration,
    private val transactions: PgTransactions, private val bucket: String) {
    private var eventAfter: Position? = null
    private var heldAfter: Position? = null
    private class Position(val at: Instant, val id: UUID)

    fun events(): List<CommittedEvent> = processingSafe {
        val after = eventAfter
        val result = transactions.run { c ->
            val position = if (after == null) "" else " AND (e.occurred_at,e.event_id)>(?,?)"
            pq(c, "SELECT e.* FROM platform.outbox e JOIN platform.media_assets a ON a.environment=? AND a.id=e.aggregate_id " +
                "WHERE e.event_type='platform.media.upload_completed.v2' AND e.schema_version=2 AND e.producer='platform' " +
                "AND e.aggregate_type='media' AND a.storage_protocol=? AND a.storage_bucket=? " +
                "AND NOT EXISTS(SELECT 1 FROM platform.media_processing_inbox i WHERE i.event_id=e.event_id)" + position +
                " ORDER BY e.occurred_at,e.event_id LIMIT ?", {
                    setString(1, config.environment); setString(2, SUPABASE_MEDIA_PROTOCOL); setString(3, bucket)
                    var next = 4
                    if (after != null) { setObject(next++, pt(after.at)); setObject(next++, after.id) }
                    setInt(next, config.eventsPerBatch)
                }) { rows -> buildList {
                    while (rows.next()) add(CommittedEvent(EventDraft(
                        rows.getObject("event_id", UUID::class.java), rows.getString("event_type"), rows.getInt("schema_version"),
                        rows.getString("aggregate_type"), rows.getObject("aggregate_id", UUID::class.java), rows.getLong("aggregate_version"),
                        rows.getString("producer"), rows.getString("correlation_id"), rows.getObject("causation_id", UUID::class.java),
                        Json.parseToJsonElement(rows.getString("payload")).jsonObject), pi(rows, "occurred_at")))
                } }
        }
        eventAfter = result.lastOrNull()?.let { Position(it.occurredAt, it.draft.eventId) }
        result
    }

    fun heldJobs(): List<UUID> = processingSafe {
        val after = heldAfter
        val rows = transactions.run { c ->
            val position = if (after == null) "" else " AND (h.acknowledged_at,j.id)>(?,?)"
            pq(c, "SELECT j.id,h.acknowledged_at FROM platform.media_processing_jobs j " +
                "JOIN platform.media_private_materializations h ON h.job_id=j.id WHERE j.environment=? " +
                "AND j.policy_revision=? AND j.codec_revision=? AND j.source->>'protocol'=? AND j.source->>'bucket'=? " +
                "AND j.attempts<? AND j.available_at<=clock_timestamp() AND (j.lease_expires_at IS NULL OR j.lease_expires_at<=clock_timestamp()) " +
                "AND ((j.state='quarantined' AND j.last_failure_code IN('PRIVATE_DERIVATIVES_HELD','SUPABASE_READY_PROMOTION')) " +
                "OR (j.state='working' AND j.last_failure_code='SUPABASE_READY_PROMOTION'))" + position +
                " ORDER BY h.acknowledged_at,j.id LIMIT ?", {
                    setString(1, config.environment); setString(2, config.processing.revision); setString(3, config.processing.codecRevision)
                    setString(4, SUPABASE_MEDIA_PROTOCOL); setString(5, bucket); setInt(6, config.processing.maxAttempts)
                    var next = 7
                    if (after != null) { setObject(next++, pt(after.at)); setObject(next++, after.id) }
                    setInt(next, config.promotionsPerBatch)
                }) { result -> buildList {
                    while (result.next()) add(Position(pi(result, "acknowledged_at"), result.getObject("id", UUID::class.java)))
                } }
        }
        heldAfter = rows.lastOrNull()
        rows.map { it.id }
    }
}
