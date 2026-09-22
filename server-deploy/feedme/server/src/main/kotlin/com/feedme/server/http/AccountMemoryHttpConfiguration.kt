package com.feedme.server.http

import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.memory.AccountFeedbackStore
import com.feedme.server.memory.AccountMemoryStore
import kotlinx.coroutines.CoroutineDispatcher

/** Optional concrete account owners only; not a principal callback or guest route adapter. */
class AccountMemoryHttpConfiguration internal constructor(internal val feedback: AccountFeedbackStore,
    internal val memories: AccountMemoryStore, internal val verifier: SupabaseUserAccessVerifier,
    internal val databaseDispatcher: CoroutineDispatcher) {
    override fun toString() = "AccountMemoryHttpConfiguration(<redacted>)"
    internal companion object { const val MAX_REQUEST_BYTES = 16_384 }
}
