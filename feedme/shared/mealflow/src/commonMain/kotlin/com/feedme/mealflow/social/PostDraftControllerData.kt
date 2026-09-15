package com.feedme.mealflow.social

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.mealflow.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Controller-only tagged views. Common text getters are presentation conveniences, never a
 * serialization model. Every copy unwraps the original format and retains its other fields. */
internal class DraftControllerLocal private constructor(val legacy: PostLocal?, val current: DraftLocalSnapshotV1?,
    private val snapshots: DraftLocalSnapshotCodecV1?) {
    constructor(value: PostLocal) : this(value, null, null)
    constructor(value: DraftLocalSnapshotV1, snapshots: DraftLocalSnapshotCodecV1) : this(null, value, snapshots)
    val id get() = legacy?.id ?: current!!.clientDraftId
    val revision get() = legacy?.revision ?: current!!.localRevision
    val caption get() = legacy?.caption ?: current!!.content.caption
    val alt get() = if (legacy != null) legacy.alt else current!!.content.altText
    val server get() = legacy?.server ?: (current?.serverAssociation as? DraftServerAssociationV1.Observed)?.exactPostDraft
    val etag get() = legacy?.etag ?: (current?.serverAssociation as? DraftServerAssociationV1.Observed)?.etag
    val requiresReviewedSave get() = current?.content is DraftLocalContentV1.ComposerV2
    fun copy(revision: Long = this.revision, caption: String = this.caption, alt: String? = this.alt,
        server: WireDocument? = this.server, etag: String? = this.etag): DraftControllerLocal {
        legacy?.let { return DraftControllerLocal(it.copy(revision = revision, caption = caption, alt = alt, server = server, etag = etag)) }
        val codec = snapshots!!; var value = current!!
        if (revision != value.localRevision || caption != value.content.caption || alt != value.content.altText)
            value = codec.withText(value, caption, alt, revision)
        if (server !== this.server || etag != this.etag) {
            if ((server == null) != (etag == null)) mealFail(FailureReason.INVALID_DATA)
            value = codec.withServerAssociation(value, if (server == null) DraftServerAssociationV1.NotObserved
                else DraftServerAssociationV1.Observed(server, etag!!))
        }
        return DraftControllerLocal(value, codec)
    }
    fun same(other: DraftControllerLocal?): Boolean {
        if (other == null || (legacy == null) != (other.legacy == null)) return false
        if (current != null) return current.exactUtf8.copyForCodec().contentEquals(other.current!!.exactUtf8.copyForCodec())
        return id == other.id && revision == other.revision && caption == other.caption && alt == other.alt && etag == other.etag &&
            (if (server == null || other.server == null) server == null && other.server == null else equal(server!!, other.server!!))
    }
    override fun toString() = "DraftControllerLocal(<redacted>)"
}

