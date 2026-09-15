package com.feedme.mealflow.social

import com.feedme.core.ports.*
import com.feedme.mealflow.mealFail
import com.feedme.sync.CommandIntent
import com.feedme.sync.CommandPhase
import com.feedme.sync.CommandView

/** Detached pure projection prepared by the sole draft codec/owner after full draft2 validation.
 * A constructed projection is comparison data only, never a StoreMutation/contribution or ACK. */
internal class DraftPublicationProjectionV2(val canonicalOrigin: String, locals: List<DraftLocalSnapshotV1>,
    issuedIds: List<String>, discardedRootIds: Set<String>, val hold: PublicationHoldProjectionV1?,
    published: List<PublishedRootProjectionV2>, remainders: List<PublicationRemainderProjectionV1>,
    val otherDraftCommandRoot: String?, val otherDraftCompletionRoot: String?) {
    private val localValues = locals.toList(); private val issuedValues = issuedIds.toList()
    private val discardedValues = discardedRootIds.toSet(); private val publishedValues = published.toList()
    private val remainderValues = remainders.toList()
    val locals get() = localValues.toList()
    val issuedIds get() = issuedValues.toList()
    val discardedRootIds get() = discardedValues.toSet()
    val published get() = publishedValues.toList()
    val remainders get() = remainderValues.toList()
    override fun toString() = "DraftPublicationProjectionV2(<redacted>)"
}
internal class PublicationHoldProjectionV1(val original: PublicationOriginalLinkV1,
    val currentLocalSnapshot: DraftLocalSnapshotV1, val reservation: PublicationCapacityReservationV1) {
    override fun toString() = "PublicationHoldProjectionV1(<redacted>)"
}
internal class PublishedRootProjectionV2(val clientDraftId: String, val commandId: String, val postId: String,
    val reviewedLocalRevision: Long, val original: PublicationOriginalLinkV1) {
    override fun toString() = "PublishedRootProjectionV2(<redacted>)"
}
internal class PublicationRemainderProjectionV1(val closedClientDraftId: String, val originalCommandId: String,
    val reviewedLocalRevision: Long, val newerLocalRevision: Long, val content: DraftLocalContentV1,
    val original: PublicationOriginalLinkV1) {
    override fun toString() = "PublicationRemainderProjectionV1(<redacted>)"
}

/** Pure queue comparison data, not persisted and not a queue-created receipt or permission.
 * Live callers capture the actual CommandView; unit fixtures can describe corrupt observations. */
internal class PublicationQueueObservation(val commandId: String, val operationId: String,
    val phase: CommandPhase, val attempts: Int, val localRevision: Long) {
    override fun toString() = "PublicationQueueObservation(<redacted>)"
    companion object {
        fun fromActual(command: CommandView) = PublicationQueueObservation(command.commandId, command.operationId,
            command.phase, command.attempts, command.localRevision)
    }
}

/** Complete pure cross-record consistency. No store reads, automatic repair, dispatch,
 * principal verification, review/receipt provenance, same-lease witness or local delivery. */
