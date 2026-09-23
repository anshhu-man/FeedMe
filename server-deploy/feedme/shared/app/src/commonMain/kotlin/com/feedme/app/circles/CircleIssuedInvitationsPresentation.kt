package com.feedme.app.circles

import com.feedme.core.ports.FailureReason
import com.feedme.mealflow.circles.*

internal fun circleIssuedCanAct(screen: CircleIssuedInvitationsScreen, phase: CircleIssuedInvitationsPhase): Boolean =
    screen != CircleIssuedInvitationsScreen.HIDDEN && phase !in setOf(CircleIssuedInvitationsPhase.BUSY, CircleIssuedInvitationsPhase.UNAVAILABLE)

internal fun circleIssuedLifetime(value: String, maximum: Int): Int? =
    if (value.isEmpty() || value.length > 3 || value.any { it !in '0'..'9' }) null
    else value.toIntOrNull()?.takeIf { it in 1..maximum }

internal fun circleIssuedHours(hours: Int): String = if (hours == 1) "1 hour" else "$hours hours"

internal fun circleIssuedReviewTitle(kind: CircleIssuedInvitationReviewKind): String = when (kind) {
    CircleIssuedInvitationReviewKind.ISSUE -> "Review invite link"
    CircleIssuedInvitationReviewKind.REVOKE -> "Review invitation revocation"
    CircleIssuedInvitationReviewKind.RETRY_ORIGINAL -> "Review original request"
    CircleIssuedInvitationReviewKind.DISCARD_UNSENT -> "Review unsent discard"
    CircleIssuedInvitationReviewKind.ACKNOWLEDGE_COMPLETION -> "Review retained result"
}

internal fun circleIssuedConfirmLabel(kind: CircleIssuedInvitationReviewKind): String = when (kind) {
    CircleIssuedInvitationReviewKind.ISSUE -> CIRCLE_ISSUED_CREATE_LABEL
    CircleIssuedInvitationReviewKind.REVOKE -> CIRCLE_ISSUED_REVOKE_LABEL
    CircleIssuedInvitationReviewKind.RETRY_ORIGINAL -> "Retry original request"
    CircleIssuedInvitationReviewKind.DISCARD_UNSENT -> "Discard unsent request"
    CircleIssuedInvitationReviewKind.ACKNOWLEDGE_COMPLETION -> "Confirm retained result"
}

internal fun circleIssuedReviewNotice(kind: CircleIssuedInvitationReviewKind): String = when (kind) {
    CircleIssuedInvitationReviewKind.ISSUE -> CIRCLE_ISSUED_REVIEW_NOTICE
    CircleIssuedInvitationReviewKind.REVOKE -> "Only Revoke selected invite sends this revocation request. No circle member is removed."
    CircleIssuedInvitationReviewKind.RETRY_ORIGINAL -> "Retry only this exact original request. It cannot create a different link or revoke a different invitation."
    CircleIssuedInvitationReviewKind.DISCARD_UNSENT -> "Discard only this request that has never been sent. This does not revoke an existing invitation."
    CircleIssuedInvitationReviewKind.ACKNOWLEDGE_COMPLETION -> "Confirm the retained result on this device. This does not send a request or recover a shareable link."
}

internal fun circleIssuedFailure(reason: FailureReason?): String? = when (reason) {
    null -> null
    FailureReason.OFFLINE -> "You’re offline. Keep the original request and reconnect before retrying. Nothing is confirmed as sent."
    FailureReason.UNAUTHENTICATED, FailureReason.STALE_SESSION -> "This account connection is no longer available. Private invitation details are hidden."
    FailureReason.FORBIDDEN -> "You no longer have permission for this action. An unresolved original stays separate; it is not replaced automatically."
    FailureReason.CONFLICT -> "The invitation or review changed. Return to the original request or review fresh details; no different request is sent automatically."
    FailureReason.INVALID_DATA -> "The details could not be verified. Check the requested lifetime or return to the original request."
    FailureReason.STORAGE_FAILURE, FailureReason.OUTCOME_UNKNOWN -> "The result is not confirmed here. Keep and review the original request; do not create a replacement."
    FailureReason.NOT_CONFIGURED -> "This action is not connected in this view."
    FailureReason.NOT_FOUND -> "This invitation or one-display link is not available here."
    FailureReason.RATE_LIMITED -> "The service asked you to wait. Retry the original request later; nothing retries automatically."
    FailureReason.UNAVAILABLE -> "This action is temporarily unavailable. Keep any original request for recovery."
}

/** Canonical INVITE.01/.02/.03 copy. Presentation never grants issue, share or revoke authority. */
internal const val CIRCLE_ISSUED_TITLE = "Invite your people"
internal const val CIRCLE_ISSUED_CREATE_LABEL = "Create invite link"
internal const val CIRCLE_ISSUED_SHARE_LABEL = "Share invite link"
internal const val CIRCLE_ISSUED_REVOKE_LABEL = "Revoke selected invite"
internal const val CIRCLE_ISSUED_HISTORY_TITLE = "Issued on this device"
internal const val CIRCLE_ISSUED_HISTORY_NOTICE =
    "This is history kept on this device, not a live list of invitations. Details are shown as last confirmed."
internal const val CIRCLE_ISSUED_SINGLE_USE_NOTICE =
    "One person can use this link once, before it expires. Share it only with someone you want in this circle."
internal const val CIRCLE_ISSUED_REVIEW_NOTICE =
    "Reviewing does not create or share a link. Only Create invite link sends the request."
internal const val CIRCLE_ISSUED_LINK_NOTICE =
    "This link is available only in this view now. Going back or ending this account session hides it. Keep it private."
internal const val CIRCLE_ISSUED_SHARE_NOTICE =
    "You choose the app and recipient in your device’s share sheet. Opening or cancelling it does not confirm delivery."
internal const val CIRCLE_ISSUED_SHARE_UNAVAILABLE =
    "Sharing is not connected in this view. No share sheet has been opened."
internal const val CIRCLE_ISSUED_REVOKE_NOTICE =
    "Revoke only this invitation. Revoking a link does not remove anyone who has already joined the circle."
internal const val CIRCLE_ISSUED_NOT_DELIVERED =
    "The share sheet was opened. FeedMe cannot confirm whether the invitation was sent or received."
internal const val CIRCLE_ISSUED_SHARE_CANCELLED =
    "Sharing was cancelled. No delivery is confirmed."
