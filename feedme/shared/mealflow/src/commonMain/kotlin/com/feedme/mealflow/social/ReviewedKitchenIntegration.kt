package com.feedme.mealflow.social

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*

/** Required platform-owned integrations. Success is a client prerequisite, never service
 * authorization. Callbacks run on the serialized identity dispatcher, must honor cancellation,
 * and must not re-enter controllers or change native session lifetime. Revoke delivery BEFORE
 * changing the real mapped principal/policy owner. No callback has an accepting default. */
interface ReviewedKitchenIntegration {
    val principals: PostPublicationPrincipalIntegration
    val delivery: PostPublicationDeliveryIntegration
    suspend fun disclosure(context: ReviewedPostPrerequisiteContext): PortResult<PublicationDisclosure>
    suspend fun requireNewPrivateSave(context: ReviewedPostPrerequisiteContext, check: ReviewedPrivateSaveCheck): PortResult<Unit>
    suspend fun requirePrivateSaveReplay(context: ReviewedPostPrerequisiteContext, check: ReviewedPrivateSaveReplayCheck): PortResult<Unit>
    suspend fun requireNewPublication(context: ReviewedPostPrerequisiteContext, check: ReviewedPublicationCheck): PortResult<Unit>
    suspend fun requirePublicationReplay(context: ReviewedPostPrerequisiteContext, check: ReviewedPublicationReplayCheck): PortResult<Unit>
}

enum class ReviewedPostPurpose { PRIVATE_SAVE, PUBLICATION }
/** Constructed only from the actual admitted internal context, not a screen account ID. */
class ReviewedPostPrerequisiteContext internal constructor(val purpose: ReviewedPostPurpose,
    val lease: SessionLease, val boundary: SessionBoundary, val origin: String, val environment: String,
    val principal: PublicationPrincipalSnapshot, val clientDraftId: String) {
    override fun toString() = "ReviewedPostPrerequisiteContext(<redacted>)"
}
class ReviewedPrivateSaveCheck internal constructor(val target: PublicationTarget.SavedDraft,
    val exactPatch: WireDocument, val exactBaseline: WireDocument, val expectedFields: WireDocument,
    val originalReviewedLocal: PublicationLocalObservation, val separatelyObservedCurrentLocal: PublicationLocalObservation,
    val displayedDisclosure: PublicationDisclosure?) {
    override fun toString() = "ReviewedPrivateSaveCheck(<redacted>)"
}
class ReviewedPrivateSaveReplayCheck internal constructor(val original: ReviewedDraftOriginalSnapshot,
    val observedAttempts: Int) {
    override fun toString() = "ReviewedPrivateSaveReplayCheck(<redacted>)"
}
class ReviewedPublicationCheck internal constructor(val exactPostWrite: WireDocument, val branch: ReviewedPostBranch,
    val savedDraftId: String?, val savedDraftVersion: ExactPostVersion?, val savedDraftETag: String?,
    val exactSavedBaseline: WireDocument?, val displayedDisclosure: PublicationDisclosure,
    val separatelyObservedCurrentLocal: PublicationLocalObservation) {
    override fun toString() = "ReviewedPublicationCheck(<redacted>)"
}
class ReviewedPublicationReplayCheck internal constructor(val original: PublicationOriginalSnapshot,
    val originalTarget: PublicationTarget, val exactSavedBaseline: WireDocument?, val observedAttempts: Int) {
    override fun toString() = "ReviewedPublicationReplayCheck(<redacted>)"
}

internal fun reviewedObservation(value: DraftLocalSnapshotV1) = PublicationLocalObservation(value.clientDraftId,
    value.localRevision, WireDocument.decode(value.exactUtf8.copyForCodec()))
internal fun reviewedOriginal(value: PostDraftCommandV2.ReviewedPatch) = ReviewedDraftOriginalSnapshot(value.id,
    value.clientId, value.localRevision, value.created, WireDocument.decode(value.original.body.copyForCodec()),
    value.original.baseline, value.original.etag, value.original.path.getValue("draftId"), value.expectedFields,
    value.historicalReview.displayedDisclosure,
    (value.historicalReview.exactReviewedLocalSnapshot.content as? DraftLocalContentV1.ComposerV2)?.historicalDisclosureText)
