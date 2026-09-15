package com.feedme.mealflow.social

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.mealflow.*
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.CLIENT
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.KEY
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.ORIGIN
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.SERVER
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.TIME
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.document
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.draft
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.number
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.policy
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.response
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import kotlin.test.*

/** Actual public configured factory + sole owner/controllers/queue over explicitly synthetic
 * mapping, prerequisite, transport and CAS ports. No provider/native/deployment acceptance.
 * Only migration tests seed legacy historical rows; natural journeys never seed schema2. */
@OptIn(ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)
class ReviewedPostEntryTest {
    @Test fun configuredConstructionAndBalancedCloseHaveNoIoAndDoNotCloseBorrowedSession() = runTest { fixture { f ->
        assertSame(f.bundle.postDrafts, f.entry.drafts)
        assertEquals(0, f.store.reads); assertEquals(0, f.store.writes); assertEquals(0, f.ids); assertTrue(f.calls.isEmpty())
        assertTrue(f.contexts.isEmpty()); value(f.bundle.close()); value(f.bundle.close())
        assertTrue(f.boundary.isCurrent(f.lease)); assertEquals(0, f.store.erases)
    } }

    @Test fun legacyFactoryDefaultsRemainUnconfiguredAndDoNotEnableMigration() = runTest { fixture { f ->
        value(f.bundle.close())
        val old = f.legacyBundle()
        try {
            assertNull(old.reviewedPosts); assertNotNull(old.postDrafts)
            val created = value(old.postDrafts!!.newLocalDraft("Legacy"))
            assertEquals(1, f.store.records.getValue(KEY).schemaVersion)
            assertTrue(value(old.postDrafts!!.saveExplicitly()).serverAcknowledged)
            assertEquals(listOf("createPostDraft"), f.calls.map { it.operationId })
            assertTrue(created.selected!!.localAcknowledged)
        } finally { old.close() }
    } }

