package com.feedme.android

import com.feedme.core.ports.*
import com.feedme.mealflow.AuthenticatedMealPlanningAccess
import com.feedme.mealflow.social.*
import com.feedme.session.EmailAccountPrivateConnection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** One borrowed identity integration for the retained real native account connection.
 * Construction/subscription and principal reads belong to its identity dispatcher.
 * Construction and synchronous currentness do no I/O; explicit resolution rechecks the
 * actual native handoff. The account ID comes only from the verified bootstrap/onboarding archive;
 * neither the private scope ID, getMe, a profile lookup nor a UI argument is that evidence.
 * This mapping is comparison data, never server publication/attachment authorization. */
@OptIn(ExperimentalAtomicApi::class)
internal class NativeReviewedPostIdentity(
    private val connection: EmailAccountPrivateConnection,
    private val actual: AuthenticatedMealPlanningAccess,
) : PostPublicationPrincipalIntegration() {
    private val closed = AtomicBoolean(false)
    private val mapping = connection.access.accountMapping
    private val actualDelivery = Delivery(this)
    val principals: PostPublicationPrincipalIntegration get() = this
    val delivery: PostPublicationDeliveryIntegration get() = actualDelivery

    // Registered before this object can issue a principal or epoch. The callback may run
    // off-dispatcher; it touches atomics only and never invokes confined observers.
    private val retirement = connection.onRetiring {
        closed.store(true)
        actualDelivery.retireFromAnyCaller()
    }
    // Native mapping/refresh changes run on the same identity dispatcher. Revoke the exact
    // old epoch before the runtime rotates its retained observation, then redact observers.
    private val mappingChanges = mapping?.onChanging { actualDelivery.revokeBeforeMappingChange() }

    override suspend fun resolve(binding: PublicationSessionBinding): PortResult<PublicationPrincipalSnapshot> {
        currentCoroutineContext().ensureActive()
        if (mapping == null) return PortResult.Failure(FailureReason.NOT_CONFIGURED)
        if (!sameSession(binding) || !connection.isCurrentForNavigation())
            return PortResult.Failure(FailureReason.STALE_SESSION)
        currentCoroutineContext().ensureActive()
        val observation = mapping.observe() ?: return PortResult.Failure(FailureReason.STALE_SESSION)
        if (!sameSession(binding)) return PortResult.Failure(FailureReason.STALE_SESSION)
        val principal = mappedPrincipal(binding, observation.canonicalUserId, mapping, observation)
        currentCoroutineContext().ensureActive()
        return if (isCurrent(binding, principal)) PortResult.Value(principal)
        else PortResult.Failure(FailureReason.STALE_SESSION)
    }

    override suspend fun requireCurrent(binding: PublicationSessionBinding,
        principal: PublicationPrincipalSnapshot): PortResult<Unit> {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(binding, principal) || !connection.isCurrentForNavigation())
            return PortResult.Failure(FailureReason.STALE_SESSION)
        currentCoroutineContext().ensureActive()
        return if (isCurrent(binding, principal)) PortResult.Value(Unit)
        else PortResult.Failure(FailureReason.STALE_SESSION)
    }

    override fun isCurrent(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot): Boolean {
        if (!sameSession(binding)) return false
        val owner = mapping ?: return false
        val observation = owner.observe() ?: return false
        return matchesCurrentPrincipal(binding, principal, owner, observation) &&
            principal.canonicalUserId == observation.canonicalUserId
    }

    private fun sameSession(binding: PublicationSessionBinding): Boolean =
        !closed.load() && connection.access.mode == com.feedme.session.PrivateSessionAccessMode.ONLINE &&
            binding.lease === connection.access.lease && binding.boundary === connection.boundary &&
            binding.origin == connection.access.originBinding && binding.environment == connection.access.scope.environment &&
            matchesSession(binding, actual, connection.boundary)

    /** Identity dispatcher only; this closes borrowed RAM subscriptions, not native stores,
     * credentials, the connection or an account. Retired instances are never reusable. */
    fun close() {
        closed.store(true)
        actualDelivery.retireFromAnyCaller()
        try { actualDelivery.finishOnIdentityDispatcher() }
        finally { mappingChanges?.close(); retirement.close() }
    }

    private class Delivery(private val source: NativeReviewedPostIdentity) :
        PostPublicationDeliveryIntegration(source, maxPendingDeliveries = 32) {
        fun retireFromAnyCaller() = retireAtomically()
        fun revokeBeforeMappingChange() = revokeCurrent()
        fun finishOnIdentityDispatcher() = finishRetirement()

        override fun capture(binding: PublicationSessionBinding,
            principal: PublicationPrincipalSnapshot): PortResult<PublicationDeliveryWitness> =
            if (!source.isCurrent(binding, principal)) PortResult.Failure(FailureReason.STALE_SESSION)
            else PortResult.Value(witness(binding, principal, generationFor(principal)))
    }
}
