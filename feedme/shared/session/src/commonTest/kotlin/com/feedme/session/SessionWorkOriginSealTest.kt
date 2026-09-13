package com.feedme.session

import com.feedme.core.ports.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Detached CAS/proof-table fixtures prove protocol only, not native cryptography or disk sync. */
class SessionWorkOriginSealTest {
    @Test fun initialSealRetainsExactProvenanceAndReturnsNoLeaseOrNativeAuthority() = runTest {
        fixture { f ->
            val plan = f.selected()
            val selected = f.store.record!!
            value(f.registry.sealOrigin(plan))
            assertEquals(selected.revision + 1, f.store.record!!.revision)
            assertSealed(plan, f.store.record!!)
            assertEquals(SessionWorkOriginPlanStatus.SEALED, value(f.registry.inspectOrigin(plan)))
            val snapshot = value(f.registry.snapshot())
            assertEquals(SCOPE, snapshot.scope); assertEquals(details(plan).origin, snapshot.originBinding)
            assertFalse(snapshot.retiring); assertTrue(snapshot.entries.isEmpty())
            assertNull(f.boundary.current()); assertEquals(1, f.ids)
            f.noEffects()
        }
    }

    @Test fun sealedReplaySurvivesReopenAndAlwaysFreshlyAcknowledgesIdenticalBytes() = runTest {
        fixture { f ->
            val plan = f.sealed()
            val sealed = f.store.record!!
            f.reopen()
            repeat(2) {
                val before = f.store.record!!
                assertEquals(SessionWorkOriginPlanStatus.SEALED, value(f.registry.inspectOrigin(plan)))
                assertRecord(before, f.store.record!!)
                value(f.registry.sealOrigin(plan))
                assertEquals(before.revision + 1, f.store.record!!.revision)
                assertBytes(sealed.payload, f.store.record!!.payload)
            }
            assertEquals(1, f.ids); assertEquals(1, f.store.signs)
            f.noEffects()
        }
    }

    @Test fun preparedPlanCannotSkipSelectionAndSealedPlanCannotRegressToSelection() = runTest {
        fixture { f ->
            val plan = f.plan()
            val initial = f.store.record!!
            failure(FailureReason.CONFLICT, f.registry.sealOrigin(plan))
            assertRecord(initial, f.store.record!!)
            value(f.registry.selectOrigin(plan)); value(f.registry.sealOrigin(plan))
            val sealed = f.store.record!!; val writes = f.store.writes
            failure(FailureReason.CONFLICT, f.registry.selectOrigin(plan))
            assertRecord(sealed, f.store.record!!); assertEquals(writes, f.store.writes)
            f.noEffects()
        }
    }

    @Test fun beforeCommitFailurePreservesSelectionAndRetryUsesOriginalPlan() = runTest {
        for (reason in listOf(FailureReason.STORAGE_FAILURE, FailureReason.OUTCOME_UNKNOWN)) fixture { f ->
            val plan = f.selected(); val before = f.store.record!!
            f.store.beforeFailure = reason
            failure(reason, f.registry.sealOrigin(plan))
            assertRecord(before, f.store.record!!)
            assertEquals(SessionWorkOriginPlanStatus.SELECTED, value(f.registry.inspectOrigin(plan)))
            value(f.registry.sealOrigin(plan))
            assertSealed(plan, f.store.record!!); assertEquals(1, f.ids)
            f.noEffects()
        }
    }

    @Test fun visibleFailedSealIsNotAcknowledgedAndReplayRequiresAnotherChangedCas() = runTest {
        for (reason in listOf(FailureReason.STORAGE_FAILURE, FailureReason.OUTCOME_UNKNOWN)) fixture { f ->
            val plan = f.selected(); f.store.afterFailure = reason
            failure(reason, f.registry.sealOrigin(plan))
            val visible = f.store.record!!
            assertSealed(plan, visible)
            assertEquals(SessionWorkOriginPlanStatus.SEALED, value(f.registry.inspectOrigin(plan)))
            f.reopen()
            f.store.afterFailure = reason
            failure(reason, f.registry.sealOrigin(plan))
            val second = f.store.record!!
            assertEquals(visible.revision + 1, second.revision)
            value(f.registry.sealOrigin(plan))
            assertEquals(second.revision + 1, f.store.record!!.revision)
            assertBytes(visible.payload, f.store.record!!.payload)
            f.noEffects()
        }
    }

