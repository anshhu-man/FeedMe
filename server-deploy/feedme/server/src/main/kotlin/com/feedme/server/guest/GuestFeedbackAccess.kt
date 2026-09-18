package com.feedme.server.guest

import com.feedme.server.catalog.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.CommandActor
import com.feedme.server.db.CommandIdentity
import com.feedme.server.db.PrincipalScope
import com.feedme.server.kitchen.VerifiedKitchenPrincipal
import com.feedme.server.memory.*
import java.math.BigDecimal
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.json.*

/** Created only inside actual opaque guest admission. This is a bound target resolver,
 * not an identity provider or permission to disclose/copy/cook a historical recipe. */
internal class GuestFeedbackAccess(
    private val environment: String,
    private val connection: Connection,
    private val actual: VerifiedKitchenPrincipal,
    private val catalog: RecipeCatalogJournal,
    private val ingredients: IngredientCatalogStore,
    private val planningPolicy: GuestPlanningPolicy,
) : FeedbackAuthority {
    private val thread = Thread.currentThread()
    private val transaction = transactionId()
    val principal: VerifiedFeedbackPrincipal
    private var proof: Proof? = null

    init {
        if (actual.environment != environment || actual.kind != CommandActor.GUEST || actual.deviceSessionId != null ||
            catalog.environment != environment || ingredients.environment != environment) unauthenticated()
        principal = VerifiedFeedbackPrincipal(environment, CommandActor.GUEST, actual.principalId, null, guestId())
        bound(connection, principal)
    }

    override fun lockPrincipal(connection: Connection, actor: VerifiedFeedbackPrincipal) = bound(connection, actor)

    override fun authorizeTarget(connection: Connection, actor: VerifiedFeedbackPrincipal,
        context: FeedbackTargetContext): FeedbackTargetEvidence {
        bound(connection, actor)
        if (proof != null) unavailable()
        val checks = mutableListOf<() -> Unit>()
        val provenance = buildJsonObject {
            put("formatVersion", 1)
            put("principalId", actual.principalId.toString())
            put("target", context.target)
            context.cookSessionId?.let { put("cookSessionId", it.toString()) }
            val cookId = context.cookSessionId ?: context.resourceId.takeIf { context.kind == "cookSession" }
            if (cookId != null) {
                val original = cook(cookId)
                val planId = UUID.fromString(original.getValue("plan_id").jsonPrimitive.content)
                val pin = historicalPlan(planId, checks)
                if (original.getValue("plan_snapshot_text").jsonPrimitive.content != pin.snapshotText ||
                    original.getValue("plan_snapshot_hash").jsonPrimitive.content != pin.snapshotHash ||
                    original.getValue("plan_proof_hash").jsonPrimitive.content != pin.proofHash ||
                    original.getValue("plan_evidence_hash").jsonPrimitive.content != pin.evidenceHash) unavailable()
                val recipe = Json.parseToJsonElement(pin.snapshotText).jsonObject.getValue("recipeSnapshot").jsonObject
                validateScoped(context, cookId, planId, recipe)
                checks += { if (canonical(cook(cookId)) != canonical(original)) unavailable() }
                put("ownedCookId", cookId.toString()); put("planId", planId.toString())
                put("planSnapshotSha256", pin.snapshotHash); put("manifestSha256", pin.evidenceHash)
                put("recipeVersionId", recipe.getValue("id"))
            } else when (context.kind) {
                "plan" -> {
                    val planId = context.resourceId ?: invalid()
                    val pin = historicalPlan(planId, checks)
                    put("planId", planId.toString()); put("planSnapshotSha256", pin.snapshotHash)
                    put("manifestSha256", pin.evidenceHash)
                    // Retained private memory scope must remain inspectable independently
                    // of future Plan retention; this is an ID, never another recipe copy.
                    put("recipeVersionId", Json.parseToJsonElement(pin.snapshotText).jsonObject.getValue("recipeVersionId"))
                }
                "recipeVersion" -> {
                    val id = context.resourceId ?: invalid()
                    val view = catalog.openView(connection)
                    fun source(): JsonObject {
                        val value = view.lookupCurrent(id) ?: targetUnavailable()
                        if (value.entry.recipe.getValue("reviewStatus").jsonPrimitive.content != "published" ||
                            !value.entry.review.getValue("freeCatalogEligible").jsonPrimitive.boolean) targetUnavailable()
                        return buildJsonObject {
                            put("releaseId", value.releaseId.toString()); put("revision", value.revision)
                            put("requestSha256", value.requestSha256); put("recipe", value.entry.recipe)
                            put("review", value.entry.review); put("rightsReference", value.entry.rightsReference)
                        }
                    }
                    val hash = sha(canonical(source()))
                    checks += { view.checkCurrent(); if (sha(canonical(source())) != hash) unavailable(); view.checkCurrent() }
                    put("catalogSourceSha256", hash)
                }
                "ingredient" -> {
                    val id = context.resourceId ?: invalid()
                    ingredients.checkCompatibility(connection)
                    fun source(): JsonObject {
                        val current = ingredients.current(connection)
                        val item = current.original.items.singleOrNull { it.id == id } ?: targetUnavailable()
                        if (!item.reviewed || !item.published || !item.freeAccess) targetUnavailable()
                        return buildJsonObject {
                            put("revision", current.revision); put("releaseId", current.original.releaseId.toString())
                            put("requestSha256", current.original.requestSha256); put("ingredient", item.ingredient)
                        }
                    }
                    val hash = sha(canonical(source()))
                    checks += { ingredients.checkCompatibility(connection); if (sha(canonical(source())) != hash) unavailable() }
                    put("ingredientSourceSha256", hash)
                }
                // Explicit standalone sensory/preparation reports need no fabricated meal.
                "taste", "preparation" -> put("source", "explicitSelfReport")
                else -> invalid()
            }
        }
        return Proof(context, provenance, checks).also { proof = it; it.revalidate(connection, actor, context) }
    }

    fun revalidate() {
        bound(connection, principal)
        proof?.let { it.revalidate(connection, principal, it.context) }
        bound(connection, principal)
    }

    private fun historicalPlan(id: UUID, checks: MutableList<() -> Unit>): com.feedme.server.planning.CookingPlanSnapshot {
        for ((version, name) in listOf(3 to "private_planning", 17 to "planning_manifests",
            19 to "guest_planning_preparations", 20 to "manifest_plan_lineage")) compatibility(connection, version, name)
        val verifier = GuestPlanningManifestVerifier(environment, catalog, planningPolicy)
        val verified = verifier.verifyFeedbackFirstPlan(connection, actual, id)
        val snapshot = verifier.requireFeedbackSnapshot(connection, actual, verified)
        checks += {
            val current = verifier.requireFeedbackSnapshot(connection, actual, verified)
            if (current.snapshotText != snapshot.snapshotText || current.snapshotHash != snapshot.snapshotHash ||
                current.proofHash != snapshot.proofHash || current.evidenceHash != snapshot.evidenceHash) unavailable()
        }
        return snapshot
    }

    private fun cook(id: UUID): JsonObject {
        compatibility(connection, 5, "private_cooking")
        val row = connection.prepareStatement("SELECT to_jsonb(s)::text FROM cooking.cook_sessions s " +
            "WHERE environment=? AND actor_kind='guest' AND principal_id=? AND id=? FOR SHARE").use { s ->
            s.setString(1, environment); s.setObject(2, actual.principalId); s.setObject(3, id)
            s.executeQuery().use { r ->
                if (!r.next()) targetUnavailable()
                Json.parseToJsonElement(r.getString(1)).jsonObject.also { if (r.next()) unavailable() }
            }
        }
        val body = row.getValue("snapshot").jsonObject
        if (validator.validateResponse("getCookSession", 200, body.toString().toByteArray(), "application/json") !is BodyValidationResult.Valid)
            unavailable()
        if (row.getValue("status") != JsonPrimitive("completed") || body.getValue("status") != JsonPrimitive("completed"))
            targetUnavailable()
        if (UUID.fromString(body.getValue("id").jsonPrimitive.content) != id ||
            body.getValue("planId") != row.getValue("plan_id") ||
            number(body.getValue("version")) != number(row.getValue("version")) ||
            number(body.getValue("deviceSequence")) != number(row.getValue("device_sequence")) ||
            instant(body.getValue("createdAt")) != instant(row.getValue("created_at")) ||
            instant(body.getValue("updatedAt")) != instant(row.getValue("updated_at")) ||
            instant(body.getValue("completedAt")) != instant(row.getValue("updated_at")) ||
            sha(row.getValue("plan_snapshot_text").jsonPrimitive.content) != row.getValue("plan_snapshot_hash").jsonPrimitive.content)
            unavailable()
        // A status string alone is not proof of the real completion transaction.
        val event = connection.prepareStatement("SELECT to_jsonb(e)::text FROM cooking.step_events e " +
            "WHERE environment=? AND actor_kind='guest' AND principal_id=? AND session_id=? AND kind='completed'").use { s ->
            s.setString(1, environment); s.setObject(2, actual.principalId); s.setObject(3, id)
            s.executeQuery().use { r ->
                if (!r.next()) unavailable()
                Json.parseToJsonElement(r.getString(1)).jsonObject.also { if (r.next()) unavailable() }
            }
        }
        val payload = event.getValue("payload").jsonObject
        if (validator.validateRequest("completeCookSession", payload.toString().toByteArray(), "application/json") !is BodyValidationResult.Valid ||
            number(event.getValue("device_sequence")) != number(body.getValue("deviceSequence")) ||
            number(event.getValue("session_version")) != number(body.getValue("version")) ||
            number(payload.getValue("deviceSequence")) != number(body.getValue("deviceSequence")) ||
            UUID.fromString(event.getValue("device_identity").jsonPrimitive.content) != principal.guestSessionId ||
            instant(event.getValue("accepted_at")) != instant(body.getValue("completedAt"))) unavailable()
        val command = CommandIdentity(PrincipalScope(environment, CommandActor.GUEST, actual.principalId),
            "completeCookSession", UUID.fromString(event.getValue("command_id").jsonPrimitive.content),
            mapOf("sessionId" to id.toString()), body = payload)
        if (command.requestHash != event.getValue("request_hash").jsonPrimitive.content) unavailable()
        // Capture the entire event, not just its version: deletion/reinsertion during a
        // same-connection policy callback must not replace the proven completion action.
        return JsonObject(row + ("_completionEvent" to event))
    }

    private fun validateScoped(context: FeedbackTargetContext, cookId: UUID, planId: UUID, recipe: JsonObject) {
        val valid = when (context.kind) {
            "cookSession" -> context.resourceId == cookId
            "plan" -> context.resourceId == planId
            "recipeVersion" -> context.resourceId == UUID.fromString(recipe.getValue("id").jsonPrimitive.content)
            "ingredient" -> recipe.getValue("ingredients").jsonArray.any {
                UUID.fromString(it.jsonObject.getValue("ingredientId").jsonPrimitive.content) == context.resourceId }
            "taste" -> recipe.getValue("tasteTags").jsonArray.any { it.jsonPrimitive.content == context.tag }
            // These are user-reported burden dimensions, not editorial preparationTags.
            "preparation" -> context.tag in setOf("chopping", "activeCooking", "cleanup")
            else -> false
        }
        if (!valid) targetUnavailable()
    }

    private inner class Proof(val context: FeedbackTargetContext, override val snapshot: JsonObject,
        private val checks: List<() -> Unit>) : FeedbackTargetEvidence {
        private var failed = false
        override fun revalidate(connection: Connection, actor: VerifiedFeedbackPrincipal, context: FeedbackTargetContext) {
            if (failed || context !== this.context) unavailable()
            try { bound(connection, actor); checks.forEach { it() }; bound(connection, actor) }
            catch (failure: Throwable) { failed = true; throw failure }
        }
    }

    private fun bound(c: Connection, actor: VerifiedFeedbackPrincipal) {
        current()
        if (c !== connection || actor !== principal || Thread.currentThread() !== thread || c.isClosed || c.autoCommit ||
            transactionId() != transaction || actor.guestSessionId != guestId()) unauthenticated()
    }
    private fun guestId(): UUID = connection.prepareStatement("SELECT g.id FROM identity.principals p " +
        "JOIN identity.guest_sessions g ON g.environment=p.environment AND g.id=p.guest_session_id " +
        "WHERE p.environment=? AND p.id=? AND p.kind='guest' AND p.status='active' " +
        "AND g.revoked_at IS NULL AND g.merged_to_user_id IS NULL FOR SHARE OF p,g").use { s ->
        s.setString(1, environment); s.setObject(2, actual.principalId)
        s.executeQuery().use { r -> if (!r.next()) unauthenticated(); r.getObject(1, UUID::class.java).also { if (r.next()) unavailable() } }
    }
    private fun transactionId(): Long {
        current()
        if (connection.isClosed || connection.autoCommit) unauthenticated()
        return connection.createStatement().use { s -> s.executeQuery("SELECT txid_current()").use { r ->
            if (!r.next()) unavailable(); r.getLong(1).also { if (r.next()) unavailable() }
        } }
    }
    private fun current() { if (Thread.currentThread().isInterrupted) throw InterruptedException("Guest feedback interrupted") }
    private fun number(value: JsonElement) = BigDecimal(value.jsonPrimitive.content).longValueExact()
    private fun instant(value: JsonElement) = java.time.OffsetDateTime.parse(value.jsonPrimitive.content).toInstant()
    private fun invalid(): Nothing = throw FeedbackFailure(FeedbackFailureCode.INPUT_INVALID)
    private fun unauthenticated(): Nothing = throw FeedbackFailure(FeedbackFailureCode.UNAUTHENTICATED)
    private fun targetUnavailable(): Nothing = throw FeedbackFailure(FeedbackFailureCode.TARGET_UNAVAILABLE)
    private fun unavailable(): Nothing = throw FeedbackFailure(FeedbackFailureCode.STORAGE_UNAVAILABLE)
    override fun toString() = "GuestFeedbackAccess(<redacted>)"

    companion object {
        private val validator by lazy { ContractBodyValidator.bundled() }
        internal fun compatibility(c: Connection, version: Int, name: String) {
            val expected = GuestFeedbackAccess::class.java.getResourceAsStream("/db/migration/V${version.toString().padStart(3, '0')}__$name.sql")
                ?.use { sha(it.readBytes().decodeToString()) } ?: throw FeedbackFailure(FeedbackFailureCode.NOT_CONFIGURED)
            c.prepareStatement("SELECT description,checksum FROM platform.schema_migrations WHERE version=?").use { s ->
                s.setInt(1, version); s.executeQuery().use { r ->
                    if (!r.next() || r.getString(1) != name || r.getString(2) != expected || r.next())
                        throw FeedbackFailure(FeedbackFailureCode.NOT_CONFIGURED)
                }
            }
        }
        private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        private fun canonical(value: JsonElement): String = when (value) {
            is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { "${JsonPrimitive(it.key)}:${canonical(it.value)}" }
            is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
            is JsonPrimitive -> if (value.isString || value == JsonNull || value.booleanOrNull != null) value.toString()
                else BigDecimal(value.content).stripTrailingZeros().toPlainString()
        }
    }
}
