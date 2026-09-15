package com.feedme.app.mealflow

import com.feedme.mealflow.social.PostDraftJournalFormat
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Presentation restrictions only, not controller admission or native rendering evidence. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewedPostReadOnlyPresentationTest {
    @Test fun previewRequiresActualCurrentFormatBeforeAnyMutationControls() {
        assertFalse(reviewedDraftMutationControlsAllowed(true, PostDraftJournalFormat.NOT_OBSERVED))
        assertFalse(reviewedDraftMutationControlsAllowed(true, PostDraftJournalFormat.LEGACY))
        assertTrue(reviewedDraftMutationControlsAllowed(true, PostDraftJournalFormat.CURRENT))
        for (format in listOf(PostDraftJournalFormat.LEGACY, PostDraftJournalFormat.NOT_OBSERVED)) {
            val writable = reviewedDraftMutationControlsAllowed(true, format)
            assertTrue(reviewedPostDraftPageRequired(false, writable, false))
            assertFalse(reviewedPostDraftPageRequired(false, writable, true)) // Retained history remains mounted read-only.
            assertTrue(reviewedPostDraftPageRequired(true, writable, true)) // Revocation always redacts it.
        }
    }
    @Test fun originalRoutesKeepTheirExistingFormatIndependentControls() {
        PostDraftJournalFormat.entries.forEach { assertTrue(reviewedDraftMutationControlsAllowed(false, it)) }
    }
    @Test fun queuedPublicationCallbackRechecksCurrentToLegacy() = runTest {
        var format = PostDraftJournalFormat.CURRENT; var actions = 0
        val live = { reviewedDraftMutationControlsAllowed(true, format) }
        assertTrue(live()) // The old enabled affordance.
        val job = launch { runReviewedPostUiAction(live) { actions++ } }
        format = PostDraftJournalFormat.LEGACY
        runCurrent(); job.join(); assertEquals(0, actions)
    }
    @Test fun queuedPublicationCallbackRechecksCurrentToUnknown() = runTest {
        var format = PostDraftJournalFormat.CURRENT; var actions = 0
        val job = launch { runReviewedPostUiAction({ reviewedDraftMutationControlsAllowed(true, format) }) { actions++ } }
        format = PostDraftJournalFormat.NOT_OBSERVED
        runCurrent(); job.join(); assertEquals(0, actions)
    }
    @Test fun currentFormatCallbackInvokesOnlyTheExplicitActionOnce() = runTest {
        var actions = 0
        runReviewedPostUiAction({ reviewedDraftMutationControlsAllowed(true, PostDraftJournalFormat.CURRENT) }) { actions++ }
        assertEquals(1, actions)
    }
    @Test fun disabledCallbackDoesNotEvaluateItsBodyOrInventAResult() = runTest {
        runReviewedPostUiAction({ false }) { fail("Read-only UI invoked a controller action") }
    }
}
