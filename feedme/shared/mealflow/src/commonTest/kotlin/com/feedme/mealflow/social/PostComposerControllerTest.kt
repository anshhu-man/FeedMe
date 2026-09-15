package com.feedme.mealflow.social

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.mealflow.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Actual PUBLIC controller -> actual composition/queue -> both real journal codecs/owners.
 * Storage CAS and trusted account/prerequisite/transport/native-delivery ports are explicitly
 * synthetic. No controller replacement, direct-store response or live provider is fabricated.
 * These common tests are not actual SQLite/HTTP/native-host acceptance. */
@OptIn(ExperimentalCoroutinesApi::class)
class PostComposerControllerTest {
    @Test fun constructionPerformsNoIoResolutionIdAllocationOrTransport() = runTest { fixture { f ->
        assertEquals(0, f.store.reads); assertEquals(0, f.store.commits)
        assertEquals(0, f.principals.resolves); assertEquals(0, f.allocated); assertTrue(f.calls.isEmpty())
        assertEquals(PostComposerPhase.HIDDEN, f.controller.states.value.phase)
    } }

    @Test fun restoreReadsActualCompleteDraftProjectionWithoutWritingOrAcknowledging() = runTest { fixture { f ->
        val state = value(f.controller.restore())
        assertEquals(PostComposerPhase.HISTORY, state.phase); assertFalse(state.acknowledged)
        assertTrue(state.history.isEmpty()); assertEquals(0, f.store.commits); assertEquals(0, f.allocated); assertTrue(f.calls.isEmpty())
    } }

    @Test fun newReviewDisplaysExactChoicesAndDisclosureWithoutAllocatingOrSubmitting() = runTest { fixture { f ->
        val state = value(f.controller.prepareReview(f.target(), f.choices()))
        val review = state.review!!
        assertEquals(CAPTION, json(review.snapshot.exactProposedPostWrite).getValue("caption").jsonPrimitive.content)
        assertEquals(DISCLOSURE, review.snapshot.disclosure.version); assertEquals(DISPLAY, review.snapshot.disclosure.text)
        assertEquals(ROOT, review.snapshot.reviewedLocal.clientDraftId); assertEquals(ACCOUNT, review.snapshot.publisherUserId)
        assertFalse(state.acknowledged); assertEquals(0, f.store.commits); assertEquals(0, f.allocated); assertTrue(f.calls.isEmpty())
    } }

    @Test fun directAndExactSavedBigintBranchesPublishThroughTheActualQueueAndBothOwners() = runTest {
        for (saved in listOf(false, true)) fixture(saved) { f ->
            val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
            val exact = review.snapshot.exactProposedPostWrite.encodeUtf8()
            val state = value(f.controller.confirmPublish(review.token))
            assertEquals(PostComposerPhase.PUBLISHED, state.phase); assertTrue(state.acknowledged); assertEquals("\"1\"", state.etag)
            assertEquals(1, f.allocated); assertEquals(1, f.calls.size)
            val call = f.calls.single(); assertContentEquals(exact, call.body!!.copyForCodec())
            assertEquals("publishPost", call.operationId); assertTrue(call.pathParameters.isEmpty()); assertNull(call.ifMatch)
            val request = json(WireDocument.decode(exact))
            assertEquals(saved, "draftId" in request); assertEquals(saved, "draftVersion" in request)
            if (saved) assertEquals("9007199254740993", request.getValue("draftVersion").jsonPrimitive.content)
            assertTrue(f.drafts().locals.none { it.clientDraftId == ROOT }); assertNull(f.drafts().publicationHold)
            assertEquals(ROOT, f.drafts().terminals.single().clientId)
            assertEquals("APPLIED", f.metadata().getValue("phase").jsonPrimitive.content)
            assertContentEquals(f.actualReplies.single().body!!.copyForCodec(), state.exactCanonicalPost!!.encodeUtf8())
        }
    }

    @Test fun actualPublishedHistoryCannotBeRestoredAsAnotherAcknowledgementOrEditableRoot() = runTest { fixture { f ->
        f.publish()
        val before = f.store.commits
        val historical = value(f.controller.restore())
        assertFalse(historical.acknowledged); assertEquals("published", historical.history.single().outcome)
        assertNotNull(historical.history.single().exactCanonicalPost); assertEquals(before, f.store.commits)
        failure(FailureReason.CONFLICT, f.controller.prepareReview(f.target(), f.choices()))
        assertEquals(1, f.allocated); assertEquals(1, f.calls.size)
    } }

    @Test fun defaultMissingMappingOrRequiredNewPrerequisiteCannotEnablePublication() = runTest { fixture { f ->
        f.principals.resolveFailure = FailureReason.NOT_CONFIGURED
        failure(FailureReason.NOT_CONFIGURED, f.controller.prepareReview(f.target(), f.choices()))
        f.principals.resolveFailure = null; f.newFailure = FailureReason.FORBIDDEN
        failure(FailureReason.FORBIDDEN, f.controller.prepareReview(f.target(), f.choices()))
        assertEquals(0, f.allocated); assertEquals(0, f.store.commits); assertTrue(f.calls.isEmpty())
    } }

    @Test fun changedDisclosureInvalidatesReviewInsteadOfChangingOriginalOrConsentingAgain() = runTest { fixture { f ->
        val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
        f.disclosure = PublicationDisclosure("synthetic-disclosure-v2", "Different actual synthetic text")
        failure(FailureReason.CONFLICT, f.controller.confirmPublish(review.token))
        assertEquals(0, f.allocated); assertEquals(0, f.store.commits); assertTrue(f.calls.isEmpty())
    } }

    @Test fun expiredAndForgedReviewTokensCannotAllocateAnyCommand() = runTest { fixture { f ->
        val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
        failure(FailureReason.CONFLICT, f.controller.confirmPublish(PreparedPublicationReview()))
        f.time += 300_000
        failure(FailureReason.CONFLICT, f.controller.confirmPublish(review.token))
        assertEquals(0, f.allocated); assertEquals(0, f.store.commits); assertTrue(f.calls.isEmpty())
    } }

    @Test fun laterReviewInvalidatesEarlierTokenWithoutChangingTheRetainedLocalSnapshot() = runTest { fixture { f ->
        val first = value(f.controller.prepareReview(f.target(), f.choices())).review!!
        val second = value(f.controller.prepareReview(f.target(), f.choices())).review!!
        assertNotSame(first.token, second.token)
        failure(FailureReason.CONFLICT, f.controller.confirmPublish(first.token))
        assertEquals(CAPTION, (f.drafts().locals.single().content as DraftLocalContentV1.TextV1).caption)
        assertEquals(0, f.allocated); assertTrue(f.calls.isEmpty())
    } }

    @Test fun principalRevocationDuringFinalCallerHandoffCannotDeliverReviewOrCallLegacyCurrentnessOnCaller() = runTest { fixture { f ->
        val caller = QueuedCaller(); val running = async(caller) { f.controller.prepareReview(f.target(), f.choices()) }
        caller.runOne(); runCurrent(); assertFalse(running.isCompleted); assertTrue(caller.hasWork())
        withContext(f.dispatcher) { f.delivery.revokeBeforeMutation(); f.principals.generation = Any() }
        val checks = f.principals.syncChecks
        caller.runAll(); runCurrent(); caller.runAll(); runCurrent()
        failure(FailureReason.STALE_SESSION, running.await())
        assertEquals(checks, f.principals.syncChecks)
        assertNull(f.controller.states.value.review); assertFalse(f.controller.states.value.acknowledged)
        assertEquals(0, f.allocated); assertTrue(f.calls.isEmpty())
    } }

    @Test fun revocationAfterActualQueueApplyBeforeCallerHandoffRetainsRecoverableEvidenceNotAck() = runTest { fixture { f ->
        val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
        val caller = QueuedCaller(); val running = async(caller) { f.controller.confirmPublish(review.token) }
        caller.runOne(); runCurrent(); assertFalse(running.isCompleted); assertTrue(caller.hasWork())
        assertEquals("APPLIED", f.metadata().getValue("phase").jsonPrimitive.content)
        assertEquals(1, f.drafts().terminals.size); assertFalse(f.controller.states.value.acknowledged)
        val commits = f.store.commits
        withContext(f.dispatcher) { f.delivery.revokeBeforeMutation(); f.principals.generation = Any() }
        caller.runAll(); runCurrent(); caller.runAll(); runCurrent()
        failure(FailureReason.STALE_SESSION, running.await()); assertFalse(f.controller.states.value.acknowledged)
        val recovered = value(f.controller.finalizeOriginal())
        assertTrue(recovered.acknowledged); assertEquals(PostComposerPhase.PUBLISHED, recovered.phase)
        assertEquals(commits + 1, f.store.commits); assertEquals(1, f.calls.size); assertEquals(1, f.allocated)
    } }

    @Test fun callerCancellationAfterActualApplyDoesNotRetireEvidenceAndOriginalFinalizationDoesNotSendAgain() = runTest { fixture { f ->
        val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
        val caller = QueuedCaller(); var delivered = false
        val running = async(caller) { f.controller.confirmPublish(review.token).also { delivered = true } }
        caller.runOne(); runCurrent(); assertTrue(caller.hasWork())
        assertEquals("APPLIED", f.metadata().getValue("phase").jsonPrimitive.content)
        running.cancel(); caller.runAll(); runCurrent(); caller.runAll(); runCurrent(); running.join()
        assertTrue(running.isCancelled); assertFalse(delivered); assertFalse(f.controller.states.value.acknowledged)
        val recovered = value(f.controller.finalizeOriginal())
        assertTrue(recovered.acknowledged); assertEquals(1, f.calls.size); assertEquals(1, f.allocated)
    } }

    @Test fun finalCallerDeliveryAllowsLaterMemoryRetirementWithoutAStorageWriteOrSecondAck() = runTest { fixture { f ->
        val published = f.publish(); assertTrue(published.acknowledged)
        val commits = f.store.commits
        val observed = value(f.controller.restore())
        assertFalse(observed.acknowledged); assertEquals(commits, f.store.commits)
        failure(FailureReason.CONFLICT, f.controller.finalizeOriginal())
        assertEquals(commits, f.store.commits); assertEquals(1, f.calls.size)
    } }

