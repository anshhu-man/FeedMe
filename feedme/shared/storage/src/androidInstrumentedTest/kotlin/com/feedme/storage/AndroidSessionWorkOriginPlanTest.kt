package com.feedme.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.feedme.core.ports.*
import com.feedme.session.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import com.feedme.storage.AndroidWorkOriginPlanFixture.Companion.ORIGIN
import com.feedme.storage.AndroidWorkOriginPlanFixture.Companion.SCOPE
import com.feedme.storage.AndroidWorkOriginPlanFixture.Companion.TICKET
import com.feedme.storage.AndroidWorkOriginPlanFixture.Companion.bytes
import com.feedme.storage.AndroidWorkOriginPlanFixture.Companion.failure
import com.feedme.storage.AndroidWorkOriginPlanFixture.Companion.sameBytes

/** Real Android Keystore/private SQLite; setup-only plans never authenticate or schedule work. */
@RunWith(AndroidJUnit4::class)
class AndroidSessionWorkOriginPlanTest {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val fixtures = mutableListOf<AndroidWorkOriginPlanFixture>()

    @After fun cleanup() = runBlocking(dispatcher) {
        // A failed close retains the owned fixture rather than deleting under a live manager.
        fixtures.asReversed().forEach { it.close() }
        fixtures.asReversed().forEach { it.destroy() }
        fixtures.clear()
    }

    @Test fun planningAndInspectionPreserveExactNativeFilesRowsKeysAndInactiveBoundary() = runBlocking(dispatcher) {
        val f = fixture()
        val before = f.record()
        val files = f.fileSnapshot()
        val rows = f.rowSnapshot()
        val aliases = f.aliases()
        val plan = f.registry.planOrigin(SCOPE, before.revision).valueOrFail()
        repeat(3) { assertEquals(SessionWorkOriginPlanStatus.PREPARED, f.registry.inspectOrigin(plan).valueOrFail()) }
        assertEquals(before.revision, f.record().revision)
        assertTrue(sameBytes(before.payload, f.record().payload))
        assertEquals(files, f.fileSnapshot())
        assertEquals(rows, f.rowSnapshot())
        assertEquals(aliases, f.aliases())
        assertEquals(1, f.allocatedIds)
        f.assertNoEffects()
    }

    @Test fun serializedPlanIsDetachedRedactedAndCanSelectWithoutAllocatingAnotherIdentity() = runBlocking(dispatcher) {
        val f = fixture()
        val plan = f.registry.planOrigin(SCOPE, 1).valueOrFail()
        val saved = plan.copyForStorage().copyForCodec()
        val reopened = SessionWorkOriginPlan.fromStorage(PrivateBytes(saved)).valueOrFail()
        saved.fill(0)
        assertTrue(sameBytes(plan.copyForStorage(), reopened.copyForStorage()))
        for (privateValue in listOf(SCOPE.environment, SCOPE.actorId, ORIGIN, f.box.keyPrefix, f.file.path)) {
            assertFalse(plan.toString().contains(privateValue))
        }
        f.allowIds = false
        f.registry.selectOrigin(reopened).valueOrFail()
        assertEquals(SessionWorkOriginPlanStatus.SELECTED, f.registry.inspectOrigin(plan).valueOrFail())
        assertEquals(1, f.allocatedIds)
        f.assertNoEffects()
    }

    @Test fun selectedReplayChangesRevisionWithIdenticalPayloadAndNoOrdinarySnapshotAuthority() = runBlocking(dispatcher) {
        val f = fixture()
        val plan = f.registry.planOrigin(SCOPE, 1).valueOrFail()
        f.allowIds = false
        f.registry.selectOrigin(plan).valueOrFail()
        val selected = f.record()
        assertEquals(2L, selected.revision)
        failure(f.registry.snapshot(), FailureReason.CONFLICT)
        repeat(3) { index ->
            val files = f.fileSnapshot()
            assertEquals(SessionWorkOriginPlanStatus.SELECTED, f.registry.inspectOrigin(plan).valueOrFail())
            assertEquals(files, f.fileSnapshot())
            f.registry.selectOrigin(plan).valueOrFail()
            assertEquals(3L + index, f.record().revision)
            assertTrue(sameBytes(selected.payload, f.record().payload))
        }
        f.assertNoEffects()
    }

