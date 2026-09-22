package com.feedme.server.social.reciperequests

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.catalog.RecipeCatalogJournal
import com.feedme.server.catalog.RecipeCatalogFailure
import com.feedme.server.contract.*
import com.feedme.server.db.*
import com.feedme.server.identity.*
import com.feedme.server.social.*
import com.feedme.server.social.conversations.*
import com.feedme.server.social.posts.*
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Same-transaction source access, request consent, exact retained command and linked
 * thread message. Fulfillment references current catalog material only; neither this
 * record nor its card creates a save grant or changes the source post/audience. */
internal class AccountRecipeRequestStore(private val environment: String, private val transactions: PgTransactions,
    private val accounts: AccountProfileStore, private val identities: AccountSocialIdentityPolicy,
    private val conversations: AccountConversationStore, private val posts: AccountPostReadStore,
    private val catalog: RecipeCatalogJournal, val policy: AccountRecipeRequestPolicy,
    private val eligibility: RecipeRequestEligibility) {
    private val commands = DurableCommands(transactions)
    private val outbox = OutboxStore(transactions)
    init { require(environment == accounts.environment && environment == posts.environment && environment == catalog.environment) }

    fun canRequest(c: Connection, actor: VerifiedSocialAccount, material: PostReadMaterial) = eligibility.canRequest(c, actor, material)

    fun requestRecipe(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, body: JsonObject): CommandResult = safe {
        val input = request("requestRecipe", body); val post = input.uuid("postId"); val note = note(input)
        transactions.run { c ->
            val actor = identities.resolvePrincipal(c, subject, device)
            val command = CommandIdentity(scope(actor), "requestRecipe", key, body = input)
            var deliveryDeadline: Instant? = null
            val result = commands.executeInTransaction(c, command, { identities.lockPrincipal(it, actor) }, {},
                { db, cached ->
                    val old = receiptRow(db, actor, cached)
                    if (old.requester != actor.accountId || old.post != post) fail(RecipeRequestFailureCode.REQUEST_UNAVAILABLE)
                    requireOriginalNote(db, old, note)
                    if (cached.status != 201 || cached.etag != "\"1\"" || cached.body != pendingJson(old)) fail(RecipeRequestFailureCode.STORAGE_UNAVAILABLE)
                    participants(db, actor, old, false); validateReply("requestRecipe", cached)
                }) { db ->
                if (!policy.enabled) fail(RecipeRequestFailureCode.NOT_CONFIGURED)
                val source = posts.requireRecipeRequestSource(db, actor, post)
                deliveryDeadline = source.validUntil
                if (source.ownerId == actor.accountId || source.body["attachment"] != null) fail(RecipeRequestFailureCode.SOURCE_UNAVAILABLE)
                val thread = conversations.resolveRecipeThread(db, actor, source.ownerId)
                val previous = query(db, "SELECT * FROM social.recipe_requests WHERE environment=? AND requester_user_id=? AND post_id=? AND status='pending' FOR UPDATE NOWAIT", {
                    setString(1, environment); setObject(2, actor.accountId); setObject(3, post)
                }) { if (it.next()) row(it) else null }?.let { expire(db, it) }
                if (previous?.status == "pending") {
                    if (previous.author != source.ownerId || previous.thread != thread) fail(RecipeRequestFailureCode.STORAGE_UNAVAILABLE)
                    requireOriginalNote(db, previous, note)
                    deliveryDeadline = minOf(source.validUntil, previous.expires)
                    if (!now(db).isBefore(source.validUntil)) fail(RecipeRequestFailureCode.SOURCE_UNAVAILABLE)
                    return@executeInTransaction reply("requestRecipe", previous, 201)
                }
                val count = query(db, "SELECT count(*) FROM social.recipe_requests WHERE environment=? AND requester_user_id=? AND created_at>=clock_timestamp()-interval '24 hours'", {
                    setString(1, environment); setObject(2, actor.accountId)
                }) { it.next(); it.getLong(1) }
                if (count >= policy.maxRequestsPer24Hours) fail(RecipeRequestFailureCode.RATE_LIMITED)
                val at = now(db); val id = UUID.randomUUID()
                deliveryDeadline = minOf(source.validUntil, at.plusSeconds(policy.lifetimeSeconds.toLong()))
                update(db, "INSERT INTO social.recipe_requests(environment,id,requester_user_id,author_user_id,post_id,thread_id,version,status,expires_at,created_at,updated_at) VALUES(?,?,?,?,?,?,1,'pending',?,?,?)", {
                    setString(1, environment); setObject(2, id); setObject(3, actor.accountId); setObject(4, source.ownerId); setObject(5, post); setObject(6, thread)
                    instant(7, at.plusSeconds(policy.lifetimeSeconds.toLong())); instant(8, at); instant(9, at)
                })
                conversations.appendRecipeMessage(db, actor, thread, id, "recipeRequest", note, null, key)
                val saved = owned(db, actor.accountId, id)
                event(db, saved, actor.accountId, key, created = true)
                if (!now(db).isBefore(source.validUntil)) fail(RecipeRequestFailureCode.SOURCE_UNAVAILABLE)
                reply("requestRecipe", saved, 201)
            }
            identities.lockPrincipal(c, actor)
            deliveryDeadline?.let { if (!now(c).isBefore(it)) fail(RecipeRequestFailureCode.SOURCE_UNAVAILABLE) }
            result
        }
    }

    fun getRecipeRequest(subject: VerifiedSupabaseSubject, device: UUID, id: UUID): StoredReply = safe {
        transactions.run { c ->
            val actor = identities.resolvePrincipal(c, subject, device)
            val initial = owned(c, actor.accountId, id, false); participants(c, actor, initial, false)
            val current = expire(c, owned(c, actor.accountId, id))
            identities.lockPrincipal(c, actor); reply("getRecipeRequest", current)
        }
    }

    fun respondToRecipeRequest(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, id: UUID,
        ifMatch: String, body: JsonObject): CommandResult = safe {
        val input = request("respondToRecipeRequest", body); val expected = version(ifMatch)
        val decision = input.getValue("decision").jsonPrimitive.content; val recipe = input["recipeVersionId"]?.jsonPrimitive?.content?.let(UUID::fromString)
        if ((decision == "fulfill") != (recipe != null)) fail(RecipeRequestFailureCode.INPUT_INVALID)
        val note = note(input)
        transactions.run { c ->
            val actor = identities.resolvePrincipal(c, subject, device)
            val initial = owned(c, actor.accountId, id, false)
            if (initial.author != actor.accountId) fail(RecipeRequestFailureCode.REQUEST_UNAVAILABLE)
            participants(c, actor, initial, false)
            val command = CommandIdentity(scope(actor), "respondToRecipeRequest", key, mapOf("recipeRequestId" to id.toString()), body = input, ifMatch = ifMatch)
            var deliveryDeadline: Instant? = null
            val result = commands.executeInTransaction(c, command, { identities.lockPrincipal(it, actor) }, {},
                { db, cached ->
                    val row = owned(db, actor.accountId, id)
                    if (expected == Long.MAX_VALUE || row.version != expected + 1 || row.status != (if (decision == "fulfill") "fulfilled" else "declined") || row.recipe != recipe)
                        fail(RecipeRequestFailureCode.REQUEST_CLOSED)
                    if (recipe != null) requireRecipe(db, recipe)
                    if (cached.body != json(row)) fail(RecipeRequestFailureCode.STORAGE_UNAVAILABLE)
                    validateReply("respondToRecipeRequest", cached)
                }) { db ->
                val current = owned(db, actor.accountId, id)
                participants(db, actor, current, true)
                pending(current, expected, now(db))
                deliveryDeadline = current.expires
                if (recipe != null) requireRecipe(db, recipe)
                val status = if (decision == "fulfill") "fulfilled" else "declined"
                update(db, "UPDATE social.recipe_requests SET status=?,close_reason=?,recipe_version_id=?,version=version+1,updated_at=clock_timestamp() WHERE environment=? AND id=? AND version=?", {
                    setString(1, status); setString(2, status); setObject(3, recipe); setString(4, environment); setObject(5, id); setLong(6, expected)
                })
                conversations.appendRecipeMessage(db, actor, current.thread, id, if (recipe == null) "system" else "recipeCard", note, recipe, key)
                if (!now(db).isBefore(current.expires)) fail(RecipeRequestFailureCode.REQUEST_CLOSED)
                val saved = owned(db, actor.accountId, id); event(db, saved, actor.accountId, key)
                reply("respondToRecipeRequest", saved)
            }
            identities.lockPrincipal(c, actor)
            deliveryDeadline?.let { if (!now(c).isBefore(it)) fail(RecipeRequestFailureCode.REQUEST_CLOSED) }
            result
        }
    }

    fun cancelRecipeRequest(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, id: UUID, ifMatch: String): CommandResult = safe {
        val expected = version(ifMatch)
        transactions.run { c ->
            val owner = accounts.lockAccountSafety(c, subject, device)
            val command = CommandIdentity(PrincipalScope(environment, CommandActor.ACCOUNT, owner), "cancelRecipeRequest", key,
                mapOf("recipeRequestId" to id.toString()), ifMatch = ifMatch)
            val result = commands.executeInTransaction(c, command, { safetyCurrent(it, subject, device, owner) }, {},
                { db, cached ->
                    val row = owned(db, owner, id)
                    if (row.requester != owner || row.closeReason != "cancelled" || expected == Long.MAX_VALUE || row.version != expected + 1 || cached.status != 204 || cached.body != null || cached.etag != null)
                        fail(RecipeRequestFailureCode.REQUEST_CLOSED)
                }) { db ->
                val row = owned(db, owner, id)
                if (row.requester != owner) fail(RecipeRequestFailureCode.REQUEST_UNAVAILABLE)
                // Cancellation is not a new delivery or unsolicited response. It requires
                // no current contact opt-in/new Terms, but exact retained requester ownership.
                pending(row, expected, now(db))
                update(db, "UPDATE social.recipe_requests SET status='unavailable',close_reason='cancelled',version=version+1,updated_at=clock_timestamp() WHERE environment=? AND id=? AND version=?", {
                    setString(1, environment); setObject(2, id); setLong(3, expected)
                })
                event(db, owned(db, owner, id), owner, key)
                StoredReply(204)
            }
            safetyCurrent(c, subject, device, owner); result
        }
    }

    private fun participants(c: Connection, actor: VerifiedSocialAccount, row: Row, delivery: Boolean) {
        conversations.requireRecipeParticipants(c, actor, row.requester, row.author, delivery)
        requireVisible(c, row)
    }
    private fun requireVisible(c: Connection, row: Row) {
        if (!conversations.recipeRequestVisible(c, row.id)) fail(RecipeRequestFailureCode.REQUEST_UNAVAILABLE)
    }
    private fun pending(row: Row, expected: Long, at: Instant) {
        if (row.status != "pending" || !at.isBefore(row.expires)) fail(RecipeRequestFailureCode.REQUEST_CLOSED)
        if (row.version != expected || expected == Long.MAX_VALUE) fail(RecipeRequestFailureCode.VERSION_CONFLICT)
    }
    private fun expire(c: Connection, original: Row): Row {
        if (original.status != "pending" || now(c) < original.expires) return original
        if (original.version == Long.MAX_VALUE) fail(RecipeRequestFailureCode.STORAGE_UNAVAILABLE)
        update(c, "UPDATE social.recipe_requests SET status='unavailable',close_reason='expired',version=version+1,updated_at=clock_timestamp() WHERE environment=? AND id=? AND version=?", {
            setString(1, environment); setObject(2, original.id); setLong(3, original.version)
        })
        // Read-triggered expiry is an authoritative lifecycle transition, not an authored
        // reply; no message, reminder or fake author response is emitted.
        return owned(c, original.requester, original.id)
    }
    private fun requireRecipe(c: Connection, id: UUID) {
        val view = catalog.openView(c); val actual = view.lookupCurrent(id)?.entry ?: fail(RecipeRequestFailureCode.SOURCE_UNAVAILABLE)
        if (actual.recipe["reviewStatus"] != JsonPrimitive("published") || actual.recipe["contentLicense"] != JsonPrimitive("catalogRedistributable") ||
            actual.review["freeCatalogEligible"] != JsonPrimitive(true) || actual.recall != null || actual.rightsReference.isBlank()) fail(RecipeRequestFailureCode.SOURCE_UNAVAILABLE)
        view.checkCurrent()
    }
    private fun receiptRow(c: Connection, actor: VerifiedSocialAccount, cached: StoredReply): Row =
        owned(c, actor.accountId, cached.body?.jsonObject?.uuid("id") ?: fail(RecipeRequestFailureCode.STORAGE_UNAVAILABLE))
    private fun requireOriginalNote(c: Connection, row: Row, expected: String) {
        requireVisible(c, row)
        val actual = query(c, "SELECT text FROM social.thread_messages WHERE environment=? AND thread_id=? AND recipe_request_id=? AND sender_user_id=? AND kind='recipeRequest'", {
            setString(1, environment); setObject(2, row.thread); setObject(3, row.id); setObject(4, row.requester)
        }) { if (!it.next()) fail(RecipeRequestFailureCode.STORAGE_UNAVAILABLE); it.getString(1).also { _ -> if (it.next()) fail(RecipeRequestFailureCode.STORAGE_UNAVAILABLE) } }
        if (actual != expected) fail(RecipeRequestFailureCode.REQUEST_ALREADY_PENDING)
    }
    private class Row(val id: UUID, val requester: UUID, val author: UUID, val post: UUID, val thread: UUID,
        val version: Long, val status: String, val expires: Instant, val recipe: UUID?, val closeReason: String?, val created: Instant, val updated: Instant)
    private fun row(r: ResultSet) = Row(r.getObject("id", UUID::class.java), r.getObject("requester_user_id", UUID::class.java), r.getObject("author_user_id", UUID::class.java),
        r.getObject("post_id", UUID::class.java), r.getObject("thread_id", UUID::class.java), r.getLong("version"), r.getString("status"), r.time("expires_at"),
        r.getObject("recipe_version_id", UUID::class.java), r.getString("close_reason"), r.time("created_at"), r.time("updated_at"))
    private fun owned(c: Connection, owner: UUID, id: UUID, lock: Boolean = true): Row = query(c,
        "SELECT * FROM social.recipe_requests WHERE environment=? AND id=? AND (requester_user_id=? OR author_user_id=?)" + if (lock) " FOR UPDATE NOWAIT" else "", {
            setString(1, environment); setObject(2, id); setObject(3, owner); setObject(4, owner)
        }) { if (it.next()) row(it) else fail(RecipeRequestFailureCode.REQUEST_UNAVAILABLE) }
    private fun json(row: Row) = buildJsonObject {
        put("id", row.id.toString()); put("version", row.version); put("createdAt", row.created.toString()); put("updatedAt", row.updated.toString())
        put("postId", row.post.toString()); put("requesterUserId", row.requester.toString()); put("authorUserId", row.author.toString()); put("threadId", row.thread.toString()); put("status", row.status)
        row.recipe?.let { put("recipeVersionId", it.toString()) }
    }
    private fun pendingJson(row: Row) = JsonObject(json(row).toMutableMap().apply {
        put("version", JsonPrimitive(1)); put("status", JsonPrimitive("pending")); put("updatedAt", JsonPrimitive(row.created.toString())); remove("recipeVersionId")
    })
    private fun event(c: Connection, row: Row, owner: UUID, key: UUID, created: Boolean = false) = outbox.append(c,
        EventDraft(UUID.randomUUID(), if (created) "conversations.recipe_request.created.v1" else "conversations.recipe_request.responded.v1", 1, "recipe_request", row.id, row.version,
            "conversations", key.toString(), key, buildJsonObject { put("requestId", row.id.toString()); put("threadId", row.thread.toString()); put("status", row.status) }, EventOwner.account(environment, owner)))
    private fun reply(operation: String, row: Row, status: Int = 200) = StoredReply(status, json(row), "\"${row.version}\"").also { validateReply(operation, it) }
    private fun validateReply(operation: String, reply: StoredReply) {
        val bytes = reply.body?.toString()?.encodeToByteArray()
        if ((bytes?.size ?: 0) > policy.maxResponseBytes || validator.validateResponse(operation, reply.status, bytes, if (bytes == null) null else "application/json") != BodyValidationResult.Valid) fail(RecipeRequestFailureCode.STORAGE_UNAVAILABLE)
    }
    private fun request(operation: String, body: JsonObject): JsonObject {
        val bytes = body.toString().encodeToByteArray(); if (bytes.size > 8192 || validator.validateRequest(operation, bytes, "application/json") != BodyValidationResult.Valid) fail(RecipeRequestFailureCode.INPUT_INVALID)
        return Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    }
    private fun note(input: JsonObject): String = input["message"]?.jsonPrimitive?.content.orEmpty().also {
        if (it.any { char -> char.isISOControl() && char !in "\n\r\t" }) fail(RecipeRequestFailureCode.INPUT_INVALID)
    }
    private fun version(value: String): Long = value.takeIf { it.matches(Regex("\"[1-9][0-9]{0,18}\"")) }?.drop(1)?.dropLast(1)?.toLongOrNull() ?: fail(RecipeRequestFailureCode.INPUT_INVALID)
    private fun JsonObject.uuid(name: String) = UUID.fromString(getValue(name).jsonPrimitive.content)
    private fun scope(actor: VerifiedSocialAccount) = PrincipalScope(environment, CommandActor.ACCOUNT, actor.accountId)
    private fun safetyCurrent(c: Connection, subject: VerifiedSupabaseSubject, device: UUID, owner: UUID) { if (accounts.lockAccountSafety(c, subject, device) != owner) fail(RecipeRequestFailureCode.UNAUTHENTICATED) }
    private fun ResultSet.time(name: String) = getObject(name, OffsetDateTime::class.java).toInstant()
    private fun PreparedStatement.instant(index: Int, value: Instant) = setObject(index, value.atOffset(java.time.ZoneOffset.UTC))
    private fun now(c: Connection) = query(c, "SELECT clock_timestamp()", {}) { it.next(); it.getObject(1, OffsetDateTime::class.java).toInstant() }
    private fun update(c: Connection, sql: String, bind: PreparedStatement.() -> Unit) = c.prepareStatement(sql).use { it.bind(); check(it.executeUpdate() == 1) }
    private fun <T> query(c: Connection, sql: String, bind: PreparedStatement.() -> Unit, result: (ResultSet) -> T): T = c.prepareStatement(sql).use { it.bind(); it.executeQuery().use(result) }
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: RecipeRequestFailure) { throw failure }
        catch (failure: AccountFailure) { fail(if (failure.code == AccountFailureCode.UNAUTHENTICATED) RecipeRequestFailureCode.UNAUTHENTICATED else if (failure.code == AccountFailureCode.NOT_CONFIGURED) RecipeRequestFailureCode.NOT_CONFIGURED else RecipeRequestFailureCode.STORAGE_UNAVAILABLE) }
        catch (failure: SocialFailure) { fail(when (failure.code) { SocialFailureCode.UNAUTHENTICATED -> RecipeRequestFailureCode.UNAUTHENTICATED; SocialFailureCode.CIRCLE_UNAVAILABLE -> RecipeRequestFailureCode.RECIPIENT_UNAVAILABLE; SocialFailureCode.NOT_CONFIGURED -> RecipeRequestFailureCode.NOT_CONFIGURED; else -> RecipeRequestFailureCode.STORAGE_UNAVAILABLE }) }
        catch (failure: ConversationFailure) { fail(when (failure.code) { ConversationFailureCode.UNAUTHENTICATED -> RecipeRequestFailureCode.UNAUTHENTICATED; ConversationFailureCode.CONTACT_UNAVAILABLE, ConversationFailureCode.THREAD_UNAVAILABLE -> RecipeRequestFailureCode.RECIPIENT_UNAVAILABLE; ConversationFailureCode.RATE_LIMITED -> RecipeRequestFailureCode.RATE_LIMITED; ConversationFailureCode.NOT_CONFIGURED -> RecipeRequestFailureCode.NOT_CONFIGURED; else -> RecipeRequestFailureCode.STORAGE_UNAVAILABLE }) }
        catch (failure: PostReadFailure) { fail(when (failure.code) { PostReadFailureCode.UNAUTHENTICATED -> RecipeRequestFailureCode.UNAUTHENTICATED; PostReadFailureCode.POST_UNAVAILABLE -> RecipeRequestFailureCode.SOURCE_UNAVAILABLE; PostReadFailureCode.NOT_CONFIGURED -> RecipeRequestFailureCode.NOT_CONFIGURED; else -> RecipeRequestFailureCode.STORAGE_UNAVAILABLE }) }
        catch (_: RecipeCatalogFailure) { fail(RecipeRequestFailureCode.STORAGE_UNAVAILABLE) }
        catch (failure: CommitOutcomeUnknown) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (_: Exception) { fail(RecipeRequestFailureCode.STORAGE_UNAVAILABLE) }
    override fun toString() = "AccountRecipeRequestStore(<redacted>)"
    companion object { private val validator by lazy { ContractBodyValidator.bundled() }; private fun fail(code: RecipeRequestFailureCode): Nothing = throw RecipeRequestFailure(code) }
}
