package com.feedme.storage

import android.system.Os
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_FULLMUTEX
import androidx.sqlite.driver.bundled.SQLITE_OPEN_NOFOLLOW
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import com.feedme.core.ports.*
import com.feedme.session.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/** Test-only native storage seam. No real identity, callback, timer or worker is created here. */
internal class AndroidWorkOriginPlanFixture(private val dispatcher: CoroutineDispatcher) {
    val box = AndroidStateTestSandbox()
    val boundary = SessionBoundary()
    val file: File get() = File(box.directory, "state.sqlite").canonicalFile
    lateinit var store: EncryptedSessionWorkStore
        private set
    lateinit var registry: SessionWorkRegistry
        private set
    var sql: WorkOriginSqlTrace? = null
        private set
    var allocatedIds = 0
        private set
    var effects = 0
        private set
    var allowIds = true
    private var storeOpen = false
    private var registryOpen = false

    suspend fun initialize() {
        store = AndroidStateDatabase.openOwned(
            location = { box.directory to box.keyPrefix },
            requireNewVaultForNewFile = true,
            requireNewDirectoryForInitialization = true,
            wrap = { database, created -> EncryptedSessionWorkStore.open(database, created) },
        ).valueOrFail()
        storeOpen = true
        openRegistry()
    }

    suspend fun reopen(vfs: Boolean = false, traced: Boolean = false) {
        close()
        if (vfs || traced) {
            if (vfs) SqliteSyncFailureInjector.registerVfs()
            val vault = AndroidStateVault.createOrOpen(box.keyPrefix, databaseExisted = true)
            val trace = WorkOriginSqlTrace(BundledSQLiteDriver().open(
                if (vfs) "${file.toURI()}?vfs=feedme-test-sync-failure-v1" else file.path,
                SQLITE_OPEN_READWRITE or SQLITE_OPEN_FULLMUTEX or SQLITE_OPEN_NOFOLLOW or (if (vfs) 0x00000040 else 0),
            ))
            sql = trace
            val database = EncryptedStateDatabase.open(trace, vault).valueOrFail()
            store = EncryptedSessionWorkStore.open(database, false).valueOrFail()
        } else {
            sql = null
            store = AndroidStateDatabase.openOwned(
                location = { box.directory to box.keyPrefix },
                requireNewVaultForNewFile = true,
                requireNewDirectoryForInitialization = true,
                wrap = { database, created -> EncryptedSessionWorkStore.open(database, created) },
            ).valueOrFail()
        }
        storeOpen = true
        openRegistry()
    }

    private suspend fun openRegistry() {
        registry = SessionWorkRegistry.open(store, boundary, dispatcher,
            NativeWorkCancellationPort { forbiddenEffect() },
            NativeWorkIdSource {
                check(allowIds) { "Recovery must not allocate another origin" }
                allocatedIds++
                ORIGIN
            },
            NativeWorkAdmissionPolicy { forbiddenEffect() },
            NativeWorkExecutionPolicy { _, _, _, _ -> forbiddenEffect() },
        ).valueOrFail()
        registryOpen = true
    }

    private fun forbiddenEffect(): Nothing {
        effects++
        throw AssertionError("Setup-only work planning must not consult identity or native effect policies")
    }

    suspend fun closeRegistry() {
        if (registryOpen) {
            registry.close().valueOrFail()
            registryOpen = false
        }
    }

    suspend fun close() {
        closeRegistry()
        if (storeOpen) {
            store.close().valueOrFail()
            storeOpen = false
        }
    }

    fun destroy() { box.close() }

    suspend fun record(): SessionControlRecord = store.read().valueOrFail()
        ?: throw AssertionError("A previously initialized work ledger cannot become missing")

    fun aliases(): Set<String> = box.keyStore().aliases().toList()
        .filter { it.startsWith("${box.keyPrefix}.") }.toSet()

    fun fileSnapshot(): Map<String, String> = box.directory.walkTopDown().filter { it.isFile }.associate { f ->
        val stat = Os.lstat(f.path)
        // Closing an extra descriptor for a POSIX lock inode can release the process's lock.
        // Stat proves the lifetime lock is empty without ever opening another descriptor.
        val bytes = if (f.name == "state.lock") {
            assertEquals("Lifetime lock contents must remain empty", 0L, stat.st_size)
            ByteArray(0)
        } else f.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        f.relativeTo(box.directory).path to "${stat.st_ino}:${stat.st_mode}:${f.length()}:$digest"
    }

    fun rowSnapshot(): List<String> {
        val connection = BundledSQLiteDriver().open(file.path, 0x00000001 or SQLITE_OPEN_FULLMUTEX or SQLITE_OPEN_NOFOLLOW)
        try {
            val queries = listOf(
                "SELECT name || ':' || sql FROM sqlite_master WHERE type='table' ORDER BY name",
                "SELECT owner_tag || ':' || generation || ':' || active || ':' || key_id FROM feedme_owners ORDER BY owner_tag",
                "SELECT owner_tag || ':' || record_tag || ':' || revision || ':' || schema_version || ':' || hex(payload) FROM feedme_records ORDER BY owner_tag,record_tag",
                "SELECT key_id FROM feedme_key_gc ORDER BY key_id",
                "SELECT owner_tag || ':' || generation || ':' || key_id || ':' || revision FROM feedme_activation_aborts ORDER BY owner_tag",
            )
            return queries.flatMap { query ->
                connection.prepare(query).use { statement ->
                    buildList { while (statement.step()) add(statement.getText(0)) }
                }
            }
        } finally { connection.close() }
    }

    fun assertNoEffects() {
        assertEquals(0, effects)
        assertNull(boundary.current())
    }

    companion object {
        val SCOPE = StorageScope("native-work-origin-plan", ActorKind.ACCOUNT, "test-only-origin-owner")
        const val ORIGIN = "00000000-0000-4000-8000-000000000271"
        const val TICKET = "00000000-0000-4000-8000-000000000272"
        fun bytes(value: String) = PrivateBytes(value.encodeToByteArray())
        fun sameBytes(left: PrivateBytes, right: PrivateBytes) = left.copyForCodec().contentEquals(right.copyForCodec())
        fun failure(result: PortResult<*>, vararg expected: FailureReason) {
            assertTrue("Expected a typed failure", result is PortResult.Failure)
            assertTrue("Unexpected failure reason: ${(result as PortResult.Failure).reason}", result.reason in expected)
            assertNull(result.retryAfterSeconds)
        }
    }
}

internal class WorkOriginSqlTrace(private val delegate: SQLiteConnection) : SQLiteConnection by delegate {
    val nativeCodes = mutableListOf<Int>()
    var afterCommit: (() -> Unit)? = null
    private var transactionWrote = false
    override fun prepare(sql: String): SQLiteStatement {
        val statement = delegate.prepare(sql)
        return object : SQLiteStatement by statement {
            override fun step(): Boolean = try {
                statement.step().also {
                    val command = sql.trim().substringBefore(' ').uppercase()
                    if (command in setOf("INSERT", "UPDATE", "DELETE")) transactionWrote = true
                    if (command == "ROLLBACK") transactionWrote = false
                    if (command == "COMMIT") {
                        val changed = transactionWrote
                        transactionWrote = false
                        if (changed) afterCommit?.invoke()
                    }
                }
            } catch (failure: Exception) {
                Regex("Error code: ([0-9]+)").find(failure.message.orEmpty())?.groupValues?.get(1)
                    ?.toIntOrNull()?.let(nativeCodes::add)
                throw failure
            }
        }
    }
}
