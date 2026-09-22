package com.feedme.server.http

import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.social.posts.AccountPostDeletionStore
import kotlinx.coroutines.CoroutineDispatcher

class AccountPostDeletionHttpConfiguration internal constructor(internal val store: AccountPostDeletionStore,
    internal val verifier: SupabaseUserAccessVerifier, internal val databaseDispatcher: CoroutineDispatcher) {
    override fun toString() = "AccountPostDeletionHttpConfiguration(<redacted>)"
}
