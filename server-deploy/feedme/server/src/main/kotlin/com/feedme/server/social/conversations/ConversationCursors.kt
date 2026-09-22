package com.feedme.server.social.conversations

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Pagination position only. Every page independently checks the actual account and peer. */
class ConversationCursors(private val currentKeyId: String, keys: Map<String, ByteArray>) {
    private val keys = keys.mapValues { it.value.copyOf() }
    init { require(keys.size in 1..8 && currentKeyId in keys && keys.all { (id, key) -> id.matches(Regex("[a-z0-9_-]{1,32}")) && key.size == 32 }) }
    internal class Position(val after: String, val expires: Instant)
    internal fun encode(environment: String, owner: UUID, route: String, limit: Int, after: String, expires: Instant): String {
        val payload = "$currentKeyId.$after.${expires.epochSecond}.${expires.nano}"
        return "$payload.${mac(currentKeyId, environment, owner, route, limit, payload)}"
    }
    internal fun decode(token: String, environment: String, owner: UUID, route: String, limit: Int, now: Instant): Position {
        try {
            if (token.length !in 1..2048) invalid()
            val parts = token.split('.')
            if (parts.size != 5 || parts[0] !in keys || !parts[4].matches(Regex("[A-Za-z0-9_-]{43}"))) invalid()
            val after = parts[1]
            if (route == "threads") { if (UUID.fromString(after).toString() != after) invalid() }
            else if (!after.matches(Regex("[1-9][0-9]{0,18}")) || after.toLong() <= 0) invalid()
            val seconds = parts[2].toLong(); val nanos = parts[3].toInt()
            if (seconds.toString() != parts[2] || nanos.toString() != parts[3] || nanos !in 0..999999999) invalid()
            val expected = mac(parts[0], environment, owner, route, limit, parts.take(4).joinToString("."))
            if (!MessageDigest.isEqual(expected.toByteArray(), parts[4].toByteArray())) invalid()
            val expires = Instant.ofEpochSecond(seconds, nanos.toLong())
            if (!now.isBefore(expires)) throw ConversationFailure(ConversationFailureCode.CURSOR_EXPIRED)
            return Position(after, expires)
        } catch (failure: ConversationFailure) { throw failure }
        catch (_: Exception) { invalid() }
    }
    private fun mac(keyId: String, environment: String, owner: UUID, route: String, limit: Int, payload: String): String =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(keys.getValue(keyId), "HmacSHA256"))
            Base64.getUrlEncoder().withoutPadding().encodeToString(doFinal(
                "feedme.conversation-cursor.v1\u0000$environment\u0000$owner\u0000$route\u0000$limit\u0000$payload".toByteArray()))
        }
    override fun toString() = "ConversationCursors(<redacted>)"
    private fun invalid(): Nothing = throw ConversationFailure(ConversationFailureCode.INPUT_INVALID)
}
