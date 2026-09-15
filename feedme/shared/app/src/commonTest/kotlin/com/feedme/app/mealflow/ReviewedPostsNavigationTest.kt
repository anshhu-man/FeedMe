package com.feedme.app.mealflow

import com.feedme.core.ports.*
import kotlinx.coroutines.Job
import kotlin.test.*

/** Presentation lifetime only. These tickets never authorize a draft mutation or provider call. */
class ReviewedPostsNavigationTest {
    @Test fun idleAndReadingStateAreExplicitAndNotADraftVisibilityProjection() {
        val f = Fixture()
        assertFalse(f.owner.states.value.visible)
        val ticket = assertNotNull(f.owner.begin(null))
        assertTrue(f.owner.states.value.visible); assertTrue(f.owner.states.value.opening)
        assertTrue(f.owner.finish(ticket, null))
        assertTrue(f.owner.states.value.visible); assertFalse(f.owner.states.value.opening)
        assertNull(f.owner.states.value.failure); f.owner.close()
    }
    @Test fun backCancelsOnlyTheOwnedOpenAndRejectsItsLateCompletion() {
        val f = Fixture(); val owned = Job(); val other = Job()
        val ticket = assertNotNull(f.owner.begin(owned))
        f.owner.leave()
        assertTrue(owned.isCancelled); assertTrue(other.isActive)
        assertFalse(f.owner.finish(ticket, null)); assertFalse(f.owner.states.value.visible)
        other.cancel(); f.owner.close()
    }
    @Test fun oldCompletionCannotOverwriteAFreshExplicitOpen() {
        val f = Fixture(); val old = assertNotNull(f.owner.begin(null))
        f.owner.leave(); val current = assertNotNull(f.owner.begin(null))
        assertFalse(f.owner.finish(old, FailureReason.INVALID_DATA))
        assertTrue(f.owner.finish(current, null)); assertNull(f.owner.states.value.failure)
        assertTrue(f.owner.states.value.visible); f.owner.close()
    }
    @Test fun foreignTicketCannotFinishAnOpen() {
        val f = Fixture(); val actual = assertNotNull(f.owner.begin(null))
        assertFalse(f.owner.finish(Any(), null)); assertTrue(f.owner.states.value.opening)
        assertTrue(f.owner.finish(actual, FailureReason.NOT_CONFIGURED))
        assertEquals(FailureReason.NOT_CONFIGURED, f.owner.states.value.failure)
        f.owner.close()
    }
    @Test fun cancellationOfAnOldOpenCannotDismissTheCurrentRoute() {
        val f = Fixture(); val old = assertNotNull(f.owner.begin(null))
        f.owner.leave(); val current = assertNotNull(f.owner.begin(null))
        f.owner.cancelled(old)
        assertTrue(f.owner.states.value.visible)
        assertTrue(f.owner.finish(current, null)); f.owner.close()
    }
    @Test fun invalidationClearsRouteAndNeverAcceptsOldFinishOrAnotherBegin() {
        val f = Fixture(); val pending = Job(); val ticket = assertNotNull(f.owner.begin(pending))
        f.boundary.clear()
        assertTrue(pending.isCancelled); assertFalse(f.owner.states.value.visible)
        assertFalse(f.owner.finish(ticket, null)); assertNull(f.owner.begin(null))
        f.owner.close()
    }
    @Test fun repeatedCloseDoesNotInvalidateTheBorrowedLease() {
        val f = Fixture(); val ticket = assertNotNull(f.owner.begin(null))
        f.owner.close(); f.owner.close()
        assertTrue(f.boundary.isCurrent(f.lease)); assertFalse(f.owner.finish(ticket, null))
        assertNull(f.owner.begin(null)); assertFalse(f.owner.states.value.visible)
    }
    @Test fun currentFailureIsVisibleButDoesNotImplicitlyRetry() {
        val f = Fixture(); val ticket = assertNotNull(f.owner.begin(null))
        assertTrue(f.owner.finish(ticket, FailureReason.STORAGE_FAILURE))
        repeat(3) {
            assertTrue(f.owner.states.value.visible)
            assertFalse(f.owner.states.value.opening)
            assertEquals(FailureReason.STORAGE_FAILURE, f.owner.states.value.failure)
        }
        f.owner.leave(); assertNull(f.owner.states.value.failure); f.owner.close()
    }
    private class Fixture {
        val boundary = SessionBoundary()
        val lease = boundary.activate(StorageScope("test", ActorKind.ACCOUNT, "navigation-only"))
        val owner = ReviewedPostsNavigationOwner(boundary, lease)
    }
}
