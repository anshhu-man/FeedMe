package com.feedme.server.guest

import com.feedme.server.catalog.*
import com.feedme.server.db.CommandActor
import com.feedme.server.kitchen.VerifiedKitchenPrincipal
import com.feedme.server.memory.*
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

/** Per-attempt bridge constructed by the real opaque-token owner. It never authenticates
 * caller IDs, starts a transaction or treats planning/cooking access as a copying grant. */
internal class GuestSavedRecipeAccess(
    private val environment: String,
    private val connection: Connection,
    private val actualActor: VerifiedKitchenPrincipal,
    private val catalog: RecipeCatalogJournal,
    private val planningPolicy: GuestPlanningPolicy,
    private val rights: RecipeCopyRightsStore,
    private val operation: String,
) : SavedRecipeAuthority {
    private val thread = Thread.currentThread()
    private val transaction = transactionId()
    val principal: VerifiedSavedRecipePrincipal
    private var fresh: Fresh? = null
    private val retained = linkedMapOf<UUID, Retained>()

    init {
        if (actualActor.environment != environment || actualActor.kind != CommandActor.GUEST || actualActor.deviceSessionId != null)
            fail(SavedRecipeFailureCode.UNAUTHENTICATED)
        principal = VerifiedSavedRecipePrincipal(environment, CommandActor.GUEST, actualActor.principalId, null, guestId())
        requireBound(connection, principal)
    }

    override fun lockPrincipal(connection: Connection, principal: VerifiedSavedRecipePrincipal) = requireBound(connection, principal)

    override fun authorizeNewCopy(connection: Connection, principal: VerifiedSavedRecipePrincipal,
        planId: UUID?, recipeVersionId: UUID?): AuthorizedRecipeCopy {
        requireBound(connection, principal)
        if (operation != "saveRecipe" || fresh != null || planId == null && recipeVersionId == null)
            fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
        val verifier = planId?.let { GuestPlanningManifestVerifier(environment, catalog, planningPolicy) }
        val verified = planId?.let { verifier!!.verifyCopyFirstPlan(connection, actualActor, it) }
        val material = verified?.let { verifier!!.requireCopyRecipe(connection, actualActor, it) }
        val selectedId = material?.getValue("id")?.jsonPrimitive?.content?.let(UUID::fromString) ?: recipeVersionId!!
        if (recipeVersionId != null && recipeVersionId != selectedId) fail(SavedRecipeFailureCode.RECIPE_UNAVAILABLE)
        val grant = copyRights { rights.openNew(connection, selectedId, true) }
        val recipe = material ?: grant.source.entry.recipe
        requireGuestSavedMaterial(grant.source.entry, recipe, grant.allowReviewedScaling && planId != null)
        val evidence = buildJsonObject {
            put("formatVersion", 1); put("grant", grant.evidence); put("recipeHash", guestSavedRecipeHash(recipe))
        }
        val permit = AuthorizedRecipeCopy(selectedId, recipe, grant.contentLicense, evidence)
        fresh = Fresh(planId, verifier, verified, recipe, grant)
        revalidate()
        return permit
    }

    override fun requireExistingCopyAllowed(connection: Connection, principal: VerifiedSavedRecipePrincipal, copy: SavedRecipeCopyEvidence) {
        requireBound(connection, principal)
        if (operation == "deleteSavedRecipe") fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
        val old = retained[copy.savedRecipeId]
        if (old != null) {
            if (identity(old.copy) != identity(copy)) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
            checkRetained(old)
        } else {
            val evidence = copy.evidence
            if (evidence.keys != setOf("formatVersion", "grant", "recipeHash") || evidence["formatVersion"] != JsonPrimitive(1) ||
                evidence["recipeHash"] != JsonPrimitive(copy.recipeHash)) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
            val grant = copyRights { rights.openExisting(connection, evidence.getValue("grant").jsonObject, true) }
            val binding = Retained(copy, grant)
            checkRetained(binding)
            retained[copy.savedRecipeId] = binding
        }
        requireBound(connection, principal)
    }

    fun revalidate() {
        requireBound(connection, principal)
        fresh?.let { binding ->
            copyRights { binding.grant.revalidate(connection) }
            if (binding.verified != null && binding.verifier!!.requireCopyRecipe(connection, actualActor, binding.verified) != binding.recipe)
                fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
            requireGuestSavedMaterial(binding.grant.source.entry, binding.recipe,
                binding.grant.allowReviewedScaling && binding.planId != null)
        }
        retained.values.forEach(::checkRetained)
        requireBound(connection, principal)
    }

    private fun checkRetained(binding: Retained) {
        val copy = binding.copy; val grant = binding.grant
        copyRights { grant.revalidate(connection) }
        if (copy.recipeVersionId != grant.recipeVersionId || copy.contentLicense != grant.contentLicense ||
            copy.sourceType !in setOf("catalog", "ownPlan") || (copy.sourceType == "ownPlan") != (copy.originPlanId != null))
            fail(SavedRecipeFailureCode.RECIPE_UNAVAILABLE)
        // Existing copies are recipe-only. Never require original Plan existence, inputs,
        // cursor or expiry; separately rechecked retained rights govern this material.
        val recipe = connection.prepareStatement("SELECT snapshot,recipe_hash,copy_evidence,source_type,origin_plan_id,content_license,recipe_version_id " +
            "FROM memory.saved_recipes WHERE environment=? AND actor_kind='guest' AND principal_id=? AND id=? AND NOT deleted FOR SHARE").use { s ->
            s.setString(1, environment); s.setObject(2, actualActor.principalId); s.setObject(3, copy.savedRecipeId)
            s.executeQuery().use { rows ->
                if (!rows.next()) fail(SavedRecipeFailureCode.SAVED_RECIPE_UNAVAILABLE)
                if (rows.getString(2) != copy.recipeHash || Json.parseToJsonElement(rows.getString(3)) != copy.evidence ||
                    rows.getString(4) != copy.sourceType || rows.getObject(5, UUID::class.java) != copy.originPlanId ||
                    rows.getString(6) != copy.contentLicense || rows.getObject(7, UUID::class.java) != copy.recipeVersionId)
                    fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
                Json.parseToJsonElement(rows.getString(1)).jsonObject.getValue("snapshot").jsonObject.also {
                    if (rows.next()) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
                }
            }
        }
        if (guestSavedRecipeHash(recipe) != copy.recipeHash) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
        requireGuestSavedMaterial(grant.source.entry, recipe, grant.allowReviewedScaling && copy.sourceType == "ownPlan")
        copyRights { grant.revalidate(connection) }
    }

    fun requireBound(connection: Connection, actor: VerifiedSavedRecipePrincipal) {
        current()
        if (connection !== this.connection || actor !== principal || Thread.currentThread() !== thread ||
            connection.isClosed || connection.autoCommit || transactionId() != transaction || actor.environment != environment ||
            actor.kind != CommandActor.GUEST || actor.deviceSessionId != null || actor.principalId != actualActor.principalId ||
            actor.guestSessionId != guestId()) fail(SavedRecipeFailureCode.UNAUTHENTICATED)
    }

    /** No SQL or I/O: the actual guest owner supplies its final accepted database time
     * after all current-identity and domain reads. These checks can only reject. */
    fun checkAt(connection: Connection, at: Instant) {
        current()
        if (connection !== this.connection || Thread.currentThread() !== thread || connection.isClosed || connection.autoCommit)
            fail(SavedRecipeFailureCode.UNAUTHENTICATED)
        fresh?.let { copyRights { it.grant.checkAt(connection, at) } }
        retained.values.forEach { copyRights { it.grant.checkAt(connection, at) } }
    }

    private fun guestId(): UUID = connection.prepareStatement("SELECT g.id FROM identity.principals p JOIN identity.guest_sessions g " +
        "ON g.environment=p.environment AND g.id=p.guest_session_id WHERE p.environment=? AND p.id=? AND p.kind='guest' AND p.status='active' " +
        "AND g.revoked_at IS NULL AND g.merged_to_user_id IS NULL FOR SHARE OF p,g").use { s ->
        s.setString(1, environment); s.setObject(2, actualActor.principalId)
        s.executeQuery().use { rows ->
            if (!rows.next()) fail(SavedRecipeFailureCode.UNAUTHENTICATED)
            rows.getObject(1, UUID::class.java).also { if (rows.next()) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE) }
        }
    }
    private fun transactionId(): Long {
        current()
        if (connection.isClosed || connection.autoCommit) fail(SavedRecipeFailureCode.UNAUTHENTICATED)
        return connection.createStatement().use { s -> s.executeQuery("SELECT txid_current()").use { rows ->
            if (!rows.next()) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
            rows.getLong(1).also { if (rows.next()) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE) }
        } }
    }
    private fun identity(copy: SavedRecipeCopyEvidence) = listOf(copy.savedRecipeId, copy.recipeVersionId, copy.recipeHash,
        copy.sourceType, copy.originPlanId, copy.contentLicense, copy.evidence)
    private class Fresh(val planId: UUID?, val verifier: GuestPlanningManifestVerifier?, val verified: GuestVerifiedManifest?,
        val recipe: JsonObject, val grant: RecipeCopyRightsHandle)
    private class Retained(val copy: SavedRecipeCopyEvidence, val grant: RecipeCopyRightsHandle)
    private fun current() { if (Thread.currentThread().isInterrupted) throw InterruptedException("Guest saving interrupted") }
    private fun <T> copyRights(action: () -> T): T = try { action() }
        catch (failure: RecipeCopyRightsFailure) { fail(when (failure.code) {
            RecipeCopyRightsFailureCode.NOT_CONFIGURED -> SavedRecipeFailureCode.NOT_CONFIGURED
            RecipeCopyRightsFailureCode.RECALLED -> SavedRecipeFailureCode.RECIPE_RECALLED
            RecipeCopyRightsFailureCode.AUTHORITY_DENIED, RecipeCopyRightsFailureCode.NOT_ALLOWED,
            RecipeCopyRightsFailureCode.EXPIRED -> SavedRecipeFailureCode.RECIPE_UNAVAILABLE
            else -> SavedRecipeFailureCode.STORAGE_UNAVAILABLE
        }) }
    private fun fail(code: SavedRecipeFailureCode): Nothing = throw SavedRecipeFailure(code)
    override fun toString() = "GuestSavedRecipeAccess(<redacted>)"
}
