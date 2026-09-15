package com.feedme.storage

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireField
import com.feedme.core.ports.*
import com.feedme.session.*
import com.feedme.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Actual retained runtime, leased store, encrypted bundled SQLite and DurableCommandQueue.
 * Only credentials/JCA vault, execution prerequisites and transport are synthetic fixtures.
 * Controlled connection/runtime reopening is not a process kill, native vault or HTTP proof.
 * Faults run at actual SQLite COMMIT, never by substituting a successful persistence result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TargetedCommandRecoveryStorageTest {
    @Test fun exactDirectAndSavedOriginalRecoveryAcrossRuntimeReopenLeavesOtherLaneAndPrivateDraftUnchanged() = runTest {
        for (saved in listOf(false, true)) fixture { f ->
            val original = f.publication(saved)
            val pending = f.orphan(original)
            val originalMetadata = f.record(metadata(ID))
            f.orphan(f.cooking())
            value(f.access.store.commit(f.access.scope, listOf(StoreMutation.Put(PRIVATE_DRAFT, null, 1, bytes(MARKER)))))
            val protected = f.records(listOf(request(ID), metadata(COOK), request(COOK), PRIVATE_DRAFT))
            val oldAccess = f.access; val oldQueue = f.queue; val origin = f.access.originBinding
            val created = f.rt.credentials.creates; val keys = f.rt.dataVault.keys.keys.toSet()
            f.reopen()
            // Verified session restore deliberately advances its control acknowledgement.
            // Only the following queue recovery must leave those current owners untouched.
            val control = value(f.rt.control.read())!!; val work = value(f.rt.workControl.read())!!
            assertNotSame(oldAccess.lease, f.access.lease); assertEquals(origin, f.access.originBinding)
            rejected(FailureReason.STALE_SESSION, oldQueue.recoverInterrupted(oldAccess.lease, ID, pending.localRevision))
            val calls = f.calls.size; val gates = f.gates
            val recovered = value(f.queue.recoverInterrupted(f.access.lease, ID, pending.localRevision))
            assertEquals(CommandPhase.AWAITING_CONFIRMATION, recovered.phase)
            assertEquals(CommandIssue.OUTCOME_UNKNOWN, recovered.issue); assertEquals(1, recovered.attempts)
            assertEquals(pending.localRevision + 1, recovered.localRevision)
            assertOriginalMetadata(originalMetadata, f.record(metadata(ID)))
            assertEquals(calls, f.calls.size); assertEquals(gates, f.gates)
            f.assertRecords(protected); assertEquals(CommandPhase.IN_FLIGHT, f.command(COOK).phase)
            assertIntent(original, value(f.queue.intent(f.access.lease, ID))!!)
            assertEquals(control.revision, value(f.rt.control.read())!!.revision)
            assertContentEquals(control.payload.copyForCodec(), value(f.rt.control.read())!!.payload.copyForCodec())
            assertEquals(work.revision, value(f.rt.workControl.read())!!.revision)
            assertContentEquals(work.payload.copyForCodec(), value(f.rt.workControl.read())!!.payload.copyForCodec())
            assertEquals(created, f.rt.credentials.creates); assertEquals(keys, f.rt.dataVault.keys.keys.toSet())
            assertTrue(f.rt.cancelled.isEmpty())
            f.time = recovered.retryAtMillis
            value(f.queue.dispatchConfirmed(f.access.lease, ID))
            assertEquals(calls + 1, f.calls.size); assertCall(original.call, f.calls.last())
            assertEquals(2, f.command(ID).attempts); f.assertRecords(protected)
            f.rt.closeRuntimeAndStores()
            f.rt.assertNoPlaintext(listOf(MARKER, ID, COOK, ROOT, DRAFT, origin, "publishPost", "feedme.command.request"))
        }
    }

    @Test fun failureBeforeActualRecoveryCommitRollsBackBothMetadataAndIndexAcrossReopen() = runTest { fixture { f ->
        val original = f.publication(true); val pending = f.orphan(original)
        val before = f.records(listOf(INDEX, metadata(ID), request(ID))); f.failBefore = true
        rejected(FailureReason.STORAGE_FAILURE, f.queue.recoverInterrupted(f.access.lease, ID, pending.localRevision))
        f.assertRecords(before); assertEquals(0, f.successfulRecoveryCommits)
        f.reopen(); f.assertRecords(before)
        val recovered = value(f.queue.recoverInterrupted(f.access.lease, ID, pending.localRevision))
        assertEquals(pending.localRevision + 1, recovered.localRevision); assertEquals(1, f.successfulRecoveryCommits)
        assertIntent(original, value(f.queue.intent(f.access.lease, ID))!!); assertEquals(1, f.calls.size)
    } }

    @Test fun lostActualRecoveryCommitReplyRetainsOneChangedMetadataRevisionAndCannotRepeatTheWrite() = runTest { fixture { f ->
        val original = f.publication(true); val pending = f.orphan(original)
        val originalRequest = f.record(request(ID)); val index = f.record(INDEX); f.failAfter = true
        rejected(FailureReason.OUTCOME_UNKNOWN, f.queue.recoverInterrupted(f.access.lease, ID, pending.localRevision))
        assertEquals(1, f.successfulRecoveryCommits)
        val observed = f.command(ID); assertEquals(CommandPhase.AWAITING_CONFIRMATION, observed.phase)
        assertEquals(pending.localRevision + 1, observed.localRevision); assertEquals(index.revision + 1, f.record(INDEX).revision)
        assertRecord(originalRequest, f.record(request(ID)))
        f.reopen(); assertView(observed, f.command(ID))
        rejected(FailureReason.CONFLICT, f.queue.recoverInterrupted(f.access.lease, ID, pending.localRevision))
        assertEquals(0, f.successfulRecoveryCommits); assertView(observed, f.command(ID))
        assertIntent(original, value(f.queue.intent(f.access.lease, ID))!!); assertEquals(1, f.calls.size)
    } }

    @Test fun competingActualQueueInstancesCannotBothAcknowledgeTheSameRecoveryRevision() = runTest { fixture { f ->
        val pending = f.orphan(f.publication(false)); val sibling = f.newQueue()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.rt.afterControlRead = { f.rt.afterControlRead = {}; entered.complete(Unit); release.await() }
        val first = async { f.queue.recoverInterrupted(f.access.lease, ID, pending.localRevision) }
        entered.await()
        val second = value(sibling.recoverInterrupted(f.access.lease, ID, pending.localRevision))
        release.complete(Unit); rejected(FailureReason.CONFLICT, first.await())
        assertEquals(pending.localRevision + 1, second.localRevision); assertView(second, f.command(ID))
        assertEquals(1, f.successfulRecoveryCommits); assertEquals(1, f.calls.size)
    } }

    @Test fun liveTransportClaimOnActualStorePreventsRecoveryUntilThatAttemptEnds() = runTest { fixture { f ->
        val original = f.publication(false); value(f.queue.enqueue(f.access.lease, original))
        val entered = CompletableDeferred<Unit>(); f.handler = { entered.complete(Unit); awaitCancellation() }
        val dispatch = async { f.queue.dispatchConfirmed(f.access.lease, ID) }; entered.await()
        val pending = f.command(ID); assertEquals(CommandPhase.IN_FLIGHT, pending.phase)
        val before = f.records(listOf(INDEX, metadata(ID), request(ID))); f.resetCounts()
        rejected(FailureReason.CONFLICT, f.newQueue().recoverInterrupted(f.access.lease, ID, pending.localRevision))
        f.assertRecords(before); assertEquals(0, f.successfulRecoveryCommits)
        dispatch.cancelAndJoin()
        assertEquals(CommandPhase.AWAITING_CONFIRMATION,
            value(f.newQueue().recoverInterrupted(f.access.lease, ID, pending.localRevision)).phase)
        assertEquals(1, f.successfulRecoveryCommits); assertEquals(1, f.calls.size)
    } }

    @Test fun callerCancellationAfterRealRecoveryCommitCannotReturnAcknowledgementOrRepeatTheWrite() = runTest { fixture { f ->
        val pending = f.orphan(f.publication(true)); var delivered = false
        lateinit var recovery: Deferred<PortResult<CommandView>>
        f.afterCommit = { recovery.cancel() }
        recovery = async { f.queue.recoverInterrupted(f.access.lease, ID, pending.localRevision).also { delivered = true } }
        recovery.join(); assertTrue(recovery.isCancelled); assertFalse(delivered); assertEquals(1, f.successfulRecoveryCommits)
        f.afterCommit = {}
        val observed = f.command(ID); assertEquals(pending.localRevision + 1, observed.localRevision)
        f.reopen(); assertView(observed, f.command(ID))
        rejected(FailureReason.CONFLICT, f.queue.recoverInterrupted(f.access.lease, ID, pending.localRevision))
        assertEquals(0, f.successfulRecoveryCommits); assertEquals(1, f.calls.size)
    } }

    @Test fun sessionInvalidationDuringRealLeasedReadPreventsRecoveryWritesAndPreservesOriginal() = runTest { fixture { f ->
        val original = f.publication(false); val pending = f.orphan(original)
        val before = f.records(listOf(INDEX, metadata(ID), request(ID)))
        f.rt.afterControlRead = { f.rt.afterControlRead = {}; f.rt.boundary.clear() }
        rejected(FailureReason.STALE_SESSION, f.queue.recoverInterrupted(f.access.lease, ID, pending.localRevision))
        assertEquals(0, f.successfulRecoveryCommits); assertEquals(1, f.calls.size)
        f.reopen(); f.assertRecords(before)
        assertIntent(original, value(f.queue.intent(f.access.lease, ID))!!)
        assertEquals(CommandPhase.AWAITING_CONFIRMATION,
            value(f.queue.recoverInterrupted(f.access.lease, ID, pending.localRevision)).phase)
    } }

    @Test fun expiredOrRolledBackClockAfterReopenCannotRenewReplayAgeOrRefundAttempts() = runTest {
        for (expired in listOf(false, true)) fixture { f ->
            val original = f.publication(true); val pending = f.orphan(original)
            val body = f.record(request(ID)); val originalMetadata = f.record(metadata(ID)); f.reopen()
            f.time = if (expired) START + 6L * 86_400_000 else START - 1
            val recovered = value(f.queue.recoverInterrupted(f.access.lease, ID, pending.localRevision))
            assertEquals(CommandPhase.NEEDS_RESOLUTION, recovered.phase)
            assertEquals(if (expired) CommandIssue.RECONCILIATION_REQUIRED else CommandIssue.CLOCK_CHANGED, recovered.issue)
            assertEquals(1, recovered.attempts); assertEquals(1, f.calls.size)
            assertOriginalMetadata(originalMetadata, f.record(metadata(ID)))
            assertRecord(body, f.record(request(ID))); assertIntent(original, value(f.queue.intent(f.access.lease, ID))!!)
            assertNull(value(f.queue.dispatchConfirmed(f.access.lease, ID))); assertEquals(1, f.calls.size)
        }
    }

    private suspend fun TestScope.fixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture(StandardTestDispatcher(testScheduler), this)
        try { f.start(); block(f) } finally { f.rt.close() }
    }

    private class Fixture(private val dispatcher: CoroutineDispatcher, private val tasks: CoroutineScope) {
        val rt = PrivateSessionRuntimeTest.RuntimeFixture(dispatcher,
            NativeWorkExecutionPolicy { _, _, _, _ -> PortResult.Value(false) })
        lateinit var access: PrivateSessionAccess
        lateinit var queue: DurableCommandQueue
        var time = START; var gates = 0; val calls = mutableListOf<ApiCall>()
        var handler: suspend (ApiCall) -> PortResult<ApiReply> = { PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
        var failBefore = false; var failAfter = false; var successfulRecoveryCommits = 0
        var afterCommit: () -> Unit = {}
        suspend fun start() { rt.start(); access = rt.create(); queue = newQueue(); installHooks() }
        fun newQueue() = DurableCommandQueue(access.scope, access.store, rt.boundary, dispatcher, EpochClock { time },
            CommandExecutionGate { lease, intent ->
                assertSame(access.lease, lease); assertEquals(access.originBinding, intent.originBinding)
                gates++; ExecutionDecision.Ready
            }, object : AccountTransport {
                override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                    assertSame(access.lease, lease); calls += call; return handler(call)
                }
            })
        private fun installHooks() {
            rt.dataConnection.beforeRecordCommit = { if (failBefore) { failBefore = false; error("Synthetic failure before actual recovery COMMIT") } }
            rt.dataConnection.afterRecordCommit = {
                successfulRecoveryCommits++
                if (failAfter) { failAfter = false; error("Synthetic lost reply after actual recovery COMMIT") }
                afterCommit()
            }
        }
        fun resetCounts() { successfulRecoveryCommits = 0 }
        suspend fun reopen() {
            rt.reopen(); assertEquals(PrivateSessionPhase.RESTORE_REQUIRED, value(rt.runtime.recover()))
            access = value(rt.runtime.restore()); queue = newQueue(); resetCounts(); installHooks()
        }
        suspend fun orphan(intent: CommandIntent): CommandView {
            value(queue.enqueue(access.lease, intent))
            val entered = CompletableDeferred<Unit>(); handler = { entered.complete(Unit); awaitCancellation() }
            val dispatch = tasks.async { queue.dispatchConfirmed(access.lease, intent.commandId) }
            entered.await(); dispatch.cancelAndJoin()
            handler = { PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }; resetCounts()
            return command(intent.commandId).also { assertEquals(CommandPhase.IN_FLIGHT, it.phase) }
        }
        suspend fun command(id: String) = value(queue.command(access.lease, id))!!
        suspend fun record(key: RecordKey) = value(access.store.read(access.scope, key))!!
        suspend fun records(keys: List<RecordKey>) = keys.associateWith { record(it) }
        suspend fun assertRecords(before: Map<RecordKey, PrivateRecord>) { before.forEach { (key, expected) -> assertRecord(expected, record(key)) } }
        fun publication(saved: Boolean) = CommandIntent(ID, access.originBinding, ApiCall("publishPost", body = bytes(""" {
            "clientDraftId":"$ROOT", "caption":"$MARKER", "mediaIds":[], "audience":{"kind":"self","circleIds":[]},
            "keepOnPlate":false, "allowRecipeSaves":false, "saveDisclosureVersion":"synthetic-disclosure-v1"${if (saved) ", \"draftId\":\"$DRAFT\", \"draftVersion\":9007199254740993" else ""}
        } 
"""), idempotencyKey = SecretText(ID)))
        fun cooking() = CommandIntent(COOK, access.originBinding, ApiCall("updateCookSession", mapOf("sessionId" to DRAFT),
            body = bytes(" { \"deviceSequence\": 1e0 } "), idempotencyKey = SecretText(COOK), ifMatch = "\"7\""))
    }

    private companion object {
        const val ID = "abcdefab-1000-4000-8000-000000000001"
        const val COOK = "abcdefab-1000-4000-8000-000000000002"
        const val ROOT = "abcdefab-1000-4000-8000-000000000003"
        const val DRAFT = "abcdefab-1000-4000-8000-000000000004"
        const val MARKER = "private-targeted-recovery-caption-7fa304"
        const val START = 1_800_000_000_000L
        val INDEX = RecordKey("feedme.command.index", "v1")
        val PRIVATE_DRAFT = RecordKey("test-only.unrelated-private-draft", "draft-7fa304")
        fun metadata(id: String) = RecordKey("feedme.command.metadata", id)
        fun request(id: String) = RecordKey("feedme.command.request", id)
        fun bytes(value: String) = PrivateBytes(value.encodeToByteArray())
        fun <T> value(result: PortResult<T>): T = when (result) { is PortResult.Value -> result.value; is PortResult.Failure -> fail("Expected success, got ${result.reason}") }
        fun rejected(reason: FailureReason, result: PortResult<*>) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
        fun assertRecord(expected: PrivateRecord, actual: PrivateRecord) {
            assertEquals(expected.revision, actual.revision); assertEquals(expected.schemaVersion, actual.schemaVersion)
            assertContentEquals(expected.payload.copyForCodec(), actual.payload.copyForCodec())
        }
        fun assertView(expected: CommandView, actual: CommandView) {
            assertEquals(expected.commandId, actual.commandId); assertEquals(expected.operationId, actual.operationId)
            assertEquals(expected.phase, actual.phase); assertEquals(expected.attempts, actual.attempts)
            assertEquals(expected.issue, actual.issue); assertEquals(expected.retryAtMillis, actual.retryAtMillis)
            assertEquals(expected.localRevision, actual.localRevision)
        }
        fun assertOriginalMetadata(expected: PrivateRecord, actual: PrivateRecord) {
            val before = WireDocument.decode(expected.payload.copyForCodec())
            val after = WireDocument.decode(actual.payload.copyForCodec())
            for (name in listOf("id", "originBinding", "scopeEnvironment", "scopeActorKind", "scopeActorId",
                "request", "dependencies", "createdAt", "firstAttemptAt", "attempts")) {
                val prior = assertIs<WireField.Value<WireDocument>>(before.field(name)).value
                val current = assertIs<WireField.Value<WireDocument>>(after.field(name)).value
                assertContentEquals(prior.encodeUtf8(), current.encodeUtf8(), "Changed original metadata: $name")
            }
        }
        fun assertIntent(expected: CommandIntent, actual: CommandIntent) {
            assertEquals(expected.commandId, actual.commandId); assertEquals(expected.originBinding, actual.originBinding)
            assertEquals(expected.dependencyCommandIds, actual.dependencyCommandIds); assertCall(expected.call, actual.call)
        }
        fun assertCall(expected: ApiCall, actual: ApiCall) {
            assertEquals(expected.operationId, actual.operationId); assertEquals(expected.pathParameters, actual.pathParameters)
            assertEquals(expected.queryParameters, actual.queryParameters); assertEquals(expected.ifMatch, actual.ifMatch)
            assertEquals(expected.idempotencyKey!!.use { it }, actual.idempotencyKey!!.use { it })
            assertContentEquals(expected.body!!.copyForCodec(), actual.body!!.copyForCodec())
        }
    }
}
