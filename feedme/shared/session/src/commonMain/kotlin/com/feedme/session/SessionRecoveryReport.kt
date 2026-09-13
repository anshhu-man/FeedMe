package com.feedme.session

import com.feedme.core.ports.FailureReason

/** Advisory, install-local evidence only. No value grants activation, erasure or offline access. */
enum class SessionRecoveryFinding {
    METADATA_EMPTY,
    VERIFICATION_REQUIRED,
    RETIREMENT_PENDING,
    EVIDENCE_UNAVAILABLE,
    EVIDENCE_CHANGED,
    PARTIAL_STATE,
    SCOPE_MISMATCH,
    DATA_MISSING,
    BINDING_MISSING,
    BINDING_INVALID,
    CONFIGURATION_CHANGED,
    BINDING_MISMATCH,
}

enum class SessionRecoveryComponent { CONTROL, CREDENTIAL_METADATA, WORK_METADATA, PRIVATE_BINDING }

/** Suggestions for a trusted presentation layer, not executable commands or repair permissions. */
enum class SessionRecoveryNextStep {
    RECHECK_STARTUP,
    VERIFY_PREVIOUS_IDENTITY,
    RETRY_EXISTING_RETIREMENT,
    RECHECK_EVIDENCE,
    PRESERVE_FOR_REPAIR,
}

/**
 * Contains no account/scope, token, UUID, revision, file path, key locator or configuration digest.
 * METADATA_EMPTY means only that no credential/work selection was found, not absence of owner
 * data or orphan keys. VERIFICATION_REQUIRED does not prove credential usability or identity.
 * Factory-open failures are outside this report: never reopen/create native stores as a probe.
 */
class SessionRecoveryReport internal constructor(
    val finding: SessionRecoveryFinding,
    val component: SessionRecoveryComponent? = null,
    val failureReason: FailureReason? = null,
) {
    val nextStep: SessionRecoveryNextStep get() = when (finding) {
        SessionRecoveryFinding.METADATA_EMPTY -> SessionRecoveryNextStep.RECHECK_STARTUP
        SessionRecoveryFinding.VERIFICATION_REQUIRED -> SessionRecoveryNextStep.VERIFY_PREVIOUS_IDENTITY
        SessionRecoveryFinding.RETIREMENT_PENDING -> SessionRecoveryNextStep.RETRY_EXISTING_RETIREMENT
        SessionRecoveryFinding.EVIDENCE_UNAVAILABLE, SessionRecoveryFinding.EVIDENCE_CHANGED -> SessionRecoveryNextStep.RECHECK_EVIDENCE
        else -> SessionRecoveryNextStep.PRESERVE_FOR_REPAIR
    }

    override fun toString() = "SessionRecoveryReport(finding=$finding, component=$component, nextStep=$nextStep)"
}
