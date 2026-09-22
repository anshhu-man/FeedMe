package com.feedme.server.http

import com.feedme.server.export.AccountExportDownloadService
import kotlinx.coroutines.CoroutineDispatcher

class AccountExportDeliveryHttpConfiguration internal constructor(
    internal val downloads: AccountExportDownloadService,
    internal val databaseDispatcher: CoroutineDispatcher,
) {
    override fun toString() = "AccountExportDeliveryHttpConfiguration(<redacted>)"
}
