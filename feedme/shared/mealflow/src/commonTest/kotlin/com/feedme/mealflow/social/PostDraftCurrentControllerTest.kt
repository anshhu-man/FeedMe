package com.feedme.mealflow.social

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.kitchen.PrivateKitchenSession
import com.feedme.mealflow.*
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.CLIENT
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.KEY
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.ORIGIN
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.SERVER
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.TIME
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.document
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.draft
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.failure
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.number
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.page
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.policy
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.problem
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.response
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.value
import com.feedme.sync.CommandIntent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Public-controller tests over actual composition, queue and journal owner with explicitly
 * synthetic account/transport/CAS store. Not native SQLite/provider/HTTP acceptance. Historical
 * reviewed originals are seeded through the actual queue before constructing the live controller;
 * that fixture setup is not an implementation of live reviewed-Save consent. */
@OptIn(ExperimentalCoroutinesApi::class)
class PostDraftCurrentControllerTest {
    @Test fun configuredEmptyOwnerWritesCurrentFormatAndUnconfiguredFactoryKeepsLegacy() = runTest {
        for (configured in listOf(false, true)) {
            val f = Fixture(this, configured)
            try {
                val state = f.controller.newLocalDraft("Hello").value()
                assertTrue(state.selected!!.localAcknowledged); assertFalse(state.serverAcknowledged)
                assertEquals(if (configured) 2 else 1, f.store.records.getValue(KEY).schemaVersion)
                assertEquals(1, f.idCalls); assertTrue(f.calls.isEmpty())
            } finally { f.close() }
        }
    }
    @Test fun configuredOwnerReadsLegacyWithoutMigratingOrChangingOriginalBytes() = runTest {
        val f = Fixture(this)
        try {
            val raw = PostDraftCodec(ORIGIN, f.draftPolicy).encode(PostRecord(10, listOf(PostLocal(CLIENT, 2, "Old", "")), listOf(CLIENT)))
            f.store.records[KEY] = PrivateRecord(7, 1, raw)
            val state = f.controller.restoreLocal().value()
            assertEquals("Old", state.localDrafts.single().caption); assertFalse(state.serverAcknowledged)
            assertEquals(0, f.store.writes); assertEquals(0, f.idCalls)
            assertContentEquals(raw.copyForCodec(), f.store.records.getValue(KEY).payload.copyForCodec())
            f.controller.openLocal(CLIENT).value(); f.controller.editCaption(CLIENT, "Still legacy").value()
            assertEquals(1, f.store.records.getValue(KEY).schemaVersion)
        } finally { f.close() }
    }
    @Test fun unconfiguredCurrentRestoreIsTypedNotConfiguredWithNoWriteOrFallback() = runTest {
        val f = Fixture(this, configured = false)
        try {
            f.seed(f.composer())
            val before = f.store.records.getValue(KEY)
            failure(f.controller.restoreLocal(), FailureReason.NOT_CONFIGURED)
            assertEquals(0, f.store.writes); assertEquals(0, f.idCalls); assertTrue(f.calls.isEmpty())
            assertTrue(postSame(before, f.store.records[KEY])); assertTrue(f.controller.states.value.localDrafts.isEmpty())
        } finally { f.close() }
    }
    @Test fun captionAndAltEditsPreserveEveryUnownedComposerChoiceAndHistoricalDisclosure() = runTest {
        val f = Fixture(this)
        try {
            val before = f.composer(); f.seed(before); f.open(CLIENT)
            val state = f.controller.editCaption(CLIENT, "New caption").value()
            assertTrue(state.selected!!.localAcknowledged); assertEquals("", state.selected!!.altText)
            assertTrue(state.selected!!.requiresReviewedSave); assertFalse(state.serverAcknowledged)
            val changed = f.current().locals.single()
            f.assertUnowned(before, changed)
            val alt = f.controller.editAltText(CLIENT, "New description").value()
            assertEquals("New caption", alt.selected!!.caption); assertEquals("New description", alt.selected!!.altText)
            f.assertUnowned(before, f.current().locals.single()); assertEquals(0, f.idCalls); assertTrue(f.calls.isEmpty())
        } finally { f.close() }
    }
    @Test fun invalidCurrentPasteKeepsCompleteSnapshotRevisionAndLocalAckThenValidEditRecovers() = runTest {
        val f = Fixture(this)
        try {
            f.seed(f.composer()); f.open(CLIENT)
            val before = f.current().locals.single(); val writes = f.store.writes
            failure(f.controller.editCaption(CLIENT, "x".repeat(501)), FailureReason.INVALID_DATA)
            val error = f.controller.states.value
            assertEquals(PostDraftIssue.INVALID_INPUT, error.issue); assertEquals(before.localRevision, error.selected!!.localRevision)
            assertTrue(error.selected!!.localAcknowledged); assertEquals(writes, f.store.writes)
            assertContentEquals(before.exactUtf8.copyForCodec(), f.current().locals.single().exactUtf8.copyForCodec())
            assertEquals(PostDraftIssue.NONE, f.controller.editCaption(CLIENT, "Good").value().issue)
        } finally { f.close() }
    }
    @Test fun completePersonalRecipeAndLargeExactNumericLexemeSurviveCurrentTextOverlay() = runTest {
        val f = Fixture(this)
        try {
            val personal = buildJsonObject {
                put("title", "Full personal recipe")
                put("ingredients", JsonArray(listOf(buildJsonObject { put("ingredientId", SOURCE)
                    put("quantity", Json.parseToJsonElement("9007199254740993.000")); put("unit", "g"); put("optional", false) })))
                put("steps", JsonArray(listOf(buildJsonObject { put("stepId", "mix"); put("position", 1); put("instruction", "Mix fully")
                    put("ingredientIds", JsonArray(listOf(JsonPrimitive(SOURCE)))); put("requiredEquipmentIds", JsonArray(listOf(JsonPrimitive("bowl"))))
                    put("mandatorySafetyStep", false) })))
                put("servings", 1); put("activeMinutes", 1); put("totalMinutes", 1); put("utensilCount", 1)
                put("equipmentIds", JsonArray(listOf(JsonPrimitive("bowl")))); put("modes", JsonArray(listOf(JsonPrimitive("assemble"))))
            }
            val attachment = buildJsonObject { put("personalRecipe", personal); put("reviewStatus", "personal")
                put("rightsBasis", "creatorOriginal"); put("confirmedChanges", JsonArray(listOf(JsonPrimitive("Original instructions")))) }
            val input = document(choices().json().jsonObject + ("attachment" to attachment))
            val before = f.composer(content = input); f.seed(before); f.open(CLIENT)
            f.controller.editCaption(CLIENT, "Changed only caption").value()
            val after = f.current().locals.single(); f.assertUnowned(before, after)
            val stored = assertIs<DraftLocalContentV1.ComposerV2>(after.content).exactChoices.json().jsonObject
                .getValue("attachment").jsonObject.getValue("personalRecipe").jsonObject
            assertEquals(personal, stored)
            assertEquals("9007199254740993.000", stored.getValue("ingredients").jsonArray.single().jsonObject.getValue("quantity").jsonPrimitive.content)
        } finally { f.close() }
    }
    @Test fun staleCurrentClientCallbackCannotEditAnotherSelectionOrPublishInputFeedback() = runTest {
        val f = Fixture(this)
        try {
            f.seed(f.composer(), f.composer(number(92))); f.open(number(92)); val before = f.store.records.getValue(KEY)
            failure(f.controller.editCaption(CLIENT, "x".repeat(501)), FailureReason.CONFLICT)
            assertEquals(PostDraftIssue.NONE, f.controller.states.value.issue)
            assertEquals(number(92), f.controller.states.value.selected!!.clientDraftId); assertTrue(postSame(before, f.store.records[KEY]))
        } finally { f.close() }
    }
    @Test fun currentComposerPlainSaveRefusesBeforeIdentifierQueueOrTransport() = runTest {
        val f = Fixture(this)
        try {
            f.seed(f.composer()); f.open(CLIENT); val before = f.store.records.getValue(KEY)
            failure(f.controller.saveExplicitly(), FailureReason.NOT_CONFIGURED)
            assertEquals(0, f.idCalls); assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty()); assertTrue(postSame(before, f.store.records[KEY]))
            assertNull(f.current().command)
        } finally { f.close() }
    }
    @Test fun currentTextSaveAndUpdateUseExistingPrivateBodiesAndExactReceiptApplication() = runTest {
        val f = Fixture(this)
        try {
            val local = f.controller.newLocalDraft("Hello", "Description").value().selected!!
            val saved = f.controller.saveExplicitly().value()
            assertTrue(saved.serverAcknowledged); assertEquals(2, f.store.records.getValue(KEY).schemaVersion)
            assertIs<PostDraftCompletionV2.Legacy>(f.current().completion)
            val body = f.calls.single().document().json().jsonObject
            assertEquals(postSelfAudience(), body["audience"]); assertEquals(JsonArray(emptyList()), body["mediaIds"])
            assertFalse("saveDisclosureVersion" in body); assertFalse("attachment" in body)
            f.controller.editCaption(local.clientDraftId, "Changed").value()
            assertTrue(f.controller.saveExplicitly().value().serverAcknowledged)
            assertEquals(setOf("caption", "altText"), f.calls.last().document().json().jsonObject.keys)
        } finally { f.close() }
    }
    @Test fun currentSaveThenRefreshRetiresOnlyDeliveredMarkerAndDoesNotAcknowledgeGet() = runTest {
        val f = Fixture(this)
        try {
            f.controller.newLocalDraft("Hello").value(); f.controller.saveExplicitly().value()
            val refreshed = f.controller.refreshRemote().value()
            assertFalse(refreshed.serverAcknowledged); assertNull(refreshed.pending); assertNull(f.current().completion)
            assertEquals(listOf("createPostDraft", "getPostDraft"), f.calls.map { it.operationId })
        } finally { f.close() }
    }
    @Test fun currentGetPreservesFullComposerChoicesInsteadOfReplacingWithRemoteDefaults() = runTest {
        val f = Fixture(this)
        try {
            val local = f.composer(observed = true); f.seed(local); f.remote = draft(CLIENT, "Remote changed", "2")
            f.open(CLIENT); val state = f.controller.refreshRemote().value()
            assertEquals("Caption", state.selected!!.caption); assertEquals("Remote changed", state.selected!!.server!!.caption)
            assertEquals(3L, state.selected!!.localRevision); assertFalse(state.serverAcknowledged)
            f.assertUnowned(local, f.current().locals.single())
        } finally { f.close() }
    }
    @Test fun currentNewGetImportsHistoricalTextAndCompleteAssociationWithoutInventingReview() = runTest {
        val f = Fixture(this)
        try {
            f.remote = document(draft(CLIENT).json().jsonObject + ("sourcePostId" to JsonPrimitive(SOURCE.uppercase())))
            val state = f.controller.refreshRemote(SERVER).value()
            assertFalse(state.selected!!.requiresReviewedSave); assertFalse(state.serverAcknowledged)
            val local = f.current().locals.single(); assertIs<DraftLocalContentV1.TextV1>(local.content)
            assertContentEquals(f.remote!!.encodeUtf8(), assertIs<DraftServerAssociationV1.Observed>(local.serverAssociation).exactPostDraft.encodeUtf8())
            assertEquals(0, f.idCalls)
        } finally { f.close() }
    }
    @Test fun currentDeniedGetRedactsObservationButNotAssociationOrRetainedComposer() = runTest {
        val f = Fixture(this)
        try {
            val local = f.composer(observed = true); f.seed(local); f.open(CLIENT)
            f.handler = { problem(403, "DRAFT_UNAVAILABLE") }
            f.controller.refreshRemote()
            val shown = f.controller.states.value.selected!!
            assertNull(shown.server); assertTrue(shown.serverAssociated); assertTrue(shown.requiresReviewedSave)
            assertContentEquals(local.exactUtf8.copyForCodec(), f.current().locals.single().exactUtf8.copyForCodec())
        } finally { f.close() }
    }
    @Test fun currentLocalRemovalRequiresExactRevisionAndNeverRecyclesClosedRoot() = runTest {
        val f = Fixture(this)
        try {
            f.seed(f.composer()); f.open(CLIENT); val newer = f.controller.editCaption(CLIENT, "Newer").value().selected!!
            val writes = f.store.writes
            failure(f.controller.discardLocal(CLIENT, 3), FailureReason.CONFLICT); assertEquals(writes, f.store.writes)
            f.controller.discardLocal(CLIENT, newer.localRevision).value()
            assertTrue(f.current().locals.isEmpty()); assertEquals(CLIENT, f.current().terminals.single().clientId)
            assertTrue(CLIENT in f.current().issued); f.nextId = CLIENT
            failure(f.controller.newLocalDraft("Another"), FailureReason.CONFLICT); assertTrue(f.current().locals.isEmpty())
        } finally { f.close() }
    }
    @Test fun currentExplicitServerDiscardAppliesTypedTerminalAndLeavesEditorOnlyAfterAck() = runTest {
        val f = Fixture(this)
        try {
            f.seed(f.composer(observed = true)); f.open(CLIENT)
            val prepared = f.controller.prepareServerDiscard().value().discardConfirmation!!
            val state = f.controller.confirmServerDiscard(prepared).value()
            assertTrue(state.serverAcknowledged); assertEquals(PostDraftScreen.LOCAL_LIST, state.screen); assertNull(state.selected)
            assertIs<PostDraftTerminalV2.LegacyDiscard>(f.current().terminals.single())
            assertEquals("deletePostDraft", f.calls.single().operationId); assertNull(f.calls.single().body)
        } finally { f.close() }
    }
    @Test fun currentChangedCasBadAckRetainsFullTextWitnessAndExplicitRetryAcknowledgesOnlyLocalCommit() = runTest {
        val f = Fixture(this)
        try {
            val before = f.composer(); f.seed(before); f.open(CLIENT); f.store.badAck = true
            failure(f.controller.editCaption(CLIENT, "Pending"), FailureReason.STORAGE_FAILURE)
            assertFalse(f.controller.states.value.selected!!.localAcknowledged)
            assertNotNull(PostDraftCurrentHeld.edit(f.access, f.boundary)); assertNull(PostDraftHeld.edit(f.access, f.boundary))
            f.assertUnowned(before, f.current().locals.single())
            val recovered = f.controller.editCaption(CLIENT, "Pending").value()
            assertTrue(recovered.selected!!.localAcknowledged); assertFalse(recovered.serverAcknowledged); assertTrue(f.calls.isEmpty())
        } finally { f.close() }
    }
    @Test fun currentCommittedUnknownThenNoWriteFailureDoesNotLoseActuallyWitnessedPredecessor() = runTest {
        val f = Fixture(this)
        try {
            val before = f.composer(); f.seed(before); f.open(CLIENT)
            f.store.failAfter = { it.any { change -> change.key == KEY } }
            failure(f.controller.editCaption(CLIENT, "A"), FailureReason.OUTCOME_UNKNOWN)
            f.store.failBefore = true; failure(f.controller.editCaption(CLIENT, "B"), FailureReason.STORAGE_FAILURE)
            val recovered = f.controller.editCaption(CLIENT, "C").value()
            assertEquals("C", recovered.selected!!.caption); assertTrue(recovered.selected!!.localAcknowledged)
            f.assertUnowned(before, f.current().locals.single()); assertEquals(0, f.idCalls)
        } finally { f.close() }
    }
    @Test fun currentHeldUnknownEditSurvivesControllerCloseWithSameLeaseAndFullContent() = runTest {
        val f = Fixture(this)
        try {
            val before = f.composer(); f.seed(before); f.open(CLIENT); f.store.failBefore = true
            failure(f.controller.editCaption(CLIENT, "Retained"), FailureReason.STORAGE_FAILURE)
            val held = PostDraftCurrentHeld.edit(f.access, f.boundary)!!
            f.reopen(); f.open(CLIENT)
            assertSame(held, PostDraftCurrentHeld.edit(f.access, f.boundary))
            assertEquals("Retained", f.controller.states.value.selected!!.caption)
            assertFalse(f.controller.states.value.selected!!.localAcknowledged)
            assertTrue(f.controller.editCaption(CLIENT, "Retained").value().selected!!.localAcknowledged)
            f.assertUnowned(before, f.current().locals.single())
        } finally { f.close() }
    }
    @Test fun currentUnknownApplyRequiresExactSameLeaseArchiveProofAndFreshChangedCas() = runTest {
        val f = Fixture(this)
        try {
            f.controller.newLocalDraft("Hello").value()
            f.store.failAfter = { batch -> batch.filterIsInstance<StoreMutation.Put>().any { it.key == KEY && f.codec.decode(2, it.payload).completion != null } }
            failure(f.controller.saveExplicitly(), FailureReason.OUTCOME_UNKNOWN)
            val held = PostDraftCurrentHeld.apply(f.access, f.boundary)!!
            assertNotNull(held.archive); assertFalse(f.controller.states.value.serverAcknowledged)
            val revision = f.store.records.getValue(KEY).revision; f.reopen()
            val state = f.controller.retryOriginal().value()
            assertTrue(state.serverAcknowledged); assertTrue(f.store.records.getValue(KEY).revision > revision)
            assertEquals(1, f.calls.size); assertTrue(held.delivery!!.delivered(held))
        } finally { f.close() }
    }
    @Test fun currentUnknownApplyArchiveCorruptionCannotBecomeAckOrEraseOriginalEvidence() = runTest {
        val f = Fixture(this)
        try {
            f.controller.newLocalDraft("Hello").value()
            f.store.failAfter = { batch -> batch.filterIsInstance<StoreMutation.Put>().any { it.key == KEY && f.codec.decode(2, it.payload).completion != null } }
            f.controller.saveExplicitly(); val proof = PostDraftCurrentHeld.apply(f.access, f.boundary)!!
            val archive = proof.archive!!; val actual = f.store.records.getValue(archive.key)
            f.store.records[archive.key] = actual.copy(payload = PrivateBytes("{}".encodeToByteArray()))
            val before = f.store.records.getValue(KEY)
            failure(f.controller.retryOriginal(), FailureReason.CONFLICT)
            assertTrue(postSame(before, f.store.records[KEY])); assertFalse(f.controller.states.value.serverAcknowledged)
            assertSame(proof, PostDraftCurrentHeld.apply(f.access, f.boundary)); assertEquals(1, f.calls.size)
        } finally { f.close() }
    }
    @Test fun reconstructedCurrentCompletionIsHistoricalNotNewServerAck() = runTest {
        val f = Fixture(this)
        try {
            f.controller.newLocalDraft("Hello").value(); f.controller.saveExplicitly().value()
            PostDraftCurrentHeld.clear(f.access, f.boundary) // Explicit process-loss simulation; no live proof survives.
            f.reopen(); val state = f.controller.restoreLocal().value()
            assertFalse(state.serverAcknowledged); assertEquals("HISTORICAL_COMPLETION", state.pending!!.phase)
            val before = f.store.records.getValue(KEY)
            failure(f.controller.retryOriginal(), FailureReason.CONFLICT); assertTrue(postSame(before, f.store.records[KEY]))
            assertEquals(1, f.calls.size)
        } finally { f.close() }
    }
    @Test fun backDuringCurrentTransportHidesUnsentActionAndLateResultCannotReturnAck() = runTest {
        val f = Fixture(this); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var job: Deferred<PortResult<PostDraftState>>? = null
        try {
            f.controller.newLocalDraft("Hello").value()
            f.handler = { call -> entered.complete(Unit); release.await(); f.reply(call) }
            job = async { f.controller.saveExplicitly() }; entered.await()
            val back = f.controller.back().value()
            assertEquals("DISPATCH_UNOBSERVED", back.pending!!.phase); assertEquals(-1, back.pending!!.attempts)
            assertFalse(back.pending!!.canDiscardUnsent); assertTrue(back.pending!!.canRetry)
            release.complete(Unit); failure(job.await(), FailureReason.STALE_SESSION)
            assertFalse(f.controller.states.value.serverAcknowledged); assertFalse(f.controller.states.value.pending!!.canDiscardUnsent)
            f.handler = f::reply
            val restored = f.controller.restoreLocal().value()
            assertEquals(1, restored.pending!!.attempts); assertFalse(restored.pending!!.canDiscardUnsent)
            assertNotEquals("DISPATCH_UNOBSERVED", restored.pending!!.phase); assertEquals(1, f.calls.size)
            failure(f.controller.discardUnsent(), FailureReason.CONFLICT)
            f.time = assertNotNull(restored.earliestRetryAtMillis) + 1
            assertTrue(f.controller.retryOriginal().value().serverAcknowledged)
            assertEquals(2, f.calls.size); assertTrue(postSameCall(f.calls.first(), f.calls.last()))
        } finally { release.complete(Unit); job?.cancelAndJoin(); f.close() }
    }
    @Test fun cancellingCurrentLocalCommitCannotDeliverAckAndExactProposalRemainsRecoverable() = runTest {
        val f = Fixture(this); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var job: Deferred<PortResult<PostDraftState>>? = null
        try {
            f.seed(f.composer()); f.open(CLIENT)
            f.store.afterCommit = { batch -> if (batch.any { it.key == KEY }) { entered.complete(Unit); release.await() } }
            job = async { f.controller.editCaption(CLIENT, "Held") }; entered.await(); job.cancel()
            release.complete(Unit); assertFailsWith<CancellationException> { job.await() }
            assertFalse(f.controller.states.value.selected!!.localAcknowledged)
            assertEquals("Held", PostDraftCurrentHeld.edit(f.access, f.boundary)!!.proposed.content.caption)
            f.store.afterCommit = {}; assertTrue(f.controller.editCaption(CLIENT, "Held").value().selected!!.localAcknowledged)
        } finally { release.complete(Unit); job?.cancelAndJoin(); f.close() }
    }
    @Test fun reviewedHistoricalRetryUsesExactRawPatchPathKeyBaselineAndFullReceipt() = runTest {
        val f = Fixture(this)
        try {
            val original = f.seedReviewedOriginal()
            val state = f.retryReviewed()
            assertTrue(state.serverAcknowledged); assertTrue(postSameCall(original.historicalCall(), f.calls.single()))
            val completed = assertIs<PostDraftCompletionV2.ReviewedApplied>(f.current().completion)
            assertContentEquals(original.original.body.copyForCodec(), completed.original.original.body.copyForCodec())
            assertContentEquals(original.original.baseline.encodeUtf8(), completed.original.original.baseline.encodeUtf8())
            assertEquals(original.original.path, completed.original.original.path)
            assertEquals(SOURCE.uppercase(), postString(completed.actualDraft, "sourcePostId")); assertEquals(0, f.idCalls)
        } finally { f.close() }
    }
    @Test fun reviewedHistoricalUnsentDiscardArchivesExactOriginalWithoutTransportOrServerAck() = runTest {
        val f = Fixture(this)
        try {
            val original = f.seedReviewedOriginal()
            val state = f.controller.discardUnsent().value()
            assertFalse(state.serverAcknowledged); assertTrue(f.calls.isEmpty()); assertEquals(0, f.idCalls)
            val completed = assertIs<PostDraftCompletionV2.ReviewedUnsent>(f.current().completion)
            assertTrue(postSameCall(original.historicalCall(), completed.original.historicalCall()))
            assertTrue(f.current().locals.single().content is DraftLocalContentV1.ComposerV2)
            assertTrue(PostDraftCurrentHeld.apply(f.access, f.boundary)!!.unsent)
        } finally { f.close() }
    }
    @Test fun reviewedOriginalReplyLostThenGetIsObservationAndOriginalRetryRetainsBytes() = runTest {
        val f = Fixture(this)
        try {
            val original = f.seedReviewedOriginal(); var drop = true
            f.handler = { call -> val reply = f.reply(call); if (call.operationId == "updatePostDraft" && drop) {
                drop = false; PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
            } else reply }
            f.retryReviewed(); assertFalse(f.controller.states.value.serverAcknowledged)
            val get = f.controller.refreshRemote(SERVER).value()
            assertFalse(get.serverAcknowledged); assertEquals(original.id, get.pending!!.commandId)
            assertTrue(postSameCall(original.historicalCall(), f.current().command!!.historicalCall()))
            failure(f.controller.discardUnsent(), FailureReason.CONFLICT)
            f.time += 60_000; assertTrue(f.retryReviewed().serverAcknowledged)
            assertTrue(postSameCall(f.calls.first(), f.calls.last())); assertEquals(0, f.idCalls)
        } finally { f.close() }
    }
    @Test fun reviewedRetryWithNewerTextPreservesAllUnsubmittedComposerFields() = runTest {
        val f = Fixture(this)
        try {
            val original = f.seedReviewedOriginal(); f.open(CLIENT)
            val before = f.current().locals.single()
            f.controller.editCaption(CLIENT, "Not the reviewed text").value()
            assertTrue(f.retryReviewed().serverAcknowledged)
            assertEquals("Not the reviewed text", f.current().locals.single().content.caption)
            assertTrue(postSameCall(original.historicalCall(), f.calls.single()))
            f.assertUnowned(before, f.current().locals.single())
        } finally { f.close() }
    }
    @Test fun reviewedMalformedReceiptIsDataUnverifiedNotInputFeedbackOrAck() = runTest {
        val f = Fixture(this)
        try {
            val original = f.seedReviewedOriginal()
            f.handler = { PortResult.Value(ApiReply(200, PrivateBytes("{}".encodeToByteArray()), "\"2\"", contentType = "application/json")) }
            val retry = f.controller.prepareReviewedOriginalRetry().value()
            f.controller.confirmReviewedOriginalRetry(retry.token)
            assertFalse(f.controller.states.value.serverAcknowledged)
            assertNotEquals(PostDraftIssue.INVALID_INPUT, f.controller.states.value.issue)
            assertTrue(postSameCall(original.historicalCall(), f.current().command!!.historicalCall()))
            assertNull(f.current().completion)
        } finally { f.close() }
    }
    @Test fun historicalCurrentHoldBlocksSameRootServerIntentButAllowsReservedFullLocalEdit() = runTest {
        val f = Fixture(this)
        try {
            val link = f.seedHeld(); f.open(CLIENT)
            assertTrue(f.controller.states.value.selected!!.publicationHeld)
            assertTrue(f.controller.states.value.publicationHold!!.historical)
            failure(f.controller.saveExplicitly(), FailureReason.CONFLICT)
            failure(f.controller.discardLocal(CLIENT, 3), FailureReason.CONFLICT)
            val before = f.current().locals.single(); val reserve = f.current().publicationHold!!.reservation.draftFinalizationReservedBytes
            f.controller.openLocal(CLIENT).value()
            val state = f.controller.editCaption(CLIENT, "Newer local content is not the published original").value()
            assertTrue(state.selected!!.localAcknowledged); assertTrue(state.publicationHold!!.hasNewerLocalChanges)
            assertFalse(state.serverAcknowledged); f.assertUnowned(before, f.current().locals.single())
            assertTrue(f.current().publicationHold!!.reservation.draftFinalizationReservedBytes > reserve)
            assertContentEquals(link.exactUtf8.copyForCodec(), f.current().publicationHold!!.link.exactUtf8.copyForCodec())
            assertTrue(f.calls.isEmpty()); assertEquals(0, f.idCalls)
        } finally { f.close() }
    }
    @Test fun historicalCurrentHoldSurvivesUnrelatedNewLocalWithRecomputedWholeRowReserve() = runTest {
        val f = Fixture(this)
        try {
            val link = f.seedHeld(); val before = f.current().publicationHold!!.reservation.draftFinalizationReservedBytes
            val state = f.controller.newLocalDraft("A different local draft").value()
            assertEquals(2, state.localDrafts.size); assertFalse(state.selected!!.publicationHeld)
            assertEquals(CLIENT, state.publicationHold!!.clientDraftId)
            assertTrue(f.current().publicationHold!!.reservation.draftFinalizationReservedBytes > before)
            assertContentEquals(link.exactUtf8.copyForCodec(), f.current().publicationHold!!.link.exactUtf8.copyForCodec())
            assertTrue(f.calls.isEmpty()); assertEquals(1, f.idCalls)
        } finally { f.close() }
    }
    @Test fun currentPublishedRemainderIsNoneditableAndSurvivesNewUnrelatedDraft() = runTest {
        val f = Fixture(this)
        try {
            val link = f.seedPublishedRemainder(); val retained = f.current().remainders.single().unsubmittedSnapshot.exactUtf8.copyForCodec()
            val state = f.controller.restoreLocal().value()
            assertTrue(state.localDrafts.isEmpty()); assertNull(state.selected); assertFalse(state.serverAcknowledged)
            val summary = state.unsubmittedRemainders.single()
            assertTrue(summary.historical); assertFalse(summary.editable); assertTrue(summary.requiresReviewedSave)
            assertEquals(CLIENT, summary.closedClientDraftId); assertEquals(4L, summary.newerLocalRevision)
            failure(f.controller.openLocal(CLIENT), FailureReason.NOT_FOUND)
            val next = f.controller.newLocalDraft("New root").value()
            assertNotEquals(CLIENT, next.selected!!.clientDraftId); assertEquals(1, next.unsubmittedRemainders.size)
            assertContentEquals(retained, f.current().remainders.single().unsubmittedSnapshot.exactUtf8.copyForCodec())
            assertContentEquals(link.exactUtf8.copyForCodec(), assertIs<PostDraftTerminalV2.Published>(f.current().terminals.single()).link.exactUtf8.copyForCodec())
        } finally { f.close() }
    }
    @Test fun currentRemoteListIsReadOnlyAndCannotResurrectPublishedRoot() = runTest {
        val f = Fixture(this)
        try {
            f.seedPublishedRemainder(); f.remote = draft(CLIENT)
            val before = f.store.records.getValue(KEY)
            failure(f.controller.listRemote(), FailureReason.CONFLICT)
            assertTrue(postSame(before, f.store.records[KEY])); assertEquals(0, f.store.writes); assertEquals(0, f.idCalls)
            assertTrue(f.controller.states.value.remoteItems.isEmpty()); assertTrue(f.current().locals.isEmpty())
        } finally { f.close() }
    }

    private class Fixture(test: TestScope, val configured: Boolean = true) {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val draftPolicy = policy(); val publicationPolicy = PostPublicationClientPolicy(1_048_576, 65_536, 8192, 64, 64, 8, 32, 32, 32, 65_536, 8192, 60_000)
        val snapshots = DraftLocalSnapshotCodecV1(draftPolicy, publicationPolicy)
        val links = PublicationOriginalLinkCodecV1(publicationPolicy, snapshots)
        val scope = StorageScope("current-controller-test", ActorKind.ACCOUNT, "synthetic arbitrary account actor")
        val boundary = SessionBoundary(); val lease = boundary.activate(scope); val store = PostDraftControllerTest.Store(scope)
        val codec = PostDraftV2Codec(scope.environment, ORIGIN, draftPolicy, publicationPolicy, snapshots, links)
        var time = 1_000_000L; var idCalls = 0; var nextId: String? = null; var remote: WireDocument? = null
        val calls = mutableListOf<ApiCall>(); private val exactReplies = mutableMapOf<String, PortResult<ApiReply>>()
        var handler: suspend (ApiCall) -> PortResult<ApiReply> = { reply(it) }
        val access = AuthenticatedMealPlanningAccess(lease, ORIGIN, store, object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                assertSame(this@Fixture.lease, lease); calls += call; return handler(call)
            }
        }, true)
        // Required synthetic trusted mapping/disclosure/replay inputs for the now-explicit
        // restored-original review. These are not construction defaults or restored consent.
        val reviewedPorts = ReviewedDraftSaveTestPorts(access, boundary)
        private fun create(): PostDraftController {
            val composition = MealKitchenComposition(access, boundary, dispatcher, EpochClock { time }, ConnectivityPort { Connectivity.ONLINE })
            return PostDraftController(composition, MealOperationIds { idCalls++; nextId ?: number(100 + idCalls) }, draftPolicy,
                PostDraftJournalOwner(composition, draftPolicy, configuredPublicationPolicy = publicationPolicy.takeIf { configured }), reviewedPorts)
        }
        var controller = create()
        suspend fun reopen() { controller.close(); controller = create() }
        suspend fun close() { controller.close(); boundary.clear() }
        suspend fun open(id: String) { controller.restoreLocal().value(); controller.openLocal(id).value() }
        suspend fun retryReviewed(): PostDraftState {
            val review = controller.prepareReviewedOriginalRetry().value()
            return controller.confirmReviewedOriginalRetry(review.token).value()
        }
        fun current() = codec.decode(2, store.records.getValue(KEY).payload)
        fun seed(vararg locals: DraftLocalSnapshotV1) {
            store.records[KEY] = PrivateRecord(1, 2, codec.encode(PostDraftV2Record(10, locals.toList(), locals.map { it.clientDraftId })))
        }
        fun historicalLink(): PublicationOriginalLinkV1 {
            val local = composer()
            val body = document(assertIs<DraftLocalContentV1.ComposerV2>(local.content).exactChoices.json().jsonObject +
                ("clientDraftId" to JsonPrimitive(CLIENT)))
            return links.create(PublicationJournalBindingData(scope.environment, ORIGIN, ORIGIN, number(88)), number(90), 7,
                ApiCall("publishPost", body = PrivateBytes(body.encodeUtf8()), idempotencyKey = SecretText(number(90))), local,
                PublicationReviewTargetV1.DirectLocal, PublicationDisclosure("historical-v1", "Historical text that was actually supplied"))
        }
        fun seedHeld(): PublicationOriginalLinkV1 {
            val link = historicalLink()
            val binding = PublicationJournalBindingData(scope.environment, ORIGIN, ORIGIN, number(88))
            val publication = PostPublicationJournalCodecV1(binding, publicationPolicy, links)
            val history = PublicationJournalV1(binding, 10, listOf(link.commandId), listOf(PublicationHistoryEntryV1.PendingOriginal(link)))
            val raw = PostDraftV2Record(10, listOf(link.historicalReview.exactReviewedLocalSnapshot), listOf(CLIENT, link.commandId),
                publicationHold = PostDraftPublicationHoldV2(link, PublicationCapacityReservationV1(publicationPolicy.maxResponseBytes, 1, 0, 1)))
            val row = codec.withPublicationReservation(raw, publication.reservedPublishedBytes(history))
            // Historical row fixture only, not actual publication queue/hold provenance.
            store.records[KEY] = PrivateRecord(1, 2, codec.encode(row)); return link
        }
        fun seedPublishedRemainder(): PublicationOriginalLinkV1 {
            val link = historicalLink(); val before = link.historicalReview.exactReviewedLocalSnapshot
            val newer = snapshots.withText(before, "Retained unsubmitted text", "", 4)
            val row = PostDraftV2Record(10, issued = listOf(CLIENT, link.commandId),
                terminals = listOf(PostDraftTerminalV2.Published(link, number(91))),
                remainders = listOf(PostDraftRemainderV2(link, newer)))
            store.records[KEY] = PrivateRecord(1, 2, codec.encode(row)); return link
        }
        fun composer(client: String = CLIENT, observed: Boolean = false, content: WireDocument = choices()) = snapshots.create(client, 3,
            DraftLocalContentV1.ComposerV2(content, "Historical text that was actually supplied"),
            if (observed) DraftServerAssociationV1.Observed(draft(client), "\"1\"") else DraftServerAssociationV1.NotObserved)
        fun assertUnowned(before: DraftLocalSnapshotV1, after: DraftLocalSnapshotV1) {
            val old = assertIs<DraftLocalContentV1.ComposerV2>(before.content); val next = assertIs<DraftLocalContentV1.ComposerV2>(after.content)
            assertEquals(old.exactChoices.json().jsonObject - setOf("caption", "altText"), next.exactChoices.json().jsonObject - setOf("caption", "altText"))
            assertEquals(old.historicalDisclosureText, next.historicalDisclosureText)
        }
        suspend fun seedReviewedOriginal(): PostDraftCommandV2.ReviewedPatch {
            controller.close()
            val baseline = draft(CLIENT, "Before")
            val patch = WireDocument.parse(" \n{\"caption\":\"Reviewed\",\"altText\":\"\",\"sourcePostId\":\"${SOURCE.uppercase()}\",\"mediaIds\":[\"${number(21)}\",\"${number(20)}\"]}\n")
            val original = ReviewedDraftOriginalFieldsV2(number(90), CLIENT, 3, mapOf("draftId" to SERVER.uppercase()),
                PrivateBytes(patch.encodeUtf8()), baseline, "\"1\"", 10)
            val expected = ReviewedPostDraftAdapter(8192).expectedFields(original.historicalCall(), baseline, "\"1\"")
            val fullChoices = document(expected.json().jsonObject - setOf("id", "clientDraftId", "version", "createdAt", "updatedAt", "expiresAt", "status", "publishedPostId"))
            val local = snapshots.create(CLIENT, 3, DraftLocalContentV1.ComposerV2(fullChoices, null), DraftServerAssociationV1.Observed(baseline, "\"1\""))
            val command = codec.createReviewedCommand(original, local, null)
            val row = PostDraftV2Record(time, listOf(local), listOf(CLIENT, command.id), command)
            // Sequential fixture seeding only. This temporary queue is not retained or used
            // once the actual composed controller is reconstructed over the durable records.
            val seed = PrivateKitchenSession(scope, store, boundary, dispatcher, EpochClock { time }, access.transport, ORIGIN)
            seed.commands.enqueue(lease, CommandIntent(command.id, ORIGIN, command.historicalCall()),
                listOf(StoreMutation.Put(KEY, store.records[KEY]?.revision, 2, codec.encode(row)))).value()
            remote = baseline; controller = create(); calls.clear(); return command
        }
        fun reply(call: ApiCall): PortResult<ApiReply> {
            val key = call.idempotencyKey?.use { it }
            if (key != null) exactReplies[key]?.let { return it }
            val result = when (call.operationId) {
                "createPostDraft" -> {
                    val body = call.document().json().jsonObject
                    remote = document(draft(body.getValue("clientDraftId").jsonPrimitive.content).json().jsonObject + body)
                    response(remote!!, 201)
                }
                "updatePostDraft" -> {
                    val before = remote!!; val expected = ReviewedPostDraftAdapter(8192).expectedFields(call, before, call.ifMatch!!)
                    remote = document(expected.json().jsonObject + mapOf("updatedAt" to JsonPrimitive(TIME), "expiresAt" to JsonPrimitive("2030-01-01T00:00:00Z")))
                    response(remote!!)
                }
                "getPostDraft" -> remote?.let { response(it) } ?: problem(404, "DRAFT_UNAVAILABLE")
                "listPostDrafts" -> response(page(listOfNotNull(remote)), etag = "\"0\"")
                "deletePostDraft" -> PortResult.Value(ApiReply(204, null))
                else -> PortResult.Failure(FailureReason.NOT_CONFIGURED)
            }
            if (key != null) exactReplies[key] = result
            return result
        }
    }
    private companion object {
        const val SOURCE = "abcdefab-1111-4111-8111-111111111111"
        fun choices() = document(mapOf("caption" to JsonPrimitive("Caption"), "altText" to JsonPrimitive(""),
            "mediaIds" to JsonArray(listOf(JsonPrimitive(number(21)), JsonPrimitive(number(20)))), "audience" to buildJsonObject {
                put("kind", "circles"); put("circleIds", JsonArray(listOf(JsonPrimitive(number(31)), JsonPrimitive(number(30))))) },
            "keepOnPlate" to JsonPrimitive(true), "allowRecipeSaves" to JsonPrimitive(true),
            "saveDisclosureVersion" to JsonPrimitive("historical-v1"), "sourcePostId" to JsonPrimitive(SOURCE.uppercase()),
            "attachment" to buildJsonObject { put("recipeVersionId", SOURCE.uppercase()); put("reviewStatus", "reviewed")
                put("rightsBasis", "catalogRedistributable"); put("confirmedChanges", JsonArray(listOf(JsonPrimitive("One"), JsonPrimitive("Two")))) }))
    }
}
