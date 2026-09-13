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

/** Exercises the real native control factory path with isolated test-owned files and Keystore keys. */
@RunWith(AndroidJUnit4::class)
class AndroidSessionControlStoreTest {
    private lateinit var sandbox: AndroidStateTestSandbox
    private val sandboxes = mutableListOf<AndroidStateTestSandbox>()
    private val openedControls = mutableListOf<EncryptedSessionControlStore>()
    private val openedManagers = mutableListOf<EncryptedStateDatabase>()

    @Before
    fun setUp() {
        sandbox = newSandbox()
    }

    @After
    fun tearDown() = runBlocking {
        try {
            openedControls.asReversed().forEach { it.close().valueOrFail() }
            openedManagers.asReversed().forEach { it.close().valueOrFail() }
        } finally {
            openedControls.clear()
            openedManagers.clear()
            sandboxes.asReversed().forEach(AndroidStateTestSandbox::close)
            sandboxes.clear()
        }
    }

    @Test
    fun nativeControlCasReopensAndSurvivesIndependentOwnerKeyErasure() = runBlocking {
        val first = openControl()
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
        val controlAliases = aliases(sandbox)
        assertEquals(2, controlAliases.size)
        closeControl(first)
        assertNoPlaintext(marker)

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
        assertEquals(controlAliases, aliases(sandbox))
        closeDataManager(dataManager)

        val reopened = openControl()
        val restored = reopened.read().valueOrFail()
        assertNotNull(restored)
        assertEquals(2L, restored!!.revision)
        assertArrayEquals(expected, restored.payload.copyForCodec())
        assertEquals(controlAliases, aliases(sandbox))
        assertEquals(3L, reopened.compareAndSet(2, bytes("{\"version\":1,\"state\":\"idle\"}")).valueOrFail().revision)
    }

