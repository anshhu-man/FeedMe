package com.feedme.session

/**
 * One runtime-generation-bound presentation proposal. Call confirmation only after explicit
 * consent to discard this interrupted setup. It exposes no identity, plan, payload, revision,
 * native handle or credential and cannot be persisted/reconstructed as consent after restart.
 */
class PreparedSessionSetupAbort internal constructor(
    internal val owner: Any,
    internal val generation: Any,
    internal val configuration: String,
    internal val evidence: InterruptedSetupEvidence,
) {
    override fun toString() = "PreparedSessionSetupAbort(<redacted>)"
}
