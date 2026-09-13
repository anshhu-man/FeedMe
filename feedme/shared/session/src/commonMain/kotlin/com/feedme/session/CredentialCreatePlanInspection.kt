package com.feedme.session

import com.feedme.core.ports.PortResult
import com.feedme.core.ports.PrivateBytes
import com.feedme.core.ports.StorageScope

/**
 * Optional trusted native metadata observation on an already-open credential owner. This must
 * authenticate the exact plan and its scope without reading credential payloads, creating keys,
 * opening another manager, changing files or acknowledging durability. It grants no recovery,
 * abort, identity or credential-usability authority; poisoned and closed owners remain blocked.
 */
interface CredentialCreatePlanInspection {
    suspend fun inspectPlannedCreate(
        scope: StorageScope,
        plan: CredentialCreatePlan,
    ): PortResult<CredentialCreatePlanObservation>
}

/**
 * Comparison metadata only, not a capability. Public construction supports protocol adapters and
 * tests; constructing this value does not authenticate anything. The fingerprint covers native
 * authenticated selection metadata and exact artifact/key presence, not credential file contents.
 * Thus SELECTED can coexist with missing or unusable credential material.
 */
class CredentialCreatePlanObservation(
    val status: CredentialCreateRecoveryStatus,
    val fingerprint: PrivateBytes,
) {
    init {
        val bytes = fingerprint.copyForCodec()
        try { require(bytes.size == 32) } finally { bytes.fill(0) }
    }

    override fun toString() = "CredentialCreatePlanObservation(<redacted>)"
}