    @Test
    fun duplicateControlOpenFailsWhileLifetimeLockIsHeldAndReopensAfterClose() = runBlocking {
        val first = openControl()
        val expected = "opaque-held-retirement-evidence".encodeToByteArray()
        first.compareAndSet(1, PrivateBytes(expected)).valueOrFail()

        assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE), openControlResult())
        assertArrayEquals(expected, first.read().valueOrFail()!!.payload.copyForCodec())

        closeControl(first)
        val reopened = openControl()
        assertArrayEquals(expected, reopened.read().valueOrFail()!!.payload.copyForCodec())
    }

    @Test
    fun missingControlDatabaseWithSurvivingKeysFailsWithoutCreatingReplacement() = runBlocking {
        val first = openControl()
        first.compareAndSet(1, bytes("retirement-must-not-become-idle")).valueOrFail()
        closeControl(first)
        val before = aliases(sandbox)
        assertEquals(2, before.size)
        // Delete only this exact test-owned file, leaving the native namespace as recovery evidence.
        Files.delete(databaseFile().toPath())
        assertFalse(databaseFile().exists())

        repeat(2) {
            assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE), openControlResult())
            assertFalse("Failure must not initialize another control file", databaseFile().exists())
            assertEquals(before, aliases(sandbox))
        }
    }

    @Test
    fun existingDatabaseWithMissingLedgerFailsWithoutResettingItsOwner() = runBlocking {
        closeControl(openControl())
        val before = aliases(sandbox)
        executeSqlOnClosedDatabase("DELETE FROM feedme_records")

        repeat(2) {
            assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE), openControlResult())
            assertEquals(before, aliases(sandbox))
        }
        // A raw test-only opener proves the failed wrapper released its lifetime lock and preserved
        // the existing owner; no production caller receives this underlying store handle.
        val manager = openDataManager(sandbox)
        val scope = StorageScope("feedme-session-control-v1", ActorKind.DEMO, "install-control")
        val owner = manager.resume(scope).valueOrFail()
        assertNotNull(owner)
        assertNull(owner!!.read(scope, RecordKey("session-control", "retirement-ledger")).valueOrFail())
        assertEquals(before, aliases(sandbox))
    }

    @Test
    fun existingDatabaseWithMissingControlOwnerFailsWithoutActivatingIt() = runBlocking {
        // Simulates an interrupted first setup after SQLite creation, before the control owner exists.
        closeDataManager(openDataManager(sandbox))
        val before = aliases(sandbox)
        assertEquals(setOf("${sandbox.keyPrefix}.index"), before)

        assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE), openControlResult())
        assertEquals(before, aliases(sandbox))
        val manager = openDataManager(sandbox)
        assertNull(manager.resume(StorageScope("feedme-session-control-v1", ActorKind.DEMO, "install-control")).valueOrFail())
        assertEquals(before, aliases(sandbox))
    }

    @Test
    fun missingControlOwnerKeyFailsWithoutGeneratingAnotherKey() = runBlocking {
        closeControl(openControl())
        val missingAlias = aliases(sandbox).single { it.startsWith("${sandbox.keyPrefix}.owner.") }
        sandbox.keyStore().deleteEntry(missingAlias)
        val remaining = aliases(sandbox)

        repeat(2) {
            assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE), openControlResult())
            assertFalse(sandbox.keyStore().containsAlias(missingAlias))
            assertEquals(remaining, aliases(sandbox))
        }
    }

    @Test
    fun missingControlIndexKeyFailsWithoutRebindingExistingState() = runBlocking {
        closeControl(openControl())
        val missingAlias = "${sandbox.keyPrefix}.index"
        sandbox.keyStore().deleteEntry(missingAlias)
        val remaining = aliases(sandbox)

        repeat(2) {
            assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE), openControlResult())
            assertFalse(sandbox.keyStore().containsAlias(missingAlias))
            assertEquals(remaining, aliases(sandbox))
        }
    }

    @Test
    fun existingEmptyLockOnlyAndLostAllStateDirectoriesCannotResetRetirementControl() = runBlocking {
        for (state in listOf("empty", "lock-only", "lost-all-state")) {
            sandbox = newSandbox()
            if (state == "lost-all-state") {
                val control = openControl()
                control.compareAndSet(1, bytes("original-retirement-barrier-must-not-reset")).valueOrFail()
                closeControl(control)
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
                assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE), openControlResult())
                assertFalse("An existing directory cannot authorize a fresh idle retirement record", databaseFile().exists())
                assertEquals("Failed open must not create a replacement control namespace", emptySet<String>(), aliases(sandbox))
            }
        }
    }

    private fun newSandbox() = AndroidStateTestSandbox().also { sandboxes += it }
    private fun bytes(value: String) = PrivateBytes(value.encodeToByteArray())
    private fun databaseFile() = File(sandbox.directory, "state.sqlite")
    private fun aliases(target: AndroidStateTestSandbox) = target.keyStore().aliases().toList()
        .filter { it.startsWith("${target.keyPrefix}.") }.toSet()

    private suspend fun openControl(): EncryptedSessionControlStore = openControlResult().valueOrFail()

    private suspend fun openControlResult(): PortResult<EncryptedSessionControlStore> = AndroidStateDatabase.openOwned(
        location = { sandbox.directory to sandbox.keyPrefix },
        requireNewVaultForNewFile = true,
        requireNewDirectoryForInitialization = true,
        wrap = { database, fileCreated -> EncryptedSessionControlStore.open(database, allowInitialize = fileCreated) },
    ).also { result ->
        // Also own an unexpected success, so assertion failure never leaks its connection or lock.
        if (result is PortResult.Value) openedControls += result.value
    }

    private suspend fun closeControl(control: EncryptedSessionControlStore) {
        control.close().valueOrFail()
        openedControls.remove(control)
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
                assertFalse("Control metadata must not appear in native database or journal files", data.indices.any { start ->
                    start + needle.size <= data.size && needle.indices.all { data[start + it] == needle[it] }
                })
            }
        }
    }
}
