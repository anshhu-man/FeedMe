package com.feedme.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Keystore/SQLite components; not application journaling, recovery opening or power-loss proof. */
@RunWith(AndroidJUnit4::class)
class AndroidStateActivationPlanTest {
    private val sandboxes = mutableListOf<AndroidStateTestSandbox>()
    private val managers = mutableListOf<EncryptedStateDatabase>()

    @After fun cleanup() = runBlocking {
        // If a close is not acknowledged, retain the exact fixture rather than deleting live data.
        managers.asReversed().forEach { it.close().valueOrFail() }
        managers.clear()
        sandboxes.asReversed().forEach { it.close() }
        sandboxes.clear()
    }

    @Test fun nativeCandidateSelectionCreatesNoAliasAndExactCreationNeverOverwritesAnyAlias() {
        val box = sandbox()
        val vault = AndroidStateVault.createOrOpen(box.keyPrefix, databaseExisted = false)
        val original = aliases(box)
        val candidates = List(32) { vault.newOwnerKeyId() }
        assertEquals(32, candidates.toSet().size)
        assertTrue(candidates.all { it.matches(Regex("[0-9a-f]{32}")) })
        assertEquals(original, aliases(box))
        val selected = candidates.first()
        vault.createOwnerKey(selected)
        val plaintext = "native exact-key canary".encodeToByteArray()
        val aad = "native-plan-test".encodeToByteArray()
        val encrypted = vault.seal(selected, plaintext, aad)
        val after = aliases(box)
        assertVaultFailure { vault.createOwnerKey(selected) }
        assertEquals(after, aliases(box))
        assertArrayEquals(plaintext, vault.open(selected, encrypted, aad))
        assertNull(box.keyStore().getKey("${box.keyPrefix}.owner.$selected", null).encoded)
        for (bad in listOf("", "../index", "A".repeat(32), selected.dropLast(1), "g".repeat(32))) {
            assertVaultFailure { vault.createOwnerKey(bad) }
            assertEquals(after, aliases(box))
        }
        val other = candidates[1]
        val spec = KeyGenParameterSpec.Builder("${box.keyPrefix}.owner.$other", KeyProperties.PURPOSE_SIGN)
            .setKeySize(256).build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
            .apply { init(spec) }.generateKey()
        val wrongKind = aliases(box)
        assertVaultFailure { vault.createOwnerKey(other) }
        assertEquals(wrongKind, aliases(box))
        assertEquals("HmacSHA256", box.keyStore().getKey("${box.keyPrefix}.owner.$other", null).algorithm)
    }

    @Test fun planningIsReadOnlyDetachedOpaqueAndAllocatesNoNativeOwnerKey() = runBlocking {
        val box = sandbox(); val db = open(box)
        val before = files(box); val nativeBefore = aliases(box)
        val plan = db.planActivation(OWNER).valueOrFail()
        val decoded = decode(plan)
        assertEquals(0L, decoded.priorGeneration)
        assertNull(decoded.priorKeyId)
        assertEquals(1L, decoded.selectedGeneration)
        assertEquals(2L, decoded.consumedGeneration)
        assertEquals(170, plan.copyForStorage().size)
        val copy = plan.copyForStorage(); copy.fill(0)
        assertEquals(decoded.keyId, decode(plan).keyId)
        for (privateValue in listOf(OWNER.environment, OWNER.actorId, decoded.ownerTag, decoded.keyId))
            assertFalse(plan.toString().contains(privateValue))
        assertFiles(before, files(box)); assertEquals(nativeBefore, aliases(box))
        assertFalse(box.keyStore().containsAlias("${box.keyPrefix}.owner.${decoded.keyId}"))
        assertNull(db.inspectOwnerState(OWNER).valueOrFail().target)
    }

