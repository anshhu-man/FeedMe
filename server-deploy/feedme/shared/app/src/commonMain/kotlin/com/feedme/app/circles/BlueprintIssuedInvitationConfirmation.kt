package com.feedme.app.circles

import androidx.compose.runtime.Composable
import com.feedme.app.blueprint.BlueprintConfirmationScreen
import com.feedme.app.blueprint.BlueprintConfirmationState
import com.feedme.contracts.WireField
import com.feedme.mealflow.circles.CircleIssuedInvitationReview
import com.feedme.mealflow.circles.CircleIssuedInvitationsPhase
import com.feedme.mealflow.circles.CircleIssuedInvitationsScreen
import com.feedme.mealflow.circles.CircleIssuedInvitationsState

/** Original CONFIRM_ACTION presents the owner's exact opaque invitation review.
 * Rendering and Cancel never issue, retry, revoke, discard or acknowledge a request. */
@Composable
internal fun RetainedBlueprintIssuedInvitationConfirmation(
    state: CircleIssuedInvitationsState,
    enabled: Boolean,
    busy: Boolean,
    isCurrent: () -> Boolean,
    onConfirm: (CircleIssuedInvitationReview) -> Unit,
    onBack: () -> Unit,
): Boolean {
    val review = state.review ?: return false
    if (state.screen != CircleIssuedInvitationsScreen.REVIEW ||
        state.phase == CircleIssuedInvitationsPhase.UNAVAILABLE || state.failureReason != null ||
        !review.isCurrentForNavigation) return false
    val model = BlueprintConfirmationState(
        actionLabel = circleIssuedConfirmLabel(review.kind),
        affectedSummary = buildString {
            append("Circle: ").append(review.circle.name)
            review.issued?.let { append("\nInvitation: ").append(it.id).append(" · version ").append(it.version) }
            review.original?.let { append("\nOriginal request: ").append(it.commandId) }
        },
        consequences = buildList {
            add(review.consentText)
            add(circleIssuedReviewNotice(review.kind))
            review.expiresInHours?.let { add("Requested lifetime: ${circleIssuedHours(it)}.") }
            review.issued?.let { issued ->
                add("Last confirmed status: ${circleStatusLabel(issued.status)}. " +
                    "Expires: ${circleInvitationExpiry(WireField.Value(issued.expiresAt))}.")
                add("Invitation updated: ${circleInvitationExpiry(WireField.Value(issued.updatedAt))}.")
                add(if (issued.revocationAcknowledged) "Revocation was confirmed on this device. No existing member was removed."
                    else "Current use or revocation elsewhere is not checked by this view.")
            }
            review.original?.let { original ->
                add(circleCreatePendingText(original.queuePhase, original.attempts, original.recoveryRequired))
            }
        },
        canConfirm = enabled && isCurrent(),
        canCancel = isCurrent(),
        busy = busy,
        status = if (busy) "Checking this exact invitation action. No result is confirmed yet." else
            "Back keeps the existing invitation and any original request unchanged.",
    )
    BlueprintConfirmationScreen(model,
        heading = circleIssuedReviewTitle(review.kind),
        confirmLabel = circleIssuedConfirmLabel(review.kind),
        onConfirm = { observed ->
            if (observed === model && model.confirmEnabled && isCurrent() &&
                state.review === review && review.isCurrentForNavigation) onConfirm(review)
        },
        onCancel = { observed ->
            if (observed === model && model.cancelEnabled && isCurrent()) onBack()
        })
    return true
}
