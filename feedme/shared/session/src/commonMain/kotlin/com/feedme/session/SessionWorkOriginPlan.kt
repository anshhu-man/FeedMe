package com.feedme.session

import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.PrivateBytes
import com.feedme.core.ports.StorageScope

/** Read-only authenticated observation, never a write acknowledgement or session authority. */
enum class SessionWorkOriginPlanStatus { PREPARED, SELECTED, SEALED, ABORTED }

/**
 * Advisory exact-plan observation, not a write acknowledgement or permission to publish/abort.
 * The prepared predecessor is authenticated byte-for-byte; selected/sealed/aborted payloads are fixed
 * canonically by the authenticated plan. Status and revision therefore identify the observed
 * work record without exposing its private raw payload. A later action must revalidate it.
 */
class SessionWorkOriginObservation(val status: SessionWorkOriginPlanStatus, val revision: Long) {
    init { require(revision > 0) }
    override fun toString() = "SessionWorkOriginObservation(<redacted>)"
}

/**
 * Exact install-local work selection intent, never a lease or native scheduling permission.
 * Persist only in encrypted setup control. Structural decoding does not authenticate its proof;
 * the issuing work store must verify its exact ledger/owner binding before selection or replay.
 */
class SessionWorkOriginPlan private constructor(private val encoded: PrivateBytes) {
    fun copyForStorage(): PrivateBytes = PrivateBytes(encoded.copyForCodec())
    override fun toString() = "SessionWorkOriginPlan(<redacted>)"

    companion object {
        fun fromStorage(bytes: PrivateBytes): PortResult<SessionWorkOriginPlan> = try {
            val record = SessionWorkOriginPlanCodec.decode(bytes)
            val canonical = SessionWorkOriginPlanCodec.encode(record)
            val supplied = bytes.copyForCodec()
            val expected = canonical.copyForCodec()
            try {
                if (!supplied.contentEquals(expected)) PortResult.Failure(FailureReason.INVALID_DATA)
                else PortResult.Value(SessionWorkOriginPlan(canonical))
            } finally { supplied.fill(0); expected.fill(0) }
        } catch (_: Exception) { PortResult.Failure(FailureReason.INVALID_DATA) }

        internal fun create(record: SessionWorkOriginPlanRecord): SessionWorkOriginPlan =
            SessionWorkOriginPlan(SessionWorkOriginPlanCodec.encode(record))
    }
}

internal data class SessionWorkOriginPlanRecord(
    val expectedRevision: Long,
    val scope: StorageScope,
    val origin: String,
    val proof: PrivateBytes,
) {
    override fun toString() = "SessionWorkOriginPlanRecord(<redacted>)"
}

internal class SessionWorkOriginPlanFormatException : Exception("Work origin plan unavailable")
