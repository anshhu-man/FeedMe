package com.feedme.server.cooking

import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.planning.*
import java.math.BigDecimal
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
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
 * Owner-scoped PostgreSQL cooking component. Each state transition, per-device cursor, private
 * step event, original-key receipt and redacted outbox fact commit together. No GET initialization,
 * timer delivery, save, feedback, pantry consumption or publication follows these operations.
 * The scalar sequence is the last accepted aggregate sequence; fresh mutations require +1 and
 * cannot regress the current verified device's cursor. Completion is status-only, not an inferred
 * final progress/safety report. Current identity and pinned recipe rights precede cached disclosure.
 */
class CookingStore(val environment: String, private val transactions: PgTransactions,
    private val authority: CookingAuthority, private val plans: PlansStore, val policy: CookingServicePolicy) {
    private val commands = DurableCommands(transactions)
    private val outbox = OutboxStore(transactions)
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(plans.environment == environment)
    }

    fun createCookSession(actor: VerifiedCookingPrincipal, key: UUID, body: JsonObject): CommandResult {
        val input = request("createCookSession", body); val planId = uuid(input, "planId")
        val sequence = input["deviceSequence"]?.let { integer(it, CookingFailureCode.INPUT_INVALID) } ?: 0
        val sessionId = UUID.randomUUID()
        return command(actor, "createCookSession", key, null, null, input) { c, identity ->
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
            event(c, actor, sessionId, planId, key, identity.requestHash, input, "started", sequence, 1, at)
            val persisted = row(c, actor, sessionId); live(c, persisted)
            reply("createCookSession", 201, persisted.body).also { current() }
        }
    }

    fun getCookSession(actor: VerifiedCookingPrincipal, sessionId: UUID): StoredReply = read(actor) { c ->
        val (row, _) = authorized(c, actor, sessionId)
        reply("getCookSession", 200, row.body).also { live(c, row); current() }
    }

    fun updateCookSession(actor: VerifiedCookingPrincipal, key: UUID, sessionId: UUID, ifMatch: String, body: JsonObject): CommandResult {
        val input = request("updateCookSession", body); val expected = version(ifMatch)
        val sequence = integer(input.getValue("deviceSequence"), CookingFailureCode.INPUT_INVALID)
        return command(actor, "updateCookSession", key, sessionId, ifMatch, input) { c, identity ->
            val (old, pin) = authorized(c, actor, sessionId)
            mutable(old); if (old.version != expected) fail(CookingFailureCode.VERSION_CONFLICT)
            nextSequence(c, actor, old, sequence); unusedCommand(c, actor, sessionId, key)
            val at = now(c); val changed = JsonObject(old.body + input + mapOf("version" to JsonPrimitive(increment(old.version)), "updatedAt" to JsonPrimitive(at.toString())))
            progress(changed, steps(pin.document), CookingFailureCode.INPUT_INVALID)
            input["personalNotes"]?.let { authority.validatePersonalNotes(c, actor, it.jsonArray); current() }
            reply("updateCookSession", 200, changed)
            update(c, actor, old, changed, at)
            event(c, actor, sessionId, old.planId, key, identity.requestHash, input, "progressed", sequence, old.version + 1, at)
            val persisted = row(c, actor, sessionId); live(c, persisted)
            reply("updateCookSession", 200, persisted.body).also { current() }
        }
    }

    fun completeCookSession(actor: VerifiedCookingPrincipal, key: UUID, sessionId: UUID, body: JsonObject): CommandResult {
        val input = request("completeCookSession", body)
        if (input.getValue("makeAgain").jsonPrimitive.boolean) fail(CookingFailureCode.NOT_CONFIGURED)
        val sequence = integer(input.getValue("deviceSequence"), CookingFailureCode.INPUT_INVALID)
        return command(actor, "completeCookSession", key, sessionId, null, input) { c, identity ->
            val (old, _) = authorized(c, actor, sessionId)
            mutable(old); nextSequence(c, actor, old, sequence); unusedCommand(c, actor, sessionId, key)
            val at = now(c)
            // No If-Match or final progress is present in Completion. Preserve every locked field.
            // finishedAtClient stays untrusted private step-event provenance, not accepted DB time.
            val changed = JsonObject(old.body + mapOf("status" to JsonPrimitive("completed"), "deviceSequence" to JsonPrimitive(sequence),
                "version" to JsonPrimitive(increment(old.version)), "updatedAt" to JsonPrimitive(at.toString()), "completedAt" to JsonPrimitive(at.toString())))
            reply("completeCookSession", 200, changed)
            update(c, actor, old, changed, at)
            event(c, actor, sessionId, old.planId, key, identity.requestHash, input, "completed", sequence, old.version + 1, at)
            val persisted = row(c, actor, sessionId); live(c, persisted)
            reply("completeCookSession", 200, persisted.body).also { current() }
        }
    }

    private fun command(actor: VerifiedCookingPrincipal, operation: String, key: UUID, sessionId: UUID?, ifMatch: String?, input: JsonObject,
        mutate: (Connection, CommandIdentity) -> StoredReply): CommandResult = safe {
        checkActor(actor); current()
        val identity = CommandIdentity(PrincipalScope(environment, actor.kind, actor.principalId), operation, key,
            sessionId?.let { mapOf("sessionId" to it.toString()) } ?: emptyMap(), body = input, ifMatch = ifMatch)
        commands.execute(identity, { authority.lockPrincipal(it, actor); current() }, {}, { c, cached ->
            val id = sessionId ?: uuid(cached.body!!.jsonObject, "id")
            val (currentRow, _) = authorized(c, actor, id)
            val kind = when (operation) { "createCookSession" -> "started"; "updateCookSession" -> "progressed"; else -> "completed" }
            query(c, "SELECT kind,device_identity,request_hash,session_version FROM cooking.step_events WHERE environment=? AND actor_kind=? AND principal_id=? AND session_id=? AND command_id=?", {
                owner(actor); setObject(4, id); setObject(5, key)
            }) { r ->
                if (!r.next() || r.getString(1) != kind || r.getString(3) != identity.requestHash) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
                if (r.getObject(2, UUID::class.java) != actor.sequenceIdentity) fail(CookingFailureCode.COMMAND_CONFLICT)
                if (r.getLong(4) != currentRow.version) fail(CookingFailureCode.VERSION_CONFLICT)
            }
            val actual = reply(operation, if (operation == "createCookSession") 201 else 200, currentRow.body)
            if (cached.status != actual.status || cached.etag != actual.etag || cached.body != actual.body) fail(CookingFailureCode.VERSION_CONFLICT)
            live(c, currentRow); current()
        }, { c -> mutate(c, identity) })
    }

    private fun <T> read(actor: VerifiedCookingPrincipal, action: (Connection) -> T): T = safe {
        checkActor(actor); current(); transactions.run { c -> authority.lockPrincipal(c, actor); current(); action(c) }
    }
    private fun authorized(c: Connection, actor: VerifiedCookingPrincipal, id: UUID): Pair<Row, CookingPlanSnapshot> {
        // Read only enough owned metadata to find the lineage. Re-read the locked row after rights.
        val planId = query(c, "SELECT plan_id,expires_at>clock_timestamp() FROM cooking.cook_sessions WHERE environment=? AND actor_kind=? AND principal_id=? AND id=?", {
            owner(actor); setObject(4, id)
        }) { if (!it.next()) fail(CookingFailureCode.COOK_SESSION_UNAVAILABLE)
            if (!it.getBoolean(2)) fail(CookingFailureCode.SESSION_EXPIRED); it.getObject(1, UUID::class.java) }
        val pin = plan(c, actor, planId, CookingPlanUse.EXISTING_PIN, id)
        val row = row(c, actor, id); live(c, row)
        if (row.planId != planId || row.planText != pin.snapshotText || row.planHash != pin.snapshotHash ||
            row.proofHash != pin.proofHash || row.evidenceHash != pin.evidenceHash) fail(CookingFailureCode.STORAGE_UNAVAILABLE)
        progress(row.body, steps(pin.document), CookingFailureCode.STORAGE_UNAVAILABLE)
        current(); return row to pin
    }
    private fun plan(c: Connection, actor: VerifiedCookingPrincipal, id: UUID, use: CookingPlanUse, sessionId: UUID? = null) =
        plans.lockCookingPlan(c, VerifiedPlanningPrincipal(environment, actor.kind, actor.principalId, actor.deviceSessionId), id, use, sessionId).also { current() }

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
        input: JsonObject, kind: String, sequence: Long, version: Long, at: Instant) {
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
        outbox.append(c, EventDraft(UUID.randomUUID(), "cooking.session.$kind.v1", 1, "cook_session", sessionId, version,
            "cooking", key.toString(), key, buildJsonObject {
                put("principalId", actor.principalId.toString()); put("sessionId", sessionId.toString())
                if (kind == "progressed") put("deviceSequence", sequence) else put("planId", planId.toString())
            }))
        current()
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
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: CookingFailure) { throw failure }
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
