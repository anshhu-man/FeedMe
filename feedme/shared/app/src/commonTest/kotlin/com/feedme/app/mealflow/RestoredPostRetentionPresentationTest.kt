package com.feedme.app.mealflow

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.FailureReason
import com.feedme.mealflow.social.PostDraftScreen
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RestoredPostRetentionPresentationTest {
    @Test fun recoveryAffordanceRequiresConfiguredUnacknowledgedEditorButDoesNotClaimEligibility() {
        assertTrue(restoredRetentionReviewOffered(true, PostDraftScreen.EDITOR, false))
        assertFalse(restoredRetentionReviewOffered(false, PostDraftScreen.EDITOR, false))
        assertFalse(restoredRetentionReviewOffered(true, PostDraftScreen.EDITOR, true))
        for (screen in PostDraftScreen.entries.filter { it != PostDraftScreen.EDITOR })
            assertFalse(restoredRetentionReviewOffered(true, screen, false))
        assertTrue(restoredRetentionFailureText(FailureReason.CONFLICT)!!.contains("not eligible"))
    }

    @Test fun matchingRootAndRevisionCannotMakeARevokedReviewDisplayCurrent() {
        for (current in listOf(false, true)) for (selection in listOf(false, true)) for (ack in listOf(false, true))
            assertEquals(current && selection && !ack, restoredRetentionReviewCurrent(current, selection, ack))
        assertFalse(restoredRetentionReviewCurrent(false, true, false)) // Same-root Back/reopen ABA.
    }

    @Test fun latePreparationAndFailureUseExactNavigationTicketAndSelection() {
        val original = Any()
        val pin = ReviewedDraftViewPin(PostDraftScreen.EDITOR, "root", 9007199254740993L)
        assertTrue(reviewedEntryDeliveryCurrent(original, original, pin, PostDraftScreen.EDITOR, "root", 9007199254740993L, true))
        assertFalse(reviewedEntryDeliveryCurrent(original, Any(), pin, PostDraftScreen.EDITOR, "root", 9007199254740993L, true))
        assertFalse(reviewedEntryDeliveryCurrent(original, original, pin, PostDraftScreen.EDITOR, "root", 9007199254740994L, true))
        assertFalse(reviewedEntryDeliveryCurrent(original, original, pin, PostDraftScreen.LOCAL_LIST, null, null, true))
        assertFalse(reviewedEntryDeliveryCurrent(original, original, pin, PostDraftScreen.EDITOR, "root", 9007199254740993L, false))
    }

    @Test fun delayedInspectionSurvivesTheDraftPageThatLaunchedItBeingRemoved() = runTest {
        // The page starts the same helper used by both route-changing wrapper callbacks.
        // Cancelling only that page must not cancel the owner's suspended inspection.
        val wrapperJob = Job()
        val wrapper = CoroutineScope(coroutineContext + wrapperJob)
        val pageJob = Job()
        val page = CoroutineScope(coroutineContext + pageJob)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var delivered = false
        lateinit var inspection: Job
        try {
            page.launch {
                inspection = launchReviewedEntryInspection(wrapper) {
                    entered.complete(Unit); release.await(); delivered = true
                }
            }
            runCurrent(); assertTrue(entered.isCompleted); assertFalse(delivered)
            pageJob.cancel(); runCurrent(); assertTrue(inspection.isActive)
            release.complete(Unit); inspection.join(); assertTrue(delivered)
        } finally { pageJob.cancel(); wrapperJob.cancel() }
    }

    @Test fun removingTheWrapperCancelsItsSuspendedInspection() = runTest {
        val wrapperJob = Job()
        val wrapper = CoroutineScope(coroutineContext + wrapperJob)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var delivered = false
        val inspection = launchReviewedEntryInspection(wrapper) {
            entered.complete(Unit); release.await(); delivered = true
        }
        runCurrent(); assertTrue(entered.isCompleted)
        wrapperJob.cancel(); inspection.join(); release.complete(Unit); runCurrent()
        assertTrue(inspection.isCancelled); assertFalse(delivered)
    }

    @Test fun backDuringDelayedInspectionRejectsLateViewDespiteOwnerScopeSurviving() = runTest {
        val ticket = Any()
        var navigation = ticket
        val pin = ReviewedDraftViewPin(PostDraftScreen.EDITOR, "root", 7)
        val gate = CompletableDeferred<Unit>()
        var shown = false
        val pending = launchReviewedEntryInspection(this) {
            gate.await()
            if (reviewedEntryDeliveryCurrent(ticket, navigation, pin, PostDraftScreen.EDITOR, "root", 7, true)) shown = true
        }
        runCurrent(); navigation = Any(); gate.complete(Unit); pending.join()
        assertFalse(shown)
    }

    @Test fun retentionUnknownMeansLocalUncertaintyNotAnAttemptedServerRequest() {
        val copy = assertNotNull(restoredRetentionFailureText(FailureReason.OUTCOME_UNKNOWN))
        assertTrue(copy.contains("Local storage may have changed"))
        assertTrue(copy.contains("not acknowledged"))
        assertTrue(copy.contains("do not infer that storage rolled back"))
        assertTrue(copy.contains("No server request was made by retention"))
        assertFalse(copy.contains("may have reached the server"))
    }

    @Test fun allRetentionFailuresHaveBoundedNonAcceptingCopyAndNullIsQuiet() {
        assertNull(restoredRetentionFailureText(null))
        for (reason in FailureReason.entries) {
            val copy = assertNotNull(restoredRetentionFailureText(reason))
            assertTrue(copy.length in 1..350)
            assertFalse(copy.contains("PRIVATE_MARKER"))
        }
        assertTrue(restoredRetentionFailureText(FailureReason.STORAGE_FAILURE)!!.contains("not a private Save or publication"))
    }

    @Test fun completeRetainedChoiceEvidencePreservesOrderExactNumbersAndUnknownFields() {
        val text = """{ "caption":"exact\ncaption", "altText":"", "mediaIds":["SECOND","FIRST"], "audience":{"kind":"circles","circleIds":["B","A"]}, "keepOnPlate":false, "allowRecipeSaves":false, "sourcePostId":"UPPERCASE", "attachment":{"personalRecipe":{"quantity":9007199254740993.000,"safety":"Exact mandatory instruction"}}, "futureField":{"retained":true} }"""
        val document = WireDocument.parse(text)
        val before = document.encodeUtf8()
        val rows = exactReviewRows(document).map { it.label to it.value }
        assertTrue(rows.indexOf("mediaIds · 1" to "SECOND") < rows.indexOf("mediaIds · 2" to "FIRST"))
        assertTrue(rows.contains("altText" to "Empty text (included)"))
        assertTrue(rows.contains("keepOnPlate" to "false"))
        assertTrue(rows.contains("attachment · personalRecipe · quantity" to "9007199254740993.000"))
        assertTrue(rows.contains("attachment · personalRecipe · safety" to "Exact mandatory instruction"))
        assertTrue(rows.contains("futureField · retained" to "true"))
        assertContentEquals(before, document.encodeUtf8())
    }

    @Test fun missingHistoricalDisclosureAndAltDoNotBecomeEmptyIncludedText() {
        assertEquals("Not included", exactReviewText(null))
        assertEquals("Empty text (included)", exactReviewText(JsonPrimitive("")))
        assertNotEquals(exactReviewText(null), exactReviewText(JsonPrimitive("")))
        assertEquals("historical\nexact", exactReviewText(JsonPrimitive("historical\nexact")))
    }
}
