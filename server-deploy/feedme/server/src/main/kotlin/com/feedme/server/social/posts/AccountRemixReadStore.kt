package com.feedme.server.social.posts

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.PgTransactions
import com.feedme.server.db.StoredReply
import com.feedme.server.social.AccountSocialIdentityPolicy
import com.feedme.server.social.SocialFailure
import com.feedme.server.social.SocialFailureCode
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

class RemixReadPolicy(val maxResponseBytes: Int, val maxDepth: Int, val cursorLifetimeSeconds: Int) {
    init { require(maxResponseBytes in 1..262144 && maxDepth in 20..200 && cursorLifetimeSeconds in 1..60) }
}
internal class PostRemixObservation(val postId: UUID, val body: JsonObject, val validUntil: Instant)

/** Read-only, bounded ancestry. Hidden nodes/edges/counts never enter the canonical DTO.
 * The original recipe/media text is never copied into the graph. Selecting a visible ID
 * requires a separate current getPost and a separate media capability. */
class AccountRemixReadStore(val environment: String, private val transactions: PgTransactions,
    private val identity: AccountSocialIdentityPolicy, private val posts: AccountPostReadStore,
    private val cursors: RemixCursors, val policy: RemixReadPolicy) {
    init { require(environment == posts.environment && environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    fun getRemixTrail(subject: VerifiedSupabaseSubject, device: UUID, postId: UUID,
        cursor: String? = null, limit: Int = 20): StoredReply = safe {
        if (limit !in 1..20) fail(PostReadFailureCode.INPUT_INVALID)
        transactions.run { c ->
            val actor = identity.resolvePrincipal(c, subject, device)
            if (actor.environment != environment) fail(PostReadFailureCode.UNAUTHENTICATED)
            val initial = now(c)
            val position = cursor?.let { cursors.decode(it, environment, actor.accountId, postId, limit, initial) }
            val expiry = position?.expiresAt ?: minOf(initial.plusSeconds(policy.cursorLifetimeSeconds.toLong()),
                Instant.ofEpochSecond(subject.expiresAtEpochSeconds))
            // Authorize the root before inspecting any private ancestry or stored receipts.
            val root = posts.observeRemixPosts(c, actor, listOf(postId)).singleOrNull()
                ?: fail(PostReadFailureCode.POST_UNAVAILABLE)
            val lineage = mutableListOf<UUID>()
            var next: UUID? = postId
            while (next != null && lineage.size < policy.maxDepth) {
                val id = next
                if (id in lineage) fail(PostReadFailureCode.STORAGE_UNAVAILABLE)
                lineage += id
                next = posts.originalRemixParent(c, id)
            }
            val offset = position?.offset ?: 0
            if (offset !in lineage.indices) fail(PostReadFailureCode.INPUT_INVALID)
            // The encrypted cursor carries scan position, not visible/hidden node counts.
            val candidates = lineage.drop(offset).take(limit)
            val observed = posts.observeRemixPosts(c, actor, candidates).associateBy { it.postId }
            identity.lockPrincipal(c, actor)
            val at = now(c)
            if (!at.isBefore(expiry)) fail(PostReadFailureCode.CURSOR_EXPIRED)
            if (!at.isBefore(root.validUntil)) fail(PostReadFailureCode.POST_UNAVAILABLE)
            val visible = candidates.mapNotNull { observed[it]?.takeIf { item -> at.isBefore(item.validUntil) } }
            val visibleIds = visible.map { it.postId }.toSet()
            val items = visible.map { item -> buildJsonObject {
                put("postId", item.postId.toString()); put("available", true)
                // Both endpoints must be visible in this same response before an edge is disclosed.
                val index = lineage.indexOf(item.postId)
                lineage.getOrNull(index + 1)?.takeIf { it in visibleIds }?.let { put("parentPostId", it.toString()) }
                put("author", item.body.getValue("author"))
                item.body["caption"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { put("title", it) }
                item.body.getValue("mediaIds").jsonArray.firstOrNull()?.let { put("thumbnailMediaId", it) }
            } }
            val continuation = (offset + candidates.size).takeIf { it < lineage.size }?.let {
                cursors.encode(environment, actor.accountId, postId, limit, it, expiry)
            }
            val body = buildJsonObject {
                put("items", JsonArray(items)); put("nextCursor", continuation?.let(::JsonPrimitive) ?: JsonNull)
                put("serverTime", at.toString())
            }
            val bytes = body.toString().encodeToByteArray()
            if (bytes.size > policy.maxResponseBytes || validator.validateResponse("getRemixTrail", 200, bytes, "application/json") != BodyValidationResult.Valid)
                fail(PostReadFailureCode.STORAGE_UNAVAILABLE)
            StoredReply(200, body)
        }
    }

    /** Admission only. No grants, owner fallback or migration is installed here. */
    fun checkCompatibility() = safe { transactions.run { c ->
        PostReadServingCompatibility.check(c)
        c.createStatement().use { s ->
            s.executeQuery("SELECT principal_scope,operation_id,key,request_hash,state,response_code,response_json,response_etag FROM platform.idempotency WHERE false").use { check(!it.next()) }
        }
    } }

    private fun now(c: Connection): Instant = c.createStatement().use { s ->
        s.executeQuery("SELECT clock_timestamp()").use { check(it.next()); it.getObject(1, OffsetDateTime::class.java).toInstant() }
    }
    private fun fail(code: PostReadFailureCode): Nothing = throw PostReadFailure(code)
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: PostReadFailure) { throw failure }
        catch (failure: RemixCursorFailure) { fail(if (failure.expired) PostReadFailureCode.CURSOR_EXPIRED else PostReadFailureCode.INPUT_INVALID) }
        catch (failure: SocialFailure) { fail(when (failure.code) {
            SocialFailureCode.UNAUTHENTICATED -> PostReadFailureCode.UNAUTHENTICATED
            SocialFailureCode.CIRCLE_UNAVAILABLE -> PostReadFailureCode.POST_UNAVAILABLE
            SocialFailureCode.NOT_CONFIGURED -> PostReadFailureCode.NOT_CONFIGURED
            else -> PostReadFailureCode.STORAGE_UNAVAILABLE
        }) }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (_: Exception) { fail(PostReadFailureCode.STORAGE_UNAVAILABLE) }
    override fun toString() = "AccountRemixReadStore(<redacted>)"
    companion object { private val validator by lazy { ContractBodyValidator.bundled() } }
}
