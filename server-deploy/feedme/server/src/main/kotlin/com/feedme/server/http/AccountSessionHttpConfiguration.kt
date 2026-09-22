package com.feedme.server.http

import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.identity.AccountSessionStore
import kotlinx.coroutines.CoroutineDispatcher

class AccountSessionHttpConfiguration internal constructor(internal val store:AccountSessionStore,
    internal val verifier:SupabaseUserAccessVerifier,internal val databaseDispatcher:CoroutineDispatcher) {
    override fun toString()="AccountSessionHttpConfiguration(<redacted>)"
}
