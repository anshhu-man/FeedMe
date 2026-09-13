package com.feedme.storage

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.feedme.core.ports.ActorKind
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.PrivateBytes
import com.feedme.core.ports.RecordKey
import com.feedme.core.ports.StorageScope
import com.feedme.core.ports.StoreMutation
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Real bundled SQLite and test-only JCA keys; not native Keystore or process-hard-kill evidence. */
class SessionWorkStoreTest {
    private val workScope = StorageScope("feedme-session-work-v1", ActorKind.DEMO, "install-work")
    private val workKey = RecordKey("session-work", "native-work-ledger")

    @Test fun initializesOnlyNewStoreAndReopensExactEncryptedPayloadAndRevision() = runTest {
        withFixture(StandardTestDispatcher(testScheduler)) { fixture ->
            val work = workValue(EncryptedSessionWorkStore.open(fixture.open(), allowInitialize = true))
            val initial = assertNotNull(workValue(work.read()))
            assertEquals(1, initial.revision)
            assertEquals("{\"version\":1,\"state\":\"idle\"}", initial.payload.copyForCodec().decodeToString())
            val text = " {\n\"version\":1,\"state\":\"retiring\",\"opaqueTarget\":\"private-retirement-marker-e8b194\"\n} "
            val callerBytes = text.encodeToByteArray()
            val written = workValue(work.compareAndSet(initial.revision, PrivateBytes(callerBytes)))
            callerBytes.fill(0)
            assertEquals(2, written.revision)
            assertContentEquals(text.encodeToByteArray(), written.payload.copyForCodec())
            assertFalse(written.toString().contains("private-retirement-marker"))
            workValue(work.close())
            fixture.assertNoPlaintext("private-retirement-marker-e8b194")
            fixture.assertNoPlaintext("native-work-ledger")

            val reopened = workValue(EncryptedSessionWorkStore.open(fixture.open(), allowInitialize = false))
            val restored = assertNotNull(workValue(reopened.read()))
            assertEquals(2, restored.revision)
            assertContentEquals(text.encodeToByteArray(), restored.payload.copyForCodec())
            assertEquals(FailureReason.CONFLICT, assertIs<PortResult.Failure>(reopened.compareAndSet(null, bytes("replacement"))).reason)
            assertEquals(FailureReason.CONFLICT, assertIs<PortResult.Failure>(reopened.compareAndSet(1, bytes("stale"))).reason)
            assertContentEquals(text.encodeToByteArray(), assertNotNull(workValue(reopened.read())).payload.copyForCodec())
        }
    }

    @Test fun simultaneousExactCasHasOneWinnerAndNoLostUpdate() = runTest {
        withFixture(StandardTestDispatcher(testScheduler)) { fixture ->
            val work = workValue(EncryptedSessionWorkStore.open(fixture.open(), allowInitialize = true))
            val results = listOf("first", "second").map { value -> async { work.compareAndSet(1, bytes(value)) } }.awaitAll()
            assertEquals(1, results.count { it is PortResult.Value })
            assertEquals(1, results.count { it is PortResult.Failure && it.reason == FailureReason.CONFLICT })
            val winner = results.filterIsInstance<PortResult.Value<com.feedme.core.ports.SessionControlRecord>>().single().value
            val stored = assertNotNull(workValue(work.read()))
            assertEquals(2, stored.revision)
            assertContentEquals(winner.payload.copyForCodec(), stored.payload.copyForCodec())
        }
    }

    @Test fun acceptsExact32KiBBoundAndRejectsOversizeEmptyAndInvalidRevisionsWithoutMutation() = runTest {
        withFixture(StandardTestDispatcher(testScheduler)) { fixture ->
            val work = workValue(EncryptedSessionWorkStore.open(fixture.open(), allowInitialize = true))
            val maximum = ByteArray(32 * 1024) { (it % 251).toByte() }
            assertEquals(2, workValue(work.compareAndSet(1, PrivateBytes(maximum))).revision)
            for (payload in listOf(ByteArray(32 * 1024 + 1), byteArrayOf())) {
                assertEquals(FailureReason.INVALID_DATA, assertIs<PortResult.Failure>(work.compareAndSet(2, PrivateBytes(payload))).reason)
            }
            for (revision in listOf(0L, -1L)) {
                assertEquals(FailureReason.INVALID_DATA, assertIs<PortResult.Failure>(work.compareAndSet(revision, bytes("unused"))).reason)
            }
            val actual = assertNotNull(workValue(work.read()))
            assertEquals(2, actual.revision)
            assertContentEquals(maximum, actual.payload.copyForCodec())
        }
    }

