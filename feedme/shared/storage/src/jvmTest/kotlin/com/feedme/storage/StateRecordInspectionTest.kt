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

/** Real SQLite diagnostics with fixture-only JCA keys, not native-vault or authentication proof. */
class StateRecordInspectionTest {
    @Test fun missingOwnerDoesNotActivateCreateKeysWriteOrClaimOtherOwnersAreAbsent() = runBlocking {
        fixture { f ->
            val db = f.open()
            f.resetObservations()
            val before = Files.readAllBytes(f.file)
            val result = value(db.inspectRecord(OWNER, KEY))
            assertNull(result.target)
            assertNull(result.record)
            assertEquals(0, f.vault.creates)
            assertTrue(f.vault.keys.isEmpty())
            f.assertReadOnly()
            assertContentEquals(before, Files.readAllBytes(f.file))
            assertEquals(0L, f.scalar("SELECT count(*) FROM feedme_owners"))
        }
    }

    @Test fun activeRecordAndAuthenticatedCurrentTargetAreReadTogetherWithoutFencingItsHandle() = runBlocking {
        fixture { f ->
            val db = f.open()
            val store = value(db.activate(OWNER))
            value(store.commit(OWNER, listOf(put("private-inspected-payload", schema = 7))))
            val expected = assertNotNull(value(db.captureRetirement(OWNER))).copyForStorage()
            val creates = f.vault.creates
            val seals = f.vault.seals
            val before = Files.readAllBytes(f.file)
            f.resetObservations()
            val result = value(db.inspectRecord(OWNER, KEY))
            assertContentEquals(expected, assertNotNull(result.target).copyForStorage())
            assertRecord(assertNotNull(result.record), "private-inspected-payload", 1, 7)
            f.assertReadOnly()
            assertEquals(creates, f.vault.creates)
            assertEquals(seals, f.vault.seals)
            assertContentEquals(before, Files.readAllBytes(f.file))
            value(db.validateRetirement(OWNER, assertNotNull(result.target)))
            assertRecord(assertNotNull(value(store.read(OWNER, KEY))), "private-inspected-payload", 1, 7)
            value(store.commit(OWNER, listOf(put("still-writable", revision = 1))))
        }
    }

    @Test fun activeMissingAndDeletedRecordKeepTargetButDoNotResurrectPayloadOrRevision() = runBlocking {
        fixture { f ->
            val db = f.open()
            val store = value(db.activate(OWNER))
            val first = value(db.inspectRecord(OWNER, KEY))
            val firstTarget = assertNotNull(first.target)
            assertNull(first.record)
            value(store.commit(OWNER, listOf(put("deleted-private-data"))))
            value(store.commit(OWNER, listOf(StoreMutation.Delete(KEY, 1))))
            f.resetObservations()
            val deleted = value(db.inspectRecord(OWNER, KEY))
            assertContentEquals(firstTarget.copyForStorage(), assertNotNull(deleted.target).copyForStorage())
            assertNull(deleted.record)
            f.assertReadOnly()
            assertEquals(2L, f.scalar("SELECT revision FROM feedme_records"))
            assertEquals(1L, f.scalar("SELECT count(*) FROM feedme_records WHERE payload IS NULL"))
        }
    }

    @Test fun environmentKindActorCollectionAndRecordIdentityRemainExact() = runBlocking {
        fixture { f ->
            val db = f.open()
            value(value(db.activate(OWNER)).commit(OWNER, listOf(put("only-this-owner-and-record"))))
            val scopes = listOf(OTHER, OWNER.copy(environment = "other-environment"), OWNER.copy(actorKind = ActorKind.GUEST), OWNER.copy(actorKind = ActorKind.DEMO))
            f.resetObservations()
            for (scope in scopes) {
                val missing = value(db.inspectRecord(scope, KEY))
                assertNull(missing.target)
                assertNull(missing.record)
            }
            for (key in listOf(KEY.copy(collection = "other-collection"), KEY.copy(id = "other-record"))) {
                val missing = value(db.inspectRecord(OWNER, key))
                assertNotNull(missing.target)
                assertNull(missing.record)
            }
            f.assertReadOnly()
            assertEquals(1L, f.scalar("SELECT count(*) FROM feedme_owners"))
            assertRecord(assertNotNull(value(db.inspectRecord(OWNER, KEY)).record), "only-this-owner-and-record")
        }
    }

