package com.feedme.server.identity

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Authenticated pagination positions, never recipient or target authorization. */
class NotificationInboxCursors(private val currentKeyId: String, keys: Map<String, ByteArray>) {
    private val keys = keys.mapValues { it.value.copyOf() }
    init { require(keys.size in 1..8 && currentKeyId in keys && keys.all { (id, key) -> id.matches(Regex("[a-z0-9_-]{1,32}")) && key.size == 32 }) }
    internal class Position(val through: Instant, val after: Instant, val id: UUID, val expires: Instant)
    internal fun encode(environment: String, owner: UUID, limit: Int, p: Position): String {
        val body = listOf(currentKeyId, p.through.epochSecond, p.through.nano, p.after.epochSecond, p.after.nano,
            p.id, p.expires.epochSecond, p.expires.nano).joinToString(".")
        return "$body.${mac(currentKeyId, environment, owner, limit, body)}"
    }
    internal fun decode(value: String, environment: String, owner: UUID, limit: Int, now: Instant): Position {
        try {
            if (value.length !in 1..2048) invalid()
            val parts = value.split('.')
            if (parts.size != 9 || parts[0] !in keys || !parts[8].matches(Regex("[A-Za-z0-9_-]{43}"))) invalid()
            val body = parts.take(8).joinToString(".")
            if (!MessageDigest.isEqual(mac(parts[0], environment, owner, limit, body).toByteArray(), parts[8].toByteArray())) invalid()
            fun time(at: Int): Instant {
                val seconds = parts[at].toLong(); val nano = parts[at + 1].toInt()
                if (seconds.toString() != parts[at] || nano.toString() != parts[at + 1] || nano !in 0..999999999) invalid()
                return Instant.ofEpochSecond(seconds, nano.toLong())
            }
            val id = UUID.fromString(parts[5]); if (id.toString() != parts[5]) invalid()
            val position = Position(time(1), time(3), id, time(6))
            if (position.after > position.through || position.through > now || position.expires <= position.through) invalid()
            if (now >= position.expires) throw NotificationInboxFailure(NotificationInboxFailureCode.CURSOR_EXPIRED)
            return position
        } catch (failure: NotificationInboxFailure) { throw failure }
        catch (_: Exception) { invalid() }
    }
    private fun mac(id: String, environment: String, owner: UUID, limit: Int, body: String) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(keys.getValue(id), "HmacSHA256"))
        Base64.getUrlEncoder().withoutPadding().encodeToString(doFinal(
            "feedme.notification-inbox-cursor.v1\u0000$environment\u0000$owner\u0000$limit\u0000$body".toByteArray(Charsets.UTF_8)))
    }
    private fun invalid(): Nothing = throw NotificationInboxFailure(NotificationInboxFailureCode.INPUT_INVALID)
    override fun toString() = "NotificationInboxCursors(<redacted>)"
}
