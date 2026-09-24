package com.feedme.app

import com.feedme.app.guest.GuestDraftLoad
import com.feedme.app.guest.GuestDraftSave
import com.feedme.app.guest.GuestKitchenDraft
import com.feedme.app.guest.GuestKitchenDraftCodec
import com.feedme.app.guest.GuestKitchenDraftStore
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

/** A single process-owned local guest draft kept in the iOS Keychain. The item is
 * device-only and available only while the device is unlocked. It is not an account
 * credential, cloud-sync record or permission to create an account/session. Existing
 * unreadable or future data is preserved; there is deliberately no reset/delete path. */
@OptIn(ExperimentalForeignApi::class)
internal class IosGuestDraftStore : GuestKitchenDraftStore {
    private val mutex = Mutex()
    private var writable = false
    private var observed: ByteArray? = null

    override suspend fun load(): GuestDraftLoad = mutex.withLock {
        forget()
        var bytes: ByteArray? = null
        try {
            currentCoroutineContext().ensureActive()
            bytes = read()
            currentCoroutineContext().ensureActive()
            val result = if (bytes == null) GuestDraftLoad.Empty else {
                if (bytes.size !in 1..GuestKitchenDraftCodec.MAX_ENCODED_BYTES) GuestDraftLoad.Corrupt
                else GuestKitchenDraftCodec.decode(bytes)
            }
            if (result == GuestDraftLoad.Empty || result is GuestDraftLoad.Loaded) {
                observed = bytes?.copyOf()
                writable = true
            }
            result
        } catch (cancelled: CancellationException) {
            forget()
            throw cancelled
        } catch (_: Exception) {
            GuestDraftLoad.Unavailable
        } finally {
            bytes?.fill(0)
        }
    }

    override suspend fun save(draft: GuestKitchenDraft): GuestDraftSave = mutex.withLock {
        currentCoroutineContext().ensureActive()
        if (!writable) return@withLock GuestDraftSave.Unavailable
        val expected = observed?.copyOf()
        forget()
        var encoded: ByteArray? = null
        var dispatched = false
        try {
            val current = read()
            try {
                if (!same(current, expected)) return@withLock GuestDraftSave.OutcomeUnknown
            } finally {
                current?.fill(0)
            }
            encoded = GuestKitchenDraftCodec.encode(draft)
            currentCoroutineContext().ensureActive()
            dispatched = true
            write(encoded, expected == null)
            currentCoroutineContext().ensureActive()
            val confirmed = read()
            try {
                if (!same(encoded, confirmed)) return@withLock GuestDraftSave.OutcomeUnknown
            } finally {
                confirmed?.fill(0)
            }
            observed = encoded.copyOf()
            writable = true
            GuestDraftSave.Saved
        } catch (cancelled: CancellationException) {
            forget()
            throw cancelled
        } catch (_: Exception) {
            if (dispatched) GuestDraftSave.OutcomeUnknown else GuestDraftSave.Unavailable
        } finally {
            expected?.fill(0)
            encoded?.fill(0)
        }
    }

    private fun read(): ByteArray? = memScoped {
        val result = alloc<COpaquePointerVar>()
        val status = withDictionary(base = true, returnData = true) { query ->
            SecItemCopyMatching(query, result.ptr)
        }
        if (status == errSecItemNotFound) return@memScoped null
        check(status == errSecSuccess)
        val retained = checkNotNull(result.value)
        try {
            val length = CFDataGetLength(retained.reinterpret())
            check(length in 1..GuestKitchenDraftCodec.MAX_ENCODED_BYTES.toLong())
            val source = checkNotNull(CFDataGetBytePtr(retained.reinterpret()))
            ByteArray(length.toInt()).also { output ->
                output.usePinned { pinned -> memcpy(pinned.addressOf(0), source, length.toULong()) }
            }
        } finally {
            CFRelease(retained)
        }
    }

    private fun write(bytes: ByteArray, absent: Boolean) {
        check(bytes.size in 1..GuestKitchenDraftCodec.MAX_ENCODED_BYTES)
        val status = if (absent) {
            withDictionary(base = true, accessible = true, data = bytes) { SecItemAdd(it, null) }
        } else {
            withDictionary(base = true) { query ->
                withDictionary(data = bytes) { updates -> SecItemUpdate(query, updates) }
            }
        }
        check(status == errSecSuccess)
    }

    private inline fun <T> withDictionary(
        base: Boolean = false,
        accessible: Boolean = false,
        returnData: Boolean = false,
        data: ByteArray? = null,
        block: (CFDictionaryRef) -> T,
    ): T = memScoped {
        val dictionary = checkNotNull(CFDictionaryCreateMutable(null, 0, null, null))
        val service = if (base) checkNotNull(CFStringCreateWithCString(null, SERVICE, kCFStringEncodingUTF8)) else null
        val account = if (base) checkNotNull(CFStringCreateWithCString(null, ACCOUNT, kCFStringEncodingUTF8)) else null
        var valueData = data?.usePinned { pinned ->
            checkNotNull(CFDataCreate(null, pinned.addressOf(0).reinterpret(), data.size.toLong()))
        }
        try {
            if (base) {
                CFDictionarySetValue(dictionary, kSecClass, kSecClassGenericPassword)
                CFDictionarySetValue(dictionary, kSecAttrService, service)
                CFDictionarySetValue(dictionary, kSecAttrAccount, account)
            }
            if (accessible) CFDictionarySetValue(dictionary, kSecAttrAccessible,
                kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
            if (returnData) {
                CFDictionarySetValue(dictionary, kSecReturnData, kCFBooleanTrue)
                CFDictionarySetValue(dictionary, kSecMatchLimit, kSecMatchLimitOne)
            }
            if (valueData != null) CFDictionarySetValue(dictionary, kSecValueData, valueData)
            block(dictionary)
        } finally {
            valueData?.let(::CFRelease)
            account?.let(::CFRelease)
            service?.let(::CFRelease)
            CFRelease(dictionary)
            valueData = null
        }
    }

    private fun forget() {
        writable = false
        observed?.fill(0)
        observed = null
    }

    private fun same(first: ByteArray?, second: ByteArray?): Boolean =
        if (first == null || second == null) first == null && second == null else first.contentEquals(second)

    override fun toString() = "IosGuestDraftStore(<redacted>)"

    private companion object {
        const val SERVICE = "FeedMe.local-guest-draft.v1"
        const val ACCOUNT = "current"
    }
}
