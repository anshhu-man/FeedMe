package com.feedme.storage

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.feedme.core.ports.*
import com.feedme.session.*
import com.feedme.storage.AndroidWorkOriginPlanFixture.Companion.ORIGIN
import com.feedme.storage.AndroidWorkOriginPlanFixture.Companion.SCOPE
import com.feedme.storage.AndroidWorkOriginPlanFixture.Companion.TICKET
import com.feedme.storage.AndroidWorkOriginPlanFixture.Companion.bytes
import com.feedme.storage.AndroidWorkOriginPlanFixture.Companion.failure
import com.feedme.storage.AndroidWorkOriginPlanFixture.Companion.sameBytes
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exact native setup-work consumption only; no composite confirmation or OS effect authority. */
@RunWith(AndroidJUnit4::class)
class AndroidSessionWorkOriginAbortTest {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val fixtures = mutableListOf<AndroidWorkOriginPlanFixture>()
    private val extraRegistries = mutableListOf<SessionWorkRegistry>()

    @After fun cleanup() = runBlocking(dispatcher) {
        extraRegistries.asReversed().forEach { it.close().valueOrFail() }
        extraRegistries.clear()
        fixtures.asReversed().forEach { it.close() }
        fixtures.asReversed().forEach { it.destroy() }
        fixtures.clear()
    }

    @Test fun preparedAbortConsumesExactPlanWithoutIdsKeysGarbageCollectionOrLease() = runBlocking(dispatcher) {
        val f = fixture(); val plan = f.registry.planOrigin(SCOPE, 1).valueOrFail()
        val vault = AndroidStateVault.createOrOpen(f.box.keyPrefix, databaseExisted = true)
        val garbage = vault.createOwnerKey()
        sql(f, "INSERT INTO feedme_key_gc(key_id) VALUES('$garbage')")
        val aliases = f.aliases(); val before = f.record(); f.allowIds = false
        f.registry.abortOrigin(plan).valueOrFail()
        val after = f.record()
        assertEquals(before.revision + 1, after.revision)
        assertEquals(aborted(plan), after.payload.copyForCodec().decodeToString())
        assertEquals(SessionWorkOriginPlanStatus.ABORTED, f.registry.inspectOrigin(plan).valueOrFail())
        assertEquals(aliases, f.aliases()); assertTrue(vault.containsOwnerKey(garbage))
        assertEquals(1L, scalar(f, "SELECT count(*) FROM feedme_key_gc WHERE key_id='$garbage'"))
        val snapshotFiles = f.fileSnapshot()
        val snapshot = f.registry.snapshot().valueOrFail()
        assertEquals(after.revision, snapshot.revision)
        assertNull(snapshot.scope); assertNull(snapshot.originBinding)
        assertFalse(snapshot.retiring); assertTrue(snapshot.entries.isEmpty())
        assertEquals(after.revision, f.record().revision)
        assertTrue(sameBytes(after.payload, f.record().payload))
        assertEquals(snapshotFiles, f.fileSnapshot())
        assertEquals(1, f.allocatedIds); f.assertNoEffects()
    }

    @Test fun preparedSelectedAndUntouchedSealedAbortAlwaysRequireFreshChangedRevision() = runBlocking(dispatcher) {
        for (phase in listOf("prepared", "selected", "sealed")) {
            val f = fixture(); val plan = f.registry.planOrigin(SCOPE, 1).valueOrFail()
            if (phase != "prepared") f.registry.selectOrigin(plan).valueOrFail()
            if (phase == "sealed") f.registry.sealOrigin(plan).valueOrFail()
            val aliases = f.aliases(); f.allowIds = false
            repeat(3) {
                val before = f.record()
                f.registry.abortOrigin(plan).valueOrFail()
                assertEquals(before.revision + 1, f.record().revision)
                assertEquals(aborted(plan), f.record().payload.copyForCodec().decodeToString())
                assertEquals(SessionWorkOriginPlanStatus.ABORTED, f.registry.inspectOrigin(plan).valueOrFail())
            }
            failure(f.registry.selectOrigin(plan), FailureReason.CONFLICT)
            failure(f.registry.sealOrigin(plan), FailureReason.CONFLICT)
            assertEquals(aliases, f.aliases()); assertEquals(1, f.allocatedIds); f.assertNoEffects()
        }
    }

