package com.feedme.session

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import org.json.JSONObject
import org.junit.Assert.*

/**
 * A single run's private test-APK fixture, handed across deliberately killed processes.
 * The witness contains only orchestration metadata, never a plan, scope, token or key alias.
 * Existing fixed namespaces are NOT cleanup authority: only initial empty preflight + this
 * exact private ownership marker authorize the successful recovery's bounded fixture cleanup.
 */
internal class AndroidSessionSetupProcessSandbox private constructor(
    base: Context,
    val runId: String,
    val scenario: String,
    private val directory: File,
    val previousPid: Int,
) {
    val context: Context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
    }

    fun aliases(): Set<String> = keyStore().aliases().toList().filter(::inNamespace).toSet()

    fun writeCheckpoint(control: String, closed: Int) {
        assertEquals(expectedControl(scenario), control)
        assertEquals(expectedClosed(scenario), closed)
        validateTree()
        assertFalse("Never replace another interruption witness", File(directory, CHECKPOINT).exists())
        writeNew(directory, CHECKPOINT, checkpoint(runId, scenario, Process.myPid(), control, closed))
    }

    /** Read-only snapshots never open a second descriptor for a POSIX lifetime-lock inode. */
    fun snapshot(): Map<String, ByteArray> {
        validateTree()
        return Files.walk(directory.toPath()).use { stream ->
            stream.iterator().asSequence().filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.associate { path ->
                val name = path.fileName.toString()
                val bytes = if (name == "state.lock" || name == "credentials.lock") {
                    assertEquals(0L, Os.lstat(path.toString()).st_size)
                    ByteArray(0)
                } else Files.readAllBytes(path)
                directory.toPath().relativize(path).toString() to bytes
            }
        }
    }

    fun assertUnchanged(files: Map<String, ByteArray>, keys: Set<String>) {
        val after = snapshot()
        assertEquals(files.keys, after.keys)
        files.forEach { (name, bytes) -> assertArrayEquals("Unexpected native metadata mutation", bytes, after[name]) }
        assertEquals(keys, aliases())
    }

    /** Only after every exact owner and composition reservation acknowledges close. */
    fun removeAfterSuccessfulRecovery() {
        validateOwnership(directory, runId, scenario, previousPid)
        validateTree()
        val owned = aliases()
        assertTrue("Unknown aliases are preserved, never cleanup candidates", owned.all(::validAlias))
        val expectedFixed = PREFIXES.map { "$it.index" }.toSet() + "com.feedme.session.credentials.v1.manifest"
        val controlOwners = owned.filter { it.startsWith("com.feedme.session.control.v1.owner.") }
        val workOwners = owned.filter { it.startsWith("com.feedme.session.work.v1.owner.") }
        assertEquals("Only the authenticated independent control owner can remain", 1, controlOwners.size)
        assertEquals("Only the authenticated independent work owner can remain", 1, workOwners.size)
        assertEquals("No unknown/newer owner key may be swept up by fixture cleanup",
            expectedFixed + controlOwners + workOwners, owned)
        val paths = Files.walk(directory.toPath()).use { it.iterator().asSequence().toList() }
        val store = keyStore()
        owned.forEach(store::deleteEntry)
        assertTrue(aliases().isEmpty())
        paths.sortedByDescending { it.nameCount }.forEach(Files::delete)
        synchronize(directory.parentFile!!)
        assertFalse(directory.exists())
    }

    private fun validateTree() {
        privateDirectory(directory)
        Files.walk(directory.toPath()).use { stream ->
            stream.iterator().forEachRemaining { path ->
                if (path == directory.toPath()) return@forEachRemaining
                val relative = directory.toPath().relativize(path)
                val parts = relative.map { it.toString() }
                when {
                    parts.size == 1 && parts[0] in DIRECTORIES -> privateDirectory(path.toFile())
                    parts.size == 1 && parts[0] in setOf(OWNERSHIP, CHECKPOINT) -> privateFile(path.toFile())
                    parts.size == 2 && parts[0] in DIRECTORIES -> {
                        val name = parts[1]
                        val allowed = if (parts[0] == "feedme-credentials") {
                            name in setOf("credentials.lock", "manifest.bin") || BLOB.matches(name)
                        } else name in setOf("state.lock", "state.sqlite")
                        assertTrue("Unknown private inventory is not test cleanup authority", allowed)
                        privateFile(path.toFile())
                    }
                    else -> fail("Unknown fixture inventory must be preserved")
                }
            }
        }
        assertTrue("Unknown namespace alias must be preserved", aliases().all(::validAlias))
    }

    companion object {
        const val PREFIX = "retirement-integration-process-"
        const val MAX_HOLD_MILLIS = 60_000L
        private const val OWNERSHIP = "ownership.json"
        private const val CHECKPOINT = "checkpoint.json"
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val BLOB = Regex("[0-9a-f]{64}\\.[1-9][0-9]{0,18}\\.bin")
        private val DIRECTORIES = setOf("feedme-state", "feedme-session-control", "feedme-session-work", "feedme-credentials")
        private val PREFIXES = listOf("com.feedme.storage.private.v1", "com.feedme.session.control.v1",
            "com.feedme.session.work.v1", "com.feedme.session.credentials.v1")
        private val SCENARIOS = setOf("opening", "ready", "work-aborted", "data-aborted", "work-closed", "data-closed", "complete")

        fun create(scenario: String): AndroidSessionSetupProcessSandbox {
            val (base, runId) = arguments(scenario, "interrupt")
            assertTrue("Existing fixed namespaces cannot be reset", keyStore().aliases().toList().none(::inNamespace))
            val parent = base.noBackupFilesDir.canonicalFile
            assertFalse(Files.isSymbolicLink(base.noBackupFilesDir.toPath()))
            assertTrue("A prior interrupted fixture requires explicit recovery",
                checkNotNull(parent.listFiles()).none { it.name.startsWith(PREFIX) })
            val directory = File(parent, PREFIX + runId)
            Os.mkdir(directory.path, 448) // Only this successful mkdir grants initial ownership.
            privateDirectory(directory)
            writeNew(directory, OWNERSHIP, ownership(runId, scenario, Process.myPid()))
            return AndroidSessionSetupProcessSandbox(base, runId, scenario, directory, Process.myPid())
        }

        fun reopen(scenario: String): AndroidSessionSetupProcessSandbox {
            val (base, runId) = arguments(scenario, "recover")
            val parent = base.noBackupFilesDir.canonicalFile
            assertFalse(Files.isSymbolicLink(base.noBackupFilesDir.toPath()))
            val directory = File(parent, PREFIX + runId)
            privateDirectory(directory)
            val raw = readMarker(File(directory, CHECKPOINT))
            val json = JSONObject(raw)
            val pid = json.getInt("pid")
            assertTrue("The interrupted process must have a real, distinct PID", pid > 0 && pid != Process.myPid())
            assertEquals(checkpoint(runId, scenario, pid, expectedControl(scenario), expectedClosed(scenario)), raw)
            validateOwnership(directory, runId, scenario, pid)
            return AndroidSessionSetupProcessSandbox(base, runId, scenario, directory, pid).also { it.validateTree() }
        }

        private fun arguments(scenario: String, action: String): Pair<Context, String> {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            assertEquals("Never operate in the application package", "com.feedme.session.test", base.packageName)
            assertTrue(scenario in SCENARIOS)
            val args = InstrumentationRegistry.getArguments()
            assertEquals(scenario, args.getString("startupScenario"))
            assertEquals(action, args.getString("startupAction"))
            val runId = checkNotNull(args.getString("startupRunId"))
            assertTrue("A strict opaque run UUID is required", UUID.matches(runId))
            return base to runId
        }

        fun expectedControl(scenario: String) = when (scenario) {
            "opening", "ready" -> "unconfirmed"
            "complete" -> "complete"
            else -> "requested"
        }
        fun expectedClosed(scenario: String) = when (scenario) {
            "work-closed" -> 1
            "data-closed" -> 2
            "complete" -> 3
            else -> 0
        }
        private fun ownership(run: String, scenario: String, pid: Int) =
            "{\"version\":1,\"runId\":\"$run\",\"scenario\":\"$scenario\",\"creatorPid\":$pid}"
        private fun checkpoint(run: String, scenario: String, pid: Int, control: String, closed: Int) =
            "{\"version\":1,\"runId\":\"$run\",\"scenario\":\"$scenario\",\"pid\":$pid,\"control\":\"$control\",\"closed\":$closed}"

        private fun validateOwnership(directory: File, run: String, scenario: String, pid: Int) {
            assertEquals(ownership(run, scenario, pid), readMarker(File(directory, OWNERSHIP)))
        }
        private fun readMarker(file: File): String {
            privateFile(file)
            assertTrue("Bound the non-secret witness before reading it", Os.lstat(file.path).st_size in 1..1024)
            return Files.readAllBytes(file.toPath()).decodeToString(throwOnInvalidSequence = true)
        }
        private fun writeNew(directory: File, name: String, value: String) {
            val target = File(directory, name)
            val temporary = File(directory, "$name.pending")
            assertFalse(target.exists()); assertFalse(temporary.exists())
            val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
            assertTrue(bytes.size in 1..1024)
            try {
                FileChannel.open(temporary.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS).use { channel ->
                    Os.chmod(temporary.path, 384)
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) check(channel.write(buffer) > 0)
                    channel.force(true)
                }
                privateFile(temporary)
                Os.rename(temporary.path, target.path)
                synchronize(directory)
                synchronize(directory.parentFile!!)
            } finally { bytes.fill(0) }
        }
        private fun synchronize(directory: File) {
            val descriptor = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
            try {
                assertTrue(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode))
                Os.fsync(descriptor)
            } finally { Os.close(descriptor) }
        }
        private fun privateDirectory(file: File) {
            val stat = Os.lstat(file.path)
            assertTrue("Private fixture directory required", OsConstants.S_ISDIR(stat.st_mode))
            assertEquals(Process.myUid(), stat.st_uid); assertEquals(448, stat.st_mode and 511)
            assertEquals(file, file.canonicalFile)
        }
        private fun privateFile(file: File) {
            val stat = Os.lstat(file.path)
            assertTrue("Private regular fixture file required", OsConstants.S_ISREG(stat.st_mode))
            assertEquals(Process.myUid(), stat.st_uid); assertEquals(384, stat.st_mode and 511)
            assertEquals(1L, stat.st_nlink)
            assertEquals(file, file.canonicalFile)
        }
        private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        private fun inNamespace(alias: String) = PREFIXES.any { alias == it || alias.startsWith("$it.") }
        private fun validAlias(alias: String) = PREFIXES.any { prefix ->
            alias == "$prefix.index" || if (prefix == "com.feedme.session.credentials.v1") {
                alias == "$prefix.manifest" || alias.matches(Regex("${Regex.escape(prefix)}\\.credential\\.[0-9a-f]{64}"))
            } else alias.matches(Regex("${Regex.escape(prefix)}\\.owner\\.[0-9a-f]{32}"))
        }
    }
}
