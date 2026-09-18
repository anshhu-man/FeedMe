package com.feedme.server.guest

import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.*

/** Validated public bootstrap input, NOT an authenticated installation or guest principal.
 * Only digests, the explicit environment/key and optional locale are retained. A nonce is
 * client supplied and may have low entropy: neither its value nor its hash proves identity,
 * ownership, eligibility or permission to replay a token-bearing result.
 *
 * The future dedicated bootstrap store must bind BOTH installationSha256 and requestSha256,
 * atomically persist its own command/session state, and separately protect secret replay.
 * Existing principal-scoped DurableCommands/StoredReply are deliberately not used here.
 */
internal class GuestBootstrapRequest private constructor(
    val environment: String,
    val commandKey: UUID,
    val installationSha256: String,
    val requestSha256: String,
    val locale: String?,
) {
    override fun toString() = "GuestBootstrapRequest(<redacted>)"

    companion object {
        /** An explicit ingress byte bound, not a new canonical string-length constraint. */
        const val MAX_BODY_BYTES = 4096
        private val validator by lazy { ContractBodyValidator.bundled() }

        fun parse(environment: String, commandKey: UUID, body: ByteArray): GuestBootstrapRequest {
            require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) { "Invalid guest bootstrap input" }
            require(body.size in 1..MAX_BODY_BYTES) { "Invalid guest bootstrap input" }
            val snapshot = body.copyOf()
            return try { parseSnapshot(environment, commandKey, snapshot) }
            finally { snapshot.fill(0) }
        }

        private fun parseSnapshot(environment: String, commandKey: UUID, body: ByteArray): GuestBootstrapRequest {
            // Validation enforces canonical code-point lengths, strict UTF-8, duplicate-field
            // rejection and exact schema. No token, principal or default locale is inferred.
            require(validator.validateRequest("createGuestSession", body, "application/json") ==
                BodyValidationResult.Valid) { "Invalid guest bootstrap input" }
            val root = try { Json.parseToJsonElement(body.decodeToString(throwOnInvalidSequence = true)).jsonObject }
                catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid guest bootstrap input") }
            val nonce = root.getValue("installationNonce").jsonPrimitive.content
            val locale = root["locale"]?.jsonPrimitive?.content
            val normalized = buildJsonObject {
                put("installationNonce", nonce)
                locale?.let { put("locale", it) }
            }
            return GuestBootstrapRequest(environment, commandKey,
                digest("feedme.guest-installation.v1", environment, JsonPrimitive(nonce)),
                digest("feedme.guest-bootstrap-request.v1", environment, normalized), locale)
        }

        private fun digest(purpose: String, environment: String, value: JsonElement): String {
            // JSON-array framing prevents concatenation collisions and keeps absent/empty
            // locale distinct. Object member order and equivalent escapes are not identity.
            val bytes = buildJsonArray { add(purpose); add(environment); add(value) }
                .toString().encodeToByteArray(throwOnInvalidSequence = true)
            return try {
                val hashed = MessageDigest.getInstance("SHA-256").digest(bytes)
                try { hashed.joinToString("") { "%02x".format(it.toInt() and 255) } }
                finally { hashed.fill(0) }
            } finally { bytes.fill(0) }
        }
    }
}
