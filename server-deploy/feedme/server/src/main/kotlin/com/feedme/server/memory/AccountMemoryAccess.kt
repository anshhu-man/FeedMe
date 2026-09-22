package com.feedme.server.memory

import com.feedme.server.catalog.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.cooking.AccountMealAccess
import com.feedme.server.db.CommandActor
import com.feedme.server.identity.AccountFailure
import com.feedme.server.identity.AccountFailureCode
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.json.*

/** Actual account-owned projection/context controls. Committed feedback provenance is
 * retained independently of Plan age; it never confers a new recipe or cooking grant. */
internal class AccountMemoryAccess(private val environment: String, private val connection: Connection,
    private val account: AccountMealAccess, device: UUID, private val catalog: RecipeCatalogJournal,
    private val ingredients: IngredientCatalogStore) : MemoryAuthority {
    val principal = VerifiedMemoryPrincipal(environment, CommandActor.ACCOUNT, account.principalId, device)
    private val proofs = mutableListOf<Proof>()
    init { require(catalog.environment == environment && ingredients.environment == environment) }
    override fun lockPrincipal(connection: Connection, actor: VerifiedMemoryPrincipal) {
        if (connection !== this.connection || actor !== principal) fail(MemoryFailureCode.UNAUTHENTICATED)
        try { account.current(connection) } catch (f: AccountFailure) { fail(when (f.code) {
            AccountFailureCode.UNAUTHENTICATED, AccountFailureCode.ACCOUNT_UNAVAILABLE -> MemoryFailureCode.UNAUTHENTICATED
            AccountFailureCode.POLICY_BLOCKED -> MemoryFailureCode.FORBIDDEN
            AccountFailureCode.NOT_CONFIGURED -> MemoryFailureCode.NOT_CONFIGURED
            else -> MemoryFailureCode.STORAGE_UNAVAILABLE
        }) }
    }
    override fun authorizeContext(connection: Connection, actor: VerifiedMemoryPrincipal, context: JsonObject): MemoryContextEvidence {
        lockPrincipal(connection, actor); validate(context)
        val checks = mutableListOf<() -> Unit>()
        context["recipeVersionId"]?.let { checks += AccountMemoryCatalog.recipe(connection, catalog, UUID.fromString(it.jsonPrimitive.content)).second }
        context["ingredientId"]?.let { checks += AccountMemoryCatalog.ingredient(connection, ingredients, UUID.fromString(it.jsonPrimitive.content)).second }
        return proof(context, checks)
    }
    override fun resolveFeedbackContext(connection: Connection, actor: VerifiedMemoryPrincipal, feedbackRow: JsonObject): MemoryContextEvidence {
        lockPrincipal(connection, actor)
        val id = uuid(feedbackRow, "id")
        val row = owned(id)
        if (feedbackCanonical(row) != feedbackCanonical(feedbackRow) || row["deleted"] != JsonPrimitive(false)) corrupt()
        val text = row.getValue("context_text").jsonPrimitive.content
        val sourceText = row.getValue("provenance_text").jsonPrimitive.content
        if (row["context_sha256"] != JsonPrimitive(feedbackSha(text)) || row["provenance_sha256"] != JsonPrimitive(feedbackSha(sourceText))) corrupt()
        val target = FeedbackTargetContext.decode(text)
        val source = Json.parseToJsonElement(sourceText).jsonObject
        val body = row.getValue("snapshot").jsonObject
        if (validator.validateSchema("Feedback", body.toString().encodeToByteArray()) != BodyValidationResult.Valid ||
            body["id"] != JsonPrimitive(id.toString()) || body["version"] != row["version"] ||
            FeedbackTargetContext.fromInput(body).exactDocument != target.exactDocument ||
            feedbackCanonical(source) != sourceText || source["formatVersion"] != JsonPrimitive(1) ||
            source["principalId"] != JsonPrimitive(principal.principalId.toString()) || source["target"] != target.target ||
            source["cookSessionId"] != target.cookSessionId?.let { JsonPrimitive(it.toString()) }) corrupt()
        var recipe: UUID? = null
        if (target.cookSessionId != null) {
            if (source["ownedCookId"] != JsonPrimitive(target.cookSessionId.toString())) corrupt()
            if (target.kind == "plan" && source["planId"] != JsonPrimitive(target.resourceId.toString())) corrupt()
            recipe = uuid(source, "recipeVersionId")
        } else if (target.kind == "plan") {
            if (source["planId"] != JsonPrimitive(target.resourceId.toString())) corrupt()
            // No guest format-2 reconstruction and no inference from today's selected Plan.
            if (source["recipeVersionId"] == null) fail(MemoryFailureCode.SOURCE_UNAVAILABLE)
            recipe = uuid(source, "recipeVersionId")
        }
        val context = buildJsonObject {
            recipe?.let { put("recipeVersionId", it.toString()) }
            when (target.kind) {
                "recipeVersion" -> { if (recipe != null && recipe != target.resourceId) corrupt(); put("recipeVersionId", requireNotNull(target.resourceId).toString()) }
                "ingredient" -> put("ingredientId", requireNotNull(target.resourceId).toString())
                "taste" -> put("tasteTag", requireNotNull(target.tag))
                "preparation" -> put("effortAspect", requireNotNull(target.tag))
                "plan", "cookSession" -> if (recipe == null) corrupt()
                else -> corrupt()
            }
        }
        validate(context)
        return proof(context, listOf { if (feedbackCanonical(owned(id)) != feedbackCanonical(row)) corrupt() })
    }
    fun revalidate() { lockPrincipal(connection, principal); proofs.forEach { it.revalidate(connection, principal) }; lockPrincipal(connection, principal) }
    private fun proof(context: JsonObject, checks: List<() -> Unit>): MemoryContextEvidence =
        Proof(Json.parseToJsonElement(context.toString()).jsonObject, checks.toList()).also { proofs += it; it.revalidate(connection, principal) }
    private inner class Proof(override val context: JsonObject, private val checks: List<() -> Unit>) : MemoryContextEvidence {
        private var failed = false
        override fun revalidate(connection: Connection, actor: VerifiedMemoryPrincipal) {
            if (failed) corrupt()
            try { lockPrincipal(connection, actor); checks.forEach { it(); lockPrincipal(connection, actor) } }
            catch (failure: Throwable) { failed = true; throw failure }
        }
        override fun toString() = "AccountMemoryEvidence(<redacted>)"
    }
    private fun owned(id: UUID): JsonObject = connection.prepareStatement(
        "SELECT to_jsonb(r)::text FROM memory.feedback r WHERE environment=? AND actor_kind='account' AND principal_id=? AND id=? FOR SHARE").use {
        it.setString(1, environment); it.setObject(2, principal.principalId); it.setObject(3, id)
        it.executeQuery().use { rows -> if (!rows.next()) fail(MemoryFailureCode.SOURCE_UNAVAILABLE)
            Json.parseToJsonElement(rows.getString(1)).jsonObject.also { row ->
                if (rows.next() || row["environment"] != JsonPrimitive(environment) || row["actor_kind"] != JsonPrimitive("account") ||
                    row["principal_id"] != JsonPrimitive(principal.principalId.toString()) || row["id"] != JsonPrimitive(id.toString())) corrupt()
            }
        }
    }
    private fun validate(context: JsonObject) { if (validator.validateSchema("MemoryContext", context.toString().encodeToByteArray()) != BodyValidationResult.Valid) fail(MemoryFailureCode.INPUT_INVALID) }
    private fun uuid(body: JsonObject, name: String) = UUID.fromString(body.getValue(name).jsonPrimitive.content)
    private fun corrupt(): Nothing = fail(MemoryFailureCode.STORAGE_UNAVAILABLE)
    private fun fail(code: MemoryFailureCode): Nothing = throw MemoryFailure(code)
    override fun toString() = "AccountMemoryAccess(<redacted>)"
    private companion object { val validator by lazy { ContractBodyValidator.bundled() } }
}
