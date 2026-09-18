package com.feedme.server.planning

import com.feedme.contracts.*
import com.feedme.core.ports.PortResult
import com.feedme.planning.*
import com.feedme.server.catalog.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Original command data, not principal verification or permission to derive a Plan. */
internal class DerivedPlanCommand(val principal: VerifiedPlanningPrincipal, val operationId: String,
    val key: UUID, val parentId: UUID, val originalIfMatch: String, val body: WireDocument) {
    val identity: CommandIdentity
    val bodySha256: String get() = digest(body.encodeUtf8())
    init {
        identity = dpFormat {
            require(operationId in setOf("adaptPlan", "simplifyPlan"))
            require(originalIfMatch.length <= 1024 && originalIfMatch.matches(Regex("\"[0-9]+\"")))
            dpRequest(operationId, body)
            CommandIdentity(PrincipalScope(principal.environment, principal.kind, principal.principalId), operationId,
                key, mapOf("planId" to parentId.toString()), body = dpJson(body), ifMatch = originalIfMatch)
        }
    }
    override fun toString() = "DerivedPlanCommand(<redacted>)"
}

/** Exact image supplied by the actual row-locking owner. Hash consistency is structural only:
 * this type neither reads a row nor verifies its owner, lifetime, proof format or source grant. */
internal class DerivedPlanParent private constructor(val snapshotText: String, val snapshotHash: String,
    val proofText: String, val proofHash: String, val requestText: String, val requestHash: String,
    val planningRequestText: String,
    val plan: PlanWire, val request: WireDocument) {
    val id: UUID = UUID.fromString(plan.id.value)
    val version: BigInteger = dpInteger(plan.version.jsonToken)
    val recipeVersionId: UUID = UUID.fromString((plan.recipeVersionId as WireField.Value).value.value)
    val planningRequestHash: String get() = digest(request.encodeUtf8())
    override fun toString() = "DerivedPlanParent(<redacted>)"
    companion object {
        fun fromStored(snapshotText: String, snapshotHash: String, proofText: String, proofHash: String,
            requestText: String, requestHash: String, planningRequestText: String = requestText): DerivedPlanParent = dpFormat {
            val snapshot = dpDocument(snapshotText, snapshotHash, 262_144)
            require(dpValidator.validateSchema("Plan", snapshot.encodeUtf8()) == BodyValidationResult.Valid)
            val plan = PlanWire.from(snapshot)
            require(plan.status == "ready" && plan.recipeVersionId is WireField.Value && plan.recipeSnapshot is WireField.Value)
            val recipe = (plan.recipeSnapshot as WireField.Value).value
            require((plan.recipeVersionId as WireField.Value).value.value == recipe.id.value)
            require(dpInteger(plan.version.jsonToken) > BigInteger.ZERO)
            dpDocument(proofText, proofHash, 32_768)
            val original = dpDocument(requestText, requestHash, 65_536)
            require(dpValidator.validateSchema("PlanRequest", original.encodeUtf8()) == BodyValidationResult.Valid ||
                dpValidator.validateSchema("AdaptRequest", original.encodeUtf8()) == BodyValidationResult.Valid)
            val request = WireDocument.parse(planningRequestText, WireLimits(65_536, 32))
            require(dpValidator.validateSchema("PlanRequest", request.encodeUtf8()) == BodyValidationResult.Valid)
            require(dpSemantic(dpJson(snapshot).getValue("constraints")) == dpSemantic(dpJson(request).getValue("constraints")))
            DerivedPlanParent(snapshotText, snapshotHash, proofText, proofHash, requestText, requestHash, planningRequestText, plan, request)
        }
    }
}

internal class DerivedPlanReceipt(val planId: UUID, val createdAt: Instant, val expiresAt: Instant) {
    init { dpFormat { dpTime(createdAt); dpTime(expiresAt); require(expiresAt > createdAt) } }
    override fun toString() = "DerivedPlanReceipt(<redacted>)"
}

/** Fresh construction only from the typed complete selector results. These inputs remain
 * provisional to their actual caller-owned transaction. In particular, the parent snapshot
 * hash and unscaled catalog material hash are deliberately separate: this pure seam does NOT
 * prove the parent's scaling derivation, current authorization, copy rights or catalog locks. */
