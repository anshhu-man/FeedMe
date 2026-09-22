package com.feedme.server.social.posts

import com.feedme.server.social.VerifiedSocialAccount
import com.feedme.server.social.reciperequests.RecipeRequestEligibility
import com.feedme.server.social.reciperequests.RecipeRequestFailure
import com.feedme.server.social.reciperequests.RecipeRequestFailureCode
import com.feedme.server.social.conversations.AccountConversationStore
import com.feedme.server.social.conversations.ConversationFailure
import com.feedme.server.social.conversations.ConversationFailureCode
import com.feedme.server.memory.AccountPostRecipeCopyAuthority
import com.feedme.server.memory.SavedRecipeFailure
import com.feedme.server.memory.SavedRecipeFailureCode
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** Current post moderation plus explicitly composed attachment/media authority.
 * AccountPostReadStore already locks the current post, placement, author, membership and
 * reciprocal blocks. F21 does not require a new human approval for each published post.
 * Existing moderation decisions additionally deny visibility. A SHARE table fence protects
 * even absent decisions from concurrent insertion/update until the caller commits.
 *
 * Current restricted serving grants cannot acquire that fence: startup must reject this
 * optional feature until narrowly scoped serving authority is supplied separately. Never
 * grant this reader moderation mutation rights or switch to an administrator as a fallback.
 * Without the optional actual content/safety dependencies only metadata-only posts can be
 * read. A configured dependency does not itself make Supabase media publishable. Reactions
 * and copying remain separately authorized operations, never inferred from old capabilities.
 */
