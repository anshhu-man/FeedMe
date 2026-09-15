package com.feedme.kitchen

import com.feedme.core.ports.*
import com.feedme.kitchen.KitchenReliabilityFixture.Companion.COMMAND
import com.feedme.kitchen.KitchenReliabilityFixture.Companion.ORIGIN
import com.feedme.kitchen.KitchenReliabilityFixture.Companion.PLAN
import com.feedme.kitchen.KitchenReliabilityFixture.Companion.bytes
import com.feedme.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateKitchenSessionReliabilityTest {
    @Test fun missingCreateGateFailsClosedEvenAfterExplicitUserConfirmation() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); val session = session(f)
        value(session.commands.enqueue(f.lease, start()))
        val blocked = assertNotNull(value(session.commands.dispatchConfirmed(f.lease, COMMAND)))
        assertEquals(CommandIssue.NOT_CONFIGURED, blocked.issue)
        assertEquals(0, blocked.attempts); assertTrue(f.calls.isEmpty())
    }

    @Test fun optionalGateReceivesOnlyExactCreateIntentAndDoesNotBypassExplicitConfirmation() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); val gated = mutableListOf<CommandIntent>()
        val session = session(f, CommandExecutionGate { lease, intent ->
            assertSame(f.lease, lease); gated += intent; ExecutionDecision.Wait(CommandIssue.DOMAIN_RECHECK_REQUIRED)
        })
        value(session.commands.enqueue(f.lease, start()))
        assertNull(value(session.commands.dispatchNext(f.lease))); assertTrue(gated.isEmpty())
        val blocked = assertNotNull(value(session.commands.dispatchConfirmed(f.lease, COMMAND)))
        assertEquals(CommandIssue.DOMAIN_RECHECK_REQUIRED, blocked.issue)
        assertEquals(1, gated.size); assertEquals(ORIGIN, gated.single().originBinding)
        assertEquals(COMMAND, gated.single().call.idempotencyKey!!.use { it })
        assertContentEquals(start().call.body!!.copyForCodec(), gated.single().call.body!!.copyForCodec())
        assertTrue(f.calls.isEmpty())
    }

    @Test fun createHookCannotGrantUnrelatedFeatureCommands() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); var gates = 0
        val session = session(f, CommandExecutionGate { _, _ -> gates++; ExecutionDecision.Ready })
        val unrelated = CommandIntent(COMMAND, ORIGIN, ApiCall("updatePreferences", body = bytes("{}"),
            idempotencyKey = SecretText(COMMAND), ifMatch = "\"1\""))
        value(session.commands.enqueue(f.lease, unrelated))
        val blocked = assertNotNull(value(session.commands.dispatchNext(f.lease)))
        assertEquals(CommandIssue.NOT_CONFIGURED, blocked.issue); assertEquals(0, gates); assertTrue(f.calls.isEmpty())
    }

    @Test fun queueEnqueueCannotAcknowledgeWrongMapOrMissingDomainReadback() = runTest {
        for (wrongMap in listOf(true, false)) {
            val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); val session = session(f)
            if (wrongMap) f.store.receipt = { PortResult.Value(emptyMap()) } else f.store.write = false
            assertEquals(FailureReason.STORAGE_FAILURE, assertIs<PortResult.Failure>(session.commands.enqueue(f.lease, start())).reason)
            assertEquals(1, f.store.commits.size); assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun failedInFlightAcknowledgementNeverHandsCommandToTransport() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); val session = session(f,
            CommandExecutionGate { _, _ -> ExecutionDecision.Ready })
        value(session.commands.enqueue(f.lease, start()))
        f.store.receipt = { PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
        assertEquals(FailureReason.OUTCOME_UNKNOWN,
            assertIs<PortResult.Failure>(session.commands.dispatchConfirmed(f.lease, COMMAND)).reason)
        assertTrue(f.calls.isEmpty())
        assertEquals(CommandPhase.IN_FLIGHT, value(session.commands.command(f.lease, COMMAND))!!.phase)
    }

    @Test fun cancellationInCreateGateCannotReachNativeClaimOrTransport() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); val session = session(f,
            CommandExecutionGate { _, _ -> currentCoroutineContext().cancel(); ExecutionDecision.Ready })
        value(session.commands.enqueue(f.lease, start())); val before = f.store.commits.size
        val pending = async { session.commands.dispatchConfirmed(f.lease, COMMAND) }
        pending.join(); assertTrue(pending.isCancelled)
        assertEquals(before, f.store.commits.size); assertTrue(f.calls.isEmpty())
    }

    private fun session(f: KitchenReliabilityFixture, gate: CommandExecutionGate? = null) = PrivateKitchenSession(
        f.scope, f.store, f.boundary, f.dispatcher, EpochClock { 1_800_000_000_000L }, f.transport, ORIGIN, gate)
    private fun start() = CommandIntent(COMMAND, ORIGIN, ApiCall("createCookSession", body = bytes("""{"planId":"$PLAN","deviceSequence":0}"""),
        idempotencyKey = SecretText(COMMAND)))
    private fun <T> value(result: PortResult<T>) = assertIs<PortResult.Value<T>>(result).value
}
