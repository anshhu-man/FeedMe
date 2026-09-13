package com.feedme.storage

import android.system.Os
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_FULLMUTEX
import androidx.sqlite.driver.bundled.SQLITE_OPEN_NOFOLLOW
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.feedme.core.ports.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native SQLite/Keystore exact binding removal. No composite consent, provider or power-loss claim. */
@RunWith(AndroidJUnit4::class)
class AndroidStateBindingAbortTest {
    private val boxes = mutableListOf<AndroidStateTestSandbox>()
    private val closers = mutableListOf<suspend () -> PortResult<Unit>>()

    @After fun cleanup() = runBlocking {
        // Close failure preserves exact fixture evidence rather than deleting under a live owner.
        closers.asReversed().forEach { it().valueOrFail() }; closers.clear()
        boxes.asReversed().forEach { it.close() }; boxes.clear()
    }

    @Test fun exactNativeBindingAbortConsumesOwnerAndReacknowledgesEveryReplayAcrossPublicReopen() = runBlocking {
        val f = fixture(); val original = f.plan.copyForStorage(); val key = alias(f)
        f.db.abortPlannedActivation(OWNER, f.plan, BODY).valueOrFail()
        assertEquals(0L, scalar(f.box, "SELECT count(*) FROM feedme_records"))
        assertEquals(decode(f.plan).consumedGeneration, scalar(f.box, "SELECT generation FROM feedme_owners"))
        assertEquals(0L, scalar(f.box, "SELECT active FROM feedme_owners"))
        assertEquals(1L, scalar(f.box, "SELECT revision FROM feedme_activation_aborts")); assertFalse(key in aliases(f.box))
        f.db.close().valueOrFail()
        val handle = recover(f)
        repeat(2) { i ->
            handle.abortBound(BODY).valueOrFail()
            assertEquals(i + 2L, scalar(f.box, "SELECT revision FROM feedme_activation_aborts"))
            assertEquals(StateActivationStatus.ABORTED, handle.inspect().valueOrFail().status)
        }
        assertArrayEquals(original, f.plan.copyForStorage()); assertFalse(key in aliases(f.box))
    }

    @Test fun strictEmptyAbortWrongPayloadAndSchemaOneCannotEraseNativeBinding() = runBlocking {
        for (schema in listOf(1, 2)) {
            val f = fixture(schema = schema); val before = snapshot(f.box); val keys = aliases(f.box)
            failure(f.db.abortPlannedActivation(OWNER, f.plan), FailureReason.CONFLICT)
            failure(f.db.abortPlannedActivation(OWNER, f.plan, if (schema == 1) BODY else bytes("other")), FailureReason.CONFLICT)
            assertSnapshot(before, snapshot(f.box)); assertEquals(keys, aliases(f.box))
        }
    }

    @Test fun otherPrivateRowsTombstonesAndDamagedReservedBindingsRemainPreserved() = runBlocking {
        for (damage in listOf("row", "other-tombstone", "binding-tombstone", "ciphertext", "schema")) {
            val f = fixture()
            if (damage == "row" || damage == "other-tombstone") {
                val store = f.db.resume(OWNER).valueOrFail()!!
                store.commit(OWNER, listOf(StoreMutation.Put(OTHER, null, 1, bytes("keep-native-private")))).valueOrFail()
                if (damage == "other-tombstone") store.commit(OWNER, listOf(StoreMutation.Delete(OTHER, 1))).valueOrFail()
            } else sql(f.box, when (damage) { "binding-tombstone" -> "UPDATE feedme_records SET payload=NULL";
                "schema" -> "UPDATE feedme_records SET schema_version=3"; else -> "UPDATE feedme_records SET payload=zeroblob(29)" })
            val before = snapshot(f.box); val keys = aliases(f.box)
            failure(f.db.abortPlannedActivation(OWNER, f.plan, BODY),
                if (damage == "ciphertext") FailureReason.STORAGE_FAILURE else FailureReason.CONFLICT)
            assertSnapshot(before, snapshot(f.box)); assertEquals(keys, aliases(f.box))
        }
    }

