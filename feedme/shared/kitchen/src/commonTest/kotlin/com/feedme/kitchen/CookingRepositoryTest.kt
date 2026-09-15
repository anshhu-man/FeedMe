package com.feedme.kitchen

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireField
import com.feedme.core.ports.*
import com.feedme.sync.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Independent domain+real-journal tests. The detached CAS fake is not native persistence proof. */
class CookingRepositoryTest {
    @Test fun downloadBindsOwnedSessionToItsExactMaterializedPlan() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.planBody = " \n" + plan(quantity = "2.7500") + "\r\n"
        val view = value(f.repo.download(f.lease, SESSION))
        assertEquals(CookingAvailability.AVAILABLE, view.availability)
        assertEquals(SESSION, view.id)
        assertEquals(PLAN, view.plan.id.value)
        assertEquals(PLAN, view.remote.planId.value)
        assertEquals("2.7500", (view.plan.recipeSnapshot as WireField.Value).value.ingredients.single().quantity.jsonToken)
        assertEquals("step-one", view.progress.currentStepId)
        assertEquals("0", view.progress.deviceSequence)
        assertEquals("\"0007\"", view.etag)
        assertEquals(START, view.lastCheckedMillis)
        assertContentEquals(f.planBody.encodeToByteArray(), f.store.records.getValue(key("plan", SESSION)).payload.copyForCodec())
        assertEquals(listOf("getCookSession", "getPlan"), f.calls.map { it.operationId })
        assertEquals(mapOf("sessionId" to SESSION), f.calls[0].pathParameters)
        assertEquals(mapOf("planId" to PLAN), f.calls[1].pathParameters)
        assertEquals(1, value(f.repo.list(f.lease)).size)
        assertFalse(view.toString().contains("private instruction"))
        assertFalse(view.toString().contains(SESSION))
    }

    @Test fun localEditsAndCanonicalPendingBodiesSurviveRepositoryAndQueueRecreation() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val initial = value(f.repo.download(f.lease, SESSION))
        val moved = value(f.repo.edit(f.lease, SESSION, initial.localRevision, COMMAND1, CookingEdit.MoveTo("step-two")))
        val marked = value(f.repo.edit(f.lease, SESSION, moved.localRevision, COMMAND2, CookingEdit.MarkStepComplete("step-one")))
        assertEquals(listOf(COMMAND1, COMMAND2), marked.pendingCommandIds)
        assertEquals("2", marked.progress.deviceSequence)
        assertEquals("step-one", marked.remote.currentStepId.value)
        assertEquals("7", marked.remote.version.jsonToken)
        assertTrue(value(f.queue.pending(f.lease)).isEmpty())
        val firstBody = f.store.text(key("action-body", COMMAND1))
        val secondBody = f.store.text(key("action-body", COMMAND2))
        f.reopen()
        val restored = assertNotNull(value(f.repo.read(f.lease, SESSION)))
        assertEquals("step-two", restored.progress.currentStepId)
        assertEquals(listOf("step-one"), restored.progress.completedStepIds)
        assertEquals(listOf(COMMAND1, COMMAND2), restored.pendingCommandIds)
        assertEquals(firstBody, f.store.text(key("action-body", COMMAND1)))
        assertEquals(secondBody, f.store.text(key("action-body", COMMAND2)))
        assertEquals(2, f.calls.size)
    }

    @Test fun twoOfflineEditsDispatchAndApplySequentiallyUsingOnlyReceivedEtags() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val initial = value(f.repo.download(f.lease, SESSION))
        val moved = value(f.repo.edit(f.lease, SESSION, initial.localRevision, COMMAND1, CookingEdit.MoveTo("step-two")))
        value(f.repo.edit(f.lease, SESSION, moved.localRevision, COMMAND2, CookingEdit.MarkStepComplete("step-one")))
        f.reopen()
        val first = assertNotNull(value(f.repo.materializeNext(f.lease, SESSION)))
        assertEquals(COMMAND1, first.commandId)
        assertEquals(listOf(COMMAND1), value(f.queue.pending(f.lease)).map { it.commandId })
        assertEquals(CommandPhase.RECEIPT_READY, assertNotNull(value(f.queue.dispatchNext(f.lease))).phase)
        val firstCall = f.calls.last()
        assertEquals("\"0007\"", firstCall.ifMatch)
        assertEquals(COMMAND1, firstCall.idempotencyKey!!.use { it })
        assertEquals("1", json(firstCall.body!!).getValue("deviceSequence").jsonPrimitive.content)
        val firstApplied = value(f.repo.applyReceipt(f.lease, SESSION))
        assertEquals("\"8\"", firstApplied.etag)
        assertEquals("8", firstApplied.remote.version.jsonToken)
        assertEquals("1", firstApplied.remote.deviceSequence.jsonToken)
        assertEquals("2", firstApplied.progress.deviceSequence)
        assertEquals(listOf("step-one"), firstApplied.progress.completedStepIds)
        assertEquals(listOf(COMMAND2), firstApplied.pendingCommandIds)
        assertNull(value(f.queue.receipt(f.lease, COMMAND1)))
        assertNull(f.store.records[key("action-body", COMMAND1)])
        f.reopen()
        assertEquals(COMMAND2, assertNotNull(value(f.repo.materializeNext(f.lease, SESSION))).commandId)
        assertEquals(CommandPhase.RECEIPT_READY, assertNotNull(value(f.queue.dispatchNext(f.lease))).phase)
        val secondCall = f.calls.last()
        assertEquals("\"8\"", secondCall.ifMatch)
        assertEquals(COMMAND2, secondCall.idempotencyKey!!.use { it })
        assertEquals("2", json(secondCall.body!!).getValue("deviceSequence").jsonPrimitive.content)
        val finished = value(f.repo.applyReceipt(f.lease, SESSION))
        assertEquals("\"9\"", finished.etag)
        assertEquals("9", finished.remote.version.jsonToken)
        assertTrue(finished.pendingCommandIds.isEmpty())
        assertEquals(finished.remote.deviceSequence.jsonToken, finished.progress.deviceSequence)
        assertTrue(value(f.queue.pending(f.lease)).isEmpty())
        assertEquals(listOf("getCookSession", "getPlan", "updateCookSession", "updateCookSession"), f.calls.map { it.operationId })
    }

    @Test fun completionPersistsAnExplicitMakeAgainChoiceWithoutFabricatingASave() = runTest {
        for (makeAgain in listOf(false, true)) {
            val f = Fixture(StandardTestDispatcher(testScheduler))
            val initial = value(f.repo.download(f.lease, SESSION))
            val local = value(f.repo.edit(f.lease, SESSION, initial.localRevision, COMMAND1,
                CookingEdit.Complete(makeAgain, "2026-09-13T08:05:00Z")))
            assertEquals(CookingStatus.COMPLETED, local.progress.status)
            assertEquals("active", local.remote.status)
            value(f.repo.materializeNext(f.lease, SESSION))
            assertEquals(CommandPhase.RECEIPT_READY, assertNotNull(value(f.queue.dispatchNext(f.lease))).phase)
            val call = f.calls.last()
            assertEquals("completeCookSession", call.operationId)
            assertNull(call.ifMatch)
            assertEquals(makeAgain, json(call.body!!).getValue("makeAgain").jsonPrimitive.boolean)
            assertFalse("status" in json(call.body!!))
            val applied = value(f.repo.applyReceipt(f.lease, SESSION))
            assertEquals("completed", applied.remote.status)
            assertTrue(applied.pendingCommandIds.isEmpty())
            assertFalse(f.calls.any { it.operationId in setOf("saveRecipe", "savePostRecipe", "createFeedback") })
            assertTrue(f.store.records.keys.none { it.collection.startsWith("feedme.kitchen.saved") })
            failure(FailureReason.CONFLICT, f.repo.edit(f.lease, SESSION, applied.localRevision, COMMAND2, CookingEdit.MoveTo("step-two")))
        }
    }

    @Test fun failedEditCasLeavesNoProgressActionOrUsedCommandTombstone() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val initial = value(f.repo.download(f.lease, SESSION))
        val before = f.store.detached()
        f.store.failWhen = { it.any { mutation -> mutation.key == key("action-body", COMMAND1) } }
        failure(FailureReason.CONFLICT, f.repo.edit(f.lease, SESSION, initial.localRevision, COMMAND1, CookingEdit.MoveTo("step-two")))
        assertEquals(before, f.store.detached())
        assertNull(f.store.records[key("used-action", COMMAND1)])
        assertEquals("step-one", assertNotNull(value(f.repo.read(f.lease, SESSION))).progress.currentStepId)
        f.store.failWhen = { false }
        value(f.repo.edit(f.lease, SESSION, initial.localRevision, COMMAND1, CookingEdit.MoveTo("step-two")))
    }

    @Test fun failedReceiptCasPreservesBothRecoverableReceiptAndLocalDomainIntent() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val initial = value(f.repo.download(f.lease, SESSION))
        value(f.repo.edit(f.lease, SESSION, initial.localRevision, COMMAND1, CookingEdit.MoveTo("step-two")))
        value(f.repo.materializeNext(f.lease, SESSION)); value(f.queue.dispatchNext(f.lease))
        val before = f.store.detached()
        f.store.failWhen = { it.any { mutation -> mutation is StoreMutation.Delete && mutation.key == key("action-body", COMMAND1) } }
        failure(FailureReason.CONFLICT, f.repo.applyReceipt(f.lease, SESSION))
        assertEquals(before, f.store.detached())
        assertNotNull(value(f.queue.receipt(f.lease, COMMAND1)))
        f.reopen()
        assertEquals(listOf(COMMAND1), assertNotNull(value(f.repo.read(f.lease, SESSION))).pendingCommandIds)
        f.store.failWhen = { false }
        val applied = value(f.repo.applyReceipt(f.lease, SESSION))
        assertTrue(applied.pendingCommandIds.isEmpty())
        assertNull(value(f.queue.receipt(f.lease, COMMAND1)))
        assertEquals("8", applied.remote.version.jsonToken)
    }

    @Test fun successfulButNonmatchingReceiptPreservesBothStatesForExplicitConflictChoice() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val initial = value(f.repo.download(f.lease, SESSION))
        value(f.repo.edit(f.lease, SESSION, initial.localRevision, COMMAND1, CookingEdit.MoveTo("step-two")))
        value(f.repo.materializeNext(f.lease, SESSION))
        f.writeExchange = { reply(session(version = 8, sequence = 1, step = "step-one"), "\"8\"") }
        assertEquals(CommandPhase.RECEIPT_READY, assertNotNull(value(f.queue.dispatchNext(f.lease))).phase)
        failure(FailureReason.CONFLICT, f.repo.applyReceipt(f.lease, SESSION))
        f.reopen()
        val conflict = assertNotNull(value(f.repo.read(f.lease, SESSION)))
        assertEquals(CookingAvailability.CONFLICT, conflict.availability)
        assertEquals("step-two", conflict.progress.currentStepId)
        assertEquals("7", conflict.remote.version.jsonToken)
        assertEquals("8", conflict.conflictingRemote!!.version.jsonToken)
        assertEquals("step-one", conflict.conflictingRemote!!.currentStepId.value)
        assertEquals(listOf(COMMAND1), conflict.pendingCommandIds)
        assertNotNull(value(f.queue.receipt(f.lease, COMMAND1)))
        failure(FailureReason.FORBIDDEN, f.repo.edit(f.lease, SESSION, conflict.localRevision, COMMAND2, CookingEdit.MoveTo("step-one")))
    }

    @Test fun preconditionProblemDoesNotAcknowledgeOrRewriteTheOfflinePatch() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val initial = value(f.repo.download(f.lease, SESSION))
        value(f.repo.edit(f.lease, SESSION, initial.localRevision, COMMAND1, CookingEdit.MoveTo("step-two")))
        val exactBody = f.store.text(key("action-body", COMMAND1))
        value(f.repo.materializeNext(f.lease, SESSION))
        f.writeExchange = { problem(412, "VERSION_CONFLICT") }
        val failed = assertNotNull(value(f.queue.dispatchNext(f.lease)))
        assertEquals(CommandPhase.NEEDS_RESOLUTION, failed.phase)
        assertEquals(CommandIssue.VERSION_CONFLICT, failed.issue)
        assertNull(value(f.queue.receipt(f.lease, COMMAND1)))
        failure(FailureReason.NOT_FOUND, f.repo.applyReceipt(f.lease, SESSION))
        val local = assertNotNull(value(f.repo.read(f.lease, SESSION)))
        assertEquals("step-two", local.progress.currentStepId)
        assertEquals("\"0007\"", local.etag)
        assertEquals(exactBody, f.store.text(key("action-body", COMMAND1)))
        assertEquals(listOf(COMMAND1), local.pendingCommandIds)
    }

    @Test fun refreshWithPendingEditsRetainsLocalAndRemoteConflictAndRejectsOlderCandidate() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val initial = value(f.repo.download(f.lease, SESSION))
        value(f.repo.edit(f.lease, SESSION, initial.localRevision, COMMAND1, CookingEdit.MoveTo("step-two")))
        f.sessionBody = session(version = 10, sequence = 5, status = "paused")
        f.sessionEtag = "\"10\""
        val conflict = value(f.repo.download(f.lease, SESSION))
        assertEquals(CookingAvailability.CONFLICT, conflict.availability)
        assertEquals("step-two", conflict.progress.currentStepId)
        assertEquals("7", conflict.remote.version.jsonToken)
        assertEquals("10", conflict.conflictingRemote!!.version.jsonToken)
        f.sessionBody = session(version = 9, sequence = 4)
        f.sessionEtag = "\"9\""
        failure(FailureReason.CONFLICT, f.repo.download(f.lease, SESSION))
        assertEquals("10", assertNotNull(value(f.repo.read(f.lease, SESSION))).conflictingRemote!!.version.jsonToken)
    }

    @Test fun receivedPlanRecallIsStickyAndPreventsAnAlreadyMaterializedPatchFromSending() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val initial = value(f.repo.download(f.lease, SESSION))
        value(f.repo.edit(f.lease, SESSION, initial.localRevision, COMMAND1, CookingEdit.MoveTo("step-two")))
        value(f.repo.materializeNext(f.lease, SESSION))
        f.planBody = plan(version = 4, status = "recalled")
        f.planEtag = "\"4\""
        assertEquals(CookingAvailability.RECALLED, value(f.repo.download(f.lease, SESSION)).availability)
        val before = f.calls.size
        val blocked = assertNotNull(value(f.queue.dispatchNext(f.lease)))
        assertEquals(CommandPhase.NEEDS_RESOLUTION, blocked.phase)
        assertEquals(before, f.calls.size)
        f.planBody = plan(version = 5)
        f.planEtag = "\"5\""
        assertEquals(CookingAvailability.RECALLED, value(f.repo.download(f.lease, SESSION)).availability)
        f.reopen()
        assertEquals(CookingAvailability.RECALLED, assertNotNull(value(f.repo.read(f.lease, SESSION))).availability)
    }

    @Test fun recallCannotBeHiddenByAChangedImmutablePlanOrMismatchedEtag() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.repo.download(f.lease, SESSION))
        val original = f.store.text(key("plan", SESSION))
        f.planBody = plan(version = 4, status = "recalled", quantity = "999")
        f.planEtag = "\"4\""
        failure(FailureReason.CONFLICT, f.repo.download(f.lease, SESSION))
        assertEquals(original, f.store.text(key("plan", SESSION)))
        assertEquals(CookingAvailability.RECALLED, assertNotNull(value(f.repo.read(f.lease, SESSION))).availability)
        val mismatch = Fixture(StandardTestDispatcher(testScheduler))
        value(mismatch.repo.download(mismatch.lease, SESSION))
        mismatch.planBody = plan(version = 4, reviewStatus = "recalled", recipeVersion = 2)
        mismatch.planEtag = "\"5\""
        failure(FailureReason.INVALID_DATA, mismatch.repo.download(mismatch.lease, SESSION))
        assertEquals(CookingAvailability.RECALLED, assertNotNull(value(mismatch.repo.read(mismatch.lease, SESSION))).availability)
    }

    @Test fun existingPinCannotChangeItsMaterializedRecipeOrPlanIdentity() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.repo.download(f.lease, SESSION))
        val original = f.store.detached()
        f.planBody = plan(version = 4, quantity = "1")
        f.planEtag = "\"4\""
        failure(FailureReason.CONFLICT, f.repo.download(f.lease, SESSION))
        assertEquals(original, f.store.detached())
        f.sessionBody = session(planId = VERSION, version = 8)
        f.sessionEtag = "\"8\""
        failure(FailureReason.CONFLICT, f.repo.download(f.lease, SESSION))
        assertEquals(original, f.store.detached())
    }

    @Test fun wrongReturnedIdsAndCrossVersionSnapshotFailBeforeAnyBundleCommit() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.sessionBody = session(id = PLAN)
        failure(FailureReason.INVALID_DATA, f.repo.download(f.lease, SESSION))
        assertTrue(f.store.records.isEmpty())
        f.sessionBody = session()
        f.planBody = plan(id = VERSION)
        failure(FailureReason.INVALID_DATA, f.repo.download(f.lease, SESSION))
        assertTrue(f.store.records.isEmpty())
        val root = Json.parseToJsonElement(plan()).jsonObject
        f.planBody = JsonObject(root + ("recipeVersionId" to JsonPrimitive(RECIPE))).toString()
        failure(FailureReason.INVALID_DATA, f.repo.download(f.lease, SESSION))
        assertTrue(f.store.records.isEmpty())
    }

    @Test fun unknownStepDuplicateCompletionAndTimerReferencesAreRejected() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.sessionBody = session(step = "unknown-step")
        failure(FailureReason.INVALID_DATA, f.repo.download(f.lease, SESSION))
        f.sessionBody = session(completed = listOf("step-one", "step-one"))
        failure(FailureReason.INVALID_DATA, f.repo.download(f.lease, SESSION))
        f.sessionBody = session(timers = listOf(timer(step = "unknown-step")))
        failure(FailureReason.INVALID_DATA, f.repo.download(f.lease, SESSION))
        f.sessionBody = session()
        val initial = value(f.repo.download(f.lease, SESSION))
        for (edit in listOf(CookingEdit.MoveTo("unknown-step"), CookingEdit.MarkStepComplete("unknown-step"),
            CookingEdit.ReplaceTimers(listOf(timer(), timer())), CookingEdit.SetStatus(CookingStatus.COMPLETED))) {
            failure(FailureReason.INVALID_DATA, f.repo.edit(f.lease, SESSION, initial.localRevision, COMMAND1, edit))
            assertTrue(assertNotNull(value(f.repo.read(f.lease, SESSION))).pendingCommandIds.isEmpty())
        }
    }

    @Test fun timerDeadlineRequirementsRejectInvalidEditsWhileMultipleStepTimersPersist() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val initial = value(f.repo.download(f.lease, SESSION))
        for (timer in listOf(timer(endAt = null), timer(status = "paused", endAt = null),
            timer(status = "paused", endAt = null, remaining = 61))) {
            failure(FailureReason.INVALID_DATA, f.repo.edit(f.lease, SESSION, initial.localRevision, COMMAND1, CookingEdit.ReplaceTimers(listOf(timer))))
        }
        val edit = CookingEdit.ReplaceTimers(listOf(timer(), timer(TIMER2, "step-two", "paused", null, 30)))
        val changed = value(f.repo.edit(f.lease, SESSION, initial.localRevision, COMMAND1, edit))
        assertEquals(2, changed.progress.timers.size)
        assertEquals("step-one", changed.progress.currentStepId)
        f.reopen()
        assertEquals(listOf("step-one", "step-two"), assertNotNull(value(f.repo.read(f.lease, SESSION))).progress.timers.map { it.stepId.value })
        value(f.repo.materializeNext(f.lease, SESSION)); value(f.queue.dispatchNext(f.lease))
        assertEquals(2, value(f.repo.applyReceipt(f.lease, SESSION)).remote.timers.size)
    }

    @Test fun incompletePersonalAndRetiredPinsHaveDistinctReadAndEditAvailability() = runTest {
        for ((body, expected) in listOf(
            plan(withRecipe = false) to CookingAvailability.INCOMPLETE,
            plan(reviewStatus = "personal") to CookingAvailability.PERSONAL_UNREVIEWED,
            plan(reviewStatus = "retired") to CookingAvailability.AVAILABLE,
        )) {
            val f = Fixture(StandardTestDispatcher(testScheduler))
            f.planBody = body
            val view = value(f.repo.download(f.lease, SESSION))
            assertEquals(expected, view.availability)
            if (expected == CookingAvailability.AVAILABLE)
                value(f.repo.edit(f.lease, SESSION, view.localRevision, COMMAND1, CookingEdit.MoveTo("step-two")))
            else failure(FailureReason.FORBIDDEN, f.repo.edit(f.lease, SESSION, view.localRevision, COMMAND1, CookingEdit.MoveTo("step-two")))
        }
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.sessionBody = session(timers = listOf(timer(endAt = null)))
        assertEquals(CookingAvailability.INCOMPLETE, value(f.repo.download(f.lease, SESSION)).availability)
    }

    @Test fun absentEtagAllowsPrivateReadingAndLocalIntentButNeverGuessedPatchPreconditions() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.sessionEtag = null
        val initial = value(f.repo.download(f.lease, SESSION))
        assertNull(initial.etag)
        value(f.repo.edit(f.lease, SESSION, initial.localRevision, COMMAND1, CookingEdit.MoveTo("step-two")))
        failure(FailureReason.NOT_CONFIGURED, f.repo.materializeNext(f.lease, SESSION))
        assertTrue(value(f.queue.pending(f.lease)).isEmpty())
        assertEquals(2, f.calls.size)
        f.sessionEtag = "\"8\""
        failure(FailureReason.INVALID_DATA, f.repo.download(f.lease, SESSION))
        assertNull(assertNotNull(value(f.repo.read(f.lease, SESSION))).etag)
    }

    @Test fun accountRetirementAcrossReadAwaitPreventsCachingOrExposure() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.afterExchange = { if (it.operationId == "getCookSession") f.boundary.clear() }
        failure(FailureReason.STALE_SESSION, f.repo.download(f.lease, SESSION))
        assertTrue(f.store.records.isEmpty())
        assertEquals(listOf("getCookSession"), f.calls.map { it.operationId })
        val replacement = f.boundary.activate(f.scope)
        failure(FailureReason.STALE_SESSION, f.repo.read(f.lease, SESSION))
        val foreign = f.boundary.activate(StorageScope("another-env", ActorKind.ACCOUNT, f.scope.actorId))
        failure(FailureReason.STALE_SESSION, f.repo.read(foreign, SESSION))
        assertNotSame(replacement, f.lease)
    }

    @Test fun changedOriginAndTamperedRequestsCannotPassTheRealExecutionGate() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val initial = value(f.repo.download(f.lease, SESSION))
        assertTrue(initial.originMatches)
        value(f.repo.edit(f.lease, SESSION, initial.localRevision, COMMAND1, CookingEdit.MoveTo("step-two")))
        value(f.repo.materializeNext(f.lease, SESSION))
        val body = f.store.records.getValue(key("action-body", COMMAND1)).payload
        fun intent(origin: String = ORIGIN, bytes: PrivateBytes = body, etag: String = "\"0007\"") = CommandIntent(COMMAND1, origin,
            ApiCall("updateCookSession", mapOf("sessionId" to SESSION), body = bytes, idempotencyKey = SecretText(COMMAND1), ifMatch = etag))
        assertEquals(ExecutionDecision.Ready, f.repo.executionDecision(f.lease, intent()))
        assertIs<ExecutionDecision.Wait>(f.repo.executionDecision(f.lease, intent(OTHER_ORIGIN)))
        assertIs<ExecutionDecision.Wait>(f.repo.executionDecision(f.lease, intent(etag = "\"7\"")))
        assertIs<ExecutionDecision.Wait>(f.repo.executionDecision(f.lease, intent(bytes = bytes("{}"))))
        f.reopen(OTHER_ORIGIN)
        val current = assertNotNull(value(f.repo.read(f.lease, SESSION)))
        assertFalse(current.originMatches)
        failure(FailureReason.CONFLICT, f.repo.edit(f.lease, SESSION, current.localRevision, COMMAND2, CookingEdit.MoveTo("step-one")))
        val count = f.calls.size
        assertEquals(CommandPhase.NEEDS_RESOLUTION, assertNotNull(value(f.queue.dispatchNext(f.lease))).phase)
        assertEquals(count, f.calls.size)
    }

    @Test fun explicitRecallObservationNeverDownloadsContentOrGrantsEditAuthority() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        assertFalse(value(f.repo.hasRecipeRecall(f.lease, PLAN)))
        assertTrue(f.calls.isEmpty()); assertTrue(f.store.commits.isEmpty())
        assertNull(value(f.repo.read(f.lease, SESSION)))
        failure(FailureReason.INVALID_DATA, f.repo.hasRecipeRecall(f.lease, "not-an-id"))
        f.boundary.clear()
        failure(FailureReason.STALE_SESSION, f.repo.hasRecipeRecall(f.lease, PLAN))
    }

    @Test fun appliedCommandIdsCannotBeReusedForANewLocalIntent() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val initial = value(f.repo.download(f.lease, SESSION))
        value(f.repo.edit(f.lease, SESSION, initial.localRevision, COMMAND1, CookingEdit.MoveTo("step-two")))
        value(f.repo.materializeNext(f.lease, SESSION)); value(f.queue.dispatchNext(f.lease))
        val applied = value(f.repo.applyReceipt(f.lease, SESSION))
        assertNull(f.store.records[key("action-body", COMMAND1)])
        assertNotNull(f.store.records[key("used-action", COMMAND1)])
        f.reopen()
        failure(FailureReason.CONFLICT, f.repo.edit(f.lease, SESSION, applied.localRevision, COMMAND1, CookingEdit.MoveTo("step-one")))
    }

    @Test fun corruptedExactPlanOrActionBytesFailClosedWithoutDispatch() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val initial = value(f.repo.download(f.lease, SESSION))
        value(f.repo.edit(f.lease, SESSION, initial.localRevision, COMMAND1, CookingEdit.MoveTo("step-two")))
        f.store.corrupt(key("action-body", COMMAND1), f.store.text(key("action-body", COMMAND1)) + " ")
        failure(FailureReason.INVALID_DATA, f.repo.materializeNext(f.lease, SESSION))
        assertTrue(value(f.queue.pending(f.lease)).isEmpty())
        f.store.corrupt(key("plan", SESSION), f.store.text(key("plan", SESSION)) + " ")
        failure(FailureReason.INVALID_DATA, f.repo.read(f.lease, SESSION))
        failure(FailureReason.INVALID_DATA, f.repo.list(f.lease))
        assertEquals(2, f.calls.size)
    }

    @Test fun downloadedConflictMatchingTheExactHeadReceiptCanBeAtomicallyAcknowledged() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val initial = value(f.repo.download(f.lease, SESSION))
        value(f.repo.edit(f.lease, SESSION, initial.localRevision, COMMAND1, CookingEdit.MoveTo("step-two")))
        value(f.repo.materializeNext(f.lease, SESSION)); value(f.queue.dispatchNext(f.lease))
        val candidate = value(f.repo.download(f.lease, SESSION))
        assertEquals(CookingAvailability.CONFLICT, candidate.availability)
        assertEquals("8", candidate.conflictingRemote!!.version.jsonToken)
        assertEquals(listOf(COMMAND1), candidate.pendingCommandIds)
        val applied = value(f.repo.applyReceipt(f.lease, SESSION))
        assertEquals(CookingAvailability.AVAILABLE, applied.availability)
        assertEquals("8", applied.remote.version.jsonToken)
        assertNull(applied.conflictingRemote)
        assertNull(f.store.records[key("conflict", SESSION)])
        assertTrue(applied.pendingCommandIds.isEmpty())
        assertNull(value(f.queue.receipt(f.lease, COMMAND1)))
    }

    @Test fun differentNewerConflictCannotBeOverwrittenByAnOlderSuccessfulReceipt() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val initial = value(f.repo.download(f.lease, SESSION))
        value(f.repo.edit(f.lease, SESSION, initial.localRevision, COMMAND1, CookingEdit.MoveTo("step-two")))
        value(f.repo.materializeNext(f.lease, SESSION)); value(f.queue.dispatchNext(f.lease))
        f.sessionBody = session(version = 10, sequence = 3, status = "paused")
        f.sessionEtag = "\"10\""
        value(f.repo.download(f.lease, SESSION))
        val before = f.store.detached()
        failure(FailureReason.CONFLICT, f.repo.applyReceipt(f.lease, SESSION))
        assertEquals(before, f.store.detached())
        assertEquals("10", assertNotNull(value(f.repo.read(f.lease, SESSION))).conflictingRemote!!.version.jsonToken)
        assertNotNull(value(f.queue.receipt(f.lease, COMMAND1)))
    }

    @Test fun nestedRecipeLifecycleRequiresItsOwnIncreasingVersionAndRecallStillWins() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.repo.download(f.lease, SESSION))
        f.planBody = plan(version = 4, reviewStatus = "retired", recipeVersion = 1)
        f.planEtag = "\"4\""
        failure(FailureReason.CONFLICT, f.repo.download(f.lease, SESSION))
        f.planBody = plan(version = 4, reviewStatus = "retired", recipeVersion = 2)
        assertEquals(CookingAvailability.AVAILABLE, value(f.repo.download(f.lease, SESSION)).availability)
        f.planBody = plan(version = 5, reviewStatus = "recalled", recipeVersion = 1)
        f.planEtag = "\"5\""
        failure(FailureReason.CONFLICT, f.repo.download(f.lease, SESSION))
        assertEquals(CookingAvailability.RECALLED, assertNotNull(value(f.repo.read(f.lease, SESSION))).availability)
    }

    @Test fun mutationRecallObserverInvalidatesBothCookAndSavedPinButOrdinaryGoneDoesNot() = runTest {
        for (code in listOf("RECIPE_RECALLED", "SOURCE_UNAVAILABLE")) {
            val f = Fixture(StandardTestDispatcher(testScheduler))
            val initial = value(f.repo.download(f.lease, SESSION))
            val saved = f.savedRepository()
            assertEquals(SavedRecipeAvailability.AVAILABLE, value(saved.download(f.lease, SAVED)).availability)
            value(f.repo.edit(f.lease, SESSION, initial.localRevision, COMMAND1, CookingEdit.MoveTo("step-two")))
            value(f.repo.materializeNext(f.lease, SESSION))
            f.writeExchange = { problem(410, code) }
            assertEquals(CommandPhase.NEEDS_RESOLUTION, assertNotNull(value(f.queue.dispatchNext(f.lease))).phase)
            val recalled = code == "RECIPE_RECALLED"
            assertEquals(if (recalled) CookingAvailability.RECALLED else CookingAvailability.AVAILABLE,
                assertNotNull(value(f.repo.read(f.lease, SESSION))).availability)
            assertEquals(if (recalled) SavedRecipeAvailability.RECALLED else SavedRecipeAvailability.AVAILABLE,
                assertNotNull(value(f.savedRepository().read(f.lease, SAVED))).availability)
            assertNotNull(f.store.records[key("action-body", COMMAND1)])
        }
    }

    @Test fun terminalRemoteSessionCannotBeReopenedByANewerHydration() = runTest {
        for (status in listOf("completed", "abandoned")) {
            val f = Fixture(StandardTestDispatcher(testScheduler))
            f.sessionBody = session(status = status)
            val terminal = value(f.repo.download(f.lease, SESSION))
            failure(FailureReason.CONFLICT, f.repo.edit(f.lease, SESSION, terminal.localRevision, COMMAND1, CookingEdit.MoveTo("step-two")))
            f.sessionBody = session(version = 8, status = "active")
            f.sessionEtag = "\"8\""
            failure(FailureReason.CONFLICT, f.repo.download(f.lease, SESSION))
            assertEquals(status, assertNotNull(value(f.repo.read(f.lease, SESSION))).remote.status)
        }
    }

    @Test fun timerMetadataIsAtomicWithProgressAndOriginalImmutableCommandBody() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val initial = value(f.repo.download(f.lease, SESSION))
        val timer = timerFixture()
        val next = value(f.repo.editTimersWithMetadata(f.lease, SESSION, initial.localRevision, COMMAND1, listOf(timer), null, bytes("private-clock-and-ticket")))
        assertEquals(listOf(COMMAND1), next.pendingCommandIds)
        val batch = f.store.commits.last()
        assertTrue(batch.any { it.key == key("timer-metadata", SESSION) })
        assertTrue(batch.any { it.key == key("progress", SESSION) }); assertTrue(batch.any { it.key == key("action-body", COMMAND1) })
        val body = f.store.text(key("action-body", COMMAND1))
        assertTrue(body.contains("timerId")); assertFalse(body.contains("private-clock")); assertFalse(body.contains("ticket"))
        assertTrue(value(f.repo.readTimerMetadata(f.lease, SESSION))!!.matchesTimers)
    }

    @Test fun rejectedMappingCasCannotPartiallyAppendCookingIntent() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val initial = value(f.repo.download(f.lease, SESSION)); val before = f.store.detached()
        f.store.failWhen = { batch -> batch.any { it.key == key("timer-metadata", SESSION) } }
        failure(FailureReason.CONFLICT, f.repo.editTimersWithMetadata(f.lease, SESSION, initial.localRevision, COMMAND1, listOf(timerFixture()), null, bytes("private")))
        assertEquals(before, f.store.detached()); assertNull(value(f.repo.readTimerMetadata(f.lease, SESSION)))
    }

    @Test fun mappingReackChangesRealRevisionsWithoutRewritingImmutableIntent() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val initial = value(f.repo.download(f.lease, SESSION))
        val next = value(f.repo.editTimersWithMetadata(f.lease, SESSION, initial.localRevision, COMMAND1, listOf(timerFixture()), null, bytes("private")))
        val old = value(f.repo.readTimerMetadata(f.lease, SESSION))!!; val body = f.store.text(key("action-body", COMMAND1))
        val ack = value(f.repo.acknowledgeTimerMetadata(f.lease, SESSION, next.localRevision, old.record, bytes("private")))
        assertEquals(old.record.revision + 1, ack.record.revision); assertTrue(ack.matchesTimers)
        assertEquals(next.localRevision + 1, value(f.repo.read(f.lease, SESSION))!!.localRevision)
        assertEquals(body, f.store.text(key("action-body", COMMAND1))); assertEquals(listOf(COMMAND1), value(f.repo.read(f.lease, SESSION))!!.pendingCommandIds)
    }

    @Test fun staleOrAlteredMappingPredecessorCannotReplaceNewerEvidence() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val initial = value(f.repo.download(f.lease, SESSION))
        val next = value(f.repo.editTimersWithMetadata(f.lease, SESSION, initial.localRevision, COMMAND1, listOf(timerFixture()), null, bytes("private")))
        val old = value(f.repo.readTimerMetadata(f.lease, SESSION))!!; val before = f.store.detached()
        failure(FailureReason.CONFLICT, f.repo.acknowledgeTimerMetadata(f.lease, SESSION, next.localRevision, old.record.copy(payload = bytes("altered")), bytes("new")))
        failure(FailureReason.CONFLICT, f.repo.acknowledgeTimerMetadata(f.lease, SESSION, initial.localRevision, old.record, bytes("new")))
        assertEquals(before, f.store.detached())
    }

    @Test fun ordinaryTimerReplacementMakesMappingCleanupOnlyAndReackCannotRebindIt() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val initial = value(f.repo.download(f.lease, SESSION))
        val next = value(f.repo.editTimersWithMetadata(f.lease, SESSION, initial.localRevision, COMMAND1, listOf(timerFixture()), null, bytes("private")))
        val changed = value(f.repo.edit(f.lease, SESSION, next.localRevision, COMMAND2, CookingEdit.ReplaceTimers(emptyList())))
        val old = value(f.repo.readTimerMetadata(f.lease, SESSION))!!; assertFalse(old.matchesTimers)
        assertFalse(value(f.repo.acknowledgeTimerMetadata(f.lease, SESSION, changed.localRevision, old.record, bytes("cancelled"))).matchesTimers)
    }

    @Test fun mappingCannotCrossOriginOrRevokedLeaseAndReadHasNoWrites() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val initial = value(f.repo.download(f.lease, SESSION))
        value(f.repo.editTimersWithMetadata(f.lease, SESSION, initial.localRevision, COMMAND1, listOf(timerFixture()), null, bytes("private")))
        val count = f.store.commits.size; value(f.repo.readTimerMetadata(f.lease, SESSION)); assertEquals(count, f.store.commits.size)
        f.reopen(COMMAND2); failure(FailureReason.CONFLICT, f.repo.readTimerMetadata(f.lease, SESSION))
        f.boundary.clear(); failure(FailureReason.STALE_SESSION, f.repo.readTimerMetadata(f.lease, SESSION)); assertEquals(count, f.store.commits.size)
    }

    @Test fun mappingPayloadBoundsFailBeforeAnyAtomicMutation() = runTest {
        for (size in listOf(0, 16001)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); val initial = value(f.repo.download(f.lease, SESSION)); val before = f.store.detached()
            failure(FailureReason.INVALID_DATA, f.repo.editTimersWithMetadata(f.lease, SESSION, initial.localRevision, COMMAND1, listOf(timerFixture()), null, PrivateBytes(ByteArray(size))))
            assertEquals(before, f.store.detached())
        }
    }

    @Test fun mappingAdmissionStillUsesPinnedStepAndRecallGuards() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val initial = value(f.repo.download(f.lease, SESSION))
        val unknown = WireDocument.parse(timerFixture().encodeUtf8().decodeToString().replace("step-one", "foreign"))
        failure(FailureReason.INVALID_DATA, f.repo.editTimersWithMetadata(f.lease, SESSION, initial.localRevision, COMMAND1, listOf(unknown), null, bytes("private")))
        assertNull(value(f.repo.readTimerMetadata(f.lease, SESSION)))
        f.planBody = plan(version = 4, status = "recalled")
        f.planEtag = "\"4\""
        val recalled = value(f.repo.download(f.lease, SESSION)); assertEquals(CookingAvailability.RECALLED, recalled.availability)
        failure(FailureReason.FORBIDDEN, f.repo.editTimersWithMetadata(f.lease, SESSION, recalled.localRevision, COMMAND1, listOf(timerFixture()), null, bytes("private")))
    }

    private fun timerFixture() = WireDocument.parse("""{"timerId":"$COMMAND2","stepId":"step-one","status":"paused","durationSeconds":60,"pausedRemainingSeconds":60}""")

    private class Fixture(private val dispatcher: CoroutineDispatcher) {
        val scope = StorageScope("cook-test", ActorKind.ACCOUNT, "private-cook-owner-${nextOwner++}")
        val boundary = SessionBoundary()
        val lease = boundary.activate(scope)
        val store = CookCasStore(scope)
        val calls = mutableListOf<ApiCall>()
        var now = START
        var planBody = plan()
        var sessionBody = session()
        var planEtag: String? = "\"3\""
        var sessionEtag: String? = "\"0007\""
        var afterExchange: suspend (ApiCall) -> Unit = {}
        var writeExchange: suspend (ApiCall) -> PortResult<ApiReply> = { echoMutation(it) }
        private val transport = object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                calls += call
                val result = when (call.operationId) {
                    "getCookSession" -> reply(sessionBody, sessionEtag)
                    "getPlan" -> reply(planBody, planEtag)
                    "getSavedRecipe" -> reply(saved(), "\"1\"")
                    else -> writeExchange(call)
                }
                afterExchange(call)
                return result
            }
        }
        lateinit var queue: DurableCommandQueue
        lateinit var repo: CookingRepository
        init { reopen() }
        fun reopen(origin: String = ORIGIN) {
            queue = DurableCommandQueue(scope, store, boundary, dispatcher, EpochClock { now },
                CommandExecutionGate { lease, intent -> repo.executionDecision(lease, intent) }, transport,
                replyObserver = CommandReplyObserver { lease, intent, reply -> repo.observeReply(lease, intent, reply) })
            repo = CookingRepository(scope, store, boundary, dispatcher, EpochClock { now }, transport, origin, queue)
        }
        fun savedRepository() = SavedRecipeRepository(scope, store, boundary, dispatcher, EpochClock { now }, transport)
        private fun echoMutation(call: ApiCall): PortResult<ApiReply> {
            val old = Json.parseToJsonElement(sessionBody).jsonObject
            val request = json(call.body!!)
            val version = old.getValue("version").jsonPrimitive.long + 1
            val fields = if (call.operationId == "completeCookSession") mapOf(
                "status" to JsonPrimitive("completed"), "deviceSequence" to request.getValue("deviceSequence"),
                "completedAt" to JsonPrimitive("2026-09-13T08:06:00Z"),
            ) else request
            sessionBody = JsonObject(old + fields + ("version" to JsonPrimitive(version))).toString()
            sessionEtag = "\"$version\""
            return reply(sessionBody, sessionEtag)
        }
    }

    private class CookCasStore(private val owner: StorageScope) : PrivateStateStore {
        val records = mutableMapOf<RecordKey, PrivateRecord>()
        private val revisions = mutableMapOf<RecordKey, Long>()
        val commits = mutableListOf<List<StoreMutation>>()
        var failWhen: (List<StoreMutation>) -> Boolean = { false }
        var afterRead: suspend (RecordKey) -> Unit = {}
        fun text(key: RecordKey) = records.getValue(key).payload.copyForCodec().decodeToString()
        fun detached() = records.mapValues { (_, record) -> record.revision to record.payload.copyForCodec().decodeToString() }
        fun corrupt(key: RecordKey, text: String) { records[key] = records.getValue(key).copy(payload = bytes(text)) }
        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> {
            if (scope != owner) return PortResult.Failure(FailureReason.STALE_SESSION)
            val value = records[key]?.let { it.copy(payload = PrivateBytes(it.payload.copyForCodec())) }
            afterRead(key)
            return PortResult.Value(value)
        }
        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> {
            if (scope != owner) return PortResult.Failure(FailureReason.STALE_SESSION)
            if (mutations.size !in 1..64 || mutations.map { it.key }.distinct().size != mutations.size)
                return PortResult.Failure(FailureReason.INVALID_DATA)
            if (failWhen(mutations) || mutations.any { it.expectedRevision != records[it.key]?.revision })
                return PortResult.Failure(FailureReason.CONFLICT)
            val result = mutableMapOf<RecordKey, Long?>()
            for (mutation in mutations) {
                val revision = (revisions[mutation.key] ?: 0) + 1
                revisions[mutation.key] = revision
                when (mutation) {
                    is StoreMutation.Put -> {
                        records[mutation.key] = PrivateRecord(revision, mutation.schemaVersion, PrivateBytes(mutation.payload.copyForCodec()))
                        result[mutation.key] = revision
                    }
                    is StoreMutation.Delete -> { records.remove(mutation.key); result[mutation.key] = null }
                }
            }
            commits += mutations.toList()
            return PortResult.Value(result)
        }
        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> = error("Domain adapter must not activate or erase an owner")
    }

    companion object {
        private var nextOwner = 1
        private const val SESSION = "123e4567-e89b-12d3-a456-426614174001"
        private const val PLAN = "123e4567-e89b-12d3-a456-426614174002"
        private const val VERSION = "123e4567-e89b-12d3-a456-426614174003"
        private const val RECIPE = "123e4567-e89b-12d3-a456-426614174004"
        private const val INGREDIENT = "123e4567-e89b-12d3-a456-426614174005"
        private const val SAVED = "123e4567-e89b-12d3-a456-426614174006"
        private const val COMMAND1 = "123e4567-e89b-12d3-a456-426614174011"
        private const val COMMAND2 = "123e4567-e89b-12d3-a456-426614174012"
        private const val ORIGIN = "123e4567-e89b-12d3-a456-426614174090"
        private const val OTHER_ORIGIN = "123e4567-e89b-12d3-a456-426614174091"
        private const val TIMER1 = "123e4567-e89b-12d3-a456-426614174070"
        private const val TIMER2 = "123e4567-e89b-12d3-a456-426614174071"
        private const val START = 1_800_000_000_000L
        private val INDEX = RecordKey("feedme.kitchen.cook.index", "v1")
        private fun key(part: String, id: String) = RecordKey("feedme.kitchen.cook.$part", id)
        private fun bytes(text: String) = PrivateBytes(text.encodeToByteArray())
        private fun json(bytes: PrivateBytes) = Json.parseToJsonElement(bytes.copyForCodec().decodeToString()).jsonObject
        private fun reply(body: String, etag: String? = "\"7\""): PortResult<ApiReply> =
            PortResult.Value(ApiReply(200, bytes(body), etag = etag, contentType = "application/json"))
        private fun problem(status: Int, code: String): PortResult<ApiReply> = PortResult.Value(ApiReply(status,
            bytes("""{"type":"https://example.test/problems/cook","title":"Cooking unavailable","status":$status,"code":"$code","traceId":"test-trace"}"""),
            contentType = "application/problem+json"))
        private fun timer(id: String = TIMER1, step: String = "step-one", status: String = "running",
            endAt: String? = "2026-09-13T08:01:00Z", remaining: Int? = null): WireDocument = WireDocument.parse(buildJsonObject {
            put("timerId", id); put("stepId", step); put("status", status); put("durationSeconds", 60)
            endAt?.let { put("endAt", it) }; remaining?.let { put("pausedRemainingSeconds", it) }
        }.toString())
        private fun <T> value(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
        private fun failure(reason: FailureReason, result: PortResult<*>) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
        private fun session(id: String = SESSION, planId: String = PLAN, version: Int = 7, status: String = "active",
            step: String = "step-one", sequence: Int = 0, completed: List<String> = emptyList(), timers: List<WireDocument> = emptyList()): String = buildJsonObject {
            put("id", id); put("version", version); put("createdAt", "2026-09-13T07:00:00Z"); put("updatedAt", "2026-09-13T08:00:00Z")
            put("planId", planId); put("status", status); put("currentStepId", step); put("deviceSequence", sequence)
            put("completedStepIds", JsonArray(completed.map(::JsonPrimitive)))
            put("timers", JsonArray(timers.map { Json.parseToJsonElement(it.encodeUtf8().decodeToString()) }))
        }.toString()
        private fun plan(id: String = PLAN, version: Int = 3, status: String = "ready", reviewStatus: String = "published", recipeVersion: Int = 1,
            quantity: String = "2.7500", withRecipe: Boolean = true): String = buildJsonObject {
            put("id", id); put("version", version); put("createdAt", "2026-09-13T07:00:00Z"); put("updatedAt", "2026-09-13T08:00:00Z")
            put("mode", "cook"); put("status", status); put("recipeVersionId", VERSION); put("catalogRevision", "catalog-test-1")
            put("missingIngredients", JsonArray(emptyList())); put("changes", JsonArray(emptyList())); put("reasons", JsonArray(emptyList()))
            put("constraints", buildJsonObject {
                put("ingredientIds", JsonArray(listOf(JsonPrimitive(INGREDIENT)))); put("energy", "little")
                put("equipmentIds", JsonArray(listOf(JsonPrimitive("bowl")))); put("servings", 1)
                put("hardExcludedIngredientIds", JsonArray(emptyList()))
            })
            if (withRecipe) put("recipeSnapshot", buildJsonObject {
                put("id", VERSION); put("recipeId", RECIPE); put("version", recipeVersion)
                put("createdAt", "2026-09-13T07:00:00Z"); put("updatedAt", "2026-09-13T08:00:00Z")
                put("title", "Private materialized lunch"); put("reviewStatus", reviewStatus); put("servings", 1)
                put("activeMinutes", 5); put("totalMinutes", 5); put("utensilCount", 1)
                put("equipmentIds", JsonArray(listOf(JsonPrimitive("bowl")))); put("modes", JsonArray(listOf(JsonPrimitive("cook"))))
                put("tasteTags", JsonArray(emptyList()))
                put("ingredients", buildJsonArray { add(buildJsonObject {
                    put("ingredientId", INGREDIENT); put("quantity", Json.parseToJsonElement(quantity)); put("unit", "g"); put("optional", false)
                }) })
                put("steps", buildJsonArray { for ((position, step) in listOf("step-one", "step-two").withIndex()) add(buildJsonObject {
                    put("stepId", step); put("position", position + 1); put("instruction", "private instruction $step")
                    put("ingredientIds", JsonArray(listOf(JsonPrimitive(INGREDIENT))))
                    put("requiredEquipmentIds", JsonArray(listOf(JsonPrimitive("bowl")))); put("mandatorySafetyStep", false)
                }) })
            })
        }.toString()
        private fun saved(): String = buildJsonObject {
            put("id", SAVED); put("version", 1); put("title", "Private saved lunch")
            put("createdAt", "2026-09-13T07:00:00Z"); put("updatedAt", "2026-09-13T08:00:00Z")
            put("sourceType", "ownPlan"); put("recalled", false)
            put("snapshot", Json.parseToJsonElement(plan()).jsonObject.getValue("recipeSnapshot"))
        }.toString()
    }
}
