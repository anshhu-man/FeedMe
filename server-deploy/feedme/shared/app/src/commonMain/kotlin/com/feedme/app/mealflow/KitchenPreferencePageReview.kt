package com.feedme.app.mealflow

import com.feedme.app.blueprint.BlueprintPreferencePage
import com.feedme.contracts.WireDocument
import com.feedme.mealflow.KitchenPreferenceSavePage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** A projection of an already retained draft, not new choices or permission to save.
 * The controller independently binds the page save to the exact reviewed observation. */
internal class KitchenPreferencePageReview(
    val page: KitchenPreferenceSavePage,
    val body: WireDocument,
    val retainedDraft: WireDocument?,
)

internal fun kitchenPreferencePageReview(page: BlueprintPreferencePage,
    draft: WireDocument?): KitchenPreferencePageReview? {
    val (scope, fields) = when (page) {
        BlueprintPreferencePage.FOOD_PREFS -> KitchenPreferenceSavePage.FOOD_PREFERENCES to
            setOf("hardExcludedIngredientIds", "dietaryPatterns", "dislikedIngredientIds")
        BlueprintPreferencePage.EQUIPMENT -> KitchenPreferenceSavePage.EQUIPMENT to
            setOf("equipmentIds", "defaultServings")
        else -> return null
    }
    val values = draft?.let { Json.parseToJsonElement(it.encodeUtf8().decodeToString()).jsonObject }
        ?: return null
    val selected = values.filterKeys { it in fields }
    if (selected.isEmpty()) return null
    val retained = values.filterKeys { it !in fields }
    return KitchenPreferencePageReview(scope, WireDocument.parse(JsonObject(selected).toString()),
        retained.takeIf { it.isNotEmpty() }?.let { WireDocument.parse(JsonObject(it).toString()) })
}
