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
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Actual SQLite, test-only JCA keys. No production JVM fallback or native durability claim. */
class WorkOriginPlanAuthenticationTest {
    @Test fun optionalWorkCapabilitySignsDetachedRepeatedProofsWithoutAnyWriteOrKeyEffect() = runBlocking {
        fixture { f ->
            val work = f.work()
            assertIs<WorkOriginPlanAuthentication>(work)
            val expected = f.record(work)
            val input = PROPOSAL.copyForCodec()
            val proposal = PrivateBytes(input)
            input.fill(0)
            f.observe()
            val before = Files.readAllBytes(f.file)
            val proof = ok(work.signOriginPlan(expected, proposal))
            assertEquals(64, proof.copyForCodec().size)
            val detached = proof.copyForCodec(); detached.fill(0)
            assertContentEquals(proof.copyForCodec(), ok(work.signOriginPlan(expected, PROPOSAL)).copyForCodec())
            ok(work.verifyOriginPlan(expected.revision, PROPOSAL, proof))
            ok(work.verifyOriginPredecessor(expected, PROPOSAL, proof))
            assertFalse(proof.toString().contains("proposed-origin"))
            assertEquals(expected.revision, f.record(work).revision)
            f.assertReadOnly(before)
            assertTrue(f.vault.indexes > 0)
            assertTrue(f.vault.opens > 0) // Authenticated work metadata, never credential reads.
        }
    }

    @Test fun exactProofSurvivesAcknowledgedCloseAndExistingReopen() = runBlocking {
        fixture { f ->
            val first = f.work(); val expected = f.record(first)
            val proof = ok(first.signOriginPlan(expected, PROPOSAL))
            ok(first.close())
            val reopened = f.work(initialize = false)
            f.observe(); val before = Files.readAllBytes(f.file)
            ok(reopened.verifyOriginPlan(expected.revision, PROPOSAL, PrivateBytes(proof.copyForCodec())))
            ok(reopened.verifyOriginPredecessor(expected, PROPOSAL, proof))
            assertContentEquals(proof.copyForCodec(), ok(reopened.signOriginPlan(expected, PROPOSAL)).copyForCodec())
            f.assertReadOnly(before)
        }
    }

    @Test fun identicalRevisionAndPayloadInForeignInstallCannotAuthenticateProof() = runBlocking {
        fixture { first -> fixture { second ->
            val a = first.work(); val b = second.work()
            val expected = first.record(a); val other = second.record(b)
            assertEquals(expected.revision, other.revision)
            assertContentEquals(expected.payload.copyForCodec(), other.payload.copyForCodec())
            val proof = ok(a.signOriginPlan(expected, PROPOSAL))
            second.observe(); val before = Files.readAllBytes(second.file)
            rejected(FailureReason.INVALID_DATA, b.verifyOriginPlan(expected.revision, PROPOSAL, proof))
            rejected(FailureReason.INVALID_DATA, b.verifyOriginPredecessor(other, PROPOSAL, proof))
            second.assertReadOnly(before)
        } }
    }

    @Test fun revisionProposalAndBothMacHalvesAreBound() = runBlocking {
        fixture { f ->
            val work = f.work(); val expected = f.record(work)
            val proof = ok(work.signOriginPlan(expected, PROPOSAL))
            f.observe(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.INVALID_DATA, work.verifyOriginPlan(expected.revision + 1, PROPOSAL, proof))
            rejected(FailureReason.INVALID_DATA, work.verifyOriginPlan(expected.revision, bytes("other-proposal"), proof))
            for (index in listOf(0, 15, 31, 32, 47, 63)) {
                val changed = proof.copyForCodec(); changed[index] = (changed[index].toInt() xor 1).toByte()
                rejected(FailureReason.INVALID_DATA, work.verifyOriginPlan(expected.revision, PROPOSAL, PrivateBytes(changed)))
            }
            ok(work.verifyOriginPlan(expected.revision, PROPOSAL, proof))
            f.assertReadOnly(before)
        }
    }

