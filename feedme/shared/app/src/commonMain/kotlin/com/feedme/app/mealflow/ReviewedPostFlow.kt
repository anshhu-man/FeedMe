package com.feedme.app.mealflow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.feedme.app.FeedMeColors
import com.feedme.app.FeedMeDetails
import com.feedme.app.FeedMeTheme
import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.mealflow.social.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

internal class RetainedPostSelections(media: List<String>, val attachment: OptionalValue<PublicationAttachment>,
    val source: OptionalValue<String>, val audience: PublicationAudience?) {
    private val ids = media.toList()
    val mediaIds get() = ids.toList()
    override fun toString() = "RetainedPostSelections(<redacted>)"
}

/** Reads only actual retained selections. No missing boolean/audience/disclosure is defaulted,
 * no UUID/numeric/array normalization, no new request or principal/permission claim. */
internal fun retainedPostSelections(document: WireDocument?): RetainedPostSelections {
    if (document == null) return RetainedPostSelections(emptyList(), OptionalValue.Absent, OptionalValue.Absent, null)
    val body = Json.parseToJsonElement(document.encodeUtf8().decodeToString()).jsonObject
    fun string(value: JsonElement): String = value.jsonPrimitive.also { require(it.isString) }.content
    val media = body.getValue("mediaIds").jsonArray.map(::string)
    val source = body["sourcePostId"]?.let { OptionalValue.Present(string(it)) } ?: OptionalValue.Absent
    val audience = body["audience"]?.jsonObject?.let {
        val circles = it.getValue("circleIds").jsonArray.map(::string)
        when (string(it.getValue("kind"))) {
            "self" -> { require(circles.isEmpty()); PublicationAudience.OnlyYou }
            "circles" -> PublicationAudience.Circles(circles)
            else -> error("Unsupported retained audience")
        }
    }
    val attachment = body["attachment"]?.jsonObject?.let {
        val sourceKeys = listOf("recipeVersionId", "planId", "personalRecipe").filter(it::containsKey)
        require(sourceKeys.size == 1)
        val selected = when (sourceKeys.single()) {
            "recipeVersionId" -> PublicationAttachmentSource.RecipeVersion(string(it.getValue("recipeVersionId")))
            "planId" -> PublicationAttachmentSource.Plan(string(it.getValue("planId")))
            else -> PublicationAttachmentSource.Personal(WireDocument.parse(it.getValue("personalRecipe").toString()))
        }
        val status = when (string(it.getValue("reviewStatus"))) {
            "reviewed" -> AttachmentReviewStatus.REVIEWED
            "personal" -> AttachmentReviewStatus.PERSONAL
            else -> error("Unsupported retained review status")
        }
        val rights = when (string(it.getValue("rightsBasis"))) {
            "catalogRedistributable" -> AttachmentRightsBasis.CATALOG_REDISTRIBUTABLE
            "creatorOriginal" -> AttachmentRightsBasis.CREATOR_ORIGINAL
            else -> error("Unsupported retained rights basis")
        }
        OptionalValue.Present(PublicationAttachment(selected, it.getValue("confirmedChanges").jsonArray.map(::string), status, rights))
    } ?: OptionalValue.Absent
    return RetainedPostSelections(media, attachment, source, audience)
}

/** UI route policy only. This never grants migration, review, Save or publication authority. */
enum class ReviewedPostLegacyFormatUiPolicy { EXPLICIT_UPGRADE, CURRENT_FORMAT_ONLY }

internal fun reviewedPostLocalUpgradeOffered(policy: ReviewedPostLegacyFormatUiPolicy, needsUpgrade: Boolean): Boolean =
    needsUpgrade && policy == ReviewedPostLegacyFormatUiPolicy.EXPLICIT_UPGRADE

internal fun reviewedPostDraftPageRequired(sessionUnavailable: Boolean, formatActionsAllowed: Boolean,
    showingPublicationHistory: Boolean): Boolean = sessionUnavailable || (!formatActionsAllowed && !showingPublicationHistory)

private enum class ReviewedEntryPage { DRAFTS, CHOICES, PUBLICATION_TARGET, PUBLICATIONS, RESTORED_LOCAL }

