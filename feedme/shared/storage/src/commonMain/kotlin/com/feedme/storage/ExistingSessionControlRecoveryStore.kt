package com.feedme.storage

import com.feedme.core.ports.PortResult
import com.feedme.core.ports.SessionControlStore

/** Trusted startup composition boundary, not a general application storage API. */
@RequiresOptIn(
    message = "Control recovery is a trusted composition SPI. Retain its owner through failed acquisition and close; expose no raw control store to applications.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class SessionControlRecoveryCompositionApi

/**
 * Retained existing-only ownership of the independent, sole encrypted control ledger. Construct
 * and retain before open; failed/cancelled admitted opening is close-only. Missing files, keys,
 * schema, owner or record never authorize initialization, migration, normal resume or key GC.
 * Every read/CAS retains the captured owner generation/key and exact fixed ledger-only shape.
 * Bounded plaintext is exposed only to the trusted session codec; storage does not interpret a
 * Pending/Complete state, authenticate nested plans, infer confirmation or grant a lease.
 *
 * Keep this owner through the final changed control acknowledgement. Resource owners must be
 * closed in the order required by the session protocol, and this owner's close is acknowledged
 * separately. A failed close must not discard the owner or silently roll back completed control.
 * An ambiguous native close remains a terminal process repair gate, not a successful retry.
 */
@SessionControlRecoveryCompositionApi
interface ExistingSessionControlRecoveryStore : SessionControlStore {
    suspend fun open(): PortResult<Unit>
    suspend fun close(): PortResult<Unit>
}