    @Test fun commitAcknowledgesOnlyAndExactSelectedReplayPreservesRecordsAcrossNativeReopen() = runBlocking {
        val box = sandbox(); val first = open(box)
        val plan = first.planActivation(OWNER).valueOrFail(); val key = decode(plan).keyId
        assertEquals(Unit, first.commitPlannedActivation(OWNER, plan).valueOrFail())
        val store = first.resume(OWNER).valueOrFail()!! // Explicit test-only verified-owner action.
        store.commit(OWNER, listOf(StoreMutation.Put(RECORD, null, 7, PrivateBytes(PAYLOAD)))).valueOrFail()
        close(first)
        val reopened = open(box); val before = files(box); val keys = aliases(box)
        assertEquals(Unit, reopened.commitPlannedActivation(OWNER, plan).valueOrFail())
        assertFiles(before, files(box)); assertEquals(keys, aliases(box))
        assertTrue(keys.contains("${box.keyPrefix}.owner.$key"))
        val record = reopened.resume(OWNER).valueOrFail()!!.read(OWNER, RECORD).valueOrFail()!!
        assertEquals(7, record.schemaVersion); assertArrayEquals(PAYLOAD, record.payload.copyForCodec())
    }

    @Test fun fullyRetiredPredecessorCanBePlannedWithoutChangingOtherNativeOwners() = runBlocking {
        val box = sandbox(); val db = open(box)
        val prior = db.activate(OWNER).valueOrFail()
        val oldKey = aliases(box).single { ".owner." in it }.substringAfterLast('.')
        prior.eraseScope(OWNER).valueOrFail()
        val other = db.activate(OTHER).valueOrFail()
        other.commit(OTHER, listOf(StoreMutation.Put(RECORD, null, 1, PrivateBytes(PAYLOAD)))).valueOrFail()
        val before = files(box); val keys = aliases(box)
        val plan = db.planActivation(OWNER).valueOrFail(); val record = decode(plan)
        assertEquals(2L, record.priorGeneration); assertEquals(oldKey, record.priorKeyId)
        assertEquals(3L, record.selectedGeneration); assertTrue(record.keyId != oldKey)
        assertFiles(before, files(box)); assertEquals(keys, aliases(box))
        db.commitPlannedActivation(OWNER, plan).valueOrFail()
        assertArrayEquals(PAYLOAD, other.read(OTHER, RECORD).valueOrFail()!!.payload.copyForCodec())
        assertTrue(aliases(box).containsAll(keys))
        assertFalse(box.keyStore().containsAlias("${box.keyPrefix}.owner.$oldKey"))
    }

    @Test fun forgedWrongScopeAndForeignInstallPlansCannotChangeKeysOrFiles() = runBlocking {
        val box = sandbox(); val db = open(box)
        val plan = db.planActivation(OWNER).valueOrFail(); val record = decode(plan)
        val before = files(box); val keys = aliases(box)
        val changedMac = record.authenticationMac.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val changed = listOf(record.copy(authenticationMac = changedMac),
            record.copy(ownerTag = flip(record.ownerTag)), record.copy(keyId = flip(record.keyId)),
            record.copy(priorGeneration = 2, priorKeyId = flip(record.keyId)))
        for (forged in changed) failure(db.commitPlannedActivation(OWNER, StateActivationPlanCodec.encode(forged)), FailureReason.INVALID_DATA)
        failure(db.commitPlannedActivation(OTHER, plan), FailureReason.INVALID_DATA)
        failure(db.commitPlannedActivation(OWNER.copy(actorId = "bad-${0xD800.toChar()}"), plan), FailureReason.INVALID_DATA)
        assertFiles(before, files(box)); assertEquals(keys, aliases(box))
        val foreignBox = sandbox(); val foreign = open(foreignBox)
        val foreignBefore = files(foreignBox); val foreignKeys = aliases(foreignBox)
        failure(foreign.commitPlannedActivation(OWNER, plan), FailureReason.INVALID_DATA)
        assertFiles(foreignBefore, files(foreignBox)); assertEquals(foreignKeys, aliases(foreignBox))
    }

