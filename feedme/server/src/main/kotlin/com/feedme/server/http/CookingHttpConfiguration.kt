package com.feedme.server.http

import com.feedme.core.ports.PortResult
import com.feedme.core.ports.SecretText
import com.feedme.server.cooking.CookingStore
import com.feedme.server.cooking.VerifiedCookingPrincipal
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher

/** Parsed credential metadata, not proof of token kind or a guest/device identity. */
class CookingHttpBearer internal constructor(val token: SecretText, val deviceSessionId: UUID?) {
    override fun toString() = "CookingHttpBearer(<redacted>)"
}

/**
 * Mandatory trusted verification of actual issuer/signature, audience, token use, expiry,
 * account device binding or bounded guest-session identity. Never accepts a client-asserted
 * guest device/origin. Transactional authority independently locks current eligibility/rights.
 */
fun interface CookingHttpVerifier {
    suspend fun verify(bearer: CookingHttpBearer): PortResult<VerifiedCookingPrincipal>
}

/** Explicit four-operation composition. No provider, reviewed catalog or default activation. */
class CookingHttpConfiguration(
    val environment: String,
    val store: CookingStore,
    val verifier: CookingHttpVerifier,
    val databaseDispatcher: CoroutineDispatcher,
) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(store.environment == environment)
    }
    override fun toString() = "CookingHttpConfiguration(<redacted>)"
    companion object { const val MAX_REQUEST_BYTES = 65_536 }
}