internal class DraftControllerOriginal private constructor(val legacy: PostOriginal?, val current: PostDraftCommandV2?) {
    constructor(value: PostOriginal) : this(value, null)
    constructor(value: PostDraftCommandV2) : this(null, value)
    val id get() = legacy?.id ?: current!!.id
    val clientId get() = legacy?.clientId ?: current!!.clientId
    val localRevision get() = legacy?.localRevision ?: current!!.localRevision
    val operation get() = legacy?.operation ?: current!!.operation
    val created get() = legacy?.created ?: current!!.created
    val baseline get() = legacy?.baseline ?: current?.legacyOriginal?.baseline ?: (current as? PostDraftCommandV2.ReviewedPatch)?.original?.baseline
    val etag get() = legacy?.etag ?: current?.legacyOriginal?.etag ?: (current as? PostDraftCommandV2.ReviewedPatch)?.original?.etag
    val serverId get() = baseline?.let { postString(it, "id") }
    fun call() = legacy?.call() ?: current!!.historicalCall()
    fun receipt(adapter: PostDraftAdapter, reviewed: ReviewedPostDraftAdapter?, reply: ApiReply): PostDraftObservation? {
        val reviewedOriginal = current as? PostDraftCommandV2.ReviewedPatch
        if (reviewedOriginal == null) return adapter.receipt(legacy ?: current!!.legacyOriginal!!, reply)
        val actual = (reviewed ?: mealFail(FailureReason.NOT_CONFIGURED)).receipt(call(), reviewedOriginal.original.baseline,
            reviewedOriginal.original.etag, reply)
        return PostDraftObservation(actual.document, actual.etag, false)
    }
    fun possibleOriginalResult(adapter: PostDraftAdapter, reviewed: ReviewedPostDraftAdapter?, observed: WireDocument, observedETag: String) {
        val reviewedOriginal = current as? PostDraftCommandV2.ReviewedPatch
        if (reviewedOriginal == null) adapter.possibleOriginalResult(legacy ?: current!!.legacyOriginal!!, observed, observedETag)
        else (reviewed ?: mealFail(FailureReason.NOT_CONFIGURED)).possibleOriginalResult(call(), reviewedOriginal.original.baseline,
            reviewedOriginal.original.etag, observed, observedETag)
    }
    fun completion(unsent: Boolean, observed: PostDraftObservation? = null): DraftControllerCompletion {
        val text = PostCompletion(id, operation, clientId, unsent)
        legacy?.let { return DraftControllerCompletion(text) }
        val reviewedOriginal = current as? PostDraftCommandV2.ReviewedPatch
        return DraftControllerCompletion(if (reviewedOriginal == null) PostDraftCompletionV2.Legacy(text)
            else if (unsent) PostDraftCompletionV2.ReviewedUnsent(reviewedOriginal)
            else PostDraftCompletionV2.ReviewedApplied(reviewedOriginal, observed?.document ?: mealFail(FailureReason.CONFLICT), observed.etag))
    }
    override fun toString() = "DraftControllerOriginal(<redacted>)"
}

internal class DraftControllerCompletion private constructor(val legacy: PostCompletion?, val current: PostDraftCompletionV2?) {
    constructor(value: PostCompletion) : this(value, null)
    constructor(value: PostDraftCompletionV2) : this(null, value)
    val commandId get() = legacy?.commandId ?: current!!.commandId
    val clientId get() = legacy?.clientId ?: current!!.clientId
    val operation get() = legacy?.operation ?: current!!.operation
    val unsent get() = legacy?.unsent ?: current!!.unsent
    fun matches(proof: DraftControllerApply): Boolean {
        if ((legacy == null) != (proof.legacy == null) || commandId != proof.original.id || clientId != proof.original.clientId ||
            operation != proof.original.operation || unsent != proof.unsent) return false
        val reviewed = proof.current?.original as? PostDraftCommandV2.ReviewedPatch
        if (reviewed == null) return current == null || current is PostDraftCompletionV2.Legacy
        val retained = when (current) {
            is PostDraftCompletionV2.ReviewedApplied -> current.original
            is PostDraftCompletionV2.ReviewedUnsent -> current.original
            else -> return false
        }
        // Domain bytes are checked by the owner; this also prevents cross-variant completion
        // retirement before any mutation, without accepting a historical original as proof.
        return (current is PostDraftCompletionV2.ReviewedUnsent) == proof.unsent &&
            retained.original.id == reviewed.original.id && retained.original.clientId == reviewed.original.clientId &&
            retained.original.localRevision == reviewed.original.localRevision && retained.original.created == reviewed.original.created &&
            postSameCall(retained.historicalCall(), reviewed.historicalCall()) &&
            retained.historicalReview.exactUtf8.copyForCodec().contentEquals(reviewed.historicalReview.exactUtf8.copyForCodec()) &&
            retained.original.baseline.encodeUtf8().contentEquals(reviewed.original.baseline.encodeUtf8()) &&
            retained.expectedFields.encodeUtf8().contentEquals(reviewed.expectedFields.encodeUtf8())
    }
    override fun toString() = "DraftControllerCompletion(<redacted>)"
}

internal class DraftControllerTerminal private constructor(val legacy: PostTerminal?, val current: PostDraftTerminalV2?) {
    constructor(value: PostTerminal) : this(value, null)
    constructor(value: PostDraftTerminalV2) : this(null, value)
    val clientId get() = legacy?.clientId ?: current!!.clientId
    val serverId: String? get() = legacy?.serverId ?: when (current) {
        is PostDraftTerminalV2.LegacyDiscard -> current.exactLegacy.serverId
        is PostDraftTerminalV2.Published -> (current.link.historicalReview.exactReviewedLocalSnapshot.serverAssociation as?
            DraftServerAssociationV1.Observed)?.exactPostDraft?.let { postString(it, "id") }
        null -> null
    }
    override fun toString() = "DraftControllerTerminal(<redacted>)"
}

