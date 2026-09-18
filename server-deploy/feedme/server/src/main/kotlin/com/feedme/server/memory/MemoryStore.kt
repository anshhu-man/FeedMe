package com.feedme.server.memory

import com.feedme.server.catalog.IngredientCatalogFailure
import com.feedme.server.catalog.RecipeCatalogFailure
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.guest.GuestSessionFailure
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

/** Explicit-feedback projection and private controls, inside the actual owner's transaction.
 * Reconciliation is a separate bounded commit, never hidden inside a read or memory command.
 * A dirty head cannot disclose stale memories or become a ranking grant. No worker, HTTP,
 * medical inference, recipe copy, feedback rewrite, or autonomous preference activation. */
internal class MemoryStore(val environment: String, private val transactions: PgTransactions,
    private val authority: MemoryAuthority, private val cursors: MemoryCursors, val policy: MemoryServicePolicy) {
    private val commands = DurableCommands(transactions)
    private val outbox = OutboxStore(transactions)
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }
    fun isBoundTo(env: String, tx: PgTransactions): Boolean = env == environment && tx === transactions

    fun requestRebuild(c: Connection, actor: VerifiedMemoryPrincipal): Pending<MemoryProjectionProgress> = safe {
        val tx = begin(c, actor, initialize = true)
        // A rebuild is deliberately not a new explicit opinion. Keep epochs/overrides and
        // suppressions unchanged, even when every retained source is marked dirty again.
        val head = head(c, actor)
        sourceIntegrity(c, actor)
        exec(c, "UPDATE memory.memory_heads SET source_revision=? WHERE $OWNER") {
            setLong(1, increment(head.source)); owner(actor, 2)
        }
        c.prepareStatement("UPDATE memory.memory_feedback_state SET dirty=true WHERE $OWNER").use { it.owner(actor); it.executeUpdate() }
        tx.watch("rebuild-state") { ownerDigest(c, actor, "memory.memory_feedback_state", "feedback_id") }
        val updated = head(c, actor)
        tx.finish(MemoryProjectionProgress(0, true, updated.source, updated.projected))
    }

    fun reconcile(c: Connection, actor: VerifiedMemoryPrincipal): Pending<MemoryProjectionProgress> = safe {
        val tx = begin(c, actor, initialize = true)
        val initial = head(c, actor)
        sourceIntegrity(c, actor)
        val selected = query(c, "SELECT feedback_id FROM memory.memory_feedback_state WHERE $OWNER AND dirty ORDER BY feedback_id LIMIT ? FOR UPDATE", {
            owner(actor); setInt(4, policy.maxProjectionFeedback)
        }) { r -> buildList { while (r.next()) add(r.getObject(1, UUID::class.java)) } }
        val groups = linkedSetOf<Group>()
        for (id in selected) {
            val source = feedback(tx, id)
            val previous = sourceRows(c, actor, " AND feedback_id=?", { setObject(4, id) })
            previous.forEach { requireOwner(it, actor); groups += group(it) }
            c.prepareStatement("DELETE FROM memory.memory_sources WHERE $OWNER AND feedback_id=?").use {
                it.owner(actor); it.setObject(4, id); it.executeUpdate()
            }
            for (part in source.parts) {
                groups += Group(part.kind, part.semanticKey)
                val fingerprint = MemoryRules.fingerprint(id, part)
                val suppression = owned(c, actor, "memory.memory_suppressions",
                    " AND semantic_key=? AND feedback_id=? AND signal_key=? AND signal_epoch=?") {
                    setString(4, part.semanticKey); setObject(5, id); setString(6, part.signalKey); setLong(7, part.epoch)
                }
                if (suppression != null) {
                    if (suppression["semantic_key"] != JsonPrimitive(part.semanticKey) || suppression["feedback_id"] != JsonPrimitive(id.toString()) ||
                        suppression["signal_key"] != JsonPrimitive(part.signalKey) || number(suppression, "signal_epoch") != part.epoch ||
                        suppression["fingerprint"] != JsonPrimitive(fingerprint)) unavailable()
                    continue
                }
                exec(c, "INSERT INTO memory.memory_sources(environment,actor_kind,principal_id,semantic_key,feedback_id,signal_key," +
                    "signal_epoch,fingerprint,kind,value,context,source_version,source_sha256) VALUES(?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?)") {
                    owner(actor); setString(4, part.semanticKey); setObject(5, id); setString(6, part.signalKey); setLong(7, part.epoch)
                    setString(8, fingerprint); setString(9, part.kind); setString(10, part.value)
                    setString(11, part.context.toString()); setLong(12, source.version); setString(13, source.sha)
                }
            }
            exec(c, "UPDATE memory.memory_feedback_state SET dirty=false WHERE $OWNER AND feedback_id=? AND feedback_version=? AND dirty") {
                owner(actor); setObject(4, id); setLong(5, source.version)
            }
            c.prepareStatement("INSERT INTO memory.memory_projection_events(environment,actor_kind,principal_id,event_id,feedback_id,source_version,rule_version,processed_at) " +
                "VALUES(?,?,?,?,?,?,?,clock_timestamp()) ON CONFLICT DO NOTHING").use {
                it.owner(actor); it.setObject(4, source.eventId); it.setObject(5, id); it.setLong(6, source.version); it.setString(7, MemoryRules.VERSION)
                it.executeUpdate()
            }
            val processed = owned(c, actor, "memory.memory_projection_events", " AND event_id=? AND rule_version=?") {
                setObject(4, source.eventId); setString(5, MemoryRules.VERSION)
            } ?: unavailable()
            if (processed["feedback_id"] != JsonPrimitive(id.toString()) || number(processed, "source_version") != source.version) unavailable()
            tx.watch("projected:$id") { owned(c, actor, "memory.memory_projection_events", " AND event_id=? AND rule_version=?") {
                setObject(4, source.eventId); setString(5, MemoryRules.VERSION)
            } ?: unavailable() }
            tx.watch("state:$id") { state(c, actor, id) }
        }
        for (group in groups) c.prepareStatement("INSERT INTO memory.memory_dirty_groups(environment,actor_kind,principal_id,kind,semantic_key) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING").use {
            it.owner(actor); it.setString(4, group.kind); it.setString(5, group.key); it.executeUpdate()
        }
        groups.forEach(tx::group)
        // Never materialize a source prefix. In particular, an empty prefix must not
        // tombstone an explicit override while a later dirty source still supports it.
        // Both phases are durably resumable and bounded, including the final group drain.
        var changed = false
        if (!sourceDirty(c, actor)) {
            val ready = query(c, "SELECT kind,semantic_key FROM memory.memory_dirty_groups WHERE $OWNER ORDER BY kind,semantic_key LIMIT ? FOR UPDATE", {
                owner(actor); setInt(4, policy.maxProjectionFeedback)
            }) { r -> buildList { while (r.next()) add(Group(r.getString(1), r.getString(2))) } }
            for (group in ready) {
                changed = refresh(tx, group) || changed
                exec(c, "DELETE FROM memory.memory_dirty_groups WHERE $OWNER AND kind=? AND semantic_key=?") {
                    owner(actor); setString(4, group.kind); setString(5, group.key)
                }
            }
        }
        val remaining = dirty(c, actor)
        val beforeAdvance = head(c, actor)
        if (beforeAdvance.source != initial.source || beforeAdvance.projected != initial.projected || beforeAdvance.revision != initial.revision) unavailable()
        if (changed || !remaining) exec(c, "UPDATE memory.memory_heads SET projected_revision=?,memory_revision=? WHERE $OWNER") {
            setLong(1, if (remaining) initial.projected else initial.source)
            setLong(2, if (changed) increment(initial.revision) else initial.revision); owner(actor, 3)
        }
        val result = head(c, actor)
        tx.watch("group-queue") { ownerDigest(c, actor, "memory.memory_dirty_groups", "kind,semantic_key") }
        tx.watch("dirty") { JsonPrimitive(dirty(c, actor)) }
        tx.finish(MemoryProjectionProgress(selected.size, remaining || result.source != result.projected, result.source, result.projected))
    }

    fun listMemories(c: Connection, actor: VerifiedMemoryPrincipal, cursor: String? = null, limit: Int = 20): Pending<StoredReply> = safe {
        if (limit !in 1..50) fail(MemoryFailureCode.INPUT_INVALID)
        val tx = begin(c, actor); val head = tx.clean(); tx.watchHead(); val at = now(c)
        val position = cursors.decode(actor, head.revision, limit, cursor, at)
        if (cursor != null) tx.cursor(head.revision, limit, cursor)
        fun selection(): JsonArray = query(c, "SELECT id FROM memory.memories WHERE $OWNER AND NOT deleted AND (?::uuid IS NULL OR id>?) ORDER BY id LIMIT ?", {
            owner(actor); setObject(4, position?.after); setObject(5, position?.after); setInt(6, limit + 1)
        }) { r -> buildJsonArray { while (r.next()) add(r.getObject(1, UUID::class.java).toString()) } }
        val selected = selection(); tx.watch("page", ::selection)
        val items = mutableListOf<JsonObject>(); var next: String? = null
        for (item in selected.take(limit)) {
            val id = UUID.fromString(item.jsonPrimitive.content); val stored = checkedMemory(tx, id)
            val more = selected.size > items.size + 1
            val candidate = if (more) cursors.encode(actor, head.revision, limit, id, at.plusSeconds(policy.cursorLifetimeSeconds)) else null
            if (bytes(page(items + stored.snapshot!!, candidate, at)).size > policy.maxResponseBytes) {
                if (items.isEmpty()) fail(MemoryFailureCode.RESPONSE_TOO_LARGE)
                next = cursors.encode(actor, head.revision, limit, UUID.fromString(items.last().text("id")), at.plusSeconds(policy.cursorLifetimeSeconds))
                break
            }
            items += stored.snapshot!!; next = candidate
        }
        if (next != null) tx.cursor(head.revision, limit, next)
        tx.finish(reply("listMemories", 200, page(items, next, at)))
    }

    fun getMemory(c: Connection, actor: VerifiedMemoryPrincipal, id: UUID): Pending<StoredReply> = safe {
        val tx = begin(c, actor); tx.clean(); tx.watchHead()
        val stored = checkedMemory(tx, id)
        tx.finish(reply("getMemory", 200, stored.snapshot, stored.version))
    }

    fun updateMemory(c: Connection, actor: VerifiedMemoryPrincipal, key: UUID, id: UUID,
        ifMatch: String, body: JsonObject): Pending<CommandResult> = safe {
        val expected = version(ifMatch); val input = request(body)
        command(c, actor, "updateMemory", key, id, ifMatch, input) { tx ->
            val old = checkedMemory(tx, id, watch = false)
            if (old.version != expected) fail(MemoryFailureCode.VERSION_CONFLICT)
            val override = JsonObject((old.override ?: buildJsonObject {}) + input)
            input["context"]?.jsonObject?.let { context ->
                val evidence = authority.authorizeContext(c, actor, context)
                tx.proof(evidence, context)
            }
            val visible = visible(tx, old.group, validateCurrent = true)
            val at = now(c); val next = increment(old.version)
            val document = render(old.id, next, old.created, at, old.group, visible, override)
            reply("updateMemory", 200, document, next)
            exec(c, "UPDATE memory.memories SET version=?,snapshot=?::jsonb,user_override=?::jsonb,updated_at=? WHERE $OWNER AND id=? AND version=? AND NOT deleted") {
                setLong(1, next); setString(2, document.toString()); setString(3, override.toString()); instant(4, at)
                owner(actor, 5); setObject(8, id); setLong(9, old.version)
            }
            requireMaterialized(c, actor, id, old.generation, old.group, old.context, document, override)
            tx.advance(); tx.group(old.group); tx.memory(id)
            tx.event(key, id, next, "updated")
            reply("updateMemory", 200, document, next)
        }
    }

    fun deleteMemory(c: Connection, actor: VerifiedMemoryPrincipal, key: UUID, id: UUID,
        ifMatch: String): Pending<CommandResult> = safe {
        val expected = version(ifMatch)
        command(c, actor, "deleteMemory", key, id, ifMatch, null) { tx ->
            val old = checkedMemory(tx, id, watch = false)
            if (old.version != expected) fail(MemoryFailureCode.VERSION_CONFLICT)
            val attached = visible(tx, old.group, validateCurrent = true)
            val at = now(c)
            for (source in attached) {
                exec(c, "INSERT INTO memory.memory_suppressions(environment,actor_kind,principal_id,semantic_key,feedback_id,signal_key,signal_epoch,fingerprint,created_at) VALUES(?,?,?,?,?,?,?,?,?)") {
                    owner(actor); setString(4, source.text("semantic_key")); setObject(5, uuid(source, "feedback_id")); setString(6, source.text("signal_key"))
                    setLong(7, number(source, "signal_epoch")); setString(8, source.text("fingerprint")); instant(9, at)
                }
                exec(c, "DELETE FROM memory.memory_sources WHERE $OWNER AND semantic_key=? AND feedback_id=? AND signal_key=? AND signal_epoch=? AND fingerprint=?") {
                    owner(actor); setString(4, source.text("semantic_key")); setObject(5, uuid(source, "feedback_id")); setString(6, source.text("signal_key"))
                    setLong(7, number(source, "signal_epoch")); setString(8, source.text("fingerprint"))
                }
            }
            tombstone(c, actor, old, at, key); tx.advance(); tx.group(old.group); tx.memory(id)
            tx.event(key, id, old.version + 1, "forgotten")
            reply("deleteMemory", 204, null)
        }
    }

    private fun command(c: Connection, actor: VerifiedMemoryPrincipal, operation: String, key: UUID, id: UUID,
        ifMatch: String, input: JsonObject?, mutate: (Trace) -> StoredReply): Pending<CommandResult> {
        val tx = begin(c, actor); tx.clean()
        val identity = CommandIdentity(PrincipalScope(environment, actor.kind, actor.principalId), operation, key,
            mapOf("memoryId" to id.toString()), body = input, ifMatch = ifMatch)
        val result = commands.executeInTransaction(c, identity, { authority.lockPrincipal(it, actor); tx.guard.check(it, actor) }, {}, { _, cached ->
            val actual = memory(c, actor, id)
            if (operation == "deleteMemory") {
                if (!actual.deleted || actual.deletionKey != key || actual.version != increment(version(ifMatch)) ||
                    cached.status != 204 || cached.body != null || cached.etag != null) fail(MemoryFailureCode.VERSION_CONFLICT)
            } else {
                val live = checkedMemory(tx, id)
                if (cached.status != 200 || cached.etag != "\"${live.version}\"" || canonical(cached.body ?: JsonNull) != canonical(live.snapshot!!))
                    fail(MemoryFailureCode.VERSION_CONFLICT)
            }
        }, { mutate(tx) })
        when (result) {
            is CommandResult.Applied -> {
                val row = memory(c, actor, id)
                exec(c, "INSERT INTO memory.memory_commands(environment,actor_kind,principal_id,memory_id,memory_version,principal_scope,operation_id,command_key,request_hash,response_sha256) VALUES(?,?,?,?,?,?,?,?,?,?)") {
                    owner(actor); setObject(4, id); setLong(5, row.version); setString(6, identity.scope.storageKey); setString(7, operation)
                    setObject(8, key); setString(9, identity.requestHash); setString(10, sha(canonical(result.reply.body ?: JsonNull)))
                }
                compact(c, actor, id, preserve = if (operation == "updateMemory") key else null)
                tx.command(identity, id, result.reply); tx.memory(id); tx.chain(id)
            }
            is CommandResult.Replayed -> { tx.command(identity, id, result.reply); tx.memory(id); tx.chain(id) }
            else -> tx.receipt(identity)
        }
        return tx.finish(result)
    }

    private fun refresh(tx: Trace, group: Group): Boolean {
        val c = tx.c; val actor = tx.actor
        val sources = visible(tx, group, validateCurrent = false)
        val old = query(c, "SELECT id FROM memory.memories WHERE $OWNER AND kind=? AND semantic_key=? ORDER BY generation DESC LIMIT 1 FOR UPDATE", {
            owner(actor); setString(4, group.kind); setString(5, group.key)
        }) { r -> if (r.next()) memory(c, actor, r.getObject(1, UUID::class.java)) else null }
        tx.group(group)
        if (sources.isEmpty()) {
            if (old == null || old.deleted) return false
            tombstone(c, actor, old, now(c), null); compact(c, actor, old.id); tx.memory(old.id); tx.chain(old.id)
            tx.event(UUID.randomUUID(), old.id, old.version + 1, "sourceRetracted")
            return true
        }
        val at = now(c)
        if (old == null || old.deleted) {
            val id = UUID.randomUUID(); val generation = increment(old?.generation ?: 0)
            val body = render(id, 1, at, at, group, sources, null)
            reply("getMemory", 200, body, 1)
            exec(c, "INSERT INTO memory.memories(environment,actor_kind,principal_id,id,generation,version,kind,semantic_key,original_context,snapshot,created_at,updated_at) VALUES(?,?,?,?,?,1,?,?,?::jsonb,?::jsonb,?,?)") {
                owner(actor); setObject(4, id); setLong(5, generation); setString(6, group.kind); setString(7, group.key)
                setString(8, sources.first().getValue("context").toString()); setString(9, body.toString()); instant(10, at); instant(11, at)
            }
            requireMaterialized(c, actor, id, generation, group, sources.first().getValue("context").jsonObject, body, null)
            tx.memory(id); tx.event(UUID.randomUUID(), id, 1, "projected")
            return true
        }
        val sameTime = render(old.id, old.version, old.created, old.updated, group, sources, old.override)
        if (canonical(sameTime) == canonical(old.snapshot!!)) { tx.memory(old.id); return false }
        val next = increment(old.version); val body = render(old.id, next, old.created, at, group, sources, old.override)
        reply("getMemory", 200, body, next)
        exec(c, "UPDATE memory.memories SET version=?,snapshot=?::jsonb,updated_at=? WHERE $OWNER AND id=? AND version=? AND NOT deleted") {
            setLong(1, next); setString(2, body.toString()); instant(3, at); owner(actor, 4); setObject(7, old.id); setLong(8, old.version)
        }
        requireMaterialized(c, actor, old.id, old.generation, group, old.context, body, old.override)
        compact(c, actor, old.id); tx.memory(old.id); tx.chain(old.id); tx.event(UUID.randomUUID(), old.id, next, "projected")
        return true
    }

    private fun checkedMemory(tx: Trace, id: UUID, watch: Boolean = true): MemoryRow {
        val row = memory(tx.c, tx.actor, id)
        if (row.deleted) fail(MemoryFailureCode.MEMORY_UNAVAILABLE)
        val sources = visible(tx, row.group, validateCurrent = true)
        if (sources.isEmpty() || canonical(render(id, row.version, row.created, row.updated, row.group, sources, row.override)) != canonical(row.snapshot!!)) unavailable()
        if (watch) { tx.memory(id); tx.group(row.group) }
        return row
    }

    private fun visible(tx: Trace, group: Group, validateCurrent: Boolean): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        tx.c.prepareStatement("SELECT to_jsonb(s),p.fingerprint FROM memory.memory_sources s LEFT JOIN memory.memory_suppressions p " +
            "ON p.environment=s.environment AND p.actor_kind=s.actor_kind AND p.principal_id=s.principal_id AND p.semantic_key=s.semantic_key " +
            "AND p.feedback_id=s.feedback_id AND p.signal_key=s.signal_key AND p.signal_epoch=s.signal_epoch " +
            "WHERE s.environment=? AND s.actor_kind=? AND s.principal_id=? AND s.kind=? AND s.semantic_key=? " +
            "ORDER BY s.signal_epoch DESC,s.feedback_id,s.signal_key").use { s ->
            s.owner(tx.actor); s.setString(4, group.kind); s.setString(5, group.key); s.fetchSize = 32
            s.executeQuery().use { r -> while (r.next()) {
                current(); val row = json(r.getString(1)); requireOwner(row, tx.actor)
                val part = contribution(row); val id = uuid(row, "feedback_id")
                if (part.kind != group.kind || part.semanticKey != group.key || MemoryRules.fingerprint(id, part) != row.text("fingerprint")) unavailable()
                val suppressed = r.getString(2)
                if (suppressed != null) { if (suppressed != row.text("fingerprint")) unavailable(); continue }
                val state = state(tx.c, tx.actor, id)
                if (validateCurrent || !state.getValue("dirty").jsonPrimitive.boolean) {
                    val actual = feedback(tx, id)
                    val expected = actual.parts.singleOrNull { it.signalKey == part.signalKey } ?: unavailable()
                    if (actual.version != number(row, "source_version") || actual.sha != row.text("source_sha256") ||
                        expected.semanticKey != part.semanticKey || expected.epoch != part.epoch || expected.value != part.value ||
                        canonical(expected.context) != canonical(part.context)) unavailable()
                }
                result += row
                if (result.size > policy.maxSourcesPerMemory) fail(MemoryFailureCode.RESPONSE_TOO_LARGE)
            } }
        }
        return result
    }

    private fun contribution(row: JsonObject): MemoryContribution = stored {
        val kind = row.text("kind"); val context = MemoryRules.normalizeContext(row.getValue("context").jsonObject)
        val key = MemoryRules.semanticKey(kind, context); val signal = row.text("signal_key"); val epoch = number(row, "signal_epoch")
        if (key != row.text("semantic_key") || epoch <= 0 || signal != when (kind) { "repeat" -> "makeAgain"; else -> kind }) unavailable()
        val value = MemoryRules.validateValue(row.text("value"))
        MemoryContribution(kind, key, context, signal, epoch, value, MemoryRules.label(kind, value, context))
    }

    private fun render(id: UUID, version: Long, created: Instant, updated: Instant, group: Group,
        sources: List<JsonObject>, override: JsonObject?): JsonObject = stored {
        if (sources.isEmpty() || updated < created) unavailable()
        val selected = sources.first(); val original = selected.getValue("context").jsonObject
        val context = override?.get("context")?.jsonObject ?: original
        val value = override?.get("value")?.jsonPrimitive?.content ?: selected.text("value")
        val enabled = override?.get("enabled")?.jsonPrimitive?.boolean ?: (value != "neutral")
        MemoryRules.normalizeContext(context); MemoryRules.validateValue(value)
        buildJsonObject {
            put("id", id.toString()); put("version", version); put("createdAt", created.toString()); put("updatedAt", updated.toString())
            put("kind", group.kind); put("label", MemoryRules.label(group.kind, value, context)); put("value", value)
            put("sourceFeedbackIds", JsonArray(sources.map { it.text("feedback_id") }.distinct().sorted().map(::JsonPrimitive)))
            put("enabled", enabled); put("context", context)
        }
    }

    private fun feedback(tx: Trace, id: UUID): FeedbackSource {
        tx.sources[id]?.let { return it }
        val c = tx.c; val actor = tx.actor
        fun sourceRow() = committed(c, actor, "memory.feedback", " AND id=?") { setObject(4, id) }
        val row = sourceRow(); val version = number(row, "version"); val state = state(c, actor, id)
        if (number(state, "feedback_version") != version || version <= 0) unavailable()
        tx.watch("state-content:$id") { JsonObject(state(c, actor, id) - "dirty") }
        val deleted = row.getValue("deleted").jsonPrimitive.boolean
        val snapshot = row.getValue("snapshot")
        if (deleted) {
            if (listOf("snapshot", "context_text", "context_sha256", "provenance_text", "provenance_sha256", "cook_session_id").any { row[it] != JsonNull }) unavailable()
        } else {
            if (snapshot !is JsonObject || validator.validateSchema("Feedback", bytes(snapshot)) != BodyValidationResult.Valid ||
                snapshot["id"] != JsonPrimitive(id.toString()) || number(snapshot, "version") != version ||
                instant(snapshot, "createdAt") != instant(row, "created_at") || instant(snapshot, "updatedAt") != instant(row, "updated_at")) unavailable()
            val context = row.text("context_text"); val provenance = row.text("provenance_text")
            if (sha(context) != row.text("context_sha256") || sha(provenance) != row.text("provenance_sha256") ||
                FeedbackTargetContext.decode(context).exactDocument != FeedbackTargetContext.fromInput(snapshot).exactDocument ||
                canonical(json(provenance)) != provenance) unavailable()
        }
        val link = committed(c, actor, "memory.feedback_commands", " AND feedback_id=? AND feedback_version=?") { setObject(4, id); setLong(5, version) }
        val operation = link.text("operation_id")
        if (operation != (if (deleted) "deleteFeedback" else if (version == 1L) "createFeedback" else "updateFeedback")) unavailable()
        val key = uuid(link, "command_key"); val scope = PrincipalScope(environment, actor.kind, actor.principalId).storageKey
        if (link["principal_scope"] != JsonPrimitive(scope) || link["feedback_id"] != JsonPrimitive(id.toString()) ||
            number(link, "feedback_version") != version || link.text("response_sha256") != sha(canonical(snapshot))) unavailable()
        fun receipt() = image(c, "platform.idempotency", "principal_scope=? AND operation_id=? AND key=?") {
            setString(1, scope); setString(2, operation); setObject(3, key)
        } ?: unavailable()
        val receipt = receipt()
        if (receipt["principal_scope"] != JsonPrimitive(scope) || receipt["operation_id"] != JsonPrimitive(operation) ||
            receipt["key"] != JsonPrimitive(key.toString()) || receipt["request_hash"] != link["request_hash"] ||
            receipt["state"]?.jsonPrimitive?.content !in setOf("completed", "tombstone")) unavailable()
        if (receipt["state"] == JsonPrimitive("completed") && (canonical(receipt.getValue("response_json")) != canonical(snapshot) ||
            receipt["response_code"] != JsonPrimitive(if (deleted) 204 else if (version == 1L) 201 else 200) ||
            receipt["response_etag"] != (if (deleted) JsonNull else JsonPrimitive("\"$version\"")))) unavailable()
        if (receipt["state"] == JsonPrimitive("tombstone") && listOf("response_code", "response_json", "response_etag").any { receipt[it] != JsonNull }) unavailable()
        val event = query(c, "SELECT to_jsonb(e),(e.xmin::text::bigint<>mod(txid_current(),4294967296)) FROM platform.outbox e " +
            "WHERE event_type='memory.feedback.changed.v1' AND aggregate_type='feedback' AND aggregate_id=? AND aggregate_version=? AND causation_id=? LIMIT 2 FOR SHARE", {
            setObject(1, id); setLong(2, version); setObject(3, key)
        }) { r -> if (!r.next() || !r.getBoolean(2)) unavailable(); json(r.getString(1)).also { if (r.next()) unavailable() } }
        val payload = buildJsonObject { put("principalId", actor.principalId.toString()); put("feedbackId", id.toString())
            put("action", if (deleted) "deleted" else if (version == 1L) "created" else "updated") }
        if (event["payload"] != payload || event["schema_version"] != JsonPrimitive(1) || event["producer"] != JsonPrimitive("memory")) unavailable()
        tx.watch("feedback:$id", ::sourceRow)
        tx.watch("feedback-link:$id") { committed(c, actor, "memory.feedback_commands", " AND feedback_id=? AND feedback_version=?") { setObject(4, id); setLong(5, version) } }
        tx.watch("feedback-receipt:$id", ::receipt)
        val eventId = uuid(event, "event_id")
        tx.watch("feedback-event:$id") { image(c, "platform.outbox", "event_id=?") { setObject(1, eventId) } ?: unavailable() }
        val parts = if (deleted) emptyList() else {
            val evidence = authority.resolveFeedbackContext(c, actor, row)
            tx.proof(evidence)
            if (canonical(sourceRow()) != canonical(row)) unavailable()
            val epochs = mapOf("taste" to number(state, "taste_epoch"), "effort" to number(state, "effort_epoch"), "makeAgain" to number(state, "make_again_epoch"))
            if (epochs.values.any { it < 0 || it > head(c, actor).source }) unavailable()
            stored { MemoryRules.derive(snapshot.jsonObject, evidence.context, epochs) }
        }
        return FeedbackSource(version, sha(canonical(row)), eventId, parts).also { tx.sources[id] = it }
    }

    private fun memory(c: Connection, actor: VerifiedMemoryPrincipal, id: UUID): MemoryRow = stored {
        val image = owned(c, actor, "memory.memories", " AND id=?") { setObject(4, id) } ?: fail(MemoryFailureCode.MEMORY_UNAVAILABLE)
        val group = group(image); val version = number(image, "version"); val generation = number(image, "generation")
        val created = instant(image, "created_at"); val updated = instant(image, "updated_at")
        val context = image.getValue("original_context").jsonObject
        if (version <= 0 || generation <= 0 || updated < created || MemoryRules.semanticKey(group.kind, context) != group.key) unavailable()
        val deleted = image.getValue("deleted").jsonPrimitive.boolean
        val body = image.getValue("snapshot").takeUnless { it == JsonNull }?.jsonObject
        val override = image.getValue("user_override").takeUnless { it == JsonNull }?.jsonObject
        val deletion = image.getValue("deletion_key").takeUnless { it == JsonNull }?.jsonPrimitive?.content?.let(UUID::fromString)
        if (deleted) { if (body != null || override != null) unavailable() }
        else {
            if (body == null || deletion != null || validator.validateSchema("Memory", bytes(body)) != BodyValidationResult.Valid ||
                body["id"] != JsonPrimitive(id.toString()) || number(body, "version") != version || body["kind"] != JsonPrimitive(group.kind) ||
                instant(body, "createdAt") != created || instant(body, "updatedAt") != updated) unavailable()
            if (override != null) request(override)
        }
        MemoryRow(id, generation, version, group, context, body, override, deleted, deletion, created, updated)
    }

    private fun tombstone(c: Connection, actor: VerifiedMemoryPrincipal, old: MemoryRow, at: Instant, key: UUID?) {
        exec(c, "UPDATE memory.memories SET version=?,snapshot=NULL,user_override=NULL,deleted=true,deletion_key=?,updated_at=? WHERE $OWNER AND id=? AND version=? AND NOT deleted") {
            setLong(1, increment(old.version)); setObject(2, key); instant(3, at); owner(actor, 4); setObject(7, old.id); setLong(8, old.version)
        }
        val actual = memory(c, actor, old.id)
        if (!actual.deleted || actual.deletionKey != key || actual.version != increment(old.version) ||
            actual.generation != old.generation || actual.group != old.group || actual.context != old.context ||
            actual.created != old.created || actual.updated != at) unavailable()
    }

    private fun requireMaterialized(c: Connection, actor: VerifiedMemoryPrincipal, id: UUID, generation: Long,
        group: Group, context: JsonObject, expected: JsonObject, override: JsonObject?) {
        val actual = memory(c, actor, id)
        if (actual.deleted || actual.generation != generation || actual.group != group || actual.context != context ||
            canonical(actual.snapshot ?: JsonNull) != canonical(expected) ||
            canonical(actual.override ?: JsonNull) != canonical(override ?: JsonNull)) unavailable()
    }

    /** Stream all linked receipt images; projection revisions cause gaps, so never equate
     * command count with memory version. No finite history cap can block Forget. */
    private fun linked(c: Connection, actor: VerifiedMemoryPrincipal, id: UUID, consume: (JsonObject, JsonObject) -> Unit) {
        val version = memory(c, actor, id).version
        c.prepareStatement("SELECT to_jsonb(l),to_jsonb(i) FROM memory.memory_commands l JOIN platform.idempotency i " +
            "ON i.principal_scope=l.principal_scope AND i.operation_id=l.operation_id AND i.key=l.command_key " +
            "WHERE l.environment=? AND l.actor_kind=? AND l.principal_id=? AND l.memory_id=? ORDER BY l.operation_id,l.command_key FOR UPDATE OF i FOR SHARE OF l").use { s ->
            s.owner(actor); s.setObject(4, id); s.fetchSize = 32
            s.executeQuery().use { r -> while (r.next()) {
                current(); val link = json(r.getString(1)); val receipt = json(r.getString(2)); requireOwner(link, actor)
                if (number(link, "memory_version") !in 1..version || link["memory_id"] != JsonPrimitive(id.toString()) ||
                    link["principal_scope"] != JsonPrimitive(PrincipalScope(environment, actor.kind, actor.principalId).storageKey) ||
                    link["principal_scope"] != receipt["principal_scope"] || link["operation_id"] != receipt["operation_id"] ||
                    link["command_key"] != receipt["key"] || link["request_hash"] != receipt["request_hash"]) unavailable()
                if (receipt["state"] == JsonPrimitive("completed")) {
                    if (link["response_sha256"] != JsonPrimitive(sha(canonical(receipt.getValue("response_json"))))) unavailable()
                } else if (receipt["state"] != JsonPrimitive("tombstone") || listOf("response_code", "response_json", "response_etag").any { receipt[it] != JsonNull }) unavailable()
                consume(link, receipt)
            } }
        }
    }
    private fun compact(c: Connection, actor: VerifiedMemoryPrincipal, id: UUID, preserve: UUID? = null) {
        linked(c, actor, id) { link, receipt ->
            if (link["operation_id"] == JsonPrimitive("updateMemory") && uuid(link, "command_key") != preserve && receipt["state"] == JsonPrimitive("completed")) {
                exec(c, "UPDATE platform.idempotency SET state='tombstone',response_code=NULL,response_json=NULL,response_etag=NULL," +
                    "tombstoned_at=clock_timestamp(),updated_at=clock_timestamp() WHERE principal_scope=? AND operation_id=? AND key=? AND state='completed'") {
                    setString(1, link.text("principal_scope")); setString(2, link.text("operation_id")); setObject(3, uuid(link, "command_key"))
                }
            }
        }
    }

    private fun begin(c: Connection, actor: VerifiedMemoryPrincipal, initialize: Boolean = false): Trace {
        if (actor.environment != environment) fail(MemoryFailureCode.UNAUTHENTICATED)
        val tx = Trace(c, actor); compatibility(c); authority.lockPrincipal(c, actor); tx.guard.check(c, actor)
        if (initialize) c.prepareStatement("INSERT INTO memory.memory_heads(environment,actor_kind,principal_id,source_revision,projected_revision,memory_revision) VALUES(?,?,?,0,0,0) ON CONFLICT DO NOTHING").use {
            it.owner(actor); it.executeUpdate()
        }
        head(c, actor)
        return tx
    }
    private fun clean(c: Connection, actor: VerifiedMemoryPrincipal): Head = head(c, actor).also {
        if (it.source != it.projected || dirty(c, actor)) fail(MemoryFailureCode.PROJECTION_PENDING)
    }
    private fun head(c: Connection, actor: VerifiedMemoryPrincipal): Head {
        val image = owned(c, actor, "memory.memory_heads") ?: return Head(0, 0, 0)
        val source = number(image, "source_revision"); val projected = number(image, "projected_revision"); val revision = number(image, "memory_revision")
        if (source < 0 || projected !in 0..source || revision < 0) unavailable()
        return Head(source, projected, revision)
    }
    private fun sourceDirty(c: Connection, actor: VerifiedMemoryPrincipal): Boolean = query(c,
        "SELECT EXISTS(SELECT 1 FROM memory.memory_feedback_state WHERE $OWNER AND dirty)", { owner(actor) }) { r -> check(r.next()); r.getBoolean(1) }
    private fun dirty(c: Connection, actor: VerifiedMemoryPrincipal): Boolean {
        sourceIntegrity(c, actor)
        return sourceDirty(c, actor) || query(c, "SELECT EXISTS(SELECT 1 FROM memory.memory_dirty_groups WHERE $OWNER)", {
            owner(actor)
        }) { r -> check(r.next()); r.getBoolean(1) }
    }
    private fun sourceIntegrity(c: Connection, actor: VerifiedMemoryPrincipal) {
        // An empty capped batch is not evidence of a complete source set. Validate both
        // directions, current versions, and owner-global epochs independently of limits.
        val invalid = query(c, "SELECT EXISTS(SELECT 1 FROM memory.feedback f FULL JOIN memory.memory_feedback_state s " +
            "ON s.environment=f.environment AND s.actor_kind=f.actor_kind AND s.principal_id=f.principal_id AND s.feedback_id=f.id " +
            "WHERE coalesce(f.environment,s.environment)=? AND coalesce(f.actor_kind,s.actor_kind)=? AND coalesce(f.principal_id,s.principal_id)=? " +
            "AND (f.id IS NULL OR s.feedback_id IS NULL OR f.version<>s.feedback_version OR " +
            "NOT EXISTS(SELECT 1 FROM memory.memory_heads WHERE $OWNER) OR " +
            "greatest(s.taste_epoch,s.effort_epoch,s.make_again_epoch)>(SELECT source_revision FROM memory.memory_heads WHERE $OWNER)))", {
            owner(actor); owner(actor, 4); owner(actor, 7)
        }) { r -> check(r.next()); r.getBoolean(1) }
        if (invalid) unavailable()
    }
    private fun state(c: Connection, actor: VerifiedMemoryPrincipal, id: UUID) =
        owned(c, actor, "memory.memory_feedback_state", " AND feedback_id=?") { setObject(4, id) } ?: unavailable()

    internal class Pending<T> internal constructor(val result: T, private val completion: (Connection, VerifiedMemoryPrincipal) -> Unit,
        private val finalTime: (Connection, VerifiedMemoryPrincipal, Instant) -> Unit) {
        fun revalidate(c: Connection, actor: VerifiedMemoryPrincipal) = completion(c, actor)
        fun checkAt(c: Connection, actor: VerifiedMemoryPrincipal, at: Instant) = finalTime(c, actor, at)
        override fun toString() = "MemoryPending(<redacted>)"
    }
    private inner class Trace(val c: Connection, val actor: VerifiedMemoryPrincipal) {
        val guard = Guard(c, actor)
        val sources = mutableMapOf<UUID, FeedbackSource>()
        private val observations = linkedMapOf<String, Pair<String, () -> JsonElement>>()
        private val proofs = mutableListOf<Pair<MemoryContextEvidence, String>>()
        private val times = mutableListOf<(Instant) -> Unit>()
        private val failed = AtomicBoolean(false); private val active = AtomicBoolean(false)
        private var observedAt: Instant? = null
        private var cleanBaseline: Head? = null
        private fun cleanSource() {
            cleanBaseline?.let { expected ->
                val actual = this@MemoryStore.clean(c, actor)
                if (actual != expected) unavailable()
            }
        }
        fun clean(): Head = this@MemoryStore.clean(c, actor).also { cleanBaseline = it }
        fun advance() {
            cleanSource()
            val previous = cleanBaseline ?: unavailable()
            val next = increment(previous.revision)
            exec(c, "UPDATE memory.memory_heads SET memory_revision=? WHERE $OWNER") { setLong(1, next); owner(actor, 2) }
            cleanBaseline = Head(previous.source, previous.projected, next)
        }
        fun watchHead() = watch("head") { owned(c, actor, "memory.memory_heads") ?: JsonNull }
        fun watch(key: String, read: () -> JsonElement) {
            val expected = sha(canonical(read()))
            val previous = observations[key]
            if (previous != null && previous.first != expected) unavailable()
            observations.putIfAbsent(key, expected to read)
        }
        fun proof(evidence: MemoryContextEvidence, expected: JsonObject? = null) {
            evidence.revalidate(c, actor); guard.check(c, actor)
            val context = MemoryRules.normalizeContext(evidence.context)
            if (expected != null && canonical(context) != canonical(MemoryRules.normalizeContext(expected))) unavailable()
            proofs += evidence to sha(canonical(context))
        }
        fun memory(id: UUID) = watch("memory:$id") { owned(c, actor, "memory.memories", " AND id=?") { setObject(4, id) } ?: unavailable() }
        fun group(group: Group) {
            watch("sources:${group.key}") { ownerDigest(c, actor, "memory.memory_sources", "feedback_id,signal_key", " AND kind=? AND semantic_key=?") { setString(4, group.kind); setString(5, group.key) } }
            watch("suppressions:${group.key}") { ownerDigest(c, actor, "memory.memory_suppressions", "feedback_id,signal_key,signal_epoch", " AND semantic_key=?") { setString(4, group.key) } }
        }
        fun chain(id: UUID) = watch("chain:$id") {
            val digest = MessageDigest.getInstance("SHA-256"); var count = 0L
            linked(c, actor, id) { link, receipt -> add(digest, buildJsonObject { put("link", link); put("receipt", receipt) }); count++ }
            digestResult(digest, count)
        }
        fun receipt(identity: CommandIdentity): JsonObject {
            fun load() = image(c, "platform.idempotency", "principal_scope=? AND operation_id=? AND key=?") {
                setString(1, identity.scope.storageKey); setString(2, identity.operationId); setObject(3, identity.key)
            } ?: unavailable()
            val value = load(); watch("receipt", ::load); return value
        }
        fun command(identity: CommandIdentity, id: UUID, response: StoredReply) {
            val value = receipt(identity)
            if (value["principal_scope"] != JsonPrimitive(identity.scope.storageKey) || value["operation_id"] != JsonPrimitive(identity.operationId) ||
                value["key"] != JsonPrimitive(identity.key.toString()) || value["request_hash"] != JsonPrimitive(identity.requestHash) || value["state"] != JsonPrimitive("completed") ||
                value["response_code"] != JsonPrimitive(response.status) || value["response_etag"] != (response.etag?.let(::JsonPrimitive) ?: JsonNull) ||
                canonical(value.getValue("response_json")) != canonical(response.body ?: JsonNull)) unavailable()
            fun link() = owned(c, actor, "memory.memory_commands", " AND memory_id=? AND operation_id=? AND command_key=?") {
                setObject(4, id); setString(5, identity.operationId); setObject(6, identity.key)
            } ?: unavailable()
            val link = link()
            if (link["principal_scope"] != JsonPrimitive(identity.scope.storageKey) || link["request_hash"] != JsonPrimitive(identity.requestHash) ||
                number(link, "memory_version") != memory(c, actor, id).version ||
                link["response_sha256"] != JsonPrimitive(sha(canonical(response.body ?: JsonNull)))) unavailable()
            watch("command-link", ::link)
            val expires = instant(value, "expires_at"); times += { if (!expires.isAfter(it)) unavailable() }
        }
        fun cursor(revision: Long, limit: Int, cursor: String) { times += { cursors.decode(actor, revision, limit, cursor, it); Unit } }
        fun event(key: UUID, id: UUID, version: Long, action: String) {
            val draft = EventDraft(UUID.randomUUID(), "memory.preference.changed.v1", 1, "memory", id, version, "memory", UUID.randomUUID().toString(), key,
                buildJsonObject { put("principalId", actor.principalId.toString()); put("memoryId", id.toString()); put("action", action) })
            outbox.append(c, draft)
            val actual = image(c, "platform.outbox", "event_id=?") { setObject(1, draft.eventId) } ?: unavailable()
            if (actual["event_type"] != JsonPrimitive(draft.eventType) || actual["aggregate_type"] != JsonPrimitive("memory") ||
                actual["aggregate_id"] != JsonPrimitive(id.toString()) || number(actual, "aggregate_version") != version ||
                actual["event_id"] != JsonPrimitive(draft.eventId.toString()) || actual["schema_version"] != JsonPrimitive(1) ||
                actual["producer"] != JsonPrimitive("memory") || actual["correlation_id"] != JsonPrimitive(draft.correlationId) ||
                actual["payload"] != draft.data || actual["causation_id"] != JsonPrimitive(key.toString())) unavailable()
            watch("event:${draft.eventId}") { image(c, "platform.outbox", "event_id=?") { setObject(1, draft.eventId) } ?: unavailable() }
        }
        fun <T> finish(result: T): Pending<T> {
            cleanSource(); watchHead()
            watch("dirty") { JsonPrimitive(dirty(c, actor)) }
            authority.lockPrincipal(c, actor); guard.check(c, actor)
            revalidate(c, actor)
            return Pending(result, ::revalidate, ::checkAt)
        }
        fun revalidate(connection: Connection, principal: VerifiedMemoryPrincipal) = checked {
            guard.check(connection, principal)
            for ((proof, hash) in proofs) {
                proof.revalidate(connection, principal); guard.check(connection, principal)
                if (sha(canonical(MemoryRules.normalizeContext(proof.context))) != hash) unavailable()
            }
            cleanSource()
            for ((expected, read) in observations.values) {
                if (sha(canonical(read())) != expected) unavailable()
                guard.check(connection, principal)
            }
            guard.check(connection, principal)
            val at = now(connection); localTime(connection, principal, at); observedAt = at
        }
        fun checkAt(connection: Connection, principal: VerifiedMemoryPrincipal, at: Instant) = checked {
            if (observedAt == null) unavailable()
            localTime(connection, principal, at); observedAt = at
        }
        private fun localTime(connection: Connection, principal: VerifiedMemoryPrincipal, at: Instant) {
            guard.checkLocal(connection, principal)
            if (observedAt?.let { at < it } == true) unavailable()
            times.forEach { it(at) }
        }
        private fun checked(action: () -> Unit) = safe {
            if (failed.get() || !active.compareAndSet(false, true)) unavailable()
            try { action() } catch (failure: Throwable) { failed.set(true); throw failure } finally { active.set(false) }
        }
    }

    internal class Guard(private val connection: Connection, private val actor: VerifiedMemoryPrincipal) {
        private val thread = Thread.currentThread(); private val transaction: Long
        init { checkLocal(connection, actor); isolation(connection); transaction = transaction(connection) }
        fun check(c: Connection, actual: VerifiedMemoryPrincipal) {
            checkLocal(c, actual); isolation(c); if (transaction(c) != transaction) unavailable()
        }
        fun checkLocal(c: Connection, actual: VerifiedMemoryPrincipal) {
            current()
            if (c !== connection || actual !== actor || Thread.currentThread() !== thread || c.isClosed || c.autoCommit) fail(MemoryFailureCode.UNAUTHENTICATED)
        }
    }
    private fun request(body: JsonObject): JsonObject {
        val copy = Json.parseToJsonElement(body.toString()).jsonObject
        if (copy.isEmpty() || validator.validateRequest("updateMemory", bytes(copy), "application/json") != BodyValidationResult.Valid) fail(MemoryFailureCode.INPUT_INVALID)
        copy["value"]?.let { MemoryRules.validateValue(it.jsonPrimitive.content) }
        val context = copy["context"]?.let { MemoryRules.normalizeContext(it.jsonObject) }
        return if (context == null) copy else JsonObject(copy + ("context" to context))
    }
    private fun reply(operation: String, code: Int, body: JsonObject?, version: Long? = null): StoredReply {
        val bytes = body?.let(::bytes)
        if ((bytes?.size ?: 0) > policy.maxResponseBytes) fail(MemoryFailureCode.RESPONSE_TOO_LARGE)
        if (validator.validateResponse(operation, code, bytes, if (body == null) null else "application/json") != BodyValidationResult.Valid) unavailable()
        return StoredReply(code, body, version?.let { "\"$it\"" })
    }
    private fun page(items: List<JsonObject>, next: String?, at: Instant) = buildJsonObject {
        put("items", JsonArray(items)); put("nextCursor", next?.let(::JsonPrimitive) ?: JsonNull); put("serverTime", at.toString())
    }
    private fun compatibility(c: Connection) {
        for ((version, name) in listOf(1 to "durable_platform", 22 to "private_feedback", 24 to "preference_memories")) {
            val checksum = MemoryStore::class.java.getResourceAsStream("/db/migration/V${version.toString().padStart(3, '0')}__$name.sql")
                ?.use { sha(it.readBytes().decodeToString()) } ?: fail(MemoryFailureCode.NOT_CONFIGURED)
            query(c, "SELECT checksum FROM platform.schema_migrations WHERE version=?", { setInt(1, version) }) { r ->
                if (!r.next() || r.getString(1) != checksum || r.next()) fail(MemoryFailureCode.NOT_CONFIGURED)
            }
        }
    }
    private fun sourceRows(c: Connection, actor: VerifiedMemoryPrincipal, suffix: String, bind: PreparedStatement.() -> Unit): List<JsonObject> =
        query(c, "SELECT to_jsonb(s) FROM memory.memory_sources s WHERE $OWNER$suffix ORDER BY signal_key", { owner(actor); bind() }) {
            r -> buildList { while (r.next()) { if (size >= 3) unavailable(); add(json(r.getString(1))) } }
        }
    private fun committed(c: Connection, actor: VerifiedMemoryPrincipal, table: String, suffix: String,
        bind: PreparedStatement.() -> Unit): JsonObject = query(c,
        "SELECT to_jsonb(r),(r.xmin::text::bigint<>mod(txid_current(),4294967296)) FROM $table r WHERE $OWNER$suffix FOR SHARE", { owner(actor); bind() }) {
        r -> if (!r.next() || !r.getBoolean(2)) unavailable(); json(r.getString(1)).also { requireOwner(it, actor); if (r.next()) unavailable() }
    }
    private fun owned(c: Connection, actor: VerifiedMemoryPrincipal, table: String, suffix: String = "", bind: PreparedStatement.() -> Unit = {}): JsonObject? =
        image(c, table, OWNER + suffix) { owner(actor); bind() }?.also { requireOwner(it, actor) }
    private fun image(c: Connection, table: String, where: String, bind: PreparedStatement.() -> Unit): JsonObject? =
        query(c, "SELECT to_jsonb(r) FROM $table r WHERE $where FOR UPDATE", bind) { r ->
            if (!r.next()) null else json(r.getString(1)).also { if (r.next()) unavailable() }
        }
    private fun ownerDigest(c: Connection, actor: VerifiedMemoryPrincipal, table: String, order: String,
        suffix: String = "", bind: PreparedStatement.() -> Unit = {}): JsonObject {
        val digest = MessageDigest.getInstance("SHA-256"); var count = 0L
        c.prepareStatement("SELECT to_jsonb(r) FROM $table r WHERE $OWNER$suffix ORDER BY $order FOR SHARE").use { s ->
            s.owner(actor); s.bind(); s.fetchSize = 32; s.executeQuery().use { r -> while (r.next()) {
                current(); val row = json(r.getString(1)); requireOwner(row, actor); add(digest, row); count = increment(count)
            } }
        }
        return digestResult(digest, count)
    }
    private fun requireOwner(row: JsonObject, actor: VerifiedMemoryPrincipal) {
        if (row["environment"] != JsonPrimitive(environment) || row["actor_kind"] != JsonPrimitive(actor.kind.name.lowercase()) ||
            row["principal_id"] != JsonPrimitive(actor.principalId.toString())) unavailable()
    }
    private fun PreparedStatement.owner(actor: VerifiedMemoryPrincipal, offset: Int = 1) {
        setString(offset, environment); setString(offset + 1, actor.kind.name.lowercase()); setObject(offset + 2, actor.principalId)
    }
    private fun PreparedStatement.instant(index: Int, at: Instant) = setObject(index, at.atOffset(ZoneOffset.UTC))
    private fun exec(c: Connection, sql: String, bind: PreparedStatement.() -> Unit) = c.prepareStatement(sql).use { it.bind(); check(it.executeUpdate() == 1) }
    private fun <T> query(c: Connection, sql: String, bind: PreparedStatement.() -> Unit, read: (ResultSet) -> T): T = c.prepareStatement(sql).use { it.bind(); it.executeQuery().use(read) }
    private fun group(row: JsonObject) = Group(row.text("kind"), row.text("semantic_key"))
    private data class Group(val kind: String, val key: String)
    private data class Head(val source: Long, val projected: Long, val revision: Long)
    private class FeedbackSource(val version: Long, val sha: String, val eventId: UUID, val parts: List<MemoryContribution>)
    private class MemoryRow(val id: UUID, val generation: Long, val version: Long, val group: Group, val context: JsonObject,
        val snapshot: JsonObject?, val override: JsonObject?, val deleted: Boolean, val deletionKey: UUID?, val created: Instant, val updated: Instant)
    override fun toString() = "MemoryStore(<redacted>)"
    companion object { private const val OWNER = "environment=? AND actor_kind=? AND principal_id=?"; private val validator by lazy { ContractBodyValidator.bundled() } }
}

