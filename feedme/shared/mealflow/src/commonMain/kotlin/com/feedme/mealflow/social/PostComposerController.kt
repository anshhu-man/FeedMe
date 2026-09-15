package com.feedme.mealflow.social

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.mealflow.*
import com.feedme.sync.CommandPhase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

enum class PostComposerPhase { HIDDEN, HISTORY, REVIEW, ORIGINAL_PENDING, PUBLISHED, CANCELLED_UNSENT, ERROR, UNAVAILABLE }

/** Detached display, never an editable draft, new review or receipt/ACK capability. */
class PublicationLocalObservation internal constructor(val clientDraftId: String, val localRevision: Long,
    val exactHistoricalSnapshot: WireDocument) {
    override fun toString() = "PublicationLocalObservation(<redacted>)"
}
class PublicationReviewSnapshot internal constructor(val target: PublicationTarget, val exactProposedPostWrite: WireDocument,
    val reviewedLocal: PublicationLocalObservation, val disclosure: PublicationDisclosure,
    val publisherUserId: String, val preparedAtMillis: Long, val reviewExpiresAtMillis: Long) {
    override fun toString() = "PublicationReviewSnapshot(<redacted>)"
}
class PublicationOriginalSnapshot internal constructor(val commandId: String, val clientDraftId: String,
    val originalCreatedAtMillis: Long, val originalCanonicalUserId: String, val exactOriginalPostWrite: WireDocument,
    val originalReviewedLocal: PublicationLocalObservation, val displayedDisclosure: PublicationDisclosure) {
    override fun toString() = "PublicationOriginalSnapshot(<redacted>)"
}
class PublicationReviewView internal constructor(val token: PreparedPublicationReview, val snapshot: PublicationReviewSnapshot,
    private val navigation: PublicationNavigationValidity) {
    /** Presentation only; confirm must still pass the actual controller's live token checks. */
    val isCurrentForNavigation: Boolean get() = navigation.isCurrent()
    override fun toString() = "PublicationReviewView(<redacted>)"
}
class PublicationRetryView internal constructor(val token: PreparedPublicationRetry, val original: PublicationOriginalSnapshot,
    val separatelyObservedCurrentLocal: PublicationLocalObservation, val observedAttempts: Int?,
    val preparedAtMillis: Long, val reviewExpiresAtMillis: Long, private val navigation: PublicationNavigationValidity) {
    val isCurrentForNavigation: Boolean get() = navigation.isCurrent()
    override fun toString() = "PublicationRetryView(<redacted>)"
}
class UnsentPublicationView internal constructor(val token: PreparedUnsentPublicationDiscard,
    val original: PublicationOriginalSnapshot, val retainedLocal: PublicationLocalObservation, private val navigation: PublicationNavigationValidity) {
    val isCurrentForNavigation: Boolean get() = navigation.isCurrent()
    override fun toString() = "UnsentPublicationView(<redacted>)"
}
class PublicationHistorySnapshot internal constructor(val original: PublicationOriginalSnapshot,
    val outcome: String, val exactCanonicalPost: WireDocument?, val etag: String?) {
    override fun toString() = "PublicationHistorySnapshot(<redacted>)"
}
class PublicationPendingSnapshot internal constructor(val commandId: String, val phase: String,
    val observedAttempts: Int?, val earliestRetryAtMillis: Long?) {
    override fun toString() = "PublicationPendingSnapshot(<redacted>)"
}

/** Immutable public read shape. Every historical Post remains explicitly unacknowledged unless
 * this exact controller call delivered genuine same-lease application evidence at its tail. */
class PostComposerState internal constructor(val phase: PostComposerPhase,
    history: List<PublicationHistorySnapshot> = emptyList(), val pending: PublicationPendingSnapshot? = null,
    val disclosure: PublicationDisclosure? = null, val review: PublicationReviewView? = null,
    val retry: PublicationRetryView? = null, val unsentCancellation: UnsentPublicationView? = null,
    val exactCanonicalPost: WireDocument? = null, val etag: String? = null,
    val acknowledged: Boolean = false, val failure: FailureReason? = null,
    val allocation: PublicationAllocationStatus? = null) {
    private val retainedHistory = history.toList()
    val history: List<PublicationHistorySnapshot> get() = retainedHistory.toList()
    override fun toString() = "PostComposerState(<redacted>)"
    internal fun delivered() = PostComposerState(phase, history, pending, disclosure, review, retry,
        unsentCancellation, exactCanonicalPost, etag, acknowledged = true, failure = failure, allocation = allocation)
    companion object {
        internal fun unavailable() = PostComposerState(PostComposerPhase.UNAVAILABLE, failure = FailureReason.STALE_SESSION)
    }
}

