package com.feedme.mealflow.social

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.mealflow.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Identity-only reference to an ACTUAL configured-ID allocation. A constructor lookalike or
 * decoded original is never registered. This is not live review, dispatch or receipt authority. */
internal class PublicationAllocatedOriginal internal constructor() {
    override fun toString() = "PublicationAllocatedOriginal(<redacted>)"
}

class PreparedPublicationAllocationAbandon internal constructor() {
    override fun toString() = "PreparedPublicationAllocationAbandon(<redacted>)"
}
enum class PublicationAllocationPhase { AWAITING_ID, ABANDONED_WAITING_FOR_PROVIDER, REGISTRATION_UNOBSERVED }
class PublicationAllocationStatus internal constructor(val clientDraftId: String, val reviewedLocalRevision: Long,
    val phase: PublicationAllocationPhase, val returnedCommandId: String?, val abandonToken: PreparedPublicationAllocationAbandon?) {
    override fun toString() = "PublicationAllocationStatus(<redacted>)"
}

/** Immutable sizing data only. Awaiting uses the exact validated unused preflight probe; a
 * returned ID uses its exact retained original. Never a mutation/review/queue identity proof. */
internal class PublicationAllocationCapacity internal constructor(val original: PublicationOriginalLinkV1,
    val publicationReservedBytes: Long) {
    override fun toString() = "PublicationAllocationCapacity(<redacted>)"
}

