package com.feedme.server.http

import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.reuse.AccountReuseStore
import kotlinx.coroutines.CoroutineDispatcher

/** Explicit account composition only; absent configuration leaves the route unavailable. */
class AccountReuseHttpConfiguration internal constructor(internal val store: AccountReuseStore,
    internal val verifier: SupabaseUserAccessVerifier, internal val databaseDispatcher: CoroutineDispatcher) {
    override fun toString() = "AccountReuseHttpConfiguration(<redacted>)"
    internal companion object { const val MAX_REQUEST_BYTES = 16_384 }
}
