package com.feedme.mealflow.timers

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.kitchen.*
import com.feedme.session.*
import com.feedme.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Mandatory trusted native integration. No implementation/default is supplied by this foundation.
 * Only opaque native ticket + deadline crosses this boundary; no private labels/account data.
 */
fun interface CookingTimerScheduler { suspend fun schedule(ticket: NativeWorkTicket, deadlineMillis: Long): PortResult<Unit> }

/** Construct before the runtime; deny-all until exactly one genuine current-access facade binds.
 * A closed/invalidated facade never rebinds. A successor requires a new explicit policy/runtime.
 */
class CookingTimerExecutionPolicy : NativeWorkExecutionPolicy {
    private var owner: SessionCookingTimers? = null
    internal fun bind(value: SessionCookingTimers) { if (owner != null) fail(FailureReason.CONFLICT); owner = value }
    override suspend fun allowed(scope: StorageScope, originBinding: String, logicalId: String, ticket: NativeWorkTicket): PortResult<Boolean> =
        owner?.allows(scope, originBinding, logicalId, ticket) ?: PortResult.Value(false)
}

/** Actual runtime-owned facade, not a scheduler, verifier or server synchronizer. Use the same
 * trusted serialized dispatcher/boundary as runtime and kitchen. Existing mappings on a new facade
 * are cleanup-only: no automatic reinstallation or inferred alert receipt after restart/unknown ACK.
 * Closing this borrowed facade fences delivery, but does not close/retire its runtime or stores.
 */
