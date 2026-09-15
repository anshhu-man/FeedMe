package com.feedme.server.http

import com.feedme.core.ports.PortResult
import com.feedme.core.ports.SecretText
import com.feedme.server.memory.SavedRecipeStore
import com.feedme.server.memory.VerifiedSavedRecipePrincipal
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher

/** Parsed credential metadata, not proof of token kind or a guest/device identity. */
class SavedRecipeHttpBearer internal constructor(val token: SecretText, val deviceSessionId: UUID?) {
    override fun toString() = "SavedRecipeHttpBearer(<redacted>)"
}

/**
 * Mandatory trusted verification of actual issuer/signature, audience, token use, expiry,
 * account device binding or bounded guest-session identity. Never accepts a client-asserted
 * guest device/origin. Transactional authority independently locks current eligibility/rights.
 */
fun interface SavedRecipeHttpVerifier {
    suspend fun verify(bearer: SavedRecipeHttpBearer): PortResult<VerifiedSavedRecipePrincipal>
}

/** Explicit six-operation basic cookbook composition. No provider, copy grant or default activation. */
class SavedRecipeHttpConfiguration(
    val environment: String,
    val store: SavedRecipeStore,
    val verifier: SavedRecipeHttpVerifier,
    val databaseDispatcher: CoroutineDispatcher,
) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(store.environment == environment)
    }
    override fun toString() = "SavedRecipeHttpConfiguration(<redacted>)"
    companion object { const val MAX_REQUEST_BYTES = 65_536 }
}