/** Internally minted actual controller return ticket. Atomic state only may be consulted from
 * arbitrary caller dispatchers; no dispatcher-confined session/provider callbacks run here. */
@OptIn(ExperimentalAtomicApi::class)
internal class PublicationControllerDeliveryTicket internal constructor(private val owner: Any) {
    private sealed interface State
    private data object Pending : State
    private class Armed(val application: ActualPublicationApplication?, val expected: PostComposerState,
        val projected: PostComposerState, val flow: MutableStateFlow<PostComposerState>, val gate: PublicationDeliveryGate) : State
    private sealed interface Completion
    private class Publishing(val armed: Armed) : Completion
    private class Published(val application: ActualPublicationApplication?, val projected: PostComposerState) : Completion
    private data object Abandoned : Completion
    private data object Revoked : State
    private val state = AtomicReference<State>(Pending)
    private val published = AtomicReference<Completion?>(null)
    fun arm(owner: Any, application: ActualPublicationApplication?, expected: PostComposerState,
        projected: PostComposerState, flow: MutableStateFlow<PostComposerState>, gate: PublicationDeliveryGate): Boolean {
        if (this.owner !== owner) return false
        if (state.compareAndSet(Pending, Armed(application, expected, projected, flow, gate))) return true
        gate.cancel(); return false
    }
    fun authorize(owner: Any, application: ActualPublicationApplication?, expected: PostComposerState, projected: PostComposerState): Boolean {
        if (this.owner !== owner) return false
        val prior = state.load() as? Armed ?: return false
        if (prior.application !== application || prior.expected !== expected || prior.projected !== projected) return false
        return prior.gate.tryAuthorizeDelivery()
    }
    /** Own the actual bound state CAS and only then stamp genuine publication. A concurrent
     * collector can revoke/redact inside CAS; that cannot erase an earlier authorized emission.
     * There is no external recordPublished boolean and no callback or await in this tail. */
    fun publishAuthorized(owner: Any, application: ActualPublicationApplication?, expected: PostComposerState, projected: PostComposerState): PostComposerState? {
        val armed = state.load() as? Armed ?: return null
        if (this.owner !== owner || armed.application !== application || armed.expected !== expected || armed.projected !== projected || !armed.gate.wasAuthorized())
            return null
        val publishing = Publishing(armed)
        if (!published.compareAndSet(null, publishing)) return null
        if (!armed.flow.compareAndSet(expected, projected)) {
            check(published.compareAndSet(publishing, Abandoned)) { "Publication delivery invariant" }
            return null
        }
        check(published.compareAndSet(publishing, Published(application, projected))) { "Publication delivery invariant" }
        return projected
    }
    fun abandon(owner: Any, expected: PostComposerState, projected: PostComposerState) {
        val armed = state.load() as? Armed ?: return
        if (this.owner === owner && armed.expected === expected && armed.projected === projected) {
            armed.gate.cancel(); published.compareAndSet(null, Abandoned)
        }
    }
    /** Coordinator retains this exact ticket only after its ACTUAL enclosing controller proved
     * registry ownership. Safe after controller replacement; constructors/history cannot register. */
    fun deliveredApplication(application: ActualPublicationApplication): Boolean =
        (published.load() as? Published)?.application === application
    fun revoke() { while (true) {
        val old = state.load()
        if (old is Armed) { old.gate.cancel(); return }
        if (old === Revoked || state.compareAndSet(old, Revoked)) return
    } }
    override fun toString() = "PublicationControllerDeliveryTicket(<redacted>)"
}

/** Exact operation-owned cancellation holder, created before any dispatcher handoff.
 * Callback may run on any thread; it only revokes this operation's ticket, never a newer one.
 * Cancellation before binding is remembered and cancels a later ticket immediately. */
@OptIn(ExperimentalAtomicApi::class)
private class PublicationCallerCancellation {
    private sealed interface State
    private data object Pending : State
    private data object Cancelled : State
    private class Bound(val ticket: PublicationControllerDeliveryTicket) : State
    private val state = AtomicReference<State>(Pending)
    fun bind(ticket: PublicationControllerDeliveryTicket): Boolean {
        if (state.compareAndSet(Pending, Bound(ticket))) return true
        ticket.revoke(); return false
    }
    fun cancel() { (state.exchange(Cancelled) as? Bound)?.ticket?.revoke() }
}