    @Test fun preparedAndSelectedPlansSurviveNativeCloseReopenWithoutNewKeysOrLease() = runBlocking(dispatcher) {
        val f = fixture()
        val plan = f.registry.planOrigin(SCOPE, 1).valueOrFail()
        val persisted = plan.copyForStorage()
        val aliases = f.aliases()
        f.allowIds = false
        f.reopen()
        val recovered = SessionWorkOriginPlan.fromStorage(persisted).valueOrFail()
        assertEquals(SessionWorkOriginPlanStatus.PREPARED, f.registry.inspectOrigin(recovered).valueOrFail())
        f.registry.selectOrigin(recovered).valueOrFail()
        val selected = f.record()
        f.reopen()
        assertEquals(SessionWorkOriginPlanStatus.SELECTED, f.registry.inspectOrigin(recovered).valueOrFail())
        f.registry.selectOrigin(recovered).valueOrFail()
        assertEquals(selected.revision + 1, f.record().revision)
        assertTrue(sameBytes(selected.payload, f.record().payload))
        assertEquals(aliases, f.aliases())
        assertEquals(1, f.allocatedIds)
        f.assertNoEffects()
    }

    @Test fun equalRevisionIdenticalIdleInAnotherNativeStoreCannotAcceptForeignPlan() = runBlocking(dispatcher) {
        val first = fixture()
        val second = fixture()
        assertEquals(first.record().revision, second.record().revision)
        assertTrue(sameBytes(first.record().payload, second.record().payload))
        val plan = first.registry.planOrigin(SCOPE, 1).valueOrFail()
        val files = second.fileSnapshot()
        val aliases = second.aliases()
        failure(second.registry.inspectOrigin(plan), FailureReason.INVALID_DATA)
        failure(second.registry.selectOrigin(plan), FailureReason.INVALID_DATA)
        assertEquals(files, second.fileSnapshot())
        assertEquals(aliases, second.aliases())
        assertEquals(0, second.allocatedIds)
        assertEquals(SessionWorkOriginPlanStatus.PREPARED, first.registry.inspectOrigin(plan).valueOrFail())
        first.assertNoEffects(); second.assertNoEffects()
    }

    @Test fun alteredValidPlanScopeOriginRevisionAndProofAreRejectedWithoutNativeWrites() = runBlocking(dispatcher) {
        val f = fixture()
        val plan = f.registry.planOrigin(SCOPE, 1).valueOrFail()
        val raw = plan.copyForStorage().copyForCodec().decodeToString()
        val proof = Regex("\\\"proof\\\":\\\"([0-9a-f]{128})\\\"").find(raw)!!.groupValues[1]
        val altered = listOf(
            raw.replace(SCOPE.actorId, "another-test-owner"),
            raw.replace(ORIGIN, TICKET),
            raw.replace("\"expectedRevision\":1", "\"expectedRevision\":2"),
            raw.replace(proof, (if (proof[0] == '0') "1" else "0") + proof.drop(1)),
        )
        val files = f.fileSnapshot()
        val rows = f.rowSnapshot()
        val aliases = f.aliases()
        for (wire in altered) {
            assertNotEquals(raw, wire)
            val forged = SessionWorkOriginPlan.fromStorage(bytes(wire)).valueOrFail()
            failure(f.registry.inspectOrigin(forged), FailureReason.INVALID_DATA)
            failure(f.registry.selectOrigin(forged), FailureReason.INVALID_DATA)
        }
        assertEquals(files, f.fileSnapshot())
        assertEquals(rows, f.rowSnapshot())
        assertEquals(aliases, f.aliases())
        f.assertNoEffects()
    }

    @Test fun changedIdleRevisionCannotBeSelectedByAnOlderPlanOrSilentlyReplanned() = runBlocking(dispatcher) {
        val f = fixture()
        val plan = f.registry.planOrigin(SCOPE, 1).valueOrFail()
        f.store.compareAndSet(1, f.record().payload).valueOrFail()
        val files = f.fileSnapshot()
        failure(f.registry.inspectOrigin(plan), FailureReason.STALE_SESSION)
        failure(f.registry.selectOrigin(plan), FailureReason.STALE_SESSION)
        failure(f.registry.planOrigin(SCOPE, 1), FailureReason.CONFLICT)
        assertEquals(files, f.fileSnapshot())
        assertEquals(1, f.allocatedIds)
        f.assertNoEffects()
    }