    @Test fun falseSuccessReceiptCannotAcknowledgeInitialOrReplayedSeal() = runTest {
        for (replay in listOf(false, true)) for (changedPayload in listOf(false, true)) fixture { f ->
            val plan = if (replay) f.sealed() else f.selected()
            f.store.receipt = { if (changedPayload) SessionControlRecord(it.revision, SessionWorkCodec.encode(SessionWorkState.Idle))
                else SessionControlRecord(it.revision - 1, it.payload) }
            failure(FailureReason.STORAGE_FAILURE, f.registry.sealOrigin(plan))
            assertSealed(plan, f.store.record!!)
            val unknown = f.store.record!!
            f.store.receipt = { it }
            value(f.registry.sealOrigin(plan))
            assertEquals(unknown.revision + 1, f.store.record!!.revision)
            f.noEffects()
        }
    }

    @Test fun exactReadbackIsMandatoryAfterSealSuccessReceipt() = runTest {
        for (missing in listOf(false, true)) fixture { f ->
            val plan = f.selected(); val before = f.store.record!!
            f.store.afterCas = { if (missing) f.store.readFailure = FailureReason.STORAGE_FAILURE else f.store.record = before }
            failure(if (missing) FailureReason.STORAGE_FAILURE else FailureReason.OUTCOME_UNKNOWN, f.registry.sealOrigin(plan))
            f.store.afterCas = { }; f.store.readFailure = null
            value(f.registry.sealOrigin(plan))
            assertSealed(plan, f.store.record!!); f.noEffects()
        }
    }

    @Test fun activeOrNewlyActivatedBoundaryBlocksSealWithoutClearingTheNewIdentity() = runTest {
        for (point in listOf("before", "read", "verify", "cas")) fixture { f ->
            val plan = f.selected(); val writes = f.store.writes
            when (point) {
                "before" -> f.boundary.activate(SCOPE)
                "read" -> f.store.afterRead = { f.boundary.activate(SCOPE) }
                "verify" -> f.store.afterVerify = { f.boundary.activate(SCOPE) }
                else -> f.store.afterCas = { f.boundary.activate(SCOPE) }
            }
            failure(FailureReason.STALE_SESSION, f.registry.sealOrigin(plan))
            assertNotNull(f.boundary.current())
            assertEquals(writes + if (point == "cas") 1 else 0, f.store.writes)
            f.noEffects()
        }
    }

    @Test fun cancellationDuringReadAuthenticationCasOrReadbackPropagatesWithoutCleanup() = runTest {
        for (point in listOf("read", "verify", "cas", "readback")) fixture { f ->
            val plan = f.selected(); val writes = f.store.writes
            val cancelled: suspend () -> Unit = { throw CancellationException("private-fixture-cancel") }
            when (point) {
                "read" -> f.store.afterRead = cancelled
                "verify" -> f.store.afterVerify = cancelled
                "cas" -> f.store.afterCas = cancelled
                else -> f.store.afterCas = { f.store.afterRead = cancelled }
            }
            assertFailsWith<CancellationException> { f.registry.sealOrigin(plan) }
            assertEquals(writes + if (point in listOf("cas", "readback")) 1 else 0, f.store.writes)
            f.store.afterRead = { }; f.store.afterVerify = { }; f.store.afterCas = { }
            value(f.registry.sealOrigin(plan))
            assertSealed(plan, f.store.record!!); f.noEffects()
        }
    }

