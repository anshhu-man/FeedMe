package com.feedme.server.media

import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.media.processing.MediaProcessingDeletion
import java.nio.charset.CharacterCodingException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/**
 * Four owner-only photo operations. Completion means exact source content was verified and
 * processing queued, NEVER image safety/readiness/publication. No PostDraft is implicitly created.
 * No worker/provider/moderator or public access implementation is supplied by this component.
 * Core receipts are committed without bearer fields. Same-key prepare may freshly render a bounded
 * legacy POST only while that exact original reservation remains awaitingUpload/current/unexpired.
 * Explicit Supabase mode instead records one issuance before remote minting; replay never remints.
 * An expired, unchanged Supabase reservation can replay only its core, within the original command
 * receipt lifetime and with all current upload/draft authority still required. This grants no PUT.
 * Completion replays its original processing acknowledgement after a ready/rejected transition;
 * it never substitutes current status, grants access or re-enqueues work. GET observes current status.
 */
class MediaStore(val environment: String, private val transactions: PgTransactions,
    private val authority: MediaAuthority, private val capabilities: MediaUploadCapabilities,
    private val objects: MediaObjectVerifier, val policy: MediaServicePolicy,
    private val supabase: SupabaseMediaStorage? = null) {
    private val commands = DurableCommands(transactions)
    private val outbox = OutboxStore(transactions)
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    fun prepareMediaUpload(actor: VerifiedMediaAccount, key: UUID, body: JsonObject): CommandResult {
        val input = request("prepareMediaUpload", body)
        val protocol = input["protocol"]?.jsonPrimitive?.content ?: LEGACY_MEDIA_PROTOCOL
        if (protocol !in setOf(LEGACY_MEDIA_PROTOCOL, SUPABASE_MEDIA_PROTOCOL)) fail(MediaFailureCode.INPUT_INVALID)
        if (protocol !in policy.allowedProtocols) fail(MediaFailureCode.NOT_CONFIGURED)
        val bucket = if (protocol == SUPABASE_MEDIA_PROTOCOL) configuredSupabase().bucket else null
        if (input.text("kind") != "photo") fail(MediaFailureCode.NOT_CONFIGURED)
        val type = input.text("contentType")
        if (type !in policy.supportedContentTypes) fail(MediaFailureCode.UNSUPPORTED_FORMAT)
        if (protocol == SUPABASE_MEDIA_PROTOCOL && type !in setOf("image/jpeg", "image/png")) fail(MediaFailureCode.UNSUPPORTED_FORMAT)
        val declaredSize = input.getValue("bytes").jsonPrimitive.content.toBigDecimal()
        if (declaredSize > policy.maxSourceBytes.toBigDecimal()) fail(MediaFailureCode.MEDIA_TOO_LARGE)
        val size = declaredSize.longValueExact()
        if (input["durationSeconds"]?.jsonPrimitive?.content?.toBigDecimal()?.signum()?.let { it != 0 } == true) fail(MediaFailureCode.INPUT_INVALID)
        val draft = UUID.fromString(input.text("clientDraftId")); val checksum = input.text("sha256").lowercase()
        val result = command(actor, "prepareMediaUpload", key, body = input, replay = { c, cached ->
            val row = locked(c, actor, replyId(cached), true)
            authority.requireUploadEnabled(c, actor, false); current()
            if (protocol == SUPABASE_MEDIA_PROTOCOL) {
                if (row.storageProtocol != protocol || row.bucket != bucket) fail(MediaFailureCode.STORAGE_UNAVAILABLE)
                configuredSupabase(row.bucket)
            }
            awaiting(c, row, exactSupabaseReplay = protocol == SUPABASE_MEDIA_PROTOCOL)
            exact(cached, core(row, "prepareMediaUpload", 201))
        }) { c ->
            authority.requireUploadEnabled(c, actor, true); current()
            val generation = authority.lockDraftLifecycle(c, actor, draft, true); current(); positive(generation)
            val at = now(c); val existing = lifecycle(c, actor, draft)
            requireUnpublished(c,actor,draft)
            if (existing == null) exec(c, "INSERT INTO platform.media_draft_lifecycles VALUES(?,?,?,?,?)") {
                owner(actor); setObject(3, draft); setLong(4, generation); setObject(5, time(at))
            } else if (existing != generation) fail(MediaFailureCode.DRAFT_UNAVAILABLE)
            val id = UUID.randomUUID(); val objectKey = "quarantine/$environment/${UUID.randomUUID()}/$id"
            exec(c, "INSERT INTO platform.media_assets(environment,owner_user_id,id,client_draft_id,draft_generation,kind,state,version,quarantine_key,expected_sha256,expected_bytes,content_type,reservation_expires_at,created_at,updated_at,storage_protocol,storage_bucket) VALUES(?,?,?,?,?,'photo','awaitingUpload',1,?,?,?,?,?,?,?,?,?)") {
                owner(actor); setObject(3, id); setObject(4, draft); setLong(5, generation); setString(6, objectKey)
                setString(7, checksum); setLong(8, size); setString(9, type); setObject(10, time(at.plusSeconds(policy.reservationLifetimeSeconds.toLong())))
                setObject(11, time(at)); setObject(12, time(at))
                setString(13, protocol); setString(14, bucket)
            }
            val row = row(c, actor, id)
            val core = core(row, "prepareMediaUpload", 201)
            // Fail before COMMIT if even the configured maximum capability cannot fit the core.
            if (bytes(core.body!!.jsonObject).size + policy.maxCapabilityBytes + CAPABILITY_ENVELOPE > policy.maxResponseBytes) fail(MediaFailureCode.RESPONSE_TOO_LARGE)
            core
        }
        // A signing failure must not undo or forget the committed reservation. It is retriable only
        // with the same key/body; no fresh media row or changed deadline is created by reissuance.
        return when (result) {
            is CommandResult.Applied -> CommandResult.Applied(renderCapability(actor, result.reply, protocol, replayed = false))
            is CommandResult.Replayed -> CommandResult.Replayed(renderCapability(actor, result.reply, protocol, replayed = true))
            else -> result
        }
    }

    fun completeMediaUpload(actor: VerifiedMediaAccount, key: UUID, mediaId: UUID, body: JsonObject): CommandResult {
        val input = request("completeMediaUpload", body)
        val protocol = input["protocol"]?.jsonPrimitive?.content ?: LEGACY_MEDIA_PROTOCOL
        if (protocol !in setOf(LEGACY_MEDIA_PROTOCOL, SUPABASE_MEDIA_PROTOCOL)) fail(MediaFailureCode.INPUT_INVALID)
        if (protocol !in policy.allowedProtocols) fail(MediaFailureCode.NOT_CONFIGURED)
        val versionId = if (protocol == LEGACY_MEDIA_PROTOCOL) input.text("objectVersionId").also { text(it, policy.maxObjectVersionBytes) }
            else null
        if (protocol == SUPABASE_MEDIA_PROTOCOL && input.containsKey("objectVersionId")) fail(MediaFailureCode.INPUT_INVALID)
        val checksum = input.text("sha256").lowercase()
        val observed = read(actor) { c ->
            val row = locked(c, actor, mediaId, true)
            authority.requireUploadEnabled(c, actor, false); current()
            if (row.state == "awaitingUpload") awaiting(c, row)
            if (row.storageProtocol != protocol) fail(MediaFailureCode.OBJECT_MISMATCH)
            if(protocol==SUPABASE_MEDIA_PROTOCOL) requireIssuance(c,actor,row)
            row
        }
        val verified = if (observed.state == "awaitingUpload") {
            if (checksum != observed.checksum) fail(MediaFailureCode.OBJECT_MISMATCH)
            if (protocol == SUPABASE_MEDIA_PROTOCOL) {
                val provider = configuredSupabase(observed.bucket)
                val proof = external { provider.verify(objectRequest(actor, observed)) }; current()
                if (proof.objectKey != observed.objectKey || proof.bytes != observed.expectedBytes ||
                    proof.contentType != observed.contentType || proof.sha256 != observed.checksum) fail(MediaFailureCode.OBJECT_MISMATCH)
            } else {
                val request = MediaObjectVerificationRequest(environment, actor.accountId, mediaId, observed.objectKey,
                    checkNotNull(versionId), observed.expectedBytes, observed.contentType, observed.checksum)
                val proof = external { objects.verify(request) }; current()
                if (proof.objectKey != request.objectKey || proof.objectVersionId != request.objectVersionId ||
                    proof.bytes != request.expectedBytes || proof.contentType != request.contentType || proof.sha256.lowercase() != request.sha256)
                    fail(MediaFailureCode.OBJECT_MISMATCH)
            }
            true
        } else false
        return command(actor, "completeMediaUpload", key, mapOf("mediaId" to mediaId.toString()), input,
            replay = { c, cached ->
                val row = locked(c, actor, mediaId, true); authority.requireUploadEnabled(c, actor, false); current()
                if (row.state !in setOf("processing", "ready", "rejected") || row.objectVersion != versionId || row.checksum != checksum)
                    fail(MediaFailureCode.MEDIA_CONFLICT)
                exact(cached, completionCore(row, key))
            }) { c ->
            val currentRow = locked(c, actor, mediaId, true); authority.requireUploadEnabled(c, actor, false); current()
            requireUnpublished(c,actor,currentRow.draftId)
            if (!verified || currentRow != observed) fail(MediaFailureCode.MEDIA_CONFLICT)
            if(protocol==SUPABASE_MEDIA_PROTOCOL) requireIssuance(c,actor,currentRow)
            awaiting(c, currentRow) // Fresh database time AFTER external verification and lock waits.
            val at = now(c)
            exec(c, "UPDATE platform.media_assets SET state='processing',version=version+1,quarantine_version_id=?,updated_at=?,completion_key=?,completion_version=version+1,completion_accepted_at=? WHERE environment=? AND owner_user_id=? AND id=? AND state='awaitingUpload' AND version=?") {
                setString(1, versionId); setObject(2, time(at)); setObject(3, key); setObject(4, time(at))
                owner(actor, 5); setObject(7, mediaId); setLong(8, observed.version)
            }
            val row = row(c, actor, mediaId)
            val eventVersion = if (protocol == LEGACY_MEDIA_PROTOCOL) 1 else 2
            outbox.append(c, EventDraft(UUID.randomUUID(), "platform.media.upload_completed.v$eventVersion", eventVersion, "media", mediaId, row.version,
                "platform", UUID.randomUUID().toString(), key, buildJsonObject {
                    put("mediaId", mediaId.toString())
                    if (eventVersion == 1) put("quarantineVersionId", checkNotNull(versionId))
                    else { put("protocol", SUPABASE_MEDIA_PROTOCOL); put("bucket", checkNotNull(row.bucket)) }
                    put("checksum",row.checksum)
                }))
            completionCore(row, key)
        }
    }

    /** Observation never signs, renews, initializes a draft or grants access, including after expiry. */
    fun getMediaStatus(actor: VerifiedMediaAccount, mediaId: UUID): StoredReply = read(actor) { c ->
        core(locked(c, actor, mediaId, false), "getMediaStatus", 200)
    }

    fun deleteDraftMedia(actor: VerifiedMediaAccount, key: UUID, mediaId: UUID, ifMatch: String): CommandResult {
        val expected = version(ifMatch)
        return command(actor, "deleteDraftMedia", key, mapOf("mediaId" to mediaId.toString()), ifMatch = ifMatch,
            replay = { c, cached ->
                val row = locked(c, actor, mediaId, false)
                if (row.state != "deleted" || row.deletionKey != key || row.version != increment(expected)) fail(MediaFailureCode.VERSION_CONFLICT)
                requireUnattached(c, actor, mediaId); exact(cached, StoredReply(204))
                requireCleanup(c, actor, row)
            }) { c ->
            val row = locked(c, actor, mediaId, false)
            if (row.state == "deleted") fail(MediaFailureCode.MEDIA_UNAVAILABLE)
            if (row.version != expected) fail(MediaFailureCode.VERSION_CONFLICT)
            discardLocked(c, actor, row, key)
            StoredReply(204)
        }
    }

    /**
     * Internal PostDraft composition only. The caller owns this transaction and already holds the
     * current principal, command, owner head and exact client-draft lifecycle locks. No nested
     * transaction, public receipt, upload renewal or provider effect. Stream UUID-ordered root
     * media, including unlisted uploads and already-deleted assets. The callback durably captures
     * each exact terminal version in the SAME transaction; cleanup retains original deadlines,
     * derivative manifests and all V008 write intents. An attached asset aborts the entire discard.
     */
    internal fun discardOwnedDraft(c: Connection, actor: VerifiedMediaAccount, clientDraftId: UUID,
        generation: Long, key: UUID, capture: (UUID, Long) -> Unit) {
        visitDraft(c, actor, clientDraftId, generation) { row ->
            val deleted = if (row.state == "deleted") { requireUnattached(c, actor, row.id); row }
                else { discardLocked(c, actor, row, key); row(c, actor, row.id) }
            requireCleanup(c, actor, deleted); capture(deleted.id, deleted.version); current()
        }
    }

    /** Replays verify every root asset/cleanup against the caller's original captured manifest. */
    internal fun verifyOwnedDraftDiscard(c: Connection, actor: VerifiedMediaAccount, clientDraftId: UUID,
        generation: Long, verify: (UUID, Long) -> Unit) {
        visitDraft(c, actor, clientDraftId, generation) { row ->
            if (row.state != "deleted") fail(MediaFailureCode.MEDIA_CONFLICT)
            requireUnattached(c, actor, row.id)
            requireCleanup(c, actor, row); verify(row.id, row.version); current()
        }
    }

    internal data class PublicationMedia(val id: UUID, val version: Long, val derivatives: JsonObject)

    /** Exact owned post-removal composition only. The caller has tombstoned the post in
     * THIS transaction and retains principal/command/head/lifecycle/post locks. This is
     * not the draft-delete path: immutable publication links remain, and only their exact
     * assets can pass the attached-object fence. No capability, remote erase, processing
     * readiness or new publication grant is inferred. Existing cleanup is verified on
     * replay; original upload deadlines and all derivative write intents stay retained. */
    internal fun discardDeletedPostMedia(c: Connection, actor: VerifiedMediaAccount, postId: UUID,
        clientDraftId: UUID, generation: Long, key: UUID, replay: Boolean) {
        check(!c.autoCommit); checkActor(actor); authority.lockPrincipal(c, actor); current()
        val selected = query(c, "SELECT m.media_id,m.media_version,m.derivative_set FROM social.post_media m " +
            "JOIN social.posts p ON p.environment=m.environment AND p.owner_user_id=m.owner_user_id AND p.id=m.post_id " +
            "WHERE m.environment=? AND m.owner_user_id=? AND m.post_id=? AND p.status='deleted' AND p.content IS NULL ORDER BY m.media_id FOR SHARE OF m,p NOWAIT", {
            owner(actor); setObject(3, postId)
        }) { r -> buildMap {
            while (r.next()) {
                // More than can fit in the existing 64 KiB publication request.
                if (size >= 2048) fail(MediaFailureCode.STORAGE_UNAVAILABLE)
                put(r.getObject(1, UUID::class.java), PublicationMedia(r.getObject(1, UUID::class.java), r.getLong(2), Json.parseToJsonElement(r.getString(3)).jsonObject))
            }
        } }
        val terminal = query(c, "SELECT 1 FROM social.posts p JOIN social.post_publications x ON x.environment=p.environment AND x.owner_user_id=p.owner_user_id AND x.post_id=p.id " +
            "WHERE p.environment=? AND p.owner_user_id=? AND p.id=? AND p.status='deleted' AND p.content IS NULL AND x.client_draft_id=? AND x.draft_generation=? FOR SHARE OF p,x NOWAIT", {
            owner(actor); setObject(3, postId); setObject(4, clientDraftId); setLong(5, generation)
        }) { it.next() }
        if (!terminal) fail(MediaFailureCode.MEDIA_CONFLICT)
        if (generation <= 0 || authority.lockDraftLifecycle(c, actor, clientDraftId, false) != generation || lifecycle(c, actor, clientDraftId) != generation)
            fail(MediaFailureCode.DRAFT_UNAVAILABLE)
        for (original in selected.values) {
            val row = row(c, actor, original.id)
            if (row.draftId != clientDraftId || row.draftGeneration != generation || row.version < original.version) fail(MediaFailureCode.MEDIA_CONFLICT)
            requireDeletedPostAttachment(c, actor, row.id, postId)
            if (row.state == "deleted") {
                requireCleanup(c, actor, row)
                val originalCaptured = query(c, "SELECT derivative_set FROM platform.media_cleanup_jobs WHERE environment=? AND owner_user_id=? AND media_id=?", {
                    owner(actor); setObject(3, row.id)
                }) { it.next() && it.getString(1)?.let { value -> Json.parseToJsonElement(value) } == original.derivatives }
                if (!originalCaptured) fail(MediaFailureCode.MEDIA_CONFLICT)
            }
            else {
                if (replay) fail(MediaFailureCode.MEDIA_CONFLICT)
                // Keep the immutable publication's original variants even if an intervening
                // terminal processing transition cleared its live derivative pointer.
                if (row.derivatives != null && row.derivatives != original.derivatives) fail(MediaFailureCode.MEDIA_CONFLICT)
                discardLocked(c, actor, row.copy(derivatives = original.derivatives), key, postId)
                requireCleanup(c, actor, row(c, actor, row.id))
            }
            current()
        }
        authority.lockPrincipal(c, actor); current()
    }

    private fun requireDeletedPostAttachment(c: Connection, actor: VerifiedMediaAccount, id: UUID, postId: UUID) {
        val exact = query(c, "SELECT 1 FROM social.post_media m JOIN social.posts p ON p.environment=m.environment AND p.owner_user_id=m.owner_user_id AND p.id=m.post_id " +
            "WHERE m.environment=? AND m.owner_user_id=? AND m.media_id=? AND m.post_id=? AND p.status='deleted' AND p.content IS NULL FOR SHARE OF m,p NOWAIT", {
            owner(actor); setObject(3, id); setObject(4, postId)
        }) { it.next() }
        if (!exact) fail(MediaFailureCode.MEDIA_ATTACHED)
        // A later avatar choice is an independent live use, never silently removed here.
        val avatar = query(c, "SELECT avatar_media_id FROM profile.profiles WHERE environment=? AND user_id=? FOR SHARE NOWAIT", { owner(actor) }) {
            it.next() && it.getObject(1, UUID::class.java) == id
        }
        if (avatar) fail(MediaFailureCode.MEDIA_ATTACHED)
    }

    /** Same transaction as publication. Selected READY objects remain attached; unused exact-root
     * uploads are tombstoned with their original late-acceptance/derivative cleanup evidence. */
    internal fun preparePublication(c: Connection, actor: VerifiedMediaAccount, client: UUID, generation: Long,
        selected: List<UUID>, key: UUID, authorize: (UUID, Long, JsonObject) -> Unit,
        unused: (UUID, Long) -> Unit): List<PublicationMedia> {
        if (selected.distinct().size != selected.size) fail(MediaFailureCode.INPUT_INVALID)
        val found = mutableMapOf<UUID, PublicationMedia>()
        visitDraft(c,actor,client,generation) { r ->
            if (r.id in selected) {
                requirePublicationReady(c,actor,r)
                requireUnattached(c,actor,r.id)
                val derivatives = checkNotNull(r.derivatives)
                authorize(r.id,r.version,derivatives); current()
                found[r.id] = PublicationMedia(r.id,r.version,derivatives)
            } else {
                val deleted = if (r.state == "deleted") { requireUnattached(c,actor,r.id); r }
                    else { discardLocked(c,actor,r,key); row(c,actor,r.id) }
                requireCleanup(c,actor,deleted);unused(deleted.id,deleted.version);current()
            }
        }
        if (found.size != selected.size) fail(MediaFailureCode.MEDIA_UNAVAILABLE)
        return selected.map { found.getValue(it) }
    }

    internal fun verifyPublicationMedia(c: Connection, actor: VerifiedMediaAccount, client: UUID, generation: Long,
        selected: List<PublicationMedia>, unused: (UUID, Long) -> Unit) {
        val expected = selected.associateBy { it.id }; var observed = 0
        visitDraft(c,actor,client,generation) { r ->
            val original = expected[r.id]
            if (original != null) {
                requirePublicationReady(c,actor,r)
                if (r.version != original.version || r.derivatives != original.derivatives) fail(MediaFailureCode.MEDIA_CONFLICT)
                observed++
            } else {
                if (r.state != "deleted") fail(MediaFailureCode.MEDIA_CONFLICT)
                requireUnattached(c,actor,r.id);requireCleanup(c,actor,r);unused(r.id,r.version)
            }
        }
        if (observed != selected.size) fail(MediaFailureCode.MEDIA_CONFLICT)
    }

    private fun requirePublicationReady(c: Connection, actor: VerifiedMediaAccount, r: Row) {
        if (r.state != "ready" || r.derivatives == null) fail(MediaFailureCode.MEDIA_CONFLICT)
        if (r.storageProtocol == SUPABASE_MEDIA_PROTOCOL) {
            // Structural digest readiness only. The caller's mandatory publication authority
            // still checks current safety/policy and its deadline in this same transaction.
            com.feedme.server.media.processing.PostMediaReadVerifier(environment).verify(c, actor.accountId, r.id, r.version, r.derivatives)
            return
        }
        validateDerivatives(r)
        val manifest = r.derivatives.getValue("variants").jsonArray
        if (r.derivatives.getValue("version") != JsonPrimitive(1) || manifest.size != 2 ||
            manifest.map { it.jsonObject.text("variant") }.toSet() != setOf("thumbnail","display")) fail(MediaFailureCode.MEDIA_CONFLICT)
        val job = query(c,"SELECT id,state,terminal_media_version,terminal_at FROM platform.media_processing_jobs WHERE environment=? AND owner_user_id=? AND media_id=? FOR UPDATE",{owner(actor);setObject(3,r.id)}) {
            if(!it.next() || it.getString("state")!="ready" || it.getLong("terminal_media_version")!=r.version || it.getObject("terminal_at")==null) fail(MediaFailureCode.MEDIA_CONFLICT)
            it.getObject("id",UUID::class.java)
        }
        val actual = query(c,"SELECT variant,object_key,object_version_id,acknowledged_at,cleanup_required FROM platform.media_derivative_intents WHERE job_id=? ORDER BY variant FOR UPDATE",{setObject(1,job)}){rs->buildList{
            while(rs.next()) {
                if(size>=2 || rs.getString("object_version_id")==null || rs.getObject("acknowledged_at")==null || rs.getBoolean("cleanup_required"))fail(MediaFailureCode.MEDIA_CONFLICT)
                add(buildJsonObject{put("variant",rs.getString("variant"));put("key",rs.getString("object_key"));put("objectVersionId",rs.getString("object_version_id"))})
            }
        }}
        if(actual.size!=2 || actual.toSet()!=manifest.toSet())fail(MediaFailureCode.MEDIA_CONFLICT)
    }

    private fun requireUnattached(c: Connection, actor: VerifiedMediaAccount, id: UUID) {
        if(com.feedme.server.social.posts.PostPublicationLifecycle.isAttached(c,environment,actor.accountId,id))fail(MediaFailureCode.MEDIA_ATTACHED)
        authority.requireUnattached(c,actor,id);current()
    }

    private fun requireUnpublished(c: Connection, actor: VerifiedMediaAccount, client: UUID) {
        if(com.feedme.server.social.posts.PostPublicationLifecycle.isPublished(c,environment,actor.accountId,client))fail(MediaFailureCode.DRAFT_UNAVAILABLE)
    }

    /** Draft editing may retain pending/rejected photo metadata; this never asserts readiness. */
    internal fun validateDraftReferences(c: Connection, actor: VerifiedMediaAccount, clientDraftId: UUID,
        generation: Long, mediaIds: List<UUID>) {
        check(!c.autoCommit); checkActor(actor); current()
        if (generation <= 0 || authority.lockDraftLifecycle(c, actor, clientDraftId, true) != generation ||
            lifecycle(c, actor, clientDraftId) != generation) fail(MediaFailureCode.DRAFT_UNAVAILABLE)
        requireUnpublished(c,actor,clientDraftId)
        mediaIds.sorted().forEach { id ->
            val row = row(c, actor, id)
            if (row.draftId != clientDraftId || row.draftGeneration != generation || row.state == "deleted")
                fail(MediaFailureCode.DRAFT_UNAVAILABLE)
            requireUnattached(c, actor, id)
        }
    }

    private fun visitDraft(c: Connection, actor: VerifiedMediaAccount, draft: UUID, generation: Long, action: (Row) -> Unit) {
        check(!c.autoCommit); checkActor(actor); current()
        if (generation <= 0 || authority.lockDraftLifecycle(c, actor, draft, false) != generation || lifecycle(c, actor, draft) != generation)
            fail(MediaFailureCode.DRAFT_UNAVAILABLE)
        c.prepareStatement("SELECT id FROM platform.media_assets WHERE environment=? AND owner_user_id=? AND client_draft_id=? ORDER BY id FOR UPDATE").use {
            it.owner(actor); it.setObject(3, draft); it.fetchSize = 64
            it.executeQuery().use { rows -> while (rows.next()) {
                val row = row(c, actor, rows.getObject(1, UUID::class.java))
                if (row.draftId != draft || row.draftGeneration != generation) fail(MediaFailureCode.DRAFT_UNAVAILABLE)
                action(row)
            } }
        }
        current()
    }

    private fun discardLocked(c: Connection, actor: VerifiedMediaAccount, row: Row, key: UUID, deletedPostId: UUID? = null) {
        if (deletedPostId == null) requireUnattached(c, actor, row.id)
        else requireDeletedPostAttachment(c, actor, row.id, deletedPostId)
        val at = now(c)
        validateDerivatives(row)
        val manifestHash = cleanupHash(row, row.derivatives)
        exec(c, "UPDATE platform.media_assets SET state='deleted',version=?,deletion_key=?,cleanup_manifest_hash=?,derivative_set=NULL,rejection_code=NULL,updated_at=? WHERE environment=? AND owner_user_id=? AND id=? AND version=?") {
            setLong(1, increment(row.version)); setObject(2, key); setString(3,manifestHash);setObject(4, time(at)); owner(actor, 5); setObject(7, row.id); setLong(8, row.version)
        }
        exec(c, "INSERT INTO platform.media_cleanup_jobs(environment,owner_user_id,media_id,media_version,quarantine_key,known_version_id,derivative_set,manifest_hash,final_sweep_after,available_at,storage_protocol,storage_bucket) VALUES(?,?,?,?,?,?,?::jsonb,?,?,?,?,?)") {
            owner(actor); setObject(3, row.id); setLong(4, increment(row.version)); setString(5, row.objectKey); setString(6, row.objectVersion)
            setString(7,row.derivatives?.toString());setString(8,manifestHash);setObject(9, time(row.deadline)); setObject(10, time(at))
            setString(11,row.storageProtocol);setString(12,row.bucket)
        }
        MediaProcessingDeletion.capture(c, environment, actor.accountId, row.id, increment(row.version))
    }

    private fun renderCapability(actor: VerifiedMediaAccount, cached: StoredReply, protocol:String, replayed:Boolean): StoredReply {
        return if (protocol == SUPABASE_MEDIA_PROTOCOL) renderSupabaseCapability(actor, cached, replayed) else renderLegacyCapability(actor, cached)
    }

    private fun renderLegacyCapability(actor: VerifiedMediaAccount, cached: StoredReply): StoredReply = read(actor) { c ->
        val row = locked(c, actor, replyId(cached), true)
        authority.requireUploadEnabled(c, actor, false); current(); awaiting(c, row)
        exact(cached, core(row, "prepareMediaUpload", 201))
        val at = now(c); val expires = minOf(row.deadline, at.plusSeconds(policy.capabilityLifetimeSeconds.toLong()))
        val authorization = MediaUploadAuthorization(environment, actor.accountId, row.id, row.draftId, row.version,
            row.objectKey, row.contentType, row.expectedBytes, row.checksum, expires)
        val cap = try { capabilities.sign(authorization) } catch (e: CancellationException) { throw e }
            catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
            catch (_: Exception) { fail(MediaFailureCode.CAPABILITY_UNAVAILABLE) }
        current(); awaiting(c, row)
        if (mediaOrigin(cap.url) !in policy.uploadOrigins || cap.expiresAt > expires || cap.expiresAt <= now(c)) fail(MediaFailureCode.CAPABILITY_UNAVAILABLE)
        text(cap.url, policy.maxCapabilityBytes, MediaFailureCode.CAPABILITY_UNAVAILABLE)
        if (cap.fields.isEmpty() || cap.fields.size > 64) fail(MediaFailureCode.CAPABILITY_UNAVAILABLE)
        cap.fields.forEach { (k,v) -> text(k, 256, MediaFailureCode.CAPABILITY_UNAVAILABLE); text(v, policy.maxCapabilityBytes, MediaFailureCode.CAPABILITY_UNAVAILABLE) }
        val ephemeral = buildJsonObject {
            put("uploadUrl", cap.url); put("uploadMethod", "POST"); put("uploadFields", JsonObject(cap.fields.mapValues { JsonPrimitive(it.value) })); put("uploadExpiresAt", cap.expiresAt.toString())
        }
        if (bytes(ephemeral).size > policy.maxCapabilityBytes) fail(MediaFailureCode.CAPABILITY_UNAVAILABLE)
        reply("prepareMediaUpload", 201, JsonObject(cached.body!!.jsonObject + ephemeral), row.version)
    }

    /** Single durable issuance BEFORE external I/O; even a lost mint/ack cannot authorize remint.
     * The bearer exists only in this call. Replays with an issuance row OR an expired unchanged
     * reservation return the retained core. Expiry recovery never creates an issuance intent. */
    private fun renderSupabaseCapability(actor: VerifiedMediaAccount, cached: StoredReply, replayed:Boolean): StoredReply {
        val captured = read(actor) { c ->
            val row = locked(c, actor, replyId(cached), true)
            if (row.storageProtocol != SUPABASE_MEDIA_PROTOCOL || row.bucket == null) fail(MediaFailureCode.STORAGE_UNAVAILABLE)
            authority.requireUploadEnabled(c, actor, false); current()
            exact(cached, core(row, "prepareMediaUpload", 201)); configuredSupabase(row.bucket)
            if (!awaiting(c, row, exactSupabaseReplay = replayed)) return@read null
            val exists = query(c, "SELECT 1 FROM platform.media_upload_issuances WHERE environment=? AND owner_user_id=? AND media_id=? FOR UPDATE",
                { owner(actor); setObject(3,row.id) }) { it.next() }
            if (exists) null else {
                // A lock/query above may have waited past the original reservation deadline.
                val at = now(c)
                if (!mediaAwaitingUploadWindow(row.state, row.version, row.deadline, at, row.storageProtocol, replayed)) return@read null
                val deadline = minOf(row.deadline, at.plusSeconds(policy.capabilityLifetimeSeconds.toLong()))
                exec(c, "INSERT INTO platform.media_upload_issuances(environment,owner_user_id,media_id,requested_expires_at,created_at) VALUES(?,?,?,?,?)") {
                    owner(actor); setObject(3,row.id); setObject(4,time(deadline)); setObject(5,time(at))
                }
                row to deadline
            }
        } ?: return cached
        val (original, deadline) = captured
        val provider = configuredSupabase(original.bucket)
        val capability = try {
            current(); provider.mint(SupabaseUploadRequest(environment, actor.accountId, original.id, original.objectKey,
                original.expectedBytes, original.contentType, original.checksum, deadline)).also { current() }
        } catch (e: CancellationException) { throw e }
        catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
        catch (_: Exception) { fail(MediaFailureCode.CAPABILITY_UNAVAILABLE) }
        return read(actor) { c ->
            val row = locked(c, actor, original.id, true)
            authority.requireUploadEnabled(c, actor, false); current(); awaiting(c, row)
            if (row != original) fail(MediaFailureCode.MEDIA_CONFLICT)
            configuredSupabase(row.bucket)
            if (mediaOrigin(capability.url) !in policy.uploadOrigins || capability.expiresAt > deadline || capability.expiresAt <= now(c))
                fail(MediaFailureCode.CAPABILITY_UNAVAILABLE)
            text(capability.url, policy.maxCapabilityBytes, MediaFailureCode.CAPABILITY_UNAVAILABLE)
            exec(c, "UPDATE platform.media_upload_issuances SET acknowledged_expires_at=? WHERE environment=? AND owner_user_id=? AND media_id=? AND requested_expires_at=? AND acknowledged_expires_at IS NULL") {
                setObject(1,time(capability.expiresAt)); owner(actor,2); setObject(4,row.id); setObject(5,time(deadline))
            }
            val ephemeral = buildJsonObject { put("uploadCapability", buildJsonObject {
                put("protocol",SUPABASE_MEDIA_PROTOCOL);put("method","PUT");put("url",capability.url);put("expiresAt",capability.expiresAt.toString())
            }) }
            if (bytes(ephemeral).size > policy.maxCapabilityBytes) fail(MediaFailureCode.CAPABILITY_UNAVAILABLE)
            reply("prepareMediaUpload",201,JsonObject(cached.body!!.jsonObject+ephemeral),row.version)
        }
    }

    private fun configuredSupabase(bucket: String? = null): SupabaseMediaStorage {
        val provider = supabase ?: fail(MediaFailureCode.NOT_CONFIGURED)
        if(!provider.bucket.matches(Regex("[a-z0-9][a-z0-9_-]{0,62}"))) fail(MediaFailureCode.NOT_CONFIGURED)
        if (bucket != null && provider.bucket != bucket) fail(MediaFailureCode.NOT_CONFIGURED)
        return provider
    }
    private fun requireIssuance(c:Connection,actor:VerifiedMediaAccount,row:Row) {
        query(c,"SELECT requested_expires_at,created_at FROM platform.media_upload_issuances WHERE environment=? AND owner_user_id=? AND media_id=? FOR SHARE",
            {owner(actor);setObject(3,row.id)}) {
            if(!it.next() || instant(it,"requested_expires_at")>row.deadline || instant(it,"created_at")<row.created)
                fail(MediaFailureCode.MEDIA_CONFLICT)
        }
    }
    private fun objectRequest(actor: VerifiedMediaAccount, row: Row) = SupabaseObjectRequest(environment, actor.accountId,
        row.id,row.objectKey,row.expectedBytes,row.contentType,row.checksum)

    private fun locked(c: Connection, actor: VerifiedMediaAccount, id: UUID, forUpload: Boolean): Row {
        // Read immutable owner/draft identity before acquiring lifecycle locks, then lock and reload.
        val draft = query(c, "SELECT client_draft_id FROM platform.media_assets WHERE environment=? AND owner_user_id=? AND id=?", { owner(actor); setObject(3, id) }) {
            if (!it.next()) fail(MediaFailureCode.MEDIA_UNAVAILABLE); it.getObject(1, UUID::class.java)
        }
        val generation = authority.lockDraftLifecycle(c, actor, draft, forUpload); current(); positive(generation)
        val pinned = lifecycle(c, actor, draft) ?: fail(MediaFailureCode.STORAGE_UNAVAILABLE)
        val row = row(c, actor, id)
        if (row.draftId != draft || row.draftGeneration != pinned) fail(MediaFailureCode.STORAGE_UNAVAILABLE)
        if (forUpload && generation != pinned) fail(MediaFailureCode.DRAFT_UNAVAILABLE)
        return row
    }
    private fun lifecycle(c: Connection, actor: VerifiedMediaAccount, draft: UUID): Long? = query(c,
        "SELECT generation FROM platform.media_draft_lifecycles WHERE environment=? AND owner_user_id=? AND client_draft_id=? FOR UPDATE",
        { owner(actor); setObject(3, draft) }) { if (it.next()) it.getLong(1) else null }
    private fun row(c: Connection, actor: VerifiedMediaAccount, id: UUID): Row = query(c,
        "SELECT * FROM platform.media_assets WHERE environment=? AND owner_user_id=? AND id=? FOR UPDATE", { owner(actor); setObject(3, id) }) {
        if (!it.next()) fail(MediaFailureCode.MEDIA_UNAVAILABLE)
        Row(id, it.getObject("client_draft_id", UUID::class.java), it.getLong("draft_generation"), it.getString("state"), it.getLong("version"),
            it.getString("quarantine_key"), it.getString("expected_sha256"), it.getLong("expected_bytes"), it.getString("content_type"),
            instant(it,"reservation_expires_at"), it.getString("quarantine_version_id"), it.getString("rejection_code"),
            it.getObject("deletion_key", UUID::class.java), instant(it,"created_at"), instant(it,"updated_at"),
            it.getString("derivative_set")?.let { text -> Json.parseToJsonElement(text).jsonObject },it.getString("cleanup_manifest_hash"),
            it.getObject("completion_key", UUID::class.java), it.getObject("completion_version")?.let { v -> (v as Number).toLong() },
            it.getObject("completion_accepted_at", OffsetDateTime::class.java)?.toInstant(),
            it.getString("storage_protocol"),it.getString("storage_bucket"))
    }
    private fun requireCleanup(c: Connection, actor: VerifiedMediaAccount, row: Row) = query(c,
        "SELECT * FROM platform.media_cleanup_jobs WHERE environment=? AND owner_user_id=? AND media_id=? FOR UPDATE", { owner(actor); setObject(3,row.id) }) {
        if (!it.next() || it.getLong("media_version") != row.version || it.getString("quarantine_key") != row.objectKey ||
            it.getString("known_version_id") != row.objectVersion || instant(it,"final_sweep_after") != row.deadline ||
            it.getString("storage_protocol") != row.storageProtocol || it.getString("storage_bucket") != row.bucket) fail(MediaFailureCode.STORAGE_UNAVAILABLE)
        val derivatives=it.getString("derivative_set")?.let{value->Json.parseToJsonElement(value).jsonObject}
        if(it.getString("manifest_hash")!=row.cleanupHash||cleanupHash(row,derivatives)!=row.cleanupHash)fail(MediaFailureCode.STORAGE_UNAVAILABLE)
    }
    /** Internal future processor shape, not a public API or evidence that processing is implemented. */
    private fun validateDerivatives(row:Row) {
        val d=row.derivatives?:return
        if (row.storageProtocol == SUPABASE_MEDIA_PROTOCOL) {
            com.feedme.server.media.processing.SupabaseMediaReadiness.validateManifest(d, environment, row.id, row.bucket)
            return
        }
        if(d.keys!=setOf("version","variants")||d["version"]?.jsonPrimitive?.longOrNull?.let{it>0}!=true)fail(MediaFailureCode.STORAGE_UNAVAILABLE)
        val variants=d["variants"]?.jsonArray?:fail(MediaFailureCode.STORAGE_UNAVAILABLE)
        if(variants.size !in 1..2)fail(MediaFailureCode.STORAGE_UNAVAILABLE)
        val names=mutableSetOf<String>()
        variants.forEach{item->val v=item.jsonObject
            if(v.keys!=setOf("variant","key","objectVersionId")||v.text("variant") !in setOf("thumbnail","display")||!names.add(v.text("variant")))fail(MediaFailureCode.STORAGE_UNAVAILABLE)
            text(v.text("key"),200,MediaFailureCode.STORAGE_UNAVAILABLE);text(v.text("objectVersionId"),policy.maxObjectVersionBytes,MediaFailureCode.STORAGE_UNAVAILABLE)
            if(!v.text("key").matches(Regex("derivatives/${Regex.escape(environment)}/${row.id}/[a-zA-Z0-9_-]{1,80}")))fail(MediaFailureCode.STORAGE_UNAVAILABLE)
        }
    }
    private fun cleanupHash(row:Row,derivatives:JsonObject?):String = MediaCleanupManifest.hash(
        MediaCleanupManifest.canonical(row.id,row.objectKey,row.objectVersion,row.storageProtocol,
            row.bucket,row.checksum,derivatives,row.deadline))
    private fun awaiting(c: Connection, row: Row, exactSupabaseReplay:Boolean = false): Boolean =
        mediaAwaitingUploadWindow(row.state, row.version, row.deadline, now(c), row.storageProtocol, exactSupabaseReplay)
    private fun core(row: Row, operation: String, status: Int): StoredReply = reply(operation, status, buildJsonObject {
        put("id", row.id.toString()); put("version", row.version); put("createdAt", row.created.toString()); put("updatedAt", row.updated.toString()); put("status", row.state)
        row.error?.let { put("errorCode", it) }
    }, row.version)
    private fun completionCore(row: Row, key: UUID): StoredReply {
        val acceptedVersion = row.completionVersion ?: fail(MediaFailureCode.STORAGE_UNAVAILABLE)
        val acceptedAt = row.completionAcceptedAt ?: fail(MediaFailureCode.STORAGE_UNAVAILABLE)
        if (row.completionKey != key) fail(MediaFailureCode.MEDIA_CONFLICT)
        if (acceptedVersion != 2L || row.version < acceptedVersion || acceptedAt < row.created ||
            acceptedAt > row.updated || acceptedAt >= row.deadline) fail(MediaFailureCode.STORAGE_UNAVAILABLE)
        return reply("completeMediaUpload", 200, buildJsonObject {
            put("id", row.id.toString()); put("version", acceptedVersion); put("createdAt", row.created.toString())
            put("updatedAt", acceptedAt.toString()); put("status", "processing")
        }, acceptedVersion)
    }
    private fun reply(operation: String, status: Int, body: JsonObject, version: Long): StoredReply {
        val bytes = bytes(body)
        if (bytes.size > policy.maxResponseBytes) fail(MediaFailureCode.RESPONSE_TOO_LARGE)
        if (validator.validateResponse(operation,status,bytes,"application/json") != BodyValidationResult.Valid) fail(MediaFailureCode.STORAGE_UNAVAILABLE)
        return StoredReply(status,body,"\"$version\"")
    }
    private fun request(operation: String, body: JsonObject): JsonObject {
        val bytes = bytes(body, MediaFailureCode.INPUT_INVALID)
        if (validator.validateRequest(operation,bytes,"application/json") != BodyValidationResult.Valid) fail(MediaFailureCode.INPUT_INVALID)
        return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }
    private fun command(actor: VerifiedMediaAccount, operation: String, key: UUID, paths: Map<String,String> = emptyMap(), body: JsonObject? = null,
        ifMatch: String? = null, replay: (Connection,StoredReply)->Unit, mutate: (Connection)->StoredReply): CommandResult = safe {
        checkActor(actor); current()
        commands.execute(CommandIdentity(PrincipalScope(environment,CommandActor.ACCOUNT,actor.accountId),operation,key,paths,body=body,ifMatch=ifMatch),
            { authority.lockPrincipal(it,actor); current() },
            { authority.lockPrincipal(it,actor); current() },
            { c,r -> replay(c,r); authority.lockPrincipal(c,actor); current() },
            { c -> mutate(c).also { authority.lockPrincipal(c,actor); current() } })
    }
    private fun <T> read(actor: VerifiedMediaAccount, action: (Connection)->T): T = safe {
        checkActor(actor); current(); transactions.run { c ->
            authority.lockPrincipal(c,actor); current()
            action(c).also { authority.lockPrincipal(c,actor); current() }
        }
    }
    private fun <T> external(action: ()->T): T = try { current(); action().also { current() } }
        catch (e: CancellationException) { throw e } catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
        catch (e: MediaFailure) { throw e } catch (_: Exception) { fail(MediaFailureCode.OBJECT_VERIFICATION_UNAVAILABLE) }
    private fun <T> safe(action: ()->T): T = try { action() } catch (e: MediaFailure) { throw e }
        catch (e: CommitOutcomeUnknown) { throw e } catch (e: CancellationException) { throw e }
        catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e } catch (_: Exception) { fail(MediaFailureCode.STORAGE_UNAVAILABLE) }
    private fun checkActor(actor: VerifiedMediaAccount) { if (actor.environment != environment) fail(MediaFailureCode.UNAUTHENTICATED) }
    private fun current() { if (Thread.currentThread().isInterrupted) throw InterruptedException("Media operation interrupted") }
    private fun positive(value: Long) { if(value<=0) fail(MediaFailureCode.STORAGE_UNAVAILABLE) }
    private fun exact(a: StoredReply,b: StoredReply) { if(a.status!=b.status||a.etag!=b.etag||a.body!=b.body) fail(MediaFailureCode.MEDIA_CONFLICT) }
    private fun replyId(reply: StoredReply) = UUID.fromString(reply.body!!.jsonObject.text("id"))
    private fun version(value: String): Long {
        if(!value.matches(Regex("\"[0-9]{1,64}\""))) fail(MediaFailureCode.INPUT_INVALID)
        return value.drop(1).dropLast(1).trimStart('0').ifEmpty{"0"}.toLongOrNull()?.takeIf{it>0} ?: fail(MediaFailureCode.INPUT_INVALID)
    }
    private fun increment(value: Long): Long = if(value==Long.MAX_VALUE) fail(MediaFailureCode.STORAGE_UNAVAILABLE) else value+1
    private fun text(value: String,max: Int,code: MediaFailureCode=MediaFailureCode.INPUT_INVALID) {
        if(value.isEmpty()||value.any{Character.isISOControl(it)}) fail(code)
        val bytes=try{value.encodeToByteArray(throwOnInvalidSequence=true)}catch(_:Exception){fail(code)}
        if(bytes.size>max) fail(code)
    }
    private fun bytes(body: JsonObject,code: MediaFailureCode=MediaFailureCode.STORAGE_UNAVAILABLE): ByteArray = try { body.toString().encodeToByteArray(throwOnInvalidSequence=true) }
        catch(_:IllegalArgumentException){fail(code)}catch(_:CharacterCodingException){fail(code)}
    private fun JsonObject.text(name: String)=getValue(name).jsonPrimitive.content
    private fun PreparedStatement.owner(actor: VerifiedMediaAccount,start: Int=1){setString(start,environment);setObject(start+1,actor.accountId)}
    private fun time(at: Instant)=OffsetDateTime.ofInstant(at,ZoneOffset.UTC)
    private fun instant(r:ResultSet,name:String)=r.getObject(name,OffsetDateTime::class.java).toInstant()
    private fun now(c:Connection)=c.createStatement().use{s->s.executeQuery("SELECT clock_timestamp()").use{it.next();it.getObject(1,OffsetDateTime::class.java).toInstant()}}
    private fun exec(c:Connection,sql:String,bind:PreparedStatement.()->Unit){current();c.prepareStatement(sql).use{it.bind();if(it.executeUpdate()!=1)fail(MediaFailureCode.STORAGE_UNAVAILABLE)}}
    private fun <T> query(c:Connection,sql:String,bind:PreparedStatement.()->Unit,read:(ResultSet)->T):T=c.prepareStatement(sql).use{it.bind();it.executeQuery().use(read)}
    private fun fail(code:MediaFailureCode):Nothing=throw MediaFailure(code)
    private data class Row(val id:UUID,val draftId:UUID,val draftGeneration:Long,val state:String,val version:Long,
        val objectKey:String,val checksum:String,val expectedBytes:Long,val contentType:String,val deadline:Instant,
        val objectVersion:String?,val error:String?,val deletionKey:UUID?,val created:Instant,val updated:Instant,val derivatives:JsonObject?,val cleanupHash:String?,
        val completionKey:UUID?,val completionVersion:Long?,val completionAcceptedAt:Instant?,val storageProtocol:String,val bucket:String?)
    private companion object { const val CAPABILITY_ENVELOPE=256;val validator by lazy{ContractBodyValidator.bundled()} }
}

/** Window decision only, NOT authorization or receipt validation. False means core-only recovery;
 * callers must still bind the exact live command receipt, owner, eligible draft, protocol and bucket. */
internal fun mediaAwaitingUploadWindow(state:String, version:Long, deadline:Instant, at:Instant,
    protocol:String, exactSupabaseReplay:Boolean = false): Boolean {
    if (state != "awaitingUpload" || version != 1L) throw MediaFailure(MediaFailureCode.MEDIA_CONFLICT)
    if (at < deadline) return true
    if (protocol == SUPABASE_MEDIA_PROTOCOL && exactSupabaseReplay) return false
    throw MediaFailure(MediaFailureCode.UPLOAD_EXPIRED)
}
