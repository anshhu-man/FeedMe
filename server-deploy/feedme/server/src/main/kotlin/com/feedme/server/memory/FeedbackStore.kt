package com.feedme.server.memory

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.guest.GuestSessionFailure
import com.feedme.server.catalog.IngredientCatalogFailure
import com.feedme.server.catalog.RecipeCatalogFailure
import com.feedme.server.planning.PlanningServiceFailure
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Caller-owned transaction only. Raw explicit signals and one registered redacted event,
 * not a memory projection, food exclusion, recipe copy, completion or automatic Make Again.
 * Updates replace the full signal set, retaining the SAME immutable context/provenance.
 * Superseded response receipts are tombstoned immediately; prior keys return ReceiptExpired.
 * Delete clears raw row material and all earlier response bodies in the SAME transaction.
 * The real token owner must revalidate Pending after final authority and supply its last
 * accepted DB time to checkAt. No method here starts, retries or commits a transaction. */
internal class FeedbackStore(val environment: String, private val transactions: PgTransactions,
    private val authority: FeedbackAuthority, val policy: FeedbackServicePolicy) {
    private val commands = DurableCommands(transactions)
    private val outbox = OutboxStore(transactions)
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }
    fun isBoundTo(expectedEnvironment: String, expectedTransactions: PgTransactions): Boolean =
        environment == expectedEnvironment && transactions === expectedTransactions

    fun createFeedback(c: Connection, actor: VerifiedFeedbackPrincipal, key: UUID, body: JsonObject): Pending<CommandResult> = safe {
        val input = request("createFeedback", body)
        val context = context(input)
        command(c, actor, "createFeedback", key, null, null, input,
            replay = { db, cached -> requireCurrentReply(db, actor, cached, null, "createFeedback") }) { db, trace ->
            context.cookSessionId?.let { cook ->
                db.prepareStatement("SELECT id FROM memory.feedback WHERE environment=? AND actor_kind=? AND principal_id=? AND cook_session_id=? AND NOT deleted FOR UPDATE").use {
                    it.owner(actor); it.setObject(4, cook)
                    it.executeQuery().use { rows -> if (rows.next()) fail(FeedbackFailureCode.FEEDBACK_CONFLICT) }
                }
            }
            val evidence = authority.authorizeTarget(db, actor, context)
            trace.guard.check(db, actor)
            evidence.revalidate(db, actor, context); trace.guard.check(db, actor)
            val provenance = provenance(evidence.snapshot)
            trace.fresh = Fresh(context, evidence, provenance)
            val at = now(db); val id = UUID.randomUUID()
            val snapshot = snapshot(id, 1, context, input, at, at)
            val response = reply("createFeedback", 201, snapshot, 1)
            db.prepareStatement("INSERT INTO memory.feedback(environment,actor_kind,principal_id,id,version,cook_session_id," +
                "context_text,context_sha256,provenance_text,provenance_sha256,snapshot,created_at,updated_at) VALUES(?,?,?,?,1,?,?,?,?,?,?::jsonb,?,?)").use {
                it.owner(actor); it.setObject(4, id); it.setObject(5, context.cookSessionId)
                it.setString(6, context.exactDocument); it.setString(7, context.sha256); it.setString(8, provenance)
                it.setString(9, feedbackSha(provenance)); it.setString(10, snapshot.toString()); it.instant(11, at); it.instant(12, at)
                check(it.executeUpdate() == 1)
            }
            val expected = Row(id, 1, context, provenance, snapshot, false, null, at, at)
            requireSame(expected, row(db, actor, id))
            trace.event = appendEvent(db, actor, key, id, 1, "created")
            response
        }
    }

    fun updateFeedback(c: Connection, actor: VerifiedFeedbackPrincipal, key: UUID, id: UUID,
        ifMatch: String, body: JsonObject): Pending<CommandResult> = safe {
        val expectedVersion = version(ifMatch)
        val input = request("updateFeedback", body); val context = context(input)
        command(c, actor, "updateFeedback", key, id, ifMatch, input,
            replay = { db, cached -> requireCurrentReply(db, actor, cached, id, "updateFeedback") }) { db, trace ->
            val previous = row(db, actor, id)
            if (previous.deleted) fail(FeedbackFailureCode.FEEDBACK_UNAVAILABLE)
            if (previous.version != expectedVersion) fail(FeedbackFailureCode.VERSION_CONFLICT)
            if (previous.context?.exactDocument != context.exactDocument) fail(FeedbackFailureCode.INPUT_INVALID)
            val nextVersion = increment(previous.version); val at = now(db)
            if (at < previous.updated) fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
            val changed = snapshot(id, nextVersion, context, input, previous.created, at)
            val response = reply("updateFeedback", 200, changed, nextVersion)
            db.prepareStatement("UPDATE memory.feedback SET version=?,snapshot=?::jsonb,updated_at=? " +
                "WHERE environment=? AND actor_kind=? AND principal_id=? AND id=? AND version=? AND NOT deleted").use {
                it.setLong(1, nextVersion); it.setString(2, changed.toString()); it.instant(3, at); it.owner(actor, 4)
                it.setObject(7, id); it.setLong(8, previous.version); check(it.executeUpdate() == 1)
            }
            requireSame(Row(id, nextVersion, previous.context, previous.provenance, changed, false, null, previous.created, at), row(db, actor, id))
            trace.event = appendEvent(db, actor, key, id, nextVersion, "updated")
            response
        }
    }

    fun deleteFeedback(c: Connection, actor: VerifiedFeedbackPrincipal, key: UUID, id: UUID,
        ifMatch: String): Pending<CommandResult> = safe {
        val expectedVersion = version(ifMatch)
        command(c, actor, "deleteFeedback", key, id, ifMatch, null, replay = { db, cached ->
            val current = row(db, actor, id)
            if (!current.deleted || current.deletionKey != key || current.version != increment(expectedVersion) ||
                cached.status != 204 || cached.body != null || cached.etag != null) fail(FeedbackFailureCode.VERSION_CONFLICT)
        }) { db, trace ->
            val previous = row(db, actor, id)
            if (previous.deleted) fail(FeedbackFailureCode.FEEDBACK_UNAVAILABLE)
            if (previous.version != expectedVersion) fail(FeedbackFailureCode.VERSION_CONFLICT)
            val nextVersion = increment(previous.version); val at = now(db)
            if (at < previous.updated) fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
            db.prepareStatement("UPDATE memory.feedback SET version=?,deleted=true,deletion_key=?,updated_at=?,cook_session_id=NULL," +
                "context_text=NULL,context_sha256=NULL,provenance_text=NULL,provenance_sha256=NULL,snapshot=NULL " +
                "WHERE environment=? AND actor_kind=? AND principal_id=? AND id=? AND version=? AND NOT deleted").use {
                it.setLong(1, nextVersion); it.setObject(2, key); it.instant(3, at); it.owner(actor, 4)
                it.setObject(7, id); it.setLong(8, previous.version); check(it.executeUpdate() == 1)
            }
            requireSame(Row(id, nextVersion, null, null, null, true, key, previous.created, at), row(db, actor, id))
            trace.event = appendEvent(db, actor, key, id, nextVersion, "deleted")
            reply("deleteFeedback", 204, null, null)
        }
    }

    private fun command(c: Connection, actor: VerifiedFeedbackPrincipal, operation: String, key: UUID,
        id: UUID?, ifMatch: String?, body: JsonObject?, replay: (Connection, StoredReply) -> Unit,
        mutate: (Connection, Trace) -> StoredReply): Pending<CommandResult> {
        checkActor(actor)
        val trace = Trace(c, actor)
        checkCompatibility(c); trace.guard.check(c, actor)
        val identity = CommandIdentity(PrincipalScope(environment, actor.kind, actor.principalId), operation, key,
            id?.let { mapOf("feedbackId" to it.toString()) } ?: emptyMap(), body = body, ifMatch = ifMatch)
        val result = commands.executeInTransaction(c, identity, { db ->
            authority.lockPrincipal(db, actor); trace.guard.check(db, actor); trace.firstTime = now(db)
        }, {}, { db, cached -> replay(db, cached); trace.guard.check(db, actor) }, { db -> mutate(db, trace) })
        when (result) {
            is CommandResult.Applied -> {
                val feedbackId = id ?: result.reply.body!!.jsonObject.getValue("id").jsonPrimitive.content.let(UUID::fromString)
                val actual = row(c, actor, feedbackId)
                insertLink(c, actor, identity, actual, result.reply)
                compactPriorReceipts(c, actor, feedbackId, actual.version)
                trace.capture(identity, feedbackId, result.reply)
            }
            is CommandResult.Replayed -> trace.capture(identity, id, result.reply)
            else -> trace.captureRefusal(identity, result)
        }
        authority.lockPrincipal(c, actor); trace.guard.check(c, actor)
        trace.revalidate(c, actor)
        return Pending(result, trace)
    }

    private fun requireCurrentReply(c: Connection, actor: VerifiedFeedbackPrincipal, cached: StoredReply,
        requestedId: UUID?, operation: String) {
        val id = requestedId ?: cached.body?.jsonObject?.get("id")?.jsonPrimitive?.content?.let(UUID::fromString)
            ?: fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
        val current = row(c, actor, id)
        if (current.deleted) fail(FeedbackFailureCode.FEEDBACK_UNAVAILABLE)
        val actual = reply(operation, if (operation == "createFeedback") 201 else 200, current.snapshot, current.version)
        if (cached.status != actual.status || cached.etag != actual.etag ||
            feedbackCanonical(cached.body ?: JsonNull) != feedbackCanonical(actual.body ?: JsonNull)) fail(FeedbackFailureCode.VERSION_CONFLICT)
    }

    private fun request(operation: String, body: JsonObject): JsonObject {
        val copy = try { Json.parseToJsonElement(body.toString()).jsonObject } catch (_: Exception) { fail(FeedbackFailureCode.INPUT_INVALID) }
        val bytes = try { copy.toString().encodeToByteArray(throwOnInvalidSequence = true) } catch (_: Exception) { fail(FeedbackFailureCode.INPUT_INVALID) }
        if (validator.validateRequest(operation, bytes, "application/json") != BodyValidationResult.Valid ||
            !hasSignal(copy)) fail(FeedbackFailureCode.INPUT_INVALID)
        context(copy)
        return copy
    }
    private fun context(body: JsonObject): FeedbackTargetContext = try { FeedbackTargetContext.fromInput(body) }
        catch (_: Exception) { fail(FeedbackFailureCode.INPUT_INVALID) }
    private fun hasSignal(body: JsonObject): Boolean = listOf("taste", "effort", "makeAgain").any(body::containsKey) ||
        (body["note"] as? JsonPrimitive)?.let { it.isString && it.content.isNotBlank() } == true
    private fun snapshot(id: UUID, version: Long, context: FeedbackTargetContext, signals: JsonObject,
        created: Instant, updated: Instant) = buildJsonObject {
        put("id", id.toString()); put("version", version); put("createdAt", created.toString()); put("updatedAt", updated.toString())
        context.cookSessionId?.let { put("cookSessionId", it.toString()) }; put("target", context.target)
        for (field in listOf("taste", "effort", "makeAgain", "note")) signals[field]?.let { put(field, it) }
    }
    private fun provenance(value: JsonObject): String {
        if (value.isEmpty()) fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
        val text = feedbackCanonical(value)
        WireDocument.decode(text.encodeToByteArray(throwOnInvalidSequence = true), WireLimits(16_384, 16))
        return text
    }
    private fun reply(operation: String, status: Int, body: JsonObject?, version: Long?): StoredReply {
        val bytes = body?.toString()?.encodeToByteArray(throwOnInvalidSequence = true)
        if ((bytes?.size ?: 0) > policy.maxResponseBytes) fail(FeedbackFailureCode.RESPONSE_TOO_LARGE)
        if (validator.validateResponse(operation, status, bytes, if (body == null) null else "application/json") != BodyValidationResult.Valid)
            fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
        return StoredReply(status, body, version?.let { "\"$it\"" })
    }
    private fun version(value: String): Long = value.takeIf { it.matches(Regex("\"[1-9][0-9]*\"")) }
        ?.removeSurrounding("\"")?.toLongOrNull() ?: fail(FeedbackFailureCode.INPUT_INVALID)
    private fun increment(value: Long): Long = if (value in 1 until Long.MAX_VALUE) value + 1 else fail(FeedbackFailureCode.VERSION_CONFLICT)
    private fun checkActor(actor: VerifiedFeedbackPrincipal) {
        if (actor.environment != environment || actor.kind !in setOf(CommandActor.ACCOUNT, CommandActor.GUEST))
            fail(FeedbackFailureCode.UNAUTHENTICATED)
    }
    private fun row(c: Connection, actor: VerifiedFeedbackPrincipal, id: UUID): Row = c.prepareStatement(
        "SELECT * FROM memory.feedback WHERE environment=? AND actor_kind=? AND principal_id=? AND id=? FOR UPDATE").use {
        it.owner(actor); it.setObject(4, id); it.executeQuery().use { r ->
            if (!r.next()) fail(FeedbackFailureCode.FEEDBACK_UNAVAILABLE)
            if (r.getString("environment") != environment || r.getString("actor_kind") != actor.kind.name.lowercase() ||
                r.getObject("principal_id", UUID::class.java) != actor.principalId || r.getObject("id", UUID::class.java) != id)
                fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
            val version = r.getLong("version"); val deleted = r.getBoolean("deleted")
            val created = r.instant("created_at"); val updated = r.instant("updated_at")
            val contextText = r.getString("context_text"); val contextHash = r.getString("context_sha256")
            val provenanceText = r.getString("provenance_text"); val provenanceHash = r.getString("provenance_sha256")
            val rawSnapshot = r.getString("snapshot"); val cook = r.getObject("cook_session_id", UUID::class.java)
            val deletion = r.getObject("deletion_key", UUID::class.java)
            if (version <= 0 || updated < created) fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
            val context: FeedbackTargetContext?; val body: JsonObject?
            if (deleted) {
                if (listOf(contextText, contextHash, provenanceText, provenanceHash, rawSnapshot, cook).any { it != null } || deletion == null)
                    fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
                context = null; body = null
            } else {
                if (contextText == null || provenanceText == null || rawSnapshot == null || deletion != null ||
                    feedbackSha(contextText) != contextHash || feedbackSha(provenanceText) != provenanceHash)
                    fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
                context = FeedbackTargetContext.decode(contextText)
                if (cook != context.cookSessionId) fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
                if (provenance(Json.parseToJsonElement(provenanceText).jsonObject) != provenanceText) fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
                body = Json.parseToJsonElement(rawSnapshot).jsonObject
                if (validator.validateSchema("Feedback", rawSnapshot.encodeToByteArray()) != BodyValidationResult.Valid || !hasSignal(body) ||
                    body.getValue("id") != JsonPrimitive(id.toString()) || body.getValue("version").jsonPrimitive.content.toBigDecimal().longValueExact() != version ||
                    Instant.parse(body.getValue("createdAt").jsonPrimitive.content) != created || Instant.parse(body.getValue("updatedAt").jsonPrimitive.content) != updated ||
                    FeedbackTargetContext.fromInput(body).exactDocument != context.exactDocument)
                    fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
            }
            val result = Row(id, version, context, provenanceText, body, deleted, deletion, created, updated)
            if (r.next()) fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
            result
        }
    }
    private fun requireSame(expected: Row, actual: Row) {
        if (expected.identity() != actual.identity()) fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
    }
    private fun appendEvent(c: Connection, actor: VerifiedFeedbackPrincipal, key: UUID, id: UUID, version: Long, action: String): EventDraft {
        val event = EventDraft(UUID.randomUUID(), "memory.feedback.changed.v1", 1, "feedback", id, version, "memory",
            UUID.randomUUID().toString(), key, buildJsonObject {
                put("principalId", actor.principalId.toString()); put("feedbackId", id.toString()); put("action", action)
            })
        outbox.append(c, event)
        return event
    }

    private fun insertLink(c: Connection, actor: VerifiedFeedbackPrincipal, command: CommandIdentity, value: Row, reply: StoredReply) {
        c.prepareStatement("INSERT INTO memory.feedback_commands(environment,actor_kind,principal_id,feedback_id,feedback_version," +
            "principal_scope,operation_id,command_key,request_hash,response_sha256) VALUES(?,?,?,?,?,?,?,?,?,?)").use {
            it.owner(actor); it.setObject(4, value.id); it.setLong(5, value.version); it.setString(6, command.scope.storageKey)
            it.setString(7, command.operationId); it.setObject(8, command.key); it.setString(9, command.requestHash)
            it.setString(10, feedbackSha(feedbackCanonical(reply.body ?: JsonNull))); check(it.executeUpdate() == 1)
        }
    }

    /** Principal is already locked by the mandatory authority. Lock earlier receipt keys in
     * deterministic order; stream without a history-size cap that would prevent erasure. */
    private fun compactPriorReceipts(c: Connection, actor: VerifiedFeedbackPrincipal, id: UUID, version: Long) {
        linked(c, actor, id, version) { link, receipt ->
            val linkedVersion = link.getValue("feedback_version").jsonPrimitive.content.toLong()
            checkLinked(actor, id, version, link, receipt, requireCompacted = false)
            if (linkedVersion < version && receipt.getValue("state") != JsonPrimitive("tombstone")) {
                c.prepareStatement("UPDATE platform.idempotency SET state='tombstone',response_code=NULL,response_json=NULL," +
                    "response_etag=NULL,tombstoned_at=clock_timestamp(),updated_at=clock_timestamp() " +
                    "WHERE principal_scope=? AND operation_id=? AND key=? AND state='completed'").use {
                    it.setString(1, link.getValue("principal_scope").jsonPrimitive.content)
                    it.setString(2, link.getValue("operation_id").jsonPrimitive.content)
                    it.setObject(3, UUID.fromString(link.getValue("command_key").jsonPrimitive.content)); check(it.executeUpdate() == 1)
                }
            }
        }
    }

    private fun linked(c: Connection, actor: VerifiedFeedbackPrincipal, id: UUID, version: Long,
        consume: (JsonObject, JsonObject) -> Unit) {
        c.prepareStatement("SELECT to_jsonb(l) AS feedback_link,to_jsonb(i) AS feedback_receipt " +
            "FROM memory.feedback_commands l JOIN platform.idempotency i ON i.principal_scope=l.principal_scope " +
            "AND i.operation_id=l.operation_id AND i.key=l.command_key " +
            "WHERE l.environment=? AND l.actor_kind=? AND l.principal_id=? AND l.feedback_id=? " +
            "ORDER BY l.operation_id,l.command_key FOR UPDATE OF i FOR SHARE OF l").use { statement ->
            statement.owner(actor); statement.setObject(4, id); statement.fetchSize = 32
            statement.executeQuery().use { rows ->
                var count = 0L
                while (rows.next()) {
                    current(); if (count == Long.MAX_VALUE) fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
                    count++
                    consume(Json.parseToJsonElement(rows.getString("feedback_link")).jsonObject,
                        Json.parseToJsonElement(rows.getString("feedback_receipt")).jsonObject)
                }
                // Unique positive versions bounded by current version + this count prove no missing links.
                if (count != version) fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
            }
        }
    }

    private fun checkLinked(actor: VerifiedFeedbackPrincipal, id: UUID, version: Long, link: JsonObject,
        receipt: JsonObject, requireCompacted: Boolean) {
        val linkedVersion = link.getValue("feedback_version").jsonPrimitive.content.toLong()
        val operation = link.getValue("operation_id").jsonPrimitive.content
        val scope = PrincipalScope(environment, actor.kind, actor.principalId).storageKey
        if (link["environment"] != JsonPrimitive(environment) || link["actor_kind"] != JsonPrimitive(actor.kind.name.lowercase()) ||
            link["principal_id"] != JsonPrimitive(actor.principalId.toString()) || link["feedback_id"] != JsonPrimitive(id.toString()) ||
            linkedVersion !in 1..version || link["principal_scope"] != JsonPrimitive(scope) ||
            (linkedVersion == 1L && operation != "createFeedback") ||
            (linkedVersion > 1 && operation !in setOf("updateFeedback", "deleteFeedback")) ||
            (operation == "deleteFeedback" && linkedVersion != version) ||
            receipt["principal_scope"] != link["principal_scope"] || receipt["operation_id"] != link["operation_id"] ||
            receipt["key"] != link["command_key"] || receipt["request_hash"] != link["request_hash"])
            fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
        val state = receipt.getValue("state").jsonPrimitive.content
        if (state == "tombstone") {
            if (listOf("response_code", "response_json", "response_etag").any { receipt[it] != JsonNull } ||
                receipt["tombstoned_at"] == JsonNull) fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
        } else {
            if (state != "completed" || receipt["tombstoned_at"] != JsonNull || (requireCompacted && linkedVersion < version))
                fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
            val body = receipt.getValue("response_json")
            val code = if (operation == "createFeedback") 201 else if (operation == "updateFeedback") 200 else 204
            val etag = if (operation == "deleteFeedback") JsonNull else JsonPrimitive("\"$linkedVersion\"")
            if (receipt["response_code"] != JsonPrimitive(code) || receipt["response_etag"] != etag ||
                link["response_sha256"] != JsonPrimitive(feedbackSha(feedbackCanonical(body)))) fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
            if (operation == "deleteFeedback") {
                if (body != JsonNull) fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
            } else if (body !is JsonObject || body["id"] != JsonPrimitive(id.toString()) ||
                body["version"]?.jsonPrimitive?.content?.toBigDecimal()?.longValueExact() != linkedVersion ||
                validator.validateSchema("Feedback", body.toString().encodeToByteArray()) != BodyValidationResult.Valid || !hasSignal(body))
                fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
        }
    }

    private fun chainImage(c: Connection, actor: VerifiedFeedbackPrincipal, id: UUID, version: Long, identity: CommandIdentity): JsonObject {
        val digest = MessageDigest.getInstance("SHA-256")
        linked(c, actor, id, version) { link, receipt ->
            checkLinked(actor, id, version, link, receipt, requireCompacted = true)
            if (link.getValue("feedback_version").jsonPrimitive.content.toLong() == version &&
                (link["operation_id"] != JsonPrimitive(identity.operationId) || link["command_key"] != JsonPrimitive(identity.key.toString()) ||
                    link["request_hash"] != JsonPrimitive(identity.requestHash))) fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
            val bytes = feedbackCanonical(buildJsonObject { put("link", link); put("receipt", receipt) }).encodeToByteArray()
            digest.update(bytes.size.toString().encodeToByteArray()); digest.update(':'.code.toByte()); digest.update(bytes)
        }
        return buildJsonObject { put("count", version); put("sha256", digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }) }
    }
    private fun checkCompatibility(c: Connection) {
        for ((version, name) in listOf(1 to "durable_platform", 22 to "private_feedback")) {
            val expected = FeedbackStore::class.java.getResourceAsStream("/db/migration/V${version.toString().padStart(3, '0')}__$name.sql")
                ?.use { feedbackSha(it.readBytes().decodeToString()) } ?: fail(FeedbackFailureCode.NOT_CONFIGURED)
            c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=?").use {
                it.setInt(1, version); it.executeQuery().use { r ->
                    if (!r.next() || r.getString(1) != expected || r.next()) fail(FeedbackFailureCode.NOT_CONFIGURED)
                }
            }
        }
    }

    /** Provisional domain result; actual owner must not disclose it until its commit succeeds. */
    internal class Pending<T> private constructor(val result: T, private val trace: FeedbackStore.Trace) {
        fun revalidate(c: Connection, actor: VerifiedFeedbackPrincipal) = trace.revalidate(c, actor)
        fun checkAt(c: Connection, actor: VerifiedFeedbackPrincipal, acceptedAt: Instant) = trace.checkAt(c, actor, acceptedAt)
        override fun toString() = "FeedbackPending(<redacted>)"
        companion object { internal operator fun <T> invoke(result: T, trace: FeedbackStore.Trace): Pending<T> = Pending(result, trace) }
    }

    internal inner class Trace(private val connection: Connection, private val actor: VerifiedFeedbackPrincipal) {
        internal val guard = Guard(connection, actor)
        internal var firstTime: Instant? = null
        internal var event: EventDraft? = null
        internal var fresh: Fresh? = null
        private var expires: Instant? = null
        private var lastCheckedAt: Instant? = null
        private val observations = mutableListOf<Pair<String, () -> JsonElement>>()
        private val refused = AtomicBoolean(false)
        private val active = AtomicBoolean(false)
        fun capture(identity: CommandIdentity, requestedId: UUID?, response: StoredReply) {
            val id = requestedId ?: response.body!!.jsonObject.getValue("id").jsonPrimitive.content.let(UUID::fromString)
            val value = row(connection, actor, id)
            if (identity.operationId == "deleteFeedback") {
                if (!value.deleted || value.deletionKey != identity.key || response.status != 204 || response.body != null || response.etag != null)
                    fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
            } else if (value.deleted || response.status != (if (identity.operationId == "createFeedback") 201 else 200) ||
                response.etag != "\"${value.version}\"" || feedbackCanonical(response.body ?: JsonNull) != feedbackCanonical(value.snapshot ?: JsonNull))
                fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
            observe(value.image()) { row(connection, actor, id).image() }
            fun receipt() = image(connection, "platform.idempotency", "principal_scope=? AND operation_id=? AND key=?") {
                setString(1, identity.scope.storageKey); setString(2, identity.operationId); setObject(3, identity.key)
            }
            val receipt = receipt()
            if (receipt["principal_scope"] != JsonPrimitive(identity.scope.storageKey) || receipt["operation_id"] != JsonPrimitive(identity.operationId) ||
                receipt["key"] != JsonPrimitive(identity.key.toString()) || receipt["request_hash"] != JsonPrimitive(identity.requestHash) ||
                receipt["state"] != JsonPrimitive("completed") || receipt["response_code"] != JsonPrimitive(response.status) ||
                receipt["response_etag"] != (response.etag?.let(::JsonPrimitive) ?: JsonNull) ||
                feedbackCanonical(receipt.getValue("response_json")) != feedbackCanonical(response.body ?: JsonNull))
                fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
            expires = OffsetDateTime.parse(receipt.getValue("expires_at").jsonPrimitive.content).toInstant()
            observe(receipt, ::receipt)
            observe(chainImage(connection, actor, id, value.version, identity)) { chainImage(connection, actor, id, value.version, identity) }
            event?.let { draft ->
                fun load() = image(connection, "platform.outbox", "event_id=?") { setObject(1, draft.eventId) }
                val actual = load()
                val fields = buildJsonObject {
                    put("event_id", draft.eventId.toString()); put("event_type", draft.eventType); put("schema_version", draft.schemaVersion)
                    put("aggregate_type", draft.aggregateType); put("aggregate_id", draft.aggregateId.toString()); put("aggregate_version", draft.aggregateVersion)
                    put("producer", draft.producer); put("correlation_id", draft.correlationId); put("causation_id", draft.causationId.toString()); put("payload", draft.data)
                }
                if (fields.any { (key, expected) -> feedbackCanonical(actual[key] ?: JsonNull) != feedbackCanonical(expected) })
                    fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
                observe(actual, ::load)
            }
        }
        fun captureRefusal(identity: CommandIdentity, result: CommandResult) {
            fun load() = image(connection, "platform.idempotency", "principal_scope=? AND operation_id=? AND key=?") {
                setString(1, identity.scope.storageKey); setString(2, identity.operationId); setObject(3, identity.key)
            }
            val actual = load()
            val sameHash = actual["request_hash"] == JsonPrimitive(identity.requestHash)
            if (when (result) {
                CommandResult.Mismatch -> sameHash
                CommandResult.ReceiptExpired -> !sameHash || actual["state"] != JsonPrimitive("tombstone") ||
                    listOf("response_code", "response_json", "response_etag").any { actual[it] != JsonNull }
                CommandResult.IncompleteReceipt -> !sameHash || actual["state"] != JsonPrimitive("pending")
                else -> true
            }) fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
            observe(actual, ::load)
        }
        private fun observe(expected: JsonElement, read: () -> JsonElement) { observations += feedbackSha(feedbackCanonical(expected)) to read }
        fun revalidate(c: Connection, actual: VerifiedFeedbackPrincipal): Unit = checked {
            guard.check(c, actual)
            fresh?.let { source ->
                source.evidence.revalidate(c, actual, source.context); guard.check(c, actual)
                if (provenance(source.evidence.snapshot) != source.provenance) fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
            }
            for ((expected, read) in observations) {
                if (feedbackSha(feedbackCanonical(read())) != expected) fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
                guard.check(c, actual)
            }
            guard.check(c, actual)
            val at = now(c)
            checkTime(c, actual, at)
            lastCheckedAt = at
        }
        fun checkAt(c: Connection, actual: VerifiedFeedbackPrincipal, at: Instant): Unit = checked {
            if (lastCheckedAt == null) fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
            checkTime(c, actual, at); lastCheckedAt = at
        }
        private fun checkTime(c: Connection, actual: VerifiedFeedbackPrincipal, at: Instant) {
            guard.checkLocal(c, actual)
            val first = firstTime ?: fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
            if (at < first || lastCheckedAt?.let { at < it } == true || expires?.isAfter(at) == false)
                fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
        }
        private fun checked(action: () -> Unit): Unit = safe {
            if (refused.get() || !active.compareAndSet(false, true)) fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
            try { action() } catch (failure: Throwable) { refused.set(true); throw failure } finally { active.set(false) }
        }
    }
    internal class Guard(private val connection: Connection, private val actor: VerifiedFeedbackPrincipal) {
        private val thread = Thread.currentThread()
        private val transaction: Long
        init { checkLocal(connection, actor); checkIsolation(connection); transaction = transactionId(connection) }
        fun check(c: Connection, actual: VerifiedFeedbackPrincipal) {
            checkLocal(c, actual); checkIsolation(c)
            if (transactionId(c) != transaction) fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
        }
        fun checkLocal(c: Connection, actual: VerifiedFeedbackPrincipal) {
            current()
            if (c !== connection || actual !== actor || Thread.currentThread() !== thread || c.isClosed || c.autoCommit)
                fail(FeedbackFailureCode.UNAUTHENTICATED)
        }
    }
    internal class Fresh(val context: FeedbackTargetContext, val evidence: FeedbackTargetEvidence, val provenance: String)
    private class Row(val id: UUID, val version: Long, val context: FeedbackTargetContext?, val provenance: String?,
        val snapshot: JsonObject?, val deleted: Boolean, val deletionKey: UUID?, val created: Instant, val updated: Instant) {
        fun image(): JsonObject = buildJsonObject {
            put("id", id.toString()); put("version", version); put("context", context?.exactDocument?.let(::JsonPrimitive) ?: JsonNull)
            put("provenance", provenance?.let(::JsonPrimitive) ?: JsonNull); put("snapshot", snapshot ?: JsonNull)
            put("deleted", deleted); put("deletionKey", deletionKey?.toString()?.let(::JsonPrimitive) ?: JsonNull)
            put("createdAt", created.toString()); put("updatedAt", updated.toString())
        }
        fun identity() = feedbackCanonical(image())
    }
    private fun image(c: Connection, table: String, where: String, bind: PreparedStatement.() -> Unit): JsonObject =
        c.prepareStatement("SELECT to_jsonb(retained_row) FROM $table AS retained_row WHERE $where FOR SHARE").use {
            it.bind(); it.executeQuery().use { r ->
                if (!r.next()) fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
                Json.parseToJsonElement(r.getString(1)).jsonObject.also { if (r.next()) fail(FeedbackFailureCode.STORAGE_UNAVAILABLE) }
            }
        }
    private fun PreparedStatement.owner(actor: VerifiedFeedbackPrincipal, offset: Int = 1) {
        setString(offset, environment); setString(offset + 1, actor.kind.name.lowercase()); setObject(offset + 2, actor.principalId)
    }
    private fun PreparedStatement.instant(index: Int, value: Instant) = setObject(index, value.atOffset(ZoneOffset.UTC))
    private fun ResultSet.instant(name: String): Instant = getObject(name, OffsetDateTime::class.java).toInstant()
    override fun toString() = "FeedbackStore(<redacted>)"
    companion object { private val validator by lazy { ContractBodyValidator.bundled() } }
}

