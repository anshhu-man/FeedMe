package com.feedme.session

import com.feedme.core.ports.*
import com.feedme.storage.EncryptedStateDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Local-only logout/account-switch retirement. NOT sign-in, provider refresh, remote revocation,
 * deletion acceptance, guest merge or permission to erase on a generic 401/403. Credential/work
 * ports must be real incarnation-fenced native implementations before application integration.
 * Consult restorationAllowed before identity restore, credentials, DB resume or boundary activate.
 * All coordinators and users of a boundary must share its serialized application dispatcher;
 * the boundary is not a thread-safe identity authority. Storage moves its own I/O off that owner.
 */
class LocalRetirementCoordinator(
    private val control: SessionControlStore,
    private val data: EncryptedStateDatabase,
    private val boundary: SessionBoundary,
    private val dispatcher: CoroutineDispatcher,
    private val credentials: CredentialRetirementPort,
    private val work: SessionWorkRetirementPort,
) {
    private val mutex = Mutex()

    /**
     * Called during verified session composition, not from a late auth response. Scope comes from
     * the verified application owner mapping, never email/Profile.id inference. Capture before a
     * user requests logout so fencing does not need to wait for the first disk read.
     */
    suspend fun capture(lease: SessionLease, originBinding: String, credentialIncarnation: String): PortResult<RetirementBinding> = guarded {
        mutex.withLock {
            current(lease)
            retirementUuid(originBinding); retirementUuid(credentialIncarnation)
            if (ProcessRetirements.get(boundary) != null) failRetirement(FailureReason.CONFLICT)
            val record = read()
            if (record.state.blocksAccess()) failRetirement(FailureReason.CONFLICT)
            val target = requireRetirement(data.captureRetirement(lease.scope)) ?: failRetirement(FailureReason.NOT_CONFIGURED)
            current(lease)
            if (read().record.revision != record.record.revision) failRetirement(FailureReason.CONFLICT)
            current(lease)
            RetirementBinding(lease, originBinding, credentialIncarnation, target, record.record.revision,
                (record.state as? RetirementState.Complete)?.operationId)
        }
    }

    /** A new explicit logout/account-switch request; process authority is cleared before any I/O. */
    suspend fun retire(binding: RetirementBinding, operationId: String): PortResult<LocalRetirementProgress> = guarded {
        current(binding.lease)
        retirementUuid(operationId)
        if (operationId == binding.priorCompletedId) failRetirement(FailureReason.CONFLICT)
        val state = RetirementState.Pending(operationId, binding.lease.scope, binding.origin,
            binding.credentialIncarnation, binding.dataTarget, emptySet())
        // Fence BEFORE waiting for another capture/restore read to release this coordinator's
        // mutex. Captured authority is exact; no storage lookup is needed to stop outward effects.
        withContext(NonCancellable) {
            boundary.clear()
            ProcessRetirements.install(boundary, PendingLatch(state, binding.controlRevision))
        }
        mutex.withLock {
            resumeLatched(read())
        }
    }

    /**
     * Internal explicit-confirmation boundary. Runtime must have compared a prepared, exact empty
     * setup snapshot immediately before this call. This does not create a lease or infer identity.
     * Targets become durable before any cleanup; retries never capture replacement resources.
     */
    internal suspend fun discardSetup(state: RetirementState.SetupDiscardPending,
        expectedControl: SessionControlRecord): PortResult<LocalRetirementProgress> = guarded {
        RetirementCodec.decode(RetirementCodec.encode(state))
        if (state.done.isNotEmpty() || boundary.current() != null) failRetirement(FailureReason.CONFLICT)
        val expectedState = RetirementCodec.decode(expectedControl.payload)
        if (expectedState.blocksAccess() ||
            (expectedState is RetirementState.Complete && expectedState.operationId == state.operationId)) failRetirement(FailureReason.CONFLICT)
        mutex.withLock {
            if (boundary.current() != null || ProcessRetirements.get(boundary) != null) failRetirement(FailureReason.CONFLICT)
            val observed = read()
            if (boundary.current() != null || observed.record.revision != expectedControl.revision ||
                !samePayload(observed.record.payload, expectedControl.payload)) failRetirement(FailureReason.CONFLICT)
            // The inactive boundary must remain inactive. Never clear a newly installed lease.
            withContext(NonCancellable) {
                if (boundary.current() != null) failRetirement(FailureReason.CONFLICT)
                ProcessRetirements.install(boundary, PendingLatch(state, expectedControl.revision, expectedControl.payload))
            }
            resumeLatched(observed)
        }
    }

    /** Startup/explicit retry only. Never restore authority or capture new cleanup targets here. */
    suspend fun recover(): PortResult<LocalRetirementProgress> = guarded {
        mutex.withLock {
            boundary.clear()
            resumeLatched(read())
        }
    }

    /** Recovery snapshot, not an activation grant. Missing/corrupt state never authorizes restore. */
    suspend fun restorationAllowed(): PortResult<Boolean> = guarded {
        mutex.withLock {
            if (ProcessRetirements.get(boundary) != null) false else {
                val entry = read()
                val pending = entry.state as? RetirementState.InFlight
                if (pending != null) ProcessRetirements.install(boundary, PendingLatch(pending, entry.record.revision))
                // Another coordinator may have fenced this shared boundary while read suspended.
                // An older idle/complete snapshot must not override that process retirement intent.
                !entry.state.blocksAccess() && ProcessRetirements.get(boundary) == null
            }
        }
    }

    /** Pure in-memory observation for diagnostics; does not install a latch or grant restoration. */
    internal suspend fun hasProcessRetirement(): Boolean = withContext(dispatcher) {
        ProcessRetirements.get(boundary) != null
    }

    private suspend fun resumeLatched(record: Entry): LocalRetirementProgress {
        // A credential plan is neither a logout nor permission to abort a failed create.
        if (record.state is RetirementState.PendingCreate) failRetirement(FailureReason.CONFLICT)
        val latch = ProcessRetirements.get(boundary)
        if (latch == null) {
            val pending = record.state as? RetirementState.InFlight
            if (pending != null) ProcessRetirements.install(boundary, PendingLatch(pending, record.record.revision))
            return finish(record)
        }
        val state = record.state
        if (state is RetirementState.InFlight) {
            if (!sameIntent(state, latch.state)) failRetirement(FailureReason.CONFLICT)
            return finish(record)
        }
        if (state is RetirementState.Complete && state.operationId == latch.state.operationId && record.record.revision > latch.expectedRevision) {
            ProcessRetirements.remove(boundary, latch.state.operationId)
            return progress(state)
        }
        if (record.record.revision != latch.expectedRevision) failRetirement(FailureReason.CONFLICT)
        if (latch.expectedPayload != null && !samePayload(record.record.payload, latch.expectedPayload)) failRetirement(FailureReason.CONFLICT)
        return finish(write(record, latch.state))
    }

    private suspend fun finish(initial: Entry): LocalRetirementProgress {
        var entry = initial
        val initialState = entry.state
        if (initialState == RetirementState.Idle) return progress(initialState)
        if (initialState is RetirementState.Complete) return progress(initialState)
        val verified = initialState as RetirementState.InFlight
        // Reject malformed/foreign/stale captured data authority BEFORE touching credentials or
        // native work. The control record's encryption is not a replacement for target validation.
        when (verified) {
            is RetirementState.Pending -> requireRetirement(data.validateRetirement(verified.scope, verified.target))
            is RetirementState.SetupDiscardPending -> verified.target?.let { requireRetirement(data.validateEmptyRetirement(verified.scope, it)) }
        }
        val failures = mutableMapOf<RetirementStep, FailureReason>()
        for (step in RetirementStep.entries) {
            val pending = entry.state as RetirementState.InFlight
            if (step !in pending.requiredSteps || step in pending.done) continue
            // All are exact-incarnation, idempotent local cleanup. Independent cleanup continues
            // after a typed failure; a cancellation leaves the durable barrier for restart.
            val outcome = try {
                when (step) {
                    RetirementStep.NATIVE_WORK -> if (pending is RetirementState.SetupDiscardPending) {
                        val emptyWork = work as? EmptySessionWorkRetirementPort
                        emptyWork?.retireEmpty(pending.scope, checkNotNull(pending.origin)) ?: PortResult.Failure(FailureReason.NOT_CONFIGURED)
                    } else work.retire(pending.scope, checkNotNull(pending.origin))
                    RetirementStep.CREDENTIALS -> credentials.retire(pending.scope, checkNotNull(pending.credentialIncarnation))
                    RetirementStep.PRIVATE_DATA -> if (pending is RetirementState.SetupDiscardPending)
                        data.recoverEmptyRetirement(checkNotNull(pending.target)) else data.recoverRetirement(checkNotNull(pending.target))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
            when (outcome) {
                is PortResult.Failure -> failures[step] = outcome.reason
                is PortResult.Value -> entry = write(entry, pending.withDone(step))
            }
        }
        val pending = entry.state as RetirementState.InFlight
        if (pending.done == pending.requiredSteps) {
            entry = write(entry, RetirementState.Complete(pending.operationId))
            ProcessRetirements.remove(boundary, pending.operationId)
        }
        return progress(entry.state, failures)
    }

    private fun progress(state: RetirementState, failures: Map<RetirementStep, FailureReason> = emptyMap()): LocalRetirementProgress = when (state) {
        RetirementState.Idle -> LocalRetirementProgress(LocalRetirementPhase.IDLE, null, emptySet())
        is RetirementState.Complete -> LocalRetirementProgress(LocalRetirementPhase.COMPLETE, state.operationId, emptySet())
        is RetirementState.InFlight -> LocalRetirementProgress(LocalRetirementPhase.PENDING, state.operationId, state.requiredSteps - state.done, failures)
        is RetirementState.PendingCreate -> failRetirement(FailureReason.CONFLICT)
    }

    private suspend fun read(): Entry {
        val record = requireRetirement(control.read()) ?: failRetirement(FailureReason.STORAGE_FAILURE)
        return Entry(record, RetirementCodec.decode(record.payload))
    }
    private suspend fun write(expected: Entry, state: RetirementState): Entry {
        val payload = RetirementCodec.encode(state)
        val result = control.compareAndSet(expected.record.revision, payload)
        if (result is PortResult.Value) {
            if (result.value.revision <= expected.record.revision || !result.value.payload.copyForCodec().contentEquals(payload.copyForCodec()))
                failRetirement(FailureReason.STORAGE_FAILURE)
            return Entry(result.value, state)
        }
        val reason = (result as PortResult.Failure).reason
        if (reason == FailureReason.OUTCOME_UNKNOWN) {
            // Reconcile this exact possibly committed CAS; never create another logout ID.
            val observed = read()
            if (observed.record.revision > expected.record.revision && observed.record.payload.copyForCodec().contentEquals(payload.copyForCodec())) return observed
        }
        failRetirement(reason)
    }
    private fun current(lease: SessionLease) {
        if (!boundary.isCurrent(lease)) failRetirement(FailureReason.STALE_SESSION)
        if (lease.scope.actorKind == ActorKind.DEMO) failRetirement(FailureReason.UNAUTHENTICATED)
    }
    private suspend fun <T> guarded(action: suspend () -> T): PortResult<T> = withContext(dispatcher) {
        try { PortResult.Value(action()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: RetirementFailure) { PortResult.Failure(failure.reason) }
        catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
    }
    private class Entry(val record: SessionControlRecord, val state: RetirementState)
}

private class PendingLatch(val state: RetirementState.InFlight, val expectedRevision: Long, val expectedPayload: PrivateBytes? = null)
private fun sameIntent(left: RetirementState.InFlight, right: RetirementState.InFlight): Boolean =
    left::class == right::class && left.operationId == right.operationId && left.scope == right.scope && left.origin == right.origin &&
        left.credentialIncarnation == right.credentialIncarnation && sameTarget(left.target, right.target)

private fun sameTarget(left: com.feedme.storage.StateRetirementTarget?, right: com.feedme.storage.StateRetirementTarget?): Boolean {
    if (left == null || right == null) return left == null && right == null
    val a = left.copyForStorage(); val b = right.copyForStorage()
    return try { a.contentEquals(b) } finally { a.fill(0); b.fill(0) }
}
private fun samePayload(left: PrivateBytes, right: PrivateBytes): Boolean {
    val a = left.copyForCodec(); val b = right.copyForCodec()
    return try { a.contentEquals(b) } finally { a.fill(0); b.fill(0) }
}

/** Application-boundary lifetime only. This is not a substitute for the durable control record. */
private object ProcessRetirements {
    private val mutex = Mutex()
    private val pending = mutableMapOf<SessionBoundary, PendingLatch>()
    suspend fun get(boundary: SessionBoundary): PendingLatch? = mutex.withLock { pending[boundary] }
    suspend fun install(boundary: SessionBoundary, value: PendingLatch) = mutex.withLock {
        val old = pending[boundary]
        if (old != null && !sameIntent(old.state, value.state)) failRetirement(FailureReason.CONFLICT)
        if (old == null) pending[boundary] = value
    }
    suspend fun remove(boundary: SessionBoundary, operationId: String) = mutex.withLock {
        if (pending[boundary]?.state?.operationId != operationId) failRetirement(FailureReason.CONFLICT)
        pending.remove(boundary)
        Unit
    }
}
