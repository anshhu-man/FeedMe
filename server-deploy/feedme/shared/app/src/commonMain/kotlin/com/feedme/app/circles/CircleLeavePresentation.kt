package com.feedme.app.circles

import com.feedme.core.ports.FailureReason
import com.feedme.mealflow.circles.*

internal fun circleLeaveRoleVisible(role: String) = role in setOf("admin", "member")
internal fun circleLeaveCanAct(state: CircleLeaveState) = state.screen != CircleLeaveScreen.HIDDEN &&
    state.phase !in setOf(CircleLeavePhase.BUSY, CircleLeavePhase.UNAVAILABLE)
internal fun circleLeaveConfirmLabel(kind: CircleLeaveReviewKind) = when (kind) {
    CircleLeaveReviewKind.LEAVE -> "Leave circle"
    CircleLeaveReviewKind.DELETE -> "Delete circle"
    CircleLeaveReviewKind.RETRY_ORIGINAL -> "Retry original leave request"
    CircleLeaveReviewKind.DISCARD_UNSENT -> "Discard unsent request"
    CircleLeaveReviewKind.ACKNOWLEDGE_COMPLETION -> "Confirm retained result"
}
internal fun circleLeaveReviewNotice(kind: CircleLeaveReviewKind) = when (kind) {
    CircleLeaveReviewKind.LEAVE -> "Only the button below sends a leave request for your current account. Circle owners must transfer ownership separately."
    CircleLeaveReviewKind.DELETE -> "Only the button below sends the owner-only delete request. This dissolves the circle; it does not delete your account or posts retained for another audience."
    CircleLeaveReviewKind.RETRY_ORIGINAL -> "Only the original circle and request are retried. A saved reply may finish without sending again. A missing circle is not proof of success."
    CircleLeaveReviewKind.DISCARD_UNSENT -> "Only this proven-unsent request is discarded. Your membership does not change."
    CircleLeaveReviewKind.ACKNOWLEDGE_COMPLETION -> "Confirm the result already retained on this device. This does not send another leave request."
}
internal fun circleLeaveFailureText(reason: FailureReason?,
    action: CircleDepartureAction = CircleDepartureAction.LEAVE): String? = when (reason) {
    null -> null
    FailureReason.FORBIDDEN -> if (action == CircleDepartureAction.DELETE)
        "Only the current circle owner can delete this circle. Refresh and review again."
        else "This membership cannot leave here. Circle owners must transfer ownership separately."
    FailureReason.OFFLINE -> "You’re offline. Any original request stays available for explicit recovery."
    FailureReason.CONFLICT -> "This view changed. Review again; an existing original cannot be replaced."
    FailureReason.STALE_SESSION, FailureReason.UNAUTHENTICATED -> "This account session is unavailable. Private details are hidden."
    FailureReason.NOT_CONFIGURED -> if (action == CircleDepartureAction.DELETE)
        "Deleting circles is not connected for this session." else "Leaving circles is not connected for this session."
    else -> if (action == CircleDepartureAction.DELETE)
        "We couldn’t confirm deletion. Keep the original request and review its recovery."
        else "We couldn’t confirm leaving. Keep the original request and review its recovery."
}
