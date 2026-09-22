package com.feedme.server.social.posts

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.db.*
import com.feedme.server.identity.*
import com.feedme.server.media.*
import com.feedme.server.media.processing.MediaProcessingFailure
import com.feedme.server.social.VerifiedSocialAccount
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

class AccountPostDeletionPolicy(val enabled: Boolean) {
    override fun toString() = "AccountPostDeletionPolicy(<redacted>)"
}
enum class PostDeletionFailureCode(val status: Int) {
    INPUT_INVALID(422), UNAUTHENTICATED(401), POST_UNAVAILABLE(404), VERSION_CONFLICT(412),
    MEDIA_UNAVAILABLE(409), NOT_CONFIGURED(503), STORAGE_UNAVAILABLE(503),
}
class PostDeletionFailure(val code: PostDeletionFailureCode) : RuntimeException("Post deletion unavailable: ${code.name}")

/** Owner removal is not new authoring: current account/device is mandatory, but new
 * Terms, active audience membership, upload/publish switches and a fresh READY safety
 * grant are not prerequisites. A committed 204 means app visibility is tombstoned and
 * exact cleanup is retained, NEVER that remote objects/copies/screenshots are erased. */
internal class AccountPostDeletionStore(private val environment: String, private val transactions: PgTransactions,
    private val accounts: AccountProfileStore, private val media: MediaStore, private val policy: AccountPostDeletionPolicy) {
    private val commands = DurableCommands(transactions)
    private val outbox = OutboxStore(transactions)
    init { require(environment == accounts.environment && environment == media.environment) }

    fun canDelete(c: Connection, actor: VerifiedSocialAccount, material: PostReadMaterial): Boolean = safe {
        if (!policy.enabled || actor.environment != environment || actor.accountId != material.ownerId) return@safe false
        val subject = actor.providerSubject ?: fail(PostDeletionFailureCode.UNAUTHENTICATED)
        current(c, subject, actor.deviceSessionId, actor.accountId)
        val row = post(c, actor.accountId, material.postId)
        row.status == "published" && row.version == material.version
    }

    fun deletePost(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, postId: UUID, ifMatch: String): CommandResult = safe {
        val expected = ifMatch.takeIf { it.matches(Regex("\"[1-9][0-9]{0,18}\"")) }?.drop(1)?.dropLast(1)?.toLongOrNull()
            ?: fail(PostDeletionFailureCode.INPUT_INVALID)
        transactions.run { c ->
            val actor = VerifiedSocialAccount.resolveSafety(c, accounts, subject, device)
            val owner = actor.accountId
            val command = CommandIdentity(PrincipalScope(environment, CommandActor.ACCOUNT, owner), "deletePost", key,
                mapOf("postId" to postId.toString()), ifMatch = ifMatch)
            val result = commands.executeInTransaction(c, command, { current(it, subject, device, owner) }, {},
                { db, cached ->
                    val original = lineage(db, owner, postId)
                    val row = post(db, owner, postId)
                    if (expected == Long.MAX_VALUE || row.status != "deleted" || row.version != expected + 1 ||
                        cached.status != 204 || cached.body != null || cached.etag != null) fail(PostDeletionFailureCode.VERSION_CONFLICT)
                    requireSavesDisabled(db, owner, postId)
                    media.discardDeletedPostMedia(db, VerifiedMediaAccount.fromSocial(actor), postId, original.client, original.generation, key, replay = true)
                }) { db ->
                if (!policy.enabled) fail(PostDeletionFailureCode.NOT_CONFIGURED)
                val original = lineage(db, owner, postId)
                val row = post(db, owner, postId)
                if (row.status == "deleted") fail(PostDeletionFailureCode.POST_UNAVAILABLE)
                if (row.status !in setOf("published", "hidden") || row.version != expected || row.version == Long.MAX_VALUE) fail(PostDeletionFailureCode.VERSION_CONFLICT)
                val content = row.content ?: fail(PostDeletionFailureCode.STORAGE_UNAVAILABLE)
                val mediaIds = query(db, "SELECT media_id FROM social.post_media WHERE environment=? AND owner_user_id=? AND post_id=? ORDER BY position FOR SHARE NOWAIT", {
                    owner(owner); setObject(3, postId)
                }) { r -> buildList { while (r.next()) { if (size >= 2048) fail(PostDeletionFailureCode.STORAGE_UNAVAILABLE); add(r.getObject(1, UUID::class.java).toString()) } } }
                if (content["mediaIds"]?.jsonArray?.map { it.jsonPrimitive.content } != mediaIds ||
                    content["author"]?.jsonObject?.get("userId")?.jsonPrimitive?.content != owner.toString()) fail(PostDeletionFailureCode.STORAGE_UNAVAILABLE)
                val saveVersion = query(db, "SELECT version FROM social.recipe_save_policies WHERE environment=? AND owner_user_id=? AND post_id=? FOR UPDATE NOWAIT", {
                    owner(owner); setObject(3, postId)
                }) { if (!it.next()) fail(PostDeletionFailureCode.STORAGE_UNAVAILABLE); it.getLong(1) }
                if (saveVersion == Long.MAX_VALUE) fail(PostDeletionFailureCode.STORAGE_UNAVAILABLE)
                update(db, "UPDATE social.recipe_save_policies SET version=version+1,allow_future_saves=false WHERE environment=? AND owner_user_id=? AND post_id=?", {
                    owner(owner); setObject(3, postId)
                })
                update(db, "UPDATE social.posts SET status='deleted',content=NULL,version=version+1,updated_at=clock_timestamp() WHERE environment=? AND owner_user_id=? AND id=? AND version=?", {
                    owner(owner); setObject(3, postId); setLong(4, expected)
                })
                media.discardDeletedPostMedia(db, VerifiedMediaAccount.fromSocial(actor), postId, original.client, original.generation, key, replay = false)
                outbox.append(db, EventDraft(UUID.randomUUID(), "social.post.deleted.v1", 1, "post", postId, expected + 1,
                    "social", key.toString(), key, buildJsonObject {
                        put("postId", postId.toString()); put("aclVersion", expected + 1); put("deletionReasonCode", "owner_request")
                    }, EventOwner.account(environment, owner)))
                StoredReply(204)
            }
            current(c, subject, device, owner); result
        }
    }

    /** Immutable publication discovery is not authority; lock owner head and exact client
     * root before returning the re-read publication. No new draft/root is created. */
    private fun lineage(c: Connection, owner: UUID, id: UUID): Lineage {
        val found = query(c, "SELECT client_draft_id,draft_generation FROM social.post_publications WHERE environment=? AND owner_user_id=? AND post_id=?", {
            owner(owner); setObject(3, id)
        }) { if (!it.next()) fail(PostDeletionFailureCode.POST_UNAVAILABLE); Lineage(it.getObject(1, UUID::class.java), it.getLong(2)) }
        query(c, "SELECT revision FROM platform.post_draft_heads WHERE environment=? AND owner_user_id=? FOR UPDATE NOWAIT", { owner(owner) }) {
            if (it.next() && it.getLong(1) <= 0) fail(PostDeletionFailureCode.STORAGE_UNAVAILABLE)
        }
        query(c, "SELECT generation FROM platform.media_draft_lifecycles WHERE environment=? AND owner_user_id=? AND client_draft_id=? FOR UPDATE NOWAIT", {
            owner(owner); setObject(3, found.client)
        }) { if (!it.next() || it.getLong(1) != found.generation) fail(PostDeletionFailureCode.STORAGE_UNAVAILABLE) }
        query(c, "SELECT client_draft_id,draft_generation FROM social.post_publications WHERE environment=? AND owner_user_id=? AND post_id=? FOR SHARE NOWAIT", {
            owner(owner); setObject(3, id)
        }) { if (!it.next() || it.getObject(1, UUID::class.java) != found.client || it.getLong(2) != found.generation) fail(PostDeletionFailureCode.STORAGE_UNAVAILABLE) }
        return found
    }
    private class Lineage(val client: UUID, val generation: Long)
    private class Post(val version: Long, val status: String, val content: JsonObject?)
    private fun post(c: Connection, owner: UUID, id: UUID): Post = query(c, "SELECT version,status,content FROM social.posts WHERE environment=? AND owner_user_id=? AND id=? FOR UPDATE NOWAIT", {
        owner(owner); setObject(3, id)
    }) { if (!it.next()) fail(PostDeletionFailureCode.POST_UNAVAILABLE); Post(it.getLong(1), it.getString(2), it.getString(3)?.let { raw -> Json.parseToJsonElement(raw).jsonObject }) }
    private fun requireSavesDisabled(c: Connection, owner: UUID, id: UUID) = query(c, "SELECT allow_future_saves FROM social.recipe_save_policies WHERE environment=? AND owner_user_id=? AND post_id=? FOR SHARE NOWAIT", {
        owner(owner); setObject(3, id)
    }) { if (!it.next() || it.getBoolean(1)) fail(PostDeletionFailureCode.STORAGE_UNAVAILABLE) }
    private fun current(c: Connection, subject: VerifiedSupabaseSubject, device: UUID, owner: UUID) {
        if (accounts.lockAccountSafety(c, subject, device) != owner) fail(PostDeletionFailureCode.UNAUTHENTICATED)
    }
    private fun PreparedStatement.owner(owner: UUID) { setString(1, environment); setObject(2, owner) }
    private fun update(c: Connection, sql: String, bind: PreparedStatement.() -> Unit) = c.prepareStatement(sql).use { it.bind(); check(it.executeUpdate() == 1) }
    private fun <T> query(c: Connection, sql: String, bind: PreparedStatement.() -> Unit, result: (ResultSet) -> T): T = c.prepareStatement(sql).use { it.bind(); it.executeQuery().use(result) }
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: PostDeletionFailure) { throw failure }
        catch (failure: AccountFailure) { fail(when (failure.code) {
            AccountFailureCode.UNAUTHENTICATED, AccountFailureCode.ACCOUNT_UNAVAILABLE -> PostDeletionFailureCode.UNAUTHENTICATED
            AccountFailureCode.NOT_CONFIGURED -> PostDeletionFailureCode.NOT_CONFIGURED
            else -> PostDeletionFailureCode.STORAGE_UNAVAILABLE
        }) }
        catch (failure: MediaFailure) { fail(when (failure.code) {
            MediaFailureCode.UNAUTHENTICATED -> PostDeletionFailureCode.UNAUTHENTICATED
            MediaFailureCode.NOT_CONFIGURED -> PostDeletionFailureCode.NOT_CONFIGURED
            MediaFailureCode.STORAGE_UNAVAILABLE -> PostDeletionFailureCode.STORAGE_UNAVAILABLE
            else -> PostDeletionFailureCode.MEDIA_UNAVAILABLE
        }) }
        catch (_: MediaProcessingFailure) { fail(PostDeletionFailureCode.MEDIA_UNAVAILABLE) }
        catch (failure: CommitOutcomeUnknown) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (_: Exception) { fail(PostDeletionFailureCode.STORAGE_UNAVAILABLE) }
    override fun toString() = "AccountPostDeletionStore(<redacted>)"
    companion object { private fun fail(code: PostDeletionFailureCode): Nothing = throw PostDeletionFailure(code) }
}