/** Closed allocation implementation, assembly-bound to the actual publication participant and
 * sole draft owner. It, not a caller callback, invokes the configured ID source, constructs the
 * exact original, and retains it before the next suspension. All operations except invalidation
 * callbacks are confined to the composition's serialized identity dispatcher.
 *
 * Retention is same actual access/store/lease/boundary/raw origin, not process-restorable data.
 * Replacing a controller may bind a new registry to that SAME retained session. The newly
 * admitted mapped issuer/owner/account must still match; a fresh generation requires actual
 * admission and a fresh coordinator review, never reuse of the old principal as permission.
 * There is at most one unresolved allocation per retained session. No ID or I/O at construction.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class PublicationAllocationRegistry(
    private val composition: MealKitchenComposition,
    private val drafts: PostDraftJournalOwner,
    private val borrower: MealKitchenComposition.Borrower,
    private val principalAdmission: PublicationPrincipalAdmission,
    private val ids: MealOperationIds,
    private val draftPolicy: PostDraftClientPolicy,
    private val policy: PostPublicationClientPolicy,
) {
    private val access = composition.access
    private val boundary = composition.boundary
    private val links = PublicationOriginalLinkCodecV1(policy, DraftLocalSnapshotCodecV1(draftPolicy, policy))

    private class Allocation(val token: PublicationAllocatedOriginal, val candidate: PublicationOriginalLinkV1,
        val principal: PublicationPrincipalSnapshot, val access: AuthenticatedMealPlanningAccess,
        val boundary: SessionBoundary, val slot: ComposerAllocationSlot, val publicationReservedBytes: Long) {
        private sealed interface State
        private class Awaiting(val settled: Boolean) : State
        private class Returned(val original: PublicationOriginalLinkV1) : State
        private class Abandoned(val settled: Boolean) : State
        private val state = AtomicReference<State>(Awaiting(false))
        val abandonToken = PreparedPublicationAllocationAbandon()
        val original: PublicationOriginalLinkV1? get() = (state.load() as? Returned)?.original
        fun awaitingUnsettled() = (state.load() as? Awaiting)?.settled == false
        fun capture(original: PublicationOriginalLinkV1): Boolean {
            val prior = state.load() as? Awaiting ?: return false
            return !prior.settled && state.compareAndSet(prior, Returned(original))
        }
        fun abandon(): Boolean {
            val prior = state.load() as? Awaiting ?: return false
            return state.compareAndSet(prior, Abandoned(prior.settled))
        }
        fun settled(): Boolean {
            while (true) {
                val prior = state.load()
                val next = when (prior) {
                    is Returned -> return false
                    is Awaiting -> Awaiting(true)
                    is Abandoned -> Abandoned(true)
                }
                if (state.compareAndSet(prior, next)) return next is Abandoned
            }
        }
        fun removable() = (state.load() as? Abandoned)?.settled == true
        fun status(): PublicationAllocationStatus {
            val actual = state.load()
            return PublicationAllocationStatus(candidate.clientDraftId, candidate.reviewedLocalRevision, when (actual) {
                is Awaiting -> PublicationAllocationPhase.AWAITING_ID
                is Abandoned -> PublicationAllocationPhase.ABANDONED_WAITING_FOR_PROVIDER
                is Returned -> PublicationAllocationPhase.REGISTRATION_UNOBSERVED
            }, (actual as? Returned)?.original?.commandId, abandonToken.takeIf { actual is Awaiting })
        }
        val store = access.store
        val lease = access.lease
        val origin = access.origin
        val environment = access.lease.scope.environment
        var subscription: SessionInvalidationSubscription? = null
        fun matches(actual: AuthenticatedMealPlanningAccess, actualBoundary: SessionBoundary): Boolean =
            access === actual && store === actual.store && lease === actual.lease && boundary === actualBoundary &&
                origin == actual.origin && environment == actual.lease.scope.environment
    }

    init { requireAssembly(composition, drafts, borrower) }

    /** Assembly identity only. Owner binds this exact concrete object to its registered member. */
    fun requireAssembly(actualComposition: MealKitchenComposition, actualOwner: PostDraftJournalOwner,
        actualBorrower: MealKitchenComposition.Borrower) {
        if (actualComposition !== composition || actualOwner !== drafts || actualBorrower !== borrower ||
            borrower.feature != MealKitchenFeature.POST_PUBLICATIONS || drafts.configuredPublicationPolicy !== policy)
            mealFail(FailureReason.CONFLICT)
        composition.requireComposerParticipant(borrower)
        drafts.requireComposition(composition, draftPolicy)
    }

    /** Historical identity inspection only; not review/admission and no mutable link exposure. */
    fun retained(): PublicationAllocatedOriginal? = matching()?.takeIf { it.original != null }?.token
    fun status(): PublicationAllocationStatus? = matching()?.status()

    fun requireNone() { if (matching() != null) mealFail(FailureReason.CONFLICT) }

    /** The caller already holds an exact live review/pin. This method additionally checks the
     * ACTUAL current purpose-fixed permit/principal before allocation. All link validation is
     * probed before IDs. The shared arbiter and rich reservation are installed BEFORE IDs.next;
     * when a valid ID returns, exact original retention is synchronous before any subsequent
     * awaited fence. No exception/cancellation invents a missing provider result or allocates
     * again. The only local escape is explicit unreturned abandonment plus actual settlement.
     * onReserved publishes informational non-ACK progress only; it is not an authority callback.
     */
    suspend fun allocate(permit: ComposerOperationPermit, principal: PublicationPrincipalSnapshot,
        createdAtMillis: Long, exactPostWrite: WireDocument, snapshot: DraftLocalSnapshotV1,
        target: PublicationReviewTargetV1, disclosure: PublicationDisclosure,
        probeCommandId: String, publicationReservedBytes: Long,
        onReserved: (PublicationAllocationStatus) -> Unit): PublicationAllocatedOriginal {
        requireCurrent(permit, principal)
        requireNone()
        val binding = PublicationJournalBindingData(access.lease.scope.environment,
            publicationRecordUuid(access.origin), access.origin, principal.canonicalUserId)
        val probe = publicationRecordId(probeCommandId)
        if (publicationReservedBytes !in 1..policy.maxRecordBytes.toLong()) mealFail(FailureReason.INVALID_DATA)
        val candidate = links.create(binding, probe, createdAtMillis, call(probe, exactPostWrite), snapshot, target, disclosure)
        requireCurrent(permit, principal)
        requireNone()
        val slot = ComposerAllocationArbiter.reservePublication(access, boundary, snapshot.clientDraftId)
        val allocation = Allocation(PublicationAllocatedOriginal(), candidate, principal, access, boundary, slot, publicationReservedBytes)
        try {
            while (true) {
                val before = held.load()
                if (before.any { it.matches(access, boundary) }) mealFail(FailureReason.CONFLICT)
                if (held.compareAndSet(before, before + allocation)) break
            }
            val installed = boundary.onInvalidated(access.lease) { remove(allocation) }
            allocation.subscription = installed
            if (!boundary.isCurrent(access.lease) || matching() !== allocation) {
                installed.close(); remove(allocation); mealFail(FailureReason.STALE_SESSION)
            }
        } catch (failure: Throwable) {
            // Actual provider has not been invoked; no uncertain generated result exists.
            remove(allocation); ComposerAllocationArbiter.release(slot); throw failure
        }
        try {
            onReserved(allocation.status())
            currentCoroutineContext().ensureActive(); principalAdmission.requireCurrentNow(principal)
            // A same-dispatcher immediate progress collector can explicitly abandon before
            // the provider is invoked. Do not allocate an ID for that already-fenced slot.
            if (matching() !== allocation || !allocation.awaitingUnsettled()) mealFail(FailureReason.CONFLICT)
            val id = publicationRecordId(ids.next())
            val original = links.create(binding, id, createdAtMillis, call(id, exactPostWrite), snapshot, target, disclosure)
            // No awaited fence follows the provider's returned valid ID before capture.
            // An explicit earlier abandonment wins; its late result cannot be registered.
            if (matching() !== allocation || !allocation.capture(original)) mealFail(FailureReason.CONFLICT)
        } finally {
            if (matching() === allocation && allocation.settled()) remove(allocation)
        }
        // This awaited check may fail. It MUST NOT discard the allocated original.
        requireCurrent(permit, principal)
        return allocation.token
    }

    /** Local cancellation fencing only. A returned original is NEVER abandonable here, even
     * before enqueue. An unsettled abandoned provider keeps both slots until its actual tail. */
    fun abandonUnreturned(token: PreparedPublicationAllocationAbandon) {
        if (!boundary.isCurrent(access.lease)) mealFail(FailureReason.STALE_SESSION)
        val value = matching() ?: mealFail(FailureReason.CONFLICT)
        if (value.abandonToken !== token || !value.abandon()) mealFail(FailureReason.CONFLICT)
        if (value.removable()) remove(value)
    }

    /** Owner-facing, exact current use. The raw link is derived only from the private actual
     * allocation registry after live mapped admission; no supplied link/Boolean can stand in.
     * The owner still enforces its own fresh pin, complete row, quiescence, lineage and reserve.
     */
    suspend fun requireOriginal(permit: ComposerOperationPermit, actualOwner: PostDraftJournalOwner,
        actualBorrower: MealKitchenComposition.Borrower, allocation: PublicationAllocatedOriginal): PublicationOriginalLinkV1 {
        requireAssembly(composition, actualOwner, actualBorrower)
        val principal = principalAdmission.resolve(permit)
        val retained = requireAllocation(allocation, principal)
        requireCurrent(permit, principal)
        if (requireAllocation(allocation, principal) !== retained) mealFail(FailureReason.CONFLICT)
        return retained.original ?: mealFail(FailureReason.CONFLICT)
    }

    /** Never called merely because registration was observed. The concrete coordinator's
     * private registry must ALREADY retain this exact original with actual queue receipt or
     * zero-attempt CAS and both owner contributions. No await can strand the last original
     * between that validation and removing this duplicate. Failure removes nothing. The
     * actual Attempt remains recoverable until genuine public-delivery retirement. */
    fun transferToActualApplication(principal: PublicationPrincipalSnapshot, allocation: PublicationAllocatedOriginal,
        application: ActualPublicationApplication) {
        principalAdmission.requireCurrentNow(principal)
        val retained = requireAllocation(allocation, principal)
        val original = retained.original ?: mealFail(FailureReason.CONFLICT)
        val actual = PostComposerCoordinator.requireActualAllocationTransfer(access, boundary, principal, application)
        links.requireSameExactLink(original, actual)
        if (requireAllocation(allocation, principal) !== retained) mealFail(FailureReason.CONFLICT)
        remove(retained)
    }

    private fun requireAllocation(token: PublicationAllocatedOriginal, current: PublicationPrincipalSnapshot): Allocation {
        val value = matching()?.takeIf { it.token === token } ?: mealFail(FailureReason.CONFLICT)
        if (!value.principal.binding.sameRetainedSession(current.binding) ||
            value.principal.issuer !== current.issuer || value.principal.retainedOwner !== current.retainedOwner ||
            value.candidate.originalCanonicalUserId != current.canonicalUserId) mealFail(FailureReason.STALE_SESSION)
        if (value.original == null) mealFail(FailureReason.CONFLICT)
        return value
    }
    private suspend fun requireCurrent(permit: ComposerOperationPermit, principal: PublicationPrincipalSnapshot) {
        currentCoroutineContext().ensureActive()
        principalAdmission.requireCurrentNow(principal)
        principalAdmission.requireCurrent(permit, principal)
        currentCoroutineContext().ensureActive()
        principalAdmission.requireCurrentNow(principal)
    }
    private fun matching(): Allocation? {
        val matches = held.load().filter { it.matches(access, boundary) }
        if (matches.size > 1) mealFail(FailureReason.CONFLICT)
        return matches.singleOrNull()
    }
    private fun call(id: String, body: WireDocument) = ApiCall("publishPost",
        body = PrivateBytes(body.encodeUtf8()), idempotencyKey = SecretText(id))
    companion object {
        private val held = AtomicReference<List<Allocation>>(emptyList())
        /** Sole draft owner may use actual allocator memory to preflight a full future hold,
         * issued identity and terminal/newer-remainder reserve during every later mutation.
         * This method does not touch storage, create a reservation, rebase or grant authority.
         * Owner must measure the shadow with its complete codec and leave the real row intact.
         * All calls remain on the retained identity dispatcher, like this concrete registry.
         */
        fun capacityCandidate(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary): PublicationAllocationCapacity? {
            val values = held.load().filter { it.matches(access, boundary) }
            if (values.size > 1) mealFail(FailureReason.CONFLICT)
            val value = values.singleOrNull() ?: return null
            return PublicationAllocationCapacity(value.original ?: value.candidate, value.publicationReservedBytes)
        }
        private fun remove(allocation: Allocation) {
            while (true) {
                val before = held.load()
                if (held.compareAndSet(before, before.filterNot { it === allocation })) break
            }
            allocation.subscription?.close(); allocation.subscription = null
            ComposerAllocationArbiter.release(allocation.slot)
        }
    }
    override fun toString() = "PublicationAllocationRegistry(<redacted>)"
}
