package com.feedme.server.planning

import com.feedme.server.db.*
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/**
 * Caller-transaction storage ONLY. This kernel neither authenticates a principal nor grants
 * source, private-input, catalog, substitution or copy authority. The real service must hold
 * those locks, use DurableCommands.executeInTransaction, and revalidate them before completing
 * the transaction. No HTTP/runtime path instantiates this class yet.
 *
 * Fresh: lockParent inside authorizeNew/mutate -> materialize with the actual catalog view ->
 * insert -> return Written.reply from mutate -> captureCompleted after DurableCommands returns.
 * Replay: call replay from authorizeReplay; never rerun the selector or append an event.
 * Both: keep Pending private, perform final authority, revalidate, then checkAt with the owner's
 * last accepted database time. Only the sole outer transaction's successful commit permits a
 * response. A Pending, a verified-principal value, or matching hashes are NOT authority.
 */
internal class DerivedPlanStore(private val outbox: OutboxStore) {
    internal class LockedParent internal constructor(val parent: DerivedPlanParent,
        private val insertAction: (Connection, DerivedPlanMaterial) -> Written) {
        fun insert(connection: Connection, material: DerivedPlanMaterial): Written = insertAction(connection, material)
        override fun toString() = "DerivedPlanLockedParent(<redacted>)"
    }

    internal class Written internal constructor(val reply: StoredReply,
        private val capture: (Connection, VerifiedPlanningPrincipal) -> Pending) {
        /** Receipt must already be completed by the original DurableCommands invocation. */
        fun captureCompleted(connection: Connection, actor: VerifiedPlanningPrincipal): Pending = capture(connection, actor)
        override fun toString() = "DerivedPlanWritten(<redacted>)"
    }

    internal class Pending internal constructor(val record: DerivedPlanStoredRecord,
        private val completion: (Connection, VerifiedPlanningPrincipal) -> Unit,
        private val timeFence: (Connection, VerifiedPlanningPrincipal, Instant) -> Unit) {
        val reply get() = record.reply
        fun revalidate(connection: Connection, actor: VerifiedPlanningPrincipal) = completion(connection, actor)
        fun checkAt(connection: Connection, actor: VerifiedPlanningPrincipal, acceptedAt: Instant) = timeFence(connection, actor, acceptedAt)
        override fun toString() = "DerivedPlanPending(<redacted>)"
    }

