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
import kotlinx.serialization.json.*

/** The explicit account Save + Make Again command, in one actual owned transaction.
 * The parent key/body remain the canonical saveRecipe request. A random child key
 * records only the requested private makeAgain signal. Exact replay checks both
 * effects and never re-creates a removed save or an edited/retracted opinion.
 * This does not complete cooking, publish/share, grant copy rights, or infer a
 * preference from an ordinary Save. Recipe-version-only composition is not enabled. */
internal class AccountMakeAgainStore(
    private val environment: String,
    private val transactions: PgTransactions,
    private val accounts: AccountProfileStore,
    private val rights: RecipeCopyRightsStore,
    private val cursors: SavedRecipeCursors,
    private val savingPolicy: SavedRecipeServicePolicy,
    private val planning: AccountPlanningStore,
    private val catalog: RecipeCatalogJournal,
    private val ingredients: IngredientCatalogStore,
    private val feedbackPolicy: FeedbackServicePolicy,
    private val newCopiesEnabled: Boolean,
) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(rights.environment == environment && catalog.environment == environment && ingredients.environment == environment)
        require(planning.isBoundTo(environment, transactions, accounts))
    }

    fun isSavingBoundTo(env: String, tx: PgTransactions, owner: AccountProfileStore,
        copyRights: RecipeCopyRightsStore, keys: SavedRecipeCursors, policy: SavedRecipeServicePolicy,
        plans: AccountPlanningStore?, newCopies: Boolean): Boolean = environment == env && transactions === tx &&
        accounts === owner && rights === copyRights && cursors === keys && savingPolicy === policy &&
        planning === plans && newCopiesEnabled == newCopies

    fun saveRecipe(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, body: JsonObject): CommandResult = safe {
        if (body["markMakeAgain"] != JsonPrimitive(true)) fail(SavedRecipeFailureCode.INPUT_INVALID)
        // The completion screen supplies its real plan. Do not substitute catalog
        // identity for this provenance or silently invent a completed cook session.
        if (!body.containsKey("planId")) fail(SavedRecipeFailureCode.NOT_CONFIGURED)
        transactions.run { c ->
            val account = AccountMealAccess(environment, c, accounts, subject, device)
            AccountMakeAgainServingCompatibility.check(c)
            planning.withOwnedTransaction(c, subject, device) { planningActor, plans, sourceAt ->
                val savingAccess = AccountSavedRecipeAccess(environment, c, account, device, rights,
                    newCopiesEnabled, plans, planningActor)
                val feedbackAccess = AccountFeedbackAccess(environment, c, account, device, catalog,
                    ingredients, plans, planningActor)
                val saving = SavedRecipeStore(environment, transactions, savingAccess, cursors, savingPolicy)
                val feedback = FeedbackStore(environment, transactions, feedbackAccess, feedbackPolicy)
                val ledger = AccountMakeAgainActionStore(environment, c, feedbackAccess)
                val signals = mutableListOf<FeedbackStore.Pending<CommandResult>>()
                val effect = object : SavedRecipeMakeAgainEffect {
                    override fun applied(connection: Connection, actor: VerifiedSavedRecipePrincipal,
                        key: UUID, input: JsonObject, saved: JsonObject, generation: Long, created: Boolean) {
                        savingAccess.lockPrincipal(connection, actor)
                        if (signals.isNotEmpty()) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
                        val childKey = UUID.randomUUID()
                        val childInput = signal(input)
                        val pending = feedback.createFeedback(connection, feedbackAccess.principal, childKey, childInput)
                        val applied = pending.result as? CommandResult.Applied ?: fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
                        signals += pending
                        ledger.record(parent(feedbackAccess, key, input), input, saved, generation, created,
                            childKey, requireNotNull(applied.reply.body).jsonObject, childInput)
                    }
                    override fun replayed(connection: Connection, actor: VerifiedSavedRecipePrincipal,
                        key: UUID, input: JsonObject, saved: JsonObject, generation: Long) {
                        savingAccess.lockPrincipal(connection, actor)
                        ledger.replay(parent(feedbackAccess, key, input), input, saved, generation, signal(input))
                    }
                }
                val pending = saving.saveRecipe(c, savingAccess.principal, key, body, effect)
                val accepted = pending.result is CommandResult.Applied || pending.result is CommandResult.Replayed
                if (accepted) ledger.seal()
                account.current(c)
                pending.revalidate(c, savingAccess.principal)
                signals.forEach { it.revalidate(c, feedbackAccess.principal) }
                savingAccess.revalidate(); feedbackAccess.revalidate()
                if (accepted) ledger.revalidate()
                account.current(c)
                val at = AccountMealAccess.now(c)
                sourceAt(at); savingAccess.checkAt(at)
                pending.checkAt(c, savingAccess.principal, at)
                signals.forEach { it.checkAt(c, feedbackAccess.principal, at) }
                if (accepted) ledger.checkAt(c, at)
                account.checkAt(c, at)
                pending.result
            }
        }
    }

    private fun parent(access: AccountFeedbackAccess, key: UUID, input: JsonObject) = CommandIdentity(
        PrincipalScope(environment, CommandActor.ACCOUNT, access.principal.principalId), "saveRecipe", key, body = input)
    private fun signal(input: JsonObject) = buildJsonObject {
        put("target", buildJsonObject { put("kind", "plan"); put("resourceId", input.getValue("planId")) })
        put("makeAgain", true)
    }
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (f: SavedRecipeFailure) { throw f }
        catch (f: FeedbackFailure) { fail(when (f.code) {
            FeedbackFailureCode.INPUT_INVALID -> SavedRecipeFailureCode.INPUT_INVALID
            FeedbackFailureCode.UNAUTHENTICATED -> SavedRecipeFailureCode.UNAUTHENTICATED
            FeedbackFailureCode.FORBIDDEN -> SavedRecipeFailureCode.FORBIDDEN
            FeedbackFailureCode.NOT_CONFIGURED -> SavedRecipeFailureCode.NOT_CONFIGURED
            FeedbackFailureCode.VERSION_CONFLICT, FeedbackFailureCode.FEEDBACK_CONFLICT,
            FeedbackFailureCode.FEEDBACK_UNAVAILABLE -> SavedRecipeFailureCode.VERSION_CONFLICT
            FeedbackFailureCode.TARGET_UNAVAILABLE -> SavedRecipeFailureCode.PLAN_UNAVAILABLE
            FeedbackFailureCode.RECIPE_RECALLED -> SavedRecipeFailureCode.RECIPE_RECALLED
            else -> SavedRecipeFailureCode.STORAGE_UNAVAILABLE
        }) }
        catch (f: AccountFailure) { fail(when (f.code) {
            AccountFailureCode.UNAUTHENTICATED, AccountFailureCode.ACCOUNT_UNAVAILABLE -> SavedRecipeFailureCode.UNAUTHENTICATED
            AccountFailureCode.POLICY_BLOCKED -> SavedRecipeFailureCode.FORBIDDEN
            AccountFailureCode.NOT_CONFIGURED -> SavedRecipeFailureCode.NOT_CONFIGURED
            else -> SavedRecipeFailureCode.STORAGE_UNAVAILABLE
        }) }
        catch (f: PlanningServiceFailure) { fail(when (f.code) {
            PlanningFailureCode.UNAUTHENTICATED -> SavedRecipeFailureCode.UNAUTHENTICATED
            PlanningFailureCode.POLICY_BLOCKED -> SavedRecipeFailureCode.FORBIDDEN
            PlanningFailureCode.NOT_CONFIGURED -> SavedRecipeFailureCode.NOT_CONFIGURED
            PlanningFailureCode.RECIPE_RECALLED -> SavedRecipeFailureCode.RECIPE_RECALLED
            PlanningFailureCode.RECIPE_UNAVAILABLE -> SavedRecipeFailureCode.RECIPE_UNAVAILABLE
            PlanningFailureCode.PLAN_UNAVAILABLE, PlanningFailureCode.PLAN_EXPIRED -> SavedRecipeFailureCode.PLAN_UNAVAILABLE
            else -> SavedRecipeFailureCode.STORAGE_UNAVAILABLE
        }) }
        catch (f: RecipeCatalogFailure) { fail(if (f.code == RecipeCatalogFailureCode.NOT_CONFIGURED) SavedRecipeFailureCode.NOT_CONFIGURED else SavedRecipeFailureCode.STORAGE_UNAVAILABLE) }
        catch (f: IngredientCatalogFailure) { fail(if (f.code == IngredientCatalogFailureCode.NOT_CONFIGURED) SavedRecipeFailureCode.NOT_CONFIGURED else SavedRecipeFailureCode.STORAGE_UNAVAILABLE) }
        catch (f: CommitOutcomeUnknown) { throw f }
        catch (f: CancellationException) { throw f }
        catch (f: InterruptedException) { Thread.currentThread().interrupt(); throw f }
        catch (f: SQLException) { current(); if (f.sqlState in setOf("40001", "40P01")) throw f; fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE) }
        catch (_: Exception) { current(); fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE) }
    private fun current() { if (Thread.currentThread().isInterrupted) throw InterruptedException("Account Make Again interrupted") }
    private fun fail(code: SavedRecipeFailureCode): Nothing = throw SavedRecipeFailure(code)
    override fun toString() = "AccountMakeAgainStore(<redacted>)"
}
