package com.feedme.storage

import android.os.Process
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feedme.core.ports.ActorKind
import com.feedme.core.ports.PrivateBytes
import com.feedme.core.ports.RecordKey
import com.feedme.core.ports.StorageScope
import com.feedme.core.ports.StoreMutation
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two opt-in stages, each run in a separate `am instrument` invocation. The writer deliberately
 * leaves only this fixed test fixture behind. The reader requires a different process and cleans
 * up the fixture. Ordinary suites skip both stages unless processRestartStage is explicitly set.
 */
@RunWith(AndroidJUnit4::class)
class AndroidStateProcessRestartTest {
    @Test
    fun writeFixture() = runBlocking {
        requireStage("write")
        cleanupFixture()
        val manager = AndroidStateDatabase.openForTests(directory, KEY_PREFIX).valueOrFail()
        try {
            val store = manager.activate(scope).valueOrFail()
            val revisions = store.commit(
                scope,
                listOf(StoreMutation.Put(recordKey, null, SCHEMA_VERSION, PrivateBytes(payload))),
            ).valueOrFail()
            assertEquals(1L, revisions[recordKey])
            writerPidFile.writeText(Process.myPid().toString())
            Os.chmod(writerPidFile.path, 384) // 0600
        } finally {
            manager.close().valueOrFail()
        }
    }

    @Test
    fun readFixtureAndCleanup() = runBlocking {
        requireStage("read")
        var manager: EncryptedStateDatabase? = null
        try {
            assertTrue("Run writeFixture in an earlier process first", File(directory, "state.sqlite").isFile)
            assertTrue("Writer process marker is missing", writerPidFile.isFile)
            assertNotEquals(
                "Run fixture stages in separate instrumentation processes",
                writerPidFile.readText(),
                Process.myPid().toString(),
            )
            val reopened = AndroidStateDatabase.openForTests(directory, KEY_PREFIX).valueOrFail()
            manager = reopened
            val store = reopened.resume(scope).valueOrFail()
            assertNotNull("Existing owner must survive process restart", store)
            val record = store!!.read(scope, recordKey).valueOrFail()
            assertNotNull("Committed record must survive process restart", record)
            assertEquals(1L, record!!.revision)
            assertEquals(SCHEMA_VERSION, record.schemaVersion)
            assertArrayEquals(payload, record.payload.copyForCodec())
        } finally {
            try {
                manager?.close()?.valueOrFail()
            } finally {
                cleanupFixture()
            }
        }
    }

    private fun requireStage(stage: String) {
        assumeTrue(
            "Staged restart test requires an explicit processRestartStage argument",
            InstrumentationRegistry.getArguments().getString("processRestartStage") == stage,
        )
    }

    private fun cleanupFixture() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.aliases().toList().filter { it.startsWith("$KEY_PREFIX.") }.forEach(keyStore::deleteEntry)
        val fixture = directory
        if (Files.isSymbolicLink(fixture.toPath())) {
            Files.delete(fixture.toPath())
        } else {
            assertTrue("Could not remove dedicated restart test fixture", !fixture.exists() || fixture.deleteRecursively())
        }
    }

    private val directory: File
        get() = File(
            InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
            "feedme-state-instrumented-process-restart-v1",
        )

    private val writerPidFile: File get() = File(directory, "instrumented-writer.pid")
    private val scope = StorageScope("instrumented-process-restart", ActorKind.ACCOUNT, "test-only-restart-owner")
    private val recordKey = RecordKey("test-only-restart-records", "durable-record")
    private val payload = "test-only private record across actual Android process restart".encodeToByteArray()

    private companion object {
        const val KEY_PREFIX = "com.feedme.storage.instrumented.process_restart.v1"
        const val SCHEMA_VERSION = 7
    }
}
