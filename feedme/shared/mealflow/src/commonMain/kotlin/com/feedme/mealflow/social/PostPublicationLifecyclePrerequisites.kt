package com.feedme.mealflow.social

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*

/** Internally assembled from the actual current retained account, never a screen owner string.
 * Data provided to configured client prerequisites is not backend publication permission. */
internal class PublicationPrerequisiteContext internal constructor(val lease: SessionLease,
    val boundary: SessionBoundary, val origin: String, val environment: String,
    val principal: PublicationPrincipalSnapshot, val clientDraftId: String) {
    val verifiedAccountUserId: String get() = principal.canonicalUserId
    override fun toString() = "PublicationPrerequisiteContext(<redacted>)"
}

/** Complete exact content for NEW eligibility, including a never-attempted original. Neither
 * current local text nor a new disclosure may replace the immutable body being checked. */
internal class PublicationNewPrerequisiteCheck internal constructor(val exactPostWrite: WireDocument,
    val target: PublicationReviewTargetV1, val displayedDisclosure: PublicationDisclosure,
    val currentLocalSnapshot: DraftLocalSnapshotV1) {
    override fun toString() = "PublicationNewPrerequisiteCheck(<redacted>)"
}
/** Attempted-original eligibility intentionally differs from new publication. A committed root
 * or attached selected media does not by itself deny recovery of this exact immutable command. */
internal class PublicationReplayPrerequisiteCheck internal constructor(val original: PublicationOriginalLinkV1,
    val observedAttempts: Int) {
    override fun toString() = "PublicationReplayPrerequisiteCheck(<redacted>)"
}

/** Required concrete retained account/policy/media/audience/source integration. No accepting
 * default, invented disclosure, new network operation, arbitrary actorId conversion or factory.
 * All callbacks run on the serialized identity dispatcher, must honor cancellation, and must
 * not re-enter a public controller/composition or mutate native session lifetime. Their actual
 * owners must enforce readiness, expiry/revocation, ownership and rights for the supplied exact
 * data. Value(Unit) is CLIENT prerequisite success only; the service reauthorizes atomically.
 */
internal interface PostPublicationLifecyclePrerequisites {
    val principals: PostPublicationPrincipalIntegration
    val delivery: PostPublicationDeliveryIntegration
    suspend fun disclosure(owner: PublicationPrerequisiteContext): PortResult<PublicationDisclosure>
    suspend fun requireNew(owner: PublicationPrerequisiteContext, check: PublicationNewPrerequisiteCheck): PortResult<Unit>
    suspend fun requireOriginalReplay(owner: PublicationPrerequisiteContext, check: PublicationReplayPrerequisiteCheck): PortResult<Unit>
}

/** Actual future retained-controller generation/operation fence. Both callbacks are required;
 * there is no Boolean/default grant. The synchronous callback is SAME identity dispatcher only.
 * Final caller/StateFlow delivery still needs its own reviewed cross-dispatcher protocol. */
internal interface PublicationControllerOperationFence {
    suspend fun requireCurrent()
    fun requireCurrentNow()
    /** Exact real controller's registered final-tail ticket, never a decoded history marker. */
    fun ownsApplicationDelivery(ticket: PublicationControllerDeliveryTicket): Boolean
    /** Informational progress only: no review token, ID, caller ACK or write authority. */
    fun showAllocationPending(status: PublicationAllocationStatus)
}
