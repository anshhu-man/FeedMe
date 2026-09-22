package com.feedme.server.social.posts

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.catalog.RecipeCatalogJournal
import com.feedme.server.catalog.RecipeCatalogVersion
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.PgTransactions
import com.feedme.server.db.StoredReply
import com.feedme.server.identity.AccountProfileStore
import com.feedme.server.identity.AccountFailure
import com.feedme.server.identity.AccountFailureCode
import com.feedme.server.catalog.RecipeCatalogFailure
import com.feedme.server.catalog.RecipeCatalogFailureCode
import com.feedme.server.social.VerifiedSocialAccount
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.json.*

/** Explicit current source read. Neither this observation nor its provenance grants a
 * saved copy. Save consent/copy licensing is a distinct transaction-owned operation. */
internal class AccountPostRecipeSourceStore(private val environment: String,
    private val transactions: PgTransactions, private val accounts: AccountProfileStore,
    private val postReads: AccountPostReadStore, private val catalog: RecipeCatalogJournal,
    private val provider: com.feedme.server.identity.SupabasePostgresAuthority) {
    init { require(accounts.environment == environment && postReads.environment == environment && catalog.environment == environment) }

    fun getSource(subject: VerifiedSupabaseSubject, device: UUID, postId: UUID): StoredReply = sourceSafe { transactions.run { c ->
        val source = requireSource(c, subject, device, postId)
        accounts.lockPrivateAccount(c, subject, device)
        val at = now(c)
        if (at >= source.validUntil) unavailable()
        val body = buildJsonObject {
            put("postId", source.postId.toString()); put("postVersion", source.postVersion)
            put("recipeSnapshot", source.recipe); put("serverTime", at.toString())
            put("validUntil", minOf(source.validUntil, at.plusSeconds(60)).toString())
        }
        if (body.toString().encodeToByteArray().size > postReads.policy.maxResponseBytes ||
            validator.validateResponse("getPostRecipeSource", 200, body.toString().encodeToByteArray(), "application/json") != BodyValidationResult.Valid)
            throw PostReadFailure(PostReadFailureCode.STORAGE_UNAVAILABLE)
        StoredReply(200, body, "\"${source.postVersion}\"")
    } }

    internal fun requireSource(c: Connection, subject: VerifiedSupabaseSubject, device: UUID, postId: UUID,
        expectedVersion: Long? = null): AccountPostRecipeSource = sourceSafe {
        require(!c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
        accounts.lockPrivateAccount(c, subject, device)
        val actor = VerifiedSocialAccount.resolveSafety(c, accounts, subject, device)
        val (material, deadline) = postReads.requireRecipeMaterial(c, actor, postId)
        if (expectedVersion != null && material.version != expectedVersion) unavailable()
        val source = catalogMaterial(c, material)
        val at = now(c)
        val until = minOf(deadline, provider.lockCurrentValidUntil(c, subject), at.plusSeconds(60))
        if (at >= until) unavailable()
        AccountPostRecipeSource(environment, material, source, until)
    }

    /** Called only after the ordinary reader has authorized material. No reader recursion
     * and no declaration that every visible recipe has an eligible substitution target. */
    internal fun canMakeMine(c: Connection, actor: VerifiedSocialAccount, material: PostReadMaterial): Boolean {
        val subject = actor.providerSubject ?: return false
        if (actor.environment != environment || material.attachment == null) return false
        try { accounts.lockPrivateAccount(c, subject, actor.deviceSessionId) }
        catch (failure: AccountFailure) {
            if (failure.code in setOf(AccountFailureCode.POLICY_BLOCKED, AccountFailureCode.ACCOUNT_UNAVAILABLE) && failure.suppressed.isEmpty()) return false
            throw failure
        }
        return try { sourceSafe { catalogMaterial(c, material) }; true }
        catch (failure: PostReadFailure) { if (failure.code == PostReadFailureCode.POST_UNAVAILABLE && failure.suppressed.isEmpty()) false else throw failure }
    }

    private fun catalogMaterial(c: Connection, material: PostReadMaterial): RecipeCatalogVersion {
        val recipe = material.recipeSnapshot ?: unavailable()
        val attachment = material.attachment ?: unavailable()
        if (attachment["rightsBasis"] != JsonPrimitive("catalogRedistributable") ||
            attachment["reviewStatus"] != JsonPrimitive("reviewed")) unavailable()
        val id = recipe["id"]?.jsonPrimitive?.content?.let(UUID::fromString) ?: unavailable()
        val view = catalog.openView(c)
        val actual = view.lookupCurrent(id) ?: unavailable()
        if (actual.entry.recipe != recipe || actual.entry.status != "published" || actual.entry.recall != null ||
            actual.entry.review["freeCatalogEligible"] != JsonPrimitive(true) ||
            recipe["contentLicense"] != JsonPrimitive("catalogRedistributable") || actual.entry.rightsReference.isBlank()) unavailable()
        view.checkCurrent()
        return actual
    }
    override fun toString() = "AccountPostRecipeSourceStore(<redacted>)"
    private fun <T> sourceSafe(action: () -> T): T = try { action() }
        catch (failure: AccountFailure) {
            throw PostReadFailure(when (failure.code) {
                AccountFailureCode.UNAUTHENTICATED -> PostReadFailureCode.UNAUTHENTICATED
                AccountFailureCode.NOT_CONFIGURED -> PostReadFailureCode.NOT_CONFIGURED
                AccountFailureCode.STORAGE_UNAVAILABLE -> PostReadFailureCode.STORAGE_UNAVAILABLE
                else -> PostReadFailureCode.POST_UNAVAILABLE
            }).also { mapped -> failure.suppressed.forEach(mapped::addSuppressed) }
        } catch (failure: RecipeCatalogFailure) {
            throw PostReadFailure(if (failure.code == RecipeCatalogFailureCode.NOT_CONFIGURED) PostReadFailureCode.NOT_CONFIGURED
                else PostReadFailureCode.STORAGE_UNAVAILABLE).also { mapped -> failure.suppressed.forEach(mapped::addSuppressed) }
        }
    private companion object {
        val validator by lazy { ContractBodyValidator.bundled() }
        fun unavailable(): Nothing = throw PostReadFailure(PostReadFailureCode.POST_UNAVAILABLE)
        fun now(c: Connection): Instant = c.createStatement().use { s -> s.executeQuery("SELECT clock_timestamp()").use { r ->
            check(r.next()); r.getObject(1, OffsetDateTime::class.java).toInstant().also { check(!r.next()) }
        } }
    }
}

internal class AccountPostRecipeSource(val environment: String, material: PostReadMaterial,
    val source: RecipeCatalogVersion, val validUntil: Instant) {
    val ownerId = material.ownerId
    val postId = material.postId
    val postVersion = material.version
    val post = material.post
    val attachment = requireNotNull(material.attachment)
    val recipe = requireNotNull(material.recipeSnapshot)
    val recipeSha256 = requireNotNull(material.recipeSha256)
    val savePolicy = material.savePolicy
    fun evidence(): JsonObject = buildJsonObject {
        put("environment", environment); put("ownerId", ownerId.toString()); put("postId", postId.toString())
        put("postVersion", postVersion); put("attachment", attachment); put("recipeText", recipe.toString())
        put("recipeSha256", recipeSha256)
    }
    override fun toString() = "AccountPostRecipeSource(<redacted>)"
}
