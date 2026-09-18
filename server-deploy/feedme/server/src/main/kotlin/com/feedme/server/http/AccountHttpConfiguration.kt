package com.feedme.server.http

import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.identity.AccountProfileStore
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Explicit account/profile ingress only. Main never supplies this configuration by default.
 * The concrete verifier authenticates signed provider identity facts; the store's mandatory
 * current provider/session/confirmation/recovery and account policy independently authorizes
 * every transaction and replay. No JWT claim, installation ID, or request header grants access.
 * The caller owns the store, its policy, trusted verifier configuration and JDBC dispatcher.
 * This adapter neither refreshes credentials nor retries commands or unknown commits.
 */
class AccountHttpConfiguration(
    val store: AccountProfileStore,
    val verifier: SupabaseUserAccessVerifier,
    val databaseDispatcher: CoroutineDispatcher,
) {
    override fun toString() = "AccountHttpConfiguration(<redacted>)"
    companion object { const val MAX_REQUEST_BYTES = 16_384 }
}