    fun lockParent(c: Connection, command: DerivedPlanCommand): LockedParent = storage {
        val bound = Bound(c, command.principal)
        val admittedReceipt = receipt(c, command)
        requireReceipt(admittedReceipt, command, null)
        val admissionDeadline = instant(admittedReceipt, "expires_at")
        if (!admissionDeadline.isAfter(now(c))) unavailable()
        val parentRows = parentRows(c, command, fresh = true)
        val parent = parentMaterial(parentRows)
        val once = Once()
        LockedParent(parent) { actual, material -> once.run {
            bound.check(actual, command.principal)
            if (material.command !== command || material.parent !== parent) unavailable()
            unchanged(actual, command.principal, parentRows)
            if (receipt(actual, command) != admittedReceipt) unavailable()
            val at = now(actual)
            if (!admissionDeadline.isAfter(at)) unavailable()
            live(parentRows.expires, at)
            if (material.createdAt > at || material.createdAt < instant(parentRows.plan, "created_at")) unavailable()
            live(material.expiresAt, at)
            val requestId = UUID.randomUUID()
            if (requestId == uuid(parentRows.request, "id") || material.planId == parent.id) unavailable()
            insertRequest(actual, command, requestId, material)
            insertPlan(actual, command, requestId, material)
            val event = EventDraft(UUID.randomUUID(), "planning.plan.created.v1", 1, "plan", material.planId, 1,
                "planning", command.key.toString(), command.key, buildJsonObject {
                    put("principalId", command.principal.principalId.toString()); put("planId", material.planId.toString())
                    material.recipeVersionId?.let { put("recipeVersionId", it.toString()) }
                    put("status", material.status)
                    put("rankingVersion", parse(material.contextText).getValue("policy").jsonObject.getValue("version"))
                }, owner = EventOwner.principal(command.principal.environment, command.principal.kind, command.principal.principalId))
            outbox.append(actual, event)
            val child = planImage(actual, command.principal, material.planId)
            val request = requestImage(actual, command.principal, requestId)
            val record = record(child, request, command)
            if (record.snapshotText != material.snapshotText || record.contextText != material.contextText ||
                record.proofText != material.proofText) unavailable()
            val eventImage = eventImage(actual, event.eventId)
            requireNewEvent(eventImage, event)
            unchanged(actual, command.principal, parentRows)
            bound.check(actual, command.principal)
            val captureOnce = Once()
            Written(material.reply) { same, actor -> captureOnce.run {
                bound.check(same, actor)
                val captured = Captured(parentRows, child, request, receipt(same, command), eventImage, event.eventId)
                requireReceipt(captured.receipt, command, record.reply)
                val completionFields = setOf("state", "response_code", "response_json", "response_etag", "updated_at", "expires_at")
                if (captured.receipt.filterKeys { it !in completionFields } != admittedReceipt.filterKeys { it !in completionFields }) unavailable()
                val pending = pending(bound, command, record, captured, parentRows.expires, admissionDeadline)
                pending.revalidate(same, actor)
                pending
            } }
        } }
    }

    /** Caller must reauthorize this exact retained proposal, not grant access from a cache hit.
     * Parent creation-time expiry is not renewed or required for historical replay; the child's
     * independent lifetime and the original command receipt must still be live. */
    fun replay(c: Connection, command: DerivedPlanCommand, cached: StoredReply): Pending = storage {
        val bound = Bound(c, command.principal)
        val receipt = receipt(c, command)
        requireReceipt(receipt, command, cached)
        val request = image(c, "planning.plan_requests",
            "environment=? AND actor_kind=? AND principal_id=? AND derived_operation=? AND derived_command_key=?") {
            owner(command.principal); setString(4, command.operationId); setObject(5, command.key)
        }
        requireOwner(request, command.principal)
        val child = planImage(c, command.principal, uuid(request, "current_plan_id"))
        val record = record(child, request, command)
        if (!sameReply(cached, record.reply)) unavailable()
        val parent = parentRows(c, command, fresh = false)
        requireParentContext(record, parent)
        // Delivery metadata can legitimately change after commit; replay does not recreate,
        // lock or demand an undelivered historical event.
        pending(bound, command, record, Captured(parent, child, request, receipt, null, null), null).also {
            it.revalidate(c, command.principal)
        }
    }

    private fun pending(bound: Bound, command: DerivedPlanCommand, record: DerivedPlanStoredRecord,
        expected: Captured, parentDeadline: Instant?, admissionDeadline: Instant? = null): Pending {
        val gate = Gate()
        val clock = CompletionClock(bound.connection, command.principal, parentDeadline, record.expiresAt,
            instant(expected.receipt, "expires_at").let { if (admissionDeadline != null && admissionDeadline < it) admissionDeadline else it })
        return Pending(record, { c, actor -> gate.run {
            bound.check(c, actor)
            unchanged(c, actor, expected.parent)
            if (planImage(c, actor, record.planId) != expected.child ||
                requestImage(c, actor, uuid(expected.request, "id")) != expected.request ||
                receipt(c, command) != expected.receipt) unavailable()
            if (expected.eventId != null && eventImage(c, expected.eventId) != expected.event) unavailable()
            bound.check(c, actor)
            // Last SQL here; the owner may still perform its own final authority before checkAt.
            val at = now(c)
            bound.local(c, actor); clock.observe(c, actor, at)
        } }, { c, actor, at -> gate.run {
            bound.local(c, actor); clock.checkAt(c, actor, at)
        } })
    }

