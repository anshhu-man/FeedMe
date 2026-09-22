package com.feedme.server.staff

import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

/** Unreviewed proposal validation only. Does not invoke the published-catalog codec,
 * approve ingredients, infer rights or turn authored estimateBasis into review evidence. */
internal object SupabaseStaffRecipeDraftCodec {
    private val validator by lazy { ContractBodyValidator.bundled() }
    fun input(body: JsonObject, maxBytes: Int): JsonObject {
        if (bytes(body).size > maxBytes || validator.validateSchema("RecipeDraft", bytes(body)) != BodyValidationResult.Valid) invalid()
        text(body, "title", 120, required = true)
        body["summary"]?.let { text(body, "summary", 1000) }
        body["estimateNote"]?.let { text(body, "estimateNote", 500) }
        for (field in listOf("servings", "scalingMin", "scalingMax")) body[field]?.let {
            val number = it.jsonPrimitive.content.toBigDecimal()
            if (number < BigDecimal("0.1") || number > BigDecimal(1000)) invalid()
        }
        val servings = body.getValue("servings").jsonPrimitive.content.toBigDecimal()
        if (body["scalingMin"]?.jsonPrimitive?.content?.toBigDecimal()?.let { it > servings } == true ||
            body["scalingMax"]?.jsonPrimitive?.content?.toBigDecimal()?.let { it < servings } == true) invalid()
        for (field in listOf("activeMinutes", "totalMinutes", "waitingMinutes", "cleanupMinutes")) body[field]?.let {
            if (it.jsonPrimitive.content.toBigDecimal().longValueExact() !in 0..10080) invalid()
        }
        if (body.getValue("activeMinutes").jsonPrimitive.content.toBigDecimal() > body.getValue("totalMinutes").jsonPrimitive.content.toBigDecimal() ||
            body.getValue("utensilCount").jsonPrimitive.content.toBigDecimal().longValueExact() !in 0..1000) invalid()
        strings(body, "equipmentIds", 64, 128); strings(body, "modes", 3, 16)
        body["tasteTags"]?.let { strings(body, "tasteTags", 64, 128) }
        body["preparationTags"]?.let { strings(body, "preparationTags", 6, 32) }
        val ingredients = body.getValue("ingredients").jsonArray
        val steps = body.getValue("steps").jsonArray
        if (ingredients.size > 128 || steps.size > 128) invalid()
        for (value in ingredients) {
            val item = value.jsonObject; text(item, "unit", 128, true)
            item["preparation"]?.let { text(item, "preparation", 500) }
            if (item.getValue("quantity").jsonPrimitive.content.toBigDecimal() > BigDecimal(1_000_000)) invalid()
        }
        val stepIds = mutableSetOf<String>(); val positions = mutableSetOf<Long>()
        for (value in steps) {
            val step = value.jsonObject; text(step, "stepId", 128, true); text(step, "instruction", 4000, true)
            strings(step, "ingredientIds", 128, 36); strings(step, "requiredEquipmentIds", 64, 128)
            val position = step.getValue("position").jsonPrimitive.content.toBigDecimal().longValueExact()
            if (position !in 1..128 || !positions.add(position) || !stepIds.add(step.getValue("stepId").jsonPrimitive.content)) invalid()
            step["durationSeconds"]?.let { if (it.jsonPrimitive.content.toBigDecimal().longValueExact() !in 0..604800) invalid() }
        }
        // Empty arrays remain valid incomplete drafts, not ready-to-submit assertions.
        return body
    }
    fun snapshot(input: JsonObject, recipeId: UUID, id: UUID, version: Long, createdAt: Instant, updatedAt: Instant): JsonObject =
        JsonObject(input + mapOf("id" to JsonPrimitive(id.toString()), "recipeId" to JsonPrimitive(recipeId.toString()),
            "version" to JsonPrimitive(version), "createdAt" to JsonPrimitive(createdAt.toString()), "updatedAt" to JsonPrimitive(updatedAt.toString()),
            "reviewStatus" to JsonPrimitive("draft"), "tasteTags" to (input["tasteTags"] ?: JsonArray(emptyList()))))
            .also { response("RecipeVersion", it) }
    fun response(schema: String, body: JsonObject) {
        if (validator.validateSchema(schema, bytes(body)) != BodyValidationResult.Valid) unavailable()
    }
    fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { (key, child) -> "${JsonPrimitive(key)}:${canonical(child)}" }
        is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
        is JsonPrimitive -> if (value.isString || value == JsonNull || value.booleanOrNull != null) value.toString()
            else value.content.toBigDecimal().stripTrailingZeros().toPlainString()
    }
    fun sha(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray(throwOnInvalidSequence = true))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun bytes(body: JsonObject) = body.toString().encodeToByteArray(throwOnInvalidSequence = true)
    private fun strings(body: JsonObject, field: String, maxItems: Int, maxChars: Int) {
        val values = body.getValue(field).jsonArray.map { it.jsonPrimitive.content }
        if (values.size > maxItems || values.distinct().size != values.size ||
            values.any { it.isBlank() || it.length > maxChars || it.any(Char::isISOControl) }) invalid()
    }
    private fun text(body: JsonObject, field: String, maxChars: Int, required: Boolean = false) {
        val value = body.getValue(field).jsonPrimitive.content
        if (value.length > maxChars || required && value.isBlank() || value.any { it.isISOControl() && it !in "\n\r\t" }) invalid()
    }
    private fun invalid(): Nothing = throw SupabaseStaffRecipeFailure(SupabaseStaffRecipeFailureCode.INPUT_INVALID)
    private fun unavailable(): Nothing = throw SupabaseStaffRecipeFailure(SupabaseStaffRecipeFailureCode.STORAGE_UNAVAILABLE)
}

internal enum class SupabaseStaffRecipeFailureCode(val status: Int) {
    INPUT_INVALID(422), UNAVAILABLE(404), AUTHOR_REQUIRED(403), VERSION_CONFLICT(412), CURSOR_INVALID(409),
    RESPONSE_TOO_LARGE(422), NOT_CONFIGURED(503), STORAGE_UNAVAILABLE(503),
    STATE_CONFLICT(409), INDEPENDENT_REVIEW_REQUIRED(403),
}
internal class SupabaseStaffRecipeFailure(val code: SupabaseStaffRecipeFailureCode) :
    RuntimeException("Staff draft unavailable: ${code.name}")
