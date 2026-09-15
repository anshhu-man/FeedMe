package com.feedme.development.progress

import com.feedme.core.ports.FailureReason
import kotlin.test.*

class ProgressConsentOwnerTest {
    @Test fun onlyTheExactCurrentProposalCanBeConsumed() {
        val owner = ProgressConsentOwner()
        val first = assertNotNull(owner.prepare(ProgressConfirmationKind.RESET))
        assertTrue(owner.owns(first)); assertTrue(owner.consume(first))
        assertFalse(owner.owns(first)); assertFalse(owner.consume(first))
    }

    @Test fun replacementRequiresAnotherExplicitConfirmation() {
        val owner = ProgressConsentOwner()
        val old = assertNotNull(owner.prepare(ProgressConfirmationKind.RESET))
        val next = assertNotNull(owner.prepare(ProgressConfirmationKind.RESET))
        assertNotSame(old, next); assertFalse(owner.consume(old)); assertTrue(owner.owns(next))
        assertTrue(owner.consume(next))
    }

    @Test fun setupAbortAndResetTicketsNeverSubstituteForEachOther() {
        val owner = ProgressConsentOwner()
        val reset = assertNotNull(owner.prepare(ProgressConfirmationKind.RESET))
        val abort = assertNotNull(owner.prepare(ProgressConfirmationKind.ABORT_SETUP))
        assertEquals(ProgressConfirmationKind.ABORT_SETUP, abort.kind)
        assertFalse(owner.consume(reset)); assertTrue(owner.consume(abort))
    }

    @Test fun foreignApplicationOwnerCannotUseAnOtherwiseValidTicket() {
        val original = ProgressConsentOwner(); val replacement = ProgressConsentOwner()
        val ticket = assertNotNull(original.prepare(ProgressConfirmationKind.RESET))
        assertFalse(replacement.consume(ticket)); assertTrue(original.owns(ticket))
    }

    @Test fun matchingFieldsCannotReconstructTheOpaqueCurrentTicket() {
        val owner = ProgressConsentOwner()
        val ticket = assertNotNull(owner.prepare(ProgressConfirmationKind.RESET))
        val copied = ProgressConfirmation(ticket.kind, ticket.owner, ticket.generation)
        assertFalse(owner.consume(copied)); assertTrue(owner.consume(ticket))
    }

    @Test fun dismissalNeverConfirmsAndRequiresFreshPresentation() {
        val owner = ProgressConsentOwner()
        val ticket = assertNotNull(owner.prepare(ProgressConfirmationKind.ABORT_SETUP))
        owner.dismiss(); owner.dismiss()
        assertFalse(owner.consume(ticket))
        val next = assertNotNull(owner.prepare(ProgressConfirmationKind.ABORT_SETUP))
        assertTrue(owner.consume(next))
    }

    @Test fun closedOwnerCannotReviveOldOrNewConsent() {
        val owner = ProgressConsentOwner()
        val ticket = assertNotNull(owner.prepare(ProgressConfirmationKind.RESET))
        owner.close(); owner.close()
        assertFalse(owner.consume(ticket)); assertNull(owner.prepare(ProgressConfirmationKind.RESET))
    }

    @Test fun stateAndConsentDiagnosticsNeverIncludePrivateDetailOrOwnerIdentity() {
        val owner = ProgressConsentOwner()
        val ticket = assertNotNull(owner.prepare(ProgressConfirmationKind.ABORT_SETUP))
        val state = ProgressHostState(ProgressHostPhase.RECOVERY_REQUIRED, failure = FailureReason.CONFLICT,
            confirmation = ticket, detail = "private fixture marker")
        assertFalse(state.toString().contains("private fixture marker"))
        assertFalse(ticket.toString().contains(ticket.owner.toString()))
        assertTrue(state.toString().contains("RECOVERY_REQUIRED"))
    }
}