/** Actual public methods over one borrowed composition, actual queue, sole draft owner and
 * publication journal owner. Constructor is INTERNAL and performs no I/O. No enabled/native
 * factory is installed; all mapped owner, disclosure, media/audience/source prerequisites and
 * atomic native revocation remain REQUIRED assembly dependencies.
 *
 * Save/Review/Publish remain distinct: this controller never PATCHes a private server draft,
 * creates one implicitly, changes a reviewed original, or allocates a remainder root.
 */
@OptIn(ExperimentalAtomicApi::class)
class PostComposerController internal constructor(private val composition: MealKitchenComposition,
    drafts: PostDraftJournalOwner, draftPolicy: PostDraftClientPolicy, policy: PostPublicationClientPolicy,
    prerequisites: PostPublicationLifecyclePrerequisites, ids: MealOperationIds) {
    private val dispatcher = composition.dispatcher
    private val identity = Any()
    private val mutex = Mutex()
    private val finalPublication = Mutex()
    private val currentTicket = AtomicReference<PublicationControllerDeliveryTicket?>(null)
    private val isClosed = AtomicReference(false)
    private val navigationEpoch = AtomicReference<Any>(Any())
    private var generation: Any = Any() // Identity dispatcher only.
    private var active: Any? = null // Actual inherited operation generation, identity only.
    private var deliverySubscription: PublicationDeliveryRevocationSubscription? = null
    private val mutable = MutableStateFlow(PostComposerState(PostComposerPhase.HIDDEN))
    val states: StateFlow<PostComposerState> = mutable.asStateFlow()
    private val coordinator = PostComposerCoordinator(composition, drafts, draftPolicy, policy, prerequisites,
        object : PublicationControllerOperationFence {
            override suspend fun requireCurrent() { currentCoroutineContext().ensureActive(); requireCurrentNow() }
            override fun requireCurrentNow() {
                if (isClosed.load() || active == null || active !== generation) mealFail(FailureReason.STALE_SESSION)
            }
            override fun ownsApplicationDelivery(ticket: PublicationControllerDeliveryTicket): Boolean {
                requireCurrentNow()
                return currentTicket.load() === ticket
            }
            override fun showAllocationPending(status: PublicationAllocationStatus) {
                requireCurrentNow()
                // Informational progress, not a deliverable review/ACK. No command ID exists
                // in this display until the actual provider returns one.
                mutable.value = PostComposerState(PostComposerPhase.ORIGINAL_PENDING, mutable.value.history,
                    allocation = status)
            }
        }, ids)
    private val boundarySubscription = composition.boundary.onInvalidated(composition.access.lease) { invalidatePrivateState() }

    suspend fun restore(): PortResult<PostComposerState> = run { use -> observed(coordinator.inspect(use)) }
    suspend fun loadDisclosure(clientDraftId: String): PortResult<PostComposerState> = run { use ->
        val actual = coordinator.disclosure(use, clientDraftId)
        PostComposerState(PostComposerPhase.HISTORY, observed(coordinator.inspect(use)).history, disclosure = actual)
    }
    suspend fun prepareReview(target: PublicationTarget, choices: ReviewedPostChoices): PortResult<PostComposerState> = run(replaceReview = true) { use ->
        val prepared = coordinator.prepareReview(use, target, choices)
        val material = prepared.material
        PostComposerState(PostComposerPhase.REVIEW, observed(coordinator.inspect(use)).history,
            review = PublicationReviewView(prepared.token, PublicationReviewSnapshot(material.target,
                material.exactPostWrite, display(material.snapshot), material.disclosure, material.publisherUserId,
                material.preparedAtMillis, material.expiresAtMillis), navigation()))
    }
    internal fun requireComposition(actual: MealKitchenComposition) {
        if (actual !== composition) mealFail(FailureReason.STALE_SESSION)
    }
    /** Same-composition draft selection change: fence UI tokens/current return, retain actual
     * allocated/pending originals for explicit recovery. Never called for terminal observation. */
    internal fun invalidateDraftSelection() {
        navigationEpoch.store(Any())
        currentTicket.load()?.revoke(); generation = Any(); coordinator.invalidateReviews(); active = null
        // No StateFlow emission/user collector here: this is called during identity staging.
        // The UI follows the draft selection and must discard its detached review display.
    }
    internal suspend fun prepareSelectedReview(target: PublicationTarget, choices: ReviewedPostChoices,
        selection: DraftReviewedSelectionFence): PortResult<PostComposerState> = run(replaceReview = true, selection = selection) { use ->
        val prepared = coordinator.prepareReview(use, target, choices)
        val material = prepared.material
        PostComposerState(PostComposerPhase.REVIEW, observed(coordinator.inspect(use)).history,
            review = PublicationReviewView(prepared.token, PublicationReviewSnapshot(material.target,
                material.exactPostWrite, display(material.snapshot), material.disclosure, material.publisherUserId,
                material.preparedAtMillis, material.expiresAtMillis), navigation(selection)))
    }
    suspend fun confirmPublish(review: PreparedPublicationReview): PortResult<PostComposerState> = run { use ->
        progress(use, coordinator.confirm(use, review))
    }
    suspend fun prepareOriginalRetry(): PortResult<PostComposerState> = run(replaceReview = true) { use ->
        val prepared = coordinator.prepareRetry(use)
        val current = observed(coordinator.inspect(use))
        PostComposerState(PostComposerPhase.ORIGINAL_PENDING, current.history, current.pending,
            retry = PublicationRetryView(prepared.token, display(prepared.original), display(prepared.separatelyObservedLocal),
                prepared.attempts, prepared.preparedAtMillis, prepared.expiresAtMillis, navigation()))
    }
    suspend fun retryOriginal(review: PreparedPublicationRetry): PortResult<PostComposerState> = run { use ->
        progress(use, coordinator.retryOriginal(use, review))
    }
    suspend fun prepareUnsentCancellation(): PortResult<PostComposerState> = run(replaceReview = true) { use ->
        val prepared = coordinator.prepareDiscardUnsent(use)
        val current = observed(coordinator.inspect(use))
        PostComposerState(PostComposerPhase.ORIGINAL_PENDING, current.history, current.pending,
            unsentCancellation = UnsentPublicationView(prepared.token, display(prepared.original), display(prepared.retainedLocal), navigation()))
    }
    suspend fun confirmUnsentCancellation(review: PreparedUnsentPublicationDiscard): PortResult<PostComposerState> = run { use ->
        applied(use, coordinator.discardUnsent(use, review))
    }
    suspend fun applyActualReceipt(): PortResult<PostComposerState> = run { use -> applied(use, coordinator.applyReceipt(use)) }
    suspend fun finalizeOriginal(): PortResult<PostComposerState> = run { use -> applied(use, coordinator.finalizeOriginal(use)) }

    /** Explicit cancellation of only the exact UNRETURNED ID invocation. No disk/network/queue
     * mutation or caller application ACK. This local fence does not wait for a noncooperative
     * provider's composition mutex. Unit records only local cancellation, not private state or
     * admission. A returned original cannot use this route and remains recoverable unchanged. */
    suspend fun abandonUnreturnedAllocation(token: PreparedPublicationAllocationAbandon): PortResult<Unit> = try {
        withContext(dispatcher) { finalPublication.withLock {
            currentCoroutineContext().ensureActive(); requireLocalOwner()
            val status = coordinator.abandonUnreturnedAllocation(token)
            navigationEpoch.store(Any())
            currentTicket.exchange(null)?.revoke(); generation = Any(); active = null
            mutable.value = PostComposerState(if (status == null) PostComposerPhase.HISTORY else PostComposerPhase.ORIGINAL_PENDING,
                allocation = status)
        } }
        PortResult.Value(Unit)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { PortResult.Failure((failure as? MealFailure)?.reason ?: FailureReason.STORAGE_FAILURE) }
    suspend fun dismissReview(): PortResult<PostComposerState> = run(replaceReview = true) { use -> observed(coordinator.inspect(use)) }

    suspend fun close(): PortResult<Unit> = withContext(NonCancellable + dispatcher) {
        if (!isClosed.exchange(true)) {
            invalidatePrivateState(); coordinator.close(); boundarySubscription.close()
        }
        PortResult.Value(Unit)
    }

    private var applicationForReturn: ActualPublicationApplication? = null // Identity dispatcher only.
    private suspend fun progress(use: PublicationCoordinatorUse, current: PublicationCoordinatorObservation): PostComposerState =
        if (current.pendingCommand?.phase == CommandPhase.RECEIPT_READY) applied(use, coordinator.applyReceipt(use)) else observed(current)
    private suspend fun applied(use: PublicationCoordinatorUse, actual: ActualPublicationApplication): PostComposerState {
        val receipt = coordinator.appliedResult(use, actual)
        applicationForReturn = actual
        val current = coordinator.inspect(use)
        return PostComposerState(if (receipt == null) PostComposerPhase.CANCELLED_UNSENT else PostComposerPhase.PUBLISHED,
            observed(current).history, exactCanonicalPost = receipt?.document, etag = receipt?.etag)
    }
    private class Return(val ticket: PublicationControllerDeliveryTicket, val witness: PublicationDeliveryWitness,
        val application: ActualPublicationApplication?, val expected: PostComposerState, val projected: PostComposerState)

    private suspend fun run(replaceReview: Boolean = false, selection: DraftReviewedSelectionFence? = null,
        action: suspend (PublicationCoordinatorUse) -> PostComposerState): PortResult<PostComposerState> {
        val originalCallerJob = currentCoroutineContext()[Job]
        val cancellation = PublicationCallerCancellation()
        var owned: PublicationControllerDeliveryTicket? = null
        val cancellationRegistration = DeliveryOperationCancellation.register(originalCallerJob, cancellation::cancel)
        try {
            val publication = withContext(dispatcher) {
                currentCoroutineContext().ensureActive(); requireLocalOwner()
                selection?.requireRegistered(composition)
                navigationEpoch.store(Any())
                if (replaceReview) {
                    currentTicket.load()?.revoke(); generation = Any(); coordinator.invalidateReviews()
                }
                val captured = generation
                mutex.withLock {
                    currentCoroutineContext().ensureActive(); requireLocalOwner()
                    if (generation !== captured) mealFail(FailureReason.STALE_SESSION)
                    val ticket = PublicationControllerDeliveryTicket(identity)
                    if (!cancellation.bind(ticket)) mealFail(FailureReason.STALE_SESSION)
                    currentTicket.exchange(ticket)?.revoke(); owned = ticket
                    currentCoroutineContext().ensureActive(); active = captured
                    applicationForReturn = null
                    try {
                        composition.operate(coordinator.borrower) {
                            val use = coordinator.enter(composition.composerPermit(coordinator.borrower))
                            try {
                                // Only a previous genuinely delivered actual application can retire.
                                coordinator.retireDeliveredApplication(use)
                                val result = action(use)
                                val witness = coordinator.captureDelivery(use)
                                if (currentTicket.load() !== ticket || generation !== captured) mealFail(FailureReason.STALE_SESSION)
                                deliverySubscription?.close()
                                deliverySubscription = witness.observeRevocation { invalidatePrivateState() }
                                val application = applicationForReturn
                                application?.let { coordinator.armApplicationDelivery(use, it, ticket) }
                                val expected = mutable.value
                                val projected = if (application == null) result else result.delivered()
                                selection?.requireRegistered(composition)
                                val gate = coordinator.registerDelivery(use, witness)
                                if (!ticket.arm(identity, application, expected, projected, mutable, gate)) {
                                    gate.cancel(); mealFail(FailureReason.STALE_SESSION)
                                }
                                Return(ticket, witness, application, expected, projected)
                            } finally { coordinator.leave(use) }
                        }
                    } finally { if (active === captured) active = null }
                }
            }
            // The operation/composition/dispatcher can ALL have suspended after witness capture.
            // Final checks below intentionally read only atomics, final values and StateFlow CAS.
            // No SessionBoundary or legacy principal callback is read from this caller context.
            finalPublication.lock()
            try {
                currentCoroutineContext().ensureActive()
                if (isClosed.load() || currentTicket.load() !== publication.ticket || !publication.witness.isCurrent())
                    mealFail(FailureReason.STALE_SESSION)
                selection?.requireCurrentNow()
                // Absolutely NO ACK-bearing StateFlow publication precedes this one epoch CAS.
                // Revoke/cancel winning the gate means no transient ACK can reach a collector.
                if (!publication.ticket.authorize(identity, publication.application, publication.expected, publication.projected))
                    mealFail(FailureReason.STALE_SESSION)
                currentCoroutineContext().ensureActive()
                // A gate win is only authorization. If a redaction/newer publication changed the
                // expected state, fail without emitting or marking a delivered acknowledgement.
                val delivered = publication.ticket.publishAuthorized(identity, publication.application, publication.expected, publication.projected)
                    ?: mealFail(FailureReason.STALE_SESSION)
                // No-await actual PUBLICATION/return tail. If revocation is ordered after the
                // winning gate, it may redact the already authorized emission. That cannot turn
                // an already observed valid ACK into a never-delivered result. Conversely, a
                // gate win followed by losing state CAS never marks actual delivery/retirement.
                return PortResult.Value(delivered)
            } finally { finalPublication.unlock() }
        } catch (cancelled: CancellationException) {
            owned?.revoke()
            withContext(NonCancellable + dispatcher) {
                if (currentTicket.load() === owned) {
                    generation = Any(); coordinator.invalidateReviews(); active = null
                    mutable.value = PostComposerState(PostComposerPhase.ERROR, failure = FailureReason.OUTCOME_UNKNOWN)
                }
            }
            throw cancelled
        } catch (failure: Exception) {
            owned?.revoke()
            val reason = (failure as? MealFailure)?.reason ?: if (failure is IllegalArgumentException)
                FailureReason.INVALID_DATA else FailureReason.STORAGE_FAILURE
            withContext(NonCancellable + dispatcher) {
                if (currentTicket.load() === owned && !isClosed.load())
                    mutable.value = PostComposerState(PostComposerPhase.ERROR, failure = reason)
            }
            return PortResult.Failure(reason)
        } finally {
            // End every pending registration even when cancellation ignored an awaited provider.
            // Later cancellation cannot erase a gate-authorized, actually published ACK.
            cancellation.cancel(); cancellationRegistration?.dispose()
        }
    }
    private fun navigation(selection: DraftReviewedSelectionFence? = null) =
        PublicationNavigationValidity(navigationEpoch, navigationEpoch.load(), selection)
    private fun requireLocalOwner() {
        if (isClosed.load() || !composition.boundary.isCurrent(composition.access.lease)) mealFail(FailureReason.STALE_SESSION)
    }
    private fun invalidatePrivateState() {
        navigationEpoch.store(Any())
        currentTicket.exchange(null)?.revoke(); generation = Any(); active = null
        deliverySubscription?.close(); deliverySubscription = null
        coordinator.invalidateReviews(); applicationForReturn = null
        mutable.value = PostComposerState.unavailable()
    }
    private fun observed(value: PublicationCoordinatorObservation): PostComposerState {
        val history = value.history.entries.map { entry ->
            when (entry) {
                is PublicationHistoryEntryV1.PendingOriginal -> PublicationHistorySnapshot(display(entry.original), "pending", null, null)
                is PublicationHistoryEntryV1.PublishedHistorical -> PublicationHistorySnapshot(display(entry.original), "published",
                    entry.reply.exactPost, entry.reply.etag)
                is PublicationHistoryEntryV1.CancelledUnsentHistorical -> PublicationHistorySnapshot(display(entry.original), "cancelled-unsent", null, null)
            }
        } + listOfNotNull(value.unobservedRegistration?.let {
            PublicationHistorySnapshot(display(it), "registration-unobserved", null, null)
        })
        val pending = value.pendingCommand?.let { PublicationPendingSnapshot(it.commandId, it.phase.name, it.attempts, it.retryAtMillis) }
            ?: value.unobservedRegistration?.let { PublicationPendingSnapshot(it.commandId, "ORIGINAL_REGISTRATION_UNOBSERVED", null, null) }
        return PostComposerState(if (pending == null && value.allocation == null) PostComposerPhase.HISTORY else PostComposerPhase.ORIGINAL_PENDING,
            history, pending, allocation = value.allocation)
    }
    private fun display(value: DraftLocalSnapshotV1) = PublicationLocalObservation(value.clientDraftId, value.localRevision,
        WireDocument.decode(value.exactUtf8.copyForCodec()))
    private fun display(value: PublicationOriginalLinkV1) = PublicationOriginalSnapshot(value.commandId, value.clientDraftId,
        value.originalCreatedAtMillis, value.originalCanonicalUserId, value.exactPostWrite,
        display(value.historicalReview.exactReviewedLocalSnapshot), value.historicalReview.displayedDisclosure)
    override fun toString() = "PostComposerController(<redacted>)"
}
