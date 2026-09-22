package com.feedme.server.http

import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.social.posts.AccountPostPlacementStore
import kotlinx.coroutines.CoroutineDispatcher

class AccountPostPlacementHttpConfiguration internal constructor(internal val store: AccountPostPlacementStore,
    internal val verifier: SupabaseUserAccessVerifier, internal val databaseDispatcher: CoroutineDispatcher) {
    override fun toString() = "AccountPostPlacementHttpConfiguration(<redacted>)"
}
