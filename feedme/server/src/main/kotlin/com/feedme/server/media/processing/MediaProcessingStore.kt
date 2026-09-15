package com.feedme.server.media.processing

import com.feedme.server.db.*
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

/** Database-only processing protocol. Claims hold only a job lock; all media effects reacquire
 * principal -> draft -> media -> job -> intents. No provider call or default authority lives here. */
class MediaProcessingStore(val environment: String, private val transactions: PgTransactions,
    private val authority: MediaProcessingAuthority, val policy: MediaProcessingPolicy) {
    private val outbox = OutboxStore(transactions)
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    fun ingest(event: CommittedEvent): UUID = processingSafe {
        validateEvent(event)
        val fingerprint = processingHash(canonicalMedia(event.envelope()).encodeToByteArray())
        transactions.run { c ->
            val source = pq(c, "SELECT * FROM platform.media_assets WHERE environment=? AND id=?", { setString(1, environment); setObject(2, event.draft.aggregateId) }) {
                if (!it.next()) processingFail(MediaProcessingFailureCode.INVALID_EVENT)
                val s = processingSource(it); if (it.next()) processingFail(MediaProcessingFailureCode.INVALID_EVENT); s
            }
            val asset = lockAsset(c, source, MediaWorkerPurpose.CLEANUP)
            if (source.completionKey != event.draft.causationId || source.completionVersion != event.draft.aggregateVersion ||
                source.objectVersionId != event.draft.data.getValue("quarantineVersionId").jsonPrimitive.content ||
                source.sha256 != event.draft.data.getValue("checksum").jsonPrimitive.content) processingFail(MediaProcessingFailureCode.INVALID_EVENT)
            val original = pq(c, "SELECT event_sha256,job_id FROM platform.media_processing_inbox WHERE event_id=? FOR UPDATE", { setObject(1, event.draft.eventId) }) {
                if (it.next()) it.getString(1) to it.getObject(2, UUID::class.java) else null
            }
            if (original != null) {
                if (original.first != fingerprint) processingFail(MediaProcessingFailureCode.INVALID_EVENT)
                val job = job(c, original.second)
                sameSource(source, job.source)
                return@run job.id
            }
            // The internal event must be a real retained committed local outbox fact, not just a
            // caller-constructed DTO. A later exact duplicate uses its durable fingerprint above.
            pq(c, "SELECT * FROM platform.outbox WHERE event_id=?", { setObject(1, event.draft.eventId) }) {
                if (!it.next() || it.getString("event_type") != event.draft.eventType || it.getInt("schema_version") != 1 ||
                    it.getString("producer") != "platform" || it.getString("aggregate_type") != "media" ||
                    it.getObject("aggregate_id", UUID::class.java) != source.mediaId || it.getLong("aggregate_version") != source.completionVersion ||
                    it.getObject("causation_id", UUID::class.java) != source.completionKey || it.getString("correlation_id") != event.draft.correlationId ||
                    pi(it, "occurred_at") != event.occurredAt || Json.parseToJsonElement(it.getString("payload")) != event.draft.data)
                    processingFail(MediaProcessingFailureCode.INVALID_EVENT)
            }
            val existing = pq(c, "SELECT id FROM platform.media_processing_jobs WHERE environment=? AND owner_user_id=? AND media_id=? FOR UPDATE",
                { owner(source.owner); setObject(3, source.mediaId) }) { if (it.next()) it.getObject(1, UUID::class.java) else null }
            val id = existing ?: UUID.randomUUID().also { id ->
                if (asset.state !in setOf("processing", "deleted")) processingFail(MediaProcessingFailureCode.CONFLICT)
                val cancelled = asset.state == "deleted"
                if (!cancelled) { requireProcessing(c,asset,source) }
                px(c, "INSERT INTO platform.media_processing_jobs(id,environment,owner_user_id,media_id,source,policy_revision,codec_revision,state,available_at,created_at,terminal_media_version,terminal_at) VALUES(?,?,?,?,?::jsonb,?,?,?,clock_timestamp(),clock_timestamp(),?,?)",
                    { setObject(1, id); owner(source.owner, 2); setObject(4, source.mediaId); setString(5, source.document().toString()); setString(6, policy.revision); setString(7, policy.codecRevision)
                        setString(8, if (cancelled) "cancelled" else "queued"); setObject(9, if (cancelled) asset.version else null); setObject(10, if (cancelled) pt(pnow(c)) else null) })
            }
            sameSource(source, job(c, id).source)
            px(c, "INSERT INTO platform.media_processing_inbox(event_id,event_sha256,job_id,accepted_at) VALUES(?,?,?,clock_timestamp())",
                { setObject(1, event.draft.eventId); setString(2, fingerprint); setObject(3, id) })
            id
        }
    }

    /** One short, bounded claim. Attempt exhaustion is operational quarantine, not rejection. */
    fun claim(): MediaProcessingLease? = processingSafe { transactions.run { c ->
        px(c, "WITH exhausted AS (SELECT id FROM platform.media_processing_jobs WHERE environment=? AND state IN ('queued','retry','working') AND attempts>=? AND (lease_expires_at IS NULL OR lease_expires_at<=clock_timestamp()) LIMIT 100 FOR UPDATE SKIP LOCKED) UPDATE platform.media_processing_jobs j SET state='quarantined',lease_token=NULL,lease_expires_at=NULL,last_failure_code='ATTEMPTS_EXHAUSTED' FROM exhausted e WHERE j.id=e.id",
            { setString(1, environment); setInt(2, policy.maxAttempts) })
        val token = UUID.randomUUID()
        pq(c, "WITH candidate AS (SELECT id FROM platform.media_processing_jobs WHERE environment=? AND policy_revision=? AND codec_revision=? AND state IN ('queued','retry','working') AND attempts<? AND available_at<=clock_timestamp() AND (lease_expires_at IS NULL OR lease_expires_at<=clock_timestamp()) ORDER BY available_at,id LIMIT 1 FOR UPDATE SKIP LOCKED) UPDATE platform.media_processing_jobs j SET state='working',lease_token=?,lease_generation=lease_generation+1,lease_expires_at=clock_timestamp()+(? * interval '1 second'),attempts=attempts+1 FROM candidate x WHERE j.id=x.id RETURNING j.*",
            { setString(1, environment); setString(2, policy.revision); setString(3, policy.codecRevision); setInt(4, policy.maxAttempts); setObject(5, token); setInt(6, policy.leaseSeconds) }) {
            if (it.next()) MediaProcessingLease(it.getObject("id", UUID::class.java), token, it.getLong("lease_generation"), it.getInt("attempts"), processingSource(it.getString("source"))) else null
        }
    } }

    /** An original terminal receipt may survive a later deletion; it is not current access. */
    fun begin(lease: MediaProcessingLease): MediaProcessingReceipt? = processingSafe { transactions.run { c ->
        val asset = lockAsset(c, lease.source, MediaWorkerPurpose.CLEANUP); val job = job(c, lease.jobId)
        sameSource(lease.source, job.source)
        terminal(job, lease)?.let { return@run it }
        currentLease(c, job, lease); requireProcessing(c, asset, lease.source); currentLease(c, job, lease); null
    } }
    fun renew(lease: MediaProcessingLease) = processingSafe { transactions.run { c ->
        val asset = lockAsset(c, lease.source, MediaWorkerPurpose.PROCESS); val job = job(c, lease.jobId)
        currentLease(c, job, lease); requireProcessing(c, asset, lease.source)
        currentLease(c, job, lease)
        one(px(c, "UPDATE platform.media_processing_jobs SET lease_expires_at=clock_timestamp()+(? * interval '1 second') WHERE id=?",
            { setInt(1, policy.leaseSeconds); setObject(2, lease.jobId) }))
    } }

    fun prepareDerivatives(lease: MediaProcessingLease, variants: List<EncodedPhotoVariant>): List<MediaDerivativeIntent> = processingSafe {
        validateVariants(variants)
        transactions.run { c ->
            val asset = lockAsset(c, lease.source, MediaWorkerPurpose.PROCESS); val job = job(c, lease.jobId)
            currentLease(c, job, lease); requireProcessing(c, asset, lease.source)
            val prior = intents(c, lease.source, lease.jobId)
            currentLease(c, job, lease)
            if (prior.isNotEmpty()) {
                if (prior.size != 2 || variants.any { v -> prior.singleOrNull { it.variant == v.variant }?.let { sameOutput(it, v) } != true })
                    processingFail(MediaProcessingFailureCode.CONFLICT)
                return@run prior
            }
            val at = pnow(c)
            for (v in variants.sortedBy { it.variant.wire }) {
                currentLease(c, job, lease)
                val id = UUID.randomUUID(); val bytes = v.copyBytes()
                px(c, "INSERT INTO platform.media_derivative_intents(id,job_id,variant,object_key,sha256,bytes,content_type,width,height,acceptance_deadline,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    { setObject(1, id); setObject(2, lease.jobId); setString(3, v.variant.wire); setString(4, "derivatives/$environment/${lease.source.mediaId}/$id")
                        setString(5, processingHash(bytes)); setLong(6, bytes.size.toLong()); setString(7, v.contentType); setInt(8, v.width); setInt(9, v.height)
                        setObject(10, pt(at.plusSeconds(policy.writeAcceptanceSeconds.toLong()))); setObject(11, pt(at)) })
            }
            intents(c, lease.source, lease.jobId).also { currentLease(c, job, lease) }
        }
    }
    fun validateSafety(lease: MediaProcessingLease, variants: List<EncodedPhotoVariant>, proof: MediaSafetyEvidence) = processingSafe {
        validateVariants(variants)
        transactions.run { c ->
            val asset = lockAsset(c, lease.source, MediaWorkerPurpose.PROCESS); val job = job(c, lease.jobId)
            currentLease(c, job, lease); requireProcessing(c, asset, lease.source)
            val at = pnow(c)
            if (proof.sourceSha256 != lease.source.sha256 || proof.derivativeSha256 != variants.associate { it.variant to processingHash(it.copyBytes()) } ||
                proof.assessedAt > at || proof.validUntil <= at) processingFail(MediaProcessingFailureCode.SAFETY_UNAVAILABLE)
            authority.requireSafety(c, lease.source, proof, policy.revision, at); processingCurrent()
            if (proof.validUntil <= pnow(c)) processingFail(MediaProcessingFailureCode.SAFETY_UNAVAILABLE)
            currentLease(c, job, lease)
        }
    }
    /** Recheck immediately before an external write; deletion can still race afterwards, so the
     * durable intent and provider acceptance deadline, not this check, preserve cleanup ownership. */
    fun authorizeWrite(lease: MediaProcessingLease, intent: MediaDerivativeIntent) = processingSafe { transactions.run { c ->
        val asset = lockAsset(c, lease.source, MediaWorkerPurpose.PROCESS); val job = job(c, lease.jobId)
        currentLease(c, job, lease); requireProcessing(c, asset, lease.source)
        val stored = intents(c, lease.source, lease.jobId).singleOrNull { it.id == intent.id } ?: processingFail(MediaProcessingFailureCode.CONFLICT)
        sameIntent(stored, intent)
        currentLease(c, job, lease)
        if (pnow(c) >= stored.acceptanceDeadline) processingFail(MediaProcessingFailureCode.CONFLICT)
    } }
    /** Late immutable receipts remain recordable after lease expiry/deletion ONLY for cleanup.
     * This path never grants lease, READY, public access, or a replacement write. */
    fun acknowledge(intent: MediaDerivativeIntent, receipt: MediaDerivativeReceipt) = processingSafe { transactions.run { c ->
        lockAsset(c, intent.source, MediaWorkerPurpose.CLEANUP); val job = job(c, intent.jobId); sameSource(intent.source, job.source)
        val stored = intents(c, job.source, job.id).singleOrNull { it.id == intent.id } ?: processingFail(MediaProcessingFailureCode.CONFLICT)
        sameIntent(stored, intent); validateReceipt(stored, receipt)
        if (stored.objectVersionId != null && stored.objectVersionId != receipt.objectVersionId) processingFail(MediaProcessingFailureCode.OBJECT_MISMATCH)
        if (stored.objectVersionId == null) one(px(c, "UPDATE platform.media_derivative_intents SET object_version_id=?,acknowledged_at=clock_timestamp() WHERE id=?",
            { setString(1, receipt.objectVersionId); setObject(2, intent.id) }))
    } }

    fun ready(lease: MediaProcessingLease, evidence: MediaSafetyEvidence): MediaProcessingReceipt = finish(lease, null, evidence)
    fun reject(lease: MediaProcessingLease, reason: PhotoRejection): MediaProcessingReceipt = finish(lease, reason.name, null)
    fun reject(lease: MediaProcessingLease, reason: MediaSafetyRejection): MediaProcessingReceipt = finish(lease, reason.name, null)
    private fun finish(lease: MediaProcessingLease, rejection: String?, evidence: MediaSafetyEvidence?): MediaProcessingReceipt = processingSafe {
        transactions.run { c ->
            val asset = lockAsset(c, lease.source, MediaWorkerPurpose.CLEANUP); val job = job(c, lease.jobId)
            sameSource(lease.source, job.source); terminal(job, lease)?.let { return@run it }
            currentLease(c, job, lease); requireProcessing(c, asset, lease.source)
            val outputs = intents(c, lease.source, lease.jobId); val at = pnow(c)
            val manifest = if (rejection == null) {
                val proof = evidence ?: processingFail(MediaProcessingFailureCode.SAFETY_UNAVAILABLE)
                if (outputs.size != 2 || outputs.map { it.variant }.toSet() != PhotoVariant.entries.toSet() || outputs.any { it.objectVersionId == null } ||
                    proof.sourceSha256 != lease.source.sha256 || proof.derivativeSha256 != outputs.associate { it.variant to it.sha256 } ||
                    proof.assessedAt > at || proof.validUntil <= at) processingFail(MediaProcessingFailureCode.SAFETY_UNAVAILABLE)
                authority.requireSafety(c, lease.source, proof, policy.revision, at); processingCurrent()
                if (proof.validUntil <= pnow(c)) processingFail(MediaProcessingFailureCode.SAFETY_UNAVAILABLE)
                buildJsonObject { put("version", 1); put("variants", buildJsonArray { for (v in outputs) add(buildJsonObject {
                    put("variant", v.variant.wire); put("key", v.objectKey); put("objectVersionId", v.objectVersionId)
                }) }) }
            } else null
            if (manifest != null && manifest.toString().encodeToByteArray().size > policy.maxManifestBytes) processingFail(MediaProcessingFailureCode.LIMIT_EXCEEDED)
            // Fresh lease and time AFTER every authority callback/lock. Completion pins are untouched.
            currentLease(c, job, lease)
            val state = if (rejection == null) "ready" else "rejected"; val eventId = UUID.randomUUID()
            one(px(c, "UPDATE platform.media_assets SET state=?,version=version+1,updated_at=clock_timestamp(),derivative_set=?::jsonb,rejection_code=? WHERE environment=? AND owner_user_id=? AND id=? AND state='processing' AND version=?",
                { setString(1, state); setString(2, manifest?.toString()); setString(3, rejection); owner(lease.source.owner, 4); setObject(6, lease.source.mediaId); setLong(7, asset.version) }))
            one(px(c, "UPDATE platform.media_processing_jobs SET state=?,terminal_token=?,terminal_generation=?,terminal_media_version=?,terminal_event_id=?,terminal_at=clock_timestamp(),lease_token=NULL,lease_expires_at=NULL WHERE id=?",
                { setString(1, state); setObject(2, lease.token); setLong(3, lease.generation); setLong(4, asset.version + 1); setObject(5, eventId); setObject(6, job.id) }))
            outbox.append(c, EventDraft(eventId, "platform.media.$state.v1", 1, "media", lease.source.mediaId, asset.version + 1, "platform",
                job.id.toString(), lease.source.completionKey, buildJsonObject { put("mediaId", lease.source.mediaId.toString())
                    if (rejection == null) put("derivativeSetVersion", 1) else put("safeReasonCode", rejection) }))
            cleanupTarget(c, lease.source.owner, lease.source.mediaId, lease.source.objectKey, lease.source.reservationDeadline, null)
            if (rejection != null) for (v in outputs) {
                px(c, "UPDATE platform.media_derivative_intents SET cleanup_required=true WHERE id=?", { setObject(1, v.id) })
                cleanupTarget(c, lease.source.owner, lease.source.mediaId, v.objectKey, v.acceptanceDeadline, v.id)
            }
            MediaProcessingReceipt(job.id, if (rejection == null) MediaProcessingTerminal.READY else MediaProcessingTerminal.REJECTED, asset.version + 1, eventId)
        }
    }

    fun defer(lease: MediaProcessingLease, reason: MediaProcessingFailureCode) = processingSafe { transactions.run { c ->
        val job = job(c, lease.jobId); currentLease(c, job, lease)
        one(px(c, "UPDATE platform.media_processing_jobs SET state=?,last_failure_code=?,available_at=clock_timestamp()+(? * interval '1 second'),lease_token=NULL,lease_expires_at=NULL WHERE id=?",
            { setString(1, if (job.attempt >= policy.maxAttempts) "quarantined" else "retry"); setString(2, reason.name); setInt(3, policy.retrySeconds); setObject(4, job.id) }))
    } }

    internal fun lockAsset(c: Connection, source: MediaProcessingSource, purpose: MediaWorkerPurpose): Asset {
        if (source.owner.environment != environment) processingFail(MediaProcessingFailureCode.CONFLICT)
        authority.lockPrincipal(c, source.owner, purpose); processingCurrent()
        authority.lockDraft(c, source.owner, source.draftId, source.draftGeneration, purpose); processingCurrent()
        pq(c, "SELECT generation FROM platform.media_draft_lifecycles WHERE environment=? AND owner_user_id=? AND client_draft_id=? FOR UPDATE",
            { owner(source.owner); setObject(3, source.draftId) }) {
            if (!it.next() || it.getLong(1) != source.draftGeneration) processingFail(MediaProcessingFailureCode.CONFLICT)
        }
        return pq(c, "SELECT * FROM platform.media_assets WHERE environment=? AND owner_user_id=? AND id=? FOR UPDATE",
            { owner(source.owner); setObject(3, source.mediaId) }) {
            if (!it.next()) processingFail(MediaProcessingFailureCode.CONFLICT)
            sameSource(source, processingSource(it))
            Asset(it.getString("state"), it.getLong("version"), it.getString("derivative_set")?.let(Json::parseToJsonElement))
        }
    }
    private fun requireProcessing(c: Connection, asset: Asset, source: MediaProcessingSource) {
        if (asset.state != "processing" || asset.version != source.completionVersion || source.bytes > policy.maxSourceBytes) processingFail(MediaProcessingFailureCode.CONFLICT)
        authority.requireProcessing(c, source, policy.revision); processingCurrent()
        if(com.feedme.server.social.posts.PostPublicationLifecycle.isPublished(c,environment,source.owner.ownerId,source.draftId))processingFail(MediaProcessingFailureCode.CONFLICT)
    }
    private fun job(c: Connection, id: UUID): Job = pq(c, "SELECT * FROM platform.media_processing_jobs WHERE id=? FOR UPDATE", { setObject(1, id) }) {
        if (!it.next() || it.getString("environment") != environment || it.getString("policy_revision") != policy.revision || it.getString("codec_revision") != policy.codecRevision)
            processingFail(MediaProcessingFailureCode.CONFLICT)
        Job(id, processingSource(it.getString("source")), it.getString("state"), it.getObject("lease_token", UUID::class.java), it.getLong("lease_generation"),
            it.getInt("attempts"), it.getObject("lease_expires_at", java.time.OffsetDateTime::class.java)?.toInstant(),
            it.getObject("terminal_token", UUID::class.java), it.getLong("terminal_generation"), it.getLong("terminal_media_version"), it.getObject("terminal_event_id", UUID::class.java))
    }
    private fun currentLease(c: Connection, j: Job, l: MediaProcessingLease) {
        sameSource(j.source, l.source)
        if (j.id != l.jobId || j.state != "working" || j.token != l.token || j.generation != l.generation || j.attempt != l.attempt || j.expires == null || j.expires <= pnow(c))
            processingFail(MediaProcessingFailureCode.STALE_LEASE)
    }
    private fun terminal(j: Job, l: MediaProcessingLease): MediaProcessingReceipt? {
        if (j.state !in setOf("ready", "rejected", "cancelled")) return null
        if (j.terminalToken != l.token || j.terminalGeneration != l.generation || j.terminalVersion <= 0) processingFail(MediaProcessingFailureCode.STALE_LEASE)
        return MediaProcessingReceipt(j.id, MediaProcessingTerminal.valueOf(j.state.uppercase()), j.terminalVersion, j.terminalEvent)
    }
    private fun intents(c: Connection, source: MediaProcessingSource, id: UUID): List<MediaDerivativeIntent> = pq(c,
        "SELECT * FROM platform.media_derivative_intents WHERE job_id=? ORDER BY variant FOR UPDATE", { setObject(1, id) }) { r -> buildList {
        while (r.next()) add(MediaDerivativeIntent(r.getObject("id", UUID::class.java), id, source, PhotoVariant.entries.single { it.wire == r.getString("variant") },
            r.getString("object_key"), r.getString("sha256"), r.getLong("bytes"), r.getString("content_type"), r.getInt("width"), r.getInt("height"),
            pi(r, "acceptance_deadline"), r.getString("object_version_id")))
    } }
    /** Shape/byte admission occurs before any external safety assessment sees decoder output. */
    fun validateDecoded(v: List<EncodedPhotoVariant>) = processingSafe { validateVariants(v) }
    private fun validateVariants(v: List<EncodedPhotoVariant>) {
        if (v.size != 2 || v.map { it.variant }.toSet() != PhotoVariant.entries.toSet()) processingFail(MediaProcessingFailureCode.CONFLICT)
        var total = 0L
        for (x in v) { val size = x.byteCount; total += size
            if (size !in 1..policy.maxDerivativeBytes || x.width !in 1..policy.maxDimension || x.height !in 1..policy.maxDimension || x.contentType !in setOf("image/png", "image/jpeg")) processingFail(MediaProcessingFailureCode.LIMIT_EXCEEDED)
        }
        if (total > policy.maxCombinedDerivativeBytes) processingFail(MediaProcessingFailureCode.LIMIT_EXCEEDED)
    }
    private fun sameOutput(i: MediaDerivativeIntent, v: EncodedPhotoVariant): Boolean = i.variant == v.variant && i.contentType == v.contentType &&
        i.width == v.width && i.height == v.height && i.bytes == v.copyBytes().size.toLong() && i.sha256 == processingHash(v.copyBytes())
    private fun sameIntent(a: MediaDerivativeIntent, b: MediaDerivativeIntent) {
        sameSource(a.source, b.source)
        if (a.id != b.id || a.jobId != b.jobId || a.variant != b.variant || a.objectKey != b.objectKey || a.sha256 != b.sha256 || a.bytes != b.bytes ||
            a.contentType != b.contentType || a.width != b.width || a.height != b.height || a.acceptanceDeadline != b.acceptanceDeadline) processingFail(MediaProcessingFailureCode.CONFLICT)
    }
    private fun validateReceipt(i: MediaDerivativeIntent, r: MediaDerivativeReceipt) {
        ptext(r.objectVersionId, policy.maxObjectVersionBytes)
        if (r.objectKey != i.objectKey || r.sha256 != i.sha256 || r.bytes != i.bytes || r.contentType != i.contentType) processingFail(MediaProcessingFailureCode.OBJECT_MISMATCH)
    }
    private fun sameSource(a: MediaProcessingSource, b: MediaProcessingSource) { if (a.document() != b.document()) processingFail(MediaProcessingFailureCode.CONFLICT) }
    private fun validateEvent(e: CommittedEvent) {
        val d = e.draft
        if (d.eventType != "platform.media.upload_completed.v1" || d.schemaVersion != 1 || d.aggregateType != "media" || d.producer != "platform" || d.aggregateVersion != 2L ||
            d.data.keys != setOf("mediaId", "quarantineVersionId", "checksum") || d.data["mediaId"] != JsonPrimitive(d.aggregateId.toString()) ||
            d.data["checksum"]?.jsonPrimitive?.content?.matches(HASH) != true || d.data.values.any { it !is JsonPrimitive || !it.isString }) processingFail(MediaProcessingFailureCode.INVALID_EVENT)
        ptext(d.data.getValue("quarantineVersionId").jsonPrimitive.content, policy.maxObjectVersionBytes)
    }
    private fun one(n: Int) { if (n != 1) processingFail(MediaProcessingFailureCode.CONFLICT) }
    internal class Asset(val state: String, val version: Long, val derivatives: JsonElement?)
    private class Job(val id: UUID, val source: MediaProcessingSource, val state: String, val token: UUID?, val generation: Long,
        val attempt: Int, val expires: Instant?, val terminalToken: UUID?, val terminalGeneration: Long, val terminalVersion: Long, val terminalEvent: UUID?)
}
