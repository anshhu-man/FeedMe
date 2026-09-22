package com.feedme.server.staff

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import java.math.BigDecimal
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Actual queue -> exclusive assignment -> dismissal or delivery removal. Evidence stays restricted;
 * a read requires a committed purpose audit, and every mutation shares its transaction with
 * immutable history, exact original receipt and a content-free event. Removal queues an exact
 * pending cleanup intent, never a claim of physical erasure. No restriction, appeal,
 * reassignment, media URL, reporter identity or moderation grant is manufactured. */
internal class SupabaseStaffModerationStore(private val environment: String, transactions: PgTransactions,
    private val auth: SupabaseStaffAdmissionStore, val policy: StaffModerationPolicy,
    private val cursors: StaffModerationCursors) {
    private val commands = DurableCommands(transactions)
    private val outbox = OutboxStore(transactions)
    init { require(auth.isBoundTo(environment, transactions)) }

    fun adminListReports(subject: VerifiedSupabaseSubject, cursor: String?, limit: Int, traceId: String): StoredReply = access(subject) { c, actor ->
        trace(traceId); if (limit !in 1..50) invalid()
        val at = current(c, actor)
        val position = cursor?.let { cursors.decode(it, environment, actor.actorId, limit, at) }
        val through = position?.through ?: at
        val expires = position?.expires ?: through.plusSeconds(policy.cursorLifetimeSeconds)
        val page = query(c, SELECT + " WHERE r.environment=? AND c.status IN ('open','assigned') AND c.created_at<=? " +
            (if (position == null) "" else "AND ($PRIORITY,c.created_at,c.id)>(?,?,?) ") +
            "ORDER BY $PRIORITY,c.created_at,c.id LIMIT ? FOR SHARE OF r,c NOWAIT", {
                setString(1, environment); setObject(2, time(through)); var index = 3
                if (position != null) { setInt(index++, position.priority); setObject(index++, time(position.after)); setObject(index++, position.id) }
                setInt(index, limit + 1)
            }) { rows -> buildList { while (rows.next()) add(row(rows)) } }
        val selected = mutableListOf<Case>()
        var next: String? = null
        for (item in page.take(limit)) {
            val candidate = selected + item
            val possibleNext = cursors.encode(environment, actor.actorId, limit,
                StaffModerationCursors.Position(through, rank(item.priority), item.created, item.id, expires))
            // Reserve the bounded cursor and final clock before deciding the prefix. Never
            // omit an item without retaining its preceding position for the next page.
            if (pageJson(candidate, possibleNext, at).toString().encodeToByteArray().size + 64 > policy.maxResponseBytes) {
                if (selected.isEmpty()) unavailable()
                break
            }
            selected += item
        }
        if (selected.size < page.size) {
            val last = selected.lastOrNull() ?: unavailable()
            next = cursors.encode(environment, actor.actorId, limit,
                StaffModerationCursors.Position(through, rank(last.priority), last.created, last.id, expires))
        }
        val finalAt = current(c, actor)
        if (finalAt < at || finalAt >= expires) fail(StaffModerationFailureCode.CURSOR_EXPIRED)
        val reply = reply(LIST, pageJson(selected, next, finalAt))
        audit(c, actor, "queue-list", selected, traceId, finalAt)
        current(c, actor); reply
    }

    /** Bounded, role-scoped audit projection over the immutable moderation access and
     * action journals. Private evidence, notes, provider identifiers and request bodies
     * never enter this DTO. Reading the page appends its own immutable access receipt. */
    fun adminListAudit(subject: VerifiedSupabaseSubject, cursor: String?, limit: Int, traceId: String): StoredReply = access(subject) { c, actor ->
        trace(traceId); if (limit !in 1..50) invalid()
        val at = current(c, actor)
        val position = cursor?.let { cursors.decodeAudit(it, environment, actor.actorId, limit, at) }
        val through = position?.through ?: at
        val expires = position?.expires ?: through.plusSeconds(policy.cursorLifetimeSeconds)
        val page = query(c, AUDIT_SELECT + " WHERE created_at<=? " +
            (if (position == null) "" else "AND (created_at,source_rank,id)<(?,?,?) ") +
            "ORDER BY created_at DESC,source_rank DESC,id DESC LIMIT ?", {
                setString(1, environment); setString(2, environment); setObject(3, time(through)); var index = 4
                if (position != null) { setObject(index++, time(position.after)); setInt(index++, position.priority); setObject(index++, position.id) }
                setInt(index, limit + 1)
            }) { rows -> buildList { while (rows.next()) add(auditRow(rows)) } }
        val selected = mutableListOf<Audit>()
        var next: String? = null
        for (item in page.take(limit)) {
            val candidate = selected + item
            val possibleNext = cursors.encodeAudit(environment, actor.actorId, limit,
                StaffModerationCursors.Position(through, item.source, item.created, item.id, expires))
            if (auditPageJson(candidate, possibleNext, at).toString().encodeToByteArray().size + 64 > policy.maxResponseBytes) {
                if (selected.isEmpty()) unavailable()
                break
            }
            selected += item
        }
        if (selected.size < page.size) {
            val last = selected.lastOrNull() ?: unavailable()
            next = cursors.encodeAudit(environment, actor.actorId, limit,
                StaffModerationCursors.Position(through, last.source, last.created, last.id, expires))
        }
        val finalAt = current(c, actor)
        if (finalAt < at || finalAt >= expires) fail(StaffModerationFailureCode.CURSOR_EXPIRED)
        val reply = reply(AUDIT, auditPageJson(selected, next, finalAt))
        auditRead(c, actor, selected, traceId, finalAt)
        current(c, actor); reply
    }

    fun adminGetCase(subject: VerifiedSupabaseSubject, caseId: UUID, traceId: String): StoredReply = access(subject) { c, actor ->
        trace(traceId)
        val item = byCase(c, caseId) ?: missing()
        readable(actor, item)
        val capture = capture(c, item)
        val history = history(c, item)
        val assigned = item.status == "assigned" && item.assignee == actor.actorId
        val removable = if (assigned) removalTarget(c, item, capture) else null
        val body = JsonObject(summary(item) + mapOf("evidence" to capture.projection, "history" to JsonArray(history.map(::historyJson)),
            "dismissible" to JsonPrimitive(assigned), "removable" to JsonPrimitive(removable != null),
            "removalTargetVersion" to (removable?.version?.let(::JsonPrimitive) ?: JsonNull)))
        val reply = reply(GET, body, item.version)
        audit(c, actor, "case-review", listOf(item), traceId, current(c, actor))
        current(c, actor); reply
    }

    fun adminClaimReport(subject: VerifiedSupabaseSubject, reportId: UUID, key: UUID,
        body: JsonObject, traceId: String): CommandResult = write(subject, reportId, key, null, body, traceId, "claim")

    fun adminDismissReport(subject: VerifiedSupabaseSubject, reportId: UUID, key: UUID,
        ifMatch: String, body: JsonObject, traceId: String): CommandResult = write(subject, reportId, key, ifMatch, body, traceId, "dismiss")

    fun adminRemoveReport(subject: VerifiedSupabaseSubject, reportId: UUID, key: UUID,
        ifMatch: String, body: JsonObject, traceId: String): CommandResult = write(subject, reportId, key, ifMatch, body, traceId, "remove")

    private fun write(subject: VerifiedSupabaseSubject, reportId: UUID, key: UUID, match: String?, body: JsonObject,
        traceId: String, action: String): CommandResult {
        val resolution = action != "claim"
        val operation = if (resolution) DISMISS else CLAIM
        val input = request(operation, body, action)
        val expected = match?.let(::version)
        if (resolution != (expected != null)) invalid()
        trace(traceId)
        return access(subject) { c, actor ->
            val identity = CommandIdentity(PrincipalScope(environment, CommandActor.STAFF, actor.actorId), operation, key,
                mapOf("reportId" to reportId.toString()), body = input, ifMatch = match)
            val result = commands.executeInTransaction(c, identity,
                validatePrincipal = { same(c, it); current(c, actor) },
                authorizeNew = { same(c, it); current(c, actor) },
                authorizeReplay = { actual, cached ->
                    same(c, actual)
                    val item = byReport(c, reportId) ?: missing()
                    readable(actor, item)
                    val original = history(c, item).singleOrNull { it.actor == actor.actorId && it.operation == operation && it.key == key }
                        ?: unavailable()
                    if (original.requestHash != identity.requestHash || original.request != input || original.ifMatch != match ||
                        cached.status != original.reply.status || cached.body != original.reply.body || cached.etag != original.reply.etag) unavailable()
                    validateReply(operation, cached)
                    audit(c, actor, "$action-receipt", listOf(item), traceId, current(c, actor))
                }) { actual ->
                    same(c, actual)
                    val before = byReport(c, reportId, write = true) ?: missing()
                    readable(actor, before)
                    history(c, before) // Refuse an incomplete or altered existing lifecycle.
                    if (resolution) {
                        if (before.assignee != actor.actorId || before.status != "assigned") conflict()
                        if (before.version != expected) fail(StaffModerationFailureCode.VERSION_CONFLICT)
                    } else if (before.status != "open" || before.assignee != null) conflict()
                    val capture = capture(c, before) // No decision against an incomplete/corrupt original capture.
                    val target = if (action == "remove") removalTarget(c, before, capture) ?: conflict() else null
                    if (target != null && input["targetVersion"] != JsonPrimitive(target.version)) fail(StaffModerationFailureCode.VERSION_CONFLICT)
                    val at = current(c, actor)
                    if (at < before.updated) unavailable()
                    val after = before.copy(version = Math.addExact(before.version, 1), updated = at,
                        status = if (resolution) "resolved" else "assigned", assignee = actor.actorId,
                        action = if (action == "remove") "remove" else "none",
                        reasonCode = input.getValue("reasonCode").jsonPrimitive.content,
                        reportVersion = Math.addExact(before.reportVersion, 1), reportUpdated = at,
                        reportStatus = when (action) { "remove" -> "resolved"; "dismiss" -> "dismissed"; else -> "triaged" })
                    exec(c, "UPDATE safety.moderation_cases SET version=?,status=?,assignee_staff_id=?,reason_code=?,updated_at=?,action=? WHERE environment=? AND id=? AND version=?") {
                        setLong(1, after.version); setString(2, after.status); setObject(3, actor.actorId); setString(4, after.reasonCode)
                        setObject(5, time(at)); setString(6, after.action); setString(7, environment); setObject(8, after.id); setLong(9, before.version)
                    }
                    exec(c, "UPDATE safety.reports SET version=?,status=?,updated_at=? WHERE environment=? AND id=? AND version=?") {
                        setLong(1, after.reportVersion); setString(2, after.reportStatus); setObject(3, time(at))
                        setString(4, environment); setObject(5, reportId); setLong(6, before.reportVersion)
                    }
                    val reply = reply(operation, summary(after), after.version)
                    val event = UUID.randomUUID()
                    val actionId = action(c, actor, after, action, identity, input, match, reply, event, traceId, at)
                    if (target != null) removal(c, after, actionId, target, at)
                    outbox.append(c, EventDraft(event, "safety.moderation.$action.v1", 1, "moderationCase", after.id, after.version,
                        "safety", key.toString(), key, buildJsonObject {
                            put("caseId", after.id.toString()); put("reportId", reportId.toString()); put("caseVersion", after.version); put("action", action)
                        }))
                    current(c, actor); reply
                }
            current(c, actor); result
        }
    }

    private data class Case(val id: UUID, val report: UUID, val reporter: UUID, val type: String, val target: UUID,
        val version: Long, val priority: String, val status: String, val assignee: UUID?, val reasonCode: String?,
        val created: Instant, val updated: Instant, val reportVersion: Long, val reportCreated: Instant,
        val reportUpdated: Instant, val reportReason: String, val reportStatus: String, val action: String)

    private data class Audit(val id: UUID, val version: Long, val created: Instant, val actor: UUID,
        val action: String, val targetType: String, val targetId: String, val reason: String,
        val traceId: String, val source: Int)

    private fun auditRow(r: ResultSet): Audit {
        val value = Audit(r.getObject("id", UUID::class.java), r.getLong("version"), instant(r, "created_at"),
            r.getObject("actor_id", UUID::class.java), r.getString("operation_id"), r.getString("target_type"),
            r.getString("target_id"), r.getString("reason"), r.getString("trace_id"), r.getInt("source_rank"))
        if (value.version <= 0 || value.targetId.isBlank() || value.targetId.length > 128 ||
            value.reason.isBlank() || value.reason.length > 100 || value.reason.any(Char::isISOControl) ||
            value.traceId.length !in 1..128 || value.traceId.any(Char::isISOControl)) unavailable()
        if (value.source == 0) {
            if (value.version != 1L || value.targetType != "moderationAccess" || value.targetId != value.id.toString() ||
                value.action !in setOf(LIST, GET, CLAIM, DISMISS, AUDIT) ||
                value.reason !in setOf("queue-list", "case-review", "claim-receipt", "dismiss-receipt", "remove-receipt", "audit-list")) unavailable()
        } else if (value.source == 1) {
            if (value.version !in 2L..3L || value.action !in setOf(CLAIM, DISMISS) ||
                value.targetType !in setOf("post", "message", "user", "shortcut") || !uuid(value.targetId)) unavailable()
        } else unavailable()
        return value
    }

    private fun auditPageJson(items: List<Audit>, cursor: String?, at: Instant) = buildJsonObject {
        put("items", JsonArray(items.map { item -> buildJsonObject {
            put("id", item.id.toString()); put("version", item.version); put("createdAt", item.created.toString())
            put("updatedAt", item.created.toString()); put("actorId", item.actor.toString()); put("action", item.action)
            put("targetType", item.targetType); put("targetId", item.targetId); put("reason", item.reason); put("traceId", item.traceId)
        } }))
        put("nextCursor", cursor?.let(::JsonPrimitive) ?: JsonNull); put("serverTime", at.toString())
    }

    private fun row(r: ResultSet): Case {
        val value = Case(r.getObject("case_id", UUID::class.java), r.getObject("report_id", UUID::class.java),
            r.getObject("reporter_user_id", UUID::class.java), r.getString("target_type"), r.getObject("target_id", UUID::class.java),
            r.getLong("case_version"), r.getString("priority"), r.getString("case_status"), r.getObject("assignee_staff_id", UUID::class.java),
            r.getString("reason_code"), instant(r, "case_created"), instant(r, "case_updated"), r.getLong("report_version"),
            instant(r, "report_created"), instant(r, "report_updated"), r.getString("reason"), r.getString("report_status"), r.getString("case_action"))
        if (r.getObject("linked_report_id", UUID::class.java) != value.report || r.getObject("linked_case_id", UUID::class.java) != value.id ||
            r.getString("case_target_type") != value.type || r.getObject("case_target_id", UUID::class.java) != value.target ||
            value.type !in setOf("post", "message", "user", "shortcut") || value.action !in setOf("none", "remove") ||
            value.reportReason !in setOf("harassment", "unsafeFood", "privacy", "spam", "other") ||
            value.created != value.reportCreated || value.updated != value.reportUpdated || value.updated < value.created ||
            value.version != value.reportVersion || value.priority !in setOf("urgent", "high", "normal")) unavailable()
        val lifecycle = when (value.version) {
            1L -> value.action == "none" && value.status == "open" && value.reportStatus == "received" && value.assignee == null && value.reasonCode == null
            2L -> value.action == "none" && value.status == "assigned" && value.reportStatus == "triaged" && value.assignee != null && value.reasonCode != null
            3L -> value.status == "resolved" && value.assignee != null && value.reasonCode != null &&
                (value.action == "none" && value.reportStatus == "dismissed" || value.action == "remove" && value.reportStatus == "resolved" && value.type in setOf("post", "message"))
            else -> false
        }
        if (!lifecycle) unavailable()
        return value
    }
    private fun byCase(c: Connection, id: UUID) = one(c, "c.id", id, false)
    private fun byReport(c: Connection, id: UUID, write: Boolean = false) = one(c, "r.id", id, write)
    private fun one(c: Connection, field: String, id: UUID, write: Boolean): Case? = query(c,
        SELECT + " WHERE r.environment=? AND $field=? FOR ${if (write) "UPDATE" else "SHARE"} OF r,c NOWAIT",
        { setString(1, environment); setObject(2, id) }) { r -> if (!r.next()) null else row(r).also { if (r.next()) unavailable() } }
    private fun readable(actor: SupabaseStaffModeratorActor, item: Case) {
        if (item.assignee != null && item.assignee != actor.actorId) missing()
    }
    private fun summary(item: Case) = buildJsonObject {
        put("id", item.id.toString()); put("version", item.version); put("createdAt", item.created.toString()); put("updatedAt", item.updated.toString())
        put("reportIds", JsonArray(listOf(JsonPrimitive(item.report.toString())))); put("targetType", item.type); put("targetId", item.target.toString())
        put("priority", item.priority); put("status", item.status); item.assignee?.let { put("assigneeId", it.toString()) }
        put("action", item.action); item.reasonCode?.let { put("reasonCode", it) }
    }
    private fun pageJson(items: List<Case>, cursor: String?, at: Instant) = buildJsonObject {
        put("items", JsonArray(items.map { item -> buildJsonObject {
            put("id", item.report.toString()); put("version", item.reportVersion); put("createdAt", item.reportCreated.toString())
            put("updatedAt", item.reportUpdated.toString()); put("targetType", item.type); put("targetId", item.target.toString())
            put("reason", item.reportReason); put("status", item.reportStatus); put("caseId", item.id.toString()); put("caseVersion", item.version)
            put("priority", item.priority); put("caseStatus", item.status); item.assignee?.let { put("assigneeId", it.toString()) }
        } })); put("nextCursor", cursor?.let(::JsonPrimitive) ?: JsonNull); put("serverTime", at.toString())
    }

    private data class Capture(val projection: JsonObject, val owner: UUID, val version: Long, val material: JsonObject)
    private fun capture(c: Connection, item: Case): Capture = query(c,
        "SELECT target_type,target_id,target_owner_id,target_version,evidence_text,evidence_sha256,valid_until,captured_at FROM safety.report_evidence " +
            "WHERE environment=? AND reporter_user_id=? AND report_id=? FOR SHARE NOWAIT",
        { setString(1, environment); setObject(2, item.reporter); setObject(3, item.report) }) { r ->
        if (!r.next()) unavailable()
        val source = r.getString("evidence_text") ?: unavailable()
        val version = r.getLong("target_version"); val owner = r.getObject("target_owner_id", UUID::class.java)
        val captured = instant(r, "captured_at"); val until = r.getObject("valid_until", OffsetDateTime::class.java)?.toInstant()
        if (version <= 0 || owner == null || captured != item.created || until?.let { it <= captured } == true ||
            r.getString("target_type") != item.type || r.getObject("target_id", UUID::class.java) != item.target ||
            hash(source) != r.getString("evidence_sha256")) unavailable()
        val original = document(source, 524288)
        if (original.keys != setOf("formatVersion", "targetType", "targetId", "targetOwnerId", "version", "material", "validUntil") ||
            original["formatVersion"] != JsonPrimitive(1) || original["targetType"] != JsonPrimitive(item.type) ||
            original["targetId"] != JsonPrimitive(item.target.toString()) || original["targetOwnerId"] != JsonPrimitive(owner.toString()) ||
            original["version"] != JsonPrimitive(version) ||
            original["validUntil"] != (until?.let { JsonPrimitive(it.toString()) } ?: JsonNull)) unavailable()
        val material = original["material"] as? JsonObject ?: unavailable()
        if (material["id"] != JsonPrimitive(item.target.toString()) || material["version"] != JsonPrimitive(version)) unavailable()
        val fields = mutableListOf<JsonObject>()
        fun text(field: String, label: String, required: Boolean = false) {
            val value = material[field]
            if (value == null) { if (required) unavailable(); return }
            val string = value as? JsonPrimitive ?: unavailable()
            if (!string.isString || string.content.length > 4000) unavailable()
            fields += buildJsonObject { put("label", label); put("value", string.content) }
        }
        when (item.type) {
            "post" -> {
                if (material.keys.any { it !in setOf("id", "ownerId", "version", "contentSha256", "status", "caption", "mediaIds", "publishedAt", "expiresAt", "keepOnPlate") } ||
                    material["ownerId"] != JsonPrimitive(owner.toString())) unavailable()
                text("caption", "Caption")
                val media = material["mediaIds"]?.let { it as? JsonArray ?: unavailable() } ?: JsonArray(emptyList())
                if (media.any { it !is JsonPrimitive || !it.isString || !uuid(it.content) } || media.distinct().size != media.size) unavailable()
                fields += buildJsonObject { put("label", "Media count"); put("value", media.size.toString()) }
                text("status", "Post status", true)
            }
            "message" -> {
                if (material.keys.any { it !in setOf("id", "threadId", "ownerId", "version", "kind", "text", "createdAt", "recipeRequestId", "recipeRequestVersion", "recipeRequestStatus", "recipeVersionId", "contentSha256") } ||
                    material["ownerId"] != JsonPrimitive(owner.toString()) || material["kind"]?.jsonPrimitive?.content !in setOf("text", "recipeRequest", "recipeCard", "system")) unavailable()
                text("kind", "Message kind", true); text("text", "Message", true); text("createdAt", "Sent at", true)
            }
            "user" -> {
                if (owner != item.target || material.keys.any { it !in setOf("id", "version", "displayName", "handle", "bio") }) unavailable()
                text("displayName", "Display name"); text("handle", "Handle"); text("bio", "Bio")
            }
            else -> unavailable() // No invented shortcut evidence integration.
        }
        if (fields.size !in 1..12 || r.next()) unavailable()
        Capture(buildJsonObject { put("targetVersion", version); put("capturedAt", captured.toString())
            put("originalSourceValidUntil", until?.let { JsonPrimitive(it.toString()) } ?: JsonNull); put("fields", JsonArray(fields)) }, owner, version, material)
    }

    private data class Action(val id: UUID, val action: String, val actor: UUID, val reason: String, val notes: String,
        val created: Instant, val version: Long, val operation: String, val key: UUID, val requestHash: String,
        val request: JsonObject, val ifMatch: String?, val reply: StoredReply, val targetVersion: Long?)

    private data class RemovalTarget(val owner: UUID, val version: Long, val sha256: String)
    /** IDs locate the actual immutable authored source; historical evidence is not a
     * substitute for the current target/version. A changed post requires a new report
     * observation instead of silently applying an old review to changed content. */
    private fun removalTarget(c: Connection, item: Case, captured: Capture): RemovalTarget? {
        if (item.type !in setOf("post", "message")) return null
        if (query(c, "SELECT 1 FROM safety.moderation_removals WHERE environment=? AND target_type=? AND target_id=?", {
                setString(1, environment); setString(2, item.type); setObject(3, item.target)
            }) { it.next() }) return null
        val source = captured.material
        return if (item.type == "post") query(c, "SELECT owner_user_id,version,status,content,encode(sha256(convert_to(content::text,'UTF8')),'hex') target_hash " +
            "FROM social.posts WHERE environment=? AND id=? FOR SHARE NOWAIT", { setString(1, environment); setObject(2, item.target) }) { r ->
            if (!r.next()) return@query null
            val owner = r.getObject("owner_user_id", UUID::class.java); val version = r.getLong("version")
            val content = r.getString("content")?.let { document(it, 262144) }
            val sha = r.getString("target_hash")
            val compatible = r.getString("status") == "published" && owner == captured.owner && version == captured.version &&
                content != null && content["id"] == JsonPrimitive(item.target.toString()) && content["version"] == JsonPrimitive(version) &&
                source["contentSha256"] == JsonPrimitive(hash(canonicalSource(content)))
            if (r.next()) unavailable()
            if (compatible) RemovalTarget(owner, version, sha ?: unavailable()) else null
        } else query(c, "SELECT *,encode(sha256(convert_to($MESSAGE_FINGERPRINT::text,'UTF8')),'hex') target_hash " +
            "FROM social.thread_messages WHERE environment=? AND id=? FOR SHARE NOWAIT", { setString(1, environment); setObject(2, item.target) }) { r ->
            if (!r.next()) return@query null
            val owner = r.getObject("sender_user_id", UUID::class.java)
            fun reference(name: String, column: String): Boolean = source[name] ==
                (r.getObject(column, UUID::class.java)?.let { JsonPrimitive(it.toString()) })
            val compatible = captured.version == 1L && owner == captured.owner && reference("threadId", "thread_id") &&
                source["kind"] == JsonPrimitive(r.getString("kind")) && source["text"] == JsonPrimitive(r.getString("text")) &&
                source["createdAt"] == JsonPrimitive(instant(r, "created_at").toString()) &&
                reference("recipeRequestId", "recipe_request_id") && reference("recipeVersionId", "recipe_version_id")
            val sha = r.getString("target_hash")
            if (r.next()) unavailable()
            if (compatible) RemovalTarget(owner, 1L, sha ?: unavailable()) else null
        }
    }
    private fun removal(c: Connection, item: Case, actionId: UUID, target: RemovalTarget, at: Instant) {
        exec(c, "INSERT INTO safety.moderation_removals(environment,id,case_id,report_id,action_id,target_type,target_id,target_owner_id,target_version,target_sha256,state,created_at) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?,'pending',?)") {
            setString(1, environment); setObject(2, UUID.randomUUID()); setObject(3, item.id); setObject(4, item.report); setObject(5, actionId)
            setString(6, item.type); setObject(7, item.target); setObject(8, target.owner); setLong(9, target.version); setString(10, target.sha256); setObject(11, time(at))
        }
    }
    private fun checkRemoval(c: Connection, item: Case, actionId: UUID, version: Long, at: Instant) {
        query(c, "SELECT target_type,target_id,target_owner_id,target_version,target_sha256,state,created_at,report_id " +
            "FROM safety.moderation_removals WHERE environment=? AND case_id=? AND action_id=? FOR SHARE NOWAIT", {
            setString(1, environment); setObject(2, item.id); setObject(3, actionId)
        }) { r ->
            if (!r.next() || r.getString("target_type") != item.type || r.getObject("target_id", UUID::class.java) != item.target ||
                r.getObject("report_id", UUID::class.java) != item.report || r.getLong("target_version") != version ||
                r.getObject("target_owner_id", UUID::class.java) == null || !r.getString("target_sha256").matches(Regex("[0-9a-f]{64}")) ||
                r.getString("state") != "pending" || instant(r, "created_at") != at || r.next()) unavailable()
        }
    }
    private fun history(c: Connection, item: Case): List<Action> = query(c,
        "SELECT * FROM safety.moderation_actions WHERE environment=? AND case_id=? ORDER BY case_version LIMIT 3 FOR SHARE NOWAIT",
        { setString(1, environment); setObject(2, item.id) }) { r ->
        val result = buildList<Action> { while (r.next()) {
            val version = r.getLong("case_version"); val action = r.getString("action"); val actor = r.getObject("actor_id", UUID::class.java)
            val created = instant(r, "created_at"); val reason = r.getString("reason_code"); val notes = r.getString("notes")
            val operation = r.getString("operation_id"); val key = r.getObject("command_key", UUID::class.java)
            val input = document(r.getString("request_text"), 16384); val match = r.getString("if_match")
            val targetVersion = r.getObject("target_version", java.lang.Long::class.java)?.toLong()
            val responseText = r.getString("response_text"); val response = document(responseText, 16384)
            val identity = CommandIdentity(PrincipalScope(environment, CommandActor.STAFF, actor), operation, key,
                mapOf("reportId" to item.report.toString()), body = input, ifMatch = match)
            if (r.getObject("report_id", UUID::class.java) != item.report || r.getLong("report_version") != version ||
                identity.requestHash != r.getString("request_sha256") || hash(responseText) != r.getString("response_sha256") ||
                version != size + 2L || created < item.created || created > item.updated || actor != item.assignee ||
                (version == 2L && (action != "claim" || operation != CLAIM || match != null)) ||
                (version == 3L && (action !in setOf("dismiss", "remove") || operation != DISMISS || match != "\"2\"")) || version !in 2L..3L ||
                (action == "remove") != (targetVersion != null) || targetVersion?.let { it <= 0 } == true) unavailable()
            val expected = buildJsonObject { if (action != "claim") put("action", action); put("reasonCode", reason); put("notes", notes)
                targetVersion?.let { put("targetVersion", it) } }
            if (input != expected || request(operation, input, action) != input ||
                response != summary(item.copy(version = version, updated = created, status = if (action == "claim") "assigned" else "resolved",
                    reasonCode = reason, action = if (action == "remove") "remove" else "none"))) unavailable()
            val id = r.getObject("id", UUID::class.java)
            if (action == "remove") checkRemoval(c, item, id, targetVersion!!, created)
            add(Action(id, action, actor, reason, notes, created, version, operation, key,
                identity.requestHash, input, match, reply(operation, response, version), targetVersion))
        } }
        if (result.size.toLong() != item.version - 1 || result.zipWithNext().any { (a, b) -> b.created < a.created } ||
            result.lastOrNull()?.let { it.created != item.updated || it.reason != item.reasonCode } == true) unavailable()
        result
    }
    private fun historyJson(value: Action) = buildJsonObject {
        put("id", value.id.toString()); put("action", value.action); put("actorId", value.actor.toString()); put("reasonCode", value.reason)
        put("notes", value.notes); put("createdAt", value.created.toString()); put("caseVersion", value.version)
        value.targetVersion?.let { put("targetVersion", it) }
    }
    private fun action(c: Connection, actor: SupabaseStaffModeratorActor, item: Case, action: String, identity: CommandIdentity,
        input: JsonObject, match: String?, reply: StoredReply, event: UUID, traceId: String, at: Instant): UUID {
        val response = requireNotNull(reply.body).toString()
        val id = UUID.randomUUID()
        exec(c, "INSERT INTO safety.moderation_actions(environment,id,case_id,report_id,case_version,report_version,actor_id,provider_session_id," +
            "authority_revision,action,reason_code,notes,operation_id,command_key,request_sha256,request_text,if_match,response_text,response_sha256,event_id,trace_id,created_at,target_version) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)") {
            setString(1, environment); setObject(2, id); setObject(3, item.id); setObject(4, item.report)
            setLong(5, item.version); setLong(6, item.reportVersion); setObject(7, actor.actorId); setObject(8, actor.providerSessionId)
            setString(9, actor.authorityRevision); setString(10, action); setString(11, input.getValue("reasonCode").jsonPrimitive.content)
            setString(12, input.getValue("notes").jsonPrimitive.content); setString(13, identity.operationId); setObject(14, identity.key)
            setString(15, identity.requestHash); setString(16, input.toString()); setString(17, match); setString(18, response)
            setString(19, hash(response)); setObject(20, event); setString(21, traceId); setObject(22, time(at))
            setObject(23, input["targetVersion"]?.jsonPrimitive?.long)
        }
        return id
    }
    private fun audit(c: Connection, actor: SupabaseStaffModeratorActor, purpose: String, items: List<Case>, traceId: String, at: Instant) {
        val observed = JsonArray(items.map { buildJsonObject { put("caseId", it.id.toString()); put("caseVersion", it.version) } })
        exec(c, "INSERT INTO safety.moderation_access_audit(environment,id,actor_id,provider_session_id,authority_revision,purpose,observed_cases,trace_id,created_at) VALUES(?,?,?,?,?,?,?::jsonb,?,?)") {
            setString(1, environment); setObject(2, UUID.randomUUID()); setObject(3, actor.actorId); setObject(4, actor.providerSessionId)
            setString(5, actor.authorityRevision); setString(6, purpose); setString(7, observed.toString()); setString(8, traceId); setObject(9, time(at))
        }
    }
    private fun auditRead(c: Connection, actor: SupabaseStaffModeratorActor, items: List<Audit>, traceId: String, at: Instant) {
        val observed = JsonArray(items.map { buildJsonObject {
            put("auditId", it.id.toString()); put("auditVersion", it.version); put("source", if (it.source == 0) "access" else "action")
        } })
        exec(c, "INSERT INTO safety.moderation_access_audit(environment,id,actor_id,provider_session_id,authority_revision,purpose,observed_cases,trace_id,created_at) VALUES(?,?,?,?,?,?,?::jsonb,?,?)") {
            setString(1, environment); setObject(2, UUID.randomUUID()); setObject(3, actor.actorId); setObject(4, actor.providerSessionId)
            setString(5, actor.authorityRevision); setString(6, "audit-list"); setString(7, observed.toString()); setString(8, traceId); setObject(9, time(at))
        }
    }
    private fun request(operation: String, value: JsonObject, action: String): JsonObject {
        val text = value.toString(); val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
        if (bytes.size > 16384 || validator.validateRequest(operation, bytes, "application/json") != BodyValidationResult.Valid ||
            value.keys != (when (action) { "remove" -> setOf("action", "reasonCode", "notes", "targetVersion"); "dismiss" -> setOf("action", "reasonCode", "notes"); else -> setOf("reasonCode", "notes") }) ||
            (action != "claim" && value["action"] != JsonPrimitive(action)) ||
            (action == "remove" && (value["targetVersion"]?.jsonPrimitive?.longOrNull ?: 0L) <= 0L)) invalid()
        val reason = value["reasonCode"]?.jsonPrimitive?.content ?: invalid()
        val notes = value["notes"]?.jsonPrimitive?.content ?: invalid()
        if (reason.length !in 1..100 || reason.isBlank() || reason.any(Char::isISOControl) || notes.length !in 1..2000 || notes.isBlank()) invalid()
        return document(text, 16384)
    }
    private fun trace(value: String) { if (value.length !in 1..128 || value.any(Char::isISOControl)) invalid() }
    private fun version(value: String): Long {
        if (!value.matches(Regex("\"[1-9][0-9]{0,18}\""))) invalid()
        return value.removeSurrounding("\"").toLongOrNull() ?: invalid()
    }
    private fun current(c: Connection, actor: SupabaseStaffModeratorActor): Instant {
        actor.requireConnection(c); if (actor.environment != environment) unavailable()
        return query(c, "SELECT clock_timestamp()", {}) { r ->
            if (!r.next()) unavailable()
            val at = r.getObject(1, OffsetDateTime::class.java).toInstant()
            if (r.next()) unavailable(); actor.checkAt(at); at
        }
    }
    private fun reply(operation: String, body: JsonObject, version: Long? = null) = StoredReply(200, body, version?.let { "\"$it\"" })
        .also { validateReply(operation, it) }
    private fun validateReply(operation: String, reply: StoredReply) {
        val bytes = reply.body?.toString()?.encodeToByteArray() ?: unavailable()
        if (bytes.size > policy.maxResponseBytes || validator.validateResponse(operation, reply.status, bytes, "application/json") != BodyValidationResult.Valid) unavailable()
    }
    private fun <T> access(subject: VerifiedSupabaseSubject, work: (Connection, SupabaseStaffModeratorActor) -> T): T = safe {
        auth.withModerationAccess(subject) { c, actor -> StaffModerationServingCompatibility.check(c); work(c, actor) }
    }
    private fun <T> safe(work: () -> T): T = try { work() }
        catch (e: StaffModerationFailure) { throw e }
        catch (e: SupabaseStaffFailure) { throw e }
        catch (e: CommitOutcomeUnknown) { throw e }
        catch (e: CancellationException) { throw e }
        catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
        catch (_: Exception) { unavailable() }
    private fun <T> query(c: Connection, sql: String, bind: PreparedStatement.() -> Unit, result: (ResultSet) -> T): T = contended {
        c.prepareStatement(sql).use { it.bind(); it.executeQuery().use(result) }
    }
    private fun exec(c: Connection, sql: String, bind: PreparedStatement.() -> Unit) = contended {
        c.prepareStatement(sql).use { it.bind(); if (it.executeUpdate() != 1) unavailable() }
    }
    private fun <T> contended(work: () -> T): T = try { work() } catch (e: SQLException) {
        if (e.sqlState != "55P03") throw e
        throw SQLException("Moderation state contended", "40001")
    }
    private fun same(expected: Connection, actual: Connection) { if (expected !== actual) unavailable() }
    private fun instant(r: ResultSet, name: String) = r.getObject(name, OffsetDateTime::class.java)?.toInstant() ?: unavailable()
    private fun time(value: Instant) = OffsetDateTime.ofInstant(value, ZoneOffset.UTC)
    private fun document(value: String, bound: Int): JsonObject = Json.parseToJsonElement(
        WireDocument.decode(value.encodeToByteArray(throwOnInvalidSequence = true), WireLimits(bound, 32)).encodeUtf8().decodeToString()).jsonObject
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    // The captured source digest uses the publication/report target canonical format,
    // not PostgreSQL's jsonb key ordering. Exact evidence/receipt byte hashes above do
    // not use this normalization and retain their original storage semantics.
    private fun canonicalSource(value: JsonElement): String = when (value) {
        is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { (key, item) -> "${JsonPrimitive(key)}:${canonicalSource(item)}" }
        is JsonArray -> value.joinToString(",", "[", "]", transform = ::canonicalSource)
        is JsonPrimitive -> if (value.isString || value == JsonNull || value.booleanOrNull != null) value.toString()
            else BigDecimal(value.content).stripTrailingZeros().toString()
    }
    private fun uuid(value: String) = try { UUID.fromString(value).toString() == value } catch (_: Exception) { false }
    private fun rank(value: String) = when (value) { "urgent" -> 0; "high" -> 1; "normal" -> 2; else -> unavailable() }
    override fun toString() = "SupabaseStaffModerationStore(<redacted>)"
    companion object {
        private const val LIST = "adminListReports"
        private const val GET = "adminGetCase"
        private const val CLAIM = "adminClaimReport"
        private const val DISMISS = "adminActOnReport"
        private const val AUDIT = "adminListAudit"
        private const val PRIORITY = "CASE c.priority WHEN 'urgent' THEN 0 WHEN 'high' THEN 1 ELSE 2 END"
        private const val MESSAGE_FINGERPRINT = "jsonb_build_object('id',id,'threadId',thread_id,'ownerId',sender_user_id,'version',1," +
            "'kind',kind,'text',text,'createdAt',to_char(created_at AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')," +
            "'recipeRequestId',recipe_request_id,'recipeVersionId',recipe_version_id)"
        private const val SELECT = "SELECT r.id report_id,r.reporter_user_id,r.case_id linked_case_id,r.target_type,r.target_id,r.version report_version," +
            "r.created_at report_created,r.updated_at report_updated,r.reason,r.status report_status,c.id case_id,c.report_id linked_report_id," +
            "c.target_type case_target_type,c.target_id case_target_id,c.version case_version,c.priority,c.status case_status,c.assignee_staff_id," +
            "c.reason_code,c.action case_action,c.created_at case_created,c.updated_at case_updated FROM safety.reports r " +
            "JOIN safety.moderation_cases c ON c.environment=r.environment AND c.id=r.case_id"
        private const val AUDIT_SELECT = "SELECT * FROM (" +
            "SELECT a.id,1::bigint version,a.created_at,a.actor_id," +
            "CASE a.purpose WHEN 'queue-list' THEN 'adminListReports' WHEN 'case-review' THEN 'adminGetCase' " +
            "WHEN 'claim-receipt' THEN 'adminClaimReport' WHEN 'dismiss-receipt' THEN 'adminActOnReport' " +
            "WHEN 'remove-receipt' THEN 'adminActOnReport' ELSE 'adminListAudit' END operation_id," +
            "'moderationAccess'::varchar target_type,a.id::text target_id,a.purpose reason,a.trace_id,0 source_rank " +
            "FROM safety.moderation_access_audit a WHERE a.environment=? UNION ALL " +
            "SELECT m.id,m.case_version,m.created_at,m.actor_id,m.operation_id,c.target_type,c.target_id::text," +
            "m.reason_code,m.trace_id,1 source_rank FROM safety.moderation_actions m JOIN safety.moderation_cases c " +
            "ON c.environment=m.environment AND c.id=m.case_id WHERE m.environment=?) feedme_staff_audit"
        private val validator by lazy { ContractBodyValidator.bundled() }
        private fun fail(code: StaffModerationFailureCode): Nothing = throw StaffModerationFailure(code)
        private fun invalid(): Nothing = fail(StaffModerationFailureCode.INPUT_INVALID)
        private fun missing(): Nothing = fail(StaffModerationFailureCode.CASE_UNAVAILABLE)
        private fun conflict(): Nothing = fail(StaffModerationFailureCode.CASE_CONFLICT)
        private fun unavailable(): Nothing = fail(StaffModerationFailureCode.STORAGE_UNAVAILABLE)
    }
}
