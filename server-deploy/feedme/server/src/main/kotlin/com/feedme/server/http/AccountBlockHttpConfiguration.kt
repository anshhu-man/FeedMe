package com.feedme.server.http

import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.social.AccountBlockStore
import kotlinx.coroutines.CoroutineDispatcher

/** Account safety has its own explicit composition, independent of social creation.
 * Signature ingress does not replace the store's current account/device checks. */
class AccountBlockHttpConfiguration internal constructor(
    internal val store: AccountBlockStore,
    internal val verifier: SupabaseUserAccessVerifier,
    internal val databaseDispatcher: CoroutineDispatcher,
) {
    override fun toString() = "AccountBlockHttpConfiguration(<redacted>)"

    companion object {
        internal const val MAX_REQUEST_BYTES = 4096
    }
}
