package com.feedme.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_FULLMUTEX
import androidx.sqlite.driver.bundled.SQLITE_OPEN_NOFOLLOW
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.feedme.core.ports.ActorKind
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.PrivateBytes
import com.feedme.core.ports.RecordKey
import com.feedme.core.ports.StorageScope
import com.feedme.core.ports.StoreMutation
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Keystore/SQLite recovery; no runtime-journal, hot-journal or power-loss claim. */
@RunWith(AndroidJUnit4::class)
class AndroidStateActivationRecoveryTest {
    private val sandboxes = mutableListOf<AndroidStateTestSandbox>()
    private val managers = mutableListOf<EncryptedStateDatabase>()
    private val handles = mutableListOf<StateActivationRecoveryHandle>()

    @After fun cleanup() = runBlocking {
        // Failed close retains the fixture: never remove files or aliases under a live owner.
        handles.asReversed().forEach { it.close().valueOrFail() }
        handles.clear()
        managers.asReversed().forEach { it.close().valueOrFail() }
        managers.clear()
        sandboxes.asReversed().forEach { it.close() }
        sandboxes.clear()
    }

    @Test fun preparedInspectionIsReadOnlyAndNeverAllocatesKeysOrPrivateHandles() = runBlocking {
        val f = prepared()
        val before = snapshot(f.box); val keys = aliases(f.box)
        val recovery = recover(f)
        repeat(3) { status(recovery, StateActivationStatus.PREPARED) }
        assertEquals("StateActivationRecoveryHandle(<redacted>)", recovery.toString())
        assertEquals("StateActivationInspection(<redacted>)", recovery.inspect().valueOrFail().toString())
        assertSnapshot(before, snapshot(f.box)); assertEquals(keys, aliases(f.box))
        close(recovery)
        val reopened = recover(f)
        status(reopened, StateActivationStatus.PREPARED)
        assertSnapshot(before, snapshot(f.box)); assertEquals(keys, aliases(f.box))
    }

    @Test fun partialExactKeyIsConsumedBeforeDeletionAndRepeatedAbortChangesItsReceipt() = runBlocking {
        val f = prepared(); val record = decode(f.plan)
        AndroidStateVault.openExisting(f.box.keyPrefix).createOwnerKey(record.keyId)
        val recovery = recover(f)
        status(recovery, StateActivationStatus.PARTIAL)
        recovery.abort().valueOrFail(); status(recovery, StateActivationStatus.ABORTED)
        assertFalse(aliases(f.box).contains(ownerAlias(f)))
        close(recovery)
        assertEquals(1L, scalar(f.box, "SELECT revision FROM feedme_activation_aborts"))
        assertEquals(record.consumedGeneration, scalar(f.box, "SELECT generation FROM feedme_owners"))
        assertEquals(0L, scalar(f.box, "SELECT active FROM feedme_owners"))
        val again = recover(f)
        again.abort().valueOrFail(); close(again)
        assertEquals(2L, scalar(f.box, "SELECT revision FROM feedme_activation_aborts"))
        assertFalse(aliases(f.box).contains(ownerAlias(f)))
        val ordinary = open(f.box)
        failure(ordinary.commitPlannedActivation(OWNER, f.plan), FailureReason.STALE_SESSION)
    }

    @Test fun selectedEmptyAbortPreservesSiblingDataAndOldRecoveryCannotEraseNewerOwner() = runBlocking {
        val f = prepared()
        var db = open(f.box)
        val sibling = db.activate(OTHER).valueOrFail()
        sibling.commit(OTHER, listOf(put())).valueOrFail()
        db.commitPlannedActivation(OWNER, f.plan).valueOrFail(); close(db)
        val recovery = recover(f); status(recovery, StateActivationStatus.SELECTED_EMPTY)
        recovery.abort().valueOrFail(); close(recovery)
        assertFalse(aliases(f.box).contains(ownerAlias(f)))
        db = open(f.box)
        assertArrayEquals(PAYLOAD, db.resume(OTHER).valueOrFail()!!.read(OTHER, KEY).valueOrFail()!!.payload.copyForCodec())
        val newer = db.activate(OWNER).valueOrFail()
        newer.commit(OWNER, listOf(put())).valueOrFail(); close(db)
        val before = snapshot(f.box); val keys = aliases(f.box)
        failure(recoveryResult(f), FailureReason.STALE_SESSION)
        assertEquals(keys, aliases(f.box)); assertSnapshot(before, snapshot(f.box))
        db = open(f.box)
        assertArrayEquals(PAYLOAD, db.resume(OWNER).valueOrFail()!!.read(OWNER, KEY).valueOrFail()!!.payload.copyForCodec())
    }

