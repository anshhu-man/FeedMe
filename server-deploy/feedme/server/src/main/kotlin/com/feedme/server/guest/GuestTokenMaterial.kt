package com.feedme.server.guest

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Opaque credential material only. Parsing is not authentication: no principal, lifetime,
 * capability, database state or replay receipt is established here. The future issuer owns
 * protected exact-response replay; this value must never enter generic StoredReply storage.
 * Scratch bytes are cleared, but immutable JVM Strings cannot be securely erased.
 */
internal class GuestTokenMaterial private constructor(private val encoded: String) {
    /** Explicit secret boundary for the future response/protected replay owner, never logs. */
    fun revealForResponse(): String = encoded

    /** Lowercase SHA-256 lookup material; not an authenticated identity or permission.
     * Frames are a four-byte unsigned big-endian length followed by the field bytes:
     * UTF-8 purpose, exact UTF-8 environment, then the decoded 32-byte random token.
     * Environment is neither trimmed nor case-normalized.
     */
    fun lookupSha256(environment: String): String = redacted {
        require(ENVIRONMENT.matches(environment))
        val tokenBytes = Base64.getUrlDecoder().decode(encoded)
        val purposeBytes = PURPOSE.toByteArray(Charsets.UTF_8)
        val environmentBytes = environment.toByteArray(Charsets.UTF_8)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            for (field in listOf(purposeBytes, environmentBytes, tokenBytes)) {
                digest.update(ByteBuffer.allocate(4).putInt(field.size).array())
                digest.update(field)
            }
            val hash = digest.digest()
            try { hash.joinToString("") { "%02x".format(it) } }
            finally { hash.fill(0) }
        } finally {
            tokenBytes.fill(0)
            purposeBytes.fill(0)
            environmentBytes.fill(0)
        }
    }

    override fun toString() = "GuestTokenMaterial(<redacted>)"

    companion object {
        private const val PURPOSE = "feedme.guest-token-lookup.v1"
        private const val INVALID = "Guest token material is invalid"
        private const val TOKEN_BYTES = 32
        private val ENVIRONMENT = Regex("[a-z][a-z0-9-]{0,39}")
        private val TOKEN = Regex("[A-Za-z0-9_-]{43}")

        /** The production entry point uses the platform's standard cryptographic random source. */
        fun issue(): GuestTokenMaterial = redacted { issue(SecureRandom()) }

        /** Internal entropy seam for deterministic tests, never a verification callback. */
        fun issue(random: SecureRandom): GuestTokenMaterial = redacted {
            val bytes = ByteArray(TOKEN_BYTES)
            try {
                random.nextBytes(bytes)
                GuestTokenMaterial(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
            } finally { bytes.fill(0) }
        }

        fun parse(token: String): GuestTokenMaterial = redacted {
            require(TOKEN.matches(token))
            val decoded = Base64.getUrlDecoder().decode(token)
            try {
                require(decoded.size == TOKEN_BYTES)
                // A permissive decoder otherwise accepts nonzero unused final bits.
                require(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == token)
                GuestTokenMaterial(token)
            } finally { decoded.fill(0) }
        }

        private inline fun <T> redacted(block: () -> T): T = try { block() }
            catch (failure: Exception) {
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                throw IllegalArgumentException(INVALID)
            }
    }
}
