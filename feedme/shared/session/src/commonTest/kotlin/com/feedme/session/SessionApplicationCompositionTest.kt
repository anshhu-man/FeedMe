package com.feedme.session

import com.feedme.core.ports.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SessionApplicationCompositionTest {
    @Test fun rootRequiresExactConfigurationAndCannotReplaceExistingRoot() = runTest {
        val boundary = SessionBoundary(); val dispatcher = StandardTestDispatcher(testScheduler)
        rejected(FailureReason.INVALID_DATA, SessionApplicationComposition.create(boundary, dispatcher, "private-invalid"))
        val root = ok(SessionApplicationComposition.create(boundary, dispatcher, CONFIG))
        rejected(FailureReason.CONFLICT, SessionApplicationComposition.create(boundary, dispatcher, "b".repeat(64)))
        ok(root.close()); ok(SessionApplicationComposition.create(boundary, dispatcher, "b".repeat(64))).close()
    }
    @Test fun unreservedRuntimeAdmissionCannotBypassEvenAnUnreservedApplicationRoot() = runTest {
        val boundary = SessionBoundary(); val root = ok(SessionApplicationComposition.create(boundary, StandardTestDispatcher(testScheduler), CONFIG))
        rejected(FailureReason.CONFLICT, SessionCompositions.admitRuntime(boundary, Any(), null)); ok(root.close())
    }
    @Test fun legacyRuntimeRegistrationExcludesRootUntilItsExactRelease() = runTest {
        val boundary = SessionBoundary(); val dispatcher = StandardTestDispatcher(testScheduler)
        val first = ok(SessionCompositions.admitRuntime(boundary, Any(), null))
        rejected(FailureReason.CONFLICT, SessionApplicationComposition.create(boundary, dispatcher, CONFIG))
        rejected(FailureReason.CONFLICT, SessionCompositions.admitRuntime(boundary, Any(), null))
        ok(SessionCompositions.releaseRuntime(first)); val root = ok(SessionApplicationComposition.create(boundary, dispatcher, CONFIG))
        ok(root.close())
    }
    @Test fun reservedRuntimeReleaseDoesNotReleaseBorrowedStoreReservation() = runTest {
        val boundary = SessionBoundary(); val root = ok(SessionApplicationComposition.create(boundary, StandardTestDispatcher(testScheduler), CONFIG))
        val reservation = ok(root.reserve()); val runtime = ok(SessionCompositions.admitRuntime(boundary, Any(), reservation))
        rejected(FailureReason.CONFLICT, reservation.release()); rejected(FailureReason.CONFLICT, root.close())
        ok(SessionCompositions.releaseRuntime(runtime)); rejected(FailureReason.CONFLICT, root.reserve())
        ok(reservation.release()); ok(root.close())
    }
    @Test fun invalidationFencesOldTokenWithoutDroppingItsOwnerOrNativeCleanupDuty() = runTest {
        val root = ok(SessionApplicationComposition.create(SessionBoundary(), StandardTestDispatcher(testScheduler), CONFIG))
        val token = ok(root.reserve()); val owner = Any(); ok(SessionCompositions.claim(token, owner)); ok(root.invalidate())
        rejected(FailureReason.STALE_SESSION, SessionCompositions.checkClaim(token, owner))
        rejected(FailureReason.CONFLICT, token.release()); rejected(FailureReason.STALE_SESSION, root.reserve())
        ok(SessionCompositions.release(token, owner)); rejected(FailureReason.STALE_SESSION, root.reserve())
        rejected(FailureReason.STALE_SESSION, SessionCompositions.claim(token, Any())); ok(root.close())
    }
    @Test fun rootReplacementCannotReactivateReleasedOldTokenEvenWithSameConfiguration() = runTest {
        val boundary = SessionBoundary(); val dispatcher = StandardTestDispatcher(testScheduler)
        val first = ok(SessionApplicationComposition.create(boundary, dispatcher, CONFIG)); val old = ok(first.reserve())
        ok(old.release()); ok(first.close()); val second = ok(SessionApplicationComposition.create(boundary, dispatcher, CONFIG))
        val current = ok(second.reserve()); rejected(FailureReason.STALE_SESSION, SessionCompositions.claim(old, Any()))
        rejected(FailureReason.STALE_SESSION, first.reserve()); ok(current.release()); ok(second.close())
    }
    @Test fun activeBoundaryAndWrongBoundaryCannotAcquireCompositionAuthority() = runTest {
        val boundary = SessionBoundary(); val dispatcher = StandardTestDispatcher(testScheduler)
        boundary.activate(StorageScope("test", ActorKind.ACCOUNT, "private-owner"))
        rejected(FailureReason.CONFLICT, SessionApplicationComposition.create(boundary, dispatcher, CONFIG)); boundary.clear()
        val root = ok(SessionApplicationComposition.create(boundary, dispatcher, CONFIG)); val token = ok(root.reserve())
        rejected(FailureReason.CONFLICT, SessionCompositions.admitRuntime(SessionBoundary(), Any(), token))
        ok(token.release()); ok(root.close())
    }
    @Test fun reservationAndRootRenderNoPrivateConfigurationOrOwner() = runTest {
        val root = ok(SessionApplicationComposition.create(SessionBoundary(), StandardTestDispatcher(testScheduler), CONFIG))
        val token = ok(root.reserve()); assertFalse(root.toString().contains(CONFIG)); assertFalse(token.toString().contains(CONFIG))
        ok(token.release()); ok(token.release()); ok(root.close()); ok(root.close())
    }
    companion object {
        private val CONFIG = "a".repeat(64)
        private fun <T> ok(result: PortResult<T>) = assertIs<PortResult.Value<T>>(result).value
        private fun rejected(reason: FailureReason, result: PortResult<*>) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
    }
}
