package com.feedme.app.mealflow

import com.feedme.core.ports.FailureReason
import com.feedme.mealflow.KitchenInputIssue
import com.feedme.mealflow.KitchenInputPhase
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KitchenStatusHierarchyTest {
    @Test fun onlyRoutineReadyAndEditingStatusUseCompactPresentation() {
        KitchenInputPhase.entries.forEach { phase ->
            val compact = kitchenUsesCompactStatus(phase, KitchenInputIssue.NONE, null)
            if (phase == KitchenInputPhase.READY || phase == KitchenInputPhase.EDITING) assertTrue(compact)
            else assertFalse(compact, "Keep $phase status prominent")
        }
    }

    @Test fun everyReportedIssueKeepsFullStatusEvenWithRoutinePhase() {
        listOf(KitchenInputPhase.READY, KitchenInputPhase.EDITING).forEach { phase ->
            KitchenInputIssue.entries.filterNot { it == KitchenInputIssue.NONE }.forEach { issue ->
                assertFalse(kitchenUsesCompactStatus(phase, issue, null), "$phase / $issue")
            }
        }
    }

    @Test fun everyFailureKeepsFullStatusEvenWithNoReportedIssue() {
        listOf(KitchenInputPhase.READY, KitchenInputPhase.EDITING).forEach { phase ->
            FailureReason.entries.forEach { failure ->
                assertFalse(kitchenUsesCompactStatus(phase, KitchenInputIssue.NONE, failure), "$phase / $failure")
            }
        }
    }

    @Test fun compactStatusRetainsTheSameAcknowledgementAndLocalDraftCopy() {
        assertTrue(kitchenUsesCompactStatus(KitchenInputPhase.READY, KitchenInputIssue.NONE, null))
        assertTrue(kitchenUsesCompactStatus(KitchenInputPhase.EDITING, KitchenInputIssue.NONE, null))
        val ready = kitchenPhaseMessage(KitchenInputPhase.READY, KitchenInputIssue.NONE)
        val editing = kitchenPhaseMessage(KitchenInputPhase.EDITING, KitchenInputIssue.NONE)
        assertTrue(ready.second.contains("acknowledged"))
        assertTrue(editing.second.contains("local draft", ignoreCase = true))
        assertTrue(editing.second.contains("server", ignoreCase = true))
    }
}
