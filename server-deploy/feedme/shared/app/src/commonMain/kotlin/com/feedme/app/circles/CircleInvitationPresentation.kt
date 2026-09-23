package com.feedme.app.circles

import com.feedme.core.ports.FailureReason
import com.feedme.contracts.WireField
import com.feedme.mealflow.circles.*
import kotlin.time.Instant

/** F23 / INVITE_ACCEPT copy. Public preview is not membership or account authority. */
internal const val CIRCLE_INVITATION_TITLE = "Join a kitchen circle"
internal const val CIRCLE_INVITATION_AUDIENCE = "Members can see posts shared with this circle."
internal const val CIRCLE_INVITATION_HISTORY = "Joining gives you access to still-live Today posts and retained Plate posts shared with this circle."
internal const val CIRCLE_INVITATION_PUBLIC_NOTICE = "This is a public invitation preview. It does not join the circle or show its members."
internal const val CIRCLE_INVITATION_ACCOUNT_NOTICE = "Joining is optional. Review this invitation with your current account before choosing Join circle."
internal const val CIRCLE_INVITATION_SIGN_IN_NOTICE = "Sign in with a FeedMe account to review joining. Signing in does not accept this invitation."
internal const val CIRCLE_INVITATION_LOGIN_UNAVAILABLE = "Account sign-in is not connected in this view. You can go back without joining."
internal const val CIRCLE_INVITATION_UNAVAILABLE = "This invitation is no longer available. Ask the sender for a new invitation."
internal const val CIRCLE_INVITATION_SCOPE_NOTICE = "Creating and revoking invitation links, member changes and sharing posts are not available in this view."

/** Preserve supplied display strings; missing and present-blank evidence remain distinct.
 * These strings are never parsed as links, IDs, permission, membership or command authority. */
internal fun circleInvitationDisplay(value: String?, missing: String, blank: String): String =
    when (value) { null -> missing; "" -> blank; else -> value }

internal fun circleInvitationDisplay(value: WireField<String>, missing: String, blank: String): String =
    when (value) {
        WireField.Missing, WireField.Null -> missing
        is WireField.Value -> circleInvitationDisplay(value.value, missing, blank)
    }

internal fun circleInvitationReviewVisible(screen: CircleInvitationScreen, phase: CircleInvitationPhase, current: Boolean): Boolean =
    screen == CircleInvitationScreen.REVIEW && phase != CircleInvitationPhase.UNAVAILABLE && current
internal fun circleInvitationCanAct(screen: CircleInvitationScreen, phase: CircleInvitationPhase): Boolean =
    screen != CircleInvitationScreen.HIDDEN && phase !in setOf(CircleInvitationPhase.BUSY, CircleInvitationPhase.UNAVAILABLE)
internal fun circleInvitationReviewTitle(kind: CircleInvitationReviewKind): String = when (kind) {
    CircleInvitationReviewKind.ACCEPT -> "Review joining this circle"
    CircleInvitationReviewKind.RETRY_ORIGINAL -> "Review the original join request"
    CircleInvitationReviewKind.DISCARD_UNSENT -> "Discard this unsent join request?"
    CircleInvitationReviewKind.ACKNOWLEDGE_COMPLETION -> "Review the retained result"
}
internal fun circleInvitationConfirmLabel(kind: CircleInvitationReviewKind): String = when (kind) {
    CircleInvitationReviewKind.ACCEPT -> "Join circle"
    CircleInvitationReviewKind.RETRY_ORIGINAL -> "Retry original join request"
    CircleInvitationReviewKind.DISCARD_UNSENT -> "Discard unsent join request"
    CircleInvitationReviewKind.ACKNOWLEDGE_COMPLETION -> "Confirm retained result"
}
internal fun circleInvitationReviewNotice(kind: CircleInvitationReviewKind): String = when (kind) {
    CircleInvitationReviewKind.ACCEPT -> "Only Join circle sends this request for your current account. The service checks the invitation and account again before joining."
    CircleInvitationReviewKind.RETRY_ORIGINAL -> "Continue only with the exact original join request. A kept reply may finish without sending again. This does not accept a different invitation."
    CircleInvitationReviewKind.DISCARD_UNSENT -> "Discard only this request that has not been sent. This does not leave a circle or revoke the sender’s invitation."
    CircleInvitationReviewKind.ACKNOWLEDGE_COMPLETION -> "Check the result retained on this device. Confirming it does not send another join request or prove continuing access to the circle."
}
internal fun circleInvitationPendingText(pending: CircleInvitationPending): String =
    circleCreatePendingText(pending.queuePhase, pending.attempts, pending.recoveryRequired)

/** Readable deterministic English/UTC presentation, never a guessed device locale or expiry.
 * Exact returned instant remains available separately and is never changed in the model. */
internal fun circleInvitationExpiry(value: WireField<String>): String {
    val raw = (value as? WireField.Value)?.value ?: return "Expiry unavailable"
    val utc = try { Instant.parse(raw).toString() } catch (_: Exception) { return raw }
    val parts = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})T(.+)Z").matchEntire(utc) ?: return utc
    val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val month = parts.groupValues[2].toIntOrNull()?.let { months.getOrNull(it - 1) } ?: return utc
    return parts.groupValues[3].toInt().toString() + " " + month + " " + parts.groupValues[1] +
        " at " + parts.groupValues[4] + " UTC"
}

internal fun circleInvitationFailureText(reason: FailureReason?): String? = when (reason) {
    null -> null
    FailureReason.OFFLINE -> "You’re offline. Joining has not been confirmed. Reconnect before checking the original request."
    FailureReason.FORBIDDEN -> "This account cannot use this invitation. No successful join is confirmed here."
    FailureReason.NOT_FOUND -> CIRCLE_INVITATION_UNAVAILABLE
    FailureReason.CONFLICT -> "This view or invitation changed. An original request must keep its own recovery path; do not start a replacement."
    FailureReason.NOT_CONFIGURED -> "Joining invitations is not connected for this session."
    FailureReason.INVALID_DATA -> "This invitation could not be checked. Open a supported invitation link from the sender."
    FailureReason.STALE_SESSION, FailureReason.UNAUTHENTICATED -> "This account session is unavailable. Private invitation details are hidden."
    FailureReason.RATE_LIMITED -> "Please wait before checking this invitation or retrying its original request."
    else -> "We couldn’t confirm the action. An existing original request may still need recovery."
}
