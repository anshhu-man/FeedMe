package com.feedme.mealflow

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.sync.*
import com.feedme.transport.MobileRequestValidator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Owner-scoped pantry/preferences editor over the existing durable command journal. All actions
 * are explicit. Use the session's SAME serialized dispatcher. Save is local admission, not remote
 * success; attempted conflicts keep their exact request and require a later reconciliation workflow.
 * This object borrows every port and never erases a scope, selects food, or submits a meal.
 */
class KitchenInputController(
    private val access: AuthenticatedMealPlanningAccess,
    private val boundary: SessionBoundary,
    private val dispatcher: CoroutineDispatcher,
    private val clock: EpochClock,
    private val connectivity: ConnectivityPort,
    private val ids: MealOperationIds,
    private val policy: KitchenInputPolicy,
) {
    private val origin = uuid(access.origin)
    private val key = KitchenInputPreferenceGuard.key(origin)
    private val codec = KitchenInputCodec(origin)
    private val binder = CanonicalResponseBinder()
    private val requests = MobileRequestValidator()
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(KitchenInputState.empty())
    val states: StateFlow<KitchenInputState> = mutable.asStateFlow()
    private var generation = Any()
    private var active: Any? = null
    private var operationOwner: Any? = null
    private var closed = false
    private var claimed = false
    private var last = KitchenInputRecord(0)
    private var views = emptyList<CommandView>()
    private var unacknowledgedPreference: WireDocument? = null
    private var pantryCursor: String? = null
    private var pantryPages = 0
    private val cursors = mutableSetOf<String>()
    private val pageIngredientIds = mutableSetOf<String>()
    private val pageRowIds = mutableSetOf<String>()
    private var preferencesObserved = false
    private val pantryObserved = mutableSetOf<String>()
    private var applyingPreference: KitchenInputFinalization? = null

    // Queue writes receive the same acknowledgement/readback discipline as domain writes. A
    // possibly committed failure is NEVER promoted just because a later read happens to match.
    private val store = object : PrivateStateStore {
        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> {
            checkActive(); if (scope != access.lease.scope) mealFail(FailureReason.STALE_SESSION)
            return access.store.read(scope, key).also { checkActive() }
        }
        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> {
            checkActive(); if (scope != access.lease.scope) mealFail(FailureReason.STALE_SESSION)
            applyingPreference?.let { proof ->
                if (mutations.any { it.key == key }) {
                    val archive = mutations.filterIsInstance<StoreMutation.Put>().singleOrNull { it.key.id == proof.command.id }
                        ?: mealFail(FailureReason.STORAGE_FAILURE)
                    if (archive.expectedRevision != proof.priorReceiptRevision) mealFail(FailureReason.CONFLICT)
                    proof.archive = archive
                }
            }
            val result = access.store.commit(scope, mutations); checkActive()
            if (result is PortResult.Failure) return result
            val receipt = (result as PortResult.Value).value
            if (receipt.keys != mutations.map { it.key }.toSet()) return PortResult.Failure(FailureReason.STORAGE_FAILURE)
            for (mutation in mutations) {
                val revision = receipt[mutation.key]
                if (mutation is StoreMutation.Put && (revision == null || revision <= (mutation.expectedRevision ?: 0)))
                    return PortResult.Failure(FailureReason.STORAGE_FAILURE)
                if (mutation is StoreMutation.Delete && revision != null) return PortResult.Failure(FailureReason.STORAGE_FAILURE)
                val read = access.store.read(scope, mutation.key); checkActive()
                if (read is PortResult.Failure) return read
                val actual = (read as PortResult.Value).value
                if (mutation is StoreMutation.Delete && actual != null || mutation is StoreMutation.Put &&
                    (actual == null || actual.revision != revision || actual.schemaVersion != mutation.schemaVersion ||
                        !actual.payload.copyForCodec().contentEquals(mutation.payload.copyForCodec())))
                    return PortResult.Failure(FailureReason.CONFLICT)
            }
            return result
        }
        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> = PortResult.Failure(FailureReason.NOT_CONFIGURED)
    }
    private val queue = DurableCommandQueue(access.lease.scope, store, boundary, dispatcher, clock,
        CommandExecutionGate { lease, intent ->
            checkActive()
            val entry = read()
            val command = entry.value.commands.singleOrNull { it.id == intent.commandId }
            if (lease !== access.lease || intent.originBinding != origin || command == null || !sameCall(command.call(), intent.call))
                ExecutionDecision.Wait(CommandIssue.DOMAIN_RECHECK_REQUIRED)
            else if (!online()) ExecutionDecision.Wait(CommandIssue.OFFLINE)
            else ExecutionDecision.Ready // Current authenticated transport/server still enforce rights and versions.
        }, object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                checkActive()
                if (lease !== access.lease) return PortResult.Failure(FailureReason.STALE_SESSION)
                if (!online()) return PortResult.Failure(FailureReason.OFFLINE)
                val result = access.transport.execute(lease, call); checkActive()
                return result
            }
        })
    private val subscription = boundary.onInvalidated(access.lease) { redact(invalidated = true) }

    init { require(access.lease.scope.actorKind != ActorKind.DEMO) }

    /** No send or automatic receipt application. Reading a stored draft is not server freshness. */
    suspend fun restore(): PortResult<KitchenInputState> = run {
        val entry = read(); preferencesObserved = false; pantryObserved.clear()
        observe(entry); publish()
    }

    suspend fun loadPreferences(): PortResult<KitchenInputState> = run {
        val entry = read(); observe(entry)
        if (!online()) return@run publish(KitchenInputPhase.OFFLINE, KitchenInputIssue.OFFLINE)
        publish(KitchenInputPhase.LOADING)
        val reply = fetch(ApiCall("getPreferences"))
        val body = document(reply, "Preference")
        entry.value.preferences?.let { monotonic(it, body, preference = true) }
        reply.etag?.let { kiEtag(body, it) }
        val saved = write(entry, entry.value.copy(preferences = body, preferenceEtag = reply.etag))
        preferencesObserved = true; observe(saved); publish()
    }

    suspend fun loadPantry(): PortResult<KitchenInputState> = run {
        pantryCursor = null; pantryPages = 0; cursors.clear(); pageIngredientIds.clear(); pageRowIds.clear(); pantryObserved.clear()
        loadPantryPage(null)
    }
    suspend fun nextPantryPage(): PortResult<KitchenInputState> = run {
        val next = pantryCursor ?: return@run publish(issue = KitchenInputIssue.PAGE_LIMIT)
        if (pantryPages >= policy.maxPantryPages) return@run publish(issue = KitchenInputIssue.PAGE_LIMIT)
        loadPantryPage(next)
    }

    /** This local patch, including stricter exclusions, blocks NEW meal planning until acknowledged
     * by the server or explicitly discarded. A failed local write retains a process-only fence. */
    suspend fun editPreferences(patch: WireDocument): PortResult<KitchenInputState> = run(fence = true) {
        codec.preferencePatch(patch)
        unacknowledgedPreference = patch
        KitchenInputPreferenceGuard.retainDraft(access.lease, origin, boundary, patch)
        val entry = read()
        val saved = write(entry, entry.value.copy(preferenceDraft = patch))
        unacknowledgedPreference = null; KitchenInputPreferenceGuard.clearDraft(access.lease, origin)
        observe(saved); publish(KitchenInputPhase.EDITING)
    }
    suspend fun discardPreferenceDraft(): PortResult<KitchenInputState> = run(fence = true) {
        // This local action has no authority to bypass an ambiguous original receipt archive.
        if (KitchenInputPreferenceGuard.finalization(access.lease, origin) != null) mealFail(FailureReason.CONFLICT)
        val entry = read()
        val saved = write(entry, entry.value.copy(preferenceDraft = null))
        unacknowledgedPreference = null; KitchenInputPreferenceGuard.clearDraft(access.lease, origin)
        observe(saved); releasePreferenceFenceAfterAcknowledgement(); publish()
    }
    suspend fun editPantry(write: WireDocument): PortResult<KitchenInputState> = run(fence = true) {
        codec.pantryDraft(write)
        val entry = read(); val id = kiIngredientId(write)
        val drafts = entry.value.pantryDrafts.filterNot { kiIngredientId(it) == id } + write
        if (drafts.size > policy.maxDrafts) mealFail(FailureReason.RATE_LIMITED)
        val saved = write(entry, entry.value.copy(pantryDrafts = drafts))
        observe(saved); publish(KitchenInputPhase.EDITING)
    }
    suspend fun discardPantryDraft(ingredientId: String): PortResult<KitchenInputState> = run(fence = true) {
        val id = uuid(ingredientId); val entry = read()
        observe(write(entry, entry.value.copy(pantryDrafts = entry.value.pantryDrafts.filterNot { kiIngredientId(it) == id })))
        publish()
    }

    suspend fun savePreferences(): PortResult<KitchenInputState> = run {
        val entry = read()
        if (unacknowledgedPreference != null || KitchenInputPreferenceGuard.draft(access.lease, origin) != null) mealFail(FailureReason.CONFLICT)
        if (KitchenInputPreferenceGuard.finalization(access.lease, origin) != null) mealFail(FailureReason.CONFLICT)
        if (entry.value.commands.any { it.operation == "updatePreferences" }) mealFail(FailureReason.CONFLICT)
        val patch = entry.value.preferenceDraft ?: mealFail(FailureReason.INVALID_DATA)
        val base = entry.value.preferences ?: return@run publish(KitchenInputPhase.EDITING, KitchenInputIssue.LOAD_REQUIRED)
        val etag = entry.value.preferenceEtag ?: return@run publish(KitchenInputPhase.EDITING, KitchenInputIssue.LOAD_REQUIRED)
        val command = KitchenInputCommand(newId(), "updatePreferences", null, patch, etag, base)
        enqueue(entry, command, entry.value.copy(preferenceDraft = null))
    }
    suspend fun savePantry(ingredientId: String): PortResult<KitchenInputState> = run {
        val id = uuid(ingredientId); val entry = read()
        if (entry.value.commands.any { it.target == id }) mealFail(FailureReason.CONFLICT)
        val draft = entry.value.pantryDrafts.singleOrNull { kiIngredientId(it) == id } ?: mealFail(FailureReason.INVALID_DATA)
        val base = entry.value.pantry.singleOrNull { kiIngredientId(it) == id }
        if (base == null && entry.value.pantry.size >= policy.maxPantryItems) mealFail(FailureReason.RATE_LIMITED)
        val body = doc(JsonObject(draft.json().jsonObject.toMutableMap().apply {
            if (base != null) put("expectedVersion", base.json().jsonObject.getValue("version"))
        }))
        val command = KitchenInputCommand(newId(), "upsertPantryItem", id, body, null, base)
        enqueue(entry, command, entry.value.copy(pantryDrafts = entry.value.pantryDrafts.filterNot { kiIngredientId(it) == id }))
    }
    suspend fun removePantry(ingredientId: String): PortResult<KitchenInputState> = run {
        val id = uuid(ingredientId); val entry = read()
        if (entry.value.commands.any { it.target == id }) mealFail(FailureReason.CONFLICT)
        val base = entry.value.pantry.singleOrNull { kiIngredientId(it) == id }
            ?: return@run publish(KitchenInputPhase.EDITING, KitchenInputIssue.LOAD_REQUIRED)
        val command = KitchenInputCommand(newId(), "removePantryItem", id, null, "\"${pickerVersion(base)}\"", base)
        enqueue(entry, command, entry.value)
    }

    /** Explicit bounded attempt of THIS command. Permanent/attempted conflicts never rotate its
     * ID/body/version. Applying a retained successful receipt does not need a network connection. */
    suspend fun synchronize(commandId: String): PortResult<KitchenInputState> = run { synchronizeOwned(uuid(commandId)) }

    /** Restores the unsent body as a LOCAL draft. Preference safety remains blocked until that
     * draft is explicitly discarded or saved; attempted intentions cannot enter this path. */
    suspend fun discardUnsent(commandId: String): PortResult<KitchenInputState> = run(fence = true) {
        val id = uuid(commandId); val entry = read()
        val command = entry.value.commands.singleOrNull { it.id == id } ?: mealFail(FailureReason.CONFLICT)
        val view = mealValue(queue.command(access.lease, id)) ?: mealFail(FailureReason.STORAGE_FAILURE)
        if (view.attempts != 0) mealFail(FailureReason.CONFLICT)
        var updated = entry.value.copy(commands = entry.value.commands.filterNot { it.id == id })
        if (command.operation == "updatePreferences" && updated.preferenceDraft == null) updated = updated.copy(preferenceDraft = command.body)
        if (command.operation == "upsertPantryItem" && updated.pantryDrafts.none { kiIngredientId(it) == command.target }) {
            val draft = doc(JsonObject(command.body!!.json().jsonObject - "expectedVersion"))
            updated = updated.copy(pantryDrafts = updated.pantryDrafts + draft)
        }
        mealValue(queue.discardUnsent(access.lease, id, view.localRevision, listOf(mutation(entry, updated))))
        observe(read()); publish()
    }
    suspend fun close(): PortResult<Unit> = withContext(NonCancellable + dispatcher) {
        closed = true; redact(invalidated = false); subscription.close(); PortResult.Value(Unit)
    }

    private suspend fun enqueue(entry: KitchenInputEntry, command: KitchenInputCommand, updated: KitchenInputRecord): KitchenInputState {
        if (!requests.accepts(command.call(), principal())) mealFail(FailureReason.INVALID_DATA)
        val value = updated.copy(commands = updated.commands + command)
        // Reserve worst-case string-escaped SUCCESS bodies before any server write. A successful
        // receipt must fit without dropping another unresolved intent or local exclusion draft.
        reserve(value)
        mealValue(queue.enqueue(access.lease, CommandIntent(command.id, origin, command.call()), listOf(mutation(entry, value))))
        observe(read())
        return publish(if (online()) KitchenInputPhase.PENDING else KitchenInputPhase.OFFLINE, KitchenInputIssue.PENDING_SYNC)
    }
    private suspend fun synchronizeOwned(id: String): KitchenInputState {
        var entry = read()
        if (entry.value.commands.none { it.id == id }) return finalizeAppliedPreference(entry, id)
        var view = mealValue(queue.command(access.lease, id)) ?: mealFail(FailureReason.STORAGE_FAILURE)
        if (view.phase == CommandPhase.IN_FLIGHT) {
            mealValue(queue.recoverInterrupted(access.lease))
            view = mealValue(queue.command(access.lease, id)) ?: mealFail(FailureReason.STORAGE_FAILURE)
        }
        if (view.phase == CommandPhase.NEEDS_RESOLUTION && resumable(view)) {
            if (!online()) { observe(entry); return publish(KitchenInputPhase.OFFLINE, KitchenInputIssue.PENDING_SYNC) }
            view = mealValue(queue.resumeAfterResolution(access.lease, id, view.localRevision))
        }
        if (view.phase in setOf(CommandPhase.READY, CommandPhase.RETRY_WAIT)) {
            if (!online()) { observe(entry); return publish(KitchenInputPhase.OFFLINE, KitchenInputIssue.PENDING_SYNC) }
            reserve(entry.value)
            mealValue(queue.dispatchAutomatic(access.lease, id))
        }
        val receipt = mealValue(queue.receipt(access.lease, id))
        if (receipt != null) {
            entry = read()
            val command = entry.value.commands.singleOrNull { it.id == id } ?: mealFail(FailureReason.CONFLICT)
            val original = mealValue(queue.intent(access.lease, id)) ?: mealFail(FailureReason.CONFLICT)
            if (original.originBinding != origin || original.commandId != id || !sameCall(command.call(), original.call))
                mealFail(FailureReason.CONFLICT)
            val updated = apply(entry.value, command, receipt.reply)
            val change = mutation(entry, updated)
            val proof = if (command.operation == "updatePreferences")
                KitchenInputFinalization(command, change.payload, entry.record!!.revision, receipt.command.localRevision, receipt.command.attempts) else null
            proof?.let { KitchenInputPreferenceGuard.retain(access.lease, origin, boundary, it) }
            applyingPreference = proof
            try { mealValue(queue.applyReceipt(access.lease, id, receipt.command.localRevision, listOf(change))) }
            finally { applyingPreference = null }
            observe(read())
            if (command.operation == "updatePreferences") {
                KitchenInputPreferenceGuard.clearFinalization(access.lease, origin)
                preferencesObserved = true; releasePreferenceFenceAfterAcknowledgement()
            } else command.target?.let(pantryObserved::add)
        }
        observe(read()); return publish()
    }
    private suspend fun finalizeAppliedPreference(entry: KitchenInputEntry, id: String): KitchenInputState {
        val proof = KitchenInputPreferenceGuard.finalization(access.lease, origin) ?: mealFail(FailureReason.CONFLICT)
        val record = entry.record ?: mealFail(FailureReason.CONFLICT)
        if (proof.command.id != id || record.revision <= proof.priorDomainRevision ||
            !record.payload.copyForCodec().contentEquals(proof.payload.copyForCodec())) mealFail(FailureReason.CONFLICT)
        val view = mealValue(queue.command(access.lease, id)) ?: mealFail(FailureReason.STORAGE_FAILURE)
        if (view.operationId != proof.command.operation || view.phase != CommandPhase.APPLIED || view.localRevision <= proof.priorReceiptRevision)
            mealFail(FailureReason.CONFLICT)
        val archive = proof.archive ?: mealFail(FailureReason.CONFLICT)
        val archived = mealValue(store.read(access.lease.scope, archive.key)) ?: mealFail(FailureReason.CONFLICT)
        if (archived.revision != view.localRevision || archived.schemaVersion != archive.schemaVersion ||
            !archived.payload.copyForCodec().contentEquals(archive.payload.copyForCodec())) mealFail(FailureReason.CONFLICT)
        // Receipt observation alone cannot repair an ambiguous apply acknowledgement. A fresh
        // changed revision of the EXACT resulting domain payload must be acknowledged/read back.
        mealValue(store.commit(access.lease.scope, listOf(StoreMutation.Put(key, record.revision, 1, proof.payload))))
        val repeated = mealValue(queue.command(access.lease, id)) ?: mealFail(FailureReason.STORAGE_FAILURE)
        if (repeated.localRevision != view.localRevision || repeated.phase != CommandPhase.APPLIED) mealFail(FailureReason.CONFLICT)
        val repeatedArchive = mealValue(store.read(access.lease.scope, archive.key)) ?: mealFail(FailureReason.CONFLICT)
        if (repeatedArchive.revision != archived.revision || !repeatedArchive.payload.copyForCodec().contentEquals(archived.payload.copyForCodec()))
            mealFail(FailureReason.CONFLICT)
        observe(read()); KitchenInputPreferenceGuard.clearFinalization(access.lease, origin)
        releasePreferenceFenceAfterAcknowledgement(); return publish()
    }
    private fun apply(before: KitchenInputRecord, command: KitchenInputCommand, reply: ApiReply): KitchenInputRecord {
        if (binder.bind(command.operation, reply.status, reply.body?.copyForCodec(), reply.contentType, reply.traceId) !is ResponseBindingResult.Accepted)
            mealFail(FailureReason.INVALID_DATA)
        var updated = before.copy(commands = before.commands.filterNot { it.id == command.id })
        if (command.operation == "removePantryItem") {
            if (reply.status != 204 || reply.body != null) mealFail(FailureReason.INVALID_DATA)
            // Do not erase a newer locally observed incarnation/version from a delayed receipt.
            before.pantry.singleOrNull { kiIngredientId(it) == command.target }?.let {
                if (!equivalent(it.json(), command.base!!.json())) mealFail(FailureReason.CONFLICT)
            }
            return updated.copy(pantry = before.pantry.filterNot { kiIngredientId(it) == command.target })
        }
        if (reply.status != 200) mealFail(FailureReason.INVALID_DATA)
        val preference = command.operation == "updatePreferences"
        val body = document(reply, if (preference) "Preference" else "PantryItem")
        command.base?.let { base ->
            if (kiId(base) != kiId(body) || pickerCompareVersion(body, base) <= 0)
                mealFail(FailureReason.CONFLICT)
        }
        if (!preference && kiIngredientId(body) != command.target) mealFail(FailureReason.INVALID_DATA)
        for ((field, value) in command.body!!.json().jsonObject) {
            if (field != "expectedVersion" && !equivalent(value, body.json().jsonObject[field], field)) mealFail(FailureReason.CONFLICT)
        }
        reply.etag?.let { kiEtag(body, it) }
        if (preference) {
            before.preferences?.let { monotonic(it, body, true) }
            updated = updated.copy(preferences = body, preferenceEtag = reply.etag)
        } else {
            before.pantry.singleOrNull { kiIngredientId(it) == command.target }?.let { monotonic(it, body, false) }
            updated = updated.copy(pantry = before.pantry.filterNot { kiIngredientId(it) == command.target } + body)
        }
        return updated
    }

    private suspend fun loadPantryPage(cursor: String?): KitchenInputState {
        val entry = read(); observe(entry)
        if (!online()) return publish(KitchenInputPhase.OFFLINE, KitchenInputIssue.OFFLINE)
        publish(KitchenInputPhase.LOADING)
        val parameters = mutableMapOf("limit" to listOf(policy.pageSize.toString()))
        cursor?.let { parameters["cursor"] = listOf(it) }
        val reply = fetch(ApiCall("listPantry", queryParameters = parameters))
        val page = WireDocument.decode(reply.body!!.copyForCodec(), WireLimits(262_144, 16)).json().jsonObject
        val items = page.getValue("items").jsonArray.map { doc(it).also { row -> codec.schema("PantryItem", row) } }
        if (items.size > policy.pageSize || items.map(::kiIngredientId).distinct().size != items.size ||
            items.map(::kiId).distinct().size != items.size || items.any { kiIngredientId(it) in pageIngredientIds || kiId(it) in pageRowIds })
            mealFail(FailureReason.CONFLICT)
        val next = page.getValue("nextCursor").let { if (it == JsonNull) null else string(it).also { value ->
            if (value.isBlank() || value.length > 2048 || value.any(Char::isISOControl) || value in cursors) mealFail(FailureReason.CONFLICT)
        } }
        val merged = entry.value.pantry.associateBy(::kiIngredientId).toMutableMap()
        for (item in items) {
            merged[kiIngredientId(item)]?.let { monotonic(it, item, false) }
            merged[kiIngredientId(item)] = item
        }
        if (merged.size > policy.maxPantryItems) mealFail(FailureReason.RATE_LIMITED)
        val value = entry.value.copy(pantry = merged.values.toList()); reserve(value)
        val saved = write(entry, value)
        pantryCursor = next; pantryPages++; next?.let(cursors::add)
        pageIngredientIds += items.map(::kiIngredientId); pageRowIds += items.map(::kiId)
        pantryObserved += items.map(::kiIngredientId); observe(saved); return publish()
    }
    private suspend fun fetch(call: ApiCall): ApiReply {
        checkActive(); if (!online()) mealFail(FailureReason.OFFLINE)
        if (!requests.accepts(call, principal())) mealFail(FailureReason.INVALID_DATA)
        val result = access.transport.execute(access.lease, call); checkActive()
        val reply = mealValue(result)
        if (binder.bind(call.operationId, reply.status, reply.body?.copyForCodec(), reply.contentType, reply.traceId) !is ResponseBindingResult.Accepted)
            mealFail(FailureReason.INVALID_DATA)
        if (reply.status != 200) mealFail(when (reply.status) {
            401 -> FailureReason.UNAUTHENTICATED; 403 -> FailureReason.FORBIDDEN; 404 -> FailureReason.NOT_FOUND
            409, 412 -> FailureReason.CONFLICT; 429 -> FailureReason.RATE_LIMITED; 500, 503 -> FailureReason.UNAVAILABLE
            else -> FailureReason.INVALID_DATA
        })
        return reply
    }
    private fun document(reply: ApiReply, schema: String): WireDocument =
        WireDocument.decode(reply.body?.copyForCodec() ?: mealFail(FailureReason.INVALID_DATA), WireLimits(policy.maxResponseBytes, 16)).also { codec.schema(schema, it) }
    private suspend fun read(): KitchenInputEntry {
        val record = mealValue(store.read(access.lease.scope, key))
        if (record != null && record.schemaVersion != 1) mealFail(FailureReason.INVALID_DATA)
        val value = record?.let { codec.decode(it.payload) } ?: KitchenInputRecord(now())
        if (value.clock > now()) mealFail(FailureReason.CONFLICT)
        return KitchenInputEntry(record, value)
    }
    private fun mutation(entry: KitchenInputEntry, value: KitchenInputRecord): StoreMutation.Put =
        StoreMutation.Put(key, entry.record?.revision, 1, codec.encode(value.copy(clock = now())))
    private suspend fun write(entry: KitchenInputEntry, value: KitchenInputRecord): KitchenInputEntry {
        reserve(value)
        mealValue(store.commit(access.lease.scope, listOf(mutation(entry, value))))
        return read()
    }
    private fun reserve(value: KitchenInputRecord) {
        val size = codec.encode(value.copy(clock = now())).copyForCodec().size
        val growth = value.commands.count { it.operation != "removePantryItem" }.toLong() * policy.maxResponseBytes * 2L
        if (size.toLong() + growth > 1_048_576L) mealFail(FailureReason.RATE_LIMITED)
    }
    private suspend fun observe(entry: KitchenInputEntry) {
        val commands = entry.value.commands.map { command ->
            mealValue(queue.command(access.lease, command.id))?.also {
                if (it.operationId != command.operation || it.phase in setOf(CommandPhase.APPLIED, CommandPhase.DISCARDED))
                    mealFail(FailureReason.STORAGE_FAILURE)
            } ?: mealFail(FailureReason.STORAGE_FAILURE)
        }
        val after = read()
        if (!sameEntry(entry, after)) mealFail(FailureReason.CONFLICT)
        last = entry.value; views = commands
        if (last.pendingPreferences() || unacknowledgedPreference != null) KitchenInputPreferenceGuard.block(access.lease, origin, boundary)
    }
    private fun releasePreferenceFenceAfterAcknowledgement() {
        if (!last.pendingPreferences() && unacknowledgedPreference == null && KitchenInputPreferenceGuard.draft(access.lease, origin) == null &&
            KitchenInputPreferenceGuard.finalization(access.lease, origin) == null) KitchenInputPreferenceGuard.release(access.lease, origin)
    }
    private fun publish(phase: KitchenInputPhase? = null, issue: KitchenInputIssue = KitchenInputIssue.NONE,
        reason: FailureReason? = null): KitchenInputState {
        if (closed || !boundary.isCurrent(access.lease)) return KitchenInputState.unavailable().also { mutable.value = it }
        val pending = last.commands.mapNotNull { command -> views.singleOrNull { it.commandId == command.id }?.let { view ->
            KitchenInputPending(command.id, command.operation, command.target, command.body, command.etag, command.base,
                KitchenInputCommandPhase.valueOf(view.phase.name), view.attempts, view.issue.name, view.retryAtMillis,
                view.phase == CommandPhase.RECEIPT_READY || view.phase == CommandPhase.IN_FLIGHT ||
                    (online() && (view.phase in setOf(CommandPhase.READY, CommandPhase.RETRY_WAIT) || resumable(view))), view.attempts == 0)
        } }.toMutableList()
        KitchenInputPreferenceGuard.finalization(access.lease, origin)?.takeIf { proof -> last.commands.none { it.id == proof.command.id } }?.let { proof ->
            val command = proof.command
            val matches = codec.encode(last).copyForCodec().contentEquals(proof.payload.copyForCodec())
            pending += KitchenInputPending(command.id, command.operation, command.target, command.body, command.etag, command.base,
                KitchenInputCommandPhase.APPLIED, proof.attempts, if (matches) "OUTCOME_UNKNOWN" else "DOMAIN_RECHECK_REQUIRED", 0, matches, false)
        }
        val blocked = pending.any { it.phase in setOf(KitchenInputCommandPhase.NEEDS_RESOLUTION, KitchenInputCommandPhase.APPLIED) && !it.canSynchronize }
        val unacknowledged = unacknowledgedPreference ?: KitchenInputPreferenceGuard.draft(access.lease, origin)
        val localDraft = unacknowledged ?: last.preferenceDraft
        val exclusions = (listOfNotNull(last.preferences, localDraft) + last.commands.filter { it.operation == "updatePreferences" }.flatMap { listOfNotNull(it.base, it.body) })
            .flatMap { it.json().jsonObject["hardExcludedIngredientIds"]?.jsonArray?.map { id -> uuid(string(id)) }.orEmpty() }.distinct()
        val preferencesPending = localDraft != null || pending.any { it.operationId == "updatePreferences" } || KitchenInputPreferenceGuard.blocked(access.lease, origin)
        val actualPhase = phase ?: if (blocked) KitchenInputPhase.CONFLICT else if (pending.isNotEmpty()) KitchenInputPhase.PENDING
            else if (localDraft != null || last.pantryDrafts.isNotEmpty()) KitchenInputPhase.EDITING else KitchenInputPhase.READY
        val actualIssue = if (issue != KitchenInputIssue.NONE) issue else if (blocked) KitchenInputIssue.RECONCILIATION_REQUIRED
            else if (pending.isNotEmpty()) KitchenInputIssue.PENDING_SYNC else KitchenInputIssue.NONE
        return KitchenInputState(actualPhase, last.preferences, localDraft, last.pantry, last.pantryDrafts, pending,
            preferencesPending, exclusions, pantryCursor != null && pantryPages < policy.maxPantryPages,
            !online() || last.preferences != null && !preferencesObserved || last.pantry.any { kiIngredientId(it) !in pantryObserved },
            unacknowledged == null, actualIssue, reason).also { mutable.value = it }
    }
    private suspend fun newId(): String { checkActive(); val id = ids.next(); checkActive(); return uuid(id) }
    private fun online() = access.onlineAllowed && connectivity.current() == Connectivity.ONLINE
    private fun principal() = if (access.lease.scope.actorKind == ActorKind.GUEST) PrincipalClass.GUEST else PrincipalClass.ACCOUNT
    private fun now() = clock.nowMillis().also { if (it < 0) mealFail(FailureReason.INVALID_DATA) }
    private suspend fun checkActive() {
        currentCoroutineContext().ensureActive()
        if (closed || active !== generation || !boundary.isCurrent(access.lease)) mealFail(FailureReason.STALE_SESSION)
    }
    private suspend fun run(fence: Boolean = false, action: suspend () -> KitchenInputState): PortResult<KitchenInputState> {
        var owned: Any? = null
        var ownedOperation: Any? = null
        try {
            return withContext(dispatcher) {
                currentCoroutineContext().ensureActive()
                if (closed || !boundary.isCurrent(access.lease)) mealFail(FailureReason.STALE_SESSION)
                if (fence) generation = Any()
                val token = generation
                mutex.withLock {
                    if (token !== generation) mealFail(FailureReason.STALE_SESSION)
                    owned = token; active = token
                    // Lifetime/edit generations can span successive operations. Only this admitted
                    // operation may handle a late caller-handoff cancellation or publish its error.
                    ownedOperation = Any().also { operationOwner = it }
                    checkActive(); claim()
                    try { val result = action(); checkActive(); PortResult.Value(result) }
                    finally { if (active === token) active = null }
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + dispatcher) { if (owned === generation && ownedOperation === operationOwner) {
                generation = Any(); active = null
                publish(KitchenInputPhase.PENDING, KitchenInputIssue.OUTCOME_UNKNOWN, FailureReason.OUTCOME_UNKNOWN)
            } }
            throw cancelled
        } catch (error: Exception) {
            val reason = (error as? MealFailure)?.reason ?: if (error is WireDecodingException || error is kotlinx.serialization.SerializationException)
                FailureReason.INVALID_DATA else FailureReason.STORAGE_FAILURE
            withContext(NonCancellable + dispatcher) { if (owned === generation && ownedOperation === operationOwner) publish(
                if (reason == FailureReason.OFFLINE) KitchenInputPhase.OFFLINE else KitchenInputPhase.ERROR,
                when (reason) { FailureReason.OFFLINE -> KitchenInputIssue.OFFLINE; FailureReason.INVALID_DATA -> KitchenInputIssue.INVALID_INPUT
                    FailureReason.OUTCOME_UNKNOWN -> KitchenInputIssue.OUTCOME_UNKNOWN; FailureReason.RATE_LIMITED -> KitchenInputIssue.RETRY_LATER
                    FailureReason.CONFLICT -> KitchenInputIssue.RECONCILIATION_REQUIRED; else -> KitchenInputIssue.STORAGE }, reason) }
            return PortResult.Failure(reason)
        }
    }
    private fun claim() {
        if (!claimed) {
            if (owners.any { it.lease === access.lease && it.origin == origin }) mealFail(FailureReason.CONFLICT)
            owners += Owner(access.lease, origin, this); claimed = true
        }
    }
    private fun redact(invalidated: Boolean) {
        generation = Any(); active = null; operationOwner = null; last = KitchenInputRecord(0); views = emptyList(); unacknowledgedPreference = null
        pantryCursor = null; cursors.clear(); pageIngredientIds.clear(); pageRowIds.clear(); pantryObserved.clear(); preferencesObserved = false
        if (claimed) { owners.removeAll { it.controller === this }; claimed = false }
        if (invalidated) KitchenInputPreferenceGuard.release(access.lease, origin)
        mutable.value = KitchenInputState.unavailable()
    }
    private class Owner(val lease: SessionLease, val origin: String, val controller: KitchenInputController)
    private companion object { val owners = mutableListOf<Owner>() }
}