internal class DraftControllerRecord private constructor(val legacy: PostRecord?, val current: PostDraftV2Record?,
    val snapshots: DraftLocalSnapshotCodecV1?) {
    constructor(value: PostRecord) : this(value, null, null)
    constructor(value: PostDraftV2Record, snapshots: DraftLocalSnapshotCodecV1) : this(null, value, snapshots)
    val clock get() = legacy?.clock ?: current!!.clock
    val locals get() = legacy?.locals?.map(::DraftControllerLocal) ?: current!!.locals.map { DraftControllerLocal(it, snapshots!!) }
    val issued get() = legacy?.issued ?: current!!.issued
    val tombstones get() = legacy?.tombstones?.map(::DraftControllerTerminal) ?: current!!.terminals.map(::DraftControllerTerminal)
    val command get() = legacy?.command?.let(::DraftControllerOriginal) ?: current?.command?.let(::DraftControllerOriginal)
    val completion get() = legacy?.completion?.let(::DraftControllerCompletion) ?: current?.completion?.let(::DraftControllerCompletion)
    val localPending get() = legacy?.localPending ?: current?.localPending
    fun requiresPublicationReconciliation(clientId: String) = current?.publicationHold?.link?.clientDraftId == clientId
    fun newLocal(id: String, revision: Long, caption: String, alt: String?, server: WireDocument? = null, etag: String? = null): DraftControllerLocal {
        if (legacy != null) return DraftControllerLocal(PostLocal(id, revision, caption, alt, server, etag))
        if ((server == null) != (etag == null)) mealFail(FailureReason.INVALID_DATA)
        // An explicit GET is historical data, not new review. Its full document is retained as
        // an association; no disclosure text or selected composer choices are manufactured.
        return DraftControllerLocal(snapshots!!.create(id, revision, DraftLocalContentV1.TextV1(caption, alt),
            if (server == null) DraftServerAssociationV1.NotObserved else DraftServerAssociationV1.Observed(server, etag!!)), snapshots)
    }
    fun original(value: PostOriginal) = if (legacy != null) DraftControllerOriginal(value) else DraftControllerOriginal(when (value.operation) {
        "createPostDraft" -> PostDraftCommandV2.TextCreate(value)
        "updatePostDraft" -> PostDraftCommandV2.TextPatch(value)
        "deletePostDraft" -> PostDraftCommandV2.Discard(value)
        else -> mealFail(FailureReason.INVALID_DATA)
    })
    fun terminal(value: PostTerminal) = if (legacy != null) DraftControllerTerminal(value)
        else DraftControllerTerminal(PostDraftTerminalV2.LegacyDiscard(value))
    fun copy(clock: Long = this.clock, locals: List<DraftControllerLocal> = this.locals, issued: List<String> = this.issued,
        tombstones: List<DraftControllerTerminal> = this.tombstones, command: DraftControllerOriginal? = this.command,
        completion: DraftControllerCompletion? = this.completion, localPending: PostLocalPending? = this.localPending): DraftControllerRecord {
        if (legacy != null) return DraftControllerRecord(legacy.copy(clock = clock,
            locals = locals.map { it.legacy ?: mealFail(FailureReason.CONFLICT) }, issued = issued,
            tombstones = tombstones.map { it.legacy ?: mealFail(FailureReason.CONFLICT) },
            command = command?.let { it.legacy ?: mealFail(FailureReason.CONFLICT) },
            completion = completion?.let { it.legacy ?: mealFail(FailureReason.CONFLICT) }, localPending = localPending))
        // Full current copy preserves publication hold, reservation and remainders verbatim.
        // The sole owner recomputes reservation and validates the entire row before encoding.
        return DraftControllerRecord(current!!.copy(clock = clock,
            locals = locals.map { it.current ?: mealFail(FailureReason.CONFLICT) }, issued = issued,
            terminals = tombstones.map { it.current ?: mealFail(FailureReason.CONFLICT) },
            command = command?.let { it.current ?: mealFail(FailureReason.CONFLICT) },
            completion = completion?.let { it.current ?: mealFail(FailureReason.CONFLICT) }, localPending = localPending), snapshots!!)
    }
    override fun toString() = "DraftControllerRecord(<redacted>)"
}

