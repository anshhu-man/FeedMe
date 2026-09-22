package com.feedme.server.identity

import com.feedme.server.media.supabase.SupabaseStorageEraseResult
import com.feedme.server.media.supabase.SupabaseStorageErasureHttp
import com.feedme.server.media.supabase.SupabaseStorageErasureTarget
import com.feedme.server.media.supabase.SupabaseStoragePresence
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

internal interface AccountMediaSourceErasureObjects {
    val configured: Boolean
    fun acceptsTarget(target: SupabaseStorageErasureTarget): Boolean
    fun eraseExact(target: SupabaseStorageErasureTarget): SupabaseStorageEraseResult
    fun inspectExact(target: SupabaseStorageErasureTarget): SupabaseStoragePresence
}

/** Explicit adapter only; does not load secrets or enable a public upload/deletion route. */
internal class SupabaseAccountMediaSourceErasureObjects(private val http: SupabaseStorageErasureHttp) : AccountMediaSourceErasureObjects {
    // V049 accepts only this issuer. A reusable storage adapter configured for a
    // different project must not consume the accepted account's one-shot intent.
    override val configured get() = http.configuredProjectOrigin ==
        SupabaseAuthErasureClient.APPROVED_ISSUER.removeSuffix("/auth/v1")
    override fun acceptsTarget(target: SupabaseStorageErasureTarget) = configured && http.acceptsTarget(target)
    override fun eraseExact(target: SupabaseStorageErasureTarget) =
        if (configured) http.eraseExact(target) else SupabaseStorageEraseResult.NOT_CONFIGURED
    override fun inspectExact(target: SupabaseStorageErasureTarget) =
        if (configured) http.inspectExact(target) else SupabaseStoragePresence.NOT_CONFIGURED
    override fun toString() = "SupabaseAccountMediaSourceErasureObjects(<redacted>)"
}

internal enum class AccountMediaSourceErasureRun {
    NOT_CONFIGURED, HELD_OR_LEASE_LOST, RECONCILIATION_REQUIRED, DELETE_OBSERVATION_RECORDED, PRESENCE_OBSERVATION_RECORDED,
}

/** One explicit operation, never a scheduler. Database commits bracket external I/O;
 * a lost/cancelled dispatch acknowledgement cannot authorize a DELETE. Once dispatched,
 * eraseOnce never sends another DELETE, even if the previous process died before I/O.
 * reconcile is separately invoked and may record absence then later presence. Neither
 * result permits media/core purge or claims signed-upload settlement/completion.
 */
internal class AccountMediaSourceErasureWorker(
    private val store: AccountMediaSourceErasurePersistence,
    private val dispatcher: CoroutineDispatcher,
    private val objects: AccountMediaSourceErasureObjects? = null,
    private val captures: AccountMediaSourceCapturePersistence? = null,
) {
    suspend fun eraseOnce(lease: AccountErasureWorkLease, mediaId: UUID): AccountMediaSourceErasureRun {
        currentCoroutineContext().ensureActive()
        val provider = objects ?: return AccountMediaSourceErasureRun.NOT_CONFIGURED
        if (!provider.configured) return AccountMediaSourceErasureRun.NOT_CONFIGURED
        val original = runInterruptible(dispatcher) { store.prepare(lease, mediaId) }
            ?: return AccountMediaSourceErasureRun.HELD_OR_LEASE_LOST
        return erasePrepared(lease, original, provider)
    }

    /** Capture an untouched reservation under the accepted account job, then use
     * the same V049 dispatch fence. Unknown capture commits never authorize HTTP.
     * Capture is not a fabricated client DELETE or proof of provider settlement. */
    suspend fun captureAndEraseOnce(lease: AccountErasureWorkLease, mediaId: UUID): AccountMediaSourceErasureRun {
        currentCoroutineContext().ensureActive()
        val capture = captures ?: return AccountMediaSourceErasureRun.NOT_CONFIGURED
        val provider = objects ?: return AccountMediaSourceErasureRun.NOT_CONFIGURED
        if (!provider.configured) return AccountMediaSourceErasureRun.NOT_CONFIGURED
        val original = runInterruptible(dispatcher) { capture.capture(lease, mediaId) }
            ?: return AccountMediaSourceErasureRun.HELD_OR_LEASE_LOST
        return erasePrepared(lease, original, provider)
    }

    private suspend fun erasePrepared(lease: AccountErasureWorkLease, original: AccountMediaSourceIntent,
        provider: AccountMediaSourceErasureObjects): AccountMediaSourceErasureRun {
        currentCoroutineContext().ensureActive()
        if (original.alreadyDispatched) return AccountMediaSourceErasureRun.RECONCILIATION_REQUIRED
        if (!provider.acceptsTarget(original.target)) return AccountMediaSourceErasureRun.NOT_CONFIGURED
        if (!runInterruptible(dispatcher) { store.markDispatched(lease, original) })
            return AccountMediaSourceErasureRun.HELD_OR_LEASE_LOST
        currentCoroutineContext().ensureActive()
        val result = runInterruptible(dispatcher) { provider.eraseExact(original.target) }
        if (!runInterruptible(dispatcher) { store.recordErase(lease, original, result) })
            return AccountMediaSourceErasureRun.HELD_OR_LEASE_LOST
        return AccountMediaSourceErasureRun.DELETE_OBSERVATION_RECORDED
    }

    /** Bounded explicit inspection only. A failed/unknown call leaves its durable
     * observation original; next invocation creates a distinct inspection, not DELETE. */
    suspend fun reconcile(lease: AccountErasureWorkLease, mediaId: UUID): AccountMediaSourceErasureRun {
        currentCoroutineContext().ensureActive()
        val provider = objects ?: return AccountMediaSourceErasureRun.NOT_CONFIGURED
        if (!provider.configured) return AccountMediaSourceErasureRun.NOT_CONFIGURED
        val original = runInterruptible(dispatcher) { store.prepare(lease, mediaId) }
            ?: return AccountMediaSourceErasureRun.HELD_OR_LEASE_LOST
        if (!original.alreadyDispatched) return AccountMediaSourceErasureRun.HELD_OR_LEASE_LOST
        if (!provider.acceptsTarget(original.target)) return AccountMediaSourceErasureRun.NOT_CONFIGURED
        val observation = UUID.randomUUID()
        if (!runInterruptible(dispatcher) { store.prepareInspection(lease, original, observation) })
            return AccountMediaSourceErasureRun.HELD_OR_LEASE_LOST
        currentCoroutineContext().ensureActive()
        val result = runInterruptible(dispatcher) { provider.inspectExact(original.target) }
        if (!runInterruptible(dispatcher) { store.recordInspection(lease, original, observation, result) })
            return AccountMediaSourceErasureRun.HELD_OR_LEASE_LOST
        return AccountMediaSourceErasureRun.PRESENCE_OBSERVATION_RECORDED
    }
    override fun toString() = "AccountMediaSourceErasureWorker(<redacted>)"
}
