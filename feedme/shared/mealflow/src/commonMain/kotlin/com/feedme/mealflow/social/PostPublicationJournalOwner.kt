package com.feedme.mealflow.social

import com.feedme.core.ports.*
import com.feedme.mealflow.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Registered identities only. Constructed lookalikes are never accepted. */
internal class PublicationJournalUse internal constructor() {
    override fun toString() = "PublicationJournalUse(<redacted>)"
}
internal class PublicationJournalEntry internal constructor() {
    override fun toString() = "PublicationJournalEntry(<redacted>)"
}
internal class PublicationJournalContribution internal constructor() {
    override fun toString() = "PublicationJournalContribution(<redacted>)"
}
/** A retained attempted-write witness, NOT evidence that a write happened or an ACK. */
internal class PublicationJournalReadback internal constructor() {
    override fun toString() = "PublicationJournalReadback(<redacted>)"
}

/** Sole purpose-fixed publication namespace owner. Uses the actual composition store, account
 * mapping and inherited operation. Construction does no I/O, account resolution or native work.
 * All calls run on the composition's serialized identity dispatcher. Draft state is never read
 * or mutated here: the registered sole draft owner supplies its fully validated projection.
 * No method dispatches, mints review consent, accepts a serialized ACK or returns a UI ACK.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class PostPublicationJournalOwner(
    private val composition: MealKitchenComposition,
    private val borrower: MealKitchenComposition.Borrower,
    private val principals: PublicationPrincipalAdmission,
    private val draftPolicy: PostDraftClientPolicy,
    private val policy: PostPublicationClientPolicy,
    private val snapshots: DraftLocalSnapshotCodecV1,
    private val links: PublicationOriginalLinkCodecV1,
) {
    private val access = composition.access
    private val boundary = composition.boundary
    private val origin = publicationRecordUuid(access.origin)
    private val key = RecordKey("mealflow.post-publications.v1", origin)
    private val store = composition.store
    private class Use(val token: PublicationJournalUse, val permit: ComposerOperationPermit,
        val principal: PublicationPrincipalSnapshot, val codec: PostPublicationJournalCodecV1,
        val cross: PublicationCrossRecordValidator, val binding: PublicationJournalBindingData) {
        val entries = mutableListOf<Entry>()
        val changes = mutableListOf<Change>()
    }
    private class Entry(val token: PublicationJournalEntry, val record: PrivateRecord?, val journal: PublicationJournalV1)
    private class Change(val token: PublicationJournalContribution, val before: Entry,
        val journal: PublicationJournalV1, val put: StoreMutation.Put, var validated: Boolean = false)
    private class Readback(val token: PublicationJournalReadback, val put: StoreMutation.Put,
        val canonicalUserId: String, val journal: PublicationJournalV1, val lease: SessionLease,
        val store: PrivateStateStore, val boundary: SessionBoundary, val exactOrigin: String) {
        var subscription: SessionInvalidationSubscription? = null
        fun matches(actual: AuthenticatedMealPlanningAccess, actualBoundary: SessionBoundary) =
            lease === actual.lease && store === actual.store && boundary === actualBoundary && exactOrigin == actual.origin
    }
    private val uses = mutableListOf<Use>()
    private var attached = borrower.hooks != null && boundary.isCurrent(access.lease)
    private var retired = !attached
    private var claim: Claim? = null
    private var subscription: SessionInvalidationSubscription? = null

    init {
        if (borrower.owner !== composition) mealFail(FailureReason.STALE_SESSION)
        if (borrower.feature != MealKitchenFeature.POST_PUBLICATIONS) mealFail(FailureReason.NOT_CONFIGURED)
    }

    /** A restored journal is bound to the actually mapped account, never StorageScope.actorId. */
    suspend fun enter(permit: ComposerOperationPermit, principal: PublicationPrincipalSnapshot): PublicationJournalUse {
        principals.requireCurrent(permit, principal)
        currentCoroutineContext().ensureActive(); localNow()
        if (uses.any { it.permit === permit }) mealFail(FailureReason.CONFLICT)
        claim()
        val binding = PublicationJournalBindingData(access.lease.scope.environment, origin, access.origin, principal.canonicalUserId)
        val codec = PostPublicationJournalCodecV1(binding, policy, links)
        val cross = PublicationCrossRecordValidator(codec, links, snapshots, policy, draftPolicy)
        return PublicationJournalUse().also { uses += Use(it, permit, principal, codec, cross, binding) }
    }

    /** No permission recovery on leave/close, including cancelled operations. */
    fun leave(use: PublicationJournalUse) {
        val held = uses.singleOrNull { it.token === use } ?: mealFail(FailureReason.STALE_SESSION)
        uses.remove(held); held.entries.clear(); held.changes.clear(); drain()
    }
    fun release() { attached = false; drain() }

    suspend fun requireCurrent(use: PublicationJournalUse) { requireUse(use) }
    fun requireCurrentNow(use: PublicationJournalUse) {
        val held = held(use); principals.requireCurrentNow(held.principal); held(use)
    }
    suspend fun principal(use: PublicationJournalUse): PublicationPrincipalSnapshot = requireUse(use).principal

    suspend fun read(use: PublicationJournalUse): PublicationJournalEntry {
        val held = requireUse(use)
        val record = mealValue(store.read(access.lease.scope, key))
        requireUse(use)
        val value = record?.let { held.codec.decode(it.schemaVersion, it.payload) }
            ?: PublicationJournalV1(held.binding, now(), emptyList(), emptyList())
        if (now() < value.clock) mealFail(FailureReason.CONFLICT)
        return PublicationJournalEntry().also { held.entries += Entry(it, record, value) }
    }
    suspend fun value(use: PublicationJournalUse, entry: PublicationJournalEntry): PublicationJournalV1 =
        entry(requireUse(use), entry).journal
    suspend fun requireUnchanged(use: PublicationJournalUse, entry: PublicationJournalEntry) {
        val before = entry(requireUse(use), entry)
        val current = mealValue(store.read(access.lease.scope, key))
        requireUse(use)
        if (!same(before.record, current)) mealFail(FailureReason.CONFLICT)
    }
    suspend fun requireConsistent(use: PublicationJournalUse, entry: PublicationJournalEntry, drafts: DraftPublicationProjectionV2) {
        val held = requireUse(use); held.cross.requireConsistent(entry(held, entry).journal, drafts)
    }

    /** Pure next-record preparation inside a real use. No generic replace-record API exists. */
    suspend fun prepareOriginal(use: PublicationJournalUse, entry: PublicationJournalEntry,
        original: PublicationOriginalLinkV1): PublicationJournalContribution {
        val held = requireUse(use); val before = entry(held, entry)
        return prepare(use, held, before, nextOriginal(held, before, original))
    }
    /** Detached complete pre-ID data only: never registers a probe mutation/readback handle. */
    suspend fun preflightOriginal(use: PublicationJournalUse, entry: PublicationJournalEntry,
        original: PublicationOriginalLinkV1): Pair<PublicationJournalV1, Long> {
        val held = requireUse(use); val before = entry(held, entry); val next = nextOriginal(held, before, original)
        requireUnchanged(use, entry)
        return next to held.codec.reservedPublishedBytes(next)
    }
    suspend fun validatePreflight(use: PublicationJournalUse, proposed: PublicationJournalV1,
        drafts: DraftPublicationProjectionV2) {
        val held = requireUse(use); held.cross.requireConsistent(proposed, drafts)
    }
    private fun nextOriginal(held: Use, before: Entry, original: PublicationOriginalLinkV1): PublicationJournalV1 {
        val prior = before.journal
        if (prior.entries.any { it is PublicationHistoryEntryV1.PendingOriginal }) mealFail(FailureReason.CONFLICT)
        if (original.originalCreatedAtMillis > now()) mealFail(FailureReason.INVALID_DATA)
        val checked = links.decode(links.encode(original))
        val next = PublicationJournalV1(held.binding, now(), prior.issuedCommandIds + checked.commandId,
            prior.entries + PublicationHistoryEntryV1.PendingOriginal(checked))
        held.codec.requireReservedPublishedCapacity(next)
        return next
    }
    suspend fun preparePublished(use: PublicationJournalUse, entry: PublicationJournalEntry,
        original: PublicationOriginalLinkV1, reply: HistoricalPublicationReplyV1): PublicationJournalContribution {
        val held = requireUse(use); val before = entry(held, entry); requirePending(held, before, original)
        val next = PublicationJournalV1(held.binding, now(), before.journal.issuedCommandIds,
            before.journal.entries.dropLast(1) + PublicationHistoryEntryV1.PublishedHistorical(original, reply))
        return prepare(use, held, before, next)
    }
    suspend fun prepareCancelledUnsent(use: PublicationJournalUse, entry: PublicationJournalEntry,
        original: PublicationOriginalLinkV1): PublicationJournalContribution {
        val held = requireUse(use); val before = entry(held, entry); requirePending(held, before, original)
        val next = PublicationJournalV1(held.binding, now(), before.journal.issuedCommandIds,
            before.journal.entries.dropLast(1) + PublicationHistoryEntryV1.CancelledUnsentHistorical(original))
        return prepare(use, held, before, next)
    }
    suspend fun candidate(use: PublicationJournalUse, contribution: PublicationJournalContribution): PublicationJournalV1 =
        change(requireUse(use), contribution).journal
    suspend fun publicationReserve(use: PublicationJournalUse, contribution: PublicationJournalContribution): Long {
        val held = requireUse(use); val value = change(held, contribution).journal
        held.codec.requireReservedPublishedCapacity(value); return held.codec.reservedPublishedBytes(value)
    }
    /** The coordinator obtains this projection from the actual prepared draft contribution, not
     * a screen or a deserialized permission. Data consistency still grants no write/ACK itself. */
    suspend fun validatePrepared(use: PublicationJournalUse, contribution: PublicationJournalContribution,
        drafts: DraftPublicationProjectionV2) {
        val held = requireUse(use); val change = change(held, contribution)
        held.cross.requireConsistent(change.journal, drafts); change.validated = true
    }
    suspend fun mutation(use: PublicationJournalUse, contribution: PublicationJournalContribution): StoreMutation.Put {
        val held = requireUse(use); val change = change(held, contribution)
        if (!change.validated) mealFail(FailureReason.CONFLICT)
        requireUnchanged(use, change.before.token)
        return change.put
    }

    /** Composition beforeCommit already checks the inherited operation; this is a synchronous
     * exact registered-object fence, not a raw-store facade. No matching-key lookalike Put. */
    fun beforeCommit(use: PublicationJournalUse, contribution: PublicationJournalContribution, batch: List<StoreMutation>) {
        requireCurrentNow(use); val change = change(held(use), contribution)
        if (!change.validated || batch.filter { it.key == key }.singleOrNull() !== change.put)
            mealFail(FailureReason.CONFLICT)
    }
    fun ownsKey(candidate: RecordKey) = candidate == key

    /** This handle only records an actual intended Put. The coordinator must separately retain
     * the queue invocation and exact archive Put, and observe both after the attempted commit. */
    suspend fun retainReadback(use: PublicationJournalUse, contribution: PublicationJournalContribution): PublicationJournalReadback {
        val held = requireUse(use); val change = change(held, contribution)
        if (!change.validated) mealFail(FailureReason.CONFLICT)
        val record = Readback(PublicationJournalReadback(), change.put, held.principal.canonicalUserId, change.journal,
            access.lease, access.store, boundary, access.origin)
        while (true) { val old = retainedReadbacks.load(); if (retainedReadbacks.compareAndSet(old, old + record)) break }
        val installed = boundary.onInvalidated(access.lease) { removeReadback(record) }
        if (!boundary.isCurrent(access.lease)) { installed.close(); removeReadback(record); mealFail(FailureReason.STALE_SESSION) }
        record.subscription = installed
        return record.token
    }
    suspend fun observeReadback(use: PublicationJournalUse, witness: PublicationJournalReadback): PrivateRecord {
        val held = requireUse(use); val actual = readback(held, witness)
        val first = mealValue(store.read(access.lease.scope, key)) ?: mealFail(FailureReason.CONFLICT)
        requireUse(use)
        if (first.schemaVersion != actual.put.schemaVersion || first.revision <= (actual.put.expectedRevision ?: 0L) ||
            !first.payload.copyForCodec().contentEquals(actual.put.payload.copyForCodec())) mealFail(FailureReason.CONFLICT)
        held.codec.decode(first.schemaVersion, first.payload)
        val repeated = mealValue(store.read(access.lease.scope, key))
        requireUse(use)
        if (!same(first, repeated)) mealFail(FailureReason.CONFLICT)
        return first
    }
    /** A NEW use may prepare a fresh changed CAS of observed exact bytes. The old contribution
     * never becomes a write permission again. This alone is not publication acknowledgement. */
    suspend fun prepareReadbackCAS(use: PublicationJournalUse, witness: PublicationJournalReadback): PublicationJournalContribution {
        val held = requireUse(use); val actual = readback(held, witness)
        val record = observeReadback(use, witness)
        val entry = Entry(PublicationJournalEntry(), record, actual.journal).also { held.entries += it }
        return prepare(use, held, entry, actual.journal)
    }
    suspend fun verifyCommitReadback(use: PublicationJournalUse, contribution: PublicationJournalContribution,
        acknowledgements: Map<RecordKey, Long?>): PrivateRecord {
        val held = requireUse(use); val change = change(held, contribution)
        val revision = acknowledgements[key] ?: mealFail(FailureReason.STORAGE_FAILURE)
        if (revision <= 0 || change.put.expectedRevision?.let { it == Long.MAX_VALUE || revision != it + 1 } == true)
            mealFail(FailureReason.STORAGE_FAILURE)
        val actual = mealValue(store.read(access.lease.scope, key)) ?: mealFail(FailureReason.STORAGE_FAILURE)
        requireUse(use)
        if (actual.revision != revision || actual.schemaVersion != 1 ||
            !actual.payload.copyForCodec().contentEquals(change.put.payload.copyForCodec())) mealFail(FailureReason.STORAGE_FAILURE)
        return actual
    }
    fun forgetReadback(witness: PublicationJournalReadback) {
        localNow(); val found = retainedReadbacks.load().singleOrNull { it.token === witness && it.matches(access, boundary) }
            ?: mealFail(FailureReason.STALE_SESSION)
        removeReadback(found)
    }

    private suspend fun prepare(use: PublicationJournalUse, held: Use, before: Entry,
        next: PublicationJournalV1): PublicationJournalContribution {
        if (next.clock < before.journal.clock || now() < next.clock) mealFail(FailureReason.CONFLICT)
        if (before.record?.revision == Long.MAX_VALUE) mealFail(FailureReason.UNAVAILABLE)
        val payload = held.codec.encode(next)
        requireUnchanged(use, before.token)
        return PublicationJournalContribution().also {
            held.changes += Change(it, before, held.codec.decode(1, payload), StoreMutation.Put(key, before.record?.revision, 1, payload))
        }
    }
    private fun requirePending(held: Use, before: Entry, original: PublicationOriginalLinkV1) {
        val pending = before.journal.entries.lastOrNull() as? PublicationHistoryEntryV1.PendingOriginal
            ?: mealFail(FailureReason.CONFLICT)
        links.requireSameExactLink(pending.original, original)
        if (original.originalCanonicalUserId != held.principal.canonicalUserId) mealFail(FailureReason.STALE_SESSION)
    }
    private fun entry(held: Use, token: PublicationJournalEntry) = held.entries.singleOrNull { it.token === token }
        ?: mealFail(FailureReason.STALE_SESSION)
    private fun change(held: Use, token: PublicationJournalContribution) = held.changes.singleOrNull { it.token === token }
        ?: mealFail(FailureReason.STALE_SESSION)
    private fun readback(held: Use, token: PublicationJournalReadback): Readback {
        val actual = retainedReadbacks.load().singleOrNull { it.token === token && it.matches(access, boundary) }
            ?: mealFail(FailureReason.STALE_SESSION)
        if (actual.canonicalUserId != held.principal.canonicalUserId) mealFail(FailureReason.STALE_SESSION)
        return actual
    }
    private suspend fun requireUse(token: PublicationJournalUse): Use {
        val held = held(token)
        principals.requireCurrent(held.permit, held.principal)
        currentCoroutineContext().ensureActive()
        if (held(token) !== held) mealFail(FailureReason.STALE_SESSION)
        return held
    }
    private fun held(token: PublicationJournalUse): Use {
        localNow()
        val held = uses.singleOrNull { it.token === token } ?: mealFail(FailureReason.STALE_SESSION)
        val actual = claim ?: mealFail(FailureReason.STALE_SESSION)
        if (!actual.matches(access, boundary) || claims.load().none { it === actual }) mealFail(FailureReason.STALE_SESSION)
        return held
    }
    private fun localNow() {
        if (retired || !attached || borrower.hooks == null || !boundary.isCurrent(access.lease)) mealFail(FailureReason.STALE_SESSION)
        if (access.lease.scope.actorKind != ActorKind.ACCOUNT) mealFail(FailureReason.UNAUTHENTICATED)
    }
    private fun claim() {
        claim?.let {
            if (!it.matches(access, boundary) || claims.load().none { item -> item === it }) mealFail(FailureReason.STALE_SESSION)
            return
        }
        while (true) {
            val old = claims.load()
            if (old.any { it.lease === access.lease && it.origin == origin }) mealFail(FailureReason.CONFLICT)
            val next = Claim(access.lease, access.store, boundary, origin, access.origin)
            if (claims.compareAndSet(old, old + next)) { claim = next; break }
        }
        if (subscription == null) {
            val installed = boundary.onInvalidated(access.lease) { retired = true; attached = false; drain() }
            if (retired) installed.close() else subscription = installed
        }
    }
    private fun drain() {
        if (attached || uses.isNotEmpty()) return
        retired = true
        claim?.let { actual -> while (true) {
            val old = claims.load(); if (claims.compareAndSet(old, old.filterNot { it === actual })) break
        } }
        claim = null; subscription?.close(); subscription = null
    }
    private fun now() = composition.clock.nowMillis().also { if (it < 0) mealFail(FailureReason.INVALID_DATA) }
    private fun same(first: PrivateRecord?, second: PrivateRecord?) = first == null && second == null ||
        first != null && second != null && first.revision == second.revision && first.schemaVersion == second.schemaVersion &&
            first.payload.copyForCodec().contentEquals(second.payload.copyForCodec())
    private class Claim(val lease: SessionLease, val store: PrivateStateStore, val boundary: SessionBoundary,
        val origin: String, val exactOrigin: String) {
        fun matches(actual: AuthenticatedMealPlanningAccess, actualBoundary: SessionBoundary) = lease === actual.lease &&
            store === actual.store && boundary === actualBoundary && origin == publicationRecordUuid(actual.origin) && exactOrigin == actual.origin
    }
    private companion object {
        val claims = AtomicReference<List<Claim>>(emptyList())
        // Actual attempted-write identity survives controller replacement on this lease only.
        // A new process, store/boundary facade or deserialized journal has no such witness.
        val retainedReadbacks = AtomicReference<List<Readback>>(emptyList())
        fun removeReadback(value: Readback) {
            while (true) { val old = retainedReadbacks.load(); if (retainedReadbacks.compareAndSet(old, old.filterNot { it === value })) break }
            value.subscription?.close(); value.subscription = null
        }
    }
    override fun toString() = "PostPublicationJournalOwner(<redacted>)"
}
