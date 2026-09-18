package com.feedme.server.social

import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Explicit server secret configuration; no generated/default key or invented production host.
 * Retain old key IDs while their invitations/cursors remain valid. Invitation tokens are derived
 * from a random invitation UUID plus this keyed MAC, so same-command replay can return the same
 * capability without storing the bearer in idempotency JSON, outbox, logs or invitation rows.
 */
class CircleCapabilities(currentKeyId: String, keys: Map<String, ByteArray>, val invitationEndpoint: URI) {
    val currentKeyId = currentKeyId
    private val keys = keys.mapValues { (_, value) -> value.copyOf() }
    init {
        require(currentKeyId in keys && keys.isNotEmpty() && keys.size <= 8)
        require(keys.all { (id, key) -> id.matches(Regex("[a-z0-9_-]{1,32}")) && key.size == 32 })
        require(invitationEndpoint.scheme == "https" && invitationEndpoint.host != null &&
            invitationEndpoint.userInfo == null && invitationEndpoint.rawQuery == null && invitationEndpoint.rawFragment == null)
    }
    internal fun invite(environment: String, id: UUID, keyId: String): String =
        "$keyId.$id.${mac(keyId, "feedme.circle-invite.v1\u0000$environment\u0000$id")}"
    internal fun url(environment: String, id: UUID, keyId: String) = "$invitationEndpoint?token=${invite(environment, id, keyId)}"
    internal fun tokenHash(token: String): String {
        if (token.length !in 32..512 || !token.matches(Regex("[A-Za-z0-9_.-]+"))) throw SocialFailure(SocialFailureCode.INPUT_INVALID)
        return MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    internal fun cursor(actor: VerifiedSocialAccount, kind: String, circle: UUID?, last: UUID): String =
        "$currentKeyId.$last.${mac(currentKeyId, cursorMaterial(actor, kind, circle, last))}"
    internal fun parseCursor(actor: VerifiedSocialAccount, kind: String, circle: UUID?, cursor: String?): UUID? {
        if (cursor == null) return null
        if (cursor.length > 2048) throw SocialFailure(SocialFailureCode.INPUT_INVALID)
        val parts = cursor.split('.')
        if (parts.size != 3 || !parts[0].matches(Regex("[a-z0-9_-]{1,32}"))) throw SocialFailure(SocialFailureCode.INPUT_INVALID)
        val id = try { UUID.fromString(parts[1]).also { require(it.toString() == parts[1]) } }
            catch (_: IllegalArgumentException) { throw SocialFailure(SocialFailureCode.INPUT_INVALID) }
        val expected = try { mac(parts[0], cursorMaterial(actor, kind, circle, id)) }
            catch (_: SocialFailure) { throw SocialFailure(SocialFailureCode.INPUT_INVALID) }
        if (!MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), parts[2].toByteArray(Charsets.US_ASCII)))
            throw SocialFailure(SocialFailureCode.INPUT_INVALID)
        return id
    }
    private fun cursorMaterial(actor: VerifiedSocialAccount, kind: String, circle: UUID?, last: UUID) =
        "feedme.circle-cursor.v1\u0000${actor.environment}\u0000${actor.accountId}\u0000$kind\u0000${circle ?: ""}\u0000$last"
    private fun mac(id: String, text: String): String {
        val key = keys[id] ?: throw SocialFailure(SocialFailureCode.NOT_CONFIGURED)
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256")); Base64.getUrlEncoder().withoutPadding().encodeToString(doFinal(text.toByteArray(Charsets.UTF_8)))
        }
    }
    override fun toString() = "CircleCapabilities(<redacted>)"
}
