package com.feedme.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.feedme.core.ports.*
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Comparator
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Actual SQLite, fixture-only JCA keys; no native-vault or identity-provider claim. */
class EmptyStateRetirementTest {
    @Test fun missingExactOwnerInspectionDoesNotEnumerateActivateWriteOrCreateKeys() = runBlocking {
        fixture { f ->
            val db = f.open()
            f.observe()
            val before = Files.readAllBytes(f.file)
            val state = ok(db.inspectOwnerState(OWNER))
            assertNull(state.target)
            assertFalse(state.hasRecords)
            f.assertReadOnly()
            assertContentEquals(before, Files.readAllBytes(f.file))
            assertTrue(f.vault.keys.isEmpty())
            assertEquals(0L, f.scalar("SELECT count(*) FROM feedme_owners"))
        }
    }

    @Test fun activeEmptyInspectionAndValidationAreReadOnlyAndKeepExistingHandleUsable() = runBlocking {
        fixture { f ->
            val db = f.open()
            val store = ok(db.activate(OWNER))
            val expected = assertNotNull(ok(db.captureRetirement(OWNER))).copyForStorage()
            f.observe()
            val before = Files.readAllBytes(f.file)
            val state = ok(db.inspectOwnerState(OWNER))
            assertFalse(state.hasRecords)
            assertContentEquals(expected, assertNotNull(state.target).copyForStorage())
            ok(db.validateEmptyRetirement(OWNER, assertNotNull(state.target)))
            f.assertReadOnly()
            assertEquals(0, f.vault.opens)
            assertContentEquals(before, Files.readAllBytes(f.file))
            ok(store.commit(OWNER, listOf(put())))
        }
    }

    @Test fun arbitraryCollectionsFutureSchemasAndTombstonesAllCountAsRecords() = runBlocking {
        fixture { f ->
            val db = f.open()
            val store = ok(db.activate(OWNER))
            val foreignKey = RecordKey("not-an-activation-record", "future-schema-row")
            ok(store.commit(OWNER, listOf(StoreMutation.Put(foreignKey, null, Int.MAX_VALUE, bytes("future-private-data")))))
            f.observe()
            assertTrue(ok(db.inspectOwnerState(OWNER)).hasRecords)
            f.assertReadOnly()
            assertEquals(0, f.vault.opens)
            ok(store.commit(OWNER, listOf(StoreMutation.Delete(foreignKey, 1))))
            f.observe()
            assertTrue(ok(db.inspectOwnerState(OWNER)).hasRecords)
            assertEquals(1L, f.scalar("SELECT count(*) FROM feedme_records WHERE payload IS NULL"))
            f.assertReadOnly()
        }
    }

    @Test fun corruptRecordStillCountsWithoutDecryptionAndCannotBeDiscardedAsEmpty() = runBlocking {
        fixture { f ->
            val db = f.open()
            val store = ok(db.activate(OWNER))
            ok(store.commit(OWNER, listOf(put())))
            val target = assertNotNull(ok(db.captureRetirement(OWNER)))
            f.raw("PRAGMA ignore_check_constraints=ON", "UPDATE feedme_records SET revision=0,payload='malformed-private-record'")
            f.observe()
            assertTrue(ok(db.inspectOwnerState(OWNER)).hasRecords)
            rejected(FailureReason.CONFLICT, db.validateEmptyRetirement(OWNER, target))
            rejected(FailureReason.CONFLICT, db.recoverEmptyRetirement(target))
            f.assertReadOnly()
            assertEquals(0, f.vault.opens)
            assertNotNull(ok(db.captureRetirement(OWNER)), "Rejected empty-only cleanup must not fence the owner")
        }
    }

