package com.feedme.session

import com.feedme.core.ports.*
import com.feedme.storage.EncryptedStateDatabase
import com.feedme.storage.StateRecordInspection
import kotlinx.coroutines.CancellationException

/** Already-open resources only; every path observes, never repairs or creates authority. */
internal class SessionRecoveryInspector(
    private val control: SessionControlStore,
    private val credentials: IncarnationCredentialStore,
    private val work: SessionWorkRegistry,
    private val data: EncryptedStateDatabase,
    private val bindingKey: RecordKey,
    private val configuration: String,
    private val processPending: suspend () -> Boolean,
    private val checkCurrent: () -> Unit,
) {
    private var firstControl: SessionControlRecord? = null
    private var firstSlot: CredentialSlotState? = null
    private var firstWork: SessionWorkSnapshot? = null
    private var firstData: StateRecordInspection? = null
    private var dataScope: StorageScope? = null

    suspend fun inspect(): SessionRecoveryReport {
        if (pending()) return report(SessionRecoveryFinding.RETIREMENT_PENDING)
        var result = try { classify() } catch (failure: EvidenceFailure) { unavailable(failure) }
        // No activation can race this inspection under the runtime's owner mutex. Independent
        // retirement intent nevertheless wins over every diagnostic, including unreadable disk.
        if (pending()) return report(SessionRecoveryFinding.RETIREMENT_PENDING)
        try {
            firstData?.let { original ->
                val current = observe(SessionRecoveryComponent.PRIVATE_BINDING) { data.inspectRecord(dataScope!!, bindingKey) }
                if (!sameData(original, current)) result = report(SessionRecoveryFinding.EVIDENCE_CHANGED)
            }
            firstWork?.let { original ->
                val current = observe(SessionRecoveryComponent.WORK_METADATA) { work.snapshot() }
                if (!sameWork(original, current)) result = report(SessionRecoveryFinding.EVIDENCE_CHANGED)
            }
            firstSlot?.let { original ->
                val current = observe(SessionRecoveryComponent.CREDENTIAL_METADATA) { credentials.state() }
                if (original.revision != current.revision || original.owner != current.owner || original.incarnation != current.incarnation)
                    result = report(SessionRecoveryFinding.EVIDENCE_CHANGED)
            }
        } catch (failure: EvidenceFailure) { result = unavailable(failure) }
        // Enclose lower-store observations in the same exact independent control revision/bytes.
        // There is still no cross-store transaction: every real action must freshly revalidate.
        firstControl?.let { original ->
            try {
                val (current, state) = readControl()
                if (state.blocksAccess()) return blocked(state)
                if (original.revision != current.revision || !sameBytes(original.payload, current.payload))
                    result = report(SessionRecoveryFinding.EVIDENCE_CHANGED)
            } catch (failure: EvidenceFailure) { result = unavailable(failure) }
        }
        if (pending()) return report(SessionRecoveryFinding.RETIREMENT_PENDING)
        checkCurrent()
        return result
    }

    private suspend fun classify(): SessionRecoveryReport {
        val (controlRecord, state) = readControl()
        firstControl = controlRecord
        if (state.blocksAccess()) return blocked(state)
        val slot = observe(SessionRecoveryComponent.CREDENTIAL_METADATA) { credentials.state() }.also { firstSlot = it }
        val registry = observe(SessionRecoveryComponent.WORK_METADATA) { work.snapshot() }.also { firstWork = it }
        if (slot.owner == null && registry.originBinding == null) return report(SessionRecoveryFinding.METADATA_EMPTY)
        if (slot.owner == null || registry.originBinding == null || registry.retiring) return report(SessionRecoveryFinding.PARTIAL_STATE)
        if (slot.owner != registry.scope) return report(SessionRecoveryFinding.SCOPE_MISMATCH)
        val scope = slot.owner
        dataScope = scope
        val inspection = observe(SessionRecoveryComponent.PRIVATE_BINDING) { data.inspectRecord(scope, bindingKey) }.also { firstData = it }
        val target = inspection.target ?: return report(SessionRecoveryFinding.DATA_MISSING)
        val record = inspection.record ?: return report(SessionRecoveryFinding.BINDING_MISSING)
        val binding = try { SessionActivationCodec.decode(record.payload) }
            catch (_: SessionActivationFormatException) { return report(SessionRecoveryFinding.BINDING_INVALID) }
        if (record.schemaVersion != binding.schemaVersion) return report(SessionRecoveryFinding.BINDING_INVALID)
        if (binding.setupOperationId != null && (state as? RetirementState.Complete)?.operationId != binding.setupOperationId)
            return report(SessionRecoveryFinding.BINDING_MISMATCH)
        if (binding.configurationBinding != configuration) return report(SessionRecoveryFinding.CONFIGURATION_CHANGED)
        if (binding.scope != scope || binding.credentialIncarnation != slot.incarnation ||
            binding.originBinding != registry.originBinding || !sameTarget(binding.dataTarget, target))
            return report(SessionRecoveryFinding.BINDING_MISMATCH)
        // Never decrypt credential payloads or contact a verifier here. Even an unreadable token
        // blob can have coherent authenticated metadata; actual restore must independently fail.
        return report(SessionRecoveryFinding.VERIFICATION_REQUIRED)
    }

    private suspend fun readControl(): Pair<SessionControlRecord, RetirementState> {
        val record = observe(SessionRecoveryComponent.CONTROL) { control.read() }
            ?: throw EvidenceFailure(SessionRecoveryComponent.CONTROL, FailureReason.STORAGE_FAILURE)
        val state = try { RetirementCodec.decode(record.payload) }
            catch (_: Exception) { throw EvidenceFailure(SessionRecoveryComponent.CONTROL, FailureReason.STORAGE_FAILURE) }
        return record to state
    }

    private fun blocked(state: RetirementState) = report(
        if (state is RetirementState.InFlight) SessionRecoveryFinding.RETIREMENT_PENDING else SessionRecoveryFinding.PARTIAL_STATE,
    )

    private suspend fun pending(): Boolean {
        val result = processPending()
        checkCurrent()
        return result
    }

    private suspend fun <T> observe(component: SessionRecoveryComponent, read: suspend () -> PortResult<T>): T {
        val result = try { read() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
        // Do not flatten a closed/superseded inspection into a reassuring old evidence report.
        checkCurrent()
        return when (result) {
            is PortResult.Value -> result.value
            is PortResult.Failure -> throw EvidenceFailure(component, result.reason)
        }
    }

    private class EvidenceFailure(val component: SessionRecoveryComponent, val reason: FailureReason) : Exception("Recovery evidence unavailable")
    private fun unavailable(failure: EvidenceFailure) = SessionRecoveryReport(SessionRecoveryFinding.EVIDENCE_UNAVAILABLE, failure.component, failure.reason)
    private fun report(finding: SessionRecoveryFinding) = SessionRecoveryReport(finding)

    private fun sameData(a: StateRecordInspection, b: StateRecordInspection): Boolean {
        val leftTarget = a.target; val rightTarget = b.target
        if ((leftTarget == null) != (rightTarget == null) || (leftTarget != null && !sameTarget(leftTarget, rightTarget!!))) return false
        val left = a.record; val right = b.record
        return if (left == null || right == null) left == null && right == null else
            left.revision == right.revision && left.schemaVersion == right.schemaVersion && sameBytes(left.payload, right.payload)
    }

    private fun sameWork(a: SessionWorkSnapshot, b: SessionWorkSnapshot): Boolean =
        a.revision == b.revision && a.scope == b.scope && a.originBinding == b.originBinding && a.retiring == b.retiring &&
            a.entries.size == b.entries.size && a.entries.zip(b.entries).all { (left, right) ->
                left.phase == right.phase && left.ticket.id == right.ticket.id && left.ticket.kind == right.ticket.kind
            }

    private fun sameTarget(a: com.feedme.storage.StateRetirementTarget, b: com.feedme.storage.StateRetirementTarget): Boolean {
        val left = a.copyForStorage(); val right = b.copyForStorage()
        return try { left.contentEquals(right) } finally { left.fill(0); right.fill(0) }
    }

    private fun sameBytes(a: PrivateBytes, b: PrivateBytes): Boolean {
        val left = a.copyForCodec(); val right = b.copyForCodec()
        return try { left.contentEquals(right) } finally { left.fill(0); right.fill(0) }
    }
}
