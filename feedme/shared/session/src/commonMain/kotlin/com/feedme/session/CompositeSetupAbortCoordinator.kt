package com.feedme.session

import com.feedme.core.ports.*
import com.feedme.storage.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Already-open, exclusively owned native resources. No factory, ordinary handle or secret read. */
internal interface CompositeSetupAbortResources : InterruptedSetupResources {
    val credentialAbortAvailable: Boolean
    suspend fun abortCredentials(scope: StorageScope, plan: CredentialCreatePlan): PortResult<Unit>
    suspend fun abortData(scope: StorageScope, plan: StateActivationPlan, expectedBinding: PrivateBytes?): PortResult<Unit>
    suspend fun abortWork(plan: SessionWorkOriginPlan): PortResult<Unit>
}

internal class NativeCompositeSetupAbortResources(
    private val credentials: IncarnationCredentialStore,
    private val data: EncryptedStateDatabase,
    private val work: SessionWorkRegistry,
) : CompositeSetupAbortResources {
    private val observations = NativeInterruptedSetupResources(credentials, data, work)
    override val credentialAbortAvailable: Boolean get() = credentials is CredentialCreatePlanAbort
    override suspend fun credentials(scope: StorageScope, plan: CredentialCreatePlan) = observations.credentials(scope, plan)
    override suspend fun data(scope: StorageScope, plan: StateActivationPlan) = observations.data(scope, plan)
    override suspend fun binding(scope: StorageScope, plan: StateActivationPlan) = observations.binding(scope, plan)
    override suspend fun work(plan: SessionWorkOriginPlan) = observations.work(plan)
    override suspend fun abortCredentials(scope: StorageScope, plan: CredentialCreatePlan) =
        (credentials as? CredentialCreatePlanAbort)?.abortPlannedCreate(scope, plan)
            ?: PortResult.Failure(FailureReason.NOT_CONFIGURED)
    override suspend fun abortData(scope: StorageScope, plan: StateActivationPlan, expectedBinding: PrivateBytes?) =
        data.abortPlannedActivation(scope, plan, expectedBinding)
    override suspend fun abortWork(plan: SessionWorkOriginPlan) = work.abortOrigin(plan)
}

/**
 * Trusted runtime-only protocol on its serialized owner mutex/dispatcher. The runtime supplies
 * an inactive-boundary/lifecycle check and must invalidate it on close, replacement or new login.
 * Native resources are borrowed: no factory/close, provider, credential read, IDs or lease occurs.
 * A proposal is exact observed evidence, not consent; only explicit confirm persists consent.
 * Pending retries re-acknowledge control and ALL components, even when already ABORTED. Failed
 * writes never acquire credit from readback. The private retained finalization is only for a
 * lost final Complete receipt; it cannot reconstruct authority from a persisted operation ID.
 */