    @Test fun scopeEnvironmentAndActorKindAreIndependentAndOtherOwnerDataIsNotEmptyEvidence() = runBlocking {
        fixture { f ->
            val db = f.open()
            ok(ok(db.activate(OTHER)).commit(OTHER, listOf(put())))
            f.observe()
            for (scope in listOf(OWNER, OTHER.copy(environment = "other-environment"), OTHER.copy(actorKind = ActorKind.GUEST))) {
                val state = ok(db.inspectOwnerState(scope))
                assertNull(state.target)
                assertFalse(state.hasRecords)
            }
            assertTrue(ok(db.inspectOwnerState(OTHER)).hasRecords)
            f.assertReadOnly()
            assertEquals(1L, f.scalar("SELECT count(*) FROM feedme_owners"))
        }
    }

    @Test fun queuedGarbageKeyIsNotCollectedByEmptyInspectionOrValidation() = runBlocking {
        fixture { f ->
            val db = f.open()
            val old = ok(db.activate(OWNER))
            val oldKey = f.vault.keys.keys.single()
            ok(db.activate(OTHER))
            val current = assertNotNull(ok(db.captureRetirement(OTHER)))
            f.vault.failDelete = true
            rejected(FailureReason.STORAGE_FAILURE, old.eraseScope(OWNER))
            f.vault.failDelete = false
            assertEquals(1L, f.scalar("SELECT count(*) FROM feedme_key_gc"))
            f.observe()
            val before = Files.readAllBytes(f.file)
            assertNull(ok(db.inspectOwnerState(OWNER)).target)
            assertFalse(ok(db.inspectOwnerState(OTHER)).hasRecords)
            ok(db.validateEmptyRetirement(OTHER, current))
            f.assertReadOnly()
            assertContentEquals(before, Files.readAllBytes(f.file))
            assertTrue(oldKey in f.vault.keys)
            assertNotNull(ok(db.resume(OTHER)))
            assertFalse(oldKey in f.vault.keys)
            assertEquals(listOf(oldKey), f.vault.deleted)
        }
    }

    @Test fun inactiveOrMissingOwnerWithStrayRowsIsCorruptNotAnEmptyDiagnostic() = runBlocking {
        for (change in listOf("UPDATE feedme_owners SET active=0,generation=2", "DELETE FROM feedme_owners")) {
            fixture { f ->
                val db = f.open()
                ok(ok(db.activate(OWNER)).commit(OWNER, listOf(put())))
                f.raw("PRAGMA foreign_keys=OFF", change)
                f.observe()
                rejected(FailureReason.STORAGE_FAILURE, db.inspectOwnerState(OWNER))
                f.assertReadOnly()
            }
        }
    }

    @Test fun missingActiveKeyBlocksInspectionButExactInterruptedEmptyRetirementCanResume() = runBlocking {
        fixture { f ->
            val db = f.open()
            ok(db.activate(OWNER))
            val target = assertNotNull(ok(db.inspectOwnerState(OWNER)).target)
            f.vault.keys.clear()
            f.observe()
            rejected(FailureReason.STORAGE_FAILURE, db.inspectOwnerState(OWNER))
            ok(db.validateEmptyRetirement(OWNER, target))
            f.assertReadOnly()
            ok(db.recoverEmptyRetirement(target))
            assertNull(ok(db.inspectOwnerState(OWNER)).target)
            assertEquals(1, f.vault.creates)
        }
    }

    @Test fun emptyRetirementDeletesExactKeyBeforeSqlAndLeavesOtherOwnerUntouched() = runBlocking {
        fixture { f ->
            val db = f.open()
            val empty = ok(db.activate(OWNER))
            val key = f.vault.keys.keys.single()
            val target = assertNotNull(ok(db.inspectOwnerState(OWNER)).target)
            val other = ok(db.activate(OTHER))
            ok(other.commit(OTHER, listOf(put("other-owner-private-data"))))
            f.connection.beforeMutation = { assertFalse(key in f.vault.keys) }
            ok(db.recoverEmptyRetirement(target))
            f.connection.beforeMutation = null
            assertEquals(listOf(key), f.vault.deleted)
            assertNull(ok(db.inspectOwnerState(OWNER)).target)
            assertRecord(other, OTHER, "other-owner-private-data")
            rejected(FailureReason.STALE_SESSION, empty.commit(OWNER, listOf(put())))
            assertEquals(1, f.vault.keys.size)
        }
    }

