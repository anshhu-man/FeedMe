package com.feedme.app.mealflow

import com.feedme.core.ports.FailureReason
import com.feedme.mealflow.MealFlowIssue
import com.feedme.mealflow.MealFlowPhase
import com.feedme.mealflow.MealFlowScreen

/** Display only: missing source material is not evidence of recall, permission or completion. */
internal data class MealStatusNotice(val title: String, val message: String)
internal class MealStatusPresentation(val sourceUnavailable: Boolean,
    val notices: List<MealStatusNotice>, val originalRequestRetained: Boolean)

internal fun mealStatusPresentation(meal: MealScreenState, form: MealFormState,
    root: RootRecipeFormState?): MealStatusPresentation {
    val sourceUnavailable = form.values != null && meal.issue != MealFlowIssue.SESSION_UNAVAILABLE && when (meal.screen) {
        MealFlowScreen.CATALOG_RECIPE, MealFlowScreen.SAVED_RECIPE, MealFlowScreen.POST_RECIPE -> meal.rootSource == null
        MealFlowScreen.ROOT_MAKE_MINE -> root?.values == null || currentRootRecipeFormSource(meal, root) == null
        MealFlowScreen.ROOT_VARIANT -> meal.rootProposal == null && meal.rootSource == null
        else -> false
    }
    val accessUnavailable = form.values == null || meal.phase == MealFlowPhase.UNAVAILABLE
    val failures = if (accessUnavailable && !sourceUnavailable) emptyList() else
        listOfNotNull(form.failure, meal.failureReason).distinct()
    val originalRetained = meal.phase == MealFlowPhase.RESOLVING || meal.issue in setOf(
        MealFlowIssue.REQUEST_UNRESOLVED, MealFlowIssue.REPLAY_EXPIRED, MealFlowIssue.RETRY_LATER)
    val notices = buildList {
        if (sourceUnavailable) add(MealStatusNotice(if (meal.postSourceNeedsRefresh) "Check recipe availability" else "Source unavailable",
            if (meal.postSourceNeedsRefresh) "This shared recipe needs a fresh check. Instructions are hidden; your current meal and any sent original are unchanged."
            else "Its instructions are hidden. Use Back to leave this view."))
        // The controller's generic failure bucket can say STORAGE for a confirmed denial.
        // Prefer the actual reason, preserving a genuine storage/unknown-outcome failure below.
        val redundantIssue = sourceUnavailable && (
            meal.issue == MealFlowIssue.STORAGE && failures.isNotEmpty() ||
                meal.issue == MealFlowIssue.INVALID_REPLY && FailureReason.FORBIDDEN in failures)
        if (!redundantIssue) mealIssueMessage(meal.issue)?.let { add(MealStatusNotice("Heads up", it)) }
        failures.filterNot { sourceUnavailable && it in setOf(FailureReason.FORBIDDEN, FailureReason.UNAVAILABLE) }
            .forEach { reason ->
                val notice = when {
                    sourceUnavailable && reason == FailureReason.STORAGE_FAILURE -> MealStatusNotice("Device save not confirmed",
                        "The device could not confirm this change was saved. Keep this screen open; do not assume it is saved.")
                    sourceUnavailable && reason == FailureReason.OUTCOME_UNKNOWN && !originalRetained -> MealStatusNotice("Outcome not confirmed",
                        "The previous action may have completed, but its outcome is not confirmed. Do not assume the change is saved.")
                    else -> MealStatusNotice("Not completed", mealFailureText(reason))
                }
                add(notice)
            }
    }
    return MealStatusPresentation(sourceUnavailable, notices, originalRetained)
}

internal fun mealFailureText(reason: FailureReason) = when (reason) {
    FailureReason.INVALID_DATA -> "Check your input. Servings must be at least 0.1; total and hands-on minutes must be positive whole numbers. Cleanup minutes may be zero or a positive whole number. Selected IDs and choices must be supported."
    FailureReason.OUTCOME_UNKNOWN -> "The previous action may have completed. Keep the original command and use its explicit retry."
    FailureReason.OFFLINE -> "Reconnect when you’re ready. Nothing was submitted automatically."
    FailureReason.CONFLICT -> "The action conflicts with current or pending state. Review your input and the retained request."
    FailureReason.STORAGE_FAILURE -> "The device could not acknowledge this change. It is not marked saved."
    FailureReason.RATE_LIMITED -> "Please wait before trying again; keep your original request."
    else -> "This action is unavailable. No account access, recipe permission or completed result is implied."
}