internal class PostgresPostReadContentAuthority(private val environment: String,
    private val content: AccountPostContentAuthority? = null,
    private val mediaSafety: PostReadMediaSafety? = null,
    private val conversations: AccountConversationStore? = null,
    private val deletion: AccountPostDeletionStore? = null,
    private val recipeRequests: RecipeRequestEligibility? = null,
    private val postRecipes: (() -> AccountPostRecipeSourceStore?)? = null,
    private val postCopies: (() -> AccountPostRecipeCopyAuthority?)? = null,
    private val placement: (() -> AccountPostPlacementStore?)? = null,
    private val reactions: (() -> AccountPostReactionStore?)? = null,
) : PostReadContentAuthority {
    override fun authorize(connection: Connection, actor: VerifiedSocialAccount,
        material: PostReadMaterial, surface: String): PostReadGrant =
        authorizeContent(connection, actor, material, surface, discoverActions = true)

    override fun authorizeSinglePost(connection: Connection, actor: VerifiedSocialAccount,
        material: PostReadMaterial, surface: String): PostReadGrant =
        authorizeContent(connection, actor, material, surface, discoverActions = true, includeReactionActors = true)

    override fun authorizeRecipeRequestSource(connection: Connection, actor: VerifiedSocialAccount,
        material: PostReadMaterial, surface: String): PostReadGrant =
        authorizeContent(connection, actor, material, surface, discoverActions = false)

    override fun authorizeMediaAccess(connection: Connection, actor: VerifiedSocialAccount,
        material: PostReadMaterial, surface: String): PostReadGrant =
        authorizeContent(connection, actor, material, surface, discoverActions = false)

    override fun authorizeReactionNotification(connection: Connection, material: PostReadMaterial): Instant {
        require(!connection.isClosed && !connection.autoCommit &&
            connection.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
        val mediaDeadline = verifySource(connection, material)
        val now = databaseTime(connection)
        if (mediaDeadline != null && !now.isBefore(mediaDeadline))
            throw PostReadFailure(PostReadFailureCode.POST_UNAVAILABLE)
        return minOf(now.plusSeconds(60), mediaDeadline ?: Instant.MAX)
    }

    private fun authorizeContent(connection: Connection, actor: VerifiedSocialAccount,
        material: PostReadMaterial, surface: String, discoverActions: Boolean, includeReactionActors: Boolean = false): PostReadGrant {
        require(!connection.isClosed && !connection.autoCommit &&
            connection.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
        if (actor.environment != environment || actor.providerSubject == null)
            throw PostReadFailure(PostReadFailureCode.UNAUTHENTICATED)
        if (surface !in setOf("today", "plate", "detail"))
            throw PostReadFailure(PostReadFailureCode.INPUT_INVALID)
        val mediaDeadline = verifySource(connection, material)
        return discover(connection, actor, material, discoverActions, includeReactionActors, mediaDeadline)
    }

    private fun verifySource(connection: Connection, material: PostReadMaterial): Instant? {
        if (material.post["status"] != JsonPrimitive("published"))
            throw PostReadFailure(PostReadFailureCode.POST_UNAVAILABLE)
        lockModeration(connection)
        connection.prepareStatement("SELECT EXISTS(SELECT 1 FROM safety.moderation_cases " +
            "WHERE environment=? AND action IN ('hide','remove','suspend') AND " +
            "((target_type='post' AND target_id=?) OR (target_type='user' AND target_id=?)))").use { statement ->
            statement.setString(1, environment); statement.setObject(2, material.postId)
            statement.setObject(3, material.ownerId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) throw PostReadFailure(PostReadFailureCode.STORAGE_UNAVAILABLE)
                if (rows.getBoolean(1)) throw PostReadFailure(PostReadFailureCode.POST_UNAVAILABLE)
                if (rows.next()) throw PostReadFailure(PostReadFailureCode.STORAGE_UNAVAILABLE)
            }
        }
        if (material.attachment != null || material.recipeSnapshot != null || material.recipeSha256 != null) {
            val attachment = material.attachment ?: throw PostReadFailure(PostReadFailureCode.STORAGE_UNAVAILABLE)
            val snapshot = material.recipeSnapshot ?: throw PostReadFailure(PostReadFailureCode.STORAGE_UNAVAILABLE)
            if (material.recipeSha256 == null) throw PostReadFailure(PostReadFailureCode.STORAGE_UNAVAILABLE)
            val authority = content ?: throw PostReadFailure(PostReadFailureCode.NOT_CONFIGURED)
            // Revalidate exact retained provenance and current catalog redistribution;
            // never treat the stored rightsBasis or an owner's private plan as a grant.
            try { authority.verifyRetainedAttachment(connection, material.ownerId, attachment, snapshot) }
            catch (failure: PostPublicationFailure) {
                throw PostReadFailure(when (failure.code) {
                    PostPublicationFailureCode.NOT_CONFIGURED -> PostReadFailureCode.NOT_CONFIGURED
                    PostPublicationFailureCode.STORAGE_UNAVAILABLE -> PostReadFailureCode.STORAGE_UNAVAILABLE
                    else -> PostReadFailureCode.POST_UNAVAILABLE
                }).also { mapped -> failure.suppressed.forEach(mapped::addSuppressed) }
            }
        }
        return if (material.media.isEmpty()) null else
            (mediaSafety ?: throw PostReadFailure(PostReadFailureCode.NOT_CONFIGURED)).requireCurrent(connection, material)
    }

    private fun discover(connection: Connection, actor: VerifiedSocialAccount, material: PostReadMaterial,
        discoverActions: Boolean, includeReactionActors: Boolean, mediaDeadline: Instant?): PostReadGrant {
        val replyAllowed = try { discoverActions && conversations?.canStartDirect(connection, actor, material.ownerId) == true }
        catch (failure: ConversationFailure) {
            throw PostReadFailure(when (failure.code) {
                ConversationFailureCode.UNAUTHENTICATED -> PostReadFailureCode.UNAUTHENTICATED
                ConversationFailureCode.NOT_CONFIGURED -> PostReadFailureCode.NOT_CONFIGURED
                else -> PostReadFailureCode.STORAGE_UNAVAILABLE
            }).also { mapped -> failure.suppressed.forEach(mapped::addSuppressed) }
        }
        val deletionAllowed = try { discoverActions && deletion?.canDelete(connection, actor, material) == true }
        catch (failure: PostDeletionFailure) {
            throw PostReadFailure(when (failure.code) {
                PostDeletionFailureCode.UNAUTHENTICATED -> PostReadFailureCode.UNAUTHENTICATED
                PostDeletionFailureCode.NOT_CONFIGURED -> PostReadFailureCode.NOT_CONFIGURED
                else -> PostReadFailureCode.STORAGE_UNAVAILABLE
            }).also { mapped -> failure.suppressed.forEach(mapped::addSuppressed) }
        }
        val placementAllowed = try { discoverActions && placement?.invoke()?.canChangePlacement(connection, actor, material) == true }
        catch (failure: PostPlacementFailure) {
            throw PostReadFailure(when (failure.code) {
                PostPlacementFailureCode.UNAUTHENTICATED -> PostReadFailureCode.UNAUTHENTICATED
                PostPlacementFailureCode.NOT_CONFIGURED -> PostReadFailureCode.NOT_CONFIGURED
                PostPlacementFailureCode.POST_UNAVAILABLE -> PostReadFailureCode.POST_UNAVAILABLE
                else -> PostReadFailureCode.STORAGE_UNAVAILABLE
            }).also { mapped -> failure.suppressed.forEach(mapped::addSuppressed) }
        }
        val requestAllowed = try { discoverActions && recipeRequests?.canRequest(connection, actor, material) == true }
        catch (failure: RecipeRequestFailure) {
            throw PostReadFailure(when (failure.code) {
                RecipeRequestFailureCode.UNAUTHENTICATED -> PostReadFailureCode.UNAUTHENTICATED
                RecipeRequestFailureCode.NOT_CONFIGURED -> PostReadFailureCode.NOT_CONFIGURED
                else -> PostReadFailureCode.STORAGE_UNAVAILABLE
            }).also { mapped -> failure.suppressed.forEach(mapped::addSuppressed) }
        }
        val makeMineAllowed = discoverActions && postRecipes?.invoke()?.canMakeMine(connection, actor, material) == true
        val saveAllowed = try { discoverActions && postCopies?.invoke()?.canSave(connection, actor, material) == true }
        catch (failure: SavedRecipeFailure) {
            throw PostReadFailure(when (failure.code) {
                SavedRecipeFailureCode.UNAUTHENTICATED -> PostReadFailureCode.UNAUTHENTICATED
                SavedRecipeFailureCode.NOT_CONFIGURED -> PostReadFailureCode.NOT_CONFIGURED
                SavedRecipeFailureCode.RECIPE_UNAVAILABLE, SavedRecipeFailureCode.RECIPE_RECALLED,
                SavedRecipeFailureCode.FORBIDDEN -> PostReadFailureCode.POST_UNAVAILABLE
                else -> PostReadFailureCode.STORAGE_UNAVAILABLE
            }).also { mapped -> failure.suppressed.forEach(mapped::addSuppressed) }
        }
        val reactionState = try { if (discoverActions) reactions?.invoke()?.project(connection, actor, material, includeReactionActors) else null }
        catch (failure: PostReactionFailure) {
            throw PostReadFailure(when (failure.code) {
                PostReactionFailureCode.UNAUTHENTICATED -> PostReadFailureCode.UNAUTHENTICATED
                PostReactionFailureCode.NOT_CONFIGURED -> PostReadFailureCode.NOT_CONFIGURED
                PostReactionFailureCode.POST_UNAVAILABLE -> PostReadFailureCode.POST_UNAVAILABLE
                else -> PostReadFailureCode.STORAGE_UNAVAILABLE
            }).also { mapped -> failure.suppressed.forEach(mapped::addSuppressed) }
        }
        val now = databaseTime(connection)
        val expires = Instant.ofEpochSecond(checkNotNull(actor.providerSubject).expiresAtEpochSeconds)
        if (!now.isBefore(expires)) throw PostReadFailure(PostReadFailureCode.UNAUTHENTICATED)
        if (mediaDeadline != null && !now.isBefore(mediaDeadline)) throw PostReadFailure(PostReadFailureCode.POST_UNAVAILABLE)
        val capabilities = buildSet {
            add("view")
            if (replyAllowed) add("reply")
            if (deletionAllowed) add("delete")
            if (placementAllowed) add("edit")
            if (reactionState?.canSet == true) add("react")
            if (requestAllowed) add("askRecipe")
            if (makeMineAllowed) add("makeMine")
            if (saveAllowed) add("saveRecipe")
        }
        return PostReadGrant(capabilities, reactionState?.counts ?: JsonArray(emptyList()),
            minOf(now.plusSeconds(60), expires, mediaDeadline ?: Instant.MAX), reactionState?.own, reactionState?.actors, reactionState?.hasMore)
    }

    private fun databaseTime(connection: Connection): Instant = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT clock_timestamp()").use { rows ->
                check(rows.next()); rows.getObject(1, OffsetDateTime::class.java).toInstant()
            }
        }

    override fun toString() = "PostgresPostReadContentAuthority(<redacted>)"

    companion object {
        internal fun lockModeration(connection: Connection) {
            try {
                connection.createStatement().use {
                    it.execute("LOCK TABLE ONLY safety.moderation_cases IN SHARE MODE NOWAIT")
                }
            } catch (failure: SQLException) {
                if (failure.sqlState == "55P03") throw SQLException("Post moderation fence is contended", "40001").also {
                    failure.suppressed.forEach(it::addSuppressed)
                }
                throw failure
            }
        }
    }
}
