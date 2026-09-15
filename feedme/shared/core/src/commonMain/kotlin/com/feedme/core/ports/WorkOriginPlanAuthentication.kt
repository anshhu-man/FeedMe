package com.feedme.core.ports

/**
 * Optional, work-ledger-only proof capability. Not a generic MAC service, identity verifier,
 * reservation, lease or permission to write/schedule. The work coordinator owns the bounded
 * proposal format and must independently require an exact idle predecessor or selected plan.
 * Implementations bind proofs to the existing install key, exact captured work-owner incarnation,
 * work-record identity, original revision and exact predecessor bytes. They never initialize,
 * create keys, collect keys, repair missing state or expose their database/vault.
 *
 * Proposals are 1..4096 bytes; revisions are 1..Long.MAX_VALUE-2. A proof is exactly 64 bytes:
 * a domain-separated predecessor MAC followed by a proposal MAC authenticating that predecessor
 * MAC. Persist only as part of the opaque plan in independent encrypted session control.
 */
interface WorkOriginPlanAuthentication : WorkOriginPlanVerification {
    /** Read-only. Exact-current record comparison and signing share one storage read transaction. */
    suspend fun signOriginPlan(expected: SessionControlRecord, proposal: PrivateBytes): PortResult<PrivateBytes>

    /**
     * Read-only proof authentication against this handle's still-current work owner. The original
     * predecessor need not remain selected; this operation alone grants no replay permission.
     */
    override suspend fun verifyOriginPlan(expectedRevision: Long, proposal: PrivateBytes, proof: PrivateBytes): PortResult<Unit>

    /** Also requires [expected] to be the exact current record and match the authenticated predecessor. */
    override suspend fun verifyOriginPredecessor(expected: SessionControlRecord, proposal: PrivateBytes, proof: PrivateBytes): PortResult<Unit>
}
