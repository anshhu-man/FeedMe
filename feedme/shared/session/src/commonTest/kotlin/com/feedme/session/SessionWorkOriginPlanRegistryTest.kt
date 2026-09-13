package com.feedme.session

import com.feedme.core.ports.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Detached ledger and lookup-based proof fake: no native authentication/disk durability claim. */
class SessionWorkOriginPlanRegistryTest {
    @Test fun planningCapturesExactIdlePredecessorWithoutWritingOrPublishingAuthority() = runTest {
        fixture { f ->
            val initial = f.store.record!!.copyRecord()
            val plan = value(f.registry.planOrigin(SCOPE, initial.revision))
            val record = decode(plan)
            assertEquals(initial.revision, record.expectedRevision)
            assertEquals(SCOPE, record.scope)
            assertEquals(uuid(1), record.origin)
            assertEquals(64, record.proof.copyForCodec().size)
            assertRecord(initial, f.store.record!!)
            assertEquals(1, f.ids)
            assertEquals(1, f.store.signs)
            assertEquals(0, f.store.writes)
            assertNull(f.boundary.current())
            f.assertNoEffects()
        }
    }

    @Test fun invalidScopeRevisionAndIdFailWithoutPlanOrLedgerMutation() = runTest {
        fixture { f ->
            for (revision in listOf(0L, -1L)) failure(FailureReason.INVALID_DATA, f.registry.planOrigin(SCOPE, revision))
            for (revision in listOf(Long.MAX_VALUE - 1, Long.MAX_VALUE))
                failure(FailureReason.STORAGE_FAILURE, f.registry.planOrigin(SCOPE, revision))
            failure(FailureReason.INVALID_DATA, f.registry.planOrigin(SCOPE.copy(actorKind = ActorKind.DEMO), 1))
            failure(FailureReason.INVALID_DATA, f.registry.planOrigin(SCOPE.copy(actorId = "\uD800"), 1))
            failure(FailureReason.CONFLICT, f.registry.planOrigin(SCOPE, 2))
            assertEquals(0, f.ids)
            f.id = { "not-a-native-uuid" }
            failure(FailureReason.INVALID_DATA, f.registry.planOrigin(SCOPE, 1))
            f.id = { throw CancellationException("fixture ID source cancelled") }
            assertFailsWith<CancellationException> { f.registry.planOrigin(SCOPE, 1) }
            assertEquals(0, f.store.signs)
            assertEquals(0, f.store.writes)
            f.assertNoEffects()
        }
    }

    @Test fun everySetupOperationRequiresAnInactiveBoundary() = runTest {
        fixture { f ->
            val plan = f.plan()
            for (scope in listOf(SCOPE, SCOPE.copy(actorKind = ActorKind.GUEST), SCOPE.copy(actorId = "new-owner"))) {
                f.boundary.activate(scope)
                val reads = f.store.reads
                failure(FailureReason.STALE_SESSION, f.registry.planOrigin(SCOPE, 1))
                failure(FailureReason.STALE_SESSION, f.registry.inspectOrigin(plan))
                failure(FailureReason.STALE_SESSION, f.registry.selectOrigin(plan))
                assertEquals(reads, f.store.reads)
                assertEquals(1, f.ids)
                assertEquals(0, f.store.writes)
                f.boundary.clear()
            }
            f.assertNoEffects()
        }
    }

    @Test fun ordinaryControlStoreCannotSilentlyReplaceMissingProofCapability() = runTest {
        fixture { f ->
            val plan = f.plan()
            val ordinary = object : SessionControlStore by f.store {}
            value(f.registry.close())
            f.registry = value(f.open(ordinary))
            val ids = f.ids
            failure(FailureReason.NOT_CONFIGURED, f.registry.planOrigin(SCOPE, 1))
            failure(FailureReason.NOT_CONFIGURED, f.registry.inspectOrigin(plan))
            failure(FailureReason.NOT_CONFIGURED, f.registry.selectOrigin(plan))
            assertEquals(ids, f.ids)
            assertEquals(0, f.store.writes)
            f.assertNoEffects()
        }
    }

