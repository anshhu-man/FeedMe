package com.feedme.server.memory

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.catalog.*
import com.feedme.server.social.VerifiedSocialAccount
import com.feedme.server.social.posts.*
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.json.*

/** Positive source consent AND independent catalog copying rights. A capability displayed
 * by an earlier Post response is never admission. No image, caption, audience or private
 * Plan is copied; retained receipts no longer depend on the author's future-save policy. */
internal class AccountPostRecipeCopyAuthority(private val environment: String,
    private val sources: AccountPostRecipeSourceStore, private val rights: RecipeCopyRightsStore,
    private val disclosureVersion: String) {
    init { require(rights.environment == environment && disclosureVersion.isNotBlank() && disclosureVersion.length <= 256) }

    fun open(c: Connection, subject: VerifiedSupabaseSubject, device: UUID, postId: UUID,
        recipeId: UUID, policyVersion: Long): Grant = safe {
        val source = sources.requireSource(c, subject, device, postId)
        if (!allows(source.savePolicy) || source.savePolicy["policyVersion"] != JsonPrimitive(policyVersion) ||
            source.recipe["id"] != JsonPrimitive(recipeId.toString())) fail(SavedRecipeFailureCode.FORBIDDEN)
        val right = rights.openNew(c, recipeId, guest = false)
        requireAccountSavedMaterial(right.source.entry, source.recipe, false)
        val label = source.post.getValue("author").jsonObject.getValue("displayName").jsonPrimitive.content
        if (label.isBlank() || label.length > 256) fail(SavedRecipeFailureCode.RECIPE_UNAVAILABLE)
        val grantId = UUID.randomUUID()
        val at = now(c)
        val evidence = buildJsonObject {
            put("formatVersion", 2); put("grant", right.evidence); put("recipeHash", accountSavedRecipeHash(source.recipe))
            put("postGrant", buildJsonObject {
                put("id", grantId.toString()); put("postId", postId.toString()); put("postVersion", source.postVersion)
                put("authorId", source.ownerId.toString()); put("creatorLabel", label)
                put("policyVersion", policyVersion); put("disclosureVersion", disclosureVersion)
                put("authorizedAt", at.toString()); put("rights", "PRIVATE_RECIPE_COPY")
            })
        }
        Grant(c, source.recipe, right, source.validUntil, AuthorizedRecipeCopy(recipeId, source.recipe,
            "privateCopyOnly", evidence, AuthorizedPostCopySource(postId, grantId, label))) {
            val actual = sources.requireSource(c, subject, device, postId, source.postVersion)
            if (actual.recipe != source.recipe || actual.attachment != source.attachment || actual.savePolicy != source.savePolicy ||
                actual.ownerId != source.ownerId || !allows(actual.savePolicy)) fail(SavedRecipeFailureCode.FORBIDDEN)
        }.also { it.revalidate(); it.checkAt(now(c)) }
    }

    /** Caller already holds actual reader/Post/source locks. No nested source read or
     * capability recursion. Missing infrastructure is not silently converted into denial. */
    fun canSave(c: Connection, actor: VerifiedSocialAccount, material: PostReadMaterial): Boolean = safe {
        if (actor.environment != environment) fail(SavedRecipeFailureCode.UNAUTHENTICATED)
        val recipe = material.recipeSnapshot ?: return@safe false
        if (!allows(material.savePolicy)) return@safe false
        if (!sources.canMakeMine(c, actor, material)) return@safe false
        try {
            val grant = rights.openNew(c, UUID.fromString(recipe.getValue("id").jsonPrimitive.content), false)
            requireAccountSavedMaterial(grant.source.entry, recipe, false)
            grant.revalidate(c); grant.checkAt(c, now(c)); true
        } catch (failure: RecipeCopyRightsFailure) {
            if (failure.code in setOf(RecipeCopyRightsFailureCode.NOT_ALLOWED, RecipeCopyRightsFailureCode.EXPIRED,
                    RecipeCopyRightsFailureCode.RECALLED, RecipeCopyRightsFailureCode.AUTHORITY_DENIED)) false else throw failure
        }
    }
    private fun allows(policy: JsonObject) = policy["allowFutureSaves"] == JsonPrimitive(true) &&
        policy["disclosureVersion"] == JsonPrimitive(disclosureVersion) &&
        (policy["policyVersion"]?.jsonPrimitive?.longOrNull ?: 0) > 0

    internal class Grant(private val connection: Connection, private val recipe: JsonObject,
        private val rights: RecipeCopyRightsHandle, private val deadline: Instant,
        val copy: AuthorizedRecipeCopy, private val sourceCheck: () -> Unit) {
        fun revalidate() = safe {
            sourceCheck(); rights.revalidate(connection)
            requireAccountSavedMaterial(rights.source.entry, recipe, false)
        }
        fun checkAt(at: Instant) = safe {
            if (!at.isBefore(deadline)) fail(SavedRecipeFailureCode.RECIPE_UNAVAILABLE)
            rights.checkAt(connection, at)
        }
    }
    override fun toString() = "AccountPostRecipeCopyAuthority(<redacted>)"
    companion object {
        private fun now(c: Connection): Instant = c.createStatement().use { s -> s.executeQuery("SELECT clock_timestamp()").use {
            check(it.next()); it.getObject(1, OffsetDateTime::class.java).toInstant()
        } }
        private fun fail(code: SavedRecipeFailureCode): Nothing = throw SavedRecipeFailure(code)
        private fun <T> safe(action: () -> T): T = try { action() }
        catch (f: PostReadFailure) { fail(when (f.code) {
            PostReadFailureCode.UNAUTHENTICATED -> SavedRecipeFailureCode.UNAUTHENTICATED
            PostReadFailureCode.NOT_CONFIGURED -> SavedRecipeFailureCode.NOT_CONFIGURED
            PostReadFailureCode.STORAGE_UNAVAILABLE -> SavedRecipeFailureCode.STORAGE_UNAVAILABLE
            else -> SavedRecipeFailureCode.RECIPE_UNAVAILABLE
        }) }
        catch (f: RecipeCopyRightsFailure) { fail(when (f.code) {
            RecipeCopyRightsFailureCode.NOT_CONFIGURED -> SavedRecipeFailureCode.NOT_CONFIGURED
            RecipeCopyRightsFailureCode.RECALLED -> SavedRecipeFailureCode.RECIPE_RECALLED
            RecipeCopyRightsFailureCode.NOT_ALLOWED, RecipeCopyRightsFailureCode.EXPIRED,
            RecipeCopyRightsFailureCode.AUTHORITY_DENIED -> SavedRecipeFailureCode.RECIPE_UNAVAILABLE
            else -> SavedRecipeFailureCode.STORAGE_UNAVAILABLE
        }) }
    }
}
