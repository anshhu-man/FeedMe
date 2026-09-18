package com.feedme.server.planning

import com.feedme.contracts.*
import com.feedme.planning.*
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.*

/**
 * Server-private, bounded persistence format for a trusted adapter's actual evidence. Parsing
 * proves structure, NOT native/provider rights or editorial approval. No raw natural language,
 * social data, tokens, nutrition inference or implicit review flags are represented here.
 * The same complete evidence reconstructs the pure engine after a server process restart.
 */
class PlanningEvidenceSnapshot private constructor(internal val json: JsonObject) {
    internal val preferences = json.getValue("preferences").jsonObject
    internal val pantry = json.getValue("pantry").jsonObject
    internal val catalog = json.getValue("catalog").jsonObject
    internal val candidates = catalog.getValue("candidates").jsonArray.map { it.jsonObject }
    internal fun context() = PlanningContext(PlanningPreferences(preferences.text("revision"),
        preferences.strings("excludedIngredientIds").toSet(), preferences.strings("dislikedIngredientIds").toSet()),
        pantry.getValue("items").jsonArray.map { it.jsonObject.let { item ->
            ReportedIngredient(item.text("ingredientId"), PlanningAvailability.valueOf(item.text("availability"))) } },
        json["baseMeal"].takeUnless { it == JsonNull }?.jsonObject?.let {
            ConfirmedBaseMeal(wire(it.getValue("document")), it.text("catalogType"), it.bool("compositionComplete")) })
    internal fun catalog(excluded: Set<String> = emptySet()) = PlanningCatalog(catalog.text("revision"), catalog.text("taxonomyRevision"),
        candidates.filter { it.getValue("recipe").jsonObject.text("id").lowercase() !in excluded }.map { candidate ->
            val e = candidate.getValue("review").jsonObject
            PlanningCandidate(RecipeVersionWire.from(wire(candidate.getValue("recipe"))), ReviewedPlanningEvidence(
                e.text("reviewReference"), e.text("policyVersion"), PlanningContentKind.valueOf(e.text("kind")),
                PlanningEnergy.valueOf(e.text("minimumEnergy")), e.bool("heatingRequired"), e.bool("substantialPreparation"),
                e.bool("freeCatalogEligible"), e.strings("compatibleBaseTypes").toSet(), e.bool("linearQuantityScalingReviewed"),
                e.bool("stepsValidForScalingRange"), e.bool("effortValidForScalingRange"), e.strings("scalableUnits").toSet()))
        }, catalog.getValue("ingredients").jsonArray.map { it.jsonObject.let { ingredient ->
            IngredientComposition(ingredient.text("ingredientId"), ingredient["componentIds"].takeUnless { it == JsonNull }
                ?.jsonArray?.map { id -> id.jsonPrimitive.content }?.toSet()) } })
    internal fun candidate(id: String) = candidates.singleOrNull { it.getValue("recipe").jsonObject.text("id").equals(id, true) }
    internal fun samePrivateInputs(other: PlanningEvidenceSnapshot) = preferences == other.preferences && pantry == other.pantry && json["baseMeal"] == other.json["baseMeal"]
    // Match the engine's taxonomy identity: order/UUID spelling are not composition changes,
    // but null (unknown) and an explicitly empty atom remain different evidence.
    internal fun sameTaxonomy(other: PlanningEvidenceSnapshot): Boolean =
        catalog["taxonomyRevision"] == other.catalog["taxonomyRevision"] && taxonomyIdentity() == other.taxonomyIdentity()
    private fun taxonomyIdentity(): Map<String, Set<String>?> = catalog.getValue("ingredients").jsonArray.associate { value ->
        val ingredient = value.jsonObject
        ingredient.text("ingredientId").lowercase() to ingredient["componentIds"].takeUnless { it == JsonNull }
            ?.jsonArray?.map { it.jsonPrimitive.content.lowercase() }?.toSet()
    }
    fun copyForStorage(): WireDocument = wire(json)
    override fun toString() = "PlanningEvidenceSnapshot(<redacted>)"

