package com.feedme.core.ports

/** Independent install-local control plane; never place bearer/refresh/guest tokens in this record. */
class SessionControlRecord(val revision: Long, val payload: PrivateBytes) {
    init { require(revision > 0) }
    override fun toString() = "SessionControlRecord(revision=$revision, payload=<redacted>)"
}

/**
 * Native private non-backup storage with an independently retained key, separate from every
 * retiring owner's data/credential keys. One bounded (32 KiB) record; exact CAS, no unconditional
 * replace, delete or automatic reset. Null is a genuinely uninitialized store, not a recovery
 * instruction. Missing/corrupt previously initialized state must fail closed.
 */
interface SessionControlStore {
    suspend fun read(): PortResult<SessionControlRecord?>
    /**
     * Value acknowledges this invocation's successful durable commit, with revision exactly one
     * greater than the expected revision (1 on initialization). Even identical payloads must make
     * a changed write; no read-only/no-op success. Overflow fails closed. Failure, including
     * OUTCOME_UNKNOWN, is not an acknowledgement: matching readback alone cannot authorize
     * effects. An exact recovery retry must CAS the freshly observed revision/payload again.
     */
    suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord>
}
