package com.feedme.server.http

import com.feedme.core.ports.PortResult
import com.feedme.core.ports.SecretText
import com.feedme.server.planning.PlansStore
import com.feedme.server.planning.VerifiedPlanningPrincipal
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher

/** Secret input, not a verified principal or a hint that selects an accepting verifier. */
class PlanningHttpBearer internal constructor(val token: SecretText, val deviceSessionId: UUID?) {
    override fun toString() = "PlanningHttpBearer(<redacted>)"
}

/**
 * Mandatory trusted integration. Independently verify the ACTUAL token class: account issuer,
 * signature, audience/client, token_use, scope and expiry, or an authenticated bounded guest
 * session. Never classify a token from its spelling or the presence of a device header. Return
 * only its verified environment/subject/kind/device binding. Account and guest tokens are not
 * interchangeable. PlansStore's authority still locks/rechecks current eligibility, revocation,
 * rights and inputs in the database transaction. No provider or accepting implementation exists
 * here, and thrown exceptions must never carry a public diagnostic or imply failed mutation.
 */
fun interface PlanningHttpVerifier {
    suspend fun verify(bearer: PlanningHttpBearer): PortResult<VerifiedPlanningPrincipal>
}

/**
 * Explicit opt-in for four planning routes, not deployment enablement. The application owns the
 * actual PlansStore/adapters and dispatcher. Main supplies no configuration and remains closed.
 * The existing service wire profile accepts at most 64 KiB per planning request, depth 32;
 * schemas themselves are unchanged. Successful Plan bodies are bounded to 256 KiB by the store.
 */
class PlanningHttpConfiguration(
    val environment: String,
    val store: PlansStore,
    val verifier: PlanningHttpVerifier,
    val databaseDispatcher: CoroutineDispatcher,
) {
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }
    override fun toString() = "PlanningHttpConfiguration(<redacted>)"
    companion object { const val MAX_REQUEST_BYTES = 65_536 }
}
