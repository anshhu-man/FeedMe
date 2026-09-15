package com.feedme.kitchen

import com.feedme.core.ports.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal val savedProvenanceFields = setOf("creatorLabel", "sourcePostId", "grantId")

/** Negative attribution evidence only; never edit canonical bodies or infer restored rights. */
internal suspend fun KitchenContext.savedRedactions(lease: SessionLease, id: String): Set<String> {
    val record = read(lease, savedRedactionKey(id))
    val fields = savedRedactionFields(record)
    if (fields.isNotEmpty()) SavedRedactionMemory.add(lease, ownerBoundary, id, fields)
    current(lease)
    return fields + SavedRedactionMemory.get(lease, ownerBoundary, id)
}

internal suspend fun KitchenContext.markSavedRedaction(lease: SessionLease, id: String, fields: Set<String>) {
    if (fields.isEmpty()) return
    if (fields.any { it !in savedProvenanceFields }) kitchenFail(FailureReason.INVALID_DATA)
    normalizedId(id)
    currentCoroutineContext().ensureActive(); current(lease)
    // Fail-closed process evidence survives failed/cancelled persistence and repository replacement.
    SavedRedactionMemory.add(lease, ownerBoundary, id, fields)
    val key = savedRedactionKey(id)
    val record = read(lease, key)
    val existing = savedRedactionFields(record)
    val union = existing + SavedRedactionMemory.get(lease, ownerBoundary, id)
    if (record != null && union == existing) return
    commit(lease, listOf(kitchenPut(key, record?.revision, PrivateJson.encode(buildJsonObject {
        put("removed", JsonArray(union.sorted().map(::JsonPrimitive)))
    }))))
}

private fun savedRedactionKey(id: String) = RecordKey("feedme.kitchen.saved.redaction", normalizedId(id))
private fun savedRedactionFields(record: PrivateRecord?): Set<String> {
    if (record == null) return emptySet()
    if (record.schemaVersion != 1) PrivateJson.invalid()
    val values = PrivateJson.strings(PrivateJson.decode(record.payload, setOf("removed")).getValue("removed"))
    if (values.isEmpty() || values.distinct().size != values.size || values.any { it !in savedProvenanceFields }) PrivateJson.invalid()
    return values.toSet()
}

/**
 * Different boundaries may have different serialized dispatchers. Only immutable membership is
 * process-global and CAS-protected; an Entry's fields/subscription are touched on its OWN boundary
 * dispatcher. No CAS loop calls a boundary, callback, I/O or suspending function.
 */
@OptIn(ExperimentalAtomicApi::class)
private object SavedRedactionMemory {
    private class Entry(val lease: SessionLease, val boundary: SessionBoundary) {
        val fields = mutableMapOf<String, Set<String>>()
        var subscription: SessionInvalidationSubscription? = null
    }
    private val entries = AtomicReference<List<Entry>>(emptyList())
    fun add(lease: SessionLease, boundary: SessionBoundary, id: String, fields: Set<String>) {
        if (!boundary.isCurrent(lease)) kitchenFail(FailureReason.STALE_SESSION)
        val entry = findOrAdd(lease, boundary)
        if (entry.subscription == null) {
            val subscription = boundary.onInvalidated(lease) {
                entry.fields.clear(); remove(entry); entry.subscription = null
            }
            // A stale lease fires synchronously while registering. Never store that returned
            // dead subscription or repopulate fields after the callback removed this exact entry.
            if (!boundary.isCurrent(lease)) {
                subscription.close(); entry.fields.clear(); remove(entry)
                kitchenFail(FailureReason.STALE_SESSION)
            }
            entry.subscription = subscription
        }
        entry.fields[id] = entry.fields[id].orEmpty() + fields
    }
    fun get(lease: SessionLease, boundary: SessionBoundary, id: String): Set<String> =
        entries.load().singleOrNull { it.lease === lease && it.boundary === boundary }?.fields?.get(id).orEmpty()

    private fun findOrAdd(lease: SessionLease, boundary: SessionBoundary): Entry {
        while (true) {
            val before = entries.load()
            before.singleOrNull { it.lease === lease && it.boundary === boundary }?.let { return it }
            val created = Entry(lease, boundary)
            if (entries.compareAndSet(before, before + created)) return created
        }
    }

    private fun remove(entry: Entry) {
        while (true) {
            val before = entries.load()
            if (before.none { it === entry }) return
            if (entries.compareAndSet(before, before.filterNot { it === entry })) return
        }
    }
}
