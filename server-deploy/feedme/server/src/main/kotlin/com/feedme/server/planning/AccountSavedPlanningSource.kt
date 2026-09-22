package com.feedme.server.planning

import com.feedme.server.catalog.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.memory.accountSavedRecipeHash
import com.feedme.server.memory.requireAccountSavedMaterial
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.json.*

/** One real account transaction's exact Saved source. The historical recipe is material, not
 * cooking authority: independent retained-copy rights/current recall are checked here, then
 * current private inputs and constraints still pass through the deterministic planner.
 * No origin Plan read or new-copy grant is required. Ordinary catalog retirement and expiry
 * of newCopiesUntil are allowed; explicit retained expiry/revocation continue to deny access. */
internal class AccountSavedPlanningSource(private val environment: String, private val connection: Connection,
    private val principalId: UUID, private val rights: RecipeCopyRightsStore) {
    private class Retained(val identity: JsonObject, val recipe: JsonObject, val grant: RecipeCopyRightsHandle)
    private val retained = linkedMapOf<UUID, Retained>()

    fun load(id: UUID): Pair<JsonObject, JsonObject> {
        val selected = retained(id)
        // Ordinary Saved cooking consumes the established bounded legacy projection.
        // Full source publication/rights remain available only through this owned resolver.
        return selected.identity to buildJsonObject {
            put("recipe", selected.recipe); put("review", legacyPlanningReview(selected.grant.source.entry.review))
        }
    }

    /** Exact copy material plus the ACTUAL retained grant's original publication. This is
     * not adaptation permission: callers must separately own current published source/edge. */
    fun material(id: UUID): AccountSavedPlanningMaterial = retained(id).let {
        AccountSavedPlanningMaterial(it.identity, it.recipe, it.grant.source)
    }

    private fun retained(id: UUID): Retained {
        val row = read(id)
        val previous = retained[id]
        val grant = previous?.grant ?: rights.openExisting(connection, row.first.getValue("copyEvidence").jsonObject
            .getValue("grant").jsonObject, guest = false)
        val identity = JsonObject(row.first + ("allowReviewedScaling" to JsonPrimitive(grant.allowReviewedScaling)))
        if (previous != null && (previous.identity != identity || previous.recipe != row.second)) unavailable()
        val selected = previous ?: Retained(identity, row.second, grant).also { retained[id] = it }
        validate(selected)
        return selected
    }

    fun revalidate() {
        retained.forEach { (id, selected) ->
            val row = read(id)
            if (JsonObject(row.first + ("allowReviewedScaling" to JsonPrimitive(selected.grant.allowReviewedScaling))) != selected.identity ||
                row.second != selected.recipe) unavailable()
            validate(selected)
        }
    }
    fun checkAt(at: Instant) = retained.values.forEach { it.grant.checkAt(connection, at) }

    private fun validate(selected: Retained) {
        val grant = selected.grant; val identity = selected.identity
        grant.revalidate(connection)
        if (identity.text("recipeVersionId") != grant.recipeVersionId.toString() ||
            identity.text("contentLicense") != (if (identity.text("sourceType") == "postGrant") "privateCopyOnly" else grant.contentLicense) ||
            identity.getValue("copyEvidence").jsonObject.getValue("grant") != grant.evidence) unavailable()
        requireAccountSavedMaterial(grant.source.entry, selected.recipe,
            grant.allowReviewedScaling && identity.text("sourceType") == "ownPlan")
    }

    private fun read(id: UUID): Pair<JsonObject, JsonObject> = connection.prepareStatement(
        "SELECT * FROM memory.saved_recipes WHERE environment=? AND actor_kind='account' AND principal_id=? AND id=? FOR SHARE").use { statement ->
        statement.setString(1, environment); statement.setObject(2, principalId); statement.setObject(3, id)
        statement.executeQuery().use { row ->
            if (!row.next() || row.getBoolean("deleted")) unavailable()
            val document = Json.parseToJsonElement(row.getString("snapshot")).jsonObject
            if (document["recalled"] == JsonPrimitive(true)) throw PlanningServiceFailure(PlanningFailureCode.RECIPE_RECALLED)
            val recipe = document.getValue("snapshot").jsonObject
            val evidence = Json.parseToJsonElement(row.getString("copy_evidence")).jsonObject
            val recipeId = row.getObject("recipe_version_id", UUID::class.java)
            val version = row.getLong("version"); val generation = row.getLong("generation")
            val hash = row.getString("recipe_hash"); val sourceType = row.getString("source_type")
            val sourceId = row.getObject("source_id", UUID::class.java); val origin = row.getObject("origin_plan_id", UUID::class.java)
            val license = row.getString("content_license")
            val postGrant = sourceType == "postGrant"
            if (validator.validateResponse("getSavedRecipe", 200, document.toString().encodeToByteArray(), "application/json") != BodyValidationResult.Valid ||
                document["id"] != JsonPrimitive(id.toString()) || document["version"]?.jsonPrimitive?.long != version ||
                version < 1 || generation < 1 || recipe["id"] != JsonPrimitive(recipeId.toString()) ||
                accountSavedRecipeHash(recipe) != hash || document["sourceType"] != JsonPrimitive(sourceType) ||
                sourceType !in setOf("catalog", "ownPlan", "postGrant") || (sourceType == "ownPlan") != (origin != null) ||
                (!postGrant && sourceId != (origin ?: recipeId)) || document["contentLicense"] != JsonPrimitive(license) ||
                license !in setOf("catalogRedistributable", "privateCopyOnly") ||
                document["createdAt"] != JsonPrimitive(row.getObject("created_at", OffsetDateTime::class.java).toInstant().toString()) ||
                document["updatedAt"] != JsonPrimitive(row.getObject("updated_at", OffsetDateTime::class.java).toInstant().toString()) ||
                evidence.keys != (setOf("formatVersion", "grant", "recipeHash") + if (postGrant) setOf("postGrant") else emptySet()) ||
                evidence["formatVersion"] != JsonPrimitive(if (postGrant) 2 else 1) ||
                evidence["recipeHash"] != JsonPrimitive(hash) || row.getObject("deletion_key") != null)
                throw PlanningServiceFailure(PlanningFailureCode.STORAGE_UNAVAILABLE)
            if (postGrant) {
                val grant = evidence["postGrant"] as? JsonObject ?: unavailable()
                if (license != "privateCopyOnly" ||
                    grant.keys != setOf("id", "postId", "postVersion", "authorId", "creatorLabel", "policyVersion", "disclosureVersion", "authorizedAt", "rights") ||
                    grant["postId"] != JsonPrimitive(sourceId.toString()) || document["sourcePostId"] != grant["postId"] ||
                    document["grantId"] != grant["id"] || document["creatorLabel"] != grant["creatorLabel"] ||
                    grant["rights"] != JsonPrimitive("PRIVATE_RECIPE_COPY") ||
                    (grant["postVersion"]?.jsonPrimitive?.longOrNull ?: 0) < 1 ||
                    (grant["policyVersion"]?.jsonPrimitive?.longOrNull ?: 0) < 1) unavailable()
                UUID.fromString(grant.getValue("id").jsonPrimitive.content)
                UUID.fromString(grant.getValue("authorId").jsonPrimitive.content)
                Instant.parse(grant.getValue("authorizedAt").jsonPrimitive.content)
                // An authorized recipient copy is independent of current source visibility,
                // membership and future-save policy. Actual retained catalog rights/recall
                // remain mandatory in validate(); this is not redistribution permission.
            }
            val identity = buildJsonObject {
                put("environment", environment); put("principalId", principalId.toString()); put("savedRecipeId", id.toString())
                put("generation", generation.toString()); put("version", version.toString()); put("recipeVersionId", recipeId.toString())
                put("recipeHash", hash); put("sourceType", sourceType); put("sourceId", sourceId.toString())
                put("originPlanId", origin?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                put("contentLicense", license); put("copyEvidence", evidence)
            }
            if (row.next()) throw PlanningServiceFailure(PlanningFailureCode.STORAGE_UNAVAILABLE)
            identity to recipe
        }
    }
    private fun unavailable(): Nothing = throw PlanningServiceFailure(PlanningFailureCode.RECIPE_UNAVAILABLE)
    override fun toString() = "AccountSavedPlanningSource(<redacted>)"
    companion object { private val validator by lazy { ContractBodyValidator.bundled() } }
}

internal class AccountSavedPlanningMaterial internal constructor(val identity: JsonObject,
    val recipe: JsonObject, val original: RecipeCatalogVersion) {
    fun evidence() = buildJsonObject {
        put("identity", identity); put("recipeText", recipe.toString()); put("originalSource", dpVersion(original))
    }
    override fun toString() = "AccountSavedPlanningMaterial(<redacted>)"
}
