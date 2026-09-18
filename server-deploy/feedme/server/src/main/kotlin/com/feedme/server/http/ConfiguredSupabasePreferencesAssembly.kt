package com.feedme.server.http

import com.feedme.server.auth.*
import com.feedme.server.db.PgTransactions
import com.feedme.server.identity.*
import com.feedme.server.kitchen.*
import java.time.Clock
import javax.sql.DataSource
import kotlinx.coroutines.CoroutineDispatcher

/** Explicit preferences/search-only assembly. The same trusted Supabase/FeedMe DataSource,
 * reviewed deployment, pending-account rules and actual DB-only catalog are mandatory.
 * No catalog, cursor key, provider configuration, initial choices or route is invented.
 * Borrowed database/dispatcher/catalog/cursor configuration remain caller-owned. This object
 * closes only its provider authority and HTTPS key engine; it never calls provider logout.
 */
class ConfiguredSupabasePreferencesAssembly private constructor(
    val http: PendingPreferencesHttpConfiguration,
    private val authority: SupabasePostgresAuthority,
    private val keys: HttpsSupabaseJwksSource,
) : AutoCloseable {
    override fun close() { authority.close(); keys.close() }
    override fun toString() = "ConfiguredSupabasePreferencesAssembly(<redacted>)"
    companion object {
        fun open(environment: String, database: DataSource, deployment: SupabaseAuthorityDeployment,
            accountRules: AccountPendingProfileRules, keyPolicy: SupabaseJwksHttpPolicy,
            catalog: PendingPreferencesCatalog, cursors: KitchenCursorCodec, policy: KitchenServicePolicy,
            databaseDispatcher: CoroutineDispatcher, clock: Clock): ConfiguredSupabasePreferencesAssembly {
            require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
            val transactions = PgTransactions(database)
            val authority = SupabasePostgresAuthority(deployment)
            try { transactions.run { authority.checkCompatibility(it); catalog.checkCompatibility(it) } }
            catch (_: Exception) { authority.close(); throw KitchenFailure(KitchenFailureCode.NOT_CONFIGURED) }
            val keys = try { HttpsSupabaseJwksSource.create(deployment.verification, keyPolicy, clock) }
                catch (_: Exception) { authority.close(); throw KitchenFailure(KitchenFailureCode.NOT_CONFIGURED) }
            return try {
                val accounts = AccountProfileStore(environment, transactions, SupabaseAccountBootstrapPolicy(authority, accountRules))
                val store = PendingAccountPreferencesStore(environment, transactions, accounts, catalog, cursors, policy)
                val verifier = SupabaseUserAccessVerifier(deployment.verification, keys, clock)
                ConfiguredSupabasePreferencesAssembly(PendingPreferencesHttpConfiguration(store, verifier, databaseDispatcher), authority, keys)
            } catch (_: Exception) { authority.close(); keys.close(); throw KitchenFailure(KitchenFailureCode.NOT_CONFIGURED) }
        }
    }
}
