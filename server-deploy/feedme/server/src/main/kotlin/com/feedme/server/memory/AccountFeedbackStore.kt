package com.feedme.server.memory

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.catalog.*
import com.feedme.server.cooking.AccountMealAccess
import com.feedme.server.db.*
import com.feedme.server.identity.*
import com.feedme.server.planning.*
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** Optional real account owner of the existing feedback kernel; no HTTP or guest activation. */
internal class AccountFeedbackStore(private val environment: String, private val transactions: PgTransactions,
    private val accounts: AccountProfileStore, private val catalog: RecipeCatalogJournal,
    private val ingredients: IngredientCatalogStore, private val planning: AccountPlanningStore,
    val policy: FeedbackServicePolicy) {
    init { require(catalog.environment == environment && ingredients.environment == environment); require(planning.isBoundTo(environment, transactions, accounts)) }
    fun createFeedback(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, body: JsonObject): CommandResult =
        use(subject, device) { c, a, s -> s.createFeedback(c, a, key, body) }
    fun updateFeedback(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, id: UUID, ifMatch: String, body: JsonObject): CommandResult =
        use(subject, device) { c, a, s -> s.updateFeedback(c, a, key, id, ifMatch, body) }
    fun deleteFeedback(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, id: UUID, ifMatch: String): CommandResult =
        use(subject, device) { c, a, s -> s.deleteFeedback(c, a, key, id, ifMatch) }
    private fun use(subject: VerifiedSupabaseSubject, device: UUID,
        action: (Connection, VerifiedFeedbackPrincipal, FeedbackStore) -> FeedbackStore.Pending<CommandResult>): CommandResult = safe {
        transactions.run { c ->
            val account = AccountMealAccess(environment, c, accounts, subject, device)
            planning.withOwnedTransaction(c, subject, device) { planningActor, plans, sourceAt ->
                val access = AccountFeedbackAccess(environment, c, account, device, catalog, ingredients, plans, planningActor)
                val pending = action(c, access.principal, FeedbackStore(environment, transactions, access, policy))
                account.current(c); pending.revalidate(c, access.principal); access.revalidate(); account.current(c)
                val at = AccountMealAccess.now(c)
                sourceAt(at); pending.checkAt(c, access.principal, at); account.checkAt(c, at)
                pending.result
            }
        }
    }
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (f: FeedbackFailure) { throw f }
        catch (f: AccountFailure) { fail(when (f.code) {
            AccountFailureCode.UNAUTHENTICATED, AccountFailureCode.ACCOUNT_UNAVAILABLE -> FeedbackFailureCode.UNAUTHENTICATED
            AccountFailureCode.POLICY_BLOCKED -> FeedbackFailureCode.FORBIDDEN
            AccountFailureCode.NOT_CONFIGURED -> FeedbackFailureCode.NOT_CONFIGURED
            else -> FeedbackFailureCode.STORAGE_UNAVAILABLE
        }) }
        catch (f: PlanningServiceFailure) { fail(when (f.code) {
            PlanningFailureCode.UNAUTHENTICATED -> FeedbackFailureCode.UNAUTHENTICATED
            PlanningFailureCode.POLICY_BLOCKED -> FeedbackFailureCode.FORBIDDEN
            PlanningFailureCode.NOT_CONFIGURED -> FeedbackFailureCode.NOT_CONFIGURED
            PlanningFailureCode.RECIPE_RECALLED -> FeedbackFailureCode.RECIPE_RECALLED
            PlanningFailureCode.PLAN_UNAVAILABLE, PlanningFailureCode.PLAN_EXPIRED, PlanningFailureCode.MODE_CONFIRMATION_REQUIRED,
            PlanningFailureCode.RECIPE_UNAVAILABLE -> FeedbackFailureCode.TARGET_UNAVAILABLE
            else -> FeedbackFailureCode.STORAGE_UNAVAILABLE
        }) }
        catch (f: RecipeCatalogFailure) { fail(if (f.code == RecipeCatalogFailureCode.NOT_CONFIGURED) FeedbackFailureCode.NOT_CONFIGURED else FeedbackFailureCode.STORAGE_UNAVAILABLE) }
        catch (f: IngredientCatalogFailure) { fail(if (f.code == IngredientCatalogFailureCode.NOT_CONFIGURED) FeedbackFailureCode.NOT_CONFIGURED else FeedbackFailureCode.STORAGE_UNAVAILABLE) }
        catch (f: CommitOutcomeUnknown) { throw f }
        catch (f: CancellationException) { throw f }
        catch (f: InterruptedException) { Thread.currentThread().interrupt(); throw f }
        catch (f: SQLException) { if (Thread.currentThread().isInterrupted) throw InterruptedException("Account feedback interrupted")
            if (f.sqlState in setOf("40001", "40P01")) throw f; fail(FeedbackFailureCode.STORAGE_UNAVAILABLE) }
        catch (_: Exception) { if (Thread.currentThread().isInterrupted) throw InterruptedException("Account feedback interrupted"); fail(FeedbackFailureCode.STORAGE_UNAVAILABLE) }
    private fun fail(code: FeedbackFailureCode): Nothing = throw FeedbackFailure(code)
    override fun toString() = "AccountFeedbackStore(<redacted>)"
}
