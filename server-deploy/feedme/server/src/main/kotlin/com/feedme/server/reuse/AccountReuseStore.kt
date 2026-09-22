package com.feedme.server.reuse

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.catalog.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.cooking.AccountMealAccess
import com.feedme.server.db.*
import com.feedme.server.identity.AccountProfileStore
import com.feedme.server.planning.*
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.serialization.json.*

/** Account-only reviewed reuse proposals. No Plan, pantry, recipe copy or portion mutation.
 * Immutable private proposal/page evidence and the exact durable command commit together.
 * A new page never reranks/replaces the original; current authorization may only refuse it. */
internal class AccountReuseStore(private val environment: String, private val transactions: PgTransactions,
    private val accounts: AccountProfileStore, private val catalog: RecipeCatalogJournal,
    private val ingredients: IngredientCatalogStore, private val planning: AccountPlanningStore,
    private val planningPolicy: PlanningServicePolicy, val policy: AccountReusePolicy, private val cursors: ReuseCursors) {
    init { require(catalog.environment == environment && ingredients.environment == environment && planning.isBoundTo(environment, transactions, accounts)) }
    fun createReuseOptions(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, body: JsonObject,
        query: Map<String, List<String>> = emptyMap()): CommandResult = reuseSafe {
        val input = request(body)
        val requestText = reuseCanonical(input); val requestHash = reuseSha(requestText)
        val paging = paging(query)
        transactions.run { c ->
            val account = AccountMealAccess(environment, c, accounts, subject, device)
            planning.withOwnedTransaction(c, subject, device) { actor, plans, checkSourceAt ->
                val identity = CommandIdentity(PrincipalScope(environment, CommandActor.ACCOUNT, actor.principalId), "createReuseOptions", key,
                    queryParameters = query, body = input)
                var source: AccountReuseEvidence? = null
                var expected: Proposal? = null
                var expectedPage: JsonObject? = null
                var cursorExpiry: Instant? = null
                fun source(): AccountReuseEvidence = source ?: AccountReuseEvidence(c, environment, account, actor, plans,
                    catalog, ingredients, input, policy, planningPolicy).also { source = it }
                fun position(): ReuseCursors.Position? = paging.cursor?.let {
                    cursors.decode(environment, actor.principalId, requestHash, it, paging.limit, now(c)).also { p -> cursorExpiry = p.expiry }
                }
                fun authorize(proposal: Proposal) {
                    if (proposal.requestText != requestText || proposal.requestHash != requestHash) reuseFail(ReuseFailureCode.CURSOR_INVALID)
                    source().current(proposal.evidence)
                    if (!now(c).isBefore(proposal.expires)) reuseFail(ReuseFailureCode.PROPOSAL_EXPIRED)
                }
                val result = DurableCommands(transactions).executeInTransaction(c, identity,
                    validatePrincipal = { account.current(it) },
                    authorizeNew = { account.current(it) },
                    authorizeReplay = { _, reply ->
                        val page = pageRow(c, actor, key)
                        val proposal = load(c, actor, page.reuseId("proposal_id"))
                        val offset = position()?.also { if (it.proposal != proposal.id) reuseFail(ReuseFailureCode.CURSOR_INVALID) }?.offset ?: 0
                        requirePage(page, identity, paging.limit, offset, reply)
                        reply.body!!.jsonObject["nextCursor"]?.takeUnless { it == JsonNull }?.let { next ->
                            val retained = cursors.decode(environment, actor.principalId, requestHash, next.jsonPrimitive.content, paging.limit, now(c))
                            if (retained.proposal != proposal.id) reuseFail()
                            cursorExpiry = cursorExpiry?.let { minOf(it, retained.expiry) } ?: retained.expiry
                        }
                        authorize(proposal); expected = proposal; expectedPage = page
                    },
                    mutate = { _ ->
                        val at = now(c)
                        val pos = position()
                        val proposal = if (pos == null) {
                            val evidence = source().create()
                            consume(c, actor, source().policyHash)
                            val created = now(c)
                            Proposal(UUID.randomUUID(), requestText, requestHash, reuseCanonical(evidence), created,
                                created.plusSeconds(policy.proposalLifetimeSeconds.toLong())).also { insert(c, actor, it) }
                        } else {
                            load(c, actor, pos.proposal).also { authorize(it); consume(c, actor, source().policyHash) }
                        }
                        val offset = pos?.offset ?: 0
                        if (offset > proposal.options.size || (pos != null && offset == proposal.options.size)) reuseFail(ReuseFailureCode.CURSOR_INVALID)
                        val end = minOf(proposal.options.size, offset + paging.limit)
                        val pageTime = now(c)
                        if (pageTime < at || !pageTime.isBefore(proposal.expires)) reuseFail(ReuseFailureCode.PROPOSAL_EXPIRED)
                        val expiry = minOf(proposal.expires, pageTime.plusSeconds(policy.cursorLifetimeSeconds.toLong()))
                        val next = if (end < proposal.options.size) cursors.encode(environment, actor.principalId, requestHash,
                            proposal.id, end, paging.limit, expiry).also { cursorExpiry = expiry } else null
                        val response = buildJsonObject { put("items", JsonArray(proposal.options.subList(offset, end).map { it.getValue("option") }))
                            put("nextCursor", next?.let(::JsonPrimitive) ?: JsonNull); put("serverTime", pageTime.toString()) }
                        val reply = checkedReply(response)
                        val text = reuseCanonical(response)
                        c.prepareStatement("INSERT INTO planning.reuse_pages(environment,actor_kind,principal_id,command_key,principal_scope,operation_id,request_sha256,proposal_id,page_offset,page_limit,response_text,response_sha256) " +
                            "VALUES(?,'account',?,?,?,'createReuseOptions',?,?,?,?,?,?)").use { s ->
                            s.setString(1, environment); s.setObject(2, actor.principalId); s.setObject(3, key); s.setString(4, identity.scope.storageKey)
                            s.setString(5, identity.requestHash); s.setObject(6, proposal.id); s.setInt(7, offset); s.setInt(8, paging.limit)
                            s.setString(9, text); s.setString(10, reuseSha(text)); check(s.executeUpdate() == 1)
                        }
                        expected = load(c, actor, proposal.id); expectedPage = pageRow(c, actor, key)
                        requirePage(expectedPage!!, identity, paging.limit, offset, reply); authorize(proposal)
                        reply
                    })
                expected?.let { before ->
                    authorize(before)
                    if (!same(before, load(c, actor, before.id)) || expectedPage != pageRow(c, actor, key)) reuseFail()
                    // Provider checks can wait; compare all persisted effects once more after.
                    account.current(c)
                    if (!same(before, load(c, actor, before.id)) || expectedPage != pageRow(c, actor, key)) reuseFail()
                }
                account.current(c)
                val receiptExpires = when (result) {
                    is CommandResult.Applied -> receipt(c, identity, result.reply)
                    is CommandResult.Replayed -> receipt(c, identity, result.reply)
                    else -> null
                }
                val final = now(c)
                expected?.let { if (final < it.created || !final.isBefore(it.expires)) reuseFail(ReuseFailureCode.PROPOSAL_EXPIRED) }
                cursorExpiry?.let { if (!final.isBefore(it)) reuseFail(ReuseFailureCode.CURSOR_EXPIRED) }
                receiptExpires?.let { if (!final.isBefore(it)) reuseFail() }
                checkSourceAt(final); account.checkAt(c, final)
                result
            }
        }
    }
    private fun receipt(c: Connection, command: CommandIdentity, reply: StoredReply): Instant = c.prepareStatement(
        "SELECT request_hash,state,response_code,response_json::text,response_etag,expires_at FROM platform.idempotency WHERE principal_scope=? AND operation_id=? AND key=? FOR UPDATE").use {
        it.setString(1, command.scope.storageKey); it.setString(2, command.operationId); it.setObject(3, command.key)
        it.executeQuery().use { r ->
            if (!r.next() || r.getString(1) != command.requestHash || r.getString(2) != "completed" || r.getInt(3) != reply.status ||
                r.getString(4)?.let(Json::parseToJsonElement) != reply.body || r.getString(5) != reply.etag) reuseFail()
            r.getObject(6, OffsetDateTime::class.java).toInstant().also { if (r.next()) reuseFail() }
        }
    }
    private fun consume(c: Connection, actor: VerifiedPlanningPrincipal, policyHash: String) {
        val day = now(c).atOffset(ZoneOffset.UTC).toLocalDate()
        val used = c.prepareStatement("SELECT policy_sha256,maximum,used FROM planning.reuse_windows WHERE environment=? AND actor_kind='account' AND principal_id=? AND window_date=? FOR UPDATE").use {
            it.setString(1, environment); it.setObject(2, actor.principalId); it.setObject(3, day)
            it.executeQuery().use { r -> if (!r.next()) null else {
                if (r.getString(1) != policyHash || r.getInt(2) != policy.maxRequestsPerUtcDay) reuseFail(ReuseFailureCode.NOT_CONFIGURED)
                r.getInt(3).also { if (r.next()) reuseFail() }
            } }
        }
        if (used != null && used >= policy.maxRequestsPerUtcDay) reuseFail(ReuseFailureCode.RATE_LIMITED)
        if (used == null) c.prepareStatement("INSERT INTO planning.reuse_windows(environment,actor_kind,principal_id,window_date,policy_sha256,maximum,used) VALUES(?,'account',?,?,?,?,1)").use {
            it.setString(1, environment); it.setObject(2, actor.principalId); it.setObject(3, day); it.setString(4, policyHash); it.setInt(5, policy.maxRequestsPerUtcDay); check(it.executeUpdate() == 1)
        } else c.prepareStatement("UPDATE planning.reuse_windows SET used=used+1 WHERE environment=? AND actor_kind='account' AND principal_id=? AND window_date=? AND used=?").use {
            it.setString(1, environment); it.setObject(2, actor.principalId); it.setObject(3, day); it.setInt(4, used); check(it.executeUpdate() == 1)
        }
    }
    private fun insert(c: Connection, actor: VerifiedPlanningPrincipal, p: Proposal) {
        c.prepareStatement("INSERT INTO planning.reuse_requests(environment,actor_kind,principal_id,id,request_text,request_sha256,evidence_text,evidence_sha256,created_at,expires_at) VALUES(?,'account',?,?,?,?,?,?,?,?)").use {
            it.setString(1, environment); it.setObject(2, actor.principalId); it.setObject(3, p.id); it.setString(4, p.requestText); it.setString(5, p.requestHash)
            it.setString(6, p.evidenceText); it.setString(7, reuseSha(p.evidenceText)); it.setObject(8, p.created.atOffset(ZoneOffset.UTC)); it.setObject(9, p.expires.atOffset(ZoneOffset.UTC))
            check(it.executeUpdate() == 1)
        }
    }
    private fun load(c: Connection, actor: VerifiedPlanningPrincipal, id: UUID): Proposal = c.prepareStatement(
        "SELECT request_text,request_sha256,evidence_text,evidence_sha256,created_at,expires_at FROM planning.reuse_requests WHERE environment=? AND actor_kind='account' AND principal_id=? AND id=? FOR SHARE").use {
        it.setString(1, environment); it.setObject(2, actor.principalId); it.setObject(3, id)
        it.executeQuery().use { r -> if (!r.next()) reuseFail(ReuseFailureCode.CURSOR_INVALID)
            val p = Proposal(id, r.getString(1), r.getString(2), r.getString(3), r.getObject(5, OffsetDateTime::class.java).toInstant(), r.getObject(6, OffsetDateTime::class.java).toInstant())
            if (reuseSha(p.requestText) != p.requestHash || reuseSha(p.evidenceText) != r.getString(4) || r.next()) reuseFail()
            p
        }
    }
    private fun pageRow(c: Connection, actor: VerifiedPlanningPrincipal, key: UUID): JsonObject = c.prepareStatement(
        "SELECT to_jsonb(p)::text FROM planning.reuse_pages p WHERE environment=? AND actor_kind='account' AND principal_id=? AND command_key=? FOR SHARE").use {
        it.setString(1, environment); it.setObject(2, actor.principalId); it.setObject(3, key)
        it.executeQuery().use { r -> if (!r.next()) reuseFail(); reuseJson(r.getString(1)).also { if (r.next()) reuseFail() } }
    }
    private fun requirePage(row: JsonObject, command: CommandIdentity, limit: Int, offset: Int, reply: StoredReply) {
        val text = row.reuseText("response_text")
        if (row["principal_scope"] != JsonPrimitive(command.scope.storageKey) || row["operation_id"] != JsonPrimitive("createReuseOptions") ||
            row["command_key"] != JsonPrimitive(command.key.toString()) || row["request_sha256"] != JsonPrimitive(command.requestHash) ||
            row["page_offset"] != JsonPrimitive(offset) || row["page_limit"] != JsonPrimitive(limit) || row["response_sha256"] != JsonPrimitive(reuseSha(text)) ||
            reply.status != 201 || reply.etag != null || reuseCanonical(reply.body ?: JsonNull) != text) reuseFail()
        checkedReply(reuseJson(text, policy.maxResponseBytes))
    }
    private fun request(body: JsonObject): JsonObject {
        val text = body.toString()
        if (text.encodeToByteArray().size > 16384 || validator.validateRequest("createReuseOptions", text.encodeToByteArray(), "application/json") != BodyValidationResult.Valid)
            reuseFail(ReuseFailureCode.INPUT_INVALID)
        val input = reuseJson(text, 16384)
        val ids = input["ingredientIds"]?.jsonArray?.map { reuseUuid(it.jsonPrimitive.content) }.orEmpty()
        if (ids.size > 128 || ids.distinct().size != ids.size) reuseFail(ReuseFailureCode.INPUT_INVALID)
        return input
    }
    private fun paging(query: Map<String, List<String>>): Paging {
        if (!setOf("cursor","limit").containsAll(query.keys) || query.values.any { it.size != 1 }) reuseFail(ReuseFailureCode.INPUT_INVALID)
        val cursor = query["cursor"]?.single()?.also { if (it.isEmpty() || it.length > 2048 || it.any(Char::isISOControl)) reuseFail(ReuseFailureCode.CURSOR_INVALID) }
        val limit = query["limit"]?.single()?.let { if (!it.matches(Regex("[1-9][0-9]?"))) reuseFail(ReuseFailureCode.INPUT_INVALID)
            it.toIntOrNull()?.takeIf { n -> n in 1..50 } ?: reuseFail(ReuseFailureCode.INPUT_INVALID) } ?: 20
        return Paging(cursor, limit)
    }
    private fun checkedReply(body: JsonObject): StoredReply {
        val bytes = body.toString().encodeToByteArray()
        if (bytes.size > policy.maxResponseBytes) reuseFail(ReuseFailureCode.RESPONSE_TOO_LARGE)
        if (validator.validateResponse("createReuseOptions", 201, bytes, "application/json") != BodyValidationResult.Valid) reuseFail()
        return StoredReply(201, body)
    }
    private class Paging(val cursor: String?, val limit: Int)
    private class Proposal(val id: UUID, val requestText: String, val requestHash: String, val evidenceText: String,
        val created: Instant, val expires: Instant) {
        val evidence = reuseJson(evidenceText)
        val options = evidence.getValue("options").jsonArray.map { it.jsonObject }
        init { if (created >= expires || reuseCanonical(evidence) != evidenceText || reuseCanonical(reuseJson(requestText,16384)) != requestText) reuseFail() }
    }
    private fun same(a: Proposal, b: Proposal) = a.id == b.id && a.requestText == b.requestText && a.requestHash == b.requestHash &&
        a.evidenceText == b.evidenceText && a.created == b.created && a.expires == b.expires
    override fun toString() = "AccountReuseStore(<redacted>)"
    private companion object { val validator by lazy { ContractBodyValidator.bundled() }
        fun now(c: Connection): Instant = AccountMealAccess.now(c).truncatedTo(ChronoUnit.MILLIS) }
}
