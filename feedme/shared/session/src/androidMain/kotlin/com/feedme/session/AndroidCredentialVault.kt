package com.feedme.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException

/** Nonexportable install and incarnation keys in an independently owned AndroidKeyStore namespace. */
internal class AndroidCredentialVault private constructor(
    private val keyStore: KeyStore,
    private val keyPrefix: String,
) {
    fun index(input: ByteArray): ByteArray = sanitized {
        authenticate(requireKey(indexAlias, HMAC), input)
    }

    fun sealManifest(plaintext: ByteArray, aad: ByteArray): ByteArray = sanitized {
        sealEnvelope(requireKey(manifestAlias, AES), plaintext, aad)
    }

    fun openManifest(ciphertext: ByteArray, aad: ByteArray): ByteArray = sanitized {
        openEnvelope(requireKey(manifestAlias, AES), ciphertext, aad)
    }

    fun createIncarnationKey(target: String) = synchronized(keyMutationLock) {
        sanitized {
            val alias = credentialAlias(target)
            // Never replace a key, including an unusable key or an interrupted earlier creation.
            require(!keyStore.containsAlias(alias))
            generateAesKey(alias)
            requireUsableAesKey(alias)
            Unit
        }
    }

    fun hasIncarnationKey(target: String): Boolean = sanitized {
        val alias = credentialAlias(target)
        if (!keyStore.containsAlias(alias)) false else {
            requireUsableAesKey(alias)
            true
        }
    }

    /** Enumerates identities only; orphan-key repair decisions belong to the store owner. */
    fun credentialTargets(): Set<String> = sanitized {
        ownedCredentialTargets()
    }

    fun deleteIncarnationKey(target: String) = synchronized(keyMutationLock) {
        sanitized {
            val alias = credentialAlias(target)
            if (keyStore.containsAlias(alias)) {
                // Do not require a usable key: invalidated keys must still be erasable.
                keyStore.deleteEntry(alias)
            }
            require(!keyStore.containsAlias(alias))
        }
    }

    fun sealCredentials(target: String, plaintext: ByteArray, aad: ByteArray): ByteArray = sanitized {
        sealEnvelope(requireKey(credentialAlias(target), AES), plaintext, aad)
    }

    fun openCredentials(target: String, ciphertext: ByteArray, aad: ByteArray): ByteArray = sanitized {
        openEnvelope(requireKey(credentialAlias(target), AES), ciphertext, aad)
    }

    private val indexAlias: String get() = "$keyPrefix.index"
    private val manifestAlias: String get() = "$keyPrefix.manifest"

    private fun credentialAlias(target: String): String {
        require(TARGET.matches(target))
        return "$keyPrefix.credential.$target"
    }

    private fun ownedCredentialTargets(): Set<String> {
        val namespace = "$keyPrefix."
        val credentialPrefix = "$keyPrefix.credential."
        val targets = mutableSetOf<String>()
        val aliases = keyStore.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            if (!alias.startsWith(namespace)) continue
            when {
                alias == indexAlias || alias == manifestAlias -> Unit
                alias.startsWith(credentialPrefix) -> {
                    val target = alias.removePrefix(credentialPrefix)
                    require(TARGET.matches(target))
                    targets.add(target)
                }
                else -> throw CredentialVaultException()
            }
        }
        return targets.toSet()
    }

    private fun requireKey(alias: String, algorithm: String): SecretKey {
        val key = keyStore.getKey(alias, null) as? SecretKey ?: throw CredentialVaultException()
        require(key.algorithm.equals(algorithm, ignoreCase = true) && key.encoded == null)
        val keyInfo = SecretKeyFactory.getInstance(algorithm, KEY_STORE)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        require(keyInfo.keySize == 256)
        return key
    }

    private fun requireUsableAesKey(alias: String): SecretKey {
        val key = requireKey(alias, AES)
        // Metadata alone does not establish that an existing key is still usable.
        val probe = sealEnvelope(key, ByteArray(0), PROBE_AAD)
        require(openEnvelope(key, probe, PROBE_AAD).isEmpty())
        return key
    }

    private fun generateAesKey(alias: String) {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()
        KeyGenerator.getInstance(AES, KEY_STORE).apply { init(spec) }.generateKey()
    }

    private fun authenticate(key: SecretKey, input: ByteArray): ByteArray = Mac.getInstance(HMAC).run {
        init(key)
        doFinal(input).also { require(it.size == 32) }
    }

    private fun sealEnvelope(key: SecretKey, plaintext: ByteArray, aad: ByteArray): ByteArray =
        Cipher.getInstance(TRANSFORMATION).run {
            // AndroidKeyStore chooses the nonce; callers cannot disable randomized encryption.
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(aad)
            val encrypted = doFinal(plaintext)
            val nonce = iv
            require(nonce.size == NONCE_BYTES)
            require(encrypted.size.toLong() == plaintext.size.toLong() + TAG_BYTES)
            byteArrayOf(ENVELOPE_VERSION) + nonce + encrypted
        }

    private fun openEnvelope(key: SecretKey, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        require(ciphertext.size >= HEADER_BYTES + NONCE_BYTES + TAG_BYTES)
        require(ciphertext[0] == ENVELOPE_VERSION)
        return Cipher.getInstance(TRANSFORMATION).run {
            init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(TAG_BYTES * 8, ciphertext, HEADER_BYTES, NONCE_BYTES),
            )
            updateAAD(aad)
            val payloadOffset = HEADER_BYTES + NONCE_BYTES
            // No plaintext escapes before the complete authentication tag has been verified.
            doFinal(ciphertext, payloadOffset, ciphertext.size - payloadOffset)
        }
    }

    companion object {
        private const val KEY_STORE = "AndroidKeyStore"
        private const val AES = KeyProperties.KEY_ALGORITHM_AES
        private const val HMAC = KeyProperties.KEY_ALGORITHM_HMAC_SHA256
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_BYTES = 12
        private const val TAG_BYTES = 16
        private const val HEADER_BYTES = 1
        private const val ENVELOPE_VERSION: Byte = 1
        private val TARGET = Regex("[0-9a-f]{64}")
        private val KEY_PREFIX = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")
        private val PROBE_AAD = "feedme.credentials.key-usability.v1".encodeToByteArray()
        private val keyMutationLock = Any()

        /** The caller must also hold its durable credential-store lifetime lock. */
        fun open(keyPrefix: String, initialize: Boolean): AndroidCredentialVault =
            synchronized(keyMutationLock) {
                sanitized {
                    require(KEY_PREFIX.matches(keyPrefix))
                    val store = KeyStore.getInstance(KEY_STORE).apply { load(null) }
                    val vault = AndroidCredentialVault(store, keyPrefix)
                    if (initialize) {
                        val namespace = "$keyPrefix."
                        val aliases = store.aliases()
                        while (aliases.hasMoreElements()) {
                            require(!aliases.nextElement().startsWith(namespace))
                        }
                        val spec = KeyGenParameterSpec.Builder(vault.indexAlias, KeyProperties.PURPOSE_SIGN)
                            .setKeySize(256)
                            .setUserAuthenticationRequired(false)
                            .build()
                        KeyGenerator.getInstance(HMAC, KEY_STORE).apply { init(spec) }.generateKey()
                        vault.generateAesKey(vault.manifestAlias)
                        // Partial initialization is left fail-closed, never silently replaced on retry.
                    }
                    // Reopening only uses existing keys; missing or unusable install keys are fatal.
                    vault.ownedCredentialTargets()
                    vault.authenticate(vault.requireKey(vault.indexAlias, HMAC), PROBE_AAD)
                    vault.requireUsableAesKey(vault.manifestAlias)
                    vault
                }
            }

        private inline fun <T> sanitized(operation: () -> T): T = try {
            operation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw CredentialVaultException()
        }
    }
}

internal class CredentialVaultException : Exception("Credential storage unavailable")
