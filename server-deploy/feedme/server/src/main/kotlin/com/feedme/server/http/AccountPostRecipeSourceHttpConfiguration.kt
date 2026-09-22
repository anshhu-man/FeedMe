package com.feedme.server.http

import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.social.posts.AccountPostRecipeSourceStore
import kotlinx.coroutines.CoroutineDispatcher

class AccountPostRecipeSourceHttpConfiguration internal constructor(
    internal val store: AccountPostRecipeSourceStore,
    internal val verifier: SupabaseUserAccessVerifier,
    internal val databaseDispatcher: CoroutineDispatcher,
) {
    override fun toString() = "AccountPostRecipeSourceHttpConfiguration(<redacted>)"
}
