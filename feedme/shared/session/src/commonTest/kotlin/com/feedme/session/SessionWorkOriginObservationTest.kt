package com.feedme.session

import com.feedme.core.ports.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Lookup-equivalent proof fake and detached CAS ledger; not native authentication or sync proof. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionWorkOriginObservationTest {
    @Test fun preparedSelectedAndSealedExposeOnlyTheirExactAuthenticatedRevision() = runTest {
        for (status in SessionWorkOriginPlanStatus.entries) fixture { f ->
            f.stage(status)
            val before = f.store.record!!.copyRecord()
            val observation = value(f.registry.inspectOriginState(PLAN))
            assertEquals(status, observation.status)
            assertEquals(before.revision, observation.revision)
            assertEquals(status, value(f.registry.inspectOrigin(PLAN)), "Legacy enum observation remains unchanged")
            assertRecord(before, f.store.record!!)
            assertEquals(0, f.store.writes)
            assertEquals(if (status == SessionWorkOriginPlanStatus.PREPARED) 2 else 0, f.store.predecessors)
            f.noEffects()
        }
    }

    @Test fun samePayloadSelectedAndSealedReacknowledgementsChangeObservedRevision() = runTest {
        fixture { f ->
            value(f.registry.selectOrigin(PLAN))
            for (sealed in listOf(false, true)) {
                if (sealed) value(f.registry.sealOrigin(PLAN))
                val before = value(f.registry.inspectOriginState(PLAN))
                val payload = f.store.record!!.payload.copyForCodec()
                if (sealed) value(f.registry.sealOrigin(PLAN)) else value(f.registry.selectOrigin(PLAN))
                val writes = f.store.writes
                val after = value(f.registry.inspectOriginState(PLAN))
                assertEquals(before.status, after.status)
                assertEquals(before.revision + 1, after.revision)
                assertContentEquals(payload, f.store.record!!.payload.copyForCodec())
                assertEquals(writes, f.store.writes, "Observation must not re-acknowledge the ledger")
            }
            f.noEffects()
        }
    }

    @Test fun changedRevisionOrPayloadDuringProofNeverReturnsAnEarlierObservation() = runTest {
        for (status in SessionWorkOriginPlanStatus.entries) for (revisionOnly in listOf(false, true)) fixture { f ->
            f.stage(status)
            val change: suspend () -> Unit = {
                val prior = f.store.record!!
                f.store.record = if (revisionOnly) SessionControlRecord(prior.revision + 1, prior.payload)
                    else SessionControlRecord(prior.revision, SessionWorkCodec.encode(SessionWorkState.Idle))
                if (status == SessionWorkOriginPlanStatus.PREPARED && !revisionOnly)
                    f.store.record = SessionControlRecord(prior.revision,
                        PrivateBytes("{\"state\":\"idle\",\"version\":1}".encodeToByteArray()))
            }
            if (status == SessionWorkOriginPlanStatus.PREPARED) f.store.afterPredecessor = change
            else f.store.afterVerify = change
            failure(FailureReason.CONFLICT, f.registry.inspectOriginState(PLAN))
            assertEquals(0, f.store.writes); f.noEffects()
        }
    }

    @Test fun foreignOrTamperedProofAndMissingNativeCapabilityFailWithoutFallback() = runTest {
        fixture { f ->
            val invalid = listOf(DETAILS.copy(proof = PrivateBytes(ByteArray(64))),
                DETAILS.copy(scope = SCOPE.copy(actorId = "different-private-owner")),
                DETAILS.copy(expectedRevision = INITIAL_REVISION + 1), DETAILS.copy(origin = OTHER_ORIGIN))
            for (details in invalid)
                failure(FailureReason.INVALID_DATA, f.registry.inspectOriginState(SessionWorkOriginPlan.create(details)))
            f.store.validProof = false
            failure(FailureReason.INVALID_DATA, f.registry.inspectOriginState(PLAN))
            value(f.registry.close())
            f.registry = value(f.open(object : SessionControlStore by f.store {}))
            failure(FailureReason.NOT_CONFIGURED, f.registry.inspectOriginState(PLAN))
            assertEquals(0, f.store.writes); f.noEffects()
        }
    }

    @Test fun authenticatedPredecessorAndCanonicalSelectedSealedBytesCannotBeNormalized() = runTest {
        for (status in SessionWorkOriginPlanStatus.entries) fixture { f ->
            f.stage(status)
            val original = f.store.record!!
            f.store.record = SessionControlRecord(original.revision,
                PrivateBytes((" " + original.payload.copyForCodec().decodeToString()).encodeToByteArray()))
            failure(if (status == SessionWorkOriginPlanStatus.PREPARED) FailureReason.INVALID_DATA else FailureReason.STALE_SESSION,
                f.registry.inspectOriginState(PLAN))
            assertEquals(0, f.store.writes); f.noEffects()
        }
    }

    @Test fun missingCorruptAndUnreadableRecordsRemainFailuresWithoutInitialization() = runTest {
        for (problem in listOf("missing", "corrupt", "unreadable")) fixture { f ->
            when (problem) {
                "missing" -> f.store.record = null
                "corrupt" -> f.store.record = SessionControlRecord(INITIAL_REVISION, PrivateBytes("private-corruption".encodeToByteArray()))
                else -> f.store.readFailure = FailureReason.UNAVAILABLE
            }
            failure(if (problem == "unreadable") FailureReason.UNAVAILABLE else FailureReason.STORAGE_FAILURE,
                f.registry.inspectOriginState(PLAN))
            assertEquals(0, f.store.writes); assertEquals(0, f.store.verifications); f.noEffects()
        }
    }

    @Test fun activeOrLateReplacementLeaseIsRejectedAndNeverClearedByInspection() = runTest {
        for (point in listOf("entry", "read", "proof", "predecessor")) fixture { f ->
            var lease: SessionLease? = null
            val activate: suspend () -> Unit = { lease = f.boundary.activate(SCOPE) }
            when (point) {
                "entry" -> activate()
                "read" -> f.store.afterRead = activate
                "proof" -> f.store.afterVerify = activate
                else -> f.store.afterPredecessor = activate
            }
            val initialReads = f.store.reads
            failure(FailureReason.STALE_SESSION, f.registry.inspectOriginState(PLAN))
            assertTrue(f.boundary.isCurrent(assertNotNull(lease)))
            if (point == "entry") assertEquals(initialReads, f.store.reads)
            assertEquals(0, f.store.writes); f.noEffects()
        }
    }

    @Test fun cancellationAfterEveryReadOrProofBoundaryCannotReturnObservation() = runTest {
        for (point in listOf("read", "proof", "predecessor", "readback")) fixture { f ->
            val cancel: suspend () -> Unit = { currentCoroutineContext().cancel() }
            when (point) {
                "read" -> f.store.afterRead = cancel
                "proof" -> f.store.afterVerify = cancel
                "predecessor" -> f.store.afterPredecessor = cancel
                else -> f.store.afterPredecessor = { f.store.afterRead = cancel }
            }
            val pending = async { f.registry.inspectOriginState(PLAN) }
            assertFailsWith<CancellationException> { pending.await() }
            assertEquals(0, f.store.writes); f.noEffects()
        }
    }

    @Test fun queuedObservationAfterAcknowledgedCloseDoesNotReadOrAuthenticate() = runTest {
        fixture { f ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.store.afterVerify = { entered.complete(Unit); release.await() }
            val first = async { f.registry.inspectOriginState(PLAN) }
            entered.await()
            val close = async { f.registry.close() }; runCurrent()
            val queued = async { f.registry.inspectOriginState(PLAN) }; runCurrent()
            release.complete(Unit)
            value(first.await()); value(close.await())
            val reads = f.store.reads; val verifications = f.store.verifications
            failure(FailureReason.STORAGE_FAILURE, queued.await())
            failure(FailureReason.STORAGE_FAILURE, f.registry.inspectOriginState(PLAN))
            assertEquals(reads, f.store.reads); assertEquals(verifications, f.store.verifications)
            assertEquals(0, f.store.writes); f.noEffects()
        }
    }

    @Test fun visibleSelectionAfterUnknownWriteIsEvidenceButNeverAnAcknowledgement() = runTest {
        fixture { f ->
            f.store.afterWriteFailure = FailureReason.OUTCOME_UNKNOWN
            failure(FailureReason.OUTCOME_UNKNOWN, f.registry.selectOrigin(PLAN))
            val writes = f.store.writes
            val observation = value(f.registry.inspectOriginState(PLAN))
            assertEquals(SessionWorkOriginPlanStatus.SELECTED, observation.status)
            assertEquals(INITIAL_REVISION + 1, observation.revision)
            assertEquals(writes, f.store.writes)
            failure(FailureReason.CONFLICT, f.registry.snapshot())
            assertNull(f.boundary.current()); f.noEffects()
        }
    }

    @Test fun ordinaryUsedRetiringOrCompetingOriginsNeverProduceSetupEvidence() = runTest {
        fixture { f ->
            val variants = listOf(
                SessionWorkState.Origin(SCOPE, ORIGIN, false, emptyList()),
                SessionWorkState.Origin(SCOPE, ORIGIN, true, emptyList()),
                SessionWorkState.Origin(SCOPE, ORIGIN, false,
                    listOf(SessionWorkEntry(OTHER_ORIGIN, NativeWorkKind.TIMER, "private-timer", NativeWorkPhase.INSTALLED))),
                SessionWorkState.SetupSelected(SessionWorkOriginPlan.create(DETAILS.copy(origin = OTHER_ORIGIN))),
            )
            for (state in variants) {
                f.store.record = SessionControlRecord(INITIAL_REVISION + 3, SessionWorkCodec.encode(state))
                failure(FailureReason.STALE_SESSION, f.registry.inspectOriginState(PLAN))
            }
            assertEquals(0, f.store.writes); f.noEffects()
        }
    }

    @Test fun observationValidatesPositiveRevisionAndRedactsAllDiagnosticFields() {
        for (revision in listOf(0L, -1L, Long.MIN_VALUE))
            assertFailsWith<IllegalArgumentException> { SessionWorkOriginObservation(SessionWorkOriginPlanStatus.PREPARED, revision) }
        for (status in SessionWorkOriginPlanStatus.entries) for (revision in listOf(1L, Long.MAX_VALUE)) {
            val observation = SessionWorkOriginObservation(status, revision)
            assertEquals(status, observation.status); assertEquals(revision, observation.revision)
            assertEquals("SessionWorkOriginObservation(<redacted>)", observation.toString())
        }
    }

    private suspend fun TestScope.fixture(action: suspend (Fixture) -> Unit) {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.registry = value(f.open())
        try { action(f) } finally { value(f.registry.close()) }
    }

    private class Fixture(private val dispatcher: CoroutineDispatcher) {
        val store = ProofStore()
        val boundary = SessionBoundary()
        lateinit var registry: SessionWorkRegistry
        var ids = 0; var policies = 0; var cancellations = 0
        suspend fun open(control: SessionControlStore = store) = SessionWorkRegistry.open(control, boundary, dispatcher,
            NativeWorkCancellationPort { cancellations++; PortResult.Failure(FailureReason.FORBIDDEN) },
            NativeWorkIdSource { ids++; error("No ID request is permitted") },
            NativeWorkAdmissionPolicy { policies++; PortResult.Failure(FailureReason.FORBIDDEN) },
            NativeWorkExecutionPolicy { _, _, _, _ -> policies++; PortResult.Failure(FailureReason.FORBIDDEN) })
        fun stage(status: SessionWorkOriginPlanStatus) {
            store.record = when (status) {
                SessionWorkOriginPlanStatus.PREPARED -> SessionControlRecord(INITIAL_REVISION, IDLE)
                SessionWorkOriginPlanStatus.SELECTED -> SessionControlRecord(INITIAL_REVISION + 1, SessionWorkCodec.encode(SessionWorkState.SetupSelected(PLAN)))
                SessionWorkOriginPlanStatus.SEALED -> SessionControlRecord(INITIAL_REVISION + 2,
                    SessionWorkCodec.encode(SessionWorkState.Origin(SCOPE, ORIGIN, false, emptyList(), PLAN)))
                SessionWorkOriginPlanStatus.ABORTED -> SessionControlRecord(INITIAL_REVISION + 1,
                    SessionWorkCodec.encode(SessionWorkState.SetupAborted(PLAN)))
            }
        }
        fun noEffects() { assertEquals(0, ids); assertEquals(0, policies); assertEquals(0, cancellations); assertEquals(0, store.signs) }
    }

    private class ProofStore : SessionControlStore, WorkOriginPlanAuthentication {
        var record: SessionControlRecord? = SessionControlRecord(INITIAL_REVISION, IDLE)
        var reads = 0; var writes = 0; var signs = 0; var verifications = 0; var predecessors = 0
        var readFailure: FailureReason? = null
        var afterWriteFailure: FailureReason? = null
        var validProof = true
        var afterRead: suspend () -> Unit = {}
        var afterVerify: suspend () -> Unit = {}
        var afterPredecessor: suspend () -> Unit = {}
        override suspend fun read(): PortResult<SessionControlRecord?> {
            reads++
            val outcome = readFailure?.let { PortResult.Failure(it) } ?: PortResult.Value(record?.copyRecord())
            afterRead()
            return outcome
        }
        override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> {
            writes++
            val before = record ?: return PortResult.Failure(FailureReason.STORAGE_FAILURE)
            if (expectedRevision != before.revision) return PortResult.Failure(FailureReason.CONFLICT)
            if (before.revision == Long.MAX_VALUE) return PortResult.Failure(FailureReason.STORAGE_FAILURE)
            val selected = SessionControlRecord(before.revision + 1, PrivateBytes(payload.copyForCodec()))
            record = selected.copyRecord()
            afterWriteFailure?.let { return PortResult.Failure(it) }
            return PortResult.Value(selected.copyRecord())
        }
        override suspend fun signOriginPlan(expected: SessionControlRecord, proposal: PrivateBytes): PortResult<PrivateBytes> {
            signs++
            return PortResult.Failure(FailureReason.FORBIDDEN)
        }
        override suspend fun verifyOriginPlan(expectedRevision: Long, proposal: PrivateBytes, proof: PrivateBytes): PortResult<Unit> {
            verifications++
            val valid = authentic(expectedRevision, proposal, proof)
            afterVerify()
            return if (valid) PortResult.Value(Unit) else PortResult.Failure(FailureReason.INVALID_DATA)
        }
        override suspend fun verifyOriginPredecessor(expected: SessionControlRecord, proposal: PrivateBytes, proof: PrivateBytes): PortResult<Unit> {
            predecessors++
            if (!sameRecord(expected, record)) return PortResult.Failure(FailureReason.CONFLICT)
            val valid = authentic(expected.revision, proposal, proof) && sameBytes(expected.payload, IDLE)
            afterPredecessor()
            return if (valid) PortResult.Value(Unit) else PortResult.Failure(FailureReason.INVALID_DATA)
        }
        private fun authentic(revision: Long, proposal: PrivateBytes, proof: PrivateBytes) = validProof &&
            revision == INITIAL_REVISION && sameBytes(proposal, SessionWorkOriginPlanCodec.encodeUnsigned(DETAILS)) && sameBytes(proof, DETAILS.proof)
    }

    companion object {
        private const val INITIAL_REVISION = 7L
        private val SCOPE = StorageScope("origin-observation-test", ActorKind.ACCOUNT, "private-observation-owner")
        private const val ORIGIN = "11111111-2222-4333-8444-555555555555"
        private const val OTHER_ORIGIN = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        private val DETAILS = SessionWorkOriginPlanRecord(INITIAL_REVISION, SCOPE, ORIGIN, PrivateBytes(ByteArray(64) { 19 }))
        private val PLAN = SessionWorkOriginPlan.create(DETAILS)
        private val IDLE = SessionWorkCodec.encode(SessionWorkState.Idle)
        private fun SessionControlRecord.copyRecord() = SessionControlRecord(revision, PrivateBytes(payload.copyForCodec()))
        private fun sameBytes(a: PrivateBytes, b: PrivateBytes) = a.copyForCodec().contentEquals(b.copyForCodec())
        private fun sameRecord(a: SessionControlRecord, b: SessionControlRecord?) = b != null && a.revision == b.revision && sameBytes(a.payload, b.payload)
        private fun assertRecord(a: SessionControlRecord, b: SessionControlRecord) { assertEquals(a.revision, b.revision); assertTrue(sameBytes(a.payload, b.payload)) }
        private fun <T> value(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
        private fun failure(reason: FailureReason, result: PortResult<*>) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
    }
}
