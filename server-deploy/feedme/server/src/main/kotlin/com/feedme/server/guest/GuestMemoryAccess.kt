package com.feedme.server.guest

import com.feedme.server.catalog.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.CommandActor
import com.feedme.server.kitchen.VerifiedKitchenPrincipal
import com.feedme.server.memory.*
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.json.*

/** Actual guest-owner bridge. Historical feedback scope is not a new recipe-use/copy
 * grant; only a newly requested context edit resolves current public catalog identities.
 * Every evidence object belongs to this exact actor, connection, thread and transaction. */
internal class GuestMemoryAccess(private val environment: String, private val connection: Connection,
    private val actual: VerifiedKitchenPrincipal, private val catalog: RecipeCatalogJournal,
    private val ingredients: IngredientCatalogStore) : MemoryAuthority {
    private val thread = Thread.currentThread()
    private val transaction = transactionId()
    val principal: VerifiedMemoryPrincipal
    private val proofs = mutableListOf<Proof>()
    init {
        if (actual.environment != environment || actual.kind != CommandActor.GUEST || actual.deviceSessionId != null ||
            catalog.environment != environment || ingredients.environment != environment) unauthenticated()
        principal = VerifiedMemoryPrincipal(environment, CommandActor.GUEST, actual.principalId, null, guestId())
        bound(connection, principal)
    }
    override fun lockPrincipal(connection: Connection, actor: VerifiedMemoryPrincipal) = bound(connection, actor)

    override fun authorizeContext(connection: Connection, actor: VerifiedMemoryPrincipal, context: JsonObject): MemoryContextEvidence {
        bound(connection, actor); validateContext(context)
        val checks = mutableListOf<() -> Unit>()
        context["recipeVersionId"]?.let { value ->
            val id = UUID.fromString(value.jsonPrimitive.content)
            val view = catalog.openView(connection)
            fun source(): String {
                view.checkCurrent()
                val item = view.lookupCurrent(id) ?: unavailable()
                val recipe = item.entry.recipe
                if (recipe["reviewStatus"] != JsonPrimitive("published") || item.entry.review["freeCatalogEligible"] != JsonPrimitive(true)) unavailable()
                return feedbackSha(feedbackCanonical(buildJsonObject {
                    put("releaseId", item.releaseId.toString()); put("revision", item.revision)
                    put("requestSha256", item.requestSha256); put("recipe", recipe)
                    put("review", item.entry.review); put("rightsReference", item.entry.rightsReference)
                }))
            }
            val original = source()
            checks += { if (source() != original) unavailable(); view.checkCurrent() }
        }
        context["ingredientId"]?.let { value ->
            val id = UUID.fromString(value.jsonPrimitive.content)
            fun source(): String {
                ingredients.checkCompatibility(connection)
                val current = ingredients.current(connection)
                val item = current.original.items.singleOrNull { it.ingredient.getValue("id") == JsonPrimitive(id.toString()) }
                    ?: unavailable()
                if (!item.reviewed || !item.published || !item.freeAccess) unavailable()
                return feedbackSha(feedbackCanonical(buildJsonObject {
                    put("revision", current.revision); put("releaseId", current.original.releaseId.toString())
                    put("requestSha256", current.original.requestSha256); put("ingredient", item.ingredient)
                }))
            }
            val original = source()
            checks += { if (source() != original) unavailable() }
        }
        return proof(context, checks)
    }

    override fun resolveFeedbackContext(connection: Connection, actor: VerifiedMemoryPrincipal, feedbackRow: JsonObject): MemoryContextEvidence {
        bound(connection, actor)
        requireOwner(feedbackRow)
        val id = uuid(feedbackRow, "id")
        val actualRow = owned("memory.feedback", "id", id)
        if (feedbackCanonical(actualRow) != feedbackCanonical(feedbackRow) || actualRow["deleted"] != JsonPrimitive(false)) corrupt()
        val contextText = actualRow.getValue("context_text").jsonPrimitive.content
        val provenanceText = actualRow.getValue("provenance_text").jsonPrimitive.content
        if (actualRow["context_sha256"] != JsonPrimitive(feedbackSha(contextText)) ||
            actualRow["provenance_sha256"] != JsonPrimitive(feedbackSha(provenanceText))) corrupt()
        val target = FeedbackTargetContext.decode(contextText)
        val provenance = Json.parseToJsonElement(provenanceText).jsonObject
        val body = actualRow.getValue("snapshot").jsonObject
        if (validator.validateSchema("Feedback", body.toString().encodeToByteArray()) != BodyValidationResult.Valid ||
            uuid(body, "id") != id || body["version"] != actualRow["version"] ||
            FeedbackTargetContext.fromInput(body).exactDocument != target.exactDocument ||
            feedbackCanonical(provenance) != provenanceText || provenance["formatVersion"] != JsonPrimitive(1) ||
            provenance["principalId"] != JsonPrimitive(actual.principalId.toString()) ||
            provenance["target"] != target.target || provenance["cookSessionId"] != target.cookSessionId?.let { JsonPrimitive(it.toString()) }) corrupt()
        val checks = mutableListOf<() -> Unit>({
            if (feedbackCanonical(owned("memory.feedback", "id", id)) != feedbackCanonical(actualRow)) corrupt()
        })
        var recipeId: UUID? = null
        if (target.cookSessionId != null) {
            if (provenance["ownedCookId"] != JsonPrimitive(target.cookSessionId.toString())) corrupt()
            if (target.kind == "plan" && provenance["planId"] != JsonPrimitive(target.resourceId.toString())) corrupt()
            recipeId = uuid(provenance, "recipeVersionId")
        } else if (target.kind == "plan") {
            if (provenance["planId"] != JsonPrimitive(target.resourceId.toString())) corrupt()
            recipeId = provenance["recipeVersionId"]?.let { UUID.fromString(it.jsonPrimitive.content) }
                ?: legacyPlanRecipe(requireNotNull(target.resourceId), provenance, checks)
        }
        val context = buildJsonObject {
            recipeId?.let { put("recipeVersionId", it.toString()) }
            when (target.kind) {
                "recipeVersion" -> {
                    if (recipeId != null && recipeId != target.resourceId) corrupt()
                    put("recipeVersionId", requireNotNull(target.resourceId).toString())
                }
                "ingredient" -> put("ingredientId", requireNotNull(target.resourceId).toString())
                "taste" -> put("tasteTag", requireNotNull(target.tag))
                "preparation" -> put("effortAspect", requireNotNull(target.tag))
                "plan", "cookSession" -> if (recipeId == null) corrupt()
                else -> corrupt()
            }
        }
        validateContext(context)
        return proof(context, checks)
    }

    /** Older proven Plan feedback did not retain recipeVersionId. Resolve only its exact
     * owned immutable Plan/header, never the current recommendation, TTL or catalog choice. */
    private fun legacyPlanRecipe(planId: UUID, provenance: JsonObject, checks: MutableList<() -> Unit>): UUID {
        fun compatible() {
            for ((version, name) in listOf(3 to "private_planning", 17 to "planning_manifests",
                19 to "guest_planning_preparations", 20 to "manifest_plan_lineage"))
                GuestFeedbackAccess.compatibility(connection, version, name)
        }
        compatible()
        val plan = owned("planning.plans", "id", planId)
        val text = plan.getValue("snapshot_text").jsonPrimitive.content
        val hash = feedbackSha(text)
        if (plan["storage_format"] != JsonPrimitive(2) || plan["parent_plan_id"] != JsonNull ||
            plan["status"] != JsonPrimitive("ready") || plan["snapshot_hash"] != JsonPrimitive(hash) ||
            provenance["planSnapshotSha256"] != JsonPrimitive(hash)) corrupt()
        val body = Json.parseToJsonElement(text).jsonObject
        if (validator.validateSchema("Plan", text.encodeToByteArray()) != BodyValidationResult.Valid ||
            uuid(body, "id") != planId || body["recipeVersionId"] != plan["recipe_version_id"] ||
            body.getValue("recipeSnapshot").jsonObject["id"] != plan["recipe_version_id"]) corrupt()
        val manifestId = uuid(plan, "request_id")
        val header = owned("planning.manifest_headers", "manifest_id", manifestId)
        val headerHash = feedbackSha(header.getValue("header_text").jsonPrimitive.content)
        if (header["header_sha256"] != JsonPrimitive(headerHash) || provenance["manifestSha256"] != JsonPrimitive(headerHash)) corrupt()
        checks += {
            compatible()
            if (feedbackCanonical(owned("planning.plans", "id", planId)) != feedbackCanonical(plan) ||
                feedbackCanonical(owned("planning.manifest_headers", "manifest_id", manifestId)) != feedbackCanonical(header)) corrupt()
        }
        return uuid(plan, "recipe_version_id")
    }
    private fun proof(context: JsonObject, checks: List<() -> Unit>): MemoryContextEvidence =
        Proof(Json.parseToJsonElement(context.toString()).jsonObject, checks.toList()).also {
            proofs += it; it.revalidate(connection, principal)
        }
    fun revalidate() { bound(connection, principal); proofs.forEach { it.revalidate(connection, principal) }; bound(connection, principal) }
    private inner class Proof(override val context: JsonObject, private val checks: List<() -> Unit>) : MemoryContextEvidence {
        override fun revalidate(connection: Connection, actor: VerifiedMemoryPrincipal) {
            bound(connection, actor); checks.forEach { it(); bound(connection, actor) }
        }
        override fun toString() = "MemoryContextEvidence(<redacted>)"
    }
    private fun bound(c: Connection, actor: VerifiedMemoryPrincipal) {
        current()
        if (c !== connection || actor !== principal || Thread.currentThread() !== thread || c.isClosed || c.autoCommit ||
            transactionId() != transaction || actor.environment != environment || actor.kind != CommandActor.GUEST ||
            actor.principalId != actual.principalId || actor.guestSessionId != guestId()) unauthenticated()
    }
    private fun guestId(): UUID = connection.prepareStatement("SELECT g.id FROM identity.principals p JOIN identity.guest_sessions g " +
        "ON g.environment=p.environment AND g.id=p.guest_session_id WHERE p.environment=? AND p.id=? AND p.kind='guest' AND p.status='active' FOR SHARE OF p,g").use {
        it.setString(1, environment); it.setObject(2, actual.principalId)
        it.executeQuery().use { rows -> if (!rows.next()) unauthenticated()
            rows.getObject(1, UUID::class.java).also { if (rows.next()) corrupt() } }
    }
    private fun transactionId(): Long {
        current(); if (connection.isClosed || connection.autoCommit) unauthenticated()
        return connection.createStatement().use { it.executeQuery("SELECT txid_current()").use { rows ->
            if (!rows.next()) corrupt(); rows.getLong(1).also { if (rows.next()) corrupt() }
        } }
    }
    private fun owned(table: String, column: String, id: UUID): JsonObject = connection.prepareStatement(
        "SELECT to_jsonb(r)::text FROM $table r WHERE environment=? AND actor_kind='guest' AND principal_id=? AND $column=? FOR SHARE").use {
        it.setString(1, environment); it.setObject(2, actual.principalId); it.setObject(3, id)
        it.executeQuery().use { rows ->
            if (!rows.next()) throw MemoryFailure(MemoryFailureCode.SOURCE_UNAVAILABLE)
            Json.parseToJsonElement(rows.getString(1)).jsonObject.also { row ->
                requireOwner(row); if (row[column] != JsonPrimitive(id.toString()) || rows.next()) corrupt()
            }
        }
    }
    private fun requireOwner(row: JsonObject) {
        if (row["environment"] != JsonPrimitive(environment) || row["actor_kind"] != JsonPrimitive("guest") ||
            row["principal_id"] != JsonPrimitive(actual.principalId.toString())) unauthenticated()
    }
    private fun validateContext(context: JsonObject) {
        if (validator.validateSchema("MemoryContext", context.toString().encodeToByteArray()) != BodyValidationResult.Valid)
            throw MemoryFailure(MemoryFailureCode.INPUT_INVALID)
    }
    private fun uuid(body: JsonObject, field: String): UUID = UUID.fromString(body.getValue(field).jsonPrimitive.content)
    private fun current() { if (Thread.currentThread().isInterrupted) throw InterruptedException("Guest memory interrupted") }
    private fun unauthenticated(): Nothing = throw MemoryFailure(MemoryFailureCode.UNAUTHENTICATED)
    private fun unavailable(): Nothing = throw MemoryFailure(MemoryFailureCode.CONTEXT_UNAVAILABLE)
    private fun corrupt(): Nothing = throw MemoryFailure(MemoryFailureCode.STORAGE_UNAVAILABLE)
    override fun toString() = "GuestMemoryAccess(<redacted>)"
    private companion object { val validator by lazy { ContractBodyValidator.bundled() } }
}
