package com.feedme.server.reuse

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.PortResult
import com.feedme.planning.*
import com.feedme.server.catalog.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.cooking.AccountMealAccess
import com.feedme.server.db.CommandActor
import com.feedme.server.kitchen.VerifiedKitchenPrincipal
import com.feedme.server.planning.*
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.json.*

/** Same-transaction account/private-input/catalog evidence. A Plan reference reports only
 * the selected portion identity; it never confirms its amount, temperature, age or freshness. */
internal class AccountReuseEvidence(private val c: Connection, private val environment: String,
    private val account: AccountMealAccess, private val actor: VerifiedPlanningPrincipal, private val plans: PlansStore,
    private val catalog: RecipeCatalogJournal, private val ingredients: IngredientCatalogStore,
    private val request: JsonObject, private val policy: AccountReusePolicy, private val planningPolicy: PlanningServicePolicy) {
    private val loader = AccountPlanningInputs(environment)
    private val kitchen = VerifiedKitchenPrincipal(environment, CommandActor.ACCOUNT, actor.principalId, actor.deviceSessionId)
    val inputs = loader.lock(c, kitchen)
    private val portionId = request["preparedPortionPlanId"]?.jsonPrimitive?.content?.let(::reuseUuid)
    private val portion = portionId?.let(::portion)
    val recipes = catalog.openView(c)
    val relationships = ReuseRelationshipView(c, environment, recipes, policy.maxRelationships)
    private val explicit = request["ingredientIds"]?.jsonArray?.map { reuseUuid(it.jsonPrimitive.content) }?.toSet().orEmpty()
    private val sourceIngredients = if (portion == null) emptySet() else portion.getValue("recipeSnapshot").jsonObject
        .getValue("ingredients").jsonArray.filter { it.jsonObject["optional"] != JsonPrimitive(true) }.map { it.jsonObject.reuseId("ingredientId") }.toSet()
    private val policyDocument = buildJsonObject { put("reuse", policy.document()); put("rankingVersion", planningPolicy.rankingVersion)
        put("heatEnabled", planningPolicy.heatEnabled); put("improveEnabled", planningPolicy.improveEnabled) }
    val policyHash = reuseSha(reuseCanonical(policyDocument))
    init {
        account.current(c); ingredients.checkCompatibility(c)
        val current = ingredients.current(c)
        if (explicit.any { id -> current.original.items.none { it.id == id && it.reviewed && it.published && it.freeAccess } })
            reuseFail(ReuseFailureCode.SOURCE_UNAVAILABLE)
        val constraintIds = request.getValue("constraints").jsonObject.getValue("ingredientIds").jsonArray.map { reuseUuid(it.jsonPrimitive.content) }.toSet()
        if (!constraintIds.containsAll(explicit)) reuseFail(ReuseFailureCode.INPUT_INVALID)
    }
    fun create(): JsonObject {
        val constraints = request.getValue("constraints").jsonObject
        val matching = relationships.relationships.filter { eligible(it) }.groupBy { it.targetId }
        val source = RecipePlanningCatalogSource(recipes)
        val ranked = mutableListOf<Pair<PlanningRank, JsonObject>>()
        val planRequest = buildJsonObject { put("mode", "auto"); put("constraints", constraints)
            put("preferenceVersion", Json.parseToJsonElement(inputs.preferenceRevision)) }
        val result = StreamingPlanner(PlanningPolicy(planningPolicy.rankingVersion, planningPolicy.heatEnabled, planningPolicy.improveEnabled))
            .scanPreferred(WireDocument.parse(planRequest.toString()), inputs.context(), source.header, source,
                PlanningScanBudget(policy.maxCandidates, policy.maxCatalogPages), pageSize = 16,
                classify = { candidate -> if (UUID.fromString(candidate.recipe.id.value) in matching) PlanningScanPreference.PRIMARY else PlanningScanPreference.EXCLUDED },
                onEligible = { candidate, rank, _ ->
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Reuse scan interrupted")
                    val actual = source.sourceOf(candidate)
                    val edge = matching.getValue(actual.entry.recipeVersionId).sortedWith(compareBy<ReviewedReuseRelationship> { it.extraMinutes }.thenBy { it.id.toString() }).first()
                    requireReuseTarget(actual, edge)
                    val shared = shared(edge)
                    if (shared.isEmpty()) reuseFail()
                    val option = buildJsonObject { put("recipeVersionId", edge.targetId.toString()); put("title", actual.entry.recipe.getValue("title"))
                        put("extraPreparationMinutes", edge.extraMinutes); put("sharedIngredientIds", JsonArray(shared.sortedBy(UUID::toString).map { JsonPrimitive(it.toString()) })) }
                    val proof = buildJsonObject { put("relationshipId", edge.id.toString()); put("relationshipSha256", edge.sha256)
                        put("recipeVersionId", edge.targetId.toString()); put("materialSha256", edge.targetMaterial); put("option", option) }
                    ranked += rank to proof
                    if (ranked.size > policy.maxRelationships) reuseFail(ReuseFailureCode.NOT_CONFIGURED)
                })
        val scan = when (result) { is PortResult.Value -> result.value; is PortResult.Failure -> reuseFail(
            if (result.reason == com.feedme.core.ports.FailureReason.INVALID_DATA) ReuseFailureCode.INPUT_INVALID else ReuseFailureCode.NOT_CONFIGURED) }
        if (scan.decision.status == PlanningStatus.NEEDS_CONFIRMATION) reuseFail(ReuseFailureCode.INPUTS_CHANGED)
        source.checkCurrent()
        val sorted = ranked.sortedWith { a, b -> a.first.compareTo(b.first) }.map { it.second }
        if (sorted.map { it.reuseId("recipeVersionId") }.distinct().size != sorted.size) reuseFail()
        return buildJsonObject {
            put("formatVersion", 1); put("policy", policyDocument); put("policySha256", policyHash)
            put("inputs", reuseJson(inputs.copyForStorage().encodeUtf8().decodeToString()))
            put("portion", portion ?: JsonNull)
            put("catalog", buildJsonObject { put("releaseId", recipes.releaseId.toString()); put("revision", recipes.revision)
                put("requestSha256", recipes.requestSha256); put("taxonomyRevision", recipes.taxonomyRevision)
                put("taxonomySha256", recipes.taxonomySha256); put("versionCount", recipes.versionCount) })
            put("options", JsonArray(sorted))
        }.also { if (reuseCanonical(it).encodeToByteArray().size > 1048576) reuseFail(ReuseFailureCode.RESPONSE_TOO_LARGE); current(it) }
    }
    fun current(evidence: JsonObject) {
        account.current(c)
        if (evidence.keys != setOf("formatVersion","policy","policySha256","inputs","portion","catalog","options") ||
            evidence["formatVersion"] != JsonPrimitive(1) || evidence["policy"] != policyDocument ||
            evidence["policySha256"] != JsonPrimitive(policyHash) ||
            evidence["inputs"] != reuseJson(inputs.copyForStorage().encodeUtf8().decodeToString()) ||
            !inputs.samePrivateInputs(loader.lock(c, kitchen)) || evidence["portion"] != (portion ?: JsonNull) ||
            portionId?.let { portion(it) != portion } == true) reuseFail(ReuseFailureCode.INPUTS_CHANGED)
        val anchor = evidence.getValue("catalog").jsonObject
        recipes.verifyAnchor(anchor.reuseId("releaseId"), anchor.getValue("revision").jsonPrimitive.long,
            anchor.reuseText("requestSha256"), anchor.reuseText("taxonomyRevision"), anchor.reuseText("taxonomySha256"), anchor.getValue("versionCount").jsonPrimitive.long)
        // A page is the original ranked result, not a fresh proposal. Conservatively
        // refuse any catalog revision change (including changed effort/composition
        // review) instead of silently reranking or retaining stale hard-filter evidence.
        if (anchor.reuseId("releaseId") != recipes.releaseId || anchor.getValue("revision").jsonPrimitive.long != recipes.revision ||
            anchor.reuseText("requestSha256") != recipes.requestSha256 || anchor.reuseText("taxonomyRevision") != recipes.taxonomyRevision ||
            anchor.reuseText("taxonomySha256") != recipes.taxonomySha256 || anchor.getValue("versionCount").jsonPrimitive.long != recipes.versionCount)
            reuseFail(ReuseFailureCode.SOURCE_UNAVAILABLE)
        val options = evidence.getValue("options").jsonArray
        if (options.size > policy.maxRelationships) reuseFail()
        val ids = mutableSetOf<UUID>()
        for (item in options) {
            val retained = item.jsonObject
            if (retained.keys != setOf("relationshipId","relationshipSha256","recipeVersionId","materialSha256","option")) reuseFail()
            val edge = relationships.requireRetained(retained.reuseId("relationshipId"), retained.reuseText("relationshipSha256"))
            if (!eligible(edge) || retained["recipeVersionId"] != JsonPrimitive(edge.targetId.toString()) ||
                retained["materialSha256"] != JsonPrimitive(edge.targetMaterial) || !ids.add(edge.targetId)) reuseFail(ReuseFailureCode.SOURCE_UNAVAILABLE)
            val target = recipes.lookupCurrent(edge.targetId) ?: reuseFail(ReuseFailureCode.SOURCE_UNAVAILABLE)
            requireReuseTarget(target, edge)
            val expected = buildJsonObject { put("recipeVersionId", edge.targetId.toString()); put("title", target.entry.recipe.getValue("title"))
                put("extraPreparationMinutes", edge.extraMinutes); put("sharedIngredientIds", JsonArray(shared(edge).sortedBy(UUID::toString).map { JsonPrimitive(it.toString()) })) }
            if (retained["option"] != expected) reuseFail(ReuseFailureCode.SOURCE_UNAVAILABLE)
        }
        relationships.current(); recipes.checkCurrent(); account.current(c)
    }
    private fun shared(edge: ReviewedReuseRelationship): Set<UUID> = edge.shared.intersect(
        if (edge.sourceKind == "portion" && explicit.isEmpty()) sourceIngredients else explicit)
    private fun eligible(edge: ReviewedReuseRelationship): Boolean {
        if (edge.policyRevision != planningPolicy.rankingVersion || shared(edge).isEmpty() ||
            edge.servings.compareTo(request.getValue("constraints").jsonObject.getValue("servings").jsonPrimitive.content.toBigDecimal()) != 0) return false
        if (edge.sourceKind == "ingredient" && edge.source.reuseId("ingredientId") !in explicit) return false
        if (edge.sourceKind == "portion") {
            if (portion == null || edge.source["recipeVersionId"] != portion["recipeVersionId"] || !sourceIngredients.containsAll(edge.shared)) return false
            val original = recipes.lookupCurrent(edge.source.reuseId("recipeVersionId")) ?: return false
            if (!reuseVisible(original.entry) || original.entry.materialSha256 != edge.source.reuseText("materialSha256")) return false
        }
        val target = recipes.lookupCurrent(edge.targetId) ?: return false
        return reuseVisible(target.entry) && target.entry.materialSha256 == edge.targetMaterial && recipeIngredients(target.entry.recipe).containsAll(edge.shared)
    }
    private fun portion(id: UUID): JsonObject = c.prepareStatement("SELECT snapshot_text,snapshot_hash,status,recipe_version_id,storage_format FROM planning.plans " +
        "WHERE environment=? AND actor_kind='account' AND principal_id=? AND id=? FOR SHARE").use { s ->
        s.setString(1, environment); s.setObject(2, actor.principalId); s.setObject(3, id)
        s.executeQuery().use { r ->
            if (!r.next()) reuseFail(ReuseFailureCode.SOURCE_UNAVAILABLE)
            val text = r.getString(1); val hash = r.getString(2); val status = r.getString(3); val recipeId = r.getObject(4, UUID::class.java); val format = r.getInt(5)
            if (r.next() || reuseSha(text) != hash || validator.validateSchema("Plan", text.encodeToByteArray()) != BodyValidationResult.Valid) reuseFail()
            val body = reuseJson(text, 262144)
            if (body["id"] != JsonPrimitive(id.toString()) || status != "ready" || body["status"] != JsonPrimitive("ready") ||
                body["recipeVersionId"] != JsonPrimitive(recipeId?.toString())) reuseFail(ReuseFailureCode.SOURCE_UNAVAILABLE)
            // Existing actual account source authority supports these formats. Refuse older
            // or guest formats rather than invent a historical content/portion grant.
            if (format !in setOf(3,4,5)) reuseFail(ReuseFailureCode.NOT_CONFIGURED)
            if (plans.lockOwnedDerivedCopy(c, actor, id) != body.getValue("recipeSnapshot")) reuseFail()
            body
        }
    }
    override fun toString() = "AccountReuseEvidence(<redacted>)"
    private companion object { val validator by lazy { ContractBodyValidator.bundled() } }
}
