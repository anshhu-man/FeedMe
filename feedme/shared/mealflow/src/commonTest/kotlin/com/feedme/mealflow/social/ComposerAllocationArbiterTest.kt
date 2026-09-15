package com.feedme.mealflow.social

import com.feedme.core.ports.*
import com.feedme.mealflow.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Actual shared arbiter with deny-I/O ports. Boundary registration/disposal and every arbiter
 * call stay on one serialized test dispatcher. Competing coroutines are not a multithreaded
 * SessionBoundary test. These checks do not prove either controller claims before ID allocation
 * or releases only after durable registration; connected controller tests must establish that. */
class ComposerAllocationArbiterTest {
    @Test fun emptyInspectionNeitherAllocatesNorReadsPrivateState() {
        fixture { f ->
            assertFalse(ComposerAllocationArbiter.hasReviewedSave(f.access, f.boundary, ROOT))
            assertFalse(ComposerAllocationArbiter.hasPublication(f.access, f.boundary, ROOT))
            f.noIo()
        }
    }

    @Test fun sameRootExcludesCompetingPurposeInEitherArrivalOrder() {
        for (saveFirst in listOf(false, true)) fixture { f ->
            val first = f.reserve(saveFirst, ROOT)
            try {
                conflict { f.reserve(!saveFirst, ROOT) }
                assertTrue(f.has(saveFirst, ROOT)); assertFalse(f.has(!saveFirst, ROOT))
                f.noIo()
            } finally { ComposerAllocationArbiter.release(first) }
        }
    }

    @Test fun eachPurposeHasOneSlotEvenWhenItsNextRootIsDifferent() {
        for (save in listOf(false, true)) fixture { f ->
            val first = f.reserve(save, ROOT)
            try {
                conflict { f.reserve(save, OTHER_ROOT) }
                assertTrue(f.has(save, ROOT)); assertFalse(f.has(save, OTHER_ROOT))
                assertFalse(f.has(!save, ROOT)); f.noIo()
            } finally { ComposerAllocationArbiter.release(first) }
        }
    }

    @Test fun differentRootsMayHoldBothPurposesWithoutLosingEitherReservation() {
        fixture { f ->
            val save = f.reserve(true, ROOT); val publication = f.reserve(false, OTHER_ROOT)
            try {
                assertTrue(f.has(true, ROOT)); assertTrue(f.has(false, OTHER_ROOT))
                assertFalse(f.has(false, ROOT)); assertFalse(f.has(true, OTHER_ROOT))
                conflict { f.reserve(true, THIRD_ROOT) }; conflict { f.reserve(false, THIRD_ROOT) }
                ComposerAllocationArbiter.release(save)
                assertFalse(f.has(true, ROOT)); assertTrue(f.has(false, OTHER_ROOT))
                conflict { f.reserve(true, OTHER_ROOT) }
                val replacement = f.reserve(true, THIRD_ROOT)
                try { assertTrue(f.has(true, THIRD_ROOT)); assertTrue(f.has(false, OTHER_ROOT)) }
                finally { ComposerAllocationArbiter.release(replacement) }
                f.noIo()
            } finally { ComposerAllocationArbiter.release(save); ComposerAllocationArbiter.release(publication) }
        }
    }

    @Test fun releasedOldSlotCannotRemoveNewReservationOfSamePurposeAndRoot() {
        fixture { f ->
            val old = f.reserve(true, ROOT); ComposerAllocationArbiter.release(old)
            val current = f.reserve(true, ROOT)
            try {
                assertNotSame(old, current)
                repeat(4) { ComposerAllocationArbiter.release(old) }
                assertTrue(f.has(true, ROOT)); conflict { f.reserve(false, ROOT) }; f.noIo()
            } finally { ComposerAllocationArbiter.release(current) }
        }
    }

    @Test fun constructedSlotCannotReleaseEitherActualReservation() {
        fixture { f ->
            val save = f.reserve(true, ROOT); val publication = f.reserve(false, OTHER_ROOT)
            try {
                repeat(4) { ComposerAllocationArbiter.release(ComposerAllocationSlot()) }
                assertTrue(f.has(true, ROOT)); assertTrue(f.has(false, OTHER_ROOT))
                conflict { f.reserve(false, ROOT) }; conflict { f.reserve(true, OTHER_ROOT) }; f.noIo()
            } finally { ComposerAllocationArbiter.release(save); ComposerAllocationArbiter.release(publication) }
        }
    }

