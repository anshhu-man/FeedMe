package com.feedme.server.planning

import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.db.*
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.json.*

/** Read/cook/copy purpose fences for actual account-owned immutable no-parent proposals. */
internal class AccountRootRecipePlanReader(private val c: Connection, private val actor: VerifiedPlanningPrincipal,
    private val authority: AccountDerivedPlanningAuthority) {
    private enum class Purpose { READ, NEW, PIN, COPY }
    private data class Use(val id: UUID, val purpose: Purpose, val session: UUID?)
    private class Observed(val rows: RootRecipePlanRows, var deadline: Instant?)
    private val observed = linkedMapOf<Use, Observed>()
    private var last: Instant? = null
    private val thread = Thread.currentThread()
    fun getPlan(id: UUID): StoredReply = load(Use(id, Purpose.READ, null)).let { StoredReply(200, it.reply.body, it.reply.etag) }
    fun getExplanation(id: UUID, cursor: String?, limit: Int, cursors: PlanningCursors): StoredReply {
        if (limit !in 1..50) rootFail(PlanningFailureCode.INPUT_INVALID)
        val record = load(Use(id, Purpose.READ, null))
        val binding = "${actor.environment}:${actor.kind}:${actor.principalId}:$id:${record.snapshotHash}"
        val offset = cursors.offset(binding, cursor)
        val reasons = rootJson(record.snapshotText).getValue("reasons").jsonArray
        if (offset > reasons.size) rootFail(PlanningFailureCode.CURSOR_INVALID)
        val items = reasons.drop(offset).take(limit); val end = offset + items.size
        val body = buildJsonObject { put("items", JsonArray(items)); put("nextCursor", if (end < reasons.size) JsonPrimitive(cursors.explanation(binding, end)) else JsonNull)
            put("serverTime", rootNow(c).toString()) }
        if (dpValidator.validateResponse("getPlanExplanation", 200, body.toString().encodeToByteArray(), "application/json") != BodyValidationResult.Valid) rootFail()
        return StoredReply(200, body)
    }
    fun lockCookingPlan(id: UUID, use: CookingPlanUse, session: UUID?): CookingPlanSnapshot {
        if ((use == CookingPlanUse.NEW_SELECTION) != (session == null)) rootFail(PlanningFailureCode.INPUT_INVALID)
        val record = load(Use(id, if (session == null) Purpose.NEW else Purpose.PIN, session))
        if (record.status != "ready") rootFail(PlanningFailureCode.MODE_CONFIRMATION_REQUIRED)
        return CookingPlanSnapshot(record.snapshotText, record.snapshotHash, record.proofHash, record.contextHash)
    }
    fun ownedCopy(id: UUID): JsonObject {
        val record = load(Use(id, Purpose.COPY, null))
        if (record.status != "ready") rootFail(PlanningFailureCode.RECIPE_UNAVAILABLE)
        return rootJson(record.snapshotText).getValue("recipeSnapshot").jsonObject
    }
    private fun load(use: Use): RootRecipePlanRecord {
        local(); authority.current()
        val rows = RootRecipePlanRows.load(c, actor, use.id)
        observed[use]?.let { if (it.rows.plan != rows.plan || it.rows.request != rows.request) rootFail() }
        observed.putIfAbsent(use, Observed(rows, null)); revalidate()
        return rows.record
    }
    fun revalidate() {
        if (observed.isEmpty()) return
        local(); authority.current()
        observed.forEach { (use, before) ->
            val rows = RootRecipePlanRows.load(c, actor, use.id)
            if (before.rows.plan != rows.plan || before.rows.request != rows.request) rootFail()
            authority.authorizeRoot(rows.record, use.purpose == Purpose.NEW)
            before.deadline = when (use.purpose) {
                Purpose.COPY -> null
                Purpose.NEW -> rows.record.expiresAt
                Purpose.PIN -> pin(rows.record, use.session) ?: rootFail(PlanningFailureCode.PLAN_EXPIRED)
                Purpose.READ -> maxOf(rows.record.expiresAt, pin(rows.record, null) ?: rows.record.expiresAt)
            }
        }
        authority.current(); val at = rootNow(c)
        if (last?.let { at < it } == true) rootFail()
        last = at; checkAt(at)
    }
    fun checkAt(at: Instant) {
        if (observed.isEmpty()) return
        local(); val previous = last ?: rootFail()
        if (at < previous) rootFail()
        authority.checkAt(at)
        if (observed.values.any { it.deadline?.isAfter(at) == false }) rootFail(PlanningFailureCode.PLAN_EXPIRED)
        last = at
    }
    private fun pin(record: RootRecipePlanRecord, session: UUID?): Instant? = c.prepareStatement(
        "SELECT expires_at FROM cooking.cook_sessions WHERE environment=? AND actor_kind='account' AND principal_id=? AND plan_id=? " +
            "AND (?::uuid IS NULL OR id=?::uuid) AND plan_snapshot_hash=? AND plan_proof_hash=? AND plan_evidence_hash=? " +
            "AND plan_snapshot_text=? ORDER BY expires_at DESC LIMIT 1 FOR SHARE").use { s ->
        s.setString(1, actor.environment); s.setObject(2, actor.principalId); s.setObject(3, record.planId)
        s.setObject(4, session); s.setObject(5, session); s.setString(6, record.snapshotHash); s.setString(7, record.proofHash)
        s.setString(8, record.contextHash); s.setString(9, record.snapshotText)
        s.executeQuery().use { r -> if (!r.next()) null else r.getObject(1, OffsetDateTime::class.java).toInstant() }
    }
    private fun local() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Root proposal read interrupted")
        if (Thread.currentThread() !== thread || c.isClosed || c.autoCommit || actor.kind != CommandActor.ACCOUNT) rootFail()
    }
}

