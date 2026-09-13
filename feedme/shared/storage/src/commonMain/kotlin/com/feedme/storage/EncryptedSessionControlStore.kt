package com.feedme.storage

import com.feedme.core.ports.ActorKind
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.PrivateBytes
import com.feedme.core.ports.PrivateRecord
import com.feedme.core.ports.PrivateStateStore
import com.feedme.core.ports.RecordKey
import com.feedme.core.ports.SessionControlRecord
import com.feedme.core.ports.SessionControlStore
import com.feedme.core.ports.StorageScope
import com.feedme.core.ports.StoreMutation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One encrypted install-local retirement-control record, not password or token storage.
 * Its native database and vault namespace are independent of every retiring owner. Neither the
 * underlying owner handle nor an activation/erasure/reset operation is exposed to callers.
 *
 * An interrupted first setup can leave a database without this record. That is an explicit repair
 * gate: later opens fail closed rather than treating missing retirement evidence as an idle state.
 */
class EncryptedSessionControlStore private constructor(
    private val database: EncryptedStateDatabase,
    private val store: PrivateStateStore,
) : SessionControlStore {
    private val mutex = Mutex()

    override suspend fun read(): PortResult<SessionControlRecord?> = mutex.withLock {
        when (val result = readExisting()) {
            is PortResult.Value -> PortResult.Value(result.value.asControlRecord())
            is PortResult.Failure -> result
        }
    }

    override suspend fun compareAndSet(
        expectedRevision: Long?,
        payload: PrivateBytes,
    ): PortResult<SessionControlRecord> {
        if (expectedRevision != null && expectedRevision <= 0) return PortResult.Failure(FailureReason.INVALID_DATA)
        if (!bounded(payload)) return PortResult.Failure(FailureReason.INVALID_DATA)
        return mutex.withLock {
            // Missing or malformed initialized state must not be repaired by an otherwise valid CAS.
            val current = when (val result = readExisting()) {
                is PortResult.Value -> result.value
                is PortResult.Failure -> return@withLock result
            }
            if (expectedRevision != current.revision) return@withLock PortResult.Failure(FailureReason.CONFLICT)
            when (val result = store.commit(CONTROL_SCOPE, listOf(
                StoreMutation.Put(CONTROL_KEY, expectedRevision, SCHEMA_VERSION, payload),
            ))) {
                is PortResult.Failure -> result
                is PortResult.Value -> {
                    val revision = result.value[CONTROL_KEY]
                    if (revision == null || revision <= current.revision) PortResult.Failure(FailureReason.STORAGE_FAILURE)
                    else PortResult.Value(SessionControlRecord(revision, payload))
                }
            }
        }
    }

    suspend fun close(): PortResult<Unit> = withContext(NonCancellable) {
        mutex.withLock { database.close() }
    }

    override fun toString(): String = "EncryptedSessionControlStore(<private-control>)"

    private suspend fun readExisting(): PortResult<PrivateRecord> = when (val result = store.read(CONTROL_SCOPE, CONTROL_KEY)) {
        is PortResult.Failure -> result
        is PortResult.Value -> {
            val record = result.value
            if (record == null || record.revision <= 0 || record.schemaVersion != SCHEMA_VERSION || !bounded(record.payload)) {
                PortResult.Failure(FailureReason.STORAGE_FAILURE)
            } else PortResult.Value(record)
        }
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val MAX_RECORD_BYTES = 32 * 1024
        private val CONTROL_SCOPE = StorageScope("feedme-session-control-v1", ActorKind.DEMO, "install-control")
        private val CONTROL_KEY = RecordKey("session-control", "retirement-ledger")
        private val INITIAL_PAYLOAD = PrivateBytes("{\"version\":1,\"state\":\"idle\"}".encodeToByteArray())

        /**
         * Takes exclusive database ownership even on failure. [allowInitialize] is true only when
         * the native factory itself successfully created a new database file, never after a read
         * returned null or because recovery appeared necessary. It is not an application reset API.
         */
        internal suspend fun open(
            database: EncryptedStateDatabase,
            allowInitialize: Boolean,
        ): PortResult<EncryptedSessionControlStore> {
            try {
                val owned = if (allowInitialize) database.activate(CONTROL_SCOPE) else database.resume(CONTROL_SCOPE)
                val store = when (owned) {
                    is PortResult.Failure -> { database.close(); return owned }
                    is PortResult.Value -> owned.value ?: run {
                        database.close()
                        return PortResult.Failure(FailureReason.STORAGE_FAILURE)
                    }
                }
                if (allowInitialize) {
                    when (val initialized = store.commit(CONTROL_SCOPE, listOf(
                        StoreMutation.Put(CONTROL_KEY, null, SCHEMA_VERSION, INITIAL_PAYLOAD),
                    ))) {
                        is PortResult.Failure -> { database.close(); return initialized }
                        is PortResult.Value -> Unit
                    }
                }
                val wrapper = EncryptedSessionControlStore(database, store)
                return when (val result = wrapper.readExisting()) {
                    is PortResult.Value -> PortResult.Value(wrapper)
                    is PortResult.Failure -> { database.close(); result }
                }
            } catch (failure: Throwable) {
                database.close()
                throw failure
            }
        }

        private fun bounded(payload: PrivateBytes): Boolean {
            val bytes = payload.copyForCodec()
            return try { bytes.size in 1..MAX_RECORD_BYTES } finally { bytes.fill(0) }
        }

        private fun PrivateRecord.asControlRecord() = SessionControlRecord(revision, payload)
    }
}