    @Test fun inspectionNeverRunsRealQueuedKeyGcForRetiredActiveOrMissingRequestedOwners() = runBlocking {
        fixture { f ->
            val db = f.open()
            val old = value(db.activate(OWNER))
            val oldKey = f.vault.keys.keys.single()
            value(old.commit(OWNER, listOf(put("retired-owner"))))
            val other = value(db.activate(OTHER))
            value(other.commit(OTHER, listOf(put("still-active-owner"))))
            f.vault.failDelete = true
            failure(FailureReason.STORAGE_FAILURE, old.eraseScope(OWNER))
            f.vault.failDelete = false
            assertTrue(oldKey in f.vault.keys)
            assertEquals(1L, f.scalar("SELECT count(*) FROM feedme_key_gc"))
            val before = Files.readAllBytes(f.file)
            val keys = f.vault.keys.keys.toSet()
            f.resetObservations()
            val retired = value(db.inspectRecord(OWNER, KEY))
            assertNull(retired.target)
            assertNull(retired.record)
            assertRecord(assertNotNull(value(db.inspectRecord(OTHER, KEY)).record), "still-active-owner")
            assertNull(value(db.inspectRecord(OWNER.copy(actorId = "never-created"), KEY)).target)
            f.assertReadOnly()
            assertEquals(keys, f.vault.keys.keys)
            assertContentEquals(before, Files.readAllBytes(f.file))
            assertEquals(1L, f.scalar("SELECT count(*) FROM feedme_key_gc"))
            // Demonstrate this is executable cleanup, not a fixture row that could never delete.
            assertNotNull(value(db.resume(OTHER)))
            assertFalse(oldKey in f.vault.keys)
            assertEquals(listOf(oldKey), f.vault.deleted)
            assertEquals(0L, f.scalar("SELECT count(*) FROM feedme_key_gc"))
        }
    }

    @Test fun reactivatedSameOwnerReturnsOnlyNewGenerationKeyAndRecordNotOldCapability() = runBlocking {
        fixture { f ->
            val db = f.open()
            val old = value(db.activate(OWNER))
            value(old.commit(OWNER, listOf(put("old-incarnation"))))
            val prior = assertNotNull(value(db.inspectRecord(OWNER, KEY)).target).copyForStorage()
            value(old.eraseScope(OWNER))
            assertNull(value(db.inspectRecord(OWNER, KEY)).target)
            val current = value(db.activate(OWNER))
            value(current.commit(OWNER, listOf(put("new-incarnation"))))
            f.resetObservations()
            val result = value(db.inspectRecord(OWNER, KEY))
            val next = assertNotNull(result.target).copyForStorage()
            assertFalse(prior.contentEquals(next))
            assertEquals(1L, generation(prior))
            assertEquals(3L, generation(next))
            assertFalse(prior.copyOfRange(73, 105).contentEquals(next.copyOfRange(73, 105)))
            assertRecord(assertNotNull(result.record), "new-incarnation")
            f.assertReadOnly()
            failure(FailureReason.STALE_SESSION, old.read(OWNER, KEY))
        }
    }

    @Test fun locallyFencedActiveRowCannotBeInspectedEvenWhenFailedKeyDeletionKeptItReadable() = runBlocking {
        fixture { f ->
            val db = f.open()
            val store = value(db.activate(OWNER))
            value(store.commit(OWNER, listOf(put("fenced-private-data"))))
            f.vault.failDelete = true
            f.connection.failNextCommit = true
            failure(FailureReason.STORAGE_FAILURE, store.eraseScope(OWNER))
            f.vault.failDelete = false
            assertEquals(1L, f.scalar("SELECT active FROM feedme_owners"))
            assertEquals(1, f.vault.keys.size)
            f.resetObservations()
            failure(FailureReason.STALE_SESSION, db.inspectRecord(OWNER, KEY))
            f.assertReadOnly()
            assertEquals(0, f.vault.opens)
        }
    }

