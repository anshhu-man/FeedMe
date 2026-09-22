package com.feedme.server.http

import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.social.conversations.AccountConversationStore
import kotlinx.coroutines.CoroutineDispatcher

/** Explicit optional account composition. Constructing it never installs schema or grants. */
class AccountConversationHttpConfiguration internal constructor(
    internal val store: AccountConversationStore,
    internal val verifier: SupabaseUserAccessVerifier,
    internal val databaseDispatcher: CoroutineDispatcher,
) {
    override fun toString() = "AccountConversationHttpConfiguration(<redacted>)"
    companion object { internal const val MAX_REQUEST_BYTES = 16384 }
}
