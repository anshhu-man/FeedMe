package com.feedme.android

import com.feedme.app.mealflow.MealInputChoice
import com.feedme.app.mealflow.MealInputChoices
import com.feedme.mealflow.*
import kotlinx.serialization.json.*

/** Explicit public client integration bounds and reviewed display metadata, NOT service
 * availability, catalog authority, consent or an eligibility grant. No policy defaults.
 * Actual requests still use the account owner's canonical endpoint/current credentials. */
internal class PrivateMealConfiguration private constructor(
    val meals: MealFlowPolicy,
    val ingredients: IngredientPickerPolicy,
    val kitchen: KitchenInputPolicy,
    val cooking: CookingFlowPolicy,
    val cookbook: CookbookPolicy,
    val choices: MealInputChoices,
    val mealInterpretationEnabled: Boolean,
    val accountExportEnabled: Boolean,
    val notificationReadAcknowledgementsEnabled: Boolean,
    val canonical: JsonObject,
) {
    override fun toString() = "PrivateMealConfiguration(<redacted>)"

    companion object {
        private val fields = setOf("schema", "draftRetentionMillis", "replayWindowMillis", "historyLimit", "issuedIdCapacity",
            "ingredientPageSize", "maxIngredientPages", "maxCachedIngredients", "ingredientCacheRetentionMillis",
            "pantryPageSize", "maxPantryPages", "maxPantryItems", "maxKitchenDrafts", "kitchenMaxResponseBytes",
            "cookingConfirmationMillis", "cookingMaxSessionBytes", "cookingMaxPreferencesBytes",
            "cookbookPageSize", "cookbookConfirmationMillis", "equipment", "tastes")

        fun parse(element: JsonElement): PrivateMealConfiguration {
            val obj = element as? JsonObject ?: error("Invalid private product configuration")
            require(obj.keys.containsAll(fields) && obj.keys.all { it in fields || it in setOf("mealInterpretationEnabled",
                "directRecipeMakeMineEnabled", "savedMakeMineEnabled", "postMakeMineEnabled", "accountExportEnabled", "makeAgainEnabled",
                "notificationReadAcknowledgementsEnabled") })
            // A client opt-in only. The actual service still requires current account,
            // audience, public catalog and provider configuration on every request.
            val interpretationEnabled = obj["mealInterpretationEnabled"]?.let {
                require(it is JsonPrimitive && !it.isString)
                requireNotNull(it.booleanOrNull)
            } ?: false
            val postMakeMineEnabled = obj["postMakeMineEnabled"]?.let {
                require(it is JsonPrimitive && !it.isString)
                requireNotNull(it.booleanOrNull)
            } ?: false
            val directRecipeMakeMineEnabled = obj["directRecipeMakeMineEnabled"]?.let {
                require(it is JsonPrimitive && !it.isString)
                requireNotNull(it.booleanOrNull)
            } ?: false
            val savedMakeMineEnabled = obj["savedMakeMineEnabled"]?.let {
                require(it is JsonPrimitive && !it.isString)
                requireNotNull(it.booleanOrNull)
            } ?: false
            val accountExportEnabled = obj["accountExportEnabled"]?.let {
                require(it is JsonPrimitive && !it.isString)
                requireNotNull(it.booleanOrNull)
            } ?: false
            val makeAgainEnabled = obj["makeAgainEnabled"]?.let {
                require(it is JsonPrimitive && !it.isString)
                requireNotNull(it.booleanOrNull)
            } ?: false
            val notificationReadAcknowledgementsEnabled = obj["notificationReadAcknowledgementsEnabled"]?.let {
                require(it is JsonPrimitive && !it.isString)
                requireNotNull(it.booleanOrNull)
            } ?: false
            fun number(key: String): Long {
                val value = obj.getValue(key) as? JsonPrimitive ?: error("Invalid product bound")
                require(!value.isString && value.content.matches(Regex("0|[1-9][0-9]{0,11}")))
                return value.content.toLong()
            }
            fun integer(key: String) = number(key).also { require(it <= Int.MAX_VALUE) }.toInt()
            fun choices(key: String): List<MealInputChoice> {
                val values = obj.getValue(key) as? JsonArray ?: error("Invalid product choices")
                require(values.size <= 128)
                return values.map { value ->
                    val item = value as? JsonObject ?: error("Invalid product choice")
                    require(item.keys == setOf("id", "label"))
                    fun text(name: String): String {
                        val text = item.getValue(name) as? JsonPrimitive ?: error("Invalid product metadata")
                        require(text.isString)
                        return text.content.also {
                            require(it.isNotBlank() && it.length <= 200 && it.none(Char::isISOControl))
                            it.encodeToByteArray(throwOnInvalidSequence = true)
                        }
                    }
                    MealInputChoice(text("id"), text("label"))
                }
            }
            require(number("schema") == 1L)
            // Owned catalog-plan simplification and adaptation use the actual journal,
            // quota, command owner and derived read/cook/save authority. Direct Recipe,
            // Saved and Post root adaptation each have a separate explicit opt-in, off
            // in legacy configs; their actual source resolvers still check current rights.
            // Compiled capabilities are not deployment health or permission grants and
            // never grant authority through public JSON.
            val meals = MealFlowPolicy(number("draftRetentionMillis"), number("replayWindowMillis"),
                integer("historyLimit"), integer("issuedIdCapacity"),
                simplificationEnabled = true, adaptationEnabled = true,
                directRecipeMakeMineEnabled = directRecipeMakeMineEnabled,
                savedMakeMineEnabled = savedMakeMineEnabled, postMakeMineEnabled = postMakeMineEnabled)
            val ingredients = IngredientPickerPolicy(integer("ingredientPageSize"), integer("maxIngredientPages"),
                integer("maxCachedIngredients"), number("ingredientCacheRetentionMillis"))
            val kitchen = KitchenInputPolicy(integer("pantryPageSize"), integer("maxPantryPages"),
                integer("maxPantryItems"), integer("maxKitchenDrafts"), integer("kitchenMaxResponseBytes"))
            val cooking = CookingFlowPolicy(number("cookingConfirmationMillis"), integer("cookingMaxSessionBytes"),
                integer("cookingMaxPreferencesBytes"))
            val cookbook = CookbookPolicy(integer("cookbookPageSize"), number("cookbookConfirmationMillis"),
                makeAgainEnabled = makeAgainEnabled)
            val choices = MealInputChoices(choices("equipment"), choices("tastes"))
            val canonical = buildJsonObject {
                (fields - setOf("equipment", "tastes")).sorted().forEach { put(it, number(it)) }
                fun values(items: List<MealInputChoice>) = JsonArray(items.map { buildJsonObject {
                    put("id", it.id); put("label", it.label)
                } })
                put("equipment", values(choices.equipment)); put("tastes", values(choices.tastes))
                // Keep absent legacy bindings exact; explicit enablement/disablement
                // participates in the same native configuration binding as other policy.
                if (obj.containsKey("mealInterpretationEnabled")) put("mealInterpretationEnabled", interpretationEnabled)
                if (obj.containsKey("postMakeMineEnabled")) put("postMakeMineEnabled", postMakeMineEnabled)
                if (obj.containsKey("directRecipeMakeMineEnabled")) put("directRecipeMakeMineEnabled", directRecipeMakeMineEnabled)
                if (obj.containsKey("savedMakeMineEnabled")) put("savedMakeMineEnabled", savedMakeMineEnabled)
                if (obj.containsKey("accountExportEnabled")) put("accountExportEnabled", accountExportEnabled)
                if (obj.containsKey("makeAgainEnabled")) put("makeAgainEnabled", makeAgainEnabled)
                if (obj.containsKey("notificationReadAcknowledgementsEnabled")) put("notificationReadAcknowledgementsEnabled", notificationReadAcknowledgementsEnabled)
            }
            return PrivateMealConfiguration(meals, ingredients, kitchen, cooking, cookbook, choices, interpretationEnabled, accountExportEnabled,
                notificationReadAcknowledgementsEnabled, canonical)
        }
    }
}
