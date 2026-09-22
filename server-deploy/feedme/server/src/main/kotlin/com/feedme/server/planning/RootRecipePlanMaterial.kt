package com.feedme.server.planning

import com.feedme.contracts.*
import com.feedme.core.ports.PortResult
import com.feedme.planning.*
import com.feedme.server.catalog.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.db.*
import com.feedme.server.memory.accountSavedRecipeHash
import com.feedme.server.memory.requireAccountSavedMaterial
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

/** Canonical no-parent command. This is an original, never a source/read/copy grant. */
internal class RootRecipePlanCommand(val actor: VerifiedPlanningPrincipal, val key: UUID, val body: WireDocument) {
    val storageFormat get() = when { dpJson(body).containsKey("sourcePostId") -> 6; dpJson(body).containsKey("savedRecipeId") -> 5; else -> 4 }
    val identity = dpFormat {
        require(actor.kind == CommandActor.ACCOUNT)
        rootRecipeRequest(body)
        CommandIdentity(PrincipalScope(actor.environment, actor.kind, actor.principalId), "createPlan", key, body = dpJson(body))
    }
    val bodyHash get() = digest(body.encodeUtf8())
}

/** Format4 records preserve a real catalog source without inventing a parent Plan. Pure
 * structural evidence only; readers/writers must use the concrete account/journal owner. */
