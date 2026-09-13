package com.feedme.storage

import com.feedme.core.ports.PortResult

/** Observations only: neither a login capability nor permission to discard nonempty private data. */
enum class StateActivationStatus {
    PREPARED, PARTIAL, SELECTED_EMPTY, SELECTED_NONEMPTY, ABORTING, ABORTED,
}

class StateActivationInspection(val status: StateActivationStatus) {
    override fun toString(): String = "StateActivationInspection(<redacted>)"
}

/**
 * Owns one existing database connection and one authenticated immutable setup plan. No ordinary
 * activation, record, key-creation or general-retirement capability escapes this handle. The
 * session owner must persist explicit abort intent independently before calling abort; inspection
 * alone never authorizes it. A successful abort is cleanup acknowledgement, never login.
 */
interface StateActivationRecoveryHandle {
    suspend fun inspect(): PortResult<StateActivationInspection>
    suspend fun abort(): PortResult<Unit>
    suspend fun close(): PortResult<Unit>
}
