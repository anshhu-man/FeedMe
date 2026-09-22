package com.feedme.server.media.processing

import com.feedme.server.db.PgTransactions
import com.feedme.server.media.LEGACY_MEDIA_PROTOCOL
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Purpose-fixed exact-key cleanup. No owner/bucket sweep, guessed absence or V007 receipt edit.
 * Claims become eligible only after every issued POST/backend acceptance deadline. The mandatory
 * provider must prove settled exact-key inventory; timeout is never an absence acknowledgement. */
class MediaObjectCleanup(private val environment: String, private val transactions: PgTransactions,
    private val authority: MediaProcessingAuthority, private val objects: MediaProcessingObjects,
    private val policy: MediaProcessingPolicy) {
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }
    fun claim(): MediaCleanupLease? = processingSafe { transactions.run { c ->
        px(c, "WITH exhausted AS (SELECT id FROM platform.media_processing_cleanup WHERE environment=? AND state IN ('pending','working') AND attempts>=? AND (lease_expires_at IS NULL OR lease_expires_at<=clock_timestamp()) LIMIT 100 FOR UPDATE SKIP LOCKED) UPDATE platform.media_processing_cleanup t SET state='quarantined',lease_token=NULL,lease_expires_at=NULL FROM exhausted e WHERE t.id=e.id",
            { setString(1, environment); setInt(2, policy.maxAttempts) })
        val token = UUID.randomUUID()
        pq(c, "WITH candidate AS (SELECT id FROM platform.media_processing_cleanup WHERE environment=? AND state IN ('pending','working') AND not_before<=clock_timestamp() AND available_at<=clock_timestamp() AND attempts<? AND (lease_expires_at IS NULL OR lease_expires_at<=clock_timestamp()) ORDER BY available_at,id LIMIT 1 FOR UPDATE SKIP LOCKED) UPDATE platform.media_processing_cleanup t SET state='working',lease_token=?,lease_generation=lease_generation+1,lease_expires_at=clock_timestamp()+(? * interval '1 second'),attempts=attempts+1 FROM candidate x WHERE t.id=x.id RETURNING t.*",
            { setString(1, environment); setInt(2, policy.maxAttempts); setObject(3, token); setInt(4, policy.leaseSeconds) }) {
            if (it.next()) MediaCleanupLease(it.getObject("id", UUID::class.java), token, it.getLong("lease_generation"),
                MediaProcessingOwner(it.getString("environment"), it.getObject("owner_user_id", UUID::class.java)), it.getObject("media_id", UUID::class.java),
                it.getString("object_key"), pi(it, "not_before"), it.getObject("derivative_intent_id", UUID::class.java),
                it.getString("storage_protocol"),it.getString("storage_bucket")) else null
        }
    } }
    fun clean(lease: MediaCleanupLease) {
        if(lease.storageProtocol!=LEGACY_MEDIA_PROTOCOL || lease.bucket!=null) processingFail(MediaProcessingFailureCode.NOT_CONFIGURED)
        var versions = processingSafe { transactions.run { c -> lock(c, lease) } }
        if (versions == null) {
            val settled = external { objects.settle(lease) }
            if (settled.objectKey != lease.objectKey || settled.versionIds.size > policy.maxVersionsPerKey || settled.versionIds.toSet().size != settled.versionIds.size)
                processingFail(MediaProcessingFailureCode.OBJECT_MISMATCH)
            settled.versionIds.forEach { ptext(it, policy.maxObjectVersionBytes) }
            val exact = settled.versionIds.sorted()
            if (JsonArray(exact.map(::JsonPrimitive)).toString().encodeToByteArray().size > 1_048_576) processingFail(MediaProcessingFailureCode.LIMIT_EXCEEDED)
            versions = processingSafe { transactions.run { c ->
                val previous = lock(c, lease)
                if (previous != null && previous != exact) processingFail(MediaProcessingFailureCode.CONFLICT)
                if (previous == null) px(c, "UPDATE platform.media_processing_cleanup SET settled_versions=?::jsonb WHERE id=?",
                    { setString(1, JsonArray(exact.map(::JsonPrimitive)).toString()); setObject(2, lease.id) })
                exact
            } }
        }
        for (version in checkNotNull(versions)) {
            // Re-lock just before each exact deletion; never delete a currently live manifest key.
            processingSafe { transactions.run { c -> lock(c, lease) } }
            external { objects.deleteVersion(lease, version) }
        }
        processingSafe { transactions.run { c ->
            if (lock(c, lease) != versions) processingFail(MediaProcessingFailureCode.CONFLICT)
            if (px(c, "UPDATE platform.media_processing_cleanup SET state='done',completed_at=clock_timestamp(),lease_token=NULL,lease_expires_at=NULL WHERE id=?",
                    { setObject(1, lease.id) }) != 1) processingFail(MediaProcessingFailureCode.CONFLICT)
        } }
    }
    /** Keep provider exception text/causes private. Failure is never a settled-inventory or
     * deletion acknowledgement; cancellation retains the original durable target and lease. */
    private fun <T> external(block: () -> T): T = try {
        processingCurrent(); block().also { processingCurrent() }
    } catch (e: CancellationException) { throw e }
    catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
    catch (e: MediaProcessingFailure) { throw e }
    catch (_: Exception) { processingFail(MediaProcessingFailureCode.OBJECT_UNAVAILABLE) }

    private fun lock(c: Connection, lease: MediaCleanupLease): List<String>? {
        if (lease.owner.environment != environment) processingFail(MediaProcessingFailureCode.CONFLICT)
        authority.lockPrincipal(c, lease.owner, MediaWorkerPurpose.CLEANUP); processingCurrent()
        val draft = pq(c, "SELECT client_draft_id,draft_generation FROM platform.media_assets WHERE environment=? AND owner_user_id=? AND id=?",
            { owner(lease.owner); setObject(3, lease.mediaId) }) {
            if (!it.next()) processingFail(MediaProcessingFailureCode.CONFLICT)
            it.getObject(1, UUID::class.java) to it.getLong(2)
        }
        authority.lockDraft(c, lease.owner, draft.first, draft.second, MediaWorkerPurpose.CLEANUP); processingCurrent()
        pq(c, "SELECT generation FROM platform.media_draft_lifecycles WHERE environment=? AND owner_user_id=? AND client_draft_id=? FOR UPDATE",
            { owner(lease.owner); setObject(3, draft.first) }) {
            // Cancelling/replacing a draft advances its lifecycle, but the retained exact
            // old object still needs cleanup. This never admits stale PROCESS work.
            if (!it.next() || draft.second <= 0 || it.getLong(1) < draft.second)
                processingFail(MediaProcessingFailureCode.CONFLICT)
        }
        pq(c, "SELECT state,quarantine_key,derivative_set,client_draft_id,draft_generation FROM platform.media_assets WHERE environment=? AND owner_user_id=? AND id=? FOR UPDATE",
            { owner(lease.owner); setObject(3, lease.mediaId) }) {
            if (!it.next() || it.getObject("client_draft_id", UUID::class.java) != draft.first ||
                it.getLong("draft_generation") != draft.second) processingFail(MediaProcessingFailureCode.CONFLICT)
            val state = it.getString("state"); val quarantine = it.getString("quarantine_key")
            val liveKeys = it.getString("derivative_set")?.let { raw -> Json.parseToJsonElement(raw).jsonObject.getValue("variants").jsonArray.map { v -> v.jsonObject.getValue("key").jsonPrimitive.content } }.orEmpty()
            if (lease.objectKey in liveKeys || (lease.objectKey == quarantine && state !in setOf("ready", "rejected", "deleted"))) processingFail(MediaProcessingFailureCode.CONFLICT)
            if (lease.objectKey != quarantine && !lease.objectKey.startsWith("derivatives/$environment/${lease.mediaId}/")) processingFail(MediaProcessingFailureCode.CONFLICT)
        }
        return pq(c, "SELECT * FROM platform.media_processing_cleanup WHERE id=? FOR UPDATE", { setObject(1, lease.id) }) {
            if (!it.next() || it.getString("environment") != environment || it.getObject("owner_user_id", UUID::class.java) != lease.owner.ownerId ||
                it.getObject("media_id", UUID::class.java) != lease.mediaId || it.getString("object_key") != lease.objectKey || pi(it, "not_before") != lease.notBefore ||
                it.getObject("derivative_intent_id", UUID::class.java) != lease.derivativeIntentId || it.getString("state") != "working" ||
                it.getString("storage_protocol")!=LEGACY_MEDIA_PROTOCOL || it.getString("storage_bucket")!=null ||
                it.getObject("lease_token", UUID::class.java) != lease.token || it.getLong("lease_generation") != lease.generation ||
                pi(it, "lease_expires_at") <= pnow(c) || lease.notBefore > pnow(c)) processingFail(MediaProcessingFailureCode.STALE_LEASE)
            it.getString("settled_versions")?.let { raw -> Json.parseToJsonElement(raw).jsonArray.map { v -> v.jsonPrimitive.content } }
        }
    }
}