internal class DraftControllerEntry(val record: PrivateRecord?, val value: DraftControllerRecord,
    private val ownerCurrentEntry: PostDraftV2Entry? = null) {
    fun legacy() = PostEntry(record, value.legacy ?: mealFail(FailureReason.CONFLICT))
    // Current mutations require the exact entry registered by this actual owner/use. A view
    // must not reconstruct an equal-looking entry or launder copied row data into admission.
    fun current() = (ownerCurrentEntry ?: mealFail(FailureReason.CONFLICT)).also {
        if (it.record !== record || it.value !== value.current) mealFail(FailureReason.CONFLICT)
    }
    override fun toString() = "DraftControllerEntry(<redacted>)"
}

/** Wrappers dispatch to separate, unchanged legacy witnesses or full current witnesses. Their
 * identity is the underlying held object, not the short-lived wrapper read from the registry. */
internal class DraftControllerEdit private constructor(val legacy: PostEdit?, val current: PostDraftCurrentEdit?,
    private val snapshots: DraftLocalSnapshotCodecV1?) {
    constructor(value: PostEdit) : this(value, null, null)
    constructor(value: PostDraftCurrentEdit, snapshots: DraftLocalSnapshotCodecV1) : this(null, value, snapshots)
    val proposed get() = legacy?.proposed?.let(::DraftControllerLocal) ?: DraftControllerLocal(current!!.proposed, snapshots!!)
    var predecessors: List<DraftControllerLocal?>
        get() = legacy?.predecessors?.map { it?.let(::DraftControllerLocal) }
            ?: current!!.predecessors.map { it?.let { value -> DraftControllerLocal(value, snapshots!!) } }
        set(values) {
            if (legacy != null) legacy.predecessors = values.map { it?.let { value -> value.legacy ?: mealFail(FailureReason.CONFLICT) } }
            else current!!.predecessors = values.map { it?.let { value -> value.current ?: mealFail(FailureReason.CONFLICT) } }
        }
    var mutation: StoreMutation.Put?
        get() = legacy?.mutation ?: current?.mutation
        set(value) { if (legacy != null) legacy.mutation = value else current!!.mutation = value }
    fun sameIdentity(other: DraftControllerEdit?) = other != null && if (legacy != null) legacy === other.legacy else current === other.current
    fun delivered() = if (legacy != null) legacy.delivery?.delivered(legacy) == true else current!!.delivery?.delivered(current) == true
    override fun toString() = "DraftControllerEdit(<redacted>)"
}
internal class DraftControllerApply private constructor(val legacy: PostApply?, val current: PostDraftCurrentApply?) {
    constructor(value: PostApply) : this(value, null)
    constructor(value: PostDraftCurrentApply) : this(null, value)
    val original get() = legacy?.original?.let(::DraftControllerOriginal) ?: DraftControllerOriginal(current!!.original)
    val mutation get() = legacy?.mutation ?: current!!.mutation
    val receiptRevision get() = legacy?.receiptRevision ?: current!!.receiptRevision
    val unsent get() = legacy?.unsent ?: current!!.unsent
    var archive: StoreMutation.Put?
        get() = legacy?.archive ?: current?.archive
        set(value) { if (legacy != null) legacy.archive = value else current!!.archive = value }
    fun sameIdentity(other: DraftControllerApply?) = other != null && if (legacy != null) legacy === other.legacy else current === other.current
    fun delivered() = if (legacy != null) legacy.delivery?.delivered(legacy) == true else current!!.delivery?.delivered(current) == true
    override fun toString() = "DraftControllerApply(<redacted>)"
}

