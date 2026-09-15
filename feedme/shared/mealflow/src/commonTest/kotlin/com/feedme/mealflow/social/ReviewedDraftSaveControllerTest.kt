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
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.failure
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.number
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.policy
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.response
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.value
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.coroutines.CoroutineContext

/** Public reviewed-Save controller over the actual composition/journal/queue. All retained
 * principal, policy, transport and CAS-storage integrations are explicitly SYNTHETIC. These
 * tests do not enable migration, provider/native mapping, publication or a production factory. */
@OptIn(ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)
class ReviewedDraftSaveControllerTest {
    @Test fun reviewIsReadOnlyAndDisplaysFullPinnedPatchResultAndActualDisclosure() = runTest { fixture { f ->
        val before = f.store.records.getValue(KEY); val writes = f.store.writes
        val review = f.review()
        assertSame(review, f.controller.states.value.reviewedSave)
        assertEquals("Reviewed", postString(review.snapshot.exactProposedPatch, "caption"))
        assertEquals("", postAlt(review.snapshot.exactProposedPatch)); assertEquals("Before", postString(review.snapshot.baseline, "caption"))
        assertEquals("2", review.snapshot.expectedSuccessorVersion.decimal)
        assertEquals(f.ports.actualDisclosure.version, review.snapshot.displayedDisclosure!!.version)
        assertEquals(f.ports.actualDisclosure.text, review.snapshot.displayedDisclosure!!.text)
        assertTrue(review.snapshot.expectedContent.field("updatedAt") is WireField.Missing)
        assertTrue(review.snapshot.expectedContent.field("expiresAt") is WireField.Missing)
        assertContentEquals(f.local.exactUtf8.copyForCodec(), f.current().locals.single().exactUtf8.copyForCodec())
        assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes); assertEquals(0, f.ids); assertTrue(f.calls.isEmpty())
        assertFalse(f.controller.states.value.serverAcknowledged)
    } }

    @Test fun confirmUsesActualFullPatchAndQueueReceiptWithoutDroppingComposerChoices() = runTest { fixture { f ->
        val rawLocal = f.current().locals.single(); val review = f.review()
        val saved = f.controller.confirmReviewedSave(review.token).value()
        assertTrue(saved.serverAcknowledged); assertEquals(1, f.ids); assertEquals(1, f.calls.size)
        val call = f.calls.single(); assertEquals("updatePostDraft", call.operationId); assertEquals("\"1\"", call.ifMatch)
        assertEquals(SERVER, call.pathParameters["draftId"])
        assertContentEquals(review.snapshot.exactProposedPatch.encodeUtf8(), call.body!!.copyForCodec())
        val original = assertIs<PostDraftCompletionV2.ReviewedApplied>(f.current().completion).original
        assertTrue(postSameCall(original.historicalCall(), call)); assertEquals(3L, original.localRevision)
        val retained = assertIs<DraftLocalContentV1.ComposerV2>(f.current().locals.single().content)
        assertContentEquals(assertIs<DraftLocalContentV1.ComposerV2>(rawLocal.content).exactChoices.encodeUtf8(), retained.exactChoices.encodeUtf8())
        assertEquals(f.ports.actualDisclosure.text, retained.historicalDisclosureText)
        assertEquals("2", postVersion(saved.selected!!.server!!.document)); assertNull(saved.reviewedSave)
        assertTrue(PostDraftCurrentHeld.apply(f.access, f.boundary)!!.delivery!!.delivered(PostDraftCurrentHeld.apply(f.access, f.boundary)!!))
    } }

    @Test fun consumedOrForgedReviewCannotAllocateAnotherOriginal() = runTest { fixture { f ->
        failure(f.controller.confirmReviewedSave(PreparedReviewedDraftSave()), FailureReason.CONFLICT)
        val review = f.review(); f.controller.confirmReviewedSave(review.token).value()
        val writes = f.store.writes
        failure(f.controller.confirmReviewedSave(review.token), FailureReason.CONFLICT)
        assertEquals(1, f.ids); assertEquals(1, f.calls.size); assertEquals(writes, f.store.writes)
    } }

    @Test fun exhaustedPermanentIdCapacityRefusesReviewBeforeAnyIdWriteOrTransport() = runTest { fixture { f ->
        val prior = f.store.records.getValue(KEY)
        f.store.records[KEY] = PrivateRecord(prior.revision, 2, f.codec.encode(f.current().copy(
            issued = listOf(CLIENT) + (10..72).map(::number))))
        val full = f.store.records.getValue(KEY)
        failure(f.controller.prepareReviewedSave(f.target(), f.patch()), FailureReason.UNAVAILABLE)
        assertEquals(0, f.ids); assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty())
        assertTrue(postSame(full, f.store.records[KEY])); assertEquals(64, f.current().issued.size)
    } }

    @Test fun validEditOrBackRevokesPreviouslyDisplayedReviewBeforeIdsOrTransport() = runTest {
        for (edit in listOf(false, true)) fixture { f ->
            val review = f.review()
            if (edit) f.controller.editCaption(CLIENT, "Newer retained text").value() else f.controller.back().value()
            failure(f.controller.confirmReviewedSave(review.token), FailureReason.CONFLICT)
            assertEquals(0, f.ids); assertTrue(f.calls.isEmpty()); assertNull(f.controller.states.value.reviewedSave)
            if (edit) assertEquals("Newer retained text", f.current().locals.single().content.caption)
        }
    }

    @Test fun actualDisclosureMustMatchBothVersionAndTextAndCanBecomeUnavailable() = runTest {
        for (mode in 0..2) fixture { f ->
            val supplied = f.patch()
            if (mode == 0) f.ports.actualDisclosure = PublicationDisclosure("different-version", f.ports.actualDisclosure.text)
            if (mode == 1) f.ports.actualDisclosure = PublicationDisclosure(f.ports.actualDisclosure.version, "Different actual text")
            if (mode == 2) f.ports.denyDisclosure = FailureReason.NOT_CONFIGURED
            failure(f.controller.prepareReviewedSave(f.target(), supplied), if (mode == 2) FailureReason.NOT_CONFIGURED else FailureReason.CONFLICT)
            assertEquals(0, f.ids); assertTrue(f.calls.isEmpty()); assertEquals(0, f.store.writes); assertNull(f.controller.states.value.reviewedSave)
        }
    }

    @Test fun expiryAndCurrentMappingRevocationBlockPreviouslyReviewedOriginalWithoutReplacement() = runTest {
        for (expired in listOf(false, true)) fixture { f ->
            val review = f.review()
            if (expired) f.time = review.snapshot.reviewExpiresAtMillis + 1 else f.ports.revokeBeforeOwnerChange()
            assertIs<PortResult.Failure>(f.controller.confirmReviewedSave(review.token))
            assertEquals(0, f.ids); assertTrue(f.calls.isEmpty()); assertEquals(0, f.store.writes)
        }
    }

    @Test fun suspensionThenBackDuringRequiredNewSaveCheckCannotPublishReviewOrSubmit() = runTest { fixture { f ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.ports.beforeNew = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
        val task = async { f.controller.prepareReviewedSave(f.target(), f.patch()) }
        try {
            entered.await(); f.controller.back().value(); release.complete(Unit)
            failure(task.await(), FailureReason.STALE_SESSION)
            assertNull(f.controller.states.value.reviewedSave); assertEquals(0, f.ids); assertTrue(f.calls.isEmpty()); assertEquals(0, f.store.writes)
        } finally { release.complete(Unit); withContext(NonCancellable) { task.cancelAndJoin() } }
    } }

    @Test fun cancelledNoncooperativeReviewCheckNeverReturnsReviewOrEnqueues() = runTest { fixture { f ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var returned = false
        f.ports.beforeNew = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
        val task = async { f.controller.prepareReviewedSave(f.target(), f.patch()); returned = true }
        try {
            entered.await(); task.cancel(); release.complete(Unit); task.join()
            assertFalse(returned); assertTrue(task.isCancelled); assertNull(f.controller.states.value.reviewedSave)
            assertEquals(0, f.ids); assertTrue(f.calls.isEmpty()); assertEquals(0, f.store.writes)
        } finally { release.complete(Unit); withContext(NonCancellable) { task.cancelAndJoin() } }
    } }

    @Test fun principalRevocationAtArmedReviewTailNeverLeaksEvenTransientReviewDisplay() = runTest { fixture { f ->
        val paused = PausedCaller(this); var shown = false
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.controller.states.collect { if (it.reviewedSave != null) shown = true }
        }
        val task = async(paused.dispatcher) { paused.hold = true; f.controller.prepareReviewedSave(f.target(), f.patch()) }
        try {
            runCurrent(); paused.releaseOne(); runCurrent(); assertEquals(1, paused.pending.size)
            assertFalse(shown); f.ports.revokeBeforeOwnerChange(); paused.releaseAll()
            failure(task.await(), FailureReason.STALE_SESSION); assertFalse(shown); assertNull(f.controller.states.value.reviewedSave)
            assertEquals(0, f.ids); assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty())
        } finally { paused.releaseAll(); withContext(NonCancellable) { task.cancelAndJoin(); collector.cancelAndJoin() } }
    } }

    @Test fun principalRevocationAtArmedSaveTailPreservesActualApplyForFreshExplicitLocalRecovery() = runTest { fixture { f ->
        val review = f.review(); val paused = PausedCaller(this); var acknowledged = false
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.controller.states.collect { if (it.serverAcknowledged) acknowledged = true }
        }
        val task = async(paused.dispatcher) { paused.hold = true; f.controller.confirmReviewedSave(review.token) }
        try {
            runCurrent(); paused.releaseOne(); runCurrent(); assertEquals(1, paused.pending.size)
            val proof = assertNotNull(PostDraftCurrentHeld.apply(f.access, f.boundary))
            assertNotNull(proof.delivery); assertFalse(proof.delivery!!.delivered(proof)); assertFalse(acknowledged)
            f.ports.revokeBeforeOwnerChange(); paused.releaseAll()
            failure(task.await(), FailureReason.STALE_SESSION); assertFalse(acknowledged); assertFalse(proof.delivery!!.delivered(proof))
            val retry = f.controller.prepareReviewedOriginalRetry().value()
            assertEquals(ReviewedDraftRetryKind.LOCAL_RECEIPT_RECONCILIATION, retry.snapshot.kind)
            assertTrue(f.controller.confirmReviewedOriginalRetry(retry.token).value().serverAcknowledged)
            assertTrue(acknowledged); assertEquals(1, f.ids); assertEquals(1, f.calls.size)
        } finally { paused.releaseAll(); withContext(NonCancellable) { task.cancelAndJoin(); collector.cancelAndJoin() } }
    } }

    @Test fun lostReplyThenMatchingGetUsesExplicitAttemptedReplayNotNewSaveOrNewKey() = runTest { fixture { f ->
        val review = f.review(); var drop = true
        f.handler = { call -> val reply = f.reply(call); if (call.operationId == "updatePostDraft" && drop) {
            drop = false; PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
        } else reply }
        assertFalse(f.controller.confirmReviewedSave(review.token).value().serverAcknowledged)
        val original = assertIs<PostDraftCommandV2.ReviewedPatch>(f.current().command)
        assertTrue(f.controller.states.value.pending!!.requiresReviewedRetry)
        failure(f.controller.retryOriginal(), FailureReason.NOT_CONFIGURED)
        val observed = f.controller.refreshRemote(SERVER).value(); assertFalse(observed.serverAcknowledged)
        assertTrue(postSameCall(original.historicalCall(), f.current().command!!.historicalCall()))
        f.ports.denyNew = FailureReason.FORBIDDEN; f.time += 60_000
        val retry = f.controller.prepareReviewedOriginalRetry().value()
        assertEquals(ReviewedDraftRetryKind.ATTEMPTED_ORIGINAL_REPLAY, retry.snapshot.kind)
        assertEquals("\"1\"", retry.snapshot.original.originalETag); assertEquals("\"2\"", retry.snapshot.currentSavedDraft!!.etag)
        assertContentEquals(original.original.body.copyForCodec(), retry.snapshot.original.exactPatch.encodeUtf8())
        val recovered = f.controller.confirmReviewedOriginalRetry(retry.token).value()
        assertTrue(recovered.serverAcknowledged); assertEquals(1, f.ids); assertTrue(f.ports.replayCalls > 0)
        val sent = f.calls.filter { it.operationId == "updatePostDraft" }; assertEquals(2, sent.size); assertTrue(postSameCall(sent[0], sent[1]))
    } }

    @Test fun unknownEnqueueRequiresFreshFirstDispatchReviewAndNeverTreatsHistoryAsConsent() = runTest { fixture { f ->
        val review = f.review()
        f.store.failAfter = { changes -> changes.any { it.key == KEY && it is StoreMutation.Put &&
            f.codec.decode(2, it.payload).command is PostDraftCommandV2.ReviewedPatch } }
        failure(f.controller.confirmReviewedSave(review.token), FailureReason.OUTCOME_UNKNOWN)
        val original = assertIs<PostDraftCommandV2.ReviewedPatch>(f.current().command)
        assertEquals(1, f.ids); assertTrue(f.calls.isEmpty())
        failure(f.controller.confirmReviewedSave(review.token), FailureReason.CONFLICT)
        failure(f.controller.retryOriginal(), FailureReason.NOT_CONFIGURED)
        f.ports.denyNew = FailureReason.FORBIDDEN
        failure(f.controller.prepareReviewedOriginalRetry(), FailureReason.FORBIDDEN)
        assertTrue(f.calls.isEmpty()); assertEquals(1, f.ids)
        assertTrue(postSameCall(original.historicalCall(), f.current().command!!.historicalCall()))
        f.ports.denyNew = null
        val retry = f.controller.prepareReviewedOriginalRetry().value()
        assertEquals(ReviewedDraftRetryKind.FIRST_DISPATCH_REVIEW, retry.snapshot.kind); assertEquals(0, retry.snapshot.observedAttempts)
        assertTrue(f.controller.confirmReviewedOriginalRetry(retry.token).value().serverAcknowledged)
        assertEquals(1, f.ids); assertEquals(1, f.calls.size); assertEquals(0, f.ports.replayCalls)
        assertTrue(postSameCall(original.historicalCall(), f.calls.single()))
    } }

    @Test fun reviewedLocalFinalizationRejectsMissingActualArchiveWithoutTransportOrAck() = runTest { fixture { f ->
        val review = f.review()
        f.store.failAfter = { changes -> changes.any { it.key == KEY && it is StoreMutation.Put &&
            f.codec.decode(2, it.payload).completion is PostDraftCompletionV2.ReviewedApplied } }
        assertIs<PortResult.Failure>(f.controller.confirmReviewedSave(review.token))
        val proof = assertNotNull(PostDraftCurrentHeld.apply(f.access, f.boundary)); proof.archive = null
        val retry = f.controller.prepareReviewedOriginalRetry().value(); val writes = f.store.writes
        failure(f.controller.confirmReviewedOriginalRetry(retry.token), FailureReason.CONFLICT)
        assertFalse(f.controller.states.value.serverAcknowledged); assertEquals(writes, f.store.writes)
        assertEquals(1, f.ids); assertEquals(1, f.calls.size); assertFalse(proof.delivery?.delivered(proof) == true)
    } }

    @Test fun lostApplyAckUsesActualSameLeaseProofAndNoSecondTransport() = runTest { fixture { f ->
        val review = f.review()
        f.store.failAfter = { changes -> changes.any { it.key == KEY && it is StoreMutation.Put &&
            f.codec.decode(2, it.payload).completion is PostDraftCompletionV2.ReviewedApplied } }
        assertIs<PortResult.Failure>(f.controller.confirmReviewedSave(review.token)); assertFalse(f.controller.states.value.serverAcknowledged)
        val retry = f.controller.prepareReviewedOriginalRetry().value()
        assertEquals(ReviewedDraftRetryKind.LOCAL_RECEIPT_RECONCILIATION, retry.snapshot.kind)
        val calls = f.calls.size; val before = f.store.records.getValue(KEY).revision
        assertTrue(f.controller.confirmReviewedOriginalRetry(retry.token).value().serverAcknowledged)
        assertEquals(calls, f.calls.size); assertEquals(1, f.ids); assertTrue(f.store.records.getValue(KEY).revision > before)
    } }

    @Test fun malformedActualReceiptNeverBecomesTextErrorAckOrReplacementOriginal() = runTest { fixture { f ->
        val review = f.review()
        f.handler = { PortResult.Value(ApiReply(200, PrivateBytes("{}".encodeToByteArray()), "\"2\"", contentType = "application/json")) }
        f.controller.confirmReviewedSave(review.token)
        assertFalse(f.controller.states.value.serverAcknowledged); assertNotEquals(PostDraftIssue.INVALID_INPUT, f.controller.states.value.issue)
        val original = assertIs<PostDraftCommandV2.ReviewedPatch>(f.current().command)
        assertContentEquals(review.snapshot.exactProposedPatch.encodeUtf8(), original.original.body.copyForCodec())
        assertEquals(1, f.ids); assertEquals(1, f.calls.size)
    } }

    @Test fun unconfiguredReviewedSaveDoesNotPreventOrdinaryPrivateTextOrInterpretLegacyAsCurrent() = runTest {
        for (legacy in listOf(false, true)) {
            val f = Fixture(this, configure = false, legacy = legacy)
            try {
                f.open()
                failure(f.controller.prepareReviewedSave(f.target(), f.patch()), FailureReason.NOT_CONFIGURED)
                assertEquals(0, f.ids); assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty())
                assertTrue(f.controller.editCaption(CLIENT, "Still privately editable").value().selected!!.localAcknowledged)
                assertEquals(if (legacy) 1 else 2, f.store.records.getValue(KEY).schemaVersion)
            } finally { f.close() }
        }
    }

    private suspend fun TestScope.fixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture(this); try { f.open(); block(f) } finally { f.close() }
    }
    @Test fun abandonedSuspendedProviderLateIdCannotEnqueueAndNeedsFreshReviewAfterSettling() = runTest { fixture { f ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val review = f.review()
        f.idProvider = { f.ids++; entered.complete(Unit); withContext(NonCancellable) { release.await() }; number(101) }
        val task = async { f.controller.confirmReviewedSave(review.token) }
        try {
            entered.await(); val pending = assertNotNull(f.controller.states.value.reviewedAllocation)
            assertEquals(ReviewedDraftAllocationPhase.ID_PROVIDER_UNRESOLVED, pending.phase); assertNull(pending.returnedCommandId)
            val result = f.controller.abandonUnreturnedReviewedAllocation(assertNotNull(pending.abandonToken)).value()
            assertEquals(ReviewedDraftAllocationPhase.ABANDONED_WAITING_FOR_PROVIDER, result.reviewedAllocation!!.phase)
            assertNull(result.reviewedAllocation!!.abandonToken); assertEquals(1, f.ids)
            release.complete(Unit); assertIs<PortResult.Failure>(task.await())
            assertNull(ReviewedDraftSaveHeld.pending(f.access, f.boundary)); assertEquals(listOf(CLIENT), f.current().issued)
            assertNull(f.current().command); assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty())
            failure(f.controller.confirmReviewedSave(review.token), FailureReason.CONFLICT)
            f.idProvider = { number(100 + ++f.ids) }
            val fresh = f.review(); assertTrue(f.controller.confirmReviewedSave(fresh.token).value().serverAcknowledged)
            assertEquals(2, f.ids); assertEquals(number(102), f.calls.single().idempotencyKey!!.use { it })
        } finally { release.complete(Unit); withContext(NonCancellable) { task.cancelAndJoin() } }
    } }

    @Test fun inlineProgressAbandonBeforeProviderInvocationAllocatesNoIdAndSettlesSlot() = runTest {
        // Bounded same-thread reentrancy driver, not a production dispatcher. Always
        // dispatch directly so a real StateFlow collector runs inside the progress emission;
        // an Unconfined event loop can queue/conflate this exact before-provider interleaving.
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { block.run() }
        }
        val f = Fixture(this, identityDispatcher = dispatcher)
        var abandonedInline = false
        val collector = backgroundScope.launch(f.dispatcher) {
            f.controller.states.collect { state ->
                val token = state.reviewedAllocation?.abandonToken
                if (token != null && !abandonedInline) {
                    abandonedInline = true
                    assertEquals(0, f.ids)
                    f.controller.abandonUnreturnedReviewedAllocation(token).value()
                    assertEquals(0, f.ids)
                }
            }
        }
        try {
            f.open(); val review = f.review()
            failure(f.controller.confirmReviewedSave(review.token), FailureReason.STALE_SESSION)
            assertTrue(abandonedInline); assertEquals(0, f.ids); assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty())
            assertNull(ReviewedDraftSaveHeld.pending(f.access, f.boundary))
            assertFalse(ComposerAllocationArbiter.hasReviewedSave(f.access, f.boundary, CLIENT))
            assertNull(f.current().command); assertEquals(listOf(CLIENT), f.current().issued)
            failure(f.controller.confirmReviewedSave(review.token), FailureReason.CONFLICT)
        } finally { collector.cancelAndJoin(); f.close() }
    }

    @Test fun capturedIdWinsAgainstAbandonAndRetainsOriginalBodyKeyTimeAcrossReplacement() = runTest { fixture { f ->
        var abandon: PreparedReviewedDraftAllocationAbandon? = null; val review = f.review()
        f.idProvider = { abandon = f.controller.states.value.reviewedAllocation!!.abandonToken; number(100 + ++f.ids) }
        f.ports.beforeNew = { if (f.ids > 0) f.ports.denyNew = FailureReason.FORBIDDEN }
        failure(f.controller.confirmReviewedSave(review.token), FailureReason.FORBIDDEN)
        val original = assertNotNull(ReviewedDraftSaveHeld.pending(f.access, f.boundary)!!.original)
        val bytes = original.original.body.copyForCodec(); val created = original.created
        failure(f.controller.abandonUnreturnedReviewedAllocation(assertNotNull(abandon)), FailureReason.CONFLICT)
        assertEquals(1, f.ids); assertTrue(f.calls.isEmpty()); assertEquals(0, f.store.writes)
        f.ports.beforeNew = {}; f.ports.denyNew = null; f.reopen()
        val retry = f.controller.prepareReviewedOriginalRetry().value()
        assertEquals(ReviewedDraftRetryKind.REGISTRATION_RECONCILIATION, retry.snapshot.kind)
        assertEquals(created, retry.snapshot.original.originalCreatedAtMillis)
        assertContentEquals(bytes, retry.snapshot.original.exactPatch.encodeUtf8())
        assertTrue(f.controller.confirmReviewedOriginalRetry(retry.token).value().serverAcknowledged)
        assertEquals(1, f.ids); assertTrue(postSameCall(original.historicalCall(), f.calls.single()))
    } }

    @Test fun abandonedOldProviderCannotReviveAfterControllerReplacement() = runTest { fixture { f ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val review = f.review()
        f.idProvider = { f.ids++; entered.complete(Unit); withContext(NonCancellable) { release.await() }; number(101) }
        val previous = f.controller; val task = async { previous.confirmReviewedSave(review.token) }
        try {
            entered.await(); val token = previous.states.value.reviewedAllocation!!.abandonToken!!
            previous.abandonUnreturnedReviewedAllocation(token).value(); f.replaceWithoutOpening()
            assertNull(f.controller.states.value.reviewedSave)
            release.complete(Unit); assertIs<PortResult.Failure>(task.await()); f.open()
            assertNull(f.controller.states.value.reviewedAllocation); assertNull(f.current().command)
            assertEquals(listOf(CLIENT), f.current().issued); assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty())
            failure(f.controller.abandonUnreturnedReviewedAllocation(token), FailureReason.CONFLICT)
            assertEquals(1, f.ids)
        } finally { release.complete(Unit); withContext(NonCancellable) { task.cancelAndJoin() } }
    } }

    @Test fun settledProviderFailureNeverInventsKeyAndExplicitAbandonPermitsFreshReview() = runTest {
        for (cancelled in listOf(false, true)) fixture { f ->
            val review = f.review()
            f.idProvider = { f.ids++; if (cancelled) throw CancellationException("synthetic ID source cancelled")
                else throw IllegalStateException("synthetic unavailable ID source") }
            if (cancelled) assertFailsWith<CancellationException> { f.controller.confirmReviewedSave(review.token) }
            else assertIs<PortResult.Failure>(f.controller.confirmReviewedSave(review.token))
            val pending = f.controller.states.value.reviewedAllocation!!
            assertEquals(ReviewedDraftAllocationPhase.ID_PROVIDER_UNRESOLVED, pending.phase); assertNull(pending.returnedCommandId)
            failure(f.controller.prepareReviewedOriginalRetry(), FailureReason.CONFLICT)
            f.controller.abandonUnreturnedReviewedAllocation(pending.abandonToken!!).value()
            assertNull(ReviewedDraftSaveHeld.pending(f.access, f.boundary)); assertTrue(f.calls.isEmpty()); assertEquals(0, f.store.writes)
            f.idProvider = { number(100 + ++f.ids) }
            val fresh = f.review(); assertTrue(f.controller.confirmReviewedSave(fresh.token).value().serverAcknowledged)
            assertEquals(2, f.ids); assertEquals(1, f.calls.size)
        }
    }

    @Test fun repeatedAbandonKeepsOneSlotUntilNoncooperativeInvocationSettles() = runTest { fixture { f ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val review = f.review()
        f.idProvider = { f.ids++; entered.complete(Unit); withContext(NonCancellable) { release.await() }; number(101) }
        val task = async { f.controller.confirmReviewedSave(review.token) }
        try {
            entered.await(); val token = f.controller.states.value.reviewedAllocation!!.abandonToken!!
            f.controller.abandonUnreturnedReviewedAllocation(token).value()
            failure(f.controller.abandonUnreturnedReviewedAllocation(token), FailureReason.CONFLICT)
            val reservation = assertNotNull(ReviewedDraftSaveHeld.pending(f.access, f.boundary)); assertTrue(reservation.abandoned)
            val waiting = async { f.review() }
            try { runCurrent(); assertFalse(waiting.isCompleted); assertEquals(1, f.ids); assertSame(reservation, ReviewedDraftSaveHeld.pending(f.access, f.boundary)) }
            finally { waiting.cancel(); release.complete(Unit); withContext(NonCancellable) { task.join(); waiting.join() } }
            assertEquals(1, f.ids); assertNull(f.current().command); assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty())
        } finally { release.complete(Unit); withContext(NonCancellable) { task.cancelAndJoin() } }
    } }

    @Test fun beforeAndAfterCommitUnknownNeverAllowAbandonOrReplacementKey() = runTest {
        for (after in listOf(false, true)) fixture { f ->
            var token: PreparedReviewedDraftAllocationAbandon? = null; val review = f.review()
            f.idProvider = { token = f.controller.states.value.reviewedAllocation!!.abandonToken; number(100 + ++f.ids) }
            if (after) f.store.failAfter = { changes -> changes.any { it.key == KEY && it is StoreMutation.Put &&
                f.codec.decode(2, it.payload).command is PostDraftCommandV2.ReviewedPatch } }
            else f.beforeCommitFailure = FailureReason.OUTCOME_UNKNOWN
            failure(f.controller.confirmReviewedSave(review.token), FailureReason.OUTCOME_UNKNOWN)
            val retained = assertNotNull(ReviewedDraftSaveHeld.pending(f.access, f.boundary)!!.original)
            assertEquals(after, f.current().command != null); assertEquals(1, f.ids); assertTrue(f.calls.isEmpty())
            failure(f.controller.abandonUnreturnedReviewedAllocation(token!!), FailureReason.CONFLICT)
            failure(f.controller.confirmReviewedSave(review.token), FailureReason.CONFLICT)
            val retry = f.controller.prepareReviewedOriginalRetry().value()
            assertEquals(if (after) ReviewedDraftRetryKind.FIRST_DISPATCH_REVIEW else ReviewedDraftRetryKind.REGISTRATION_RECONCILIATION, retry.snapshot.kind)
            assertTrue(f.controller.confirmReviewedOriginalRetry(retry.token).value().serverAcknowledged)
            assertEquals(1, f.ids); assertEquals(1, f.calls.size); assertTrue(postSameCall(retained.historicalCall(), f.calls.single()))
        }
    }

    @Test fun forgedForeignAndInvalidatedAllocationTokensCannotAbandonCurrentReservation() = runTest {
        fixture { first -> fixture { second ->
            for (f in listOf(first, second)) {
                f.idProvider = { f.ids++; throw IllegalStateException("synthetic source failed") }
                assertIs<PortResult.Failure>(f.controller.confirmReviewedSave(f.review().token))
            }
            val token = first.controller.states.value.reviewedAllocation!!.abandonToken!!
            val other = ReviewedDraftSaveHeld.pending(second.access, second.boundary)
            failure(second.controller.abandonUnreturnedReviewedAllocation(token), FailureReason.CONFLICT)
            failure(second.controller.abandonUnreturnedReviewedAllocation(PreparedReviewedDraftAllocationAbandon()), FailureReason.CONFLICT)
            assertSame(other, ReviewedDraftSaveHeld.pending(second.access, second.boundary))
            first.boundary.clear()
            failure(first.controller.abandonUnreturnedReviewedAllocation(token), FailureReason.STALE_SESSION)
            assertEquals(1, first.ids); assertEquals(1, second.ids); assertEquals(0, first.store.writes); assertEquals(0, second.store.writes)
        } }
    }

    @Test fun backAndRestoreKeepUnreturnedReservationAndNeverAllocateOrAbandon() = runTest { fixture { f ->
        f.idProvider = { f.ids++; throw IllegalStateException("synthetic source failed") }
        assertIs<PortResult.Failure>(f.controller.confirmReviewedSave(f.review().token))
        val held = assertNotNull(ReviewedDraftSaveHeld.pending(f.access, f.boundary))
        f.controller.back().value(); val restored = f.controller.restoreLocal().value()
        assertSame(held, ReviewedDraftSaveHeld.pending(f.access, f.boundary)); assertFalse(held.abandoned)
        assertEquals(ReviewedDraftAllocationPhase.ID_PROVIDER_UNRESOLVED, restored.reviewedAllocation!!.phase)
        assertEquals(1, f.ids); assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty()); assertNull(f.current().command)
    } }

    @Test fun actualCallerCancellationAfterIdCaptureKeepsOriginalForExplicitRegistrationRecovery() = runTest { fixture { f ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val review = f.review()
        f.ports.beforeNew = { if (f.ids > 0) { entered.complete(Unit); withContext(NonCancellable) { release.await() } } }
        var returned = false; val task = async { f.controller.confirmReviewedSave(review.token); returned = true }
        try {
            entered.await(); val original = assertNotNull(ReviewedDraftSaveHeld.pending(f.access, f.boundary)!!.original)
            task.cancel(); release.complete(Unit); task.join()
            assertFalse(returned); assertTrue(task.isCancelled); assertEquals(1, f.ids); assertTrue(f.calls.isEmpty()); assertEquals(0, f.store.writes)
            assertSame(original, ReviewedDraftSaveHeld.pending(f.access, f.boundary)!!.original)
            f.ports.beforeNew = {}; f.reopen()
            val retry = f.controller.prepareReviewedOriginalRetry().value()
            assertEquals(ReviewedDraftRetryKind.REGISTRATION_RECONCILIATION, retry.snapshot.kind)
            assertTrue(f.controller.confirmReviewedOriginalRetry(retry.token).value().serverAcknowledged)
            assertEquals(1, f.ids); assertTrue(postSameCall(original.historicalCall(), f.calls.single()))
        } finally { release.complete(Unit); withContext(NonCancellable) { task.cancelAndJoin() } }
    } }

    @Test fun capturedUnregisteredOriginalReservesCapacityWhileNewerFullLocalTextRemainsEditable() = runTest { fixture { f ->
        val review = f.review(); f.beforeCommitFailure = FailureReason.OUTCOME_UNKNOWN
        failure(f.controller.confirmReviewedSave(review.token), FailureReason.OUTCOME_UNKNOWN)
        val original = ReviewedDraftSaveHeld.pending(f.access, f.boundary)!!.original!!
        val oldChoices = assertIs<DraftLocalContentV1.ComposerV2>(f.current().locals.single().content).exactChoices.json().jsonObject
        val changed = f.controller.editCaption(CLIENT, "Newer not submitted caption").value()
        assertTrue(changed.selected!!.localAcknowledged); assertEquals(4L, changed.selected!!.localRevision)
        assertNull(f.current().command); assertEquals(listOf(CLIENT), f.current().issued)
        val newer = f.current().locals.single(); val newerChoices = assertIs<DraftLocalContentV1.ComposerV2>(newer.content).exactChoices.json().jsonObject
        assertEquals(oldChoices.filterKeys { it != "caption" }, newerChoices.filterKeys { it != "caption" })
        assertSame(original, ReviewedDraftSaveHeld.pending(f.access, f.boundary)!!.original); assertEquals(1, f.ids)
        val retry = f.controller.prepareReviewedOriginalRetry().value()
        assertEquals("Reviewed", postString(retry.snapshot.original.exactPatch, "caption"))
        assertEquals("Newer not submitted caption", postString(retry.snapshot.currentLocalChoices, "caption"))
        val saved = f.controller.confirmReviewedOriginalRetry(retry.token).value()
        assertTrue(saved.serverAcknowledged); assertEquals("Newer not submitted caption", saved.selected!!.caption)
        assertContentEquals(newer.content.let { assertIs<DraftLocalContentV1.ComposerV2>(it).exactChoices.encodeUtf8() },
            assertIs<DraftLocalContentV1.ComposerV2>(f.current().locals.single().content).exactChoices.encodeUtf8())
        assertEquals(1, f.ids); assertTrue(postSameCall(original.historicalCall(), f.calls.single()))
    } }

    @Test fun staleAbandonTokenCannotAffectANewSameRootReservation() = runTest { fixture { f ->
        f.idProvider = { f.ids++; throw IllegalStateException("synthetic unreturned ID") }
        assertIs<PortResult.Failure>(f.controller.confirmReviewedSave(f.review().token))
        val stale = f.controller.states.value.reviewedAllocation!!.abandonToken!!
        f.controller.abandonUnreturnedReviewedAllocation(stale).value()
        assertIs<PortResult.Failure>(f.controller.confirmReviewedSave(f.review().token))
        val actual = ReviewedDraftSaveHeld.pending(f.access, f.boundary)!!
        failure(f.controller.abandonUnreturnedReviewedAllocation(stale), FailureReason.CONFLICT)
        assertSame(actual, ReviewedDraftSaveHeld.pending(f.access, f.boundary)); assertTrue(actual.awaiting)
        assertEquals(2, f.ids); assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty())
    } }

    @Test fun sharedSameRootPublicationSlotBlocksReviewedSaveBeforeIdAllocation() = runTest { fixture { f ->
        val review = f.review()
        val slot = ComposerAllocationArbiter.reservePublication(f.access, f.boundary, CLIENT)
        try {
            failure(f.controller.confirmReviewedSave(review.token), FailureReason.CONFLICT)
            failure(f.controller.prepareReviewedSave(f.target(), f.patch()), FailureReason.CONFLICT)
            assertEquals(0, f.ids); assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty())
            assertNull(ReviewedDraftSaveHeld.pending(f.access, f.boundary))
        } finally { ComposerAllocationArbiter.release(slot) }
        assertTrue(f.controller.confirmReviewedSave(f.review().token).value().serverAcknowledged)
        assertEquals(1, f.ids)
    } }

    @Test fun sameRootPublicationSlotBlocksOrdinaryTextSaveBeforeIdInBothFormatsButNotUnrelatedRoot() = runTest {
        for (legacy in listOf(false, true)) {
            val f = Fixture(this, legacy = legacy)
            try {
                f.seedPlain(associated = true); f.open()
                val before = f.store.records.getValue(KEY)
                val slot = ComposerAllocationArbiter.reservePublication(f.access, f.boundary, CLIENT)
                try {
                    failure(f.controller.saveExplicitly(), FailureReason.CONFLICT)
                    assertEquals(0, f.ids); assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty())
                    assertTrue(postSame(before, f.store.records[KEY])); assertFalse(f.controller.states.value.serverAcknowledged)
                } finally { ComposerAllocationArbiter.release(slot) }
                val unrelated = ComposerAllocationArbiter.reservePublication(f.access, f.boundary, number(333))
                try {
                    assertTrue(f.controller.saveExplicitly().value().serverAcknowledged)
                    assertEquals(1, f.ids); assertEquals(1, f.calls.size)
                    assertTrue(ComposerAllocationArbiter.hasPublication(f.access, f.boundary, number(333)))
                } finally { ComposerAllocationArbiter.release(unrelated) }
            } finally { f.close() }
        }
    }

    @Test fun sameRootPublicationSlotBlocksServerDiscardPreparationAndExistingConsentBeforeIdInBothFormats() = runTest {
        for (legacy in listOf(false, true)) {
            val f = Fixture(this, legacy = legacy)
            try {
                f.seedPlain(associated = true); f.open()
                val consent = f.controller.prepareServerDiscard().value().discardConfirmation!!
                val before = f.store.records.getValue(KEY)
                val slot = ComposerAllocationArbiter.reservePublication(f.access, f.boundary, CLIENT)
                try {
                    failure(f.controller.confirmServerDiscard(consent), FailureReason.CONFLICT)
                    failure(f.controller.prepareServerDiscard(), FailureReason.CONFLICT)
                    assertEquals(0, f.ids); assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty())
                    assertTrue(postSame(before, f.store.records[KEY])); assertFalse(f.controller.states.value.serverAcknowledged)
                } finally { ComposerAllocationArbiter.release(slot) }
                assertNotNull(f.controller.prepareServerDiscard().value().discardConfirmation)
                assertEquals(0, f.ids); assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty())
            } finally { f.close() }
        }
    }

    @Test fun sameRootPublicationSlotBlocksUnassociatedLocalDiscardWithoutWritesInBothFormats() = runTest {
        for (legacy in listOf(false, true)) {
            val f = Fixture(this, legacy = legacy)
            try {
                f.seedPlain(associated = false); f.open()
                val before = f.store.records.getValue(KEY)
                val slot = ComposerAllocationArbiter.reservePublication(f.access, f.boundary, CLIENT)
                try {
                    failure(f.controller.discardLocal(CLIENT, 3), FailureReason.CONFLICT)
                    assertEquals(0, f.ids); assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty())
                    assertTrue(postSame(before, f.store.records[KEY])); assertFalse(f.controller.states.value.serverAcknowledged)
                } finally { ComposerAllocationArbiter.release(slot) }
                assertEquals(PostDraftScreen.LOCAL_LIST, f.controller.discardLocal(CLIENT, 3).value().screen)
                assertEquals(0, f.ids); assertTrue(f.calls.isEmpty()); assertTrue(f.store.writes > 0)
            } finally { f.close() }
        }
    }

    @Test fun publicationSlotDenialPreservesActualDeliveredLocalMarkerAcrossEveryOrdinaryEntryInBothFormats() = runTest {
        for (legacy in listOf(false, true)) for (action in 0..3) {
            val f = Fixture(this, legacy = legacy)
            try {
                f.seedPlain(associated = action != 3); f.open()
                // Confirmation is deliberately pinned before the edit. Even a now-stale
                // actual token must not cleanup-write before denying this occupied root.
                val consent = if (action == 2) f.controller.prepareServerDiscard().value().discardConfirmation!! else null
                assertTrue(f.controller.editCaption(CLIENT, "Newer local text").value().selected!!.localAcknowledged)
                val legacyEdit = PostDraftHeld.edit(f.access, f.boundary)
                val currentEdit = PostDraftCurrentHeld.edit(f.access, f.boundary)
                val legacyDelivery = legacyEdit?.delivery; val currentDelivery = currentEdit?.delivery
                if (legacy) assertTrue(legacyDelivery!!.delivered(legacyEdit!!)) else assertTrue(currentDelivery!!.delivered(currentEdit!!))
                val before = f.store.records.getValue(KEY); val writes = f.store.writes; val ids = f.ids; val calls = f.calls.size
                val slot = ComposerAllocationArbiter.reservePublication(f.access, f.boundary, CLIENT)
                try {
                    val result = when (action) {
                        0 -> f.controller.saveExplicitly()
                        1 -> f.controller.prepareServerDiscard()
                        2 -> f.controller.confirmServerDiscard(consent!!)
                        else -> f.controller.discardLocal(CLIENT, 4)
                    }
                    failure(result, FailureReason.CONFLICT)
                    assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes)
                    assertEquals(ids, f.ids); assertEquals(calls, f.calls.size)
                    assertSame(legacyEdit, PostDraftHeld.edit(f.access, f.boundary))
                    assertSame(currentEdit, PostDraftCurrentHeld.edit(f.access, f.boundary))
                    assertSame(legacyDelivery, legacyEdit?.delivery); assertSame(currentDelivery, currentEdit?.delivery)
                    if (legacy) assertTrue(legacyDelivery!!.delivered(legacyEdit!!)) else assertTrue(currentDelivery!!.delivered(currentEdit!!))
                } finally { ComposerAllocationArbiter.release(slot) }
            } finally { f.close() }
        }
    }

    @Test fun publicationSlotDenialPreservesActualDeliveredCompletionAndArchiveIdentityInBothFormats() = runTest {
        for (legacy in listOf(false, true)) for (action in 0..2) {
            val f = Fixture(this, legacy = legacy)
            try {
                f.seedPlain(associated = true); f.open()
                val consent = if (action == 2) f.controller.prepareServerDiscard().value().discardConfirmation!! else null
                assertTrue(f.controller.saveExplicitly().value().serverAcknowledged)
                val legacyApply = PostDraftHeld.apply(f.access, f.boundary)
                val currentApply = PostDraftCurrentHeld.apply(f.access, f.boundary)
                val legacyDelivery = legacyApply?.delivery; val currentDelivery = currentApply?.delivery
                val legacyArchive = legacyApply?.archive; val currentArchive = currentApply?.archive
                if (legacy) { assertTrue(legacyDelivery!!.delivered(legacyApply!!)); assertNotNull(legacyArchive) }
                else { assertTrue(currentDelivery!!.delivered(currentApply!!)); assertNotNull(currentArchive) }
                val before = f.store.records.getValue(KEY); val writes = f.store.writes; val ids = f.ids; val calls = f.calls.size
                val slot = ComposerAllocationArbiter.reservePublication(f.access, f.boundary, CLIENT)
                try {
                    val result = when (action) {
                        0 -> f.controller.saveExplicitly()
                        1 -> f.controller.prepareServerDiscard()
                        else -> f.controller.confirmServerDiscard(consent!!)
                    }
                    failure(result, FailureReason.CONFLICT)
                    assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes)
                    assertEquals(ids, f.ids); assertEquals(calls, f.calls.size)
                    assertSame(legacyApply, PostDraftHeld.apply(f.access, f.boundary))
                    assertSame(currentApply, PostDraftCurrentHeld.apply(f.access, f.boundary))
                    assertSame(legacyDelivery, legacyApply?.delivery); assertSame(currentDelivery, currentApply?.delivery)
                    assertSame(legacyArchive, legacyApply?.archive); assertSame(currentArchive, currentApply?.archive)
                    if (legacy) assertTrue(legacyDelivery!!.delivered(legacyApply!!)) else assertTrue(currentDelivery!!.delivered(currentApply!!))
                } finally { ComposerAllocationArbiter.release(slot) }
            } finally { f.close() }
        }
    }

    @Test fun actualSaveReservationExcludesSameRootPublishButNotUnrelatedRootSlot() = runTest { fixture { f ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val review = f.review()
        f.idProvider = { f.ids++; entered.complete(Unit); withContext(NonCancellable) { release.await() }; number(101) }
        val task = async { f.controller.confirmReviewedSave(review.token) }
        var unrelated: ComposerAllocationSlot? = null
        try {
            entered.await(); assertTrue(ComposerAllocationArbiter.hasReviewedSave(f.access, f.boundary, CLIENT))
            assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> {
                ComposerAllocationArbiter.reservePublication(f.access, f.boundary, CLIENT)
            }.reason)
            unrelated = ComposerAllocationArbiter.reservePublication(f.access, f.boundary, number(333))
            assertTrue(ComposerAllocationArbiter.hasPublication(f.access, f.boundary, number(333)))
            f.controller.abandonUnreturnedReviewedAllocation(f.controller.states.value.reviewedAllocation!!.abandonToken!!).value()
            assertTrue(ComposerAllocationArbiter.hasReviewedSave(f.access, f.boundary, CLIENT))
            release.complete(Unit); assertIs<PortResult.Failure>(task.await())
            assertFalse(ComposerAllocationArbiter.hasReviewedSave(f.access, f.boundary, CLIENT))
            assertTrue(ComposerAllocationArbiter.hasPublication(f.access, f.boundary, number(333)))
            assertEquals(1, f.ids); assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty())
        } finally { release.complete(Unit); withContext(NonCancellable) { task.cancelAndJoin() }; unrelated?.let(ComposerAllocationArbiter::release) }
    } }

    @Test fun unrelatedPublicationSlotDoesNotBlockActualReviewedSaveOrGetReleasedByIt() = runTest { fixture { f ->
        val root = number(333); val slot = ComposerAllocationArbiter.reservePublication(f.access, f.boundary, root)
        try {
            assertTrue(f.controller.confirmReviewedSave(f.review().token).value().serverAcknowledged)
            assertFalse(ComposerAllocationArbiter.hasReviewedSave(f.access, f.boundary, CLIENT))
            assertTrue(ComposerAllocationArbiter.hasPublication(f.access, f.boundary, root))
            assertEquals(1, f.ids); assertEquals(1, f.calls.size)
        } finally { ComposerAllocationArbiter.release(slot) }
    } }

    @Test fun acknowledgedReviewedSaveCanImmediatelyEnterReadOnlyPublicationReviewWithoutDummyEdit() = runTest { fixture { f ->
        val saved = f.controller.confirmReviewedSave(f.review().token).value()
        assertTrue(saved.serverAcknowledged)
        val completed = assertIs<PostDraftCompletionV2.ReviewedApplied>(f.current().completion)
        val retained = f.current().locals.single(); val before = f.store.records.getValue(KEY)
        val writes = f.store.writes; val calls = f.calls.size; val ids = f.ids
        val observed = f.publicationReview(CLIENT, retained.localRevision)
        assertContentEquals(retained.exactUtf8.copyForCodec(), observed.exactUtf8.copyForCodec())
        val server = assertIs<DraftServerAssociationV1.Observed>(observed.serverAssociation)
        assertEquals("2", postVersion(server.exactPostDraft)); assertEquals("\"2\"", server.etag)
        assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes)
        assertEquals(calls, f.calls.size); assertEquals(ids, f.ids)
        val stillCompleted = assertIs<PostDraftCompletionV2.ReviewedApplied>(f.current().completion)
        assertTrue(postSameCall(completed.original.historicalCall(), stillCompleted.original.historicalCall()))
        assertTrue(saved.serverAcknowledged) // Only the earlier actual Save was acknowledged; review performs no publication.
    } }

    @Test fun changedOrDeletedDomainDuringExactQueueReadCannotRetireActualAllocationEvidence() = runTest {
        for (deleted in listOf(false, true)) fixture { f ->
            val review = f.review(); var injected = false; var held: ReviewedDraftSaveHeld.Allocation? = null
            f.store.afterRead = { key, record ->
                if (!injected && key.collection == "feedme.command.request" && record != null) {
                    injected = true; held = assertNotNull(ReviewedDraftSaveHeld.pending(f.access, f.boundary))
                    assertNotNull(held!!.original)
                    val prior = f.store.records.getValue(KEY)
                    assertIs<PostDraftCommandV2.ReviewedPatch>(f.current().command)
                    if (deleted) f.store.records.remove(KEY)
                    else f.store.records[KEY] = PrivateRecord(prior.revision + 1, 2,
                        f.codec.encode(f.current().copy(clock = f.current().clock + 1)))
                }
            }
            failure(f.controller.confirmReviewedSave(review.token), FailureReason.CONFLICT)
            assertTrue(injected); assertSame(held, ReviewedDraftSaveHeld.pending(f.access, f.boundary))
            assertTrue(ComposerAllocationArbiter.hasReviewedSave(f.access, f.boundary, CLIENT))
            assertEquals(1, f.ids); assertTrue(f.calls.isEmpty()); assertFalse(f.controller.states.value.serverAcknowledged)
            val original = held!!.original!!; assertEquals(number(101), original.id)
            assertContentEquals(review.snapshot.exactProposedPatch.encodeUtf8(), original.original.body.copyForCodec())
            if (!deleted) {
                f.store.afterRead = { _, _ -> }; f.time++
                val retry = f.controller.prepareReviewedOriginalRetry().value()
                assertTrue(f.controller.confirmReviewedOriginalRetry(retry.token).value().serverAcknowledged)
                assertEquals(1, f.ids); assertTrue(postSameCall(original.historicalCall(), f.calls.single()))
            }
        }
    }

    @Test fun attemptedNoGetReplayCanOutliveOldBaselineButFreshExpiredSaveIsDenied() = runTest {
        val expiry = "2026-09-14T00:00:02Z"
        val at = kotlin.time.Instant.parse(expiry).toEpochMilliseconds()
        fixture { f ->
            f.baselineExpiresAt(expiry); f.time = at - 1000; f.open(); val review = f.review()
            var drop = true
            f.handler = { call -> val response = f.reply(call); if (drop) { drop = false; PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) } else response }
            assertFalse(f.controller.confirmReviewedSave(review.token).value().serverAcknowledged)
            val original = assertIs<PostDraftCommandV2.ReviewedPatch>(f.current().command)
            f.time = at + 60_000; f.ports.denyNew = FailureReason.FORBIDDEN
            val retry = f.controller.prepareReviewedOriginalRetry().value()
            assertEquals(ReviewedDraftRetryKind.ATTEMPTED_ORIGINAL_REPLAY, retry.snapshot.kind)
            assertEquals("\"1\"", retry.snapshot.currentSavedDraft!!.etag)
            assertTrue(f.controller.confirmReviewedOriginalRetry(retry.token).value().serverAcknowledged)
            assertEquals(1, f.ids); assertEquals(2, f.calls.size); assertTrue(f.calls.all { it.operationId == "updatePostDraft" })
            assertTrue(f.calls.all { postSameCall(original.historicalCall(), it) })
        }
        fixture { f ->
            f.baselineExpiresAt(expiry); f.time = at + 1; f.open()
            failure(f.controller.prepareReviewedSave(f.target(), f.patch()), FailureReason.CONFLICT)
            assertEquals(0, f.ids); assertTrue(f.calls.isEmpty()); assertEquals(0, f.store.writes)
        }
    }

    @Test fun actualCallerCancellationAtArmedReviewedTailsNeverEmitsUndeliveredReviewOrAck() = runTest {
        for (save in listOf(false, true)) fixture { f ->
            val review = if (save) f.review() else null; val paused = PausedCaller(this); var emitted = false
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.controller.states.collect { if (if (save) it.serverAcknowledged else it.reviewedSave != null) emitted = true }
            }
            var returned = false
            val task = async(paused.dispatcher) { paused.hold = true
                if (save) f.controller.confirmReviewedSave(review!!.token) else f.controller.prepareReviewedSave(f.target(), f.patch())
                returned = true
            }
            try {
                runCurrent(); paused.releaseOne(); runCurrent(); assertEquals(1, paused.pending.size); assertFalse(emitted)
                task.cancel(); paused.releaseAll(); task.join()
                assertTrue(task.isCancelled); assertFalse(returned); assertFalse(emitted)
                if (save) {
                    val held = PostDraftCurrentHeld.apply(f.access, f.boundary)!!
                    assertFalse(held.delivery!!.delivered(held)); assertEquals(1, f.ids); assertEquals(1, f.calls.size)
                } else { assertEquals(0, f.ids); assertTrue(f.calls.isEmpty()); assertEquals(0, f.store.writes) }
            } finally { paused.releaseAll(); withContext(NonCancellable) { task.cancelAndJoin(); collector.cancelAndJoin() } }
        }
    }

    private class PausedCaller(test: TestScope) {
        private val scheduled = StandardTestDispatcher(test.testScheduler)
        var hold = false; val pending = ArrayDeque<Pair<CoroutineContext, Runnable>>()
        val dispatcher = object : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (hold) pending.addLast(context to block) else scheduled.dispatch(context, block)
        } }
        fun releaseOne() { assertEquals(1, pending.size); val next = pending.removeFirst(); scheduled.dispatch(next.first, next.second) }
        fun releaseAll() { hold = false; while (pending.isNotEmpty()) { val next = pending.removeFirst(); scheduled.dispatch(next.first, next.second) } }
    }
    private class Fixture(test: TestScope, configure: Boolean = true, legacy: Boolean = false,
        identityDispatcher: CoroutineDispatcher? = null) {
        val dispatcher = identityDispatcher ?: StandardTestDispatcher(test.testScheduler); val draftPolicy = policy()
        val publicationPolicy = PostPublicationClientPolicy(1_048_576, 65_536, 8192, 64, 64, 8, 32, 32, 32, 65_536, 8192, 60_000)
        val snapshots = DraftLocalSnapshotCodecV1(draftPolicy, publicationPolicy)
        val links = PublicationOriginalLinkCodecV1(publicationPolicy, snapshots)
        val scope = StorageScope("reviewed-save-test", ActorKind.ACCOUNT, "opaque synthetic actor")
        val boundary = SessionBoundary(); val lease = boundary.activate(scope); val store = PostDraftControllerTest.Store(scope)
        val codec = PostDraftV2Codec(scope.environment, ORIGIN, draftPolicy, publicationPolicy, snapshots, links)
        var time = 1_000_000L; var ids = 0; val calls = mutableListOf<ApiCall>()
        var idProvider: suspend () -> String = { number(100 + ++ids) }
        var beforeCommitFailure: FailureReason? = null
        private val actualStore = object : PrivateStateStore by store {
            override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> {
                beforeCommitFailure?.let { beforeCommitFailure = null; return PortResult.Failure(it) }
                return store.commit(scope, mutations)
            }
        }
        var remote = baseline(); private val replies = mutableMapOf<String, PortResult<ApiReply>>()
        var handler: suspend (ApiCall) -> PortResult<ApiReply> = { reply(it) }
        val access = AuthenticatedMealPlanningAccess(lease, ORIGIN, actualStore, object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                assertSame(this@Fixture.lease, lease); calls += call; return handler(call)
            }
        }, true)
        val ports = ReviewedDraftSaveTestPorts(access, boundary)
        val local = snapshots.create(CLIENT, 3, DraftLocalContentV1.ComposerV2(choices(), ports.actualDisclosure.text),
            DraftServerAssociationV1.Observed(remote, "\"1\""))
        private val configured = configure
        private lateinit var composition: MealKitchenComposition
        private lateinit var owner: PostDraftJournalOwner
        private lateinit var publicationBorrower: MealKitchenComposition.Borrower
        private lateinit var publicationParticipant: DraftJournalParticipant
        var controller = create()
        private fun create(): PostDraftController {
            composition = MealKitchenComposition(access, boundary, dispatcher, EpochClock { time }, ConnectivityPort { Connectivity.ONLINE })
            owner = PostDraftJournalOwner(composition, draftPolicy, publicationPolicy)
            val controller = PostDraftController(composition, MealOperationIds { idProvider() }, draftPolicy, owner, ports.takeIf { configured })
            // Synthetic read-only publication participant, registered before first owner use.
            // It cannot commit, dispatch or acknowledge a publication in this fixture.
            publicationBorrower = composition.bind(MealKitchenFeature.POST_PUBLICATIONS, object : MealKitchenHooks {
                override suspend fun checkCurrent() = Unit
                override fun beforeCommit(mutations: List<StoreMutation>) { error("Read-only synthetic publication fixture") }
            })
            publicationParticipant = owner.register(publicationBorrower)
            return controller
        }
        private fun releasePublication() {
            if (boundary.isCurrent(lease)) owner.release(publicationParticipant)
            composition.release(publicationBorrower)
        }
        suspend fun replaceWithoutOpening() { controller.close(); releasePublication(); controller = create() }
        suspend fun reopen() { replaceWithoutOpening(); open() }
        suspend fun publicationReview(root: String, revision: Long): DraftLocalSnapshotV1 = withContext(dispatcher) {
            composition.operate(publicationBorrower) {
                val use = owner.enter(composition.composerPermit(publicationBorrower), publicationParticipant)
                try { owner.reviewSnapshot(use, owner.readForPublication(use, root, revision)) }
                finally { owner.leave(use) }
            }
        }
        init {
            store.records[KEY] = if (legacy) PrivateRecord(1, 1, PostDraftCodec(ORIGIN, draftPolicy).encode(PostRecord(10,
                locals = listOf(PostLocal(CLIENT, 3, "Reviewed", "", remote, "\"1\"")), issued = listOf(CLIENT))))
            else PrivateRecord(1, 2, codec.encode(PostDraftV2Record(10, listOf(local), listOf(CLIENT))))
        }
        suspend fun open() { controller.restoreLocal().value(); controller.openLocal(CLIENT).value() }
        suspend fun close() { controller.close(); releasePublication(); ports.delivery.revoke(); boundary.clear() }
        fun current() = codec.decode(2, store.records.getValue(KEY).payload)
        fun seedPlain(associated: Boolean) {
            val old = store.records.getValue(KEY)
            val server = remote.takeIf { associated }; val etag = "\"1\"".takeIf { associated }
            store.records[KEY] = if (old.schemaVersion == 1) PrivateRecord(old.revision, 1,
                PostDraftCodec(ORIGIN, draftPolicy).encode(PostRecord(10,
                    locals = listOf(PostLocal(CLIENT, 3, "Reviewed", "", server, etag)), issued = listOf(CLIENT))))
            else PrivateRecord(old.revision, 2, codec.encode(PostDraftV2Record(10, listOf(snapshots.create(CLIENT, 3,
                DraftLocalContentV1.TextV1("Reviewed", ""), if (server == null) DraftServerAssociationV1.NotObserved
                else DraftServerAssociationV1.Observed(server, etag!!))), listOf(CLIENT))))
        }
        fun baselineExpiresAt(value: String) {
            remote = document(remote.json().jsonObject + ("expiresAt" to JsonPrimitive(value)))
            val old = store.records.getValue(KEY)
            val snapshot = snapshots.create(CLIENT, 3, local.content, DraftServerAssociationV1.Observed(remote, "\"1\""))
            store.records[KEY] = PrivateRecord(old.revision + 1, 2, codec.encode(current().copy(locals = listOf(snapshot))))
        }
        fun target() = PublicationTarget.SavedDraft(CLIENT, 3, SERVER, ExactPostVersion("1"), "\"1\"")
        suspend fun review() = controller.prepareReviewedSave(target(), patch()).value()
        fun patch() = ReviewedDraftPatch(PatchValue.Unchanged, PatchValue.Set("Reviewed"), PatchValue.Set(""),
            PatchValue.Set(listOf(number(21), number(20))), PatchValue.Unchanged, PatchValue.Set(false),
            PatchValue.Set(PublicationAudience.Circles(listOf(number(31), number(30)))), PatchValue.Set(true), PatchValue.Set(true),
            PatchValue.Set(ports.actualDisclosure), PatchValue.Unchanged)
        fun reply(call: ApiCall): PortResult<ApiReply> {
            val key = call.idempotencyKey?.use { it }; if (key != null) replies[key]?.let { return it }
            val reply = when (call.operationId) {
                "updatePostDraft" -> {
                    val expected = ReviewedPostDraftAdapter(8192).expectedFields(call, remote, call.ifMatch!!)
                    remote = document(expected.json().jsonObject + mapOf("updatedAt" to JsonPrimitive(TIME), "expiresAt" to JsonPrimitive("2030-01-01T00:00:00Z")))
                    response(remote)
                }
                "getPostDraft" -> response(remote)
                else -> PortResult.Failure(FailureReason.NOT_CONFIGURED)
            }
            if (key != null) replies[key] = reply
            return reply
        }
    }
    private companion object {
        const val SOURCE = "ABCDEFAB-1111-4111-8111-111111111111"
        fun attachment() = buildJsonObject { put("recipeVersionId", SOURCE); put("confirmedChanges", JsonArray(listOf(JsonPrimitive("One"), JsonPrimitive("Two"))))
            put("reviewStatus", "reviewed"); put("rightsBasis", "catalogRedistributable") }
        fun baseline() = document(draft(CLIENT, "Before").json().jsonObject + mapOf("attachment" to attachment(),
            "sourcePostId" to JsonPrimitive(SOURCE), "altText" to JsonPrimitive("Before alt")))
        fun choices() = document(mapOf("caption" to JsonPrimitive("Reviewed"), "altText" to JsonPrimitive(""),
            "mediaIds" to JsonArray(listOf(JsonPrimitive(number(21)), JsonPrimitive(number(20)))),
            "audience" to buildJsonObject { put("kind", "circles"); put("circleIds", JsonArray(listOf(JsonPrimitive(number(31)), JsonPrimitive(number(30))))) },
            "keepOnPlate" to JsonPrimitive(true), "allowRecipeSaves" to JsonPrimitive(true), "attachment" to attachment(),
            "sourcePostId" to JsonPrimitive(SOURCE), "saveDisclosureVersion" to JsonPrimitive("reviewed-v1")))
    }
}
