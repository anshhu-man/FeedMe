package com.feedme.server.http

import com.feedme.core.ports.PortResult
import com.feedme.core.ports.SecretText
import com.feedme.server.media.MediaStore
import com.feedme.server.media.VerifiedMediaAccount
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher

/** Parsed credentials only. The required device header is not an authenticated identity. */
class MediaHttpBearer internal constructor(val token: SecretText, val deviceSessionId: UUID) {
    override fun toString() = "MediaHttpBearer(<redacted>)"
}

/** Mandatory issuer/signature/audience/expiry and current account/device verification.
 * Transactional authority separately locks current eligibility, terms and draft ownership.
 * Guests, asserted owner IDs and successful upload bytes never substitute for this verifier.
 */
fun interface MediaHttpVerifier {
    suspend fun verify(bearer: MediaHttpBearer): PortResult<VerifiedMediaAccount>
}

/** Four explicitly configured owned-media operations; no provider or default activation. */
class MediaHttpConfiguration(
    val environment: String,
    val store: MediaStore,
    val verifier: MediaHttpVerifier,
    val databaseDispatcher: CoroutineDispatcher,
) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(store.environment == environment)
    }
    override fun toString() = "MediaHttpConfiguration(<redacted>)"
    companion object { const val MAX_REQUEST_BYTES = 65_536 }
}