    @Test fun activeOrChangedOwnerPreconditionsPreserveTheWinningIncarnation() = runBlocking {
        val box = sandbox(); val db = open(box)
        val plan = db.planActivation(OWNER).valueOrFail()
        val winner = db.activate(OWNER).valueOrFail()
        winner.commit(OWNER, listOf(StoreMutation.Put(RECORD, null, 1, PrivateBytes(PAYLOAD)))).valueOrFail()
        val before = files(box); val keys = aliases(box)
        failure(db.planActivation(OWNER), FailureReason.CONFLICT)
        failure(db.commitPlannedActivation(OWNER, plan), FailureReason.STALE_SESSION)
        assertFiles(before, files(box)); assertEquals(keys, aliases(box))
        assertArrayEquals(PAYLOAD, winner.read(OWNER, RECORD).valueOrFail()!!.payload.copyForCodec())
        winner.eraseScope(OWNER).valueOrFail()
        val newer = db.activate(OWNER).valueOrFail(); val newerKeys = aliases(box)
        failure(db.commitPlannedActivation(OWNER, plan), FailureReason.STALE_SESSION)
        assertEquals(newerKeys, aliases(box)); assertNull(newer.read(OWNER, RECORD).valueOrFail())
    }

    @Test fun selectedMissingNativeKeyCannotBeRegeneratedByExactReplay() = runBlocking {
        val box = sandbox(); val db = open(box)
        val plan = db.planActivation(OWNER).valueOrFail(); val alias = "${box.keyPrefix}.owner.${decode(plan).keyId}"
        db.commitPlannedActivation(OWNER, plan).valueOrFail(); close(db)
        box.keyStore().deleteEntry(alias)
        val reopened = open(box); val before = files(box); val keys = aliases(box)
        failure(reopened.commitPlannedActivation(OWNER, plan), FailureReason.STORAGE_FAILURE)
        assertFalse(box.keyStore().containsAlias(alias)); assertEquals(keys, aliases(box)); assertFiles(before, files(box))
        failure(reopened.resume(OWNER), FailureReason.STORAGE_FAILURE)
    }

    @Test fun interruptedExactKeyCreationRetainsAndReusesTheSameNativeKeyAfterReopen() = runBlocking {
        val box = sandbox(); val first = direct(box)
        val plan = first.db.planActivation(OWNER).valueOrFail(); val key = decode(plan).keyId
        first.vault.afterCreate = { error("Injected key creation interruption") }
        failure(first.db.commitPlannedActivation(OWNER, plan), FailureReason.STORAGE_FAILURE)
        assertEquals(0L, first.sql.scalar("SELECT count(*) FROM feedme_owners"))
        assertTrue(first.vault.hasOwnerKey(key)); assertTrue(first.vault.deleted.isEmpty())
        val encrypted = first.vault.seal(key, PAYLOAD, AAD); close(first.db)
        val reopened = direct(box); val keys = aliases(box)
        reopened.db.commitPlannedActivation(OWNER, plan).valueOrFail()
        assertEquals(0, reopened.vault.exactCreates); assertEquals(keys, aliases(box))
        assertArrayEquals(PAYLOAD, reopened.vault.open(key, encrypted, AAD))
    }

