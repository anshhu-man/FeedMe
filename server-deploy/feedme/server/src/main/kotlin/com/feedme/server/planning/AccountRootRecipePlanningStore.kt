package com.feedme.server.planning

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.planning.PlanningScanBudget
import com.feedme.server.catalog.*
import com.feedme.server.db.*
import java.sql.Connection
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.serialization.json.*

/** Account-owned direct Recipe Make Mine. One genuine source, one root proposal and one
 * receipt/quota/event transaction. No hidden parent, fabricated ETag or generic ranking. */
internal class AccountRootRecipePlanningStore(private val transactions: PgTransactions,
    private val operational: AccountPlanningPolicy, private val policy: PlanningServicePolicy) {
    fun create(c: Connection, actor: VerifiedPlanningPrincipal, authority: AccountDerivedPlanningAuthority,
        key: UUID, body: JsonObject): CommandResult {
        val command = try { RootRecipePlanCommand(actor, key, WireDocument.parse(body.toString())) }
            catch (_: DerivedPlanMaterialException) { rootFail(PlanningFailureCode.INPUT_INVALID) }
        var expected: RootRecipePlanRows? = null
        var admitted: JsonObject? = null
        var eventProof: Pair<UUID, JsonObject>? = null
        val result = DurableCommands(transactions).executeInTransaction(c, command.identity,
            validatePrincipal = { authority.current(); operational.checkCompatibility(it) },
            authorizeNew = { operational.requireNew(it, actor); authority.current() },
            authorizeReplay = { same, reply ->
                val id = reply.body?.jsonObject?.get("id")?.jsonPrimitive?.content?.let(UUID::fromString) ?: rootFail()
                val rows = RootRecipePlanRows.load(same, actor, id)
                requireCommand(rows, command)
                if (!sameReply(reply, rows.record.reply)) rootFail()
                authority.authorizeRoot(rows.record, selection = true)
                expected = rows
            },
            mutate = { same ->
                admitted = receipt(same, command).also { requireReceipt(it, command, null) }
                val inputs = authority.inputs()
                if (body.getValue("preferenceVersion").jsonPrimitive.content.toBigDecimal().stripTrailingZeros().toPlainString() != inputs.preferenceRevision)
                    rootFail(PlanningFailureCode.PREFERENCE_CHANGED)
                val edges = authority.substitutionView(); val view = edges.recipes
                val saved = body["savedRecipeId"]?.jsonPrimitive?.content?.let(UUID::fromString)?.let { id ->
                    authority.savedMaterial(id).let { material -> material to authority.savedSelection(id, material) }
                }
                val post = body["sourcePostId"]?.jsonPrimitive?.content?.let(UUID::fromString)?.let { id ->
                    authority.postMaterial(id, body.getValue("sourcePostVersion").jsonPrimitive.long)
                }
                val anchor = view.verifyAnchor(view.releaseId, view.revision, view.requestSha256,
                    view.taxonomyRevision, view.taxonomySha256, view.versionCount)
                val scan = when (val selected = RecipeSubstitutionCatalogScanner(edges, authority.policy).scan(command.body,
                    inputs.context(), RecipeSubstitutionScanBudget(PlanningScanBudget(128, 128), 1024, 1024), pageSize = 16,
                    savedSource = saved?.second, postSource = post?.let {
                        RecipeSubstitutionPostSource(it.postId, it.postVersion, it.source)
                    })) {
                    is PortResult.Value -> selected.value
                    is PortResult.Failure -> rootFail(when (selected.reason) {
                        FailureReason.NOT_CONFIGURED -> PlanningFailureCode.NOT_CONFIGURED
                        FailureReason.INVALID_DATA -> PlanningFailureCode.INPUT_INVALID
                        else -> PlanningFailureCode.RECIPE_UNAVAILABLE
                    })
                }
                val created = rootNow(same).truncatedTo(ChronoUnit.MILLIS)
                val material = try { RootRecipePlanRecord.create(command, inputs, authority.policy, anchor, scan,
                    DerivedPlanReceipt(UUID.randomUUID(), created, created.plusSeconds(policy.planRetentionSeconds.toLong())), saved?.first?.evidence(), post?.evidence()) }
                    catch (_: DerivedPlanMaterialException) { rootFail(PlanningFailureCode.INPUTS_CHANGED) }
                authority.authorizeRoot(material, selection = true)
                val requestId = UUID.randomUUID()
                insert(same, command, requestId, material)
                val draft = EventDraft(UUID.randomUUID(), "planning.plan.created.v1", 1, "plan", material.planId, 1,
                    "planning", key.toString(), key, buildJsonObject {
                        put("principalId", actor.principalId.toString()); put("planId", material.planId.toString())
                        material.recipeVersionId?.let { put("recipeVersionId", it.toString()) }
                        put("status", material.status); put("rankingVersion", authority.policy.version)
                    }, owner = EventOwner.principal(actor.environment, actor.kind, actor.principalId))
                OutboxStore(transactions).append(same, draft)
                val eventImage = event(same, draft.eventId)
                if (eventImage["event_type"] != JsonPrimitive(draft.eventType) || eventImage["aggregate_id"] != JsonPrimitive(material.planId.toString()) ||
                    eventImage["payload"] != draft.data || eventImage["causation_id"] != JsonPrimitive(key.toString()) ||
                    eventImage["producer"] != JsonPrimitive("planning") || eventImage["schema_version"] != JsonPrimitive(1) ||
                    eventImage["aggregate_version"] != JsonPrimitive(1) || eventImage["aggregate_type"] != JsonPrimitive("plan") ||
                    eventImage["correlation_id"] != JsonPrimitive(key.toString()) || eventImage["attempts"] != JsonPrimitive(0) ||
                    listOf("published_at", "lease_token", "lease_expires_at", "quarantined_at", "last_failure_code").any { eventImage[it] != JsonNull }) rootFail()
                eventProof = draft.eventId to eventImage
                expected = RootRecipePlanRows.load(same, actor, material.planId).also {
                    if (it.record.snapshotText != material.snapshotText || it.record.contextText != material.contextText || it.record.proofText != material.proofText) rootFail()
                }
                material.reply
            })
        expected?.let { original ->
            val actual = RootRecipePlanRows.load(c, actor, original.record.planId)
            if (actual.plan != original.plan || actual.request != original.request) rootFail()
            requireCommand(actual, command)
            val completed = receipt(c, command); requireReceipt(completed, command, actual.record.reply)
            admitted?.let { before ->
                val allowed = setOf("state", "response_code", "response_json", "response_etag", "updated_at", "expires_at")
                if (before.filterKeys { it !in allowed } != completed.filterKeys { it !in allowed }) rootFail()
            }
            eventProof?.let { if (event(c, it.first) != it.second) rootFail() }
            authority.authorizeRoot(actual.record, selection = true)
            // Recheck unchanged durable effects after actual provider/catalog callbacks.
            val final = RootRecipePlanRows.load(c, actor, actual.record.planId)
            if (final.plan != actual.plan || final.request != actual.request || receipt(c, command) != completed) rootFail()
            eventProof?.let { if (event(c, it.first) != it.second) rootFail() }
            authority.current()
            val at = rootNow(c)
            if (at < actual.record.createdAt || !actual.record.expiresAt.isAfter(at)) rootFail(PlanningFailureCode.PLAN_EXPIRED)
            if (!rootInstant(completed, "expires_at").isAfter(at) || admitted?.let { !rootInstant(it, "expires_at").isAfter(at) } == true) rootFail(PlanningFailureCode.PLAN_EXPIRED)
            authority.checkAt(at)
        }
        if (expected == null) { authority.current(); authority.checkAt(rootNow(c)) }
        return result
    }
    private fun requireCommand(rows: RootRecipePlanRows, command: RootRecipePlanCommand) {
        val context = rootJson(rows.record.contextText)
        if (context["commandKey"] != JsonPrimitive(command.key.toString()) || context["commandRequestHash"] != JsonPrimitive(command.identity.requestHash) ||
            dpSemantic(rootJson(context.rootText("requestText"))) != dpSemantic(dpJson(command.body))) rootFail()
    }
    private fun sameReply(a: StoredReply, b: StoredReply) = a.status == b.status && a.etag == b.etag &&
        dpSemantic(a.body ?: JsonNull) == dpSemantic(b.body ?: JsonNull)
    private fun insert(c: Connection, command: RootRecipePlanCommand, requestId: UUID, m: RootRecipePlanRecord) {
        c.prepareStatement("""INSERT INTO planning.plan_requests(environment,actor_kind,principal_id,id,request_text,request_hash,evidence_text,evidence_hash,
            policy_text,current_plan_id,version,created_at,expires_at,cursor_expires_at,storage_format,derived_operation,derived_command_key,derived_request_sha256)
            VALUES(?,'account',?,?,?,?,?,?,?,?,1,?,?,?,?,'createPlan',?,?)""").use { s ->
            s.setString(1, command.actor.environment); s.setObject(2, command.actor.principalId); s.setObject(3, requestId)
            s.setString(4, command.body.encodeUtf8().decodeToString()); s.setString(5, command.bodyHash); s.setString(6, m.contextText); s.setString(7, m.contextHash)
            s.setString(8, rootJson(m.contextText).getValue("policy").toString()); s.setObject(9, m.planId)
            s.setObject(10, m.createdAt.atOffset(ZoneOffset.UTC)); s.setObject(11, m.expiresAt.atOffset(ZoneOffset.UTC)); s.setObject(12, m.expiresAt.atOffset(ZoneOffset.UTC))
            s.setInt(13, m.storageFormat); s.setObject(14, command.key); s.setString(15, command.identity.requestHash); check(s.executeUpdate() == 1)
        }
        c.prepareStatement("""INSERT INTO planning.plans(environment,actor_kind,principal_id,id,request_id,version,position,recipe_version_id,status,
            snapshot_text,snapshot_hash,proof_text,proof_hash,created_at,storage_format) VALUES(?,'account',?,?,?,1,?,?,?,?,?,?,?,?,?)""").use { s ->
            s.setString(1, command.actor.environment); s.setObject(2, command.actor.principalId); s.setObject(3, m.planId); s.setObject(4, requestId)
            s.setInt(5, if (m.status == "ready") 0 else -1); s.setObject(6, m.recipeVersionId); s.setString(7, m.status); s.setString(8, m.snapshotText)
            s.setString(9, m.snapshotHash); s.setString(10, m.proofText); s.setString(11, m.proofHash); s.setObject(12, m.createdAt.atOffset(ZoneOffset.UTC))
            s.setInt(13, m.storageFormat)
            check(s.executeUpdate() == 1)
        }
    }
    private fun receipt(c: Connection, command: RootRecipePlanCommand) = c.prepareStatement(
        "SELECT to_jsonb(r)::text FROM platform.idempotency r WHERE principal_scope=? AND operation_id='createPlan' AND key=? FOR SHARE").use { s ->
        s.setString(1, command.identity.scope.storageKey); s.setObject(2, command.key)
        s.executeQuery().use { r -> if (!r.next()) rootFail(); rootJson(r.getString(1)).also { if (r.next()) rootFail() } }
    }
    private fun requireReceipt(row: JsonObject, command: RootRecipePlanCommand, reply: StoredReply?) {
        if (row["principal_scope"] != JsonPrimitive(command.identity.scope.storageKey) || row["operation_id"] != JsonPrimitive("createPlan") ||
            row["key"] != JsonPrimitive(command.key.toString()) || row["request_hash"] != JsonPrimitive(command.identity.requestHash) ||
            row["state"] != JsonPrimitive(if (reply == null) "pending" else "completed") || row["tombstoned_at"] != JsonNull) rootFail()
        if (reply == null) { if (listOf("response_code", "response_json", "response_etag").any { row[it] != JsonNull }) rootFail() }
        else if (row["response_code"] != JsonPrimitive(201) || dpSemantic(row.getValue("response_json")) != dpSemantic(reply.body ?: JsonNull) || row["response_etag"] != JsonPrimitive(reply.etag)) rootFail()
    }
    private fun event(c: Connection, id: UUID) = c.prepareStatement("SELECT to_jsonb(e)::text FROM platform.outbox e WHERE event_id=? FOR SHARE").use { s ->
        s.setObject(1, id); s.executeQuery().use { r -> if (!r.next()) rootFail(); rootJson(r.getString(1)).also { if (r.next()) rootFail() } }
    }
}
