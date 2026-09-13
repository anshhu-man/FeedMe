package com.feedme.storage

import android.content.Context
import com.feedme.core.ports.PortResult
import java.io.File

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

    private const val DIRECTORY_NAME = "feedme-session-control"
    private const val KEY_PREFIX = "com.feedme.session.control.v1"
}
