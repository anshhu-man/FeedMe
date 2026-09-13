package com.feedme.storage

import com.feedme.core.ports.PortResult
import com.feedme.core.ports.PrivateBytes

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
    /** Empty-only legacy operation; never deletes even a valid activation binding. */
    suspend fun abort(): PortResult<Unit>
    /**
     * Trusted composite owner only, AFTER independently acknowledged exact abort confirmation.
     * Allows zero rows or the sole schema2 activation binding matching these full expected bytes.
     * The owner must derive those bytes from its original authenticated composite intent; this
     * low-level store cannot authenticate provider identity, configuration or confirmation itself.
     * No ordinary private row/tombstone may be removed, and inspection alone grants no authority.
     */
    suspend fun abortBound(expectedBinding: PrivateBytes): PortResult<Unit>
    suspend fun close(): PortResult<Unit>
}
