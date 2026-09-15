package com.feedme.mealflow.social

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.kitchen.PostPublicationCommandHooks
import com.feedme.mealflow.*
import com.feedme.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal class PublicationCoordinatorUse internal constructor() {
    override fun toString() = "PublicationCoordinatorUse(<redacted>)"
}
class PreparedPublicationReview internal constructor() {
    override fun toString() = "PreparedPublicationReview(<redacted>)"
}
class PreparedPublicationRetry internal constructor() {
    override fun toString() = "PreparedPublicationRetry(<redacted>)"
}
class PreparedUnsentPublicationDiscard internal constructor() {
    override fun toString() = "PreparedUnsentPublicationDiscard(<redacted>)"
}
/** Same-lease actual application evidence. Not serialized; not a caller/StateFlow delivery ACK. */
internal class ActualPublicationApplication internal constructor() {
    override fun toString() = "ActualPublicationApplication(<redacted>)"
}
internal class PublicationCoordinatorObservation internal constructor(val history: PublicationJournalV1,
    val pendingCommand: CommandView?, val drafts: DraftPublicationProjectionV2,
    val unobservedRegistration: PublicationOriginalLinkV1? = null,
    val allocation: PublicationAllocationStatus? = null) {
    override fun toString() = "PublicationCoordinatorObservation(<redacted>)"
}
internal class PublicationReviewMaterial internal constructor(val target: PublicationTarget,
    val exactPostWrite: WireDocument, val snapshot: DraftLocalSnapshotV1, val disclosure: PublicationDisclosure,
    val publisherUserId: String, val preparedAtMillis: Long, val expiresAtMillis: Long) {
    override fun toString() = "PublicationReviewMaterial(<redacted>)"
}
internal class PublicationReviewPresentation internal constructor(val token: PreparedPublicationReview, val material: PublicationReviewMaterial) {
    override fun toString() = "PublicationReviewPresentation(<redacted>)"
}
internal class PublicationRetryPresentation internal constructor(val token: PreparedPublicationRetry,
    val original: PublicationOriginalLinkV1, val attempts: Int?, val separatelyObservedLocal: DraftLocalSnapshotV1,
    val preparedAtMillis: Long, val expiresAtMillis: Long) {
    override fun toString() = "PublicationRetryPresentation(<redacted>)"
}
internal class UnsentPublicationPresentation internal constructor(val token: PreparedUnsentPublicationDiscard,
    val original: PublicationOriginalLinkV1, val retainedLocal: DraftLocalSnapshotV1) {
    override fun toString() = "UnsentPublicationPresentation(<redacted>)"
}

