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
 * One encrypted install-local native-work registry record, not password or token storage.
 * Its native database and vault namespace are independent of every retiring owner. Neither the
 * underlying owner handle nor an activation/erasure/reset operation is exposed to callers.
 *
 * An interrupted first setup can leave a database without this record. That is an explicit repair
 * gate: later opens fail closed rather than treating missing cancellation evidence as idle state.
 */
class EncryptedSessionWorkStore private constructor(
    private val database: EncryptedStateDatabase,
    private val store: PrivateStateStore,
) : SessionControlStore {
    private val mutex = Mutex()

    override suspend fun read(): PortResult<SessionControlRecord?> = mutex.withLock {
        when (val result = readExisting()) {
            is PortResult.Value -> PortResult.Value(result.value.asWorkRecord())
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
            when (val result = store.commit(WORK_SCOPE, listOf(
                StoreMutation.Put(WORK_KEY, expectedRevision, SCHEMA_VERSION, payload),
            ))) {
                is PortResult.Failure -> result
                is PortResult.Value -> {
                    val revision = result.value[WORK_KEY]
                    if (revision == null || revision <= current.revision) PortResult.Failure(FailureReason.STORAGE_FAILURE)
                    else PortResult.Value(SessionControlRecord(revision, payload))
                }
            }
        }
    }

    suspend fun close(): PortResult<Unit> = withContext(NonCancellable) {
        mutex.withLock { database.close() }
    }

    override fun toString(): String = "EncryptedSessionWorkStore(<private-work>)"

    private suspend fun readExisting(): PortResult<PrivateRecord> = when (val result = store.read(WORK_SCOPE, WORK_KEY)) {
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
        private val WORK_SCOPE = StorageScope("feedme-session-work-v1", ActorKind.DEMO, "install-work")
        private val WORK_KEY = RecordKey("session-work", "native-work-ledger")
        private val INITIAL_PAYLOAD = PrivateBytes("{\"version\":1,\"state\":\"idle\"}".encodeToByteArray())

        /**
         * Takes exclusive database ownership even on failure. [allowInitialize] is true only when
         * the native factory itself successfully created the previously absent directory and database
         * file in this open, never after a read returned null or because recovery appeared necessary.
         * It is not an application reset API. The bytes are opaque here; the work coordinator owns
         * its strict schema and validates the record before using it as cancellation authority.
         */
        internal suspend fun open(
            database: EncryptedStateDatabase,
            allowInitialize: Boolean,
        ): PortResult<EncryptedSessionWorkStore> {
            try {
                val owned = if (allowInitialize) database.activate(WORK_SCOPE) else database.resume(WORK_SCOPE)
                val store = when (owned) {
                    is PortResult.Failure -> { database.close(); return owned }
                    is PortResult.Value -> owned.value ?: run {
                        database.close()
                        return PortResult.Failure(FailureReason.STORAGE_FAILURE)
                    }
                }
                if (allowInitialize) {
                    when (val initialized = store.commit(WORK_SCOPE, listOf(
                        StoreMutation.Put(WORK_KEY, null, SCHEMA_VERSION, INITIAL_PAYLOAD),
                    ))) {
                        is PortResult.Failure -> { database.close(); return initialized }
                        is PortResult.Value -> Unit
                    }
                }
                val wrapper = EncryptedSessionWorkStore(database, store)
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

        private fun PrivateRecord.asWorkRecord() = SessionControlRecord(revision, payload)
    }
}
