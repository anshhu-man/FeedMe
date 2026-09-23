package com.feedme.app.onboarding

import com.feedme.core.ports.PortResult
import com.feedme.session.*

/** Exact rendered acquisition path only; configuration and account admission stay with the
 * coordinator. A conflicting host is unavailable, never a native/browser fallback. */
internal class EmailGoogleSelection private constructor(
    private val native: GoogleAccountCredentialRequest?,
    private val oauth: GoogleOAuthCredentialRequest?,
) {
    fun matches(native: GoogleAccountCredentialRequest?, oauth: GoogleOAuthCredentialRequest?) =
        this.native === native && this.oauth === oauth

    suspend fun confirm(coordinator: EmailAccountCoordinator, expected: EmailAccountState): PortResult<EmailAccountState> =
        if (oauth != null) coordinator.signInWithGoogleOAuth(true, true, expected, oauth)
        else coordinator.signInWithGoogle(true, true, expected, checkNotNull(native))

    override fun toString() = "EmailGoogleSelection(<redacted>)"

    companion object {
        fun select(native: GoogleAccountCredentialRequest?, oauth: GoogleOAuthCredentialRequest?): EmailGoogleSelection? =
            if ((native == null) == (oauth == null)) null else EmailGoogleSelection(native, oauth)
    }
}
