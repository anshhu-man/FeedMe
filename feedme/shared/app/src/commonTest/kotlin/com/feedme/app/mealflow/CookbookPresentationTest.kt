package com.feedme.app.mealflow

import com.feedme.contracts.*
import com.feedme.kitchen.SavedRecipeAvailability
import kotlin.test.*

/** Display-only canonical SavedRecipe fixtures; no Plan, copy authority or service is fabricated. */
class CookbookPresentationTest {
    @Test fun savedIdentityAndRecipeLineageAreNotPromotedIntoPlanIdentity() {
        val saved = saved(); val view = view(saved)
        assertEquals(SAVED, saved.id.value); assertEquals(VERSION, view.recipe!!.id.value)
        assertNotEquals(saved.id.value, view.recipe!!.id.value)
        assertEquals("My saved copy", view.title); assertEquals("Original recipe title", view.recipe!!.title)
    }
    @Test fun quantitiesUnitsPreparationAndOptionalRemainExactWithoutInventedName() {
        val ingredient = saved().snapshot.ingredients.single()
        assertEquals("1.230000 g Label unavailable · $INGREDIENT · finely chopped · optional", savedIngredientLine(ingredient, emptyMap()))
        assertEquals("1.230000 g Actual ingredient label · finely chopped · optional", savedIngredientLine(ingredient, mapOf(INGREDIENT to "Actual ingredient label")))
    }
    @Test fun mandatoryInstructionsAndNumericLexemesSurviveSavedPresentation() {
        val recipe = view().recipe!!
        assertEquals("1.5000", recipe.servings.jsonToken)
        assertTrue(recipe.steps.single().mandatorySafetyStep); assertEquals("Exact retained instruction", recipe.steps.single().instruction)
    }
    @Test fun unavailableRecalledOrDamagedObservationsNeverExposeRecipeInstructions() {
        for (availability in SavedRecipeAvailability.entries.filter { it != SavedRecipeAvailability.AVAILABLE }) {
            assertNull(view(availability = availability).recipe)
            assertEquals("Saved recipe unavailable", view(availability = availability).title)
        }
        assertEquals("Saved recipe unavailable", view(saved(recalled = true)).title)
        assertNull(view(saved(recalled = true)).recipe); assertNull(view(saved(review = "recalled")).recipe)
        assertNull(SavedRecipePresentation(null, SavedRecipeAvailability.UNAVAILABLE, null).recipe)
    }
    @Test fun remoteObservationCannotClaimDownloadedBundle() {
        val remote = view(revision = null); assertFalse(remote.downloaded)
        assertTrue(remote.notice.contains("not downloaded")); assertFalse(remote.notice.contains("verified"))
        val local = view(revision = 7); assertTrue(local.downloaded); assertTrue(local.notice.contains("historical"))
    }
    @Test fun historicalRetiredSavedSnapshotIsReadableButNotNewCookingOrCopyAuthority() {
        val retained = view(saved(review = "retired"), revision = 4)
        assertNotNull(retained.recipe); assertTrue(retained.notice.contains("not a current cooking or rights check"))
        assertFalse(retained.toString().contains(SAVED)); assertFalse(retained.toString().contains("My saved copy"))
    }
    @Test fun unknownApplyCopyOverridesAnyStalePositiveAcknowledgementProjection() {
        assertTrue(cookbookAcknowledgementText(true, true).contains("unresolved"))
        assertTrue(cookbookAcknowledgementText(true, false).contains("does not repair"))
        assertFalse(cookbookAcknowledgementText(true, true).contains("were acknowledged"))
    }
    @Test fun onlyCurrentActionAcknowledgementUsesSuccessCopy() {
        assertTrue(cookbookAcknowledgementText(false, true).contains("were acknowledged"))
        assertTrue(cookbookAcknowledgementText(false, false).contains("Historical or read-only"))
        assertTrue(cookbookAcknowledgementText(false, false).contains("No new Save"))
    }
    @Test fun unavailableLocalRemovalAffordanceRequiresRetainedVersionButNeverEnablesInstructions() {
        for (availability in listOf(SavedRecipeAvailability.RECALLED, SavedRecipeAvailability.UNAVAILABLE)) {
            val local = SavedRecipePresentation(null, availability, 7, "\"1\"")
            assertTrue(local.removalReviewAvailable); assertNull(local.recipe)
            assertEquals("Saved recipe unavailable", local.title)
            assertFalse(SavedRecipePresentation(null, availability, null, "\"1\"").removalReviewAvailable)
            assertFalse(SavedRecipePresentation(null, availability, 7, null).removalReviewAvailable)
        }
        assertFalse(SavedRecipePresentation(null, SavedRecipeAvailability.INTEGRITY_FAILURE, 7, "\"1\"").removalReviewAvailable)
        assertTrue(view().removalReviewAvailable)
    }
    @Test fun redactedRemovalDescriptionCannotLeakEvenAnAccidentallySuppliedOldTitle() {
        val text = cookbookRemovalDescription("Private old title", true)
        assertFalse(text.contains("Private old title")); assertTrue(text.contains("Instructions and attribution stay hidden"))
        assertTrue(text.contains("source and existing cooking progress are not erased"))
        assertTrue(text.contains("never an automatic rebase"))
        assertTrue(cookbookRemovalDescription("Readable title", false).contains("Readable title"))
    }
    private fun view(saved: SavedRecipeWire = saved(), availability: SavedRecipeAvailability = SavedRecipeAvailability.AVAILABLE,
        revision: Long? = null) = SavedRecipePresentation(saved, availability, revision)
    private fun saved(recalled: Boolean = false, review: String = "published") = SavedRecipeWire.from(WireDocument.parse("""{
      "id":"$SAVED","version":1,"createdAt":"2026-09-14T00:00:00Z","updatedAt":"2026-09-14T00:00:00Z",
      "title":"My saved copy","sourceType":"ownPlan","contentLicense":"privateCopyOnly","recalled":$recalled,
      "snapshot":{"id":"$VERSION","recipeId":"00000000-0000-4000-8000-000000000002","version":1,
      "createdAt":"2026-09-14T00:00:00Z","updatedAt":"2026-09-14T00:00:00Z","title":"Original recipe title","reviewStatus":"$review",
      "servings":1.5000,"activeMinutes":1,"totalMinutes":1,"utensilCount":1,"equipmentIds":["bowl"],"modes":["assemble"],"tasteTags":[],
      "ingredients":[{"ingredientId":"$INGREDIENT","quantity":1.230000,"unit":"g","optional":true,"preparation":"finely chopped"}],
      "steps":[{"stepId":"first","position":1,"instruction":"Exact retained instruction","ingredientIds":[],"requiredEquipmentIds":[],"mandatorySafetyStep":true}]}}"""))
    private companion object {
        const val SAVED = "00000000-0000-4000-8000-000000000001"
        const val VERSION = "00000000-0000-4000-8000-000000000003"
        const val INGREDIENT = "00000000-0000-4000-8000-000000000004"
    }
}
