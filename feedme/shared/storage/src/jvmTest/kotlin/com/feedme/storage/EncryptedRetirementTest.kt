package com.feedme.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.feedme.core.ports.*
import java.nio.file.Files
import java.nio.file.Path
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

/** Exact retirement recovery against real SQLite and fixture-only keys; no native-vault claim. */
class EncryptedRetirementTest {
    @Test fun captureMissingOwnerDoesNotActivateWriteOrCreateKey() = runBlocking {
        withRetirementFixture { fixture ->
            val db = fixture.open()
            fixture.control.executed.clear()
            val before = Files.readAllBytes(fixture.file)
            assertNull(retirementValue(db.captureRetirement(OWNER)))
            assertEquals(0, fixture.vault.creates)
            assertTrue(fixture.vault.keys.isEmpty())
            assertTrue(fixture.vault.deleted.isEmpty())
            assertFalse(fixture.control.executed.any(::isMutation))
            assertContentEquals(before, Files.readAllBytes(fixture.file))
            assertEquals(0L, fixture.scalar("SELECT count(*) FROM feedme_owners"))
        }
    }

    @Test fun captureActiveOwnerIsReadOnlyDeterministicAndDoesNotInvalidateExistingStore() = runBlocking {
        withRetirementFixture { fixture ->
            val db = fixture.open()
            val store = retirementValue(db.activate(OWNER))
            retirementValue(store.commit(OWNER, listOf(put("private-state"))))
            val creates = fixture.vault.creates
            val seals = fixture.vault.seals
            val before = Files.readAllBytes(fixture.file)
            fixture.control.executed.clear()
            val first = assertNotNull(retirementValue(db.captureRetirement(OWNER)))
            val second = assertNotNull(retirementValue(db.captureRetirement(OWNER)))
            retirementValue(db.validateRetirement(OWNER, first))
            assertContentEquals(first.copyForStorage(), second.copyForStorage())
            assertEquals(StateRetirementTarget.ENCODED_SIZE, first.copyForStorage().size)
            assertEquals(creates, fixture.vault.creates)
            assertEquals(seals, fixture.vault.seals)
            assertTrue(fixture.vault.deleted.isEmpty())
            assertFalse(fixture.control.executed.any(::isMutation))
            assertContentEquals(before, Files.readAllBytes(fixture.file))
            assertRecord(store, "private-state")
        }
    }

    @Test fun serializedTargetOwnsItsBytesAndRedactsOpaqueIdentityMaterial() = runBlocking {
        withRetirementFixture { fixture ->
            val db = fixture.open()
            retirementValue(db.activate(OWNER))
            val original = assertNotNull(retirementValue(db.captureRetirement(OWNER))).copyForStorage()
            val source = original.copyOf()
            val detached = StateRetirementTarget(source)
            source.fill(0)
            detached.copyForStorage().fill(0)
            assertContentEquals(original, detached.copyForStorage())
            val diagnostics = detached.toString()
            assertFalse(diagnostics.contains(OWNER.actorId))
            assertFalse(diagnostics.contains(fixture.vault.keys.keys.single()))
            assertFalse(diagnostics.contains(original.copyOfRange(1, 65).decodeToString()))
            for (size in listOf(0, 1, StateRetirementTarget.ENCODED_SIZE - 1, StateRetirementTarget.ENCODED_SIZE + 1, 4096))
                assertFailsWith<IllegalArgumentException> { StateRetirementTarget(ByteArray(size)) }
        }
    }

