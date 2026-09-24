package com.feedme.server.guest

import com.feedme.server.catalog.RecipeCopyRightsStore
import com.feedme.server.db.CommandResult
import com.feedme.server.db.CommitOutcomeUnknown
import com.feedme.server.db.PgTransactions
import com.feedme.server.db.StoredReply
import com.feedme.server.memory.*
import com.feedme.server.planning.PlanningFailureCode
import com.feedme.server.planning.PlanningServiceFailure
import java.sql.Connection
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Real guest-token durable saving, bounded reads/default collections and owned deletion.
 * Explicit cursor keys, policy and governed copy-rights store are mandatory. This is not
 * HTTP/mobile activation remains explicit; this supplies no implicit Make Again/feedback,
 * adaptation or social sharing. */
internal class GuestSavedRecipeStore(
    private val environment: String,
    private val transactions: PgTransactions,
    private val preparations: GuestPlanningStore,
    private val rights: RecipeCopyRightsStore,
    private val cursors: SavedRecipeCursors,
    val policy: SavedRecipeServicePolicy,
    private val makeAgain: GuestMakeAgainStore? = null,
) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(preparations.isBoundTo(environment, transactions) && preparations.hasSavingRightsOwner(rights))
        require(makeAgain == null || makeAgain.isSavingBoundTo(environment, transactions, preparations, rights, cursors, policy))
    }
    internal fun isBoundTo(owner: GuestSessionStore): Boolean = preparations.isBoundTo(owner)
    internal fun isBoundTo(owner: GuestPlanningStore): Boolean = preparations === owner
    fun saveRecipe(token: String, key: UUID, body: JsonObject): CommandResult =
        if ((body["markMakeAgain"] as? JsonPrimitive)?.booleanOrNull == true && makeAgain != null)
            makeAgain.saveRecipe(token, key, body)
        else use(token, "saveRecipe") { c, actor, store -> store.saveRecipe(c, actor, key, body) }
    fun getSavedRecipe(token: String, id: UUID): StoredReply =
        use(token, "getSavedRecipe") { c, actor, store -> store.getSavedRecipe(c, actor, id) }
    fun deleteSavedRecipe(token: String, key: UUID, id: UUID, ifMatch: String): CommandResult =
        use(token, "deleteSavedRecipe") { c, actor, store -> store.deleteSavedRecipe(c, actor, key, id, ifMatch) }
    fun listSavedRecipes(token: String, q: String? = null, cursor: String? = null, limit: Int = 20): StoredReply =
        use(token, "listSavedRecipes") { c, actor, store -> store.listSavedRecipes(c, actor, q, cursor, limit) }
    fun listCollections(token: String, cursor: String? = null, limit: Int = 20): StoredReply =
        use(token, "listCollections") { c, actor, store -> store.listCollections(c, actor, cursor, limit) }
    fun getCollection(token: String, id: UUID, cursor: String? = null, limit: Int = 20): StoredReply =
        use(token, "getCollection") { c, actor, store -> store.getCollection(c, actor, id, cursor, limit) }

    private fun <T> use(token: String, operation: String,
        action: (Connection, VerifiedSavedRecipePrincipal, SavedRecipeStore) -> SavedRecipeStore.Pending<T>): T = safe {
        preparations.withSavedRecipes(token, operation, rights, consume = { c, access ->
            val actor = access.principal
            val store = SavedRecipeStore(environment, transactions, access, cursors, policy)
            Attempt(actor, action(c, actor, store)).also { access.requireBound(c, actor) }
        }, complete = { c, access, attempt ->
            access.requireBound(c, attempt.actor)
            attempt.pending.revalidate(c, attempt.actor)
            access.requireBound(c, attempt.actor)
        }, completeAt = { c, _, attempt, at ->
            // Pure deadline rejection at the real guest owner's final database time.
            attempt.pending.checkAt(c, attempt.actor, at)
        }).pending.result
    }
    private class Attempt<T>(val actor: VerifiedSavedRecipePrincipal, val pending: SavedRecipeStore.Pending<T>)
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: GuestSessionFailure) { throw failure }
        catch (failure: SavedRecipeFailure) { throw failure }
        catch (failure: PlanningServiceFailure) { fail(when (failure.code) {
            PlanningFailureCode.UNAUTHENTICATED -> SavedRecipeFailureCode.UNAUTHENTICATED
            PlanningFailureCode.PLAN_UNAVAILABLE -> SavedRecipeFailureCode.PLAN_UNAVAILABLE
            PlanningFailureCode.RECIPE_RECALLED -> SavedRecipeFailureCode.RECIPE_RECALLED
            PlanningFailureCode.RECIPE_UNAVAILABLE, PlanningFailureCode.MODE_CONFIRMATION_REQUIRED -> SavedRecipeFailureCode.RECIPE_UNAVAILABLE
            PlanningFailureCode.NOT_CONFIGURED -> SavedRecipeFailureCode.NOT_CONFIGURED
            else -> SavedRecipeFailureCode.STORAGE_UNAVAILABLE
        }) }
        catch (failure: CommitOutcomeUnknown) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (_: Exception) { if (Thread.currentThread().isInterrupted) throw InterruptedException("Guest saving interrupted")
            fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE) }
    private fun fail(code: SavedRecipeFailureCode): Nothing = throw SavedRecipeFailure(code)
    override fun toString() = "GuestSavedRecipeStore(<redacted>)"
}
