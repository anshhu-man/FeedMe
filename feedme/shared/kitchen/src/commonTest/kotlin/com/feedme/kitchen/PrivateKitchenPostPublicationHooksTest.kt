package com.feedme.kitchen

import com.feedme.core.ports.*
import com.feedme.kitchen.KitchenReliabilityFixture.Companion.COMMAND
import com.feedme.kitchen.KitchenReliabilityFixture.Companion.ID
import com.feedme.kitchen.KitchenReliabilityFixture.Companion.ORIGIN
import com.feedme.kitchen.KitchenReliabilityFixture.Companion.PLAN
import com.feedme.kitchen.KitchenReliabilityFixture.Companion.bytes
import com.feedme.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Actual queue/acknowledgement wrapper over synthetic ports; not publication authority,
 * native upload/storage, HTTP/PG or release evidence. No publication controller is supplied. */
@OptIn(ExperimentalCoroutinesApi::class)
class PrivateKitchenPostPublicationHooksTest {
    @Test fun absentPublicationHooksDenyBothBranchesAndNeverAutomaticallySend() = runTest {
        for (saved in listOf(false, true)) {
            val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); val s = session(f)
            val queued = value(s.commands.enqueue(f.lease, intent(saved)))
            assertEquals(CommandPhase.AWAITING_CONFIRMATION, queued.phase)
            assertNull(value(s.commands.dispatchNext(f.lease)))
            assertNull(value(s.commands.dispatchAutomatic(f.lease, COMMAND)))
            val blocked = value(s.commands.dispatchConfirmed(f.lease, COMMAND))!!
            assertEquals(CommandIssue.NOT_CONFIGURED, blocked.issue); assertEquals(0, blocked.attempts)
            assertTrue(f.calls.isEmpty()); assertNull(value(s.commands.receipt(f.lease, COMMAND)))
        }
    }

    @Test fun bothBranchesDeliverExactOriginalOnlyAfterExplicitConfirmationAndStopAtReceipt() = runTest {
        for (saved in listOf(false, true)) {
            val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler))
            val gated = mutableListOf<CommandIntent>(); val observed = mutableListOf<CommandIntent>()
            val response = postReply(); f.exchange = { PortResult.Value(response) }
            val s = session(f, hooks(gate = { lease, original ->
                assertSame(f.lease, lease); gated += original; ExecutionDecision.Ready
            }, observer = { lease, original, reply ->
                assertSame(f.lease, lease); assertSame(response, reply); observed += original; PortResult.Value(Unit)
            }))
            val original = intent(saved); value(s.commands.enqueue(f.lease, original))
            assertNull(value(s.commands.dispatchNext(f.lease)))
            assertNull(value(s.commands.dispatchAutomatic(f.lease, COMMAND)))
            assertTrue(gated.isEmpty()); assertTrue(observed.isEmpty()); assertTrue(f.calls.isEmpty())
            value(s.commands.dispatchConfirmed(f.lease, COMMAND))
            assertEquals(1, gated.size); assertEquals(1, observed.size); assertEquals(1, f.calls.size)
            (gated + observed).forEach { assertOriginal(original, it) }
            val sent = f.calls.single()
            assertEquals("publishPost", sent.operationId); assertNull(sent.ifMatch)
            assertTrue(sent.pathParameters.isEmpty()); assertTrue(sent.queryParameters.isEmpty())
            assertContentEquals(original.call.body!!.copyForCodec(), sent.body!!.copyForCodec())
            assertEquals(COMMAND, sent.idempotencyKey!!.use { it })
            val command = value(s.commands.command(f.lease, COMMAND))!!
            assertEquals(CommandPhase.RECEIPT_READY, command.phase); assertEquals(1, command.attempts)
            val receipt = value(s.commands.receipt(f.lease, COMMAND))!!
            assertEquals(201, receipt.reply.status); assertEquals("\"1\"", receipt.reply.etag)
            assertContentEquals(response.body!!.copyForCodec(), receipt.reply.body!!.copyForCodec())
            // The hook/journal neither applies a Post to domain state nor clears its intent.
            assertNotNull(value(s.commands.intent(f.lease, COMMAND)))
            assertTrue(f.store.records.keys.all { it.collection.startsWith("feedme.command.") })
        }
    }

    @Test fun publicationHooksCannotAdmitDraftCookingCookbookOrPostMutationSiblings() = runTest {
        val calls = listOf(
            ApiCall("createPostDraft", body = bytes("""{"clientDraftId":"$PLAN","caption":"Synthetic"}"""), idempotencyKey = SecretText(COMMAND)),
            ApiCall("updatePostDraft", pathParameters = mapOf("draftId" to ID), body = bytes("""{"caption":"Synthetic"}"""), ifMatch = "\"1\"", idempotencyKey = SecretText(COMMAND)),
            ApiCall("deletePostDraft", pathParameters = mapOf("draftId" to ID), ifMatch = "\"1\"", idempotencyKey = SecretText(COMMAND)),
            ApiCall("createCookSession", body = bytes("""{"planId":"$PLAN","deviceSequence":0}"""), idempotencyKey = SecretText(COMMAND)),
            ApiCall("saveRecipe", body = bytes("""{"planId":"$PLAN"}"""), idempotencyKey = SecretText(COMMAND)),
            ApiCall("deleteSavedRecipe", pathParameters = mapOf("savedRecipeId" to ID), ifMatch = "\"1\"", idempotencyKey = SecretText(COMMAND)),
            ApiCall("updatePreferences", body = bytes("{}"), ifMatch = "\"1\"", idempotencyKey = SecretText(COMMAND)),
            ApiCall("createCollection", body = bytes("""{"name":"Synthetic"}"""), idempotencyKey = SecretText(COMMAND)),
            ApiCall("updatePost", pathParameters = mapOf("postId" to ID), body = bytes("""{"caption":"Synthetic"}"""), ifMatch = "\"1\"", idempotencyKey = SecretText(COMMAND)),
            ApiCall("deletePost", pathParameters = mapOf("postId" to ID), ifMatch = "\"1\"", idempotencyKey = SecretText(COMMAND)),
        )
        for (call in calls) {
            val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); var called = 0
            val s = session(f, hooks(gate = { _, _ -> called++; ExecutionDecision.Ready },
                observer = { _, _, _ -> called++; PortResult.Value(Unit) }))
            value(s.commands.enqueue(f.lease, CommandIntent(COMMAND, ORIGIN, call)))
            val blocked = value(s.commands.dispatchConfirmed(f.lease, COMMAND))!!
            assertEquals(CommandIssue.NOT_CONFIGURED, blocked.issue); assertEquals(0, blocked.attempts)
            assertEquals(0, called); assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun configuredDraftHookDoesNotBecomePublicationPermission() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); var called = 0
        val draft = object : PostDraftCommandHooks {
            override suspend fun executionDecision(lease: SessionLease, intent: CommandIntent): ExecutionDecision {
                called++; return ExecutionDecision.Ready
            }
            override suspend fun observeReply(lease: SessionLease, intent: CommandIntent, reply: ApiReply): PortResult<Unit> {
                called++; return PortResult.Value(Unit)
            }
        }
        val s = PrivateKitchenSession(f.scope, f.store, f.boundary, f.dispatcher,
            EpochClock { 1_800_000_000_000L }, f.transport, ORIGIN, postDraftCommandHooks = draft)
        value(s.commands.enqueue(f.lease, intent()))
        val blocked = value(s.commands.dispatchConfirmed(f.lease, COMMAND))!!
        assertEquals(CommandIssue.NOT_CONFIGURED, blocked.issue); assertEquals(0, blocked.attempts)
        assertEquals(0, called); assertTrue(f.calls.isEmpty())
    }

    @Test fun readUploadAndSignedCapabilityWorkflowsCannotEnterPublicationJournal() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); var called = 0
        val s = session(f, hooks(gate = { _, _ -> called++; ExecutionDecision.Ready }))
        val calls = listOf(
            ApiCall("getPost", pathParameters = mapOf("postId" to ID)),
            ApiCall("listPostDrafts"),
            ApiCall("getMediaStatus", pathParameters = mapOf("mediaId" to ID)),
            ApiCall("prepareMediaUpload", body = bytes("""{"kind":"photo","contentType":"image/jpeg","bytes":1,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","clientDraftId":"$PLAN"}"""), idempotencyKey = SecretText(COMMAND)),
            ApiCall("completeMediaUpload", pathParameters = mapOf("mediaId" to ID), body = bytes("""{"objectVersionId":"synthetic-version","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}"""), idempotencyKey = SecretText(COMMAND)),
            ApiCall("getMediaAccess", pathParameters = mapOf("mediaId" to ID), body = bytes("""{"postId":"$ID","surface":"detail","variant":"display"}""")),
        )
        for (call in calls) assertEquals(FailureReason.INVALID_DATA,
            assertIs<PortResult.Failure>(s.commands.enqueue(f.lease, CommandIntent(COMMAND, ORIGIN, call))).reason)
        assertEquals(0, called); assertTrue(f.store.reads.isEmpty())
        assertTrue(f.store.commits.isEmpty()); assertTrue(f.calls.isEmpty())
    }

    @Test fun guestAndDemoCannotReachPublicationHooksOrPrivateQueueStorage() = runTest {
        for (kind in listOf(ActorKind.GUEST, ActorKind.DEMO)) {
            val scope = StorageScope("publication-hook-test", kind, "synthetic-owner")
            val store = KitchenReliabilityStore(scope); val boundary = SessionBoundary(); val lease = boundary.activate(scope)
            var called = 0; var sent = 0
            val transport = object : AccountTransport {
                override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                    sent++; return PortResult.Value(postReply())
                }
            }
            val s = PrivateKitchenSession(scope, store, boundary, StandardTestDispatcher(testScheduler),
                EpochClock { 1_800_000_000_000L }, transport, ORIGIN,
                postPublicationCommandHooks = hooks(gate = { _, _ -> called++; ExecutionDecision.Ready }))
            val failure = assertIs<PortResult.Failure>(s.commands.enqueue(lease, intent()))
            assertEquals(if (kind == ActorKind.DEMO) FailureReason.UNAUTHENTICATED else FailureReason.INVALID_DATA, failure.reason)
            assertEquals(0, called); assertEquals(0, sent); assertTrue(store.reads.isEmpty()); assertTrue(store.commits.isEmpty())
        }
    }

    @Test fun wrongOriginCannotReachPublicationGate() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); var called = 0
        val s = session(f, hooks(gate = { _, _ -> called++; ExecutionDecision.Ready }))
        value(s.commands.enqueue(f.lease, CommandIntent(COMMAND, PLAN, intent().call)))
        val blocked = value(s.commands.dispatchConfirmed(f.lease, COMMAND))!!
        assertEquals(CommandIssue.AUTH_REQUIRED, blocked.issue); assertEquals(0, blocked.attempts)
        assertEquals(0, called); assertTrue(f.calls.isEmpty())
    }

    @Test fun foreignBoundaryWrongScopeAndReplacedLeaseCannotReachPublicationGate() = runTest {
        for (variant in listOf("boundary", "scope", "replaced")) {
            val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); var called = 0
            val s = session(f, hooks(gate = { _, _ -> called++; ExecutionDecision.Ready }))
            value(s.commands.enqueue(f.lease, intent()))
            val candidate = when (variant) {
                "boundary" -> SessionBoundary().activate(f.scope)
                "scope" -> f.boundary.activate(StorageScope("other-environment", ActorKind.ACCOUNT, "synthetic-other"))
                else -> f.lease.also { f.boundary.activate(f.scope) }
            }
            val reads = f.store.reads.size; val writes = f.store.commits.size
            assertEquals(FailureReason.STALE_SESSION,
                assertIs<PortResult.Failure>(s.commands.dispatchConfirmed(candidate, COMMAND)).reason)
            assertEquals(reads, f.store.reads.size); assertEquals(writes, f.store.commits.size)
            assertEquals(0, called); assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun publicationCannotSkipAnEarlierSocialDraftInTheSameQueue() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); var called = 0
        f.exchange = { PortResult.Value(postReply()) }
        val s = session(f, hooks(gate = { _, _ -> called++; ExecutionDecision.Ready }))
        val earlier = value(s.commands.enqueue(f.lease, CommandIntent(COMMAND, ORIGIN,
            ApiCall("createPostDraft", body = bytes("""{"clientDraftId":"$PLAN"}"""), idempotencyKey = SecretText(COMMAND)))))
        val publish = intent(command = SECOND_COMMAND); value(s.commands.enqueue(f.lease, publish))
        assertNull(value(s.commands.dispatchConfirmed(f.lease, SECOND_COMMAND)))
        assertEquals(0, called); assertTrue(f.calls.isEmpty())
        assertEquals(0, value(s.commands.command(f.lease, SECOND_COMMAND))!!.attempts)
        value(s.commands.discardUnsent(f.lease, COMMAND, earlier.localRevision))
        value(s.commands.dispatchConfirmed(f.lease, SECOND_COMMAND))
        assertEquals(1, called); assertEquals(listOf("publishPost"), f.calls.map { it.operationId })
        assertEquals(CommandPhase.RECEIPT_READY, value(s.commands.command(f.lease, SECOND_COMMAND))!!.phase)
    }

    @Test fun failedPublicationObserverRetainsSentOriginalWithoutReceiptPromotion() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); f.exchange = { PortResult.Value(postReply()) }
        val s = session(f, hooks(observer = { _, _, _ -> PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }))
        val original = intent(true); value(s.commands.enqueue(f.lease, original))
        assertEquals(FailureReason.OUTCOME_UNKNOWN,
            assertIs<PortResult.Failure>(s.commands.dispatchConfirmed(f.lease, COMMAND)).reason)
        assertEquals(1, f.calls.size); assertEquals(CommandPhase.IN_FLIGHT, value(s.commands.command(f.lease, COMMAND))!!.phase)
        assertNull(value(s.commands.receipt(f.lease, COMMAND)))
        assertOriginal(original, value(s.commands.intent(f.lease, COMMAND))!!)
    }

    @Test fun cancelledPublicationGateDoesNotClaimOrSend() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler))
        val s = session(f, hooks(gate = { _, _ -> currentCoroutineContext().cancel(); ExecutionDecision.Ready }))
        value(s.commands.enqueue(f.lease, intent())); val writes = f.store.commits.size
        val task = async { s.commands.dispatchConfirmed(f.lease, COMMAND) }; task.join(); assertTrue(task.isCancelled)
        assertEquals(writes, f.store.commits.size); assertTrue(f.calls.isEmpty())
        assertEquals(CommandPhase.AWAITING_CONFIRMATION, value(s.commands.command(f.lease, COMMAND))!!.phase)
    }

    @Test fun cancelledPublicationObserverDoesNotPersistSuccessfulPostReceipt() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); f.exchange = { PortResult.Value(postReply()) }
        val s = session(f, hooks(observer = { _, _, _ -> currentCoroutineContext().cancel(); PortResult.Value(Unit) }))
        value(s.commands.enqueue(f.lease, intent()))
        val task = async { s.commands.dispatchConfirmed(f.lease, COMMAND) }; task.join(); assertTrue(task.isCancelled)
        assertEquals(1, f.calls.size); assertEquals(CommandPhase.IN_FLIGHT, value(s.commands.command(f.lease, COMMAND))!!.phase)
        assertNull(value(s.commands.receipt(f.lease, COMMAND)))
    }

    @Test fun invalidationDuringNonCooperativeTransportCannotReachPublicationObserver() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var observed = 0
        f.exchange = { entered.complete(Unit); withContext(NonCancellable) { release.await() }; PortResult.Value(postReply()) }
        val s = session(f, hooks(observer = { _, _, _ -> observed++; PortResult.Value(Unit) }))
        value(s.commands.enqueue(f.lease, intent()))
        val pending = async { s.commands.dispatchConfirmed(f.lease, COMMAND) }; entered.await()
        val writes = f.store.commits.size; f.boundary.clear(); release.complete(Unit)
        assertEquals(FailureReason.STALE_SESSION, assertIs<PortResult.Failure>(pending.await()).reason)
        assertEquals(1, f.calls.size); assertEquals(0, observed); assertEquals(writes, f.store.commits.size)
        assertTrue(f.store.records.keys.none { it.collection == "feedme.command.receipt" })
    }

    @Test fun readyPublicationHookCannotBypassFailedClaimAcknowledgement() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); val s = session(f, hooks())
        value(s.commands.enqueue(f.lease, intent()))
        f.store.receipt = { PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
        assertEquals(FailureReason.OUTCOME_UNKNOWN,
            assertIs<PortResult.Failure>(s.commands.dispatchConfirmed(f.lease, COMMAND)).reason)
        assertTrue(f.calls.isEmpty()); assertEquals(CommandPhase.IN_FLIGHT, value(s.commands.command(f.lease, COMMAND))!!.phase)
        assertNull(value(s.commands.receipt(f.lease, COMMAND)))
    }

    @Test fun publicationHookPreservesConstructionWithoutIoAndExactCompositionIdentity() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); val s = session(f, hooks())
        assertTrue(s.matchesComposition(f.scope, f.store, f.boundary, ORIGIN))
        assertFalse(s.matchesComposition(f.scope, object : PrivateStateStore by f.store {}, f.boundary, ORIGIN))
        assertFalse(s.matchesComposition(f.scope, f.store, SessionBoundary(), ORIGIN))
        assertFalse(s.matchesComposition(StorageScope("other", ActorKind.ACCOUNT, "synthetic-owner"), f.store, f.boundary, ORIGIN))
        assertFalse(s.matchesComposition(f.scope, f.store, f.boundary, PLAN))
        assertTrue(f.store.reads.isEmpty()); assertTrue(f.store.commits.isEmpty()); assertTrue(f.calls.isEmpty())
    }

    private fun session(f: KitchenReliabilityFixture, hook: PostPublicationCommandHooks? = null) = PrivateKitchenSession(
        f.scope, f.store, f.boundary, f.dispatcher, EpochClock { 1_800_000_000_000L }, f.transport, ORIGIN,
        postPublicationCommandHooks = hook)
    private fun hooks(gate: suspend (SessionLease, CommandIntent) -> ExecutionDecision = { _, _ -> ExecutionDecision.Ready },
        observer: suspend (SessionLease, CommandIntent, ApiReply) -> PortResult<Unit> = { _, _, _ -> PortResult.Value(Unit) }) =
        object : PostPublicationCommandHooks {
            override suspend fun executionDecision(lease: SessionLease, intent: CommandIntent) = gate(lease, intent)
            override suspend fun observeReply(lease: SessionLease, intent: CommandIntent, reply: ApiReply) = observer(lease, intent, reply)
        }
    private fun intent(saved: Boolean = false, command: String = COMMAND): CommandIntent {
        val pair = if (saved) """, "draftId":"$ID", "draftVersion":9007199254740993""" else ""
        return CommandIntent(command, ORIGIN, ApiCall("publishPost",
            body = bytes("""{ "caption":"Synthetic reviewed moment", "mediaIds":[], "audience":{"kind":"self","circleIds":[]}, "keepOnPlate":false, "allowRecipeSaves":false, "saveDisclosureVersion":"synthetic-disclosure-v1", "clientDraftId":"$PLAN"$pair }"""),
            idempotencyKey = SecretText(command)))
    }
    private fun postReply() = ApiReply(201,
        bytes("""{"id":"$ID","version":1,"createdAt":"2026-09-14T00:00:00Z","updatedAt":"2026-09-14T00:00:00Z","author":{"userId":"$PLAN","displayName":"Synthetic author","handle":"synthetic-author"},"caption":"Synthetic reviewed moment","mediaIds":[],"audience":{"kind":"self","circleIds":[]},"status":"published","publishedAt":"2026-09-14T00:00:00Z","expiresAt":"2026-09-15T00:00:00Z","keepOnPlate":false,"savePolicy":{"allowFutureSaves":false,"policyVersion":1,"disclosureVersion":"synthetic-disclosure-v1"},"aclVersion":1,"capabilities":[],"reactionCounts":[]}"""),
        etag = "\"1\"", contentType = "application/json")
    private fun assertOriginal(expected: CommandIntent, actual: CommandIntent) {
        assertEquals(expected.commandId, actual.commandId); assertEquals(expected.originBinding, actual.originBinding)
        assertEquals(expected.dependencyCommandIds, actual.dependencyCommandIds)
        assertEquals(expected.call.operationId, actual.call.operationId)
        assertEquals(expected.call.pathParameters, actual.call.pathParameters); assertEquals(expected.call.queryParameters, actual.call.queryParameters)
        assertEquals(expected.call.ifMatch, actual.call.ifMatch)
        assertEquals(expected.call.idempotencyKey!!.use { it }, actual.call.idempotencyKey!!.use { it })
        assertContentEquals(expected.call.body!!.copyForCodec(), actual.call.body!!.copyForCodec())
    }
    private fun <T> value(result: PortResult<T>) = assertIs<PortResult.Value<T>>(result).value
    private companion object { const val SECOND_COMMAND = "123e4567-e89b-12d3-a456-426614174005" }
}