    @Test fun existingDatabaseWithoutWorkOwnerFailsClosedAndDoesNotCreateKey() = runTest {
        withFixture(StandardTestDispatcher(testScheduler)) { fixture ->
            workValue(fixture.open().close())
            assertEquals(0, fixture.vault.keyIds.size)
            val database = fixture.open()
            assertEquals(FailureReason.STORAGE_FAILURE, assertIs<PortResult.Failure>(
                EncryptedSessionWorkStore.open(database, allowInitialize = false),
            ).reason)
            // Failed wrapper construction still owns/closes the underlying manager.
            assertEquals(FailureReason.STORAGE_FAILURE, assertIs<PortResult.Failure>(database.resume(workScope)).reason)
            workValue(database.close())
            assertEquals(0, fixture.vault.keyIds.size)
        }
    }

    @Test fun interruptedSetupMissingRecordAndRetiredOwnerCannotReinitializeOnReopen() = runTest {
        for (retired in listOf(false, true)) {
            withFixture(StandardTestDispatcher(testScheduler)) { fixture ->
                val database = fixture.open()
                val owner = workValue(database.activate(workScope))
                if (retired) workValue(owner.eraseScope(workScope))
                val keysBefore = fixture.vault.keyIds
                workValue(database.close())
                assertEquals(FailureReason.STORAGE_FAILURE, assertIs<PortResult.Failure>(
                    EncryptedSessionWorkStore.open(fixture.open(), allowInitialize = false),
                ).reason)
                assertEquals(keysBefore, fixture.vault.keyIds)
            }
        }
    }

    @Test fun missingDeletedOrInvalidRecordCannotBeRepairedByReadOrCas() = runTest {
        for (damage in listOf("delete", "schema", "oversize", "ciphertext")) {
            withFixture(StandardTestDispatcher(testScheduler)) { fixture ->
                val database = fixture.open()
                val work = workValue(EncryptedSessionWorkStore.open(database, allowInitialize = true))
                // Test-only access to the owned manager creates corruption impossible through the
                // production work API, which exposes no underlying store or deletion operation.
                val owner = assertNotNull(workValue(database.resume(workScope)))
                when (damage) {
                    "delete" -> workValue(owner.commit(workScope, listOf(StoreMutation.Delete(workKey, 1))))
                    "schema" -> workValue(owner.commit(workScope, listOf(StoreMutation.Put(workKey, 1, 2, bytes("unknown-schema")))))
                    "oversize" -> workValue(owner.commit(workScope, listOf(StoreMutation.Put(workKey, 1, 1, PrivateBytes(ByteArray(32 * 1024 + 1))))))
                    "ciphertext" -> fixture.damageCiphertext()
                }
                assertEquals(FailureReason.STORAGE_FAILURE, assertIs<PortResult.Failure>(work.read()).reason)
                assertEquals(FailureReason.STORAGE_FAILURE, assertIs<PortResult.Failure>(work.compareAndSet(1, bytes("must-not-repair"))).reason)
                workValue(work.close())
                assertEquals(FailureReason.STORAGE_FAILURE, assertIs<PortResult.Failure>(
                    EncryptedSessionWorkStore.open(fixture.open(), allowInitialize = false),
                ).reason)
                assertEquals(1, fixture.vault.keyIds.size)
            }
        }
    }

    @Test fun missingWorkKeyFailsClosedWithoutGeneratingReplacement() = runTest {
        withFixture(StandardTestDispatcher(testScheduler)) { fixture ->
            val work = workValue(EncryptedSessionWorkStore.open(fixture.open(), allowInitialize = true))
            workValue(work.close())
            fixture.vault.keyIds.forEach(fixture.vault::deleteOwnerKey)
            assertEquals(FailureReason.STORAGE_FAILURE, assertIs<PortResult.Failure>(
                EncryptedSessionWorkStore.open(fixture.open(), allowInitialize = false),
            ).reason)
            assertTrue(fixture.vault.keyIds.isEmpty())
        }
    }

