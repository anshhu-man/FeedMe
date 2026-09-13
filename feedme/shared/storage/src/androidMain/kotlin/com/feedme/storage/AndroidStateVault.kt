package com.feedme.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException

/** Keys remain inside AndroidKeyStore; no encoded key material is stored in application files. */
internal class AndroidStateVault private constructor(
    private val keyStore: KeyStore,
    private val keyPrefix: String,
) : PlannedStateVault {
    override fun index(input: ByteArray): ByteArray = sanitized {
        Mac.getInstance(HMAC).run {
            init(requireKey(indexAlias, HMAC))
            doFinal(input).also { require(it.size == 32) }
        }
    }

    override fun createOwnerKey(): String = synchronized(keyMutationLock) {
        val keyId = newOwnerKeyId()
        createOwnerKey(keyId)
        keyId
    }

    /** A random candidate only: neither a Keystore entry nor a durable reservation is created. */
    override fun newOwnerKeyId(): String = synchronized(keyMutationLock) {
        sanitized {
            repeat(10) {
                val bytes = ByteArray(16).also(random::nextBytes)
                val keyId = try {
                    buildString(32) {
                        for (byte in bytes) {
                            append(HEX[(byte.toInt() ushr 4) and 15])
                            append(HEX[byte.toInt() and 15])
                        }
                    }
                } finally { bytes.fill(0) }
                if (!keyStore.containsAlias(ownerAlias(keyId))) return@sanitized keyId
            }
            throw StateVaultException()
        }
    }

    override fun createOwnerKey(keyId: String) = synchronized(keyMutationLock) {
        sanitized {
            val alias = ownerAlias(keyId)
            // Refuse every existing alias, including an invalidated/unusable entry. The caller
            // must authenticate its plan and exact SQL precondition before requesting this key.
            require(!keyStore.containsAlias(alias))
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
            requireKey(alias, AES)
            Unit
        }
    }

    override fun hasOwnerKey(keyId: String): Boolean = sanitized {
        val alias = ownerAlias(keyId)
        if (!keyStore.containsAlias(alias)) false else {
            requireKey(alias, AES)
            true
        }
    }

    /** Presence is distinct from usability: authenticated exact abort may erase an invalid key. */
    override fun containsOwnerKey(keyId: String): Boolean = sanitized {
        keyStore.containsAlias(ownerAlias(keyId))
    }

    override fun seal(keyId: String, plaintext: ByteArray, associatedData: ByteArray): ByteArray = sanitized {
        Cipher.getInstance(TRANSFORMATION).run {
            // Passing no IV preserves Keystore randomized-encryption enforcement.
            init(Cipher.ENCRYPT_MODE, requireKey(ownerAlias(keyId), AES))
            updateAAD(associatedData)
            val encrypted = doFinal(plaintext)
            val nonce = iv
            require(nonce.size == NONCE_BYTES && encrypted.size == plaintext.size + TAG_BYTES)
            byteArrayOf(ENVELOPE_VERSION) + nonce + encrypted
        }
    }

    override fun open(keyId: String, ciphertext: ByteArray, associatedData: ByteArray): ByteArray = sanitized {
        require(ciphertext.size >= HEADER_BYTES + NONCE_BYTES + TAG_BYTES)
        require(ciphertext[0] == ENVELOPE_VERSION)
        Cipher.getInstance(TRANSFORMATION).run {
            init(
                Cipher.DECRYPT_MODE,
                requireKey(ownerAlias(keyId), AES),
                GCMParameterSpec(TAG_BYTES * 8, ciphertext, HEADER_BYTES, NONCE_BYTES),
            )
            updateAAD(associatedData)
            // No plaintext leaves this call until doFinal has verified the complete tag.
            val payloadOffset = HEADER_BYTES + NONCE_BYTES
            doFinal(ciphertext, payloadOffset, ciphertext.size - payloadOffset)
        }
    }

    override fun deleteOwnerKey(keyId: String) = synchronized(keyMutationLock) {
        sanitized {
            val alias = ownerAlias(keyId)
            if (keyStore.containsAlias(alias)) {
                // Invalidated keys must remain erasable even when getKey/crypto can no longer use them.
                keyStore.deleteEntry(alias)
                require(!keyStore.containsAlias(alias))
            }
        }
    }

    private val indexAlias: String get() = "$keyPrefix.index"

    private fun ownerAlias(keyId: String): String {
        require(KEY_ID.matches(keyId))
        return "$keyPrefix.owner.$keyId"
    }

    private fun requireKey(alias: String, algorithm: String): SecretKey {
        val key = keyStore.getKey(alias, null) as? SecretKey ?: throw StateVaultException()
        require(key.algorithm.equals(algorithm, ignoreCase = true) && key.encoded == null)
        val keyInfo = SecretKeyFactory.getInstance(algorithm, KEY_STORE)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        require(keyInfo.keySize == 256)
        return key
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
        private const val HEX = "0123456789abcdef"
        private val KEY_ID = Regex("[0-9a-f]{32}")
        private val KEY_PREFIX = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")
        private val random = SecureRandom()
        private val keyMutationLock = Any()

        /** Recovery has no authority to create an install index, even if every file is missing. */
        fun openExisting(keyPrefix: String): AndroidStateVault = createOrOpen(keyPrefix, databaseExisted = true)

        /** Must be called while the factory holds the database lifetime lock. */
        fun createOrOpen(keyPrefix: String, databaseExisted: Boolean): AndroidStateVault =
            synchronized(keyMutationLock) {
                sanitized {
                    require(KEY_PREFIX.matches(keyPrefix))
                    val store = KeyStore.getInstance(KEY_STORE).apply { load(null) }
                    val vault = AndroidStateVault(store, keyPrefix)
                    if (!store.containsAlias(vault.indexAlias)) {
                        // Existing state must never be silently rebound to a newly generated index.
                        if (databaseExisted) throw StateVaultException()
                        val spec = KeyGenParameterSpec.Builder(vault.indexAlias, KeyProperties.PURPOSE_SIGN)
                            .setKeySize(256)
                            .setUserAuthenticationRequired(false)
                            .build()
                        KeyGenerator.getInstance(HMAC, KEY_STORE).apply { init(spec) }.generateKey()
                    }
                    // Reuse an existing index after a crash between key creation and database creation.
                    vault.requireKey(vault.indexAlias, HMAC)
                    vault
                }
            }

        private inline fun <T> sanitized(operation: () -> T): T = try {
            operation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw StateVaultException()
        }
    }
}
