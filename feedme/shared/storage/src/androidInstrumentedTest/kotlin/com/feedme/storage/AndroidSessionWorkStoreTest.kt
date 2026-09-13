package com.feedme.storage

import android.system.Os
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real native work factory path with isolated test-owned files and Keystore keys. */
@RunWith(AndroidJUnit4::class)
class AndroidSessionWorkStoreTest {
    private lateinit var sandbox: AndroidStateTestSandbox
    private val sandboxes = mutableListOf<AndroidStateTestSandbox>()
    private val openedWorks = mutableListOf<EncryptedSessionWorkStore>()
    private val openedManagers = mutableListOf<EncryptedStateDatabase>()

    @Before
    fun setUp() {
        sandbox = newSandbox()
    }

    @After
    fun tearDown() = runBlocking {
        try {
            openedWorks.asReversed().forEach { it.close().valueOrFail() }
            openedManagers.asReversed().forEach { it.close().valueOrFail() }
        } finally {
            openedWorks.clear()
            openedManagers.clear()
            sandboxes.asReversed().forEach(AndroidStateTestSandbox::close)
            sandboxes.clear()
        }
    }

    @Test
    fun nativeWorkCasReopensAndSurvivesIndependentControlWritesAndOwnerKeyErasure() = runBlocking {
        val first = openWork()
        val initial = first.read().valueOrFail()
        assertNotNull(initial)
        assertEquals(1L, initial!!.revision)
        assertArrayEquals("{\"version\":1,\"state\":\"idle\"}".encodeToByteArray(), initial.payload.copyForCodec())
        assertEquals(448, Os.lstat(sandbox.directory.path).st_mode and 511) // 0700
        assertEquals(384, Os.lstat(databaseFile().path).st_mode and 511) // 0600

        val marker = "private-native-retirement-evidence-3279b5"
        val expected = " {\n\"version\":1,\"state\":\"retiring\",\"opaqueTarget\":\"$marker\"\n} ".encodeToByteArray()
        val committed = first.compareAndSet(initial.revision, PrivateBytes(expected)).valueOrFail()
        assertEquals(2L, committed.revision)
        assertArrayEquals(expected, committed.payload.copyForCodec())
        assertEquals(PortResult.Failure(FailureReason.CONFLICT), first.compareAndSet(1, bytes("stale")))
        assertEquals(PortResult.Failure(FailureReason.CONFLICT), first.compareAndSet(null, bytes("must-not-reset")))
        val workAliases = aliases(sandbox)
        assertEquals(2, workAliases.size)
        closeWork(first)
        assertNoPlaintext(marker)

        val controlSandbox = newSandbox()
        val control = AndroidStateDatabase.openOwned(
            location = { controlSandbox.directory to controlSandbox.keyPrefix },
            requireNewVaultForNewFile = true,
            wrap = { database, fileCreated -> EncryptedSessionControlStore.open(database, allowInitialize = fileCreated) },
        ).valueOrFail()
        try {
            control.compareAndSet(1, bytes("independent-local-retirement-checkpoint")).valueOrFail()
            assertEquals(workAliases, aliases(sandbox))
            assertEquals(2, aliases(controlSandbox).size)
        } finally {
            control.close().valueOrFail()
        }

        val dataSandbox = newSandbox()
        val dataManager = openDataManager(dataSandbox)
        val dataScope = StorageScope("native-retirement-instrumented", ActorKind.ACCOUNT, "private-test-owner")
        val dataStore = dataManager.activate(dataScope).valueOrFail()
        val dataAlias = aliases(dataSandbox).single { it.startsWith("${dataSandbox.keyPrefix}.owner.") }
        dataStore.commit(dataScope, listOf(
            StoreMutation.Put(RecordKey("private-draft", "one"), null, 1, bytes("private-owner-draft")),
        )).valueOrFail()
        dataStore.eraseScope(dataScope).valueOrFail()
        assertFalse(dataSandbox.keyStore().containsAlias(dataAlias))
        assertEquals(workAliases, aliases(sandbox))
        closeDataManager(dataManager)

        val reopened = openWork()
        val restored = reopened.read().valueOrFail()
        assertNotNull(restored)
        assertEquals(2L, restored!!.revision)
        assertArrayEquals(expected, restored.payload.copyForCodec())
        assertEquals(workAliases, aliases(sandbox))
        assertEquals(3L, reopened.compareAndSet(2, bytes("{\"version\":1,\"state\":\"idle\"}")).valueOrFail().revision)
    }

