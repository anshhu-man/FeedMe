package com.feedme.storage

import android.content.Context
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.PrivateBytes
import java.io.File
import kotlinx.coroutines.Dispatchers

/**
 * Native non-backup retirement metadata only: never store credentials here. The fixed directory
 * and independent Keystore prefix survive retirement of ordinary FeedMe owner data keys.
 * Existing incomplete state is a repair gate, not permission to reinitialize an idle ledger.
 */
object AndroidSessionControlStore {
    suspend fun open(context: Context): PortResult<EncryptedSessionControlStore> = AndroidStateDatabase.openOwned(
        location = {
            val base = context.applicationContext.noBackupFilesDir
            require(!java.nio.file.Files.isSymbolicLink(base.toPath()))
            File(base.canonicalFile, DIRECTORY_NAME) to KEY_PREFIX
        },
        requireNewVaultForNewFile = true,
        requireNewDirectoryForInitialization = true,
        wrap = { database, fileCreated -> EncryptedSessionControlStore.open(database, allowInitialize = fileCreated) },
    )

    /**
     * Synchronous trusted startup composition. Retain before open and through every failed or
     * cancelled acquisition/close. API 27+ is checked before Context access. Only the existing
     * fixed control owner/sole ledger is admitted; no normal open/resume, initialization or GC.
     * The session protocol alone interprets control bytes and authorizes exact native effects.
     */
    @SessionControlRecoveryCompositionApi
    fun createRecoveryStore(context: Context): ExistingSessionControlRecoveryStore = NativeControlRecoveryStore(
        faults = StateActivationRecoveryFaults {},
        location = {
            val base = context.applicationContext.noBackupFilesDir
            require(!java.nio.file.Files.isSymbolicLink(base.toPath()))
            File(base.canonicalFile, DIRECTORY_NAME) to KEY_PREFIX
        },
    )

    @SessionControlRecoveryCompositionApi
    internal fun createRecoveryStoreForTests(directory: File, keyPrefix: String,
        faults: StateActivationRecoveryFaults = StateActivationRecoveryFaults {}): ExistingSessionControlRecoveryStore =
        NativeControlRecoveryStore(faults) { directory to keyPrefix }

    @OptIn(SessionControlRecoveryCompositionApi::class)
    private class NativeControlRecoveryStore(faults: StateActivationRecoveryFaults,
        location: () -> Pair<File, String>) : ExistingSessionControlRecoveryStore {
        private val lifetime = AndroidStateDatabase.NativeExistingRecovery(
            faults = faults, location = location,
            // The control record is authenticated by its fixed owner key after guarded SQLite
            // opening; nested plan authentication and confirmation belong to the session layer.
            beforeSqliteOpen = {},
            createDelegate = { connection, vault, onClosed ->
                EncryptedStateDatabase.createControlRecoveryStore(connection, vault, Dispatchers.IO, onClosed)
            },
            openDelegate = { it.open() }, closeDelegate = { it.close() },
        )
        override suspend fun open() = lifetime.open()
        override suspend fun read() = lifetime.whenReady { it.read() }
        override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes) =
            lifetime.whenReady { it.compareAndSet(expectedRevision, payload) }
        override suspend fun close() = lifetime.close()
        override fun toString() = "ExistingSessionControlRecoveryStore(<redacted>)"
    }

    private const val DIRECTORY_NAME = "feedme-session-control"
    private const val KEY_PREFIX = "com.feedme.session.control.v1"
}
