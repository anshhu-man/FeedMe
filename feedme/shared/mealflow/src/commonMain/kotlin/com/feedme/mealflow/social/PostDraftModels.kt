package com.feedme.mealflow.social

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.FailureReason

/** Explicit device-resource policy, not a product quota or a server retention policy.
 * Local text, issued identities and terminal markers are never automatically deleted. Capacity
 * refuses a new intent; it never recycles a clientDraftId or evicts an unresolved original.
 * Device-clock rollback blocks mutation, not proves expiry.
 */
class PostDraftClientPolicy(
    val maxLocalDrafts: Int,
    val maxRecordBytes: Int,
    val maxResponseBytes: Int,
    val maxIssuedIds: Int,
    val maxTombstones: Int,
    val pageSize: Int,
    val maxTraversalItems: Int,
    val confirmationMillis: Long,
) {
    init {
        require(maxLocalDrafts in 1..64 && maxRecordBytes in 65_536..1_048_576)
        require(maxResponseBytes in 1024..262_144 && maxIssuedIds in 2..4096 && maxTombstones in 1..4096)
        require(pageSize in 1..50 && maxTraversalItems in pageSize..10_000 && confirmationMillis in 1..300_000)
    }
}

enum class PostDraftScreen { HIDDEN, LOCAL_LIST, REMOTE_LIST, EDITOR }
enum class PostDraftPhase { IDLE, READY, PENDING, OFFLINE, ERROR, UNAVAILABLE }
enum class PostDraftIssue { NONE, ORIGINAL_PENDING, LOCAL_UNACKNOWLEDGED, CONFIRM_DISCARD,
    CONTEXT_CHANGED, RECONCILIATION_REQUIRED, CAPACITY, OFFLINE, INVALID_INPUT, DATA_UNVERIFIED, STORAGE, SESSION_UNAVAILABLE }

/** The full canonical remote object is preserved; this observation is not a mutation receipt. */
class PostDraftObservation internal constructor(val document: WireDocument, val etag: String, val historical: Boolean) {
    val id: String get() = postString(document, "id")
    val clientDraftId: String get() = postString(document, "clientDraftId")
    val status: String get() = postString(document, "status")
    val caption: String get() = postString(document, "caption")
    override fun toString() = "PostDraftObservation(status=$status, content=<redacted>)"
}
class LocalPostDraft internal constructor(val clientDraftId: String, val caption: String, val altText: String?,
    val localRevision: Long, val localAcknowledged: Boolean, val server: PostDraftObservation?,
    val textMatchesServer: Boolean,
    /** Content-free retained association; a denied/redacted server body is not a local-only draft.
     * This flag offers a cleanup affordance, never deletion authorization or server availability. */
    val serverAssociated: Boolean,
    /** Content shape only. This flag cannot construct a reviewed Save/publication ticket. */
    val requiresReviewedSave: Boolean = false,
    /** Historical local hold, never current server status or permission to retry. */
    val publicationHeld: Boolean = false) {
    override fun toString() = "LocalPostDraft(<redacted>)"
}
class PostDraftPending internal constructor(val commandId: String, val operationId: String, val clientDraftId: String,
    val phase: String, val attempts: Int, val issue: String, val canRetry: Boolean,
    val canDiscardUnsent: Boolean, val finalizationRequired: Boolean,
    /** Content-free routing only; prepare a new explicit original-review token, never invoke
     * the legacy plain retry action or reconstruct an original from these fields. */
    val requiresReviewedRetry: Boolean = false) {
    override fun toString() = "PostDraftPending(operation=$operationId, phase=$phase, details=<redacted>)"
}
/** Detached content-free link display. Exact original/reviewed content stays with the separate
 * publication review/retry flow; this summary is not a command or live consent token. */
class PostDraftPublicationHold internal constructor(val clientDraftId: String, val commandId: String,
    val reviewedLocalRevision: Long, val hasNewerLocalChanges: Boolean) {
    val historical: Boolean get() = true
    override fun toString() = "PostDraftPublicationHold(<redacted>)"
}
/** Closed-root retained changes are never exposed in editable localDrafts. Retain-as-new and
 * full content review require separate explicit operations; this summary cannot mint them. */
class PostDraftRemainderSummary internal constructor(val closedClientDraftId: String, val originalCommandId: String,
    val reviewedLocalRevision: Long, val newerLocalRevision: Long, val requiresReviewedSave: Boolean) {
    val historical: Boolean get() = true
    val editable: Boolean get() = false
    override fun toString() = "PostDraftRemainderSummary(<redacted>)"
}
/** Opaque live consent, never reconstructed from a stored document or a GET result. */
class PreparedPostDraftDiscard internal constructor(internal val owner: Any, internal val generation: Any,
    internal val clientId: String, internal val localRevision: Long, internal val baseline: WireDocument,
    internal val etag: String, internal val created: Long) {
    override fun toString() = "PreparedPostDraftDiscard(<redacted>)"
}
/** Last successful actual journal observation, including the configured empty-row format.
 * Presentation data only, never mutation, migration, consent or receipt authority. */
enum class PostDraftJournalFormat { NOT_OBSERVED, LEGACY, CURRENT }

/** Reference equality is intentional: final delivery CAS must never overwrite newer navigation. */
class PostDraftState internal constructor(val screen: PostDraftScreen, val phase: PostDraftPhase,
    localDrafts: List<LocalPostDraft>, val selected: LocalPostDraft?, remoteItems: List<PostDraftObservation>,
    val hasMore: Boolean, val remoteHeadEtag: String?, val historical: Boolean, val busy: Boolean,
    val pending: PostDraftPending?, val discardConfirmation: PreparedPostDraftDiscard?,
    val serverAcknowledged: Boolean, val issue: PostDraftIssue, val failureReason: FailureReason? = null,
    val earliestRetryAtMillis: Long? = null,
    val publicationHold: PostDraftPublicationHold? = null,
    unsubmittedRemainders: List<PostDraftRemainderSummary> = emptyList(),
    val reviewedSave: ReviewedDraftSavePresentation? = null,
    val reviewedRetry: ReviewedDraftRetryPresentation? = null,
    val reviewedAllocation: ReviewedDraftAllocationStatus? = null,
    val journalFormat: PostDraftJournalFormat = PostDraftJournalFormat.NOT_OBSERVED) {
    private val locals = localDrafts.toList()
    private val remotes = remoteItems.toList()
    private val remainders = unsubmittedRemainders.toList()
    val localDrafts get() = locals.toList()
    val remoteItems get() = remotes.toList()
    val unsubmittedRemainders get() = remainders.toList()
    override fun toString() = "PostDraftState(screen=$screen, phase=$phase, content=<redacted>)"
    internal companion object {
        fun empty() = PostDraftState(PostDraftScreen.HIDDEN, PostDraftPhase.IDLE, emptyList(), null, emptyList(),
            false, null, true, false, null, null, false, PostDraftIssue.NONE)
        fun unavailable() = PostDraftState(PostDraftScreen.HIDDEN, PostDraftPhase.UNAVAILABLE, emptyList(), null,
            emptyList(), false, null, true, false, null, null, false, PostDraftIssue.SESSION_UNAVAILABLE, FailureReason.STALE_SESSION)
    }
}