internal class DerivedPlanMaterial private constructor(val command: DerivedPlanCommand, val parent: DerivedPlanParent,
    private val retained: DerivedPlanStoredRecord) {
    val planId get() = retained.planId
    val parentId get() = retained.parentId
    val recipeVersionId get() = retained.recipeVersionId
    val status get() = retained.status
    val createdAt get() = retained.createdAt
    val expiresAt get() = retained.expiresAt
    val snapshotText get() = retained.snapshotText
    val snapshotHash get() = retained.snapshotHash
    val contextText get() = retained.contextText
    val contextHash get() = retained.contextHash
    val proofText get() = retained.proofText
    val proofHash get() = retained.proofHash
    val reply get() = retained.reply
    override fun toString() = "DerivedPlanMaterial(<redacted>)"

    companion object {
        fun fromAdaptation(command: DerivedPlanCommand, parent: DerivedPlanParent, inputs: PlanningPrivateInputsSnapshot,
            policy: PlanningPolicy, anchor: RecipeCatalogAnchor, scan: RecipeSubstitutionScanResult,
            receipt: DerivedPlanReceipt): DerivedPlanMaterial = dpFormat {
            require(command.operationId == "adaptPlan")
            require(scan.originalAdaptation?.encodeUtf8()?.contentEquals(command.body.encodeUtf8()) == true)
            val effective = dpEffectiveRequest(command, parent)
            require(dpSemantic(dpJson(scan.originalRequest)) == dpSemantic(dpJson(effective)))
            val selection = scan.selection
            require((scan.decision.status == PlanningStatus.READY) == (selection != null))
            val changes = if (selection != null) {
                val definition = selection.edge.record.definition
                require(selection.edge.record.status == "reviewed" && definition.policyVersion == policy.version)
                require(selection.source.entry.materialSha256 == scan.source.entry.materialSha256)
                require(selection.source.entry.recipeVersionId == scan.source.entry.recipeVersionId)
                require(selection.target.entry.recipeVersionId.toString() == scan.decision.recipe?.id?.value)
                require(definition.comparisonServings.compareTo(dpServings(command.body)) == 0)
                val comparison = validateRecipeSubstitutionPair(selection.source.entry, selection.target.entry, definition)
                require(comparison.source.materialSha256 == scan.source.entry.materialSha256)
                val body = dpJson(command.body)
                body["replaceIngredientId"]?.let { require(UUID.fromString(it.jsonPrimitive.content) == definition.fromIngredientId) }
                body["requestedReplacementId"]?.let { require(UUID.fromString(it.jsonPrimitive.content) == definition.toIngredientId) }
                body["retainTasteTag"]?.let { require(it.jsonPrimitive.content in definition.preservedTags) }
                require(selection.target.entry.recipeVersionId !in body["excludeRecipeVersionIds"]?.jsonArray.orEmpty().map { UUID.fromString(it.jsonPrimitive.content) })
                val authored = buildJsonObject {
                    put("fromIngredientId", definition.fromIngredientId.toString()); put("toIngredientId", definition.toIngredientId.toString())
                    put("substitutionId", definition.id.toString()); put("explanation", definition.explanation)
                }
                require(scan.changes.size == 1 && dpJson(scan.changes.single()) == authored)
                listOf(authored)
            } else { require(scan.changes.isEmpty()); emptyList() }
            require(scan.inspectedEdgeCount >= 0 && scan.edgePageCount >= 0)
            val provenance = buildJsonObject {
                put("kind", "adaptation"); put("source", dpVersion(scan.source))
                selection?.let {
                    put("target", dpVersion(it.target))
                    put("edge", buildJsonObject {
                        put("publicationId", it.edge.publicationId.toString()); put("revision", it.edge.revision.toString())
                        put("requestSha256", it.edge.requestSha256); put("recordText", it.edge.record.document.toString())
                        put("recordSha256", it.edge.record.sha256); put("definitionSha256", it.edge.record.definition.sha256)
                        put("catalogRevision", it.edge.catalogRevision.toString()); put("catalogReleaseId", it.edge.catalogReleaseId.toString())
                        put("catalogRequestSha256", it.edge.catalogRequestSha256)
                    })
                }
                put("inspectedEdgeCount", scan.inspectedEdgeCount.toString()); put("edgePageCount", scan.edgePageCount.toString())
            }
            build(command, parent, inputs, policy, anchor, scan.decision, scan.source, selection?.target,
                scan.traversedCount, scan.eligibleCount, effective, changes, provenance, receipt)
        }

        fun fromSimplification(command: DerivedPlanCommand, parent: DerivedPlanParent, inputs: PlanningPrivateInputsSnapshot,
            policy: PlanningPolicy, anchor: RecipeCatalogAnchor, scan: RecipeSimplificationScanResult,
            receipt: DerivedPlanReceipt): DerivedPlanMaterial = dpFormat {
            require(command.operationId == "simplifyPlan")
            val body = dpJson(command.body); val goal = body.getValue("simplificationGoal").jsonPrimitive.content
            val selection = scan.selection
            require((scan.decision.status == PlanningStatus.READY) == (selection != null))
            selection?.let {
                val comparison = compareRecipeEffort(scan.source.entry, it.target.entry, dpServings(command.body))
                require(comparison.improves(goal))
                require(comparison.sourceMaterialSha256 == it.comparison.sourceMaterialSha256 &&
                    comparison.targetMaterialSha256 == it.comparison.targetMaterialSha256 &&
                    comparison.comparisonServings.compareTo(it.comparison.comparisonServings) == 0)
                when (it.kind) {
                    RecipeSimplificationSelectionKind.REVIEWED_VARIANT -> {
                        val relationship = requireNotNull(it.reviewedRelationship)
                        require(relationship.source.entry.materialSha256 == scan.source.entry.materialSha256 &&
                            relationship.target.entry.materialSha256 == it.target.entry.materialSha256)
                        val evidence = relationship.comparison.evidence
                        require(goal in evidence.goals && evidence.comparisonServings.compareTo(dpServings(command.body)) == 0)
                        validateRecipeSimplificationPair(scan.source.entry, it.target.entry, evidence)
                    }
                    RecipeSimplificationSelectionKind.DIFFERENT_MEAL -> {
                        require(body["allowDifferentMeal"] == JsonPrimitive(true) && it.reviewedRelationship == null)
                        require(scan.source.entry.recipe.getValue("recipeId") != it.target.entry.recipe.getValue("recipeId"))
                    }
                }
            }
            val provenance = buildJsonObject {
                put("kind", "simplification"); put("source", dpVersion(scan.source)); put("goal", goal)
                put("allowDifferentMeal", body["allowDifferentMeal"] == JsonPrimitive(true))
                selection?.let { selected ->
                    put("target", dpVersion(selected.target)); put("selectionKind", selected.kind.name)
                    selected.reviewedRelationship?.let { put("reviewReference", it.comparison.evidence.reviewReference) }
                }
            }
            build(command, parent, inputs, policy, anchor, scan.decision, scan.source, selection?.target,
                scan.traversedCount, scan.eligibleCount, dpEffectiveRequest(command, parent), emptyList(), provenance, receipt)
        }

        private fun build(command: DerivedPlanCommand, parent: DerivedPlanParent, inputs: PlanningPrivateInputsSnapshot,
            policy: PlanningPolicy, anchor: RecipeCatalogAnchor, decision: PlanningDecision, source: RecipeCatalogVersion,
            target: RecipeCatalogVersion?, traversed: Long, eligible: Long, effective: WireDocument,
            changes: List<JsonObject>, provenance: JsonObject, receipt: DerivedPlanReceipt): DerivedPlanMaterial {
            require(command.parentId == parent.id && dpInteger(command.originalIfMatch.removeSurrounding("\"")) == parent.version)
            require(receipt.planId != parent.id)
            require(receipt.createdAt >= Instant.parse(dpJson(parent.plan.document).getValue("createdAt").jsonPrimitive.content))
            require(source.entry.recipeVersionId == parent.recipeVersionId)
            dpVersion(source); target?.let(::dpVersion)
            require(source.entry.status == "published" && source.entry.review.getValue("freeCatalogEligible") == JsonPrimitive(true))
            require(source.entry.review.getValue("policyVersion").jsonPrimitive.content == policy.version)
            require(source.revision <= anchor.revision && (source.revision != anchor.revision ||
                source.releaseId == anchor.releaseId && source.requestSha256 == anchor.requestSha256))
            require(anchor.revision > 0 && anchor.versionCount >= 0 && traversed == anchor.versionCount && eligible in 0..traversed)
            dpHash(anchor.requestSha256); dpHash(anchor.taxonomySha256)
            require(anchor.taxonomyRevision.isNotBlank() && anchor.taxonomyRevision.length <= 128)
            require(decision.catalogRevision == anchor.revision.toString() && decision.eligibilityCatalogRevision == anchor.revision.toString() &&
                decision.taxonomyRevision == anchor.taxonomyRevision && decision.policyVersion == policy.version &&
                decision.preferenceVersion == inputs.preferenceRevision)
            val expected = dpJson(effective)
            require(dpSemantic(dpJson(decision.constraints)) == dpSemantic(expected.getValue("constraints")))
            require(expected["preferenceVersion"]?.let { dpInteger(it.jsonPrimitive.content).toString() } == inputs.preferenceRevision)
            val excluded = dpJson(inputs.copyForStorage()).getValue("preferences").jsonObject.getValue("excludedIngredientIds").jsonArray.map { UUID.fromString(it.jsonPrimitive.content) }.toSet()
            require(dpJson(command.body).getValue("constraints").jsonObject.getValue("hardExcludedIngredientIds").jsonArray.map { UUID.fromString(it.jsonPrimitive.content) }.toSet().containsAll(excluded))
            require((decision.status == PlanningStatus.READY) == (target != null))
            if (target != null) {
                require(eligible > 0 && target.entry.recipeVersionId != source.entry.recipeVersionId)
                require(target.entry.status == "published" && target.entry.review.getValue("freeCatalogEligible") == JsonPrimitive(true) &&
                    target.entry.review.getValue("policyVersion").jsonPrimitive.content == policy.version)
                require(target.revision <= anchor.revision && (target.revision != anchor.revision ||
                    target.releaseId == anchor.releaseId && target.requestSha256 == anchor.requestSha256))
                require(decision.recipe?.id?.value == target.entry.recipeVersionId.toString())
                // Recheck the selected materialization with the real bounded single-candidate
                // engine. This is NOT another full scan or proof of parent/source authority.
                val selected = DeterministicPlanner(policy).plan(WireDocument.parse(JsonObject(expected - "sourceRecipeVersionId").toString()),
                    inputs.context(), PlanningCatalog(anchor.revision.toString(), anchor.taxonomyRevision,
                        listOf(recipePlanningCandidate(target.entry)), anchor.ingredients.map {
                            IngredientComposition(it.ingredientId.toString(), it.componentIds?.map(UUID::toString)?.toSet())
                        }))
                require(selected is PortResult.Value && selected.value.decision.status == PlanningStatus.READY)
                require(dpSemantic(dpJson(requireNotNull(selected.value.decision.recipe).document)) ==
                    dpSemantic(dpJson(requireNotNull(decision.recipe).document)) && selected.value.decision.scaled == decision.scaled)
            } else { require(decision.recipe == null && changes.isEmpty() && eligible == 0L) }
            val projected = when (val value = CanonicalPlanningAdapter().materialize(decision,
                PlanningReceipt(receipt.planId.toString(), "1", receipt.createdAt.toString(), receipt.createdAt.toString(), parent.id.toString()))) {
                is PortResult.Value -> dpJson(value.value.document)
                is PortResult.Failure -> throw DerivedPlanMaterialException()
            }
            val snapshot = JsonObject(projected + ("changes" to JsonArray(projected.getValue("changes").jsonArray + changes)))
            val snapshotText = snapshot.toString(); val snapshotHash = digest(snapshotText.toByteArray(Charsets.UTF_8))
            require(snapshotText.toByteArray(Charsets.UTF_8).size <= 262_144 && dpValidator.validateResponse(command.operationId, 200,
                snapshotText.toByteArray(Charsets.UTF_8), "application/json") == BodyValidationResult.Valid)
            val context = buildJsonObject {
                put("version", 1); put("owner", dpOwner(command.principal)); put("operationId", command.operationId)
                put("commandKey", command.key.toString()); put("parentId", parent.id.toString()); put("parentVersion", parent.version.toString())
                put("originalIfMatch", command.originalIfMatch); put("commandRequestHash", command.identity.requestHash)
                put("requestText", command.body.encodeUtf8().decodeToString()); put("requestSha256", command.bodySha256)
                put("parentSnapshotText", parent.snapshotText); put("parentSnapshotHash", parent.snapshotHash)
                put("parentProofText", parent.proofText); put("parentProofHash", parent.proofHash)
                put("parentRequestText", parent.requestText); put("parentRequestHash", parent.requestHash)
                put("parentPlanningRequestText", parent.planningRequestText); put("parentPlanningRequestHash", parent.planningRequestHash)
                put("effectiveRequestText", effective.encodeUtf8().decodeToString()); put("inputsText", inputs.copyForStorage().encodeUtf8().decodeToString())
                put("policy", dpPolicy(policy)); put("catalogAnchor", buildJsonObject {
                    put("releaseId", anchor.releaseId.toString()); put("revision", anchor.revision.toString()); put("requestSha256", anchor.requestSha256)
                    put("taxonomyRevision", anchor.taxonomyRevision); put("taxonomySha256", anchor.taxonomySha256); put("versionCount", anchor.versionCount.toString())
                })
                put("traversedCount", traversed.toString()); put("eligibleCount", eligible.toString()); put("provenance", provenance)
                put("planId", receipt.planId.toString()); put("createdAt", receipt.createdAt.toString()); put("expiresAt", receipt.expiresAt.toString())
            }
            val contextText = context.toString(); require(contextText.toByteArray(Charsets.UTF_8).size <= 2_097_152)
            val contextHash = digest(contextText.toByteArray(Charsets.UTF_8))
            val proof = buildJsonObject {
                put("version", 3); put("kind", "derivedPlan"); put("owner", dpOwner(command.principal))
                put("operationId", command.operationId); put("commandKey", command.key.toString()); put("commandRequestHash", command.identity.requestHash)
                put("parentPlanId", parent.id.toString()); put("parentVersion", parent.version.toString()); put("originalIfMatch", command.originalIfMatch)
                put("parentSnapshotHash", parent.snapshotHash); put("parentProofHash", parent.proofHash); put("parentRequestHash", parent.requestHash)
                put("requestSha256", command.bodySha256); put("contextHash", contextHash); put("snapshotHash", snapshotHash)
                put("planId", receipt.planId.toString()); put("createdAt", receipt.createdAt.toString()); put("expiresAt", receipt.expiresAt.toString())
                put("status", snapshot.getValue("status")); put("rankingVersion", policy.version)
            }
            val proofText = proof.toString(); require(proofText.toByteArray(Charsets.UTF_8).size <= 32_768)
            return DerivedPlanMaterial(command, parent, DerivedPlanStoredRecord.decode(snapshotText, snapshotHash,
                contextText, contextHash, proofText, digest(proofText.toByteArray(Charsets.UTF_8))))
        }
    }
}

