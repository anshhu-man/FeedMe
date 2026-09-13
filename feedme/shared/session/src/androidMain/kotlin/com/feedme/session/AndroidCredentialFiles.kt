package com.feedme.session

import android.os.Build
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import kotlinx.coroutines.CancellationException

/** Instrumentation-only failure points. No production caller supplies an injector. */
internal enum class CredentialFileFaultPoint {
    AFTER_MANIFEST_RENAME, AFTER_BLOB_RENAME, AFTER_BLOB_DELETE,
    AFTER_KEY_CREATE, AFTER_BLOB_TEMP_CREATE, AFTER_BLOB_TEMP_FORCE,
    AFTER_CREATE_MANIFEST_TEMP_FORCE, AFTER_ABORT_MANIFEST_TEMP_FORCE,
    AFTER_ABORT_CONSUMED, AFTER_KEY_DELETE,
    BEFORE_DURABILITY_SYNC, AFTER_DURABILITY_SYNC,
    AFTER_LOCK_DESCRIPTOR_OPEN, AFTER_LOCK_ACQUIRED,
    BEFORE_LOCK_RELEASE, BEFORE_LOCK_CHANNEL_CLOSE, BEFORE_LOCK_DESCRIPTOR_CLOSE,
    AFTER_LOCK_DESCRIPTOR_CLOSE, AFTER_RECOVERY_FILES_OPEN, AFTER_RECOVERY_AUTHENTICATED,
}

internal class CredentialFileException(val outcomeUnknown: Boolean = false) :
    Exception("Credential storage unavailable")

