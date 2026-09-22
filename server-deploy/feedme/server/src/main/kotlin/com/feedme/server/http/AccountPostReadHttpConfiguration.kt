package com.feedme.server.http

import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.social.posts.AccountPostReadStore
import kotlinx.coroutines.CoroutineDispatcher

/** Explicit read-only social composition. The concrete verifier establishes token facts;
 * the real store resolves and rechecks account/device and current object/content authority.
 * No publication, protected media issuance, runtime configuration or default enablement. */
class AccountPostReadHttpConfiguration internal constructor(
    internal val store: AccountPostReadStore,
    internal val verifier: SupabaseUserAccessVerifier,
    internal val databaseDispatcher: CoroutineDispatcher,
) {
    override fun toString() = "AccountPostReadHttpConfiguration(<redacted>)"
}