/** Owned row bindings, not authority. Shared by the writer/replay and purpose-specific reader. */
internal class RootRecipePlanRows(val plan: JsonObject, val request: JsonObject, val record: RootRecipePlanRecord) {
    companion object {
        fun load(c: Connection, actor: VerifiedPlanningPrincipal, id: UUID): RootRecipePlanRows {
            val plan = rootOwnedRow(c, actor, "planning.plans", id)
            val request = rootOwnedRow(c, actor, "planning.plan_requests", UUID.fromString(plan.rootText("request_id")))
            return decode(actor, plan, request)
        }
        fun decode(actor: VerifiedPlanningPrincipal, plan: JsonObject, request: JsonObject): RootRecipePlanRows {
            val record = RootRecipePlanRecord.decode(plan.rootText("snapshot_text"), plan.rootText("snapshot_hash"),
                request.rootText("evidence_text"), request.rootText("evidence_hash"), plan.rootText("proof_text"), plan.rootText("proof_hash"))
            val context = rootJson(record.contextText)
            for (row in listOf(plan, request)) if (row["environment"] != JsonPrimitive(actor.environment) || row["actor_kind"] != JsonPrimitive("account") ||
                row["principal_id"] != JsonPrimitive(actor.principalId.toString()) || row["storage_format"] != JsonPrimitive(record.storageFormat) || row["version"] != JsonPrimitive(1)) rootFail()
            if (actor.kind != CommandActor.ACCOUNT || context["owner"] != dpOwner(actor) || plan["id"] != JsonPrimitive(record.planId.toString()) ||
                plan["request_id"] != request["id"] || request["current_plan_id"] != plan["id"] || plan["parent_plan_id"] != JsonNull || plan["next_cursor_hash"] != JsonNull ||
                request["derived_operation"] != JsonPrimitive("createPlan") || context["commandKey"] != request["derived_command_key"] ||
                context["commandRequestHash"] != request["derived_request_sha256"] || context["requestText"] != request["request_text"] || context["requestSha256"] != request["request_hash"] ||
                listOf("derived_if_match", "derived_parent_plan_id", "derived_parent_version", "ordered_ids", "manifest_id", "create_command_key", "command_request_sha256").any { request[it] != JsonNull } ||
                plan["recipe_version_id"] != (record.recipeVersionId?.let { JsonPrimitive(it.toString()) } ?: JsonNull) || plan["status"] != JsonPrimitive(record.status) ||
                plan["position"] != JsonPrimitive(if (record.status == "ready") 0 else -1) ||
                rootInstant(plan, "created_at") != record.createdAt || rootInstant(request, "created_at") != record.createdAt ||
                rootInstant(request, "expires_at") != record.expiresAt || rootInstant(request, "cursor_expires_at") != record.expiresAt ||
                rootJson(request.rootText("policy_text")) != context["policy"]) rootFail()
            return RootRecipePlanRows(plan, request, record)
        }
    }
}
internal fun rootOwnedRow(c: Connection, actor: VerifiedPlanningPrincipal, table: String, id: UUID): JsonObject {
    require(table in setOf("planning.plans", "planning.plan_requests"))
    return c.prepareStatement("SELECT to_jsonb(r)::text FROM $table r WHERE environment=? AND actor_kind='account' AND principal_id=? AND id=? FOR SHARE").use { s ->
        s.setString(1, actor.environment); s.setObject(2, actor.principalId); s.setObject(3, id)
        s.executeQuery().use { r -> if (!r.next()) rootFail(PlanningFailureCode.PLAN_UNAVAILABLE)
            rootJson(r.getString(1)).also { if (r.next()) rootFail() } }
    }
}
internal fun rootJson(text: String) = Json.parseToJsonElement(text).jsonObject
internal fun rootInstant(value: JsonObject, field: String) = OffsetDateTime.parse(value.rootText(field)).toInstant()
internal fun rootNow(c: Connection): Instant = c.createStatement().use { s -> s.executeQuery("SELECT clock_timestamp()").use { r ->
    check(r.next()); r.getObject(1, OffsetDateTime::class.java).toInstant().also { check(!r.next()) }
} }
internal fun rootFail(code: PlanningFailureCode = PlanningFailureCode.STORAGE_UNAVAILABLE): Nothing = throw PlanningServiceFailure(code)
