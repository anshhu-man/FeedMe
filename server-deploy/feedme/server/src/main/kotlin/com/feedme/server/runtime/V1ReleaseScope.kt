package com.feedme.server.runtime

import com.feedme.server.contract.ContractOperation

/**
 * Runtime enforcement of the approved free V1 partition.
 *
 * Shared operations remain reachable because an operation is deferred only when every
 * canonical feature association is deferred. The canonical contract is byte-pinned and
 * validates every feature identifier before this policy is evaluated.
 */
internal object V1ReleaseScope {
    internal data class BackgroundRegistration(val id: String, val featureIds: Set<String>)

    val deferredFeatureIds: Set<String> = setOf(
        "F32", "F33", "F34", "F35", "F36", "F37", "F38", "F44", "F45", "F46",
    )

    /** The only production server loop currently registered by AccountCoreRuntime. */
    val reactionNotificationRegistration =
        BackgroundRegistration("reaction-notifications", setOf("F27", "F41"))

    fun isDeferredOnly(operation: ContractOperation): Boolean =
        operation.featureIds.isNotEmpty() && operation.featureIds.all { it in deferredFeatureIds }

    /**
     * Autonomous work is stricter than an interactive mixed operation: every owner must be an
     * approved V1 feature because a background process cannot ask the user which branch to run.
     */
    fun allowsBackground(registration: BackgroundRegistration): Boolean =
        registration.id.matches(Regex("[a-z][a-z0-9-]{0,63}")) &&
            registration.featureIds.isNotEmpty() &&
            registration.featureIds.all { it.matches(Regex("F(?:0[1-9]|[1-4][0-9]|5[0-4])")) } &&
            registration.featureIds.none { it in deferredFeatureIds }

    /** FeedMe V1 is free. No store/provider offer may be registered by this runtime. */
    fun requireProductionRegistrations(background: Set<BackgroundRegistration>, paidOfferIds: Set<String>) {
        check(background.size == background.map { it.id }.toSet().size)
        check(background.all(::allowsBackground))
        check(paidOfferIds.isEmpty())
    }
}
