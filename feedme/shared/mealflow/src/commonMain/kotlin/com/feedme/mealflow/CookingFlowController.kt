package com.feedme.mealflow

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.kitchen.*
import com.feedme.mealflow.timers.CookingTimerActionAdmission
import com.feedme.mealflow.timers.CookingTimerSnapshot
import com.feedme.mealflow.timers.SessionCookingTimers
import com.feedme.mealflow.timers.TimerFailure
import com.feedme.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/**
 * Retained manual cooking, composed from borrowed authenticated access on its identity dispatcher.
 * Uses the real kitchen repositories and their one durable queue. Construction/restoration never
 * sends, activates a store, creates a server session, starts a timer, or certifies recipe safety.
 * The application must retain this controller, render only its permitted projection, and explicitly
 * invoke confirmStart/retryStart/synchronize. No arbitrary historical Plan is a start capability.
 */
class CookingFlowController internal constructor(
    private val composition: MealKitchenComposition,
    private val ids: MealOperationIds,
    private val policy: CookingFlowPolicy,
) {
    constructor(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary, dispatcher: CoroutineDispatcher,
        clock: EpochClock, connectivity: ConnectivityPort, ids: MealOperationIds, policy: CookingFlowPolicy) :
        this(MealKitchenComposition(access, boundary, dispatcher, clock, connectivity), ids, policy)

    private val access = composition.access
    private val boundary = composition.boundary
    private val dispatcher = composition.dispatcher
    private val clock = composition.clock
    private val origin = uuid(access.origin)
    private val key = RecordKey("mealflow.cooking.v1", origin)
    private val codec = CookingFlowCodec(origin, policy)
    private val mealCodec = MealFlowCodec(MealRequestBuilder(), origin)
    private val mealKey = RecordKey("mealflow.v1", origin)
    private val mutex = Mutex()
    private var generation = Any()
    private var active: Any? = null
    private var operationOwner: Any? = null
    private var closed = false
    private var claimed = false
    private var last = CookingFlowRecord(0)
    private var snapshot: CookingSnapshot? = null
    private var commandViews = emptyList<CommandView>()
    private var preferencesBlocked = false
    private var acknowledged = false
    private var previewBlocked = false
    private var screenOverride: CookingFlowScreen? = null
    private var applyingStart: CookingStartFinalization? = null
    private val mutable = MutableStateFlow(CookingFlowState.empty())
    val states: StateFlow<CookingFlowState> = mutable.asStateFlow()
    private val store = composition.store
    private val transport = composition.transport
    private val kitchen = composition.kitchen
    private val borrower = composition.bind(MealKitchenFeature.COOKING, object : MealKitchenHooks {
        override suspend fun checkCurrent() = checkActive()
        override fun beforeCommit(mutations: List<StoreMutation>) {
            applyingStart?.let { proof ->
                if (mutations.any { it.key == key }) {
                    val archive = mutations.filterIsInstance<StoreMutation.Put>().singleOrNull { it.key.id == proof.start.id }
                        ?: mealFail(FailureReason.STORAGE_FAILURE)
                    if (archive.expectedRevision != proof.priorReceiptRevision) mealFail(FailureReason.CONFLICT)
                    proof.archive = archive
                }
            }
        }
        override suspend fun beforeTransport(call: ApiCall) {
            // The repository gate remains authoritative for exact head progress commands. This
            // additional guard covers preferences immediately before transport, including retries.
            if (call.operationId in setOf("updateCookSession", "completeCookSession") && !safeStop(call)) requirePreferences()
        }
        override suspend fun afterTransport(call: ApiCall, reply: ApiReply) {
            observeRecall(call, reply)
            if (call.operationId == "createCookSession") {
                val body = bound(call.operationId, reply)
                if (reply.status !in 200..299 &&
                    (body.field("code") as? WireField.Value)?.value?.stringOrNull() == "RECIPE_RECALLED") {
                    last.start?.let { rememberRecall(recipeId(it.plan)) }
                }
            }
        }
        override val createExecutionGate = CommandExecutionGate { lease, intent ->
            try {
                checkActive()
                if (lease !== access.lease) mealFail(FailureReason.STALE_SESSION)
                val entry = read()
                val start = entry.value.start ?: mealFail(FailureReason.CONFLICT)
                if (start.stage != CookingStartStage.QUEUED || !matches(intent, start)) mealFail(FailureReason.CONFLICT)
                validateCurrent(start, fresh = true)
                checkSame(entry, read())
                ExecutionDecision.Ready
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                ExecutionDecision.Wait(when ((failure as? MealFailure)?.reason) {
                    FailureReason.OFFLINE -> CommandIssue.OFFLINE
                    FailureReason.UNAUTHENTICATED, FailureReason.STALE_SESSION -> CommandIssue.AUTH_REQUIRED
                    else -> CommandIssue.DOMAIN_RECHECK_REQUIRED
                })
            }
        }
    })
    private val subscription = boundary.onInvalidated(access.lease) { redact() }

    init { require(access.lease.scope.actorKind != ActorKind.DEMO) }

    /** Local observation only: no download, command dispatch or automatic recovery send. */
    suspend fun restore(): PortResult<CookingFlowState> = run {
        read(); acknowledged = false; observe(); publish()
    }

    /** Fresh authorization/context reads prepare a retained proposal, but never POST a session. */
    suspend fun prepareStart(planId: String): PortResult<CookingFlowState> = run {
        val id = uuid(planId)
        val entry = read()
        if (finalization() != null) mealFail(FailureReason.CONFLICT)
        if (entry.value.start?.stage in setOf(CookingStartStage.PREPARED, CookingStartStage.QUEUED)) mealFail(FailureReason.CONFLICT)
        requirePreferences()
        if (!online()) mealFail(FailureReason.OFFLINE)
        val source = mealSource(id)
        val data = mealCodec.decode(source.payload)
        val selected = data.plans[data.selected]
        val preferences = data.preferences ?: mealFail(FailureReason.CONFLICT)
        val proposed = CookingStart(newId(), selected.body, preferences, source.revision, now(), CookingStartStage.PREPARED)
        validateCurrent(proposed, fresh = true)
        checkSame(entry, read())
        codec.reserve(entry.value.copy(start = proposed))
        write(entry, entry.value.copy(clock = now(), screen = CookingFlowScreen.RECIPE, start = proposed))
        screenOverride = null; acknowledged = false
        observe(); publish(CookingFlowIssue.CONFIRM_START)
    }

    /** Explicit consent. Offline calls retain only the prepared proposal, never invent a server ID. */
    suspend fun confirmStart(): PortResult<CookingFlowState> = run {
        var entry = read()
        val start = entry.value.start ?: mealFail(FailureReason.CONFLICT)
        if (start.stage != CookingStartStage.PREPARED) mealFail(FailureReason.CONFLICT)
        if (!online()) mealFail(FailureReason.OFFLINE)
        if (now() < start.created || now() - start.created > policy.confirmationMillis) mealFail(FailureReason.CONFLICT)
        validateCurrent(start, fresh = true)
        checkSame(entry, read())
        val queued = entry.value.copy(clock = now(), start = start.queued())
        codec.reserve(queued)
        // The exact original intent and domain pointer are committed by the actual shared queue.
        val result = kitchen.commands.enqueue(access.lease, CommandIntent(start.id, origin, start.call()), listOf(put(entry, queued)))
        checkActive(); mealValue(result)
        entry = read()
        if (entry.value.start?.id != start.id || entry.value.start?.stage != CookingStartStage.QUEUED) mealFail(FailureReason.CONFLICT)
        sendStart(entry)
    }

    /** Retry original identity/body only. Unknown dispatched outcomes are never replaced/discarded. */
    suspend fun retryStart(): PortResult<CookingFlowState> = run {
        var entry = read()
        val start = entry.value.start ?: mealFail(FailureReason.CONFLICT)
        if (start.stage == CookingStartStage.PREPARED) mealFail(FailureReason.CONFLICT)
        if (start.stage == CookingStartStage.ATTACHED) return@run acknowledgeAttachment(entry)
        checkActive(); mealValue(kitchen.commands.recoverInterrupted(access.lease)); checkActive()
        var view = command(start.id) ?: mealFail(FailureReason.CONFLICT)
        if (view.phase == CommandPhase.RECEIPT_READY) return@run attach(entry)
        if (!online()) mealFail(FailureReason.OFFLINE)
        validateCurrent(start, fresh = true)
        if (view.phase == CommandPhase.NEEDS_RESOLUTION && view.issue in RESUMABLE) {
            checkActive(); mealValue(kitchen.commands.resumeAfterResolution(access.lease, start.id, view.localRevision)); checkActive()
            view = command(start.id) ?: mealFail(FailureReason.CONFLICT)
        }
        if (view.phase !in setOf(CommandPhase.AWAITING_CONFIRMATION, CommandPhase.READY, CommandPhase.RETRY_WAIT))
            mealFail(FailureReason.CONFLICT)
        entry = write(entry, entry.value.copy(clock = now()))
        sendStart(entry)
    }

    suspend fun discardUnsentStart(): PortResult<CookingFlowState> = run {
        val entry = read(); val start = entry.value.start ?: mealFail(FailureReason.CONFLICT)
        val value = entry.value.copy(clock = now(), start = null)
        when (start.stage) {
            CookingStartStage.PREPARED -> write(entry, value)
            CookingStartStage.QUEUED -> {
                val command = command(start.id) ?: mealFail(FailureReason.CONFLICT)
                if (command.attempts != 0) mealFail(FailureReason.CONFLICT)
                checkActive(); mealValue(kitchen.commands.discardUnsent(access.lease, start.id, command.localRevision, listOf(put(entry, value)))); checkActive(); read()
            }
            CookingStartStage.ATTACHED -> mealFail(FailureReason.CONFLICT)
        }
        observe(); publish()
    }

    /** Select only an already downloaded owned bundle. A historical foreign origin stays read-only. */
    suspend fun open(sessionId: String): PortResult<CookingFlowState> = run {
        val entry = read(); val id = uuid(sessionId)
        if (finalization() != null) mealFail(FailureReason.CONFLICT)
        checkActive(); val result = kitchen.cooking.read(access.lease, id); checkActive()
        mealValue(result) ?: mealFail(FailureReason.NOT_FOUND)
        write(entry, entry.value.copy(clock = now(), selected = id, screen = CookingFlowScreen.COOK))
        acknowledged = false; screenOverride = null
        observe(); publish()
    }

    /** Explicit owned server download; no refreshed read substitutes for a pending command receipt. */
    suspend fun refresh(): PortResult<CookingFlowState> = run {
        read(); val id = last.selected ?: mealFail(FailureReason.CONFLICT)
        checkActive(); mealValue(kitchen.cooking.download(access.lease, id)); checkActive()
        acknowledged = false; observe(); publish()
    }

    suspend fun moveTo(stepId: String) = edit(CookingEdit.MoveTo(stepId), false)
    suspend fun markStepComplete(stepId: String) = edit(CookingEdit.MarkStepComplete(stepId), false)
    suspend fun pause() = edit(CookingEdit.SetStatus(CookingStatus.PAUSED), true)
    suspend fun resume() = edit(CookingEdit.SetStatus(CookingStatus.ACTIVE), false)
    suspend fun abandon() = edit(CookingEdit.SetStatus(CookingStatus.ABANDONED), true)
    /** makeAgain/save/feedback are separate unresolved actions, never implied by finishing. */
    suspend fun complete() = edit(CookingEdit.Complete(makeAgain = false), false)

    /** Identity observation only. The facade keeps its exact actual-store/runtime pairing;
     * the operation-scoped cooking wrapper is never passed off as that native store. */
    internal fun matchesTimerComposition(timers: SessionCookingTimers): Boolean =
        timers.matchesComposition(access.store, boundary, access.lease.scope, origin)

    /** Fresh, read-only timer observation for the exact selected pin. Ticks do not restore the
     * controller or clear a previously acknowledged server receipt. No scheduler is invoked. */
    suspend fun inspectTimers(timers: SessionCookingTimers, sessionId: String): PortResult<CookingTimerSnapshot> = run {
        val entry = timerSelection(timers, sessionId)
        val pin = snapshot ?: mealFail(FailureReason.CONFLICT)
        val observation = mealValue(timers.observe(pin.id)); checkActive()
        checkSame(entry, read()); observe()
        matchTimerObservation(observation)
        if (observation.localRevision != pin.localRevision) mealFail(FailureReason.CONFLICT)
        checkSame(entry, read()); publish(); observation
    }

    /** One explicit timer mutation against the exact selected cooking revision. No new command ID,
     * automatic retry, generic accepting gate or alternative store/queue is created here. */
    suspend fun applyTimerAction(timers: SessionCookingTimers, sessionId: String, expectedLocalRevision: Long,
        commandId: String, action: CookingTimerAction): PortResult<CookingTimerSnapshot> = run {
        if (expectedLocalRevision <= 0) mealFail(FailureReason.INVALID_DATA)
        val command = uuid(commandId)
        val entry = timerSelection(timers, sessionId)
        val pin = snapshot ?: mealFail(FailureReason.CONFLICT)
        if (pin.localRevision != expectedLocalRevision) mealFail(FailureReason.CONFLICT)
        if (!pin.originMatches || pin.availability != CookingAvailability.AVAILABLE ||
            recipeId(pin.plan.document) in last.recalled || CookingFlowRecallGuard.contains(access, recipeId(pin.plan.document)))
            mealFail(FailureReason.FORBIDDEN)
        val starts = action is CookingTimerAction.Start || action is CookingTimerAction.Resume
        if (starts) requirePreferences()
        checkSame(entry, read())
        val token = generation; val operation = operationOwner
        val admission = CookingTimerActionAdmission(check = {
            if (closed || generation !== token || active !== token || operationOwner !== operation ||
                !boundary.isCurrent(access.lease) || last.selected != pin.id || snapshot?.id != pin.id ||
                snapshot?.plan?.id != pin.plan.id || last.start?.stage in setOf(CookingStartStage.PREPARED, CookingStartStage.QUEUED) ||
                finalization() != null) throw TimerFailure(FailureReason.STALE_SESSION)
        }, beforeSchedule = {
            try {
                checkActive(); checkSame(entry, read())
                if (starts) requirePreferences()
                checkSame(entry, read()); checkActive()
            } catch (failure: MealFailure) { throw TimerFailure(failure.reason) }
        })
        val observation = withContext(admission) {
            mealValue(timers.change(pin.id, expectedLocalRevision, command, action)); checkActive()
            mealValue(timers.observe(pin.id)).also { checkActive() }
        }
        checkSame(entry, read()); acknowledged = false; observe()
        matchTimerObservation(observation)
        checkSame(entry, read()); publish(); observation
    }

    private suspend fun timerSelection(timers: SessionCookingTimers, sessionId: String): CookingFlowEntry {
        if (!matchesTimerComposition(timers)) mealFail(FailureReason.STALE_SESSION)
        val id = uuid(sessionId)
        val entry = read()
        if (last.selected != id || finalization() != null || last.start?.stage in setOf(CookingStartStage.PREPARED, CookingStartStage.QUEUED))
            mealFail(FailureReason.CONFLICT)
        observe()
        val pin = snapshot ?: mealFail(FailureReason.CONFLICT)
        if (pin.id != id || !pin.originMatches) mealFail(FailureReason.CONFLICT)
        checkSame(entry, read())
        return entry
    }

    private fun matchTimerObservation(observation: CookingTimerSnapshot) {
        val pin = snapshot ?: mealFail(FailureReason.CONFLICT)
        if (observation.sessionId != pin.id || observation.planId != pin.plan.id.value ||
            observation.localRevision != pin.localRevision || observation.originMatches != pin.originMatches ||
            observation.availability != pin.availability || observation.status != pin.progress.status)
            mealFail(FailureReason.CONFLICT)
    }

    /** Only the selected session's immutable head intent is materialized/dispatched/applied. */
    suspend fun synchronize(): PortResult<CookingFlowState> = run {
        read(); observe()
        val selected = snapshot ?: mealFail(FailureReason.CONFLICT)
        val head = selected.pendingCommandIds.firstOrNull() ?: return@run publish()
        checkActive(); mealValue(kitchen.commands.recoverInterrupted(access.lease)); checkActive()
        checkActive(); var view = mealValue(kitchen.cooking.materializeNext(access.lease, selected.id)); checkActive()
        if (view?.commandId != head) mealFail(FailureReason.CONFLICT)
        if (view!!.phase == CommandPhase.NEEDS_RESOLUTION && view.issue in RESUMABLE) {
            checkActive(); mealValue(kitchen.commands.resumeAfterResolution(access.lease, head, view.localRevision)); checkActive()
            view = command(head) ?: mealFail(FailureReason.CONFLICT)
        }
        if (view.phase != CommandPhase.RECEIPT_READY) {
            val original = kitchen.commands.intent(access.lease, head); checkActive()
            val intent = mealValue(original) ?: mealFail(FailureReason.CONFLICT)
            if (!safeStop(intent.call)) requirePreferences()
            checkActive(); mealValue(kitchen.commands.dispatchAutomatic(access.lease, head)); checkActive()
        }
        if (command(head)?.phase == CommandPhase.RECEIPT_READY) {
            checkActive(); mealValue(kitchen.cooking.applyReceipt(access.lease, selected.id)); checkActive()
            acknowledged = true
        }
        observe(); publish()
    }

    /** Back is navigation only, even with uncertain commands or completed local progress. */
    suspend fun backToRecipe(): PortResult<CookingFlowState> {
        val result = withContext(dispatcher) {
            currentCoroutineContext().ensureActive()
            if (closed || !boundary.isCurrent(access.lease)) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
            generation = Any(); active = null; operationOwner = Any()
            screenOverride = CookingFlowScreen.RECIPE
            PortResult.Value(publish())
        }
        currentCoroutineContext().ensureActive()
        return if (closed || !boundary.isCurrent(access.lease)) PortResult.Failure(FailureReason.STALE_SESSION) else result
    }

    suspend fun close(): PortResult<Unit> = withContext(dispatcher) {
        closed = true; redact(); composition.release(borrower); subscription.close(); PortResult.Value(Unit)
    }

    /** Cached return to the selected pin only. Never selects, writes, re-acknowledges or starts. */
    suspend fun returnToCooking(): PortResult<CookingFlowState> {
        val result = withContext(dispatcher) {
            currentCoroutineContext().ensureActive()
            if (closed || !boundary.isCurrent(access.lease)) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
            val pin = snapshot
            if (pin == null || pin.id != last.selected || last.start?.stage in setOf(CookingStartStage.PREPARED, CookingStartStage.QUEUED) ||
                mutable.value.plan?.id != pin.plan.id) return@withContext PortResult.Failure(FailureReason.CONFLICT)
            generation = Any(); active = null; operationOwner = Any()
            screenOverride = CookingFlowScreen.COOK
            PortResult.Value(publish())
        }
        currentCoroutineContext().ensureActive()
        return if (closed || !boundary.isCurrent(access.lease)) PortResult.Failure(FailureReason.STALE_SESSION) else result
    }

    private suspend fun edit(edit: CookingEdit, stop: Boolean): PortResult<CookingFlowState> = run {
        read(); observe()
        if (finalization() != null) mealFail(FailureReason.CONFLICT)
        if (last.start?.stage in setOf(CookingStartStage.PREPARED, CookingStartStage.QUEUED)) mealFail(FailureReason.CONFLICT)
        if (!stop) requirePreferences()
        val current = snapshot ?: mealFail(FailureReason.CONFLICT)
        checkActive(); mealValue(kitchen.cooking.edit(access.lease, current.id, current.localRevision, newId(), edit)); checkActive()
        if (!stop) requirePreferences()
        acknowledged = false; observe(); publish()
    }

    private suspend fun sendStart(entry: CookingFlowEntry): CookingFlowState {
        val start = entry.value.start ?: mealFail(FailureReason.CONFLICT)
        val intent = kitchen.commands.intent(access.lease, start.id); checkActive()
        if (!matches(mealValue(intent) ?: mealFail(FailureReason.CONFLICT), start)) mealFail(FailureReason.CONFLICT)
        // This fresh flow ACK is additional to queue reservation/dispatch acknowledgement.
        write(entry, entry.value.copy(clock = now()))
        checkActive(); mealValue(kitchen.commands.dispatchConfirmed(access.lease, start.id)); checkActive()
        val current = read()
        return if (command(start.id)?.phase == CommandPhase.RECEIPT_READY) attach(current)
        else { observe(); publish(CookingFlowIssue.PENDING_SYNC) }
    }

    private suspend fun attach(entry: CookingFlowEntry): CookingFlowState {
        val start = entry.value.start ?: mealFail(FailureReason.CONFLICT)
        val intent = kitchen.commands.intent(access.lease, start.id); checkActive()
        if (!matches(mealValue(intent) ?: mealFail(FailureReason.CONFLICT), start)) mealFail(FailureReason.CONFLICT)
        val receiptResult = kitchen.commands.receipt(access.lease, start.id); checkActive()
        val receipt = mealValue(receiptResult) ?: mealFail(FailureReason.CONFLICT)
        if (receipt.reply.status != 201 || receipt.command.operationId != "createCookSession") mealFail(FailureReason.INVALID_DATA)
        val body = bound("createCookSession", receipt.reply, policy.maxSessionBytes)
        val server = CookSessionWire.from(body); kiEtag(body, receipt.reply.etag ?: mealFail(FailureReason.INVALID_DATA))
        if (uuid(server.planId.value) != start.planId || server.status != "active" ||
            kiNumber(body.json().jsonObject.getValue("deviceSequence")) != "0") mealFail(FailureReason.INVALID_DATA)
        val id = uuid(server.id.value)
        // Preserve the actual retained receipt observation before the separate download can
        // fail. This exposes original-ID retry, not a selected pin or an applied receipt.
        commandViews = commandViews.filterNot { it.commandId == start.id } + receipt.command
        checkActive(); val downloaded = mealValue(kitchen.cooking.download(access.lease, id)); checkActive()
        if (!downloaded.originMatches || !equal(downloaded.plan.document, start.plan) ||
            !equal(downloaded.remote.document, body) || downloaded.availability != CookingAvailability.AVAILABLE ||
            downloaded.pendingCommandIds.isNotEmpty()) mealFail(FailureReason.CONFLICT)
        checkSame(entry, read())
        val value = entry.value.copy(clock = now(), selected = id, screen = CookingFlowScreen.COOK, start = start.attached(body))
        val change = put(entry, value)
        val proof = CookingStartFinalization(start, change.payload, entry.record!!.revision, receipt.command.localRevision, receipt.command.attempts)
        CookingFlowFinalizationGuard.retain(access, boundary, proof); applyingStart = proof
        try { checkActive(); mealValue(kitchen.commands.applyReceipt(access.lease, start.id, receipt.command.localRevision, listOf(change))); checkActive() }
        finally { applyingStart = null }
        read(); observe(); CookingFlowFinalizationGuard.clear(access)
        acknowledged = true; screenOverride = null; return publish()
    }

    private suspend fun acknowledgeAttachment(entry: CookingFlowEntry): CookingFlowState {
        val start = entry.value.start ?: mealFail(FailureReason.CONFLICT)
        val proof = finalization() ?: mealFail(FailureReason.CONFLICT)
        val record = entry.record ?: mealFail(FailureReason.CONFLICT)
        if (proof.start.id != start.id || record.revision <= proof.priorDomainRevision || !bytes(record.payload, proof.payload)) mealFail(FailureReason.CONFLICT)
        val body = start.receipt ?: mealFail(FailureReason.CONFLICT)
        val id = uuid(CookSessionWire.from(body).id.value)
        if (entry.value.selected != id) mealFail(FailureReason.CONFLICT)
        val before = command(start.id) ?: mealFail(FailureReason.CONFLICT)
        if (before.phase != CommandPhase.APPLIED || before.operationId != "createCookSession" || before.localRevision <= proof.priorReceiptRevision || before.attempts != proof.attempts) mealFail(FailureReason.CONFLICT)
        val archiveMutation = proof.archive ?: mealFail(FailureReason.CONFLICT)
        val archived = archive(proof, before.localRevision)
        checkActive(); val pin = mealValue(kitchen.cooking.read(access.lease, id)) ?: mealFail(FailureReason.CONFLICT); checkActive()
        if (!pin.originMatches || !equal(pin.plan.document, start.plan) || !equal(pin.remote.document, body) || pin.pendingCommandIds.isNotEmpty()) mealFail(FailureReason.CONFLICT)
        checkSame(entry, read())
        val after = command(start.id) ?: mealFail(FailureReason.CONFLICT)
        if (after.phase != before.phase || after.localRevision != before.localRevision) mealFail(FailureReason.CONFLICT)
        write(entry, entry.value)
        val repeated = command(start.id) ?: mealFail(FailureReason.CONFLICT)
        if (repeated.phase != before.phase || repeated.localRevision != before.localRevision) mealFail(FailureReason.CONFLICT)
        val repeatedArchive = archive(proof, repeated.localRevision)
        if (!bytes(archived.payload, repeatedArchive.payload) || archiveMutation.key.id != start.id) mealFail(FailureReason.CONFLICT)
        observe(); CookingFlowFinalizationGuard.clear(access); acknowledged = true; return publish()
    }

    private suspend fun validateCurrent(start: CookingStart, fresh: Boolean) {
        requirePreferences()
        val before = mealSource(start.planId)
        if (before.revision != start.sourceRevision) mealFail(FailureReason.CONFLICT)
        val data = mealCodec.decode(before.payload)
        if (!equal(data.plans[data.selected].body, start.plan) || data.preferences?.let { equal(it, start.preferences) } != true)
            mealFail(FailureReason.CONFLICT)
        requireReady(start.plan)
        val version = recipeId(start.plan)
        if (version in data.recalledVersions || version in last.recalled || CookingFlowRecallGuard.contains(access, version)) {
            CookingFlowRecallGuard.mark(access, boundary, version); previewBlocked = true; mealFail(FailureReason.FORBIDDEN)
        }
        checkActive(); val recall = kitchen.cooking.hasRecipeRecall(access.lease, version); checkActive()
        if (mealValue(recall)) { CookingFlowRecallGuard.mark(access, boundary, version); previewBlocked = true; mealFail(FailureReason.FORBIDDEN) }
        if (fresh) {
            if (!online()) mealFail(FailureReason.OFFLINE)
            val pref = fetch(ApiCall("getPreferences"), "Preference", policy.maxPreferencesBytes)
            if (!equal(pref, start.preferences)) mealFail(FailureReason.CONFLICT)
            val plan = fetch(ApiCall("getPlan", pathParameters = mapOf("planId" to start.planId)), "Plan", MealFlowCodec.MAX_PLAN_BYTES, version)
            if (PlanWire.from(plan).status == "recalled" || (PlanWire.from(plan).recipeSnapshot as? WireField.Value)?.value?.reviewStatus == "recalled")
                rememberRecall(version)
            requireReady(plan)
            if (!equal(plan, start.plan)) mealFail(FailureReason.CONFLICT)
            val repeat = fetch(ApiCall("getPreferences"), "Preference", policy.maxPreferencesBytes)
            if (!equal(pref, repeat)) mealFail(FailureReason.CONFLICT)
        }
        requirePreferences()
        val after = mealSource(start.planId)
        if (before.revision != after.revision || !bytes(before.payload, after.payload)) mealFail(FailureReason.CONFLICT)
    }

    private suspend fun mealSource(id: String): PrivateRecord {
        checkActive(); val result = access.store.read(access.lease.scope, mealKey); checkActive()
        val record = mealValue(result) ?: mealFail(FailureReason.CONFLICT)
        if (record.schemaVersion != 1) mealFail(FailureReason.INVALID_DATA)
        val data = mealCodec.decode(record.payload)
        if (data.command != null || data.draft == null || data.preferences == null || data.draft.preferencesPendingSync) mealFail(FailureReason.CONFLICT)
        val plan = data.plans.getOrNull(data.selected) ?: mealFail(FailureReason.CONFLICT)
        if (uuid(PlanWire.from(plan.body).id.value) != id ||
            !equal(mealValue(MealRequestBuilder().build(data.draft, data.preferences)), plan.request)) mealFail(FailureReason.CONFLICT)
        return record
    }

    private fun requireReady(document: WireDocument) {
        codec.schema("Plan", document)
        val plan = PlanWire.from(document)
        val recipe = (plan.recipeSnapshot as? WireField.Value)?.value ?: mealFail(FailureReason.CONFLICT)
        if (plan.status != "ready" || plan.sourcePostId !is WireField.Missing || recipe.reviewStatus != "published" ||
            recipe.reviewedAt !is WireField.Value || recipe.ingredients.isEmpty() || recipe.steps.isEmpty() ||
            (plan.recipeVersionId as? WireField.Value)?.value?.value?.lowercase() != recipe.id.value.lowercase()) mealFail(FailureReason.CONFLICT)
    }
    private fun recipeId(plan: WireDocument) = uuid(((PlanWire.from(plan).recipeVersionId as? WireField.Value)?.value ?: mealFail(FailureReason.INVALID_DATA)).value)

    /** Preserve matching negative evidence even if the repository subsequently rejects a changed
     * version/body or its durable marker acknowledgement fails. Let its own observer also run. */
    private fun observeRecall(call: ApiCall, reply: ApiReply) {
        val pinned = when (call.operationId) {
            "getPlan" -> last.start?.plan?.takeIf { PlanWire.from(it).id.value == call.pathParameters["planId"] }
                ?: snapshot?.plan?.document?.takeIf { PlanWire.from(it).id.value == call.pathParameters["planId"] }
            "updateCookSession", "completeCookSession" -> snapshot?.takeIf { it.id == call.pathParameters["sessionId"] }?.plan?.document
            else -> null
        } ?: return
        val body = bound(call.operationId, reply)
        val recalled = if (reply.status !in 200..299)
            (body.field("code") as? WireField.Value)?.value?.stringOrNull() == "RECIPE_RECALLED"
        else if (call.operationId == "getPlan") PlanWire.from(body).let {
            it.id.value == call.pathParameters["planId"] && (it.status == "recalled" || (it.recipeSnapshot as? WireField.Value)?.value?.reviewStatus == "recalled")
        } else false
        if (recalled) { CookingFlowRecallGuard.mark(access, boundary, recipeId(pinned)); previewBlocked = true }
    }

    private suspend fun rememberRecall(version: String) {
        CookingFlowRecallGuard.mark(access, boundary, version)
        val entry = read()
        if (version !in entry.value.recalled) {
            if (entry.value.recalled.size >= 128) mealFail(FailureReason.RATE_LIMITED)
            write(entry, entry.value.copy(clock = now(), recalled = entry.value.recalled + version))
        }
        mealFail(FailureReason.FORBIDDEN)
    }

    private suspend fun fetch(call: ApiCall, schema: String, limit: Int, pinnedRecipe: String? = null): WireDocument {
        checkActive(); val result = transport.execute(access.lease, call); checkActive()
        val reply = mealValue(result); val body = bound(call.operationId, reply, limit)
        if (call.operationId == "getPlan" && pinnedRecipe != null && reply.status !in 200..299 &&
            (body.field("code") as? WireField.Value)?.value?.stringOrNull() == "RECIPE_RECALLED") rememberRecall(pinnedRecipe)
        if (reply.status != 200) mealFail(when (reply.status) { 401 -> FailureReason.UNAUTHENTICATED; 403 -> FailureReason.FORBIDDEN
            404 -> FailureReason.NOT_FOUND; 409, 410, 412, 422 -> FailureReason.CONFLICT; else -> FailureReason.UNAVAILABLE })
        codec.schema(schema, body); kiEtag(body, reply.etag ?: mealFail(FailureReason.INVALID_DATA)); return body
    }
    private fun bound(operation: String, reply: ApiReply, limit: Int = 262_144): WireDocument {
        val raw = reply.body?.copyForCodec() ?: mealFail(FailureReason.INVALID_DATA)
        if (raw.size > limit || CanonicalResponseBinder().bind(operation, reply.status, raw, reply.contentType, reply.traceId) !is ResponseBindingResult.Accepted)
            mealFail(FailureReason.INVALID_DATA)
        return WireDocument.decode(raw, WireLimits(limit, 20))
    }

    private suspend fun observe() {
        checkActive(); preferencesBlocked = KitchenInputPreferenceGuard.pending(access); checkActive()
        previewBlocked = last.start?.let {
            checkActive(); val recall = kitchen.cooking.hasRecipeRecall(access.lease, recipeId(it.plan)); checkActive(); mealValue(recall)
        } ?: false
        snapshot = last.selected?.let { id -> checkActive(); val value = kitchen.cooking.read(access.lease, id); checkActive(); mealValue(value) }
        val ids = snapshot?.pendingCommandIds.orEmpty() + listOfNotNull(last.start?.id)
        commandViews = ids.distinct().mapNotNull { command(it) }
    }
    private suspend fun requirePreferences() {
        checkActive(); preferencesBlocked = KitchenInputPreferenceGuard.pending(access); checkActive()
        if (preferencesBlocked) mealFail(FailureReason.CONFLICT)
    }
    private suspend fun command(id: String): CommandView? { checkActive(); val result = kitchen.commands.command(access.lease, id); checkActive(); return mealValue(result) }
    private fun finalization() = CookingFlowFinalizationGuard.get(access)
    private suspend fun archive(proof: CookingStartFinalization, revision: Long): PrivateRecord {
        val expected = proof.archive ?: mealFail(FailureReason.CONFLICT)
        checkActive(); val result = store.read(access.lease.scope, expected.key); checkActive()
        val record = mealValue(result) ?: mealFail(FailureReason.CONFLICT)
        if (record.revision != revision || record.schemaVersion != expected.schemaVersion || !bytes(record.payload, expected.payload)) mealFail(FailureReason.CONFLICT)
        return record
    }
    private suspend fun newId(): String {
        checkActive(); val id = uuid(ids.next()); checkActive()
        if (id == last.start?.id || command(id) != null) mealFail(FailureReason.CONFLICT)
        return id
    }
    private suspend fun read(): CookingFlowEntry {
        checkActive(); val result = access.store.read(access.lease.scope, key); checkActive()
        val record = mealValue(result)
        if (record != null && record.schemaVersion != 1) mealFail(FailureReason.INVALID_DATA)
        val data = record?.let { codec.decode(it.payload) } ?: CookingFlowRecord(now())
        if (now() < data.clock || data.start?.created?.let { it > data.clock } == true) mealFail(FailureReason.CONFLICT)
        last = data; return CookingFlowEntry(record, data)
    }
    private fun put(before: CookingFlowEntry, value: CookingFlowRecord) = StoreMutation.Put(key, before.record?.revision, 1, codec.encode(value))
    private suspend fun write(before: CookingFlowEntry, value: CookingFlowRecord): CookingFlowEntry {
        val payload = codec.encode(value); val prior = before.record?.revision ?: 0
        if (prior == Long.MAX_VALUE) mealFail(FailureReason.STORAGE_FAILURE)
        checkActive(); val result = access.store.commit(access.lease.scope, listOf(StoreMutation.Put(key, before.record?.revision, 1, payload))); checkActive()
        val receipt = mealValue(result)
        if (receipt.keys != setOf(key) || receipt[key] != prior + 1) mealFail(FailureReason.STORAGE_FAILURE)
        val read = read()
        val observed = read.record ?: mealFail(FailureReason.CONFLICT)
        if (observed.revision != prior + 1 || !bytes(observed.payload, payload)) mealFail(FailureReason.CONFLICT)
        return read
    }
    private fun checkSame(a: CookingFlowEntry, b: CookingFlowEntry) {
        if (a.record?.revision != b.record?.revision || (a.record != null && b.record != null && !bytes(a.record.payload, b.record.payload))) mealFail(FailureReason.CONFLICT)
    }
    private fun bytes(a: PrivateBytes, b: PrivateBytes) = a.copyForCodec().contentEquals(b.copyForCodec())
    private fun matches(intent: CommandIntent, start: CookingStart): Boolean {
        val call = intent.call
        return intent.commandId == start.id && intent.originBinding == origin && intent.dependencyCommandIds.isEmpty() &&
            call.operationId == "createCookSession" && call.pathParameters.isEmpty() && call.queryParameters.isEmpty() &&
            call.ifMatch == null && call.idempotencyKey?.use { it } == start.id && call.body?.let { bytes(it, PrivateBytes(start.body().encodeUtf8())) } == true
    }
    private fun safeStop(call: ApiCall): Boolean {
        if (call.operationId != "updateCookSession") return false
        val body = call.body ?: return false
        val status = WireDocument.decode(body.copyForCodec()).json().jsonObject["status"]?.jsonPrimitive?.content
        return status in setOf("paused", "abandoned")
    }
    private fun now() = clock.nowMillis().also { if (it < 0) mealFail(FailureReason.INVALID_DATA) }
    private fun online() = composition.online()
    private suspend fun checkActive() {
        currentCoroutineContext().ensureActive()
        if (closed || active !== generation || !boundary.isCurrent(access.lease)) mealFail(FailureReason.STALE_SESSION)
    }

    private fun publish(issue: CookingFlowIssue = CookingFlowIssue.NONE, failure: FailureReason? = null): CookingFlowState {
        if (closed || !boundary.isCurrent(access.lease)) return CookingFlowState.unavailable().also { mutable.value = it }
        val pin = snapshot
        val usable = pin?.availability == CookingAvailability.AVAILABLE && pin.originMatches
        val pending = commandViews.filter { it.phase !in setOf(CommandPhase.APPLIED, CommandPhase.DISCARDED) }.map {
            CookingFlowPending(it.commandId, it.operationId, it.phase.name, it.attempts, it.issue.name, it.retryAtMillis,
                it.phase !in setOf(CommandPhase.IN_FLIGHT, CommandPhase.NEEDS_RESOLUTION) || it.issue in RESUMABLE,
                it.attempts == 0 && it.operationId == "createCookSession")
        } + listOfNotNull(finalization()?.takeIf { proof -> commandViews.any { it.commandId == proof.start.id && it.phase == CommandPhase.APPLIED } }?.let {
            CookingFlowPending(it.start.id, "createCookSession", "FINALIZATION_REQUIRED", it.attempts, CommandIssue.OUTCOME_UNKNOWN.name, 0, true, false)
        })
        val localPending = pin?.pendingCommandIds?.isNotEmpty() == true
        val starting = last.start?.stage == CookingStartStage.QUEUED
        val startBlocksActions = last.start?.stage in setOf(CookingStartStage.PREPARED, CookingStartStage.QUEUED) || finalization() != null
        val safe = pin?.takeIf { it.availability in setOf(CookingAvailability.AVAILABLE, CookingAvailability.CONFLICT) && recipeId(it.plan.document) !in last.recalled && !CookingFlowRecallGuard.contains(access, recipeId(it.plan.document)) }
        val active = pin?.progress?.status in setOf(CookingStatus.ACTIVE, CookingStatus.PAUSED)
        val resolvedIssue = when {
            preferencesBlocked -> CookingFlowIssue.PREFERENCES_PENDING
            pin?.availability == CookingAvailability.RECALLED -> CookingFlowIssue.RECALLED
            pin?.availability == CookingAvailability.PERSONAL_UNREVIEWED -> CookingFlowIssue.PERSONAL_UNREVIEWED
            pin?.availability == CookingAvailability.INCOMPLETE -> CookingFlowIssue.DOWNLOAD_INCOMPLETE
            pin != null && !pin.originMatches -> CookingFlowIssue.ORIGIN_CHANGED
            issue != CookingFlowIssue.NONE -> issue
            finalization() != null -> CookingFlowIssue.OUTCOME_UNKNOWN
            starting || localPending -> CookingFlowIssue.PENDING_SYNC
            else -> CookingFlowIssue.NONE
        }
        val phase = when {
            failure == FailureReason.OFFLINE -> CookingFlowPhase.OFFLINE
            failure != null -> CookingFlowPhase.ERROR
            starting -> CookingFlowPhase.START_PENDING
            last.start?.stage == CookingStartStage.PREPARED -> CookingFlowPhase.START_CONFIRMATION
            pin?.availability == CookingAvailability.CONFLICT -> CookingFlowPhase.CONFLICT
            pin != null && safe == null -> CookingFlowPhase.UNAVAILABLE
            pin?.progress?.status == CookingStatus.ACTIVE -> CookingFlowPhase.COOKING
            pin?.progress?.status == CookingStatus.PAUSED -> CookingFlowPhase.PAUSED
            pin?.progress?.status == CookingStatus.COMPLETED -> CookingFlowPhase.COMPLETED
            pin?.progress?.status == CookingStatus.ABANDONED -> CookingFlowPhase.ABANDONED
            else -> CookingFlowPhase.IDLE
        }
        val preview = last.start?.takeIf { it.stage == CookingStartStage.PREPARED && !previewBlocked }?.plan?.takeIf {
            recipeId(it) !in last.recalled && !CookingFlowRecallGuard.contains(access, recipeId(it))
        }?.let(PlanWire::from)
        // Consent is about the exact prepared proposal, never an older selected cooking pin.
        // If that proposal is blocked, null must not fall back to the older recipe's instructions.
        val visiblePlan = if (last.start?.stage == CookingStartStage.PREPARED) preview else safe?.plan
        return CookingFlowState(phase, screenOverride ?: last.screen, visiblePlan, safe, pending,
            usable && safe != null && active && !preferencesBlocked && !startBlocksActions,
            usable && safe != null && active && !startBlocksActions,
            usable && safe != null && active && !preferencesBlocked && !startBlocksActions,
            !acknowledged, acknowledged && !localPending && !startBlocksActions, resolvedIssue, failure).also { mutable.value = it }
    }

    private suspend fun <T> run(action: suspend () -> T): PortResult<T> {
        var owned: Any? = null; var operation: Any? = null
        try { val result = withContext(dispatcher) {
            currentCoroutineContext().ensureActive()
            if (closed || !boundary.isCurrent(access.lease)) mealFail(FailureReason.STALE_SESSION)
            val token = generation
            mutex.withLock {
                if (token !== generation) mealFail(FailureReason.STALE_SESSION)
                owned = token; active = token; operation = Any().also { operationOwner = it }
                checkActive(); claim()
                try { composition.operate(borrower) { val result = action(); checkActive(); PortResult.Value(result) } }
                finally { if (active === token) active = null }
            }
        }
            currentCoroutineContext().ensureActive()
            if (closed || owned !== generation || operation !== operationOwner || !boundary.isCurrent(access.lease)) return PortResult.Failure(FailureReason.STALE_SESSION)
            return result
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + dispatcher) { if (owned === generation && operation === operationOwner) {
                generation = Any(); active = null; acknowledged = false
                publish(CookingFlowIssue.OUTCOME_UNKNOWN, FailureReason.OUTCOME_UNKNOWN)
            } }; throw cancelled
        } catch (error: Exception) {
            val reason = if (closed || owned !== generation || !boundary.isCurrent(access.lease)) FailureReason.STALE_SESSION else
                (error as? MealFailure)?.reason ?: if (error is WireDecodingException || error is kotlinx.serialization.SerializationException) FailureReason.INVALID_DATA else FailureReason.STORAGE_FAILURE
            withContext(NonCancellable + dispatcher) { if (owned === generation && operation === operationOwner) {
                acknowledged = false
                publish(when (reason) { FailureReason.OUTCOME_UNKNOWN -> CookingFlowIssue.OUTCOME_UNKNOWN
                    FailureReason.OFFLINE -> CookingFlowIssue.OFFLINE; FailureReason.CONFLICT -> CookingFlowIssue.RECONCILIATION_REQUIRED
                    FailureReason.INVALID_DATA -> CookingFlowIssue.INVALID_INPUT; FailureReason.RATE_LIMITED -> CookingFlowIssue.RETRY_LATER
                    else -> CookingFlowIssue.STORAGE }, reason)
            } }
            return PortResult.Failure(reason)
        }
    }
    private fun claim() {
        if (claimed) return
        if (owners.any { it.lease === access.lease && it.origin == origin }) mealFail(FailureReason.CONFLICT)
        owners += Owner(access.lease, origin, this); claimed = true
    }
    private fun redact() {
        generation = Any(); active = null; operationOwner = null; last = CookingFlowRecord(0)
        snapshot = null; commandViews = emptyList(); acknowledged = false; preferencesBlocked = false; previewBlocked = false; screenOverride = null; applyingStart = null
        if (claimed) { owners.removeAll { it.controller === this }; claimed = false }
        mutable.value = CookingFlowState.unavailable()
    }
    private class Owner(val lease: SessionLease, val origin: String, val controller: CookingFlowController)
    private companion object {
        val owners = mutableListOf<Owner>()
        val RESUMABLE = setOf(CommandIssue.AUTH_REQUIRED, CommandIssue.NOT_CONFIGURED, CommandIssue.DOMAIN_RECHECK_REQUIRED,
            CommandIssue.OFFLINE, CommandIssue.TEMPORARILY_UNAVAILABLE)
    }
}

