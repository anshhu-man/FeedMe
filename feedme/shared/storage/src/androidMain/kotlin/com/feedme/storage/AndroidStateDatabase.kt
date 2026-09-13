package com.feedme.storage

import android.content.Context
import android.os.Build
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_FULLMUTEX
import androidx.sqlite.driver.bundled.SQLITE_OPEN_NOFOLLOW
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.SQLiteConnection
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.StorageScope
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Explicit development storage factory. Calling it does not authenticate or activate an account. */
object AndroidStateDatabase {
    suspend fun open(context: Context): PortResult<EncryptedStateDatabase> = openDatabase {
        val base = context.applicationContext.noBackupFilesDir
        require(!java.nio.file.Files.isSymbolicLink(base.toPath()))
        // Resolve the OS-supplied app sandbox root once; all children have fixed internal names.
        File(base.canonicalFile, DIRECTORY_NAME) to KEY_PREFIX
    }

    /** Allows instrumentation to own a separate exact directory and Keystore namespace. */
    internal suspend fun openForTests(directory: File, keyPrefix: String): PortResult<EncryptedStateDatabase> =
        openDatabase { directory to keyPrefix }

    /**
     * Restricted API 27+ recovery: never initializes storage, returns a private-store handle or
     * runs key garbage collection. Existing rollback journals remain an explicit recovery gate.
     * API 26 is unsupported here because the public atomic close-on-exec open flag needs API 27.
     */
    suspend fun openActivationRecovery(
        context: Context,
        scope: StorageScope,
        plan: StateActivationPlan,
    ): PortResult<StateActivationRecoveryHandle> = openExistingRecovery(scope, plan) {
        val base = context.applicationContext.noBackupFilesDir
        require(!java.nio.file.Files.isSymbolicLink(base.toPath()))
        File(base.canonicalFile, DIRECTORY_NAME) to KEY_PREFIX
    }

    internal suspend fun openActivationRecoveryForTests(
        directory: File,
        keyPrefix: String,
        scope: StorageScope,
        plan: StateActivationPlan,
    ): PortResult<StateActivationRecoveryHandle> = openExistingRecovery(scope, plan) { directory to keyPrefix }

    private suspend fun openExistingRecovery(
        scope: StorageScope,
        plan: StateActivationPlan,
        location: () -> Pair<File, String>,
    ): PortResult<StateActivationRecoveryHandle> {
        // Check before invoking even the location callback: unsupported devices must do no I/O.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return PortResult.Failure(FailureReason.NOT_CONFIGURED)
        var ownership: DatabaseOwnership? = null
        var untransferredConnection: SQLiteConnection? = null
        var handle: StateActivationRecoveryHandle? = null
        var transferred = false
        suspend fun releaseUntransferred() = withContext(NonCancellable + Dispatchers.IO) {
            handle?.close()
            if (!transferred) {
                // Never release the sibling lock if SQLite has not acknowledged its close.
                untransferredConnection?.close()
                untransferredConnection = null
                ownership?.close()
            }
        }
        try {
            return withContext(Dispatchers.IO) {
                val (requested, keyPrefix) = location()
                val directory = privateDirectory(requested, create = false).first
                val initialDirectory = checkNotNull(statOrNull(directory))
                require(initialDirectory.st_mode and PERMISSION_MASK == OWNER_DIRECTORY_MODE)
                requireRecoveryInventory(directory)
                val database = privateChild(directory, DATABASE_NAME)
                val initialDatabase = requireExistingPrivateFile(database)
                val lockFile = privateChild(directory, LOCK_NAME)
                require(requireExistingPrivateFile(lockFile).st_size == 0L)
                ownership = DatabaseOwnership.acquireExisting(lockFile)
                val vault = AndroidStateVault.openExisting(keyPrefix)
                // Authentication precedes opening SQLite, so an invalid capability cannot cause
                // SQLite recovery, initialization, migration or any other metadata mutation.
                validateStateActivationPlan(scope, plan, vault)
                requireRecoveryInventory(directory)
                require(sameIdentity(initialDirectory, checkNotNull(statOrNull(directory))))
                require(sameIdentity(initialDatabase, requireExistingPrivateFile(database)))
                val connection = BundledSQLiteDriver().open(
                    database.path,
                    SQLITE_OPEN_READWRITE or SQLITE_OPEN_FULLMUTEX or SQLITE_OPEN_NOFOLLOW,
                ).also { untransferredConnection = it }
                // SQLite owns its database descriptor. Do not open/close a second descriptor for
                // that inode while it is live; compare the stable path identity around its open.
                require(sameIdentity(initialDirectory, checkNotNull(statOrNull(directory))))
                require(sameIdentity(initialDatabase, requireExistingPrivateFile(database)))
                requireRecoveryInventory(directory)
                val lifetime = checkNotNull(ownership)
                transferred = true
                untransferredConnection = null
                EncryptedStateDatabase.openActivationRecovery(connection, vault, scope, plan,
                    Dispatchers.IO, lifetime::close).also { result ->
                    if (result is PortResult.Value) handle = result.value
                }
            }
        } catch (cancelled: CancellationException) {
            try { releaseUntransferred() } catch (_: Exception) { /* Retain ownership on failed close. */ }
            throw cancelled
        } catch (_: StateActivationPlanFormatException) {
            try { releaseUntransferred() } catch (_: Exception) { return PortResult.Failure(FailureReason.STORAGE_FAILURE) }
            return PortResult.Failure(FailureReason.INVALID_DATA)
        } catch (_: Exception) {
            try { releaseUntransferred() } catch (_: Exception) { /* Fail closed with ownership retained. */ }
            return PortResult.Failure(FailureReason.STORAGE_FAILURE)
        } catch (_: LinkageError) {
            try { releaseUntransferred() } catch (_: Exception) { /* Fail closed with ownership retained. */ }
            return PortResult.Failure(FailureReason.STORAGE_FAILURE)
        }
    }

