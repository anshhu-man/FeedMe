package com.feedme.server.http

import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.identity.AccountNotificationStore
import kotlinx.coroutines.CoroutineDispatcher

class AccountNotificationHttpConfiguration internal constructor(internal val store:AccountNotificationStore,
    internal val verifier:SupabaseUserAccessVerifier,internal val databaseDispatcher:CoroutineDispatcher) {
    override fun toString()="AccountNotificationHttpConfiguration(<redacted>)"
}
