package com.feedme.server.memory

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Page continuation only. Current real principal authority remains mandatory. */
internal class MemoryCursors(private val currentKeyId: String, keys: Map<String, ByteArray>) {
    private val keys = keys.mapValues { it.value.copyOf() }
    init {
        require(currentKeyId in this.keys && this.keys.size in 1..8)
        require(this.keys.all { (id, bytes) -> id.matches(Regex("[a-z0-9_-]{1,32}")) && bytes.size == 32 })
    }
    fun encode(actor: VerifiedMemoryPrincipal, revision: Long, limit: Int, after: UUID, expires: Instant): String {
        require(revision >= 0 && limit in 1..50 && expires.epochSecond >= 0)
        val payload = "$currentKeyId.$revision.$limit.$after.${expires.epochSecond}.${expires.nano}"
        return "$payload.${mac(currentKeyId, actor, payload)}"
    }
    fun decode(actor: VerifiedMemoryPrincipal, revision: Long, limit: Int, cursor: String?, now: Instant): Position? {
        if (cursor == null) return null
        if (revision < 0 || limit !in 1..50 || cursor.length !in 1..2048) invalid()
        val p = cursor.split('.')
        if (p.size != 7 || p[0] !in keys || !p[6].matches(Regex("[A-Za-z0-9_-]{43}"))) invalid()
        fun number(index: Int): Long {
            if (!p[index].matches(Regex("0|[1-9][0-9]{0,18}"))) invalid()
            return p[index].toLongOrNull() ?: invalid()
        }
        if (number(1) != revision || number(2) != limit.toLong()) invalid()
        val after = try { UUID.fromString(p[3]).also { if (it.toString() != p[3]) invalid() } }
            catch (_: IllegalArgumentException) { invalid() }
        val seconds = number(4); val nanos = number(5)
        if (nanos > 999_999_999) invalid()
        val expected = mac(p[0], actor, p.take(6).joinToString("."))
        if (!MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), p[6].toByteArray(Charsets.US_ASCII))) invalid()
        val expires = try { Instant.ofEpochSecond(seconds, nanos) } catch (_: Exception) { invalid() }
        if (!now.isBefore(expires)) throw MemoryFailure(MemoryFailureCode.CURSOR_EXPIRED)
        return Position(after, expires)
    }
    private fun mac(key: String, actor: VerifiedMemoryPrincipal, payload: String): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(keys.getValue(key), "HmacSHA256"))
        Base64.getUrlEncoder().withoutPadding().encodeToString(doFinal(
            "feedme.memory.list.v1\u0000${actor.environment}\u0000${actor.kind.name}\u0000${actor.principalId}\u0000$payload".toByteArray(Charsets.UTF_8)))
    }
    internal class Position(val after: UUID, val expires: Instant) {
        override fun toString() = "MemoryCursorPosition(<redacted>)"
    }
    private fun invalid(): Nothing = throw MemoryFailure(MemoryFailureCode.CURSOR_INVALID)
    override fun toString() = "MemoryCursors(<redacted>)"
}
