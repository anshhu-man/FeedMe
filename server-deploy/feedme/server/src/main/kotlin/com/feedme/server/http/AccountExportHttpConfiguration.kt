package com.feedme.server.http

import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.export.AccountExportStore
import kotlinx.coroutines.CoroutineDispatcher

class AccountExportHttpConfiguration internal constructor(internal val store:AccountExportStore,
    internal val verifier:SupabaseUserAccessVerifier,internal val databaseDispatcher:CoroutineDispatcher) {
    override fun toString()="AccountExportHttpConfiguration(<redacted>)"
}
