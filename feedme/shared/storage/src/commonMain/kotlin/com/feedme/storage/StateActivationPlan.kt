package com.feedme.storage

import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult

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

        /**
         * Decodes only the exact canonical structure, without a database, vault or native key.
         * Success does not authenticate the issuing install, the owner scope or the plan MAC;
         * the owning native store must still validate them before any use of this capability.
         */
        fun fromStorage(encoded: ByteArray): PortResult<StateActivationPlan> {
            if (encoded.size != ENCODED_SIZE) return PortResult.Failure(FailureReason.INVALID_DATA)
            val candidate = StateActivationPlan(encoded)
            var record: StateActivationPlanRecord? = null
            var canonical: StateActivationPlan? = null
            var retained = false
            return try {
                record = StateActivationPlanCodec.decode(candidate)
                canonical = StateActivationPlanCodec.encode(record)
                if (!candidate.bytes.contentEquals(canonical.bytes)) {
                    PortResult.Failure(FailureReason.INVALID_DATA)
                } else {
                    retained = true
                    PortResult.Value(candidate)
                }
            } catch (_: StateActivationPlanFormatException) {
                PortResult.Failure(FailureReason.INVALID_DATA)
            } finally {
                record?.authenticationMac?.fill(0)
                canonical?.bytes?.fill(0)
                if (!retained) candidate.bytes.fill(0)
            }
        }
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
