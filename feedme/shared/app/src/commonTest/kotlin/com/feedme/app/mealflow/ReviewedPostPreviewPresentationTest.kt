package com.feedme.app.mealflow

import com.feedme.mealflow.social.PostDraftScreen
import kotlin.test.*

/** Pure presentation/identity oracles, not Compose rendering or controller authority tests. */
class ReviewedPostPreviewPresentationTest {
    @Test fun currentFormatOnlyNeverOffersALocalUpgrade() {
        for (needsUpgrade in listOf(false, true))
            assertFalse(reviewedPostLocalUpgradeOffered(ReviewedPostLegacyFormatUiPolicy.CURRENT_FORMAT_ONLY, needsUpgrade))
    }

    @Test fun existingExplicitUpgradeRouteStillRequiresAnOlderFormat() {
        assertTrue(reviewedPostLocalUpgradeOffered(ReviewedPostLegacyFormatUiPolicy.EXPLICIT_UPGRADE, true))
        assertFalse(reviewedPostLocalUpgradeOffered(ReviewedPostLegacyFormatUiPolicy.EXPLICIT_UPGRADE, false))
    }

    @Test fun onlyTheExactReturnedHiddenStateCanRequestOuterExit() {
        val returned = Any()
        assertTrue(draftBackExitReady(returned, returned, PostDraftScreen.HIDDEN))
        assertFalse(draftBackExitReady(returned, Any(), PostDraftScreen.HIDDEN))
    }

    @Test fun equalLookingNewerStatesDoNotAcceptAnOldBackReturn() {
        data class Display(val caption: String)
        val old = Display("unchanged")
        val newer = Display("unchanged")
        assertEquals(old, newer)
        assertFalse(draftBackExitReady(old, newer, PostDraftScreen.HIDDEN))
    }

    @Test fun editorToListAndOtherNonHiddenBackResultsDoNotExit() {
        val returned = Any()
        for (screen in PostDraftScreen.entries.filter { it != PostDraftScreen.HIDDEN })
            assertFalse(draftBackExitReady(returned, returned, screen))
        assertFalse(draftBackExitReady(returned, returned, null))
    }

    @Test fun hiddenObservationWithoutAnExplicitSuccessfulReturnCannotExit() {
        val current = Any()
        // A terminal publication can make the editor HIDDEN. No Back result means no exit.
        assertFalse(draftBackExitReady(null, current, PostDraftScreen.HIDDEN))
        assertFalse(draftBackExitReady(null, current, null))
    }
}
