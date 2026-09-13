package com.feedme.storage

import com.feedme.core.ports.PrivateBytes

/**
 * Trusted owner comparison data, not a write acknowledgement or activation/cleanup authority.
 * The fingerprint binds authenticated plan/owner/abort-receipt metadata and key presence, not
 * key usability or private-record contents. A selected nonempty owner requires separate exact
 * binding inspection. Constructing this DTO does not authenticate its caller-supplied contents.
 */
class StateActivationPlanObservation(val status: StateActivationStatus, val fingerprint: PrivateBytes) {
    init {
        val bytes = fingerprint.copyForCodec()
        try { require(bytes.size == 32) { "Invalid activation observation" } }
        finally { bytes.fill(0) }
    }
    override fun toString() = "StateActivationPlanObservation(<redacted>)"
}