    @Test fun exactRetirementDeletesKeyBeforeOwnerSqlMutationAndFencesRetainedHandles() = runBlocking {
        withRetirementFixture { fixture ->
            val db = fixture.open()
            val store = retirementValue(db.activate(OWNER))
            val retained = assertNotNull(retirementValue(db.resume(OWNER)))
            retirementValue(store.commit(OWNER, listOf(put("retire-original"))))
            val target = assertNotNull(retirementValue(db.captureRetirement(OWNER)))
            val oldKey = fixture.vault.keys.keys.single()
            fixture.control.beforeMutation = {
                assertFalse(oldKey in fixture.vault.keys, "Retirement SQL must not precede destructive key removal")
            }
            retirementValue(db.recoverRetirement(target))
            fixture.control.beforeMutation = null
            assertFalse(oldKey in fixture.vault.keys)
            assertEquals(0L, fixture.scalar("SELECT count(*) FROM feedme_records"))
            assertEquals(0L, fixture.scalar("SELECT active FROM feedme_owners"))
            assertNull(retirementValue(db.resume(OWNER)))
            assertNull(retirementValue(db.captureRetirement(OWNER)))
            failure(FailureReason.STALE_SESSION, store.read(OWNER, KEY))
            failure(FailureReason.STALE_SESSION, retained.commit(OWNER, listOf(put("late-private-write"))))
        }
    }

    @Test fun keyDeletedBeforeSqlCommitFailureRemainsUnreadableAfterReopenAndExactTargetFinishesCleanup() = runBlocking {
        withRetirementFixture { fixture ->
            val db = fixture.open()
            val store = retirementValue(db.activate(OWNER))
            retirementValue(store.commit(OWNER, listOf(put("must-not-resurrect"))))
            val serialized = assertNotNull(retirementValue(db.captureRetirement(OWNER))).copyForStorage()
            val oldKey = fixture.vault.keys.keys.single()
            fixture.control.commitFault = RetirementCommitFault.BEFORE
            failure(FailureReason.STORAGE_FAILURE, db.recoverRetirement(StateRetirementTarget(serialized)))
            assertFalse(oldKey in fixture.vault.keys)
            assertEquals(1L, fixture.scalar("SELECT active FROM feedme_owners"))
            assertEquals(1L, fixture.scalar("SELECT count(*) FROM feedme_records"))
            failure(FailureReason.STALE_SESSION, store.read(OWNER, KEY))
            retirementValue(db.close())

            val reopened = fixture.open()
            val creates = fixture.vault.creates
            rejected(reopened.resume(OWNER))
            rejected(reopened.activate(OWNER))
            rejected(reopened.captureRetirement(OWNER))
            assertEquals(creates, fixture.vault.creates, "Missing owner key must not be silently regenerated")
            retirementValue(reopened.recoverRetirement(StateRetirementTarget(serialized)))
            assertNull(retirementValue(reopened.resume(OWNER)))
            assertEquals(0L, fixture.scalar("SELECT count(*) FROM feedme_records"))
            assertEquals(0L, fixture.scalar("SELECT active FROM feedme_owners"))
            retirementValue(reopened.close())

            val final = fixture.open()
            retirementValue(final.recoverRetirement(StateRetirementTarget(serialized)))
            assertNull(retirementValue(final.resume(OWNER)))
            assertTrue(fixture.vault.keys.isEmpty())
            assertEquals(creates, fixture.vault.creates)
        }
    }

    @Test fun committedSqlResponseLossIsUnknownButExactRecoveryIsIdempotentAfterReopen() = runBlocking {
        withRetirementFixture { fixture ->
            val db = fixture.open()
            val store = retirementValue(db.activate(OWNER))
            retirementValue(store.commit(OWNER, listOf(put("committed-retirement"))))
            val target = assertNotNull(retirementValue(db.captureRetirement(OWNER)))
            fixture.control.commitFault = RetirementCommitFault.AFTER
            failure(FailureReason.OUTCOME_UNKNOWN, db.recoverRetirement(target))
            assertTrue(fixture.vault.keys.isEmpty())
            retirementValue(db.close())
            val reopened = fixture.open()
            retirementValue(reopened.recoverRetirement(StateRetirementTarget(target.copyForStorage())))
            assertNull(retirementValue(reopened.resume(OWNER)))
            assertEquals(0L, fixture.scalar("SELECT count(*) FROM feedme_records"))
            assertEquals(0L, fixture.scalar("SELECT active FROM feedme_owners"))
        }
    }

