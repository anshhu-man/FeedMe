package com.feedme.mealflow.social

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.mealflow.json
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.CLIENT
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.SERVER
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.document
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.draft
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.failure
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.number
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.policy
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.problem
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.response
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.value
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Additional retained-plan acceptance cases over the actual shared composition and queue.
 * The fault fixture is synthetic; encrypted SQLite and HTTP authority are separate gates. */
@OptIn(ExperimentalCoroutinesApi::class)
class PostDraftAcceptanceTest {
    @Test fun matchingCreateAtLaterVersionCannotAcknowledgeTheOriginalCreate() {
        val request = WireDocument.parse(buildJsonObject {
            put("clientDraftId", CLIENT); put("caption", "Original"); put("mediaIds", JsonArray(emptyList()))
            put("audience", postSelfAudience()); put("keepOnPlate", false); put("allowRecipeSaves", false)
        }.toString())
        val original = PostOriginal(number(10), "createPostDraft", CLIENT, 1, request, null, null, 1)
        val adapter = PostDraftAdapter(policy())
        assertNotNull(adapter.receipt(original, response(draft(CLIENT, "Original", "1"), 201).value()))
        assertFails { adapter.receipt(original, response(draft(CLIENT, "Original", "2"), 201).value()) }
        assertFails { adapter.receipt(original, response(draft(CLIENT, "Original", "9007199254740993"), 201).value()) }
    }

    @Test fun textPatchRetainsActualCanonicalAttachmentAndRejectsItsRemovalOrSubstitution() {
        val attachment = buildJsonObject {
            put("recipeVersionId", number(40)); put("planId", number(41))
            put("confirmedChanges", JsonArray(listOf(JsonPrimitive("Used cucumber"))))
            put("reviewStatus", "reviewed"); put("rightsBasis", "catalogRedistributable")
        }
        val baseline = document(draft(CLIENT).json().jsonObject + ("attachment" to attachment))
        val original = PostOriginal(number(10), "updatePostDraft", CLIENT, 1,
            WireDocument.parse("{\"caption\":\"Next\"}"), baseline, "\"1\"", 1)
        val next = document(baseline.json().jsonObject + mapOf("caption" to JsonPrimitive("Next"), "version" to JsonPrimitive(2)))
        val adapter = PostDraftAdapter(policy())
        assertEquals(attachment, adapter.receipt(original, response(next).value())!!.document.json().jsonObject["attachment"])
        assertFails { adapter.receipt(original, response(document(next.json().jsonObject - "attachment")).value()) }
        val changed = JsonObject(attachment + ("recipeVersionId" to JsonPrimitive(number(42))))
        assertFails { adapter.receipt(original, response(document(next.json().jsonObject + ("attachment" to changed))).value()) }
    }

    @Test fun suspendedListAndGetCannotPublishAfterBackOrLeaseInvalidation() = runTest {
        for (operation in listOf("list", "get")) for (change in listOf("back", "invalidate")) fixture { f ->
            val local = f.controller.newLocalDraft("Private local text").value().selected!!
            f.remote = draft(local.clientDraftId)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.handler = { call -> val reply = f.reply(call); entered.complete(Unit)
                withContext(NonCancellable) { release.await() }; reply }
            val read = async { if (operation == "list") f.controller.listRemote() else f.controller.refreshRemote(SERVER) }
            entered.await()
            if (change == "back") f.controller.back().value() else f.boundary.clear()
            release.complete(Unit); failure(read.await(), FailureReason.STALE_SESSION)
            val state = f.controller.states.value
            assertNull(state.selected); assertTrue(state.remoteItems.isEmpty()); assertFalse(state.serverAcknowledged)
            if (change == "invalidate") { assertTrue(state.localDrafts.isEmpty()); assertEquals(PostDraftPhase.UNAVAILABLE, state.phase) }
            else assertEquals("Private local text", state.localDrafts.single().caption)
            assertEquals(1, f.calls.size); assertNull(f.record().locals.single().server)
        }
    }

