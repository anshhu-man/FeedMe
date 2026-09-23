package com.feedme.app.circles

import com.feedme.core.ports.FailureReason
import com.feedme.mealflow.circles.*

internal fun sameCircleEditInput(a: CircleEditInput, b: CircleEditInput): Boolean =
    a.name == b.name && a.description == b.description

internal fun circleEditFieldErrors(input: CircleEditInput): CircleCreateFieldErrors =
    circleCreateFieldErrors(CircleCreateInput(input.name, input.description))

internal fun circleEditDescriptionText(description: String?): String = when (description) {
    null -> "No description — removes any existing description"
    "" -> "Description included, left blank"
    else -> description
}

/** Display eligibility only. Actual selection, current session and server permission still apply. */
internal fun circleEditRoleVisible(role: String): Boolean = role == "owner" || role == "admin"
internal fun circleEditCanEdit(screen: CircleEditScreen, phase: CircleEditPhase): Boolean =
    screen == CircleEditScreen.FORM && phase !in setOf(CircleEditPhase.BUSY, CircleEditPhase.UNAVAILABLE)
internal fun circleEditCompletion(acknowledged: Boolean, pending: Boolean): Boolean = acknowledged && !pending
internal fun circleEditReviewVisible(screen: CircleEditScreen, phase: CircleEditPhase, current: Boolean): Boolean =
    screen == CircleEditScreen.REVIEW && phase != CircleEditPhase.UNAVAILABLE && current
internal fun circleEditReviewTitle(kind: CircleEditReviewKind): String = when (kind) {
    CircleEditReviewKind.UPDATE -> "Review your changes"
    CircleEditReviewKind.RETRY_ORIGINAL -> "Review the original request"
    CircleEditReviewKind.DISCARD_UNSENT -> "Discard this unsent request?"
    CircleEditReviewKind.RESOLVE_VERSION_CONFLICT -> "Review the latest circle details"
}
internal fun circleEditConfirmLabel(kind: CircleEditReviewKind): String = when (kind) {
    CircleEditReviewKind.UPDATE -> "Save changes"
    CircleEditReviewKind.RETRY_ORIGINAL -> "Retry original request"
    CircleEditReviewKind.DISCARD_UNSENT -> "Discard unsent request"
    CircleEditReviewKind.RESOLVE_VERSION_CONFLICT -> "Use latest details for review"
}
internal fun circleEditReviewNotice(kind: CircleEditReviewKind): String = when (kind) {
    CircleEditReviewKind.UPDATE -> "This replaces the circle name and description. Changes are sent only when you choose Save changes."
    CircleEditReviewKind.RETRY_ORIGINAL -> "Continue only with these exact original changes. Your newer draft stays separate. A kept reply may finish without sending again."
    CircleEditReviewKind.DISCARD_UNSENT -> "Discard only this unsent request. Your draft stays on this device. This does not delete or change the circle."
    CircleEditReviewKind.RESOLVE_VERSION_CONFLICT -> "The service rejected the original changes because the circle changed. Check the original, the latest details and your kept draft. This step keeps the latest starting point; it does not send your changes. Review changes and Save changes are separate next steps."
}
internal fun circleEditPendingText(pending: CircleEditPending): String =
    if (pending.canReviewConflict) "The service rejected these original changes because the circle changed. Review the latest details before preparing a different request."
    else circleCreatePendingText(pending.queuePhase, pending.attempts, pending.recoveryRequired)
internal fun circleEditFailureText(reason: FailureReason?): String? = when (reason) {
    null -> null
    FailureReason.OFFLINE -> "You’re offline. Changes have not been confirmed. Keep your draft; the original request remains separate."
    FailureReason.FORBIDDEN -> "You no longer have permission to change these circle details. No successful update is confirmed here."
    FailureReason.NOT_FOUND -> "This circle is no longer available to this account."
    FailureReason.CONFLICT -> "This view or the circle changed. Keep the original request. If a confirmed version conflict is available, review the latest details separately."
    FailureReason.NOT_CONFIGURED -> "Editing circle details isn’t connected for this session."
    FailureReason.INVALID_DATA -> "Check the name and description. Your text has not been trimmed or rewritten."
    FailureReason.STALE_SESSION, FailureReason.UNAUTHENTICATED -> "This account session is unavailable. Private details are hidden."
    FailureReason.RATE_LIMITED -> "Please wait before trying the same original request again."
    else -> "We couldn’t confirm the action. Keep the original request until its outcome is clear."
}
