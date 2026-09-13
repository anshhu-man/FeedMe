package com.feedme.storage

/**
 * Internal, synchronous native-vault seam. Calls run serialized on the store's background
 * dispatcher. No plaintext/JVM production fallback exists. Implementations must sanitize errors.
 * The install index key is distinct from every independently erasable owner encryption key.
 */
internal interface StateVault {
    /** HMAC-SHA256 under the existing install index key; exactly 32 bytes. */
    fun index(input: ByteArray): ByteArray

    /** Creates a fresh AES-256-GCM key; returns a random 32-character lowercase hex handle. */
    fun createOwnerKey(): String
    fun hasOwnerKey(keyId: String): Boolean

    /** AES-GCM envelope with provider-generated 12-byte nonce and 16-byte authentication tag. */
    fun seal(keyId: String, plaintext: ByteArray, associatedData: ByteArray): ByteArray
    fun open(keyId: String, ciphertext: ByteArray, associatedData: ByteArray): ByteArray

    /** Exact key only; missing is success. Other access/deletion failures must throw. */
    fun deleteOwnerKey(keyId: String)
}

/** Optional pre-write planning capability. No fallback may allocate an unplanned owner key. */
internal interface PlannedStateVault : StateVault {
    /** Fresh random 32-character lowercase hex ID, absent from this vault; creates no key. */
    fun newOwnerKeyId(): String

    /** Creates only this exact absent key ID. Any existing alias, even unusable, must fail. */
    fun createOwnerKey(keyId: String)

    /** Alias presence, including unusable material, for an authenticated exact-plan abort only. */
    fun containsOwnerKey(keyId: String): Boolean = hasOwnerKey(keyId)
}

/** Never attach an underlying provider error, SQL, key alias or private value to this exception. */
internal class StateVaultException : Exception("Private storage vault unavailable")