    @Test fun proofAuthenticationDoesNotPromoteChangedRecordIntoOriginalPredecessor() = runBlocking {
        fixture { f ->
            val work = f.work(); val expected = f.record(work)
            val proof = ok(work.signOriginPlan(expected, PROPOSAL))
            val selected = ok(work.compareAndSet(expected.revision, bytes("bounded-selected-work-metadata")))
            f.observe(); val before = Files.readAllBytes(f.file)
            // Signature verification is not idle/selected/replay authorization; registry checks that.
            ok(work.verifyOriginPlan(expected.revision, PROPOSAL, proof))
            rejected(FailureReason.CONFLICT, work.signOriginPlan(expected, PROPOSAL))
            rejected(FailureReason.CONFLICT, work.verifyOriginPredecessor(expected, PROPOSAL, proof))
            rejected(FailureReason.INVALID_DATA, work.verifyOriginPredecessor(selected, PROPOSAL, proof))
            f.assertReadOnly(before)
        }
    }

    @Test fun predecessorComparisonPreservesExactNoncanonicalRawBytes() = runBlocking {
        fixture { f ->
            val work = f.work()
            val raw = bytes(" { \"version\" : 1, \"state\" : \"idle\" } \n")
            val actual = ok(work.compareAndSet(1, raw))
            val proof = ok(work.signOriginPlan(actual, PROPOSAL))
            val canonicalImpostor = SessionControlRecord(actual.revision, IDLE)
            f.observe(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.CONFLICT, work.signOriginPlan(canonicalImpostor, PROPOSAL))
            rejected(FailureReason.CONFLICT, work.verifyOriginPredecessor(canonicalImpostor, PROPOSAL, proof))
            ok(work.verifyOriginPredecessor(actual, PROPOSAL, proof))
            f.assertReadOnly(before)
        }
    }

    @Test fun authenticatedDifferentBytesAtSameRevisionCannotMatchOriginalPredecessorMac() = runBlocking {
        fixture { f ->
            val work = f.work(); val original = f.record(work)
            val proof = ok(work.signOriginPlan(original, PROPOSAL))
            // Test-only authenticated SQL rewrite: impossible through the wrapper's monotonic CAS.
            f.rewriteRecord(bytes(" {\"version\":1,\"state\":\"idle\"}"), original.revision)
            val changed = f.record(work)
            f.observe(); val before = Files.readAllBytes(f.file)
            ok(work.verifyOriginPlan(original.revision, PROPOSAL, proof))
            rejected(FailureReason.INVALID_DATA, work.verifyOriginPredecessor(changed, PROPOSAL, proof))
            rejected(FailureReason.CONFLICT, work.verifyOriginPredecessor(original, PROPOSAL, proof))
            f.assertReadOnly(before)
        }
    }

    @Test fun missingOwnerKeyCannotProduceOrVerifyProofOrRegenerateMaterial() = runBlocking {
        fixture { f ->
            val work = f.work(); val expected = f.record(work); val proof = ok(work.signOriginPlan(expected, PROPOSAL))
            f.vault.keys.clear(); f.observe(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.STORAGE_FAILURE, work.signOriginPlan(expected, PROPOSAL))
            rejected(FailureReason.STORAGE_FAILURE, work.verifyOriginPlan(expected.revision, PROPOSAL, proof))
            rejected(FailureReason.STORAGE_FAILURE, work.verifyOriginPredecessor(expected, PROPOSAL, proof))
            assertTrue(f.vault.keys.isEmpty()); f.assertReadOnly(before)
        }
    }

    @Test fun malformedMissingAndTombstonedWorkRecordFailClosedWithoutRepair() = runBlocking {
        for (damage in listOf("missing", "tombstone", "schema", "oversize", "ciphertext")) fixture { f ->
            val work = f.work(); val expected = f.record(work); val proof = ok(work.signOriginPlan(expected, PROPOSAL))
            when (damage) {
                "missing" -> f.raw("DELETE FROM feedme_records")
                "tombstone" -> f.raw("UPDATE feedme_records SET payload=NULL")
                "schema" -> f.rewriteRecord(IDLE, 1, schema = 2)
                "oversize" -> f.rewriteRecord(PrivateBytes(ByteArray(32_769)), 1)
                else -> f.raw("UPDATE feedme_records SET payload=zeroblob(29)")
            }
            f.observe(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.STORAGE_FAILURE, work.signOriginPlan(expected, PROPOSAL))
            rejected(FailureReason.STORAGE_FAILURE, work.verifyOriginPlan(expected.revision, PROPOSAL, proof))
            rejected(FailureReason.STORAGE_FAILURE, work.verifyOriginPredecessor(expected, PROPOSAL, proof))
            f.assertReadOnly(before)
        }
    }

