package com.feedme.mealflow

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.kitchen.SavedRecipeAvailability
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.coroutines.CoroutineContext

/** Synthetic canonical content over the actual shared repositories/journal, not native or rights evidence. */
@OptIn(ExperimentalCoroutinesApi::class)
class CookbookControllerTest {
    @Test fun explicitSaveUsesPersistedSelectedPlanOriginalKeyAndActualAtomicRepositoryReceipt() = runTest { fixture { f ->
        val state = f.book.saveSelectedPlan().value()
        val call = f.writes().single()
        assertEquals("saveRecipe", call.operationId); assertNull(call.ifMatch)
        assertEquals(setOf("planId"), call.document().json().jsonObject.keys)
        assertEquals(PLAN, call.document().json().jsonObject.getValue("planId").jsonPrimitive.content)
        assertEquals(SAVED, state.selected!!.id); assertNotNull(state.selected!!.localRevision)
        assertTrue(state.serverAcknowledged); assertNull(state.pending); assertEquals(1, f.idCalls)
        assertFalse(f.calls.any { it.operationId == "getSavedRecipe" })
        assertTrue(f.store.records.keys.any { it.collection == "feedme.command.metadata" })
    } }
    @Test fun restoreBackAndLocalSearchNeverDispatchOrInventDownloadedRows() = runTest { fixture { f ->
        f.book.restore().value(); f.book.back().value(); val state = f.book.searchDownloaded().value()
        assertTrue(state.localOnly); assertTrue(state.items.isEmpty()); assertTrue(f.calls.isEmpty()); assertEquals(0, f.idCalls)
    } }
    @Test fun unsavedFormInvalidatesControllerSaveBeforeIdStoreOrTransport() = runTest { fixture { f ->
        f.meals.draftReadiness.invalidate(); val writes = f.store.writes
        failure(f.book.saveSelectedPlan(), FailureReason.CONFLICT)
        assertEquals(0, f.idCalls); assertEquals(writes, f.store.writes); assertTrue(f.calls.isEmpty())
        f.meals.edit(draft()).value(); assertTrue(f.meals.draftReadiness.isReady); f.book.saveSelectedPlan().value()
    } }
    @Test fun unknownDraftAcknowledgementCannotBeReleasedByReadOnlyRestoreOrReplacement() = runTest { fixture { f ->
        f.meals.draftReadiness.invalidate(); f.store.failAfter = { it.any { m -> m.key == MEAL_KEY } }
        failure(f.meals.edit(draft()), FailureReason.OUTCOME_UNKNOWN)
        f.meals.restore().value(); assertFalse(f.meals.draftReadiness.isReady)
        f.reopen(); assertFalse(f.meals.draftReadiness.isReady)
        failure(f.book.saveSelectedPlan(), FailureReason.CONFLICT); assertTrue(f.calls.isEmpty())
        f.meals.edit(draft()).value(); f.book.saveSelectedPlan().value()
    } }
    @Test fun invalidatedDraftWhileIdIsSuspendedCannotEnqueueOldPlan() = runTest { fixture { f ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.beforeId = { entered.complete(Unit); release.await() }
        val task = async { f.book.saveSelectedPlan() }; entered.await(); f.meals.draftReadiness.invalidate(); release.complete(Unit)
        failure(task.await(), FailureReason.CONFLICT)
        assertTrue(f.writes().isEmpty()); assertNull(f.store.records[BOOK_KEY])
    } }
    @Test fun cancelledDraftCommitCannotMintSaveReadinessFromItsVisibleBytes() = runTest { fixture { f ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.meals.draftReadiness.invalidate()
        f.store.afterCommit = { changes -> if (changes.any { it.key == MEAL_KEY }) {
            entered.complete(Unit); withContext(NonCancellable) { release.await() }
        } }
        val task = async { f.meals.edit(draft()) }; entered.await(); task.cancel(); release.complete(Unit)
        assertFailsWith<CancellationException> { task.await() }
        f.meals.restore().value(); assertFalse(f.meals.draftReadiness.isReady)
        failure(f.book.saveSelectedPlan(), FailureReason.CONFLICT); assertTrue(f.writes().isEmpty())
        f.store.afterCommit = {}; f.meals.edit(draft()).value(); assertTrue(f.meals.draftReadiness.isReady)
    } }
    @Test fun changedPersistedMealRevisionDuringReservationRejectsMixedSource() = runTest { fixture { f ->
        var changed = false
        f.store.afterRead = { key -> if (!changed && key.collection.contains("saved")) {
            changed = true; val old = f.store.records.getValue(MEAL_KEY); f.store.records[MEAL_KEY] = old.copy(revision = old.revision + 1)
        } }
        failure(f.book.saveSelectedPlan(), FailureReason.CONFLICT); assertTrue(f.writes().isEmpty())
    } }
    @Test fun pendingPreferencesAndMismatchedDraftNeverGrantCopy() = runTest {
        fixture { f -> KitchenInputPreferenceGuard.block(f.lease, ORIGIN, f.boundary)
            failure(f.book.saveSelectedPlan(), FailureReason.CONFLICT); assertTrue(f.writes().isEmpty()) }
        fixture { f -> f.meals.edit(draft("2")).value()
            failure(f.book.saveSelectedPlan(), FailureReason.CONFLICT); assertEquals(0, f.idCalls) }
    }
    @Test fun foreignReadinessCannotBePairedToOtherActualControllerOrStore() = runTest { fixture { f ->
        val other = MealRequestController(f.access, f.boundary, f.dispatcher, EpochClock { f.time }, f.connectivity, f.ids, MEAL_POLICY)
        try { assertFailsWith<IllegalArgumentException> { MealKitchenControllers.create(f.access, f.boundary, f.dispatcher,
            EpochClock { f.time }, f.connectivity, f.ids, other, f.meals.draftReadiness, COOK_POLICY, BOOK_POLICY) } }
        finally { other.close() }
        val otherTransport = object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> =
                error("A foreign access must be rejected before transport")
        }
        for (store in listOf(f.store, Store(f.scope))) {
            val foreign = AuthenticatedMealPlanningAccess(f.lease, ORIGIN, store, otherTransport, true)
            assertFailsWith<IllegalArgumentException> {
                MealKitchenControllers.create(foreign, f.boundary, f.dispatcher, EpochClock { f.time },
                    f.connectivity, f.ids, f.meals, f.meals.draftReadiness, COOK_POLICY, BOOK_POLICY)
            }
        }
        assertTrue(f.calls.isEmpty()); assertEquals(0, f.store.writes)
    } }
    @Test fun offlineSaveIsOnlyExactEncryptedUnsentIntentAndExplicitDiscardReleasesReservation() = runTest { fixture { f ->
        f.online = false; val state = f.book.saveSelectedPlan("My title").value()
        assertEquals(CookbookPhase.OFFLINE, state.phase); assertTrue(state.pending!!.canDiscardUnsent)
        assertTrue(f.calls.isEmpty()); assertNotNull(f.record().command)
        val discarded = f.book.discardUnsent().value(); assertNull(discarded.pending); assertNull(f.record().command)
        assertTrue(f.calls.isEmpty()); f.online = true; f.book.saveSelectedPlan().value()
    } }
    @Test fun unknownEnqueueCannotAutoSendAndOriginalRetryKeepsKeyBody() = runTest { fixture { f ->
        f.store.failAfter = { it.any { m -> m.key == BOOK_KEY } && it.size > 1 }
        failure(f.book.saveSelectedPlan(), FailureReason.OUTCOME_UNKNOWN); assertTrue(f.calls.isEmpty())
        val original = f.record().command!!; f.book.restore().value(); f.book.retryOriginal().value()
        assertEquals(original.id, f.writes().single().idempotencyKey!!.use { it })
        assertContentEquals(original.body!!.encodeUtf8(), f.writes().single().body!!.copyForCodec())
    } }
    @Test fun lostTransportReplyRetriesSameCommandAfterBackoffAndCannotDiscardAttempt() = runTest { fixture { f ->
        var lost = true
        f.handler = { call -> if (call.operationId == "saveRecipe" && lost) { lost = false; PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) } else f.reply(call) }
        f.book.saveSelectedPlan().value(); val first = f.writes().single()
        failure(f.book.discardUnsent(), FailureReason.CONFLICT)
        f.time += 60_000; f.book.retryOriginal().value()
        assertEquals(first.idempotencyKey!!.use { it }, f.writes().last().idempotencyKey!!.use { it })
        assertContentEquals(first.body!!.copyForCodec(), f.writes().last().body!!.copyForCodec()); assertEquals(1, f.idCalls)
    } }
    @Test fun malformedOrWrongRecipeSuccessRetainsReceiptWithoutInventingSavedBundle() = runTest {
        for (bad in listOf("id", "recipe", "content")) fixture { f ->
            f.handler = { call -> if (call.operationId == "saveRecipe") when (bad) {
                "content" -> PortResult.Value(ApiReply(201, PrivateBytes("{}".encodeToByteArray()), contentType = "text/plain"))
                "recipe" -> response(changed(f.saved, "snapshot", changed(f.recipe, "title", JsonPrimitive("Divergent")).json()), 201)
                else -> response(changed(f.saved, "sourceType", JsonPrimitive("social")), 201)
            } else f.reply(call) }
            val result = f.book.saveSelectedPlan()
            assertTrue(result is PortResult.Failure || result.value().pending != null)
            assertNull(f.book.states.value.selected); assertFalse(f.book.states.value.serverAcknowledged)
        }
    }
    @Test fun unknownApplyRequiresFreshExactOriginalArchiveAcknowledgementWithoutSecondPost() = runTest { fixture { f ->
        f.store.failAfter = { changes -> changes.any { it.key == BOOK_KEY } && changes.any { it is StoreMutation.Delete } }
        failure(f.book.saveSelectedPlan(), FailureReason.OUTCOME_UNKNOWN)
        assertNull(f.record().command); assertFalse(f.book.states.value.serverAcknowledged)
        val prior = f.store.records.getValue(BOOK_KEY).revision
        failure(f.book.discardUnsent(), FailureReason.CONFLICT)
        val resolved = f.book.retryOriginal().value()
        assertTrue(resolved.serverAcknowledged); assertNull(resolved.pending)
        assertTrue(f.store.records.getValue(BOOK_KEY).revision > prior); assertEquals(1, f.writes().size)
    } }
    @Test fun changedArchiveCannotReleaseLostApplyProofViaRetryDiscardOrNewSave() = runTest { fixture { f ->
        f.store.failAfter = { changes -> changes.any { it.key == BOOK_KEY } && changes.any { it is StoreMutation.Delete } }
        failure(f.book.saveSelectedPlan(), FailureReason.OUTCOME_UNKNOWN)
        val archive = f.store.records.keys.single { it.collection == "feedme.command.metadata" }
        val original = f.store.records.getValue(archive)
        f.store.records[archive] = original.copy(payload = PrivateBytes("{}".encodeToByteArray()))
        val writes = f.store.writes
        assertIs<PortResult.Failure>(f.book.retryOriginal()); failure(f.book.discardUnsent(), FailureReason.CONFLICT)
        failure(f.book.saveSelectedPlan(), FailureReason.CONFLICT); assertEquals(writes, f.store.writes)
    } }
    @Test fun repeatedUnknownFinalizationKeepsProofAndReacksLaterRevision() = runTest { fixture { f ->
        f.store.failAfter = { changes -> changes.any { it.key == BOOK_KEY } && changes.any { it is StoreMutation.Delete } }
        failure(f.book.saveSelectedPlan(), FailureReason.OUTCOME_UNKNOWN)
        f.store.failAfter = { it.size == 1 && it.single().key == BOOK_KEY }
        failure(f.book.retryOriginal(), FailureReason.OUTCOME_UNKNOWN)
        f.book.retryOriginal().value(); assertTrue(f.book.states.value.serverAcknowledged); assertEquals(1, f.writes().size)
    } }
    @Test fun remotePagesHaveNoLocalRevisionAndAreNotAutomaticDownloads() = runTest { fixture { f ->
        val state = f.book.load().value()
        assertEquals(1, state.items.size); assertNull(state.items.single().localRevision)
        assertEquals("listSavedRecipes", f.calls.single().operationId)
        assertEquals(mapOf("limit" to listOf("2")), f.calls.single().queryParameters)
        assertEquals(0, f.store.writes)
        f.book.open(SAVED).value(); assertNull(f.book.states.value.selected!!.localRevision)
        f.book.download().value(); assertNotNull(f.book.states.value.selected!!.localRevision)
    } }
    @Test fun searchQueryAndOpaqueCursorStayExactAndPagesNeverMergeAbsentRowsAsDeleted() = runTest { fixture { f ->
        var pageNumber = 0
        f.handler = { call -> if (call.operationId == "listSavedRecipes") {
            pageNumber++; response(page(if (pageNumber == 1) listOf(f.saved) else emptyList(), if (pageNumber == 1) "exact:cursor" else null))
        } else f.reply(call) }
        val first = f.book.load("").value(); assertTrue(first.hasMore)
        val second = f.book.more().value(); assertTrue(second.items.isEmpty()); assertFalse(second.hasMore)
        assertEquals(listOf(""), f.calls.first().queryParameters["q"])
        assertEquals(listOf("exact:cursor"), f.calls.last().queryParameters["cursor"])
        assertEquals(0, f.store.writes)
    } }
    @Test fun duplicatePageIdsAndCursorLoopsFailClosed() = runTest { fixture { f ->
        f.handler = { response(page(listOf(f.saved), "repeat")) }
        f.book.load().value(); failure(f.book.more(), FailureReason.INVALID_DATA)
    } }
    @Test fun staleSearchCannotReplaceNewerQueryOrPublishPrivateDataAfterBack() = runTest { fixture { f ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.handler = { entered.complete(Unit); withContext(NonCancellable) { release.await() }; response(page(listOf(f.saved))) }
        val task = async { f.book.load("old") }; entered.await(); f.book.back().value(); release.complete(Unit)
        failure(task.await(), FailureReason.STALE_SESSION); assertEquals(CookbookScreen.HIDDEN, f.book.states.value.screen)
        assertTrue(f.book.states.value.items.isEmpty())
    } }
    @Test fun uncachedHigherVersionLifecycleChangePreservesExactContentAndIsAccepted() = runTest { fixture { f ->
        val first = f.book.open(SAVED).value().selected!!; assertNull(first.localRevision)
        val recipe = changed(changed(changed(f.recipe, "version", JsonPrimitive(2)), "reviewStatus", JsonPrimitive("retired")),
            "updatedAt", JsonPrimitive("2026-09-14T00:00:00Z"))
        f.saved = changed(changed(changed(f.saved, "version", JsonPrimitive(2)), "snapshot", recipe.json()), "updatedAt", JsonPrimitive("2026-09-14T00:00:00Z"))
        val next = f.book.refreshDetail().value().selected!!
        assertNull(next.localRevision); assertEquals("retired", next.savedRecipe!!.snapshot.reviewStatus)
        assertEquals(0, f.store.writes)
    } }
    @Test fun uncachedOuterDowngradeOrHigherVersionContentReplacementIsRejected() = runTest {
        for (change in listOf("outer-downgrade", "recipe-downgrade", "title", "instruction")) fixture { f ->
            f.saved = changed(f.saved, "version", JsonPrimitive(2))
            f.book.open(SAVED).value()
            f.saved = when (change) {
                "outer-downgrade" -> changed(f.saved, "version", JsonPrimitive(1))
                "title" -> changed(changed(f.saved, "version", JsonPrimitive(3)), "title", JsonPrimitive("Replacement title"))
                "recipe-downgrade" -> changed(changed(f.saved, "version", JsonPrimitive(3)), "snapshot", changed(f.recipe, "version", JsonPrimitive(0)).json())
                else -> changed(changed(f.saved, "version", JsonPrimitive(3)), "snapshot", changed(f.recipe, "ingredients", JsonArray(emptyList())).json())
            }
            assertIs<PortResult.Failure>(f.book.refreshDetail()); assertEquals(0, f.store.writes)
        }
    }
    @Test fun uncachedProvenanceMayDisappearButNeverChangeOrReappear() = runTest {
        for (change in listOf("changed", "removed-then-restored")) fixture { f ->
            f.saved = changed(f.saved, "creatorLabel", JsonPrimitive("Original attribution"))
            f.book.open(SAVED).value()
            if (change == "removed-then-restored") {
                f.saved = doc(JsonObject(changed(f.saved, "version", JsonPrimitive(2)).json().jsonObject - "creatorLabel"))
                f.book.refreshDetail().value()
            }
            f.saved = changed(changed(f.saved, "version", JsonPrimitive(3)), "creatorLabel", JsonPrimitive("Replacement attribution"))
            failure(f.book.refreshDetail(), FailureReason.CONFLICT)
        }
    }
    @Test fun deleteRequiresExactCurrentConsentAndCanonicalOriginalIfMatch() = runTest { fixture { f ->
        f.book.saveSelectedPlan().value(); val prepared = f.book.prepareDelete(SAVED).value().deleteConfirmation!!
        assertEquals(1, f.writes().size); val result = f.book.confirmDelete(prepared).value()
        val call = f.writes().last(); assertEquals("deleteSavedRecipe", call.operationId)
        assertEquals("\"1\"", call.ifMatch); assertNull(call.body)
        assertEquals(mapOf("savedRecipeId" to SAVED), call.pathParameters)
        assertNull(result.selected); assertTrue(result.serverAcknowledged)
        assertTrue(f.store.records.keys.any { it.collection.contains("deleted") })
        assertTrue(f.book.searchDownloaded().value().items.isEmpty())
    } }
    @Test fun dismissedExpiredForeignOrRefreshChangedDeleteTicketNeverDispatches() = runTest {
        for (action in listOf("dismiss", "expire", "refresh")) fixture { f ->
            val ticket = f.book.prepareDelete(SAVED).value().deleteConfirmation!!
            when (action) { "dismiss" -> f.book.dismissDelete().value(); "expire" -> f.time += 60_001; else -> f.book.refreshDetail().value() }
            failure(f.book.confirmDelete(ticket), FailureReason.CONFLICT); assertTrue(f.writes().isEmpty())
        }
    }
    @Test fun deleteVersionConflictRemainsOriginalWithoutRebaseOrLocalRemoval() = runTest { fixture { f ->
        f.book.saveSelectedPlan().value()
        f.handler = { call -> if (call.operationId == "deleteSavedRecipe") problem(412, "VERSION_CONFLICT") else f.reply(call) }
        val ticket = f.book.prepareDelete(SAVED).value().deleteConfirmation!!
        f.book.confirmDelete(ticket).value(); assertNotNull(f.record().command)
        assertNotNull(f.book.searchDownloaded().value().items.single().savedRecipe)
        f.book.retryOriginal().value(); assertEquals(1, f.writes().count { it.operationId == "deleteSavedRecipe" })
    } }
    @Test fun cookingAndCookbookShareJournalButSaveNeverDrainsPriorCookingCommand() = runTest { fixture { f ->
        f.bundle.cooking.prepareStart(PLAN).value()
        f.store.failAfter = { changes -> changes.size > 1 && changes.any { it.key.collection == "mealflow.cooking.v1" } }
        failure(f.bundle.cooking.confirmStart(), FailureReason.OUTCOME_UNKNOWN)
        val cookingKey = RecordKey("mealflow.cooking.v1", ORIGIN)
        val cooking = CookingFlowCodec(ORIGIN, COOK_POLICY).decode(f.store.records.getValue(cookingKey).payload)
        assertEquals(CookingStartStage.QUEUED, cooking.start!!.stage)
        val retained = listOf(cookingKey, RecordKey("feedme.command.metadata", cooking.start.id),
            RecordKey("feedme.command.request", cooking.start.id)).associateWith { key ->
            f.store.records.getValue(key).let { it.copy(payload = PrivateBytes(it.payload.copyForCodec())) }
        }
        val result = f.book.saveSelectedPlan().value()
        assertTrue(result.serverAcknowledged); assertNull(result.pending)
        assertEquals(SAVED, result.selected!!.id); assertNotNull(result.selected!!.localRevision)
        for ((key, original) in retained) {
            val current = f.store.records.getValue(key)
            assertEquals(original.revision, current.revision); assertEquals(original.schemaVersion, current.schemaVersion)
            assertContentEquals(original.payload.copyForCodec(), current.payload.copyForCodec())
        }
        assertEquals(2, f.store.records.keys.count { it.collection == "feedme.command.metadata" })
        assertEquals("saveRecipe", f.writes().single().operationId)
        assertTrue(f.calls.none { it.operationId == "createCookSession" })
        assertNull(f.record().command)
    } }
    @Test fun callerReturnBackOrCloseReopenCannotConsumeUndeliveredOriginalApplyProof() = runTest {
        for (change in listOf("back", "reopen")) fixture { f ->
            val scheduled = StandardTestDispatcher(testScheduler)
            val held = ArrayDeque<Pair<CoroutineContext, Runnable>>(); var hold = false
            val caller = object : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (hold) held.addLast(context to block) else scheduled.dispatch(context, block)
            } }
            val task = async(caller) { hold = true; f.book.saveSelectedPlan() }
            runCurrent(); assertEquals(1, held.size); assertNotNull(CookbookProofs.get(f.access, f.boundary))
            if (change == "back") f.book.back().value() else f.reopen()
            assertNotNull(CookbookProofs.get(f.access, f.boundary))
            hold = false; while (held.isNotEmpty()) { val (context, block) = held.removeFirst(); scheduled.dispatch(context, block) }
            failure(task.await(), FailureReason.STALE_SESSION)
            assertNotNull(CookbookProofs.get(f.access, f.boundary)); assertFalse(f.book.states.value.serverAcknowledged)
            val result = f.book.retryOriginal().value()
            assertTrue(result.serverAcknowledged); assertNull(CookbookProofs.get(f.access, f.boundary)); assertEquals(1, f.writes().size)
        }
    }
    @Test fun cancelledFinalCallerReturnRetainsProofAndLeaseInvalidationCannotRepublishPrivateCopy() = runTest {
        for (change in listOf("cancel", "lease")) fixture { f ->
            val scheduled = StandardTestDispatcher(testScheduler)
            val held = ArrayDeque<Pair<CoroutineContext, Runnable>>(); var hold = false
            val caller = object : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (hold) held.addLast(context to block) else scheduled.dispatch(context, block)
            } }
            val task = async(caller) { hold = true; f.book.saveSelectedPlan() }; runCurrent()
            // Release the first caller return only, so the second final publication return is gated.
            val first = held.removeFirst(); scheduled.dispatch(first.first, first.second); runCurrent(); assertEquals(1, held.size)
            assertNotNull(CookbookProofs.get(f.access, f.boundary))
            if (change == "cancel") task.cancel() else f.boundary.activate(f.scope)
            hold = false; while (held.isNotEmpty()) { val (context, block) = held.removeFirst(); scheduled.dispatch(context, block) }
            if (change == "cancel") {
                assertFailsWith<CancellationException> { task.await() }; assertNotNull(CookbookProofs.get(f.access, f.boundary))
                f.book.retryOriginal().value(); assertEquals(1, f.writes().size)
            } else {
                failure(task.await(), FailureReason.STALE_SESSION); assertNull(f.book.states.value.selected)
                assertTrue(f.book.states.value.items.isEmpty()); assertNull(CookbookProofs.get(f.access, f.boundary))
            }
        }
    }
    @Test fun deliveryTicketRevocationAndExactPublicationIdentityArbitrateWithoutStructuralAba() {
        val boundary = SessionBoundary(); val lease = boundary.activate(StorageScope("fixture", ActorKind.ACCOUNT, "delivery"))
        val original = Any(); val other = Any()
        val revoked = CookbookDelivery(lease, Any()); assertTrue(revoked.arm(null, original)); revoked.revoke()
        assertFalse(revoked.deliver(null, original))
        val delivered = CookbookDelivery(lease, Any()); assertTrue(delivered.arm(null, original))
        assertFalse(delivered.deliver(null, other)); assertTrue(delivered.deliver(null, original))
        delivered.revoke(); assertFalse(delivered.deliver(null, original))
        val before = CookbookState.empty(); val later = CookbookState.empty()
        val state = kotlinx.coroutines.flow.MutableStateFlow(before); state.value = later
        assertFalse(state.compareAndSet(before, CookbookState.empty())); assertSame(later, state.value)
        boundary.clear()
    }
    @Test fun nonCooperativeSaveCancellationKeepsExactJournalWithoutLateApply() = runTest { fixture { f ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.handler = { call -> if (call.operationId == "saveRecipe") { entered.complete(Unit); withContext(NonCancellable) { release.await() } }; f.reply(call) }
        val task = async { f.book.saveSelectedPlan() }; entered.await(); task.cancel(); release.complete(Unit)
        assertFailsWith<CancellationException> { task.await() }
        assertNotNull(f.record().command); assertNull(f.book.states.value.selected); assertFalse(f.book.states.value.serverAcknowledged)
    } }
    @Test fun closeAndActualLeaseInvalidationRedactWithoutErasingOrRetiringNewerLease() = runTest { fixture { f ->
        f.book.saveSelectedPlan().value(); val newer = f.boundary.activate(f.scope)
        assertNull(f.book.states.value.selected); assertTrue(f.book.states.value.items.isEmpty())
        failure(f.book.restore(), FailureReason.STALE_SESSION); assertTrue(f.boundary.isCurrent(newer)); assertEquals(0, f.store.erases)
        assertFalse(f.book.states.value.toString().contains(SAVED)); assertFalse(f.meals.draftReadiness.toString().contains(ORIGIN))
    } }

    @Test fun recalledLocalCopyDeletesWithoutAnotherContentGetOrDisclosingConsentBody() = runTest { fixture { f ->
        f.book.saveSelectedPlan().value(); f.book.load().value(); f.book.open(SAVED).value()
        f.recall()
        assertNull(f.book.states.value.selected!!.savedRecipe)
        assertNull(f.book.states.value.items.single().savedRecipe)
        val gets = f.calls.count { it.operationId == "getSavedRecipe" }; val writes = f.store.writes
        val ticket = f.book.prepareDelete(SAVED).value().deleteConfirmation!!
        assertTrue(ticket.contentUnavailable); assertNull(ticket.title)
        assertFalse(ticket.toString().contains("Synthetic")); assertEquals(writes, f.store.writes)
        f.meals.draftReadiness.invalidate() // Removal is independent of permission to make a new meal/copy.
        val result = f.book.confirmDelete(ticket).value()
        assertEquals(gets, f.calls.count { it.operationId == "getSavedRecipe" })
        val call = f.writes().last(); assertEquals("deleteSavedRecipe", call.operationId)
        assertEquals(mapOf("savedRecipeId" to SAVED), call.pathParameters); assertEquals("\"1\"", call.ifMatch); assertNull(call.body)
        assertTrue(result.serverAcknowledged); assertNull(result.selected); assertTrue(result.items.isEmpty())
        assertTrue(f.store.records.keys.any { it.collection.contains("recall") })
        assertTrue(f.book.searchDownloaded().value().items.isEmpty())
    } }
    @Test fun redactedOfflineIntentContainsOnlyStrictEvidenceAndRestartsUnderSameOriginalKey() = runTest { fixture { f ->
        f.book.saveSelectedPlan().value(); f.recall(); f.online = false
        val ticket = f.book.prepareDelete(SAVED).value().deleteConfirmation!!
        f.book.confirmDelete(ticket).value()
        val original = f.record().command!!; val raw = f.store.records.getValue(BOOK_KEY)
        assertEquals(2, raw.schemaVersion); assertNotNull(original.localDeletion); assertNull(original.expected); assertNull(original.plan)
        assertFalse(raw.payload.copyForCodec().decodeToString().contains("Synthetic recipe"))
        assertFalse(raw.payload.copyForCodec().decodeToString().contains("Synthetic instruction"))
        assertNull(original.call().body); assertEquals(1, f.writes().size)
        f.reopen(); f.book.restore().value(); assertNotNull(f.book.states.value.pending)
        f.online = true; f.book.retryOriginal().value()
        assertEquals(original.id, f.writes().last().idempotencyKey!!.use { it })
        assertEquals(original.etag, f.writes().last().ifMatch); assertEquals(2, f.idCalls)
        assertTrue(f.book.states.value.serverAcknowledged)
    } }
    @Test fun attributionRedactedLocalBranchKeepsOldEtagAndConflictInsteadOfRebasing() = runTest { fixture { f ->
        f.saved = changed(f.saved, "creatorLabel", JsonPrimitive("Private attribution"))
        f.book.saveSelectedPlan().value()
        f.saved = doc(JsonObject(changed(f.saved, "version", JsonPrimitive(2)).json().jsonObject - "creatorLabel"))
        f.book.refreshDetail().value(); f.book.open(SAVED).value()
        val selected = f.book.states.value.selected!!
        assertEquals(SavedRecipeAvailability.UNAVAILABLE, selected.availability); assertNull(selected.savedRecipe)
        val gets = f.calls.size
        f.handler = { call -> if (call.operationId == "deleteSavedRecipe") problem(412, "VERSION_CONFLICT") else f.reply(call) }
        val ticket = f.book.prepareDelete(SAVED).value().deleteConfirmation!!; assertTrue(ticket.contentUnavailable)
        f.book.confirmDelete(ticket).value()
        assertEquals(gets + 1, f.calls.size); assertEquals("\"1\"", f.writes().last().ifMatch)
        assertNotNull(f.record().command!!.localDeletion); assertNull(f.book.states.value.selected!!.savedRecipe)
        f.book.retryOriginal().value(); assertEquals(1, f.writes().count { it.operationId == "deleteSavedRecipe" })
    } }
    @Test fun failedReadableGetNeverFallsBackToLocalDeletionConsent() = runTest {
        for (status in listOf(401, 404, 503)) fixture { f ->
            f.book.saveSelectedPlan().value()
            f.handler = { call -> if (call.operationId == "getSavedRecipe") problem(status, "UNAVAILABLE") else f.reply(call) }
            assertIs<PortResult.Failure>(f.book.prepareDelete(SAVED))
            assertNull(f.book.states.value.deleteConfirmation); assertEquals(1, f.idCalls)
            assertEquals(1, f.writes().size); assertNull(f.record().command)
        }
    }
    @Test fun corruptOrMissingLocalRemovalEvidenceNeverAllocatesIdOrFallsBackToGet() = runTest {
        for (damage in listOf("body", "metadata", "index")) fixture { f ->
            f.book.saveSelectedPlan().value(); f.recall()
            val key = when (damage) { "body" -> savedBodyKey(); "metadata" -> savedMetadataKey(); else -> RecordKey("feedme.kitchen.saved.index", "v1") }
            val prior = f.store.records.getValue(key)
            f.store.records[key] = prior.copy(payload = PrivateBytes("{}".encodeToByteArray()))
            val gets = f.calls.size; val writes = f.store.writes
            assertIs<PortResult.Failure>(f.book.prepareDelete(SAVED))
            assertEquals(gets, f.calls.size); assertEquals(writes, f.store.writes); assertEquals(1, f.idCalls)
            assertNull(f.book.states.value.deleteConfirmation)
        }
    }
    @Test fun changedLocalEvidenceDuringIdSuspensionCannotEnqueueOrAdoptNewRevision() = runTest { fixture { f ->
        f.book.saveSelectedPlan().value(); f.recall()
        val ticket = f.book.prepareDelete(SAVED).value().deleteConfirmation!!
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.beforeId = { entered.complete(Unit); release.await() }
        val task = async { f.book.confirmDelete(ticket) }; entered.await()
        val key = savedMetadataKey(); val old = f.store.records.getValue(key)
        f.store.records[key] = old.copy(revision = old.revision + 1); release.complete(Unit)
        failure(task.await(), FailureReason.CONFLICT); assertNull(f.record().command)
        assertEquals(1, f.writes().size)
    } }
    @Test fun localConsentDismissExpiryRollbackOrControllerReplacementCannotDelete() = runTest {
        for (change in listOf("dismiss", "expiry", "rollback", "reopen")) fixture { f ->
            f.book.saveSelectedPlan().value(); f.recall()
            val ticket = f.book.prepareDelete(SAVED).value().deleteConfirmation!!
            when (change) { "dismiss" -> f.book.dismissDelete().value(); "expiry" -> f.time += 60_001
                "rollback" -> f.time--; else -> f.reopen() }
            failure(f.book.confirmDelete(ticket), FailureReason.CONFLICT)
            assertEquals(1, f.idCalls); assertEquals(1, f.writes().size)
        }
    }
    @Test fun localPendingEvidenceIsRevalidatedBeforeEveryDispatchAndBeforeReceiptApplication() = runTest {
        for (stage in listOf("dispatch", "receipt")) fixture { f ->
            f.book.saveSelectedPlan().value(); f.recall(); f.online = false
            val ticket = f.book.prepareDelete(SAVED).value().deleteConfirmation!!
            f.book.confirmDelete(ticket).value(); val original = f.record().command!!
            val damage = { val key = savedBodyKey(); val old = f.store.records.getValue(key); f.store.records[key] = old.copy(revision = old.revision + 1) }
            if (stage == "dispatch") damage() else f.handler = { call -> if (call.operationId == "deleteSavedRecipe") damage(); f.reply(call) }
            f.online = true; val result = f.book.retryOriginal()
            assertTrue(result is PortResult.Failure || result.value().pending != null)
            assertEquals(original.id, f.record().command!!.id); assertFalse(f.book.states.value.serverAcknowledged)
            assertEquals(if (stage == "dispatch") 0 else 1, f.writes().count { it.operationId == "deleteSavedRecipe" })
            assertNotNull(f.store.records[savedBodyKey()])
        }
    }
    @Test fun localLostDeleteReplyRetainsOriginalThroughAuthConflictAndTimeoutProblems() = runTest {
        for (status in listOf(401, 403, 404, 409, 412, 422)) fixture { f ->
            f.book.saveSelectedPlan().value(); f.recall(); var attempts = 0
            f.handler = { call -> if (call.operationId != "deleteSavedRecipe") f.reply(call) else {
                attempts++; if (attempts == 1) PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) else problem(status, "UNAVAILABLE")
            } }
            val ticket = f.book.prepareDelete(SAVED).value().deleteConfirmation!!
            f.book.confirmDelete(ticket).value(); val original = f.record().command!!
            f.time += 60_000; f.book.retryOriginal().value()
            val calls = f.writes().filter { it.operationId == "deleteSavedRecipe" }
            assertEquals(2, calls.size); assertTrue(calls.all { it.idempotencyKey!!.use { it == original.id } && it.ifMatch == original.etag && it.body == null })
            assertNotNull(f.record().command!!.localDeletion); failure(f.book.discardUnsent(), FailureReason.CONFLICT)
            assertFalse(f.book.states.value.serverAcknowledged); assertEquals(2, f.idCalls)
        }
    }
    @Test fun localDeleteLostApplyUsesRetainedArchiveProofNotDeletedRepositoryEvidence() = runTest { fixture { f ->
        f.book.saveSelectedPlan().value(); f.recall()
        val ticket = f.book.prepareDelete(SAVED).value().deleteConfirmation!!
        f.store.failAfter = { changes -> changes.any { it.key == savedBodyKey() && it is StoreMutation.Delete } }
        failure(f.book.confirmDelete(ticket), FailureReason.OUTCOME_UNKNOWN)
        assertNull(f.store.records[savedBodyKey()]); assertNull(f.record().command)
        assertNotNull(CookbookProofs.get(f.access, f.boundary)); assertFalse(f.book.states.value.serverAcknowledged)
        f.book.restore().value(); failure(f.book.discardUnsent(), FailureReason.CONFLICT)
        f.reopen(); val result = f.book.retryOriginal().value()
        assertTrue(result.serverAcknowledged); assertNull(result.selected); assertNull(result.pending)
        assertEquals(1, f.writes().count { it.operationId == "deleteSavedRecipe" })
    } }
    @Test fun localDeleteChangedArchiveCannotBeClearedByReadDiscardOrReplacementConsent() = runTest { fixture { f ->
        f.book.saveSelectedPlan().value(); f.recall()
        val ticket = f.book.prepareDelete(SAVED).value().deleteConfirmation!!
        f.store.failAfter = { changes -> changes.any { it.key == savedBodyKey() && it is StoreMutation.Delete } }
        failure(f.book.confirmDelete(ticket), FailureReason.OUTCOME_UNKNOWN)
        val proof = CookbookProofs.get(f.access, f.boundary)!!
        val archive = RecordKey("feedme.command.metadata", proof.command.id)
        val old = f.store.records.getValue(archive); f.store.records[archive] = old.copy(payload = PrivateBytes("{}".encodeToByteArray()))
        val writes = f.store.writes
        assertIs<PortResult.Failure>(f.book.retryOriginal()); failure(f.book.discardUnsent(), FailureReason.CONFLICT)
        failure(f.book.prepareDelete(SAVED), FailureReason.CONFLICT)
        assertEquals(writes, f.store.writes); assertSame(proof, CookbookProofs.get(f.access, f.boundary))
    } }
    @Test fun schemaOneReadablePendingDeletionRetainsOriginalIdentityOnReplacementAndRetry() = runTest { fixture { f ->
        val ticket = f.book.prepareDelete(SAVED).value().deleteConfirmation!!
        f.online = false; f.book.confirmDelete(ticket).value()
        val original = f.record().command!!; val bytes = f.store.records.getValue(BOOK_KEY).payload.copyForCodec()
        assertEquals(1, f.store.records.getValue(BOOK_KEY).schemaVersion); assertNull(original.localDeletion)
        assertNotNull(original.expected); assertFalse(bytes.decodeToString().contains("localDeletion"))
        f.reopen(); f.book.restore().value(); assertContentEquals(bytes, f.store.records.getValue(BOOK_KEY).payload.copyForCodec())
        f.online = true; f.book.retryOriginal().value()
        assertEquals(original.id, f.writes().single().idempotencyKey!!.use { it }); assertEquals(original.etag, f.writes().single().ifMatch)
        assertTrue(f.book.states.value.serverAcknowledged)
    } }
    @Test fun localRecordRejectsMixedBranchesUnknownSchemaAlteredEtagAndEvidenceFields() = runTest {
        for (change in listOf("mixed", "etag", "extra", "schema", "origin")) fixture { f ->
            f.book.saveSelectedPlan().value(); f.recall(); f.online = false
            f.book.confirmDelete(f.book.prepareDelete(SAVED).value().deleteConfirmation!!).value()
            val record = f.store.records.getValue(BOOK_KEY); val raw = WireDocument.decode(record.payload.copyForCodec()).json().jsonObject
            val command = raw.getValue("command").jsonObject
            val altered = when (change) {
                "schema" -> JsonObject(raw + ("schema" to JsonPrimitive(3)))
                "origin" -> JsonObject(raw + ("origin" to JsonPrimitive(OTHER)))
                else -> JsonObject(raw + ("command" to JsonObject(command + when (change) {
                    "mixed" -> "expected" to JsonPrimitive(f.saved.encodeUtf8().decodeToString())
                    "etag" -> "etag" to JsonPrimitive("\"2\"")
                    else -> "extra" to JsonPrimitive(true)
                })))
            }
            f.store.records[BOOK_KEY] = record.copy(payload = PrivateBytes(altered.toString().encodeToByteArray()))
            val calls = f.calls.size; val writes = f.store.writes
            failure(f.book.restore(), FailureReason.INVALID_DATA); assertEquals(calls, f.calls.size); assertEquals(writes, f.store.writes)
        }
    }
    @Test fun exactRecallStillHidesPositiveProjectionWhenLocalNegativeInspectionFails() = runTest { fixture { f ->
        f.book.saveSelectedPlan().value(); f.book.load().value(); f.book.open(SAVED).value()
        f.handler = { call -> if (call.operationId == "getSavedRecipe") {
            f.store.failReads = true; problem(409, "RECIPE_RECALLED")
        } else f.reply(call) }
        assertIs<PortResult.Failure>(f.book.refreshDetail())
        assertNull(f.book.states.value.selected); assertTrue(f.book.states.value.items.isEmpty())
        assertFalse(f.book.states.value.serverAcknowledged); assertNull(f.book.states.value.deleteConfirmation)
        assertEquals(1, f.idCalls); assertEquals(1, f.writes().size)
    } }
    @Test fun ordinaryReadTimeoutDoesNotInventRecallOrDeletionAuthority() = runTest { fixture { f ->
        f.book.saveSelectedPlan().value()
        f.handler = { call -> if (call.operationId == "getSavedRecipe") PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) else f.reply(call) }
        failure(f.book.refreshDetail(), FailureReason.OUTCOME_UNKNOWN)
        assertNotNull(f.book.states.value.selected!!.savedRecipe)
        assertEquals(SavedRecipeAvailability.AVAILABLE, f.book.states.value.selected!!.availability)
        assertNull(f.book.states.value.deleteConfirmation); assertEquals(1, f.writes().size)
    } }
    @Test fun collectionRecallClearsOnlyUncertainProjectionAndNeverCreatesPerCopyRemovalAuthority() = runTest { fixture { f ->
        f.book.saveSelectedPlan().value()
        f.handler = { call -> if (call.operationId == "listSavedRecipes")
            if (call.queryParameters["cursor"] == null) response(page(listOf(f.saved), "next-owned-page"))
            else problem(409, "RECIPE_RECALLED") else f.reply(call) }
        f.book.load("owned query").value()
        val consent = f.book.prepareDelete(SAVED).value().deleteConfirmation!!
        assertNotNull(f.book.states.value.selected!!.savedRecipe); assertTrue(f.book.states.value.hasMore)
        val writes = f.store.writes; val ids = f.idCalls; val calls = f.calls.size
        failure(f.book.more(), FailureReason.CONFLICT)
        val state = f.book.states.value
        assertTrue(state.items.isEmpty()); assertNull(state.selected); assertFalse(state.hasMore)
        assertNull(state.deleteConfirmation); assertFalse(state.serverAcknowledged); assertTrue(state.historical)
        assertEquals(writes, f.store.writes); assertEquals(ids, f.idCalls)
        assertEquals(listOf("listSavedRecipes"), f.calls.drop(calls).map { it.operationId })
        assertTrue(f.store.records.keys.none { it.collection.contains("recall") })
        failure(f.book.confirmDelete(consent), FailureReason.CONFLICT)
        // The collection failure cannot identify this copy as recalled. An explicit local read
        // still observes its exact historical bundle; no local evidence is inferred from LIST.
        val local = f.book.open(SAVED).value().selected!!
        assertEquals(SavedRecipeAvailability.AVAILABLE, local.availability); assertNotNull(local.savedRecipe)
        assertTrue(f.book.states.value.historical); assertEquals(writes, f.store.writes)
        f.handler = { call -> if (call.operationId == "getSavedRecipe") problem(401, "UNAVAILABLE") else f.reply(call) }
        failure(f.book.prepareDelete(SAVED), FailureReason.UNAUTHENTICATED)
        assertNull(f.book.states.value.deleteConfirmation); assertEquals(ids, f.idCalls)
    } }
    @Test fun collectionRecallPreservesExactPendingIntentAndLostApplyFinalizationProof() = runTest {
        for (mode in listOf("unsent", "finalization")) fixture { f ->
            if (mode == "unsent") { f.online = false; f.book.saveSelectedPlan().value(); f.online = true }
            else {
                f.store.failAfter = { changes -> changes.any { it.key == BOOK_KEY } && changes.any { it is StoreMutation.Delete } }
                failure(f.book.saveSelectedPlan(), FailureReason.OUTCOME_UNKNOWN)
            }
            val originalProof = CookbookProofs.get(f.access, f.boundary)
            f.handler = { call -> if (call.operationId == "listSavedRecipes")
                if (call.queryParameters["cursor"] == null) response(page(listOf(f.saved), "next-owned-page"))
                else problem(409, "RECIPE_RECALLED") else f.reply(call) }
            f.book.load().value()
            val pending = f.book.states.value.pending!!; val before = f.store.records.toMap(); val writes = f.store.writes
            failure(f.book.more(), FailureReason.CONFLICT)
            assertEquals(pending.commandId, f.book.states.value.pending!!.commandId)
            assertEquals(pending.finalizationRequired, f.book.states.value.pending!!.finalizationRequired)
            assertSame(originalProof, CookbookProofs.get(f.access, f.boundary))
            assertEquals(before.keys, f.store.records.keys); assertEquals(writes, f.store.writes)
            before.forEach { (key, record) ->
                val after = f.store.records.getValue(key)
                assertEquals(record.revision, after.revision); assertEquals(record.schemaVersion, after.schemaVersion)
                assertContentEquals(record.payload.copyForCodec(), after.payload.copyForCodec())
            }
            assertTrue(f.book.states.value.items.isEmpty()); assertNull(f.book.states.value.selected)
            assertFalse(f.book.states.value.hasMore); assertFalse(f.book.states.value.serverAcknowledged)
            assertEquals(if (mode == "unsent") 0 else 1, f.writes().size)
        }
    }
    @Test fun ordinaryOrSchemaInvalidCollectionFailureCannotInventRecallProjection() = runTest {
        for (mode in listOf("timeout", "ordinary-problem", "wrong-media")) fixture { f ->
            f.book.saveSelectedPlan().value()
            f.handler = { call -> if (call.operationId == "listSavedRecipes") response(page(listOf(f.saved), "next-owned-page")) else f.reply(call) }
            f.book.load().value()
            val before = f.book.states.value; val writes = f.store.writes
            f.handler = { call -> if (call.operationId == "listSavedRecipes") when (mode) {
                "timeout" -> PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
                "ordinary-problem" -> problem(409, "UNAVAILABLE")
                else -> PortResult.Value(problem(409, "RECIPE_RECALLED").value().let {
                    ApiReply(it.status, it.body, contentType = "application/json")
                })
            } else f.reply(call) }
            assertIs<PortResult.Failure>(f.book.more())
            assertSame(before.selected, f.book.states.value.selected)
            assertEquals(before.items, f.book.states.value.items); assertTrue(f.book.states.value.hasMore)
            assertTrue(f.store.records.keys.none { it.collection.contains("recall") }); assertEquals(writes, f.store.writes)
        }
    }
    @Test fun lateCollectionRecallAfterBackCancellationOrLeaseChangeCannotRedactNewerView() = runTest {
        for (change in listOf("back", "cancel", "lease")) fixture { f ->
            f.book.saveSelectedPlan().value()
            f.handler = { call -> if (call.operationId == "listSavedRecipes") response(page(listOf(f.saved), "next-owned-page")) else f.reply(call) }
            f.book.load().value()
            val selected = f.book.states.value.selected; val writes = f.store.writes
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.handler = { call -> if (call.operationId == "listSavedRecipes") {
                entered.complete(Unit); withContext(NonCancellable) { release.await() }; problem(409, "RECIPE_RECALLED")
            } else f.reply(call) }
            val task = async { f.book.more() }; entered.await()
            when (change) { "back" -> f.book.back().value(); "cancel" -> task.cancel(); else -> f.boundary.activate(f.scope) }
            release.complete(Unit)
            if (change == "cancel") assertFailsWith<CancellationException> { task.await() }
            else failure(task.await(), FailureReason.STALE_SESSION)
            if (change == "lease") { assertNull(f.book.states.value.selected); assertTrue(f.book.states.value.items.isEmpty()) }
            else {
                assertSame(selected, f.book.states.value.selected); assertNotNull(f.book.states.value.items.single().savedRecipe)
                if (change == "back") assertEquals(CookbookScreen.HIDDEN, f.book.states.value.screen)
            }
            assertTrue(f.store.records.keys.none { it.collection.contains("recall") }); assertEquals(writes, f.store.writes)
        }
    }
    @Test fun lateRecallReplyAfterBackCancellationOrLeaseChangeCannotPublishAnotherSelection() = runTest {
        for (change in listOf("back", "cancel", "lease")) fixture { f ->
            f.book.saveSelectedPlan().value()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.handler = { call -> if (call.operationId == "getSavedRecipe") {
                entered.complete(Unit); withContext(NonCancellable) { release.await() }; problem(409, "RECIPE_RECALLED")
            } else f.reply(call) }
            val task = async { f.book.refreshDetail() }; entered.await()
            when (change) { "back" -> f.book.back().value(); "cancel" -> task.cancel(); else -> f.boundary.activate(f.scope) }
            release.complete(Unit)
            if (change == "cancel") assertFailsWith<CancellationException> { task.await() } else failure(task.await(), FailureReason.STALE_SESSION)
            assertNull(f.book.states.value.deleteConfirmation); assertEquals(1, f.writes().size)
            if (change == "lease") { assertNull(f.book.states.value.selected); assertTrue(f.book.states.value.items.isEmpty()) }
            else if (change == "back") assertEquals(CookbookScreen.LIST, f.book.states.value.screen)
        }
    }
    @Test fun localDeleteCallerReturnBackOrCloseCannotConsumeOriginalFinalizationProof() = runTest {
        for (change in listOf("back", "reopen", "cancel")) fixture { f ->
            f.book.saveSelectedPlan().value(); f.recall()
            val ticket = f.book.prepareDelete(SAVED).value().deleteConfirmation!!
            val scheduled = StandardTestDispatcher(testScheduler)
            val held = ArrayDeque<Pair<CoroutineContext, Runnable>>(); var hold = false
            val caller = object : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (hold) held.addLast(context to block) else scheduled.dispatch(context, block)
            } }
            val task = async(caller) { hold = true; f.book.confirmDelete(ticket) }
            runCurrent(); assertEquals(1, held.size)
            // Gate the final caller return after the delivery ticket has been armed too.
            val first = held.removeFirst(); scheduled.dispatch(first.first, first.second); runCurrent()
            assertEquals(1, held.size); assertNotNull(CookbookProofs.get(f.access, f.boundary))
            assertNull(f.store.records[savedBodyKey()])
            when (change) { "back" -> f.book.back().value(); "reopen" -> f.reopen(); else -> task.cancel() }
            hold = false; while (held.isNotEmpty()) { val (context, block) = held.removeFirst(); scheduled.dispatch(context, block) }
            if (change == "cancel") assertFailsWith<CancellationException> { task.await() } else failure(task.await(), FailureReason.STALE_SESSION)
            assertNotNull(CookbookProofs.get(f.access, f.boundary)); assertFalse(f.book.states.value.serverAcknowledged)
            val result = f.book.retryOriginal().value(); assertTrue(result.serverAcknowledged); assertNull(result.pending)
            assertEquals(1, f.writes().count { it.operationId == "deleteSavedRecipe" })
        }
    }
    @Test fun recalledRemovalPreservesExactQueuedCookingIntentAndNeverDrainsIt() = runTest { fixture { f ->
        f.bundle.cooking.prepareStart(PLAN).value()
        val cookingKey = RecordKey("mealflow.cooking.v1", ORIGIN)
        f.store.failAfter = { changes -> changes.size > 1 && changes.any { it.key == cookingKey } }
        failure(f.bundle.cooking.confirmStart(), FailureReason.OUTCOME_UNKNOWN)
        val cooking = CookingFlowCodec(ORIGIN, COOK_POLICY).decode(f.store.records.getValue(cookingKey).payload)
        val command = cooking.start!!; assertEquals(CookingStartStage.QUEUED, command.stage)
        f.book.saveSelectedPlan().value(); f.recall()
        val retained = listOf(cookingKey, RecordKey("feedme.command.metadata", command.id),
            RecordKey("feedme.command.request", command.id)).associateWith { key ->
            f.store.records.getValue(key).let { it.copy(payload = PrivateBytes(it.payload.copyForCodec())) }
        }
        val ticket = f.book.prepareDelete(SAVED).value().deleteConfirmation!!
        f.book.confirmDelete(ticket).value()
        for ((key, old) in retained) {
            val current = f.store.records.getValue(key)
            assertEquals(old.revision, current.revision); assertEquals(old.schemaVersion, current.schemaVersion)
            assertContentEquals(old.payload.copyForCodec(), current.payload.copyForCodec())
        }
        assertTrue(f.calls.none { it.operationId == "createCookSession" })
        assertEquals(1, f.writes().count { it.operationId == "deleteSavedRecipe" })
    } }

    private suspend fun TestScope.fixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture(this)
        try { f.meals.restore().value(); block(f) }
        finally { f.close(); f.boundary.clear() }
    }
    private class Fixture(test: TestScope) {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = StorageScope("fixture", ActorKind.ACCOUNT, "cookbook-private")
        val boundary = SessionBoundary(); val lease = boundary.activate(scope); val store = Store(scope)
        var time = 1_000_000L; var online = true; var idCalls = 0
        var beforeId: suspend () -> Unit = {}
        val ids = MealOperationIds { beforeId(); idCalls++; number(100 + idCalls) }
        val connectivity = ConnectivityPort { if (online) Connectivity.ONLINE else Connectivity.OFFLINE }
        val preference = preference(); val request = MealRequestBuilder().build(draft(), preference).value()
        val plan = plan(request); val recipe = (PlanWire.from(plan).recipeSnapshot as WireField.Value).value.document
        var saved = saved(recipe)
        val calls = mutableListOf<ApiCall>()
        var handler: suspend (ApiCall) -> PortResult<ApiReply> = { reply(it) }
        val access = AuthenticatedMealPlanningAccess(lease, ORIGIN, store, object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                assertSame(this@Fixture.lease, lease); calls += call; return handler(call)
            }
        }, true)
        var meals = newMeals()
        var bundle = newBundle()
        val book get() = bundle.cookbook
        init {
            val data = FlowRecord(time, time, draft(), preference, plans = listOf(FlowPlan(plan, "\"1\"", time, request)), selected = 0)
            store.records[MEAL_KEY] = PrivateRecord(1, 1, MealFlowCodec(MealRequestBuilder(), ORIGIN).encode(data))
        }
        fun newMeals() = MealRequestController(access, boundary, dispatcher, EpochClock { time }, connectivity, ids, MEAL_POLICY)
        fun newBundle() = MealKitchenControllers.create(access, boundary, dispatcher, EpochClock { time }, connectivity,
            ids, meals, meals.draftReadiness, COOK_POLICY, BOOK_POLICY)
        suspend fun close() { bundle.cookbook.close(); bundle.cooking.close(); meals.close() }
        suspend fun reopen() { close(); meals = newMeals(); bundle = newBundle(); meals.restore().value() }
        fun writes() = calls.filter { it.operationId in setOf("saveRecipe", "deleteSavedRecipe") }
        suspend fun recall() {
            handler = { call -> if (call.operationId == "getSavedRecipe") problem(409, "RECIPE_RECALLED") else reply(call) }
            failure(book.refreshDetail(), FailureReason.CONFLICT)
            assertNull(book.states.value.selected!!.savedRecipe)
            assertEquals(SavedRecipeAvailability.RECALLED, book.states.value.selected!!.availability)
        }
        fun record() = CookbookCodec(ORIGIN).decode(store.records.getValue(BOOK_KEY).payload)
        fun reply(call: ApiCall): PortResult<ApiReply> = when (call.operationId) {
            "saveRecipe" -> response(saved, 201)
            "getSavedRecipe" -> response(saved)
            "listSavedRecipes" -> response(page(listOf(saved)))
            "deleteSavedRecipe" -> PortResult.Value(ApiReply(204, null))
            "getPlan" -> response(plan)
            "getPreferences" -> response(preference)
            else -> PortResult.Failure(FailureReason.NOT_CONFIGURED)
        }
    }
    private class Store(val scope: StorageScope) : PrivateStateStore {
        val records = mutableMapOf<RecordKey, PrivateRecord>(); var writes = 0; var erases = 0; var failReads = false
        var afterRead: suspend (RecordKey) -> Unit = {}
        var afterCommit: suspend (List<StoreMutation>) -> Unit = {}
        var failAfter: ((List<StoreMutation>) -> Boolean)? = null
        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> {
            if (scope != this.scope) return PortResult.Failure(FailureReason.STALE_SESSION)
            if (failReads) return PortResult.Failure(FailureReason.STORAGE_FAILURE)
            val value = records[key]?.let { it.copy(payload = PrivateBytes(it.payload.copyForCodec())) }
            afterRead(key); return PortResult.Value(value)
        }
        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> {
            if (scope != this.scope) return PortResult.Failure(FailureReason.STALE_SESSION)
            if (mutations.map { it.key }.distinct().size != mutations.size || mutations.any { records[it.key]?.revision != it.expectedRevision }) return PortResult.Failure(FailureReason.CONFLICT)
            val fail = failAfter?.invoke(mutations) == true; if (fail) failAfter = null
            val result = mutations.associate { mutation -> mutation.key to when (mutation) {
                is StoreMutation.Put -> { val revision = (records[mutation.key]?.revision ?: 0) + 1
                    records[mutation.key] = PrivateRecord(revision, mutation.schemaVersion, PrivateBytes(mutation.payload.copyForCodec())); revision }
                is StoreMutation.Delete -> { records.remove(mutation.key); null }
            } }; writes++; afterCommit(mutations)
            return if (fail) PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) else PortResult.Value(result)
        }
        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> { erases++; return PortResult.Failure(FailureReason.FORBIDDEN) }
    }
    private companion object {
        const val ORIGIN = "00000000-0000-4000-8000-000000000001"
        const val PLAN = "00000000-0000-4000-8000-000000000002"
        const val VERSION = "00000000-0000-4000-8000-000000000003"
        const val INGREDIENT = "00000000-0000-4000-8000-000000000004"
        const val OTHER = "00000000-0000-4000-8000-000000000005"
        const val SAVED = "00000000-0000-4000-8000-000000000006"
        const val TIME = "2026-09-13T10:00:00Z"
        val MEAL_POLICY = MealFlowPolicy(86400000, 518400000, 20, 128)
        val COOK_POLICY = CookingFlowPolicy(60000, 65536, 65536)
        val BOOK_POLICY = CookbookPolicy(2, 60000)
        val MEAL_KEY = RecordKey("mealflow.v1", ORIGIN); val BOOK_KEY = RecordKey("mealflow.cookbook.v1", ORIGIN)
        fun savedBodyKey() = RecordKey("feedme.kitchen.saved.body", SAVED)
        fun savedMetadataKey() = RecordKey("feedme.kitchen.saved.metadata", SAVED)
        fun number(number: Int) = "00000000-0000-4000-8000-${number.toString().padStart(12, '0')}"
        fun draft(servings: String = "1") = ManualMealDraft(MealMode.COOK, MealEnergy.LITTLE, servings, listOf(INGREDIENT), listOf("bowl"), emptyList(), emptyList())
        fun preference() = doc(buildJsonObject {
            put("id", OTHER); put("version", 1); put("createdAt", TIME); put("updatedAt", TIME)
            for (field in listOf("hardExcludedIngredientIds", "dietaryPatterns", "dislikedIngredientIds")) put(field, JsonArray(emptyList()))
            put("equipmentIds", JsonArray(listOf(JsonPrimitive("bowl"))))
        })
        fun plan(request: WireDocument) = doc(buildJsonObject {
            put("id", PLAN); put("version", 1); put("createdAt", TIME); put("updatedAt", TIME); put("mode", "cook"); put("status", "ready")
            put("constraints", request.json().jsonObject.getValue("constraints")); put("catalogRevision", "synthetic-fixture")
            for (field in listOf("missingIngredients", "changes", "reasons")) put(field, JsonArray(emptyList()))
            put("recipeVersionId", VERSION); put("recipeSnapshot", buildJsonObject {
                put("id", VERSION); put("recipeId", OTHER); put("version", 1); put("createdAt", TIME); put("updatedAt", TIME)
                put("title", "Synthetic recipe"); put("reviewStatus", "published"); put("reviewedAt", TIME)
                put("servings", 1); put("activeMinutes", 2); put("totalMinutes", 2); put("utensilCount", 1)
                put("equipmentIds", JsonArray(listOf(JsonPrimitive("bowl")))); put("modes", JsonArray(listOf(JsonPrimitive("cook")))); put("tasteTags", JsonArray(emptyList()))
                put("ingredients", buildJsonArray { add(buildJsonObject { put("ingredientId", INGREDIENT); put("quantity", 1); put("unit", "g"); put("optional", false) }) })
                put("steps", buildJsonArray { add(buildJsonObject {
                    put("stepId", "first"); put("position", 1); put("instruction", "Synthetic instruction")
                    put("ingredientIds", JsonArray(listOf(JsonPrimitive(INGREDIENT)))); put("requiredEquipmentIds", JsonArray(listOf(JsonPrimitive("bowl")))); put("mandatorySafetyStep", false)
                }) })
            })
        })
        fun saved(recipe: WireDocument) = doc(buildJsonObject {
            put("id", SAVED); put("version", 1); put("createdAt", TIME); put("updatedAt", TIME)
            put("title", "Synthetic recipe"); put("sourceType", "ownPlan"); put("contentLicense", "privateCopyOnly")
            put("snapshot", recipe.json()); put("recalled", false)
        })
        fun page(items: List<WireDocument>, next: String? = null) = doc(buildJsonObject {
            put("items", JsonArray(items.map { it.json() })); put("nextCursor", next?.let(::JsonPrimitive) ?: JsonNull); put("serverTime", TIME)
        })
        fun response(document: WireDocument, status: Int = 200): PortResult<ApiReply> = PortResult.Value(ApiReply(status,
            PrivateBytes(document.encodeUtf8()), etag = (document.field("version") as? WireField.Value)?.value?.let { "\"${it.numberTokenOrNull()}\"" }, contentType = "application/json"))
        fun problem(status: Int, code: String): PortResult<ApiReply> = PortResult.Value(ApiReply(status,
            PrivateBytes(doc(buildJsonObject {
                put("type", "https://example.invalid/problem"); put("title", "Synthetic denial"); put("status", status); put("code", code); put("traceId", "fixture-trace")
            }).encodeUtf8()), contentType = "application/problem+json"))
        fun changed(body: WireDocument, key: String, value: JsonElement) = doc(JsonObject(body.json().jsonObject + (key to value)))
        fun ApiCall.document() = WireDocument.decode(body!!.copyForCodec())
        fun <T> PortResult<T>.value(): T = assertIs<PortResult.Value<T>>(this).value
        fun failure(result: PortResult<*>, reason: FailureReason) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
    }
}
