@file:OptIn(com.feedme.storage.WorkRecoveryCompositionApi::class)

package com.feedme.session

import com.feedme.core.ports.*
import com.feedme.storage.ExistingSessionWorkRecoveryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Existing-only recovery of one exact authenticated setup work plan. Keep the owner before
 * calling open and until close acknowledges every release, including after failed/cancelled
 * opening. No raw ledger, signing, ordinary registry, IDs, scheduler or lease is exposed.
 * Inspection is advisory; abort is a changed acknowledgement, not user consent or permission to
 * clear independent setup control. The trusted coordinator must authorize that exact operation.
 */
interface SessionWorkOriginRecoveryOwner {
    suspend fun open(): PortResult<Unit>
    suspend fun inspect(): PortResult<SessionWorkOriginObservation>
    suspend fun abort(): PortResult<Unit>
    suspend fun close(): PortResult<Unit>
}

/** Native composition retains the lower owner synchronously, before any opening can suspend. */
internal class RetainedSessionWorkOriginRecoveryOwner(
    scope: StorageScope,
    plan: SessionWorkOriginPlan,
    private val store: ExistingSessionWorkRecoveryStore,
    private val boundary: SessionBoundary,
    private val dispatcher: CoroutineDispatcher,
) : SessionWorkOriginRecoveryOwner {
    private val scope = scope.copy()
    private val copiedPlan = SessionWorkOriginPlan.fromStorage(plan.copyForStorage())
    private val mutex = Mutex()
    private var attempted = false
    private var ready = false
    private var closeRequested = false
    private var closed = false
    private val protocol = SessionWorkSetupProtocol(store, {
        if (closeRequested || boundary.current() != null) FailureReason.STALE_SESSION else null
    })

    override suspend fun open(): PortResult<Unit> {
        var admitted = false
        try {
            val outcome = withContext(dispatcher) {
                mutex.withLock {
                    if (attempted) return@withLock PortResult.Failure(FailureReason.CONFLICT)
                    attempted = true
                    admitted = true
                    currentCoroutineContext().ensureActive()
                    if (boundary.current() != null) return@withLock PortResult.Failure(FailureReason.STALE_SESSION)
                    val plan = validPlan() ?: return@withLock PortResult.Failure(FailureReason.INVALID_DATA)
                    val opened = call { store.open() }
                    if (opened is PortResult.Failure) return@withLock opened
                    if (boundary.current() != null) return@withLock PortResult.Failure(FailureReason.STALE_SESSION)
                    when (val inspected = protocol.inspect(plan)) {
                        is PortResult.Failure -> inspected
                        is PortResult.Value -> PortResult.Value(Unit)
                    }
                }
            }
            if (!admitted || outcome is PortResult.Failure) return outcome
            // Do not publish while the I/O result is waiting on a cancellable caller handoff.
            return mutex.withLock {
                currentCoroutineContext().ensureActive()
                if (closeRequested || boundary.current() != null) PortResult.Failure(FailureReason.STALE_SESSION)
                else { ready = true; outcome }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + dispatcher) {
                mutex.withLock {
                    // A cancelled rejected second opener never invalidates the admitted owner.
                    if (admitted || !attempted) { attempted = true; ready = false; closeRequested = true }
                }
            }
            throw cancelled
        }
    }

    override suspend fun inspect(): PortResult<SessionWorkOriginObservation> = whenReady { protocol.inspect(it) }
    override suspend fun abort(): PortResult<Unit> = whenReady { protocol.abort(it) }

    override suspend fun close(): PortResult<Unit> = withContext(NonCancellable + dispatcher) {
        mutex.withLock {
            attempted = true
            ready = false
            closeRequested = true
            if (closed) return@withLock PortResult.Value(Unit)
            val result = try { store.close() }
                catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
            if (result is PortResult.Value) closed = true
            result
        }
    }

    private suspend fun <T> whenReady(action: suspend (SessionWorkOriginPlan) -> PortResult<T>): PortResult<T> =
        withContext(dispatcher) {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                if (!ready || closeRequested || boundary.current() != null) PortResult.Failure(FailureReason.STALE_SESSION)
                else call { action(checkNotNull(validPlan())) }
            }
        }

    private fun validPlan(): SessionWorkOriginPlan? {
        return try {
            val value = (copiedPlan as? PortResult.Value)?.value ?: return null
            val details = SessionWorkOriginPlanCodec.decode(value.copyForStorage())
            if (scope.actorKind == ActorKind.DEMO || details.scope != scope) null else value
        } catch (_: Exception) { null }
    }

    private suspend fun <T> call(action: suspend () -> PortResult<T>): PortResult<T> {
        currentCoroutineContext().ensureActive()
        val result = try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
        currentCoroutineContext().ensureActive()
        return result
    }

    override fun toString() = "SessionWorkOriginRecoveryOwner(<redacted>)"
}