    @Test fun capturedWrapperNeverRebindsAfterItsOwnerGenerationChanges() = runBlocking {
        fixture { f ->
            val work = f.work(); val expected = f.record(work); val proof = ok(work.signOriginPlan(expected, PROPOSAL))
            f.raw("UPDATE feedme_owners SET generation=generation+1")
            f.observe(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.STALE_SESSION, work.signOriginPlan(expected, PROPOSAL))
            rejected(FailureReason.STALE_SESSION, work.verifyOriginPlan(expected.revision, PROPOSAL, proof))
            rejected(FailureReason.STALE_SESSION, work.verifyOriginPredecessor(expected, PROPOSAL, proof))
            assertEquals(0, f.vault.opens)
            f.assertReadOnly(before)
        }
    }

    @Test fun reopenedNewOwnerUnderSameInstallIndexCannotAuthenticateOldOwnerProof() = runBlocking {
        fixture { f ->
            val old = f.work(); val expected = f.record(old); val proof = ok(old.signOriginPlan(expected, PROPOSAL))
            val owned = assertNotNull(ok(f.database.resume(WORK_SCOPE)))
            ok(owned.eraseScope(WORK_SCOPE))
            val fresh = ok(f.database.activate(WORK_SCOPE))
            ok(fresh.commit(WORK_SCOPE, listOf(StoreMutation.Put(WORK_KEY, null, 1, IDLE))))
            ok(old.close())
            val reopened = f.work(initialize = false)
            val current = f.record(reopened)
            assertEquals(expected.revision, current.revision)
            assertContentEquals(expected.payload.copyForCodec(), current.payload.copyForCodec())
            f.observe(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.INVALID_DATA, reopened.verifyOriginPlan(expected.revision, PROPOSAL, proof))
            rejected(FailureReason.INVALID_DATA, reopened.verifyOriginPredecessor(current, PROPOSAL, proof))
            assertFalse(proof.copyForCodec().contentEquals(ok(reopened.signOriginPlan(current, PROPOSAL)).copyForCodec()))
            f.assertReadOnly(before)
        }
    }

    @Test fun internalSeamRejectsForeignManagerAndNonWorkScopedHandles() = runBlocking {
        fixture { first -> fixture { second ->
            val a = first.work(); second.work()
            val expected = first.record(a)
            val owned = assertNotNull(ok(first.database.resume(WORK_SCOPE)))
            val other = ok(first.database.activate(OTHER_SCOPE))
            first.observe(); second.observe()
            val beforeA = Files.readAllBytes(first.file); val beforeB = Files.readAllBytes(second.file)
            rejected(FailureReason.INVALID_DATA, second.database.authenticateWorkOriginPlan(owned, expected.revision, PROPOSAL, null, expected))
            rejected(FailureReason.INVALID_DATA, first.database.authenticateWorkOriginPlan(other, expected.revision, PROPOSAL, null, expected))
            first.assertReadOnly(beforeA); second.assertReadOnly(beforeB)
        } }
    }

    @Test fun proofOperationsNeverRunUnrelatedPendingKeyGarbageCollection() = runBlocking {
        fixture { f ->
            val work = f.work(); val expected = f.record(work)
            val other = ok(f.database.activate(OTHER_SCOPE))
            f.vault.failDelete = true
            rejected(FailureReason.STORAGE_FAILURE, other.eraseScope(OTHER_SCOPE))
            f.vault.failDelete = false
            val keys = f.vault.keys.toMap()
            assertEquals(1L, f.scalar("SELECT count(*) FROM feedme_key_gc"))
            f.observe(); val before = Files.readAllBytes(f.file)
            val proof = ok(work.signOriginPlan(expected, PROPOSAL))
            ok(work.verifyOriginPlan(expected.revision, PROPOSAL, proof))
            ok(work.verifyOriginPredecessor(expected, PROPOSAL, proof))
            assertEquals(keys, f.vault.keys)
            assertEquals(1L, f.scalar("SELECT count(*) FROM feedme_key_gc"))
            f.assertReadOnly(before)
        }
    }

