package com.feedme.server.social.posts

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.identity.*
import com.feedme.server.social.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** One reversible actor/post row. Commands and events commit together. Neither reaction
 * events nor counts grant access, generate food preferences, or claim notification delivery. */
internal class AccountPostReactionStore(private val environment: String, private val transactions: PgTransactions,
    private val accounts: AccountProfileStore, private val identity: AccountSocialIdentityPolicy,
    private val posts: AccountPostReadStore, val policy: AccountPostReactionPolicy) {
    private val commands = DurableCommands(transactions)
    private val outbox = OutboxStore(transactions)
    init { require(environment == accounts.environment && environment == posts.environment) }

    fun setReaction(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, postId: UUID, body: JsonObject): CommandResult = safe {
        val kind = kind(body)
        transactions.run { c ->
            val actor = identity.resolvePrincipal(c, subject, device)
            if (actor.environment != environment) fail(PostReactionFailureCode.UNAUTHENTICATED)
            val command = CommandIdentity(PrincipalScope(environment, CommandActor.ACCOUNT, actor.accountId),
                "setReaction", key, mapOf("postId" to postId.toString()), body = body)
            var deadline: Instant? = null
            fun authorize(db: Connection): PostReadMaterial {
                val proof = posts.requireRecipeMaterial(db, actor, postId)
                deadline = proof.second
                lock(db, postId, exclusive = true)
                return proof.first
            }
            val result = commands.executeInTransaction(c, command, { identity.lockPrincipal(it, actor) }, {}, { db, cached ->
                val material = authorize(db)
                val row = row(db, actor.accountId, postId) ?: fail(PostReactionFailureCode.REACTION_UNAVAILABLE)
                validate("setReaction", cached)
                val original = cached.body as? JsonObject ?: fail(PostReactionFailureCode.STORAGE_UNAVAILABLE)
                val originalVersion = original.getValue("version").jsonPrimitive.long
                if (cached.status != 200 || cached.etag != etag(originalVersion) || row.postOwner != material.ownerId ||
                    original["id"] != JsonPrimitive(row.id.toString()) || original["userId"] != JsonPrimitive(actor.accountId.toString()) ||
                    original["postId"] != JsonPrimitive(postId.toString()) || original["kind"] != JsonPrimitive(kind) ||
                    original["createdAt"] != JsonPrimitive(row.created.toString()) || originalVersion > row.version ||
                    Instant.parse(original.getValue("updatedAt").jsonPrimitive.content) > row.updated)
                    fail(PostReactionFailureCode.STORAGE_UNAVAILABLE)
            }) { db ->
                if (!policy.enabled) fail(PostReactionFailureCode.NOT_CONFIGURED)
                val material = authorize(db)
                val old = row(db, actor.accountId, postId)
                if (old != null && old.postOwner != material.ownerId) fail(PostReactionFailureCode.STORAGE_UNAVAILABLE)
                if (old?.active == true && old.kind == kind) return@executeInTransaction reply(old)
                if (old == null) exec(db, "INSERT INTO social.post_reactions(environment,actor_user_id,post_owner_user_id,post_id,id,version,active,kind,last_command_key,last_operation_id,created_at,updated_at) " +
                    "SELECT ?,?,?,?,?,1,true,?,?,'setReaction',t,t FROM (SELECT clock_timestamp() t) clock", {
                    setString(1, environment); setObject(2, actor.accountId); setObject(3, material.ownerId); setObject(4, postId)
                    setObject(5, UUID.randomUUID()); setString(6, kind); setObject(7, key)
                }) else {
                    next(old)
                    exec(db, "UPDATE social.post_reactions SET active=true,kind=?,version=version+1,last_command_key=?,last_operation_id='setReaction',updated_at=clock_timestamp() " +
                        "WHERE environment=? AND actor_user_id=? AND post_id=?", {
                        setString(1, kind); setObject(2, key); setString(3, environment); setObject(4, actor.accountId); setObject(5, postId)
                    })
                }
                val saved = row(db, actor.accountId, postId) ?: fail(PostReactionFailureCode.STORAGE_UNAVAILABLE)
                event(db, saved, key, removed = false)
                reply(saved)
            }
            identity.lockPrincipal(c, actor)
            deadline?.let { if (!now(c).isBefore(it)) fail(PostReactionFailureCode.POST_UNAVAILABLE) }
            result
        }
    }

    /** Safety cleanup needs the actual current actor/device and its exact row revision,
     * not permission to redisclose an expired, blocked, removed or recalled post. */
    fun removeReaction(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, postId: UUID, ifMatch: String): CommandResult = safe {
        val expected = version(ifMatch)
        transactions.run { c ->
            val owner = accounts.lockAccountSafety(c, subject, device)
            val command = CommandIdentity(PrincipalScope(environment, CommandActor.ACCOUNT, owner), "removeReaction", key,
                mapOf("postId" to postId.toString()), ifMatch = ifMatch)
            val result = commands.executeInTransaction(c, command, { current(it, subject, device, owner) }, {}, { db, cached ->
                lock(db, postId, exclusive = true)
                val row = row(db, owner, postId) ?: fail(PostReactionFailureCode.REACTION_UNAVAILABLE)
                if (expected == Long.MAX_VALUE || row.version < expected + 1)
                    fail(PostReactionFailureCode.VERSION_CONFLICT)
                if (cached.status != 204 || cached.etag != null || cached.body != null) fail(PostReactionFailureCode.STORAGE_UNAVAILABLE)
                validate("removeReaction", cached)
            }) { db ->
                lock(db, postId, exclusive = true)
                val old = row(db, owner, postId) ?: fail(PostReactionFailureCode.REACTION_UNAVAILABLE)
                if (!old.active || old.version != expected) fail(PostReactionFailureCode.VERSION_CONFLICT)
                next(old)
                exec(db, "UPDATE social.post_reactions SET active=false,version=version+1,last_command_key=?,last_operation_id='removeReaction',updated_at=clock_timestamp() " +
                    "WHERE environment=? AND actor_user_id=? AND post_id=?", {
                    setObject(1, key); setString(2, environment); setObject(3, owner); setObject(4, postId)
                })
                event(db, row(db, owner, postId) ?: fail(PostReactionFailureCode.STORAGE_UNAVAILABLE), key, removed = true)
                StoredReply(204)
            }
            current(c, subject, device, owner)
            result
        }
    }

    /** Called only after the reader's current post/audience/content proof. Shared post
     * reaction fence also protects empty sets. Counts are never publication hints.
     * Streaming bounds memory; only 50 actual actor summaries are retained for delivery. */
    internal fun project(c: Connection, actor: VerifiedSocialAccount, material: PostReadMaterial,
        includeActors: Boolean): PostReactionProjection {
        identity.lockPrincipal(c, actor)
        if (actor.environment != environment || material.post["status"] != JsonPrimitive("published")) fail(PostReactionFailureCode.POST_UNAVAILABLE)
        lock(c, material.postId, exclusive = false)
        val own = row(c, actor.accountId, material.postId)?.also {
            if (it.postOwner != material.ownerId) fail(PostReactionFailureCode.STORAGE_UNAVAILABLE)
        }?.takeIf { it.active }?.let(::json)
        if (!policy.enabled || actor.accountId != material.ownerId)
            return PostReactionProjection(policy.enabled, own, JsonArray(emptyList()), null, null)
        val counts = POST_REACTION_KINDS.associateWith { 0L }.toMutableMap()
        val actors = mutableListOf<JsonObject>(); var visible = 0L; var inspected = 0
        val deadline = minOf(now(c).plusSeconds(60), Instant.ofEpochSecond(checkNotNull(actor.providerSubject).expiresAtEpochSeconds))
        c.prepareStatement("SELECT * FROM social.post_reactions WHERE environment=? AND post_owner_user_id=? AND post_id=? AND active ORDER BY actor_user_id FOR SHARE NOWAIT").use { s ->
            s.setString(1, environment); s.setObject(2, material.ownerId); s.setObject(3, material.postId); s.fetchSize = 128
            s.executeQuery().use { rows -> while (rows.next()) {
                if (++inspected % 128 == 0 && !now(c).isBefore(deadline)) fail(PostReactionFailureCode.POST_UNAVAILABLE)
                val row = decode(rows)
                val profile = try {
                    val value = identity.readProfile(c, environment, row.actor)
                    identity.lockUnblockedPair(c, environment, actor.accountId, row.actor)
                    value
                } catch (failure: SocialFailure) {
                    if (failure.code == SocialFailureCode.CIRCLE_UNAVAILABLE && failure.suppressed.isEmpty()) null else throw failure
                } ?: continue
                val count = counts[row.kind] ?: fail(PostReactionFailureCode.STORAGE_UNAVAILABLE)
                if (count == Long.MAX_VALUE || visible == Long.MAX_VALUE) fail(PostReactionFailureCode.STORAGE_UNAVAILABLE)
                counts[row.kind] = count + 1; visible++
                if (includeActors && actors.size < 50) actors += buildJsonObject {
                    put("reaction", json(row)); put("user", buildJsonObject {
                        put("userId", profile.userId.toString()); put("displayName", profile.displayName); put("handle", profile.handle)
                        profile.avatarMediaId?.let { put("avatarMediaId", it.toString()) }
                    })
                }
            } }
        }
        if (!now(c).isBefore(deadline)) fail(PostReactionFailureCode.POST_UNAVAILABLE)
        return PostReactionProjection(true, own, JsonArray(POST_REACTION_KINDS.mapNotNull { kind -> counts.getValue(kind).takeIf { it > 0 }?.let { count ->
            buildJsonObject { put("kind", kind); put("count", count) }
        } }), if (includeActors) JsonArray(actors) else null, if (includeActors) visible > actors.size else null)
    }

    private fun row(c: Connection, owner: UUID, post: UUID): Row? = c.prepareStatement(
        "SELECT * FROM social.post_reactions WHERE environment=? AND actor_user_id=? AND post_id=? FOR SHARE NOWAIT").use {
        it.setString(1, environment); it.setObject(2, owner); it.setObject(3, post)
        it.executeQuery().use { r -> if (!r.next()) null else decode(r).also { if (r.next()) fail(PostReactionFailureCode.STORAGE_UNAVAILABLE) } }
    }
    private class Row(val id: UUID, val actor: UUID, val postOwner: UUID, val post: UUID, val version: Long,
        val active: Boolean, val kind: String, val command: UUID, val operation: String, val created: Instant, val updated: Instant)
    private fun decode(r: ResultSet) = Row(r.getObject("id", UUID::class.java), r.getObject("actor_user_id", UUID::class.java),
        r.getObject("post_owner_user_id", UUID::class.java), r.getObject("post_id", UUID::class.java), r.getLong("version"),
        r.getBoolean("active"), r.getString("kind"), r.getObject("last_command_key", UUID::class.java), r.getString("last_operation_id"),
        r.getObject("created_at", OffsetDateTime::class.java).toInstant(), r.getObject("updated_at", OffsetDateTime::class.java).toInstant())
    private fun json(row: Row) = buildJsonObject {
        put("id", row.id.toString()); put("version", row.version); put("createdAt", row.created.toString()); put("updatedAt", row.updated.toString())
        put("postId", row.post.toString()); put("userId", row.actor.toString()); put("kind", row.kind)
    }
    private fun event(c: Connection, row: Row, key: UUID, removed: Boolean) = outbox.append(c, EventDraft(UUID.randomUUID(),
        if (removed) "social.reaction.removed.v1" else "social.reaction.changed.v1", 1, "reaction", row.id, row.version,
        "social", key.toString(), key, buildJsonObject {
            put("reactionId", row.id.toString()); put("postId", row.post.toString()); put("postOwnerUserId", row.postOwner.toString())
            put("actorUserId", row.actor.toString()); put("kind", row.kind); put("active", row.active)
        }, EventOwner.account(environment, row.actor)))
    private fun lock(c: Connection, post: UUID, exclusive: Boolean) {
        require(!c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
        val material = buildJsonArray { add("feedme.social.post-reactions.v1"); add(environment); add(post.toString()) }
        val key = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(material.toString().encodeToByteArray())).long
        val name = if (exclusive) "pg_try_advisory_xact_lock" else "pg_try_advisory_xact_lock_shared"
        c.prepareStatement("SELECT $name(?)").use { s -> s.setLong(1, key); s.executeQuery().use {
            if (!it.next() || !it.getBoolean(1)) throw SQLException("Reaction fence contended", "40001")
        } }
    }
    private fun current(c: Connection, subject: VerifiedSupabaseSubject, device: UUID, owner: UUID) {
        if (accounts.lockAccountSafety(c, subject, device) != owner) fail(PostReactionFailureCode.UNAUTHENTICATED)
    }
    private fun kind(body: JsonObject): String {
        if (body.keys != setOf("kind")) fail(PostReactionFailureCode.INPUT_INVALID)
        val value = (body["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail(PostReactionFailureCode.INPUT_INVALID)
        if (value !in POST_REACTION_KINDS) fail(PostReactionFailureCode.REACTION_UNSUPPORTED)
        if (validator.validateRequest("setReaction", body.toString().encodeToByteArray(), "application/json") != BodyValidationResult.Valid)
            fail(PostReactionFailureCode.INPUT_INVALID)
        return value
    }
    private fun reply(row: Row) = StoredReply(200, json(row), etag(row.version)).also { validate("setReaction", it) }
    private fun validate(operation: String, reply: StoredReply) {
        val bytes = reply.body?.toString()?.encodeToByteArray()
        if ((bytes?.size ?: 0) > policy.maxResponseBytes || validator.validateResponse(operation, reply.status, bytes,
                if (bytes == null) null else "application/json") != BodyValidationResult.Valid) fail(PostReactionFailureCode.STORAGE_UNAVAILABLE)
    }
    private fun next(row: Row) { if (row.version == Long.MAX_VALUE) fail(PostReactionFailureCode.STORAGE_UNAVAILABLE) }
    private fun etag(version: Long) = "\"$version\""
    private fun version(value: String): Long = value.takeIf { it.matches(Regex("\"[1-9][0-9]{0,18}\"")) }
        ?.removeSurrounding("\"")?.toLongOrNull() ?: fail(PostReactionFailureCode.INPUT_INVALID)
    private fun now(c: Connection): Instant = c.createStatement().use { s -> s.executeQuery("SELECT clock_timestamp()").use {
        check(it.next()); it.getObject(1, OffsetDateTime::class.java).toInstant()
    } }
    private fun exec(c: Connection, sql: String, bind: PreparedStatement.() -> Unit) = c.prepareStatement(sql).use { it.bind(); check(it.executeUpdate() == 1) }
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: PostReactionFailure) { throw failure }
        catch (failure: PostReadFailure) { fail(when (failure.code) {
            PostReadFailureCode.UNAUTHENTICATED -> PostReactionFailureCode.UNAUTHENTICATED
            PostReadFailureCode.NOT_CONFIGURED -> PostReactionFailureCode.NOT_CONFIGURED
            PostReadFailureCode.STORAGE_UNAVAILABLE -> PostReactionFailureCode.STORAGE_UNAVAILABLE
            else -> PostReactionFailureCode.POST_UNAVAILABLE
        }) }
        catch (failure: SocialFailure) { fail(when (failure.code) {
            SocialFailureCode.UNAUTHENTICATED -> PostReactionFailureCode.UNAUTHENTICATED
            SocialFailureCode.NOT_CONFIGURED -> PostReactionFailureCode.NOT_CONFIGURED
            SocialFailureCode.STORAGE_UNAVAILABLE -> PostReactionFailureCode.STORAGE_UNAVAILABLE
            else -> PostReactionFailureCode.POST_UNAVAILABLE
        }) }
        catch (failure: AccountFailure) { fail(when (failure.code) {
            AccountFailureCode.UNAUTHENTICATED, AccountFailureCode.ACCOUNT_UNAVAILABLE -> PostReactionFailureCode.UNAUTHENTICATED
            AccountFailureCode.NOT_CONFIGURED -> PostReactionFailureCode.NOT_CONFIGURED
            else -> PostReactionFailureCode.STORAGE_UNAVAILABLE
        }) }
        catch (failure: CommitOutcomeUnknown) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (_: Exception) { fail(PostReactionFailureCode.STORAGE_UNAVAILABLE) }
    override fun toString() = "AccountPostReactionStore(<redacted>)"
    companion object {
        private val validator by lazy { ContractBodyValidator.bundled() }
        private fun fail(code: PostReactionFailureCode): Nothing = throw PostReactionFailure(code)
    }
}
