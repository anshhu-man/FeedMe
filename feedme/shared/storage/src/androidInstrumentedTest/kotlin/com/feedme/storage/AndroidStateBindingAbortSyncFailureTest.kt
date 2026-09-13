package com.feedme.storage

import android.os.Bundle
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_FULLMUTEX
import androidx.sqlite.driver.bundled.SQLITE_OPEN_NOFOLLOW
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feedme.core.ports.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Separate instrumentation invocation. Actual bundled SQLite explicitly selects the existing
 * non-default test forwarding VFS; faults are VFS return codes, not OS errno or power loss.
 * Reopening uses the owned-connection seam and may let SQLite recover its rollback journal.
 * This does not establish public recovery-factory journal support or composite confirmation.
 */
@RunWith(AndroidJUnit4::class)
class AndroidStateBindingAbortSyncFailureTest {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val boxes = mutableListOf<AndroidStateTestSandbox>()
    private val databases = mutableListOf<EncryptedStateDatabase>()

    @After fun cleanup() = runBlocking(dispatcher) {
        SqliteSyncFailureInjector.restore()
        databases.asReversed().forEach { it.close().valueOrFail() }
        databases.clear()
        SqliteSyncFailureInjector.unregisterVfs()
        boxes.asReversed().forEach { it.close() }
        boxes.clear()
    }

    @Test fun journalSyncFailureCannotDeleteKeyDuringInitialOrReplayedBindingAbort() = runBlocking(dispatcher) {
        bindingAndReplay(SyncPoint.JOURNAL)
    }

    @Test fun databaseSyncFailureCannotDeleteKeyDuringInitialOrReplayedBindingAbort() = runBlocking(dispatcher) {
        bindingAndReplay(SyncPoint.DATABASE)
    }

    @Test fun directorySyncFailureCannotPromoteVisibleBindingRemovalToKeyDeletion() = runBlocking(dispatcher) {
        bindingAndReplay(SyncPoint.DIRECTORY)
    }

    private suspend fun bindingAndReplay(point: SyncPoint) {
        var f = fixture()
        val originalPlan = f.plan.copyForStorage()
        val beforeAliases = aliases(f.box)
        val key = StateActivationPlanCodec.decode(f.plan).keyId
        inject(f, point, "initial-${point.label}")
        assertEquals(beforeAliases, aliases(f.box))
        f = reopen(f)
        // Deliberately leave the acknowledged consumed receipt and exact key behind, so replay
        // exercises the key-deletion gate with a real key, not only an already-absent alias.
        f.vault.failDelete = true
        val consumed = f.database.abortPlannedActivation(OWNER, f.plan, BODY)
        assertTrue(consumed is PortResult.Failure)
        assertEquals(FailureReason.STORAGE_FAILURE, (consumed as PortResult.Failure).reason)
        assertEquals(beforeAliases, aliases(f.box))
        f = reopen(f)
        assertEquals(StateActivationStatus.ABORTING, f.database.inspectPlannedActivation(OWNER, f.plan).valueOrFail().status)
        inject(f, point, "replay-${point.label}")
        assertEquals(beforeAliases, aliases(f.box))
        f = reopen(f)
        val revision = scalar(f, "SELECT revision FROM feedme_activation_aborts")
        f.database.abortPlannedActivation(OWNER, f.plan, BODY).valueOrFail()
        assertEquals(revision + 1, scalar(f, "SELECT revision FROM feedme_activation_aborts"))
        assertEquals(0L, scalar(f, "SELECT count(*) FROM feedme_records"))
        assertEquals(StateActivationStatus.ABORTED, f.database.inspectPlannedActivation(OWNER, f.plan).valueOrFail().status)
        assertFalse(aliases(f.box).contains("${f.box.keyPrefix}.owner.$key"))
        assertEquals(0, f.vault.allocated); assertEquals(0, f.vault.created)
        assertArrayEquals(originalPlan, f.plan.copyForStorage())
        // No runtime/control journal, confirmation, scoped handle or ordinary activation here.
    }

    private fun scalar(f: Fixture, query: String): Long =
        f.sql.prepare(query).use { assertTrue(it.step()); it.getLong(0) }

