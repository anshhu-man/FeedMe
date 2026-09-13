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
import com.feedme.core.ports.ActorKind
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.StorageScope
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Run this class in its own instrumentation invocation: the bundled VFS syscall table is global.
 * The C helper fails the actual engine syscall for this exact owned fd only. No synthetic SQLite
 * build, post-COMMIT Kotlin exception, provider/UI claim or physical power-loss claim is involved.
 * Reopen here deliberately uses the internal owned connection seam: SQLite may recover its own
 * rollback journal. This is NOT evidence that the strict Android recovery factory accepts journals.
 */
@RunWith(AndroidJUnit4::class)
class AndroidStateActivationSyncFailureTest {
    private val sandboxes = mutableListOf<AndroidStateTestSandbox>()
    private val handles = mutableListOf<StateActivationRecoveryHandle>()

    @After fun cleanup() = runBlocking {
        // Always restore the process-global table before close/recovery/owned-fixture deletion.
        SqliteSyncFailureInjector.restore()
        handles.asReversed().forEach { it.close().valueOrFail() }
        handles.clear()
        sandboxes.asReversed().forEach { it.close() }
        sandboxes.clear()
    }

    @Test fun realJournalSyncEioRetainsExactKeyUntilFreshSuccessfulAbort() = runBlocking {
        failFirstAbort(SyncPoint.JOURNAL, "journal")
    }

    @Test fun realDatabaseSyncEioRetainsExactKeyUntilFreshSuccessfulAbort() = runBlocking {
        failFirstAbort(SyncPoint.DATABASE, "database")
    }

    @Test fun realPostUnlinkDirectorySyncEioNeverTreatsVisibleConsumeAsDurableAcknowledgement() = runBlocking {
        failFirstAbort(SyncPoint.DIRECTORY_AFTER_UNLINK, "directory")
    }

    @Test fun consumedReceiptReplayStillRequiresFreshSuccessfulSyncBeforeDeletingKey() = runBlocking {
        val f = fixture()
        f.vault.failNextDelete = true
        failure(f.handle.abort(), FailureReason.STORAGE_FAILURE)
        assertEquals(StateActivationStatus.ABORTING, f.handle.inspect().valueOrFail().status)
        assertEquals(1L, f.sql.receiptRevision())
        assertTrue(f.vault.containsOwnerKey(f.key))
        f.vault.deleted.clear()
        injectFailure(f, SyncPoint.DIRECTORY_AFTER_UNLINK, "consumed-replay")
        val reopened = reopen(f)
        assertTrue(reopened.vault.containsOwnerKey(f.key))
        assertEquals(StateActivationStatus.ABORTING, reopened.handle.inspect().valueOrFail().status)
        val previous = reopened.sql.receiptRevision()
        assertTrue(previous >= 1L)
        reopened.handle.abort().valueOrFail()
        assertEquals(previous + 1, reopened.sql.receiptRevision())
        assertEquals(listOf(f.key), reopened.vault.deleted)
        assertFalse(reopened.vault.containsOwnerKey(f.key))
    }

    @Test fun observedAbortedReplayCannotAcknowledgeWhenItsNewJournalSyncFails() = runBlocking {
        val f = fixture()
        f.handle.abort().valueOrFail()
        assertEquals(StateActivationStatus.ABORTED, f.handle.inspect().valueOrFail().status)
        assertEquals(1L, f.sql.receiptRevision())
        assertFalse(f.vault.containsOwnerKey(f.key))
        f.vault.deleted.clear()
        injectFailure(f, SyncPoint.JOURNAL, "aborted-replay")
        val reopened = reopen(f)
        val previous = reopened.sql.receiptRevision()
        assertTrue(previous >= 1L)
        assertEquals(StateActivationStatus.ABORTED, reopened.handle.inspect().valueOrFail().status)
        reopened.handle.abort().valueOrFail()
        assertEquals(previous + 1, reopened.sql.receiptRevision())
        assertEquals(listOf(f.key), reopened.vault.deleted) // Exact idempotent deletion, never a new key.
    }