    /** Rejection-only clock. No SQL (including queryful transactionIsolation) is allowed here. */
    internal class CompletionClock(private val connection: Connection, private val actor: VerifiedPlanningPrincipal,
        private val parentExpires: Instant?, private val childExpires: Instant, private val receiptExpires: Instant) {
        private val thread = Thread.currentThread()
        private val gate = Gate()
        private var observed: Instant? = null
        fun observe(c: Connection, actual: VerifiedPlanningPrincipal, at: Instant) = gate.run {
            local(c, actual); validate(at); observed = at
        }
        fun checkAt(c: Connection, actual: VerifiedPlanningPrincipal, at: Instant) = gate.run {
            local(c, actual); if (observed == null) unavailable(); validate(at); observed = at
        }
        private fun local(c: Connection, actual: VerifiedPlanningPrincipal) {
            current()
            if (Thread.currentThread() !== thread || c !== connection || actual !== actor || c.isClosed || c.autoCommit) unavailable()
        }
        private fun validate(at: Instant) {
            if (observed?.let { at < it } == true) unavailable()
            parentExpires?.let { live(it, at) }; live(childExpires, at)
            if (!receiptExpires.isAfter(at)) unavailable()
        }
    }

    private fun parentRows(c: Connection, command: DerivedPlanCommand, fresh: Boolean): ParentRows {
        val plan = planImage(c, command.principal, command.parentId)
        val request = requestImage(c, command.principal, uuid(plan, "request_id"))
        if (integer(plan, "storage_format") !in 1L..6L || plan["storage_format"] != request["storage_format"]) unavailable()
        val parent = parentMaterial(ParentRows(plan, request, instant(request, "expires_at")))
        if (parent.id != command.parentId || parent.version.toString() != integer(plan, "version").toString()) unavailable()
        if (parent.version != command.originalIfMatch.removeSurrounding("\"").toBigInteger())
            throw PlanningServiceFailure(PlanningFailureCode.VERSION_CONFLICT)
        val body = parse(parent.snapshotText)
        if (plan["status"] != JsonPrimitive("ready") || plan["recipe_version_id"] != JsonPrimitive(parent.recipeVersionId.toString()) ||
            body["parentPlanId"].orNull() != plan["parent_plan_id"].orNull() ||
            Instant.parse(text(body, "createdAt")) != instant(plan, "created_at")) unavailable()
        if (integer(plan, "storage_format") == 3L) {
            val original = DerivedPlanCommand(command.principal, text(request, "derived_operation"), uuid(request, "derived_command_key"),
                uuid(request, "derived_parent_plan_id"), text(request, "derived_if_match"), wire(parse(text(request, "request_text"))))
            record(plan, request, original)
        } else if (integer(plan, "storage_format") in 4L..6L) {
            // A genuine no-parent root can itself become an explicitly chosen parent.
            // Validate its own complete immutable row/context binding, never invent one.
            RootRecipePlanRows.decode(command.principal, plan, request)
        }
        val expires = instant(request, "expires_at")
        if (fresh) live(expires, now(c))
        return ParentRows(plan, request, expires)
    }

    private fun parentMaterial(rows: ParentRows): DerivedPlanParent {
        val requestText = text(rows.request, "request_text")
        val effective = if (integer(rows.plan, "storage_format") == 3L) {
            val contextText = text(rows.request, "evidence_text")
            if (digest(contextText.toByteArray(Charsets.UTF_8)) != text(rows.request, "evidence_hash")) unavailable()
            text(parse(contextText), "effectiveRequestText")
        } else if (integer(rows.plan, "storage_format") in 5L..6L) {
            val original = RootRecipePlanRecord.decode(text(rows.plan, "snapshot_text"), text(rows.plan, "snapshot_hash"),
                text(rows.request, "evidence_text"), text(rows.request, "evidence_hash"), text(rows.plan, "proof_text"), text(rows.plan, "proof_hash"))
            rootParentPlanningRequest(requestText, original)
        } else requestText
        return DerivedPlanParent.fromStored(text(rows.plan, "snapshot_text"), text(rows.plan, "snapshot_hash"),
            text(rows.plan, "proof_text"), text(rows.plan, "proof_hash"), requestText, text(rows.request, "request_hash"), effective)
    }