    @Test fun lostActualApplicationCommitReplyKeepsOnePostAndRequiresRealReadbackFinalization() = runTest { fixture { f ->
        val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
        f.store.afterCommit = { batch -> if (batch.any { it.key == DRAFT_KEY } && batch.any { it.key == PUB_KEY } &&
            f.drafts().terminals.isNotEmpty()) PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) else null }
        failure(FailureReason.OUTCOME_UNKNOWN, f.controller.confirmPublish(review.token))
        assertEquals("APPLIED", f.metadata().getValue("phase").jsonPrimitive.content)
        assertFalse(f.controller.states.value.acknowledged); f.store.afterCommit = { null }
        val commits = f.store.commits
        assertTrue(value(f.controller.finalizeOriginal()).acknowledged)
        assertEquals(commits + 1, f.store.commits); assertEquals(1, f.calls.size); assertEquals(1, f.allocated)
    } }

    @Test fun failedApplicationBeforeCommitRepreparesActualSameReceiptWithoutAnotherTransportOrId() = runTest { fixture { f ->
        val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
        f.store.beforeCommit = { changes ->
            val draft = changes.filterIsInstance<StoreMutation.Put>().singleOrNull { it.key == DRAFT_KEY }
            if (draft != null && f.codec.decode(2, draft.payload).terminals.isNotEmpty())
                PortResult.Failure(FailureReason.STORAGE_FAILURE) else null
        }
        failure(FailureReason.STORAGE_FAILURE, f.controller.confirmPublish(review.token))
        assertEquals("RECEIPT_READY", f.metadata().getValue("phase").jsonPrimitive.content)
        assertTrue(f.drafts().terminals.isEmpty()); assertNotNull(f.drafts().publicationHold)
        assertFalse(f.controller.states.value.acknowledged); f.store.beforeCommit = { null }
        val commits = f.store.commits
        val completed = value(f.controller.finalizeOriginal())
        assertTrue(completed.acknowledged); assertEquals(commits + 1, f.store.commits)
        assertEquals("APPLIED", f.metadata().getValue("phase").jsonPrimitive.content)
        assertEquals(1, f.calls.size); assertEquals(1, f.allocated)
    } }

    @Test fun priorDeliveredTicketCanRetireAcrossSameLeaseControllerReplacementButHistoryCannotMintOne() = runTest { fixture { f ->
        f.publish(); val commits = f.store.commits
        value(f.controller.close()); value(f.draftController.close()); f.replaceController()
        assertFalse(value(f.controller.restore()).acknowledged)
        failure(FailureReason.CONFLICT, f.controller.finalizeOriginal())
        assertEquals(commits, f.store.commits); assertEquals(1, f.calls.size)
    } }

    @Test fun pureEpochCurrentnessAndForgedApplicationTicketCannotRegisterAReceipt() = runTest { fixture { f ->
        value(f.controller.restore()); val fake = PublicationControllerDeliveryTicket(Any())
        assertFalse(fake.deliveredApplication(ActualPublicationApplication()))
        failure(FailureReason.CONFLICT, f.controller.applyActualReceipt())
        assertEquals(0, f.store.commits); assertEquals(0, f.allocated); assertTrue(f.calls.isEmpty())
    } }

    @Test fun lostEnqueueReplyBeforeOrAfterCommitRecoversOnlyTheSameAllocatedOriginalWithoutAnotherId() = runTest {
        for (after in listOf(false, true)) fixture { f ->
            val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
            val fault: (List<StoreMutation>) -> PortResult.Failure? = { changes ->
                if (changes.any { it.key == DRAFT_KEY } && changes.any { it.key == PUB_KEY })
                    PortResult.Failure(if (after) FailureReason.OUTCOME_UNKNOWN else FailureReason.STORAGE_FAILURE) else null
            }
            if (after) f.store.afterCommit = fault else f.store.beforeCommit = fault
            failure(if (after) FailureReason.OUTCOME_UNKNOWN else FailureReason.STORAGE_FAILURE, f.controller.confirmPublish(review.token))
            assertEquals(1, f.allocated); assertTrue(f.calls.isEmpty()); assertFalse(f.controller.states.value.acknowledged)
            f.store.beforeCommit = { null }; f.store.afterCommit = { null }
            val inspection = value(f.controller.restore())
            assertEquals(COMMAND, inspection.pending!!.commandId)
            assertEquals(if (after) 0 else null, inspection.pending.observedAttempts)
            val retry = value(f.controller.prepareOriginalRetry()).retry!!
            assertContentEquals(review.snapshot.exactProposedPostWrite.encodeUtf8(), retry.original.exactOriginalPostWrite.encodeUtf8())
            assertEquals(COMMAND, retry.original.commandId); assertEquals(START, retry.original.originalCreatedAtMillis)
            val published = value(f.controller.retryOriginal(retry.token))
            assertTrue(published.acknowledged); assertEquals(1, f.allocated); assertEquals(1, f.calls.size)
            assertContentEquals(review.snapshot.exactProposedPostWrite.encodeUtf8(), f.calls.single().body!!.copyForCodec())
        }
    }

    @Test fun newerDurableTextBeforeUnobservedRegistrationRequiresFreshDualReviewAndPreservesExactOriginal() = runTest { fixture { f ->
        val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
        f.store.beforeCommit = { changes -> if (changes.any { it.key == PUB_KEY }) PortResult.Failure(FailureReason.STORAGE_FAILURE) else null }
        failure(FailureReason.STORAGE_FAILURE, f.controller.confirmPublish(review.token)); f.store.beforeCommit = { null }
        value(f.draftController.openLocal(ROOT)); value(f.draftController.editCaption(ROOT, "Newer explicitly retained dinner"))
        assertEquals(2L, f.drafts().locals.single().localRevision)
        val original = value(f.controller.restore()).pending!!
        assertEquals(COMMAND, original.commandId); assertNull(original.observedAttempts)
        failure(FailureReason.CONFLICT, f.controller.prepareReview(PublicationTarget.DirectLocal(ROOT, 2), f.choices()))
        assertEquals("Newer explicitly retained dinner", (f.drafts().locals.single().content as DraftLocalContentV1.TextV1).caption)
        assertEquals(1, f.allocated); assertTrue(f.calls.isEmpty()); assertNull(f.drafts().publicationHold)
        val previous = value(f.controller.prepareOriginalRetry()).retry!!
        assertNull(previous.observedAttempts); assertEquals(2L, previous.separatelyObservedCurrentLocal.localRevision)
        value(f.draftController.editCaption(ROOT, "Even newer retained dinner"))
        failure(FailureReason.CONFLICT, f.controller.retryOriginal(previous.token))
        val fresh = value(f.controller.prepareOriginalRetry()).retry!!
        assertEquals(3L, fresh.separatelyObservedCurrentLocal.localRevision)
        assertEquals(1L, fresh.original.originalReviewedLocal.localRevision)
        assertContentEquals(review.snapshot.exactProposedPostWrite.encodeUtf8(), fresh.original.exactOriginalPostWrite.encodeUtf8())
        assertEquals(START, fresh.original.originalCreatedAtMillis)
        val published = value(f.controller.retryOriginal(fresh.token))
        assertTrue(published.acknowledged); assertEquals(1, f.allocated); assertEquals(1, f.calls.size)
        assertContentEquals(review.snapshot.exactProposedPostWrite.encodeUtf8(), f.calls.single().body!!.copyForCodec())
        assertEquals("Even newer retained dinner", (f.drafts().remainders.single().content as DraftLocalContentV1.TextV1).caption)
        assertEquals(3L, f.drafts().remainders.single().newerLocalRevision)
        assertTrue(f.drafts().locals.isEmpty())
    } }

    @Test fun explicitZeroAttemptRetryShowsOriginalAndNewerRetainedTextThenPublishesOnlyOriginalWithRemainder() = runTest { fixture { f ->
        val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
        f.online = false; failure(FailureReason.OFFLINE, f.controller.confirmPublish(review.token))
        assertEquals("AWAITING_CONFIRMATION", f.metadata().getValue("phase").jsonPrimitive.content)
        assertEquals(0, f.metadata().getValue("attempts").jsonPrimitive.int)
        value(f.draftController.openLocal(ROOT)); value(f.draftController.editCaption(ROOT, "Newer unsent lunch")); f.online = true
        val retry = value(f.controller.prepareOriginalRetry()).retry!!
        assertEquals(0, retry.observedAttempts); assertEquals(1L, retry.original.originalReviewedLocal.localRevision)
        assertEquals(2L, retry.separatelyObservedCurrentLocal.localRevision)
        assertEquals(CAPTION, json(retry.original.exactOriginalPostWrite).getValue("caption").jsonPrimitive.content)
        val state = value(f.controller.retryOriginal(retry.token)); assertTrue(state.acknowledged)
        assertEquals(CAPTION, json(state.exactCanonicalPost!!).getValue("caption").jsonPrimitive.content)
        val remainder = f.drafts().remainders.single()
        assertEquals(2L, remainder.newerLocalRevision); assertEquals("Newer unsent lunch", (remainder.content as DraftLocalContentV1.TextV1).caption)
        assertTrue(f.drafts().locals.isEmpty()); assertEquals(1, f.allocated); assertEquals(1, f.calls.size)
    } }

    @Test fun attemptedSavedOriginalUsesReplayAdmissionAfterActualTerminalDraftObservationWithoutRebasing() = runTest { fixture(saved = true) { f ->
        val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
        f.transport = { PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
        val pending = value(f.controller.confirmPublish(review.token)); assertEquals(1, pending.pending!!.observedAttempts)
        f.transport = { call -> if (call.operationId == "getPostDraft") {
            val observed = JsonObject(draft() + mapOf("version" to Json.parseToJsonElement("9007199254740994"),
                "status" to JsonPrimitive("published"), "publishedPostId" to JsonPrimitive(POST)))
            PortResult.Value(ApiReply(200, PrivateBytes(observed.toString().encodeToByteArray()), etag = "\"9007199254740994\"", contentType = "application/json"))
        } else PortResult.Value(reply(call).also { f.actualReplies += it }) }
        value(f.draftController.openLocal(ROOT)); value(f.draftController.refreshRemote(DRAFT))
        val observed = f.drafts().locals.single().serverAssociation as DraftServerAssociationV1.Observed
        assertEquals("published", json(observed.exactPostDraft).getValue("status").jsonPrimitive.content)
        f.time += 60_000; f.newFailure = FailureReason.FORBIDDEN
        val retry = value(f.controller.prepareOriginalRetry()).retry!!
        assertEquals(1, retry.observedAttempts)
        assertEquals("9007199254740993", json(retry.original.exactOriginalPostWrite).getValue("draftVersion").jsonPrimitive.content)
        val state = value(f.controller.retryOriginal(retry.token)); assertTrue(state.acknowledged)
        val sends = f.calls.filter { it.operationId == "publishPost" }
        assertEquals(2, sends.size); assertEquals(1, f.allocated)
        assertContentEquals(sends.first().body!!.copyForCodec(), sends.last().body!!.copyForCodec())
        assertEquals(sends.first().idempotencyKey!!.use { it }, sends.last().idempotencyKey!!.use { it })
    } }

    @Test fun selectedPhotoOrderAndUuidSpellingAreReviewedAndSentUnchangedWhileActualPostNormalizesIds() = runTest { fixture { f ->
        val media = listOf(AVATAR.uppercase(), DRAFT.uppercase())
        val choices = ReviewedPostChoices(CAPTION, OptionalValue.Absent, media, PublicationAudience.OnlyYou, false,
            OptionalValue.Absent, false, f.disclosure, OptionalValue.Absent)
        val review = value(f.controller.prepareReview(f.target(), choices)).review!!
        val state = value(f.controller.confirmPublish(review.token)); assertTrue(state.acknowledged)
        assertEquals(media, json(WireDocument.decode(f.calls.single().body!!.copyForCodec())).getValue("mediaIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(media.map(String::lowercase), json(state.exactCanonicalPost!!).getValue("mediaIds").jsonArray.map { it.jsonPrimitive.content })
        // Actual media readiness remains the explicit synthetic prerequisite here, not a native
        // upload, a server-side READY grant, or a substitute for later real HTTP/media acceptance.
    } }

    @Test fun actualAckCollectorRevocationPreservesAuthorizedEmissionAndItsLaterRealRetirement() = runTest {
        // Both synthetic identity owner and collector execute serially on this test dispatcher.
        // No claim of an implemented native dispatcher/mapping is made by this fixture.
        val f = Fixture(UnconfinedTestDispatcher(testScheduler), false)
        var observed = false
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.controller.states.collect { state ->
            if (state.acknowledged) {
                assertEquals("APPLIED", f.metadata().getValue("phase").jsonPrimitive.content)
                assertNotNull(state.exactCanonicalPost)
                observed = true
                f.delivery.revokeBeforeMutation(); f.principals.generation = Any()
            }
        } }
        try {
            val published = f.publish()
            assertTrue(observed); assertTrue(published.acknowledged)
            assertEquals(PostComposerPhase.UNAVAILABLE, f.controller.states.value.phase)
            val commits = f.store.commits
            assertFalse(value(f.controller.restore()).acknowledged)
            // Only a genuinely published actual application permits this memory retirement.
            // A rolled-back emission in the old CAS-before-gate ordering instead lost delivery.
            failure(FailureReason.CONFLICT, f.controller.finalizeOriginal())
            assertEquals(commits, f.store.commits); assertEquals(1, f.calls.size); assertEquals(1, f.allocated)
        } finally { collector.cancelAndJoin(); withContext(NonCancellable) { f.close() } }
    }

    @Test fun actualRevocationWinningBeforeCallerAuthorizationNeverEmitsEvenATransientAck() = runTest { fixture { f ->
        val seen = mutableListOf<PostComposerState>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.controller.states.collect { seen += it } }
        val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
        val caller = QueuedCaller(); val running = async(caller) { f.controller.confirmPublish(review.token) }
        try {
            caller.runOne(); runCurrent(); assertTrue(caller.hasWork())
            assertEquals("APPLIED", f.metadata().getValue("phase").jsonPrimitive.content)
            withContext(f.dispatcher) { f.delivery.revokeBeforeMutation(); f.principals.generation = Any() }
            caller.runAll(); runCurrent(); caller.runAll(); runCurrent()
            failure(FailureReason.STALE_SESSION, running.await())
            assertFalse(seen.any { it.acknowledged }); assertEquals(1, f.calls.size)
            assertTrue(value(f.controller.finalizeOriginal()).acknowledged)
        } finally {
            running.cancel()
            // Drain both explicitly controlled dispatchers even when a pre-resume assertion
            // fails. Do not strand this structured child behind an unconsumed queued return.
            repeat(4) { caller.runAll(); runCurrent() }
            withContext(NonCancellable) { running.join(); collector.cancelAndJoin() }
        }
    } }

    @Test fun gateAuthorizationFollowedByChangedBoundFlowCannotFabricateActualDelivery() = runTest { fixture { f ->
        value(f.controller.restore())
        val before = PostComposerState(PostComposerPhase.HISTORY)
        val projected = PostComposerState(PostComposerPhase.PUBLISHED, acknowledged = true)
        val flow = MutableStateFlow(before); val owner = Any(); val application = ActualPublicationApplication()
        val ticket = PublicationControllerDeliveryTicket(owner)
        assertTrue(f.armTicket(ticket, owner, application, before, projected, flow))
        assertTrue(ticket.authorize(owner, application, before, projected))
        assertFalse(ticket.deliveredApplication(application))
        val seen = mutableListOf<PostComposerState>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { seen += it } }
        try {
            val redacted = PostComposerState.unavailable(); flow.value = redacted; ticket.revoke()
            assertNull(ticket.publishAuthorized(owner, application, before, projected))
            assertSame(redacted, flow.value); assertFalse(seen.any { it.acknowledged })
            assertFalse(ticket.deliveredApplication(application))
            assertNull(ticket.publishAuthorized(owner, application, before, projected))
        } finally { collector.cancelAndJoin() }
        // Ticket-only data exercises publication order, NOT actual queue/application provenance.
        assertEquals(0, f.store.commits); assertTrue(f.calls.isEmpty())
    } }

    @Test fun ticketCollectorRunsInsideCasToStampAndCannotEraseAlreadyAuthorizedObservedAck() = runTest { fixture { f ->
        value(f.controller.restore())
        val before = PostComposerState(PostComposerPhase.HISTORY)
        val projected = PostComposerState(PostComposerPhase.PUBLISHED, acknowledged = true)
        val flow = MutableStateFlow(before); val owner = Any(); val application = ActualPublicationApplication()
        val ticket = PublicationControllerDeliveryTicket(owner)
        assertTrue(f.armTicket(ticket, owner, application, before, projected, flow))
        assertTrue(ticket.authorize(owner, application, before, projected))
        var insideCas = false
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { state ->
            if (state.acknowledged) {
                assertFalse(ticket.deliveredApplication(application)) // proves the critical interval
                insideCas = true; ticket.revoke(); flow.value = PostComposerState.unavailable()
            }
        } }
        try {
            assertSame(projected, ticket.publishAuthorized(owner, application, before, projected))
            assertTrue(insideCas); assertTrue(ticket.deliveredApplication(application))
            assertEquals(PostComposerPhase.UNAVAILABLE, flow.value.phase)
        } finally { collector.cancelAndJoin() }
        assertEquals(0, f.store.commits); assertTrue(f.calls.isEmpty())
    } }

    @Test fun actualCallerCancellationFromAckCollectorCannotEraseAnAlreadyObservedRealApplication() = runTest {
        val f = Fixture(UnconfinedTestDispatcher(testScheduler), false)
        var caller: Job? = null; var observed = false
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.controller.states.collect { state ->
            if (state.acknowledged) {
                observed = true; caller!!.cancel(CancellationException("After actual ACK emission"))
            }
        } }
        try {
            val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
            val task = async(start = CoroutineStart.LAZY) { f.controller.confirmPublish(review.token) }
            caller = task; task.start(); task.join()
            assertTrue(observed); assertTrue(task.isCancelled)
            assertTrue(f.controller.states.value.acknowledged)
            val commits = f.store.commits
            assertFalse(value(f.controller.restore()).acknowledged)
            failure(FailureReason.CONFLICT, f.controller.finalizeOriginal())
            assertEquals(commits, f.store.commits); assertEquals(1, f.calls.size)
            // Cancellation can suppress Deferred's eventual returned value after this genuine
            // public emission; historical delivery is not rewritten to undelivered evidence.
        } finally { collector.cancelAndJoin(); withContext(NonCancellable) { f.close() } }
    }

    @Test fun naturalAcknowledgedLocalEditCanBeReviewedAndPublishedWithoutDummyDraftAction() = runTest { fixture { f ->
        value(f.draftController.openLocal(ROOT))
        val local = value(f.draftController.editCaption(ROOT, "Freshly acknowledged dinner"))
        assertTrue(local.selected!!.localAcknowledged)
        val choices = f.choices("Freshly acknowledged dinner")
        val review = value(f.controller.prepareReview(PublicationTarget.DirectLocal(ROOT, 2), choices)).review!!
        val published = value(f.controller.confirmPublish(review.token))
        assertTrue(published.acknowledged); assertEquals(1, f.calls.size); assertEquals(1, f.allocated)
        assertEquals("Freshly acknowledged dinner", json(published.exactCanonicalPost!!).getValue("caption").jsonPrimitive.content)
        assertNull(f.drafts().localPending); assertTrue(f.drafts().locals.isEmpty())
        // Required natural UX. Owner successor must recognize only the genuinely delivered
        // exact held edit; a serialized historical localPending marker is insufficient.
    } }

    @Test fun naturalNewLocalAcknowledgementCanPublishDirectlyWithoutImplicitPrivateSave() = runTest { fixture { f ->
        val local = value(f.draftController.newLocalDraft("New root dinner"))
        assertTrue(local.selected!!.localAcknowledged); assertEquals(NEW_ROOT, local.selected!!.clientDraftId)
        val review = value(f.controller.prepareReview(PublicationTarget.DirectLocal(NEW_ROOT, 1), f.choices("New root dinner"))).review!!
        val published = value(f.controller.confirmPublish(review.token))
        assertTrue(published.acknowledged); assertEquals(1, f.privateAllocated); assertEquals(1, f.allocated)
        assertEquals(listOf("publishPost"), f.calls.map { it.operationId })
        assertEquals(listOf(ROOT), f.drafts().locals.map { it.clientDraftId })
        assertEquals(NEW_ROOT, f.drafts().terminals.single().clientId)
    } }

    @Test fun actualReviewedSaveAcknowledgementCanImmediatelyReviewAndPublishItsExactSavedSuccessor() = runTest { fixture(saved = true) { f ->
        val caption = "Privately saved healthy dinner"
        value(f.draftController.openLocal(ROOT))
        assertTrue(value(f.draftController.editCaption(ROOT, caption)).selected!!.localAcknowledged)
        val patch = ReviewedDraftPatch(PatchValue.Unchanged, PatchValue.Set(caption), PatchValue.Unchanged,
            PatchValue.Unchanged, PatchValue.Unchanged, PatchValue.Unchanged, PatchValue.Unchanged,
            PatchValue.Unchanged, PatchValue.Unchanged, PatchValue.Set(f.disclosure), PatchValue.Unchanged)
        f.transport = { call ->
            if (call.operationId == "updatePostDraft") {
                val baseline = WireDocument.parse(draft().toString())
                val expected = json(ReviewedPostDraftAdapter(8192).expectedFields(call, baseline, "\"9007199254740993\""))
                val actual = JsonObject(expected + mapOf("updatedAt" to JsonPrimitive("2026-09-14T12:00:00Z"),
                    "expiresAt" to JsonPrimitive("2026-10-14T12:00:00Z")))
                PortResult.Value(ApiReply(200, PrivateBytes(actual.toString().encodeToByteArray()),
                    contentType = "application/json", etag = "\"9007199254740994\""))
            } else PortResult.Value(reply(call).also { f.actualReplies += it })
        }
        val savedTarget = PublicationTarget.SavedDraft(ROOT, 2, DRAFT, ExactPostVersion("9007199254740993"), "\"9007199254740993\"")
        val privateReview = value(f.draftController.prepareReviewedSave(savedTarget, patch))
        val saved = value(f.draftController.confirmReviewedSave(privateReview.token))
        assertTrue(saved.serverAcknowledged); assertNotNull(f.drafts().completion)
        val held = PostDraftCurrentHeld.apply(f.access, f.boundary)!!
        assertTrue(held.delivery!!.delivered(held))
        // No GET, no no-op edit, no reopening and no dummy Save to clear actual completion.
        val publishTarget = PublicationTarget.SavedDraft(ROOT, 2, DRAFT, ExactPostVersion("9007199254740994"), "\"9007199254740994\"")
        val review = value(f.controller.prepareReview(publishTarget, f.choices(caption))).review!!
        assertNotNull(f.drafts().completion) // Read-only review does not mutate delivery markers.
        val published = value(f.controller.confirmPublish(review.token))
        assertTrue(published.acknowledged); assertNull(f.drafts().completion); assertTrue(f.drafts().locals.isEmpty())
        assertEquals(listOf("updatePostDraft", "publishPost"), f.calls.map { it.operationId })
        assertEquals("9007199254740994", json(WireDocument.decode(f.calls.last().body!!.copyForCodec())).getValue("draftVersion").jsonPrimitive.content)
        assertEquals(caption, json(published.exactCanonicalPost!!).getValue("caption").jsonPrimitive.content)
        assertEquals(1, f.privateAllocated); assertEquals(1, f.allocated)
    } }

    @Test fun explicitUnsentCancellationArchivesOnlyOriginalAndKeepsItsRootOpen() = runTest { fixture { f ->
        val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
        f.online = false; failure(FailureReason.OFFLINE, f.controller.confirmPublish(review.token))
        val retained = value(f.controller.prepareUnsentCancellation()).unsentCancellation!!
        assertEquals(COMMAND, retained.original.commandId); assertEquals(ROOT, retained.retainedLocal.clientDraftId)
        val cancelled = value(f.controller.confirmUnsentCancellation(retained.token))
        assertEquals(PostComposerPhase.CANCELLED_UNSENT, cancelled.phase); assertTrue(cancelled.acknowledged)
        assertNull(cancelled.exactCanonicalPost); assertNull(f.drafts().publicationHold)
        assertEquals(ROOT, f.drafts().locals.single().clientDraftId); assertTrue(f.drafts().terminals.isEmpty())
        assertTrue(COMMAND in f.drafts().issued); assertEquals("DISCARDED", f.metadata().getValue("phase").jsonPrimitive.content)
        assertTrue(f.calls.isEmpty()); assertEquals(1, f.allocated)
        val commits = f.store.commits; assertFalse(value(f.controller.restore()).acknowledged)
        assertEquals(commits, f.store.commits); failure(FailureReason.CONFLICT, f.controller.finalizeOriginal())
    } }

    @Test fun attemptedOriginalCannotBeCancelledAsIfItHadNeverBeenSent() = runTest { fixture { f ->
        f.transport = { PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
        val pending = value(f.controller.confirmPublish(value(f.controller.prepareReview(f.target(), f.choices())).review!!.token))
        assertEquals(1, pending.pending!!.observedAttempts)
        val before = f.store.commits
        failure(FailureReason.CONFLICT, f.controller.prepareUnsentCancellation())
        assertEquals(before, f.store.commits); assertNotNull(f.drafts().publicationHold)
        assertTrue(f.drafts().terminals.isEmpty()); assertEquals(1, f.calls.size); assertEquals(1, f.allocated)
    } }

    @Test fun newEligibilityDenialAfterReviewDoesNotReplaceOrAllocateOriginal() = runTest { fixture { f ->
        val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
        f.newFailure = FailureReason.FORBIDDEN
        failure(FailureReason.FORBIDDEN, f.controller.confirmPublish(review.token))
        assertEquals(0, f.allocated); assertTrue(f.calls.isEmpty()); assertEquals(0, f.store.commits)
        assertEquals(CAPTION, (f.drafts().locals.single().content as DraftLocalContentV1.TextV1).caption)
    } }

    @Test fun originalCallerCancellationDuringNoncooperativePrerequisiteNeverPublishesOrAllocates() = runTest { fixture { f ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.beforeNew = { withContext(NonCancellable) { entered.complete(Unit); release.await() } }
        val seen = mutableListOf<PostComposerState>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.controller.states.collect { seen += it } }
        val task = async { f.controller.prepareReview(f.target(), f.choices()) }
        try {
            entered.await(); task.cancel()
            assertTrue(task.isCancelled); assertFalse(task.isCompleted)
            release.complete(Unit); task.join()
            assertFalse(seen.any { it.acknowledged || it.review != null })
            assertEquals(0, f.allocated); assertTrue(f.calls.isEmpty()); assertEquals(0, f.store.commits)
        } finally {
            f.beforeNew = {}; release.complete(Unit)
            withContext(NonCancellable) { task.cancelAndJoin(); collector.cancelAndJoin() }
        }
    } }

    @Test fun cancelledIdWithoutReturnedValueConsumesReviewAndRequiresExplicitUnreturnedAbandonment() = runTest { fixture { f ->
        val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
        f.nextPublicationId = { throw CancellationException("Synthetic ID result was not returned") }
        assertFailsWith<CancellationException> { f.controller.confirmPublish(review.token) }
        val observed = value(f.controller.restore())
        val allocation = observed.allocation!!
        assertEquals(PublicationAllocationPhase.AWAITING_ID, allocation.phase)
        assertNull(allocation.returnedCommandId); assertNull(observed.pending); assertFalse(observed.acknowledged)
        failure(FailureReason.CONFLICT, f.controller.confirmPublish(review.token))
        failure(FailureReason.CONFLICT, f.controller.prepareReview(f.target(), f.choices()))
        failure(FailureReason.CONFLICT, f.controller.prepareOriginalRetry())
        assertEquals(1, f.allocated); assertTrue(f.calls.isEmpty()); assertEquals(0, f.store.commits)
        value(f.controller.abandonUnreturnedAllocation(allocation.abandonToken!!))
        assertNull(value(f.controller.restore()).allocation)
        f.nextPublicationId = { NEXT_COMMAND }
        assertNotNull(value(f.controller.prepareReview(f.target(), f.choices())).review)
        assertEquals(1, f.allocated) // New review never invokes another ID provider.
    } }

    @Test fun explicitAbandonWhileNoncooperativeIdRunsKeepsSharedSlotUntilLateReturnIsFenced() = runTest { fixture { f ->
        val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.nextPublicationId = { withContext(NonCancellable) { entered.complete(Unit); release.await(); COMMAND } }
        val task = async { f.controller.confirmPublish(review.token) }
        try {
            entered.await()
            val allocation = f.controller.states.value.allocation!!
            assertEquals(PublicationAllocationPhase.AWAITING_ID, allocation.phase)
            assertNull(allocation.returnedCommandId); assertFalse(f.controller.states.value.acknowledged)
            withContext(f.dispatcher) {
                assertTrue(ComposerAllocationArbiter.hasPublication(f.access, f.boundary, ROOT))
                assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> {
                    ComposerAllocationArbiter.reserveReviewedSave(f.access, f.boundary, ROOT)
                }.reason)
            }
            task.cancel(); assertFalse(task.isCompleted)
            // Cancellation alone neither releases nor abandons this actual provider invocation.
            assertEquals(PublicationAllocationPhase.AWAITING_ID, f.controller.states.value.allocation!!.phase)
            value(f.controller.abandonUnreturnedAllocation(allocation.abandonToken!!))
            assertEquals(PublicationAllocationPhase.ABANDONED_WAITING_FOR_PROVIDER, f.controller.states.value.allocation!!.phase)
            withContext(f.dispatcher) {
                assertTrue(ComposerAllocationArbiter.hasPublication(f.access, f.boundary, ROOT))
                val unrelated = ComposerAllocationArbiter.reserveReviewedSave(f.access, f.boundary, NEW_ROOT)
                try { assertTrue(ComposerAllocationArbiter.hasReviewedSave(f.access, f.boundary, NEW_ROOT)) }
                finally { ComposerAllocationArbiter.release(unrelated) }
            }
            release.complete(Unit); task.join()
            assertTrue(task.isCancelled); assertEquals(1, f.allocated)
            assertTrue(f.calls.isEmpty()); assertEquals(0, f.store.commits)
            assertNull(value(f.controller.restore()).allocation)
            withContext(f.dispatcher) { assertFalse(ComposerAllocationArbiter.hasPublication(f.access, f.boundary, ROOT)) }
        } finally {
            release.complete(Unit); task.cancel()
            withContext(NonCancellable) { task.join() }
        }
    } }

    @Test fun actuallyReturnedIdCannotBeAbandonedEvenWhenPostIdCurrentnessFailsBeforeEnqueue() = runTest { fixture { f ->
        val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
        var unreturnedToken: PreparedPublicationAllocationAbandon? = null
        f.nextPublicationId = {
            unreturnedToken = f.controller.states.value.allocation!!.abandonToken
            f.delivery.revokeBeforeMutation(); f.principals.generation = Any()
            COMMAND
        }
        failure(FailureReason.STALE_SESSION, f.controller.confirmPublish(review.token))
        val observed = value(f.controller.restore())
        assertEquals(PublicationAllocationPhase.REGISTRATION_UNOBSERVED, observed.allocation!!.phase)
        assertEquals(COMMAND, observed.allocation.returnedCommandId); assertNull(observed.allocation.abandonToken)
        failure(FailureReason.CONFLICT, f.controller.abandonUnreturnedAllocation(unreturnedToken!!))
        val retry = value(f.controller.prepareOriginalRetry()).retry!!
        assertContentEquals(review.snapshot.exactProposedPostWrite.encodeUtf8(), retry.original.exactOriginalPostWrite.encodeUtf8())
        assertEquals(START, retry.original.originalCreatedAtMillis)
        assertTrue(value(f.controller.retryOriginal(retry.token)).acknowledged)
        assertEquals(1, f.allocated); assertEquals(1, f.calls.size)
    } }

    @Test fun unreturnedReservationSurvivesSameSessionControllerReplacementWithoutInventingAnId() = runTest { fixture { f ->
        val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
        f.nextPublicationId = { throw IllegalStateException("Synthetic provider failed without a returned ID") }
        failure(FailureReason.STORAGE_FAILURE, f.controller.confirmPublish(review.token))
        val before = value(f.controller.restore()).allocation!!
        value(f.controller.close()); value(f.draftController.close()); f.replaceController()
        val after = value(f.controller.restore()).allocation!!
        assertSame(before.abandonToken, after.abandonToken); assertNull(after.returnedCommandId)
        assertEquals(PublicationAllocationPhase.AWAITING_ID, after.phase)
        failure(FailureReason.CONFLICT, f.controller.prepareReview(f.target(), f.choices()))
        value(f.controller.abandonUnreturnedAllocation(after.abandonToken!!))
        assertNull(value(f.controller.restore()).allocation)
        assertEquals(1, f.allocated); assertTrue(f.calls.isEmpty()); assertEquals(0, f.store.commits)
    } }

    @Test fun actualPublicationApplicationTransferReleasesOnlyItsOwnSharedSlotNotUnrelatedSaveWork() = runTest { fixture { f ->
        val unrelated = withContext(f.dispatcher) { ComposerAllocationArbiter.reserveReviewedSave(f.access, f.boundary, NEW_ROOT) }
        try {
            f.nextPublicationId = {
                assertTrue(ComposerAllocationArbiter.hasPublication(f.access, f.boundary, ROOT))
                assertTrue(ComposerAllocationArbiter.hasReviewedSave(f.access, f.boundary, NEW_ROOT))
                COMMAND
            }
            assertTrue(f.publish().acknowledged)
            withContext(f.dispatcher) {
                assertFalse(ComposerAllocationArbiter.hasPublication(f.access, f.boundary, ROOT))
                assertTrue(ComposerAllocationArbiter.hasReviewedSave(f.access, f.boundary, NEW_ROOT))
            }
            assertEquals(1, f.allocated); assertEquals(1, f.calls.size)
        } finally { withContext(f.dispatcher) { ComposerAllocationArbiter.release(unrelated) } }
    } }

    @Test fun sameRootSaveClaimAfterReviewBlocksPublicationBeforeItsIdProvider() = runTest { fixture { f ->
        val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
        val saving = withContext(f.dispatcher) { ComposerAllocationArbiter.reserveReviewedSave(f.access, f.boundary, ROOT) }
        try {
            failure(FailureReason.CONFLICT, f.controller.confirmPublish(review.token))
            assertEquals(0, f.allocated); assertTrue(f.calls.isEmpty()); assertEquals(0, f.store.commits)
            withContext(f.dispatcher) {
                assertTrue(ComposerAllocationArbiter.hasReviewedSave(f.access, f.boundary, ROOT))
                assertFalse(ComposerAllocationArbiter.hasPublication(f.access, f.boundary, ROOT))
            }
        } finally { withContext(f.dispatcher) { ComposerAllocationArbiter.release(saving) } }
    } }

    @Test fun immediateAllocationProgressCollectorCanAbandonBeforeAnyIdProviderInvocation() = runTest {
        // Bounded same-thread reentrancy driver, not a production dispatcher. Always
        // dispatch directly so a real StateFlow collector runs inside the progress emission;
        // an Unconfined event loop can queue/conflate this exact before-provider interleaving.
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { block.run() }
        }
        val f = Fixture(dispatcher, saved = false)
        var cancelled = false
        val collector = backgroundScope.launch(dispatcher) { f.controller.states.collect { state ->
            val token = state.allocation?.abandonToken
            if (token != null && !cancelled) {
                cancelled = true
                assertEquals(0, f.allocated)
                value(f.controller.abandonUnreturnedAllocation(token))
                assertEquals(0, f.allocated)
            }
        } }
        try {
            val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
            assertIs<PortResult.Failure>(f.controller.confirmPublish(review.token))
            assertTrue(cancelled); assertEquals(0, f.allocated); assertEquals(0, f.store.commits); assertTrue(f.calls.isEmpty())
            assertFalse(f.controller.states.value.acknowledged)
            assertNull(value(f.controller.restore()).allocation)
            assertFalse(ComposerAllocationArbiter.hasPublication(f.access, f.boundary, ROOT))
        } finally { collector.cancelAndJoin(); withContext(NonCancellable) { f.close() } }
    }

    @Test fun unregisteredOriginalShadowCapacityPreservesFittingNewerEditAndRejectsOverflowWithoutAnotherId() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler), saved = false, draftRecordBytes = 65_536)
        try {
            val initial = f.drafts().locals.single()
            val binding = PublicationJournalBindingData("test", ORIGIN, ORIGIN, ACCOUNT)
            val body = PostPublicationEncoder(f.policy).encode(f.target(), f.choices())
            val probe = f.links.create(binding, COMMAND, START, ApiCall("publishPost", body = PrivateBytes(body.encodeUtf8()),
                idempotencyKey = SecretText(COMMAND)), initial, PublicationReviewTargetV1.DirectLocal, f.disclosure)
            val pubCodec = PostPublicationJournalCodecV1(binding, f.policy, f.links)
            val reserve = pubCodec.reservedPublishedBytes(PublicationJournalV1(binding, START, listOf(COMMAND),
                listOf(PublicationHistoryEntryV1.PendingOriginal(probe))))
            fun seed(padding: Int): PostDraftV2Record {
                var left = padding
                val locals = mutableListOf(initial)
                var index = 100
                while (left > 0) {
                    val count = minOf(500, left); left -= count
                    val ordinal = index++
                    val root = "bbbbbbbb-1000-4000-8000-${ordinal.toString().padStart(12, '0')}"
                    locals += f.snapshots.create(root, 1, DraftLocalContentV1.TextV1("🍳".repeat(count), null), DraftServerAssociationV1.NotObserved)
                }
                return PostDraftV2Record(START, locals, locals.map { it.clientDraftId })
            }
            fun fittingShadow(padding: Int): Boolean = try {
                val row = seed(padding)
                f.codec.encode(row)
                f.codec.withPublicationReservation(row.copy(issued = row.issued + COMMAND,
                    publicationHold = PostDraftPublicationHoldV2(probe, PublicationCapacityReservationV1(f.policy.maxResponseBytes, reserve, 0, 1))), reserve)
                val newer = f.snapshots.create(ROOT, 2, DraftLocalContentV1.TextV1("Short newer edit", null), DraftServerAssociationV1.NotObserved)
                f.codec.withPublicationReservation(row.copy(locals = listOf(newer) + row.locals.drop(1),
                    issued = row.issued + COMMAND, localPending = PostLocalPending(ROOT, 2),
                    publicationHold = PostDraftPublicationHoldV2(probe, PublicationCapacityReservationV1(f.policy.maxResponseBytes, reserve, 0, 1))), reserve)
                true
            } catch (failure: MealFailure) {
                if (failure.reason != FailureReason.UNAVAILABLE) throw failure
                false
            }
            // Pure deterministic fixture construction BEFORE real controller work. No mutation
            // bypass is used after allocation; actual owner/queue paths perform every edit.
            var low = 0; var high = 15_000
            while (low < high) {
                val middle = (low + high + 1) / 2
                if (fittingShadow(middle)) low = middle else high = middle - 1
            }
            assertTrue(low in 1 until 15_000)
            val seeded = seed(low)
            f.store.rows[DRAFT_KEY] = PrivateRecord(1, 2, f.codec.encode(seeded))
            val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
            f.store.beforeCommit = { batch -> if (batch.any { it.key == PUB_KEY }) PortResult.Failure(FailureReason.STORAGE_FAILURE) else null }
            failure(FailureReason.STORAGE_FAILURE, f.controller.confirmPublish(review.token))
            f.store.beforeCommit = { null }
            value(f.draftController.openLocal(ROOT))
            assertTrue(value(f.draftController.editCaption(ROOT, "Short newer edit")).selected!!.localAcknowledged)
            val beforeOverflow = f.store.rows.getValue(DRAFT_KEY)
            failure(FailureReason.UNAVAILABLE, f.draftController.editCaption(ROOT, "🍜".repeat(500)))
            assertContentEquals(beforeOverflow.payload.copyForCodec(), f.store.rows.getValue(DRAFT_KEY).payload.copyForCodec())
            assertEquals(beforeOverflow.revision, f.store.rows.getValue(DRAFT_KEY).revision)
            assertEquals(1, f.allocated); assertTrue(f.calls.isEmpty())
            val retry = value(f.controller.prepareOriginalRetry()).retry!!
            assertEquals(2L, retry.separatelyObservedCurrentLocal.localRevision)
            assertContentEquals(review.snapshot.exactProposedPostWrite.encodeUtf8(), retry.original.exactOriginalPostWrite.encodeUtf8())
            assertTrue(value(f.controller.retryOriginal(retry.token)).acknowledged)
            assertEquals("Short newer edit", (f.drafts().remainders.single().content as DraftLocalContentV1.TextV1).caption)
            assertEquals(seeded.locals.drop(1).map { it.clientDraftId }, f.drafts().locals.map { it.clientDraftId })
            assertEquals(1, f.allocated); assertEquals(1, f.calls.size)
        } finally { withContext(NonCancellable) { f.close() } }
    }

    @Test fun durablePendingObservationKeepsTheAllocatedOriginalUntilAnActualApplicationOwnsIt() = runTest {
        for (saved in listOf(false, true)) fixture(saved) { f ->
            f.online = false
            val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
            assertIs<PortResult.Failure>(f.controller.confirmPublish(review.token))
            val allocated = assertNotNull(f.capacity()).original
            assertEquals(COMMAND, allocated.commandId)
            assertContentEquals(review.snapshot.exactProposedPostWrite.encodeUtf8(), allocated.exactPostWrite.encodeUtf8())
            val commits = f.store.commits
            repeat(3) {
                val observed = value(f.controller.restore())
                assertFalse(observed.acknowledged); assertEquals(0, observed.pending!!.observedAttempts)
                assertSame(allocated, assertNotNull(f.capacity()).original)
            }
            value(f.controller.close()); value(f.draftController.close()); f.replaceController()
            assertFalse(value(f.controller.restore()).acknowledged)
            assertSame(allocated, assertNotNull(f.capacity()).original)
            val cancel = value(f.controller.prepareUnsentCancellation()).unsentCancellation!!
            assertEquals(START, cancel.original.originalCreatedAtMillis)
            assertSame(allocated, assertNotNull(f.capacity()).original)
            assertEquals(commits, f.store.commits); assertEquals(1, f.allocated); assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun changedOrDeletedDomainDuringRegistrationRecheckCannotForgetTheAllocatedOriginal() = runTest {
        for (key in listOf(PUB_KEY, DRAFT_KEY)) for (delete in listOf(false, true)) fixture { f ->
            f.online = false
            val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
            assertIs<PortResult.Failure>(f.controller.confirmPublish(review.token))
            val allocated = assertNotNull(f.capacity()).original
            val previous = f.store.rows.getValue(key)
            val readsBefore = f.store.reads
            val trigger = f.principals.resolves + 2 // enter, then the retained-original check AFTER journal reads.
            var changed = false
            f.principals.beforeResolve = {
                if (!changed && f.principals.resolves == trigger) {
                    assertTrue(f.store.reads > readsBefore)
                    changed = true
                    if (delete) f.store.rows.remove(key)
                    else f.store.rows[key] = PrivateRecord(previous.revision + 1, previous.schemaVersion,
                        PrivateBytes("{\"syntheticCorruption\":true}".encodeToByteArray()))
                }
            }
            // This observation may have read the old snapshot before the synthetic damage.
            // It must never retire the actual original, regardless of its return value.
            f.controller.restore()
            assertTrue(changed); f.principals.beforeResolve = {}
            assertSame(allocated, assertNotNull(f.capacity()).original)
            assertIs<PortResult.Failure>(f.controller.prepareOriginalRetry())
            assertSame(allocated, assertNotNull(f.capacity()).original)
            assertEquals(1, f.allocated); assertTrue(f.calls.isEmpty()); assertFalse(f.controller.states.value.acknowledged)
            // Exact fixture repair, not a production reconciliation or a manufactured ACK.
            f.store.rows[key] = previous
            val retry = value(f.controller.prepareOriginalRetry()).retry!!
            assertEquals(COMMAND, retry.original.commandId)
            assertContentEquals(review.snapshot.exactProposedPostWrite.encodeUtf8(), retry.original.exactOriginalPostWrite.encodeUtf8())
            f.online = true
            assertTrue(value(f.controller.retryOriginal(retry.token)).acknowledged)
            assertNull(f.capacity()); assertEquals(1, f.allocated); assertEquals(1, f.calls.size)
        }
    }

    @Test fun cancellationDuringRegistrationRecheckRetainsTheOriginalAcrossControllerReplacement() = runTest { fixture { f ->
        f.online = false
        val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
        assertIs<PortResult.Failure>(f.controller.confirmPublish(review.token))
        val original = assertNotNull(f.capacity()).original
        val trigger = f.principals.resolves + 2
        f.principals.beforeResolve = {
            if (f.principals.resolves == trigger) throw CancellationException("Synthetic retained-registration cancellation")
        }
        assertFailsWith<CancellationException> { f.controller.restore() }
        f.principals.beforeResolve = {}
        assertSame(original, assertNotNull(f.capacity()).original)
        value(f.controller.close()); value(f.draftController.close()); f.replaceController()
        val retry = value(f.controller.prepareOriginalRetry()).retry!!
        assertEquals(COMMAND, retry.original.commandId); assertEquals(START, retry.original.originalCreatedAtMillis)
        f.online = true
        assertTrue(value(f.controller.retryOriginal(retry.token)).acknowledged)
        assertNull(f.capacity()); assertEquals(1, f.allocated); assertEquals(1, f.calls.size)
    } }

    @Test fun failedActualApplyTransfersBeforeCommitAndRecoversFromThePrivateAttemptAfterReplacement() = runTest {
        for (saved in listOf(false, true)) fixture(saved) { f ->
            val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
            var sawTransfer = false
            f.store.beforeCommit = { batch ->
                if (f.isTerminalBatch(batch)) {
                    assertNull(f.capacityNow())
                    assertFalse(ComposerAllocationArbiter.hasPublication(f.access, f.boundary, ROOT))
                    assertEquals("RECEIPT_READY", f.metadata().getValue("phase").jsonPrimitive.content)
                    sawTransfer = true; PortResult.Failure(FailureReason.STORAGE_FAILURE)
                } else null
            }
            failure(FailureReason.STORAGE_FAILURE, f.controller.confirmPublish(review.token))
            assertTrue(sawTransfer); assertFalse(f.controller.states.value.acknowledged)
            assertNotNull(f.drafts().publicationHold); assertTrue(f.drafts().terminals.isEmpty())
            f.store.beforeCommit = { null }
            value(f.controller.close()); value(f.draftController.close()); f.replaceController()
            assertFalse(value(f.controller.restore()).acknowledged)
            failure(FailureReason.CONFLICT, f.controller.prepareReview(f.target(), f.choices()))
            val recovered = value(f.controller.finalizeOriginal())
            assertTrue(recovered.acknowledged)
            assertContentEquals(f.actualReplies.single().body!!.copyForCodec(), recovered.exactCanonicalPost!!.encodeUtf8())
            assertContentEquals(review.snapshot.exactProposedPostWrite.encodeUtf8(), f.calls.single().body!!.copyForCodec())
            assertNull(f.capacity()); assertEquals(1, f.allocated); assertEquals(1, f.calls.size)
            value(f.controller.restore())
            failure(FailureReason.CONFLICT, f.controller.finalizeOriginal())
        }
    }

    @Test fun actualCallerCancellationAfterTransferBeforeApplyKeepsOnlyRecoverableAttemptEvidence() = runTest { fixture { f ->
        val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
        var callerJob: Job? = null; var cancelledAtApply = false
        f.store.beforeCommit = { batch ->
            if (f.isTerminalBatch(batch)) {
                assertNull(f.capacityNow()); assertFalse(f.controller.states.value.acknowledged)
                cancelledAtApply = true; callerJob!!.cancel()
                PortResult.Failure(FailureReason.STORAGE_FAILURE)
            } else null
        }
        val running = async { callerJob = currentCoroutineContext().job; f.controller.confirmPublish(review.token) }
        try {
            running.join()
            assertTrue(cancelledAtApply); assertTrue(running.isCancelled)
            assertEquals("RECEIPT_READY", f.metadata().getValue("phase").jsonPrimitive.content)
            assertFalse(f.controller.states.value.acknowledged); assertNull(f.capacity())
            f.store.beforeCommit = { null }
            value(f.controller.close()); value(f.draftController.close()); f.replaceController()
            val recovered = value(f.controller.finalizeOriginal())
            assertTrue(recovered.acknowledged); assertEquals(1, f.allocated); assertEquals(1, f.calls.size)
        } finally { f.store.beforeCommit = { null }; withContext(NonCancellable) { running.cancelAndJoin() } }
    } }

    @Test fun damagedDomainAfterTransferDoesNotPermitAnotherOriginalOrEraseTheActualAttempt() = runTest { fixture { f ->
        val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
        var retained: PrivateRecord? = null
        f.store.beforeCommit = { batch ->
            if (f.isTerminalBatch(batch)) {
                assertNull(f.capacityNow()); retained = f.store.rows.remove(PUB_KEY)
                PortResult.Failure(FailureReason.STORAGE_FAILURE)
            } else null
        }
        failure(FailureReason.STORAGE_FAILURE, f.controller.confirmPublish(review.token))
        f.store.beforeCommit = { null }; assertNotNull(retained)
        assertIs<PortResult.Failure>(f.controller.finalizeOriginal())
        failure(FailureReason.CONFLICT, f.controller.prepareReview(f.target(), f.choices()))
        assertEquals(1, f.allocated); assertEquals(1, f.calls.size); assertNull(f.capacity())
        f.store.rows[PUB_KEY] = retained!! // Exact synthetic fault repair; not an application ACK.
        value(f.controller.close()); value(f.draftController.close()); f.replaceController()
        assertTrue(value(f.controller.finalizeOriginal()).acknowledged)
        assertEquals(1, f.allocated); assertEquals(1, f.calls.size)
    } }

    @Test fun unsentTransferBeforeOrAfterLostCommitKeepsRootEditableWithoutAStaleCapacityShadow() = runTest {
        for (after in listOf(false, true)) fixture { f ->
            f.online = false
            val review = value(f.controller.prepareReview(f.target(), f.choices())).review!!
            assertIs<PortResult.Failure>(f.controller.confirmPublish(review.token))
            val original = assertNotNull(f.capacity()).original
            val cancellation = value(f.controller.prepareUnsentCancellation()).unsentCancellation!!
            var sawTransfer = false
            val fault: (List<StoreMutation>) -> PortResult.Failure? = { batch ->
                if (f.isDiscardBatch(batch)) {
                    assertNull(f.capacityNow()); sawTransfer = true
                    PortResult.Failure(if (after) FailureReason.OUTCOME_UNKNOWN else FailureReason.STORAGE_FAILURE)
                } else null
            }
            if (after) f.store.afterCommit = fault else f.store.beforeCommit = fault
            failure(if (after) FailureReason.OUTCOME_UNKNOWN else FailureReason.STORAGE_FAILURE,
                f.controller.confirmUnsentCancellation(cancellation.token))
            assertTrue(sawTransfer); assertFalse(f.controller.states.value.acknowledged)
            f.store.beforeCommit = { null }; f.store.afterCommit = { null }
            value(f.controller.close()); value(f.draftController.close()); f.replaceController()
            val cancelled = value(f.controller.finalizeOriginal())
            assertTrue(cancelled.acknowledged); assertEquals(PostComposerPhase.CANCELLED_UNSENT, cancelled.phase)
            assertNull(cancelled.exactCanonicalPost); assertEquals("cancelled-unsent", cancelled.history.single().outcome)
            assertEquals(original.commandId, cancelled.history.single().original.commandId)
            assertNull(f.capacity()); assertNull(f.drafts().publicationHold); assertTrue(f.drafts().terminals.isEmpty())
            value(f.draftController.openLocal(ROOT))
            assertTrue(value(f.draftController.editCaption(ROOT, "New dinner after explicit cancellation")).selected!!.localAcknowledged)
            val next = value(f.controller.prepareReview(PublicationTarget.DirectLocal(ROOT, 2),
                f.choices("New dinner after explicit cancellation"))).review!!
            assertEquals(2L, next.snapshot.reviewedLocal.localRevision)
            assertEquals("DISCARDED", f.metadata().getValue("phase").jsonPrimitive.content)
            assertEquals(1, f.allocated); assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun concreteAttemptLookupRejectsForgedApplicationForeignSessionIssuerOwnerAndAccount() = runTest { fixture { f ->
        value(f.controller.close()); value(f.draftController.close())
        f.recreateComposition()
        val owner = PostDraftJournalOwner(f.composition, f.draftPolicy, f.policy)
        val coordinator = PostComposerCoordinator(f.composition, owner, f.draftPolicy, f.policy, f.prerequisites,
            object : PublicationControllerOperationFence {
                override suspend fun requireCurrent() = requireCurrentNow()
                override fun requireCurrentNow() { check(f.boundary.isCurrent(f.lease)) }
                override fun ownsApplicationDelivery(ticket: PublicationControllerDeliveryTicket) = false
                override fun showAllocationPending(status: PublicationAllocationStatus) = Unit
            }, MealOperationIds { f.allocated++; COMMAND })
        try { f.composition.operate(coordinator.borrower) {
            val use = coordinator.enter(f.composition.composerPermit(coordinator.borrower))
            try {
                val reviewed = coordinator.prepareReview(use, f.target(), f.choices())
                coordinator.confirm(use, reviewed.token)
                val actual = coordinator.applyReceipt(use)
                val principal = assertNotNull(f.principals.lastPrincipal)
                val original = PostComposerCoordinator.requireActualAllocationTransfer(f.access, f.boundary, principal, actual)
                assertEquals(COMMAND, original.commandId); assertNull(f.capacityNow())
                val commits = f.store.commits; val reads = f.store.reads
                fun denied(reason: FailureReason, access: AuthenticatedMealPlanningAccess = f.access,
                    boundary: SessionBoundary = f.boundary, mapped: PublicationPrincipalSnapshot = principal,
                    application: ActualPublicationApplication = actual) {
                    assertEquals(reason, assertFailsWith<MealFailure> {
                        PostComposerCoordinator.requireActualAllocationTransfer(access, boundary, mapped, application)
                    }.reason)
                }
                denied(FailureReason.CONFLICT, application = ActualPublicationApplication())
                val cloned = AuthenticatedMealPlanningAccess(f.lease, ORIGIN, f.store, f.accountTransport, true)
                denied(FailureReason.CONFLICT, access = cloned)
                denied(FailureReason.CONFLICT, boundary = SessionBoundary())
                // Deliberately forged comparison data: constructors are NOT authority or ACK.
                denied(FailureReason.STALE_SESSION, mapped = PublicationPrincipalSnapshot(Source(f.access, f.boundary),
                    principal.binding, ACCOUNT, principal.retainedOwner, principal.refreshGeneration))
                denied(FailureReason.STALE_SESSION, mapped = PublicationPrincipalSnapshot(principal.issuer,
                    principal.binding, ACCOUNT, Any(), principal.refreshGeneration))
                denied(FailureReason.STALE_SESSION, mapped = PublicationPrincipalSnapshot(principal.issuer,
                    principal.binding, NEW_ROOT, principal.retainedOwner, principal.refreshGeneration))
                assertSame(original, PostComposerCoordinator.requireActualAllocationTransfer(f.access, f.boundary, principal, actual))
                assertEquals(commits, f.store.commits); assertEquals(reads, f.store.reads)
                assertFalse(f.controller.states.value.acknowledged)
                assertEquals(1, f.allocated); assertEquals(1, f.calls.size)
            } finally { coordinator.leave(use) }
        } } finally { withContext(NonCancellable + f.dispatcher) { coordinator.close() } }
    } }

    private suspend fun TestScope.fixture(saved: Boolean = false, action: suspend (Fixture) -> Unit) {
        val f = Fixture(StandardTestDispatcher(testScheduler), saved)
        try { action(f) } finally { withContext(NonCancellable) { f.close() } }
    }
    private class QueuedCaller : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun runOne() { check(tasks.isNotEmpty()); tasks.removeFirst().run() }
        fun runAll() { repeat(100) { if (tasks.isEmpty()) return; tasks.removeFirst().run() }; error("Unexpected caller loop") }
        fun hasWork() = tasks.isNotEmpty()
    }
    private class Fixture(val dispatcher: CoroutineDispatcher, val saved: Boolean, draftRecordBytes: Int = 1_048_576) {
        val boundary = SessionBoundary(); val lease = boundary.activate(StorageScope("test", ActorKind.ACCOUNT, "opaque-storage-owner"))
        val store = Store(); var time = START; var allocated = 0; var privateAllocated = 0; var online = true
        var nextPublicationId: suspend () -> String = { COMMAND }
        val calls = mutableListOf<ApiCall>(); val actualReplies = mutableListOf<ApiReply>()
        var transport: suspend (ApiCall) -> PortResult<ApiReply> = { call -> PortResult.Value(reply(call).also { actualReplies += it }) }
        val accountTransport = object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                assertSame(this@Fixture.lease, lease); calls += call; return transport(call)
            }
        }
        val access = AuthenticatedMealPlanningAccess(lease, ORIGIN, store, accountTransport, true)
        var composition = newComposition()
            private set
        private fun newComposition() = MealKitchenComposition(access, boundary, dispatcher, EpochClock { time },
            ConnectivityPort { if (online) Connectivity.ONLINE else Connectivity.OFFLINE })
        fun recreateComposition() { composition = newComposition() }
        val draftPolicy = PostDraftClientPolicy(64, draftRecordBytes, 262_144, 4096, 4096, 20, 1000, 300_000)
        val policy = PostPublicationClientPolicy(1_048_576, 65_536, 8192, 10, 30, 10, 10, 10, 10, 65_536, 4096, 300_000)
        val snapshots = DraftLocalSnapshotCodecV1(draftPolicy, policy); val links = PublicationOriginalLinkCodecV1(policy, snapshots)
        val codec = PostDraftV2Codec("test", ORIGIN, draftPolicy, policy, snapshots, links)
        val principals = Source(access, boundary); val delivery = Delivery(principals)
        var disclosure = PublicationDisclosure(DISCLOSURE, DISPLAY); var newFailure: FailureReason? = null
        var beforeNew: suspend () -> Unit = {}
        val prerequisites = object : PostPublicationLifecyclePrerequisites {
            override val principals get() = this@Fixture.principals
            override val delivery get() = this@Fixture.delivery
            override suspend fun disclosure(owner: PublicationPrerequisiteContext) = PortResult.Value(this@Fixture.disclosure)
            override suspend fun requireNew(owner: PublicationPrerequisiteContext, check: PublicationNewPrerequisiteCheck): PortResult<Unit> {
                assertEquals(ACCOUNT, owner.verifiedAccountUserId); assertSame(lease, owner.lease)
                beforeNew()
                return newFailure?.let { PortResult.Failure(it) } ?: PortResult.Value(Unit)
            }
            override suspend fun requireOriginalReplay(owner: PublicationPrerequisiteContext, check: PublicationReplayPrerequisiteCheck): PortResult<Unit> {
                assertTrue(check.observedAttempts > 0); assertEquals(ACCOUNT, owner.verifiedAccountUserId); return PortResult.Value(Unit)
            }
        }
        val savePrerequisites = object : ReviewedDraftSavePrerequisites {
            override val principals get() = this@Fixture.principals
            override val delivery get() = this@Fixture.delivery
            override suspend fun disclosure(owner: ReviewedDraftPrerequisiteContext): PortResult<PublicationDisclosure> {
                assertSame(lease, owner.lease); assertEquals(ACCOUNT, owner.principal.canonicalUserId)
                return PortResult.Value(this@Fixture.disclosure)
            }
            override suspend fun requireNewSave(owner: ReviewedDraftPrerequisiteContext, check: ReviewedDraftNewSaveCheck): PortResult<Unit> {
                assertSame(lease, owner.lease); assertEquals(owner.clientDraftId, check.target.clientDraftId)
                return PortResult.Value(Unit) // Explicit synthetic private-save prerequisite, not publish eligibility.
            }
            override suspend fun requireOriginalReplay(owner: ReviewedDraftPrerequisiteContext, check: ReviewedDraftOriginalReplayCheck): PortResult<Unit> {
                assertSame(lease, owner.lease); assertTrue(check.observedAttempts > 0)
                return PortResult.Value(Unit)
            }
        }
        lateinit var controller: PostComposerController
        lateinit var draftController: PostDraftController
        init {
            val association = if (saved) DraftServerAssociationV1.Observed(WireDocument.parse(draft().toString()), "\"9007199254740993\"")
                else DraftServerAssociationV1.NotObserved
            val local = snapshots.create(ROOT, 1, DraftLocalContentV1.TextV1(CAPTION, null), association)
            // Explicit synthetic initial retained row, validated by the COMPLETE actual codec.
            store.rows[DRAFT_KEY] = PrivateRecord(1, 2, codec.encode(PostDraftV2Record(START, listOf(local), listOf(ROOT))))
            replaceController()
        }
        fun replaceController() {
            // Closing the last borrower intentionally retires the OLD composition. Replace
            // its wrapper, not the retained access/lease/store/boundary or any actual evidence.
            if (::controller.isInitialized) recreateComposition()
            val owner = PostDraftJournalOwner(composition, draftPolicy, policy)
            draftController = PostDraftController(composition, MealOperationIds { privateAllocated++; NEW_ROOT }, draftPolicy, owner, savePrerequisites)
            controller = PostComposerController(composition, owner, draftPolicy, policy, prerequisites, MealOperationIds { allocated++; nextPublicationId() })
        }
        fun target(): PublicationTarget = if (saved) PublicationTarget.SavedDraft(ROOT, 1, DRAFT, ExactPostVersion("9007199254740993"), "\"9007199254740993\"")
            else PublicationTarget.DirectLocal(ROOT, 1)
        fun choices(caption: String = CAPTION) = ReviewedPostChoices(caption, OptionalValue.Absent, emptyList(), PublicationAudience.OnlyYou, false,
            OptionalValue.Absent, false, disclosure, OptionalValue.Absent)
        suspend fun armTicket(ticket: PublicationControllerDeliveryTicket, owner: Any, application: ActualPublicationApplication,
            before: PostComposerState, projected: PostComposerState, flow: MutableStateFlow<PostComposerState>): Boolean {
            // Ticket-order fixture only, but registration and immediate binding still use a
            // real inherited operation and exact purpose-fixed principal admission.
            // Its separate wrapper avoids a second POST_PUBLICATIONS borrower in the actual
            // controller's composition. It shares only the exact retained session identity.
            val ticketComposition = newComposition()
            val borrower = ticketComposition.bind(MealKitchenFeature.POST_PUBLICATIONS, object : MealKitchenHooks {
                override suspend fun checkCurrent() {
                    if (!boundary.isCurrent(lease)) mealFail(FailureReason.STALE_SESSION)
                }
            })
            val mapped = PublicationPrincipalAdmission(ticketComposition, borrower, principals)
            val admitted = PrincipalDeliveryAdmission(mapped, delivery)
            return try { ticketComposition.operate(borrower) {
                val permit = ticketComposition.composerPermit(borrower); val principal = mapped.resolve(permit)
                val witness = admitted.capture(permit, principal)
                val gate = admitted.registerDelivery(permit, principal, witness)
                ticket.arm(owner, application, before, projected, flow, gate).also { if (!it) gate.cancel() }
            } } finally { withContext(NonCancellable + dispatcher) { ticketComposition.release(borrower) } }
        }
        suspend fun publish(): PostComposerState = value(controller.confirmPublish(value(controller.prepareReview(target(), choices())).review!!.token))
        suspend fun capacity(): PublicationAllocationCapacity? = withContext(dispatcher) { capacityNow() }
        fun capacityNow() = PublicationAllocationRegistry.capacityCandidate(access, boundary)
        fun isTerminalBatch(batch: List<StoreMutation>): Boolean = batch.filterIsInstance<StoreMutation.Put>()
            .singleOrNull { it.key == DRAFT_KEY }?.let { codec.decode(2, it.payload).terminals.isNotEmpty() } == true
        fun isDiscardBatch(batch: List<StoreMutation>): Boolean = batch.filterIsInstance<StoreMutation.Put>()
            .singleOrNull { it.key == RecordKey("feedme.command.metadata", COMMAND) }?.let {
                Json.parseToJsonElement(it.payload.copyForCodec().decodeToString()).jsonObject["phase"]?.jsonPrimitive?.content == "DISCARDED"
            } == true
        fun drafts() = codec.decode(2, store.rows.getValue(DRAFT_KEY).payload)
        fun metadata() = Json.parseToJsonElement(store.rows.getValue(RecordKey("feedme.command.metadata", COMMAND)).payload.copyForCodec().decodeToString()).jsonObject
        suspend fun close() { controller.close(); draftController.close(); delivery.revokeBeforeMutation(); boundary.clear() }
    }
    private class Source(val actual: AuthenticatedMealPlanningAccess, val boundary: SessionBoundary) : PostPublicationPrincipalIntegration() {
        var owner: Any = Any(); var generation: Any = Any(); var resolves = 0; var syncChecks = 0
        var resolveFailure: FailureReason? = null
        var beforeResolve: suspend () -> Unit = {}
        var lastPrincipal: PublicationPrincipalSnapshot? = null
        override suspend fun resolve(binding: PublicationSessionBinding): PortResult<PublicationPrincipalSnapshot> {
            resolves++; beforeResolve()
            return resolveFailure?.let { PortResult.Failure(it) }
                ?: PortResult.Value(mappedPrincipal(binding, ACCOUNT, owner, generation).also { lastPrincipal = it })
        }
        override suspend fun requireCurrent(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot) =
            if (isCurrent(binding, principal)) PortResult.Value(Unit) else PortResult.Failure(FailureReason.STALE_SESSION)
        override fun isCurrent(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot): Boolean {
            syncChecks++; return matchesSession(binding, actual, boundary) && matchesCurrentPrincipal(binding, principal, owner, generation)
        }
    }
    private class Delivery(source: Source) : PostPublicationDeliveryIntegration(source, maxPendingDeliveries = 2) {
        var lastWitness: PublicationDeliveryWitness? = null
        fun revokeBeforeMutation() = revokeCurrent()
        override fun capture(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot) =
            PortResult.Value(witness(binding, principal, generationFor(principal)).also { lastWitness = it })
    }
    private class Store : PrivateStateStore {
        val rows = mutableMapOf<RecordKey, PrivateRecord>(); var reads = 0; var commits = 0
        var beforeCommit: (List<StoreMutation>) -> PortResult.Failure? = { null }
        var afterCommit: (List<StoreMutation>) -> PortResult.Failure? = { null }
        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> { reads++; return PortResult.Value(rows[key]) }
        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> {
            beforeCommit(mutations)?.let { return it }
            if (mutations.map { it.key }.distinct().size != mutations.size || mutations.any { rows[it.key]?.revision != it.expectedRevision })
                return PortResult.Failure(FailureReason.CONFLICT)
            val ack = mutableMapOf<RecordKey, Long?>()
            for (mutation in mutations) when (mutation) {
                is StoreMutation.Put -> { val revision = (mutation.expectedRevision ?: 0) + 1
                    rows[mutation.key] = PrivateRecord(revision, mutation.schemaVersion, mutation.payload); ack[mutation.key] = revision }
                is StoreMutation.Delete -> { rows.remove(mutation.key); ack[mutation.key] = null }
            }
            commits++; return afterCommit(mutations) ?: PortResult.Value(ack)
        }
        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> = error("No erasure is in this task")
    }
    private companion object {
        const val START = 1_789_387_200_000L
        const val ROOT = "aaaaaaaa-1000-4000-8000-000000000001"
        const val COMMAND = "aaaaaaaa-1000-4000-8000-000000000002"
        const val DRAFT = "aaaaaaaa-1000-4000-8000-000000000003"
        const val POST = "aaaaaaaa-1000-4000-8000-000000000004"
        const val ACCOUNT = "aaaaaaaa-1000-4000-8000-000000000005"
        const val ORIGIN = "aaaaaaaa-1000-4000-8000-000000000006"
        const val AVATAR = "aaaaaaaa-1000-4000-8000-000000000007"
        const val NEW_ROOT = "aaaaaaaa-1000-4000-8000-000000000008"
        const val NEXT_COMMAND = "aaaaaaaa-1000-4000-8000-000000000009"
        const val CAPTION = "Synthetic reviewed healthy dinner"
        const val DISCLOSURE = "synthetic-disclosure-v1"
        const val DISPLAY = "Actual synthetic review disclosure, not a production policy."
        val DRAFT_KEY = RecordKey("mealflow.post-drafts.v1", ORIGIN)
        val PUB_KEY = RecordKey("mealflow.post-publications.v1", ORIGIN)
        fun json(value: WireDocument) = Json.parseToJsonElement(value.encodeUtf8().decodeToString()).jsonObject
        fun draft() = buildJsonObject {
            put("id", DRAFT); put("clientDraftId", ROOT); put("caption", CAPTION); put("mediaIds", JsonArray(emptyList()))
            put("audience", buildJsonObject { put("kind", "self"); put("circleIds", JsonArray(emptyList())) })
            put("keepOnPlate", false); put("allowRecipeSaves", false); put("saveDisclosureVersion", DISCLOSURE)
            put("version", Json.parseToJsonElement("9007199254740993")); put("status", "draft")
            put("createdAt", "2026-09-14T12:00:00Z"); put("updatedAt", "2026-09-14T12:00:00Z"); put("expiresAt", "2026-09-15T12:00:00Z")
        }
        fun reply(call: ApiCall): ApiReply {
            val selected = json(PostPublicationAdapter(8192).expectedSelection(call))
            val post = buildJsonObject {
                put("id", POST); put("version", 1); put("createdAt", "2026-09-14T12:00:00Z"); put("updatedAt", "2026-09-14T12:00:00Z")
                put("author", buildJsonObject { put("userId", ACCOUNT); put("displayName", "Synthetic Cook"); put("handle", "synthetic"); put("avatarMediaId", AVATAR) })
                for (field in listOf("caption", "altText", "mediaIds", "keepOnPlate", "attachment", "sourcePostId")) selected[field]?.let { put(field, it) }
                put("audience", JsonObject(selected.getValue("audience").jsonObject + ("bindings" to JsonArray(emptyList()))))
                put("status", "published"); put("publishedAt", "2026-09-14T12:00:00Z"); put("expiresAt", "2026-09-15T12:00:00Z")
                put("savePolicy", buildJsonObject { put("allowFutureSaves", selected.getValue("allowRecipeSaves")); put("policyVersion", 1)
                    put("disclosureVersion", selected.getValue("saveDisclosureVersion")) })
                put("aclVersion", 1); put("capabilities", JsonArray(listOf(JsonPrimitive("view"), JsonPrimitive("delete"))))
                put("reactionCounts", JsonArray(emptyList()))
            }
            return ApiReply(201, PrivateBytes(post.toString().encodeToByteArray()), contentType = "application/json", etag = "\"1\"")
        }
        fun <T> value(result: PortResult<T>): T = when (result) {
            is PortResult.Value -> result.value
            is PortResult.Failure -> fail("Expected success, got ${result.reason}")
        }
        fun failure(reason: FailureReason, result: PortResult<*>) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
    }
}
