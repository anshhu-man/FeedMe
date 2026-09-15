package com.feedme.storage

import com.feedme.core.ports.PortResult
import com.feedme.core.ports.SessionControlStore
import com.feedme.core.ports.WorkOriginPlanVerification

/** Explicit cross-module composition boundary, not an application-facing recovery API. */
@RequiresOptIn(
    message = "Work-recovery storage is a trusted composition SPI. Expose only the scope/plan-pinned session recovery owner to applications.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class WorkRecoveryCompositionApi

/**
 * Retained, existing-only access to the sole independent native work record. Construction owns
 * cleanup before any I/O; retain this object across failed opening, cancellation and failed close.
 * Opening validates exact V2 schema and fixed owner/record inventory without initialization,
 * migration, resume, GC or key allocation. All operations retain that owner generation/key.
 *
 * This intentionally explicit cross-module SPI exposes encrypted-ledger plaintext to the trusted
 * session codec. It is NOT the public application facade: only the session layer can authenticate
 * the original scope/plan, recognize strict PREPARED/SELECTED/SEALED/ABORTED states and authorize
 * an exact abort. Native proof alone cannot establish an empty predecessor. There is no signing,
 * ordinary owner handle, scheduler, lease, repair or arbitrary-record API.
 *
 * Failed/cancelled admitted opening is close-only. Only a successful close acknowledgement
 * releases ownership; ambiguous driver/platform close remains a terminal process repair gate.
 */
@WorkRecoveryCompositionApi
interface ExistingSessionWorkRecoveryStore : SessionControlStore, WorkOriginPlanVerification {
    suspend fun open(): PortResult<Unit>
    suspend fun close(): PortResult<Unit>
}