    private suspend fun inject(f: Fixture, point: SyncPoint, label: String) {
        f.sql.nativeCodes.clear()
        SqliteSyncFailureInjector.install(f.file.path, point.kind, 1)
        val outcome: PortResult<Unit>
        val stats: LongArray
        try {
            outcome = f.database.abortPlannedActivation(OWNER, f.plan, BODY)
            stats = SqliteSyncFailureInjector.statistics()
        } finally { SqliteSyncFailureInjector.restore() }
        assertTrue("Failed native synchronization cannot authorize exact key deletion", outcome is PortResult.Failure)
        val failure = outcome as PortResult.Failure
        assertTrue(failure.reason in setOf(FailureReason.STORAGE_FAILURE, FailureReason.OUTCOME_UNKNOWN))
        assertNull(failure.retryAfterSeconds)
        assertEquals(7, stats.size); assertEquals(1L, stats[4]); assertEquals(point.sqlite.toLong(), stats[5])
        assertTrue(stats[point.kind - 1] >= 1L)
        val unlinked = if (point == SyncPoint.DIRECTORY) 1L else 0L
        assertEquals(unlinked, stats[6])
        assertTrue("Bundled SQLite must expose the actual injected extended code", point.sqlite in f.sql.nativeCodes)
        assertNoKeyEffects(f)
        for (privateValue in listOf(OWNER.environment, OWNER.actorId, f.file.path, f.box.keyPrefix,
            StateActivationPlanCodec.decode(f.plan).keyId, point.sqlite.toString())) {
            assertFalse(failure.toString().contains(privateValue))
        }
        InstrumentationRegistry.getInstrumentation().addResults(Bundle().apply {
            putString("data_binding_abort_sync_$label",
                "sqlite=${point.sqlite};failures=1;keyDeletes=0;vfs=1;delegatedUnlink=$unlinked")
        })
    }

    private suspend fun fixture(): Fixture {
        val box = AndroidStateTestSandbox().also(boxes::add)
        val initial = AndroidStateDatabase.openForTests(box.directory, box.keyPrefix).valueOrFail()
        val plan: StateActivationPlan
        try {
            plan = initial.planActivation(OWNER).valueOrFail()
            initial.commitPlannedActivation(OWNER, plan).valueOrFail()
            initial.bindPlannedActivation(OWNER, plan, 2, BODY).valueOrFail()
        } finally { initial.close().valueOrFail() }
        return open(box, plan)
    }

    private suspend fun reopen(f: Fixture): Fixture {
        f.database.close().valueOrFail(); databases.remove(f.database)
        return open(f.box, f.plan)
    }

    private suspend fun open(box: AndroidStateTestSandbox, plan: StateActivationPlan): Fixture {
        val file = File(box.directory, "state.sqlite").canonicalFile
        val vault = NativeTrace(AndroidStateVault.createOrOpen(box.keyPrefix, databaseExisted = true))
        SqliteSyncFailureInjector.registerVfs()
        val sql = SqlTrace(BundledSQLiteDriver().open("${file.toURI()}?vfs=feedme-test-sync-failure-v1",
            SQLITE_OPEN_READWRITE or SQLITE_OPEN_FULLMUTEX or SQLITE_OPEN_NOFOLLOW or 0x00000040))
        val database = EncryptedStateDatabase.open(sql, vault, dispatcher).valueOrFail().also(databases::add)
        return Fixture(box, plan, file, vault, sql, database)
    }

    private fun assertNoKeyEffects(f: Fixture) {
        assertEquals(0, f.vault.allocated); assertEquals(0, f.vault.created); assertEquals(0, f.vault.deleted)
    }
    private fun aliases(box: AndroidStateTestSandbox) = box.keyStore().aliases().toList()
        .filter { it.startsWith("${box.keyPrefix}.") }.toSet()
    private class Fixture(val box: AndroidStateTestSandbox, val plan: StateActivationPlan, val file: File,
        val vault: NativeTrace, val sql: SqlTrace, val database: EncryptedStateDatabase)
    private class NativeTrace(private val native: AndroidStateVault) : PlannedStateVault by native {
        var allocated = 0; var created = 0; var deleted = 0; var failDelete = false
        override fun newOwnerKeyId(): String { allocated++; return native.newOwnerKeyId() }
        override fun createOwnerKey(): String { created++; return native.createOwnerKey() }
        override fun createOwnerKey(keyId: String) { created++; native.createOwnerKey(keyId) }
        override fun deleteOwnerKey(keyId: String) { deleted++; if (failDelete) throw StateVaultException(); native.deleteOwnerKey(keyId) }
    }
    private class SqlTrace(private val native: SQLiteConnection) : SQLiteConnection by native {
        val nativeCodes = mutableListOf<Int>()
        override fun prepare(sql: String): SQLiteStatement {
            val statement = native.prepare(sql)
            return object : SQLiteStatement by statement {
                override fun step(): Boolean = try { statement.step() } catch (failure: Exception) {
                    Regex("Error code: ([0-9]+)").find(failure.message.orEmpty())?.groupValues?.get(1)
                        ?.toIntOrNull()?.let(nativeCodes::add)
                    throw failure
                }
            }
        }
    }
    private enum class SyncPoint(val kind: Int, val sqlite: Int, val label: String) {
        JOURNAL(1, 1034, "journal"), DATABASE(2, 1034, "database"), DIRECTORY(4, 1290, "directory"),
    }
    companion object {
        private val OWNER = StorageScope("native-binding-abort-sync-fixture", ActorKind.ACCOUNT, "private-binding-owner")
        private val BODY = PrivateBytes("canonical-binding-metadata-fixture".encodeToByteArray())
    }
}
