package com.feedme.mealflow

import com.feedme.contracts.*
import com.feedme.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Protocol fixtures: fake authenticated backend/atomic store, NOT provider, review or crypto proof. */
@OptIn(ExperimentalCoroutinesApi::class)
class MealRequestControllerTest {
    @Test fun manualBuilderUsesOnlyCanonicalFieldsAndMergesHardExclusionsWithoutPantryInference() {
        val source = draft(exclusions = listOf(OTHER), ingredients = emptyList())
        val body = MealRequestBuilder().build(source, preferences(exclusions = listOf(INGREDIENT))).value()
        assertEquals(setOf("mode", "constraints", "preferenceVersion"), body.json().jsonObject.keys)
        assertEquals(setOf(INGREDIENT, OTHER), body.json().jsonObject.getValue("constraints").jsonObject.getValue("hardExcludedIngredientIds").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertTrue(body.json().jsonObject.getValue("constraints").jsonObject.getValue("ingredientIds").jsonArray.isEmpty())
        assertEquals(ContractValidationResult.Valid, VALIDATOR.validateSchema("PlanRequest", body.encodeUtf8()))
    }

    @Test fun builderRejectsMalformedDuplicatesInvalidModesAndUnsupportedNumbersWithoutThrowingPrivateData() {
        for (value in listOf(draft(ingredients = listOf("secret-invalid")), draft(ingredients = listOf(INGREDIENT, INGREDIENT)),
            draft(servings = "NaN"), draft(servings = "0"), draft(total = "0"), draft(mode = MealMode.IMPROVE),
            draft(equipment = listOf("bowl", "bowl")))) {
            failure(MealRequestBuilder().build(value, preferences()), FailureReason.INVALID_DATA)
        }
        failure(MealRequestBuilder().build(draft(pending = true), preferences()), FailureReason.CONFLICT)
    }

    @Test fun decimalSpellingAndPreparedMealArePreservedWithoutAnInterpretationOrSourceIssuer() {
        val d = draft(mode = MealMode.IMPROVE, servings = "1.2500", base = ManualBaseMeal("Private base", BasePreparation.UNKNOWN, null))
        val body = MealRequestBuilder().build(d, preferences()).value().json().jsonObject
        assertEquals("1.2500", body.getValue("constraints").jsonObject.getValue("servings").jsonPrimitive.content)
        assertEquals("unknown", body.getValue("baseMeal").jsonObject.getValue("preparationState").jsonPrimitive.content)
        assertFalse(body.containsKey("confirmedInterpretation")); assertFalse(body.containsKey("sourcePostId"))
    }

    @Test fun successPersistsExactRequestBeforeTransportAndPublishesWholeCanonicalPlanAfterReceipt() = runTest {
        fixture { f ->
            f.controller.edit(draft()).value()
            f.beforeSend = { call ->
                val stored = f.store.onlyText()
                assertTrue(stored.contains(call.idempotencyKey!!.use { it }))
                assertEquals(call.body!!.copyForCodec().decodeToString(), f.record().command!!.body.encodeUtf8().decodeToString())
            }
            val state = f.controller.submit().value()
            assertEquals(MealFlowPhase.READY, state.phase); assertEquals(MealFlowScreen.RECOMMENDATIONS, state.screen)
            assertNotNull(state.plan); assertFalse(state.plan.historical)
            assertEquals("Synthetic reviewed fixture", (state.plan.plan.recipeSnapshot as WireField.Value).value.title)
            assertNull(f.record().command); assertEquals(1, f.sends().size)
        }
    }

    @Test fun guestAccessUsesRealLeaseClassWithoutAccountOrSocialRequirement() = runTest {
        fixture(guest = true) { f ->
            f.controller.edit(draft()).value(); assertEquals(MealFlowPhase.READY, f.controller.submit().value().phase)
            assertEquals(ActorKind.GUEST, f.lease.scope.actorKind)
            assertTrue(f.calls.all { it.operationId in setOf("getPreferences", "createPlan") })
        }
    }

    @Test fun bothSuccessfulNonCookableStatusesAllowMissingModeAndNeverPublishRecipe() = runTest {
        for (status in listOf("needsConfirmation", "noMatch")) fixture { f ->
            f.status = status; f.controller.edit(draft()).value()
            val state = f.controller.submit().value()
            assertEquals(if (status == "noMatch") MealFlowPhase.NO_MATCH else MealFlowPhase.NEEDS_CONFIRMATION, state.phase)
            assertTrue(state.plan!!.plan.mode is WireField.Missing)
            assertTrue(state.plan.plan.recipeSnapshot is WireField.Missing)
            failure(f.controller.openRecipe(), FailureReason.CONFLICT)
        }
    }

    @Test fun offlineInitialSubmissionRetainsDraftWithoutGeneratingKeyOrCallingBackend() = runTest {
        fixture { f ->
            f.online = false; f.controller.edit(draft()).value()
            assertEquals(MealFlowPhase.OFFLINE_DRAFT, f.controller.submit().value().phase)
            assertTrue(f.calls.isEmpty()); assertEquals(0, f.idCalls); assertNull(f.record().command)
        }
    }

    @Test fun offlinePrivateSessionNeverUpgradesToNetworkEvenWhenConnectivitySaysOnline() = runTest {
        fixture(onlineAllowed = false) { f ->
            f.controller.edit(draft()).value(); assertEquals(MealFlowPhase.OFFLINE_DRAFT, f.controller.submit().value().phase)
            f.controller.refreshContext().value(); assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun contextHydrationPreservesFullBoundedPantryPageAndDoesNotAutoSelectUsualItems() = runTest {
        fixture { f ->
            f.controller.edit(draft(ingredients = emptyList())).value()
            val context = f.controller.refreshContext().value()
            assertEquals("unfetched-page", context.pantryPage!!.json().jsonObject.getValue("nextCursor").jsonPrimitive.content)
            assertEquals(listOf("getPreferences", "listPantry", "getPreferences"), f.calls.map { it.operationId })
            f.controller.submit().value()
            assertTrue(f.sends().single().body!!.copyForCodec().decodeToString().contains("\"ingredientIds\":[]"))
        }
    }

    @Test fun changedPreferencesDuringPantryAwaitRejectsMixedSnapshot() = runTest {
        fixture { f ->
            f.controller.edit(draft()).value()
            f.handler = { call -> if (call.operationId == "listPantry") { f.preference = preferences(version = "2"); f.defaultReply(call) } else f.defaultReply(call) }
            failure(f.controller.refreshContext(), FailureReason.CONFLICT)
            assertNull(f.record().preferences); assertNull(f.record().pantry)
        }
    }

    @Test fun pendingLocalPreferenceEditBlocksSubmissionWithoutRelaxingExclusions() = runTest {
        fixture { f ->
            f.controller.edit(draft(exclusions = listOf(INGREDIENT), pending = true)).value()
            assertEquals(MealFlowIssue.PREFERENCES_PENDING, f.controller.submit().value().issue)
            assertTrue(f.calls.isEmpty()); assertNull(f.record().command)
        }
    }

    @Test fun journalFailureBeforeOrAfterCommitNeverAuthorizesTransportAndRetryReacksVisibleIntent() = runTest {
        for (after in listOf(false, true)) fixture { f ->
            f.controller.edit(draft()).value()
            f.store.fail = if (after) StoreFailure.AFTER else StoreFailure.BEFORE
            failure(f.controller.submit(), FailureReason.OUTCOME_UNKNOWN)
            assertTrue(f.sends().isEmpty())
            if (after) {
                val key = f.record().command!!.id; val revision = f.store.current!!.revision
                f.beforeSend = { assertTrue(f.store.current!!.revision > revision) }
                f.controller.retrySubmitted().value()
                assertEquals(key, f.sends().single().idempotencyKey!!.use { it })
            } else assertNull(f.record().command)
        }
    }

    @Test fun falseRevisionOrReadbackReceiptCannotAuthorizeDispatch() = runTest {
        for (bad in listOf(StoreFailure.BAD_REVISION, StoreFailure.BAD_READBACK)) fixture { f ->
            f.controller.edit(draft()).value(); f.store.fail = bad
            assertIs<PortResult.Failure>(f.controller.submit()); assertTrue(f.sends().isEmpty())
        }
    }

    @Test fun lostResponseRecreatedControllerRetriesOriginalExactKeyBodyAndThenPublishes() = runTest {
        fixture { f ->
            f.controller.edit(draft(servings = "1.0")).value()
            f.handler = { call -> if (call.operationId == "createPlan") PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) else f.defaultReply(call) }
            assertEquals(MealFlowPhase.RESOLVING, f.controller.submit().value().phase)
            val original = f.sends().single(); f.reopen()
            assertEquals(MealFlowPhase.RESOLVING, f.controller.restore().value().phase)
            f.handler = { f.defaultReply(it) }; f.controller.retrySubmitted().value()
            val retry = f.sends().last()
            assertEquals(original.idempotencyKey!!.use { it }, retry.idempotencyKey!!.use { it })
            assertContentEquals(original.body!!.copyForCodec(), retry.body!!.copyForCodec()); assertEquals(1, f.idCalls)
        }
    }

    @Test fun cancelledDispatchedCommandRemainsResolvingAndNeverRotatesItsIdentity() = runTest {
        fixture { f ->
            f.controller.edit(draft()).value(); val entered = CompletableDeferred<Unit>(); val wait = CompletableDeferred<Unit>()
            f.handler = { call -> if (call.operationId == "createPlan") { entered.complete(Unit); wait.await(); f.defaultReply(call) } else f.defaultReply(call) }
            val job = launch { f.controller.submit() }; entered.await(); job.cancelAndJoin()
            assertEquals(MealFlowPhase.RESOLVING, f.controller.states.value.phase)
            val key = f.record().command!!.id
            f.handler = { f.defaultReply(it) }; f.controller.retrySubmitted().value()
            assertEquals(key, f.sends().last().idempotencyKey!!.use { it }); assertEquals(1, f.idCalls)
        }
    }

    @Test fun boundaryInvalidationImmediatelyRedactsReactiveStateAndFencesNoncooperativeReply() = runTest {
        fixture { f ->
            f.controller.edit(draft()).value(); val entered = CompletableDeferred<Unit>(); val wait = CompletableDeferred<Unit>()
            f.handler = { call -> if (call.operationId == "createPlan") { entered.complete(Unit); withContext(NonCancellable) { wait.await() }; f.defaultReply(call) } else f.defaultReply(call) }
            val job = async { f.controller.submit() }; entered.await()
            f.boundary.activate(f.scope)
            assertEquals(MealFlowPhase.UNAVAILABLE, f.controller.states.value.phase); assertNull(f.controller.states.value.draft)
            wait.complete(Unit); failure(job.await(), FailureReason.STALE_SESSION)
            assertNull(f.controller.states.value.plan); assertNotNull(f.record().command)
        }
    }

    @Test fun staleDuringStoreReadCannotExposeOldOwnerDraftOrCallBackend() = runTest {
        fixture { f ->
            f.controller.edit(draft()).value()
            f.store.afterRead = { f.boundary.clear(); f.store.afterRead = null }
            failure(f.controller.submit(), FailureReason.STALE_SESSION)
            assertNull(f.controller.states.value.draft); assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun closeDuringAwaitIsImmediateRedactionWithoutClosingBorrowedResourcesOrClearingNewLease() = runTest {
        fixture { f ->
            f.controller.edit(draft()).value(); val entered = CompletableDeferred<Unit>(); val wait = CompletableDeferred<Unit>()
            f.handler = { call -> entered.complete(Unit); wait.await(); f.defaultReply(call) }
            val pending = async { f.controller.submit() }; entered.await(); f.controller.close().value()
            val newer = f.boundary.activate(f.scope); wait.complete(Unit)
            failure(pending.await(), FailureReason.STALE_SESSION); assertSame(newer, f.boundary.current())
            assertNull(f.controller.states.value.draft); assertEquals(0, f.store.erases)
        }
    }

    @Test fun editsFenceLateReceiptPreserveUnresolvedBodyAndInvalidateOldCandidateContext() = runTest {
        fixture { f ->
            f.controller.edit(draft()).value(); val entered = CompletableDeferred<Unit>(); val wait = CompletableDeferred<Unit>()
            f.handler = { call -> if (call.operationId == "createPlan") { entered.complete(Unit); wait.await(); f.defaultReply(call) } else f.defaultReply(call) }
            val old = async { f.controller.submit() }; entered.await()
            val edited = async { f.controller.edit(draft(total = "8")) }; runCurrent(); wait.complete(Unit)
            failure(old.await(), FailureReason.STALE_SESSION); val state = edited.await().value()
            assertEquals(MealFlowPhase.RESOLVING, state.phase); assertFalse(state.pendingMatchesDraft)
            f.handler = { f.defaultReply(it) }; val resolved = f.controller.retrySubmitted().value()
            assertEquals(MealFlowPhase.EDITING, resolved.phase); assertTrue(resolved.plan!!.historical)
        }
    }

    @Test fun rapidDuplicateSubmissionDoesNotCreateTwoCommands() = runTest {
        fixture { f ->
            f.controller.edit(draft()).value(); val entered = CompletableDeferred<Unit>(); val wait = CompletableDeferred<Unit>()
            f.handler = { call -> if (call.operationId == "createPlan") { entered.complete(Unit); wait.await(); f.defaultReply(call) } else f.defaultReply(call) }
            val first = async { f.controller.submit() }; entered.await()
            failure(f.controller.submit(), FailureReason.CONFLICT); assertEquals(1, f.idCalls)
            wait.complete(Unit); first.await().value(); assertEquals(1, f.sends().size)
        }
    }

    @Test fun alternativeUsesCanonicalParentCursorConstraintsNoIfMatchAndBoundedHistory() = runTest {
        fixture { f ->
            f.controller.edit(draft()).value(); val first = f.controller.submit().value()
            val second = f.controller.nextAlternative().value()
            val call = f.sends().last(); assertEquals("nextPlan", call.operationId)
            assertNull(call.ifMatch); assertEquals(first.plan!!.plan.id.value, call.pathParameters["planId"])
            val body = WireDocument.decode(call.body!!.copyForCodec()).json().jsonObject
            assertEquals("cursor-1", body.getValue("continuationCursor").jsonPrimitive.content)
            assertEquals(first.plan.plan.constraints.json(), body.getValue("constraints"))
            assertEquals(first.plan.plan.id, (second.plan!!.plan.parentPlanId as WireField.Value).value)
            assertEquals(2, second.history.size)
        }
    }

    @Test fun fetchedPreviousAndNextCanBeBrowsedOfflineWithoutNewIdOrNetwork() = runTest {
        fixture { f ->
            f.controller.edit(draft()).value(); f.controller.submit().value(); f.controller.nextAlternative().value()
            val calls = f.calls.size; val ids = f.idCalls; f.online = false
            val previous = f.controller.previousPlan().value(); val next = f.controller.nextAlternative().value()
            assertNotEquals(previous.plan!!.plan.id, next.plan!!.plan.id)
            assertTrue(next.plan.historical); assertEquals(calls, f.calls.size); assertEquals(ids, f.idCalls)
            f.controller.openRecipe().value(); assertEquals(MealFlowScreen.RECIPE, f.controller.states.value.screen)
        }
    }

    @Test fun changedDraftOrPreferenceVersionInvalidatesAlternativesWithoutNewCommand() = runTest {
        for (edit in listOf(false, true)) fixture { f ->
            f.controller.edit(draft()).value(); f.controller.submit().value()
            if (edit) f.controller.edit(draft(total = "9")).value() else f.preference = preferences(version = "2")
            val state = f.controller.nextAlternative().value()
            assertEquals(MealFlowIssue.CONTEXT_CHANGED, state.issue); assertFalse(state.alternativesAvailable)
            assertEquals(1, f.idCalls); assertEquals(1, f.sends().size)
        }
    }

    @Test fun malformedOrMismatchedSuccessNeverBecomesReadyOrDropsPendingIntent() = runTest {
        for (fault in listOf("etag", "constraints", "identity", "mode", "shape", "trace")) fixture { f ->
            f.controller.edit(draft(mode = MealMode.ASSEMBLE)).value()
            f.handler = { call ->
                val original = f.defaultReply(call)
                if (call.operationId != "createPlan") original else {
                    val reply = original.value(); val raw = WireDocument.decode(reply.body!!.copyForCodec()).json().jsonObject
                    when (fault) {
                        "etag" -> PortResult.Value(reply.copy(etag = "\"999\""))
                        "constraints" -> PortResult.Value(reply.copy(body = bytes(JsonObject(raw + ("constraints" to JsonObject(raw.getValue("constraints").jsonObject + ("energy" to JsonPrimitive("happy"))))).toString())))
                        "identity" -> PortResult.Value(reply.copy(body = bytes(JsonObject(raw + ("recipeVersionId" to JsonPrimitive(OTHER))).toString())))
                        "mode" -> PortResult.Value(reply.copy(body = bytes(JsonObject(raw + ("mode" to JsonPrimitive("cook"))).toString())))
                        "shape" -> PortResult.Value(reply.copy(body = bytes("{}")))
                        else -> PortResult.Value(problem(503, "UNAVAILABLE").copy(traceId = "wrong"))
                    }
                }
            }
            f.controller.submit()
            assertEquals(MealFlowPhase.RESOLVING, f.controller.states.value.phase); assertNull(f.controller.states.value.plan)
            assertNotNull(f.record().command)
        }
    }

    @Test fun validPermanentProblemIsNotSuccessAndDoesNotTrapCorrectableDraftAsUnknown() = runTest {
        fixture { f ->
            f.controller.edit(draft()).value()
            f.handler = { call -> if (call.operationId == "createPlan") PortResult.Value(problem(422, "INVALID_REQUEST")) else f.defaultReply(call) }
            assertEquals(MealFlowPhase.ERROR, f.controller.submit().value().phase)
            assertNull(f.record().command); assertNotNull(f.controller.states.value.draft)
        }
    }

    @Test fun retryAfterTakesLargestCanonicalDelayAndDoesNotSpendKeyOrCallBeforeDeadline() = runTest {
        fixture { f ->
            f.controller.edit(draft()).value()
            f.handler = { call -> if (call.operationId == "createPlan") PortResult.Value(problem(429, "RATE_LIMITED", 30).copy(retryAfterSeconds = 10)) else f.defaultReply(call) }
            f.controller.submit().value(); val count = f.calls.size
            f.time += 29_999
            assertEquals(MealFlowIssue.RETRY_LATER, f.controller.retrySubmitted().value().issue); assertEquals(count, f.calls.size)
            f.time++; f.handler = { f.defaultReply(it) }; f.controller.retrySubmitted().value(); assertEquals(1, f.idCalls)
        }
    }

    @Test fun expiredOrChangedPreferenceUncertaintyDoesNotInventFreshRequestIdentity() = runTest {
        for (expire in listOf(false, true)) fixture { f ->
            f.controller.edit(draft()).value()
            f.handler = { call -> if (call.operationId == "createPlan") PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) else f.defaultReply(call) }
            f.controller.submit().value(); val original = f.record().command!!.id
            if (expire) f.time += 60_000 else f.preference = preferences(version = "2", exclusions = listOf(OTHER))
            val state = f.controller.retrySubmitted().value()
            assertEquals(if (expire) MealFlowIssue.REPLAY_EXPIRED else MealFlowIssue.CONTEXT_CHANGED, state.issue)
            assertEquals(original, f.record().command!!.id); assertEquals(1, f.idCalls); assertEquals(1, f.sends().size)
        }
    }

    @Test fun restartRestoresExactPlanBodyDraftAndHistoricalContextWithoutBackend() = runTest {
        fixture { f ->
            f.controller.edit(draft(servings = "1.00")).value(); val before = f.controller.submit().value()
            val exact = before.plan!!.plan.document.encodeUtf8(); val calls = f.calls.size
            f.reopen(); val after = f.controller.restore().value()
            assertContentEquals(exact, after.plan!!.plan.document.encodeUtf8()); assertEquals("1.00", after.draft!!.servings)
            assertTrue(after.plan.historical); assertEquals(calls, f.calls.size)
            f.controller.backToDraft().value(); assertEquals(MealFlowScreen.REQUEST, f.controller.states.value.screen)
        }
    }

    @Test fun expiredNonpendingDraftIsPurgedByScopedCASButUnknownCommandIsNeverAutoErased() = runTest {
        fixture { f ->
            f.controller.edit(draft()).value(); f.time += 120_001
            val state = f.controller.restore().value()
            assertEquals(MealFlowIssue.DRAFT_EXPIRED, state.issue); assertNull(f.record().draft); assertEquals(0, f.store.erases)
        }
        fixture { f ->
            f.controller.edit(draft()).value(); f.handler = { call -> if (call.operationId == "createPlan") PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) else f.defaultReply(call) }
            f.controller.submit().value(); f.time += 120_001; f.controller.restore().value()
            assertNotNull(f.record().command); assertNotNull(f.record().draft)
        }
    }

    @Test fun corruptedUnknownOrForeignOriginRecordFailsClosedWithoutReset() = runTest {
        for (bad in listOf("corrupt", "origin", "schema")) fixture { f ->
            f.controller.edit(draft()).value(); val old = f.store.current!!
            val original = old.payload.copyForCodec().decodeToString()
            val changed = when (bad) { "origin" -> original.replace(ORIGIN, OTHER); "schema" -> original.replace("\"schema\":1", "\"schema\":2"); else -> "not-json-private" }
            f.store.current = old.copy(payload = bytes(changed)); val writes = f.store.writes
            assertIs<PortResult.Failure>(f.controller.restore()); assertEquals(writes, f.store.writes); assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun twoControllersCannotConcurrentlyOwnOneSessionFlowAndClosedOwnerCanReopen() = runTest {
        fixture { f ->
            f.controller.restore().value(); val second = f.newController()
            try { failure(second.restore(), FailureReason.CONFLICT) } finally { second.close() }
            f.reopen(); f.controller.restore().value()
        }
    }

    @Test fun nativeIdFailureCancellationAndReuseNeverFallbackToTimestampOrDispatch() = runTest {
        fixture { f ->
            f.controller.edit(draft()).value(); f.id = "invalid-secret"; assertIs<PortResult.Failure>(f.controller.submit()); assertTrue(f.sends().isEmpty())
        }
        fixture { f ->
            f.controller.edit(draft()).value(); f.idFailure = CancellationException("private")
            assertFailsWith<CancellationException> { f.controller.submit() }; assertTrue(f.sends().isEmpty())
        }
        fixture { f ->
            f.id = UUID1; f.controller.edit(draft()).value(); f.controller.submit().value()
            failure(f.controller.submit(), FailureReason.CONFLICT); assertEquals(1, f.sends().size)
        }
    }

    @Test fun knownBoundRecallBlocksCurrentVersionAndSurvivesReopenWithoutMutatingSnapshot() = runTest {
        fixture { f ->
            f.controller.edit(draft()).value(); val original = f.controller.submit().value().plan!!.plan.document.encodeUtf8()
            f.handler = { call -> if (call.operationId == "nextPlan") PortResult.Value(problem(410, "RECIPE_RECALLED")) else f.defaultReply(call) }
            assertEquals(MealFlowPhase.UNAVAILABLE, f.controller.nextAlternative().value().phase)
            assertNull(f.controller.states.value.plan); assertContentEquals(original, f.record().plans.single().body.encodeUtf8())
            f.reopen(); assertNull(f.controller.restore().value().plan); failure(f.controller.openRecipe(), FailureReason.CONFLICT)
        }
    }

    @Test fun collectionsAndDiagnosticsAreDetachedAndNeverExposeRawPrivateBody() = runTest {
        fixture { f ->
            val ids = mutableListOf(INGREDIENT); val value = draft(ingredients = ids); ids.clear()
            f.controller.edit(value).value(); val state = f.controller.submit().value()
            assertEquals(listOf(INGREDIENT), state.draft!!.ingredientIds)
            runCatching { (state.history as MutableList<*>).clear() }; assertEquals(1, f.controller.states.value.history.size)
            for (item in listOf(value, state, state.plan!!, f.access, f.record(), f.record().plans.single().body)) {
                assertFalse(item.toString().contains(INGREDIENT)); assertFalse(item.toString().contains("Synthetic"))
            }
        }
    }

    @Test fun independentRootsWithIdenticalConstraintsNeverLeakPriorLineageIntoAlternativeExclusions() = runTest {
        fixture { f ->
            f.controller.edit(draft()).value()
            val first = f.controller.submit().value().plan!!.plan
            val second = f.controller.submit().value().plan!!.plan
            f.controller.nextAlternative().value()
            val excluded = WireDocument.decode(f.sends().last().body!!.copyForCodec()).json().jsonObject
                .getValue("excludeRecipeVersionIds").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(listOf((second.recipeVersionId as WireField.Value).value.value), excluded)
            assertFalse((first.recipeVersionId as WireField.Value).value.value in excluded)
        }
    }

    @Test fun deepAlternativeExclusionsUseOnlyRetainedAncestorChain() = runTest {
        fixture { f ->
            f.controller.edit(draft()).value(); val first = f.controller.submit().value().plan!!.plan
            val second = f.controller.nextAlternative().value().plan!!.plan
            f.controller.nextAlternative().value()
            val excluded = WireDocument.decode(f.sends().last().body!!.copyForCodec()).json().jsonObject
                .getValue("excludeRecipeVersionIds").jsonArray.map { it.jsonPrimitive.content }.toSet()
            assertEquals(setOf((first.recipeVersionId as WireField.Value).value.value,
                (second.recipeVersionId as WireField.Value).value.value), excluded)
        }
    }

    @Test fun equalPreferenceVersionCannotChangeItsBodyAndQuotedLeadingZerosAreExactIntegers() = runTest {
        fixture { f ->
            f.handler = { call -> f.defaultReply(call).let { original ->
                PortResult.Value(original.value().copy(etag = if (call.operationId == "listPantry") null else "\"0001\""))
            } }
            f.controller.edit(draft()).value(); assertEquals(MealFlowPhase.READY, f.controller.submit().value().phase)
            f.preference = preferences(exclusions = listOf(OTHER))
            failure(f.controller.submit(), FailureReason.CONFLICT)
            assertEquals(1, f.sends().size); assertEquals(1, f.idCalls)
        }
    }

    @Test fun byteBudgetEvictsOnlyResolvedHistoryAndNeverTrapsAnAcknowledgedNewPlan() = runTest {
        fixture { f ->
            f.handler = { call ->
                val original = f.defaultReply(call)
                if (call.operationId !in setOf("createPlan", "nextPlan")) original else {
                    val reply = original.value(); val raw = WireDocument.decode(reply.body!!.copyForCodec()).json().jsonObject
                    PortResult.Value(reply.copy(body = bytes(JsonObject(raw + ("reasons" to JsonArray(listOf(buildJsonObject {
                        put("code", "ingredientFit"); put("label", "R".repeat(110_000))
                    })))).toString())))
                }
            }
            f.controller.edit(draft()).value()
            repeat(11) { assertEquals(MealFlowPhase.READY, f.controller.submit().value().phase) }
            assertTrue(f.record().plans.size in 1..9); assertNull(f.record().command)
            assertTrue(f.store.current!!.payload.copyForCodec().size <= 1_048_576)
            val selected = f.controller.states.value.plan!!.plan.id
            f.reopen(); assertEquals(selected, f.controller.restore().value().plan!!.plan.id)
        }
    }

    @Test fun issuedIdCapacityIsTemporaryAndAdvertisesEarliestSafeSevenDayExpiry() = runTest {
        fixture { f ->
            f.capacity = 2; f.reopen(); f.controller.edit(draft()).value()
            f.controller.submit().value(); f.controller.submit().value()
            failure(f.controller.submit(), FailureReason.RATE_LIMITED)
            assertEquals(MealFlowIssue.RETRY_LATER, f.controller.states.value.issue)
            assertEquals(f.time + 604_800_000L, f.controller.states.value.retryAtMillis)
            assertEquals(2, f.idCalls); assertEquals(2, f.sends().size)
            f.time += 604_800_000L
            f.controller.edit(draft()).value(); f.controller.submit().value()
            assertEquals(3, f.sends().size); assertEquals(1, f.record().usedIds.size)
        }
    }

    @Test fun expiredUnresolvedIdentityAndBodyAreNeverEvictedByHistoryOrDraftMaintenance() = runTest {
        fixture { f ->
            f.capacity = 1; f.reopen(); f.controller.edit(draft()).value()
            f.handler = { call -> if (call.operationId == "createPlan") PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) else f.defaultReply(call) }
            f.controller.submit().value(); val original = f.record().command!!
            f.time += 604_800_001L; f.controller.edit(draft(total = "9")).value()
            f.reopen(); f.controller.restore().value()
            assertEquals(original.id, f.record().usedIds.single().id)
            assertContentEquals(original.body.encodeUtf8(), f.record().command!!.body.encodeUtf8())
            assertEquals(MealFlowIssue.REPLAY_EXPIRED, f.controller.retrySubmitted().value().issue)
            assertEquals(1, f.sends().size); assertEquals(1, f.idCalls)
        }
    }

    @Test fun persistedClockRollbackCannotExpireDeduplicationOrRetryUnknownDispatch() = runTest {
        fixture { f ->
            f.controller.edit(draft()).value(); f.controller.submit().value(); f.time--
            failure(f.controller.submit(), FailureReason.CONFLICT)
            assertEquals(1, f.sends().size); assertEquals(1, f.idCalls)
        }
    }

    @Test fun canonicalGuestReadAndMutationValidationRequiresActualGuestPrincipalClass() {
        val validator = com.feedme.transport.MobileRequestValidator()
        for (call in listOf(ApiCall("getPreferences"), ApiCall("listPantry", queryParameters = mapOf("limit" to listOf("50"))),
            ApiCall("createPlan", body = PrivateBytes(MealRequestBuilder().build(draft(), preferences()).value().encodeUtf8()), idempotencyKey = SecretText(UUID1)))) {
            assertTrue(validator.accepts(call, PrincipalClass.GUEST))
            assertFalse(validator.accepts(call, PrincipalClass.PUBLIC))
        }
    }

    @Test fun priorUnknownThenCurrentPolicyProblemsNeverEraseOriginalCommandAndRecoveryReusesItsIdentity() = runTest {
        for (status in listOf(401, 403, 409, 410, 422)) fixture { f ->
            f.controller.edit(draft()).value()
            f.handler = { call -> if (call.operationId == "createPlan") PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) else f.defaultReply(call) }
            f.controller.submit().value(); val original = f.record().command!!
            assertEquals(1L, original.dispatches)
            f.reopen()
            f.handler = { call -> if (call.operationId == "createPlan") PortResult.Value(problem(status, "INPUT_INVALID")) else f.defaultReply(call) }
            f.controller.retrySubmitted().value()
            assertEquals(original.id, f.record().command!!.id); assertEquals(2L, f.record().command!!.dispatches)
            assertContentEquals(original.body.encodeUtf8(), f.record().command!!.body.encodeUtf8())
            f.handler = { f.defaultReply(it) }
            assertEquals(MealFlowPhase.READY, f.controller.retrySubmitted().value().phase)
            assertEquals(1, f.idCalls); assertTrue(f.sends().all { it.idempotencyKey!!.use { key -> key == original.id } })
        }
    }

    @Test fun firstAuthorizationFailureAlsoRetainsCommandRatherThanPretendingItNeverExisted() = runTest {
        for (status in listOf(401, 403, 409, 410)) fixture { f ->
            f.controller.edit(draft()).value()
            f.handler = { call -> if (call.operationId == "createPlan") PortResult.Value(problem(status, "UNAVAILABLE")) else f.defaultReply(call) }
            f.controller.submit().value(); assertNotNull(f.record().command)
            assertEquals(1L, f.record().command!!.dispatches)
        }
    }

    @Test fun cancellationAfterDurableAdmissionBeforeTransportMakesLaterInputProblemUncertain() = runTest {
        fixture { f ->
            f.controller.edit(draft()).value()
            f.store.afterRead = {
                if (f.record().command?.dispatches == 1L) {
                    f.store.afterRead = null
                    throw CancellationException("admission handoff")
                }
            }
            assertFailsWith<CancellationException> { f.controller.submit() }
            assertTrue(f.sends().isEmpty()); assertEquals(1L, f.record().command!!.dispatches)
            val original = f.record().command!!.id; f.reopen()
            f.handler = { call -> if (call.operationId == "createPlan") PortResult.Value(problem(422, "INPUT_INVALID")) else f.defaultReply(call) }
            f.controller.retrySubmitted().value(); assertEquals(original, f.record().command!!.id)
            assertEquals(2L, f.record().command!!.dispatches); assertEquals(1, f.idCalls)
        }
    }

    @Test fun fullServerReplyBoundRemainsPersistableAndReadableAfterRecreation() = runTest {
        fixture { f ->
            f.handler = { call ->
                val original = f.defaultReply(call)
                if (call.operationId != "createPlan") original else {
                    val reply = original.value(); val raw = WireDocument.decode(reply.body!!.copyForCodec()).json().jsonObject
                    PortResult.Value(reply.copy(body = bytes(JsonObject(raw + ("reasons" to JsonArray(listOf(buildJsonObject {
                        put("code", "ingredientFit"); put("label", "\\".repeat(125_000))
                    })))).toString())))
                }
            }
            f.controller.edit(draft()).value(); val ready = f.controller.submit().value()
            assertEquals(MealFlowPhase.READY, ready.phase)
            val exact = ready.plan!!.plan.document.encodeUtf8(); assertTrue(exact.size in 131_073..262_144)
            f.reopen(); assertContentEquals(exact, f.controller.restore().value().plan!!.plan.document.encodeUtf8())
        }
    }

    @Test fun insufficientReplyReservationIsRefusedBeforeDispatchRatherThanTrappingServerSuccess() = runTest {
        fixture { f ->
            f.capacity = 4096; f.reopen(); f.controller.edit(draft()).value()
            f.preference = doc(JsonObject(preferences().json().jsonObject + ("equipmentIds" to JsonArray(listOf(JsonPrimitive("\\".repeat(62_000)))))))
            assertTrue(f.preference.encodeUtf8().size <= 131_072, "Context fixture must fit the real input cap")
            val record = f.record().copy(preferences = f.preference,
                usedIds = List(4095) { FlowIssuedId(uuidNumber(10_000 + it), f.time) })
            val codec = MealFlowCodec(MealRequestBuilder(), ORIGIN)
            val encoded = codec.encode(record)
            val request = MealRequestBuilder().build(record.draft!!, f.preference).value()
            val reservedBytes = encoded.copyForCodec().size.toLong() + 2L * request.encodeUtf8().size + 2L * 262_144 + 16_384
            assertTrue(reservedBytes > 1_048_576, "Worst-case reply reservation must exceed the actual storage cap")
            assertFalse(codec.replyFits(record, request))
            f.store.current = f.store.current!!.copy(payload = encoded)
            failure(f.controller.submit(), FailureReason.INVALID_DATA)
            assertTrue(f.sends().isEmpty()); assertNull(f.record().command)
        }
    }

    @Test fun recipeBackReturnsToSameRecommendationWithoutRewritingHistoryDraftOrSelection() = runTest {
        fixture { f ->
            f.controller.edit(draft()).value(); f.controller.submit().value(); f.controller.nextAlternative().value()
            val selected = f.controller.states.value.plan!!.plan.id
            f.controller.openRecipe().value(); val writes = f.store.writes; val record = f.store.onlyText()
            val returned = f.controller.returnToRecommendations().value()
            assertEquals(MealFlowScreen.RECOMMENDATIONS, returned.screen); assertEquals(selected, returned.plan!!.plan.id)
            assertEquals(2, returned.history.size); assertEquals(writes, f.store.writes); assertEquals(record, f.store.onlyText())
            assertNull(f.record().command)
        }
    }

    @Test fun backWhileTransportSuspendedIsImmediateAndFencesLateSuccessWithoutLosingOriginalCommand() = runTest {
        fixture { f ->
            f.controller.edit(draft()).value()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.handler = { call -> if (call.operationId == "createPlan") {
                entered.complete(Unit); withContext(NonCancellable) { release.await() }; f.defaultReply(call)
            } else f.defaultReply(call) }
            val sending = async { f.controller.submit() }; entered.await()
            val pending = f.record().command!!; val writes = f.store.writes
            val back = async { f.controller.backToDraft() }; runCurrent()
            assertTrue(back.isCompleted); val state = back.await().value()
            assertEquals(MealFlowScreen.REQUEST, state.screen); assertEquals(MealFlowPhase.RESOLVING, state.phase)
            assertEquals(writes, f.store.writes); assertEquals(pending.id, f.record().command!!.id)
            release.complete(Unit); failure(sending.await(), FailureReason.STALE_SESSION)
            assertNull(f.controller.states.value.plan); assertEquals(pending.id, f.record().command!!.id)
            assertContentEquals(pending.body.encodeUtf8(), f.record().command!!.body.encodeUtf8())
        }
    }

    @Test fun returnToRecommendationsPreservesUncertainAlternativeAndTimeoutIssue() = runTest {
        fixture { f ->
            f.controller.edit(draft()).value(); f.controller.submit().value(); f.controller.openRecipe().value()
            val selected = f.controller.states.value.plan!!.plan.id
            f.handler = { call -> if (call.operationId == "nextPlan") PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) else f.defaultReply(call) }
            f.controller.nextAlternative()
            val prior = f.controller.states.value; val bytes = f.store.onlyText(); val writes = f.store.writes
            val state = f.controller.returnToRecommendations().value()
            assertEquals(selected, state.plan!!.plan.id); assertEquals(MealFlowScreen.RECOMMENDATIONS, state.screen)
            assertEquals(prior.issue, state.issue); assertEquals(prior.failureReason, state.failureReason)
            assertEquals(MealFlowPhase.RESOLVING, state.phase); assertNotNull(f.record().command)
            assertEquals(bytes, f.store.onlyText()); assertEquals(writes, f.store.writes)
        }
    }

    @Test fun backDoesNotPromoteUnacknowledgedDraftAndOldCancellationCannotOverwriteNewNavigation() = runTest {
        fixture { f ->
            f.controller.edit(draft(total = "10")).value()
            f.store.fail = StoreFailure.AFTER
            failure(f.controller.edit(draft(total = "20")), FailureReason.OUTCOME_UNKNOWN)
            assertEquals("10", f.controller.backToDraft().value().draft!!.maxTotalMinutes)
            // A later validated read may restore the stored edit; Back itself never does so.
            f.controller.restore().value()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.handler = { call -> if (call.operationId == "createPlan") {
                entered.complete(Unit); withContext(NonCancellable) { release.await() }; f.defaultReply(call)
            } else f.defaultReply(call) }
            val sending = async { f.controller.submit() }; entered.await()
            val back = f.controller.backToDraft().value(); sending.cancel(); release.complete(Unit)
            assertFailsWith<CancellationException> { sending.await() }
            assertSame(back, f.controller.states.value); assertNotNull(f.record().command)
        }
    }

    @Test fun bothBackActionsRejectRetiredLeaseAndCannotClearOrExposeNewerIdentity() = runTest {
        fixture { f ->
            f.controller.edit(draft()).value(); f.controller.submit().value(); f.controller.openRecipe().value()
            val newer = f.boundary.activate(f.scope)
            failure(f.controller.backToDraft(), FailureReason.STALE_SESSION)
            failure(f.controller.returnToRecommendations(), FailureReason.STALE_SESSION)
            assertNull(f.controller.states.value.plan); assertNull(f.controller.states.value.draft)
            assertTrue(f.boundary.isCurrent(newer))
        }
    }

    @Test fun malformedNumericAndIngredientDraftsAreInputFailuresBeforeAnyPortEffects() = runTest {
        fixture { f ->
            for (bad in listOf(draft(servings = "0."), draft(servings = "NaN"), draft(ingredients = listOf("not-an-ingredient-id")))) {
                failure(f.controller.edit(bad), FailureReason.INVALID_DATA)
                assertNull(f.store.current); assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty()); assertEquals(0, f.idCalls)
            }
        }
    }

