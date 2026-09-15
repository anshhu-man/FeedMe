package com.feedme.app.mealflow

import com.feedme.contracts.*
import com.feedme.mealflow.*
import kotlin.test.*

class MealPlanPresentationTest {
    private val ingredientId = "00000000-0000-4000-8000-00000000000a"
    private fun plan(status: String = "ready", quantity: String = "1.230000", recipeStatus: String = "published", recipe: Boolean = true): PlanWire {
        val snapshot = if (!recipe) "" else """, "recipeVersionId":"00000000-0000-4000-8000-000000000003", "recipeSnapshot":{
          "id":"00000000-0000-4000-8000-000000000003","recipeId":"00000000-0000-4000-8000-000000000002","version":1,
          "createdAt":"2026-09-14T00:00:00Z","updatedAt":"2026-09-14T00:00:00Z","title":"Synthetic recipe","reviewStatus":"$recipeStatus",
          "ingredients":[{"ingredientId":"$ingredientId","quantity":$quantity,"unit":"cup","optional":false,"preparation":"chopped"}],
          "steps":[{"stepId":"safety-a","position":1,"instruction":"Synthetic required instruction","ingredientIds":["$ingredientId"],
          "requiredEquipmentIds":["bowl"],"mandatorySafetyStep":true,"durationSeconds":60}],"servings":1.5000,"activeMinutes":10,"totalMinutes":15,
          "utensilCount":1,"equipmentIds":["bowl"],"modes":["assemble"],"tasteTags":["fresh"]} """
        val mode = if (status in setOf("ready", "recalled")) "\"mode\":\"assemble\"," else ""
        return PlanWire.from(WireDocument.parse("""{"id":"00000000-0000-4000-8000-000000000001","version":1,
          "createdAt":"2026-09-14T00:00:00Z","updatedAt":"2026-09-14T00:00:00Z",$mode"status":"$status",
          "constraints":{"ingredientIds":[],"energy":"assemble","equipmentIds":[],"servings":1.5,"hardExcludedIngredientIds":[]},
          "missingIngredients":[],"changes":[{"explanation":"Exact recorded change"}],"reasons":[{"code":"effortFit","label":"Exact recorded reason"}],
          "catalogRevision":"synthetic-catalog"$snapshot} """))
    }
    @Test fun quantityAndServingsRetainExactWireSpelling() {
        val view = MealPlanPresentation(plan(), true, mapOf(ingredientId to "Synthetic ingredient"))
        assertEquals("1.230000 cup · Synthetic ingredient · chopped", view.ingredientLine(view.recipe!!.ingredients.single()))
        assertEquals("1.5000", view.recipe!!.servings.jsonToken)
    }
    @Test fun largeExactNumbersNeverRoundThroughDouble() {
        val token = "9007199254740993.0000000001"
        val view = MealPlanPresentation(plan(quantity = token), true, emptyMap())
        assertTrue(view.ingredientLine(view.recipe!!.ingredients.single()).startsWith("$token cup"))
    }
    @Test fun absentIngredientLabelIsExplicitAndNeverGuessed() {
        val view = MealPlanPresentation(plan(), false, emptyMap())
        assertEquals(1, view.unresolvedIngredientCount)
        assertTrue(view.ingredientLine(view.recipe!!.ingredients.single()).contains("Ingredient label unavailable ($ingredientId)"))
    }
    @Test fun labelIdentityMatchingIsCaseInsensitiveWithoutChangingIds() {
        val view = MealPlanPresentation(plan(), true, mapOf(ingredientId.uppercase() to "Recorded name"))
        assertEquals("Recorded name", view.ingredientName(ingredientId))
        assertEquals(ingredientId, view.recipe!!.ingredients.single().ingredientId.value)
    }
    @Test fun recordedReasonsAndChangesAreNotGeneratedClaims() {
        val view = MealPlanPresentation(plan(), false, emptyMap())
        assertEquals(listOf("Exact recorded change"), view.changes)
        assertEquals(listOf("Exact recorded reason"), view.reasons)
    }
    @Test fun unresolvedModeStaysAbsentAndRecipeIsNotVisible() {
        val view = MealPlanPresentation(plan("needsConfirmation", recipe = false), false, emptyMap())
        assertIs<WireField.Missing>(view.plan.mode); assertFalse(view.recipeVisible)
        assertEquals("A little clarity first.", view.title)
    }
    @Test fun missingReadySnapshotDoesNotEnableRecipe() {
        assertFalse(MealPlanPresentation(plan(recipe = false), false, emptyMap()).recipeVisible)
    }
    @Test fun recalledOrRetiredRecipeDoesNotEnableInstructions() {
        assertFalse(MealPlanPresentation(plan("recalled"), true, emptyMap()).recipeVisible)
        assertFalse(MealPlanPresentation(plan(recipeStatus = "retired"), true, emptyMap()).recipeVisible)
        assertFalse(MealPlanPresentation(plan(recipeStatus = "recalled"), true, emptyMap()).recipeVisible)
    }
    @Test fun fullStepsAndSafetyFlagsAreRetainedWithoutDemoConversion() {
        val step = MealPlanPresentation(plan(), false, emptyMap()).recipe!!.steps.single()
        assertEquals("safety-a", step.stepId.value); assertTrue(step.mandatorySafetyStep)
        assertEquals("Synthetic required instruction", step.instruction)
        assertEquals("60", (step.durationSeconds as WireField.Value).value.jsonToken)
    }
    @Test fun everyPhaseHasExplicitPresentationIncludingUncertainty() {
        MealFlowPhase.entries.forEach { assertTrue(mealPhaseMessage(it).first.isNotBlank()); assertTrue(mealPhaseMessage(it).second.isNotBlank()) }
        assertTrue(mealPhaseMessage(MealFlowPhase.RESOLVING).second.contains("original request"))
    }
    @Test fun everyIssueHasRecoveryCopyExceptNone() {
        assertNull(mealIssueMessage(MealFlowIssue.NONE))
        MealFlowIssue.entries.filter { it != MealFlowIssue.NONE }.forEach { assertFalse(mealIssueMessage(it).isNullOrBlank()) }
        assertTrue(mealIssueMessage(MealFlowIssue.REPLAY_EXPIRED)!!.contains("do not recreate"))
    }
    @Test fun projectionLoggingIsRedacted() {
        assertFalse(MealPlanPresentation(plan(), true, emptyMap()).toString().contains("Synthetic"))
    }
}