    @Test fun selectedRowsIncludingOnlyTombstonesRejectAbortWithoutDecryptingOrDeleting() = runBlocking {
        for (tombstone in listOf(false, true)) {
            val f = prepared(); val db = open(f.box)
            db.commitPlannedActivation(OWNER, f.plan).valueOrFail()
            val store = db.resume(OWNER).valueOrFail()!!
            store.commit(OWNER, listOf(put())).valueOrFail()
            if (tombstone) store.commit(OWNER, listOf(StoreMutation.Delete(KEY, 1))).valueOrFail()
            close(db)
            // Removing the key demonstrates inspection/rejection relies on authenticated metadata,
            // not secret decryption; the nonempty rows still cannot be discarded by this plan.
            f.box.keyStore().deleteEntry(ownerAlias(f))
            val before = snapshot(f.box); val keys = aliases(f.box)
            val recovery = recover(f); status(recovery, StateActivationStatus.SELECTED_NONEMPTY)
            failure(recovery.abort(), FailureReason.CONFLICT)
            assertSnapshot(before, snapshot(f.box)); assertEquals(keys, aliases(f.box))
            close(recovery)
            assertEquals(1L, scalar(f.box, "SELECT count(*) FROM feedme_records"))
            assertEquals(0L, scalar(f.box, "SELECT count(*) FROM feedme_activation_aborts"))
        }
    }

    @Test fun selectedMissingKeyRemainsInspectableAndExplicitlyAbortableWithoutRegeneration() = runBlocking {
        val f = prepared(); val db = open(f.box)
        db.commitPlannedActivation(OWNER, f.plan).valueOrFail(); close(db)
        f.box.keyStore().deleteEntry(ownerAlias(f))
        val before = snapshot(f.box); val keys = aliases(f.box)
        val recovery = recover(f); status(recovery, StateActivationStatus.SELECTED_EMPTY)
        assertSnapshot(before, snapshot(f.box)); assertEquals(keys, aliases(f.box))
        recovery.abort().valueOrFail(); status(recovery, StateActivationStatus.ABORTED)
        assertEquals(keys, aliases(f.box)); close(recovery)
        val ordinary = open(f.box)
        assertNull(ordinary.resume(OWNER).valueOrFail())
        failure(ordinary.commitPlannedActivation(OWNER, f.plan), FailureReason.STALE_SESSION)
    }