    private suspend fun openDatabase(location: () -> Pair<File, String>): PortResult<EncryptedStateDatabase> =
        openOwned(location) { database, _ -> PortResult.Value(database) }

    /** One shared native ownership path; wrappers cannot bypass private modes, locking or NOFOLLOW. */
    internal suspend fun <T> openOwned(
        location: () -> Pair<File, String>,
        requireNewVaultForNewFile: Boolean = false,
        requireNewDirectoryForInitialization: Boolean = false,
        wrap: suspend (database: EncryptedStateDatabase, fileCreated: Boolean) -> PortResult<T>,
    ): PortResult<T> {
        var ownership: DatabaseOwnership? = null
        var manager: EncryptedStateDatabase? = null
        var transferred = false
        try {
            return withContext(Dispatchers.IO) {
                val (requestedDirectory, keyPrefix) = location()
                val (directory, directoryCreated) = privateDirectory(requestedDirectory)
                val lockFile = privateChild(directory, LOCK_NAME)
                ownership = DatabaseOwnership.acquire(lockFile)
                val database = privateChild(directory, DATABASE_NAME)
                val journal = privateChild(directory, "$DATABASE_NAME-journal")
                // This format uses rollback journaling. Never remove or checkpoint foreign WAL state.
                require(statOrNull(File(directory, "$DATABASE_NAME-wal")) == null)
                require(statOrNull(File(directory, "$DATABASE_NAME-shm")) == null)
                val databaseExisted = statOrNull(database) != null
                require(databaseExisted || statOrNull(journal) == null)
                if (requireNewDirectoryForInitialization && !databaseExisted) {
                    // Only this exact opener's successful mkdir is initialization authority. An
                    // existing empty/lock-only directory may be lost cancellation state, even if
                    // all of its keys disappeared. EEXIST never grants permission to reset it.
                    require(directoryCreated)
                }
                if (requireNewVaultForNewFile && !databaseExisted) {
                    // A vanished control file must not turn surviving retirement keys into a new
                    // idle ledger. Even a crash before initial file creation requires explicit repair.
                    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    require(keyStore.aliases().asSequence().none {
                        it == "$keyPrefix.index" || it.startsWith("$keyPrefix.owner.")
                    })
                }
                val vault = AndroidStateVault.createOrOpen(keyPrefix, databaseExisted)
                var fileCreated = false
                if (!databaseExisted) {
                    // Create an empty file with exact private permissions before SQLite initializes it.
                    // If interrupted here, the persistent index already exists for the next opener.
                    FileChannel.open(
                        database.toPath(),
                        setOf<OpenOption>(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                    ).use { }
                    fileCreated = true
                }
                val connection = BundledSQLiteDriver().open(
                    database.path,
                    SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE or SQLITE_OPEN_FULLMUTEX or SQLITE_OPEN_NOFOLLOW,
                )
                // The shared opener owns the connection and callback from entry, including failures.
                val lifetime = checkNotNull(ownership)
                transferred = true
                when (val result = EncryptedStateDatabase.open(connection, vault, Dispatchers.IO, lifetime::close)) {
                    is PortResult.Failure -> result
                    is PortResult.Value -> {
                        manager = result.value
                        wrap(result.value, fileCreated).also { wrapped ->
                            if (wrapped is PortResult.Failure) result.value.close()
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            // withContext can cancel while handing a just-opened manager back to its caller.
            withContext(NonCancellable + Dispatchers.IO) {
                manager?.close()
                if (!transferred) runCatching { ownership?.close() }
            }
            throw cancelled
        } catch (_: Exception) {
            withContext(NonCancellable + Dispatchers.IO) {
                manager?.close()
                if (!transferred) runCatching { ownership?.close() }
            }
            return PortResult.Failure(FailureReason.STORAGE_FAILURE)
        } catch (_: LinkageError) {
            withContext(NonCancellable + Dispatchers.IO) {
                manager?.close()
                if (!transferred) runCatching { ownership?.close() }
            }
            return PortResult.Failure(FailureReason.STORAGE_FAILURE)
        }
    }

    private fun privateDirectory(requested: File, create: Boolean = true): Pair<File, Boolean> {
        val absolute = requested.absoluteFile
        val parent = checkNotNull(absolute.parentFile).canonicalFile
        require(statOrNull(parent)?.let { OsConstants.S_ISDIR(it.st_mode) } == true)
        val directory = File(parent, absolute.name)
        require(absolute.canonicalFile == directory && absolute.name != "." && absolute.name != "..")
        var created = false
        if (statOrNull(directory) == null) {
            require(create)
            try {
                Os.mkdir(directory.path, OWNER_DIRECTORY_MODE)
                created = true
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EEXIST) throw error
            }
        }
        val attributes = checkNotNull(statOrNull(directory))
        require(OsConstants.S_ISDIR(attributes.st_mode) && attributes.st_uid == Process.myUid())
        require(attributes.st_mode and OTHER_PERMISSIONS == 0)
        require(directory.canonicalFile == directory)
        return directory to created
    }

    private fun requireRecoveryInventory(directory: File) {
        // No journal is silently removed or recovered here. Supporting a hot rollback journal
        // requires a separate faithful native recovery proof; zero-length sidecars also block.
        val names = checkNotNull(directory.list()).toSet()
        require(names == setOf(DATABASE_NAME, LOCK_NAME))
    }

    private fun requireExistingPrivateFile(file: File): StructStat = checkNotNull(statOrNull(file)).also {
        requirePrivateFile(it)
        require(file.canonicalFile == file)
    }

    private fun requirePrivateFile(stat: StructStat) {
        require(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Process.myUid())
        require(stat.st_nlink == 1L && stat.st_mode and PERMISSION_MASK == OWNER_FILE_MODE)
    }

    private fun sameIdentity(first: StructStat, second: StructStat): Boolean =
        first.st_dev == second.st_dev && first.st_ino == second.st_ino

    private fun privateChild(directory: File, name: String): File {
        val child = File(directory, name)
        require(child.canonicalFile == child)
        statOrNull(child)?.let { attributes ->
            require(OsConstants.S_ISREG(attributes.st_mode) && attributes.st_uid == Process.myUid())
            require(attributes.st_nlink == 1L && attributes.st_mode and OTHER_PERMISSIONS == 0)
        }
        return child
    }

    private fun statOrNull(file: File): StructStat? = try {
        Os.lstat(file.path)
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) null else throw error
    }

    private class DatabaseOwnership private constructor(
        private val channel: FileChannel,
        private val lock: FileLock,
        private val reservedPath: String,
        private val descriptor: FileDescriptor? = null,
    ) {
        private var closed = false

        @Synchronized
        fun close() {
            if (closed) return
            var failure: Throwable? = null
            fun attempt(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    val primary = failure
                    if (primary == null) failure = error else primary.addSuppressed(error)
                }
            }
            attempt { if (lock.isValid) lock.release() }
            attempt { channel.close() }
            // Android FileOutputStream(FileDescriptor) borrows its descriptor. Os.open ownership
            // remains here; a channel close alone does not release the recovery lock descriptor.
            attempt { descriptor?.let { if (it.valid()) Os.close(it) } }
            val released = descriptor?.let { !it.valid() } ?: !channel.isOpen
            if (released) releaseReservation(reservedPath)
            closed = released && !channel.isOpen
            failure?.let { throw it }
        }

        companion object {
            private val reservedPaths = mutableSetOf<String>()

            private fun releaseReservation(path: String) = synchronized(reservedPaths) {
                reservedPaths.remove(path)
            }

            fun acquire(file: File): DatabaseOwnership {
                val path = file.path
                // POSIX locks belong to a process: closing a second descriptor for this inode can
                // release its first descriptor's lock. Reject duplicates before opening another FD.
                synchronized(reservedPaths) { check(reservedPaths.add(path)) }
                var channel: FileChannel? = null
                try {
                    val opened = FileChannel.open(
                        file.toPath(),
                        setOf<OpenOption>(
                            StandardOpenOption.CREATE,
                            StandardOpenOption.READ,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS,
                        ),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                    ).also { channel = it }
                    val lock = opened.tryLock() ?: throw IllegalStateException()
                    return DatabaseOwnership(opened, lock, path)
                } catch (error: Throwable) {
                    try {
                        channel?.close()
                    } finally {
                        if (channel?.isOpen != true) releaseReservation(path)
                    }
                    throw error
                }
            }

            fun acquireExisting(file: File): DatabaseOwnership {
                val path = file.path
                synchronized(reservedPaths) { check(reservedPaths.add(path)) }
                var descriptor: FileDescriptor? = null
                var channel: FileChannel? = null
                try {
                    val before = requireExistingPrivateFile(file)
                    require(before.st_size == 0L)
                    val openedDescriptor = openExistingLockDescriptor(path).also { descriptor = it }
                    val openedStat = Os.fstat(openedDescriptor)
                    requirePrivateFile(openedStat)
                    require(openedStat.st_size == 0L && sameIdentity(before, openedStat))
                    require(sameIdentity(openedStat, requireExistingPrivateFile(file)))
                    val openedChannel = FileOutputStream(openedDescriptor).channel.also { channel = it }
                    val lock = checkNotNull(openedChannel.tryLock())
                    return DatabaseOwnership(openedChannel, lock, path, openedDescriptor)
                } catch (error: Throwable) {
                    try { channel?.close() } catch (closeError: Throwable) { error.addSuppressed(closeError) }
                    try { descriptor?.let { if (it.valid()) Os.close(it) } }
                    catch (closeError: Throwable) { error.addSuppressed(closeError) }
                    if (descriptor?.valid() != true) releaseReservation(path)
                    throw error
                }
            }

            private fun openExistingLockDescriptor(path: String): FileDescriptor {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    return Os.open(path, OsConstants.O_RDWR or OsConstants.O_NOFOLLOW or
                        OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
                }
                throw IllegalStateException("Private storage recovery unavailable")
            }
        }
    }

    private const val DIRECTORY_NAME = "feedme-state"
    private const val DATABASE_NAME = "state.sqlite"
    private const val LOCK_NAME = "state.lock"
    private const val KEY_PREFIX = "com.feedme.storage.private.v1"
    private const val OWNER_DIRECTORY_MODE = 448 // 0700
    private const val OWNER_FILE_MODE = 384 // 0600
    private const val PERMISSION_MASK = 511 // 0777
    private const val OTHER_PERMISSIONS = 63 // 0077
}