    @Test fun preparedInspectionIsReadOnlyAndNeverAllocatesAnotherIdentity() = runTest {
        fixture { f ->
            val plan = f.plan()
            val original = f.store.record!!.copyRecord()
            repeat(3) { assertEquals(SessionWorkOriginPlanStatus.PREPARED, value(f.registry.inspectOrigin(plan))) }
            assertRecord(original, f.store.record!!)
            assertEquals(1, f.ids)
            assertEquals(1, f.store.signs)
            assertEquals(3, f.store.verifications)
            assertEquals(3, f.store.predecessors)
            assertEquals(0, f.store.writes)
            f.assertNoEffects()
        }
    }

    @Test fun selectedReplaySurvivesManagerReopenButAlwaysNeedsFreshChangedAcknowledgement() = runTest {
        fixture { f ->
            val plan = f.plan()
            value(f.registry.selectOrigin(plan))
            val selected = f.store.record!!.copyRecord()
            assertEquals(2, selected.revision)
            assertSelected(plan, selected)
            failure(FailureReason.CONFLICT, f.registry.snapshot())
            f.reopen()
            repeat(2) {
                val before = f.store.record!!.copyRecord()
                assertEquals(SessionWorkOriginPlanStatus.SELECTED, value(f.registry.inspectOrigin(plan)))
                assertRecord(before, f.store.record!!)
                value(f.registry.selectOrigin(plan))
                assertEquals(before.revision + 1, f.store.record!!.revision)
                assertContentEquals(selected.payload.copyForCodec(), f.store.record!!.payload.copyForCodec())
            }
            assertEquals(1, f.ids)
            assertEquals(1, f.store.signs)
            assertEquals(3, f.store.writes)
            f.assertNoEffects()
        }
    }

    @Test fun failedCasNeverPromotesVisibleSelectionAndRetryUsesExactOriginalPlan() = runTest {
        for (committed in listOf(false, true)) for (reason in listOf(FailureReason.OUTCOME_UNKNOWN, FailureReason.STORAGE_FAILURE)) fixture { f ->
            val plan = f.plan()
            if (committed) f.store.afterFailure = reason else f.store.beforeFailure = reason
            failure(reason, f.registry.selectOrigin(plan))
            assertEquals(f.store.readsAtLastCas, f.store.reads, "A failed CAS cannot request authority readback")
            val before = f.store.record!!.copyRecord()
            f.reopen()
            assertEquals(if (committed) SessionWorkOriginPlanStatus.SELECTED else SessionWorkOriginPlanStatus.PREPARED,
                value(f.registry.inspectOrigin(plan)))
            assertRecord(before, f.store.record!!)
            value(f.registry.selectOrigin(plan))
            assertEquals(before.revision + 1, f.store.record!!.revision)
            assertSelected(plan, f.store.record!!)
            assertEquals(1, f.ids)
            f.assertNoEffects()
        }
    }

    @Test fun falseSuccessReceiptCannotAcknowledgeEvenCommittedExactSelection() = runTest {
        for (kind in listOf("old", "jump", "payload")) fixture { f ->
            val plan = f.plan()
            f.store.receipt = { actual -> when (kind) {
                "old" -> SessionControlRecord(actual.revision - 1, actual.payload)
                "jump" -> SessionControlRecord(actual.revision + 1, actual.payload)
                else -> SessionControlRecord(actual.revision, SessionWorkCodec.encode(SessionWorkState.Idle))
            } }
            failure(FailureReason.STORAGE_FAILURE, f.registry.selectOrigin(plan))
            assertEquals(f.store.readsAtLastCas, f.store.reads)
            assertSelected(plan, f.store.record!!)
            f.store.receipt = { it }
            value(f.registry.selectOrigin(plan))
            assertEquals(3, f.store.record!!.revision)
            assertEquals(1, f.ids)
            f.assertNoEffects()
        }
    }

    @Test fun successfulCasRequiresExactReadbackBeforeSelectionAcknowledgement() = runTest {
        val cases = mapOf("missing" to FailureReason.STORAGE_FAILURE, "older" to FailureReason.OUTCOME_UNKNOWN,
            "newer" to FailureReason.CONFLICT, "payload" to FailureReason.OUTCOME_UNKNOWN, "unreadable" to FailureReason.UNAVAILABLE)
        for ((kind, reason) in cases) fixture { f ->
            val plan = f.plan()
            f.store.afterCas = {
                val actual = f.store.record!!
                when (kind) {
                    "missing" -> f.store.record = null
                    "older" -> f.store.record = SessionControlRecord(actual.revision - 1, actual.payload)
                    "newer" -> f.store.record = SessionControlRecord(actual.revision + 1, actual.payload)
                    "payload" -> f.store.record = SessionControlRecord(actual.revision, SessionWorkCodec.encode(SessionWorkState.Idle))
                    else -> f.store.readFailure = FailureReason.UNAVAILABLE
                }
            }
            failure(reason, f.registry.selectOrigin(plan))
            assertEquals(f.store.readsAtLastCas + 1, f.store.reads)
            assertEquals(1, f.store.writes)
            f.assertNoEffects()
        }
    }

