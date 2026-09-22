package com.feedme.server.planning

import com.feedme.contracts.*
import com.feedme.core.ports.PortResult
import com.feedme.planning.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import java.math.BigDecimal
import java.security.SecureRandom
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.*

/**
 * Transactional owner-scoped application service; no HTTP/provider/editorial adapter or worker.
 * Original request, input evidence, candidate order, plan snapshot and explanation proof persist.
 * Alternatives reconstruct the original pure engine, never a process-local continuation identity.
 * A lineage has one head: consuming its exact parent cursor and creating a child are atomic with
 * the command receipt and outbox. Old plans remain immutable for Back/owned session pinning.
 * Cursor hashes support lookup; the exact opaque cursor is also retained inside private Plan
 * bodies/receipts. It is NOT an authentication bearer and is never logged or put in events.
 */
class PlansStore(val environment: String, private val transactions: PgTransactions,
    private val authority: PlanningAuthority, private val policy: PlanningServicePolicy,
    private val cursors: PlanningCursors) {
    private val commands = DurableCommands(transactions)
    private val outbox = OutboxStore(transactions)
    private var derivedReaders: ((Connection, VerifiedPlanningPrincipal) -> AccountDerivedPlanReader)? = null
    internal constructor(environment: String, transactions: PgTransactions, authority: PlanningAuthority,
        policy: PlanningServicePolicy, cursors: PlanningCursors,
        derivedReaders: (Connection, VerifiedPlanningPrincipal) -> AccountDerivedPlanReader) :
        this(environment, transactions, authority, policy, cursors) {
        this.derivedReaders = derivedReaders
    }
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    fun createPlan(principal: VerifiedPlanningPrincipal, key: UUID, body: JsonObject): CommandResult {
        val request = request("createPlan", body); supported(request)
        val requestId = UUID.randomUUID(); val planId = UUID.randomUUID(); val cursor = newCursor()
        return command(principal, "createPlan", key, emptyMap(), request) { c ->
            authority.requireNewPlanningEnabledAndQuota(c, principal)
            val current = authority.lockCurrentSnapshot(c, principal, request)
            requirePreferences(request, current); requireDirectSource(request, current)
            val sequence = sequence(request, current)
            val now = now(c); val expires = now.plusSeconds(policy.planRetentionSeconds.toLong())
            val cursorExpires = now.plusSeconds(policy.cursorLifetimeSeconds.toLong())
            val requestText = request.encodeUtf8().decodeToString(); val evidence = current.copyForStorage().encodeUtf8()
            exec(c, "INSERT INTO planning.plan_requests(environment,actor_kind,principal_id,id,request_text,request_hash,evidence_text,evidence_hash,ordered_ids,policy_text,version,created_at,expires_at,cursor_expires_at) VALUES(?,?,?,?,?,?,?,?,?::jsonb,?,1,?,?,?)") {
                bindOwner(principal); setObject(4, requestId); setString(5, requestText); setString(6, digest(request.encodeUtf8()))
                setString(7, evidence.decodeToString()); setString(8, digest(evidence)); setString(9, JsonArray(sequence.ids.map(::JsonPrimitive)).toString())
                setString(10, policyText()); setObject(11, date(now)); setObject(12, date(expires)); setObject(13, date(cursorExpires))
            }
            val row = Lineage(requestId, request, current, sequence.ids, null, 1, expires, cursorExpires, policyText())
            val next = cursor.takeIf { sequence.first.next != null }
            val reply = persist(c, principal, key, row, planId, null, sequence.first.decision,
                if (sequence.ids.isEmpty()) -1 else 0, next, now, "createPlan", 201)
            moveHead(c, principal, row, planId)
            requireStillLive(c, row, cursorRequired = false)
            reply
        }
    }

    fun getPlan(principal: VerifiedPlanningPrincipal, planId: UUID): StoredReply = read(principal, planId) { c ->
        derivedReader(c, principal, planId)?.let { return@read it.getPlan(planId) }
        val plan = plan(c, principal, planId); val lineage = lineage(c, principal, plan.requestId)
        authorizeStored(c, principal, lineage, plan, selection = false)
        checkedReply("getPlan", 200, plan.body, plan.version)
    }

    /** Explicit same-transaction READ for a separately authorized post attachment. This
     * proves the actual owned Plan, not redistribution or confirmation of its changes. */
    internal fun readOwnedPostPlan(connection: Connection, principal: VerifiedPlanningPrincipal, planId: UUID): JsonObject = safe {
        check(!connection.autoCommit)
        checkPrincipal(principal); authority.lockPrincipal(connection, principal)
        val derived = derivedReader(connection, principal, planId)
        val reply = if (derived != null) derived.getPlan(planId) else {
            val plan = plan(connection, principal, planId)
            val lineage = lineage(connection, principal, plan.requestId)
            authorizeStored(connection, principal, lineage, plan, selection = false)
            requireReadLifetime(connection, principal, lineage, plan, selection = false)
            checkedReply("getPlan", 200, plan.body, plan.version)
        }
        authority.lockPrincipal(connection, principal); derived?.revalidate()
        reply.body?.jsonObject ?: fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
    }

    /**
     * Database-transaction composition only; never opens/commits a second transaction. The caller
     * holds the same exclusive principal lock before its receipt. NEW_SELECTION rechecks live
     * READY/current inputs; EXISTING_PIN requires the exact owned cooking row and bounded lifetime.
     * Neither an enum nor a caller-provided hash can manufacture an existing pin. Current editorial
     * rights/recall checks always run. The returned bytes are not an offline content manifest.
     */
    fun lockCookingPlan(connection: Connection, principal: VerifiedPlanningPrincipal, planId: UUID,
        use: CookingPlanUse, sessionId: UUID? = null): CookingPlanSnapshot = safe {
        check(!connection.autoCommit) { "Cooking selection requires an owned transaction" }
        checkPrincipal(principal)
        authority.lockPrincipal(connection, principal)
        derivedReader(connection, principal, planId)?.let { reader ->
            val snapshot = reader.lockCookingPlan(planId, use, sessionId)
            authority.lockPrincipal(connection, principal)
            reader.revalidate()
            return@safe snapshot
        }
        val plan = plan(connection, principal, planId); val lineage = lineage(connection, principal, plan.requestId)
        if (use == CookingPlanUse.NEW_SELECTION) {
            if (sessionId != null) fail(PlanningFailureCode.INPUT_INVALID)
            if (plan.body.text("status") != "ready" || plan.recipeId == null) fail(PlanningFailureCode.MODE_CONFIRMATION_REQUIRED)
        } else if (sessionId == null || !hasCookingPin(connection, principal, lineage, plan, sessionId)) {
            fail(PlanningFailureCode.PLAN_UNAVAILABLE)
        }
        authorizeStored(connection, principal, lineage, plan, selection = use == CookingPlanUse.NEW_SELECTION)
        if (use == CookingPlanUse.EXISTING_PIN && !hasCookingPin(connection, principal, lineage, plan, sessionId))
            fail(PlanningFailureCode.PLAN_EXPIRED)
        // The caller still owns this transaction; lineage/catalog waits cannot extend a
        // provider proof. Recheck the same already-held principal before returning the pin.
        authority.lockPrincipal(connection, principal)
        requireReadLifetime(connection, principal, lineage, plan, selection = use == CookingPlanUse.NEW_SELECTION)
        if (use == CookingPlanUse.EXISTING_PIN && !hasCookingPin(connection, principal, lineage, plan, sessionId))
            fail(PlanningFailureCode.PLAN_EXPIRED)
        CookingPlanSnapshot(plan.snapshotText, plan.hash, plan.proofHash, digest(lineage.evidence.copyForStorage().encodeUtf8()))
    }

    fun getPlanExplanation(principal: VerifiedPlanningPrincipal, planId: UUID, cursor: String? = null, limit: Int = 20): StoredReply = read(principal, planId) { c ->
        if (limit !in 1..50) fail(PlanningFailureCode.INPUT_INVALID)
        derivedReader(c, principal, planId)?.let { return@read it.getExplanation(planId, cursor, limit, cursors) }
        val plan = plan(c, principal, planId); val lineage = lineage(c, principal, plan.requestId)
        authorizeStored(c, principal, lineage, plan, selection = false)
        val binding = "$environment:${principal.kind}:${principal.principalId}:$planId:${plan.hash}"
        val offset = cursors.offset(binding, cursor); val reasons = plan.body.getValue("reasons").jsonArray
        if (offset > reasons.size) fail(PlanningFailureCode.CURSOR_INVALID)
        val selected = reasons.drop(offset).take(limit); val end = offset + selected.size
        checkedReply("getPlanExplanation", 200, buildJsonObject {
            put("items", JsonArray(selected)); put("nextCursor", if (end < reasons.size) JsonPrimitive(cursors.explanation(binding, end)) else JsonNull)
            put("serverTime", now(c).toString())
        })
    }

    /** The canonical cursor pins parent/version; the current OpenAPI does not add an If-Match header. */
    fun nextPlan(principal: VerifiedPlanningPrincipal, key: UUID, parentPlanId: UUID, body: JsonObject): CommandResult {
        val input = request("nextPlan", body); val root = json(input)
        if (root.text("reason") != "alternative" || root.keys.any { it !in setOf("reason", "constraints", "continuationCursor", "excludeRecipeVersionIds") })
            fail(PlanningFailureCode.NOT_CONFIGURED)
        val token = root["continuationCursor"]?.jsonPrimitive?.content ?: fail(PlanningFailureCode.CURSOR_INVALID)
        if (!token.matches(Regex("[A-Za-z0-9_-]{43}"))) fail(PlanningFailureCode.CURSOR_INVALID)
        val planId = UUID.randomUUID(); val nextCursor = newCursor()
        return command(principal, "nextPlan", key, mapOf("planId" to parentPlanId.toString()), input) { c ->
            val parent = plan(c, principal, parentPlanId); val lineage = lineage(c, principal, parent.requestId)
            requireStillLive(c, lineage, cursorRequired = true)
            if (lineage.head != parent.id || parent.cursorHash == null || digest(token.toByteArray(Charsets.US_ASCII)) != parent.cursorHash)
                fail(PlanningFailureCode.CURSOR_INVALID)
            if (canonical(root.getValue("constraints")) != canonical(parent.body.getValue("constraints"))) fail(PlanningFailureCode.INPUTS_CHANGED)
            if (lineage.policy != policyText()) fail(PlanningFailureCode.INPUTS_CHANGED)
            authority.requireNewPlanningEnabledAndQuota(c, principal)
            val current = authority.lockCurrentSnapshot(c, principal, lineage.request)
            requirePreferences(lineage.request, current)
            if (!lineage.evidence.samePrivateInputs(current) || !lineage.evidence.sameSavedSource(current)) fail(PlanningFailureCode.INPUTS_CHANGED)
            requireDirectSource(lineage.request, current)
            val excluded = root["excludeRecipeVersionIds"]?.jsonArray?.map { it.jsonPrimitive.content.lowercase() }?.toSet().orEmpty()
            val presented = query(c, "SELECT recipe_version_id FROM planning.plans WHERE environment=? AND actor_kind=? AND principal_id=? AND request_id=? AND recipe_version_id IS NOT NULL", {
                bindOwner(principal); setObject(4, lineage.id)
            }) { rows -> buildSet { while (rows.next()) add(rows.getObject(1, UUID::class.java).toString()) } }
            if (!presented.containsAll(excluded)) fail(PlanningFailureCode.INPUT_INVALID)
            val original = sequence(lineage.request, lineage.evidence)
            if (original.ids != lineage.ordered || parent.position !in original.pages.indices) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
            val continuation = original.pages[parent.position].next ?: fail(PlanningFailureCode.CURSOR_INVALID)
            val page = engineValue(original.engine.next(continuation, lineage.request, current.context(), current.catalog(excluded)), current = true)
            val position = page.decision.recipe?.id?.value?.lowercase()?.let { id -> lineage.ordered.indexOf(id).takeIf { it > parent.position }
                ?: fail(PlanningFailureCode.STORAGE_UNAVAILABLE) } ?: lineage.ordered.size
            val result = persist(c, principal, key, lineage, planId, parent.id, page.decision, position,
                nextCursor.takeIf { page.next != null }, now(c), "nextPlan", 200)
            moveHead(c, principal, lineage, planId)
            requireStillLive(c, lineage, cursorRequired = true)
            result
        }
    }

    private fun authorizeStored(c: Connection, principal: VerifiedPlanningPrincipal, lineage: Lineage, plan: PlanRow, selection: Boolean) {
        requireReadLifetime(c, principal, lineage, plan, selection)
        if (plan.proof.text("requestHash") != digest(lineage.request.encodeUtf8()) ||
            plan.proof.text("evidenceHash") != digest(lineage.evidence.copyForStorage().encodeUtf8()) ||
            plan.proof.text("preferenceVersion") != lineage.evidence.preferences.text("revision") ||
            plan.proof.text("pantryRevision") != lineage.evidence.pantry.text("revision")) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        val current = authority.lockCurrentSnapshot(c, principal, lineage.request)
        if (json(lineage.request).containsKey("savedRecipeId")) requireDirectSource(lineage.request, current)
        if (!lineage.evidence.sameSavedSource(current)) fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
        current.savedSource?.let { saved ->
            if (principal.kind != CommandActor.ACCOUNT || saved.text("environment") != environment ||
                saved.text("principalId") != principal.principalId.toString()) fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
        }
        if (selection) {
            requirePreferences(lineage.request, current)
            if (lineage.policy != policyText() || !lineage.evidence.samePrivateInputs(current) ||
                !lineage.evidence.sameTaxonomy(current)) fail(PlanningFailureCode.INPUTS_CHANGED)
        }
        plan.recipeId?.let { id ->
            val old = lineage.evidence.candidate(id.toString()) ?: fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
            val fresh = current.candidate(id.toString()) ?: fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
            if (current.savedSource != null) {
                // The current account adapter revalidates the exact established grant/copy
                // and current recall. It deliberately need not certify free/new publication.
                if (old != fresh) fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
                return@let
            }
            val recipe = fresh.getValue("recipe").jsonObject
            if (recipe.text("reviewStatus") == "recalled") fail(PlanningFailureCode.RECIPE_RECALLED)
            if (recipe.text("reviewStatus") !in (if (selection) setOf("published") else setOf("published", "retired")) ||
                !fresh.getValue("review").jsonObject.bool("freeCatalogEligible")) fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
            val originalRecipe = old.getValue("recipe").jsonObject
            val versionOrder = BigDecimal(recipe.getValue("version").jsonPrimitive.content)
                .compareTo(BigDecimal(originalRecipe.getValue("version").jsonPrimitive.content))
            // Resource metadata can advance without changing immutable recipe material. A
            // reused or regressed version cannot certify changed lifecycle/review metadata.
            if (versionOrder < 0 || (versionOrder == 0 && originalRecipe != recipe))
                fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
            val lifecycleFields = setOf("version", "updatedAt", "reviewStatus", "recallReasonCode", "reviewedAt", "reviewerLabel")
            val immutableOld = originalRecipe - lifecycleFields
            val immutableNew = recipe - lifecycleFields
            if (JsonObject(immutableOld) != JsonObject(immutableNew) || old["review"] != fresh["review"])
                fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
        }
        requireReadLifetime(c, principal, lineage, plan, selection)
    }

    private fun requireReadLifetime(c: Connection, principal: VerifiedPlanningPrincipal, lineage: Lineage, plan: PlanRow, selection: Boolean) {
        if (selection || lineage.expires.isAfter(now(c))) requireStillLive(c, lineage, cursorRequired = false)
        else if (!hasCookingPin(c, principal, lineage, plan, null)) fail(PlanningFailureCode.PLAN_EXPIRED)
    }

    private fun hasCookingPin(c: Connection, principal: VerifiedPlanningPrincipal, lineage: Lineage, plan: PlanRow, sessionId: UUID?): Boolean = query(c,
        "SELECT 1 FROM cooking.cook_sessions WHERE environment=? AND actor_kind=? AND principal_id=? AND plan_id=? " +
            "AND (?::uuid IS NULL OR id=?::uuid) AND expires_at>clock_timestamp() AND plan_snapshot_hash=? " +
            "AND plan_proof_hash=? AND plan_evidence_hash=? AND plan_snapshot_text=? LIMIT 1", {
            bindOwner(principal); setObject(4, plan.id); setObject(5, sessionId); setObject(6, sessionId)
            setString(7, plan.hash); setString(8, plan.proofHash); setString(9, digest(lineage.evidence.copyForStorage().encodeUtf8()))
            setString(10, plan.snapshotText)
        }) { it.next() }

    private fun persist(c: Connection, principal: VerifiedPlanningPrincipal, command: UUID, lineage: Lineage, planId: UUID,
        parentId: UUID?, decision: PlanningDecision, position: Int, cursor: String?, createdAt: Instant, operation: String, status: Int): StoredReply {
        val result = adapter.materialize(decision, PlanningReceipt(planId.toString(), "1", createdAt.toString(), createdAt.toString(), parentId?.toString(), cursor))
        val materialized = when (result) { is PortResult.Value -> result.value; is PortResult.Failure -> fail(PlanningFailureCode.STORAGE_UNAVAILABLE) }
        val bytes = materialized.document.encodeUtf8(); val body = json(materialized.document)
        val proof = buildJsonObject {
            put("version", 1); put("preferenceVersion", decision.preferenceVersion); put("pantryRevision", lineage.evidence.pantry.text("revision"))
            put("catalogRevision", decision.catalogRevision); put("eligibilityCatalogRevision", decision.eligibilityCatalogRevision)
            put("taxonomyRevision", decision.taxonomyRevision); put("rankingVersion", decision.policyVersion)
            put("requestHash", digest(lineage.request.encodeUtf8())); put("evidenceHash", digest(lineage.evidence.copyForStorage().encodeUtf8()))
            put("issues", JsonArray(decision.issues.map { JsonPrimitive(it.name) })); put("facts", body.getValue("reasons"))
        }
        exec(c, "INSERT INTO planning.plans(environment,actor_kind,principal_id,id,request_id,parent_plan_id,version,position,recipe_version_id,status,snapshot_text,snapshot_hash,proof_text,proof_hash,next_cursor_hash,created_at) VALUES(?,?,?,?,?,?,1,?,?,?,?,?,?,?,?,?)") {
            bindOwner(principal); setObject(4, planId); setObject(5, lineage.id); setObject(6, parentId); setInt(7, position)
            setObject(8, decision.recipe?.id?.value?.let(UUID::fromString)); setString(9, body.text("status")); setString(10, bytes.decodeToString())
            setString(11, digest(bytes)); setString(12, proof.toString()); setString(13, digest(wire(proof).encodeUtf8()))
            setString(14, cursor?.let { digest(it.toByteArray(Charsets.US_ASCII)) }); setObject(15, date(createdAt))
        }
        outbox.append(c, EventDraft(UUID.randomUUID(), "planning.plan.created.v1", 1, "plan", planId, 1, "planning", command.toString(), command,
            buildJsonObject { put("principalId", principal.principalId.toString()); put("planId", planId.toString())
                decision.recipe?.let { put("recipeVersionId", it.id.value) }; put("status", body.text("status")); put("rankingVersion", decision.policyVersion) },
            owner = EventOwner.principal(environment, principal.kind, principal.principalId)))
        return checkedReply(operation, status, body, 1)
    }

    private fun moveHead(c: Connection, principal: VerifiedPlanningPrincipal, lineage: Lineage, id: UUID) = exec(c,
        "UPDATE planning.plan_requests SET current_plan_id=?,version=version+1 WHERE environment=? AND actor_kind=? AND principal_id=? AND id=? AND version=? AND current_plan_id IS NOT DISTINCT FROM ?::uuid") {
        setObject(1, id); setString(2, environment); setString(3, principal.kind.name.lowercase()); setObject(4, principal.principalId)
        setObject(5, lineage.id); setLong(6, lineage.version); setObject(7, lineage.head)
    }

    private fun sequence(request: WireDocument, evidence: PlanningEvidenceSnapshot): Sequence {
        val engine = DeterministicPlanner(PlanningPolicy(policy.rankingVersion, policy.heatEnabled, policy.improveEnabled))
        val context = evidence.context(); val catalog = evidence.catalog(); val first = engineValue(engine.plan(request, context, catalog))
        val pages = mutableListOf(first); val ids = mutableListOf<String>()
        first.decision.recipe?.let { ids += it.id.value.lowercase() }
        var page = first
        while (page.next != null) {
            if (pages.size >= 128) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
            page = engineValue(engine.next(page.next!!, request, context, catalog))
            if (page.decision.status != PlanningStatus.READY) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
            pages += page; ids += page.decision.recipe!!.id.value.lowercase()
        }
        if (ids.distinct().size != ids.size) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        return Sequence(engine, first, pages, ids)
    }

    private fun command(principal: VerifiedPlanningPrincipal, operation: String, key: UUID, path: Map<String, String>, body: WireDocument,
        mutate: (Connection) -> StoredReply): CommandResult = safe {
        checkPrincipal(principal)
        var exactReplay: StoredReply? = null
        val command = CommandIdentity(PrincipalScope(environment, principal.kind, principal.principalId), operation, key, path, body = json(body))
        val result = transactions.run { c ->
            val applied = commands.executeInTransaction(c, command, { authority.lockPrincipal(it, principal) }, {}, { db, cached ->
                val id = UUID.fromString(cached.body!!.jsonObject.text("id")); val plan = plan(db, principal, id); val lineage = lineage(db, principal, plan.requestId)
                authorizeStored(db, principal, lineage, plan, selection = plan.recipeId != null)
                if (canonical(cached.body) != canonical(plan.body) || cached.etag != "\"${plan.version}\"") fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                exactReplay = checkedReply(operation, cached.status, plan.body, plan.version)
            }, mutate)
            // Includes replay and non-success receipt outcomes, after every receipt/domain
            // wait and before the owning transaction can commit or disclose its result.
            authority.lockPrincipal(c, principal)
            // The final provider check may itself wait. Re-read the already-owned lineage
            // and fence DB time after that wait, including completed receipt writes.
            // An exact replay does not consume the alternative cursor again.
            val successfulReply = when (applied) {
                is CommandResult.Applied -> applied.reply
                is CommandResult.Replayed -> applied.reply
                else -> null
            }
            if (successfulReply != null) {
                val id = UUID.fromString(successfulReply.body!!.jsonObject.text("id"))
                val stored = plan(c, principal, id); val row = lineage(c, principal, stored.requestId)
                if (applied is CommandResult.Applied) requireStillLive(c, row, cursorRequired = operation == "nextPlan")
                else requireReadLifetime(c, principal, row, stored, selection = stored.recipeId != null)
            }
            applied
        }
        if (result is CommandResult.Replayed) CommandResult.Replayed(checkNotNull(exactReplay)) else result
    }
    private fun <T> read(principal: VerifiedPlanningPrincipal, planId: UUID, action: (Connection) -> T): T = safe {
        checkPrincipal(principal); transactions.run { c ->
            authority.lockPrincipal(c, principal)
            action(c).also {
                authority.lockPrincipal(c, principal)
                val derived = derivedReader(c, principal, planId)
                if (derived != null) derived.revalidate()
                else {
                    val stored = plan(c, principal, planId); val row = lineage(c, principal, stored.requestId)
                    requireReadLifetime(c, principal, row, stored, selection = false)
                }
            }
        }
    }

    /** Historical format-3 material for a separately authorized NEW Saved copy. Neither this
     * reader nor Plan expiry waives the caller's actual new-copy grant and final rights fence. */
    internal fun lockOwnedDerivedCopy(connection: Connection, principal: VerifiedPlanningPrincipal, planId: UUID): JsonObject = safe {
        check(!connection.autoCommit)
        checkPrincipal(principal); authority.lockPrincipal(connection, principal)
        val reader = derivedReader(connection, principal, planId) ?: fail(PlanningFailureCode.NOT_CONFIGURED)
        val recipe = reader.ownedCopy(planId)
        authority.lockPrincipal(connection, principal); reader.revalidate()
        recipe
    }

    private fun derivedReader(c: Connection, principal: VerifiedPlanningPrincipal, id: UUID): AccountDerivedPlanReader? {
        val format = query(c, "SELECT storage_format FROM planning.plans WHERE environment=? AND actor_kind=? AND principal_id=? AND id=?", {
            bindOwner(principal); setObject(4, id)
        }) { rows -> if (!rows.next()) null else rows.getInt(1).also { if (rows.next()) fail(PlanningFailureCode.STORAGE_UNAVAILABLE) } }
        if (format !in setOf(3, 4, 5, 6)) return null
        if (principal.kind != CommandActor.ACCOUNT) fail(PlanningFailureCode.NOT_CONFIGURED)
        return derivedReaders?.invoke(c, principal) ?: fail(PlanningFailureCode.NOT_CONFIGURED)
    }
    private fun plan(c: Connection, principal: VerifiedPlanningPrincipal, id: UUID): PlanRow = query(c,
        "SELECT * FROM planning.plans WHERE environment=? AND actor_kind=? AND principal_id=? AND id=?", { bindOwner(principal); setObject(4, id) }) { r ->
        if (!r.next()) fail(PlanningFailureCode.PLAN_UNAVAILABLE)
        // This reader owns only the finite, evidence-backed v1 lineage. Other formats
        // need their own source/proof authority; a canonical Plan body is not a grant.
        if (r.getInt("storage_format") != 1) fail(PlanningFailureCode.NOT_CONFIGURED)
        val bytes = r.getString("snapshot_text").toByteArray(Charsets.UTF_8); if (digest(bytes) != r.getString("snapshot_hash")) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        val body = json(WireDocument.decode(bytes, WireLimits(262144, 32)))
        if (validator.validateResponse("getPlan", 200, bytes, "application/json") != BodyValidationResult.Valid ||
            body.text("id") != id.toString() || body.getValue("version").jsonPrimitive.long != r.getLong("version") || body.text("status") != r.getString("status"))
            fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        val recipeId = r.getObject("recipe_version_id", UUID::class.java)
        if (body["recipeVersionId"]?.jsonPrimitive?.content?.let(UUID::fromString) != recipeId ||
            body["parentPlanId"]?.jsonPrimitive?.content?.let(UUID::fromString) != r.getObject("parent_plan_id", UUID::class.java)) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        val proofBytes = r.getString("proof_text").toByteArray(Charsets.UTF_8)
        if (digest(proofBytes) != r.getString("proof_hash")) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        val proof = json(WireDocument.decode(proofBytes, WireLimits(32768, 16)))
        if (proof["version"] != JsonPrimitive(1) || proof["facts"] != body["reasons"] || proof["catalogRevision"] != body["catalogRevision"])
            fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        val cursor = body["nextAlternativeCursor"].takeUnless { it == JsonNull }?.jsonPrimitive?.content
        if (cursor?.let { digest(it.toByteArray(Charsets.US_ASCII)) } != r.getString("next_cursor_hash")) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        PlanRow(id, r.getObject("request_id", UUID::class.java), r.getLong("version"), r.getInt("position"),
            recipeId, body, r.getString("snapshot_hash"), r.getString("next_cursor_hash"), proof, r.getString("snapshot_text"), r.getString("proof_hash"))
    }
    private fun lineage(c: Connection, principal: VerifiedPlanningPrincipal, id: UUID): Lineage = query(c,
        "SELECT * FROM planning.plan_requests WHERE environment=? AND actor_kind=? AND principal_id=? AND id=? FOR UPDATE", { bindOwner(principal); setObject(4, id) }) { r ->
        if (!r.next()) fail(PlanningFailureCode.PLAN_UNAVAILABLE)
        if (r.getInt("storage_format") != 1) fail(PlanningFailureCode.NOT_CONFIGURED)
        val requestBytes = r.getString("request_text").toByteArray(Charsets.UTF_8); val evidenceBytes = r.getString("evidence_text").toByteArray(Charsets.UTF_8)
        if (digest(requestBytes) != r.getString("request_hash") || digest(evidenceBytes) != r.getString("evidence_hash")) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        val original = WireDocument.decode(requestBytes, WireLimits(65536, 32))
        if (validator.validateRequest("createPlan", requestBytes, "application/json") != BodyValidationResult.Valid) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        val ids = Json.parseToJsonElement(r.getString("ordered_ids")).jsonArray.map { it.jsonPrimitive.content }
        if (ids.size > 128 || ids.distinct().size != ids.size || ids.any { !CanonicalFormats.accepts("uuid", it) }) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        Lineage(id, original, PlanningEvidenceSnapshot.decode(evidenceBytes), ids, r.getObject("current_plan_id", UUID::class.java), r.getLong("version"),
            instant(r, "expires_at"), instant(r, "cursor_expires_at"), r.getString("policy_text"))
    }

    private fun requirePreferences(request: WireDocument, snapshot: PlanningEvidenceSnapshot) {
        val version = json(request)["preferenceVersion"]?.jsonPrimitive?.content ?: fail(PlanningFailureCode.PREFERENCE_CHANGED)
        if (BigDecimal(version).compareTo(BigDecimal(snapshot.preferences.text("revision"))) != 0) fail(PlanningFailureCode.PREFERENCE_CHANGED)
    }
    private fun requireDirectSource(request: WireDocument, snapshot: PlanningEvidenceSnapshot) {
        val body = json(request)
        val savedId = body["savedRecipeId"]?.jsonPrimitive?.content
        if (savedId != null) {
            val saved = snapshot.savedSource ?: fail(PlanningFailureCode.NOT_CONFIGURED)
            if (!saved.text("savedRecipeId").equals(savedId, true)) fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
            return
        }
        if (snapshot.savedSource != null) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        val source = body["sourceRecipeVersionId"]?.jsonPrimitive?.content ?: return
        val candidate = snapshot.candidate(source) ?: fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
        val recipe = candidate.getValue("recipe").jsonObject
        if (recipe.text("reviewStatus") == "recalled") fail(PlanningFailureCode.RECIPE_RECALLED)
        if (recipe.text("reviewStatus") != "published" || !candidate.getValue("review").jsonObject.bool("freeCatalogEligible")) fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
    }
    private fun requireStillLive(c: Connection, row: Lineage, cursorRequired: Boolean) {
        val now = now(c); if (!row.expires.isAfter(now)) fail(PlanningFailureCode.PLAN_EXPIRED)
        if (cursorRequired && !row.cursorExpires.isAfter(now)) fail(PlanningFailureCode.CURSOR_EXPIRED)
    }
    private fun supported(request: WireDocument) {
        val body = json(request)
        if (body.containsKey("sourcePostId") || body["naturalLanguage"]?.jsonPrimitive?.content?.isNotEmpty() == true ||
            body["intent"]?.jsonPrimitive?.content == "tonight" || body.getValue("constraints").jsonObject.keys.any { it.startsWith("household") }) fail(PlanningFailureCode.NOT_CONFIGURED)
        if (listOf("sourcePostId", "savedRecipeId", "sourceRecipeVersionId").count(body::containsKey) > 1) fail(PlanningFailureCode.INPUT_INVALID)
    }
    private fun request(operation: String, body: JsonObject): WireDocument = try {
        val doc = WireDocument.parse(body.toString(), WireLimits(65536, 32))
        if (validator.validateRequest(operation, doc.encodeUtf8(), "application/json") != BodyValidationResult.Valid) fail(PlanningFailureCode.INPUT_INVALID)
        doc
    } catch (failure: PlanningServiceFailure) { throw failure } catch (_: Exception) { fail(PlanningFailureCode.INPUT_INVALID) }
    private fun checkedReply(operation: String, status: Int, body: JsonObject, version: Long? = null): StoredReply {
        if (validator.validateResponse(operation, status, wire(body).encodeUtf8(), "application/json") != BodyValidationResult.Valid) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        return StoredReply(status, body, version?.let { "\"$it\"" })
    }
    private fun <T> engineValue(result: PortResult<T>, current: Boolean = false): T = when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> fail(when (result.reason) {
            com.feedme.core.ports.FailureReason.NOT_CONFIGURED -> PlanningFailureCode.NOT_CONFIGURED
            com.feedme.core.ports.FailureReason.CONFLICT -> if (current) PlanningFailureCode.INPUTS_CHANGED else PlanningFailureCode.INPUT_INVALID
            else -> PlanningFailureCode.STORAGE_UNAVAILABLE
        })
    }
    private fun policyText() = buildJsonObject { put("version", 1); put("rankingVersion", policy.rankingVersion); put("heatEnabled", policy.heatEnabled); put("improveEnabled", policy.improveEnabled) }.toString()
    private fun checkPrincipal(p: VerifiedPlanningPrincipal) { if (p.environment != environment) fail(PlanningFailureCode.UNAUTHENTICATED) }
    private fun PreparedStatement.bindOwner(p: VerifiedPlanningPrincipal) { setString(1, environment); setString(2, p.kind.name.lowercase()); setObject(3, p.principalId) }
    private fun now(c: Connection) = query(c, "SELECT clock_timestamp()", {}) { it.next(); it.getObject(1, OffsetDateTime::class.java).toInstant() }
    private fun instant(r: ResultSet, name: String) = r.getObject(name, OffsetDateTime::class.java).toInstant()
    private fun date(time: Instant) = OffsetDateTime.ofInstant(time, java.time.ZoneOffset.UTC)
    private fun exec(c: Connection, sql: String, bind: PreparedStatement.() -> Unit) { c.prepareStatement(sql).use { it.bind(); check(it.executeUpdate() == 1) } }
    private fun <T> query(c: Connection, sql: String, bind: PreparedStatement.() -> Unit, read: (ResultSet) -> T): T = c.prepareStatement(sql).use { it.bind(); it.executeQuery().use(read) }
    private fun <T> safe(action: () -> T): T = try { action() } catch (failure: PlanningServiceFailure) { throw failure }
        catch (failure: CommitOutcomeUnknown) { throw failure } catch (failure: kotlinx.coroutines.CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (_: Exception) { fail(PlanningFailureCode.STORAGE_UNAVAILABLE) }
    private class Lineage(val id: UUID, val request: WireDocument, val evidence: PlanningEvidenceSnapshot, val ordered: List<String>, val head: UUID?,
        val version: Long, val expires: Instant, val cursorExpires: Instant, val policy: String)
    private class PlanRow(val id: UUID, val requestId: UUID, val version: Long, val position: Int, val recipeId: UUID?, val body: JsonObject, val hash: String, val cursorHash: String?, val proof: JsonObject,
        val snapshotText: String, val proofHash: String)
    private class Sequence(val engine: DeterministicPlanner, val first: PlanningPage, val pages: List<PlanningPage>, val ids: List<String>)
    companion object {
        private val validator by lazy { ContractBodyValidator.bundled() }
        private val adapter by lazy { CanonicalPlanningAdapter() }
        private val random = SecureRandom()
        private fun newCursor() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        private fun fail(code: PlanningFailureCode): Nothing = throw PlanningServiceFailure(code)
        private fun canonical(value: JsonElement): String = when (value) {
            is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { (k, v) -> "${JsonPrimitive(k)}:${canonical(v)}" }
            is JsonArray -> value.joinToString(",", "[", "]", transform = ::canonical)
            is JsonPrimitive -> if (value.isString || value == JsonNull || value.booleanOrNull != null) value.toString() else BigDecimal(value.content).stripTrailingZeros().toString()
        }
    }
}
