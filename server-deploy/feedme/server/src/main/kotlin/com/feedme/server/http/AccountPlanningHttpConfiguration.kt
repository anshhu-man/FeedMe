package com.feedme.server.http

import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.planning.AccountPlanningStore
import com.feedme.server.planning.AccountRecipeCatalogStore
import kotlinx.coroutines.CoroutineDispatcher

/** Account planning and explicitly composed simplification/adaptation; no caller-supplied principal,
 * alternate accepting verifier, automatic runtime activation or source-copy authority. */
class AccountPlanningHttpConfiguration internal constructor(
    internal val store: AccountPlanningStore,
    internal val verifier: SupabaseUserAccessVerifier,
    internal val databaseDispatcher: CoroutineDispatcher,
    internal val recipes: AccountRecipeCatalogStore? = null,
) {
    override fun toString() = "AccountPlanningHttpConfiguration(<redacted>)"
}
