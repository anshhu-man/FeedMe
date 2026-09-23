package com.feedme.app.mealflow

import com.feedme.app.blueprint.*
import com.feedme.kitchen.CookingTimerReducer
import com.feedme.kitchen.CookingTimerTiming
import com.feedme.mealflow.timers.CookingTimerDurationInput
import com.feedme.mealflow.timers.CookingTimerFlowIssue

/** Only a local view selection. A draft uses the actual pinned step ID, never an invented timer ID. */
internal data class BlueprintTimerSelection(val kind: BlueprintTimerChoiceKind, val id: String) {
    override fun toString() = "BlueprintTimerSelection(kind=$kind, private=<redacted>)"
}
internal class BlueprintTimerPresentation(val view: CookingTimerScreenState, val blueprint: BlueprintTimerState,
    val selection: BlueprintTimerSelection?) {
    override fun toString() = "BlueprintTimerPresentation(<redacted>)"
}

/** Lossless projection only. Unresolved writes, cleanup-only observations and unavailable pins
 * retain the existing recovery reader; no countdown is parsed from localized display text. */
internal fun blueprintTimerPresentation(view: CookingTimerScreenState, selected: BlueprintTimerSelection? = null,
    overflowExpanded: Boolean = false): BlueprintTimerPresentation? {
    if (!view.visible || !view.eligible || view.pending || view.failure != null || view.issue != CookingTimerFlowIssue.NONE ||
        view.sessionId.isNullOrBlank() || view.planId.isNullOrBlank() || view.localRevision == null || view.localRevision < 0 ||
        view.selectedStepId.isNullOrBlank() || view.stepLabel.isNullOrBlank()) return null
    if (view.rows.any { it.id.isBlank() || it.stepId.isNullOrBlank() || it.durationSeconds == null ||
        it.durationSeconds !in 1..CookingTimerReducer.MAX_DURATION_SECONDS || it.status !in setOf("running", "paused") } ||
        view.rows.map { it.id }.distinct().size != view.rows.size || view.rows.any { it.id == view.selectedStepId }) return null
    val draft = BlueprintTimerSelection(BlueprintTimerChoiceKind.STEP_DRAFT, view.selectedStepId)
    // Starting a new timer is offered for the actual current step. Existing rows are never
    // implicitly chosen by index, and a vanished selection is not replaced by another timer.
    val choices = listOf(BlueprintTimerChoice(draft.id, BlueprintTimerSlot.CURRENT_STEP,
        "New timer · ${view.stepLabel}", BlueprintTimerChoiceKind.STEP_DRAFT)) + view.rows.map { row ->
        BlueprintTimerChoice(row.id, if (row.stepId == view.selectedStepId) BlueprintTimerSlot.CURRENT_STEP else BlueprintTimerSlot.ANOTHER_ACTIVE_TIMER,
            "${row.label} · ${row.timing} · ${row.remaining}", BlueprintTimerChoiceKind.RETAINED_TIMER)
    }
    val chosen = (selected ?: draft.takeIf { view.rows.isEmpty() })?.takeIf { target -> choices.count { it.id == target.id && it.kind == target.kind } == 1 }
    val row = chosen?.takeIf { it.kind == BlueprintTimerChoiceKind.RETAINED_TIMER }?.let { target -> view.rows.single { it.id == target.id } }
    val draftSelected = chosen?.kind == BlueprintTimerChoiceKind.STEP_DRAFT
    val duration = if (draftSelected) view.durationText else row?.durationSeconds?.toString().orEmpty()
    val parsedDuration = CookingTimerDurationInput.seconds(duration)
    val phase = when { draftSelected -> BlueprintTimerPhase.READY; row?.status == "running" -> BlueprintTimerPhase.RUNNING; row?.status == "paused" -> BlueprintTimerPhase.PAUSED; else -> null }
    val remaining = if (draftSelected) parsedDuration else row?.remainingMillis?.takeIf {
        it >= 0 && row.timingKind in setOf(CookingTimerTiming.RUNNING, CookingTimerTiming.PAUSED, CookingTimerTiming.DUE)
    }?.let { it / 1000 + if (it % 1000 > 0) 1 else 0 }
    val allowed = buildSet {
        add(BlueprintCookingAction.TIMER_BACK); add(BlueprintCookingAction.BACK_TO_COOKING)
        if (!view.busy) {
            if (draftSelected && view.canStart && parsedDuration != null) add(BlueprintCookingAction.START_TIMER)
            if (row != null) {
                if (row.status == "running" && view.canStop) add(BlueprintCookingAction.PAUSE_TIMER)
                if (row.status == "paused" && view.canStart) add(BlueprintCookingAction.RESUME_TIMER)
                if (view.canStop) { add(BlueprintCookingAction.RESET_TIMER); add(BlueprintCookingAction.CANCEL_TIMER) }
            }
        }
    }
    val blueprint = BlueprintTimerState(durationSeconds = duration, choices = choices, selectedTimerId = chosen?.id,
        remainingSeconds = remaining, phase = phase, durationHeadline = parsedDuration?.let(::timerDurationHeadline),
        allowEdit = draftSelected && !view.busy, allowSelection = !view.busy, overflowExpanded = overflowExpanded,
        allowedActions = allowed, status = buildList {
            if (view.busy) add("Saving your timer action…")
            if (chosen == null) add("Choose the exact retained timer, or choose Current step to prepare a new timer.")
            if (row != null) add(row.timing)
            if (row?.timingKind == CookingTimerTiming.DUE) add("Estimated time reached. Check your food and pinned instructions. No step was advanced or meal completed.")
            else if (view.due) add("A timer reached its estimated end. Check your food and pinned instructions. No step was advanced or meal completed.")
        }.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        alertNote = listOfNotNull(row?.alert,
            "Timers are estimates, not food-safety checks. Alerts depend on the actual device service and permissions; background delivery is not guaranteed. Back does not pause or cancel. Open More for alert cleanup and observation tools.").joinToString("\n"))
    return BlueprintTimerPresentation(view, blueprint, chosen)
}

