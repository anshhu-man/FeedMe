package com.feedme.development.progress

import android.content.Context
import android.os.Build
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.feedme.core.ports.*
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.*

internal enum class ProgressInventoryKind { FRESH, EXISTING }

/** Positive evidence, never a cleanup operation. In particular, do not call noBackupFilesDir
 * while probing: Android may create that directory. No lock file is opened or read here. */
internal object ProgressNativeInventory {
    const val PACKAGE = "com.feedme.development.progress"
    private val directories = setOf("feedme-session-control", "feedme-session-work", "feedme-state", "feedme-credentials")
    private val prefixes = setOf("com.feedme.session.control.v1", "com.feedme.session.work.v1",
        "com.feedme.storage.private.v1", "com.feedme.session.credentials.v1")

    suspend fun inspect(context: Context): PortResult<ProgressInventoryKind> = withContext(Dispatchers.IO) {
        try {
            currentCoroutineContext().ensureActive()
            if (Build.VERSION.SDK_INT < 27) return@withContext PortResult.Failure(FailureReason.NOT_CONFIGURED)
            require(context.packageName == PACKAGE && context.applicationInfo.uid == Process.myUid())
            val root = File(context.applicationInfo.dataDir)
            directory(root, platform = true)
            val base = File(root.canonicalFile, "no_backup")
            val exists = exists(base)
            if (exists) directory(base, platform = true)
            val names = if (exists) checkNotNull(base.list()).toSet() else emptySet()
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.aliases().toList().toSet()
            if (names.isEmpty() && keys.isEmpty()) return@withContext PortResult.Value(ProgressInventoryKind.FRESH)
            require(names == directories && keys.isNotEmpty()) { "Incomplete progress ownership" }
            require(keys.all(::knownKey)) { "Unknown progress ownership" }
            for (name in directories) {
                val child = File(base, name); directory(child, platform = false)
                val children = checkNotNull(child.list()).toSet()
                val required = if (name == "feedme-credentials") setOf("credentials.lock", "manifest.bin")
                    else setOf("state.lock", "state.sqlite")
                require(children.containsAll(required))
                require(children.all { it in required || (name == "feedme-credentials" && progressCredentialCandidate(it)) })
                for (file in children) {
                    val stat = Os.lstat(File(child, file).path)
                    require(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Process.myUid() &&
                        (stat.st_mode and 511) == 384)
                    if (file.endsWith(".lock")) require(stat.st_size == 0L)
                }
            }
            currentCoroutineContext().ensureActive()
            PortResult.Value(ProgressInventoryKind.EXISTING)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
    }

    private fun knownKey(alias: String): Boolean = prefixes.any { prefix ->
        alias == "$prefix.index" ||
            (prefix == "com.feedme.session.credentials.v1" && alias == "$prefix.manifest") ||
            alias.matches(Regex("${Regex.escape(prefix)}\\.owner\\.[0-9a-f]{32}")) ||
            (prefix == "com.feedme.session.credentials.v1" &&
                alias.matches(Regex("${Regex.escape(prefix)}\\.credential\\.[0-9a-f]{64}")))
    }
    private fun exists(file: File): Boolean = try { Os.lstat(file.path); true }
        catch (missing: ErrnoException) { if (missing.errno == OsConstants.ENOENT) false else throw missing }
    private fun directory(file: File, platform: Boolean) {
        val stat = Os.lstat(file.path)
        require(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Process.myUid())
        val mode = stat.st_mode and 511
        require(mode == 448 || (platform && mode == 505))
    }
}

/** Coarse routing candidates ONLY. The retained native credential owner subsequently authenticates
 * every name against the original plan. This does not grant cleanup or ordinary-open authority. */
internal fun progressCredentialCandidate(name: String): Boolean =
    Regex("[0-9a-f]{64}\\.[1-9][0-9]{0,18}\\.bin(?:\\.pending)?").matches(name) ||
        Regex("manifest\\.(?:create|abort)\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.pending").matches(name)
