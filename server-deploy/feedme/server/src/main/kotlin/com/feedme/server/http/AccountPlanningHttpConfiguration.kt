package com.feedme.server.http

import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.planning.AccountPlanningStore
import kotlinx.coroutines.CoroutineDispatcher

/** Exactly the four existing account planning operations; no caller-supplied principal,
 * alternate accepting verifier, automatic runtime activation or source-copy authority. */
class AccountPlanningHttpConfiguration internal constructor(
    internal val store: AccountPlanningStore,
    internal val verifier: SupabaseUserAccessVerifier,
    internal val databaseDispatcher: CoroutineDispatcher,
) {
    override fun toString() = "AccountPlanningHttpConfiguration(<redacted>)"
}
