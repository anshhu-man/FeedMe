package com.feedme.session

import com.feedme.core.ports.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
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

    suspend fun snapshot(): PortResult<SessionWorkSnapshot> = guarded {
        mutex.withLock {
            val value = read()
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
            write(previous, SessionWorkState.Origin(lease.scope, origin, false, emptyList()))
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
            val state = read().state as? SessionWorkState.Origin ?: fail(FailureReason.NOT_CONFIGURED)
            current(lease)
            if (state.scope != lease.scope || state.retiring || fenced(state)) fail(FailureReason.STALE_SESSION)
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
            value = write(value, origin.withEntries(origin.entries + task))
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
                write(value, selected.withEntries(selected.entries.map { if (it.id == id) it.phase(NativeWorkPhase.INSTALLED) else it }))
                validBinding(binding)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { compensateInstall(binding, task.ticket()) }
                throw cancelled
            } catch (failure: WorkFailure) {
                val cleanup = withContext(NonCancellable) { compensateInstall(binding, task.ticket()) }
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
            if (task == null) { if (newFence) ticketFences.remove(ticket.id); return@withLock Unit }
            if (task.kind != ticket.kind) {
                if (newFence) ticketFences.remove(ticket.id)
                fail(FailureReason.INVALID_DATA)
            }
            requireResult(cancelEntry(value, task.id))
        }
    }

    /** Uncertain/incomplete native installs are canceled, never implicitly rescheduled on restart. */
    suspend fun reconcilePending(binding: SessionWorkBinding): PortResult<Unit> = guarded {
        mutex.withLock {
            validBinding(binding)
            var value = read()
            val selected = active(value, binding)
            var firstFailure: FailureReason? = null
            for (task in selected.entries.filter { it.phase != NativeWorkPhase.INSTALLED }) {
                validBinding(binding)
                when (val outcome = cancelEntry(value, task.id)) {
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
            if (!requireResult(execution.allowed(origin.scope, origin.origin, task.logicalId, task.ticket())))
                fail(FailureReason.STALE_SESSION)
            current(lease)
            if (origin.retiring || fenced(origin) || task.id in ticketFences) fail(FailureReason.STALE_SESSION)
            requireResult(effect())
        }
    }

    /** Exact empty-origin discard. Never invokes native cancellation, even if content changed. */
    override suspend fun retireEmpty(scope: StorageScope, originBinding: String): PortResult<Unit> = guarded {
        validateScope(scope)
        try { requireCredentialUuid(originBinding) } catch (_: IllegalArgumentException) { fail(FailureReason.INVALID_DATA) }
        mutex.withLock {
            val value = read()
            val selected = value.state as? SessionWorkState.Origin ?: return@withLock Unit
            if (selected.scope != scope || selected.origin != originBinding) return@withLock Unit
            if (selected.entries.isNotEmpty()) fail(FailureReason.CONFLICT)
            val target = scope to originBinding
            originFences.add(target)
            // One exact CAS is sufficient: the independent repair control already owns retries.
            write(value, SessionWorkState.Idle)
            originFences.remove(target)
        }
    }

    override suspend fun retire(scope: StorageScope, originBinding: String): PortResult<Unit> = guarded {
        validateScope(scope)
        try { requireCredentialUuid(originBinding) } catch (_: IllegalArgumentException) { fail(FailureReason.INVALID_DATA) }
        val target = scope to originBinding
        originFences.add(target)
        mutex.withLock {
            var value = read()
            val selected = value.state as? SessionWorkState.Origin
            if (selected == null || selected.scope != scope || selected.origin != originBinding) {
                originFences.remove(target)
                return@withLock Unit
            }
            if (!selected.retiring) value = write(value, selected.retiring())
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

    private suspend fun cancelEntry(initial: Entry, id: String): PortResult<Unit> {
        val origin = initial.state as? SessionWorkState.Origin ?: return PortResult.Failure(FailureReason.STORAGE_FAILURE)
        val task = origin.entries.firstOrNull { it.id == id } ?: return PortResult.Failure(FailureReason.CONFLICT)
        ticketFences.add(id)
        var value = initial
        var barrierFailure: FailureReason? = null
        if (!origin.retiring && task.phase != NativeWorkPhase.CANCELLING) {
            try { value = write(value, origin.withEntries(origin.entries.map { if (it.id == id) it.phase(NativeWorkPhase.CANCELLING) else it })) }
            catch (failure: WorkFailure) { barrierFailure = failure.reason }
        }
        // Even if this checkpoint failed, a RESERVED/cancellation process fence remains; cleanup
        // must still attempt the exact known OS identity. Never broaden to an app-wide cancel.
        val outcome = native { cancellation.cancel(task.ticket()) }
        if (barrierFailure != null) return PortResult.Failure(barrierFailure)
        if (outcome is PortResult.Failure) return outcome
        return try {
            val selected = value.state as SessionWorkState.Origin
            write(value, selected.withEntries(selected.entries.filterNot { it.id == id }))
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
            } else native { cancellation.cancel(ticket) }
        } catch (failure: WorkFailure) {
            // A broken ledger must not prevent an independent exact native cancellation attempt.
            native { cancellation.cancel(ticket) }
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

    private suspend fun write(previous: Entry, state: SessionWorkState): Entry {
        val payload = try { SessionWorkCodec.encode(state) } catch (_: SessionWorkFormatException) { fail(FailureReason.INVALID_DATA) }
        val outcome = native { control.compareAndSet(previous.record.revision, payload) }
        if (outcome is PortResult.Failure && outcome.reason == FailureReason.OUTCOME_UNKNOWN) {
            // Only an exact newer durable receipt proves this CAS committed. Never retry using
            // a new identity or treat unavailable/conflicting read-back as a confirmed rollback.
            val observed = native { control.read() }
            val record = (observed as? PortResult.Value)?.value
            if (record != null && record.revision > previous.record.revision && sameBytes(record.payload, payload))
                return Entry(record, state)
            fail(FailureReason.OUTCOME_UNKNOWN)
        }
        val result = requireResult(outcome)
        if (result.revision <= previous.record.revision || !sameBytes(result.payload, payload)) fail(FailureReason.STORAGE_FAILURE)
        return Entry(result, state)
    }

    private suspend fun admit(scope: StorageScope) {
        if (!requireResult(admission.allowed(scope))) fail(FailureReason.STALE_SESSION)
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
    private fun newId(): String = try { requireCredentialUuid(ids.nextId()) } catch (_: Exception) { fail(FailureReason.INVALID_DATA) }

    private suspend fun <T> guarded(action: suspend () -> T): PortResult<T> = withContext(dispatcher) {
        try {
            if (closed) fail(FailureReason.STORAGE_FAILURE)
            PortResult.Value(action())
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
                val result = manager.snapshot()
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
        private fun sameBytes(left: PrivateBytes, right: PrivateBytes): Boolean {
            val a = left.copyForCodec(); val b = right.copyForCodec()
            return try { a.contentEquals(b) } finally { a.fill(0); b.fill(0) }
        }
        private suspend fun <T> native(action: suspend () -> PortResult<T>): PortResult<T> = try { action() }
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
