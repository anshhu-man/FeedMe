package com.feedme.app.mealflow

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fixed public factory namespaces are UID-wide, not isolated by ContextWrapper alone. Only the
 * app TEST package may own this sandbox, after an empty-namespace preflight and successful mkdir.
 * Unknown files/aliases and any failed/partial native lifecycle are preserved, never swept away.
 */
internal class NativeMealFlowTestSandbox private constructor(base: Context, private val directory: File) {
    val context: Context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
    }
    private var selected: Set<String>? = null

    fun selectedInventory(): Set<String> {
        validateTree()
        val aliases = aliases()
        require(aliases == FIXED + owner(aliases, DATA) + owner(aliases, CONTROL) +
            owner(aliases, WORK) + credentialOwner(aliases)) { "Unknown native fixture ownership" }
        selected = aliases.toSet()
        return aliases.toSet()
    }

    /** Reopening is permitted only after the caller closed every native owner successfully. */
    fun requireSelectedInventory(expected: Set<String>) {
        validateTree()
        require(expected == selected && aliases() == expected) { "Native fixture ownership changed" }
    }

    /** Called only after actual exact runtime retirement AND all native/root close receipts. */
    fun removeAfterRetirement() {
        validateTree()
        val original = checkNotNull(selected)
        val expected = original - owner(original, DATA) - credentialOwner(original)
        require(expected == FIXED + owner(original, CONTROL) + owner(original, WORK))
        require(aliases() == expected) { "Unknown or unretired native aliases must be preserved" }
        val credentialNames = checkNotNull(File(directory, "feedme-credentials").list()).toSet()
        require(credentialNames == setOf("credentials.lock", "manifest.bin")) {
            "Unretired credential artifacts must be preserved"
        }
        val paths = Files.walk(directory.toPath()).use { it.iterator().asSequence().toList() }
        val store = keyStore()
        // The exact set was captured from this fixture's successful live composition. Do not
        // enumerate-and-delete foreign keys, even if their names match a familiar prefix.
        expected.forEach(store::deleteEntry)
        require(aliases().isEmpty()) { "Native fixture key deletion not acknowledged" }
        paths.sortedByDescending { it.nameCount }.forEach { path ->
            if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) privateDirectory(path.toFile())
            else privateFile(path.toFile())
            Files.delete(path)
        }
        synchronize(checkNotNull(directory.parentFile))
        require(!Files.exists(directory.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        reserved.set(false)
    }

    private fun validateTree() {
        privateDirectory(directory)
        require(checkNotNull(directory.list()).toSet() == DIRECTORIES) { "Unexpected native fixture directories" }
        Files.walk(directory.toPath()).use { stream ->
            stream.iterator().forEachRemaining { path ->
                if (path != directory.toPath()) {
                    val parts = directory.toPath().relativize(path).map { it.toString() }
                    when {
                        parts.size == 1 && parts[0] in DIRECTORIES -> privateDirectory(path.toFile())
                        parts.size == 2 && parts[0] in DIRECTORIES -> {
                            val name = parts[1]
                            val allowed = if (parts[0] == "feedme-credentials")
                                name in setOf("credentials.lock", "manifest.bin") || BLOB.matches(name)
                            else name in setOf("state.lock", "state.sqlite")
                            require(allowed) { "Unknown native fixture file must be preserved" }
                            privateFile(path.toFile())
                            if (name == "state.lock" || name == "credentials.lock") {
                                // Never open/read a second FD for a POSIX lifetime-lock inode.
                                require(Os.lstat(path.toString()).st_size == 0L)
                            }
                        }
                        else -> error("Unknown native fixture inventory must be preserved")
                    }
                }
            }
        }
        DIRECTORIES.forEach { name ->
            val names = checkNotNull(File(directory, name).list()).toSet()
            val required = if (name == "feedme-credentials") setOf("credentials.lock", "manifest.bin")
                else setOf("state.lock", "state.sqlite")
            require(names.containsAll(required)) { "Incomplete native fixture must be preserved" }
        }
    }

    companion object {
        const val DIRECTORY_PREFIX = "meal-host-native-"
        private const val DATA = "com.feedme.storage.private.v1"
        private const val CONTROL = "com.feedme.session.control.v1"
        private const val WORK = "com.feedme.session.work.v1"
        private const val CREDENTIAL = "com.feedme.session.credentials.v1"
        private val PREFIXES = setOf(DATA, CONTROL, WORK, CREDENTIAL)
        private val FIXED = PREFIXES.map { "$it.index" }.toSet() + "$CREDENTIAL.manifest"
        private val DIRECTORIES = setOf("feedme-state", "feedme-session-control", "feedme-session-work", "feedme-credentials")
        private val BLOB = Regex("[0-9a-f]{64}\\.[1-9][0-9]{0,18}\\.bin")
        private val reserved = AtomicBoolean(false)

        fun create(base: Context, stage: (NativeMealFixtureStage) -> Unit): NativeMealFlowTestSandbox {
            stage(NativeMealFixtureStage.SANDBOX_IDENTITY)
            require(base.packageName == "com.feedme.app.test") { "Only the isolated application test UID is authorized" }
            require(base.applicationInfo.uid == Process.myUid()) { "Test Context UID mismatch" }
            check(reserved.compareAndSet(false, true)) { "Native host fixtures must be serialized" }
            var created = false
            try {
                stage(NativeMealFixtureStage.SANDBOX_NAMESPACE)
                require(aliases().isEmpty()) { "Existing native namespaces are not cleanup authority" }
                stage(NativeMealFixtureStage.SANDBOX_PARENT)
                val requestedParent = base.noBackupFilesDir
                require(!Files.isSymbolicLink(requestedParent.toPath()))
                val parent = requestedParent.canonicalFile
                platformParent(parent)
                require(checkNotNull(parent.list()).none { it.startsWith(DIRECTORY_PREFIX) }) {
                    "A prior native host fixture must be inspected, never replaced"
                }
                val directory = File(parent, DIRECTORY_PREFIX + UUID.randomUUID().toString())
                stage(NativeMealFixtureStage.SANDBOX_MKDIR)
                Os.mkdir(directory.path, 448)
                created = true
                // Ownership exists only after successful mkdir. The native leaf directories
                // remain absent, as required by the real factories' first-install guards.
                return NativeMealFlowTestSandbox(base, directory)
            } finally {
                if (!created) reserved.set(false)
            }
        }

        private fun owner(aliases: Set<String>, prefix: String): Set<String> = aliases.filter {
            it.matches(Regex("${Regex.escape(prefix)}\\.owner\\.[0-9a-f]{32}"))
        }.toSet().also { require(it.size == 1) { "Exact native owner required" } }

        private fun credentialOwner(aliases: Set<String>): Set<String> = aliases.filter {
            it.matches(Regex("${Regex.escape(CREDENTIAL)}\\.credential\\.[0-9a-f]{64}"))
        }.toSet().also { require(it.size == 1) { "Exact credential incarnation required" } }

        private fun aliases(): Set<String> = keyStore().aliases().toList().filter { alias ->
            PREFIXES.any { alias == it || alias.startsWith("$it.") }
        }.toSet()
        private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        private fun privateDirectory(file: File) {
            val stat = Os.lstat(file.path)
            require(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Process.myUid() && stat.st_mode and 511 == 448)
            require(file == file.canonicalFile)
        }
        private fun platformParent(file: File) {
            val stat = Os.lstat(file.path)
            // Android owns no_backup and creates it as 0771 on the tested platform. Do not
            // chmod that shared application parent. Only our fresh UUID child is required to
            // be 0700; all of its native descendants remain strictly 0700/0600 below.
            require(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Process.myUid())
            require((stat.st_mode and 511) in setOf(448, 505))
            require(file == file.canonicalFile)
        }
        private fun privateFile(file: File) {
            val stat = Os.lstat(file.path)
            require(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Process.myUid() && stat.st_mode and 511 == 384)
            require(stat.st_nlink == 1L && file == file.canonicalFile)
        }
        private fun synchronize(directory: File) {
            val descriptor = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
            try {
                require(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode))
                Os.fsync(descriptor)
            } finally { Os.close(descriptor) }
        }
    }
}