    @Test fun ordinaryEmptyRetiringAndEveryNativeEntryPhaseRejectSetupReplayEvenWithSameOrigin() = runBlocking(dispatcher) {
        val variants = listOf(origin(), origin(retiring = true)) + NativeWorkPhase.entries.map { origin(phase = it.name) }
        for (payload in variants) {
            val f = fixture()
            val plan = f.registry.planOrigin(SCOPE, 1).valueOrFail()
            f.registry.selectOrigin(plan).valueOrFail()
            f.store.compareAndSet(f.record().revision, bytes(payload)).valueOrFail()
            val files = f.fileSnapshot()
            val rows = f.rowSnapshot()
            failure(f.registry.inspectOrigin(plan), FailureReason.STALE_SESSION)
            failure(f.registry.selectOrigin(plan), FailureReason.STALE_SESSION)
            assertEquals(files, f.fileSnapshot())
            assertEquals(rows, f.rowSnapshot())
            f.assertNoEffects()
        }
    }

    @Test fun selectedMarkerCannotBorrowAnotherPlanOrAcceptUnknownAndMalformedStoredMetadata() = runBlocking(dispatcher) {
        for (kind in listOf("other-plan", "unknown", "duplicate")) {
            val f = fixture()
            val plan = f.registry.planOrigin(SCOPE, 1).valueOrFail()
            f.registry.selectOrigin(plan).valueOrFail()
            val payload = when (kind) {
                "other-plan" -> {
                    val other = fixture().registry.planOrigin(SCOPE, 1).valueOrFail()
                    val hex = other.copyForStorage().copyForCodec().joinToString("") { "%02x".format(it.toInt() and 255) }
                    "{\"version\":1,\"state\":\"setup-selected\",\"plan\":\"$hex\"}"
                }
                "unknown" -> "{\"version\":1,\"state\":\"unknown-private-state\"}"
                else -> "{\"version\":1,\"state\":\"idle\",\"state\":\"idle\"}"
            }
            f.store.compareAndSet(f.record().revision, bytes(payload)).valueOrFail()
            val files = f.fileSnapshot()
            val expected = if (kind == "other-plan") FailureReason.STALE_SESSION else FailureReason.STORAGE_FAILURE
            failure(f.registry.inspectOrigin(plan), expected)
            failure(f.registry.selectOrigin(plan), expected)
            assertEquals(files, f.fileSnapshot())
            f.assertNoEffects()
        }
    }

    @Test fun liveBoundaryBlocksSetupMethodsWithoutClearingOrReplacingItsExistingLease() = runBlocking(dispatcher) {
        val f = fixture()
        val plan = f.registry.planOrigin(SCOPE, 1).valueOrFail()
        val lease = f.boundary.activate(StorageScope("other-native-test", ActorKind.GUEST, "other-test-owner"))
        val files = f.fileSnapshot()
        failure(f.registry.planOrigin(SCOPE, 1), FailureReason.STALE_SESSION)
        failure(f.registry.inspectOrigin(plan), FailureReason.STALE_SESSION)
        failure(f.registry.selectOrigin(plan), FailureReason.STALE_SESSION)
        assertSame(lease, f.boundary.current())
        assertEquals(files, f.fileSnapshot())
        assertEquals(0, f.effects)
        f.boundary.clear()
    }

    @Test fun missingSelectedNativeOwnerKeyFailsClosedWithoutRegenerationOrChangedFiles() = runBlocking(dispatcher) {
        val f = fixture()
        val plan = f.registry.planOrigin(SCOPE, 1).valueOrFail()
        f.registry.selectOrigin(plan).valueOrFail()
        val key = f.aliases().single { it.startsWith("${f.box.keyPrefix}.owner.") }
        f.box.keyStore().deleteEntry(key)
        val remaining = f.aliases()
        val files = f.fileSnapshot()
        failure(f.registry.inspectOrigin(plan), FailureReason.STORAGE_FAILURE)
        failure(f.registry.selectOrigin(plan), FailureReason.STORAGE_FAILURE)
        assertEquals(remaining, f.aliases())
        assertEquals(files, f.fileSnapshot())
        f.assertNoEffects()
    }

