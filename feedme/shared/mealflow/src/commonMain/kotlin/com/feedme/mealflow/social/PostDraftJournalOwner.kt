package com.feedme.mealflow.social

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.mealflow.*
import com.feedme.sync.CommandPhase
import com.feedme.transport.MobileRequestValidator
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Instant

/** Final identity-only publication handles. A constructed or retained handle alone is neither
 * current write admission, actual readback nor caller acknowledgement. */
internal class DraftReviewPin internal constructor() { override fun toString() = "DraftReviewPin(<redacted>)" }
internal class DraftMutationContribution internal constructor() { override fun toString() = "DraftMutationContribution(<redacted>)" }
internal class DraftPublicationReadback internal constructor() { override fun toString() = "DraftPublicationReadback(<redacted>)" }
internal class DraftPublicationEvidenceRetirement internal constructor() { override fun toString() = "DraftPublicationEvidenceRetirement(<redacted>)" }

/** Identity-only handles. A constructed lookalike is never registered and grants no use. */
internal class DraftJournalParticipant internal constructor() {
    override fun toString() = "DraftJournalParticipant(<redacted>)"
}
internal class DraftJournalUse internal constructor() {
    override fun toString() = "DraftJournalUse(<redacted>)"
}

/** Sole version-aware namespace codec, read and mutation owner. All calls are on the same serialized
 * identity dispatcher as the composition. No constructor/registration I/O or native ownership.
 * Publication gets only registered purpose-fixed contributions and detached validated projections,
 * never the mutable draft row or a general writer. No implicit schema upgrade is performed. */
