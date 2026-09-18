package com.feedme.server.guest

import com.feedme.server.catalog.*
import com.feedme.server.db.*
import com.feedme.server.memory.*
import com.feedme.server.planning.PlanningFailureCode
import com.feedme.server.planning.PlanningServiceFailure
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** Explicit, private guest feedback. The actual session owner encloses the target,
 * feedback, original receipt and redacted event in one transaction. No automatic save,
 * completion, allergy, preference projection, social publication or HTTP activation. */
internal class GuestFeedbackStore(
    private val environment: String,
    private val transactions: PgTransactions,
    private val sessions: GuestSessionStore,
    private val catalog: RecipeCatalogJournal,
    private val ingredients: IngredientCatalogStore,
    private val planningPolicy: GuestPlanningPolicy,
    val policy: FeedbackServicePolicy,
) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(sessions.isBoundTo(environment, transactions))
        require(catalog.environment == environment && ingredients.environment == environment)
    }
    fun createFeedback(token: String, key: UUID, body: JsonObject): CommandResult =
        use(token, "createFeedback") { c, actor, store -> store.createFeedback(c, actor, key, body) }
    fun updateFeedback(token: String, key: UUID, id: UUID, ifMatch: String, body: JsonObject): CommandResult =
        use(token, "updateFeedback") { c, actor, store -> store.updateFeedback(c, actor, key, id, ifMatch, body) }
    fun deleteFeedback(token: String, key: UUID, id: UUID, ifMatch: String): CommandResult =
        use(token, "deleteFeedback") { c, actor, store -> store.deleteFeedback(c, actor, key, id, ifMatch) }

    private fun use(token: String, operation: String,
        action: (Connection, VerifiedFeedbackPrincipal, FeedbackStore) -> FeedbackStore.Pending<CommandResult>): CommandResult = safe {
        sessions.withCurrentCompletion(token, operation, action = { c, actual -> safe {
            GuestFeedbackAccess.compatibility(c, 1, "durable_platform")
            GuestFeedbackAccess.compatibility(c, 22, "private_feedback")
            val access = GuestFeedbackAccess(environment, c, actual, catalog, ingredients, planningPolicy)
            val kernel = FeedbackStore(environment, transactions, access, policy)
            Attempt(access, action(c, access.principal, kernel)).also { access.revalidate() }
        } }, complete = { c, _, attempt -> safe {
            attempt.access.revalidate()
            attempt.pending.revalidate(c, attempt.access.principal)
            attempt.access.revalidate()
        } }, completeAt = { c, _, attempt, at -> safe {
            // Rejection only: no queryful JDBC getter, source read or new authority here.
            attempt.pending.checkAt(c, attempt.access.principal, at)
        } }).pending.result
    }
    private class Attempt(val access: GuestFeedbackAccess, val pending: FeedbackStore.Pending<CommandResult>)
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: GuestSessionFailure) { throw failure }
        catch (failure: FeedbackFailure) { throw failure }
        catch (failure: RecipeCatalogFailure) { throw FeedbackFailure(if (failure.code == RecipeCatalogFailureCode.NOT_CONFIGURED)
            FeedbackFailureCode.NOT_CONFIGURED else FeedbackFailureCode.STORAGE_UNAVAILABLE) }
        catch (failure: IngredientCatalogFailure) { throw FeedbackFailure(if (failure.code == IngredientCatalogFailureCode.NOT_CONFIGURED)
            FeedbackFailureCode.NOT_CONFIGURED else FeedbackFailureCode.STORAGE_UNAVAILABLE) }
        catch (failure: PlanningServiceFailure) { throw FeedbackFailure(when (failure.code) {
            PlanningFailureCode.UNAUTHENTICATED -> FeedbackFailureCode.UNAUTHENTICATED
            PlanningFailureCode.PLAN_UNAVAILABLE, PlanningFailureCode.MODE_CONFIRMATION_REQUIRED,
            PlanningFailureCode.RECIPE_UNAVAILABLE, PlanningFailureCode.RECIPE_RECALLED -> FeedbackFailureCode.TARGET_UNAVAILABLE
            PlanningFailureCode.NOT_CONFIGURED -> FeedbackFailureCode.NOT_CONFIGURED
            else -> FeedbackFailureCode.STORAGE_UNAVAILABLE
        }) }
        catch (failure: CommitOutcomeUnknown) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (failure: SQLException) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Guest feedback interrupted")
            if (failure.sqlState in setOf("40001", "40P01")) throw failure
            throw FeedbackFailure(FeedbackFailureCode.STORAGE_UNAVAILABLE)
        }
        catch (_: Exception) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Guest feedback interrupted")
            throw FeedbackFailure(FeedbackFailureCode.STORAGE_UNAVAILABLE)
        }
    override fun toString() = "GuestFeedbackStore(<redacted>)"
}
