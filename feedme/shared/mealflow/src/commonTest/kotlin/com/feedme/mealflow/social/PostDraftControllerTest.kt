package com.feedme.mealflow.social

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.mealflow.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.coroutines.CoroutineContext

/** Synthetic bounded service/store faults over the ACTUAL composition and shared durable queue.
 * These common tests do not claim encrypted SQLite, live account authority or native durability. */
@OptIn(ExperimentalCoroutinesApi::class)
class PostDraftControllerTest {
    @Test fun cachedBackDuringAndAfterTransportCannotDescribeAnUnobservedDispatchAsUnsent() = runTest { fixture { f ->
        f.controller.newLocalDraft("Retain this caption").value()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.handler = { call -> entered.complete(Unit); withContext(NonCancellable) { release.await() }; f.reply(call) }
        val save = async { f.controller.saveExplicitly() }; entered.await(); assertEquals(1, f.calls.size)
        val beforeReads = f.store.reads; val beforeWrites = f.store.writes
        val back = f.controller.back().value(); val pending = back.pending!!
        assertEquals("DISPATCH_UNOBSERVED", pending.phase); assertEquals(-1, pending.attempts)
        assertFalse(pending.canDiscardUnsent); assertTrue(pending.canRetry); assertFalse(back.serverAcknowledged)
        assertEquals(beforeReads, f.store.reads); assertEquals(beforeWrites, f.store.writes)
        release.complete(Unit); failure(save.await(), FailureReason.STALE_SESSION)
        val readsAfter = f.store.reads; val writesAfter = f.store.writes
        val after = f.controller.back().value()
        assertEquals(pending.commandId, after.pending!!.commandId); assertEquals(-1, after.pending!!.attempts)
        assertFalse(after.pending!!.canDiscardUnsent); assertTrue(after.pending!!.canRetry)
        assertEquals(readsAfter, f.store.reads); assertEquals(writesAfter, f.store.writes)
        val restored = f.controller.restoreLocal().value()
        assertEquals(1, restored.pending!!.attempts); assertFalse(restored.pending!!.canDiscardUnsent)
        assertNotEquals("DISPATCH_UNOBSERVED", restored.pending!!.phase); assertEquals(1, f.calls.size)
        failure(f.controller.discardUnsent(), FailureReason.CONFLICT)
        f.time = assertNotNull(restored.earliestRetryAtMillis) + 1
        assertTrue(f.controller.retryOriginal().value().serverAcknowledged)
        assertEquals(pending.commandId, f.calls.last().idempotencyKey!!.use { it }); assertEquals(2, f.calls.size)
    } }
    @Test fun dispatchMaskSurvivesNestedExecutionHookObservingZeroAttemptsBeforeRegistration() = runTest { fixture { f ->
        f.controller.newLocalDraft("Exact original").value(); var zeroAttemptReads = 0
        f.store.afterRead = { key, record ->
            if (key.collection == "feedme.command.metadata" && record != null &&
                f.controller.states.value.pending?.phase == "DISPATCH_UNOBSERVED") {
                val body = WireDocument.decode(record.payload.copyForCodec()).json().jsonObject
                if (body["phase"] == JsonPrimitive("AWAITING_CONFIRMATION")) zeroAttemptReads++
            }
        }
        f.handler = { call ->
            assertTrue(zeroAttemptReads > 0, "Actual nested queue reads must exercise the pre-attempt view")
            val pending = f.controller.states.value.pending!!
            assertEquals("DISPATCH_UNOBSERVED", pending.phase); assertEquals(-1, pending.attempts)
            assertFalse(pending.canDiscardUnsent); assertTrue(pending.canRetry); f.reply(call)
        }
        assertTrue(f.controller.saveExplicitly().value().serverAcknowledged)
        assertNull(f.controller.states.value.pending); assertEquals(1, f.calls.size)
    } }
    @Test fun invalidCaptionAndAltTextPublishScopedFeedbackWithoutChangingRetainedTextOrAcknowledgement() = runTest {
        for (field in listOf("caption", "alt")) fixture { f ->
            val original = f.controller.newLocalDraft("Visible valid caption", "Visible valid alt").value().selected!!
            val reads = f.store.reads; val writes = f.store.writes; val ids = f.idCalls
            val invalid = if (field == "caption") f.controller.editCaption(original.clientDraftId, "🌱".repeat(501))
                else f.controller.editAltText(original.clientDraftId, "🌱".repeat(501))
            failure(invalid, FailureReason.INVALID_DATA)
            val state = f.controller.states.value
            assertEquals(PostDraftPhase.ERROR, state.phase); assertEquals(PostDraftIssue.INVALID_INPUT, state.issue)
            assertEquals(FailureReason.INVALID_DATA, state.failureReason)
            assertEquals(original.clientDraftId, state.selected!!.clientDraftId); assertEquals(original.localRevision, state.selected!!.localRevision)
            assertEquals(original.caption, state.selected!!.caption); assertEquals(original.altText, state.selected!!.altText)
            assertTrue(state.selected!!.localAcknowledged); assertEquals(reads, f.store.reads); assertEquals(writes, f.store.writes)
            assertEquals(ids, f.idCalls); assertTrue(f.calls.isEmpty())
            val corrected = if (field == "caption") f.controller.editCaption(original.clientDraftId, "🌱".repeat(500)).value()
                else f.controller.editAltText(original.clientDraftId, "🌱".repeat(500)).value()
            assertEquals(PostDraftIssue.NONE, corrected.issue); assertNull(corrected.failureReason)
            assertTrue(corrected.selected!!.localAcknowledged); assertEquals(original.localRevision + 1, corrected.selected!!.localRevision)
            assertEquals(ids, f.idCalls); assertTrue(f.calls.isEmpty())
        }
    }
    @Test fun invalidEditFeedbackPreservesOriginalPendingCommandAndDoesNotSendOrRetainThePaste() = runTest { fixture { f ->
        val local = f.controller.newLocalDraft("Submitted text", "Original alt").value().selected!!
        f.handler = { PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
        f.controller.saveExplicitly().value(); val command = f.record().command!!; val pending = f.controller.states.value.pending!!
        val reads = f.store.reads; val writes = f.store.writes; val ids = f.idCalls
        failure(f.controller.editCaption(local.clientDraftId, "\uD800"), FailureReason.INVALID_DATA)
        val state = f.controller.states.value
        assertEquals(PostDraftIssue.INVALID_INPUT, state.issue); assertPendingFields(pending, state.pending!!)
        assertEquals(local.localRevision, state.selected!!.localRevision); assertEquals("Submitted text", state.selected!!.caption)
        assertTrue(state.selected!!.localAcknowledged); assertFalse(state.serverAcknowledged)
        assertEquals(reads, f.store.reads); assertEquals(writes, f.store.writes); assertEquals(ids, f.idCalls)
        assertOriginalFields(command, f.record().command!!); assertEquals(1, f.calls.size)
    } }
    @Test fun malformedCanonicalGetReportsUnverifiedDataWithoutChangingTextOrOriginalIntent() = runTest { fixture { f ->
        val local = f.controller.newLocalDraft("Original caption", "Original description").value().selected!!
        f.handler = { call -> f.reply(call); PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
        f.controller.saveExplicitly().value()
        val original = f.record().command!!; val pending = f.controller.states.value.pending!!
        val retained = f.store.records.getValue(KEY); val writes = f.store.writes; val ids = f.idCalls
        f.handler = { response(WireDocument.parse("{}"), etag = "\"1\"") }
        failure(f.controller.refreshRemote(SERVER), FailureReason.INVALID_DATA)
        val state = f.controller.states.value
        assertEquals(PostDraftIssue.DATA_UNVERIFIED, state.issue); assertEquals(FailureReason.INVALID_DATA, state.failureReason)
        assertNotEquals(PostDraftIssue.INVALID_INPUT, state.issue); assertEquals(PostDraftPhase.ERROR, state.phase)
        assertEquals(local.caption, state.selected!!.caption); assertEquals(local.altText, state.selected!!.altText)
        assertEquals(local.localRevision, state.selected!!.localRevision); assertTrue(state.selected!!.localAcknowledged)
        assertFalse(state.serverAcknowledged); assertPendingFields(pending, state.pending!!); assertOriginalFields(original, f.record().command!!)
        assertContentEquals(retained.payload.copyForCodec(), f.store.records.getValue(KEY).payload.copyForCodec())
        assertEquals(writes, f.store.writes); assertEquals(ids, f.idCalls)
        assertEquals(listOf("createPostDraft", "getPostDraft"), f.calls.map { it.operationId })
        f.handler = { f.reply(it) }
        val observed = f.controller.refreshRemote(SERVER).value()
        assertEquals(PostDraftIssue.ORIGINAL_PENDING, observed.issue); assertFalse(observed.serverAcknowledged)
        assertOriginalFields(original, f.record().command!!); assertEquals(3, f.calls.size)
    } }
    @Test fun malformedRetainedCanonicalReceiptReportsUnverifiedDataAndCannotReplaceOriginalCommand() = runTest { fixture { f ->
        val local = f.controller.newLocalDraft("Submitted caption", "Submitted description").value().selected!!
        f.store.failAfter = { changes -> changes.any { it.key.collection == "feedme.command.receipt" } }
        failure(f.controller.saveExplicitly(), FailureReason.OUTCOME_UNKNOWN)
        val original = f.record().command!!; val key = RecordKey("feedme.command.receipt", original.id)
        val valid = f.store.records.getValue(key)
        f.store.records[key] = valid.copy(payload = PrivateBytes("{}".encodeToByteArray()))
        val writes = f.store.writes; val ids = f.idCalls
        failure(f.controller.retryOriginal(), FailureReason.INVALID_DATA)
        val state = f.controller.states.value
        assertEquals(PostDraftIssue.DATA_UNVERIFIED, state.issue); assertNotEquals(PostDraftIssue.INVALID_INPUT, state.issue)
        assertEquals(FailureReason.INVALID_DATA, state.failureReason); assertFalse(state.serverAcknowledged)
        assertEquals(local.caption, state.selected!!.caption); assertEquals(local.altText, state.selected!!.altText)
        assertEquals(local.localRevision, state.selected!!.localRevision); assertTrue(state.selected!!.localAcknowledged)
        assertEquals(original.id, state.pending!!.commandId); assertOriginalFields(original, f.record().command!!)
        assertEquals(writes, f.store.writes); assertEquals(ids, f.idCalls); assertEquals(1, f.calls.size)
        assertContentEquals("{}".encodeToByteArray(), f.store.records.getValue(key).payload.copyForCodec())
        // Test-only removal of the injected corruption restores the actual original receipt.
        f.store.records[key] = valid
        val recovered = f.controller.retryOriginal().value()
        assertTrue(recovered.serverAcknowledged); assertEquals(PostDraftIssue.NONE, recovered.issue); assertNull(recovered.pending)
        assertEquals(1, f.calls.size)
    } }
    @Test fun malformedStoredDomainReportsUnverifiedDataWithoutRepairingTextOrAllocatingAnotherIntent() = runTest { fixture { f ->
        val local = f.controller.newLocalDraft("Retained caption", "Retained description").value().selected!!
        f.handler = { PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }; f.controller.saveExplicitly().value()
        val original = f.record().command!!; val pending = f.controller.states.value.pending!!
        val valid = f.store.records.getValue(KEY)
        val body = WireDocument.decode(valid.payload.copyForCodec()).json().jsonObject
        val damaged = valid.copy(payload = PrivateBytes(JsonObject(body + ("origin" to JsonPrimitive(SERVER))).toString().encodeToByteArray()))
        f.store.records[KEY] = damaged
        val writes = f.store.writes; val ids = f.idCalls
        failure(f.controller.retryOriginal(), FailureReason.INVALID_DATA)
        val state = f.controller.states.value
        assertEquals(PostDraftIssue.DATA_UNVERIFIED, state.issue); assertNotEquals(PostDraftIssue.INVALID_INPUT, state.issue)
        assertEquals(FailureReason.INVALID_DATA, state.failureReason); assertFalse(state.serverAcknowledged)
        assertEquals(local.caption, state.selected!!.caption); assertEquals(local.altText, state.selected!!.altText)
        assertEquals(local.localRevision, state.selected!!.localRevision); assertTrue(state.selected!!.localAcknowledged)
        assertPendingFields(pending, state.pending!!); assertEquals(writes, f.store.writes); assertEquals(ids, f.idCalls)
        assertEquals(1, f.calls.size); assertContentEquals(damaged.payload.copyForCodec(), f.store.records.getValue(KEY).payload.copyForCodec())
        // The controller did not rewrite corrupt storage. Restore only this synthetic fault.
        f.store.records[KEY] = valid
        assertOriginalFields(original, f.record().command!!)
        val restored = f.controller.restoreLocal().value()
        assertEquals(PostDraftIssue.ORIGINAL_PENDING, restored.issue); assertFalse(restored.serverAcknowledged)
        assertPendingFields(pending, restored.pending!!); assertEquals(1, f.calls.size); assertEquals(writes, f.store.writes)
    } }
    @Test fun staleClientAndCancelledQueuedInvalidEditsNeverPublishFeedbackOverTheCurrentDraft() = runTest { fixture { f ->
        val first = f.controller.newLocalDraft("First").value().selected!!
        val current = f.controller.newLocalDraft("Second", "Second alt").value().selected!!
        val state = f.controller.states.value; val reads = f.store.reads; val writes = f.store.writes; val ids = f.idCalls
        for (stale in listOf(first.clientDraftId, "not-a-client-id")) {
            failure(f.controller.editCaption(stale, "🌱".repeat(501)), FailureReason.CONFLICT)
            failure(f.controller.editAltText(stale, "\uD800"), FailureReason.CONFLICT)
            assertSame(state, f.controller.states.value)
        }
        val cancelled = async { f.controller.editCaption(current.clientDraftId, "🌱".repeat(501)) }
        cancelled.cancel(); assertFailsWith<CancellationException> { cancelled.await() }; runCurrent()
        assertSame(state, f.controller.states.value); assertEquals(reads, f.store.reads); assertEquals(writes, f.store.writes)
        assertEquals(ids, f.idCalls); assertTrue(f.calls.isEmpty())
    } }
    @Test fun rejectedPasteOutlivesEarlierSuspendedEditButCannotHideItsLaterPersistenceFailure() = runTest {
        for (fails in listOf(false, true)) fixture { f ->
            val local = f.controller.newLocalDraft("Before", "Original alt").value().selected!!
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.store.afterCommit = { changes -> if (changes.any { it.key == KEY }) { entered.complete(Unit); release.await() } }
            if (fails) f.store.failAfter = { changes -> changes.any { it.key == KEY } }
            val edit = async { f.controller.editCaption(local.clientDraftId, "Earlier valid edit") }
            try {
            entered.await(); val revision = f.controller.states.value.selected!!.localRevision
            failure(f.controller.editCaption(local.clientDraftId, "🌱".repeat(501)), FailureReason.INVALID_DATA)
            assertEquals(PostDraftIssue.INVALID_INPUT, f.controller.states.value.issue)
            assertFalse(f.controller.states.value.selected!!.localAcknowledged)
            f.store.afterCommit = {}; release.complete(Unit)
            if (fails) {
                failure(edit.await(), FailureReason.OUTCOME_UNKNOWN)
                assertEquals(PostDraftIssue.RECONCILIATION_REQUIRED, f.controller.states.value.issue)
                assertFalse(f.controller.states.value.selected!!.localAcknowledged)
            } else {
                val finished = edit.await().value()
                assertEquals(PostDraftIssue.INVALID_INPUT, finished.issue); assertEquals(PostDraftPhase.ERROR, finished.phase)
                assertEquals(FailureReason.INVALID_DATA, finished.failureReason); assertTrue(finished.selected!!.localAcknowledged)
                assertEquals(PostDraftIssue.INVALID_INPUT, f.controller.states.value.issue)
            }
            assertEquals("Earlier valid edit", f.controller.states.value.selected!!.caption)
            assertEquals("Original alt", f.controller.states.value.selected!!.altText)
            assertEquals(revision, f.controller.states.value.selected!!.localRevision); assertEquals(1, f.idCalls); assertTrue(f.calls.isEmpty())
            assertEquals(PostDraftIssue.NONE, f.controller.editCaption(local.clientDraftId, "Later valid edit").value().issue)
            } finally {
                f.store.afterCommit = {}; release.complete(Unit); edit.cancelAndJoin()
            }
        }
    }
    @Test fun rejectedPasteAfterArmedLocalDeliveryPreservesFeedbackAndExactAcknowledgement() = runTest {
        for (cancelBeforeTail in listOf(false, true)) fixture { f ->
        val local = f.controller.newLocalDraft("Before", "Original alt").value().selected!!
        val scheduled = StandardTestDispatcher(testScheduler); val held = ArrayDeque<Pair<CoroutineContext, Runnable>>(); var hold = false
        val caller = object : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (hold) held.addLast(context to block) else scheduled.dispatch(context, block)
        } }
        val task = async(caller) { hold = true; f.controller.editCaption(local.clientDraftId, "Earlier valid edit") }
        try {
        runCurrent()
        repeat(2) { val next = held.removeFirst(); scheduled.dispatch(next.first, next.second); runCurrent() }
        assertEquals(1, held.size); val edit = PostDraftHeld.edit(f.access, f.boundary)!!
        assertFalse(edit.delivery?.delivered(edit) == true)
        failure(f.controller.editCaption(local.clientDraftId, "🌱".repeat(501)), FailureReason.INVALID_DATA)
        failure(f.controller.editAltText(local.clientDraftId, "\uD800"), FailureReason.INVALID_DATA)
        assertEquals(PostDraftIssue.INVALID_INPUT, f.controller.states.value.issue)
        assertFalse(f.controller.states.value.selected!!.localAcknowledged)
        if (cancelBeforeTail) task.cancel()
        hold = false; while (held.isNotEmpty()) { val (context, block) = held.removeFirst(); scheduled.dispatch(context, block) }
        val finished = if (cancelBeforeTail) {
            task.join(); assertFailsWith<CancellationException> { task.await() }
            assertFalse(edit.delivery?.delivered(edit) == true)
            f.controller.states.value.also {
                assertFalse(it.selected!!.localAcknowledged); assertFalse(it.serverAcknowledged)
                assertEquals(PostDraftIssue.RECONCILIATION_REQUIRED, it.issue)
            }
        } else task.await().value().also {
            assertEquals(PostDraftIssue.INVALID_INPUT, it.issue); assertEquals(FailureReason.INVALID_DATA, it.failureReason)
            assertEquals(PostDraftPhase.ERROR, it.phase); assertTrue(it.selected!!.localAcknowledged)
            assertTrue(edit.delivery?.delivered(edit) == true); assertEquals(PostDraftIssue.INVALID_INPUT, f.controller.states.value.issue)
        }
        assertEquals("Earlier valid edit", finished.selected!!.caption); assertEquals("Original alt", finished.selected!!.altText)
        assertEquals(local.localRevision + 1, finished.selected!!.localRevision); assertEquals(1, f.idCalls); assertTrue(f.calls.isEmpty())
        assertEquals(PostDraftIssue.NONE, f.controller.editCaption(local.clientDraftId, "Later valid edit").value().issue)
        } finally {
            hold = false
            while (held.isNotEmpty()) { val (context, block) = held.removeFirst(); scheduled.dispatch(context, block) }
            task.cancelAndJoin()
        }
    } }
    @Test fun constructorRestoreAndNavigationNeverSaveOrCreateNativeWork() = runTest { fixture { f ->
        assertTrue(f.calls.isEmpty()); assertEquals(0, f.store.writes)
        f.controller.restoreLocal().value(); f.controller.back().value()
        assertTrue(f.calls.isEmpty()); assertEquals(0, f.idCalls); assertEquals(0, f.store.writes); assertEquals(0, f.store.erases)
    } }
    @Test fun localDraftTextIsAcknowledgedOnlyAfterChangedCasAndActualReadback() = runTest { fixture { f ->
        f.store.nextCreateRevision = 37
        val state = f.controller.newLocalDraft("Dinner 🌱", "Green bowl").value()
        assertEquals("Dinner 🌱", state.selected!!.caption); assertTrue(state.selected!!.localAcknowledged)
        assertEquals(37, f.store.records.getValue(KEY).revision); assertEquals(1, f.idCalls)
        assertNull(state.selected!!.server); assertFalse(state.selected!!.serverAssociated); assertFalse(state.serverAcknowledged); assertTrue(f.calls.isEmpty())
        f.controller.editText("Dinner for two").value()
        assertEquals("Green bowl", f.controller.states.value.selected!!.altText)
        assertEquals(2, f.controller.states.value.selected!!.localRevision)
    } }
    @Test fun explicitCreateIsTextOnlySelfAndDoesNotPublishOrRequireGet() = runTest { fixture { f ->
        val local = f.controller.newLocalDraft("Made this", "The bowl").value().selected!!
        val saved = f.controller.saveExplicitly().value(); val call = f.calls.single()
        assertEquals("createPostDraft", call.operationId); assertNull(call.ifMatch); assertTrue(call.pathParameters.isEmpty())
        assertEquals(local.clientDraftId, call.document().json().jsonObject.getValue("clientDraftId").jsonPrimitive.content)
        assertEquals(postSelfAudience(), call.document().json().jsonObject["audience"])
        assertEquals(JsonArray(emptyList()), call.document().json().jsonObject["mediaIds"])
        assertEquals(JsonPrimitive(false), call.document().json().jsonObject["keepOnPlate"])
        assertTrue(saved.serverAcknowledged); assertNull(saved.pending); assertTrue(saved.selected!!.textMatchesServer)
        assertEquals(2, f.idCalls); assertNotNull(f.record().completion)
        assertTrue(f.store.records.keys.any { it.collection == "feedme.command.metadata" })
    } }
    @Test fun captionOnlyEditPreservesAltTextAndExplicitEmptyStringClearsIt() = runTest { fixture { f ->
        f.controller.newLocalDraft("First", "Original description").value(); f.controller.saveExplicitly().value()
        f.controller.editText("Second").value(); f.controller.saveExplicitly().value()
        val patch = f.calls.last().document().json().jsonObject
        assertEquals(setOf("caption", "altText"), patch.keys); assertEquals("Original description", patch.getValue("altText").jsonPrimitive.content)
        f.controller.editText("Third", "").value(); f.controller.saveExplicitly().value()
        assertEquals("", f.calls.last().document().json().jsonObject.getValue("altText").jsonPrimitive.content)
    } }
    @Test fun offlineSaveRemainsLocalAndReconnectRestoreDoesNotEnqueueOrDispatch() = runTest { fixture { f ->
        f.controller.newLocalDraft("Offline draft").value(); f.online = false
        val count = f.idCalls; val state = f.controller.saveExplicitly().value()
        assertEquals(PostDraftPhase.OFFLINE, state.phase); assertNull(f.record().command)
        f.online = true; f.controller.restoreLocal().value(); assertEquals(count, f.idCalls); assertTrue(f.calls.isEmpty())
    } }
    @Test fun unicodeBoundsRejectInvalidScalarsAndMoreThanFiveHundredWithoutLosingValidText() = runTest { fixture { f ->
        f.controller.newLocalDraft("🌱".repeat(500)).value()
        failure(f.controller.editText("🌱".repeat(501)), FailureReason.INVALID_DATA)
        failure(f.controller.editText("\uD800"), FailureReason.INVALID_DATA)
        assertEquals("🌱".repeat(500), f.controller.states.value.selected!!.caption); assertTrue(f.calls.isEmpty())
    } }
    @Test fun lostLocalAcknowledgementSurvivesSameLeaseReplacementAndCannotBecomeServerSave() = runTest { fixture { f ->
        f.store.failAfter = { it.size == 1 && it.single().key == KEY }
        failure(f.controller.newLocalDraft("Keep my typing"), FailureReason.OUTCOME_UNKNOWN)
        assertFalse(f.controller.states.value.selected!!.localAcknowledged)
        val id = f.controller.states.value.selected!!.clientDraftId
        f.reopen(); f.controller.restoreLocal().value(); f.controller.openLocal(id).value()
        assertEquals("Keep my typing", f.controller.states.value.selected!!.caption)
        assertFalse(f.controller.states.value.selected!!.localAcknowledged)
        failure(f.controller.saveExplicitly(), FailureReason.CONFLICT); assertTrue(f.calls.isEmpty())
        f.controller.editText("Keep my typing").value(); assertTrue(f.controller.states.value.selected!!.localAcknowledged)
        f.controller.saveExplicitly().value(); assertEquals(1, f.calls.size)
    } }
    @Test fun failedCreateBeforeCommitRetainsOriginalClientIdentityAndExplicitEditCanPersistIt() = runTest { fixture { f ->
        f.store.failBefore = true
        failure(f.controller.newLocalDraft("Still here"), FailureReason.STORAGE_FAILURE)
        val id = f.controller.states.value.selected!!.clientDraftId; assertNull(f.store.records[KEY])
        f.reopen(); f.controller.restoreLocal().value(); f.controller.openLocal(id).value()
        f.controller.editText("Still here, now retained").value()
        assertEquals(id, f.record().locals.single().id); assertEquals(1, f.idCalls); assertTrue(f.calls.isEmpty())
    } }
    @Test fun malformedLocalAckNeverUsesVisibleBytesAsAnAcknowledgement() = runTest { fixture { f ->
        f.store.badAck = true
        failure(f.controller.newLocalDraft("Uncertain"), FailureReason.STORAGE_FAILURE)
        assertNotNull(f.store.records[KEY]); assertFalse(f.controller.states.value.selected!!.localAcknowledged)
        f.controller.restoreLocal().value(); assertFalse(f.controller.states.value.localDrafts.single().localAcknowledged)
    } }
    @Test fun lostEnqueueNeverAutomaticallySendsAndRetryUsesOriginalExactBytes() = runTest { fixture { f ->
        f.controller.newLocalDraft("One original").value()
        f.store.failAfter = { it.size > 1 && it.any { m -> m.key == KEY } }
        failure(f.controller.saveExplicitly(), FailureReason.OUTCOME_UNKNOWN)
        val original = f.record().command!!; assertTrue(f.calls.isEmpty())
        f.controller.restoreLocal().value(); assertTrue(f.calls.isEmpty())
        f.controller.retryOriginal().value(); val call = f.calls.single()
        assertEquals(original.id, call.idempotencyKey!!.use { it }); assertContentEquals(original.body!!.encodeUtf8(), call.body!!.copyForCodec())
        assertTrue(f.controller.states.value.serverAcknowledged)
    } }
    @Test fun lostResponseCannotBeDiscardedAsUnsentAndRetryNeverRotatesKey() = runTest { fixture { f ->
        f.controller.newLocalDraft("Original caption").value(); var first = true
        f.handler = { call -> if (first) { first = false; f.reply(call); PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) } else f.reply(call) }
        val state = f.controller.saveExplicitly().value(); assertNotNull(state.pending); assertFalse(state.serverAcknowledged)
        failure(f.controller.discardUnsent(), FailureReason.CONFLICT)
        val original = f.calls.single(); f.time += 60_000; f.controller.retryOriginal().value()
        assertEquals(original.idempotencyKey!!.use { it }, f.calls.last().idempotencyKey!!.use { it })
        assertContentEquals(original.body!!.copyForCodec(), f.calls.last().body!!.copyForCodec()); assertEquals(2, f.idCalls)
    } }
    @Test fun staleIfMatchRetainsOriginalPatchAndNewerTextCannotRebaseIt() = runTest { fixture { f ->
        f.controller.newLocalDraft("Before").value(); f.controller.saveExplicitly().value(); f.controller.editText("Attempted").value()
        f.handler = { problem(412, "VERSION_CONFLICT") }; val state = f.controller.saveExplicitly().value()
        assertNotNull(state.pending); assertFalse(state.serverAcknowledged); val original = f.record().command!!
        f.controller.editText("Newer unsent text").value()
        f.controller.retryOriginal().value()
        assertEquals(original.id, f.record().command!!.id); assertEquals(original.etag, f.record().command!!.etag)
        assertContentEquals(original.body!!.encodeUtf8(), f.record().command!!.body!!.encodeUtf8())
        assertEquals("Newer unsent text", f.controller.states.value.selected!!.caption)
    } }
    @Test fun editWhileSaveSuspendedFencesLateReplyAndOriginalRecoveryPreservesNewerText() = runTest { fixture { f ->
        f.controller.newLocalDraft("Submitted text").value()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var first = true
        f.handler = { call -> val result = f.reply(call)
            if (first) { first = false; entered.complete(Unit); withContext(NonCancellable) { release.await() } }; result }
        val save = async { f.controller.saveExplicitly() }; entered.await()
        val edit = async { f.controller.editText("Newer text") }; runCurrent(); release.complete(Unit)
        failure(save.await(), FailureReason.STALE_SESSION); edit.await().value()
        assertEquals("Newer text", f.controller.states.value.selected!!.caption); assertFalse(f.controller.states.value.serverAcknowledged)
        val recovering = f.controller.retryOriginal().value()
        assertEquals(1, f.calls.size); assertNotNull(recovering.pending); assertNull(recovering.selected!!.server)
        f.time = assertNotNull(recovering.earliestRetryAtMillis) + 1
        f.controller.retryOriginal().value()
        val state = f.controller.states.value; assertEquals("Newer text", state.selected!!.caption)
        assertEquals("Submitted text", postString(state.selected!!.server!!.document, "caption")); assertFalse(state.selected!!.textMatchesServer)
        assertEquals(f.calls.first().idempotencyKey!!.use { it }, f.calls.last().idempotencyKey!!.use { it })
    } }
    @Test fun unknownApplyNeedsActualArchiveProofAndFreshChangedCasWithoutSecondRequest() = runTest { fixture { f ->
        f.controller.newLocalDraft("Retained result").value()
        f.store.failAfter = { changes -> changes.any { it.key == KEY } && changes.any { it is StoreMutation.Delete } }
        failure(f.controller.saveExplicitly(), FailureReason.OUTCOME_UNKNOWN)
        assertNull(f.record().command); assertNotNull(f.record().completion); assertFalse(f.controller.states.value.serverAcknowledged)
        val before = f.store.records.getValue(KEY).revision
        f.reopen(); f.controller.restoreLocal().value(); val result = f.controller.retryOriginal().value()
        assertTrue(result.serverAcknowledged); assertNull(result.pending); assertTrue(f.store.records.getValue(KEY).revision > before)
        assertEquals(1, f.calls.size)
    } }
    @Test fun damagedArchiveCannotBeReplacedByAppliedStatusOrMatchingGet() = runTest { fixture { f ->
        f.controller.newLocalDraft("Private").value()
        f.store.failAfter = { changes -> changes.any { it.key == KEY } && changes.any { it is StoreMutation.Delete } }
        failure(f.controller.saveExplicitly(), FailureReason.OUTCOME_UNKNOWN)
        val proof = PostDraftHeld.apply(f.access, f.boundary)!!; val archive = proof.archive!!.key
        val old = f.store.records.getValue(archive); f.store.records[archive] = old.copy(payload = PrivateBytes("{}".encodeToByteArray()))
        val count = f.store.writes
        assertIs<PortResult.Failure>(f.controller.retryOriginal()); assertFalse(f.controller.states.value.serverAcknowledged)
        failure(f.controller.discardUnsent(), FailureReason.CONFLICT); assertEquals(count, f.store.writes)
    } }
    @Test fun durableCompletionWithLostProcessProofRestoresAsHistoricalReconciliationNotAck() = runTest { fixture { f ->
        f.controller.newLocalDraft("Old result").value(); f.controller.saveExplicitly().value()
        PostDraftHeld.clear(f.access, f.boundary); f.reopen()
        val state = f.controller.restoreLocal().value()
        assertFalse(state.serverAcknowledged); assertTrue(state.historical); assertNotNull(state.pending)
        assertEquals("HISTORICAL_COMPLETION", state.pending!!.phase); assertFalse(state.pending!!.canRetry)
        failure(f.controller.retryOriginal(), FailureReason.CONFLICT); assertEquals(1, f.calls.size)
    } }
    @Test fun cancellationAfterLocalCommitKeepsTextButNoLocalAcknowledgementOnReplacement() = runTest { fixture { f ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.store.afterCommit = { changes -> if (changes.any { it.key == KEY }) { entered.complete(Unit); withContext(NonCancellable) { release.await() } } }
        val task = async { f.controller.newLocalDraft("Cancelled return") }; entered.await(); task.cancel(); release.complete(Unit)
        assertFailsWith<CancellationException> { task.await() }; f.store.afterCommit = {}
        f.reopen(); val state = f.controller.restoreLocal().value()
        assertEquals("Cancelled return", state.localDrafts.single().caption); assertFalse(state.localDrafts.single().localAcknowledged)
        assertFalse(state.serverAcknowledged); assertTrue(f.calls.isEmpty())
    } }
    @Test fun backDuringNetworkCannotReturnAnAcknowledgedOrLateEditorProjection() = runTest { fixture { f ->
        f.controller.newLocalDraft("Back-safe").value()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.handler = { call -> val reply = f.reply(call); entered.complete(Unit); withContext(NonCancellable) { release.await() }; reply }
        val task = async { f.controller.saveExplicitly() }; entered.await(); f.controller.back().value(); release.complete(Unit)
        failure(task.await(), FailureReason.STALE_SESSION); assertEquals(PostDraftScreen.LOCAL_LIST, f.controller.states.value.screen)
        assertNull(f.controller.states.value.selected); assertFalse(f.controller.states.value.serverAcknowledged)
    } }
    @Test fun expiredOwnedDraftCanBeDiscardedFromExactRetainedMetadataWithoutGet() = runTest { fixture { f ->
        f.remote = draft(CLIENT, "Old draft", expires = "1970-01-01T00:00:01Z")
        f.controller.refreshRemote(SERVER).value(); val before = f.calls.size
        val ticket = f.controller.prepareServerDiscard().value().discardConfirmation!!
        val result = f.controller.confirmServerDiscard(ticket).value()
        assertEquals(listOf("deletePostDraft"), f.calls.drop(before).map { it.operationId })
        assertEquals("\"1\"", f.calls.last().ifMatch); assertEquals(mapOf("draftId" to SERVER), f.calls.last().pathParameters)
        assertTrue(result.serverAcknowledged); assertTrue(result.localDrafts.isEmpty()); assertEquals(CLIENT, f.record().tombstones.single().clientId)
        assertEquals(PostDraftScreen.LOCAL_LIST, result.screen); assertNull(result.selected); assertNull(result.discardConfirmation)
    } }
    @Test fun discardedEditorReturnsToLocalListOnlyAfterOriginalLocalFinalizationIsAcknowledged() = runTest { fixture { f ->
        f.remote = draft(CLIENT); f.controller.refreshRemote(SERVER).value()
        val ticket = f.controller.prepareServerDiscard().value().discardConfirmation!!
        // DELETE has no queued request body to remove. Target its actual terminal domain
        // payload plus archive batch, not the body-removal mutation used by Save tests.
        f.store.failAfter = { changes -> changes.size > 1 && changes.filterIsInstance<StoreMutation.Put>().any {
            it.key == KEY && PostDraftCodec(ORIGIN, f.policy).decode(it.payload).completion?.operation == "deletePostDraft"
        } }
        failure(f.controller.confirmServerDiscard(ticket), FailureReason.OUTCOME_UNKNOWN)
        assertEquals(PostDraftScreen.EDITOR, f.controller.states.value.screen)
        assertFalse(f.controller.states.value.serverAcknowledged); assertNotNull(f.record().completion)
        assertTrue(f.record().locals.isEmpty()); val calls = f.calls.size
        f.store.failBefore = true
        failure(f.controller.retryOriginal(), FailureReason.STORAGE_FAILURE)
        assertEquals(PostDraftScreen.EDITOR, f.controller.states.value.screen)
        assertFalse(f.controller.states.value.serverAcknowledged)
        val result = f.controller.retryOriginal().value()
        assertTrue(result.serverAcknowledged); assertNull(result.pending); assertEquals(calls, f.calls.size)
        assertEquals(PostDraftScreen.LOCAL_LIST, result.screen); assertNull(result.selected); assertNull(result.discardConfirmation)
    } }
    @Test fun lateDiscardAndOriginalRetryNeverReopenAnEditorAlreadyLeftWithBack() = runTest { fixture { f ->
        f.remote = draft(CLIENT); f.controller.refreshRemote(SERVER).value()
        val ticket = f.controller.prepareServerDiscard().value().discardConfirmation!!
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var first = true
        f.handler = { call -> val reply = f.reply(call)
            if (first) { first = false; entered.complete(Unit); withContext(NonCancellable) { release.await() } }; reply }
        val deleting = async { f.controller.confirmServerDiscard(ticket) }; entered.await()
        f.controller.back().value(); f.controller.back().value(); release.complete(Unit)
        failure(deleting.await(), FailureReason.STALE_SESSION)
        assertEquals(PostDraftScreen.HIDDEN, f.controller.states.value.screen)
        assertFalse(f.controller.states.value.serverAcknowledged); assertNull(f.controller.states.value.selected)
        val original = f.calls.last(); val recovering = f.controller.retryOriginal().value()
        f.time = assertNotNull(recovering.earliestRetryAtMillis) + 1
        val result = f.controller.retryOriginal().value()
        assertEquals(PostDraftScreen.HIDDEN, result.screen); assertNull(result.selected); assertTrue(result.serverAcknowledged)
        assertEquals(original.idempotencyKey!!.use { it }, f.calls.last().idempotencyKey!!.use { it })
        assertEquals(original.ifMatch, f.calls.last().ifMatch); assertEquals(original.pathParameters, f.calls.last().pathParameters)
        assertNull(f.calls.last().body); assertTrue(result.localDrafts.isEmpty())
    } }
    @Test fun editedDismissedOrExpiredDiscardTicketCannotAllocateACommand() = runTest {
        for (change in listOf("edit", "dismiss", "clock")) fixture { f ->
            f.remote = draft(CLIENT); f.controller.refreshRemote(SERVER).value()
            val ticket = f.controller.prepareServerDiscard().value().discardConfirmation!!; val ids = f.idCalls
            when (change) { "edit" -> f.controller.editText("Changed").value(); "dismiss" -> f.controller.dismissServerDiscard().value(); else -> f.time += 60_001 }
            failure(f.controller.confirmServerDiscard(ticket), FailureReason.CONFLICT)
            assertEquals(ids, f.idCalls); assertEquals(1, f.calls.size)
        }
    }
    @Test fun serverDiscardAndLocalRemovalNeverRecycleClientIdentity() = runTest { fixture { f ->
        val id = f.controller.newLocalDraft("Remove local").value().selected!!.clientDraftId
        f.controller.discardLocal(id).value(); assertEquals(id, f.record().tombstones.single().clientId)
        f.time += 30L * 86_400_000; f.nextId = id
        failure(f.controller.newLocalDraft("Not a resurrection"), FailureReason.CONFLICT)
        assertTrue(f.record().locals.isEmpty()); assertTrue(id in f.record().issued); assertTrue(f.calls.isEmpty())
    } }
    @Test fun staleLocalRemovalRevisionCannotDeleteNewerTextOrEvenClearItsDeliveryMarker() = runTest { fixture { f ->
        val reviewed = f.controller.newLocalDraft("Reviewed for removal").value().selected!!
        f.controller.editText("Newer text must remain").value(); val before = f.store.writes
        failure(f.controller.discardLocal(reviewed.clientDraftId, reviewed.localRevision), FailureReason.CONFLICT)
        assertEquals(before, f.store.writes); assertEquals("Newer text must remain", f.record().locals.single().caption)
        assertTrue(f.record().tombstones.isEmpty()); assertNotNull(f.record().localPending)
        f.controller.discardLocal(reviewed.clientDraftId, f.record().locals.single().revision).value()
        assertTrue(f.record().locals.isEmpty()); assertEquals(1, f.record().tombstones.size)
    } }
    @Test fun explicitUnsentDiscardOnlyArchivesZeroAttemptOriginalAndPreservesLocalText() = runTest { fixture { f ->
        f.controller.newLocalDraft("Keep local").value()
        f.store.failAfter = { changes -> changes.size > 1 && changes.any { it.key == KEY } }
        failure(f.controller.saveExplicitly(), FailureReason.OUTCOME_UNKNOWN)
        val result = f.controller.discardUnsent().value(); assertNull(result.pending); assertFalse(result.serverAcknowledged)
        assertEquals("Keep local", result.selected!!.caption); assertNull(f.record().command); assertTrue(f.calls.isEmpty())
        f.controller.saveExplicitly().value(); assertEquals(1, f.calls.size)
    } }
    @Test fun remoteListIsBoundedObservationNotDownloadedDraftOrMutationAcknowledgement() = runTest { fixture { f ->
        f.remote = draft(CLIENT); val state = f.controller.listRemote().value()
        assertEquals(SERVER, state.remoteItems.single().id); assertEquals("\"0\"", state.remoteHeadEtag)
        assertTrue(state.localDrafts.isEmpty()); assertFalse(state.serverAcknowledged); assertEquals(0, f.store.writes)
        assertEquals(mapOf("limit" to listOf("2")), f.calls.single().queryParameters); assertEquals(0, f.idCalls)
    } }
    @Test fun cursorHeadChangeAndDuplicatePagesCannotSilentlySkipOrResurrect() = runTest {
        for (mode in listOf("head", "duplicate")) fixture { f ->
            var pageNumber = 0; f.handler = { pageNumber++; response(page(listOf(draft(CLIENT)), "next"), etag = if (mode == "head" && pageNumber > 1) "\"2\"" else "\"1\"") }
            f.controller.listRemote().value(); assertIs<PortResult.Failure>(f.controller.nextRemotePage())
            assertEquals(0, f.store.writes); assertFalse(f.controller.states.value.serverAcknowledged)
            if (mode == "head") { assertTrue(f.controller.states.value.remoteItems.isEmpty()); assertFalse(f.controller.states.value.hasMore) }
        }
    }
    @Test fun exactNegativeReadHidesCanonicalContentAcrossSameLeaseReplacementWithoutDeleteAuthority() = runTest { fixture { f ->
        f.remote = draft(CLIENT); f.controller.refreshRemote(SERVER).value()
        f.handler = { problem(410, "DRAFT_EXPIRED") }; failure(f.controller.refreshRemote(), FailureReason.NOT_FOUND)
        assertNull(f.controller.states.value.selected!!.server); assertTrue(f.controller.states.value.selected!!.serverAssociated)
        f.reopen(); f.controller.restoreLocal().value(); f.controller.openLocal(CLIENT).value()
        assertNull(f.controller.states.value.selected!!.server); assertTrue(f.controller.states.value.selected!!.serverAssociated)
        assertNull(f.controller.states.value.discardConfirmation)
        assertTrue(f.record().tombstones.isEmpty()); assertFalse(f.controller.states.value.serverAcknowledged)
    } }
    @Test fun guestAndInvalidatedLeaseCannotReadWriteOrProjectDraftContent() = runTest {
        fixture(actor = ActorKind.GUEST) { f -> failure(f.controller.restoreLocal(), FailureReason.UNAUTHENTICATED)
            failure(f.controller.newLocalDraft("Guest"), FailureReason.UNAUTHENTICATED); assertTrue(f.store.records.isEmpty()); assertTrue(f.calls.isEmpty()) }
        fixture { f -> f.controller.newLocalDraft("Account private").value(); f.boundary.clear()
            assertTrue(f.controller.states.value.localDrafts.isEmpty()); assertNull(f.controller.states.value.selected)
            failure(f.controller.saveExplicitly(), FailureReason.STALE_SESSION); assertTrue(f.calls.isEmpty()) }
    }
    @Test fun issuedAndLocalCapacityRefuseBeforeAllocatingAnotherIdentity() = runTest { fixture(policy = policy(local = 1, issued = 2)) { f ->
        f.controller.newLocalDraft("Only slot").value(); val count = f.idCalls
        failure(f.controller.newLocalDraft("No eviction"), FailureReason.UNAVAILABLE); assertEquals(count, f.idCalls)
        assertEquals("Only slot", f.record().locals.single().caption); assertTrue(f.calls.isEmpty())
    } }
    @Test fun wireAndIdentityTamperingAreRejectedBeforeNetworkOrNewWrites() = runTest { fixture { f ->
        f.controller.newLocalDraft("Original").value(); f.store.failAfter = { it.size > 1 && it.any { m -> m.key == KEY } }
        failure(f.controller.saveExplicitly(), FailureReason.OUTCOME_UNKNOWN)
        val old = f.store.records.getValue(KEY); val raw = WireDocument.decode(old.payload.copyForCodec()).json().jsonObject
        f.store.records[KEY] = old.copy(payload = PrivateBytes(JsonObject(raw + ("origin" to JsonPrimitive(SERVER))).toString().encodeToByteArray()))
        val writes = f.store.writes; failure(f.controller.retryOriginal(), FailureReason.INVALID_DATA)
        assertEquals(writes, f.store.writes); assertTrue(f.calls.isEmpty())
    } }
    @Test fun requestAndStateDiagnosticsNeverContainCaptionAltTextOrCanonicalBody() = runTest { fixture { f ->
        val state = f.controller.newLocalDraft("PRIVATE_CAPTION", "PRIVATE_ALT").value(); f.controller.saveExplicitly().value()
        val strings = listOf(state.toString(), state.selected.toString(), f.record().toString(), f.calls.single().toString(),
            f.controller.states.value.selected!!.server.toString())
        assertTrue(strings.all { "PRIVATE_CAPTION" !in it && "PRIVATE_ALT" !in it })
    } }
    @Test fun callerTailAfterArmedSaveCannotReleaseProofAcrossBackReopenOrCancellation() = runTest {
        for (change in listOf("back", "reopen", "cancel")) fixture { f ->
            f.controller.newLocalDraft("Caller-tail save").value()
            val scheduled = StandardTestDispatcher(testScheduler)
            val held = ArrayDeque<Pair<CoroutineContext, Runnable>>(); var hold = false
            val caller = object : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (hold) held.addLast(context to block) else scheduled.dispatch(context, block)
            } }
            val task = async(caller) { hold = true; f.controller.saveExplicitly() }
            runCurrent(); assertEquals(1, held.size)
            val first = held.removeFirst(); scheduled.dispatch(first.first, first.second); runCurrent()
            assertEquals(1, held.size); assertNotNull(PostDraftHeld.apply(f.access, f.boundary))
            assertFalse(f.controller.states.value.serverAcknowledged)
            when (change) { "back" -> f.controller.back().value(); "reopen" -> f.reopen(); else -> task.cancel() }
            hold = false; while (held.isNotEmpty()) { val (context, block) = held.removeFirst(); scheduled.dispatch(context, block) }
            if (change == "cancel") assertFailsWith<CancellationException> { task.await() } else failure(task.await(), FailureReason.STALE_SESSION)
            assertFalse(f.controller.states.value.serverAcknowledged)
            val proof = PostDraftHeld.apply(f.access, f.boundary)!!; assertFalse(proof.delivery?.delivered(proof) == true)
            val recovered = f.controller.retryOriginal().value(); assertTrue(recovered.serverAcknowledged); assertEquals(1, f.calls.size)
        }
    }
    @Test fun callerTailAfterArmedLocalEditCannotReportLocalAcknowledgementAfterNavigation() = runTest { fixture { f ->
        f.controller.newLocalDraft("Before").value()
        val scheduled = StandardTestDispatcher(testScheduler); val held = ArrayDeque<Pair<CoroutineContext, Runnable>>(); var hold = false
        val caller = object : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (hold) held.addLast(context to block) else scheduled.dispatch(context, block)
        } }
        val task = async(caller) { hold = true; f.controller.editText("After") }
        // editText first returns from its staging dispatcher; then run returns from the body,
        // then from arming delivery. Hold only the last one, after actual local commit.
        runCurrent()
        repeat(2) { val next = held.removeFirst(); scheduled.dispatch(next.first, next.second); runCurrent() }
        assertEquals(1, held.size); val edit = PostDraftHeld.edit(f.access, f.boundary)!!
        assertEquals("After", f.record().locals.single().caption); assertFalse(edit.delivery?.delivered(edit) == true)
        f.controller.back().value(); hold = false
        while (held.isNotEmpty()) { val (context, block) = held.removeFirst(); scheduled.dispatch(context, block) }
        failure(task.await(), FailureReason.STALE_SESSION)
        assertEquals(PostDraftScreen.LOCAL_LIST, f.controller.states.value.screen)
        assertFalse(f.controller.states.value.localDrafts.single().localAcknowledged); assertTrue(f.calls.isEmpty())
    } }
    @Test fun repeatedFailedEditsRetainTheActuallyObservedPredecessorWithoutAnEverGrowingHistory() = runTest { fixture { f ->
        f.controller.newLocalDraft("Base").value()
        f.store.failAfter = { it.size == 1 && it.single().key == KEY }
        failure(f.controller.editText("A committed, acknowledgement lost"), FailureReason.OUTCOME_UNKNOWN)
        f.store.failBefore = true
        failure(f.controller.editText("B did not commit"), FailureReason.STORAGE_FAILURE)
        val state = f.controller.editText("C acknowledged").value()
        assertEquals("C acknowledged", state.selected!!.caption); assertTrue(state.selected!!.localAcknowledged)
        assertEquals("C acknowledged", f.record().locals.single().caption)
        assertTrue(PostDraftHeld.edit(f.access, f.boundary)!!.predecessors.size <= 2); assertEquals(1, f.idCalls)
    } }
    @Test fun discardByteCapacityRefusesBeforeAllocatingCommandOrChangingQueue() = runTest {
        fixture(policy = PostDraftClientPolicy(8, 65_536, 65_536, 64, 64, 2, 100, 60_000)) { f ->
            f.remote = document(draft(CLIENT).json().jsonObject + ("saveDisclosureVersion" to JsonPrimitive("x".repeat(34_000))))
            f.controller.refreshRemote(SERVER).value(); val ticket = f.controller.prepareServerDiscard().value().discardConfirmation!!
            val before = f.store.writes; val ids = f.idCalls
            failure(f.controller.confirmServerDiscard(ticket), FailureReason.UNAVAILABLE)
            assertEquals(ids, f.idCalls); assertEquals(before, f.store.writes); assertEquals(1, f.calls.size); assertNull(f.record().command)
        }
    }
    @Test fun stableClientIdentityCannotBindToAnotherServerIdOnLaterPageOrRefresh() = runTest {
        for (refresh in listOf(false, true)) fixture { f ->
            var count = 0; f.handler = { count++
                val row = if (count == 1) draft(CLIENT) else document(draft(CLIENT).json().jsonObject + ("id" to JsonPrimitive(number(90))))
                response(page(listOf(row), if (count == 1 && !refresh) "next" else null), etag = "\"1\"") }
            f.controller.listRemote().value()
            failure(if (refresh) f.controller.listRemote() else f.controller.nextRemotePage(), FailureReason.CONFLICT)
            assertEquals(0, f.store.writes); assertFalse(f.controller.states.value.serverAcknowledged)
        }
    }
    @Test fun repeatedExplicitListRefreshCannotReplaceSameVersionBodyOrDowngradeEvenAfterReplacement() = runTest {
        for (replacement in listOf(false, true)) fixture { f ->
            f.remote = draft(CLIENT, "Original", "2"); f.controller.listRemote().value()
            if (replacement) f.reopen()
            f.remote = draft(CLIENT, "Different", "2"); failure(f.controller.listRemote(), FailureReason.CONFLICT)
            f.remote = draft(CLIENT, "Older", "1"); failure(f.controller.listRemote(), FailureReason.CONFLICT)
            assertEquals(0, f.store.writes)
        }
    }
    @Test fun queuedFieldCallbacksCannotEditAnotherSelectedClientDraft() = runTest {
        for (field in listOf("caption", "alt")) fixture { f ->
            val first = f.controller.newLocalDraft("First", "First alt").value().selected!!
            val release = CompletableDeferred<Unit>(); val waiting = CompletableDeferred<Unit>()
            val callback = async { waiting.complete(Unit); release.await()
                if (field == "caption") f.controller.editCaption(first.clientDraftId, "Stale callback") else f.controller.editAltText(first.clientDraftId, "Stale callback") }
            waiting.await(); val second = f.controller.newLocalDraft("Second", "Second alt").value().selected!!
            val writes = f.store.writes; release.complete(Unit); failure(callback.await(), FailureReason.CONFLICT)
            assertEquals(writes, f.store.writes); assertEquals(second.clientDraftId, f.controller.states.value.selected!!.clientDraftId)
            assertEquals("Second", f.controller.states.value.selected!!.caption); assertEquals("Second alt", f.controller.states.value.selected!!.altText)
            assertEquals("First", f.record().locals.single { it.id == first.clientDraftId }.caption)
        }
    }
    @Test fun altFieldEditWhileCaptionCommitSuspendedResolvesFreshCounterpartInsideStaging() = runTest { fixture { f ->
        val local = f.controller.newLocalDraft("Old caption", "Old alt").value().selected!!
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var first = true
        f.store.afterCommit = { if (first) { first = false; entered.complete(Unit); withContext(NonCancellable) { release.await() } } }
        val caption = async { f.controller.editCaption(local.clientDraftId, "Fresh caption") }; entered.await()
        val alt = async { f.controller.editAltText(local.clientDraftId, "Fresh alt") }; runCurrent(); release.complete(Unit)
        failure(caption.await(), FailureReason.STALE_SESSION); val state = alt.await().value()
        assertEquals("Fresh caption", state.selected!!.caption); assertEquals("Fresh alt", state.selected!!.altText)
        assertTrue(state.selected!!.localAcknowledged); assertEquals("Fresh caption", f.record().locals.single().caption)
        assertEquals("Fresh alt", f.record().locals.single().alt); assertTrue(f.calls.isEmpty())
    } }
    @Test fun acknowledgedSaveCanRefreshDirectlyWhileListNeverRetiresCompletion() = runTest { fixture { f ->
        val local = f.controller.newLocalDraft("Saved text", "Saved alt").value().selected!!
        assertTrue(f.controller.saveExplicitly().value().serverAcknowledged)
        val completion = f.record().completion!!; val writes = f.store.writes
        val listed = f.controller.listRemote().value()
        assertFalse(listed.serverAcknowledged); assertEquals(writes, f.store.writes)
        assertEquals(completion, f.record().completion)
        f.remote = document(f.remote!!.json().jsonObject + mapOf("version" to JsonPrimitive(2), "keepOnPlate" to JsonPrimitive(true)))
        val refreshed = f.controller.refreshRemote(SERVER).value()
        assertFalse(refreshed.serverAcknowledged); assertNull(refreshed.pending); assertNull(f.record().completion)
        assertEquals(local.clientDraftId, refreshed.selected!!.clientDraftId)
        assertEquals("Saved text", refreshed.selected!!.caption); assertEquals("Saved alt", refreshed.selected!!.altText)
        assertEquals(JsonPrimitive(true), refreshed.selected!!.server!!.document.json().jsonObject["keepOnPlate"])
        assertEquals(listOf("createPostDraft", "listPostDrafts", "getPostDraft"), f.calls.map { it.operationId })
    } }
    @Test fun refreshCannotRetireHistoricalCompletionWithoutDeliveredSameLeaseProof() = runTest { fixture { f ->
        f.controller.newLocalDraft("Saved text").value(); f.controller.saveExplicitly().value()
        val completion = f.record().completion!!; PostDraftHeld.clear(f.access, f.boundary); f.reopen()
        f.controller.restoreLocal().value(); val writes = f.store.writes
        failure(f.controller.refreshRemote(SERVER), FailureReason.CONFLICT)
        assertEquals(completion, f.record().completion); assertEquals(writes, f.store.writes)
        assertFalse(f.controller.states.value.serverAcknowledged)
        assertEquals("HISTORICAL_COMPLETION", f.controller.states.value.pending!!.phase)
    } }
    @Test fun matchingGetAfterLostCreateIsOnlyObservationAndStillAllowsExactOriginalReplay() = runTest { fixture { f ->
        f.controller.newLocalDraft("Original submitted caption").value(); var first = true
        f.handler = { call -> val result = f.reply(call)
            if (call.operationId == "createPostDraft" && first) { first = false; PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) } else result }
        f.controller.saveExplicitly().value(); val original = f.record().command!!
        val observed = f.controller.refreshRemote(SERVER).value()
        assertFalse(observed.serverAcknowledged); assertEquals(original.id, observed.pending!!.commandId)
        assertNull(f.record().command!!.baseline); assertNull(f.record().command!!.etag)
        assertContentEquals(original.body!!.encodeUtf8(), f.record().command!!.body!!.encodeUtf8())
        f.time += 60_000; val replayed = f.controller.retryOriginal().value()
        assertTrue(replayed.serverAcknowledged); assertEquals(original.id, f.calls.last().idempotencyKey!!.use { it })
        assertContentEquals(original.body!!.encodeUtf8(), f.calls.last().body!!.copyForCodec()); assertNull(f.calls.last().ifMatch)
        assertEquals(listOf("createPostDraft", "getPostDraft", "createPostDraft"), f.calls.map { it.operationId })
    } }
    @Test fun exactObservedUpdateResultCanReplayAfterOriginalBaseExpiryWithoutRebasing() = runTest { fixture { f ->
        f.remote = draft(CLIENT, "Before", expires = "1970-01-01T00:16:41Z")
        f.controller.refreshRemote(SERVER).value(); f.controller.editCaption(CLIENT, "Submitted update").value()
        var committed: WireDocument? = null
        f.handler = { call -> if (call.operationId == "updatePostDraft") {
            if (committed == null) {
                f.reply(call); committed = document(f.remote!!.json().jsonObject + ("expiresAt" to JsonPrimitive("2030-01-01T00:00:00Z")))
                f.remote = committed; PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
            } else response(committed!!)
        } else f.reply(call) }
        f.controller.saveExplicitly().value(); val original = f.record().command!!
        f.time += 60_000; val observed = f.controller.refreshRemote().value()
        assertFalse(observed.serverAcknowledged); assertEquals("\"1\"", f.record().command!!.etag)
        assertContentEquals(original.baseline!!.encodeUtf8(), f.record().command!!.baseline!!.encodeUtf8())
        val result = f.controller.retryOriginal().value(); assertTrue(result.serverAcknowledged)
        assertEquals(original.id, f.calls.last().idempotencyKey!!.use { it }); assertEquals("\"1\"", f.calls.last().ifMatch)
        assertContentEquals(original.body!!.encodeUtf8(), f.calls.last().body!!.copyForCodec())
    } }
    @Test fun getWithUnsubmittedAltTextCannotAdmitOriginalCreateReplayOrAcknowledgeIt() = runTest { fixture { f ->
        f.controller.newLocalDraft("Original caption").value(); var first = true
        f.handler = { call -> val result = f.reply(call)
            if (call.operationId == "createPostDraft" && first) { first = false; PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) } else result }
        f.controller.saveExplicitly().value(); val original = f.record().command!!
        f.remote = document(f.remote!!.json().jsonObject + ("altText" to JsonPrimitive("Never submitted")))
        val observed = f.controller.refreshRemote(SERVER).value()
        assertFalse(observed.serverAcknowledged); assertNull(observed.selected!!.altText)
        val calls = f.calls.size; f.time += 60_000
        failure(f.controller.retryOriginal(), FailureReason.CONFLICT)
        assertEquals(calls, f.calls.size); assertNull(f.record().completion)
        assertEquals(original.id, f.record().command!!.id)
        assertContentEquals(original.body!!.encodeUtf8(), f.record().command!!.body!!.encodeUtf8())
        assertFalse(f.controller.states.value.serverAcknowledged)
    } }
    @Test fun laterObservedMatchingCaptionCannotMasqueradeAsOriginalCreateResultOrEnableReplay() = runTest { fixture { f ->
        f.controller.newLocalDraft("Same text").value(); var first = true
        f.handler = { call -> val result = f.reply(call)
            if (call.operationId == "createPostDraft" && first) { first = false; PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) } else result }
        f.controller.saveExplicitly().value(); val original = f.record().command!!
        f.remote = document(f.remote!!.json().jsonObject + ("version" to JsonPrimitive(2)))
        f.controller.refreshRemote(SERVER).value(); val calls = f.calls.size; f.time += 60_000
        failure(f.controller.retryOriginal(), FailureReason.CONFLICT)
        assertEquals(calls, f.calls.size); assertEquals(original.id, f.record().command!!.id)
        assertFalse(f.controller.states.value.serverAcknowledged); assertNull(f.record().command!!.etag)
    } }

    private suspend fun TestScope.fixture(actor: ActorKind = ActorKind.ACCOUNT, policy: PostDraftClientPolicy = policy(), block: suspend (Fixture) -> Unit) {
        val f = Fixture(this, actor, policy); try { block(f) } finally { f.controller.close(); f.boundary.clear() }
    }
    internal class Fixture(test: TestScope, actor: ActorKind, val policy: PostDraftClientPolicy) {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = StorageScope("fixture", actor, "post-draft-owner")
        val boundary = SessionBoundary(); val lease = boundary.activate(scope); val store = Store(scope)
        var time = 1_000_000L; var online = true; var idCalls = 0; var nextId: String? = null
        val ids = MealOperationIds { idCalls++; nextId ?: number(idCalls + 100) }
        val calls = mutableListOf<ApiCall>(); var remote: WireDocument? = null
        var handler: suspend (ApiCall) -> PortResult<ApiReply> = { reply(it) }
        val access = AuthenticatedMealPlanningAccess(lease, ORIGIN, store, object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> { assertSame(this@Fixture.lease, lease); calls += call; return handler(call) }
        }, true)
        var controller = create()
        fun create() = PostDraftController(MealKitchenComposition(access, boundary, dispatcher, EpochClock { time },
            ConnectivityPort { if (online) Connectivity.ONLINE else Connectivity.OFFLINE }), ids, policy)
        suspend fun reopen() { controller.close(); controller = create() }
        fun record() = PostDraftCodec(ORIGIN, policy).decode(store.records.getValue(KEY).payload)
        fun reply(call: ApiCall): PortResult<ApiReply> = when (call.operationId) {
            "createPostDraft" -> {
                val body = call.document().json().jsonObject
                if (remote == null) remote = document(draft(body.getValue("clientDraftId").jsonPrimitive.content).json().jsonObject + body)
                response(remote!!, 201)
            }
            "updatePostDraft" -> { val old = remote!!.json().jsonObject; val version = postVersion(remote!!).toLong() + 1
                remote = document(old + call.document().json().jsonObject + ("version" to JsonPrimitive(version))); response(remote!!) }
            "getPostDraft" -> remote?.let { response(it) } ?: problem(404, "DRAFT_UNAVAILABLE")
            "listPostDrafts" -> response(page(listOfNotNull(remote)), etag = "\"0\"")
            "deletePostDraft" -> PortResult.Value(ApiReply(204, null))
            else -> PortResult.Failure(FailureReason.NOT_CONFIGURED)
        }
    }
    internal class Store(private val scope: StorageScope) : PrivateStateStore {
        val records = mutableMapOf<RecordKey, PrivateRecord>(); var reads = 0; var writes = 0; var erases = 0; var nextCreateRevision = 1L
        var afterRead: (RecordKey, PrivateRecord?) -> Unit = { _, _ -> }
        var failBefore = false; var badAck = false; var failAfter: ((List<StoreMutation>) -> Boolean)? = null
        var afterCommit: suspend (List<StoreMutation>) -> Unit = {}
        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> {
            if (scope != this.scope) return PortResult.Failure(FailureReason.STALE_SESSION)
            reads++; val record = records[key]?.let { it.copy(payload = PrivateBytes(it.payload.copyForCodec())) }
            afterRead(key, record); return PortResult.Value(record)
        }
        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> {
            if (scope != this.scope) return PortResult.Failure(FailureReason.STALE_SESSION)
            if (failBefore) { failBefore = false; return PortResult.Failure(FailureReason.STORAGE_FAILURE) }
            if (mutations.map { it.key }.distinct().size != mutations.size || mutations.any { records[it.key]?.revision != it.expectedRevision }) return PortResult.Failure(FailureReason.CONFLICT)
            val fail = failAfter?.invoke(mutations) == true; if (fail) failAfter = null
            val result = mutations.associate { change -> change.key to when (change) {
                is StoreMutation.Put -> { val revision = records[change.key]?.revision?.plus(1) ?: nextCreateRevision
                    records[change.key] = PrivateRecord(revision, change.schemaVersion, PrivateBytes(change.payload.copyForCodec())); revision }
                is StoreMutation.Delete -> { records.remove(change.key); null }
            } }; writes++; afterCommit(mutations)
            if (badAck) { badAck = false; return PortResult.Value(emptyMap()) }
            return if (fail) PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) else PortResult.Value(result)
        }
        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> { erases++; return PortResult.Failure(FailureReason.FORBIDDEN) }
    }
    companion object {
        internal const val ORIGIN = "00000000-0000-4000-8000-000000000001"
        internal const val SERVER = "00000000-0000-4000-8000-000000000002"
        internal const val CLIENT = "00000000-0000-4000-8000-000000000003"
        internal const val TIME = "2026-09-14T00:00:00Z"
        internal val KEY = RecordKey("mealflow.post-drafts.v1", ORIGIN)
        internal fun number(n: Int) = "00000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
        internal fun policy(local: Int = 8, issued: Int = 64) = PostDraftClientPolicy(local, 1_048_576, 262_144, issued, 64, 2, 100, 60_000)
        internal fun document(values: Map<String, JsonElement>) = WireDocument.parse(JsonObject(values).toString())
        internal fun draft(client: String, caption: String = "A draft", version: String = "1", expires: String = "2030-01-01T00:00:00Z") = WireDocument.parse(buildJsonObject {
            put("id", SERVER); put("clientDraftId", client); put("version", Json.parseToJsonElement(version)); put("createdAt", TIME); put("updatedAt", TIME)
            put("status", "draft"); put("caption", caption); put("mediaIds", JsonArray(emptyList())); put("audience", postSelfAudience())
            put("keepOnPlate", false); put("allowRecipeSaves", false); put("expiresAt", expires)
        }.toString())
        internal fun page(items: List<WireDocument>, next: String? = null) = WireDocument.parse(buildJsonObject {
            put("items", JsonArray(items.map { it.json() })); put("nextCursor", next?.let(::JsonPrimitive) ?: JsonNull); put("serverTime", TIME)
        }.toString())
        internal fun response(body: WireDocument, status: Int = 200, etag: String? = null): PortResult<ApiReply> = PortResult.Value(ApiReply(status,
            PrivateBytes(body.encodeUtf8()), etag ?: if (body.field("version") is WireField.Value) "\"${postVersion(body)}\"" else null, contentType = "application/json"))
        internal fun problem(status: Int, code: String): PortResult<ApiReply> = PortResult.Value(ApiReply(status, PrivateBytes(buildJsonObject {
            put("type", "https://example.invalid/problem"); put("title", "Synthetic denial"); put("status", status); put("code", code); put("traceId", "fixture-trace")
        }.toString().encodeToByteArray()), contentType = "application/problem+json"))
        internal fun ApiCall.document() = WireDocument.decode(body!!.copyForCodec())
        private fun assertPendingFields(expected: PostDraftPending, actual: PostDraftPending) {
            assertEquals(expected.commandId, actual.commandId); assertEquals(expected.operationId, actual.operationId)
            assertEquals(expected.clientDraftId, actual.clientDraftId); assertEquals(expected.phase, actual.phase)
            assertEquals(expected.attempts, actual.attempts); assertEquals(expected.issue, actual.issue)
            assertEquals(expected.canRetry, actual.canRetry); assertEquals(expected.canDiscardUnsent, actual.canDiscardUnsent)
            assertEquals(expected.finalizationRequired, actual.finalizationRequired)
        }
        private fun assertOriginalFields(expected: PostOriginal, actual: PostOriginal) {
            assertEquals(expected.id, actual.id); assertEquals(expected.operation, actual.operation)
            assertEquals(expected.clientId, actual.clientId); assertEquals(expected.localRevision, actual.localRevision)
            assertEquals(expected.etag, actual.etag); assertEquals(expected.created, actual.created); assertEquals(expected.serverId, actual.serverId)
            assertEquals(expected.body == null, actual.body == null); assertEquals(expected.baseline == null, actual.baseline == null)
            expected.body?.let { assertContentEquals(it.encodeUtf8(), actual.body!!.encodeUtf8()) }
            expected.baseline?.let { assertContentEquals(it.encodeUtf8(), actual.baseline!!.encodeUtf8()) }
            val before = expected.call(); val after = actual.call()
            assertEquals(before.operationId, after.operationId); assertEquals(before.idempotencyKey!!.use { it }, after.idempotencyKey!!.use { it })
            assertEquals(before.ifMatch, after.ifMatch); assertEquals(before.pathParameters, after.pathParameters)
            assertEquals(before.queryParameters, after.queryParameters)
        }
        internal fun <T> PortResult<T>.value(): T = assertIs<PortResult.Value<T>>(this).value
        internal fun failure(result: PortResult<*>, expected: FailureReason) = assertEquals(expected, assertIs<PortResult.Failure>(result).reason)
    }
}
