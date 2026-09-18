package com.feedme.server.cooking

import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.guest.GuestSessionFailure
import com.feedme.server.memory.CookingMakeAgainEffect
import com.feedme.server.memory.FeedbackFailure
import com.feedme.server.memory.SavedRecipeFailure
import com.feedme.server.planning.*
import java.math.BigDecimal
import java.nio.charset.CharacterCodingException
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

/**
 * Owner-scoped PostgreSQL cooking component. Each state transition, per-device cursor, private
 * step event, original-key receipt and redacted outbox fact commit together. No GET initialization,
 * timer delivery, save, feedback, pantry consumption or publication follows these operations.
 * The scalar sequence is the last accepted aggregate sequence; fresh mutations require +1 and
 * cannot regress the current verified device's cursor. Completion is status-only, not an inferred
 * final progress/safety report. Current identity and pinned recipe rights precede cached disclosure.
 */
class CookingStore internal constructor(val environment: String, private val transactions: PgTransactions,
    private val authority: CookingAuthority, private val planReader: CookingPlanReader, val policy: CookingServicePolicy) {
    /** Preserve the existing public v1 composition and its real planning authority. */
    constructor(environment: String, transactions: PgTransactions, authority: CookingAuthority,
        plans: PlansStore, policy: CookingServicePolicy) : this(environment, transactions, authority,
        CookingPlanReader { c, actor, id, use, session ->
            plans.lockCookingPlan(c, VerifiedPlanningPrincipal(environment, actor.kind, actor.principalId,
                actor.deviceSessionId), id, use, session)
        }, policy) {
        require(plans.environment == environment)
    }
    private val commands = DurableCommands(transactions)
    private val outbox = OutboxStore(transactions)
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
    }

    fun createCookSession(actor: VerifiedCookingPrincipal, key: UUID, body: JsonObject): CommandResult =
        createCookSessionIn(null, actor, key, body).result

    internal fun createCookSession(connection: Connection, actor: VerifiedCookingPrincipal,
        key: UUID, body: JsonObject): Pending<CommandResult> = createCookSessionIn(connection, actor, key, body)

    private fun createCookSessionIn(connection: Connection?, actor: VerifiedCookingPrincipal,
        key: UUID, body: JsonObject): Pending<CommandResult> {
        val input = request("createCookSession", body); val planId = uuid(input, "planId")
        val sequence = input["deviceSequence"]?.let { integer(it, CookingFailureCode.INPUT_INVALID) } ?: 0
        val sessionId = UUID.randomUUID()
        return command(connection, actor, "createCookSession", key, null, null, input) { c, identity ->
            authority.requireNewCookingEnabled(c, actor); current()
            val pin = plan(c, actor, planId, CookingPlanUse.NEW_SELECTION)
            val steps = steps(pin.document); val at = now(c)
            val snapshot = buildJsonObject {
                put("id", sessionId.toString()); put("version", 1); put("createdAt", at.toString()); put("updatedAt", at.toString())
                put("planId", planId.toString()); put("status", "active"); put("currentStepId", steps.first())
                put("completedStepIds", JsonArray(emptyList())); put("deviceSequence", sequence); put("timers", JsonArray(emptyList()))
            }
            reply("createCookSession", 201, snapshot)
            current()
            exec(c, "INSERT INTO cooking.cook_sessions(environment,actor_kind,principal_id,id,plan_id,version,status,device_sequence,snapshot," +
                "plan_snapshot_text,plan_snapshot_hash,plan_proof_hash,plan_evidence_hash,created_at,updated_at,expires_at) " +
                "VALUES(?,?,?,?,?,1,'active',?,?::jsonb,?,?,?,?,?,?,?)") {
                owner(actor); setObject(4, sessionId); setObject(5, planId); setLong(6, sequence); setString(7, snapshot.toString())
                setString(8, pin.snapshotText); setString(9, pin.snapshotHash); setString(10, pin.proofHash); setString(11, pin.evidenceHash)
                setObject(12, time(at)); setObject(13, time(at)); setObject(14, time(at.plusSeconds(policy.sessionRetentionSeconds.toLong())))
            }
            val appended = event(c, actor, sessionId, planId, key, identity.requestHash, input, "started", sequence, 1, at)
            val persisted = row(c, actor, sessionId); live(c, persisted)
            requirePin(persisted, planId, pin)
            if (canonical(persisted.body) != canonical(snapshot)) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
            Mutation(reply("createCookSession", 201, persisted.body).also { current() }, appended)
        }
    }

    fun getCookSession(actor: VerifiedCookingPrincipal, sessionId: UUID): StoredReply =
        getCookSessionIn(null, actor, sessionId).result

    internal fun getCookSession(connection: Connection, actor: VerifiedCookingPrincipal,
        sessionId: UUID): Pending<StoredReply> = getCookSessionIn(connection, actor, sessionId)

    private fun getCookSessionIn(connection: Connection?, actor: VerifiedCookingPrincipal,
        sessionId: UUID): Pending<StoredReply> = read(connection, actor, sessionId) { c ->
            val (row, _) = authorized(c, actor, sessionId)
            reply("getCookSession", 200, row.body).also { live(c, row); current() }
        }

    fun updateCookSession(actor: VerifiedCookingPrincipal, key: UUID, sessionId: UUID, ifMatch: String,
        body: JsonObject): CommandResult = updateCookSessionIn(null, actor, key, sessionId, ifMatch, body).result

    internal fun updateCookSession(connection: Connection, actor: VerifiedCookingPrincipal, key: UUID,
        sessionId: UUID, ifMatch: String, body: JsonObject): Pending<CommandResult> =
        updateCookSessionIn(connection, actor, key, sessionId, ifMatch, body)

    private fun updateCookSessionIn(connection: Connection?, actor: VerifiedCookingPrincipal, key: UUID,
        sessionId: UUID, ifMatch: String, body: JsonObject): Pending<CommandResult> {
        val input = request("updateCookSession", body); val expected = version(ifMatch)
        val sequence = integer(input.getValue("deviceSequence"), CookingFailureCode.INPUT_INVALID)
        return command(connection, actor, "updateCookSession", key, sessionId, ifMatch, input) { c, identity ->
            val (old, pin) = authorized(c, actor, sessionId)
            mutable(old); if (old.version != expected) fail(CookingFailureCode.VERSION_CONFLICT)
            nextSequence(c, actor, old, sequence); unusedCommand(c, actor, sessionId, key)
            val at = now(c); val changed = JsonObject(old.body + input + mapOf("version" to JsonPrimitive(increment(old.version)), "updatedAt" to JsonPrimitive(at.toString())))
            progress(changed, steps(pin.document), CookingFailureCode.INPUT_INVALID)
            input["personalNotes"]?.let { authority.validatePersonalNotes(c, actor, it.jsonArray); current() }
            reply("updateCookSession", 200, changed)
            update(c, actor, old, changed, at)
            val appended = event(c, actor, sessionId, old.planId, key, identity.requestHash, input, "progressed", sequence, old.version + 1, at)
            val persisted = row(c, actor, sessionId); live(c, persisted)
            // JSONB can normalize equivalent JSON numbers (for example 1e0 to 1).
            // Compare state values canonically; immutable recipe pin text stays byte-exact.
            if (canonical(persisted.body) != canonical(changed)) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
            Mutation(reply("updateCookSession", 200, persisted.body).also { current() }, appended)
        }
    }

    fun completeCookSession(actor: VerifiedCookingPrincipal, key: UUID, sessionId: UUID,
        body: JsonObject): CommandResult = completeCookSessionIn(null, actor, key, sessionId, body).result

    internal fun completeCookSession(connection: Connection, actor: VerifiedCookingPrincipal, key: UUID,
        sessionId: UUID, body: JsonObject): Pending<CommandResult> = completeCookSessionIn(connection, actor, key, sessionId, body)

    /** Explicit caller-owned composition only; the existing/public paths have no effect. */
    internal fun completeCookSession(connection: Connection, actor: VerifiedCookingPrincipal, key: UUID,
        sessionId: UUID, body: JsonObject, effect: CookingMakeAgainEffect): Pending<CommandResult> =
        completeCookSessionIn(connection, actor, key, sessionId, body, effect)

    private fun completeCookSessionIn(connection: Connection?, actor: VerifiedCookingPrincipal, key: UUID,
        sessionId: UUID, body: JsonObject, effect: CookingMakeAgainEffect? = null): Pending<CommandResult> {
        val input = request("completeCookSession", body)
        val makeAgain = input.getValue("makeAgain").jsonPrimitive.boolean
        if (makeAgain && effect == null) fail(CookingFailureCode.NOT_CONFIGURED)
        val sequence = integer(input.getValue("deviceSequence"), CookingFailureCode.INPUT_INVALID)
        return command(connection, actor, "completeCookSession", key, sessionId, null, input,
            completionEffect = if (makeAgain) effect else null) { c, identity ->
            val (old, _) = authorized(c, actor, sessionId)
            mutable(old); nextSequence(c, actor, old, sequence); unusedCommand(c, actor, sessionId, key)
            val at = now(c)
            // No If-Match or final progress is present in Completion. Preserve every locked field.
            // finishedAtClient stays untrusted private step-event provenance, not accepted DB time.
            val changed = JsonObject(old.body + mapOf("status" to JsonPrimitive("completed"), "deviceSequence" to JsonPrimitive(sequence),
                "version" to JsonPrimitive(increment(old.version)), "updatedAt" to JsonPrimitive(at.toString()), "completedAt" to JsonPrimitive(at.toString())))
            reply("completeCookSession", 200, changed)
            update(c, actor, old, changed, at)
            val appended = event(c, actor, sessionId, old.planId, key, identity.requestHash, input, "completed", sequence, old.version + 1, at)
            val persisted = row(c, actor, sessionId); live(c, persisted)
            if (canonical(persisted.body) != canonical(changed)) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
            if (makeAgain) { current(); effect!!.applied(c, actor, key, input, persisted.body); current() }
            Mutation(reply("completeCookSession", 200, persisted.body).also { current() }, appended)
        }
    }

    private fun command(connection: Connection?, actor: VerifiedCookingPrincipal, operation: String, key: UUID,
        sessionId: UUID?, ifMatch: String?, input: JsonObject,
        completionEffect: CookingMakeAgainEffect? = null,
        mutate: (Connection, CommandIdentity) -> Mutation): Pending<CommandResult> = safe(connection != null) {
        checkActor(actor); current()
        val identity = CommandIdentity(PrincipalScope(environment, actor.kind, actor.principalId), operation, key,
            sessionId?.let { mapOf("sessionId" to it.toString()) } ?: emptyMap(), body = input, ifMatch = ifMatch)
        inTransaction(connection) { c ->
            val bound = connection?.let { Bound(c, actor) }
            var appended: EventDraft? = null
            val result = commands.executeInTransaction(c, identity, { authority.lockPrincipal(it, actor); current() }, {}, { db, cached ->
                val id = sessionId ?: uuid(cached.body!!.jsonObject, "id")
                val (currentRow, _) = authorized(db, actor, id)
                val kind = when (operation) { "createCookSession" -> "started"; "updateCookSession" -> "progressed"; else -> "completed" }
                query(db, "SELECT kind,device_identity,request_hash,session_version FROM cooking.step_events WHERE environment=? AND actor_kind=? AND principal_id=? AND session_id=? AND command_id=?", {
                    owner(actor); setObject(4, id); setObject(5, key)
                }) { r ->
                    if (!r.next() || r.getString(1) != kind || r.getString(3) != identity.requestHash) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
                    if (r.getObject(2, UUID::class.java) != actor.sequenceIdentity) fail(CookingFailureCode.COMMAND_CONFLICT)
                    if (r.getLong(4) != currentRow.version) fail(CookingFailureCode.VERSION_CONFLICT)
                }
                val actual = reply(operation, if (operation == "createCookSession") 201 else 200, currentRow.body)
                if (cached.status != actual.status || cached.etag != actual.etag || cached.body != actual.body) fail(CookingFailureCode.VERSION_CONFLICT)
                live(db, currentRow); current()
                completionEffect?.let { it.replayed(db, actor, key, input, currentRow.body); current() }
            }, { db -> mutate(db, identity).also { appended = it.event }.reply })
            // Only caller-owned operations retain a rejecting completion. Public v1 calls
            // preserve their original checks and never acquire these extra projection locks.
            val expected = if (bound == null) null else when (result) {
                is CommandResult.Applied -> capture(c, actor, operation, identity, input, sessionId, result.reply, appended)
                is CommandResult.Replayed -> capture(c, actor, operation, identity, input, sessionId, result.reply, null)
                else -> null // Receipt refusal is not a successful domain result.
            }
            // Re-lock the already-held principal after all receipt/domain work, including
            // replay or receipt refusal, so a wait cannot extend current session authority.
            current(); authority.lockPrincipal(c, actor); current()
            pending(result, bound, expected)
        }
    }

    private fun read(connection: Connection?, actor: VerifiedCookingPrincipal, sessionId: UUID,
        action: (Connection) -> StoredReply): Pending<StoredReply> = safe(connection != null) {
        checkActor(actor); current(); inTransaction(connection) { c ->
            val bound = connection?.let { Bound(c, actor) }
            authority.lockPrincipal(c, actor); current()
            val result = action(c)
            val expected = if (bound == null) null else capture(c, actor, "getCookSession", null, null, sessionId, result, null)
            current(); authority.lockPrincipal(c, actor); current()
            pending(result, bound, expected)
        }
    }
    private fun <T> inTransaction(connection: Connection?, action: (Connection) -> T): T {
        if (connection == null) return transactions.run(action)
        if (connection.isClosed || connection.autoCommit) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
        val result = action(connection)
        if (connection.isClosed || connection.autoCommit) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
        return result
    }
    private fun authorized(c: Connection, actor: VerifiedCookingPrincipal, id: UUID): Pair<Row, CookingPlanSnapshot> {
        // Read only enough owned metadata to find the lineage. Re-read the locked row after rights.
        val planId = query(c, "SELECT plan_id,expires_at>clock_timestamp() FROM cooking.cook_sessions WHERE environment=? AND actor_kind=? AND principal_id=? AND id=?", {
            owner(actor); setObject(4, id)
        }) { if (!it.next()) fail(CookingFailureCode.COOK_SESSION_UNAVAILABLE)
            if (!it.getBoolean(2)) fail(CookingFailureCode.SESSION_EXPIRED); it.getObject(1, UUID::class.java) }
        val pin = plan(c, actor, planId, CookingPlanUse.EXISTING_PIN, id)
        val row = row(c, actor, id); live(c, row)
        requirePin(row, planId, pin)
        progress(row.body, steps(pin.document), CookingFailureCode.STORAGE_UNAVAILABLE)
        current(); return row to pin
    }
    private fun plan(c: Connection, actor: VerifiedCookingPrincipal, id: UUID, use: CookingPlanUse, sessionId: UUID? = null) =
        planReader.lock(c, actor, id, use, sessionId).also { current() }

    private fun requirePin(row: Row, planId: UUID, pin: CookingPlanSnapshot) {
        if (row.planId != planId || row.planText != pin.snapshotText || row.planHash != pin.snapshotHash ||
            row.proofHash != pin.proofHash || row.evidenceHash != pin.evidenceHash) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
    }

    private fun row(c: Connection, actor: VerifiedCookingPrincipal, id: UUID): Row = query(c,
        "SELECT * FROM cooking.cook_sessions WHERE environment=? AND actor_kind=? AND principal_id=? AND id=? FOR UPDATE", { owner(actor); setObject(4, id) }) { r ->
        if (!r.next()) fail(CookingFailureCode.COOK_SESSION_UNAVAILABLE)
        val body = Json.parseToJsonElement(r.getString("snapshot")).jsonObject
        val version = r.getLong("version"); val planId = r.getObject("plan_id", UUID::class.java)
        if (uuid(body, "id") != id || uuid(body, "planId") != planId || integer(body.getValue("version"), CookingFailureCode.STORAGE_UNAVAILABLE) != version ||
            body.text("status") != r.getString("status") || integer(body.getValue("deviceSequence"), CookingFailureCode.STORAGE_UNAVAILABLE) != r.getLong("device_sequence") ||
            body.text("createdAt") != instant(r, "created_at").toString() || body.text("updatedAt") != instant(r, "updated_at").toString()) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
        reply("getCookSession", 200, body)
        val text = r.getString("plan_snapshot_text"); if (digest(text.toByteArray(Charsets.UTF_8)) != r.getString("plan_snapshot_hash")) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
        Row(id, planId, version, body, text, r.getString("plan_snapshot_hash"), r.getString("plan_proof_hash"), r.getString("plan_evidence_hash"), instant(r, "expires_at"))
    }
    private fun update(c: Connection, actor: VerifiedCookingPrincipal, old: Row, body: JsonObject, at: Instant) {
        current()
        exec(c, "UPDATE cooking.cook_sessions SET version=?,status=?,device_sequence=?,snapshot=?::jsonb,updated_at=? WHERE environment=? AND actor_kind=? AND principal_id=? AND id=? AND version=?") {
            setLong(1, integer(body.getValue("version"), CookingFailureCode.STORAGE_UNAVAILABLE)); setString(2, body.text("status"))
            setLong(3, integer(body.getValue("deviceSequence"), CookingFailureCode.STORAGE_UNAVAILABLE)); setString(4, body.toString()); setObject(5, time(at))
            owner(actor, 6); setObject(9, old.id); setLong(10, old.version)
        }
    }
    private fun nextSequence(c: Connection, actor: VerifiedCookingPrincipal, row: Row, sequence: Long) {
        val previous = integer(row.body.getValue("deviceSequence"), CookingFailureCode.STORAGE_UNAVAILABLE)
        if (previous == Long.MAX_VALUE || sequence != previous + 1) fail(CookingFailureCode.SEQUENCE_CONFLICT)
        query(c, "SELECT device_sequence FROM cooking.device_cursors WHERE environment=? AND actor_kind=? AND principal_id=? AND session_id=? AND device_identity=? FOR UPDATE", {
            owner(actor); setObject(4, row.id); setObject(5, actor.sequenceIdentity)
        }) { if (it.next() && sequence <= it.getLong(1)) fail(CookingFailureCode.SEQUENCE_CONFLICT) }
    }
    private fun unusedCommand(c: Connection, actor: VerifiedCookingPrincipal, sessionId: UUID, key: UUID) = query(c,
        "SELECT 1 FROM cooking.step_events WHERE environment=? AND actor_kind=? AND principal_id=? AND session_id=? AND command_id=?", {
            owner(actor); setObject(4, sessionId); setObject(5, key)
        }) { if (it.next()) fail(CookingFailureCode.COMMAND_CONFLICT) }

    private fun event(c: Connection, actor: VerifiedCookingPrincipal, sessionId: UUID, planId: UUID, key: UUID, hash: String,
        input: JsonObject, kind: String, sequence: Long, version: Long, at: Instant): EventDraft {
        current()
        exec(c, "INSERT INTO cooking.device_cursors(environment,actor_kind,principal_id,session_id,device_identity,device_sequence) VALUES(?,?,?,?,?,?) " +
            "ON CONFLICT(environment,actor_kind,principal_id,session_id,device_identity) DO UPDATE SET device_sequence=EXCLUDED.device_sequence WHERE cooking.device_cursors.device_sequence<EXCLUDED.device_sequence") {
            owner(actor); setObject(4, sessionId); setObject(5, actor.sequenceIdentity); setLong(6, sequence)
        }
        exec(c, "INSERT INTO cooking.step_events(environment,actor_kind,principal_id,session_id,command_id,device_identity,device_sequence,session_version,kind,request_hash,payload,accepted_at) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?,?::jsonb,?)") {
            owner(actor); setObject(4, sessionId); setObject(5, key); setObject(6, actor.sequenceIdentity); setLong(7, sequence)
            setLong(8, version); setString(9, kind); setString(10, hash); setString(11, input.toString()); setObject(12, time(at))
        }
        val draft = EventDraft(UUID.randomUUID(), "cooking.session.$kind.v1", 1, "cook_session", sessionId, version,
            "cooking", key.toString(), key, buildJsonObject {
                put("principalId", actor.principalId.toString()); put("sessionId", sessionId.toString())
                if (kind == "progressed") put("deviceSequence", sequence) else put("planId", planId.toString())
            })
        outbox.append(c, draft)
        current()
        return draft
    }

    /** Provisional internal result, not a committed reply or authentication grant. The actual
     * transaction owner must keep this private and call revalidate after its final authority;
     * only that owner's successful commit permits disclosure. No mutable invocation state
     * lives on the reusable CookingStore. A failed completion cannot later be revived. */
    internal class Pending<T> internal constructor(val result: T,
        private val completion: (Connection, VerifiedCookingPrincipal) -> Unit,
        private val finalTime: (Connection, VerifiedCookingPrincipal, Instant) -> Unit) {
        fun revalidate(connection: Connection, actor: VerifiedCookingPrincipal) = completion(connection, actor)
        /** SQL-free rejection only, supplied the real owner's last accepted DB time after
         * all final identity/source/domain waits. Never an alternate authorization path. */
        fun checkAt(connection: Connection, actor: VerifiedCookingPrincipal, acceptedAt: Instant) = finalTime(connection, actor, acceptedAt)
        override fun toString() = "CookingPending(<redacted>)"
    }

    private fun <T> pending(result: T, bound: Bound?, expected: Captured?): Pending<T> {
        val refused = AtomicBoolean(false)
        val active = AtomicBoolean(false)
        val clock = bound?.clock(expected?.expires, expected?.receiptExpires)
        fun checked(action: () -> Unit): Unit = safe(true) {
            if (refused.get() || !active.compareAndSet(false, true)) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
            try { action() } catch (failure: Throwable) { refused.set(true); throw failure }
            finally { active.set(false) }
        }
        return Pending(result, { c, actor -> checked {
            // Public operations never expose this Pending or gain a post-commit path.
            if (bound == null || clock == null) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
            bound.check(c, actor)
            val observed = if (expected == null) now(c) else revalidate(c, actor, expected, bound)
            // No SQL follows this final clock observation.
            bound.checkLocal(c, actor); clock.observe(c, actor, observed)
        } }, { c, actor, at -> checked {
            if (bound == null || clock == null) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
            bound.checkLocal(c, actor); clock.checkAt(c, actor, at)
        } })
    }

    /** A rejecting deadline fence only, not a session, pin, source or receipt grant.
     * The owning Pending records time only after its actual SQL revalidation succeeds. */
    internal class CompletionClock(private val connection: Connection, private val actor: VerifiedCookingPrincipal,
        private val sessionExpires: Instant?, private val receiptExpires: Instant?) {
        private val thread = Thread.currentThread()
        private val failed = AtomicBoolean(false)
        private var observedAt: Instant? = null
        fun observe(c: Connection, actual: VerifiedCookingPrincipal, at: Instant) = checked {
            local(c, actual); time(at); observedAt = at
        }
        fun checkAt(c: Connection, actual: VerifiedCookingPrincipal, at: Instant) = checked {
            local(c, actual)
            if (observedAt == null) deny(CookingFailureCode.STORAGE_UNAVAILABLE)
            time(at); observedAt = at
        }
        private fun local(c: Connection, actual: VerifiedCookingPrincipal) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Cooking operation interrupted")
            // Pgjdbc transactionIsolation is queryful and deliberately absent here.
            if (Thread.currentThread() !== thread || c !== connection || actual !== actor || c.isClosed || c.autoCommit)
                deny(CookingFailureCode.UNAUTHENTICATED)
        }
        private fun time(at: Instant) {
            if (observedAt?.let { at < it } == true) deny(CookingFailureCode.STORAGE_UNAVAILABLE)
            if (sessionExpires?.isAfter(at) == false) deny(CookingFailureCode.SESSION_EXPIRED)
            if (receiptExpires?.isAfter(at) == false) deny(CookingFailureCode.STORAGE_UNAVAILABLE)
        }
        private fun checked(action: () -> Unit) {
            if (failed.get()) deny(CookingFailureCode.STORAGE_UNAVAILABLE)
            try { action() } catch (failure: Throwable) { failed.set(true); throw failure }
        }
        private fun deny(code: CookingFailureCode): Nothing = throw CookingFailure(code)
    }

    private class Mutation(val reply: StoredReply, val event: EventDraft)
    private class Captured(val sessionId: UUID, val session: JsonObject, val expires: Instant,
        val identity: CommandIdentity?, val receipt: JsonObject?, val receiptExpires: Instant?,
        val step: JsonObject?, val cursor: JsonObject?, val outbox: JsonObject?, val eventId: UUID?)

    private fun capture(c: Connection, actor: VerifiedCookingPrincipal, operation: String, identity: CommandIdentity?,
        input: JsonObject?, requestedId: UUID?, response: StoredReply, appended: EventDraft?): Captured {
        current()
        val id = requestedId ?: uuid(response.body!!.jsonObject, "id")
        val retained = row(c, actor, id)
        val expected = reply(operation, if (operation == "createCookSession") 201 else 200, retained.body)
        if (response.status != expected.status || response.etag != expected.etag || response.body != expected.body)
            fail(CookingFailureCode.STORAGE_UNAVAILABLE)
        val session = sessionImage(c, actor, id)
        if (session["snapshot"] != retained.body || session["plan_snapshot_text"] != JsonPrimitive(retained.planText) ||
            session["plan_snapshot_hash"] != JsonPrimitive(retained.planHash) || session["plan_proof_hash"] != JsonPrimitive(retained.proofHash) ||
            session["plan_evidence_hash"] != JsonPrimitive(retained.evidenceHash) || storedInstant(session, "expires_at") != retained.expires)
            fail(CookingFailureCode.STORAGE_UNAVAILABLE)
        var receipt: JsonObject? = null
        var step: JsonObject? = null
        var cursor: JsonObject? = null
        var receiptExpires: Instant? = null
        if (identity != null) {
            receipt = receiptImage(c, identity)
            if (receipt["request_hash"] != JsonPrimitive(identity.requestHash) || receipt["state"] != JsonPrimitive("completed") ||
                receipt["response_code"] != JsonPrimitive(response.status) || receipt["response_json"] != response.body ||
                receipt["response_etag"] != JsonPrimitive(response.etag)) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
            receiptExpires = storedInstant(receipt, "expires_at")
            step = stepImage(c, actor, id, identity.key)
            val kind = when (operation) { "createCookSession" -> "started"; "updateCookSession" -> "progressed"; else -> "completed" }
            val sequence = integer(retained.body.getValue("deviceSequence"), CookingFailureCode.STORAGE_UNAVAILABLE)
            if (step["kind"] != JsonPrimitive(kind) || step["device_identity"] != JsonPrimitive(actor.sequenceIdentity.toString()) ||
                step["request_hash"] != JsonPrimitive(identity.requestHash) || step["session_version"] != JsonPrimitive(retained.version) ||
                step["device_sequence"] != JsonPrimitive(sequence) || input == null ||
                canonical(step.getValue("payload")) != canonical(input) ||
                storedInstant(step, "accepted_at") != Instant.parse(retained.body.text("updatedAt")))
                fail(CookingFailureCode.STORAGE_UNAVAILABLE)
            cursor = cursorImage(c, actor, id)
            if (cursor["device_sequence"] != JsonPrimitive(sequence)) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
        }
        val event = appended?.let { draft -> outboxImage(c, draft.eventId).also { actual ->
            if (actual["event_type"] != JsonPrimitive(draft.eventType) || actual["schema_version"] != JsonPrimitive(draft.schemaVersion) ||
                actual["aggregate_type"] != JsonPrimitive(draft.aggregateType) || actual["aggregate_id"] != JsonPrimitive(draft.aggregateId.toString()) ||
                actual["aggregate_version"] != JsonPrimitive(draft.aggregateVersion) || actual["producer"] != JsonPrimitive(draft.producer) ||
                actual["correlation_id"] != JsonPrimitive(draft.correlationId) || actual["causation_id"] != JsonPrimitive(draft.causationId.toString()) ||
                actual["payload"] != draft.data) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
        } }
        val at = now(c)
        if (!retained.expires.isAfter(at)) fail(CookingFailureCode.SESSION_EXPIRED)
        if (receiptExpires != null && !receiptExpires.isAfter(at)) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
        return Captured(id, session, retained.expires, identity, receipt, receiptExpires, step, cursor, event, appended?.eventId)
    }

    /** DB-only rejecting checks; no identity, note-policy or Plan-source callback runs here.
     * The guest owner revalidates its actual Plan source on both sides of this completion. */
    private fun revalidate(c: Connection, actor: VerifiedCookingPrincipal, expected: Captured, bound: Bound): Instant {
        if (sessionImage(c, actor, expected.sessionId) != expected.session) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
        bound.check(c, actor)
        expected.identity?.let { identity ->
            if (receiptImage(c, identity) != expected.receipt || stepImage(c, actor, expected.sessionId, identity.key) != expected.step ||
                cursorImage(c, actor, expected.sessionId) != expected.cursor) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
            bound.check(c, actor)
        }
        expected.eventId?.let { if (outboxImage(c, it) != expected.outbox) fail(CookingFailureCode.STORAGE_UNAVAILABLE) }
        bound.check(c, actor)
        val at = now(c)
        if (!expected.expires.isAfter(at)) fail(CookingFailureCode.SESSION_EXPIRED)
        if (expected.receiptExpires?.isAfter(at) == false) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
        current()
        return at
    }

    private fun sessionImage(c: Connection, actor: VerifiedCookingPrincipal, id: UUID) = image(c,
        "cooking.cook_sessions", "environment=? AND actor_kind=? AND principal_id=? AND id=?") {
        owner(actor); setObject(4, id)
    }.also { requireImageOwner(it, actor); requireImageId(it, "id", id) }
    private fun receiptImage(c: Connection, identity: CommandIdentity) = image(c,
        "platform.idempotency", "principal_scope=? AND operation_id=? AND key=?") {
        setString(1, identity.scope.storageKey); setString(2, identity.operationId); setObject(3, identity.key)
    }.also {
        if (it["principal_scope"] != JsonPrimitive(identity.scope.storageKey) || it["operation_id"] != JsonPrimitive(identity.operationId))
            fail(CookingFailureCode.STORAGE_UNAVAILABLE)
        requireImageId(it, "key", identity.key)
    }
    private fun stepImage(c: Connection, actor: VerifiedCookingPrincipal, id: UUID, key: UUID) = image(c,
        "cooking.step_events", "environment=? AND actor_kind=? AND principal_id=? AND session_id=? AND command_id=?") {
        owner(actor); setObject(4, id); setObject(5, key)
    }.also { requireImageOwner(it, actor); requireImageId(it, "session_id", id); requireImageId(it, "command_id", key) }
    private fun cursorImage(c: Connection, actor: VerifiedCookingPrincipal, id: UUID) = image(c,
        "cooking.device_cursors", "environment=? AND actor_kind=? AND principal_id=? AND session_id=? AND device_identity=?") {
        owner(actor); setObject(4, id); setObject(5, actor.sequenceIdentity)
    }.also { requireImageOwner(it, actor); requireImageId(it, "session_id", id); requireImageId(it, "device_identity", actor.sequenceIdentity) }
    private fun outboxImage(c: Connection, id: UUID) = image(c, "platform.outbox", "event_id=?") {
        setObject(1, id)
    }.also { requireImageId(it, "event_id", id) }
    private fun requireImageOwner(value: JsonObject, actor: VerifiedCookingPrincipal) {
        if (value["environment"] != JsonPrimitive(environment) || value["actor_kind"] != JsonPrimitive(actor.kind.name.lowercase()) ||
            value["principal_id"] != JsonPrimitive(actor.principalId.toString())) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
    }
    private fun requireImageId(value: JsonObject, field: String, id: UUID) {
        if (value[field] != JsonPrimitive(id.toString())) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
    }
    // Table/predicate strings are private source constants, never a request or callback input.
    private fun image(c: Connection, table: String, where: String, bind: PreparedStatement.() -> Unit): JsonObject =
        query(c, "SELECT to_jsonb(retained_row) FROM $table AS retained_row WHERE $where FOR SHARE", bind) { rows ->
            if (!rows.next()) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
            val value = Json.parseToJsonElement(rows.getString(1)).jsonObject
            if (rows.next()) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
            value
        }
    private fun storedInstant(body: JsonObject, field: String) = OffsetDateTime.parse(body.text(field)).toInstant()
    private fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { (key, v) -> "${JsonPrimitive(key)}:${canonical(v)}" }
        is JsonArray -> value.joinToString(",", "[", "]", transform = ::canonical)
        is JsonPrimitive -> if (value.isString || value == JsonNull || value.booleanOrNull != null) value.toString()
            else BigDecimal(value.content).stripTrailingZeros().toString()
    }
    private inner class Bound(private val connection: Connection, private val actor: VerifiedCookingPrincipal) {
        private val thread = Thread.currentThread()
        private val transaction = transactionId()
        fun check(c: Connection, actual: VerifiedCookingPrincipal) {
            checkLocal(c, actual)
            if (transactionId() != transaction) fail(CookingFailureCode.UNAUTHENTICATED)
        }
        fun checkLocal(c: Connection, actual: VerifiedCookingPrincipal) {
            current()
            if (Thread.currentThread() !== thread || c !== connection || actual !== actor || actor.environment != environment ||
                c.isClosed || c.autoCommit) fail(CookingFailureCode.UNAUTHENTICATED)
        }
        fun clock(sessionExpires: Instant?, receiptExpires: Instant?) = CompletionClock(connection, actor, sessionExpires, receiptExpires)
        private fun transactionId(): Long {
            current()
            if (connection.isClosed || connection.autoCommit) fail(CookingFailureCode.UNAUTHENTICATED)
            return connection.createStatement().use { statement -> statement.executeQuery("SELECT txid_current()").use { rows ->
                if (!rows.next()) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
                val id = rows.getLong(1)
                if (rows.next()) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
                id
            } }
        }
    }

    private fun steps(plan: JsonObject): List<String> {
        if (plan.text("status") != "ready") fail(CookingFailureCode.PLAN_NOT_READY)
        val recipe = plan["recipeSnapshot"]?.jsonObject ?: fail(CookingFailureCode.PLAN_NOT_READY)
        if (uuid(plan, "recipeVersionId") != uuid(recipe, "id")) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
        val ingredients = recipe.getValue("ingredients").jsonArray.map { uuid(it.jsonObject, "ingredientId") }
        val rows = recipe.getValue("steps").jsonArray.map { it.jsonObject }
        val ids = rows.map { it.text("stepId") }
        if (ingredients.isEmpty() || ingredients.distinct().size != ingredients.size || rows.isEmpty() || ids.any(String::isBlank) || ids.distinct().size != ids.size)
            fail(CookingFailureCode.PLAN_NOT_READY)
        val positions = rows.map { integer(it.getValue("position"), CookingFailureCode.PLAN_NOT_READY) }
        if (positions.sorted() != (1..rows.size).map(Int::toLong)) fail(CookingFailureCode.PLAN_NOT_READY)
        rows.forEach { row ->
            if (row.text("instruction").isBlank() || row.getValue("ingredientIds").jsonArray.any { UUID.fromString(it.jsonPrimitive.content) !in ingredients })
                fail(CookingFailureCode.PLAN_NOT_READY)
        }
        return rows.sortedBy { integer(it.getValue("position"), CookingFailureCode.PLAN_NOT_READY) }.map { it.text("stepId") }
    }
    private fun progress(body: JsonObject, steps: List<String>, failure: CookingFailureCode) {
        val completed = body.getValue("completedStepIds").jsonArray.map { it.jsonPrimitive.content }
        if (body.text("currentStepId") !in steps || completed.any { it !in steps } || completed.distinct().size != completed.size) fail(failure)
        if ((body.text("status") == "completed") != body.containsKey("completedAt")) fail(failure)
        val timers = body.getValue("timers").jsonArray.map { it.jsonObject }; val ids = timers.map { uuid(it, "timerId") }
        if (ids.distinct().size != ids.size) fail(failure)
        timers.forEach { timer ->
            if (timer.text("stepId") !in steps) fail(failure)
            val duration = integer(timer.getValue("durationSeconds"), failure)
            val remaining = timer["pausedRemainingSeconds"]?.let { integer(it, failure) }
            if (duration < 1 || (remaining != null && remaining > duration) ||
                (timer.text("status") == "running" && !timer.containsKey("endAt")) ||
                (timer.text("status") == "paused" && remaining == null)) fail(failure)
        }
    }
    private fun request(operation: String, body: JsonObject): JsonObject {
        val bytes = utf8(body, CookingFailureCode.INPUT_INVALID)
        if (bytes.size > 65536 || validator.validateRequest(operation, bytes, "application/json") != BodyValidationResult.Valid) fail(CookingFailureCode.INPUT_INVALID)
        return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }
    private fun reply(operation: String, status: Int, body: JsonObject): StoredReply {
        val bytes = utf8(body, CookingFailureCode.STORAGE_UNAVAILABLE)
        if (bytes.size > policy.maxResponseBytes) fail(CookingFailureCode.RESPONSE_TOO_LARGE)
        if (validator.validateResponse(operation, status, bytes, "application/json") != BodyValidationResult.Valid) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
        val version = integer(body.getValue("version"), CookingFailureCode.STORAGE_UNAVAILABLE)
        return StoredReply(status, body, "\"$version\"")
    }
    private fun utf8(body: JsonObject, failure: CookingFailureCode): ByteArray = try { body.toString().encodeToByteArray(throwOnInvalidSequence = true) }
        catch (_: IllegalArgumentException) { fail(failure) } catch (_: CharacterCodingException) { fail(failure) }
    private fun version(etag: String): Long {
        if (!etag.matches(Regex("\"[0-9]{1,64}\""))) fail(CookingFailureCode.INPUT_INVALID)
        return etag.substring(1, etag.length - 1).trimStart('0').ifEmpty { "0" }.toLongOrNull()?.takeIf { it > 0 }
            ?: fail(CookingFailureCode.INPUT_INVALID)
    }
    private fun integer(value: JsonElement, code: CookingFailureCode): Long = try {
        value.jsonPrimitive.let { if (it.isString) fail(code); BigDecimal(it.content).longValueExact().also { n -> if (n < 0) fail(code) } }
    } catch (failure: CookingFailure) { throw failure } catch (_: Exception) { fail(code) }
    private fun mutable(row: Row) { if (row.body.text("status") !in setOf("active", "paused")) fail(CookingFailureCode.TERMINAL_CONFLICT) }
    private fun live(c: Connection, row: Row) { if (!row.expires.isAfter(now(c))) fail(CookingFailureCode.SESSION_EXPIRED) }
    private fun increment(value: Long): Long = if (value == Long.MAX_VALUE) fail(CookingFailureCode.STORAGE_UNAVAILABLE) else value + 1
    private fun checkActor(actor: VerifiedCookingPrincipal) { if (actor.environment != environment) fail(CookingFailureCode.UNAUTHENTICATED) }
    private fun current() { if (Thread.currentThread().isInterrupted) throw InterruptedException("Cooking operation interrupted") }
    private fun <T> safe(callerOwned: Boolean = false, action: () -> T): T = try { action() }
        catch (failure: CookingFailure) { throw failure }
        catch (failure: FeedbackFailure) { throw failure }
        catch (failure: SavedRecipeFailure) { throw failure }
        catch (failure: GuestSessionFailure) { throw failure }
        catch (failure: PlanningServiceFailure) { current(); fail(when (failure.code) {
            PlanningFailureCode.UNAUTHENTICATED -> CookingFailureCode.UNAUTHENTICATED
            PlanningFailureCode.PLAN_UNAVAILABLE -> CookingFailureCode.PLAN_UNAVAILABLE
            PlanningFailureCode.PLAN_EXPIRED -> CookingFailureCode.PLAN_EXPIRED
            PlanningFailureCode.MODE_CONFIRMATION_REQUIRED -> CookingFailureCode.PLAN_NOT_READY
            PlanningFailureCode.PREFERENCE_CHANGED, PlanningFailureCode.INPUTS_CHANGED -> CookingFailureCode.INPUTS_CHANGED
            PlanningFailureCode.RECIPE_RECALLED -> CookingFailureCode.RECIPE_RECALLED
            PlanningFailureCode.RECIPE_UNAVAILABLE -> CookingFailureCode.RECIPE_UNAVAILABLE
            PlanningFailureCode.NOT_CONFIGURED -> CookingFailureCode.NOT_CONFIGURED
            else -> CookingFailureCode.STORAGE_UNAVAILABLE
        }) }
        catch (failure: CommitOutcomeUnknown) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (failure: SQLException) {
            current()
            if (callerOwned && failure.sqlState in setOf("40001", "40P01")) throw failure
            fail(CookingFailureCode.STORAGE_UNAVAILABLE)
        }
        catch (_: Exception) { current(); fail(CookingFailureCode.STORAGE_UNAVAILABLE) }
    private class Row(val id: UUID, val planId: UUID, val version: Long, val body: JsonObject, val planText: String,
        val planHash: String, val proofHash: String, val evidenceHash: String, val expires: Instant)
    private fun PreparedStatement.owner(actor: VerifiedCookingPrincipal, offset: Int = 1) {
        setString(offset, environment); setString(offset + 1, actor.kind.name.lowercase()); setObject(offset + 2, actor.principalId)
    }
    private fun now(c: Connection) = query(c, "SELECT clock_timestamp()", {}) { it.next(); it.getObject(1, OffsetDateTime::class.java).toInstant() }
    private fun instant(row: ResultSet, name: String) = row.getObject(name, OffsetDateTime::class.java).toInstant()
    private fun time(at: Instant) = OffsetDateTime.ofInstant(at, ZoneOffset.UTC)
    private fun exec(c: Connection, sql: String, bind: PreparedStatement.() -> Unit) { c.prepareStatement(sql).use { it.bind(); check(it.executeUpdate() == 1) } }
    private fun <T> query(c: Connection, sql: String, bind: PreparedStatement.() -> Unit, read: (ResultSet) -> T): T = c.prepareStatement(sql).use { it.bind(); it.executeQuery().use(read) }
    private fun uuid(body: JsonObject, field: String) = UUID.fromString(body.getValue(field).jsonPrimitive.content)
    private fun JsonObject.text(field: String) = getValue(field).jsonPrimitive.content
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun fail(code: CookingFailureCode): Nothing = throw CookingFailure(code)
    companion object { private val validator by lazy { ContractBodyValidator.bundled() } }
}