    @Test fun nonemptyRejectionPrecedesFenceAndKeyDeletionAndPreservesWritableHandle() = runBlocking {
        fixture { f ->
            val db = f.open()
            val store = ok(db.activate(OWNER))
            val target = assertNotNull(ok(db.inspectOwnerState(OWNER)).target)
            ok(store.commit(OWNER, listOf(put("must-survive"))))
            f.observe()
            val before = Files.readAllBytes(f.file)
            rejected(FailureReason.CONFLICT, db.validateEmptyRetirement(OWNER, target))
            rejected(FailureReason.CONFLICT, db.recoverEmptyRetirement(target))
            f.assertReadOnly()
            assertContentEquals(before, Files.readAllBytes(f.file))
            assertRecord(store, OWNER, "must-survive")
            ok(store.commit(OWNER, listOf(put("still-writable", 1))))
        }
    }

    @Test fun tombstoneRejectionDoesNotFenceHandleOrEraseRevisionHistory() = runBlocking {
        fixture { f ->
            val db = f.open()
            val store = ok(db.activate(OWNER))
            val target = assertNotNull(ok(db.captureRetirement(OWNER)))
            ok(store.commit(OWNER, listOf(put())))
            ok(store.commit(OWNER, listOf(StoreMutation.Delete(KEY, 1))))
            f.observe()
            rejected(FailureReason.CONFLICT, db.validateEmptyRetirement(OWNER, target))
            rejected(FailureReason.CONFLICT, db.recoverEmptyRetirement(target))
            f.assertReadOnly()
            assertEquals(2L, f.scalar("SELECT revision FROM feedme_records"))
            assertEquals(3L, ok(store.commit(OWNER, listOf(put("recreated-after-rejection"))))[KEY])
        }
    }

    @Test fun successfulPreflightIsNotAuthorityToEraseARecordWrittenBeforeRecovery() = runBlocking {
        fixture { f ->
            val db = f.open()
            val store = ok(db.activate(OWNER))
            val target = assertNotNull(ok(db.inspectOwnerState(OWNER)).target)
            ok(db.validateEmptyRetirement(OWNER, target))
            ok(store.commit(OWNER, listOf(put("written-after-preflight"))))
            f.observe()
            rejected(FailureReason.CONFLICT, db.recoverEmptyRetirement(target))
            f.assertReadOnly()
            assertRecord(store, OWNER, "written-after-preflight")
        }
    }

    @Test fun oldTargetCannotRetireNewSameOwnerIncarnationEvenWhenNewOwnerIsEmpty() = runBlocking {
        fixture { f ->
            val db = f.open()
            ok(db.activate(OWNER))
            val old = assertNotNull(ok(db.inspectOwnerState(OWNER)).target)
            ok(db.recoverEmptyRetirement(old))
            val newStore = ok(db.activate(OWNER))
            val current = assertNotNull(ok(db.inspectOwnerState(OWNER)).target)
            assertFalse(old.copyForStorage().contentEquals(current.copyForStorage()))
            f.observe()
            rejected(FailureReason.STALE_SESSION, db.validateEmptyRetirement(OWNER, old))
            rejected(FailureReason.STALE_SESSION, db.recoverEmptyRetirement(old))
            f.assertReadOnly()
            ok(newStore.commit(OWNER, listOf(put("new-incarnation-survives"))))
        }
    }

