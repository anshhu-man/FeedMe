package com.feedme.mealflow.social

import com.feedme.core.ports.*
import com.feedme.mealflow.*
import kotlin.test.*

/** Explicit test-only mapped owner, disclosure and new-save/replay prerequisites. No fixture
 * is exported through a production factory or inferred from StorageScope.actorId. */
internal class ReviewedDraftSaveTestPorts(private val access: AuthenticatedMealPlanningAccess,
    private val boundary: SessionBoundary) : ReviewedDraftSavePrerequisites {
    override val principals = Source(access, boundary)
    override val delivery = Delivery(principals)
    var actualDisclosure = PublicationDisclosure("reviewed-v1", "Explicit synthetic disclosure shown to this reviewer")
    var newCalls = 0; var replayCalls = 0; var disclosureCalls = 0
    var denyNew: FailureReason? = null; var denyReplay: FailureReason? = null; var denyDisclosure: FailureReason? = null
    var beforeNew: suspend (ReviewedDraftNewSaveCheck) -> Unit = {}
    var beforeReplay: suspend (ReviewedDraftOriginalReplayCheck) -> Unit = {}
    var beforeDisclosure: suspend () -> Unit = {}
    override suspend fun disclosure(owner: ReviewedDraftPrerequisiteContext): PortResult<PublicationDisclosure> {
        requireContext(owner); disclosureCalls++; beforeDisclosure()
        return denyDisclosure?.let { PortResult.Failure(it) } ?: PortResult.Value(actualDisclosure)
    }
    override suspend fun requireNewSave(owner: ReviewedDraftPrerequisiteContext, check: ReviewedDraftNewSaveCheck): PortResult<Unit> {
        requireContext(owner); newCalls++; beforeNew(check)
        assertEquals(owner.clientDraftId, check.target.clientDraftId)
        assertEquals(owner.clientDraftId, check.exactReviewedLocalSnapshot.clientDraftId)
        assertEquals(owner.clientDraftId, check.exactCurrentLocalSnapshot.clientDraftId)
        return denyNew?.let { PortResult.Failure(it) } ?: PortResult.Value(Unit)
    }
    override suspend fun requireOriginalReplay(owner: ReviewedDraftPrerequisiteContext, check: ReviewedDraftOriginalReplayCheck): PortResult<Unit> {
        requireContext(owner); replayCalls++; beforeReplay(check)
        assertEquals(owner.clientDraftId, check.original.clientId); assertTrue(check.observedAttempts > 0)
        return denyReplay?.let { PortResult.Failure(it) } ?: PortResult.Value(Unit)
    }
    fun revokeBeforeOwnerChange() { delivery.revoke(); principals.generation = Any() }
    private fun requireContext(context: ReviewedDraftPrerequisiteContext) {
        assertSame(access.lease, context.lease); assertSame(boundary, context.boundary)
        assertEquals(access.origin, context.origin); assertEquals(access.lease.scope.environment, context.environment)
        assertTrue(principals.isCurrent(context.principal.binding, context.principal))
    }
    class Source(private val access: AuthenticatedMealPlanningAccess, private val boundary: SessionBoundary) : PostPublicationPrincipalIntegration() {
        private val owner = Any(); var generation = Any()
        var beforeResolve: suspend () -> Unit = {}
        var beforeCurrent: suspend () -> Unit = {}
        override suspend fun resolve(binding: PublicationSessionBinding): PortResult<PublicationPrincipalSnapshot> {
            beforeResolve()
            return PortResult.Value(mappedPrincipal(binding, "aaaaaaaa-1111-4111-8111-111111111111", owner, generation))
        }
        override suspend fun requireCurrent(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot): PortResult<Unit> {
            beforeCurrent()
            return if (isCurrent(binding, principal)) PortResult.Value(Unit) else PortResult.Failure(FailureReason.STALE_SESSION)
        }
        override fun isCurrent(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot) =
            matchesSession(binding, access, boundary) && matchesCurrentPrincipal(binding, principal, owner, generation)
    }
    class Delivery(source: Source) : PostPublicationDeliveryIntegration(source, maxPendingDeliveries = 32) {
        var beforeCapture: () -> Unit = {}
        override fun capture(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot): PortResult<PublicationDeliveryWitness> {
            beforeCapture(); return PortResult.Value(witness(binding, principal, generationFor(principal)))
        }
        fun revoke() = revokeCurrent()
    }
}
