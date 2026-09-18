package com.feedme.server.http

import com.feedme.server.auth.*
import com.feedme.server.catalog.*
import com.feedme.server.cooking.*
import com.feedme.server.db.PgTransactions
import com.feedme.server.identity.*
import com.feedme.server.memory.*
import com.feedme.server.planning.*
import java.time.Clock
import javax.sql.DataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher

/** Explicit account Plan -> cooking -> private cookbook composition. Borrowed catalog and
 * governed copy registry must already exist; no approval/eligibility/rights are fabricated.
 * Owns provider/JWKS resources only. No migration, environment lookup or Main activation. */
class ConfiguredSupabaseAccountMealAssembly private constructor(
    val planning: AccountPlanningHttpConfiguration, val cooking: AccountCookingHttpConfiguration,
    val saved: AccountSavedRecipeHttpConfiguration, private val authority: SupabasePostgresAuthority,
    private val keys: HttpsSupabaseJwksSource,
) : AutoCloseable {
    override fun close() { authority.close(); keys.close() }
    override fun toString() = "ConfiguredSupabaseAccountMealAssembly(<redacted>)"
    companion object {
        fun open(environment: String, database: DataSource, deployment: SupabaseAuthorityDeployment,
            accountRules: AccountPendingProfileRules, keyPolicy: SupabaseJwksHttpPolicy,
            catalog: RecipeCatalogStore, rights: RecipeCopyRightsStore, operational: AccountPlanningPolicy,
            planningPolicy: PlanningServicePolicy, planningCursors: PlanningCursors,
            cookingPolicy: CookingServicePolicy, newCookingEnabled: Boolean,
            savedPolicy: SavedRecipeServicePolicy, savedCursors: SavedRecipeCursors, newCopiesEnabled: Boolean,
            databaseDispatcher: CoroutineDispatcher, clock: Clock): ConfiguredSupabaseAccountMealAssembly {
            require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")) && catalog.environment == environment && rights.environment == environment)
            val transactions = PgTransactions(database)
            val authority = SupabasePostgresAuthority(deployment)
            try { transactions.run { authority.checkCompatibility(it); operational.checkCompatibility(it)
                catalog.checkCompatibility(it); rights.checkCompatibility(it) } }
            catch (f: Exception) { authority.close(); unavailable(f) }
            val keys = try { HttpsSupabaseJwksSource.create(deployment.verification, keyPolicy, clock) }
                catch (f: Exception) { authority.close(); unavailable(f) }
            return try {
                val accounts = AccountProfileStore(environment, transactions, SupabaseAccountBootstrapPolicy(authority, accountRules))
                val plans = AccountPlanningStore(environment, transactions, accounts, catalog, operational, planningPolicy, planningCursors)
                val cooking = AccountCookingStore(environment, transactions, accounts, plans, cookingPolicy, newCookingEnabled)
                val saved = AccountSavedRecipeStore(environment, transactions, accounts, rights, savedCursors, savedPolicy, newCopiesEnabled)
                val verifier = SupabaseUserAccessVerifier(deployment.verification, keys, clock)
                ConfiguredSupabaseAccountMealAssembly(AccountPlanningHttpConfiguration(plans, verifier, databaseDispatcher),
                    AccountCookingHttpConfiguration(cooking, verifier, databaseDispatcher),
                    AccountSavedRecipeHttpConfiguration(saved, verifier, databaseDispatcher), authority, keys)
            } catch (f: Exception) { authority.close(); keys.close(); unavailable(f) }
        }
        private fun unavailable(f: Exception): Nothing = when (f) {
            is CancellationException -> throw f
            is InterruptedException -> { Thread.currentThread().interrupt(); throw f }
            else -> throw CookingFailure(CookingFailureCode.NOT_CONFIGURED)
        }
    }
}
