package com.feedme.kitchen

import com.feedme.core.ports.*
import com.feedme.sync.*
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Construction-only integration seam for an ALREADY verified identity and owner store. Does not
 * activate/resume a database, create credentials, schedule background work or replace demo UI.
 * Both required cooking hooks are wired here; basic cookbook, account-private post drafts and
 * reviewed publication require separate purpose-fixed hooks. Other commands stay unconfigured.
 * Starting a new cook requires the optional create-only gate from its trusted domain controller;
 * omitting it never admits a start. Every queue batch uses exact store acknowledgement/readback.
 */
class PrivateKitchenSession(
    scope: StorageScope, store: PrivateStateStore, boundary: SessionBoundary,
    dispatcher: CoroutineDispatcher, clock: EpochClock, transport: AccountTransport,
    originBinding: String,
    createExecutionGate: CommandExecutionGate? = null,
    savedRecipeCommandHooks: SavedRecipeCommandHooks? = null,
    postDraftCommandHooks: PostDraftCommandHooks? = null,
    postPublicationCommandHooks: PostPublicationCommandHooks? = null,
) {
    private val ownerScope = scope
    private val ownerStore = store
    private val ownerBoundary = boundary
    private val ownerOrigin = normalizedId(originBinding)
    /** Identity-only composition check, not a transferable authentication/ownership proof. */
    fun matchesComposition(scope: StorageScope, store: PrivateStateStore, boundary: SessionBoundary, originBinding: String): Boolean =
        scope == ownerScope && store === ownerStore && boundary === ownerBoundary && originBinding == ownerOrigin

    private val cookingAdapter: CookingRepository
    private val commandStore = KitchenContext(scope, store, boundary, dispatcher, clock, transport).commandStore()
    val cooking: CookingRepository get() = cookingAdapter
    val savedRecipes = SavedRecipeRepository(scope, store, boundary, dispatcher, clock, transport)
    val commands = DurableCommandQueue(scope, commandStore, boundary, dispatcher, clock,
        CommandExecutionGate { lease, intent ->
            if (intent.call.operationId in COOKING) cookingAdapter.executionDecision(lease, intent)
            else if (intent.call.operationId == "createCookSession" && createExecutionGate != null)
                createExecutionGate.evaluate(lease, intent)
            else if (intent.call.operationId in SAVED && savedRecipeCommandHooks != null)
                savedRecipeCommandHooks.executionDecision(lease, intent)
            else if (intent.call.operationId in POST_DRAFTS) {
                if (lease.scope.actorKind != ActorKind.ACCOUNT || lease.scope != ownerScope ||
                    !ownerBoundary.isCurrent(lease) || intent.originBinding != ownerOrigin)
                    ExecutionDecision.Wait(CommandIssue.AUTH_REQUIRED)
                else postDraftCommandHooks?.executionDecision(lease, intent)
                    ?: ExecutionDecision.Wait(CommandIssue.NOT_CONFIGURED)
            }
            else if (intent.call.operationId == "publishPost") {
                if (lease.scope.actorKind != ActorKind.ACCOUNT || lease.scope != ownerScope ||
                    !ownerBoundary.isCurrent(lease) || intent.originBinding != ownerOrigin)
                    ExecutionDecision.Wait(CommandIssue.AUTH_REQUIRED)
                else postPublicationCommandHooks?.executionDecision(lease, intent)
                    ?: ExecutionDecision.Wait(CommandIssue.NOT_CONFIGURED)
            }
            else ExecutionDecision.Wait(CommandIssue.NOT_CONFIGURED)
        }, transport,
        CommandReplyObserver { lease, intent, reply ->
            if (intent.call.operationId in COOKING) cookingAdapter.observeReply(lease, intent, reply)
            else if (intent.call.operationId in SAVED)
                savedRecipeCommandHooks?.observeReply(lease, intent, reply)
                    ?: PortResult.Failure(FailureReason.NOT_CONFIGURED)
            else if (intent.call.operationId in POST_DRAFTS) {
                if (lease.scope.actorKind != ActorKind.ACCOUNT || lease.scope != ownerScope ||
                    !ownerBoundary.isCurrent(lease) || intent.originBinding != ownerOrigin)
                    PortResult.Failure(FailureReason.STALE_SESSION)
                else postDraftCommandHooks?.observeReply(lease, intent, reply)
                    ?: PortResult.Failure(FailureReason.NOT_CONFIGURED)
            }
            else if (intent.call.operationId == "publishPost") {
                if (lease.scope.actorKind != ActorKind.ACCOUNT || lease.scope != ownerScope ||
                    !ownerBoundary.isCurrent(lease) || intent.originBinding != ownerOrigin)
                    PortResult.Failure(FailureReason.STALE_SESSION)
                else postPublicationCommandHooks?.observeReply(lease, intent, reply)
                    ?: PortResult.Failure(FailureReason.NOT_CONFIGURED)
            }
            else PortResult.Value(Unit)
        })

    init {
        cookingAdapter = CookingRepository(scope, store, boundary, dispatcher, clock, transport, originBinding, commands)
    }
    private companion object {
        val COOKING = setOf("updateCookSession", "completeCookSession")
        val SAVED = setOf("saveRecipe", "deleteSavedRecipe")
        val POST_DRAFTS = setOf("createPostDraft", "updatePostDraft", "deletePostDraft")
    }
}