    @Test fun signingFailureOrMalformedProofNeverCreatesUsablePlanOrWrites() = runTest {
        fixture { f ->
            f.store.signFailure = FailureReason.UNAVAILABLE
            failure(FailureReason.UNAVAILABLE, f.registry.planOrigin(SCOPE, 1))
            f.store.signFailure = null
            for (size in listOf(0, 32, 63, 65)) {
                f.store.signOutput = { PrivateBytes(ByteArray(size)) }
                failure(FailureReason.STORAGE_FAILURE, f.registry.planOrigin(SCOPE, 1))
            }
            assertEquals(0, f.store.writes)
            assertSame(SessionWorkState.Idle, SessionWorkCodec.decode(f.store.record!!.payload))
            f.assertNoEffects()
        }
    }

    @Test fun structurallyValidTamperedOrForeignProofCannotSelectAnIdleLedger() = runTest {
        fixture { f ->
            val plan = f.plan()
            val original = decode(plan)
            val tampered = listOf(original.copy(expectedRevision = 2), original.copy(origin = uuid(999)),
                original.copy(scope = SCOPE.copy(actorKind = ActorKind.GUEST)), original.copy(proof = PrivateBytes(ByteArray(64))))
            for (record in tampered) {
                val malformedAuthority = SessionWorkOriginPlan.create(record)
                failure(FailureReason.INVALID_DATA, f.registry.inspectOrigin(malformedAuthority))
                failure(FailureReason.INVALID_DATA, f.registry.selectOrigin(malformedAuthority))
            }
            val foreign = FakeProofStore("foreign")
            value(f.registry.close())
            f.registry = value(f.open(foreign))
            failure(FailureReason.INVALID_DATA, f.registry.inspectOrigin(plan))
            failure(FailureReason.INVALID_DATA, f.registry.selectOrigin(plan))
            assertEquals(0, foreign.writes)
            assertEquals(0, f.store.writes)
            assertEquals(1, f.ids)
            f.assertNoEffects()
        }
    }

    @Test fun competingPlansCannotClaimOneAnothersPersistentSelection() = runTest {
        fixture { f ->
            val first = f.plan()
            val competing = f.plan()
            assertNotEquals(decode(first).origin, decode(competing).origin)
            assertEquals(0, f.store.writes)
            value(f.registry.selectOrigin(first))
            val selected = f.store.record!!.copyRecord()
            failure(FailureReason.STALE_SESSION, f.registry.inspectOrigin(competing))
            failure(FailureReason.STALE_SESSION, f.registry.selectOrigin(competing))
            failure(FailureReason.CONFLICT, f.registry.planOrigin(SCOPE, selected.revision))
            assertRecord(selected, f.store.record!!)
            assertEquals(2, f.ids)
            assertEquals(1, f.store.writes)
            f.assertNoEffects()
        }
    }

    @Test fun ordinaryUsedOrRetiringOriginsNeverSubstituteForSelectionProvenance() = runTest {
        for (retiring in listOf(false, true)) for (phase in listOf<NativeWorkPhase?>(null) + NativeWorkPhase.entries) fixture { f ->
            val plan = f.plan()
            val origin = decode(plan).origin
            val entries = phase?.let { listOf(SessionWorkEntry(uuid(90), NativeWorkKind.TIMER, "already-used", it)) } ?: emptyList()
            f.store.replace(SessionWorkState.Origin(SCOPE, origin, retiring, entries))
            val before = f.store.record!!.copyRecord()
            failure(FailureReason.STALE_SESSION, f.registry.inspectOrigin(plan))
            failure(FailureReason.STALE_SESSION, f.registry.selectOrigin(plan))
            failure(FailureReason.CONFLICT, f.registry.planOrigin(SCOPE, before.revision))
            assertRecord(before, f.store.record!!)
            assertEquals(0, f.store.writes)
            assertEquals(1, f.ids)
            f.assertNoEffects()
        }
    }

