package com.feedme.kitchen

import com.feedme.contracts.SavedRecipeWire
import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.*

/** Availability of an owned local copy, not professional review or an offline authorization lease. */
enum class SavedRecipeAvailability { AVAILABLE, RECALLED, UNAVAILABLE, INTEGRITY_FAILURE }

class SavedRecipeSnapshot internal constructor(
    val id: String,
    val savedRecipe: SavedRecipeWire?,
    val availability: SavedRecipeAvailability,
    val lastCheckedMillis: Long,
    val localRevision: Long?,
    val etag: String?,
) {
    override fun toString() = "SavedRecipeSnapshot(content=<redacted>, availability=$availability, metadata=<redacted>)"
}

/**
 * Owner-scoped read/download slice. A previously granted private copy is its own authorization
 * root: this repository neither queries the old post grant nor gates basic access on a purchase.
 * The digest verifies exact locally stored bytes, NOT a server manifest or reviewed recipe proof.
 * No new save, deletion, remote enumeration, source-media access or background sync is performed.
 */
class SavedRecipeRepository(
    scope: StorageScope,
    store: PrivateStateStore,
    boundary: SessionBoundary,
    dispatcher: CoroutineDispatcher,
    clock: EpochClock,
    transport: AccountTransport,
) {
    private val context = KitchenContext(scope, store, boundary, dispatcher, clock, transport)

    suspend fun download(lease: SessionLease, savedRecipeId: String): PortResult<SavedRecipeSnapshot> = context.guarded(lease) {
        val id = normalizedId(savedRecipeId)
        // All expected revisions are captured before network suspension, including the index.
        val baseline = load(lease, id)
        if (baseline.broken) kitchenFail(FailureReason.INVALID_DATA)
        val reply = try { context.fetch(lease, "getSavedRecipe", mapOf("savedRecipeId" to id)) }
        catch (failure: KitchenFailure) {
            if (failure.problemCode == "RECIPE_RECALLED") {
                try {
                    val pinned = baseline.document?.let { SavedRecipeWire.from(it).snapshot.id.value }
                    if (pinned == null) context.markRecall(lease, "saved", id)
                    else markRecall(lease, id, normalizedId(pinned))
                } catch (markerFailure: KitchenFailure) {
                    // Markers install their process fence first. Preserve the authoritative domain
                    // outcome if persistence failed, but never hide an owner retirement.
                    if (markerFailure.reason == FailureReason.STALE_SESSION) throw markerFailure
                }
            }
            throw failure
        }
        val bytes = reply.body ?: kitchenFail(FailureReason.INVALID_DATA)
        val document = context.document("SavedRecipe", bytes)
        val incoming = SavedRecipeWire.from(document)
        if (normalizedId(incoming.id.value) != id) kitchenFail(FailureReason.INVALID_DATA)
        val recalled = incoming.recalled || incoming.snapshot.reviewStatus == "recalled"
        if (recalled) {
            // Independent monotonic fences survive body/index CAS failure. An invalid replacement
            // cannot hide a same-save recall, or use it to invalidate an unrelated recipe version.
            val pinned = baseline.document?.let { SavedRecipeWire.from(it).snapshot.id.value }
                ?: incoming.snapshot.id.value
            markRecall(lease, id, normalizedId(pinned))
        }
        val version = documentVersion(document)
        val etag = checkedEtag(document, reply.etag)
        val old = baseline.metadata
        val removed = old?.removedFields.orEmpty().toMutableSet()
        val conflict = old != null && !compatible(baseline.document!!, document, old, removed)
        if (conflict) kitchenFail(FailureReason.CONFLICT)
        val metadata = Metadata(id, privateDigest(bytes), version, etag, maxOf(old?.lastCheckedMillis ?: 0, context.now()),
            recalled || old?.recalled == true, incoming.title, removed.toSet())
        val revisions = write(lease, baseline, bytes, metadata)
        view(lease, id, metadata, revisions[metadataKey(id)], document)
    }

    suspend fun read(lease: SessionLease, id: String): PortResult<SavedRecipeSnapshot?> = context.guarded(lease) {
        val normalized = normalizedId(id)
        val loaded = load(lease, normalized)
        if (!loaded.exists) {
            if (context.isRecalled(lease, "saved", normalized))
                SavedRecipeSnapshot(normalized, null, SavedRecipeAvailability.RECALLED, 0, null, null)
            else null
        } else loaded.view(lease, normalized)
    }

    /** Bounded local-title search. Query contents are never sent to transport or diagnostics. */
    suspend fun search(lease: SessionLease, query: String = "", limit: Int = 50): PortResult<List<SavedRecipeSnapshot>> = context.guarded(lease) {
        if (limit !in 1..MAX_ENTRIES || !validQuery(query)) kitchenFail(FailureReason.INVALID_DATA)
        val index = readIndex(lease)
        val needle = query.trim().lowercase()
        val results = mutableListOf<SavedRecipeSnapshot>()
        for (id in index.ids) {
            val record = context.read(lease, metadataKey(id))
            val metadata = decodeMetadataOrNull(record, id)
            if (metadata == null) {
                results += corrupt(lease, id, record?.revision)
            } else {
                val body = context.read(lease, bodyKey(id))
                val document = verifiedDocumentOrNull(body, metadata)
                if (document == null) results += corrupt(lease, id, record?.revision, metadata)
                else if (needle.isEmpty() || metadata.title.lowercase().contains(needle))
                    results += view(lease, id, metadata, record?.revision, document)
            }
            if (results.size == limit) break
        }
        // A changed index means one of the atomic documents changed while it was being read.
        checkIndex(lease, index)
        results.toList()
    }

    private suspend fun load(lease: SessionLease, id: String): Loaded {
        val index = readIndex(lease)
        val metadataRecord = context.read(lease, metadataKey(id))
        val bodyRecord = context.read(lease, bodyKey(id))
        val exists = id in index.ids || metadataRecord != null || bodyRecord != null
        val metadata = decodeMetadataOrNull(metadataRecord, id)
        val document = metadata?.let { verifiedDocumentOrNull(bodyRecord, it) }
        checkIndex(lease, index)
        return Loaded(index, metadataRecord, bodyRecord, metadata, document, exists,
            exists && (id !in index.ids || metadata == null || document == null))
    }

    private suspend fun readIndex(lease: SessionLease): Index {
        val record = context.read(lease, INDEX) ?: return Index(null, emptyList())
        if (record.schemaVersion != 1) PrivateJson.invalid()
        val values = PrivateJson.decode(record.payload, setOf("ids"))
        val ids = PrivateJson.strings(values.getValue("ids"))
        if (ids.size > MAX_ENTRIES || ids.toSet().size != ids.size || ids.any { normalizedId(it) != it }) PrivateJson.invalid()
        return Index(record, ids)
    }

    private suspend fun checkIndex(lease: SessionLease, index: Index) {
        if (context.read(lease, INDEX)?.revision != index.record?.revision) kitchenFail(FailureReason.CONFLICT)
    }

    private suspend fun write(lease: SessionLease, baseline: Loaded, body: PrivateBytes, metadata: Metadata): Map<RecordKey, Long?> {
        val ids = if (metadata.id in baseline.index.ids) baseline.index.ids else baseline.index.ids + metadata.id
        if (ids.size > MAX_ENTRIES) kitchenFail(FailureReason.INVALID_DATA)
        return context.commit(lease, listOf(
            kitchenPut(bodyKey(metadata.id), baseline.bodyRecord?.revision, body),
            kitchenPut(metadataKey(metadata.id), baseline.metadataRecord?.revision, encodeMetadata(metadata)),
            kitchenPut(INDEX, baseline.index.record?.revision, PrivateJson.encode(buildJsonObject {
                put("ids", JsonArray(ids.map(::JsonPrimitive)))
            })),
        ))
    }

    private suspend fun markRecall(lease: SessionLease, id: String, versionId: String) {
        var firstFailure: KitchenFailure? = null
        // Install both fail-closed memory fences even if the first durable marker write fails.
        // Cancellation is intentionally not caught; each helper also independently fences owner.
        try { context.markRecall(lease, "version", versionId) }
        catch (failure: KitchenFailure) { firstFailure = failure }
        try { context.markRecall(lease, "saved", id) }
        catch (failure: KitchenFailure) { if (firstFailure == null) firstFailure = failure }
        firstFailure?.let { throw it }
    }

    private fun compatible(old: WireDocument, incoming: WireDocument, metadata: Metadata, removed: MutableSet<String>): Boolean {
        val order = compareVersions(documentVersion(incoming), metadata.version)
        if (order < 0) return false
        val before = old.json().jsonObject
        val after = incoming.json().jsonObject
        // Reordered JSON/whitespace is harmless; same aggregate version cannot change any field.
        if (order == 0) return before == after
        if (JsonObject(before - OUTER_MUTABLE) != JsonObject(after - OUTER_MUTABLE)) return false
        val oldRecipe = SavedRecipeWire.from(old).snapshot.document
        val newRecipe = SavedRecipeWire.from(incoming).snapshot.document
        if (immutableRecipe(oldRecipe) != immutableRecipe(newRecipe)) return false
        val recipeOrder = compareVersions(documentVersion(newRecipe), documentVersion(oldRecipe))
        if (recipeOrder < 0 || (recipeOrder == 0 && oldRecipe.json() != newRecipe.json())) return false
        // Only removal is a provable redaction in this DTO: arbitrary labels or reintroduced
        // source identities are not an explicit restoration authorization, even at higher version.
        for (field in PROVENANCE_FIELDS) {
            if (field in metadata.removedFields && field in after) return false
            if (field in after && before[field] != after[field]) return false
            if (field in before && field !in after) removed += field
        }
        return true
    }

    private fun verifiedDocumentOrNull(record: PrivateRecord?, metadata: Metadata): WireDocument? = try {
        val document = context.document("SavedRecipe", readVerified(record, metadata.digest))
        val saved = SavedRecipeWire.from(document)
        if (normalizedId(saved.id.value) != metadata.id || documentVersion(document) != metadata.version || saved.title != metadata.title)
            PrivateJson.invalid()
        checkedEtag(document, metadata.etag)
        if ((saved.recalled || saved.snapshot.reviewStatus == "recalled") && !metadata.recalled) PrivateJson.invalid()
        if (metadata.removedFields.any { it in document.json().jsonObject }) PrivateJson.invalid()
        document
    } catch (_: KitchenFailure) { null }

    private fun decodeMetadataOrNull(record: PrivateRecord?, id: String): Metadata? = try {
        if (record == null || record.schemaVersion != 1) PrivateJson.invalid()
        val fields = PrivateJson.decode(record.payload, METADATA_FIELDS)
        val storedId = PrivateJson.uuid(fields.getValue("id"))
        if (storedId != id) PrivateJson.invalid()
        val version = PrivateJson.string(fields.getValue("version"))
        if (version.length !in 1..4096 || !version.matches(Regex("[1-9][0-9]*"))) PrivateJson.invalid()
        val removed = PrivateJson.strings(fields.getValue("removedFields"))
        if (removed.toSet().size != removed.size || removed.any { it !in PROVENANCE_FIELDS }) PrivateJson.invalid()
        Metadata(storedId, PrivateJson.hash(fields.getValue("digest")), version,
            PrivateJson.nullableString(fields.getValue("etag")), PrivateJson.long(fields.getValue("lastCheckedMillis")),
            PrivateJson.boolean(fields.getValue("recalled")), PrivateJson.string(fields.getValue("title")), removed.toSet())
    } catch (_: KitchenFailure) { null }

    private fun encodeMetadata(metadata: Metadata): PrivateBytes = PrivateJson.encode(buildJsonObject {
        put("id", metadata.id); put("digest", metadata.digest); put("version", metadata.version)
        put("etag", metadata.etag?.let(::JsonPrimitive) ?: JsonNull)
        put("lastCheckedMillis", metadata.lastCheckedMillis); put("recalled", metadata.recalled); put("title", metadata.title)
        put("removedFields", JsonArray(metadata.removedFields.sorted().map(::JsonPrimitive)))
    })

    private suspend fun view(lease: SessionLease, id: String, metadata: Metadata, revision: Long?, document: WireDocument): SavedRecipeSnapshot {
        val saved = SavedRecipeWire.from(document)
        val recalled = metadata.recalled || context.isRecalled(lease, "saved", id) ||
            context.isRecalled(lease, "version", normalizedId(saved.snapshot.id.value))
        return SavedRecipeSnapshot(id, if (recalled) null else saved,
            if (recalled) SavedRecipeAvailability.RECALLED else SavedRecipeAvailability.AVAILABLE,
            metadata.lastCheckedMillis, revision, metadata.etag)
    }

    private suspend fun corrupt(lease: SessionLease, id: String, revision: Long?, metadata: Metadata? = null): SavedRecipeSnapshot = SavedRecipeSnapshot(
        id, null, if (metadata?.recalled == true || context.isRecalled(lease, "saved", id)) SavedRecipeAvailability.RECALLED else SavedRecipeAvailability.INTEGRITY_FAILURE,
        metadata?.lastCheckedMillis ?: 0, revision, null,
    )

    private suspend fun Loaded.view(lease: SessionLease, id: String): SavedRecipeSnapshot = if (broken) corrupt(lease, id, metadataRecord?.revision, metadata)
        else view(lease, id, metadata!!, metadataRecord?.revision, document!!)

    private fun validQuery(query: String): Boolean {
        var count = 0
        var offset = 0
        while (offset < query.length) {
            val char = query[offset++]
            if (char.isISOControl() || char.isLowSurrogate()) return false
            if (char.isHighSurrogate()) {
                if (offset == query.length || !query[offset].isLowSurrogate()) return false
                offset++
            }
            if (++count > 100) return false
        }
        return true
    }

    private class Index(val record: PrivateRecord?, val ids: List<String>)
    private class Loaded(val index: Index, val metadataRecord: PrivateRecord?, val bodyRecord: PrivateRecord?,
        val metadata: Metadata?, val document: WireDocument?, val exists: Boolean, val broken: Boolean)
    private data class Metadata(val id: String, val digest: String, val version: String, val etag: String?,
        val lastCheckedMillis: Long, val recalled: Boolean, val title: String, val removedFields: Set<String>) {
        override fun toString() = "SavedRecipeMetadata(<redacted>)"
    }

    private companion object {
        const val MAX_ENTRIES = 512
        val INDEX = RecordKey("feedme.kitchen.saved.index", "v1")
        val PROVENANCE_FIELDS = setOf("creatorLabel", "sourcePostId", "grantId")
        val OUTER_MUTABLE = setOf("snapshot", "version", "updatedAt", "recalled") + PROVENANCE_FIELDS
        val METADATA_FIELDS = setOf("id", "digest", "version", "etag", "lastCheckedMillis", "recalled", "title", "removedFields")
        fun metadataKey(id: String) = RecordKey("feedme.kitchen.saved.metadata", id)
        fun bodyKey(id: String) = RecordKey("feedme.kitchen.saved.body", id)
    }
}