    @Test fun nativeFaultIsRestrictedToExactOwnedDatabaseNotAnotherSandbox() = runBlocking {
        val target = fixture()
        val independent = fixture()
        SqliteSyncFailureInjector.install(target.file.path, SyncPoint.DATABASE.nativeKind, 1)
        val result: PortResult<Unit>
        val stats: LongArray
        try {
            independent.handle.abort().valueOrFail()
            assertEquals(StateActivationStatus.ABORTED, independent.handle.inspect().valueOrFail().status)
            assertEquals(listOf(independent.key), independent.vault.deleted)
            assertEquals(0L, SqliteSyncFailureInjector.statistics()[4])
            result = target.handle.abort()
            stats = SqliteSyncFailureInjector.statistics()
        } finally { SqliteSyncFailureInjector.restore() }
        assertFault(target, result, stats, SyncPoint.DATABASE, "exact-scope")
        val reopened = reopen(target)
        reopened.handle.abort().valueOrFail()
        assertEquals(listOf(target.key), reopened.vault.deleted)
        assertEquals(StateActivationStatus.ABORTED, reopened.handle.inspect().valueOrFail().status)
    }

    private suspend fun failFirstAbort(point: SyncPoint, label: String) {
        val f = fixture()
        val aliases = aliases(f.box)
        injectFailure(f, point, label)
        assertEquals(aliases, aliases(f.box))
        assertTrue(f.vault.containsOwnerKey(f.key))
        val reopened = reopen(f)
        val previous = reopened.sql.receiptRevision()
        assertTrue(reopened.vault.containsOwnerKey(f.key))
        assertTrue(reopened.handle.inspect().valueOrFail().status in
            setOf(StateActivationStatus.SELECTED_EMPTY, StateActivationStatus.ABORTING))
        reopened.handle.abort().valueOrFail()
        assertEquals(previous + 1, reopened.sql.receiptRevision())
        assertEquals(listOf(f.key), reopened.vault.deleted)
        assertFalse(reopened.vault.containsOwnerKey(f.key))
        assertEquals(StateActivationStatus.ABORTED, reopened.handle.inspect().valueOrFail().status)
        assertEquals(aliases - "${f.box.keyPrefix}.owner.${f.key}", aliases(f.box))
    }

    private suspend fun injectFailure(f: Fixture, point: SyncPoint, label: String) {
        f.sql.nativeCodes.clear()
        SqliteSyncFailureInjector.install(f.file.path, point.nativeKind, 1)
        val result: PortResult<Unit>
        val stats: LongArray
        try {
            result = f.handle.abort()
            stats = SqliteSyncFailureInjector.statistics()
        } finally { SqliteSyncFailureInjector.restore() }
        assertFault(f, result, stats, point, label)
    }

    private fun assertFault(f: Fixture, result: PortResult<Unit>, stats: LongArray, point: SyncPoint, label: String) {
        assertTrue("A true native sync failure must not acknowledge cleanup", result is PortResult.Failure)
        val failed = result as PortResult.Failure
        assertTrue(failed.reason in setOf(FailureReason.STORAGE_FAILURE, FailureReason.OUTCOME_UNKNOWN))
        assertEquals(null, failed.retryAfterSeconds)
        assertEquals(7, stats.size)
        assertEquals("The exact native syscall fault must actually execute", 1L, stats[4])
        assertEquals(5L, stats[5]) // EIO, not a substituted Java exception.
        assertTrue(stats[point.nativeKind - 1] >= 1L)
        assertTrue(stats[6] == 1L || stats[6] == 3L)
        assertTrue("The actual bundled engine must surface its extended numeric sync error",
            f.sql.nativeCodes.contains(point.sqliteCode))
        assertTrue("No key deletion may precede a fresh successful barrier", f.vault.deleted.isEmpty())
        for (privateValue in listOf(OWNER.environment, OWNER.actorId, f.file.path, f.key,
                f.box.keyPrefix, point.sqliteCode.toString())) assertFalse(failed.toString().contains(privateValue))
        // Machine-readable numeric evidence only; do not expose fd paths, aliases or SQL text.
        InstrumentationRegistry.getInstrumentation().addResults(Bundle().apply {
            putString("state_activation_sync_$label", "sqlite=${point.sqliteCode};errno=${stats[5]};failures=${stats[4]};keyDeletes=0;hooks=${stats[6]}")
        })
    }

