package com.feedme.storage

import com.feedme.core.ports.PortResult
import com.feedme.core.ports.PrivateBytes

/**
 * Retained lifetime of one existing-only, scope-and-plan-pinned private-data recovery attempt.
 * Keep this owner before calling open, including when open fails or is cancelled. No ordinary
 * private store, activation, migration, initialization, secret enumeration or key GC is exposed.
 * A failed/cancelled open is close-only: never call a new factory to bypass an unresolved close.
 * close must acknowledge every owned release before this owner can be discarded. It is retryable
 * for known unfinished stages; an ambiguous platform close may require explicit process repair.
 * These component operations do not grant user consent or complete a composite setup journal.
 */
interface StateActivationRecoveryOwner {
    suspend fun open(): PortResult<Unit>
    suspend fun inspect(): PortResult<StateActivationPlanObservation>
    suspend fun binding(): PortResult<StateRecordInspection>
    suspend fun abort(expectedBinding: PrivateBytes? = null): PortResult<Unit>
    suspend fun close(): PortResult<Unit>
}