    private fun record(plan: JsonObject, request: JsonObject, command: DerivedPlanCommand): DerivedPlanStoredRecord {
        requireOwner(plan, command.principal); requireOwner(request, command.principal)
        if (integer(plan, "storage_format") != 3L || integer(request, "storage_format") != 3L ||
            integer(plan, "version") != 1L || integer(request, "version") != 1L ||
            uuid(plan, "request_id") != uuid(request, "id") || uuid(plan, "id") != uuid(request, "current_plan_id") ||
            text(request, "derived_operation") != command.operationId || uuid(request, "derived_command_key") != command.key ||
            text(request, "derived_request_sha256") != command.identity.requestHash || text(request, "derived_if_match") != command.originalIfMatch ||
            uuid(request, "derived_parent_plan_id") != command.parentId ||
            integer(request, "derived_parent_version").toBigInteger() != command.originalIfMatch.removeSurrounding("\"").toBigInteger() ||
            semantic(parse(text(request, "request_text"))) != semantic(json(command.body)) ||
            text(request, "request_hash") != digest(text(request, "request_text").toByteArray(Charsets.UTF_8)) || plan["next_cursor_hash"] != JsonNull ||
            listOf("ordered_ids", "manifest_id", "create_command_key", "command_request_sha256").any { request[it] != JsonNull }) unavailable()
        val record = DerivedPlanStoredRecord.decode(text(plan, "snapshot_text"), text(plan, "snapshot_hash"),
            text(request, "evidence_text"), text(request, "evidence_hash"), text(plan, "proof_text"), text(plan, "proof_hash"))
        val context = parse(record.contextText)
        // Incoming retries use semantic JSON, but the database must retain the original
        // submitted bytes pinned by this context. Matching meaning cannot excuse rewriting it.
        if (context["requestText"] != request["request_text"] || context["requestSha256"] != request["request_hash"]) unavailable()
        if (record.planId != uuid(plan, "id") || record.parentId != command.parentId || uuid(plan, "parent_plan_id") != command.parentId ||
            plan["recipe_version_id"].orNull() != record.recipeVersionId?.let { JsonPrimitive(it.toString()) }.orNull() ||
            text(plan, "status") != record.status || integer(plan, "position") != if (record.status == "ready") 0L else -1L) unavailable()
        if (record.createdAt != instant(plan, "created_at") || record.createdAt != instant(request, "created_at") ||
            record.expiresAt != instant(request, "expires_at") || record.expiresAt != instant(request, "cursor_expires_at") ||
            context["owner"] != buildJsonObject { put("environment", command.principal.environment)
                put("actorKind", command.principal.kind.name.lowercase()); put("principalId", command.principal.principalId.toString()) } ||
            text(context, "commandKey") != command.key.toString() || text(context, "operationId") != command.operationId ||
            text(context, "commandRequestHash") != command.identity.requestHash ||
            semantic(parse(text(request, "policy_text"))) != semantic(context.getValue("policy"))) unavailable()
        return record
    }

