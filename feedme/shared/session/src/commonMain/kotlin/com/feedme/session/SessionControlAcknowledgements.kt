package com.feedme.session

import com.feedme.core.ports.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * A readback proves identity, not durability. Only this invocation's successfully returned,
 * changed CAS followed by its exact readback may authorize the next effect. In particular an
 * OUTCOME_UNKNOWN result is never promoted to success, even if the payload is now visible.
 * Recovery may call again with the exact observed payload and revision to obtain a NEW ack.
 */
internal suspend fun SessionControlStore.acknowledge(
    expected: SessionControlRecord,
    payload: PrivateBytes,
    checkCurrent: () -> Unit = {},
): PortResult<SessionControlRecord> {
    suspend fun check() { currentCoroutineContext().ensureActive(); checkCurrent() }
    check()
    if (expected.revision == Long.MAX_VALUE) return PortResult.Failure(FailureReason.STORAGE_FAILURE)
    val outcome = controlCall { compareAndSet(expected.revision, payload) }
    check()
    if (outcome is PortResult.Failure) return outcome
    val receipt = (outcome as PortResult.Value).value
    if (receipt.revision != expected.revision + 1 || !sameControlPayload(receipt.payload, payload))
        return PortResult.Failure(FailureReason.STORAGE_FAILURE)
    val readback = controlCall { read() }
    check()
    if (readback is PortResult.Failure) return readback
    val current = (readback as PortResult.Value).value
        ?: return PortResult.Failure(FailureReason.STORAGE_FAILURE)
    if (current.revision > receipt.revision) return PortResult.Failure(FailureReason.CONFLICT)
    if (current.revision != receipt.revision || !sameControlPayload(current.payload, payload))
        return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
    return PortResult.Value(current)
}

private suspend fun <T> controlCall(action: suspend () -> PortResult<T>): PortResult<T> = try { action() }
catch (cancelled: CancellationException) { throw cancelled }
catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }

private fun sameControlPayload(left: PrivateBytes, right: PrivateBytes): Boolean {
    val a = left.copyForCodec(); val b = right.copyForCodec()
    return try { a.contentEquals(b) } finally { a.fill(0); b.fill(0) }
}
