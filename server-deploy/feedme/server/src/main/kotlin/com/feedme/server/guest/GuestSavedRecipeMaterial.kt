package com.feedme.server.guest

import com.feedme.server.catalog.RecipeCatalogEntry
import com.feedme.server.catalog.recipeJsonIdentity
import com.feedme.server.memory.SavedRecipeFailure
import com.feedme.server.memory.SavedRecipeFailureCode
import java.math.BigDecimal
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Reject-only material comparison, not review or a copy grant. A mandatory separately
 * authorized copy permission is required even when this comparison succeeds. Established
 * copies must pass it too; a self-rehashed stored body/evidence cannot invent instructions. */
internal fun requireGuestSavedMaterial(source: RecipeCatalogEntry, recipe: JsonObject, allowReviewedScaling: Boolean) {
    fun refuse(): Nothing = throw SavedRecipeFailure(SavedRecipeFailureCode.RECIPE_UNAVAILABLE)
    fun number(value: JsonElement?): BigDecimal {
        val primitive = value as? JsonPrimitive ?: refuse()
        if (primitive.isString || primitive == JsonNull || primitive.booleanOrNull != null) refuse()
        return primitive.content.toBigDecimalOrNull() ?: refuse()
    }
    val lifecycle = setOf("version", "updatedAt", "reviewStatus", "recallReasonCode", "reviewedAt", "reviewerLabel")
    if (recipe["reviewStatus"] != JsonPrimitive("published")) refuse()
    val expected = JsonObject(source.recipe - lifecycle)
    val actual = JsonObject(recipe - lifecycle)
    if (recipeJsonIdentity(expected) == recipeJsonIdentity(actual)) return
    if (!allowReviewedScaling || listOf("linearQuantityScalingReviewed", "stepsValidForScalingRange", "effortValidForScalingRange")
            .any { source.review[it] != JsonPrimitive(true) }) refuse()
    if (recipeJsonIdentity(JsonObject(expected - setOf("servings", "ingredients"))) !=
        recipeJsonIdentity(JsonObject(actual - setOf("servings", "ingredients")))) refuse()
    val original = number(expected["servings"]); val requested = number(actual["servings"])
    val minimum = number(expected["scalingMin"]); val maximum = number(expected["scalingMax"])
    if (original.signum() <= 0 || requested.signum() <= 0 || minimum > maximum || original < minimum || original > maximum ||
        requested < minimum || requested > maximum || original.compareTo(requested) == 0) refuse()
    val before = expected["ingredients"] as? JsonArray ?: refuse()
    val after = actual["ingredients"] as? JsonArray ?: refuse()
    val units = (source.review["scalableUnits"] as? JsonArray)?.toSet() ?: refuse()
    if (before.isEmpty() || before.size != after.size) refuse()
    before.zip(after).forEach { (a, b) ->
        val old = a as? JsonObject ?: refuse(); val fresh = b as? JsonObject ?: refuse()
        if (old["unit"] !in units || recipeJsonIdentity(JsonObject(old - "quantity")) !=
            recipeJsonIdentity(JsonObject(fresh - "quantity"))) refuse()
        val oldQuantity = number(old["quantity"]); val freshQuantity = number(fresh["quantity"])
        if (oldQuantity.signum() <= 0 || freshQuantity.signum() <= 0 ||
            (oldQuantity * requested).compareTo(freshQuantity * original) != 0) refuse()
    }
}

/** Match the existing saved-store identity exactly, without floating-point conversion. */
internal fun guestSavedRecipeHash(recipe: JsonObject): String {
    fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { (k, v) -> "${JsonPrimitive(k)}:${canonical(v)}" }
        is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
        is JsonPrimitive -> if (value.isString || value == JsonNull || value.booleanOrNull != null) value.toString()
            else BigDecimal(value.content).stripTrailingZeros().toPlainString()
    }
    return MessageDigest.getInstance("SHA-256").digest(canonical(recipe).encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
