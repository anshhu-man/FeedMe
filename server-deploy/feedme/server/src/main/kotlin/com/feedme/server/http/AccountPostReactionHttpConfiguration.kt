package com.feedme.server.http

import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.social.posts.AccountPostReactionStore
import kotlinx.coroutines.CoroutineDispatcher

class AccountPostReactionHttpConfiguration internal constructor(internal val store: AccountPostReactionStore,
    internal val verifier: SupabaseUserAccessVerifier, internal val databaseDispatcher: CoroutineDispatcher) {
    override fun toString() = "AccountPostReactionHttpConfiguration(<redacted>)"
}
