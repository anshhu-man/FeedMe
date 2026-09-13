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
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Real SQLite/JCA. Exceptions around successful COMMIT are application receipt loss, not fsync faults. */
class PlannedStateBindingAbortTest {
    @Test fun exactBindingIsRemovedWithConsumedOwnerAndReceiptBeforeKeyDeletion() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = f.bound(db); val key = decode(plan).keyId
            f.observe()
            f.vault.beforeDelete = {
                assertEquals(key, it); assertTrue("COMMIT_ACK" in f.events)
                assertEquals(0L, f.scalar("SELECT count(*) FROM feedme_records"))
                assertEquals(decode(plan).consumedGeneration, f.scalar("SELECT generation FROM feedme_owners"))
                assertEquals(0L, f.scalar("SELECT active FROM feedme_owners"))
                assertEquals(1L, f.receipt())
            }
            ok(db.abortPlannedActivation(OWNER, plan, BODY))
            assertFalse(key in f.vault.keys); assertEquals(listOf(key), f.vault.deleted)
            assertTrue(f.events.indexOf("COMMIT_ACK") < f.events.indexOf("DELETE_KEY"))
            assertEquals(0, f.vault.created); assertEquals(0, f.vault.sealed)
            assertEquals(StateActivationStatus.ABORTED, ok(db.inspectPlannedActivation(OWNER, plan)).status)
        }
    }

    @Test fun nullExpectedBindingRetainsStrictEmptyOnlyRejectionWithoutDecrypting() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = f.bound(db); f.observe(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.CONFLICT, db.abortPlannedActivation(OWNER, plan))
            assertEquals(0, f.vault.opened); f.assertPreserved(before)
        }
    }

    @Test fun exactOpaquePayloadComparisonDoesNotPretendToValidateSessionJsonOrProviderConsent() = runBlocking {
        for (payload in listOf(bytes("not-session-json"), PrivateBytes(ByteArray(4096) { 42 }))) fixture { f ->
            val db = f.open(); val plan = f.bound(db, payload = payload)
            ok(db.abortPlannedActivation(OWNER, plan, payload))
            assertEquals(0L, f.scalar("SELECT count(*) FROM feedme_records"))
        }
    }

    @Test fun schemaOneAndDifferentExpectedPayloadCannotAuthorizeBindingDeletion() = runBlocking {
        for (schema in listOf(1, 2)) fixture { f ->
            val db = f.open(); val plan = f.bound(db, schema = schema); f.observe(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.CONFLICT, db.abortPlannedActivation(OWNER, plan, if (schema == 1) BODY else bytes("different")))
            f.assertPreserved(before)
        }
    }

    @Test fun invalidExpectedBindingSizeIsRejectedBeforeSqlOrNativeKeyCalls() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = f.bound(db); f.observe()
            for (size in listOf(0, 4097)) rejected(FailureReason.INVALID_DATA,
                db.abortPlannedActivation(OWNER, plan, PrivateBytes(ByteArray(size))))
            assertTrue(f.sql.executed.isEmpty()); assertEquals(0, f.vault.opened); assertTrue(f.vault.deleted.isEmpty())
        }
    }

    @Test fun anyOtherPrivateRowOrTombstoneBlocksBoundAbortWithoutTouchingKeys() = runBlocking {
        for (tombstone in listOf(false, true)) fixture { f ->
            val db = f.open(); val plan = f.bound(db)
            val store = ok(db.resume(OWNER))!!
            ok(store.commit(OWNER, listOf(StoreMutation.Put(OTHER, null, 1, bytes("keep-private")))))
            if (tombstone) ok(store.commit(OWNER, listOf(StoreMutation.Delete(OTHER, 1))))
            f.observe(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.CONFLICT, db.abortPlannedActivation(OWNER, plan, BODY)); f.assertPreserved(before)
        }
    }

    @Test fun reservedTombstoneCorruptCiphertextAndUnknownSchemaRemainFailClosed() = runBlocking {
        for (damage in listOf("tombstone", "ciphertext", "schema")) fixture { f ->
            val db = f.open(); val plan = f.bound(db)
            f.raw(when (damage) { "tombstone" -> "UPDATE feedme_records SET payload=NULL";
                "schema" -> "UPDATE feedme_records SET schema_version=3"; else -> "UPDATE feedme_records SET payload=zeroblob(29)" })
            f.observe(); val before = Files.readAllBytes(f.file)
            rejected(if (damage == "ciphertext") FailureReason.STORAGE_FAILURE else FailureReason.CONFLICT,
                db.abortPlannedActivation(OWNER, plan, BODY)); f.assertPreserved(before)
        }
    }

    @Test fun selectedMissingOrUnusableBindingKeyPreservesTheBindingWhileEmptyMissingKeyCanBeConsumed() = runBlocking {
        for (bound in listOf(false, true)) for (unusable in listOf(false, true)) fixture { f ->
            val db = f.open(); val plan = if (bound) f.bound(db) else f.selected(db); val key = decode(plan).keyId
            if (unusable) f.vault.unusable += key else f.vault.keys.remove(key)
            f.observe(); val before = Files.readAllBytes(f.file)
            if (bound) { rejected(FailureReason.STORAGE_FAILURE, db.abortPlannedActivation(OWNER, plan, BODY)); f.assertPreserved(before) }
            else { ok(db.abortPlannedActivation(OWNER, plan, BODY)); assertEquals(0, f.vault.opened); assertEquals(0L, f.scalar("SELECT count(*) FROM feedme_records")) }
            assertEquals(0, f.vault.created)
        }
    }

    @Test fun preparedPartialAndSelectedEmptyStagesAcceptExpectedBytesWithoutInventingABinding() = runBlocking {
        for (stage in listOf("prepared", "partial", "selected")) fixture { f ->
            val db = f.open(); val plan = ok(db.planActivation(OWNER)); val key = decode(plan).keyId
            if (stage == "partial") f.vault.seed(key)
            if (stage == "selected") ok(db.commitPlannedActivation(OWNER, plan))
            f.observe(); ok(db.abortPlannedActivation(OWNER, plan, BODY))
            assertEquals(0, f.vault.opened); assertEquals(0, f.vault.created)
            assertEquals(StateActivationStatus.ABORTED, ok(db.inspectPlannedActivation(OWNER, plan)).status)
        }
    }

    @Test fun wrongScopeBadMacAndForeignInstallCannotConsumeOrEraseAnything() = runBlocking {
        fixture { f -> fixture { foreign ->
            val db = f.open(); val plan = f.bound(db); val otherDb = foreign.open(); f.observe(); val before = Files.readAllBytes(f.file)
            val altered = plan.copyForStorage(); altered[169] = (altered[169].toInt() xor 1).toByte()
            rejected(FailureReason.INVALID_DATA, db.abortPlannedActivation(OWNER, StateActivationPlan(altered), BODY))
            rejected(FailureReason.INVALID_DATA, db.abortPlannedActivation(OWNER.copy(actorId = "foreign"), plan, BODY))
            rejected(FailureReason.INVALID_DATA, otherDb.abortPlannedActivation(OWNER, plan, BODY))
            f.assertPreserved(before); assertTrue(foreign.vault.deleted.isEmpty())
        } }
    }

    @Test fun failedCommitBeforeOrAfterSqliteAcknowledgementNeverDeletesAndExactRetryRewritesReceipt() = runBlocking {
        for (fault in AbortCommitFault.entries) fixture { f ->
            val db = f.open(); val plan = f.bound(db); val key = decode(plan).keyId; f.observe(); f.sql.commitFault = fault
            rejected(if (fault == AbortCommitFault.BEFORE) FailureReason.STORAGE_FAILURE else FailureReason.OUTCOME_UNKNOWN,
                db.abortPlannedActivation(OWNER, plan, BODY))
            assertTrue(key in f.vault.keys); assertTrue(f.vault.deleted.isEmpty())
            assertEquals(if (fault == AbortCommitFault.BEFORE) 1L else 0L, f.scalar("SELECT count(*) FROM feedme_records"))
            val old = f.scalar("SELECT coalesce(max(revision),0) FROM feedme_activation_aborts")
            ok(db.abortPlannedActivation(OWNER, plan, BODY)); assertEquals(old + 1, f.receipt()); assertFalse(key in f.vault.keys)
        }
    }

    @Test fun keyDeletionFailureRetainsConsumedFenceAndReopenRetriesOnlyOriginalKeyAfterFreshCommit() = runBlocking {
        fixture { f ->
            var db = f.open(); val plan = f.bound(db); val key = decode(plan).keyId
            f.vault.failDelete = true; rejected(FailureReason.STORAGE_FAILURE, db.abortPlannedActivation(OWNER, plan, BODY))
            assertEquals(1L, f.receipt()); assertEquals(0L, f.scalar("SELECT count(*) FROM feedme_records")); assertTrue(key in f.vault.keys)
            ok(db.close()); f.vault.failDelete = false; db = f.open(); f.observe()
            ok(db.abortPlannedActivation(OWNER, plan, BODY)); assertEquals(2L, f.receipt())
            assertEquals(listOf(key), f.vault.deleted); assertEquals(0, f.vault.opened); assertEquals(0, f.vault.created)
        }
    }

    @Test fun everyAbortedReplayStillRequiresAChangedAcknowledgedReceipt() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = f.bound(db); ok(db.abortPlannedActivation(OWNER, plan, BODY))
            repeat(3) { index -> ok(db.abortPlannedActivation(OWNER, plan, BODY)); assertEquals(index + 2L, f.receipt()) }
            f.observe(); f.sql.commitFault = AbortCommitFault.BEFORE
            rejected(FailureReason.STORAGE_FAILURE, db.abortPlannedActivation(OWNER, plan, BODY))
            assertEquals(4L, f.receipt()); assertTrue(f.vault.deleted.isEmpty())
        }
    }

    @Test fun unrelatedQueuedGarbageAndSiblingRecordsSurviveBoundAbortAndItsReplay() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = f.bound(db); val sibling = OWNER.copy(actorId = "sibling")
            val other = ok(db.activate(sibling)); ok(other.commit(sibling, listOf(StoreMutation.Put(OTHER, null, 1, bytes("sibling-private")))))
            val garbage = "e".repeat(32); f.vault.seed(garbage); f.raw("INSERT INTO feedme_key_gc VALUES('$garbage')")
            f.observe(); repeat(2) { ok(db.abortPlannedActivation(OWNER, plan, BODY)) }
            assertTrue(garbage in f.vault.keys); assertEquals(1L, f.scalar("SELECT count(*) FROM feedme_key_gc"))
            same(bytes("sibling-private"), ok(other.read(sibling, OTHER))!!.payload)
            assertTrue(f.vault.deleted.all { it == decode(plan).keyId })
        }
    }

    @Test fun changedReceiptOrNewRowBetweenConsumeAndDeletionKeepsTheKey() = runBlocking {
        for (change in listOf("receipt", "row", "owner")) fixture { f ->
            val db = f.open(); val plan = f.bound(db); val owner = decode(plan); f.observe()
            f.sql.afterCommit = { f.sql.afterCommit = null
                when (change) {
                    "receipt" -> f.raw("UPDATE feedme_activation_aborts SET revision=revision+1")
                    "owner" -> f.raw("UPDATE feedme_owners SET generation=generation+1")
                    else -> f.raw("INSERT INTO feedme_records VALUES('${owner.ownerTag}','${"f".repeat(64)}',1,1,NULL)")
                }
            }
            assertIs<PortResult.Failure>(db.abortPlannedActivation(OWNER, plan, BODY))
            assertTrue(owner.keyId in f.vault.keys); assertTrue(f.vault.deleted.isEmpty())
        }
    }

    @Test fun newlyActivatedOwnerCannotBeErasedThroughAnOldConsumedBindingPlan() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = f.bound(db); ok(db.abortPlannedActivation(OWNER, plan, BODY))
            val next = f.bound(db); val newer = decode(next).keyId; f.observe(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.STALE_SESSION, db.abortPlannedActivation(OWNER, plan, BODY)); f.assertPreserved(before)
            assertTrue(newer in f.vault.keys)
        }
    }

    @Test fun exhaustedAbortReceiptCannotWrapOrDeleteEvenWhenBindingWasAlreadyRemoved() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = f.bound(db); f.vault.failDelete = true
            rejected(FailureReason.STORAGE_FAILURE, db.abortPlannedActivation(OWNER, plan, BODY)); f.vault.failDelete = false
            f.raw("UPDATE feedme_activation_aborts SET revision=${Long.MAX_VALUE}"); f.observe(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.STORAGE_FAILURE, db.abortPlannedActivation(OWNER, plan, BODY)); f.assertPreserved(before)
        }
    }

    @Test fun cancellationBeforeAndAfterConsumeNeverDeletesKeyAndExactIntentRemainsRetryable() = runTest {
        for (after in listOf(false, true)) fixture { f ->
            val db = f.open(StandardTestDispatcher(testScheduler)); val plan = f.bound(db); f.observe()
            lateinit var running: Deferred<PortResult<Unit>>
            val cancel = { running.cancel(); Unit }
            if (after) f.sql.afterCommit = cancel else f.sql.beforeCommit = cancel
            running = async(start = CoroutineStart.LAZY) { db.abortPlannedActivation(OWNER, plan, BODY) }
            running.start(); assertFailsWith<CancellationException> { running.await() }
            assertTrue(decode(plan).keyId in f.vault.keys); assertTrue(f.vault.deleted.isEmpty())
            f.sql.afterCommit = null; f.sql.beforeCommit = null
            ok(db.abortPlannedActivation(OWNER, plan, BODY))
        }
    }

    @Test fun existingOnlyRecoveryHandleRequiresExactBindingAndCloseFailureRetainsOwnershipUntilRetry() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = f.bound(db); ok(db.close())
            val handle = f.recover(plan)
            rejected(FailureReason.CONFLICT, handle.abort()); rejected(FailureReason.CONFLICT, handle.abortBound(bytes("wrong")))
            ok(handle.abortBound(BODY)); assertEquals(StateActivationStatus.ABORTED, ok(handle.inspect()).status)
            f.sql.failCloseOnce = true; rejected(FailureReason.STORAGE_FAILURE, handle.close()); assertEquals(0, f.closedCallbacks)
            ok(handle.close()); assertEquals(1, f.closedCallbacks)
            rejected(FailureReason.STORAGE_FAILURE, handle.abortBound(BODY))
            val again = f.recover(plan); ok(again.abortBound(BODY)); assertEquals(2L, f.receipt())
        }
    }

    @Test fun closedDirectOwnerCannotReadDeleteOrConsumeAnyFurtherBinding() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = f.bound(db); ok(db.close()); f.observe(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.STORAGE_FAILURE, db.abortPlannedActivation(OWNER, plan, BODY)); f.assertPreserved(before)
        }
    }

    private suspend fun fixture(action: suspend (Fixture) -> Unit) {
        val f = Fixture(); try { action(f) } finally { f.close() }
    }
    private class Fixture {
        private val directory = Files.createTempDirectory("feedme-binding-abort-test-")
        val file = directory.resolve("state.sqlite")
        val events = mutableListOf<String>(); val vault = AbortVault(events)
        lateinit var sql: AbortConnection
        private val closers = mutableListOf<suspend () -> PortResult<Unit>>()
        var closedCallbacks = 0
        suspend fun open(dispatcher: CoroutineDispatcher = Dispatchers.IO): EncryptedStateDatabase {
            sql = AbortConnection(BundledSQLiteDriver().open(file.toString()), events)
            return ok(EncryptedStateDatabase.open(sql, vault, dispatcher)).also { db -> closers += { db.close() } }
        }
        suspend fun recover(plan: StateActivationPlan): StateActivationRecoveryHandle {
            sql = AbortConnection(BundledSQLiteDriver().open(file.toString()), events)
            return ok(EncryptedStateDatabase.openActivationRecovery(sql, vault, OWNER, plan, onClosed = { closedCallbacks++ }))
                .also { handle -> closers += { handle.close() } }
        }
        suspend fun selected(db: EncryptedStateDatabase) = ok(db.planActivation(OWNER)).also { ok(db.commitPlannedActivation(OWNER, it)) }
        suspend fun bound(db: EncryptedStateDatabase, schema: Int = 2, payload: PrivateBytes = BODY) =
            selected(db).also { ok(db.bindPlannedActivation(OWNER, it, schema, payload)) }
        fun raw(sql: String) = BundledSQLiteDriver().open(file.toString()).use { c -> c.prepare(sql).use { while (it.step()) { } } }
        fun scalar(sql: String) = BundledSQLiteDriver().open(file.toString()).use { c -> c.prepare(sql).use { assertTrue(it.step()); it.getLong(0) } }
        fun receipt() = scalar("SELECT revision FROM feedme_activation_aborts")
        fun observe() { events.clear(); sql.executed.clear(); vault.deleted.clear(); vault.created = 0; vault.sealed = 0; vault.opened = 0 }
        fun assertPreserved(before: ByteArray) {
            assertContentEquals(before, Files.readAllBytes(file)); assertTrue(vault.deleted.isEmpty())
            assertEquals(0, vault.created); assertEquals(0, vault.sealed)
        }
        suspend fun close() {
            try { closers.asReversed().forEach { ok(it()) } } finally {
                Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
    }
    companion object {
        private val OWNER = StorageScope("binding-abort-environment", ActorKind.ACCOUNT, "exact-private-owner")
        private val OTHER = RecordKey("domain-records", "retained-item")
        private val BODY = bytes("exact-private-binding-v2")
        private fun bytes(s: String) = PrivateBytes(s.encodeToByteArray())
        private fun decode(p: StateActivationPlan) = StateActivationPlanCodec.decode(p)
        private fun <T> ok(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
        private fun rejected(reason: FailureReason, result: PortResult<*>) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
        private fun same(a: PrivateBytes, b: PrivateBytes) = assertContentEquals(a.copyForCodec(), b.copyForCodec())
    }
}

private enum class AbortCommitFault { BEFORE, AFTER }
private class AbortConnection(private val native: SQLiteConnection, private val events: MutableList<String>) : SQLiteConnection by native {
    val executed = mutableListOf<String>()
    var commitFault: AbortCommitFault? = null; var beforeCommit: (() -> Unit)? = null; var afterCommit: (() -> Unit)? = null
    var failCloseOnce = false
    private var changed = false
    override fun prepare(sql: String): SQLiteStatement {
        val statement = native.prepare(sql)
        return object : SQLiteStatement by statement {
            override fun step(): Boolean {
                executed += sql
                if (sql.startsWith("BEGIN")) changed = false
                val committing = sql == "COMMIT" && changed
                val fault = if (committing) commitFault.also { commitFault = null } else null
                if (committing) beforeCommit?.invoke()
                if (fault == AbortCommitFault.BEFORE) error("Synthetic before-COMMIT failure")
                val result = statement.step()
                if (listOf("INSERT ", "UPDATE ", "DELETE ").any(sql::startsWith)) changed = true
                if (committing) { events += "COMMIT_ACK"; changed = false; afterCommit?.invoke() }
                if (fault == AbortCommitFault.AFTER) error("Synthetic lost application COMMIT receipt")
                if (sql == "COMMIT" || sql == "ROLLBACK") changed = false
                return result
            }
        }
    }
    override fun close() { if (failCloseOnce) { failCloseOnce = false; error("Synthetic close failure") }; native.close() }
}
private class AbortVault(private val events: MutableList<String>) : PlannedStateVault {
    private val random = SecureRandom(); private val indexKey = SecretKeySpec(ByteArray(32).also(random::nextBytes), "HmacSHA256")
    val keys = mutableMapOf<String, SecretKey>(); val unusable = mutableSetOf<String>(); val deleted = mutableListOf<String>()
    var created = 0; var sealed = 0; var opened = 0; var failDelete = false; var beforeDelete: ((String) -> Unit)? = null
    override fun index(input: ByteArray) = Mac.getInstance("HmacSHA256").run { init(indexKey); doFinal(input) }
    override fun newOwnerKeyId() = UUID.randomUUID().toString().replace("-", "")
    override fun createOwnerKey(): String = newOwnerKeyId().also(::createOwnerKey)
    override fun createOwnerKey(keyId: String) { check(keyId !in keys); created++; seed(keyId) }
    fun seed(keyId: String) { keys[keyId] = KeyGenerator.getInstance("AES").apply { init(256, random) }.generateKey() }
    override fun containsOwnerKey(keyId: String) = keyId in keys
    override fun hasOwnerKey(keyId: String): Boolean { if (keyId in unusable) throw StateVaultException(); return keyId in keys }
    override fun seal(keyId: String, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        sealed++; val nonce = ByteArray(12).also(random::nextBytes)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, keys[keyId] ?: throw StateVaultException(), GCMParameterSpec(128, nonce)); updateAAD(associatedData)
            byteArrayOf(1) + nonce + doFinal(plaintext)
        }
    }
    override fun open(keyId: String, ciphertext: ByteArray, associatedData: ByteArray): ByteArray = try {
        opened++; if (keyId in unusable || ciphertext.size < 29 || ciphertext[0] != 1.toByte()) throw StateVaultException()
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, keys[keyId] ?: throw StateVaultException(), GCMParameterSpec(128, ciphertext.copyOfRange(1, 13)))
            updateAAD(associatedData); doFinal(ciphertext, 13, ciphertext.size - 13)
        }
    } catch (_: Exception) { throw StateVaultException() }
    override fun deleteOwnerKey(keyId: String) { beforeDelete?.invoke(keyId); events += "DELETE_KEY"; deleted += keyId
        if (failDelete) throw StateVaultException(); keys.remove(keyId) }
}
