package com.feedme.core

import com.feedme.core.ports.*
import kotlin.test.*

class SessionInvalidationTest {
    private val scope = StorageScope("test", ActorKind.ACCOUNT, "test-owner")

    @Test fun clearRedactsSynchronouslyAfterLeaseBecomesStale() {
        val boundary = SessionBoundary()
        val lease = boundary.activate(scope)
        var visible: String? = "private state"
        boundary.onInvalidated(lease) {
            assertFalse(boundary.isCurrent(lease))
            assertNull(boundary.current())
            visible = null
        }
        assertEquals("private state", visible)
        boundary.clear()
        assertNull(visible)
    }

    @Test fun replacingTheSameScopeInvalidatesTheOldIncarnationExactlyOnce() {
        val boundary = SessionBoundary()
        val old = boundary.activate(scope)
        var calls = 0
        boundary.onInvalidated(old) { calls++; assertFalse(boundary.isCurrent(old)) }
        val replacement = boundary.activate(scope)
        assertEquals(1, calls)
        assertTrue(boundary.isCurrent(replacement))
        boundary.clear(); boundary.activate(scope); boundary.clear()
        assertEquals(1, calls)
    }

    @Test fun alreadyStaleOrForeignLeasesSignalImmediatelyWithoutGrantingAccess() {
        val boundary = SessionBoundary()
        val stale = boundary.activate(scope)
        boundary.clear()
        val other = SessionBoundary().activate(scope)
        var calls = 0
        for (lease in listOf(stale, other)) boundary.onInvalidated(lease) { calls++ }
        assertEquals(2, calls)
        assertNull(boundary.current())
        boundary.activate(scope); boundary.clear()
        assertEquals(2, calls)
    }

    @Test fun disposedSubscriptionsAreIdempotentAndDoNotRetainNotificationWork() {
        val boundary = SessionBoundary()
        val lease = boundary.activate(scope)
        var calls = 0
        val subscription = boundary.onInvalidated(lease) { calls++ }
        subscription.close(); subscription.close()
        boundary.clear()
        assertEquals(0, calls)
        subscription.close()
    }

    @Test fun anObserverExceptionCannotUndoClearingOrSkipOtherObservers() {
        val boundary = SessionBoundary()
        val lease = boundary.activate(scope)
        var reached = 0
        boundary.onInvalidated(lease) { throw IllegalStateException("private observer detail") }
        boundary.onInvalidated(lease) { reached++ }
        boundary.clear()
        assertNull(boundary.current())
        assertFalse(boundary.isCurrent(lease))
        assertEquals(1, reached)
    }

    @Test fun subscriptionDisposalDuringNotificationDoesNotCorruptTheRemainingCallbacks() {
        val boundary = SessionBoundary()
        val lease = boundary.activate(scope)
        var calls = 0
        var disposedCallbackRan = false
        lateinit var disposable: SessionInvalidationSubscription
        boundary.onInvalidated(lease) { disposable.close(); calls++ }
        disposable = boundary.onInvalidated(lease) { disposedCallbackRan = true }
        boundary.onInvalidated(lease) { calls++ }
        boundary.clear()
        assertEquals(2, calls)
        assertFalse(disposedCallbackRan)
    }
}