    @Test fun ownerDataRetirementAndControlWritesDoNotEraseSeparateWorkKeyOrEvidence() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        withFixture(dispatcher) { workFixture ->
            withFixture(dispatcher) { dataFixture ->
                val work = workValue(EncryptedSessionWorkStore.open(workFixture.open(), allowInitialize = true))
                val marker = "opaque-retirement-target-keep-until-cleanup-confirmed"
                workValue(work.compareAndSet(1, bytes(marker)))
                val workKeys = workFixture.vault.keyIds
                withFixture(dispatcher) { controlFixture ->
                    val control = workValue(EncryptedSessionControlStore.open(controlFixture.open(), allowInitialize = true))
                    val controlMarker = "independent-coordinator-checkpoint"
                    workValue(control.compareAndSet(1, bytes(controlMarker)))
                    assertEquals(workKeys, workFixture.vault.keyIds)
                    assertEquals(marker, assertNotNull(workValue(work.read())).payload.copyForCodec().decodeToString())
                    workValue(control.close())
                    val reopenedControl = workValue(EncryptedSessionControlStore.open(controlFixture.open(), allowInitialize = false))
                    assertEquals(controlMarker, assertNotNull(workValue(reopenedControl.read())).payload.copyForCodec().decodeToString())
                }
                val dataDatabase = dataFixture.open()
                val dataScope = StorageScope("independent-account-data", ActorKind.ACCOUNT, "private-account")
                val owner = workValue(dataDatabase.activate(dataScope))
                workValue(owner.commit(dataScope, listOf(StoreMutation.Put(RecordKey("draft", "one"), null, 1, bytes("private-draft")))))
                workValue(owner.eraseScope(dataScope))
                assertTrue(dataFixture.vault.keyIds.isEmpty())
                assertEquals(workKeys, workFixture.vault.keyIds)
                assertEquals(marker, assertNotNull(workValue(work.read())).payload.copyForCodec().decodeToString())
                workValue(work.close())
                val reopened = workValue(EncryptedSessionWorkStore.open(workFixture.open(), allowInitialize = false))
                assertEquals(marker, assertNotNull(workValue(reopened.read())).payload.copyForCodec().decodeToString())
            }
        }
    }

    private fun bytes(value: String) = PrivateBytes(value.encodeToByteArray())

    private suspend fun withFixture(dispatcher: CoroutineDispatcher, block: suspend (WorkStorageFixture) -> Unit) {
        val fixture = WorkStorageFixture(dispatcher)
        try { block(fixture) } finally { fixture.close() }
    }
}

private fun <T> workValue(result: PortResult<T>): T = when (result) {
    is PortResult.Value -> result.value
    is PortResult.Failure -> fail("Expected success, got ${result.reason}")
}

private class WorkStorageFixture(private val dispatcher: CoroutineDispatcher) {
    private val directory = Files.createTempDirectory("feedme-work-sqlite-test-")
    private val file = directory.resolve("work.sqlite")
    val vault = WorkStorageTestVault()
    private val databases = mutableListOf<EncryptedStateDatabase>()

    suspend fun open(): EncryptedStateDatabase = workValue(
        EncryptedStateDatabase.open(BundledSQLiteDriver().open(file.toString()), vault, dispatcher),
    ).also { databases += it }

    fun damageCiphertext() {
        val connection = BundledSQLiteDriver().open(file.toString())
        try {
            val statement = connection.prepare("UPDATE feedme_records SET payload=zeroblob(29)")
            try { statement.step() } finally { statement.close() }
        } finally { connection.close() }
    }

    fun assertNoPlaintext(marker: String) {
        Files.list(directory).use { files ->
            files.filter { Files.isRegularFile(it) }.forEach { path ->
                val bytes = Files.readAllBytes(path)
                for (encoding in listOf(Charsets.UTF_8, Charsets.UTF_16LE)) {
                    val needle = marker.toByteArray(encoding)
                    assertFalse(bytes.indices.any { start -> start + needle.size <= bytes.size && needle.indices.all { bytes[start + it] == needle[it] } })
                }
            }
        }
    }

    suspend fun close() {
        try { databases.forEach { it.close() } } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

/** Fixture-only keys retained across workled close/reopen, with no production JVM fallback. */
private class WorkStorageTestVault : StateVault {
    private val random = SecureRandom()
    private val indexKey = SecretKeySpec(ByteArray(32).also(random::nextBytes), "HmacSHA256")
    private val keys = mutableMapOf<String, SecretKey>()
    val keyIds: Set<String> get() = keys.keys.toSet()
    override fun index(input: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(indexKey); doFinal(input) }
    override fun createOwnerKey(): String = UUID.randomUUID().toString().replace("-", "").also { id ->
        keys[id] = KeyGenerator.getInstance("AES").apply { init(256, random) }.generateKey()
    }
    override fun hasOwnerKey(keyId: String): Boolean = keyId in keys
    override fun seal(keyId: String, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
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
    override fun deleteOwnerKey(keyId: String) { keys.remove(keyId) }
}
