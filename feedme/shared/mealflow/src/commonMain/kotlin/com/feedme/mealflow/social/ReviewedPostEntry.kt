package com.feedme.mealflow.social

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.mealflow.*
import kotlinx.coroutines.*

enum class ReviewedPostBranch { DIRECT_LOCAL, SAVED_DRAFT }
enum class ReviewedDraftFormat { LEGACY_TEXT, CURRENT_TEXT, CURRENT_COMPOSER }
enum class ReviewedDraftUpgradeResult { CURRENT_REVIEW_REQUIRED }
class PreparedReviewedDraftUpgrade internal constructor(val clientDraftId: String, val localRevision: Long,
    val retainedDraftCount: Int, val expiresAtMillis: Long) {
    override fun toString() = "PreparedReviewedDraftUpgrade(<redacted>)"
}
/** Detached display only. A historical disclosure is never current policy consent. No choices
 * are invented for text-only records. exactChoices/choices are null until explicit full editing. */
class ReviewedPostSelection internal constructor(val clientDraftId: String, val localRevision: Long,
    val format: ReviewedDraftFormat, val caption: String, val altText: String?,
    val exactChoices: WireDocument?, val choices: ReviewedPostChoices?, val savedDraft: PostDraftObservation?,
    val historicalDisclosure: PublicationDisclosure?, internal val selectionFence: DraftReviewedSelectionFence) {
    val needsExplicitChoices: Boolean get() = choices == null
    val needsUpgrade: Boolean get() = format == ReviewedDraftFormat.LEGACY_TEXT
    /** Editable DATA only. Use an explicitly supplied disclosure to retain all historical
     * choice fields even when old display text is absent. This neither writes nor reviews. */
    fun choicesUsingDisclosure(disclosure: PublicationDisclosure): ReviewedPostChoices? =
        exactChoices?.let { reviewedChoiceValues(it, null, disclosure) }
    override fun toString() = "ReviewedPostSelection(<redacted>)"
}

/** Explicit opt-in facade over the actual controllers/sole journal. No hidden selected defaults,
 * implicit Save, migration-on-read, raw-store entry point or native session ownership. */
class ReviewedPostEntry internal constructor(val drafts: PostDraftController, val publications: PostComposerController) {
    suspend fun inspectSelected(clientDraftId: String, expectedLocalRevision: Long): PortResult<ReviewedPostSelection> =
        drafts.inspectReviewedSelection(clientDraftId, expectedLocalRevision)
    suspend fun editChoices(clientDraftId: String, expectedLocalRevision: Long, choices: ReviewedPostChoices): PortResult<PostDraftState> =
        drafts.editReviewedChoices(clientDraftId, expectedLocalRevision, choices)
    suspend fun prepareRestoredLocalRetention(clientDraftId: String, expectedLocalRevision: Long): PortResult<RestoredLocalRetentionPresentation> =
        drafts.prepareRestoredLocalRetention(clientDraftId, expectedLocalRevision)
    suspend fun confirmRestoredLocalRetention(token: PreparedRestoredLocalRetention): PortResult<PostDraftState> =
        drafts.confirmRestoredLocalRetention(token)
    suspend fun prepareUpgrade(clientDraftId: String, expectedLocalRevision: Long): PortResult<PreparedReviewedDraftUpgrade> =
        drafts.prepareReviewedUpgrade(clientDraftId, expectedLocalRevision)
    suspend fun confirmUpgrade(token: PreparedReviewedDraftUpgrade): PortResult<ReviewedDraftUpgradeResult> = drafts.confirmReviewedUpgrade(token)

    suspend fun loadDisclosure(clientDraftId: String, purpose: ReviewedPostPurpose): PortResult<PublicationDisclosure> = when (purpose) {
        ReviewedPostPurpose.PRIVATE_SAVE -> drafts.loadReviewedDisclosure(clientDraftId)
        ReviewedPostPurpose.PUBLICATION -> when (val result = publications.loadDisclosure(clientDraftId)) {
            is PortResult.Failure -> result
            is PortResult.Value -> result.value.disclosure?.let { PortResult.Value(it) } ?: PortResult.Failure(FailureReason.CONFLICT)
        }
    }
    suspend fun prepareSelectedReviewedSave(clientDraftId: String, expectedLocalRevision: Long): PortResult<ReviewedDraftSavePresentation> {
        return when (val observed = inspectSelected(clientDraftId, expectedLocalRevision)) {
            is PortResult.Failure -> observed
            is PortResult.Value -> {
                val current = observed.value
                val choices = current.choices ?: return PortResult.Failure(FailureReason.NOT_CONFIGURED)
                val saved = current.savedDraft ?: return PortResult.Failure(FailureReason.CONFLICT)
                try {
                    val target = reviewedSavedTarget(current, saved)
                    drafts.prepareReviewedSave(target, reviewedCompletePatch(current, choices, saved.document))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { PortResult.Failure((failure as? MealFailure)?.reason ?: FailureReason.INVALID_DATA) }
            }
        }
    }
    suspend fun prepareSelectedPublication(clientDraftId: String, expectedLocalRevision: Long,
        branch: ReviewedPostBranch): PortResult<PublicationReviewView> {
        return when (val observed = inspectSelected(clientDraftId, expectedLocalRevision)) {
            is PortResult.Failure -> observed
            is PortResult.Value -> {
                val current = observed.value
                val choices = current.choices ?: return PortResult.Failure(FailureReason.NOT_CONFIGURED)
                val target = when (branch) {
                    ReviewedPostBranch.DIRECT_LOCAL -> {
                        if (current.savedDraft != null) return PortResult.Failure(FailureReason.CONFLICT)
                        PublicationTarget.DirectLocal(clientDraftId, expectedLocalRevision)
                    }
                    ReviewedPostBranch.SAVED_DRAFT -> current.savedDraft?.let { reviewedSavedTarget(current, it) }
                        ?: return PortResult.Failure(FailureReason.CONFLICT)
                }
                when (val result = publications.prepareSelectedReview(target, choices, current.selectionFence)) {
                    is PortResult.Failure -> result
                    is PortResult.Value -> result.value.review?.let { PortResult.Value(it) } ?: PortResult.Failure(FailureReason.CONFLICT)
                }
            }
        }
    }
    /** Explicit historical restore only. Existing schema1 remains schema1. Restore does not
     * recreate review/upgrade/ACK tokens. Each controller retains its own failure/redaction. */
    suspend fun restore(): PortResult<Unit> {
        when (val result = drafts.restoreLocal()) { is PortResult.Failure -> return result; is PortResult.Value -> Unit }
        return when (val result = publications.restore()) { is PortResult.Failure -> result; is PortResult.Value -> PortResult.Value(Unit) }
    }
    suspend fun close(): PortResult<Unit> = withContext(NonCancellable) {
        val publication = publications.close()
        val draft = drafts.close()
        if (publication is PortResult.Failure) publication else draft
    }
    override fun toString() = "ReviewedPostEntry(<redacted>)"
}
internal fun reviewedSavedTarget(value: ReviewedPostSelection, saved: PostDraftObservation) = PublicationTarget.SavedDraft(
    value.clientDraftId, value.localRevision, saved.id, ExactPostVersion(saved.etag.removeSurrounding("\"")), saved.etag)
