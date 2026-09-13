package com.feedme.session

import android.content.Context
import android.content.ContextWrapper
import android.system.Os
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

internal enum class CredentialDiagnosticDamage { MISSING_KEY, MISSING_BLOB, TAMPERED_BLOB }

/**
 * Public factories have fixed Keystore prefixes, so this fixture is deliberately serialized in
 * the isolated session test APK. A redirected noBackupFilesDir alone does NOT isolate keys.
 * Unexpected existing aliases are a hard gate: never delete them to make a test start clean.
 */
internal class AndroidRetirementIntegrationSandbox private constructor(
    base: Context,
    private val directory: File,
) {
    val context: Context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
    }

    fun aliases(): Set<String> = keyStore().aliases().toList().filter(::inNamespace).toSet()
    fun dataOwnerAliases(): Set<String> = aliases().filter { it.startsWith("$DATA_PREFIX.owner.") }.toSet()
    fun credentialAliases(): Set<String> = aliases().filter { it.startsWith("$CREDENTIAL_PREFIX.credential.") }.toSet()

    /** Already-idle test-owned stores only; never open a second descriptor for a lifetime lock. */
    fun fileSnapshot(): Map<String, ByteArray> = Files.walk(directory.toPath()).use { stream ->
        stream.iterator().asSequence().filter { path ->
            assertFalse("Unexpected diagnostic fixture symlink", Files.isSymbolicLink(path))
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        }.associate { path ->
            val name = path.fileName.toString()
            val bytes = if (name == "state.lock" || name == "credentials.lock") {
                // Closing another fd for a POSIX lock inode can release this process's lock.
                // A zero stat size proves empty contents without touching that descriptor.
                assertEquals("Lifetime lock contents must stay empty", 0L, Files.size(path))
                ByteArray(0)
            } else Files.readAllBytes(path)
            directory.toPath().relativize(path).toString() to bytes
        }
    }

    fun assertFilesEqual(expected: Map<String, ByteArray>) {
        val actual = fileSnapshot()
        assertTrue("Diagnostic inspection changed the file inventory", expected.keys == actual.keys)
        expected.forEach { (path, bytes) ->
            assertTrue("Diagnostic inspection changed stored bytes", bytes.contentEquals(checkNotNull(actual[path])))
        }
    }

    /** Fault setup only, after the exact fixture's runtime and every native store have closed. */
    fun damageSelectedCredentials(damage: CredentialDiagnosticDamage) {
        val credentialDirectory = File(directory, "feedme-credentials")
        val blob = checkNotNull(credentialDirectory.listFiles()).single {
            it.name.matches(Regex("[0-9a-f]{64}\\.[1-9][0-9]{0,18}\\.bin"))
        }
        assertFalse("Fault injection must not follow a symlink", Files.isSymbolicLink(blob.toPath()))
        when (damage) {
            CredentialDiagnosticDamage.MISSING_KEY -> {
                val alias = credentialAliases().single()
                keyStore().deleteEntry(alias)
                assertFalse("Exact selected key should be absent", aliases().contains(alias))
            }
            CredentialDiagnosticDamage.MISSING_BLOB -> Files.delete(blob.toPath())
            CredentialDiagnosticDamage.TAMPERED_BLOB -> {
                val bytes = Files.readAllBytes(blob.toPath())
                try {
                    check(bytes.size >= 29)
                    bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                    FileChannel.open(blob.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                        val buffer = ByteBuffer.wrap(bytes)
                        while (buffer.hasRemaining()) check(channel.write(buffer) > 0)
                        channel.force(true)
                    }
                } finally { bytes.fill(0) }
            }
        }
    }

    /** Call only after every exact native cancellation and every store close was acknowledged. */
    fun removeOwnedFixture() {
        try {
            val owned = aliases()
            assertTrue("Unexpected alias shape; preserve the owned fixture for inspection", owned.all(::validAlias))
            val paths = Files.walk(directory.toPath()).use { it.iterator().asSequence().toList() }
            assertTrue("Unexpected symlink; preserve the owned fixture for inspection", paths.none(Files::isSymbolicLink))
            val store = keyStore()
            owned.forEach(store::deleteEntry)
            assertTrue("Owned integration aliases remain", aliases().isEmpty())
            // This exact UUID root was created by this fixture after empty-namespace preflight.
            // No production app data, WorkManager database, sibling fixture or lock in use is removed.
            paths.sortedByDescending { it.nameCount }.forEach(Files::delete)
            assertFalse("Owned integration root remains", directory.exists())
        } finally { reserved.set(false) }
    }

    /** Failure preserves files and keys; future preflight must not silently wipe this evidence. */
    fun releasePreservingFixture() { reserved.set(false) }

    companion object {
        private const val DATA_PREFIX = "com.feedme.storage.private.v1"
        private const val CONTROL_PREFIX = "com.feedme.session.control.v1"
        private const val WORK_PREFIX = "com.feedme.session.work.v1"
        private const val CREDENTIAL_PREFIX = "com.feedme.session.credentials.v1"
        private val prefixes = listOf(DATA_PREFIX, CONTROL_PREFIX, WORK_PREFIX, CREDENTIAL_PREFIX)
        private val reserved = AtomicBoolean(false)

        fun create(base: Context): AndroidRetirementIntegrationSandbox {
            assertEquals("Never use the FeedMe application UID", "com.feedme.session.test", base.packageName)
            assertTrue("Fixed-prefix integration fixtures cannot run in parallel", reserved.compareAndSet(false, true))
            try {
                assertTrue("Existing fixed namespaces are not test cleanup authority",
                    keyStore().aliases().toList().none(::inNamespace))
                val parent = base.noBackupFilesDir
                assertFalse("Test root parent must not be a symlink", Files.isSymbolicLink(parent.toPath()))
                val root = File(parent.canonicalFile, "retirement-integration-${UUID.randomUUID()}")
                // Only this successful mkdir creates ownership. Factory leaf directories stay absent.
                Os.mkdir(root.absolutePath, 0x1c0)
                return AndroidRetirementIntegrationSandbox(base, root)
            } catch (failure: Throwable) {
                reserved.set(false)
                throw failure
            }
        }

        private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        private fun inNamespace(alias: String) = prefixes.any { alias == it || alias.startsWith("$it.") }
        private fun validAlias(alias: String): Boolean = prefixes.any { prefix ->
            alias == "$prefix.index" || if (prefix == CREDENTIAL_PREFIX) {
                alias == "$prefix.manifest" || alias.matches(Regex("${Regex.escape(prefix)}\\.credential\\.[0-9a-f]{64}"))
            } else alias.matches(Regex("${Regex.escape(prefix)}\\.owner\\.[0-9a-f]{32}"))
        }
    }
}
