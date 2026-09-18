package com.feedme.server.memory

import com.feedme.server.catalog.*
import com.feedme.server.cooking.AccountMealAccess
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.CommandActor
import com.feedme.server.identity.AccountFailure
import com.feedme.server.identity.AccountFailureCode
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

/** Real account mapping plus independently persisted positive copy rights, in one owned
 * transaction. Format1 own-Plan material or direct catalog copies only; no guest manifests.
 * Plan age/current private preferences do not grant or revoke the independent copying right. */
internal class AccountSavedRecipeAccess(private val environment: String, private val connection: Connection,
    private val account: AccountMealAccess, device: UUID, private val rights: RecipeCopyRightsStore,
    private val newCopiesEnabled: Boolean) : SavedRecipeAuthority {
    val principal = VerifiedSavedRecipePrincipal(environment, CommandActor.ACCOUNT, account.principalId, device)
    private var fresh: Fresh? = null
    private val retained = linkedMapOf<UUID, Retained>()
    init { require(rights.environment == environment) }
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
            if (copy.evidence.keys != setOf("formatVersion", "grant", "recipeHash") || copy.evidence["formatVersion"] != JsonPrimitive(1) ||
                copy.evidence["recipeHash"] != JsonPrimitive(copy.recipeHash)) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
            val binding = Retained(copy, rights { rights.openExisting(connection, copy.evidence.getValue("grant").jsonObject, guest = false) })
            checkRetained(binding); retained[copy.savedRecipeId] = binding
        }
    }
    fun revalidate() {
        account.current(connection)
        fresh?.let { f ->
            rights { f.grant.revalidate(connection) }
            if (f.planId != null && ownedRecipe(f.planId) != f.recipe) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
            requireAccountSavedMaterial(f.grant.source.entry, f.recipe, f.grant.allowReviewedScaling && f.planId != null)
        }
        retained.values.forEach(::checkRetained)
    }
    fun checkAt(at: Instant) {
        fresh?.let { rights { it.grant.checkAt(connection, at) } }
        retained.values.forEach { rights { it.grant.checkAt(connection, at) } }
    }
    private fun ownedRecipe(id: UUID): JsonObject = connection.prepareStatement(
        "SELECT snapshot_text,snapshot_hash,status,recipe_version_id,storage_format FROM planning.plans " +
            "WHERE environment=? AND actor_kind='account' AND principal_id=? AND id=? FOR SHARE").use { s ->
        s.setString(1, environment); s.setObject(2, principal.principalId); s.setObject(3, id)
        s.executeQuery().use { r ->
            if (!r.next()) fail(SavedRecipeFailureCode.PLAN_UNAVAILABLE)
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
        if (copy.recipeVersionId != grant.recipeVersionId || copy.contentLicense != grant.contentLicense ||
            copy.sourceType !in setOf("catalog", "ownPlan") || (copy.sourceType == "ownPlan") != (copy.originPlanId != null))
            fail(SavedRecipeFailureCode.RECIPE_UNAVAILABLE)
        val recipe = connection.prepareStatement("SELECT snapshot,recipe_hash,copy_evidence,source_type,origin_plan_id,content_license,recipe_version_id " +
            "FROM memory.saved_recipes WHERE environment=? AND actor_kind='account' AND principal_id=? AND id=? AND NOT deleted FOR SHARE").use { s ->
            s.setString(1, environment); s.setObject(2, principal.principalId); s.setObject(3, copy.savedRecipeId)
            s.executeQuery().use { r ->
                if (!r.next()) fail(SavedRecipeFailureCode.SAVED_RECIPE_UNAVAILABLE)
                if (r.getString(2) != copy.recipeHash || Json.parseToJsonElement(r.getString(3)) != copy.evidence ||
                    r.getString(4) != copy.sourceType || r.getObject(5, UUID::class.java) != copy.originPlanId ||
                    r.getString(6) != copy.contentLicense || r.getObject(7, UUID::class.java) != copy.recipeVersionId)
                    fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
                Json.parseToJsonElement(r.getString(1)).jsonObject.getValue("snapshot").jsonObject.also { if (r.next()) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE) }
            }
        }
        if (accountSavedRecipeHash(recipe) != copy.recipeHash) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
        requireAccountSavedMaterial(grant.source.entry, recipe, grant.allowReviewedScaling && copy.sourceType == "ownPlan")
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
