package com.feedme.mealflow

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.kitchen.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.coroutines.CoroutineContext

/** Actual kitchen repositories and journal over a detached CAS/transport fixture. These synthetic
 * reviewed bodies prove protocol behavior, never native encryption, provider or content approval. */
@OptIn(ExperimentalCoroutinesApi::class)
class CookingFlowControllerTest {
    @Test fun prepareIsReadPreflightThenEncryptedProposalWithoutCreatingServerSession() = runTest { fixture { f ->
        val state = f.controller.prepareStart(PLAN).value()
        assertEquals(CookingFlowPhase.START_CONFIRMATION, state.phase)
        assertEquals(CookingFlowScreen.RECIPE, state.screen); assertNull(state.cooking)
        assertEquals(listOf("getPreferences", "getPlan", "getPreferences"), f.calls.map { it.operationId })
        assertEquals(CookingStartStage.PREPARED, f.record().start!!.stage)
        assertFalse(state.canEdit); assertFalse(state.serverAcknowledged); assertEquals(1, f.idCalls)
    } }

    @Test fun explicitStartUsesExactOriginalKeyNoIfMatchThenOwnedSessionAndPlanDownload() = runTest { fixture { f ->
        f.controller.prepareStart(PLAN).value(); val prepared = f.record().start!!
        f.beforeCall = { call -> if (call.operationId == "createCookSession") {
            assertEquals(CookingStartStage.QUEUED, f.record().start!!.stage)
            assertTrue(f.store.records.keys.any { it.collection.endsWith("request") && it.id == prepared.id })
        } }
        val result = f.controller.confirmStart().value()
        val post = f.mutations().single()
        assertEquals("createCookSession", post.operationId); assertNull(post.ifMatch)
        assertEquals(prepared.id, post.idempotencyKey!!.use { it })
        assertEquals(setOf("planId", "deviceSequence"), body(post).keys)
        assertEquals(PLAN, body(post).getValue("planId").jsonPrimitive.content)
        assertEquals("0", body(post).getValue("deviceSequence").jsonPrimitive.content)
        assertEquals(listOf("createCookSession", "getCookSession", "getPlan"), f.calls.takeLast(3).map { it.operationId })
        assertEquals(CookingFlowPhase.COOKING, result.phase); assertEquals(SESSION, result.cooking!!.id)
        assertTrue(result.canEdit); assertTrue(result.serverAcknowledged)
        assertEquals(CookingStartStage.ATTACHED, f.record().start!!.stage)
    } }

    @Test fun accountAndGuestUseActualLeaseClassAndNoAnonymousOrSocialFallback() = runTest {
        for (guest in listOf(false, true)) fixture(guest = guest) { f ->
            f.start(); assertEquals(if (guest) ActorKind.GUEST else ActorKind.ACCOUNT, f.lease.scope.actorKind)
            assertTrue(f.calls.all { it.operationId in setOf("getPreferences", "getPlan", "createCookSession", "getCookSession") })
        }
    }

    @Test fun missingHistoricalOrChangedSelectedPlanCannotBeUsedAsStartAuthority() = runTest {
        fixture { f -> failure(f.controller.prepareStart(OTHER), FailureReason.CONFLICT); assertTrue(f.calls.isEmpty()) }
        fixture { f -> f.store.records.remove(MEAL_KEY); failure(f.controller.prepareStart(PLAN), FailureReason.CONFLICT); assertTrue(f.calls.isEmpty()) }
        fixture { f -> f.updateMeal { it.copy(draft = draft(servings = "2")) }; failure(f.controller.prepareStart(PLAN), FailureReason.CONFLICT); assertTrue(f.calls.isEmpty()) }
    }

    @Test fun pendingMealCommandAndPendingPreferenceDraftBlockBeforeTransport() = runTest {
        fixture { f ->
            val m = f.meal(); val command = FlowCommand(number(99), "createPlan", f.request, MealRequestBuilder().manual(draft()), f.preference, null, f.time, f.time)
            f.updateMeal { m.copy(command = command, usedIds = listOf(FlowIssuedId(command.id, command.created))) }
            failure(f.controller.prepareStart(PLAN), FailureReason.CONFLICT); assertTrue(f.calls.isEmpty())
        }
        fixture { f -> f.blockPreferences(); failure(f.controller.prepareStart(PLAN), FailureReason.CONFLICT)
            assertTrue(f.calls.isEmpty()); assertEquals(CookingFlowIssue.PREFERENCES_PENDING, f.controller.states.value.issue) }
    }

    @Test fun freshPreferencesOrMaterializedPlanChangesInvalidateStartWithoutPost() = runTest {
        fixture { f -> f.preference = preference(version = 2); failure(f.controller.prepareStart(PLAN), FailureReason.CONFLICT); assertTrue(f.mutations().isEmpty()) }
        fixture { f -> f.plan = changed(f.plan, "version", JsonPrimitive(2)); failure(f.controller.prepareStart(PLAN), FailureReason.CONFLICT); assertTrue(f.mutations().isEmpty()) }
    }

    @Test fun unreviewedRecalledMissingReviewAndMissingInstructionsNeverEnableCooking() = runTest {
        for (kind in listOf("personal", "retired", "recalled", "missing-review", "missing-steps")) fixture { f ->
            val recipe = f.plan.json().jsonObject.getValue("recipeSnapshot").jsonObject.toMutableMap()
            when (kind) { "missing-review" -> recipe.remove("reviewedAt"); "missing-steps" -> recipe["steps"] = JsonArray(emptyList())
                else -> recipe["reviewStatus"] = JsonPrimitive(kind) }
            f.plan = changed(f.plan, "recipeSnapshot", JsonObject(recipe)); f.seedMeal()
            assertIs<PortResult.Failure>(f.controller.prepareStart(PLAN))
            assertTrue(f.mutations().isEmpty()); assertFalse(f.controller.states.value.canEdit)
        }
    }

