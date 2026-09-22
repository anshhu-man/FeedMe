package com.feedme.server.http

import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.staff.SupabaseStaffAdmissionStore
import com.feedme.server.staff.SupabaseStaffRecipeDraftStore
import kotlinx.coroutines.CoroutineDispatcher

/** Explicit optional composition; neither a consumer token nor a mounted UI enables it. */
class SupabaseStaffHttpConfiguration internal constructor(
    internal val store: SupabaseStaffAdmissionStore,
    internal val verifier: SupabaseUserAccessVerifier,
    internal val databaseDispatcher: CoroutineDispatcher,
    internal val recipes: SupabaseStaffRecipeDraftStore? = null,
    internal val moderation: com.feedme.server.staff.SupabaseStaffModerationStore? = null,
) {
    override fun toString() = "SupabaseStaffHttpConfiguration(<redacted>)"
}
