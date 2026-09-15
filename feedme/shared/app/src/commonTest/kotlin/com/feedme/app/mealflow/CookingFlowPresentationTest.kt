package com.feedme.app.mealflow

import com.feedme.contracts.*
import com.feedme.core.ports.FailureReason
import com.feedme.kitchen.CookingStatus
import com.feedme.mealflow.*
import kotlin.test.*

/** Display-only synthetic fixtures. Actual fromSession/native ownership is tested by Android host tests. */
class CookingFlowPresentationTest {
    private fun plan(review: String = "published") = PlanWire.from(WireDocument.parse("""{
      "id":"00000000-0000-4000-8000-000000000001","version":1,"createdAt":"2026-09-14T00:00:00Z","updatedAt":"2026-09-14T00:00:00Z",
      "status":"ready","mode":"assemble","constraints":{},"missingIngredients":[],"changes":[],"reasons":[],"catalogRevision":"synthetic",
      "recipeSnapshot":{"id":"00000000-0000-4000-8000-000000000003","recipeId":"00000000-0000-4000-8000-000000000002","version":1,
      "createdAt":"2026-09-14T00:00:00Z","updatedAt":"2026-09-14T00:00:00Z","title":"Synthetic pinned plan","reviewStatus":"$review",
      "servings":1.5000,"activeMinutes":1,"totalMinutes":1,"utensilCount":1,"equipmentIds":["bowl"],"modes":["assemble"],"tasteTags":[],
      "ingredients":[{"ingredientId":"00000000-0000-4000-8000-000000000004","quantity":1.230000,"unit":"g","optional":false}],
      "steps":[{"stepId":"first","position":1,"instruction":"Synthetic first step","ingredientIds":[],"requiredEquipmentIds":[],"mandatorySafetyStep":true,"durationSeconds":60},
      {"stepId":"last","position":2,"instruction":"Synthetic last step","ingredientIds":[],"requiredEquipmentIds":[],"mandatorySafetyStep":false}]}}"""))
    private fun view(phase: CookingFlowPhase = CookingFlowPhase.COOKING, status: CookingStatus = CookingStatus.ACTIVE,
        local: Int = 0, acknowledged: Boolean = false, historical: Boolean = true, issue: CookingFlowIssue = CookingFlowIssue.NONE,
        failure: FailureReason? = null, step: String = "first", recipeStatus: String = "published",
        pending: List<CookingPendingRow> = emptyList(), selected: Boolean = true) = CookingScreenState(phase,
        CookingFlowScreen.COOK, plan(recipeStatus), if (selected) "synthetic-session" else null, status, step,
        listOf("last"), local, historical, acknowledged, true, true, true, issue, failure, pending)
    private fun pending(phase: String = "RETRY_WAIT", operation: String = "createCookSession", attempts: Int = 1) =
        CookingPendingRow("synthetic-command", operation, phase, attempts, "OUTCOME_UNKNOWN", 10, true, attempts == 0)

    @Test fun currentStepUsesExactStableIdNotPositionOrCompletedSet() {
        val view = view(); assertEquals("first", view.currentStep!!.stepId.value)
        assertTrue(view.currentStep!!.mandatorySafetyStep); assertEquals(listOf("last"), view.completedStepIds)
        assertTrue(view.instructionsVisible); assertFalse(view.done)
    }
    @Test fun missingStepNeverClampsToFirstOrLast() { assertNull(view(step = "missing").currentStep); assertFalse(view(step = "missing").instructionsVisible) }
    @Test fun quantitiesAndDurationsStayExactAndDoNotBecomeTimers() {
        val view = view(); assertEquals("1.5000", view.recipe!!.servings.jsonToken)
        assertEquals("1.230000", view.recipe!!.ingredients.single().quantity.jsonToken)
        assertEquals("60", view.currentStep!!.durationSeconds.valueOrNull()!!.jsonToken)
    }
    @Test fun lastStepOrMarkedLastDoesNotImplyCompletion() {
        val view = view(step = "last"); assertFalse(view.done); assertNull(view.completionText)
    }
    @Test fun localAcknowledgedAndHistoricalCompletionHaveDifferentCopy() {
        assertEquals("Finished on this device · waiting to sync", view(CookingFlowPhase.COMPLETED, CookingStatus.COMPLETED, local = 1).completionText)
        assertEquals("Completion acknowledged by the server", view(CookingFlowPhase.COMPLETED, CookingStatus.COMPLETED, acknowledged = true, historical = false).completionText)
        assertEquals("Retained completed session · historical observation", view(CookingFlowPhase.COMPLETED, CookingStatus.COMPLETED).completionText)
    }
    @Test fun errorRecallOriginAndConflictOverrideCelebratoryCompletion() {
        for (issue in listOf(CookingFlowIssue.RECALLED, CookingFlowIssue.ORIGIN_CHANGED, CookingFlowIssue.RECONCILIATION_REQUIRED))
            assertFalse(view(CookingFlowPhase.COMPLETED, CookingStatus.COMPLETED, issue = issue).done)
        assertFalse(view(CookingFlowPhase.CONFLICT, CookingStatus.COMPLETED).done)
        assertFalse(view(CookingFlowPhase.COMPLETED, CookingStatus.COMPLETED, failure = FailureReason.OUTCOME_UNKNOWN).done)
    }
    @Test fun retainedRetiredMayBeReadButRecallUnreviewedAndMissingPinCannot() {
        assertTrue(view(recipeStatus = "retired").instructionsVisible)
        for (status in listOf("personal", "recalled", "approved")) assertFalse(view(recipeStatus = status).instructionsVisible)
        assertFalse(view(selected = false).instructionsVisible)
        assertFalse(view(issue = CookingFlowIssue.RECALLED).instructionsVisible)
    }
    @Test fun originalStartAndLostFinalizationCannotBecomeOrdinaryStepSyncOrDiscard() {
        val view = view(pending = listOf(pending("FINALIZATION_REQUIRED")))
        assertTrue(view.hasStartRetry); assertFalse(view.canSync); assertFalse(view.canDiscardStart)
        assertTrue(view.pending.single().message.contains("still needs its local acknowledgement"))
        assertFalse(view.pending.single().message.contains("acknowledged applied"))
        assertTrue(view(pending = listOf(pending(attempts = 0))).canDiscardStart)
    }
    @Test fun onlyExplicitPendingProgressEnablesHeadSync() {
        assertFalse(view().canSync); assertTrue(view(local = 1).canSync)
        assertTrue(view(pending = listOf(pending(operation = "updateCookSession"))).canSync)
        assertFalse(view(CookingFlowPhase.START_CONFIRMATION, local = 1).canSync)
    }
    @Test fun allIssuesExplainUnavailableActionWithoutRawPrivatePayload() {
        assertNull(cookingIssueMessage(CookingFlowIssue.NONE))
        CookingFlowIssue.entries.filter { it != CookingFlowIssue.NONE }.forEach { assertFalse(cookingIssueMessage(it).isNullOrBlank()) }
        assertFalse(view().toString().contains("Synthetic pinned")); assertFalse(pending().toString().contains("synthetic-command"))
    }
}
