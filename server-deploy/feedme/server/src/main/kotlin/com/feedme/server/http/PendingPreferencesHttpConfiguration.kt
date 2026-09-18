package com.feedme.server.http

import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.kitchen.PendingAccountPreferencesStore
import kotlinx.coroutines.CoroutineDispatcher

/** Only ConfiguredSupabasePreferencesAssembly supplies production instances. No arbitrary
 * KitchenHttpVerifier, guest authentication, ready-account route, provisioning or pantry grant.
 * Shared raw input/response validation remains the existing canonical kitchen implementation.
 */
class PendingPreferencesHttpConfiguration internal constructor(
    internal val store: PendingAccountPreferencesStore,
    internal val verifier: SupabaseUserAccessVerifier,
    internal val databaseDispatcher: CoroutineDispatcher,
) {
    override fun toString() = "PendingPreferencesHttpConfiguration(<redacted>)"
}