    @Test fun suspendedListAndGetCannotResurrectAConfirmedLocalRemoval() = runTest {
        for (operation in listOf("list", "get")) fixture { f ->
            val local = f.controller.newLocalDraft("Remove only this revision").value().selected!!
            f.remote = draft(local.clientDraftId)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.handler = { call -> val reply = f.reply(call); entered.complete(Unit)
                withContext(NonCancellable) { release.await() }; reply }
            val read = async { if (operation == "list") f.controller.listRemote() else f.controller.refreshRemote(SERVER) }
            entered.await()
            val remove = async { f.controller.discardLocal(local.clientDraftId, local.localRevision) }; runCurrent()
            release.complete(Unit); failure(read.await(), FailureReason.STALE_SESSION); remove.await().value()
            assertTrue(f.controller.states.value.localDrafts.isEmpty()); assertTrue(f.controller.states.value.remoteItems.isEmpty())
            assertEquals(local.clientDraftId, f.record().tombstones.single().clientId)
            f.handler = { f.reply(it) }
            failure(if (operation == "list") f.controller.listRemote() else f.controller.refreshRemote(SERVER), FailureReason.CONFLICT)
            assertTrue(f.record().locals.isEmpty()); assertFalse(f.controller.states.value.serverAcknowledged)
        }
    }

    @Test fun suspendedGetCannotReplaceTextEditedAfterTheReadStarted() = runTest { fixture { f ->
        val local = f.controller.newLocalDraft("Before").value().selected!!; f.remote = draft(local.clientDraftId, "Remote old text")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.handler = { call -> val reply = f.reply(call); entered.complete(Unit)
            withContext(NonCancellable) { release.await() }; reply }
        val read = async { f.controller.refreshRemote(SERVER) }; entered.await()
        val edit = async { f.controller.editText("Newer local text") }; runCurrent()
        release.complete(Unit); failure(read.await(), FailureReason.STALE_SESSION); edit.await().value()
        val state = f.controller.states.value
        assertEquals("Newer local text", state.selected!!.caption); assertNull(state.selected!!.server)
        assertEquals("Newer local text", f.record().locals.single().caption); assertFalse(state.serverAcknowledged)
    } }

    @Test fun oldPositiveGetCannotReappearAfterANewerAuthoritativeNegativeRead() = runTest { fixture { f ->
        f.remote = draft(CLIENT); f.controller.refreshRemote(SERVER).value()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var first = true
        f.handler = { call -> if (first) { first = false; val reply = f.reply(call); entered.complete(Unit)
            withContext(NonCancellable) { release.await() }; reply } else problem(410, "DRAFT_EXPIRED") }
        val old = async { f.controller.refreshRemote() }; entered.await()
        val denied = async { f.controller.refreshRemote() }; runCurrent(); release.complete(Unit)
        failure(old.await(), FailureReason.STALE_SESSION); failure(denied.await(), FailureReason.NOT_FOUND)
        assertNull(f.controller.states.value.selected!!.server); assertTrue(f.controller.states.value.remoteItems.isEmpty())
        f.reopen(); f.controller.restoreLocal().value(); f.controller.openLocal(CLIENT).value()
        assertNull(f.controller.states.value.selected!!.server); assertTrue(f.record().tombstones.isEmpty())
    } }

    @Test fun delayedDuplicateSaveCannotAllocateASecondCommandOrTurnCreateIntoUpdate() = runTest { fixture { f ->
        f.controller.newLocalDraft("One logical save").value()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.handler = { call -> val reply = f.reply(call); entered.complete(Unit)
            withContext(NonCancellable) { release.await() }; reply }
        val first = async { f.controller.saveExplicitly() }; entered.await()
        val duplicate = async { f.controller.saveExplicitly() }; runCurrent(); release.complete(Unit)
        first.await(); duplicate.await()
        assertEquals(2, f.idCalls, "One client identity and one command identity")
        assertEquals(listOf("createPostDraft"), f.calls.map { it.operationId })
        assertEquals(2, f.record().issued.size)
    } }

