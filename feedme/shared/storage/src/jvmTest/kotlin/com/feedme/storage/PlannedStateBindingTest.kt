package com.feedme.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.feedme.core.ports.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
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

/** Real SQLite/JCA tests; injected exceptions are application faults, not native fsync proof. */
class PlannedStateBindingTest {
    @Test fun emptyExactInspectionReturnsDetachedTargetWithoutRecordHandleWritesOrGarbageCollection() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = selected(db)
            val garbage = "e".repeat(32); f.vault.seed(garbage)
            f.raw("INSERT INTO feedme_key_gc VALUES('$garbage')")
            f.observe(); val before = Files.readAllBytes(f.file)
            val inspection = ok(db.inspectPlannedBinding(OWNER, plan))
            assertNotNull(inspection.target); assertNull(inspection.record)
            val encoded = inspection.target!!.copyForStorage(); val expected = encoded.copyOf(); encoded.fill(0)
            assertContentEquals(expected, inspection.target!!.copyForStorage())
            assertEquals("StateRecordInspection(<redacted>)", inspection.toString())
            f.assertReadOnly(); assertEquals(0, f.vault.opens); assertContentEquals(before, Files.readAllBytes(f.file))
            assertTrue(garbage in f.vault.keys)
        }
    }

    @Test fun firstBindingSupportsBothSchemasAndReturnsTheActuallyReadBackRecord() = runBlocking {
        for (schema in listOf(1, 2)) fixture { f ->
            val db = f.open(); val plan = selected(db); f.observe()
            val receipt = ok(db.bindPlannedActivation(OWNER, plan, schema, BODY))
            val record = assertNotNull(receipt.record)
            assertEquals(1L, record.revision); assertEquals(schema, record.schemaVersion)
            same(BODY, record.payload)
            assertNotNull(receipt.target); assertEquals(0, f.vault.allocations); assertTrue(f.vault.deleted.isEmpty())
            assertEquals(1, f.connection.executed.count { it == "BEGIN IMMEDIATE" })
            assertEquals(1, f.connection.executed.count { it == "BEGIN" })
            assertEquals(2, f.connection.executed.count { it == "COMMIT" })
            assertTrue(f.vault.opens > 0)
            val observed = ok(db.inspectPlannedBinding(OWNER, plan))
            assertEquals(record.revision, observed.record!!.revision)
            same(record.payload, observed.record!!.payload)
        }
    }

    @Test fun everyExactRetryChangesRevisionCiphertextAndAadWhileKeepingTargetAndPayload() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = selected(db)
            var receipt = ok(db.bindPlannedActivation(OWNER, plan, 2, BODY))
            var ciphertext = f.text("SELECT hex(payload) FROM feedme_records")
            val target = receipt.target!!.copyForStorage(); val keys = f.vault.keys.toMap()
            repeat(3) {
                val prior = receipt.record!!.revision
                receipt = ok(db.bindPlannedActivation(OWNER, plan, 2, BODY))
                assertEquals(prior + 1, receipt.record!!.revision)
                assertContentEquals(target, receipt.target!!.copyForStorage()); same(BODY, receipt.record!!.payload)
                val changed = f.text("SELECT hex(payload) FROM feedme_records")
                assertNotEquals(ciphertext, changed); ciphertext = changed
            }
            assertEquals(keys, f.vault.keys); assertTrue(f.vault.deleted.isEmpty())
        }
    }

    @Test fun invalidSchemaAndPayloadBoundsAreRejectedBeforeSqlOrKeyAccess() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = selected(db); f.observe()
            for (schema in listOf(0, 3, Int.MAX_VALUE)) rejected(FailureReason.INVALID_DATA,
                db.bindPlannedActivation(OWNER, plan, schema, BODY))
            for (size in listOf(0, 4097)) rejected(FailureReason.INVALID_DATA,
                db.bindPlannedActivation(OWNER, plan, 2, PrivateBytes(ByteArray(size))))
            f.assertReadOnly(); assertTrue(f.connection.executed.isEmpty())
            val maximum = PrivateBytes(ByteArray(4096) { 42 })
            same(maximum, ok(db.bindPlannedActivation(OWNER, plan, 2, maximum)).record!!.payload)
        }
    }

    @Test fun preparedPartialAndOrdinarilyRetiredPlansCannotBindOrExposeAStore() = runBlocking {
        for (stage in listOf("prepared", "partial", "retired")) fixture { f ->
            val db = f.open(); val plan = ok(db.planActivation(OWNER)); val key = decode(plan).keyId
            if (stage == "partial") f.vault.seed(key)
            if (stage == "retired") {
                ok(db.commitPlannedActivation(OWNER, plan)); ok(assertNotNull(ok(db.resume(OWNER))).eraseScope(OWNER))
            }
            f.observe(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.STALE_SESSION, db.inspectPlannedBinding(OWNER, plan))
            rejected(FailureReason.STALE_SESSION, db.bindPlannedActivation(OWNER, plan, 2, BODY))
            f.assertReadOnly(allowWriteTransaction = true); assertContentEquals(before, Files.readAllBytes(f.file))
        }
    }

    @Test fun selectedMissingOrUnusableKeyNeverRegeneratesOrOverwritesBinding() = runBlocking {
        for (bound in listOf(false, true)) for (unusable in listOf(false, true)) fixture { f ->
            val db = f.open(); val plan = selected(db); if (bound) ok(db.bindPlannedActivation(OWNER, plan, 2, BODY))
            val key = decode(plan).keyId
            if (unusable) f.vault.unusable += key else f.vault.keys.remove(key)
            f.observe(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.STORAGE_FAILURE, db.inspectPlannedBinding(OWNER, plan))
            rejected(FailureReason.STORAGE_FAILURE, db.bindPlannedActivation(OWNER, plan, 2, BODY))
            f.assertReadOnly(allowWriteTransaction = true); assertContentEquals(before, Files.readAllBytes(f.file))
        }
    }

    @Test fun differentPayloadOrSchemaCannotReplaceTheFirstBinding() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = selected(db); val first = ok(db.bindPlannedActivation(OWNER, plan, 2, BODY))
            f.observe(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.CONFLICT, db.bindPlannedActivation(OWNER, plan, 1, BODY))
            rejected(FailureReason.CONFLICT, db.bindPlannedActivation(OWNER, plan, 2, bytes("replacement")))
            f.assertReadOnly(allowWriteTransaction = true); assertContentEquals(before, Files.readAllBytes(f.file))
            assertEquals(first.record!!.revision, ok(db.inspectPlannedBinding(OWNER, plan)).record!!.revision)
        }
    }

    @Test fun anyOtherRecordOrTombstoneBlocksInitialBindingAndReplay() = runBlocking {
        for (bound in listOf(false, true)) for (tombstone in listOf(false, true)) fixture { f ->
            val db = f.open(); val plan = selected(db); if (bound) ok(db.bindPlannedActivation(OWNER, plan, 2, BODY))
            val store = assertNotNull(ok(db.resume(OWNER)))
            ok(store.commit(OWNER, listOf(StoreMutation.Put(DOMAIN, null, 1, bytes("private-data")))))
            if (tombstone) ok(store.commit(OWNER, listOf(StoreMutation.Delete(DOMAIN, 1))))
            f.observe(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.CONFLICT, db.inspectPlannedBinding(OWNER, plan))
            rejected(FailureReason.CONFLICT, db.bindPlannedActivation(OWNER, plan, 2, BODY))
            f.assertReadOnly(allowWriteTransaction = true); assertContentEquals(before, Files.readAllBytes(f.file))
        }
    }

    @Test fun reservedTombstoneAndCorruptOrUnknownBindingAreNeverRepaired() = runBlocking {
        for (damage in listOf("tombstone", "ciphertext", "schema")) fixture { f ->
            val db = f.open(); val plan = selected(db); ok(db.bindPlannedActivation(OWNER, plan, 2, BODY))
            f.raw(when (damage) { "tombstone" -> "UPDATE feedme_records SET payload=NULL";
                "schema" -> "UPDATE feedme_records SET schema_version=99"; else -> "UPDATE feedme_records SET payload=zeroblob(29)" })
            val reason = if (damage == "ciphertext") FailureReason.STORAGE_FAILURE else FailureReason.CONFLICT
            f.observe(); val before = Files.readAllBytes(f.file)
            rejected(reason, db.inspectPlannedBinding(OWNER, plan))
            rejected(reason, db.bindPlannedActivation(OWNER, plan, 2, BODY))
            f.assertReadOnly(allowWriteTransaction = true); assertContentEquals(before, Files.readAllBytes(f.file))
        }
    }

    @Test fun scopeMacForeignInstallAndNewerOwnerCannotCrossBind() = runBlocking {
        fixture { f -> fixture { foreign ->
            val db = f.open(); val plan = selected(db); val foreignDb = foreign.open()
            val altered = plan.copyForStorage(); altered[169] = (altered[169].toInt() xor 1).toByte()
            for (candidate in listOf(StateActivationPlan(altered), StateActivationPlan(ByteArray(170)))) {
                rejected(FailureReason.INVALID_DATA, db.bindPlannedActivation(OWNER, candidate, 2, BODY))
            }
            rejected(FailureReason.INVALID_DATA, db.bindPlannedActivation(OWNER.copy(actorId = "wrong"), plan, 2, BODY))
            rejected(FailureReason.INVALID_DATA, foreignDb.bindPlannedActivation(OWNER, plan, 2, BODY))
            ok(assertNotNull(ok(db.resume(OWNER))).eraseScope(OWNER)); selected(db)
            f.observe(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.STALE_SESSION, db.bindPlannedActivation(OWNER, plan, 2, BODY))
            f.assertReadOnly(allowWriteTransaction = true); assertContentEquals(before, Files.readAllBytes(f.file))
        } }
    }

    @Test fun failedCommitNeverReturnsReceiptAndFreshRetryAlwaysChangesTheObservedRevision() = runBlocking {
        for (fault in BindingCommitFault.entries) for (alreadyBound in listOf(false, true)) fixture { f ->
            val db = f.open(); val plan = selected(db); if (alreadyBound) ok(db.bindPlannedActivation(OWNER, plan, 2, BODY))
            f.connection.commitFault = fault
            rejected(if (fault == BindingCommitFault.AFTER) FailureReason.OUTCOME_UNKNOWN else FailureReason.STORAGE_FAILURE,
                db.bindPlannedActivation(OWNER, plan, 2, BODY))
            val observed = ok(db.inspectPlannedBinding(OWNER, plan)).record
            val next = ok(db.bindPlannedActivation(OWNER, plan, 2, BODY)).record!!
            assertEquals((observed?.revision ?: 0) + 1, next.revision); same(BODY, next.payload)
        }
    }

    @Test fun changedSelectionOrExtraRowsAfterCommitCannotPassExactReadback() = runBlocking {
        for (change in listOf("owner", "row", "binding")) fixture { f ->
            val db = f.open(); val plan = selected(db); val tag = decode(plan).ownerTag
            f.connection.afterWriteCommit = {
                f.connection.afterWriteCommit = null
                when (change) {
                    "owner" -> f.raw("UPDATE feedme_owners SET generation=generation+1")
                    "binding" -> f.raw("DELETE FROM feedme_records")
                    else -> f.raw("INSERT INTO feedme_records VALUES('$tag','${"f".repeat(64)}',1,1,NULL)")
                }
            }
            rejected(when (change) { "owner" -> FailureReason.STALE_SESSION; "row" -> FailureReason.CONFLICT;
                else -> FailureReason.OUTCOME_UNKNOWN }, db.bindPlannedActivation(OWNER, plan, 2, BODY))
        }
    }

    @Test fun readbackFailureAfterRealCommitIsNotPromotedAndRetryMustWriteAgain() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = selected(db)
            f.connection.afterWriteCommit = { f.connection.afterWriteCommit = null; f.connection.failNextReadBegin = true }
            rejected(FailureReason.STORAGE_FAILURE, db.bindPlannedActivation(OWNER, plan, 2, BODY))
            assertEquals(1L, ok(db.inspectPlannedBinding(OWNER, plan)).record!!.revision)
            assertEquals(2L, ok(db.bindPlannedActivation(OWNER, plan, 2, BODY)).record!!.revision)
        }
    }

    @Test fun authenticatedMaximumBindingRevisionCannotWrapResetOrReturnFreshAcknowledgement() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = selected(db); ok(db.bindPlannedActivation(OWNER, plan, 2, BODY))
            val owner = decode(plan); val tag = f.text("SELECT record_tag FROM feedme_records")
            val fields = listOf("feedme.record-aead.v1", owner.ownerTag, owner.selectedGeneration.toString(),
                tag, Long.MAX_VALUE.toString(), "2").map { it.encodeToByteArray() }
            val output = ByteArrayOutputStream()
            DataOutputStream(output).use { wire ->
                wire.writeInt(fields.size); fields.forEach { wire.writeInt(it.size); wire.write(it) }
            }
            val ciphertext = f.vault.seal(owner.keyId, BODY.copyForCodec(), output.toByteArray())
            val hex = ciphertext.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            f.raw("UPDATE feedme_records SET revision=${Long.MAX_VALUE},payload=X'$hex'")
            assertEquals(Long.MAX_VALUE, ok(db.inspectPlannedBinding(OWNER, plan)).record!!.revision)
            f.observe(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.STORAGE_FAILURE, db.bindPlannedActivation(OWNER, plan, 2, BODY))
            f.assertReadOnly(allowWriteTransaction = true); assertContentEquals(before, Files.readAllBytes(f.file))
        }
    }

    @Test fun exactBoundResumePermitsDomainRowsAndDoesNotCollectUnrelatedGarbage() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = selected(db); val receipt = ok(db.bindPlannedActivation(OWNER, plan, 2, BODY))
            val store = ok(db.resumeBound(OWNER, receipt.target!!, receipt.record!!))
            ok(store.commit(OWNER, listOf(StoreMutation.Put(DOMAIN, null, 1, bytes("retained-domain")))))
            val garbage = "e".repeat(32); f.vault.seed(garbage); f.raw("INSERT INTO feedme_key_gc VALUES('$garbage')")
            f.observe(); val before = Files.readAllBytes(f.file)
            val resumed = ok(db.resumeBound(OWNER, receipt.target!!, receipt.record!!))
            f.assertReadOnly(); assertContentEquals(before, Files.readAllBytes(f.file)); assertTrue(garbage in f.vault.keys)
            same(bytes("retained-domain"), assertNotNull(ok(resumed.read(OWNER, DOMAIN))).payload)
        }
    }

    @Test fun staleOrDifferentBindingReceiptCannotPublishAnExactOwner() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = selected(db); val receipt = ok(db.bindPlannedActivation(OWNER, plan, 2, BODY))
            val expected = receipt.record!!; val target = receipt.target!!
            for (other in listOf(expected.copy(revision = 2), expected.copy(schemaVersion = 1), expected.copy(payload = bytes("other"))))
                rejected(FailureReason.STALE_SESSION, db.resumeBound(OWNER, target, other))
            rejected(FailureReason.STALE_SESSION, db.resumeBound(OWNER.copy(actorKind = ActorKind.GUEST), target, expected))
            val changed = target.copyForStorage(); changed[136] = (changed[136].toInt() xor 1).toByte()
            rejected(FailureReason.INVALID_DATA, db.resumeBound(OWNER, StateRetirementTarget(changed), expected))
            ok(db.bindPlannedActivation(OWNER, plan, 2, BODY))
            rejected(FailureReason.STALE_SESSION, db.resumeBound(OWNER, target, expected))
        }
    }

    @Test fun cancelledOrClosedCallsCannotBindOrReturnBoundHandles() = runTest {
        fixture { f ->
            val db = f.open(StandardTestDispatcher(testScheduler)); val plan = selected(db)
            val receipt = ok(db.bindPlannedActivation(OWNER, plan, 2, BODY)); f.observe()
            val pending = async(start = CoroutineStart.UNDISPATCHED) { db.bindPlannedActivation(OWNER, plan, 2, BODY) }
            pending.cancel(); assertFailsWith<CancellationException> { pending.await() }
            f.assertReadOnly(); assertTrue(f.connection.executed.isEmpty())
            ok(db.close()); f.observe()
            rejected(FailureReason.STORAGE_FAILURE, db.inspectPlannedBinding(OWNER, plan))
            rejected(FailureReason.STORAGE_FAILURE, db.bindPlannedActivation(OWNER, plan, 2, BODY))
            rejected(FailureReason.STORAGE_FAILURE, db.resumeBound(OWNER, receipt.target!!, receipt.record!!))
            f.assertReadOnly(); assertTrue(f.connection.executed.isEmpty())
        }
    }

    @Test fun acknowledgedBindingAndExactResumeSurviveDatabaseCloseAndReopen() = runBlocking {
        fixture { f ->
            val first = f.open(); val plan = selected(first); val receipt = ok(first.bindPlannedActivation(OWNER, plan, 2, BODY))
            val expected = receipt.record!!; expected.payload.copyForCodec().fill(0); receipt.target!!.copyForStorage().fill(0)
            ok(first.close()); val db = f.open(); f.observe()
            val inspected = ok(db.inspectPlannedBinding(OWNER, plan))
            assertContentEquals(receipt.target!!.copyForStorage(), inspected.target!!.copyForStorage())
            assertEquals(expected.revision, inspected.record!!.revision); same(BODY, inspected.record!!.payload)
            ok(db.resumeBound(OWNER, receipt.target!!, expected)); f.assertReadOnly()
        }
    }

    private suspend fun fixture(action: suspend (BindingFixture) -> Unit) {
        val f = BindingFixture(); try { action(f) } finally { f.close() }
    }
    private suspend fun selected(db: EncryptedStateDatabase): StateActivationPlan =
        ok(db.planActivation(OWNER)).also { ok(db.commitPlannedActivation(OWNER, it)) }
    companion object {
        private val OWNER = StorageScope("binding-environment", ActorKind.ACCOUNT, "private-binding-owner")
        private val DOMAIN = RecordKey("domain-records", "private-item")
        private val BODY = bytes("exact-private-binding-v2")
        private fun bytes(value: String) = PrivateBytes(value.encodeToByteArray())
        private fun decode(plan: StateActivationPlan) = StateActivationPlanCodec.decode(plan)
        private fun <T> ok(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
        private fun rejected(reason: FailureReason, result: PortResult<*>) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
        private fun same(a: PrivateBytes, b: PrivateBytes) = assertContentEquals(a.copyForCodec(), b.copyForCodec())
    }
}

