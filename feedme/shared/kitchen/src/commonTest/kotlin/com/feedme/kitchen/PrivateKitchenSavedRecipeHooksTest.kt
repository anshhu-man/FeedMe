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

/** Real journal/repositories over synthetic common ports; no copy-rights or native-storage claim. */
@OptIn(ExperimentalCoroutinesApi::class)
class PrivateKitchenSavedRecipeHooksTest {
    @Test fun missingCookbookHooksDenyBothSaveAndExplicitlyConfirmedDelete() = runTest {
        for (delete in listOf(false, true)) {
            val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); val s = session(f)
            value(s.commands.enqueue(f.lease, intent(delete)))
            val view = value(if (delete) s.commands.dispatchConfirmed(f.lease, COMMAND) else s.commands.dispatchAutomatic(f.lease, COMMAND))!!
            assertEquals(CommandIssue.NOT_CONFIGURED, view.issue); assertEquals(0, view.attempts); assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun cookbookHooksSeeExactOriginalSaveAndDeleteOnlyAfterTheirQueuePolicy() = runTest {
        for (delete in listOf(false, true)) {
            val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); val gated = mutableListOf<CommandIntent>()
            val observed = mutableListOf<CommandIntent>()
            f.exchange = { PortResult.Value(if (delete) ApiReply(204, null) else rejected()) }
            val s = session(f, hooks(gate = { lease, original ->
                assertSame(f.lease, lease); gated += original; ExecutionDecision.Ready
            }, observer = { lease, original, _ -> assertSame(f.lease, lease); observed += original; PortResult.Value(Unit) }))
            val original = intent(delete); value(s.commands.enqueue(f.lease, original))
            if (delete) { assertNull(value(s.commands.dispatchNext(f.lease))); assertTrue(gated.isEmpty()) }
            value(if (delete) s.commands.dispatchConfirmed(f.lease, COMMAND) else s.commands.dispatchAutomatic(f.lease, COMMAND))
            assertEquals(1, gated.size); assertEquals(1, observed.size); assertEquals(1, f.calls.size)
            for (seen in gated + observed) {
                assertEquals(original.commandId, seen.commandId); assertEquals(ORIGIN, seen.originBinding)
                assertEquals(original.call.operationId, seen.call.operationId); assertEquals(original.call.pathParameters, seen.call.pathParameters)
                assertEquals(original.call.ifMatch, seen.call.ifMatch); assertEquals(COMMAND, seen.call.idempotencyKey!!.use { it })
                assertContentEquals(original.call.body?.copyForCodec(), seen.call.body?.copyForCodec())
            }
            assertEquals(if (delete) CommandPhase.RECEIPT_READY else CommandPhase.NEEDS_RESOLUTION, value(s.commands.command(f.lease, COMMAND))!!.phase)
        }
    }