    private fun requireParentContext(record: DerivedPlanStoredRecord, rows: ParentRows) {
        val context = parse(record.contextText)
        for ((contextField, rowField) in listOf("parentSnapshotText" to "snapshot_text", "parentSnapshotHash" to "snapshot_hash",
            "parentProofText" to "proof_text", "parentProofHash" to "proof_hash")) if (context[contextField] != rows.plan[rowField]) unavailable()
        for ((contextField, rowField) in listOf("parentRequestText" to "request_text", "parentRequestHash" to "request_hash"))
            if (context[contextField] != rows.request[rowField]) unavailable()
        val actualParent = parentMaterial(rows)
        if (text(context, "parentPlanningRequestText") != actualParent.planningRequestText ||
            text(context, "parentPlanningRequestHash") != actualParent.planningRequestHash) unavailable()
    }

    private fun insertRequest(c: Connection, command: DerivedPlanCommand, requestId: UUID, m: DerivedPlanMaterial) = exec(c, """
        INSERT INTO planning.plan_requests(environment,actor_kind,principal_id,id,request_text,request_hash,evidence_text,evidence_hash,
            policy_text,current_plan_id,version,created_at,expires_at,cursor_expires_at,storage_format,
            derived_operation,derived_command_key,derived_request_sha256,derived_if_match,derived_parent_plan_id,derived_parent_version)
        VALUES(?,?,?,?,?,?,?,?,?,?,1,?,?,?,3,?,?,?,?,?,?)
    """.trimIndent()) {
        owner(command.principal); setObject(4, requestId); setString(5, command.body.encodeUtf8().decodeToString()); setString(6, command.bodySha256)
        setString(7, m.contextText); setString(8, m.contextHash); setString(9, parse(m.contextText).getValue("policy").toString())
        setObject(10, m.planId); setObject(11, time(m.createdAt)); setObject(12, time(m.expiresAt)); setObject(13, time(m.expiresAt))
        setString(14, command.operationId); setObject(15, command.key); setString(16, command.identity.requestHash)
        setString(17, command.originalIfMatch); setObject(18, command.parentId); setLong(19, m.parent.version.longValueExact())
    }
    private fun insertPlan(c: Connection, command: DerivedPlanCommand, requestId: UUID, m: DerivedPlanMaterial) = exec(c, """
        INSERT INTO planning.plans(environment,actor_kind,principal_id,id,request_id,parent_plan_id,version,position,
            recipe_version_id,status,snapshot_text,snapshot_hash,proof_text,proof_hash,next_cursor_hash,created_at,storage_format)
        VALUES(?,?,?,?,?,?,1,?,?,?,?,?,?,?,NULL,?,3)
    """.trimIndent()) {
        owner(command.principal); setObject(4, m.planId); setObject(5, requestId); setObject(6, command.parentId)
        setLong(7, if (m.status == "ready") 0 else -1); setObject(8, m.recipeVersionId); setString(9, m.status)
        setString(10, m.snapshotText); setString(11, m.snapshotHash); setString(12, m.proofText); setString(13, m.proofHash)
        setObject(14, time(m.createdAt))
    }

    private fun requireReceipt(value: JsonObject, command: DerivedPlanCommand, reply: StoredReply?) {
        if (text(value, "principal_scope") != command.identity.scope.storageKey || text(value, "operation_id") != command.operationId ||
            uuid(value, "key") != command.key || text(value, "request_hash") != command.identity.requestHash ||
            text(value, "state") != if (reply == null) "pending" else "completed") unavailable()
        if (reply == null) {
            if (listOf("response_code", "response_json", "response_etag", "tombstoned_at").any { value[it] != JsonNull }) unavailable()
        } else if (integer(value, "response_code") != reply.status.toLong() ||
            semantic(value.getValue("response_json")) != semantic(reply.body ?: JsonNull) || value["response_etag"] != JsonPrimitive(reply.etag) ||
            value["tombstoned_at"] != JsonNull) unavailable()
    }