    @Test fun missingExistingKeyFailsClosedEvenWhenRequestedRecordIsAbsentAndNeverHeals() = runBlocking {
        fixture { f ->
            val db = f.open()
            value(value(db.activate(OWNER)).commit(OWNER, listOf(put("lost-key-data"))))
            f.vault.keys.clear()
            val creates = f.vault.creates
            f.resetObservations()
            failure(FailureReason.STORAGE_FAILURE, db.inspectRecord(OWNER, KEY))
            failure(FailureReason.STORAGE_FAILURE, db.inspectRecord(OWNER, KEY.copy(id = "absent")))
            f.assertReadOnly()
            assertEquals(creates, f.vault.creates)
            assertTrue(f.vault.keys.isEmpty())
            value(db.close())
            val reopened = f.open()
            failure(FailureReason.STORAGE_FAILURE, reopened.inspectRecord(OWNER, KEY))
            assertEquals(creates, f.vault.creates)
        }
    }

    @Test fun ciphertextRevisionSchemaLengthAndTypeCorruptionCannotReturnAnApparentlyValidTargetOnly() = runBlocking {
        for (assignment in listOf("payload=zeroblob(29)", "revision=revision+1", "schema_version=schema_version+1", "payload=zeroblob(28)", "payload='private-corrupt-text'", "revision=0", "schema_version=2147483648")) {
            fixture { f ->
                val db = f.open()
                value(value(db.activate(OWNER)).commit(OWNER, listOf(put("authenticated-original"))))
                f.raw("PRAGMA ignore_check_constraints=ON", "UPDATE feedme_records SET $assignment")
                val before = Files.readAllBytes(f.file)
                f.resetObservations()
                failure(FailureReason.STORAGE_FAILURE, db.inspectRecord(OWNER, KEY))
                f.assertReadOnly()
                assertContentEquals(before, Files.readAllBytes(f.file))
            }
        }
    }

    @Test fun malformedOrUnretirableOwnerCannotBecomeAMissingOwnerDiagnostic() = runBlocking {
        for (assignment in listOf("generation=0", "generation=9223372036854775807", "active=7", "key_id='INVALID-KEY'")) {
            fixture { f ->
                val db = f.open()
                value(value(db.activate(OWNER)).commit(OWNER, listOf(put("strict-owner-metadata"))))
                f.raw("PRAGMA ignore_check_constraints=ON", "UPDATE feedme_owners SET $assignment")
                f.resetObservations()
                failure(FailureReason.STORAGE_FAILURE, db.inspectRecord(OWNER, KEY))
                f.assertReadOnly()
            }
        }
    }

    @Test fun targetAndRecordShareOneSqliteSnapshotThatRejectsInterleavingWriterCommit() = runBlocking {
        fixture { f ->
            val db = f.open()
            val store = value(db.activate(OWNER))
            value(store.commit(OWNER, listOf(put("one-read-snapshot", schema = 9))))
            val expected = assertNotNull(value(db.captureRetirement(OWNER))).copyForStorage()
            f.resetObservations()
            var attempted = false
            f.connection.beforeRecord = {
                attempted = true
                assertTrue(f.connection.inTransaction())
                assertFails {
                    f.raw("PRAGMA busy_timeout=0", "UPDATE feedme_owners SET generation=generation+1")
                }
            }
            val result = value(db.inspectRecord(OWNER, KEY))
            f.connection.beforeRecord = null
            assertTrue(attempted)
            assertContentEquals(expected, assertNotNull(result.target).copyForStorage())
            assertRecord(assertNotNull(result.record), "one-read-snapshot", 1, 9)
            assertEquals(1, f.connection.executed.count { it == "BEGIN" })
            assertEquals(1, f.connection.executed.count { it == "COMMIT" })
            assertFalse(f.connection.inTransaction())
            f.assertReadOnly()
            assertEquals(1L, f.scalar("SELECT generation FROM feedme_owners"))
        }
    }