    @Test fun readOnlyRestoreAndOfflinePreparationNeverSendOrInventServerIdentity() = runTest { fixture { f ->
        assertEquals(CookingFlowPhase.IDLE, f.controller.restore().value().phase)
        f.online = false; failure(f.controller.prepareStart(PLAN), FailureReason.OFFLINE)
        assertTrue(f.calls.isEmpty()); assertEquals(0, f.idCalls)
        f.online = true; f.controller.prepareStart(PLAN).value(); f.calls.clear(); f.online = false
        failure(f.controller.confirmStart(), FailureReason.OFFLINE)
        assertEquals(CookingStartStage.PREPARED, f.record().start!!.stage); assertNull(f.record().selected); assertTrue(f.calls.isEmpty())
    } }

    @Test fun offlinePrivateAccessCannotBeUpgradedByConnectivity() = runTest { fixture(allowed = false) { f ->
        failure(f.controller.prepareStart(PLAN), FailureReason.OFFLINE); assertTrue(f.calls.isEmpty()); assertEquals(0, f.idCalls)
    } }

    @Test fun confirmationExpiryClockRollbackAndSourceRevisionChangeRequireNewConsent() = runTest {
        for (change in listOf("expire", "rollback", "source")) fixture { f ->
            f.controller.prepareStart(PLAN).value()
            when (change) { "expire" -> f.time += 60_001; "rollback" -> f.time--; else -> f.updateMeal { it } }
            failure(f.controller.confirmStart(), FailureReason.CONFLICT); assertTrue(f.mutations().isEmpty())
        }
    }

    @Test fun preferenceOrMealChangeDuringPreflightRejectsMixedReadSequence() = runTest {
        fixture { f -> f.beforeCall = { if (it.operationId == "getPlan") f.preference = preference(2) }
            failure(f.controller.prepareStart(PLAN), FailureReason.CONFLICT); assertTrue(f.mutations().isEmpty()) }
        fixture { f -> f.beforeCall = { if (it.operationId == "getPlan") f.updateMeal { it } }
            failure(f.controller.prepareStart(PLAN), FailureReason.CONFLICT); assertTrue(f.mutations().isEmpty()) }
    }

    @Test fun preparedProposalSurvivesCloseReopenWithoutImplicitConfirmation() = runTest { fixture { f ->
        f.controller.prepareStart(PLAN).value(); val id = f.record().start!!.id; f.calls.clear()
        f.reopen(); val state = f.controller.restore().value()
        assertEquals(CookingFlowPhase.START_CONFIRMATION, state.phase); assertEquals(id, f.record().start!!.id); assertTrue(f.calls.isEmpty())
        f.controller.confirmStart().value(); assertEquals(id, f.mutations().single().idempotencyKey!!.use { it })
    } }

    @Test fun failedOrFalseProposalAcknowledgementNeverAuthorizesPost() = runTest {
        for (fault in listOf(Fault.BEFORE, Fault.AFTER, Fault.FALSE_RECEIPT)) fixture { f ->
            f.store.fault = fault; f.store.failWhen = { it.any { m -> m.key == FLOW_KEY } }
            assertIs<PortResult.Failure>(f.controller.prepareStart(PLAN)); assertTrue(f.mutations().isEmpty())
            assertFalse(f.controller.states.value.canEdit)
        }
    }

    @Test fun unknownQueueEnqueueAfterCommitRetainsOriginalForExplicitFreshRetry() = runTest { fixture { f ->
        f.controller.prepareStart(PLAN).value(); val id = f.record().start!!.id
        f.store.fault = Fault.AFTER; f.store.failWhen = { it.size > 1 && it.any { m -> m.key == FLOW_KEY } }
        failure(f.controller.confirmStart(), FailureReason.OUTCOME_UNKNOWN); assertTrue(f.mutations().isEmpty())
        assertEquals(CookingStartStage.QUEUED, f.record().start!!.stage)
        f.controller.retryStart().value(); assertEquals(id, f.mutations().single().idempotencyKey!!.use { it })
    } }