private fun resumable(view: CommandView) = view.phase == CommandPhase.NEEDS_RESOLUTION && view.issue in setOf(
    CommandIssue.AUTH_REQUIRED, CommandIssue.NOT_CONFIGURED, CommandIssue.DOMAIN_RECHECK_REQUIRED,
    CommandIssue.OFFLINE, CommandIssue.TEMPORARILY_UNAVAILABLE)
private fun sameEntry(a: KitchenInputEntry, b: KitchenInputEntry) = a.record?.revision == b.record?.revision &&
    (a.record == null && b.record == null || a.record != null && b.record != null &&
        a.record.payload.copyForCodec().contentEquals(b.record.payload.copyForCodec()))
private fun sameCall(a: ApiCall, b: ApiCall): Boolean {
    val aKey = a.idempotencyKey; val bKey = b.idempotencyKey; val aBody = a.body; val bBody = b.body
    return a.operationId == b.operationId && a.pathParameters == b.pathParameters && a.queryParameters == b.queryParameters &&
        a.ifMatch == b.ifMatch && aKey != null && bKey != null && aKey.use { first -> bKey.use { first == it } } &&
        (aBody == null && bBody == null || aBody != null && bBody != null && aBody.copyForCodec().contentEquals(bBody.copyForCodec()))
}
private fun monotonic(previous: WireDocument, incoming: WireDocument, preference: Boolean) {
    if (kiId(previous) != kiId(incoming) || !preference && kiIngredientId(previous) != kiIngredientId(incoming)) mealFail(FailureReason.CONFLICT)
    val order = pickerCompareVersion(incoming, previous)
    if (order < 0 || order == 0 && !equivalent(previous.json(), incoming.json())) mealFail(FailureReason.CONFLICT)
}
/** Object order and decimal spellings are immaterial; canonical selection arrays are sets. */
private fun equivalent(a: JsonElement?, b: JsonElement?, field: String = ""): Boolean {
    if (a == null || b == null) return a == b
    if (a is JsonObject && b is JsonObject) return a.keys == b.keys && a.all { (key, value) -> equivalent(value, b[key], key) }
    if (a is JsonArray && b is JsonArray) {
        if (a.size != b.size) return false
        val remaining = b.toMutableList()
        for (value in a) {
            val index = remaining.indexOfFirst { equivalent(value, it, field) }
            if (index < 0) return false
            remaining.removeAt(index)
        }
        return remaining.isEmpty()
    }
    if (a is JsonPrimitive && b is JsonPrimitive && a != JsonNull && b != JsonNull) {
        if (!a.isString && !b.isString && a.content.firstOrNull()?.isDigit() == true && b.content.firstOrNull()?.isDigit() == true)
            return kiNumber(a) == kiNumber(b)
        if (a.isString && b.isString && (field.endsWith("IngredientIds") || field in setOf("id", "ingredientId")))
            return uuid(a.content) == uuid(b.content)
    }
    return a == b
}
