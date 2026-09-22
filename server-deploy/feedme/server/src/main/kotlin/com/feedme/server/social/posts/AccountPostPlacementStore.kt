package com.feedme.server.social.posts

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.social.AccountSocialIdentityPolicy
import com.feedme.server.social.SocialFailure
import com.feedme.server.social.SocialFailureCode
import com.feedme.server.social.VerifiedSocialAccount
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** POST.08/.14 only. A placement update never changes audience, content, expiry, source,
 * recipe-save policy or media. Original receipts are not fresh delivery capabilities. */
internal class AccountPostPlacementStore(private val environment: String, private val transactions: PgTransactions,
    private val identity: AccountSocialIdentityPolicy, private val posts: AccountPostReadStore,
    val policy: AccountPostPlacementPolicy) {
    private val commands = DurableCommands(transactions)
    private val outbox = OutboxStore(transactions)
    init { require(environment == posts.environment && environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    fun canChangePlacement(c: Connection, actor: VerifiedSocialAccount, material: PostReadMaterial): Boolean = safe {
        if (!policy.enabled || actor.environment != environment || actor.accountId != material.ownerId ||
            material.post["status"] != JsonPrimitive("published")) return@safe false
        identity.lockPrincipal(c, actor)
        material.version < Long.MAX_VALUE && integer(material.post["aclVersion"]) < Long.MAX_VALUE &&
            material.post["keepOnPlate"]?.jsonPrimitive?.booleanOrNull != null
    }

    fun updatePost(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, postId: UUID,
        ifMatch: String, body: JsonObject): CommandResult = safe {
        val keep = request(body)
        val expected = ifMatch.takeIf { it.matches(Regex("\"[1-9][0-9]{0,18}\"")) }
            ?.removeSurrounding("\"")?.toLongOrNull() ?: fail(PostPlacementFailureCode.INPUT_INVALID)
        transactions.run { c ->
            val actor = identity.resolvePrincipal(c, subject, device)
            if (actor.environment != environment) fail(PostPlacementFailureCode.UNAUTHENTICATED)
            val command = CommandIdentity(PrincipalScope(environment, CommandActor.ACCOUNT, actor.accountId),
                "updatePost", key, mapOf("postId" to postId.toString()), ifMatch = ifMatch, body = body)
            var proof: PostPlacementObservation? = null
            val result = commands.executeInTransaction(c, command, { identity.lockPrincipal(it, actor) }, {},
                { db, cached ->
                    val checked = posts.requirePlacementMaterial(db, actor, postId, requirePlacement = false)
                    proof = checked
                    val document = receipt(cached)
                    val version = integer(document["version"])
                    if (checked.material.version != version || version != expected && (expected == Long.MAX_VALUE || version != expected + 1) ||
                        document["id"] != JsonPrimitive(postId.toString()) ||
                        document["author"]?.jsonObject?.get("userId") != JsonPrimitive(actor.accountId.toString()) ||
                        document["keepOnPlate"] != JsonPrimitive(keep) ||
                        document["capabilities"] != JsonArray(emptyList()) || document.containsKey("sourcePostId") ||
                        staticFields(document) != staticFields(checked.material.post) ||
                        document["savePolicy"] != checked.material.savePolicy)
                        fail(PostPlacementFailureCode.VERSION_CONFLICT)
                }) { db ->
                if (!policy.enabled) fail(PostPlacementFailureCode.NOT_CONFIGURED)
                val checked = posts.requirePlacementMaterial(db, actor, postId, requirePlacement = true)
                proof = checked
                val material = checked.material
                if (material.version != expected) fail(PostPlacementFailureCode.VERSION_CONFLICT)
                lockExact(db, material)
                if (material.post["keepOnPlate"] == JsonPrimitive(keep))
                    return@executeInTransaction response(checked.receipt, material.version)
                val oldAcl = integer(material.post["aclVersion"])
                if (material.version == Long.MAX_VALUE || oldAcl == Long.MAX_VALUE) fail(PostPlacementFailureCode.STORAGE_UNAVAILABLE)
                val at = now(db)
                if (at < Instant.parse(material.post.getValue("updatedAt").jsonPrimitive.content))
                    fail(PostPlacementFailureCode.STORAGE_UNAVAILABLE)
                val changed = mapOf("keepOnPlate" to JsonPrimitive(keep), "version" to JsonPrimitive(material.version + 1),
                    "aclVersion" to JsonPrimitive(oldAcl + 1), "updatedAt" to JsonPrimitive(at.toString()))
                val persisted = JsonObject(material.post + changed)
                val reply = response(JsonObject(checked.receipt + changed), material.version + 1)
                db.prepareStatement("UPDATE social.posts SET version=?,content=?::jsonb,updated_at=?::timestamptz " +
                    "WHERE environment=? AND owner_user_id=? AND id=? AND version=? AND status='published'").use {
                    it.setLong(1, material.version + 1); it.setString(2, persisted.toString()); it.setString(3, at.toString())
                    it.setString(4, environment); it.setObject(5, actor.accountId); it.setObject(6, postId); it.setLong(7, expected)
                    if (it.executeUpdate() != 1) fail(PostPlacementFailureCode.STORAGE_UNAVAILABLE)
                }
                outbox.append(db, EventDraft(UUID.randomUUID(), "social.post.placement_changed.v1", 1, "post", postId,
                    material.version + 1, "social", key.toString(), key, buildJsonObject {
                        put("postId", postId.toString()); put("aclVersion", oldAcl + 1); put("keepOnPlate", keep)
                    }, EventOwner.account(environment, actor.accountId)))
                reply
            }
            identity.lockPrincipal(c, actor)
            proof?.let { if (!now(c).isBefore(it.validUntil)) fail(PostPlacementFailureCode.POST_UNAVAILABLE) }
            result
        }
    }

    private fun lockExact(c: Connection, material: PostReadMaterial) {
        try {
            c.prepareStatement("SELECT version,status,content FROM social.posts WHERE environment=? AND owner_user_id=? AND id=? FOR UPDATE NOWAIT").use {
                it.setString(1, environment); it.setObject(2, material.ownerId); it.setObject(3, material.postId)
                it.executeQuery().use { r ->
                    if (!r.next() || r.getLong(1) != material.version || r.getString(2) != "published" ||
                        r.getString(3)?.let { raw -> Json.parseToJsonElement(raw).jsonObject } != material.post || r.next())
                        fail(PostPlacementFailureCode.VERSION_CONFLICT)
                }
            }
        } catch (failure: SQLException) {
            if (failure.sqlState != "55P03") throw failure
            throw SQLException("Post placement fence contended", "40001").also { failure.suppressed.forEach(it::addSuppressed) }
        }
    }
    private fun request(body: JsonObject): Boolean {
        if (body.keys != setOf("keepOnPlate") || body["keepOnPlate"] !is JsonPrimitive ||
            body.getValue("keepOnPlate").jsonPrimitive.isString ||
            validator.validateRequest("updatePost", body.toString().encodeToByteArray(), "application/json") != BodyValidationResult.Valid)
            fail(PostPlacementFailureCode.INPUT_INVALID)
        return body.getValue("keepOnPlate").jsonPrimitive.booleanOrNull ?: fail(PostPlacementFailureCode.INPUT_INVALID)
    }
    private fun receipt(reply: StoredReply): JsonObject {
        val body = reply.body as? JsonObject ?: fail(PostPlacementFailureCode.STORAGE_UNAVAILABLE)
        val bytes = body.toString().encodeToByteArray()
        if (reply.status != 200 || bytes.size > policy.maxResponseBytes ||
            validator.validateResponse("updatePost", 200, bytes, "application/json") != BodyValidationResult.Valid ||
            reply.etag != "\"${integer(body["version"])}\"") fail(PostPlacementFailureCode.STORAGE_UNAVAILABLE)
        return body
    }
    private fun response(body: JsonObject, version: Long) = StoredReply(200, body, "\"$version\"").also(::receipt)
    private fun staticFields(body: JsonObject) = JsonObject(body - setOf("author", "sourcePostId", "capabilities", "reactionCounts", "savePolicy", "myReaction", "reactionActors", "reactionActorsHasMore"))
    private fun integer(value: JsonElement?): Long = (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        ?.takeIf { it > 0 } ?: fail(PostPlacementFailureCode.STORAGE_UNAVAILABLE)
    private fun now(c: Connection): Instant = c.createStatement().use { s -> s.executeQuery("SELECT clock_timestamp()").use {
        check(it.next()); it.getObject(1, OffsetDateTime::class.java).toInstant()
    } }
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: PostPlacementFailure) { throw failure }
        catch (failure: PostReadFailure) { fail(when (failure.code) {
            PostReadFailureCode.UNAUTHENTICATED -> PostPlacementFailureCode.UNAUTHENTICATED
            PostReadFailureCode.NOT_CONFIGURED -> PostPlacementFailureCode.NOT_CONFIGURED
            PostReadFailureCode.STORAGE_UNAVAILABLE -> PostPlacementFailureCode.STORAGE_UNAVAILABLE
            else -> PostPlacementFailureCode.POST_UNAVAILABLE
        }) }
        catch (failure: SocialFailure) { fail(when (failure.code) {
            SocialFailureCode.UNAUTHENTICATED -> PostPlacementFailureCode.UNAUTHENTICATED
            SocialFailureCode.NOT_CONFIGURED -> PostPlacementFailureCode.NOT_CONFIGURED
            SocialFailureCode.STORAGE_UNAVAILABLE -> PostPlacementFailureCode.STORAGE_UNAVAILABLE
            else -> PostPlacementFailureCode.POST_UNAVAILABLE
        }) }
        catch (failure: CommitOutcomeUnknown) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (_: Exception) { fail(PostPlacementFailureCode.STORAGE_UNAVAILABLE) }
    override fun toString() = "AccountPostPlacementStore(<redacted>)"
    companion object {
        private val validator by lazy { ContractBodyValidator.bundled() }
        private fun fail(code: PostPlacementFailureCode): Nothing = throw PostPlacementFailure(code)
    }
}