    companion object {
        const val MAX_BYTES = 2_097_152
        fun fromAuthoritativeDocument(document: WireDocument): PlanningEvidenceSnapshot = decode(document.encodeUtf8())
        internal fun decode(bytes: ByteArray): PlanningEvidenceSnapshot = try {
            val root = Json.parseToJsonElement(WireDocument.decode(bytes, WireLimits(MAX_BYTES, 32)).encodeUtf8().decodeToString()).jsonObject
            exact(root, "version", "preferences", "pantry", "catalog", "baseMeal"); require(root["version"] == JsonPrimitive(1))
            if (root["baseMeal"] != JsonNull) {
                val base = root.getValue("baseMeal").jsonObject; exact(base, "document", "catalogType", "compositionComplete")
                bounded(base.text("catalogType"), 128); base.bool("compositionComplete")
                val doc = base.getValue("document").jsonObject
                require(doc.keys.containsAll(setOf("description", "preparationState")) && doc.keys.all { it in setOf("description", "preparationState", "ingredientIds") })
                require(doc.text("description").length <= 500); require(doc.text("preparationState") in setOf("alreadyPrepared", "partiallyPrepared", "unknown"))
                if (doc.containsKey("ingredientIds")) ids(doc, "ingredientIds", 256)
            }
            val p = root.getValue("preferences").jsonObject; exact(p, "revision", "excludedIngredientIds", "dislikedIngredientIds")
            require(p.text("revision").matches(Regex("[1-9][0-9]{0,127}")))
            ids(p, "excludedIngredientIds", 256); ids(p, "dislikedIngredientIds", 256)
            val pantry = root.getValue("pantry").jsonObject; exact(pantry, "revision", "items"); bounded(pantry.text("revision"), 128)
            val items = pantry.getValue("items").jsonArray; require(items.size <= 256)
            val pantryIds = items.map { it.jsonObject.let { item -> exact(item, "ingredientId", "availability"); uuid(item.text("ingredientId"));
                PlanningAvailability.valueOf(item.text("availability")); item.text("ingredientId").lowercase() } }; require(pantryIds.distinct().size == pantryIds.size)
            val c = root.getValue("catalog").jsonObject; exact(c, "revision", "taxonomyRevision", "ingredients", "candidates")
            bounded(c.text("revision"), 128); bounded(c.text("taxonomyRevision"), 128)
            val ingredients = c.getValue("ingredients").jsonArray; require(ingredients.size <= 1024)
            val ingredientIds = ingredients.map { it.jsonObject.let { i -> exact(i, "ingredientId", "componentIds"); uuid(i.text("ingredientId"));
                if (i["componentIds"] != JsonNull) ids(i, "componentIds", 128); i.text("ingredientId").lowercase() } }
            require(ingredientIds.distinct().size == ingredientIds.size)
            val candidates = c.getValue("candidates").jsonArray; require(candidates.size <= 128)
            val recipeIds = candidates.map { it.jsonObject.let { candidate ->
                exact(candidate, "recipe", "review")
                val recipe = wire(candidate.getValue("recipe")); require(recipe.encodeUtf8().size <= 65_536)
                require(validator.validateSchema("RecipeVersion", recipe.encodeUtf8()) == ContractValidationResult.Valid)
                val e = candidate.getValue("review").jsonObject
                exact(e, "reviewReference", "policyVersion", "kind", "minimumEnergy", "heatingRequired", "substantialPreparation", "freeCatalogEligible",
                    "compatibleBaseTypes", "linearQuantityScalingReviewed", "stepsValidForScalingRange", "effortValidForScalingRange", "scalableUnits")
                bounded(e.text("reviewReference"), 256); bounded(e.text("policyVersion"), 128)
                PlanningContentKind.valueOf(e.text("kind")); PlanningEnergy.valueOf(e.text("minimumEnergy"))
                listOf("heatingRequired", "substantialPreparation", "freeCatalogEligible", "linearQuantityScalingReviewed", "stepsValidForScalingRange", "effortValidForScalingRange").forEach { name -> e.bool(name) }
                for (name in listOf("compatibleBaseTypes", "scalableUnits")) { val values = e.strings(name); require(values.size <= 128 && values.distinct().size == values.size); values.forEach { value -> bounded(value, 128) } }
                candidate.getValue("recipe").jsonObject.text("id").lowercase()
            } }; require(recipeIds.distinct().size == recipeIds.size)
            PlanningEvidenceSnapshot(root)
        } catch (_: Exception) { throw PlanningSnapshotFormatException() }
        private val validator by lazy { CanonicalBodyValidator.bundled() }
        private fun exact(o: JsonObject, vararg names: String) { require(o.keys == names.toSet()) }
        private fun ids(o: JsonObject, name: String, max: Int) { val values = o.strings(name); require(values.size <= max); values.forEach(::uuid); require(values.map(String::lowercase).distinct().size == values.size) }
        private fun uuid(v: String) { require(CanonicalFormats.accepts("uuid", v)); UUID.fromString(v) }
        private fun bounded(v: String, max: Int) { require(v.isNotBlank() && v.length <= max && v.none(Char::isISOControl)) }
    }
}

class PlanningSnapshotFormatException : IllegalArgumentException("Planning evidence unavailable")
internal fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.let { require(it.isString); it.content }
internal fun JsonObject.bool(name: String): Boolean = getValue(name).jsonPrimitive.let { require(!it.isString); it.boolean }
internal fun JsonObject.strings(name: String) = getValue(name).jsonArray.map { it.jsonPrimitive.let { p -> require(p.isString); p.content } }
internal fun wire(value: JsonElement) = WireDocument.parse(value.toString(), WireLimits(PlanningEvidenceSnapshot.MAX_BYTES, 32))
internal fun json(document: WireDocument) = Json.parseToJsonElement(document.encodeUtf8().decodeToString()).jsonObject
internal fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