    @Test fun nativeAbortAndExactReplaySurviveCloseReopenWithoutReactivatingWork() = runBlocking(dispatcher) {
        val f = fixture(); val plan = f.registry.planOrigin(SCOPE, 1).valueOrFail()
        f.registry.selectOrigin(plan).valueOrFail(); f.registry.sealOrigin(plan).valueOrFail()
        f.registry.abortOrigin(plan).valueOrFail()
        val consumed = f.record(); val aliases = f.aliases(); f.allowIds = false
        val saved = SessionWorkOriginPlan.fromStorage(plan.copyForStorage()).valueOrFail()
        f.reopen()
        val files = f.fileSnapshot()
        repeat(3) { assertEquals(SessionWorkOriginPlanStatus.ABORTED, f.registry.inspectOrigin(saved).valueOrFail()) }
        assertEquals(files, f.fileSnapshot())
        f.registry.abortOrigin(saved).valueOrFail()
        assertEquals(consumed.revision + 1, f.record().revision)
        assertTrue(sameBytes(consumed.payload, f.record().payload))
        assertEquals(aliases, f.aliases()); assertEquals(1, f.allocatedIds); f.assertNoEffects()
    }

    @Test fun exactAbortedPredecessorCanPlanSuccessorAndItsSelectionFencesOldAbort() = runBlocking(dispatcher) {
        val f = fixture(); val original = f.registry.planOrigin(SCOPE, 1).valueOrFail()
        f.registry.abortOrigin(original).valueOrFail(); val consumed = f.record(); val aliases = f.aliases()
        f.closeRegistry(); val registry = successorRegistry(f)
        val next = registry.planOrigin(SCOPE, consumed.revision).valueOrFail()
        assertEquals(consumed.revision, f.record().revision)
        assertTrue(sameBytes(consumed.payload, f.record().payload))
        assertEquals(SessionWorkOriginPlanStatus.PREPARED, registry.inspectOrigin(next).valueOrFail())
        registry.selectOrigin(next).valueOrFail()
        val before = f.fileSnapshot()
        failure(registry.abortOrigin(original), FailureReason.STALE_SESSION)
        assertEquals(before, f.fileSnapshot())
        assertEquals(SessionWorkOriginPlanStatus.SELECTED, registry.inspectOrigin(next).valueOrFail())
        assertEquals(aliases, f.aliases()); f.assertNoEffects()
    }

    @Test fun successorPlanningIsNotReservationAndAnOldReacknowledgementInvalidatesItsPredecessor() = runBlocking(dispatcher) {
        val f = fixture(); val original = f.registry.planOrigin(SCOPE, 1).valueOrFail()
        f.registry.abortOrigin(original).valueOrFail()
        failure(f.registry.planOrigin(SCOPE, f.record().revision), FailureReason.CONFLICT) // ID source repeats old origin.
        f.closeRegistry(); val registry = successorRegistry(f)
        val next = registry.planOrigin(SCOPE, f.record().revision).valueOrFail()
        registry.abortOrigin(original).valueOrFail()
        val before = f.fileSnapshot()
        failure(registry.selectOrigin(next), FailureReason.STALE_SESSION)
        assertEquals(before, f.fileSnapshot())
        assertEquals(SessionWorkOriginPlanStatus.ABORTED, registry.inspectOrigin(original).valueOrFail())
        f.assertNoEffects()
    }

