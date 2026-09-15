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
import com.feedme.core.ports.PrivateBytes
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Test-only pre-stage failures; not native errno, SQLite VFS faults or power-loss simulation. */
internal fun interface StateActivationRecoveryFaults { fun at(stage: String) }

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

    /**
     * Allocate and retain before calling open. Construction does no location/Keystore/file work.
     * Even failed or cancelled opens remain owned by this object until close acknowledges release.
     * API 27+, existing exact files/schema only; rollback journals remain a separate recovery gate.
     */
    fun createActivationRecoveryOwner(
        context: Context,
        scope: StorageScope,
        plan: StateActivationPlan,
    ): StateActivationRecoveryOwner = NativeActivationRecoveryOwner(scope, plan, StateActivationRecoveryFaults {}) {
        val base = context.applicationContext.noBackupFilesDir
        require(!java.nio.file.Files.isSymbolicLink(base.toPath()))
        File(base.canonicalFile, DIRECTORY_NAME) to KEY_PREFIX
    }

    internal fun createActivationRecoveryOwnerForTests(
        directory: File,
        keyPrefix: String,
        scope: StorageScope,
        plan: StateActivationPlan,
        faults: StateActivationRecoveryFaults = StateActivationRecoveryFaults {},
    ): StateActivationRecoveryOwner = NativeActivationRecoveryOwner(scope, plan, faults) { directory to keyPrefix }

    private class NativeActivationRecoveryOwner(
        scope: StorageScope,
        plan: StateActivationPlan,
        faults: StateActivationRecoveryFaults,
        location: () -> Pair<File, String>,
    ) : StateActivationRecoveryOwner {
        private val plan = StateActivationPlan(plan.copyForStorage())
        private val lifetime = NativeExistingRecovery(
            faults = faults,
            location = location,
            beforeSqliteOpen = { vault -> validateStateActivationPlan(scope, this.plan, vault) },
            createDelegate = { connection, vault, onClosed ->
                EncryptedStateDatabase.createActivationRecoveryOwner(connection, vault, scope, this.plan, Dispatchers.IO, onClosed)
            },
            openDelegate = { it.open() }, closeDelegate = { it.close() },
        )
        override suspend fun open() = lifetime.open()
        override suspend fun inspect() = lifetime.whenReady { it.inspect() }
        override suspend fun binding() = lifetime.whenReady { it.binding() }
        override suspend fun abort(expectedBinding: PrivateBytes?) = lifetime.whenReady { it.abort(expectedBinding) }
        override suspend fun close() = lifetime.close()
        override fun toString() = "StateActivationRecoveryOwner(<redacted>)"
    }

    /**
     * Shared internal acquisition only; neither vault nor connection escapes through a public
     * owner. Construct and retain before calling open. Delegates take ownership synchronously
     * and are retained before their first await. No normal open/resume/GC route is used here.
     */
    internal class NativeExistingRecovery<T : Any>(
        private val faults: StateActivationRecoveryFaults,
        private val location: () -> Pair<File, String>,
        private val beforeSqliteOpen: (StateVault) -> Unit,
        private val createDelegate: (SQLiteConnection, StateVault, () -> Unit) -> T,
        private val openDelegate: suspend (T) -> PortResult<Unit>,
        private val closeDelegate: suspend (T) -> PortResult<Unit>,
    ) {
        private val mutex = Mutex()
        private var attempted = false
        private var ready = false
        private var closeRequested = false
        private var closed = false
        private var ownership: DatabaseOwnership? = null
        private var connection: SQLiteConnection? = null
        private var recovery: T? = null

        suspend fun open(): PortResult<Unit> {
            var admitted = false
            try {
                val result = withContext(Dispatchers.IO) {
                    mutex.withLock {
                        if (attempted) return@withLock PortResult.Failure(FailureReason.CONFLICT)
                        attempted = true
                        admitted = true
                        // Before even invoking location: no native I/O on unsupported devices.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1)
                            return@withLock PortResult.Failure(FailureReason.NOT_CONFIGURED)
                        try {
                            val (requested, keyPrefix) = location()
                            val directory = privateDirectory(requested, create = false).first
                            val initialDirectory = checkNotNull(statOrNull(directory))
                            require(initialDirectory.st_mode and PERMISSION_MASK == OWNER_DIRECTORY_MODE)
                            requireRecoveryInventory(directory)
                            val database = privateChild(directory, DATABASE_NAME)
                            val initialDatabase = requireExistingPrivateFile(database)
                            val lockFile = privateChild(directory, LOCK_NAME)
                            require(requireExistingPrivateFile(lockFile).st_size == 0L)
                            // Install lifetime before opening/acquiring any native descriptor.
                            val lifetime = DatabaseOwnership.prepare(lockFile, faults).also { ownership = it }
                            lifetime.openExisting(lockFile)
                            currentCoroutineContext().ensureActive()
                            val vault = AndroidStateVault.openExisting(keyPrefix)
                            beforeSqliteOpen(vault)
                            requireRecoveryInventory(directory)
                            require(sameIdentity(initialDirectory, checkNotNull(statOrNull(directory))))
                            require(sameIdentity(initialDatabase, requireExistingPrivateFile(database)))
                            faults.at("before_sqlite_open")
                            val opened = RetainedRecoveryConnection(BundledSQLiteDriver().open(database.path,
                                SQLITE_OPEN_READWRITE or SQLITE_OPEN_FULLMUTEX or SQLITE_OPEN_NOFOLLOW), faults)
                                .also { connection = it }
                            faults.at("after_sqlite_open")
                            currentCoroutineContext().ensureActive()
                            require(sameIdentity(initialDirectory, checkNotNull(statOrNull(directory))))
                            require(sameIdentity(initialDatabase, requireExistingPrivateFile(database)))
                            requireRecoveryInventory(directory)
                            // Common owner creation is synchronous. Retain it before its first await.
                            val owned = createDelegate(opened, vault, lifetime::close)
                                .also { recovery = it }
                            connection = null
                            faults.at("before_initialize")
                            val result = openDelegate(owned)
                            currentCoroutineContext().ensureActive()
                            result
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: StateActivationPlanFormatException) { PortResult.Failure(FailureReason.INVALID_DATA) }
                        catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
                        catch (_: LinkageError) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
                    }
                }
                if (!admitted || result is PortResult.Failure) return result
                // No recovery capability is published during the cancellable dispatcher handoff.
                return mutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (closeRequested) PortResult.Failure(FailureReason.STALE_SESSION)
                    else { ready = true; result }
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    mutex.withLock {
                        if (admitted || !attempted) { attempted = true; ready = false; closeRequested = true }
                    }
                }
                throw cancelled
            }
        }

        suspend fun <R> whenReady(action: suspend (T) -> PortResult<R>): PortResult<R> =
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    if (!ready) PortResult.Failure(FailureReason.STALE_SESSION) else action(checkNotNull(recovery))
                }
            }

        suspend fun close(): PortResult<Unit> = withContext(NonCancellable + Dispatchers.IO) {
            mutex.withLock {
                attempted = true
                ready = false
                closeRequested = true
                if (closed) return@withLock PortResult.Value(Unit)
                val owned = recovery
                if (owned != null) {
                    val result = closeDelegate(owned)
                    if (result is PortResult.Value) closed = true
                    result
                } else try {
                    // Never release the sibling lock until SQLite has acknowledged its close.
                    connection?.let { it.close(); connection = null }
                    ownership?.close()
                    closed = true
                    PortResult.Value(Unit)
                } catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
                  catch (_: LinkageError) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
            }
        }
        override fun toString() = "NativeExistingRecovery(<redacted>)"
    }

    /**
     * Bundled driver close can mark itself closed before its native call returns. A later no-op
     * cannot acknowledge a failed native close. Only explicit faults BEFORE admission are safely
     * retryable; any exception after admission is terminal and must keep the sibling owner held.
     */
    internal class RetainedRecoveryConnection(
        private val native: SQLiteConnection,
        private val faults: StateActivationRecoveryFaults,
    ) : SQLiteConnection by native {
        private var closed = false
        private var terminalCloseFailure = false

        @Synchronized
        override fun close() {
            if (closed) return
            check(!terminalCloseFailure) { "Private storage release requires process repair" }
            faults.at("before_sqlite_close")
            try {
                native.close()
                faults.at("after_sqlite_close")
                closed = true
            } catch (failure: Throwable) {
                terminalCloseFailure = true
                throw failure
            }
        }
    }

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
        private val reservedPath: String,
        private val faults: StateActivationRecoveryFaults,
    ) {
        private var channel: FileChannel? = null
        private var lock: FileLock? = null
        private var descriptor: FileDescriptor? = null
        private var lockReleased = false
        private var channelClosed = false
        private var descriptorClosed = false
        private var terminalCloseFailure = false
        private var closed = false

        @Synchronized
        fun close() {
            if (closed) return
            check(!terminalCloseFailure) { "Private storage release requires process repair" }
            if (!lockReleased) {
                faults.at("before_lock_release")
                val owned = lock
                if (owned != null && !owned.isValid) {
                    terminalCloseFailure = true
                    error("Private storage release requires process repair")
                }
                try { if (owned?.isValid == true) owned.release() }
                catch (failure: Exception) {
                    if (owned?.isValid == false) terminalCloseFailure = true
                    throw failure
                }
                lockReleased = true
            }
            if (!channelClosed) {
                faults.at("before_channel_close")
                val owned = channel
                if (owned != null && !owned.isOpen) {
                    terminalCloseFailure = true
                    error("Private storage release requires process repair")
                }
                try { owned?.close() }
                catch (failure: Exception) {
                    // A channel can mark itself closed before the native close failed. A later
                    // no-op close is not a new acknowledgement of that unresolved release.
                    if (owned?.isOpen == false) terminalCloseFailure = true
                    throw failure
                }
                channelClosed = true
            }
            // Android FileOutputStream(FileDescriptor) borrows its descriptor. Os.open ownership
            // remains here; a channel close alone does not release the recovery lock descriptor.
            if (!descriptorClosed) {
                faults.at("before_descriptor_close")
                val owned = descriptor
                if (owned != null) {
                    if (!owned.valid()) {
                        terminalCloseFailure = true
                        error("Private storage release requires process repair")
                    }
                    try { Os.close(owned) }
                    catch (failure: Exception) {
                        // Never recover/retry a saved numeric FD: it may already be reused.
                        if (!owned.valid()) terminalCloseFailure = true
                        throw failure
                    }
                    try { faults.at("after_descriptor_close") }
                    catch (failure: Exception) { terminalCloseFailure = true; throw failure }
                }
                descriptorClosed = true
            }
            faults.at("before_reservation_release")
            synchronized(reservedOwners) {
                check(reservedOwners[reservedPath] === this)
                reservedOwners.remove(reservedPath)
            }
            closed = true
        }

        /** The caller has already retained this lifetime before the first acquisition stage. */
        fun openExisting(file: File) {
            val before = requireExistingPrivateFile(file)
            require(before.st_size == 0L)
            faults.at("before_lock_open")
            val openedDescriptor = openExistingLockDescriptor(file.path).also { descriptor = it }
            faults.at("after_lock_open")
            val openedStat = Os.fstat(openedDescriptor)
            requirePrivateFile(openedStat)
            require(openedStat.st_size == 0L && sameIdentity(before, openedStat))
            require(sameIdentity(openedStat, requireExistingPrivateFile(file)))
            val openedChannel = FileOutputStream(openedDescriptor).channel.also { channel = it }
            faults.at("before_lock_acquire")
            lock = checkNotNull(openedChannel.tryLock())
            faults.at("after_lock_acquire")
        }

        companion object {
            // Strongly retain the actual owner, not just a path string, on failed legacy cleanup.
            // Legacy value-returning factories remain fail-closed but cannot expose retry ownership.
            private val reservedOwners = mutableMapOf<String, DatabaseOwnership>()

            fun prepare(file: File, faults: StateActivationRecoveryFaults = StateActivationRecoveryFaults {}): DatabaseOwnership {
                val owner = DatabaseOwnership(file.path, faults)
                synchronized(reservedOwners) {
                    check(!reservedOwners.containsKey(file.path))
                    reservedOwners[file.path] = owner
                }
                return owner
            }

            fun acquire(file: File): DatabaseOwnership {
                // POSIX locks belong to a process: closing a second descriptor for this inode can
                // release its first descriptor's lock. Reject duplicates before opening another FD.
                val owner = prepare(file)
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
                    ).also { owner.channel = it }
                    owner.lock = opened.tryLock() ?: throw IllegalStateException()
                    return owner
                } catch (error: Throwable) {
                    try { owner.close() } catch (closeError: Throwable) { error.addSuppressed(closeError) }
                    throw error
                }
            }

            fun acquireExisting(file: File): DatabaseOwnership {
                val owner = prepare(file)
                try {
                    owner.openExisting(file)
                    return owner
                } catch (error: Throwable) {
                    try { owner.close() } catch (closeError: Throwable) { error.addSuppressed(closeError) }
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