    private fun requireNewEvent(value: JsonObject, event: EventDraft) {
        if (uuid(value, "event_id") != event.eventId || text(value, "event_type") != event.eventType ||
            integer(value, "schema_version") != event.schemaVersion.toLong() || text(value, "aggregate_type") != event.aggregateType ||
            uuid(value, "aggregate_id") != event.aggregateId || integer(value, "aggregate_version") != event.aggregateVersion ||
            text(value, "producer") != event.producer || text(value, "correlation_id") != event.correlationId ||
            uuid(value, "causation_id") != event.causationId || semantic(value.getValue("payload")) != semantic(event.data) ||
            integer(value, "attempts") != 0L || listOf("published_at", "lease_token", "lease_expires_at", "quarantined_at", "last_failure_code")
                .any { value[it] != JsonNull }) unavailable()
        instant(value, "occurred_at"); instant(value, "available_at")
    }

    private class ParentRows(val plan: JsonObject, val request: JsonObject, val expires: Instant)
    private class Captured(val parent: ParentRows, val child: JsonObject, val request: JsonObject,
        val receipt: JsonObject, val event: JsonObject?, val eventId: UUID?)
    private fun unchanged(c: Connection, actor: VerifiedPlanningPrincipal, rows: ParentRows) {
        if (planImage(c, actor, uuid(rows.plan, "id")) != rows.plan || requestImage(c, actor, uuid(rows.request, "id")) != rows.request) unavailable()
    }
    private class Bound(val connection: Connection, private val actor: VerifiedPlanningPrincipal) {
        private val thread = Thread.currentThread()
        private val transaction: Long
        init {
            local(connection, actor)
            if (connection.transactionIsolation != Connection.TRANSACTION_READ_COMMITTED) unavailable()
            transaction = transactionId(connection)
        }
        fun check(c: Connection, actual: VerifiedPlanningPrincipal) {
            local(c, actual); if (transactionId(c) != transaction) unavailable()
        }
        fun local(c: Connection, actual: VerifiedPlanningPrincipal) {
            current()
            if (Thread.currentThread() !== thread || c !== connection || actual !== actor || c.isClosed || c.autoCommit || c.isReadOnly) unavailable()
        }
    }

    /** All failed/misbound/concurrent uses permanently refuse this invocation. */
    private class Gate {
        private val refused = AtomicBoolean(false)
        private val active = AtomicBoolean(false)
        fun <T> run(action: () -> T): T {
            if (refused.get() || !active.compareAndSet(false, true)) { refused.set(true); unavailable() }
            try { return storage { action().also { if (refused.get()) unavailable() } } }
            catch (failure: Throwable) { refused.set(true); throw failure }
            finally { active.set(false) }
        }
    }
    private class Once {
        private val used = AtomicBoolean(false)
        fun <T> run(action: () -> T): T { if (!used.compareAndSet(false, true)) unavailable(); return storage(action) }
    }

    private fun planImage(c: Connection, actor: VerifiedPlanningPrincipal, id: UUID) = ownedImage(c, actor, id, "planning.plans")
    private fun requestImage(c: Connection, actor: VerifiedPlanningPrincipal, id: UUID) = ownedImage(c, actor, id, "planning.plan_requests")
    private fun ownedImage(c: Connection, actor: VerifiedPlanningPrincipal, id: UUID, table: String) =
        image(c, table, "environment=? AND actor_kind=? AND principal_id=? AND id=?") { owner(actor); setObject(4, id) }
            .also { requireOwner(it, actor); if (uuid(it, "id") != id) unavailable() }
    private fun receipt(c: Connection, command: DerivedPlanCommand) = image(c, "platform.idempotency", "principal_scope=? AND operation_id=? AND key=?") {
        setString(1, command.identity.scope.storageKey); setString(2, command.operationId); setObject(3, command.key)
    }
    private fun eventImage(c: Connection, id: UUID) = image(c, "platform.outbox", "event_id=?") { setObject(1, id) }
    private fun requireOwner(value: JsonObject, actor: VerifiedPlanningPrincipal) {
        if (text(value, "environment") != actor.environment || text(value, "actor_kind") != actor.kind.name.lowercase() ||
            uuid(value, "principal_id") != actor.principalId) unavailable()
    }
    // SQL identifiers are private source constants, not caller inputs.
    private fun image(c: Connection, table: String, where: String, bind: PreparedStatement.() -> Unit): JsonObject =
        c.prepareStatement("SELECT to_jsonb(r)::text FROM $table r WHERE $where FOR SHARE").use { s ->
            current(); s.bind(); s.executeQuery().use { r ->
                if (!r.next()) throw PlanningServiceFailure(PlanningFailureCode.PLAN_UNAVAILABLE)
                val value = parse(r.getString(1)); if (r.next()) unavailable(); current(); value
            }
        }
    private fun exec(c: Connection, sql: String, bind: PreparedStatement.() -> Unit) = c.prepareStatement(sql).use { s ->
        current(); s.bind(); if (s.executeUpdate() != 1) unavailable(); current()
    }
    private fun PreparedStatement.owner(actor: VerifiedPlanningPrincipal) {
        setString(1, actor.environment); setString(2, actor.kind.name.lowercase()); setObject(3, actor.principalId)
    }