    @Test fun cancellationAfterRealSelectionCommitNeverReturnsAccessAndExactRetryAcknowledgesAgain() = runBlocking(dispatcher) {
        val f = fixture()
        val plan = f.registry.planOrigin(SCOPE, 1).valueOrFail()
        f.allowIds = false
        f.reopen(traced = true)
        val attempt = async(start = CoroutineStart.LAZY) { f.registry.selectOrigin(plan) }
        f.sql!!.afterCommit = { f.sql!!.afterCommit = null; attempt.cancel() }
        attempt.start()
        val thrown = runCatching { attempt.await() }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        val selected = f.record()
        assertEquals(2L, selected.revision)
        assertEquals(SessionWorkOriginPlanStatus.SELECTED, f.registry.inspectOrigin(plan).valueOrFail())
        f.registry.selectOrigin(plan).valueOrFail()
        assertEquals(3L, f.record().revision)
        assertTrue(sameBytes(selected.payload, f.record().payload))
        f.assertNoEffects()
    }

    @Test fun rejectedOrdinaryRetirementCannotEraseOrPoisonExactSetupSelection() = runBlocking(dispatcher) {
        val f = fixture()
        val plan = f.registry.planOrigin(SCOPE, 1).valueOrFail()
        f.registry.selectOrigin(plan).valueOrFail()
        val files = f.fileSnapshot()
        failure(f.registry.retireEmpty(SCOPE, ORIGIN), FailureReason.CONFLICT)
        failure(f.registry.retire(SCOPE, ORIGIN), FailureReason.CONFLICT)
        assertEquals(files, f.fileSnapshot())
        assertEquals(SessionWorkOriginPlanStatus.SELECTED, f.registry.inspectOrigin(plan).valueOrFail())
        f.registry.selectOrigin(plan).valueOrFail()
        f.assertNoEffects()
    }

    @Test fun closedRegistryAndInvalidPlanningArgumentsCannotMutateNativeEvidence() = runBlocking(dispatcher) {
        val f = fixture()
        val before = f.fileSnapshot()
        failure(f.registry.planOrigin(SCOPE, 0), FailureReason.INVALID_DATA)
        failure(f.registry.planOrigin(SCOPE, Long.MAX_VALUE), FailureReason.STORAGE_FAILURE)
        failure(f.registry.planOrigin(StorageScope("native-demo", ActorKind.DEMO, "demo"), 1), FailureReason.INVALID_DATA)
        failure(f.registry.planOrigin(StorageScope("bad\uD800", ActorKind.ACCOUNT, "actor"), 1), FailureReason.INVALID_DATA)
        assertEquals(0, f.allocatedIds)
        assertEquals(before, f.fileSnapshot())
        val plan = f.registry.planOrigin(SCOPE, 1).valueOrFail()
        f.closeRegistry()
        failure(f.registry.planOrigin(SCOPE, 1), FailureReason.STORAGE_FAILURE)
        failure(f.registry.inspectOrigin(plan), FailureReason.STORAGE_FAILURE)
        failure(f.registry.selectOrigin(plan), FailureReason.STORAGE_FAILURE)
        assertEquals(before, f.fileSnapshot())
        f.assertNoEffects()
    }

    private suspend fun fixture() = AndroidWorkOriginPlanFixture(dispatcher).also { fixtures += it; it.initialize() }

    private fun origin(retiring: Boolean = false, phase: String? = null): String {
        val entries = if (phase == null) "[]" else
            "[{\"id\":\"$TICKET\",\"kind\":\"TIMER\",\"logicalId\":\"test-only-native-timer\",\"phase\":\"$phase\"}]"
        return "{\"version\":1,\"state\":\"${if (retiring) "retiring" else "active"}\",\"scope\":{" +
            "\"environment\":\"${SCOPE.environment}\",\"actorKind\":\"ACCOUNT\",\"actorId\":\"${SCOPE.actorId}\"}," +
            "\"origin\":\"$ORIGIN\",\"entries\":$entries}"
    }
}