    @Test fun changedIdleRevisionOrBytesCannotBeInferredAsTheAuthenticatedPredecessor() = runTest {
        for (revisionChanged in listOf(false, true)) fixture { f ->
            val plan = f.plan()
            f.store.record = if (revisionChanged) SessionControlRecord(2, f.store.record!!.payload)
                else SessionControlRecord(1, PrivateBytes("{\"state\":\"idle\",\"version\":1}".encodeToByteArray()))
            val before = f.store.record!!.copyRecord()
            failure(if (revisionChanged) FailureReason.STALE_SESSION else FailureReason.INVALID_DATA, f.registry.inspectOrigin(plan))
            failure(if (revisionChanged) FailureReason.STALE_SESSION else FailureReason.INVALID_DATA, f.registry.selectOrigin(plan))
            assertRecord(before, f.store.record!!)
            assertEquals(0, f.store.writes)
            f.assertNoEffects()
        }
    }

    @Test fun selectedReplayRejectsNoncanonicalWrapperAndExhaustedResolutionSpace() = runTest {
        fixture { f ->
            val plan = f.plan()
            value(f.registry.selectOrigin(plan))
            val selected = f.store.record!!
            f.store.record = SessionControlRecord(selected.revision, PrivateBytes((" " + selected.payload.copyForCodec().decodeToString()).encodeToByteArray()))
            failure(FailureReason.STALE_SESSION, f.registry.inspectOrigin(plan))
            failure(FailureReason.STALE_SESSION, f.registry.selectOrigin(plan))
            f.store.record = SessionControlRecord(Long.MAX_VALUE - 1, selected.payload)
            assertEquals(SessionWorkOriginPlanStatus.SELECTED, value(f.registry.inspectOrigin(plan)))
            failure(FailureReason.STORAGE_FAILURE, f.registry.selectOrigin(plan))
            assertEquals(1, f.store.writes)
            f.assertNoEffects()
        }
    }

    @Test fun ordinaryRetirementCannotEraseSelectionOrLeaveARejectedFenceBehind() = runTest {
        fixture { f ->
            val plan = f.plan()
            value(f.registry.selectOrigin(plan))
            val origin = decode(plan).origin
            val selected = f.store.record!!.copyRecord()
            failure(FailureReason.CONFLICT, f.registry.retire(SCOPE, origin))
            failure(FailureReason.CONFLICT, f.registry.retireEmpty(SCOPE, origin))
            failure(FailureReason.CONFLICT, f.registry.retire(SCOPE, uuid(900)))
            assertRecord(selected, f.store.record!!)
            assertEquals(SessionWorkOriginPlanStatus.SELECTED, value(f.registry.inspectOrigin(plan)))
            value(f.registry.selectOrigin(plan))
            f.assertNoEffects()
        }
    }

    @Test fun cancellationAfterSuspendingProofOrCasOperationsNeverReturnsAuthority() = runTest {
        for (point in listOf("sign", "verify", "cas", "readback")) fixture { f ->
            val plan = if (point == "sign") null else f.plan()
            when (point) {
                "sign" -> f.store.afterSign = { currentCoroutineContext().cancel() }
                "verify" -> f.store.afterVerify = { currentCoroutineContext().cancel() }
                "cas" -> f.store.afterCas = { currentCoroutineContext().cancel() }
                else -> f.store.afterCas = { f.store.afterRead = { currentCoroutineContext().cancel() } }
            }
            val operation = async { if (plan == null) f.registry.planOrigin(SCOPE, 1) else f.registry.selectOrigin(plan) }
            assertFailsWith<CancellationException> { operation.await() }
            assertEquals(if (point in setOf("cas", "readback")) 1 else 0, f.store.writes)
            assertNull(f.boundary.current())
            f.assertNoEffects()
        }
    }

    @Test fun boundaryActivationDuringReadAuthenticationOrCasStopsSetupAuthority() = runTest {
        for (point in listOf("read", "sign", "verify", "cas")) fixture { f ->
            val plan = if (point == "sign") null else f.plan()
            when (point) {
                "read" -> f.store.afterRead = { f.boundary.activate(SCOPE) }
                "sign" -> f.store.afterSign = { f.boundary.activate(SCOPE) }
                "verify" -> f.store.afterVerify = { f.boundary.activate(SCOPE) }
                else -> f.store.afterCas = { f.boundary.activate(SCOPE) }
            }
            failure(FailureReason.STALE_SESSION, if (plan == null) f.registry.planOrigin(SCOPE, 1) else f.registry.selectOrigin(plan))
            assertEquals(if (point == "cas") 1 else 0, f.store.writes)
            f.assertNoEffects()
        }
    }

