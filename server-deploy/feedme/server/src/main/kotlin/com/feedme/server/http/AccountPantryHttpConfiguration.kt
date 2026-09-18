package com.feedme.server.http

import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.kitchen.AccountPantryStore
import kotlinx.coroutines.CoroutineDispatcher

/** Only ready-account pantry operations. The store resolves and rechecks its own private
 * principal from verified provider subject + registered device; no caller-supplied owner.
 * Cannot coexist with either overlapping full-kitchen or arbitrary-principal pantry routes.
 */
class AccountPantryHttpConfiguration internal constructor(
    internal val store: AccountPantryStore,
    internal val verifier: SupabaseUserAccessVerifier,
    internal val databaseDispatcher: CoroutineDispatcher,
) {
    override fun toString() = "AccountPantryHttpConfiguration(<redacted>)"
}