    @Test fun boundedInputsRejectBeforeSqlAndMaximumSupportedRevisionStillSigns() = runBlocking {
        fixture { f ->
            val work = f.work(); val expected = f.record(work); val proof = ok(work.signOriginPlan(expected, PROPOSAL))
            f.observe(); val before = Files.readAllBytes(f.file)
            for (revision in listOf(-1L, 0L, Long.MAX_VALUE - 1, Long.MAX_VALUE))
                rejected(FailureReason.INVALID_DATA, work.verifyOriginPlan(revision, PROPOSAL, proof))
            for (size in listOf(0, 4097)) {
                rejected(FailureReason.INVALID_DATA, work.signOriginPlan(expected, PrivateBytes(ByteArray(size))))
                rejected(FailureReason.INVALID_DATA, work.verifyOriginPlan(1, PrivateBytes(ByteArray(size)), proof))
            }
            for (size in listOf(0, 63, 65))
                rejected(FailureReason.INVALID_DATA, work.verifyOriginPredecessor(expected, PROPOSAL, PrivateBytes(ByteArray(size))))
            for (size in listOf(0, 32_769))
                rejected(FailureReason.INVALID_DATA, work.signOriginPlan(SessionControlRecord(1, PrivateBytes(ByteArray(size))), PROPOSAL))
            assertTrue(f.sql.executed.isEmpty())
            assertEquals(0, f.vault.indexes)
            f.assertReadOnly(before)
            f.rewriteRecord(IDLE, Long.MAX_VALUE - 2)
            val maximum = f.record(work); val proposed = PrivateBytes(ByteArray(4096) { 73 })
            f.observe(); val atMaximum = Files.readAllBytes(f.file)
            val signed = ok(work.signOriginPlan(maximum, proposed))
            ok(work.verifyOriginPlan(maximum.revision, proposed, signed))
            ok(work.verifyOriginPredecessor(maximum, proposed, signed))
            f.assertReadOnly(atMaximum)
        }
    }

    @Test fun vaultErrorsAreSanitizedAndCancellationPropagatesWithoutMutation() = runBlocking {
        fixture { f ->
            val work = f.work(); val expected = f.record(work)
            f.observe(); val before = Files.readAllBytes(f.file)
            f.vault.indexFailure = IllegalStateException("must-not-expose-provider-or-owner")
            val error = assertIs<PortResult.Failure>(work.signOriginPlan(expected, PROPOSAL))
            assertEquals(FailureReason.STORAGE_FAILURE, error.reason)
            assertFalse(error.toString().contains("must-not-expose"))
            f.vault.indexFailure = CancellationException("fixture cancellation")
            assertFailsWith<CancellationException> { work.signOriginPlan(expected, PROPOSAL) }
            val proof = ok(work.signOriginPlan(expected, PROPOSAL))
            ok(work.verifyOriginPredecessor(expected, PROPOSAL, proof))
            f.assertReadOnly(before)
        }
    }

    @Test fun closedWrapperCannotSignOrVerifyAndDoesNotTouchVault() = runBlocking {
        fixture { f ->
            val work = f.work(); val expected = f.record(work); val proof = ok(work.signOriginPlan(expected, PROPOSAL))
            ok(work.close()); f.observe(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.STORAGE_FAILURE, work.signOriginPlan(expected, PROPOSAL))
            rejected(FailureReason.STORAGE_FAILURE, work.verifyOriginPlan(expected.revision, PROPOSAL, proof))
            rejected(FailureReason.STORAGE_FAILURE, work.verifyOriginPredecessor(expected, PROPOSAL, proof))
            assertEquals(0, f.vault.indexes); assertEquals(0, f.vault.opens)
            f.assertReadOnly(before)
        }
    }

    private suspend fun fixture(action: suspend (Fixture) -> Unit) {
        val f = Fixture()
        try { action(f) } finally { f.close() }
    }