    @Test fun malformedOrForeignAuthenticatedPlanCannotConsumeAnotherNativeLedger() = runBlocking(dispatcher) {
        val f = fixture(); val plan = f.registry.planOrigin(SCOPE, 1).valueOrFail()
        val raw = plan.copyForStorage().copyForCodec().decodeToString()
        val proof = Regex("\\\"proof\\\":\\\"([0-9a-f]{128})\\\"").find(raw)!!.groupValues[1]
        val before = f.fileSnapshot(); val aliases = f.aliases()
        for (changed in listOf(raw.replace(SCOPE.actorId, "another-owner"), raw.replace(ORIGIN, TICKET),
            raw.replace("\"expectedRevision\":1", "\"expectedRevision\":2"),
            raw.replace(proof, (if (proof.first() == '0') "1" else "0") + proof.drop(1)))) {
            val forged = SessionWorkOriginPlan.fromStorage(bytes(changed)).valueOrFail()
            failure(f.registry.abortOrigin(forged), FailureReason.INVALID_DATA)
        }
        val foreign = fixture(); val foreignBefore = foreign.fileSnapshot(); val foreignAliases = foreign.aliases()
        failure(foreign.registry.abortOrigin(plan), FailureReason.INVALID_DATA)
        assertEquals(foreignBefore, foreign.fileSnapshot()); assertEquals(foreignAliases, foreign.aliases())
        assertEquals(before, f.fileSnapshot()); assertEquals(aliases, f.aliases()); f.assertNoEffects(); foreign.assertNoEffects()
    }

    @Test fun changedIdleOrdinaryAndRetiringOriginsNeverBecomeSetupAbortAuthority() = runBlocking(dispatcher) {
        val ordinary = listOf(origin(), origin(retiring = true)) + NativeWorkPhase.entries.map { origin(phase = it.name) }
        for (payload in listOf("{\"version\":1,\"state\":\"idle\"}") + ordinary) {
            val f = fixture(); val plan = f.registry.planOrigin(SCOPE, 1).valueOrFail()
            f.store.compareAndSet(1, bytes(payload)).valueOrFail()
            val before = f.fileSnapshot(); val aliases = f.aliases()
            failure(f.registry.abortOrigin(plan), FailureReason.STALE_SESSION)
            assertEquals(before, f.fileSnapshot()); assertEquals(aliases, f.aliases()); f.assertNoEffects()
        }
    }

    @Test fun abortedMarkerMustBeCanonicalAndBelongToThisExactAuthenticatedPlan() = runBlocking(dispatcher) {
        for (kind in listOf("foreign", "extra-field", "duplicate", "unknown")) {
            val f = fixture(); val plan = f.registry.planOrigin(SCOPE, 1).valueOrFail()
            val payload = when (kind) {
                "foreign" -> aborted(fixture().registry.planOrigin(SCOPE, 1).valueOrFail())
                "extra-field" -> aborted(plan).dropLast(1) + ",\"extra\":true}"
                "duplicate" -> "{\"version\":1,\"state\":\"idle\",\"state\":\"idle\"}"
                else -> "{\"version\":1,\"state\":\"unrecognized-state\"}"
            }
            f.store.compareAndSet(1, bytes(payload)).valueOrFail()
            val before = f.fileSnapshot()
            failure(f.registry.abortOrigin(plan), if (kind == "foreign") FailureReason.STALE_SESSION else FailureReason.STORAGE_FAILURE)
            assertEquals(before, f.fileSnapshot()); f.assertNoEffects()
        }
    }

    @Test fun activeBoundaryAndClosedRegistryCannotEraseOrReplaceTheirExistingAuthority() = runBlocking(dispatcher) {
        val f = fixture(); val plan = f.registry.planOrigin(SCOPE, 1).valueOrFail()
        val lease = f.boundary.activate(SCOPE); val before = f.fileSnapshot()
        failure(f.registry.abortOrigin(plan), FailureReason.STALE_SESSION)
        assertSame(lease, f.boundary.current()); assertEquals(before, f.fileSnapshot()); assertEquals(0, f.effects)
        f.boundary.clear(); f.closeRegistry()
        failure(f.registry.abortOrigin(plan), FailureReason.STORAGE_FAILURE)
        assertEquals(before, f.fileSnapshot()); f.assertNoEffects()
    }

    @Test fun missingNativeLedgerKeyFailsClosedWithoutRegenerationOrGarbageCollection() = runBlocking(dispatcher) {
        for (suffix in listOf(".index", ".owner.")) {
            val f = fixture(); val plan = f.registry.planOrigin(SCOPE, 1).valueOrFail()
            f.registry.selectOrigin(plan).valueOrFail()
            val key = f.aliases().single { if (suffix == ".index") it.endsWith(suffix) else it.contains(suffix) }
            f.box.keyStore().deleteEntry(key)
            val before = f.fileSnapshot(); val aliases = f.aliases()
            failure(f.registry.abortOrigin(plan), FailureReason.STORAGE_FAILURE)
            assertEquals(before, f.fileSnapshot()); assertEquals(aliases, f.aliases()); f.assertNoEffects()
        }
    }