    @Test fun malformedMacForeignInstallAndWrongScopeCannotDeleteOrFenceTheExactOwner() = runBlocking {
        fixture { f ->
            val db = f.open()
            val store = ok(db.activate(OWNER))
            val target = assertNotNull(ok(db.inspectOwnerState(OWNER)).target)
            val original = target.copyForStorage()
            val bad = listOf(ByteArray(137), original.copyOf().also { it[136] = (it[136].toInt() xor 1).toByte() })
            f.observe()
            for (bytes in bad) {
                rejected(FailureReason.INVALID_DATA, db.validateEmptyRetirement(OWNER, StateRetirementTarget(bytes)))
                rejected(FailureReason.INVALID_DATA, db.recoverEmptyRetirement(StateRetirementTarget(bytes)))
            }
            rejected(FailureReason.STALE_SESSION, db.validateEmptyRetirement(OTHER, target))
            fixture { foreign ->
                val foreignDb = foreign.open()
                ok(foreignDb.activate(OWNER))
                val foreignTarget = assertNotNull(ok(foreignDb.inspectOwnerState(OWNER)).target)
                rejected(FailureReason.INVALID_DATA, db.recoverEmptyRetirement(foreignTarget))
            }
            f.assertReadOnly()
            ok(store.commit(OWNER, listOf(put())))
        }
    }

    @Test fun sharedActiveKeyReferenceFailsBeforeFenceAndDoesNotDeleteEitherOwnerKey() = runBlocking {
        fixture { f ->
            val db = f.open()
            ok(db.activate(OWNER))
            val target = assertNotNull(ok(db.inspectOwnerState(OWNER)).target)
            val key = f.vault.keys.keys.single()
            ok(db.activate(OTHER))
            f.raw("UPDATE feedme_owners SET key_id='$key'")
            f.observe()
            rejected(FailureReason.STORAGE_FAILURE, db.validateEmptyRetirement(OWNER, target))
            rejected(FailureReason.STORAGE_FAILURE, db.recoverEmptyRetirement(target))
            f.assertReadOnly()
            assertNotNull(ok(db.captureRetirement(OWNER)))
            assertEquals(2, f.vault.keys.size)
        }
    }

    @Test fun keyDeletionFailureCommitsExactRetiredFallbackAndRetryFinishesWithoutNewGeneration() = runBlocking {
        fixture { f ->
            val db = f.open()
            ok(db.activate(OWNER))
            val target = assertNotNull(ok(db.inspectOwnerState(OWNER)).target)
            f.vault.failDelete = true
            rejected(FailureReason.STORAGE_FAILURE, db.recoverEmptyRetirement(target))
            assertEquals(0L, f.scalar("SELECT active FROM feedme_owners"))
            assertEquals(2L, f.scalar("SELECT generation FROM feedme_owners"))
            assertEquals(1L, f.scalar("SELECT count(*) FROM feedme_key_gc"))
            f.vault.failDelete = false
            f.observe()
            ok(db.validateEmptyRetirement(OWNER, target))
            assertNull(ok(db.inspectOwnerState(OWNER)).target)
            f.assertReadOnly()
            ok(db.recoverEmptyRetirement(target))
            assertEquals(2L, f.scalar("SELECT generation FROM feedme_owners"))
            assertTrue(f.vault.keys.isEmpty())
        }
    }

    @Test fun deletedKeyAndFailedSqlCommitReopenAsUnreadableThenExactTargetCanFinish() = runBlocking {
        fixture { f ->
            val db = f.open()
            ok(db.activate(OWNER))
            val encoded = assertNotNull(ok(db.inspectOwnerState(OWNER)).target).copyForStorage()
            f.connection.commitFault = EmptyCommitFault.BEFORE
            rejected(FailureReason.STORAGE_FAILURE, db.recoverEmptyRetirement(StateRetirementTarget(encoded)))
            assertTrue(f.vault.keys.isEmpty())
            assertEquals(1L, f.scalar("SELECT active FROM feedme_owners"))
            rejected(FailureReason.STALE_SESSION, db.inspectOwnerState(OWNER))
            ok(db.close())
            val reopened = f.open()
            rejected(FailureReason.STORAGE_FAILURE, reopened.inspectOwnerState(OWNER))
            f.observe()
            ok(reopened.validateEmptyRetirement(OWNER, StateRetirementTarget(encoded)))
            f.assertReadOnly()
            ok(reopened.recoverEmptyRetirement(StateRetirementTarget(encoded)))
            assertNull(ok(reopened.inspectOwnerState(OWNER)).target)
            assertEquals(1, f.vault.creates)
        }
    }

