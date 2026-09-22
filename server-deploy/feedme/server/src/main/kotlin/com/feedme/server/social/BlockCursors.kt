package com.feedme.server.social

import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class BlockCursorFailureReason { INVALID, EXPIRED }

/** Safe for the caller to map to its canonical Problem; no token, key or private context. */
class BlockCursorFailure(val reason: BlockCursorFailureReason) : RuntimeException("Block cursor unavailable")

/** A pagination position only, never evidence of ownership or permission to read a block. */
data class BlockCursorPosition(val afterId: UUID, val expiresAt: Instant) {
    override fun toString() = "BlockCursorPosition(<redacted>)"
}

/** Account/environment/page-size-bound listBlocks continuation with explicit rotating keys.
 * Every actual read must independently authorize the current account/device and block rows.
 * The exact expiry is retained, including subsecond precision; no time or key is invented here. */
class BlockCursors(private val currentKeyId: String, keys: Map<String, ByteArray>) {
    private val keys = run {
        valid(keys.size in 1..8 && currentKeyId in keys)
        valid(keys.all { (id, key) -> KEY_ID.matches(id) && key.size == 32 })
        keys.mapValues { (_, value) -> value.copyOf() }
    }

    fun encode(environment: String, ownerId: UUID, limit: Int, afterId: UUID, expiresAt: Instant): String {
        context(environment, limit)
        val payload = "$currentKeyId.$afterId.${expiresAt.epochSecond}.${expiresAt.nano}"
        return "$payload.${mac(currentKeyId, environment, ownerId, limit, payload)}"
    }

    fun decode(token: String, environment: String, ownerId: UUID, limit: Int, now: Instant): BlockCursorPosition {
        context(environment, limit)
        valid(token.length in 1..2048)
        val parts = token.split('.')
        valid(parts.size == 5)
        valid(parts[0] in keys && KEY_ID.matches(parts[0]) && MAC.matches(parts[4]))
        val after = try {
            UUID.fromString(parts[1]).also { valid(it.toString() == parts[1]) }
        } catch (_: IllegalArgumentException) { invalid() }
        valid(SECONDS.matches(parts[2]) && NANOS.matches(parts[3]))
        val seconds = parts[2].toLongOrNull() ?: invalid()
        val nanos = parts[3].toIntOrNull() ?: invalid()
        valid(seconds.toString() == parts[2] && nanos.toString() == parts[3] && nanos in 0..999_999_999)
        val expires = try { Instant.ofEpochSecond(seconds, nanos.toLong()) }
        catch (_: DateTimeException) { invalid() }
        val expected = mac(parts[0], environment, ownerId, limit, parts.take(4).joinToString("."))
        valid(MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), parts[4].toByteArray(Charsets.US_ASCII)))
        if (!now.isBefore(expires)) throw BlockCursorFailure(BlockCursorFailureReason.EXPIRED)
        return BlockCursorPosition(after, expires)
    }

    private fun mac(keyId: String, environment: String, ownerId: UUID, limit: Int, payload: String): String = try {
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(keys.getValue(keyId), "HmacSHA256"))
            Base64.getUrlEncoder().withoutPadding().encodeToString(doFinal(
                "feedme.block-cursor.v1\u0000$environment\u0000$ownerId\u0000$limit\u0000$payload".toByteArray(Charsets.UTF_8)))
        }
    } catch (_: Exception) { invalid() }

    override fun toString() = "BlockCursors(<redacted>)"

    companion object {
        private val KEY_ID = Regex("[a-z0-9_-]{1,32}")
        private val ENVIRONMENT = Regex("[a-z][a-z0-9-]{0,39}")
        private val MAC = Regex("[A-Za-z0-9_-]{43}")
        private val SECONDS = Regex("-?(?:0|[1-9][0-9]{0,18})")
        private val NANOS = Regex("0|[1-9][0-9]{0,8}")
        private fun context(environment: String, limit: Int) {
            valid(ENVIRONMENT.matches(environment) && limit in 1..50)
        }
        private fun valid(value: Boolean) { if (!value) invalid() }
        private fun invalid(): Nothing = throw BlockCursorFailure(BlockCursorFailureReason.INVALID)
    }
}