/** The operation belongs to the retained wrapper, not the draft page it immediately replaces. */
internal fun launchReviewedEntryInspection(owner: CoroutineScope, inspect: suspend () -> Unit): Job =
    owner.launch { inspect() }

/** Wrapper-owned loading state, not controller authority. The composer is not mounted while
 * this exact page-opening read is pending. A retired read cannot report an error or settle a
 * newer read. All access is on the UI dispatcher; currentView reads the actual draft state. */
internal class ReviewedPublicationHistoryRead {
    private class Read(val currentView: () -> Boolean)
    private var active by mutableStateOf<Read?>(null)
    val pending get() = active != null

    fun begin(currentView: () -> Boolean): Any = Read(currentView).also { active = it }
    fun retire() { active = null }
    private fun owns(ticket: Any) = active === ticket && active?.currentView?.invoke() == true

    suspend fun restore(controller: PostComposerController, ticket: Any, failed: (FailureReason) -> Unit) {
        try {
            if (!owns(ticket)) return
            val result = controller.restore()
            if (result is PortResult.Failure && owns(ticket)) failed(result.reason)
        } finally {
            if (active === ticket) active = null
        }
    }
}

/** Detached display/navigation identity only. This never admits a controller operation. */
internal class ReviewedDraftViewPin(val screen: PostDraftScreen, val clientDraftId: String?, val localRevision: Long?) {
    fun matches(screen: PostDraftScreen, clientDraftId: String?, localRevision: Long?, available: Boolean): Boolean =
        available && this.screen == screen && this.clientDraftId == clientDraftId && this.localRevision == localRevision
    override fun toString() = "ReviewedDraftViewPin(<redacted>)"
}

internal fun reviewedEntryDeliveryCurrent(expectedTicket: Any, actualTicket: Any, expected: ReviewedDraftViewPin,
    screen: PostDraftScreen, clientDraftId: String?, localRevision: Long?, available: Boolean): Boolean =
    expectedTicket === actualTicket && expected.matches(screen, clientDraftId, localRevision, available)

@Composable
private fun ReviewChoice(label: String, selected: Boolean, enabled: Boolean, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = choose)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ReviewedEntryPageFrame(back: () -> Unit, busy: Boolean, content: @Composable ColumnScope.() -> Unit) {
    FeedMeTheme {
        Box(Modifier.fillMaxSize().background(FeedMeColors.Paper).safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = back, modifier = Modifier.heightIn(min = 48.dp)) { Text("← Back") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                content()
            }
        }
    }
}

/** Actual configured entry, not a mock coordinator. Navigation/temporary input stays in memory,
 * never saved instance state. Composition never invokes restore/prepare/edit/confirm. Every call
 * below starts at an explicit visible action; actual facade/controller admission is authoritative. */
@Composable
fun FeedMeReviewedPostFlow(entry: ReviewedPostEntry,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit) =
    ReviewedPostFlowContent(entry, platformBackHandler, ReviewedPostLegacyFormatUiPolicy.EXPLICIT_UPGRADE, null)

/** Explicit outer route. No global HIDDEN observer: publication completion/history can hide
 * the draft without requesting exit. The old two-argument entry retains its existing behavior. */
@Composable
fun FeedMeReviewedPostFlow(entry: ReviewedPostEntry,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
    legacyFormatPolicy: ReviewedPostLegacyFormatUiPolicy, onExit: () -> Unit) =
    ReviewedPostFlowContent(entry, platformBackHandler, legacyFormatPolicy, onExit)