    @Test fun committedResponseLossAndRepeatedRetirementAreExactAndIdempotentAfterReopen() = runBlocking {
        fixture { f ->
            val db = f.open()
            ok(db.activate(OWNER))
            val target = assertNotNull(ok(db.inspectOwnerState(OWNER)).target)
            f.connection.commitFault = EmptyCommitFault.AFTER
            rejected(FailureReason.OUTCOME_UNKNOWN, db.recoverEmptyRetirement(target))
            ok(db.close())
            val reopened = f.open()
            repeat(3) {
                ok(reopened.validateEmptyRetirement(OWNER, target))
                ok(reopened.recoverEmptyRetirement(target))
            }
            assertEquals(2L, f.scalar("SELECT generation FROM feedme_owners"))
            assertFalse(ok(reopened.inspectOwnerState(OWNER)).hasRecords)
            assertTrue(f.vault.keys.isEmpty())
            assertEquals(1, f.vault.creates)
        }
    }

    @Test fun emptyInspectionAndValidationReadFailureDoNotFenceOwnerOrReturnOutcomeUnknown() = runBlocking {
        fixture { f ->
            val db = f.open()
            val store = ok(db.activate(OWNER))
            val target = assertNotNull(ok(db.inspectOwnerState(OWNER)).target)
            f.observe()
            f.connection.commitFault = EmptyCommitFault.BEFORE
            rejected(FailureReason.STORAGE_FAILURE, db.inspectOwnerState(OWNER))
            f.connection.commitFault = EmptyCommitFault.AFTER
            rejected(FailureReason.STORAGE_FAILURE, db.validateEmptyRetirement(OWNER, target))
            f.assertReadOnly()
            ok(store.commit(OWNER, listOf(put())))
        }
    }

    @Test fun inspectionIsOneReadTransactionAndConcurrentRowInsertionCannotCommitBetweenObservations() = runBlocking {
        fixture { f ->
            val db = f.open()
            ok(db.activate(OWNER))
            f.observe()
            var attempted = false
            f.connection.beforeExistence = {
                attempted = true
                assertTrue(f.connection.inTransaction())
                assertFails { f.raw("PRAGMA busy_timeout=0", "INSERT INTO feedme_records SELECT owner_tag,'${"0".repeat(64)}',1,1,NULL FROM feedme_owners") }
            }
            val result = ok(db.inspectOwnerState(OWNER))
            f.connection.beforeExistence = null
            assertTrue(attempted)
            assertNotNull(result.target)
            assertFalse(result.hasRecords)
            assertEquals(1, f.connection.executed.count { it == "BEGIN" })
            assertEquals(1, f.connection.executed.count { it == "COMMIT" })
            f.assertReadOnly()
            assertEquals(0L, f.scalar("SELECT count(*) FROM feedme_records"))
        }
    }

    @Test fun cancellationBeforeDispatchHasNoEffectsAndClosedManagerDoesNotInspectOrRetire() = runTest {
        fixture { f ->
            val db = f.open(StandardTestDispatcher(testScheduler))
            ok(db.activate(OWNER))
            val target = assertNotNull(ok(db.inspectOwnerState(OWNER)).target)
            f.observe()
            val pending = async(start = CoroutineStart.UNDISPATCHED) { db.recoverEmptyRetirement(target) }
            pending.cancel()
            assertFailsWith<CancellationException> { pending.await() }
            assertTrue(f.connection.executed.isEmpty())
            f.assertReadOnly()
            ok(db.close())
            f.observe()
            rejected(FailureReason.STORAGE_FAILURE, db.inspectOwnerState(OWNER))
            rejected(FailureReason.STORAGE_FAILURE, db.validateEmptyRetirement(OWNER, target))
            rejected(FailureReason.STORAGE_FAILURE, db.recoverEmptyRetirement(target))
            assertTrue(f.connection.executed.isEmpty())
            f.assertReadOnly()
        }
    }

