package com.feedme.server.http

import com.feedme.server.guest.GuestIngredientSearchStore
import com.feedme.server.guest.GuestKitchenStore
import com.feedme.server.guest.GuestSessionStore
import kotlinx.coroutines.CoroutineDispatcher

/** Explicit guest bootstrap + ingredient-search composition, not deployment admission.
 * A runtime must supply the real session authority, protected keys, catalog and owned bounded
 * DB dispatcher before constructing this. No accepting policy, fixture, secret lookup or
 * Main activation is supplied here. Account search may coexist via its separate verifier.
 * Only implemented guest capabilities may be advertised by this purpose-fixed assembly.
 */
class GuestHttpConfiguration internal constructor(
    internal val sessions: GuestSessionStore,
    internal val search: GuestIngredientSearchStore,
    internal val databaseDispatcher: CoroutineDispatcher,
    internal val kitchen: GuestKitchenStore? = null,
) {
    init {
        require(search.isBoundTo(sessions)) { "Guest HTTP session owner mismatch" }
        require(kitchen == null || kitchen.isBoundTo(sessions)) { "Guest kitchen session owner mismatch" }
        val implemented = setOf("searchIngredients") + if (kitchen == null) emptySet() else guestKitchenHttpOperations
        require(sessions.advertisedCapabilities.toSet() == implemented) {
            "Guest HTTP capabilities do not match implemented operations"
        }
    }
    override fun toString() = "GuestHttpConfiguration(<redacted>)"
}
