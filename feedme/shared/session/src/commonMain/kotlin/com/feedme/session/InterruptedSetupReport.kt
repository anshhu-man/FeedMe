package com.feedme.session

import com.feedme.core.ports.FailureReason

/** Read-only advisory findings. None is a confirmation proposal or cleanup/activation right. */
enum class InterruptedSetupFinding {
    NONE, UNCONFIRMED_SETUP, ABORT_REQUESTED, OTHER_CONTROL_PENDING, RETIREMENT_PENDING,
    CONFIGURATION_CHANGED, RESOURCE_MISMATCH, EVIDENCE_UNAVAILABLE, EVIDENCE_CHANGED,
}

enum class InterruptedSetupComponent { CONTROL, CREDENTIAL_METADATA, PRIVATE_DATA, PRIVATE_BINDING, WORK_METADATA }
enum class InterruptedSetupDataStage { PREPARED, PARTIAL, SELECTED_EMPTY, BOUND, ABORTING, ABORTED }

/**
 * UI-safe enums only: no scope, identifier, plan, digest, revision, record, secret or native handle.
 * SELECTED and BOUND are observations, not durability/identity/credential-usability guarantees.
 * Stages appear only after exact native metadata and any sole binding survive the read bracket.
 * Factory-blocked/poisoned owners remain unavailable; this report cannot reopen or repair them.
 */
class InterruptedSetupReport internal constructor(
    val finding: InterruptedSetupFinding,
    val component: InterruptedSetupComponent? = null,
    val failureReason: FailureReason? = null,
    val credentialStage: CredentialCreateRecoveryStatus? = null,
    val dataStage: InterruptedSetupDataStage? = null,
    val workStage: SessionWorkOriginPlanStatus? = null,
) {
    val nextStep: SessionRecoveryNextStep get() = when (finding) {
        InterruptedSetupFinding.NONE -> SessionRecoveryNextStep.RECHECK_STARTUP
        InterruptedSetupFinding.RETIREMENT_PENDING -> SessionRecoveryNextStep.RETRY_EXISTING_RETIREMENT
        InterruptedSetupFinding.EVIDENCE_UNAVAILABLE, InterruptedSetupFinding.EVIDENCE_CHANGED -> SessionRecoveryNextStep.RECHECK_EVIDENCE
        else -> SessionRecoveryNextStep.PRESERVE_FOR_REPAIR
    }
    override fun toString() = "InterruptedSetupReport(finding=$finding, component=$component, nextStep=$nextStep)"
}