internal class RootRecipePlanRecord private constructor(val snapshotText: String, val snapshotHash: String,
    val contextText: String, val contextHash: String, val proofText: String, val proofHash: String,
    val planId: UUID, val recipeVersionId: UUID?, val status: String, val createdAt: Instant, val expiresAt: Instant,
    val storageFormat: Int) {
    val reply get() = StoredReply(201, dpJson(WireDocument.parse(snapshotText)), "\"1\"")
    companion object {
        fun create(command: RootRecipePlanCommand, inputs: PlanningPrivateInputsSnapshot, policy: PlanningPolicy,
            anchor: RecipeCatalogAnchor, scan: RecipeSubstitutionScanResult, receipt: DerivedPlanReceipt,
            savedSource: JsonObject? = null, postSource: JsonObject? = null): RootRecipePlanRecord = dpFormat {
            require(scan.originalAdaptation == null && scan.originalRequest.encodeUtf8().contentEquals(command.body.encodeUtf8()))
            require((command.storageFormat == 5) == (savedSource != null))
            require((command.storageFormat == 6) == (postSource != null))
            if (command.storageFormat == 4) require(scan.source.entry.recipeVersionId == dpUuid(dpJson(command.body).getValue("sourceRecipeVersionId")))
            if (savedSource != null) require(savedSource.getValue("identity").jsonObject["recipeVersionId"] == JsonPrimitive(scan.source.entry.recipeVersionId.toString()))
            if (postSource != null) require(rootJson(postSource.rootText("recipeText")) == scan.source.entry.recipe)
            require(scan.traversedCount == anchor.versionCount && scan.eligibleCount in 0..scan.traversedCount)
            require(scan.decision.catalogRevision == anchor.revision.toString() && scan.decision.eligibilityCatalogRevision == anchor.revision.toString() &&
                scan.decision.taxonomyRevision == anchor.taxonomyRevision && scan.decision.policyVersion == policy.version &&
                scan.decision.preferenceVersion == inputs.preferenceRevision)
            require(dpSemantic(dpJson(scan.decision.constraints)) == dpSemantic(dpJson(command.body).getValue("constraints")))
            val selected = scan.selection
            require((scan.decision.status == PlanningStatus.READY) == (selected != null))
            val projected = when (val result = CanonicalPlanningAdapter().materialize(scan.decision,
                PlanningReceipt(receipt.planId.toString(), "1", receipt.createdAt.toString(), receipt.createdAt.toString()))) {
                is PortResult.Value -> dpJson(result.value.document)
                is PortResult.Failure -> throw DerivedPlanMaterialException()
            }
            val snapshot = JsonObject(projected + ("changes" to JsonArray(projected.getValue("changes").jsonArray + scan.changes.map(::dpJson))) +
                (postSource?.let { mapOf("sourcePostId" to it.getValue("postId")) } ?: emptyMap()))
            val provenance = buildJsonObject {
                put("kind", rootProvenanceKind(command.storageFormat)); put("source", dpVersion(scan.source))
                savedSource?.let { put("savedSource", it) }
                postSource?.let { put("postSource", it) }
                selected?.let {
                    require(it.source.entry.document() == scan.source.entry.document())
                    put("target", dpVersion(it.target)); put("edge", buildJsonObject {
                        put("publicationId", it.edge.publicationId.toString()); put("revision", it.edge.revision.toString())
                        put("requestSha256", it.edge.requestSha256); put("recordText", it.edge.record.document.toString())
                        put("recordSha256", it.edge.record.sha256); put("definitionSha256", it.edge.record.definition.sha256)
                        put("catalogRevision", it.edge.catalogRevision.toString()); put("catalogReleaseId", it.edge.catalogReleaseId.toString())
                        put("catalogRequestSha256", it.edge.catalogRequestSha256)
                    })
                }
                put("inspectedEdgeCount", scan.inspectedEdgeCount.toString()); put("edgePageCount", scan.edgePageCount.toString())
            }
            val context = buildJsonObject {
                put("version", 1); put("owner", dpOwner(command.actor)); put("operationId", "createPlan")
                put("commandKey", command.key.toString()); put("commandRequestHash", command.identity.requestHash)
                put("requestText", command.body.encodeUtf8().decodeToString()); put("requestSha256", command.bodyHash)
                put("inputsText", inputs.copyForStorage().encodeUtf8().decodeToString()); put("policy", dpPolicy(policy))
                put("catalogAnchor", buildJsonObject {
                    put("releaseId", anchor.releaseId.toString()); put("revision", anchor.revision.toString()); put("requestSha256", anchor.requestSha256)
                    put("taxonomyRevision", anchor.taxonomyRevision); put("taxonomySha256", anchor.taxonomySha256); put("versionCount", anchor.versionCount.toString())
                })
                put("traversedCount", scan.traversedCount.toString()); put("eligibleCount", scan.eligibleCount.toString()); put("provenance", provenance)
                put("planId", receipt.planId.toString()); put("createdAt", receipt.createdAt.toString()); put("expiresAt", receipt.expiresAt.toString())
            }
            val snapshotText = snapshot.toString(); val contextText = context.toString()
            val snapshotHash = digest(snapshotText.encodeToByteArray()); val contextHash = digest(contextText.encodeToByteArray())
            val proof = buildJsonObject {
                put("version", command.storageFormat); put("kind", rootProofKind(command.storageFormat))
                for (field in ROOT_COMMON) put(field, context.getValue(field))
                put("contextHash", contextHash); put("snapshotHash", snapshotHash); put("status", snapshot.getValue("status"))
                put("rankingVersion", policy.version)
            }.toString()
            decode(snapshotText, snapshotHash, contextText, contextHash, proof, digest(proof.encodeToByteArray()))
        }

        fun decode(snapshotText: String, snapshotHash: String, contextText: String, contextHash: String,
            proofText: String, proofHash: String): RootRecipePlanRecord = dpFormat {
            val snapshot = dpJson(dpDocument(snapshotText, snapshotHash, 262_144))
            val context = dpJson(dpDocument(contextText, contextHash, 2_097_152))
            val proof = dpJson(dpDocument(proofText, proofHash, 32_768))
            require(context.keys == ROOT_CONTEXT && proof.keys == ROOT_PROOF)
            val storageFormat = proof.getValue("version").jsonPrimitive.int
            require(context["version"] == JsonPrimitive(1) && storageFormat in 4..6 &&
                proof["kind"] == JsonPrimitive(rootProofKind(storageFormat)))
            require(context["operationId"] == JsonPrimitive("createPlan"))
            val owner = context.getValue("owner").jsonObject
            require(owner.keys == setOf("environment", "actorKind", "principalId") && owner["actorKind"] == JsonPrimitive("account"))
            val body = dpDocument(context.rootText("requestText"), context.rootText("requestSha256"), 65_536)
            rootRecipeRequest(body)
            require((storageFormat == 5) == dpJson(body).containsKey("savedRecipeId"))
            require((storageFormat == 6) == dpJson(body).containsKey("sourcePostId"))
            val command = CommandIdentity(PrincipalScope(owner.rootText("environment"), CommandActor.ACCOUNT, dpUuid(owner.getValue("principalId"))),
                "createPlan", dpUuid(context.getValue("commandKey")), body = dpJson(body))
            require(context["commandRequestHash"] == JsonPrimitive(command.requestHash))
            for (field in ROOT_COMMON) require(proof[field] == context[field])
            require(proof["contextHash"] == JsonPrimitive(contextHash) && proof["snapshotHash"] == JsonPrimitive(snapshotHash))
            require(dpValidator.validateResponse("createPlan", 201, snapshotText.encodeToByteArray(), "application/json") == BodyValidationResult.Valid)
            require(snapshot["version"] == JsonPrimitive(1) && !snapshot.containsKey("parentPlanId"))
            require(snapshot["sourcePostId"] == dpJson(body)["sourcePostId"])
            require(snapshot["nextAlternativeCursor"] == JsonNull && snapshot["id"] == context["planId"] &&
                snapshot["createdAt"] == context["createdAt"] && snapshot["updatedAt"] == context["createdAt"] && snapshot["status"] == proof["status"])
            require(dpSemantic(snapshot.getValue("constraints")) == dpSemantic(dpJson(body).getValue("constraints")))
            val inputs = PlanningPrivateInputsSnapshot.decode(context.rootText("inputsText").encodeToByteArray())
            require(dpInteger(dpJson(body).getValue("preferenceVersion").jsonPrimitive.content).toString() == inputs.preferenceRevision)
            val exclusions = dpJson(inputs.copyForStorage()).getValue("preferences").jsonObject.getValue("excludedIngredientIds").jsonArray.map(::dpUuid).toSet()
            require(dpJson(body).getValue("constraints").jsonObject.getValue("hardExcludedIngredientIds").jsonArray.map(::dpUuid).toSet().containsAll(exclusions))
            val p = context.getValue("policy").jsonObject
            require(p.keys == setOf("version", "heatEnabled", "improveEnabled", "relatedTasteExplicitlyRequested"))
            val policy = PlanningPolicy(p.rootText("version"), p.getValue("heatEnabled").jsonPrimitive.boolean,
                p.getValue("improveEnabled").jsonPrimitive.boolean, p.getValue("relatedTasteExplicitlyRequested").jsonPrimitive.boolean)
            require(dpPolicy(policy) == p && proof["rankingVersion"] == p["version"])
            val anchor = context.getValue("catalogAnchor").jsonObject
            require(anchor.keys == setOf("releaseId", "revision", "requestSha256", "taxonomyRevision", "taxonomySha256", "versionCount"))
            dpUuid(anchor.getValue("releaseId")); dpHash(anchor.rootText("requestSha256")); dpHash(anchor.rootText("taxonomySha256"))
            val revision = dpLong(anchor.getValue("revision")); require(revision > 0)
            require(anchor.rootText("taxonomyRevision").isNotBlank() && anchor.rootText("taxonomyRevision").length <= 128)
            val count = dpLong(anchor.getValue("versionCount")); val eligible = dpLong(context.getValue("eligibleCount"))
            require(dpLong(context.getValue("traversedCount")) == count && eligible in 0..count && snapshot["catalogRevision"] == anchor["revision"])
            fun source(value: JsonObject, retained: Boolean = false): RecipeCatalogEntry {
                require(value.keys == setOf("recipeVersionId", "materialSha256", "releaseId", "revision", "requestSha256", "recipeText", "reviewText", "rightsReference"))
                dpUuid(value.getValue("releaseId")); dpHash(value.rootText("requestSha256")); require(dpLong(value.getValue("revision")) in 1..revision)
                if (value["revision"] == anchor["revision"]) require(value["releaseId"] == anchor["releaseId"] && value["requestSha256"] == anchor["requestSha256"])
                val entry = RecipeCatalogEntry(dpJson(WireDocument.parse(value.rootText("recipeText"))), dpJson(WireDocument.parse(value.rootText("reviewText"))), value.rootText("rightsReference"))
                require(entry.recipeVersionId == dpUuid(value.getValue("recipeVersionId")) && entry.materialSha256 == value.rootText("materialSha256"))
                require(entry.status == "published" && (retained || entry.review["freeCatalogEligible"] == JsonPrimitive(true)) && entry.review["policyVersion"] == p["version"])
                return entry
            }
            val provenance = context.getValue("provenance").jsonObject
            val ready = snapshot["status"] == JsonPrimitive("ready")
            require(provenance.keys == setOf("kind", "source", "inspectedEdgeCount", "edgePageCount") +
                (if (ready) setOf("target", "edge") else emptySet()) + when (storageFormat) { 5 -> setOf("savedSource"); 6 -> setOf("postSource"); else -> emptySet() })
            require(provenance["kind"] == JsonPrimitive(rootProvenanceKind(storageFormat)))
            dpLong(provenance.getValue("inspectedEdgeCount")); dpLong(provenance.getValue("edgePageCount"))
            val original = source(provenance.getValue("source").jsonObject, storageFormat == 5)
            if (storageFormat == 4) require(original.recipeVersionId == dpUuid(dpJson(body).getValue("sourceRecipeVersionId")))
            else if (storageFormat == 6) {
                val post = provenance.getValue("postSource").jsonObject
                require(post.keys == setOf("environment", "ownerId", "postId", "postVersion", "attachment", "recipeText", "recipeSha256"))
                require(post["environment"] == owner["environment"] && post["postId"] == dpJson(body)["sourcePostId"] &&
                    post["postVersion"] == dpJson(body)["sourcePostVersion"])
                dpUuid(post.getValue("ownerId")); dpUuid(post.getValue("postId"))
                require(post.getValue("postVersion").jsonPrimitive.long > 0)
                val recipe = rootJson(post.rootText("recipeText"))
                require(recipe == original.recipe && accountSavedRecipeHash(recipe) == post.rootText("recipeSha256"))
                val attachment = post.getValue("attachment").jsonObject
                require(dpValidator.validateSchema("Attachment", attachment.toString().encodeToByteArray()) == BodyValidationResult.Valid &&
                    attachment["rightsBasis"] == JsonPrimitive("catalogRedistributable") && attachment["reviewStatus"] == JsonPrimitive("reviewed") &&
                    listOf("recipeVersionId", "planId").count(attachment::containsKey) == 1 && !attachment.containsKey("personalRecipe"))
                attachment["recipeVersionId"]?.let { require(it == recipe["id"]) }
            } else {
                val saved = provenance.getValue("savedSource").jsonObject
                require(saved.keys == setOf("identity", "recipeText", "originalSource"))
                val identity = saved.getValue("identity").jsonObject
                require(identity.keys == ROOT_SAVED_IDENTITY)
                require(identity["environment"] == owner["environment"] && identity["principalId"] == owner["principalId"] &&
                    identity["savedRecipeId"] == dpJson(body)["savedRecipeId"] && identity["recipeVersionId"] == JsonPrimitive(original.recipeVersionId.toString()))
                for (field in listOf("savedRecipeId", "recipeVersionId", "sourceId")) dpUuid(identity.getValue(field))
                require(dpLong(identity.getValue("generation")) > 0 && dpLong(identity.getValue("version")) > 0)
                val kind = identity.rootText("sourceType"); require(kind in setOf("catalog", "ownPlan"))
                if (kind == "catalog") require(identity["originPlanId"] == JsonNull && identity["sourceId"] == identity["recipeVersionId"])
                else { dpUuid(identity.getValue("originPlanId")); require(identity["sourceId"] == identity["originPlanId"]) }
                require(identity.rootText("contentLicense") in setOf("catalogRedistributable", "privateCopyOnly"))
                val recipe = dpJson(WireDocument.parse(saved.rootText("recipeText")))
                require(dpValidator.validateSchema("RecipeVersion", recipe.toString().encodeToByteArray()) == BodyValidationResult.Valid)
                require(accountSavedRecipeHash(recipe) == identity.rootText("recipeHash"))
                val grant = identity.getValue("copyEvidence").jsonObject
                require(grant.keys == setOf("formatVersion", "grant", "recipeHash") && grant["formatVersion"] == JsonPrimitive(1) && grant["recipeHash"] == identity["recipeHash"])
                val originalSource = saved.getValue("originalSource").jsonObject
                val licensed = source(originalSource, retained = true)
                require(licensed.recipeVersionId == original.recipeVersionId && licensed.materialSha256 == original.materialSha256 &&
                    recipeCopySourceSha256(licensed) == recipeCopySourceSha256(original))
                val evidence = grant.getValue("grant").jsonObject
                require(evidence.keys == setOf("formatVersion", "environment", "grantId", "requestSha256", "recipeVersionId", "sourceReleaseId", "sourceRevision", "sourceRequestSha256", "sourceSha256"))
                require(evidence["formatVersion"] == JsonPrimitive(1) && evidence["environment"] == identity["environment"] && evidence["recipeVersionId"] == identity["recipeVersionId"] &&
                    evidence["sourceReleaseId"] == originalSource["releaseId"] && evidence.getValue("sourceRevision").jsonPrimitive.long == originalSource.rootText("revision").toLong() &&
                    evidence["sourceRequestSha256"] == originalSource["requestSha256"] && evidence["sourceSha256"] == JsonPrimitive(recipeCopySourceSha256(licensed)))
                dpUuid(evidence.getValue("grantId")); dpHash(evidence.rootText("requestSha256"))
                requireAccountSavedMaterial(licensed, recipe, identity.getValue("allowReviewedScaling").jsonPrimitive.boolean && kind == "ownPlan")
            }
            val id = dpUuid(snapshot.getValue("id")); val recipeId = snapshot["recipeVersionId"]?.let(::dpUuid)
            if (ready) {
                require(eligible > 0 && recipeId != null)
                val target = source(provenance.getValue("target").jsonObject)
                require(recipeId == target.recipeVersionId && target.recipeVersionId != original.recipeVersionId)
                require(snapshot.getValue("recipeSnapshot").jsonObject["id"] == snapshot["recipeVersionId"])
                val edge = provenance.getValue("edge").jsonObject
                require(edge.keys == setOf("publicationId", "revision", "requestSha256", "recordText", "recordSha256", "definitionSha256", "catalogRevision", "catalogReleaseId", "catalogRequestSha256"))
                dpUuid(edge.getValue("publicationId")); dpUuid(edge.getValue("catalogReleaseId")); dpHash(edge.rootText("requestSha256")); dpHash(edge.rootText("catalogRequestSha256"))
                require(dpLong(edge.getValue("revision")) > 0 && dpLong(edge.getValue("catalogRevision")) in 1..revision)
                val record = decodeRecipeSubstitutionRecord(edge.rootText("recordText"))
                require(record.status == "reviewed" && record.sha256 == edge.rootText("recordSha256") && record.definition.sha256 == edge.rootText("definitionSha256"))
                require(record.definition.policyVersion == policy.version && record.definition.comparisonServings.compareTo(dpJson(body).getValue("constraints").jsonObject.getValue("servings").jsonPrimitive.content.toBigDecimal()) == 0)
                validateRecipeSubstitutionPair(original, target, record.definition)
                val change = buildJsonObject { put("fromIngredientId", record.definition.fromIngredientId.toString()); put("toIngredientId", record.definition.toIngredientId.toString())
                    put("substitutionId", record.definition.id.toString()); put("explanation", record.definition.explanation) }
                require(snapshot.getValue("changes").jsonArray.filter { it.jsonObject.containsKey("substitutionId") } == listOf(change))
            } else require(recipeId == null && !snapshot.containsKey("recipeSnapshot") && snapshot.getValue("changes").jsonArray.isEmpty() && eligible == 0L)
            val created = Instant.parse(context.rootText("createdAt")); val expires = Instant.parse(context.rootText("expiresAt"))
            dpTime(created); dpTime(expires); require(created < expires)
            RootRecipePlanRecord(snapshotText, snapshotHash, contextText, contextHash, proofText, proofHash, id, recipeId,
                snapshot.getValue("status").jsonPrimitive.content, created, expires, storageFormat)
        }
    }
}

