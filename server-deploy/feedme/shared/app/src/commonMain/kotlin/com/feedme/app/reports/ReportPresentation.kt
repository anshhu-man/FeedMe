package com.feedme.app.reports

import com.feedme.core.ports.FailureReason
import com.feedme.mealflow.reports.ReportInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ReportReasonChoice(val value: String, val label: String)

internal val reportReasons = listOf(
    ReportReasonChoice("harassment", "Harassment or bullying"),
    ReportReasonChoice("unsafeFood", "Unsafe food advice"),
    ReportReasonChoice("privacy", "Privacy concern"),
    ReportReasonChoice("spam", "Spam"),
    ReportReasonChoice("other", "Something else"),
)

internal fun reportReasonLabel(reason: String?): String =
    reportReasons.firstOrNull { it.value == reason }?.label ?: "Choose a reason"

/** Counts Unicode scalar values, not UTF-16 units. No normalization or silent truncation. */
internal fun reportTextLength(text: String): Int? {
    var at = 0
    var count = 0
    while (at < text.length) {
        val character = text[at++].code
        if (character in 0xD800..0xDBFF) {
            if (at == text.length || text[at].code !in 0xDC00..0xDFFF) return null
            at++
        } else if (character in 0xDC00..0xDFFF) return null
        count++
    }
    return count
}

internal fun reportDescriptionError(text: String): String? = when (val count = reportTextLength(text)) {
    null -> "Use valid text."
    in 0..1000 -> null
    else -> "Use up to 1,000 characters."
}

internal fun reportCanReview(reason: String?, description: String): Boolean =
    reportReasons.any { it.value == reason } && reportDescriptionError(description) == null

internal data class ReportStatusPresentation(val title: String, val text: String)

internal fun reportStatusPresentation(status: String): ReportStatusPresentation = when (status) {
    "received" -> ReportStatusPresentation("Report received", "Your report was received. It has not been marked as reviewed yet.")
    "triaged" -> ReportStatusPresentation("In the review queue", "Your report has been triaged. A final outcome is not recorded yet.")
    "resolved" -> ReportStatusPresentation("Review closed", "Your report is marked resolved. This status does not tell you what action was taken.")
    "dismissed" -> ReportStatusPresentation("Report closed", "Your report is marked dismissed. No action is confirmed by this status.")
    else -> ReportStatusPresentation("Status unavailable", "We couldn’t read this status. Check again when connected.")
}

internal fun reportPendingText(attempts: Int?): String = when (attempts) {
    null -> "Delivery hasn’t been confirmed. Keep this original report for recovery."
    0 -> "This original report has not been sent."
    else -> "This report may already have arrived. Retry only the original report to check its outcome."
}

internal enum class ReportReplacementKind { KEPT_DRAFT, COMPLETED_REPORT }

internal fun reportReplacementNotice(kind: ReportReplacementKind, oldLabel: String, nextLabel: String): String = when (kind) {
    ReportReplacementKind.KEPT_DRAFT -> "Replace your draft for $oldLabel with an empty draft for $nextLabel? The current reason and unsent text will be discarded. Nothing is sent."
    ReportReplacementKind.COMPLETED_REPORT -> "Start an empty draft for $nextLabel? This does not withdraw or change your previous report. Nothing is sent until you review and choose Send report."
}

internal fun reportFailureText(reason: FailureReason?): String? = when (reason) {
    null -> null
    FailureReason.OFFLINE -> "You’re offline. Keep the draft or original report and try again when connected."
    FailureReason.INVALID_DATA -> "Check the reason and description. Nothing was rewritten."
    FailureReason.CONFLICT -> "This view changed. Check the current draft before continuing."
    FailureReason.STALE_SESSION, FailureReason.UNAUTHENTICATED -> "Your account session is unavailable. Private report details are hidden."
    FailureReason.FORBIDDEN, FailureReason.NOT_FOUND -> "This item is no longer available for that action. Any original report is kept."
    FailureReason.NOT_CONFIGURED -> "Reporting isn’t connected for this session."
    FailureReason.RATE_LIMITED -> "Please wait, then try the same original report again."
    FailureReason.OUTCOME_UNKNOWN -> "We couldn’t confirm delivery. Review the original report before retrying."
    FailureReason.STORAGE_FAILURE -> "We couldn’t confirm that the draft or result was kept on this device. Keep this screen open and try again."
    else -> "We couldn’t confirm that action. Your original report has not been replaced."
}