internal fun reviewedOriginal(value: PublicationOriginalLinkV1) = PublicationOriginalSnapshot(value.commandId,
    value.clientDraftId, value.originalCreatedAtMillis, value.originalCanonicalUserId, value.exactPostWrite,
    reviewedObservation(value.historicalReview.exactReviewedLocalSnapshot), value.historicalReview.displayedDisclosure)
internal fun reviewedTarget(value: PublicationReviewTargetV1, snapshot: DraftLocalSnapshotV1): PublicationTarget = when (value) {
    PublicationReviewTargetV1.DirectLocal -> PublicationTarget.DirectLocal(snapshot.clientDraftId, snapshot.localRevision)
    is PublicationReviewTargetV1.SavedDraft -> PublicationTarget.SavedDraft(snapshot.clientDraftId, snapshot.localRevision,
        value.draftId, value.draftVersion, value.reviewedETag)
}
internal fun reviewedBaseline(value: PublicationReviewTargetV1): WireDocument? =
    (value as? PublicationReviewTargetV1.SavedDraft)?.exactReviewedDraft

internal class ReviewedSaveAdapter(private val actual: ReviewedKitchenIntegration) : ReviewedDraftSavePrerequisites {
    override val principals get() = actual.principals
    override val delivery get() = actual.delivery
    private fun context(value: ReviewedDraftPrerequisiteContext) = ReviewedPostPrerequisiteContext(ReviewedPostPurpose.PRIVATE_SAVE,
        value.lease, value.boundary, value.origin, value.environment, value.principal, value.clientDraftId)
    override suspend fun disclosure(owner: ReviewedDraftPrerequisiteContext) = actual.disclosure(context(owner))
    override suspend fun requireNewSave(owner: ReviewedDraftPrerequisiteContext, check: ReviewedDraftNewSaveCheck) =
        actual.requireNewPrivateSave(context(owner), ReviewedPrivateSaveCheck(check.target, check.exactPatch,
            check.exactBaseline, check.expectedFields, reviewedObservation(check.exactReviewedLocalSnapshot),
            reviewedObservation(check.exactCurrentLocalSnapshot), check.displayedDisclosure))
    override suspend fun requireOriginalReplay(owner: ReviewedDraftPrerequisiteContext, check: ReviewedDraftOriginalReplayCheck) =
        actual.requirePrivateSaveReplay(context(owner), ReviewedPrivateSaveReplayCheck(reviewedOriginal(check.original), check.observedAttempts))
}
internal class ReviewedPublicationAdapter(private val actual: ReviewedKitchenIntegration) : PostPublicationLifecyclePrerequisites {
    override val principals get() = actual.principals
    override val delivery get() = actual.delivery
    private fun context(value: PublicationPrerequisiteContext) = ReviewedPostPrerequisiteContext(ReviewedPostPurpose.PUBLICATION,
        value.lease, value.boundary, value.origin, value.environment, value.principal, value.clientDraftId)
    override suspend fun disclosure(owner: PublicationPrerequisiteContext) = actual.disclosure(context(owner))
    override suspend fun requireNew(owner: PublicationPrerequisiteContext, check: PublicationNewPrerequisiteCheck) =
        actual.requireNewPublication(context(owner), ReviewedPublicationCheck(check.exactPostWrite,
            if (check.target is PublicationReviewTargetV1.DirectLocal) ReviewedPostBranch.DIRECT_LOCAL else ReviewedPostBranch.SAVED_DRAFT,
            (check.target as? PublicationReviewTargetV1.SavedDraft)?.draftId,
            (check.target as? PublicationReviewTargetV1.SavedDraft)?.draftVersion,
            (check.target as? PublicationReviewTargetV1.SavedDraft)?.reviewedETag, reviewedBaseline(check.target),
            check.displayedDisclosure, reviewedObservation(check.currentLocalSnapshot)))
    override suspend fun requireOriginalReplay(owner: PublicationPrerequisiteContext, check: PublicationReplayPrerequisiteCheck) =
        actual.requirePublicationReplay(context(owner), ReviewedPublicationReplayCheck(reviewedOriginal(check.original),
            reviewedTarget(check.original.historicalReview.target, check.original.historicalReview.exactReviewedLocalSnapshot),
            reviewedBaseline(check.original.historicalReview.target), check.observedAttempts))
}