private class BindingFixture {
    private val directory = Files.createTempDirectory("feedme-planned-binding-test-")
    val file = directory.resolve("state.sqlite")
    val vault = BindingVault()
    lateinit var connection: BindingConnection
    private val managers = mutableListOf<EncryptedStateDatabase>()
    private var creates = 0; private var legacyCreates = 0; private var seals = 0
    suspend fun open(dispatcher: CoroutineDispatcher = Dispatchers.IO, vault: StateVault = this.vault): EncryptedStateDatabase {
        connection = BindingConnection(BundledSQLiteDriver().open(file.toString()))
        return when (val result = EncryptedStateDatabase.open(connection, vault, dispatcher)) {
            is PortResult.Value -> result.value.also(managers::add)
            is PortResult.Failure -> fail("Expected fixture database, got ${result.reason}")
        }
    }
    fun raw(vararg queries: String) = BundledSQLiteDriver().open(file.toString()).use { c ->
        queries.forEach { sql -> c.prepare(sql).use { while (it.step()) { } } }
    }
    fun scalar(sql: String): Long = BundledSQLiteDriver().open(file.toString()).use { c ->
        c.prepare(sql).use { assertTrue(it.step()); it.getLong(0) }
    }
    fun text(sql: String): String = BundledSQLiteDriver().open(file.toString()).use { c ->
        c.prepare(sql).use { assertTrue(it.step()); it.getText(0) }
    }
    fun observe() {
        connection.executed.clear(); vault.deleted.clear(); vault.opens = 0; vault.allocations = 0
        creates = vault.exactCreated.size; legacyCreates = vault.legacyCreates; seals = vault.seals
    }
    fun assertReadOnly(allowWriteTransaction: Boolean = false) {
        assertEquals(creates, vault.exactCreated.size); assertEquals(legacyCreates, vault.legacyCreates)
        assertEquals(seals, vault.seals); assertTrue(vault.deleted.isEmpty())
        // Exact replay uses an immediate transaction to serialize predecessor/key checks. It may
        // acquire that lock but must not execute DML or alter stored bytes/material.
        assertFalse(connection.executed.any { bindingMutation(it) || (!allowWriteTransaction && it == "BEGIN IMMEDIATE") })
    }
    suspend fun close() {
        try { managers.forEach { it.close() } } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

private enum class BindingCommitFault { BEFORE, AFTER }
private fun bindingMutation(sql: String) = listOf("INSERT ", "UPDATE ", "DELETE ", "CREATE ", "ALTER ", "DROP ").any(sql.trim().uppercase()::startsWith)
private class BindingConnection(private val delegate: SQLiteConnection) : SQLiteConnection by delegate {
    val executed = mutableListOf<String>()
    var commitFault: BindingCommitFault? = null
    var afterWriteCommit: (() -> Unit)? = null
    var failNextReadBegin = false
    private var writing = false
    override fun prepare(sql: String): SQLiteStatement {
        val statement = delegate.prepare(sql)
        return object : SQLiteStatement by statement {
            override fun step(): Boolean {
                executed += sql
                if (sql == "BEGIN" && failNextReadBegin) { failNextReadBegin = false; error("Synthetic readback failure") }
                if (sql == "BEGIN IMMEDIATE") writing = true
                if (sql == "BEGIN") writing = false
                val fault = if (sql == "COMMIT" && writing) commitFault.also { commitFault = null } else null
                if (fault == BindingCommitFault.BEFORE) error("Synthetic precommit failure")
                val result = statement.step()
                if (sql == "COMMIT" && writing) { writing = false; afterWriteCommit?.invoke() }
                if (fault == BindingCommitFault.AFTER) error("Synthetic committed reply loss")
                if (sql == "ROLLBACK") writing = false
                return result
            }
        }
    }
}

private class BindingVault : PlannedStateVault {
    private val random = SecureRandom()
    private val indexKey = SecretKeySpec(ByteArray(32).also(random::nextBytes), "HmacSHA256")
    val keys = mutableMapOf<String, SecretKey>()
    val unusable = mutableSetOf<String>()
    val exactCreated = mutableListOf<String>(); val deleted = mutableListOf<String>()
    var allocations = 0; var legacyCreates = 0; var seals = 0; var opens = 0
    var nextId: String? = null; var failDelete = false; var afterCreate: (() -> Unit)? = null
    override fun index(input: ByteArray) = Mac.getInstance("HmacSHA256").run { init(indexKey); doFinal(input) }
    override fun newOwnerKeyId(): String { allocations++; return nextId ?: UUID.randomUUID().toString().replace("-", "") }
    override fun createOwnerKey(keyId: String) {
        if (keyId in keys) throw StateVaultException()
        seed(keyId); exactCreated += keyId; afterCreate?.invoke()
    }
    override fun createOwnerKey(): String {
        legacyCreates++
        return UUID.randomUUID().toString().replace("-", "").also(::seed)
    }
    fun seed(keyId: String) { keys[keyId] = KeyGenerator.getInstance("AES").apply { init(256, random) }.generateKey() }
    override fun hasOwnerKey(keyId: String): Boolean {
        if (keyId in unusable) throw StateVaultException()
        return keyId in keys
    }
    override fun seal(keyId: String, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        seals++; val nonce = ByteArray(12).also(random::nextBytes)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, keys[keyId] ?: throw StateVaultException(), GCMParameterSpec(128, nonce)); updateAAD(associatedData)
            byteArrayOf(1) + nonce + doFinal(plaintext)
        }
    }
    override fun open(keyId: String, ciphertext: ByteArray, associatedData: ByteArray): ByteArray = try {
        opens++; if (ciphertext.size < 29 || ciphertext[0] != 1.toByte()) throw StateVaultException()
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, keys[keyId] ?: throw StateVaultException(), GCMParameterSpec(128, ciphertext.copyOfRange(1, 13)))
            updateAAD(associatedData); doFinal(ciphertext, 13, ciphertext.size - 13)
        }
    } catch (_: Exception) { throw StateVaultException() }
    override fun deleteOwnerKey(keyId: String) {
        deleted += keyId; if (failDelete) throw StateVaultException(); keys.remove(keyId)
    }
}