    @Test fun changedRevisionOrPayloadDuringProofVerificationPreventsSealCas() = runTest {
        for (payload in listOf(false, true)) fixture { f ->
            val plan = f.selected(); val writes = f.store.writes
            f.store.afterVerify = {
                val record = f.store.record!!
                f.store.record = if (payload) SessionControlRecord(record.revision, SessionWorkCodec.encode(SessionWorkState.Idle))
                    else SessionControlRecord(record.revision + 1, record.payload)
            }
            failure(FailureReason.CONFLICT, f.registry.sealOrigin(plan))
            assertEquals(writes, f.store.writes); f.noEffects()
        }
    }

    @Test fun foreignProofAndCompetingGenuinePlanNeverSealEqualLookingOrigins() = runTest {
        fixture { f ->
            val plan = f.plan(); val competitor = f.plan()
            value(f.registry.selectOrigin(plan))
            val before = f.store.record!!; val writes = f.store.writes
            failure(FailureReason.STALE_SESSION, f.registry.sealOrigin(competitor))
            val forged = SessionWorkOriginPlan.create(details(plan).copy(proof = PrivateBytes(ByteArray(64) { 127 })))
            failure(FailureReason.INVALID_DATA, f.registry.sealOrigin(forged))
            assertRecord(before, f.store.record!!); assertEquals(writes, f.store.writes)
            f.noEffects()
        }
    }

    @Test fun ordinaryLegacyUsedRetiringAndCompetingOriginsRejectSealEvenWithSameId() = runTest {
        for (variant in 0..3) fixture { f ->
            val plan = f.selected(); val origin = details(plan).origin
            val entries = if (variant == 2) listOf(entry()) else emptyList()
            val state = SessionWorkState.Origin(SCOPE, if (variant == 3) uuid(99) else origin, variant == 1, entries)
            f.store.record = SessionControlRecord(9, SessionWorkCodec.encode(state))
            val before = f.store.record!!
            failure(FailureReason.STALE_SESSION, f.registry.sealOrigin(plan))
            failure(FailureReason.STALE_SESSION, f.registry.inspectOrigin(plan))
            assertRecord(before, f.store.record!!); f.noEffects()
        }
    }

    @Test fun impossibleOrExhaustedSealedRevisionNeverWrapsOrAcknowledges() = runTest {
        fixture { f ->
            val plan = f.sealed(); val sealed = f.store.record!!
            f.store.record = SessionControlRecord(details(plan).expectedRevision + 1, sealed.payload)
            failure(FailureReason.STALE_SESSION, f.registry.sealOrigin(plan))
            for (revision in listOf(Long.MAX_VALUE - 1, Long.MAX_VALUE)) {
                f.store.record = SessionControlRecord(revision, sealed.payload)
                val before = f.store.record!!
                failure(FailureReason.STORAGE_FAILURE, f.registry.sealOrigin(plan))
                assertRecord(before, f.store.record!!)
            }
            f.noEffects()
        }
    }

    @Test fun missingAuthenticationCorruptMissingAndClosedStoresHaveNoSealFallback() = runTest {
        for (kind in listOf("plain", "corrupt", "missing", "closed")) fixture { f ->
            val plan = f.selected(); val writes = f.store.writes
            when (kind) {
                "plain" -> { value(f.registry.close()); f.registry = value(f.open(object : SessionControlStore by f.store {})) }
                "corrupt" -> f.store.record = SessionControlRecord(2, bytes("private-corrupt"))
                "missing" -> f.store.record = null
                else -> value(f.registry.close())
            }
            failure(if (kind == "plain") FailureReason.NOT_CONFIGURED else FailureReason.STORAGE_FAILURE, f.registry.sealOrigin(plan))
            assertEquals(writes, f.store.writes); f.noEffects()
        }
    }

    @Test fun successfulOrdinaryResumeConsumesProvenanceEvenWithoutAnyNativeWork() = runTest {
        fixture { f ->
            val plan = f.sealed(); val origin = details(plan).origin
            val lease = f.boundary.activate(SCOPE)
            val binding = value(f.registry.resume(lease))
            assertEquals(origin, binding.originBinding)
            assertNull(f.origin().setupPlan); assertTrue(f.origin().entries.isEmpty())
            f.boundary.clear()
            failure(FailureReason.STALE_SESSION, f.registry.sealOrigin(plan))
            failure(FailureReason.STALE_SESSION, f.registry.inspectOrigin(plan))
            assertEquals(0, f.effects)
        }
    }

