package com.feedme.server.planning

import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.CommandActor
import com.feedme.server.db.StoredReply
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.json.*

/** Actual account-transaction format-3 reader. The structural codec is not authority:
 * every use also reauthorizes the retained source/target/publication/private-input proof
 * through the concrete account adapter. Read/recovery never scan or re-rank a new proposal;
 * validating the exact retained single-candidate material is not reselection.
 * An independently published B is the source for B->C: C rechecks B/C, not an unbounded
 * ancestor chain. The writer must separately authorize the actual parent before creating C;
 * immediate parent bytes remain bound below but never stand in for B's actual publication.
 * Adapted Plans additionally require their own exact current substitution publication
 * through that concrete authority for every purpose, including existing cooking and copying.
 * Mixed simplify/adapt parents do not compose edges or inherit a parent's transformation grant.
 * Copying is a distinct purpose: historical Plan age is not a new-copy license. */
internal class AccountDerivedPlanReader(private val connection: Connection,
    private val actor: VerifiedPlanningPrincipal, private val authority: AccountDerivedPlanningAuthority) {
    private enum class Purpose { READ, NEW_COOKING, EXISTING_COOKING, COPY }
    private data class Use(val id: UUID, val purpose: Purpose, val session: UUID?)
    private class Image(val plan: JsonObject, val request: JsonObject, val record: DerivedPlanStoredRecord)
    private class Observation(val image: Image, var deadline: Instant?)
    private val observations = linkedMapOf<Use, Observation>()
    private var observedAt: Instant? = null
    private val thread = Thread.currentThread()
    private var root: AccountRootRecipePlanReader? = null
    private fun rootReader(id: UUID): AccountRootRecipePlanReader? {
        val format = connection.prepareStatement("SELECT storage_format FROM planning.plans WHERE environment=? AND actor_kind='account' AND principal_id=? AND id=?").use { s ->
            s.owner(id); s.executeQuery().use { r -> if (!r.next()) null else r.getInt(1).also { if (r.next()) unavailable() } }
        }
        if (format !in setOf(4, 5, 6)) return null
        return root ?: AccountRootRecipePlanReader(connection, actor, authority).also { root = it }
    }

    fun getPlan(id: UUID): StoredReply = rootReader(id)?.getPlan(id) ?: load(Use(id, Purpose.READ, null)).reply

    fun getExplanation(id: UUID, cursor: String?, limit: Int, cursors: PlanningCursors): StoredReply {
        rootReader(id)?.let { return it.getExplanation(id, cursor, limit, cursors) }
        if (limit !in 1..50) fail(PlanningFailureCode.INPUT_INVALID)
        val record = load(Use(id, Purpose.READ, null))
        val binding = "${actor.environment}:${actor.kind}:${actor.principalId}:$id:${record.snapshotHash}"
        val offset = cursors.offset(binding, cursor)
        val reasons = parse(record.snapshotText).getValue("reasons").jsonArray
        if (offset > reasons.size) fail(PlanningFailureCode.CURSOR_INVALID)
        val selected = reasons.drop(offset).take(limit); val end = offset + selected.size
        val body = buildJsonObject {
            put("items", JsonArray(selected))
            put("nextCursor", if (end < reasons.size) JsonPrimitive(cursors.explanation(binding, end)) else JsonNull)
            put("serverTime", now().toString())
        }
        if (validator.validateResponse("getPlanExplanation", 200, body.toString().encodeToByteArray(), "application/json") != BodyValidationResult.Valid)
            unavailable()
        return StoredReply(200, body)
    }

    fun lockCookingPlan(id: UUID, use: CookingPlanUse, session: UUID?): CookingPlanSnapshot {
        rootReader(id)?.let { return it.lockCookingPlan(id, use, session) }
        if ((use == CookingPlanUse.NEW_SELECTION) != (session == null)) fail(PlanningFailureCode.INPUT_INVALID)
        val record = load(Use(id, if (use == CookingPlanUse.NEW_SELECTION) Purpose.NEW_COOKING else Purpose.EXISTING_COOKING, session))
        if (record.status != "ready" || record.recipeVersionId == null) fail(PlanningFailureCode.MODE_CONFIRMATION_REQUIRED)
        return CookingPlanSnapshot(record.snapshotText, record.snapshotHash, record.proofHash, record.contextHash)
    }

    /** Caller MUST separately acquire actual new-copy rights for this exact recipe material. */
    fun ownedCopy(id: UUID): JsonObject {
        rootReader(id)?.let { return it.ownedCopy(id) }
        val record = load(Use(id, Purpose.COPY, null))
        if (record.status != "ready" || record.recipeVersionId == null) fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
        return parse(record.snapshotText).getValue("recipeSnapshot").jsonObject
    }

    private fun load(use: Use): DerivedPlanStoredRecord {
        local(); authority.current()
        val image = image(use.id)
        observations[use]?.let { if (it.image.plan != image.plan || it.image.request != image.request) unavailable() }
        observations.putIfAbsent(use, Observation(image, null))
        revalidate()
        return image.record
    }

    fun revalidate() {
        local(); authority.current()
        root?.revalidate()
        observations.forEach { (use, retained) ->
            val current = image(use.id)
            if (retained.image.plan != current.plan || retained.image.request != current.request) unavailable()
            authority.authorizeStored(current.record, selection = use.purpose == Purpose.NEW_COOKING)
            retained.deadline = when (use.purpose) {
                Purpose.COPY -> null
                Purpose.NEW_COOKING -> current.record.expiresAt
                Purpose.EXISTING_COOKING -> pin(current.record, use.session) ?: fail(PlanningFailureCode.PLAN_EXPIRED)
                Purpose.READ -> maxOf(current.record.expiresAt, pin(current.record, null) ?: current.record.expiresAt)
            }
        }
        authority.current()
        val at = now()
        observedAt?.let { if (at < it) unavailable() }
        observedAt = at
        checkAt(at)
    }

    /** Rejection-only, no SQL. Must use the owning operation's final database clock. */
    fun checkAt(at: Instant) {
        local()
        root?.checkAt(at)
        val previous = observedAt ?: if (observations.isEmpty()) return else unavailable()
        if (at < previous) unavailable()
        authority.checkAt(at)
        if (observations.values.any { it.deadline?.isAfter(at) == false }) fail(PlanningFailureCode.PLAN_EXPIRED)
        observedAt = at
    }

    private fun image(id: UUID): Image {
        val plan = row("planning.plans", id)
        val request = row("planning.plan_requests", uuid(plan, "request_id"))
        if (integer(plan, "storage_format") != 3L || integer(request, "storage_format") != 3L ||
            integer(plan, "version") != 1L || integer(request, "version") != 1L ||
            uuid(plan, "id") != id || uuid(request, "current_plan_id") != id ||
            plan["next_cursor_hash"] != JsonNull ||
            listOf("ordered_ids", "manifest_id", "create_command_key", "command_request_sha256").any { request[it] != JsonNull }) unavailable()
        val command = DerivedPlanCommand(actor, request.text("derived_operation"), uuid(request, "derived_command_key"),
            uuid(request, "derived_parent_plan_id"), request.text("derived_if_match"), wire(parse(request.text("request_text"))))
        // Decoding either canonical operation is structural only. revalidate() below must
        // still pass the concrete operation-specific publication/edge/material authority;
        // absent substitution-journal composition cannot admit an adapted Plan.
        if (command.operationId !in OPERATIONS) fail(PlanningFailureCode.NOT_CONFIGURED)
        val record = DerivedPlanStoredRecord.decode(plan.text("snapshot_text"), plan.text("snapshot_hash"),
            request.text("evidence_text"), request.text("evidence_hash"), plan.text("proof_text"), plan.text("proof_hash"))
        val context = parse(record.contextText)
        val owner = buildJsonObject { put("environment", actor.environment); put("actorKind", "account"); put("principalId", actor.principalId.toString()) }
        if (context["owner"] != owner || context["requestText"] != request["request_text"] ||
            context["requestSha256"] != request["request_hash"] || context["commandKey"] != JsonPrimitive(command.key.toString()) ||
            context["operationId"] != JsonPrimitive(command.operationId) ||
            context["commandRequestHash"] != JsonPrimitive(command.identity.requestHash) ||
            request["derived_request_sha256"] != JsonPrimitive(command.identity.requestHash) ||
            context["originalIfMatch"] != request["derived_if_match"] ||
            context.text("parentVersion").toBigInteger() != integer(request, "derived_parent_version").toBigInteger() ||
            record.planId != id || record.parentId != command.parentId || uuid(plan, "parent_plan_id") != command.parentId ||
            plan["recipe_version_id"] != (record.recipeVersionId?.let { JsonPrimitive(it.toString()) } ?: JsonNull) ||
            plan["status"] != JsonPrimitive(record.status) || integer(plan, "position") != (if (record.status == "ready") 0L else -1L) ||
            record.createdAt != instant(plan, "created_at") || record.createdAt != instant(request, "created_at") ||
            record.expiresAt != instant(request, "expires_at") || record.expiresAt != instant(request, "cursor_expires_at") ||
            parse(request.text("policy_text")) != context["policy"]) unavailable()
        val parent = row("planning.plans", record.parentId)
        val parentRequest = row("planning.plan_requests", uuid(parent, "request_id"))
        if (integer(parent, "storage_format") !in setOf(1L, 3L, 4L, 5L, 6L) || parent["storage_format"] != parentRequest["storage_format"] ||
            parent["status"] != JsonPrimitive("ready") ||
            integer(parent, "version").toBigInteger() != context.text("parentVersion").toBigInteger() ||
            parse(parent.text("snapshot_text"))["recipeVersionId"] != parent["recipe_version_id"] ||
            OffsetDateTime.parse(parse(parent.text("snapshot_text")).text("createdAt")).toInstant() != instant(parent, "created_at")) unavailable()
        for ((field, column) in listOf("parentSnapshotText" to "snapshot_text", "parentSnapshotHash" to "snapshot_hash",
            "parentProofText" to "proof_text", "parentProofHash" to "proof_hash")) if (context[field] != parent[column]) unavailable()
        for ((field, column) in listOf("parentRequestText" to "request_text", "parentRequestHash" to "request_hash"))
            if (context[field] != parentRequest[column]) unavailable()
        val effectiveParent = if (integer(parent, "storage_format") == 3L) {
            val original = DerivedPlanStoredRecord.decode(parent.text("snapshot_text"), parent.text("snapshot_hash"),
                parentRequest.text("evidence_text"), parentRequest.text("evidence_hash"), parent.text("proof_text"), parent.text("proof_hash"))
            val originalContext = parse(original.contextText)
            if (original.planId != record.parentId || originalContext["owner"] != owner ||
                originalContext.text("operationId") !in OPERATIONS ||
                originalContext["operationId"] != parentRequest["derived_operation"] ||
                originalContext["requestText"] != parentRequest["request_text"] ||
                originalContext["requestSha256"] != parentRequest["request_hash"] ||
                originalContext["commandKey"] != parentRequest["derived_command_key"] ||
                originalContext["commandRequestHash"] != parentRequest["derived_request_sha256"] ||
                originalContext["originalIfMatch"] != parentRequest["derived_if_match"] ||
                originalContext["parentId"] != parentRequest["derived_parent_plan_id"] ||
                originalContext["parentId"] != parent["parent_plan_id"] ||
                originalContext.text("parentVersion").toBigInteger() != integer(parentRequest, "derived_parent_version").toBigInteger() ||
                uuid(parentRequest, "current_plan_id") != original.planId || integer(parentRequest, "version") != 1L ||
                original.createdAt != instant(parent, "created_at") || original.createdAt != instant(parentRequest, "created_at") ||
                original.expiresAt != instant(parentRequest, "expires_at") || original.expiresAt != instant(parentRequest, "cursor_expires_at") ||
                parse(parentRequest.text("policy_text")) != originalContext["policy"] || parent["next_cursor_hash"] != JsonNull ||
                integer(parent, "position") != 0L ||
                listOf("ordered_ids", "manifest_id", "create_command_key", "command_request_sha256").any { parentRequest[it] != JsonNull }) unavailable()
            originalContext.text("effectiveRequestText")
        } else if (integer(parent, "storage_format") in 4L..6L) {
            val original = RootRecipePlanRows.decode(actor, parent, parentRequest).record
            if (original.planId != record.parentId || original.status != "ready") unavailable()
            // Immediate root provenance remains immutable audit. The child's own reviewed
            // source/target pair is its authority; reads do not recurse through old edges.
            rootParentPlanningRequest(parentRequest.text("request_text"), original)
        } else {
            val parentEvidence = parentRequest.text("evidence_text")
            if (digest(parentEvidence.encodeToByteArray()) != parentRequest.text("evidence_hash")) unavailable()
            val evidence = PlanningEvidenceSnapshot.decode(parentEvidence.encodeToByteArray())
            val proof = parse(parent.text("proof_text"))
            if (evidence.savedSource != null || proof["version"] != JsonPrimitive(1) ||
                proof["requestHash"] != parentRequest["request_hash"] || proof["evidenceHash"] != parentRequest["evidence_hash"] ||
                proof["preferenceVersion"] != evidence.preferences["revision"] || proof["pantryRevision"] != evidence.pantry["revision"] ||
                proof["facts"] != parse(parent.text("snapshot_text"))["reasons"] ||
                evidence.candidate(parent.text("recipe_version_id")) == null) unavailable()
            parentRequest.text("request_text")
        }
        if (context["parentPlanningRequestText"] != JsonPrimitive(effectiveParent) ||
            context["parentPlanningRequestHash"] != JsonPrimitive(digest(effectiveParent.encodeToByteArray()))) unavailable()
        return Image(plan, request, record)
    }

    private fun pin(record: DerivedPlanStoredRecord, session: UUID?): Instant? = connection.prepareStatement(
        "SELECT expires_at FROM cooking.cook_sessions WHERE environment=? AND actor_kind='account' AND principal_id=? AND plan_id=? " +
            "AND (?::uuid IS NULL OR id=?::uuid) AND plan_snapshot_hash=? AND plan_proof_hash=? AND plan_evidence_hash=? " +
            "AND plan_snapshot_text=? ORDER BY expires_at DESC LIMIT 1 FOR SHARE").use { s ->
        s.setString(1, actor.environment); s.setObject(2, actor.principalId); s.setObject(3, record.planId)
        s.setObject(4, session); s.setObject(5, session); s.setString(6, record.snapshotHash); s.setString(7, record.proofHash)
        s.setString(8, record.contextHash); s.setString(9, record.snapshotText)
        s.executeQuery().use { r -> if (!r.next()) null else r.getObject(1, OffsetDateTime::class.java).toInstant() }
    }

    private fun row(table: String, id: UUID): JsonObject = connection.prepareStatement(
        "SELECT to_jsonb(r)::text FROM $table r WHERE environment=? AND actor_kind='account' AND principal_id=? AND id=? FOR SHARE").use { s ->
        s.owner(id); s.executeQuery().use { r ->
            if (!r.next()) fail(PlanningFailureCode.PLAN_UNAVAILABLE)
            parse(r.getString(1)).also { row ->
                if (r.next() || row["environment"] != JsonPrimitive(actor.environment) || row["actor_kind"] != JsonPrimitive("account") ||
                    row["principal_id"] != JsonPrimitive(actor.principalId.toString()) || row["id"] != JsonPrimitive(id.toString())) unavailable()
            }
        }
    }
    private fun PreparedStatement.owner(id: UUID) { setString(1, actor.environment); setObject(2, actor.principalId); setObject(3, id) }
    private fun now(): Instant = connection.createStatement().use { s -> s.executeQuery("SELECT clock_timestamp()").use { r ->
        check(r.next()); r.getObject(1, OffsetDateTime::class.java).toInstant().also { check(!r.next()) }
    } }
    private fun local() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Derived Plan read interrupted")
        if (Thread.currentThread() !== thread || connection.isClosed || connection.autoCommit || actor.kind != CommandActor.ACCOUNT) unavailable()
    }
    private fun parse(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun uuid(value: JsonObject, field: String) = UUID.fromString(value.text(field))
    private fun integer(value: JsonObject, field: String) = value.getValue(field).jsonPrimitive.content.toLong()
    private fun instant(value: JsonObject, field: String) = OffsetDateTime.parse(value.text(field)).toInstant()
    private fun unavailable(): Nothing = fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
    private fun fail(code: PlanningFailureCode): Nothing = throw PlanningServiceFailure(code)
    override fun toString() = "AccountDerivedPlanReader(<redacted>)"
    companion object {
        private val OPERATIONS = setOf("simplifyPlan", "adaptPlan")
        private val validator by lazy { ContractBodyValidator.bundled() }
    }
}
