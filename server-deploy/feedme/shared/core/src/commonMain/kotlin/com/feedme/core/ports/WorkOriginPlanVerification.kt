package com.feedme.core.ports

/**
 * Read-only verification against an existing native work ledger. It cannot sign a new plan,
 * initialize a store or grant session/scheduling authority. Exact session schema/predecessor
 * validation remains the caller's responsibility; a valid proof alone is not an idle ledger.
 */
interface WorkOriginPlanVerification {
    suspend fun verifyOriginPlan(expectedRevision: Long, proposal: PrivateBytes, proof: PrivateBytes): PortResult<Unit>

    /** Also requires [expected] to be the exact current authenticated predecessor bytes. */
    suspend fun verifyOriginPredecessor(expected: SessionControlRecord, proposal: PrivateBytes, proof: PrivateBytes): PortResult<Unit>
}