    @Test fun changedRecordDuringAuthenticationInvalidatesPlanningInspectionAndSelection() = runTest {
        for (operation in listOf("plan", "inspect", "select")) for (selected in listOf(false, true)) fixture { f ->
            if (operation == "plan" && selected) return@fixture
            val plan = if (operation == "plan") null else f.plan()
            if (selected) value(f.registry.selectOrigin(plan!!))
            val writes = f.store.writes
            val change: suspend () -> Unit = {
                val current = f.store.record!!
                f.store.record = SessionControlRecord(current.revision + 1, current.payload)
            }
            if (operation == "plan") f.store.afterSign = change else f.store.afterVerify = change
            val result = when (operation) {
                "plan" -> f.registry.planOrigin(SCOPE, 1)
                "inspect" -> f.registry.inspectOrigin(plan!!)
                else -> f.registry.selectOrigin(plan!!)
            }
            assertTrue(assertIs<PortResult.Failure>(result).reason in setOf(FailureReason.CONFLICT, FailureReason.STALE_SESSION))
            assertEquals(writes, f.store.writes)
            f.assertNoEffects()
        }
    }

    @Test fun missingCorruptOrClosedLedgerNeverInitializesOrSelectsAnything() = runTest {
        for (kind in listOf("missing", "corrupt", "closed")) fixture { f ->
            val plan = f.plan()
            when (kind) {
                "missing" -> f.store.record = null
                "corrupt" -> f.store.record = SessionControlRecord(1, PrivateBytes("private-canary".encodeToByteArray()))
                else -> value(f.registry.close())
            }
            failure(FailureReason.STORAGE_FAILURE, f.registry.planOrigin(SCOPE, 1))
            failure(FailureReason.STORAGE_FAILURE, f.registry.inspectOrigin(plan))
            failure(FailureReason.STORAGE_FAILURE, f.registry.selectOrigin(plan))
            assertEquals(0, f.store.writes)
            f.assertNoEffects()
        }
    }

    private suspend fun TestScope.fixture(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        try { fixture.reopen(); block(fixture) } finally { value(fixture.registry.close()) }
    }

    private class Fixture(private val dispatcher: CoroutineDispatcher) {
        val store = FakeProofStore("primary")
        val boundary = SessionBoundary()
        lateinit var registry: SessionWorkRegistry
        var ids = 0
        var id: () -> String = { uuid(ids) }
        var effects = 0
        var policies = 0
        suspend fun open(control: SessionControlStore = store) = SessionWorkRegistry.open(control, boundary, dispatcher,
            NativeWorkCancellationPort { effects++; PortResult.Failure(FailureReason.FORBIDDEN) },
            NativeWorkIdSource { ids++; id() },
            NativeWorkAdmissionPolicy { policies++; PortResult.Failure(FailureReason.FORBIDDEN) },
            NativeWorkExecutionPolicy { _, _, _, _ -> policies++; PortResult.Failure(FailureReason.FORBIDDEN) })
        suspend fun reopen() {
            if (::registry.isInitialized) value(registry.close())
            registry = value(open())
        }
        suspend fun plan() = value(registry.planOrigin(SCOPE, store.record!!.revision))
        fun assertNoEffects() { assertEquals(0, effects); assertEquals(0, policies) }
    }

    /** Proof issuance table survives manager reopen only in this fixture; it is intentionally not crypto. */
    private class FakeProofStore(private val identity: String) : SessionControlStore, WorkOriginPlanAuthentication {
        var record: SessionControlRecord? = SessionControlRecord(1, SessionWorkCodec.encode(SessionWorkState.Idle))
        var reads = 0
        var writes = 0
        var readsAtLastCas = 0
        var signs = 0
        var verifications = 0
        var predecessors = 0
        var beforeFailure: FailureReason? = null
        var afterFailure: FailureReason? = null
        var readFailure: FailureReason? = null
        var signFailure: FailureReason? = null
        var receipt: (SessionControlRecord) -> SessionControlRecord = { it }
        var signOutput: (PrivateBytes) -> PrivateBytes = { it }
        var afterRead: suspend () -> Unit = {}
        var afterCas: suspend () -> Unit = {}
        var afterSign: suspend () -> Unit = {}
        var afterVerify: suspend () -> Unit = {}
        private val issued = mutableListOf<Issued>()

