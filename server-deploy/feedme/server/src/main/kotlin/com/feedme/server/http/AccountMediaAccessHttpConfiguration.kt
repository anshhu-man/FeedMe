package com.feedme.server.http

import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.media.access.AccountMediaAccessStore
import kotlinx.coroutines.CoroutineDispatcher

/** Explicit optional issuance plus same-instance verified-byte delivery. No startup fetch,
 * worker activation, bucket change or public-storage fallback. Store owns its provider client. */
class AccountMediaAccessHttpConfiguration internal constructor(
    internal val store: AccountMediaAccessStore,
    internal val verifier: SupabaseUserAccessVerifier,
    internal val databaseDispatcher: CoroutineDispatcher,
) {
    override fun toString() = "AccountMediaAccessHttpConfiguration(<redacted>)"
}