private fun current() { if (Thread.currentThread().isInterrupted) throw InterruptedException("Feedback interrupted") }
private fun checkIsolation(c: Connection) {
    if (c.transactionIsolation != Connection.TRANSACTION_READ_COMMITTED) fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
}
private fun transactionId(c: Connection): Long = c.createStatement().use { s -> s.executeQuery("SELECT txid_current()").use {
    check(it.next()); it.getLong(1).also { _ -> check(!it.next()) }
} }
private fun now(c: Connection): Instant = c.createStatement().use { s ->
    s.executeQuery("SELECT date_trunc('milliseconds',clock_timestamp())").use { r ->
        check(r.next()); r.getObject(1, OffsetDateTime::class.java).toInstant().also { check(!r.next()) }
    }
}
private fun fail(code: FeedbackFailureCode): Nothing = throw FeedbackFailure(code)
private fun <T> safe(action: () -> T): T = try { action() }
    catch (failure: FeedbackFailure) { throw failure }
    catch (failure: GuestSessionFailure) { throw failure }
    catch (failure: PlanningServiceFailure) { throw failure }
    catch (failure: RecipeCatalogFailure) { throw failure }
    catch (failure: IngredientCatalogFailure) { throw failure }
    catch (failure: CommitOutcomeUnknown) { throw failure }
    catch (failure: CancellationException) { throw failure }
    catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
    catch (failure: SQLException) { current(); if (failure.sqlState in setOf("40001", "40P01")) throw failure
        else fail(FeedbackFailureCode.STORAGE_UNAVAILABLE) }
    catch (_: Exception) { current(); fail(FeedbackFailureCode.STORAGE_UNAVAILABLE) }
