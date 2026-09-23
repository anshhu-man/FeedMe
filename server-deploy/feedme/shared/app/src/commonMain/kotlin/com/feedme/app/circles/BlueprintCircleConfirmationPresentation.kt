package com.feedme.app.circles

import androidx.compose.runtime.Composable
import com.feedme.app.blueprint.BlueprintConfirmationScreen
import com.feedme.app.blueprint.BlueprintConfirmationState
import com.feedme.core.ports.FailureReason
import com.feedme.mealflow.circles.*

/** The original CONFIRM_ACTION renders a selected existing review. It never prepares a
 * review, allocates a command, infers success or converts historical state into authority. */
@Composable
internal fun RetainedCircleLeaveConfirmation(state: CircleLeaveState, busy: Boolean,
    isCurrent: () -> Boolean, onConfirm: (CircleLeaveReview) -> Unit, onBack: () -> Unit) {
    val review = state.review?.takeIf { state.phase != CircleLeavePhase.UNAVAILABLE && isCurrent() && it.isCurrentForNavigation }
    val model = BlueprintConfirmationState(
        actionLabel = review?.let { circleLeaveConfirmLabel(it.kind) },
        affectedSummary = review?.circle?.name,
        consequences = review?.let { selected -> buildList {
            if (selected.kind in setOf(CircleLeaveReviewKind.LEAVE, CircleLeaveReviewKind.DELETE, CircleLeaveReviewKind.RETRY_ORIGINAL)) add(selected.consentText)
            add(circleLeaveReviewNotice(selected.kind))
            selected.original?.let { add(circleCreatePendingText(it.queuePhase, it.attempts, it.recoveryRequired)) }
        } }.orEmpty(),
        canConfirm = review != null && !busy && circleLeaveCanAct(state), canCancel = true, busy = busy,
        status = circleLeaveFailureText(state.failureReason, state.action) ?: when {
            review == null -> "This review changed. Go back and review again; this view cannot confirm an action."
            busy -> "The original request is being checked. Going back does not undo a request already sent."
            else -> null
        })
    BlueprintConfirmationScreen(model, onConfirm = { selected ->
        if (selected === model && model.confirmEnabled && isCurrent() && review != null &&
            state.review === review && review.isCurrentForNavigation) onConfirm(review)
    }, onCancel = { selected -> if (selected === model && model.cancelEnabled && isCurrent()) onBack() },
        confirmLabel = review?.let { circleLeaveConfirmLabel(it.kind) } ?: "Review required")
}

@Composable
internal fun RetainedCircleEditConfirmation(state: CircleEditState, busy: Boolean,
    failure: FailureReason?, handoffFailure: FailureReason?, isCurrent: () -> Boolean,
    onConfirm: (CircleEditReview) -> Unit, onBack: () -> Unit,
    details: @Composable (CircleEditReview) -> Unit) {
    val review = state.review?.takeIf { isCurrent() && circleEditReviewVisible(state.screen, state.phase, it.isCurrentForNavigation) }
    val model = BlueprintConfirmationState(
        actionLabel = review?.let { circleEditConfirmLabel(it.kind) },
        affectedSummary = review?.let { "${it.baseline.name} · original version ${it.baseline.version}" },
        consequences = review?.let { listOf(circleEditReviewNotice(it.kind)) }.orEmpty(),
        canConfirm = review != null && !busy, canCancel = true, busy = busy,
        status = circleEditFailureText(failure ?: state.failureReason ?: handoffFailure) ?: when {
            review == null -> "This review changed. Go back to your draft and review again. Nothing is confirmed from this view."
            busy -> "Keeping the exact original request. Going back does not undo changes already sent."
            else -> null
        })
    BlueprintConfirmationScreen(model, onConfirm = { selected ->
        if (selected === model && model.confirmEnabled && isCurrent() && review != null &&
            state.review === review && review.isCurrentForNavigation) onConfirm(review)
    }, onCancel = { selected -> if (selected === model && model.cancelEnabled && isCurrent()) onBack() },
        confirmLabel = review?.let { circleEditConfirmLabel(it.kind) } ?: "Review required",
        reviewLinks = { if (review != null) details(review) })
}
