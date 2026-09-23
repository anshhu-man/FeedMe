package com.feedme.app.circles

import com.feedme.core.ports.FailureReason
import com.feedme.mealflow.circles.*
import com.feedme.sync.CommandPhase

internal fun sameCircleCreateInput(a: CircleCreateInput, b: CircleCreateInput): Boolean =
    a.name == b.name && a.description == b.description

/** Scalar count only, never trimming/normalizing text or accepting a broken surrogate. */
internal fun circleTextScalars(text: String): Int? {
    var index = 0; var count = 0
    while (index < text.length) {
        val c = text[index++].code
        if (c in 0xD800..0xDBFF) {
            if (index == text.length || text[index].code !in 0xDC00..0xDFFF) return null
            index++
        } else if (c in 0xDC00..0xDFFF) return null
        count++
    }
    return count
}

internal class CircleCreateFieldErrors(val name: String?, val description: String?) {
    val valid get() = name == null && description == null
}

internal fun circleCreateFieldErrors(input: CircleCreateInput): CircleCreateFieldErrors {
    val name = circleTextScalars(input.name)
    val description = input.description?.let(::circleTextScalars)
    return CircleCreateFieldErrors(
        when { name == null -> "Use valid text for the name."
            name == 0 -> "Give your circle a name."
            name > 60 -> "Use up to 60 characters for the name."
            else -> null },
        when { input.description != null && description == null -> "Use valid text for the description."
            description != null && description > 300 -> "Use up to 300 characters for the description."
            else -> null })
}

internal fun circleCreateDescriptionText(description: String?): String = when (description) {
    null -> "Not included"
    "" -> "Included, left blank"
    else -> description
}

internal fun circleCreateCanEdit(screen: CircleCreateScreen, phase: CircleCreatePhase): Boolean =
    screen == CircleCreateScreen.FORM && phase !in setOf(CircleCreatePhase.BUSY, CircleCreatePhase.UNAVAILABLE)

/** Acknowledged create AND acknowledged discard finish the old form. Neither is a new draft. */
internal fun circleCreateCompletion(acknowledged: Boolean, hasPending: Boolean): Boolean =
    acknowledged && !hasPending

internal fun circleCreateReviewVisible(screen: CircleCreateScreen, phase: CircleCreatePhase, current: Boolean): Boolean =
    screen == CircleCreateScreen.REVIEW && phase != CircleCreatePhase.UNAVAILABLE && current

internal fun circleCreateReviewTitle(kind: CircleCreateReviewKind): String = when (kind) {
    CircleCreateReviewKind.CREATE -> "Ready to start your circle?"
    CircleCreateReviewKind.RETRY_ORIGINAL -> "Review the original request"
    CircleCreateReviewKind.DISCARD_UNSENT -> "Discard this unsent request?"
}

internal fun circleCreateConfirmLabel(kind: CircleCreateReviewKind): String = when (kind) {
    CircleCreateReviewKind.CREATE -> "Create circle"
    CircleCreateReviewKind.RETRY_ORIGINAL -> "Retry original request"
    CircleCreateReviewKind.DISCARD_UNSENT -> "Discard unsent request"
}

internal fun circleCreateReviewNotice(kind: CircleCreateReviewKind): String = when (kind) {
    CircleCreateReviewKind.CREATE -> "Check the details below. Your circle is created only when you choose Create circle."
    CircleCreateReviewKind.RETRY_ORIGINAL -> "Continue with these exact original details. Any newer draft stays separate. If a reply is already kept, this may finish without sending again."
    CircleCreateReviewKind.DISCARD_UNSENT -> "Remove only this unsent request. Your current draft stays on this device. This does not delete a circle."
}

internal fun circleCreatePendingText(phase: CommandPhase?, attempts: Int?, recovery: Boolean): String = when {
    recovery -> "This request needs to be checked before continuing. Its outcome has not been confirmed here."
    phase == CommandPhase.RECEIPT_READY -> "A reply is kept, but finishing the request has not been confirmed on this device."
    attempts == null -> "Delivery has not been checked. Keep the original details for recovery."
    attempts == 0 -> "This original request has not been sent."
    else -> "This request may already have reached the service. Retry only its original details."
}

internal fun circleCreateFailureText(reason: FailureReason?): String? = when (reason) {
    null -> null
    FailureReason.OFFLINE -> "You’re offline. Your circle has not been confirmed. Keep the draft and try again when connected."
    FailureReason.INVALID_DATA -> "Check the name and description, then try again. Your text has not been rewritten."
    FailureReason.CONFLICT -> "This view changed. Check the latest draft or original request before continuing."
    FailureReason.STALE_SESSION, FailureReason.UNAUTHENTICATED -> "This account session is unavailable. Private details are hidden."
    FailureReason.FORBIDDEN -> "This account can’t complete that action right now."
    FailureReason.NOT_CONFIGURED -> "Starting circles isn’t connected for this session."
    FailureReason.RATE_LIMITED -> "Please wait before trying the same original request again."
    else -> "We couldn’t confirm the action. Keep the original request until its outcome is clear."
}
