package com.feedme.session

import com.feedme.core.ports.*

/**
 * Native authenticated CREATE capability, not an identity proof or a user-visible token.
 * Persist only in independent encrypted session control BEFORE credential material is written.
 * Structural decoding does not authenticate this capability: its issuing native store must
 * verify the install/namespace MAC, revision and payload before any effect. No credentials or
 * raw owner identifiers are contained in its serialized projection.
 */
class CredentialCreatePlan private constructor(private val encoded: PrivateBytes) {
    fun copyForStorage(): PrivateBytes = PrivateBytes(encoded.copyForCodec())
    override fun toString() = "CredentialCreatePlan(<redacted>)"

    companion object {
        fun fromStorage(bytes: PrivateBytes): PortResult<CredentialCreatePlan> = try {
            val record = CredentialCreatePlanCodec.decode(bytes)
            val canonical = CredentialCreatePlanCodec.encode(record)
            val a = bytes.copyForCodec(); val b = canonical.copyForCodec()
            try {
                if (!a.contentEquals(b)) PortResult.Failure(FailureReason.INVALID_DATA)
                else PortResult.Value(CredentialCreatePlan(canonical))
            } finally { a.fill(0); b.fill(0) }
        } catch (_: Exception) { PortResult.Failure(FailureReason.INVALID_DATA) }

        internal fun create(record: CredentialCreatePlanRecord): CredentialCreatePlan =
            CredentialCreatePlan(CredentialCreatePlanCodec.encode(record))
    }
}

/** Native owner only. A caller must persist the plan independently before calling commit. */
interface PlannedCredentialCreateStore : IncarnationCredentialStore {
    suspend fun planCreate(expectedSlotRevision: Long, credentials: StoredCredentials): PortResult<CredentialCreatePlan>
    suspend fun commitPlannedCreate(plan: CredentialCreatePlan, credentials: StoredCredentials): PortResult<CredentialSnapshot>
}

/** SELECTED describes authenticated selection only; it does not prove usable credentials. */
enum class CredentialCreateRecoveryStatus { PREPARED, PARTIAL, SELECTED, ABORTING, ABORTED }

/**
 * Restricted existing-only handle pinned to one authenticated CREATE plan. It cannot expose
 * credentials, activate identity, refresh, create a different account or initialize lost stores.
 * Abort requires an explicit durable owner request; inspect is not confirmation. Always close.
 */
interface CredentialCreateRecoveryHandle {
    suspend fun inspect(): PortResult<CredentialCreateRecoveryStatus>
    suspend fun abort(): PortResult<Unit>
    suspend fun close(): PortResult<Unit>
}

fun interface CredentialCreateRecoveryFactory {
    suspend fun open(plan: CredentialCreatePlan): PortResult<CredentialCreateRecoveryHandle>
}

internal data class CredentialCreatePlanRecord(
    val expectedSlotRevision: Long,
    val incarnation: String,
    val target: String,
    val payloadMac: String,
    val authenticationMac: String,
) {
    val snapshotRevision: Long get() = expectedSlotRevision + 1
    val abortedRevision: Long get() = expectedSlotRevision + 2
    override fun toString() = "CredentialCreatePlanRecord(<redacted>)"
}

internal class CredentialCreatePlanFormatException : Exception("Credential create plan unavailable")