    @Test fun deniedOrdinaryResumeKeepsProvenanceButUncertainCommittedResumeConsumesIt() = runTest {
        fixture { f ->
            val plan = f.sealed(); val before = f.store.record!!
            val lease = f.boundary.activate(SCOPE)
            f.admitted = false
            failure(FailureReason.STALE_SESSION, f.registry.resume(lease))
            assertRecord(before, f.store.record!!)
            f.admitted = true; f.store.afterFailure = FailureReason.OUTCOME_UNKNOWN
            failure(FailureReason.OUTCOME_UNKNOWN, f.registry.resume(lease))
            assertNull(f.origin().setupPlan)
            f.boundary.clear()
            failure(FailureReason.STALE_SESSION, f.registry.sealOrigin(plan))
            assertEquals(0, f.effects)
        }
    }

    @Test fun installThenCancelToEmptyCannotRestoreOldSealingEligibility() = runTest {
        fixture { f ->
            val plan = f.sealed()
            val lease = f.boundary.activate(SCOPE)
            // Internal fixture binding isolates the first reservation's provenance consumption.
            val binding = SessionWorkBinding(lease, details(plan).origin)
            val ticket = value(f.registry.install(binding, NativeWorkKind.TIMER, "timer") {
                assertNull(f.origin().setupPlan); f.effects++; PortResult.Value(Unit)
            })
            value(f.registry.cancel(binding, ticket))
            assertTrue(f.origin().entries.isEmpty()); assertNull(f.origin().setupPlan)
            f.boundary.clear()
            failure(FailureReason.STALE_SESSION, f.registry.sealOrigin(plan))
        }
    }

    @Test fun failedInstallerCompensationAlsoLeavesEmptyOriginPermanentlyUsed() = runTest {
        fixture { f ->
            val plan = f.sealed()
            val binding = SessionWorkBinding(f.boundary.activate(SCOPE), details(plan).origin)
            failure(FailureReason.UNAVAILABLE, f.registry.install(binding, NativeWorkKind.WORKER, "worker") {
                assertNull(f.origin().setupPlan); PortResult.Failure(FailureReason.UNAVAILABLE)
            })
            assertTrue(f.origin().entries.isEmpty()); assertNull(f.origin().setupPlan)
            f.boundary.clear()
            failure(FailureReason.STALE_SESSION, f.registry.sealOrigin(plan))
        }
    }

    @Test fun ordinaryEmptyCancelAndReconcileConsumeProvenanceWithoutNativeEffects() = runTest {
        for (cancel in listOf(false, true)) fixture { f ->
            val plan = f.sealed()
            val binding = SessionWorkBinding(f.boundary.activate(SCOPE), details(plan).origin)
            if (cancel) value(f.registry.cancel(binding, NativeWorkTicket(uuid(90), NativeWorkKind.TIMER)))
            else value(f.registry.reconcilePending(binding))
            assertNull(f.origin().setupPlan)
            f.boundary.clear()
            failure(FailureReason.STALE_SESSION, f.registry.sealOrigin(plan))
            assertEquals(0, f.effects)
        }
    }

    @Test fun retirementAndEmptyDiscardCannotReplaceExplicitAbortOfUntouchedSealedOrigin() = runTest {
        for (empty in listOf(false, true)) fixture { f ->
            val plan = f.sealed()
            val before = f.store.record!!
            if (empty) failure(FailureReason.CONFLICT, f.registry.retireEmpty(SCOPE, details(plan).origin))
            else failure(FailureReason.CONFLICT, f.registry.retire(SCOPE, details(plan).origin))
            assertEquals(SessionWorkOriginPlanStatus.SEALED, value(f.registry.inspectOrigin(plan)))
            assertRecord(before, f.store.record!!); assertEquals(0, f.effects)
        }
    }

