package com.feedme.session

import com.feedme.core.ports.PortResult
import com.feedme.core.ports.StorageScope

/**
 * Optional exact CREATE cleanup on an already-open native credential owner. The trusted parent
 * must durably acknowledge explicit composite abort confirmation before invoking this capability;
 * a plan, scope or metadata observation alone is not confirmation or identity authority.
 *
 * Authenticate the native plan and its exact scope before any effect. Preserve poison, lifetime,
 * inventory and newer-selection guards. Never read credential payloads, initialize another store,
 * create keys or close the parent owner. Every retry, including observed ABORTED state, must
 * acknowledge native consumption before reporting success. The parent retains close ownership.
 */
interface CredentialCreatePlanAbort {
    suspend fun abortPlannedCreate(
        scope: StorageScope,
        plan: CredentialCreatePlan,
    ): PortResult<Unit>
}
