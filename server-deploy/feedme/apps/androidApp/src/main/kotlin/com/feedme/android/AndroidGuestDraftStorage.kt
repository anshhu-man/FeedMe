package com.feedme.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Local cooking notes only. Never opens an account database or mints a session. */
internal fun androidGuestDraftStore(context: Context): GuestEncryptedDraftStore {
    val directory = File(context.noBackupFilesDir, "feedme-guest-draft")
    return GuestEncryptedDraftStore(AndroidGuestDraftBytes(directory),
        AndroidGuestDraftCipher(context.packageName))
}

/** One process-owned store serializes every call. No plaintext reaches an application file. */
private class AndroidGuestDraftBytes(private val directory: File) : GuestDraftBytes {
    private val base = File(directory, "draft.v1.enc")
    private val atomic = AtomicFile(base)

    override fun read(): ByteArray? {
        // File.exists() cannot distinguish absence from access/I/O failure. Only ENOENT
        // admits a fresh draft; any interrupted file or failed actual read stays unavailable.
        if (absent(base) && absent(File(base.path + ".bak")) && absent(File(base.path + ".new"))) return null
        val input = atomic.openRead()
        return input.use {
            val buffer = ByteArray(MAX_CIPHERTEXT_BYTES + 1)
            var count = 0
            while (count < buffer.size) {
                val read = it.read(buffer, count, buffer.size - count)
                if (read < 0) break
                if (read == 0) throw GuestDraftCorruptException()
                count += read
            }
            if (count > MAX_CIPHERTEXT_BYTES) throw GuestDraftCorruptException()
            buffer.copyOf(count)
        }
    }

    private fun absent(file: File): Boolean = try {
        Os.lstat(file.path)
        false
    } catch (failure: ErrnoException) {
        if (failure.errno == OsConstants.ENOENT) true else throw failure
    }

    override fun write(bytes: ByteArray) {
        require(bytes.size in 1..MAX_CIPHERTEXT_BYTES)
        if (!directory.exists()) {
            check(directory.mkdir())
            syncDirectory(checkNotNull(directory.parentFile))
        }
        check(directory.isDirectory)
        var output: FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output.write(bytes)
            output.flush()
            output.fd.sync()
            atomic.finishWrite(output)
            output = null
            syncDirectory(directory)
            // AtomicFile can report some failures only through platform diagnostics. Verify the
            // committed ciphertext too; a failure here remains outcome-unknown, never "saved".
            check(read()?.contentEquals(bytes) == true)
        } catch (failure: Exception) {
            output?.let { atomic.failWrite(it) }
            throw failure
        }
    }

    private fun syncDirectory(path: File) {
        val descriptor = Os.open(path.path, OsConstants.O_RDONLY, 0)
        try {
            check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode))
            Os.fsync(descriptor)
        } finally { Os.close(descriptor) }
    }

    companion object { private const val MAX_CIPHERTEXT_BYTES = 16_384 }
}

/** AndroidKeyStore generates IVs and keeps AES material non-exportable. No fallback key. */
private class AndroidGuestDraftCipher(packageName: String) : GuestDraftCipher {
    private var keyCreationPermitted = true
    private val alias = "$packageName.local-cooking-draft.aes.v1"
    private val associatedData = "$packageName|local-cooking-draft|envelope-v1".toByteArray(Charsets.UTF_8)

    override fun seal(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(createWhenAbsent = keyCreationPermitted))
        keyCreationPermitted = false
        cipher.updateAAD(associatedData)
        val encrypted = cipher.doFinal(plaintext)
        val nonce = cipher.iv
        check(nonce.size == 12 && encrypted.size == plaintext.size + 16)
        return byteArrayOf(1) + nonce + encrypted
    }

    override fun open(ciphertext: ByteArray): ByteArray {
        keyCreationPermitted = false
        if (ciphertext.isEmpty()) throw GuestDraftCorruptException()
        val version = ciphertext[0].toInt() and 255
        if (version > 1) throw GuestDraftFutureVersionException()
        if (version != 1 || ciphertext.size < 29) throw GuestDraftCorruptException()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(createWhenAbsent = false), GCMParameterSpec(128, ciphertext, 1, 12))
        cipher.updateAAD(associatedData)
        return try { cipher.doFinal(ciphertext, 13, ciphertext.size - 13) }
        catch (_: AEADBadTagException) { throw GuestDraftCorruptException() }
    }

    private fun key(createWhenAbsent: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) {
            check(createWhenAbsent) // Existing ciphertext without its key must remain untouched.
            val spec = KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(false).build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply { init(spec) }.generateKey()
        }
        return (store.getKey(alias, null) as? SecretKey)?.also {
            check(it.algorithm == "AES" && it.encoded == null)
        } ?: error("Local cooking storage is unavailable")
    }
}
