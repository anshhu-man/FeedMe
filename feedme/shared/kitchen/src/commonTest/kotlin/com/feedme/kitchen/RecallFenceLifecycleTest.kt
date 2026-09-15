package com.feedme.kitchen

import com.feedme.core.ports.*
import com.feedme.kitchen.KitchenReliabilityFixture.Companion.ID
import com.feedme.kitchen.KitchenReliabilityFixture.Companion.bytes
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RecallFenceLifecycleTest {
    @Test fun failedPersistenceBlocksAcrossRepositoryReplacementForExactLiveLease() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler))
        try {
            rememberWithoutWrite(f)
            val replacement = f.context()
            assertTrue(value(replacement.guarded(f.lease) { replacement.isRecalled(f.lease, "version", ID) }))
            assertTrue(f.store.records.isEmpty())
        } finally { f.boundary.clear() }
    }

    @Test fun sameOwnerNewIncarnationCannotInheritOldInMemoryRecallIdentities() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler))
        try {
            rememberWithoutWrite(f)
            val newer = f.boundary.activate(f.scope)
            assertFalse(value(f.context.guarded(newer) { f.context.isRecalled(newer, "version", ID) }))
            assertEquals(FailureReason.STALE_SESSION, assertIs<PortResult.Failure>(f.context.guarded(f.lease) {
                f.context.markRecall(f.lease, "version", ID)
            }).reason)
            assertFalse(value(f.context.guarded(newer) { f.context.isRecalled(newer, "version", ID) }))
        } finally { f.boundary.clear() }
    }

    @Test fun invalidationOfOneBoundaryCannotDropAnotherLiveBoundaryFence() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = KitchenReliabilityFixture(dispatcher); val second = KitchenReliabilityFixture(dispatcher)
        try {
            rememberWithoutWrite(first); rememberWithoutWrite(second)
            first.boundary.clear()
            assertTrue(value(second.context.guarded(second.lease) { second.context.isRecalled(second.lease, "version", ID) }))
            assertSame(second.lease, second.boundary.current())
        } finally { first.boundary.clear(); second.boundary.clear() }
    }

    @Test fun persistedOneWayMarkerStillBlocksAfterLeaseInvalidationAndRehydration() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler))
        try {
            value(f.context.guarded(f.lease) { f.context.markRecall(f.lease, "version", ID) })
            f.boundary.clear(); val next = f.boundary.activate(f.scope)
            val writes = f.store.commits.size
            assertTrue(value(f.context().guarded(next) { f.context.isRecalled(next, "version", ID) }))
            assertEquals(writes, f.store.commits.size)
            assertEquals(listOf(KEY), f.store.records.keys.toList())
        } finally { f.boundary.clear() }
    }

    @Test fun cancelledMarkerWriteNeverAcknowledgesButRetainsExistingLeaseBlockingFence() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); f.store.write = false
        f.store.afterCommit = { currentCoroutineContext().cancel() }
        try {
            val pending = async { f.context.guarded(f.lease) { f.context.markRecall(f.lease, "version", ID) } }
            pending.join(); assertTrue(pending.isCancelled); assertTrue(f.store.records.isEmpty())
            assertTrue(value(f.context.guarded(f.lease) { f.context.isRecalled(f.lease, "version", ID) }))
            val next = f.boundary.activate(f.scope)
            assertFalse(value(f.context.guarded(next) { f.context.isRecalled(next, "version", ID) }))
        } finally { f.boundary.clear() }
    }

    @Test fun cancelledPersistedReadCannotInstallLateProcessRecallFence() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler))
        f.store.seed(KEY, payload = PrivateBytes(byteArrayOf(1)))
        f.store.afterRead = { currentCoroutineContext().cancel() }
        try {
            val pending = async { f.context.guarded(f.lease) { f.context.isRecalled(f.lease, "version", ID) } }
            pending.join(); assertTrue(pending.isCancelled)
            f.store.afterRead = {}; f.store.records.clear()
            assertFalse(value(f.context.guarded(f.lease) { f.context.isRecalled(f.lease, "version", ID) }))
        } finally { f.boundary.clear() }
    }

    private suspend fun rememberWithoutWrite(f: KitchenReliabilityFixture) {
        f.store.write = false; f.store.receipt = { PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
        assertEquals(FailureReason.OUTCOME_UNKNOWN, assertIs<PortResult.Failure>(f.context.guarded(f.lease) {
            f.context.markRecall(f.lease, "version", ID)
        }).reason)
    }
    private fun <T> value(result: PortResult<T>) = assertIs<PortResult.Value<T>>(result).value
    private companion object { val KEY = RecordKey("feedme.kitchen.recall", "version:$ID") }
}