/** Decoded byte bindings only. Never returns a selector/PlanningDecision or a fresh proposal
 * and never claims that the serialized source/input evidence was authorized or current. */
internal class DerivedPlanStoredRecord private constructor(val snapshotText: String, val snapshotHash: String,
    val contextText: String, val contextHash: String, val proofText: String, val proofHash: String,
    val planId: UUID, val parentId: UUID, val recipeVersionId: UUID?, val status: String,
    val createdAt: Instant, val expiresAt: Instant) {
    val reply get() = StoredReply(200, dpJson(WireDocument.parse(snapshotText)), "\"1\"")
    override fun toString() = "DerivedPlanStoredRecord(<redacted>)"
    companion object {
        fun decode(snapshotText: String, snapshotHash: String, contextText: String, contextHash: String,
            proofText: String, proofHash: String): DerivedPlanStoredRecord = dpFormat {
            val snapshot = dpJson(dpDocument(snapshotText, snapshotHash, 262_144))
            val context = dpJson(dpDocument(contextText, contextHash, 2_097_152))
            val proof = dpJson(dpDocument(proofText, proofHash, 32_768))
            require(context.keys == DP_CONTEXT_KEYS && proof.keys == DP_PROOF_KEYS)
            require(context["version"] == JsonPrimitive(1) && proof["version"] == JsonPrimitive(3) && proof["kind"] == JsonPrimitive("derivedPlan"))
            val owner = context.getValue("owner").jsonObject
            require(owner.keys == setOf("environment", "actorKind", "principalId"))
            val kind = when (owner.getValue("actorKind").jsonPrimitive.content) { "account" -> CommandActor.ACCOUNT; "guest" -> CommandActor.GUEST; else -> error("Invalid owner") }
            val scope = PrincipalScope(owner.getValue("environment").jsonPrimitive.content, kind, dpUuid(owner.getValue("principalId")))
            val operation = context.getValue("operationId").jsonPrimitive.content
            val body = dpDocument(context.getValue("requestText").jsonPrimitive.content, context.getValue("requestSha256").jsonPrimitive.content, 65_536)
            dpRequest(operation, body)
            val parent = DerivedPlanParent.fromStored(context.getValue("parentSnapshotText").jsonPrimitive.content,
                context.getValue("parentSnapshotHash").jsonPrimitive.content, context.getValue("parentProofText").jsonPrimitive.content,
                context.getValue("parentProofHash").jsonPrimitive.content, context.getValue("parentRequestText").jsonPrimitive.content,
                context.getValue("parentRequestHash").jsonPrimitive.content, context.getValue("parentPlanningRequestText").jsonPrimitive.content)
            require(parent.planningRequestHash == context.getValue("parentPlanningRequestHash").jsonPrimitive.content)
            val header = context.getValue("originalIfMatch").jsonPrimitive.content
            require(header.length <= 1024 && header.matches(Regex("\"[0-9]+\"")) && dpInteger(header.removeSurrounding("\"")) == parent.version)
            val identity = CommandIdentity(scope, operation, dpUuid(context.getValue("commandKey")), mapOf("planId" to parent.id.toString()), body = dpJson(body), ifMatch = header)
            require(context.getValue("commandRequestHash").jsonPrimitive.content == identity.requestHash)
            require(context.getValue("parentId").jsonPrimitive.content == parent.id.toString() && context.getValue("parentVersion").jsonPrimitive.content == parent.version.toString())
            require(proof.getValue("contextHash").jsonPrimitive.content == contextHash && proof.getValue("snapshotHash").jsonPrimitive.content == snapshotHash)
            for (field in listOf("owner", "operationId", "commandKey", "commandRequestHash", "parentVersion", "originalIfMatch", "parentSnapshotHash",
                "parentProofHash", "parentRequestHash", "requestSha256", "planId", "createdAt", "expiresAt")) require(proof[field] == context[field])
            require(proof["parentPlanId"] == context["parentId"] && proof["rankingVersion"] == context.getValue("policy").jsonObject["version"])
            require(dpValidator.validateResponse(operation, 200, snapshotText.toByteArray(Charsets.UTF_8), "application/json") == BodyValidationResult.Valid)
            val id = dpUuid(snapshot.getValue("id")); val parentId = dpUuid(snapshot.getValue("parentPlanId"))
            require(id != parentId && parentId == parent.id && snapshot.getValue("version") == JsonPrimitive(1))
            require(snapshot["id"] == context["planId"] && snapshot["createdAt"] == context["createdAt"] && snapshot["updatedAt"] == context["createdAt"])
            require(snapshot["status"] == proof["status"] && snapshot["nextAlternativeCursor"] == JsonNull)
            require(dpSemantic(snapshot.getValue("constraints")) == dpSemantic(dpJson(body).getValue("constraints")))
            dpStoredContext(context, parent, body, snapshot)
            val created = Instant.parse(context.getValue("createdAt").jsonPrimitive.content); val expires = Instant.parse(context.getValue("expiresAt").jsonPrimitive.content)
            dpTime(created); dpTime(expires); require(created < expires)
            val status = snapshot.getValue("status").jsonPrimitive.content
            val recipeId = snapshot["recipeVersionId"]?.let(::dpUuid)
            require((status == "ready") == (recipeId != null))
            if (recipeId != null) require(snapshot.getValue("recipeSnapshot").jsonObject.getValue("id") == snapshot.getValue("recipeVersionId"))
            else require(!snapshot.containsKey("recipeSnapshot") && snapshot.getValue("changes").jsonArray.isEmpty())
            DerivedPlanStoredRecord(snapshotText, snapshotHash, contextText, contextHash, proofText, proofHash,
                id, parentId, recipeId, status, created, expires)
        }
    }
}

