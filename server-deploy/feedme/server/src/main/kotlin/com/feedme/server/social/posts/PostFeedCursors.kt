package com.feedme.server.social.posts

import java.nio.charset.CharacterCodingException
import java.security.SecureRandom
import java.time.DateTimeException
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class PostFeedCursorFailureReason { INVALID, EXPIRED }
class PostFeedCursorFailure(val reason: PostFeedCursorFailureReason) : RuntimeException("Post feed cursor unavailable")

/** Complete descending SQL continuation, including the owner tie-breaker because Post IDs
 * are not globally unique. This position carries no account, audience or content authority. */
data class PostFeedCursorPosition(val publishedAt: Instant, val postId: UUID, val ownerId: UUID,
    val upperTime: Instant, val expiresAt: Instant) {
    override fun toString() = "PostFeedCursorPosition(<redacted>)"
}

/** Authenticated encrypted continuation: a bounded scan may resume after a denied candidate,
 * so neither its Post ID nor its author ID may be exposed as plaintext cursor material.
 * Every page independently reauthorizes current rows. Caller supplies the stable first-page
 * upper time/absolute expiry; continuation must preserve both instead of renewing them.
 * Explicit detached keys only; no generated key, default environment or hidden clock. */
class PostFeedCursors(private val currentKeyId: String, keys: Map<String, ByteArray>) {
    private val keys = run {
        valid(keys.size in 1..8 && currentKeyId in keys)
        valid(keys.all { (id, key) -> KEY_ID.matches(id) && key.size == 32 })
        keys.mapValues { (_, value) ->
            val detached = value.copyOf()
            try { derive(detached) } finally { detached.fill(0) }
        }
    }
    private val random = SecureRandom()

    fun encode(environment: String, viewerId: UUID, surface: String, circleId: UUID?, profileOwnerId: UUID?,
        limit: Int, publishedAt: Instant, postId: UUID, ownerId: UUID, upperTime: Instant, expiresAt: Instant): String {
        context(environment, surface, circleId, profileOwnerId, limit)
        valid(!publishedAt.isAfter(upperTime) && upperTime.isBefore(expiresAt))
        val plaintext = "1.${publishedAt.epochSecond}.${publishedAt.nano}.$postId.$ownerId.${upperTime.epochSecond}.${upperTime.nano}.${expiresAt.epochSecond}.${expiresAt.nano}"
            .toByteArray(Charsets.UTF_8)
        try {
            valid(plaintext.size in 1..MAX_PAYLOAD_BYTES)
            val nonce = ByteArray(12).also(random::nextBytes)
            val encrypted = crypt(Cipher.ENCRYPT_MODE, currentKeyId, nonce,
                aad(currentKeyId, environment, viewerId, surface, circleId, profileOwnerId, limit), plaintext)
            return "$currentKeyId.${encode64(nonce)}.${encode64(encrypted)}".also { valid(it.length <= 2048) }
        } finally { plaintext.fill(0) }
    }

    fun decode(token: String, environment: String, viewerId: UUID, surface: String, circleId: UUID?,
        profileOwnerId: UUID?, limit: Int, now: Instant): PostFeedCursorPosition {
        context(environment, surface, circleId, profileOwnerId, limit)
        valid(token.length in 1..2048)
        val pieces = token.split('.')
        valid(pieces.size == 3 && KEY_ID.matches(pieces[0]) && pieces[0] in keys)
        val nonce = decode64(pieces[1]); valid(nonce.size == 12)
        val ciphertext = decode64(pieces[2]); valid(ciphertext.size in 17..(MAX_PAYLOAD_BYTES + 16))
        // GCM authenticates the entire encrypted payload and every request-context field
        // before parsing a position or distinguishing expiry from malformed input.
        val plaintext = crypt(Cipher.DECRYPT_MODE, pieces[0], nonce,
            aad(pieces[0], environment, viewerId, surface, circleId, profileOwnerId, limit), ciphertext)
        try {
            valid(plaintext.size in 1..MAX_PAYLOAD_BYTES)
            val text = try { plaintext.decodeToString(throwOnInvalidSequence = true) }
                catch (_: IllegalArgumentException) { invalid() }
                catch (_: CharacterCodingException) { invalid() }
            val fields = text.split('.')
            valid(fields.size == 9 && fields[0] == "1")
            val published = instant(fields[1], fields[2])
            val post = uuid(fields[3]); val owner = uuid(fields[4])
            val upper = instant(fields[5], fields[6]); val expires = instant(fields[7], fields[8])
            valid(!published.isAfter(upper) && upper.isBefore(expires))
            if (!now.isBefore(expires)) throw PostFeedCursorFailure(PostFeedCursorFailureReason.EXPIRED)
            return PostFeedCursorPosition(published, post, owner, upper, expires)
        } finally { plaintext.fill(0) }
    }

