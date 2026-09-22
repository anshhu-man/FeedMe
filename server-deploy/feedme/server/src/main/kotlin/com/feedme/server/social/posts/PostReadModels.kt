package com.feedme.server.social.posts

import com.feedme.server.social.VerifiedSocialAccount
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

class PostReadPolicy(val maxResponseBytes: Int, val maxCandidates: Int, val cursorLifetimeSeconds: Int) {
    init { require(maxResponseBytes in 1..262144 && maxCandidates in 50..500 && cursorLifetimeSeconds in 1..86400) }
    override fun toString() = "PostReadPolicy(<redacted>)"
}
enum class PostReadFailureCode(val status: Int) {
    INPUT_INVALID(422), UNAUTHENTICATED(401), POST_UNAVAILABLE(404), CURSOR_EXPIRED(410),
    NOT_CONFIGURED(503), STORAGE_UNAVAILABLE(503),
}
class PostReadFailure(val code: PostReadFailureCode) : RuntimeException("Post read unavailable: ${code.name}")

/** Exact current database material. Identifiers/READY/old publication capabilities are not
 * grants. This model is never sent to the client; derivative object references stay private. */
class PostReadMaterial internal constructor(val ownerId: UUID, val postId: UUID, val version: Long,
    val post: JsonObject, val attachment: JsonObject?, val recipeSnapshot: JsonObject?, val recipeSha256: String?,
    val media: List<PostReadMedia>, val savePolicy: JsonObject) {
    override fun toString() = "PostReadMaterial(<redacted>)"
}
class PostReadMedia internal constructor(val id: UUID, val version: Long, val derivatives: JsonObject) {
    override fun toString() = "PostReadMedia(<redacted>)"
}
class PostReadGrant(capabilities: Set<String>, reactionCounts: JsonArray, val validUntil: Instant,
    myReaction: JsonObject? = null, reactionActors: JsonArray? = null, val reactionActorsHasMore: Boolean? = null) {
    val capabilities = capabilities.toSet()
    val reactionCounts = Json.parseToJsonElement(reactionCounts.toString()).jsonArray
    val myReaction = myReaction?.let { Json.parseToJsonElement(it.toString()).jsonObject }
    val reactionActors = reactionActors?.let { Json.parseToJsonElement(it.toString()).jsonArray }
    init {
        require("view" in this.capabilities && this.capabilities.all { it in POST_CAPABILITIES })
        require(this.reactionCounts.toString().encodeToByteArray().size <= 65536)
        require((this.reactionActors == null) == (reactionActorsHasMore == null))
        require(this.reactionActors == null || this.reactionActors.size <= 50 && this.reactionActors.toString().encodeToByteArray().size <= 65536)
    }
    override fun toString() = "PostReadGrant(<redacted>)"
}

/** REQUIRED content/feature integration, with no accepting default and no production fixture.
 * The reader independently enforces actual account, audience, blocks, status and placement.
 * This callback must use ONLY the caller's transaction: lock/revalidate current moderation,
 * recall, exact attachment/provenance/redistribution and immutable media safety grants, then
 * return their earliest deadline. READY or old publication evidence is not a current grant.
 * Derive actions and reaction counts from actual implemented/current authority; never use
 * stored publication capabilities/counts. Make Mine/save/remix need independent reviewed
 * recipe/current copy rights. A private saved/cooking pin alone grants no redistribution.
 * No network I/O, hidden transaction, commit, diagnostics or mutation. Revocation writers
 * must share the acquired fences; use NOWAIT for locks that would reverse existing ordering.
 * Ordinary hidden/recalled content uses POST_UNAVAILABLE; missing integration/provider or
 * storage faults use NOT_CONFIGURED/STORAGE_UNAVAILABLE, not a false empty feed.
 * This interface is an integration requirement, not completed production content authority. */
fun interface PostReadContentAuthority {
    fun authorize(connection: Connection, actor: VerifiedSocialAccount, material: PostReadMaterial, surface: String): PostReadGrant
    /** Only a single getPost response may request the bounded author actor list. The
     * placement surface can still be today/plate; it is not a page/detail discriminator. */
    fun authorizeSinglePost(connection: Connection, actor: VerifiedSocialAccount, material: PostReadMaterial, surface: String): PostReadGrant =
        authorize(connection, actor, material, surface)
    /** Explicit source-only mode; never recursively discovers request/action capabilities. */
    fun authorizeRecipeRequestSource(connection: Connection, actor: VerifiedSocialAccount, material: PostReadMaterial, surface: String): PostReadGrant =
        throw PostReadFailure(PostReadFailureCode.NOT_CONFIGURED)
    /** Same current content/safety checks, without discovering unrelated write actions. */
    fun authorizeMediaAccess(connection: Connection, actor: VerifiedSocialAccount, material: PostReadMaterial, surface: String): PostReadGrant =
        throw PostReadFailure(PostReadFailureCode.NOT_CONFIGURED)
    /** Explicit sessionless, source-only worker authority. Caller proves both actual
     * provider accounts, current profiles/audience/placement and rechecks final time.
     * Never supplies a synthetic session or discovers user actions. */
    fun authorizeReactionNotification(connection: Connection, material: PostReadMaterial): Instant =
        throw PostReadFailure(PostReadFailureCode.NOT_CONFIGURED)
}

internal class PostRecipeRequestSource(val ownerId: UUID, val postId: UUID, val version: Long,
    val body: JsonObject, val validUntil: Instant) {
    override fun toString() = "PostRecipeRequestSource(<redacted>)"
}

internal val POST_CAPABILITIES = setOf("view", "react", "reply", "askRecipe", "makeMine", "saveRecipe", "edit", "delete", "report", "remix")
