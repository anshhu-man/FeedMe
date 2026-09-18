package com.feedme.server.http

import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.cooking.AccountCookingStore
import com.feedme.server.memory.AccountSavedRecipeStore
import kotlinx.coroutines.CoroutineDispatcher

/** Only the concrete subject-aware account owner is installable, never a supplied principal callback. */
class AccountCookingHttpConfiguration internal constructor(internal val store: AccountCookingStore,
    internal val verifier: SupabaseUserAccessVerifier, internal val databaseDispatcher: CoroutineDispatcher) {
    override fun toString() = "AccountCookingHttpConfiguration(<redacted>)"
}
class AccountSavedRecipeHttpConfiguration internal constructor(internal val store: AccountSavedRecipeStore,
    internal val verifier: SupabaseUserAccessVerifier, internal val databaseDispatcher: CoroutineDispatcher) {
    override fun toString() = "AccountSavedRecipeHttpConfiguration(<redacted>)"
}