    @Test fun invalidationClearsBothSlotsAndStaleLeaseCannotReserveAgain() {
        fixture { f ->
            val save = f.reserve(true, ROOT); val publication = f.reserve(false, OTHER_ROOT)
            f.boundary.clear()
            assertFalse(f.has(true, ROOT)); assertFalse(f.has(false, OTHER_ROOT))
            stale { f.reserve(true, ROOT) }; stale { f.reserve(false, OTHER_ROOT) }
            ComposerAllocationArbiter.release(save); ComposerAllocationArbiter.release(publication); f.noIo()
        }
    }

    @Test fun equalScopeReactivationDoesNotLetOldSlotAffectTheNewLease() {
        fixture { f ->
            val old = f.reserve(true, ROOT)
            val nextLease = f.boundary.activate(f.scope)
            val nextAccess = f.accessFor(nextLease)
            val next = ComposerAllocationArbiter.reserveReviewedSave(nextAccess, f.boundary, ROOT)
            try {
                assertNotSame(f.lease, nextLease); assertEquals(f.lease.scope, nextLease.scope)
                ComposerAllocationArbiter.release(old)
                assertFalse(f.has(true, ROOT))
                assertTrue(ComposerAllocationArbiter.hasReviewedSave(nextAccess, f.boundary, ROOT))
                stale { f.reserve(false, ROOT) }; f.noIo()
            } finally { ComposerAllocationArbiter.release(next) }
        }
    }

    @Test fun independentSessionsWithEqualValuesKeepSeparateReservations() {
        fixture { first -> fixture { second ->
            val save = first.reserve(true, ROOT); val publication = second.reserve(false, ROOT)
            try {
                assertEquals(first.scope, second.scope); assertNotSame(first.lease, second.lease)
                assertTrue(first.has(true, ROOT)); assertFalse(first.has(false, ROOT))
                assertTrue(second.has(false, ROOT)); assertFalse(second.has(true, ROOT))
                first.boundary.clear()
                assertFalse(first.has(true, ROOT)); assertTrue(second.has(false, ROOT))
                conflict { second.reserve(true, ROOT) }; first.noIo(); second.noIo()
            } finally { ComposerAllocationArbiter.release(save); ComposerAllocationArbiter.release(publication) }
        } }
    }

    @Test fun inspectionRequiresExactAccessStoreOriginAndBoundaryIdentity() {
        fixture { f ->
            val slot = f.reserve(true, ROOT); val foreignBoundary = SessionBoundary()
            try {
                val cloned = f.accessFor(f.lease)
                val otherStore = f.accessFor(f.lease, DenyStore())
                val otherOrigin = f.accessFor(f.lease, origin = OTHER_ROOT)
                for (candidate in listOf(cloned, otherStore, otherOrigin)) {
                    assertFalse(ComposerAllocationArbiter.hasReviewedSave(candidate, f.boundary, ROOT))
                    assertFalse(ComposerAllocationArbiter.hasPublication(candidate, f.boundary, ROOT))
                }
                assertFalse(ComposerAllocationArbiter.hasReviewedSave(f.access, foreignBoundary, ROOT))
                assertTrue(f.has(true, ROOT)); f.noIo()
            } finally { ComposerAllocationArbiter.release(slot); foreignBoundary.clear() }
        }
    }

    @Test fun malformedOrNoncanonicalRootsDoNotConsumeAnySlot() {
        fixture { f ->
            for (root in listOf("", "not-a-uuid", ROOT.uppercase(), " $ROOT", "$ROOT ", "../../$ROOT")) {
                for (save in listOf(false, true)) {
                    assertEquals(FailureReason.INVALID_DATA, assertFailsWith<MealFailure> { f.reserve(save, root) }.reason)
                }
            }
            val save = f.reserve(true, ROOT); val publication = f.reserve(false, OTHER_ROOT)
            try { assertTrue(f.has(true, ROOT)); assertTrue(f.has(false, OTHER_ROOT)); f.noIo() }
            finally { ComposerAllocationArbiter.release(save); ComposerAllocationArbiter.release(publication) }
        }
    }

    @Test fun slotDiagnosticsDoNotExposeRootSessionOrPurposeData() {
        fixture { f ->
            val slot = f.reserve(true, ROOT)
            try {
                assertEquals("ComposerAllocationSlot(<redacted>)", slot.toString())
                for (secret in listOf(ROOT, f.scope.actorId, f.scope.environment, ORIGIN)) {
                    assertFalse(slot.toString().contains(secret))
                }
                f.noIo()
            } finally { ComposerAllocationArbiter.release(slot) }
        }
    }

