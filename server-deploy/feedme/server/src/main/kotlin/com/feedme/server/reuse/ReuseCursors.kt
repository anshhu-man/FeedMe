package com.feedme.server.reuse

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Exact owner/request/limit continuation, never authority to access a source recipe. */
internal class ReuseCursors(private val currentKeyId: String, keys: Map<String, ByteArray>) {
    private val keys = keys.mapValues { it.value.copyOf() }
    init { require(currentKeyId in this.keys && this.keys.size in 1..8)
        require(this.keys.all { (id, key) -> id.matches(Regex("[a-z0-9_-]{1,32}")) && key.size == 32 }) }
    fun encode(environment: String, principal: UUID, requestHash: String, proposal: UUID, offset: Int, limit: Int, expiry: Instant): String {
        require(offset > 0 && limit in 1..50 && expiry.epochSecond >= 0)
        val payload = "$currentKeyId.$proposal.$offset.$limit.${expiry.epochSecond}.${expiry.nano}"
        return "$payload.${mac(currentKeyId, environment, principal, requestHash, payload)}"
    }
    fun decode(environment: String, principal: UUID, requestHash: String, value: String, limit: Int, now: Instant): Position {
        if (value.length !in 1..2048 || limit !in 1..50) invalid()
        val p = value.split('.'); if (p.size != 7 || p[0] !in keys || !p[6].matches(Regex("[A-Za-z0-9_-]{43}"))) invalid()
        fun number(index: Int): Long { if (!p[index].matches(Regex("0|[1-9][0-9]{0,18}"))) invalid(); return p[index].toLongOrNull() ?: invalid() }
        val id = try { UUID.fromString(p[1]).also { if (it.toString() != p[1]) invalid() } } catch (_: IllegalArgumentException) { invalid() }
        val offset = number(2); val nanos = number(5)
        if (offset !in 1..1000 || number(3) != limit.toLong() || nanos > 999999999) invalid()
        val expected = mac(p[0], environment, principal, requestHash, p.take(6).joinToString("."))
        if (!MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), p[6].toByteArray(Charsets.US_ASCII))) invalid()
        val expiry = try { Instant.ofEpochSecond(number(4), nanos) } catch (_: Exception) { invalid() }
        if (!now.isBefore(expiry)) reuseFail(ReuseFailureCode.CURSOR_EXPIRED)
        return Position(id, offset.toInt(), expiry)
    }
    private fun mac(key: String, environment: String, principal: UUID, hash: String, payload: String): String {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")) && hash.matches(Regex("[0-9a-f]{64}")))
        return Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(keys.getValue(key), "HmacSHA256"))
            Base64.getUrlEncoder().withoutPadding().encodeToString(doFinal("feedme.reuse.options.v1\u0000$environment\u0000account\u0000$principal\u0000$hash\u0000$payload".toByteArray(Charsets.UTF_8))) }
    }
    internal class Position(val proposal: UUID, val offset: Int, val expiry: Instant) { override fun toString() = "ReuseCursorPosition(<redacted>)" }
    private fun invalid(): Nothing = reuseFail(ReuseFailureCode.CURSOR_INVALID)
    override fun toString() = "ReuseCursors(<redacted>)"
}