    private class Fixture {
        private val directory = Files.createTempDirectory("feedme-work-origin-proof-test-")
        val file = directory.resolve("state.sqlite")
        val vault = TestVault()
        lateinit var database: EncryptedStateDatabase
        lateinit var sql: SqlTrace
        private val databases = mutableListOf<EncryptedStateDatabase>()
        suspend fun work(initialize: Boolean = true): EncryptedSessionWorkStore {
            sql = SqlTrace(BundledSQLiteDriver().open(file.toString()))
            database = ok(EncryptedStateDatabase.open(sql, vault)).also(databases::add)
            return ok(EncryptedSessionWorkStore.open(database, initialize))
        }
        suspend fun record(work: EncryptedSessionWorkStore) = assertNotNull(ok(work.read()))
        fun observe() { sql.executed.clear(); vault.resetCounters() }
        fun assertReadOnly(before: ByteArray) {
            assertTrue(sql.executed.all { it == "BEGIN" || it == "COMMIT" || it == "ROLLBACK" || it.startsWith("SELECT ") })
            assertEquals(0, vault.creates); assertEquals(0, vault.seals); assertEquals(0, vault.deletes)
            assertContentEquals(before, Files.readAllBytes(file))
        }
        fun raw(text: String) { sql.prepare(text).use { it.step() } }
        fun scalar(text: String): Long = sql.prepare(text).use { assertTrue(it.step()); it.getLong(0) }
        fun rewriteRecord(payload: PrivateBytes, revision: Long, schema: Int = 1) {
            sql.prepare("SELECT o.owner_tag,o.generation,o.key_id,r.record_tag FROM feedme_owners o JOIN feedme_records r USING(owner_tag)").use {
                assertTrue(it.step())
                val tag = it.getText(0); val generation = it.getLong(1); val key = it.getText(2); val recordTag = it.getText(3)
                val aad = frame("feedme.record-aead.v1", tag, generation.toString(), recordTag, revision.toString(), schema.toString())
                val plain = payload.copyForCodec()
                val encrypted = try { vault.seal(key, plain, aad) } finally { plain.fill(0); aad.fill(0) }
                sql.prepare("UPDATE feedme_records SET revision=?,schema_version=?,payload=? WHERE owner_tag=? AND record_tag=?").use { update ->
                    update.bindLong(1, revision); update.bindLong(2, schema.toLong()); update.bindBlob(3, encrypted)
                    update.bindText(4, tag); update.bindText(5, recordTag); update.step()
                }
            }
        }
        suspend fun close() {
            databases.asReversed().forEach { ok(it.close()) }
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private class SqlTrace(private val native: SQLiteConnection) : SQLiteConnection by native {
        val executed = mutableListOf<String>()
        override fun prepare(sql: String): SQLiteStatement {
            val statement = native.prepare(sql)
            return object : SQLiteStatement by statement {
                override fun step(): Boolean { executed += sql; return statement.step() }
            }
        }
    }

    private class TestVault : StateVault {
        private val random = SecureRandom()
        private val indexKey = SecretKeySpec(ByteArray(32).also(random::nextBytes), "HmacSHA256")
        val keys = mutableMapOf<String, SecretKey>()
        var creates = 0; var seals = 0; var opens = 0; var deletes = 0; var indexes = 0
        var failDelete = false
        var indexFailure: Exception? = null
        fun resetCounters() { creates = 0; seals = 0; opens = 0; deletes = 0; indexes = 0 }
        override fun index(input: ByteArray): ByteArray {
            indexes++
            indexFailure?.let { indexFailure = null; throw it }
            return Mac.getInstance("HmacSHA256").run { init(indexKey); doFinal(input) }
        }
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
        override fun open(keyId: String, ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
            opens++
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, keys[keyId] ?: throw StateVaultException(), GCMParameterSpec(128, ciphertext.copyOfRange(1, 13)))
                updateAAD(associatedData)
                doFinal(ciphertext, 13, ciphertext.size - 13)
            }
        }
        override fun deleteOwnerKey(keyId: String) {
            deletes++
            if (failDelete) throw StateVaultException()
            keys.remove(keyId)
        }
    }

    companion object {
        private val WORK_SCOPE = StorageScope("feedme-session-work-v1", ActorKind.DEMO, "install-work")
        private val WORK_KEY = RecordKey("session-work", "native-work-ledger")
        private val OTHER_SCOPE = StorageScope("proof-test-other", ActorKind.ACCOUNT, "other-owner")
        private val IDLE = bytes("{\"version\":1,\"state\":\"idle\"}")
        private val PROPOSAL = bytes("bounded-proposed-origin-fixture")
        private fun bytes(text: String) = PrivateBytes(text.encodeToByteArray())
        private fun <T> ok(result: PortResult<T>): T = when (result) {
            is PortResult.Value -> result.value
            is PortResult.Failure -> fail("Expected value, got ${result.reason}")
        }
        private fun rejected(reason: FailureReason, result: PortResult<*>) {
            assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
        }
        private fun frame(vararg fields: String): ByteArray {
            val parts = fields.map { it.encodeToByteArray(throwOnInvalidSequence = true) }
            val result = ByteArray(4 + parts.sumOf { 4 + it.size }); var offset = 0
            fun length(value: Int) { for (shift in 24 downTo 0 step 8) result[offset++] = (value ushr shift).toByte() }
            length(parts.size)
            parts.forEach { length(it.size); it.copyInto(result, offset); offset += it.size }
            return result
        }
    }
}
