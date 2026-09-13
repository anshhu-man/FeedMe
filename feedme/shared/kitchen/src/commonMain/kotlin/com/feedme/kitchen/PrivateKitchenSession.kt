package com.feedme.kitchen

import com.feedme.core.ports.*
import com.feedme.sync.*
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Construction-only integration seam for an ALREADY verified identity and owner store. Does not
 * activate/resume a database, create credentials, schedule background work or replace demo UI.
 * Both required cooking hooks are wired here; other feature commands remain unconfigured.
 */
class PrivateKitchenSession(
    scope: StorageScope, store: PrivateStateStore, boundary: SessionBoundary,
    dispatcher: CoroutineDispatcher, clock: EpochClock, transport: AccountTransport,
    originBinding: String,
) {
    private val cookingAdapter: CookingRepository
    val cooking: CookingRepository get() = cookingAdapter
    val savedRecipes = SavedRecipeRepository(scope, store, boundary, dispatcher, clock, transport)
    val commands = DurableCommandQueue(scope, store, boundary, dispatcher, clock,
        CommandExecutionGate { lease, intent ->
            if (intent.call.operationId in COOKING) cookingAdapter.executionDecision(lease, intent)
            else ExecutionDecision.Wait(CommandIssue.NOT_CONFIGURED)
        }, transport,
        CommandReplyObserver { lease, intent, reply ->
            if (intent.call.operationId in COOKING) cookingAdapter.observeReply(lease, intent, reply)
            else PortResult.Value(Unit)
        })

    init {
        cookingAdapter = CookingRepository(scope, store, boundary, dispatcher, clock, transport, originBinding, commands)
    }
    private companion object { val COOKING = setOf("updateCookSession", "completeCookSession") }
}
