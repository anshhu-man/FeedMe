package com.feedme.server.http

import com.feedme.server.auth.*
import com.feedme.server.db.PgTransactions
import com.feedme.server.identity.*
import java.time.Clock
import javax.sql.DataSource
import kotlinx.coroutines.CoroutineDispatcher

/** Explicit, owned account-only composition. Does not read environment/secrets, migrate,
 * listen, deploy, configure Main or enable any other domain. The caller supplies the exact
 * same database holding auth and FeedMe schemas, verified deployment facts and server rules.
 * Native/project configuration is not inferred from a request. Missing/incompatible authority
 * throws before an HTTP configuration is returned. Borrowed DataSource/dispatcher stay owned
 * by the caller; only the private key-source engine and authority lifetime are closed here.
 */
class ConfiguredSupabaseAccountAssembly private constructor(
    val http: AccountHttpConfiguration,
    private val authority: SupabasePostgresAuthority,
    private val keys: HttpsSupabaseJwksSource,
) : AutoCloseable {
    override fun close() {
        authority.close() // Reject admission before retiring the owned HTTPS engine.
        keys.close()
    }
    override fun toString() = "ConfiguredSupabaseAccountAssembly(<redacted>)"
    companion object {
        fun open(environment: String, database: DataSource, deployment: SupabaseAuthorityDeployment,
            accountRules: AccountPendingProfileRules, keyPolicy: SupabaseJwksHttpPolicy,
            databaseDispatcher: CoroutineDispatcher, clock: Clock): ConfiguredSupabaseAccountAssembly {
            require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) { "Invalid account environment" }
            val transactions = PgTransactions(database)
            val authority = SupabasePostgresAuthority(deployment)
            try {
                transactions.run(authority::checkCompatibility)
            } catch (_: Exception) {
                authority.close()
                throw AccountFailure(AccountFailureCode.NOT_CONFIGURED)
            }
            val keys = try { HttpsSupabaseJwksSource.create(deployment.verification, keyPolicy, clock) }
                catch (_: Exception) { authority.close(); throw AccountFailure(AccountFailureCode.NOT_CONFIGURED) }
            return try {
                val verifier = SupabaseUserAccessVerifier(deployment.verification, keys, clock)
                val store = AccountProfileStore(environment, transactions, SupabaseAccountBootstrapPolicy(authority, accountRules))
                ConfiguredSupabaseAccountAssembly(AccountHttpConfiguration(store, verifier, databaseDispatcher), authority, keys)
            } catch (_: Exception) {
                authority.close(); keys.close()
                throw AccountFailure(AccountFailureCode.NOT_CONFIGURED)
            }
        }
    }
}
