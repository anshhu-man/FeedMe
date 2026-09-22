package com.feedme.server.http

import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.social.reciperequests.AccountRecipeRequestStore
import kotlinx.coroutines.CoroutineDispatcher

/** Optional serving composition only; never installs a schema, role or grant. */
class AccountRecipeRequestHttpConfiguration internal constructor(
    internal val store: AccountRecipeRequestStore,
    internal val verifier: SupabaseUserAccessVerifier,
    internal val databaseDispatcher: CoroutineDispatcher,
) {
    override fun toString() = "AccountRecipeRequestHttpConfiguration(<redacted>)"
    companion object { internal const val MAX_REQUEST_BYTES = 8192 }
}
