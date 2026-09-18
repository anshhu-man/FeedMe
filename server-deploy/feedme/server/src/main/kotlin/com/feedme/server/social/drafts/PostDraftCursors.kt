package com.feedme.server.social.drafts

import com.feedme.server.social.VerifiedSocialAccount
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Owner/head/page-limit/expiry-bound continuation. Not an authorization capability. Explicit keys only. */
class PostDraftCursors(private val currentKeyId: String, keys: Map<String, ByteArray>) {
    private val keys = keys.mapValues { it.value.copyOf() }
    init {
        require(currentKeyId in keys && keys.size in 1..8)
        require(keys.all { (id, key) -> id.matches(Regex("[a-z0-9_-]{1,32}")) && key.size == 32 })
    }
    internal fun encode(actor: VerifiedSocialAccount, head: Long, limit: Int, after: UUID, expires: Instant): String {
        val payload = "$currentKeyId.$head.$after.${expires.epochSecond}"
        return "$payload.${mac(currentKeyId, actor, limit, payload)}"
    }
    internal fun decode(actor: VerifiedSocialAccount, head: Long, limit: Int, cursor: String?, now: Instant): UUID? {
        if (cursor == null) return null
        fun invalid(): Nothing = throw PostDraftFailure(PostDraftFailureCode.CURSOR_INVALID)
        if (cursor.length !in 1..2048) invalid()
        val p = cursor.split('.')
        if (p.size != 5 || p[0] !in keys || !p[1].matches(Regex("[0-9]{1,19}")) ||
            !p[3].matches(Regex("[0-9]{1,18}")) || !p[4].matches(Regex("[A-Za-z0-9_-]{43}"))) invalid()
        val r = p[1].toLongOrNull() ?: invalid()
        if (r.toString() != p[1] || r != head) invalid()
        val id = try { UUID.fromString(p[2]).also { if (it.toString() != p[2]) invalid() } } catch (_: IllegalArgumentException) { invalid() }
        val expires = p[3].toLongOrNull() ?: invalid()
        if (expires.toString() != p[3]) invalid()
        if (!MessageDigest.isEqual(mac(p[0], actor, limit, p.take(4).joinToString(".")).toByteArray(), p[4].toByteArray())) invalid()
        if (expires <= now.epochSecond) throw PostDraftFailure(PostDraftFailureCode.CURSOR_EXPIRED)
        return id
    }
    private fun mac(key: String, actor: VerifiedSocialAccount, limit: Int, payload: String) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(keys.getValue(key), "HmacSHA256"))
        Base64.getUrlEncoder().withoutPadding().encodeToString(doFinal(
            "feedme.post-draft-cursor.v1\u0000${actor.environment}\u0000${actor.accountId}\u0000$limit\u0000$payload".toByteArray()))
    }
    override fun toString() = "PostDraftCursors(<redacted>)"
}
