package com.feedme.server.http

import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.social.posts.AccountRemixReadStore
import kotlinx.coroutines.CoroutineDispatcher

class AccountRemixReadHttpConfiguration internal constructor(
    internal val store: AccountRemixReadStore,
    internal val verifier: SupabaseUserAccessVerifier,
    internal val databaseDispatcher: CoroutineDispatcher,
) {
    override fun toString() = "AccountRemixReadHttpConfiguration(<redacted>)"
}
