package com.feedme.server.http

import com.feedme.core.ports.PortResult
import com.feedme.core.ports.SecretText
import com.feedme.server.social.CirclesStore
import com.feedme.server.social.VerifiedSocialAccount
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher

/** Unverified secret input. Device/header spelling is never proof of an account token. */
class SocialHttpBearer internal constructor(val token: SecretText, val deviceSessionId: UUID) {
    override fun toString() = "SocialHttpBearer(<redacted>)"
}

/**
 * Mandatory trusted integration, with no accepting default or provider implementation.
 * Independently verify an actual ACCOUNT access token (issuer/signature, audience/client,
 * token_use, required scope and expiry), its subject and registered device binding. Guest,
 * refresh, invitation and other token classes must never be promoted to an account principal.
 * CirclesStore still locks/rechecks current account/device/role/block/profile policy on every
 * transaction and cached command replay. Do not log the input or throw public diagnostics.
 */
fun interface SocialHttpVerifier {
    suspend fun verify(bearer: SocialHttpBearer): PortResult<VerifiedSocialAccount>
}

/**
 * Explicit opt-in for fourteen circle/invitation operations only, not social release enablement.
 * The caller owns the real store, mandatory identity/block adapters, capability keys and dispatcher.
 * Public preview is a minimal invite-token capability read, never acceptance or account access.
 * Well-formed optional auth/device headers on preview are ignored, never verified or trusted;
 * duplicate/malformed control metadata is rejected by the same bounded ingress profile.
 * Main provides no configuration. The HTTP wire profile is 64 KiB/depth 32 per JSON request;
 * responses are bounded to 256 KiB. Canonical schemas and launch policy are not widened here.
 */
class SocialHttpConfiguration(
    val environment: String,
    val store: CirclesStore,
    val verifier: SocialHttpVerifier,
    val databaseDispatcher: CoroutineDispatcher,
) {
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }
    override fun toString() = "SocialHttpConfiguration(<redacted>)"
    companion object { const val MAX_REQUEST_BYTES = 65_536 }
}