@OptIn(ExperimentalAtomicApi::class)
internal class PostDraftJournalOwner(
    private val composition: MealKitchenComposition,
    private val policy: PostDraftClientPolicy,
    val configuredPublicationPolicy: PostPublicationClientPolicy? = null,
) {
    private val access = composition.access
    private val boundary = composition.boundary
    private val origin = uuid(access.origin)
    private val key = RecordKey("mealflow.post-drafts.v1", origin)
    private val codec = PostDraftCodec(origin, policy)
    private val snapshotCodec = configuredPublicationPolicy?.let { DraftLocalSnapshotCodecV1(policy, it) }
    private val linkCodec = configuredPublicationPolicy?.let { PublicationOriginalLinkCodecV1(it, snapshotCodec!!) }
    private val currentCodec = configuredPublicationPolicy?.let {
        PostDraftV2Codec(access.lease.scope.environment, access.origin, policy, it, snapshotCodec!!, linkCodec!!)
    }
    private val store = composition.store
    private class Member(val token: DraftJournalParticipant, val borrower: MealKitchenComposition.Borrower, var attached: Boolean)
    private class Use(val token: DraftJournalUse, val member: Member, val permit: ComposerOperationPermit) {
        val prepared = mutableListOf<StoreMutation.Put>()
        val entries = mutableListOf<PostDraftV2Entry>()
        val observed = mutableListOf<PrivateRecord>()
        var retirement: Retirement? = null
    }
    private class DeliveredLocalMarker(val edit: PostDraftCurrentEdit, val mutation: StoreMutation.Put,
        val delivery: PostDraftCurrentDelivery)
    private class DeliveredApplyMarker(val apply: PostDraftCurrentApply, val archive: StoreMutation.Put,
        val delivery: PostDraftCurrentDelivery)
    private class Pin(val token: DraftReviewPin, val snapshot: DraftLocalSnapshotV1,
        val deliveredLocal: DeliveredLocalMarker?, val deliveredApply: DeliveredApplyMarker?)
    private class HoldProposal(val entry: PostDraftV2Entry, val value: PostDraftV2Record,
        val deliveredLocal: DeliveredLocalMarker?, val deliveredApply: DeliveredApplyMarker?)
    private val pins = mutableListOf<Pin>()
    private var publicationObserver: Pair<DraftJournalParticipant, (String) -> Unit>? = null
    private var publicationAllocations: Pair<DraftJournalParticipant, PublicationAllocationRegistry>? = null
    private val members = mutableListOf<Member>()
    private val uses = mutableListOf<Use>()
    private var started = false
    private var retired = !boundary.isCurrent(access.lease)
    private var claim: Claim? = null
    // No listener until an actual member exists: failed construction or an unavailable
    // zero-member controller must not retain the owner/composition through the boundary.
    private var subscription: SessionInvalidationSubscription? = null

    fun requireComposition(actual: MealKitchenComposition, actualPolicy: PostDraftClientPolicy) {
        if (actual !== composition || actualPolicy !== policy) mealFail(FailureReason.CONFLICT)
    }

    /** Assembly-only registration. No domain check, claim, consent, read or mutation. */
    fun register(borrower: MealKitchenComposition.Borrower): DraftJournalParticipant {
        if (retired) mealFail(FailureReason.STALE_SESSION)
        composition.requireComposerParticipant(borrower)
        if (started || members.any { it.borrower.feature == borrower.feature }) mealFail(FailureReason.CONFLICT)
        return DraftJournalParticipant().also { token ->
            members += Member(token, borrower, !retired && boundary.isCurrent(access.lease) && borrower.hooks != null)
            if (subscription == null) {
                val installed = boundary.onInvalidated(access.lease) {
                    retired = true
                    members.forEach { it.attached = false }
                    pins.clear(); publicationObserver = null; publicationAllocations = null
                    drain()
                }
                if (retired) installed.close() else subscription = installed
            }
        }
    }

    /** Assembly-only observer; root-specific cache invalidation, never ACK or proof disposal. */
    fun registerDraftPublicationObserver(participant: DraftJournalParticipant, observer: (String) -> Unit) {
        val member = member(participant)
        if (member.borrower.feature != MealKitchenFeature.POST_DRAFTS || started || publicationObserver != null)
            mealFail(FailureReason.CONFLICT)
        publicationObserver = participant to observer
    }

    /** Assembly binding to the concrete allocator, never a caller-supplied accepting port.
     * Registration grants no current use, ID allocation, original replay or write permission. */
    fun registerPublicationAllocations(participant: DraftJournalParticipant, registry: PublicationAllocationRegistry) {
        val member = member(participant)
        if (member.borrower.feature != MealKitchenFeature.POST_PUBLICATIONS || started || publicationAllocations != null)
            mealFail(FailureReason.CONFLICT)
        registry.requireAssembly(composition, this, member.borrower)
        publicationAllocations = participant to registry
    }

    /** Exactly one enter/leave per admitted operation. Nested queue hooks reuse this use,
     * rather than incrementing lifetime or reentering a controller/composition mutex. */
    suspend fun enter(op: ComposerOperationPermit, participant: DraftJournalParticipant): DraftJournalUse {
        val member = member(participant)
        composition.requireComposerPermit(op, member.borrower)
        currentCoroutineContext().ensureActive()
        requireMember(member)
        if (uses.any { it.permit === op }) mealFail(FailureReason.CONFLICT)
        claim()
        started = true
        return DraftJournalUse().also { uses += Use(it, member, op) }
    }

    /** Lifetime decrement only, even after cancellation/close. It does not restore admission. */
    fun leave(use: DraftJournalUse) {
        val held = uses.singleOrNull { it.token === use } ?: mealFail(FailureReason.STALE_SESSION)
        uses.remove(held)
        findWitness()?.successors?.removeAll { !it.attempted && held.prepared.any { change -> change === it.mutation } }
        held.prepared.clear()
        held.entries.clear(); held.observed.clear()
        held.retirement = null
        drain()
    }

    /** Detach this participant only; all admitted uses must drain before the claim is released. */
    fun release(participant: DraftJournalParticipant) {
        val held = members.singleOrNull { it.token === participant } ?: mealFail(FailureReason.STALE_SESSION)
        held.attached = false
        if (publicationObserver?.first === participant) publicationObserver = null
        if (publicationAllocations?.first === participant) publicationAllocations = null
        drain()
    }

    /** Missing rows use the explicitly configured consumer. Existing schema1 is NEVER upgraded
     * by reading; old factories fail closed on schema2, without any write or migration. */
    suspend fun readDrafts(use: DraftJournalUse): PostDraftJournalEntry {
        val held = requireLegacy(use)
        val stored = readActual(use, publication = false)
        return when (stored?.schemaVersion) {
            null -> if (currentCodec == null) PostDraftJournalEntry.Legacy(PostEntry(null, PostRecord(now())))
                else PostDraftJournalEntry.Current(currentEntry(held, stored, PostDraftV2Record(now())))
            1 -> {
                val value = codec.decode(stored.payload)
                if (now() < value.clock) mealFail(FailureReason.CONFLICT)
                value.command?.let { if (!MobileRequestValidator().accepts(it.call(), PrincipalClass.ACCOUNT)) mealFail(FailureReason.INVALID_DATA) }
                PostDraftJournalEntry.Legacy(PostEntry(stored, value))
            }
            2 -> PostDraftJournalEntry.Current(currentEntry(held, stored, requireCurrentCodec().decode(2, stored.payload)))
            else -> mealFail(FailureReason.INVALID_DATA)
        }
    }

    /** Pure complete conversion sizing. It does not register a mutation or authorize consent.
     * Every historical original/marker must be reconciled in its unchanged legacy consumer. */
    fun preflightLegacyUpgradeData(value: PostRecord) { requireCurrentCodec().encode(legacyUpgradeValue(value)) }
    private fun legacyUpgradeValue(value: PostRecord): PostDraftV2Record {
        if (value.command != null || value.completion != null || value.localPending != null) mealFail(FailureReason.CONFLICT)
        codec.encode(value) // Strict unchanged v1 validation, including all capacity/ID rules.
        val snapshots = snapshotCodec ?: mealFail(FailureReason.NOT_CONFIGURED)
        val next = PostDraftV2Record(value.clock, locals = value.locals.map { local ->
            snapshots.create(local.id, local.revision, DraftLocalContentV1.TextV1(local.caption, local.alt),
                local.server?.let { DraftServerAssociationV1.Observed(it, local.etag ?: mealFail(FailureReason.INVALID_DATA)) }
                    ?: DraftServerAssociationV1.NotObserved)
        }, issued = value.issued, terminals = value.tombstones.map { PostDraftTerminalV2.LegacyDiscard(it) })
        return reserved(next)
    }
    /** Only the actual draft participant may register this explicit, quiescent transition.
     * The owner re-reads/decodes the actual row; caller candidate bytes are never permission. */
    suspend fun prepareLegacyUpgrade(use: DraftJournalUse, entry: PostEntry): StoreMutation.Put {
        val held = requireLegacy(use)
        val actual = readLegacy(use)
        if (!postSame(actual.record, entry.record) || actual.record == null || actual.record.schemaVersion != 1)
            mealFail(FailureReason.CONFLICT)
        if (actual.record.revision == Long.MAX_VALUE) mealFail(FailureReason.UNAVAILABLE)
        if (actual.value.locals.any { ComposerAllocationArbiter.hasPublication(access, boundary, it.id) ||
                ComposerAllocationArbiter.hasReviewedSave(access, boundary, it.id) }) mealFail(FailureReason.CONFLICT)
        val next = legacyUpgradeValue(actual.value)
        return StoreMutation.Put(key, actual.record.revision, 2, requireCurrentCodec().encode(next)).also {
            held.prepared += it
        }
    }

    suspend fun readCurrent(use: DraftJournalUse): PostDraftV2Entry {
        val held = requireLegacy(use)
        requireCurrentCodec()
        val stored = readActual(use, publication = false)
        if (stored != null && stored.schemaVersion != 2) mealFail(FailureReason.NOT_CONFIGURED)
        return currentEntry(held, stored, stored?.let { requireCurrentCodec().decode(2, it.payload) } ?: PostDraftV2Record(now()))
    }

    /** Pure bounded historical command data, not a live Save ticket or prepared mutation.
     * No claim, admission, IDs, transport, persistence or acknowledgement is created here. */
    fun reviewedCommandData(original: ReviewedDraftOriginalFieldsV2, snapshot: DraftLocalSnapshotV1,
        displayedDisclosure: PublicationDisclosure?): PostDraftCommandV2.ReviewedPatch =
        requireCurrentCodec().createReviewedCommand(original, snapshot, displayedDisclosure)

    /** Pure bounded callback preflight. No claim, I/O, admission, returned mutation or proof. */
    fun preflightCurrentData(value: PostDraftV2Record) { requireCurrentCodec().encode(reserved(value)) }

    suspend fun preflightCurrent(use: DraftJournalUse, value: PostDraftV2Record) {
        requireLegacy(use); preflightCurrentData(value)
    }

    /** A new local retention transaction: same complete snapshot/logical revision/marker,
     * fresh actual store CAS revision. This neither recovers nor clears a historical ACK.
     * Only the actual current owner entry can produce its current-use mutation. */
    suspend fun prepareRestoredLocalRetention(use: DraftJournalUse, entry: PostDraftV2Entry,
        clientDraftId: String, localRevision: Long): StoreMutation.Put {
        val held = requireLegacy(use)
        if (held.entries.none { it === entry }) mealFail(FailureReason.CONFLICT)
        val local = entry.value.locals.singleOrNull { it.clientDraftId == clientDraftId }
            ?: mealFail(FailureReason.CONFLICT)
        if (local.localRevision != localRevision ||
            entry.value.localPending != PostLocalPending(clientDraftId, localRevision) ||
            entry.value.command != null || entry.value.completion != null) mealFail(FailureReason.CONFLICT)
        // refreshCurrent uses the actual observed payload, revalidating full format/shadow
        // capacity. No field, timestamp, original or marker is reserialized or removed.
        return refreshCurrent(use, entry.record ?: mealFail(FailureReason.CONFLICT))
    }

    suspend fun mutationCurrent(use: DraftJournalUse, entry: PostDraftV2Entry, value: PostDraftV2Record): StoreMutation.Put {
        val held = requireLegacy(use)
        if (held.entries.none { it === entry } || (entry.record != null && entry.record.schemaVersion != 2)) mealFail(FailureReason.CONFLICT)
        if (entry.record?.revision == Long.MAX_VALUE) mealFail(FailureReason.UNAVAILABLE)
        val next = reserved(value)
        preservePublicationMaterial(entry.value, next)
        val mutation = StoreMutation.Put(key, entry.record?.revision, 2, requireCurrentCodec().encode(next))
        held.prepared += mutation
        rememberSuccessor(entry.record, mutation)
        return mutation
    }

    suspend fun refreshCurrent(use: DraftJournalUse, observed: PrivateRecord): StoreMutation.Put {
        val held = requireLegacy(use)
        if (held.observed.none { it === observed } || observed.schemaVersion != 2) mealFail(FailureReason.CONFLICT)
        if (observed.revision == Long.MAX_VALUE) mealFail(FailureReason.UNAVAILABLE)
        // A retained allocated original can precede its durable hold. Refresh may not bypass
        // the same shadow budget enforced by edits, although its exact observed bytes stay intact.
        reserved(requireCurrentCodec().decode(2, observed.payload))
        return StoreMutation.Put(key, observed.revision, 2, observed.payload).also {
            held.prepared += it; rememberSuccessor(observed, it)
        }
    }

    fun beforeCurrentCommit(use: DraftJournalUse, mutations: List<StoreMutation>) {
        val held = requireHeldLegacy(use)
        val owned = mutations.filter { it.key == key }
        if (owned.isEmpty()) return
        val actual = owned.singleOrNull() as? StoreMutation.Put ?: mealFail(FailureReason.CONFLICT)
        if (actual.schemaVersion != 2 || held.prepared.none { it === actual }) mealFail(FailureReason.CONFLICT)
        reserved(requireCurrentCodec().decode(2, actual.payload))
        markAttempted(actual)
    }

    suspend fun commitCurrent(use: DraftJournalUse, change: StoreMutation.Put) {
        requireLegacy(use); beforeCurrentCommit(use, listOf(change))
        if (change.key != key || change.schemaVersion != 2 || change.expectedRevision == Long.MAX_VALUE) mealFail(FailureReason.CONFLICT)
        val result = mealValue(store.commit(access.lease.scope, listOf(change)))
        requireLegacy(use)
        val expected = result[change.key]
        if (result.keys != setOf(change.key) || expected == null || expected <= 0 ||
            (change.expectedRevision != null && expected != change.expectedRevision!! + 1)) mealFail(FailureReason.STORAGE_FAILURE)
        val actual = readActual(use, publication = false) ?: mealFail(FailureReason.STORAGE_FAILURE)
        if (actual.revision != expected || actual.schemaVersion != 2 ||
            !actual.payload.copyForCodec().contentEquals(change.payload.copyForCodec())) mealFail(FailureReason.STORAGE_FAILURE)
    }

    suspend fun observeCurrentApply(use: DraftJournalUse, proof: PostDraftCurrentApply): Pair<PrivateRecord, PrivateRecord> {
        requireLegacy(use)
        val archive = proof.archive ?: mealFail(FailureReason.CONFLICT)
        if (proof.mutation.key != key || proof.mutation.schemaVersion != 2 ||
            archive.key != RecordKey("feedme.command.metadata", proof.original.id)) mealFail(FailureReason.CONFLICT)
        val domain = readActual(use, publication = false) ?: mealFail(FailureReason.CONFLICT)
        val command = mealValue(store.read(access.lease.scope, archive.key)) ?: mealFail(FailureReason.CONFLICT)
        requireLegacy(use)
        if (domain.schemaVersion != 2 || domain.revision <= (proof.mutation.expectedRevision ?: 0) ||
            !domain.payload.copyForCodec().contentEquals(proof.mutation.payload.copyForCodec()) || archive.expectedRevision == null ||
            command.schemaVersion != archive.schemaVersion || command.revision != archive.expectedRevision!! + 1 ||
            !command.payload.copyForCodec().contentEquals(archive.payload.copyForCodec())) mealFail(FailureReason.CONFLICT)
        val commandAgain = mealValue(store.read(access.lease.scope, archive.key)); requireLegacy(use)
        val domainAgain = readActual(use, publication = false)
        if (!postSame(command, commandAgain) || !postSame(domain, domainAgain)) mealFail(FailureReason.CONFLICT)
        return domain to command
    }

    /** Publication reads expose detached complete pins/projections only, never the row. */
    suspend fun readForPublication(use: DraftJournalUse, clientDraftId: String, expectedLocalRevision: Long): DraftReviewPin {
        val entry = publicationEntry(use)
        strictId(clientDraftId)
        val snapshot = entry.value.locals.singleOrNull { it.clientDraftId == clientDraftId && it.localRevision == expectedLocalRevision }
            ?: mealFail(FailureReason.CONFLICT)
        val deliveredLocal = requireRootQuiescent(entry.value, clientDraftId)
        val deliveredApply = requireDeliveredApplyMarker(use, entry, clientDraftId)
        requireRootQuiescent(entry.value, clientDraftId, deliveredLocal)
        val next = pins.filterNot { it.snapshot.clientDraftId == clientDraftId }
        val candidate = Pin(DraftReviewPin(), snapshot, deliveredLocal, deliveredApply)
        if (next.size + 1 > policy.maxLocalDrafts || next.sumOf { it.snapshot.exactUtf8.copyForCodec().size.toLong() } +
            snapshot.exactUtf8.copyForCodec().size > policy.maxRecordBytes ||
            next.sumOf(::pinBytes) + pinBytes(candidate) > policy.maxRecordBytes.toLong() * 4) mealFail(FailureReason.UNAVAILABLE)
        pins.clear(); pins += next; pins += candidate
        return candidate.token
    }

    private fun pinBytes(pin: Pin): Long = pin.snapshot.exactUtf8.copyForCodec().size.toLong() +
        (pin.deliveredLocal?.mutation?.payload?.copyForCodec()?.size ?: 0) +
        (pin.deliveredApply?.apply?.mutation?.payload?.copyForCodec()?.size ?: 0) +
        (pin.deliveredApply?.archive?.payload?.copyForCodec()?.size ?: 0)

    suspend fun reviewSnapshot(use: DraftJournalUse, pin: DraftReviewPin): DraftLocalSnapshotV1 {
        requirePin(use, pin)
        return pins.single { it.token === pin }.snapshot
    }

    suspend fun requirePin(use: DraftJournalUse, pin: DraftReviewPin) {
        requirePublication(use)
        val held = pins.singleOrNull { it.token === pin } ?: mealFail(FailureReason.CONFLICT)
        val entry = publicationEntry(use)
        requireRootQuiescent(entry.value, held.snapshot.clientDraftId, held.deliveredLocal)
        requireDeliveredApplyMarker(use, entry, held.snapshot.clientDraftId, held.deliveredApply)
        requireRootQuiescent(entry.value, held.snapshot.clientDraftId, held.deliveredLocal)
        val actual = entry.value.locals.singleOrNull { it.clientDraftId == held.snapshot.clientDraftId } ?: mealFail(FailureReason.CONFLICT)
        if (!snapshotCodec!!.encode(actual).copyForCodec().contentEquals(held.snapshot.exactUtf8.copyForCodec())) mealFail(FailureReason.CONFLICT)
    }

    suspend fun prepareHold(use: DraftJournalUse, pin: DraftReviewPin, link: PublicationOriginalLinkV1,
        publicationReservedBytes: Long): DraftMutationContribution {
        val proposed = proposedHold(use, pin, link, publicationReservedBytes)
        return contribution(use, proposed.entry, proposed.value, link.commandId, ContributionKind.HOLD, proposed.deliveredLocal, proposed.deliveredApply)
    }

    /** Fixed-width unused probe IDs belong only to the caller's pure proposed data. No ID,
     * contribution, notification or durable state is retained by this preflight. */
    suspend fun preflightHold(use: DraftJournalUse, pin: DraftReviewPin, probeLink: PublicationOriginalLinkV1,
        publicationReservedBytes: Long): DraftPublicationProjectionV2 =
        requireCurrentCodec().publicationProjection(proposedHold(use, pin, probeLink, publicationReservedBytes).value)

    /** Recovery of a genuinely allocated but not observed-enqueued original. A fresh pin may
     * include newer DURABLE work; the original historical snapshot/key/body/branch never changes.
     * Only the actual registered allocator supplies its exact link. No raw link is accepted. */
    suspend fun preflightOriginalRegistration(use: DraftJournalUse, currentPin: DraftReviewPin,
        allocation: PublicationAllocatedOriginal, publicationReservedBytes: Long): DraftPublicationProjectionV2 {
        val (_, proposed) = proposedOriginalRegistration(use, currentPin, allocation, publicationReservedBytes)
        return requireCurrentCodec().publicationProjection(proposed.value)
    }

    suspend fun prepareOriginalRegistration(use: DraftJournalUse, currentPin: DraftReviewPin,
        allocation: PublicationAllocatedOriginal, publicationReservedBytes: Long): DraftMutationContribution {
        val (original, proposed) = proposedOriginalRegistration(use, currentPin, allocation, publicationReservedBytes)
        return contribution(use, proposed.entry, proposed.value, original.commandId, ContributionKind.HOLD, proposed.deliveredLocal, proposed.deliveredApply)
    }

    suspend fun prepareTerminal(use: DraftJournalUse, link: PublicationOriginalLinkV1, actualPost: PostPublicationReceipt): DraftMutationContribution {
        val entry = publicationEntry(use)
        requireExactHold(entry.value, link)
        requireNoUndeliveredLocal(link.clientDraftId)
        val deliveredLocal = requireDeliveredLocalMarker(entry.value, link.clientDraftId)
        val deliveredApply = requireDeliveredApplyMarker(use, entry, link.clientDraftId)
        // Canonical comparison DATA only. Coordinator independently supplies actual queue
        // receipt/archive provenance and validates the next actual publication journal jointly.
        val post = actualPost.document
        if (actualPost.etag != "\"1\"" || post.encodeUtf8().size > configuredPublicationPolicy!!.maxResponseBytes ||
            CanonicalBodyValidator.bundled().validateSchema("Post", post.encodeUtf8()) != ContractValidationResult.Valid ||
            postString(post, "status") != "published") mealFail(FailureReason.INVALID_DATA)
        val postId = strictId(postString(post, "id"))
        requirePostComparison(link, actualPost)
        val current = entry.value.locals.single { it.clientDraftId == link.clientDraftId }
        (current.serverAssociation as? DraftServerAssociationV1.Observed)?.exactPostDraft?.let {
            if (postString(it, "status") == "published" && postString(it, "publishedPostId") != postId) mealFail(FailureReason.CONFLICT)
        }
        val remainder = if (current.localRevision > link.reviewedLocalRevision) PostDraftRemainderV2(link,
            snapshotCodec!!.create(current.clientDraftId, current.localRevision, current.content, DraftServerAssociationV1.NotObserved)) else null
        val next = entry.value.copy(clock = now(), locals = entry.value.locals.filterNot { it.clientDraftId == link.clientDraftId },
            publicationHold = null, terminals = entry.value.terminals + PostDraftTerminalV2.Published(link, postId),
            remainders = entry.value.remainders + listOfNotNull(remainder),
            localPending = entry.value.localPending?.takeUnless { it.clientId == link.clientDraftId && deliveredLocal != null },
            completion = entry.value.completion?.takeUnless { it.clientId == link.clientDraftId && deliveredApply != null })
        val encoded = requireCurrentCodec().encode(next)
        if (encoded.copyForCodec().size.toLong() > entry.value.publicationHold!!.reservation.draftFinalizationReservedBytes)
            mealFail(FailureReason.CONFLICT)
        publicationObserver?.second?.invoke(link.clientDraftId)
        requirePublication(use)
        requireNoUndeliveredLocal(link.clientDraftId)
        return contribution(use, entry, next, link.commandId, ContributionKind.TERMINAL, deliveredLocal, deliveredApply)
    }

    suspend fun prepareUnsentRelease(use: DraftJournalUse, link: PublicationOriginalLinkV1): DraftMutationContribution {
        val entry = publicationEntry(use)
        requireExactHold(entry.value, link)
        // Root stays open; original ID stays permanently issued. Zero-attempt archive proof is
        // enforced by the actual queue/coordinator, not invented from this historical value.
        return contribution(use, entry, entry.value.copy(clock = now(), publicationHold = null), link.commandId, ContributionKind.UNSENT)
    }

    suspend fun requirePublicationState(use: DraftJournalUse): DraftPublicationProjectionV2 =
        requireCurrentCodec().publicationProjection(publicationEntry(use).value)

    /** Never-attempted dispatch only. A fresh explicit original-retry review may pin newer
     * DURABLE local work separately from the unchanged original; old review tickets may not.
     * Coordinator must freshly show/pin both and recheck actual current target authority.
     * Attempted replay uses separate admission, not new-publication eligibility. */
    suspend fun requirePublicationNewDispatch(use: DraftJournalUse, link: PublicationOriginalLinkV1,
        reviewedCurrentSnapshot: DraftLocalSnapshotV1) {
        val entry = publicationEntry(use)
        requireExactHold(entry.value, link)
        val current = entry.value.locals.single { it.clientDraftId == link.clientDraftId }
        if (!snapshotCodec!!.encode(reviewedCurrentSnapshot).copyForCodec().contentEquals(current.exactUtf8.copyForCodec()))
            mealFail(FailureReason.CONFLICT)
        requireDeliveredLocalMarker(entry.value, link.clientDraftId)
        requireDeliveredApplyMarker(use, entry, link.clientDraftId)
        requireNewTargetEligibility(link, current)
        requireNoUndeliveredLocal(link.clientDraftId)
    }

    suspend fun requireContribution(use: DraftJournalUse, contribution: DraftMutationContribution) {
        requirePublication(use); liveContribution(use, contribution)
    }

    fun mutations(use: DraftJournalUse, contribution: DraftMutationContribution): List<StoreMutation> =
        listOf(liveContribution(use, contribution).mutation)

    fun publicationProjection(use: DraftJournalUse, contribution: DraftMutationContribution): DraftPublicationProjectionV2 =
        requireCurrentCodec().publicationProjection(liveContribution(use, contribution).value)

    fun beforePublicationCommit(use: DraftJournalUse, contribution: DraftMutationContribution, actualBatch: List<StoreMutation>) {
        val actual = liveContribution(use, contribution)
        val owned = actualBatch.filter { it.key == key }
        if (owned.size != 1 || owned.single() !== actual.mutation) mealFail(FailureReason.CONFLICT)
        if (actual.kind in setOf(ContributionKind.HOLD, ContributionKind.TERMINAL)) {
            requireNoUndeliveredLocal(actual.rootId)
            requireDeliveredLocalMarker(requireCurrentCodec().decode(2, actual.before.payload), actual.rootId, actual.deliveredLocal)
            requireDeliveredApplyNow(requireCurrentCodec().decode(2, actual.before.payload), actual.rootId, actual.deliveredApply)
        }
        actual.attempted = true
        actual.readbackAttempted = true
        markAttempted(actual.mutation)
    }

    /** Known acknowledgement map followed by actual exact stable readback. It remains only
     * one component of the coordinator's archive/journal/final caller-delivery evidence. */
    suspend fun verifyPublicationReadback(use: DraftJournalUse, contribution: DraftMutationContribution,
        acknowledgements: Map<RecordKey, Long?>) {
        requirePublication(use)
        val actual = liveContribution(use, contribution)
        val revision = acknowledgements[key] ?: mealFail(FailureReason.STORAGE_FAILURE)
        if (!actual.attempted || revision != (actual.mutation.expectedRevision ?: 0) + 1) mealFail(FailureReason.STORAGE_FAILURE)
        val observed = readActual(use, publication = true) ?: mealFail(FailureReason.STORAGE_FAILURE)
        if (observed.revision != revision || !samePayload(observed, actual.mutation)) mealFail(FailureReason.STORAGE_FAILURE)
        val again = readActual(use, publication = true)
        if (!postSame(observed, again)) mealFail(FailureReason.CONFLICT)
    }

    /** Old contribution cannot write in this operation. Only registered same-lease actual
     * full-row observation and preserved owner-successor evidence may yield a new readback. */
    suspend fun observePublicationReadback(use: DraftJournalUse, contribution: DraftMutationContribution): DraftPublicationReadback {
        requirePublication(use)
        val witness = retainedContribution(contribution)
        val current = readActual(use, publication = true) ?: mealFail(FailureReason.CONFLICT)
        if (!witness.readbackAttempted || !postSame(witness.observed, current)) mealFail(FailureReason.CONFLICT)
        val again = readActual(use, publication = true)
        if (!postSame(current, again) || !postSame(witness.observed, current)) mealFail(FailureReason.CONFLICT)
        val registry = witnessHolder()
        registry.readbacks.removeAll { it.contribution === witness }
        return DraftPublicationReadback().also { registry.readbacks += Readback(it, witness, current) }
    }

    suspend fun preparePublicationFinalization(use: DraftJournalUse, readback: DraftPublicationReadback): DraftMutationContribution {
        val held = requirePublication(use)
        val ticket = findWitness()?.readbacks?.singleOrNull { it.token === readback } ?: mealFail(FailureReason.CONFLICT)
        val entry = publicationEntry(use)
        if (entry.record == null || !postSame(ticket.contribution.observed, entry.record) ||
            ticket.contribution !in witnessHolder().contributions) mealFail(FailureReason.CONFLICT)
        val result = contribution(use, entry, entry.value, ticket.contribution.commandId, ContributionKind.FINALIZE)
        if (requireHeldPublication(use) !== held) mealFail(FailureReason.STALE_SESSION)
        return result
    }

    /** Coordinator calls only AFTER its actual caller-tail delivery. Preparation performs all
     * suspending admission checks, but never removes memory or manufactures delivery proof. */
    suspend fun preparePublicationEvidenceRetirement(use: DraftJournalUse,
        contribution: DraftMutationContribution): DraftPublicationEvidenceRetirement {
        val held = requirePublication(use)
        val actual = retainedContribution(contribution)
        val holder = findWitness() ?: mealFail(FailureReason.CONFLICT)
        if (actual !in holder.contributions) mealFail(FailureReason.CONFLICT)
        return DraftPublicationEvidenceRetirement().also { held.retirement = Retirement(it, actual) }
    }

    /** Same serialized, no-await final turn as the coordinator's other memory retirements.
     * The registered current-use ticket grants no disk mutation, ACK or lifecycle transition. */
    fun retirePublicationEvidence(use: DraftJournalUse, ticket: DraftPublicationEvidenceRetirement) {
        val held = requireHeldPublication(use)
        val actual = held.retirement?.takeIf { it.token === ticket }?.contribution ?: mealFail(FailureReason.CONFLICT)
        val holder = findWitness() ?: mealFail(FailureReason.CONFLICT)
        if (actual !in holder.contributions) mealFail(FailureReason.CONFLICT)
        held.retirement = null
        holder.contributions.removeAll { it.commandId == actual.commandId }
        holder.readbacks.removeAll { it.contribution.commandId == actual.commandId }
        if (holder.contributions.isEmpty()) clearWitness(holder)
    }

    suspend fun readLegacy(use: DraftJournalUse): PostEntry {
        requireLegacy(use)
        val stored = mealValue(store.read(access.lease.scope, key))
        requireLegacy(use)
        if (stored != null && stored.schemaVersion != 1) mealFail(FailureReason.INVALID_DATA)
        val value = stored?.let { codec.decode(it.payload) } ?: PostRecord(now())
        if (now() < value.clock) mealFail(FailureReason.CONFLICT)
        value.command?.let {
            if (!MobileRequestValidator().accepts(it.call(), PrincipalClass.ACCOUNT)) mealFail(FailureReason.INVALID_DATA)
        }
        return PostEntry(stored, value)
    }

    suspend fun preflightLegacy(use: DraftJournalUse, value: PostRecord) {
        requireLegacy(use); codec.encode(value)
    }

    suspend fun schemaLegacy(use: DraftJournalUse, name: String, document: WireDocument) {
        requireLegacy(use); codec.schema(name, document)
    }

    suspend fun mutationLegacy(use: DraftJournalUse, entry: PostEntry, value: PostRecord): StoreMutation.Put {
        val held = requireLegacy(use)
        return StoreMutation.Put(key, entry.record?.revision, 1, codec.encode(value)).also { held.prepared += it }
    }

    /** Same-lease finalization writes the exact observed legacy bytes with a fresh CAS. */
    suspend fun refreshLegacy(use: DraftJournalUse, observed: PrivateRecord): StoreMutation.Put {
        val held = requireLegacy(use)
        if (observed.schemaVersion != 1) mealFail(FailureReason.INVALID_DATA)
        codec.decode(observed.payload)
        return StoreMutation.Put(key, observed.revision, 1, observed.payload).also { held.prepared += it }
    }

    /** Called only from this participant's existing composition beforeCommit hook after the
     * composition has checked the current coroutine operation. No suspension or raw I/O here. */
    fun beforeLegacyCommit(use: DraftJournalUse, mutations: List<StoreMutation>) {
        val held = requireHeldLegacy(use)
        val owned = mutations.filter { it.key == key }
        if (owned.isEmpty()) return
        val actual = owned.singleOrNull() ?: mealFail(FailureReason.CONFLICT)
        if (actual !is StoreMutation.Put || actual.schemaVersion != 1 || held.prepared.none { it === actual }) mealFail(FailureReason.CONFLICT)
    }

    fun ownsLegacyKey(candidate: RecordKey) = candidate == key

    suspend fun commitLegacy(use: DraftJournalUse, change: StoreMutation.Put) {
        requireLegacy(use); beforeLegacyCommit(use, listOf(change))
        if (change.key != key || change.schemaVersion != 1) mealFail(FailureReason.CONFLICT)
        if (change.expectedRevision == Long.MAX_VALUE) mealFail(FailureReason.UNAVAILABLE)
        val result = mealValue(store.commit(access.lease.scope, listOf(change)))
        requireLegacy(use)
        if (result.keys != setOf(change.key)) mealFail(FailureReason.STORAGE_FAILURE)
        val revision = result[change.key] ?: mealFail(FailureReason.STORAGE_FAILURE)
        if (revision <= 0 || (change.expectedRevision != null && revision != change.expectedRevision!! + 1))
            mealFail(FailureReason.STORAGE_FAILURE)
        val actual = mealValue(store.read(access.lease.scope, change.key)) ?: mealFail(FailureReason.STORAGE_FAILURE)
        requireLegacy(use)
        if (actual.revision != revision || actual.schemaVersion != 1 ||
            !actual.payload.copyForCodec().contentEquals(change.payload.copyForCodec())) mealFail(FailureReason.STORAGE_FAILURE)
    }

    /** Preserve the existing exact domain/archive byte/revision checks. Neither a schema
     * projection nor queue APPLIED metadata can manufacture a held application proof. */
    suspend fun observeLegacyApply(use: DraftJournalUse, proof: PostApply): Pair<PrivateRecord, PrivateRecord> {
        requireLegacy(use)
        val archive = proof.archive ?: mealFail(FailureReason.CONFLICT)
        if (proof.mutation.key != key || archive.key != RecordKey("feedme.command.metadata", proof.original.id))
            mealFail(FailureReason.CONFLICT)
        val domain = mealValue(store.read(access.lease.scope, key)) ?: mealFail(FailureReason.CONFLICT)
        requireLegacy(use)
        val command = mealValue(store.read(access.lease.scope, archive.key)) ?: mealFail(FailureReason.CONFLICT)
        requireLegacy(use)
        if (domain.schemaVersion != 1 || domain.revision <= (proof.mutation.expectedRevision ?: 0) ||
            !domain.payload.copyForCodec().contentEquals(proof.mutation.payload.copyForCodec()) ||
            command.schemaVersion != archive.schemaVersion || command.revision != archive.expectedRevision!! + 1 ||
            !command.payload.copyForCodec().contentEquals(archive.payload.copyForCodec())) mealFail(FailureReason.CONFLICT)
        val commandAgain = mealValue(store.read(access.lease.scope, archive.key))
        requireLegacy(use)
        val domainAgain = mealValue(store.read(access.lease.scope, key))
        requireLegacy(use)
        if (!postSame(command, commandAgain) || !postSame(domain, domainAgain)) mealFail(FailureReason.CONFLICT)
        return domain to command
    }

    private fun requireCurrentCodec() = currentCodec ?: mealFail(FailureReason.NOT_CONFIGURED)
    private fun reserved(value: PostDraftV2Record): PostDraftV2Record {
        val actual = value.publicationHold?.let {
            requireCurrentCodec().withPublicationReservation(value, it.reservation.publicationReservedBytes)
        } ?: value.also { requireCurrentCodec() }
        requireAllocatedShadowCapacity(actual)
        return actual
    }

    /** Pure exact-session sizing only. The concrete allocator's probe/returned original is
     * NOT a live review, a registered owner contribution or a new persisted hold. The full
     * codec recomputes counts and worst-case terminal/full-newer-remainder bytes after EVERY
     * current edit/observation/history change, including the actual beforeCommit tail.
     * This prevents unrelated edits consuming a retained original's future registration space
     * without banning those edits. Unknown/awaiting allocations stay bounded until resolved. */
    private fun requireAllocatedShadowCapacity(value: PostDraftV2Record) {
        val candidate = PublicationAllocationRegistry.capacityCandidate(access, boundary) ?: return
        val original = candidate.original
        value.publicationHold?.let { hold ->
            linkCodec!!.requireSameExactLink(hold.link, original)
            if (hold.reservation.publicationReservedBytes != candidate.publicationReservedBytes) mealFail(FailureReason.CONFLICT)
            return // The actual hold already reserves this exact original; never double count.
        }
        value.terminals.filterIsInstance<PostDraftTerminalV2.Published>().singleOrNull { it.commandId == original.commandId }?.let {
            linkCodec!!.requireSameExactLink(it.link, original)
            return // Full terminal/remainder is already real data, not an invented old ACK.
        }
        if (original.commandId in value.issued || value.command?.clientId == original.clientDraftId) mealFail(FailureReason.CONFLICT)
        val shadow = value.copy(issued = value.issued + original.commandId,
            publicationHold = PostDraftPublicationHoldV2(original, PublicationCapacityReservationV1(configuredPublicationPolicy!!.maxResponseBytes,
                candidate.publicationReservedBytes, 0, 1)),
            // Only the hypothetical hold's shape; no actual completion is cleared here and
            // genuine delivered evidence remains mandatory before any real contribution.
            completion = value.completion?.takeUnless { it.clientId == original.clientDraftId })
        requireCurrentCodec().withPublicationReservation(shadow, candidate.publicationReservedBytes)
    }

    private suspend fun readActual(use: DraftJournalUse, publication: Boolean): PrivateRecord? {
        val held = if (publication) requirePublication(use) else requireLegacy(use)
        val actual = mealValue(store.read(access.lease.scope, key))
        if ((if (publication) requirePublication(use) else requireLegacy(use)) !== held) mealFail(FailureReason.STALE_SESSION)
        if (actual?.schemaVersion == 2) {
            val value = requireCurrentCodec().decode(2, actual.payload)
            if (value.clock > now()) mealFail(FailureReason.CONFLICT)
            rememberObservation(actual)
            held.observed += actual
        }
        return actual
    }
    private fun currentEntry(held: Use, record: PrivateRecord?, value: PostDraftV2Record): PostDraftV2Entry {
        if (value.clock > now()) mealFail(FailureReason.CONFLICT)
        return PostDraftV2Entry(record, value).also { held.entries += it }
    }
    private suspend fun publicationEntry(use: DraftJournalUse): PostDraftV2Entry {
        val held = requirePublication(use)
        requireCurrentCodec()
        val stored = readActual(use, publication = true)
        if (stored != null && stored.schemaVersion != 2) mealFail(FailureReason.NOT_CONFIGURED)
        return currentEntry(held, stored, stored?.let { requireCurrentCodec().decode(2, it.payload) } ?: PostDraftV2Record(now()))
    }
    private fun requireHeldPublication(use: DraftJournalUse): Use = requireHeld(use).also {
        if (it.member.borrower.feature != MealKitchenFeature.POST_PUBLICATIONS) mealFail(FailureReason.NOT_CONFIGURED)
    }
    private suspend fun requirePublication(use: DraftJournalUse): Use {
        val held = requireHeldPublication(use)
        composition.requireComposerPermit(held.permit, held.member.borrower)
        currentCoroutineContext().ensureActive()
        if (requireHeldPublication(use) !== held) mealFail(FailureReason.STALE_SESSION)
        requireCurrentCodec()
        return held
    }
    private fun requireNoUndeliveredLocal(root: String) {
        // Exclusion data only, never admission: a still-owned reviewed Save allocation may
        // have no durable command yet. Its same-root reservation must settle before review,
        // first publication dispatch or terminalization; unrelated roots remain independent.
        if (ComposerAllocationArbiter.hasReviewedSave(access, boundary, root)) mealFail(FailureReason.CONFLICT)
        PostDraftHeld.edit(access, boundary)?.let { if (it.proposed.id == root && it.delivery?.delivered(it) != true) mealFail(FailureReason.CONFLICT) }
        PostDraftHeld.apply(access, boundary)?.let { if (it.original.clientId == root && it.delivery?.delivered(it) != true) mealFail(FailureReason.CONFLICT) }
        PostDraftCurrentHeld.edit(access, boundary)?.let { if (it.proposed.clientDraftId == root && it.delivery?.delivered(it) != true) mealFail(FailureReason.CONFLICT) }
        PostDraftCurrentHeld.apply(access, boundary)?.let { if (it.original.clientId == root && it.delivery?.delivered(it) != true) mealFail(FailureReason.CONFLICT) }
    }
    /** A disk marker is never delivery proof. Only this owner's captured actual same-lease
     * edit/ticket/mutation, or the current exact registry entry, may explain it. An unrelated
     * staged edit can replace the registry slot without erasing a pin's genuine old evidence. */
    private fun requireDeliveredLocalMarker(value: PostDraftV2Record, root: String,
        retained: DeliveredLocalMarker? = null): DeliveredLocalMarker? {
        val pending = value.localPending?.takeIf { it.clientId == root } ?: return null
        requireNoUndeliveredLocal(root)
        val registered = PostDraftCurrentHeld.edit(access, boundary)
        val actual = retained ?: registered?.let { edit ->
            val mutation = edit.mutation ?: mealFail(FailureReason.CONFLICT)
            val delivery = edit.delivery ?: mealFail(FailureReason.CONFLICT)
            DeliveredLocalMarker(edit, mutation, delivery)
        } ?: mealFail(FailureReason.CONFLICT)
        val edit = actual.edit
        if (registered?.proposed?.clientDraftId == root && registered !== edit) mealFail(FailureReason.CONFLICT)
        if (edit.mutation !== actual.mutation || edit.delivery !== actual.delivery || !actual.delivery.delivered(edit) ||
            edit.proposed.clientDraftId != pending.clientId || edit.proposed.localRevision != pending.revision ||
            actual.mutation.key != key || actual.mutation.schemaVersion != 2) mealFail(FailureReason.CONFLICT)
        val current = value.locals.singleOrNull { it.clientDraftId == root } ?: mealFail(FailureReason.CONFLICT)
        if (!current.exactUtf8.copyForCodec().contentEquals(edit.proposed.exactUtf8.copyForCodec())) mealFail(FailureReason.CONFLICT)
        val written = requireCurrentCodec().decode(2, actual.mutation.payload)
        if (written.localPending != pending || written.locals.singleOrNull { it.clientDraftId == root }?.exactUtf8
                ?.copyForCodec()?.contentEquals(edit.proposed.exactUtf8.copyForCodec()) != true) mealFail(FailureReason.CONFLICT)
        return actual
    }
    private fun requireRootQuiescent(value: PostDraftV2Record, root: String,
        retained: DeliveredLocalMarker? = null): DeliveredLocalMarker? {
        if (value.publicationHold?.link?.clientDraftId == root || value.command?.clientId == root) mealFail(FailureReason.CONFLICT)
        requireNoUndeliveredLocal(root)
        return requireDeliveredLocalMarker(value, root, retained)
    }

    /** A known caller-delivered apply is still checked against its ACTUAL retained domain and
     * archive before admitting read-only review. APPLIED, a GET or a decoded completion alone
     * cannot explain a marker. No mutation or held-proof retirement occurs here. */
    private suspend fun requireDeliveredApplyMarker(use: DraftJournalUse, entry: PostDraftV2Entry, root: String,
        retained: DeliveredApplyMarker? = null): DeliveredApplyMarker? {
        val completion = entry.value.completion?.takeIf { it.clientId == root } ?: return null
        requirePublication(use)
        val actual = retained ?: PostDraftCurrentHeld.apply(access, boundary)?.let { proof ->
            DeliveredApplyMarker(proof, proof.archive ?: mealFail(FailureReason.CONFLICT),
                proof.delivery ?: mealFail(FailureReason.CONFLICT))
        } ?: mealFail(FailureReason.CONFLICT)
        requireDeliveredApplyNow(entry.value, root, actual)
        val proof = actual.apply
        val domain = entry.record ?: mealFail(FailureReason.CONFLICT)
        if (domain.schemaVersion != 2 || domain.revision <= (proof.mutation.expectedRevision ?: 0) ||
            !samePayload(domain, proof.mutation)) mealFail(FailureReason.CONFLICT)
        val archive = mealValue(store.read(access.lease.scope, actual.archive.key)) ?: mealFail(FailureReason.CONFLICT)
        requirePublication(use)
        if (actual.archive.expectedRevision == null || archive.schemaVersion != actual.archive.schemaVersion ||
            archive.revision != actual.archive.expectedRevision!! + 1 || !samePayload(archive, actual.archive)) mealFail(FailureReason.CONFLICT)
        val command = mealValue(composition.kitchen.commands.command(access.lease, proof.original.id)) ?: mealFail(FailureReason.CONFLICT)
        requirePublication(use)
        if (command.commandId != completion.commandId || command.operationId != completion.operation ||
            command.localRevision != archive.revision || command.phase != (if (completion.unsent) CommandPhase.DISCARDED else CommandPhase.APPLIED))
            mealFail(FailureReason.CONFLICT)
        val repeatedArchive = mealValue(store.read(access.lease.scope, actual.archive.key)); requirePublication(use)
        val repeatedDomain = readActual(use, publication = true)
        if (!postSame(archive, repeatedArchive) || !postSame(domain, repeatedDomain)) mealFail(FailureReason.CONFLICT)
        requireDeliveredApplyNow(entry.value, root, actual)
        return actual
    }

    /** Synchronous proof-identity recheck for the actual beforeCommit hook; never nested queue
     * I/O while a command batch holds its mutex. The prepared domain CAS fences row changes. */
    private fun requireDeliveredApplyNow(value: PostDraftV2Record, root: String, retained: DeliveredApplyMarker?) {
        val completion = value.completion?.takeIf { it.clientId == root } ?: return
        val actual = retained ?: mealFail(FailureReason.CONFLICT)
        val proof = actual.apply
        val registered = PostDraftCurrentHeld.apply(access, boundary)
        if (registered?.original?.clientId == root && registered !== proof) mealFail(FailureReason.CONFLICT)
        requireNoUndeliveredLocal(root)
        if (proof.archive !== actual.archive || proof.delivery !== actual.delivery || !actual.delivery.delivered(proof) ||
            proof.mutation.key != key || proof.mutation.schemaVersion != 2 ||
            actual.archive.key != RecordKey("feedme.command.metadata", proof.original.id) ||
            actual.archive.expectedRevision != proof.receiptRevision || completion.commandId != proof.original.id ||
            completion.clientId != proof.original.clientId || completion.operation != proof.original.operation || completion.unsent != proof.unsent)
            mealFail(FailureReason.CONFLICT)
        val written = requireCurrentCodec().decode(2, proof.mutation.payload)
        // Exact domain payload is rechecked by the suspending admission. This comparison also
        // fixes the command discriminator to the actual proof before any prepared mutation.
        if (written.completion?.commandId != completion.commandId || written.completion?.clientId != root ||
            written.completion?.operation != completion.operation || written.completion?.unsent != completion.unsent ||
            (completion is PostDraftCompletionV2.Legacy) != (proof.original !is PostDraftCommandV2.ReviewedPatch))
            mealFail(FailureReason.CONFLICT)
    }
    private fun requireExactHold(value: PostDraftV2Record, link: PublicationOriginalLinkV1) {
        val held = value.publicationHold ?: mealFail(FailureReason.CONFLICT)
        linkCodec!!.requireSameExactLink(held.link, link)
    }
    private suspend fun proposedHold(use: DraftJournalUse, pin: DraftReviewPin, link: PublicationOriginalLinkV1,
        publicationReservedBytes: Long): HoldProposal {
        requirePin(use, pin)
        val entry = publicationEntry(use)
        val held = pins.singleOrNull { it.token === pin } ?: mealFail(FailureReason.CONFLICT)
        val snapshot = held.snapshot
        val deliveredLocal = requireRootQuiescent(entry.value, snapshot.clientDraftId, held.deliveredLocal)
        val deliveredApply = requireDeliveredApplyMarker(use, entry, snapshot.clientDraftId, held.deliveredApply)
        requireRootQuiescent(entry.value, snapshot.clientDraftId, deliveredLocal)
        val actual = entry.value.locals.singleOrNull { it.clientDraftId == snapshot.clientDraftId } ?: mealFail(FailureReason.CONFLICT)
        if (entry.value.publicationHold != null || link.commandId in entry.value.issued ||
            !snapshot.exactUtf8.copyForCodec().contentEquals(actual.exactUtf8.copyForCodec()) ||
            !snapshot.exactUtf8.copyForCodec().contentEquals(link.historicalReview.exactReviewedLocalSnapshot.exactUtf8.copyForCodec())) mealFail(FailureReason.CONFLICT)
        val next = requireCurrentCodec().withPublicationReservation(entry.value.copy(clock = now(), issued = entry.value.issued + link.commandId,
            publicationHold = PostDraftPublicationHoldV2(link, PublicationCapacityReservationV1(configuredPublicationPolicy!!.maxResponseBytes,
                publicationReservedBytes, 0, 1)),
            localPending = entry.value.localPending?.takeUnless { it.clientId == snapshot.clientDraftId && deliveredLocal != null },
            completion = entry.value.completion?.takeUnless { it.clientId == snapshot.clientDraftId && deliveredApply != null }), publicationReservedBytes)
        return HoldProposal(entry, next, deliveredLocal, deliveredApply)
    }

    private suspend fun proposedOriginalRegistration(use: DraftJournalUse, pin: DraftReviewPin,
        allocation: PublicationAllocatedOriginal, publicationReservedBytes: Long): Pair<PublicationOriginalLinkV1, HoldProposal> {
        val admitted = requirePublication(use)
        val registration = publicationAllocations?.takeIf { it.first === admitted.member.token }
            ?: mealFail(FailureReason.NOT_CONFIGURED)
        val original = registration.second.requireOriginal(admitted.permit, this, admitted.member.borrower, allocation)
        if (requirePublication(use) !== admitted || publicationAllocations !== registration) mealFail(FailureReason.STALE_SESSION)
        requirePin(use, pin)
        val entry = publicationEntry(use)
        val held = pins.singleOrNull { it.token === pin } ?: mealFail(FailureReason.CONFLICT)
        val snapshot = held.snapshot
        val deliveredLocal = requireRootQuiescent(entry.value, snapshot.clientDraftId, held.deliveredLocal)
        val deliveredApply = requireDeliveredApplyMarker(use, entry, snapshot.clientDraftId, held.deliveredApply)
        requireRootQuiescent(entry.value, snapshot.clientDraftId, deliveredLocal)
        val actual = entry.value.locals.singleOrNull { it.clientDraftId == snapshot.clientDraftId } ?: mealFail(FailureReason.CONFLICT)
        if (snapshot.clientDraftId != original.clientDraftId || snapshot.localRevision < original.reviewedLocalRevision ||
            entry.value.publicationHold != null || original.commandId in entry.value.issued ||
            !snapshot.exactUtf8.copyForCodec().contentEquals(actual.exactUtf8.copyForCodec())) mealFail(FailureReason.CONFLICT)
        // This is still first dispatch, not positive-attempt replay. Do not rebase a saved
        // target or accept a later terminal observation merely because its root is the same.
        requireNewTargetEligibility(original, actual)
        val next = requireCurrentCodec().withPublicationReservation(entry.value.copy(clock = now(),
            issued = entry.value.issued + original.commandId,
            publicationHold = PostDraftPublicationHoldV2(original, PublicationCapacityReservationV1(configuredPublicationPolicy!!.maxResponseBytes,
                publicationReservedBytes, 0, 1)),
            localPending = entry.value.localPending?.takeUnless { it.clientId == snapshot.clientDraftId && deliveredLocal != null },
            completion = entry.value.completion?.takeUnless { it.clientId == snapshot.clientDraftId && deliveredApply != null }), publicationReservedBytes)
        return original to HoldProposal(entry, next, deliveredLocal, deliveredApply)
    }

    /** Full original bytes stay immutable. Only a later observed JSON object's key order is
     * immaterial; arrays, optional presence, strings, exact numeric values and ETag still bind.
     * This is first-dispatch eligibility DATA, not consent, a GET acknowledgement or replay. */
    private fun requireNewTargetEligibility(original: PublicationOriginalLinkV1, current: DraftLocalSnapshotV1) {
        val observed = current.serverAssociation as? DraftServerAssociationV1.Observed
        observed?.let {
            if (postString(it.exactPostDraft, "status") != "draft" ||
                Instant.parse(postString(it.exactPostDraft, "expiresAt")).toEpochMilliseconds() <= now()) mealFail(FailureReason.CONFLICT)
        }
        when (val target = original.historicalReview.target) {
            PublicationReviewTargetV1.DirectLocal -> if (observed != null) mealFail(FailureReason.CONFLICT)
            is PublicationReviewTargetV1.SavedDraft -> if (observed == null || observed.etag != target.reviewedETag ||
                !publicationRecordSame(observed.exactPostDraft.json(), target.exactReviewedDraft.json())) mealFail(FailureReason.CONFLICT)
        }
    }

    /** Current draft consumers can append legacy draft history and edit/observe locals. They
     * cannot alter publication lifecycle fields, remove prior identities or resurrect roots. */
    private fun preservePublicationMaterial(before: PostDraftV2Record, next: PostDraftV2Record) {
        if (next.clock < before.clock || next.clock > now() || next.issued.take(before.issued.size) != before.issued ||
            !samePublicationMaterial(before, next)) mealFail(FailureReason.CONFLICT)
        for (terminal in before.terminals.filterIsInstance<PostDraftTerminalV2.LegacyDiscard>()) {
            val after = next.terminals.filterIsInstance<PostDraftTerminalV2.LegacyDiscard>().singleOrNull { it.clientId == terminal.clientId }
                ?: mealFail(FailureReason.CONFLICT)
            if (after.exactLegacy != terminal.exactLegacy) mealFail(FailureReason.CONFLICT)
        }
        for (after in next.locals) {
            val prior = before.locals.singleOrNull { it.clientDraftId == after.clientDraftId }
            if (prior == null) {
                if (after.clientDraftId in before.issued) mealFail(FailureReason.CONFLICT)
            } else {
                if (after.localRevision < prior.localRevision) mealFail(FailureReason.CONFLICT)
                if (after.localRevision == prior.localRevision) {
                    val a = snapshotCodec!!.create(prior.clientDraftId, prior.localRevision, prior.content, DraftServerAssociationV1.NotObserved)
                    val b = snapshotCodec!!.create(after.clientDraftId, after.localRevision, after.content, DraftServerAssociationV1.NotObserved)
                    if (!a.exactUtf8.copyForCodec().contentEquals(b.exactUtf8.copyForCodec())) mealFail(FailureReason.CONFLICT)
                }
                if (prior.serverAssociation is DraftServerAssociationV1.Observed) {
                    val observed = after.serverAssociation as? DraftServerAssociationV1.Observed ?: mealFail(FailureReason.CONFLICT)
                    PostDraftAdapter(policy).monotone(prior.serverAssociation.exactPostDraft, observed.exactPostDraft)
                }
            }
        }
    }
    private fun samePublicationMaterial(a: PostDraftV2Record, b: PostDraftV2Record): Boolean {
        val ah = a.publicationHold; val bh = b.publicationHold
        if ((ah == null) != (bh == null) || (ah != null && !sameLink(ah.link, bh!!.link))) return false
        val at = a.terminals.filterIsInstance<PostDraftTerminalV2.Published>()
        val bt = b.terminals.filterIsInstance<PostDraftTerminalV2.Published>()
        if (at.size != bt.size || at.indices.any { at[it].postId != bt[it].postId || !sameLink(at[it].link, bt[it].link) }) return false
        if (a.remainders.size != b.remainders.size) return false
        return a.remainders.indices.all { i -> val x = a.remainders[i]; val y = b.remainders[i]
            sameLink(x.link, y.link) && x.unsubmittedSnapshot.exactUtf8.copyForCodec().contentEquals(y.unsubmittedSnapshot.exactUtf8.copyForCodec()) }
    }
    private fun sameLink(a: PublicationOriginalLinkV1, b: PublicationOriginalLinkV1) = a.exactUtf8.copyForCodec().contentEquals(b.exactUtf8.copyForCodec())
    private fun samePayload(actual: PrivateRecord, proposed: StoreMutation.Put) = actual.schemaVersion == proposed.schemaVersion &&
        actual.payload.copyForCodec().contentEquals(proposed.payload.copyForCodec())

    /** Canonical data correlation only. No ApiReply/header is fabricated to turn a detached
     * Post into queue evidence; actual receipt provenance stays in the publication coordinator. */
    private fun requirePostComparison(link: PublicationOriginalLinkV1, receipt: PostPublicationReceipt) {
        val expected = PostPublicationAdapter(configuredPublicationPolicy!!.maxResponseBytes)
            .expectedSelection(link.originalIntentForComparison().call).json().jsonObject
        val actual = receipt.document.json().jsonObject
        if (publicationRecordBigint(actual.getValue("version")) != "1" ||
            uuid(string(actual.getValue("author").jsonObject.getValue("userId"))) != link.originalCanonicalUserId) mealFail(FailureReason.INVALID_DATA)
        for (field in listOf("caption", "altText", "mediaIds", "keepOnPlate", "attachment", "sourcePostId"))
            if (!publicationRecordSame(expected[field], actual[field])) mealFail(FailureReason.INVALID_DATA)
        val ea = expected.getValue("audience").jsonObject; val aa = actual.getValue("audience").jsonObject
        if (!publicationRecordSame(ea["kind"], aa["kind"]) || !publicationRecordSame(ea["circleIds"], aa["circleIds"]))
            mealFail(FailureReason.INVALID_DATA)
        val bindings = aa["bindings"] as? JsonArray ?: mealFail(FailureReason.INVALID_DATA)
        val bound = bindings.map { string(it.jsonObject.getValue("circleId")) }
        val selected = ea.getValue("circleIds").jsonArray.map(::string)
        if (bound.distinct().size != bound.size || bound.toSet() != selected.toSet()) mealFail(FailureReason.INVALID_DATA)
        val save = actual.getValue("savePolicy").jsonObject
        if (!publicationRecordSame(expected["allowRecipeSaves"], save["allowFutureSaves"]) ||
            !publicationRecordSame(expected["saveDisclosureVersion"], save["disclosureVersion"])) mealFail(FailureReason.INVALID_DATA)
    }

    private suspend fun contribution(use: DraftJournalUse, entry: PostDraftV2Entry, value: PostDraftV2Record,
        commandId: String, kind: ContributionKind, deliveredLocal: DeliveredLocalMarker? = null,
        deliveredApply: DeliveredApplyMarker? = null): DraftMutationContribution {
        val held = requirePublication(use)
        if (held.entries.none { it === entry } || entry.record?.schemaVersion != 2 || value.clock < entry.value.clock || value.clock > now())
            mealFail(FailureReason.CONFLICT)
        if (entry.record?.revision == Long.MAX_VALUE) mealFail(FailureReason.UNAVAILABLE)
        val root = value.publicationHold?.link?.takeIf { it.commandId == commandId }?.clientDraftId ?: value.terminals
            .filterIsInstance<PostDraftTerminalV2.Published>().singleOrNull { it.commandId == commandId }?.clientId ?:
            entry.value.publicationHold?.link?.takeIf { it.commandId == commandId }?.clientDraftId ?:
            findWitness()?.contributions?.filter { it.commandId == commandId && it.kind != ContributionKind.FINALIZE }
                ?.map { it.rootId }?.distinct()?.singleOrNull()
            ?: mealFail(FailureReason.CONFLICT)
        if (kind in setOf(ContributionKind.HOLD, ContributionKind.TERMINAL)) {
            requireNoUndeliveredLocal(root)
            requireDeliveredLocalMarker(entry.value, root, deliveredLocal)
            requireDeliveredApplyMarker(use, entry, root, deliveredApply)
            requireDeliveredLocalMarker(entry.value, root, deliveredLocal)
        }
        var preparedValue = value
        var payload = requireCurrentCodec().encode(preparedValue)
        val registry = witnessHolder()
        if (registry.contributions.any { it.commandId != commandId }) mealFail(FailureReason.CONFLICT)
        val originalLink = value.publicationHold?.link ?: value.terminals.filterIsInstance<PostDraftTerminalV2.Published>()
            .singleOrNull { it.commandId == commandId }?.link ?: entry.value.publicationHold?.link ?:
            registry.contributions.firstOrNull { it.commandId == commandId }?.originalLink ?: mealFail(FailureReason.CONFLICT)
        val prior = registry.contributions.singleOrNull { it.kind == kind }
        var equivalentActualAttempt = false
        if (kind != ContributionKind.FINALIZE && prior != null) {
            if (!sameLink(prior.originalLink, originalLink) || prior.rootId != root || prior.observed != null)
                mealFail(FailureReason.CONFLICT)
            if (postSame(entry.record, prior.before)) {
                // A retry over the exact same base uses the original frozen payload/CAS. A
                // genuinely admitted equivalent old attempt remains READBACK evidence only;
                // the new Put still requires this new use's own beforeCommit authorization.
                preparedValue = value.copy(clock = prior.value.clock)
                payload = requireCurrentCodec().encode(preparedValue)
                if (!payload.copyForCodec().contentEquals(prior.mutation.payload.copyForCodec())) mealFail(FailureReason.CONFLICT)
                equivalentActualAttempt = prior.readbackAttempted
            } else if (entry.record!!.revision <= prior.before.revision) mealFail(FailureReason.CONFLICT)
            // A strictly later actual row makes the old expected-revision CAS impossible.
            // Purpose-specific prepare methods already require the still-unapplied exact hold
            // (or absent unused hold ID), preserving the original link without reusing a key.
        }
        // A later explicitly requested finalization supersedes only a previous finalization
        // write ticket. Original hold/terminal evidence is never discarded by a failed retry.
        val replacing = listOfNotNull(prior)
        val nextCount = registry.contributions.size - replacing.size
        if (nextCount >= 4) mealFail(FailureReason.UNAVAILABLE)
        val mutation = StoreMutation.Put(key, entry.record.revision, 2, payload)
        val token = DraftMutationContribution()
        val actual = Contribution(token, this, use, entry.record!!, mutation, preparedValue, commandId, root, originalLink, kind, deliveredLocal, deliveredApply)
        actual.readbackAttempted = equivalentActualAttempt
        if (replacing.isNotEmpty()) {
            registry.contributions.removeAll(replacing.toSet())
            registry.readbacks.removeAll { it.contribution in replacing }
        }
        registry.contributions += actual
        held.prepared += mutation
        rememberSuccessor(entry.record, mutation)
        return token
    }
    private fun liveContribution(use: DraftJournalUse, token: DraftMutationContribution): Contribution {
        val held = requireHeldPublication(use)
        val actual = retainedContribution(token)
        if (actual.writeOwner !== this || actual.writeUse !== use || held.prepared.none { it === actual.mutation }) mealFail(FailureReason.STALE_SESSION)
        return actual
    }
    private fun retainedContribution(token: DraftMutationContribution): Contribution = findWitness()?.contributions
        ?.singleOrNull { it.token === token } ?: mealFail(FailureReason.CONFLICT)

    /** Readback evidence is retained under actual lease/store/boundary/raw-origin identity,
     * independently of controller closure. It never acquires or extends a namespace claim. */
    private fun findWitness() = witnesses.load().singleOrNull { it.matches(access, boundary) }
    private fun witnessHolder(): WitnessHolder {
        if (!boundary.isCurrent(access.lease)) mealFail(FailureReason.STALE_SESSION)
        while (true) {
            val old = witnesses.load()
            old.singleOrNull { it.matches(access, boundary) }?.let { return it }
            val next = WitnessHolder(access, boundary)
            if (!witnesses.compareAndSet(old, old + next)) continue
            val subscription = boundary.onInvalidated(access.lease) { clearWitness(next) }
            if (boundary.isCurrent(access.lease) && next in witnesses.load()) next.subscription = subscription
            else { clearWitness(next); subscription.close(); mealFail(FailureReason.STALE_SESSION) }
            return next
        }
    }
    private fun rememberSuccessor(before: PrivateRecord?, mutation: StoreMutation.Put) {
        before ?: return
        val registry = findWitness() ?: return
        if (registry.contributions.none { postSame(it.observed, before) }) return
        val prior = requireCurrentCodec().decode(2, before.payload)
        val next = requireCurrentCodec().decode(2, mutation.payload)
        if (!samePublicationMaterial(prior, next)) return
        if (registry.successors.any { postSame(it.before, before) && sameProposed(it.mutation, mutation) }) return
        // Bounded pending actual-write candidates, not an unbounded history. Once an observed
        // CAS advances the row, impossible earlier candidates are removed below.
        if (registry.successors.size >= policy.maxLocalDrafts || registry.successors.sumOf {
                it.before.payload.copyForCodec().size.toLong() + it.mutation.payload.copyForCodec().size } +
            before.payload.copyForCodec().size + mutation.payload.copyForCodec().size > policy.maxRecordBytes.toLong() * 4)
            mealFail(FailureReason.UNAVAILABLE)
        registry.successors += Successor(before, mutation)
    }
    private fun markAttempted(mutation: StoreMutation.Put) {
        // Caller already checked THIS exact new Put in the actual active beforeCommit batch.
        // Equivalent byte/CAS retry attempts share a bounded readback candidate, not authority.
        findWitness()?.successors?.filter { sameProposed(it.mutation, mutation) }?.forEach { it.attempted = true }
    }
    private fun sameProposed(a: StoreMutation.Put, b: StoreMutation.Put) = a.key == b.key && a.schemaVersion == b.schemaVersion &&
        a.expectedRevision == b.expectedRevision && a.payload.copyForCodec().contentEquals(b.payload.copyForCodec())
    private fun rememberObservation(actual: PrivateRecord) {
        val registry = findWitness() ?: return
        for (contribution in registry.contributions) {
            if (contribution.observed == null && contribution.readbackAttempted &&
                actual.revision == (contribution.mutation.expectedRevision ?: 0) + 1 && samePayload(actual, contribution.mutation))
                contribution.observed = actual
        }
        for (edge in registry.successors) {
            if (!edge.attempted || actual.revision != edge.before.revision + 1 || !samePayload(actual, edge.mutation)) continue
            // Both endpoints are actual store observations. Exact registered CAS + complete
            // immutable publication preservation was checked when producing this successor.
            registry.contributions.filter { postSame(it.observed, edge.before) }.forEach { it.observed = actual }
        }
        registry.successors.removeAll { it.before.revision < actual.revision }
    }
    private fun clearWitness(value: WitnessHolder) {
        while (true) {
            val old = witnesses.load()
            if (value !in old) return
            if (witnesses.compareAndSet(old, old.filterNot { it === value })) {
                value.contributions.clear(); value.readbacks.clear(); value.successors.clear(); value.subscription?.close(); return
            }
        }
    }

    private enum class ContributionKind { HOLD, TERMINAL, UNSENT, FINALIZE }
    private class Contribution(val token: DraftMutationContribution, val writeOwner: PostDraftJournalOwner,
        val writeUse: DraftJournalUse, val before: PrivateRecord, val mutation: StoreMutation.Put, val value: PostDraftV2Record,
        val commandId: String, val rootId: String, val originalLink: PublicationOriginalLinkV1, val kind: ContributionKind,
        val deliveredLocal: DeliveredLocalMarker?, val deliveredApply: DeliveredApplyMarker?) {
        var attempted = false
        var readbackAttempted = false
        var observed: PrivateRecord? = null
    }
    private class Readback(val token: DraftPublicationReadback, val contribution: Contribution, val actual: PrivateRecord)
    private class Retirement(val token: DraftPublicationEvidenceRetirement, val contribution: Contribution)
    private class Successor(val before: PrivateRecord, val mutation: StoreMutation.Put) { var attempted = false }
    private class WitnessHolder(val access: AuthenticatedMealPlanningAccess, val boundary: SessionBoundary) {
        val contributions = mutableListOf<Contribution>()
        val readbacks = mutableListOf<Readback>()
        val successors = mutableListOf<Successor>()
        var subscription: SessionInvalidationSubscription? = null
        fun matches(actual: AuthenticatedMealPlanningAccess, actualBoundary: SessionBoundary) =
            access.lease === actual.lease && access.store === actual.store && access.origin == actual.origin && boundary === actualBoundary
    }

    private fun member(participant: DraftJournalParticipant): Member {
        val member = members.singleOrNull { it.token === participant } ?: mealFail(FailureReason.STALE_SESSION)
        requireMember(member); return member
    }
    private fun requireMember(member: Member) {
        if (retired || !member.attached || member !in members || !boundary.isCurrent(access.lease))
            mealFail(FailureReason.STALE_SESSION)
        if (access.lease.scope.actorKind != ActorKind.ACCOUNT) mealFail(FailureReason.UNAUTHENTICATED)
    }
    private fun requireHeld(use: DraftJournalUse): Use {
        val held = uses.singleOrNull { it.token === use } ?: mealFail(FailureReason.STALE_SESSION)
        requireMember(held.member)
        val actual = claim ?: mealFail(FailureReason.STALE_SESSION)
        if (!actual.matches(access, boundary) || claims.load().none { it === actual }) mealFail(FailureReason.STALE_SESSION)
        return held
    }
    private fun requireHeldLegacy(use: DraftJournalUse): Use = requireHeld(use).also {
        if (it.member.borrower.feature != MealKitchenFeature.POST_DRAFTS) mealFail(FailureReason.NOT_CONFIGURED)
    }
    private suspend fun requireLegacy(use: DraftJournalUse): Use {
        val held = requireHeldLegacy(use)
        composition.requireComposerPermit(held.permit, held.member.borrower)
        currentCoroutineContext().ensureActive()
        if (requireHeldLegacy(use) !== held) mealFail(FailureReason.STALE_SESSION)
        return held
    }
    private fun claim() {
        claim?.let {
            if (!it.matches(access, boundary) || claims.load().none { item -> item === it }) mealFail(FailureReason.STALE_SESSION)
            return
        }
        while (true) {
            val old = claims.load()
            // Preserve the legacy anti-wrapper exclusion: another store/boundary facade cannot
            // take the same actual lease + origin while any participant/use retains its owner.
            if (old.any { it.lease === access.lease && it.origin == origin }) mealFail(FailureReason.CONFLICT)
            val next = Claim(access.lease, access.store, boundary, origin, access.origin)
            if (claims.compareAndSet(old, old + next)) { claim = next; return }
        }
    }
    private fun drain() {
        if (members.any { it.attached } || uses.isNotEmpty()) return
        retired = true
        pins.clear(); publicationObserver = null; publicationAllocations = null
        val held = claim
        if (held != null) {
            while (true) {
                val old = claims.load()
                if (claims.compareAndSet(old, old.filterNot { it === held })) break
            }
            claim = null
        }
        subscription?.close()
        subscription = null
    }
    private fun now() = composition.clock.nowMillis().also { if (it < 0) mealFail(FailureReason.INVALID_DATA) }
    private class Claim(val lease: SessionLease, val store: PrivateStateStore, val boundary: SessionBoundary,
        val origin: String, val originBinding: String) {
        fun matches(access: AuthenticatedMealPlanningAccess, actualBoundary: SessionBoundary) =
            lease === access.lease && store === access.store && boundary === actualBoundary &&
                origin == uuid(access.origin) && originBinding == access.origin
    }
    private companion object {
        val claims = AtomicReference<List<Claim>>(emptyList())
        val witnesses = AtomicReference<List<WitnessHolder>>(emptyList())
    }
    override fun toString() = "PostDraftJournalOwner(<redacted>)"
}
