@file:OptIn(com.feedme.storage.SessionControlRecoveryCompositionApi::class)

package com.feedme.session

import com.feedme.core.ports.*
import com.feedme.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SessionSetupRecoveryPhase { NEW, OPENING, READY, CLOSING, COMPLETE, CLOSE_ONLY, CLOSED }

/**
 * Application-owned startup recovery, never an authentication or automatic-erasure API.
 * Construct and retain before open. Failed/cancelled opening is close-only. A proposal is not
 * consent: explicit confirmation must acknowledge the original independent PendingSetup first.
 * Retry resumes only previously confirmed intent. Complete alone is never recovery authority.
 * Keep this owner through failed close; it owns the composition reservation through CONTROL
 * close. The caller must not separately close its private subordinate owners.
 */
interface SessionSetupRecoveryOwner {
    suspend fun open(): PortResult<Unit>
    suspend fun phase(): SessionSetupRecoveryPhase
    suspend fun inspect(): PortResult<InterruptedSetupReport>
    suspend fun prepareAbort(): PortResult<PreparedSessionSetupAbort>
    suspend fun confirmAbort(prepared: PreparedSessionSetupAbort): PortResult<Unit>
    suspend fun retryAbort(): PortResult<Unit>
    suspend fun close(): PortResult<Unit>
}

/** Synchronous native factories only. Every returned owner is retained before its open call. */
internal interface SessionSetupRecoveryFactories {
    fun control(): ExistingSessionControlRecoveryStore
    fun credentials(scope: StorageScope, plan: CredentialCreatePlan): CredentialCreateRecoveryOwner
    fun data(scope: StorageScope, plan: StateActivationPlan): StateActivationRecoveryOwner
    fun work(scope: StorageScope, plan: SessionWorkOriginPlan): SessionWorkOriginRecoveryOwner
}