    @Test fun returnedBytesAreDetachedFromLaterCommitsAndAllDiagnosticStringsAreRedacted() = runBlocking {
        fixture { f ->
            val db = f.open()
            val store = value(db.activate(OWNER))
            value(store.commit(OWNER, listOf(put("private-detached-marker"))))
            val first = value(db.inspectRecord(OWNER, KEY))
            val record = assertNotNull(first.record)
            val target = assertNotNull(first.target)
            val originalTarget = target.copyForStorage()
            target.copyForStorage().fill(0)
            record.payload.copyForCodec().fill(0)
            value(store.commit(OWNER, listOf(put("replacement-marker", revision = 1))))
            assertRecord(record, "private-detached-marker")
            assertContentEquals(originalTarget, target.copyForStorage())
            assertRecord(assertNotNull(value(db.inspectRecord(OWNER, KEY)).record), "replacement-marker", 2)
            val diagnostics = "$first ${PortResult.Value(first)} $target"
            for (marker in listOf(OWNER.environment, OWNER.actorId, KEY.collection, KEY.id, "private-detached-marker", f.vault.keys.keys.single(), originalTarget.copyOfRange(1, 65).decodeToString()))
                assertFalse(marker in diagnostics)
        }
    }

    @Test fun invalidRecordKeysAreRejectedBeforeAnyDatabaseOrVaultRead() = runBlocking {
        fixture { f ->
            val db = f.open()
            val keys = listOf(KEY.copy(collection = "x".repeat(129)), KEY.copy(id = "x".repeat(513)), KEY.copy(collection = "control\nname"), KEY.copy(id = "bad\uD800"))
            f.resetObservations()
            val indexes = f.vault.indexes
            for (key in keys) failure(FailureReason.INVALID_DATA, db.inspectRecord(OWNER, key))
            assertTrue(f.connection.executed.isEmpty())
            assertEquals(indexes, f.vault.indexes)
            f.assertReadOnly()
        }
    }

    @Test fun indexAndDecryptionProviderFailuresReturnOnlySanitizedFailureAndNoMutation() = runBlocking {
        fixture { f ->
            val db = f.open()
            value(value(db.activate(OWNER)).commit(OWNER, listOf(put("private-error-marker"))))
            f.resetObservations()
            f.vault.indexFailure = true
            val indexFailure = db.inspectRecord(OWNER, KEY)
            failure(FailureReason.STORAGE_FAILURE, indexFailure)
            f.vault.indexFailure = false
            f.vault.openFailure = true
            val openFailure = db.inspectRecord(OWNER, KEY)
            failure(FailureReason.STORAGE_FAILURE, openFailure)
            assertFalse("canary" in "$indexFailure $openFailure")
            assertFalse("private-error-marker" in "$indexFailure $openFailure")
            f.assertReadOnly()
        }
    }

    @Test fun oversizedDecryptedOutputFailsClosedAndTemporaryPlaintextIsCleared() = runBlocking {
        fixture { f ->
            val db = f.open()
            value(value(db.activate(OWNER)).commit(OWNER, listOf(put("bounded-record"))))
            val oversized = ByteArray(EncryptedStateDatabase.MAX_RECORD_BYTES + 1) { 42 }
            f.vault.openOverride = { oversized }
            f.resetObservations()
            failure(FailureReason.STORAGE_FAILURE, db.inspectRecord(OWNER, KEY))
            assertTrue(oversized.all { it == 0.toByte() })
            f.assertReadOnly()
        }
    }

