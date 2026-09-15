package com.feedme.mealflow

import com.feedme.core.ports.*
import com.feedme.kitchen.PrivateKitchenSession
import com.feedme.kitchen.PostDraftCommandHooks
import com.feedme.kitchen.PostPublicationCommandHooks
import com.feedme.kitchen.SavedRecipeCommandHooks
import com.feedme.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal enum class MealKitchenFeature { COOKING, COOKBOOK, POST_DRAFTS, POST_PUBLICATIONS }

/** Opaque identity only: constructing another instance cannot grant admission. No private
 * state, store, transport, borrower or coroutine context is exposed through this token. */
internal class ComposerOperationPermit internal constructor() {
    override fun toString() = "ComposerOperationPermit(<redacted>)"
}

/** Trusted domain callbacks, not UI permission flags. Called inside the admitted operation;
 * callbacks must not re-enter operate or a domain mutex already held by the caller. */
internal interface MealKitchenHooks {
    suspend fun checkCurrent()
    fun beforeCommit(mutations: List<StoreMutation>) = Unit
    suspend fun beforeTransport(call: ApiCall) = Unit
    suspend fun afterTransport(call: ApiCall, reply: ApiReply) = Unit
    val createExecutionGate: CommandExecutionGate? get() = null
    val savedRecipeCommands: SavedRecipeCommandHooks? get() = null
    val postDraftCommands: PostDraftCommandHooks? get() = null
    val postPublicationCommands: PostPublicationCommandHooks? get() = null
}

/**
 * One borrowed kitchen/queue for retained meal feature controllers. Construction performs no I/O.
 * The wrapper is operation-scoped, not a replacement identity for the actual private store. In
 * particular it cannot satisfy SessionCookingTimers' actual-store identity check. Nothing here
 * closes/erases the session, admits timer effects or reconstructs a receipt acknowledgement.
 *
 * All use is serialized on the application's identity dispatcher. Each operation carries an
 * inherited coroutine token as well as its borrower's current-generation check: a leaked wrapper,
 * late child or another controller cannot accidentally use whichever feature is currently active.
 */
