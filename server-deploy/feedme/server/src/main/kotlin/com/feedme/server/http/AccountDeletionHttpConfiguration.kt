package com.feedme.server.http

import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.identity.AccountDeletionService

/** Explicit serving composition only. Optional runtime policy and separately reviewed
 * serving privileges are required. No enabling default, database grant, worker credential
 * or deletion-completion claim is supplied by this adapter. */
class AccountDeletionHttpConfiguration internal constructor(
    internal val service: AccountDeletionService,
    internal val verifier: SupabaseUserAccessVerifier,
) {
    override fun toString() = "AccountDeletionHttpConfiguration(<redacted>)"
    internal companion object { const val MAX_REQUEST_BYTES = 16_384 }
}
