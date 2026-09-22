package com.feedme.server.social.reciperequests

import com.feedme.server.social.*
import com.feedme.server.social.conversations.*
import com.feedme.server.social.posts.PostReadMaterial
import java.sql.Connection

class AccountRecipeRequestPolicy(val enabled: Boolean, val lifetimeSeconds: Int,
    val maxRequestsPer24Hours: Int, val maxResponseBytes: Int) {
    init { require(lifetimeSeconds in 1..604800 && maxRequestsPer24Hours in 1..100 && maxResponseBytes in 1024..262144) }
    override fun toString() = "AccountRecipeRequestPolicy(<redacted>)"
}
enum class RecipeRequestFailureCode(val status: Int) {
    INPUT_INVALID(422), UNAUTHENTICATED(401), REQUEST_UNAVAILABLE(404), SOURCE_UNAVAILABLE(404),
    RECIPIENT_UNAVAILABLE(403), REQUEST_ALREADY_PENDING(409), REQUEST_CLOSED(409), VERSION_CONFLICT(412), RATE_LIMITED(429),
    NOT_CONFIGURED(503), STORAGE_UNAVAILABLE(503),
}
class RecipeRequestFailure(val code: RecipeRequestFailureCode) : RuntimeException("Recipe request unavailable: ${code.name}")

/** Capability projection AFTER the caller has authorized the real source. It never reads
 * the post recursively, grants copying, or substitutes a caller-supplied author identity. */
internal class RecipeRequestEligibility(private val environment: String, private val conversations: AccountConversationStore,
    private val policy: AccountRecipeRequestPolicy) {
    fun canRequest(c: Connection, actor: VerifiedSocialAccount, material: PostReadMaterial): Boolean {
        if (!policy.enabled || actor.environment != environment || actor.accountId == material.ownerId || material.attachment != null) return false
        return try {
            conversations.requireRecipeParticipants(c, actor, actor.accountId, material.ownerId, delivery = true)
            c.prepareStatement("SELECT 1 FROM social.recipe_requests WHERE environment=? AND requester_user_id=? AND post_id=? AND status='pending' AND expires_at>clock_timestamp()").use {
                it.setString(1, environment); it.setObject(2, actor.accountId); it.setObject(3, material.postId); it.executeQuery().use { rows -> !rows.next() }
            }
        } catch (failure: ConversationFailure) {
            if (failure.code in setOf(ConversationFailureCode.CONTACT_UNAVAILABLE, ConversationFailureCode.THREAD_UNAVAILABLE)) false
            else throw RecipeRequestFailure(if (failure.code == ConversationFailureCode.NOT_CONFIGURED) RecipeRequestFailureCode.NOT_CONFIGURED else RecipeRequestFailureCode.STORAGE_UNAVAILABLE)
        } catch (failure: SocialFailure) {
            if (failure.code == SocialFailureCode.CIRCLE_UNAVAILABLE) false else throw RecipeRequestFailure(when (failure.code) {
                SocialFailureCode.UNAUTHENTICATED -> RecipeRequestFailureCode.UNAUTHENTICATED
                SocialFailureCode.NOT_CONFIGURED -> RecipeRequestFailureCode.NOT_CONFIGURED
                else -> RecipeRequestFailureCode.STORAGE_UNAVAILABLE
            })
        }
    }
}
