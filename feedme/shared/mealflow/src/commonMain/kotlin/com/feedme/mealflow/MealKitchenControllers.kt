package com.feedme.mealflow

import com.feedme.core.ports.*
import com.feedme.mealflow.social.*
import kotlinx.coroutines.CoroutineDispatcher

/** One actual borrowed kitchen/queue for retained feature owners. No store or accepting gate escapes.
 * The readiness MUST be minted by the supplied actual meal controller, on this same access.
 * Close all created feature owners on journey disposal; none closes the borrowed private session.
 * The old factory does not configure social draft commands. Server drafts are not publication. */
class MealKitchenControllers private constructor(val cooking: CookingFlowController, val cookbook: CookbookController,
    val postDrafts: PostDraftController? = null, val reviewedPosts: ReviewedPostEntry? = null) {
    /** Balanced close of only these children; the supplied meal controller and native private
     * session remain caller-owned. Child close methods are idempotent. */
    suspend fun close(): PortResult<Unit> = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        val social = reviewedPosts?.close() ?: postDrafts?.close()
        val book = cookbook.close(); val cook = cooking.close()
        (social as? PortResult.Failure) ?: (book as? PortResult.Failure) ?: (cook as? PortResult.Failure) ?: PortResult.Value(Unit)
    }
    companion object {
        /** Required explicit opt-in. No I/O, migration, principal resolution, disclosure, IDs
         * or policy default at construction. Both social children share one actual owner/queue. */
        fun createWithReviewedPosts(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary, dispatcher: CoroutineDispatcher,
            clock: EpochClock, connectivity: ConnectivityPort, ids: MealOperationIds, meals: MealRequestController,
            readiness: MealDraftReadiness, cookingPolicy: CookingFlowPolicy, cookbookPolicy: CookbookPolicy,
            draftPolicy: PostDraftClientPolicy, publicationPolicy: PostPublicationClientPolicy,
            integration: ReviewedKitchenIntegration): MealKitchenControllers {
            require(readiness === meals.draftReadiness && readiness.matches(meals, access, boundary))
            require(access.lease.scope.actorKind == ActorKind.ACCOUNT && boundary.isCurrent(access.lease))
            val composition = MealKitchenComposition(access, boundary, dispatcher, clock, connectivity)
            val owner = PostDraftJournalOwner(composition, draftPolicy, publicationPolicy)
            val drafts = PostDraftController(composition, ids, draftPolicy, owner, ReviewedSaveAdapter(integration))
            val publications = PostComposerController(composition, owner, draftPolicy, publicationPolicy,
                ReviewedPublicationAdapter(integration), ids)
            drafts.bindReviewedPublicationEntry(publications)
            return MealKitchenControllers(CookingFlowController(composition, ids, cookingPolicy),
                CookbookController(composition, ids, readiness, cookbookPolicy), drafts, ReviewedPostEntry(drafts, publications))
        }

        fun create(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary, dispatcher: CoroutineDispatcher,
            clock: EpochClock, connectivity: ConnectivityPort, ids: MealOperationIds, meals: MealRequestController,
            readiness: MealDraftReadiness, cookingPolicy: CookingFlowPolicy, cookbookPolicy: CookbookPolicy): MealKitchenControllers {
            require(readiness === meals.draftReadiness && readiness.matches(meals, access, boundary))
            val composition = MealKitchenComposition(access, boundary, dispatcher, clock, connectivity)
            return MealKitchenControllers(CookingFlowController(composition, ids, cookingPolicy),
                CookbookController(composition, ids, readiness, cookbookPolicy))
        }

        /** Explicit policy/configuration opt-in; all three controllers borrow ONE kitchen/journal.
         * Construction performs no I/O and does not upgrade guest/offline access or grant posting. */
        fun createWithDrafts(access: AuthenticatedMealPlanningAccess, boundary: SessionBoundary, dispatcher: CoroutineDispatcher,
            clock: EpochClock, connectivity: ConnectivityPort, ids: MealOperationIds, meals: MealRequestController,
            readiness: MealDraftReadiness, cookingPolicy: CookingFlowPolicy, cookbookPolicy: CookbookPolicy,
            draftPolicy: PostDraftClientPolicy): MealKitchenControllers {
            require(readiness === meals.draftReadiness && readiness.matches(meals, access, boundary))
            val composition = MealKitchenComposition(access, boundary, dispatcher, clock, connectivity)
            return MealKitchenControllers(CookingFlowController(composition, ids, cookingPolicy),
                CookbookController(composition, ids, readiness, cookbookPolicy), PostDraftController(composition, ids, draftPolicy))
        }
    }
    override fun toString() = "MealKitchenControllers(<redacted>)"
}
