package com.feedme.server.http

import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.kitchen.AccountPreferencesStore
import kotlinx.coroutines.CoroutineDispatcher

/** Explicit setup/ready self-preferences composition supplied by
 * ConfiguredSupabaseAccountPreferencesAssembly. Its concrete store rechecks current authority
 * in every transaction. No guest, arbitrary principal, pantry, provisioning or private lease.
 * Cannot be installed alongside either overlapping kitchen or pending-only route authority.
 */
class AccountPreferencesHttpConfiguration internal constructor(
    internal val store: AccountPreferencesStore,
    internal val verifier: SupabaseUserAccessVerifier,
    internal val databaseDispatcher: CoroutineDispatcher,
) {
    override fun toString() = "AccountPreferencesHttpConfiguration(<redacted>)"
}