    @Test fun cancellationAfterKeyDeletionCannotResurrectOwnerAndExactRecoveryRemainsPossible() = runTest {
        fixture { f ->
            val db = f.open(StandardTestDispatcher(testScheduler))
            ok(db.activate(OWNER))
            val target = assertNotNull(ok(db.inspectOwnerState(OWNER)).target)
            val pending = async(start = CoroutineStart.UNDISPATCHED) { db.recoverEmptyRetirement(target) }
            f.vault.afterDelete = { pending.cancel() }
            assertFailsWith<CancellationException> { pending.await() }
            f.vault.afterDelete = null
            assertTrue(f.vault.keys.isEmpty())
            ok(db.close())
            val reopened = f.open(StandardTestDispatcher(testScheduler))
            ok(reopened.validateEmptyRetirement(OWNER, target))
            ok(reopened.recoverEmptyRetirement(target))
            assertNull(ok(reopened.inspectOwnerState(OWNER)).target)
        }
    }

    @Test fun privateInspectionTargetCopiesAreDetachedAndAllResultStringsAreRedacted() = runBlocking {
        fixture { f ->
            val db = f.open()
            ok(db.activate(OWNER))
            val state = ok(db.inspectOwnerState(OWNER))
            val target = assertNotNull(state.target)
            val original = target.copyForStorage()
            target.copyForStorage().fill(0)
            assertContentEquals(original, target.copyForStorage())
            val diagnostics = "$state ${PortResult.Value(state)} $target"
            for (privateText in listOf(OWNER.environment, OWNER.actorId, f.vault.keys.keys.single(), original.copyOfRange(1, 65).decodeToString()))
                assertFalse(privateText in diagnostics)
        }
    }

    private suspend fun fixture(block: suspend (EmptyFixture) -> Unit) {
        val fixture = EmptyFixture()
        try { block(fixture) } finally { fixture.close() }
    }
    private suspend fun assertRecord(store: PrivateStateStore, scope: StorageScope, expected: String) {
        assertContentEquals(expected.encodeToByteArray(), assertNotNull(ok(store.read(scope, KEY))).payload.copyForCodec())
    }
    companion object {
        private val OWNER = StorageScope("empty-retirement-private-env", ActorKind.ACCOUNT, "empty-owner-a")
        private val OTHER = OWNER.copy(actorId = "nonempty-owner-b")
        private val KEY = RecordKey("private-draft", "private-record")
        private fun bytes(value: String) = PrivateBytes(value.encodeToByteArray())
        private fun put(text: String = "private-content", revision: Long? = null) = StoreMutation.Put(KEY, revision, 1, bytes(text))
        private fun rejected(reason: FailureReason, result: PortResult<*>) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
    }
}

private fun <T> ok(result: PortResult<T>): T = when (result) {
    is PortResult.Value -> result.value
    is PortResult.Failure -> fail("Expected empty retirement success, got ${result.reason}")
}