    companion object {
        private fun transactionId(c: Connection) = c.createStatement().use { s -> s.executeQuery("SELECT txid_current()").use { r ->
            if (!r.next()) unavailable(); val id = r.getLong(1); if (r.next()) unavailable(); id
        } }
        private fun now(c: Connection) = c.createStatement().use { s -> s.executeQuery("SELECT clock_timestamp()").use { r ->
            if (!r.next()) unavailable(); val at = r.getObject(1, OffsetDateTime::class.java).toInstant(); if (r.next()) unavailable(); at
        } }
        private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject
        private fun text(value: JsonObject, field: String) = value.getValue(field).jsonPrimitive.let { if (!it.isString) unavailable(); it.content }
        private fun uuid(value: JsonObject, field: String) = UUID.fromString(text(value, field))
        private fun integer(value: JsonObject, field: String) = value.getValue(field).jsonPrimitive.let {
            if (it.isString) unavailable(); BigDecimal(it.content).longValueExact()
        }
        private fun instant(value: JsonObject, field: String) = OffsetDateTime.parse(text(value, field)).toInstant()
        private fun time(at: Instant) = OffsetDateTime.ofInstant(at, ZoneOffset.UTC)
        private fun JsonElement?.orNull() = this ?: JsonNull
        private fun live(deadline: Instant, at: Instant) { if (!deadline.isAfter(at)) throw PlanningServiceFailure(PlanningFailureCode.PLAN_EXPIRED) }
        private fun sameReply(a: StoredReply, b: StoredReply) = a.status == b.status && a.etag == b.etag && semantic(a.body ?: JsonNull) == semantic(b.body ?: JsonNull)
        private fun semantic(value: JsonElement): String = when (value) {
            is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { (k, v) -> "${JsonPrimitive(k)}:${semantic(v)}" }
            is JsonArray -> value.joinToString(",", "[", "]", transform = ::semantic)
            is JsonPrimitive -> if (value.isString || value == JsonNull || value.booleanOrNull != null) value.toString()
                else BigDecimal(value.content).stripTrailingZeros().toString()
        }
        private fun current() { if (Thread.currentThread().isInterrupted) throw InterruptedException("Derived Plan operation interrupted") }
        private fun unavailable(): Nothing = throw PlanningServiceFailure(PlanningFailureCode.STORAGE_UNAVAILABLE)
        private inline fun <T> storage(action: () -> T): T = try { current(); action() }
            catch (failure: PlanningServiceFailure) { throw failure }
            catch (failure: CommitOutcomeUnknown) { throw failure }
            catch (failure: CancellationException) { throw failure }
            catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
            catch (failure: SQLException) { current(); if (failure.sqlState in setOf("40001", "40P01")) throw failure; unavailable() }
            catch (_: Exception) { current(); unavailable() }
    }
}
