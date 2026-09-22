package com.feedme.server.memory

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.catalog.RecipeCopyRightsStore
import com.feedme.server.cooking.AccountMealAccess
import com.feedme.server.db.*
import com.feedme.server.identity.*
import com.feedme.server.planning.*
import java.sql.Connection
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Explicit six-operation account cookbook owner. Current identity and retained rights
 * are rechecked after result/receipt writes and before one atomic commit. No publication,
 * saved-source replanning or social-source grant is inferred. Make Again is an
 * explicit, optional compound owner, never inferred from an ordinary Save. */
internal class AccountSavedRecipeStore(private val environment: String, private val transactions: PgTransactions,
    private val accounts: AccountProfileStore, private val rights: RecipeCopyRightsStore,
    private val cursors: SavedRecipeCursors, val policy: SavedRecipeServicePolicy,
    private val newCopiesEnabled: Boolean, private val planning: AccountPlanningStore? = null,
    private val collectionMutationsEnabled: Boolean = false,
    private val postCopies: AccountPostRecipeCopyAuthority? = null,
    private val makeAgain: AccountMakeAgainStore? = null) {
    init {
        require(rights.environment == environment)
        require(planning == null || planning.isBoundTo(environment, transactions, accounts))
        require(makeAgain == null || makeAgain.isSavingBoundTo(environment, transactions, accounts,
            rights, cursors, policy, planning, newCopiesEnabled))
    }
    fun saveRecipe(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, body: JsonObject): CommandResult =
        if (body["markMakeAgain"] == JsonPrimitive(true))
            (makeAgain ?: fail(SavedRecipeFailureCode.NOT_CONFIGURED)).saveRecipe(subject, device, key, body)
        else use(subject, device) { c, a, s -> s.saveRecipe(c, a, key, body) }
    fun savePostRecipe(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, postId: UUID, body: JsonObject): CommandResult =
        use(subject, device) { c, a, s -> s.savePostRecipe(c, a, key, postId, body) }
    fun getSavedRecipe(subject: VerifiedSupabaseSubject, device: UUID, id: UUID): StoredReply =
        use(subject, device) { c, a, s -> s.getSavedRecipe(c, a, id) }
    fun deleteSavedRecipe(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, id: UUID, ifMatch: String): CommandResult =
        use(subject, device) { c, a, s -> s.deleteSavedRecipe(c, a, key, id, ifMatch) }
    fun listSavedRecipes(subject: VerifiedSupabaseSubject, device: UUID, q: String? = null, cursor: String? = null, limit: Int = 20): StoredReply =
        use(subject, device) { c, a, s -> s.listSavedRecipes(c, a, q, cursor, limit) }
    fun listCollections(subject: VerifiedSupabaseSubject, device: UUID, cursor: String? = null, limit: Int = 20): StoredReply =
        use(subject, device) { c, a, s -> s.listCollections(c, a, cursor, limit) }
    fun getCollection(subject: VerifiedSupabaseSubject, device: UUID, id: UUID, cursor: String? = null, limit: Int = 20): StoredReply =
        use(subject, device) { c, a, s -> s.getCollection(c, a, id, cursor, limit) }
    fun createCollection(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, body: JsonObject): CommandResult =
        collectionMutation(subject,device,"createCollection",key,body=body)
    fun updateCollection(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, id: UUID, ifMatch: String, body: JsonObject): CommandResult =
        collectionMutation(subject,device,"updateCollection",key,id,ifMatch=ifMatch,body=body)
    fun deleteCollection(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, id: UUID, ifMatch: String): CommandResult =
        collectionMutation(subject,device,"deleteCollection",key,id,ifMatch=ifMatch)
    fun addCollectionItem(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, id: UUID, body: JsonObject): CommandResult =
        collectionMutation(subject,device,"addCollectionItem",key,id,body=body)
    fun removeCollectionItem(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, id: UUID, savedId: UUID, ifMatch: String): CommandResult =
        collectionMutation(subject,device,"removeCollectionItem",key,id,savedId,ifMatch)
    private fun collectionMutation(subject: VerifiedSupabaseSubject, device: UUID, operation: String, key: UUID,
        id: UUID?=null,savedId:UUID?=null,ifMatch:String?=null,body:JsonObject?=null):CommandResult {
        if(!collectionMutationsEnabled) fail(SavedRecipeFailureCode.NOT_CONFIGURED)
        return use(subject,device) { c,a,s -> CollectionMutationStore(environment,transactions,s,cursors,policy)
            .execute(c,a,operation,key,id,savedId,ifMatch,body) }
    }
    private fun <T> use(subject: VerifiedSupabaseSubject, device: UUID,
        action: (Connection, VerifiedSavedRecipePrincipal, SavedRecipeStore) -> SavedRecipeStore.Pending<T>): T = safe {
        transactions.run { c ->
            val account = AccountMealAccess(environment, c, accounts, subject, device)
            fun owned(plans: PlansStore?, planningActor: VerifiedPlanningPrincipal?, checkSourceAt: (java.time.Instant) -> Unit): T {
                val access = AccountSavedRecipeAccess(environment, c, account, device, rights, newCopiesEnabled, plans, planningActor,
                    postCopies, subject)
                val actor = access.principal
                val store = SavedRecipeStore(environment, transactions, access, cursors, policy)
                val pending = action(c, actor, store)
                account.current(c)
                pending.revalidate(c, actor)
                access.revalidate()
                account.current(c)
                val at = AccountMealAccess.now(c)
                checkSourceAt(at); access.checkAt(at); pending.checkAt(c, actor, at); account.checkAt(c, at)
                return pending.result
            }
            if (planning == null) owned(null, null, {})
            else planning.withOwnedTransaction(c, subject, device) { actor, plans, checkSourceAt -> owned(plans, actor, checkSourceAt) }
        }
    }
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (f: SavedRecipeFailure) { throw f }
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
        catch (f: CommitOutcomeUnknown) { throw f }
        catch (f: CancellationException) { throw f }
        catch (f: InterruptedException) { Thread.currentThread().interrupt(); throw f }
        catch (_: Exception) { if (Thread.currentThread().isInterrupted) throw InterruptedException("Account saving interrupted")
            fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE) }
    private fun fail(code: SavedRecipeFailureCode): Nothing = throw SavedRecipeFailure(code)
    override fun toString() = "AccountSavedRecipeStore(<redacted>)"
}