    private fun crypt(mode: Int, keyId: String, nonce: ByteArray, context: ByteArray, input: ByteArray): ByteArray = try {
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, SecretKeySpec(keys.getValue(keyId), "AES"), GCMParameterSpec(128, nonce))
            updateAAD(context)
            doFinal(input)
        }
    } catch (_: Exception) { invalid() }

    override fun toString() = "PostFeedCursors(<redacted>)"

    companion object {
        private const val MAX_PAYLOAD_BYTES = 512
        private val KEY_ID = Regex("[a-z0-9_-]{1,32}")
        private val ENVIRONMENT = Regex("[a-z][a-z0-9-]{0,39}")
        private val BASE64URL = Regex("[A-Za-z0-9_-]+")
        private val SECONDS = Regex("-?(?:0|[1-9][0-9]{0,18})")
        private val NANOS = Regex("0|[1-9][0-9]{0,8}")
        private fun context(environment: String, surface: String, circleId: UUID?, profileOwnerId: UUID?, limit: Int) {
            valid(ENVIRONMENT.matches(environment) && limit in 1..50)
            valid(when (surface) {
                "today" -> profileOwnerId == null
                "plate" -> circleId == null && profileOwnerId != null
                else -> false
            })
        }
        private fun aad(keyId: String, environment: String, viewer: UUID, surface: String, circle: UUID?, profile: UUID?, limit: Int) =
            "feedme.post-feed-cursor.v1\u0000$keyId\u0000$environment\u0000$viewer\u0000$surface\u0000${circle ?: "-"}\u0000${profile ?: "-"}\u0000$limit"
                .toByteArray(Charsets.UTF_8)
        private fun derive(key: ByteArray): ByteArray = try {
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal("feedme.post-feed-cursor.aes-256-gcm.v1".toByteArray(Charsets.US_ASCII))
            }
        } catch (_: Exception) { invalid() }
        private fun encode64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        private fun decode64(text: String): ByteArray {
            valid(BASE64URL.matches(text))
            val bytes = try { Base64.getUrlDecoder().decode(text) } catch (_: IllegalArgumentException) { invalid() }
            valid(encode64(bytes) == text)
            return bytes
        }
        private fun uuid(text: String): UUID = try {
            UUID.fromString(text).also { valid(it.toString() == text) }
        } catch (_: IllegalArgumentException) { invalid() }
        private fun instant(seconds: String, nanos: String): Instant {
            valid(SECONDS.matches(seconds) && NANOS.matches(nanos))
            val s = seconds.toLongOrNull() ?: invalid(); val n = nanos.toIntOrNull() ?: invalid()
            valid(s.toString() == seconds && n.toString() == nanos && n in 0..999_999_999)
            return try { Instant.ofEpochSecond(s, n.toLong()) } catch (_: DateTimeException) { invalid() }
        }
        private fun valid(value: Boolean) { if (!value) invalid() }
        private fun invalid(): Nothing = throw PostFeedCursorFailure(PostFeedCursorFailureReason.INVALID)
    }
}