    @Test fun mutation410KeepsOriginalPatchKeyBaseAndNewerTextWithoutAutomaticRetry() = runTest { fixture { f ->
        f.controller.newLocalDraft("Base").value(); f.controller.saveExplicitly().value(); f.controller.editText("Submitted").value()
        f.handler = { problem(410, "DRAFT_EXPIRED") }; f.controller.saveExplicitly().value()
        val original = f.record().command!!; val calls = f.calls.size; val ids = f.idCalls
        f.controller.editText("Newer unsent text").value(); f.time += 60_000
        f.controller.retryOriginal().value(); failure(f.controller.discardUnsent(), FailureReason.CONFLICT)
        assertOriginal(original, f.record().command!!); assertEquals(calls, f.calls.size); assertEquals(ids, f.idCalls)
        assertEquals("Newer unsent text", f.controller.states.value.selected!!.caption)
        assertNotNull(f.controller.states.value.pending); assertFalse(f.controller.states.value.serverAcknowledged)
    } }

    @Test fun clockRollbackCannotRewriteOrDispatchOriginalAndExplicitRecoveryKeepsExactIfMatch() = runTest { fixture { f ->
        f.controller.newLocalDraft("Base").value(); f.controller.saveExplicitly().value(); f.controller.editText("Original patch").value()
        f.store.failAfter = { changes -> changes.size > 1 && changes.any { it.key == PostDraftControllerTest.KEY } }
        failure(f.controller.saveExplicitly(), FailureReason.OUTCOME_UNKNOWN)
        val original = f.record().command!!; val calls = f.calls.size; val ids = f.idCalls; val writes = f.store.writes
        f.time = original.created - 1
        failure(f.controller.retryOriginal(), FailureReason.CONFLICT)
        assertOriginal(original, f.record().command!!); assertEquals(calls, f.calls.size); assertEquals(ids, f.idCalls); assertEquals(writes, f.store.writes)
        f.time = original.created; f.controller.retryOriginal().value()
        val call = f.calls.last(); assertEquals(original.id, call.idempotencyKey!!.use { it })
        assertEquals(original.etag, call.ifMatch); assertEquals(mapOf("draftId" to original.serverId!!), call.pathParameters)
        assertContentEquals(original.body!!.encodeUtf8(), call.body!!.copyForCodec()); assertEquals(ids, f.idCalls)
    } }

    @Test fun observationItemCapacitySurvivesRefreshAndReplacementWithoutEvictingHistory() = runTest {
        fixture(PostDraftClientPolicy(8, 1_048_576, 262_144, 64, 64, 1, 1, 60_000)) { f ->
            f.remote = draft(CLIENT); f.controller.listRemote().value(); f.reopen()
            f.remote = document(draft(number(50)).json().jsonObject + ("id" to JsonPrimitive(number(51))))
            failure(f.controller.listRemote(), FailureReason.UNAVAILABLE)
            assertEquals(listOf(SERVER), PostDraftHeld.observations(f.access, f.boundary).map { postString(it, "id") })
            f.remote = draft(CLIENT, "Changed at same version"); failure(f.controller.listRemote(), FailureReason.CONFLICT)
            assertEquals(0, f.store.writes); assertEquals(0, f.idCalls)
        }
    }

    @Test fun observationByteCapacityRejectsTheWholeNewObservationWithoutForgettingPriorIdentity() = runTest {
        fixture(PostDraftClientPolicy(8, 65_536, 262_144, 64, 64, 1, 100, 60_000)) { f ->
            val first = document(draft(CLIENT).json().jsonObject + ("saveDisclosureVersion" to JsonPrimitive("a".repeat(34_000))))
            f.remote = first; f.controller.listRemote().value()
            f.remote = document(draft(number(50)).json().jsonObject + mapOf("id" to JsonPrimitive(number(51)),
                "saveDisclosureVersion" to JsonPrimitive("b".repeat(34_000))))
            failure(f.controller.listRemote(), FailureReason.UNAVAILABLE)
            assertContentEquals(first.encodeUtf8(), PostDraftHeld.observations(f.access, f.boundary).single().encodeUtf8())
            assertTrue(f.controller.states.value.remoteItems.isEmpty()); assertEquals(0, f.store.writes); assertEquals(0, f.idCalls)
        }
    }

