package com.feedme.server.guest

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Exact comparison inputs, not a verified installation, identity, command or session. */
internal class GuestReplayBinding(
    val environment: String,
    val commandKey: UUID,
    val installationSha256: String,
    val requestSha256: String,
    val guestSessionId: UUID,
) {
    init {
        replayChecked {
            require(Regex("[a-z][a-z0-9-]{0,39}").matches(environment))
            require(Regex("[0-9a-f]{64}").matches(installationSha256))
            require(Regex("[0-9a-f]{64}").matches(requestSha256))
        }
    }
    override fun toString() = "GuestReplayBinding(<redacted>)"
}

/** Ciphertext container only, never a successful replay receipt or authority. */
internal class GuestReplayEnvelope(keyId: String, nonce: ByteArray, ciphertext: ByteArray) {
    val keyId: String = keyId
    private val nonceBytes: ByteArray
    private val ciphertextBytes: ByteArray
    init {
        replayChecked {
            require(REPLAY_KEY_ID.matches(keyId))
            require(nonce.size == REPLAY_NONCE_BYTES)
            require(ciphertext.size in REPLAY_TAG_BYTES..REPLAY_MAX_CIPHERTEXT)
        }
        nonceBytes = nonce.copyOf()
        ciphertextBytes = ciphertext.copyOf()
    }
    val nonce: ByteArray get() = nonceBytes.copyOf()
    val ciphertext: ByteArray get() = ciphertextBytes.copyOf()
    override fun toString() = "GuestReplayEnvelope(<redacted>)"
}

/** Protected exact bytes for a future dedicated guest bootstrap receipt, NOT StoredReply.
 * No keys are generated, discovered or persisted here. The owner supplies and retires the
 * explicit copied key ring. Operations and close serialize; closed instances cannot reopen.
 * open returns caller-owned plaintext, which its caller must clear when finished. Scratch
 * clearing does not promise erasure of JCA internals or immutable JVM values.
 */
internal class GuestReplayCipher(private val currentKeyId: String, keys: Map<String, ByteArray>) : AutoCloseable {
    private val keyMaterial: Map<String, ByteArray> = replayChecked {
        require(keys.size in 1..8 && REPLAY_KEY_ID.matches(currentKeyId) && currentKeyId in keys)
        val copied = mutableMapOf<String, ByteArray>()
        try {
            keys.forEach { (id, key) ->
                require(REPLAY_KEY_ID.matches(id) && key.size == 32)
                copied[id] = key.copyOf()
            }
            copied.toMap()
        } catch (failure: Exception) {
            copied.values.forEach { it.fill(0) }
            throw failure
        }
    }
    private var closed = false

    @Synchronized fun seal(binding: GuestReplayBinding, plaintext: ByteArray): GuestReplayEnvelope = replayChecked {
        require(!closed && plaintext.size <= REPLAY_MAX_PLAINTEXT)
        val input = plaintext.copyOf()
        val nonce = ByteArray(REPLAY_NONCE_BYTES)
        val aad = replayAad(binding, currentKeyId)
        try {
            SecureRandom().nextBytes(nonce)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyMaterial.getValue(currentKeyId), "AES"),
                GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            val encrypted = cipher.doFinal(input)
            try { GuestReplayEnvelope(currentKeyId, nonce, encrypted) }
            finally { encrypted.fill(0) }
        } finally {
            input.fill(0)
            nonce.fill(0)
            aad.fill(0)
        }
    }

    @Synchronized fun open(binding: GuestReplayBinding, envelope: GuestReplayEnvelope): ByteArray = replayChecked {
        require(!closed)
        val key = requireNotNull(keyMaterial[envelope.keyId])
        val nonce = envelope.nonce
        val encrypted = envelope.ciphertext
        val aad = replayAad(binding, envelope.keyId)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(encrypted)
        } finally {
            nonce.fill(0)
            encrypted.fill(0)
            aad.fill(0)
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        keyMaterial.values.forEach { it.fill(0) }
    }

    override fun toString() = "GuestReplayCipher(<redacted>)"
}

/** Format 1 AAD: each field is length-prefixed with four-byte big-endian length.
 * Field order: UTF-8 purpose, four-byte big-endian format=1, UTF-8 key ID, environment,
 * canonical command UUID, installation SHA-256, request SHA-256, canonical guest UUID.
 */
private fun replayAad(binding: GuestReplayBinding, keyId: String): ByteArray {
    val fields = listOf(
        "feedme.guest-replay.v1".toByteArray(Charsets.UTF_8),
        ByteBuffer.allocate(4).putInt(1).array(),
        keyId.toByteArray(Charsets.UTF_8),
        binding.environment.toByteArray(Charsets.UTF_8),
        binding.commandKey.toString().toByteArray(Charsets.UTF_8),
        binding.installationSha256.toByteArray(Charsets.UTF_8),
        binding.requestSha256.toByteArray(Charsets.UTF_8),
        binding.guestSessionId.toString().toByteArray(Charsets.UTF_8),
    )
    return try {
        ByteBuffer.allocate(fields.sumOf { 4 + it.size }).apply {
            fields.forEach { putInt(it.size); put(it) }
        }.array()
    } finally { fields.forEach { it.fill(0) } }
}

private val REPLAY_KEY_ID = Regex("[a-z0-9_-]{1,32}")
private const val REPLAY_NONCE_BYTES = 12
private const val REPLAY_TAG_BYTES = 16
private const val REPLAY_MAX_PLAINTEXT = 8192
private const val REPLAY_MAX_CIPHERTEXT = REPLAY_MAX_PLAINTEXT + REPLAY_TAG_BYTES
private inline fun <T> replayChecked(action: () -> T): T = try { action() }
    catch (failure: Exception) {
        if (failure is InterruptedException) Thread.currentThread().interrupt()
        throw IllegalArgumentException("Guest replay material is invalid")
    }