    @Test fun repeatedExactRetiredTargetDoesNotAdvanceGenerationOrCreateAnOwner() = runBlocking {
        withRetirementFixture { fixture ->
            val db = fixture.open()
            retirementValue(db.activate(OWNER))
            val target = assertNotNull(retirementValue(db.captureRetirement(OWNER)))
            retirementValue(db.recoverRetirement(target))
            val generation = fixture.scalar("SELECT generation FROM feedme_owners")
            val creates = fixture.vault.creates
            repeat(3) { retirementValue(db.recoverRetirement(StateRetirementTarget(target.copyForStorage()))) }
            assertEquals(generation, fixture.scalar("SELECT generation FROM feedme_owners"))
            assertEquals(0L, fixture.scalar("SELECT active FROM feedme_owners"))
            assertEquals(creates, fixture.vault.creates)
            assertNull(retirementValue(db.resume(OWNER)))
        }
    }

    @Test fun malformedAndUnauthenticatedTargetCannotDeleteAnyOwnerKeyOrRow() = runBlocking {
        withRetirementFixture { fixture ->
            val db = fixture.open()
            val store = retirementValue(db.activate(OWNER))
            retirementValue(store.commit(OWNER, listOf(put("authenticated-original"))))
            val target = assertNotNull(retirementValue(db.captureRetirement(OWNER))).copyForStorage()
            val keys = fixture.vault.keys.keys.toSet()
            val badTargets = listOf(
                ByteArray(StateRetirementTarget.ENCODED_SIZE),
                target.copyOf().also { it[0] = 2 },
                target.copyOf().also { it[1] = (if (it[1] == '0'.code.toByte()) '1' else '0').code.toByte() },
                target.copyOf().also { it[72] = (it[72].toInt() xor 1).toByte() },
                target.copyOf().also { it[73] = (if (it[73] == '0'.code.toByte()) '1' else '0').code.toByte() },
                target.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() },
            )
            for (bytes in badTargets) {
                failure(FailureReason.INVALID_DATA, db.validateRetirement(OWNER, StateRetirementTarget(bytes)))
                failure(FailureReason.INVALID_DATA, db.recoverRetirement(StateRetirementTarget(bytes)))
            }
            assertEquals(keys, fixture.vault.keys.keys)
            assertTrue(fixture.vault.deleted.isEmpty())
            assertEquals(1L, fixture.scalar("SELECT active FROM feedme_owners"))
            assertRecord(store, "authenticated-original")
        }
    }

    @Test fun swappingAuthenticatedOwnerAndKeyFieldsCannotDeleteForeignOwner() = runBlocking {
        withRetirementFixture { fixture ->
            val db = fixture.open()
            val first = retirementValue(db.activate(OWNER))
            val second = retirementValue(db.activate(OTHER))
            retirementValue(first.commit(OWNER, listOf(put("first-owner-state"))))
            retirementValue(second.commit(OTHER, listOf(put("second-owner-state"))))
            val target = assertNotNull(retirementValue(db.captureRetirement(OWNER))).copyForStorage()
            val foreign = assertNotNull(retirementValue(db.captureRetirement(OTHER))).copyForStorage()
            val keys = fixture.vault.keys.keys.toSet()
            for (range in listOf(1 until 65, 73 until 105, 1 until 105)) {
                val forged = target.copyOf()
                range.forEach { forged[it] = foreign[it] }
                failure(FailureReason.INVALID_DATA, db.recoverRetirement(StateRetirementTarget(forged)))
            }
            assertEquals(keys, fixture.vault.keys.keys)
            assertTrue(fixture.vault.deleted.isEmpty())
            assertRecord(first, "first-owner-state")
            assertRecord(second, "second-owner-state", OTHER)
        }
    }

    @Test fun targetAuthenticatedByAnotherVaultCannotRetireMatchingScope() = runBlocking {
        withRetirementFixture { firstFixture -> withRetirementFixture { secondFixture ->
            val first = firstFixture.open()
            retirementValue(first.activate(OWNER))
            val target = assertNotNull(retirementValue(first.captureRetirement(OWNER)))
            val second = secondFixture.open()
            val store = retirementValue(second.activate(OWNER))
            retirementValue(store.commit(OWNER, listOf(put("other-vault-private-state"))))
            val keys = secondFixture.vault.keys.keys.toSet()
            failure(FailureReason.INVALID_DATA, second.recoverRetirement(StateRetirementTarget(target.copyForStorage())))
            assertEquals(keys, secondFixture.vault.keys.keys)
            assertTrue(secondFixture.vault.deleted.isEmpty())
            assertRecord(store, "other-vault-private-state")
        } }
    }

    @Test fun sharedActiveOwnerKeyReferenceBlocksDestructionEvenForAuthenticTarget() = runBlocking {
        withRetirementFixture { fixture ->
            val db = fixture.open()
            retirementValue(db.activate(OWNER))
            retirementValue(db.activate(OTHER))
            val target = assertNotNull(retirementValue(db.captureRetirement(OWNER)))
            val serialized = target.copyForStorage()
            val ownerTag = serialized.copyOfRange(1, 65).decodeToString()
            val ownerKey = serialized.copyOfRange(73, 105).decodeToString()
            val keys = fixture.vault.keys.keys.toSet()
            retirementValue(db.close())
            fixture.raw { prepare("UPDATE feedme_owners SET key_id=? WHERE owner_tag<>?").use { statement ->
                statement.bindText(1, ownerKey); statement.bindText(2, ownerTag); while (statement.step()) Unit
            } }
            val reopened = fixture.open()
            failure(FailureReason.STORAGE_FAILURE, reopened.validateRetirement(OWNER, StateRetirementTarget(serialized)))
            failure(FailureReason.STORAGE_FAILURE, reopened.recoverRetirement(StateRetirementTarget(serialized)))
            assertEquals(keys, fixture.vault.keys.keys)
            assertTrue(fixture.vault.deleted.isEmpty())
            assertEquals(2L, fixture.scalar("SELECT count(*) FROM feedme_owners WHERE active=1"))
        }
    }

    @Test fun oldTargetCannotDeleteNewlyActivatedIncarnationOrItsPrivateState() = runBlocking {
        withRetirementFixture { fixture ->
            val db = fixture.open()
            retirementValue(db.activate(OWNER))
            val old = assertNotNull(retirementValue(db.captureRetirement(OWNER)))
            retirementValue(db.recoverRetirement(old))
            val replacement = retirementValue(db.activate(OWNER))
            retirementValue(replacement.commit(OWNER, listOf(put("new-incarnation-private-state"))))
            val replacementTarget = assertNotNull(retirementValue(db.captureRetirement(OWNER)))
            val keys = fixture.vault.keys.keys.toSet()
            val deletes = fixture.vault.deleted.toList()
            val generation = fixture.scalar("SELECT generation FROM feedme_owners")
            failure(FailureReason.STALE_SESSION, db.validateRetirement(OWNER, old))
            failure(FailureReason.STALE_SESSION, db.recoverRetirement(StateRetirementTarget(old.copyForStorage())))
            assertEquals(keys, fixture.vault.keys.keys)
            assertEquals(deletes, fixture.vault.deleted)
            assertEquals(generation, fixture.scalar("SELECT generation FROM feedme_owners"))
            assertRecord(replacement, "new-incarnation-private-state")
            assertFalse(old.copyForStorage().contentEquals(replacementTarget.copyForStorage()))
            retirementValue(db.close())
            val reopened = fixture.open()
            failure(FailureReason.STALE_SESSION, reopened.recoverRetirement(old))
            assertRecord(assertNotNull(retirementValue(reopened.resume(OWNER))), "new-incarnation-private-state")
        }
    }

    @Test fun unavailableOwnerKeyCannotBeCapturedResumedOrSilentlyHealed() = runBlocking {
        withRetirementFixture { fixture ->
            val db = fixture.open()
            val store = retirementValue(db.activate(OWNER))
            retirementValue(store.commit(OWNER, listOf(put("missing-key-private-state"))))
            val original = assertNotNull(retirementValue(db.captureRetirement(OWNER)))
            fixture.vault.keys.clear()
            val creates = fixture.vault.creates
            retirementValue(db.validateRetirement(OWNER, original))
            rejected(db.captureRetirement(OWNER))
            rejected(db.resume(OWNER))
            rejected(db.activate(OWNER))
            rejected(store.read(OWNER, KEY))
            assertEquals(creates, fixture.vault.creates)
            retirementValue(db.close())
            val reopened = fixture.open()
            rejected(reopened.resume(OWNER))
            assertEquals(creates, fixture.vault.creates)
            retirementValue(reopened.recoverRetirement(StateRetirementTarget(original.copyForStorage())))
            assertNull(retirementValue(reopened.resume(OWNER)))
            assertEquals(creates, fixture.vault.creates)
        }
    }

    @Test fun failedNativeKeyDeletionIsNotReportedAsRetirementSuccess() = runBlocking {
        withRetirementFixture { fixture ->
            val db = fixture.open()
            val store = retirementValue(db.activate(OWNER))
            retirementValue(store.commit(OWNER, listOf(put("must-not-report-erased"))))
            val target = assertNotNull(retirementValue(db.captureRetirement(OWNER)))
            val keys = fixture.vault.keys.keys.toSet()
            fixture.vault.failDelete = true
            failure(FailureReason.STORAGE_FAILURE, db.recoverRetirement(target))
            assertEquals(keys, fixture.vault.keys.keys)
            // The durable SQL fence and exact-key GC fallback still commit when native deletion
            // fails, but missing cryptographic erasure must never be reported as full success.
            assertEquals(0L, fixture.scalar("SELECT active FROM feedme_owners"))
            assertEquals(0L, fixture.scalar("SELECT count(*) FROM feedme_records"))
            assertEquals(1L, fixture.scalar("SELECT count(*) FROM feedme_key_gc"))
            fixture.vault.failDelete = false
            retirementValue(db.recoverRetirement(target))
            assertTrue(fixture.vault.keys.isEmpty())
            assertNull(retirementValue(db.resume(OWNER)))
        }
    }

    @Test fun validatingAuthenticTargetAgainstDifferentScopeHasNoRetirementSideEffects() = runBlocking {
        withRetirementFixture { fixture ->
            val db = fixture.open()
            val store = retirementValue(db.activate(OWNER))
            retirementValue(store.commit(OWNER, listOf(put("scope-validation-private-state"))))
            val target = assertNotNull(retirementValue(db.captureRetirement(OWNER)))
            val keys = fixture.vault.keys.keys.toSet()
            val before = Files.readAllBytes(fixture.file)
            fixture.control.executed.clear()
            failure(FailureReason.STALE_SESSION, db.validateRetirement(OTHER, target))
            failure(FailureReason.STALE_SESSION, db.validateRetirement(OWNER.copy(environment = "different-environment"), target))
            assertEquals(keys, fixture.vault.keys.keys)
            assertTrue(fixture.vault.deleted.isEmpty())
            assertFalse(fixture.control.executed.any(::isMutation))
            assertContentEquals(before, Files.readAllBytes(fixture.file))
            assertRecord(store, "scope-validation-private-state")
        }
    }

    @Test fun validatingExactAlreadyRetiredTargetIsReadOnlyAndDoesNotRepeatKeyDeletion() = runBlocking {
        withRetirementFixture { fixture ->
            val db = fixture.open()
            retirementValue(db.activate(OWNER))
            val target = assertNotNull(retirementValue(db.captureRetirement(OWNER)))
            retirementValue(db.recoverRetirement(target))
            val deletes = fixture.vault.deleted.toList()
            val generation = fixture.scalar("SELECT generation FROM feedme_owners")
            val before = Files.readAllBytes(fixture.file)
            fixture.control.executed.clear()
            retirementValue(db.validateRetirement(OWNER, StateRetirementTarget(target.copyForStorage())))
            assertFalse(fixture.control.executed.any(::isMutation))
            assertContentEquals(before, Files.readAllBytes(fixture.file))
            assertEquals(deletes, fixture.vault.deleted)
            assertEquals(generation, fixture.scalar("SELECT generation FROM feedme_owners"))
            assertNull(retirementValue(db.resume(OWNER)))
        }
    }

    @Test fun scopedEraseSharesKeyFirstFailureAndExactRecoveryProtocol() = runBlocking {
        withRetirementFixture { fixture ->
            val db = fixture.open()
            val store = retirementValue(db.activate(OWNER))
            retirementValue(store.commit(OWNER, listOf(put("scoped-erase-private-state"))))
            val target = assertNotNull(retirementValue(db.captureRetirement(OWNER)))
            fixture.control.commitFault = RetirementCommitFault.BEFORE
            failure(FailureReason.STORAGE_FAILURE, store.eraseScope(OWNER))
            assertTrue(fixture.vault.keys.isEmpty())
            failure(FailureReason.STALE_SESSION, store.read(OWNER, KEY))
            retirementValue(db.close())
            val reopened = fixture.open()
            rejected(reopened.resume(OWNER))
            retirementValue(reopened.recoverRetirement(target))
            assertNull(retirementValue(reopened.resume(OWNER)))
            assertEquals(0L, fixture.scalar("SELECT count(*) FROM feedme_records"))
        }
    }

    @Test fun cancellationBeforeOwnerDispatcherStartsCannotDeleteKeyOrChangeSql() = runTest {
        withRetirementFixture { fixture ->
            val dispatcher = StandardTestDispatcher(testScheduler)
            val db = fixture.open(dispatcher)
            val store = retirementValue(db.activate(OWNER))
            retirementValue(store.commit(OWNER, listOf(put("cancel-before-private-state"))))
            val target = assertNotNull(retirementValue(db.captureRetirement(OWNER)))
            val keys = fixture.vault.keys.keys.toSet()
            val pending = async(start = CoroutineStart.UNDISPATCHED) { db.recoverRetirement(target) }
            pending.cancel()
            assertFailsWith<CancellationException> { pending.await() }
            assertEquals(keys, fixture.vault.keys.keys)
            assertTrue(fixture.vault.deleted.isEmpty())
            assertRecord(store, "cancel-before-private-state")
        }
    }

    @Test fun cancellationAfterKeyDeletionNeverRestoresReadableOldIncarnationAfterReopen() = runTest {
        withRetirementFixture { fixture ->
            val dispatcher = StandardTestDispatcher(testScheduler)
            val db = fixture.open(dispatcher)
            val store = retirementValue(db.activate(OWNER))
            retirementValue(store.commit(OWNER, listOf(put("cancel-after-private-state"))))
            val target = assertNotNull(retirementValue(db.captureRetirement(OWNER)))
            val pending = async(start = CoroutineStart.UNDISPATCHED) { db.recoverRetirement(target) }
            fixture.vault.afterDelete = { pending.cancel() }
            assertFailsWith<CancellationException> { pending.await() }
            fixture.vault.afterDelete = null
            assertTrue(fixture.vault.keys.isEmpty())
            retirementValue(db.close())
            val reopened = fixture.open(dispatcher)
            when (val resumed = reopened.resume(OWNER)) {
                is PortResult.Value -> assertNull(resumed.value)
                is PortResult.Failure -> assertEquals(FailureReason.STORAGE_FAILURE, resumed.reason)
            }
            retirementValue(reopened.recoverRetirement(StateRetirementTarget(target.copyForStorage())))
            assertNull(retirementValue(reopened.resume(OWNER)))
            assertEquals(0L, fixture.scalar("SELECT count(*) FROM feedme_records"))
        }
    }

    private suspend fun assertRecord(store: PrivateStateStore, text: String, scope: StorageScope = OWNER) {
        val record = assertNotNull(retirementValue(store.read(scope, KEY)))
        assertContentEquals(text.encodeToByteArray(), record.payload.copyForCodec())
    }
    private suspend fun withRetirementFixture(block: suspend (RetirementFixture) -> Unit) {
        val fixture = RetirementFixture()
        try { block(fixture) } finally { fixture.close() }
    }
    companion object {
        private val OWNER = StorageScope("retirement-integration", ActorKind.ACCOUNT, "private-owner-901c")
        private val OTHER = StorageScope("retirement-integration", ActorKind.ACCOUNT, "other-private-owner-992e")
        private val KEY = RecordKey("private-retirement-draft", "private-record-190a")
        private fun put(text: String) = StoreMutation.Put(KEY, null, 1, PrivateBytes(text.encodeToByteArray()))
        private fun failure(reason: FailureReason, result: PortResult<*>) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
        private fun rejected(result: PortResult<*>) { assertIs<PortResult.Failure>(result) }
    }
}