    @Test fun lostCreateReplyRetriesExactOriginalAfterBackoffAndNeverChangesBodyOrKey() = runTest { fixture { f ->
        f.controller.prepareStart(PLAN).value(); var lost = true
        f.handler = { call -> if (call.operationId == "createCookSession" && lost) { lost = false; PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) } else f.defaultReply(call) }
        f.controller.confirmStart().value(); val first = f.mutations().single(); assertNull(f.record().selected)
        failure(f.controller.discardUnsentStart(), FailureReason.CONFLICT)
        f.reopen(); f.controller.restore().value(); f.time += 60_000
        f.controller.retryStart().value(); val retry = f.mutations().last()
        assertEquals(first.idempotencyKey!!.use { it }, retry.idempotencyKey!!.use { it }); assertContentEquals(first.body!!.copyForCodec(), retry.body!!.copyForCodec())
        assertEquals(1, f.idCalls); assertEquals(SESSION, f.controller.states.value.cooking!!.id)
    } }

    @Test fun receiptDownloadFailurePreservesReceiptWithoutAnotherCreatePost() = runTest { fixture { f ->
        f.controller.prepareStart(PLAN).value(); var fail = true
        f.handler = { call -> if (call.operationId == "getCookSession" && fail) PortResult.Failure(FailureReason.OFFLINE) else f.defaultReply(call) }
        failure(f.controller.confirmStart(), FailureReason.OFFLINE); assertEquals(1, f.mutations().size)
        val pending = f.controller.states.value.pending.single()
        assertEquals(f.record().start!!.id, pending.commandId); assertEquals("RECEIPT_READY", pending.phase)
        assertTrue(pending.canRetry); assertFalse(pending.canDiscardUnsent)
        assertNull(f.controller.states.value.cooking); assertNull(f.record().selected)
        assertFalse(f.controller.states.value.serverAcknowledged)
        fail = false; f.controller.retryStart().value(); assertEquals(1, f.mutations().size); assertEquals(SESSION, f.record().selected)
    } }

    @Test fun malformedWrongPlanOrDivergentDownloadCannotAttachReceipt() = runTest {
        for (kind in listOf("wrong-plan", "wrong-step", "divergent", "invalid-content")) fixture { f ->
            f.controller.prepareStart(PLAN).value()
            f.handler = { call -> when {
                call.operationId == "createCookSession" && kind == "wrong-plan" -> reply(changed(f.session, "planId", JsonPrimitive(OTHER)), 201)
                call.operationId == "createCookSession" && kind == "wrong-step" -> reply(changed(f.session, "currentStepId", JsonPrimitive("missing")), 201)
                call.operationId == "getCookSession" && kind == "divergent" -> reply(changed(f.session, "version", JsonPrimitive(2)))
                call.operationId == "createCookSession" && kind == "invalid-content" -> PortResult.Value(ApiReply(201, PrivateBytes("{}".encodeToByteArray()), contentType = "text/plain"))
                else -> f.defaultReply(call)
            } }
            val result = f.controller.confirmStart()
            // A malformed transport outcome is classified by the real queue as unresolved; it
            // is not an applied domain receipt. Other mismatches fail during receipt application.
            if (kind == "invalid-content") {
                assertEquals(CookingFlowPhase.START_PENDING, result.value().phase)
                assertTrue(f.controller.states.value.pending.isNotEmpty())
            } else assertIs<PortResult.Failure>(result)
            assertNull(f.record().selected); assertFalse(f.controller.states.value.canEdit)
        }
    }

    @Test fun lostAtomicAttachmentReceiptNeedsFreshAcknowledgementWithoutReposting() = runTest { fixture { f ->
        f.controller.prepareStart(PLAN).value()
        f.store.fault = Fault.AFTER; f.store.failWhen = { changes -> changes.any { m -> m is StoreMutation.Put && m.key == FLOW_KEY && text(m.payload).contains("ATTACHED") } }
        failure(f.controller.confirmStart(), FailureReason.OUTCOME_UNKNOWN)
        assertEquals(CookingStartStage.ATTACHED, f.record().start!!.stage); assertFalse(f.controller.states.value.serverAcknowledged)
        assertEquals("FINALIZATION_REQUIRED", f.controller.restore().value().pending.single().phase)
        f.reopen(); f.controller.restore().value()
        val revision = f.store.records.getValue(FLOW_KEY).revision
        val state = f.controller.retryStart().value()
        assertTrue(f.store.records.getValue(FLOW_KEY).revision > revision); assertTrue(state.serverAcknowledged); assertEquals(1, f.mutations().size)
    } }

    @Test fun lostAttachmentProofRejectsChangedArchiveAndCannotBeDiscardedOrOverwrittenByNavigation() = runTest { fixture { f ->
        f.controller.prepareStart(PLAN).value()
        f.store.fault = Fault.AFTER; f.store.failWhen = { changes -> changes.any { m -> m is StoreMutation.Put && m.key == FLOW_KEY && text(m.payload).contains("ATTACHED") } }
        failure(f.controller.confirmStart(), FailureReason.OUTCOME_UNKNOWN); f.controller.restore().value()
        val before = f.store.records.getValue(FLOW_KEY); val writes = f.store.writes
        f.controller.backToRecipe().value(); assertEquals(writes, f.store.writes); assertContentEquals(before.payload.copyForCodec(), f.store.records.getValue(FLOW_KEY).payload.copyForCodec())
        failure(f.controller.discardUnsentStart(), FailureReason.CONFLICT); failure(f.controller.prepareStart(PLAN), FailureReason.CONFLICT)
        failure(f.controller.open(SESSION), FailureReason.CONFLICT)
        val archivedKey = RecordKey("feedme.command.metadata", f.record().start!!.id)
        val archived = f.store.records.getValue(archivedKey)
        // Whitespace-only alteration is still a different raw archive, not the captured queue batch.
        f.store.records[archivedKey] = archived.copy(payload = PrivateBytes((text(archived.payload) + " ").encodeToByteArray()))
        failure(f.controller.retryStart(), FailureReason.CONFLICT); assertEquals(writes, f.store.writes)
        assertFalse(f.controller.states.value.serverAcknowledged)
        f.store.records[archivedKey] = archived
        assertTrue(f.controller.retryStart().value().serverAcknowledged)
    } }

    @Test fun openingAnotherCachedSessionNeverInheritsPreviousAcknowledgementAndPreparedStartDisablesActions() = runTest { fixture { f ->
        f.start(); val first = f.controller.states.value
        assertTrue(first.serverAcknowledged)
        f.session = changed(f.session, "id", JsonPrimitive(OTHER))
        val external = PrivateKitchenSession(f.scope, f.store, f.boundary, f.dispatcher, EpochClock { f.time }, f.access.transport, ORIGIN)
        external.cooking.download(f.lease, OTHER).value()
        val opened = f.controller.open(OTHER).value()
        assertTrue(opened.historical); assertFalse(opened.serverAcknowledged); assertEquals(OTHER, opened.cooking!!.id)
        val prepared = f.controller.prepareStart(PLAN).value()
        assertFalse(prepared.canEdit); assertFalse(prepared.canStop); assertFalse(prepared.canComplete)
    } }

    @Test fun preparedDifferentPlanOwnsConsentProjectionAndBlockedProposalNeverFallsBackToPriorPin() = runTest { fixture { f ->
        f.start()
        val newRecipeId = number(801)
        val recipe = f.plan.json().jsonObject.getValue("recipeSnapshot").jsonObject.toMutableMap()
        recipe["id"] = JsonPrimitive(newRecipeId); recipe["title"] = JsonPrimitive("Different synthetic prepared recipe")
        f.plan = changed(changed(changed(f.plan, "id", JsonPrimitive(OTHER)), "recipeVersionId", JsonPrimitive(newRecipeId)), "recipeSnapshot", JsonObject(recipe))
        f.seedMeal()
        val proposed = f.controller.prepareStart(OTHER).value()
        assertEquals(CookingFlowPhase.START_CONFIRMATION, proposed.phase)
        assertEquals(OTHER, proposed.plan!!.id.value); assertEquals(newRecipeId, (proposed.plan.recipeVersionId as WireField.Value).value.value)
        assertEquals(PLAN, proposed.cooking!!.plan.id.value)
        assertFalse(proposed.canEdit); assertFalse(proposed.canStop); assertFalse(proposed.canComplete); assertFalse(proposed.serverAcknowledged)
        val ready = f.plan
        f.plan = changed(f.plan, "status", JsonPrimitive("recalled"))
        f.session = changed(changed(f.session, "id", JsonPrimitive(number(802))), "planId", JsonPrimitive(OTHER))
        val external = PrivateKitchenSession(f.scope, f.store, f.boundary, f.dispatcher, EpochClock { f.time }, f.access.transport, ORIGIN)
        external.cooking.download(f.lease, number(802)).value(); f.plan = ready; f.calls.clear()
        val restored = f.controller.restore().value()
        assertEquals(CookingFlowPhase.START_CONFIRMATION, restored.phase)
        assertNull(restored.plan); assertEquals(PLAN, restored.cooking!!.plan.id.value)
        failure(f.controller.confirmStart(), FailureReason.FORBIDDEN); assertNull(f.controller.states.value.plan)
        assertTrue(f.calls.isEmpty())
        val discarded = f.controller.discardUnsentStart().value()
        assertEquals(PLAN, discarded.plan!!.id.value); assertEquals(discarded.plan.id, discarded.cooking!!.plan.id)
        assertFalse(discarded.serverAcknowledged)
    } }

    @Test fun knownSharedRecallAfterPreparationHidesPreviewBeforeConfirmFailureAndRestore() = runTest { fixture { f ->
        f.controller.prepareStart(PLAN).value(); assertNotNull(f.controller.states.value.plan)
        val original = f.plan; f.plan = changed(f.plan, "status", JsonPrimitive("recalled"))
        val external = PrivateKitchenSession(f.scope, f.store, f.boundary, f.dispatcher, EpochClock { f.time }, f.access.transport, ORIGIN)
        external.cooking.download(f.lease, SESSION).value(); f.plan = original; f.calls.clear()
        failure(f.controller.confirmStart(), FailureReason.FORBIDDEN)
        assertNull(f.controller.states.value.plan); assertTrue(f.mutations().isEmpty())
        f.reopen(); assertNull(f.controller.restore().value().plan); assertTrue(f.calls.isEmpty())
    } }

    @Test fun callerReturnHandoffRechecksLeaseAndNeverReturnsOldPrivateSnapshot() = runTest { fixture { f ->
        f.start()
        val scheduled = StandardTestDispatcher(testScheduler); val held = ArrayDeque<Pair<CoroutineContext, Runnable>>(); var hold = false
        val caller = object : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (hold) held.addLast(context to block) else scheduled.dispatch(context, block)
        } }
        val task = async(caller) { hold = true; f.controller.restore() }; runCurrent(); assertEquals(1, held.size)
        val newer = f.boundary.activate(f.scope); hold = false
        while (held.isNotEmpty()) { val (context, block) = held.removeFirst(); scheduled.dispatch(context, block) }
        failure(task.await(), FailureReason.STALE_SESSION)
        assertNull(f.controller.states.value.cooking); assertTrue(f.boundary.isCurrent(newer))
    } }

    @Test fun closeDuringRepositoryStoreReadPreventsLaterNetworkAndStoreEffects() = runTest { fixture { f ->
        f.start(); f.calls.clear(); val writes = f.store.writes
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var armed = true
        f.store.afterRead = { key -> if (armed && key.collection == "feedme.kitchen.cook.metadata") {
            armed = false; entered.complete(Unit); withContext(NonCancellable) { release.await() }
        } }
        val task = async { f.controller.refresh() }; entered.await(); f.controller.close().value(); release.complete(Unit)
        failure(task.await(), FailureReason.STALE_SESSION)
        assertEquals(writes, f.store.writes); assertTrue(f.calls.isEmpty()); assertTrue(f.boundary.isCurrent(f.lease))
    } }

    @Test fun backDuringNonCooperativeStartIsImmediateAndKeepsExactOriginalPendingBytes() = runTest { fixture { f ->
        f.controller.prepareStart(PLAN).value()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.handler = { call -> if (call.operationId == "createCookSession") {
            entered.complete(Unit); withContext(NonCancellable) { release.await() }; f.defaultReply(call)
        } else f.defaultReply(call) }
        val task = async { f.controller.confirmStart() }; entered.await()
        val original = f.store.records.getValue(FLOW_KEY); val writes = f.store.writes
        val back = async { f.controller.backToRecipe() }; runCurrent(); assertTrue(back.isCompleted)
        assertEquals(CookingFlowScreen.RECIPE, back.await().value().screen)
        assertEquals(writes, f.store.writes); assertContentEquals(original.payload.copyForCodec(), f.store.records.getValue(FLOW_KEY).payload.copyForCodec())
        release.complete(Unit); assertIs<PortResult.Failure>(task.await()); assertNull(f.controller.states.value.cooking)
        assertEquals(CookingStartStage.QUEUED, f.record().start!!.stage); assertFalse(f.calls.any { it.operationId == "getCookSession" })
    } }

    @Test fun preparedOrNeverAttemptedCommandsCanBeDiscardedButAttemptedCannot() = runTest { fixture { f ->
        f.controller.prepareStart(PLAN).value(); f.controller.discardUnsentStart().value(); assertNull(f.record().start)
        f.controller.prepareStart(PLAN).value(); f.store.fault = Fault.AFTER
        f.store.failWhen = { it.size > 1 && it.any { m -> m.key == FLOW_KEY } }
        failure(f.controller.confirmStart(), FailureReason.OUTCOME_UNKNOWN)
        f.controller.discardUnsentStart().value(); assertNull(f.record().start); assertTrue(f.mutations().isEmpty())
    } }

    @Test fun restoredOwnedBundleIsHistoricalAndDoesNotDownloadOrAcknowledgeServerAgain() = runTest { fixture { f ->
        f.start(); f.calls.clear(); f.reopen()
        val restored = f.controller.restore().value(); assertTrue(restored.historical); assertFalse(restored.serverAcknowledged)
        assertEquals(SESSION, restored.cooking!!.id); assertTrue(restored.canEdit); assertTrue(f.calls.isEmpty())
    } }

    @Test fun offlineStepEditsRetainRealRepositoryActionsAndLastStepNeverCompletes() = runTest { fixture { f ->
        f.start(); f.calls.clear(); f.online = false
        f.controller.moveTo("second").value(); val state = f.controller.markStepComplete("second").value()
        assertEquals(CookingStatus.ACTIVE, state.cooking!!.progress.status)
        assertEquals("2", state.cooking.progress.deviceSequence); assertEquals(2, state.cooking.pendingCommandIds.size)
        assertEquals("first", state.cooking.remote.currentStepId.value); assertTrue(f.calls.isEmpty()); assertFalse(state.serverAcknowledged)
        f.reopen(); assertEquals("second", f.controller.restore().value().cooking!!.progress.currentStepId)
    } }

    @Test fun headOnlySynchronizationUsesReceivedEtagsAndPreservesLaterLocalEdit() = runTest { fixture { f ->
        f.start(); f.controller.moveTo("second").value(); f.controller.markStepComplete("first").value(); f.calls.clear()
        val first = f.controller.synchronize().value(); assertEquals(1, first.cooking!!.pendingCommandIds.size)
        assertEquals("2", first.cooking.progress.deviceSequence); assertEquals("1", first.cooking.remote.deviceSequence.jsonToken)
        assertEquals("\"1\"", f.mutations().single().ifMatch)
        val second = f.controller.synchronize().value(); assertTrue(second.cooking!!.pendingCommandIds.isEmpty())
        assertEquals("\"2\"", f.mutations().last().ifMatch); assertEquals("2", second.cooking.remote.deviceSequence.jsonToken)
    } }

    @Test fun explicitCompletionHasNoIfMatchMakeAgainFalseAndNoImplicitSaveFeedbackOrTimer() = runTest { fixture { f ->
        f.start(); val local = f.controller.complete().value()
        assertEquals(CookingFlowPhase.COMPLETED, local.phase); assertFalse(local.serverAcknowledged)
        f.controller.synchronize().value(); val completion = f.mutations().last()
        assertEquals("completeCookSession", completion.operationId); assertNull(completion.ifMatch)
        assertEquals(JsonPrimitive(false), body(completion)["makeAgain"])
        assertTrue(f.calls.none { it.operationId.contains("save", true) || it.operationId.contains("timer", true) || it.operationId.contains("feedback", true) })
    } }

    @Test fun backRetainsProgressAndPendingIntentWithoutAbandonOrCompletion() = runTest { fixture { f ->
        f.start(); f.controller.moveTo("second").value(); val before = f.controller.states.value.cooking!!; f.calls.clear()
        val back = f.controller.backToRecipe().value()
        assertEquals(CookingFlowScreen.RECIPE, back.screen); assertEquals(CookingStatus.ACTIVE, back.cooking!!.progress.status)
        assertEquals(before.pendingCommandIds, back.cooking.pendingCommandIds); assertEquals(before.progress.deviceSequence, back.cooking.progress.deviceSequence)
        assertTrue(f.calls.isEmpty()); f.controller.open(SESSION).value(); assertEquals(CookingFlowScreen.COOK, f.controller.states.value.screen)
    } }

    @Test fun stricterPendingPreferencesBlockCookingAndCompletionButAllowExplicitSafePauseAbandon() = runTest { fixture { f ->
        f.start(); f.blockPreferences()
        failure(f.controller.moveTo("second"), FailureReason.CONFLICT); failure(f.controller.complete(), FailureReason.CONFLICT)
        val paused = f.controller.pause().value(); assertEquals(CookingStatus.PAUSED, paused.cooking!!.progress.status)
        assertFalse(paused.canEdit); assertTrue(paused.canStop)
        f.controller.synchronize().value(); assertEquals("paused", body(f.mutations().last()).getValue("status").jsonPrimitive.content)
        val abandoned = f.controller.abandon().value(); assertEquals(CookingStatus.ABANDONED, abandoned.cooking!!.progress.status)
    } }

    @Test fun cachedReturnToCookingPreservesExactPinPendingProofAndRejectsAnotherPreparedPlan() = runTest { fixture { f ->
        failure(f.controller.returnToCooking(), FailureReason.CONFLICT)
        f.start(); f.controller.moveTo("second").value(); val before = f.controller.states.value.cooking!!
        f.controller.backToRecipe().value(); val writes = f.store.writes; val calls = f.calls.size
        val returned = f.controller.returnToCooking().value()
        assertEquals(CookingFlowScreen.COOK, returned.screen); assertEquals(before.plan.id, returned.plan!!.id)
        assertEquals(before.pendingCommandIds, returned.cooking!!.pendingCommandIds)
        assertEquals(before.progress.deviceSequence, returned.cooking.progress.deviceSequence)
        assertEquals(writes, f.store.writes); assertEquals(calls, f.calls.size)
        f.plan = changed(f.plan, "id", JsonPrimitive(OTHER)); f.seedMeal()
        f.controller.prepareStart(OTHER).value(); f.controller.backToRecipe().value()
        val afterPrepare = f.store.writes; val afterCalls = f.calls.size
        failure(f.controller.returnToCooking(), FailureReason.CONFLICT)
        assertEquals(OTHER, f.controller.states.value.plan!!.id.value)
        assertEquals(afterPrepare, f.store.writes); assertEquals(afterCalls, f.calls.size)
    } }

    @Test fun cachedReturnCallerHandoffCannotExposeInvalidatedPinOrClearNewLease() = runTest { fixture { f ->
        f.start(); f.controller.backToRecipe().value()
        val writes = f.store.writes; val calls = f.calls.size
        val scheduled = StandardTestDispatcher(testScheduler); val held = ArrayDeque<Pair<CoroutineContext, Runnable>>(); var hold = false
        val caller = object : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (hold) held.addLast(context to block) else scheduled.dispatch(context, block)
        } }
        val task = async(caller) { hold = true; f.controller.returnToCooking() }; runCurrent(); assertEquals(1, held.size)
        val newer = f.boundary.activate(f.scope); hold = false
        while (held.isNotEmpty()) { val (context, block) = held.removeFirst(); scheduled.dispatch(context, block) }
        failure(task.await(), FailureReason.STALE_SESSION); assertTrue(f.boundary.isCurrent(newer))
        assertNull(f.controller.states.value.plan); assertEquals(writes, f.store.writes); assertEquals(calls, f.calls.size)
    } }

    @Test fun pendingPreferencesIntroducedAfterLocalEditStopUnsafeDispatchButKeepExactHead() = runTest { fixture { f ->
        f.start(); f.controller.moveTo("second").value(); val head = f.controller.states.value.cooking!!.pendingCommandIds.single()
        f.blockPreferences(); f.calls.clear(); assertIs<PortResult.Failure>(f.controller.synchronize())
        assertTrue(f.mutations().isEmpty()); assertEquals(head, f.controller.restore().value().cooking!!.pendingCommandIds.single())
    } }

    @Test fun changedOriginDownloadedPinRemainsReadOnlyAndCannotBeAdopted() = runTest { fixture { f ->
        f.start(); f.controller.close().value(); f.controller = f.newController(number(909));
        val state = f.controller.open(SESSION).value()
        assertFalse(state.cooking!!.originMatches); assertFalse(state.canEdit); assertEquals(CookingFlowIssue.ORIGIN_CHANGED, state.issue)
        failure(f.controller.moveTo("second"), FailureReason.CONFLICT)
    } }

    @Test fun recallLearnedByExistingKitchenRepositoryBlocksNewStartEvenWithStaleReadyGetPlan() = runTest { fixture { f ->
        f.start(); f.plan = changed(f.plan, "status", JsonPrimitive("recalled"))
        // Same-version changed lifecycle bytes are rejected, but the matching negative recall
        // is still learned first and must hide the older cached instructions on that failure.
        failure(f.controller.refresh(), FailureReason.CONFLICT); assertNull(f.controller.states.value.cooking); assertFalse(f.controller.states.value.canEdit)
        f.plan = plan(f.request); f.controller.close().value(); f.controller = f.newController()
        f.seedMeal(); f.calls.clear()
        failure(f.controller.prepareStart(PLAN), FailureReason.FORBIDDEN); assertTrue(f.calls.isEmpty())
    } }

    @Test fun boundGetPlanRecallProblemsFencePrepareConfirmAndRetryAcrossControllerReplacement() = runTest {
        for (stage in listOf("prepare", "confirm", "retry")) fixture { f ->
            if (stage != "prepare") f.controller.prepareStart(PLAN).value()
            if (stage == "retry") {
                f.handler = { call -> if (call.operationId == "createCookSession") PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) else f.defaultReply(call) }
                f.controller.confirmStart().value(); f.time += 60_000
            }
            f.handler = { call -> if (call.operationId == "getPlan") PortResult.Value(ApiReply(410, PrivateBytes(buildJsonObject {
                put("type", "about:blank"); put("title", "Unavailable"); put("status", 410); put("code", "RECIPE_RECALLED"); put("traceId", "test-trace")
            }.toString().encodeToByteArray()), contentType = "application/problem+json")) else f.defaultReply(call) }
            failure(when (stage) { "prepare" -> f.controller.prepareStart(PLAN); "confirm" -> f.controller.confirmStart(); else -> f.controller.retryStart() }, FailureReason.FORBIDDEN)
            assertNull(f.controller.states.value.plan); assertFalse(f.controller.states.value.canEdit)
            assertTrue(VERSION in f.record().recalled)
            f.reopen(); f.handler = { f.defaultReply(it) }; f.calls.clear(); f.controller.restore().value()
            assertNull(f.controller.states.value.plan)
            assertIs<PortResult.Failure>(when (stage) { "prepare" -> f.controller.prepareStart(PLAN); "confirm" -> f.controller.confirmStart(); else -> f.controller.retryStart() })
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun actualLeaseInvalidationSynchronouslyRedactsAndPreservesNewerLease() = runTest { fixture { f ->
        f.start(); val newer = f.boundary.activate(f.scope)
        assertEquals(CookingFlowPhase.UNAVAILABLE, f.controller.states.value.phase); assertNull(f.controller.states.value.plan); assertNull(f.controller.states.value.cooking)
        failure(f.controller.restore(), FailureReason.STALE_SESSION); assertTrue(f.boundary.isCurrent(newer))
        assertEquals(0, f.store.erases)
    } }

    @Test fun cancelledNonCooperativeCreateKeepsOriginalIntentAndCannotDownloadOrPublishLateReply() = runTest { fixture { f ->
        f.controller.prepareStart(PLAN).value(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.handler = { call -> if (call.operationId == "createCookSession") { entered.complete(Unit); withContext(NonCancellable) { release.await() }; f.defaultReply(call) } else f.defaultReply(call) }
        val task = async { f.controller.confirmStart() }; entered.await(); task.cancel(); release.complete(Unit)
        assertFailsWith<CancellationException> { task.await() }
        assertEquals(CookingStartStage.QUEUED, f.record().start!!.stage); assertNull(f.record().selected)
        assertFalse(f.calls.any { it.operationId == "getCookSession" }); assertNull(f.controller.states.value.cooking)
    } }

    @Test fun closeDuringSuspendedPreflightFencesLateResponseWithoutClosingBorrowedStore() = runTest { fixture { f ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.handler = { call -> entered.complete(Unit); withContext(NonCancellable) { release.await() }; f.defaultReply(call) }
        val task = async { f.controller.prepareStart(PLAN) }; entered.await(); f.controller.close().value(); release.complete(Unit)
        failure(task.await(), FailureReason.STALE_SESSION); assertFalse(f.store.records.containsKey(FLOW_KEY))
        assertTrue(f.boundary.isCurrent(f.lease)); assertEquals(0, f.store.erases); assertNull(f.controller.states.value.plan)
    } }

    @Test fun duplicateLiveControllerAndMalformedNativeIdsFailBeforeMutation() = runTest { fixture { f ->
        f.controller.restore().value(); val other = f.newController()
        try { failure(other.restore(), FailureReason.CONFLICT) } finally { other.close().value() }
        f.id = "private-not-uuid"; assertIs<PortResult.Failure>(f.controller.prepareStart(PLAN)); assertTrue(f.mutations().isEmpty())
        assertFalse(f.controller.states.value.toString().contains("private-not-uuid")); assertFalse(f.controller.states.value.toString().contains(PLAN))
    } }

    @Test fun responseBindingProfileAndCodecCorruptionFailClosedWithoutPortExpansion() = runTest {
        fixture { f -> f.handler = { PortResult.Value(ApiReply(200, PrivateBytes("{}".encodeToByteArray()), contentType = "text/plain")) }
            failure(f.controller.prepareStart(PLAN), FailureReason.INVALID_DATA); assertTrue(f.mutations().isEmpty()) }
        fixture { f -> f.store.records[FLOW_KEY] = PrivateRecord(1, 1, PrivateBytes("{}".encodeToByteArray()))
            failure(f.controller.restore(), FailureReason.INVALID_DATA); assertTrue(f.calls.isEmpty()) }
        assertFailsWith<IllegalArgumentException> { CookingFlowPolicy(0, 1024, 1024) }
        assertFailsWith<IllegalArgumentException> { CookingFlowPolicy(1, 262145, 1024) }
    }

    private suspend fun TestScope.fixture(guest: Boolean = false, allowed: Boolean = true, block: suspend (Fixture) -> Unit) {
        val f = Fixture(this, guest, allowed)
        try { block(f) } finally { f.controller.close(); f.boundary.clear() }
    }
    private class Fixture(test: TestScope, guest: Boolean, allowed: Boolean) {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = StorageScope("fixture", if (guest) ActorKind.GUEST else ActorKind.ACCOUNT, "private-cook")
        val boundary = SessionBoundary(); val lease = boundary.activate(scope); val store = Store(scope)
        var time = 1_000_000L; var online = true; var idCalls = 0; var id: String? = null
        var preference = preference(); val request = MealRequestBuilder().build(draft(), preference).value()
        var plan = plan(request); var session = session()
        val calls = mutableListOf<ApiCall>(); var beforeCall: suspend (ApiCall) -> Unit = {}
        var handler: suspend (ApiCall) -> PortResult<ApiReply> = { defaultReply(it) }
        val access = AuthenticatedMealPlanningAccess(lease, ORIGIN, store, object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                assertSame(this@Fixture.lease, lease); calls += call; beforeCall(call); return handler(call)
            }
        }, allowed)
        var controller = newController()
        init { seedMeal() }
        fun newController(origin: String = ORIGIN) = CookingFlowController(
            if (origin == ORIGIN) access else AuthenticatedMealPlanningAccess(lease, origin, store, access.transport, access.onlineAllowed),
            boundary, dispatcher, EpochClock { time }, ConnectivityPort { if (online) Connectivity.ONLINE else Connectivity.OFFLINE },
            MealOperationIds { idCalls++; id ?: number(100 + idCalls) }, POLICY)
        suspend fun start() { controller.prepareStart(PLAN).value(); controller.confirmStart().value() }
        suspend fun reopen() { controller.close().value(); controller = newController() }
        fun record() = CookingFlowCodec(ORIGIN, POLICY).decode(store.records.getValue(FLOW_KEY).payload)
        fun meal() = MealFlowCodec(MealRequestBuilder(), ORIGIN).decode(store.records.getValue(MEAL_KEY).payload)
        fun seedMeal() {
            val record = FlowRecord(time, time, draft(), preference, plans = listOf(FlowPlan(plan, "\"1\"", time, request)), selected = 0)
            store.records[MEAL_KEY] = PrivateRecord((store.records[MEAL_KEY]?.revision ?: 0) + 1, 1, MealFlowCodec(MealRequestBuilder(), ORIGIN).encode(record))
        }
        fun updateMeal(transform: (FlowRecord) -> FlowRecord) {
            val old = store.records.getValue(MEAL_KEY)
            store.records[MEAL_KEY] = PrivateRecord(old.revision + 1, 1, MealFlowCodec(MealRequestBuilder(), ORIGIN).encode(transform(meal())))
        }
        fun blockPreferences() = KitchenInputPreferenceGuard.block(lease, ORIGIN, boundary)
        fun mutations() = calls.filter { it.operationId in setOf("createCookSession", "updateCookSession", "completeCookSession") }
        fun defaultReply(call: ApiCall): PortResult<ApiReply> = when (call.operationId) {
            "getPreferences" -> reply(preference)
            "getPlan" -> reply(plan)
            "getCookSession" -> reply(session)
            "createCookSession" -> reply(session, 201)
            "updateCookSession", "completeCookSession" -> {
                val prior = session.json().jsonObject.toMutableMap(); val patch = body(call)
                for ((name, value) in patch) if (name !in setOf("makeAgain", "finishedAtClient")) prior[name] = value
                if (call.operationId == "completeCookSession") prior["status"] = JsonPrimitive("completed")
                prior["version"] = JsonPrimitive(prior.getValue("version").jsonPrimitive.int + 1)
                session = doc(JsonObject(prior)); reply(session)
            }
            else -> PortResult.Failure(FailureReason.NOT_CONFIGURED)
        }
    }
    private enum class Fault { BEFORE, AFTER, FALSE_RECEIPT }
    private class Store(val owner: StorageScope) : PrivateStateStore {
        val records = mutableMapOf<RecordKey, PrivateRecord>(); var writes = 0; var erases = 0
        var fault: Fault? = null; var failWhen: (List<StoreMutation>) -> Boolean = { true }
        var afterRead: suspend (RecordKey) -> Unit = {}
        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> {
            if (scope != owner) return PortResult.Failure(FailureReason.STALE_SESSION)
            val value = records[key]?.let { it.copy(payload = PrivateBytes(it.payload.copyForCodec())) }
            afterRead(key); return PortResult.Value(value)
        }
        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> {
            if (scope != owner) return PortResult.Failure(FailureReason.STALE_SESSION)
            if (mutations.map { it.key }.distinct().size != mutations.size || mutations.any { records[it.key]?.revision != it.expectedRevision }) return PortResult.Failure(FailureReason.CONFLICT)
            val fail = fault?.takeIf { failWhen(mutations) }; if (fail != null) fault = null
            if (fail == Fault.BEFORE) return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
            val result = mutations.associate { mutation -> mutation.key to when (mutation) {
                is StoreMutation.Put -> { val revision = (records[mutation.key]?.revision ?: 0) + 1
                    records[mutation.key] = PrivateRecord(revision, mutation.schemaVersion, PrivateBytes(mutation.payload.copyForCodec())); revision }
                is StoreMutation.Delete -> { records.remove(mutation.key); null }
            } }; writes++
            if (fail == Fault.AFTER) return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
            if (fail == Fault.FALSE_RECEIPT) return PortResult.Value(result.mapValues { (_, value) -> value?.plus(1) })
            return PortResult.Value(result)
        }
        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> { erases++; return PortResult.Failure(FailureReason.FORBIDDEN) }
    }
    private companion object {
        const val ORIGIN = "00000000-0000-4000-8000-000000000001"
        const val PLAN = "00000000-0000-4000-8000-000000000002"
        const val VERSION = "00000000-0000-4000-8000-000000000003"
        const val INGREDIENT = "00000000-0000-4000-8000-000000000004"
        const val OTHER = "00000000-0000-4000-8000-000000000005"
        const val SESSION = "00000000-0000-4000-8000-000000000006"
        const val TIME = "2026-09-13T10:00:00Z"
        val POLICY = CookingFlowPolicy(60_000, 65536, 65536)
        val FLOW_KEY = RecordKey("mealflow.cooking.v1", ORIGIN); val MEAL_KEY = RecordKey("mealflow.v1", ORIGIN)
        fun number(number: Int) = "00000000-0000-4000-8000-${number.toString().padStart(12, '0')}"
        fun draft(servings: String = "1") = ManualMealDraft(MealMode.COOK, MealEnergy.LITTLE, servings,
            listOf(INGREDIENT), listOf("bowl"), emptyList(), emptyList())
        fun preference(version: Int = 1) = doc(buildJsonObject {
            put("id", OTHER); put("version", version); put("createdAt", TIME); put("updatedAt", TIME)
            for (field in listOf("hardExcludedIngredientIds", "dietaryPatterns", "dislikedIngredientIds")) put(field, JsonArray(emptyList()))
            put("equipmentIds", JsonArray(listOf(JsonPrimitive("bowl"))))
        })
        fun plan(request: WireDocument) = doc(buildJsonObject {
            put("id", PLAN); put("version", 1); put("createdAt", TIME); put("updatedAt", TIME); put("mode", "cook"); put("status", "ready")
            put("constraints", request.json().jsonObject.getValue("constraints")); put("catalogRevision", "synthetic-reviewed-fixture")
            for (field in listOf("missingIngredients", "changes", "reasons")) put(field, JsonArray(emptyList()))
            put("recipeVersionId", VERSION); put("recipeSnapshot", buildJsonObject {
                put("id", VERSION); put("recipeId", OTHER); put("version", 1); put("createdAt", TIME); put("updatedAt", TIME)
                put("title", "Synthetic reviewed fixture only"); put("reviewStatus", "published"); put("reviewedAt", TIME)
                put("servings", 1); put("activeMinutes", 2); put("totalMinutes", 2); put("utensilCount", 1)
                put("equipmentIds", JsonArray(listOf(JsonPrimitive("bowl")))); put("modes", JsonArray(listOf(JsonPrimitive("cook")))); put("tasteTags", JsonArray(emptyList()))
                put("ingredients", buildJsonArray { add(buildJsonObject { put("ingredientId", INGREDIENT); put("quantity", 1); put("unit", "g"); put("optional", false) }) })
                put("steps", buildJsonArray { for ((i, step) in listOf("first", "second").withIndex()) add(buildJsonObject {
                    put("stepId", step); put("position", i + 1); put("instruction", "Fixture instruction $step")
                    put("ingredientIds", JsonArray(listOf(JsonPrimitive(INGREDIENT)))); put("requiredEquipmentIds", JsonArray(listOf(JsonPrimitive("bowl")))); put("mandatorySafetyStep", false)
                }) })
            })
        })
        fun session() = doc(buildJsonObject {
            put("id", SESSION); put("planId", PLAN); put("version", 1); put("createdAt", TIME); put("updatedAt", TIME)
            put("status", "active"); put("currentStepId", "first"); put("deviceSequence", 0)
            put("completedStepIds", JsonArray(emptyList())); put("timers", JsonArray(emptyList()))
        })
        fun changed(doc: WireDocument, key: String, value: JsonElement) = doc(JsonObject(doc.json().jsonObject + (key to value)))
        fun reply(doc: WireDocument, status: Int = 200): PortResult<ApiReply> = PortResult.Value(ApiReply(status, PrivateBytes(doc.encodeUtf8()),
            etag = "\"${doc.json().jsonObject.getValue("version").jsonPrimitive.content}\"", contentType = "application/json"))
        fun text(bytes: PrivateBytes) = bytes.copyForCodec().decodeToString()
        fun body(call: ApiCall) = WireDocument.decode(call.body!!.copyForCodec()).json().jsonObject
        fun <T> PortResult<T>.value(): T = assertIs<PortResult.Value<T>>(this).value
        fun failure(result: PortResult<*>, reason: FailureReason) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
    }
}
