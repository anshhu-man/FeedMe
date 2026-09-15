package com.feedme.app.mealflow

import com.feedme.mealflow.social.PostDraftIssue
import kotlin.test.*

class PostDraftPresentationTest {
    @Test fun visibleOriginalCardReplacesOnlyItsDuplicateGlobalWarning() {
        assertNull(draftHeadsUpText(PostDraftIssue.ORIGINAL_PENDING, true))
        assertEquals(draftIssueText(PostDraftIssue.ORIGINAL_PENDING), draftHeadsUpText(PostDraftIssue.ORIGINAL_PENDING, false))
    }
    @Test fun pendingCardNeverHidesAnUnrelatedErrorOrStorageOrSessionWarning() {
        PostDraftIssue.entries.filterNot { it == PostDraftIssue.ORIGINAL_PENDING }.forEach { issue ->
            for (pending in listOf(false, true)) assertEquals(draftIssueText(issue), draftHeadsUpText(issue, pending))
        }
    }
    @Test fun localAcknowledgementNeverClaimsServerSave() {
        val text = draftAcknowledgementText(true, false, false, false)
        assertTrue(text.contains("on this device")); assertTrue(text.contains("separate"))
    }
    @Test fun observedMatchingTextIsNotANewReceipt() {
        assertTrue(draftAcknowledgementText(true, false, true, false).contains("not a new save confirmation"))
    }
    @Test fun acknowledgedOldTextDoesNotClaimNewEditsSaved() {
        assertTrue(draftAcknowledgementText(true, true, false, false).contains("newer edits are still local"))
    }
    @Test fun unknownLocalAckTakesPrecedenceOverServerProjection() {
        assertTrue(draftAcknowledgementText(false, true, true, false).contains("storage has not confirmed"))
    }
    @Test fun finalizationRequiresOriginalActionNotRefresh() {
        val text = draftAcknowledgementText(true, true, true, true)
        assertTrue(text.contains("refreshing is not confirmation")); assertTrue(text.contains("when available"))
        assertFalse(text.contains("original save"))
    }
    @Test fun acknowledgedPrivateSaveNeverClaimsPosting() {
        assertTrue(draftAcknowledgementText(true, true, true, false).contains("Nothing has been posted"))
    }
    @Test fun deniedServerAssociationDoesNotDescribeALocalOnlyDraftOrOfferAnUnavailableSave() {
        val text = draftAcknowledgementText(true, false, false, false, true, false)
        assertTrue(text.contains("Server content is unavailable")); assertTrue(text.contains("local text is retained"))
        assertTrue(text.contains("review discard")); assertFalse(text.contains("Save to server")); assertFalse(text.contains("Nothing has been posted"))
    }
    @Test fun uncertainAcknowledgementsTakePrecedenceOverDeniedServerAssociation() {
        assertTrue(draftAcknowledgementText(false, false, false, false, true, false).contains("storage has not confirmed"))
        assertTrue(draftAcknowledgementText(true, false, false, true, true, false).contains("original action needs local confirmation"))
    }
    @Test fun invalidTextFeedbackExplainsBothBoundsAndDoesNotClaimRejectedTextWasRetained() {
        val error = assertNotNull(draftTextValidationError(PostDraftIssue.INVALID_INPUT))
        assertTrue(error.contains("Edit not applied")); assertTrue(error.contains("captions and image descriptions"))
        assertTrue(error.contains("500 Unicode characters")); assertTrue(error.contains("previous text is unchanged"))
        PostDraftIssue.entries.filterNot { it == PostDraftIssue.INVALID_INPUT }.forEach { assertNull(draftTextValidationError(it)) }
    }
    @Test fun everyIssueHasFiniteSafeCopyOrIsExplicitlyQuiet() {
        PostDraftIssue.entries.forEach { issue ->
            val text = draftIssueText(issue)
            if (issue in setOf(PostDraftIssue.NONE, PostDraftIssue.CONFIRM_DISCARD)) assertNull(text)
            else assertTrue(assertNotNull(text).length in 1..300)
        }
    }
    @Test fun unverifiedDataDoesNotMarkValidTextAsAnInvalidEdit() {
        assertNull(draftTextValidationError(PostDraftIssue.DATA_UNVERIFIED))
        val text = assertNotNull(draftIssueText(PostDraftIssue.DATA_UNVERIFIED))
        assertTrue(text.contains("data could not be verified")); assertTrue(text.contains("No new save has been confirmed"))
        assertFalse(text.contains("Check your text")); assertFalse(text.contains("Edit not applied"))
        assertFalse(text.contains("500"))
    }
    @Test fun onlyOwnedEditableExpiredOrRedactedDraftsOfferDiscardReview() {
        assertTrue(canReviewServerDraftDiscard(true, "draft"))
        assertTrue(canReviewServerDraftDiscard(true, "expired"))
        assertTrue(canReviewServerDraftDiscard(true, null))
        assertFalse(canReviewServerDraftDiscard(true, "published"))
        assertFalse(canReviewServerDraftDiscard(true, "discarded"))
        assertFalse(canReviewServerDraftDiscard(false, null))
    }