        override suspend fun read(): PortResult<SessionControlRecord?> {
            reads++
            val result = readFailure?.let { PortResult.Failure(it) } ?: PortResult.Value(record?.copyRecord())
            afterRead()
            return result
        }
        override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> {
            writes++
            readsAtLastCas = reads
            beforeFailure?.let { beforeFailure = null; return PortResult.Failure(it) }
            val current = record ?: return PortResult.Failure(FailureReason.STORAGE_FAILURE)
            if (current.revision != expectedRevision) return PortResult.Failure(FailureReason.CONFLICT)
            if (current.revision == Long.MAX_VALUE) return PortResult.Failure(FailureReason.STORAGE_FAILURE)
            val next = SessionControlRecord(current.revision + 1, PrivateBytes(payload.copyForCodec()))
            record = next.copyRecord()
            afterCas()
            afterFailure?.let { afterFailure = null; return PortResult.Failure(it) }
            return PortResult.Value(receipt(next.copyRecord()))
        }
        override suspend fun signOriginPlan(expected: SessionControlRecord, proposal: PrivateBytes): PortResult<PrivateBytes> {
            signs++
            signFailure?.let { return PortResult.Failure(it) }
            if (!sameRecord(expected, record)) return PortResult.Failure(FailureReason.CONFLICT)
            val proof = PrivateBytes(ByteArray(64) { index -> (identity.hashCode() + signs * 37 + index * 17).toByte() })
            issued += Issued(expected.copyRecord(), PrivateBytes(proposal.copyForCodec()), proof)
            afterSign()
            return PortResult.Value(signOutput(proof))
        }
        override suspend fun verifyOriginPlan(expectedRevision: Long, proposal: PrivateBytes, proof: PrivateBytes): PortResult<Unit> {
            verifications++
            val valid = issued.any { it.expected.revision == expectedRevision && sameBytes(it.proposal, proposal) && sameBytes(it.proof, proof) }
            afterVerify()
            return if (valid) PortResult.Value(Unit) else PortResult.Failure(FailureReason.INVALID_DATA)
        }
        override suspend fun verifyOriginPredecessor(expected: SessionControlRecord, proposal: PrivateBytes, proof: PrivateBytes): PortResult<Unit> {
            predecessors++
            if (!sameRecord(expected, record)) return PortResult.Failure(FailureReason.CONFLICT)
            return if (issued.any { sameRecord(it.expected, expected) && sameBytes(it.proposal, proposal) && sameBytes(it.proof, proof) })
                PortResult.Value(Unit) else PortResult.Failure(FailureReason.INVALID_DATA)
        }
        fun replace(state: SessionWorkState) { record = SessionControlRecord(record!!.revision + 1, SessionWorkCodec.encode(state)) }
        private class Issued(val expected: SessionControlRecord, val proposal: PrivateBytes, val proof: PrivateBytes)
    }

    companion object {
        private val SCOPE = StorageScope("origin-plan-fixture", ActorKind.ACCOUNT, "private-owner")
        private fun uuid(number: Int) = "00000000-0000-4000-8000-${number.toString().padStart(12, '0')}"
        private fun decode(plan: SessionWorkOriginPlan) = SessionWorkOriginPlanCodec.decode(plan.copyForStorage())
        private fun SessionControlRecord.copyRecord() = SessionControlRecord(revision, PrivateBytes(payload.copyForCodec()))
        private fun sameBytes(a: PrivateBytes, b: PrivateBytes) = a.copyForCodec().contentEquals(b.copyForCodec())
        private fun sameRecord(a: SessionControlRecord, b: SessionControlRecord?) = b != null && a.revision == b.revision && sameBytes(a.payload, b.payload)
        private fun assertRecord(a: SessionControlRecord, b: SessionControlRecord) { assertEquals(a.revision, b.revision); assertTrue(sameBytes(a.payload, b.payload)) }
        private fun assertSelected(plan: SessionWorkOriginPlan, record: SessionControlRecord) {
            val selected = assertIs<SessionWorkState.SetupSelected>(SessionWorkCodec.decode(record.payload))
            assertTrue(sameBytes(plan.copyForStorage(), selected.plan.copyForStorage()))
        }
        private fun <T> value(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
        private fun failure(reason: FailureReason, result: PortResult<*>) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
    }
}
