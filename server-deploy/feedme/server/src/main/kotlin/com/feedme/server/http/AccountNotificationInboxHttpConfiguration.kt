package com.feedme.server.http

import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.identity.AccountNotificationInboxStore
import kotlinx.coroutines.CoroutineDispatcher

class AccountNotificationInboxHttpConfiguration internal constructor(
    internal val store: AccountNotificationInboxStore,
    internal val verifier: SupabaseUserAccessVerifier,
    internal val databaseDispatcher: CoroutineDispatcher,
) {
    override fun toString() = "AccountNotificationInboxHttpConfiguration(<redacted>)"
    companion object { internal const val MAX_REQUEST_BYTES = 1024 }
}
