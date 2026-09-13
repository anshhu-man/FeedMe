package com.feedme.storage

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feedme.core.ports.*
import com.feedme.session.*
import com.feedme.storage.AndroidWorkOriginPlanFixture.Companion.SCOPE
import com.feedme.storage.AndroidWorkOriginPlanFixture.Companion.failure
import com.feedme.storage.AndroidWorkOriginPlanFixture.Companion.sameBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Separate instrumentation invocation: the exact owned connections explicitly select the test
 * forwarding VFS. Real bundled-engine VFS return-code injection, not OS errno or power loss.
 * Both first selection and selected replay require this invocation's acknowledged changed CAS.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSessionWorkOriginPlanSyncFailureTest {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val fixtures = mutableListOf<AndroidWorkOriginPlanFixture>()

    @After fun cleanup() = runBlocking(dispatcher) {
        SqliteSyncFailureInjector.restore()
        fixtures.asReversed().forEach { it.close() }
        SqliteSyncFailureInjector.unregisterVfs()
        fixtures.asReversed().forEach { it.destroy() }
        fixtures.clear()
    }

    @Test fun journalSyncFailureCannotAcknowledgeInitialOrReplayedWorkOriginSelection() = runBlocking(dispatcher) {
        verifySelectionAndReplay(SyncPoint.JOURNAL)
    }

    @Test fun databaseSyncFailureCannotAcknowledgeInitialOrReplayedWorkOriginSelection() = runBlocking(dispatcher) {
        verifySelectionAndReplay(SyncPoint.DATABASE)
    }

    @Test fun directorySyncFailureCannotPromoteVisibleWorkOriginSelectionOrReplayToAcknowledgement() = runBlocking(dispatcher) {
        verifySelectionAndReplay(SyncPoint.DIRECTORY)
    }

    private suspend fun verifySelectionAndReplay(point: SyncPoint) {
        val f = AndroidWorkOriginPlanFixture(dispatcher).also { fixtures += it; it.initialize() }
        val plan = f.registry.planOrigin(SCOPE, 1).valueOrFail()
        val originalPlan = plan.copyForStorage()
        val aliases = f.aliases()
        f.allowIds = false
        f.reopen(vfs = true)
        inject(f, plan, point, "select-${point.label}")
        assertEquals(aliases, f.aliases())
        f.reopen(vfs = true)
        val observed = f.registry.inspectOrigin(plan).valueOrFail()
        assertTrue(observed in setOf(SessionWorkOriginPlanStatus.PREPARED, SessionWorkOriginPlanStatus.SELECTED))
        if (point == SyncPoint.DIRECTORY) assertEquals(SessionWorkOriginPlanStatus.SELECTED, observed)
        val beforeFreshSelection = f.record().revision
        f.registry.selectOrigin(plan).valueOrFail()
        assertEquals(beforeFreshSelection + 1, f.record().revision)
        assertEquals(SessionWorkOriginPlanStatus.SELECTED, f.registry.inspectOrigin(plan).valueOrFail())
        val selectedPayload = f.record().payload

        inject(f, plan, point, "replay-${point.label}")
        assertEquals(aliases, f.aliases())
        f.reopen(vfs = true)
        assertEquals(SessionWorkOriginPlanStatus.SELECTED, f.registry.inspectOrigin(plan).valueOrFail())
        assertTrue(sameBytes(selectedPayload, f.record().payload))
        val beforeFreshReplay = f.record().revision
        f.registry.selectOrigin(plan).valueOrFail()
        assertEquals(beforeFreshReplay + 1, f.record().revision)
        assertTrue(sameBytes(selectedPayload, f.record().payload))
        assertTrue(sameBytes(originalPlan, plan.copyForStorage()))
        assertEquals(aliases, f.aliases())
        assertEquals(1, f.allocatedIds)
        failure(f.registry.snapshot(), FailureReason.CONFLICT)
        f.assertNoEffects()
    }

    private suspend fun inject(
        f: AndroidWorkOriginPlanFixture, plan: SessionWorkOriginPlan, point: SyncPoint, label: String,
    ) {
        val trace = f.sql ?: throw AssertionError("VFS-owned SQLite trace required")
        trace.nativeCodes.clear()
        SqliteSyncFailureInjector.install(f.file.path, point.kind, 1)
        val outcome: PortResult<Unit>
        val stats: LongArray
        try {
            outcome = f.registry.selectOrigin(plan)
            stats = SqliteSyncFailureInjector.statistics()
        } finally { SqliteSyncFailureInjector.restore() }
        failure(outcome, FailureReason.STORAGE_FAILURE, FailureReason.OUTCOME_UNKNOWN)
        assertEquals(7, stats.size)
        assertEquals("The actual VFS failure must execute once", 1L, stats[4])
        assertEquals(point.sqlite.toLong(), stats[5])
        assertTrue(stats[point.kind - 1] >= 1L)
        val unlinked = if (point == SyncPoint.DIRECTORY) 1L else 0L
        assertEquals(unlinked, stats[6])
        assertTrue("Bundled SQLite must surface the injected extended VFS code", trace.nativeCodes.contains(point.sqlite))
        f.assertNoEffects()
        for (privateValue in listOf(SCOPE.environment, SCOPE.actorId, f.file.path, f.box.keyPrefix, point.sqlite.toString())) {
            assertFalse(outcome.toString().contains(privateValue))
        }
        InstrumentationRegistry.getInstrumentation().addResults(Bundle().apply {
            putString("work_origin_sync_$label",
                "sqlite=${point.sqlite};failures=1;effects=0;vfs=1;delegatedUnlink=$unlinked")
        })
    }

    private enum class SyncPoint(val kind: Int, val sqlite: Int, val label: String) {
        JOURNAL(1, 1034, "journal"), DATABASE(2, 1034, "database"), DIRECTORY(4, 1290, "directory"),
    }
}
