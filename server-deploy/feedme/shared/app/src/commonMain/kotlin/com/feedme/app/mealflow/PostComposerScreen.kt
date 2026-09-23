package com.feedme.app.mealflow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.feedme.app.FeedMeColors
import com.feedme.app.FeedMeDetails
import com.feedme.app.FeedMeTheme
import com.feedme.app.FeedMeWordmark
import com.feedme.contracts.WireDocument
import com.feedme.mealflow.social.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/** Extracts display-only content from the exact immutable historical local snapshot. It never
 * rebuilds an original, changes choices, or treats observations as new review/ACK authority. */
internal fun publicationLocalContent(snapshot: WireDocument): WireDocument? {
    val root = Json.parseToJsonElement(snapshot.encodeUtf8().decodeToString()) as? JsonObject ?: return null
    val content = root["content"] as? JsonObject ?: return null
    return when ((content["kind"] as? JsonPrimitive)?.content) {
        "composer-v2" -> (content["exactChoicesUtf8"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { WireDocument.parse(it) }
        "text-v1" -> WireDocument.parse(content.toString())
        else -> null
    }
}

internal fun publicationBranchLabel(document: WireDocument): String {
    val body = Json.parseToJsonElement(document.encodeUtf8().decodeToString()) as? JsonObject ?: return "Target unavailable"
    return when {
        "draftId" !in body && "draftVersion" !in body -> "Direct local original — no private server Save"
        "draftId" in body && "draftVersion" in body -> "Exact saved-draft original"
        else -> "Incomplete saved target — not a direct local request"
    }
}

/** Display eligibility only, never permission to dispatch or acknowledgement evidence. */
internal fun publicationReviewIsStale(hasLiveToken: Boolean, navigationCurrent: Boolean, selectionCurrent: Boolean): Boolean =
    hasLiveToken && (!navigationCurrent || !selectionCurrent)

/** Recheck a presentation restriction at the launched callback, not just at render time.
 * This grants no operation, token, session or server authority. */
internal suspend fun runReviewedPostUiAction(actionAllowed: () -> Boolean, action: suspend () -> Unit) {
    if (actionAllowed()) action()
}

@Composable
internal fun PostAbandonDialog(title: String, onDismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("This only fences a request whose ID has not returned. It does not cancel a known original, a queued action or an attempted server request.")
            Text("If the provider is still working, its slot stays occupied until it settles. A late return cannot revive the abandoned request. Back does not abandon it.")
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Keep waiting") }
            Button(onClick = confirm, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Abandon this unreturned request") }
        }
    }, confirmButton = {})
}

@Composable
private fun OriginalPublicationContent(original: PublicationOriginalSnapshot) {
    PostReviewSection("Original publication", "This is the original you reviewed. Your current changes will not replace it.") {
        PostReviewValue("Target", publicationBranchLabel(original.exactOriginalPostWrite))
        val body = remember(original.exactOriginalPostWrite) {
            Json.parseToJsonElement(original.exactOriginalPostWrite.encodeUtf8().decodeToString()) as JsonObject
        }
        FeedMeDetails("Original request details") {
            PostReviewValue("Request ID", original.commandId)
            PostReviewValue("Original local revision", original.originalReviewedLocal.localRevision.toString())
            if ("draftId" in body || "draftVersion" in body) {
                PostReviewValue("Saved draft", exactReviewText(body["draftId"]))
                PostReviewValue("Saved version", exactReviewText(body["draftVersion"]))
            }
        }
    }
    PostContentReview(original.exactOriginalPostWrite, "Content in the original publication")
    PostDisclosure(original.displayedDisclosure)
    PostExactEvidence("All original request details", original.exactOriginalPostWrite)
}

@Composable
private fun CurrentPublicationLocal(current: PublicationLocalObservation, title: String) {
    PostReviewSection(title, "These observations do not replace the original or confirm publication.") {
        FeedMeDetails("Local version details") { PostReviewValue("Local revision", current.localRevision.toString()) }
    }
    publicationLocalContent(current.exactHistoricalSnapshot)?.let { PostContentReview(it, "Current local content") }
        ?: PostReviewSection("Local content is unavailable", "No current choices are inferred from the original.")
    PostExactEvidence("All separately observed local details", current.exactHistoricalSnapshot)
}

