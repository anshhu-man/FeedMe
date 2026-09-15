package com.feedme.sync

import com.feedme.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Real DurableCommandQueue + process-claim registry over an explicitly SYNTHETIC atomic CAS
 * store/account transport. No production persistence, principal or publication permission. */
@OptIn(ExperimentalCoroutinesApi::class)
class TargetedCommandRecoveryTest {
    @Test fun exactOrphanedPublicationRetainsOriginalEnvelopeAgeAndAttemptWithoutDispatch() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val original = publication()
        val pending = f.orphan(original); val before = f.metadata(ID)
        val body = f.store.peek(request(ID))!!; val commits = f.store.commits.size
        val gates = f.gateCalls; val sent = f.sent.size; f.now += 100
        val recovered = value(f.otherQueue().recoverInterrupted(f.lease, ID, pending.localRevision))
        val after = f.metadata(ID)
        assertEquals(CommandPhase.AWAITING_CONFIRMATION, recovered.phase); assertEquals(CommandIssue.OUTCOME_UNKNOWN, recovered.issue)
        assertEquals(before.attempts, recovered.attempts); assertEquals(1, recovered.attempts)
        assertEquals(before.createdAt, after.createdAt); assertEquals(before.firstAttemptAt, after.firstAttemptAt)
        assertEquals(before.originBinding, after.originBinding); assertEquals(before.dependencies, after.dependencies)
        assertEquals(before.request, after.request); assertTrue(recovered.retryAtMillis in f.now + 1000..f.now + 1500)
        assertEquals(pending.localRevision + 1, recovered.localRevision)
        assertRecord(body, f.store.peek(request(ID))); assertEquals(commits + 1, f.store.commits.size)
        assertEquals(setOf(metadata(ID), INDEX), f.store.commits.last().map { it.key }.toSet())
        val read = assertNotNull(value(f.queue.intent(f.lease, ID)))
        assertIntent(original, read); assertEquals(gates, f.gateCalls); assertEquals(sent, f.sent.size)
    }

    @Test fun nonTargetOrphanedCookingAndUnattemptedSocialSiblingRemainByteAndRevisionIdentical() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val a = f.orphan(publication())
        f.orphan(cooking(ID2)); value(f.queue.enqueue(f.lease, publication(ID3)))
        val siblingKeys = listOf(metadata(ID2), request(ID2), sequence(), metadata(ID3), request(ID3))
        val before = siblingKeys.associateWith { f.store.peek(it)!! }
        val sent = f.sent.toList(); val gates = f.gateCalls
        value(f.otherQueue().recoverInterrupted(f.lease, ID, a.localRevision))
        siblingKeys.forEach { assertRecord(before.getValue(it), f.store.peek(it)) }
        assertEquals(CommandPhase.IN_FLIGHT, f.metadata(ID2).phase)
        assertEquals(CommandPhase.AWAITING_CONFIRMATION, f.metadata(ID3).phase); assertEquals(0, f.metadata(ID3).attempts)
        assertEquals(sent, f.sent); assertEquals(gates, f.gateCalls)
        assertEquals(listOf(ID, ID2, ID3), JournalCodec.decodeIndex(f.store.peek(INDEX)!!.payload).ids)
    }

    @Test fun dependenciesAndExactSavedBranchLargeVersionSurviveRecovery() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, removal(ID2)))
        f.exchange = { PortResult.Value(ApiReply(204, null)) }
        val receipt = assertNotNull(value(f.queue.dispatchAutomatic(f.lease, ID2)))
        value(f.queue.applyReceipt(f.lease, ID2, receipt.localRevision, emptyList()))
        val original = publication(saved = true, dependencies = listOf(ID2)); val pending = f.orphan(original)
        val parent = f.store.peek(metadata(ID2))!!
        value(f.queue.recoverInterrupted(f.lease, ID, pending.localRevision))
        assertIntent(original, assertNotNull(value(f.queue.intent(f.lease, ID))))
        assertEquals(listOf(ID2), f.metadata(ID).dependencies); assertRecord(parent, f.store.peek(metadata(ID2)))
        assertTrue(f.store.peek(request(ID))!!.payload.copyForCodec().decodeToString().contains("9007199254740993"))
    }

    @Test fun liveTransportClaimCannotBeRecoveredByAnotherQueueInstance() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); value(f.queue.enqueue(f.lease, publication()))
        f.exchange = { awaitCancellation() }
        val job = launch { f.queue.dispatchConfirmed(f.lease, ID) }; runCurrent()
        assertEquals(1, f.sent.size); val current = assertNotNull(value(f.otherQueue().command(f.lease, ID)))
        assertEquals(CommandPhase.IN_FLIGHT, current.phase); val commits = f.store.commits.size
        failure(FailureReason.CONFLICT, f.otherQueue().recoverInterrupted(f.lease, ID, current.localRevision))
        assertEquals(commits, f.store.commits.size)
        job.cancelAndJoin()
        val recovered = value(f.otherQueue().recoverInterrupted(f.lease, ID, current.localRevision))
        assertEquals(CommandPhase.AWAITING_CONFIRMATION, recovered.phase); assertEquals(1, f.sent.size)
    }

    @Test fun newlyObservedLiveClaimAfterPayloadReadStillPreventsRecoveryCommit() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val pending = f.orphan(publication())
        val commits = f.store.commits.size; var claimed = false
        f.store.afterRead = { key -> if (key == request(ID) && !claimed) {
            claimed = ProcessCommandClaims.acquire(f.scope, ID); assertTrue(claimed)
        } }
        try {
            failure(FailureReason.CONFLICT, f.queue.recoverInterrupted(f.lease, ID, pending.localRevision))
            assertTrue(claimed); assertEquals(commits, f.store.commits.size)
        } finally { if (claimed) ProcessCommandClaims.release(f.scope, ID) }
    }

    @Test fun staleExpectedRevisionFailsWithoutRecoveringAnyCommand() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val pending = f.orphan(publication())
        val before = f.store.peek(metadata(ID))!!; val commits = f.store.commits.size
        failure(FailureReason.CONFLICT, f.queue.recoverInterrupted(f.lease, ID, pending.localRevision - 1))
        failure(FailureReason.CONFLICT, f.queue.recoverInterrupted(f.lease, ID, pending.localRevision + 1))
        assertRecord(before, f.store.peek(metadata(ID))); assertEquals(commits, f.store.commits.size)
    }

    @Test fun malformedIdentityAndNonpositiveRevisionFailBeforeStoreAccess() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        for ((id, revision) in listOf("invalid" to 1L, ID.uppercase() to 1L, ID to 0L, ID to -1L)) {
            val reads = f.store.reads
            failure(FailureReason.INVALID_DATA, f.queue.recoverInterrupted(f.lease, id, revision))
            assertEquals(reads, f.store.reads)
        }
        assertTrue(f.store.commits.isEmpty()); assertTrue(f.sent.isEmpty())
    }

    @Test fun absentIdentityDoesNotFallBackToAnotherOrphan() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); f.orphan(publication(ID2))
        val before = f.store.peek(metadata(ID2))!!; val commits = f.store.commits.size
        failure(FailureReason.NOT_FOUND, f.queue.recoverInterrupted(f.lease, ID, 1))
        assertRecord(before, f.store.peek(metadata(ID2))); assertEquals(commits, f.store.commits.size)
    }

    @Test fun unattemptedReceiptAndArchivedStatesAreNotInterruptedAttempts() = runTest {
        for (phase in listOf("unattempted", "receipt", "applied", "discarded")) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); val queued = value(f.queue.enqueue(f.lease, removal()))
            when (phase) {
                "receipt", "applied" -> {
                    f.exchange = { PortResult.Value(ApiReply(204, null)) }
                    val receipt = assertNotNull(value(f.queue.dispatchAutomatic(f.lease, ID)))
                    if (phase == "applied") value(f.queue.applyReceipt(f.lease, ID, receipt.localRevision, emptyList()))
                }
                "discarded" -> value(f.queue.discardUnsent(f.lease, ID, queued.localRevision))
            }
            val current = assertNotNull(value(f.queue.command(f.lease, ID))); val before = f.store.peek(metadata(ID))!!
            val commits = f.store.commits.size
            failure(if (phase in listOf("applied", "discarded")) FailureReason.NOT_FOUND else FailureReason.CONFLICT,
                f.queue.recoverInterrupted(f.lease, ID, current.localRevision))
            assertRecord(before, f.store.peek(metadata(ID))); assertEquals(commits, f.store.commits.size)
        }
    }

    @Test fun expiredReplayWindowAndClockRollbackBecomeExactResolutionWithoutAgeReset() = runTest {
        for (expired in listOf(false, true)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); val pending = f.orphan(publication())
            val before = f.metadata(ID)
            f.now = if (expired) START + 6L * 86_400_000 else START - 1
            val recovered = value(f.queue.recoverInterrupted(f.lease, ID, pending.localRevision))
            assertEquals(CommandPhase.NEEDS_RESOLUTION, recovered.phase)
            assertEquals(if (expired) CommandIssue.RECONCILIATION_REQUIRED else CommandIssue.CLOCK_CHANGED, recovered.issue)
            assertEquals(before.createdAt, f.metadata(ID).createdAt); assertEquals(before.firstAttemptAt, f.metadata(ID).firstAttemptAt)
            assertEquals(before.attempts, recovered.attempts); assertEquals(1, f.sent.size)
            assertEquals(START.coerceAtLeast(f.now), JournalCodec.decodeIndex(f.store.peek(INDEX)!!.payload).lastObservedMillis)
        }
    }

    @Test fun requestCorruptionIsNotRepairedOrPromotedToRecoveredState() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val pending = f.orphan(publication())
        f.store.corrupt(request(ID), bytes("{}")); val commits = f.store.commits.size
        failure(FailureReason.INVALID_DATA, f.queue.recoverInterrupted(f.lease, ID, pending.localRevision))
        assertEquals("{}", f.store.peek(request(ID))!!.payload.copyForCodec().decodeToString())
        assertEquals(CommandPhase.IN_FLIGHT, f.metadata(ID).phase); assertEquals(commits, f.store.commits.size)
    }

    @Test fun exhaustedActualAttemptsRemainResolutionRequiredWithoutRefundOrNewSend() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); var pending = f.orphan(publication())
        for (attempt in 1..8) {
            assertEquals(attempt, pending.attempts)
            val recovered = value(f.queue.recoverInterrupted(f.lease, ID, pending.localRevision))
            assertEquals(attempt, recovered.attempts)
            if (attempt == 8) {
                assertEquals(CommandPhase.NEEDS_RESOLUTION, recovered.phase); assertEquals(CommandIssue.RETRY_EXHAUSTED, recovered.issue)
            } else {
                assertEquals(CommandPhase.AWAITING_CONFIRMATION, recovered.phase)
                f.now = recovered.retryAtMillis
                failure(FailureReason.STORAGE_FAILURE, f.queue.dispatchConfirmed(f.lease, ID))
                pending = assertNotNull(value(f.queue.command(f.lease, ID)))
            }
        }
        assertEquals(8, f.sent.size); assertEquals(START, f.metadata(ID).createdAt); assertEquals(START, f.metadata(ID).firstAttemptAt)
    }

    @Test fun payloadReplacementBetweenReadsIsRejectedWithoutMetadataMutation() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val pending = f.orphan(publication())
        var changed = false; val before = f.store.peek(metadata(ID))!!; val commits = f.store.commits.size
        f.store.afterRead = { key -> if (key == request(ID) && !changed) {
            changed = true; f.store.corrupt(key, publication(caption = "Changed original").call.body!!)
        } }
        failure(FailureReason.CONFLICT, f.queue.recoverInterrupted(f.lease, ID, pending.localRevision))
        assertTrue(changed); assertRecord(before, f.store.peek(metadata(ID))); assertEquals(commits, f.store.commits.size)
    }

    @Test fun competingMetadataOrIndexCasPreventsTheEntireRecoveryWrite() = runTest {
        for (key in listOf(metadata(ID), INDEX)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); val pending = f.orphan(publication())
            var changed = false; val commits = f.store.commits.size
            f.store.afterRead = { read -> if (read == request(ID) && !changed) { changed = true; f.store.bump(key) } }
            failure(FailureReason.CONFLICT, f.queue.recoverInterrupted(f.lease, ID, pending.localRevision))
            assertTrue(changed); assertEquals(CommandPhase.IN_FLIGHT, f.metadata(ID).phase)
            assertEquals(commits, f.store.commits.size); assertEquals(1, f.sent.size)
        }
    }

    @Test fun lostRecoveryCommitResponseLeavesOneChangedOriginalAndRejectsOldRevisionRetry() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val original = publication(); val pending = f.orphan(original)
        val commits = f.store.commits.size; f.store.failAfter = FailureReason.STORAGE_FAILURE
        failure(FailureReason.STORAGE_FAILURE, f.queue.recoverInterrupted(f.lease, ID, pending.localRevision))
        assertEquals(commits + 1, f.store.commits.size)
        val observed = assertNotNull(value(f.otherQueue().command(f.lease, ID)))
        assertEquals(CommandPhase.AWAITING_CONFIRMATION, observed.phase); assertEquals(pending.localRevision + 1, observed.localRevision)
        failure(FailureReason.CONFLICT, f.otherQueue().recoverInterrupted(f.lease, ID, pending.localRevision))
        assertEquals(commits + 1, f.store.commits.size); assertEquals(1, f.sent.size)
        assertIntent(original, assertNotNull(value(f.otherQueue().intent(f.lease, ID))))
    }

    @Test fun failedBeforeRecoveryCommitLeavesOriginalOrphanForTheSameExactRetry() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val pending = f.orphan(publication())
        val before = f.store.peek(metadata(ID))!!; val commits = f.store.commits.size
        f.store.failBefore = FailureReason.STORAGE_FAILURE
        failure(FailureReason.STORAGE_FAILURE, f.queue.recoverInterrupted(f.lease, ID, pending.localRevision))
        assertRecord(before, f.store.peek(metadata(ID))); assertEquals(commits, f.store.commits.size)
        assertEquals(CommandPhase.AWAITING_CONFIRMATION, value(f.queue.recoverInterrupted(f.lease, ID, pending.localRevision)).phase)
        assertEquals(commits + 1, f.store.commits.size); assertEquals(1, f.sent.size)
    }

    @Test fun foreignAndRevokedLeaseCannotReadOrRecoverThisOwner() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val pending = f.orphan(publication())
        val reads = f.store.reads; val commits = f.store.commits.size
        failure(FailureReason.STALE_SESSION, f.queue.recoverInterrupted(SessionBoundary().activate(f.scope), ID, pending.localRevision))
        assertEquals(reads, f.store.reads)
        f.boundary.clear()
        failure(FailureReason.STALE_SESSION, f.queue.recoverInterrupted(f.lease, ID, pending.localRevision))
        assertEquals(reads, f.store.reads); assertEquals(commits, f.store.commits.size)
    }

    @Test fun invalidationDuringPayloadReadPreventsRecoveryCommit() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val pending = f.orphan(publication())
        val commits = f.store.commits.size
        f.store.afterRead = { if (it == request(ID)) f.boundary.clear() }
        failure(FailureReason.STALE_SESSION, f.queue.recoverInterrupted(f.lease, ID, pending.localRevision))
        assertEquals(commits, f.store.commits.size); assertEquals(CommandPhase.IN_FLIGHT, f.metadata(ID).phase)
    }

    @Test fun cancellationDuringNoncooperativePayloadReadCannotReachRecoveryCommit() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val pending = f.orphan(publication())
        val entered = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>(); val commits = f.store.commits.size
        f.store.afterRead = { if (it == request(ID)) withContext(NonCancellable) { entered.complete(Unit); resume.await() } }
        val job = launch { f.queue.recoverInterrupted(f.lease, ID, pending.localRevision) }
        entered.await(); job.cancel(); resume.complete(Unit); job.join()
        assertTrue(job.isCancelled); assertEquals(commits, f.store.commits.size)
        assertEquals(CommandPhase.IN_FLIGHT, f.metadata(ID).phase); assertEquals(1, f.sent.size)
    }

    @Test fun cancellationAfterActualRecoveryCommitDoesNotDeliverSuccessOrRepeatThatWrite() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val pending = f.orphan(publication())
        val committed = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>(); val commits = f.store.commits.size
        var delivered = false
        f.store.afterCommit = { withContext(NonCancellable) { committed.complete(Unit); resume.await() } }
        val job = launch { value(f.queue.recoverInterrupted(f.lease, ID, pending.localRevision)); delivered = true }
        committed.await(); job.cancel(); resume.complete(Unit); job.join(); f.store.afterCommit = {}
        assertTrue(job.isCancelled); assertFalse(delivered); assertEquals(commits + 1, f.store.commits.size)
        val observed = assertNotNull(value(f.otherQueue().command(f.lease, ID)))
        assertEquals(CommandPhase.AWAITING_CONFIRMATION, observed.phase); assertEquals(pending.localRevision + 1, observed.localRevision)
        failure(FailureReason.CONFLICT, f.queue.recoverInterrupted(f.lease, ID, pending.localRevision))
        assertEquals(commits + 1, f.store.commits.size); assertEquals(1, f.sent.size)
    }

    private class Fixture(private val dispatcher: CoroutineDispatcher) {
        val scope = StorageScope("test", ActorKind.ACCOUNT, "synthetic-private-owner")
        val boundary = SessionBoundary(); val lease = boundary.activate(scope); val store = CasStore(scope)
        var now = START; var gateCalls = 0; val sent = mutableListOf<ApiCall>()
        var exchange: suspend (ApiCall) -> PortResult<ApiReply> = { PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
        val queue = otherQueue()
        fun otherQueue() = DurableCommandQueue(scope, store, boundary, dispatcher, EpochClock { now },
            CommandExecutionGate { _, _ -> gateCalls++; ExecutionDecision.Ready }, object : AccountTransport {
                override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> { sent += call; return exchange(call) }
            })
        suspend fun orphan(intent: CommandIntent): CommandView {
            value(queue.enqueue(lease, intent))
            exchange = { store.failBefore = FailureReason.STORAGE_FAILURE; PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
            failure(FailureReason.STORAGE_FAILURE, queue.dispatchConfirmed(lease, intent.commandId))
            return assertNotNull(value(queue.command(lease, intent.commandId))).also { assertEquals(CommandPhase.IN_FLIGHT, it.phase) }
        }
        fun metadata(id: String) = JournalCodec.decodeCommand(store.peek(TargetedCommandRecoveryTest.metadata(id))!!.payload)
    }
    private class CasStore(private val owner: StorageScope) : PrivateStateStore {
        private val records = mutableMapOf<RecordKey, PrivateRecord>(); private val revisions = mutableMapOf<RecordKey, Long>()
        val commits = mutableListOf<List<StoreMutation>>(); var reads = 0
        var failBefore: FailureReason? = null; var failAfter: FailureReason? = null
        var afterRead: suspend (RecordKey) -> Unit = {}
        var afterCommit: suspend () -> Unit = {}
        fun peek(key: RecordKey): PrivateRecord? = records[key]?.let { it.copy(payload = PrivateBytes(it.payload.copyForCodec())) }
        fun corrupt(key: RecordKey, payload: PrivateBytes) { records[key] = checkNotNull(records[key]).copy(payload = payload) }
        fun bump(key: RecordKey) { val old = checkNotNull(records[key]); records[key] = old.copy(revision = old.revision + 1); revisions[key] = old.revision + 1 }
        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> {
            if (scope != owner) return PortResult.Failure(FailureReason.STALE_SESSION)
            reads++; val result = peek(key); afterRead(key); return PortResult.Value(result)
        }
        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> {
            if (scope != owner) return PortResult.Failure(FailureReason.STALE_SESSION)
            failBefore?.let { failBefore = null; return PortResult.Failure(it) }
            if (mutations.size !in 1..64 || mutations.map { it.key }.toSet().size != mutations.size) return PortResult.Failure(FailureReason.INVALID_DATA)
            if (mutations.any { it.expectedRevision != records[it.key]?.revision }) return PortResult.Failure(FailureReason.CONFLICT)
            val result = mutableMapOf<RecordKey, Long?>()
            for (mutation in mutations) {
                val revision = (revisions[mutation.key] ?: 0L) + 1; revisions[mutation.key] = revision
                when (mutation) {
                    is StoreMutation.Put -> { records[mutation.key] = PrivateRecord(revision, mutation.schemaVersion, PrivateBytes(mutation.payload.copyForCodec())); result[mutation.key] = revision }
                    is StoreMutation.Delete -> { records.remove(mutation.key); result[mutation.key] = null }
                }
            }
            commits += mutations.toList()
            failAfter?.let { failAfter = null; return PortResult.Failure(it) }
            afterCommit()
            return PortResult.Value(result)
        }
        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> = error("Recovery must never erase state")
    }
    private companion object {
        const val ID = "123e4567-e89b-42d3-a456-426614174001"
        const val ID2 = "123e4567-e89b-42d3-a456-426614174002"
        const val ID3 = "123e4567-e89b-42d3-a456-426614174003"
        const val ROOT = "123e4567-e89b-42d3-a456-426614174070"
        const val RESOURCE = "123e4567-e89b-42d3-a456-426614174080"
        const val ORIGIN = "123e4567-e89b-42d3-a456-426614174090"
        const val START = 1_800_000_000_000L
        val INDEX = RecordKey("feedme.command.index", "v1")
        fun metadata(id: String) = RecordKey("feedme.command.metadata", id)
        fun request(id: String) = RecordKey("feedme.command.request", id)
        fun sequence() = RecordKey("feedme.command.cook-sequence", "$ORIGIN:$RESOURCE")
        fun bytes(text: String) = PrivateBytes(text.encodeToByteArray())
        fun publication(id: String = ID, saved: Boolean = false, dependencies: List<String> = emptyList(), caption: String = "Original") =
            CommandIntent(id, ORIGIN, ApiCall("publishPost", body = bytes(""" { "clientDraftId":"$ROOT", "caption":"$caption", "mediaIds":[], "audience":{"kind":"self","circleIds":[]}, "keepOnPlate":false, "allowRecipeSaves":false, "saveDisclosureVersion":"synthetic-disclosure"${if (saved) ", \"draftId\":\"$RESOURCE\", \"draftVersion\":9007199254740993" else ""} } 
"""), idempotencyKey = SecretText(id)), dependencies)
        fun cooking(id: String) = CommandIntent(id, ORIGIN, ApiCall("updateCookSession", mapOf("sessionId" to RESOURCE),
            body = bytes(" { \"deviceSequence\": 1e0 } "), idempotencyKey = SecretText(id), ifMatch = "\"7\""))
        fun removal(id: String = ID) = CommandIntent(id, ORIGIN, ApiCall("removePantryItem", mapOf("ingredientId" to RESOURCE),
            idempotencyKey = SecretText(id), ifMatch = "\"7\""))
        fun assertRecord(expected: PrivateRecord, actual: PrivateRecord?) {
            assertNotNull(actual); assertEquals(expected.revision, actual.revision); assertEquals(expected.schemaVersion, actual.schemaVersion)
            assertContentEquals(expected.payload.copyForCodec(), actual.payload.copyForCodec())
        }
        fun assertIntent(expected: CommandIntent, actual: CommandIntent) {
            assertEquals(expected.commandId, actual.commandId); assertEquals(expected.originBinding, actual.originBinding)
            assertEquals(expected.dependencyCommandIds, actual.dependencyCommandIds)
            assertEquals(expected.call.operationId, actual.call.operationId); assertEquals(expected.call.pathParameters, actual.call.pathParameters)
            assertEquals(expected.call.queryParameters, actual.call.queryParameters); assertEquals(expected.call.ifMatch, actual.call.ifMatch)
            assertEquals(expected.call.idempotencyKey!!.use { it }, actual.call.idempotencyKey!!.use { it })
            assertContentEquals(expected.call.body!!.copyForCodec(), actual.call.body!!.copyForCodec())
        }
        fun <T> value(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
        fun failure(reason: FailureReason, result: PortResult<*>) { assertEquals(reason, assertIs<PortResult.Failure>(result).reason) }
    }
}
