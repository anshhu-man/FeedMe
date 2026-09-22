package com.feedme.server.memory

import com.feedme.server.catalog.*
import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.cooking.AccountMealAccess
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.CommandActor
import com.feedme.server.identity.AccountFailure
import com.feedme.server.identity.AccountFailureCode
import com.feedme.server.planning.PlansStore
import com.feedme.server.planning.VerifiedPlanningPrincipal
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

/** Real account mapping plus independently persisted positive copy rights, in one owned
 * transaction. Format1 or genuinely authorized format3/4 own-Plan material, or direct catalog
 * copies only; no guest manifests. Derived provenance never substitutes for copy permission.
 * Plan age/current private preferences do not grant or revoke the independent copying right. */
internal class AccountSavedRecipeAccess(private val environment: String, private val connection: Connection,
    private val account: AccountMealAccess, device: UUID, private val rights: RecipeCopyRightsStore,
    private val newCopiesEnabled: Boolean, private val planning: PlansStore? = null,
    private val planningActor: VerifiedPlanningPrincipal? = null,
    private val postCopies: AccountPostRecipeCopyAuthority? = null,
    private val subject: VerifiedSupabaseSubject? = null) : SavedRecipeAuthority {
    val principal = VerifiedSavedRecipePrincipal(environment, CommandActor.ACCOUNT, account.principalId, device)
    private var fresh: Fresh? = null
    private var postFresh: AccountPostRecipeCopyAuthority.Grant? = null
    private val retained = linkedMapOf<UUID, Retained>()
    init {
        require(rights.environment == environment)
        require((planning == null) == (planningActor == null))
        require(planningActor == null || planningActor.environment == environment && planningActor.kind == CommandActor.ACCOUNT &&
            planningActor.principalId == account.principalId && planningActor.deviceSessionId == device)
    }
    override fun lockPrincipal(connection: Connection, principal: VerifiedSavedRecipePrincipal) {
        if (principal !== this.principal || connection !== this.connection) fail(SavedRecipeFailureCode.UNAUTHENTICATED)
        try { account.current(connection) } catch (f: AccountFailure) { fail(when (f.code) {
            AccountFailureCode.UNAUTHENTICATED, AccountFailureCode.ACCOUNT_UNAVAILABLE -> SavedRecipeFailureCode.UNAUTHENTICATED
            AccountFailureCode.POLICY_BLOCKED -> SavedRecipeFailureCode.FORBIDDEN
            AccountFailureCode.NOT_CONFIGURED -> SavedRecipeFailureCode.NOT_CONFIGURED
            else -> SavedRecipeFailureCode.STORAGE_UNAVAILABLE
        }) }
    }
    override fun authorizeNewCopy(connection: Connection, principal: VerifiedSavedRecipePrincipal,
        planId: UUID?, recipeVersionId: UUID?): AuthorizedRecipeCopy {
        lockPrincipal(connection, principal)
        if (!newCopiesEnabled) fail(SavedRecipeFailureCode.NOT_CONFIGURED)
        if (fresh != null || planId == null && recipeVersionId == null) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
        val plan = planId?.let(::ownedRecipe)
        val id = plan?.getValue("id")?.jsonPrimitive?.content?.let(UUID::fromString) ?: recipeVersionId!!
        if (recipeVersionId != null && id != recipeVersionId) fail(SavedRecipeFailureCode.RECIPE_UNAVAILABLE)
        val grant = rights { rights.openNew(connection, id, guest = false) }
        val recipe = plan ?: grant.source.entry.recipe
        requireAccountSavedMaterial(grant.source.entry, recipe, grant.allowReviewedScaling && planId != null)
        fresh = Fresh(planId, recipe, grant)
        revalidate()
        return AuthorizedRecipeCopy(id, recipe, grant.contentLicense, buildJsonObject {
            put("formatVersion", 1); put("grant", grant.evidence); put("recipeHash", accountSavedRecipeHash(recipe))
        })
    }
    override fun requireExistingCopyAllowed(connection: Connection, principal: VerifiedSavedRecipePrincipal, copy: SavedRecipeCopyEvidence) {
        lockPrincipal(connection, principal)
        val old = retained[copy.savedRecipeId]
        if (old != null) {
            if (identity(old.copy) != identity(copy)) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
            checkRetained(old)
        } else {
            val post = copy.sourceType == "postGrant"
            if (copy.evidence.keys != (setOf("formatVersion", "grant", "recipeHash") + if (post) setOf("postGrant") else emptySet()) ||
                copy.evidence["formatVersion"] != JsonPrimitive(if (post) 2 else 1) ||
                copy.evidence["recipeHash"] != JsonPrimitive(copy.recipeHash)) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
            val binding = Retained(copy, rights { rights.openExisting(connection, copy.evidence.getValue("grant").jsonObject, guest = false) })
            checkRetained(binding); retained[copy.savedRecipeId] = binding
        }
    }
    override fun authorizePostCopy(connection: Connection, principal: VerifiedSavedRecipePrincipal,
        postId: UUID, recipeVersionId: UUID, grantPolicyVersion: Long): AuthorizedRecipeCopy {
        lockPrincipal(connection, principal)
        if (!newCopiesEnabled) fail(SavedRecipeFailureCode.NOT_CONFIGURED)
        if (fresh != null || postFresh != null) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
        return (postCopies ?: fail(SavedRecipeFailureCode.NOT_CONFIGURED)).open(connection,
            subject ?: fail(SavedRecipeFailureCode.NOT_CONFIGURED), principal.deviceSessionId!!, postId, recipeVersionId,
            grantPolicyVersion).also { postFresh = it }.copy
    }
    fun revalidate() {
        account.current(connection)
        fresh?.let { f ->
            rights { f.grant.revalidate(connection) }
            if (f.planId != null && ownedRecipe(f.planId) != f.recipe) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
            requireAccountSavedMaterial(f.grant.source.entry, f.recipe, f.grant.allowReviewedScaling && f.planId != null)
        }
        retained.values.forEach(::checkRetained)
        postFresh?.revalidate()
    }
    fun checkAt(at: Instant) {
        fresh?.let { rights { it.grant.checkAt(connection, at) } }
        retained.values.forEach { rights { it.grant.checkAt(connection, at) } }
        postFresh?.checkAt(at)
    }
    /** Exact account-owned format3/4 material only, NOT permission to acquire a copy.
     * The kernel compares this to its own immutable row pin before separately invoking
     * authorizeNewCopy, which must still acquire actual new-copy/scaling rights. */
    fun lockOwnedDerivedPlanRecipe(connection: Connection, principal: VerifiedSavedRecipePrincipal, id: UUID): JsonObject {
        lockPrincipal(connection, principal)
        return (planning ?: fail(SavedRecipeFailureCode.NOT_CONFIGURED))
            .lockOwnedDerivedCopy(connection, checkNotNull(planningActor), id)
    }
    private fun ownedRecipe(id: UUID): JsonObject = connection.prepareStatement(
        "SELECT snapshot_text,snapshot_hash,status,recipe_version_id,storage_format FROM planning.plans " +
            "WHERE environment=? AND actor_kind='account' AND principal_id=? AND id=? FOR SHARE").use { s ->
        s.setString(1, environment); s.setObject(2, principal.principalId); s.setObject(3, id)
        s.executeQuery().use { r ->
            if (!r.next()) fail(SavedRecipeFailureCode.PLAN_UNAVAILABLE)
            if (r.getInt(5) in setOf(3, 4, 5)) {
                if (r.next()) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
                return@use lockOwnedDerivedPlanRecipe(connection, principal, id)
            }
            if (r.getInt(5) != 1) fail(SavedRecipeFailureCode.NOT_CONFIGURED)
            val text = r.getString(1)
            if (accountSavedSha(text) != r.getString(2) || validator.validateResponse("getPlan", 200,
                    text.encodeToByteArray(throwOnInvalidSequence = true), "application/json") != BodyValidationResult.Valid)
                fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
            val plan = Json.parseToJsonElement(text).jsonObject
            if (plan["id"] != JsonPrimitive(id.toString()) || r.getString(3) != "ready" || plan["status"] != JsonPrimitive("ready"))
                fail(SavedRecipeFailureCode.RECIPE_UNAVAILABLE)
            val recipe = plan.getValue("recipeSnapshot").jsonObject
            if (recipe["id"] != JsonPrimitive(r.getObject(4, UUID::class.java).toString()) || r.next())
                fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
            recipe
        }
    }
    private fun checkRetained(binding: Retained) {
        val copy = binding.copy; val grant = binding.grant
        rights { grant.revalidate(connection) }
        val post = copy.sourceType == "postGrant"
        if (copy.recipeVersionId != grant.recipeVersionId || copy.contentLicense != (if (post) "privateCopyOnly" else grant.contentLicense) ||
            copy.sourceType !in setOf("catalog", "ownPlan", "postGrant") || (copy.sourceType == "ownPlan") != (copy.originPlanId != null))
            fail(SavedRecipeFailureCode.RECIPE_UNAVAILABLE)
        val recipe = connection.prepareStatement("SELECT snapshot,recipe_hash,copy_evidence,source_type,origin_plan_id,content_license,recipe_version_id,source_id " +
            "FROM memory.saved_recipes WHERE environment=? AND actor_kind='account' AND principal_id=? AND id=? AND NOT deleted FOR SHARE").use { s ->
            s.setString(1, environment); s.setObject(2, principal.principalId); s.setObject(3, copy.savedRecipeId)
            s.executeQuery().use { r ->
                if (!r.next()) fail(SavedRecipeFailureCode.SAVED_RECIPE_UNAVAILABLE)
                if (r.getString(2) != copy.recipeHash || Json.parseToJsonElement(r.getString(3)) != copy.evidence ||
                    r.getString(4) != copy.sourceType || r.getObject(5, UUID::class.java) != copy.originPlanId ||
                    r.getString(6) != copy.contentLicense || r.getObject(7, UUID::class.java) != copy.recipeVersionId)
                    fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
                val snapshot = Json.parseToJsonElement(r.getString(1)).jsonObject
                if (post) requirePostGrant(copy, snapshot, r.getObject(8, UUID::class.java))
                snapshot.getValue("snapshot").jsonObject.also { if (r.next()) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE) }
            }
        }
        if (accountSavedRecipeHash(recipe) != copy.recipeHash) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
        requireAccountSavedMaterial(grant.source.entry, recipe, grant.allowReviewedScaling && copy.sourceType == "ownPlan")
    }
    private fun requirePostGrant(copy: SavedRecipeCopyEvidence, snapshot: JsonObject, sourceId: UUID) {
        val grant = copy.evidence["postGrant"] as? JsonObject ?: fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
        if (grant.keys != setOf("id", "postId", "postVersion", "authorId", "creatorLabel", "policyVersion", "disclosureVersion", "authorizedAt", "rights") ||
            grant["postId"] != JsonPrimitive(sourceId.toString()) || snapshot["sourcePostId"] != grant["postId"] ||
            snapshot["grantId"] != grant["id"] || snapshot["creatorLabel"] != grant["creatorLabel"] ||
            grant["rights"] != JsonPrimitive("PRIVATE_RECIPE_COPY") ||
            (grant["policyVersion"]?.jsonPrimitive?.longOrNull ?: 0) < 1 ||
            (grant["postVersion"]?.jsonPrimitive?.longOrNull ?: 0) < 1)
            fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
        try { UUID.fromString(grant.getValue("id").jsonPrimitive.content); UUID.fromString(grant.getValue("authorId").jsonPrimitive.content)
            Instant.parse(grant.getValue("authorizedAt").jsonPrimitive.content)
        } catch (_: Exception) { fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE) }
        // Deliberately no source-post, author status, future-save policy or membership GET:
        // this exact private copy survives ordinary source removal/revocation.
    }
    private fun identity(c: SavedRecipeCopyEvidence) = listOf(c.savedRecipeId, c.recipeVersionId, c.recipeHash, c.sourceType, c.originPlanId, c.contentLicense, c.evidence)
    private class Fresh(val planId: UUID?, val recipe: JsonObject, val grant: RecipeCopyRightsHandle)
    private class Retained(val copy: SavedRecipeCopyEvidence, val grant: RecipeCopyRightsHandle)
    private fun <T> rights(action: () -> T): T = try { action() } catch (f: RecipeCopyRightsFailure) { fail(when (f.code) {
        RecipeCopyRightsFailureCode.NOT_CONFIGURED -> SavedRecipeFailureCode.NOT_CONFIGURED
        RecipeCopyRightsFailureCode.RECALLED -> SavedRecipeFailureCode.RECIPE_RECALLED
        RecipeCopyRightsFailureCode.AUTHORITY_DENIED, RecipeCopyRightsFailureCode.NOT_ALLOWED, RecipeCopyRightsFailureCode.EXPIRED -> SavedRecipeFailureCode.RECIPE_UNAVAILABLE
        else -> SavedRecipeFailureCode.STORAGE_UNAVAILABLE
    }) }
    private fun fail(code: SavedRecipeFailureCode): Nothing = throw SavedRecipeFailure(code)
    override fun toString() = "AccountSavedRecipeAccess(<redacted>)"
    companion object { private val validator by lazy { ContractBodyValidator.bundled() } }
}
