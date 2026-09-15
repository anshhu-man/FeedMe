package com.feedme.kitchen

import com.feedme.core.ports.*
import com.feedme.kitchen.KitchenReliabilityFixture.Companion.ID
import com.feedme.kitchen.KitchenReliabilityFixture.Companion.KEY
import com.feedme.kitchen.KitchenReliabilityFixture.Companion.OTHER
import com.feedme.kitchen.KitchenReliabilityFixture.Companion.bytes
import com.feedme.kitchen.KitchenReliabilityFixture.Companion.cookReply
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class KitchenAcknowledgementTest {
    @Test fun exactChangedPutAndDeleteReceiptRequiresExactReadback() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler))
        f.store.seed(KEY, 7); f.store.seed(OTHER, 4)
        assertEquals(mapOf(KEY to 8L, OTHER to null), value(f.context.guarded(f.lease) {
            f.context.commit(f.lease, listOf(kitchenPut(KEY, 7, bytes("new")), StoreMutation.Delete(OTHER, 4)))
        }))
        assertEquals(listOf(KEY, OTHER), f.store.reads)
        assertEquals("new", f.store.records.getValue(KEY).payload.copyForCodec().decodeToString())
        assertNull(f.store.records[OTHER])
    }

    @Test fun absentCreatePreservesNativeTombstoneRevisionInsteadOfInventingRevisionOne() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler))
        f.store.seed(KEY, 9)
        value(f.context.guarded(f.lease) { f.context.commit(f.lease, listOf(StoreMutation.Delete(KEY, 9))) })
        assertEquals(11L, value(f.context.guarded(f.lease) {
            f.context.commit(f.lease, listOf(kitchenPut(KEY, null, bytes("new"))))
        })[KEY])
    }

    @Test fun missingExtraOrNullPutReceiptNeverBecomesSuccess() = runTest {
        for (receipt in listOf(emptyMap(), mapOf(KEY to null), mapOf(KEY to 1L, OTHER to 1L))) {
            val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler))
            f.store.receipt = { PortResult.Value(receipt) }
            failed(FailureReason.STORAGE_FAILURE, f.context.guarded(f.lease) {
                f.context.commit(f.lease, listOf(kitchenPut(KEY, null, bytes("new"))))
            })
            assertTrue(f.store.reads.isEmpty())
        }
    }

    @Test fun unchangedSkippedNegativeOrZeroPutRevisionsAreRejected() = runTest {
        for (revision in listOf(-1L, 0, 7, 9, Long.MAX_VALUE)) {
            val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); f.store.seed(KEY, 7)
            f.store.receipt = { PortResult.Value(mapOf(KEY to revision)) }
            failed(FailureReason.STORAGE_FAILURE, f.context.guarded(f.lease) {
                f.context.commit(f.lease, listOf(kitchenPut(KEY, 7, bytes("new"))))
            })
            assertTrue(f.store.reads.isEmpty())
        }
    }

    @Test fun changedPutReceiptCannotHideWrongRevisionSchemaBytesOrMissingRecord() = runTest {
        for (wrong in listOf(null, PrivateRecord(1, 1, bytes("old")), PrivateRecord(2, 2, bytes("new")),
            PrivateRecord(2, 1, bytes("new ")), PrivateRecord(3, 1, bytes("new")))) {
            val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); f.store.seed(KEY)
            f.store.observation = { _, _ -> PortResult.Value(wrong) }
            failed(FailureReason.STORAGE_FAILURE, f.context.guarded(f.lease) {
                f.context.commit(f.lease, listOf(kitchenPut(KEY, 1, bytes("new"))))
            })
            assertEquals(listOf(KEY), f.store.reads)
        }
    }

    @Test fun deletionRequiresBothNullReceiptAndObservedAbsence() = runTest {
        for (wrongReceipt in listOf(true, false)) {
            val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); f.store.seed(KEY)
            if (wrongReceipt) f.store.receipt = { PortResult.Value(mapOf(KEY to 2L)) } else f.store.write = false
            failed(FailureReason.STORAGE_FAILURE, f.context.guarded(f.lease) {
                f.context.commit(f.lease, listOf(StoreMutation.Delete(KEY, 1)))
            })
        }
    }

    @Test fun failureWithVisibleExactCommittedBytesNeverTriggersReadbackPromotion() = runTest {
        for (reason in listOf(FailureReason.OUTCOME_UNKNOWN, FailureReason.STORAGE_FAILURE, FailureReason.CONFLICT)) {
            val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler))
            f.store.receipt = { PortResult.Failure(reason, 23) }
            val result = failed(reason, f.context.guarded(f.lease) {
                f.context.commit(f.lease, listOf(kitchenPut(KEY, null, bytes("new"))))
            })
            assertEquals(23L, result.retryAfterSeconds)
            assertNotNull(f.store.records[KEY]); assertTrue(f.store.reads.isEmpty())
            assertEquals(1, f.store.commits.size)
        }
    }

    @Test fun readbackFailureRetainsExactCategoryWithoutRetryOrRollbackClaim() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler))
        f.store.observation = { _, _ -> PortResult.Failure(FailureReason.UNAVAILABLE, 17) }
        assertEquals(17L, failed(FailureReason.UNAVAILABLE, f.context.guarded(f.lease) {
            f.context.commit(f.lease, listOf(kitchenPut(KEY, null, bytes("new"))))
        }).retryAfterSeconds)
        assertNotNull(f.store.records[KEY]); assertEquals(1, f.store.commits.size)
    }

    @Test fun invalidBatchAndExhaustedRevisionFailBeforeAnyStoreEffects() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler))
        for (batch in listOf(emptyList(), listOf(kitchenPut(KEY, null, bytes("a")), kitchenPut(KEY, null, bytes("b"))),
            listOf(kitchenPut(KEY, Long.MAX_VALUE, bytes("new"))), listOf(StoreMutation.Delete(KEY, Long.MAX_VALUE))))
            failed(FailureReason.INVALID_DATA, f.context.guarded(f.lease) { f.context.commit(f.lease, batch) })
        assertTrue(f.store.commits.isEmpty()); assertTrue(f.store.reads.isEmpty())
    }

    @Test fun callerBatchMutationWhilePortSuspendsCannotChangeAcknowledgementIntent() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler))
        val batch = mutableListOf<StoreMutation>(kitchenPut(KEY, null, bytes("new")))
        f.store.beforeCommit = { batch.clear(); batch += kitchenPut(OTHER, null, bytes("foreign")) }
        assertEquals(setOf(KEY), value(f.context.guarded(f.lease) { f.context.commit(f.lease, batch) }).keys)
        assertEquals(listOf(KEY), f.store.reads); assertNull(f.store.records[OTHER])
    }

    @Test fun cancellationSwallowedByReadCannotPermitFollowingCommit() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); f.store.seed(KEY)
        f.store.afterRead = { currentCoroutineContext().cancel() }
        val pending = async { f.context.guarded(f.lease) {
            f.context.read(f.lease, KEY)
            f.context.commit(f.lease, listOf(kitchenPut(KEY, 1, bytes("late"))))
        } }
        pending.join(); assertTrue(pending.isCancelled); assertTrue(f.store.commits.isEmpty())
    }

    @Test fun cancellationAfterCommitCannotReturnReceiptOrBeginReadback() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler))
        f.store.afterCommit = { currentCoroutineContext().cancel() }
        val pending = async { f.context.guarded(f.lease) { f.context.commit(f.lease, listOf(kitchenPut(KEY, null, bytes("new")))) } }
        pending.join(); assertTrue(pending.isCancelled)
        assertNotNull(f.store.records[KEY]); assertTrue(f.store.reads.isEmpty())
    }

    @Test fun cancellationSwallowedByTransportCannotCacheItsLateReply() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler))
        f.exchange = { currentCoroutineContext().cancel(); PortResult.Value(cookReply()) }
        val pending = async { f.context.guarded(f.lease) {
            f.context.fetch(f.lease, "getCookSession", mapOf("sessionId" to ID))
            f.context.commit(f.lease, listOf(kitchenPut(KEY, null, bytes("late"))))
        } }
        pending.join(); assertTrue(pending.isCancelled); assertEquals(1, f.calls.size)
        assertTrue(f.store.commits.isEmpty())
    }

    @Test fun invalidationDuringReadbackFencesReceiptWithoutClearingSuccessor() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); var successor: SessionLease? = null
        f.store.afterRead = { successor = f.boundary.activate(f.scope) }
        failed(FailureReason.STALE_SESSION, f.context.guarded(f.lease) {
            f.context.commit(f.lease, listOf(kitchenPut(KEY, null, bytes("new"))))
        })
        assertSame(successor, f.boundary.current()); assertNotNull(f.store.records[KEY])
    }

    @Test fun invalidationInCallerReturnGapNeverPublishesTheCompletedPrivateResult() = runTest {
        val backing = StandardTestDispatcher(testScheduler)
        lateinit var f: KitchenReliabilityFixture
        val owner = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = backing.dispatch(context, Runnable {
                block.run(); f.boundary.clear()
            })
        }
        f = KitchenReliabilityFixture(owner)
        failed(FailureReason.STALE_SESSION, f.context.guarded(f.lease) { "private-result" })
        assertTrue(f.store.reads.isEmpty()); assertTrue(f.store.commits.isEmpty())
    }

    @Test fun returnedRevisionMapDoesNotAliasStoreOwnedMutableReceipt() = runTest {
        val f = KitchenReliabilityFixture(StandardTestDispatcher(testScheduler)); val raw = mutableMapOf<RecordKey, Long?>()
        f.store.receipt = { raw.putAll(it); PortResult.Value(raw) }
        val returned = value(f.context.guarded(f.lease) { f.context.commit(f.lease, listOf(kitchenPut(KEY, null, bytes("new")))) })
        raw.clear(); assertEquals(mapOf(KEY to 1L), returned)
    }

    private fun <T> value(result: PortResult<T>) = assertIs<PortResult.Value<T>>(result).value
    private fun failed(reason: FailureReason, result: PortResult<*>) = assertIs<PortResult.Failure>(result).also { assertEquals(reason, it.reason) }
}
