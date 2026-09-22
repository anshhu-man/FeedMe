package com.feedme.server.planning

import com.feedme.contracts.RecipeVersionWire
import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.planning.PlanningDecision
import com.feedme.planning.PlanningFact
import com.feedme.planning.PlanningIssue
import com.feedme.planning.PlanningScanResult
import com.feedme.planning.PlanningStatus
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Bounded first-result evidence, not a Plan, completed traversal, current eligibility or grant.
 * The owning writer must obtain fromScan's input from its actual successful scanner. Decoding
 * only checks structure; it deliberately cannot manufacture a shared-engine PlanningDecision.
 * No receipt IDs/timestamps, alternative cursor or complete candidate list are invented here.
 * Nested engine documents and the whole stored envelope retain their exact UTF-8 bytes.
 */
internal class PlanningManifestFirstDecision private constructor(
    private val document: WireDocument, private val root: JsonObject,
) {
    val status: PlanningStatus get() = PlanningStatus.valueOf(root.text("status"))
    val mode: String? get() = root["mode"].takeUnless { it == JsonNull }?.jsonPrimitive?.content
    val constraints: WireDocument get() = nested(root.text("constraintsText"), "Constraint")
    val recipe: RecipeVersionWire? get() = root["recipeText"].takeUnless { it == JsonNull }?.let {
        RecipeVersionWire.from(nested(it.jsonPrimitive.content, "RecipeVersion"))
    }
    val missingIngredients: List<WireDocument> get() = root.getValue("missingIngredientTexts").jsonArray.map {
        nested(it.jsonPrimitive.content, "IngredientAmount")
    }
    val issues: Set<PlanningIssue> get() = root.getValue("issues").jsonArray.map { PlanningIssue.valueOf(it.jsonPrimitive.content) }.toSet()
    val facts: List<PlanningFact> get() = root.getValue("facts").jsonArray.map {
        val value = it.jsonObject; PlanningFact(value.text("code"), value.text("label"))
    }
    val catalogRevision: String get() = root.text("catalogRevision")
    val eligibilityCatalogRevision: String get() = root.text("eligibilityCatalogRevision")
    val taxonomyRevision: String get() = root.text("taxonomyRevision")
    val preferenceVersion: String get() = root.text("preferenceVersion")
    val policyVersion: String get() = root.text("policyVersion")
    val scaled: Boolean get() = root.bool("scaled")
    val sha256: String get() = digest(document.encodeUtf8())
    fun copyForStorage(): WireDocument = document
    override fun toString() = "PlanningManifestFirstDecision(<redacted>)"

    companion object {
        // Matches V017 manifest_seals.first_decision_text, independent of catalog size.
        const val MAX_BYTES = 262_144
        // A reviewed source is <=64 KiB, but exact reviewed scaling can enlarge its
        // materialized recipe. The outer SQL byte bound still limits every component.
        private const val COMPONENT_BYTES = MAX_BYTES
        private val validator by lazy { ContractBodyValidator.bundled() }
        private val fields = setOf("version", "status", "mode", "constraintsText", "recipeText",
            "missingIngredientTexts", "issues", "facts", "catalogRevision", "eligibilityCatalogRevision",
            "taxonomyRevision", "preferenceVersion", "policyVersion", "scaled")

        fun fromScan(scan: PlanningScanResult): PlanningManifestFirstDecision = fromDecision(scan.decision)

        fun fromDecision(decision: PlanningDecision): PlanningManifestFirstDecision = firstDecisionFormat {
            // These are per-result ingredient/fact bounds, never a candidate/ordered-ID limit.
            require(decision.missingIngredients.size <= 128 && decision.facts.size <= 128)
            require(decision.facts.all { it.sourceMemoryId == null }) // Legacy guest format cannot drop private evidence links.
            fun exact(value: WireDocument): String {
                val bytes = value.encodeUtf8(); require(bytes.size <= COMPONENT_BYTES)
                return bytes.decodeToString(throwOnInvalidSequence = true)
            }
            val value = buildJsonObject {
                put("version", 1); put("status", decision.status.name)
                put("mode", decision.mode?.let(::JsonPrimitive) ?: JsonNull)
                put("constraintsText", exact(decision.constraints))
                put("recipeText", decision.recipe?.let { JsonPrimitive(exact(it.document)) } ?: JsonNull)
                put("missingIngredientTexts", JsonArray(decision.missingIngredients.map { JsonPrimitive(exact(it)) }))
                put("issues", JsonArray(decision.issues.sortedBy { it.ordinal }.map { JsonPrimitive(it.name) }))
                put("facts", JsonArray(decision.facts.map { buildJsonObject { put("code", it.code); put("label", it.label) } }))
                put("catalogRevision", decision.catalogRevision); put("eligibilityCatalogRevision", decision.eligibilityCatalogRevision)
                put("taxonomyRevision", decision.taxonomyRevision); put("preferenceVersion", decision.preferenceVersion)
                put("policyVersion", decision.policyVersion); put("scaled", decision.scaled)
            }
            decode(value.toString().encodeToByteArray(throwOnInvalidSequence = true))
        }

        fun decode(bytes: ByteArray): PlanningManifestFirstDecision = firstDecisionFormat {
            val document = WireDocument.decode(bytes, WireLimits(MAX_BYTES, 8))
            val root = json(document)
            require(root.keys == fields && root["version"] == JsonPrimitive(1))
            val status = PlanningStatus.valueOf(root.text("status"))
            val mode = root.getValue("mode").takeUnless { it == JsonNull }?.let {
                require(it.jsonPrimitive.isString); it.jsonPrimitive.content.also { value ->
                    require(value in setOf("cook", "assemble", "improve"))
                }
            }
            nested(root.text("constraintsText"), "Constraint")
            val recipe = root.getValue("recipeText").takeUnless { it == JsonNull }?.let {
                require(it.jsonPrimitive.isString)
                RecipeVersionWire.from(nested(it.jsonPrimitive.content, "RecipeVersion"))
            }
            val missing = root.getValue("missingIngredientTexts").jsonArray
            require(missing.size <= 128)
            missing.forEach { require(it.jsonPrimitive.isString); nested(it.jsonPrimitive.content, "IngredientAmount") }
            val issues = root.getValue("issues").jsonArray.map {
                require(it.jsonPrimitive.isString); PlanningIssue.valueOf(it.jsonPrimitive.content)
            }
            require(issues.size <= PlanningIssue.entries.size && issues == issues.distinct().sortedBy { it.ordinal })
            val facts = root.getValue("facts").jsonArray
            require(facts.size <= 128)
            facts.forEach {
                val fact = it.jsonObject
                require(fact.keys == setOf("code", "label"))
                schema("Reason", fact.toString().encodeToByteArray(throwOnInvalidSequence = true))
            }
            for (field in listOf("catalogRevision", "eligibilityCatalogRevision", "taxonomyRevision", "policyVersion")) {
                val value = root.text(field)
                require(value.isNotBlank() && value.length <= 128 && value.none(Char::isISOControl))
            }
            require(root.text("preferenceVersion").matches(Regex("[1-9][0-9]{0,127}")))
            root.bool("scaled") // May be true for a scaled but still unconfirmed ingredient result.
            // The pure engine exposes recipe material only for READY. Canonical Plan's broader
            // schema also permits recalled/history shapes; those are not a first scan decision.
            if (status == PlanningStatus.READY) {
                require(mode != null && recipe != null && issues.isEmpty())
                require(recipe.ingredients.size <= 128 && recipe.steps.size <= 128 && recipe.equipmentIds.size <= 128)
                require(json(recipe.document).text("reviewStatus") == "published")
                missing.forEach { require(json(nested(it.jsonPrimitive.content, "IngredientAmount")).bool("optional")) }
            } else require(recipe == null && issues.isNotEmpty())
            PlanningManifestFirstDecision(document, root)
        }

        private fun nested(text: String, schemaName: String): WireDocument {
            val document = WireDocument.parse(text, WireLimits(COMPONENT_BYTES, 32))
            schema(schemaName, document.encodeUtf8())
            return document
        }
        private fun schema(name: String, bytes: ByteArray) {
            require(validator.validateSchema(name, bytes) == BodyValidationResult.Valid)
        }
    }
}

private fun <T> firstDecisionFormat(action: () -> T): T = try { action() }
catch (failure: CancellationException) { throw failure }
catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
catch (_: Exception) { throw PlanningManifestFormatException() }
