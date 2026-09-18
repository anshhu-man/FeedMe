package com.feedme.server.guest

import com.feedme.server.contract.ContractCatalog
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.json.*

/** Explicit deployment limits, not launch approval. Capabilities are canonical operation IDs;
 * a runtime must advertise only operations it actually implements for guests. There are no
 * implicit defaults, account/social capabilities, consent declarations or accepting authority.
 * A changed field changes the stored policy binding; old sessions are not silently upgraded.
 */
internal class GuestSessionPolicy(
    val revision: String,
    val idleLifetimeSeconds: Long,
    val absoluteLifetimeSeconds: Long,
    val replayLifetimeSeconds: Long,
    val dailyPlanLimit: Int,
    val maxActivePerInstallation: Int,
    val maxIssuedPerInstallationPerDay: Int,
    capabilities: Set<String>,
) {
    private val values = capabilities.sorted()
    val capabilities: List<String> get() = values.toList()
    val bindingSha256: String
    init {
        require(revision.isNotBlank() && revision.length <= 256 && revision.none(Char::isISOControl)) { "Invalid guest policy" }
        try { revision.encodeToByteArray(throwOnInvalidSequence = true) }
        catch (_: CharacterCodingException) { throw IllegalArgumentException("Invalid guest policy") }
        require(idleLifetimeSeconds in 1..604800 && absoluteLifetimeSeconds in idleLifetimeSeconds..2592000 &&
            replayLifetimeSeconds in 1..idleLifetimeSeconds && dailyPlanLimit in 1..10000 &&
            maxActivePerInstallation in 1..16 && maxIssuedPerInstallationPerDay in 1..10000) { "Invalid guest policy" }
        val allowed = ContractCatalog.bundled().operations.filter { it.principal == "both" }.map { it.id }.toSet()
        require(values.size in 1..32 && values.all { it in allowed }) { "Invalid guest policy" }
        val exact = buildJsonArray {
            add("feedme.guest-session-policy.v1"); add(revision); add(idleLifetimeSeconds); add(absoluteLifetimeSeconds)
            add(replayLifetimeSeconds); add(dailyPlanLimit); add(maxActivePerInstallation); add(maxIssuedPerInstallationPerDay)
            add(JsonArray(values.map(::JsonPrimitive)))
        }.toString().encodeToByteArray()
        bindingSha256 = MessageDigest.getInstance("SHA-256").digest(exact).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    override fun toString() = "GuestSessionPolicy(<redacted>)"
}

/** Required real deployment admission/abuse/replay eligibility. These are DB-only checks and
 * locks in the supplied transaction: never commit, roll back, issue tokens or perform network
 * effects. Bootstrap nonce/key/digests alone do not implement this policy. Each callback is
 * called again after any work/waits; it must be safe to repeat without double-counting usage.
 * No accepting production implementation is supplied by the persistence component.
 */
internal interface GuestSessionAuthority {
    fun requireBootstrap(connection: Connection, request: GuestBootstrapRequest, replay: Boolean)
    fun requireCurrent(connection: Connection, guestSessionId: UUID, principalId: UUID, operation: String)
}

internal enum class GuestSessionFailureCode {
    INPUT_INVALID, ORIGINAL_MISMATCH, UNAUTHENTICATED, EXPIRED, POLICY_BLOCKED, LIMIT_REACHED,
    NOT_CONFIGURED, STORAGE_UNAVAILABLE,
}

internal class GuestSessionFailure(val code: GuestSessionFailureCode) :
    RuntimeException("Guest session unavailable: ${code.name}")

/** Contains a credential: intentionally not StoredReply, a data class, or loggable JSON. */
internal class GuestSessionReply(private val exact: String, val replayed: Boolean) {
    fun encodeForResponse(): ByteArray = exact.encodeToByteArray()
    override fun toString() = "GuestSessionReply(<redacted>)"
}
