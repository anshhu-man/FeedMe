package com.feedme.server.memory

import com.feedme.server.cooking.*
import com.feedme.server.db.*
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.*

/** TEST ONLY. Real PostgreSQL/PlansStore, explicitly synthetic verified identity/editorial/copy decisions. */
class SavedRecipeTestFixture(val source: DataSource) {
    val base = CookingTestFixture(source)
    val authority = TestAuthority()
    val faults get() = base.faults
    val account = actor(base.account)
    val store = newStore()
    fun newStore(maxResponseBytes: Int = 65536, cursorLifetimeSeconds: Int = 600) = SavedRecipeStore("test", PgTransactions(faults.wrap(source)), authority,
        SavedRecipeCursors("v1", mapOf("v1" to ByteArray(32) { 9 })), SavedRecipeServicePolicy(maxResponseBytes, cursorLifetimeSeconds, "My cookbook"))
    fun principal(kind: CommandActor = CommandActor.ACCOUNT, id: UUID = UUID.randomUUID()) = actor(base.principal(kind, id))
    fun secondDevice(principal: VerifiedSavedRecipePrincipal = account) = actor(base.secondDevice(cooking(principal)))
    fun seedPlan(principal: VerifiedSavedRecipePrincipal = account, body: JsonObject = CookingTestFixture.planningRequest()) = base.seedPlan(cooking(principal), body)
    fun recipe(principal: VerifiedSavedRecipePrincipal = account) = source.connection.use { currentRecipe(it, principal) }
    fun changeRecipe(principal: VerifiedSavedRecipePrincipal = account, transform: (JsonObject) -> JsonObject) = base.changeEvidence(cooking(principal)) { e ->
        val catalog = e.getValue("catalog").jsonObject; val candidate = catalog.getValue("candidates").jsonArray.single().jsonObject
        val changed = JsonObject(candidate + ("recipe" to transform(candidate.getValue("recipe").jsonObject)))
        JsonObject(e + ("catalog" to JsonObject(catalog + ("candidates" to JsonArray(listOf(changed))))))
    }
    fun catalogInput(principal: VerifiedSavedRecipePrincipal = account) = buildJsonObject { put("recipeVersionId", recipe(principal).getValue("id")) }
    fun planInput(plan: JsonObject) = buildJsonObject { put("planId", plan.getValue("id")) }
    fun sql(sql: String) = base.sql(sql)
    fun value(sql: String) = base.value(sql)
    fun count(table: String) = base.count(table)
    fun cooking(p: VerifiedSavedRecipePrincipal) = VerifiedCookingPrincipal(p.environment, p.kind, p.principalId, p.deviceSessionId, p.guestSessionId)

    inner class TestAuthority : SavedRecipeAuthority {
        var newCopiesAllowed = true; var existingCopiesAllowed = true; var recalled = false
        var allowExpiredPlanCopy = false; var newCalls = 0; var existingCalls = 0
        var afterPrincipal: (() -> Unit)? = null; var afterNewCopy: (() -> Unit)? = null
        var substituteRecipe: JsonObject? = null
        override fun lockPrincipal(connection: Connection, principal: VerifiedSavedRecipePrincipal) {
            try { base.authority.lockPrincipal(connection, cooking(principal)) }
            catch (_: CookingFailure) { throw SavedRecipeFailure(SavedRecipeFailureCode.UNAUTHENTICATED) }
            afterPrincipal?.invoke()
        }
        override fun authorizeNewCopy(connection: Connection, principal: VerifiedSavedRecipePrincipal, planId: UUID?, recipeVersionId: UUID?): AuthorizedRecipeCopy {
            newCalls++
            if (!newCopiesAllowed) throw SavedRecipeFailure(SavedRecipeFailureCode.NOT_CONFIGURED)
            if (recalled) throw SavedRecipeFailure(SavedRecipeFailureCode.RECIPE_RECALLED)
            val catalog = currentRecipe(connection, principal)
            if (catalog["reviewStatus"] != JsonPrimitive("published")) throw SavedRecipeFailure(SavedRecipeFailureCode.RECIPE_UNAVAILABLE)
            val selected = if (planId == null) catalog else connection.prepareStatement(
                "SELECT p.snapshot_text,r.expires_at>clock_timestamp() FROM planning.plans p JOIN planning.plan_requests r ON (r.environment,r.actor_kind,r.principal_id,r.id)=(p.environment,p.actor_kind,p.principal_id,p.request_id) WHERE p.environment=? AND p.actor_kind=? AND p.principal_id=? AND p.id=? FOR SHARE OF p,r").use {
                it.setString(1, principal.environment); it.setString(2, principal.kind.name.lowercase()); it.setObject(3, principal.principalId); it.setObject(4, planId)
                it.executeQuery().use { r ->
                    if (!r.next()) throw SavedRecipeFailure(SavedRecipeFailureCode.PLAN_UNAVAILABLE)
                    if (!r.getBoolean(2) && !allowExpiredPlanCopy) throw SavedRecipeFailure(SavedRecipeFailureCode.RECIPE_UNAVAILABLE)
                    Json.parseToJsonElement(r.getString(1)).jsonObject.getValue("recipeSnapshot").jsonObject
                }
            }
            if (selected["id"] != catalog["id"] || (recipeVersionId != null && selected["id"] != JsonPrimitive(recipeVersionId.toString())))
                throw SavedRecipeFailure(SavedRecipeFailureCode.RECIPE_UNAVAILABLE)
            val result = AuthorizedRecipeCopy(UUID.fromString(selected.getValue("id").jsonPrimitive.content), substituteRecipe ?: selected,
                "catalogRedistributable", buildJsonObject { put("testCopyPolicy", "synthetic-positive-copy-v1"); put("reviewReference", "synthetic-independent-review") })
            afterNewCopy?.invoke(); return result
        }
        override fun requireExistingCopyAllowed(connection: Connection, principal: VerifiedSavedRecipePrincipal, copy: SavedRecipeCopyEvidence) {
            existingCalls++
            if (recalled) throw SavedRecipeFailure(SavedRecipeFailureCode.RECIPE_RECALLED)
            if (!existingCopiesAllowed) throw SavedRecipeFailure(SavedRecipeFailureCode.RECIPE_UNAVAILABLE)
            if (copy.evidence["testCopyPolicy"] != JsonPrimitive("synthetic-positive-copy-v1")) throw SavedRecipeFailure(SavedRecipeFailureCode.RECIPE_UNAVAILABLE)
            // Synthetic retained grant/recall decision; deliberately no fresh publication/Plan TTL lookup.
        }
    }
    private fun currentRecipe(c: Connection, actor: VerifiedSavedRecipePrincipal): JsonObject = c.prepareStatement("SELECT snapshot_text FROM cooking_test.inputs WHERE kind=? AND id=? FOR SHARE").use {
        it.setString(1, actor.kind.name.lowercase()); it.setObject(2, actor.principalId)
        it.executeQuery().use { r -> check(r.next()); Json.parseToJsonElement(r.getString(1)).jsonObject.getValue("catalog").jsonObject.getValue("candidates").jsonArray.single().jsonObject.getValue("recipe").jsonObject }
    }
    companion object {
        fun actor(p: VerifiedCookingPrincipal) = VerifiedSavedRecipePrincipal(p.environment, p.kind, p.principalId, p.deviceSessionId, p.guestSessionId)
        fun reply(result: CommandResult): StoredReply = when (result) { is CommandResult.Applied -> result.reply; is CommandResult.Replayed -> result.reply; else -> error("Expected acknowledged synthetic test command") }
    }
}
