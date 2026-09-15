package com.feedme.kitchen

import com.feedme.contracts.*
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
 * Explicit remote reads do not download. Save/delete preparations expose only fixed mutations
 * for the existing queue; this repository never dispatches those commands or grants copy rights.
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

    /**
     * Read-only, removal-only target from an intact downloaded bundle. Recall and attribution
     * redaction still hide its body, but do not grant content access or prevent owned removal.
     * Missing/corrupt/uncached data is not a target and never triggers a content GET fallback.
     */
    suspend fun prepareLocalDeletion(lease: SessionLease, id: String): PortResult<SavedRecipeDeletionTarget> = context.guarded(lease) {
        val normalized = normalizedId(id)
        val baseline = localDeletionBaseline(lease, normalized)
        val evidence = localDeletionEvidence(normalized, baseline)
        checkLocalDeletionRecords(lease, normalized, baseline)
        SavedRecipeDeletionTarget(this, lease, evidence)
    }

    /** Live confirmation must still belong to this exact repository and lease. No mutation. */
    suspend fun validateLocalDeletion(lease: SessionLease, target: SavedRecipeDeletionTarget): PortResult<Unit> = context.guarded(lease) {
        if (target.repository !== this || target.lease !== lease) kitchenFail(FailureReason.STALE_SESSION)
        validatedLocalDeletion(lease, target.evidence)
        Unit
    }

    /** Stored evidence supports only an already admitted original command, never new consent. */
    suspend fun validateLocalDeletion(lease: SessionLease, evidence: SavedRecipeDeletionEvidence): PortResult<Unit> = context.guarded(lease) {
        validatedLocalDeletion(lease, evidence)
        Unit
    }

    /**
     * Prepare the fixed atomic deletion batch for an exact original local-evidence DELETE 204.
     * Already deleted/unknown-ACK state is refused; only the caller's retained actual batch and
     * archive proof can finish an unknown apply. No content fetch, dispatch or commit occurs here.
     */
    suspend fun prepareDeleteReceipt(lease: SessionLease, commandId: String, evidence: SavedRecipeDeletionEvidence,
        reply: ApiReply): PortResult<SavedRecipePreparedMutation> = context.guarded(lease) {
        val command = normalizedId(commandId)
        boundReply("deleteSavedRecipe", reply)
        if (reply.status != 204 || reply.body != null || reply.etag != null) kitchenFail(FailureReason.INVALID_DATA)
        val baseline = validatedLocalDeletion(lease, evidence)
        val marker = PrivateJson.encode(buildJsonObject {
            put("id", evidence.id); put("commandId", command); put("version", evidence.version)
            put("recipeVersionId", evidence.recipeVersionId); put("digest", evidence.bodyDigest)
        })
        SavedRecipePreparedMutation(listOf(
            StoreMutation.Delete(bodyKey(evidence.id), baseline.bodyRecord!!.revision),
            StoreMutation.Delete(metadataKey(evidence.id), baseline.metadataRecord!!.revision),
            indexMutation(baseline.index, baseline.index.ids - evidence.id),
            kitchenPut(tombstoneKey(evidence.id), null, marker),
        ), evidence.id)
    }

    /** Bound negative-only reply observation; recalled data stays redacted and no ACK is made. */
    suspend fun observeDeleteReply(lease: SessionLease, evidence: SavedRecipeDeletionEvidence,
        reply: ApiReply): PortResult<Unit> = context.guarded(lease) {
        validatedLocalDeletion(lease, evidence)
        if (recallProblem("deleteSavedRecipe", reply)) markRecall(lease, evidence.id, evidence.recipeVersionId)
    }

    private fun deletionScopeDigest() = privateDigest(PrivateJson.encode(buildJsonObject {
        put("environment", context.scope.environment); put("actorKind", context.scope.actorKind.name)
        put("actorId", context.scope.actorId)
    }))

    private suspend fun localDeletionBaseline(lease: SessionLease, id: String): Loaded {
        val baseline = load(lease, id)
        rejectDeletedOrBroken(baseline)
        if (!baseline.exists) kitchenFail(FailureReason.NOT_FOUND)
        val document = baseline.document ?: kitchenFail(FailureReason.INVALID_DATA)
        checkedEtag(document, baseline.metadata?.etag) ?: kitchenFail(FailureReason.INVALID_DATA)
        if (baseline.index.record == null || baseline.bodyRecord == null || baseline.metadataRecord == null ||
            baseline.index.record.revision <= 0 || baseline.bodyRecord.revision <= 0 || baseline.metadataRecord.revision <= 0)
            kitchenFail(FailureReason.INVALID_DATA)
        return baseline
    }

    private fun localDeletionEvidence(id: String, baseline: Loaded) = SavedRecipeDeletionEvidence(
        deletionScopeDigest(), id, baseline.metadata!!.etag!!, baseline.metadata.version,
        normalizedId(SavedRecipeWire.from(baseline.document!!).snapshot.id.value),
        baseline.bodyRecord!!.revision, privateDigest(baseline.bodyRecord.payload),
        baseline.metadataRecord!!.revision, privateDigest(baseline.metadataRecord.payload),
        baseline.index.record!!.revision, privateDigest(baseline.index.record.payload),
    )

    private suspend fun validatedLocalDeletion(lease: SessionLease, evidence: SavedRecipeDeletionEvidence): Loaded {
        // Internal construction remains checked too; a structurally decoded tuple is never an
        // alternate route around canonical limits, scope or the actual retained local records.
        kitchenValue(SavedRecipeDeletionEvidence.decode(evidence.encode()))
        if (evidence.scopeDigest != deletionScopeDigest()) kitchenFail(FailureReason.STALE_SESSION)
        val baseline = localDeletionBaseline(lease, evidence.id)
        if (!evidence.encode().copyForCodec().contentEquals(localDeletionEvidence(evidence.id, baseline).encode().copyForCodec()))
            kitchenFail(FailureReason.CONFLICT)
        checkLocalDeletionRecords(lease, evidence.id, baseline)
        return baseline
    }

    private suspend fun checkLocalDeletionRecords(lease: SessionLease, id: String, baseline: Loaded) {
        fun same(expected: PrivateRecord?, current: PrivateRecord?) {
            if (expected?.revision != current?.revision || expected?.schemaVersion != current?.schemaVersion ||
                (expected != null && current != null && !expected.payload.copyForCodec().contentEquals(current.payload.copyForCodec())))
                kitchenFail(FailureReason.CONFLICT)
        }
        same(baseline.bodyRecord, context.read(lease, bodyKey(id)))
        same(baseline.metadataRecord, context.read(lease, metadataKey(id)))
        same(baseline.tombstone, readTombstone(lease, id))
        checkIndex(lease, baseline.index)
    }

    /** Reserve one bounded local bundle before enqueue/transport, atomically WITH the intent. */
    suspend fun prepareSave(lease: SessionLease, commandId: String, plan: PlanWire,
        request: SaveRecipeRequest): PortResult<SavedRecipePreparedMutation> = context.guarded(lease) {
        val reservation = saveReservation(commandId, plan, request)
        requireNotRecalled(lease, plan)
        val index = readIndex(lease)
        val reserved = readReservations(lease)
        if (reserved.items.any { it.commandId == reservation.commandId }) kitchenFail(FailureReason.CONFLICT)
        if (index.ids.size + reserved.items.size >= MAX_LOCAL_ENTRIES || reserved.items.size >= MAX_RESERVATIONS)
            kitchenFail(FailureReason.UNAVAILABLE)
        checkIndex(lease, index)
        SavedRecipePreparedMutation(listOf(indexMutation(index, index.ids),
            reservationMutation(reserved, reserved.items + reservation)))
    }

    /** Only use with the queue's never-attempted discard; preparation itself releases nothing. */
    suspend fun prepareDiscardSave(lease: SessionLease, commandId: String, plan: PlanWire,
        request: SaveRecipeRequest): PortResult<SavedRecipePreparedMutation> = context.guarded(lease) {
        val expected = saveReservation(commandId, plan, request)
        val reserved = readReservations(lease)
        requireReservation(reserved, expected)
        val index = readIndex(lease)
        SavedRecipePreparedMutation(listOf(indexMutation(index, index.ids),
            reservationMutation(reserved, reserved.items.filterNot { it.commandId == expected.commandId })))
    }

    /** Exact retained 201 supplies the complete bundle; never issue a second mandatory GET. */
    suspend fun prepareSaveReceipt(lease: SessionLease, commandId: String, plan: PlanWire,
        request: SaveRecipeRequest, reply: ApiReply): PortResult<SavedRecipePreparedMutation> = context.guarded(lease) {
        val expected = saveReservation(commandId, plan, request)
        boundReply("saveRecipe", reply)
        if (reply.status != 201) kitchenFail(FailureReason.INVALID_DATA)
        val bytes = boundedBody(reply)
        val document = context.document("SavedRecipe", bytes)
        val incoming = SavedRecipeWire.from(document)
        val id = normalizedId(incoming.id.value)
        val baseline = load(lease, id)
        rejectDeletedOrBroken(baseline)
        // Positive receipt correlation cannot turn mismatched content into a recall of another
        // version. Bound negative Problem observations use the exact original Plan separately.
        val recipe = planRecipe(plan)
        if (incoming.snapshot.document.json() != recipe.document.json() ||
            incoming.sourceType !in setOf("ownPlan", "catalog") ||
            request.title?.let { it != incoming.title } == true) kitchenFail(FailureReason.CONFLICT)
        val metadata = incomingMetadata(lease, baseline, document, bytes, reply.etag, requireEtag = true)
        requireNotRecalled(lease, plan)
        if (metadata.recalled || context.isRecalled(lease, "saved", id)) kitchenFail(FailureReason.FORBIDDEN)
        val reserved = readReservations(lease)
        requireReservation(reserved, expected)
        val remaining = reserved.items.filterNot { it.commandId == expected.commandId }
        val ids = (baseline.index.ids + id).distinct()
        if (ids.size + remaining.size > MAX_LOCAL_ENTRIES) kitchenFail(FailureReason.UNAVAILABLE)
        checkIndex(lease, baseline.index)
        val snapshot = view(lease, id, metadata, null, document)
        if (snapshot.availability != SavedRecipeAvailability.AVAILABLE) kitchenFail(FailureReason.FORBIDDEN)
        SavedRecipePreparedMutation(bundleMutations(baseline, bytes, metadata, ids) +
            reservationMutation(reserved, remaining), id, snapshot)
    }

    /**
     * The caller supplies its ORIGINAL confirmed snapshot/ETag and exact successful DELETE
     * receipt. A 404/page omission is not deletion. Uncached remote copies need no cache slot.
     */
    suspend fun prepareDeleteReceipt(lease: SessionLease, commandId: String, expected: SavedRecipeWire,
        expectedEtag: String, reply: ApiReply): PortResult<SavedRecipePreparedMutation> = context.guarded(lease) {
        val command = normalizedId(commandId)
        val document = context.document("SavedRecipe", PrivateBytes(expected.document.encodeUtf8()))
        val id = normalizedId(expected.id.value)
        checkedEtag(document, expectedEtag) ?: kitchenFail(FailureReason.INVALID_DATA)
        boundReply("deleteSavedRecipe", reply)
        if (reply.status != 204 || reply.body != null || reply.etag != null) kitchenFail(FailureReason.INVALID_DATA)
        val baseline = load(lease, id)
        rejectDeletedOrBroken(baseline)
        // An intervening download of another version must not silently rebase confirmed intent.
        if (baseline.document != null && baseline.document.json() != document.json()) kitchenFail(FailureReason.CONFLICT)
        val marker = PrivateJson.encode(buildJsonObject {
            put("id", id); put("commandId", command); put("version", documentVersion(document))
            put("recipeVersionId", normalizedId(expected.snapshot.id.value))
            put("digest", privateDigest(PrivateBytes(document.encodeUtf8())))
        })
        val changes = listOfNotNull(
            baseline.bodyRecord?.let { StoreMutation.Delete(bodyKey(id), it.revision) },
            baseline.metadataRecord?.let { StoreMutation.Delete(metadataKey(id), it.revision) },
        ) + listOf(indexMutation(baseline.index, baseline.index.ids - id), kitchenPut(tombstoneKey(id), null, marker))
        SavedRecipePreparedMutation(changes, id)
    }

    /** Bound negative evidence only; a successful reply here is NOT a Save acknowledgement. */
    suspend fun observeSaveReply(lease: SessionLease, plan: PlanWire, reply: ApiReply): PortResult<Unit> = context.guarded(lease) {
        val recipe = planRecipe(plan)
        if (recallProblem("saveRecipe", reply)) context.markRecall(lease, "version", normalizedId(recipe.id.value))
        else if (reply.status == 201) {
            val incoming = SavedRecipeWire.from(context.document("SavedRecipe", boundedBody(reply)))
            if (incoming.recalled || incoming.snapshot.reviewStatus == "recalled") {
                if (immutableRecipe(incoming.snapshot.document) != immutableRecipe(recipe.document)) kitchenFail(FailureReason.CONFLICT)
                markRecall(lease, normalizedId(incoming.id.value), normalizedId(recipe.id.value))
            }
        }
    }

    suspend fun observeDeleteReply(lease: SessionLease, expected: SavedRecipeWire, reply: ApiReply): PortResult<Unit> = context.guarded(lease) {
        context.document("SavedRecipe", PrivateBytes(expected.document.encodeUtf8()))
        if (recallProblem("deleteSavedRecipe", reply)) markRecall(lease, normalizedId(expected.id.value), normalizedId(expected.snapshot.id.value))
    }

    /** Explicit GET without caching: still available when all 512 local slots are occupied. */
    suspend fun getRemote(lease: SessionLease, savedRecipeId: String): PortResult<SavedRecipeSnapshot> = context.guarded(lease) {
        val id = normalizedId(savedRecipeId)
        val baseline = load(lease, id)
        rejectDeletedOrBroken(baseline)
        val reply = try { context.fetch(lease, "getSavedRecipe", mapOf("savedRecipeId" to id)) }
        catch (failure: KitchenFailure) {
            if (failure.problemCode == "RECIPE_RECALLED") {
                val version = baseline.document?.let { SavedRecipeWire.from(it).snapshot.id.value }
                if (version == null) context.markRecall(lease, "saved", id) else markRecall(lease, id, version)
            }
            throw failure
        }
        val bytes = boundedBody(reply)
        val document = context.document("SavedRecipe", bytes)
        if (normalizedId(SavedRecipeWire.from(document).id.value) != id) kitchenFail(FailureReason.INVALID_DATA)
        val metadata = incomingMetadata(lease, baseline, document, bytes, reply.etag, requireEtag = true)
        val result = view(lease, id, metadata, null, document)
        checkIndex(lease, baseline.index)
        result
    }

    /** Absent q differs from q="". Caller owns traversal/query generations and cross-page merge. */
    suspend fun listRemote(lease: SessionLease, q: String? = null, cursor: String? = null,
        limit: Int = 50): PortResult<SavedRecipePageObservation> = context.guarded(lease) {
        if (q?.let { !validQuery(it) } == true || limit !in 1..50 ||
            cursor?.let { it.isEmpty() || !validQuery(it, 2048) } == true) kitchenFail(FailureReason.INVALID_DATA)
        val index = readIndex(lease)
        val query = buildMap {
            q?.let { put("q", listOf(it)) }; cursor?.let { put("cursor", listOf(it)) }; put("limit", listOf(limit.toString()))
        }
        val reply = context.fetch(lease, "listSavedRecipes", emptyMap(), query)
        val document = context.document("SavedRecipePage", boundedBody(reply)).json().jsonObject
        val next = document.getValue("nextCursor").let { if (it == JsonNull) null else it.jsonPrimitive.content }
        if (next?.let { it.isEmpty() || !validQuery(it, 2048) || it == cursor } == true) kitchenFail(FailureReason.INVALID_DATA)
        val items = document.getValue("items").jsonArray
        if (items.size > limit) kitchenFail(FailureReason.INVALID_DATA)
        val ids = mutableSetOf<String>()
        val snapshots = items.map { element ->
            val bytes = PrivateBytes(element.toString().encodeToByteArray())
            val savedDocument = context.document("SavedRecipe", bytes)
            val id = normalizedId(SavedRecipeWire.from(savedDocument).id.value)
            if (!ids.add(id)) kitchenFail(FailureReason.INVALID_DATA)
            val baseline = load(lease, id)
            rejectDeletedOrBroken(baseline)
            val metadata = incomingMetadata(lease, baseline, savedDocument, bytes, null, requireEtag = false)
            view(lease, id, metadata, null, savedDocument)
        }
        checkIndex(lease, index)
        SavedRecipePageObservation(q, cursor, limit, snapshots, next, document.getValue("serverTime").jsonPrimitive.content)
    }

    suspend fun download(lease: SessionLease, savedRecipeId: String): PortResult<SavedRecipeSnapshot> = context.guarded(lease) {
        val id = normalizedId(savedRecipeId)
        // All expected revisions are captured before network suspension, including the index.
        val baseline = load(lease, id)
        rejectDeletedOrBroken(baseline)
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
        val bytes = boundedBody(reply)
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
        removed += context.savedRedactions(lease, id)
        if (removed.any { it in document.json().jsonObject }) kitchenFail(FailureReason.CONFLICT)
        context.markSavedRedaction(lease, id, removed)
        val metadata = Metadata(id, privateDigest(bytes), version, etag, maxOf(old?.lastCheckedMillis ?: 0, context.now()),
            recalled || old?.recalled == true, incoming.title, removed.toSet())
        val revisions = write(lease, baseline, bytes, metadata)
        view(lease, id, metadata, revisions[metadataKey(id)], document)
    }

    suspend fun read(lease: SessionLease, id: String): PortResult<SavedRecipeSnapshot?> = context.guarded(lease) {
        val normalized = normalizedId(id)
        val loaded = load(lease, normalized)
        if (loaded.tombstone != null) return@guarded null
        if (!loaded.exists) {
            if (context.isRecalled(lease, "saved", normalized))
                SavedRecipeSnapshot(normalized, null, SavedRecipeAvailability.RECALLED, 0, null, null)
            else null
        } else loaded.view(lease, normalized)
    }

    /** Bounded local-title search. Query contents are never sent to transport or diagnostics. */
    suspend fun search(lease: SessionLease, query: String = "", limit: Int = 50): PortResult<List<SavedRecipeSnapshot>> = context.guarded(lease) {
        if (limit !in 1..MAX_LOCAL_ENTRIES || !validQuery(query)) kitchenFail(FailureReason.INVALID_DATA)
        val index = readIndex(lease)
        val needle = query.trim().lowercase()
        val results = mutableListOf<SavedRecipeSnapshot>()
        for (id in index.ids) {
            if (readTombstone(lease, id) != null) kitchenFail(FailureReason.INVALID_DATA)
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
        val tombstone = readTombstone(lease, id)
        val metadataRecord = context.read(lease, metadataKey(id))
        val bodyRecord = context.read(lease, bodyKey(id))
        val exists = id in index.ids || metadataRecord != null || bodyRecord != null
        val metadata = decodeMetadataOrNull(metadataRecord, id)
        val document = metadata?.let { verifiedDocumentOrNull(bodyRecord, it) }
        checkIndex(lease, index)
        if (tombstone != null && exists) kitchenFail(FailureReason.INVALID_DATA)
        return Loaded(index, metadataRecord, bodyRecord, metadata, document, exists,
            exists && (id !in index.ids || metadata == null || document == null), tombstone)
    }

    private suspend fun readIndex(lease: SessionLease): Index {
        val record = context.read(lease, INDEX) ?: return Index(null, emptyList())
        if (record.schemaVersion != 1) PrivateJson.invalid()
        val values = PrivateJson.decode(record.payload, setOf("ids"))
        val ids = PrivateJson.strings(values.getValue("ids"))
        if (ids.size > MAX_LOCAL_ENTRIES || ids.toSet().size != ids.size || ids.any { normalizedId(it) != it }) PrivateJson.invalid()
        return Index(record, ids)
    }

    private suspend fun checkIndex(lease: SessionLease, index: Index) {
        val current = context.read(lease, INDEX)
        if (current?.revision != index.record?.revision || current?.schemaVersion != index.record?.schemaVersion ||
            (current != null && index.record != null && !current.payload.copyForCodec().contentEquals(index.record.payload.copyForCodec())))
            kitchenFail(FailureReason.CONFLICT)
    }

    private suspend fun write(lease: SessionLease, baseline: Loaded, body: PrivateBytes, metadata: Metadata): Map<RecordKey, Long?> {
        val ids = if (metadata.id in baseline.index.ids) baseline.index.ids else baseline.index.ids + metadata.id
        val reserved = readReservations(lease)
        if (ids.size > MAX_LOCAL_ENTRIES) kitchenFail(FailureReason.INVALID_DATA)
        if (ids.size + reserved.items.size > MAX_LOCAL_ENTRIES) kitchenFail(FailureReason.UNAVAILABLE)
        return context.commit(lease, bundleMutations(baseline, body, metadata, ids) +
            listOfNotNull(reserved.record?.let { reservationMutation(reserved, reserved.items) }))
    }

    private fun bundleMutations(baseline: Loaded, body: PrivateBytes, metadata: Metadata, ids: List<String>): List<StoreMutation> = listOf(
            kitchenPut(bodyKey(metadata.id), baseline.bodyRecord?.revision, body),
            kitchenPut(metadataKey(metadata.id), baseline.metadataRecord?.revision, encodeMetadata(metadata)),
            indexMutation(baseline.index, ids),
        )

    private fun indexMutation(index: Index, ids: List<String>) = kitchenPut(INDEX, index.record?.revision,
        PrivateJson.encode(buildJsonObject { put("ids", JsonArray(ids.map(::JsonPrimitive))) }))

    private fun rejectDeletedOrBroken(baseline: Loaded) {
        if (baseline.tombstone != null) kitchenFail(FailureReason.CONFLICT)
        if (baseline.broken) kitchenFail(FailureReason.INVALID_DATA)
    }

    private suspend fun readTombstone(lease: SessionLease, id: String): PrivateRecord? {
        val record = context.read(lease, tombstoneKey(id)) ?: return null
        if (record.schemaVersion != 1) PrivateJson.invalid()
        val fields = PrivateJson.decode(record.payload, setOf("id", "commandId", "version", "recipeVersionId", "digest"))
        if (PrivateJson.uuid(fields.getValue("id")) != id) PrivateJson.invalid()
        PrivateJson.uuid(fields.getValue("commandId")); PrivateJson.uuid(fields.getValue("recipeVersionId"))
        PrivateJson.hash(fields.getValue("digest"))
        val version = PrivateJson.string(fields.getValue("version"))
        if (!version.matches(Regex("[1-9][0-9]*")) || version.length > 4096) PrivateJson.invalid()
        return record
    }

    private fun planRecipe(plan: PlanWire): RecipeVersionWire {
        context.document("Plan", PrivateBytes(plan.document.encodeUtf8()))
        val recipe = (plan.recipeSnapshot as? WireField.Value)?.value ?: kitchenFail(FailureReason.INVALID_DATA)
        val version = (plan.recipeVersionId as? WireField.Value)?.value ?: kitchenFail(FailureReason.INVALID_DATA)
        if (normalizedId(version.value) != normalizedId(recipe.id.value)) kitchenFail(FailureReason.INVALID_DATA)
        return recipe
    }

    private fun saveReservation(commandId: String, plan: PlanWire, request: SaveRecipeRequest): Reservation {
        val recipe = planRecipe(plan)
        val body = PrivateBytes(request.document.encodeUtf8())
        context.document("SaveRecipeRequest", body)
        if (request.planId?.value?.let(::normalizedId) != normalizedId(plan.id.value) ||
            request.recipeVersionId?.value?.let(::normalizedId)?.let { it != normalizedId(recipe.id.value) } == true ||
            request.collectionId != null || request.markMakeAgain == true) kitchenFail(FailureReason.INVALID_DATA)
        // Bound canonical bundle + conservative envelope before an external irreversible Save.
        // This is a response/cache profile, not a quota, disk-space guarantee or rights grant.
        if (recipe.document.encodeUtf8().size > MAX_RESPONSE_BYTES - 4096 || plan.document.encodeUtf8().size > MAX_RESPONSE_BYTES)
            kitchenFail(FailureReason.UNAVAILABLE)
        return Reservation(normalizedId(commandId), privateDigest(PrivateBytes(plan.document.encodeUtf8())), privateDigest(body))
    }

    private suspend fun requireNotRecalled(lease: SessionLease, plan: PlanWire) {
        val recipe = planRecipe(plan)
        if (plan.status != "ready" || recipe.reviewStatus != "published" ||
            context.isRecalled(lease, "version", normalizedId(recipe.id.value))) kitchenFail(FailureReason.FORBIDDEN)
    }

    private suspend fun readReservations(lease: SessionLease): Reservations {
        val record = context.read(lease, RESERVATIONS) ?: return Reservations(null, emptyList())
        if (record.schemaVersion != 1) PrivateJson.invalid()
        val fields = PrivateJson.decode(record.payload, setOf("items"))
        val array = fields.getValue("items") as? JsonArray ?: PrivateJson.invalid()
        if (array.size > MAX_RESERVATIONS) PrivateJson.invalid()
        val items = array.map { entry ->
            val value = entry as? JsonObject ?: PrivateJson.invalid()
            if (value.keys != setOf("commandId", "planDigest", "requestDigest")) PrivateJson.invalid()
            Reservation(PrivateJson.uuid(value.getValue("commandId")), PrivateJson.hash(value.getValue("planDigest")),
                PrivateJson.hash(value.getValue("requestDigest")))
        }
        if (items.map { it.commandId }.distinct().size != items.size) PrivateJson.invalid()
        return Reservations(record, items)
    }

    private fun reservationMutation(before: Reservations, items: List<Reservation>) = kitchenPut(RESERVATIONS, before.record?.revision,
        PrivateJson.encode(buildJsonObject { put("items", JsonArray(items.map { item -> buildJsonObject {
            put("commandId", item.commandId); put("planDigest", item.planDigest); put("requestDigest", item.requestDigest)
        } })) }))

    private fun requireReservation(reserved: Reservations, expected: Reservation) {
        if (reserved.items.singleOrNull { it.commandId == expected.commandId } != expected) kitchenFail(FailureReason.CONFLICT)
    }

    private fun boundedBody(reply: ApiReply): PrivateBytes {
        val body = reply.body ?: kitchenFail(FailureReason.INVALID_DATA)
        if (body.copyForCodec().size > MAX_RESPONSE_BYTES) kitchenFail(FailureReason.UNAVAILABLE)
        return body
    }

    private fun boundReply(operation: String, reply: ApiReply) {
        if (reply.body?.copyForCodec()?.size?.let { it > MAX_RESPONSE_BYTES } == true) kitchenFail(FailureReason.UNAVAILABLE)
        if (CanonicalResponseBinder(context.validator).bind(operation, reply.status, reply.body?.copyForCodec(), reply.contentType,
                reply.traceId) !is ResponseBindingResult.Accepted) kitchenFail(FailureReason.INVALID_DATA)
    }

    private fun recallProblem(operation: String, reply: ApiReply): Boolean {
        boundReply(operation, reply)
        if (reply.status in 200..299) return false
        return (WireDocument.decode(boundedBody(reply).copyForCodec()).field("code") as? WireField.Value)
            ?.value?.stringOrNull() == "RECIPE_RECALLED"
    }

    private suspend fun incomingMetadata(lease: SessionLease, baseline: Loaded, document: WireDocument,
        bytes: PrivateBytes, etag: String?, requireEtag: Boolean): Metadata {
        val incoming = SavedRecipeWire.from(document)
        val id = normalizedId(incoming.id.value)
        val recalled = incoming.recalled || incoming.snapshot.reviewStatus == "recalled"
        if (recalled) markRecall(lease, id, normalizedId(baseline.document?.let { SavedRecipeWire.from(it).snapshot.id.value }
            ?: incoming.snapshot.id.value))
        val checked = checkedEtag(document, etag)
        if (requireEtag && checked == null) kitchenFail(FailureReason.INVALID_DATA)
        val old = baseline.metadata
        val removed = old?.removedFields.orEmpty().toMutableSet()
        if (old != null && !compatible(baseline.document!!, document, old, removed)) kitchenFail(FailureReason.CONFLICT)
        removed += context.savedRedactions(lease, id)
        if (removed.any { it in document.json().jsonObject }) kitchenFail(FailureReason.CONFLICT)
        // Explicit remote observation is not a download. Still retain proven negative removal
        // so a later offline read cannot reveal attribution from the older downloaded bytes.
        context.markSavedRedaction(lease, id, removed)
        return Metadata(id, privateDigest(bytes), documentVersion(document), checked, maxOf(old?.lastCheckedMillis ?: 0, context.now()),
            recalled || old?.recalled == true, incoming.title, removed.toSet())
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
        val redacted = context.savedRedactions(lease, id).any { it in document.json().jsonObject }
        return SavedRecipeSnapshot(id, if (recalled || redacted) null else saved,
            if (recalled) SavedRecipeAvailability.RECALLED else if (redacted) SavedRecipeAvailability.UNAVAILABLE else SavedRecipeAvailability.AVAILABLE,
            metadata.lastCheckedMillis, revision, metadata.etag)
    }

    private suspend fun corrupt(lease: SessionLease, id: String, revision: Long?, metadata: Metadata? = null): SavedRecipeSnapshot = SavedRecipeSnapshot(
        id, null, if (metadata?.recalled == true || context.isRecalled(lease, "saved", id)) SavedRecipeAvailability.RECALLED else SavedRecipeAvailability.INTEGRITY_FAILURE,
        metadata?.lastCheckedMillis ?: 0, revision, null,
    )

    private suspend fun Loaded.view(lease: SessionLease, id: String): SavedRecipeSnapshot = if (broken) corrupt(lease, id, metadataRecord?.revision, metadata)
        else view(lease, id, metadata!!, metadataRecord?.revision, document!!)

    private fun validQuery(query: String, maximum: Int = 100): Boolean {
        var count = 0
        var offset = 0
        while (offset < query.length) {
            val char = query[offset++]
            if (char.isISOControl() || char.isLowSurrogate()) return false
            if (char.isHighSurrogate()) {
                if (offset == query.length || !query[offset].isLowSurrogate()) return false
                offset++
            }
            if (++count > maximum) return false
        }
        return true
    }

    private class Index(val record: PrivateRecord?, val ids: List<String>)
    private class Loaded(val index: Index, val metadataRecord: PrivateRecord?, val bodyRecord: PrivateRecord?,
        val metadata: Metadata?, val document: WireDocument?, val exists: Boolean, val broken: Boolean, val tombstone: PrivateRecord?)
    private data class Reservation(val commandId: String, val planDigest: String, val requestDigest: String)
    private class Reservations(val record: PrivateRecord?, val items: List<Reservation>)
    private data class Metadata(val id: String, val digest: String, val version: String, val etag: String?,
        val lastCheckedMillis: Long, val recalled: Boolean, val title: String, val removedFields: Set<String>) {
        override fun toString() = "SavedRecipeMetadata(<redacted>)"
    }

    companion object {
        const val MAX_LOCAL_ENTRIES = 512
        const val MAX_RESPONSE_BYTES = 262_144
        private const val MAX_RESERVATIONS = 64
        private val RESERVATIONS = RecordKey("feedme.kitchen.saved.reservations", "v1")
        private fun tombstoneKey(id: String) = RecordKey("feedme.kitchen.saved.deleted", id)
        private val INDEX = RecordKey("feedme.kitchen.saved.index", "v1")
        private val PROVENANCE_FIELDS = savedProvenanceFields
        private val OUTER_MUTABLE = setOf("snapshot", "version", "updatedAt", "recalled") + PROVENANCE_FIELDS
        private val METADATA_FIELDS = setOf("id", "digest", "version", "etag", "lastCheckedMillis", "recalled", "title", "removedFields")
        private fun metadataKey(id: String) = RecordKey("feedme.kitchen.saved.metadata", id)
        private fun bodyKey(id: String) = RecordKey("feedme.kitchen.saved.body", id)
    }
}
