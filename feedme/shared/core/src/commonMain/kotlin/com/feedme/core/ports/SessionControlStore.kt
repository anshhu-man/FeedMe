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
    suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord>
}