    @Test fun missingNativeBindingKeyCannotBeRegeneratedButExactEmptySelectionCanStillBeConsumed() = runBlocking {
        for (bound in listOf(false, true)) {
            val f = fixture(bound = bound); f.box.keyStore().deleteEntry(alias(f))
            val before = snapshot(f.box); val keys = aliases(f.box)
            if (bound) {
                failure(f.db.abortPlannedActivation(OWNER, f.plan, BODY), FailureReason.STORAGE_FAILURE)
                assertSnapshot(before, snapshot(f.box))
            } else {
                f.db.abortPlannedActivation(OWNER, f.plan, BODY).valueOrFail()
                assertEquals(StateActivationStatus.ABORTED, f.db.inspectPlannedActivation(OWNER, f.plan).valueOrFail().status)
            }
            assertEquals(keys, aliases(f.box))
        }
    }

    @Test fun wrongScopeForgedMacAndForeignInstallNeverDeleteOrConsumeNativeTargets() = runBlocking {
        val f = fixture(); val foreign = fixture(); val before = snapshot(f.box); val keys = aliases(f.box)
        val changed = f.plan.copyForStorage(); changed[169] = (changed[169].toInt() xor 1).toByte()
        failure(f.db.abortPlannedActivation(OWNER, StateActivationPlan(changed), BODY), FailureReason.INVALID_DATA)
        failure(f.db.abortPlannedActivation(OWNER.copy(actorId = "wrong"), f.plan, BODY), FailureReason.INVALID_DATA)
        val otherBefore = snapshot(foreign.box); val otherKeys = aliases(foreign.box)
        failure(foreign.db.abortPlannedActivation(OWNER, f.plan, BODY), FailureReason.INVALID_DATA)
        assertSnapshot(before, snapshot(f.box)); assertEquals(keys, aliases(f.box))
        assertSnapshot(otherBefore, snapshot(foreign.box)); assertEquals(otherKeys, aliases(foreign.box))
    }

    @Test fun beforeAndAfterSuccessfulSqliteCommitFaultsRetainKeyUntilExactFreshAcknowledgement() = runBlocking {
        for (after in listOf(false, true)) {
            val f = fixture(); f.db.close().valueOrFail(); val trace = traced(f)
            trace.sql.failAfter = after; trace.sql.armed = true
            failure(trace.db.abortPlannedActivation(OWNER, f.plan, BODY),
                if (after) FailureReason.OUTCOME_UNKNOWN else FailureReason.STORAGE_FAILURE)
            assertEquals(0, trace.vault.deletes); assertTrue(alias(f) in aliases(f.box))
            assertEquals(if (after) 0L else 1L, scalar(f.box, "SELECT count(*) FROM feedme_records"))
            val before = scalar(f.box, "SELECT coalesce(max(revision),0) FROM feedme_activation_aborts")
            trace.db.close().valueOrFail()
            val handle = recover(f); handle.abortBound(BODY).valueOrFail()
            assertEquals(before + 1, scalar(f.box, "SELECT revision FROM feedme_activation_aborts")); assertFalse(alias(f) in aliases(f.box))
        }
    }

    @Test fun nativeKeyDeletionFailureLeavesConsumedBindingAndOriginalKeyRetryableAcrossClose() = runBlocking {
        val f = fixture(); f.db.close().valueOrFail(); val trace = traced(f)
        trace.vault.failDelete = true
        failure(trace.db.abortPlannedActivation(OWNER, f.plan, BODY), FailureReason.STORAGE_FAILURE)
        assertEquals(1L, scalar(f.box, "SELECT revision FROM feedme_activation_aborts"))
        assertEquals(0L, scalar(f.box, "SELECT count(*) FROM feedme_records")); assertTrue(alias(f) in aliases(f.box))
        trace.db.close().valueOrFail()
        val handle = recover(f); handle.abortBound(BODY).valueOrFail(); assertFalse(alias(f) in aliases(f.box))
        assertEquals(2L, scalar(f.box, "SELECT revision FROM feedme_activation_aborts"))
    }