internal class CompositeSetupAbortCoordinator(
    private val control: SessionControlStore,
    private val resources: CompositeSetupAbortResources,
    private val configuration: String,
    private val owner: Any,
    private val generation: Any,
    private val processPending: suspend () -> Boolean,
    private val checkCurrent: () -> Unit,
    /** Owned startup stops here; its retained owner must close resources before final control. */
    private val allAborted: ((InterruptedSetupEvidence, SessionControlRecord) -> Unit)? = null,
) {
    init { require(configuration.matches(Regex("[0-9a-f]{64}"))) }
    private var finalization: Finalization? = null

    suspend fun prepare(): PortResult<PreparedSessionSetupAbort> = operation {
        available()
        val evidence = capture()
        if (evidence.abortRequested) fail(FailureReason.CONFLICT)
        validateOrder(evidence)
        PreparedSessionSetupAbort(owner, generation, configuration, evidence)
    }

    /** Only explicit confirmation of this exact generation-bound proposal may set the flag. */
    suspend fun confirm(prepared: PreparedSessionSetupAbort): PortResult<Unit> = operation(completes = true) {
        if (prepared.owner !== owner || prepared.generation !== generation || prepared.configuration != configuration)
            fail(FailureReason.STALE_SESSION)
        available()
        val current = capture()
        if (current.abortRequested || prepared.evidence.abortRequested || !sameEvidence(prepared.evidence, current))
            fail(FailureReason.CONFLICT)
        validateOrder(current)
        barrier(current.control)
        val pending = RetirementState.PendingSetup(SessionSetupPlan.create(current.plan), true)
        val acknowledged = acknowledge(current.control, RetirementCodec.encode(pending))
        finish(current, acknowledged)
    }

    /** Already-confirmed pending intent only; never set confirmation from an observed crash. */
    suspend fun retry(): PortResult<Unit> = operation(completes = true) {
        available()
        val entry = readControl()
        when (val state = decodeControl(entry)) {
            is RetirementState.PendingSetup -> {
                if (!state.abortRequested) fail(FailureReason.CONFLICT)
                val current = capture()
                if (!sameRecord(entry, current.control) || !current.abortRequested) fail(FailureReason.CONFLICT)
                validateOrder(current)
                barrier(entry)
                // Preserve exact current bytes, including valid legacy JSON whitespace.
                finish(current, acknowledge(entry, entry.payload))
            }
            is RetirementState.Complete -> {
                val retained = finalization ?: fail(FailureReason.CONFLICT)
                if (!sameRecord(entry, retained.expectedComplete) || state.operationId != retained.evidence.plan.operationId)
                    fail(FailureReason.CONFLICT)
                val current = captureCompleted(retained.evidence.plan, entry)
                if (!sameResources(current, retained.evidence)) fail(FailureReason.CONFLICT)
                requireAborted(current)
                complete(current, entry)
            }
            else -> fail(FailureReason.CONFLICT)
        }
    }

    private suspend fun finish(original: InterruptedSetupEvidence, requested: SessionControlRecord) {
        // Confirmation CAS can suspend. It acknowledges intent, not unchanged native resources.
        var current = capture()
        if (!sameRecord(current.control, requested) || !current.abortRequested || !sameResources(original, current))
            fail(FailureReason.CONFLICT)
        validateOrder(current)
        val plan = current.plan

        barrier(requested)
        call { resources.abortWork(plan.workOriginPlan) }
        var after = capture()
        requireControl(after, requested)
        if (!sameCredential(current, after) || !sameData(current, after) ||
            current.work.revision == Long.MAX_VALUE || after.work.revision != current.work.revision + 1 ||
            after.work.status != SessionWorkOriginPlanStatus.ABORTED) fail(FailureReason.CONFLICT)
        validateOrder(after)
        current = after

        val expected = expectedBinding(current)
        barrier(requested)
        call { resources.abortData(plan.scope, plan.dataPlan, expected) }
        after = capture()
        requireControl(after, requested)
        if (!sameCredential(current, after) || !sameWork(current, after) ||
            after.data.status != StateActivationStatus.ABORTED || after.binding != null ||
            sameBytes(current.data.fingerprint, after.data.fingerprint)) fail(FailureReason.CONFLICT)
        validateOrder(after)
        current = after

        barrier(requested)
        call { resources.abortCredentials(plan.scope, plan.credentialPlan) }
        after = capture()
        requireControl(after, requested)
        if (!sameData(current, after) || !sameWork(current, after) ||
            after.credential.status != CredentialCreateRecoveryStatus.ABORTED) fail(FailureReason.CONFLICT)
        requireAborted(after)

        // Freeze every observed post-ack component, not just the three terminal enum values.
        val final = capture()
        requireControl(final, requested)
        if (!sameResources(after, final)) fail(FailureReason.CONFLICT)
        if (allAborted == null) complete(final, requested)
        else allAborted.invoke(final, requested)
    }

    private suspend fun complete(evidence: InterruptedSetupEvidence, previous: SessionControlRecord) {
        requireAborted(evidence)
        barrier(previous)
        if (previous.revision == Long.MAX_VALUE) fail(FailureReason.STORAGE_FAILURE)
        val payload = RetirementCodec.encode(RetirementState.Complete(evidence.plan.operationId))
        // Retain BEFORE attempting the final write, but only after every component acknowledged.
        finalization = Finalization(evidence, SessionControlRecord(previous.revision + 1, payload))
        val acknowledged = acknowledge(previous, payload)
        val final = captureCompleted(evidence.plan, acknowledged)
        if (!sameResources(evidence, final)) fail(FailureReason.CONFLICT)
        requireAborted(final)
        barrier(acknowledged)
    }

    private fun expectedBinding(evidence: InterruptedSetupEvidence): PrivateBytes? {
        val observed = evidence.binding ?: return null
        val target = observed.target ?: fail(FailureReason.CONFLICT)
        val binding = observed.record ?: fail(FailureReason.CONFLICT)
        val plan = evidence.plan
        val credential = CredentialCreatePlanCodec.decode(plan.credentialPlan.copyForStorage())
        val work = SessionWorkOriginPlanCodec.decode(plan.workOriginPlan.copyForStorage())
        val expected = SessionActivationCodec.encode(SessionActivationRecord(plan.scope, credential.incarnation,
            work.origin, target, plan.configurationBinding, plan.operationId))
        if (binding.schemaVersion != 2 || !sameBytes(binding.payload, expected)) fail(FailureReason.CONFLICT)
        return expected
    }

    private fun validateOrder(evidence: InterruptedSetupEvidence) {
        val credential = evidence.credential.status
        val data = evidence.data.status
        val work = evidence.work.status
        val credentialAborting = credential in setOf(CredentialCreateRecoveryStatus.ABORTING, CredentialCreateRecoveryStatus.ABORTED)
        val dataAborting = data in setOf(StateActivationStatus.ABORTING, StateActivationStatus.ABORTED)
        if (credentialAborting) {
            if (data != StateActivationStatus.ABORTED || work != SessionWorkOriginPlanStatus.ABORTED)
                fail(FailureReason.CONFLICT)
            return
        }
        if (dataAborting) {
            if (work != SessionWorkOriginPlanStatus.ABORTED) fail(FailureReason.CONFLICT)
            return
        }
        if (credential != CredentialCreateRecoveryStatus.SELECTED && data != StateActivationStatus.PREPARED)
            fail(FailureReason.CONFLICT)
        if (work == SessionWorkOriginPlanStatus.ABORTED) return
        val allowed = when (data) {
            StateActivationStatus.PREPARED, StateActivationStatus.PARTIAL -> setOf(SessionWorkOriginPlanStatus.PREPARED)
            StateActivationStatus.SELECTED_EMPTY -> setOf(SessionWorkOriginPlanStatus.PREPARED, SessionWorkOriginPlanStatus.SELECTED)
            StateActivationStatus.SELECTED_NONEMPTY -> setOf(SessionWorkOriginPlanStatus.SELECTED, SessionWorkOriginPlanStatus.SEALED)
            else -> emptySet()
        }
        if (work !in allowed) fail(FailureReason.CONFLICT)
    }

    private fun requireAborted(evidence: InterruptedSetupEvidence) {
        if (evidence.credential.status != CredentialCreateRecoveryStatus.ABORTED ||
            evidence.data.status != StateActivationStatus.ABORTED || evidence.binding != null ||
            evidence.work.status != SessionWorkOriginPlanStatus.ABORTED) fail(FailureReason.CONFLICT)
    }

    private fun inspector() = InterruptedSetupInspector(control, resources, configuration, ::readPending, checkCurrent)
    private suspend fun capture() = value(inspector().capture())
    private suspend fun captureCompleted(plan: SessionSetupPlanRecord, expected: SessionControlRecord) =
        value(inspector().captureCompleted(plan, expected))
    private fun requireControl(evidence: InterruptedSetupEvidence, expected: SessionControlRecord) {
        if (!evidence.abortRequested || !sameRecord(evidence.control, expected)) fail(FailureReason.CONFLICT)
    }

    private suspend fun readControl(): SessionControlRecord {
        check()
        pending()
        val entry = call { control.read() } ?: fail(FailureReason.STORAGE_FAILURE)
        if (entry.revision <= 0) fail(FailureReason.STORAGE_FAILURE)
        decodeControl(entry)
        pending()
        return entry
    }
    private suspend fun barrier(expected: SessionControlRecord) {
        if (!sameRecord(readControl(), expected)) fail(FailureReason.CONFLICT)
        check()
    }
    private fun decodeControl(entry: SessionControlRecord): RetirementState = try { RetirementCodec.decode(entry.payload) }
        catch (_: Exception) { fail(FailureReason.STORAGE_FAILURE) }
    private suspend fun acknowledge(expected: SessionControlRecord, payload: PrivateBytes): SessionControlRecord {
        val acknowledged = value(control.acknowledge(expected, payload, checkCurrent))
        check()
        barrier(acknowledged)
        return acknowledged
    }
    private suspend fun readPending(): Boolean {
        check()
        val result = try { processPending() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail(FailureReason.STORAGE_FAILURE) }
        check()
        return result
    }
    private suspend fun pending() { if (readPending()) fail(FailureReason.CONFLICT) }
    private fun available() { if (!resources.credentialAbortAvailable) fail(FailureReason.NOT_CONFIGURED) }
    private suspend fun check() { currentCoroutineContext().ensureActive(); checkCurrent() }
    private suspend fun <T> call(action: suspend () -> PortResult<T>): T {
        check()
        val result = try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
        check()
        return value(result)
    }
    private suspend fun <T> operation(completes: Boolean = false, action: suspend () -> T): PortResult<T> = try {
        check()
        val result = action()
        check()
        // Retain only an unresolved final control receipt. A successful operation consumes its
        // process-local retry proof; Complete alone must never make a later call idempotent.
        if (completes) finalization = null
        PortResult.Value(result)
    } catch (failure: AbortFailure) { PortResult.Failure(failure.reason) }

    private fun sameEvidence(a: InterruptedSetupEvidence, b: InterruptedSetupEvidence) =
        a.abortRequested == b.abortRequested && sameRecord(a.control, b.control) && sameResources(a, b)
    private fun sameResources(a: InterruptedSetupEvidence, b: InterruptedSetupEvidence) =
        sameBytes(SessionSetupPlan.create(a.plan).copyForStorage(), SessionSetupPlan.create(b.plan).copyForStorage()) &&
            sameCredential(a, b) && sameData(a, b) && sameWork(a, b)
    private fun sameCredential(a: InterruptedSetupEvidence, b: InterruptedSetupEvidence) =
        a.credential.status == b.credential.status && sameBytes(a.credential.fingerprint, b.credential.fingerprint)
    private fun sameData(a: InterruptedSetupEvidence, b: InterruptedSetupEvidence) =
        a.data.status == b.data.status && sameBytes(a.data.fingerprint, b.data.fingerprint) && sameBinding(a.binding, b.binding)
    private fun sameWork(a: InterruptedSetupEvidence, b: InterruptedSetupEvidence) =
        a.work.status == b.work.status && a.work.revision == b.work.revision
    private fun sameBinding(a: StateRecordInspection?, b: StateRecordInspection?): Boolean {
        if (a == null || b == null) return a == null && b == null
        val left = a.record ?: return false; val right = b.record ?: return false
        val leftTarget = a.target ?: return false; val rightTarget = b.target ?: return false
        val l = leftTarget.copyForStorage(); val r = rightTarget.copyForStorage()
        return try { l.contentEquals(r) && left.revision == right.revision && left.schemaVersion == right.schemaVersion &&
            sameBytes(left.payload, right.payload) } finally { l.fill(0); r.fill(0) }
    }
    private fun sameRecord(a: SessionControlRecord, b: SessionControlRecord) =
        a.revision == b.revision && sameBytes(a.payload, b.payload)
    private fun sameBytes(a: PrivateBytes, b: PrivateBytes): Boolean {
        val left = a.copyForCodec(); val right = b.copyForCodec()
        return try { left.contentEquals(right) } finally { left.fill(0); right.fill(0) }
    }
    private class Finalization(val evidence: InterruptedSetupEvidence, val expectedComplete: SessionControlRecord)
    private class AbortFailure(val reason: FailureReason) : Exception("Composite setup abort unavailable")
    private fun fail(reason: FailureReason): Nothing = throw AbortFailure(reason)
    private fun <T> value(result: PortResult<T>): T = when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> fail(result.reason)
    }
    override fun toString() = "CompositeSetupAbortCoordinator(<redacted>)"
}
