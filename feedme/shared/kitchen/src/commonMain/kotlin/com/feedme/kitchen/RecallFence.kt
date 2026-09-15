package com.feedme.kitchen

import com.feedme.core.ports.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * A learned recall is one-way. This independent record cannot lose to an ordinary cache CAS.
 * The memory fence is installed first so even failed/uncertain persistence cannot expose content
 * again in this process. Durable identity/logout orchestration must handle retry/owner erasure;
 * this does not promise immediate propagation to an offline device or complete process recovery.
 * Replacing a repository does not discard this protection. Invalidating its exact lease clears
 * only process memory; a subsequent lease must still load any independently persisted marker.
 */
internal suspend fun KitchenContext.markRecall(lease: SessionLease, kind: String, id: String) {
    val key = recallKey(kind, id)
    currentCoroutineContext().ensureActive()
    current(lease)
    ProcessRecallFence.remember(lease, ownerBoundary, key)
    current(lease)
    if (validRecallRecord(read(lease, key))) return
    try {
        commit(lease, listOf(kitchenPut(key, null, PrivateBytes(byteArrayOf(1)))))
    } catch (failure: KitchenFailure) {
        // Only reread an uncertain/raced create for this identical one-way marker, never a new key
        // or a rewrite of possibly committed recipe/domain state. This is blocking recall
        // evidence only, never a successful effect receipt or permission to cook.
        if (failure.reason !in setOf(FailureReason.CONFLICT, FailureReason.OUTCOME_UNKNOWN)) throw failure
        if (!validRecallRecord(read(lease, key))) throw failure
    }
}

internal suspend fun KitchenContext.isRecalled(lease: SessionLease, kind: String, id: String): Boolean {
    val key = recallKey(kind, id)
    current(lease)
    val persisted = validRecallRecord(read(lease, key))
    if (persisted) ProcessRecallFence.remember(lease, ownerBoundary, key)
    val result = persisted || ProcessRecallFence.contains(lease, ownerBoundary, key)
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
    // Like SessionBoundary, this process registry belongs to the serialized application owner.
    // No suspension between lease validation, installation and callback registration: an invalid
    // lease cannot resurrect a fence after its synchronous invalidation has already run.
    private class Entry(val boundary: SessionBoundary, val lease: SessionLease) {
        val keys = mutableSetOf<RecordKey>()
        var subscription: SessionInvalidationSubscription? = null
    }
    private val remembered = mutableListOf<Entry>()
    fun remember(lease: SessionLease, boundary: SessionBoundary, key: RecordKey) {
        if (!boundary.isCurrent(lease)) kitchenFail(FailureReason.STALE_SESSION)
        val entry = remembered.singleOrNull { it.boundary === boundary && it.lease === lease }
            ?: Entry(boundary, lease).also { created ->
                remembered.add(created)
                created.subscription = boundary.onInvalidated(lease) {
                    created.keys.clear()
                    remembered.remove(created)
                    created.subscription = null
                }
            }
        entry.keys.add(key)
    }
    fun contains(lease: SessionLease, boundary: SessionBoundary, key: RecordKey): Boolean =
        remembered.any { it.boundary === boundary && it.lease === lease && key in it.keys }
}