    @Test fun sealedOriginCodecKeepsLegacyBytesUnchangedAndCanonicalPlanDetached() {
        val plan = structuralPlan()
        val origin = SessionWorkState.Origin(SCOPE, details(plan).origin, false, emptyList())
        val legacy = SessionWorkCodec.encode(origin).copyForCodec().decodeToString()
        assertEquals("""{"version":1,"state":"active","scope":{"environment":"work-seal-test","actorKind":"ACCOUNT","actorId":"private-seal-owner"},"origin":"${details(plan).origin}","entries":[]}""", legacy)
        assertNull(assertIs<SessionWorkState.Origin>(SessionWorkCodec.decode(bytes(legacy))).setupPlan)
        val sealed = SessionWorkState.Origin(SCOPE, origin.origin, false, emptyList(), plan)
        val encoded = SessionWorkCodec.encode(sealed)
        val decoded = assertIs<SessionWorkState.Origin>(SessionWorkCodec.decode(encoded))
        assertBytes(plan.copyForStorage(), assertNotNull(decoded.setupPlan).copyForStorage())
        encoded.copyForCodec().fill(0); decoded.setupPlan!!.copyForStorage().copyForCodec().fill(0)
        assertBytes(plan.copyForStorage(), decoded.setupPlan!!.copyForStorage())
        assertFalse(decoded.toString().contains(SCOPE.actorId))
    }

    @Test fun codecRejectsNullMalformedUnknownOrNonCanonicalProvenance() {
        val plan = structuralPlan()
        val raw = SessionWorkCodec.encode(SessionWorkState.Origin(SCOPE, details(plan).origin, false, emptyList(), plan)).copyForCodec().decodeToString()
        val root = Json.parseToJsonElement(raw).jsonObject
        for (value in listOf(JsonNull, JsonPrimitive(true), JsonPrimitive(7), JsonArray(emptyList()), JsonObject(emptyMap()),
            JsonPrimitive(""), JsonPrimitive("0"), JsonPrimitive("GG"), JsonPrimitive("00".repeat(4097)),
            JsonPrimitive(hex((" " + plan.copyForStorage().copyForCodec().decodeToString()).encodeToByteArray())))) {
            assertFailsWith<SessionWorkFormatException> { SessionWorkCodec.decode(bytes(JsonObject(root + ("setupPlan" to value)).toString())) }
        }
        assertFailsWith<SessionWorkFormatException> { SessionWorkCodec.decode(bytes(JsonObject(root + ("sealed" to JsonPrimitive(true))).toString())) }
        assertFailsWith<SessionWorkFormatException> { SessionWorkCodec.decode(bytes(raw.replace("\"setupPlan\":", "\"setupPlan\":\"00\",\"setupPlan\":"))) }
    }

    @Test fun provenanceCannotCoexistWithEntriesRetirementOrDifferentVisibleIdentity() {
        val plan = structuralPlan(); val origin = details(plan).origin
        for (state in listOf(SessionWorkState.Origin(SCOPE, origin, true, emptyList(), plan),
            SessionWorkState.Origin(SCOPE, origin, false, listOf(entry()), plan),
            SessionWorkState.Origin(SCOPE.copy(actorKind = ActorKind.GUEST), origin, false, emptyList(), plan),
            SessionWorkState.Origin(SCOPE.copy(environment = "other-environment"), origin, false, emptyList(), plan),
            SessionWorkState.Origin(SCOPE.copy(actorId = "other-owner"), origin, false, emptyList(), plan),
            SessionWorkState.Origin(SCOPE, uuid(99), false, emptyList(), plan)))
            assertFailsWith<SessionWorkFormatException> { SessionWorkCodec.encode(state) }
        val raw = SessionWorkCodec.encode(SessionWorkState.Origin(SCOPE, origin, false, emptyList(), plan)).copyForCodec().decodeToString()
        for (bad in listOf(raw.replace("\"active\"", "\"retiring\""), raw.replace(SCOPE.actorId, "other-owner"),
            raw.replace("\"origin\":\"$origin\"", "\"origin\":\"${uuid(99)}\"")))
            assertFailsWith<SessionWorkFormatException> { SessionWorkCodec.decode(bytes(bad)) }
    }