    private suspend fun fixture(): Fixture {
        val box = AndroidStateTestSandbox().also(sandboxes::add)
        val initial = AndroidStateDatabase.openForTests(box.directory, box.keyPrefix).valueOrFail()
        val plan: StateActivationPlan
        try {
            plan = initial.planActivation(OWNER).valueOrFail()
            initial.commitPlannedActivation(OWNER, plan).valueOrFail()
        } finally { initial.close().valueOrFail() }
        return recover(box, plan)
    }

    private suspend fun reopen(f: Fixture): Fixture {
        f.handle.close().valueOrFail(); handles.remove(f.handle)
        return recover(f.box, f.plan)
    }

    private suspend fun recover(box: AndroidStateTestSandbox, plan: StateActivationPlan): Fixture {
        val file = File(box.directory, "state.sqlite").canonicalFile
        val vault = NativeTrace(AndroidStateVault.createOrOpen(box.keyPrefix, databaseExisted = true))
        val sql = NativeSqlTrace(BundledSQLiteDriver().open(file.path,
            SQLITE_OPEN_READWRITE or SQLITE_OPEN_FULLMUTEX or SQLITE_OPEN_NOFOLLOW))
        val handle = EncryptedStateDatabase.openActivationRecovery(sql, vault, OWNER, plan)
            .valueOrFail().also(handles::add)
        return Fixture(box, plan, file, StateActivationPlanCodec.decode(plan).keyId, vault, sql, handle)
    }

    private class Fixture(val box: AndroidStateTestSandbox, val plan: StateActivationPlan, val file: File,
        val key: String, val vault: NativeTrace, val sql: NativeSqlTrace, val handle: StateActivationRecoveryHandle)

    private class NativeTrace(private val native: AndroidStateVault) : PlannedStateVault by native {
        val deleted = mutableListOf<String>()
        var failNextDelete = false
        override fun deleteOwnerKey(keyId: String) {
            deleted += keyId
            if (failNextDelete) { failNextDelete = false; throw StateVaultException() }
            native.deleteOwnerKey(keyId)
        }
    }

    private class NativeSqlTrace(private val native: SQLiteConnection) : SQLiteConnection by native {
        val nativeCodes = mutableListOf<Int>()
        override fun prepare(sql: String): SQLiteStatement {
            val statement = native.prepare(sql)
            return object : SQLiteStatement by statement {
                override fun step(): Boolean = try { statement.step() } catch (failure: Exception) {
                    // Keep only native numeric evidence; never retain/log the raw private SQL error.
                    Regex("Error code: ([0-9]+)").find(failure.message.orEmpty())?.groupValues?.get(1)
                        ?.toIntOrNull()?.let(nativeCodes::add)
                    throw failure
                }
            }
        }
        fun receiptRevision(): Long = prepare("SELECT COALESCE(MAX(revision),0) FROM feedme_activation_aborts")
            .use { assertTrue(it.step()); it.getLong(0) }
    }

    private enum class SyncPoint(val nativeKind: Int, val sqliteCode: Int) {
        JOURNAL(1, 1034), DATABASE(2, 1034), DIRECTORY_AFTER_UNLINK(4, 1290),
    }
    private fun aliases(box: AndroidStateTestSandbox) = box.keyStore().aliases().toList()
        .filter { it.startsWith("${box.keyPrefix}.") }.toSet()
    private fun failure(value: PortResult<*>, reason: FailureReason) {
        assertTrue(value is PortResult.Failure); assertEquals(reason, (value as PortResult.Failure).reason)
    }

    companion object { private val OWNER = StorageScope("sync-failure-fixture", ActorKind.ACCOUNT, "private-owner") }
}

/** Never loaded by production sources; the corresponding .so belongs only to androidTest JNI. */
internal object SqliteSyncFailureInjector {
    init { System.loadLibrary("feedmeSqliteSyncFailure") }
    external fun install(databasePath: String, target: Int, nthMatch: Int)
    external fun statistics(): LongArray
    external fun restore()
}
