package com.feedme.session

import com.feedme.core.ports.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Setup-only ledger protocol shared by ordinary composition and existing-only recovery. Callers
 * serialize it and retain their store; it owns no lifetime, policies, IDs, leases or OS ports.
 * Native proof is necessary but never replaces strict predecessor/provenance validation.
 */
internal class SessionWorkSetupProtocol(
    private val control: SessionControlStore,
    private val unavailable: () -> FailureReason?,
    private val retired: (StorageScope, String) -> Boolean = { _, _ -> false },
) {
    suspend fun inspect(plan: SessionWorkOriginPlan): PortResult<SessionWorkOriginObservation> = operation {
        inspect(read(), plan)
    }

    suspend fun inspect(record: SessionControlRecord, plan: SessionWorkOriginPlan): PortResult<SessionWorkOriginObservation> =
        operation { inspect(entry(record), plan) }

    suspend fun abort(plan: SessionWorkOriginPlan): PortResult<Unit> = operation {
        val before = read()
        val status = inspect(before, plan).status
        val payload = if (status == SessionWorkOriginPlanStatus.ABORTED) before.record.payload
            else SessionWorkCodec.encode(SessionWorkState.SetupAborted(plan))
        requireResult(control.acknowledge(before.record, payload) { requireAvailable() })
        check()
    }

    private suspend fun inspect(entry: Entry, plan: SessionWorkOriginPlan): SessionWorkOriginObservation {
        check()
        val intended = try { SessionWorkOriginPlanCodec.decode(plan.copyForStorage()) }
            catch (_: SessionWorkOriginPlanFormatException) { fail(FailureReason.INVALID_DATA) }
        val authentication = control as? WorkOriginPlanVerification ?: fail(FailureReason.NOT_CONFIGURED)
        val unsigned = SessionWorkOriginPlanCodec.encodeUnsigned(intended)
        requireResult(native { authentication.verifyOriginPlan(intended.expectedRevision, unsigned, intended.proof) })
        if (retired(intended.scope, intended.origin)) fail(FailureReason.STALE_SESSION)
        val status = when (val state = entry.state) {
            SessionWorkState.Idle -> {
                if (entry.record.revision != intended.expectedRevision) fail(FailureReason.STALE_SESSION)
                requireResult(native { authentication.verifyOriginPredecessor(entry.record, unsigned, intended.proof) })
                SessionWorkOriginPlanStatus.PREPARED
            }
            is SessionWorkState.SetupSelected -> {
                if (entry.record.revision <= intended.expectedRevision ||
                    !same(state.plan.copyForStorage(), plan.copyForStorage()) ||
                    !same(entry.record.payload, SessionWorkCodec.encode(SessionWorkState.SetupSelected(plan))))
                    fail(FailureReason.STALE_SESSION)
                SessionWorkOriginPlanStatus.SELECTED
            }
            is SessionWorkState.SetupAborted -> {
                if (same(state.plan.copyForStorage(), plan.copyForStorage())) {
                    if (entry.record.revision <= intended.expectedRevision ||
                        !same(entry.record.payload, SessionWorkCodec.encode(SessionWorkState.SetupAborted(plan))))
                        fail(FailureReason.STALE_SESSION)
                    SessionWorkOriginPlanStatus.ABORTED
                } else {
                    // An aborted predecessor is not generic Idle. Authenticate its original
                    // exact plan as well as the successor's MAC of the current raw record.
                    if (entry.record.revision != intended.expectedRevision) fail(FailureReason.STALE_SESSION)
                    if (inspect(entry, state.plan).status != SessionWorkOriginPlanStatus.ABORTED)
                        fail(FailureReason.STALE_SESSION)
                    requireResult(native { authentication.verifyOriginPredecessor(entry.record, unsigned, intended.proof) })
                    SessionWorkOriginPlanStatus.PREPARED
                }
            }
            is SessionWorkState.Origin -> {
                val retained = state.setupPlan ?: fail(FailureReason.STALE_SESSION)
                if (state.retiring || state.entries.isNotEmpty() || state.scope != intended.scope ||
                    state.origin != intended.origin || entry.record.revision < intended.expectedRevision + 2 ||
                    !same(retained.copyForStorage(), plan.copyForStorage()) ||
                    !same(entry.record.payload, SessionWorkCodec.encode(state))) fail(FailureReason.STALE_SESSION)
                SessionWorkOriginPlanStatus.SEALED
            }
        }
        val after = read()
        if (after.record.revision != entry.record.revision || !same(after.record.payload, entry.record.payload))
            fail(FailureReason.CONFLICT)
        return SessionWorkOriginObservation(status, entry.record.revision)
    }

    private class Entry(val record: SessionControlRecord, val state: SessionWorkState)
    private fun entry(record: SessionControlRecord): Entry {
        if (record.revision <= 0) fail(FailureReason.STORAGE_FAILURE)
        val state = try { SessionWorkCodec.decode(record.payload) }
            catch (_: SessionWorkFormatException) { fail(FailureReason.STORAGE_FAILURE) }
        return Entry(record, state)
    }
    private suspend fun read(): Entry = entry(requireResult(native { control.read() }) ?: fail(FailureReason.STORAGE_FAILURE))
    private fun requireAvailable() { unavailable()?.let(::fail) }
    private suspend fun check() { currentCoroutineContext().ensureActive(); requireAvailable() }
    private suspend fun <T> native(action: suspend () -> PortResult<T>): PortResult<T> {
        check()
        val result = try { action() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
        check()
        return result
    }
    private suspend fun <T> operation(action: suspend () -> T): PortResult<T> = try {
        check(); val result = action(); check(); PortResult.Value(result)
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (failure: ProtocolFailure) { PortResult.Failure(failure.reason) }
      catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
    private fun <T> requireResult(result: PortResult<T>): T = when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> fail(result.reason)
    }
    private fun same(a: PrivateBytes, b: PrivateBytes): Boolean {
        val first = a.copyForCodec(); val second = b.copyForCodec()
        return try { first.contentEquals(second) } finally { first.fill(0); second.fill(0) }
    }
    private fun fail(reason: FailureReason): Nothing = throw ProtocolFailure(reason)
    private class ProtocolFailure(val reason: FailureReason) : Exception("Work setup unavailable")
}
