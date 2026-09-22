package com.feedme.server.export

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Dedicated export wrapping key. It must not be a provider credential, JWT signing key,
 * account password or key derived from public account IDs. Old key IDs must remain available
 * until their artifacts expire; a missing key fails closed, never selects a replacement. */
class AccountExportEncryption(val keyId: String, wrappingKey: ByteArray) : AutoCloseable {
    private val key = wrappingKey.copyOf()
    private val random = SecureRandom()
    private var closed = false
    init { require(keyId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) && key.size == 32) }

    @Synchronized internal fun encrypt(environment: String, jobId: UUID, accountId: UUID, artifactId: UUID,
        plaintext: ByteArray): ExportEncryptedBytes {
        current(); require(plaintext.size in 1..MAX_PLAINTEXT_BYTES)
        val aad = binding(environment, jobId, accountId, artifactId)
        val dataKey = ByteArray(32).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val wrapNonce = ByteArray(12).also(random::nextBytes)
        try {
            val encrypted = crypt(Cipher.ENCRYPT_MODE, dataKey, nonce, aad, plaintext)
            val wrapped = crypt(Cipher.ENCRYPT_MODE, key, wrapNonce, aad + WRAP_DOMAIN, dataKey)
            return ExportEncryptedBytes(keyId, wrapNonce + wrapped, nonce, encrypted, exportSha(plaintext), plaintext.size, exportSha(encrypted))
        } finally { dataKey.fill(0); aad.fill(0) }
    }

    @Synchronized internal fun decrypt(artifact: ExportArtifact, ciphertext: ByteArray): ByteArray {
        current()
        val wrapped = artifact.wrappedKey ?: throw ExportWorkFailure(ExportWorkIssue.OBJECT_MISMATCH)
        val nonce = artifact.nonce
        if (artifact.keyId != keyId || wrapped.size != 60 || nonce.size != 12 ||
            artifact.plaintextBytes !in 1..MAX_PLAINTEXT_BYTES || ciphertext.size != artifact.plaintextBytes + 16 ||
            exportSha(ciphertext) != artifact.cipherSha256) {
            wrapped.fill(0); nonce.fill(0); throw ExportWorkFailure(ExportWorkIssue.OBJECT_MISMATCH)
        }
        val aad = binding(artifact.environment, artifact.jobId, artifact.accountId, artifact.artifactId)
        val dataKey = try { crypt(Cipher.DECRYPT_MODE, key, wrapped.copyOfRange(0,12), aad + WRAP_DOMAIN, wrapped.copyOfRange(12,60)) }
            catch (_: Exception) { wrapped.fill(0); nonce.fill(0); aad.fill(0); throw ExportWorkFailure(ExportWorkIssue.OBJECT_MISMATCH) }
        try {
            val plaintext = crypt(Cipher.DECRYPT_MODE, dataKey, nonce, aad, ciphertext)
            if (plaintext.size != artifact.plaintextBytes || exportSha(plaintext) != artifact.plaintextSha256) {
                plaintext.fill(0); throw ExportWorkFailure(ExportWorkIssue.OBJECT_MISMATCH)
            }
            return plaintext
        } catch (f: ExportWorkFailure) { throw f }
        catch (_: Exception) { throw ExportWorkFailure(ExportWorkIssue.OBJECT_MISMATCH) }
        finally { dataKey.fill(0); wrapped.fill(0); nonce.fill(0); aad.fill(0) }
    }
    private fun current() { if (closed) throw ExportWorkFailure(ExportWorkIssue.NOT_CONFIGURED) }
    @Synchronized override fun close() { closed = true; key.fill(0) }
    override fun toString() = "AccountExportEncryption(<redacted>)"
    companion object {
        const val MAX_PLAINTEXT_BYTES = 4_194_304
        private val WRAP_DOMAIN = "\nwrapped-export-data-key-v1".toByteArray(StandardCharsets.UTF_8)
        private fun binding(environment: String, job: UUID, account: UUID, artifact: UUID): ByteArray {
            require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
            return "feedme-private-export-v1\n$environment\n$job\n$account\n$artifact".toByteArray(StandardCharsets.UTF_8)
        }
        private fun crypt(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad); doFinal(input)
            }
    }
}
internal class ExportEncryptedBytes(val keyId:String,val wrappedKey:ByteArray,val nonce:ByteArray,val ciphertext:ByteArray,
    val plaintextSha256:String,val plaintextBytes:Int,val cipherSha256:String) : AutoCloseable {
    override fun close() { wrappedKey.fill(0);nonce.fill(0);ciphertext.fill(0) }
    override fun toString() = "ExportEncryptedBytes(<redacted>)"
}
internal fun exportSha(bytes:ByteArray):String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }
internal enum class ExportWorkIssue { NOT_CONFIGURED, LIMIT_EXCEEDED, OBJECT_MISMATCH, OUTCOME_UNKNOWN, SOURCE_UNAVAILABLE }
internal class ExportWorkFailure(val issue:ExportWorkIssue):RuntimeException("Export work unavailable: ${issue.name}")
