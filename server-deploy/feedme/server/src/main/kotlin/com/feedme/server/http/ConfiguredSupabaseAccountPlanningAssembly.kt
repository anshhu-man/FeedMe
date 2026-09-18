package com.feedme.server.http

import com.feedme.server.auth.*
import com.feedme.server.catalog.RecipeCatalogStore
import com.feedme.server.db.PgTransactions
import com.feedme.server.identity.*
import com.feedme.server.planning.*
import java.time.Clock
import javax.sql.DataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher

/** Opt-in production composition using actual provider/account/device authority and exact
 * private inputs/current published catalog. No environment lookup, migration, seed, default
 * operational values or Main activation. Database/catalog/cursors/dispatcher are borrowed;
 * this object owns the provider authority and HTTPS public key reader only. */
class ConfiguredSupabaseAccountPlanningAssembly private constructor(
    val http: AccountPlanningHttpConfiguration,
    private val authority: SupabasePostgresAuthority,
    private val keys: HttpsSupabaseJwksSource,
) : AutoCloseable {
    override fun close() { authority.close(); keys.close() }
    override fun toString() = "ConfiguredSupabaseAccountPlanningAssembly(<redacted>)"

    companion object {
        fun open(environment: String, database: DataSource, deployment: SupabaseAuthorityDeployment,
            accountRules: AccountPendingProfileRules, keyPolicy: SupabaseJwksHttpPolicy,
            catalog: RecipeCatalogStore, operational: AccountPlanningPolicy, policy: PlanningServicePolicy,
            cursors: PlanningCursors, databaseDispatcher: CoroutineDispatcher, clock: Clock): ConfiguredSupabaseAccountPlanningAssembly {
            require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")) && catalog.environment == environment) {
                "Invalid planning environment configuration"
            }
            val transactions = PgTransactions(database)
            val authority = SupabasePostgresAuthority(deployment)
            try { transactions.run { authority.checkCompatibility(it); operational.checkCompatibility(it); catalog.checkCompatibility(it) } }
            catch (failure: Exception) { authority.close(); failOpen(failure) }
            val keys = try { HttpsSupabaseJwksSource.create(deployment.verification, keyPolicy, clock) }
                catch (failure: Exception) { authority.close(); failOpen(failure) }
            return try {
                val accounts = AccountProfileStore(environment, transactions, SupabaseAccountBootstrapPolicy(authority, accountRules))
                val store = AccountPlanningStore(environment, transactions, accounts, catalog, operational, policy, cursors)
                val verifier = SupabaseUserAccessVerifier(deployment.verification, keys, clock)
                ConfiguredSupabaseAccountPlanningAssembly(AccountPlanningHttpConfiguration(store, verifier, databaseDispatcher), authority, keys)
            } catch (failure: Exception) { authority.close(); keys.close(); failOpen(failure) }
        }
        private fun failOpen(failure: Exception): Nothing = when (failure) {
            is CancellationException -> throw failure
            is InterruptedException -> { Thread.currentThread().interrupt(); throw failure }
            else -> throw PlanningServiceFailure(PlanningFailureCode.NOT_CONFIGURED)
        }
    }
}
