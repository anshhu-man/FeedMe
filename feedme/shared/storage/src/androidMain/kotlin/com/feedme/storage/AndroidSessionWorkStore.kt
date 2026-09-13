package com.feedme.storage

import android.content.Context
import com.feedme.core.ports.PortResult
import java.io.File

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

    private const val DIRECTORY_NAME = "feedme-session-work"
    private const val KEY_PREFIX = "com.feedme.session.work.v1"
}