private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
private fun canonical(value: JsonElement) = feedbackCanonical(value)
private fun sha(text: String) = feedbackSha(text)
private fun bytes(value: JsonElement) = value.toString().encodeToByteArray(throwOnInvalidSequence = true)
private fun number(value: JsonObject, key: String) = value.getValue(key).jsonPrimitive.content.toBigDecimal().longValueExact()
private fun uuid(value: JsonObject, key: String) = UUID.fromString(value.text(key))
private fun instant(value: JsonObject, key: String) = OffsetDateTime.parse(value.text(key)).toInstant()
private fun increment(value: Long): Long = if (value in 0 until Long.MAX_VALUE) value + 1 else unavailable()
private fun version(value: String): Long = value.takeIf { it.matches(Regex("\"[1-9][0-9]*\"")) }?.removeSurrounding("\"")?.toLongOrNull() ?: fail(MemoryFailureCode.INPUT_INVALID)
private fun current() { if (Thread.currentThread().isInterrupted) throw InterruptedException("Memory operation interrupted") }
private fun isolation(c: Connection) { if (c.transactionIsolation != Connection.TRANSACTION_READ_COMMITTED) unavailable() }
private fun transaction(c: Connection): Long = c.createStatement().use { s -> s.executeQuery("SELECT txid_current()").use { r -> check(r.next()); r.getLong(1).also { check(!r.next()) } } }
private fun now(c: Connection): Instant = c.createStatement().use { s -> s.executeQuery("SELECT date_trunc('milliseconds',clock_timestamp())").use { r -> check(r.next()); r.getObject(1, OffsetDateTime::class.java).toInstant().also { check(!r.next()) } } }
private fun add(digest: MessageDigest, value: JsonElement) { val data = bytes(JsonPrimitive(canonical(value))); digest.update(data.size.toString().toByteArray()); digest.update(':'.code.toByte()); digest.update(data) }
private fun digestResult(digest: MessageDigest, count: Long) = buildJsonObject { put("count", count); put("sha256", digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }) }
private fun unavailable(): Nothing = fail(MemoryFailureCode.STORAGE_UNAVAILABLE)
private fun fail(code: MemoryFailureCode): Nothing = throw MemoryFailure(code)
private fun <T> stored(action: () -> T): T = try { action() } catch (failure: MemoryFailure) { if (failure.code == MemoryFailureCode.INPUT_INVALID) unavailable() else throw failure }
private fun <T> safe(action: () -> T): T = try { current(); action() }
    catch (failure: MemoryFailure) { throw failure }
    catch (failure: FeedbackFailure) { throw failure }
    catch (failure: GuestSessionFailure) { throw failure }
    catch (failure: PlanningServiceFailure) { throw failure }
    catch (failure: RecipeCatalogFailure) { throw failure }
    catch (failure: IngredientCatalogFailure) { throw failure }
    catch (failure: CommitOutcomeUnknown) { throw failure }
    catch (failure: CancellationException) { throw failure }
    catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
    catch (failure: SQLException) { current(); if (failure.sqlState in setOf("40001", "40P01")) throw failure else unavailable() }
    catch (_: Exception) { current(); unavailable() }