    @Test fun originTransformsPermanentlyDiscardProvenanceWithoutChangingOriginal() {
        val plan = structuralPlan()
        val original = SessionWorkState.Origin(SCOPE, details(plan).origin, false, emptyList(), plan)
        assertNull(original.used().setupPlan)
        assertNull(original.retiring().setupPlan)
        assertNull(original.withEntries(emptyList()).setupPlan)
        assertNull(original.withEntries(listOf(entry())).withEntries(emptyList()).setupPlan)
        assertNotNull(original.setupPlan); assertTrue(original.entries.isEmpty()); assertFalse(original.retiring)
        val legacy = original.used()
        assertSame(legacy, legacy.used())
    }

    @Test fun nonCanonicalOuterSealedStateIsReadableButNeverSealReplayAuthority() = runTest {
        fixture { f ->
            val plan = f.sealed(); val sealed = f.store.record!!
            f.store.record = SessionControlRecord(sealed.revision, bytes(" " + sealed.payload.copyForCodec().decodeToString()))
            val before = f.store.record!!; val writes = f.store.writes
            failure(FailureReason.STALE_SESSION, f.registry.inspectOrigin(plan))
            failure(FailureReason.STALE_SESSION, f.registry.sealOrigin(plan))
            assertEquals(writes, f.store.writes); assertRecord(before, f.store.record!!)
            f.noEffects()
        }
    }