    @Test fun cancellationAfterNativeKeyCreationPropagatesWithoutDeletingItsExactCandidate() = runBlocking {
        val box = sandbox(); val f = direct(box)
        val plan = f.db.planActivation(OWNER).valueOrFail(); val key = decode(plan).keyId
        f.vault.afterCreate = { throw CancellationException("Injected native cancellation") }
        var cancelled = false
        try { f.db.commitPlannedActivation(OWNER, plan) } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled); assertTrue(f.vault.hasOwnerKey(key)); assertTrue(f.vault.deleted.isEmpty())
        assertEquals(0L, f.sql.scalar("SELECT count(*) FROM feedme_owners"))
        f.db.commitPlannedActivation(OWNER, plan).valueOrFail()
        assertEquals(1, f.vault.exactCreates); assertEquals(0, f.vault.legacyCreates)
    }

    @Test fun beforeAndAfterRealSqliteCommitFailuresPreserveExactNativeReplay() = runBlocking {
        for (point in CommitFault.entries) {
            val box = sandbox(); val f = direct(box)
            val plan = f.db.planActivation(OWNER).valueOrFail(); val key = decode(plan).keyId
            f.sql.commitFault = point
            failure(f.db.commitPlannedActivation(OWNER, plan),
                if (point == CommitFault.AFTER) FailureReason.OUTCOME_UNKNOWN else FailureReason.STORAGE_FAILURE)
            assertTrue(f.vault.hasOwnerKey(key)); assertTrue(f.vault.deleted.isEmpty())
            assertEquals(if (point == CommitFault.AFTER) 1L else 0L, f.sql.scalar("SELECT count(*) FROM feedme_owners"))
            close(f.db)
            val reopened = direct(box); val before = aliases(box)
            reopened.db.commitPlannedActivation(OWNER, plan).valueOrFail()
            assertEquals(before, aliases(box)); assertEquals(0, reopened.vault.exactCreates)
            assertEquals(1L, reopened.sql.scalar("SELECT count(*) FROM feedme_owners"))
        }
    }

    @Test fun planningCommitAndSelectedReplayNeverExecuteUnrelatedKeyGarbageCollection() = runBlocking {
        val box = sandbox(); val f = direct(box)
        val unrelated = f.vault.newOwnerKeyId(); f.vault.createOwnerKey(unrelated)
        f.sql.execute("INSERT INTO feedme_key_gc(key_id) VALUES('$unrelated')")
        f.observe()
        val plan = f.db.planActivation(OWNER).valueOrFail()
        assertEquals(0, f.vault.exactCreates); assertTrue(f.vault.deleted.isEmpty())
        assertFalse(f.sql.executed.any(::isMutation))
        f.db.commitPlannedActivation(OWNER, plan).valueOrFail()
        f.db.commitPlannedActivation(OWNER, plan).valueOrFail()
        assertTrue(f.vault.hasOwnerKey(unrelated)); assertTrue(f.vault.deleted.isEmpty())
        assertEquals(1L, f.sql.scalar("SELECT count(*) FROM feedme_key_gc WHERE key_id='$unrelated'"))
        assertFalse(f.sql.executed.any { it.startsWith("DELETE FROM feedme_key_gc") })
    }

    @Test fun unfinishedRetiredKeyOrGarbageEntryCannotBeSilentlyCollectedByPlanning() = runBlocking {
        val box = sandbox(); val f = direct(box)
        f.db.activate(OWNER).valueOrFail()
        val key = aliases(box).single { ".owner." in it }.substringAfterLast('.')
        f.sql.execute("UPDATE feedme_owners SET active=0,generation=2")
        f.observe()
        failure(f.db.planActivation(OWNER), FailureReason.CONFLICT)
        assertTrue(f.vault.hasOwnerKey(key)); assertTrue(f.vault.deleted.isEmpty())
        f.vault.deleteOwnerKey(key)
        f.sql.execute("INSERT INTO feedme_key_gc(key_id) VALUES('$key')")
        f.observe()
        failure(f.db.planActivation(OWNER), FailureReason.CONFLICT)
        assertTrue(f.vault.deleted.isEmpty()); assertEquals(1L, f.sql.scalar("SELECT count(*) FROM feedme_key_gc"))
        f.sql.execute("DELETE FROM feedme_key_gc WHERE key_id='$key'")
        assertEquals(2L, decode(f.db.planActivation(OWNER).valueOrFail()).priorGeneration)
    }

    @Test fun selectedReplayDoesNotDecryptOrRewriteUnknownRowsAndTombstones() = runBlocking {
        val box = sandbox(); val f = direct(box)
        val plan = f.db.planActivation(OWNER).valueOrFail(); val tag = decode(plan).ownerTag
        f.db.commitPlannedActivation(OWNER, plan).valueOrFail()
        f.sql.execute("INSERT INTO feedme_records(owner_tag,record_tag,revision,schema_version,payload) VALUES('$tag','${"a".repeat(64)}',1,2147483647,NULL)")
        f.sql.execute("INSERT INTO feedme_records(owner_tag,record_tag,revision,schema_version,payload) VALUES('$tag','${"b".repeat(64)}',1,2147483647,X'${"00".repeat(29)}')")
        val before = files(box); val keys = aliases(box); f.observe()
        assertEquals(Unit, f.db.commitPlannedActivation(OWNER, plan).valueOrFail())
        assertEquals(0, f.vault.opens); assertEquals(0, f.vault.seals); assertEquals(0, f.vault.exactCreates)
        assertTrue(f.vault.deleted.isEmpty()); assertFalse(f.sql.executed.any(::isMutation))
        assertFiles(before, files(box)); assertEquals(keys, aliases(box))
        assertEquals(2L, f.sql.scalar("SELECT count(*) FROM feedme_records"))
    }

    @Test fun candidateReferencesAndOrphanRowsBlockCommitWithoutCreatingOrDeletingKeys() = runBlocking {
        for (collision in listOf("owner", "gc", "orphan-row")) {
            val box = sandbox(); val f = direct(box)
            val plan = f.db.planActivation(OWNER).valueOrFail(); val record = decode(plan)
            when (collision) {
                "owner" -> {
                    val tag = if (record.ownerTag == "f".repeat(64)) "e".repeat(64) else "f".repeat(64)
                    f.sql.execute("INSERT INTO feedme_owners(owner_tag,generation,active,key_id) VALUES('$tag',1,1,'${record.keyId}')")
                }
                "gc" -> f.sql.execute("INSERT INTO feedme_key_gc(key_id) VALUES('${record.keyId}')")
                else -> {
                    f.sql.execute("PRAGMA foreign_keys=OFF")
                    f.sql.execute("INSERT INTO feedme_records(owner_tag,record_tag,revision,schema_version,payload) VALUES('${record.ownerTag}','${"a".repeat(64)}',1,1,NULL)")
                    f.sql.execute("PRAGMA foreign_keys=ON")
                }
            }
            val before = files(box); val keys = aliases(box); f.observe()
            failure(f.db.commitPlannedActivation(OWNER, plan),
                if (collision == "orphan-row") FailureReason.STORAGE_FAILURE else FailureReason.CONFLICT)
            if (collision == "orphan-row") failure(f.db.planActivation(OWNER), FailureReason.STORAGE_FAILURE)
            assertEquals(0, f.vault.exactCreates); assertEquals(0, f.vault.legacyCreates); assertTrue(f.vault.deleted.isEmpty())
            assertFiles(before, files(box)); assertEquals(keys, aliases(box))
        }
    }

    private fun sandbox() = AndroidStateTestSandbox().also(sandboxes::add)
    private suspend fun open(box: AndroidStateTestSandbox) = AndroidStateDatabase.openForTests(box.directory, box.keyPrefix)
        .valueOrFail().also(managers::add)
    private suspend fun close(db: EncryptedStateDatabase) { db.close().valueOrFail(); managers.remove(db) }

    /** Isolated internal seam for real native faults; does not claim public-factory recovery/locking. */
    private suspend fun direct(box: AndroidStateTestSandbox): Direct {
        if (!File(box.directory, "state.sqlite").exists()) close(open(box))
        val vault = NativeTrace(AndroidStateVault.createOrOpen(box.keyPrefix, databaseExisted = true))
        val sql = SqlTrace(BundledSQLiteDriver().open(File(box.directory, "state.sqlite").path,
            SQLITE_OPEN_READWRITE or SQLITE_OPEN_FULLMUTEX or SQLITE_OPEN_NOFOLLOW))
        val db = EncryptedStateDatabase.open(sql, vault).valueOrFail().also(managers::add)
        return Direct(db, sql, vault)
    }

    private class Direct(val db: EncryptedStateDatabase, val sql: SqlTrace, val vault: NativeTrace) {
        fun observe() { sql.executed.clear(); vault.exactCreates = 0; vault.legacyCreates = 0; vault.opens = 0; vault.seals = 0; vault.deleted.clear() }
    }

    private class NativeTrace(private val native: AndroidStateVault) : PlannedStateVault by native {
        var exactCreates = 0; var legacyCreates = 0; var opens = 0; var seals = 0
        val deleted = mutableListOf<String>()
        var afterCreate: (() -> Unit)? = null
        override fun createOwnerKey(): String { legacyCreates++; return native.createOwnerKey() }
        override fun createOwnerKey(keyId: String) {
            exactCreates++; native.createOwnerKey(keyId)
            afterCreate.also { afterCreate = null }?.invoke()
        }
        override fun deleteOwnerKey(keyId: String) { deleted += keyId; native.deleteOwnerKey(keyId) }
        override fun seal(keyId: String, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            seals++; return native.seal(keyId, plaintext, associatedData)
        }
        override fun open(keyId: String, ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
            opens++; return native.open(keyId, ciphertext, associatedData)
        }
    }

    private enum class CommitFault { BEFORE, AFTER }
    private class SqlTrace(private val native: SQLiteConnection) : SQLiteConnection by native {
        val executed = mutableListOf<String>()
        var commitFault: CommitFault? = null
        private var writeTransaction = false
        override fun prepare(sql: String): SQLiteStatement {
            val statement = native.prepare(sql)
            return object : SQLiteStatement by statement {
                override fun step(): Boolean {
                    executed += sql
                    val fault = if (sql == "COMMIT" && writeTransaction) commitFault.also { commitFault = null } else null
                    if (fault == CommitFault.BEFORE) error("Injected precommit interruption")
                    val result = statement.step()
                    if (sql == "BEGIN IMMEDIATE") writeTransaction = true
                    if (sql == "COMMIT" || sql == "ROLLBACK") writeTransaction = false
                    if (fault == CommitFault.AFTER) error("Injected committed acknowledgment loss")
                    return result
                }
            }
        }
        fun execute(sql: String) { prepare(sql).use { while (it.step()) { } } }
        fun scalar(sql: String): Long = prepare(sql).use { assertTrue(it.step()); it.getLong(0) }
    }

    private fun aliases(box: AndroidStateTestSandbox) = box.keyStore().aliases().toList().filter { it.startsWith("${box.keyPrefix}.") }.toSet()
    private fun files(box: AndroidStateTestSandbox): Map<String, ByteArray> = box.directory.listFiles()!!.associate { file ->
        // Never open a second descriptor for the held POSIX lock inode merely to snapshot it.
        file.name to if (file.name == "state.lock") { assertEquals(0L, file.length()); ByteArray(0) } else file.readBytes()
    }
    private fun assertFiles(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
        assertEquals(expected.keys, actual.keys); expected.forEach { (name, bytes) -> assertArrayEquals(bytes, actual.getValue(name)) }
    }
    private fun failure(actual: PortResult<*>, expected: FailureReason) { assertTrue(actual is PortResult.Failure); assertEquals(expected, (actual as PortResult.Failure).reason) }
    private fun assertVaultFailure(action: () -> Unit) {
        var failure: StateVaultException? = null
        try { action() } catch (error: StateVaultException) { failure = error }
        assertNotNull(failure); assertEquals("Private storage vault unavailable", failure!!.message); assertNull(failure.cause)
    }
    private fun decode(plan: StateActivationPlan) = StateActivationPlanCodec.decode(plan)
    private fun flip(hex: String) = (if (hex[0] == 'a') "b" else "a") + hex.drop(1)

    companion object {
        private val OWNER = StorageScope("native-plan-fixture", ActorKind.ACCOUNT, "private-owner")
        private val OTHER = StorageScope("native-plan-fixture", ActorKind.GUEST, "private-other-owner")
        private val RECORD = RecordKey("recipe", "native-planned-record")
        private val PAYLOAD = "encrypted planned-state fixture".encodeToByteArray()
        private val AAD = "native planned-key fixture".encodeToByteArray()
        private fun isMutation(sql: String) = listOf("INSERT ", "UPDATE ", "DELETE ", "CREATE ", "ALTER ", "DROP ")
            .any(sql.trim().uppercase()::startsWith)
    }
}
