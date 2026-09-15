@file:OptIn(com.feedme.storage.WorkRecoveryCompositionApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.feedme.session

import com.feedme.core.ports.*
import com.feedme.storage.ExistingSessionWorkRecoveryStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Detached ledger and lookup proof fixture only; no native authentication or fsync claim. */
class SessionWorkOriginRecoveryOwnerTest {
    @Test fun constructionHasNoIoAndPublicFacadeHasNoLedgerSigningOrSchedulerCapability() = runTest {
        val f = fixture()
        assertTrue(f.store.events.isEmpty())
        assertEquals("SessionWorkOriginRecoveryOwner(<redacted>)", f.owner.toString())
        assertFalse(f.owner is SessionControlStore)
        assertFalse(f.owner is WorkOriginPlanVerification)
        assertFalse(f.owner is WorkOriginPlanAuthentication)
        assertFalse(f.owner is SessionWorkRetirementPort)
        stale(f.owner.inspect()); stale(f.owner.abort())
        assertTrue(f.store.events.isEmpty())
        value(f.owner.close())
    }

    @Test fun closeBeforeOpenIsIdempotentAndNeverOpensOrInitializesLowerState() = runTest {
        val f = fixture(); val before = f.store.record!!.copyRecord()
        value(f.owner.close()); value(f.owner.close())
        failure(FailureReason.CONFLICT, f.owner.open()); stale(f.owner.inspect()); stale(f.owner.abort())
        assertEquals(listOf("close"), f.store.events); assertRecord(before, f.store.record!!)
    }

    @Test fun everySupportedStatusOpensWithExactReadOnlyObservation() = runTest {
        for (status in SessionWorkOriginPlanStatus.entries) withFixture { f ->
            f.store.stage(status)
            val before = f.store.record!!.copyRecord(); value(f.owner.open())
            repeat(2) {
                val observed = value(f.owner.inspect())
                assertEquals(status, observed.status); assertEquals(before.revision, observed.revision)
                assertEquals("SessionWorkOriginObservation(<redacted>)", observed.toString())
            }
            assertRecord(before, f.store.record!!); assertEquals(0, f.store.writes)
            assertNull(f.boundary.current())
        }
    }

    @Test fun wrongScopeDemoAndMalformedUnicodeFailBeforeLowerOpening() = runTest {
        for (scope in listOf(SCOPE.copy(actorId = "foreign"), SCOPE.copy(actorKind = ActorKind.DEMO),
            SCOPE.copy(environment = "\uD800"), SCOPE.copy(actorId = "\uD800"))) {
            val f = fixture(scope = scope)
            failure(FailureReason.INVALID_DATA, f.owner.open())
            assertTrue(f.store.events.isEmpty()); stale(f.owner.inspect()); stale(f.owner.abort())
            failure(FailureReason.CONFLICT, f.owner.open()); value(f.owner.close())
        }
    }

    @Test fun activeBoundaryBlocksOpenWithoutClearingOrReplacingLease() = runTest {
        withFixture { f ->
            val lease = f.boundary.activate(SCOPE)
            stale(f.owner.open()); assertTrue(f.store.events.isEmpty())
            assertTrue(f.boundary.isCurrent(lease)); stale(f.owner.inspect()); stale(f.owner.abort())
        }
    }

    @Test fun failedNativeOpenRemainsCloseOnlyAndPreservesTypedFailure() = runTest {
        for (reason in listOf(FailureReason.NOT_CONFIGURED, FailureReason.STORAGE_FAILURE, FailureReason.OUTCOME_UNKNOWN))
            withFixture { f ->
                f.store.openFailure = reason
                failure(reason, f.owner.open()); assertEquals(listOf("open"), f.store.events)
                failure(FailureReason.CONFLICT, f.owner.open()); stale(f.owner.inspect()); stale(f.owner.abort())
                assertEquals(listOf("open"), f.store.events)
            }
    }

    @Test fun thrownNativeOpenIsSanitizedAndRetainedForExplicitClose() = runTest {
        withFixture { f ->
            f.store.hook = { if (it == "open") error(PRIVATE_ERROR) }
            val result = f.owner.open(); failure(FailureReason.STORAGE_FAILURE, result)
            assertFalse(result.toString().contains(PRIVATE_ERROR)); assertEquals(0, f.store.closes)
            f.store.hook = {}; stale(f.owner.inspect()); failure(FailureReason.CONFLICT, f.owner.open())
        }
    }

    @Test fun nativeProofFailureCannotPublishReadyOrWrite() = runTest {
        withFixture { f ->
            f.store.proofs.clear()
            failure(FailureReason.INVALID_DATA, f.owner.open())
            stale(f.owner.inspect()); stale(f.owner.abort()); assertEquals(0, f.store.writes)
            assertEquals(0, f.store.closes)
        }
    }

    @Test fun missingMalformedAndUnreadableRecordsAreNotInitialized() = runTest {
        for (kind in listOf("missing", "malformed", "unreadable")) withFixture { f ->
            when (kind) {
                "missing" -> f.store.record = null
                "malformed" -> f.store.record = SessionControlRecord(R, PrivateBytes("private-broken-ledger".encodeToByteArray()))
                else -> f.store.readFailure = FailureReason.UNAVAILABLE
            }
            failure(if (kind == "unreadable") FailureReason.UNAVAILABLE else FailureReason.STORAGE_FAILURE, f.owner.open())
            assertEquals(0, f.store.writes); assertEquals(0, f.store.events.count { it == "verify" })
            stale(f.owner.inspect()); stale(f.owner.abort())
        }
    }

    @Test fun validNativeProofNeverMakesOrdinaryUsedOrNonemptyOriginRecoverable() = runTest {
        val states = listOf(SessionWorkState.Origin(SCOPE, ORIGIN, false, emptyList()),
            SessionWorkState.Origin(SCOPE, ORIGIN, true, emptyList()),
            SessionWorkState.Origin(SCOPE, ORIGIN, false,
                listOf(SessionWorkEntry(OTHER, NativeWorkKind.TIMER, "private-timer", NativeWorkPhase.INSTALLED))))
        for (state in states) withFixture { f ->
            f.store.record = SessionControlRecord(R, SessionWorkCodec.encode(state))
            // Deliberately prove the raw nonempty predecessor: schema admission still rejects it.
            f.store.register(PLAN, f.store.record!!)
            failure(FailureReason.STALE_SESSION, f.owner.open()); assertEquals(0, f.store.writes)
        }
    }

    @Test fun successorOfAbortedPlanRequiresBothOriginalAndSuccessorProofs() = runTest {
        for (validOriginal in listOf(false, true)) {
            val successor = SessionWorkOriginPlan.create(DETAILS.copy(expectedRevision = R + 3,
                origin = OTHER, proof = PrivateBytes(ByteArray(64) { 3 })))
            val f = fixture(plan = successor)
            try {
                f.store.record = SessionControlRecord(R + 3, SessionWorkCodec.encode(SessionWorkState.SetupAborted(PLAN)))
                f.store.register(successor, f.store.record!!)
                if (!validOriginal) f.store.proofs.remove(PLAN.copyForStorage().key())
                if (validOriginal) {
                    value(f.owner.open()); assertEquals(SessionWorkOriginPlanStatus.PREPARED, value(f.owner.inspect()).status)
                    value(f.owner.abort()); assertEquals(SessionWorkOriginPlanStatus.ABORTED, value(f.owner.inspect()).status)
                } else { failure(FailureReason.INVALID_DATA, f.owner.open()); assertEquals(0, f.store.writes) }
            } finally { value(f.owner.close()) }
        }
    }

    @Test fun noncanonicalRawStateAndChangedPredecessorCannotBeNormalized() = runTest {
        for (status in SessionWorkOriginPlanStatus.entries) withFixture { f ->
            f.store.stage(status); val initial = f.store.record!!
            f.store.record = SessionControlRecord(initial.revision,
                PrivateBytes((" " + initial.payload.copyForCodec().decodeToString()).encodeToByteArray()))
            failure(if (status == SessionWorkOriginPlanStatus.PREPARED) FailureReason.INVALID_DATA else FailureReason.STALE_SESSION,
                f.owner.open())
            assertEquals(0, f.store.writes)
        }
    }

    @Test fun changedEvidenceDuringAuthenticationCannotPublishAnEarlierObservation() = runTest {
        for (point in listOf("verify", "predecessor")) withFixture { f ->
            f.store.hook = { if (it == point) f.store.record = f.store.record!!.bumped() }
            failure(FailureReason.CONFLICT, f.owner.open()); stale(f.owner.inspect())
            assertEquals(0, f.store.writes)
        }
    }

    @Test fun samePayloadReacknowledgementIsVisibleAsChangedRevisionNotAnotherStatus() = runTest {
        withFixture { f ->
            f.store.stage(SessionWorkOriginPlanStatus.SELECTED); value(f.owner.open())
            val before = value(f.owner.inspect()); f.store.record = f.store.record!!.bumped()
            val after = value(f.owner.inspect())
            assertEquals(before.status, after.status); assertEquals(before.revision + 1, after.revision)
            assertEquals(0, f.store.writes)
        }
    }

    @Test fun abortFromEveryAllowedStatusAlwaysChangesRevisionWithoutLeaseOrEffects() = runTest {
        for (status in SessionWorkOriginPlanStatus.entries) withFixture { f ->
            f.store.stage(status); value(f.owner.open()); val before = f.store.record!!.revision
            value(f.owner.abort()); val after = value(f.owner.inspect())
            assertEquals(SessionWorkOriginPlanStatus.ABORTED, after.status)
            assertEquals(before + 1, after.revision); assertEquals(1, f.store.writes); assertNull(f.boundary.current())
            assertContentEquals(SessionWorkCodec.encode(SessionWorkState.SetupAborted(PLAN)).copyForCodec(),
                f.store.record!!.payload.copyForCodec())
        }
    }

    @Test fun abortedReplayStillRequiresANewCasAndExactReadbackEveryTime() = runTest {
        withFixture { f ->
            f.store.stage(SessionWorkOriginPlanStatus.ABORTED); value(f.owner.open())
            val before = f.store.record!!.copyRecord()
            repeat(3) { value(f.owner.abort()) }
            assertEquals(before.revision + 3, f.store.record!!.revision)
            assertContentEquals(before.payload.copyForCodec(), f.store.record!!.payload.copyForCodec())
            assertEquals(3, f.store.writes)
        }
    }

    @Test fun unknownWriteIsNotPromotedByVisibleAbortedStateAndRetryWritesAgain() = runTest {
        withFixture { f ->
            value(f.owner.open()); f.store.afterCasFailure = FailureReason.OUTCOME_UNKNOWN
            failure(FailureReason.OUTCOME_UNKNOWN, f.owner.abort())
            assertEquals(SessionWorkOriginPlanStatus.ABORTED, value(f.owner.inspect()).status)
            val revision = f.store.record!!.revision; f.store.afterCasFailure = null
            value(f.owner.abort()); assertEquals(revision + 1, f.store.record!!.revision); assertEquals(2, f.store.writes)
        }
    }

    @Test fun definiteCasFailureDoesNotWriteAndDoesNotRequireReopeningToRetry() = runTest {
        withFixture { f ->
            value(f.owner.open()); val before = f.store.record!!.copyRecord()
            f.store.beforeCasFailure = FailureReason.UNAVAILABLE
            failure(FailureReason.UNAVAILABLE, f.owner.abort()); assertRecord(before, f.store.record!!)
            f.store.beforeCasFailure = null; value(f.owner.abort())
            assertEquals(1, f.store.writes); assertEquals(1, f.store.events.count { it == "open" })
        }
    }

    @Test fun malformedSuccessfulCasReceiptNeverAcknowledgesAbort() = runTest {
        for (wrongPayload in listOf(false, true)) withFixture { f ->
            value(f.owner.open())
            f.store.receipt = { if (wrongPayload) SessionControlRecord(it.revision, IDLE) else it.bumped() }
            failure(FailureReason.STORAGE_FAILURE, f.owner.abort())
            assertEquals(1, f.store.writes); f.store.receipt = { it }
            value(f.owner.abort()); assertEquals(2, f.store.writes)
        }
    }

    @Test fun changedAckReadbackCannotCompleteAndMaxRevisionCannotWrap() = runTest {
        withFixture { f ->
            value(f.owner.open())
            f.store.hook = { if (it == "cas") f.store.afterNextRead = { captured -> captured.bumped() } }
            failure(FailureReason.CONFLICT, f.owner.abort()); assertEquals(1, f.store.writes)
            f.store.hook = {}; f.store.record = SessionControlRecord(Long.MAX_VALUE, f.store.record!!.payload)
            failure(FailureReason.STORAGE_FAILURE, f.owner.abort()); assertEquals(1, f.store.writes)
        }
    }

    @Test fun lateBoundaryActivationAtEveryOpenAwaitKeepsExactNewLeaseAndNeverPublishes() = runTest {
        for (point in listOf("open", "read", "verify", "predecessor")) withFixture { f ->
            var lease: SessionLease? = null
            f.store.hook = { if (it == point && lease == null) lease = f.boundary.activate(SCOPE) }
            stale(f.owner.open()); assertTrue(f.boundary.isCurrent(assertNotNull(lease)))
            stale(f.owner.inspect()); stale(f.owner.abort()); assertEquals(0, f.store.writes)
        }
    }

    @Test fun cancellationAfterNoncooperativeOpenReadOrProofKeepsCloseOnlyOwner() = runTest {
        for (point in listOf("open", "read", "verify", "predecessor")) withFixture { f ->
            f.store.hook = { if (it == point) currentCoroutineContext().cancel() }
            val job = async { f.owner.open() }
            assertFailsWith<CancellationException> { job.await() }
            f.store.hook = {}; stale(f.owner.inspect()); stale(f.owner.abort())
            failure(FailureReason.CONFLICT, f.owner.open()); assertEquals(0, f.store.closes)
        }
    }

    @Test fun lateLeaseDuringAbortStopsFurtherCallsAndNeverClearsTheNewLease() = runTest {
        for (point in listOf("read", "verify", "predecessor", "cas")) withFixture { f ->
            value(f.owner.open()); var lease: SessionLease? = null
            f.store.hook = { if (it == point && lease == null) lease = f.boundary.activate(SCOPE) }
            stale(f.owner.abort()); assertTrue(f.boundary.isCurrent(assertNotNull(lease)))
            assertEquals(if (point == "cas") 1 else 0, f.store.writes)
        }
    }

    @Test fun cancelledAbortCannotReturnAnAcknowledgementFromItsCommittedMarker() = runTest {
        withFixture { f ->
            value(f.owner.open()); f.store.hook = { if (it == "cas") currentCoroutineContext().cancel() }
            val operation = async { f.owner.abort() }; assertFailsWith<CancellationException> { operation.await() }
            assertEquals(1, f.store.writes); f.store.hook = {}
            value(f.owner.abort()); assertEquals(2, f.store.writes)
        }
    }

    @Test fun failedCloseRetainsSameStoreAndRetriesOnlyCloseWhileAllOtherOperationsStayDenied() = runTest {
        withFixture { f ->
            value(f.owner.open()); f.store.closeFailure = FailureReason.STORAGE_FAILURE
            failure(FailureReason.STORAGE_FAILURE, f.owner.close()); val reads = f.store.events.count { it == "read" }
            stale(f.owner.inspect()); stale(f.owner.abort()); failure(FailureReason.CONFLICT, f.owner.open())
            assertEquals(reads, f.store.events.count { it == "read" }); assertEquals(1, f.store.closes)
            f.store.closeFailure = null; value(f.owner.close()); value(f.owner.close())
            assertEquals(2, f.store.closes); assertEquals(1, f.store.events.count { it == "open" })
        }
    }

    @Test fun callerHandoffNeverPublishesReadyAndCancellationOrCloseWinsThatGap() = runTest {
        for (closeFirst in listOf(false, true)) withFixture { f ->
            val caller = HandoffDispatcher(); val opening = async(caller) { f.owner.open() }
            caller.runNext(); runCurrent()
            assertTrue(caller.pending.isNotEmpty()); stale(f.owner.inspect()); stale(f.owner.abort())
            if (closeFirst) value(f.owner.close()) else opening.cancel()
            repeat(4) { caller.runAll(); runCurrent() }
            if (closeFirst) stale(opening.await()) else assertFailsWith<CancellationException> { opening.await() }
            stale(f.owner.inspect()); failure(FailureReason.CONFLICT, f.owner.open())
        }
    }

    @Test fun cancelledQueuedSecondOpenDoesNotInvalidateTheFirstAdmittedOwner() = runTest {
        withFixture { f ->
            val entered = CompletableDeferred<Unit>(); val released = CompletableDeferred<Unit>()
            f.store.hook = { if (it == "open") { entered.complete(Unit); released.await() } }
            val first = async { f.owner.open() }; entered.await()
            val rejected = async { f.owner.open() }; runCurrent(); rejected.cancel(); runCurrent()
            released.complete(Unit); value(first.await())
            assertFailsWith<CancellationException> { rejected.await() }
            value(f.owner.inspect()); assertEquals(1, f.store.events.count { it == "open" })
        }
    }

    @Test fun competingPlanAfterOpeningCannotBeInspectedOrAbortedByOldOwner() = runTest {
        withFixture { f ->
            value(f.owner.open())
            val successor = SessionWorkOriginPlan.create(DETAILS.copy(origin = OTHER))
            f.store.record = SessionControlRecord(R + 2, SessionWorkCodec.encode(SessionWorkState.SetupSelected(successor)))
            failure(FailureReason.STALE_SESSION, f.owner.inspect()); failure(FailureReason.STALE_SESSION, f.owner.abort())
            assertEquals(0, f.store.writes)
        }
    }

    private fun TestScope.fixture(scope: StorageScope = SCOPE, plan: SessionWorkOriginPlan = PLAN): Fixture =
        Fixture(StandardTestDispatcher(testScheduler), scope, plan)
    private suspend fun TestScope.withFixture(block: suspend (Fixture) -> Unit) {
        val f = fixture()
        try { block(f) } finally { f.store.hook = {}; f.store.closeFailure = null; value(f.owner.close()) }
    }
    private class Fixture(dispatcher: CoroutineDispatcher, scope: StorageScope, plan: SessionWorkOriginPlan) {
        val store = Store(); val boundary = SessionBoundary()
        val owner: SessionWorkOriginRecoveryOwner = RetainedSessionWorkOriginRecoveryOwner(scope, plan, store, boundary, dispatcher)
    }
    private class Store : ExistingSessionWorkRecoveryStore {
        var record: SessionControlRecord? = SessionControlRecord(R, IDLE)
        val events = mutableListOf<String>(); var writes = 0; var closes = 0
        var hook: suspend (String) -> Unit = {}
        var openFailure: FailureReason? = null; var readFailure: FailureReason? = null
        var closeFailure: FailureReason? = null; var beforeCasFailure: FailureReason? = null
        var afterCasFailure: FailureReason? = null
        var receipt: (SessionControlRecord) -> SessionControlRecord = { it }
        var afterNextRead: ((SessionControlRecord) -> SessionControlRecord)? = null
        val proofs = mutableMapOf<String, Pair<SessionWorkOriginPlanRecord, SessionControlRecord>>()
        init { register(PLAN, record!!) }
        fun register(plan: SessionWorkOriginPlan, predecessor: SessionControlRecord) {
            proofs[plan.copyForStorage().key()] = SessionWorkOriginPlanCodec.decode(plan.copyForStorage()) to predecessor.copyRecord()
        }
        override suspend fun open(): PortResult<Unit> { event("open"); return result(openFailure) }
        override suspend fun close(): PortResult<Unit> { closes++; event("close"); return result(closeFailure) }
        override suspend fun read(): PortResult<SessionControlRecord?> {
            val captured = record?.copyRecord(); val transform = afterNextRead; afterNextRead = null
            event("read"); readFailure?.let { return PortResult.Failure(it) }
            return PortResult.Value(if (captured != null && transform != null) transform(captured) else captured)
        }
        override suspend fun verifyOriginPlan(expectedRevision: Long, proposal: PrivateBytes, proof: PrivateBytes): PortResult<Unit> {
            val matches = matching(expectedRevision, proposal, proof) != null
            event("verify"); return result(if (matches) null else FailureReason.INVALID_DATA)
        }
        override suspend fun verifyOriginPredecessor(expected: SessionControlRecord, proposal: PrivateBytes, proof: PrivateBytes): PortResult<Unit> {
            val signed = matching(expected.revision, proposal, proof)?.second
            val matches = signed != null && signed.revision == expected.revision && signed.payload.key() == expected.payload.key()
            event("predecessor"); return result(if (matches) null else FailureReason.INVALID_DATA)
        }
        override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> {
            beforeCasFailure?.let { event("cas-failed"); return PortResult.Failure(it) }
            val current = record ?: return PortResult.Failure(FailureReason.STORAGE_FAILURE)
            if (current.revision != expectedRevision) return PortResult.Failure(FailureReason.CONFLICT)
            val changed = SessionControlRecord(current.revision + 1, PrivateBytes(payload.copyForCodec()))
            record = changed; writes++; event("cas")
            afterCasFailure?.let { return PortResult.Failure(it) }
            return PortResult.Value(receipt(changed.copyRecord()))
        }
        private fun matching(revision: Long, proposal: PrivateBytes, proof: PrivateBytes) = proofs.values.firstOrNull {
            it.first.expectedRevision == revision && SessionWorkOriginPlanCodec.encodeUnsigned(it.first).key() == proposal.key() &&
                it.first.proof.key() == proof.key()
        }
        private suspend fun event(name: String) { events += name; hook(name) }
        private fun result(reason: FailureReason?): PortResult<Unit> = reason?.let { PortResult.Failure(it) } ?: PortResult.Value(Unit)
        fun stage(status: SessionWorkOriginPlanStatus) {
            val state = when (status) {
                SessionWorkOriginPlanStatus.PREPARED -> SessionWorkState.Idle
                SessionWorkOriginPlanStatus.SELECTED -> SessionWorkState.SetupSelected(PLAN)
                SessionWorkOriginPlanStatus.SEALED -> SessionWorkState.Origin(SCOPE, ORIGIN, false, emptyList(), PLAN)
                SessionWorkOriginPlanStatus.ABORTED -> SessionWorkState.SetupAborted(PLAN)
            }
            record = SessionControlRecord(if (status == SessionWorkOriginPlanStatus.PREPARED) R else R + 3, SessionWorkCodec.encode(state))
        }
    }
    private class HandoffDispatcher : CoroutineDispatcher() {
        val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun runNext() = pending.removeFirst().run()
        fun runAll() { while (pending.isNotEmpty()) runNext() }
    }
    companion object {
        private const val R = 7L
        private const val ORIGIN = "11111111-1111-4111-8111-111111111111"
        private const val OTHER = "22222222-2222-4222-8222-222222222222"
        private const val PRIVATE_ERROR = "private recovery fixture failure"
        private val SCOPE = StorageScope("work-recovery-test", ActorKind.ACCOUNT, "private-owner")
        private val DETAILS = SessionWorkOriginPlanRecord(R, SCOPE, ORIGIN, PrivateBytes(ByteArray(64) { 4 }))
        private val PLAN = SessionWorkOriginPlan.create(DETAILS)
        private val IDLE = SessionWorkCodec.encode(SessionWorkState.Idle)
        private fun PrivateBytes.key() = copyForCodec().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        private fun SessionControlRecord.copyRecord() = SessionControlRecord(revision, PrivateBytes(payload.copyForCodec()))
        private fun SessionControlRecord.bumped() = SessionControlRecord(revision + 1, payload)
        private fun assertRecord(a: SessionControlRecord, b: SessionControlRecord) {
            assertEquals(a.revision, b.revision); assertContentEquals(a.payload.copyForCodec(), b.payload.copyForCodec())
        }
        private fun <T> value(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
        private fun stale(result: PortResult<*>) = failure(FailureReason.STALE_SESSION, result)
        private fun failure(reason: FailureReason, result: PortResult<*>) { assertEquals(reason, assertIs<PortResult.Failure>(result).reason) }
    }
}