    @Test fun exactUnusableAliasIsPresentForInspectionAndCanBeDeletedWithoutUsingIt() = runBlocking {
        val f = prepared(); val alias = ownerAlias(f)
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).setKeySize(256).build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
            .apply { init(spec) }.generateKey()
        val vault = AndroidStateVault.openExisting(f.box.keyPrefix)
        assertTrue(vault.containsOwnerKey(decode(f.plan).keyId))
        var rejected = false
        try { vault.hasOwnerKey(decode(f.plan).keyId) } catch (_: StateVaultException) { rejected = true }
        assertTrue(rejected)
        val recovery = recover(f); status(recovery, StateActivationStatus.PARTIAL)
        recovery.abort().valueOrFail(); status(recovery, StateActivationStatus.ABORTED)
        assertFalse(f.box.keyStore().containsAlias(alias))
    }

    @Test fun missingDirectoryDatabaseLockOrIndexNeverInitializesAnyReplacement() = runBlocking {
        val source = prepared()
        val absent = sandbox(); val absentFixture = Fixture(absent, source.plan)
        repeat(2) { failure(recoveryResult(absentFixture), FailureReason.STORAGE_FAILURE) }
        assertFalse(absent.directory.exists()); assertTrue(aliases(absent).isEmpty())
        for (part in listOf("state.sqlite", "state.lock", "index")) {
            val f = prepared()
            if (part == "index") f.box.keyStore().deleteEntry("${f.box.keyPrefix}.index")
            else Files.delete(File(f.box.directory, part).toPath())
            val before = snapshot(f.box); val keys = aliases(f.box)
            repeat(2) { failure(recoveryResult(f), FailureReason.STORAGE_FAILURE) }
            assertSnapshot(before, snapshot(f.box)); assertEquals(keys, aliases(f.box))
        }
    }

    @Test fun wrongMacScopeAndInstallAreRejectedBeforeOpeningEvenACorruptDatabase() = runBlocking {
        val f = prepared(); val other = prepared()
        val malformed = f.plan.copyForStorage().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val before = snapshot(f.box); val keys = aliases(f.box)
        failure(recoveryResult(f, plan = StateActivationPlan(malformed)), FailureReason.INVALID_DATA)
        failure(recoveryResult(f, scope = OTHER), FailureReason.INVALID_DATA)
        failure(recoveryResult(f, scope = StorageScope("native-recovery", ActorKind.DEMO, "demo")), FailureReason.INVALID_DATA)
        failure(recoveryResult(f, scope = StorageScope("native-recovery", ActorKind.ACCOUNT, "\uD800")), FailureReason.INVALID_DATA)
        failure(recoveryResult(f, plan = other.plan), FailureReason.INVALID_DATA)
        assertSnapshot(before, snapshot(f.box)); assertEquals(keys, aliases(f.box))
        write(File(f.box.directory, "state.sqlite"), byteArrayOf(1, 2, 3))
        val corrupt = snapshot(f.box)
        failure(recoveryResult(f, plan = StateActivationPlan(malformed)), FailureReason.INVALID_DATA)
        failure(recoveryResult(f), FailureReason.STORAGE_FAILURE)
        assertSnapshot(corrupt, snapshot(f.box)); assertEquals(keys, aliases(f.box))
    }

    @Test fun rollbackWalSharedMemoryAndUnknownChildrenArePreservedAndNeverRecovered() = runBlocking {
        for (name in listOf("state.sqlite-journal", "state.sqlite-wal", "state.sqlite-shm", "unknown.bin")) {
            for (bytes in listOf(byteArrayOf(), byteArrayOf(4, 5, 6))) {
                val f = prepared(); val extra = File(f.box.directory, name); write(extra, bytes)
                val before = snapshot(f.box); val keys = aliases(f.box)
                repeat(2) { failure(recoveryResult(f), FailureReason.STORAGE_FAILURE) }
                assertSnapshot(before, snapshot(f.box)); assertEquals(keys, aliases(f.box))
                Files.delete(extra.toPath()) // This test created exactly this isolated evidence.
                close(recover(f))
            }
        }
    }

    @Test fun symlinksNonprivateFilesAndNonemptyLockAreRejectedWithoutChangingTargets() = runBlocking {
        val directoryTarget = prepared(); val linked = sandbox()
        val targetBefore = snapshot(directoryTarget.box); val targetKeys = aliases(directoryTarget.box)
        Os.symlink(directoryTarget.box.directory.path, linked.directory.path)
        failure(recoveryResult(Fixture(linked, directoryTarget.plan)), FailureReason.STORAGE_FAILURE)
        assertTrue(Files.isSymbolicLink(linked.directory.toPath()))
        assertSnapshot(targetBefore, snapshot(directoryTarget.box)); assertEquals(targetKeys, aliases(directoryTarget.box))
        for (part in listOf("state.sqlite", "state.lock")) {
            val f = prepared(); val targetBox = sandbox(); Os.mkdir(targetBox.directory.path, 448)
            val target = File(targetBox.directory, "test-owned-target"); val bytes = byteArrayOf(9, 8, 7)
            write(target, bytes)
            val original = File(f.box.directory, part); Files.delete(original.toPath()); Os.symlink(target.path, original.path)
            failure(recoveryResult(f), FailureReason.STORAGE_FAILURE)
            assertTrue(Files.isSymbolicLink(original.toPath())); assertArrayEquals(bytes, target.readBytes())
        }
        for (part in listOf("state.sqlite", "state.lock")) {
            val f = prepared(); val file = File(f.box.directory, part); Os.chmod(file.path, 420)
            val before = snapshot(f.box)
            failure(recoveryResult(f), FailureReason.STORAGE_FAILURE)
            assertSnapshot(before, snapshot(f.box)); assertEquals(420, Os.lstat(file.path).st_mode and 511)
        }
        val f = prepared(); val lock = File(f.box.directory, "state.lock"); write(lock, byteArrayOf(1))
        failure(recoveryResult(f), FailureReason.STORAGE_FAILURE)
        assertArrayEquals(byteArrayOf(1), lock.readBytes())
    }

    @Test fun existingOnlyRefusesOldForeignOrUninitializedSchemaAndWrongIndexType() = runBlocking {
        for (damage in listOf("old-version", "foreign-table", "empty-file", "wrong-index")) {
            val f = prepared()
            when (damage) {
                "old-version" -> sql(f.box, "PRAGMA user_version=1")
                "foreign-table" -> sql(f.box, "CREATE TABLE native_foreign_schema(value TEXT)")
                "empty-file" -> write(File(f.box.directory, "state.sqlite"), byteArrayOf())
                "wrong-index" -> {
                    val alias = "${f.box.keyPrefix}.index"; f.box.keyStore().deleteEntry(alias)
                    val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()
                    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
                }
            }
            val before = snapshot(f.box); val keys = aliases(f.box)
            repeat(2) { failure(recoveryResult(f), FailureReason.STORAGE_FAILURE) }
            assertSnapshot(before, snapshot(f.box)); assertEquals(keys, aliases(f.box))
        }
    }

    @Test fun recoveryAndOrdinaryManagersShareOneLockAndReleaseTheSingleOwnedDescriptor() = runBlocking {
        val f = prepared(); assertEquals(0, lockDescriptors(f.box))
        repeat(3) {
            val recovery = recover(f); assertEquals(1, lockDescriptors(f.box))
            failure(recoveryResult(f), FailureReason.STORAGE_FAILURE)
            val ordinary = AndroidStateDatabase.openForTests(f.box.directory, f.box.keyPrefix)
            if (ordinary is PortResult.Value) managers += ordinary.value
            failure(ordinary, FailureReason.STORAGE_FAILURE); assertEquals(1, lockDescriptors(f.box))
            close(recovery); recovery.close().valueOrFail()
            failure(recovery.inspect(), FailureReason.STORAGE_FAILURE)
            failure(recovery.abort(), FailureReason.STORAGE_FAILURE)
            assertEquals(0, lockDescriptors(f.box))
        }
        val ordinary = open(f.box)
        failure(recoveryResult(f), FailureReason.STORAGE_FAILURE)
        close(ordinary); assertEquals(0, lockDescriptors(f.box))
        close(recover(f)); assertEquals(0, lockDescriptors(f.box))
    }

    @Test fun lostConsumedCommitReceiptNeverDeletesKeyUntilAnotherChangingCommitSucceeds() = runBlocking {
        for (point in listOf(CommitFault.BEFORE, CommitFault.AFTER)) {
            val f = prepared(); val native = AndroidStateVault.openExisting(f.box.keyPrefix)
            native.createOwnerKey(decode(f.plan).keyId)
            val connection = SqlFault(existingConnection(f.box)); val vault = DeleteFault(native)
            val recovery = EncryptedStateDatabase.openActivationRecovery(connection, vault, OWNER, f.plan)
                .valueOrFail().also { handles += it }
            connection.fault = point
            failure(recovery.abort(), if (point == CommitFault.AFTER) FailureReason.OUTCOME_UNKNOWN else FailureReason.STORAGE_FAILURE)
            assertEquals(0, vault.deletes); assertTrue(native.containsOwnerKey(decode(f.plan).keyId))
            status(recovery, if (point == CommitFault.AFTER) StateActivationStatus.ABORTING else StateActivationStatus.PARTIAL)
            close(recovery)
            val resumed = recover(f); resumed.abort().valueOrFail(); close(resumed)
            assertFalse(native.containsOwnerKey(decode(f.plan).keyId))
            assertEquals(if (point == CommitFault.AFTER) 2L else 1L,
                scalar(f.box, "SELECT revision FROM feedme_activation_aborts"))
        }
    }

    @Test fun cancellationAndKeyDeletionFailureKeepConsumedPlanRetryableWithoutGarbageCollection() = runBlocking {
        for (cancelAfterDelete in listOf(false, true)) {
            val f = prepared(); val native = AndroidStateVault.openExisting(f.box.keyPrefix)
            native.createOwnerKey(decode(f.plan).keyId)
            val unrelated = native.newOwnerKeyId(); native.createOwnerKey(unrelated)
            sql(f.box, "INSERT INTO feedme_key_gc(key_id) VALUES('$unrelated')")
            val vault = DeleteFault(native)
            val recovery = EncryptedStateDatabase.openActivationRecovery(existingConnection(f.box), vault, OWNER, f.plan)
                .valueOrFail().also { handles += it }
            vault.beforeDelete = if (cancelAfterDelete) null else { { throw StateVaultException() } }
            vault.afterDelete = if (cancelAfterDelete) { { throw CancellationException("test cancellation") } } else null
            if (cancelAfterDelete) {
                var cancelled = false
                try { recovery.abort() } catch (_: CancellationException) { cancelled = true }
                assertTrue(cancelled)
            } else failure(recovery.abort(), FailureReason.STORAGE_FAILURE)
            status(recovery, if (cancelAfterDelete) StateActivationStatus.ABORTED else StateActivationStatus.ABORTING)
            assertTrue(native.containsOwnerKey(unrelated)); close(recovery)
            val retry = recover(f); retry.abort().valueOrFail(); close(retry)
            assertFalse(native.containsOwnerKey(decode(f.plan).keyId)); assertTrue(native.containsOwnerKey(unrelated))
            assertEquals(1L, scalar(f.box, "SELECT count(*) FROM feedme_key_gc"))
            assertEquals(2L, scalar(f.box, "SELECT revision FROM feedme_activation_aborts"))
        }
    }

    private data class Fixture(val box: AndroidStateTestSandbox, val plan: StateActivationPlan)
    private fun sandbox() = AndroidStateTestSandbox().also { sandboxes += it }
    private suspend fun prepared(): Fixture {
        val box = sandbox(); val db = open(box)
        val plan = db.planActivation(OWNER).valueOrFail(); close(db)
        return Fixture(box, plan)
    }
    private suspend fun open(box: AndroidStateTestSandbox) =
        AndroidStateDatabase.openForTests(box.directory, box.keyPrefix).valueOrFail().also { managers += it }
    private suspend fun recoveryResult(f: Fixture, scope: StorageScope = OWNER, plan: StateActivationPlan = f.plan): PortResult<StateActivationRecoveryHandle> =
        AndroidStateDatabase.openActivationRecoveryForTests(f.box.directory, f.box.keyPrefix, scope, plan).also {
            if (it is PortResult.Value) handles += it.value
        }
    private suspend fun recover(f: Fixture) = recoveryResult(f).valueOrFail()
    private suspend fun close(db: EncryptedStateDatabase) { db.close().valueOrFail(); managers.remove(db) }
    private suspend fun close(handle: StateActivationRecoveryHandle) { handle.close().valueOrFail(); handles.remove(handle) }
    private suspend fun status(handle: StateActivationRecoveryHandle, expected: StateActivationStatus) =
        assertEquals(expected, handle.inspect().valueOrFail().status)
    private fun decode(plan: StateActivationPlan) = StateActivationPlanCodec.decode(plan)
    private fun ownerAlias(f: Fixture) = "${f.box.keyPrefix}.owner.${decode(f.plan).keyId}"
    private fun aliases(box: AndroidStateTestSandbox) = box.keyStore().aliases().toList().filter { it.startsWith("${box.keyPrefix}.") }.toSet()
    private fun existingConnection(box: AndroidStateTestSandbox) = BundledSQLiteDriver().open(File(box.directory, "state.sqlite").path,
        SQLITE_OPEN_READWRITE or SQLITE_OPEN_FULLMUTEX or SQLITE_OPEN_NOFOLLOW)
    private fun sql(box: AndroidStateTestSandbox, sql: String) = existingConnection(box).use { connection ->
        connection.prepare(sql).use { while (it.step()) { } }
    }
    private fun scalar(box: AndroidStateTestSandbox, sql: String): Long = existingConnection(box).use { connection ->
        connection.prepare(sql).use { assertTrue(it.step()); it.getLong(0) }
    }
    private fun write(file: File, bytes: ByteArray) { file.writeBytes(bytes); Os.chmod(file.path, 384) }
    private fun snapshot(box: AndroidStateTestSandbox): Map<String, ByteArray> = box.directory.listFiles()!!.associate { file ->
        // Never open a second descriptor for an owned POSIX lock inode to read its empty content.
        file.name to if (file.name == "state.lock" && file.length() == 0L) byteArrayOf() else file.readBytes()
    }
    private fun assertSnapshot(before: Map<String, ByteArray>, after: Map<String, ByteArray>) {
        assertEquals(before.keys, after.keys); before.forEach { (name, value) -> assertArrayEquals(value, after.getValue(name)) }
    }
    private fun lockDescriptors(box: AndroidStateTestSandbox): Int {
        val path = File(box.directory, "state.lock").canonicalPath
        return File("/proc/self/fd").listFiles()!!.count { entry ->
            try { Os.readlink(entry.path) == path } catch (_: Exception) { false }
        }
    }
    private fun failure(result: PortResult<*>, reason: FailureReason) {
        assertTrue(result is PortResult.Failure); assertEquals(reason, (result as PortResult.Failure).reason)
    }
    private enum class CommitFault { BEFORE, AFTER }
    private class SqlFault(private val native: SQLiteConnection) : SQLiteConnection by native {
        var fault: CommitFault? = null
        private var write = false
        override fun prepare(sql: String): SQLiteStatement {
            val statement = native.prepare(sql)
            return object : SQLiteStatement by statement {
                override fun step(): Boolean {
                    val selected = if (sql == "COMMIT" && write) fault.also { fault = null } else null
                    if (selected == CommitFault.BEFORE) error("Injected before commit")
                    val result = statement.step()
                    if (sql == "BEGIN IMMEDIATE") write = true
                    if (sql == "COMMIT" || sql == "ROLLBACK") write = false
                    // This is AFTER a real successful COMMIT, not a simulated native I/O failure.
                    if (selected == CommitFault.AFTER) error("Injected lost successful receipt")
                    return result
                }
            }
        }
    }
    private class DeleteFault(private val native: PlannedStateVault) : PlannedStateVault by native {
        var deletes = 0
        var beforeDelete: (() -> Unit)? = null
        var afterDelete: (() -> Unit)? = null
        override fun deleteOwnerKey(keyId: String) {
            deletes++; beforeDelete?.also { beforeDelete = null }?.invoke()
            native.deleteOwnerKey(keyId)
            afterDelete?.also { afterDelete = null }?.invoke()
        }
    }
    private fun put() = StoreMutation.Put(KEY, null, 1, PrivateBytes(PAYLOAD))
    companion object {
        private val OWNER = StorageScope("native-recovery", ActorKind.ACCOUNT, "private-recovery-owner")
        private val OTHER = StorageScope("native-recovery", ActorKind.GUEST, "private-recovery-sibling")
        private val KEY = RecordKey("native-recovery", "private-record")
        private val PAYLOAD = "native recovery retained data".encodeToByteArray()
    }
}