internal class MealKitchenComposition(
    val access: AuthenticatedMealPlanningAccess,
    val boundary: SessionBoundary,
    val dispatcher: CoroutineDispatcher,
    val clock: EpochClock,
    private val connectivity: ConnectivityPort,
) {
    internal class Borrower internal constructor(
        internal val owner: MealKitchenComposition,
        internal val feature: MealKitchenFeature,
        internal var hooks: MealKitchenHooks?,
    ) {
        override fun toString() = "MealKitchenBorrower(<redacted>)"
    }
    private class Operation(val borrower: Borrower, val composerPermit: ComposerPermitBinding?) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Operation>
    }
    private class ComposerPermitBinding(
        val token: ComposerOperationPermit,
        val owner: MealKitchenComposition,
        val lease: SessionLease,
        val store: PrivateStateStore,
        val boundary: SessionBoundary,
        val origin: String,
    )
    private val mutex = Mutex()
    private val borrowers = mutableListOf<Borrower>()
    private var active: Operation? = null
    private var unavailable = !boundary.isCurrent(access.lease)
    private val subscription = boundary.onInvalidated(access.lease) {
        unavailable = true
        borrowers.forEach { it.hooks = null }
        borrowers.clear()
    }

    val store: PrivateStateStore = object : PrivateStateStore {
        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> {
            val operation = current(); requireScope(scope)
            val result = access.store.read(scope, key)
            current(operation); return result
        }
        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> {
            val operation = current(); requireScope(scope)
            hooks(operation.borrower).beforeCommit(mutations)
            current(operation)
            val result = access.store.commit(scope, mutations)
            current(operation); return result
        }
        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> = PortResult.Failure(FailureReason.NOT_CONFIGURED)
    }
    val transport: AccountTransport = object : AccountTransport {
        override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
            val operation = current()
            if (lease !== access.lease) mealFail(FailureReason.STALE_SESSION)
            if (call.operationId !in operations(operation.borrower.feature)) return PortResult.Failure(FailureReason.NOT_CONFIGURED)
            if (operation.borrower.feature in setOf(MealKitchenFeature.POST_DRAFTS, MealKitchenFeature.POST_PUBLICATIONS) &&
                lease.scope.actorKind != ActorKind.ACCOUNT)
                return PortResult.Failure(FailureReason.UNAUTHENTICATED)
            if (!online()) return PortResult.Failure(FailureReason.OFFLINE)
            hooks(operation.borrower).beforeTransport(call)
            current(operation)
            val reply = access.transport.execute(lease, call)
            current(operation)
            if (reply is PortResult.Value) hooks(operation.borrower).afterTransport(call, reply.value)
            current(operation); return reply
        }
    }
    val kitchen = PrivateKitchenSession(access.lease.scope, store, boundary, dispatcher, clock, transport, access.origin,
        CommandExecutionGate { lease, intent ->
            val operation = current()
            if (lease !== access.lease || intent.originBinding != access.origin) ExecutionDecision.Wait(CommandIssue.AUTH_REQUIRED)
            else if (operation.borrower.feature != MealKitchenFeature.COOKING) ExecutionDecision.Wait(CommandIssue.NOT_CONFIGURED)
            else {
                val gate = hooks(operation.borrower).createExecutionGate
                val result = gate?.evaluate(lease, intent) ?: ExecutionDecision.Wait(CommandIssue.NOT_CONFIGURED)
                current(operation); result
            }
        }, object : SavedRecipeCommandHooks {
            override suspend fun executionDecision(lease: SessionLease, intent: CommandIntent): ExecutionDecision {
                val operation = current()
                if (lease !== access.lease || intent.originBinding != access.origin) return ExecutionDecision.Wait(CommandIssue.AUTH_REQUIRED)
                if (operation.borrower.feature != MealKitchenFeature.COOKBOOK) return ExecutionDecision.Wait(CommandIssue.NOT_CONFIGURED)
                val result = hooks(operation.borrower).savedRecipeCommands?.executionDecision(lease, intent)
                    ?: ExecutionDecision.Wait(CommandIssue.NOT_CONFIGURED)
                current(operation); return result
            }
            override suspend fun observeReply(lease: SessionLease, intent: CommandIntent, reply: ApiReply): PortResult<Unit> {
                val operation = current()
                if (lease !== access.lease || intent.originBinding != access.origin) return PortResult.Failure(FailureReason.STALE_SESSION)
                if (operation.borrower.feature != MealKitchenFeature.COOKBOOK) return PortResult.Failure(FailureReason.NOT_CONFIGURED)
                val result = hooks(operation.borrower).savedRecipeCommands?.observeReply(lease, intent, reply)
                    ?: PortResult.Failure(FailureReason.NOT_CONFIGURED)
                current(operation); return result
            }
        }, object : PostDraftCommandHooks {
            override suspend fun executionDecision(lease: SessionLease, intent: CommandIntent): ExecutionDecision {
                val operation = current()
                if (lease !== access.lease || lease.scope.actorKind != ActorKind.ACCOUNT || intent.originBinding != access.origin)
                    return ExecutionDecision.Wait(CommandIssue.AUTH_REQUIRED)
                if (operation.borrower.feature != MealKitchenFeature.POST_DRAFTS) return ExecutionDecision.Wait(CommandIssue.NOT_CONFIGURED)
                val result = hooks(operation.borrower).postDraftCommands?.executionDecision(lease, intent)
                    ?: ExecutionDecision.Wait(CommandIssue.NOT_CONFIGURED)
                current(operation); return result
            }
            override suspend fun observeReply(lease: SessionLease, intent: CommandIntent, reply: ApiReply): PortResult<Unit> {
                val operation = current()
                if (lease !== access.lease || lease.scope.actorKind != ActorKind.ACCOUNT || intent.originBinding != access.origin)
                    return PortResult.Failure(FailureReason.STALE_SESSION)
                if (operation.borrower.feature != MealKitchenFeature.POST_DRAFTS) return PortResult.Failure(FailureReason.NOT_CONFIGURED)
                val result = hooks(operation.borrower).postDraftCommands?.observeReply(lease, intent, reply)
                    ?: PortResult.Failure(FailureReason.NOT_CONFIGURED)
                current(operation); return result
            }
        }, object : PostPublicationCommandHooks {
            override suspend fun executionDecision(lease: SessionLease, intent: CommandIntent): ExecutionDecision {
                val operation = current()
                if (lease !== access.lease || lease.scope.actorKind != ActorKind.ACCOUNT || intent.originBinding != access.origin)
                    return ExecutionDecision.Wait(CommandIssue.AUTH_REQUIRED)
                if (operation.borrower.feature != MealKitchenFeature.POST_PUBLICATIONS || intent.call.operationId != "publishPost")
                    return ExecutionDecision.Wait(CommandIssue.NOT_CONFIGURED)
                val result = hooks(operation.borrower).postPublicationCommands?.executionDecision(lease, intent)
                    ?: ExecutionDecision.Wait(CommandIssue.NOT_CONFIGURED)
                current(operation); return result
            }
            override suspend fun observeReply(lease: SessionLease, intent: CommandIntent, reply: ApiReply): PortResult<Unit> {
                val operation = current()
                if (lease !== access.lease || lease.scope.actorKind != ActorKind.ACCOUNT || intent.originBinding != access.origin)
                    return PortResult.Failure(FailureReason.STALE_SESSION)
                if (operation.borrower.feature != MealKitchenFeature.POST_PUBLICATIONS || intent.call.operationId != "publishPost")
                    return PortResult.Failure(FailureReason.NOT_CONFIGURED)
                val result = hooks(operation.borrower).postPublicationCommands?.observeReply(lease, intent, reply)
                    ?: PortResult.Failure(FailureReason.NOT_CONFIGURED)
                current(operation); return result
            }
        })

    init { require(access.lease.scope.actorKind != ActorKind.DEMO) }

    fun bind(feature: MealKitchenFeature, hooks: MealKitchenHooks): Borrower {
        // Match retained controller construction: stale access produces an unavailable controller,
        // not constructor I/O or a substitute identity. Its first operation is rejected.
        if (unavailable || !boundary.isCurrent(access.lease)) return Borrower(this, feature, null)
        if (borrowers.any { it.feature == feature }) mealFail(FailureReason.CONFLICT)
        require(when (feature) {
            MealKitchenFeature.COOKING -> hooks.savedRecipeCommands == null && hooks.postDraftCommands == null && hooks.postPublicationCommands == null
            MealKitchenFeature.COOKBOOK -> hooks.createExecutionGate == null && hooks.postDraftCommands == null && hooks.postPublicationCommands == null
            MealKitchenFeature.POST_DRAFTS -> hooks.createExecutionGate == null && hooks.savedRecipeCommands == null && hooks.postPublicationCommands == null
            MealKitchenFeature.POST_PUBLICATIONS -> hooks.createExecutionGate == null && hooks.savedRecipeCommands == null && hooks.postDraftCommands == null
        })
        return Borrower(this, feature, hooks).also { borrowers += it }
    }

    /** Detach one feature only. No session close, pending-command deletion or sibling invalidation. */
    fun release(borrower: Borrower) {
        if (borrower.owner !== this) mealFail(FailureReason.STALE_SESSION)
        borrower.hooks = null
        borrowers.remove(borrower)
        if (borrowers.isEmpty()) { unavailable = true; subscription.close() }
    }

    suspend fun <T> operate(borrower: Borrower, action: suspend () -> T): T {
        val result = withContext(dispatcher) {
            check(borrower)
            mutex.withLock {
                check(borrower)
                // Construct one immutable binding on the serialized identity dispatcher.
                // This prevents token replacement; it does not make the composition thread-safe.
                val operation = Operation(borrower,
                    if (borrower.feature in setOf(MealKitchenFeature.POST_DRAFTS, MealKitchenFeature.POST_PUBLICATIONS))
                        ComposerPermitBinding(ComposerOperationPermit(), this@MealKitchenComposition,
                            access.lease, access.store, boundary, access.origin)
                    else null)
                kotlin.check(active == null)
                active = operation
                try {
                    withContext(operation) {
                        current(operation)
                        val value = action()
                        current(operation); value
                    }
                } finally { if (active === operation) active = null }
            }
        }
        // The caller-dispatcher return is cancellable too; never publish a late private value.
        check(borrower)
        return result
    }

    fun online() = access.onlineAllowed && connectivity.current() == Connectivity.ONLINE

    /** Assembly membership only, never operation admission, controller readiness or consent. */
    fun requireComposerParticipant(borrower: Borrower) {
        hooks(borrower)
        if (borrower.feature !in setOf(MealKitchenFeature.POST_DRAFTS, MealKitchenFeature.POST_PUBLICATIONS))
            mealFail(FailureReason.NOT_CONFIGURED)
    }

    /** Return only this borrower's already-admitted social operation's immutable token.
     * All use must remain on the serialized identity dispatcher, including inherited children
     * and nested withContext/queue hooks. No parallel-thread composition support is implied.
     * Repeated calls return the same token; a later operation always receives a new one.
     * A token alone never installs its context, creates admission or revives an expired use. */
    suspend fun composerPermit(borrower: Borrower): ComposerOperationPermit {
        val operation = current()
        requireComposerBorrower(operation, borrower)
        val token = operation.composerPermit?.token ?: mealFail(FailureReason.STALE_SESSION)
        requireComposerBinding(operation, token, borrower)
        return token
    }

    /** The journal participant supplies its privately retained actual borrower, not a caller
     * feature flag. Coordinators also enforce their own participant's method-specific purpose.
     * Call before AND after each suspension in the consuming coordinator/journal method.
     * This check performs no I/O, acquires no mutex and grants no raw-store/transport access. */
    suspend fun requireComposerPermit(permit: ComposerOperationPermit, expectedBorrower: Borrower) {
        val operation = current()
        requireComposerBinding(operation, permit, expectedBorrower)
    }

    private fun requireComposerBorrower(operation: Operation, expectedBorrower: Borrower) {
        if (expectedBorrower.owner !== this || operation.borrower !== expectedBorrower)
            mealFail(FailureReason.STALE_SESSION)
        if (expectedBorrower.feature !in setOf(MealKitchenFeature.POST_DRAFTS, MealKitchenFeature.POST_PUBLICATIONS))
            mealFail(FailureReason.NOT_CONFIGURED)
        if (access.lease.scope.actorKind != ActorKind.ACCOUNT) mealFail(FailureReason.UNAUTHENTICATED)
    }

    private fun requireComposerBinding(operation: Operation, permit: ComposerOperationPermit, expectedBorrower: Borrower) {
        requireComposerBorrower(operation, expectedBorrower)
        val binding = operation.composerPermit ?: mealFail(FailureReason.STALE_SESSION)
        if (binding.token !== permit || binding.owner !== this || binding.lease !== access.lease ||
            binding.store !== access.store || binding.boundary !== boundary || binding.origin != access.origin)
            mealFail(FailureReason.STALE_SESSION)
    }

    private fun requireScope(scope: StorageScope) { if (scope != access.lease.scope) mealFail(FailureReason.STALE_SESSION) }
    private fun hooks(borrower: Borrower): MealKitchenHooks {
        if (unavailable || borrower.owner !== this || borrower !in borrowers || !boundary.isCurrent(access.lease)) mealFail(FailureReason.STALE_SESSION)
        return borrower.hooks ?: mealFail(FailureReason.STALE_SESSION)
    }
    private suspend fun check(borrower: Borrower) {
        currentCoroutineContext().ensureActive()
        val domain = hooks(borrower)
        // Draft/publication local state is account-private too, not only the HTTP operations.
        // Preserve guest cooking/cookbook access without upgrading it through social borrowers.
        if (borrower.feature in setOf(MealKitchenFeature.POST_DRAFTS, MealKitchenFeature.POST_PUBLICATIONS) &&
            access.lease.scope.actorKind != ActorKind.ACCOUNT)
            mealFail(FailureReason.UNAUTHENTICATED)
        domain.checkCurrent()
        currentCoroutineContext().ensureActive()
        hooks(borrower)
    }
    private suspend fun current(expected: Operation? = null): Operation {
        val operation = currentCoroutineContext()[Operation] ?: mealFail(FailureReason.STALE_SESSION)
        if (active !== operation || (expected != null && operation !== expected)) mealFail(FailureReason.STALE_SESSION)
        check(operation.borrower)
        // A detached inherited child may suspend in check while its parent operation leaves.
        // Recheck the operation itself, not only its still-live borrower, after that suspension.
        if (currentCoroutineContext()[Operation] !== operation || active !== operation ||
            (expected != null && operation !== expected)) mealFail(FailureReason.STALE_SESSION)
        return operation
    }
    private fun operations(feature: MealKitchenFeature) = when (feature) {
        MealKitchenFeature.COOKING -> setOf("getPreferences", "getPlan", "getCookSession", "createCookSession", "updateCookSession", "completeCookSession")
        MealKitchenFeature.COOKBOOK -> setOf("getPreferences", "getPlan", "getSavedRecipe", "listSavedRecipes", "listCollections", "getCollection", "saveRecipe", "deleteSavedRecipe")
        MealKitchenFeature.POST_DRAFTS -> setOf("createPostDraft", "getPostDraft", "listPostDrafts", "updatePostDraft", "deletePostDraft")
        MealKitchenFeature.POST_PUBLICATIONS -> setOf("publishPost")
    }
    override fun toString() = "MealKitchenComposition(<redacted>)"
}