    private suspend fun TestScope.fixture(guest: Boolean = false, onlineAllowed: Boolean = true, action: suspend (Fixture) -> Unit) {
        val f = Fixture(this, guest, onlineAllowed)
        try { action(f) } finally { f.controller.close().value() }
    }

    private class Fixture(scopeOfTest: TestScope, guest: Boolean, allowed: Boolean) {
        val dispatcher = StandardTestDispatcher(scopeOfTest.testScheduler)
        val scope = StorageScope("fixture", if (guest) ActorKind.GUEST else ActorKind.ACCOUNT, "private-actor")
        val boundary = SessionBoundary(); val lease = boundary.activate(scope); val store = Store(scope)
        var time = 1_000_000L; var online = true; var idCalls = 0; var id: String? = null; var idFailure: Exception? = null
        var capacity = 128
        var preference = preferences(); var status = "ready"
        val calls = mutableListOf<ApiCall>(); var beforeSend: suspend (ApiCall) -> Unit = {}
        var handler: suspend (ApiCall) -> PortResult<ApiReply> = { defaultReply(it) }
        val access = AuthenticatedMealPlanningAccess(lease, ORIGIN, store, object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                assertSame(this@Fixture.lease, lease); calls += call
                if (call.operationId in setOf("createPlan", "nextPlan")) beforeSend(call)
                return handler(call)
            }
        }, allowed)
        var controller = newController()
        fun newController() = MealRequestController(access, boundary, dispatcher, EpochClock { time },
            ConnectivityPort { if (online) Connectivity.ONLINE else Connectivity.OFFLINE }, MealOperationIds {
                idCalls++; idFailure?.let { throw it }; id ?: uuidNumber(100 + idCalls)
            }, MealFlowPolicy(120_000, 60_000, 10, capacity))
        suspend fun reopen() { controller.close().value(); controller = newController() }
        fun record() = MealFlowCodec(MealRequestBuilder(VALIDATOR), ORIGIN).decode(store.current!!.payload)
        fun sends() = calls.filter { it.operationId in setOf("createPlan", "nextPlan") }
        fun defaultReply(call: ApiCall): PortResult<ApiReply> = PortResult.Value(when (call.operationId) {
            "getPreferences" -> reply(preference, etag = "\"${preference.json().jsonObject.getValue("version").jsonPrimitive.content}\"")
            "listPantry" -> reply(doc(buildJsonObject { put("items", JsonArray(listOf(buildJsonObject {
                put("id", OTHER); put("version", 1); put("createdAt", TIME); put("updatedAt", TIME); put("ingredientId", OTHER)
                put("presence", "usuallyHave"); put("confirmationStatus", "usual"); put("confirmedAt", JsonNull); put("staple", true)
            }))); put("nextCursor", "unfetched-page"); put("serverTime", TIME) }))
            else -> {
                val raw = WireDocument.decode(call.body!!.copyForCodec()).json().jsonObject
                val index = calls.count { it.operationId in setOf("createPlan", "nextPlan") } - 1
                val body = plan(raw.getValue("constraints"), status, uuidNumber(200 + index), uuidNumber(300 + index), call.pathParameters["planId"])
                reply(body, if (call.operationId == "createPlan") 201 else 200, "\"1\"")
            }
        })
    }
    private enum class StoreFailure { BEFORE, AFTER, BAD_REVISION, BAD_READBACK }
    private class Store(private val owner: StorageScope) : PrivateStateStore {
        var current: PrivateRecord? = null; var writes = 0; var erases = 0; var fail: StoreFailure? = null
        var afterRead: (suspend () -> Unit)? = null; private var corruptRead = false
        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> {
            if (scope != owner) return PortResult.Failure(FailureReason.STALE_SESSION)
            if (key.collection == "mealflow.kitchen-inputs.v1") return PortResult.Value(null)
            val result = current?.copy(payload = PrivateBytes(current!!.payload.copyForCodec()))
            afterRead?.invoke()
            return PortResult.Value(if (corruptRead) { corruptRead = false; result?.copy(payload = bytes("{}")) } else result)
        }
        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> {
            if (scope != owner) return PortResult.Failure(FailureReason.STALE_SESSION)
            val mutation = assertIs<StoreMutation.Put>(mutations.single())
            if (mutation.expectedRevision != current?.revision) return PortResult.Failure(FailureReason.CONFLICT)
            val fault = fail; fail = null
            if (fault == StoreFailure.BEFORE) return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
            val revision = (current?.revision ?: 0) + 1
            current = PrivateRecord(revision, mutation.schemaVersion, PrivateBytes(mutation.payload.copyForCodec())); writes++
            if (fault == StoreFailure.AFTER) return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
            if (fault == StoreFailure.BAD_READBACK) corruptRead = true
            return PortResult.Value(mapOf(mutation.key to if (fault == StoreFailure.BAD_REVISION) 0L else revision))
        }
        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> { erases++; return PortResult.Failure(FailureReason.NOT_CONFIGURED) }
        fun onlyText() = current!!.payload.copyForCodec().decodeToString()
    }
    companion object {
        const val INGREDIENT = "00000000-0000-4000-8000-000000000011"
        const val OTHER = "00000000-0000-4000-8000-000000000012"
        const val ORIGIN = "00000000-0000-4000-8000-000000000099"
        const val UUID1 = "00000000-0000-4000-8000-000000000101"
        const val TIME = "2026-09-13T10:00:00Z"
        private val VALIDATOR = CanonicalBodyValidator.bundled()
        private fun draft(mode: MealMode = MealMode.AUTO, servings: String = "1", ingredients: List<String> = listOf(INGREDIENT),
            exclusions: List<String> = emptyList(), equipment: List<String> = listOf("bowl"), total: String? = "10",
            base: ManualBaseMeal? = null, pending: Boolean = false) = ManualMealDraft(mode, MealEnergy.ASSEMBLE, servings,
                ingredients, equipment, exclusions, emptyList(), total, baseMeal = base, preferencesPendingSync = pending)
        private fun preferences(version: String = "1", exclusions: List<String> = emptyList()) = doc(buildJsonObject {
            put("id", OTHER); put("version", Json.parseToJsonElement(version)); put("createdAt", TIME); put("updatedAt", TIME)
            put("hardExcludedIngredientIds", JsonArray(exclusions.map(::JsonPrimitive))); put("dietaryPatterns", JsonArray(emptyList()))
            put("dislikedIngredientIds", JsonArray(emptyList())); put("equipmentIds", JsonArray(listOf(JsonPrimitive("bowl"))))
        })
        private fun plan(constraints: JsonElement, status: String, id: String, version: String, parent: String?) = doc(buildJsonObject {
            put("id", id); put("version", 1); put("createdAt", TIME); put("updatedAt", TIME); put("status", status)
            if (status in setOf("ready", "recalled")) put("mode", "assemble")
            parent?.let { put("parentPlanId", it) }; put("constraints", constraints)
            put("missingIngredients", JsonArray(emptyList())); put("changes", JsonArray(emptyList())); put("reasons", JsonArray(emptyList()))
            put("catalogRevision", "fixture-only"); put("nextAlternativeCursor", if (status == "ready") JsonPrimitive("cursor-1") else JsonNull)
            if (status == "ready") {
                put("recipeVersionId", version); put("recipeSnapshot", buildJsonObject {
                    put("id", version); put("recipeId", OTHER); put("version", 1); put("createdAt", TIME); put("updatedAt", TIME)
                    put("title", "Synthetic reviewed fixture"); put("reviewStatus", "published"); put("reviewedAt", TIME)
                    put("servings", constraints.jsonObject.getValue("servings")); put("activeMinutes", 1); put("totalMinutes", 1)
                    put("utensilCount", 1); put("equipmentIds", JsonArray(listOf(JsonPrimitive("bowl"))))
                    put("modes", JsonArray(listOf(JsonPrimitive("assemble")))); put("tasteTags", JsonArray(emptyList()))
                    put("ingredients", JsonArray(listOf(buildJsonObject { put("ingredientId", INGREDIENT); put("quantity", 1); put("unit", "g"); put("optional", false) })))
                    put("steps", JsonArray(listOf(buildJsonObject { put("stepId", "first"); put("position", 1); put("instruction", "Fixture only")
                        put("ingredientIds", JsonArray(listOf(JsonPrimitive(INGREDIENT)))); put("requiredEquipmentIds", JsonArray(emptyList())); put("mandatorySafetyStep", false) })))
                })
            }
        })
        private fun reply(body: WireDocument, status: Int = 200, etag: String? = null) = ApiReply(status, PrivateBytes(body.encodeUtf8()), etag, contentType = "application/json")
        private fun problem(status: Int, code: String, delay: Long? = null): ApiReply = ApiReply(status, bytes(buildJsonObject {
            put("type", "about:blank"); put("title", "Unavailable"); put("status", status); put("code", code); put("traceId", "trace")
            delay?.let { put("retryAfterSeconds", it) }
        }.toString()), traceId = "trace", contentType = "application/problem+json")
        private fun bytes(value: String) = PrivateBytes(value.encodeToByteArray())
        private fun uuidNumber(number: Int) = "00000000-0000-4000-8000-${number.toString().padStart(12, '0')}"
        private fun <T> PortResult<T>.value(): T = assertIs<PortResult.Value<T>>(this).value
        private fun failure(result: PortResult<*>, reason: FailureReason) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
    }
}
