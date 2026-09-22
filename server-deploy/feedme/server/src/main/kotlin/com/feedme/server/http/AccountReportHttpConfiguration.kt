package com.feedme.server.http

import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.social.reports.AccountReportStore
import kotlinx.coroutines.CoroutineDispatcher

/** Explicit account-safety composition, independent of social creation. Verified signatures
 * do not replace current account/device and exact report-target checks in the transaction. */
class AccountReportHttpConfiguration internal constructor(
    internal val store: AccountReportStore,
    internal val verifier: SupabaseUserAccessVerifier,
    internal val databaseDispatcher: CoroutineDispatcher,
) {
    override fun toString() = "AccountReportHttpConfiguration(<redacted>)"

    companion object {
        internal const val MAX_REQUEST_BYTES = 8192
    }
}
