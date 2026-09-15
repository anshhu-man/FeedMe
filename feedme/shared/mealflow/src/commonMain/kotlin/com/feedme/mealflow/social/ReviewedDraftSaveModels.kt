package com.feedme.mealflow.social

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*

/** Opaque live identities: only the owning controller's retained registration accepts them. */
class PreparedReviewedDraftSave internal constructor() { override fun toString() = "PreparedReviewedDraftSave(<redacted>)" }
class PreparedReviewedDraftRetry internal constructor() { override fun toString() = "PreparedReviewedDraftRetry(<redacted>)" }
class PreparedReviewedDraftAllocationAbandon internal constructor() {
    override fun toString() = "PreparedReviewedDraftAllocationAbandon(<redacted>)"
}

/** Complete detached display from the SAME pin as its token. expectedContent is a comparison
 * projection, not a PostDraft/receipt: unknown updatedAt/expiresAt are deliberately absent. */
class ReviewedDraftSaveSnapshot internal constructor(val target: PublicationTarget.SavedDraft,
    val baseline: WireDocument, val patch: ReviewedDraftPatch, val exactProposedPatch: WireDocument,
    val resultingChoices: WireDocument, val expectedContent: WireDocument,
    val expectedSuccessorVersion: ExactPostVersion, val displayedDisclosure: PublicationDisclosure?,
    val historicalDisclosureText: String?, val preparedAtMillis: Long, val reviewExpiresAtMillis: Long) {
    val requiresLegacyLocalUpgrade = false // This successor admits only actual schema2.
    override fun toString() = "ReviewedDraftSaveSnapshot(<redacted>)"
}
class ReviewedDraftSavePresentation internal constructor(val token: PreparedReviewedDraftSave, val snapshot: ReviewedDraftSaveSnapshot) {
    override fun toString() = "ReviewedDraftSavePresentation(<redacted>)"
}

enum class ReviewedDraftRetryKind { REGISTRATION_RECONCILIATION, FIRST_DISPATCH_REVIEW, ATTEMPTED_ORIGINAL_REPLAY, LOCAL_RECEIPT_RECONCILIATION }
enum class ReviewedDraftAllocationPhase { ID_PROVIDER_UNRESOLVED, ABANDONED_WAITING_FOR_PROVIDER, ORIGINAL_REGISTRATION_UNRESOLVED }
/** Content-free status. An ID-provider outcome is not an enqueue or remote attempt. */
class ReviewedDraftAllocationStatus internal constructor(val clientDraftId: String, val localRevision: Long,
    val phase: ReviewedDraftAllocationPhase, val returnedCommandId: String?, val abandonToken: PreparedReviewedDraftAllocationAbandon?) {
    override fun toString() = "ReviewedDraftAllocationStatus(<redacted>)"
}
/** Raw historical request stays separate from current local/GET observations; none mint ACK. */
class ReviewedDraftOriginalSnapshot internal constructor(val commandId: String, val clientDraftId: String,
    val localRevision: Long, val originalCreatedAtMillis: Long, val exactPatch: WireDocument,
    val exactBaseline: WireDocument, val originalETag: String, val exactDraftPath: String,
    val expectedContent: WireDocument, val originalDisclosure: PublicationDisclosure?, val historicalDisclosureText: String?) {
    val historical = true
    override fun toString() = "ReviewedDraftOriginalSnapshot(<redacted>)"
}
class ReviewedDraftRetrySnapshot internal constructor(val kind: ReviewedDraftRetryKind,
    val original: ReviewedDraftOriginalSnapshot, val currentLocalChoices: WireDocument,
    val currentLocalRevision: Long, val currentSavedDraft: PostDraftObservation?,
    val observedAttempts: Int, val preparedAtMillis: Long, val reviewExpiresAtMillis: Long) {
    override fun toString() = "ReviewedDraftRetrySnapshot(<redacted>)"
}
class ReviewedDraftRetryPresentation internal constructor(val token: PreparedReviewedDraftRetry, val snapshot: ReviewedDraftRetrySnapshot) {
    override fun toString() = "ReviewedDraftRetryPresentation(<redacted>)"
}

/** Actual retained binding supplied internally, not a screen user ID or backend permission. */
internal class ReviewedDraftPrerequisiteContext(val lease: SessionLease, val boundary: SessionBoundary,
    val origin: String, val environment: String, val principal: PublicationPrincipalSnapshot, val clientDraftId: String) {
    override fun toString() = "ReviewedDraftPrerequisiteContext(<redacted>)"
}
internal class ReviewedDraftNewSaveCheck(val target: PublicationTarget.SavedDraft,
    val exactPatch: WireDocument, val exactBaseline: WireDocument, val expectedFields: WireDocument,
    val exactReviewedLocalSnapshot: DraftLocalSnapshotV1, val exactCurrentLocalSnapshot: DraftLocalSnapshotV1,
    val displayedDisclosure: PublicationDisclosure?) {
    override fun toString() = "ReviewedDraftNewSaveCheck(<redacted>)"
}
internal class ReviewedDraftOriginalReplayCheck(val original: PostDraftCommandV2.ReviewedPatch, val observedAttempts: Int) {
    override fun toString() = "ReviewedDraftOriginalReplayCheck(<redacted>)"
}

/** Required purpose-specific retained policy/source/media/audience integration. No default,
 * provider, publication eligibility reuse, automatic refresh or new disclosure endpoint.
 * All callbacks run on the actual serialized identity dispatcher, honor cancellation, and
 * never re-enter composition/controllers or mutate native identity lifetime. Their successes
 * are client prerequisites only; the canonical service still reauthorizes the exact PATCH.
 * delivery must atomically revoke BEFORE any relevant native principal/policy owner change.
 * requireOriginalReplay must check the immutable original, not rebuild it from current UI/GET.
 */
internal interface ReviewedDraftSavePrerequisites {
    val principals: PostPublicationPrincipalIntegration
    val delivery: PostPublicationDeliveryIntegration
    suspend fun disclosure(owner: ReviewedDraftPrerequisiteContext): PortResult<PublicationDisclosure>
    suspend fun requireNewSave(owner: ReviewedDraftPrerequisiteContext, check: ReviewedDraftNewSaveCheck): PortResult<Unit>
    suspend fun requireOriginalReplay(owner: ReviewedDraftPrerequisiteContext, check: ReviewedDraftOriginalReplayCheck): PortResult<Unit>
}