internal class PublicationCrossRecordValidator(private val publications: PostPublicationJournalCodecV1,
    private val links: PublicationOriginalLinkCodecV1, private val snapshots: DraftLocalSnapshotCodecV1,
    private val publicationPolicy: PostPublicationClientPolicy, private val draftPolicy: PostDraftClientPolicy) {
    fun requireConsistent(value: PublicationJournalV1, drafts: DraftPublicationProjectionV2) = publicationRecordGuard {
        // Rehydrate exact encoded data instead of trusting internally constructed lookalike fields.
        val journal = publications.decode(1, publications.encode(value))
        publicationRecordRequire(drafts.canonicalOrigin == journal.binding.canonicalOrigin)
        val locals = drafts.locals.map { snapshots.decode(snapshots.encode(it)) }
        val ids = drafts.issuedIds.map(::publicationRecordId)
        val localIds = locals.map { it.clientDraftId }
        val terminals = drafts.published
        val terminalIds = terminals.map { it.clientDraftId }
        val remainderRoots = drafts.remainders.map { it.closedClientDraftId }
        publicationRecordRequire(ids.distinct().size == ids.size && localIds.distinct().size == localIds.size &&
            terminalIds.distinct().size == terminalIds.size && remainderRoots.distinct().size == remainderRoots.size)
        publicationRecordBound(locals.size, draftPolicy.maxLocalDrafts)
        publicationRecordBound(ids.size, draftPolicy.maxIssuedIds)
        publicationRecordBound(terminals.size + drafts.discardedRootIds.size, draftPolicy.maxTombstones)
        publicationRecordBound(drafts.remainders.size + if (drafts.hold == null) 0 else 1, publicationPolicy.maxUnsubmittedRemainders)
        for (root in localIds + terminalIds + remainderRoots + drafts.discardedRootIds) {
            publicationRecordId(root); publicationRecordRequire(root in ids)
        }
        publicationRecordRequire(localIds.none { it in terminalIds || it in drafts.discardedRootIds } &&
            terminalIds.none { it in drafts.discardedRootIds })
        val allRoots = (localIds + terminalIds + remainderRoots + drafts.discardedRootIds +
            listOfNotNull(drafts.otherDraftCommandRoot, drafts.otherDraftCompletionRoot)).toSet()
        publicationRecordRequire(journal.issuedCommandIds.none { it in allRoots })
        for (entry in journal.entries) publicationRecordRequire(entry.original.clientDraftId in ids && entry.original.commandId in ids)

        val pending = journal.entries.filterIsInstance<PublicationHistoryEntryV1.PendingOriginal>().singleOrNull()
        publicationRecordRequire((pending == null) == (drafts.hold == null))
        drafts.hold?.let { hold ->
            val original = links.decode(links.encode(hold.original))
            links.requireSameExactLink(checkNotNull(pending).original, original)
            val current = snapshots.decode(snapshots.encode(hold.currentLocalSnapshot))
            val actual = locals.singleOrNull { it.clientDraftId == original.clientDraftId } ?: publicationRecordInvalid()
            publicationRecordRequire(current.exactUtf8.copyForCodec().contentEquals(actual.exactUtf8.copyForCodec()) &&
                current.localRevision >= original.reviewedLocalRevision)
            val reviewed = original.historicalReview.exactReviewedLocalSnapshot
            // Same logical revision must retain the same CONTENT, but a later separate GET may
            // observe the committed terminal draft. That is not ACK or a change to the original,
            // and must not prevent the required exact attempted-original replay.
            if (current.localRevision == original.reviewedLocalRevision)
                publicationRecordRequire(sameContent(current.content, reviewed.content))
            (reviewed.serverAssociation as? DraftServerAssociationV1.Observed)?.let { before ->
                val after = current.serverAssociation as? DraftServerAssociationV1.Observed ?: publicationRecordInvalid()
                val a = publicationRecordJson(before.exactPostDraft); val b = publicationRecordJson(after.exactPostDraft)
                publicationRecordRequire(publicationRecordString(a, "id") == publicationRecordString(b, "id") &&
                    postCompare(publicationRecordBigint(b.getValue("version")), publicationRecordBigint(a.getValue("version"))) >= 0)
                PostDraftAdapter(draftPolicy).monotone(before.exactPostDraft, after.exactPostDraft)
            }
            publicationRecordRequire(drafts.otherDraftCommandRoot != original.clientDraftId &&
                drafts.otherDraftCompletionRoot != original.clientDraftId)
            val reserve = hold.reservation
            publicationRecordRequire(reserve.maxResponseBytes == publicationPolicy.maxResponseBytes && reserve.reservedRemainderSlots == 1 &&
                reserve.publicationReservedBytes == publications.reservedPublishedBytes(journal) &&
                reserve.draftFinalizationReservedBytes > 0)
            if (reserve.publicationReservedBytes > publicationPolicy.maxRecordBytes || reserve.draftFinalizationReservedBytes > draftPolicy.maxRecordBytes)
                mealFail(FailureReason.UNAVAILABLE)
            // The actual draft codec computes/checks its complete finalization byte measure on
            // EVERY encode/decode. This projection cannot replace that whole-row calculation.
        }
        val published = journal.entries.filterIsInstance<PublicationHistoryEntryV1.PublishedHistorical>()
        publicationRecordRequire(published.size == terminals.size)
        for (result in published) {
            val original = result.original
            val terminal = terminals.singleOrNull { it.clientDraftId == original.clientDraftId } ?: publicationRecordInvalid()
            links.requireSameExactLink(original, links.decode(links.encode(terminal.original)))
            publicationRecordRequire(terminal.commandId == original.commandId && terminal.reviewedLocalRevision == original.reviewedLocalRevision &&
                terminal.postId == publicationRecordString(publicationRecordJson(result.reply.exactPost), "id"))
            publicationRecordId(terminal.postId)
            publicationRecordRequire(original.clientDraftId !in localIds && original.clientDraftId !in drafts.discardedRootIds &&
                drafts.otherDraftCommandRoot != original.clientDraftId && drafts.otherDraftCompletionRoot != original.clientDraftId)
        }
        for (remainder in drafts.remainders) {
            val terminal = terminals.singleOrNull { it.clientDraftId == remainder.closedClientDraftId } ?: publicationRecordInvalid()
            val original = links.decode(links.encode(remainder.original))
            links.requireSameExactLink(original, links.decode(links.encode(terminal.original)))
            publicationRecordRequire(remainder.closedClientDraftId == original.clientDraftId && remainder.originalCommandId == original.commandId &&
                remainder.reviewedLocalRevision == original.reviewedLocalRevision && remainder.newerLocalRevision > remainder.reviewedLocalRevision)
            // Validate complete retained content without creating a new editable root or old-root
            // media permission. This temporary snapshot is comparison data and is not persisted.
            snapshots.create(remainder.closedClientDraftId, remainder.newerLocalRevision, remainder.content, DraftServerAssociationV1.NotObserved)
        }
        for (root in listOfNotNull(drafts.otherDraftCommandRoot, drafts.otherDraftCompletionRoot)) {
            publicationRecordId(root); publicationRecordRequire(root in ids && root !in terminalIds)
        }
    }

    fun requirePendingIntent(original: PublicationOriginalLinkV1, command: PublicationQueueObservation,
        intent: CommandIntent) = publicationRecordGuard {
        val checked = validatedEntry(PublicationHistoryEntryV1.PendingOriginal(original)).original; queueIdentity(checked, command)
        publicationRecordRequire(command.phase !in setOf(CommandPhase.APPLIED, CommandPhase.DISCARDED))
        val expected = checked.originalIntentForComparison()
        publicationRecordRequire(intent.commandId == checked.commandId && intent.originBinding == checked.originBinding &&
            intent.dependencyCommandIds.isEmpty() && postSameCall(expected.call, intent.call))
    }
    fun requireArchivedHistory(entry: PublicationHistoryEntryV1, command: PublicationQueueObservation) = publicationRecordGuard {
        val checked = validatedEntry(entry); val original = checked.original; queueIdentity(original, command)
        when (checked) {
            is PublicationHistoryEntryV1.PublishedHistorical -> publicationRecordRequire(command.phase == CommandPhase.APPLIED && command.attempts > 0)
            is PublicationHistoryEntryV1.CancelledUnsentHistorical -> publicationRecordRequire(command.phase == CommandPhase.DISCARDED && command.attempts == 0)
            is PublicationHistoryEntryV1.PendingOriginal -> publicationRecordInvalid()
        }
    }
    private fun queueIdentity(original: PublicationOriginalLinkV1, command: PublicationQueueObservation) {
        publicationRecordRequire(command.commandId == original.commandId && command.operationId == "publishPost" &&
            command.attempts >= 0 && command.localRevision > 0)
    }
    private fun validatedEntry(entry: PublicationHistoryEntryV1): PublicationHistoryEntryV1 {
        val original = links.decode(links.encode(entry.original))
        val binding = PublicationJournalBindingData(original.environment, publicationRecordUuid(original.originBinding),
            original.originBinding, original.originalCanonicalUserId)
        return publications.decode(1, publications.encode(PublicationJournalV1(binding, original.originalCreatedAtMillis,
            listOf(original.commandId), listOf(entry)))).entries.single()
    }
    private fun sameContent(a: DraftLocalContentV1, b: DraftLocalContentV1): Boolean = when {
        a is DraftLocalContentV1.TextV1 && b is DraftLocalContentV1.TextV1 -> a.caption == b.caption && a.altText == b.altText
        a is DraftLocalContentV1.ComposerV2 && b is DraftLocalContentV1.ComposerV2 ->
            a.historicalDisclosureText == b.historicalDisclosureText &&
                publicationRecordSame(publicationRecordJson(a.exactChoices), publicationRecordJson(b.exactChoices))
        else -> false
    }
    override fun toString() = "PublicationCrossRecordValidator(<redacted>)"
}