    @Test fun uncertainSaveAndDiscardUseDistinctLabelsWithoutClaimingCompletion() {
        for (operation in listOf("createPostDraft", "updatePostDraft", "deletePostDraft")) {
            val message = draftPendingMessage(operation, "AWAITING_CONFIRMATION", "OUTCOME_UNKNOWN", false, false)
            assertEquals(if (operation == "deletePostDraft") "Server discard is not confirmed" else "Private save is not confirmed", message.title)
            assertTrue(message.detail.contains("may have reached the server")); assertTrue(message.detail.contains("only that original action"))
            assertFalse(message.detail.contains(operation)); assertFalse(message.detail.contains("Saved privately"))
        }
    }
    @Test fun historicalCompletionAsksForReviewWithoutOfferingUnavailableRetry() {
        val message = draftPendingMessage("deletePostDraft", "HISTORICAL_COMPLETION", "RECONCILIATION_REQUIRED", true, false)
        assertEquals("Server discard needs review", message.title)
        assertTrue(message.detail.contains("this session cannot confirm")); assertFalse(message.detail.contains("Retry"))
        assertFalse(message.detail.contains("save"))
    }
    @Test fun localFinalizationCannotBePresentedAsAnAcknowledgedServerResult() {
        val message = draftPendingMessage("deletePostDraft", "DELIVERY_PENDING", "RECONCILIATION_REQUIRED", true, false)
        assertEquals("Server discard needs local confirmation", message.title)
        assertTrue(message.detail.contains("has not confirmed")); assertTrue(message.detail.contains("when available"))
        assertFalse(message.detail.contains("save")); assertFalse(message.detail.contains("discarded"))
        for (phase in listOf("RECEIPT_READY", "APPLIED", "DISCARDED", "DELIVERY_PENDING")) {
            val withoutProof = draftPendingMessage("deletePostDraft", phase, "NONE", false, false)
            assertTrue(withoutProof.title.contains("needs local confirmation"))
            assertTrue(withoutProof.detail.contains("does not confirm a server change"))
        }
    }
    @Test fun dispatchAndUnknownOutcomeNeverClaimTheActionWasUnsentEvenWithAnInconsistentFlag() {
        for (phase in listOf("DISPATCH_UNOBSERVED", "IN_FLIGHT", "AWAITING_CONFIRMATION", "READY")) {
            val message = draftPendingMessage("createPostDraft", phase, "OUTCOME_UNKNOWN", false, true)
            assertEquals("Private save is not confirmed", message.title)
            assertFalse(message.detail.contains("unsent")); assertFalse(message.title.contains("not been sent"))
        }
    }
    @Test fun onlyReadyWithTheActualCancellationAffordanceDescribesAnUnsentAction() {
        val unsent = draftPendingMessage("updatePostDraft", "READY", "NONE", false, true)
        assertEquals("Private save has not been sent", unsent.title); assertTrue(unsent.detail.contains("cancel this unsent action"))
        val notProven = draftPendingMessage("updatePostDraft", "READY", "NONE", false, false)
        assertEquals("Private save is waiting", notProven.title); assertFalse(notProven.detail.contains("unsent"))
    }
    @Test fun retryWaitingAndResolutionDoNotPromiseAutomaticSubmissionOrReplacement() {
        val waiting = draftPendingMessage("createPostDraft", "RETRY_WAIT", "OFFLINE", false, false)
        assertTrue(waiting.detail.contains("reconnecting does not send it automatically"))
        val blocked = draftPendingMessage("updatePostDraft", "NEEDS_RESOLUTION", "CONFLICT", false, false)
        assertTrue(blocked.detail.contains("cannot continue yet")); assertTrue(blocked.detail.contains("does not confirm it or create a replacement"))
    }
    @Test fun unknownOperationPhaseAndIssueAreNeverEchoedOrTreatedAsSuccessful() {
        val message = draftPendingMessage("PRIVATE_OPERATION", "PRIVATE_PHASE", "PRIVATE_ISSUE", false, true)
        assertEquals("Draft action needs review", message.title)
        assertFalse((message.title + message.detail).contains("PRIVATE_")); assertFalse(message.detail.contains("unsent"))
        assertTrue(message.detail.contains("status is unavailable")); assertTrue(message.detail.contains("does not confirm or retry"))
        for (unknown in listOf(draftPendingMessage("PRIVATE_OPERATION", "READY", "NONE", false, true),
            draftPendingMessage("updatePostDraft", "READY", "PRIVATE_ISSUE", false, true))) {
            assertFalse(unknown.title.contains("not been sent")); assertFalse(unknown.detail.contains("unsent"))
            assertFalse((unknown.title + unknown.detail).contains("PRIVATE_"))
        }
    }
    @Test fun everyCurrentPendingPhaseHasBoundedHumanReadableCopyWithoutRawQueueLabels() {
        for (phase in listOf("READY", "IN_FLIGHT", "RETRY_WAIT", "AWAITING_CONFIRMATION", "NEEDS_RESOLUTION",
            "RECEIPT_READY", "APPLIED", "DISCARDED", "DISPATCH_UNOBSERVED", "DELIVERY_PENDING", "HISTORICAL_COMPLETION", "UNOBSERVED_ORIGINAL")) {
            val message = draftPendingMessage("deletePostDraft", phase, "NONE", false, false)
            assertTrue(message.title.length in 1..70); assertTrue(message.detail.length in 1..250)
            assertFalse((message.title + message.detail).contains(phase)); assertFalse(message.detail.contains("deletePostDraft"))
        }
    }
}
