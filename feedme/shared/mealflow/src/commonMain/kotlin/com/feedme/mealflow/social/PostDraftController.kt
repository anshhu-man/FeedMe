package com.feedme.mealflow.social

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.kitchen.PostDraftCommandHooks
import com.feedme.mealflow.*
import com.feedme.sync.*
import com.feedme.transport.MobileRequestValidator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Retained private text editor over ONE borrowed composition/queue. No constructor I/O, native
 * owner, worker, automatic save or publication. A read/restore is never a mutation receipt. */
@OptIn(ExperimentalAtomicApi::class, ExperimentalTime::class)
class PostDraftController internal constructor(private val composition: MealKitchenComposition,
    private val ids: MealOperationIds, private val policy: PostDraftClientPolicy,
    journalOwner: PostDraftJournalOwner = PostDraftJournalOwner(composition, policy),
    private val reviewedPrerequisites: ReviewedDraftSavePrerequisites? = null) {
    private val access = composition.access
    private val boundary = composition.boundary
    private val dispatcher = composition.dispatcher
    private val origin = uuid(access.origin)
    private val journal = journalOwner.also { it.requireComposition(composition, policy) }
    private val queue = composition.kitchen.commands
    private val adapter = PostDraftAdapter(policy)
    private val snapshots = journal.configuredPublicationPolicy?.let { DraftLocalSnapshotCodecV1(policy, it) }
    private val reviewedAdapter = journal.configuredPublicationPolicy?.let { ReviewedPostDraftAdapter(minOf(policy.maxResponseBytes, it.maxResponseBytes)) }
    private val mutex = Mutex()
    // Only pure input staging and the final non-suspending publication/delivery tail share this
    // lock. Never hold it over store/network work or a dispatcher handoff.
    private val feedbackPublication = Mutex()
    private val identity = Any()
    private var generation = Any()
    private var active: Any? = null
    private var operation: Any? = null
    private var delivery: DraftControllerDelivery? = null
    private var closed = false
    private var activeJournalUse: DraftJournalUse? = null
    private var last = DraftControllerRecord(PostRecord(0))
    // The initial legacy-shaped placeholder is not an observation. Unknown/failed reads
    // remain fail-closed for the separate current-format-only preview route.
    private var journalObserved = false
    private var view: CommandView? = null
    private var unobservedDispatch: DispatchProjection? = null
    private var validationFeedback: ValidationFeedback? = null
    private var publicationForReturn: Publication? = null
    private var selectedId: String? = null
    private var suppressedPublicationRoots = emptySet<String>()
    private var screen = PostDraftScreen.HIDDEN
    private var page = emptyList<PostDraftObservation>()
    private var cursor: String? = null
    private var head: String? = null
    private val seenIds = mutableSetOf<String>()
    private val seenClients = mutableSetOf<String>()
    private val seenCursors = mutableSetOf<String>()
    private var historical = true
    private var acknowledged = false
    private var consent: PreparedPostDraftDiscard? = null
    private var applying: DraftControllerApply? = null
    private var editForReturn: DraftControllerEdit? = null
    private var applyForReturn: DraftControllerApply? = null
    private var restoredRetentionReview: RestoredRetentionReview? = null
    private val restoredRetentionNavigation = AtomicReference<Any>(Any())
    private var restoredRetentionCommit: Pair<Any, RestoredRetentionReview>? = null
    private var upgradeReview: UpgradeReview? = null
    private var selectionFence: DraftReviewedSelectionFence? = null
    private var entryPublications: PostComposerController? = null
    private var saveReview: SaveReview? = null
    private var saveRetry: SaveRetry? = null
    private var reviewedDispatch: ReviewedDispatch? = null
    private var reviewedWitnessForReturn: PublicationDeliveryWitness? = null
    private var reviewedGateForReturn: PublicationDeliveryGate? = null
    private var reviewedReturnCapture: ReviewedReturn? = null
    private var reviewedRevocation: PublicationDeliveryRevocationSubscription? = null
    private val mutable = MutableStateFlow(PostDraftState.empty())
    val states: StateFlow<PostDraftState> = mutable.asStateFlow()
    private val borrower = composition.bind(MealKitchenFeature.POST_DRAFTS, object : MealKitchenHooks {
        override suspend fun checkCurrent() = checkActive()
        override fun beforeCommit(mutations: List<StoreMutation>) {
            reviewedDispatch?.let(::requireReviewedDispatchNow)
            this@PostDraftController.beforeCommit(mutations)
            applying?.let { proof -> if (mutations.any { journal.ownsLegacyKey(it.key) }) {
                if (mutations.filter { journal.ownsLegacyKey(it.key) }.singleOrNull() !== proof.mutation) mealFail(FailureReason.CONFLICT)
                val archive = mutations.filterIsInstance<StoreMutation.Put>().singleOrNull {
                    it.key == RecordKey("feedme.command.metadata", proof.original.id)
                } ?: mealFail(FailureReason.CONFLICT)
                if (archive.expectedRevision != proof.receiptRevision) mealFail(FailureReason.CONFLICT)
                proof.archive = archive
            } }
        }
        override suspend fun beforeTransport(call: ApiCall) {
            val dispatch = reviewedDispatch ?: return
            if (call.operationId != "updatePostDraft") return
            if (!postSameCall(dispatch.original.historicalCall(), call)) mealFail(FailureReason.CONFLICT)
            requireReviewedDispatchCurrent(dispatch, eligibility = true)
        }
        override suspend fun afterTransport(call: ApiCall, reply: ApiReply) {
            reviewedDispatch?.let { dispatch -> if (call.operationId == "updatePostDraft") {
                if (!postSameCall(dispatch.original.historicalCall(), call)) mealFail(FailureReason.CONFLICT)
                // An actual remote effect may already have happened. Do not rerun new-save
                // eligibility to turn a receipt into a fictitious never-attempted command.
                requireReviewedDispatchCurrent(dispatch, eligibility = false)
            } }
            if (call.operationId !in setOf("getPostDraft", "listPostDrafts") || reply.status !in setOf(401, 403, 404, 409, 410, 412)) return
            val bytes = reply.body?.copyForCodec() ?: return
            if (bytes.size > policy.maxResponseBytes || CanonicalResponseBinder().bind(call.operationId, reply.status, bytes,
                    reply.contentType, reply.traceId) !is ResponseBindingResult.Accepted) return
            checkActive(); consent = null; acknowledged = false; historical = true
            if (call.operationId == "getPostDraft") call.pathParameters["draftId"]?.let { id ->
                // Bound to downloaded identities; arbitrary failed IDs cannot grow a registry.
                if (last.locals.any { it.server?.let { body -> postString(body, "id") == id } == true }) PostDraftHeld.redact(access, boundary, id)
                page = page.filterNot { it.id == id }
            } else { page = emptyList(); cursor = null; head = null }
            publish(PostDraftPhase.ERROR, PostDraftIssue.RECONCILIATION_REQUIRED)
        }
        override val postDraftCommands = object : PostDraftCommandHooks {
            override suspend fun executionDecision(lease: SessionLease, intent: CommandIntent): ExecutionDecision = try {
                if (lease !== access.lease) mealFail(FailureReason.STALE_SESSION)
                val entry = read(); val original = entry.value.command ?: mealFail(FailureReason.CONFLICT)
                requireIntent(original, intent); requireDispatch(original, entry.value); same(entry, read())
                if (original.current is PostDraftCommandV2.ReviewedPatch)
                    requireReviewedDispatchCurrent(reviewedDispatch ?: mealFail(FailureReason.NOT_CONFIGURED), eligibility = true)
                ExecutionDecision.Ready
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { ExecutionDecision.Wait(when ((failure as? MealFailure)?.reason) {
                FailureReason.OFFLINE -> CommandIssue.OFFLINE
                FailureReason.STALE_SESSION, FailureReason.UNAUTHENTICATED -> CommandIssue.AUTH_REQUIRED
                else -> CommandIssue.DOMAIN_RECHECK_REQUIRED
            }) }
            override suspend fun observeReply(lease: SessionLease, intent: CommandIntent, reply: ApiReply): PortResult<Unit> {
                if (lease !== access.lease) return PortResult.Failure(FailureReason.STALE_SESSION)
                val original = read().value.command ?: mealFail(FailureReason.CONFLICT)
                requireIntent(original, intent)
                if (original.current is PostDraftCommandV2.ReviewedPatch)
                    requireReviewedDispatchCurrent(reviewedDispatch ?: mealFail(FailureReason.NOT_CONFIGURED), eligibility = false)
                // Rejection remains the exact queue Problem; this hook never promotes it to a receipt.
                if (reply.status in 200..299) original.receipt(adapter, reviewedAdapter, reply)
                return PortResult.Value(Unit)
            }
        }
    })
    // Preserve unavailable-controller construction for an already-stale/closed composition.
    // A live registration itself performs no operation, domain check, claim or I/O.
    private val journalParticipant = if (boundary.isCurrent(access.lease) && borrower.hooks != null) journal.register(borrower) else null
    private val reviewedPrincipal = reviewedPrerequisites?.let { ReviewedDraftPrincipalAdmission(composition, borrower, it.principals) }
    private val reviewedDelivery = reviewedPrerequisites?.let { PrincipalDeliveryAdmission(reviewedPrincipal!!, it.delivery) }
    private val reviewedEncoder = journal.configuredPublicationPolicy?.let(::ReviewedDraftSaveEncoder)
    init {
        if (access.lease.scope.actorKind == ActorKind.ACCOUNT) {
            journalParticipant?.let { journal.registerDraftPublicationObserver(it, ::publicationRootChanging) }
        }
    }
    private val subscription = boundary.onInvalidated(access.lease) { redact() }

    /** Read-only selected-root projection. The final existing controller fence owns return;
     * data is not a review, policy grant, migration permission or acknowledgement. */
    internal suspend fun inspectReviewedSelection(clientDraftId: String, expectedRevision: Long): PortResult<ReviewedPostSelection> {
        var selection: ReviewedPostSelection? = null
        val result = run {
            val entry = read(); val local = requireSelectedRevision(entry, clientDraftId, expectedRevision)
            val content = local.current?.content as? DraftLocalContentV1.ComposerV2
            val choices = content?.let { reviewedChoiceValues(it.exactChoices, it.historicalDisclosureText) }
            selectionFence?.revoke()
            val fence = DraftReviewedSelectionFence(this@PostDraftController, composition).also { selectionFence = it }
            selection = ReviewedPostSelection(local.id, local.revision,
                if (local.legacy != null) ReviewedDraftFormat.LEGACY_TEXT else if (content == null) ReviewedDraftFormat.CURRENT_TEXT else ReviewedDraftFormat.CURRENT_COMPOSER,
                local.caption, local.alt, content?.exactChoices, choices,
                local.server?.let { PostDraftObservation(it, local.etag!!, true) }, choices?.disclosure, fence)
            acknowledged = false; historical = true; publish()
        }
        return when (result) { is PortResult.Failure -> result; is PortResult.Value ->
            PortResult.Value(selection ?: return PortResult.Failure(FailureReason.CONFLICT)) }
    }
    internal suspend fun loadReviewedDisclosure(clientDraftId: String): PortResult<PublicationDisclosure> {
        var actual: PublicationDisclosure? = null
        val result = run {
            val entry = read()
            if (entry.value.locals.none { it.id == clientDraftId }) mealFail(FailureReason.NOT_FOUND)
            val principal = resolveReviewedPrincipal()
            actual = mealValue((reviewedPrerequisites ?: mealFail(FailureReason.NOT_CONFIGURED)).disclosure(reviewedContext(principal, clientDraftId)))
            requireReviewedPrincipal(principal); acknowledged = false; historical = true; publish()
        }
        return when (result) { is PortResult.Failure -> result; is PortResult.Value ->
            PortResult.Value(actual ?: return PortResult.Failure(FailureReason.CONFLICT)) }
    }
    /** Local-only recovery is always explicitly reviewed; ordinary reads and publication
     * retry never mint this token or mutate a restored marker. */
    internal suspend fun prepareRestoredLocalRetention(clientDraftId: String, expectedRevision: Long): PortResult<RestoredLocalRetentionPresentation> {
        var prepared: RestoredLocalRetentionPresentation? = null
        val result = run(fence = true) {
            val entry = read()
            val snapshot = requireRestoredLocalRetention(entry, clientDraftId, expectedRevision)
            val time = now()
            if (time > Long.MAX_VALUE - policy.confirmationMillis) mealFail(FailureReason.UNAVAILABLE)
            journal.preflightCurrentData(entry.value.current ?: mealFail(FailureReason.NOT_CONFIGURED))
            val token = PreparedRestoredLocalRetention()
            restoredRetentionNavigation.store(token)
            prepared = RestoredLocalRetentionPresentation(token, RestoredLocalRetentionSnapshot(snapshot),
                time, time + policy.confirmationMillis, RestoredLocalRetentionNavigation(restoredRetentionNavigation, token))
            restoredRetentionReview = RestoredRetentionReview(prepared!!, generation,
                entry.record ?: mealFail(FailureReason.CONFLICT), snapshot)
            acknowledged = false; historical = true; publish()
        }
        return when (result) { is PortResult.Failure -> result; is PortResult.Value ->
            PortResult.Value(prepared ?: return PortResult.Failure(FailureReason.CONFLICT)) }
    }
    internal suspend fun confirmRestoredLocalRetention(token: PreparedRestoredLocalRetention): PortResult<PostDraftState> = run {
        val pending = restoredRetentionReview ?: mealFail(FailureReason.CONFLICT)
        val display = pending.presentation
        if (display.token !== token || !display.isCurrentForNavigation || pending.generation !== generation ||
            selectedId != display.snapshot.clientDraftId || now() < display.preparedAtMillis ||
            now() > display.expiresAtMillis) mealFail(FailureReason.CONFLICT)
        // Consume before any await. Unknown/cancelled results require a NEW explicit review.
        restoredRetentionReview = null
        restoredRetentionNavigation.store(Any())
        val entry = read()
        if (!postSame(pending.record, entry.record)) mealFail(FailureReason.CONFLICT)
        val snapshot = requireRestoredLocalRetention(entry, display.snapshot.clientDraftId, display.snapshot.localRevision)
        requireSnapshot(pending.snapshot, snapshot)
        val change = journal.prepareRestoredLocalRetention(journalUse(), entry.current(),
            snapshot.clientDraftId, snapshot.localRevision)
        requireRestoredRetentionNow(pending)
        val actual = PostDraftCurrentEdit.restoredRetention(snapshot)
        actual.mutation = change
        val proof = DraftControllerEdit(actual, snapshots ?: mealFail(FailureReason.NOT_CONFIGURED))
        retainEdit(proof)
        acknowledged = false; historical = true
        publish(PostDraftPhase.PENDING, PostDraftIssue.LOCAL_UNACKNOWLEDGED)
        requireRestoredRetentionNow(pending)
        val commitOwner = operation ?: mealFail(FailureReason.STALE_SESSION)
        restoredRetentionCommit = commitOwner to pending
        try { commit(change) }
        finally { if (restoredRetentionCommit?.first === commitOwner) restoredRetentionCommit = null }
        val observed = read()
        val current = requireSelectedRevision(observed, snapshot.clientDraftId, snapshot.localRevision).current
            ?: mealFail(FailureReason.CONFLICT)
        requireSnapshot(snapshot, current)
        // Existing caller-tail ticket delivers ONLY this freshly committed retention proof.
        editForReturn = proof; publish()
    }
    private fun requireRestoredLocalRetention(entry: DraftControllerEntry, clientDraftId: String,
        expectedRevision: Long): DraftLocalSnapshotV1 {
        if (snapshots == null || entry.value.current == null) mealFail(FailureReason.NOT_CONFIGURED)
        val local = requireSelectedRevision(entry, clientDraftId, expectedRevision)
        val snapshot = local.current ?: mealFail(FailureReason.NOT_CONFIGURED)
        if (entry.value.localPending != PostLocalPending(clientDraftId, expectedRevision) ||
            entry.value.command != null || entry.value.completion != null || heldAllocation() != null ||
            heldApply() != null) mealFail(FailureReason.CONFLICT)
        val retained = PostDraftCurrentHeld.edit(access, boundary)
        if (retained != null) {
            // An unresolved typed edit cannot be discarded as historical disk state. Only a
            // previous actual retention attempt may be superseded by a fresh same-root
            // review of actual stored content. It is not a newer unstored typing proposal.
            if (!retained.isRestoredRetention || retained.delivery?.delivered(retained) == true)
                mealFail(FailureReason.CONFLICT)
            if (retained.proposed.clientDraftId != clientDraftId) mealFail(FailureReason.CONFLICT)
        }
        return snapshot
    }
    private class RestoredRetentionReview(val presentation: RestoredLocalRetentionPresentation,
        val generation: Any, val record: PrivateRecord, val snapshot: DraftLocalSnapshotV1)
    private fun requireRestoredRetentionNow(pending: RestoredRetentionReview) {
        requireOwner()
        val display = pending.presentation
        if (pending.generation !== generation || screen != PostDraftScreen.EDITOR ||
            selectedId != display.snapshot.clientDraftId || now() < display.preparedAtMillis ||
            now() > display.expiresAtMillis) mealFail(FailureReason.CONFLICT)
    }

    internal suspend fun prepareReviewedUpgrade(clientDraftId: String, expectedRevision: Long): PortResult<PreparedReviewedDraftUpgrade> {
        var prepared: PreparedReviewedDraftUpgrade? = null
        val result = run(fence = true) {
            if (reviewedPrerequisites == null || snapshots == null) mealFail(FailureReason.NOT_CONFIGURED)
            val entry = read(); requireSelectedRevision(entry, clientDraftId, expectedRevision); requireUpgradeQuiescent(entry)
            if (entry.value.legacy == null) mealFail(FailureReason.CONFLICT)
            val time = now()
            if (time > Long.MAX_VALUE - policy.confirmationMillis) mealFail(FailureReason.UNAVAILABLE)
            // Preflight uses a detached quiescent projection, but only confirmation may retire
            // delivered markers and register an actual schema-changing mutation.
            journal.preflightLegacyUpgradeData(entry.value.legacy.copy(command = null, completion = null, localPending = null))
            prepared = PreparedReviewedDraftUpgrade(clientDraftId, expectedRevision, entry.value.locals.size, time + policy.confirmationMillis)
            upgradeReview = UpgradeReview(prepared!!, generation, entry.record, time)
            acknowledged = false; historical = true; publish()
        }
        return when (result) { is PortResult.Failure -> result; is PortResult.Value ->
            PortResult.Value(prepared ?: return PortResult.Failure(FailureReason.CONFLICT)) }
    }
    internal suspend fun confirmReviewedUpgrade(token: PreparedReviewedDraftUpgrade): PortResult<ReviewedDraftUpgradeResult> {
        val result = run {
            val pending = upgradeReview ?: mealFail(FailureReason.CONFLICT)
            if (pending.token !== token || pending.generation !== generation || selectedId != token.clientDraftId ||
                now() < pending.created || now() > token.expiresAtMillis) mealFail(FailureReason.CONFLICT)
            // Consume before the first await. A cancelled/unknown local commit never recreates
            // this consent. An explicit restore observes the actual format, with no old ACK.
            upgradeReview = null
            var entry = read()
            if (!postSame(pending.record, entry.record)) mealFail(FailureReason.CONFLICT)
            requireSelectedRevision(entry, token.clientDraftId, token.localRevision); requireUpgradeQuiescent(entry)
            entry = clearDelivered(entry)
            val legacy = entry.value.legacy ?: mealFail(FailureReason.CONFLICT)
            if (legacy.command != null || legacy.completion != null || legacy.localPending != null) mealFail(FailureReason.CONFLICT)
            val change = journal.prepareLegacyUpgrade(journalUse(), entry.legacy())
            journal.commitCurrent(journalUse(), change)
            read(); acknowledged = false; historical = true
            publish(PostDraftPhase.READY, PostDraftIssue.CONTEXT_CHANGED)
        }
        return when (result) { is PortResult.Failure -> result; is PortResult.Value ->
            PortResult.Value(ReviewedDraftUpgradeResult.CURRENT_REVIEW_REQUIRED) }
    }
    private fun requireSelectedRevision(entry: DraftControllerEntry, clientDraftId: String, expectedRevision: Long): DraftControllerLocal {
        if (selectedId != clientDraftId || screen != PostDraftScreen.EDITOR) mealFail(FailureReason.CONFLICT)
        val local = entry.value.locals.singleOrNull { it.id == clientDraftId } ?: mealFail(FailureReason.NOT_FOUND)
        if (local.revision != expectedRevision || clientDraftId in suppressedPublicationRoots) mealFail(FailureReason.CONFLICT)
        return local
    }
    private suspend fun requireUpgradeQuiescent(entry: DraftControllerEntry) {
        if (entry.value.command != null || heldAllocation() != null || heldEdit()?.let { !editDelivered(it) } == true ||
            heldApply()?.let { !applyDelivered(it) } == true) mealFail(FailureReason.CONFLICT)
        entry.value.completion?.let {
            val proof = heldApply() ?: mealFail(FailureReason.CONFLICT)
            if (!applyDelivered(proof) || !it.matches(proof)) mealFail(FailureReason.CONFLICT)
            val actual = observeExact(proof)
            if (!postSame(entry.record, actual.first)) mealFail(FailureReason.CONFLICT)
        }
        entry.value.localPending?.let {
            val proof = heldEdit() ?: mealFail(FailureReason.CONFLICT)
            if (!editDelivered(proof) || it != PostLocalPending(proof.proposed.id, proof.proposed.revision) ||
                entry.value.locals.singleOrNull { local -> local.id == it.clientId }?.same(proof.proposed) != true) mealFail(FailureReason.CONFLICT)
            val mutation = proof.mutation ?: mealFail(FailureReason.CONFLICT)
            val actual = entry.record ?: mealFail(FailureReason.CONFLICT)
            if (actual.schemaVersion != mutation.schemaVersion || mutation.expectedRevision == Long.MAX_VALUE ||
                actual.revision != (mutation.expectedRevision ?: 0) + 1 ||
                !actual.payload.copyForCodec().contentEquals(mutation.payload.copyForCodec())) mealFail(FailureReason.CONFLICT)
        }
        if (entry.value.locals.any { ComposerAllocationArbiter.hasPublication(access, boundary, it.id) ||
                ComposerAllocationArbiter.hasReviewedSave(access, boundary, it.id) }) mealFail(FailureReason.CONFLICT)
    }
    private class UpgradeReview(val token: PreparedReviewedDraftUpgrade, val generation: Any,
        val record: PrivateRecord?, val created: Long)

    suspend fun restoreLocal(): PortResult<PostDraftState> = run {
        revokeReviewedIntent()
        read(); recover(); screen = PostDraftScreen.LOCAL_LIST; selectedId = null
        historical = true; acknowledged = false; publish()
    }
    suspend fun newLocalDraft(caption: String = "", altText: String? = null): PortResult<PostDraftState> = run {
        revokeReviewedIntent()
        postText(caption, altText); var entry = clearDelivered(read()); requireNoUnacknowledgedEdit()
        if (entry.value.locals.size >= policy.maxLocalDrafts || entry.value.issued.size >= policy.maxIssuedIds) mealFail(FailureReason.UNAVAILABLE)
        if (entry.value.completion != null) mealFail(FailureReason.CONFLICT)
        val probe = entry.value.newLocal(unusedPlaceholder(entry.value), 1, caption, altText)
        preflight(entry.value.copy(locals = entry.value.locals + probe, issued = entry.value.issued + probe.id,
            localPending = PostLocalPending(probe.id, 1), clock = now()))
        val id = newId(entry.value); same(entry, read())
        val local = entry.value.newLocal(id, 1, caption, altText)
        val edit = newEdit(local, listOf(null)); retainEdit(edit)
        selectedId = id; screen = PostDraftScreen.EDITOR; acknowledged = false
        val value = entry.value.copy(clock = now(), locals = entry.value.locals + local, issued = entry.value.issued + id,
            localPending = PostLocalPending(id, 1))
        edit.mutation = mutation(entry, value); publish(PostDraftPhase.PENDING, PostDraftIssue.LOCAL_UNACKNOWLEDGED)
        commit(edit.mutation!!); read(); editForReturn = edit; publish()
    }
    /** Stage on the identity dispatcher BEFORE waiting for the operation mutex. A pending Save is
     * fenced, not rewritten. Its network outcome remains unknown until original-ID recovery.
     * Null altText preserves the current value; an explicit empty string clears its text. */
    suspend fun editText(caption: String, altText: String? = null): PortResult<PostDraftState> = editFields(null, caption, altText)
    /** UI callbacks carry the client identity they rendered, never a stale counterpart field. */
    suspend fun editCaption(clientDraftId: String, caption: String): PortResult<PostDraftState> = editFields(clientDraftId, caption, null)
    suspend fun editAltText(clientDraftId: String, altText: String): PortResult<PostDraftState> = editFields(clientDraftId, null, altText)
    internal suspend fun editReviewedChoices(clientDraftId: String, expectedRevision: Long, choices: ReviewedPostChoices): PortResult<PostDraftState> =
        editFields(clientDraftId, null, null, expectedRevision, choices)
    private suspend fun editFields(expectedClientId: String?, captionChange: String?, altTextChange: String?,
        expectedRevision: Long? = null, completeChoices: ReviewedPostChoices? = null): PortResult<PostDraftState> {
        val staged: DraftControllerEdit
        try {
            staged = withContext(dispatcher) { feedbackPublication.withLock {
                currentCoroutineContext().ensureActive(); requireOwner()
                val current = visibleLocals().singleOrNull { it.id == selectedId } ?: mealFail(FailureReason.CONFLICT)
                if (expectedClientId != null && expectedClientId != current.id) mealFail(FailureReason.CONFLICT)
                if (expectedRevision != null && current.revision != expectedRevision) mealFail(FailureReason.CONFLICT)
                if (completeChoices != null && (current.current == null || reviewedPrerequisites == null)) mealFail(FailureReason.NOT_CONFIGURED)
                // An unreturned provider has no known original to reserve. Once returned,
                // newer text is allowed with a shadow capacity check for that exact original.
                if (heldAllocation()?.let { it.original == null } == true) mealFail(FailureReason.CONFLICT)
                val caption = completeChoices?.caption ?: captionChange ?: current.caption
                val altText = if (completeChoices == null) altTextChange ?: current.alt else
                    (completeChoices.altText as? OptionalValue.Present)?.value
                try { postText(caption, altText) }
                catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    if (reason(failure) == FailureReason.INVALID_DATA) {
                        // Pure validation and feedback share the already checked identity turn.
                        // No suspended catch can overwrite a newer draft, owner or navigation.
                        currentCoroutineContext().ensureActive(); requireOwner()
                        val before = mutable.value
                        val compatible = publicationForReturn?.takeIf {
                            it.expected === before || it.lateValidation.load() === before
                        }
                        validationFeedback = ValidationFeedback(current.id, generation, compatible)
                        publish(PostDraftPhase.ERROR, PostDraftIssue.INVALID_INPUT, FailureReason.INVALID_DATA)
                    }
                    throw failure
                }
                if (current.revision == Long.MAX_VALUE) mealFail(FailureReason.UNAVAILABLE)
                if (current.server?.let { postString(it, "status") != "draft" } == true) mealFail(FailureReason.CONFLICT)
                val old = heldEdit()
                if (old != null && !editDelivered(old) && old.proposed.id != current.id) mealFail(FailureReason.CONFLICT)
                val proposed = if (completeChoices == null) current.copy(revision = current.revision + 1, caption = caption, alt = altText)
                    else DraftControllerLocal(snapshots!!.create(current.id, current.revision + 1,
                        DraftLocalContentV1.ComposerV2(reviewedChoicesDocument(journal.configuredPublicationPolicy!!,
                            current.id, current.revision + 1, completeChoices), completeChoices.disclosure.text),
                        current.current!!.serverAssociation), snapshots)
                if (last.current != null) {
                    // Pure cached-data capacity validation, not owner admission. Do not stage
                    // text that consumes the bytes/slot reserved for an already-held publish.
                    // The admitted read+mutation rechecks the actual row and reservation.
                    val exists = last.locals.any { it.id == current.id }
                    val candidate = last.copy(clock = now(), locals = if (exists) last.locals.map {
                        if (it.id == current.id) proposed else it
                    } else last.locals + proposed, issued = if (exists) last.issued else last.issued + current.id,
                        localPending = PostLocalPending(current.id, proposed.revision))
                    try { journal.preflightCurrentData(reviewedCapacityCandidate(candidate).current!!) }
                    catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        val why = reason(failure)
                        publish(PostDraftPhase.ERROR, if (why == FailureReason.UNAVAILABLE) PostDraftIssue.CAPACITY
                            else PostDraftIssue.DATA_UNVERIFIED, why)
                        throw failure
                    }
                }
                delivery?.revoke(); revokeReviewedIntent(); validationFeedback = null; generation = Any(); consent = null; acknowledged = false
                val unresolved = old?.takeUnless(::editDelivered)
                val candidates = if (unresolved == null) listOf(current) else unresolved.predecessors +
                    if (unresolved.mutation != null) listOf(unresolved.proposed) else emptyList()
                newEdit(proposed, candidates).also {
                    retainEdit(it); publish(PostDraftPhase.PENDING, PostDraftIssue.LOCAL_UNACKNOWLEDGED)
                }
            } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { return PortResult.Failure(reason(failure)) }
        return run {
            if (!staged.sameIdentity(heldEdit())) mealFail(FailureReason.CONFLICT)
            val entry = clearDelivered(read(), clearLocal = false)
            if (entry.value.completion != null) mealFail(FailureReason.CONFLICT)
            val stored = entry.value.locals.singleOrNull { it.id == staged.proposed.id }
            if (staged.predecessors.none { sameLocal(stored, it) } && !sameLocal(stored, staged.proposed)) mealFail(FailureReason.CONFLICT)
            if (stored == null && (entry.value.tombstones.any { it.clientId == staged.proposed.id } || entry.value.locals.size >= policy.maxLocalDrafts ||
                    entry.value.issued.size >= policy.maxIssuedIds || staged.proposed.id in entry.value.issued)) mealFail(FailureReason.CONFLICT)
            val value = entry.value.copy(clock = now(), locals = if (stored == null) entry.value.locals + staged.proposed else
                entry.value.locals.map { if (it.id == staged.proposed.id) staged.proposed else it },
                issued = if (stored == null) entry.value.issued + staged.proposed.id else entry.value.issued,
                localPending = PostLocalPending(staged.proposed.id, staged.proposed.revision))
            staged.predecessors = listOf(stored); staged.mutation = mutation(entry, value)
            commit(staged.mutation!!); read(); editForReturn = staged; publish()
        }
    }
    suspend fun openLocal(clientDraftId: String): PortResult<PostDraftState> = run(fence = true) {
        read(); val id = strictId(clientDraftId)
        if (visibleLocals().none { it.id == id }) mealFail(FailureReason.NOT_FOUND)
        selectedId = id; screen = PostDraftScreen.EDITOR; consent = null; acknowledged = false; historical = true; publish()
    }
    suspend fun listRemote(): PortResult<PostDraftState> = run(fence = true) {
        read(); consent = null; selectedId = null; acknowledged = false; screen = PostDraftScreen.REMOTE_LIST
        page = emptyList(); cursor = null; head = null; seenIds.clear(); seenClients.clear(); seenCursors.clear(); loadPage(null)
    }
    suspend fun nextRemotePage(): PortResult<PostDraftState> = run {
        val next = cursor ?: mealFail(FailureReason.CONFLICT)
        if (seenIds.size >= policy.maxTraversalItems || seenCursors.size >= policy.maxTraversalItems) mealFail(FailureReason.UNAVAILABLE)
        loadPage(next)
    }
    /** Explicit refresh may import one server draft, but cannot acknowledge or clear a command.
     * Only an already-delivered same-lease proof permits retiring its completion marker. */
    suspend fun refreshRemote(draftId: String? = null): PortResult<PostDraftState> = run(fence = true) {
        val entry = clearDelivered(read()); val id = draftId?.let(::strictId) ?: selected()?.server?.let { postString(it, "id") } ?: mealFail(FailureReason.CONFLICT)
        consent = null; acknowledged = false
        val reply = fetch(ApiCall("getPostDraft", mapOf("draftId" to id)))
        val observed = adapter.observation("getPostDraft", reply, id)
        remember(listOf(observed.document))
        if (entry.value.tombstones.any { it.clientId == observed.clientDraftId || it.serverId == id }) mealFail(FailureReason.CONFLICT)
        val prior = entry.value.locals.singleOrNull { it.id == observed.clientDraftId }
        prior?.server?.let { adapter.monotone(it, observed.document) }
        if (heldEdit()?.takeUnless(::editDelivered) != null || entry.value.completion != null) mealFail(FailureReason.CONFLICT)
        val local = if (prior == null) {
            if (entry.value.locals.size >= policy.maxLocalDrafts || entry.value.issued.size >= policy.maxIssuedIds) mealFail(FailureReason.UNAVAILABLE)
            if (observed.clientDraftId in entry.value.issued) mealFail(FailureReason.CONFLICT)
            entry.value.newLocal(observed.clientDraftId, 1, postString(observed.document, "caption"), postAlt(observed.document), observed.document, observed.etag)
        } else prior.copy(server = observed.document, etag = observed.etag)
        same(entry, read())
        commit(mutation(entry, entry.value.copy(clock = now(), locals = if (prior == null) entry.value.locals + local else
            entry.value.locals.map { if (it.id == local.id) local else it }, issued = if (prior == null) entry.value.issued + local.id else entry.value.issued)))
        read(); selectedId = local.id; screen = PostDraftScreen.EDITOR; PostDraftHeld.reveal(access, boundary, id); historical = false; publish()
    }
    suspend fun saveExplicitly(): PortResult<PostDraftState> = run {
        acknowledged = false; val before = read()
        requireNotPublicationHeld(before.value, (selected() ?: mealFail(FailureReason.CONFLICT)).id)
        var entry = clearDelivered(before); requireNoPending(); requireNoUnacknowledgedEdit()
        if (!composition.online()) return@run publish(PostDraftPhase.OFFLINE, PostDraftIssue.OFFLINE)
        val local = selected() ?: mealFail(FailureReason.CONFLICT)
        requireLocallyAcknowledged(local)
        requireNotPublicationHeld(entry.value, local.id)
        // This existing action is explicitly text-only. Full composer choices require the
        // separate live reviewed-Save token flow; never drop or silently submit those fields.
        if (local.requiresReviewedSave) mealFail(FailureReason.NOT_CONFIGURED)
        val server = local.server
        if (server != null) requireEditable(server)
        val body = WireDocument.parse(buildJsonObject {
            if (server == null) {
                put("clientDraftId", local.id); put("mediaIds", JsonArray(emptyList())); put("audience", postSelfAudience())
                put("keepOnPlate", false); put("allowRecipeSaves", false)
            }
            put("caption", local.caption); local.alt?.let { put("altText", it) }
        }.toString())
        val operation = if (server == null) "createPostDraft" else "updatePostDraft"
        schema(if (server == null) "PostDraftWrite" else "PostDraftPatch", body)
        if (entry.value.issued.size >= policy.maxIssuedIds) mealFail(FailureReason.UNAVAILABLE)
        // Validate byte capacity before generating the real command identifier.
        val previewId = unusedPlaceholder(entry.value)
        preflight(entry.value.copy(command = entry.value.original(PostOriginal(previewId, operation, local.id, local.revision, body, server, local.etag, now())),
            issued = entry.value.issued + previewId, clock = now()))
        val id = newId(entry.value); same(entry, read())
        val original = entry.value.original(PostOriginal(id, operation, local.id, local.revision, body, server, local.etag, now()))
        enqueue(entry, original); synchronize(id)
    }
    /** Read-only review of an explicit full PATCH. No migration, ID, enqueue or server Save.
     * The complete current local snapshot must already describe the proposed result. */
    suspend fun prepareReviewedSave(target: PublicationTarget.SavedDraft, patch: ReviewedDraftPatch): PortResult<ReviewedDraftSavePresentation> {
        val result = run(fence = true) {
            val entry = read(); requireReviewQuiescent(entry)
            val local = reviewedLocal(entry, target)
            val body = (reviewedEncoder ?: mealFail(FailureReason.NOT_CONFIGURED)).encode(patch)
            val principal = resolveReviewedPrincipal()
            val disclosure = reviewedDisclosure(principal, target.clientDraftId, patch.disclosure)
            val created = now(); val expiry = reviewedExpiry(created)
            val original = reviewedCommand(entry, local, body, disclosure, unusedPlaceholder(entry.value), created)
            val check = newSaveCheck(target, original, local)
            requireReviewedNew(principal, check)
            same(entry, read()); requireReviewQuiescent(entry); reviewedLocal(entry, target)
            preflightReviewed(entry, original)
            val presentation = ReviewedDraftSavePresentation(PreparedReviewedDraftSave(), ReviewedDraftSaveSnapshot(target,
                original.original.baseline, patch, body, reviewedResultChoices(original.expectedFields), original.expectedFields,
                ExactPostVersion(postVersion(original.expectedFields)), disclosure,
                (local.content as? DraftLocalContentV1.ComposerV2)?.historicalDisclosureText, created, expiry))
            saveReview = SaveReview(presentation, generation, local, original, principal, reviewedWitnessForReturn!!)
            saveRetry = null; acknowledged = false; historical = true
            publish(update = false, includeUndeliveredReview = true)
        }
        return when (result) {
            is PortResult.Failure -> result
            is PortResult.Value -> PortResult.Value(result.value.reviewedSave ?: return PortResult.Failure(FailureReason.STALE_SESSION))
        }
    }

    /** Consumes ONLY the exact live review. A lost enqueue/dispatch never reconstructs it. */
    suspend fun confirmReviewedSave(token: PreparedReviewedDraftSave): PortResult<PostDraftState> = run {
        val review = saveReview?.takeIf { it.presentation.token === token } ?: mealFail(FailureReason.CONFLICT)
        requireSaveReview(review)
        val before = read(); requireReviewQuiescent(before)
        val local = reviewedLocal(before, review.presentation.snapshot.target)
        requireSnapshot(review.local, local)
        requireReviewedNew(review.principal, newSaveCheck(review.presentation.snapshot.target, review.original, local))
        requireSaveReview(review); same(before, read())
        // This confirmation alone may retire genuinely delivered same-lease markers.
        val entry = clearDelivered(before); requireNoPending(); requireNoUnacknowledgedEdit()
        requireSnapshot(local, reviewedLocal(entry, review.presentation.snapshot.target))
        preflightReviewed(entry, review.original)
        requireSaveReview(review)
        val allocation = ReviewedDraftSaveHeld.reserve(access, boundary, review.original)
        // Consume before the first allocation await. A provider that fails/cancels without a
        // returned value leaves a distinct bounded reservation, never a replacement-key path.
        saveReview = null; saveRetry = null; acknowledged = false
        publish(PostDraftPhase.PENDING, PostDraftIssue.RECONCILIATION_REQUIRED)
        val original = try {
            // A synchronous progress collector can explicitly abandon before the provider
            // has even started. Recheck this exact slot/operation; finally settles and frees
            // a known never-invoked abandoned slot without allocating an ID.
            checkActive()
            if (!allocation.canInvokeProvider) mealFail(FailureReason.CONFLICT)
            val id = strictId(ids.next())
            // Explicit abandon and actual return compete on this exact slot. No implicit
            // Back/restore/Job cancellation abandons it; a late abandoned return is unusable.
            if (!allocation.awaiting) mealFail(FailureReason.CONFLICT)
            val raw = review.original.original
            PostDraftCommandV2.ReviewedPatch(ReviewedDraftOriginalFieldsV2(id, raw.clientId, raw.localRevision,
                raw.path, raw.body, raw.baseline, raw.etag, raw.created), review.original.expectedFields, review.original.historicalReview).also {
                if (!ReviewedDraftSaveHeld.capture(access, boundary, allocation, it)) mealFail(FailureReason.CONFLICT)
            }
        } finally { ReviewedDraftSaveHeld.sourceSettled(access, boundary, allocation) }
        val id = original.id
        // Retain the actual returned immutable original BEFORE any cancellation/read/policy
        // await. Retention is not consent, an enqueue observation, or permission to dispatch.
        checkActive(); requireReviewedLive(review.generation, review.principal, review.witness,
            review.presentation.snapshot.preparedAtMillis, review.presentation.snapshot.reviewExpiresAtMillis)
        if (id in entry.value.issued || mealValue(queue.command(access.lease, id)) != null ||
            mealValue(queue.intent(access.lease, id)) != null) mealFail(FailureReason.CONFLICT)
        same(entry, read())
        requireReviewedNew(review.principal, newSaveCheck(review.presentation.snapshot.target, original, local))
        same(entry, read()); requireReviewedLive(review.generation, review.principal, review.witness,
            review.presentation.snapshot.preparedAtMillis, review.presentation.snapshot.reviewExpiresAtMillis)
        reviewedDispatch = ReviewedDispatch(operation!!, generation, original, review.principal, review.witness,
            ReviewedDraftRetryKind.FIRST_DISPATCH_REVIEW, local, review.presentation.snapshot.reviewExpiresAtMillis)
        reviewedWitnessForReturn = review.witness
        saveReview = null; saveRetry = null; acknowledged = false
        enqueue(entry, DraftControllerOriginal(original)); synchronize(id)
    }

    /** Explicitly abandons only an exact unreturned allocation, never an original or unknown
     * enqueue. Cached admission is needed because the ID provider may hold the operation mutex.
     * The abandoned slot remains occupied until that one provider invocation actually settles. */
    suspend fun abandonUnreturnedReviewedAllocation(token: PreparedReviewedDraftAllocationAbandon): PortResult<PostDraftState> {
        val expected: Any
        try {
            expected = withContext(dispatcher) { feedbackPublication.withLock {
                currentCoroutineContext().ensureActive(); requireOwner()
                if (!ReviewedDraftSaveHeld.abandon(access, boundary, token)) mealFail(FailureReason.CONFLICT)
                delivery?.revoke(); revokeReviewedIntent(); validationFeedback = null
                generation = Any(); active = null; acknowledged = false; historical = true
                publish(PostDraftPhase.PENDING, PostDraftIssue.RECONCILIATION_REQUIRED)
                generation
            } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { return PortResult.Failure(reason(failure)) }
        return run(cached = true) { if (expected !== generation) mealFail(FailureReason.STALE_SESSION); publish() }
    }

    /** Historical originals are displayed separately from today's local/GET data. This action
     * only reads and maps; it never recovers the queue, changes the key, or acknowledges. */
    suspend fun prepareReviewedOriginalRetry(): PortResult<ReviewedDraftRetryPresentation> {
        val result = run(fence = true) {
            val entry = read()
            val original = reviewedOriginal(entry.value) ?: heldAllocation()?.original ?: mealFail(FailureReason.CONFLICT)
            val current = reviewedQueueObservation(entry.value, original)
            val kind = when {
                current == null -> ReviewedDraftRetryKind.REGISTRATION_RECONCILIATION
                entry.value.completion != null || current.phase == CommandPhase.RECEIPT_READY -> ReviewedDraftRetryKind.LOCAL_RECEIPT_RECONCILIATION
                current.attempts == 0 -> ReviewedDraftRetryKind.FIRST_DISPATCH_REVIEW
                current.attempts > 0 -> ReviewedDraftRetryKind.ATTEMPTED_ORIGINAL_REPLAY
                else -> mealFail(FailureReason.CONFLICT)
            }
            val local = entry.value.locals.singleOrNull { it.id == original.clientId }?.current ?: mealFail(FailureReason.CONFLICT)
            val principal = resolveReviewedPrincipal()
            requireReviewedRetry(principal, original, local, kind, current?.attempts ?: 0)
            same(entry, read())
            val repeated = reviewedQueueObservation(entry.value, original)
            if (!sameCommandObservation(current, repeated)) mealFail(FailureReason.CONFLICT)
            val created = now()
            val observed = (local.serverAssociation as? DraftServerAssociationV1.Observed)?.let {
                if (postString(it.exactPostDraft, "id") in PostDraftHeld.redacted(access, boundary)) null
                else PostDraftObservation(it.exactPostDraft, it.etag, true)
            }
            val presentation = ReviewedDraftRetryPresentation(PreparedReviewedDraftRetry(), ReviewedDraftRetrySnapshot(kind,
                originalDisplay(original), reviewedChoices(local), local.localRevision, observed, current?.attempts ?: 0, created, reviewedExpiry(created)))
            saveReview = null
            saveRetry = SaveRetry(presentation, generation, original, local, current, principal, reviewedWitnessForReturn!!)
            acknowledged = false; historical = true; publish(update = false, includeUndeliveredReview = true)
        }
        return when (result) {
            is PortResult.Failure -> result
            is PortResult.Value -> PortResult.Value(result.value.reviewedRetry ?: return PortResult.Failure(FailureReason.STALE_SESSION))
        }
    }

    suspend fun confirmReviewedOriginalRetry(token: PreparedReviewedDraftRetry): PortResult<PostDraftState> = run {
        val retry = saveRetry?.takeIf { it.presentation.token === token } ?: mealFail(FailureReason.CONFLICT)
        requireRetryReview(retry)
        val entry = read(); val original = reviewedOriginal(entry.value) ?: heldAllocation()?.original ?: mealFail(FailureReason.CONFLICT)
        requireSameReviewed(retry.original, original)
        val local = entry.value.locals.singleOrNull { it.id == original.clientId }?.current ?: mealFail(FailureReason.CONFLICT)
        requireSnapshot(retry.local, local)
        val current = reviewedQueueObservation(entry.value, original)
        if (!sameCommandObservation(retry.command, current)) mealFail(FailureReason.CONFLICT)
        requireReviewedRetry(retry.principal, original, local, retry.presentation.snapshot.kind, current?.attempts ?: 0)
        same(entry, read()); requireRetryReview(retry)
        reviewedDispatch = ReviewedDispatch(operation!!, generation, original, retry.principal, retry.witness,
            retry.presentation.snapshot.kind, local, retry.presentation.snapshot.reviewExpiresAtMillis)
        reviewedWitnessForReturn = retry.witness; saveRetry = null; saveReview = null; acknowledged = false
        if (current == null) {
            if (entry.value.command != null || entry.value.completion != null || original.id in entry.value.issued)
                mealFail(FailureReason.CONFLICT)
            preflightReviewed(entry, original)
            enqueue(entry, DraftControllerOriginal(original))
        }
        if (entry.value.completion != null) finalizeOriginal() else synchronize(original.id)
    }

    suspend fun prepareServerDiscard(): PortResult<PostDraftState> = run(fence = true) {
        val before = read()
        requireNotPublicationHeld(before.value, (selected() ?: mealFail(FailureReason.CONFLICT)).id)
        clearDelivered(before); requireNoPending(); val local = selected() ?: mealFail(FailureReason.CONFLICT)
        requireNotPublicationHeld(last, local.id)
        val baseline = local.server ?: mealFail(FailureReason.CONFLICT)
        if (postString(baseline, "status") !in setOf("draft", "expired")) mealFail(FailureReason.CONFLICT)
        if (last.tombstones.size >= policy.maxTombstones || last.issued.size >= policy.maxIssuedIds) mealFail(FailureReason.UNAVAILABLE)
        consent = PreparedPostDraftDiscard(identity, generation, local.id, local.revision, baseline, local.etag!!, now())
        acknowledged = false; publish(issue = PostDraftIssue.CONFIRM_DISCARD)
    }
    suspend fun confirmServerDiscard(prepared: PreparedPostDraftDiscard): PortResult<PostDraftState> = run {
        acknowledged = false; val before = read()
        requireNotPublicationHeld(before.value, (selected() ?: mealFail(FailureReason.CONFLICT)).id)
        val entry = clearDelivered(before); requireNoPending(); requireConsent(prepared)
        if (!composition.online()) return@run publish(PostDraftPhase.OFFLINE, PostDraftIssue.OFFLINE)
        if (entry.value.tombstones.size >= policy.maxTombstones || entry.value.issued.size >= policy.maxIssuedIds) mealFail(FailureReason.UNAVAILABLE)
        val probe = unusedPlaceholder(entry.value)
        preflight(entry.value.copy(clock = now(), issued = entry.value.issued + probe,
            command = entry.value.original(PostOriginal(probe, "deletePostDraft", prepared.clientId, prepared.localRevision, null, prepared.baseline, prepared.etag, now()))))
        val id = newId(entry.value); same(entry, read()); requireConsent(prepared)
        val original = entry.value.original(PostOriginal(id, "deletePostDraft", prepared.clientId, prepared.localRevision, null, prepared.baseline, prepared.etag, now()))
        enqueue(entry, original); consent = null; synchronize(id)
    }
    suspend fun retryOriginal(): PortResult<PostDraftState> = run {
        read(); acknowledged = false
        if (reviewedOriginal(last) != null) mealFail(FailureReason.NOT_CONFIGURED)
        val completion = last.completion
        if (completion != null) return@run finalizeOriginal()
        val original = last.command ?: mealFail(FailureReason.CONFLICT)
        synchronize(original.id)
    }
    suspend fun discardUnsent(): PortResult<PostDraftState> = run {
        val entry = read(); acknowledged = false
        if (heldApply() != null || entry.value.completion != null) mealFail(FailureReason.CONFLICT)
        val original = entry.value.command ?: mealFail(FailureReason.CONFLICT); val current = exactCommand(original)
        if (current.attempts != 0) mealFail(FailureReason.CONFLICT)
        val update = mutation(entry, entry.value.copy(clock = now(), command = null,
            completion = original.completion(unsent = true)))
        val proof = newApply(original, update, current.localRevision, true); retainApply(proof); applying = proof
        try { mealValue(queue.discardUnsent(access.lease, original.id, current.localRevision, listOf(update))) } finally { applying = null }
        read(); applyForReturn = proof; publish()
    }
    /** Removes only an unsent, server-unassociated local draft; permanent identity marker remains. */
    suspend fun discardLocal(clientDraftId: String, expectedLocalRevision: Long? = null): PortResult<PostDraftState> = run(fence = true) {
        if (expectedLocalRevision != null && expectedLocalRevision < 1) mealFail(FailureReason.INVALID_DATA)
        val id = strictId(clientDraftId); val before = read()
        val pinned = before.value.locals.singleOrNull { it.id == id } ?: mealFail(FailureReason.NOT_FOUND)
        if (expectedLocalRevision != null && pinned.revision != expectedLocalRevision) mealFail(FailureReason.CONFLICT)
        requireNotPublicationHeld(before.value, id)
        // Check the user's exact reviewed revision before even clearing a delivered marker.
        val entry = clearDelivered(before); requireNoPending(); requireNoUnacknowledgedEdit()
        val local = entry.value.locals.singleOrNull { it.id == id } ?: mealFail(FailureReason.NOT_FOUND)
        if (!sameLocal(pinned, local)) mealFail(FailureReason.CONFLICT)
        requireNotPublicationHeld(entry.value, id)
        if (local.server != null || entry.value.tombstones.size >= policy.maxTombstones) mealFail(FailureReason.CONFLICT)
        commit(mutation(entry, entry.value.copy(clock = now(), locals = entry.value.locals.filterNot { it.id == id },
            tombstones = entry.value.tombstones + entry.value.terminal(PostTerminal(id, null, null)),
            localPending = entry.value.localPending?.takeUnless { it.clientId == id })))
        read(); if (selectedId == id) selectedId = null; consent = null; acknowledged = false; screen = PostDraftScreen.LOCAL_LIST; publish()
    }
    suspend fun back(): PortResult<PostDraftState> = run(fence = true, cached = true) {
        consent = null; selectedId = null; screen = if (screen == PostDraftScreen.EDITOR) PostDraftScreen.LOCAL_LIST else PostDraftScreen.HIDDEN
        acknowledged = false; publish()
    }
    suspend fun dismissServerDiscard(): PortResult<PostDraftState> = run(fence = true, cached = true) { consent = null; publish() }
    suspend fun close(): PortResult<Unit> = withContext(NonCancellable + dispatcher) {
        if (!closed) { redact(); closed = true; subscription.close(); composition.release(borrower) }; PortResult.Value(Unit)
    }

    private suspend fun loadPage(next: String?): PostDraftState {
        val entry = read(); val query = mutableMapOf("limit" to listOf(policy.pageSize.toString()))
        next?.let { query["cursor"] = listOf(it) }
        val reply = fetch(ApiCall("listPostDrafts", queryParameters = query))
        val document = adapter.bound("listPostDrafts", reply) ?: mealFail(FailureReason.INVALID_DATA)
        if (reply.status != 200) mealFail(FailureReason.INVALID_DATA)
        val tag = postHead(reply.etag)
        if (next != null && tag != head) { page = emptyList(); cursor = null; head = null; mealFail(FailureReason.CONFLICT) }
        val values = (document.field("items") as? WireField.Value)?.value?.elementsOrNull() ?: mealFail(FailureReason.INVALID_DATA)
        if (values.size > policy.pageSize || seenIds.size + values.size > policy.maxTraversalItems) mealFail(FailureReason.UNAVAILABLE)
        val items = values.map { body ->
            val id = strictId(postString(body, "id")); val client = strictId(postString(body, "clientDraftId"))
            if (postString(body, "status") != "draft" || id in seenIds || client in seenClients ||
                entry.value.tombstones.any { it.clientId == client || it.serverId == id }) mealFail(FailureReason.CONFLICT)
            entry.value.locals.singleOrNull { it.id == client }?.server?.let { adapter.monotone(it, body) }
            PostDraftObservation(body, "\"${postVersion(body)}\"", false)
        }
        if (items.map { it.id }.distinct().size != items.size || items.map { it.clientDraftId }.distinct().size != items.size) mealFail(FailureReason.INVALID_DATA)
        val after = when (val field = document.field("nextCursor")) {
            WireField.Null -> null; is WireField.Value -> field.value.stringOrNull() ?: mealFail(FailureReason.INVALID_DATA)
            else -> mealFail(FailureReason.INVALID_DATA)
        }
        if (after != null && (after.isBlank() || after.length > 4096 || after.any(Char::isISOControl) || after == next || after in seenCursors)) mealFail(FailureReason.INVALID_DATA)
        same(entry, read()); remember(items.map { it.document }); page = items; cursor = after; head = tag
        seenIds += items.map { it.id }; seenClients += items.map { it.clientDraftId }; next?.let { seenCursors += it }
        historical = false; acknowledged = false; return publish()
    }
    private fun requireReviewQuiescent(entry: DraftControllerEntry) {
        if (entry.value.current == null || reviewedPrerequisites == null) mealFail(FailureReason.NOT_CONFIGURED)
        if (heldAllocation() != null) mealFail(FailureReason.CONFLICT)
        if (entry.value.command != null || heldApply()?.takeUnless(::applyDelivered) != null ||
            heldEdit()?.takeUnless(::editDelivered) != null) mealFail(FailureReason.CONFLICT)
        entry.value.completion?.let {
            val proof = heldApply()?.takeIf(::applyDelivered) ?: mealFail(FailureReason.CONFLICT)
            if (!it.matches(proof)) mealFail(FailureReason.CONFLICT)
        }
        entry.value.localPending?.let { pending ->
            val proof = heldEdit()?.takeIf(::editDelivered) ?: mealFail(FailureReason.CONFLICT)
            if (proof.proposed.id != pending.clientId || proof.proposed.revision != pending.revision) mealFail(FailureReason.CONFLICT)
        }
    }
    private fun reviewedLocal(entry: DraftControllerEntry, target: PublicationTarget.SavedDraft): DraftLocalSnapshotV1 {
        if (entry.value.current == null) mealFail(FailureReason.NOT_CONFIGURED)
        if (ComposerAllocationArbiter.hasPublication(access, boundary, target.clientDraftId)) mealFail(FailureReason.CONFLICT)
        requireNotPublicationHeld(entry.value, target.clientDraftId)
        if (selectedId != target.clientDraftId) mealFail(FailureReason.CONFLICT)
        val local = entry.value.locals.singleOrNull { it.id == target.clientDraftId } ?: mealFail(FailureReason.CONFLICT)
        requireLocallyAcknowledged(local)
        val snapshot = local.current ?: mealFail(FailureReason.CONFLICT)
        val association = snapshot.serverAssociation as? DraftServerAssociationV1.Observed ?: mealFail(FailureReason.CONFLICT)
        if (snapshot.localRevision != target.localRevision || postString(association.exactPostDraft, "id") != target.draftId ||
            postVersion(association.exactPostDraft) != target.draftVersion.decimal || association.etag != target.etag ||
            target.draftId in PostDraftHeld.redacted(access, boundary)) mealFail(FailureReason.CONFLICT)
        requireEditable(association.exactPostDraft)
        return snapshot
    }
    private fun reviewedCommand(entry: DraftControllerEntry, local: DraftLocalSnapshotV1, body: WireDocument,
        disclosure: PublicationDisclosure?, id: String, created: Long): PostDraftCommandV2.ReviewedPatch {
        if (entry.value.current == null) mealFail(FailureReason.NOT_CONFIGURED)
        val association = local.serverAssociation as? DraftServerAssociationV1.Observed ?: mealFail(FailureReason.CONFLICT)
        return journal.reviewedCommandData(ReviewedDraftOriginalFieldsV2(id, local.clientDraftId, local.localRevision,
            mapOf("draftId" to postString(association.exactPostDraft, "id")), PrivateBytes(body.encodeUtf8()),
            association.exactPostDraft, association.etag, created), local, disclosure)
    }
    private suspend fun preflightReviewed(entry: DraftControllerEntry, original: PostDraftCommandV2.ReviewedPatch) {
        if (entry.value.issued.size >= policy.maxIssuedIds) mealFail(FailureReason.UNAVAILABLE)
        preflight(entry.value.copy(clock = now(), command = DraftControllerOriginal(original),
            completion = null, localPending = null, issued = entry.value.issued + original.id))
    }
    private fun newSaveCheck(target: PublicationTarget.SavedDraft, original: PostDraftCommandV2.ReviewedPatch,
        current: DraftLocalSnapshotV1) = ReviewedDraftNewSaveCheck(target, WireDocument.decode(original.original.body.copyForCodec()),
        original.original.baseline, original.expectedFields, original.historicalReview.exactReviewedLocalSnapshot,
        current, original.historicalReview.displayedDisclosure)
    private fun reviewedContext(principal: PublicationPrincipalSnapshot, clientId: String) = ReviewedDraftPrerequisiteContext(
        access.lease, boundary, access.origin, access.lease.scope.environment, principal, clientId)
    private suspend fun resolveReviewedPrincipal(): PublicationPrincipalSnapshot {
        val admission = reviewedPrincipal ?: mealFail(FailureReason.NOT_CONFIGURED)
        val permit = composition.composerPermit(borrower)
        val principal = admission.resolve(permit)
        val witness = (reviewedDelivery ?: mealFail(FailureReason.NOT_CONFIGURED)).capture(permit, principal)
        admission.requireCurrent(permit, principal); checkActive()
        if (!witness.isCurrent()) mealFail(FailureReason.STALE_SESSION)
        bindReviewedReturn(principal, witness)
        return principal
    }
    private suspend fun requireReviewedPrincipal(principal: PublicationPrincipalSnapshot) {
        (reviewedPrincipal ?: mealFail(FailureReason.NOT_CONFIGURED)).requireCurrent(composition.composerPermit(borrower), principal)
        checkActive()
    }
    private suspend fun reviewedDisclosure(principal: PublicationPrincipalSnapshot, root: String,
        supplied: PatchValue<PublicationDisclosure>): PublicationDisclosure? {
        if (supplied !is PatchValue.Set) return null
        requireReviewedPrincipal(principal)
        val actual = mealValue((reviewedPrerequisites ?: mealFail(FailureReason.NOT_CONFIGURED)).disclosure(reviewedContext(principal, root)))
        requireReviewedPrincipal(principal)
        if (actual.version != supplied.value.version || actual.text != supplied.value.text) mealFail(FailureReason.CONFLICT)
        return actual
    }
    private suspend fun requireReviewedNew(principal: PublicationPrincipalSnapshot, check: ReviewedDraftNewSaveCheck) {
        requireReviewedPrincipal(principal)
        check.displayedDisclosure?.let { reviewedDisclosure(principal, check.target.clientDraftId, PatchValue.Set(it)) }
        mealValue((reviewedPrerequisites ?: mealFail(FailureReason.NOT_CONFIGURED)).requireNewSave(
            reviewedContext(principal, check.target.clientDraftId), check))
        requireReviewedPrincipal(principal)
    }
    private suspend fun requireReviewedRetry(principal: PublicationPrincipalSnapshot, original: PostDraftCommandV2.ReviewedPatch,
        local: DraftLocalSnapshotV1, kind: ReviewedDraftRetryKind, attempts: Int) {
        requireReviewedPrincipal(principal)
        if (ComposerAllocationArbiter.hasPublication(access, boundary, original.clientId)) mealFail(FailureReason.CONFLICT)
        requireNotPublicationHeld(last, original.clientId)
        when (kind) {
            ReviewedDraftRetryKind.REGISTRATION_RECONCILIATION, ReviewedDraftRetryKind.FIRST_DISPATCH_REVIEW -> {
                if (attempts != 0) mealFail(FailureReason.CONFLICT)
                val baseline = original.original.baseline
                val current = local.serverAssociation as? DraftServerAssociationV1.Observed ?: mealFail(FailureReason.CONFLICT)
                if (!sameDocument(baseline, current.exactPostDraft) || original.original.etag != current.etag) mealFail(FailureReason.CONFLICT)
                requireEditable(baseline)
                requireReviewedNew(principal, newSaveCheck(PublicationTarget.SavedDraft(original.clientId, original.localRevision,
                    original.original.path.getValue("draftId"), ExactPostVersion(postVersion(baseline)), original.original.etag), original, local))
            }
            ReviewedDraftRetryKind.ATTEMPTED_ORIGINAL_REPLAY -> {
                if (attempts <= 0) mealFail(FailureReason.CONFLICT)
                mealValue((reviewedPrerequisites ?: mealFail(FailureReason.NOT_CONFIGURED)).requireOriginalReplay(
                    reviewedContext(principal, original.clientId), ReviewedDraftOriginalReplayCheck(original, attempts)))
            }
            ReviewedDraftRetryKind.LOCAL_RECEIPT_RECONCILIATION -> Unit // Actual queue receipt/domain/archive checks remain mandatory below.
        }
        requireReviewedPrincipal(principal)
    }
    private fun reviewedExpiry(created: Long): Long {
        val lifetime = journal.configuredPublicationPolicy?.reviewLifetimeMillis ?: mealFail(FailureReason.NOT_CONFIGURED)
        if (created > Long.MAX_VALUE - lifetime) mealFail(FailureReason.INVALID_DATA)
        return created + lifetime
    }
    private suspend fun requireSaveReview(review: SaveReview) {
        requireReviewedLive(review.generation, review.principal, review.witness, review.presentation.snapshot.preparedAtMillis,
            review.presentation.snapshot.reviewExpiresAtMillis)
        if (saveReview !== review || !review.published.load() || selectedId != review.local.clientDraftId) mealFail(FailureReason.CONFLICT)
        bindReviewedReturn(review.principal, review.witness)
        requireReviewedLive(review.generation, review.principal, review.witness, review.presentation.snapshot.preparedAtMillis,
            review.presentation.snapshot.reviewExpiresAtMillis)
        if (saveReview !== review || !review.published.load()) mealFail(FailureReason.CONFLICT)
    }
    private suspend fun requireRetryReview(review: SaveRetry) {
        requireReviewedLive(review.generation, review.principal, review.witness, review.presentation.snapshot.preparedAtMillis,
            review.presentation.snapshot.reviewExpiresAtMillis)
        if (saveRetry !== review || !review.published.load()) mealFail(FailureReason.CONFLICT)
        bindReviewedReturn(review.principal, review.witness)
        requireReviewedLive(review.generation, review.principal, review.witness, review.presentation.snapshot.preparedAtMillis,
            review.presentation.snapshot.reviewExpiresAtMillis)
        if (saveRetry !== review || !review.published.load()) mealFail(FailureReason.CONFLICT)
    }
    private fun requireReviewedLive(expected: Any, principal: PublicationPrincipalSnapshot, witness: PublicationDeliveryWitness,
        created: Long, expiry: Long) {
        requireOwner()
        if (expected !== generation || now() < created || now() > expiry || !witness.isCurrent()) mealFail(FailureReason.CONFLICT)
        (reviewedPrincipal ?: mealFail(FailureReason.NOT_CONFIGURED)).requireCurrentNow(principal)
    }
    /** Register only within this actual admitted composer operation. The opaque gate carries
     * cross-dispatcher authorization, not the queue/store/application/ACK proof. */
    private suspend fun bindReviewedReturn(principal: PublicationPrincipalSnapshot, witness: PublicationDeliveryWitness) {
        if (active !== generation || operation == null) mealFail(FailureReason.STALE_SESSION)
        (reviewedPrincipal ?: mealFail(FailureReason.NOT_CONFIGURED)).requireCurrentNow(principal)
        val previous = reviewedWitnessForReturn
        if (previous != null && previous !== witness) mealFail(FailureReason.CONFLICT)
        val capture = reviewedReturnCapture?.takeIf { it.operation === operation } ?: mealFail(FailureReason.STALE_SESSION)
        if (reviewedGateForReturn == null) {
            val admitted = (reviewedDelivery ?: mealFail(FailureReason.NOT_CONFIGURED)).registerDelivery(
                composition.composerPermit(borrower), principal, witness)
            // Registration may suspend. Bind only to this exact original operation, with no
            // intervening await, and cancel the allocated gate if ownership changed.
            try {
                if (active !== generation || operation !== capture.operation || reviewedReturnCapture !== capture)
                    mealFail(FailureReason.STALE_SESSION)
                (reviewedPrincipal ?: mealFail(FailureReason.NOT_CONFIGURED)).requireCurrentNow(principal)
                if (!capture.bindGate(admitted)) mealFail(FailureReason.STALE_SESSION)
                reviewedGateForReturn = admitted
            } catch (failure: Throwable) { admitted.cancel(); throw failure }
        }
        reviewedWitnessForReturn = witness
        if (reviewedRevocation == null) reviewedRevocation = witness.observeRevocation {
            // Native integration invokes this on the serialized identity dispatcher, after
            // atomically revoking the epoch and BEFORE changing its native identity data.
            reviewedGateForReturn?.cancel(); delivery?.revoke()
            saveReview = null; saveRetry = null; reviewedDispatch = null
            reviewedWitnessForReturn = null; reviewedGateForReturn = null; reviewedRevocation = null
            validationFeedback = null; generation = Any(); active = null; acknowledged = false; historical = true
            publish(PostDraftPhase.ERROR, PostDraftIssue.SESSION_UNAVAILABLE, FailureReason.STALE_SESSION)
        }
    }
    internal fun bindReviewedPublicationEntry(publications: PostComposerController) {
        if (entryPublications != null) mealFail(FailureReason.CONFLICT)
        publications.requireComposition(composition)
        entryPublications = publications
    }
    internal fun requireReviewedSelection(fence: DraftReviewedSelectionFence, actualComposition: MealKitchenComposition) {
        requireOwner()
        if (actualComposition !== composition || selectionFence !== fence) mealFail(FailureReason.STALE_SESSION)
    }
    private fun revokeReviewedIntent(notifyPublicationEntry: Boolean = true) {
        restoredRetentionNavigation.store(Any())
        selectionFence?.revoke(); selectionFence = null
        if (notifyPublicationEntry) entryPublications?.invalidateDraftSelection()
        upgradeReview = null; restoredRetentionReview = null
        reviewedGateForReturn?.cancel(); reviewedGateForReturn = null
        reviewedWitnessForReturn = null; reviewedDispatch = null; saveReview = null; saveRetry = null
        reviewedRevocation?.close(); reviewedRevocation = null
    }
    private fun requireSnapshot(a: DraftLocalSnapshotV1, b: DraftLocalSnapshotV1) {
        if (!a.exactUtf8.copyForCodec().contentEquals(b.exactUtf8.copyForCodec())) mealFail(FailureReason.CONFLICT)
    }
    private fun reviewedOriginal(value: DraftControllerRecord): PostDraftCommandV2.ReviewedPatch? =
        value.current?.command as? PostDraftCommandV2.ReviewedPatch ?: when (val done = value.current?.completion) {
            is PostDraftCompletionV2.ReviewedApplied -> done.original
            is PostDraftCompletionV2.ReviewedUnsent -> done.original
            else -> null
        }
    private suspend fun reviewedQueueObservation(value: DraftControllerRecord, original: PostDraftCommandV2.ReviewedPatch): CommandView? {
        if (value.command != null) return exactCommand(DraftControllerOriginal(original))
        if (value.completion == null) {
            val allocation = heldAllocation() ?: mealFail(FailureReason.CONFLICT)
            requireSameReviewed(allocation.original ?: mealFail(FailureReason.CONFLICT), original)
            if (original.id in value.issued || mealValue(queue.command(access.lease, original.id)) != null ||
                mealValue(queue.intent(access.lease, original.id)) != null || mealValue(queue.receipt(access.lease, original.id)) != null)
                mealFail(FailureReason.CONFLICT)
            return null
        }
        val completion = value.completion ?: mealFail(FailureReason.CONFLICT)
        if (completion.commandId != original.id) mealFail(FailureReason.CONFLICT)
        return (mealValue(queue.command(access.lease, original.id)) ?: mealFail(FailureReason.CONFLICT)).also {
            if (it.commandId != original.id || it.operationId != original.operation ||
                it.phase != if (completion.unsent) CommandPhase.DISCARDED else CommandPhase.APPLIED) mealFail(FailureReason.CONFLICT)
        }
    }
    private fun requireSameReviewed(a: PostDraftCommandV2.ReviewedPatch, b: PostDraftCommandV2.ReviewedPatch) {
        if (a.id != b.id || a.clientId != b.clientId || a.localRevision != b.localRevision || a.created != b.created ||
            !postSameCall(a.historicalCall(), b.historicalCall()) ||
            !a.original.baseline.encodeUtf8().contentEquals(b.original.baseline.encodeUtf8()) ||
            !a.historicalReview.exactUtf8.copyForCodec().contentEquals(b.historicalReview.exactUtf8.copyForCodec()) ||
            !a.expectedFields.encodeUtf8().contentEquals(b.expectedFields.encodeUtf8())) mealFail(FailureReason.CONFLICT)
    }
    private fun sameCommandObservation(a: CommandView?, b: CommandView?): Boolean = if (a == null || b == null) a == null && b == null else a.commandId == b.commandId &&
        a.operationId == b.operationId && a.localRevision == b.localRevision && a.phase == b.phase && a.attempts == b.attempts &&
        a.issue == b.issue && a.retryAtMillis == b.retryAtMillis
    private fun originalDisplay(original: PostDraftCommandV2.ReviewedPatch) = ReviewedDraftOriginalSnapshot(original.id,
        original.clientId, original.localRevision, original.created, WireDocument.decode(original.original.body.copyForCodec()),
        original.original.baseline, original.original.etag, original.original.path.getValue("draftId"), original.expectedFields,
        original.historicalReview.displayedDisclosure,
        (original.historicalReview.exactReviewedLocalSnapshot.content as? DraftLocalContentV1.ComposerV2)?.historicalDisclosureText)
    private fun reviewedChoices(local: DraftLocalSnapshotV1): WireDocument = when (val content = local.content) {
        is DraftLocalContentV1.ComposerV2 -> content.exactChoices
        is DraftLocalContentV1.TextV1 -> WireDocument.parse(buildJsonObject {
            put("caption", content.caption); content.altText?.let { put("altText", it) }
        }.toString())
    }
    private fun reviewedResultChoices(expected: WireDocument) = WireDocument.parse(JsonObject(expected.json().jsonObject.filterKeys {
        it in setOf("clientDraftId", "caption", "altText", "mediaIds", "audience", "keepOnPlate", "attachment",
            "allowRecipeSaves", "saveDisclosureVersion", "sourcePostId")
    }).toString())
    private fun requireReviewedDispatchNow(dispatch: ReviewedDispatch) {
        if (reviewedDispatch !== dispatch || operation !== dispatch.operation || generation !== dispatch.generation ||
            active !== generation || !dispatch.witness.isCurrent()) mealFail(FailureReason.STALE_SESSION)
        (reviewedPrincipal ?: mealFail(FailureReason.NOT_CONFIGURED)).requireCurrentNow(dispatch.principal)
    }
    private suspend fun requireReviewedDispatchCurrent(dispatch: ReviewedDispatch, eligibility: Boolean) {
        requireReviewedDispatchNow(dispatch)
        requireReviewedPrincipal(dispatch.principal)
        if (eligibility) {
            if (now() > dispatch.expiresAt) mealFail(FailureReason.CONFLICT)
            val entry = read(); val original = entry.value.command?.current as? PostDraftCommandV2.ReviewedPatch
                ?: mealFail(FailureReason.CONFLICT)
            requireSameReviewed(dispatch.original, original)
            val local = entry.value.locals.singleOrNull { it.id == original.clientId }?.current ?: mealFail(FailureReason.CONFLICT)
            // The separately reviewed current local snapshot is immutable as well. A later
            // edit fences this first dispatch without rewriting the original request.
            requireSnapshot(dispatch.local, local)
            val queueState = exactCommand(DraftControllerOriginal(original))
            if (dispatch.kind in setOf(ReviewedDraftRetryKind.REGISTRATION_RECONCILIATION, ReviewedDraftRetryKind.FIRST_DISPATCH_REVIEW)) {
                // Queue attempt registration can already have occurred before beforeTransport.
                // Recheck the live first-dispatch review, without pretending its metadata is 0.
                val baseline = original.original.baseline
                requireReviewedNew(dispatch.principal, newSaveCheck(PublicationTarget.SavedDraft(original.clientId,
                    original.localRevision, original.original.path.getValue("draftId"), ExactPostVersion(postVersion(baseline)),
                    original.original.etag), original, local))
            } else requireReviewedRetry(dispatch.principal, original, local, dispatch.kind, queueState.attempts)
            same(entry, read())
        }
        requireReviewedDispatchNow(dispatch)
    }
    private suspend fun fetch(call: ApiCall): ApiReply {
        if (!MobileRequestValidator().accepts(call, PrincipalClass.ACCOUNT)) mealFail(FailureReason.INVALID_DATA)
        return mealValue(composition.transport.execute(access.lease, call))
    }
    private suspend fun enqueue(entry: DraftControllerEntry, original: DraftControllerOriginal) {
        if (!MobileRequestValidator().accepts(original.call(), PrincipalClass.ACCOUNT)) mealFail(FailureReason.INVALID_DATA)
        val update = mutation(entry, entry.value.copy(clock = now(), command = original, issued = entry.value.issued + original.id))
        same(entry, read()); requireDispatch(original, entry.value)
        heldAllocation()?.let { allocation ->
            val retained = allocation.original ?: mealFail(FailureReason.CONFLICT)
            requireSameReviewed(retained, original.current as? PostDraftCommandV2.ReviewedPatch ?: mealFail(FailureReason.CONFLICT))
            ReviewedDraftSaveHeld.beginEnqueue(access, boundary, allocation, retained)
        }
        mealValue(queue.enqueue(access.lease, CommandIntent(original.id, origin, original.call()), listOf(update)))
        read(); observeCommand(); invalidatePage()
    }
    private suspend fun synchronize(id: String): PostDraftState {
        var entry = read(); val original = entry.value.command ?: return finalizeOriginal()
        if (original.id != id) mealFail(FailureReason.CONFLICT)
        var current = exactCommand(original)
        heldAllocation()?.let { allocation ->
            requireSameReviewed(allocation.original ?: mealFail(FailureReason.CONFLICT),
                original.current as? PostDraftCommandV2.ReviewedPatch ?: mealFail(FailureReason.CONFLICT))
            // Queue observation may suspend. It cannot retire the last actual allocation
            // witness against a stale domain snapshot. Re-read and require the exact whole
            // row/original/permanent ID immediately before this no-await memory handoff.
            val registered = read(); same(entry, registered)
            val registeredOriginal = registered.value.current?.command as? PostDraftCommandV2.ReviewedPatch
                ?: mealFail(FailureReason.CONFLICT)
            requireSameReviewed(allocation.original ?: mealFail(FailureReason.CONFLICT), registeredOriginal)
            if (registeredOriginal.id !in registered.value.issued || heldAllocation() !== allocation)
                mealFail(FailureReason.CONFLICT)
            ReviewedDraftSaveHeld.registered(access, boundary, allocation)
        }
        if (current.phase == CommandPhase.IN_FLIGHT) {
            if (original.current is PostDraftCommandV2.ReviewedPatch)
                mealValue(queue.recoverInterrupted(access.lease, id, current.localRevision))
            else mealValue(queue.recoverInterrupted(access.lease))
            current = exactCommand(original)
        }
        if (current.phase == CommandPhase.NEEDS_RESOLUTION && current.issue in setOf(CommandIssue.AUTH_REQUIRED, CommandIssue.NOT_CONFIGURED,
                CommandIssue.DOMAIN_RECHECK_REQUIRED, CommandIssue.OFFLINE, CommandIssue.TEMPORARILY_UNAVAILABLE)) {
            if (!composition.online()) return publish(PostDraftPhase.OFFLINE, PostDraftIssue.ORIGINAL_PENDING)
            requireDispatch(original, entry.value)
            current = mealValue(queue.resumeAfterResolution(access.lease, id, current.localRevision))
        }
        if (current.phase in setOf(CommandPhase.READY, CommandPhase.RETRY_WAIT, CommandPhase.AWAITING_CONFIRMATION)) {
            if (!composition.online()) return publish(PostDraftPhase.OFFLINE, PostDraftIssue.ORIGINAL_PENDING)
            requireDispatch(original, entry.value)
            val dispatchOwner = operation ?: mealFail(FailureReason.STALE_SESSION)
            val projection = DispatchProjection(original, dispatchOwner).also { unobservedDispatch = it }
            // The cached zero-attempt view predates dispatch admission. Nested execution hooks
            // read it before attempt registration, so they must not advertise an unsent action.
            publish(PostDraftPhase.PENDING, PostDraftIssue.ORIGINAL_PENDING)
            try { mealValue(queue.dispatchConfirmed(access.lease, id)) }
            finally { projection.returned(dispatchOwner) } // Only marks scope exit; never clears evidence.
        }
        val receipt = mealValue(queue.receipt(access.lease, id))
        if (receipt != null) {
            entry = read(); if (entry.value.command?.id != id) mealFail(FailureReason.CONFLICT)
            requireIntent(original, mealValue(queue.intent(access.lease, id)) ?: mealFail(FailureReason.CONFLICT))
            val observed = original.receipt(adapter, reviewedAdapter, receipt.reply)
            if (observed != null) remember(listOf(observed.document))
            val local = entry.value.locals.singleOrNull { it.id == original.clientId } ?: mealFail(FailureReason.CONFLICT)
            local.server?.let { baseline -> if (observed != null) adapter.monotone(baseline, observed.document) }
            if (observed == null && (local.etag != original.etag || !sameDocument(local.server, original.baseline))) mealFail(FailureReason.CONFLICT)
            if (observed == null && entry.value.tombstones.size >= policy.maxTombstones) mealFail(FailureReason.UNAVAILABLE)
            val value = entry.value.copy(clock = now(), command = null,
                completion = original.completion(unsent = false, observed = observed),
                locals = if (observed == null) entry.value.locals.filterNot { it.id == original.clientId } else entry.value.locals.map {
                    if (it.id == original.clientId) it.copy(server = observed.document, etag = observed.etag) else it },
                tombstones = if (observed == null) entry.value.tombstones + entry.value.terminal(PostTerminal(original.clientId, original.serverId, id)) else entry.value.tombstones,
                localPending = entry.value.localPending?.takeUnless { observed == null && it.clientId == original.clientId })
            same(entry, read()); val update = mutation(entry, value)
            val proof = newApply(original, update, receipt.command.localRevision, false); retainApply(proof); applying = proof
            try { mealValue(queue.applyReceipt(access.lease, id, receipt.command.localRevision, listOf(update))) } finally { applying = null }
            read(); observeCommand(); invalidatePage()
            if (observed == null) finishDiscardedEditor(original.clientId)
            historical = false; acknowledged = true; applyForReturn = proof
        }
        read(); observeCommand(); return publish()
    }
    /** Actual same-lease domain + archive proof and fresh changed CAS, never APPLIED inference. */
    private suspend fun finalizeOriginal(): PostDraftState {
        val proof = heldApply() ?: mealFail(FailureReason.CONFLICT)
        if (applyDelivered(proof)) mealFail(FailureReason.CONFLICT)
        val archive = proof.archive ?: mealFail(FailureReason.CONFLICT)
        requireCompletion(proof)
        val before = observeExact(proof)
        val current = mealValue(queue.command(access.lease, proof.original.id)) ?: mealFail(FailureReason.CONFLICT)
        if (current.operationId != proof.original.operation || current.phase != (if (proof.unsent) CommandPhase.DISCARDED else CommandPhase.APPLIED) ||
            current.localRevision != before.second.revision) mealFail(FailureReason.CONFLICT)
        val repeated = observeExact(proof)
        if (!postSame(before.first, repeated.first) || !postSame(before.second, repeated.second)) mealFail(FailureReason.CONFLICT)
        commit(refresh(before.first))
        val after = observeExact(proof)
        if (!postSame(before.second, after.second) || after.first.revision <= before.first.revision) mealFail(FailureReason.CONFLICT)
        read(); observeCommand()
        if (!proof.unsent && proof.original.operation == "deletePostDraft") finishDiscardedEditor(proof.original.clientId)
        acknowledged = !proof.unsent; historical = false; applyForReturn = proof
        return publish()
    }
    /** A confirmed removal must not strand the user in an empty editor. Do not reopen a
     * screen they already left while reconciling the original command from another route. */
    private fun finishDiscardedEditor(clientId: String) {
        if (screen == PostDraftScreen.EDITOR && selectedId == clientId) screen = PostDraftScreen.LOCAL_LIST
        if (selectedId == clientId) selectedId = null
        consent = null
    }
    private suspend fun observeExact(proof: DraftControllerApply): Pair<PrivateRecord, PrivateRecord> =
        if (proof.legacy != null) journal.observeLegacyApply(journalUse(), proof.legacy) else journal.observeCurrentApply(journalUse(), proof.current!!)
    private fun requireCompletion(proof: DraftControllerApply) {
        if (last.completion?.matches(proof) != true) mealFail(FailureReason.CONFLICT)
    }
    private suspend fun clearDelivered(entry: DraftControllerEntry, clearLocal: Boolean = true): DraftControllerEntry {
        var value = entry.value; val apply = heldApply(); val edit = heldEdit()
        if (value.completion != null && apply != null && applyDelivered(apply)) { requireCompletion(apply); value = value.copy(completion = null) }
        if (clearLocal && value.localPending != null && edit != null && editDelivered(edit) &&
            value.localPending == PostLocalPending(edit.proposed.id, edit.proposed.revision)) value = value.copy(localPending = null)
        if (value == entry.value) return entry
        commit(mutation(entry, value.copy(clock = now())))
        if (entry.value.completion != null && value.completion == null && apply != null) clearApply(apply)
        if (entry.value.localPending != null && value.localPending == null && edit != null) clearEdit(edit)
        return read()
    }
    private suspend fun exactCommand(original: DraftControllerOriginal): CommandView {
        requireIntent(original, mealValue(queue.intent(access.lease, original.id)) ?: mealFail(FailureReason.CONFLICT))
        return mealValue(queue.command(access.lease, original.id))?.also {
            if (it.operationId != original.operation) mealFail(FailureReason.CONFLICT)
        } ?: mealFail(FailureReason.CONFLICT)
    }
    private fun requireIntent(original: DraftControllerOriginal, intent: CommandIntent) {
        if (original.id != intent.commandId || intent.originBinding != origin || intent.dependencyCommandIds.isNotEmpty() ||
            !postSameCall(original.call(), intent.call)) mealFail(FailureReason.CONFLICT)
    }
    private fun requireDispatch(original: DraftControllerOriginal, record: DraftControllerRecord) {
        requireOwner(); if (!composition.online()) mealFail(FailureReason.OFFLINE)
        if (original.current is PostDraftCommandV2.ReviewedPatch) {
            val dispatch = reviewedDispatch ?: mealFail(FailureReason.NOT_CONFIGURED)
            requireSameReviewed(dispatch.original, original.current)
            requireReviewedDispatchNow(dispatch)
        }
        if (heldEdit()?.takeUnless(::editDelivered) != null || record.completion != null) mealFail(FailureReason.CONFLICT)
        requireNotPublicationHeld(record, original.clientId)
        val local = record.locals.singleOrNull { it.id == original.clientId } ?: mealFail(FailureReason.CONFLICT)
        if (record.tombstones.any { it.clientId == original.clientId } || local.revision < original.localRevision) mealFail(FailureReason.CONFLICT)
        if (original.operation == "deletePostDraft") {
            if (local.etag != original.etag || !sameDocument(local.server, original.baseline) ||
                postString(original.baseline!!, "status") !in setOf("draft", "expired")) mealFail(FailureReason.CONFLICT)
        } else if (local.etag == original.etag && sameDocument(local.server, original.baseline)) {
            if (original.operation == "updatePostDraft" && !(original.current is PostDraftCommandV2.ReviewedPatch &&
                    reviewedDispatch?.kind == ReviewedDraftRetryKind.ATTEMPTED_ORIGINAL_REPLAY)) requireEditable(original.baseline!!)
        } else {
            val observed = local.server ?: mealFail(FailureReason.CONFLICT)
            original.possibleOriginalResult(adapter, reviewedAdapter, observed, local.etag ?: mealFail(FailureReason.CONFLICT))
            // A committed update may have renewed expiry beyond its old submitted base. This is
            // eligibility for original replay only; the actual service still rechecks authority.
            requireEditable(observed)
        }
        if (now() < original.created) mealFail(FailureReason.CONFLICT)
    }
    private fun requireEditable(document: WireDocument) {
        if (postString(document, "status") != "draft" || Instant.parse(postString(document, "expiresAt")).toEpochMilliseconds() <= now()) mealFail(FailureReason.CONFLICT)
    }
    private fun requireConsent(prepared: PreparedPostDraftDiscard) {
        val local = selected() ?: mealFail(FailureReason.CONFLICT); val time = now()
        requireNotPublicationHeld(last, local.id)
        if (consent !== prepared || prepared.owner !== identity || prepared.generation !== generation || local.id != prepared.clientId ||
            local.revision != prepared.localRevision || local.etag != prepared.etag || !sameDocument(local.server, prepared.baseline) ||
            time < prepared.created || time - prepared.created > policy.confirmationMillis) mealFail(FailureReason.CONFLICT)
    }
    private suspend fun read(): DraftControllerEntry {
        journalObserved = false
        val entry = when (val stored = journal.readDrafts(journalUse())) {
            is PostDraftJournalEntry.Legacy -> DraftControllerEntry(stored.entry.record, DraftControllerRecord(stored.entry.value))
            is PostDraftJournalEntry.Current -> DraftControllerEntry(stored.entry.record,
                DraftControllerRecord(stored.entry.value, snapshots ?: mealFail(FailureReason.NOT_CONFIGURED)), stored.entry)
        }
        // Migration is not performed here. Crossing formats while unresolved evidence from
        // the old format survives is reconciliation, never implicit proof conversion.
        if (entry.value.current != null && (PostDraftHeld.edit(access, boundary)?.let { it.delivery?.delivered(it) != true } == true ||
                PostDraftHeld.apply(access, boundary)?.let { it.delivery?.delivered(it) != true } == true)) mealFail(FailureReason.CONFLICT)
        if (entry.value.legacy != null && (PostDraftCurrentHeld.edit(access, boundary) != null || PostDraftCurrentHeld.apply(access, boundary) != null))
            mealFail(FailureReason.CONFLICT)
        last = entry.value
        // An explicit fresh read may reveal an open root again after an aborted contribution.
        // Closed roots remain excluded even if a held uncommitted edit still needs recovery.
        suppressedPublicationRoots = suppressedPublicationRoots.filterTo(mutableSetOf()) { id -> last.locals.none { it.id == id } }
        observeCommand()
        journalObserved = true
        return entry
    }
    private suspend fun recover() {
        if (view?.phase == CommandPhase.IN_FLIGHT) {
            val original = last.command
            if (original?.current is PostDraftCommandV2.ReviewedPatch) {
                val current = exactCommand(original)
                mealValue(queue.recoverInterrupted(access.lease, original.id, current.localRevision))
            } else mealValue(queue.recoverInterrupted(access.lease))
            observeCommand()
        }
    }
    private suspend fun observeCommand() {
        val original = last.command
        val observed = original?.let { mealValue(queue.command(access.lease, it.id)) }
        val projection = unobservedDispatch
        if (original != null && projection != null && projection.matches(original) && projection.hasReturned) {
            // This fresh observation is outside the original dispatch call. Read the immutable
            // intent as well as metadata; cached Back and in-dispatch hooks cannot clear the mask.
            requireIntent(projection.original, mealValue(queue.intent(access.lease, original.id)) ?: mealFail(FailureReason.CONFLICT))
            if (observed == null || observed.commandId != original.id || observed.operationId != original.operation) mealFail(FailureReason.CONFLICT)
            if (unobservedDispatch === projection) unobservedDispatch = null
        }
        view = observed
    }
    private fun journalUse() = activeJournalUse ?: mealFail(FailureReason.STALE_SESSION)
    private suspend fun mutation(entry: DraftControllerEntry, value: DraftControllerRecord): StoreMutation.Put {
        heldAllocation()?.let { allocation ->
            val actual = allocation.original ?: mealFail(FailureReason.CONFLICT)
            if (value.command == null) {
                if (value.issued != entry.value.issued || value.locals.none { it.id == actual.clientId }) mealFail(FailureReason.CONFLICT)
                // Capacity-only shadow, never passed to mutation/commit. The actual newer
                // local snapshot/ACK is retained as-is; no hidden command is registered.
                journal.preflightCurrent(journalUse(), reviewedCapacityCandidate(value).current!!)
            } else {
                requireSameReviewed(actual, value.current?.command as? PostDraftCommandV2.ReviewedPatch ?: mealFail(FailureReason.CONFLICT))
                if (actual.id !in value.issued) mealFail(FailureReason.CONFLICT)
            }
        }
        return if (entry.value.legacy != null) journal.mutationLegacy(journalUse(), entry.legacy(), value.legacy ?: mealFail(FailureReason.CONFLICT))
        else journal.mutationCurrent(journalUse(), entry.current(), value.current ?: mealFail(FailureReason.CONFLICT))
    }
    private suspend fun preflight(value: DraftControllerRecord) {
        if (value.legacy != null) journal.preflightLegacy(journalUse(), value.legacy)
        else journal.preflightCurrent(journalUse(), value.current!!)
    }
    private suspend fun schema(name: String, body: WireDocument) {
        if (last.legacy != null) journal.schemaLegacy(journalUse(), name, body)
        else if (body.encodeUtf8().size > policy.maxResponseBytes ||
            CanonicalBodyValidator.bundled().validateSchema(name, body.encodeUtf8()) != ContractValidationResult.Valid) mealFail(FailureReason.INVALID_DATA)
    }
    private fun beforeCommit(changes: List<StoreMutation>) {
        // One domain contribution is required; archive/intent mutations retain their existing
        // queue owner. No batch may contain an unregistered current/legacy domain mutation.
        val domain = changes.filter { journal.ownsLegacyKey(it.key) }
        if (domain.isEmpty()) return
        if (domain.size != 1 || domain.single() !is StoreMutation.Put) mealFail(FailureReason.CONFLICT)
        when ((domain.single() as StoreMutation.Put).schemaVersion) {
            1 -> journal.beforeLegacyCommit(journalUse(), changes)
            2 -> journal.beforeCurrentCommit(journalUse(), changes)
            else -> mealFail(FailureReason.CONFLICT)
        }
    }
    private suspend fun commit(change: StoreMutation.Put) = when (change.schemaVersion) {
        1 -> journal.commitLegacy(journalUse(), change)
        2 -> journal.commitCurrent(journalUse(), change)
        else -> mealFail(FailureReason.CONFLICT)
    }
    private suspend fun refresh(observed: PrivateRecord): StoreMutation.Put = when (observed.schemaVersion) {
        1 -> journal.refreshLegacy(journalUse(), observed)
        2 -> journal.refreshCurrent(journalUse(), observed)
        else -> mealFail(FailureReason.CONFLICT)
    }
    private fun newEdit(local: DraftControllerLocal, predecessors: List<DraftControllerLocal?>): DraftControllerEdit =
        if (local.legacy != null) DraftControllerEdit(PostEdit(local.legacy, predecessors.map { it?.let { p -> p.legacy ?: mealFail(FailureReason.CONFLICT) } }))
        else DraftControllerEdit(PostDraftCurrentEdit(local.current!!, predecessors.map { it?.let { p -> p.current ?: mealFail(FailureReason.CONFLICT) } }), snapshots!!)
    private fun newApply(original: DraftControllerOriginal, update: StoreMutation.Put, revision: Long, unsent: Boolean): DraftControllerApply =
        if (original.legacy != null) DraftControllerApply(PostApply(original.legacy, update, revision, unsent))
        else DraftControllerApply(PostDraftCurrentApply(original.current!!, update, revision, unsent))
    private fun heldEdit() = if (last.legacy != null) PostDraftHeld.edit(access, boundary)?.let(::DraftControllerEdit)
        else PostDraftCurrentHeld.edit(access, boundary)?.let { DraftControllerEdit(it, snapshots!!) }
    private fun heldApply() = if (last.legacy != null) PostDraftHeld.apply(access, boundary)?.let(::DraftControllerApply)
        else PostDraftCurrentHeld.apply(access, boundary)?.let(::DraftControllerApply)
    private fun editDelivered(edit: DraftControllerEdit) = edit.delivered()
    private fun applyDelivered(apply: DraftControllerApply) = apply.delivered()
    private fun retainEdit(edit: DraftControllerEdit) {
        if (edit.legacy != null) PostDraftHeld.retainEdit(access, boundary, edit.legacy)
        else PostDraftCurrentHeld.retainEdit(access, boundary, edit.current!!)
    }
    private fun retainApply(apply: DraftControllerApply) {
        if (apply.legacy != null) PostDraftHeld.retainApply(access, boundary, apply.legacy)
        else PostDraftCurrentHeld.retainApply(access, boundary, apply.current!!)
    }
    private fun clearEdit(edit: DraftControllerEdit) {
        if (edit.legacy != null) PostDraftHeld.clearEdit(access, boundary, edit.legacy)
        else PostDraftCurrentHeld.clearEdit(access, boundary, edit.current!!)
    }
    private fun clearApply(apply: DraftControllerApply) {
        if (apply.legacy != null) PostDraftHeld.clearApply(access, boundary, apply.legacy)
        else PostDraftCurrentHeld.clearApply(access, boundary, apply.current!!)
    }
    private fun requireNotPublicationHeld(record: DraftControllerRecord, clientId: String) {
        // An actual publication allocation can precede its durable hold. This root-only
        // observation is an additional exclusion, never admission or registration proof.
        if (record.requiresPublicationReconciliation(clientId) ||
            ComposerAllocationArbiter.hasPublication(access, boundary, clientId)) mealFail(FailureReason.CONFLICT)
    }
    private fun heldAllocation() = ReviewedDraftSaveHeld.pending(access, boundary)
    /** Pure byte/shape reservation probe only, never an owner mutation or serialized row. */
    private fun reviewedCapacityCandidate(value: DraftControllerRecord): DraftControllerRecord {
        val allocation = heldAllocation() ?: return value
        val actual = allocation.original ?: mealFail(FailureReason.CONFLICT)
        if (value.current == null || value.completion != null) mealFail(FailureReason.CONFLICT)
        if (value.command != null) {
            requireSameReviewed(actual, value.current.command as? PostDraftCommandV2.ReviewedPatch ?: mealFail(FailureReason.CONFLICT))
            return value
        }
        if (actual.id in value.issued || value.issued.size >= policy.maxIssuedIds) mealFail(FailureReason.CONFLICT)
        return value.copy(command = DraftControllerOriginal(actual), issued = value.issued + actual.id)
    }
    private fun requireNoPending() { if (last.command != null || last.completion != null || heldAllocation() != null || heldApply()?.takeUnless(::applyDelivered) != null) mealFail(FailureReason.CONFLICT) }
    private fun requireNoUnacknowledgedEdit() { if (heldEdit()?.takeUnless(::editDelivered) != null || last.localPending != null) mealFail(FailureReason.CONFLICT) }
    private fun requireLocallyAcknowledged(local: DraftControllerLocal) {
        val pending = last.localPending
        if (pending?.clientId == local.id && heldEdit()?.let { editDelivered(it) && pending.revision == it.proposed.revision } != true) mealFail(FailureReason.CONFLICT)
    }
    private fun selected() = last.locals.singleOrNull { it.id == selectedId }
    private fun visibleLocals(): List<DraftControllerLocal> {
        val closedRoots = last.tombstones.map { it.clientId }.toSet() + suppressedPublicationRoots
        // A retention transaction never contains unstored user edits. On a lost CAS, showing
        // its old snapshot as an edit overlay would conceal newer actually stored content.
        val edit = heldEdit()?.takeUnless { editDelivered(it) || it.current?.isRestoredRetention == true }
        val candidates = if (edit == null) last.locals else if (last.locals.any { it.id == edit.proposed.id })
            last.locals.map { if (it.id == edit.proposed.id) edit.proposed else it } else last.locals + edit.proposed
        return candidates.filterNot { it.id in closedRoots }
    }
    private fun localView(local: DraftControllerLocal, deliverEdit: DraftControllerEdit? = null): LocalPostDraft {
        val pending = last.localPending; val held = heldEdit()
        val unacknowledged = (held != null && held.proposed.id == local.id && !editDelivered(held) && !held.sameIdentity(deliverEdit)) ||
            (pending?.clientId == local.id && held?.let { (editDelivered(it) || it.sameIdentity(deliverEdit)) && it.proposed.revision == pending.revision } != true)
        val server = local.server?.takeUnless { postString(it, "id") in PostDraftHeld.redacted(access, boundary) }
        return LocalPostDraft(local.id, local.caption, local.alt, local.revision, !unacknowledged,
            server?.let { PostDraftObservation(it, local.etag!!, historical) },
            server != null && postString(server, "caption") == local.caption && postAlt(server) == local.alt, local.server != null,
            local.requiresReviewedSave, last.requiresPublicationReconciliation(local.id))
    }
    private fun publish(phase: PostDraftPhase? = null, issue: PostDraftIssue? = null, failure: FailureReason? = null,
        update: Boolean = true, deliverEdit: DraftControllerEdit? = null, deliverApply: DraftControllerApply? = null,
        includeUndeliveredReview: Boolean = false): PostDraftState {
        if (closed || !boundary.isCurrent(access.lease)) return PostDraftState.unavailable().also { mutable.value = it }
        // A later I/O failure supersedes input feedback. Successful completion of an earlier
        // edit does not: rejected text has its own owner/input marker, never a draft revision.
        if (issue != PostDraftIssue.INVALID_INPUT && (failure != null || phase in setOf(PostDraftPhase.ERROR,
                PostDraftPhase.OFFLINE, PostDraftPhase.UNAVAILABLE))) validationFeedback = null
        val validation = validationFeedback?.takeIf { it.generation === generation && it.clientId == selectedId }
        val proof = heldApply()?.takeUnless { applyDelivered(it) || it.sameIdentity(deliverApply) }
        val completion = last.completion?.takeUnless { heldApply()?.let { p -> applyDelivered(p) || p.sameIdentity(deliverApply) } == true }
        val locals = visibleLocals().map { localView(it, deliverEdit) }
        val dispatch = unobservedDispatch?.takeIf { mask -> last.command?.let(mask::matches) == true }
        val allocation = heldAllocation()
        val reviewedPending = reviewedOriginal(last) != null || allocation?.original != null
        val pending = dispatch?.let { PostDraftPending(it.original.id, it.original.operation, it.original.clientId,
            "DISPATCH_UNOBSERVED", -1, "RECONCILIATION_REQUIRED", true, false, false, reviewedPending) }
            ?: view?.let { PostDraftPending(it.commandId, it.operationId, last.command!!.clientId, it.phase.name, it.attempts, it.issue.name,
            it.phase != CommandPhase.NEEDS_RESOLUTION || it.issue in setOf(CommandIssue.AUTH_REQUIRED, CommandIssue.NOT_CONFIGURED, CommandIssue.DOMAIN_RECHECK_REQUIRED,
                CommandIssue.OFFLINE, CommandIssue.TEMPORARILY_UNAVAILABLE), it.attempts == 0 && proof == null, proof != null, reviewedPending) }
            ?: proof?.let { PostDraftPending(it.original.id, it.original.operation, it.original.clientId, "DELIVERY_PENDING", 0,
                "RECONCILIATION_REQUIRED", true, false, true, reviewedPending) }
            ?: completion?.let { PostDraftPending(it.commandId, it.operation, it.clientId, "HISTORICAL_COMPLETION", -1,
                "RECONCILIATION_REQUIRED", false, false, true, reviewedPending) }
            ?: last.command?.let { PostDraftPending(it.id, it.operation, it.clientId, "UNOBSERVED_ORIGINAL", -1, "RECONCILIATION_REQUIRED", false, false, false, reviewedPending) }
            ?: allocation?.original?.let { PostDraftPending(it.id, it.operation, it.clientId, "REGISTRATION_UNOBSERVED", -1,
                "RECONCILIATION_REQUIRED", true, false, false, true) }
        val unacknowledged = locals.any { !it.localAcknowledged }
        val result = PostDraftState(screen, phase ?: if (pending != null || allocation != null || unacknowledged) PostDraftPhase.PENDING else PostDraftPhase.READY,
            locals, locals.singleOrNull { it.clientDraftId == selectedId }, page, cursor != null, head, historical, false, pending, consent,
            acknowledged && proof == null && completion == null && last.command == null,
            issue ?: when { allocation != null -> PostDraftIssue.RECONCILIATION_REQUIRED; pending != null -> PostDraftIssue.ORIGINAL_PENDING; unacknowledged -> PostDraftIssue.LOCAL_UNACKNOWLEDGED; else -> PostDraftIssue.NONE },
            failure, view?.retryAtMillis?.takeIf { dispatch == null && it > now() },
            last.current?.publicationHold?.let { PostDraftPublicationHold(it.link.clientDraftId, it.link.commandId,
                it.link.reviewedLocalRevision, visibleLocals().singleOrNull { local -> local.id == it.link.clientDraftId }
                    ?.revision?.let { revision -> revision > it.link.reviewedLocalRevision } == true) },
            last.current?.remainders?.map { PostDraftRemainderSummary(it.clientId, it.link.commandId, it.reviewedLocalRevision,
                it.newerLocalRevision, it.content is DraftLocalContentV1.ComposerV2) } ?: emptyList(),
            saveReview?.takeIf { it.generation === generation && it.witness.isCurrent() &&
                (includeUndeliveredReview || it.published.load()) }?.presentation,
            saveRetry?.takeIf { it.generation === generation && it.witness.isCurrent() &&
                (includeUndeliveredReview || it.published.load()) }?.presentation,
            allocation?.let { ReviewedDraftAllocationStatus(it.clientId, it.localRevision,
                when { it.abandoned -> ReviewedDraftAllocationPhase.ABANDONED_WAITING_FOR_PROVIDER
                    it.original == null -> ReviewedDraftAllocationPhase.ID_PROVIDER_UNRESOLVED
                    else -> ReviewedDraftAllocationPhase.ORIGINAL_REGISTRATION_UNRESOLVED },
                it.original?.id, it.abandonToken.takeIf { _ -> it.awaiting }) },
            if (!journalObserved) PostDraftJournalFormat.NOT_OBSERVED
            else if (last.current != null) PostDraftJournalFormat.CURRENT else PostDraftJournalFormat.LEGACY)
        return (if (validation == null) result else validationState(result)).also { if (update) {
            // This exact state is the only permitted caller-tail re-projection. It is published
            // before StateFlow changes so a concurrent tail cannot miss its validation binding.
            validation?.publication?.lateValidation?.store(it)
            mutable.value = it
        } }
    }
    private fun validationState(state: PostDraftState) = PostDraftState(state.screen, PostDraftPhase.ERROR,
        state.localDrafts, state.selected, state.remoteItems, state.hasMore, state.remoteHeadEtag, state.historical,
        state.busy, state.pending, state.discardConfirmation, state.serverAcknowledged, PostDraftIssue.INVALID_INPUT,
        FailureReason.INVALID_DATA, state.earliestRetryAtMillis, state.publicationHold, state.unsubmittedRemainders,
        state.reviewedSave, state.reviewedRetry, state.reviewedAllocation, state.journalFormat)
    private fun remember(documents: List<WireDocument>) = PostDraftHeld.remember(access, boundary, documents, policy)
    private fun invalidatePage() { page = emptyList(); cursor = null; head = null; seenIds.clear(); seenClients.clear(); seenCursors.clear() }
    private fun same(a: DraftControllerEntry, b: DraftControllerEntry) { if (!postSame(a.record, b.record)) mealFail(FailureReason.CONFLICT) }
    private fun sameDocument(a: WireDocument?, b: WireDocument?) = if (a == null || b == null) a == b else equal(a, b)
    private fun sameLocal(a: DraftControllerLocal?, b: DraftControllerLocal?) = if (a == null || b == null) a == null && b == null else a.same(b)
    private fun now() = composition.clock.nowMillis().also { if (it < 0) mealFail(FailureReason.INVALID_DATA) }
    private suspend fun newId(record: DraftControllerRecord): String {
        if (heldAllocation() != null) mealFail(FailureReason.CONFLICT)
        checkActive(); val id = strictId(ids.next()); checkActive()
        if (id in record.issued || mealValue(queue.command(access.lease, id)) != null) mealFail(FailureReason.CONFLICT)
        return id
    }
    /** Codec-only capacity probe. Never persisted, enqueued, sent or returned as an allocated ID. */
    private fun unusedPlaceholder(record: DraftControllerRecord): String = (1..4097).asSequence().map {
        "00000000-0000-4000-8000-${it.toString().padStart(12, '0')}"
    }.first { it !in record.issued }
    private fun requireOwner() {
        if (closed || !boundary.isCurrent(access.lease)) mealFail(FailureReason.STALE_SESSION)
        if (access.lease.scope.actorKind != ActorKind.ACCOUNT) mealFail(FailureReason.UNAUTHENTICATED)
    }
    private suspend fun checkActive() {
        currentCoroutineContext().ensureActive(); requireOwner()
        if (active == null || active !== generation) mealFail(FailureReason.STALE_SESSION)
        // Composition calls this again after its suspending pre-store checks. Bind only to
        // this actual commit operation: cached Back remains independently usable.
        restoredRetentionCommit?.takeIf { it.first === operation }?.let { requireRestoredRetentionNow(it.second) }
    }
    private suspend fun run(fence: Boolean = false, cached: Boolean = false, action: suspend () -> PostDraftState): PortResult<PostDraftState> {
        var owned: Any? = null; var owner: Any? = null; var ticket: DraftControllerDelivery? = null
        // Capture the actual caller before any dispatcher/NonCancellable context. This holder
        // is atomics-only and exists before registration, so immediate cancellation cannot
        // race an uninitialized or newer operation's ticket/gate.
        val reviewedReturn = ReviewedReturn()
        val cancellation = DeliveryOperationCancellation.register(currentCoroutineContext()[Job], reviewedReturn::cancel)
        try {
            val result = withContext(dispatcher) {
                currentCoroutineContext().ensureActive(); requireOwner()
                if (fence) { delivery?.revoke(); revokeReviewedIntent(); validationFeedback = null; generation = Any() }
                val token = generation
                suspend fun admitted(): PostDraftState {
                    if (token !== generation) mealFail(FailureReason.STALE_SESSION)
                    owned = token; owner = Any().also { operation = it }; active = token; editForReturn = null; applyForReturn = null
                    publicationForReturn = null
                    reviewedGateForReturn?.cancel(); reviewedGateForReturn = null
                    reviewedWitnessForReturn = null; reviewedDispatch = null
                    reviewedReturn.operation = owner!!; reviewedReturnCapture = reviewedReturn
                    delivery?.revoke(); ticket = DraftControllerDelivery().also {
                        delivery = it
                        if (!reviewedReturn.bindTicket(it)) mealFail(FailureReason.STALE_SESSION)
                    }
                    try {
                        checkActive()
                        return if (cached) action() else composition.operate(borrower) {
                            val participant = journalParticipant ?: mealFail(FailureReason.STALE_SESSION)
                            val use = journal.enter(composition.composerPermit(borrower), participant)
                            activeJournalUse = use
                            try { action() }
                            finally {
                                if (activeJournalUse === use) activeJournalUse = null
                                journal.leave(use)
                            }
                        }
                    } finally { if (active === token) active = null }
                }
                if (cached) admitted() else mutex.withLock { admitted() }
            }
            currentCoroutineContext().ensureActive(); requireOwner()
            if (owned !== generation || owner !== operation) mealFail(FailureReason.STALE_SESSION)
            val publication = withContext(dispatcher) {
                requireOwner(); if (owned !== generation || owner !== operation) mealFail(FailureReason.STALE_SESSION)
                val edit = editForReturn?.takeIf { it.sameIdentity(heldEdit()) }; val apply = applyForReturn?.takeIf { it.sameIdentity(heldApply()) }
                val expected = mutable.value; val projected = if (edit != null || apply != null ||
                    validationFeedback?.let { it.generation === generation && it.clientId == selectedId } == true)
                    publish(update = false, deliverEdit = edit, deliverApply = apply) else result
                val current = ticket ?: mealFail(FailureReason.STALE_SESSION)
                if (!current.arm(edit, apply, expected, mutable)) mealFail(FailureReason.STALE_SESSION)
                current.retain(edit, apply)
                Publication(current, edit, apply, expected, projected, reviewedReturn.gate,
                    saveReview?.takeIf { projected.reviewedSave?.token === it.presentation.token },
                    saveRetry?.takeIf { projected.reviewedRetry?.token === it.presentation.token }).also { publicationForReturn = it }
            }
            // Lock acquisition may suspend, so it precedes every final cancellation/owner
            // check and ACK. Staging cannot publish a half-bound validation state concurrently
            // with this caller-side CAS+delivery; no suspension occurs until after unlock.
            feedbackPublication.lock()
            try {
            currentCoroutineContext().ensureActive(); requireOwner()
            if (owned !== generation || owner !== operation) mealFail(FailureReason.STALE_SESSION)
            // The staging lock keeps validation's bound state pair stable. Authorization
            // competes with operation revoke BEFORE any ACK can reach a collector.
            val validation = publication.lateValidation.load()
            val expected = validation ?: publication.expected
            val projected = if (validation == null) publication.projected else validationState(publication.projected)
            // The principal epoch shares this authorization CAS with native pre-change
            // revocation. No principal-backed review/ACK is emitted before it wins.
            if (publication.principalGate?.tryAuthorizeDelivery() == false) mealFail(FailureReason.STALE_SESSION)
            currentCoroutineContext().ensureActive()
            val authorization = publication.ticket.authorize(publication.edit, publication.apply, publication.expected,
                expected, projected) ?: mealFail(FailureReason.STALE_SESSION)
            // No suspend or callback gap. The ticket owns the bound StateFlow CAS and only
            // then marks actual held delivery. A lost CAS produces no transient ACK at all.
            val published = publication.ticket.publishAuthorized(authorization) ?: mealFail(FailureReason.STALE_SESSION)
            publication.saveReview?.published?.store(true)
            publication.saveRetry?.published?.store(true)
            return PortResult.Value(published)
            } finally { feedbackPublication.unlock() }
        } catch (cancelled: CancellationException) {
            reviewedReturn.cancel()
            withContext(NonCancellable + dispatcher) { if (owned === generation && owner === operation) {
                ticket?.revoke(); acknowledged = false; generation = Any(); active = null
                publish(PostDraftPhase.PENDING, PostDraftIssue.RECONCILIATION_REQUIRED, FailureReason.OUTCOME_UNKNOWN)
            } }; throw cancelled
        } catch (failure: Exception) {
            reviewedReturn.cancel()
            ticket?.revoke(); val reason = if (closed || (owned != null && owned !== generation) || !boundary.isCurrent(access.lease)) FailureReason.STALE_SESSION else reason(failure)
            withContext(NonCancellable + dispatcher) { if (owned === generation && owner === operation) {
                acknowledged = false; publish(if (reason == FailureReason.OFFLINE) PostDraftPhase.OFFLINE else PostDraftPhase.ERROR, when (reason) {
                    FailureReason.OFFLINE -> PostDraftIssue.OFFLINE; FailureReason.CONFLICT, FailureReason.OUTCOME_UNKNOWN -> PostDraftIssue.RECONCILIATION_REQUIRED
                    FailureReason.UNAVAILABLE -> PostDraftIssue.CAPACITY; FailureReason.INVALID_DATA -> PostDraftIssue.DATA_UNVERIFIED
                    FailureReason.STALE_SESSION, FailureReason.UNAUTHENTICATED -> PostDraftIssue.SESSION_UNAVAILABLE; else -> PostDraftIssue.STORAGE
                }, reason)
            } }; return PortResult.Failure(reason)
        } finally {
            // Pending authorization is released even on a failure before controller admission.
            // Authorized/published tickets remain historical; cancellation cannot retract ACK.
            reviewedReturn.cancel(); cancellation?.dispose()
        }
    }
    private fun reason(failure: Exception) = (failure as? MealFailure)?.reason ?: if (failure is WireDecodingException || failure is IllegalArgumentException)
        FailureReason.INVALID_DATA else FailureReason.STORAGE_FAILURE
    /** Owner-registered notification only, on the serialized identity dispatcher. Preparing a
     * terminal is not proof that it committed. Fence late callbacks/ACKs and hide this root;
     * retain all actual held evidence and original bytes for explicit reconciliation. */
    private fun publicationRootChanging(clientId: String) {
        if (closed || !boundary.isCurrent(access.lease)) return
        delivery?.revoke(); revokeReviewedIntent(notifyPublicationEntry = false); validationFeedback = null; publicationForReturn = null
        generation = Any(); active = null; consent = null; acknowledged = false; historical = true
        suppressedPublicationRoots = suppressedPublicationRoots + clientId
        page = page.filterNot { it.clientDraftId == clientId }
        if (selectedId == clientId) {
            selectedId = null
            if (screen == PostDraftScreen.EDITOR) screen = PostDraftScreen.LOCAL_LIST
        }
        publish(PostDraftPhase.PENDING, PostDraftIssue.RECONCILIATION_REQUIRED)
    }
    private fun redact() {
        delivery?.revoke(); revokeReviewedIntent(); delivery = null; generation = Any(); active = null; operation = null; last = DraftControllerRecord(PostRecord(0))
        journalObserved = false
        view = null; unobservedDispatch = null; validationFeedback = null; publicationForReturn = null
        selectedId = null; consent = null; applying = null; editForReturn = null; applyForReturn = null; invalidatePage(); acknowledged = false
        suppressedPublicationRoots = emptySet()
        journalParticipant?.let { journal.release(it) }
        mutable.value = PostDraftState.unavailable()
    }
    private class DispatchProjection(val original: DraftControllerOriginal, private val owner: Any) {
        var hasReturned = false; private set
        fun returned(operationOwner: Any) { if (operationOwner === owner) hasReturned = true }
        fun matches(value: DraftControllerOriginal) = value.id == original.id && value.operation == original.operation && value.clientId == original.clientId
    }
    private class ValidationFeedback(val clientId: String, val generation: Any, val publication: Publication?)
    private class ReviewedReturn {
        // Identity-dispatcher bookkeeping only; never read by the cancellation callback.
        var operation: Any? = null
        private class Bound(val ticket: DraftControllerDelivery?, val gate: PublicationDeliveryGate?, val cancelled: Boolean)
        private val bound = AtomicReference(Bound(null, null, false))
        val gate: PublicationDeliveryGate? get() = bound.load().gate
        fun bindTicket(ticket: DraftControllerDelivery): Boolean = bind(ticket, null)
        fun bindGate(gate: PublicationDeliveryGate): Boolean = bind(null, gate)
        private fun bind(ticket: DraftControllerDelivery?, gate: PublicationDeliveryGate?): Boolean {
            while (true) {
                val old = bound.load()
                if (old.cancelled || (ticket != null && old.ticket != null) || (gate != null && old.gate != null)) {
                    ticket?.revoke(); gate?.cancel(); return false
                }
                if (bound.compareAndSet(old, Bound(ticket ?: old.ticket, gate ?: old.gate, false))) return true
            }
        }
        fun cancel() {
            while (true) {
                val old = bound.load()
                if (old.cancelled) return
                if (bound.compareAndSet(old, Bound(old.ticket, old.gate, true))) {
                    old.gate?.cancel(); old.ticket?.revoke(); return
                }
            }
        }
    }
    private class SaveReview(val presentation: ReviewedDraftSavePresentation, val generation: Any,
        val local: DraftLocalSnapshotV1, val original: PostDraftCommandV2.ReviewedPatch,
        val principal: PublicationPrincipalSnapshot, val witness: PublicationDeliveryWitness) {
        val published = AtomicReference(false)
    }
    private class SaveRetry(val presentation: ReviewedDraftRetryPresentation, val generation: Any,
        val original: PostDraftCommandV2.ReviewedPatch, val local: DraftLocalSnapshotV1, val command: CommandView?,
        val principal: PublicationPrincipalSnapshot, val witness: PublicationDeliveryWitness) {
        val published = AtomicReference(false)
    }
    private class ReviewedDispatch(val operation: Any, val generation: Any, val original: PostDraftCommandV2.ReviewedPatch,
        val principal: PublicationPrincipalSnapshot, val witness: PublicationDeliveryWitness,
        val kind: ReviewedDraftRetryKind, val local: DraftLocalSnapshotV1, val expiresAt: Long)
    private class Publication(val ticket: DraftControllerDelivery, val edit: DraftControllerEdit?, val apply: DraftControllerApply?,
        val expected: PostDraftState, val projected: PostDraftState, val principalGate: PublicationDeliveryGate?,
        val saveReview: SaveReview?, val saveRetry: SaveRetry?) {
        val lateValidation = AtomicReference<PostDraftState?>(null)
    }
}