@Composable
private fun ReviewedPostFlowContent(entry: ReviewedPostEntry,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
    legacyFormatPolicy: ReviewedPostLegacyFormatUiPolicy, onExit: (() -> Unit)?) {
    val currentLegacyPolicy by rememberUpdatedState(legacyFormatPolicy)
    val draftState by entry.drafts.states.collectAsState()
    val publicationState by entry.publications.states.collectAsState()
    var page by remember(entry) { mutableStateOf(ReviewedEntryPage.DRAFTS) }
    var selection by remember(entry) { mutableStateOf<ReviewedPostSelection?>(null) }
    var selectionView by remember(entry) { mutableStateOf<ReviewedDraftViewPin?>(null) }
    var disclosure by remember(entry) { mutableStateOf<PublicationDisclosure?>(null) }
    var upgrade by remember(entry) { mutableStateOf<PreparedReviewedDraftUpgrade?>(null) }
    var failure by remember(entry) { mutableStateOf<FailureReason?>(null) }
    var note by remember(entry) { mutableStateOf<String?>(null) }
    var busy by remember(entry) { mutableStateOf(false) }
    var navigation by remember(entry) { mutableStateOf(Any()) }
    var retention by remember(entry) { mutableStateOf<RestoredLocalRetentionPresentation?>(null) }
    var retentionFailure by remember(entry) { mutableStateOf<FailureReason?>(null) }
    val publicationRead = remember(entry) { ReviewedPublicationHistoryRead() }
    DisposableEffect(publicationRead) { onDispose { publicationRead.retire() } }
    val scope = rememberCoroutineScope()
    fun formatActionsNow() = reviewedDraftMutationControlsAllowed(
        currentLegacyPolicy == ReviewedPostLegacyFormatUiPolicy.CURRENT_FORMAT_ONLY, entry.drafts.states.value.journalFormat)
    fun view() = entry.drafts.states.value.let {
        ReviewedDraftViewPin(it.screen, it.selected?.clientDraftId, it.selected?.localRevision)
    }
    fun currentReadView(ticket: Any, expected: ReviewedDraftViewPin): Boolean = entry.drafts.states.value.let {
        reviewedEntryDeliveryCurrent(ticket, navigation, expected, it.screen, it.selected?.clientDraftId,
            it.selected?.localRevision, it.phase != PostDraftPhase.UNAVAILABLE)
    }
    fun currentView(ticket: Any, expected: ReviewedDraftViewPin) = currentReadView(ticket, expected) && formatActionsNow()
    fun current(ticket: Any, clientId: String, revision: Long): Boolean {
        val state = entry.drafts.states.value
        return selectionView?.let { currentView(ticket, it) } == true &&
            state.selected?.clientDraftId == clientId && state.selected?.localRevision == revision
    }
    fun exit() {
        // Fence wrapper-owned delayed restore/inspection before the host begins async cleanup.
        publicationRead.retire()
        navigation = Any(); selection = null; selectionView = null; disclosure = null; upgrade = null
        retention = null; retentionFailure = null; failure = null; note = null; busy = false
        onExit?.invoke()
    }
    fun back() {
        publicationRead.retire()
        navigation = Any(); selection = null; selectionView = null; disclosure = null; upgrade = null; failure = null; note = null
        retention = null; retentionFailure = null
        busy = false; page = ReviewedEntryPage.DRAFTS
        scope.launch { entry.drafts.back() } // Cached fence only; never Save/Publish/abandon.
    }
    fun inspect(clientId: String, revision: Long, next: ReviewedEntryPage) {
        if (!formatActionsNow()) return
        val ticket = Any(); navigation = ticket; busy = true; selection = null; disclosure = null; upgrade = null
        val expected = view(); selectionView = expected
        failure = null; note = null; page = next
        launchReviewedEntryInspection(scope) {
            try {
                if (page != next || !currentView(ticket, expected)) return@launchReviewedEntryInspection
                when (val result = entry.inspectSelected(clientId, revision)) {
                    is PortResult.Failure -> if (currentView(ticket, expected)) failure = result.reason
                    is PortResult.Value -> if (current(ticket, clientId, revision)) selection = result.value
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (currentView(ticket, expected)) failure = FailureReason.INVALID_DATA }
            finally { if (navigation === ticket) busy = false }
        }
    }
    fun prepareRetention(clientId: String, revision: Long) {
        if (busy || !formatActionsNow()) return
        val ticket = Any(); navigation = ticket
        val expected = view(); selectionView = expected
        retention = null; retentionFailure = null; failure = null; note = null; busy = true
        page = ReviewedEntryPage.RESTORED_LOCAL
        launchReviewedEntryInspection(scope) {
            try {
                if (page != ReviewedEntryPage.RESTORED_LOCAL || !currentView(ticket, expected)) return@launchReviewedEntryInspection
                when (val result = entry.prepareRestoredLocalRetention(clientId, revision)) {
                    is PortResult.Failure -> if (page == ReviewedEntryPage.RESTORED_LOCAL && currentView(ticket, expected))
                        retentionFailure = result.reason
                    is PortResult.Value -> if (page == ReviewedEntryPage.RESTORED_LOCAL && currentView(ticket, expected) &&
                        result.value.isCurrentForNavigation && result.value.snapshot.clientDraftId == clientId &&
                        result.value.snapshot.localRevision == revision) retention = result.value
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (page == ReviewedEntryPage.RESTORED_LOCAL && currentView(ticket, expected)) retentionFailure = FailureReason.INVALID_DATA
            } finally { if (navigation === ticket) busy = false }
        }
    }
    fun action(block: suspend (Any) -> Unit) {
        if (busy || !formatActionsNow()) return
        val ticket = navigation; val expected = view(); busy = true; failure = null
        scope.launch {
            try { if (formatActionsNow()) block(ticket) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (currentView(ticket, expected)) failure = FailureReason.INVALID_DATA }
            finally { if (navigation === ticket) busy = false }
        }
    }
    fun openPublications() {
        if (entry.drafts.states.value.phase == PostDraftPhase.UNAVAILABLE) return
        val ticket = Any(); navigation = ticket
        val expected = view()
        failure = null; note = null
        val read = publicationRead.begin {
            page == ReviewedEntryPage.PUBLICATIONS && currentReadView(ticket, expected)
        }
        // Show a Back-enabled loading frame until this read settles. Mounting actionable
        // cached reviews earlier lets their preparation supersede this read and expose its
        // obsolete error over the newer review. A refused read still leaves history available.
        page = ReviewedEntryPage.PUBLICATIONS
        scope.launch {
            publicationRead.restore(entry.publications, read) { failure = it }
        }
    }
    // A revoked account can never keep an old selection or modal visible, even before an effect
    // disposes temporary memory. It is the actual controller state, not an accepting UI flag.
    if (reviewedPostDraftPageRequired(draftState.phase == PostDraftPhase.UNAVAILABLE,
            reviewedDraftMutationControlsAllowed(legacyFormatPolicy == ReviewedPostLegacyFormatUiPolicy.CURRENT_FORMAT_ONLY,
                draftState.journalFormat), page == ReviewedEntryPage.PUBLICATIONS)) {
        FeedMePostDraftFlow(entry.drafts, platformBackHandler, onExit = if (onExit != null) ::exit else null,
            onOpenPublications = ::openPublications,
            hasRetainedPublication = publicationState.pending != null || publicationState.allocation != null || publicationState.history.isNotEmpty(),
            currentFormatOnly = legacyFormatPolicy == ReviewedPostLegacyFormatUiPolicy.CURRENT_FORMAT_ONLY)
    } else when (page) {
        ReviewedEntryPage.DRAFTS -> FeedMePostDraftFlow(entry.drafts, platformBackHandler,
            onReviewPrivateSave = { id, revision ->
                val ticket = Any(); navigation = ticket
                val expected = view()
                if (formatActionsNow()) when (val result = entry.prepareSelectedReviewedSave(id, revision)) {
                    is PortResult.Failure -> if (page == ReviewedEntryPage.DRAFTS && currentView(ticket, expected)) failure = result.reason
                    is PortResult.Value -> Unit // Render only actual controller.states reviewedSave.
                }
            },
            onReviewPublication = { id, revision -> inspect(id, revision, ReviewedEntryPage.PUBLICATION_TARGET) },
            onOpenPublications = ::openPublications,
            onEditChoices = { id, revision -> inspect(id, revision, ReviewedEntryPage.CHOICES) },
            hasRetainedPublication = publicationState.pending != null || publicationState.allocation != null || publicationState.history.isNotEmpty(),
            onReviewRestoredLocalRetention = ::prepareRetention,
            onExit = if (onExit != null) ::exit else null,
            currentFormatOnly = legacyFormatPolicy == ReviewedPostLegacyFormatUiPolicy.CURRENT_FORMAT_ONLY)
        ReviewedEntryPage.PUBLICATIONS -> if (publicationRead.pending) {
            platformBackHandler(true, ::back)
            ReviewedEntryPageFrame(::back, true) {
                PostReviewSection("Opening retained publication history", "Nothing is sent. You can use Back while this loads.")
            }
        } else FeedMePostComposerScreen(entry.publications, entry.drafts, platformBackHandler,
            actionAllowed = ::formatActionsNow) {
            navigation = Any(); selection = null; selectionView = null; disclosure = null; upgrade = null; page = ReviewedEntryPage.DRAFTS
        }
        ReviewedEntryPage.RESTORED_LOCAL -> {
            platformBackHandler(true, ::back)
            val expected = selectionView
            val review = retention
            val sameSelection = expected?.matches(draftState.screen, draftState.selected?.clientDraftId,
                draftState.selected?.localRevision, draftState.phase != PostDraftPhase.UNAVAILABLE) == true
            val currentReview = review != null && restoredRetentionReviewCurrent(review.isCurrentForNavigation,
                sameSelection, draftState.selected?.localAcknowledged == true)
            // Display validity is provided by the actual review epoch, never inferred from a
            // matching root/revision. Back/selection changes also discard this cached token.
            LaunchedEffect(sameSelection, review?.isCurrentForNavigation) {
                if (!sameSelection || review?.isCurrentForNavigation == false) retention = null
            }
            ReviewedEntryPageFrame(::back, busy) {
                restoredRetentionFailureText(retentionFailure)?.let { PostReviewSection("Local retention not confirmed", it) }
                when {
                    !sameSelection -> PostReviewSection("The draft view changed",
                        "These earlier restored changes are no longer shown for confirmation. Go Back and review the current draft. Nothing was saved or published by this navigation.")
                    currentReview && review != null -> RestoredPostRetentionReview(review, !busy && !draftState.busy) {
                        // Recheck the live detached validity inside the actual button action.
                        val ticket = navigation
                        val actual = entry.drafts.states.value
                        if (busy || !formatActionsNow() || retention !== review || !review.isCurrentForNavigation || expected == null ||
                            !currentView(ticket, expected) || actual.selected?.localAcknowledged == true) return@RestoredPostRetentionReview
                        retention = null; retentionFailure = null; busy = true
                        scope.launch {
                            try {
                                if (page != ReviewedEntryPage.RESTORED_LOCAL || !currentView(ticket, expected) ||
                                    !review.isCurrentForNavigation) return@launch
                                when (val result = entry.confirmRestoredLocalRetention(review.token)) {
                                    is PortResult.Failure -> if (page == ReviewedEntryPage.RESTORED_LOCAL && currentView(ticket, expected))
                                        retentionFailure = result.reason
                                    is PortResult.Value -> if (page == ReviewedEntryPage.RESTORED_LOCAL && currentView(ticket, expected)) {
                                        // Only the actual returned/current controller state supplies
                                        // acknowledgement. No wrapper flag claims storage success.
                                        if (result.value === entry.drafts.states.value) {
                                            selectionView = null; page = ReviewedEntryPage.DRAFTS
                                        }
                                    }
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) {
                                if (page == ReviewedEntryPage.RESTORED_LOCAL && currentView(ticket, expected)) retentionFailure = FailureReason.INVALID_DATA
                            } finally { if (navigation === ticket) busy = false }
                        }
                    }
                    else -> {
                        PostReviewSection(if (busy) "Checking local retention" else "Open a fresh local review",
                            "A review does not keep changes, retry an original or send anything. Confirmation, when available, creates only a new local acknowledgement; it does not recreate an earlier one.")
                        if (!busy) draftState.selected?.let { local ->
                            if (!local.localAcknowledged) Primary("Review restored local changes", !draftState.busy) {
                                prepareRetention(local.clientDraftId, local.localRevision)
                            }
                        }
                    }
                }
            }
        }
        ReviewedEntryPage.CHOICES, ReviewedEntryPage.PUBLICATION_TARGET -> {
            platformBackHandler(true, ::back)
            ReviewedEntryPageFrame(::back, busy) {
                note?.let { PostReviewSection("Review needed", it) }
                val selected = selection
                val selectedView = selectionView
                val stale = selectedView != null && !selectedView.matches(draftState.screen, draftState.selected?.clientDraftId,
                    draftState.selected?.localRevision, draftState.phase != PostDraftPhase.UNAVAILABLE)
                if (stale) PostReviewSection("The draft view changed", "These earlier choices are no longer shown for confirmation. Go Back and open a fresh review of the current draft. No Save or Publish was requested by this navigation.")
                else if (selected == null) PostReviewSection(if (busy && note == null) "Preparing the local view" else "Open a fresh review",
                    if (note == null) "No Save or Publish has been requested. If this view is no longer loading, go Back and open the current draft again." else "Back returns to the draft list. Reopen your draft; an upgrade never creates publication or private-Save consent.")
                else if (selected.needsUpgrade && !reviewedPostLocalUpgradeOffered(legacyFormatPolicy, selected.needsUpgrade)) {
                    PostReviewSection("Your older draft is kept", "Local-format upgrades are unavailable in this preview. Your existing draft is unchanged and read-only here. Back does not request a Save or publication.")
                    PostReviewValue("Retained caption", exactReviewText(JsonPrimitive(selected.caption)))
                    PostReviewValue("Image description", selected.altText?.let { exactReviewText(JsonPrimitive(it)) } ?: "Not included")
                    selected.exactChoices?.let {
                        PostContentReview(it, "Retained choices")
                        PostExactEvidence("Retained choice details", it)
                    }
                    selected.savedDraft?.let {
                        PostContentReview(it.document, "Associated private server draft — historical")
                        PostExactEvidence("Full associated draft details", it.document)
                    }
                    FeedMeDetails("Draft version details") {
                        PostReviewValue("Local draft ID", selected.clientDraftId)
                        PostReviewValue("Local revision", selected.localRevision.toString())
                        selected.savedDraft?.let { PostReviewValue("Stored server ETag", it.etag) }
                    }
                } else if (selected.needsUpgrade) {
                    PostReviewSection("Upgrade local review support", "This older local format needs an explicit local-only upgrade. It does not Save to the server, Publish, allocate a publication request or accept a review.")
                    PostReviewValue("Retained caption", selected.caption)
                    if (upgrade == null) Primary("Review local upgrade", !busy) {
                        action { ticket ->
                            if (!reviewedPostLocalUpgradeOffered(currentLegacyPolicy, selected.needsUpgrade)) return@action
                            when (val result = entry.prepareUpgrade(selected.clientDraftId, selected.localRevision)) {
                                is PortResult.Failure -> if (current(ticket, selected.clientDraftId, selected.localRevision)) failure = result.reason
                                is PortResult.Value -> if (current(ticket, selected.clientDraftId, selected.localRevision) &&
                                    reviewedPostLocalUpgradeOffered(currentLegacyPolicy, selected.needsUpgrade)) upgrade = result.value
                            }
                        }
                    }
                    upgrade?.let { token ->
                        PostReviewValue("Retained drafts in this upgrade", token.retainedDraftCount.toString())
                        Text("After confirmation, inspect your draft and make a new review. This is not consent to a server action.")
                        Primary("Confirm local-only upgrade", !busy) {
                            action { ticket ->
                                if (!reviewedPostLocalUpgradeOffered(currentLegacyPolicy, selected.needsUpgrade)) return@action
                                when (val result = entry.confirmUpgrade(token)) {
                                    is PortResult.Failure -> if (current(ticket, selected.clientDraftId, selected.localRevision)) failure = result.reason
                                    is PortResult.Value -> if (navigation === ticket) {
                                        upgrade = null; selection = null; disclosure = null
                                        note = "Local upgrade confirmed. Review again before choosing a private Save or publication."
                                    }
                                }
                            }
                        }
                    }
                    if (note != null) Text("Back returns to the draft list; reopen this draft for a fresh review.")
                } else if (page == ReviewedEntryPage.PUBLICATION_TARGET && !selected.needsExplicitChoices) {
                    PostReviewSection("Choose the publication source", "No private Save is inserted. Your selected route is checked again before a read-only review.")
                    selected.exactChoices?.let {
                        PostContentReview(it, "Current retained choices")
                        PostExactEvidence("Current retained choice details", it)
                    }
                    if (selected.savedDraft != null) {
                        FeedMeDetails("Saved draft version details") {
                            PostReviewValue("Saved draft", selected.savedDraft?.id.orEmpty())
                            PostReviewValue("Saved ETag", selected.savedDraft?.etag.orEmpty())
                        }
                        Primary("Review this saved-draft publication", !busy) {
                            action { ticket ->
                                when (val result = entry.prepareSelectedPublication(selected.clientDraftId, selected.localRevision, ReviewedPostBranch.SAVED_DRAFT)) {
                                    is PortResult.Failure -> if (current(ticket, selected.clientDraftId, selected.localRevision)) failure = result.reason
                                    is PortResult.Value -> if (current(ticket, selected.clientDraftId, selected.localRevision)) page = ReviewedEntryPage.PUBLICATIONS
                                }
                            }
                        }
                    } else if (entry.drafts.states.value.selected?.serverAssociated == false) {
                        Primary("Review direct publication", !busy) {
                            action { ticket ->
                                when (val result = entry.prepareSelectedPublication(selected.clientDraftId, selected.localRevision, ReviewedPostBranch.DIRECT_LOCAL)) {
                                    is PortResult.Failure -> if (current(ticket, selected.clientDraftId, selected.localRevision)) failure = result.reason
                                    is PortResult.Value -> if (current(ticket, selected.clientDraftId, selected.localRevision)) page = ReviewedEntryPage.PUBLICATIONS
                                }
                            }
                        }
                    } else PostReviewSection("Saved draft is unavailable", "A retained server association is not a direct-local draft. Refresh or reconcile it from the draft page.")
                } else {
                    if (page == ReviewedEntryPage.PUBLICATION_TARGET)
                        PostReviewSection("Choose before publishing", "This text-only draft does not yet have explicit audience and recipe-save choices.")
                    key(selected) {
                        ReviewedChoicesEditor(selected, disclosure, busy,
                            load = { purpose ->
                                disclosure = null
                                action { ticket ->
                                    when (val result = entry.loadDisclosure(selected.clientDraftId, purpose)) {
                                        is PortResult.Failure -> if (current(ticket, selected.clientDraftId, selected.localRevision)) failure = result.reason
                                        is PortResult.Value -> if (current(ticket, selected.clientDraftId, selected.localRevision)) disclosure = result.value
                                    }
                                }
                            },
                            save = { choices ->
                                action { ticket ->
                                    if (current(ticket, selected.clientDraftId, selected.localRevision))
                                        when (val result = entry.editChoices(selected.clientDraftId, selected.localRevision, choices)) {
                                            is PortResult.Failure -> if (current(ticket, selected.clientDraftId, selected.localRevision)) failure = result.reason
                                            is PortResult.Value -> if (navigation === ticket && result.value.phase != PostDraftPhase.UNAVAILABLE) {
                                                selection = null; disclosure = null; page = ReviewedEntryPage.DRAFTS
                                            }
                                        }
                                }
                            })
                    }
                }
            }
        }
    }
    if (failure != null && draftState.phase != PostDraftPhase.UNAVAILABLE) {
        AlertDialog(onDismissRequest = { failure = null }, title = { Text("Action not completed") },
            text = { Text(reviewedEntryFailureText(failure).orEmpty()) },
            confirmButton = { TextButton(onClick = { failure = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep reviewing") } })
    }
}

@Composable
private fun ReviewedChoicesEditor(selection: ReviewedPostSelection, disclosure: PublicationDisclosure?, busy: Boolean,
    load: (ReviewedPostPurpose) -> Unit, save: (ReviewedPostChoices) -> Unit) {
    val existing = selection.choices
    val retained = remember(selection) {
        runCatching { retainedPostSelections(selection.exactChoices ?: selection.savedDraft?.document) }.getOrNull()
    }
    var audience by remember(selection) { mutableStateOf<PublicationAudience?>(existing?.audience) }
    var keepOnPlate by remember(selection) { mutableStateOf<Boolean?>(existing?.keepOnPlate) }
    var allowSaves by remember(selection) { mutableStateOf<Boolean?>(existing?.allowRecipeSaves) }
    var purpose by remember(selection) { mutableStateOf<ReviewedPostPurpose?>(null) }
    var loadedFor by remember(selection) { mutableStateOf<ReviewedPostPurpose?>(null) }
    PostReviewSection("Audience & recipe saves", "These choices are kept locally first. A later private Save or Publish still needs its own exact review and confirmation.") {
        PostReviewValue("Caption kept unchanged", selection.caption)
        PostReviewValue("Image description kept unchanged", exactReviewText(selection.altText?.let(::JsonPrimitive)))
    }
    if (retained == null) {
        PostReviewSection("Retained selections cannot be edited here", "The complete media, attachment or source selection could not be represented. Nothing is dropped or replaced.")
        selection.exactChoices?.let { PostExactEvidence("All retained choice details", it) }
        return
    }
    selection.exactChoices?.let {
        PostContentReview(it, "Current retained choices")
        PostExactEvidence("Current retained choice details", it)
    } ?: selection.savedDraft?.let {
        PostContentReview(it.document, "Observed saved choices — not a Save receipt")
        PostExactEvidence("Observed saved choice details", it.document)
    }
    if (selection.exactChoices == null && selection.savedDraft == null)
        PostReviewSection("Text-only local draft", "No media, recipe attachment or source post is present. This editor will retain those absences. Upload and attachment selection are not connected here.")
    PostReviewSection("Who can see a publication?") {
        ReviewChoice("Only me", audience === PublicationAudience.OnlyYou, !busy) { audience = PublicationAudience.OnlyYou }
        (retained.audience as? PublicationAudience.Circles)?.let { circles ->
            ReviewChoice("Keep the current selected circles", audience === circles ||
                (audience as? PublicationAudience.Circles)?.orderedCircleIds == circles.orderedCircleIds, !busy) { audience = circles }
            circles.orderedCircleIds.forEachIndexed { index, id -> PostReviewValue("Circle " + (index + 1), id) }
        }
        Text("Choosing new circles is not connected here. Existing selections are shown exactly.", style = MaterialTheme.typography.bodySmall)
    }
    PostReviewSection("My Plate") {
        ReviewChoice("Keep on My Plate", keepOnPlate == true, !busy) { keepOnPlate = true }
        ReviewChoice("Do not keep on My Plate", keepOnPlate == false, !busy) { keepOnPlate = false }
    }
    PostReviewSection("Recipe saves") {
        ReviewChoice("Allow recipe saves", allowSaves == true, !busy && retained.attachment is OptionalValue.Present) { allowSaves = true }
        ReviewChoice("Do not allow recipe saves", allowSaves == false, !busy) { allowSaves = false }
        if (retained.attachment is OptionalValue.Absent) Text("Allowing recipe saves requires a retained recipe attachment. None is present.")
    }
    PostReviewSection("What will you review next?") {
        ReviewChoice("A private Save", purpose == ReviewedPostPurpose.PRIVATE_SAVE, !busy) { purpose = ReviewedPostPurpose.PRIVATE_SAVE }
        ReviewChoice("A publication", purpose == ReviewedPostPurpose.PUBLICATION, !busy) { purpose = ReviewedPostPurpose.PUBLICATION }
        OutlinedButton(onClick = { purpose?.let { loadedFor = it; load(it) } }, enabled = !busy && purpose != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Load current disclosure") }
    }
    disclosure?.let {
        PostDisclosure(it)
        if (loadedFor != purpose) Text("Your next action changed. Load its current disclosure before retaining these choices.")
    }
    val chosenAudience = audience
    val plate = keepOnPlate
    val saves = allowSaves
    val currentDisclosure = disclosure?.takeIf { loadedFor == purpose && purpose != null }
    Primary("Keep these choices on device", !busy && chosenAudience != null && plate != null && saves != null && currentDisclosure != null) {
        if (chosenAudience != null && plate != null && saves != null && currentDisclosure != null)
            save(ReviewedPostChoices(selection.caption, selection.altText?.let { OptionalValue.Present(it) } ?: OptionalValue.Absent,
                retained.mediaIds, chosenAudience, plate, retained.attachment, saves, currentDisclosure, retained.source))
    }
    Text("Nothing is privately saved to the server or published by this local action. Back does not apply these changed controls.", style = MaterialTheme.typography.bodySmall)
}
