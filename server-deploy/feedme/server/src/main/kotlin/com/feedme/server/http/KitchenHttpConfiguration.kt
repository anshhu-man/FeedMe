package com.feedme.server.http

import com.feedme.core.ports.PortResult
import com.feedme.core.ports.SecretText
import com.feedme.server.kitchen.KitchenIngredientSearch
import com.feedme.server.kitchen.KitchenStore
import com.feedme.server.kitchen.VerifiedKitchenPrincipal
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher

/** Unverified secret metadata; token class is never inferred from a header or spelling. */
class KitchenHttpBearer internal constructor(val token: SecretText, val deviceSessionId: UUID?) {
    override fun toString() = "KitchenHttpBearer(<redacted>)"
}

/**
 * Required trusted integration: verify issuer/signature, audience/client, token_use, expiry and
 * scope for an actual account token, or authenticate a bounded guest session. Return only its
 * verified subject/kind/environment/device binding. A refresh or invitation token is neither.
 * Store transactions still lock/revalidate current eligibility, revocation, guest expiry/merge,
 * private ownership and catalog policy. No provider or accepting implementation is supplied.
 */
fun interface KitchenHttpVerifier {
    suspend fun verify(bearer: KitchenHttpBearer): PortResult<VerifiedKitchenPrincipal>
}

/**
 * Explicit composition for five private kitchen operations and controlled ingredient search.
 * Never provisions preferences, chooses a provider or enables a missing catalog. Search must
 * use current authorized published/free ingredient data and principal/filter-bound cursors.
 * The request wire profile is 64 KiB/depth32. Response limits come from the store's explicit
 * policy and must also be configured on the client before activation. Main remains unconfigured.
 */
class KitchenHttpConfiguration(
    val environment: String,
    val store: KitchenStore,
    val verifier: KitchenHttpVerifier,
    val ingredientSearch: KitchenIngredientSearch,
    val databaseDispatcher: CoroutineDispatcher,
) {
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }
    override fun toString() = "KitchenHttpConfiguration(<redacted>)"
    companion object { const val MAX_REQUEST_BYTES = 65_536 }
}
