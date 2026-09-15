package com.feedme.mealflow

import com.feedme.contracts.*
import com.feedme.core.ports.*
import kotlinx.serialization.json.*

/** Constructive canonical adapter, not language interpretation or client-side recipe evaluation. */
class MealRequestBuilder(private val validator: CanonicalBodyValidator = CanonicalBodyValidator.bundled()) {
    fun build(draft: ManualMealDraft, preferences: WireDocument): PortResult<WireDocument> = attempt {
        schema("Preference", preferences)
        if (draft.preferencesPendingSync) mealFail(FailureReason.CONFLICT)
        val pref = preferences.json().jsonObject
        val exclusions = checkedIds(draft.hardExcludedIngredientIds) + checkedIds(pref.getValue("hardExcludedIngredientIds").jsonArray.map { it.jsonPrimitive.content })
        val raw = manual(draft).json().jsonObject
        val constraints = raw.getValue("constraints").jsonObject
        val result = doc(JsonObject(raw + mapOf("preferenceVersion" to pref.getValue("version"),
            "constraints" to JsonObject(constraints + ("hardExcludedIngredientIds" to strings(exclusions.distinct().sorted()))))))
        schema("PlanRequest", result)
        result
    }

    internal fun manual(draft: ManualMealDraft): WireDocument {
        val result = doc(buildJsonObject {
            put("mode", draft.mode.name.lowercase())
            put("constraints", buildJsonObject {
                put("ingredientIds", strings(checkedIds(draft.ingredientIds)))
                put("energy", draft.energy.name.lowercase())
                put("servings", number(draft.servings))
                put("equipmentIds", strings(checkedStrings(draft.equipmentIds)))
                put("hardExcludedIngredientIds", strings(checkedIds(draft.hardExcludedIngredientIds)))
                put("tasteTags", strings(checkedStrings(draft.tasteTags)))
                draft.maxTotalMinutes?.let { put("maxTotalMinutes", number(it)) }
                draft.maxActiveMinutes?.let { put("maxActiveMinutes", number(it)) }
            })
            draft.baseMeal?.let { base -> put("baseMeal", buildJsonObject {
                put("description", base.description)
                put("preparationState", when (base.preparation) {
                    BasePreparation.ALREADY_PREPARED -> "alreadyPrepared"
                    BasePreparation.PARTIALLY_PREPARED -> "partiallyPrepared"
                    BasePreparation.UNKNOWN -> "unknown"
                })
                base.ingredientIds?.let { put("ingredientIds", strings(checkedIds(it))) }
            }) }
        })
        schema("PlanRequest", result)
        return result
    }

    internal fun decodeManual(document: WireDocument, pending: Boolean): ManualMealDraft {
        schema("PlanRequest", document)
        val r = document.json().jsonObject
        if (r.keys.any { it !in setOf("mode", "constraints", "baseMeal") }) mealFail(FailureReason.INVALID_DATA)
        val c = r.getValue("constraints").jsonObject
        if (c.keys.any { it !in setOf("ingredientIds", "energy", "servings", "equipmentIds", "hardExcludedIngredientIds", "tasteTags", "maxTotalMinutes", "maxActiveMinutes") }) mealFail(FailureReason.INVALID_DATA)
        val base = r["baseMeal"]?.jsonObject?.let { b -> ManualBaseMeal(b.getValue("description").jsonPrimitive.content,
            when (b.getValue("preparationState").jsonPrimitive.content) {
                "alreadyPrepared" -> BasePreparation.ALREADY_PREPARED
                "partiallyPrepared" -> BasePreparation.PARTIALLY_PREPARED
                else -> BasePreparation.UNKNOWN
            }, b["ingredientIds"]?.jsonArray?.map { it.jsonPrimitive.content }) }
        return ManualMealDraft(MealMode.valueOf(r.getValue("mode").jsonPrimitive.content.uppercase()),
            MealEnergy.valueOf(c.getValue("energy").jsonPrimitive.content.uppercase()), c.getValue("servings").jsonPrimitive.content,
            list(c, "ingredientIds"), list(c, "equipmentIds"), list(c, "hardExcludedIngredientIds"), list(c, "tasteTags"),
            c["maxTotalMinutes"]?.jsonPrimitive?.content, c["maxActiveMinutes"]?.jsonPrimitive?.content, base, pending).also { manual(it) }
    }
    internal fun schema(name: String, document: WireDocument) {
        if (validator.validateSchema(name, document.encodeUtf8()) != ContractValidationResult.Valid) mealFail(FailureReason.INVALID_DATA)
    }
    private fun checkedIds(values: List<String>) = checkedStrings(values).map { IngredientId(it).value.lowercase() }
        .also { if (it.distinct().size != it.size) mealFail(FailureReason.INVALID_DATA) }
    private fun checkedStrings(values: List<String>): List<String> {
        if (values.size > 128 || values.distinct().size != values.size || values.any { it.isBlank() || it.length > 200 || it.any(Char::isISOControl) }) mealFail(FailureReason.INVALID_DATA)
        return values.toList()
    }
    private fun number(token: String): JsonElement {
        if (token.length > 128) mealFail(FailureReason.INVALID_DATA)
        return WireDocument.parse(token, WireLimits(maxBytes = 128)).also { if (it.kind != WireKind.NUMBER) mealFail(FailureReason.INVALID_DATA) }.json()
    }
    private fun strings(values: Collection<String>) = JsonArray(values.map(::JsonPrimitive))
    private fun list(value: JsonObject, name: String) = value[name]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
}

internal fun WireDocument.json() = Json.parseToJsonElement(encodeUtf8().decodeToString())
internal fun doc(value: JsonElement) = WireDocument.parse(value.toString(), WireLimits(maxBytes = 524_288))
internal class MealFailure(val reason: FailureReason) : Exception("Meal flow unavailable")
internal fun mealFail(reason: FailureReason): Nothing = throw MealFailure(reason)
internal fun <T> mealValue(result: PortResult<T>): T = when (result) {
    is PortResult.Value -> result.value
    is PortResult.Failure -> mealFail(result.reason)
}
internal inline fun <T> attempt(action: () -> T): PortResult<T> = try { PortResult.Value(action()) }
catch (failure: MealFailure) { PortResult.Failure(failure.reason) }
catch (_: IllegalArgumentException) { PortResult.Failure(FailureReason.INVALID_DATA) }
