package com.feedme.development.progress

import com.feedme.contracts.*
import com.feedme.core.ports.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Pure service-ledger tests with an explicit fake CAS store; native ownership is tested separately. */
class ProgressServiceLedgerTest {
    @Test fun freshLedgerOnlyWritesItsOwnServiceRecordAndReopensWithoutMutation() = runTest {
        val f = Fixture(); f.open(); assertEquals(setOf(ProgressServiceLedger.KEY), f.store.records.keys)
        assertEquals(1, f.store.commits); f.service.close(); f.newService(); f.open(); assertEquals(1, f.store.commits)
        val reply = f.service.execute(ApiCall("getPreferences")).value()
        assertEquals(200, reply.status); assertEquals("\"1\"", reply.etag)
    }
    @Test fun actualPlannerKeepsExactScaledSyntheticSnapshotAndNonmatchingInputsNonready() = runTest {
        val f = Fixture(); f.open()
        val ready = f.send("createPlan", planBody(servings = 2)).json()
        assertEquals("ready", ready.string("status")); assertEquals("assemble", ready.string("mode"))
        assertEquals("200", ready.obj("recipeSnapshot").getValue("ingredients").jsonArray.first().jsonObject["quantity"].toString())
        val notReady = f.send("createPlan", planBody(equipment = false)).json()
        assertEquals("noMatch", notReady.string("status")); assertFalse("recipeSnapshot" in notReady)
    }
    @Test fun exactCommandReplayAfterCloseUsesOriginalIdBytesAndOnePersistedPlan() = runTest {
        val f = Fixture(); f.open(); val call = command("createPlan", planBody())
        val first = f.service.execute(call).value(); f.service.close(); f.newService(); f.open()
        val second = f.service.execute(call).value()
        assertContentEquals(first.body!!.copyForCodec(), second.body!!.copyForCodec()); assertEquals(first.etag, second.etag)
        assertEquals(1, f.state().obj("plans").size); assertEquals(1, f.state().obj("receipts").size)
    }
    @Test fun commitAcknowledgementLostAfterMutationRemainsUnknownThenReplaysActualStoredOutcome() = runTest {
        val f = Fixture(); f.open(); val call = command("createPlan", planBody())
        f.store.loseAfterCommit = true
        assertEquals(PortResult.Failure(FailureReason.OUTCOME_UNKNOWN), f.service.execute(call))
        val id = f.state().obj("plans").keys.single()
        f.service.close(); f.newService(); f.open()
        assertEquals(id, f.service.execute(call).value().json().string("id")); assertEquals(1, f.state().obj("plans").size)
    }
    @Test fun unknownCommitWithoutMutationDoesNotCreateAReceiptOrPretendSuccess() = runTest {
        val f = Fixture(); f.open(); val call = command("createPlan", planBody()); f.store.failBeforeCommit = true
        assertEquals(PortResult.Failure(FailureReason.OUTCOME_UNKNOWN), f.service.execute(call)); assertTrue(f.state().obj("plans").isEmpty())
        assertEquals(201, f.service.execute(call).value().status); assertEquals(1, f.state().obj("plans").size)
    }
    @Test fun matchingReadbackWithoutFreshReplayAcknowledgementStillCannotResolveCommand() = runTest {
        val f = Fixture(); f.open(); val call = command("createPlan", planBody()); f.service.execute(call).value()
        f.store.failBeforeCommit = true
        assertEquals(PortResult.Failure(FailureReason.OUTCOME_UNKNOWN), f.service.execute(call))
    }
    @Test fun sameIdempotencyKeyDifferentBodyIsCanonicalConflictWithoutReplacingOriginal() = runTest {
        val f = Fixture(); f.open(); val key = uuid(); val a = command("createPlan", planBody(), key)
        val first = f.service.execute(a).value(); val count = f.store.commits
        val conflict = f.service.execute(command("createPlan", planBody(servings = 2), key)).value()
        assertEquals(409, conflict.status); assertEquals("application/problem+json", conflict.contentType); assertEquals(count, f.store.commits)
        assertContentEquals(first.body!!.copyForCodec(), f.service.execute(a).value().body!!.copyForCodec())
    }
    @Test fun malformedRequestAndUnknownCapabilityDoNotTouchStoredState() = runTest {
        val f = Fixture(); f.open(); val before = f.store.commits
        assertEquals(PortResult.Failure(FailureReason.INVALID_DATA), f.service.execute(command("createPlan", "{}")))
        assertEquals(PortResult.Failure(FailureReason.NOT_CONFIGURED), f.service.execute(ApiCall("listCollections")))
        assertEquals(before, f.store.commits)
    }
    @Test fun preferencesAreVersionedAndHardExclusionCannotProduceReadyPlan() = runTest {
        val f = Fixture(); f.open()
        val changed = f.send("updatePreferences", """{"hardExcludedIngredientIds":["${ProgressCatalog.ingredientId}"]}""", ifMatch = "\"1\"")
        assertEquals("\"2\"", changed.etag)
        assertEquals(412, f.send("updatePreferences", "{}", ifMatch = "\"1\"").status)
        assertNotEquals("ready", f.send("createPlan", planBody(preferenceVersion = 2)).json().string("status"))
    }
    @Test fun pantryCreationSuppliesNullConfirmationAndDeleteRecreateChangesIncarnation() = runTest {
        val f = Fixture(); f.open(); val first = f.send("upsertPantryItem", pantryBody()).json()
        assertEquals(JsonNull, first["confirmedAt"])
        val remove = command("removePantryItem", null, path = mapOf("ingredientId" to ProgressCatalog.ingredientId), ifMatch = "\"1\"")
        assertEquals(204, f.service.execute(remove).value().status)
        val recreated = f.send("upsertPantryItem", pantryBody()).json()
        assertEquals("2", recreated["version"].toString()); assertNotEquals(first.string("id"), recreated.string("id"))
        assertEquals(409, f.service.execute(remove).value().status)
        assertEquals(recreated, f.state().obj("pantry").getValue(ProgressCatalog.ingredientId))
    }
    @Test fun cookingCreateProgressCompletionAreExactAndOldCreateCannotResurrectActiveState() = runTest {
        val f = Fixture(); f.open(); val plan = f.send("createPlan", planBody()).json().string("id")
        val create = command("createCookSession", """{"planId":"$plan","deviceSequence":0}""")
        val session = f.service.execute(create).value().json().string("id")
        val path = mapOf("sessionId" to session)
        val progress = command("updateCookSession", """{"deviceSequence":1,"currentStepId":"second","completedStepIds":["first"],"timers":[]}""", path = path, ifMatch = "\"1\"")
        assertEquals("\"2\"", f.service.execute(progress).value().etag)
        val complete = command("completeCookSession", """{"deviceSequence":2,"makeAgain":false}""", path = path)
        val done = f.service.execute(complete).value(); assertEquals("completed", done.json().string("status"))
        assertEquals(listOf("first"), done.json().getValue("completedStepIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(409, f.service.execute(create).value().status); assertEquals(409, f.service.execute(progress).value().status)
        f.service.close(); f.newService(); f.open()
        assertContentEquals(done.body!!.copyForCodec(), f.service.execute(complete).value().body!!.copyForCodec())
    }
    @Test fun cookingRejectsWrongPlanWrongStepWrongVersionSequenceAndUnavailableMakeAgain() = runTest {
        val f = Fixture(); f.open()
        assertEquals(404, f.send("createCookSession", """{"planId":"${uuid()}","deviceSequence":0}""").status)
        val plan = f.send("createPlan", planBody()).json().string("id")
        val session = f.send("createCookSession", """{"planId":"$plan","deviceSequence":0}""").json().string("id")
        val path = mapOf("sessionId" to session)
        assertEquals(422, f.send("updateCookSession", """{"deviceSequence":1,"currentStepId":"unknown"}""", path, "\"1\"").status)
        assertEquals(412, f.send("updateCookSession", """{"deviceSequence":1}""", path, "\"2\"").status)
        assertEquals(409, f.send("updateCookSession", """{"deviceSequence":2}""", path, "\"1\"").status)
        assertEquals(503, f.send("completeCookSession", """{"deviceSequence":1,"makeAgain":true}""", path).status)
        assertEquals("active", f.service.execute(ApiCall("getCookSession", pathParameters = path)).value().json().string("status"))
    }
    @Test fun learnedStaleSessionBlocksReadWriteAndCloseNeverErasesBorrowedStore() = runTest {
        val f = Fixture(); f.open(); val count = f.store.commits; f.current = false
        assertEquals(PortResult.Failure(FailureReason.STALE_SESSION), f.service.execute(ApiCall("getPreferences")))
        assertEquals(count, f.store.commits); assertIs<PortResult.Value<Unit>>(f.service.close()); assertEquals(0, f.store.erases)
    }
    @Test fun foreignOriginAndCorruptExistingRecordNeverReinitialize() = runTest {
        val f = Fixture(); f.open(); val before = f.store.commits
        val foreign = ProgressServiceLedger(f.store, ProgressIdentity.scope, uuid(), EpochClock { f.now }) {}
        assertIs<PortResult.Failure>(foreign.open(allowInitialize = false)); assertEquals(before, f.store.commits)
        f.store.records[ProgressServiceLedger.KEY] = PrivateRecord(1, 1, PrivateBytes("{}".encodeToByteArray()))
        f.newService(); assertIs<PortResult.Failure>(f.service.open(allowInitialize = false)); assertEquals(before, f.store.commits)
    }
    @Test fun clockRollbackDoesNotCreateOrReplayCommands() = runTest {
        val f = Fixture(); f.open(); val call = command("createPlan", planBody()); f.service.execute(call).value(); f.now--
        assertEquals(PortResult.Failure(FailureReason.CONFLICT), f.service.execute(call))
    }
    @Test fun missingRestoredLedgerIsRepairOnlyNotPermissionToCreateNewOutcomes() = runTest {
        val f = Fixture()
        assertEquals(PortResult.Failure(FailureReason.NOT_FOUND), f.service.open(allowInitialize = false))
        assertEquals(0, f.store.commits); assertTrue(f.store.records.isEmpty())
    }
    @Test fun explicitInitialCreateAcceptsAnActualHigherPositiveStoreRevision() = runTest {
        val f = Fixture(); f.store.initialRevision = 7; f.open()
        assertEquals(7L, f.store.records.getValue(ProgressServiceLedger.KEY).revision)
        assertEquals(200, f.service.execute(ApiCall("getPreferences")).value().status)
    }
    @Test fun unsupportedCursorNeverReturnsTheFirstPageAsContinuation() = runTest {
        val f = Fixture(); f.open()
        assertEquals(422, f.service.execute(ApiCall("listPantry", queryParameters = mapOf("cursor" to listOf("opaque")))).value().status)
    }
    @Test fun invalidationAfterCommittedMutationReturnsStaleWithoutLosingOriginalReceipt() = runTest {
        val f = Fixture(); f.open(); f.store.afterCommit = { f.current = false }
        assertEquals(PortResult.Failure(FailureReason.STALE_SESSION), f.service.execute(command("createPlan", planBody())))
        assertEquals(1, f.state().obj("receipts").size)
    }

    @Test fun timerProgressPersistsAndReplaysExactlyWithoutAdvancingOrCompletingCooking() = runTest {
        val f = Fixture(); f.open(); val plan = f.send("createPlan", planBody()).json().string("id")
        val created = f.send("createCookSession", """{"planId":"$plan","deviceSequence":0}""").json()
        val path = mapOf("sessionId" to created.string("id")); val timer = timerBody(uuid())
        val call = command("updateCookSession", """{"deviceSequence":1,"timers":[$timer]}""", path = path, ifMatch = "\"1\"")
        val changed = f.service.execute(call).value(); assertEquals(200, changed.status); assertEquals("\"2\"", changed.etag)
        assertEquals("active", changed.json().string("status")); assertEquals(created["currentStepId"], changed.json()["currentStepId"])
        assertEquals(JsonArray(emptyList()), changed.json()["completedStepIds"])
        assertEquals(Json.parseToJsonElement(timer), changed.json().getValue("timers").jsonArray.single())
        f.service.close(); f.newService(); f.open()
        assertContentEquals(changed.body!!.copyForCodec(), f.service.execute(call).value().body!!.copyForCodec())
        assertEquals(changed.json(), f.service.execute(ApiCall("getCookSession", pathParameters = path)).value().json())
    }
    @Test fun timerPatchRejectsForeignStepsDuplicateIdentitiesAndApplicationCapacityWithoutCookingMutation() = runTest {
        val f = Fixture(); f.open(); val plan = f.send("createPlan", planBody()).json().string("id")
        val session = f.send("createCookSession", """{"planId":"$plan","deviceSequence":0}""").json().string("id")
        val path = mapOf("sessionId" to session); val timerId = uuid(); val timer = timerBody(timerId)
        val before = f.state(); val beforeCommits = f.store.commits
        val rejected = listOf("[${timer.replace("first", "foreign-step")}]", "[$timer,$timer]",
            "[$timer,${timerBody(timerId.uppercase())}]",
            "[${timer.replace("\"durationSeconds\":2", "\"durationSeconds\":86401")}]",
            (1..33).joinToString(",", "[", "]") { timerBody(uuid()) })
        for (timers in rejected) {
            val call = command("updateCookSession", """{"deviceSequence":1,"timers":$timers}""", path = path, ifMatch = "\"1\"")
            val rejection = f.service.execute(call).value(); assertEquals(422, rejection.status)
            assertContentEquals(rejection.body!!.copyForCodec(), f.service.execute(call).value().body!!.copyForCodec())
        }
        // Business rejection receipts and their fresh retry acknowledgements are deliberate
        // ledger writes. They must not mutate cooking, sequence/version, plans or kitchen data.
        assertEquals(beforeCommits + rejected.size * 2, f.store.commits)
        assertEquals(before.obj("receipts").size + rejected.size, f.state().obj("receipts").size)
        assertEquals(JsonObject(before - "receipts" - "lastMillis"), JsonObject(f.state() - "receipts" - "lastMillis"))
        assertEquals(JsonArray(emptyList()), f.service.execute(ApiCall("getCookSession", pathParameters = path)).value().json()["timers"])
    }
    @Test fun lostTimerProgressAcknowledgementRetainsOriginalSequenceAndExactRetryAfterReopen() = runTest {
        val f = Fixture(); f.open(); val plan = f.send("createPlan", planBody()).json().string("id")
        val session = f.send("createCookSession", """{"planId":"$plan","deviceSequence":0}""").json().string("id")
        val path = mapOf("sessionId" to session); val timer = timerBody(uuid())
        val call = command("updateCookSession", """{"deviceSequence":1,"timers":[$timer]}""", path = path, ifMatch = "\"1\"")
        f.store.loseAfterCommit = true
        assertEquals(PortResult.Failure(FailureReason.OUTCOME_UNKNOWN), f.service.execute(call))
        val retained = f.state().obj("sessions").getValue(session)
        f.service.close(); f.newService(); f.open()
        val recovered = f.service.execute(call).value().json(); assertEquals(retained, recovered)
        assertEquals("1", recovered["deviceSequence"].toString()); assertEquals(1, recovered.getValue("timers").jsonArray.size)
    }
    @Test fun timerCanonicalDoneStateDoesNotCompleteAMealAndEmptyPatchCancelsOnlyTimerMetadata() = runTest {
        val f = Fixture(); f.open(); val plan = f.send("createPlan", planBody()).json().string("id")
        val session = f.send("createCookSession", """{"planId":"$plan","deviceSequence":0}""").json().string("id")
        val path = mapOf("sessionId" to session); val timer = timerBody(uuid()).replace("running", "done")
        val patched = f.send("updateCookSession", """{"deviceSequence":1,"timers":[$timer]}""", path, "\"1\"").json()
        assertEquals("active", patched.string("status")); assertEquals(JsonArray(emptyList()), patched["completedStepIds"])
        val cleared = f.send("updateCookSession", """{"deviceSequence":2,"timers":[]}""", path, "\"2\"").json()
        assertEquals(JsonArray(emptyList()), cleared["timers"]); assertEquals("active", cleared.string("status"))
    }

    @Test fun cookbookSaveCapturesExactScaledPlanAndDoesNotChangeCookingOrPlan() = runTest {
        val f = Fixture(); f.open(); val plan = f.send("createPlan", planBody(servings = 2)).json()
        val planId = plan.string("id"); f.send("createCookSession", """{"planId":"$planId","deviceSequence":0}""")
        val before = f.state(); val saved = f.send("saveRecipe", """{"planId":"$planId","title":"My crunch"}""")
        assertEquals(201, saved.status); assertEquals("\"1\"", saved.etag)
        assertEquals(plan["recipeSnapshot"], saved.json()["snapshot"]); assertEquals("ownPlan", saved.json().string("sourceType"))
        assertEquals("200", saved.json().obj("snapshot").getValue("ingredients").jsonArray.first().jsonObject["quantity"].toString())
        for (field in listOf("plans", "sessions", "preferences", "pantry")) assertEquals(before[field], f.state()[field])
        val get = f.service.execute(ApiCall("getSavedRecipe", pathParameters = mapOf("savedRecipeId" to saved.json().string("id")))).value()
        assertEquals(200, get.status); assertContentEquals(saved.body!!.copyForCodec(), get.body!!.copyForCodec())
    }
    @Test fun cookbookDedupKeepsOriginalIdentityTitleAndProvenanceAcrossEquivalentPlans() = runTest {
        val f = Fixture(); f.open(); val a = f.send("createPlan", planBody()).json().string("id")
        val first = f.send("saveRecipe", """{"planId":"$a","title":"First title"}""")
        f.now++; val b = f.send("createPlan", planBody()).json().string("id"); assertNotEquals(a, b)
        val same = f.send("saveRecipe", """{"planId":"$b"}""")
        assertContentEquals(first.body!!.copyForCodec(), same.body!!.copyForCodec())
        assertEquals(409, f.send("saveRecipe", """{"planId":"$b","title":"Not a rename"}""").status)
        assertEquals(1, f.state().obj("savedRecipes").size)
    }
    @Test fun cookbookDistinctMaterializedServingsRemainDistinctPrivateCopies() = runTest {
        val f = Fixture(); f.open()
        val ids = (1..2).map { servings -> val plan = f.send("createPlan", planBody(servings)).json().string("id")
            f.send("saveRecipe", """{"planId":"$plan"}""").json().string("id") }
        assertEquals(2, ids.distinct().size); assertEquals(2, f.state().obj("savedLatest").size)
    }
    @Test fun cookbookLostSaveAcknowledgementAndReopenRetainExactOriginalReceipt() = runTest {
        val f = Fixture(); f.open(); val plan = f.send("createPlan", planBody()).json().string("id")
        val call = command("saveRecipe", """{"planId":"$plan"}"""); f.store.loseAfterCommit = true
        assertEquals(PortResult.Failure(FailureReason.OUTCOME_UNKNOWN), f.service.execute(call))
        val original = f.state().obj("savedRecipes").values.single(); f.service.close(); f.newService(); f.open()
        f.store.failBeforeCommit = true
        assertEquals(PortResult.Failure(FailureReason.OUTCOME_UNKNOWN), f.service.execute(call))
        assertEquals(original, f.service.execute(call).value().json()); assertEquals(1, f.state().obj("savedRecipes").size)
    }
    @Test fun cookbookDeleteRequiresExactVersionAndDoesNotEraseItsPlan() = runTest {
        val f = Fixture(); f.open(); val plan = f.send("createPlan", planBody()).json().string("id")
        val saved = f.send("saveRecipe", """{"planId":"$plan"}""").json().string("id"); val path = mapOf("savedRecipeId" to saved)
        assertEquals(412, f.send("deleteSavedRecipe", null, path, "\"2\"").status)
        assertEquals(200, f.service.execute(ApiCall("getSavedRecipe", pathParameters = path)).value().status)
        val removed = f.send("deleteSavedRecipe", null, path, "\"1\"")
        assertEquals(204, removed.status); assertNull(removed.body); assertNull(removed.etag)
        assertEquals(404, f.service.execute(ApiCall("getSavedRecipe", pathParameters = path)).value().status)
        assertEquals(200, f.service.execute(ApiCall("getPlan", pathParameters = mapOf("planId" to plan))).value().status)
    }
    @Test fun cookbookLostDeleteAcknowledgementCanReplayButResaveFencesBothOldCommands() = runTest {
        val f = Fixture(); f.open(); val plan = f.send("createPlan", planBody()).json().string("id")
        val save = command("saveRecipe", """{"planId":"$plan"}"""); val id = f.service.execute(save).value().json().string("id")
        val delete = command("deleteSavedRecipe", null, path = mapOf("savedRecipeId" to id), ifMatch = "\"1\"")
        f.store.loseAfterCommit = true; assertEquals(PortResult.Failure(FailureReason.OUTCOME_UNKNOWN), f.service.execute(delete))
        f.service.close(); f.newService(); f.open(); assertEquals(204, f.service.execute(delete).value().status)
        assertEquals(409, f.service.execute(save).value().status)
        val newId = f.send("saveRecipe", """{"planId":"$plan"}""").json().string("id"); assertNotEquals(id, newId)
        assertEquals(409, f.service.execute(delete).value().status); assertEquals(409, f.service.execute(save).value().status)
        assertEquals(JsonNull, f.state().obj("savedRecipes")[id])
        assertEquals(200, f.service.execute(ApiCall("getSavedRecipe", pathParameters = mapOf("savedRecipeId" to newId))).value().status)
    }
    @Test fun cookbookPagesUseCanonicalShapeExactScopeAndNoWritesOnRead() = runTest {
        val f = Fixture(); f.open()
        for (servings in 1..3) { val id = f.send("createPlan", planBody(servings)).json().string("id")
            f.send("saveRecipe", """{"planId":"$id","title":"Crunch $servings"}""") }
        val commits = f.store.commits; val seen = mutableListOf<String>(); var cursor: String? = null
        do {
            val query = mapOf("limit" to listOf("1")) + (cursor?.let { mapOf("cursor" to listOf(it)) } ?: emptyMap())
            val page = f.service.execute(ApiCall("listSavedRecipes", queryParameters = query)).value().json()
            assertEquals(setOf("items", "nextCursor", "serverTime"), page.keys)
            seen += page.getValue("items").jsonArray.single().jsonObject.string("id")
            cursor = page["nextCursor"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
        } while (cursor != null)
        assertEquals(3, seen.distinct().size); assertEquals(seen.sorted(), seen); assertEquals(commits, f.store.commits)
        val filtered = f.service.execute(ApiCall("listSavedRecipes", queryParameters = mapOf("q" to listOf("UNCH 2")))).value().json()
        assertEquals("Crunch 2", filtered.getValue("items").jsonArray.single().jsonObject.string("title"))
    }
    @Test fun cookbookCursorCannotCrossQueryLimitOriginOrChangedSnapshot() = runTest {
        val f = Fixture(); f.open()
        for (servings in 1..2) { val id = f.send("createPlan", planBody(servings)).json().string("id"); f.send("saveRecipe", """{"planId":"$id"}""") }
        val first = f.service.execute(ApiCall("listSavedRecipes", queryParameters = mapOf("limit" to listOf("1")))).value().json()
        val cursor = first.string("nextCursor"); val query = mapOf("limit" to listOf("1"), "cursor" to listOf(cursor))
        for (changed in listOf(query + ("q" to listOf("")), query + ("limit" to listOf("2"))))
            assertEquals(409, f.service.execute(ApiCall("listSavedRecipes", queryParameters = changed)).value().status)
        val foreign = Fixture(); foreign.open()
        assertEquals(409, foreign.service.execute(ApiCall("listSavedRecipes", queryParameters = query)).value().status)
        val id = first.getValue("items").jsonArray.single().jsonObject.string("id")
        f.send("deleteSavedRecipe", null, mapOf("savedRecipeId" to id), "\"1\"")
        assertEquals(409, f.service.execute(ApiCall("listSavedRecipes", queryParameters = query)).value().status)
        assertEquals(422, f.service.execute(ApiCall("listSavedRecipes", queryParameters = mapOf("cursor" to listOf("garbage")))).value().status)
    }
    @Test fun cookbookUnavailableCopySourcesAndMakeAgainNeverCreateSaves() = runTest {
        val f = Fixture(); f.open(); val id = f.send("createPlan", planBody()).json().string("id")
        val nonready = f.send("createPlan", planBody(equipment = false)).json().string("id")
        assertEquals(409, f.send("saveRecipe", """{"planId":"$nonready"}""").status)
        assertEquals(404, f.send("saveRecipe", """{"planId":"${uuid()}"}""").status)
        for (body in listOf("""{"planId":"$id","markMakeAgain":true}""", """{"recipeVersionId":"${ProgressCatalog.recipeVersionId}"}""",
            """{"planId":"$id","collectionId":"${uuid()}"}""")) assertEquals(503, f.send("saveRecipe", body).status)
        assertTrue(f.state().obj("savedRecipes").isEmpty())
    }
    @Test fun legacyPreviewLedgerOpensWithoutWriteAndAddsCookbookOnlyOnAcknowledgedCommand() = runTest {
        val f = Fixture(); f.open(); val planCall = command("createPlan", planBody()); val original = f.service.execute(planCall).value()
        val old = f.store.records.getValue(ProgressServiceLedger.KEY)
        f.store.records[ProgressServiceLedger.KEY] = PrivateRecord(old.revision, old.schemaVersion,
            PrivateBytes(JsonObject(f.state() - "savedRecipes" - "savedLatest" - "withdrawnRecipeVersions").toString().encodeToByteArray()))
        val commits = f.store.commits; f.service.close(); f.newService(); f.open()
        assertEquals(200, f.service.execute(ApiCall("listSavedRecipes")).value().status); assertEquals(commits, f.store.commits)
        assertFalse("savedRecipes" in f.state())
        assertContentEquals(original.body!!.copyForCodec(), f.service.execute(planCall).value().body!!.copyForCodec())
        assertTrue(f.state().obj("savedRecipes").isEmpty()); assertEquals(1, f.state().obj("plans").size)
    }
    @Test fun cookbookCorruptLineageOrPartialAdditiveStateNeverReinitializes() = runTest {
        for (partial in listOf(true, false)) {
            val f = Fixture(); f.open(); val id = f.send("createPlan", planBody()).json().string("id"); f.send("saveRecipe", """{"planId":"$id"}""")
            val old = f.store.records.getValue(ProgressServiceLedger.KEY)
            val corrupt = if (partial) JsonObject(f.state() - "savedLatest") else JsonObject(f.state() + ("savedLatest" to JsonObject(emptyMap())))
            f.store.records[ProgressServiceLedger.KEY] = PrivateRecord(old.revision, old.schemaVersion, PrivateBytes(corrupt.toString().encodeToByteArray()))
            val commits = f.store.commits; f.service.close(); f.newService()
            assertIs<PortResult.Failure>(f.service.open(allowInitialize = true)); assertEquals(commits, f.store.commits)
        }
    }
    @Test fun cookbookLargeCanonicalPageStopsBeforeByteCapAndContinuesWithoutSkippingItem() = runTest {
        val f = Fixture(); f.open()
        val plans = (1..2).map { f.send("createPlan", planBody(it)).json() }
        // Canonical synthetic large-content fixture: exercise the service response envelope,
        // not a claim that the one-recipe preview catalog distributes these instructions.
        val largePlans = plans.associate { plan ->
            val snapshot = plan.obj("recipeSnapshot")
            val steps = snapshot.getValue("steps").jsonArray.toMutableList()
            steps[0] = JsonObject(steps[0].jsonObject + ("instruction" to JsonPrimitive("x".repeat(135_000))))
            val large = JsonObject(plan + ("recipeSnapshot" to JsonObject(snapshot + ("steps" to JsonArray(steps)))))
            assertEquals(ContractValidationResult.Valid, CanonicalBodyValidator.bundled().validateSchema("Plan", large.toString().encodeToByteArray()))
            plan.string("id") to large
        }
        val old = f.store.records.getValue(ProgressServiceLedger.KEY)
        f.store.records[ProgressServiceLedger.KEY] = PrivateRecord(old.revision, old.schemaVersion,
            PrivateBytes(JsonObject(f.state() + ("plans" to JsonObject(largePlans))).toString().encodeToByteArray()))
        for (id in largePlans.keys) assertEquals(201, f.send("saveRecipe", """{"planId":"$id"}""").status)
        val first = f.service.execute(ApiCall("listSavedRecipes")).value()
        assertTrue(first.body!!.copyForCodec().size <= ProgressCatalog.maxResponseBytes)
        assertEquals(1, first.json().getValue("items").jsonArray.size)
        val next = f.service.execute(ApiCall("listSavedRecipes", queryParameters = mapOf("cursor" to listOf(first.json().string("nextCursor"))))).value()
        assertTrue(next.body!!.copyForCodec().size <= ProgressCatalog.maxResponseBytes)
        assertEquals(1, next.json().getValue("items").jsonArray.size); assertEquals(JsonNull, next.json()["nextCursor"])
        assertNotEquals(first.json().getValue("items").jsonArray.single().jsonObject.string("id"), next.json().getValue("items").jsonArray.single().jsonObject.string("id"))
    }
    @Test fun cookbookRetainedIdentityCapacityDenialKeepsAllTombstonesAndOriginalFailureReceipt() = runTest {
        val f = Fixture(); f.open(); val plan = f.send("createPlan", planBody()).json().string("id")
        val tombstones = JsonObject((1..128).associate { uuid() to JsonNull })
        val old = f.store.records.getValue(ProgressServiceLedger.KEY)
        f.store.records[ProgressServiceLedger.KEY] = PrivateRecord(old.revision, old.schemaVersion,
            PrivateBytes(JsonObject(f.state() + ("savedRecipes" to tombstones)).toString().encodeToByteArray()))
        val call = command("saveRecipe", """{"planId":"$plan"}"""); val before = f.state()
        val denied = f.service.execute(call).value(); assertEquals(429, denied.status)
        assertEquals(before.obj("receipts").size + 1, f.state().obj("receipts").size)
        assertEquals(tombstones, f.state().obj("savedRecipes")); assertTrue(f.state().obj("savedLatest").isEmpty())
        assertContentEquals(denied.body!!.copyForCodec(), f.service.execute(call).value().body!!.copyForCodec())
        assertEquals(before.obj("receipts").size + 1, f.state().obj("receipts").size)
        assertEquals(200, f.service.execute(ApiCall("listSavedRecipes")).value().status)
    }

    @Test fun syntheticWithdrawalPersistsWithoutRewritingAnyRecipeCookingOrOriginalReceipt() = runTest {
        val f = Fixture(); f.open(); val plan = f.send("createPlan", planBody()).json().string("id")
        f.send("saveRecipe", """{"planId":"$plan"}""")
        f.send("createCookSession", """{"planId":"$plan","deviceSequence":0}""")
        val before = f.state(); val commits = f.store.commits
        f.service.withdrawSyntheticRecipe(ProgressCatalog.recipeVersionId).value()
        assertEquals(commits + 1, f.store.commits)
        for (field in before.keys - setOf("withdrawnRecipeVersions", "lastMillis")) assertEquals(before[field], f.state()[field])
        assertEquals(1, f.state().obj("withdrawnRecipeVersions").size)
        val withdrawn = f.state(); f.service.close(); f.newService(); f.open()
        assertEquals(withdrawn, f.state()); assertEquals(commits + 1, f.store.commits)
    }
    @Test fun withdrawalDeniesSavedReadsFilteredPagesNewSavesAndExactPositiveSaveReplay() = runTest {
        val f = Fixture(); f.open(); val plan = f.send("createPlan", planBody()).json().string("id")
        val save = command("saveRecipe", """{"planId":"$plan","title":"Secret crunch"}""")
        val id = f.service.execute(save).value().json().string("id")
        f.service.withdrawSyntheticRecipe(ProgressCatalog.recipeVersionId).value()
        val before = f.state(); val commits = f.store.commits
        for (call in listOf(ApiCall("getSavedRecipe", pathParameters = mapOf("savedRecipeId" to id)),
            ApiCall("listSavedRecipes"), ApiCall("listSavedRecipes", queryParameters = mapOf("q" to listOf("not present"))), save)) {
            f.service.execute(call).value().assertRecalled(call.operationId)
        }
        assertEquals(commits, f.store.commits); assertEquals(before, f.state())
        val fresh = command("saveRecipe", """{"planId":"$plan"}""")
        val denial = f.service.execute(fresh).value(); denial.assertRecalled("saveRecipe")
        assertContentEquals(denial.body!!.copyForCodec(), f.service.execute(fresh).value().body!!.copyForCodec())
        assertEquals(before.obj("savedRecipes"), f.state().obj("savedRecipes"))
    }
    @Test fun withdrawalDeniesOldCursorAndOwnedDeleteStillUsesOriginalVersionAndKey() = runTest {
        val f = Fixture(); f.open()
        val saves = (1..2).map { servings -> val plan = f.send("createPlan", planBody(servings)).json().string("id")
            f.send("saveRecipe", """{"planId":"$plan"}""").json().string("id") }
        val query = mapOf("limit" to listOf("1"))
        val cursor = f.service.execute(ApiCall("listSavedRecipes", queryParameters = query)).value().json().string("nextCursor")
        f.service.withdrawSyntheticRecipe(ProgressCatalog.recipeVersionId).value()
        f.service.execute(ApiCall("listSavedRecipes", queryParameters = query + ("cursor" to listOf(cursor)))).value().assertRecalled("listSavedRecipes")
        val before = f.state(); val path = mapOf("savedRecipeId" to saves.first())
        assertEquals(412, f.send("deleteSavedRecipe", null, path, "\"2\"").status)
        val delete = command("deleteSavedRecipe", null, path = path, ifMatch = "\"1\"")
        assertEquals(204, f.service.execute(delete).value().status)
        f.service.close(); f.newService(); f.open(); assertEquals(204, f.service.execute(delete).value().status)
        assertEquals(JsonNull, f.state().obj("savedRecipes")[saves.first()])
        assertEquals(before.obj("savedRecipes")[saves.last()], f.state().obj("savedRecipes")[saves.last()])
        for (field in listOf("plans", "sessions", "withdrawnRecipeVersions", "savedLatest")) assertEquals(before[field], f.state()[field])
        assertEquals(404, f.service.execute(ApiCall("getSavedRecipe", pathParameters = path)).value().status)
    }
    @Test fun withdrawalBlocksFreshAndCachedPlanAndCookingWithoutChangingExistingProgress() = runTest {
        val f = Fixture(); f.open(); val createPlan = command("createPlan", planBody())
        val plan = f.service.execute(createPlan).value().json().string("id")
        val createCook = command("createCookSession", """{"planId":"$plan","deviceSequence":0}""")
        val session = f.service.execute(createCook).value().json().string("id")
        f.service.withdrawSyntheticRecipe(ProgressCatalog.recipeVersionId).value()
        val before = f.state(); val path = mapOf("sessionId" to session)
        val calls = listOf(createPlan, createCook, ApiCall("getPlan", pathParameters = mapOf("planId" to plan)),
            ApiCall("getCookSession", pathParameters = path), command("createPlan", planBody()),
            command("createCookSession", """{"planId":"$plan","deviceSequence":0}"""),
            command("updateCookSession", """{"deviceSequence":1,"currentStepId":"second"}""", path = path, ifMatch = "\"1\""),
            command("completeCookSession", """{"deviceSequence":1,"makeAgain":false}""", path = path))
        for (call in calls) f.service.execute(call).value().assertRecalled(call.operationId)
        assertEquals(before.obj("plans"), f.state().obj("plans")); assertEquals(before.obj("sessions"), f.state().obj("sessions"))
        // A no-match plan has no withdrawn instructions and does not become false recall evidence.
        assertEquals("noMatch", f.send("createPlan", planBody(equipment = false)).json().string("status"))
    }
    @Test fun withdrawalGatesOriginalSuccessfulUpdateAndCompletionReplays() = runTest {
        for (completing in listOf(false, true)) {
            val f = Fixture(); f.open(); val plan = f.send("createPlan", planBody()).json().string("id")
            val session = f.send("createCookSession", """{"planId":"$plan","deviceSequence":0}""").json().string("id")
            val call = if (completing) command("completeCookSession", """{"deviceSequence":1,"makeAgain":false}""", path = mapOf("sessionId" to session))
                else command("updateCookSession", """{"deviceSequence":1,"currentStepId":"second"}""", path = mapOf("sessionId" to session), ifMatch = "\"1\"")
            assertEquals(200, f.service.execute(call).value().status)
            f.service.withdrawSyntheticRecipe(ProgressCatalog.recipeVersionId).value(); val before = f.state(); val commits = f.store.commits
            f.service.execute(call).value().assertRecalled(call.operationId)
            assertEquals(before, f.state()); assertEquals(commits, f.store.commits)
        }
    }
    @Test fun withdrawalUnknownIdentitiesRemainNotFoundAndInvalidDeleteNeverErasesKnownCopy() = runTest {
        val f = Fixture(); f.open(); val plan = f.send("createPlan", planBody()).json().string("id")
        f.send("saveRecipe", """{"planId":"$plan"}"""); f.service.withdrawSyntheticRecipe(ProgressCatalog.recipeVersionId).value()
        val before = f.state()
        for ((operation, field) in listOf("getSavedRecipe" to "savedRecipeId", "getPlan" to "planId", "getCookSession" to "sessionId"))
            assertEquals(404, f.service.execute(ApiCall(operation, pathParameters = mapOf(field to uuid()))).value().status)
        assertEquals(404, f.send("deleteSavedRecipe", null, mapOf("savedRecipeId" to uuid()), "\"1\"").status)
        assertEquals(before.obj("savedRecipes"), f.state().obj("savedRecipes"))
    }
    @Test fun withdrawalRequiresActualAcknowledgementEvenAfterAppliedOrUnappliedFailure() = runTest {
        for (applied in listOf(false, true)) {
            val f = Fixture(); f.open(); f.store.loseAfterCommit = applied; f.store.failBeforeCommit = !applied
            assertEquals(PortResult.Failure(FailureReason.OUTCOME_UNKNOWN), f.service.withdrawSyntheticRecipe(ProgressCatalog.recipeVersionId))
            assertEquals(applied, ProgressCatalog.recipeVersionId in f.state().obj("withdrawnRecipeVersions"))
            f.service.close(); f.newService(); f.open(); f.store.failBeforeCommit = true
            assertEquals(PortResult.Failure(FailureReason.OUTCOME_UNKNOWN), f.service.withdrawSyntheticRecipe(ProgressCatalog.recipeVersionId))
            f.service.withdrawSyntheticRecipe(ProgressCatalog.recipeVersionId).value()
            val firstAt = f.state().obj("withdrawnRecipeVersions")[ProgressCatalog.recipeVersionId]
            val revision = f.store.records.getValue(ProgressServiceLedger.KEY).revision; f.now++
            f.service.withdrawSyntheticRecipe(ProgressCatalog.recipeVersionId).value()
            assertEquals(revision + 1, f.store.records.getValue(ProgressServiceLedger.KEY).revision)
            assertEquals(firstAt, f.state().obj("withdrawnRecipeVersions")[ProgressCatalog.recipeVersionId])
        }
    }
    @Test fun withdrawalStaleClosedForeignVersionAndRollbackNeverMutateLedger() = runTest {
        val f = Fixture(); f.open(); val before = f.state(); val commits = f.store.commits
        assertEquals(PortResult.Failure(FailureReason.INVALID_DATA), f.service.withdrawSyntheticRecipe(uuid()))
        f.current = false; assertEquals(PortResult.Failure(FailureReason.STALE_SESSION), f.service.withdrawSyntheticRecipe(ProgressCatalog.recipeVersionId))
        f.current = true; f.now--; assertEquals(PortResult.Failure(FailureReason.CONFLICT), f.service.withdrawSyntheticRecipe(ProgressCatalog.recipeVersionId))
        f.now++; f.service.close(); assertEquals(PortResult.Failure(FailureReason.NOT_CONFIGURED), f.service.withdrawSyntheticRecipe(ProgressCatalog.recipeVersionId))
        assertEquals(before, f.state()); assertEquals(commits, f.store.commits)
    }
    @Test fun withdrawalInvalidationOrCancellationAfterCommitNeverReturnsSuccess() = runTest {
        for (cancelled in listOf(false, true)) {
            val f = Fixture(); f.open(); f.store.afterCommit = { if (cancelled) throw CancellationException("test") else f.current = false }
            if (cancelled) assertFailsWith<CancellationException> { f.service.withdrawSyntheticRecipe(ProgressCatalog.recipeVersionId) }
            else assertEquals(PortResult.Failure(FailureReason.STALE_SESSION), f.service.withdrawSyntheticRecipe(ProgressCatalog.recipeVersionId))
            assertTrue(ProgressCatalog.recipeVersionId in f.state().obj("withdrawnRecipeVersions"))
        }
    }
    @Test fun previousCookbookLedgerAddsWithdrawalOnlyWithAcknowledgedCommandAndRetainsOriginalReply() = runTest {
        val f = Fixture(); f.open(); val call = command("createPlan", planBody()); val original = f.service.execute(call).value()
        val record = f.store.records.getValue(ProgressServiceLedger.KEY)
        f.store.records[ProgressServiceLedger.KEY] = PrivateRecord(record.revision, record.schemaVersion,
            PrivateBytes(JsonObject(f.state() - "withdrawnRecipeVersions").toString().encodeToByteArray()))
        val before = f.state(); val commits = f.store.commits; f.service.close(); f.newService(); f.open()
        assertEquals(200, f.service.execute(ApiCall("listSavedRecipes")).value().status)
        assertEquals(before, f.state()); assertEquals(commits, f.store.commits)
        assertContentEquals(original.body!!.copyForCodec(), f.service.execute(call).value().body!!.copyForCodec())
        assertTrue(f.state().obj("withdrawnRecipeVersions").isEmpty())
    }
    @Test fun corruptWithdrawalStateCannotBeReadMigratedOrReinitialized() = runTest {
        val invalid = listOf<JsonElement>(JsonNull, JsonArray(emptyList()), buildJsonObject { put(uuid(), ProgressCatalog.fixedTime) },
            buildJsonObject { put(ProgressCatalog.recipeVersionId, "not a time") }, buildJsonObject { put(ProgressCatalog.recipeVersionId, 1) },
            buildJsonObject { put(ProgressCatalog.recipeVersionId, "9999-01-01T00:00:00Z") })
        for (field in invalid) {
            val f = Fixture(); f.open(); val record = f.store.records.getValue(ProgressServiceLedger.KEY)
            f.store.records[ProgressServiceLedger.KEY] = PrivateRecord(record.revision, record.schemaVersion,
                PrivateBytes(JsonObject(f.state() + ("withdrawnRecipeVersions" to field)).toString().encodeToByteArray()))
            val commits = f.store.commits; f.service.close(); f.newService()
            assertIs<PortResult.Failure>(f.service.open(allowInitialize = true)); assertEquals(commits, f.store.commits)
        }
    }

    private class Fixture {
        val store = Store(); var now = 1_789_344_000_000L; var current = true; val origin = uuid()
        lateinit var service: ProgressServiceLedger
        init { newService() }
        fun newService() { service = ProgressServiceLedger(store, ProgressIdentity.scope, origin, EpochClock { now }) {
            if (!current) previewFail(FailureReason.STALE_SESSION) } }
        suspend fun open() { service.open(allowInitialize = true).value() }
        fun state() = Json.parseToJsonElement(store.records.getValue(ProgressServiceLedger.KEY).payload.copyForCodec().decodeToString()).jsonObject
        suspend fun send(operation: String, body: String?, path: Map<String,String> = emptyMap(), ifMatch: String? = null) =
            service.execute(command(operation, body, path = path, ifMatch = ifMatch)).value()
    }
    private class Store : PrivateStateStore {
        val records = mutableMapOf<RecordKey, PrivateRecord>(); var commits = 0; var erases = 0
        var loseAfterCommit = false; var failBeforeCommit = false; var afterCommit: (() -> Unit)? = null; var initialRevision = 1L
        override suspend fun read(scope: StorageScope, key: RecordKey) = PortResult.Value(records[key])
        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey,Long?>> {
            commits++
            if (failBeforeCommit) { failBeforeCommit = false; return PortResult.Failure(FailureReason.STORAGE_FAILURE) }
            val put = mutations.single() as StoreMutation.Put
            assertEquals(records[put.key]?.revision, put.expectedRevision)
            val revision = put.expectedRevision?.let { it + 1L } ?: initialRevision
            records[put.key] = PrivateRecord(revision, put.schemaVersion, put.payload); afterCommit?.invoke()
            if (loseAfterCommit) { loseAfterCommit = false; return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
            return PortResult.Value(mapOf(put.key to revision))
        }
        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> { erases++; error("Must not erase borrowed storage") }
    }
    companion object {
        private fun uuid() = UUID.randomUUID().toString()
        private fun timerBody(id: String) = """{"timerId":"$id","stepId":"first","status":"running","durationSeconds":2}"""
        private fun command(operation: String, body: String?, key: String = uuid(), path: Map<String,String> = emptyMap(), ifMatch: String? = null) =
            ApiCall(operation, pathParameters = path, body = body?.let { PrivateBytes(it.encodeToByteArray()) }, idempotencyKey = SecretText(key), ifMatch = ifMatch)
        private fun planBody(servings: Int = 1, equipment: Boolean = true, preferenceVersion: Int = 1) = """{"mode":"auto","preferenceVersion":$preferenceVersion,
            "constraints":{"energy":"assemble","servings":$servings,"ingredientIds":["${ProgressCatalog.ingredientId}"],
            "equipmentIds":${if (equipment) "[\"bowl\"]" else "[]"},"hardExcludedIngredientIds":[],"tasteTags":[]}}"""
        private fun pantryBody() = """{"ingredientId":"${ProgressCatalog.ingredientId}","presence":"available"}"""
        private fun <T> PortResult<T>.value(): T = assertIs<PortResult.Value<T>>(this).value
        private fun ApiReply.json() = Json.parseToJsonElement(checkNotNull(body).copyForCodec().decodeToString()).jsonObject
        private fun ApiReply.assertRecalled(operation: String) {
            assertEquals(409, status); assertEquals("RECIPE_RECALLED", json().string("code")); assertNull(etag)
            assertEquals("application/problem+json", contentType)
            assertEquals(setOf("type", "title", "status", "code", "traceId"), json().keys)
            assertIs<ResponseBindingResult.Accepted>(CanonicalResponseBinder(CanonicalBodyValidator.bundled())
                .bind(operation, status, body!!.copyForCodec(), contentType, traceId))
        }
        private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
        private fun JsonObject.obj(key: String) = getValue(key).jsonObject
    }
}
