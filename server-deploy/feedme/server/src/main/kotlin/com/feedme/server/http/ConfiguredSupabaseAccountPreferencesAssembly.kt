package com.feedme.server.http

import com.feedme.server.auth.*
import com.feedme.server.db.PgTransactions
import com.feedme.server.identity.*
import com.feedme.server.kitchen.*
import java.time.Clock
import javax.sql.DataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException

/** Explicit same-owner preferences/search assembly for setup and eligible ready accounts.
 * The real shared Supabase/FeedMe database, reviewed current account rules and published/free
 * catalog remain mandatory. Ready admission adds exact current terms; it never promotes an
 * account or silently falls back to setup. This does not enable pantry or private sessions.
 * The borrowed database, dispatcher, catalog and cursors remain caller-owned. Only this
 * assembly's provider authority and HTTPS key engine are closed, never provider sessions.
 */
class ConfiguredSupabaseAccountPreferencesAssembly private constructor(
    val http: AccountPreferencesHttpConfiguration,
    private val authority: SupabasePostgresAuthority,
    private val keys: HttpsSupabaseJwksSource,
) : AutoCloseable {
    override fun close() { authority.close(); keys.close() }
    override fun toString() = "ConfiguredSupabaseAccountPreferencesAssembly(<redacted>)"
    companion object {
        fun open(environment: String, database: DataSource, deployment: SupabaseAuthorityDeployment,
            accountRules: AccountPendingProfileRules, keyPolicy: SupabaseJwksHttpPolicy,
            catalog: PendingPreferencesCatalog, cursors: KitchenCursorCodec, policy: KitchenServicePolicy,
            databaseDispatcher: CoroutineDispatcher, clock: Clock): ConfiguredSupabaseAccountPreferencesAssembly {
            require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
            val transactions = PgTransactions(database)
            val authority = SupabasePostgresAuthority(deployment)
            try { transactions.run { authority.checkCompatibility(it); catalog.checkCompatibility(it) } }
            catch (failure: Exception) { authority.close(); failOpen(failure) }
            val keys = try { HttpsSupabaseJwksSource.create(deployment.verification, keyPolicy, clock) }
                catch (failure: Exception) { authority.close(); failOpen(failure) }
            return try {
                val accounts = AccountProfileStore(environment, transactions, SupabaseAccountBootstrapPolicy(authority, accountRules))
                val store = AccountPreferencesStore(environment, transactions, accounts, catalog, cursors, policy)
                val verifier = SupabaseUserAccessVerifier(deployment.verification, keys, clock)
                ConfiguredSupabaseAccountPreferencesAssembly(AccountPreferencesHttpConfiguration(store, verifier, databaseDispatcher), authority, keys)
            } catch (failure: Exception) { authority.close(); keys.close(); failOpen(failure) }
        }
        private fun failOpen(failure: Exception): Nothing {
            when (failure) {
                is CancellationException -> throw failure
                is InterruptedException -> { Thread.currentThread().interrupt(); throw failure }
                else -> throw KitchenFailure(KitchenFailureCode.NOT_CONFIGURED)
            }
        }
    }
}
