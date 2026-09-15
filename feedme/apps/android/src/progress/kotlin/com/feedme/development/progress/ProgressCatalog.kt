package com.feedme.development.progress

import com.feedme.app.mealflow.MealInputChoice
import com.feedme.app.mealflow.MealInputChoices
import com.feedme.contracts.*
import com.feedme.planning.*
import kotlinx.serialization.json.*

/** Source-controlled synthetic content, NOT reviewed cooking instructions or allergy advice. */
internal object ProgressCatalog {
    const val maxResponseBytes = 262_144
    const val ingredientId = "00000000-0000-4000-8000-000000000011"
    const val recipeVersionId = "00000000-0000-4000-8000-000000000301"
    const val policyVersion = "synthetic-progress-policy-v1"
    const val fixedTime = "2026-09-14T00:00:00Z"
    val choices = MealInputChoices(listOf(MealInputChoice("bowl", "Bowl (preview)")),
        listOf(MealInputChoice("crunch", "Crunch (preview)")))

    val ingredient: JsonObject = Json.parseToJsonElement("""{"id":"$ingredientId","version":1,
        "createdAt":"$fixedTime","updatedAt":"$fixedTime","name":"Synthetic cucumber",
        "aliases":["Preview ingredient"],"category":"synthetic fixture","supportedUnits":["g"]}""").jsonObject

    val recipe: JsonObject = Json.parseToJsonElement("""{"id":"$recipeVersionId","recipeId":"00000000-0000-4000-8000-000000000302",
        "version":1,"createdAt":"$fixedTime","updatedAt":"$fixedTime","title":"Synthetic crunch bowl · preview only",
        "reviewStatus":"published","reviewedAt":"$fixedTime","estimateBasis":"reviewerEstimate",
        "servings":1,"scalingMin":1,"scalingMax":4,"activeMinutes":1,"totalMinutes":2,"cleanupMinutes":1,"utensilCount":1,
        "equipmentIds":["bowl"],"modes":["assemble"],"tasteTags":["crunch"],"preparationTags":["noHeat","oneBowl"],
        "ingredients":[{"ingredientId":"$ingredientId","quantity":100,"unit":"g","optional":false,"preparation":"Synthetic quantity; not cooking guidance"}],
        "steps":[{"stepId":"first","position":1,"instruction":"Preview step 1: this synthetic recipe demonstrates retained progress, not food-safety guidance.",
        "ingredientIds":["$ingredientId"],"requiredEquipmentIds":["bowl"],"mandatorySafetyStep":true,"durationSeconds":60},
        {"stepId":"second","position":2,"instruction":"Preview step 2: mark this step complete to try the completion screen.",
        "ingredientIds":["$ingredientId"],"requiredEquipmentIds":["bowl"],"mandatorySafetyStep":false}]}""").jsonObject

    val planning: PlanningCatalog = PlanningCatalog("synthetic-progress-catalog-v1", "synthetic-progress-taxonomy-v1",
        listOf(PlanningCandidate(RecipeVersionWire.from(WireDocument.parse(recipe.toString())),
            ReviewedPlanningEvidence("SYNTHETIC-NOT-REVIEWER-APPROVAL", policyVersion, PlanningContentKind.MEAL,
                PlanningEnergy.ASSEMBLE, false, false, true, emptySet(), true, true, true, setOf("g")))),
        listOf(IngredientComposition(ingredientId, emptySet())))
}
