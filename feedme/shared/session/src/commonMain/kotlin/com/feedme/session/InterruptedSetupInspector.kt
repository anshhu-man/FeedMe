package com.feedme.session

import com.feedme.core.ports.*
import com.feedme.storage.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Already-open native owners only. Protocol fixtures never become application defaults. */
internal interface InterruptedSetupResources {
    suspend fun credentials(scope: StorageScope, plan: CredentialCreatePlan): PortResult<CredentialCreatePlanObservation>
    suspend fun data(scope: StorageScope, plan: StateActivationPlan): PortResult<StateActivationPlanObservation>
    suspend fun binding(scope: StorageScope, plan: StateActivationPlan): PortResult<StateRecordInspection>
    suspend fun work(plan: SessionWorkOriginPlan): PortResult<SessionWorkOriginObservation>
}

internal class NativeInterruptedSetupResources(
    private val credentials: IncarnationCredentialStore,
    private val data: EncryptedStateDatabase,
    private val work: SessionWorkRegistry,
) : InterruptedSetupResources {
    override suspend fun credentials(scope: StorageScope, plan: CredentialCreatePlan) =
        (credentials as? CredentialCreatePlanInspection)?.inspectPlannedCreate(scope, plan)
            ?: PortResult.Failure(FailureReason.NOT_CONFIGURED)
    override suspend fun data(scope: StorageScope, plan: StateActivationPlan) = data.inspectPlannedActivationState(scope, plan)
    override suspend fun binding(scope: StorageScope, plan: StateActivationPlan) = data.inspectPlannedBinding(scope, plan)
    override suspend fun work(plan: SessionWorkOriginPlan) = work.inspectOriginState(plan)
}

/** Detached private observations, never an acknowledgement or a caller-created cleanup right. */
internal class InterruptedSetupEvidence(
    val control: SessionControlRecord,
    val plan: SessionSetupPlanRecord,
    val abortRequested: Boolean,
    val credential: CredentialCreatePlanObservation,
    val data: StateActivationPlanObservation,
    val binding: StateRecordInspection?,
    val work: SessionWorkOriginObservation,
) {
    override fun toString() = "InterruptedSetupEvidence(<redacted>)"
}

/**
 * One read-only inspection under the runtime's owner mutex/identity dispatcher. Native metadata
 * is read forwards and backwards inside the same exact independent control record and process
 * latch. This is not an atomic cross-store snapshot, durability acknowledgement or repair consent.
 * No credential blob is read; only the purpose-fixed activation binding may be decrypted.
 */
