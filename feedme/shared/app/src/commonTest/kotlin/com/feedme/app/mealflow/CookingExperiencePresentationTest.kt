package com.feedme.app.mealflow

import kotlin.test.*

/** Pure display checks only; they neither grant eligibility nor acknowledge cooking actions. */
class CookingExperiencePresentationTest {
    private val choices = MealInputChoices(emptyList(), listOf(MealInputChoice("bright", "Bright & fresh")))

    @Test fun unsetOptionalRefinementsDoNotInventATimeLimitOrTaste() {
        assertEquals("Time & taste: not set", mealRefinementSummary(MealFormValues(), choices))
    }

    @Test fun collapsedRefinementsKeepExactEnteredNumericAndMalformedTextVisible() {
        val values = MealFormValues(totalMinutes = "0015", activeMinutes = "0.", tasteTags = listOf("bright"))
        assertEquals("Total minutes entered: 0015 · Hands-on minutes entered: 0. · Bright & fresh", mealRefinementSummary(values, choices))
        assertEquals("0015", values.totalMinutes); assertEquals("0.", values.activeMinutes)
    }

    @Test fun unresolvedSelectedTasteIsStillVisibleWithoutGuessingALabel() {
        assertEquals("Unresolved taste (not-in-catalog)",
            mealRefinementSummary(MealFormValues(tasteTags = listOf("not-in-catalog")), choices))
    }

    @Test fun whitespaceInputIsNotPresentedAsAnUnsetOptionalValue() {
        assertEquals("Total minutes entered:   ", mealRefinementSummary(MealFormValues(totalMinutes = "  "), choices))
    }

    @Test fun progressCountsAreBoundedAndNeverClaimFoodReadiness() {
        assertEquals("0 of 4 steps marked complete", cookingProgressLabel(-1, 4))
        assertEquals("4 of 4 steps marked complete", cookingProgressLabel(8, 4))
        assertEquals("2 of 4 steps marked complete", cookingProgressLabel(2, 4))
    }

    @Test fun missingPlanDoesNotRenderAnInventedProgressFraction() {
        assertEquals("Step progress unavailable", cookingProgressLabel(2, 0))
        assertEquals("Step progress unavailable", cookingProgressLabel(2, -1))
    }

    @Test fun uncertainAndReceiptReadyActionsStayUnacknowledgedInPlainLanguage() {
        val unknown = cookingPendingSummary(pending("AWAITING_CONFIRMATION"))
        assertTrue(unknown.contains("may have completed")); assertTrue(unknown.contains("original"))
        assertTrue(unknown.contains("don’t start a replacement"))
        assertTrue(cookingPendingSummary(pending("RECEIPT_READY")).contains("still needs acknowledgement"))
    }

    @Test fun finalizationAndUnrecognizedStatesNeverBecomeSuccessCopy() {
        val finalization = pending("FINALIZATION_REQUIRED")
        assertEquals(finalization.message, cookingPendingSummary(finalization))
        assertTrue(cookingPendingSummary(pending("future-state")).contains("does not confirm it completed"))
        assertTrue(cookingPendingSummary(pending("RETRY_WAIT")).contains("Nothing retries automatically"))
    }

    private fun pending(phase: String) = CookingPendingRow("display-only", "createCookSession", phase,
        1, "OUTCOME_UNKNOWN", 0, canRetry = true, canDiscard = false)
}
