package com.feedme.sync

import com.feedme.core.ports.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Journal state-machine tests. The CAS fixture is not a production persistence/crypto adapter. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DurableCommandQueueTest {
    @Test fun originalIntentReadIsDetachedExactAndNeverCallsExecutionPorts() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); val original = cookIntent()
        value(f.queue.enqueue(f.lease, original)); val commits = f.store.commits.size
        val read = assertNotNull(value(f.queue.intent(f.lease, ID)))
        assertEquals(original.originBinding, read.originBinding); assertEquals(ID, read.commandId)
        assertEquals(original.call.pathParameters, read.call.pathParameters); assertEquals(original.call.ifMatch, read.call.ifMatch)
        assertContentEquals(original.call.body!!.copyForCodec(), read.call.body!!.copyForCodec())
        read.call.body!!.copyForCodec().fill(0)
        runCatching { (read.call.pathParameters as MutableMap<String, String>).clear() }
        val again = assertNotNull(value(f.queue.intent(f.lease, ID)))
        assertContentEquals(original.call.body!!.copyForCodec(), again.call.body!!.copyForCodec())
        assertEquals(original.call.pathParameters, again.call.pathParameters)
        assertEquals(commits, f.store.commits.size); assertEquals(0, f.gateCalls); assertTrue(f.sent.isEmpty())
    }

    @Test fun originalIntentReadRejectsWrongStaleAndMalformedIdentityWithoutEffects() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); value(f.queue.enqueue(f.lease, cookIntent()))
        val foreign = SessionBoundary().activate(f.scope)
        failure(FailureReason.STALE_SESSION, f.queue.intent(foreign, ID))
        failure(FailureReason.INVALID_DATA, f.queue.intent(f.lease, "invalid"))
        f.boundary.activate(f.scope); failure(FailureReason.STALE_SESSION, f.queue.intent(f.lease, ID))
        assertEquals(0, f.gateCalls); assertTrue(f.sent.isEmpty())
    }

    @Test fun originalIntentReadReturnsNullForAbsentAndBothTerminalTombstones() = runTest {
        for (apply in listOf(false, true)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); assertNull(value(f.queue.intent(f.lease, ID)))
            val queued = value(f.queue.enqueue(f.lease, deleteIntent()))
            if (apply) {
                val received = assertNotNull(value(f.queue.dispatchNext(f.lease)))
                value(f.queue.applyReceipt(f.lease, ID, received.localRevision, emptyList()))
            } else value(f.queue.discardUnsent(f.lease, ID, queued.localRevision))
            assertNull(value(f.queue.intent(f.lease, ID))); assertNotNull(value(f.queue.command(f.lease, ID)))
        }
    }

    @Test fun originalIntentReadBracketsChangedMetadataAndBody() = runTest {
        for (body in listOf(false, true)) {
            val f = Fixture(StandardTestDispatcher(testScheduler)); value(f.queue.enqueue(f.lease, cookIntent()))
            var changed = false
            f.store.afterRead = { key -> if (!changed && key == request(ID)) {
                changed = true
                if (body) f.store.corrupt(key, json("{\"deviceSequence\":2,\"currentStepId\":\"next\"}"))
                else f.store.bumpRevision(metadata(ID))
            } }
            failure(FailureReason.CONFLICT, f.queue.intent(f.lease, ID)); assertTrue(f.sent.isEmpty())
        }
    }

    @Test fun originalIntentReadRejectsMalformedPersistedBodyWithoutRepairingIt() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler)); value(f.queue.enqueue(f.lease, cookIntent()))
        f.store.corrupt(request(ID), json("{}")); val commits = f.store.commits.size
        failure(FailureReason.INVALID_DATA, f.queue.intent(f.lease, ID)); assertEquals(commits, f.store.commits.size)
        assertEquals("{}", f.store.text(request(ID))); assertTrue(f.sent.isEmpty())
    }

    @Test fun enqueueAtomicallyPersistsDraftExactBodyMetadataAndIndexBeforeAnySend() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val bytes = " { \"deviceSequence\": 1e0, \"currentStepId\": \"private-step\" } \n".encodeToByteArray()
        val original = bytes.copyOf()
        val intent = cookIntent(body = PrivateBytes(bytes))
        bytes.fill(0)
        val queued = value(f.queue.enqueue(f.lease, intent, listOf(put(DRAFT, "draft-before-send"))))
        assertEquals(CommandPhase.READY, queued.phase)
        assertEquals(0, queued.attempts)
        assertEquals(1, f.store.commits.size)
        assertEquals(setOf(DRAFT, INDEX, metadata(ID), request(ID), sequenceKey()), f.store.commits.single().map { it.key }.toSet())
        assertContentEquals(original, f.store.peek(request(ID))!!.payload.copyForCodec())
        assertEquals(listOf(ID), JournalCodec.decodeIndex(f.store.peek(INDEX)!!.payload).ids)
        assertEquals(0, f.gateCalls)
        assertTrue(f.sent.isEmpty())
    }

    @Test fun claimIsDurableBeforeTransportAndReceiptDoesNotApplyDomainState() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, cookIntent(), listOf(put(DRAFT, "draft"))))
        f.exchange = { _, call ->
            val durable = f.stored(ID)
            assertEquals(CommandPhase.IN_FLIGHT, durable.phase)
            assertEquals(1, durable.attempts)
            assertEquals(f.now, durable.firstAttemptAt)
            assertEquals(ID, call.idempotencyKey!!.use { it })
            assertEquals("draft", f.store.text(DRAFT))
            PortResult.Value(cookReply())
        }
        val completed = assertNotNull(value(f.queue.dispatchNext(f.lease)))
        assertEquals(CommandPhase.RECEIPT_READY, completed.phase)
        assertEquals("draft", f.store.text(DRAFT))
        assertEquals(1, value(f.queue.pending(f.lease)).size)
        assertNotNull(f.store.peek(receiptKey(ID)))
        assertNotNull(value(f.queue.receipt(f.lease, ID)))
        assertNull(value(f.queue.dispatchNext(f.lease)))
        assertEquals(1, f.sent.size)
    }

    @Test fun successfulReceiptAndDomainApplicationAreOneAtomicCasTransaction() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, cookIntent(), listOf(put(DRAFT, "draft"))))
        f.exchange = { _, _ -> PortResult.Value(cookReply()) }
        val received = assertNotNull(value(f.queue.dispatchNext(f.lease)))
        val draftRevision = f.store.peek(DRAFT)!!.revision
        value(f.queue.applyReceipt(f.lease, ID, received.localRevision, listOf(put(DRAFT, "applied", draftRevision))))
        assertEquals("applied", f.store.text(DRAFT))
        assertTrue(value(f.queue.pending(f.lease)).isEmpty())
        assertNull(value(f.queue.receipt(f.lease, ID)))
        assertNull(f.store.peek(request(ID)))
        assertNull(f.store.peek(receiptKey(ID)))
        assertEquals(CommandPhase.APPLIED, f.stored(ID).phase)
        assertTrue(f.stored(ID).request.path.isEmpty())
        assertNull(f.stored(ID).request.ifMatch)
        assertEquals(setOf(DRAFT, INDEX, metadata(ID), request(ID), receiptKey(ID)), f.store.commits.last().map { it.key }.toSet())
        failure(FailureReason.CONFLICT, f.queue.enqueue(f.lease, cookIntent()))
    }

    @Test fun staleReceiptRevisionCannotApplyDomainChangesOrLoseReceipt() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent(), listOf(put(DRAFT, "draft"))))
        val received = assertNotNull(value(f.queue.dispatchNext(f.lease)))
        failure(FailureReason.CONFLICT, f.queue.applyReceipt(f.lease, ID, received.localRevision - 1,
            listOf(put(DRAFT, "incorrect", f.store.peek(DRAFT)!!.revision))))
        assertEquals("draft", f.store.text(DRAFT))
        assertEquals(CommandPhase.RECEIPT_READY, f.stored(ID).phase)
        assertNotNull(value(f.queue.receipt(f.lease, ID)))
    }

    @Test fun domainCasConflictRejectsWholeReceiptApplication() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent(), listOf(put(DRAFT, "draft"))))
        val received = assertNotNull(value(f.queue.dispatchNext(f.lease)))
        failure(FailureReason.CONFLICT, f.queue.applyReceipt(f.lease, ID, received.localRevision,
            listOf(put(DRAFT, "incorrect", 99))))
        assertEquals("draft", f.store.text(DRAFT))
        assertEquals(received.localRevision, f.store.peek(metadata(ID))!!.revision)
        assertEquals(CommandPhase.RECEIPT_READY, value(f.queue.pending(f.lease)).single().phase)
    }

    @Test fun failedEnqueueCommitDoesNotPersistPartialDraftOrSend() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.store.nextCommitFailure = FailureReason.STORAGE_FAILURE
        failure(FailureReason.STORAGE_FAILURE, f.queue.enqueue(f.lease, cookIntent(), listOf(put(DRAFT, "draft"))))
        assertTrue(f.store.records.isEmpty())
        assertTrue(f.sent.isEmpty())
        assertEquals(0, f.gateCalls)
    }

    @Test fun failedClaimCommitNeverCallsTransport() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent()))
        f.store.nextCommitFailure = FailureReason.CONFLICT
        failure(FailureReason.CONFLICT, f.queue.dispatchNext(f.lease))
        assertEquals(CommandPhase.READY, f.stored(ID).phase)
        assertEquals(0, f.stored(ID).attempts)
        assertTrue(f.sent.isEmpty())
    }

    @Test fun failedOutcomeCommitLeavesOriginalAttemptRecoverable() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent()))
        f.exchange = { _, _ ->
            f.store.nextCommitFailure = FailureReason.STORAGE_FAILURE
            PortResult.Value(ApiReply(204, null))
        }
        failure(FailureReason.STORAGE_FAILURE, f.queue.dispatchNext(f.lease))
        assertEquals(CommandPhase.IN_FLIGHT, f.stored(ID).phase)
        assertNull(value(f.queue.receipt(f.lease, ID)))
        val restarted = f.newQueue()
        assertEquals(1, value(restarted.recoverInterrupted(f.lease)))
        assertEquals(CommandPhase.RETRY_WAIT, f.stored(ID).phase)
        assertEquals(1, f.stored(ID).attempts)
        assertEquals(1, f.sent.size)
    }

    @Test fun cancellationAndRestartReplayExactOriginalRequestWithSameIdentityAndVersion() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val body = " { \"deviceSequence\": 1e0, \"currentStepId\": \"original\" } \n"
        value(f.queue.enqueue(f.lease, cookIntent(body = json(body))))
        f.exchange = { _, _ -> awaitCancellation() }
        val dispatch = launch { f.queue.dispatchNext(f.lease) }
        runCurrent()
        assertEquals(CommandPhase.IN_FLIGHT, f.stored(ID).phase)
        assertEquals(0, value(f.queue.recoverInterrupted(f.lease)))
        dispatch.cancelAndJoin()
        val restarted = f.newQueue()
        assertEquals(1, value(restarted.recoverInterrupted(f.lease)))
        assertEquals(0, value(restarted.recoverInterrupted(f.lease)))
        assertEquals(1, f.sent.size)
        assertNull(value(restarted.dispatchNext(f.lease)))
        f.now = f.stored(ID).retryAt
        f.exchange = { _, _ -> PortResult.Value(cookReply()) }
        val result = assertNotNull(value(restarted.dispatchNext(f.lease)))
        assertEquals(CommandPhase.RECEIPT_READY, result.phase)
        assertEquals(2, result.attempts)
        assertEquals(2, f.sent.size)
        f.sent.forEach { call ->
            assertEquals(ID, call.idempotencyKey!!.use { it })
            assertEquals("\"7\"", call.ifMatch)
            assertEquals(mapOf("sessionId" to RESOURCE), call.pathParameters)
            assertContentEquals(body.encodeToByteArray(), call.body!!.copyForCodec())
        }
        assertEquals(START, f.stored(ID).firstAttemptAt)
        assertEquals(ORIGIN, f.gated.last().originBinding)
    }

    @Test fun recoveryDoesNotStealAnActiveAttemptWithinSameQueue() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent()))
        f.exchange = { _, _ ->
            assertEquals(0, value(f.queue.recoverInterrupted(f.lease)))
            assertNull(value(f.queue.dispatchNext(f.lease)))
            PortResult.Value(ApiReply(204, null))
        }
        assertEquals(CommandPhase.RECEIPT_READY, assertNotNull(value(f.queue.dispatchNext(f.lease))).phase)
        assertEquals(1, f.sent.size)
    }

    @Test fun anotherQueueInstanceCannotRecoverGenuineActiveAttempt() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent()))
        f.exchange = { _, _ ->
            assertEquals(0, value(f.newQueue().recoverInterrupted(f.lease)))
            assertNull(value(f.newQueue().dispatchNext(f.lease)))
            PortResult.Value(ApiReply(204, null))
        }
        assertEquals(CommandPhase.RECEIPT_READY, assertNotNull(value(f.queue.dispatchNext(f.lease))).phase)
        assertNotNull(value(f.queue.receipt(f.lease, ID)))
        assertEquals(1, f.sent.size)
    }

    @Test fun recoverySplitsManyInterruptedRecordsIntoBoundedAtomicBatchesWithoutSending() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val ids = List(65) { uuid(it + 1) }
        ids.forEach { id ->
            value(f.queue.enqueue(f.lease, deleteIntent(id)))
            val queued = f.stored(id)
            f.store.corrupt(metadata(id), JournalCodec.encodeCommand(queued.copy(
                phase = CommandPhase.IN_FLIGHT, attempts = 1, firstAttemptAt = START)))
        }
        val commitCount = f.store.commits.size
        assertEquals(65, value(f.newQueue().recoverInterrupted(f.lease)))
        assertEquals(listOf(33, 33, 2), f.store.commits.drop(commitCount).map { it.size })
        assertEquals(0, value(f.newQueue().recoverInterrupted(f.lease)))
        assertTrue(value(f.queue.pending(f.lease)).all {
            it.phase == CommandPhase.RETRY_WAIT && it.attempts == 1 && it.issue == CommandIssue.OUTCOME_UNKNOWN
        })
        assertTrue(f.sent.isEmpty())
        assertEquals(0, f.gateCalls)
    }

    @Test fun recoveryFailureAfterOneBatchRetainsBothRecoveredAndStillInterruptedIntentions() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val ids = List(33) { uuid(it + 1) }
        ids.forEach { id ->
            value(f.queue.enqueue(f.lease, deleteIntent(id)))
            f.store.corrupt(metadata(id), JournalCodec.encodeCommand(f.stored(id).copy(
                phase = CommandPhase.IN_FLIGHT, attempts = 1, firstAttemptAt = START)))
        }
        f.store.afterCommit = {
            f.store.nextCommitFailure = FailureReason.STORAGE_FAILURE
            f.store.afterCommit = {}
        }
        failure(FailureReason.STORAGE_FAILURE, f.queue.recoverInterrupted(f.lease))
        val partial = value(f.queue.pending(f.lease))
        assertEquals(32, partial.count { it.phase == CommandPhase.RETRY_WAIT })
        assertEquals(1, partial.count { it.phase == CommandPhase.IN_FLIGHT })
        assertEquals(1, value(f.newQueue().recoverInterrupted(f.lease)))
        assertEquals(33, value(f.queue.pending(f.lease)).size)
        assertTrue(f.sent.isEmpty())
    }

    @Test fun lateResponseCannotOverwriteChangedDurableClaimRevision() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent()))
        f.exchange = { _, _ ->
            // Simulate another owner-scoped transaction changing this record while HTTP suspended.
            f.store.bumpRevision(metadata(ID))
            PortResult.Value(ApiReply(204, null))
        }
        failure(FailureReason.CONFLICT, f.queue.dispatchNext(f.lease))
        assertEquals(CommandPhase.IN_FLIGHT, f.stored(ID).phase)
        assertNull(value(f.queue.receipt(f.lease, ID)))
        assertEquals(1, value(f.newQueue().recoverInterrupted(f.lease)))
    }

    @Test fun staleLeaseIsRejectedBeforeAnyStoreGateOrTransportAccess() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.boundary.clear()
        failure(FailureReason.STALE_SESSION, f.queue.enqueue(f.lease, deleteIntent()))
        failure(FailureReason.STALE_SESSION, f.queue.pending(f.lease))
        failure(FailureReason.STALE_SESSION, f.queue.dispatchNext(f.lease))
        assertEquals(0, f.store.readCount)
        assertEquals(0, f.gateCalls)
        assertTrue(f.sent.isEmpty())
    }

    @Test fun leaseChangeAfterStoreReadIsFencedBeforeMutation() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.store.afterRead = { f.boundary.clear() }
        failure(FailureReason.STALE_SESSION, f.queue.enqueue(f.lease, deleteIntent()))
        assertTrue(f.store.commits.isEmpty())
        assertTrue(f.sent.isEmpty())
    }

    @Test fun sessionChangeDuringGateCannotClaimOrSend() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent()))
        f.preflight = { _, _ -> f.boundary.activate(f.scope); ExecutionDecision.Ready }
        failure(FailureReason.STALE_SESSION, f.queue.dispatchNext(f.lease))
        assertEquals(CommandPhase.READY, f.stored(ID).phase)
        assertTrue(f.sent.isEmpty())
    }

    @Test fun sessionChangeDuringTransportLeavesOldAttemptUnapplied() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent()))
        f.exchange = { _, _ -> f.boundary.clear(); PortResult.Value(ApiReply(204, null)) }
        failure(FailureReason.STALE_SESSION, f.queue.dispatchNext(f.lease))
        assertEquals(CommandPhase.IN_FLIGHT, f.stored(ID).phase)
        assertNull(f.store.peek(receiptKey(ID)))
        assertEquals(1, f.sent.size)
    }

    @Test fun leaseChangeAfterCommittedClaimReturnsStaleWithoutSending() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent()))
        f.store.afterCommit = { f.boundary.clear() }
        failure(FailureReason.STALE_SESSION, f.queue.dispatchNext(f.lease))
        assertEquals(CommandPhase.IN_FLIGHT, f.stored(ID).phase)
        assertTrue(f.sent.isEmpty())
    }

    @Test fun differentOwnerAndDemoNeverAccessQueueStorage() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val other = f.boundary.activate(StorageScope("test", ActorKind.ACCOUNT, "other"))
        failure(FailureReason.STALE_SESSION, f.queue.pending(other))
        val demo = Fixture(StandardTestDispatcher(testScheduler), ActorKind.DEMO)
        failure(FailureReason.UNAUTHENTICATED, demo.queue.enqueue(demo.lease, deleteIntent()))
        assertEquals(0, f.store.readCount)
        assertEquals(0, demo.store.readCount)
    }

    @Test fun changedQueueTransactionInvalidatesPreflightCas() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent()))
        f.preflight = { _, _ ->
            value(f.queue.enqueue(f.lease, reactionIntent(ID2)))
            ExecutionDecision.Ready
        }
        failure(FailureReason.CONFLICT, f.queue.dispatchNext(f.lease))
        assertEquals(0, f.stored(ID).attempts)
        assertTrue(f.sent.isEmpty())
        assertEquals(2, value(f.queue.pending(f.lease)).size)
    }

    @Test fun gateWaitPersistsSafeHoldWithoutDispatchOrRetry() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent()))
        f.preflight = { _, _ -> ExecutionDecision.Wait(CommandIssue.PERMISSION_CHANGED) }
        val held = assertNotNull(value(f.queue.dispatchNext(f.lease)))
        assertEquals(CommandPhase.NEEDS_RESOLUTION, held.phase)
        assertEquals(CommandIssue.PERMISSION_CHANGED, held.issue)
        assertEquals(0, held.attempts)
        f.preflight = { _, _ -> ExecutionDecision.Ready }
        assertNull(value(f.queue.dispatchNext(f.lease)))
        assertNull(value(f.queue.dispatchConfirmed(f.lease, ID)))
        assertTrue(f.sent.isEmpty())
    }

    @Test fun gateExceptionAndEmptyIssueAreSanitizedWithoutSending() = runTest {
        for (decision in listOf<suspend (SessionLease, CommandIntent) -> ExecutionDecision>(
            { _, _ -> throw IllegalStateException("sensitive provider detail") },
            { _, _ -> ExecutionDecision.Wait(CommandIssue.NONE) },
        )) {
            val f = Fixture(StandardTestDispatcher(testScheduler))
            value(f.queue.enqueue(f.lease, deleteIntent()))
            f.preflight = decision
            val held = assertNotNull(value(f.queue.dispatchNext(f.lease)))
            assertEquals(CommandPhase.NEEDS_RESOLUTION, held.phase)
            assertTrue(held.issue in setOf(CommandIssue.NOT_CONFIGURED, CommandIssue.DOMAIN_RECHECK_REQUIRED))
            assertFalse(f.store.text(metadata(ID))!!.contains("sensitive"))
            assertTrue(f.sent.isEmpty())
        }
    }

    @Test fun manualPolicyRequiresFreshConfirmationOnEveryAttempt() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val queued = value(f.queue.enqueue(f.lease, feedbackDeleteIntent()))
        assertEquals(CommandPhase.AWAITING_CONFIRMATION, queued.phase)
        assertNull(value(f.queue.dispatchNext(f.lease)))
        assertEquals(0, f.gateCalls)
        f.exchange = { _, _ -> PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
        val uncertain = assertNotNull(value(f.queue.dispatchConfirmed(f.lease, ID)))
        assertEquals(CommandPhase.AWAITING_CONFIRMATION, uncertain.phase)
        assertNull(value(f.queue.dispatchConfirmed(f.lease, ID)))
        f.now = uncertain.retryAtMillis
        assertNull(value(f.queue.dispatchNext(f.lease)))
        f.exchange = { _, _ -> PortResult.Value(ApiReply(204, null)) }
        assertEquals(CommandPhase.RECEIPT_READY, assertNotNull(value(f.queue.dispatchConfirmed(f.lease, ID))).phase)
        assertEquals(2, f.sent.size)
        assertEquals(listOf(ID, ID), f.sent.map { it.idempotencyKey!!.use { key -> key } })
    }

    @Test fun dependencyWaitsForAppliedReceiptNotMerelyRemoteSuccess() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent()))
        value(f.queue.enqueue(f.lease, reactionIntent(ID2, dependencies = listOf(ID))))
        val parent = assertNotNull(value(f.queue.dispatchNext(f.lease)))
        assertEquals(ID, parent.commandId)
        assertEquals(CommandPhase.RECEIPT_READY, parent.phase)
        assertNull(value(f.queue.dispatchNext(f.lease)))
        value(f.queue.applyReceipt(f.lease, ID, parent.localRevision, emptyList()))
        val child = assertNotNull(value(f.queue.dispatchNext(f.lease)))
        assertEquals(ID2, child.commandId)
        assertEquals(listOf(ID), f.gated.last().dependencyCommandIds)
    }

    @Test fun discardedDependencyBlocksDescendantWithoutSending() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val parent = value(f.queue.enqueue(f.lease, deleteIntent()))
        value(f.queue.enqueue(f.lease, reactionIntent(ID2, dependencies = listOf(ID))))
        value(f.queue.discardUnsent(f.lease, ID, parent.localRevision))
        assertNull(value(f.queue.dispatchNext(f.lease)))
        val child = value(f.queue.pending(f.lease)).single()
        assertEquals(CommandPhase.NEEDS_RESOLUTION, child.phase)
        assertEquals(CommandIssue.DEPENDENCY_FAILED, child.issue)
        assertTrue(f.sent.isEmpty())
    }

    @Test fun sameLanePreservesOrderButIndependentLaneProgresses() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent()))
        value(f.queue.enqueue(f.lease, deleteIntent(ID2, ingredient = OTHER_RESOURCE)))
        value(f.queue.enqueue(f.lease, reactionIntent(ID3)))
        f.exchange = { _, _ -> PortResult.Failure(FailureReason.OFFLINE) }
        assertEquals(ID, assertNotNull(value(f.queue.dispatchNext(f.lease))).commandId)
        f.exchange = { _, _ -> PortResult.Value(ApiReply(204, null)) }
        assertEquals(ID3, assertNotNull(value(f.queue.dispatchNext(f.lease))).commandId)
        assertNull(value(f.queue.dispatchConfirmed(f.lease, ID2)))
        assertEquals(0, f.stored(ID2).attempts)
    }

    @Test fun sameCookingSessionSerializesWhileAnotherSessionCanProgress() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, cookIntent()))
        value(f.queue.enqueue(f.lease, cookIntent(ID2, body = json("{\"deviceSequence\":2}"))))
        value(f.queue.enqueue(f.lease, cookIntent(ID3, session = OTHER_RESOURCE)))
        f.exchange = { _, _ -> PortResult.Value(cookReply()) }
        assertEquals(ID, assertNotNull(value(f.queue.dispatchNext(f.lease))).commandId)
        assertEquals(ID3, assertNotNull(value(f.queue.dispatchNext(f.lease))).commandId)
        assertEquals(0, f.stored(ID2).attempts)
    }

    @Test fun retriesRespectBoundedBackoffAndFailureRetryAfter() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent()))
        f.exchange = { _, _ -> PortResult.Failure(FailureReason.RATE_LIMITED, 120) }
        val waiting = assertNotNull(value(f.queue.dispatchNext(f.lease)))
        assertEquals(CommandPhase.RETRY_WAIT, waiting.phase)
        assertEquals(CommandIssue.TEMPORARILY_UNAVAILABLE, waiting.issue)
        assertEquals(START + 120_000, waiting.retryAtMillis)
        f.now = waiting.retryAtMillis - 1
        assertNull(value(f.queue.dispatchNext(f.lease)))
        assertNull(value(f.queue.dispatchConfirmed(f.lease, ID)))
        assertEquals(1, f.sent.size)
        f.now++
        f.exchange = { _, _ -> PortResult.Failure(FailureReason.OFFLINE) }
        val next = assertNotNull(value(f.queue.dispatchNext(f.lease)))
        assertTrue(next.retryAtMillis - f.now in 1_000L..1_500L)
        assertEquals(1, next.attempts)
        assertEquals(CommandIssue.OFFLINE, next.issue)
    }

    @Test fun httpRetryAfterIsRespectedForCanonical503Problem() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent()))
        f.exchange = { _, _ -> PortResult.Value(problem(503, retryAfter = 90)) }
        val retry = assertNotNull(value(f.queue.dispatchNext(f.lease)))
        assertEquals(CommandPhase.RETRY_WAIT, retry.phase)
        assertEquals(START + 90_000, retry.retryAtMillis)
        assertEquals(CommandIssue.TEMPORARILY_UNAVAILABLE, retry.issue)
    }

    @Test fun retryBudgetStopsAfterEightAttemptsAndNeverCreatesNewKey() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent()))
        f.exchange = { _, _ -> PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
        repeat(8) { attempt ->
            val result = assertNotNull(value(f.queue.dispatchNext(f.lease)))
            assertEquals(attempt + 1, result.attempts)
            if (attempt < 7) {
                assertEquals(CommandPhase.RETRY_WAIT, result.phase)
                assertTrue(result.retryAtMillis - f.now in 1_000L..45_000L)
                f.now = result.retryAtMillis
            } else {
                assertEquals(CommandPhase.NEEDS_RESOLUTION, result.phase)
                assertEquals(CommandIssue.RETRY_EXHAUSTED, result.issue)
            }
        }
        assertNull(value(f.queue.dispatchNext(f.lease)))
        assertNull(value(f.queue.dispatchConfirmed(f.lease, ID)))
        assertEquals(List(8) { ID }, f.sent.map { it.idempotencyKey!!.use { key -> key } })
        assertEquals(START, f.stored(ID).firstAttemptAt)
    }

    @Test fun sixDayUncertaintyCutoffRequiresReconciliationWithoutReplay() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent()))
        f.exchange = { _, _ -> PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
        value(f.queue.dispatchNext(f.lease))
        f.now = START + 6L * 24 * 60 * 60 * 1000
        assertNull(value(f.queue.dispatchNext(f.lease)))
        assertEquals(CommandPhase.NEEDS_RESOLUTION, f.stored(ID).phase)
        assertEquals(CommandIssue.RECONCILIATION_REQUIRED, f.stored(ID).issue)
        assertNull(value(f.queue.dispatchConfirmed(f.lease, ID)))
        assertEquals(1, f.sent.size)
    }

    @Test fun neverAttemptedIntentDoesNotAcquireFalseIdempotencyAge() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent()))
        f.now += 30L * 24 * 60 * 60 * 1000
        val result = assertNotNull(value(f.queue.dispatchNext(f.lease)))
        assertEquals(CommandPhase.RECEIPT_READY, result.phase)
        assertEquals(f.now, f.stored(ID).firstAttemptAt)
        assertEquals(1, f.gateCalls)
    }

    @Test fun observedClockRollbackRequiresResolutionAndCannotEnqueueNewIntent() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent()))
        f.now--
        failure(FailureReason.INVALID_DATA, f.queue.enqueue(f.lease, reactionIntent(ID2)))
        assertNull(value(f.queue.dispatchNext(f.lease)))
        assertEquals(CommandIssue.CLOCK_CHANGED, f.stored(ID).issue)
        assertTrue(f.sent.isEmpty())
    }

    @Test fun oversizedRetryAfterSaturatesWithoutImmediateReplayOrOverflow() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent()))
        f.exchange = { _, _ -> PortResult.Failure(FailureReason.RATE_LIMITED, Long.MAX_VALUE) }
        assertEquals(Long.MAX_VALUE, assertNotNull(value(f.queue.dispatchNext(f.lease))).retryAtMillis)
        assertNull(value(f.queue.dispatchNext(f.lease)))
        assertEquals(1, f.sent.size)
    }

    @Test fun permanentCanonicalHttpProblemsNeverAutomaticallyReplay() = runTest {
        val expectations = mapOf(400 to CommandIssue.INVALID_REQUEST, 401 to CommandIssue.AUTH_REQUIRED,
            403 to CommandIssue.PERMISSION_CHANGED, 404 to CommandIssue.NOT_FOUND, 409 to CommandIssue.CONFLICT,
            410 to CommandIssue.GONE, 412 to CommandIssue.VERSION_CONFLICT, 422 to CommandIssue.INVALID_REQUEST,
            428 to CommandIssue.INVALID_REQUEST, 500 to CommandIssue.OUTCOME_UNKNOWN)
        for ((status, issue) in expectations) {
            val f = Fixture(StandardTestDispatcher(testScheduler))
            value(f.queue.enqueue(f.lease, deleteIntent()))
            f.exchange = { _, _ -> PortResult.Value(problem(status, retryAfter = 5)) }
            val result = assertNotNull(value(f.queue.dispatchNext(f.lease)))
            assertEquals(CommandPhase.NEEDS_RESOLUTION, result.phase, "HTTP $status")
            assertEquals(issue, result.issue, "HTTP $status")
            f.now += 60_000
            assertNull(value(f.queue.dispatchNext(f.lease)))
            assertNull(value(f.queue.dispatchConfirmed(f.lease, ID)))
            assertEquals(1, f.sent.size)
            assertEquals("\"7\"", f.stored(ID).request.ifMatch)
        }
    }

    @Test fun localAuthAndConfigurationFailuresBecomeExplicitResolutionHolds() = runTest {
        for ((reason, issue) in listOf(FailureReason.UNAUTHENTICATED to CommandIssue.AUTH_REQUIRED,
            FailureReason.FORBIDDEN to CommandIssue.PERMISSION_CHANGED,
            FailureReason.NOT_CONFIGURED to CommandIssue.NOT_CONFIGURED,
            FailureReason.INVALID_DATA to CommandIssue.INVALID_REQUEST)) {
            val f = Fixture(StandardTestDispatcher(testScheduler))
            value(f.queue.enqueue(f.lease, deleteIntent()))
            f.exchange = { _, _ -> PortResult.Failure(reason) }
            val result = assertNotNull(value(f.queue.dispatchNext(f.lease)))
            assertEquals(CommandPhase.NEEDS_RESOLUTION, result.phase)
            assertEquals(issue, result.issue)
            assertNull(value(f.queue.dispatchNext(f.lease)))
        }
    }

    @Test fun thrownTransportErrorIsSanitizedAsUnknownOriginalAttempt() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent()))
        f.exchange = { _, _ -> throw IllegalArgumentException("private failure details") }
        val result = assertNotNull(value(f.queue.dispatchNext(f.lease)))
        assertEquals(CommandIssue.OUTCOME_UNKNOWN, result.issue)
        assertEquals(CommandPhase.RETRY_WAIT, result.phase)
        assertFalse(f.store.text(metadata(ID))!!.contains("private failure"))
    }

    @Test fun malformedOrUnexpectedReplyCannotCreateSuccessReceipt() = runTest {
        for (reply in listOf(
            ApiReply(200, json("{}"), contentType = "application/json"),
            ApiReply(204, json("{}"), contentType = "application/json"),
            ApiReply(204, PrivateBytes(byteArrayOf(0x80.toByte())), contentType = "application/json"),
            ApiReply(503, json("{}"), contentType = "application/problem+json"),
            ApiReply(401, problem(403).body, contentType = "application/problem+json"),
        )) {
            val f = Fixture(StandardTestDispatcher(testScheduler))
            value(f.queue.enqueue(f.lease, deleteIntent()))
            f.exchange = { _, _ -> PortResult.Value(reply) }
            val result = assertNotNull(value(f.queue.dispatchNext(f.lease)))
            assertEquals(CommandPhase.RETRY_WAIT, result.phase)
            assertEquals(CommandIssue.OUTCOME_UNKNOWN, result.issue)
            assertNull(value(f.queue.receipt(f.lease, ID)))
        }
    }

    @Test fun successfulBodyMustSatisfyFullCanonicalSchema() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, cookIntent()))
        f.exchange = { _, _ -> PortResult.Value(ApiReply(200, json("{}"), contentType = "application/json")) }
        val result = assertNotNull(value(f.queue.dispatchNext(f.lease)))
        assertEquals(CommandIssue.OUTCOME_UNKNOWN, result.issue)
        assertNull(value(f.queue.receipt(f.lease, ID)))
    }

    @Test fun receiptReadAndApplyRevalidatePersistedBody() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, cookIntent()))
        f.exchange = { _, _ -> PortResult.Value(cookReply()) }
        val result = assertNotNull(value(f.queue.dispatchNext(f.lease)))
        f.store.corrupt(receiptKey(ID), json("{}"))
        failure(FailureReason.INVALID_DATA, f.queue.receipt(f.lease, ID))
        failure(FailureReason.INVALID_DATA, f.queue.applyReceipt(f.lease, ID, result.localRevision, listOf(put(DRAFT, "must-not-apply"))))
        assertNull(f.store.peek(DRAFT))
        assertEquals(CommandPhase.RECEIPT_READY, f.stored(ID).phase)
    }

    @Test fun neverAttemptedCommandCanBeDiscardedAtomicallyButItsIdCannotBeReused() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val queued = value(f.queue.enqueue(f.lease, cookIntent(), listOf(put(DRAFT, "draft"))))
        value(f.queue.discardUnsent(f.lease, ID, queued.localRevision, listOf(put(DRAFT, "removed", f.store.peek(DRAFT)!!.revision))))
        assertEquals("removed", f.store.text(DRAFT))
        assertEquals(CommandPhase.DISCARDED, f.stored(ID).phase)
        assertNull(f.store.peek(request(ID)))
        assertTrue(value(f.queue.pending(f.lease)).isEmpty())
        failure(FailureReason.CONFLICT, f.queue.enqueue(f.lease, cookIntent()))
        assertTrue(f.sent.isEmpty())
    }

    @Test fun attemptedUncertainCommandCannotBeDiscardedAsUnsent() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent()))
        f.exchange = { _, _ -> PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
        val uncertain = assertNotNull(value(f.queue.dispatchNext(f.lease)))
        failure(FailureReason.CONFLICT, f.queue.discardUnsent(f.lease, ID, uncertain.localRevision))
        assertEquals(CommandPhase.RETRY_WAIT, f.stored(ID).phase)
        assertEquals(1, value(f.queue.pending(f.lease)).size)
    }

    @Test fun duplicateCommandIdCannotOverwriteOriginalRequestEvenBeforeSend() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, cookIntent()))
        failure(FailureReason.CONFLICT, f.queue.enqueue(f.lease, cookIntent(body = json("{\"deviceSequence\":99}"))))
        assertEquals("{\"deviceSequence\":1}", f.store.text(request(ID)))
        assertEquals(1, value(f.queue.pending(f.lease)).size)
    }

    @Test fun invalidIdentityDependenciesOrCanonicalRequestAreRejectedBeforePersistence() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val base = deleteIntent()
        val invalid = listOf(
            CommandIntent("invalid", ORIGIN, base.call),
            CommandIntent(ID.uppercase(), ORIGIN, base.call),
            CommandIntent(ID, "token-not-origin-reference", base.call),
            CommandIntent(ID, ORIGIN, ApiCall("removePantryItem", mapOf("ingredientId" to RESOURCE), idempotencyKey = SecretText(ID2), ifMatch = "\"7\"")),
            CommandIntent(ID, ORIGIN, base.call, listOf(ID)),
            CommandIntent(ID, ORIGIN, base.call, listOf(ID2, ID2)),
            CommandIntent(ID, ORIGIN, base.call, listOf(ID2)),
            CommandIntent(ID, ORIGIN, base.call, List(65) { uuid(it + 20) }),
            cookIntent(body = json("{}")),
            cookIntent(body = json("{\"deviceSequence\":1,\"deviceSequence\":2}")),
        )
        invalid.forEach { failure(FailureReason.INVALID_DATA, f.queue.enqueue(f.lease, it)) }
        assertTrue(f.store.records.isEmpty())
        assertTrue(f.sent.isEmpty())
    }

    @Test fun readsAndSpecializedCredentialCapabilityWorkflowsCannotEnterGenericJournal() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val calls = listOf(
            ApiCall("getMe"),
            ApiCall("unknownOperation", idempotencyKey = SecretText(ID)),
            ApiCall("requestAccountExport", body = json("{\"format\":\"json\",\"includeMedia\":false}"), idempotencyKey = SecretText(ID)),
            ApiCall("registerPushDevice", body = json("{\"platform\":\"android\",\"pushToken\":\"private-push-token\",\"permission\":\"granted\",\"installationId\":\"test-installation\"}"), idempotencyKey = SecretText(ID)),
            ApiCall("createGuestSession", body = json("{}"), idempotencyKey = SecretText(ID)),
            ApiCall("reconcileEntitlements", body = json("{}"), idempotencyKey = SecretText(ID)),
            ApiCall("prepareMediaUpload", body = json("{}"), idempotencyKey = SecretText(ID)),
            ApiCall("completeMediaUpload", body = json("{}"), idempotencyKey = SecretText(ID)),
        )
        calls.forEach { failure(FailureReason.INVALID_DATA, f.queue.enqueue(f.lease, CommandIntent(ID, ORIGIN, it))) }
        assertTrue(f.store.records.isEmpty())
        assertEquals(0, f.store.readCount)
        assertTrue(f.sent.isEmpty())
    }

    @Test fun guestPrincipalCanJournalItsOwnCookButNotAccountOnlySocialIntent() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler), ActorKind.GUEST)
        value(f.queue.enqueue(f.lease, cookIntent()))
        failure(FailureReason.INVALID_DATA, f.queue.enqueue(f.lease, reactionIntent(ID2)))
        assertEquals("GUEST", f.stored(ID).scopeActorKind)
        assertEquals(1, value(f.queue.pending(f.lease)).size)
    }

    @Test fun reservedOrDuplicateDomainKeysCannotMutateJournalRecords() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        failure(FailureReason.INVALID_DATA, f.queue.enqueue(f.lease, deleteIntent(), listOf(put(INDEX, "fake"))))
        failure(FailureReason.INVALID_DATA, f.queue.enqueue(f.lease, deleteIntent(), listOf(put(DRAFT, "one"), put(DRAFT, "two"))))
        failure(FailureReason.INVALID_DATA, f.queue.enqueue(f.lease, deleteIntent(), List(62) { put(RecordKey("draft", "$it"), "x") }))
        assertTrue(f.store.records.isEmpty())
        val queued = value(f.queue.enqueue(f.lease, deleteIntent()))
        failure(FailureReason.INVALID_DATA, f.queue.discardUnsent(f.lease, ID, queued.localRevision, listOf(put(metadata(ID), "fake"))))
        val received = assertNotNull(value(f.queue.dispatchNext(f.lease)))
        failure(FailureReason.INVALID_DATA, f.queue.applyReceipt(f.lease, ID, received.localRevision, listOf(put(INDEX, "fake"))))
        failure(FailureReason.INVALID_DATA, f.queue.applyReceipt(f.lease, ID, received.localRevision,
            List(61) { put(RecordKey("domain", "$it"), "x") }))
        assertEquals(CommandPhase.RECEIPT_READY, f.stored(ID).phase)
    }

    @Test fun queueCapacityIsBoundedWithoutEvictingExistingIntent() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        repeat(128) { value(f.queue.enqueue(f.lease, deleteIntent(uuid(it + 1)))) }
        failure(FailureReason.UNAVAILABLE, f.queue.enqueue(f.lease, deleteIntent(uuid(129))))
        assertEquals(128, value(f.queue.pending(f.lease)).size)
        assertTrue(f.sent.isEmpty())
    }

    @Test fun corruptPersistedRequestIsRejectedBeforeGateOrNetwork() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, cookIntent()))
        f.store.corrupt(request(ID), json("{\"deviceSequence\":-1}"))
        failure(FailureReason.INVALID_DATA, f.queue.dispatchNext(f.lease))
        assertEquals(0, f.gateCalls)
        assertTrue(f.sent.isEmpty())
    }

    @Test fun missingRequestAndUnsupportedJournalSchemaFailClosed() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, cookIntent()))
        f.store.records.remove(request(ID))
        failure(FailureReason.INVALID_DATA, f.queue.dispatchNext(f.lease))
        f.store.records[INDEX] = f.store.peek(INDEX)!!.copy(schemaVersion = 2)
        failure(FailureReason.INVALID_DATA, f.queue.pending(f.lease))
        assertTrue(f.sent.isEmpty())
    }

    @Test fun crossOwnerMetadataFailsClosedWithoutDispatch() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent()))
        f.store.corrupt(metadata(ID), JournalCodec.encodeCommand(f.stored(ID).copy(scopeActorId = "different-owner")))
        failure(FailureReason.STALE_SESSION, f.queue.pending(f.lease))
        failure(FailureReason.STALE_SESSION, f.queue.dispatchNext(f.lease))
        assertTrue(f.sent.isEmpty())
    }

    @Test fun indexChangeDuringSnapshotRejectsMixedRevisionState() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent()))
        var indexReads = 0
        f.store.afterRead = { key ->
            if (key == INDEX && ++indexReads == 1) {
                f.store.bumpRevision(INDEX)
            }
        }
        failure(FailureReason.CONFLICT, f.queue.pending(f.lease))
        assertTrue(f.sent.isEmpty())
    }

    @Test fun diagnosticsDoNotExposePrivateIdentityBodyOrVersion() {
        val intent = cookIntent(body = json("{\"deviceSequence\":1,\"currentStepId\":\"secret-step\"}"))
        val command = CommandView(ID, "updateCookSession", CommandPhase.READY, 0, CommandIssue.NONE, START, 1)
        for (text in listOf(intent.toString(), command.toString(), CommandReceipt(command, cookReply()).toString())) {
            assertFalse(text.contains(ID))
            assertFalse(text.contains(ORIGIN))
            assertFalse(text.contains(RESOURCE))
            assertFalse(text.contains("secret-step"))
        }
    }

    @Test fun repeatedProvenOfflineFailuresDoNotConsumeRemoteAttemptBudget() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent()))
        f.exchange = { _, _ -> PortResult.Failure(FailureReason.OFFLINE) }
        repeat(12) {
            val waiting = assertNotNull(value(f.queue.dispatchNext(f.lease)))
            assertEquals(CommandPhase.READY, waiting.phase)
            assertEquals(CommandIssue.OFFLINE, waiting.issue)
            assertEquals(0, waiting.attempts)
            assertNull(f.stored(ID).firstAttemptAt)
            assertTrue(waiting.retryAtMillis > f.now)
            f.now = waiting.retryAtMillis
        }
        f.exchange = { _, _ -> PortResult.Value(ApiReply(204, null)) }
        val result = assertNotNull(value(f.queue.dispatchNext(f.lease)))
        assertEquals(CommandPhase.RECEIPT_READY, result.phase)
        assertEquals(1, result.attempts)
        assertEquals(f.now, f.stored(ID).firstAttemptAt)
    }

    @Test fun localAuthenticationRepairResumesExactOriginalIntentThroughFreshGate() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, cookIntent()))
        f.exchange = { _, _ -> PortResult.Failure(FailureReason.UNAUTHENTICATED) }
        val held = assertNotNull(value(f.queue.dispatchNext(f.lease)))
        assertEquals(CommandIssue.AUTH_REQUIRED, held.issue)
        assertEquals(0, held.attempts)
        assertNull(f.stored(ID).firstAttemptAt)
        val original = f.stored(ID)
        failure(FailureReason.CONFLICT, f.queue.resumeAfterResolution(f.lease, ID, held.localRevision - 1))
        val resumed = value(f.newQueue().resumeAfterResolution(f.lease, ID, held.localRevision))
        assertEquals(CommandPhase.READY, resumed.phase)
        assertEquals(1, f.sent.size)
        assertEquals(original.request, f.stored(ID).request)
        assertEquals(original.originBinding, f.stored(ID).originBinding)
        assertEquals(original.retryAt, resumed.retryAtMillis)
        f.exchange = { _, _ -> PortResult.Value(cookReply()) }
        val completed = assertNotNull(value(f.queue.dispatchNext(f.lease)))
        assertEquals(CommandPhase.RECEIPT_READY, completed.phase)
        assertEquals(1, completed.attempts)
        assertEquals(2, f.gateCalls)
        assertEquals(listOf(ID, ID), f.sent.map { it.idempotencyKey!!.use { key -> key } })
        assertContentEquals(f.sent[0].body!!.copyForCodec(), f.sent[1].body!!.copyForCodec())
        assertEquals(f.sent[0].ifMatch, f.sent[1].ifMatch)
    }

    @Test fun httpAuthenticationFailureStillCountsAsRemoteAttemptWhenResumed() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent()))
        f.exchange = { _, _ -> PortResult.Value(problem(401)) }
        val held = assertNotNull(value(f.queue.dispatchNext(f.lease)))
        assertEquals(1, held.attempts)
        assertEquals(START, f.stored(ID).firstAttemptAt)
        val resumed = value(f.queue.resumeAfterResolution(f.lease, ID, held.localRevision))
        assertEquals(CommandPhase.RETRY_WAIT, resumed.phase)
        assertEquals(1, resumed.attempts)
        f.exchange = { _, _ -> PortResult.Value(ApiReply(204, null)) }
        assertEquals(2, assertNotNull(value(f.queue.dispatchNext(f.lease))).attempts)
    }

    @Test fun repairedManualActionStillNeedsNewConfirmationAndGate() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, feedbackDeleteIntent()))
        f.preflight = { _, _ -> ExecutionDecision.Wait(CommandIssue.NOT_CONFIGURED) }
        val held = assertNotNull(value(f.queue.dispatchConfirmed(f.lease, ID)))
        assertEquals(0, held.attempts)
        val resumed = value(f.queue.resumeAfterResolution(f.lease, ID, held.localRevision))
        assertEquals(CommandPhase.AWAITING_CONFIRMATION, resumed.phase)
        assertNull(value(f.queue.dispatchNext(f.lease)))
        assertTrue(f.sent.isEmpty())
        f.preflight = { _, _ -> ExecutionDecision.Ready }
        assertEquals(CommandPhase.RECEIPT_READY, assertNotNull(value(f.queue.dispatchConfirmed(f.lease, ID))).phase)
        assertEquals(2, f.gateCalls)
    }

    @Test fun resolutionCannotClearConflictOrExpiredUncertainty() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent()))
        f.exchange = { _, _ -> PortResult.Value(problem(412)) }
        val conflict = assertNotNull(value(f.queue.dispatchNext(f.lease)))
        failure(FailureReason.CONFLICT, f.queue.resumeAfterResolution(f.lease, ID, conflict.localRevision))
        assertEquals(CommandIssue.VERSION_CONFLICT, f.stored(ID).issue)

        val expired = Fixture(StandardTestDispatcher(testScheduler))
        value(expired.queue.enqueue(expired.lease, deleteIntent()))
        expired.exchange = { _, _ -> PortResult.Value(problem(401)) }
        val auth = assertNotNull(value(expired.queue.dispatchNext(expired.lease)))
        expired.now += 6L * 24 * 60 * 60 * 1000
        val stillHeld = value(expired.queue.resumeAfterResolution(expired.lease, ID, auth.localRevision))
        assertEquals(CommandPhase.NEEDS_RESOLUTION, stillHeld.phase)
        assertEquals(CommandIssue.RECONCILIATION_REQUIRED, stillHeld.issue)
        assertEquals(1, expired.sent.size)
    }

    @Test fun elapsedReplayWindowWhileClaimCommitSuspendsPreventsTransport() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent()))
        f.store.afterCommit = {
            f.now += 6L * 24 * 60 * 60 * 1000
            f.store.afterCommit = {}
        }
        val held = assertNotNull(value(f.queue.dispatchNext(f.lease)))
        assertEquals(CommandPhase.NEEDS_RESOLUTION, held.phase)
        assertEquals(CommandIssue.RECONCILIATION_REQUIRED, held.issue)
        assertEquals(0, held.attempts)
        assertTrue(f.sent.isEmpty())
        assertEquals(0, value(f.newQueue().recoverInterrupted(f.lease)))
    }

    @Test fun cookingSequenceMustIncreaseWithinOriginAndSessionAcrossRestart() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, cookIntent(body = json("{\"deviceSequence\":2}"))))
        failure(FailureReason.CONFLICT, f.newQueue().enqueue(f.lease, cookIntent(ID2)))
        failure(FailureReason.CONFLICT, f.newQueue().enqueue(f.lease, cookIntent(ID2, body = json("{\"deviceSequence\":2e0}"))))
        value(f.newQueue().enqueue(f.lease, cookIntent(ID2, body = json("{\"deviceSequence\":3.0}"))))
        assertEquals("3", f.store.text(sequenceKey()))
        assertEquals(2, value(f.queue.pending(f.lease)).size)
        assertTrue(f.sent.isEmpty())
    }

    @Test fun cookingSequenceWatermarkSurvivesAppliedAndDiscardedCommands() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, cookIntent()))
        f.exchange = { _, _ -> PortResult.Value(cookReply()) }
        val received = assertNotNull(value(f.queue.dispatchNext(f.lease)))
        value(f.queue.applyReceipt(f.lease, ID, received.localRevision, emptyList()))
        failure(FailureReason.CONFLICT, f.newQueue().enqueue(f.lease, cookIntent(ID2)))
        val queued = value(f.queue.enqueue(f.lease, cookIntent(ID2, body = json("{\"deviceSequence\":2}"))))
        value(f.queue.discardUnsent(f.lease, ID2, queued.localRevision))
        failure(FailureReason.CONFLICT, f.newQueue().enqueue(f.lease, cookIntent(ID3, body = json("{\"deviceSequence\":2}"))))
        assertEquals("2", f.store.text(sequenceKey()))
        assertTrue(value(f.queue.pending(f.lease)).isEmpty())
    }

    @Test fun cookingSequenceIsBoundedAndFailedEnqueueDoesNotAdvanceWatermark() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        failure(FailureReason.INVALID_DATA, f.queue.enqueue(f.lease, cookIntent(body = json("{\"deviceSequence\":9223372036854775808}"))))
        assertNull(f.store.peek(sequenceKey()))
        f.store.nextCommitFailure = FailureReason.STORAGE_FAILURE
        failure(FailureReason.STORAGE_FAILURE, f.queue.enqueue(f.lease, cookIntent(body = json("{\"deviceSequence\":9}"))))
        assertNull(f.store.peek(sequenceKey()))
        value(f.queue.enqueue(f.lease, cookIntent()))
        assertEquals("1", f.store.text(sequenceKey()))
    }

    @Test fun canonicalProblemBodyRetryDelayUsesExactExponentAndGreaterGuidance() = runTest {
        for ((status, token, header, expectedSeconds) in listOf(
            DelayCase(429, "1.2e2", 30, 120), DelayCase(503, "30", 120, 120),
            DelayCase(503, "1e3", null, 1000),
        )) {
            val f = Fixture(StandardTestDispatcher(testScheduler))
            value(f.queue.enqueue(f.lease, deleteIntent()))
            f.exchange = { _, _ -> PortResult.Value(problemWithDelay(status, token, header)) }
            val waiting = assertNotNull(value(f.queue.dispatchNext(f.lease)))
            assertEquals(CommandPhase.RETRY_WAIT, waiting.phase)
            assertEquals(START + expectedSeconds * 1000, waiting.retryAtMillis)
            assertEquals(CommandIssue.TEMPORARILY_UNAVAILABLE, waiting.issue)
        }
    }

    @Test fun enormousCanonicalProblemRetryDelaySaturatesSafely() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, deleteIntent()))
        f.exchange = { _, _ -> PortResult.Value(problemWithDelay(429, "1e100", null)) }
        val waiting = assertNotNull(value(f.queue.dispatchNext(f.lease)))
        assertEquals(CommandPhase.RETRY_WAIT, waiting.phase)
        assertEquals(Long.MAX_VALUE, waiting.retryAtMillis)
        assertEquals(CommandIssue.TEMPORARILY_UNAVAILABLE, waiting.issue)
        assertNull(value(f.queue.dispatchNext(f.lease)))
    }

    private data class DelayCase(val status: Int, val token: String, val header: Long?, val expectedSeconds: Long)

    private class Fixture(dispatcher: CoroutineDispatcher, kind: ActorKind = ActorKind.ACCOUNT) {
        val scope = StorageScope("test", kind, "private-owner")
        val boundary = SessionBoundary()
        val lease = boundary.activate(scope)
        val store = CasStore(scope)
        var now = START
        var gateCalls = 0
        val gated = mutableListOf<CommandIntent>()
        val sent = mutableListOf<ApiCall>()
        var preflight: suspend (SessionLease, CommandIntent) -> ExecutionDecision = { _, _ -> ExecutionDecision.Ready }
        var exchange: suspend (SessionLease, ApiCall) -> PortResult<ApiReply> = { _, _ -> PortResult.Value(ApiReply(204, null)) }
        private val ownerDispatcher = dispatcher
        val queue = newQueue()
        fun newQueue() = DurableCommandQueue(scope, store, boundary, ownerDispatcher, EpochClock { now },
            CommandExecutionGate { lease, intent -> gateCalls++; gated += intent; preflight(lease, intent) },
            object : AccountTransport {
                override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                    sent += call
                    return exchange(lease, call)
                }
            })
        fun stored(id: String): JournalCommand = JournalCodec.decodeCommand(store.peek(metadata(id))!!.payload)
    }

    /** Atomic all-or-nothing fake with detached payloads and monotonic tombstone revisions. */
    private class CasStore(private val owner: StorageScope) : PrivateStateStore {
        val records = mutableMapOf<RecordKey, PrivateRecord>()
        private val revisions = mutableMapOf<RecordKey, Long>()
        val commits = mutableListOf<List<StoreMutation>>()
        var readCount = 0
        var nextCommitFailure: FailureReason? = null
        var afterRead: suspend (RecordKey) -> Unit = {}
        var afterCommit: suspend () -> Unit = {}
        fun peek(key: RecordKey) = records[key]
        fun text(key: RecordKey) = peek(key)?.payload?.copyForCodec()?.decodeToString()
        fun corrupt(key: RecordKey, payload: PrivateBytes) { records[key] = checkNotNull(records[key]).copy(payload = payload) }
        fun bumpRevision(key: RecordKey) {
            val record = checkNotNull(records[key])
            val revision = record.revision + 1
            records[key] = record.copy(revision = revision)
            revisions[key] = revision
        }
        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> {
            if (scope != owner) return PortResult.Failure(FailureReason.STALE_SESSION)
            readCount++
            val detached = records[key]?.let { it.copy(payload = PrivateBytes(it.payload.copyForCodec())) }
            afterRead(key)
            return PortResult.Value(detached)
        }
        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> {
            if (scope != owner) return PortResult.Failure(FailureReason.STALE_SESSION)
            nextCommitFailure?.let { nextCommitFailure = null; return PortResult.Failure(it) }
            if (mutations.size !in 1..64 || mutations.map { it.key }.toSet().size != mutations.size)
                return PortResult.Failure(FailureReason.INVALID_DATA)
            if (mutations.any { mutation -> mutation.expectedRevision != records[mutation.key]?.revision })
                return PortResult.Failure(FailureReason.CONFLICT)
            val result = mutableMapOf<RecordKey, Long?>()
            for (mutation in mutations) {
                val revision = (revisions[mutation.key] ?: 0L) + 1
                revisions[mutation.key] = revision
                when (mutation) {
                    is StoreMutation.Put -> {
                        records[mutation.key] = PrivateRecord(revision, mutation.schemaVersion, PrivateBytes(mutation.payload.copyForCodec()))
                        result[mutation.key] = revision
                    }
                    is StoreMutation.Delete -> { records.remove(mutation.key); result[mutation.key] = null }
                }
            }
            commits += mutations.toList()
            afterCommit()
            return PortResult.Value(result)
        }
        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> = error("Queue must not erase or activate identities")
    }

    companion object {
        private const val ID = "123e4567-e89b-12d3-a456-426614174001"
        private const val ID2 = "123e4567-e89b-12d3-a456-426614174002"
        private const val ID3 = "123e4567-e89b-12d3-a456-426614174003"
        private const val ORIGIN = "123e4567-e89b-12d3-a456-426614174090"
        private const val RESOURCE = "123e4567-e89b-12d3-a456-426614174080"
        private const val OTHER_RESOURCE = "123e4567-e89b-12d3-a456-426614174081"
        private const val START = 1_800_000_000_000L
        private val INDEX = RecordKey("feedme.command.index", "v1")
        private val DRAFT = RecordKey("cooking.draft", "private-draft")
        private fun metadata(id: String) = RecordKey("feedme.command.metadata", id)
        private fun request(id: String) = RecordKey("feedme.command.request", id)
        private fun receiptKey(id: String) = RecordKey("feedme.command.receipt", id)
        private fun sequenceKey() = RecordKey("feedme.command.cook-sequence", "$ORIGIN:$RESOURCE")
        private fun json(text: String) = PrivateBytes(text.encodeToByteArray())
        private fun put(key: RecordKey, text: String, revision: Long? = null) = StoreMutation.Put(key, revision, 1, json(text))
        private fun uuid(value: Int) = "123e4567-e89b-12d3-a456-${value.toString().padStart(12, '0')}"
        private fun deleteIntent(id: String = ID, ingredient: String = RESOURCE) = CommandIntent(id, ORIGIN,
            ApiCall("removePantryItem", mapOf("ingredientId" to ingredient), idempotencyKey = SecretText(id), ifMatch = "\"7\""))
        private fun cookIntent(id: String = ID, body: PrivateBytes = json("{\"deviceSequence\":1}"), session: String = RESOURCE) = CommandIntent(id, ORIGIN,
            ApiCall("updateCookSession", mapOf("sessionId" to session), body = body, idempotencyKey = SecretText(id), ifMatch = "\"7\""))
        private fun reactionIntent(id: String = ID, dependencies: List<String> = emptyList()) = CommandIntent(id, ORIGIN,
            ApiCall("removeReaction", mapOf("postId" to RESOURCE), idempotencyKey = SecretText(id), ifMatch = "\"7\""), dependencies)
        private fun feedbackDeleteIntent() = CommandIntent(ID, ORIGIN,
            ApiCall("deleteFeedback", mapOf("feedbackId" to RESOURCE), idempotencyKey = SecretText(ID), ifMatch = "\"7\""))
        private fun cookReply() = ApiReply(200, json("""{"id":"$RESOURCE","version":8,"createdAt":"2026-09-13T07:00:00Z","updatedAt":"2026-09-13T08:00:00Z","planId":"$OTHER_RESOURCE","status":"active","currentStepId":"step-one","completedStepIds":[],"deviceSequence":1,"timers":[]}"""),
            etag = "\"8\"", contentType = "application/json")
        private fun problem(status: Int, retryAfter: Long? = null) = ApiReply(status,
            json("""{"type":"https://example.test/problems/rejected","title":"Action unavailable","status":$status,"code":"TEST_REJECTED","traceId":"test-trace"}"""),
            retryAfterSeconds = retryAfter, contentType = "application/problem+json")
        private fun problemWithDelay(status: Int, token: String, header: Long?) = ApiReply(status,
            json("""{"type":"https://example.test/problems/rejected","title":"Action unavailable","status":$status,"code":"TEST_REJECTED","traceId":"test-trace","retryAfterSeconds":$token}"""),
            retryAfterSeconds = header, contentType = "application/problem+json")
        private fun <T> value(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
        private fun failure(reason: FailureReason, result: PortResult<*>) {
            assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
        }
    }
}