class SessionCookingTimers private constructor(
    private val runtime: PrivateSessionRuntime, private val access: PrivateSessionAccess,
    private val kitchen: PrivateKitchenSession, private val boundary: SessionBoundary,
    private val dispatcher: CoroutineDispatcher, private val clock: CookingTimerClock,
    private val scheduler: CookingTimerScheduler,
) {
    private val mutex = Mutex()
    private var closed = false
    private var flowClaimed = false
    private class Armed(val sessionId: String, val timerId: String, val generation: Long, val proof: String)
    private val armed = mutableMapOf<String, Armed>()
    private val denied = mutableSetOf<String>()
    private val invalidation = boundary.onInvalidated(access.lease) { closed = true; armed.clear() }
    private val repo get() = kitchen.cooking

    /** Identity-only integration observation, not current lease, delivery or scheduling authority. */
    fun matchesIntegration(clock: CookingTimerClock, scheduler: CookingTimerScheduler): Boolean =
        this.clock === clock && this.scheduler === scheduler

    /** Same-module cooking admission pairs observations with the actual borrowed native access.
     * Identity only; every operation still checks current runtime/access/lease independently.
     */
    internal fun matchesComposition(store: PrivateStateStore, boundary: SessionBoundary,
        scope: StorageScope, origin: String): Boolean = access.store === store && this.boundary === boundary &&
        access.scope == scope && access.originBinding == origin

    /** The retained UI owner cannot be replaced to forget an unresolved action in this facade. */
    internal fun claimFlow(): PortResult<Unit> = when {
        closed || !boundary.isCurrent(access.lease) -> PortResult.Failure(FailureReason.STALE_SESSION)
        flowClaimed -> PortResult.Failure(FailureReason.CONFLICT)
        else -> { flowClaimed = true; PortResult.Value(Unit) }
    }

    /** Typed identity is resolved only from this owner's acknowledged armed entry. The Android
     * adapter calls this inside runLocalEffect, never as an independent delivery authorization.
     */
    internal fun dueIdentity(ticket: NativeWorkTicket): CookingTimerDue? = armed[ticket.id]?.takeIf {
        !closed && boundary.isCurrent(access.lease) && it.timerId !in denied && ticket.kind == NativeWorkKind.TIMER
    }?.let { CookingTimerDue(it.sessionId, it.timerId, it.generation) }

    /** One successful read bracket supplies revision, pinned context and all timer rows together.
     * No queue materialization, transport, domain mutation, scheduling or delivery occurs.
     */
    suspend fun observe(sessionId: String): PortResult<CookingTimerSnapshot> = guarded { observation(load(sessionId)) }

    suspend fun inspect(sessionId: String): PortResult<List<CookingTimerView>> = guarded {
        views(load(sessionId))
    }

    private fun views(current: Loaded): List<CookingTimerView> {
        val now = clock.read()
        val ids = (current.snapshot.progress.timers.map { it.timerId.value.lowercase() } + current.slots.map { it.id }).distinct()
        return ids.map { id ->
            val slot = current.slots.firstOrNull { it.id == id }
            val timer = current.snapshot.progress.timers.firstOrNull { it.timerId.value.lowercase() == id }
            CookingTimerView(id, slot?.generation ?: 0, timer?.let { value(CookingTimerReducer.observe(it.document, slot?.anchor, now)) },
                slot?.phase ?: CookingTimerAlertPhase.QUIET,
                slot != null && slot.phase == CookingTimerAlertPhase.ARMED && current.metadata?.matchesTimers == true &&
                    slot.ticket?.let { armed[it]?.proof == proof(current, slot) } == true && slot.id !in denied,
                timer?.stepId?.value, timer?.status, timer?.durationSeconds?.jsonToken?.toLongOrNull(),
                slot?.ticket?.let { native ->
                    when (val observed = (scheduler as? CookingTimerDeliveryObservations)?.observeDelivery(ticket(native))) {
                        is PortResult.Value -> observed.value
                        else -> CookingTimerDeliveryPhase.UNAVAILABLE
                    }
                } ?: CookingTimerDeliveryPhase.NOT_INSTALLED)
        }
    }

    private fun observation(current: Loaded): CookingTimerSnapshot {
        val snapshot = current.snapshot
        val recipe = (snapshot.plan.recipeSnapshot as? WireField.Value)?.value
        return CookingTimerSnapshot(snapshot.id, snapshot.localRevision, snapshot.plan.id.value,
            (snapshot.plan.recipeVersionId as? WireField.Value)?.value?.value,
            snapshot.progress.currentStepId, snapshot.availability, snapshot.progress.status,
            snapshot.originMatches, snapshot.pendingCommandIds.size,
            recipe?.steps.orEmpty().takeIf { snapshot.availability == CookingAvailability.AVAILABLE }.orEmpty().map { step -> CookingTimerStep(step.stepId.value, step.instruction,
                (step.durationSeconds as? WireField.Value)?.value?.jsonToken?.toLongOrNull()
                    ?.takeIf { it in 1..CookingTimerReducer.MAX_DURATION_SECONDS }) }, views(current))
    }

    /** One explicit command identity; no new id is allocated by retries or observation. */
    suspend fun change(sessionId: String, expectedLocalRevision: Long, commandId: String,
        action: CookingTimerAction): PortResult<List<CookingTimerView>> = guarded {
        mutex.withLock {
            val timerId = CookingTimerLedger.id(action.timerId)
            // Synchronous local delivery fence precedes the first suspending domain mutation.
            denied += timerId
            val old = load(sessionId)
            eligible(old.snapshot)
            if (old.snapshot.localRevision != expectedLocalRevision || old.metadata?.matchesTimers == false) fail(FailureReason.CONFLICT)
            val prior = old.slots.firstOrNull { it.id == timerId }
            if (prior?.phase in setOf(CookingTimerAlertPhase.DESIRED, CookingTimerAlertPhase.MAPPED, CookingTimerAlertPhase.CANCELLING) ||
                (prior?.phase == CookingTimerAlertPhase.ARMED && prior.ticket !in armed)) fail(FailureReason.CONFLICT)
            val recipe = (old.snapshot.plan.recipeSnapshot as? WireField.Value)?.value ?: fail(FailureReason.FORBIDDEN)
            val steps = recipe.document.json().jsonObject.getValue("steps").jsonArray.map { it.jsonObject.getValue("stepId").jsonPrimitive.content }.toSet()
            val reduced = value(CookingTimerReducer.reduce(old.snapshot.progress.timers.map { it.document }, prior?.anchor, action, steps, clock.read()))
            val generation = prior?.generation ?: 0
            if (generation == Long.MAX_VALUE || (prior == null && old.slots.size >= 32)) fail(FailureReason.CONFLICT)
            val running = reduced.anchor != null
            val slot = TimerSlot(timerId, generation + 1, CookingTimerLedger.id(commandId),
                if (prior?.ticket != null) CookingTimerAlertPhase.CANCELLING else if (running) CookingTimerAlertPhase.DESIRED else CookingTimerAlertPhase.QUIET,
                reduced.anchor, prior?.ticket)
            val slots = replace(old.slots, slot)
            currentCoroutineContext()[CookingTimerFlowLifetime]?.mutationAttempted = true
            value(await { repo.editTimersWithMetadata(access.lease, old.snapshot.id, expectedLocalRevision, commandId,
                reduced.timers, old.metadata?.record, CookingTimerLedger.encode(slots)) })
            var current = load(sessionId)
            if (slot.ticket != null) {
                value(await { runtime.cancel(access, ticket(slot.ticket)) }); armed.remove(slot.ticket)
                current = write(current, slot.copy(ticket = null, phase = if (running) CookingTimerAlertPhase.DESIRED else CookingTimerAlertPhase.QUIET))
            }
            if (running) install(current, timerId) else denied.remove(timerId)
            value(inspect(sessionId))
        }
    }

    /** Exact conservative reconciliation, including unknown install/cancel results. Never schedules.
     * Foreground timer wire progress remains intact; a later explicit pause/resume can request alerts.
     * Old mappings are still available on recalled/non-current-timer bundles solely for this cleanup.
     */
    suspend fun cancelAlert(sessionId: String, timerId: String): PortResult<Unit> = guarded {
        mutex.withLock {
            val id = CookingTimerLedger.id(timerId); denied += id
            var current = load(sessionId)
            val slot = current.slots.firstOrNull { it.id == id } ?: fail(FailureReason.NOT_FOUND)
            currentCoroutineContext()[CookingTimerFlowLifetime]?.mutationAttempted = true
            if (slot.ticket != null) {
                current = write(current, slot.copy(phase = CookingTimerAlertPhase.CANCELLING))
                value(await { runtime.cancel(access, ticket(slot.ticket)) }); armed.remove(slot.ticket)
            }
            write(current, slot.copy(ticket = null, phase = CookingTimerAlertPhase.QUIET))
            Unit
        }
    }

    /** Runtime independently rechecks its installed ticket + this exact policy immediately before effect. */
    suspend fun runLocalEffect(ticket: NativeWorkTicket, effect: () -> PortResult<Unit>): PortResult<Unit> = guarded {
        value(await { runtime.runLocalEffect(ticket) {
            val held = armed[ticket.id]
            if (closed || !boundary.isCurrent(access.lease) || held == null || held.timerId in denied) PortResult.Failure(FailureReason.STALE_SESSION) else effect()
        } })
    }
    fun close() { closed = true; armed.clear(); invalidation.close() }

    // No action mutex here: registry policy evaluation may run while an action awaits runtime.
    internal suspend fun allows(scope: StorageScope, origin: String, logicalId: String, native: NativeWorkTicket): PortResult<Boolean> = guarded {
        if (scope != access.scope || origin != access.originBinding || native.kind != NativeWorkKind.TIMER || native.id !in armed) return@guarded false
        val parts = logicalId.split(':')
        if (parts.size != 3 || parts[0] != "cook-timer") return@guarded false
        // Another timer's acknowledged edit can advance the shared cooking revision between
        // successful reads. Retry ONLY that observed read race, before producing an effect.
        // Never retry a failed read/write, native acknowledgement, or an invoked local effect.
        repeat(3) { attempt ->
            try { return@guarded allowsCurrent(parts[1], parts[2], native) }
            catch (_: TimerReadRace) { if (attempt == 2) fail(FailureReason.CONFLICT) }
        }
        false
    }

    private suspend fun allowsCurrent(sessionId: String, timerId: String, native: NativeWorkTicket): Boolean {
        val current = load(sessionId, executionRead = true)
        if (current.metadata?.matchesTimers != true) return false
        eligible(current.snapshot)
        // Eligibility inspects the journal and may suspend. Re-bracket the exact domain/mapping
        // after those reads; never combine a prior timer generation with a later queue verdict.
        val after = load(sessionId, executionRead = true)
        val afterMetadata = after.metadata ?: return false
        if (after.snapshot.localRevision != current.snapshot.localRevision ||
            afterMetadata.record.revision != current.metadata.record.revision) {
            if (after.snapshot.localRevision < current.snapshot.localRevision ||
                afterMetadata.record.revision < current.metadata.record.revision) return false
            throw TimerReadRace()
        }
        if (!afterMetadata.matchesTimers ||
            after.snapshot.availability != CookingAvailability.AVAILABLE || !after.snapshot.originMatches ||
            after.snapshot.progress.status !in setOf(CookingStatus.ACTIVE, CookingStatus.PAUSED)) return false
        val slot = current.slots.firstOrNull { it.id == timerId && it.ticket == native.id && it.phase == CookingTimerAlertPhase.ARMED } ?: return false
        if (slot.id in denied) return false
        val timer = current.snapshot.progress.timers.firstOrNull { it.timerId.value.lowercase() == slot.id } ?: return false
        if (armed[native.id]?.proof != proof(current, slot)) return false
        return value(CookingTimerReducer.observe(timer.document, slot.anchor, clock.read())).timing == CookingTimerTiming.DUE && timer.status == "running"
    }

    private suspend fun install(initial: Loaded, timerId: String) {
        var current = initial
        var slot = current.slots.single { it.id == timerId }
        if (slot.phase != CookingTimerAlertPhase.DESIRED || slot.ticket != null) fail(FailureReason.CONFLICT)
        val anchor = slot.anchor ?: fail(FailureReason.CONFLICT)
        val native = value(await { runtime.install(access, NativeWorkKind.TIMER, "cook-timer:${current.snapshot.id}:$timerId") { supplied ->
            try {
                checkCurrent()
                current = write(current, slot.copy(ticket = supplied.id, phase = CookingTimerAlertPhase.MAPPED))
                slot = current.slots.single { it.id == timerId }
                eligible(current.snapshot)
                val timer = current.snapshot.progress.timers.single { it.timerId.value.lowercase() == timerId }
                if (value(CookingTimerReducer.observe(timer.document, anchor, clock.read())).timing != CookingTimerTiming.RUNNING) fail(FailureReason.CONFLICT)
                currentCoroutineContext()[CookingTimerActionAdmission]?.beforeSchedule?.invoke()
                checkCurrent()
                await { scheduler.schedule(supplied, anchor.epochMillis + anchor.remainingMillis) }
            } catch (e: CancellationException) { throw e }
            catch (e: TimerFailure) { PortResult.Failure(e.reason) }
        } })
        current = load(initial.snapshot.id)
        slot = current.slots.single { it.id == timerId }
        if (slot.phase != CookingTimerAlertPhase.MAPPED || slot.ticket != native.id) fail(FailureReason.CONFLICT)
        current = write(current, slot.copy(phase = CookingTimerAlertPhase.ARMED))
        armed[native.id] = Armed(current.snapshot.id, timerId, slot.generation,
            proof(current, current.slots.single { it.id == timerId })); denied.remove(timerId)
    }
    private suspend fun eligible(snapshot: CookingSnapshot) {
        if (!snapshot.originMatches || snapshot.availability != CookingAvailability.AVAILABLE ||
            snapshot.progress.status !in setOf(CookingStatus.ACTIVE, CookingStatus.PAUSED)) fail(FailureReason.FORBIDDEN)
        value(await { repo.timerExecutionCheck(access.lease, snapshot.id) })
    }
    private suspend fun load(id: String, executionRead: Boolean = false): Loaded {
        val normalized = CookingTimerLedger.id(id)
        val snapshot = value(await { repo.read(access.lease, normalized) }) ?: fail(FailureReason.NOT_FOUND)
        val metadata = value(await { repo.readTimerMetadata(access.lease, normalized) })
        val after = value(await { repo.read(access.lease, normalized) }) ?: fail(FailureReason.CONFLICT)
        if (!after.originMatches) fail(FailureReason.CONFLICT)
        if (after.localRevision != snapshot.localRevision) {
            if (executionRead && after.localRevision > snapshot.localRevision) throw TimerReadRace()
            fail(FailureReason.CONFLICT)
        }
        return Loaded(after, metadata, metadata?.let { CookingTimerLedger.decode(it.record.payload) }.orEmpty())
    }
    private suspend fun write(current: Loaded, slot: TimerSlot): Loaded {
        val metadata = current.metadata ?: fail(FailureReason.CONFLICT)
        value(await { repo.acknowledgeTimerMetadata(access.lease, current.snapshot.id, current.snapshot.localRevision,
            metadata.record, CookingTimerLedger.encode(replace(current.slots, slot))) })
        return load(current.snapshot.id)
    }
    private fun replace(slots: List<TimerSlot>, slot: TimerSlot) = if (slots.any { it.id == slot.id }) slots.map { if (it.id == slot.id) slot else it } else slots + slot
    private fun ticket(id: String) = value(NativeWorkTicket.fromNativeIdentity(id, NativeWorkKind.TIMER))
    private fun proof(current: Loaded, slot: TimerSlot): String = current.snapshot.id + ":" +
        CookingTimerLedger.encode(listOf(slot)).copyForCodec().decodeToString() + ":" +
        (current.snapshot.progress.timers.firstOrNull { it.timerId.value.lowercase() == slot.id }?.document?.json()?.toString() ?: "absent")
    private suspend fun checkCurrent() {
        currentCoroutineContext().ensureActive()
        currentCoroutineContext()[CookingTimerFlowLifetime]?.check?.invoke()
        currentCoroutineContext()[CookingTimerActionAdmission]?.check?.invoke()
        if (closed || !boundary.isCurrent(access.lease) || runtime.currentAccess() !== access) fail(FailureReason.STALE_SESSION)
        currentCoroutineContext().ensureActive()
        currentCoroutineContext()[CookingTimerFlowLifetime]?.check?.invoke()
        currentCoroutineContext()[CookingTimerActionAdmission]?.check?.invoke()
        if (closed || !boundary.isCurrent(access.lease)) fail(FailureReason.STALE_SESSION)
    }
    private suspend fun <T> await(action: suspend () -> T): T { checkCurrent(); return action().also { checkCurrent() } }
    private suspend fun <T> guarded(action: suspend () -> T): PortResult<T> {
        val result = withContext(dispatcher) {
            try { checkCurrent(); PortResult.Value(action()).also { checkCurrent() } }
            catch (e: CancellationException) { throw e }
            catch (e: TimerFailure) { PortResult.Failure(e.reason) }
            catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
        }
        currentCoroutineContext().ensureActive()
        return if (closed || !boundary.isCurrent(access.lease)) PortResult.Failure(FailureReason.STALE_SESSION) else result
    }
    private class Loaded(val snapshot: CookingSnapshot, val metadata: CookingTimerMetadata?, val slots: List<TimerSlot>)
    /** Constructed only for mismatched revisions obtained from successful read observations. */
    private class TimerReadRace : Exception()
    companion object {
        /** No factories, identity creation, scheduling or store writes. Exact real runtime pairing. */
        suspend fun create(runtime: PrivateSessionRuntime, access: PrivateSessionAccess, kitchen: PrivateKitchenSession,
            boundary: SessionBoundary, dispatcher: CoroutineDispatcher, clock: CookingTimerClock,
            scheduler: CookingTimerScheduler, policy: CookingTimerExecutionPolicy): PortResult<SessionCookingTimers> {
            var created: SessionCookingTimers? = null
            try {
                val result = withContext(dispatcher) {
                    if (!runtime.usesExecutionPolicy(policy) || runtime.currentAccess() !== access || !boundary.isCurrent(access.lease) ||
                        !kitchen.matchesComposition(access.scope, access.store, boundary, access.originBinding))
                        return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
                    try {
                        val owner = SessionCookingTimers(runtime, access, kitchen, boundary, dispatcher, clock, scheduler).also { created = it }
                        policy.bind(owner); PortResult.Value(owner)
                    } catch (e: TimerFailure) { created?.close(); PortResult.Failure(e.reason) }
                }
                currentCoroutineContext().ensureActive()
                return if (!boundary.isCurrent(access.lease) || created?.closed == true) {
                    created?.close(); PortResult.Failure(FailureReason.STALE_SESSION)
                } else result
            } catch (e: CancellationException) { created?.close(); throw e }
        }
    }
}
