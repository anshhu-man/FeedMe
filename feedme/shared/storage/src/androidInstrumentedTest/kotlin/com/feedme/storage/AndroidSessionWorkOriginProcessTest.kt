package com.feedme.storage

import android.os.Process
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feedme.core.ports.*
import com.feedme.session.*
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Three explicit instrumentation invocations, each closing its lifetime owners. The opaque plan
 * is retained in a separate real encrypted control fixture; only non-secret PID witnesses are
 * plain files. This proves controlled process-separated plan/selection replay, not composite
 * production journaling, a killed transaction, a provider login or power-loss recovery.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSessionWorkOriginProcessTest {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)

    @Test fun preparePlanInFirstProcess() = runBlocking(dispatcher) {
        requireStage("work-origin-plan")
        assertFalse("Never overwrite a retained or foreign process fixture", root.exists())
        assertTrue("Never erase or adopt an unknown alias namespace", aliases().isEmpty())
        Os.mkdir(root.path, 0x1c0)
        val resources = open(initialize = true, mayAllocate = true)
        try {
            val initial = resources.work.read().valueOrFail()!!
            val beforeAliases = aliases()
            val plan = resources.registry.planOrigin(SCOPE, initial.revision).valueOrFail()
            assertEquals(initial.revision, resources.work.read().valueOrFail()!!.revision)
            assertArrayEquals(initial.payload.copyForCodec(), resources.work.read().valueOrFail()!!.payload.copyForCodec())
            assertEquals(beforeAliases, aliases())
            assertEquals(SessionWorkOriginPlanStatus.PREPARED, resources.registry.inspectOrigin(plan).valueOrFail())
            val control = resources.control.read().valueOrFail()!!
            resources.control.compareAndSet(control.revision, plan.copyForStorage()).valueOrFail()
            assertEquals(1, resources.allocatedIds)
            resources.assertInactive()
            writePid("prepared.pid")
        } finally { resources.close() }
    }

    @Test fun selectExactPlanInSecondProcess() = runBlocking(dispatcher) {
        requireStage("work-origin-select")
        val firstPid = readPid("prepared.pid")
        assertNotEquals("Selection must run in a different Android process", firstPid, Process.myPid())
        assertFalse("Never silently replay a completed selection stage", File(root, "selected.pid").exists())
        val resources = open(initialize = false, mayAllocate = false)
        try {
            val planBytes = resources.control.read().valueOrFail()!!.payload
            val plan = SessionWorkOriginPlan.fromStorage(planBytes).valueOrFail()
            val beforeAliases = aliases()
            assertEquals(SessionWorkOriginPlanStatus.PREPARED, resources.registry.inspectOrigin(plan).valueOrFail())
            assertEquals(1L, resources.work.read().valueOrFail()!!.revision)
            resources.registry.selectOrigin(plan).valueOrFail()
            assertEquals(2L, resources.work.read().valueOrFail()!!.revision)
            assertEquals(SessionWorkOriginPlanStatus.SELECTED, resources.registry.inspectOrigin(plan).valueOrFail())
            assertArrayEquals(planBytes.copyForCodec(), resources.control.read().valueOrFail()!!.payload.copyForCodec())
            assertEquals(beforeAliases, aliases())
            assertEquals(0, resources.allocatedIds)
            resources.assertInactive()
            writePid("selected.pid")
        } finally { resources.close() }
    }

    @Test fun reacknowledgeSelectedPlanAndCleanInThirdProcess() = runBlocking(dispatcher) {
        requireStage("work-origin-replay")
        val firstPid = readPid("prepared.pid")
        val secondPid = readPid("selected.pid")
        assertNotEquals(firstPid, secondPid)
        assertNotEquals(firstPid, Process.myPid())
        assertNotEquals("Replay must run in a third Android process", secondPid, Process.myPid())
        val resources = open(initialize = false, mayAllocate = false)
        try {
            val planBytes = resources.control.read().valueOrFail()!!.payload
            val plan = SessionWorkOriginPlan.fromStorage(planBytes).valueOrFail()
            val selected = resources.work.read().valueOrFail()!!
            val beforeAliases = aliases()
            assertEquals(2L, selected.revision)
            assertEquals(SessionWorkOriginPlanStatus.SELECTED, resources.registry.inspectOrigin(plan).valueOrFail())
            resources.registry.selectOrigin(plan).valueOrFail()
            val acknowledged = resources.work.read().valueOrFail()!!
            assertEquals(3L, acknowledged.revision)
            assertArrayEquals(selected.payload.copyForCodec(), acknowledged.payload.copyForCodec())
            assertArrayEquals(planBytes.copyForCodec(), resources.control.read().valueOrFail()!!.payload.copyForCodec())
            assertEquals(beforeAliases, aliases())
            assertEquals(0, resources.allocatedIds)
            assertEquals(PortResult.Failure(FailureReason.CONFLICT), resources.registry.snapshot())
            resources.assertInactive()
        } finally { resources.close() }
        // Only after successful verification AND acknowledged owner closes may this test clean up.
        destroyFixture()
    }

    private suspend fun open(initialize: Boolean, mayAllocate: Boolean): Resources {
        assertEquals("com.feedme.storage.test", InstrumentationRegistry.getInstrumentation().targetContext.packageName)
        assertTrue(root.isDirectory)
        assertFalse(Files.isSymbolicLink(root.toPath()))
        assertEquals(0x1c0, Os.lstat(root.path).st_mode and 0x1ff)
        val work = AndroidStateDatabase.openOwned(
            location = { File(root, "work") to "$KEY_PREFIX.work" },
            requireNewVaultForNewFile = true,
            requireNewDirectoryForInitialization = true,
            wrap = { database, created ->
                assertEquals(initialize, created)
                EncryptedSessionWorkStore.open(database, created)
            },
        ).valueOrFail()
        var control: EncryptedSessionControlStore? = null
        try {
            val independent = AndroidStateDatabase.openOwned(
                location = { File(root, "control") to "$KEY_PREFIX.control" },
                requireNewVaultForNewFile = true,
                requireNewDirectoryForInitialization = true,
                wrap = { database, created ->
                    assertEquals(initialize, created)
                    EncryptedSessionControlStore.open(database, created)
                },
            ).valueOrFail().also { control = it }
            return Resources(work, independent, mayAllocate).also { it.openRegistry() }
        } catch (failure: Throwable) {
            work.close().valueOrFail()
            control?.close()?.valueOrFail()
            throw failure
        }
    }

    private inner class Resources(
        val work: EncryptedSessionWorkStore,
        val control: EncryptedSessionControlStore,
        private val mayAllocate: Boolean,
    ) {
        val boundary = SessionBoundary()
        lateinit var registry: SessionWorkRegistry
        var allocatedIds = 0
        private var effects = 0

        suspend fun openRegistry() {
            registry = SessionWorkRegistry.open(work, boundary, dispatcher,
                NativeWorkCancellationPort { forbidden() },
                NativeWorkIdSource {
                    assertTrue("An existing plan must never allocate a replacement origin", mayAllocate)
                    allocatedIds++
                    ORIGIN
                },
                NativeWorkAdmissionPolicy { forbidden() },
                NativeWorkExecutionPolicy { _, _, _, _ -> forbidden() },
            ).valueOrFail()
        }

        private fun forbidden(): Nothing {
            effects++
            throw AssertionError("Process-separated setup selection must not invoke identity/native policies")
        }

        fun assertInactive() { assertNull(boundary.current()); assertEquals(0, effects) }
        suspend fun close() { registry.close().valueOrFail(); work.close().valueOrFail(); control.close().valueOrFail() }
    }

    private fun requireStage(expected: String) {
        assertEquals("This exact method requires its explicit staged invocation", expected,
            InstrumentationRegistry.getArguments().getString("processRestartStage"))
    }

    private fun writePid(name: String) {
        val file = File(root, name)
        assertFalse(file.exists())
        file.writeText(Process.myPid().toString())
        Os.chmod(file.path, 0x180)
    }

    private fun readPid(name: String): Int {
        val file = File(root, name)
        assertTrue("The preceding process stage must complete first", file.isFile)
        assertFalse(Files.isSymbolicLink(file.toPath()))
        assertEquals(0x180, Os.lstat(file.path).st_mode and 0x1ff)
        val value = file.readText().toInt()
        assertTrue(value > 0)
        return value
    }

    private fun aliases(): Set<String> = keyStore().aliases().toList().filter { it.startsWith("$KEY_PREFIX.") }.toSet()
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun destroyFixture() {
        val owned = aliases()
        val exact = Regex("${Regex.escape(KEY_PREFIX)}\\.(work|control)\\.(index|owner\\.[0-9a-f]{32})")
        assertTrue(owned.isNotEmpty())
        assertTrue("Unexpected alias is diagnostic evidence, not cleanup authority", owned.all(exact::matches))
        val keys = keyStore()
        owned.forEach(keys::deleteEntry)
        assertTrue(aliases().isEmpty())
        assertFalse(Files.isSymbolicLink(root.toPath()))
        assertTrue(root.deleteRecursively())
        assertFalse(root.exists())
    }

    private val root: File get() = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
        "feedme-work-origin-instrumented-process-v1")

    private companion object {
        const val KEY_PREFIX = "com.feedme.storage.instrumented.work_origin_process.v1"
        const val ORIGIN = "00000000-0000-4000-8000-000000000281"
        val SCOPE = StorageScope("work-origin-process-fixture", ActorKind.ACCOUNT, "test-only-plan-owner")
    }
}
