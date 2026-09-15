package com.feedme.mealflow

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.kitchen.*
import com.feedme.sync.*
import com.feedme.transport.MobileRequestValidator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Retained basic cookbook: one exact original command at a time on the SHARED kitchen queue.
 * A bounded remote page is not a downloaded bundle; Save is not Make Again or a cooking grant.
 * Rendering, restoration, Back and local search never dispatch. Explicit retries never rotate
 * keys, rebase If-Match, drain another feature's commands, or infer an unknown apply ACK.
 */
@OptIn(ExperimentalAtomicApi::class)
class CookbookController internal constructor(private val composition: MealKitchenComposition,
    private val ids: MealOperationIds, private val readiness: MealDraftReadiness, private val policy: CookbookPolicy) {
    private val access = composition.access
    private val boundary = composition.boundary
    private val dispatcher = composition.dispatcher
    private val origin = uuid(access.origin)
    private val key = RecordKey("mealflow.cookbook.v1", origin)
    private val mealKey = RecordKey("mealflow.v1", origin)
    private val codec = CookbookCodec(origin)
    private val mealCodec = MealFlowCodec(MealRequestBuilder(), origin)
    private val store = composition.store
    private val repository = composition.kitchen.savedRecipes
    private val queue = composition.kitchen.commands
    private val mutex = Mutex()
    private val identity = Any()
    private var generation = Any()
    private var active: Any? = null
    private var operation: Any? = null
    private var delivery: CookbookDelivery? = null
    private var closed = false
    private var claimed = false
    private var last = CookbookRecord(0)
    private var view: CommandView? = null
    private var screen = CookbookScreen.HIDDEN
    private var query: String? = null
    private var cursor: String? = null
    private var page = emptyList<SavedRecipeSnapshot>()
    private var selected: SavedRecipeSnapshot? = null
    private var localOnly = false
    private var historical = true
    private var acknowledged = false
    private var consent: PreparedCookbookDelete? = null
    private val seenIds = mutableSetOf<String>()
    private val seenCursors = mutableSetOf<String>()
    private var applying: CookbookFinalization? = null
    private var sourceAdmission: MealDraftReadiness.Ticket? = null
    private var clearAfterReturn: CookbookFinalization? = null
    private var negativeRead: Pair<Any, String>? = null
    private val mutable = MutableStateFlow(CookbookState.empty())
    val states: StateFlow<CookbookState> = mutable.asStateFlow()
    private val borrower = composition.bind(MealKitchenFeature.COOKBOOK, object : MealKitchenHooks {
        override suspend fun checkCurrent() = checkActive()
        override fun beforeCommit(mutations: List<StoreMutation>) {
            sourceAdmission?.let { readiness.check(it, it.record) }
            applying?.let { proof -> if (mutations.any { it.key == key }) {
                val archive = mutations.filterIsInstance<StoreMutation.Put>().singleOrNull {
                    it.key.collection == "feedme.command.metadata" && it.key.id == proof.command.id
                } ?: mealFail(FailureReason.CONFLICT)
                if (archive.expectedRevision != proof.receiptRevision) mealFail(FailureReason.CONFLICT)
                proof.archive = archive
            } }
        }
        override suspend fun afterTransport(call: ApiCall, reply: ApiReply) {
            if (call.operationId !in setOf("getSavedRecipe", "listSavedRecipes") || reply.status in 200..299) return
            val bytes = reply.body?.copyForCodec() ?: return
            if (bytes.size > SavedRecipeRepository.MAX_RESPONSE_BYTES) return
            val bound = CanonicalResponseBinder().bind(call.operationId, reply.status, bytes, reply.contentType, reply.traceId)
                as? ResponseBindingResult.Accepted ?: return
            val body = (bound.response.body as? WireBody.Present)?.document ?: return
            if ((body.field("code") as? WireField.Value)?.value?.stringOrNull() != "RECIPE_RECALLED") return
            checkActive()
            if (call.operationId == "listSavedRecipes") {
                // A collection Problem identifies no saved ID. Hide this uncertain projection,
                // but never manufacture per-copy recall markers, removal evidence or a receipt.
                page = emptyList(); selected = null; cursor = null; consent = null
                historical = true; acknowledged = false
                publish(CookbookPhase.ERROR, CookbookIssue.RECALLED, FailureReason.FORBIDDEN)
                return
            }
            val id = call.pathParameters["savedRecipeId"] ?: return
            negativeRead = (operation ?: mealFail(FailureReason.STALE_SESSION)) to id
            // Negative evidence is safe before the repository's marker write. If that write or
            // subsequent local inspection fails/cancels, no old positive body remains visible.
            if (selected?.id == id) selected = null
            page = page.filterNot { it.id == id }; consent = null; acknowledged = false
            publish(CookbookPhase.ERROR, CookbookIssue.RECALLED, FailureReason.FORBIDDEN)
        }
        override val savedRecipeCommands = object : SavedRecipeCommandHooks {
            override suspend fun executionDecision(lease: SessionLease, intent: CommandIntent): ExecutionDecision = try {
                if (lease !== access.lease) mealFail(FailureReason.STALE_SESSION)
                val entry = read(); val command = entry.value.command ?: mealFail(FailureReason.CONFLICT)
                requireIntent(command, intent)
                if (command.operation == "saveRecipe") {
                    requireSaveAllowed(command)
                    sourceAdmission?.let { requireSource(it, command.plan!!) }
                } else validateLocalDeletion(command)
                same(entry, read()); ExecutionDecision.Ready
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { ExecutionDecision.Wait(when ((failure as? MealFailure)?.reason) {
                FailureReason.OFFLINE -> CommandIssue.OFFLINE
                FailureReason.STALE_SESSION, FailureReason.UNAUTHENTICATED -> CommandIssue.AUTH_REQUIRED
                else -> CommandIssue.DOMAIN_RECHECK_REQUIRED
            }) }
            override suspend fun observeReply(lease: SessionLease, intent: CommandIntent, reply: ApiReply): PortResult<Unit> {
                if (lease !== access.lease) return PortResult.Failure(FailureReason.STALE_SESSION)
                val command = read().value.command ?: mealFail(FailureReason.CONFLICT)
                requireIntent(command, intent)
                return if (command.operation == "saveRecipe") repository.observeSaveReply(lease, PlanWire.from(command.plan!!), reply)
                else if (command.localDeletion != null) repository.observeDeleteReply(lease, command.localDeletion, reply)
                else repository.observeDeleteReply(lease, SavedRecipeWire.from(command.expected!!), reply)
            }
        }
    })
    private val subscription = boundary.onInvalidated(access.lease) { redact() }

    suspend fun restore(): PortResult<CookbookState> = run {
        read(); observeCommand(); acknowledged = false; historical = true
        last.resultId?.let { selected = mealValue(repository.read(access.lease, it)) }
        publish()
    }

    /** The exact persisted selected Plan and acknowledged draft are read here, never supplied by UI. */
    suspend fun saveSelectedPlan(title: String? = null): PortResult<CookbookState> = run {
        acknowledged = false
        val entry = read(); requireNoPending()
        val ticket = readiness.capture()
        val plan = requireSource(ticket)
        val request = mealValue(attempt { SaveRecipeRequest(planId = PlanId(plan.id.value), title = title) })
        val id = newId()
        val command = CookbookCommand(id, "saveRecipe", request.document, plan.document, null, null, now())
        requireSaveAllowed(command)
        val prepared = mealValue(repository.prepareSave(access.lease, id, plan, request))
        requireSource(ticket, plan.document); same(entry, read())
        sourceAdmission = ticket
        try {
            enqueue(entry, command, prepared.mutations)
            if (composition.online()) synchronize(id, confirmed = false) else publish(CookbookPhase.OFFLINE, CookbookIssue.PENDING_ORIGINAL)
        } finally { sourceAdmission = null }
    }

    /** One explicit original operation. Delete retry is fresh consent for the ORIGINAL If-Match. */
    suspend fun retryOriginal(): PortResult<CookbookState> = run {
        acknowledged = false
        val entry = read()
        if (entry.value.command == null) return@run finalizeOriginal()
        synchronize(entry.value.command.id, confirmed = entry.value.command.operation == "deleteSavedRecipe")
    }
    suspend fun discardUnsent(): PortResult<CookbookState> = run {
        acknowledged = false
        val entry = read(); if (proof() != null) mealFail(FailureReason.CONFLICT)
        val command = entry.value.command ?: mealFail(FailureReason.CONFLICT)
        val current = exactCommand(command)
        if (current.attempts != 0) mealFail(FailureReason.CONFLICT)
        val changes = if (command.operation == "saveRecipe") mealValue(repository.prepareDiscardSave(access.lease,
            command.id, PlanWire.from(command.plan!!), command.request())).mutations else emptyList()
        val update = mutation(entry, entry.value.copy(command = null, completedCommand = command.id))
        val evidence = CookbookFinalization(command, changes + update, current.localRevision, discarded = true)
        retain(evidence); applying = evidence
        try { mealValue(queue.discardUnsent(access.lease, command.id, current.localRevision, changes + update)) }
        finally { applying = null }
        read(); observeCommand(); clearProof(); acknowledged = false; publish()
    }

    /** Each explicit page replaces the bounded visible page; local512 cache is not a library quota. */
    suspend fun load(q: String? = null): PortResult<CookbookState> = run(fence = true) {
        read(); consent = null; screen = CookbookScreen.LIST; query = q
        cursor = null; seenIds.clear(); seenCursors.clear(); page = emptyList(); localOnly = false
        loadPage(null)
    }
    suspend fun more(): PortResult<CookbookState> = run {
        if (localOnly) mealFail(FailureReason.CONFLICT)
        val next = cursor ?: mealFail(FailureReason.CONFLICT)
        if (seenIds.size >= 10_000 || seenCursors.size >= 10_000) mealFail(FailureReason.UNAVAILABLE)
        consent = null; loadPage(next)
    }
    suspend fun searchDownloaded(q: String = ""): PortResult<CookbookState> = run(fence = true) {
        read(); consent = null; screen = CookbookScreen.LIST; query = q; localOnly = true; cursor = null
        page = mealValue(repository.search(access.lease, q, policy.pageSize)); historical = true; acknowledged = false; publish()
    }
    suspend fun open(savedRecipeId: String): PortResult<CookbookState> = run(fence = true) {
        read(); consent = null; val id = uuid(savedRecipeId)
        selected = mealValue(repository.read(access.lease, id)) ?: page.singleOrNull { it.id == id }
        screen = CookbookScreen.DETAIL; historical = true; acknowledged = false
        if (selected == null) {
            selected = observeRemote(remoteRead(id))
            historical = false
        }
        publish()
    }
    suspend fun refreshDetail(): PortResult<CookbookState> = run(fence = true) {
        consent = null; val id = selected?.id ?: mealFail(FailureReason.CONFLICT)
        selected = observeRemote(remoteRead(id)); historical = false; acknowledged = false; publish()
    }
    suspend fun download(): PortResult<CookbookState> = run {
        val id = selected?.id ?: mealFail(FailureReason.CONFLICT)
        selected = observeRemote(remoteRead(id, download = true))
        historical = false; acknowledged = false; publish()
    }
    suspend fun prepareDelete(savedRecipeId: String): PortResult<CookbookState> = run(fence = true) {
        read(); requireNoPending(); consent = null; val id = uuid(savedRecipeId)
        val local = selected?.takeIf { it.id == id && redactedLocal(it) }
        val target: SavedRecipeDeletionTarget?
        val snapshot: SavedRecipeSnapshot
        if (local != null) {
            val observed = mealValue(repository.read(access.lease, id)) ?: mealFail(FailureReason.CONFLICT)
            if (!redactedLocal(observed)) mealFail(FailureReason.CONFLICT)
            target = mealValue(repository.prepareLocalDeletion(access.lease, id))
            if (target.id != id || target.etag != observed.etag) mealFail(FailureReason.CONFLICT)
            mealValue(repository.validateLocalDeletion(access.lease, target)); snapshot = observed
        } else {
            // Never use a failed GET, damaged projection or arbitrary unavailable ID as a local
            // deletion fallback. The unchanged readable/uncached path needs its real response.
            if (selected?.takeIf { it.id == id }?.availability == SavedRecipeAvailability.INTEGRITY_FAILURE)
                mealFail(FailureReason.INVALID_DATA)
            snapshot = observeRemote(remoteRead(id)); target = null
            if (snapshot.savedRecipe == null || snapshot.etag == null) mealFail(FailureReason.CONFLICT)
        }
        selected = snapshot; screen = CookbookScreen.DETAIL
        consent = PreparedCookbookDelete(identity, generation, snapshot, now(), target)
        publish(issue = CookbookIssue.CONFIRM_DELETE)
    }
    suspend fun confirmDelete(prepared: PreparedCookbookDelete): PortResult<CookbookState> = run {
        acknowledged = false
        val entry = read(); requireNoPending()
        requireConsent(prepared)
        prepared.localTarget?.let { mealValue(repository.validateLocalDeletion(access.lease, it)) }
        val id = newId()
        requireConsent(prepared)
        prepared.localTarget?.let { mealValue(repository.validateLocalDeletion(access.lease, it)) }
        requireConsent(prepared)
        val evidence = prepared.localTarget?.evidence
        val expected = if (evidence == null) prepared.snapshot.savedRecipe?.document ?: mealFail(FailureReason.CONFLICT) else null
        val command = CookbookCommand(id, "deleteSavedRecipe", null, null, expected, evidence?.etag ?: prepared.snapshot.etag, now(), evidence)
        enqueue(entry, command, emptyList()); consent = null
        if (composition.online()) synchronize(id, confirmed = true) else publish(CookbookPhase.OFFLINE, CookbookIssue.PENDING_ORIGINAL)
    }
    suspend fun dismissDelete(): PortResult<CookbookState> = navigate(screen)
    suspend fun back(): PortResult<CookbookState> = navigate(if (screen == CookbookScreen.DETAIL) CookbookScreen.LIST else CookbookScreen.HIDDEN)
    private suspend fun navigate(target: CookbookScreen): PortResult<CookbookState> = run(fence = true, cached = true) {
        consent = null; screen = target; publish()
    }
    suspend fun close(): PortResult<Unit> = withContext(NonCancellable + dispatcher) {
        if (!closed) { redact(); closed = true; subscription.close(); composition.release(borrower) }
        PortResult.Value(Unit)
    }

    private suspend fun loadPage(next: String?): CookbookState {
        val requestedQuery = query
        val previous = page
        val response = mealValue(repository.listRemote(access.lease, requestedQuery, next, policy.pageSize))
        if (query != requestedQuery || response.query != requestedQuery || response.cursor != next || response.limit != policy.pageSize)
            mealFail(FailureReason.CONFLICT)
        if (response.items.any { it.id in seenIds } || (response.nextCursor != null && response.nextCursor in seenCursors)) mealFail(FailureReason.CONFLICT)
        val incoming = response.items.map { value -> observeRemote(value, previous) }
        incoming.forEach { seenIds += it.id }; next?.let(seenCursors::add)
        page = incoming; cursor = response.nextCursor; historical = false; acknowledged = false
        screen = CookbookScreen.LIST; return publish()
    }
    private fun observeRemote(value: SavedRecipeSnapshot, priorPage: List<SavedRecipeSnapshot> = page): SavedRecipeSnapshot {
        val prior = selected?.takeIf { it.id == value.id } ?: priorPage.singleOrNull { it.id == value.id }
        val before = prior?.savedRecipe?.document
        val after = value.savedRecipe?.document
        if (before != null && after != null) {
            val a = before.json().jsonObject; val b = after.json().jsonObject
            val order = pickerCompareVersion(after, before)
            if (order < 0 || (order == 0 && a != b)) mealFail(FailureReason.CONFLICT)
            val provenance = setOf("sourcePostId", "grantId", "creatorLabel")
            val outerMutable = setOf("snapshot", "version", "updatedAt", "recalled") + provenance
            if (order > 0 && JsonObject(a - outerMutable) != JsonObject(b - outerMutable)) mealFail(FailureReason.CONFLICT)
            val oldSnapshot = a["snapshot"]?.jsonObject; val newSnapshot = b["snapshot"]?.jsonObject
            // Same precise lifecycle mask as the repository; no instruction/ingredient/title
            // rewrite is allowed just because aggregate or recipe versions advanced.
            val lifecycle = setOf("version", "updatedAt", "reviewStatus", "recallReasonCode", "reviewedAt", "reviewerLabel")
            if (oldSnapshot != null && newSnapshot != null) {
                if (JsonObject(oldSnapshot - lifecycle) != JsonObject(newSnapshot - lifecycle)) mealFail(FailureReason.CONFLICT)
                val recipeOrder = pickerCompareVersion(doc(newSnapshot), doc(oldSnapshot))
                if (recipeOrder < 0 || (recipeOrder == 0 && oldSnapshot != newSnapshot)) mealFail(FailureReason.CONFLICT)
            }
            for (field in provenance) if (field in b && a[field] != b[field]) mealFail(FailureReason.CONFLICT)
        }
        return value
    }
    /** A failed content read remains a failed action. Only the actual same-ID local negative
     * projection may replace cached positive content; it never mints removal evidence or ACK. */
    private suspend fun remoteRead(id: String, download: Boolean = false): SavedRecipeSnapshot {
        val priorPage = page
        val result = if (download) repository.download(access.lease, id) else repository.getRemote(access.lease, id)
        checkActive()
        if (result is PortResult.Failure) {
            val local = try { repository.read(access.lease, id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            checkActive()
            val negative = (local as? PortResult.Value)?.value?.takeIf {
                it.id == id && it.savedRecipe == null && it.availability != SavedRecipeAvailability.AVAILABLE
            }
            if (negative != null) {
                selected = negative
                page = priorPage.map { if (it.id == id) negative else it }
                consent = null; acknowledged = false
            } else if (negativeRead?.let { it.first === operation && it.second == id } == true) {
                if (selected?.id == id) selected = null
                page = page.filterNot { it.id == id }; consent = null; acknowledged = false
            }
        }
        return mealValue(result)
    }
    private fun redactedLocal(snapshot: SavedRecipeSnapshot) = snapshot.savedRecipe == null && snapshot.localRevision != null &&
        snapshot.availability in setOf(SavedRecipeAvailability.RECALLED, SavedRecipeAvailability.UNAVAILABLE)
    private fun requireConsent(prepared: PreparedCookbookDelete) {
        val at = now()
        if (consent !== prepared || prepared.owner !== identity || prepared.generation !== generation ||
            at < prepared.created || at - prepared.created > policy.confirmationMillis) mealFail(FailureReason.CONFLICT)
    }
    private suspend fun validateLocalDeletion(command: CookbookCommand) {
        command.localDeletion?.let { mealValue(repository.validateLocalDeletion(access.lease, it)) }
    }
    private suspend fun enqueue(entry: CookbookEntry, command: CookbookCommand, changes: List<StoreMutation>) {
        if (!MobileRequestValidator().accepts(command.call(), principal())) mealFail(FailureReason.INVALID_DATA)
        same(entry, read())
        validateLocalDeletion(command)
        val update = mutation(entry, entry.value.copy(command = command, clock = now()))
        mealValue(queue.enqueue(access.lease, CommandIntent(command.id, origin, command.call()), changes + update))
        read(); observeCommand(); acknowledged = false
    }
    private suspend fun synchronize(id: String, confirmed: Boolean): CookbookState {
        var entry = read(); val command = entry.value.command ?: return finalizeOriginal()
        if (command.id != id) mealFail(FailureReason.CONFLICT)
        var current = exactCommand(command)
        if (current.phase == CommandPhase.IN_FLIGHT) {
            mealValue(queue.recoverInterrupted(access.lease)); current = exactCommand(command)
        }
        if (current.phase == CommandPhase.NEEDS_RESOLUTION && current.issue in setOf(CommandIssue.AUTH_REQUIRED,
                CommandIssue.NOT_CONFIGURED, CommandIssue.DOMAIN_RECHECK_REQUIRED, CommandIssue.OFFLINE, CommandIssue.TEMPORARILY_UNAVAILABLE)) {
            if (!composition.online()) return publish(CookbookPhase.OFFLINE, CookbookIssue.PENDING_ORIGINAL)
            current = mealValue(queue.resumeAfterResolution(access.lease, id, current.localRevision))
        }
        if (current.phase in setOf(CommandPhase.READY, CommandPhase.RETRY_WAIT, CommandPhase.AWAITING_CONFIRMATION)) {
            if (!composition.online()) return publish(CookbookPhase.OFFLINE, CookbookIssue.PENDING_ORIGINAL)
            if (command.operation == "deleteSavedRecipe") {
                if (!confirmed) mealFail(FailureReason.CONFLICT)
                mealValue(queue.dispatchConfirmed(access.lease, id))
            } else mealValue(queue.dispatchAutomatic(access.lease, id))
        }
        val receipt = mealValue(queue.receipt(access.lease, id))
        if (receipt != null) {
            entry = read(); if (entry.value.command?.id != id) mealFail(FailureReason.CONFLICT)
            requireIntent(command, mealValue(queue.intent(access.lease, id)) ?: mealFail(FailureReason.CONFLICT))
            val prepared = if (command.operation == "saveRecipe") repository.prepareSaveReceipt(access.lease, id,
                PlanWire.from(command.plan!!), command.request(), receipt.reply)
            else if (command.localDeletion != null) repository.prepareDeleteReceipt(access.lease, id, command.localDeletion, receipt.reply)
            else repository.prepareDeleteReceipt(access.lease, id, SavedRecipeWire.from(command.expected!!), command.etag!!, receipt.reply)
            val changes = mealValue(prepared)
            same(entry, read())
            val resultId = if (command.operation == "saveRecipe") changes.savedRecipeId else null
            val update = mutation(entry, entry.value.copy(command = null, resultId = resultId, completedCommand = id, clock = now()))
            val evidence = CookbookFinalization(command, changes.mutations + update, receipt.command.localRevision)
            retain(evidence); applying = evidence
            try { mealValue(queue.applyReceipt(access.lease, id, receipt.command.localRevision, changes.mutations + update)) }
            finally { applying = null }
            read(); observeCommand()
            selected = resultId?.let { mealValue(repository.read(access.lease, it)) }
            if (command.operation == "deleteSavedRecipe") page = page.filterNot { it.id == command.savedRecipeId() }
            clearProof(); acknowledged = true; historical = false
            if (selected != null) screen = CookbookScreen.DETAIL
        }
        read(); observeCommand(); return publish()
    }

    /** Same-lease original batch + actual archive proof, fresh CAS, full read brackets. */
    private suspend fun finalizeOriginal(): CookbookState {
        val evidence = proof() ?: mealFail(FailureReason.CONFLICT)
        val archive = evidence.archive ?: mealFail(FailureReason.CONFLICT)
        val all = evidence.mutations + archive
        val observed = observeExact(all, allowDomainRevision = true)
        val command = mealValue(queue.command(access.lease, evidence.command.id)) ?: mealFail(FailureReason.CONFLICT)
            if (command.phase != (if (evidence.discarded) CommandPhase.DISCARDED else CommandPhase.APPLIED) || command.operationId != evidence.command.operation ||
            command.localRevision != observed[archive.key]?.revision) mealFail(FailureReason.CONFLICT)
        val domain = observed[key] ?: mealFail(FailureReason.CONFLICT)
        // Deleted keys stay absent; exact retained tombstone and all untouched records are checked
        // both sides. Re-ack the original resulting controller payload, not a new command/body.
        sameObserved(observed, observeExact(all, allowDomainRevision = true))
        commit(listOf(StoreMutation.Put(key, domain.revision, domain.schemaVersion, domain.payload)))
        val after = observeExact(all, allowDomainRevision = true)
        for ((k, value) in observed) if (k != key && !sameRecord(value, after[k])) mealFail(FailureReason.CONFLICT)
        read(); observeCommand()
        selected = last.resultId?.let { mealValue(repository.read(access.lease, it)) }
        if (evidence.command.operation == "deleteSavedRecipe" && !evidence.discarded)
            page = page.filterNot { it.id == evidence.command.savedRecipeId() }
        clearProof(); acknowledged = !evidence.discarded; historical = false; return publish()
    }
    private suspend fun observeExact(changes: List<StoreMutation>, allowDomainRevision: Boolean = false): Map<RecordKey, PrivateRecord?> {
        val found = linkedMapOf<RecordKey, PrivateRecord?>()
        for (change in changes) {
            val current = mealValue(store.read(access.lease.scope, change.key))
            when (change) {
                is StoreMutation.Put -> if (current == null || current.schemaVersion != change.schemaVersion ||
                    !current.payload.copyForCodec().contentEquals(change.payload.copyForCodec()) ||
                    (if (change.key == key && allowDomainRevision) current.revision <= (change.expectedRevision ?: 0)
                    else current.revision != (change.expectedRevision ?: 0) + 1)) mealFail(FailureReason.CONFLICT)
                is StoreMutation.Delete -> if (current != null) mealFail(FailureReason.CONFLICT)
            }
            found[change.key] = current
        }
        for ((k, value) in found.entries.toList().asReversed()) if (!sameRecord(value, mealValue(store.read(access.lease.scope, k)))) mealFail(FailureReason.CONFLICT)
        return found
    }
    private suspend fun exactCommand(command: CookbookCommand): CommandView {
        requireIntent(command, mealValue(queue.intent(access.lease, command.id)) ?: mealFail(FailureReason.CONFLICT))
        return mealValue(queue.command(access.lease, command.id))?.also {
            if (it.operationId != command.operation) mealFail(FailureReason.CONFLICT)
        } ?: mealFail(FailureReason.CONFLICT)
    }
    private fun requireIntent(command: CookbookCommand, intent: CommandIntent) {
        if (intent.commandId != command.id || intent.originBinding != origin || intent.dependencyCommandIds.isNotEmpty() ||
            !cookbookSameCall(command.call(), intent.call)) mealFail(FailureReason.CONFLICT)
    }
    private suspend fun requireSaveAllowed(command: CookbookCommand) {
        val plan = PlanWire.from(command.plan!!)
        val recipe = (plan.recipeSnapshot as? WireField.Value)?.value ?: mealFail(FailureReason.CONFLICT)
        if (plan.status != "ready" || recipe.reviewStatus != "published" ||
            (plan.sourcePostId as? WireField.Value) != null || recipe.reviewedAt !is WireField.Value) mealFail(FailureReason.FORBIDDEN)
        if (mealValue(composition.kitchen.cooking.hasRecipeRecall(access.lease, recipe.id.value))) mealFail(FailureReason.FORBIDDEN)
        if (KitchenInputPreferenceGuard.pending(access)) mealFail(FailureReason.CONFLICT)
        checkActive()
    }
    private suspend fun requireSource(ticket: MealDraftReadiness.Ticket, expected: WireDocument? = null): PlanWire {
        readiness.check(ticket, ticket.record)
        val stored = mealValue(store.read(access.lease.scope, mealKey)) ?: mealFail(FailureReason.CONFLICT)
        readiness.check(ticket, stored)
        if (stored.schemaVersion != 1) mealFail(FailureReason.INVALID_DATA)
        val data = mealCodec.decode(stored.payload)
        val selected = data.plans.getOrNull(data.selected) ?: mealFail(FailureReason.CONFLICT)
        val draft = data.draft ?: mealFail(FailureReason.CONFLICT)
        val preferences = data.preferences ?: mealFail(FailureReason.CONFLICT)
        if (data.command != null || draft.preferencesPendingSync || !equal(mealValue(MealRequestBuilder().build(draft, preferences)), selected.request) ||
            (expected != null && !equal(selected.body, expected))) mealFail(FailureReason.CONFLICT)
        val plan = PlanWire.from(selected.body)
        val version = (plan.recipeVersionId as? WireField.Value)?.value?.value?.lowercase()
        if (plan.status != "ready" || version in data.recalledVersions) mealFail(FailureReason.FORBIDDEN)
        return plan
    }
    private suspend fun read(): CookbookEntry {
        val record = mealValue(store.read(access.lease.scope, key))
        if (record != null && record.schemaVersion !in 1..2) mealFail(FailureReason.INVALID_DATA)
        val value = record?.let { codec.decode(it.payload) } ?: CookbookRecord(now())
        if (record != null && record.schemaVersion != codec.schema(value)) mealFail(FailureReason.INVALID_DATA)
        if (now() < value.clock) mealFail(FailureReason.CONFLICT)
        value.command?.let { if (!MobileRequestValidator().accepts(it.call(), principal())) mealFail(FailureReason.INVALID_DATA) }
        last = value
        view = value.command?.let { mealValue(queue.command(access.lease, it.id)) }
        return CookbookEntry(record, value)
    }
    private fun mutation(entry: CookbookEntry, value: CookbookRecord) = StoreMutation.Put(key, entry.record?.revision, codec.schema(value), codec.encode(value))
    private suspend fun commit(changes: List<StoreMutation>) {
        val receipt = mealValue(store.commit(access.lease.scope, changes))
        if (receipt.keys != changes.map { it.key }.toSet()) mealFail(FailureReason.STORAGE_FAILURE)
        for (change in changes) {
            val actual = mealValue(store.read(access.lease.scope, change.key))
            when (change) {
                is StoreMutation.Put -> if (receipt[change.key] != (change.expectedRevision ?: 0) + 1 || actual == null ||
                    actual.revision != receipt[change.key] || actual.schemaVersion != change.schemaVersion ||
                    !actual.payload.copyForCodec().contentEquals(change.payload.copyForCodec())) mealFail(FailureReason.STORAGE_FAILURE)
                is StoreMutation.Delete -> if (receipt[change.key] != null || actual != null) mealFail(FailureReason.STORAGE_FAILURE)
            }
        }
    }
    private suspend fun observeCommand() { view = last.command?.let { mealValue(queue.command(access.lease, it.id)) } }
    private fun requireNoPending() { if (last.command != null || proof() != null) mealFail(FailureReason.CONFLICT) }
    private fun proof() = CookbookProofs.get(access, boundary)
    private fun retain(value: CookbookFinalization) = CookbookProofs.retain(access, boundary, value)
    private fun clearProof() { clearAfterReturn = proof() }
    private fun now() = composition.clock.nowMillis().also { if (it < 0) mealFail(FailureReason.INVALID_DATA) }
    private fun principal() = if (access.lease.scope.actorKind == ActorKind.GUEST) PrincipalClass.GUEST else PrincipalClass.ACCOUNT
    private suspend fun newId(): String {
        checkActive(); val id = uuid(ids.next()); checkActive()
        if (mealValue(queue.command(access.lease, id)) != null) mealFail(FailureReason.CONFLICT)
        return id
    }
    private fun same(a: CookbookEntry, b: CookbookEntry) { if (!sameRecord(a.record, b.record)) mealFail(FailureReason.CONFLICT) }
    private fun sameObserved(a: Map<RecordKey, PrivateRecord?>, b: Map<RecordKey, PrivateRecord?>) {
        if (a.keys != b.keys || a.any { !sameRecord(it.value, b[it.key]) }) mealFail(FailureReason.CONFLICT)
    }
    private fun sameRecord(a: PrivateRecord?, b: PrivateRecord?) = if (a == null || b == null) a == b else
        a.revision == b.revision && a.schemaVersion == b.schemaVersion && a.payload.copyForCodec().contentEquals(b.payload.copyForCodec())
    private fun publish(phase: CookbookPhase = if (last.command != null || proof() != null) CookbookPhase.PENDING else CookbookPhase.READY,
        issue: CookbookIssue = if (last.command != null || proof() != null) CookbookIssue.PENDING_ORIGINAL else CookbookIssue.NONE,
        failure: FailureReason? = null, update: Boolean = true, hideDeliveredProof: Boolean = false): CookbookState {
        if (closed || !boundary.isCurrent(access.lease)) return CookbookState.unavailable().also { mutable.value = it }
        val retained = if (hideDeliveredProof) null else proof()
        val pending = view?.let { CookbookPending(it.commandId, it.operationId, it.phase.name, it.attempts, it.issue.name,
            true, it.attempts == 0 && retained == null, retained != null) }
            ?: retained?.let { CookbookPending(it.command.id, it.command.operation, "APPLY_ACK_REQUIRED", 0, "OUTCOME_UNKNOWN", true, false, true) }
            ?: last.command?.let { CookbookPending(it.id, it.operation, "UNOBSERVED_ORIGINAL", -1, "RECONCILIATION_REQUIRED", true, false, false) }
        return CookbookState(screen, phase, query, page, selected, cursor != null, localOnly, historical, false,
            pending, consent, acknowledged && retained == null, issue, failure).also { if (update) mutable.value = it }
    }
    private suspend fun checkActive() {
        currentCoroutineContext().ensureActive()
        if (closed || active == null || active !== generation || !boundary.isCurrent(access.lease)) mealFail(FailureReason.STALE_SESSION)
    }
    private fun claim() {
        if (!claimed) {
            while (true) {
                val old = owners.load()
                if (old.any { it.lease === access.lease && it.origin == origin }) mealFail(FailureReason.CONFLICT)
                if (owners.compareAndSet(old, old + Claim(access.lease, origin, identity))) break
            }
            claimed = true
        }
    }
    private suspend fun run(fence: Boolean = false, cached: Boolean = false, action: suspend () -> CookbookState): PortResult<CookbookState> {
        var owned: Any? = null; var owner: Any? = null; var ticket: CookbookDelivery? = null
        try {
            val result = withContext(dispatcher) {
                currentCoroutineContext().ensureActive()
                if (closed || !boundary.isCurrent(access.lease)) mealFail(FailureReason.STALE_SESSION)
                if (fence) { delivery?.revoke(); generation = Any() }
                val token = generation
                if (cached) { owned = token; owner = Any().also { operation = it }; active = token; clearAfterReturn = null
                    delivery?.revoke(); ticket = CookbookDelivery(access.lease, owner!!).also { delivery = it }
                    try { checkActive(); action() } finally { if (active === token) active = null }
                } else mutex.withLock {
                    if (token !== generation) mealFail(FailureReason.STALE_SESSION)
                    owned = token; owner = Any().also { operation = it }; active = token; clearAfterReturn = null
                    delivery?.revoke(); ticket = CookbookDelivery(access.lease, owner!!).also { delivery = it }
                    try { checkActive(); claim(); composition.operate(borrower) { action() } }
                    finally { if (active === token) active = null }
                }
            }
            currentCoroutineContext().ensureActive()
            if (closed || !boundary.isCurrent(access.lease) || owned !== generation || owner !== operation) mealFail(FailureReason.STALE_SESSION)
            val prepared = withContext(dispatcher) {
                if (closed || !boundary.isCurrent(access.lease) || owned !== generation || owner !== operation) mealFail(FailureReason.STALE_SESSION)
                val evidence = clearAfterReturn?.takeIf { proof() === it }
                val expected = mutable.value
                val projected = if (evidence != null) publish(CookbookPhase.READY, CookbookIssue.NONE, update = false, hideDeliveredProof = true) else result
                val current = ticket ?: mealFail(FailureReason.STALE_SESSION)
                if (!current.arm(evidence, expected)) mealFail(FailureReason.STALE_SESSION)
                evidence?.delivery = current
                CookbookPublication(current, evidence, expected, projected)
            }
            currentCoroutineContext().ensureActive()
            if (closed || !boundary.isCurrent(access.lease) || owned !== generation || owner !== operation) mealFail(FailureReason.STALE_SESSION)
            // The only success linearization, after ALL cancellable dispatcher returns. No
            // suspension or SessionBoundary callback mutation follows. A later Back may publish
            // its own object, so exact-object StateFlow CAS never overwrites newer navigation.
            if (!prepared.ticket.deliver(prepared.proof, prepared.expected)) mealFail(FailureReason.STALE_SESSION)
            prepared.proof?.let { CookbookProofs.consume(access, boundary, it) }
            mutable.compareAndSet(prepared.expected, prepared.projected)
            return PortResult.Value(prepared.projected)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + dispatcher) { if (owned === generation && owner === operation) {
                ticket?.revoke(); acknowledged = false
                generation = Any(); active = null; publish(CookbookPhase.PENDING, CookbookIssue.RECONCILIATION_REQUIRED, FailureReason.OUTCOME_UNKNOWN)
            } }
            throw cancelled
        } catch (failure: Exception) {
            ticket?.revoke()
            val reason = if (closed || owned !== generation || !boundary.isCurrent(access.lease)) FailureReason.STALE_SESSION else
                (failure as? MealFailure)?.reason ?: if (failure is WireDecodingException || failure is IllegalArgumentException) FailureReason.INVALID_DATA else FailureReason.STORAGE_FAILURE
            withContext(NonCancellable + dispatcher) { if (owned === generation && owner === operation) publish(
                if (reason == FailureReason.OFFLINE) CookbookPhase.OFFLINE else CookbookPhase.ERROR,
                when (reason) { FailureReason.OFFLINE -> CookbookIssue.OFFLINE; FailureReason.CONFLICT, FailureReason.OUTCOME_UNKNOWN -> CookbookIssue.RECONCILIATION_REQUIRED
                    FailureReason.FORBIDDEN -> CookbookIssue.RECALLED; FailureReason.INVALID_DATA -> CookbookIssue.INVALID_INPUT
                    FailureReason.UNAVAILABLE -> CookbookIssue.LOCAL_CAPACITY; else -> CookbookIssue.STORAGE }, reason) }
            return PortResult.Failure(reason)
        }
    }
    private fun redact() {
        delivery?.revoke(); delivery = null
        generation = Any(); active = null; operation = null; last = CookbookRecord(0); view = null
        page = emptyList(); selected = null; consent = null; seenIds.clear(); seenCursors.clear(); sourceAdmission = null; negativeRead = null
        if (claimed) { while (true) {
            val old = owners.load(); if (owners.compareAndSet(old, old.filterNot { it.identity === identity })) break
        }; claimed = false }
        mutable.value = CookbookState.unavailable()
    }
    private class Claim(val lease: SessionLease, val origin: String, val identity: Any)
    private class CookbookPublication(val ticket: CookbookDelivery, val proof: CookbookFinalization?,
        val expected: CookbookState, val projected: CookbookState)
    private companion object { val owners = AtomicReference<List<Claim>>(emptyList()) }
}

private fun cookbookSameCall(a: ApiCall, b: ApiCall): Boolean {
    val ak = a.idempotencyKey; val bk = b.idempotencyKey; val ab = a.body; val bb = b.body
    return a.operationId == b.operationId && a.pathParameters == b.pathParameters && a.queryParameters == b.queryParameters && a.ifMatch == b.ifMatch &&
        ak != null && bk != null && ak.use { first -> bk.use { first == it } } &&
        (if (ab == null || bb == null) ab == null && bb == null else ab.copyForCodec().contentEquals(bb.copyForCodec()))
}
