package com.feedme.server.memory

import com.feedme.server.catalog.RecipeCatalogEntry
import com.feedme.server.catalog.recipeJsonIdentity
import java.math.BigDecimal
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Reject-only comparison against independently governed catalog/copy material. This is
 * not a grant: the caller must hold a current RecipeCopyRightsHandle for the exact source. */
internal fun requireAccountSavedMaterial(source: RecipeCatalogEntry, recipe: JsonObject, scaling: Boolean) {
    fun reject(): Nothing = throw SavedRecipeFailure(SavedRecipeFailureCode.RECIPE_UNAVAILABLE)
    fun number(value: JsonElement?): BigDecimal {
        val p = value as? JsonPrimitive ?: reject()
        if (p.isString || p == JsonNull || p.booleanOrNull != null) reject()
        return p.content.toBigDecimalOrNull() ?: reject()
    }
    if (recipe["reviewStatus"] != JsonPrimitive("published")) reject()
    val lifecycle = setOf("version", "updatedAt", "reviewStatus", "recallReasonCode", "reviewedAt", "reviewerLabel")
    val before = JsonObject(source.recipe - lifecycle); val after = JsonObject(recipe - lifecycle)
    if (recipeJsonIdentity(before) == recipeJsonIdentity(after)) return
    if (!scaling || listOf("linearQuantityScalingReviewed", "stepsValidForScalingRange", "effortValidForScalingRange")
            .any { source.review[it] != JsonPrimitive(true) }) reject()
    if (recipeJsonIdentity(JsonObject(before - setOf("servings", "ingredients"))) !=
        recipeJsonIdentity(JsonObject(after - setOf("servings", "ingredients")))) reject()
    val original = number(before["servings"]); val requested = number(after["servings"])
    val minimum = number(before["scalingMin"]); val maximum = number(before["scalingMax"])
    if (original.signum() <= 0 || requested.signum() <= 0 || minimum > maximum || original < minimum || original > maximum ||
        requested < minimum || requested > maximum || original.compareTo(requested) == 0) reject()
    val old = before["ingredients"] as? JsonArray ?: reject(); val fresh = after["ingredients"] as? JsonArray ?: reject()
    val units = (source.review["scalableUnits"] as? JsonArray)?.toSet() ?: reject()
    if (old.isEmpty() || old.size != fresh.size) reject()
    old.zip(fresh).forEach { (a, b) ->
        val x = a as? JsonObject ?: reject(); val y = b as? JsonObject ?: reject()
        if (x["unit"] !in units || recipeJsonIdentity(JsonObject(x - "quantity")) != recipeJsonIdentity(JsonObject(y - "quantity"))) reject()
        val q = number(x["quantity"]); val r = number(y["quantity"])
        if (q.signum() <= 0 || r.signum() <= 0 || (q * requested).compareTo(r * original) != 0) reject()
    }
}

/** Exactly the SavedRecipeStore recipe digest, including canonical arbitrary-precision numbers. */
internal fun accountSavedRecipeHash(recipe: JsonObject): String {
    fun canonical(v: JsonElement): String = when (v) {
        is JsonObject -> v.toSortedMap().entries.joinToString(",", "{", "}") { (k, value) -> "${JsonPrimitive(k)}:${canonical(value)}" }
        is JsonArray -> v.joinToString(",", "[", "]") { canonical(it) }
        is JsonPrimitive -> if (v.isString || v == JsonNull || v.booleanOrNull != null) v.toString()
            else BigDecimal(v.content).stripTrailingZeros().toPlainString()
    }
    return accountSavedSha(canonical(recipe))
}
internal fun accountSavedSha(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.encodeToByteArray(throwOnInvalidSequence = true)).joinToString("") { "%02x".format(it.toInt() and 255) }
