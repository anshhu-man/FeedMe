package com.feedme.app.mealflow

import com.feedme.core.ports.*
import com.feedme.mealflow.AuthenticatedMealPlanningAccess
import com.feedme.mealflow.social.*

/** Test-only trusted mapping of the real borrowed native session. Its canonical user is an
 * explicit synthetic verifier fact, deliberately not StorageScope.actorId or credential text.
 * All calls and revocation occur on session.dispatcher. No production integration installs it. */
internal class NativeReviewedPostTestIdentity(
    private val actual: AuthenticatedMealPlanningAccess,
    private val session: NativeMealFlowTestSession,
) : PostPublicationPrincipalIntegration() {
    private val retainedOwner = Any()
    private var generation = Any()
    private var available = true
    private val actualBindings = mutableListOf<PublicationSessionBinding>()
    val delivery = Delivery(this)

    override suspend fun resolve(binding: PublicationSessionBinding): PortResult<PublicationPrincipalSnapshot> {
        if (!available || !matchesSession(binding, actual, session.boundary)) return PortResult.Failure(FailureReason.STALE_SESSION)
        if (actualBindings.none { it === binding }) {
            check(actualBindings.size < 8) // Explicit fixture lifetime bound, not production policy.
            actualBindings += binding
        }
        return PortResult.Value(mappedPrincipal(binding, NativeReviewedPostTestTransport.VERIFIED_USER, retainedOwner, generation))
    }

    fun requireSyntheticContext(context: ReviewedPostPrerequisiteContext) {
        val binding = checkNotNull(actualBindings.singleOrNull { isCurrent(it, context.principal) })
        check(context.lease === binding.lease && context.boundary === binding.boundary && context.boundary === session.boundary)
        check(context.origin == binding.origin && context.environment == binding.environment && context.environment == session.scope.environment)
        check(context.principal.canonicalUserId == NativeReviewedPostTestTransport.VERIFIED_USER)
    }

    override suspend fun requireCurrent(binding: PublicationSessionBinding,
        principal: PublicationPrincipalSnapshot): PortResult<Unit> =
        if (isCurrent(binding, principal)) PortResult.Value(Unit) else PortResult.Failure(FailureReason.STALE_SESSION)

    override fun isCurrent(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot): Boolean =
        available && matchesSession(binding, actual, session.boundary) &&
            matchesCurrentPrincipal(binding, principal, retainedOwner, generation)

    /** Exact pre-change discipline: revoke delivery before changing the mapping or session.
     * The test then calls actual session.invalidate; no observer-derived ACK is invented. */
    fun revokeBeforeSessionMutation() {
        delivery.revokeBeforeMutation()
        available = false
        generation = Any()
    }

    class Delivery(private val source: NativeReviewedPostTestIdentity) :
        PostPublicationDeliveryIntegration(source, maxPendingDeliveries = 4) {
        fun revokeBeforeMutation() = revokeCurrent()
        override fun capture(binding: PublicationSessionBinding,
            principal: PublicationPrincipalSnapshot): PortResult<PublicationDeliveryWitness> =
            if (!source.isCurrent(binding, principal)) PortResult.Failure(FailureReason.STALE_SESSION)
            else PortResult.Value(witness(binding, principal, generationFor(principal)))
    }
}
