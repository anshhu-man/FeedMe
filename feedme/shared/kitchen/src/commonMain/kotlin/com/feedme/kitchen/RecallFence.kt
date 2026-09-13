package com.feedme.kitchen

import com.feedme.core.ports.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A learned recall is one-way. This independent record cannot lose to an ordinary cache CAS.
 * The memory fence is installed first so even failed/uncertain persistence cannot expose content
 * again in this process. Durable identity/logout orchestration must handle retry/owner erasure;
 * this does not promise immediate propagation to an offline device or complete process recovery.
 */
internal suspend fun KitchenContext.markRecall(lease: SessionLease, kind: String, id: String) {
    val key = recallKey(kind, id)
    current(lease)
    ProcessRecallFence.remember(scope, ownerBoundary, key)
    current(lease)
    if (validRecallRecord(read(lease, key))) return
    try {
        commit(lease, listOf(kitchenPut(key, null, PrivateBytes(byteArrayOf(1)))))
    } catch (failure: KitchenFailure) {
        // Only reread an uncertain/raced create for this identical one-way marker, never a new key
        // or a rewrite of possibly committed recipe/domain state.
        if (failure.reason !in setOf(FailureReason.CONFLICT, FailureReason.OUTCOME_UNKNOWN)) throw failure
        if (!validRecallRecord(read(lease, key))) throw failure
    }
}

internal suspend fun KitchenContext.isRecalled(lease: SessionLease, kind: String, id: String): Boolean {
    val key = recallKey(kind, id)
    current(lease)
    val persisted = validRecallRecord(read(lease, key))
    if (persisted) ProcessRecallFence.remember(scope, ownerBoundary, key)
    val result = persisted || ProcessRecallFence.contains(scope, ownerBoundary, key)
    current(lease)
    return result
}

private fun recallKey(kind: String, id: String): RecordKey {
    if (kind !in setOf("saved", "version")) kitchenFail(FailureReason.INVALID_DATA)
    return RecordKey("feedme.kitchen.recall", "$kind:${normalizedId(id)}")
}

private fun validRecallRecord(record: PrivateRecord?): Boolean {
    if (record == null) return false
    if (record.schemaVersion != 1 || !record.payload.copyForCodec().contentEquals(byteArrayOf(1))) kitchenFail(FailureReason.INVALID_DATA)
    return true
}

private object ProcessRecallFence {
    private val mutex = Mutex()
    private data class Key(val scope: StorageScope, val boundary: SessionBoundary, val key: RecordKey)
    private val remembered = mutableSetOf<Key>()
    suspend fun remember(scope: StorageScope, boundary: SessionBoundary, key: RecordKey) { mutex.withLock { remembered.add(Key(scope, boundary, key)); Unit } }
    suspend fun contains(scope: StorageScope, boundary: SessionBoundary, key: RecordKey): Boolean = mutex.withLock { Key(scope, boundary, key) in remembered }
}