internal class DerivedPlanMaterialException : IllegalArgumentException("Derived Plan material unavailable")
private inline fun <T> dpFormat(block: () -> T): T = try {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("Derived Plan material interrupted")
    block()
} catch (cancelled: CancellationException) { throw cancelled }
catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
catch (_: Exception) { throw DerivedPlanMaterialException() }
private val dpValidator by lazy { ContractBodyValidator.bundled() }
private fun dpJson(document: WireDocument) = Json.parseToJsonElement(document.encodeUtf8().decodeToString()).jsonObject
private fun dpHash(value: String) { require(value.matches(Regex("[0-9a-f]{64}"))) }
private fun dpDocument(text: String, hash: String, limit: Int): WireDocument {
    dpHash(hash)
    // Validate the original UTF-16 text before encoding: Java's permissive encoder would
    // otherwise replace an unpaired surrogate and hash different material silently.
    return WireDocument.parse(text, WireLimits(limit, 32)).also {
        require(it.kind == WireKind.OBJECT && digest(it.encodeUtf8()) == hash)
    }
}
private fun dpInteger(value: String): BigInteger {
    val decimal = BigDecimal(value).stripTrailingZeros()
    require(decimal.precision().toLong() - decimal.scale().toLong() <= 128 && decimal.scale() <= 0)
    return decimal.toBigIntegerExact()
}
private fun dpUuid(value: JsonElement): UUID { val text = value.jsonPrimitive.content; require(value.jsonPrimitive.isString && CanonicalFormats.accepts("uuid", text) && text == text.lowercase()); return UUID.fromString(text) }
private fun dpTime(value: Instant) {
    require(value.nano % 1_000_000 == 0 && value.toString().matches(
        Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{3})?Z")))
}
private fun dpServings(body: WireDocument) = dpJson(body).getValue("constraints").jsonObject.getValue("servings").jsonPrimitive.content.toBigDecimal()
private fun dpRequest(operation: String, body: WireDocument) {
    require(body.encodeUtf8().size <= 65_536 && dpValidator.validateRequest(operation, body.encodeUtf8(), "application/json") == BodyValidationResult.Valid)
    val root = dpJson(body)
    when (operation) {
        "adaptPlan" -> {
            require(root.keys.all { it in setOf("constraints", "reason", "replaceIngredientId", "requestedReplacementId", "retainTasteTag", "excludeRecipeVersionIds") })
            require(root.getValue("reason").jsonPrimitive.content in setOf("makeMine", "missingIngredient"))
            if (root.getValue("reason") == JsonPrimitive("missingIngredient")) {
                val from = UUID.fromString(root.getValue("replaceIngredientId").jsonPrimitive.content)
                require(from !in root.getValue("constraints").jsonObject.getValue("ingredientIds").jsonArray.map { UUID.fromString(it.jsonPrimitive.content) })
            }
            if (root.containsKey("requestedReplacementId")) require(root.containsKey("replaceIngredientId") &&
                UUID.fromString(root.getValue("replaceIngredientId").jsonPrimitive.content) != UUID.fromString(root.getValue("requestedReplacementId").jsonPrimitive.content))
            root["retainTasteTag"]?.jsonPrimitive?.content?.let { require(it.isNotBlank() && it.length <= 128 && it.none(Char::isISOControl)) }
        }
        "simplifyPlan" -> {
            require(root.keys.all { it in setOf("constraints", "reason", "simplificationGoal", "allowDifferentMeal") })
            require(root.getValue("reason") == JsonPrimitive("easier"))
            require(root.getValue("simplificationGoal").jsonPrimitive.content in setOf("lessPrep", "lessCleanup", "lessTime", "overall"))
        }
        else -> error("Unsupported operation")
    }
}
private fun dpEffectiveRequest(command: DerivedPlanCommand, parent: DerivedPlanParent): WireDocument {
    val original = dpJson(parent.request)
    require(!original.containsKey("sourcePostId") && !original.containsKey("savedRecipeId"))
    dpSimplificationConstraints(command.operationId, parent, command.body)
    val values = original + mapOf("constraints" to dpJson(command.body).getValue("constraints"),
        "sourceRecipeVersionId" to JsonPrimitive(parent.recipeVersionId.toString())) +
        if (command.operationId == "adaptPlan") mapOf("intent" to JsonPrimitive("makeMine")) else emptyMap()
    return WireDocument.parse(JsonObject(values).toString()).also {
        require(dpValidator.validateSchema("PlanRequest", it.encodeUtf8()) == BodyValidationResult.Valid)
    }
}
private fun dpSimplificationConstraints(operation: String, parent: DerivedPlanParent, body: WireDocument) {
    // F08 changes the requested effort goal, not the original meal limits. F01/F10
    // deliberately permit a separate proposed set of constraints.
    if (operation == "simplifyPlan") require(dpSemantic(dpJson(body).getValue("constraints")) ==
        dpSemantic(dpJson(parent.request).getValue("constraints")))
}
private fun dpOwner(principal: VerifiedPlanningPrincipal) = buildJsonObject {
    put("environment", principal.environment); put("actorKind", principal.kind.name.lowercase()); put("principalId", principal.principalId.toString())
}
private fun dpPolicy(policy: PlanningPolicy) = buildJsonObject {
    require(policy.version.isNotBlank() && policy.version.length <= 128 && policy.version.none(Char::isISOControl))
    put("version", policy.version); put("heatEnabled", policy.heatEnabled); put("improveEnabled", policy.improveEnabled)
    put("relatedTasteExplicitlyRequested", policy.relatedTasteExplicitlyRequested)
}
private fun dpVersion(value: RecipeCatalogVersion): JsonObject {
    require(value.revision > 0); dpHash(value.requestSha256)
    return buildJsonObject {
        put("recipeVersionId", value.entry.recipeVersionId.toString()); put("materialSha256", value.entry.materialSha256)
        put("releaseId", value.releaseId.toString()); put("revision", value.revision.toString()); put("requestSha256", value.requestSha256)
        put("recipeText", value.entry.recipe.toString()); put("reviewText", value.entry.review.toString())
        put("rightsReference", value.entry.rightsReference)
    }
}
private fun dpStoredContext(context: JsonObject, parent: DerivedPlanParent, body: WireDocument, snapshot: JsonObject) {
    dpSimplificationConstraints(context.getValue("operationId").jsonPrimitive.content, parent, body)
    val inputs = PlanningPrivateInputsSnapshot.decode(context.getValue("inputsText").jsonPrimitive.content.toByteArray(Charsets.UTF_8))
    val effective = WireDocument.parse(context.getValue("effectiveRequestText").jsonPrimitive.content, WireLimits(65_536, 32))
    require(dpValidator.validateSchema("PlanRequest", effective.encodeUtf8()) == BodyValidationResult.Valid)
    val expected = dpJson(parent.request) + mapOf("constraints" to dpJson(body).getValue("constraints"),
        "sourceRecipeVersionId" to JsonPrimitive(parent.recipeVersionId.toString())) +
        if (context.getValue("operationId") == JsonPrimitive("adaptPlan")) mapOf("intent" to JsonPrimitive("makeMine")) else emptyMap()
    require(dpSemantic(dpJson(effective)) == dpSemantic(JsonObject(expected)))
    require(dpInteger(dpJson(effective).getValue("preferenceVersion").jsonPrimitive.content).toString() == inputs.preferenceRevision)
    val policy = context.getValue("policy").jsonObject
    require(policy.keys == setOf("version", "heatEnabled", "improveEnabled", "relatedTasteExplicitlyRequested"))
    val policyVersion = policy.getValue("version").jsonPrimitive.content
    require(dpPolicy(PlanningPolicy(policyVersion, policy.getValue("heatEnabled").jsonPrimitive.boolean,
        policy.getValue("improveEnabled").jsonPrimitive.boolean, policy.getValue("relatedTasteExplicitlyRequested").jsonPrimitive.boolean)) == policy)
    val anchor = context.getValue("catalogAnchor").jsonObject
    require(anchor.keys == setOf("releaseId", "revision", "requestSha256", "taxonomyRevision", "taxonomySha256", "versionCount"))
    dpUuid(anchor.getValue("releaseId")); dpHash(anchor.getValue("requestSha256").jsonPrimitive.content); dpHash(anchor.getValue("taxonomySha256").jsonPrimitive.content)
    val revision = dpLong(anchor.getValue("revision")); require(revision > 0)
    val count = dpLong(anchor.getValue("versionCount")); require(dpLong(context.getValue("traversedCount")) == count)
    val eligible = dpLong(context.getValue("eligibleCount")); require(eligible in 0..count)
    val taxonomy = anchor.getValue("taxonomyRevision").jsonPrimitive.content
    require(taxonomy.isNotBlank() && taxonomy.length <= 128 && taxonomy.none(Char::isISOControl))
    require(snapshot.getValue("catalogRevision") == anchor.getValue("revision"))
    val provenance = context.getValue("provenance").jsonObject
    val ready = snapshot.getValue("status") == JsonPrimitive("ready")
    fun version(raw: JsonElement): RecipeCatalogEntry {
        val value = raw.jsonObject
        require(value.keys == setOf("recipeVersionId", "materialSha256", "releaseId", "revision", "requestSha256", "recipeText", "reviewText", "rightsReference"))
        val id = dpUuid(value.getValue("recipeVersionId")); dpUuid(value.getValue("releaseId")); dpHash(value.getValue("requestSha256").jsonPrimitive.content)
        require(dpLong(value.getValue("revision")) in 1..revision)
        if (value.getValue("revision") == anchor.getValue("revision")) require(value["releaseId"] == anchor["releaseId"] && value["requestSha256"] == anchor["requestSha256"])
        val recipe = dpJson(WireDocument.parse(value.getValue("recipeText").jsonPrimitive.content, WireLimits(262_144, 32)))
        val review = dpJson(WireDocument.parse(value.getValue("reviewText").jsonPrimitive.content, WireLimits(262_144, 32)))
        val entry = RecipeCatalogEntry(recipe, review, value.getValue("rightsReference").jsonPrimitive.content)
        require(entry.recipeVersionId == id && entry.materialSha256 == value.getValue("materialSha256").jsonPrimitive.content)
        require(entry.status == "published" && review["policyVersion"] == JsonPrimitive(policyVersion) && review["freeCatalogEligible"] == JsonPrimitive(true))
        return entry
    }
    val source = version(provenance.getValue("source")); require(source.recipeVersionId == parent.recipeVersionId)
    val target = provenance["target"]?.let(::version)
    require(ready == (target != null))
    if (target != null) require(eligible > 0 && target.recipeVersionId != source.recipeVersionId && snapshot["recipeVersionId"] == JsonPrimitive(target.recipeVersionId.toString()))
    else require(eligible == 0L)
    if (context.getValue("operationId") == JsonPrimitive("adaptPlan")) {
        require(provenance.getValue("kind") == JsonPrimitive("adaptation"))
        require(provenance.keys == setOf("kind", "source", "inspectedEdgeCount", "edgePageCount") + if (ready) setOf("target", "edge") else emptySet())
        dpLong(provenance.getValue("inspectedEdgeCount")); dpLong(provenance.getValue("edgePageCount"))
        if (target != null) {
            val edge = provenance.getValue("edge").jsonObject
            require(edge.keys == setOf("publicationId", "revision", "requestSha256", "recordText", "recordSha256", "definitionSha256", "catalogRevision", "catalogReleaseId", "catalogRequestSha256"))
            dpUuid(edge.getValue("publicationId")); dpUuid(edge.getValue("catalogReleaseId")); require(dpLong(edge.getValue("revision")) > 0)
            require(dpLong(edge.getValue("catalogRevision")) in 1..revision)
            dpHash(edge.getValue("requestSha256").jsonPrimitive.content); dpHash(edge.getValue("catalogRequestSha256").jsonPrimitive.content)
            val record = decodeRecipeSubstitutionRecord(edge.getValue("recordText").jsonPrimitive.content)
            require(record.status == "reviewed" && record.sha256 == edge.getValue("recordSha256").jsonPrimitive.content &&
                record.definition.sha256 == edge.getValue("definitionSha256").jsonPrimitive.content && record.definition.policyVersion == policyVersion)
            validateRecipeSubstitutionPair(source, target, record.definition)
            require(record.definition.comparisonServings.compareTo(dpServings(body)) == 0)
            val expectedChange = buildJsonObject {
                put("fromIngredientId", record.definition.fromIngredientId.toString()); put("toIngredientId", record.definition.toIngredientId.toString())
                put("substitutionId", record.definition.id.toString()); put("explanation", record.definition.explanation)
            }
            require(snapshot.getValue("changes").jsonArray.filter { it.jsonObject.containsKey("substitutionId") } == listOf(expectedChange))
        }
    } else {
        require(provenance.getValue("kind") == JsonPrimitive("simplification"))
        require(provenance.getValue("goal") == dpJson(body).getValue("simplificationGoal") &&
            provenance.getValue("allowDifferentMeal") == JsonPrimitive(dpJson(body)["allowDifferentMeal"] == JsonPrimitive(true)))
        val base = setOf("kind", "source", "goal", "allowDifferentMeal")
        if (target == null) require(provenance.keys == base)
        else {
            require(compareRecipeEffort(source, target, dpServings(body)).improves(provenance.getValue("goal").jsonPrimitive.content))
            when (provenance.getValue("selectionKind").jsonPrimitive.content) {
                "REVIEWED_VARIANT" -> {
                    require(provenance.keys == base + setOf("target", "selectionKind", "reviewReference"))
                    val edge = target.simplificationSources.single { it.sourceRecipeVersionId == source.recipeVersionId }
                    require(edge.reviewReference == provenance.getValue("reviewReference").jsonPrimitive.content &&
                        edge.comparisonServings.compareTo(dpServings(body)) == 0 && provenance.getValue("goal").jsonPrimitive.content in edge.goals)
                    validateRecipeSimplificationPair(source, target, edge)
                }
                "DIFFERENT_MEAL" -> require(provenance.keys == base + setOf("target", "selectionKind") &&
                    provenance["allowDifferentMeal"] == JsonPrimitive(true) && source.recipe["recipeId"] != target.recipe["recipeId"])
                else -> error("Invalid selection kind")
            }
        }
    }
}
private fun dpLong(value: JsonElement): Long {
    require(value.jsonPrimitive.isString && value.jsonPrimitive.content.matches(Regex("0|[1-9][0-9]{0,18}")))
    return value.jsonPrimitive.content.toLong().also { require(it >= 0) }
}
private fun dpSemantic(value: JsonElement): String = when (value) {
    is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { (key, entry) -> "${JsonPrimitive(key)}:${dpSemantic(entry)}" }
    is JsonArray -> value.joinToString(",", "[", "]", transform = ::dpSemantic)
    is JsonPrimitive -> if (value.isString || value == JsonNull || value.booleanOrNull != null) value.toString() else value.content.toBigDecimal().stripTrailingZeros().toString()
}
private val DP_CONTEXT_KEYS = setOf("version", "owner", "operationId", "commandKey", "parentId", "parentVersion", "originalIfMatch", "commandRequestHash",
    "requestText", "requestSha256", "parentSnapshotText", "parentSnapshotHash", "parentProofText", "parentProofHash", "parentRequestText", "parentRequestHash",
    "parentPlanningRequestText", "parentPlanningRequestHash", "effectiveRequestText", "inputsText", "policy", "catalogAnchor", "traversedCount", "eligibleCount", "provenance", "planId", "createdAt", "expiresAt")
private val DP_PROOF_KEYS = setOf("version", "kind", "owner", "operationId", "commandKey", "commandRequestHash", "parentPlanId", "parentVersion", "originalIfMatch",
    "parentSnapshotHash", "parentProofHash", "parentRequestHash", "requestSha256", "contextHash", "snapshotHash", "planId", "createdAt", "expiresAt", "status", "rankingVersion")
