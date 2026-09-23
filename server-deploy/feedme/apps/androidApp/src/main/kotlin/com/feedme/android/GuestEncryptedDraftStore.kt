package com.feedme.android

import com.feedme.app.guest.GuestDraftLoad
import com.feedme.app.guest.GuestDraftSave
import com.feedme.app.guest.GuestKitchenDraft
import com.feedme.app.guest.GuestKitchenDraftCodec
import com.feedme.app.guest.GuestKitchenDraftStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One exclusive native owner supplies bounded reads and atomic, synced writes. Null means
 * positively absent. Reads transfer an owned buffer; writes consume synchronously without
 * retaining the mutable argument. This is not account access or a multi-owner CAS guarantee. */
internal interface GuestDraftBytes {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
}

internal interface GuestDraftCipher {
    fun seal(plaintext: ByteArray): ByteArray
    fun open(ciphertext: ByteArray): ByteArray
}

internal class GuestDraftCorruptException : RuntimeException("Guest draft is unreadable")
internal class GuestDraftFutureVersionException : RuntimeException("Guest draft version is unavailable")

/** Local draft storage only. The host must retain one instance for the fixed native file.
 * All native calls run on IO while this owner's mutex stays held, including the return hop.
 * No unreadable state is reset, no key is created here, and no operation retries a write.
 * Saved additionally relies on the native byte owner's durability contract; JVM tests cannot
 * establish Android Keystore, filesystem or power-loss behavior. */
internal class GuestEncryptedDraftStore(
    private val bytes: GuestDraftBytes,
    private val cipher: GuestDraftCipher,
) : GuestKitchenDraftStore {
    private val mutex = Mutex()
    private var writable = false
    private var observed: ByteArray? = null

    override suspend fun load(): GuestDraftLoad = mutex.withLock {
        forget()
        var read: ByteArray? = null
        try {
            val result = withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                read = readBounded()
                if (read == null) GuestDraftLoad.Empty else {
                    val plaintext = cipher.open(checkNotNull(read).copyOf())
                    try {
                        if (plaintext.size !in 1..GuestKitchenDraftCodec.MAX_ENCODED_BYTES)
                            GuestDraftLoad.Corrupt
                        else GuestKitchenDraftCodec.decode(plaintext)
                    } finally { plaintext.fill(0) }
                }
            }
            currentCoroutineContext().ensureActive()
            if (result == GuestDraftLoad.Empty || result is GuestDraftLoad.Loaded) {
                observed = read?.copyOf()
                writable = true
            }
            result
        } catch (cancelled: CancellationException) {
            forget()
            throw cancelled
        } catch (_: GuestDraftFutureVersionException) {
            GuestDraftLoad.FutureVersion
        } catch (_: GuestDraftCorruptException) {
            GuestDraftLoad.Corrupt
        } catch (_: Exception) {
            GuestDraftLoad.Unavailable
        } finally { read?.fill(0) }
    }

    override suspend fun save(draft: GuestKitchenDraft): GuestDraftSave = mutex.withLock {
        currentCoroutineContext().ensureActive()
        if (!writable) return@withLock GuestDraftSave.Unavailable
        val expected = observed?.copyOf()
        forget() // Only a fully returned, verified write can reopen this owner for another edit.
        var dispatched = false
        var accepted: ByteArray? = null
        try {
            accepted = withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                val current = bytes.read()
                try {
                    if (current != null && current.size !in 1..MAX_CIPHERTEXT_BYTES ||
                        !same(current, expected)) throw ChangedDraft()
                } finally { current?.fill(0) }
                val plaintext = GuestKitchenDraftCodec.encode(draft)
                var sealed: ByteArray? = null
                try {
                    sealed = cipher.seal(plaintext).copyOf()
                    if (sealed.size !in 1..MAX_CIPHERTEXT_BYTES) throw GuestDraftCorruptException()
                    currentCoroutineContext().ensureActive()
                    val write = sealed.copyOf()
                    try {
                        dispatched = true
                        bytes.write(write)
                    } finally { write.fill(0) }
                    currentCoroutineContext().ensureActive()
                    val readback = readBounded()
                    try { if (!same(sealed, readback)) throw ChangedDraft() }
                    finally { readback?.fill(0) }
                    sealed.copyOf()
                } finally {
                    plaintext.fill(0)
                    sealed?.fill(0)
                }
            }
            currentCoroutineContext().ensureActive()
            observed = accepted?.copyOf()
            writable = true
            GuestDraftSave.Saved
        } catch (cancelled: CancellationException) {
            forget()
            throw cancelled
        } catch (_: ChangedDraft) {
            GuestDraftSave.OutcomeUnknown
        } catch (_: Exception) {
            if (dispatched) GuestDraftSave.OutcomeUnknown else GuestDraftSave.Unavailable
        } finally {
            expected?.fill(0)
            accepted?.fill(0)
        }
    }

    private fun readBounded(): ByteArray? {
        val read = bytes.read() ?: return null
        try {
            if (read.size !in 1..MAX_CIPHERTEXT_BYTES) throw GuestDraftCorruptException()
            return read.copyOf()
        } finally { read.fill(0) }
    }

    private fun forget() {
        writable = false
        observed?.fill(0)
        observed = null
    }

    override fun toString() = "GuestEncryptedDraftStore(<redacted>)"

    private class ChangedDraft : RuntimeException("Guest draft changed")
    private fun same(first: ByteArray?, second: ByteArray?) =
        if (first == null || second == null) first == null && second == null else first.contentEquals(second)

    companion object { const val MAX_CIPHERTEXT_BYTES = 16_384 }
}
