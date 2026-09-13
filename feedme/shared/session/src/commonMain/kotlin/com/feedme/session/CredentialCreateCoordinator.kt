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

/** Native presentation proposal only. It exposes neither the plan nor credential identity. */
class PendingCredentialCreate internal constructor(
    internal val owner: Any,
    internal val control: SessionControlRecord,
    internal val plan: CredentialCreatePlan,
    val abortRequested: Boolean,
) {
    override fun toString() = "PendingCredentialCreate(<redacted>)"
}

/**
 * Startup-only CREATE recovery using an existing-only native handle. All users of control and
 * boundary share the application's serialized dispatcher. This owns no native store and never
 * activates, commits a create, decrypts credentials or aborts an unconfirmed plan automatically.
 */
class CredentialCreateCoordinator(
    private val control: SessionControlStore,
    private val boundary: SessionBoundary,
    private val dispatcher: CoroutineDispatcher,
    private val recovery: CredentialCreateRecoveryFactory,
) {
    private val mutex = Mutex()
    private val owner = Any()
    private val protocol get() = CredentialCreateControl(control, ::inactive)

    suspend fun inspectPending(): PortResult<PendingCredentialCreate?> = owned {
        mutex.withLock {
            inactive()
            val record = protocol.read()
            when (val state = RetirementCodec.decode(record.payload)) {
                RetirementState.Idle, is RetirementState.Complete -> null
                is RetirementState.PendingCreate -> PendingCredentialCreate(owner, record, state.plan, state.abortRequested)
                is RetirementState.InFlight, is RetirementState.PendingSetup -> createFail(FailureReason.CONFLICT)
            }
        }
    }

    /** Explicit confirmation of this exact observed plan; requested intent precedes native open. */
    suspend fun requestAbort(pending: PendingCredentialCreate): PortResult<Unit> = owned {
        if (pending.owner !== owner) createFail(FailureReason.STALE_SESSION)
        mutex.withLock {
            inactive()
            val current = protocol.read()
            if (!sameCreateRecord(current, pending.control)) createFail(FailureReason.CONFLICT)
            val state = RetirementCodec.decode(current.payload) as? RetirementState.PendingCreate ?: createFail(FailureReason.CONFLICT)
            if (state.abortRequested || pending.abortRequested || !sameCreatePlan(state.plan, pending.plan)) createFail(FailureReason.CONFLICT)
            val requested = protocol.write(current, RetirementState.PendingCreate(state.plan, true))
            finishAbort(requested)
        }
    }

    /** Replay only an already-confirmed durable abort; never infer confirmation from a crash. */
    suspend fun recoverAbort(): PortResult<Unit> = owned {
        mutex.withLock {
            inactive()
            val record = protocol.read()
            val state = RetirementCodec.decode(record.payload) as? RetirementState.PendingCreate ?: createFail(FailureReason.CONFLICT)
            if (!state.abortRequested) createFail(FailureReason.CONFLICT)
            // A readable confirmation may be the result of an uncertain prior commit. Re-ack
            // the same exact plan with a changed revision before acquiring any native handle.
            finishAbort(protocol.reacknowledge(record))
        }
    }

    private suspend fun finishAbort(record: SessionControlRecord) {
        val state = RetirementCodec.decode(record.payload) as? RetirementState.PendingCreate ?: createFail(FailureReason.CONFLICT)
        if (!state.abortRequested) createFail(FailureReason.CONFLICT)
        val opened = native { recovery.open(state.plan) }
        // Own the handle before the post-await boundary check so even a stale open is closed.
        val handle = createValue(opened)
        var closeFailure: FailureReason? = null
        try {
            currentCoroutineContext().ensureActive()
            inactive()
            observed { handle.inspect() }
            if (!sameCreateRecord(record, protocol.read())) createFail(FailureReason.CONFLICT)
            // A read-only ABORTED observation is not a durable acknowledgement of an earlier
            // uncertain rename. Native abort is idempotent and must re-acknowledge consumption.
            currentCoroutineContext().ensureActive()
            val result = native { handle.abort() }
            currentCoroutineContext().ensureActive()
            inactive()
            if (result is PortResult.Failure) createFail(result.reason)
            if (observed { handle.inspect() } != CredentialCreateRecoveryStatus.ABORTED)
                createFail(FailureReason.STORAGE_FAILURE)
        } finally {
            withContext(NonCancellable + dispatcher) {
                val closed = native { handle.close() }
                if (closed is PortResult.Failure) closeFailure = closed.reason
            }
        }
        currentCoroutineContext().ensureActive()
        inactive()
        closeFailure?.let(::createFail)
        if (!sameCreateRecord(record, protocol.read())) createFail(FailureReason.CONFLICT)
        val operation = CredentialCreatePlanCodec.decode(state.plan.copyForStorage()).incarnation
        protocol.write(record, RetirementState.Complete(operation))
    }

    private fun inactive() { if (boundary.current() != null) createFail(FailureReason.STALE_SESSION) }
    private suspend fun <T> observed(action: suspend () -> PortResult<T>): T {
        currentCoroutineContext().ensureActive()
        val result = native(action)
        currentCoroutineContext().ensureActive()
        inactive()
        return createValue(result)
    }
    private suspend fun <T> owned(action: suspend () -> T): PortResult<T> = withContext(dispatcher) {
        try { currentCoroutineContext().ensureActive(); inactive(); PortResult.Value(action()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: CredentialCreateFailure) { PortResult.Failure(failure.reason) }
        catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
    }
}

/** Live verified create only. Durable credential ownership resolves before unplanned data/work. */
internal suspend fun commitCredentialCreate(
    control: SessionControlStore,
    store: PlannedCredentialCreateStore,
    expectedSlotRevision: Long,
    credentials: StoredCredentials,
    checkCurrent: () -> Unit,
): PortResult<CredentialSnapshot> = try {
    currentCoroutineContext().ensureActive()
    val protocol = CredentialCreateControl(control, checkCurrent)
    val initial = protocol.read()
    if (RetirementCodec.decode(initial.payload).blocksAccess()) createFail(FailureReason.CONFLICT)
    val planned = native { store.planCreate(expectedSlotRevision, credentials) }
    currentCoroutineContext().ensureActive()
    checkCurrent()
    val plan = createValue(planned)
    val details = CredentialCreatePlanCodec.decode(plan.copyForStorage())
    if (details.expectedSlotRevision != expectedSlotRevision) createFail(FailureReason.CONFLICT)
    if (!sameCreateRecord(initial, protocol.read())) createFail(FailureReason.CONFLICT)
    val pending = protocol.write(initial, RetirementState.PendingCreate(plan, false))
    val result = native { store.commitPlannedCreate(plan, credentials) }
    currentCoroutineContext().ensureActive()
    checkCurrent()
    val snapshot = createValue(result)
    if (snapshot.scope != credentials.scope || snapshot.incarnation != details.incarnation || snapshot.revision != details.snapshotRevision)
        createFail(FailureReason.STALE_SESSION)
    val expected = CredentialCodec.encodeSnapshot(CredentialSnapshot(details.incarnation, details.snapshotRevision, credentials))
    if (!sameCreateBytes(expected, CredentialCodec.encodeSnapshot(snapshot))) createFail(FailureReason.STALE_SESSION)
    if (!sameCreateRecord(pending, protocol.read())) createFail(FailureReason.CONFLICT)
    protocol.write(pending, RetirementState.Complete(details.incarnation))
    PortResult.Value(snapshot)
} catch (cancelled: CancellationException) { throw cancelled }
catch (failure: CredentialCreateFailure) { PortResult.Failure(failure.reason) }

/** Exact readback is mandatory even when the CAS adapter returned a success receipt. */
private class CredentialCreateControl(private val control: SessionControlStore, private val checkCurrent: () -> Unit) {
    suspend fun reacknowledge(expected: SessionControlRecord): SessionControlRecord =
        createValue(control.acknowledge(expected, expected.payload, checkCurrent))
    suspend fun read(): SessionControlRecord {
        currentCoroutineContext().ensureActive()
        val result = native { control.read() }
        currentCoroutineContext().ensureActive()
        checkCurrent()
        return (createValue(result) ?: createFail(FailureReason.STORAGE_FAILURE)).also { RetirementCodec.decode(it.payload) }
    }
    suspend fun write(expected: SessionControlRecord, state: RetirementState): SessionControlRecord {
        val payload = RetirementCodec.encode(state)
        return createValue(control.acknowledge(expected, payload, checkCurrent))
    }
}

private suspend fun <T> native(action: suspend () -> PortResult<T>): PortResult<T> = try { action() }
catch (cancelled: CancellationException) { throw cancelled }
catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
private class CredentialCreateFailure(val reason: FailureReason) : Exception("Credential create unavailable")
private fun createFail(reason: FailureReason): Nothing = throw CredentialCreateFailure(reason)
private fun <T> createValue(result: PortResult<T>): T = when (result) {
    is PortResult.Value -> result.value
    is PortResult.Failure -> createFail(result.reason)
}
private fun sameCreatePlan(a: CredentialCreatePlan, b: CredentialCreatePlan) = sameCreateBytes(a.copyForStorage(), b.copyForStorage())
private fun sameCreateRecord(a: SessionControlRecord, b: SessionControlRecord) = a.revision == b.revision && sameCreateBytes(a.payload, b.payload)
private fun sameCreateBytes(a: PrivateBytes, b: PrivateBytes): Boolean {
    val left = a.copyForCodec(); val right = b.copyForCodec()
    return try { left.contentEquals(right) } finally { left.fill(0); right.fill(0) }
}
