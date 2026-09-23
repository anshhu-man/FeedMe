package com.feedme.server.staff

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Positions in the bounded observed queue, not a reusable moderation grant. */
internal class StaffModerationCursors(private val currentKeyId: String, keys: Map<String, ByteArray>) {
    private val keys = keys.mapValues { it.value.copyOf() }
    init { require(keys.size in 1..8 && currentKeyId in keys && keys.all { (id, key) ->
        id.matches(Regex("[a-z0-9_-]{1,32}")) && key.size == 32 }) }
    internal class Position(val through: Instant, val priority: Int, val after: Instant, val id: UUID, val expires: Instant)
    internal fun encode(environment: String, actor: UUID, limit: Int, p: Position): String {
        val body = listOf(currentKeyId, p.through.epochSecond, p.through.nano, p.priority, p.after.epochSecond,
            p.after.nano, p.id, p.expires.epochSecond, p.expires.nano).joinToString(".")
        return "$body.${mac(currentKeyId, environment, actor, limit, body)}"
    }
    internal fun decode(value: String, environment: String, actor: UUID, limit: Int, now: Instant): Position {
        try {
            if (value.length !in 1..2048) invalid()
            val parts = value.split('.')
            if (parts.size != 10 || parts[0] !in keys || !parts[9].matches(Regex("[A-Za-z0-9_-]{43}"))) invalid()
            val body = parts.take(9).joinToString(".")
            if (!MessageDigest.isEqual(mac(parts[0], environment, actor, limit, body).toByteArray(), parts[9].toByteArray())) invalid()
            fun time(at: Int): Instant {
                val seconds = parts[at].toLong(); val nano = parts[at + 1].toInt()
                if (seconds.toString() != parts[at] || nano.toString() != parts[at + 1] || nano !in 0..999999999) invalid()
                return Instant.ofEpochSecond(seconds, nano.toLong())
            }
            val priority = parts[3].toInt()
            val id = UUID.fromString(parts[6])
            if (priority !in 0..2 || priority.toString() != parts[3] || id.toString() != parts[6]) invalid()
            val p = Position(time(1), priority, time(4), id, time(7))
            if (p.after > p.through || p.through > now || p.expires <= p.through) invalid()
            if (now >= p.expires) throw StaffModerationFailure(StaffModerationFailureCode.CURSOR_EXPIRED)
            return p
        } catch (failure: StaffModerationFailure) { throw failure }
        catch (_: Exception) { invalid() }
    }
    /** Audit positions deliberately use a different MAC domain from moderation queue
     * positions. A cursor copied between the two role-scoped endpoints is invalid even
     * when actor and page size happen to match. `priority` is the audit source rank. */
    internal fun encodeAudit(environment: String, actor: UUID, limit: Int, p: Position): String {
        val body = listOf(currentKeyId, p.through.epochSecond, p.through.nano, p.priority, p.after.epochSecond,
            p.after.nano, p.id, p.expires.epochSecond, p.expires.nano).joinToString(".")
        return "$body.${auditMac(currentKeyId, environment, actor, limit, body)}"
    }
    internal fun decodeAudit(value: String, environment: String, actor: UUID, limit: Int, now: Instant): Position {
        try {
            if (value.length !in 1..2048) invalid()
            val parts = value.split('.')
            if (parts.size != 10 || parts[0] !in keys || !parts[9].matches(Regex("[A-Za-z0-9_-]{43}"))) invalid()
            val body = parts.take(9).joinToString(".")
            if (!MessageDigest.isEqual(auditMac(parts[0], environment, actor, limit, body).toByteArray(), parts[9].toByteArray())) invalid()
            fun time(at: Int): Instant {
                val seconds = parts[at].toLong(); val nano = parts[at + 1].toInt()
                if (seconds.toString() != parts[at] || nano.toString() != parts[at + 1] || nano !in 0..999999999) invalid()
                return Instant.ofEpochSecond(seconds, nano.toLong())
            }
            val source = parts[3].toInt()
            val id = UUID.fromString(parts[6])
            if (source !in 0..1 || source.toString() != parts[3] || id.toString() != parts[6]) invalid()
            val p = Position(time(1), source, time(4), id, time(7))
            if (p.after > p.through || p.through > now || p.expires <= p.through) invalid()
            if (now >= p.expires) throw StaffModerationFailure(StaffModerationFailureCode.CURSOR_EXPIRED)
            return p
        } catch (failure: StaffModerationFailure) { throw failure }
        catch (_: Exception) { invalid() }
    }
    internal class FlagPosition(val through: Instant, val after: String, val expires: Instant)
    internal fun encodeFlags(environment: String, actor: UUID, limit: Int, p: FlagPosition): String {
        require(p.after.matches(Regex("[a-z][a-z0-9_.-]{1,100}")))
        val encodedKey = Base64.getUrlEncoder().withoutPadding().encodeToString(p.after.toByteArray(Charsets.UTF_8))
        val body = listOf(currentKeyId, p.through.epochSecond, p.through.nano, encodedKey,
            p.expires.epochSecond, p.expires.nano).joinToString(".")
        return "$body.${flagMac(currentKeyId, environment, actor, limit, body)}"
    }
    internal fun decodeFlags(value: String, environment: String, actor: UUID, limit: Int, now: Instant): FlagPosition {
        try {
            if (value.length !in 1..2048) invalid()
            val parts = value.split('.')
            if (parts.size != 7 || parts[0] !in keys || !parts[6].matches(Regex("[A-Za-z0-9_-]{43}"))) invalid()
            val body = parts.take(6).joinToString(".")
            if (!MessageDigest.isEqual(flagMac(parts[0], environment, actor, limit, body).toByteArray(), parts[6].toByteArray())) invalid()
            fun time(at: Int): Instant {
                val seconds = parts[at].toLong(); val nano = parts[at + 1].toInt()
                if (seconds.toString() != parts[at] || nano.toString() != parts[at + 1] || nano !in 0..999999999) invalid()
                return Instant.ofEpochSecond(seconds, nano.toLong())
            }
            val raw = Base64.getUrlDecoder().decode(parts[3]).toString(Charsets.UTF_8)
            if (!raw.matches(Regex("[a-z][a-z0-9_.-]{1,100}")) ||
                Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8)) != parts[3]) invalid()
            val p = FlagPosition(time(1), raw, time(4))
            if (p.through > now || p.expires <= p.through) invalid()
            if (now >= p.expires) throw StaffModerationFailure(StaffModerationFailureCode.CURSOR_EXPIRED)
            return p
        } catch (failure: StaffModerationFailure) { throw failure }
        catch (_: Exception) { invalid() }
    }
    private fun mac(id: String, environment: String, actor: UUID, limit: Int, body: String) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(keys.getValue(id), "HmacSHA256"))
        Base64.getUrlEncoder().withoutPadding().encodeToString(doFinal(
            "feedme.staff-moderation-cursor.v1\u0000$environment\u0000$actor\u0000$limit\u0000$body".toByteArray(Charsets.UTF_8)))
    }
    private fun auditMac(id: String, environment: String, actor: UUID, limit: Int, body: String) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(keys.getValue(id), "HmacSHA256"))
        Base64.getUrlEncoder().withoutPadding().encodeToString(doFinal(
            "feedme.staff-audit-cursor.v1\u0000$environment\u0000$actor\u0000$limit\u0000$body".toByteArray(Charsets.UTF_8)))
    }
    private fun flagMac(id: String, environment: String, actor: UUID, limit: Int, body: String) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(keys.getValue(id), "HmacSHA256"))
        Base64.getUrlEncoder().withoutPadding().encodeToString(doFinal(
            "feedme.staff-flags-cursor.v1\u0000$environment\u0000$actor\u0000$limit\u0000$body".toByteArray(Charsets.UTF_8)))
    }
    private fun invalid(): Nothing = throw StaffModerationFailure(StaffModerationFailureCode.INPUT_INVALID)
    override fun toString() = "StaffModerationCursors(<redacted>)"
}