    @Test fun cancellationAfterRealAbortCommitCannotReturnAcknowledgementAndRetryChangesRevision() = runBlocking(dispatcher) {
        val f = fixture(); val plan = f.registry.planOrigin(SCOPE, 1).valueOrFail(); f.allowIds = false
        f.reopen(traced = true)
        val pending = async(start = CoroutineStart.LAZY) { f.registry.abortOrigin(plan) }
        f.sql!!.afterCommit = { f.sql!!.afterCommit = null; pending.cancel() }
        pending.start(); assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
        val consumed = f.record()
        assertEquals(SessionWorkOriginPlanStatus.ABORTED, f.registry.inspectOrigin(plan).valueOrFail())
        f.registry.abortOrigin(plan).valueOrFail()
        assertEquals(consumed.revision + 1, f.record().revision)
        assertTrue(sameBytes(consumed.payload, f.record().payload)); f.assertNoEffects()
    }

    @Test fun lostSuccessfulAbortResponseDoesNotCreditVisibleMarkerUntilFreshChangedRetry() = runBlocking(dispatcher) {
        val f = fixture(); val plan = f.registry.planOrigin(SCOPE, 1).valueOrFail(); f.allowIds = false
        f.reopen(traced = true)
        f.sql!!.afterCommit = { f.sql!!.afterCommit = null; throw IOException("private-abort-response-loss") }
        failure(f.registry.abortOrigin(plan), FailureReason.STORAGE_FAILURE, FailureReason.OUTCOME_UNKNOWN)
        val consumed = f.record()
        assertEquals(SessionWorkOriginPlanStatus.ABORTED, f.registry.inspectOrigin(plan).valueOrFail())
        f.registry.abortOrigin(plan).valueOrFail()
        assertEquals(consumed.revision + 1, f.record().revision)
        assertTrue(sameBytes(consumed.payload, f.record().payload)); f.assertNoEffects()
    }

    private suspend fun fixture() = AndroidWorkOriginPlanFixture(dispatcher).also { fixtures += it; it.initialize() }
    private suspend fun successorRegistry(f: AndroidWorkOriginPlanFixture): SessionWorkRegistry = SessionWorkRegistry.open(
        f.store, f.boundary, dispatcher, NativeWorkCancellationPort { throw AssertionError("No OS cancellation") },
        NativeWorkIdSource { TICKET }, NativeWorkAdmissionPolicy { throw AssertionError("No admission") },
        NativeWorkExecutionPolicy { _, _, _, _ -> throw AssertionError("No execution") },
    ).valueOrFail().also(extraRegistries::add)
    private fun aborted(plan: SessionWorkOriginPlan): String {
        val hex = plan.copyForStorage().copyForCodec().joinToString("") { "%02x".format(it.toInt() and 255) }
        return "{\"version\":1,\"state\":\"setup-aborted\",\"plan\":\"$hex\"}"
    }
    private fun origin(retiring: Boolean = false, phase: String? = null): String {
        val entries = if (phase == null) "[]" else
            "[{\"id\":\"$TICKET\",\"kind\":\"TIMER\",\"logicalId\":\"test-only-native-timer\",\"phase\":\"$phase\"}]"
        return "{\"version\":1,\"state\":\"${if (retiring) "retiring" else "active"}\",\"scope\":{" +
            "\"environment\":\"${SCOPE.environment}\",\"actorKind\":\"ACCOUNT\",\"actorId\":\"${SCOPE.actorId}\"}," +
            "\"origin\":\"$ORIGIN\",\"entries\":$entries}"
    }
    private fun sql(f: AndroidWorkOriginPlanFixture, query: String) = BundledSQLiteDriver().open(f.file.path).use { db ->
        db.prepare(query).use { while (it.step()) { } }
    }
    private fun scalar(f: AndroidWorkOriginPlanFixture, query: String): Long = BundledSQLiteDriver().open(f.file.path).use { db ->
        db.prepare(query).use { assertTrue(it.step()); it.getLong(0) }
    }
}
