package com.feedme.app.mealflow

import com.feedme.core.ports.*
import kotlin.test.*

class CookingUiOwnerTest {
    private val boundary = SessionBoundary()
    private val scope = StorageScope("test.invalid", ActorKind.ACCOUNT, "synthetic-cooking-owner")
    private val lease = boundary.activate(scope)
    private fun owner() = CookingUiOwner(boundary, lease)
    private fun intent(plan: String = "private-plan", kind: CookingConfirmationKind = CookingConfirmationKind.START) =
        CookingConfirmation(kind, plan, "private-session", "9007199254740993", 1)

    @Test fun constructionAndNavigationDoNotMintConsent() {
        val owner = owner(); assertFalse(owner.states.value.visible); owner.show()
        assertTrue(owner.states.value.visible); assertNull(owner.states.value.confirmation)
    }
    @Test fun onlyExactIntentIdentityIsAcceptedEvenWithEqualFields() {
        val owner = owner(); val first = intent(); owner.present(first)
        assertTrue(owner.owns(first)); assertFalse(owner.owns(intent()))
    }
    @Test fun newProposalFencesOldDialogForDifferentAndSamePlan() {
        val owner = owner(); val a = intent(); owner.present(a)
        val b = intent("other-plan"); owner.present(b); assertFalse(owner.owns(a)); assertTrue(owner.owns(b))
        val sameB = intent("other-plan"); owner.present(sameB); assertFalse(owner.owns(b)); assertTrue(owner.owns(sameB))
    }
    @Test fun dismissPreservesNavigationButRemovesConsent() {
        val owner = owner(); val before = intent(); owner.present(before); owner.dismiss()
        assertTrue(owner.states.value.visible); assertNull(owner.states.value.confirmation); assertFalse(owner.owns(before))
    }
    @Test fun leaveFencesSuspendedPreparationWithoutAnyProgressAuthority() {
        val owner = owner(); owner.show(); val token = owner.ticket(); owner.leave()
        assertFalse(owner.matches(token)); assertFalse(owner.states.value.visible); assertNull(owner.states.value.confirmation)
    }
    @Test fun identityInvalidationSynchronouslyRedactsEveryTicket() {
        val owner = owner(); val before = intent(); owner.present(before); boundary.clear()
        assertFalse(owner.owns(before)); assertFalse(owner.states.value.visible); assertNull(owner.states.value.confirmation)
        owner.show(); owner.present(intent()); assertNull(owner.states.value.confirmation)
    }
    @Test fun sameAccountReplacementIsStillDifferentLease() {
        val owner = owner(); val before = intent(); owner.present(before); boundary.activate(scope)
        assertFalse(owner.owns(before)); assertFalse(owner.current()); assertFalse(owner.states.value.visible)
    }
    @Test fun foreignBoundaryCannotPublishAndCloseDoesNotClearActualLease() {
        val foreign = CookingUiOwner(SessionBoundary(), lease); foreign.present(intent()); assertNull(foreign.states.value.confirmation)
        val owner = owner(); owner.present(intent()); owner.close(); owner.close()
        assertNull(owner.states.value.confirmation); assertTrue(boundary.isCurrent(lease)); owner.show(); assertFalse(owner.states.value.visible)
    }
    @Test fun formGenerationAndSavedStateAreIndependentRequiredStartChecks() {
        val form = MealFormOwner(boundary, lease); val ui = owner(); val ticket = form.capture()!!.first
        val before = CookingConfirmation(CookingConfirmationKind.START, "p", null, null, ticket); ui.present(before)
        assertTrue(ui.owns(before)); assertTrue(form.savedCurrent(before.formTicket))
        form.edit { it.copy(servings = "2") }; assertFalse(form.savedCurrent(before.formTicket))
        assertTrue(ui.owns(before)) // UI ownership alone is intentionally NOT a permission.
    }
    @Test fun allTicketAndNavigationLoggingIsRedacted() {
        val owner = owner()
        CookingConfirmationKind.entries.forEach { kind ->
            val ticket = intent(kind = kind); owner.present(ticket)
            assertFalse(ticket.toString().contains("private" + "-plan")); assertFalse(ticket.toString().contains("9007199254740993"))
            assertFalse(owner.states.value.toString().contains("private-session"))
        }
    }
}