/** Authorization is a revocable local operation decision, not acknowledgement. It precedes
 * any ACK emission. Only the exact bound StateFlow CAS below can publish an ACK and then
 * stamp the unchanged legacy/current held witness. No decoded row or caller boolean suffices.
 *
 * Revoke wins while Pending/Armed. Once authorization wins, later navigation must change
 * StateFlow: an earlier navigation makes the exact expected-state CAS fail; a later one
 * cannot retroactively erase an ACK already emitted before it. Failed CAS retains evidence.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class DraftControllerDelivery {
    private val legacy = PostDelivery()
    private val current = PostDraftCurrentDelivery()
    private sealed interface State
    private data object Pending : State
    private class Armed(val edit: DraftControllerEdit?, val apply: DraftControllerApply?, val publication: Any,
        val flow: MutableStateFlow<PostDraftState>) : State
    private class Authorized(val ticket: Authorization, val armed: Armed, val expected: PostDraftState,
        val projected: PostDraftState) : State
    private class Publishing(val authorized: Authorized) : State
    private class Published(val projected: PostDraftState) : State
    private data object Revoked : State
    private data object Abandoned : State
    private val state = AtomicReference<State>(Pending)
    /** No readable proof fields; a fabricated identity is never registered in this ticket. */
    class Authorization internal constructor() { override fun toString() = "DraftDeliveryAuthorization(<redacted>)" }

    fun arm(edit: DraftControllerEdit?, apply: DraftControllerApply?, publication: Any,
        flow: MutableStateFlow<PostDraftState>): Boolean {
        if (edit != null && apply != null && (edit.legacy == null) != (apply.legacy == null)) return false
        val armed = if (edit?.current != null || apply?.current != null) current.arm(edit?.current, apply?.current, publication)
            else legacy.arm(edit?.legacy, apply?.legacy, publication)
        return armed && state.compareAndSet(Pending, Armed(edit, apply, publication, flow))
    }
    fun retain(edit: DraftControllerEdit?, apply: DraftControllerApply?) {
        edit?.legacy?.delivery = legacy; apply?.legacy?.delivery = legacy
        edit?.current?.delivery = current; apply?.current?.delivery = current
    }
    fun authorize(edit: DraftControllerEdit?, apply: DraftControllerApply?, publication: Any,
        expected: PostDraftState, projected: PostDraftState): Authorization? {
        val armed = state.load() as? Armed ?: return null
        if (armed.edit !== edit || armed.apply !== apply || armed.publication !== publication) return null
        val ticket = Authorization()
        return if (state.compareAndSet(armed, Authorized(ticket, armed, expected, projected))) ticket else null
    }
    /** Own the exact CAS and evidence stamp together, with no injected callback or await.
     * A collector may react synchronously to CAS; revoke after authorization must therefore
     * preserve this in-progress publication. It still cannot overwrite that collector's view.
     */
    fun publishAuthorized(ticket: Authorization): PostDraftState? {
        val authorized = state.load() as? Authorized ?: return null
        if (authorized.ticket !== ticket) return null
        val publishing = Publishing(authorized)
        if (!state.compareAndSet(authorized, publishing)) return null
        if (!authorized.armed.flow.compareAndSet(authorized.expected, authorized.projected)) {
            state.compareAndSet(publishing, Abandoned)
            legacy.revoke(); current.revoke()
            return null
        }
        val armed = authorized.armed
        // No other path can revoke these underlying witnesses after authorization. Thus the
        // exact arm necessarily remains deliverable once the bound StateFlow CAS succeeds.
        val delivered = if (armed.edit?.current != null || armed.apply?.current != null)
            current.deliver(armed.edit?.current, armed.apply?.current, armed.publication)
        else legacy.deliver(armed.edit?.legacy, armed.apply?.legacy, armed.publication)
        check(delivered) { "Draft delivery invariant" }
        check(state.compareAndSet(publishing, Published(authorized.projected))) { "Draft delivery invariant" }
        return authorized.projected
    }
    fun abandon(ticket: Authorization) {
        val authorized = state.load() as? Authorized ?: return
        if (authorized.ticket === ticket && state.compareAndSet(authorized, Abandoned)) { legacy.revoke(); current.revoke() }
    }
    fun revoke() { while (true) {
        val before = state.load()
        if (before !== Pending && before !is Armed) return
        if (state.compareAndSet(before, Revoked)) { legacy.revoke(); current.revoke(); return }
    } }
}
