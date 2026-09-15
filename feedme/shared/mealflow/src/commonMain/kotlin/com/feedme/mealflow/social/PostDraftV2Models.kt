package com.feedme.mealflow.social

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.mealflow.mealFail

/** Current format data only. Neither a parsed entry nor its revision grants owner mutation. */
internal sealed class PostDraftJournalEntry {
    class Legacy(entry: PostEntry) : PostDraftJournalEntry() {
        private val retained = PostEntry(entry.record, entry.value.copy(locals = entry.value.locals.toList(),
            issued = entry.value.issued.toList(), tombstones = entry.value.tombstones.toList()))
        val entry: PostEntry get() = PostEntry(retained.record, retained.value.copy(locals = retained.value.locals.toList(),
            issued = retained.value.issued.toList(), tombstones = retained.value.tombstones.toList()))
    }
    class Current(val entry: PostDraftV2Entry) : PostDraftJournalEntry()
    override fun toString() = "PostDraftJournalEntry(<redacted>)"
}
internal class PostDraftV2Entry(val record: PrivateRecord?, val value: PostDraftV2Record) {
    override fun toString() = "PostDraftV2Entry(<redacted>)"
}

/** Exact reviewed update envelope; empty query and purpose-fixed operation, never an execute
 * capability. A current live Save ticket and actual original persistence are separate. */
internal class ReviewedDraftOriginalFieldsV2(
    val id: String, val clientId: String, val localRevision: Long, path: Map<String, String>,
    val body: PrivateBytes, val baseline: WireDocument, val etag: String, val created: Long,
) {
    init { if (path.size != 1 || path.keys != setOf("draftId")) mealFail(FailureReason.INVALID_DATA) }
    private val retainedPath = path.toMap()
    val path: Map<String, String> get() = retainedPath.toMap()
    val operation: String get() = "updatePostDraft"
    fun historicalCall() = ApiCall(operation, pathParameters = path, body = body, idempotencyKey = SecretText(id), ifMatch = etag)
    override fun toString() = "ReviewedDraftOriginalFieldsV2(<redacted>)"
}

/** Exact versioned display material. No digest convention or deserialized live review token. */
internal class ReviewedDraftReviewMaterialV1 internal constructor(
    val exactUtf8: PrivateBytes, val exactReviewedLocalSnapshot: DraftLocalSnapshotV1,
    val displayedDisclosure: PublicationDisclosure?,
) {
    override fun toString() = "ReviewedDraftReviewMaterialV1(<redacted>)"
}

internal sealed class PostDraftCommandV2 {
    class TextCreate(val original: PostOriginal) : PostDraftCommandV2()
    class TextPatch(val original: PostOriginal) : PostDraftCommandV2()
    class Discard(val original: PostOriginal) : PostDraftCommandV2()
    class ReviewedPatch(val original: ReviewedDraftOriginalFieldsV2, val expectedFields: WireDocument,
        val historicalReview: ReviewedDraftReviewMaterialV1) : PostDraftCommandV2()

    val legacyOriginal: PostOriginal? get() = when (this) {
        is TextCreate -> original
        is TextPatch -> original
        is Discard -> original
        is ReviewedPatch -> null
    }
    val id: String get() = legacyOriginal?.id ?: (this as ReviewedPatch).original.id
    val clientId: String get() = legacyOriginal?.clientId ?: (this as ReviewedPatch).original.clientId
    val localRevision: Long get() = legacyOriginal?.localRevision ?: (this as ReviewedPatch).original.localRevision
    val created: Long get() = legacyOriginal?.created ?: (this as ReviewedPatch).original.created
    val operation: String get() = legacyOriginal?.operation ?: (this as ReviewedPatch).original.operation
    fun historicalCall(): ApiCall = legacyOriginal?.call() ?: (this as ReviewedPatch).original.historicalCall()
    override fun toString() = "PostDraftCommandV2(<redacted>)"
}

/** Historical markers cannot implement actual archive/domain/caller delivery proof. */
internal sealed class PostDraftCompletionV2 {
    class Legacy(val exactLegacy: PostCompletion) : PostDraftCompletionV2()
    class ReviewedApplied(val original: PostDraftCommandV2.ReviewedPatch, val actualDraft: WireDocument,
        val etag: String) : PostDraftCompletionV2()
    class ReviewedUnsent(val original: PostDraftCommandV2.ReviewedPatch) : PostDraftCompletionV2()
    val commandId: String get() = when (this) {
        is Legacy -> exactLegacy.commandId
        is ReviewedApplied -> original.id
        is ReviewedUnsent -> original.id
    }
    val clientId: String get() = when (this) {
        is Legacy -> exactLegacy.clientId
        is ReviewedApplied -> original.clientId
        is ReviewedUnsent -> original.clientId
    }
    val operation: String get() = when (this) {
        is Legacy -> exactLegacy.operation
        is ReviewedApplied, is ReviewedUnsent -> "updatePostDraft"
    }
    val unsent: Boolean get() = when (this) {
        is Legacy -> exactLegacy.unsent
        is ReviewedApplied -> false
        is ReviewedUnsent -> true
    }
    override fun toString() = "PostDraftCompletionV2(<redacted>)"
}