internal class RetainedSessionSetupRecoveryOwner(
    private val reservation: SessionCompositionReservation,
    private val factories: SessionSetupRecoveryFactories,
) : SessionSetupRecoveryOwner {
    private val dispatcher = reservation.root.dispatcher
    private val configuration = reservation.root.configuration
    private val boundary = reservation.root.boundary
    private val mutex = Mutex()
    private val generation = Any()
    private val claim = SessionCompositions.claim(reservation, this)
    private var state = SessionSetupRecoveryPhase.NEW
    private var closeRequested = false
    private var control: ExistingSessionControlRecoveryStore? = null
    private var credentials: CredentialCreateRecoveryOwner? = null
    private var data: StateActivationRecoveryOwner? = null
    private var work: SessionWorkOriginRecoveryOwner? = null
    private var controlClosed = false
    private var credentialClosed = false
    private var dataClosed = false
    private var workClosed = false
    private var original: SessionSetupPlanRecord? = null
    private var coordinator: CompositeSetupAbortCoordinator? = null
    private var checkpoint: AllAborted? = null

    override suspend fun phase(): SessionSetupRecoveryPhase = withContext(dispatcher) { mutex.withLock { state } }

    override suspend fun open(): PortResult<Unit> {
        var admitted = false
        try {
            val outcome = withContext(dispatcher) {
                mutex.withLock {
                    if (state != SessionSetupRecoveryPhase.NEW) return@withLock failure(FailureReason.CONFLICT)
                    state = SessionSetupRecoveryPhase.OPENING; admitted = true
                    val result = operation {
                        value(claim)
                        if (pending()) fail(FailureReason.CONFLICT)
                        // Retain the control owner before its first possible I/O or suspension.
                        control = factories.control()
                        call { control!!.open() }
                        val entry = readControl()
                        val pending = decode(entry) as? RetirementState.PendingSetup ?: fail(FailureReason.CONFLICT)
                        val plan = SessionSetupPlanCodec.decode(pending.plan.copyForStorage())
                        if (plan.configurationBinding != configuration) fail(FailureReason.CONFLICT)
                        original = plan
                        credentials = factories.credentials(plan.scope, plan.credentialPlan)
                        data = factories.data(plan.scope, plan.dataPlan)
                        work = factories.work(plan.scope, plan.workOriginPlan)
                        call { credentials!!.open() }; barrier(entry)
                        call { data!!.open() }; barrier(entry)
                        call { work!!.open() }; barrier(entry)
                        val resources = PinnedResources(plan)
                        value(InterruptedSetupInspector(control!!, resources, configuration,
                            ::pending, ::checkCurrent).capture())
                        barrier(entry)
                        coordinator = CompositeSetupAbortCoordinator(control!!, resources, configuration,
                            this@RetainedSessionSetupRecoveryOwner, generation, ::pending, ::checkCurrent,
                            allAborted = { evidence, requested ->
                                // Synchronous retention BEFORE any close. No Complete fallthrough.
                                checkpoint = AllAborted(evidence, requested)
                                state = SessionSetupRecoveryPhase.CLOSING
                            })
                    }
                    if (result is PortResult.Failure) { closeRequested = true; state = SessionSetupRecoveryPhase.CLOSE_ONLY }
                    result
                }
            }
            if (!admitted || outcome is PortResult.Failure) return outcome
            // READY must not be visible during a cancelled dispatcher-return handoff.
            return mutex.withLock {
                currentCoroutineContext().ensureActive()
                if (closeRequested || state != SessionSetupRecoveryPhase.OPENING) failure(FailureReason.STALE_SESSION)
                else when (val current = SessionCompositions.checkClaim(reservation, this)) {
                    is PortResult.Failure -> { state = SessionSetupRecoveryPhase.CLOSE_ONLY; closeRequested = true; current }
                    is PortResult.Value -> { state = SessionSetupRecoveryPhase.READY; outcome }
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + dispatcher) {
                mutex.withLock {
                    if (admitted || state == SessionSetupRecoveryPhase.NEW) {
                        closeRequested = true; state = SessionSetupRecoveryPhase.CLOSE_ONLY
                    }
                }
            }
            throw cancelled
        }
    }

    override suspend fun inspect(): PortResult<InterruptedSetupReport> = ready {
        InterruptedSetupInspector(control!!, PinnedResources(original!!), configuration, ::pending, ::checkCurrent).inspect()
    }
    override suspend fun prepareAbort(): PortResult<PreparedSessionSetupAbort> = ready {
        value(coordinator!!.prepare())
    }
    override suspend fun confirmAbort(prepared: PreparedSessionSetupAbort): PortResult<Unit> = ready {
        value(coordinator!!.confirm(prepared))
        finishClosing()
    }
    override suspend fun retryAbort(): PortResult<Unit> = withContext(dispatcher) {
        mutex.withLock {
            operation {
                if (state !in setOf(SessionSetupRecoveryPhase.READY, SessionSetupRecoveryPhase.CLOSING)) stale()
                if (checkpoint == null) value(coordinator!!.retry())
                finishClosing()
            }
        }
    }

    /** Abandoning recovery never writes Complete, even if its three targets were already erased. */
    override suspend fun close(): PortResult<Unit> = withContext(NonCancellable + dispatcher) {
        closeRequested = true
        mutex.withLock {
            if (state == SessionSetupRecoveryPhase.CLOSED) return@withLock PortResult.Value(Unit)
            state = SessionSetupRecoveryPhase.CLOSE_ONLY
            var failed: PortResult.Failure? = null
            suspend fun release(owner: (suspend () -> PortResult<Unit>)?, done: Boolean, mark: () -> Unit) {
                if (done) return
                val result = try { owner?.invoke() ?: PortResult.Value(Unit) }
                    catch (_: Exception) { failure(FailureReason.STORAGE_FAILURE) }
                if (result is PortResult.Value) mark() else if (failed == null) failed = result as PortResult.Failure
            }
            release(work?.let { { it.close() } }, workClosed) { workClosed = true }
            release(data?.let { { it.close() } }, dataClosed) { dataClosed = true }
            release(credentials?.let { { it.close() } }, credentialClosed) { credentialClosed = true }
            // Keep independent control ownership while ANY subordinate release is unresolved.
            if (failed != null) return@withLock failed!!
            release(control?.let { { it.close() } }, controlClosed) { controlClosed = true }
            if (failed != null) return@withLock failed!!
            if (claim is PortResult.Value) {
                val released = SessionCompositions.release(reservation, this@RetainedSessionSetupRecoveryOwner)
                if (released is PortResult.Failure) return@withLock released
            }
            checkpoint = null; coordinator = null; original = null
            state = SessionSetupRecoveryPhase.CLOSED
            PortResult.Value(Unit)
        }
    }

    private suspend fun finishClosing() {
        val saved = checkpoint ?: fail(FailureReason.STORAGE_FAILURE)
        check()
        state = SessionSetupRecoveryPhase.CLOSING
        // No resource observation after this checkpoint: some may already be closed.
        if (!workClosed || !dataClosed || !credentialClosed) barrier(saved.control)
        closeOne(workClosed, { work!!.close() }, { workClosed = true }, saved.control)
        closeOne(dataClosed, { data!!.close() }, { dataClosed = true }, saved.control)
        closeOne(credentialClosed, { credentials!!.close() }, { credentialClosed = true }, saved.control)
        check()
        val entry = readControl()
        if (!sameRecord(entry, saved.control) &&
            (saved.attemptedComplete == null || !sameRecord(entry, saved.attemptedComplete!!))) fail(FailureReason.CONFLICT)
        val state = decode(entry)
        if (state !is RetirementState.PendingSetup &&
            (state !is RetirementState.Complete || state.operationId != saved.evidence.plan.operationId)) fail(FailureReason.CONFLICT)
        // A visible prior attempted Complete is accepted only with this retained close checkpoint.
        // Preserve it as the retry base before recording the NEXT attempted changed receipt.
        saved.control = entry
        if (entry.revision == Long.MAX_VALUE) fail(FailureReason.STORAGE_FAILURE)
        val payload = RetirementCodec.encode(RetirementState.Complete(saved.evidence.plan.operationId))
        saved.attemptedComplete = SessionControlRecord(entry.revision + 1, payload)
        barrier(entry)
        val acknowledged = value(control!!.acknowledge(entry, payload, ::checkCurrent))
        saved.control = acknowledged
        check(); barrier(acknowledged)
        this.state = SessionSetupRecoveryPhase.COMPLETE
    }

    private suspend fun closeOne(done: Boolean, action: suspend () -> PortResult<Unit>,
        mark: () -> Unit, expected: SessionControlRecord) {
        if (done) return
        barrier(expected)
        // Record the acknowledged close inside NonCancellable before checking caller cancellation.
        val result = withContext(NonCancellable) {
            val outcome = try { action() } catch (_: Exception) { failure(FailureReason.STORAGE_FAILURE) }
            if (outcome is PortResult.Value) mark()
            outcome
        }
        check(); value(result); barrier(expected)
    }

    private inner class PinnedResources(private val plan: SessionSetupPlanRecord) : CompositeSetupAbortResources {
        override val credentialAbortAvailable = true
        private fun validateScope(value: StorageScope) { if (value != plan.scope) fail(FailureReason.CONFLICT) }
        private fun validateCredentials(value: CredentialCreatePlan) { if (!sameBytes(value.copyForStorage(), plan.credentialPlan.copyForStorage())) fail(FailureReason.CONFLICT) }
        private fun validateData(value: StateActivationPlan) {
            val a = value.copyForStorage(); val b = plan.dataPlan.copyForStorage()
            try { if (!a.contentEquals(b)) fail(FailureReason.CONFLICT) } finally { a.fill(0); b.fill(0) }
        }
        private fun validateWork(value: SessionWorkOriginPlan) { if (!sameBytes(value.copyForStorage(), plan.workOriginPlan.copyForStorage())) fail(FailureReason.CONFLICT) }
        override suspend fun credentials(scope: StorageScope, plan: CredentialCreatePlan): PortResult<CredentialCreatePlanObservation> {
            validateScope(scope); validateCredentials(plan); return this@RetainedSessionSetupRecoveryOwner.credentials!!.inspect()
        }
        override suspend fun data(scope: StorageScope, plan: StateActivationPlan): PortResult<StateActivationPlanObservation> {
            validateScope(scope); validateData(plan); return this@RetainedSessionSetupRecoveryOwner.data!!.inspect()
        }
        override suspend fun binding(scope: StorageScope, plan: StateActivationPlan): PortResult<StateRecordInspection> {
            validateScope(scope); validateData(plan); return this@RetainedSessionSetupRecoveryOwner.data!!.binding()
        }
        override suspend fun work(plan: SessionWorkOriginPlan): PortResult<SessionWorkOriginObservation> {
            validateWork(plan); return this@RetainedSessionSetupRecoveryOwner.work!!.inspect()
        }
        override suspend fun abortCredentials(scope: StorageScope, plan: CredentialCreatePlan): PortResult<Unit> {
            validateScope(scope); validateCredentials(plan); return this@RetainedSessionSetupRecoveryOwner.credentials!!.abort()
        }
        override suspend fun abortData(scope: StorageScope, plan: StateActivationPlan, expectedBinding: PrivateBytes?): PortResult<Unit> {
            validateScope(scope); validateData(plan); return this@RetainedSessionSetupRecoveryOwner.data!!.abort(expectedBinding)
        }
        override suspend fun abortWork(plan: SessionWorkOriginPlan): PortResult<Unit> {
            validateWork(plan); return this@RetainedSessionSetupRecoveryOwner.work!!.abort()
        }
    }

    private suspend fun readControl(): SessionControlRecord {
        check()
        if (pending()) fail(FailureReason.CONFLICT)
        val record = call { control!!.read() } ?: fail(FailureReason.STORAGE_FAILURE)
        if (record.revision <= 0) fail(FailureReason.STORAGE_FAILURE)
        decode(record)
        if (pending()) fail(FailureReason.CONFLICT)
        return record
    }
    private suspend fun barrier(expected: SessionControlRecord) {
        if (!sameRecord(readControl(), expected)) fail(FailureReason.CONFLICT)
        check()
    }
    private fun decode(record: SessionControlRecord) = try { RetirementCodec.decode(record.payload) }
        catch (_: Exception) { fail(FailureReason.INVALID_DATA) }
    private suspend fun pending(): Boolean {
        check(); val pending = processRetirementPending(boundary); check(); return pending
    }
    private fun checkCurrent() {
        value(claim)
        if (closeRequested || state !in setOf(SessionSetupRecoveryPhase.OPENING, SessionSetupRecoveryPhase.READY, SessionSetupRecoveryPhase.CLOSING)) stale()
        value(SessionCompositions.check(reservation, this))
    }
    private suspend fun check() { currentCoroutineContext().ensureActive(); checkCurrent() }
    private suspend fun <T> call(action: suspend () -> PortResult<T>): T { check(); val result = action(); check(); return value(result) }
    private suspend fun <T> ready(action: suspend () -> T): PortResult<T> = withContext(dispatcher) {
        mutex.withLock { operation { if (state != SessionSetupRecoveryPhase.READY) stale(); action() } }
    }
    private suspend fun <T> operation(action: suspend () -> T): PortResult<T> = try {
        check(); val result = action(); currentCoroutineContext().ensureActive()
        // COMPLETE is a successful operation result, but never a subsequent cleanup capability.
        if (state != SessionSetupRecoveryPhase.COMPLETE) checkCurrent()
        PortResult.Value(result)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failed: RecoveryFailure) { failure(failed.reason) }
    catch (_: Exception) { failure(FailureReason.STORAGE_FAILURE) }

    private class AllAborted(val evidence: InterruptedSetupEvidence, var control: SessionControlRecord) {
        var attemptedComplete: SessionControlRecord? = null
    }
    private class RecoveryFailure(val reason: FailureReason) : Exception("Startup setup recovery unavailable")
    private fun fail(reason: FailureReason): Nothing = throw RecoveryFailure(reason)
    private fun stale(): Nothing = fail(FailureReason.STALE_SESSION)
    private fun failure(reason: FailureReason) = PortResult.Failure(reason)
    private fun <T> value(result: PortResult<T>): T = when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> fail(result.reason)
    }
    private fun sameRecord(a: SessionControlRecord, b: SessionControlRecord) = a.revision == b.revision && sameBytes(a.payload, b.payload)
    private fun sameBytes(a: PrivateBytes, b: PrivateBytes): Boolean {
        val left = a.copyForCodec(); val right = b.copyForCodec()
        return try { left.contentEquals(right) } finally { left.fill(0); right.fill(0) }
    }
    override fun toString() = "SessionSetupRecoveryOwner(<redacted>)"
}
