package com.feedme.server.social.posts

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class RemixCursorFailure(val expired: Boolean = false) : RuntimeException("Remix cursor unavailable")
internal class RemixPosition(val offset: Int, val expiresAt: Instant)

/** Purpose-separated authenticated encryption hides scan depth (including omitted nodes).
 * Explicit stable application signing material only; no generated/default secret. */
class RemixCursors(private val currentKeyId: String, keys: Map<String, ByteArray>) {
    private val keys = keys.mapValues { (_, key) ->
        require(key.size == 32)
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key.copyOf(), "HmacSHA256"))
            doFinal("feedme.remix-cursor.aes-256-gcm.v1".toByteArray(Charsets.US_ASCII))
        }
    }
    private val random = SecureRandom()
    init { require(keys.size in 1..8 && currentKeyId in keys && keys.keys.all { it.matches(Regex("[a-z0-9_-]{1,32}")) }) }
    internal fun encode(environment: String, viewer: UUID, root: UUID, limit: Int, offset: Int, expiry: Instant): String {
        checkContext(environment, limit); require(offset in 1..199)
        val nonce = ByteArray(12).also(random::nextBytes)
        val bytes = "1.$offset.${expiry.epochSecond}.${expiry.nano}".toByteArray(Charsets.US_ASCII)
        return try { "$currentKeyId.${encode64(nonce)}.${encode64(crypt(Cipher.ENCRYPT_MODE, currentKeyId, nonce,
            aad(environment, viewer, root, limit), bytes))}" } finally { bytes.fill(0) }
    }
    internal fun decode(value: String, environment: String, viewer: UUID, root: UUID, limit: Int, now: Instant): RemixPosition {
        checkContext(environment, limit)
        if (value.length !in 1..1024) throw RemixCursorFailure()
        val parts = value.split('.')
        if (parts.size != 3 || parts[0] !in keys) throw RemixCursorFailure()
        val nonce = decode64(parts[1]); val cipher = decode64(parts[2])
        if (nonce.size != 12 || cipher.size !in 17..128) throw RemixCursorFailure()
        val bytes = crypt(Cipher.DECRYPT_MODE, parts[0], nonce, aad(environment, viewer, root, limit), cipher)
        try {
            val fields = bytes.toString(Charsets.US_ASCII).split('.')
            if (fields.size != 4 || fields[0] != "1") throw RemixCursorFailure()
            val offset = fields[1].toIntOrNull()?.takeIf { it in 1..199 && it.toString() == fields[1] } ?: throw RemixCursorFailure()
            val seconds = fields[2].toLongOrNull()?.takeIf { it.toString() == fields[2] } ?: throw RemixCursorFailure()
            val nanos = fields[3].toIntOrNull()?.takeIf { it in 0..999999999 && it.toString() == fields[3] } ?: throw RemixCursorFailure()
            val expiry = try { Instant.ofEpochSecond(seconds, nanos.toLong()) } catch (_: Exception) { throw RemixCursorFailure() }
            if (!now.isBefore(expiry)) throw RemixCursorFailure(true)
            return RemixPosition(offset, expiry)
        } finally { bytes.fill(0) }
    }
    private fun crypt(mode: Int, id: String, nonce: ByteArray, aad: ByteArray, bytes: ByteArray): ByteArray = try {
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, SecretKeySpec(keys.getValue(id), "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad); doFinal(bytes)
        }
    } catch (_: Exception) { throw RemixCursorFailure() }
    private fun checkContext(environment: String, limit: Int) {
        if (!environment.matches(Regex("[a-z][a-z0-9-]{0,39}")) || limit !in 1..20) throw RemixCursorFailure()
    }
    private fun aad(environment: String, viewer: UUID, root: UUID, limit: Int) =
        "feedme.remix-cursor.v1\u0000$environment\u0000$viewer\u0000$root\u0000$limit".toByteArray(Charsets.UTF_8)
    private fun encode64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun decode64(value: String): ByteArray = try {
        if (!value.matches(Regex("[A-Za-z0-9_-]+"))) throw RemixCursorFailure()
        Base64.getUrlDecoder().decode(value).also { if (encode64(it) != value) throw RemixCursorFailure() }
    } catch (_: Exception) { throw RemixCursorFailure() }
    override fun toString() = "RemixCursors(<redacted>)"
}