private class EmptyFixture {
    private val directory = Files.createTempDirectory("feedme-empty-retirement-test-")
    val file = directory.resolve("state.sqlite")
    val vault = EmptyVault()
    private val databases = mutableListOf<EncryptedStateDatabase>()
    lateinit var connection: EmptyConnection
    private var creates = 0
    private var seals = 0
    suspend fun open(dispatcher: CoroutineDispatcher = Dispatchers.IO): EncryptedStateDatabase {
        connection = EmptyConnection(BundledSQLiteDriver().open(file.toString()))
        return ok(EncryptedStateDatabase.open(connection, vault, dispatcher)).also { databases += it }
    }
    fun raw(vararg queries: String) = BundledSQLiteDriver().open(file.toString()).use { c ->
        queries.forEach { sql -> c.prepare(sql).use { while (it.step()) { } } }
    }
    fun scalar(sql: String): Long = BundledSQLiteDriver().open(file.toString()).use { c ->
        c.prepare(sql).use { assertTrue(it.step()); it.getLong(0) }
    }
    fun observe() { connection.executed.clear(); vault.deleted.clear(); vault.opens = 0; creates = vault.creates; seals = vault.seals }
    fun assertReadOnly() {
        assertEquals(creates, vault.creates)
        assertEquals(seals, vault.seals)
        assertTrue(vault.deleted.isEmpty())
        assertFalse(connection.executed.any { mutation(it) || it == "BEGIN IMMEDIATE" || "feedme_key_gc" in it })
    }
    suspend fun close() {
        try { databases.forEach { it.close() } } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

private enum class EmptyCommitFault { BEFORE, AFTER }
private fun mutation(sql: String) = listOf("INSERT ", "UPDATE ", "DELETE ", "CREATE ", "ALTER ", "DROP ").any(sql.trim().uppercase()::startsWith)
private class EmptyConnection(private val delegate: SQLiteConnection) : SQLiteConnection by delegate {
    val executed = mutableListOf<String>()
    var commitFault: EmptyCommitFault? = null
    var beforeMutation: (() -> Unit)? = null
    var beforeExistence: (() -> Unit)? = null
    override fun prepare(sql: String): SQLiteStatement {
        val statement = delegate.prepare(sql)
        return object : SQLiteStatement by statement {
            override fun step(): Boolean {
                executed += sql
                if (mutation(sql)) beforeMutation?.invoke()
                if (sql.startsWith("SELECT EXISTS(SELECT 1 FROM feedme_records")) beforeExistence?.invoke()
                val fault = if (sql == "COMMIT") commitFault.also { commitFault = null } else null
                if (fault == EmptyCommitFault.BEFORE) error("Injected precommit failure")
                val result = statement.step()
                if (fault == EmptyCommitFault.AFTER) error("Injected committed response loss")
                return result
            }
        }
    }
}

/** Test-only keys survive close/reopen, with exact deletion and acknowledgement fault injection. */
private class EmptyVault : StateVault {
    private val random = SecureRandom()
    private val indexKey = SecretKeySpec(ByteArray(32).also(random::nextBytes), "HmacSHA256")
    val keys = mutableMapOf<String, SecretKey>()
    val deleted = mutableListOf<String>()
    var creates = 0
    var seals = 0
    var opens = 0
    var failDelete = false
    var afterDelete: (() -> Unit)? = null
    override fun index(input: ByteArray) = Mac.getInstance("HmacSHA256").run { init(indexKey); doFinal(input) }
    override fun createOwnerKey(): String {
        creates++
        return UUID.randomUUID().toString().replace("-", "").also {
            keys[it] = KeyGenerator.getInstance("AES").apply { init(256, random) }.generateKey()
        }
    }
    override fun hasOwnerKey(keyId: String) = keyId in keys
    override fun seal(keyId: String, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        seals++
        val nonce = ByteArray(12).also(random::nextBytes)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, keys[keyId] ?: throw StateVaultException(), GCMParameterSpec(128, nonce))
            updateAAD(associatedData)
            byteArrayOf(1) + nonce + doFinal(plaintext)
        }
    }
    override fun open(keyId: String, ciphertext: ByteArray, associatedData: ByteArray): ByteArray = try {
        opens++
        if (ciphertext.size < 29 || ciphertext[0] != 1.toByte()) throw StateVaultException()
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, keys[keyId] ?: throw StateVaultException(), GCMParameterSpec(128, ciphertext.copyOfRange(1, 13)))
            updateAAD(associatedData)
            doFinal(ciphertext, 13, ciphertext.size - 13)
        }
    } catch (_: Exception) { throw StateVaultException() }
    override fun deleteOwnerKey(keyId: String) {
        deleted += keyId
        if (failDelete) throw StateVaultException()
        keys.remove(keyId)
        afterDelete?.invoke()
    }
}