    @Test fun cookbookHookCannotAdmitCreateCookingPreferencesOrCollectionOperations() = runTest {
        for (operation in listOf("createCookSession", "updatePreferences", "createCollection")) {
            val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); var called = 0
            val s = session(f, hooks(gate = { _, _ -> called++; ExecutionDecision.Ready }, observer = { _, _, _ -> called++; PortResult.Value(Unit) }))
            val call = when (operation) {
                "createCookSession" -> ApiCall(operation, body = bytes("""{"planId":"$PLAN","deviceSequence":0}"""), idempotencyKey = SecretText(COMMAND))
                "updatePreferences" -> ApiCall(operation, body = bytes("{}"), idempotencyKey = SecretText(COMMAND), ifMatch = "\"1\"")
                else -> ApiCall(operation, body = bytes("""{"name":"Synthetic collection"}"""), idempotencyKey = SecretText(COMMAND))
            }
            value(s.commands.enqueue(f.lease, CommandIntent(COMMAND, ORIGIN, call)))
            val view = value(if (operation == "createCookSession") s.commands.dispatchConfirmed(f.lease, COMMAND) else s.commands.dispatchAutomatic(f.lease, COMMAND))!!
            assertEquals(CommandIssue.NOT_CONFIGURED, view.issue); assertEquals(0, called); assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun observerFailureRetainsSentAttemptWithoutReceiptPromotion() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); f.exchange = { PortResult.Value(ApiReply(204, null)) }
        val s = session(f, hooks(observer = { _, _, _ -> PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }))
        value(s.commands.enqueue(f.lease, intent(true)))
        assertEquals(FailureReason.OUTCOME_UNKNOWN, assertIs<PortResult.Failure>(s.commands.dispatchConfirmed(f.lease, COMMAND)).reason)
        assertEquals(1, f.calls.size); assertEquals(CommandPhase.IN_FLIGHT, value(s.commands.command(f.lease, COMMAND))!!.phase)
        assertNull(value(s.commands.receipt(f.lease, COMMAND)))
    }

    @Test fun cancelledCookbookGateCannotClaimOrSendAndDoesNotBecomeAReceipt() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler))
        val s = session(f, hooks(gate = { _, _ -> currentCoroutineContext().cancel(); ExecutionDecision.Ready }))
        value(s.commands.enqueue(f.lease, intent())); val before = f.store.commits.size
        val job = async { s.commands.dispatchAutomatic(f.lease, COMMAND) }; job.join(); assertTrue(job.isCancelled)
        assertEquals(before, f.store.commits.size); assertTrue(f.calls.isEmpty())
        assertEquals(CommandPhase.READY, value(s.commands.command(f.lease, COMMAND))!!.phase)
    }

    @Test fun cancelledObserverCannotRecordSuccessfulDeleteOutcome() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); f.exchange = { PortResult.Value(ApiReply(204, null)) }
        val s = session(f, hooks(observer = { _, _, _ -> currentCoroutineContext().cancel(); PortResult.Value(Unit) }))
        value(s.commands.enqueue(f.lease, intent(true)))
        val job = async { s.commands.dispatchConfirmed(f.lease, COMMAND) }; job.join(); assertTrue(job.isCancelled)
        assertEquals(CommandPhase.IN_FLIGHT, value(s.commands.command(f.lease, COMMAND))!!.phase)
        assertNull(value(s.commands.receipt(f.lease, COMMAND))); assertEquals(1, f.calls.size)
    }

    @Test fun cookbookHooksDoNotBypassFailedClaimAcknowledgement() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); val s = session(f, hooks())
        value(s.commands.enqueue(f.lease, intent()))
        f.store.receipt = { PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
        assertEquals(FailureReason.OUTCOME_UNKNOWN, assertIs<PortResult.Failure>(s.commands.dispatchAutomatic(f.lease, COMMAND)).reason)
        assertTrue(f.calls.isEmpty()); assertEquals(CommandPhase.IN_FLIGHT, value(s.commands.command(f.lease, COMMAND))!!.phase)
    }

    @Test fun optionalHooksDoNotWeakenExactStoreBoundaryScopeOrOriginPairing() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); val s = session(f, hooks())
        assertTrue(s.matchesComposition(f.scope, f.store, f.boundary, ORIGIN))
        val wrapper = object : PrivateStateStore by f.store {}
        assertFalse(s.matchesComposition(f.scope, wrapper, f.boundary, ORIGIN))
        assertFalse(s.matchesComposition(f.scope, f.store, SessionBoundary(), ORIGIN))
        assertFalse(s.matchesComposition(StorageScope("other", ActorKind.ACCOUNT, "synthetic-owner"), f.store, f.boundary, ORIGIN))
        assertFalse(s.matchesComposition(f.scope, f.store, f.boundary, PLAN))
        assertTrue(f.store.reads.isEmpty()); assertTrue(f.store.commits.isEmpty()); assertTrue(f.calls.isEmpty())
    }

    private fun session(f: KitchenReliabilityFixture, hook: SavedRecipeCommandHooks? = null) = PrivateKitchenSession(
        f.scope, f.store, f.boundary, f.dispatcher, EpochClock { 1_800_000_000_000L }, f.transport, ORIGIN,
        savedRecipeCommandHooks = hook)
    private fun intent(delete: Boolean = false) = CommandIntent(COMMAND, ORIGIN, if (delete)
        ApiCall("deleteSavedRecipe", pathParameters = mapOf("savedRecipeId" to ID), ifMatch = "\"1\"", idempotencyKey = SecretText(COMMAND))
        else ApiCall("saveRecipe", body = bytes("""{"planId":"$PLAN"}"""), idempotencyKey = SecretText(COMMAND)))
    private fun hooks(gate: suspend (SessionLease, CommandIntent) -> ExecutionDecision = { _, _ -> ExecutionDecision.Ready },
        observer: suspend (SessionLease, CommandIntent, ApiReply) -> PortResult<Unit> = { _, _, _ -> PortResult.Value(Unit) }) =
        object : SavedRecipeCommandHooks {
            override suspend fun executionDecision(lease: SessionLease, intent: CommandIntent) = gate(lease, intent)
            override suspend fun observeReply(lease: SessionLease, intent: CommandIntent, reply: ApiReply) = observer(lease, intent, reply)
        }
    private fun rejected() = ApiReply(422, bytes("""{"type":"about:blank","title":"Synthetic input rejection","status":422,"code":"INPUT_INVALID","traceId":"fixture-trace"}"""), contentType = "application/problem+json")
    private fun <T> value(result: PortResult<T>) = assertIs<PortResult.Value<T>>(result).value
}
