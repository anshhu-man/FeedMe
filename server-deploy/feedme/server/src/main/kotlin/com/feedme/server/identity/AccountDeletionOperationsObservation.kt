package com.feedme.server.identity

/** The only states an internal account-deletion operator tool may reveal.
 *
 * This is not a public receipt status, ownership proof or completion checkpoint. The
 * eventual restricted observer must obtain [AccountDeletionOperationsEvidence] from one
 * audited database observation bound to the exact receipt. No email, subject, account,
 * device, provider identifier or free-form database value belongs in this projection.
 */
internal enum class AccountDeletionOperationsState {
    ACCEPTED_PENDING,
    HELD,
    PROVIDER_PENDING,
    COMPLETED,
    NOT_FOUND_OR_UNAVAILABLE,
}

/** Already-redacted evidence from the future restricted database adapter.
 *
 * [durableCompletion] is deliberately separate from core/provider progress. Nothing in
 * the current V041-V091 schema supplies it, so current production composition must always
 * pass false. A provider acknowledgement or absence is not whole-account completion.
 */
internal class AccountDeletionOperationsEvidence(
    val acceptedStage: String?,
    val workStage: String?,
    val lastReason: String?,
    val providerRequired: Boolean,
    val providerStage: String?,
    val durableCompletion: Boolean,
) {
    override fun toString() = "AccountDeletionOperationsEvidence(<redacted>)"
}

/** Strict mechanical projection only. Unknown, contradictory or future state fails closed. */
internal object AccountDeletionOperationsProjection {
    private val reasons = setOf(
        "none",
        "retry",
        "unattributed_events",
        "unknown_receipts",
        "related_data",
        "identity_conflict",
    )
    private val holds = reasons - setOf("none", "retry")
    private val providerStages = setOf(
        "prepared",
        "dispatched",
        "acknowledged",
        "outcome_unknown",
        "provider_absent",
    )

    fun project(evidence: AccountDeletionOperationsEvidence?): AccountDeletionOperationsState {
        if (evidence == null || evidence.acceptedStage != "pending") return unavailable()
        if (evidence.workStage !in setOf(null, "inventory", "core_erased")) return unavailable()
        if (evidence.lastReason !in reasons + null) return unavailable()
        if (evidence.providerStage !in providerStages + null) return unavailable()

        if (evidence.workStage == null) {
            if (evidence.lastReason != null || evidence.providerStage != null || evidence.durableCompletion)
                return unavailable()
            return AccountDeletionOperationsState.ACCEPTED_PENDING
        }

        val reason = evidence.lastReason ?: return unavailable()
        if (evidence.workStage == "inventory") {
            if (evidence.providerStage != null || evidence.durableCompletion) return unavailable()
            return if (reason in holds) AccountDeletionOperationsState.HELD
            else AccountDeletionOperationsState.ACCEPTED_PENDING
        }

        // Provider work can exist only after the durable core-erased checkpoint. A
        // configured provider may legitimately be waiting for its first durable provider
        // row; an unconfigured provider cannot manufacture a provider stage.
        if (!evidence.providerRequired && evidence.providerStage != null) return unavailable()
        if (reason in holds) return unavailable()

        if (evidence.durableCompletion) {
            val providerComplete = if (evidence.providerRequired)
                evidence.providerStage == "provider_absent"
            else evidence.providerStage == null
            return if (providerComplete) AccountDeletionOperationsState.COMPLETED else unavailable()
        }

        return if (evidence.providerRequired && evidence.providerStage != "provider_absent")
            AccountDeletionOperationsState.PROVIDER_PENDING
        else AccountDeletionOperationsState.ACCEPTED_PENDING
    }

    private fun unavailable() = AccountDeletionOperationsState.NOT_FOUND_OR_UNAVAILABLE
}