/** RAM-only, retained by the account owner. Never place this form in a Bundle or saved state.
 * clear() is required on owner retirement; every callback must also check its host lease. */
class ReportFormMemory {
    private var owner: Any? = null
    private var generation = 0L
    private val mutable = MutableStateFlow<ReportFormSnapshot?>(null)
    internal val states: StateFlow<ReportFormSnapshot?> = mutable.asStateFlow()

    fun clear() { owner = null; generation++; mutable.value = null }

    internal fun bind(owner: Any, input: ReportInput) = bind(owner, input.targetType, input.targetId, input.targetLabel,
        input.reason, input.description, input.alsoBlock, input.blockTargetUserId, input.blockTargetLabel)

    internal fun bind(owner: Any, type: String, id: String, label: String, reason: String?, description: String,
        alsoBlock: Boolean = false, blockTargetUserId: String? = null, blockTargetLabel: String? = null) {
        val old = mutable.value
        if (this.owner !== owner || old == null || old.targetType != type || old.targetId != id || old.targetLabel != label ||
            old.blockTargetUserId != blockTargetUserId || old.blockTargetLabel != blockTargetLabel) {
            this.owner = owner
            mutable.value = ReportFormSnapshot(++generation, type, id, label, reason, description, reason, description,
                alsoBlock, alsoBlock, blockTargetUserId, blockTargetLabel)
        } else if (old.baseReason != reason || old.baseDescription != description || old.baseAlsoBlock != alsoBlock) {
            // Acknowledgement of this exact buffer clears dirty; unrelated durable edits remain
            // separate from newer RAM typing instead of silently replacing it.
            mutable.value = ReportFormSnapshot(++generation, type, id, label,
                if (old.dirty) old.reason else reason, if (old.dirty) old.description else description,
                reason, description, if (old.dirty) old.alsoBlock else alsoBlock, alsoBlock, blockTargetUserId, blockTargetLabel)
        }
    }

    internal fun current(owner: Any, type: String, id: String, label: String): ReportFormSnapshot? =
        mutable.value?.takeIf { this.owner === owner && it.targetType == type && it.targetId == id && it.targetLabel == label }

    internal fun edit(expected: ReportFormSnapshot, reason: String?, description: String, alsoBlock: Boolean = expected.alsoBlock): Boolean {
        if (mutable.value !== expected || reportDescriptionError(description) != null ||
            reason != null && reportReasons.none { it.value == reason } ||
            alsoBlock && expected.blockTargetUserId.isNullOrBlank()) return false
        mutable.value = ReportFormSnapshot(++generation, expected.targetType, expected.targetId, expected.targetLabel,
            reason, description, expected.baseReason, expected.baseDescription, alsoBlock, expected.baseAlsoBlock,
            expected.blockTargetUserId, expected.blockTargetLabel)
        return true
    }

    internal fun isCurrent(expected: ReportFormSnapshot): Boolean = mutable.value === expected
}

internal class ReportFormSnapshot(
    val generation: Long, val targetType: String, val targetId: String, val targetLabel: String,
    val reason: String?, val description: String, val baseReason: String?, val baseDescription: String,
    val alsoBlock: Boolean = false, val baseAlsoBlock: Boolean = false,
    val blockTargetUserId: String? = null, val blockTargetLabel: String? = null,
) {
    val dirty: Boolean get() = reason != baseReason || description != baseDescription || alsoBlock != baseAlsoBlock
    override fun toString(): String = "ReportFormSnapshot(<redacted>)"
}