internal fun rootRecipeRequest(body: WireDocument) {
    require(body.encodeUtf8().size <= 65_536 && dpValidator.validateRequest("createPlan", body.encodeUtf8(), "application/json") == BodyValidationResult.Valid)
    val request = dpJson(body)
    require(request.keys.all { it in setOf("mode", "constraints", "preferenceVersion", "intent", "sourceRecipeVersionId", "savedRecipeId", "sourcePostId", "sourcePostVersion", "baseMeal") })
    val sourceKey = listOf("sourceRecipeVersionId", "savedRecipeId", "sourcePostId").single(request::containsKey)
    require(request.containsKey("sourcePostId") == request.containsKey("sourcePostVersion"))
    request["sourcePostVersion"]?.let { require(it.jsonPrimitive.long > 0) }
    require(request["intent"] == JsonPrimitive("makeMine")); dpUuid(request.getValue(sourceKey))
    require(dpInteger(request.getValue("preferenceVersion").jsonPrimitive.content).signum() > 0)
}
internal fun JsonObject.rootText(field: String) = getValue(field).jsonPrimitive.content

/** Only for a REAL ready root becoming an existing-Plan parent. The canonical original
 * Saved request is retained separately in parentRequestText/hash forever; this explicit
 * materialization projection describes the chosen target, not the private source copy. */
