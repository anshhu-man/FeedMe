package com.feedme.server.kitchen

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Owner/collection-bound keyset pagination, never an authorization capability. Explicit rotated keys only. */
class KitchenCursorCodec(private val currentKeyId: String, keys: Map<String, ByteArray>) {
    private val keys = keys.mapValues { it.value.copyOf() }
    init {
        require(currentKeyId in keys && keys.size in 1..8)
        require(keys.all { (id, key) -> id.matches(Regex("[a-z0-9_-]{1,32}")) && key.size == 32 })
    }
    internal fun encode(actor: VerifiedKitchenPrincipal, after: UUID, expires: Instant): String {
        val payload = "$currentKeyId.$after.${expires.epochSecond}"
        return "$payload.${mac(currentKeyId, actor, payload)}"
    }
    internal fun decode(actor: VerifiedKitchenPrincipal, cursor: String?, now: Instant): UUID? {
        if (cursor == null) return null
        fun invalid(): Nothing = throw KitchenFailure(KitchenFailureCode.CURSOR_INVALID)
        if (cursor.length > 2048) invalid()
        val p = cursor.split('.')
        if (p.size != 4 || !p[0].matches(Regex("[a-z0-9_-]{1,32}")) || p[0] !in keys ||
            !p[2].matches(Regex("[0-9]{1,18}")) || !p[3].matches(Regex("[A-Za-z0-9_-]{43}"))) invalid()
        val id = try { UUID.fromString(p[1]).also { if (it.toString() != p[1]) invalid() } }
            catch (_: IllegalArgumentException) { invalid() }
        val expires = p[2].toLongOrNull() ?: invalid()
        if (expires.toString() != p[2]) invalid()
        val expected = mac(p[0], actor, p.take(3).joinToString("."))
        if (!MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), p[3].toByteArray(Charsets.US_ASCII))) invalid()
        if (expires <= now.epochSecond) throw KitchenFailure(KitchenFailureCode.CURSOR_EXPIRED)
        return id
    }
    private fun mac(id: String, actor: VerifiedKitchenPrincipal, payload: String): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(keys.getValue(id), "HmacSHA256"))
        Base64.getUrlEncoder().withoutPadding().encodeToString(doFinal(
            "feedme.pantry-cursor.v1\u0000${actor.environment}\u0000${actor.kind.name}\u0000${actor.principalId}\u0000$payload".toByteArray(Charsets.UTF_8)))
    }
    override fun toString() = "KitchenCursorCodec(<redacted>)"
}