    @Test fun readCommitFailureRollsBackWithoutOutcomeUnknownAndClosedDatabaseDoesNotReadAgain() = runBlocking {
        fixture { f ->
            val db = f.open()
            value(value(db.activate(OWNER)).commit(OWNER, listOf(put("read-transaction-failure"))))
            f.resetObservations()
            f.connection.failNextCommit = true
            failure(FailureReason.STORAGE_FAILURE, db.inspectRecord(OWNER, KEY))
            assertFalse(f.connection.inTransaction())
            f.assertReadOnly()
            assertRecord(assertNotNull(value(db.inspectRecord(OWNER, KEY)).record), "read-transaction-failure")
            value(db.close())
            f.resetObservations()
            failure(FailureReason.STORAGE_FAILURE, db.inspectRecord(OWNER, KEY))
            assertTrue(f.connection.executed.isEmpty())
        }
    }

    @Test fun cancellationBeforeOwnerDispatcherRunsPerformsNoReadOrMutation() = runTest {
        fixture { f ->
            val db = f.open(StandardTestDispatcher(testScheduler))
            value(value(db.activate(OWNER)).commit(OWNER, listOf(put("cancel-before-inspect"))))
            f.resetObservations()
            val pending = async(start = CoroutineStart.UNDISPATCHED) { db.inspectRecord(OWNER, KEY) }
            pending.cancel()
            assertFailsWith<CancellationException> { pending.await() }
            assertTrue(f.connection.executed.isEmpty())
            f.assertReadOnly()
        }
    }

    @Test fun cancellationDuringRecordReadPropagatesRollsBackAndDoesNotFenceExistingOwner() = runBlocking {
        fixture { f ->
            val db = f.open()
            val store = value(db.activate(OWNER))
            value(store.commit(OWNER, listOf(put("cancel-during-inspect"))))
            f.resetObservations()
            f.connection.beforeRecord = { throw CancellationException("test-only cancellation") }
            assertFailsWith<CancellationException> { db.inspectRecord(OWNER, KEY) }
            f.connection.beforeRecord = null
            assertFalse(f.connection.inTransaction())
            f.assertReadOnly()
            assertRecord(assertNotNull(value(store.read(OWNER, KEY))), "cancel-during-inspect")
            assertNotNull(value(db.captureRetirement(OWNER)))
        }
    }

    private suspend fun fixture(block: suspend (InspectionFixture) -> Unit) {
        val f = InspectionFixture()
        try { block(f) } finally { f.close() }
    }

    companion object {
        private val OWNER = StorageScope("private-inspection-environment", ActorKind.ACCOUNT, "private-owner-a")
        private val OTHER = OWNER.copy(actorId = "private-owner-b")
        private val KEY = RecordKey("private-inspection-collection", "private-inspection-record")
        private fun put(text: String, revision: Long? = null, schema: Int = 1) = StoreMutation.Put(KEY, revision, schema, PrivateBytes(text.encodeToByteArray()))
        private fun failure(reason: FailureReason, result: PortResult<*>) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
        private fun assertRecord(record: PrivateRecord, text: String, revision: Long = 1, schema: Int = 1) {
            assertEquals(revision, record.revision)
            assertEquals(schema, record.schemaVersion)
            assertContentEquals(text.encodeToByteArray(), record.payload.copyForCodec())
        }
        private fun generation(bytes: ByteArray): Long = bytes.sliceArray(65..72).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
    }
}

private fun <T> value(result: PortResult<T>): T = when (result) {
    is PortResult.Value -> result.value
    is PortResult.Failure -> fail("Expected inspection success, got ${result.reason}")
}

