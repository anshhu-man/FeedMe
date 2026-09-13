package com.feedme.sync

import com.feedme.core.ports.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Exact-target scheduling tests; the detached CAS fixture is not a native storage adapter. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TargetedAutomaticDispatchTest {
    @Test fun exactReadOnlyLookupFindsReadyAndTerminalIdentitiesWithoutDispatch() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        failure(FailureReason.INVALID_DATA, f.queue.command(f.lease, "not-a-command-id"))
        assertNull(value(f.queue.command(f.lease, ID)))
        val queued = value(f.queue.enqueue(f.lease, pantryIntent(ID)))
        val ready = assertNotNull(value(f.queue.command(f.lease, ID)))
        assertEquals(ID, ready.commandId)
        assertEquals("removePantryItem", ready.operationId)
        assertEquals(CommandPhase.READY, ready.phase)
        assertEquals(queued.localRevision, ready.localRevision)
        assertTrue(f.gated.isEmpty())
        assertTrue(f.sent.isEmpty())

        val receipt = assertNotNull(value(f.queue.dispatchAutomatic(f.lease, ID)))
        value(f.queue.applyReceipt(f.lease, ID, receipt.localRevision, emptyList()))
        assertTrue(value(f.queue.pending(f.lease)).isEmpty())
        val applied = assertNotNull(value(f.queue.command(f.lease, ID)))
        assertEquals(CommandPhase.APPLIED, applied.phase)
        assertEquals("removePantryItem", applied.operationId)
        assertTrue(applied.localRevision > receipt.localRevision)

        val discardable = value(f.queue.enqueue(f.lease, reactionIntent(ID2)))
        value(f.queue.discardUnsent(f.lease, ID2, discardable.localRevision))
        assertEquals(CommandPhase.DISCARDED, assertNotNull(value(f.queue.command(f.lease, ID2))).phase)
        assertNull(value(f.queue.command(f.lease, ID3)))
        assertEquals(listOf(ID), f.gated)
        assertEquals(1, f.sent.size)
    }

    @Test fun targetsCookingCommandWithoutSendingEarlierUnrelatedModule() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, pantryIntent(ID)))
        value(f.queue.enqueue(f.lease, cookIntent(ID2)))

        val result = assertNotNull(value(f.queue.dispatchAutomatic(f.lease, ID2)))
        assertEquals(ID2, result.commandId)
        assertEquals(CommandPhase.RECEIPT_READY, result.phase)
        assertEquals(listOf(ID2), f.gated)
        assertEquals(listOf("updateCookSession"), f.sent.map { it.operationId })
        assertEquals(CommandPhase.READY, f.stored(ID).phase)
        assertEquals(0, f.stored(ID).attempts)

        // The untargeted worker API still selects the first eligible independent lane.
        assertEquals(ID, assertNotNull(value(f.queue.dispatchNext(f.lease))).commandId)
        assertEquals(listOf("updateCookSession", "removePantryItem"), f.sent.map { it.operationId })
    }

    @Test fun unknownMalformedAndCompletedTargetsNeverFallBack() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, pantryIntent(ID)))
        value(f.queue.enqueue(f.lease, reactionIntent(ID2)))

        assertNull(value(f.queue.dispatchAutomatic(f.lease, ID3)))
        failure(FailureReason.INVALID_DATA, f.queue.dispatchAutomatic(f.lease, "not-a-command-id"))
        assertTrue(f.gated.isEmpty())
        assertTrue(f.sent.isEmpty())

        assertEquals(ID2, assertNotNull(value(f.queue.dispatchAutomatic(f.lease, ID2))).commandId)
        assertNull(value(f.queue.dispatchAutomatic(f.lease, ID2)))
        assertEquals(listOf(ID2), f.gated)
        assertEquals(1, f.sent.size)
        assertEquals(0, f.stored(ID).attempts)
    }

    @Test fun targetingManualPolicyDoesNotConfirmInitialOrRetriedAttempt() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, pantryIntent(ID)))
        val queued = value(f.queue.enqueue(f.lease, feedbackDeleteIntent(ID2)))
        assertEquals(CommandPhase.AWAITING_CONFIRMATION, queued.phase)
        assertNull(value(f.queue.dispatchAutomatic(f.lease, ID2)))
        assertTrue(f.gated.isEmpty())
        assertTrue(f.sent.isEmpty())

        f.exchange = { PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
        val uncertain = assertNotNull(value(f.queue.dispatchConfirmed(f.lease, ID2)))
        assertEquals(CommandPhase.AWAITING_CONFIRMATION, uncertain.phase)
        f.now = uncertain.retryAtMillis
        assertNull(value(f.queue.dispatchAutomatic(f.lease, ID2)))
        assertEquals(listOf(ID2), f.gated)
        assertEquals(1, f.sent.size)
        assertEquals(0, f.stored(ID).attempts)

        f.exchange = { PortResult.Value(ApiReply(204, null)) }
        assertEquals(CommandPhase.RECEIPT_READY, assertNotNull(value(f.queue.dispatchConfirmed(f.lease, ID2))).phase)
        assertEquals(listOf(ID2, ID2), f.gated)
        assertEquals(2, f.sent.size)
    }

    @Test fun exactTargetCannotSkipEarlierSameSessionEvenWhenHeadNeedsResolution() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, cookIntent(ID)))
        value(f.queue.enqueue(f.lease, cookIntent(ID2, sequence = 2)))
        value(f.queue.enqueue(f.lease, reactionIntent(ID3)))
        assertNull(value(f.queue.dispatchAutomatic(f.lease, ID2)))
        assertTrue(f.gated.isEmpty())

        f.preflight = { ExecutionDecision.Wait(CommandIssue.PERMISSION_CHANGED) }
        val held = assertNotNull(value(f.queue.dispatchAutomatic(f.lease, ID)))
        assertEquals(CommandPhase.NEEDS_RESOLUTION, held.phase)
        assertNull(value(f.queue.dispatchAutomatic(f.lease, ID2)))
        assertEquals(listOf(ID), f.gated)
        assertTrue(f.sent.isEmpty())

        f.preflight = { ExecutionDecision.Ready }
        assertEquals(ID3, assertNotNull(value(f.queue.dispatchAutomatic(f.lease, ID3))).commandId)
        assertEquals(listOf(ID, ID3), f.gated)
        assertEquals(0, f.stored(ID2).attempts)
    }

    @Test fun targetWaitsForDependencyApplicationAndNeverSendsItsParentInstead() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, pantryIntent(ID)))
        value(f.queue.enqueue(f.lease, reactionIntent(ID2, dependencies = listOf(ID))))
        assertNull(value(f.queue.dispatchAutomatic(f.lease, ID2)))
        assertTrue(f.gated.isEmpty())
        assertTrue(f.sent.isEmpty())

        val parent = assertNotNull(value(f.queue.dispatchAutomatic(f.lease, ID)))
        assertEquals(CommandPhase.RECEIPT_READY, parent.phase)
        assertNull(value(f.queue.dispatchAutomatic(f.lease, ID2)))
        assertEquals(listOf(ID), f.gated)
        value(f.queue.applyReceipt(f.lease, ID, parent.localRevision, emptyList()))
        assertEquals(ID2, assertNotNull(value(f.queue.dispatchAutomatic(f.lease, ID2))).commandId)
        assertEquals(listOf(ID, ID2), f.gated)
    }

    @Test fun discardedDependencyHoldsOnlyTargetWithoutSendingAnotherLane() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val parent = value(f.queue.enqueue(f.lease, pantryIntent(ID)))
        value(f.queue.enqueue(f.lease, reactionIntent(ID2, dependencies = listOf(ID))))
        value(f.queue.enqueue(f.lease, cookIntent(ID3)))
        value(f.queue.discardUnsent(f.lease, ID, parent.localRevision))

        assertNull(value(f.queue.dispatchAutomatic(f.lease, ID2)))
        assertEquals(CommandPhase.NEEDS_RESOLUTION, f.stored(ID2).phase)
        assertEquals(CommandIssue.DEPENDENCY_FAILED, f.stored(ID2).issue)
        assertEquals(CommandPhase.READY, f.stored(ID3).phase)
        assertTrue(f.gated.isEmpty())
        assertTrue(f.sent.isEmpty())
    }

    @Test fun automaticTargetRespectsRetryDeadlineWithoutFallingBack() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, pantryIntent(ID)))
        value(f.queue.enqueue(f.lease, reactionIntent(ID2)))
        f.exchange = { PortResult.Failure(FailureReason.RATE_LIMITED, 120) }
        val retry = assertNotNull(value(f.queue.dispatchAutomatic(f.lease, ID2)))
        assertEquals(CommandPhase.RETRY_WAIT, retry.phase)
        assertEquals(START + 120_000, retry.retryAtMillis)
        f.now = retry.retryAtMillis - 1
        assertNull(value(f.queue.dispatchAutomatic(f.lease, ID2)))
        assertEquals(listOf(ID2), f.gated)
        assertEquals(1, f.sent.size)
        assertEquals(0, f.stored(ID).attempts)

        f.now++
        f.exchange = { PortResult.Value(ApiReply(204, null)) }
        assertEquals(CommandPhase.RECEIPT_READY, assertNotNull(value(f.queue.dispatchAutomatic(f.lease, ID2))).phase)
        assertEquals(listOf(ID2, ID2), f.gated)
    }

    @Test fun expiredReplayWindowHoldsTargetInsteadOfSendingAnotherModule() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, pantryIntent(ID)))
        value(f.queue.enqueue(f.lease, reactionIntent(ID2)))
        f.exchange = { PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
        assertNotNull(value(f.queue.dispatchAutomatic(f.lease, ID2)))
        f.now += 6L * 24 * 60 * 60 * 1000

        assertNull(value(f.queue.dispatchAutomatic(f.lease, ID2)))
        assertEquals(CommandPhase.NEEDS_RESOLUTION, f.stored(ID2).phase)
        assertEquals(CommandIssue.RECONCILIATION_REQUIRED, f.stored(ID2).issue)
        assertEquals(listOf(ID2), f.gated)
        assertEquals(1, f.sent.size)
        assertEquals(0, f.stored(ID).attempts)
    }

    @Test fun targetStillRequiresUnchangedPreflightCas() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, pantryIntent(ID)))
        f.preflight = {
            value(f.queue.enqueue(f.lease, reactionIntent(ID2)))
            ExecutionDecision.Ready
        }

        failure(FailureReason.CONFLICT, f.queue.dispatchAutomatic(f.lease, ID))
        assertEquals(listOf(ID), f.gated)
        assertTrue(f.sent.isEmpty())
        assertEquals(0, f.stored(ID).attempts)
    }

    @Test fun staleLeaseBeforeOrDuringTargetPreflightNeverSends() = runTest {
        val stale = Fixture(StandardTestDispatcher(testScheduler))
        value(stale.queue.enqueue(stale.lease, pantryIntent(ID)))
        stale.boundary.clear()
        failure(FailureReason.STALE_SESSION, stale.queue.dispatchAutomatic(stale.lease, ID))
        assertTrue(stale.gated.isEmpty())
        assertTrue(stale.sent.isEmpty())

        val changed = Fixture(StandardTestDispatcher(testScheduler))
        value(changed.queue.enqueue(changed.lease, pantryIntent(ID)))
        changed.preflight = {
            changed.boundary.activate(changed.scope)
            ExecutionDecision.Ready
        }
        failure(FailureReason.STALE_SESSION, changed.queue.dispatchAutomatic(changed.lease, ID))
        assertEquals(listOf(ID), changed.gated)
        assertTrue(changed.sent.isEmpty())
        assertEquals(0, changed.stored(ID).attempts)
    }

    @Test fun boundRecallIsObservedOutsideMutexBeforePersistingTypedDurableHold() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, cookIntent(ID)))
        f.exchange = { PortResult.Value(problem(410, "RECIPE_RECALLED")) }
        f.observe = { lease, intent, reply ->
            assertEquals(f.lease, lease)
            assertEquals(ID, intent.commandId)
            assertEquals("updateCookSession", intent.call.operationId)
            assertEquals(410, reply.status)
            assertEquals(CommandPhase.IN_FLIGHT, f.stored(ID).phase)
            // This suspending queue read would deadlock if the observer held the queue mutex.
            assertEquals(CommandPhase.IN_FLIGHT, assertNotNull(value(f.queue.command(lease, ID))).phase)
            assertNull(value(f.queue.receipt(lease, ID)))
            PortResult.Value(Unit)
        }

        val held = assertNotNull(value(f.queue.dispatchAutomatic(f.lease, ID)))
        assertEquals(CommandPhase.NEEDS_RESOLUTION, held.phase)
        assertEquals(CommandIssue.RECIPE_RECALLED, held.issue)
        assertEquals(CommandIssue.RECIPE_RECALLED, f.stored(ID).issue)
        assertEquals(listOf(ID), f.observed)
        assertNull(value(f.queue.dispatchAutomatic(f.lease, ID)))
        assertNull(value(f.queue.dispatchConfirmed(f.lease, ID)))
        failure(FailureReason.CONFLICT, f.queue.resumeAfterResolution(f.lease, ID, held.localRevision))
        assertEquals(1, f.sent.size)
    }

    @Test fun boundSuccessObserverRunsBeforeSuccessfulReceiptBecomesVisible() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, cookIntent(ID)))
        f.observe = { lease, intent, reply ->
            assertEquals(ID, intent.commandId)
            assertEquals(200, reply.status)
            assertEquals(CommandPhase.IN_FLIGHT, f.stored(ID).phase)
            assertNull(value(f.queue.receipt(lease, ID)))
            PortResult.Value(Unit)
        }
        assertEquals(CommandPhase.RECEIPT_READY, assertNotNull(value(f.queue.dispatchAutomatic(f.lease, ID))).phase)
        assertEquals(listOf(ID), f.observed)
        assertNotNull(value(f.queue.receipt(f.lease, ID)))
    }

    @Test fun invalidOrWronglyBoundBodiesNeverReachObserverOrProduceRecallIssue() = runTest {
        val recall = problem(410, "RECIPE_RECALLED")
        for (reply in listOf(
            recall.copy(status = 200, contentType = "application/json"),
            recall.copy(status = 409),
            recall.copy(contentType = "application/json"),
            recall.copy(traceId = "different-trace"),
            recall.copy(body = json("{\"code\":\"RECIPE_RECALLED\"}")),
        )) {
            val f = Fixture(StandardTestDispatcher(testScheduler))
            value(f.queue.enqueue(f.lease, cookIntent(ID)))
            f.exchange = { PortResult.Value(reply) }
            val result = assertNotNull(value(f.queue.dispatchAutomatic(f.lease, ID)))
            assertEquals(CommandPhase.RETRY_WAIT, result.phase)
            assertEquals(CommandIssue.OUTCOME_UNKNOWN, result.issue)
            assertTrue(f.observed.isEmpty())
        }
        val failure = Fixture(StandardTestDispatcher(testScheduler))
        value(failure.queue.enqueue(failure.lease, cookIntent(ID)))
        failure.exchange = { PortResult.Failure(FailureReason.OFFLINE) }
        assertEquals(CommandIssue.OFFLINE, assertNotNull(value(failure.queue.dispatchAutomatic(failure.lease, ID))).issue)
        assertTrue(failure.observed.isEmpty())
    }

    @Test fun onlyExactValidatedProblemCodeProducesRecallInsteadOfGenericGoneOrRetry() = runTest {
        for (code in listOf("NOT_FOUND", "recipe_recalled", "RECIPE_RECALLED_SUFFIX")) {
            val f = Fixture(StandardTestDispatcher(testScheduler))
            value(f.queue.enqueue(f.lease, cookIntent(ID)))
            f.exchange = { PortResult.Value(problem(410, code)) }
            assertEquals(CommandIssue.GONE, assertNotNull(value(f.queue.dispatchAutomatic(f.lease, ID))).issue)
            assertEquals(listOf(ID), f.observed)
        }
        val unavailable = Fixture(StandardTestDispatcher(testScheduler))
        value(unavailable.queue.enqueue(unavailable.lease, cookIntent(ID)))
        unavailable.exchange = { PortResult.Value(problem(503, "RECIPE_RECALLED")) }
        val held = assertNotNull(value(unavailable.queue.dispatchAutomatic(unavailable.lease, ID)))
        assertEquals(CommandPhase.NEEDS_RESOLUTION, held.phase)
        assertEquals(CommandIssue.RECIPE_RECALLED, held.issue)
    }

    @Test fun preflightCannotManufactureAValidatedProtocolRecall() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        value(f.queue.enqueue(f.lease, cookIntent(ID)))
        f.preflight = { ExecutionDecision.Wait(CommandIssue.RECIPE_RECALLED) }
        val held = assertNotNull(value(f.queue.dispatchAutomatic(f.lease, ID)))
        assertEquals(CommandPhase.NEEDS_RESOLUTION, held.phase)
        assertEquals(CommandIssue.DOMAIN_RECHECK_REQUIRED, held.issue)
        assertTrue(f.sent.isEmpty())
        assertTrue(f.observed.isEmpty())
    }

    @Test fun typedObserverFailureLeavesSentAttemptInFlightWithoutReceiptOrRetry() = runTest {
        for (reply in listOf(cookReply(), problem(410, "RECIPE_RECALLED"))) {
            val f = Fixture(StandardTestDispatcher(testScheduler))
            value(f.queue.enqueue(f.lease, cookIntent(ID)))
            f.exchange = { PortResult.Value(reply) }
            f.observe = { _, _, _ -> PortResult.Failure(FailureReason.FORBIDDEN) }
            failure(FailureReason.FORBIDDEN, f.queue.dispatchAutomatic(f.lease, ID))
            assertEquals(CommandPhase.IN_FLIGHT, f.stored(ID).phase)
            assertEquals(1, f.stored(ID).attempts)
            assertNull(value(f.queue.receipt(f.lease, ID)))
            assertNull(value(f.queue.dispatchAutomatic(f.lease, ID)))
            assertEquals(1, f.sent.size)
            // A later explicit interruption-recovery operation can find the unfinished claim.
            assertEquals(1, value(f.queue.recoverInterrupted(f.lease)))
        }
    }

    @Test fun observerExceptionIsSanitizedAndCancellationIsPropagated() = runTest {
        val failed = Fixture(StandardTestDispatcher(testScheduler))
        value(failed.queue.enqueue(failed.lease, cookIntent(ID)))
        failed.exchange = { PortResult.Value(problem(410, "RECIPE_RECALLED")) }
        failed.observe = { _, _, _ -> throw IllegalStateException("private observer detail") }
        failure(FailureReason.STORAGE_FAILURE, failed.queue.dispatchAutomatic(failed.lease, ID))
        assertEquals(CommandPhase.IN_FLIGHT, failed.stored(ID).phase)
        assertTrue(failed.store.records.values.none { it.payload.copyForCodec().decodeToString().contains("private observer detail") })

        val cancelled = Fixture(StandardTestDispatcher(testScheduler))
        value(cancelled.queue.enqueue(cancelled.lease, cookIntent(ID)))
        cancelled.observe = { _, _, _ -> throw CancellationException("cancel observer") }
        assertFailsWith<CancellationException> { cancelled.queue.dispatchAutomatic(cancelled.lease, ID) }
        assertEquals(CommandPhase.IN_FLIGHT, cancelled.stored(ID).phase)
        assertEquals(1, cancelled.sent.size)
        assertEquals(1, value(cancelled.queue.recoverInterrupted(cancelled.lease)))
    }

    @Test fun staleReplyNeverReachesObserverAndObserverLeaseRetirementPreventsOutcomeCommit() = runTest {
        val stale = Fixture(StandardTestDispatcher(testScheduler))
        value(stale.queue.enqueue(stale.lease, cookIntent(ID)))
        stale.exchange = { stale.boundary.clear(); PortResult.Value(problem(410, "RECIPE_RECALLED")) }
        failure(FailureReason.STALE_SESSION, stale.queue.dispatchAutomatic(stale.lease, ID))
        assertTrue(stale.observed.isEmpty())
        assertEquals(CommandPhase.IN_FLIGHT, stale.stored(ID).phase)

        val retired = Fixture(StandardTestDispatcher(testScheduler))
        value(retired.queue.enqueue(retired.lease, cookIntent(ID)))
        retired.observe = { _, _, _ -> retired.boundary.clear(); PortResult.Value(Unit) }
        failure(FailureReason.STALE_SESSION, retired.queue.dispatchAutomatic(retired.lease, ID))
        assertEquals(listOf(ID), retired.observed)
        assertEquals(CommandPhase.IN_FLIGHT, retired.stored(ID).phase)
        assertNull(retired.store.records[RecordKey("feedme.command.receipt", ID)])
    }

    private class Fixture(dispatcher: CoroutineDispatcher) {
        val scope = StorageScope("test", ActorKind.ACCOUNT, "targeted-automatic-test-owner")
        val boundary = SessionBoundary()
        val lease = boundary.activate(scope)
        val store = CasStore(scope)
        var now = START
        val gated = mutableListOf<String>()
        val sent = mutableListOf<ApiCall>()
        val observed = mutableListOf<String>()
        var preflight: suspend (CommandIntent) -> ExecutionDecision = { ExecutionDecision.Ready }
        var observe: suspend (SessionLease, CommandIntent, ApiReply) -> PortResult<Unit> = { _, _, _ -> PortResult.Value(Unit) }
        var exchange: suspend (ApiCall) -> PortResult<ApiReply> = {
            PortResult.Value(if (it.operationId == "updateCookSession") cookReply() else ApiReply(204, null))
        }
        val queue = DurableCommandQueue(scope, store, boundary, dispatcher, EpochClock { now },
            CommandExecutionGate { _, intent -> gated += intent.commandId; preflight(intent) },
            object : AccountTransport {
                override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                    sent += call
                    return exchange(call)
                }
            }, CommandReplyObserver { lease, intent, reply -> observed += intent.commandId; observe(lease, intent, reply) })

        fun stored(id: String): JournalCommand = JournalCodec.decodeCommand(checkNotNull(store.records[metadata(id)]).payload)
    }

    /** All-or-nothing CAS with detached payloads and monotonic revisions, including tombstones. */
    private class CasStore(private val owner: StorageScope) : PrivateStateStore {
        val records = mutableMapOf<RecordKey, PrivateRecord>()
        private val revisions = mutableMapOf<RecordKey, Long>()

        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> {
            if (scope != owner) return PortResult.Failure(FailureReason.STALE_SESSION)
            return PortResult.Value(records[key]?.let { it.copy(payload = PrivateBytes(it.payload.copyForCodec())) })
        }

        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> {
            if (scope != owner) return PortResult.Failure(FailureReason.STALE_SESSION)
            if (mutations.size !in 1..64 || mutations.map { it.key }.toSet().size != mutations.size)
                return PortResult.Failure(FailureReason.INVALID_DATA)
            if (mutations.any { it.expectedRevision != records[it.key]?.revision })
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
                    is StoreMutation.Delete -> {
                        records.remove(mutation.key)
                        result[mutation.key] = null
                    }
                }
            }
            return PortResult.Value(result)
        }

        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> = error("Queue must not erase identities")
    }

    companion object {
        private const val ID = "123e4567-e89b-12d3-a456-426614174001"
        private const val ID2 = "123e4567-e89b-12d3-a456-426614174002"
        private const val ID3 = "123e4567-e89b-12d3-a456-426614174003"
        private const val ORIGIN = "123e4567-e89b-12d3-a456-426614174090"
        private const val RESOURCE = "123e4567-e89b-12d3-a456-426614174080"
        private const val PLAN = "123e4567-e89b-12d3-a456-426614174081"
        private const val START = 1_800_000_000_000L
        private fun metadata(id: String) = RecordKey("feedme.command.metadata", id)
        private fun json(text: String) = PrivateBytes(text.encodeToByteArray())
        private fun pantryIntent(id: String) = CommandIntent(id, ORIGIN,
            ApiCall("removePantryItem", mapOf("ingredientId" to RESOURCE), idempotencyKey = SecretText(id), ifMatch = "\"7\""))
        private fun cookIntent(id: String, sequence: Int = 1) = CommandIntent(id, ORIGIN,
            ApiCall("updateCookSession", mapOf("sessionId" to RESOURCE), body = json("{\"deviceSequence\":$sequence}"),
                idempotencyKey = SecretText(id), ifMatch = "\"7\""))
        private fun reactionIntent(id: String, dependencies: List<String> = emptyList()) = CommandIntent(id, ORIGIN,
            ApiCall("removeReaction", mapOf("postId" to RESOURCE), idempotencyKey = SecretText(id), ifMatch = "\"7\""), dependencies)
        private fun feedbackDeleteIntent(id: String) = CommandIntent(id, ORIGIN,
            ApiCall("deleteFeedback", mapOf("feedbackId" to RESOURCE), idempotencyKey = SecretText(id), ifMatch = "\"7\""))
        private fun cookReply() = ApiReply(200, json("""{"id":"$RESOURCE","version":8,"createdAt":"2026-09-13T07:00:00Z","updatedAt":"2026-09-13T08:00:00Z","planId":"$PLAN","status":"active","currentStepId":"step-one","completedStepIds":[],"deviceSequence":1,"timers":[]}"""),
            etag = "\"8\"", contentType = "application/json")
        private fun problem(status: Int, code: String) = ApiReply(status,
            json("""{"type":"https://example.test/problems/rejected","title":"Action unavailable","status":$status,"code":"$code","traceId":"test-trace"}"""),
            contentType = "application/problem+json")
        private fun <T> value(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
        private fun failure(reason: FailureReason, result: PortResult<*>) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
    }
}
