package com.feedme.app.onboarding

import com.feedme.core.ports.FailureReason
import com.feedme.core.FeedMeAdultPolicy
import com.feedme.session.EmailAccountScreen

/** The connected edition is adult-only. Public configuration cannot lower or obscure its
 * minimum age. This declaration is not verified age or a server eligibility receipt. */
class EmailAccountFormPolicy(val ageDeclaration: String, val passwordGuidance: String) {
    init {
        require(ageDeclaration == FeedMeAdultPolicy.AGE_DECLARATION)
        require(passwordGuidance.isNotBlank() && passwordGuidance.length <= 240 && passwordGuidance.none(Char::isISOControl))
    }
}

/** Attachment-only input. Never saved in SavedState, preferences, logs or a pending-email record. */
internal data class EmailFormInput(
    val email: String = "", val password: String = "", val confirmation: String = "", val code: String = "",
    val termsAccepted: Boolean = false, val ageConfirmed: Boolean = false,
) {
    fun withoutSecrets() = copy(password = "", confirmation = "", code = "")
    override fun toString() = "EmailFormInput(<redacted>)"
}

internal data class EmailFormErrors(
    val email: String? = null, val password: String? = null, val confirmation: String? = null,
    val code: String? = null, val declarations: String? = null,
) { val valid get() = listOf(email, password, confirmation, code, declarations).all { it == null } }

/** UI-thread admission, independent of the next Compose recomposition. Old cleanup cannot
 * release a later action, and disposal permanently retires this attachment. */
internal class EmailUiActionGate {
    private var ticket: Any? = null
    private var retired = false
    val busy get() = ticket != null || retired
    fun claim(): Any? = if (busy) null else Any().also { ticket = it }
    fun owns(expected: Any) = !retired && ticket === expected
    fun release(expected: Any): Boolean {
        if (!owns(expected)) return false
        ticket = null
        return true
    }
    fun retire() { retired = true; ticket = null }
}

/** UX checks only. Preserve the exact supplied address/password; provider policy is authoritative. */
internal fun emailFormErrors(screen: EmailAccountScreen, input: EmailFormInput): EmailFormErrors {
    fun safe(value: String, maximum: Int): Boolean {
        if (value.isBlank() || value.any(Char::isISOControl) || value.encodeToByteArray().size > maximum) return false
        var index = 0
        while (index < value.length) {
            val c = value[index++]
            if (c.isHighSurrogate()) {
                if (index == value.length || !value[index++].isLowSurrogate()) return false
            } else if (c.isLowSurrogate()) return false
        }
        return true
    }
    if (screen == EmailAccountScreen.VERIFY) return EmailFormErrors(
        code = if (input.code.length == 6 && input.code.all { it in '0'..'9' }) null else "Enter the 6-digit code from your email.")
    if (screen !in setOf(EmailAccountScreen.LOGIN, EmailAccountScreen.SIGNUP)) return EmailFormErrors()
    val signup = screen == EmailAccountScreen.SIGNUP
    return EmailFormErrors(
        email = if (safe(input.email, 320) && input.email.count { it == '@' } == 1 &&
            !input.email.startsWith('@') && !input.email.endsWith('@') && input.email.none(Char::isWhitespace)) null else "Enter a valid email address.",
        password = if (safe(input.password, 4096)) null else "Enter your password using valid text.",
        confirmation = if (!signup || (safe(input.confirmation, 4096) && input.password == input.confirmation)) null else "Passwords must match.",
        declarations = if (!signup || (input.termsAccepted && input.ageConfirmed)) null else "You must be 18 or older and accept both declarations to create an account.",
    )
}

internal fun emailFailureText(reason: FailureReason?): String? = when (reason) {
    null -> null
    FailureReason.OFFLINE -> "You’re offline. Reconnect, then choose the action again."
    FailureReason.RATE_LIMITED -> "Please wait before trying again. Requests are not retried automatically."
    FailureReason.UNAUTHENTICATED, FailureReason.FORBIDDEN -> "We couldn’t verify those details. Check them and try again."
    FailureReason.STALE_SESSION -> "This account step is no longer current. Go back to reconnect."
    FailureReason.NOT_CONFIGURED -> "This account connection is not configured yet."
    FailureReason.INVALID_DATA -> "We couldn’t use those details or the service response. Check your input before trying again."
    FailureReason.STORAGE_FAILURE -> "We couldn’t safely save account setup on this device. No private account screen has been opened."
    FailureReason.OUTCOME_UNKNOWN -> "We couldn’t confirm the result. The request may have reached the service."
    FailureReason.CONFLICT -> "There is account setup to resolve before starting another request."
    FailureReason.NOT_FOUND, FailureReason.UNAVAILABLE -> "The account service is unavailable. Please try again later."
}

/** Terms-specific explanatory copy. An uncertain send never means consent was rolled back. */
internal fun accountTermsFailureText(reason: FailureReason?): String? = when (reason) {
    null -> null
    FailureReason.OUTCOME_UNKNOWN -> "The result could not be confirmed. Any saved acceptance is kept. Retry saved acceptance checks that same request; it does not create a replacement."
    FailureReason.CONFLICT -> "The saved acceptance or current notice needs attention. It has not been replaced. Go back to check your account connection."
    FailureReason.STORAGE_FAILURE -> "This device could not confirm the saved acceptance. Your existing account and saved work are kept."
    FailureReason.FORBIDDEN -> "This account cannot accept the notice right now. No account access has been granted."
    FailureReason.NOT_CONFIGURED -> "Terms review is not configured for this account connection yet."
    else -> emailFailureText(reason)
}

/** Explanatory display, never the coordinator's admission decision. Fail closed on bad clocks. */
internal fun emailResendSeconds(now: Long, resendAt: Long?): Long? {
    if (now < 0 || resendAt == null || resendAt < 0) return null
    if (now >= resendAt) return 0
    val remaining = resendAt - now
    return remaining / 1000 + if (remaining % 1000 == 0L) 0 else 1
}