internal class InterruptedSetupInspector(
    private val control: SessionControlStore,
    private val resources: InterruptedSetupResources,
    private val configuration: String,
    private val processPending: suspend () -> Boolean,
    private val checkCurrent: () -> Unit,
) {
    private var initialControl: SessionControlRecord? = null

    suspend fun inspect(): InterruptedSetupReport {
        check()
        if (pending()) return report(InterruptedSetupFinding.RETIREMENT_PENDING)
        var result = try { classify() } catch (failure: EvidenceFailure) { unavailable(failure) }
        if (pending()) return report(InterruptedSetupFinding.RETIREMENT_PENDING)
        initialControl?.let { initial ->
            try {
                val (current, state) = readControl()
                if (state is RetirementState.InFlight) return report(InterruptedSetupFinding.RETIREMENT_PENDING)
                if (current.revision != initial.revision || !sameBytes(current.payload, initial.payload))
                    result = report(InterruptedSetupFinding.EVIDENCE_CHANGED, InterruptedSetupComponent.CONTROL)
            } catch (failure: EvidenceFailure) { result = unavailable(failure) }
        }
        if (pending()) return report(InterruptedSetupFinding.RETIREMENT_PENDING)
        check()
        return result
    }

    /**
     * Trusted coordinator input only. Unlike the public report, failures are not collapsed into
     * advisory findings and the exact detached evidence is retained for confirmation equality.
     * This repeats the same forward/reverse native bracket inside exact control and latch reads;
     * it performs no write, acknowledges nothing and grants no authority by itself.
     */
    internal suspend fun capture(): PortResult<InterruptedSetupEvidence> = try {
        check()
        if (pending()) mismatch(InterruptedSetupComponent.CONTROL)
        val (entry, state) = readControl()
        if (state !is RetirementState.PendingSetup) mismatch(InterruptedSetupComponent.CONTROL)
        val plan = decodePlan(state)
        if (plan.configurationBinding != configuration) mismatch(InterruptedSetupComponent.CONTROL)
        val evidence = readEvidence(entry, state.abortRequested, plan)
        if (pending()) mismatch(InterruptedSetupComponent.CONTROL)
        val (current, _) = readControl()
        if (current.revision != entry.revision || !sameBytes(current.payload, entry.payload))
            changed(InterruptedSetupComponent.CONTROL)
        if (pending()) mismatch(InterruptedSetupComponent.CONTROL)
        check()
        PortResult.Value(evidence)
    } catch (failure: EvidenceFailure) { PortResult.Failure(failure.reason) }

    /**
     * Observation for an already-retained final control receipt attempt only. The caller supplies
     * the exact locally retained plan; Complete alone cannot reconstruct it. This method still
     * grants no authority and never accepts a different operation or changes any resource.
     */
    internal suspend fun captureCompleted(plan: SessionSetupPlanRecord,
        expected: SessionControlRecord): PortResult<InterruptedSetupEvidence> = try {
        check()
        if (pending()) mismatch(InterruptedSetupComponent.CONTROL)
        val (entry, state) = readControl()
        if (state !is RetirementState.Complete || state.operationId != plan.operationId ||
            entry.revision != expected.revision || !sameBytes(entry.payload, expected.payload) ||
            plan.configurationBinding != configuration) mismatch(InterruptedSetupComponent.CONTROL)
        // True describes the supplied retained abort attempt, not inferred authority from Complete.
        val evidence = readEvidence(entry, true, plan)
        if (pending()) mismatch(InterruptedSetupComponent.CONTROL)
        val (current, _) = readControl()
        if (current.revision != entry.revision || !sameBytes(current.payload, entry.payload))
            changed(InterruptedSetupComponent.CONTROL)
        if (pending()) mismatch(InterruptedSetupComponent.CONTROL)
        check()
        PortResult.Value(evidence)
    } catch (failure: EvidenceFailure) { PortResult.Failure(failure.reason) }

    private suspend fun classify(): InterruptedSetupReport {
        val (entry, state) = readControl()
        initialControl = entry
        when (state) {
            RetirementState.Idle, is RetirementState.Complete -> return report(InterruptedSetupFinding.NONE)
            is RetirementState.InFlight -> return report(InterruptedSetupFinding.RETIREMENT_PENDING)
            is RetirementState.PendingCreate -> return report(InterruptedSetupFinding.OTHER_CONTROL_PENDING)
            is RetirementState.PendingSetup -> Unit
        }
        val plan = decodePlan(state)
        if (plan.configurationBinding != configuration) return report(InterruptedSetupFinding.CONFIGURATION_CHANGED)
        val evidence = readEvidence(entry, state.abortRequested, plan)
        return InterruptedSetupReport(
            if (state.abortRequested) InterruptedSetupFinding.ABORT_REQUESTED else InterruptedSetupFinding.UNCONFIRMED_SETUP,
            credentialStage = evidence.credential.status, dataStage = dataStage(evidence.data.status), workStage = evidence.work.status,
        )
    }

    private fun decodePlan(state: RetirementState.PendingSetup): SessionSetupPlanRecord =
        try { SessionSetupPlanCodec.decode(state.plan.copyForStorage()) }
        catch (_: Exception) { throw EvidenceFailure(InterruptedSetupComponent.CONTROL, FailureReason.INVALID_DATA) }

    private suspend fun readEvidence(entry: SessionControlRecord, abortRequested: Boolean,
        plan: SessionSetupPlanRecord): InterruptedSetupEvidence {
        val credential = observe(InterruptedSetupComponent.CREDENTIAL_METADATA) { resources.credentials(plan.scope, plan.credentialPlan) }
        val data = observe(InterruptedSetupComponent.PRIVATE_DATA) { resources.data(plan.scope, plan.dataPlan) }
        val binding = if (data.status == StateActivationStatus.SELECTED_NONEMPTY) readBinding(plan) else null
        val work = observe(InterruptedSetupComponent.WORK_METADATA) { resources.work(plan.workOriginPlan) }
        val stage = dataStage(data.status)
        if (!abortRequested) validateLiveOrder(credential.status, stage, work.status)

        // Same status alone is insufficient: exact work revisions, owner/inventory/abort-receipt
        // fingerprints and complete private binding revision/schema/payload are compared.
        val repeatedWork = observe(InterruptedSetupComponent.WORK_METADATA) { resources.work(plan.workOriginPlan) }
        if (work.status != repeatedWork.status || work.revision != repeatedWork.revision)
            changed(InterruptedSetupComponent.WORK_METADATA)
        val repeatedData = observe(InterruptedSetupComponent.PRIVATE_DATA) { resources.data(plan.scope, plan.dataPlan) }
        if (data.status != repeatedData.status || !sameBytes(data.fingerprint, repeatedData.fingerprint))
            changed(InterruptedSetupComponent.PRIVATE_DATA)
        if (binding != null && !sameBinding(binding, readBinding(plan))) changed(InterruptedSetupComponent.PRIVATE_BINDING)
        val repeatedCredential = observe(InterruptedSetupComponent.CREDENTIAL_METADATA) { resources.credentials(plan.scope, plan.credentialPlan) }
        if (credential.status != repeatedCredential.status || !sameBytes(credential.fingerprint, repeatedCredential.fingerprint))
            changed(InterruptedSetupComponent.CREDENTIAL_METADATA)
        return InterruptedSetupEvidence(entry, plan, abortRequested, credential, data, binding, work)
    }

    private suspend fun readBinding(plan: SessionSetupPlanRecord): StateRecordInspection {
        val inspection = observe(InterruptedSetupComponent.PRIVATE_BINDING) { resources.binding(plan.scope, plan.dataPlan) }
        val target = inspection.target ?: mismatch(InterruptedSetupComponent.PRIVATE_BINDING)
        val binding = inspection.record ?: mismatch(InterruptedSetupComponent.PRIVATE_BINDING)
        if (binding.revision <= 0 || binding.schemaVersion != 2) mismatch(InterruptedSetupComponent.PRIVATE_BINDING)
        val expected = try {
            val credential = CredentialCreatePlanCodec.decode(plan.credentialPlan.copyForStorage())
            val work = SessionWorkOriginPlanCodec.decode(plan.workOriginPlan.copyForStorage())
            SessionActivationCodec.encode(SessionActivationRecord(plan.scope, credential.incarnation, work.origin,
                target, plan.configurationBinding, plan.operationId))
        } catch (_: Exception) { mismatch(InterruptedSetupComponent.PRIVATE_BINDING) }
        // Compare canonical full bytes, not a permissive decode or owner/config alone.
        if (!sameBytes(binding.payload, expected)) mismatch(InterruptedSetupComponent.PRIVATE_BINDING)
        return inspection
    }

    private fun validateLiveOrder(credential: CredentialCreateRecoveryStatus, data: InterruptedSetupDataStage,
        work: SessionWorkOriginPlanStatus) {
        if (work == SessionWorkOriginPlanStatus.ABORTED) mismatch(InterruptedSetupComponent.WORK_METADATA)
        if (credential in setOf(CredentialCreateRecoveryStatus.ABORTING, CredentialCreateRecoveryStatus.ABORTED))
            mismatch(InterruptedSetupComponent.CREDENTIAL_METADATA)
        if (data in setOf(InterruptedSetupDataStage.ABORTING, InterruptedSetupDataStage.ABORTED))
            mismatch(InterruptedSetupComponent.PRIVATE_DATA)
        if (credential != CredentialCreateRecoveryStatus.SELECTED && data != InterruptedSetupDataStage.PREPARED)
            mismatch(InterruptedSetupComponent.PRIVATE_DATA)
        val allowed = when (data) {
            InterruptedSetupDataStage.PREPARED, InterruptedSetupDataStage.PARTIAL -> setOf(SessionWorkOriginPlanStatus.PREPARED)
            InterruptedSetupDataStage.SELECTED_EMPTY -> setOf(SessionWorkOriginPlanStatus.PREPARED, SessionWorkOriginPlanStatus.SELECTED)
            InterruptedSetupDataStage.BOUND -> setOf(SessionWorkOriginPlanStatus.SELECTED, SessionWorkOriginPlanStatus.SEALED)
            else -> emptySet()
        }
        if (work !in allowed) mismatch(InterruptedSetupComponent.WORK_METADATA)
    }

    private suspend fun readControl(): Pair<SessionControlRecord, RetirementState> {
        val entry = observe(InterruptedSetupComponent.CONTROL) { control.read() }
            ?: throw EvidenceFailure(InterruptedSetupComponent.CONTROL, FailureReason.STORAGE_FAILURE)
        if (entry.revision <= 0) throw EvidenceFailure(InterruptedSetupComponent.CONTROL, FailureReason.STORAGE_FAILURE)
        val state = try { RetirementCodec.decode(entry.payload) }
            catch (_: Exception) { throw EvidenceFailure(InterruptedSetupComponent.CONTROL, FailureReason.INVALID_DATA) }
        return entry to state
    }

    private suspend fun <T> observe(component: InterruptedSetupComponent, action: suspend () -> PortResult<T>): T {
        check()
        val result = try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
        check()
        return when (result) {
            is PortResult.Value -> result.value
            is PortResult.Failure -> throw EvidenceFailure(component, result.reason)
        }
    }

    private suspend fun pending(): Boolean { check(); val value = processPending(); check(); return value }
    private suspend fun check() { currentCoroutineContext().ensureActive(); checkCurrent() }
    private fun dataStage(status: StateActivationStatus) = when (status) {
        StateActivationStatus.PREPARED -> InterruptedSetupDataStage.PREPARED
        StateActivationStatus.PARTIAL -> InterruptedSetupDataStage.PARTIAL
        StateActivationStatus.SELECTED_EMPTY -> InterruptedSetupDataStage.SELECTED_EMPTY
        StateActivationStatus.SELECTED_NONEMPTY -> InterruptedSetupDataStage.BOUND
        StateActivationStatus.ABORTING -> InterruptedSetupDataStage.ABORTING
        StateActivationStatus.ABORTED -> InterruptedSetupDataStage.ABORTED
    }
    private class EvidenceFailure(val component: InterruptedSetupComponent, val reason: FailureReason,
        val changed: Boolean = false) : Exception("Interrupted setup evidence unavailable")
    private fun mismatch(component: InterruptedSetupComponent): Nothing = throw EvidenceFailure(component, FailureReason.CONFLICT)
    private fun changed(component: InterruptedSetupComponent): Nothing = throw EvidenceFailure(component, FailureReason.CONFLICT, true)
    private fun unavailable(failure: EvidenceFailure) = InterruptedSetupReport(
        when {
            failure.changed -> InterruptedSetupFinding.EVIDENCE_CHANGED
            failure.component != InterruptedSetupComponent.CONTROL && failure.reason in
                setOf(FailureReason.INVALID_DATA, FailureReason.CONFLICT, FailureReason.STALE_SESSION) -> InterruptedSetupFinding.RESOURCE_MISMATCH
            else -> InterruptedSetupFinding.EVIDENCE_UNAVAILABLE
        }, failure.component, failure.reason,
    )
    private fun report(finding: InterruptedSetupFinding, component: InterruptedSetupComponent? = null) = InterruptedSetupReport(finding, component)
    private fun sameBinding(a: StateRecordInspection, b: StateRecordInspection): Boolean {
        val left = a.record ?: return false; val right = b.record ?: return false
        val leftTarget = a.target ?: return false; val rightTarget = b.target ?: return false
        val l = leftTarget.copyForStorage(); val r = rightTarget.copyForStorage()
        return try { l.contentEquals(r) && left.revision == right.revision && left.schemaVersion == right.schemaVersion && sameBytes(left.payload, right.payload) }
        finally { l.fill(0); r.fill(0) }
    }
    private fun sameBytes(a: PrivateBytes, b: PrivateBytes): Boolean {
        val left = a.copyForCodec(); val right = b.copyForCodec()
        return try { left.contentEquals(right) } finally { left.fill(0); right.fill(0) }
    }
    override fun toString() = "InterruptedSetupInspector(<redacted>)"
}
