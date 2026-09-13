package com.feedme.storage

/**
 * Opaque authenticated capability for retiring one exact storage incarnation. It contains no raw
 * scope or credential, but must remain private: possessing an authentic target permits retirement
 * of that incarnation. Persist it in the independent session-control ledger before cleanup.
 * Construction only bounds the encoding; the owning database verifies its install HMAC on use.
 */
class StateRetirementTarget(encoded: ByteArray) {
    private val bytes = encoded.also { require(it.size == ENCODED_SIZE) { "Invalid retirement target" } }.copyOf()

    fun copyForStorage(): ByteArray = bytes.copyOf()

    override fun toString() = "StateRetirementTarget(<redacted>)"

    companion object {
        const val ENCODED_SIZE: Int = 137
    }
}
