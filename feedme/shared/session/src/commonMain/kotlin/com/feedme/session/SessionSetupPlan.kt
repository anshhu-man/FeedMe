package com.feedme.session

import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.PrivateBytes
import com.feedme.core.ports.StorageScope
import com.feedme.storage.StateActivationPlan

/**
 * Exact composite setup intent for independent encrypted control, not an activation capability.
 * Decoding verifies canonical structure and the visible work scope only. Credential/data owner
 * bindings are opaque: their native issuers must authenticate them before any selection or repair.
 * This object neither verifies a provider/configuration nor permits restoring a lease or secrets.
 */
class SessionSetupPlan private constructor(private val encoded: PrivateBytes) {
    fun copyForStorage(): PrivateBytes = PrivateBytes(encoded.copyForCodec())
    override fun toString() = "SessionSetupPlan(<redacted>)"

    companion object {
        fun fromStorage(bytes: PrivateBytes): PortResult<SessionSetupPlan> = try {
            val record = SessionSetupPlanCodec.decode(bytes)
            val canonical = SessionSetupPlanCodec.encode(record)
            val supplied = bytes.copyForCodec()
            val expected = canonical.copyForCodec()
            try {
                if (!supplied.contentEquals(expected)) PortResult.Failure(FailureReason.INVALID_DATA)
                else PortResult.Value(SessionSetupPlan(canonical))
            } finally { supplied.fill(0); expected.fill(0) }
        } catch (_: Exception) { PortResult.Failure(FailureReason.INVALID_DATA) }

        internal fun create(record: SessionSetupPlanRecord): SessionSetupPlan =
            SessionSetupPlan(SessionSetupPlanCodec.encode(record))
    }
}

internal data class SessionSetupPlanRecord(
    val operationId: String,
    val scope: StorageScope,
    val configurationBinding: String,
    val credentialPlan: CredentialCreatePlan,
    val dataPlan: StateActivationPlan,
    val workOriginPlan: SessionWorkOriginPlan,
) {
    override fun toString() = "SessionSetupPlanRecord(<redacted>)"
}

internal class SessionSetupPlanFormatException : Exception("Session setup plan unavailable")