    @Test fun boundAbortPreservesUnrelatedQueuedNativeGarbageAndSiblingPrivateRows() = runBlocking {
        val f = fixture(); val sibling = OWNER.copy(actorId = "private-sibling")
        val store = f.db.activate(sibling).valueOrFail()
        store.commit(sibling, listOf(StoreMutation.Put(OTHER, null, 1, bytes("retain-sibling")))).valueOrFail()
        val vault = AndroidStateVault.openExisting(f.box.keyPrefix); val garbage = vault.newOwnerKeyId(); vault.createOwnerKey(garbage)
        sql(f.box, "INSERT INTO feedme_key_gc VALUES('$garbage')")
        val keys = aliases(f.box)
        repeat(2) { f.db.abortPlannedActivation(OWNER, f.plan, BODY).valueOrFail() }
        assertEquals(keys - alias(f), aliases(f.box)); assertEquals(1L, scalar(f.box, "SELECT count(*) FROM feedme_key_gc"))
        assertArrayEquals(bytes("retain-sibling").copyForCodec(), store.read(sibling, OTHER).valueOrFail()!!.payload.copyForCodec())
    }

    @Test fun cancellationAfterNativeCommitDoesNotDeleteAndNewerOwnerRejectsOldBoundCleanup() = runBlocking {
        val f = fixture(); f.db.close().valueOrFail(); val trace = traced(f)
        trace.sql.afterCommit = { trace.sql.afterCommit = null; throw CancellationException("Test cancellation after successful COMMIT") }
        try { trace.db.abortPlannedActivation(OWNER, f.plan, BODY); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertEquals(0, trace.vault.deletes); assertTrue(alias(f) in aliases(f.box))
        trace.db.abortPlannedActivation(OWNER, f.plan, BODY).valueOrFail(); trace.db.close().valueOrFail()
        val db = open(f.box); val next = db.planActivation(OWNER).valueOrFail(); db.commitPlannedActivation(OWNER, next).valueOrFail()
        db.bindPlannedActivation(OWNER, next, 2, BODY).valueOrFail()
        val before = snapshot(f.box); val keys = aliases(f.box)
        failure(db.abortPlannedActivation(OWNER, f.plan, BODY), FailureReason.STALE_SESSION)
        assertSnapshot(before, snapshot(f.box)); assertEquals(keys, aliases(f.box))
    }

    @Test fun invalidPayloadBoundsAndClosedRecoveryHandleCannotGainDeletionOrLockAuthority() = runBlocking {
        val f = fixture(); val before = snapshot(f.box); val keys = aliases(f.box)
        for (size in listOf(0, 4097)) failure(f.db.abortPlannedActivation(OWNER, f.plan, PrivateBytes(ByteArray(size))), FailureReason.INVALID_DATA)
        assertSnapshot(before, snapshot(f.box)); assertEquals(keys, aliases(f.box)); f.db.close().valueOrFail()
        val handle = recover(f)
        failure(AndroidStateDatabase.openActivationRecoveryForTests(f.box.directory, f.box.keyPrefix, OWNER, f.plan), FailureReason.STORAGE_FAILURE)
        assertEquals("StateActivationRecoveryHandle(<redacted>)", handle.toString())
        handle.close().valueOrFail()
        failure(handle.abortBound(BODY), FailureReason.STORAGE_FAILURE)
        assertSnapshot(before, snapshot(f.box)); assertEquals(keys, aliases(f.box))
    }

    private data class Fixture(val box: AndroidStateTestSandbox, val db: EncryptedStateDatabase, val plan: StateActivationPlan)
    private suspend fun fixture(bound: Boolean = true, schema: Int = 2): Fixture {
        val box = AndroidStateTestSandbox().also(boxes::add); val db = open(box)
        val plan = db.planActivation(OWNER).valueOrFail(); db.commitPlannedActivation(OWNER, plan).valueOrFail()
        if (bound) db.bindPlannedActivation(OWNER, plan, schema, BODY).valueOrFail()
        return Fixture(box, db, plan)
    }
    private suspend fun open(box: AndroidStateTestSandbox) = AndroidStateDatabase.openForTests(box.directory, box.keyPrefix)
        .valueOrFail().also { db -> closers += { db.close() } }
    private suspend fun recover(f: Fixture) = AndroidStateDatabase.openActivationRecoveryForTests(f.box.directory, f.box.keyPrefix, OWNER, f.plan)
        .valueOrFail().also { handle -> closers += { handle.close() } }
    private data class Traced(val db: EncryptedStateDatabase, val sql: TraceSql, val vault: TraceVault)
    private suspend fun traced(f: Fixture): Traced {
        val sql = TraceSql(connection(f.box)); val vault = TraceVault(AndroidStateVault.openExisting(f.box.keyPrefix))
        val db = EncryptedStateDatabase.open(sql, vault).valueOrFail().also { db -> closers += { db.close() } }
        return Traced(db, sql, vault)
    }
    private class TraceVault(private val native: PlannedStateVault) : PlannedStateVault by native {
        var deletes = 0; var failDelete = false
        override fun deleteOwnerKey(keyId: String) { deletes++; if (failDelete) throw StateVaultException(); native.deleteOwnerKey(keyId) }
    }
    private class TraceSql(private val native: SQLiteConnection) : SQLiteConnection by native {
        var armed = false; var failAfter = false; var afterCommit: (() -> Unit)? = null; private var changed = false
        override fun prepare(sql: String): SQLiteStatement {
            val statement = native.prepare(sql)
            return object : SQLiteStatement by statement {
                override fun step(): Boolean {
                    if (sql.startsWith("BEGIN")) changed = false
                    val committing = sql == "COMMIT" && changed
                    val inject = committing && armed
                    if (inject) armed = false
                    if (inject && !failAfter) error("Injected pre-COMMIT application fault")
                    val result = statement.step()
                    if (listOf("INSERT ", "UPDATE ", "DELETE ").any(sql::startsWith)) changed = true
                    if (committing) { changed = false; afterCommit?.invoke() }
                    if (inject && failAfter) error("Injected lost application receipt after successful COMMIT")
                    if (sql == "COMMIT" || sql == "ROLLBACK") changed = false
                    return result
                }
            }
        }
    }
    private fun connection(box: AndroidStateTestSandbox) = BundledSQLiteDriver().open(File(box.directory, "state.sqlite").path,
        SQLITE_OPEN_READWRITE or SQLITE_OPEN_FULLMUTEX or SQLITE_OPEN_NOFOLLOW)
    private fun sql(box: AndroidStateTestSandbox, query: String) = connection(box).use { c -> c.prepare(query).use { while (it.step()) { } } }
    private fun scalar(box: AndroidStateTestSandbox, query: String): Long = connection(box).use { c -> c.prepare(query).use { assertTrue(it.step()); it.getLong(0) } }
    private fun aliases(box: AndroidStateTestSandbox) = box.keyStore().aliases().toList().filter { it.startsWith("${box.keyPrefix}.") }.toSet()
    private fun alias(f: Fixture) = "${f.box.keyPrefix}.owner.${decode(f.plan).keyId}"
    private fun snapshot(box: AndroidStateTestSandbox) = box.directory.listFiles()!!.associate { file ->
        file.name to if (file.name == "state.lock") { assertEquals(0L, Os.stat(file.path).st_size); byteArrayOf() } else file.readBytes()
    }
    private fun assertSnapshot(before: Map<String, ByteArray>, after: Map<String, ByteArray>) {
        assertEquals(before.keys, after.keys); before.forEach { (name, bytes) -> assertArrayEquals(bytes, after.getValue(name)) }
    }
    private fun failure(result: PortResult<*>, reason: FailureReason) {
        assertTrue(result is PortResult.Failure); assertEquals(reason, (result as PortResult.Failure).reason)
    }
    companion object {
        private val OWNER = StorageScope("native-binding-abort", ActorKind.ACCOUNT, "private-exact-owner")
        private val OTHER = RecordKey("private-domain", "retained")
        private val BODY = bytes("opaque-exact-native-binding-v2")
        private fun bytes(value: String) = PrivateBytes(value.encodeToByteArray())
        private fun decode(plan: StateActivationPlan) = StateActivationPlanCodec.decode(plan)
    }
}
