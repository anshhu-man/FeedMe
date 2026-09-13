package com.feedme.session

import com.feedme.core.ports.PortResult

/**
 * Retained, exact scope-and-CREATE-plan owner constructed before native I/O. The caller must
 * retain this object through open failure/cancellation and until close acknowledges release.
 * Failed opening is close-only; it cannot initialize, rebind or silently try opening again.
 *
 * Only existing authenticated metadata is inspected; no credential payload or ordinary store
 * handle is exposed. Abort requires independently acknowledged explicit intent, not inspection.
 * Retry close only on this same owner: an unacknowledged platform close after invalidation may
 * remain a terminal process-only failure. Never substitute a new opener to clean an unclosed
 * owner, and never infer successful opening or abort from eventual close success. Abort failure
 * alone does not revoke this owner; underlying native mutation poisoning still blocks retries.
 */
interface CredentialCreateRecoveryOwner {
    suspend fun open(): PortResult<Unit>
    suspend fun inspect(): PortResult<CredentialCreatePlanObservation>
    suspend fun abort(): PortResult<Unit>
    suspend fun close(): PortResult<Unit>
}