    @Test fun competingSameRootCoroutinesHaveExactlyOneWinnerAndNoImplicitRelease() = runTest {
        val f = Fixture()
        try {
            repeat(32) { index ->
                val start = CompletableDeferred<Unit>()
                val firstSave = index % 2 == 0
                val first = async { start.await(); runCatching { f.reserve(firstSave, ROOT) } }
                val second = async { start.await(); runCatching { f.reserve(!firstSave, ROOT) } }
                start.complete(Unit)
                val attempts = listOf(firstSave to first.await(), !firstSave to second.await())
                val winners = attempts.filter { it.second.isSuccess }
                assertEquals(1, winners.size)
                val winner = winners.single(); val slot = winner.second.getOrThrow()
                try {
                    val loser = attempts.single { it.second.isFailure }
                    assertEquals(FailureReason.CONFLICT, assertIs<MealFailure>(loser.second.exceptionOrNull()).reason)
                    assertTrue(f.has(winner.first, ROOT)); assertFalse(f.has(!winner.first, ROOT))
                    conflict { f.reserve(!winner.first, ROOT) }
                } finally { ComposerAllocationArbiter.release(slot) }
                assertFalse(f.has(true, ROOT)); assertFalse(f.has(false, ROOT))
            }
            f.noIo()
        } finally { f.close() }
    }

    @Test fun competingDifferentRootCoroutinesCanBothRetainTheirOwnPurpose() = runTest {
        val f = Fixture()
        try {
            val start = CompletableDeferred<Unit>()
            val saving = async { start.await(); f.reserve(true, ROOT) }
            val publishing = async { start.await(); f.reserve(false, OTHER_ROOT) }
            start.complete(Unit)
            val save = saving.await(); val publication = publishing.await()
            try {
                assertTrue(f.has(true, ROOT)); assertTrue(f.has(false, OTHER_ROOT))
                ComposerAllocationArbiter.release(save)
                assertTrue(f.has(false, OTHER_ROOT)); assertFalse(f.has(true, ROOT)); f.noIo()
            } finally { ComposerAllocationArbiter.release(save); ComposerAllocationArbiter.release(publication) }
        } finally { f.close() }
    }

    private class DenyStore : PrivateStateStore {
        var calls = 0
        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> { calls++; error("Unexpected private read") }
        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> { calls++; error("Unexpected private commit") }
        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> { calls++; error("Unexpected private erase") }
    }
    private class Fixture {
        val scope = StorageScope("arbiter-fixture", ActorKind.ACCOUNT, "opaque-synthetic-owner")
        val boundary = SessionBoundary(); val lease = boundary.activate(scope); val store = DenyStore()
        var transportCalls = 0
        val transport = object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                transportCalls++; error("Unexpected transport")
            }
        }
        val access = accessFor(lease)
        fun accessFor(lease: SessionLease, store: PrivateStateStore = this.store, origin: String = ORIGIN) =
            AuthenticatedMealPlanningAccess(lease, origin, store, transport, true)
        fun reserve(save: Boolean, root: String) = if (save)
            ComposerAllocationArbiter.reserveReviewedSave(access, boundary, root)
            else ComposerAllocationArbiter.reservePublication(access, boundary, root)
        fun has(save: Boolean, root: String) = if (save)
            ComposerAllocationArbiter.hasReviewedSave(access, boundary, root)
            else ComposerAllocationArbiter.hasPublication(access, boundary, root)
        fun noIo() { assertEquals(0, store.calls); assertEquals(0, transportCalls) }
        fun close() = boundary.clear()
    }
    private companion object {
        const val ROOT = "aaaaaaaa-1111-4111-8111-111111111111"
        const val OTHER_ROOT = "bbbbbbbb-2222-4222-8222-222222222222"
        const val THIRD_ROOT = "dddddddd-4444-4444-8444-444444444444"
        const val ORIGIN = "cccccccc-3333-4333-8333-333333333333"
        fun fixture(action: (Fixture) -> Unit) {
            val fixture = Fixture()
            try { action(fixture) } finally { fixture.close() }
        }
        fun conflict(action: () -> Unit) = assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> { action() }.reason)
        fun stale(action: () -> Unit) = assertEquals(FailureReason.STALE_SESSION, assertFailsWith<MealFailure> { action() }.reason)
    }
}