internal fun rootParentPlanningRequest(original: String, record: RootRecipePlanRecord): String {
    if (record.storageFormat == 4) return original
    require(record.storageFormat in 5..6 && record.status == "ready" && record.recipeVersionId != null)
    val request = rootJson(original)
    require((request.containsKey("savedRecipeId") || request.containsKey("sourcePostId")) && !request.containsKey("sourceRecipeVersionId"))
    return JsonObject(request - setOf("savedRecipeId", "sourcePostId", "sourcePostVersion") + ("sourceRecipeVersionId" to JsonPrimitive(record.recipeVersionId.toString()))).toString()
}
private fun rootProofKind(format: Int) = when (format) { 4 -> "rootRecipePlan"; 5 -> "rootSavedPlan"; 6 -> "rootPostPlan"; else -> error("Unsupported root format") }
private fun rootProvenanceKind(format: Int) = when (format) { 4 -> "rootAdaptation"; 5 -> "savedRootAdaptation"; 6 -> "postRootAdaptation"; else -> error("Unsupported root format") }
private val ROOT_COMMON = setOf("owner", "operationId", "commandKey", "commandRequestHash", "requestSha256", "planId", "createdAt", "expiresAt")
private val ROOT_CONTEXT = ROOT_COMMON + setOf("version", "requestText", "inputsText", "policy", "catalogAnchor", "traversedCount", "eligibleCount", "provenance")
private val ROOT_PROOF = ROOT_COMMON + setOf("version", "kind", "contextHash", "snapshotHash", "status", "rankingVersion")
private val ROOT_SAVED_IDENTITY = setOf("environment", "principalId", "savedRecipeId", "generation", "version", "recipeVersionId", "recipeHash",
    "sourceType", "sourceId", "originPlanId", "contentLicense", "copyEvidence", "allowReviewedScaling")
