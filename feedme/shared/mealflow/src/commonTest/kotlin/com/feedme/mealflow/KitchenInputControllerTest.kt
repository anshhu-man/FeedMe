package com.feedme.mealflow

import com.feedme.contracts.*
import com.feedme.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Actual DurableCommandQueue composition with detached atomic-store/transport protocol fakes.
 * These tests do not claim native encryption, configured HTTP routes, or provider authorization. */
@OptIn(ExperimentalCoroutinesApi::class)
class KitchenInputControllerTest {
    @Test fun restoreIsReadOnlyAndCollectionNeverLoadsOrSends() = runTest { fixture { f ->
        assertEquals(KitchenInputPhase.EDITING, f.controller.states.value.phase)
        assertTrue(f.controller.restore().value().pantryItems.isEmpty())
        assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty()); assertEquals(0, f.idCalls)
    } }

    @Test fun canonicalAccountAndGuestLoadsUseExactQueriesWithoutMutationAuthority() = runTest {
        for (guest in listOf(false, true)) fixture(guest = guest) { f ->
            val preferences = f.controller.loadPreferences().value(); val pantry = f.controller.loadPantry().value()
            assertEquals(pref().encodeUtf8().toList(), preferences.preferences!!.encodeUtf8().toList())
            assertEquals(listOf("getPreferences", "listPantry"), f.calls.map { it.operationId })
            assertTrue(f.calls[0].queryParameters.isEmpty()); assertEquals(mapOf("limit" to listOf("2")), f.calls[1].queryParameters)
            f.calls.forEach { assertNull(it.idempotencyKey); assertNull(it.ifMatch); assertNull(it.body) }
            assertEquals("uncertain", pantry.pantryItems.single().json().jsonObject.getValue("presence").jsonPrimitive.content)
        }
    }

    @Test fun editingAndSavingPreferencesRequireSeparateExplicitSynchronization() = runTest { fixture { f ->
        f.controller.loadPreferences().value(); f.calls.clear()
        val patch = patch(listOf(ONE)); val edited = f.controller.editPreferences(patch).value()
        assertTrue(edited.preferencesPending); assertTrue(edited.draftAcknowledged); assertEquals(listOf(ONE), edited.stricterExclusionIds)
        val saved = f.controller.savePreferences().value(); assertTrue(f.calls.isEmpty())
        val command = saved.pending.single(); assertEquals("updatePreferences", command.operationId)
        assertEquals("\"1\"", command.ifMatch); assertEquals(0, command.attempts); assertTrue(command.canDiscardUnsent)
        assertContentEquals(patch.encodeUtf8(), command.body!!.encodeUtf8())
        assertNull(saved.preferenceDraft); assertTrue(saved.preferencesPending)
        val synchronized = f.controller.synchronize(command.commandId).value()
        assertTrue(synchronized.pending.isEmpty()); assertFalse(synchronized.preferencesPending)
        val sent = f.mutations().single(); assertEquals(command.commandId, sent.idempotencyKey!!.use { it })
        assertEquals(command.ifMatch, sent.ifMatch); assertContentEquals(patch.encodeUtf8(), sent.body!!.copyForCodec())
        assertEquals("2", pickerVersion(synchronized.preferences!!)); assertEquals(0, f.store.erases)
    } }

    @Test fun preferenceSaveUsesActualEtagIncludingEquivalentLeadingZeroSpelling() = runTest { fixture { f ->
        f.tag = "\"0001\""; f.controller.loadPreferences().value(); f.controller.editPreferences(patch()).value()
        val pending = f.controller.savePreferences().value().pending.single()
        assertEquals("\"0001\"", pending.ifMatch)
        f.controller.synchronize(pending.commandId).value(); assertEquals("\"0001\"", f.mutations().single().ifMatch)
    } }

    @Test fun missingPreferenceEtagRequiresLoadInsteadOfInventingWriteVersion() = runTest { fixture { f ->
        f.tag = null; f.controller.loadPreferences().value(); f.controller.editPreferences(patch()).value()
        val result = f.controller.savePreferences().value()
        assertEquals(KitchenInputIssue.LOAD_REQUIRED, result.issue); assertTrue(result.pending.isEmpty()); assertEquals(0, f.idCalls)
    } }

    @Test fun pantryOverwriteUsesExpectedVersionAndDeleteUsesIfMatchOnly() = runTest { fixture { f ->
        f.controller.loadPantry().value(); f.controller.editPantry(write(ONE, "available")).value()
        val upsert = f.controller.savePantry(ONE).value().pending.single()
        assertEquals("1", upsert.body!!.json().jsonObject.getValue("expectedVersion").jsonPrimitive.content); assertNull(upsert.ifMatch)
        f.controller.synchronize(upsert.commandId).value()
        val removed = f.controller.removePantry(ONE).value().pending.single()
        assertNull(removed.body); assertEquals("\"2\"", removed.ifMatch)
        f.controller.synchronize(removed.commandId).value()
        assertTrue(f.controller.states.value.pantryItems.isEmpty())
        val call = f.mutations().last(); assertEquals(mapOf("ingredientId" to ONE), call.pathParameters); assertNull(call.body)
    } }

    @Test fun newPantryEntryHasNoInventedExpectedVersionAndDoesNotSelectMealIngredients() = runTest { fixture { f ->
        f.controller.editPantry(write(TWO, "usuallyHave")).value()
        val pending = f.controller.savePantry(TWO).value().pending.single()
        assertFalse("expectedVersion" in pending.body!!.json().jsonObject)
        f.controller.synchronize(pending.commandId).value()
        assertEquals("usuallyHave", f.controller.states.value.pantryItems.single().json().jsonObject.getValue("presence").jsonPrimitive.content)
        assertFalse(f.store.records.keys.any { it.collection == "mealflow.v1" })
        assertTrue(f.calls.none { it.operationId in setOf("createPlan", "nextPlan") })
    } }

    @Test fun invalidFieldsAndConsentAndDeferredQuantityAreRejectedBeforeStoreOrNetwork() = runTest { fixture { f ->
        for (bad in listOf("{}", "{\"consentVersion\":\"guessed\"}", "{\"defaultEnergy\":\"invented\"}",
            "{\"hardExcludedIngredientIds\":[\"not-id\"]}", "{\"equipmentIds\":[\"pan\",\"pan\"]}"))
            fail(f.controller.editPreferences(WireDocument.parse(bad)), FailureReason.INVALID_DATA)
        for (field in listOf("quantity", "unit", "expectedVersion", "invented")) {
            val bad = doc(JsonObject(write(ONE).json().jsonObject + (field to if (field == "unit") JsonPrimitive("g") else JsonPrimitive(1))))
            fail(f.controller.editPantry(bad), FailureReason.INVALID_DATA)
        }
        assertEquals(0, f.store.reads); assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty())
    } }

    @Test fun offlineEditsAndQueuedIntentsSurviveReopenWithoutAnyAutomaticSend() = runTest { fixture { f ->
        f.controller.loadPreferences().value(); f.online = false; f.calls.clear()
        f.controller.editPreferences(patch(listOf(ONE))).value()
        val original = f.controller.savePreferences().value().pending.single()
        assertEquals(KitchenInputPhase.OFFLINE, f.controller.states.value.phase)
        f.reopen(); val state = f.controller.restore().value()
        assertEquals(original.commandId, state.pending.single().commandId); assertTrue(state.historical); assertTrue(state.preferencesPending)
        assertTrue(f.calls.isEmpty()); f.online = true
        f.controller.synchronize(original.commandId).value(); assertEquals(1, f.idCalls); assertEquals(1, f.mutations().size)
    } }

    @Test fun localPreferenceDraftActuallyBlocksMealSubmitBeforeIdsOrTransport() = runTest { fixture { f ->
        f.meals.edit(mealDraft()).value(); f.controller.editPreferences(patch(listOf(ONE))).value()
        val result = f.meals.submit().value()
        assertEquals(MealFlowIssue.PREFERENCES_PENDING, result.issue); assertEquals(0, f.idCalls); assertTrue(f.calls.isEmpty())
        f.controller.discardPreferenceDraft().value()
        assertFalse(f.controller.states.value.preferencesPending)
        f.meals.submit().value(); assertEquals(1, f.mutations().count { it.operationId == "createPlan" })
    } }

    @Test fun acknowledgedDraftPersistsSafetyGuardAcrossBothControllersReopen() = runTest { fixture { f ->
        f.meals.edit(mealDraft()).value(); f.controller.editPreferences(patch(listOf(ONE))).value()
        f.controller.close().value(); f.meals.close().value()
        f.controller = f.newController(); f.meals = f.newMeals()
        f.meals.restore().value(); assertEquals(MealFlowIssue.PREFERENCES_PENDING, f.meals.submit().value().issue)
        assertTrue(f.calls.isEmpty())
    } }

    @Test fun failedPreferenceWriteKeepsProcessFenceThroughCloseAndReadOnlyRestore() = runTest { fixture { f ->
        f.meals.edit(mealDraft()).value(); f.store.fault = Fault.BEFORE
        fail(f.controller.editPreferences(patch(listOf(ONE))), FailureReason.OUTCOME_UNKNOWN)
        assertFalse(f.controller.states.value.draftAcknowledged); assertTrue(f.controller.states.value.preferencesPending)
        f.reopen(); val restored = f.controller.restore().value()
        assertTrue(restored.preferencesPending); assertFalse(restored.draftAcknowledged)
        assertContentEquals(patch(listOf(ONE)).encodeUtf8(), restored.preferenceDraft!!.encodeUtf8())
        fail(f.controller.savePreferences(), FailureReason.CONFLICT)
        assertEquals(MealFlowIssue.PREFERENCES_PENDING, f.meals.submit().value().issue); assertTrue(f.calls.isEmpty())
        f.controller.discardPreferenceDraft().value(); assertFalse(f.controller.states.value.preferencesPending)
    } }

    @Test fun fenceOwnsInvalidationAfterEditorHasClosedWithoutRetainingRetiredIdentity() = runTest { fixture { f ->
        f.controller.editPreferences(patch()).value(); f.controller.close().value()
        assertTrue(KitchenInputPreferenceGuard.blocked(f.lease, ORIGIN))
        val newer = f.boundary.activate(f.scope)
        assertFalse(KitchenInputPreferenceGuard.blocked(f.lease, ORIGIN)); assertTrue(f.boundary.isCurrent(newer))
    } }

    @Test fun exclusionUnionKeepsOriginalAndStricterUnsentOrNewerLocalValues() = runTest { fixture { f ->
        f.preference = pref(exclusions = listOf(ONE)); f.controller.loadPreferences().value()
        f.controller.editPreferences(patch(listOf(TWO))).value(); val pending = f.controller.savePreferences().value().pending.single()
        f.controller.editPreferences(patch(listOf(THREE))).value()
        assertEquals(setOf(ONE, TWO, THREE), f.controller.states.value.stricterExclusionIds.toSet())
        f.controller.synchronize(pending.commandId).value()
        assertEquals(setOf(TWO, THREE), f.controller.states.value.stricterExclusionIds.toSet())
        assertTrue(f.controller.states.value.preferencesPending); assertContentEquals(patch(listOf(THREE)).encodeUtf8(), f.controller.states.value.preferenceDraft!!.encodeUtf8())
    } }

    @Test fun queuedPreferenceConflictKeepsOriginalBodyKeyAndBaseAlongsideFreshServerCandidate() = runTest {
        for (status in listOf(412, 422)) fixture { f ->
            f.controller.loadPreferences().value(); f.controller.editPreferences(patch(listOf(ONE))).value()
            val original = f.controller.savePreferences().value().pending.single()
            f.handler = { call -> if (call.operationId == "getPreferences") ok(pref("3", listOf(TWO)), "\"3\"") else problem(status) }
            val state = f.controller.synchronize(original.commandId).value()
            assertEquals(KitchenInputPhase.CONFLICT, state.phase); assertTrue(state.preferencesPending)
            val held = state.pending.single(); assertEquals(1, held.attempts); assertFalse(held.canDiscardUnsent); assertFalse(held.canSynchronize)
            fail(f.controller.discardUnsent(held.commandId), FailureReason.CONFLICT)
            f.controller.loadPreferences().value(); val refreshed = f.controller.states.value
            assertEquals("3", pickerVersion(refreshed.preferences!!)); assertEquals("1", pickerVersion(refreshed.pending.single().base!!))
            assertEquals(original.commandId, refreshed.pending.single().commandId)
            assertContentEquals(original.body!!.encodeUtf8(), refreshed.pending.single().body!!.encodeUtf8())
            val sends = f.mutations().size; f.controller.synchronize(original.commandId).value(); assertEquals(sends, f.mutations().size)
        }
    }

    @Test fun outcomeUnknownKeepsExactRequestAndRetryNeverCreatesNewIdentity() = runTest { fixture { f ->
        val pending = f.preparePreference()
        f.handler = { PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
        val first = f.controller.synchronize(pending.commandId).value().pending.single()
        assertEquals(KitchenInputCommandPhase.RETRY_WAIT, first.phase); assertEquals(1, first.attempts)
        f.time = first.retryAtMillis; f.handler = { f.defaultReply(it) }
        f.controller.synchronize(pending.commandId).value()
        assertEquals(1, f.idCalls); assertEquals(2, f.mutations().size)
        assertEquals(f.mutations()[0].idempotencyKey!!.use { it }, f.mutations()[1].idempotencyKey!!.use { it })
        assertContentEquals(f.mutations()[0].body!!.copyForCodec(), f.mutations()[1].body!!.copyForCodec())
        assertEquals(f.mutations()[0].ifMatch, f.mutations()[1].ifMatch)
    } }

    @Test fun priorUnknownFollowedByAuthVersionOrInputProblemsNeverDiscardsEvidence() = runTest {
        for (status in listOf(401, 403, 409, 412, 422)) fixture { f ->
            val pending = f.preparePreference(); f.handler = { PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
            f.time = f.controller.synchronize(pending.commandId).value().pending.single().retryAtMillis
            f.handler = { problem(status) }; val retained = f.controller.synchronize(pending.commandId).value().pending.single()
            assertEquals(pending.commandId, retained.commandId); assertEquals(2, retained.attempts); assertTrue(f.controller.states.value.preferencesPending)
            assertFalse(retained.canDiscardUnsent); assertContentEquals(pending.body!!.encodeUtf8(), retained.body!!.encodeUtf8())
        }
    }

    @Test fun restoredAuthenticationExplicitlyResumesOnlyOriginalPendingRequest() = runTest { fixture { f ->
        val pending = f.preparePreference(); f.handler = { problem(401) }
        val denied = f.controller.synchronize(pending.commandId).value().pending.single(); assertTrue(denied.canSynchronize)
        f.handler = { f.defaultReply(it) }; f.controller.synchronize(pending.commandId).value()
        assertEquals(1, f.idCalls); assertEquals(2, f.mutations().size); assertTrue(f.controller.states.value.pending.isEmpty())
    } }

    @Test fun neverAttemptedDiscardRestoresLocalDraftAndDoesNotRemoveSafetyGuard() = runTest { fixture { f ->
        val pending = f.preparePreference(); val discarded = f.controller.discardUnsent(pending.commandId).value()
        assertTrue(discarded.pending.isEmpty()); assertTrue(discarded.preferencesPending); assertNotNull(discarded.preferenceDraft)
        assertTrue(f.mutations().isEmpty()); f.controller.discardPreferenceDraft().value(); assertFalse(f.controller.states.value.preferencesPending)
        fail(f.controller.synchronize(pending.commandId), FailureReason.CONFLICT)
    } }

    @Test fun duplicateNativeIdCannotOverwriteAppliedOrDiscardedQueueTombstone() = runTest { fixture { f ->
        f.fixedId = id(100); val first = f.preparePreference(); f.controller.synchronize(first.commandId).value()
        f.controller.editPreferences(patch(listOf(TWO))).value()
        fail(f.controller.savePreferences(), FailureReason.CONFLICT)
        assertTrue(f.controller.states.value.preferencesPending); assertEquals(1, f.mutations().size)
    } }

    @Test fun enqueueBeforeAfterUnknownAndMalformedAcknowledgementsNeverSend() = runTest {
        for (fault in Fault.entries) fixture { f ->
            f.controller.loadPreferences().value(); f.controller.editPreferences(patch()).value(); f.store.fault = fault
            assertIs<PortResult.Failure>(f.controller.savePreferences()); assertTrue(f.mutations().isEmpty())
            assertTrue(KitchenInputPreferenceGuard.pending(f.access))
            f.reopen(); val state = f.controller.restore().value()
            if (fault != Fault.BEFORE) assertEquals(1, state.pending.size)
        }
    }

    @Test fun receiptApplyFailurePreservesSuccessfulReceiptAndRetryDoesNotSendAgain() = runTest { fixture { f ->
        val pending = f.preparePreference()
        f.store.whenCommit = { changes -> changes.any { it is StoreMutation.Delete && it.key.collection.endsWith("receipt") } }
        f.store.fault = Fault.BEFORE
        fail(f.controller.synchronize(pending.commandId), FailureReason.OUTCOME_UNKNOWN)
        f.store.whenCommit = { true }; assertEquals(1, f.mutations().size)
        f.controller.synchronize(pending.commandId).value(); assertEquals(1, f.mutations().size); assertFalse(f.controller.states.value.preferencesPending)
    } }

    @Test fun lostAtomicApplyAcknowledgementRetainsOriginalIdAndNeedsFreshExactDomainAck() = runTest { fixture { f ->
        val pending = f.preparePreference()
        f.store.whenCommit = { changes -> changes.any { it is StoreMutation.Delete && it.key.collection.endsWith("receipt") } }
        f.store.fault = Fault.AFTER
        fail(f.controller.synchronize(pending.commandId), FailureReason.OUTCOME_UNKNOWN)
        assertTrue(f.record().commands.isEmpty()); assertTrue(KitchenInputPreferenceGuard.pending(f.access))
        f.reopen(); val state = f.controller.restore().value()
        val retained = state.pending.single(); assertEquals(pending.commandId, retained.commandId)
        assertEquals(KitchenInputCommandPhase.APPLIED, retained.phase); assertTrue(retained.canSynchronize); assertFalse(retained.canDiscardUnsent)
        assertTrue(state.preferencesPending); val before = f.store.records.getValue(KEY).revision
        f.store.whenCommit = { true }; f.store.fault = Fault.BEFORE
        fail(f.controller.synchronize(pending.commandId), FailureReason.OUTCOME_UNKNOWN)
        assertTrue(f.controller.restore().value().preferencesPending)
        f.controller.synchronize(pending.commandId).value()
        assertTrue(f.store.records.getValue(KEY).revision > before); assertEquals(1, f.mutations().size); assertEquals(1, f.idCalls)
        assertFalse(f.controller.states.value.preferencesPending); assertTrue(f.controller.states.value.pending.isEmpty())
    } }

    @Test fun lostApplyProofCannotAcknowledgeChangedDomainOrChangedArchivedJournal() = runTest {
        for (domain in listOf(false, true)) fixture { f ->
            val pending = f.preparePreference(); f.store.whenCommit = { changes -> changes.any { it is StoreMutation.Delete && it.key.collection.endsWith("receipt") } }
            f.store.fault = Fault.AFTER; fail(f.controller.synchronize(pending.commandId), FailureReason.OUTCOME_UNKNOWN)
            f.store.whenCommit = { true }
            if (domain) f.controller.editPreferences(patch(listOf(TWO))).value()
            else {
                val archive = f.store.records.entries.single { it.key.id == pending.commandId && it.key.collection.endsWith("metadata") }
                val raw = Json.parseToJsonElement(archive.value.payload.copyForCodec().decodeToString()).jsonObject
                f.store.records[archive.key] = archive.value.copy(payload = bytes(JsonObject(raw + ("originBinding" to JsonPrimitive(TWO))).toString()))
            }
            val writes = f.store.writes; fail(f.controller.synchronize(pending.commandId), FailureReason.CONFLICT)
            assertEquals(writes, f.store.writes); assertTrue(KitchenInputPreferenceGuard.pending(f.access)); assertEquals(1, f.mutations().size)
            val reads = f.store.reads
            fail(f.controller.discardPreferenceDraft(), FailureReason.CONFLICT)
            assertEquals(reads, f.store.reads); assertEquals(writes, f.store.writes)
            assertNotNull(KitchenInputPreferenceGuard.finalization(f.lease, ORIGIN)); assertTrue(KitchenInputPreferenceGuard.pending(f.access))
        }
    }

    @Test fun cancellationDuringFinalizationAckKeepsProofAndNeverSendsOrReleasesGuard() = runTest { fixture { f ->
        val pending = f.preparePreference(); f.store.whenCommit = { changes -> changes.any { it is StoreMutation.Delete && it.key.collection.endsWith("receipt") } }
        f.store.fault = Fault.AFTER; fail(f.controller.synchronize(pending.commandId), FailureReason.OUTCOME_UNKNOWN)
        f.store.whenCommit = { true }; f.controller.restore().value()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.store.afterCommit = { changes -> if (changes.singleOrNull()?.key == KEY) {
            entered.complete(Unit); withContext(NonCancellable) { release.await() }
        } }
        val job = async { f.controller.synchronize(pending.commandId) }; entered.await(); job.cancel(); release.complete(Unit)
        assertFailsWith<CancellationException> { job.await() }; assertTrue(KitchenInputPreferenceGuard.pending(f.access))
        f.store.afterCommit = null; f.controller.synchronize(pending.commandId).value()
        assertFalse(f.controller.states.value.preferencesPending); assertEquals(1, f.mutations().size)
    } }

    @Test fun successfulReceiptDoesNotLeaveFinalizationActionWhenNewerLocalDraftStillBlocks() = runTest { fixture { f ->
        val pending = f.preparePreference(); f.controller.editPreferences(patch(listOf(TWO))).value()
        val state = f.controller.synchronize(pending.commandId).value()
        assertTrue(state.preferencesPending); assertTrue(state.pending.isEmpty()); assertNotNull(state.preferenceDraft)
        assertNull(KitchenInputPreferenceGuard.finalization(f.lease, ORIGIN))
    } }

    @Test fun receiptReadyMustStillMatchExactJournalBodyAndOriginBeforeAnyDomainApplication() = runTest { fixture { f ->
        val pending = f.preparePreference()
        f.store.whenCommit = { changes -> changes.any { it is StoreMutation.Delete && it.key.collection.endsWith("receipt") } }
        f.store.fault = Fault.BEFORE; fail(f.controller.synchronize(pending.commandId), FailureReason.OUTCOME_UNKNOWN)
        f.store.whenCommit = { true }
        val before = f.record(); val command = before.commands.single()
        val reduced = doc(buildJsonObject { put("equipmentIds", JsonArray(emptyList())) })
        val damaged = before.copy(commands = listOf(KitchenInputCommand(command.id, command.operation, command.target, reduced, command.etag, command.base)))
        val stored = f.store.records.getValue(KEY); f.store.records[KEY] = stored.copy(payload = KitchenInputCodec(ORIGIN).encode(damaged))
        val writes = f.store.writes; fail(f.controller.synchronize(pending.commandId), FailureReason.CONFLICT)
        assertEquals(writes, f.store.writes); assertEquals(1, f.mutations().size); assertEquals("1", pickerVersion(f.record().preferences!!))
    } }

    @Test fun duplicateIncomingPreferenceSelectionsCannotPassSetComparisonOrBecomeCurrent() = runTest { fixture { f ->
        f.handler = { ok(pref(exclusions = listOf(ONE, ONE)), "\"1\"") }
        fail(f.controller.loadPreferences(), FailureReason.INVALID_DATA); assertEquals(0, f.store.writes)
        f.handler = { f.defaultReply(it) }; val pending = f.preparePreference()
        f.handler = { ok(pref("2", listOf(ONE, ONE)), "\"2\"") }
        fail(f.controller.synchronize(pending.commandId), FailureReason.INVALID_DATA)
        assertEquals("1", pickerVersion(f.record().preferences!!)); assertTrue(KitchenInputPreferenceGuard.pending(f.access))
    } }

    @Test fun mismatchingSuccessPayloadVersionOrOwnerRemainsReceiptReadyNotDomainSuccess() = runTest {
        for (mode in 0..3) fixture { f ->
            val pending = f.preparePreference()
            f.handler = { ok(when (mode) { 0 -> pref("1", listOf(ONE)); 1 -> pref("2", listOf(TWO))
                2 -> doc(JsonObject(pref("2", listOf(ONE)).json().jsonObject + ("id" to JsonPrimitive(TWO))))
                else -> pref("2", listOf(ONE)) }, if (mode == 3) "\"3\"" else null) }
            assertIs<PortResult.Failure>(f.controller.synchronize(pending.commandId))
            val restored = f.controller.restore().value(); assertTrue(restored.preferencesPending)
            assertEquals(KitchenInputCommandPhase.RECEIPT_READY, restored.pending.single().phase)
            assertEquals("1", pickerVersion(restored.preferences!!)); assertEquals(1, f.mutations().size)
        }
    }

    @Test fun normalizedSuccessfulSelectionSetsAndExactNumbersAreAcceptedWithoutDoubleRounding() = runTest { fixture { f ->
        f.controller.loadPreferences().value()
        val patch = doc(buildJsonObject { put("hardExcludedIngredientIds", JsonArray(listOf(JsonPrimitive(ONE), JsonPrimitive(TWO))))
            put("defaultServings", Json.parseToJsonElement("9007199254740993")) })
        f.controller.editPreferences(patch).value(); val pending = f.controller.savePreferences().value().pending.single()
        f.handler = { ok(doc(JsonObject(pref("2", listOf(TWO, ONE)).json().jsonObject +
            ("defaultServings" to Json.parseToJsonElement("9007199254740993.0")))), "\"2\"") }
        assertFalse(f.controller.synchronize(pending.commandId).value().preferencesPending)
    } }

    @Test fun boundedPantryPagesRejectDuplicateRowsAndCursorCyclesWithoutDeletingUnfetchedCache() = runTest { fixture { f ->
        f.handler = { ok(page(listOf(pantry(ONE)), "next")) }; f.controller.loadPantry().value()
        f.handler = { ok(page(listOf(pantry(TWO)), "next")) }
        fail(f.controller.nextPantryPage(), FailureReason.CONFLICT)
        assertEquals(listOf(ONE), f.controller.states.value.pantryItems.map(::kiIngredientId))
        f.handler = { ok(page(listOf(pantry(TWO)))) }; f.controller.loadPantry().value()
        assertEquals(setOf(ONE, TWO), f.controller.states.value.pantryItems.map(::kiIngredientId).toSet())
    } }

    @Test fun freshPreferencesOrPartialPantryNeverMakeUnobservedRetainedRowsCurrent() = runTest { fixture { f ->
        f.controller.loadPantry().value(); assertFalse(f.controller.states.value.historical)
        f.reopen(); assertTrue(f.controller.restore().value().historical)
        f.controller.loadPreferences().value(); assertTrue(f.controller.states.value.historical)
        f.handler = { ok(page(emptyList())) }; val emptyRefresh = f.controller.loadPantry().value()
        assertEquals(listOf(ONE), emptyRefresh.pantryItems.map(::kiIngredientId)); assertTrue(emptyRefresh.historical)
    } }

    @Test fun pantryPageLimitIsExplicitAndDoesNotPretendUnfetchedStockIsAbsent() = runTest { fixture { f ->
        f.handler = { call -> ok(page(listOf(pantry(if ("cursor" in call.queryParameters) TWO else ONE)),
            if ("cursor" in call.queryParameters) "third" else "second")) }
        f.controller.loadPantry().value(); assertFalse(f.controller.nextPantryPage().value().pantryHasMore)
        val before = f.calls.size; assertEquals(KitchenInputIssue.PAGE_LIMIT, f.controller.nextPantryPage().value().issue)
        assertEquals(before, f.calls.size)
    } }

    @Test fun sameVersionChangedPreferencesAndPantryAreNotAcceptedAsFresh() = runTest {
        for (preferences in listOf(false, true)) fixture { f ->
            if (preferences) f.controller.loadPreferences().value() else f.controller.loadPantry().value()
            val old = f.store.text(KEY)
            f.handler = { if (preferences) ok(pref(exclusions = listOf(TWO)), "\"1\"") else ok(page(listOf(pantry(ONE, presence = "available")))) }
            fail(if (preferences) f.controller.loadPreferences() else f.controller.loadPantry(), FailureReason.CONFLICT)
            assertEquals(old, f.store.text(KEY))
        }
    }

    @Test fun clockRollbackAndCorruptedOwnerRecordBlockWithoutResetOrErasure() = runTest { fixture { f ->
        f.controller.editPantry(write(ONE)).value(); val before = f.store.text(KEY); f.time--
        fail(f.controller.restore(), FailureReason.CONFLICT); assertEquals(before, f.store.text(KEY))
        f.time++; f.store.records[KEY] = PrivateRecord(2, 1, bytes("{}"))
        assertIs<PortResult.Failure>(f.controller.restore()); assertEquals("{}", f.store.text(KEY)); assertEquals(0, f.store.erases)
    } }

    @Test fun sessionInvalidationSynchronouslyRedactsEveryPrivateProjectionAndPreservesNewLease() = runTest { fixture { f ->
        f.preparePreference(); f.controller.loadPantry().value(); val newer = f.boundary.activate(f.scope)
        val state = f.controller.states.value; assertNull(state.preferences); assertNull(state.preferenceDraft)
        assertTrue(state.pending.isEmpty()); assertTrue(state.pantryItems.isEmpty()); assertTrue(state.stricterExclusionIds.isEmpty())
        fail(f.controller.restore(), FailureReason.STALE_SESSION); assertTrue(f.boundary.isCurrent(newer)); assertEquals(0, f.store.erases)
    } }

    @Test fun delayedReadAndGetCannotPublishAfterSameAccountReplacement() = runTest {
        for (network in listOf(false, true)) fixture { f ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            if (network) f.handler = { entered.complete(Unit); withContext(NonCancellable) { release.await() }; ok(pref(), "\"1\"") }
            else f.store.afterRead = { _, _ -> entered.complete(Unit); withContext(NonCancellable) { release.await() } }
            val job = async { f.controller.loadPreferences() }; entered.await(); val newer = f.boundary.activate(f.scope); release.complete(Unit)
            assertIs<PortResult.Failure>(job.await()); assertNull(f.controller.states.value.preferences); assertTrue(f.boundary.isCurrent(newer))
            assertEquals(0, f.store.writes)
        }
    }

    @Test fun cancelledNoncooperativeReservationCannotReachTransportAndCanRecoverOriginalIntent() = runTest { fixture { f ->
        val pending = f.preparePreference(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.store.afterCommit = { changes -> if (changes.any { it is StoreMutation.Put && it.key.collection.endsWith("metadata") &&
            it.payload.copyForCodec().decodeToString().contains("IN_FLIGHT") }) {
            entered.complete(Unit); withContext(NonCancellable) { release.await() }
        } }
        val job = async { f.controller.synchronize(pending.commandId) }; entered.await(); job.cancel(); release.complete(Unit)
        assertFailsWith<CancellationException> { job.await() }; assertTrue(f.mutations().isEmpty())
        f.store.afterCommit = null; f.reopen(); val restored = f.controller.restore().value().pending.single()
        assertEquals(pending.commandId, restored.commandId); assertEquals(KitchenInputCommandPhase.IN_FLIGHT, restored.phase)
        f.controller.synchronize(pending.commandId).value()
        val retry = f.controller.states.value.pending.single(); f.time = retry.retryAtMillis
        f.controller.synchronize(pending.commandId).value(); assertEquals(1, f.mutations().size); assertEquals(1, f.idCalls)
    } }

    @Test fun cancelledTransportRetainsOriginalDispatchedCommandAcrossRestart() = runTest { fixture { f ->
        val pending = f.preparePreference(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.handler = { entered.complete(Unit); withContext(NonCancellable) { release.await() }; f.defaultReply(it) }
        val job = async { f.controller.synchronize(pending.commandId) }; entered.await(); job.cancel(); release.complete(Unit)
        assertFailsWith<CancellationException> { job.await() }; f.reopen(); assertEquals(pending.commandId, f.controller.restore().value().pending.single().commandId)
        assertEquals(1, f.mutations().size); assertTrue(f.controller.states.value.preferencesPending)
    } }

    @Test fun cancelledEarlierReturnHandoffCannotInvalidateNewerAdmittedSynchronization() = runTest { fixture { f ->
        val pending = f.preparePreference()
        val scheduled = StandardTestDispatcher(testScheduler)
        val held = ArrayDeque<Pair<CoroutineContext, Runnable>>()
        var hold = false
        val caller = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (hold) held.addLast(context to block) else scheduled.dispatch(context, block)
            }
        }
        val earlier = async(caller) { hold = true; f.controller.restore() }
        runCurrent()
        assertEquals(1, held.size); assertFalse(earlier.isCompleted)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.handler = { call -> entered.complete(Unit); release.await(); f.defaultReply(call) }
        val newer = async { f.controller.synchronize(pending.commandId) }
        entered.await()
        assertEquals(1, f.mutations().size)
        assertEquals(pending.commandId, f.mutations().single().idempotencyKey!!.use { it })
        val visible = f.controller.states.value
        earlier.cancel(); hold = false
        while (held.isNotEmpty()) { val (context, block) = held.removeFirst(); scheduled.dispatch(context, block) }
        runCurrent()
        assertFailsWith<CancellationException> { earlier.await() }
        assertSame(visible, f.controller.states.value); assertFalse(newer.isCompleted)
        release.complete(Unit)
        val completed = newer.await().value()
        assertFalse(completed.preferencesPending); assertTrue(completed.pending.isEmpty())
        assertEquals(1, f.mutations().size); assertEquals(1, f.idCalls); assertTrue(f.boundary.isCurrent(f.lease))
    } }

    @Test fun supersedingPreferenceEditFencesLateLoadWithoutLosingTheNewDraft() = runTest { fixture { f ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.handler = { entered.complete(Unit); withContext(NonCancellable) { release.await() }; ok(pref(), "\"1\"") }
        val loading = async { f.controller.loadPreferences() }; entered.await()
        val editing = async { f.controller.editPreferences(patch(listOf(TWO))) }; runCurrent(); release.complete(Unit)
        assertIs<PortResult.Failure>(loading.await()); editing.await().value()
        assertNull(f.controller.states.value.preferences); assertContentEquals(patch(listOf(TWO)).encodeUtf8(), f.controller.states.value.preferenceDraft!!.encodeUtf8())
    } }

    @Test fun preferenceEditDuringMealSendPreventsPublishingLatePlanAndKeepsOriginalKey() = runTest { fixture { f ->
        f.meals.edit(mealDraft()).value(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.handler = { call -> if (call.operationId == "createPlan") { entered.complete(Unit); withContext(NonCancellable) { release.await() } }; f.defaultReply(call) }
        val request = async { f.meals.submit() }; entered.await(); f.controller.editPreferences(patch(listOf(ONE))).value(); release.complete(Unit)
        val result = request.await().value(); assertEquals(MealFlowIssue.PREFERENCES_PENDING, result.issue); assertNull(result.plan)
        val flow = MealFlowCodec(MealRequestBuilder(), ORIGIN).decode(f.store.records.getValue(MEAL).payload)
        assertNotNull(flow.command); assertEquals(1L, flow.command!!.dispatches)
        assertEquals(MealFlowIssue.PREFERENCES_PENDING, f.meals.retrySubmitted().value().issue); assertEquals(1, f.mutations().size)
    } }

    @Test fun closeReleasesOnlyControllerClaimAndNeverBorrowedStoreOrSession() = runTest { fixture { f ->
        f.controller.restore().value(); val duplicate = f.newController()
        try { fail(duplicate.restore(), FailureReason.CONFLICT) } finally { duplicate.close().value() }
        f.controller.close().value(); assertTrue(f.boundary.isCurrent(f.lease)); assertEquals(0, f.store.erases)
        f.controller = f.newController(); f.controller.restore().value()
    } }

    @Test fun readonlyValuesAreDetachedAndDiagnosticStringsNeverContainPrivateIdsOrPatch() = runTest { fixture { f ->
        val pending = f.preparePreference(); val state = f.controller.states.value
        runCatching { (state.pending as MutableList<KitchenInputPending>).clear() }; assertEquals(1, state.pending.size)
        runCatching { (state.stricterExclusionIds as MutableList<String>).clear() }; assertEquals(listOf(ONE), state.stricterExclusionIds)
        for (value in listOf(state, pending, f.controllerPolicy, f.record(), f.record().commands.single())) {
            assertFalse(value.toString().contains(ONE)); assertFalse(value.toString().contains(ORIGIN)); assertFalse(value.toString().contains("hardExcludedIngredientIds"))
        }
    } }

    @Test fun responseProfileAndIndependentRecordReservationAreRequiredBeforeAdmission() = runTest { fixture { f ->
        for (limit in listOf(0, 1023, 262145)) assertFailsWith<IllegalArgumentException> { KitchenInputPolicy(2, 2, 8, 8, limit) }
        f.controller.close().value(); f.controllerPolicy = KitchenInputPolicy(2, 2, 8, 8, 262_144); f.controller = f.newController()
        f.controller.editPantry(write(ONE)).value(); f.controller.savePantry(ONE).value()
        f.controller.editPantry(write(TWO)).value()
        fail(f.controller.savePantry(TWO), FailureReason.RATE_LIMITED)
        assertEquals(1, f.record().commands.size); assertTrue(f.mutations().isEmpty())
    } }

    @Test fun expiredRetryAndFifoConflictDoNotSendOrSilentlyReplaceEarlierPantryIntent() = runTest { fixture { f ->
        f.controller.editPantry(write(ONE)).value(); val first = f.controller.savePantry(ONE).value().pending.single()
        f.controller.editPantry(write(TWO)).value(); val second = f.controller.savePantry(TWO).value().pending.last()
        f.handler = { problem(412) }; f.controller.synchronize(first.commandId).value()
        f.controller.synchronize(second.commandId).value(); assertEquals(1, f.mutations().size)
        assertEquals(0, f.controller.states.value.pending.last().attempts)
        f.time += 7 * 86400_000L; f.controller.synchronize(first.commandId).value(); assertEquals(1, f.mutations().size)
    } }

    private suspend fun TestScope.fixture(guest: Boolean = false, action: suspend (Fixture) -> Unit) {
        val f = Fixture(this, guest)
        try { action(f) } finally { f.controller.close().value(); f.meals.close().value(); f.boundary.clear() }
    }
    private class Fixture(test: TestScope, guest: Boolean) {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = StorageScope("fixture", if (guest) ActorKind.GUEST else ActorKind.ACCOUNT, "private-owner")
        val boundary = SessionBoundary(); val lease = boundary.activate(scope); val store = Store(scope)
        var time = 10_000L; var online = true; var idCalls = 0; var fixedId: String? = null
        var preference = pref(); var tag: String? = "\"1\""; var rows = listOf(pantry(ONE))
        val calls = mutableListOf<ApiCall>()
        var handler: suspend (ApiCall) -> PortResult<ApiReply> = { defaultReply(it) }
        val access = AuthenticatedMealPlanningAccess(lease, ORIGIN, store, object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                assertSame(this@Fixture.lease, lease); calls += call; return handler(call)
            }
        }, true)
        val source = MealOperationIds { idCalls++; fixedId ?: id(100 + idCalls) }
        var controllerPolicy = KitchenInputPolicy(2, 2, 8, 8, 65_536)
        var controller = newController(); var meals = newMeals()
        fun newController() = KitchenInputController(access, boundary, dispatcher, EpochClock { time },
            ConnectivityPort { if (online) Connectivity.ONLINE else Connectivity.OFFLINE }, source, controllerPolicy)
        fun newMeals() = MealRequestController(access, boundary, dispatcher, EpochClock { time },
            ConnectivityPort { if (online) Connectivity.ONLINE else Connectivity.OFFLINE }, source, MealFlowPolicy(60_000, 60_000, 3, 128))
        suspend fun reopen() { controller.close().value(); controller = newController() }
        suspend fun preparePreference(): KitchenInputPending {
            controller.loadPreferences().value(); controller.editPreferences(patch(listOf(ONE))).value()
            return controller.savePreferences().value().pending.single()
        }
        fun mutations() = calls.filter { it.operationId !in setOf("getPreferences", "listPantry") }
        fun record() = KitchenInputCodec(ORIGIN).decode(store.records.getValue(KEY).payload)
        fun defaultReply(call: ApiCall): PortResult<ApiReply> = when (call.operationId) {
            "getPreferences" -> ok(preference, tag)
            "listPantry" -> ok(page(rows))
            "updatePreferences" -> {
                val body = WireDocument.decode(call.body!!.copyForCodec()).json().jsonObject
                val next = pickerVersion(preference).toLong() + 1
                preference = doc(JsonObject(preference.json().jsonObject + body + ("version" to JsonPrimitive(next))))
                tag = "\"$next\""; ok(preference, tag)
            }
            "upsertPantryItem" -> {
                val input = WireDocument.decode(call.body!!.copyForCodec()).json().jsonObject; val target = input.getValue("ingredientId").jsonPrimitive.content
                val previous = rows.singleOrNull { kiIngredientId(it) == target }; val version = (previous?.let(::pickerVersion)?.toLong() ?: 0) + 1
                val result = doc(JsonObject(pantry(target).json().jsonObject + (input - "expectedVersion") + ("version" to JsonPrimitive(version))))
                rows = rows.filterNot { kiIngredientId(it) == target } + result; ok(result, "\"$version\"")
            }
            "removePantryItem" -> { rows = rows.filterNot { kiIngredientId(it) == call.pathParameters["ingredientId"] }; PortResult.Value(ApiReply(204, null)) }
            "createPlan" -> {
                val request = WireDocument.decode(call.body!!.copyForCodec()).json().jsonObject
                PortResult.Value(ApiReply(201, PrivateBytes(doc(buildJsonObject {
                    put("id", id(900)); put("version", 1); put("createdAt", TIME); put("updatedAt", TIME)
                    put("status", "noMatch"); put("constraints", request.getValue("constraints")); put("reasons", JsonArray(emptyList()))
                }).encodeUtf8()), etag = "\"1\"", contentType = "application/json"))
            }
            else -> error("Unexpected canonical operation")
        }
    }
    private enum class Fault { BEFORE, AFTER, BAD_REVISION, BAD_READBACK }
    private class Store(private val scope: StorageScope) : PrivateStateStore {
        val records = mutableMapOf<RecordKey, PrivateRecord>(); private val counters = mutableMapOf<RecordKey, Long>()
        var reads = 0; var writes = 0; var erases = 0; var fault: Fault? = null
        var whenCommit: (List<StoreMutation>) -> Boolean = { true }
        var afterRead: (suspend (RecordKey, PrivateRecord?) -> Unit)? = null
        var afterCommit: (suspend (List<StoreMutation>) -> Unit)? = null
        private var corrupt: RecordKey? = null
        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> {
            assertEquals(this.scope, scope); reads++
            val record = records[key]?.let { it.copy(payload = PrivateBytes(it.payload.copyForCodec())) }
            afterRead?.invoke(key, record)
            return PortResult.Value(if (key == corrupt) { corrupt = null; record?.copy(payload = bytes("{}")) } else record)
        }
        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> {
            assertEquals(this.scope, scope); assertEquals(mutations.size, mutations.map { it.key }.distinct().size)
            if (mutations.any { it.expectedRevision != records[it.key]?.revision }) return PortResult.Failure(FailureReason.CONFLICT)
            val failure = if (whenCommit(mutations)) fault.also { fault = null } else null
            if (failure == Fault.BEFORE) return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
            val receipt = mutations.associate { mutation ->
                val revision = (counters[mutation.key] ?: records[mutation.key]?.revision ?: 0L) + 1; counters[mutation.key] = revision
                if (mutation is StoreMutation.Put) records[mutation.key] = PrivateRecord(revision, mutation.schemaVersion, PrivateBytes(mutation.payload.copyForCodec()))
                else records.remove(mutation.key)
                mutation.key to if (mutation is StoreMutation.Put) revision else null
            }
            writes++; afterCommit?.invoke(mutations)
            if (failure == Fault.AFTER) return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
            if (failure == Fault.BAD_READBACK) corrupt = mutations.first().key
            return PortResult.Value(if (failure == Fault.BAD_REVISION) receipt.mapValues { 0L } else receipt)
        }
        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> { erases++; return PortResult.Failure(FailureReason.NOT_CONFIGURED) }
        fun text(key: RecordKey) = records.getValue(key).payload.copyForCodec().decodeToString()
    }
    private companion object {
        const val ORIGIN = "00000000-0000-4000-8000-000000000099"
        const val ONE = "00000000-0000-4000-8000-000000000001"
        const val TWO = "00000000-0000-4000-8000-000000000002"
        const val THREE = "00000000-0000-4000-8000-000000000003"
        const val TIME = "2026-09-14T00:00:00Z"
        val KEY = RecordKey("mealflow.kitchen-inputs.v1", ORIGIN); val MEAL = RecordKey("mealflow.v1", ORIGIN)
        fun id(number: Int) = "00000000-0000-4000-8000-${number.toString().padStart(12, '0')}"
        fun pref(version: String = "1", exclusions: List<String> = emptyList()) = doc(buildJsonObject {
            put("id", id(50)); put("version", Json.parseToJsonElement(version)); put("createdAt", TIME); put("updatedAt", TIME)
            put("hardExcludedIngredientIds", JsonArray(exclusions.map(::JsonPrimitive))); put("dietaryPatterns", JsonArray(emptyList()))
            put("dislikedIngredientIds", JsonArray(emptyList())); put("equipmentIds", JsonArray(emptyList()))
        })
        fun patch(exclusions: List<String> = listOf(ONE)) = doc(buildJsonObject { put("hardExcludedIngredientIds", JsonArray(exclusions.map(::JsonPrimitive))) })
        fun write(ingredient: String, presence: String = "uncertain") = doc(buildJsonObject { put("ingredientId", ingredient); put("presence", presence) })
        fun pantry(ingredient: String, version: String = "1", presence: String = "uncertain") = doc(buildJsonObject {
            put("id", ingredient); put("version", Json.parseToJsonElement(version)); put("createdAt", TIME); put("updatedAt", TIME)
            put("ingredientId", ingredient); put("presence", presence); put("confirmedAt", JsonNull); put("staple", false)
        })
        fun page(rows: List<WireDocument>, cursor: String? = null) = doc(buildJsonObject {
            put("items", JsonArray(rows.map { it.json() })); put("nextCursor", cursor?.let(::JsonPrimitive) ?: JsonNull); put("serverTime", TIME)
        })
        fun mealDraft() = ManualMealDraft(MealMode.AUTO, MealEnergy.LITTLE, "1", listOf(TWO), emptyList(), emptyList(), emptyList())
        fun ok(document: WireDocument, tag: String? = null): PortResult<ApiReply> = PortResult.Value(ApiReply(200, PrivateBytes(document.encodeUtf8()), etag = tag, contentType = "application/json"))
        fun problem(status: Int): PortResult<ApiReply> = PortResult.Value(ApiReply(status, PrivateBytes(doc(buildJsonObject {
            put("type", "about:blank"); put("title", "Rejected"); put("status", status); put("code", "INPUT_INVALID"); put("traceId", "fixture")
        }).encodeUtf8()), traceId = "fixture", contentType = "application/problem+json"))
        fun bytes(text: String) = PrivateBytes(text.encodeToByteArray())
        fun <T> PortResult<T>.value(): T = assertIs<PortResult.Value<T>>(this).value
        fun fail(result: PortResult<*>, reason: FailureReason) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
    }
}