/** Internal production coordinator, not an enabled public factory/controller. It owns no native
 * session or second queue and never enters composition.operate itself. The actual controller
 * opens one admitted inherited operation, then enters/leaves this owner exactly once. Required
 * current-owner and configured prerequisite ports have no accepting production defaults.
 *
 * All methods run on the SAME serialized identity dispatcher. Internal application evidence is
 * not ultimate caller/StateFlow ACK: the future public controller must provide its own final
 * cancellation/review-ticket/current-mapping delivery fence after every dispatcher boundary.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class PostComposerCoordinator(
    private val composition: MealKitchenComposition,
    private val drafts: PostDraftJournalOwner,
    private val draftPolicy: PostDraftClientPolicy,
    private val policy: PostPublicationClientPolicy,
    private val prerequisites: PostPublicationLifecyclePrerequisites,
    private val fence: PublicationControllerOperationFence,
    private val ids: MealOperationIds,
) {
    private val access = composition.access
    private val boundary = composition.boundary
    private val queue = composition.kitchen.commands
    private val snapshots = DraftLocalSnapshotCodecV1(draftPolicy, policy)
    private val links = PublicationOriginalLinkCodecV1(policy, snapshots)
    private val adapter = PostPublicationAdapter(policy.maxResponseBytes)
    private val encoder = PostPublicationEncoder(policy)
    private var generation: Any = Any()
    private var active: Use? = null
    private var review: Review? = null
    private var retry: Retry? = null
    private var cancel: Cancel? = null
    private var dispatching: Dispatch? = null
    private var committing: Commit? = null
    private var retryingLocalApplication = false
    private var closed = false

    val borrower: MealKitchenComposition.Borrower = composition.bind(MealKitchenFeature.POST_PUBLICATIONS, object : MealKitchenHooks {
        override suspend fun checkCurrent() { fence.requireCurrent(); localNow(); active?.let { principalAdmission.requireCurrentNow(it.principal) } }
        override fun beforeCommit(mutations: List<StoreMutation>) = checkCommit(mutations)
        override suspend fun beforeTransport(call: ApiCall) {
            val send = dispatching ?: mealFail(FailureReason.CONFLICT)
            val use = active ?: mealFail(FailureReason.STALE_SESSION)
            requireSameCall(send.original.originalIntentForComparison().call, call)
            requireDispatch(use, send)
        }
        override suspend fun afterTransport(call: ApiCall, reply: ApiReply) {
            val send = dispatching ?: mealFail(FailureReason.CONFLICT); val use = active ?: mealFail(FailureReason.STALE_SESSION)
            requireSameCall(send.original.originalIntentForComparison().call, call); requireUse(use.token)
            // New-eligibility checks MUST NOT run here: a successful response already made the
            // root/media terminal. Only actual current identity and exact response correlation.
            if (reply.status in 200..299) adapter.receipt(call, use.principal.canonicalUserId, reply)
        }
        override val postPublicationCommands = object : PostPublicationCommandHooks {
            override suspend fun executionDecision(lease: SessionLease, intent: CommandIntent): ExecutionDecision = try {
                if (lease !== access.lease) mealFail(FailureReason.STALE_SESSION)
                val use = active ?: mealFail(FailureReason.STALE_SESSION)
                val send = dispatching ?: mealFail(FailureReason.CONFLICT)
                requireIntent(send.original, intent); requireDispatch(use, send)
                ExecutionDecision.Ready
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { ExecutionDecision.Wait(when ((failure as? MealFailure)?.reason) {
                FailureReason.STALE_SESSION, FailureReason.UNAUTHENTICATED -> CommandIssue.AUTH_REQUIRED
                FailureReason.OFFLINE -> CommandIssue.OFFLINE
                FailureReason.NOT_CONFIGURED -> CommandIssue.NOT_CONFIGURED
                else -> CommandIssue.DOMAIN_RECHECK_REQUIRED
            }) }
            override suspend fun observeReply(lease: SessionLease, intent: CommandIntent, reply: ApiReply): PortResult<Unit> {
                if (lease !== access.lease) return PortResult.Failure(FailureReason.STALE_SESSION)
                val use = active ?: mealFail(FailureReason.STALE_SESSION)
                val send = dispatching ?: mealFail(FailureReason.CONFLICT)
                requireUse(use.token); requireIntent(send.original, intent)
                val current = inspect(use.token); requireSamePending(current.history, send.original)
                if (reply.status in 200..299) adapter.receipt(intent.call, use.principal.canonicalUserId, reply)
                return PortResult.Value(Unit)
            }
        }
    })
    private val principalAdmission = PublicationPrincipalAdmission(composition, borrower, prerequisites.principals)
    private val deliveryAdmission = PrincipalDeliveryAdmission(principalAdmission, prerequisites.delivery)
    private val allocations = PublicationAllocationRegistry(composition, drafts, borrower, principalAdmission,
        ids, draftPolicy, policy)
    private val publications = PostPublicationJournalOwner(composition, borrower, principalAdmission, draftPolicy, policy, snapshots, links)
    private val participant = if (borrower.hooks != null && boundary.isCurrent(access.lease)) drafts.register(borrower) else null
    private val subscription = boundary.onInvalidated(access.lease) { invalidateReviews(); closed = true }

    init {
        drafts.requireComposition(composition, draftPolicy)
        if (drafts.configuredPublicationPolicy !== policy) mealFail(FailureReason.NOT_CONFIGURED)
        participant?.let { drafts.registerPublicationAllocations(it, allocations) }
    }

    private class Use(val token: PublicationCoordinatorUse, val permit: ComposerOperationPermit,
        val principal: PublicationPrincipalSnapshot, val publication: PublicationJournalUse, val draft: DraftJournalUse)
    private class Review(val token: PreparedPublicationReview, val generation: Any, val principal: PublicationPrincipalSnapshot,
        val pin: DraftReviewPin, val target: PublicationReviewTargetV1, val material: PublicationReviewMaterial)
    private class Retry(val token: PreparedPublicationRetry, val generation: Any, val principal: PublicationPrincipalSnapshot,
        val original: PublicationOriginalLinkV1, val queueRevision: Long?, val attempts: Int?,
        val local: DraftLocalSnapshotV1, val start: Long, val end: Long, val registration: PublicationAllocatedOriginal? = null,
        val registrationPin: DraftReviewPin? = null)
    private class Cancel(val token: PreparedUnsentPublicationDiscard, val generation: Any, val principal: PublicationPrincipalSnapshot,
        val original: PublicationOriginalLinkV1, val queueRevision: Long, val local: DraftLocalSnapshotV1, val start: Long, val end: Long)
    private class Dispatch(val original: PublicationOriginalLinkV1, val reviewedLocal: DraftLocalSnapshotV1,
        val attemptedBefore: Boolean, val principal: PublicationPrincipalSnapshot, val start: Long, val end: Long)
    private class Commit(val publication: PublicationJournalContribution, val draft: DraftMutationContribution,
        val attempt: Attempt? = null)
    private class Attempt(val original: PublicationOriginalLinkV1, var publication: PublicationJournalReadback,
        var draft: DraftMutationContribution, val queueRevision: Long, val unsent: Boolean,
        val receipt: PostPublicationReceipt?, val actualReply: ApiReply?, val lease: SessionLease, val store: PrivateStateStore,
        val boundary: SessionBoundary, val origin: String, val actualAccess: AuthenticatedMealPlanningAccess,
        val principal: PublicationPrincipalSnapshot) {
        var archive: StoreMutation.Put? = null
        var subscription: SessionInvalidationSubscription? = null
        var observed = false
        var delivery: PublicationControllerDeliveryTicket? = null
        val application = ActualPublicationApplication()
        fun matches(a: AuthenticatedMealPlanningAccess, b: SessionBoundary) = actualAccess === a &&
            lease === a.lease && store === a.store && boundary === b && origin == a.origin
    }
    /** Called INSIDE the actual controller's composition operation, not from UI or a callback. */
    suspend fun enter(permit: ComposerOperationPermit): PublicationCoordinatorUse {
        fence.requireCurrent(); localNow(); composition.requireComposerPermit(permit, borrower)
        if (active != null) mealFail(FailureReason.CONFLICT)
        val principal = principalAdmission.resolve(permit)
        val pubUse = publications.enter(permit, principal)
        val draftUse = try { drafts.enter(permit, participant ?: mealFail(FailureReason.STALE_SESSION)) }
        catch (failure: Throwable) { publications.leave(pubUse); throw failure }
        return PublicationCoordinatorUse().also { active = Use(it, permit, principal, pubUse, draftUse) }
    }
    fun leave(token: PublicationCoordinatorUse) {
        val use = active?.takeIf { it.token === token } ?: mealFail(FailureReason.STALE_SESSION)
        dispatching = null; committing = null; retryingLocalApplication = false; active = null
        try { drafts.leave(use.draft) } finally { publications.leave(use.publication) }
    }
    fun invalidateReviews() { generation = Any(); review = null; retry = null; cancel = null }
    fun close() {
        if (closed && participant == null) return
        closed = true; invalidateReviews(); participant?.let(drafts::release); publications.release()
        composition.release(borrower); subscription.close()
    }

    /** Restore/inspection reads only. It never migrates, repairs a queue, allocates or sends. */
    suspend fun inspect(token: PublicationCoordinatorUse): PublicationCoordinatorObservation {
        val use = requireUse(token); val entry = publications.read(use.publication)
        val value = publications.value(use.publication, entry)
        val projection = drafts.requirePublicationState(use.draft)
        publications.requireConsistent(use.publication, entry, projection)
        val pending = value.entries.lastOrNull() as? PublicationHistoryEntryV1.PendingOriginal
        val command = if (pending == null) null else exactPending(use, pending.original)
        // Validate every retained terminal against its actual archived queue identity, without
        // turning historical published/cancelled data into a receipt/application proof.
        val cross = cross(use)
        for (history in value.entries) if (history !is PublicationHistoryEntryV1.PendingOriginal) {
            val archived = mealValue(queue.command(access.lease, history.original.commandId)) ?: mealFail(FailureReason.CONFLICT)
            requireUse(token); cross.requireArchivedHistory(history, PublicationQueueObservation.fromActual(archived))
        }
        publications.requireUnchanged(use.publication, entry); requireUse(token)
        var unobserved: PublicationOriginalLinkV1? = null
        heldProposal()?.let { proposal ->
            val original = allocations.requireOriginal(use.permit, drafts, borrower, proposal)
            requireUse(token)
            if (original.originalCanonicalUserId != use.principal.canonicalUserId) mealFail(FailureReason.STALE_SESSION)
            if (pending != null) {
                links.requireSameExactLink(pending.original, original)
                // Registration observation never retires the last actual allocation. Any
                // awaited queue/principal/domain check can become stale. Keep this original
                // until a privately registered actual application Attempt takes it over.
            } else {
                if (value.entries.any { it.original.commandId == original.commandId } ||
                    mealValue(queue.command(access.lease, original.commandId)) != null) mealFail(FailureReason.CONFLICT)
                requireUse(token)
                unobserved = original
            }
        }
        return PublicationCoordinatorObservation(value, command, projection, unobserved,
            allocations.status().takeIf { pending == null })
    }

    suspend fun disclosure(token: PublicationCoordinatorUse, clientDraftId: String): PublicationDisclosure {
        val use = requireUse(token); publicationRecordId(clientDraftId)
        return supplied(use) { prerequisites.disclosure(context(use, clientDraftId)) }
    }
    suspend fun prepareReview(token: PublicationCoordinatorUse, target: PublicationTarget,
        choices: ReviewedPostChoices): PublicationReviewPresentation {
        val use = requireUse(token); requireNoHeldApply(); requireNoHeldProposal(); invalidateReviews()
        val current = inspect(token)
        if (current.pendingCommand != null) mealFail(FailureReason.CONFLICT)
        val pin = drafts.readForPublication(use.draft, publicationRecordUuid(target.clientDraftId), target.localRevision)
        val snapshot = drafts.reviewSnapshot(use.draft, pin)
        val branch = branch(target, snapshot); val body = encoder.encode(target, choices)
        val start = now(); val end = expiry(start)
        val supplied = disclosure(token, snapshot.clientDraftId)
        if (supplied.version != choices.disclosure.version || supplied.text != choices.disclosure.text) mealFail(FailureReason.CONFLICT)
        // The pure link validator binds all raw optional/content/branch material before display.
        links.create(binding(use), unusedProbe(current), start,
            call(unusedProbe(current), body), snapshot, branch, choices.disclosure)
        newPrerequisites(use, body, branch, choices.disclosure, snapshot)
        drafts.requirePin(use.draft, pin); requireUse(token); requireTime(start, end)
        val material = PublicationReviewMaterial(target, body, snapshot, choices.disclosure, use.principal.canonicalUserId, start, end)
        val retained = Review(PreparedPublicationReview(), generation, use.principal, pin, branch, material)
        review = retained
        return PublicationReviewPresentation(retained.token, material)
    }

    /** A fresh explicit confirmation persists ONE original and hold, then dispatches only it.
     * Any failure after enqueue leaves that exact original for inspection/review; no replacement. */
    suspend fun confirm(token: PublicationCoordinatorUse, prepared: PreparedPublicationReview): PublicationCoordinatorObservation {
        val use = requireUse(token); val selected = review?.takeIf { it.token === prepared } ?: mealFail(FailureReason.CONFLICT)
        requireReview(use, selected); requireNoHeldApply()
        val observed = inspect(token); if (observed.pendingCommand != null) mealFail(FailureReason.CONFLICT)
        val entry = publications.read(use.publication); val created = now(); val probeId = unusedProbe(observed)
        val probe = links.create(binding(use), probeId, created, call(probeId, selected.material.exactPostWrite),
            selected.material.snapshot, selected.target, selected.material.disclosure)
        val estimate = publications.preflightOriginal(use.publication, entry, probe)
        val draftEstimate = drafts.preflightHold(use.draft, selected.pin, probe, estimate.second)
        publications.validatePreflight(use.publication, estimate.first, draftEstimate)
        requireReview(use, selected)
        // Creation time and immutable body were captured before allocation; no later retry resets them.
        review = null
        val allocated = allocations.allocate(use.permit, selected.principal, created, selected.material.exactPostWrite,
            selected.material.snapshot, selected.target, selected.material.disclosure, probeId, estimate.second) { status ->
            fence.requireCurrentNow(); fence.showAllocationPending(status); fence.requireCurrentNow()
        }
        val original = allocations.requireOriginal(use.permit, drafts, borrower, allocated)
        val id = original.commandId
        requireUse(token)
        if (id in observed.history.issuedCommandIds || id in observed.drafts.issuedIds ||
            mealValue(queue.command(access.lease, id)) != null) mealFail(FailureReason.CONFLICT)
        requireUse(token)
        val pub = publications.prepareOriginal(use.publication, entry, original)
        val draft = drafts.prepareHold(use.draft, selected.pin, original, publications.publicationReserve(use.publication, pub))
        val changes = preparedChanges(use, pub, draft)
        // Last new-publication prerequisite and exact local pin check before the enqueue effect.
        requireReview(use, selected, retained = false)
        committing = Commit(pub, draft)
        try { mealValue(queue.enqueue(access.lease, original.originalIntentForComparison(), changes)) }
        finally { committing = null }
        requireUse(token)
        val after = inspect(token); requireSamePending(after.history, original)
        val send = Dispatch(original, selected.material.snapshot, false, selected.principal,
            selected.material.preparedAtMillis, selected.material.expiresAtMillis)
        send(use, send)
        return inspect(token)
    }

    suspend fun prepareRetry(token: PublicationCoordinatorUse): PublicationRetryPresentation {
        val use = requireUse(token); requireNoHeldApply(); invalidateReviews()
        val current = inspect(token)
        current.unobservedRegistration?.let { original ->
            val proposal = heldProposal() ?: mealFail(FailureReason.CONFLICT)
            links.requireSameExactLink(original, allocations.requireOriginal(use.permit, drafts, borrower, proposal))
            val local = current.drafts.locals.singleOrNull { it.clientDraftId == original.clientDraftId } ?: mealFail(FailureReason.CONFLICT)
            // A fresh explicit retry displays the EXACT allocated original separately from
            // newer durable local work. The owner derives that original from the actual
            // allocation registry; it never weakens strict initial prepareHold or rebases.
            val pin = drafts.readForPublication(use.draft, local.clientDraftId, local.localRevision)
            val start = now(); val end = expiry(start)
            newPrerequisites(use, original.exactPostWrite, original.historicalReview.target,
                original.historicalReview.displayedDisclosure, local)
            val entry = publications.read(use.publication)
            val estimate = publications.preflightOriginal(use.publication, entry, original)
            val draftEstimate = drafts.preflightOriginalRegistration(use.draft, pin, proposal, estimate.second)
            publications.validatePreflight(use.publication, estimate.first, draftEstimate)
            drafts.requirePin(use.draft, pin)
            val repeated = inspect(token)
            if (repeated.unobservedRegistration == null || heldProposal() !== proposal ||
                !sameLocal(local, repeated.drafts.locals.singleOrNull { it.clientDraftId == original.clientDraftId })) mealFail(FailureReason.CONFLICT)
            requireTime(start, end)
            val selected = Retry(PreparedPublicationRetry(), generation, use.principal, original, null, null, local, start, end, proposal, pin)
            retry = selected
            return PublicationRetryPresentation(selected.token, original, null, local, start, end)
        }
        val original = pending(current.history); val command = current.pendingCommand ?: mealFail(FailureReason.CONFLICT)
        val local = current.drafts.locals.singleOrNull { it.clientDraftId == original.clientDraftId } ?: mealFail(FailureReason.CONFLICT)
        val start = now(); val end = expiry(start)
        if (command.attempts == 0) newPrerequisites(use, original.exactPostWrite, original.historicalReview.target,
            original.historicalReview.displayedDisclosure, local)
        else replayPrerequisites(use, original, command.attempts)
        val repeated = inspect(token); requireSamePending(repeated.history, original)
        if (!sameCommand(command, repeated.pendingCommand) || !sameLocal(local, repeated.drafts.locals.singleOrNull { it.clientDraftId == original.clientDraftId }))
            mealFail(FailureReason.CONFLICT)
        requireTime(start, end)
        val selected = Retry(PreparedPublicationRetry(), generation, use.principal, original, command.localRevision, command.attempts, local, start, end)
        retry = selected
        return PublicationRetryPresentation(selected.token, original, command.attempts, local, start, end)
    }
    suspend fun retryOriginal(token: PublicationCoordinatorUse, prepared: PreparedPublicationRetry): PublicationCoordinatorObservation {
        val use = requireUse(token); val selected = retry?.takeIf { it.token === prepared && it.generation === generation }
            ?: mealFail(FailureReason.CONFLICT)
        principalAdmission.requireCurrent(use.permit, selected.principal); requireTime(selected.start, selected.end)
        if (selected.registration != null) return registerOriginal(use, selected)
        val current = inspect(token); requireSamePending(current.history, selected.original)
        var command = current.pendingCommand ?: mealFail(FailureReason.CONFLICT)
        if (command.localRevision != selected.queueRevision || command.attempts != selected.attempts ||
            !sameLocal(selected.local, current.drafts.locals.singleOrNull { it.clientDraftId == selected.original.clientDraftId })) mealFail(FailureReason.CONFLICT)
        retry = null
        if (command.phase == CommandPhase.IN_FLIGHT) {
            command = mealValue(queue.recoverInterrupted(access.lease, command.commandId, command.localRevision)); requireUse(token)
        }
        val send = Dispatch(selected.original, selected.local, (selected.attempts ?: mealFail(FailureReason.CONFLICT)) > 0, selected.principal, selected.start, selected.end)
        if (command.phase == CommandPhase.NEEDS_RESOLUTION && command.issue in resolvable) {
            requireDispatch(use, send)
            command = mealValue(queue.resumeAfterResolution(access.lease, command.commandId, command.localRevision)); requireUse(token)
        }
        if (command.phase in setOf(CommandPhase.READY, CommandPhase.RETRY_WAIT, CommandPhase.AWAITING_CONFIRMATION)) send(use, send)
        // Receipt-ready is observed but never auto-ACKed by retry; actual application is explicit
        // within the admitted controller action through applyReceipt below.
        return inspect(token)
    }

    private suspend fun registerOriginal(use: Use, selected: Retry): PublicationCoordinatorObservation {
        val proposal = selected.registration ?: mealFail(FailureReason.CONFLICT)
        val pin = selected.registrationPin ?: mealFail(FailureReason.CONFLICT)
        val current = inspect(use.token)
        if (heldProposal() !== proposal || current.unobservedRegistration == null || current.pendingCommand != null ||
            !sameLocal(selected.local, current.drafts.locals.singleOrNull { it.clientDraftId == selected.original.clientDraftId })) mealFail(FailureReason.CONFLICT)
        drafts.requirePin(use.draft, pin)
        newPrerequisites(use, selected.original.exactPostWrite, selected.original.historicalReview.target,
            selected.original.historicalReview.displayedDisclosure, selected.local)
        val entry = publications.read(use.publication)
        val pub = publications.prepareOriginal(use.publication, entry, selected.original)
        val draft = drafts.prepareOriginalRegistration(use.draft, pin, proposal, publications.publicationReserve(use.publication, pub))
        val changes = preparedChanges(use, pub, draft)
        drafts.requirePin(use.draft, pin); requireUse(use.token); requireTime(selected.start, selected.end)
        if (retry !== selected || selected.generation !== generation || heldProposal() !== proposal) mealFail(FailureReason.CONFLICT)
        retry = null; committing = Commit(pub, draft)
        try { mealValue(queue.enqueue(access.lease, selected.original.originalIntentForComparison(), changes)) }
        finally { committing = null }
        val after = inspect(use.token); requireSamePending(after.history, selected.original)
        send(use, Dispatch(selected.original, selected.local, false, selected.principal, selected.start, selected.end))
        return inspect(use.token)
    }

    suspend fun prepareDiscardUnsent(token: PublicationCoordinatorUse): UnsentPublicationPresentation {
        val use = requireUse(token); requireNoHeldApply(); invalidateReviews()
        val current = inspect(token); val original = pending(current.history); val command = current.pendingCommand ?: mealFail(FailureReason.CONFLICT)
        if (command.attempts != 0 || command.phase == CommandPhase.IN_FLIGHT) mealFail(FailureReason.CONFLICT)
        val local = current.drafts.locals.singleOrNull { it.clientDraftId == original.clientDraftId } ?: mealFail(FailureReason.CONFLICT)
        val start = now(); val selected = Cancel(PreparedUnsentPublicationDiscard(), generation, use.principal, original,
            command.localRevision, local, start, expiry(start))
        cancel = selected; return UnsentPublicationPresentation(selected.token, original, local)
    }
    suspend fun discardUnsent(token: PublicationCoordinatorUse, prepared: PreparedUnsentPublicationDiscard): ActualPublicationApplication {
        val use = requireUse(token); val selected = cancel?.takeIf { it.token === prepared && it.generation === generation }
            ?: mealFail(FailureReason.CONFLICT)
        requireNoHeldApply(); principalAdmission.requireCurrent(use.permit, selected.principal); requireTime(selected.start, selected.end)
        val current = inspect(token); requireSamePending(current.history, selected.original)
        val command = current.pendingCommand ?: mealFail(FailureReason.CONFLICT)
        if (command.localRevision != selected.queueRevision || command.attempts != 0 || command.phase == CommandPhase.IN_FLIGHT ||
            !sameLocal(selected.local, current.drafts.locals.singleOrNull { it.clientDraftId == selected.original.clientDraftId })) mealFail(FailureReason.CONFLICT)
        val entry = publications.read(use.publication)
        val pub = publications.prepareCancelledUnsent(use.publication, entry, selected.original)
        val draft = drafts.prepareUnsentRelease(use.draft, selected.original)
        val changes = preparedChanges(use, pub, draft)
        val attempt = retain(use, selected.original, pub, draft, command.localRevision, unsent = true, receipt = null, actualReply = null)
        cancel = null; committing = Commit(pub, draft, attempt)
        try { mealValue(queue.discardUnsent(access.lease, selected.original.commandId, command.localRevision, changes)) }
        finally { committing = null }
        observeApplication(use, attempt); return attempt.application
    }

    /** Only an actual current queue CommandReceipt enters this write path. Decoded history or
     * an independently constructed PostPublicationReceipt is never an input parameter. */
    suspend fun applyReceipt(token: PublicationCoordinatorUse): ActualPublicationApplication {
        val use = requireUse(token); requireNoHeldApply()
        val current = inspect(token); val original = pending(current.history)
        val actual = mealValue(queue.receipt(access.lease, original.commandId)) ?: mealFail(FailureReason.CONFLICT)
        requireUse(token); requireIntent(original, mealValue(queue.intent(access.lease, original.commandId)) ?: mealFail(FailureReason.CONFLICT))
        if (actual.command.operationId != "publishPost" || actual.command.phase != CommandPhase.RECEIPT_READY || actual.command.attempts <= 0 ||
            !sameCommand(actual.command, current.pendingCommand)) mealFail(FailureReason.CONFLICT)
        replayPrerequisites(use, original, actual.command.attempts)
        val receipt = adapter.receipt(original.originalIntentForComparison().call, use.principal.canonicalUserId, actual.reply)
        val repeated = mealValue(queue.receipt(access.lease, original.commandId)) ?: mealFail(FailureReason.CONFLICT)
        requireUse(token)
        if (!sameCommand(actual.command, repeated.command) || !sameReply(actual.reply, repeated.reply)) mealFail(FailureReason.CONFLICT)
        val entry = publications.read(use.publication)
        val pub = publications.preparePublished(use.publication, entry, original,
            HistoricalPublicationReplyV1(receipt.document, receipt.etag, actual.reply.contentType ?: mealFail(FailureReason.INVALID_DATA),
                actual.reply.traceId, actual.reply.retryAfterSeconds))
        val draft = drafts.prepareTerminal(use.draft, original, receipt)
        val changes = preparedChanges(use, pub, draft)
        val attempt = retain(use, original, pub, draft, actual.command.localRevision, unsent = false, receipt = receipt, actualReply = actual.reply)
        invalidateReviews(); committing = Commit(pub, draft, attempt)
        try { mealValue(queue.applyReceipt(access.lease, original.commandId, actual.command.localRevision, changes)) }
        finally { committing = null }
        observeApplication(use, attempt); return attempt.application
    }

    /** Unknown local apply/cancel ACK: retained exact actual attempt + archive + owner-mediated
     * successor witness, followed by NEW changed domain CAS/readback. Never publication HTTP. */
    suspend fun finalizeOriginal(token: PublicationCoordinatorUse): ActualPublicationApplication {
        val use = requireUse(token); val attempt = heldAttempt() ?: mealFail(FailureReason.CONFLICT)
        // A prior failed transfer can retain both copies. The fresh admitted operation checks
        // the real Attempt again before synchronously transferring the duplicate allocation.
        transferAllocation(use, attempt)
        val actual = mealValue(queue.command(access.lease, attempt.original.commandId)) ?: mealFail(FailureReason.CONFLICT)
        requireUse(token)
        if (actual.phase !in setOf(CommandPhase.APPLIED, CommandPhase.DISCARDED)) {
            retryLocalApplication(use, attempt, actual)
            observeApplication(use, attempt)
            return attempt.application
        }
        val beforeArchive = observeArchive(use, attempt)
        publications.observeReadback(use.publication, attempt.publication)
        val draftReadback = drafts.observePublicationReadback(use.draft, attempt.draft)
        val pub = publications.prepareReadbackCAS(use.publication, attempt.publication)
        val draft = drafts.preparePublicationFinalization(use.draft, draftReadback)
        val changes = preparedChanges(use, pub, draft)
        committing = Commit(pub, draft)
        val acknowledgements = try { mealValue(composition.store.commit(access.lease.scope, changes)) }
        finally { committing = null }
        requireUse(token)
        if (acknowledgements.keys != changes.map { it.key }.toSet()) mealFail(FailureReason.STORAGE_FAILURE)
        publications.verifyCommitReadback(use.publication, pub, acknowledgements)
        drafts.verifyPublicationReadback(use.draft, draft, acknowledgements)
        val afterArchive = observeArchive(use, attempt)
        if (!sameRecord(beforeArchive, afterArchive)) mealFail(FailureReason.CONFLICT)
        observeApplication(use, attempt); return attempt.application
    }

    /** Before-COMMIT application failure: original queue entry is still at the exact captured
     * receipt/zero-attempt CAS. Reprepare only that local transition, never resend HTTP or reuse
     * a stale owner Put. An already archived entry MUST use the exact readback path above. */
    private suspend fun retryLocalApplication(use: Use, attempt: Attempt, command: CommandView) {
        if (attempt.observed || command.localRevision != attempt.queueRevision ||
            command.operationId != "publishPost" || !attempt.matches(access, boundary)) mealFail(FailureReason.CONFLICT)
        val current = inspect(use.token); requireSamePending(current.history, attempt.original)
        requireIntent(attempt.original, mealValue(queue.intent(access.lease, attempt.original.commandId)) ?: mealFail(FailureReason.CONFLICT))
        requireUse(use.token)
        val entry = publications.read(use.publication)
        val pub: PublicationJournalContribution
        if (attempt.unsent) {
            if (command.attempts != 0 || command.phase == CommandPhase.IN_FLIGHT) mealFail(FailureReason.CONFLICT)
            pub = publications.prepareCancelledUnsent(use.publication, entry, attempt.original)
            attempt.draft = drafts.prepareUnsentRelease(use.draft, attempt.original)
        } else {
            if (command.phase != CommandPhase.RECEIPT_READY || command.attempts <= 0) mealFail(FailureReason.CONFLICT)
            val actual = mealValue(queue.receipt(access.lease, attempt.original.commandId)) ?: mealFail(FailureReason.CONFLICT)
            requireUse(use.token)
            if (!sameCommand(command, actual.command) || !sameReply(attempt.actualReply ?: mealFail(FailureReason.CONFLICT), actual.reply))
                mealFail(FailureReason.CONFLICT)
            replayPrerequisites(use, attempt.original, command.attempts)
            val receipt = adapter.receipt(attempt.original.originalIntentForComparison().call, use.principal.canonicalUserId, actual.reply)
            val retained = attempt.receipt ?: mealFail(FailureReason.CONFLICT)
            if (receipt.etag != retained.etag || !receipt.document.encodeUtf8().contentEquals(retained.document.encodeUtf8())) mealFail(FailureReason.CONFLICT)
            pub = publications.preparePublished(use.publication, entry, attempt.original,
                HistoricalPublicationReplyV1(receipt.document, receipt.etag, actual.reply.contentType ?: mealFail(FailureReason.INVALID_DATA),
                    actual.reply.traceId, actual.reply.retryAfterSeconds))
            attempt.draft = drafts.prepareTerminal(use.draft, attempt.original, receipt)
        }
        val changes = preparedChanges(use, pub, attempt.draft)
        val previous = attempt.publication
        attempt.publication = publications.retainReadback(use.publication, pub)
        publications.forgetReadback(previous)
        val repeated = mealValue(queue.command(access.lease, command.commandId)); requireUse(use.token)
        if (!sameCommand(command, repeated)) mealFail(FailureReason.CONFLICT)
        retryingLocalApplication = true; committing = Commit(pub, attempt.draft, attempt)
        try {
            if (attempt.unsent) mealValue(queue.discardUnsent(access.lease, command.commandId, command.localRevision, changes))
            else mealValue(queue.applyReceipt(access.lease, command.commandId, command.localRevision, changes))
        } finally { retryingLocalApplication = false; committing = null }
    }

    /** Actual retained result data, still NOT proof of final caller/StateFlow delivery. No witness
     * retirement API is exposed until the real controller's final delivery protocol is bound. */
    suspend fun appliedResult(token: PublicationCoordinatorUse, application: ActualPublicationApplication): PostPublicationReceipt? {
        val use = requireUse(token); val attempt = heldAttempt()?.takeIf { it.application === application && it.observed }
            ?: mealFail(FailureReason.CONFLICT)
        observeApplication(use, attempt); requireUse(token)
        return attempt.receipt
    }

    /** Captured inside the actual operation, checked again on the arbitrary caller dispatcher.
     * It is not itself receipt provenance or a caller ACK. */
    suspend fun captureDelivery(token: PublicationCoordinatorUse): PublicationDeliveryWitness {
        val use = requireUse(token)
        val witness = deliveryAdmission.capture(use.permit, use.principal)
        requireUse(token)
        if (!witness.isCurrent()) mealFail(FailureReason.STALE_SESSION)
        return witness
    }

    /** Registration is actual purpose-fixed permit admission, not a method on captured data.
     * The controller immediately binds this gate to its already revocable operation ticket.
     * No await follows allocation here; any local tail failure cancels the unbound gate. */
    suspend fun registerDelivery(token: PublicationCoordinatorUse, witness: PublicationDeliveryWitness): PublicationDeliveryGate {
        val use = requireUse(token)
        val gate = deliveryAdmission.registerDelivery(use.permit, use.principal, witness)
        try {
            currentCoroutineContext().ensureActive(); fence.requireCurrentNow(); localNow()
            principalAdmission.requireCurrentNow(use.principal)
            return gate
        } catch (failure: Throwable) { gate.cancel(); throw failure }
    }

    suspend fun armApplicationDelivery(token: PublicationCoordinatorUse, application: ActualPublicationApplication,
        ticket: PublicationControllerDeliveryTicket) {
        val use = requireUse(token); val attempt = heldAttempt()?.takeIf { it.application === application }
            ?: mealFail(FailureReason.CONFLICT)
        observeApplication(use, attempt)
        if (!fence.ownsApplicationDelivery(ticket)) mealFail(FailureReason.STALE_SESSION)
        // Ticket is minted/registered by the REAL enclosing controller, but remains unacknowledged
        // until its non-suspending caller tail. Nothing is retired or stored on disk here.
        attempt.delivery = ticket
    }

    /** A later actual operation may retire memory only after the previous public final tail
     * delivered this exact same-lease real application. No GET/history/opaque application token
     * can satisfy the controller-owned ticket registry. Cancellation keeps evidence recoverable. */
    suspend fun retireDeliveredApplication(token: PublicationCoordinatorUse) {
        val use = requireUse(token); val attempt = heldAttempt() ?: return
        val ticket = attempt.delivery ?: return
        if (!ticket.deliveredApplication(attempt.application)) return
        observeApplication(use, attempt)
        val retirement = drafts.preparePublicationEvidenceRetirement(use.draft, attempt.draft)
        // The final retirement tail is a single identity-dispatcher turn. All suspension and
        // callbacks precede removal; no cancellation window strands one peer's evidence.
        currentCoroutineContext().ensureActive(); fence.requireCurrentNow(); localNow()
        principalAdmission.requireCurrentNow(use.principal)
        if (!ticket.deliveredApplication(attempt.application)) mealFail(FailureReason.STALE_SESSION)
        drafts.retirePublicationEvidence(use.draft, retirement)
        publications.forgetReadback(attempt.publication)
        removeAttempt(attempt)
    }

    private suspend fun preparedChanges(use: Use, pub: PublicationJournalContribution, draft: DraftMutationContribution): List<StoreMutation> {
        val projection = drafts.publicationProjection(use.draft, draft)
        publications.validatePrepared(use.publication, pub, projection)
        val publication = publications.mutation(use.publication, pub)
        val draftChanges = drafts.mutations(use.draft, draft)
        if (draftChanges.size != 1 || draftChanges.single().key == publication.key) mealFail(FailureReason.CONFLICT)
        requireUse(use.token); return listOf(publication) + draftChanges
    }
    private fun checkCommit(batch: List<StoreMutation>) {
        fence.requireCurrentNow(); localNow()
        val use = active ?: mealFail(FailureReason.STALE_SESSION); principalAdmission.requireCurrentNow(use.principal)
        val owned = batch.any { publications.ownsKey(it.key) || it.key.collection == "mealflow.post-drafts.v1" }
        if (!owned) return // Queue-owned claim/outcome/index writes remain gated by the real queue.
        val commit = committing ?: mealFail(FailureReason.CONFLICT)
        publications.beforeCommit(use.publication, commit.publication, batch)
        drafts.beforePublicationCommit(use.draft, commit.draft, batch)
        commit.attempt?.let { attempt ->
            val archive = batch.filterIsInstance<StoreMutation.Put>().singleOrNull {
                it.key == RecordKey("feedme.command.metadata", attempt.original.commandId)
            } ?: mealFail(FailureReason.CONFLICT)
            if (archive.expectedRevision != attempt.queueRevision) mealFail(FailureReason.CONFLICT)
            if (attempt.archive != null && attempt.archive !== archive && !retryingLocalApplication) mealFail(FailureReason.CONFLICT)
            attempt.archive = archive
        }
    }
    private suspend fun retain(use: Use, original: PublicationOriginalLinkV1, pub: PublicationJournalContribution,
        draft: DraftMutationContribution, queueRevision: Long, unsent: Boolean, receipt: PostPublicationReceipt?, actualReply: ApiReply?): Attempt {
        requireNoHeldApply()
        val witness = publications.retainReadback(use.publication, pub)
        val result = Attempt(original, witness, draft, queueRevision, unsent, receipt, actualReply, access.lease, access.store,
            boundary, access.origin, access, use.principal)
        while (true) { val old = attempts.load(); if (attempts.compareAndSet(old, old + result)) break }
        val installed = boundary.onInvalidated(access.lease) { removeAttempt(result) }
        if (!boundary.isCurrent(access.lease)) { installed.close(); removeAttempt(result); mealFail(FailureReason.STALE_SESSION) }
        result.subscription = installed
        // No await between checking the ACTUAL registered Attempt and transferring the
        // retained original. A failed transfer leaves BOTH registries intact for recovery.
        transferAllocation(use, result)
        return result
    }
    private fun transferAllocation(use: Use, attempt: Attempt) {
        fence.requireCurrentNow(); localNow(); principalAdmission.requireCurrentNow(use.principal)
        heldProposal()?.let { allocations.transferToActualApplication(use.principal, it, attempt.application) }
    }
    private suspend fun observeApplication(use: Use, attempt: Attempt) {
        requireUse(use.token)
        if (!attempt.matches(access, boundary) || heldAttempt() !== attempt || attempt.original.originalCanonicalUserId != use.principal.canonicalUserId)
            mealFail(FailureReason.STALE_SESSION)
        val archive = observeArchive(use, attempt)
        publications.observeReadback(use.publication, attempt.publication)
        drafts.observePublicationReadback(use.draft, attempt.draft)
        val current = inspect(use.token)
        val actual = current.history.entries.singleOrNull { it.original.commandId == attempt.original.commandId } ?: mealFail(FailureReason.CONFLICT)
        links.requireSameExactLink(actual.original, attempt.original)
        if (attempt.unsent && actual !is PublicationHistoryEntryV1.CancelledUnsentHistorical ||
            !attempt.unsent && actual !is PublicationHistoryEntryV1.PublishedHistorical) mealFail(FailureReason.CONFLICT)
        if (actual is PublicationHistoryEntryV1.PublishedHistorical) {
            val receipt = attempt.receipt ?: mealFail(FailureReason.CONFLICT)
            if (receipt.etag != actual.reply.etag || !receipt.document.encodeUtf8().contentEquals(actual.reply.exactPost.encodeUtf8())) mealFail(FailureReason.CONFLICT)
        }
        val again = observeArchive(use, attempt)
        if (!sameRecord(archive, again)) mealFail(FailureReason.CONFLICT)
        requireUse(use.token); attempt.observed = true
    }
    private suspend fun observeArchive(use: Use, attempt: Attempt): PrivateRecord {
        requireUse(use.token)
        if (!attempt.matches(access, boundary)) mealFail(FailureReason.STALE_SESSION)
        val put = attempt.archive ?: mealFail(FailureReason.CONFLICT)
        val expected = put.expectedRevision ?: mealFail(FailureReason.CONFLICT)
        if (expected == Long.MAX_VALUE || expected != attempt.queueRevision) mealFail(FailureReason.CONFLICT)
        val first = mealValue(composition.store.read(access.lease.scope, put.key)) ?: mealFail(FailureReason.CONFLICT)
        requireUse(use.token)
        if (first.schemaVersion != put.schemaVersion || first.revision != expected + 1 ||
            !first.payload.copyForCodec().contentEquals(put.payload.copyForCodec())) mealFail(FailureReason.CONFLICT)
        val queueState = mealValue(queue.command(access.lease, attempt.original.commandId)) ?: mealFail(FailureReason.CONFLICT)
        requireUse(use.token)
        if (queueState.operationId != "publishPost" || queueState.phase != (if (attempt.unsent) CommandPhase.DISCARDED else CommandPhase.APPLIED) ||
            queueState.localRevision != first.revision || (if (attempt.unsent) queueState.attempts != 0 else queueState.attempts <= 0)) mealFail(FailureReason.CONFLICT)
        val repeated = mealValue(composition.store.read(access.lease.scope, put.key))
        requireUse(use.token); if (!sameRecord(first, repeated)) mealFail(FailureReason.CONFLICT)
        return first
    }

    private suspend fun requireReview(use: Use, selected: Review, retained: Boolean = true) {
        requireUse(use.token)
        if (selected.generation !== generation || retained && review !== selected) mealFail(FailureReason.CONFLICT)
        principalAdmission.requireCurrent(use.permit, selected.principal)
        requireTime(selected.material.preparedAtMillis, selected.material.expiresAtMillis)
        drafts.requirePin(use.draft, selected.pin)
        newPrerequisites(use, selected.material.exactPostWrite, selected.target, selected.material.disclosure, selected.material.snapshot)
        drafts.requirePin(use.draft, selected.pin); requireUse(use.token)
        requireTime(selected.material.preparedAtMillis, selected.material.expiresAtMillis)
    }
    private suspend fun send(use: Use, send: Dispatch) {
        requireDispatch(use, send)
        dispatching = send
        try { mealValue(queue.dispatchConfirmed(access.lease, send.original.commandId)) }
        finally { dispatching = null }
        requireUse(use.token)
    }
    private suspend fun requireDispatch(use: Use, send: Dispatch) {
        requireUse(use.token); principalAdmission.requireCurrent(use.permit, send.principal)
        requireTime(send.start, send.end)
        if (!composition.online()) mealFail(FailureReason.OFFLINE)
        val current = inspect(use.token); requireSamePending(current.history, send.original)
        val command = current.pendingCommand ?: mealFail(FailureReason.CONFLICT)
        val local = current.drafts.locals.singleOrNull { it.clientDraftId == send.original.clientDraftId } ?: mealFail(FailureReason.CONFLICT)
        if (!sameLocal(send.reviewedLocal, local)) mealFail(FailureReason.CONFLICT)
        if (send.attemptedBefore) {
            if (command.attempts <= 0) mealFail(FailureReason.CONFLICT)
            replayPrerequisites(use, send.original, command.attempts)
        } else {
            // The queue's actual in-flight registration increments attempts before beforeTransport.
            if (command.attempts != 0 && !(dispatching === send && command.attempts == 1 && command.phase == CommandPhase.IN_FLIGHT))
                mealFail(FailureReason.CONFLICT)
            drafts.requirePublicationNewDispatch(use.draft, send.original, send.reviewedLocal)
            newPrerequisites(use, send.original.exactPostWrite, send.original.historicalReview.target,
                send.original.historicalReview.displayedDisclosure, local)
            drafts.requirePublicationNewDispatch(use.draft, send.original, send.reviewedLocal)
        }
        requireUse(use.token); requireTime(send.start, send.end)
    }
    private suspend fun newPrerequisites(use: Use, body: WireDocument, target: PublicationReviewTargetV1,
        disclosure: PublicationDisclosure, local: DraftLocalSnapshotV1) {
        requireNewAssociation(target, local)
        val actual = supplied(use) { prerequisites.disclosure(context(use, local.clientDraftId)) }
        if (actual.version != disclosure.version || actual.text != disclosure.text) mealFail(FailureReason.CONFLICT)
        supplied(use) { prerequisites.requireNew(context(use, local.clientDraftId), PublicationNewPrerequisiteCheck(body, target, disclosure, local)) }
    }
    private suspend fun replayPrerequisites(use: Use, original: PublicationOriginalLinkV1, attempts: Int) {
        if (attempts <= 0 || original.originalCanonicalUserId != use.principal.canonicalUserId) mealFail(FailureReason.CONFLICT)
        supplied(use) { prerequisites.requireOriginalReplay(context(use, original.clientDraftId), PublicationReplayPrerequisiteCheck(original, attempts)) }
    }
    private suspend fun exactPending(use: Use, original: PublicationOriginalLinkV1): CommandView {
        val before = mealValue(queue.command(access.lease, original.commandId)) ?: mealFail(FailureReason.CONFLICT)
        requireUse(use.token)
        val intent = mealValue(queue.intent(access.lease, original.commandId)) ?: mealFail(FailureReason.CONFLICT)
        requireUse(use.token); cross(use).requirePendingIntent(original, PublicationQueueObservation.fromActual(before), intent)
        val after = mealValue(queue.command(access.lease, original.commandId))
        requireUse(use.token); if (!sameCommand(before, after)) mealFail(FailureReason.CONFLICT)
        return before
    }
    private suspend fun requireUse(token: PublicationCoordinatorUse): Use {
        currentCoroutineContext().ensureActive(); fence.requireCurrentNow(); localNow()
        val use = active?.takeIf { it.token === token } ?: mealFail(FailureReason.STALE_SESSION)
        principalAdmission.requireCurrentNow(use.principal)
        composition.requireComposerPermit(use.permit, borrower)
        currentCoroutineContext().ensureActive(); fence.requireCurrentNow(); localNow()
        if (active !== use) mealFail(FailureReason.STALE_SESSION)
        principalAdmission.requireCurrent(use.permit, use.principal)
        currentCoroutineContext().ensureActive(); fence.requireCurrentNow(); localNow()
        if (active !== use) mealFail(FailureReason.STALE_SESSION)
        principalAdmission.requireCurrentNow(use.principal); return use
    }
    private suspend fun <T> supplied(use: Use, action: suspend () -> PortResult<T>): T {
        requireUse(use.token)
        val result = try { action() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: MealFailure) { PortResult.Failure(failure.reason) }
        catch (_: Exception) { PortResult.Failure(FailureReason.UNAVAILABLE) }
        requireUse(use.token); return mealValue(result)
    }
    private fun localNow() { if (closed || !boundary.isCurrent(access.lease)) mealFail(FailureReason.STALE_SESSION) }
    private fun context(use: Use, root: String) = PublicationPrerequisiteContext(access.lease, boundary, access.origin,
        access.lease.scope.environment, use.principal, root)
    private fun binding(use: Use) = PublicationJournalBindingData(access.lease.scope.environment, publicationRecordUuid(access.origin), access.origin, use.principal.canonicalUserId)
    private fun cross(use: Use) = PublicationCrossRecordValidator(PostPublicationJournalCodecV1(binding(use), policy, links), links, snapshots, policy, draftPolicy)
    private fun pending(value: PublicationJournalV1) = (value.entries.lastOrNull() as? PublicationHistoryEntryV1.PendingOriginal)?.original
        ?: mealFail(FailureReason.CONFLICT)
    private fun requireSamePending(value: PublicationJournalV1, original: PublicationOriginalLinkV1) = links.requireSameExactLink(pending(value), original)
    private fun call(id: String, body: WireDocument) = ApiCall("publishPost", body = PrivateBytes(body.encodeUtf8()), idempotencyKey = SecretText(id))
    private fun requireIntent(original: PublicationOriginalLinkV1, actual: CommandIntent) {
        if (actual.commandId != original.commandId || actual.originBinding != original.originBinding || actual.dependencyCommandIds.isNotEmpty()) mealFail(FailureReason.CONFLICT)
        requireSameCall(original.originalIntentForComparison().call, actual.call)
    }
    private fun requireSameCall(a: ApiCall, b: ApiCall) {
        if (a.operationId != b.operationId || a.pathParameters != b.pathParameters || a.queryParameters != b.queryParameters || a.ifMatch != b.ifMatch ||
            a.idempotencyKey?.use { it } != b.idempotencyKey?.use { it } ||
            a.body?.copyForCodec()?.toList() != b.body?.copyForCodec()?.toList()) mealFail(FailureReason.CONFLICT)
    }
    private fun branch(target: PublicationTarget, snapshot: DraftLocalSnapshotV1): PublicationReviewTargetV1 {
        if (publicationRecordUuid(target.clientDraftId) != snapshot.clientDraftId || target.localRevision != snapshot.localRevision) mealFail(FailureReason.CONFLICT)
        return when (target) {
            is PublicationTarget.DirectLocal -> PublicationReviewTargetV1.DirectLocal.also { requireNewAssociation(it, snapshot) }
            is PublicationTarget.SavedDraft -> {
                val observed = snapshot.serverAssociation as? DraftServerAssociationV1.Observed ?: mealFail(FailureReason.CONFLICT)
                PublicationReviewTargetV1.SavedDraft(publicationRecordUuid(target.draftId), target.draftVersion, target.etag, observed.exactPostDraft)
                    .also { requireNewAssociation(it, snapshot) }
            }
        }
    }
    private fun requireNewAssociation(target: PublicationReviewTargetV1, snapshot: DraftLocalSnapshotV1) {
        when (target) {
            PublicationReviewTargetV1.DirectLocal -> if (snapshot.serverAssociation !is DraftServerAssociationV1.NotObserved) mealFail(FailureReason.CONFLICT)
            is PublicationReviewTargetV1.SavedDraft -> {
                val actual = snapshot.serverAssociation as? DraftServerAssociationV1.Observed ?: mealFail(FailureReason.CONFLICT)
                val json = publicationRecordJson(actual.exactPostDraft)
                if (actual.etag != target.reviewedETag || publicationRecordString(json, "id") != target.draftId ||
                    publicationRecordBigint(json.getValue("version")) != target.draftVersion.decimal || publicationRecordString(json, "status") != "draft" ||
                    !publicationRecordSame(json, publicationRecordJson(target.exactReviewedDraft))) mealFail(FailureReason.CONFLICT)
            }
        }
    }
    private fun unusedProbe(current: PublicationCoordinatorObservation): String {
        val used = current.drafts.issuedIds.toSet() + current.history.issuedCommandIds
        for (index in 1..used.size + 1) {
            val id = "eeeeeeee-eeee-4eee-8eee-${index.toString(16).padStart(12, '0')}"
            if (id !in used) return id
        }
        mealFail(FailureReason.UNAVAILABLE)
    }
    private fun sameLocal(a: DraftLocalSnapshotV1, b: DraftLocalSnapshotV1?) = b != null && snapshots.encode(a).copyForCodec().contentEquals(snapshots.encode(b).copyForCodec())
    private fun sameCommand(a: CommandView, b: CommandView?) = b != null && a.commandId == b.commandId && a.operationId == b.operationId &&
        a.localRevision == b.localRevision && a.phase == b.phase && a.attempts == b.attempts && a.issue == b.issue && a.retryAtMillis == b.retryAtMillis
    private fun sameReply(a: ApiReply, b: ApiReply) = a.status == b.status && a.etag == b.etag && a.contentType == b.contentType &&
        a.traceId == b.traceId && a.retryAfterSeconds == b.retryAfterSeconds && a.body?.copyForCodec()?.toList() == b.body?.copyForCodec()?.toList()
    private fun sameRecord(a: PrivateRecord, b: PrivateRecord?) = b != null && a.revision == b.revision && a.schemaVersion == b.schemaVersion &&
        a.payload.copyForCodec().contentEquals(b.payload.copyForCodec())
    private fun now() = composition.clock.nowMillis().also { if (it < 0) mealFail(FailureReason.INVALID_DATA) }
    private fun expiry(start: Long): Long { if (start > Long.MAX_VALUE - policy.reviewLifetimeMillis) mealFail(FailureReason.UNAVAILABLE); return start + policy.reviewLifetimeMillis }
    private fun requireTime(start: Long, end: Long) { val current = now(); if (current < start || current >= end) mealFail(FailureReason.CONFLICT) }
    private fun heldAttempt() = attempts.load().singleOrNull { it.matches(access, boundary) }
    private fun requireNoHeldApply() { if (heldAttempt() != null) mealFail(FailureReason.CONFLICT) }
    private fun heldProposal() = allocations.retained()
    private fun requireNoHeldProposal() = allocations.requireNone()
    /** Explicit local cancellation fencing, not a composition operation or command mutation.
     * This can run while a noncooperative ID invocation still holds the operation mutex. */
    fun abandonUnreturnedAllocation(token: PreparedPublicationAllocationAbandon): PublicationAllocationStatus? {
        localNow(); allocations.abandonUnreturned(token); invalidateReviews()
        return allocations.status()
    }
    companion object {
        private val attempts = AtomicReference<List<Attempt>>(emptyList())
        private val resolvable = setOf(CommandIssue.AUTH_REQUIRED, CommandIssue.NOT_CONFIGURED, CommandIssue.DOMAIN_RECHECK_REQUIRED,
            CommandIssue.OFFLINE, CommandIssue.TEMPORARILY_UNAVAILABLE)
        /** Closed concrete registry lookup, not a caller-supplied link/Boolean or public proof
         * constructor. Only retain() after actual queue receipt/zero-attempt CAS and actual
         * owner contributions registers this identity. Fresh recovery may renew generation,
         * but mapped issuer/owner/account and the exact retained session must still match. */
        fun requireActualAllocationTransfer(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary,
            principal: PublicationPrincipalSnapshot, application: ActualPublicationApplication): PublicationOriginalLinkV1 {
            val attempt = attempts.load().singleOrNull { it.application === application && it.matches(access, boundary) }
                ?: mealFail(FailureReason.CONFLICT)
            if (!boundary.isCurrent(access.lease) || !attempt.principal.binding.sameRetainedSession(principal.binding) ||
                attempt.principal.issuer !== principal.issuer || attempt.principal.retainedOwner !== principal.retainedOwner ||
                attempt.original.originalCanonicalUserId != principal.canonicalUserId) mealFail(FailureReason.STALE_SESSION)
            return attempt.original
        }
        private fun removeAttempt(attempt: Attempt) {
            while (true) { val old = attempts.load(); if (attempts.compareAndSet(old, old.filterNot { it === attempt })) break }
            attempt.subscription?.close(); attempt.subscription = null
        }
    }
    override fun toString() = "PostComposerCoordinator(<redacted>)"
}
