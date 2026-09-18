package com.feedme.server.kitchen

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.db.*
import com.feedme.server.identity.*
import java.sql.Connection
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Mandatory operator-supplied, DB-only published catalog policy. There is NO default catalog.
 * The historical name is retained for compatibility: the same limited self-preferences policy
 * serves setup and eligible ready accounts, without adding paid-content or private-domain rights.
 * Compatibility may acquire schema stability locks but must not take selection row locks.
 * Validation/search run after provider -> account/private principal -> device/profile ->
 * receipt/preferences locks. They must lock current publication/free-access/taxonomy/equipment/
 * consent facts, reject unsupported fresh selections, and never expand or rewrite a choice.
 * previousFields is the actual locked stored resource fields: retaining a retired exclusion
 * must not silently remove it or become a fresh catalog selection. Cursors must bind the exact
 * principal, query, limit and current catalog rules. Search must return canonical IngredientPage.
 * Implementations must not commit, perform network I/O, infer answers from empty lists, or
 * grant account readiness. Missing/incompatible configuration throws KitchenFailure.
 */
interface PendingPreferencesCatalog {
    fun checkCompatibility(connection: Connection)
    fun validatePreferences(connection: Connection, principal: VerifiedKitchenPrincipal,
        previousFields: JsonObject, proposed: JsonObject)
    fun search(connection: Connection, principal: VerifiedKitchenPrincipal, query: String?, cursor: String?, limit: Int): JsonObject
    fun lookup(connection: Connection, principal: VerifiedKitchenPrincipal, ids: List<UUID>): JsonObject =
        throw KitchenFailure(KitchenFailureCode.NOT_CONFIGURED)
}

/** Restricted sibling, never an ordinary KitchenAuthority or public arbitrary-principal store.
 * The mapping read is not an admission cache. EACH actual KitchenStore transaction repeats
 * the exact provider/account/device/profile proof with its original signature-verified facts.
 * Only three canonical operations are exposed; neither trusted provisioning nor pantry can be called here.
 */
internal class PendingAccountPreferencesStore(
    environment: String,
    transactions: PgTransactions,
    accounts: AccountProfileStore,
    catalog: PendingPreferencesCatalog,
    cursors: KitchenCursorCodec,
    policy: KitchenServicePolicy,
) : AccountPreferencesOperations by AccountSelfPreferencesStore(
    environment, transactions, accounts, catalog, cursors, policy, PreferencesAdmission.SETUP_ONLY,
) {
    override fun toString() = "PendingAccountPreferencesStore(<redacted>)"
}

/** Explicit same-owner setup/ready composition. Current ready access requires independently
 * established eligibility and exact current accepted terms. It never tries another authority
 * after denial. The original subject/device and actual private principal remain bound while
 * current mode is re-evaluated in EACH transaction, including exact-original replay.
 */
internal class AccountPreferencesStore(
    environment: String,
    transactions: PgTransactions,
    accounts: AccountProfileStore,
    catalog: PendingPreferencesCatalog,
    cursors: KitchenCursorCodec,
    policy: KitchenServicePolicy,
) : AccountPreferencesOperations by AccountSelfPreferencesStore(
    environment, transactions, accounts, catalog, cursors, policy, PreferencesAdmission.CURRENT_SELF,
) {
    override fun toString() = "AccountPreferencesStore(<redacted>)"
}

/** Only the three canonical self-preferences operations; no principal-accepting entry point. */
internal interface AccountPreferencesOperations {
    val policy: KitchenServicePolicy
    fun getPreferences(subject: VerifiedSupabaseSubject, device: UUID): StoredReply
    fun updatePreferences(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, ifMatch: String, body: JsonObject): CommandResult
    fun searchIngredients(subject: VerifiedSupabaseSubject, device: UUID, query: String?, cursor: String?, limit: Int): StoredReply
    fun lookupIngredients(subject: VerifiedSupabaseSubject, device: UUID, ids: List<UUID>): StoredReply
}

/** Fixed by trusted construction, never selected by request input or by catching a refusal. */
private enum class PreferencesAdmission { SETUP_ONLY, CURRENT_SELF }