private fun <T> retirementValue(result: PortResult<T>): T = when (result) {
    is PortResult.Value -> result.value
    is PortResult.Failure -> fail("Expected retirement success, got ${result.reason}")
}

private class RetirementFixture {
    private val directory = Files.createTempDirectory("feedme-retirement-sqlite-test-")
    val file: Path = directory.resolve("state.sqlite")
    val vault = RetirementVault()
    private val opened = mutableListOf<EncryptedStateDatabase>()
    lateinit var control: RetirementConnection
    suspend fun open(dispatcher: CoroutineDispatcher = Dispatchers.IO): EncryptedStateDatabase {
        control = RetirementConnection(BundledSQLiteDriver().open(file.toString()))
        return retirementValue(EncryptedStateDatabase.open(control, vault, dispatcher)).also { opened += it }
    }
    fun raw(action: SQLiteConnection.() -> Unit) { BundledSQLiteDriver().open(file.toString()).use(action) }
    fun scalar(sql: String): Long = BundledSQLiteDriver().open(file.toString()).use { connection ->
        connection.prepare(sql).use { statement -> assertTrue(statement.step()); statement.getLong(0) }
    }
    suspend fun close() {
        try { opened.forEach { it.close() } } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

private enum class RetirementCommitFault { BEFORE, AFTER }
private class RetirementConnection(private val delegate: SQLiteConnection) : SQLiteConnection by delegate {
    val executed = mutableListOf<String>()
    var commitFault: RetirementCommitFault? = null
    var beforeMutation: (() -> Unit)? = null
    override fun prepare(sql: String): SQLiteStatement {
        val statement = delegate.prepare(sql)
        return object : SQLiteStatement by statement {
            override fun step(): Boolean {
                executed += sql
                if (isMutation(sql)) beforeMutation?.invoke()
                val isCommit = sql.trim().startsWith("COMMIT", ignoreCase = true)
                val fault = if (isCommit) commitFault.also { commitFault = null } else null
                if (fault == RetirementCommitFault.BEFORE) error("Injected precommit failure")
                val result = statement.step()
                if (fault == RetirementCommitFault.AFTER) error("Injected committed response loss")
                return result
            }
        }
    }
}

private fun isMutation(sql: String): Boolean {
    val normalized = sql.trim().uppercase()
    return listOf("INSERT ", "UPDATE ", "DELETE ", "CREATE ", "ALTER ", "DROP ").any(normalized::startsWith)
}

/** Test-only JCA material is retained across reopen; no native key availability guarantee implied. */
private class RetirementVault : StateVault {
    private val random = SecureRandom()
    private val indexKey = SecretKeySpec(ByteArray(32).also(random::nextBytes), "HmacSHA256")
    val keys = mutableMapOf<String, SecretKey>()
    val deleted = mutableListOf<String>()
    var creates = 0
    var seals = 0
    var failDelete = false
    var afterDelete: (() -> Unit)? = null
    override fun index(input: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(indexKey); doFinal(input) }
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
    override fun open(keyId: String, ciphertext: ByteArray, associatedData: ByteArray): ByteArray = try {
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