private class InspectionFixture {
    private val directory = Files.createTempDirectory("feedme-inspection-sqlite-test-")
    val file = directory.resolve("state.sqlite")
    val vault = InspectionVault()
    private val opened = mutableListOf<EncryptedStateDatabase>()
    private var observedCreates = 0
    private var observedSeals = 0
    lateinit var connection: InspectionConnection
    suspend fun open(dispatcher: CoroutineDispatcher = Dispatchers.IO): EncryptedStateDatabase {
        connection = InspectionConnection(BundledSQLiteDriver().open(file.toString()))
        return value(EncryptedStateDatabase.open(connection, vault, dispatcher)).also { opened += it }
    }
    fun raw(vararg queries: String) {
        BundledSQLiteDriver().open(file.toString()).use { c ->
            queries.forEach { sql -> c.prepare(sql).use { while (it.step()) { } } }
        }
    }
    fun scalar(sql: String): Long = BundledSQLiteDriver().open(file.toString()).use { c ->
        c.prepare(sql).use { assertTrue(it.step()); it.getLong(0) }
    }
    fun resetObservations() {
        connection.executed.clear(); vault.deleted.clear(); vault.opens = 0
        observedCreates = vault.creates; observedSeals = vault.seals
    }
    fun assertReadOnly() {
        assertTrue(vault.deleted.isEmpty())
        assertEquals(observedCreates, vault.creates)
        assertEquals(observedSeals, vault.seals)
        assertFalse(connection.executed.any { sql ->
            val normalized = sql.trim().uppercase()
            listOf("INSERT ", "UPDATE ", "DELETE ", "CREATE ", "ALTER ", "DROP ", "BEGIN IMMEDIATE").any(normalized::startsWith) ||
                "feedme_key_gc" in sql
        }, "Inspection must neither mutate nor run key garbage collection")
    }
    suspend fun close() {
        try { opened.forEach { it.close() } } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

private class InspectionConnection(private val delegate: SQLiteConnection) : SQLiteConnection by delegate {
    val executed = mutableListOf<String>()
    var failNextCommit = false
    var beforeRecord: (() -> Unit)? = null
    override fun prepare(sql: String): SQLiteStatement {
        val statement = delegate.prepare(sql)
        return object : SQLiteStatement by statement {
            override fun step(): Boolean {
                executed += sql
                if (sql.startsWith("SELECT revision,schema_version,")) beforeRecord?.invoke()
                if (sql == "COMMIT" && failNextCommit) { failNextCommit = false; error("Injected precommit failure") }
                return statement.step()
            }
        }
    }
}

/** JCA keys exist only inside this disposable test fixture and survive fixture database reopen. */
private class InspectionVault : StateVault {
    private val random = SecureRandom()
    private val indexKey = SecretKeySpec(ByteArray(32).also(random::nextBytes), "HmacSHA256")
    val keys = mutableMapOf<String, SecretKey>()
    val deleted = mutableListOf<String>()
    var creates = 0
    var seals = 0
    var opens = 0
    var indexes = 0
    var failDelete = false
    var indexFailure = false
    var openFailure = false
    var openOverride: (() -> ByteArray)? = null
    override fun index(input: ByteArray): ByteArray {
        indexes++
        if (indexFailure) error("private index provider canary")
        return Mac.getInstance("HmacSHA256").run { init(indexKey); doFinal(input) }
    }
    override fun createOwnerKey(): String {
        creates++
        return UUID.randomUUID().toString().replace("-", "").also { id ->
            keys[id] = KeyGenerator.getInstance("AES").apply { init(256, random) }.generateKey()
        }
    }
    override fun hasOwnerKey(keyId: String): Boolean = keyId in keys
    override fun seal(keyId: String, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        seals++
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keys[keyId] ?: throw StateVaultException(), GCMParameterSpec(128, nonce))
        cipher.updateAAD(associatedData)
        return byteArrayOf(1) + nonce + cipher.doFinal(plaintext)
    }
    override fun open(keyId: String, ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        opens++
        if (openFailure) error("private decryption provider canary")
        openOverride?.let { return it() }
        return try {
            if (ciphertext.size < 29 || ciphertext[0] != 1.toByte()) throw StateVaultException()
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, keys[keyId] ?: throw StateVaultException(), GCMParameterSpec(128, ciphertext.copyOfRange(1, 13)))
                updateAAD(associatedData)
                doFinal(ciphertext, 13, ciphertext.size - 13)
            }
        } catch (_: Exception) { throw StateVaultException() }
    }
    override fun deleteOwnerKey(keyId: String) {
        deleted += keyId
        if (failDelete) throw StateVaultException()
        keys.remove(keyId)
    }
}