private fun timerDurationHeadline(seconds: Long): String = if (seconds % 60L == 0L) {
    val minutes = seconds / 60; "$minutes ${if (minutes == 1L) "minute" else "minutes"}."
} else "$seconds ${if (seconds == 1L) "second" else "seconds"}."

/** Calls only the existing captured callbacks. In particular CANCEL is review, not cancellation. */
internal fun dispatchBlueprintTimer(presentation: BlueprintTimerPresentation, action: BlueprintCookingAction,
    current: Boolean, actions: CookingTimerScreenActions) {
    if (!current || !presentation.blueprint.allows(action)) return
    val retained = presentation.selection?.takeIf { it.kind == BlueprintTimerChoiceKind.RETAINED_TIMER }?.id
    when (action) {
        BlueprintCookingAction.TIMER_BACK, BlueprintCookingAction.BACK_TO_COOKING -> actions.back()
        BlueprintCookingAction.START_TIMER -> if (presentation.selection?.kind == BlueprintTimerChoiceKind.STEP_DRAFT) actions.start()
        BlueprintCookingAction.PAUSE_TIMER -> retained?.let(actions.pause)
        BlueprintCookingAction.RESUME_TIMER -> retained?.let(actions.resume)
        BlueprintCookingAction.RESET_TIMER -> retained?.let(actions.reset)
        BlueprintCookingAction.CANCEL_TIMER -> retained?.let(actions.remove)
        else -> Unit
    }
}

/** Read-only UI selection/edit admission; never refreshes or obtains a newer owner binding. */
internal fun blueprintTimerSelection(presentation: BlueprintTimerPresentation, edit: BlueprintTimerEdit.Select,
    current: Boolean): BlueprintTimerSelection? = if (!current || !presentation.blueprint.canSelect) null else
    presentation.blueprint.choices.singleOrNull { it.id == edit.timerId }?.let { BlueprintTimerSelection(it.kind, it.id) }

internal fun dispatchBlueprintTimerDuration(presentation: BlueprintTimerPresentation, edit: BlueprintTimerEdit.Duration,
    current: Boolean, actions: CookingTimerScreenActions) {
    if (current && presentation.blueprint.canEdit && presentation.selection?.kind == BlueprintTimerChoiceKind.STEP_DRAFT) actions.duration(edit.value)
}

/** Closing/reopening More invalidates its queued callbacks even if the domain owner is unchanged.
 * This only rejects stale UI delivery; the original callbacks still perform every owner check. */
internal fun guardCookingTimerTools(actions: CookingTimerScreenActions, current: () -> Boolean) = CookingTimerScreenActions(
    back = { if (current()) actions.back() }, duration = { if (current()) actions.duration(it) },
    start = { if (current()) actions.start() }, pause = { if (current()) actions.pause(it) },
    resume = { if (current()) actions.resume(it) }, reset = { if (current()) actions.reset(it) },
    remove = { if (current()) actions.remove(it) }, cleanup = { if (current()) actions.cleanup(it) },
    refresh = { if (current()) actions.refresh() })
