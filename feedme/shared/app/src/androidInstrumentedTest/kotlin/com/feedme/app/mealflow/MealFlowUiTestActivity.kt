package com.feedme.app.mealflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feedme.app.FeedMeTheme
import com.feedme.contracts.*
import com.feedme.mealflow.*

/** Instrumentation-only render/event fixture. No identity, transport, catalog or store authority. */
class MealFlowUiTestActivity : ComponentActivity() {
    val meal = mutableStateOf(testMeal(MealFlowScreen.REQUEST))
    val form = mutableStateOf(MealFormState(MealFormValues(), false, null))
    val actionsSeen = mutableListOf<String>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FeedMeTheme {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Text("SYNTHETIC UI TEST · not a production recipe", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    MealJourneyScreen(meal.value, testPicker(), form.value,
                        MealInputChoices(listOf(MealInputChoice("bowl", "Synthetic bowl")), listOf(MealInputChoice("fresh", "Fresh"))),
                        MealScreenActions(
                            back = { actionsSeen += "back" },
                            edit = { transform -> form.value = MealFormState(transform(form.value.values!!), true, null) },
                            searchText = { form.value = MealFormState(form.value.values, form.value.dirty, null, searchText = it) },
                            search = { actionsSeen += "search" }, moreIngredients = { actionsSeen += "moreIngredients" },
                            pantry = { actionsSeen += "pantry" }, morePantry = { actionsSeen += "morePantry" },
                            context = { actionsSeen += "context" }, saveDraft = { actionsSeen += "save" },
                            find = { actionsSeen += "find" }, retry = { actionsSeen += "retry" },
                            alternative = { actionsSeen += "alternative" }, previous = { actionsSeen += "previous" },
                            recipe = { actionsSeen += "recipe"; meal.value = testMeal(MealFlowScreen.RECIPE) }))
                }
            }
        }
    }
    companion object {
        const val ingredient = "00000000-0000-4000-8000-00000000000a"
        fun testMeal(screen: MealFlowScreen, phase: MealFlowPhase = if (screen == MealFlowScreen.REQUEST) MealFlowPhase.EDITING else MealFlowPhase.READY,
            issue: MealFlowIssue = MealFlowIssue.NONE) = MealScreenState(phase, screen, issue, null,
            if (screen == MealFlowScreen.REQUEST) null else testPlan(), true, true, true, false, null)
        fun testPicker() = MealPickerPresentation(IngredientPickerPhase.READY,
            listOf(IngredientRow(ingredient, "Synthetic cucumber", false)), listOf(IngredientRow(ingredient, "Synthetic cucumber", false)),
            emptyList(), false, false, IngredientPickerIssue.NONE, null)
        fun testPlan(): PlanWire = PlanWire.from(WireDocument.parse("""{
            "id":"00000000-0000-4000-8000-000000000001","version":1,"createdAt":"2026-09-14T00:00:00Z","updatedAt":"2026-09-14T00:00:00Z",
            "status":"ready","mode":"assemble","constraints":{"ingredientIds":[],"energy":"assemble","equipmentIds":[],"servings":1.5,"hardExcludedIngredientIds":[]},
            "missingIngredients":[],"reasons":[{"code":"effortFit","label":"Synthetic reason from fixture"}],"changes":[],"catalogRevision":"synthetic-only",
            "recipeVersionId":"00000000-0000-4000-8000-000000000003","recipeSnapshot":{
            "id":"00000000-0000-4000-8000-000000000003","recipeId":"00000000-0000-4000-8000-000000000002","version":1,
            "createdAt":"2026-09-14T00:00:00Z","updatedAt":"2026-09-14T00:00:00Z","title":"Synthetic crunch bowl","reviewStatus":"published",
            "summary":"Fixture content only — not reviewed cooking guidance.",
            "ingredients":[{"ingredientId":"$ingredient","quantity":1.230000,"unit":"cup","optional":false,"preparation":"chopped"}],
            "steps":[{"stepId":"required-a","position":1,"instruction":"Synthetic mandatory instruction — not cooking guidance.","ingredientIds":["$ingredient"],
            "requiredEquipmentIds":["bowl"],"mandatorySafetyStep":true,"durationSeconds":60}],"servings":1.5000,"activeMinutes":10,"totalMinutes":15,
            "utensilCount":1,"equipmentIds":["bowl"],"modes":["assemble"],"tasteTags":["fresh"]}}"""))
    }
}
