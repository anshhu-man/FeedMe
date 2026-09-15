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

/** Actual journal and acknowledgement wrapper over synthetic common ports, not server authority. */
@OptIn(ExperimentalCoroutinesApi::class)
class PrivateKitchenPostDraftHooksTest {
    @Test fun missingHooksDenyEveryExplicitDraftMutationWithoutTransport() = runTest {
        for (op in OPERATIONS) {
            val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); val s = session(f)
            val enqueued = value(s.commands.enqueue(f.lease, intent(op)))
            assertEquals(CommandPhase.AWAITING_CONFIRMATION, enqueued.phase)
            assertNull(value(s.commands.dispatchNext(f.lease)))
            assertNull(value(s.commands.dispatchAutomatic(f.lease, COMMAND)))
            val blocked = value(s.commands.dispatchConfirmed(f.lease, COMMAND))!!
            assertEquals(CommandIssue.NOT_CONFIGURED, blocked.issue); assertEquals(0, blocked.attempts)
            assertTrue(f.calls.isEmpty()); assertNull(value(s.commands.receipt(f.lease, COMMAND)))
        }
    }

    @Test fun eachHookReceivesTheExactOriginalOnlyAfterExplicitConfirmation() = runTest {
        for (op in OPERATIONS) {
            val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); val gates = mutableListOf<CommandIntent>()
            val observations = mutableListOf<CommandIntent>(); val reply = reply(op)
            f.exchange = { PortResult.Value(reply) }
            val s = session(f, hooks(gate = { lease, original ->
                assertSame(f.lease, lease); gates += original; ExecutionDecision.Ready
            }, observer = { lease, original, observed ->
                assertSame(f.lease, lease); assertSame(reply, observed); observations += original; PortResult.Value(Unit)
            }))
            val original = intent(op); value(s.commands.enqueue(f.lease, original))
            assertNull(value(s.commands.dispatchNext(f.lease)))
            assertNull(value(s.commands.dispatchAutomatic(f.lease, COMMAND)))
            assertTrue(gates.isEmpty()); assertTrue(observations.isEmpty()); assertTrue(f.calls.isEmpty())
            value(s.commands.dispatchConfirmed(f.lease, COMMAND))
            assertEquals(1, gates.size); assertEquals(1, observations.size); assertEquals(1, f.calls.size)
            for (seen in gates + observations) {
                assertEquals(COMMAND, seen.commandId); assertEquals(ORIGIN, seen.originBinding)
                assertEquals(original.call.operationId, seen.call.operationId)
                assertEquals(original.call.pathParameters, seen.call.pathParameters)
                assertEquals(original.call.queryParameters, seen.call.queryParameters)
                assertEquals(original.call.ifMatch, seen.call.ifMatch)
                assertEquals(COMMAND, seen.call.idempotencyKey!!.use { it })
                assertContentEquals(original.call.body?.copyForCodec(), seen.call.body?.copyForCodec())
            }
            assertEquals(CommandPhase.RECEIPT_READY, value(s.commands.command(f.lease, COMMAND))!!.phase)
            assertEquals(reply.status, value(s.commands.receipt(f.lease, COMMAND))!!.reply.status)
        }
    }

    @Test fun draftHooksDoNotAdmitCookingCookbookPreferencesOrCollections() = runTest {
        val others = listOf(
            ApiCall("createCookSession", body = bytes("""{"planId":"$PLAN","deviceSequence":0}"""), idempotencyKey = SecretText(COMMAND)),
            ApiCall("saveRecipe", body = bytes("""{"planId":"$PLAN"}"""), idempotencyKey = SecretText(COMMAND)),
            ApiCall("deleteSavedRecipe", pathParameters = mapOf("savedRecipeId" to ID), ifMatch = "\"1\"", idempotencyKey = SecretText(COMMAND)),
            ApiCall("updatePreferences", body = bytes("{}"), ifMatch = "\"1\"", idempotencyKey = SecretText(COMMAND)),
            ApiCall("createCollection", body = bytes("""{"name":"Synthetic"}"""), idempotencyKey = SecretText(COMMAND)),
        )
        for (call in others) {
            val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); var hookCalls = 0
            val s = session(f, hooks(gate = { _, _ -> hookCalls++; ExecutionDecision.Ready },
                observer = { _, _, _ -> hookCalls++; PortResult.Value(Unit) }))
            value(s.commands.enqueue(f.lease, CommandIntent(COMMAND, ORIGIN, call)))
            val result = value(s.commands.dispatchConfirmed(f.lease, COMMAND))!!
            assertEquals(CommandIssue.NOT_CONFIGURED, result.issue); assertEquals(0, result.attempts)
            assertEquals(0, hookCalls); assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun readOperationsAreNotDurableCommandsEvenWithDraftHooks() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); var called = 0
        val s = session(f, hooks(gate = { _, _ -> called++; ExecutionDecision.Ready }))
        for (call in listOf(ApiCall("listPostDrafts"), ApiCall("getPostDraft", pathParameters = mapOf("draftId" to ID)))) {
            assertEquals(FailureReason.INVALID_DATA,
                assertIs<PortResult.Failure>(s.commands.enqueue(f.lease, CommandIntent(COMMAND, ORIGIN, call))).reason)
        }
        assertEquals(0, called); assertTrue(f.store.reads.isEmpty()); assertTrue(f.store.commits.isEmpty()); assertTrue(f.calls.isEmpty())
    }

    @Test fun mismatchedOriginCannotReachTheConfiguredDraftHook() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); var called = 0
        val s = session(f, hooks(gate = { _, _ -> called++; ExecutionDecision.Ready }))
        val original = intent("createPostDraft")
        value(s.commands.enqueue(f.lease, CommandIntent(COMMAND, PLAN, original.call)))
        val blocked = value(s.commands.dispatchConfirmed(f.lease, COMMAND))!!
        assertEquals(CommandIssue.AUTH_REQUIRED, blocked.issue); assertEquals(0, blocked.attempts)
        assertEquals(0, called); assertTrue(f.calls.isEmpty())
    }

    @Test fun failedObserverRetainsOriginalSentAttemptWithoutReceiptPromotion() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); f.exchange = { PortResult.Value(reply("deletePostDraft")) }
        val s = session(f, hooks(observer = { _, _, _ -> PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }))
        value(s.commands.enqueue(f.lease, intent("deletePostDraft")))
        assertEquals(FailureReason.OUTCOME_UNKNOWN,
            assertIs<PortResult.Failure>(s.commands.dispatchConfirmed(f.lease, COMMAND)).reason)
        assertEquals(1, f.calls.size); assertEquals(CommandPhase.IN_FLIGHT, value(s.commands.command(f.lease, COMMAND))!!.phase)
        assertNull(value(s.commands.receipt(f.lease, COMMAND)))
        assertEquals("\"9007199254740993\"", value(s.commands.intent(f.lease, COMMAND))!!.call.ifMatch)
    }

    @Test fun cancelledDraftGateDoesNotClaimSendOrPersistAnOutcome() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler))
        val s = session(f, hooks(gate = { _, _ -> currentCoroutineContext().cancel(); ExecutionDecision.Ready }))
        value(s.commands.enqueue(f.lease, intent("createPostDraft"))); val writes = f.store.commits.size
        val task = async { s.commands.dispatchConfirmed(f.lease, COMMAND) }; task.join(); assertTrue(task.isCancelled)
        assertEquals(writes, f.store.commits.size); assertTrue(f.calls.isEmpty())
        assertEquals(CommandPhase.AWAITING_CONFIRMATION, value(s.commands.command(f.lease, COMMAND))!!.phase)
    }

    @Test fun cancelledDraftObserverDoesNotAcknowledgeTheReceivedDelete() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); f.exchange = { PortResult.Value(reply("deletePostDraft")) }
        val s = session(f, hooks(observer = { _, _, _ -> currentCoroutineContext().cancel(); PortResult.Value(Unit) }))
        value(s.commands.enqueue(f.lease, intent("deletePostDraft")))
        val task = async { s.commands.dispatchConfirmed(f.lease, COMMAND) }; task.join(); assertTrue(task.isCancelled)
        assertEquals(1, f.calls.size); assertEquals(CommandPhase.IN_FLIGHT, value(s.commands.command(f.lease, COMMAND))!!.phase)
        assertNull(value(s.commands.receipt(f.lease, COMMAND)))
    }

    @Test fun failedClaimAcknowledgementCannotBeBypassedByReadyDraftHook() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); val s = session(f, hooks())
        value(s.commands.enqueue(f.lease, intent("updatePostDraft")))
        f.store.receipt = { PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
        assertEquals(FailureReason.OUTCOME_UNKNOWN,
            assertIs<PortResult.Failure>(s.commands.dispatchConfirmed(f.lease, COMMAND)).reason)
        assertTrue(f.calls.isEmpty()); assertEquals(CommandPhase.IN_FLIGHT, value(s.commands.command(f.lease, COMMAND))!!.phase)
        assertNull(value(s.commands.receipt(f.lease, COMMAND)))
    }

    @Test fun draftHooksPreserveConstructionWithoutIoAndExactCompositionIdentity() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); val s = session(f, hooks())
        assertTrue(s.matchesComposition(f.scope, f.store, f.boundary, ORIGIN))
        assertFalse(s.matchesComposition(f.scope, object : PrivateStateStore by f.store {}, f.boundary, ORIGIN))
        assertFalse(s.matchesComposition(f.scope, f.store, SessionBoundary(), ORIGIN))
        assertFalse(s.matchesComposition(StorageScope("other", ActorKind.ACCOUNT, "synthetic-owner"), f.store, f.boundary, ORIGIN))
        assertFalse(s.matchesComposition(f.scope, f.store, f.boundary, PLAN))
        assertTrue(f.store.reads.isEmpty()); assertTrue(f.store.commits.isEmpty()); assertTrue(f.calls.isEmpty())
    }

    private fun session(f: KitchenReliabilityFixture, hook: PostDraftCommandHooks? = null) = PrivateKitchenSession(
        f.scope, f.store, f.boundary, f.dispatcher, EpochClock { 1_800_000_000_000L }, f.transport, ORIGIN,
        postDraftCommandHooks = hook)
    private fun hooks(gate: suspend (SessionLease, CommandIntent) -> ExecutionDecision = { _, _ -> ExecutionDecision.Ready },
        observer: suspend (SessionLease, CommandIntent, ApiReply) -> PortResult<Unit> = { _, _, _ -> PortResult.Value(Unit) }) =
        object : PostDraftCommandHooks {
            override suspend fun executionDecision(lease: SessionLease, intent: CommandIntent) = gate(lease, intent)
            override suspend fun observeReply(lease: SessionLease, intent: CommandIntent, reply: ApiReply) = observer(lease, intent, reply)
        }
    private fun intent(op: String) = CommandIntent(COMMAND, ORIGIN, when (op) {
        "createPostDraft" -> ApiCall(op, body = bytes("""{ "clientDraftId":"$PLAN", "caption":"Synthetic original caption" }"""), idempotencyKey = SecretText(COMMAND))
        "updatePostDraft" -> ApiCall(op, pathParameters = mapOf("draftId" to ID), body = bytes("""{ "caption":"Synthetic original caption" }"""),
            ifMatch = "\"9007199254740993\"", idempotencyKey = SecretText(COMMAND))
        else -> ApiCall(op, pathParameters = mapOf("draftId" to ID), ifMatch = "\"9007199254740993\"", idempotencyKey = SecretText(COMMAND))
    })
    private fun reply(op: String) = if (op == "deletePostDraft") ApiReply(204, null) else ApiReply(if (op == "createPostDraft") 201 else 200,
        bytes("""{"id":"$ID","version":9007199254740994,"clientDraftId":"$PLAN","status":"draft","caption":"Synthetic original caption","mediaIds":[],"keepOnPlate":false,"createdAt":"2026-09-14T00:00:00Z","updatedAt":"2026-09-14T00:00:00Z","expiresAt":"2026-10-14T00:00:00Z"}"""),
        etag = "\"9007199254740994\"", contentType = "application/json")
    private fun <T> value(result: PortResult<T>) = assertIs<PortResult.Value<T>>(result).value
    private companion object { val OPERATIONS = listOf("createPostDraft", "updatePostDraft", "deletePostDraft") }
}
