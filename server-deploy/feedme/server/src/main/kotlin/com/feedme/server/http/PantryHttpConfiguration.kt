package com.feedme.server.http

import com.feedme.server.kitchen.KitchenStore
import kotlinx.coroutines.CoroutineDispatcher

/** Purpose-fixed three-operation pantry composition, disjoint from self-preferences/search.
 * Reuses the SAME KitchenStore command/receipt/transactional authority and mandatory token
 * verifier; it neither supplies a catalog nor adapts restricted preferences authority into
 * private-domain access. The caller owns real store, verifier, database and dispatcher.
 * No provisioning, default content, account readiness or accepting provider is introduced.
 * Full KitchenHttpConfiguration cannot be installed alongside this overlapping pantry view.
 */
class PantryHttpConfiguration(
    val environment: String,
    val store: KitchenStore,
    val verifier: KitchenHttpVerifier,
    val databaseDispatcher: CoroutineDispatcher,
) {
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }
    override fun toString() = "PantryHttpConfiguration(<redacted>)"
}