    private suspend fun TestScope.fixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try { f.reopen(); block(f) } finally { value(f.registry.close()) }
    }

    private class Fixture(private val dispatcher: CoroutineDispatcher) {
        val store = ProofStore()
        val boundary = SessionBoundary()
        lateinit var registry: SessionWorkRegistry
        var ids = 0
        var policies = 0
        var effects = 0
        var admitted = true
        suspend fun open(control: SessionControlStore = store) = SessionWorkRegistry.open(control, boundary, dispatcher,
            NativeWorkCancellationPort { effects++; PortResult.Value(Unit) }, NativeWorkIdSource { uuid(++ids) },
            NativeWorkAdmissionPolicy { policies++; PortResult.Value(admitted) },
            NativeWorkExecutionPolicy { _, _, _, _ -> policies++; PortResult.Value(admitted) })
        suspend fun reopen() { if (::registry.isInitialized) value(registry.close()); registry = value(open()) }
        suspend fun plan() = value(registry.planOrigin(SCOPE, store.record!!.revision))
        suspend fun selected(): SessionWorkOriginPlan = plan().also { value(registry.selectOrigin(it)) }
        suspend fun sealed(): SessionWorkOriginPlan = selected().also { value(registry.sealOrigin(it)) }
        fun origin() = assertIs<SessionWorkState.Origin>(SessionWorkCodec.decode(store.record!!.payload))
        fun noEffects() { assertEquals(0, effects); assertEquals(0, policies) }
    }

    private class ProofStore : SessionControlStore, WorkOriginPlanAuthentication {
        var record: SessionControlRecord? = SessionControlRecord(1, SessionWorkCodec.encode(SessionWorkState.Idle))
        var writes = 0
        var signs = 0
        var beforeFailure: FailureReason? = null
        var afterFailure: FailureReason? = null
        var readFailure: FailureReason? = null
        var afterRead: suspend () -> Unit = { }
        var afterVerify: suspend () -> Unit = { }
        var afterCas: suspend () -> Unit = { }
        var receipt: (SessionControlRecord) -> SessionControlRecord = { it }
        private val issued = mutableListOf<Proof>()
        override suspend fun read(): PortResult<SessionControlRecord?> {
            val result = readFailure?.let { PortResult.Failure(it) } ?: PortResult.Value(record?.copy())
            afterRead(); return result
        }
        override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> {
            writes++
            beforeFailure?.let { beforeFailure = null; return PortResult.Failure(it) }
            val old = record ?: return PortResult.Failure(FailureReason.STORAGE_FAILURE)
            if (old.revision != expectedRevision) return PortResult.Failure(FailureReason.CONFLICT)
            if (old.revision == Long.MAX_VALUE) return PortResult.Failure(FailureReason.STORAGE_FAILURE)
            val next = SessionControlRecord(old.revision + 1, PrivateBytes(payload.copyForCodec()))
            record = next.copy(); afterCas()
            afterFailure?.let { afterFailure = null; return PortResult.Failure(it) }
            return PortResult.Value(receipt(next.copy()))
        }
        override suspend fun signOriginPlan(expected: SessionControlRecord, proposal: PrivateBytes): PortResult<PrivateBytes> {
            if (!sameRecord(expected, record)) return PortResult.Failure(FailureReason.CONFLICT)
            signs++
            val proof = PrivateBytes(ByteArray(64) { (it + signs * 13).toByte() })
            issued += Proof(expected.copy(), PrivateBytes(proposal.copyForCodec()), proof)
            return PortResult.Value(proof)
        }
        override suspend fun verifyOriginPlan(expectedRevision: Long, proposal: PrivateBytes, proof: PrivateBytes): PortResult<Unit> {
            val valid = issued.any { it.expected.revision == expectedRevision && sameBytes(it.proposal, proposal) && sameBytes(it.proof, proof) }
            afterVerify()
            return if (valid) PortResult.Value(Unit) else PortResult.Failure(FailureReason.INVALID_DATA)
        }
        override suspend fun verifyOriginPredecessor(expected: SessionControlRecord, proposal: PrivateBytes, proof: PrivateBytes): PortResult<Unit> =
            if (sameRecord(expected, record) && issued.any { sameRecord(it.expected, expected) && sameBytes(it.proposal, proposal) && sameBytes(it.proof, proof) })
                PortResult.Value(Unit) else PortResult.Failure(FailureReason.INVALID_DATA)
        private class Proof(val expected: SessionControlRecord, val proposal: PrivateBytes, val proof: PrivateBytes)
    }

    companion object {
        private val SCOPE = StorageScope("work-seal-test", ActorKind.ACCOUNT, "private-seal-owner")
        private fun uuid(index: Int) = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
        private fun entry() = SessionWorkEntry(uuid(80), NativeWorkKind.TIMER, "timer", NativeWorkPhase.INSTALLED)
        private fun structuralPlan() = SessionWorkOriginPlan.create(SessionWorkOriginPlanRecord(1, SCOPE, uuid(1), PrivateBytes(ByteArray(64))))
        private fun details(plan: SessionWorkOriginPlan) = SessionWorkOriginPlanCodec.decode(plan.copyForStorage())
        private fun bytes(text: String) = PrivateBytes(text.encodeToByteArray())
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        private fun SessionControlRecord.copy() = SessionControlRecord(revision, PrivateBytes(payload.copyForCodec()))
        private fun sameBytes(a: PrivateBytes, b: PrivateBytes) = a.copyForCodec().contentEquals(b.copyForCodec())
        private fun sameRecord(a: SessionControlRecord, b: SessionControlRecord?) = b != null && a.revision == b.revision && sameBytes(a.payload, b.payload)
        private fun assertBytes(a: PrivateBytes, b: PrivateBytes) = assertContentEquals(a.copyForCodec(), b.copyForCodec())
        private fun assertRecord(a: SessionControlRecord, b: SessionControlRecord) { assertEquals(a.revision, b.revision); assertBytes(a.payload, b.payload) }
        private fun assertSealed(plan: SessionWorkOriginPlan, record: SessionControlRecord) {
            val state = assertIs<SessionWorkState.Origin>(SessionWorkCodec.decode(record.payload))
            assertBytes(plan.copyForStorage(), assertNotNull(state.setupPlan).copyForStorage())
            assertFalse(state.retiring); assertTrue(state.entries.isEmpty())
        }
        private fun failure(reason: FailureReason, result: PortResult<*>) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
        private fun <T> value(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
    }
}
