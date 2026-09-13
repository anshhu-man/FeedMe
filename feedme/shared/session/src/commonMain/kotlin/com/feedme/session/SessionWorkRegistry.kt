package com.feedme.session

import com.feedme.core.ports.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One application-lifetime native-effect registry on the serialized identity dispatcher. Own a
 * distinct durable SessionControlStore, never the retirement-control record or recipe database.
 * Policy ports are mandatory: persisted ACTIVE alone is not permission after a pending logout.
 *
 * Native installers run only after durable reservation, inside this gate. They must use exactly
 * the supplied ticket, contain no callbacks into this registry, and return after their bounded
 * native scheduling command. Local callback effects must finish synchronously inside this gate;
 * starting detached work from that block breaks the contract. Remote workers also need the domain
 * transport/commit fences: native cancellation cannot undo an already accepted server mutation.
 */
class SessionWorkRegistry private constructor(
    private val control: SessionControlStore,
    private val boundary: SessionBoundary,
    private val dispatcher: CoroutineDispatcher,
    private val cancellation: NativeWorkCancellationPort,
    private val ids: NativeWorkIdSource,
    private val admission: NativeWorkAdmissionPolicy,
    private val execution: NativeWorkExecutionPolicy,
) : EmptySessionWorkRetirementPort {
    private val mutex = Mutex()
    private var closed = false
    private val originFences = mutableSetOf<Pair<StorageScope, String>>()
    private val ticketFences = mutableSetOf<String>()

    /**
     * Read-only setup preparation by the trusted identity coordinator, before a lease exists.
     * This does not verify a provider identity, reserve the ID, write, or invoke admission/OS ports.
     * Persist this exact opaque plan in independent control before calling [selectOrigin]. An
     * authenticated prior abort marker may be the exact raw predecessor. Planning is not a
     * reservation: only a later selection consumes that predecessor and fences old abort replay.
     */
    suspend fun planOrigin(scope: StorageScope, expectedIdleRevision: Long): PortResult<SessionWorkOriginPlan> = guarded {
        mutex.withLock {
            setupInactive()
            validateScope(scope)
            if (expectedIdleRevision <= 0) fail(FailureReason.INVALID_DATA)
            if (expectedIdleRevision > Long.MAX_VALUE - 2) fail(FailureReason.STORAGE_FAILURE)
            val authentication = planAuthentication()
            val before = read()
            setupInactive()
            if (before.record.revision != expectedIdleRevision ||
                (before.state != SessionWorkState.Idle && before.state !is SessionWorkState.SetupAborted))
                fail(FailureReason.CONFLICT)
            val aborted = before.state as? SessionWorkState.SetupAborted
            if (aborted != null && inspectOrigin(before, aborted.plan) != SessionWorkOriginPlanStatus.ABORTED)
                fail(FailureReason.STALE_SESSION)
            val origin = newId()
            if ((scope to origin) in originFences) fail(FailureReason.CONFLICT)
            if (aborted != null && SessionWorkOriginPlanCodec.decode(aborted.plan.copyForStorage()).origin == origin)
                fail(FailureReason.CONFLICT)
            val draft = SessionWorkOriginPlanRecord(expectedIdleRevision, scope, origin, PrivateBytes(ByteArray(64)))
            val unsigned = try { SessionWorkOriginPlanCodec.encodeUnsigned(draft) }
                catch (_: SessionWorkOriginPlanFormatException) { fail(FailureReason.INVALID_DATA) }
            val proof = requireResult(native { authentication.signOriginPlan(before.record, unsigned) })
            setupInactive()
            val after = read()
            setupInactive()
            if (after.record.revision != before.record.revision || !sameBytes(after.record.payload, before.record.payload))
                fail(FailureReason.CONFLICT)
            try { SessionWorkOriginPlan.create(draft.copy(proof = proof)) }
            catch (_: SessionWorkOriginPlanFormatException) { fail(FailureReason.STORAGE_FAILURE) }
        }
    }

    /** Read-only exact-plan observation. No status, including SEALED/ABORTED, acknowledges a write. */
    suspend fun inspectOrigin(plan: SessionWorkOriginPlan): PortResult<SessionWorkOriginPlanStatus> = guarded {
        mutex.withLock {
            setupInactive()
            inspectOrigin(read(), plan)
        }
    }

    /**
     * Same authenticated read-only observation as [inspectOrigin], including exact predecessor,
     * canonical selected/sealed bytes and post-proof readback, with the captured ledger revision.
     * Equal status at a later revision is changed evidence, not an acknowledgement or authority.
     */
    suspend fun inspectOriginState(plan: SessionWorkOriginPlan): PortResult<SessionWorkOriginObservation> = guarded {
        mutex.withLock {
            setupInactive()
            val entry = read()
            SessionWorkOriginObservation(inspectOrigin(entry, plan), entry.record.revision)
        }
    }

    /**
     * Select only the planned empty work origin, without a lease, binding, callbacks or OS work.
     * Every selected replay rewrites the same setup marker and obtains a fresh acknowledgement.
     * Ordinary active/retiring origins, even empty ones with the same ID, never qualify as replay.
     * The composite coordinator must later explicitly seal or abort this setup-only selection.
     */
    suspend fun selectOrigin(plan: SessionWorkOriginPlan): PortResult<Unit> = guarded {
        mutex.withLock {
            setupInactive()
            val before = read()
            val status = inspectOrigin(before, plan)
            // Keep at least one revision for the eventual seal/abort. Never wrap or reset.
            if (before.record.revision > Long.MAX_VALUE - 2) fail(FailureReason.STORAGE_FAILURE)
            val selected = when (status) {
                SessionWorkOriginPlanStatus.PREPARED -> SessionWorkState.SetupSelected(plan)
                SessionWorkOriginPlanStatus.SELECTED -> before.state
                SessionWorkOriginPlanStatus.SEALED, SessionWorkOriginPlanStatus.ABORTED -> fail(FailureReason.CONFLICT)
            }
            write(before, selected) { setupInactive() }
            setupInactive()
        }
    }

    /**
     * Seal only this exact setup-selected origin into an empty ordinary origin. Retained native
     * plan provenance permits a fresh seal acknowledgement after an uncertain write/restart;
     * it is consumed by the first ordinary lifecycle write, including resume. A matching ID,
     * empty legacy origin, or read-only SEALED status is never a substitute for this write.
     * Requires an inactive boundary and returns no lease, work binding or OS authority. The
     * trusted coordinator must retain independent pending setup until binding/control completion.
     */
    suspend fun sealOrigin(plan: SessionWorkOriginPlan): PortResult<Unit> = guarded {
        mutex.withLock {
            setupInactive()
            val before = read()
            val status = inspectOrigin(before, plan)
            // Ordinary resume must still be able to consume provenance after completion.
            if (before.record.revision > Long.MAX_VALUE - 2) fail(FailureReason.STORAGE_FAILURE)
            val sealed = when (status) {
                SessionWorkOriginPlanStatus.PREPARED, SessionWorkOriginPlanStatus.ABORTED -> fail(FailureReason.CONFLICT)
                SessionWorkOriginPlanStatus.SELECTED -> {
                    val details = SessionWorkOriginPlanCodec.decode(plan.copyForStorage())
                    SessionWorkState.Origin(details.scope, details.origin, false, emptyList(), plan)
                }
                SessionWorkOriginPlanStatus.SEALED -> before.state
            }
            write(before, sealed, preserveSetup = true) { setupInactive() }
            setupInactive()
        }
    }

    /**
     * Exact setup-only consumption, after independently acknowledged explicit abort confirmation.
     * Accepts only this authenticated predecessor, selection, untouched empty seal or consumed
     * marker. Every invocation changes the ledger revision and requires exact acknowledgement;
     * a readable ABORTED marker after a failed write is not success. No OS cancellation, IDs,
     * policy, secret access or lease is involved. Never use ordinary retirement as a substitute.
     */
    suspend fun abortOrigin(plan: SessionWorkOriginPlan): PortResult<Unit> = guarded {
        mutex.withLock {
            setupInactive()
            val before = read()
            val status = inspectOrigin(before, plan)
            val aborted = if (status == SessionWorkOriginPlanStatus.ABORTED) before.state else SessionWorkState.SetupAborted(plan)
            write(before, aborted) { setupInactive() }
            setupInactive()
        }
    }

    private suspend fun inspectOrigin(entry: Entry, plan: SessionWorkOriginPlan): SessionWorkOriginPlanStatus {
        setupInactive()
        val record = try { SessionWorkOriginPlanCodec.decode(plan.copyForStorage()) }
            catch (_: SessionWorkOriginPlanFormatException) { fail(FailureReason.INVALID_DATA) }
        val authentication = planAuthentication()
        val unsigned = SessionWorkOriginPlanCodec.encodeUnsigned(record)
        requireResult(native { authentication.verifyOriginPlan(record.expectedRevision, unsigned, record.proof) })
        setupInactive()
        if ((record.scope to record.origin) in originFences) fail(FailureReason.STALE_SESSION)
        val status = when (val state = entry.state) {
            SessionWorkState.Idle -> {
                if (entry.record.revision != record.expectedRevision) fail(FailureReason.STALE_SESSION)
                requireResult(native { authentication.verifyOriginPredecessor(entry.record, unsigned, record.proof) })
                setupInactive()
                SessionWorkOriginPlanStatus.PREPARED
            }
            is SessionWorkState.SetupSelected -> {
                if (entry.record.revision <= record.expectedRevision ||
                    !sameBytes(state.plan.copyForStorage(), plan.copyForStorage()) ||
                    !sameBytes(entry.record.payload, SessionWorkCodec.encode(SessionWorkState.SetupSelected(plan))))
                    fail(FailureReason.STALE_SESSION)
                SessionWorkOriginPlanStatus.SELECTED
            }
            is SessionWorkState.SetupAborted -> {
                if (sameBytes(state.plan.copyForStorage(), plan.copyForStorage())) {
                    if (entry.record.revision <= record.expectedRevision ||
                        !sameBytes(entry.record.payload, SessionWorkCodec.encode(SessionWorkState.SetupAborted(plan))))
                        fail(FailureReason.STALE_SESSION)
                    SessionWorkOriginPlanStatus.ABORTED
                } else {
                    // A successor must authenticate the exact consumed marker, not merely its
                    // empty projection, and must have been signed at this precise raw revision.
                    if (entry.record.revision != record.expectedRevision) fail(FailureReason.STALE_SESSION)
                    if (inspectOrigin(entry, state.plan) != SessionWorkOriginPlanStatus.ABORTED)
                        fail(FailureReason.STALE_SESSION)
                    requireResult(native { authentication.verifyOriginPredecessor(entry.record, unsigned, record.proof) })
                    setupInactive()
                    SessionWorkOriginPlanStatus.PREPARED
                }
            }
            is SessionWorkState.Origin -> {
                val retained = state.setupPlan ?: fail(FailureReason.STALE_SESSION)
                if (state.retiring || state.entries.isNotEmpty() || state.scope != record.scope || state.origin != record.origin ||
                    entry.record.revision < record.expectedRevision + 2 ||
                    !sameBytes(retained.copyForStorage(), plan.copyForStorage()) ||
                    !sameBytes(entry.record.payload, SessionWorkCodec.encode(state))) fail(FailureReason.STALE_SESSION)
                SessionWorkOriginPlanStatus.SEALED
            }
        }
        // Authentication can suspend. Do not return an observation of a predecessor which
        // changed while its proof was checked; selection additionally uses this exact CAS.
        val after = read()
        setupInactive()
        if (after.record.revision != entry.record.revision || !sameBytes(after.record.payload, entry.record.payload))
            fail(FailureReason.CONFLICT)
        return status
    }

    private fun planAuthentication(): WorkOriginPlanAuthentication =
        control as? WorkOriginPlanAuthentication ?: fail(FailureReason.NOT_CONFIGURED)
    private fun setupInactive() {
        if (boundary.current() != null) fail(FailureReason.STALE_SESSION)
    }
    private fun sameBytes(first: PrivateBytes, second: PrivateBytes): Boolean {
        val left = first.copyForCodec()
        val right = second.copyForCodec()
        return try { left.contentEquals(right) } finally { left.fill(0); right.fill(0) }
    }

    suspend fun snapshot(): PortResult<SessionWorkSnapshot> = guarded {
        mutex.withLock {
            val value = read()
            if (value.state is SessionWorkState.SetupSelected) fail(FailureReason.CONFLICT)
            val state = value.state as? SessionWorkState.Origin
            SessionWorkSnapshot(value.record.revision, state?.scope, state?.origin, state?.retiring ?: false,
                state?.entries?.map { NativeWorkStatus(it.ticket(), it.phase) } ?: emptyList())
        }
    }

    /** New verified session only. The returned fresh origin becomes the private command origin. */
    suspend fun createOrigin(lease: SessionLease, expectedRevision: Long): PortResult<SessionWorkBinding> = guarded {
        mutex.withLock {
            current(lease)
            if (expectedRevision <= 0) fail(FailureReason.INVALID_DATA)
            admit(lease.scope)
            current(lease)
            val previous = read()
            current(lease)
            if (previous.record.revision != expectedRevision || previous.state != SessionWorkState.Idle) fail(FailureReason.CONFLICT)
            val origin = newId()
            if ((lease.scope to origin) in originFences) fail(FailureReason.CONFLICT)
            write(previous, SessionWorkState.Origin(lease.scope, origin, false, emptyList())) {
                current(lease)
                if ((lease.scope to origin) in originFences) fail(FailureReason.STALE_SESSION)
            }
            current(lease)
            SessionWorkBinding(lease, origin)
        }
    }

    /** Resume only the existing, independently verified exact owner. Never initialize or repair. */
    suspend fun resume(lease: SessionLease): PortResult<SessionWorkBinding> = guarded {
        mutex.withLock {
            current(lease)
            admit(lease.scope)
            current(lease)
            val value = read()
            val state = value.state as? SessionWorkState.Origin ?: fail(FailureReason.NOT_CONFIGURED)
            current(lease)
            if (state.scope != lease.scope || state.retiring || fenced(state)) fail(FailureReason.STALE_SESSION)
            write(value, state) { current(lease); if (fenced(state)) fail(FailureReason.STALE_SESSION) }
            current(lease)
            SessionWorkBinding(lease, state.origin)
        }
    }

    /**
     * Persist desired timer/worker state in its domain first. Reserve before the OS side effect;
     * RESERVED callbacks cannot run. Replacing a logical task requires exact cancellation first.
     * Failure/cancellation leaves exact evidence for reconcilePending or retirement, never retry
     * with an invented replacement identity. This does not mutate a cooking TimerState status.
     */
    suspend fun install(
        binding: SessionWorkBinding,
        kind: NativeWorkKind,
        logicalId: String,
        installer: suspend (NativeWorkTicket) -> PortResult<Unit>,
    ): PortResult<NativeWorkTicket> = guarded {
        mutex.withLock {
            validBinding(binding)
            admit(binding.lease.scope)
            validBinding(binding)
            var value = read()
            val origin = active(value, binding)
            if (origin.entries.size >= SessionWorkCodec.MAX_ENTRIES ||
                origin.entries.any { it.kind == kind && it.logicalId == logicalId }) fail(FailureReason.CONFLICT)
            val id = newId()
            if (id == origin.origin || id in ticketFences || origin.entries.any { it.id == id }) fail(FailureReason.CONFLICT)
            val task = SessionWorkEntry(id, kind, logicalId, NativeWorkPhase.RESERVED)
            value = write(value, origin.withEntries(origin.entries + task)) { validBinding(binding) }
            // Retirement can install its process fence while a suspending installer owns the lock.
            validBinding(binding)
            val outcome = native { installer(task.ticket()) }
            val stillAdmitted = if (bindingCurrent(binding)) native { admission.allowed(binding.lease.scope) }
                else PortResult.Value(false)
            if (outcome is PortResult.Failure || stillAdmitted !is PortResult.Value || !stillAdmitted.value || !bindingCurrent(binding)) {
                // Exact compensation is attempted independently of recipe/credential stores.
                val cleanup = cancelEntry(value, task.id)
                if (cleanup is PortResult.Failure) fail(cleanup.reason)
                if (outcome is PortResult.Failure) fail(outcome.reason)
                if (stillAdmitted is PortResult.Failure) fail(stillAdmitted.reason)
                fail(FailureReason.STALE_SESSION)
            }
            val selected = value.state as SessionWorkState.Origin
            // An invalid/uncertain final acknowledgment must not grant callback admission.
            ticketFences.add(id)
            try {
                write(value, selected.withEntries(selected.entries.map { if (it.id == id) it.phase(NativeWorkPhase.INSTALLED) else it })) {
                    validBinding(binding)
                }
                validBinding(binding)
            } catch (cancelled: CancellationException) {
                // Keep the process fence and durable evidence; cancellation cannot authorize a
                // fresh native effect through a non-cancellable readback/cleanup shortcut.
                throw cancelled
            } catch (failure: WorkFailure) {
                val cleanup = compensateInstall(binding, task.ticket())
                if (cleanup is PortResult.Failure) fail(cleanup.reason)
                throw failure
            }
            ticketFences.remove(id)
            task.ticket()
        }
    }

    /** Pause/cancel/replace uses this exact generation, not a scope-wide timer ID. */
    suspend fun cancel(binding: SessionWorkBinding, ticket: NativeWorkTicket): PortResult<Unit> = guarded {
        validBinding(binding)
        // No suspension between validating the captured lease and closing callback admission.
        val newFence = ticketFences.add(ticket.id)
        mutex.withLock {
            validBinding(binding)
            val value = read()
            val origin = active(value, binding)
            val task = origin.entries.firstOrNull { it.id == ticket.id }
            if (task == null) {
                // An observed removal may be the result of an earlier failed commit. Acknowledge
                // this exact current record afresh before reporting cancellation complete.
                write(value, origin) { validBinding(binding) }
                ticketFences.remove(ticket.id)
                return@withLock Unit
            }
            if (task.kind != ticket.kind) {
                if (newFence) ticketFences.remove(ticket.id)
                fail(FailureReason.INVALID_DATA)
            }
            requireResult(cancelEntry(value, task.id) { validBinding(binding) })
        }
    }

    /** Uncertain/incomplete native installs are canceled, never implicitly rescheduled on restart. */
    suspend fun reconcilePending(binding: SessionWorkBinding): PortResult<Unit> = guarded {
        mutex.withLock {
            validBinding(binding)
            var value = read()
            val selected = active(value, binding)
            value = write(value, selected) { validBinding(binding) }
            var firstFailure: FailureReason? = null
            for (task in selected.entries.filter { it.phase != NativeWorkPhase.INSTALLED }) {
                validBinding(binding)
                when (val outcome = cancelEntry(value, task.id) { validBinding(binding) }) {
                    is PortResult.Failure -> if (firstFailure == null) firstFailure = outcome.reason
                    is PortResult.Value -> Unit
                }
                value = read()
                active(value, binding)
            }
            firstFailure?.let(::fail)
        }
    }

    /**
     * A queued OS callback has only an opaque ticket, not authority. The mandatory policy checks
     * the independent retirement barrier, verified owner and current desired domain generation.
     * There is no await between the final fence check and this short synchronous local effect.
     */
    suspend fun runLocalEffect(ticket: NativeWorkTicket, effect: () -> PortResult<Unit>): PortResult<Unit> = guarded {
        val lease = boundary.current() ?: fail(FailureReason.STALE_SESSION)
        current(lease)
        mutex.withLock {
            val value = read()
            current(lease)
            val origin = value.state as? SessionWorkState.Origin ?: fail(FailureReason.STALE_SESSION)
            if (origin.scope != lease.scope) fail(FailureReason.STALE_SESSION)
            val task = origin.entries.firstOrNull { it.id == ticket.id && it.kind == ticket.kind }
                ?: fail(FailureReason.STALE_SESSION)
            if (origin.retiring || fenced(origin) || task.id in ticketFences || task.phase != NativeWorkPhase.INSTALLED)
                fail(FailureReason.STALE_SESSION)
            if (!requireResult(native { execution.allowed(origin.scope, origin.origin, task.logicalId, task.ticket()) }))
                fail(FailureReason.STALE_SESSION)
            current(lease)
            if (origin.retiring || fenced(origin) || task.id in ticketFences) fail(FailureReason.STALE_SESSION)
            write(value, origin) {
                current(lease)
                if (fenced(origin) || task.id in ticketFences) fail(FailureReason.STALE_SESSION)
            }
            current(lease)
            if (fenced(origin) || task.id in ticketFences) fail(FailureReason.STALE_SESSION)
            requireResult(effect())
        }
    }

    /** Exact empty-origin discard. Never invokes native cancellation, even if content changed. */
    override suspend fun retireEmpty(scope: StorageScope, originBinding: String): PortResult<Unit> = guarded {
        validateScope(scope)
        try { requireCredentialUuid(originBinding) } catch (_: IllegalArgumentException) { fail(FailureReason.INVALID_DATA) }
        mutex.withLock {
            val value = read()
            val selected = value.state as? SessionWorkState.Origin
            if (selected == null) {
                if (value.state != SessionWorkState.Idle) fail(FailureReason.CONFLICT)
                write(value, SessionWorkState.Idle)
                originFences.remove(scope to originBinding)
                return@withLock Unit
            }
            if (selected.scope != scope || selected.origin != originBinding) return@withLock Unit
            if (selected.setupPlan != null) fail(FailureReason.CONFLICT)
            if (selected.entries.isNotEmpty()) fail(FailureReason.CONFLICT)
            val target = scope to originBinding
            originFences.add(target)
            // The independent control owns retries, but readback of Idle is not an acknowledgement.
            write(value, SessionWorkState.Idle)
            originFences.remove(target)
        }
    }

    override suspend fun retire(scope: StorageScope, originBinding: String): PortResult<Unit> = guarded {
        validateScope(scope)
        try { requireCredentialUuid(originBinding) } catch (_: IllegalArgumentException) { fail(FailureReason.INVALID_DATA) }
        val target = scope to originBinding
        val addedFence = originFences.add(target)
        mutex.withLock {
            var value = read()
            val selected = value.state as? SessionWorkState.Origin
            if (selected == null) {
                if (value.state != SessionWorkState.Idle) {
                    // An ordinary retirement cannot consume setup intent or poison its exact
                    // recovery path. Keep pre-existing fences, including uncertain cleanup.
                    if (addedFence) originFences.remove(target)
                    fail(FailureReason.CONFLICT)
                }
                write(value, SessionWorkState.Idle)
                originFences.remove(target)
                return@withLock Unit
            }
            if (selected.scope != scope || selected.origin != originBinding) {
                originFences.remove(target)
                return@withLock Unit
            }
            if (selected.setupPlan != null) {
                if (addedFence) originFences.remove(target)
                fail(FailureReason.CONFLICT)
            }
            value = write(value, if (selected.retiring) selected else selected.retiring())
            var firstFailure: FailureReason? = null
            val entries = (value.state as SessionWorkState.Origin).entries
            for (task in entries) {
                when (val outcome = cancelEntry(value, task.id)) {
                    is PortResult.Failure -> if (firstFailure == null) firstFailure = outcome.reason
                    is PortResult.Value -> Unit
                }
                value = read()
                val current = value.state as? SessionWorkState.Origin ?: fail(FailureReason.STORAGE_FAILURE)
                if (current.scope != scope || current.origin != originBinding || !current.retiring) fail(FailureReason.CONFLICT)
            }
            firstFailure?.let(::fail)
            if ((value.state as SessionWorkState.Origin).entries.isNotEmpty()) fail(FailureReason.STORAGE_FAILURE)
            write(value, SessionWorkState.Idle)
            originFences.remove(target)
        }
    }

    /** Closing stops this manager's admission, not OS work. The native owner closes its store too. */
    suspend fun close(): PortResult<Unit> = withContext(NonCancellable + dispatcher) {
        mutex.withLock {
            if (!closed) {
                closed = true
                Ownership.release(control)
            }
            PortResult.Value(Unit)
        }
    }

    private suspend fun cancelEntry(initial: Entry, id: String, checkCurrent: () -> Unit = {}): PortResult<Unit> {
        val origin = initial.state as? SessionWorkState.Origin ?: return PortResult.Failure(FailureReason.STORAGE_FAILURE)
        val task = origin.entries.firstOrNull { it.id == id } ?: return PortResult.Failure(FailureReason.CONFLICT)
        ticketFences.add(id)
        val pending = if (origin.retiring || task.phase == NativeWorkPhase.CANCELLING) origin else
            origin.withEntries(origin.entries.map { if (it.id == id) it.phase(NativeWorkPhase.CANCELLING) else it })
        val value = try { write(initial, pending, checkCurrent = checkCurrent) }
        catch (failure: WorkFailure) { return PortResult.Failure(failure.reason) }
        // Every attempt, including already-retiring/cancelling replay, changes the durable CAS
        // revision and obtains an exact readback. Failed acknowledgement means no native effect.
        checkCurrent()
        val outcome = native { cancellation.cancel(task.ticket()) }
        if (outcome is PortResult.Failure) return outcome
        return try {
            val selected = value.state as SessionWorkState.Origin
            write(value, selected.withEntries(selected.entries.filterNot { it.id == id }), checkCurrent = checkCurrent)
            ticketFences.remove(id)
            PortResult.Value(Unit)
        } catch (failure: WorkFailure) { PortResult.Failure(failure.reason) }
    }

    /** Final install may have committed despite a missing or invalid acknowledgment. */
    private suspend fun compensateInstall(binding: SessionWorkBinding, ticket: NativeWorkTicket): PortResult<Unit> {
        ticketFences.add(ticket.id)
        return try {
            val value = read()
            val origin = value.state as? SessionWorkState.Origin
            if (origin != null && origin.scope == binding.lease.scope && origin.origin == binding.originBinding &&
                origin.entries.any { it.id == ticket.id && it.kind == ticket.kind }) {
                cancelEntry(value, ticket.id)
            } else PortResult.Failure(FailureReason.CONFLICT)
        } catch (failure: WorkFailure) {
            // Retain the process fence. Unreadable/changed metadata never authorizes cancellation.
            PortResult.Failure(failure.reason)
        }
    }

    private data class Entry(val record: SessionControlRecord, val state: SessionWorkState)
    private suspend fun read(): Entry {
        if (closed) fail(FailureReason.STORAGE_FAILURE)
        val record = requireResult(native { control.read() }) ?: fail(FailureReason.STORAGE_FAILURE)
        if (record.revision <= 0) fail(FailureReason.STORAGE_FAILURE)
        val state = try { SessionWorkCodec.decode(record.payload) } catch (_: SessionWorkFormatException) { fail(FailureReason.STORAGE_FAILURE) }
        return Entry(record, state)
    }

    private suspend fun write(previous: Entry, state: SessionWorkState, preserveSetup: Boolean = false,
        checkCurrent: () -> Unit = {}): Entry {
        val next = if (!preserveSetup && state is SessionWorkState.Origin) state.used() else state
        val payload = if (next === previous.state) previous.record.payload else
            try { SessionWorkCodec.encode(next) } catch (_: SessionWorkFormatException) { fail(FailureReason.INVALID_DATA) }
        val result = requireResult(control.acknowledge(previous.record, payload) {
            if (closed) fail(FailureReason.STORAGE_FAILURE)
            checkCurrent()
        })
        return Entry(result, next)
    }

    private suspend fun admit(scope: StorageScope) {
        if (!requireResult(native { admission.allowed(scope) })) fail(FailureReason.STALE_SESSION)
    }
    private fun active(value: Entry, binding: SessionWorkBinding): SessionWorkState.Origin {
        validBinding(binding)
        val origin = value.state as? SessionWorkState.Origin ?: fail(FailureReason.STALE_SESSION)
        if (origin.scope != binding.lease.scope || origin.origin != binding.originBinding || origin.retiring || fenced(origin))
            fail(FailureReason.STALE_SESSION)
        return origin
    }
    private fun fenced(origin: SessionWorkState.Origin) = (origin.scope to origin.origin) in originFences
    private fun bindingCurrent(binding: SessionWorkBinding) = boundary.isCurrent(binding.lease) &&
        (binding.lease.scope to binding.originBinding) !in originFences
    private fun validBinding(binding: SessionWorkBinding) { if (!bindingCurrent(binding)) fail(FailureReason.STALE_SESSION) }
    private fun current(lease: SessionLease) { validateScope(lease.scope); if (!boundary.isCurrent(lease)) fail(FailureReason.STALE_SESSION) }
    private fun newId(): String = try { requireCredentialUuid(ids.nextId()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { fail(FailureReason.INVALID_DATA) }

    private suspend fun <T> guarded(action: suspend () -> T): PortResult<T> = withContext(dispatcher) {
        try {
            currentCoroutineContext().ensureActive()
            if (closed) fail(FailureReason.STORAGE_FAILURE)
            val value = action()
            currentCoroutineContext().ensureActive()
            PortResult.Value(value)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: WorkFailure) { PortResult.Failure(failure.reason) }
        catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
    }

    companion object {
        /** Read-only open; factory initialization belongs exclusively to the native durable store. */
        suspend fun open(
            control: SessionControlStore, boundary: SessionBoundary, dispatcher: CoroutineDispatcher,
            cancellation: NativeWorkCancellationPort, ids: NativeWorkIdSource,
            admission: NativeWorkAdmissionPolicy, execution: NativeWorkExecutionPolicy,
        ): PortResult<SessionWorkRegistry> {
            if (!Ownership.reserve(control)) return PortResult.Failure(FailureReason.CONFLICT)
            var transferred = false
            try {
                val manager = SessionWorkRegistry(control, boundary, dispatcher, cancellation, ids, admission, execution)
                // Setup-selected is deliberately not an ordinary snapshot/empty origin, but
                // must still be reopenable for exact opaque-plan recovery.
                val result = manager.guarded { manager.mutex.withLock { manager.read(); Unit } }
                if (result is PortResult.Failure) return result
                transferred = true
                return PortResult.Value(manager)
            } finally { if (!transferred) withContext(NonCancellable) { Ownership.release(control) } }
        }

        private fun validateScope(scope: StorageScope) {
            if (scope.actorKind == ActorKind.DEMO) fail(FailureReason.INVALID_DATA)
            try { scope.environment.encodeToByteArray(throwOnInvalidSequence = true); scope.actorId.encodeToByteArray(throwOnInvalidSequence = true) }
            catch (_: Exception) { fail(FailureReason.INVALID_DATA) }
        }
        private suspend fun <T> native(action: suspend () -> PortResult<T>): PortResult<T> = try {
            currentCoroutineContext().ensureActive()
            val result = action()
            currentCoroutineContext().ensureActive()
            result
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
        private fun <T> requireResult(result: PortResult<T>): T = when (result) {
            is PortResult.Value -> result.value
            is PortResult.Failure -> fail(result.reason)
        }
        private fun fail(reason: FailureReason): Nothing = throw WorkFailure(reason)
    }
}

private class WorkFailure(val reason: FailureReason) : Exception("Native work unavailable")

private object Ownership {
    private val mutex = Mutex()
    private val stores = mutableSetOf<SessionControlStore>()
    suspend fun reserve(store: SessionControlStore): Boolean = mutex.withLock { stores.add(store) }
    suspend fun release(store: SessionControlStore) = mutex.withLock { stores.remove(store) }
}
