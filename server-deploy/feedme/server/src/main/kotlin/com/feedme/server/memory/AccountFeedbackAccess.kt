package com.feedme.server.memory

import com.feedme.server.catalog.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.cooking.AccountMealAccess
import com.feedme.server.db.*
import com.feedme.server.identity.AccountFailure
import com.feedme.server.identity.AccountFailureCode
import com.feedme.server.planning.*
import java.math.BigDecimal
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.json.*

/** Invocation-local account authority. Retained opinions are not new recipe/copy grants.
 * No account UUID from a request is promoted to a private principal or a guest manifest. */
internal class AccountFeedbackAccess(private val environment: String, private val connection: Connection,
    private val account: AccountMealAccess, device: UUID, private val catalog: RecipeCatalogJournal,
    private val ingredients: IngredientCatalogStore, private val plans: PlansStore,
    private val planningActor: VerifiedPlanningPrincipal) : FeedbackAuthority {
    val principal = VerifiedFeedbackPrincipal(environment, CommandActor.ACCOUNT, account.principalId, device)
    private var proof: Proof? = null
    init {
        require(catalog.environment == environment && ingredients.environment == environment)
        require(planningActor.environment == environment && planningActor.kind == CommandActor.ACCOUNT &&
            planningActor.principalId == account.principalId && planningActor.deviceSessionId == device)
    }
    override fun lockPrincipal(connection: Connection, actor: VerifiedFeedbackPrincipal) {
        if (connection !== this.connection || actor !== principal) fail(FeedbackFailureCode.UNAUTHENTICATED)
        try { account.current(connection) } catch (f: AccountFailure) { fail(when (f.code) {
            AccountFailureCode.UNAUTHENTICATED, AccountFailureCode.ACCOUNT_UNAVAILABLE -> FeedbackFailureCode.UNAUTHENTICATED
            AccountFailureCode.POLICY_BLOCKED -> FeedbackFailureCode.FORBIDDEN
            AccountFailureCode.NOT_CONFIGURED -> FeedbackFailureCode.NOT_CONFIGURED
            else -> FeedbackFailureCode.STORAGE_UNAVAILABLE
        }) }
    }
    override fun authorizeTarget(connection: Connection, actor: VerifiedFeedbackPrincipal,
        context: FeedbackTargetContext): FeedbackTargetEvidence {
        lockPrincipal(connection, actor)
        if (proof != null) corrupt()
        val checks = mutableListOf<() -> Unit>()
        val provenance = buildJsonObject {
            put("formatVersion", 1); put("principalId", principal.principalId.toString()); put("target", context.target)
            context.cookSessionId?.let { put("cookSessionId", it.toString()) }
            val cookId = context.cookSessionId ?: context.resourceId.takeIf { context.kind == "cookSession" }
            if (cookId != null) {
                val cook = completedCook(cookId)
                val planId = uuid(cook, "plan_id")
                val pin = ownedPlan(planId)
                if (cook["plan_snapshot_text"] != JsonPrimitive(pin.snapshotText) ||
                    cook["plan_snapshot_hash"] != JsonPrimitive(pin.snapshotHash) ||
                    cook["plan_proof_hash"] != JsonPrimitive(pin.proofHash) ||
                    cook["plan_evidence_hash"] != JsonPrimitive(pin.evidenceHash)) corrupt()
                val recipe = pin.document.getValue("recipeSnapshot").jsonObject
                val valid = when (context.kind) {
                    "cookSession" -> context.resourceId == cookId
                    "plan" -> context.resourceId == planId
                    "recipeVersion" -> context.resourceId == uuid(recipe, "id")
                    "ingredient" -> recipe.getValue("ingredients").jsonArray.any { uuid(it.jsonObject, "ingredientId") == context.resourceId }
                    "taste" -> recipe.getValue("tasteTags").jsonArray.any { it.jsonPrimitive.content == context.tag }
                    "preparation" -> context.tag in setOf("chopping", "activeCooking", "cleanup")
                    else -> false
                }
                if (!valid) unavailable()
                checks += { if (feedbackCanonical(completedCook(cookId)) != feedbackCanonical(cook)) corrupt(); samePin(pin, ownedPlan(planId)) }
                put("ownedCookId", cookId.toString()); put("planId", planId.toString())
                put("planSnapshotSha256", pin.snapshotHash); put("planEvidenceSha256", pin.evidenceHash)
                put("recipeVersionId", recipe.getValue("id"))
            } else when (context.kind) {
                "plan" -> {
                    val id = requireNotNull(context.resourceId); val pin = ownedPlan(id)
                    checks += { samePin(pin, ownedPlan(id)) }
                    put("planId", id.toString()); put("planSnapshotSha256", pin.snapshotHash)
                    put("planEvidenceSha256", pin.evidenceHash); put("recipeVersionId", pin.document.getValue("recipeVersionId"))
                }
                "recipeVersion" -> {
                    val check = AccountMemoryCatalog.recipe(connection, catalog, requireNotNull(context.resourceId))
                    checks += check.second; put("catalogSourceSha256", check.first)
                }
                "ingredient" -> {
                    val check = AccountMemoryCatalog.ingredient(connection, ingredients, requireNotNull(context.resourceId))
                    checks += check.second; put("ingredientSourceSha256", check.first)
                }
                "taste", "preparation" -> put("source", "explicitSelfReport")
                else -> unavailable()
            }
        }
        return Proof(context, provenance, checks).also { proof = it; it.revalidate(connection, actor, context) }
    }
    fun revalidate() { lockPrincipal(connection, principal); proof?.let { it.revalidate(connection, principal, it.context) } }

    private fun ownedPlan(id: UUID): CookingPlanSnapshot {
        val plan = owned("planning.plans", id)
        val text = plan.getValue("snapshot_text").jsonPrimitive.content
        val hash = feedbackSha(text)
        val proofText = plan.getValue("proof_text").jsonPrimitive.content
        val request = owned("planning.plan_requests", uuid(plan, "request_id"))
        val evidenceText = request.getValue("evidence_text").jsonPrimitive.content
        if (plan["snapshot_hash"] != JsonPrimitive(hash) || plan["proof_hash"] != JsonPrimitive(feedbackSha(proofText)) ||
            request["evidence_hash"] != JsonPrimitive(feedbackSha(evidenceText)) ||
            request["request_hash"] != JsonPrimitive(feedbackSha(request.getValue("request_text").jsonPrimitive.content)) ||
            validator.validateResponse("getPlan", 200, text.encodeToByteArray(), "application/json") != BodyValidationResult.Valid) corrupt()
        val body = Json.parseToJsonElement(text).jsonObject
        if (body["id"] != JsonPrimitive(id.toString()) || plan["status"] != JsonPrimitive("ready") ||
            body["status"] != JsonPrimitive("ready") || body["recipeVersionId"] != plan["recipe_version_id"] ||
            body.getValue("recipeSnapshot").jsonObject["id"] != plan["recipe_version_id"]) unavailable()
        when (plan.getValue("storage_format").jsonPrimitive.int) {
            1 -> {
                // Legacy account-owned immutable planning evidence; no guest manifest fallback.
                val proof = Json.parseToJsonElement(proofText).jsonObject
                if (request["storage_format"] != JsonPrimitive(1) || proof["evidenceHash"] != request["evidence_hash"] ||
                    proof["requestHash"] != request["request_hash"]) corrupt()
            }
            3, 4, 5 -> if (plans.lockOwnedDerivedCopy(connection, planningActor, id) != body.getValue("recipeSnapshot")) corrupt()
            else -> fail(FeedbackFailureCode.NOT_CONFIGURED)
        }
        return CookingPlanSnapshot(text, hash, feedbackSha(proofText), feedbackSha(evidenceText))
    }
    private fun completedCook(id: UUID): JsonObject {
        val row = owned("cooking.cook_sessions", id)
        val body = row.getValue("snapshot").jsonObject
        if (validator.validateResponse("getCookSession", 200, body.toString().encodeToByteArray(), "application/json") != BodyValidationResult.Valid) corrupt()
        if (row["status"] != JsonPrimitive("completed") || body["status"] != JsonPrimitive("completed")) unavailable()
        if (body["id"] != JsonPrimitive(id.toString()) || body["planId"] != row["plan_id"] ||
            number(body.getValue("version")) != number(row.getValue("version")) ||
            number(body.getValue("deviceSequence")) != number(row.getValue("device_sequence")) ||
            instant(body.getValue("createdAt")) != instant(row.getValue("created_at")) ||
            instant(body.getValue("updatedAt")) != instant(row.getValue("updated_at")) ||
            instant(body.getValue("completedAt")) != instant(row.getValue("updated_at"))) corrupt()
        val event = connection.prepareStatement("SELECT to_jsonb(e)::text FROM cooking.step_events e WHERE environment=? AND actor_kind='account' AND principal_id=? AND session_id=? AND kind='completed' FOR SHARE").use {
            it.setString(1, environment); it.setObject(2, principal.principalId); it.setObject(3, id)
            it.executeQuery().use { rows -> if (!rows.next()) corrupt(); Json.parseToJsonElement(rows.getString(1)).jsonObject.also { if (rows.next()) corrupt() } }
        }
        val payload = event.getValue("payload").jsonObject
        if (validator.validateRequest("completeCookSession", payload.toString().encodeToByteArray(), "application/json") != BodyValidationResult.Valid ||
            number(event.getValue("device_sequence")) != number(body.getValue("deviceSequence")) ||
            number(event.getValue("session_version")) != number(body.getValue("version")) ||
            number(payload.getValue("deviceSequence")) != number(body.getValue("deviceSequence")) ||
            instant(event.getValue("accepted_at")) != instant(body.getValue("completedAt"))) corrupt()
        // A completion may have come from another registered device of this same account.
        connection.prepareStatement("SELECT 1 FROM identity.device_sessions d JOIN identity.principals p ON p.environment=d.environment AND p.user_id=d.user_id WHERE p.environment=? AND p.id=? AND p.kind='user' AND d.id=? FOR SHARE OF d,p").use {
            it.setString(1, environment); it.setObject(2, principal.principalId); it.setObject(3, uuid(event, "device_identity"))
            it.executeQuery().use { rows -> if (!rows.next() || rows.next()) corrupt() }
        }
        val command = CommandIdentity(PrincipalScope(environment, CommandActor.ACCOUNT, principal.principalId), "completeCookSession",
            uuid(event, "command_id"), mapOf("sessionId" to id.toString()), body = payload)
        if (event["request_hash"] != JsonPrimitive(command.requestHash)) corrupt()
        return JsonObject(row + ("_completionEvent" to event))
    }
    private fun owned(table: String, id: UUID): JsonObject = connection.prepareStatement(
        "SELECT to_jsonb(r)::text FROM $table r WHERE environment=? AND actor_kind='account' AND principal_id=? AND id=? FOR SHARE").use {
        it.setString(1, environment); it.setObject(2, principal.principalId); it.setObject(3, id)
        it.executeQuery().use { rows -> if (!rows.next()) unavailable(); Json.parseToJsonElement(rows.getString(1)).jsonObject.also { if (rows.next()) corrupt() } }
    }
    private inner class Proof(val context: FeedbackTargetContext, override val snapshot: JsonObject,
        private val checks: List<() -> Unit>) : FeedbackTargetEvidence {
        private var failed = false
        override fun revalidate(connection: Connection, actor: VerifiedFeedbackPrincipal, context: FeedbackTargetContext) {
            if (failed || context !== this.context) corrupt()
            try { lockPrincipal(connection, actor); checks.forEach { it() }; lockPrincipal(connection, actor) }
            catch (failure: Throwable) { failed = true; throw failure }
        }
    }
    private fun samePin(a: CookingPlanSnapshot, b: CookingPlanSnapshot) {
        if (a.snapshotText != b.snapshotText || a.snapshotHash != b.snapshotHash || a.proofHash != b.proofHash || a.evidenceHash != b.evidenceHash) corrupt()
    }
    private fun uuid(o: JsonObject, key: String) = UUID.fromString(o.getValue(key).jsonPrimitive.content)
    private fun number(e: JsonElement) = BigDecimal(e.jsonPrimitive.content).longValueExact()
    private fun instant(e: JsonElement) = java.time.OffsetDateTime.parse(e.jsonPrimitive.content).toInstant()
    private fun unavailable(): Nothing = fail(FeedbackFailureCode.TARGET_UNAVAILABLE)
    private fun corrupt(): Nothing = fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
    private fun fail(code: FeedbackFailureCode): Nothing = throw FeedbackFailure(code)
    override fun toString() = "AccountFeedbackAccess(<redacted>)"
    private companion object { val validator by lazy { ContractBodyValidator.bundled() } }
}