/** Fixed-name, private, no-follow files. Incomplete writes are deliberately not cleaned up. */
internal class AndroidCredentialFiles private constructor(
    private val directory: File,
    private val ownership: Ownership,
    private val faultInjector: (CredentialFileFaultPoint) -> Unit,
    /** True only after this call's own successful mkdir, never after EEXIST or an earlier call. */
    val wasCreatedByThisOpen: Boolean,
) {
    fun names(): Set<String> = checkNotNull(directory.list()).map { name ->
        child(name) // Validate every entry, including an unrecognized entry, without following it.
        name
    }.toSet()

    fun exists(name: String): Boolean = statOrNull(child(name)) != null

    fun read(name: String): ByteArray {
        val file = child(name)
        val descriptor = openDescriptor(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        return consumeDescriptor(descriptor) { owned ->
            val attributes = Os.fstat(owned)
            requirePrivateFile(attributes)
            require(attributes.st_size in MIN_CIPHER_BYTES.toLong()..MAX_CIPHER_BYTES.toLong())
            FileInputStream(owned).use { input ->
                val bytes = ByteArray(attributes.st_size.toInt())
                var offset = 0
                while (offset < bytes.size) {
                    val count = input.read(bytes, offset, bytes.size - offset)
                    require(count > 0)
                    offset += count
                }
                require(input.read() == -1)
                bytes
            }
        }
    }

    /** New snapshots are immutable; only the authenticated manifest is replaceable. */
    fun write(name: String, ciphertext: ByteArray, replace: Boolean, pendingName: String = "$name.pending") {
        require(ciphertext.size in MIN_CIPHER_BYTES..MAX_CIPHER_BYTES)
        val target = child(name)
        require(replace == (name == MANIFEST))
        if (!replace) require(statOrNull(target) == null)
        require(pendingName == "$name.pending" || (name == MANIFEST && PLAN_MANIFEST_PENDING.matches(pendingName)))
        val pending = child(pendingName)
        require(statOrNull(pending) == null)
        var renamed = false
        try {
            val descriptor = openDescriptor(pending.path,
                OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                    OsConstants.O_NOFOLLOW, FILE_MODE)
            consumeDescriptor(descriptor) { owned ->
                requirePrivateFile(Os.fstat(owned))
                if (name != MANIFEST) faultInjector(CredentialFileFaultPoint.AFTER_BLOB_TEMP_CREATE)
                FileOutputStream(owned).use { output ->
                    val buffer = ByteBuffer.wrap(ciphertext)
                    while (buffer.hasRemaining()) require(output.channel.write(buffer) > 0)
                    output.channel.force(true)
                }
            }
            when {
                name != MANIFEST -> faultInjector(CredentialFileFaultPoint.AFTER_BLOB_TEMP_FORCE)
                pendingName.startsWith("manifest.create.") ->
                    faultInjector(CredentialFileFaultPoint.AFTER_CREATE_MANIFEST_TEMP_FORCE)
                pendingName.startsWith("manifest.abort.") ->
                    faultInjector(CredentialFileFaultPoint.AFTER_ABORT_MANIFEST_TEMP_FORCE)
            }
            // Recheck both names immediately before the atomic same-directory rename.
            child(pending.name)
            child(name)
            if (!replace) require(statOrNull(target) == null)
            Os.rename(pending.path, target.path)
            renamed = true
            faultInjector(if (name == MANIFEST) CredentialFileFaultPoint.AFTER_MANIFEST_RENAME
                else CredentialFileFaultPoint.AFTER_BLOB_RENAME)
            syncDirectory(directory)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw CredentialFileException(outcomeUnknown = renamed)
        }
    }

    /** Delete only a previously authenticated exact revision path, never an inventory wildcard. */
    fun deleteBlob(name: String) {
        require(BLOB.matches(name))
        deleteExactArtifact(name)
    }

    /** Only the authenticated CREATE plan may authorize these exact, already validated names. */
    fun deleteCreateArtifact(name: String) {
        require(BLOB.matches(name) || PLAN_MANIFEST_PENDING.matches(name) ||
            (name.endsWith(".pending") && BLOB.matches(name.removeSuffix(".pending"))))
        deleteExactArtifact(name)
    }

    /** Re-acknowledge a manifest observed after a prior rename with an unknown fsync result. */
    fun synchronize() {
        faultInjector(CredentialFileFaultPoint.BEFORE_DURABILITY_SYNC)
        syncDirectory(directory)
        faultInjector(CredentialFileFaultPoint.AFTER_DURABILITY_SYNC)
    }

    fun checkpoint(point: CredentialFileFaultPoint) = faultInjector(point)

    private fun deleteExactArtifact(name: String) {
        val target = child(name)
        var unlinked = false
        try {
            if (statOrNull(target) != null) {
                // Public API 21+. The exact child was checked as a private regular file;
                // remove never recursively traverses a path or follows its final symlink.
                Os.remove(target.path)
                unlinked = true
                if (BLOB.matches(name) || (name.endsWith(".pending") && BLOB.matches(name.removeSuffix(".pending")))) {
                    faultInjector(CredentialFileFaultPoint.AFTER_BLOB_DELETE)
                }
            }
            // Also acknowledge a replayed absent entry durably before clearing its manifest.
            syncDirectory(directory)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw CredentialFileException(outcomeUnknown = unlinked)
        }
    }

    fun close() = ownership.close()

    private fun child(name: String): File {
        require(name == LOCK || name == MANIFEST || BLOB.matches(name) ||
            name == "$MANIFEST.pending" || PLAN_MANIFEST_PENDING.matches(name) ||
            (name.endsWith(".pending") && BLOB.matches(name.removeSuffix(".pending"))))
        val file = File(directory, name)
        require(file.canonicalFile == file)
        statOrNull(file)?.let(::requirePrivateFile)
        return file
    }

    /** Construct before acquisition, so failed opening never discards its only native owner. */
    internal class Opening internal constructor(
        private val requested: File,
        private val faultInjector: (CredentialFileFaultPoint) -> Unit,
        private val existingOnly: Boolean,
    ) {
        private val ownership = Ownership(faultInjector)
        private var started = false
        private var closing = false

        fun open(): AndroidCredentialFiles {
            check(!started && !closing)
            started = true
            return openPrepared(requested, faultInjector, existingOnly, ownership)
        }

        fun close() { closing = true; ownership.close() }
    }

    private class Ownership(private val faultInjector: (CredentialFileFaultPoint) -> Unit) {
        private var descriptor: FileDescriptor? = null
        private var channel: FileChannel? = null
        private var lock: FileLock? = null
        private var path: String? = null
        private var released = false
        private var terminalCloseFailure = false
        private var lockReleased = true
        private var channelClosed = true
        private var descriptorClosed = true

        fun acquire(file: File, create: Boolean) {
            check(path == null && !released)
            val selectedPath = file.path
            synchronized(reservations) {
                require(!reservations.containsKey(selectedPath))
                reservations[selectedPath] = this
                path = selectedPath
            }
            // Each resource is attached before any subsequent check/callback can fail.
            descriptor = openDescriptor(selectedPath, OsConstants.O_WRONLY or
                (if (create) OsConstants.O_CREAT else 0) or OsConstants.O_NOFOLLOW, FILE_MODE)
            descriptorClosed = false
            val ownedDescriptor = checkNotNull(descriptor)
            faultInjector(CredentialFileFaultPoint.AFTER_LOCK_DESCRIPTOR_OPEN)
            requirePrivateFile(Os.fstat(ownedDescriptor))
            channel = FileOutputStream(ownedDescriptor).channel
            channelClosed = false
            lock = checkNotNull(checkNotNull(channel).tryLock())
            lockReleased = false
            faultInjector(CredentialFileFaultPoint.AFTER_LOCK_ACQUIRED)
        }

        @Synchronized
        fun close() {
            if (released) return
            // Native close may invalidate the descriptor before reporting an error. It must
            // never be retried by saved number or promoted into a later success. Preserve this
            // explicit process-only failure boundary until the process releases the owner.
            if (terminalCloseFailure) throw CredentialFileException()
            // Stop at a failed stage. The retained owner and reservation remain available for
            // another close, with no new descriptor opened on this POSIX-lock inode.
            if (!lockReleased) {
                val ownedLock = checkNotNull(lock)
                if (!ownedLock.isValid) { terminalCloseFailure = true; throw CredentialFileException() }
                faultInjector(CredentialFileFaultPoint.BEFORE_LOCK_RELEASE)
                try { ownedLock.release(); lockReleased = true } catch (error: Throwable) {
                    if (!ownedLock.isValid) terminalCloseFailure = true
                    throw error
                }
            }
            if (!channelClosed) {
                val ownedChannel = checkNotNull(channel)
                if (!ownedChannel.isOpen) { terminalCloseFailure = true; throw CredentialFileException() }
                faultInjector(CredentialFileFaultPoint.BEFORE_LOCK_CHANNEL_CLOSE)
                try { ownedChannel.close(); channelClosed = true } catch (error: Throwable) {
                    if (!ownedChannel.isOpen) terminalCloseFailure = true
                    throw error
                }
            }
            if (!descriptorClosed) {
                val ownedDescriptor = checkNotNull(descriptor)
                if (!ownedDescriptor.valid()) { terminalCloseFailure = true; throw CredentialFileException() }
                faultInjector(CredentialFileFaultPoint.BEFORE_LOCK_DESCRIPTOR_CLOSE)
                // Android streams borrow this descriptor. Os.close clears this descriptor
                // object even on native close errors; never retry a saved numeric fd.
                try {
                    Os.close(ownedDescriptor)
                    // A test callback here models a platform close error after invalidation;
                    // it is distinct from the retryable BEFORE_* stage callbacks above.
                    faultInjector(CredentialFileFaultPoint.AFTER_LOCK_DESCRIPTOR_CLOSE)
                    descriptorClosed = true
                } catch (error: Throwable) {
                    if (!ownedDescriptor.valid()) terminalCloseFailure = true
                    throw error
                }
            }
            check(lockReleased && channelClosed && descriptorClosed)
            check(descriptor?.valid() != true && channel?.isOpen != true)
            path?.let { selectedPath -> synchronized(reservations) {
                check(reservations[selectedPath] === this)
                reservations.remove(selectedPath)
            } }
            released = true
        }

        companion object {
            // Keep the actual close owner, not just a pathname. Legacy factories cannot expose
            // a failed cleanup owner; such a reservation remains a process-only recovery gate.
            private val reservations = mutableMapOf<String, Ownership>()
        }
    }

    companion object {
        internal const val LOCK = "credentials.lock"
        internal const val MANIFEST = "manifest.bin"
        private const val DIRECTORY_MODE = 448 // 0700
        private const val FILE_MODE = 384 // 0600
        private const val PERMISSION_MASK = 511 // 0777
        private const val MIN_CIPHER_BYTES = 29
        private const val MAX_CIPHER_BYTES = CredentialCodec.MAX_BYTES + MIN_CIPHER_BYTES
        private val BLOB = Regex("[0-9a-f]{64}\\.[1-9][0-9]{0,18}\\.bin")
        private val PLAN_MANIFEST_PENDING = Regex("manifest\\.(create|abort)\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.pending")

        fun blobName(target: String, revision: Long): String = "$target.$revision.bin".also {
            require(revision > 0 && BLOB.matches(it))
        }

        fun createManifestPending(incarnation: String): String = "manifest.create.$incarnation.pending".also {
            require(PLAN_MANIFEST_PENDING.matches(it))
        }

        fun abortManifestPending(incarnation: String): String = "manifest.abort.$incarnation.pending".also {
            require(PLAN_MANIFEST_PENDING.matches(it))
        }

        fun open(
            requested: File,
            faultInjector: (CredentialFileFaultPoint) -> Unit,
            existingOnly: Boolean = false,
        ): AndroidCredentialFiles {
            val opening = prepareOpen(requested, faultInjector, existingOnly)
            return try { opening.open() } catch (error: Throwable) {
                try { opening.close() } catch (closeError: Throwable) { error.addSuppressed(closeError) }
                throw error
            }
        }

        fun prepareOpen(requested: File, faultInjector: (CredentialFileFaultPoint) -> Unit,
            existingOnly: Boolean): Opening = Opening(requested, faultInjector, existingOnly)

        private fun openPrepared(
            requested: File,
            faultInjector: (CredentialFileFaultPoint) -> Unit,
            existingOnly: Boolean,
            ownership: Ownership,
        ): AndroidCredentialFiles {
            // Public atomic O_CLOEXEC is API 27+. Never substitute a weaker native-open path.
            require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1)
            val absolute = requested.absoluteFile
            val parent = checkNotNull(absolute.parentFile).canonicalFile
            require(statOrNull(parent)?.let { OsConstants.S_ISDIR(it.st_mode) && it.st_uid == Process.myUid() } == true)
            val directory = File(parent, absolute.name)
            require(absolute.name != "." && absolute.name != ".." && absolute.canonicalFile == directory)
            var wasCreatedByThisOpen = false
            if (statOrNull(directory) == null) {
                require(!existingOnly)
                try {
                    Os.mkdir(directory.path, DIRECTORY_MODE)
                    wasCreatedByThisOpen = true
                } catch (error: ErrnoException) {
                    if (error.errno != OsConstants.EEXIST) throw error
                }
                if (wasCreatedByThisOpen) syncDirectory(parent)
            }
            val attributes = checkNotNull(statOrNull(directory))
            require(OsConstants.S_ISDIR(attributes.st_mode) && attributes.st_uid == Process.myUid())
            require(attributes.st_mode and PERMISSION_MASK == DIRECTORY_MODE && directory.canonicalFile == directory)
            val lockFile = File(directory, LOCK)
            require(lockFile.canonicalFile == lockFile)
            val lockAttributes = statOrNull(lockFile)
            if (existingOnly) require(lockAttributes != null)
            lockAttributes?.let(::requirePrivateFile)
            ownership.acquire(lockFile, create = !existingOnly)
            return AndroidCredentialFiles(directory, ownership, faultInjector, wasCreatedByThisOpen)
        }

        private fun requirePrivateFile(attributes: StructStat) {
            require(OsConstants.S_ISREG(attributes.st_mode) && attributes.st_uid == Process.myUid())
            require(attributes.st_nlink == 1L && attributes.st_mode and PERMISSION_MASK == FILE_MODE)
        }

        private fun statOrNull(file: File): StructStat? = try { Os.lstat(file.path) } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT) null else throw error
        }

        private fun openDescriptor(path: String, flags: Int, mode: Int): FileDescriptor {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                return Os.open(path, flags or OsConstants.O_CLOEXEC, mode)
            }
            throw IllegalStateException("Credential storage unavailable")
        }

        private fun syncDirectory(directory: File) {
            // O_DIRECTORY is not in the Android public SDK. NOFOLLOW prevents a final symlink;
            // NONBLOCK avoids blocking on a substituted special file, then fstat requires a dir.
            val descriptor = openDescriptor(directory.path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or
                OsConstants.O_NOFOLLOW, 0)
            try {
                val stat = Os.fstat(descriptor)
                require(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Process.myUid())
                Os.fsync(descriptor)
            } finally { Os.close(descriptor) }
        }

        // Android streams borrow an explicitly supplied FileDescriptor; Os.open remains our
        // responsibility. A close error must also prevent a successful write acknowledgment.
        private inline fun <T> consumeDescriptor(descriptor: FileDescriptor, block: (FileDescriptor) -> T): T {
            var failure: Throwable? = null
            try { return block(descriptor) } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                if (descriptor.valid()) {
                    try { Os.close(descriptor) } catch (closeError: Throwable) {
                        if (failure == null) throw closeError else failure.addSuppressed(closeError)
                    }
                }
            }
        }
    }
}
