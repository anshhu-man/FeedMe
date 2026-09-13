package com.feedme.storage

/**
 * Opaque pre-write ownership of one proposed private-data activation. Keep it in the independent
 * encrypted setup journal, never logs/preferences or ordinary private records. The constructor
 * only bounds the encoding; the database authenticates the issuing install/namespace on use.
 * This is not a session lease, record handle, global reset or general retirement capability.
 */
class StateActivationPlan(encoded: ByteArray) {
    private val bytes = encoded.also {
        require(it.size == ENCODED_SIZE) { "Invalid activation plan" }
    }.copyOf()

    fun copyForStorage(): ByteArray = bytes.copyOf()

    override fun toString() = "StateActivationPlan(<redacted>)"

    companion object {
        const val ENCODED_SIZE = 170
    }
}

internal data class StateActivationPlanRecord(
    val ownerTag: String,
    val priorGeneration: Long,
    val priorKeyId: String?,
    val keyId: String,
    val authenticationMac: ByteArray,
) {
    val selectedGeneration: Long get() = priorGeneration + 1
    val consumedGeneration: Long get() = priorGeneration + 2

    override fun toString() = "StateActivationPlanRecord(<redacted>)"
}

/** Never expose malformed payload, scope, key ID, provider exception or an underlying cause. */
internal class StateActivationPlanFormatException : Exception("Invalid activation plan")