/** Opaque legacy meaning stays unchanged. Published history permanently closes its root. */
internal sealed class PostDraftTerminalV2 {
    class LegacyDiscard(val exactLegacy: PostTerminal) : PostDraftTerminalV2()
    class Published(val link: PublicationOriginalLinkV1, val postId: String) : PostDraftTerminalV2()
    val clientId: String get() = when (this) {
        is LegacyDiscard -> exactLegacy.clientId
        is Published -> link.clientDraftId
    }
    val commandId: String? get() = when (this) {
        is LegacyDiscard -> exactLegacy.commandId
        is Published -> link.commandId
    }
    override fun toString() = "PostDraftTerminalV2(<redacted>)"
}

/** All quantities are rederived by real codecs/cross-validation. Values are not permission
 * or trusted reservations merely because they were deserialized. No provider quota default. */
internal class PublicationCapacityReservationV1(
    val maxResponseBytes: Int, val publicationReservedBytes: Long,
    val draftFinalizationReservedBytes: Long, val reservedRemainderSlots: Int,
) {
    override fun toString() = "PublicationCapacityReservationV1(<redacted>)"
}
internal class PostDraftPublicationHoldV2(val link: PublicationOriginalLinkV1, val reservation: PublicationCapacityReservationV1) {
    override fun toString() = "PostDraftPublicationHoldV2(<redacted>)"
}

/** Closed-root unsubmitted content, not an editable local or server association. The snapshot
 * is required to be NotObserved here and bound to the exact terminal/link and newer revision.
 * Actual Retain as new draft must obtain a fresh ID under a separate acknowledged local intent. */
internal class PostDraftRemainderV2(val link: PublicationOriginalLinkV1, val unsubmittedSnapshot: DraftLocalSnapshotV1) {
    val clientId: String get() = link.clientDraftId
    val reviewedLocalRevision: Long get() = link.reviewedLocalRevision
    val newerLocalRevision: Long get() = unsubmittedSnapshot.localRevision
    val content: DraftLocalContentV1 get() = unsubmittedSnapshot.content
    override fun toString() = "PostDraftRemainderV2(<redacted>)"
}

/** Complete immutable current journal data. Only the sole owner may interpret this value into
 * registered current-operation mutations; publication receives separate opaque contributions. */
internal class PostDraftV2Record(
    val clock: Long,
    locals: List<DraftLocalSnapshotV1> = emptyList(), issued: List<String> = emptyList(),
    val command: PostDraftCommandV2? = null, val completion: PostDraftCompletionV2? = null,
    val localPending: PostLocalPending? = null, terminals: List<PostDraftTerminalV2> = emptyList(),
    val publicationHold: PostDraftPublicationHoldV2? = null, remainders: List<PostDraftRemainderV2> = emptyList(),
) {
    init {
        // Absolute supported policy ceilings bound the first defensive copy itself.
        if (locals.size > 64 || issued.size > 4096 || terminals.size > 4096 || remainders.size > 4096)
            mealFail(FailureReason.UNAVAILABLE)
    }
    private val retainedLocals = locals.toList(); private val retainedIssued = issued.toList()
    private val retainedTerminals = terminals.toList(); private val retainedRemainders = remainders.toList()
    val locals: List<DraftLocalSnapshotV1> get() = retainedLocals.toList()
    val issued: List<String> get() = retainedIssued.toList()
    val terminals: List<PostDraftTerminalV2> get() = retainedTerminals.toList()
    val remainders: List<PostDraftRemainderV2> get() = retainedRemainders.toList()
    fun copy(clock: Long = this.clock, locals: List<DraftLocalSnapshotV1> = this.locals, issued: List<String> = this.issued,
        command: PostDraftCommandV2? = this.command, completion: PostDraftCompletionV2? = this.completion,
        localPending: PostLocalPending? = this.localPending, terminals: List<PostDraftTerminalV2> = this.terminals,
        publicationHold: PostDraftPublicationHoldV2? = this.publicationHold, remainders: List<PostDraftRemainderV2> = this.remainders) =
        PostDraftV2Record(clock, locals, issued, command, completion, localPending, terminals, publicationHold, remainders)
    override fun toString() = "PostDraftV2Record(<redacted>)"
}
