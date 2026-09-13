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
import kotlinx.serialization.json.*
import kotlin.test.*

/** Detached CAS and proof lookup only; no native authentication, disk-sync or OS-effect claim. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionWorkOriginAbortTest {
    @Test fun preparedSelectedAndUntouchedSealedPlansBecomeExactConsumedMarkers() = runTest {
        for (stage in listOf("prepared", "selected", "sealed")) fixture { f ->
            val plan = f.prepare(stage); val before = f.store.record!!; val ids = f.ids; val signs = f.store.signs
            value(f.registry.abortOrigin(plan))
            assertEquals(before.revision + 1, f.store.record!!.revision); aborted(plan, f.store.record!!)
            val observation = value(f.registry.inspectOriginState(plan))
            assertEquals(SessionWorkOriginPlanStatus.ABORTED, observation.status)
            assertEquals(f.store.record!!.revision, observation.revision)
            assertEquals(ids, f.ids); assertEquals(signs, f.store.signs); assertNull(f.boundary.current()); f.noEffects()
        }
    }

    @Test fun abortedReplaySurvivesManagerReopenAndAlwaysChangesRevision() = runTest {
        fixture { f ->
            val plan = f.prepare("sealed"); value(f.registry.abortOrigin(plan))
            val bytes = f.store.record!!.payload
            repeat(3) {
                f.reopen(); val before = f.store.record!!; val writes = f.store.writes
                assertEquals(SessionWorkOriginPlanStatus.ABORTED, value(f.registry.inspectOrigin(plan)))
                assertRecord(before, f.store.record!!); assertEquals(writes, f.store.writes)
                value(f.registry.abortOrigin(plan)); assertEquals(before.revision + 1, f.store.record!!.revision)
                assertBytes(bytes, f.store.record!!.payload)
            }
            assertEquals(1, f.ids); f.noEffects()
        }
    }

    @Test fun consumedMarkerHasEmptyMetadataButNeverSelectsOrSealsTheOldPlanAgain() = runTest {
        fixture { f ->
            val plan = f.prepare(); value(f.registry.abortOrigin(plan)); val before = f.store.record!!
            val snapshot = value(f.registry.snapshot())
            assertEquals(before.revision, snapshot.revision); assertNull(snapshot.scope); assertNull(snapshot.originBinding)
            assertFalse(snapshot.retiring); assertTrue(snapshot.entries.isEmpty())
            failure(FailureReason.CONFLICT, f.registry.selectOrigin(plan)); failure(FailureReason.CONFLICT, f.registry.sealOrigin(plan))
            assertRecord(before, f.store.record!!); f.noEffects()
        }
    }

    @Test fun successorSignsExactAbortedPredecessorAndItsSelectionFencesOldPlan() = runTest {
        fixture { f ->
            val old = f.prepare(); value(f.registry.abortOrigin(old)); val predecessor = f.store.record!!
            val successor = f.plan(SCOPE.copy(actorId = "new-private-owner"))
            assertRecord(predecessor, f.store.record!!); assertRecord(predecessor, f.store.lastSigned!!)
            assertEquals(SessionWorkOriginPlanStatus.PREPARED, value(f.registry.inspectOrigin(successor)))
            value(f.registry.selectOrigin(successor)); val selected = f.store.record!!; val writes = f.store.writes
            for (action in listOf<suspend () -> PortResult<*>>({ f.registry.abortOrigin(old) },
                { f.registry.selectOrigin(old) }, { f.registry.inspectOrigin(old) })) failure(FailureReason.STALE_SESSION, action())
            assertRecord(selected, f.store.record!!); assertEquals(writes, f.store.writes)
            assertEquals(SessionWorkOriginPlanStatus.SELECTED, value(f.registry.inspectOrigin(successor))); f.noEffects()
        }
    }

    @Test fun readOnlySuccessorPlanningIsNotAReservationAgainstOldAbortReacknowledgement() = runTest {
        fixture { f ->
            val old = f.prepare(); value(f.registry.abortOrigin(old)); val next = f.plan()
            val before = f.store.record!!
            value(f.registry.abortOrigin(old)); assertEquals(before.revision + 1, f.store.record!!.revision)
            failure(FailureReason.STALE_SESSION, f.registry.selectOrigin(next))
            assertEquals(SessionWorkOriginPlanStatus.ABORTED, value(f.registry.inspectOrigin(old))); f.noEffects()
        }
    }

    @Test fun successorCannotUseReorderedOrChangedRawAbortPredecessor() = runTest {
        for (revision in listOf(false, true)) fixture { f ->
            val old = f.prepare(); value(f.registry.abortOrigin(old)); val next = f.plan(); val marker = f.store.record!!
            f.store.record = if (revision) SessionControlRecord(marker.revision + 1, marker.payload)
                else SessionControlRecord(marker.revision, bytes(" " + marker.payload.copyForCodec().decodeToString()))
            val writes = f.store.writes
            failure(FailureReason.STALE_SESSION, f.registry.selectOrigin(next))
            assertEquals(writes, f.store.writes); f.noEffects()
        }
    }

    @Test fun malformedOrForeignAbortedProofCannotBeBlessedBySuccessorPlanning() = runTest {
        fixture { f ->
            val old = f.prepare(); value(f.registry.abortOrigin(old))
            val invalid = SessionWorkOriginPlan.create(details(old).copy(proof = PrivateBytes(ByteArray(64) { 99 })))
            f.store.record = SessionControlRecord(f.store.record!!.revision, SessionWorkCodec.encode(SessionWorkState.SetupAborted(invalid)))
            val ids = f.ids; val signs = f.store.signs; val writes = f.store.writes
            failure(FailureReason.INVALID_DATA, f.registry.planOrigin(SCOPE, f.store.record!!.revision))
            failure(FailureReason.INVALID_DATA, f.registry.abortOrigin(invalid))
            assertEquals(ids, f.ids); assertEquals(signs, f.store.signs); assertEquals(writes, f.store.writes); f.noEffects()
        }
    }

    @Test fun aFreshPlanCannotReuseTheImmediatelyConsumedOriginIdentity() = runTest {
        fixture { f ->
            val old = f.prepare(); value(f.registry.abortOrigin(old)); val before = f.store.record!!; val signs = f.store.signs
            f.nextId = { details(old).origin }
            failure(FailureReason.CONFLICT, f.registry.planOrigin(SCOPE, before.revision))
            assertEquals(signs, f.store.signs); assertRecord(before, f.store.record!!); f.noEffects()
        }
    }

    @Test fun ordinaryOriginCreationResumeAndRetirementCannotEraseSetupMarkers() = runTest {
        for (abort in listOf(false, true)) fixture { f ->
            val plan = f.prepare("selected"); if (abort) value(f.registry.abortOrigin(plan))
            val before = f.store.record!!; val origin = details(plan).origin
            failure(FailureReason.CONFLICT, f.registry.retire(SCOPE, origin))
            failure(FailureReason.CONFLICT, f.registry.retireEmpty(SCOPE, origin))
            failure(FailureReason.CONFLICT, f.registry.retire(SCOPE, uuid(99)))
            failure(FailureReason.CONFLICT, f.registry.retireEmpty(SCOPE, uuid(99)))
            val lease = f.boundary.activate(SCOPE)
            failure(FailureReason.CONFLICT, f.registry.createOrigin(lease, before.revision))
            failure(FailureReason.NOT_CONFIGURED, f.registry.resume(lease))
            assertTrue(f.boundary.isCurrent(lease)); f.boundary.clear()
            assertRecord(before, f.store.record!!); assertEquals(0, f.effects)
            assertEquals(if (abort) SessionWorkOriginPlanStatus.ABORTED else SessionWorkOriginPlanStatus.SELECTED,
                value(f.registry.inspectOrigin(plan)))
        }
    }

    @Test fun ordinarySuccessfulResumeConsumesSealSoAnEmptyUsedOriginCannotBeAborted() = runTest {
        fixture { f ->
            val plan = f.prepare("sealed"); val lease = f.boundary.activate(SCOPE)
            value(f.registry.resume(lease)); f.boundary.clear(); val before = f.store.record!!
            failure(FailureReason.STALE_SESSION, f.registry.abortOrigin(plan))
            assertRecord(before, f.store.record!!); assertEquals(0, f.effects)
        }
    }

    @Test fun failuresBeforeOrAfterCasNeverPromoteReadbackAndRetryUsesTheExactPlan() = runTest {
        for (committed in listOf(false, true)) for (reason in listOf(FailureReason.STORAGE_FAILURE, FailureReason.OUTCOME_UNKNOWN)) fixture { f ->
            val plan = f.prepare("selected")
            if (committed) f.store.afterFailure = reason else f.store.beforeFailure = reason
            failure(reason, f.registry.abortOrigin(plan))
            assertEquals(f.store.readsAtCas, f.store.reads, "Failure must not ask readback to replace its acknowledgement")
            val before = f.store.record!!; f.reopen()
            value(f.registry.abortOrigin(plan)); assertEquals(before.revision + 1, f.store.record!!.revision)
            aborted(plan, f.store.record!!); assertEquals(1, f.ids); f.noEffects()
        }
    }

    @Test fun repeatedUnknownAbortedReplaysStillRequireAFreshSuccessfulCas() = runTest {
        fixture { f ->
            val plan = f.prepare(); value(f.registry.abortOrigin(plan))
            repeat(3) {
                val before = f.store.record!!; f.store.afterFailure = FailureReason.OUTCOME_UNKNOWN
                failure(FailureReason.OUTCOME_UNKNOWN, f.registry.abortOrigin(plan))
                assertEquals(before.revision + 1, f.store.record!!.revision); assertBytes(before.payload, f.store.record!!.payload)
                assertEquals(SessionWorkOriginPlanStatus.ABORTED, value(f.registry.inspectOrigin(plan)))
            }
            val last = f.store.record!!; value(f.registry.abortOrigin(plan)); assertEquals(last.revision + 1, f.store.record!!.revision)
            f.noEffects()
        }
    }

    @Test fun malformedSuccessReceiptNeverAcknowledgesInitialAbortOrReplay() = runTest {
        for (replay in listOf(false, true)) for (kind in listOf("old", "jump", "payload")) fixture { f ->
            val plan = f.prepare(); if (replay) value(f.registry.abortOrigin(plan))
            f.store.receipt = { actual -> when (kind) {
                "old" -> SessionControlRecord(actual.revision - 1, actual.payload)
                "jump" -> SessionControlRecord(actual.revision + 1, actual.payload)
                else -> SessionControlRecord(actual.revision, SessionWorkCodec.encode(SessionWorkState.Idle))
            } }
            failure(FailureReason.STORAGE_FAILURE, f.registry.abortOrigin(plan)); aborted(plan, f.store.record!!)
            val before = f.store.record!!; f.store.receipt = { it }
            value(f.registry.abortOrigin(plan)); assertEquals(before.revision + 1, f.store.record!!.revision); f.noEffects()
        }
    }

    @Test fun successfulAbortCasNeedsExactReadbackWithoutMissingOlderNewerOrWrongPayload() = runTest {
        for ((kind, reason) in mapOf("missing" to FailureReason.STORAGE_FAILURE, "older" to FailureReason.OUTCOME_UNKNOWN,
            "newer" to FailureReason.CONFLICT, "payload" to FailureReason.OUTCOME_UNKNOWN, "failure" to FailureReason.UNAVAILABLE)) fixture { f ->
            val plan = f.prepare()
            f.store.afterCas = {
                val current = f.store.record!!
                when (kind) {
                    "missing" -> f.store.record = null
                    "older" -> f.store.record = SessionControlRecord(current.revision - 1, current.payload)
                    "newer" -> f.store.record = SessionControlRecord(current.revision + 1, current.payload)
                    "payload" -> f.store.record = SessionControlRecord(current.revision, SessionWorkCodec.encode(SessionWorkState.Idle))
                    else -> f.store.readFailure = FailureReason.UNAVAILABLE
                }
            }
            failure(reason, f.registry.abortOrigin(plan)); assertEquals(1, f.store.writes); f.noEffects()
        }
    }

    @Test fun activeOrNewlyActivatedBoundaryBlocksAbortWithoutClearingNewIdentity() = runTest {
        for (point in listOf("entry", "read", "verify", "cas")) fixture { f ->
            val plan = f.prepare("selected"); val writes = f.store.writes
            when (point) {
                "entry" -> f.boundary.activate(SCOPE)
                "read" -> f.store.afterRead = { f.boundary.activate(SCOPE) }
                "verify" -> f.store.afterVerify = { f.boundary.activate(SCOPE) }
                else -> f.store.afterCas = { f.boundary.activate(SCOPE) }
            }
            failure(FailureReason.STALE_SESSION, f.registry.abortOrigin(plan))
            assertNotNull(f.boundary.current()); assertEquals(writes + if (point == "cas") 1 else 0, f.store.writes); f.noEffects()
        }
    }

    @Test fun noncooperativeCancellationAfterReadProofCasOrReadbackStopsAcknowledgement() = runTest {
        for (point in listOf("read", "verify", "cas", "readback")) fixture { f ->
            val plan = f.prepare("selected"); val writes = f.store.writes
            val cancel: suspend () -> Unit = { currentCoroutineContext().cancel() }
            when (point) {
                "read" -> f.store.afterRead = cancel
                "verify" -> f.store.afterVerify = cancel
                "cas" -> f.store.afterCas = cancel
                else -> f.store.afterCas = { f.store.afterRead = cancel }
            }
            val pending = async { f.registry.abortOrigin(plan) }
            assertFailsWith<CancellationException> { pending.await() }
            assertEquals(writes + if (point in listOf("cas", "readback")) 1 else 0, f.store.writes)
            f.store.afterRead = {}; f.store.afterVerify = {}; f.store.afterCas = {}
            value(f.registry.abortOrigin(plan)); aborted(plan, f.store.record!!); f.noEffects()
        }
    }

    @Test fun changedRecordDuringAuthenticationPreventsAbortAndSuccessorPlanning() = runTest {
        for (planning in listOf(false, true)) fixture { f ->
            val plan = f.prepare("selected"); if (planning) value(f.registry.abortOrigin(plan))
            val writes = f.store.writes; val ids = f.ids
            f.store.afterVerify = { val prior = f.store.record!!; f.store.record = SessionControlRecord(prior.revision + 1, prior.payload) }
            failure(FailureReason.CONFLICT, if (planning) f.registry.planOrigin(SCOPE, f.store.record!!.revision) else f.registry.abortOrigin(plan))
            assertEquals(writes, f.store.writes); assertEquals(ids, f.ids); f.noEffects()
        }
    }

    @Test fun missingCapabilityForeignProofCorruptMissingAndClosedStoresHaveNoFallback() = runTest {
        for (kind in listOf("capability", "proof", "corrupt", "missing", "closed")) fixture { f ->
            val original = f.prepare(); var plan = original
            when (kind) {
                "capability" -> { value(f.registry.close()); f.registry = value(f.open(object : SessionControlStore by f.store {})) }
                "proof" -> plan = SessionWorkOriginPlan.create(details(original).copy(proof = PrivateBytes(ByteArray(64))))
                "corrupt" -> f.store.record = SessionControlRecord(1, bytes("private-corrupt-marker"))
                "missing" -> f.store.record = null
                else -> value(f.registry.close())
            }
            failure(when (kind) { "capability" -> FailureReason.NOT_CONFIGURED; "proof" -> FailureReason.INVALID_DATA; else -> FailureReason.STORAGE_FAILURE },
                f.registry.abortOrigin(plan))
            assertEquals(0, f.store.writes); f.noEffects()
        }
    }

    @Test fun ordinaryUsedRetiringPopulatedOrCompetingOriginsNeverQualifyAsAbortTargets() = runTest {
        for (kind in 0..3) fixture { f ->
            val plan = f.prepare(); val origin = details(plan).origin
            val entries = if (kind == 2) listOf(SessionWorkEntry(uuid(50), NativeWorkKind.TIMER, "private-timer", NativeWorkPhase.RESERVED)) else emptyList()
            f.store.record = SessionControlRecord(8, SessionWorkCodec.encode(SessionWorkState.Origin(SCOPE, if (kind == 3) uuid(99) else origin, kind == 1, entries)))
            val before = f.store.record!!; failure(FailureReason.STALE_SESSION, f.registry.abortOrigin(plan))
            assertRecord(before, f.store.record!!); assertEquals(0, f.store.writes); f.noEffects()
        }
    }

    @Test fun impossibleConsumedRevisionLaterIdleAndExhaustionNeverResetAuthority() = runTest {
        fixture { f ->
            val plan = f.prepare(); value(f.registry.abortOrigin(plan)); val marker = f.store.record!!
            f.store.record = SessionControlRecord(details(plan).expectedRevision, marker.payload)
            failure(FailureReason.STALE_SESSION, f.registry.abortOrigin(plan))
            f.store.record = SessionControlRecord(marker.revision + 1, SessionWorkCodec.encode(SessionWorkState.Idle))
            failure(FailureReason.STALE_SESSION, f.registry.abortOrigin(plan))
            f.store.record = SessionControlRecord(Long.MAX_VALUE, marker.payload); val writes = f.store.writes
            assertEquals(SessionWorkOriginPlanStatus.ABORTED, value(f.registry.inspectOrigin(plan)))
            failure(FailureReason.STORAGE_FAILURE, f.registry.abortOrigin(plan)); assertEquals(writes, f.store.writes); f.noEffects()
        }
    }

    @Test fun exactNoncanonicalIdlePredecessorIsAuthenticatedButAbortedMarkerIsCanonical() = runTest {
        fixture { f ->
            f.store.record = SessionControlRecord(4, bytes("{\"state\":\"idle\",\"version\":1}"))
            val plan = f.plan(); value(f.registry.abortOrigin(plan)); aborted(plan, f.store.record!!)
            assertEquals(5L, f.store.record!!.revision); f.noEffects()
        }
    }

    @Test fun abortedCodecKeepsExactOpaquePlanAndLegacyWireBytesDetached() {
        val plan = structuralPlan(); val encoded = SessionWorkCodec.encode(SessionWorkState.SetupAborted(plan))
        val expected = """{"version":1,"state":"setup-aborted","plan":"${hex(plan.copyForStorage().copyForCodec())}"}"""
        assertEquals(expected, encoded.copyForCodec().decodeToString())
        val decoded = assertIs<SessionWorkState.SetupAborted>(SessionWorkCodec.decode(encoded))
        assertBytes(plan.copyForStorage(), decoded.plan.copyForStorage()); encoded.copyForCodec().fill(0)
        assertEquals(expected, SessionWorkCodec.encode(decoded).copyForCodec().decodeToString())
        assertEquals("{\"version\":1,\"state\":\"idle\"}", SessionWorkCodec.encode(SessionWorkState.Idle).copyForCodec().decodeToString())
        assertEquals("SessionWorkSetupAborted(<redacted>)", decoded.toString())
    }

    @Test fun abortedCodecRequiresExactFieldsAndCanonicalBoundedNestedPlan() {
        val root = Json.parseToJsonElement(SessionWorkCodec.encode(SessionWorkState.SetupAborted(structuralPlan())).copyForCodec().decodeToString()).jsonObject
        for (key in root.keys) invalid(JsonObject(root - key).toString())
        for (key in listOf("entries", "scope", "origin", "done", "lease", "setupPlan"))
            invalid(JsonObject(root + (key to JsonPrimitive("private-extra"))).toString())
        for (value in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(""), JsonPrimitive("0"), JsonPrimitive("FF"), JsonPrimitive("00".repeat(4097))))
            invalid(JsonObject(root + ("plan" to value)).toString())
        val noncanonical = bytes(" " + structuralPlan().copyForStorage().copyForCodec().decodeToString())
        invalid(JsonObject(root + ("plan" to JsonPrimitive(hex(noncanonical.copyForCodec())))).toString())
    }

    @Test fun abortedCodecRejectsDuplicateVersionAndUnknownProgressWithoutPrivateDiagnostics() {
        val raw = SessionWorkCodec.encode(SessionWorkState.SetupAborted(structuralPlan())).copyForCodec().decodeToString()
        for (version in listOf("2", "1.0", "1e0", "\"1\"", "null")) invalid(raw.replace("\"version\":1", "\"version\":$version"))
        invalid(raw.replace("\"state\":", "\"state\":\"idle\",\"state\":")); invalid(" ".repeat(32_769) + raw)
        val error = assertFailsWith<SessionWorkFormatException> { SessionWorkCodec.decode(bytes("private-invalid-plan")) }
        assertEquals("Native work data unavailable", error.message); assertNull(error.cause)
        for (value in listOf(SessionWorkState.SetupAborted(structuralPlan()), structuralPlan(), error))
            for (marker in listOf(SCOPE.actorId, SCOPE.environment, uuid(1), "private-invalid-plan")) assertFalse(value.toString().contains(marker))
    }

    private suspend fun TestScope.fixture(action: suspend (Fixture) -> Unit) {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try { f.reopen(); action(f) } finally { value(f.registry.close()) }
    }
    private class Fixture(private val dispatcher: CoroutineDispatcher) {
        val store = ProofStore(); val boundary = SessionBoundary(); lateinit var registry: SessionWorkRegistry
        var ids = 0; var policies = 0; var effects = 0
        var nextId: () -> String = { uuid(ids) }
        suspend fun open(control: SessionControlStore = store) = SessionWorkRegistry.open(control, boundary, dispatcher,
            NativeWorkCancellationPort { effects++; PortResult.Value(Unit) }, NativeWorkIdSource { ids++; nextId() },
            NativeWorkAdmissionPolicy { policies++; PortResult.Value(true) }, NativeWorkExecutionPolicy { _, _, _, _ -> policies++; PortResult.Value(true) })
        suspend fun reopen() { if (::registry.isInitialized) value(registry.close()); registry = value(open()) }
        suspend fun plan(scope: StorageScope = SCOPE) = value(registry.planOrigin(scope, store.record!!.revision))
        suspend fun prepare(stage: String = "prepared"): SessionWorkOriginPlan {
            val plan = plan()
            if (stage != "prepared") value(registry.selectOrigin(plan))
            if (stage == "sealed") value(registry.sealOrigin(plan))
            return plan
        }
        fun noEffects() { assertEquals(0, effects); assertEquals(0, policies) }
    }
    private class ProofStore : SessionControlStore, WorkOriginPlanAuthentication {
        var record: SessionControlRecord? = SessionControlRecord(1, SessionWorkCodec.encode(SessionWorkState.Idle))
        var reads = 0; var readsAtCas = 0; var writes = 0; var signs = 0
        var lastSigned: SessionControlRecord? = null
        var beforeFailure: FailureReason? = null; var afterFailure: FailureReason? = null; var readFailure: FailureReason? = null
        var afterRead: suspend () -> Unit = {}; var afterVerify: suspend () -> Unit = {}; var afterCas: suspend () -> Unit = {}
        var receipt: (SessionControlRecord) -> SessionControlRecord = { it }
        private val issued = mutableListOf<Proof>()
        override suspend fun read(): PortResult<SessionControlRecord?> {
            reads++; val result = readFailure?.let { PortResult.Failure(it) } ?: PortResult.Value(record?.copyRecord())
            afterRead(); return result
        }
        override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> {
            writes++; readsAtCas = reads
            beforeFailure?.let { beforeFailure = null; return PortResult.Failure(it) }
            val prior = record ?: return PortResult.Failure(FailureReason.STORAGE_FAILURE)
            if (prior.revision != expectedRevision) return PortResult.Failure(FailureReason.CONFLICT)
            if (prior.revision == Long.MAX_VALUE) return PortResult.Failure(FailureReason.STORAGE_FAILURE)
            val next = SessionControlRecord(prior.revision + 1, PrivateBytes(payload.copyForCodec())); record = next.copyRecord()
            afterCas(); afterFailure?.let { afterFailure = null; return PortResult.Failure(it) }
            return PortResult.Value(receipt(next.copyRecord()))
        }
        override suspend fun signOriginPlan(expected: SessionControlRecord, proposal: PrivateBytes): PortResult<PrivateBytes> {
            if (!sameRecord(expected, record)) return PortResult.Failure(FailureReason.CONFLICT)
            signs++; lastSigned = expected.copyRecord()
            val proof = PrivateBytes(ByteArray(64) { (it + signs * 13).toByte() })
            issued += Proof(expected.copyRecord(), PrivateBytes(proposal.copyForCodec()), proof)
            return PortResult.Value(proof)
        }
        override suspend fun verifyOriginPlan(expectedRevision: Long, proposal: PrivateBytes, proof: PrivateBytes): PortResult<Unit> {
            val valid = issued.any { it.expected.revision == expectedRevision && sameBytes(it.proposal, proposal) && sameBytes(it.proof, proof) }
            afterVerify(); return if (valid) PortResult.Value(Unit) else PortResult.Failure(FailureReason.INVALID_DATA)
        }
        override suspend fun verifyOriginPredecessor(expected: SessionControlRecord, proposal: PrivateBytes, proof: PrivateBytes): PortResult<Unit> =
            if (sameRecord(expected, record) && issued.any { sameRecord(it.expected, expected) && sameBytes(it.proposal, proposal) && sameBytes(it.proof, proof) })
                PortResult.Value(Unit) else PortResult.Failure(FailureReason.INVALID_DATA)
        private class Proof(val expected: SessionControlRecord, val proposal: PrivateBytes, val proof: PrivateBytes)
    }
    companion object {
        private val SCOPE = StorageScope("work-abort-test", ActorKind.ACCOUNT, "private-abort-owner")
        private fun uuid(index: Int) = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
        private fun details(plan: SessionWorkOriginPlan) = SessionWorkOriginPlanCodec.decode(plan.copyForStorage())
        private fun structuralPlan() = SessionWorkOriginPlan.create(SessionWorkOriginPlanRecord(1, SCOPE, uuid(1), PrivateBytes(ByteArray(64))))
        private fun bytes(value: String) = PrivateBytes(value.encodeToByteArray())
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        private fun SessionControlRecord.copyRecord() = SessionControlRecord(revision, PrivateBytes(payload.copyForCodec()))
        private fun sameBytes(a: PrivateBytes, b: PrivateBytes) = a.copyForCodec().contentEquals(b.copyForCodec())
        private fun sameRecord(a: SessionControlRecord, b: SessionControlRecord?) = b != null && a.revision == b.revision && sameBytes(a.payload, b.payload)
        private fun assertBytes(a: PrivateBytes, b: PrivateBytes) = assertContentEquals(a.copyForCodec(), b.copyForCodec())
        private fun assertRecord(a: SessionControlRecord, b: SessionControlRecord) { assertEquals(a.revision, b.revision); assertBytes(a.payload, b.payload) }
        private fun aborted(plan: SessionWorkOriginPlan, record: SessionControlRecord) {
            val state = assertIs<SessionWorkState.SetupAborted>(SessionWorkCodec.decode(record.payload))
            assertBytes(plan.copyForStorage(), state.plan.copyForStorage())
            assertBytes(record.payload, SessionWorkCodec.encode(state))
        }
        private fun invalid(raw: String) { assertFailsWith<SessionWorkFormatException> { SessionWorkCodec.decode(bytes(raw)) } }
        private fun failure(reason: FailureReason, result: PortResult<*>) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
        private fun <T> value(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
    }
}