private class AccountSelfPreferencesStore(
    private val environment: String,
    private val transactions: PgTransactions,
    private val accounts: AccountProfileStore,
    private val catalog: PendingPreferencesCatalog,
    private val cursors: KitchenCursorCodec,
    override val policy: KitchenServicePolicy,
    private val admission: PreferencesAdmission,
) : AccountPreferencesOperations {
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    override fun getPreferences(subject: VerifiedSupabaseSubject, device: UUID): StoredReply =
        use(subject, device) { actor, store -> store.getPreferences(actor) }

    override fun updatePreferences(subject: VerifiedSupabaseSubject, device: UUID, key: UUID,
        ifMatch: String, body: JsonObject): CommandResult =
        use(subject, device) { actor, store -> store.updatePreferences(actor, key, ifMatch, body) }

    override fun searchIngredients(subject: VerifiedSupabaseSubject, device: UUID, query: String?, cursor: String?, limit: Int): StoredReply =
        use(subject, device) { actor, store -> store.searchIngredients(actor, query, cursor, limit) { c, principal, q, next, size -> safe {
            val result = catalog.search(c, principal, q, next, size)
            // A held catalog query cannot turn an expired provider proof into a response.
            if (lockCurrentPreferences(c, subject, device) != actor.principalId) denied()
            result
        } } }

    override fun lookupIngredients(subject: VerifiedSupabaseSubject, device: UUID, ids: List<UUID>): StoredReply {
        val selected = checkedIngredientIds(ids)
        return use(subject, device) { actor, store -> store.lookupIngredients(actor, selected, object : KitchenIngredientSearch {
            override fun search(connection: Connection, principal: VerifiedKitchenPrincipal, q: String?, cursor: String?, limit: Int): JsonObject = unavailable()
            override fun lookup(connection: Connection, principal: VerifiedKitchenPrincipal, ids: List<UUID>): JsonObject = safe {
                val result = catalog.lookup(connection, principal, ids)
                if (lockCurrentPreferences(connection, subject, device) != actor.principalId) denied()
                result
            }
        }) }
    }

    private fun <T> use(subject: VerifiedSupabaseSubject, device: UUID,
        action: (VerifiedKitchenPrincipal, KitchenStore) -> T): T = safe {
        val id = transactions.run { lockCurrentPreferences(it, subject, device) }
        val actor = VerifiedKitchenPrincipal(environment, CommandActor.ACCOUNT, id, device)
        val current = object : KitchenAuthority, KitchenCompletionAuthority {
            override fun lockPrincipal(connection: Connection, principal: VerifiedKitchenPrincipal) = safe {
                if (principal !== actor || lockCurrentPreferences(connection, subject, device) != id) denied()
                catalog.checkCompatibility(connection)
                if (lockCurrentPreferences(connection, subject, device) != id) denied()
            }
            override fun revalidatePrincipal(connection: Connection, principal: VerifiedKitchenPrincipal) = safe {
                if (principal !== actor || lockCurrentPreferences(connection, subject, device) != id) denied()
            }
            override fun requireProvisioningAllowed(connection: Connection, principal: VerifiedKitchenPrincipal): Unit = unavailable()
            override fun validatePantryItem(connection: Connection, principal: VerifiedKitchenPrincipal, proposed: JsonObject): Unit = unavailable()
            override fun validatePreferences(connection: Connection, principal: VerifiedKitchenPrincipal, proposed: JsonObject) = safe {
                if (principal !== actor) denied()
                val previous = connection.prepareStatement("SELECT fields FROM profile.preferences WHERE environment=? AND actor_kind='account' AND principal_id=? FOR UPDATE").use { statement ->
                    statement.setString(1, environment); statement.setObject(2, id)
                    statement.executeQuery().use { rows ->
                        if (!rows.next()) throw KitchenFailure(KitchenFailureCode.PREFERENCES_UNAVAILABLE)
                        Json.parseToJsonElement(rows.getString(1)).jsonObject.also { if (rows.next()) unavailable() }
                    }
                }
                catalog.validatePreferences(connection, principal, previous, proposed)
                if (lockCurrentPreferences(connection, subject, device) != id) denied()
            }
        }
        action(actor, KitchenStore(environment, transactions, current, cursors, policy))
    }

    private fun lockCurrentPreferences(connection: Connection, subject: VerifiedSupabaseSubject, device: UUID): UUID =
        when (admission) {
            PreferencesAdmission.SETUP_ONLY -> accounts.lockPendingPreferences(connection, subject, device)
            PreferencesAdmission.CURRENT_SELF -> accounts.lockAccountPreferences(connection, subject, device).principalId
        }

    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: AccountFailure) { throw KitchenFailure(when (failure.code) {
            AccountFailureCode.UNAUTHENTICATED, AccountFailureCode.ACCOUNT_UNAVAILABLE -> KitchenFailureCode.UNAUTHENTICATED
            AccountFailureCode.POLICY_BLOCKED -> KitchenFailureCode.POLICY_BLOCKED
            AccountFailureCode.NOT_CONFIGURED -> KitchenFailureCode.NOT_CONFIGURED
            else -> KitchenFailureCode.STORAGE_UNAVAILABLE
        }) }
        catch (failure: KitchenFailure) { throw failure }
        catch (failure: CommitOutcomeUnknown) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (_: Exception) { throw KitchenFailure(KitchenFailureCode.STORAGE_UNAVAILABLE) }
    override fun toString() = "AccountSelfPreferencesStore(<redacted>)"
    private fun denied(): Nothing = throw KitchenFailure(KitchenFailureCode.UNAUTHENTICATED)
    private fun unavailable(): Nothing = throw KitchenFailure(KitchenFailureCode.NOT_CONFIGURED)
}
