package com.feedme.development.progress

import com.feedme.core.ports.*
import com.feedme.session.PrivateSessionAccess
import com.feedme.session.PrivateSessionAccessMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** No network. Separate synthetic SERVICE ledgers borrow the same actual scoped native store. */
internal class ProgressCanonicalService(
    private val access: PrivateSessionAccess,
    private val boundary: SessionBoundary,
    private val dispatcher: CoroutineDispatcher,
    clock: EpochClock,
) : AccountTransport {
    private var closed = false
    private val core = ProgressServiceLedger(access.store, access.scope, access.originBinding, clock, ::requireCurrent)
    private val posts = ProgressPostServiceLedger(access.store, access.scope, access.originBinding, clock, ::requireCurrent)

    /** Dispatcher-owned availability only; the actual integration must still admit each operation. */
    val socialAvailable: Boolean get() = !closed && posts.available

    /** Exact assembly binding only, not currentness, mapped identity or permission. */
    internal fun matchesSession(actual: PrivateSessionAccess, actualBoundary: SessionBoundary): Boolean =
        actual === access && actualBoundary === boundary

    /** Read-only NEW eligibility against all permanent roots, never list-absence inference.
     * The actual publication execution still rechecks atomically. Not for attempted replay. */
    suspend fun requireNewPublication(lease: SessionLease, exactPostWrite: PrivateBytes): PortResult<Unit> = withContext(dispatcher) {
        if (lease !== access.lease || !boundary.isCurrent(lease) || closed) PortResult.Failure(FailureReason.STALE_SESSION)
        else posts.requireNewPublication(exactPostWrite)
    }

    private suspend fun requireCurrent() {
        if (closed || !boundary.isCurrent(access.lease) || access.scope != ProgressIdentity.scope)
            previewFail(FailureReason.STALE_SESSION)
        if (access.mode != PrivateSessionAccessMode.ONLINE) previewFail(FailureReason.OFFLINE)
        val credentials = previewValue(access.credentials.read(access.scope))
        if (closed || !boundary.isCurrent(access.lease)) previewFail(FailureReason.STALE_SESSION)
        if (!ProgressIdentity.matches(credentials)) previewFail(FailureReason.UNAUTHENTICATED)
    }

    /** Existing fresh Start alone passes true. Resume never creates the separate social record. */
    suspend fun open(allowInitialize: Boolean): PortResult<Unit> = withContext(dispatcher) {
        when (val opened = core.open(allowInitialize)) {
            is PortResult.Failure -> opened
            is PortResult.Value -> posts.open(allowInitialize)
        }
    }
    /** Explicit synthetic service fault injection; never writes the client's recall records. */
    suspend fun withdrawSyntheticRecipe(lease: SessionLease, recipeVersionId: String): PortResult<Unit> = withContext(dispatcher) {
        if (lease !== access.lease || !boundary.isCurrent(lease) || closed) PortResult.Failure(FailureReason.STALE_SESSION)
        else core.withdrawSyntheticRecipe(recipeVersionId)
    }
    override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> = withContext(dispatcher) {
        if (lease !== access.lease || !boundary.isCurrent(lease) || closed) PortResult.Failure(FailureReason.STALE_SESSION)
        else if (call.operationId in ProgressPostPreviewContract.operations) posts.execute(call)
        else core.execute(call)
    }
    /** Close borrowers only. Never retire credentials, erase a queue or close native stores. */
    suspend fun close(): PortResult<Unit> = withContext(dispatcher) {
        closed = true
        val postResult = posts.close()
        val coreResult = core.close()
        if (postResult is PortResult.Failure) postResult else coreResult
    }
}