    @Test fun issuedIdentityCapacityRefusesNewDraftAndServerCommandWithLocalSlotsStillAvailable() = runTest {
        fixture(policy(local = 8, issued = 2)) { f ->
            f.controller.newLocalDraft("First").value(); f.controller.newLocalDraft("Second").value()
            val ids = f.idCalls
            failure(f.controller.newLocalDraft("No third identity"), FailureReason.UNAVAILABLE)
            failure(f.controller.saveExplicitly(), FailureReason.UNAVAILABLE)
            assertEquals(ids, f.idCalls); assertEquals(listOf("First", "Second"), f.record().locals.map { it.caption })
            assertEquals(2, f.record().issued.size); assertNull(f.record().command); assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun fullTerminalHistoryCannotEvictMarkerToRemoveAnotherLocalOrPrepareServerDiscard() = runTest {
        fixture(PostDraftClientPolicy(8, 1_048_576, 262_144, 64, 1, 2, 100, 60_000)) { f ->
            val first = f.controller.newLocalDraft("First").value().selected!!
            f.controller.discardLocal(first.clientDraftId, first.localRevision).value()
            val second = f.controller.newLocalDraft("Second remains").value().selected!!; val ids = f.idCalls
            failure(f.controller.discardLocal(second.clientDraftId, second.localRevision), FailureReason.CONFLICT)
            assertEquals(listOf(first.clientDraftId), f.record().tombstones.map { it.clientId })
            assertEquals("Second remains", f.record().locals.single().caption); assertEquals(ids, f.idCalls)
            f.remote = draft(CLIENT); f.controller.refreshRemote(SERVER).value(); val calls = f.calls.size
            failure(f.controller.prepareServerDiscard(), FailureReason.UNAVAILABLE)
            assertNull(f.controller.states.value.discardConfirmation); assertEquals(ids, f.idCalls); assertEquals(calls, f.calls.size)
            assertEquals(listOf(first.clientDraftId), f.record().tombstones.map { it.clientId })
        }
    }

    @Test fun confirmedServerDiscardCannotBeResurrectedByLaterListOrGetOrRecycledClientId() = runTest { fixture { f ->
        f.remote = draft(CLIENT); f.controller.refreshRemote(SERVER).value()
        val ticket = f.controller.prepareServerDiscard().value().discardConfirmation!!
        f.controller.confirmServerDiscard(ticket).value(); val terminal = f.record().tombstones.single()
        assertEquals(CLIENT, terminal.clientId); assertEquals(SERVER, terminal.serverId); assertNotNull(terminal.commandId)
        // The synthetic service intentionally returns the old object after the acknowledged delete.
        failure(f.controller.listRemote(), FailureReason.CONFLICT)
        failure(f.controller.refreshRemote(SERVER), FailureReason.CONFLICT)
        f.nextId = CLIENT; failure(f.controller.newLocalDraft("No recycling"), FailureReason.CONFLICT)
        assertTrue(f.record().locals.isEmpty()); assertEquals(terminal, f.record().tombstones.single())
        assertEquals(1, f.calls.count { it.operationId == "deletePostDraft" })
    } }

    private fun assertOriginal(before: PostOriginal, after: PostOriginal) {
        assertEquals(before.id, after.id); assertEquals(before.operation, after.operation); assertEquals(before.clientId, after.clientId)
        assertEquals(before.localRevision, after.localRevision); assertEquals(before.serverId, after.serverId)
        assertEquals(before.etag, after.etag); assertEquals(before.created, after.created)
        assertContentEquals(before.body!!.encodeUtf8(), after.body!!.encodeUtf8())
        assertContentEquals(before.baseline!!.encodeUtf8(), after.baseline!!.encodeUtf8())
    }
    private suspend fun TestScope.fixture(policy: PostDraftClientPolicy = policy(), block: suspend (PostDraftControllerTest.Fixture) -> Unit) {
        val f = PostDraftControllerTest.Fixture(this, ActorKind.ACCOUNT, policy)
        try { block(f) } finally { f.controller.close(); f.boundary.clear() }
    }
}
