package com.feedme.server.memory

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.catalog.RecipeCopyRightsStore
import com.feedme.server.cooking.AccountMealAccess
import com.feedme.server.db.*
import com.feedme.server.identity.*
import java.sql.Connection
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** Explicit six-operation account cookbook owner. Current identity and retained rights
 * are rechecked after result/receipt writes and before one atomic commit. No publication,
 * Make Again, saved-source replanning or social-source grant is inferred. */
internal class AccountSavedRecipeStore(private val environment: String, private val transactions: PgTransactions,
    private val accounts: AccountProfileStore, private val rights: RecipeCopyRightsStore,
    private val cursors: SavedRecipeCursors, val policy: SavedRecipeServicePolicy,
    private val newCopiesEnabled: Boolean) {
    init { require(rights.environment == environment) }
    fun saveRecipe(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, body: JsonObject): CommandResult =
        use(subject, device) { c, a, s -> s.saveRecipe(c, a, key, body) }
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
    private fun <T> use(subject: VerifiedSupabaseSubject, device: UUID,
        action: (Connection, VerifiedSavedRecipePrincipal, SavedRecipeStore) -> SavedRecipeStore.Pending<T>): T = safe {
        transactions.run { c ->
            val account = AccountMealAccess(environment, c, accounts, subject, device)
            val access = AccountSavedRecipeAccess(environment, c, account, device, rights, newCopiesEnabled)
            val actor = access.principal
            val store = SavedRecipeStore(environment, transactions, access, cursors, policy)
            val pending = action(c, actor, store)
            account.current(c)
            pending.revalidate(c, actor)
            access.revalidate()
            account.current(c)
            val at = AccountMealAccess.now(c)
            access.checkAt(at); pending.checkAt(c, actor, at); account.checkAt(c, at)
            pending.result
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
        catch (f: CommitOutcomeUnknown) { throw f }
        catch (f: CancellationException) { throw f }
        catch (f: InterruptedException) { Thread.currentThread().interrupt(); throw f }
        catch (_: Exception) { if (Thread.currentThread().isInterrupted) throw InterruptedException("Account saving interrupted")
            fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE) }
    private fun fail(code: SavedRecipeFailureCode): Nothing = throw SavedRecipeFailure(code)
    override fun toString() = "AccountSavedRecipeStore(<redacted>)"
}