/** Actual controller surface. UI attachment performs no restore, disclosure fetch, prepare,
 * publish, retry or cancellation. Only visible explicit actions invoke the retained controller.
 * Busy is presentation-only; all tokens, policy and admission remain in the actual controllers. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedMePostComposerScreen(controller: PostComposerController, drafts: PostDraftController,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
    onBack: () -> Unit) = FeedMePostComposerScreen(controller, drafts, platformBackHandler, { true }, onBack)

/** The required predicate is presentation-only. The original four-argument API is unchanged. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedMePostComposerScreen(controller: PostComposerController, drafts: PostDraftController,
    platformBackHandler: @Composable (Boolean, () -> Unit) -> Unit,
    actionAllowed: () -> Boolean,
    onBack: () -> Unit) {
    val state by controller.states.collectAsState()
    val draftState by drafts.states.collectAsState()
    val scope = rememberCoroutineScope()
    val latestActionAllowed by rememberUpdatedState(actionAllowed)
    var working by remember(controller) { mutableStateOf(false) }
    var abandon by remember(controller) { mutableStateOf<PreparedPublicationAllocationAbandon?>(null) }
    fun act(action: suspend () -> Unit) {
        if (working || !latestActionAllowed()) return
        working = true
        scope.launch { try { runReviewedPostUiAction(latestActionAllowed, action) } finally { working = false } }
    }
    fun back() {
        if (abandon != null) { abandon = null; return }
        // Explicit dismissal invalidates the review; it does not Publish or cancel an original.
        scope.launch { controller.dismissReview(); onBack() }
    }
    platformBackHandler(true, ::back)
    val enabled = !working && state.phase != PostComposerPhase.UNAVAILABLE && latestActionAllowed()
    val liveToken = state.review?.token ?: state.retry?.token ?: state.unsentCancellation?.token
    val selectionNow = Triple(draftState.screen, draftState.selected?.clientDraftId, draftState.selected?.localRevision)
    val selectionAtReview = remember(liveToken) { selectionNow }
    val preparedLocal = state.review?.snapshot?.reviewedLocal ?: state.retry?.separatelyObservedCurrentLocal ?: state.unsentCancellation?.retainedLocal
    val visibleLocal = preparedLocal?.let { expected -> draftState.localDrafts.singleOrNull { it.clientDraftId == expected.clientDraftId } }
    val navigationCurrent = state.review?.isCurrentForNavigation ?: state.retry?.isCurrentForNavigation ?:
        state.unsentCancellation?.isCurrentForNavigation ?: true
    val selectionCurrent = !(selectionAtReview != selectionNow || preparedLocal == null ||
        visibleLocal?.localRevision != preparedLocal.localRevision || (draftState.selected != null &&
            draftState.selected?.clientDraftId != preparedLocal.clientDraftId))
    val staleReview = publicationReviewIsStale(liveToken != null, navigationCurrent, selectionCurrent)
    val scroll = key(state.review?.token, state.retry?.token, state.unsentCancellation?.token, state.phase) { rememberScrollState() }
    FeedMeTheme {
        Box(Modifier.fillMaxSize().background(FeedMeColors.Paper).safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(scroll).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = ::back, modifier = Modifier.heightIn(min = 48.dp)) { Text("← Back") }
                    FeedMeWordmark(compact = true)
                }
                Text("Review, then share.", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                Text("Private Save and Publish are different actions. Opening or leaving this page never sends a post.", style = MaterialTheme.typography.bodyMedium)
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!latestActionAllowed()) PostReviewSection("Read-only publication history",
                    "This preview cannot change an older or unverified draft format. Retained content remains available; Back does not send, retry or cancel anything.")
                publicationFailureText(state.failure)?.let { message ->
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) { PostReviewSection("Action not confirmed", message) }
                }
                if (state.phase == PostComposerPhase.UNAVAILABLE || draftState.phase == PostDraftPhase.UNAVAILABLE) {
                    PostReviewSection("Review unavailable", "This session cannot show private publication content. Back remains available.")
                } else {
                    state.allocation?.let { allocation ->
                        PostReviewSection("Publication request allocation", when (allocation.phase) {
                            PublicationAllocationPhase.AWAITING_ID -> "The request-ID provider has not returned an original. This is not a queue or server acknowledgement."
                            PublicationAllocationPhase.ABANDONED_WAITING_FOR_PROVIDER -> "The unreturned request was fenced. Its slot stays occupied until the provider settles; no late return can publish it."
                            PublicationAllocationPhase.REGISTRATION_UNOBSERVED -> "The exact original is retained, but registration is unresolved. Do not start a replacement."
                        }) {
                            allocation.abandonToken?.let { token ->
                                OutlinedButton(onClick = { if (latestActionAllowed()) abandon = token }, enabled = enabled,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                    Text("Review unreturned request")
                                }
                            }
                        }
                    }
                    when {
                        staleReview -> PostReviewSection("The draft view changed", "This earlier review is no longer shown as confirmable. Return to your draft or retained original and prepare a new exact review. Nothing is sent by Back.")
                        state.review != null -> state.review?.let { review ->
                            PostReviewTarget(review.snapshot.target)
                            PostContentReview(review.snapshot.exactProposedPostWrite, "This is what you will publish")
                            PostDisclosure(review.snapshot.disclosure)
                            Text("Confirming publishes only these reviewed choices. Back lets you change your mind without sending.", style = MaterialTheme.typography.bodyMedium)
                            PostExactEvidence("All publication request details", review.snapshot.exactProposedPostWrite)
                            Primary("Publish this post", enabled && review.isCurrentForNavigation) {
                                act { if (review.isCurrentForNavigation) controller.confirmPublish(review.token) }
                            }
                        }
                        state.retry != null -> state.retry?.let { retry ->
                            PostReviewSection("Review original publication", when (retry.observedAttempts) {
                                null -> "Registration is unresolved. Reconcile this exact allocated original; do not assume it was unsent or replace it."
                                0 -> "No transport attempt was observed. Confirming may send this original for the first time."
                                else -> "This original may already have reached the server. Confirm only its unchanged request."
                            })
                            OriginalPublicationContent(retry.original)
                            CurrentPublicationLocal(retry.separatelyObservedCurrentLocal, "Current local changes — not this retry")
                            Text("These newer choices will not be substituted into the original. Retained changes are not automatically submitted.", style = MaterialTheme.typography.bodyMedium)
                            Primary("Retry this original publication", enabled && retry.isCurrentForNavigation) {
                                act { if (retry.isCurrentForNavigation) controller.retryOriginal(retry.token) }
                            }
                        }
                        state.unsentCancellation != null -> state.unsentCancellation?.let { review ->
                            PostReviewSection("Cancel this unsent publication?", "Cancel only this unsent original. Your local draft stays. This does not erase a post that was already attempted.")
                            OriginalPublicationContent(review.original)
                            CurrentPublicationLocal(review.retainedLocal, "Local draft that will be retained")
                            Primary("Cancel this unsent publication", enabled && review.isCurrentForNavigation) {
                                act { if (review.isCurrentForNavigation) controller.confirmUnsentCancellation(review.token) }
                            }
                        }
                        else -> {
                            state.pending?.let { pending ->
                                PostReviewSection("Publication is not confirmed", "Your original is kept. Opening this page does not send it again or confirm its result.") {
                                    pending.earliestRetryAtMillis?.let {
                                        Text("Retry timing is checked again when you choose to continue.", style = MaterialTheme.typography.bodySmall)
                                        FeedMeDetails("Retry timing details") { PostReviewValue("Earliest retry time (Unix milliseconds)", it.toString()) }
                                    }
                                }
                                if (pending.phase == "RECEIPT_READY") {
                                    Primary("Confirm received result on device", enabled) { act { controller.applyActualReceipt() } }
                                } else {
                                    Primary("Review original publication", enabled) { act { controller.prepareOriginalRetry() } }
                                }
                                if (publicationUnsentReviewAvailable(pending.phase, pending.observedAttempts))
                                    OutlinedButton(onClick = { act { controller.prepareUnsentCancellation() } }, enabled = enabled,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Review cancellation") }
                            }
                            if (state.phase in setOf(PostComposerPhase.PUBLISHED, PostComposerPhase.CANCELLED_UNSENT)) {
                                PostReviewSection(if (state.acknowledged) "Action confirmed" else "Local confirmation needed",
                                    publicationOutcomeText(state.phase, state.acknowledged))
                                state.exactCanonicalPost?.let { PostContentReview(it, "Post returned by the server"); PostExactEvidence("All returned post details", it) }
                                state.etag?.let { FeedMeDetails("Returned version details") { PostReviewValue("Returned ETag", it) } }
                                if (!state.acknowledged) Primary("Reconcile original confirmation", enabled) { act { controller.finalizeOriginal() } }
                            }
                            if (state.history.isEmpty() && state.pending == null && state.allocation == null)
                                PostReviewSection("No reviewed publication is open", "Return to your draft to choose its exact review. No post is created here automatically.")
                            state.history.forEach { history ->
                                PostReviewSection(when (history.outcome) {
                                    "published" -> "Earlier publication — historical"
                                    "cancelled-unsent" -> "Earlier unsent cancellation — historical"
                                    else -> "Retained original — outcome not confirmed"
                                }, "Kept for reference. This does not confirm a new action or replace a fresh review.") {
                                    FeedMeDetails("Earlier request details") {
                                        PostReviewValue("Original request", history.original.commandId)
                                        PostReviewValue("Original local revision", history.original.originalReviewedLocal.localRevision.toString())
                                    }
                                }
                                PostContentReview(history.original.exactOriginalPostWrite, "Historical original content")
                                PostDisclosure(history.original.displayedDisclosure)
                                PostExactEvidence("Earlier original request details", history.original.exactOriginalPostWrite)
                                history.exactCanonicalPost?.let { PostExactEvidence("Earlier returned post details", it) }
                                history.etag?.let { FeedMeDetails("Earlier version details") { PostReviewValue("Historical ETag", it) } }
                            }
                            OutlinedButton(onClick = { act { controller.restore() } }, enabled = enabled,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Refresh retained publication history") }
                        }
                    }
                }
            }
        }
        val token = abandon
        if (latestActionAllowed() && token != null && state.allocation?.abandonToken === token && state.phase != PostComposerPhase.UNAVAILABLE && draftState.phase != PostDraftPhase.UNAVAILABLE)
            PostAbandonDialog("Abandon this unreturned publication request?", { abandon = null }) {
                act { controller.abandonUnreturnedAllocation(token) }; abandon = null
            }
    }
}