    @Test fun newCurrentTextRequiresExplicitChoicesAndNeverInventsAudienceOrDisclosure() = runTest { fixture { f ->
        val local = f.newDraft()
        val before = f.store.records.getValue(KEY); val writes = f.store.writes
        val selection = value(f.entry.inspectSelected(local.clientDraftId, local.localRevision))
        assertEquals(ReviewedDraftFormat.CURRENT_TEXT, selection.format); assertNull(selection.choices)
        assertNull(selection.exactChoices); assertNull(selection.historicalDisclosure); assertTrue(selection.needsExplicitChoices)
        failure(f.entry.prepareSelectedPublication(local.clientDraftId, local.localRevision, ReviewedPostBranch.DIRECT_LOCAL), FailureReason.NOT_CONFIGURED)
        assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes); assertTrue(f.calls.isEmpty())
    } }

    @Test fun completeChoiceEditHasRealLocalAckAndPreservesExactOptionalsOrderAndSource() = runTest { fixture { f ->
        val local = f.newDraft(); val explicit = f.choices(full = true)
        val ids = f.ids
        val edited = value(f.entry.editChoices(local.clientDraftId, local.localRevision, explicit)).selected!!
        assertTrue(edited.localAcknowledged); assertEquals(local.localRevision + 1, edited.localRevision)
        assertEquals(ids, f.ids); assertTrue(f.calls.isEmpty())
        val selected = value(f.entry.inspectSelected(edited.clientDraftId, edited.localRevision))
        assertEquals(ReviewedDraftFormat.CURRENT_COMPOSER, selected.format)
        val retained = selected.choices!!
        assertEquals(listOf(number(31), number(30)), retained.orderedMediaIds)
        assertEquals(listOf(number(41), number(40)), assertIs<PublicationAudience.Circles>(retained.audience).orderedCircleIds)
        assertEquals("", assertIs<OptionalValue.Present<String>>(retained.altText).value)
        assertEquals(number(51), assertIs<OptionalValue.Present<String>>(retained.sourcePostId).value)
        assertEquals(listOf("One", "Two"), assertIs<OptionalValue.Present<PublicationAttachment>>(retained.attachment).value.confirmedChanges)
        assertEquals(explicit.disclosure.text, selected.historicalDisclosure!!.text)
        assertEquals(2, f.store.records.getValue(KEY).schemaVersion)
    } }

    @Test fun staleRevisionOrDifferentSelectedRootCannotReplaceFullChoices() = runTest { fixture { f ->
        val first = f.newDraft(); val second = f.newDraft("Second")
        val before = f.store.records.getValue(KEY); val writes = f.store.writes
        failure(f.entry.editChoices(first.clientDraftId, first.localRevision, f.choices()), FailureReason.CONFLICT)
        failure(f.entry.editChoices(second.clientDraftId, second.localRevision + 1, f.choices()), FailureReason.CONFLICT)
        assertEquals(writes, f.store.writes); assertTrue(postSame(before, f.store.records[KEY]))
    } }

    @Test fun oversizedCompleteChoicesRefuseBeforeRetainingEditOrAllocatingAnyId() = runTest { fixture { f ->
        val local = f.newDraft()
        val before = f.store.records.getValue(KEY); val ids = f.ids
        val tooLarge = ReviewedPostChoices("Valid bounded caption", OptionalValue.Absent, emptyList(), PublicationAudience.OnlyYou,
            false, OptionalValue.Absent, false, PublicationDisclosure("v", "A".repeat(10_000)), OptionalValue.Absent)
        failure(f.entry.editChoices(local.clientDraftId, local.localRevision, tooLarge), FailureReason.UNAVAILABLE)
        assertTrue(postSame(before, f.store.records[KEY])); assertEquals(ids, f.ids); assertTrue(f.calls.isEmpty())
        assertEquals(local.caption, f.entry.drafts.states.value.selected!!.caption)
    } }

    @Test fun naturalNewTextSaveThenCompleteEditReviewedSaveAndSavedPublicationUsesOneJourney() = runTest { fixture { f ->
        val text = f.newDraft("Before")
        assertTrue(value(f.entry.drafts.saveExplicitly()).serverAcknowledged)
        val edited = value(f.entry.editChoices(text.clientDraftId, text.localRevision, f.choices())).selected!!
        assertTrue(edited.localAcknowledged)
        val privateReview = value(f.entry.prepareSelectedReviewedSave(edited.clientDraftId, edited.localRevision))
        assertEquals("Before", postString(privateReview.snapshot.baseline, "caption"))
        assertTrue(value(f.entry.drafts.confirmReviewedSave(privateReview.token)).serverAcknowledged)
        val before = f.store.records.getValue(KEY); val writes = f.store.writes
        val publication = value(f.entry.prepareSelectedPublication(edited.clientDraftId, edited.localRevision, ReviewedPostBranch.SAVED_DRAFT))
        assertIs<PublicationTarget.SavedDraft>(publication.snapshot.target)
        assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes)
        val published = value(f.entry.publications.confirmPublish(publication.token))
        assertTrue(published.acknowledged); assertEquals("Explicit post", postString(published.exactCanonicalPost!!, "caption"))
        assertEquals(listOf("createPostDraft", "updatePostDraft", "publishPost"), f.calls.map { it.operationId })
        assertEquals(4, f.ids) // one root, one create, one reviewed PATCH, one publish.
        assertNull(f.entry.drafts.states.value.selected)
        assertTrue(f.entry.drafts.states.value.localDrafts.isEmpty())
    } }

    @Test fun directPublicationHasNoImplicitPrivateSaveAndTerminalCallbackDoesNotCancelAck() = runTest { fixture { f ->
        val edited = f.newComposer()
        val review = value(f.entry.prepareSelectedPublication(edited.clientDraftId, edited.localRevision, ReviewedPostBranch.DIRECT_LOCAL))
        assertIs<PublicationTarget.DirectLocal>(review.snapshot.target)
        val result = value(f.entry.publications.confirmPublish(review.token))
        assertTrue(result.acknowledged); assertEquals(listOf("publishPost"), f.calls.map { it.operationId })
        assertTrue(f.entry.drafts.states.value.localDrafts.isEmpty())
    } }

    @Test fun explicitBranchMismatchCannotSilentlySaveOrChangeTarget() = runTest { fixture { f ->
        val local = f.newComposer()
        failure(f.entry.prepareSelectedPublication(local.clientDraftId, local.localRevision, ReviewedPostBranch.SAVED_DRAFT), FailureReason.CONFLICT)
        assertTrue(f.calls.isEmpty())
        // New text server creation is required before rich Save; do not broaden Save itself.
        failure(f.entry.drafts.saveExplicitly(), FailureReason.NOT_CONFIGURED)
        assertTrue(f.calls.isEmpty())
    } }

    @Test fun purposeTaggedDisclosureAndChecksKeepPrivateSaveAndPublicationDistinct() = runTest { fixture { f ->
        val local = f.newDraft()
        value(f.entry.loadDisclosure(local.clientDraftId, ReviewedPostPurpose.PRIVATE_SAVE))
        value(f.entry.loadDisclosure(local.clientDraftId, ReviewedPostPurpose.PUBLICATION))
        assertEquals(listOf(ReviewedPostPurpose.PRIVATE_SAVE, ReviewedPostPurpose.PUBLICATION), f.contexts.map { it.purpose })
        assertTrue(f.contexts.all { it.lease === f.lease && it.boundary === f.boundary && it.origin == f.access.origin &&
            it.principal.canonicalUserId == ACCOUNT })
        assertTrue(f.calls.isEmpty())
    } }

    @Test fun configuredDenialCannotBecomeReviewOrAllocateAnOriginal() = runTest { fixture { f ->
        val local = f.newComposer(); val ids = f.ids; val before = f.store.records.getValue(KEY)
        f.denyPublication = FailureReason.FORBIDDEN
        failure(f.entry.prepareSelectedPublication(local.clientDraftId, local.localRevision, ReviewedPostBranch.DIRECT_LOCAL), FailureReason.FORBIDDEN)
        assertNull(f.entry.publications.states.value.review); assertEquals(ids, f.ids); assertTrue(f.calls.isEmpty())
        assertTrue(postSame(before, f.store.records[KEY]))
    } }

    @Test fun backDuringSuspendedSelectedPublicationReviewCannotReturnOrDisplayLateReview() = runTest { fixture { f ->
        val local = f.newComposer()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.beforePublication = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
        val pending = async { f.entry.prepareSelectedPublication(local.clientDraftId, local.localRevision, ReviewedPostBranch.DIRECT_LOCAL) }
        try {
            entered.await(); value(f.entry.drafts.back()); release.complete(Unit)
            assertIs<PortResult.Failure>(pending.await()); assertNull(f.entry.publications.states.value.review)
            assertTrue(f.calls.isEmpty())
        } finally { release.complete(Unit); withContext(NonCancellable) { pending.cancelAndJoin() } }
    } }

    @Test fun backOrEditAfterPublicationReviewRevokesTokenWithoutLosingLocalWork() = runTest {
        for (edit in listOf(false, true)) fixture { f ->
            val local = f.newComposer()
            val review = value(f.entry.prepareSelectedPublication(local.clientDraftId, local.localRevision, ReviewedPostBranch.DIRECT_LOCAL))
            if (edit) value(f.entry.drafts.editCaption(local.clientDraftId, "Newer")) else value(f.entry.drafts.back())
            assertIs<PortResult.Failure>(f.entry.publications.confirmPublish(review.token)); assertTrue(f.calls.isEmpty())
            assertEquals(if (edit) "Newer" else "Explicit post", f.current().locals.single().content.caption)
        }
    }

    @Test fun legacyRestoreAndUpgradePreparationDoNotWriteOrCreateConsentForPublication() = runTest { fixture { f ->
        f.seedLegacy()
        value(f.entry.drafts.restoreLocal()); value(f.entry.drafts.openLocal(CLIENT))
        val before = f.store.records.getValue(KEY); val writes = f.store.writes
        val selected = value(f.entry.inspectSelected(CLIENT, 3)); assertTrue(selected.needsUpgrade)
        val upgrade = value(f.entry.prepareUpgrade(CLIENT, 3))
        assertEquals(CLIENT, upgrade.clientDraftId); assertEquals(1, upgrade.retainedDraftCount)
        assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes); assertEquals(0, f.ids)
        assertNull(f.entry.publications.states.value.review); assertNull(f.entry.drafts.states.value.reviewedSave)
    } }

    @Test fun explicitUpgradePreservesAllHistoricalLocalBytesAndRequiresAnotherReview() = runTest { fixture { f ->
        f.seedLegacy()
        value(f.entry.drafts.restoreLocal()); value(f.entry.drafts.openLocal(CLIENT))
        val before = f.legacy()
        val consent = value(f.entry.prepareUpgrade(CLIENT, 3))
        assertEquals(ReviewedDraftUpgradeResult.CURRENT_REVIEW_REQUIRED, value(f.entry.confirmUpgrade(consent)))
        val actual = f.current()
        assertEquals(before.issued, actual.issued); assertEquals(before.clock, actual.clock)
        val local = actual.locals.single()
        assertEquals(3L, local.localRevision); assertEquals("Legacy kept", local.content.caption)
        assertNull(local.content.altText); assertIs<DraftLocalContentV1.TextV1>(local.content)
        assertContentEquals(before.locals.single().server!!.encodeUtf8(),
            assertIs<DraftServerAssociationV1.Observed>(local.serverAssociation).exactPostDraft.encodeUtf8())
        assertEquals(0, f.ids); assertTrue(f.calls.isEmpty()); assertFalse(f.entry.drafts.states.value.serverAcknowledged)
        assertNull(f.entry.drafts.states.value.reviewedSave); assertNull(f.entry.publications.states.value.review)
        failure(f.entry.confirmUpgrade(consent), FailureReason.CONFLICT)
    } }

    @Test fun freshGenerationSelectionAndExactRowBoundUpgradeRejectsForgedBackAndChangedRecords() = runTest {
        for (mode in 0..2) fixture { f ->
            f.seedLegacy(); value(f.entry.drafts.restoreLocal()); value(f.entry.drafts.openLocal(CLIENT))
            val real = value(f.entry.prepareUpgrade(CLIENT, 3))
            val candidate = if (mode == 0) PreparedReviewedDraftUpgrade(CLIENT, 3, 1, real.expiresAtMillis) else real
            if (mode == 1) value(f.entry.drafts.back())
            if (mode == 2) f.store.records[KEY] = f.store.records.getValue(KEY).copy(revision = 2)
            assertIs<PortResult.Failure>(f.entry.confirmUpgrade(candidate))
            assertEquals(1, f.store.records.getValue(KEY).schemaVersion); assertEquals(0, f.store.writes)
        }
    }

    @Test fun unresolvedLegacyMarkersOrOriginalsCannotBeMigratedFromHistoricalData() = runTest {
        for (pending in listOf(false, true)) fixture { f ->
            f.seedLegacy()
            val old = f.legacy()
            val pendingRecord = if (pending) old.copy(localPending = PostLocalPending(CLIENT, 3)) else old.copy(command =
                PostOriginal(number(70), "updatePostDraft", CLIENT, 3, WireDocument.parse("{\"caption\":\"Legacy kept\"}"),
                    old.locals.single().server, "\"1\"", 10), issued = old.issued + number(70))
            f.store.records[KEY] = PrivateRecord(1, 1, PostDraftCodec(ORIGIN, f.draftPolicy).encode(pendingRecord))
            value(f.entry.drafts.restoreLocal()); value(f.entry.drafts.openLocal(CLIENT))
            failure(f.entry.prepareUpgrade(CLIENT, 3), FailureReason.CONFLICT)
            assertEquals(0, f.store.writes); assertEquals(1, f.store.records.getValue(KEY).schemaVersion)
        }
    }

    @Test fun genuinelyDeliveredLegacyEditCanUpgradeWithoutDummyEditOrHistoricalProofForgery() = runTest { fixture { f ->
        f.seedLegacy(); value(f.entry.drafts.restoreLocal()); value(f.entry.drafts.openLocal(CLIENT))
        val edited = value(f.entry.drafts.editCaption(CLIENT, "Acknowledged legacy edit")).selected!!
        assertTrue(edited.localAcknowledged); assertNotNull(f.legacy().localPending)
        value(f.entry.confirmUpgrade(value(f.entry.prepareUpgrade(CLIENT, edited.localRevision))))
        assertNull(f.current().localPending); assertEquals("Acknowledged legacy edit", f.current().locals.single().content.caption)
        assertTrue(f.calls.isEmpty())
    } }

    @Test fun unknownUpgradeCommitConsumesConsentAndExplicitRestartReadsCurrentWithoutOldAck() = runTest { fixture { f ->
        f.seedLegacy(); value(f.entry.drafts.restoreLocal()); value(f.entry.drafts.openLocal(CLIENT))
        val consent = value(f.entry.prepareUpgrade(CLIENT, 3))
        f.store.failAfter = { mutations -> mutations.any { it is StoreMutation.Put && it.key == KEY && it.schemaVersion == 2 } }
        failure(f.entry.confirmUpgrade(consent), FailureReason.OUTCOME_UNKNOWN)
        assertEquals(2, f.store.records.getValue(KEY).schemaVersion)
        failure(f.entry.confirmUpgrade(consent), FailureReason.CONFLICT)
        f.replace()
        value(f.entry.drafts.restoreLocal()); value(f.entry.drafts.openLocal(CLIENT))
        val observed = value(f.entry.inspectSelected(CLIENT, 3))
        assertEquals(ReviewedDraftFormat.CURRENT_TEXT, observed.format); assertFalse(f.entry.drafts.states.value.serverAcknowledged)
        assertNull(f.entry.publications.states.value.review); assertNull(f.entry.drafts.states.value.reviewedSave)
        assertEquals(0, f.ids); assertTrue(f.calls.isEmpty())
    } }

    @Test fun oldFactoryCannotWriteConfiguredCurrentRecordAfterRestart() = runTest { fixture { f ->
        f.newComposer(); value(f.bundle.close())
        val before = f.store.records.getValue(KEY); val writes = f.store.writes
        val old = f.legacyBundle()
        try {
            failure(old.postDrafts!!.restoreLocal(), FailureReason.NOT_CONFIGURED)
            assertEquals(writes, f.store.writes); assertTrue(postSame(before, f.store.records[KEY]))
        } finally { old.close() }
    } }

    @Test fun optionalRemovalWithoutCanonicalPatchOperationIsRejectedNotSilentlyPreserved() = runTest { fixture { f ->
        val local = f.newDraft()
        value(f.entry.drafts.saveExplicitly())
        // Full choices explicitly add source; reviewed Save persists the actual source.
        val source = ReviewedPostChoices("With source", OptionalValue.Absent, emptyList(), PublicationAudience.OnlyYou,
            false, OptionalValue.Absent, false, f.disclosure, OptionalValue.Present(number(51)))
        val first = value(f.entry.editChoices(local.clientDraftId, local.localRevision, source)).selected!!
        value(f.entry.drafts.confirmReviewedSave(value(f.entry.prepareSelectedReviewedSave(first.clientDraftId, first.localRevision)).token))
        val second = value(f.entry.editChoices(first.clientDraftId, first.localRevision, f.choices())).selected!!
        val before = f.store.records.getValue(KEY); val calls = f.calls.size
        failure(f.entry.prepareSelectedReviewedSave(second.clientDraftId, second.localRevision), FailureReason.INVALID_DATA)
        assertEquals(calls, f.calls.size); assertTrue(postSame(before, f.store.records[KEY]))
    } }

    @Test fun cancelledUpgradeAfterActualWriteRestoresAsHistoricalCurrentAndCannotReuseConsent() = runTest { fixture { f ->
        f.seedLegacy(); value(f.entry.drafts.restoreLocal()); value(f.entry.drafts.openLocal(CLIENT))
        val token = value(f.entry.prepareUpgrade(CLIENT, 3))
        val written = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.store.afterCommit = { mutations -> if (mutations.any { it is StoreMutation.Put && it.schemaVersion == 2 }) {
            written.complete(Unit); withContext(NonCancellable) { release.await() }
        } }
        val pending = async { f.entry.confirmUpgrade(token) }
        try {
            written.await(); pending.cancel(); release.complete(Unit); pending.join()
            assertTrue(pending.isCancelled); assertEquals(2, f.store.records.getValue(KEY).schemaVersion)
            f.store.afterCommit = {}; value(f.entry.drafts.restoreLocal()); value(f.entry.drafts.openLocal(CLIENT))
            assertEquals(ReviewedDraftFormat.CURRENT_TEXT, value(f.entry.inspectSelected(CLIENT, 3)).format)
            assertFalse(f.entry.drafts.states.value.serverAcknowledged); failure(f.entry.confirmUpgrade(token), FailureReason.CONFLICT)
            assertTrue(f.calls.isEmpty()); assertEquals(0, f.ids)
        } finally { release.complete(Unit); withContext(NonCancellable) { pending.cancelAndJoin() } }
    } }

    @Test fun failedUpgradeBeforeWriteNeedsFreshConsentAndPreservesEveryUnrelatedRootAndTombstone() = runTest { fixture { f ->
        f.seedLegacy(); val before = f.legacy()
        val extra = PostLocal(number(61), 7, "Unrelated", "", null, null)
        val closed = PostTerminal(number(62), number(63), number(64))
        val retained = before.copy(locals = before.locals + extra, issued = before.issued + listOf(extra.id, closed.clientId, closed.commandId!!),
            tombstones = listOf(closed))
        f.store.records[KEY] = PrivateRecord(1, 1, PostDraftCodec(ORIGIN, f.draftPolicy).encode(retained))
        value(f.entry.drafts.restoreLocal()); value(f.entry.drafts.openLocal(CLIENT))
        val token = value(f.entry.prepareUpgrade(CLIENT, 3)); f.store.failBefore = true
        failure(f.entry.confirmUpgrade(token), FailureReason.STORAGE_FAILURE)
        assertEquals(1, f.store.records.getValue(KEY).schemaVersion); failure(f.entry.confirmUpgrade(token), FailureReason.CONFLICT)
        value(f.entry.confirmUpgrade(value(f.entry.prepareUpgrade(CLIENT, 3))))
        val current = f.current()
        assertEquals(retained.issued, current.issued)
        assertEquals(listOf(CLIENT, extra.id), current.locals.map { it.clientDraftId })
        assertEquals("Unrelated", current.locals.last().content.caption); assertEquals("", current.locals.last().content.altText)
        assertEquals(closed, assertIs<PostDraftTerminalV2.LegacyDiscard>(current.terminals.single()).exactLegacy)
        assertEquals(0, f.ids); assertTrue(f.calls.isEmpty())
    } }

    @Test fun absentAttachmentProducesExplicitRemovalButAbsentAltAndSourceAreNeverLossy() = runTest { fixture { f ->
        val local = f.newComposer()
        val selection = value(f.entry.inspectSelected(local.clientDraftId, local.localRevision))
        val attachment = buildJsonObject { put("recipeVersionId", number(50)); put("confirmedChanges", JsonArray(emptyList()))
            put("reviewStatus", "reviewed"); put("rightsBasis", "catalogRedistributable") }
        val baseline = document(draft(local.clientDraftId).json().jsonObject + ("attachment" to attachment))
        val patch = reviewedCompletePatch(selection, f.choices(), baseline)
        assertEquals(true, assertIs<PatchValue.Set<Boolean>>(patch.removeAttachment).value)
        assertIs<PatchValue.Unchanged>(patch.attachment)
        val encoded = ReviewedDraftSaveEncoder(f.pubPolicy).encode(patch)
        assertEquals(true, encoded.json().jsonObject.getValue("removeAttachment").jsonPrimitive.boolean)
        assertFalse(encoded.json().jsonObject.containsKey("attachment"))
        for (field in listOf("altText", "sourcePostId")) {
            val changed = document(baseline.json().jsonObject + (field to JsonPrimitive(if (field == "altText") "old alt" else number(51))))
            assertEquals(FailureReason.INVALID_DATA, assertFailsWith<MealFailure> { reviewedCompletePatch(selection, f.choices(), changed) }.reason)
        }
    } }

    @Test fun missingHistoricalDisclosureDoesNotManufactureCompleteReviewChoices() = runTest { fixture { f ->
        val exact = reviewedChoicesDocument(f.pubPolicy, CLIENT, 1, f.choices(full = true))
        assertNull(reviewedChoiceValues(exact, null))
        val decoded = reviewedChoiceValues(exact, f.disclosure.text)!!
        assertEquals(f.disclosure.version, decoded.disclosure.version); assertEquals(f.disclosure.text, decoded.disclosure.text)
        assertEquals(listOf(number(31), number(30)), decoded.orderedMediaIds)
        val withoutVersion = document(exact.json().jsonObject.filterKeys { it != "saveDisclosureVersion" })
        val supplied = PublicationDisclosure("new-explicit-version", "New explicitly supplied display")
        val restored = reviewedChoiceValues(withoutVersion, null, supplied)!!
        assertSame(supplied, restored.disclosure)
        assertEquals(decoded.orderedMediaIds, restored.orderedMediaIds)
        assertEquals(assertIs<OptionalValue.Present<String>>(decoded.sourcePostId).value,
            assertIs<OptionalValue.Present<String>>(restored.sourcePostId).value)
        assertEquals(assertIs<OptionalValue.Present<PublicationAttachment>>(decoded.attachment).value.confirmedChanges,
            assertIs<OptionalValue.Present<PublicationAttachment>>(restored.attachment).value.confirmedChanges)
    } }

    @Test fun inspectionAfterExactReviewIsReadOnlyAndStaleSelectionFenceCannotStartLaterReview() = runTest { fixture { f ->
        val local = f.newComposer()
        val review = value(f.entry.prepareSelectedPublication(local.clientDraftId, local.localRevision, ReviewedPostBranch.DIRECT_LOCAL))
        val before = f.store.records.getValue(KEY); val writes = f.store.writes; val ids = f.ids
        val selected = value(f.entry.inspectSelected(local.clientDraftId, local.localRevision))
        assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes); assertEquals(ids, f.ids)
        assertFalse(f.entry.publications.states.value.acknowledged)
        value(f.entry.drafts.back())
        assertIs<PortResult.Failure>(f.entry.publications.prepareSelectedReview(
            PublicationTarget.DirectLocal(local.clientDraftId, local.localRevision), selected.choices!!, selected.selectionFence))
        assertIs<PortResult.Failure>(f.entry.publications.confirmPublish(review.token)); assertTrue(f.calls.isEmpty())
    } }
    @Test fun genuineLegacySaveCompletionCanUpgradeOnlyWithActualDeliveredDomainAndArchive() = runTest { fixture { f ->
        f.seedLegacy(); value(f.entry.drafts.restoreLocal()); value(f.entry.drafts.openLocal(CLIENT))
        assertTrue(value(f.entry.drafts.saveExplicitly()).serverAcknowledged)
        assertNotNull(f.legacy().completion)
        val ids = f.ids; val calls = f.calls.size
        val token = value(f.entry.prepareUpgrade(CLIENT, 3))
        value(f.entry.confirmUpgrade(token))
        assertNull(f.current().completion)
        assertEquals(ids, f.ids); assertEquals(calls, f.calls.size)
        assertFalse(f.entry.drafts.states.value.serverAcknowledged)
    } }
    @Test fun authorizedTicketTailAfterBackAndSameRootReopenExposesOnlyStaleDetachedReview() = runTest { fixture { f ->
        val local = f.newComposer()
        val review = value(f.entry.prepareSelectedPublication(local.clientDraftId, local.localRevision, ReviewedPostBranch.DIRECT_LOCAL))
        assertTrue(review.isCurrentForNavigation)
        // Deterministic delivery-ticket oracle: real admitted gate + the actual facade view.
        // This is NOT an intercepted schedule of the controller's private StateFlow.
        val gate = f.admittedGate()
        val owner = Any(); val ticket = PublicationControllerDeliveryTicket(owner)
        val before = PostComposerState(PostComposerPhase.HIDDEN)
        val after = PostComposerState(PostComposerPhase.REVIEW, review = review)
        val observed = MutableStateFlow(before)
        assertTrue(ticket.arm(owner, null, before, after, observed, gate))
        assertTrue(ticket.authorize(owner, null, before, after))
        value(f.entry.drafts.back()); value(f.entry.drafts.openLocal(local.clientDraftId))
        assertEquals(local.localRevision, f.entry.drafts.states.value.selected!!.localRevision)
        ticket.revoke() // Earlier authorization may still win its historical data CAS.
        assertSame(after, ticket.publishAuthorized(owner, null, before, after))
        assertSame(review, observed.value.review); assertFalse(observed.value.acknowledged)
        assertFalse(observed.value.review!!.isCurrentForNavigation)
        assertIs<PortResult.Failure>(f.entry.publications.confirmPublish(review.token))
        assertTrue(f.calls.isEmpty())
    } }

    @Test fun retryAndUnsentCancellationViewsCannotBecomeCurrentAgainOnSameRootRemount() = runTest { fixture { f ->
        val local = f.newComposer()
        val reviewed = value(f.entry.prepareSelectedPublication(local.clientDraftId, local.localRevision, ReviewedPostBranch.DIRECT_LOCAL))
        // Actual atomic enqueue succeeds; the NEW prerequisite then refuses first dispatch.
        f.store.afterCommit = { mutations -> if (mutations.any { it.key.collection == "mealflow.post-publications.v1" })
            f.denyPublication = FailureReason.FORBIDDEN }
        failure(f.entry.publications.confirmPublish(reviewed.token), FailureReason.FORBIDDEN)
        f.store.afterCommit = {}; f.denyPublication = null
        val retry = value(f.entry.publications.prepareOriginalRetry()).retry!!
        assertTrue(retry.isCurrentForNavigation); assertEquals(0, retry.observedAttempts)
        val cancel = value(f.entry.publications.prepareUnsentCancellation()).unsentCancellation!!
        assertFalse(retry.isCurrentForNavigation); assertTrue(cancel.isCurrentForNavigation)
        val ids = f.ids; val before = f.store.records.getValue(KEY)
        value(f.entry.drafts.back()); value(f.entry.drafts.openLocal(local.clientDraftId))
        assertFalse(cancel.isCurrentForNavigation); assertFalse(retry.isCurrentForNavigation)
        assertIs<PortResult.Failure>(f.entry.publications.confirmUnsentCancellation(cancel.token))
        assertEquals(ids, f.ids); assertTrue(f.calls.isEmpty()); assertTrue(postSame(before, f.store.records[KEY]))
    } }

    @Test fun publicPreparationHasActualControllerNavigationLifetimeWithoutSelectionDefaults() = runTest { fixture { f ->
        val local = f.newComposer()
        val selected = value(f.entry.inspectSelected(local.clientDraftId, local.localRevision))
        val actual = value(f.entry.publications.prepareReview(PublicationTarget.DirectLocal(local.clientDraftId, local.localRevision), selected.choices!!)).review!!
        assertTrue(actual.isCurrentForNavigation)
        value(f.entry.publications.dismissReview())
        assertFalse(actual.isCurrentForNavigation)
        assertTrue(f.calls.isEmpty())
    } }


    @Test fun restoredRetentionPreparationIsReadOnlyAndShowsExactFullHistoricalChoices() = runTest { fixture { f ->
        val old = f.restoredComposer()
        val before = f.store.records.getValue(KEY); val writes = f.store.writes; val ids = f.ids
        val display = value(f.entry.prepareRestoredLocalRetention(old.clientDraftId, old.localRevision))
        assertEquals(old.clientDraftId, display.snapshot.clientDraftId); assertEquals(old.localRevision, display.snapshot.localRevision)
        assertEquals(old.content.caption, display.snapshot.caption); assertEquals(old.content.altText, display.snapshot.altText)
        val rich = assertIs<DraftLocalContentV1.ComposerV2>(old.content)
        assertContentEquals(rich.exactChoices.encodeUtf8(), display.snapshot.exactChoices!!.encodeUtf8())
        assertEquals(rich.historicalDisclosureText, display.snapshot.historicalDisclosureText)
        assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes)
        assertEquals(ids, f.ids); assertTrue(f.calls.isEmpty()); assertFalse(f.entry.drafts.states.value.selected!!.localAcknowledged)
        assertNull(PostDraftCurrentHeld.edit(f.access, f.boundary))
    } }

    @Test fun explicitRestoredRetentionMakesOneNewCasAndAckWithoutChangingAnyPayloadOrLogicalRevision() = runTest { fixture { f ->
        val old = f.restoredComposer()
        val before = f.store.records.getValue(KEY); val writes = f.store.writes; val ids = f.ids
        val display = value(f.entry.prepareRestoredLocalRetention(old.clientDraftId, old.localRevision))
        val result = value(f.entry.confirmRestoredLocalRetention(display.token))
        assertTrue(result.selected!!.localAcknowledged); assertFalse(result.serverAcknowledged)
        val after = f.store.records.getValue(KEY)
        assertEquals(before.revision + 1, after.revision); assertEquals(writes + 1, f.store.writes)
        assertContentEquals(before.payload.copyForCodec(), after.payload.copyForCodec())
        assertContentEquals(old.exactUtf8.copyForCodec(), f.current().locals.single().exactUtf8.copyForCodec())
        assertEquals(PostLocalPending(old.clientDraftId, old.localRevision), f.current().localPending)
        val newProof = assertNotNull(PostDraftCurrentHeld.edit(f.access, f.boundary))
        assertTrue(newProof.isRestoredRetention); assertTrue(newProof.delivery!!.delivered(newProof))
        assertEquals(before.revision, newProof.mutation!!.expectedRevision)
        assertEquals(ids, f.ids); assertTrue(f.calls.isEmpty())
        failure(f.entry.confirmRestoredLocalRetention(display.token), FailureReason.CONFLICT)
        failure(f.entry.prepareRestoredLocalRetention(old.clientDraftId, old.localRevision), FailureReason.CONFLICT)
    } }

    @Test fun restoredRetentionPreservesUnrelatedRootsAndKnownServerAssociation() = runTest { fixture { f ->
        val other = f.newDraft("Other root")
        val current = f.newDraft("Saved root")
        assertTrue(value(f.entry.drafts.saveExplicitly()).serverAcknowledged)
        val edited = value(f.entry.drafts.editCaption(current.clientDraftId, "Newer saved text")).selected!!
        val before = f.store.records.getValue(KEY)
        f.reopenLease(); value(f.entry.drafts.restoreLocal()); value(f.entry.drafts.openLocal(current.clientDraftId))
        val display = value(f.entry.prepareRestoredLocalRetention(current.clientDraftId, edited.localRevision))
        assertNotNull(display.snapshot.savedDraft)
        assertTrue(value(f.entry.confirmRestoredLocalRetention(display.token)).selected!!.localAcknowledged)
        assertContentEquals(before.payload.copyForCodec(), f.store.records.getValue(KEY).payload.copyForCodec())
        assertEquals("Other root", f.current().locals.single { it.clientDraftId == other.clientDraftId }.content.caption)
        assertEquals("Newer saved text", f.current().locals.single { it.clientDraftId == current.clientDraftId }.content.caption)
        assertEquals(listOf("createPostDraft"), f.calls.map { it.operationId })
    } }

    @Test fun restoredRetentionTokenCannotSurviveBackSameRootReopenEditExpiryOrLeaseChange() = runTest {
        for (change in listOf("back", "edit", "expiry", "lease")) fixture { f ->
            val old = f.restoredComposer()
            val display = value(f.entry.prepareRestoredLocalRetention(old.clientDraftId, old.localRevision))
            when (change) {
                "back" -> { value(f.entry.drafts.back()); value(f.entry.drafts.openLocal(old.clientDraftId)) }
                "edit" -> value(f.entry.drafts.editCaption(old.clientDraftId, "A genuinely newer edit"))
                "expiry" -> f.time = display.expiresAtMillis + 1
                "lease" -> { f.reopenLease(); value(f.entry.drafts.restoreLocal()); value(f.entry.drafts.openLocal(old.clientDraftId)) }
            }
            val before = f.store.records.getValue(KEY); val writes = f.store.writes
            assertIs<PortResult.Failure>(f.entry.confirmRestoredLocalRetention(display.token))
            assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes); assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun restoredRetentionDeniesForgedStaleRevisionLegacyAndOrdinaryUnresolvedTyping() = runTest { fixture { f ->
        val old = f.restoredComposer()
        failure(f.entry.prepareRestoredLocalRetention(old.clientDraftId, old.localRevision + 1), FailureReason.CONFLICT)
        val actual = value(f.entry.prepareRestoredLocalRetention(old.clientDraftId, old.localRevision))
        failure(f.entry.confirmRestoredLocalRetention(PreparedRestoredLocalRetention()), FailureReason.CONFLICT)
        // A rejected lookalike must not consume the actual token.
        assertTrue(value(f.entry.confirmRestoredLocalRetention(actual.token)).selected!!.localAcknowledged)
        f.store.failBefore = true
        failure(f.entry.drafts.editCaption(old.clientDraftId, "Unstored typing must remain"), FailureReason.STORAGE_FAILURE)
        val before = f.store.records.getValue(KEY)
        failure(f.entry.prepareRestoredLocalRetention(old.clientDraftId, old.localRevision), FailureReason.CONFLICT)
        assertTrue(postSame(before, f.store.records[KEY]))
        assertEquals("Unstored typing must remain", f.entry.drafts.states.value.selected!!.caption)
        assertFalse(PostDraftCurrentHeld.edit(f.access, f.boundary)!!.isRestoredRetention)
    } }

    @Test fun restoredRetentionBeforeAfterAndBadCommitAcknowledgementRequireFreshReviewAndNewCas() = runTest {
        for (fault in listOf("before", "after", "bad-ack")) fixture { f ->
            val old = f.restoredComposer()
            val review = value(f.entry.prepareRestoredLocalRetention(old.clientDraftId, old.localRevision))
            val before = f.store.records.getValue(KEY); val ids = f.ids
            when (fault) { "before" -> f.store.failBefore = true; "after" -> f.store.failAfter = { true }; "bad-ack" -> f.store.badAck = true }
            assertIs<PortResult.Failure>(f.entry.confirmRestoredLocalRetention(review.token))
            assertFalse(f.entry.drafts.states.value.selected!!.localAcknowledged)
            val unknown = assertNotNull(PostDraftCurrentHeld.edit(f.access, f.boundary))
            assertTrue(unknown.isRestoredRetention); assertTrue(unknown.delivery?.delivered(unknown) != true)
            assertContentEquals(before.payload.copyForCodec(), f.store.records.getValue(KEY).payload.copyForCodec())
            failure(f.entry.confirmRestoredLocalRetention(review.token), FailureReason.CONFLICT)
            f.replace(); value(f.entry.drafts.restoreLocal()); value(f.entry.drafts.openLocal(old.clientDraftId))
            val actualBefore = f.store.records.getValue(KEY)
            val fresh = value(f.entry.prepareRestoredLocalRetention(old.clientDraftId, old.localRevision))
            assertTrue(value(f.entry.confirmRestoredLocalRetention(fresh.token)).selected!!.localAcknowledged)
            assertEquals(actualBefore.revision + 1, f.store.records.getValue(KEY).revision)
            assertContentEquals(before.payload.copyForCodec(), f.store.records.getValue(KEY).payload.copyForCodec())
            assertEquals(ids, f.ids); assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun cancellationAfterRetentionCommitCannotReturnAckAndFreshReviewCanRetainExactStoredSnapshot() = runTest { fixture { f ->
        val old = f.restoredComposer(); val before = f.store.records.getValue(KEY)
        val review = value(f.entry.prepareRestoredLocalRetention(old.clientDraftId, old.localRevision))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.store.afterCommit = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
        val pending = async { f.entry.confirmRestoredLocalRetention(review.token) }
        try {
            entered.await(); pending.cancel(); release.complete(Unit); pending.join()
            assertTrue(pending.isCancelled); assertFalse(f.entry.drafts.states.value.selected!!.localAcknowledged)
            assertContentEquals(before.payload.copyForCodec(), f.store.records.getValue(KEY).payload.copyForCodec())
        } finally { release.complete(Unit); f.store.afterCommit = {}; withContext(NonCancellable) { pending.cancelAndJoin() } }
        val fresh = value(f.entry.prepareRestoredLocalRetention(old.clientDraftId, old.localRevision))
        assertTrue(value(f.entry.confirmRestoredLocalRetention(fresh.token)).selected!!.localAcknowledged)
        assertEquals(before.revision + 2, f.store.records.getValue(KEY).revision); assertTrue(f.calls.isEmpty())
    } }

    @Test fun backDuringRetentionCommitPreventsLateAckWithoutLosingCommittedContent() = runTest { fixture { f ->
        val old = f.restoredComposer(); val before = f.store.records.getValue(KEY)
        val review = value(f.entry.prepareRestoredLocalRetention(old.clientDraftId, old.localRevision))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.store.afterCommit = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
        val pending = async { f.entry.confirmRestoredLocalRetention(review.token) }
        try {
            entered.await(); value(f.entry.drafts.back()); release.complete(Unit)
            assertIs<PortResult.Failure>(pending.await())
            assertNull(f.entry.drafts.states.value.selected)
            assertTrue(f.entry.drafts.states.value.localDrafts.none { it.localAcknowledged })
            assertContentEquals(before.payload.copyForCodec(), f.store.records.getValue(KEY).payload.copyForCodec())
        } finally { release.complete(Unit); f.store.afterCommit = {}; withContext(NonCancellable) { pending.cancelAndJoin() } }
        value(f.entry.drafts.openLocal(old.clientDraftId))
        val fresh = value(f.entry.prepareRestoredLocalRetention(old.clientDraftId, old.localRevision))
        assertTrue(value(f.entry.confirmRestoredLocalRetention(fresh.token)).selected!!.localAcknowledged)
    } }

    @Test fun lostRetentionCasNeverOverlaysOlderSnapshotAndFreshReviewPinsActualNewerStoredContent() = runTest { fixture { f ->
        val old = f.restoredComposer()
        val review = value(f.entry.prepareRestoredLocalRetention(old.clientDraftId, old.localRevision))
        var changed = false
        // Explicit synthetic competing durable write AFTER the controller's selected-row read.
        // This is a store-race fixture, not an owner-produced acknowledgement.
        f.store.afterRead = { key, row -> if (key == KEY && row != null && !changed) {
            changed = true
            val parsed = f.codec.decode(2, row.payload)
            val newer = f.snapshotCodec.withText(parsed.locals.single(), "Actual newer stored text", "", old.localRevision + 1)
            f.store.records[KEY] = row.copy(revision = row.revision + 1,
                payload = f.codec.encode(parsed.copy(locals = listOf(newer),
                    localPending = PostLocalPending(newer.clientDraftId, newer.localRevision))))
        } }
        try { failure(f.entry.confirmRestoredLocalRetention(review.token), FailureReason.CONFLICT) }
        finally { f.store.afterRead = { _, _ -> } }
        value(f.entry.drafts.restoreLocal()); value(f.entry.drafts.openLocal(old.clientDraftId))
        val actual = f.current().locals.single()
        assertEquals("Actual newer stored text", f.entry.drafts.states.value.selected!!.caption)
        assertFalse(f.entry.drafts.states.value.selected!!.localAcknowledged)
        assertEquals(old.localRevision + 1, actual.localRevision)
        val fresh = value(f.entry.prepareRestoredLocalRetention(actual.clientDraftId, actual.localRevision))
        assertEquals("Actual newer stored text", fresh.snapshot.caption)
        assertTrue(value(f.entry.confirmRestoredLocalRetention(fresh.token)).selected!!.localAcknowledged)
        assertContentEquals(actual.exactUtf8.copyForCodec(), f.current().locals.single().exactUtf8.copyForCodec())
        assertTrue(f.calls.isEmpty())
    } }

    @Test fun expiryWhileConfirmReadIsSuspendedCannotCommitOrRetainAProof() = runTest {
        for (backwards in listOf(false, true)) fixture { f ->
            val old = f.restoredComposer()
            val review = value(f.entry.prepareRestoredLocalRetention(old.clientDraftId, old.localRevision))
            val before = f.store.records.getValue(KEY); val writes = f.store.writes
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var paused = false
            f.afterActualRead = { key, _ -> if (key == KEY && !paused) {
                paused = true; entered.complete(Unit); release.await()
            } }
            val pending = async { f.entry.confirmRestoredLocalRetention(review.token) }
            try {
                entered.await(); f.time = if (backwards) review.preparedAtMillis - 1 else review.expiresAtMillis + 1
                release.complete(Unit)
                failure(pending.await(), FailureReason.CONFLICT)
                assertEquals(writes, f.store.writes); assertTrue(postSame(before, f.store.records[KEY]))
                assertNull(PostDraftCurrentHeld.edit(f.access, f.boundary)); assertTrue(f.calls.isEmpty())
                failure(f.entry.confirmRestoredLocalRetention(review.token), FailureReason.CONFLICT)
            } finally { release.complete(Unit); f.afterActualRead = { _, _ -> }; withContext(NonCancellable) { pending.cancelAndJoin() } }
        }
    }

    @Test fun legacyRestoreCannotUseCurrentRetentionOrSilentlyUpgradeItsMarker() = runTest { fixture { f ->
        f.seedLegacy(); value(f.entry.drafts.restoreLocal()); value(f.entry.drafts.openLocal(CLIENT))
        val before = f.store.records.getValue(KEY)
        failure(f.entry.prepareRestoredLocalRetention(CLIENT, 3), FailureReason.NOT_CONFIGURED)
        assertTrue(postSame(before, f.store.records[KEY])); assertEquals(1, f.store.records.getValue(KEY).schemaVersion)
    } }

    @Test fun restoredRetentionNavigationNeverRevivesAfterActualBackAndSameRootReopen() = runTest { fixture { f ->
        val old = f.restoredComposer()
        val review = value(f.entry.prepareRestoredLocalRetention(old.clientDraftId, old.localRevision))
        assertTrue(review.isCurrentForNavigation)
        val beforeState = f.entry.drafts.states.value
        val observed = mutableListOf<PostDraftState>()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            f.entry.drafts.states.collect { observed += it }
        }
        val before = f.store.records.getValue(KEY); val writes = f.store.writes; val ids = f.ids
        try {
            // Both operations are real controller calls; no intercepted publication or fake
            // invalidation. A collector may conflate intermediate Back, but the final state
            // is a NEW reference even when the same root/revision has been reopened.
            withContext(f.dispatcher) {
                value(f.entry.drafts.back())
                value(f.entry.drafts.openLocal(old.clientDraftId))
            }
            runCurrent()
            val reopened = f.entry.drafts.states.value
            assertNotSame(beforeState, reopened); assertSame(reopened, observed.last())
            assertEquals(old.clientDraftId, reopened.selected!!.clientDraftId)
            assertEquals(old.localRevision, reopened.selected!!.localRevision)
            assertFalse(withContext(Dispatchers.Default) { review.isCurrentForNavigation })
            failure(f.entry.confirmRestoredLocalRetention(review.token), FailureReason.CONFLICT)
            assertEquals(writes, f.store.writes); assertTrue(postSame(before, f.store.records[KEY]))
            val fresh = value(f.entry.prepareRestoredLocalRetention(old.clientDraftId, old.localRevision))
            assertTrue(fresh.isCurrentForNavigation); assertFalse(review.isCurrentForNavigation)
            failure(f.entry.confirmRestoredLocalRetention(PreparedRestoredLocalRetention()), FailureReason.CONFLICT)
            assertTrue(fresh.isCurrentForNavigation) // A lookalike cannot consume the actual view.
            assertTrue(value(f.entry.confirmRestoredLocalRetention(fresh.token)).selected!!.localAcknowledged)
            assertFalse(fresh.isCurrentForNavigation); assertFalse(review.isCurrentForNavigation)
            assertEquals(writes + 1, f.store.writes); assertEquals(ids, f.ids); assertTrue(f.calls.isEmpty())
        } finally { collector.cancelAndJoin() }
    } }

    @Test fun restoredRetentionNavigationIsAtomicReadOnlyAndInvalidatesForEditReplacementLeaseAndNewReview() = runTest {
        for (change in listOf("edit", "replacement", "lease", "new-review")) fixture { f ->
            val old = f.restoredComposer()
            val review = value(f.entry.prepareRestoredLocalRetention(old.clientDraftId, old.localRevision))
            val reads = f.store.reads; val writes = f.store.writes; val ids = f.ids; val checks = f.contexts.size
            repeat(3) { assertTrue(withContext(Dispatchers.Default) { review.isCurrentForNavigation }) }
            assertEquals(reads, f.store.reads); assertEquals(writes, f.store.writes)
            assertEquals(ids, f.ids); assertEquals(checks, f.contexts.size); assertTrue(f.calls.isEmpty())
            when (change) {
                "edit" -> value(f.entry.drafts.editCaption(old.clientDraftId, "A real newer edit"))
                "replacement" -> { f.replace(); value(f.entry.drafts.restoreLocal()); value(f.entry.drafts.openLocal(old.clientDraftId)) }
                "lease" -> { f.reopenLease(); value(f.entry.drafts.restoreLocal()); value(f.entry.drafts.openLocal(old.clientDraftId)) }
                "new-review" -> assertTrue(value(f.entry.prepareRestoredLocalRetention(old.clientDraftId, old.localRevision)).isCurrentForNavigation)
            }
            assertFalse(withContext(Dispatchers.Default) { review.isCurrentForNavigation })
            val actual = f.store.records.getValue(KEY); val actualWrites = f.store.writes
            failure(f.entry.confirmRestoredLocalRetention(review.token), FailureReason.CONFLICT)
            assertTrue(postSame(actual, f.store.records[KEY])); assertEquals(actualWrites, f.store.writes)
            assertEquals(ids, f.ids); assertTrue(f.calls.isEmpty())
        }
    }

    private suspend fun TestScope.fixture(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture(this)
        try { block(fixture) } finally { fixture.close() }
    }
    private class Fixture(test: TestScope) {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = StorageScope("reviewed-entry-synthetic", ActorKind.ACCOUNT, "not-the-mapped-account")
        val boundary = SessionBoundary(); var lease = boundary.activate(scope); val store = PostDraftControllerTest.Store(scope)
        var afterActualRead: suspend (RecordKey, PrivateRecord?) -> Unit = { _, _ -> }
        private val storageAccess = object : PrivateStateStore by store {
            override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> {
                val actual = store.read(scope, key)
                if (actual is PortResult.Value) afterActualRead(key, actual.value)
                return actual
            }
        }
        val draftPolicy = policy(); val pubPolicy = PostPublicationClientPolicy(1_048_576, 65_536, 8192, 64, 64, 8, 32, 32, 32, 65_536, 8192, 60_000)
        val snapshotCodec = DraftLocalSnapshotCodecV1(draftPolicy, pubPolicy)
        val codec = PostDraftV2Codec(scope.environment, ORIGIN, draftPolicy, pubPolicy, snapshotCodec, PublicationOriginalLinkCodecV1(pubPolicy, snapshotCodec))
        var time = 1_000_000L; var ids = 0; val calls = mutableListOf<ApiCall>()
        val clock = EpochClock { time }; val connectivity = ConnectivityPort { Connectivity.ONLINE }; val source = MealOperationIds { number(100 + ++ids) }
        var remote: WireDocument? = null
        private val transport = object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                assertSame(this@Fixture.lease, lease); calls += call
                return when (call.operationId) {
                    "createPostDraft" -> {
                        val body = WireDocument.decode(call.body!!.copyForCodec()).json().jsonObject
                        remote = document(draft(body.getValue("clientDraftId").jsonPrimitive.content).json().jsonObject + body)
                        response(remote!!, 201)
                    }
                    "updatePostDraft" -> {
                        val expected = ReviewedPostDraftAdapter(8192).expectedFields(call, remote!!, call.ifMatch!!)
                        remote = document(expected.json().jsonObject + mapOf("updatedAt" to JsonPrimitive(TIME), "expiresAt" to JsonPrimitive("2030-01-01T00:00:00Z")))
                        response(remote!!)
                    }
                    "publishPost" -> PortResult.Value(postReply(call))
                    "getPostDraft" -> response(remote!!)
                    else -> PortResult.Failure(FailureReason.NOT_CONFIGURED)
                }
            }
        }
        var access = AuthenticatedMealPlanningAccess(lease, ORIGIN, storageAccess, transport, true)
        var oldPorts = ReviewedDraftSaveTestPorts(access, boundary)
        val disclosure = oldPorts.actualDisclosure
        val contexts = mutableListOf<ReviewedPostPrerequisiteContext>()
        var denyPublication: FailureReason? = null
        var beforePublication: suspend () -> Unit = {}
        val integration = object : ReviewedKitchenIntegration {
            override val principals get() = oldPorts.principals
            override val delivery get() = oldPorts.delivery
            override suspend fun disclosure(context: ReviewedPostPrerequisiteContext): PortResult<PublicationDisclosure> {
                contexts += context; assertSame(lease, context.lease); assertEquals(ACCOUNT, context.principal.canonicalUserId)
                return PortResult.Value(disclosure)
            }
            override suspend fun requireNewPrivateSave(context: ReviewedPostPrerequisiteContext, check: ReviewedPrivateSaveCheck): PortResult<Unit> {
                assertEquals(ReviewedPostPurpose.PRIVATE_SAVE, context.purpose)
                assertEquals(context.clientDraftId, check.target.clientDraftId)
                assertEquals(context.clientDraftId, check.originalReviewedLocal.clientDraftId)
                assertEquals(context.clientDraftId, check.separatelyObservedCurrentLocal.clientDraftId)
                return PortResult.Value(Unit) // Explicit synthetic policy only.
            }
            override suspend fun requirePrivateSaveReplay(context: ReviewedPostPrerequisiteContext, check: ReviewedPrivateSaveReplayCheck): PortResult<Unit> {
                assertEquals(ReviewedPostPurpose.PRIVATE_SAVE, context.purpose); assertTrue(check.observedAttempts > 0)
                return PortResult.Value(Unit)
            }
            override suspend fun requireNewPublication(context: ReviewedPostPrerequisiteContext, check: ReviewedPublicationCheck): PortResult<Unit> {
                assertEquals(ReviewedPostPurpose.PUBLICATION, context.purpose); beforePublication()
                return denyPublication?.let { PortResult.Failure(it) } ?: PortResult.Value(Unit)
            }
            override suspend fun requirePublicationReplay(context: ReviewedPostPrerequisiteContext, check: ReviewedPublicationReplayCheck): PortResult<Unit> {
                assertEquals(ReviewedPostPurpose.PUBLICATION, context.purpose); assertTrue(check.observedAttempts > 0)
                return PortResult.Value(Unit)
            }
        }
        var meals = MealRequestController(access, boundary, dispatcher, clock, connectivity, source, MealFlowPolicy(60_000, 60_000, 2, 20))
        var bundle = create()
        val entry get() = bundle.reviewedPosts!!
        fun create() = MealKitchenControllers.createWithReviewedPosts(access, boundary, dispatcher, clock, connectivity, source,
            meals, meals.draftReadiness, CookingFlowPolicy(60_000, 65_536, 65_536), CookbookPolicy(20, 60_000), draftPolicy, pubPolicy, integration)
        fun legacyBundle() = MealKitchenControllers.createWithDrafts(access, boundary, dispatcher, clock, connectivity, source,
            meals, meals.draftReadiness, CookingFlowPolicy(60_000, 65_536, 65_536), CookbookPolicy(20, 60_000), draftPolicy)
        suspend fun newDraft(caption: String = "New draft") = value(entry.drafts.newLocalDraft(caption)).selected!!
        suspend fun newComposer(): LocalPostDraft {
            val local = newDraft()
            return value(entry.editChoices(local.clientDraftId, local.localRevision, choices())).selected!!
        }
        suspend fun replace() { bundle.close(); bundle = create() }
        /** New real SessionLease over the same synthetic persisted port; not process/runtime proof. */
        suspend fun reopenLease() {
            bundle.close(); meals.close(); oldPorts.delivery.revoke(); boundary.clear()
            lease = boundary.activate(scope)
            access = AuthenticatedMealPlanningAccess(lease, ORIGIN, storageAccess, transport, true)
            oldPorts = ReviewedDraftSaveTestPorts(access, boundary)
            meals = MealRequestController(access, boundary, dispatcher, clock, connectivity, source, MealFlowPolicy(60_000, 60_000, 2, 20))
            bundle = create()
        }
        suspend fun restoredComposer(full: Boolean = true): DraftLocalSnapshotV1 {
            val local = newDraft()
            value(entry.editChoices(local.clientDraftId, local.localRevision, choices(full)))
            val actual = current().locals.single()
            reopenLease()
            value(entry.drafts.restoreLocal()); value(entry.drafts.openLocal(actual.clientDraftId))
            assertNull(PostDraftCurrentHeld.edit(access, boundary))
            return actual
        }
        suspend fun close() { bundle.close(); meals.close(); oldPorts.delivery.revoke(); boundary.clear() }
        fun current() = codec.decode(2, store.records.getValue(KEY).payload)
        fun legacy() = PostDraftCodec(ORIGIN, draftPolicy).decode(store.records.getValue(KEY).payload)
        fun seedLegacy() {
            remote = draft(CLIENT, "Legacy kept")
            store.records[KEY] = PrivateRecord(1, 1, PostDraftCodec(ORIGIN, draftPolicy).encode(PostRecord(10,
                listOf(PostLocal(CLIENT, 3, "Legacy kept", null, remote, "\"1\"")), listOf(CLIENT))))
        }
        fun choices(full: Boolean = false) = ReviewedPostChoices("Explicit post",
            if (full) OptionalValue.Present("") else OptionalValue.Absent,
            if (full) listOf(number(31), number(30)) else emptyList(),
            if (full) PublicationAudience.Circles(listOf(number(41), number(40))) else PublicationAudience.OnlyYou,
            full, if (full) OptionalValue.Present(PublicationAttachment(PublicationAttachmentSource.RecipeVersion(number(50)),
                listOf("One", "Two"), AttachmentReviewStatus.REVIEWED, AttachmentRightsBasis.CATALOG_REDISTRIBUTABLE)) else OptionalValue.Absent,
            full, disclosure, if (full) OptionalValue.Present(number(51)) else OptionalValue.Absent)
        suspend fun admittedGate(): PublicationDeliveryGate = withContext(dispatcher) {
            // Separate real composition admission, no journal/controller bind or storage claim.
            val composition = MealKitchenComposition(access, boundary, dispatcher, clock, connectivity)
            val borrower = composition.bind(MealKitchenFeature.POST_PUBLICATIONS, object : MealKitchenHooks {
                override suspend fun checkCurrent() = Unit
                override fun beforeCommit(mutations: List<StoreMutation>) = error("Gate-only fixture cannot commit")
            })
            val principal = PublicationPrincipalAdmission(composition, borrower, integration.principals)
            val delivery = PrincipalDeliveryAdmission(principal, integration.delivery)
            try { composition.operate(borrower) {
                val permit = composition.composerPermit(borrower)
                val mapped = principal.resolve(permit)
                val witness = delivery.capture(permit, mapped)
                delivery.registerDelivery(permit, mapped, witness)
            } } finally { composition.release(borrower) }
        }
        fun postReply(call: ApiCall): ApiReply {
            val selected = PostPublicationAdapter(8192).expectedSelection(call).json().jsonObject
            val post = buildJsonObject {
                put("id", number(81)); put("version", 1); put("createdAt", TIME); put("updatedAt", TIME)
                put("author", buildJsonObject { put("userId", ACCOUNT); put("displayName", "Synthetic"); put("handle", "synthetic"); put("avatarMediaId", number(82)) })
                for (field in listOf("caption", "altText", "mediaIds", "keepOnPlate", "attachment", "sourcePostId")) selected[field]?.let { put(field, it) }
                put("audience", JsonObject(selected.getValue("audience").jsonObject + ("bindings" to JsonArray(emptyList()))))
                put("status", "published"); put("publishedAt", TIME); put("expiresAt", "2030-01-01T00:00:00Z")
                put("savePolicy", buildJsonObject { put("allowFutureSaves", selected.getValue("allowRecipeSaves")); put("policyVersion", 1)
                    put("disclosureVersion", selected.getValue("saveDisclosureVersion")) })
                put("aclVersion", 1); put("capabilities", JsonArray(listOf(JsonPrimitive("view"), JsonPrimitive("delete")))); put("reactionCounts", JsonArray(emptyList()))
            }
            return ApiReply(201, PrivateBytes(post.toString().encodeToByteArray()), contentType = "application/json", etag = "\"1\"")
        }
    }
    private companion object {
        const val ACCOUNT = "aaaaaaaa-1111-4111-8111-111111111111"
        fun <T> value(result: PortResult<T>): T = when (result) { is PortResult.Value -> result.value; is PortResult.Failure -> fail("Expected success: ${result.reason}") }
        fun failure(result: PortResult<*>, expected: FailureReason) = assertEquals(expected, assertIs<PortResult.Failure>(result).reason)
    }
}
