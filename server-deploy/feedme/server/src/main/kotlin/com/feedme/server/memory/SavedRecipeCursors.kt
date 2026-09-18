package com.feedme.server.memory

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Purpose/owner/filter/version-bound pagination, not a bearer authorization grant. Explicit keys only. */
class SavedRecipeCursors(private val currentKeyId: String, keys: Map<String, ByteArray>) {
    private val keys = keys.mapValues { it.value.copyOf() }
    init {
        require(currentKeyId in keys && keys.size in 1..8)
        require(keys.all { (id, key) -> id.matches(Regex("[a-z0-9_-]{1,32}")) && key.size == 32 })
    }
    internal fun encode(actor: VerifiedSavedRecipePrincipal, purpose: String, scope: String, revision: Long,
        after: UUID, expires: Instant): String {
        val payload = "$currentKeyId.$revision.$after.${expires.epochSecond}"
        return "$payload.${mac(currentKeyId, actor, purpose, scope, payload)}"
    }
    internal fun decode(actor: VerifiedSavedRecipePrincipal, purpose: String, scope: String, revision: Long,
        cursor: String?, now: Instant): UUID? {
        if (cursor == null) return null
        fun invalid(): Nothing = throw SavedRecipeFailure(SavedRecipeFailureCode.CURSOR_INVALID)
        if (cursor.length > 2048) invalid()
        val p = cursor.split('.')
        if (p.size != 5 || p[0] !in keys || !p[1].matches(Regex("[0-9]{1,19}")) ||
            !p[3].matches(Regex("[0-9]{1,18}")) || !p[4].matches(Regex("[A-Za-z0-9_-]{43}"))) invalid()
        val r = p[1].toLongOrNull() ?: invalid(); if (r.toString() != p[1] || r != revision) invalid()
        val id = try { UUID.fromString(p[2]).also { if (it.toString() != p[2]) invalid() } } catch (_: IllegalArgumentException) { invalid() }
        val expires = p[3].toLongOrNull() ?: invalid(); if (expires.toString() != p[3]) invalid()
        val expected = mac(p[0], actor, purpose, scope, p.take(4).joinToString("."))
        if (!MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), p[4].toByteArray(Charsets.US_ASCII))) invalid()
        if (expires <= now.epochSecond) throw SavedRecipeFailure(SavedRecipeFailureCode.CURSOR_EXPIRED)
        return id
    }
    private fun mac(key: String, actor: VerifiedSavedRecipePrincipal, purpose: String, scope: String, payload: String): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(keys.getValue(key), "HmacSHA256"))
        Base64.getUrlEncoder().withoutPadding().encodeToString(doFinal(
            "feedme.saved-cursor.v1\u0000${actor.environment}\u0000${actor.kind.name}\u0000${actor.principalId}\u0000$purpose\u0000$scope\u0000$payload".toByteArray(Charsets.UTF_8)))
    }
    override fun toString() = "SavedRecipeCursors(<redacted>)"
}