    @Test
    fun duplicateWorkOpenFailsWhileLifetimeLockIsHeldAndReopensAfterClose() = runBlocking {
        val first = openWork()
        val expected = "opaque-held-retirement-evidence".encodeToByteArray()
        first.compareAndSet(1, PrivateBytes(expected)).valueOrFail()

        assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE), openWorkResult())
        assertArrayEquals(expected, first.read().valueOrFail()!!.payload.copyForCodec())

        closeWork(first)
        val reopened = openWork()
        assertArrayEquals(expected, reopened.read().valueOrFail()!!.payload.copyForCodec())
    }

    @Test
    fun missingWorkDatabaseWithSurvivingKeysFailsWithoutCreatingReplacement() = runBlocking {
        val first = openWork()
        first.compareAndSet(1, bytes("retirement-must-not-become-idle")).valueOrFail()
        closeWork(first)
        val before = aliases(sandbox)
        assertEquals(2, before.size)
        // Delete only this exact test-owned file, leaving the native namespace as recovery evidence.
        Files.delete(databaseFile().toPath())
        assertFalse(databaseFile().exists())

        repeat(2) {
            assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE), openWorkResult())
            assertFalse("Failure must not initialize another work file", databaseFile().exists())
            assertEquals(before, aliases(sandbox))
        }
    }

    @Test
    fun existingDatabaseWithMissingLedgerFailsWithoutResettingItsOwner() = runBlocking {
        closeWork(openWork())
        val before = aliases(sandbox)
        executeSqlOnClosedDatabase("DELETE FROM feedme_records")

        repeat(2) {
            assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE), openWorkResult())
            assertEquals(before, aliases(sandbox))
        }
        // A raw test-only opener proves the failed wrapper released its lifetime lock and preserved
        // the existing owner; no production caller receives this underlying store handle.
        val manager = openDataManager(sandbox)
        val scope = StorageScope("feedme-session-work-v1", ActorKind.DEMO, "install-work")
        val owner = manager.resume(scope).valueOrFail()
        assertNotNull(owner)
        assertNull(owner!!.read(scope, RecordKey("session-work", "native-work-ledger")).valueOrFail())
        assertEquals(before, aliases(sandbox))
    }

    @Test
    fun existingDatabaseWithMissingWorkOwnerFailsWithoutActivatingIt() = runBlocking {
        // Simulates an interrupted first setup after SQLite creation, before the work owner exists.
        closeDataManager(openDataManager(sandbox))
        val before = aliases(sandbox)
        assertEquals(setOf("${sandbox.keyPrefix}.index"), before)

        assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE), openWorkResult())
        assertEquals(before, aliases(sandbox))
        val manager = openDataManager(sandbox)
        assertNull(manager.resume(StorageScope("feedme-session-work-v1", ActorKind.DEMO, "install-work")).valueOrFail())
        assertEquals(before, aliases(sandbox))
    }

    @Test
    fun missingWorkOwnerKeyFailsWithoutGeneratingAnotherKey() = runBlocking {
        closeWork(openWork())
        val missingAlias = aliases(sandbox).single { it.startsWith("${sandbox.keyPrefix}.owner.") }
        sandbox.keyStore().deleteEntry(missingAlias)
        val remaining = aliases(sandbox)

        repeat(2) {
            assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE), openWorkResult())
            assertFalse(sandbox.keyStore().containsAlias(missingAlias))
            assertEquals(remaining, aliases(sandbox))
        }
    }

    @Test
    fun missingWorkIndexKeyFailsWithoutRebindingExistingState() = runBlocking {
        closeWork(openWork())
        val missingAlias = "${sandbox.keyPrefix}.index"
        sandbox.keyStore().deleteEntry(missingAlias)
        val remaining = aliases(sandbox)

        repeat(2) {
            assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE), openWorkResult())
            assertFalse(sandbox.keyStore().containsAlias(missingAlias))
            assertEquals(remaining, aliases(sandbox))
        }
    }

    @Test
    fun existingEmptyLockOnlyAndLostAllStateDirectoriesCannotInitializeAnIdleRegistry() = runBlocking {
        for (state in listOf("empty", "lock-only", "lost-all-state")) {
            sandbox = newSandbox()
            if (state == "lost-all-state") {
                val work = openWork()
                work.compareAndSet(1, bytes("exact-cancellation-evidence-must-not-reset")).valueOrFail()
                closeWork(work)
                Files.delete(databaseFile().toPath())
                aliases(sandbox).forEach(sandbox.keyStore()::deleteEntry)
            } else {
                Os.mkdir(sandbox.directory.path, 448)
                if (state == "lock-only") {
                    val lock = File(sandbox.directory, "state.lock")
                    Files.createFile(lock.toPath())
                    Os.chmod(lock.path, 384)
                }
            }
            assertFalse(databaseFile().exists())
            assertEquals(emptySet<String>(), aliases(sandbox))
            repeat(2) {
                assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE), openWorkResult())
                assertFalse("Existing directory is not first-use authority", databaseFile().exists())
                assertEquals("Failed open must not create a new namespace", emptySet<String>(), aliases(sandbox))
            }
        }
    }

    private fun newSandbox() = AndroidStateTestSandbox().also { sandboxes += it }
    private fun bytes(value: String) = PrivateBytes(value.encodeToByteArray())
    private fun databaseFile() = File(sandbox.directory, "state.sqlite")
    private fun aliases(target: AndroidStateTestSandbox) = target.keyStore().aliases().toList()
        .filter { it.startsWith("${target.keyPrefix}.") }.toSet()

    private suspend fun openWork(): EncryptedSessionWorkStore = openWorkResult().valueOrFail()

    private suspend fun openWorkResult(): PortResult<EncryptedSessionWorkStore> = AndroidStateDatabase.openOwned(
        location = { sandbox.directory to sandbox.keyPrefix },
        requireNewVaultForNewFile = true,
        requireNewDirectoryForInitialization = true,
        wrap = { database, fileCreated -> EncryptedSessionWorkStore.open(database, allowInitialize = fileCreated) },
    ).also { result ->
        // Also own an unexpected success, so assertion failure never leaks its connection or lock.
        if (result is PortResult.Value) openedWorks += result.value
    }

    private suspend fun closeWork(work: EncryptedSessionWorkStore) {
        work.close().valueOrFail()
        openedWorks.remove(work)
    }

    private suspend fun openDataManager(target: AndroidStateTestSandbox): EncryptedStateDatabase =
        AndroidStateDatabase.openForTests(target.directory, target.keyPrefix).valueOrFail().also { openedManagers += it }

    private suspend fun closeDataManager(manager: EncryptedStateDatabase) {
        manager.close().valueOrFail()
        openedManagers.remove(manager)
    }

    private fun executeSqlOnClosedDatabase(sql: String) {
        val connection = BundledSQLiteDriver().open(databaseFile().path)
        try {
            val statement = connection.prepare(sql)
            try { statement.step() } finally { statement.close() }
        } finally { connection.close() }
    }

    private fun assertNoPlaintext(marker: String) {
        sandbox.directory.listFiles()!!.filter(File::isFile).forEach { file ->
            val data = file.readBytes()
            for (encoding in listOf(Charsets.UTF_8, Charsets.UTF_16LE)) {
                val needle = marker.toByteArray(encoding)
                assertFalse("Work metadata must not appear in native database or journal files", data.indices.any { start ->
                    start + needle.size <= data.size && needle.indices.all { data[start + it] == needle[it] }
                })
            }
        }
    }
}