/** Current public source identities for explicit opinions/context edits, never copy rights. */
internal object AccountMemoryCatalog {
    fun recipe(c: Connection, catalog: RecipeCatalogJournal, id: UUID): Pair<String, () -> Unit> {
        val view = catalog.openView(c)
        fun source(): String {
            view.checkCurrent(); val item = view.lookupCurrent(id) ?: unavailable()
            if (item.entry.recipe["reviewStatus"] != JsonPrimitive("published") || item.entry.review["freeCatalogEligible"] != JsonPrimitive(true)) unavailable()
            return feedbackSha(feedbackCanonical(buildJsonObject { put("releaseId", item.releaseId.toString()); put("revision", item.revision)
                put("requestSha256", item.requestSha256); put("recipe", item.entry.recipe); put("review", item.entry.review); put("rightsReference", item.entry.rightsReference) }))
        }
        val hash = source(); return hash to { if (source() != hash) unavailable(); view.checkCurrent() }
    }
    fun ingredient(c: Connection, ingredients: IngredientCatalogStore, id: UUID): Pair<String, () -> Unit> {
        fun source(): String {
            ingredients.checkCompatibility(c); val current = ingredients.current(c)
            val item = current.original.items.singleOrNull { it.id == id } ?: unavailable()
            if (!item.reviewed || !item.published || !item.freeAccess) unavailable()
            return feedbackSha(feedbackCanonical(buildJsonObject { put("revision", current.revision); put("releaseId", current.original.releaseId.toString())
                put("requestSha256", current.original.requestSha256); put("ingredient", item.ingredient) }))
        }
        val hash = source(); return hash to { if (source() != hash) unavailable() }
    }
    private fun unavailable(): Nothing = throw FeedbackFailure(FeedbackFailureCode.TARGET_UNAVAILABLE)
}
