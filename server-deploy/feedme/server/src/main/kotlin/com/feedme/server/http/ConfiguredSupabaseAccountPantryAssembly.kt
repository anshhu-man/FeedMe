package com.feedme.server.http

import com.feedme.server.auth.*
import com.feedme.server.catalog.IngredientCatalogStore
import com.feedme.server.db.PgTransactions
import com.feedme.server.identity.*
import com.feedme.server.kitchen.*
import java.time.Clock
import javax.sql.DataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher

/** Explicit production composition; no accepting verifier, sample catalog, eligibility grant,
 * environment lookup, migration or automatic Main activation. The caller supplies the actual
 * shared auth/FeedMe database, deployment review, current rules and catalog configuration.
 * Only the three pantry routes are exposed. Caller-owned database/dispatcher/catalog/cursors
 * are borrowed; this assembly owns and retires its provider authority and HTTPS key source.
 */
class ConfiguredSupabaseAccountPantryAssembly private constructor(
    val http: AccountPantryHttpConfiguration,
    private val authority: SupabasePostgresAuthority,
    private val keys: HttpsSupabaseJwksSource,
) : AutoCloseable {
    override fun close() { authority.close(); keys.close() }
    override fun toString() = "ConfiguredSupabaseAccountPantryAssembly(<redacted>)"

    companion object {
        fun open(environment: String, database: DataSource, deployment: SupabaseAuthorityDeployment,
            accountRules: AccountPendingProfileRules, keyPolicy: SupabaseJwksHttpPolicy,
            catalog: IngredientCatalogStore, cursors: KitchenCursorCodec, policy: KitchenServicePolicy,
            databaseDispatcher: CoroutineDispatcher, clock: Clock): ConfiguredSupabaseAccountPantryAssembly {
            require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")) && catalog.environment == environment) {
                "Invalid pantry environment configuration"
            }
            val transactions = PgTransactions(database)
            val authority = SupabasePostgresAuthority(deployment)
            try { transactions.run { authority.checkCompatibility(it); catalog.checkCompatibility(it) } }
            catch (failure: Exception) { authority.close(); failOpen(failure) }
            val keys = try { HttpsSupabaseJwksSource.create(deployment.verification, keyPolicy, clock) }
                catch (failure: Exception) { authority.close(); failOpen(failure) }
            return try {
                val accounts = AccountProfileStore(environment, transactions, SupabaseAccountBootstrapPolicy(authority, accountRules))
                val store = AccountPantryStore(environment, transactions, accounts, catalog, cursors, policy)
                val verifier = SupabaseUserAccessVerifier(deployment.verification, keys, clock)
                ConfiguredSupabaseAccountPantryAssembly(AccountPantryHttpConfiguration(store, verifier, databaseDispatcher), authority, keys)
            } catch (failure: Exception) { authority.close(); keys.close(); failOpen(failure) }
        }

        private fun failOpen(failure: Exception): Nothing = when (failure) {
            is CancellationException -> throw failure
            is InterruptedException -> { Thread.currentThread().interrupt(); throw failure }
            else -> throw KitchenFailure(KitchenFailureCode.NOT_CONFIGURED)
        }
    }
}