/** Exact failed-ack retry evidence, owned by the actual lease rather than an editor lifetime. */
private object CookingFlowFinalizationGuard {
    private class Pending(val lease: SessionLease, val origin: String, var proof: CookingStartFinalization) {
        var subscription: SessionInvalidationSubscription? = null
    }
    private val pending = mutableListOf<Pending>()
    fun get(access: AuthenticatedMealPlanningAccess) = pending.singleOrNull { it.lease === access.lease && it.origin == access.origin }?.proof
    fun retain(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary, proof: CookingStartFinalization) {
        val old = pending.singleOrNull { it.lease === access.lease && it.origin == access.origin }
        if (old != null) { if (old.proof.start.id != proof.start.id) mealFail(FailureReason.CONFLICT); old.proof = proof; return }
        val entry = Pending(access.lease, access.origin, proof); pending += entry
        entry.subscription = boundary.onInvalidated(access.lease) { pending.remove(entry) }
    }
    fun clear(access: AuthenticatedMealPlanningAccess) {
        val removed = pending.filter { it.lease === access.lease && it.origin == access.origin }
        pending.removeAll(removed.toSet()); removed.forEach { it.subscription?.close() }
    }
}

/** Learned negative evidence survives controller close, but not the actual lease lifetime. */
private object CookingFlowRecallGuard {
    private class Fence(val lease: SessionLease, val origin: String, val versions: MutableSet<String>)
    private val fences = mutableListOf<Fence>()
    fun contains(access: AuthenticatedMealPlanningAccess, version: String) = fences.any {
        it.lease === access.lease && it.origin == access.origin && version in it.versions
    }
    fun mark(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary, version: String) {
        val existing = fences.firstOrNull { it.lease === access.lease && it.origin == access.origin }
        if (existing != null) { existing.versions += version; return }
        val fence = Fence(access.lease, access.origin, mutableSetOf(version)); fences += fence
        boundary.onInvalidated(access.lease) { fences.remove(fence) }
    }
}
