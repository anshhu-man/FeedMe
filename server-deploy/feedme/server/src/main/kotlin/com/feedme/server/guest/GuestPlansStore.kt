package com.feedme.server.guest

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.planning.PlanningStatus
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.kitchen.VerifiedKitchenPrincipal
import com.feedme.server.planning.*
import java.math.BigDecimal
import java.security.SecureRandom
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Actual guest-token first-Plan materialization. Preparation commits its single charged
 * original first; the following actual guest transaction completely rechecks that original
 * before atomically writing V2 lineage, immutable Plan/proof, canonical receipt and outbox.
 * A failure between stages leaves the original preparation recoverable, not uncharged or
 * replaced. No transaction/actor/verified result is accepted from an external caller.
 *
 * Internal implementation only: first-Plan historical GET/explanation use a distinct checked
 * path. V2 alternatives, cooking creation and erasure still require their checked paths.
 * No HTTP capability or mobile
 * activation is installed here. A stored alternative cursor does not activate such a route.
 * In particular the strict original-selection verifier must NOT become historical GET or
 * EXISTING_PIN authorization: those have different lifetime/input/retirement semantics. */
internal class GuestPlansStore(
    private val environment: String,
    transactions: PgTransactions,
    private val preparations: GuestPlanningStore,
    private val explanationCursors: PlanningCursors? = null,
) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(preparations.isBoundTo(environment, transactions)) { "Guest Plans require their actual preparation owner" }
    }
    private val commands = DurableCommands(transactions)
    private val outbox = OutboxStore(transactions)
    private val validator by lazy { ContractBodyValidator.bundled() }

    fun getPlan(token: String, planId: UUID): StoredReply = readFirst(token, "getPlan", planId) { _, _, stored ->
        if (validator.validateResponse("getPlan", 200, checkNotNull(stored.body).toString().toByteArray(Charsets.UTF_8),
                "application/json") != BodyValidationResult.Valid) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        StoredReply(200, stored.body, stored.etag)
    }

    fun getPlanExplanation(token: String, planId: UUID, cursor: String? = null, limit: Int = 20): StoredReply = safe {
        val keys = explanationCursors ?: fail(PlanningFailureCode.NOT_CONFIGURED)
        if (limit !in 1..50) fail(PlanningFailureCode.INPUT_INVALID)
        if (cursor != null && cursor.length !in 1..2048) fail(PlanningFailureCode.CURSOR_INVALID)
        readFirst(token, "getPlanExplanation", planId) { c, actor, stored ->
            GuestPlanExplanation.render(environment, actor.principalId, planId, stored,
                digest(checkNotNull(stored.body).toString().toByteArray(Charsets.UTF_8)), now(c), keys, cursor, limit)
        }
    }

    private fun readFirst(token: String, operation: String, planId: UUID,
        render: (Connection, VerifiedKitchenPrincipal, StoredReply) -> StoredReply): StoredReply = safe {
        preparations.withHistoricalPlan(token, operation, planId, consume = { c, actor, verified ->
            val guard = Guard(c, actor, verified)
            val identity = originalIdentity(actor, verified)
            val original = checkedStoredRoot(c, actor, verified, identity, planId)
            HistoryRead(identity, original, render(c, actor, original)).also { guard.check() }
        }, complete = { c, actor, verified, read ->
            val guard = Guard(c, actor, verified)
            val original = checkedStoredRoot(c, actor, verified, read.identity, planId)
            requireReplyEqual(original, read.original)
            // Explanation's serverTime reflects the final checked transaction, never a
            // new recommendation or refreshed alternative cursor. The envelope stays
            // private until all post-authority rechecks and commit have succeeded.
            read.reply = render(c, actor, original)
            guard.check()
        }).reply
    }

    private fun originalIdentity(actor: VerifiedKitchenPrincipal, verified: GuestVerifiedManifest): CommandIdentity {
        val original = identity(actor, verified.originalCommandKey, json(verified.header.request.encodeUtf8().decodeToString()))
        if (original.requestHash != verified.commandRequestHash) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        return original
    }

    fun createPlan(token: String, key: UUID, body: JsonObject): CommandResult = safe {
        // Do not insert a pending canonical receipt before D4: it correctly refuses an
        // already-executed unrelated command. Exact preparations replay without a new charge.
        preparations.prepareCreate(token, key, body)
        preparations.withOriginalSelection(token, key, body, consume = { c, actor, verified -> safe {
            val guard = Guard(c, actor, verified)
            migration(c); guard.check()
            // D5 has already locked inputs/catalog. The actual guest owner holds its
            // installation/principal/guest exclusively for this entire transaction, so
            // no same-owner operation can hold a receipt/lineage while waiting for these
            // private inputs. Catalog publication never acquires guest/receipt roots, and
            // receipt compaction acquires receipts only. Re-audit this ordering before
            // adding retention/lifecycle writers or another adapter; this is not a general
            // license to invert the principal -> receipt -> inputs/catalog lock order.
            val identity = identity(actor, key, body)
            var replay: StoredReply? = null
            var event: EventDraft? = null
            val result = commands.executeInTransaction(c, identity,
                validatePrincipal = { actual -> guard.requireConnection(actual) },
                authorizeNew = { actual ->
                    guard.requireConnection(actual)
                    if (query(actual, "SELECT 1 FROM planning.plan_requests WHERE environment=? AND actor_kind='guest' AND principal_id=? AND id=?", {
                        owner(actor); setObject(3, verified.header.manifestId)
                    }) { it.getInt(1) } != null) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                },
                authorizeReplay = { actual, cached ->
                    guard.requireConnection(actual)
                    replay = checkedOriginal(actual, actor, verified, identity, cached)
                    guard.check()
                },
                mutate = { actual ->
                    guard.requireConnection(actual)
                    val id = UUID.randomUUID()
                    val cursor = if (verified.engineDecision.status == PlanningStatus.READY && verified.eligibleCount > 1) newCursor() else null
                    val material = GuestPlanMaterializer.first(verified.engineDecision, verified.header, verified.decision,
                        verified.eligibleCount, verified.currentEligibilityCatalogRevision, id, now(actual), cursor)
                    insertLineage(actual, actor, identity, verified)
                    insertPlan(actual, actor, verified, id, material, cursor)
                    execute(actual, "UPDATE planning.plan_requests SET current_plan_id=?,version=version+1 WHERE environment=? " +
                        "AND actor_kind='guest' AND principal_id=? AND id=? AND storage_format=2 AND version=1 AND current_plan_id IS NULL") {
                        setObject(1, id); setString(2, environment); setObject(3, actor.principalId); setObject(4, verified.header.manifestId)
                    }
                    val draft = EventDraft(UUID.randomUUID(), "planning.plan.created.v1", 1, "plan", id, 1,
                        "planning", key.toString(), key, buildJsonObject {
                            put("principalId", actor.principalId.toString()); put("planId", id.toString())
                            material.recipeVersionId?.let { put("recipeVersionId", it.toString()) }
                            put("status", material.status); put("rankingVersion", verified.decision.policyVersion)
                        })
                    outbox.append(actual, draft); event = draft
                    guard.check()
                    material.reply
                })
            guard.check()
            Attempt(if (result is CommandResult.Replayed) CommandResult.Replayed(checkNotNull(replay)) else result, identity, event)
        } }, complete = { c, actor, verified, attempt -> safe {
            val guard = Guard(c, actor, verified)
            // These DB-only rejecting checks run after actual mandatory final authority and
            // idle renewal. The original verifier is checked again after this completion.
            val reply = when (val result = attempt.result) {
                is CommandResult.Applied -> result.reply
                is CommandResult.Replayed -> result.reply
                else -> null
            }
            if (reply != null) {
                val checked = checkedOriginal(c, actor, verified, attempt.identity, reply)
                requireReplyEqual(checked, reply)
                requireReceipt(c, attempt.identity, checked)
                attempt.event?.let { requireEvent(c, it) }
            }
            guard.check()
        } }).result
    }

    private fun insertLineage(c: Connection, actor: VerifiedKitchenPrincipal, identity: CommandIdentity, verified: GuestVerifiedManifest) {
        val h = verified.header
        execute(c, "INSERT INTO planning.plan_requests(environment,actor_kind,principal_id,id,request_text,request_hash," +
            "evidence_text,evidence_hash,ordered_ids,policy_text,current_plan_id,version,created_at,expires_at,cursor_expires_at," +
            "storage_format,manifest_id,create_command_key,command_request_sha256) " +
            "VALUES(?,'guest',?,?,?,?,NULL,NULL,NULL,?,NULL,1,?,?,?,2,?,?,?)") {
            owner(actor); setObject(3, h.manifestId); setString(4, h.request.encodeUtf8().decodeToString())
            setString(5, digest(h.request.encodeUtf8())); setString(6, policyText(h, verified.originalPolicyBindingSha256))
            setObject(7, date(h.createdAt)); setObject(8, date(h.expiresAt)); setObject(9, date(h.cursorExpiresAt))
            setObject(10, h.manifestId); setObject(11, identity.key); setString(12, identity.requestHash)
        }
    }

    private fun insertPlan(c: Connection, actor: VerifiedKitchenPrincipal, verified: GuestVerifiedManifest, id: UUID,
        value: GuestMaterializedPlan, cursor: String?) {
        val created = json(value.snapshotText).text("createdAt")
        execute(c, "INSERT INTO planning.plans(environment,actor_kind,principal_id,id,request_id,parent_plan_id,version,position," +
            "recipe_version_id,status,snapshot_text,snapshot_hash,proof_text,proof_hash,next_cursor_hash,created_at,storage_format) " +
            "VALUES(?,'guest',?,?,?,NULL,1,?,?,?,?,?,?,?,?,?,2)") {
            owner(actor); setObject(3, id); setObject(4, verified.header.manifestId)
            setLong(5, if (value.recipeVersionId == null) -1 else 0)
            setObject(6, value.recipeVersionId); setString(7, value.status); setString(8, value.snapshotText)
            setString(9, value.snapshotHash); setString(10, value.proofText); setString(11, value.proofHash)
            setString(12, cursor?.let(::cursorHash)); setObject(13, date(Instant.parse(created)))
        }
    }

    /** Exact original response only, not arbitrary Plan GET. Fresh D5 selection checks
     * already ran and will run after final authority. Historical proof bytes remain pinned
     * to their recorded eligibility revision; today's current view is independent evidence. */
    private fun checkedOriginal(c: Connection, actor: VerifiedKitchenPrincipal, verified: GuestVerifiedManifest,
        identity: CommandIdentity, cached: StoredReply): StoredReply {
        val id = UUID.fromString(cached.body?.jsonObject?.text("id") ?: fail(PlanningFailureCode.STORAGE_UNAVAILABLE))
        val reply = checkedStoredRoot(c, actor, verified, identity, id)
        requireReplyEqual(reply, cached)
        return reply
    }

    /** Exact immutable root bytes, independent of receipt retention and the current lineage
     * head. Authority/purpose/lifetime belong to the actual one-shot bound verifier. */
    private fun checkedStoredRoot(c: Connection, actor: VerifiedKitchenPrincipal, verified: GuestVerifiedManifest,
        identity: CommandIdentity, id: UUID): StoredReply {
        val h = verified.header
        if (identity.key != verified.originalCommandKey || identity.requestHash != verified.commandRequestHash ||
            (verified.selectedPlanId != null && verified.selectedPlanId != id)) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        query(c, "SELECT * FROM planning.plan_requests WHERE environment=? AND actor_kind='guest' AND principal_id=? AND id=? FOR UPDATE", {
            owner(actor); setObject(3, h.manifestId)
        }) { r ->
            requireOwner(r, actor)
            if (r.getInt("storage_format") != 2 || r.getObject("id", UUID::class.java) != h.manifestId ||
                r.getObject("manifest_id", UUID::class.java) != h.manifestId ||
                r.getObject("create_command_key", UUID::class.java) != identity.key ||
                r.getString("command_request_sha256") != identity.requestHash ||
                r.getString("request_text") != h.request.encodeUtf8().decodeToString() ||
                r.getString("request_hash") != digest(h.request.encodeUtf8()) ||
                r.getString("evidence_text") != null || r.getString("evidence_hash") != null || r.getString("ordered_ids") != null ||
                canonical(json(r.getString("policy_text"))) != canonical(json(policyText(h, verified.originalPolicyBindingSha256))) ||
                instant(r, "created_at") != h.createdAt || instant(r, "expires_at") != h.expiresAt ||
                instant(r, "cursor_expires_at") != h.cursorExpiresAt || r.getLong("version") < 2 ||
                r.getObject("current_plan_id", UUID::class.java) == null) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
            true
        } ?: fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        val reply = query(c, "SELECT * FROM planning.plans WHERE environment=? AND actor_kind='guest' AND principal_id=? AND id=?", {
            owner(actor); setObject(3, id)
        }) { r ->
            requireOwner(r, actor)
            if (r.getInt("storage_format") != 2 || r.getObject("id", UUID::class.java) != id ||
                r.getObject("request_id", UUID::class.java) != h.manifestId || r.getObject("parent_plan_id") != null ||
                r.getLong("version") != 1L || r.getLong("position") != (if (verified.decision.recipe == null) -1L else 0L))
                fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
            val snapshotText = r.getString("snapshot_text"); val proofText = r.getString("proof_text")
            val snapshot = json(snapshotText); val proof = json(proofText, 32768)
            val eligibility = proof.text("eligibilityCatalogRevision")
            if (!eligibility.matches(Regex("[1-9][0-9]{0,18}")) || eligibility.toLong() > verified.currentEligibilityCatalogRevision.toLong())
                fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
            val cursor = snapshot["nextAlternativeCursor"].takeUnless { it == JsonNull }?.jsonPrimitive?.content
            val expected = GuestPlanMaterializer.first(verified.engineDecision, h, verified.decision, verified.eligibleCount,
                eligibility, id, instant(r, "created_at"), cursor)
            if (expected.snapshotText != snapshotText || expected.snapshotHash != r.getString("snapshot_hash") ||
                expected.proofText != proofText || expected.proofHash != r.getString("proof_hash") ||
                expected.recipeVersionId != r.getObject("recipe_version_id", UUID::class.java) || expected.status != r.getString("status") ||
                cursor?.let(::cursorHash) != r.getString("next_cursor_hash")) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
            expected.reply
        } ?: fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        return reply
    }

    private fun requireReceipt(c: Connection, identity: CommandIdentity, reply: StoredReply) {
        query(c, "SELECT request_hash,state,response_code,response_json,response_etag,expires_at>clock_timestamp() AS live " +
            "FROM platform.idempotency WHERE principal_scope=? AND operation_id='createPlan' AND key=? FOR UPDATE", {
            setString(1, identity.scope.storageKey); setObject(2, identity.key)
        }) { r ->
            if (r.getString(1) != identity.requestHash || r.getString(2) != "completed" || !r.getBoolean(6))
                fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
            requireReplyEqual(reply, StoredReply(r.getInt(3), Json.parseToJsonElement(r.getString(4)), r.getString(5)))
            true
        } ?: fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
    }

    private fun requireEvent(c: Connection, event: EventDraft) {
        query(c, "SELECT event_type,schema_version,aggregate_type,aggregate_id,aggregate_version,producer,correlation_id,causation_id,payload " +
            "FROM platform.outbox WHERE event_id=?", { setObject(1, event.eventId) }) { r ->
            if (r.getString(1) != event.eventType || r.getInt(2) != event.schemaVersion || r.getString(3) != event.aggregateType ||
                r.getObject(4, UUID::class.java) != event.aggregateId || r.getLong(5) != event.aggregateVersion ||
                r.getString(6) != event.producer || r.getString(7) != event.correlationId ||
                r.getObject(8, UUID::class.java) != event.causationId || json(r.getString(9)) != event.data)
                fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
            true
        } ?: fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
    }

    private fun migration(c: Connection) {
        for ((version, name) in listOf(1 to "durable_platform", 3 to "private_planning", 20 to "manifest_plan_lineage")) {
            val resource = "/db/migration/V${version.toString().padStart(3, '0')}__$name.sql"
            val bytes = javaClass.getResourceAsStream(resource)?.use { it.readBytes() }
                ?: fail(PlanningFailureCode.NOT_CONFIGURED)
            if (query(c, "SELECT checksum FROM platform.schema_migrations WHERE version=?", { setInt(1, version) }) { it.getString(1) } != digest(bytes))
                fail(PlanningFailureCode.NOT_CONFIGURED)
        }
    }
    private fun policyText(h: PlanningManifestHeaderV2, originalPolicyBindingSha256: String) = buildJsonObject {
        put("version", 2); put("guestPolicySha256", originalPolicyBindingSha256)
        put("ranking", json(h.copyForStorage().encodeUtf8().decodeToString(), PlanningManifestHeaderV2.MAX_BYTES).getValue("policy"))
    }.toString()
    private fun identity(actor: VerifiedKitchenPrincipal, key: UUID, body: JsonObject) =
        CommandIdentity(PrincipalScope(environment, CommandActor.GUEST, actor.principalId), "createPlan", key, body = body)
    private fun requireReplyEqual(a: StoredReply, b: StoredReply) {
        if (a.status != 201 || a.status != b.status || a.etag != "\"1\"" || a.etag != b.etag ||
            a.body == null || b.body == null || canonical(a.body) != canonical(b.body)) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
    }
    private fun requireOwner(r: ResultSet, actor: VerifiedKitchenPrincipal) {
        if (r.getString("environment") != environment || r.getString("actor_kind") != "guest" ||
            r.getObject("principal_id", UUID::class.java) != actor.principalId) fail(PlanningFailureCode.UNAUTHENTICATED)
    }
    private inner class Guard(private val c: Connection, private val actor: VerifiedKitchenPrincipal, private val verified: GuestVerifiedManifest) {
        private val thread = Thread.currentThread()
        private val transaction = transactionId()
        init { check() }
        fun requireConnection(actual: Connection) { if (actual !== c) fail(PlanningFailureCode.STORAGE_UNAVAILABLE); check() }
        fun check() {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Guest Plan interrupted")
            if (Thread.currentThread() !== thread || actor.environment != environment || actor.kind != CommandActor.GUEST ||
                actor.deviceSessionId != null || verified.header.environment != environment || verified.header.actorKind != "guest" ||
                verified.header.principalId != actor.principalId || c.isClosed || c.autoCommit || transactionId() != transaction)
                fail(PlanningFailureCode.UNAUTHENTICATED)
        }
        private fun transactionId(): Long {
            if (c.isClosed || c.autoCommit) fail(PlanningFailureCode.UNAUTHENTICATED)
            return query(c, "SELECT txid_current()", {}) { it.getLong(1) } ?: fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        }
    }
    private class Attempt(val result: CommandResult, val identity: CommandIdentity, val event: EventDraft?)
    private class HistoryRead(val identity: CommandIdentity, val original: StoredReply, var reply: StoredReply)
    private fun PreparedStatement.owner(actor: VerifiedKitchenPrincipal) { setString(1, environment); setObject(2, actor.principalId) }
    private fun now(c: Connection) = query(c, "SELECT date_trunc('milliseconds',clock_timestamp())", {}) {
        it.getObject(1, OffsetDateTime::class.java).toInstant()
    } ?: fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
    private fun instant(r: ResultSet, column: String) = r.getObject(column, OffsetDateTime::class.java).toInstant()
    private fun date(at: Instant) = OffsetDateTime.ofInstant(at, ZoneOffset.UTC)
    private fun execute(c: Connection, sql: String, bind: PreparedStatement.() -> Unit) {
        c.prepareStatement(sql).use { it.bind(); if (it.executeUpdate() != 1) fail(PlanningFailureCode.STORAGE_UNAVAILABLE) }
    }
    private fun <T> query(c: Connection, sql: String, bind: PreparedStatement.() -> Unit, read: (ResultSet) -> T): T? =
        c.prepareStatement(sql).use { s -> s.bind(); s.executeQuery().use { r ->
            if (!r.next()) null else read(r).also { if (r.next()) fail(PlanningFailureCode.STORAGE_UNAVAILABLE) }
        } }
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: GuestSessionFailure) { throw failure }
        catch (failure: PlanningServiceFailure) { throw failure }
        catch (failure: CommitOutcomeUnknown) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (_: Exception) { fail(PlanningFailureCode.STORAGE_UNAVAILABLE) }
    override fun toString() = "GuestPlansStore(<redacted>)"
    private companion object {
        val random = SecureRandom()
        fun newCursor() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        fun cursorHash(value: String) = digest(value.toByteArray(Charsets.US_ASCII))
        fun json(text: String, maximum: Int = 262144): JsonObject = Json.parseToJsonElement(
            WireDocument.parse(text, WireLimits(maximum, 32)).encodeUtf8().decodeToString()).jsonObject
        fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
        fun fail(code: PlanningFailureCode): Nothing = throw PlanningServiceFailure(code)
        fun canonical(value: JsonElement): String = when (value) {
            is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { (k, v) -> "${JsonPrimitive(k)}:${canonical(v)}" }
            is JsonArray -> value.joinToString(",", "[", "]", transform = ::canonical)
            is JsonPrimitive -> if (value.isString || value == JsonNull || value.booleanOrNull != null) value.toString()
                else BigDecimal(value.content).stripTrailingZeros().toString()
        }
    }
}
