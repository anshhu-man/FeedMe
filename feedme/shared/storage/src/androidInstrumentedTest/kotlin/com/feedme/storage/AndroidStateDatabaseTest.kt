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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidStateDatabaseTest {
    private lateinit var sandbox: AndroidStateTestSandbox
    private val openedManagers = mutableListOf<EncryptedStateDatabase>()

    @Before
    fun setUp() {
        sandbox = AndroidStateTestSandbox()
    }

    @After
    fun tearDown() = runBlocking {
        try {
            openedManagers.asReversed().forEach { it.close().valueOrFail() }
        } finally {
            openedManagers.clear()
            sandbox.close()
        }
    }

    @Test
    fun secondManagerFailsWhileLockedAndReopensAfterClose() = runBlocking {
        val first = openManager()
        val competing = AndroidStateDatabase.openForTests(sandbox.directory, sandbox.keyPrefix)
        // Register an unexpected successful open so a failing test still releases its resources.
        if (competing is PortResult.Value) openedManagers += competing.value
        assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE), competing)

        closeManager(first)
        val reopened = openManager()
        assertNotNull(reopened)
    }

    @Test
    fun committedRecordsSurviveReopenAndErasedScopeStaysErased() = runBlocking {
        val scope = StorageScope("instrumented-test", ActorKind.ACCOUNT, "private-test-account")
        val key = RecordKey("recipes", "private-test-recipe")
        val plaintext = "private cooking record that survives native reopen".encodeToByteArray()
        val first = openManager()
        val initialStore = first.activate(scope).valueOrFail()
        val ownerKeyAlias = sandbox.keyStore().aliases().toList().single {
            it.startsWith("${sandbox.keyPrefix}.owner.")
        }
        val revisions = initialStore.commit(
            scope,
            listOf(StoreMutation.Put(key, null, 3, PrivateBytes(plaintext))),
        ).valueOrFail()
        assertEquals(1L, revisions[key])
        closeManager(first)

        val second = openManager()
        val reopenedStore = second.resume(scope).valueOrFail()
        assertNotNull("Expected existing owner after reopen", reopenedStore)
        val record = reopenedStore!!.read(scope, key).valueOrFail()
        assertNotNull(record)
        assertEquals(1L, record!!.revision)
        assertEquals(3, record.schemaVersion)
        assertArrayEquals(plaintext, record.payload.copyForCodec())
        reopenedStore.eraseScope(scope).valueOrFail()
        assertFalse("Scope erasure must remove the native owner key", sandbox.keyStore().containsAlias(ownerKeyAlias))
        closeManager(second)

        val third = openManager()
        assertNull("Erasure must persist across native reopen", third.resume(scope).valueOrFail())
        val freshStore = third.activate(scope).valueOrFail()
        assertNull(freshStore.read(scope, key).valueOrFail())
    }

    @Test
    fun existingDatabaseWithMissingIndexKeyFailsClosed() = runBlocking {
        val first = openManager()
        val scope = StorageScope("instrumented-test", ActorKind.GUEST, "private-test-guest")
        first.activate(scope).valueOrFail()
        closeManager(first)
        sandbox.keyStore().deleteEntry("${sandbox.keyPrefix}.index")

        val reopened = AndroidStateDatabase.openForTests(sandbox.directory, sandbox.keyPrefix)
        if (reopened is PortResult.Value) openedManagers += reopened.value
        assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE), reopened)
        assertFalse(sandbox.keyStore().containsAlias("${sandbox.keyPrefix}.index"))
    }

    @Test
    fun symlinkDirectoryIsRejectedWithoutChangingItsTarget() = runBlocking {
        val target = AndroidStateTestSandbox()
        try {
            Os.mkdir(target.directory.path, 448) // 0700
            val marker = File(target.directory, "test-owned-marker")
            val expected = "untouched directory target".encodeToByteArray()
            writePrivateFile(marker, expected)
            Os.symlink(target.directory.path, sandbox.directory.path)

            assertOpenFailure()

            assertTrue(Files.isSymbolicLink(sandbox.directory.toPath()))
            assertArrayEquals(expected, marker.readBytes())
            assertEquals(listOf(marker.name), target.directory.listFiles()!!.map { it.name }.sorted())
        } finally {
            // Delete this exact link itself before cleaning its independently owned target.
            try {
                Files.deleteIfExists(sandbox.directory.toPath())
            } finally {
                target.close()
            }
        }
    }

    @Test
    fun symlinkDatabaseIsRejectedWithoutChangingItsTargetAndReleasesLock() = runBlocking {
        Os.mkdir(sandbox.directory.path, 448) // 0700
        val target = File(sandbox.directory, "test-owned-database-target")
        val expected = "untouched database target".encodeToByteArray()
        writePrivateFile(target, expected)
        val database = File(sandbox.directory, "state.sqlite")
        Os.symlink(target.path, database.path)

        assertOpenFailure()

        assertTrue(Files.isSymbolicLink(database.toPath()))
        assertArrayEquals(expected, target.readBytes())
        Files.delete(database.toPath())
        // The rejected open acquired the sibling lock before discovering this link.
        openManager()
        assertArrayEquals(expected, target.readBytes())
    }

    @Test
    fun preexistingWalIsPreservedAndFailureReleasesLock() = runBlocking {
        assertSidecarIsPreservedAndLockReleased("state.sqlite-wal")
    }

    @Test
    fun preexistingSharedMemoryIsPreservedAndFailureReleasesLock() = runBlocking {
        assertSidecarIsPreservedAndLockReleased("state.sqlite-shm")
    }

    @Test
    fun invalidExistingSchemaFailureReleasesManagerCallbackLock() = runBlocking {
        closeManager(openManager())
        // Keep a valid SQLite file and native index so rejection happens in the common opener,
        // after the factory transfers connection and lock ownership to its close callback.
        executeSqlOnClosedDatabase("CREATE TABLE instrumented_foreign_schema(marker TEXT NOT NULL)")

        assertOpenFailure()

        executeSqlOnClosedDatabase("DROP TABLE instrumented_foreign_schema")
        openManager()
        Unit
    }

    private suspend fun assertSidecarIsPreservedAndLockReleased(name: String) {
        closeManager(openManager())
        val sidecar = File(sandbox.directory, name)
        val expected = "test-owned foreign sidecar evidence: $name".encodeToByteArray()
        writePrivateFile(sidecar, expected)

        assertOpenFailure()

        assertTrue("Factory must preserve foreign sidecar evidence", sidecar.isFile)
        assertArrayEquals(expected, sidecar.readBytes())
        // Remove only the exact synthetic sidecar created by this test before retrying.
        assertTrue(sidecar.delete())
        openManager()
    }

    private fun writePrivateFile(file: File, bytes: ByteArray) {
        file.writeBytes(bytes)
        Os.chmod(file.path, 384) // 0600
    }

    private fun executeSqlOnClosedDatabase(sql: String) {
        val connection = BundledSQLiteDriver().open(File(sandbox.directory, "state.sqlite").path)
        try {
            val statement = connection.prepare(sql)
            try {
                statement.step()
            } finally {
                statement.close()
            }
        } finally {
            connection.close()
        }
    }

    private suspend fun assertOpenFailure() {
        val result = AndroidStateDatabase.openForTests(sandbox.directory, sandbox.keyPrefix)
        if (result is PortResult.Value) result.value.close().valueOrFail()
        assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE), result)
    }

    private suspend fun openManager(): EncryptedStateDatabase =
        AndroidStateDatabase.openForTests(sandbox.directory, sandbox.keyPrefix)
            .valueOrFail().also { openedManagers += it }

    private suspend fun closeManager(manager: EncryptedStateDatabase) {
        manager.close().valueOrFail()
        openedManagers.remove(manager)
    }
}
