package com.feedme.storage

import android.content.Context
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.PrivateBytes
import com.feedme.core.ports.SessionControlRecord
import java.io.File
import kotlinx.coroutines.Dispatchers

/**
 * Independent non-backup native-work/cancellation evidence; never store credentials or recipe
 * payloads here. Exact origin retirement must remain retryable after those other keys are erased.
 * Existing incomplete state is a repair gate, not permission to recreate an idle registry.
 */
object AndroidSessionWorkStore {
    suspend fun open(context: Context): PortResult<EncryptedSessionWorkStore> = AndroidStateDatabase.openOwned(
        location = {
            val base = context.applicationContext.noBackupFilesDir
            require(!java.nio.file.Files.isSymbolicLink(base.toPath()))
            File(base.canonicalFile, DIRECTORY_NAME) to KEY_PREFIX
        },
        requireNewVaultForNewFile = true,
        requireNewDirectoryForInitialization = true,
        wrap = { database, fileCreated -> EncryptedSessionWorkStore.open(database, allowInitialize = fileCreated) },
    )

    /**
     * Trusted recovery composition only. Construction performs no Context/native I/O. Retain
     * this owner before open and through failed acquisition/close. API 27+, existing exact work
     * files/schema/owner/ledger only: never normal open, resume, initialization, migration or GC.
     * This storage SPI alone neither authenticates a session plan nor permits a native effect.
     */
    @WorkRecoveryCompositionApi
    fun createRecoveryStore(context: Context): ExistingSessionWorkRecoveryStore = NativeWorkRecoveryStore(
        faults = StateActivationRecoveryFaults {},
        location = {
            val base = context.applicationContext.noBackupFilesDir
            require(!java.nio.file.Files.isSymbolicLink(base.toPath()))
            File(base.canonicalFile, DIRECTORY_NAME) to KEY_PREFIX
        },
    )

    @WorkRecoveryCompositionApi
    internal fun createRecoveryStoreForTests(directory: File, keyPrefix: String,
        faults: StateActivationRecoveryFaults = StateActivationRecoveryFaults {}): ExistingSessionWorkRecoveryStore =
        NativeWorkRecoveryStore(faults) { directory to keyPrefix }

    @OptIn(WorkRecoveryCompositionApi::class)
    private class NativeWorkRecoveryStore(faults: StateActivationRecoveryFaults,
        location: () -> Pair<File, String>) : ExistingSessionWorkRecoveryStore {
        private val lifetime = AndroidStateDatabase.NativeExistingRecovery(
            faults = faults,
            location = location,
            // Work proofs bind the persisted owner generation/key. Unlike a data plan, their
            // authentication requires the existing ledger read after guarded SQLite opening.
            beforeSqliteOpen = {},
            createDelegate = { connection, vault, onClosed ->
                EncryptedStateDatabase.createWorkRecoveryStore(connection, vault, Dispatchers.IO, onClosed)
            },
            openDelegate = { it.open() }, closeDelegate = { it.close() },
        )
        override suspend fun open() = lifetime.open()
        override suspend fun read() = lifetime.whenReady { it.read() }
        override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes) =
            lifetime.whenReady { it.compareAndSet(expectedRevision, payload) }
        override suspend fun verifyOriginPlan(expectedRevision: Long, proposal: PrivateBytes, proof: PrivateBytes) =
            lifetime.whenReady { it.verifyOriginPlan(expectedRevision, proposal, proof) }
        override suspend fun verifyOriginPredecessor(expected: SessionControlRecord, proposal: PrivateBytes, proof: PrivateBytes) =
            lifetime.whenReady { it.verifyOriginPredecessor(expected, proposal, proof) }
        override suspend fun close() = lifetime.close()
        override fun toString() = "ExistingSessionWorkRecoveryStore(<redacted>)"
    }

    private const val DIRECTORY_NAME = "feedme-session-work"
    private const val KEY_PREFIX = "com.feedme.session.work.v1"
}
