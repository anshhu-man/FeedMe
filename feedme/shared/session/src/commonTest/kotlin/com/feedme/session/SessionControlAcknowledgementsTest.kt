package com.feedme.session

import com.feedme.core.ports.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SessionControlAcknowledgementsTest {
    @Test fun successfulSamePayloadRequiresChangedRevisionAndExactReadback() = runTest {
        val f = Store()
        val before = f.record
        val acknowledged = value(f.acknowledge(before, before.payload))
        assertEquals(2, acknowledged.revision)
        assertEquals(1, f.writes); assertEquals(1, f.reads)
        assertContentEquals(before.payload.copyForCodec(), acknowledged.payload.copyForCodec())
    }

    @Test fun everyTypedFailureStopsWithoutReadbackEvenIfExactPayloadIsVisible() = runTest {
        for (reason in FailureReason.entries) {
            val f = Store(); val before = f.record
            f.afterWrite = { PortResult.Failure(reason) }
            failure(reason, f.acknowledge(before, PAYLOAD))
            assertEquals(2, f.record.revision)
            assertEquals(0, f.reads)
        }
    }

    @Test fun retryWritesExactObservedPayloadAgainInsteadOfAcceptingUnknownReadback() = runTest {
        val f = Store()
        f.afterWrite = { PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
        failure(FailureReason.OUTCOME_UNKNOWN, f.acknowledge(f.record, PAYLOAD))
        val observed = value(f.read())!!
        f.afterWrite = { PortResult.Value(it) }
        val ack = value(f.acknowledge(observed, observed.payload))
        assertEquals(3, ack.revision); assertEquals(2, f.writes)
    }

    @Test fun repeatedUnknownRetriesNeverGainAuthority() = runTest {
        val f = Store(); f.afterWrite = { PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
        repeat(3) { failure(FailureReason.OUTCOME_UNKNOWN, f.acknowledge(f.record, PAYLOAD)) }
        assertEquals(4, f.record.revision); assertEquals(0, f.reads)
    }

    @Test fun uncommittedFailurePreservesExpectedRecordWithoutReadback() = runTest {
        val f = Store(); f.beforeWrite = { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
        failure(FailureReason.STORAGE_FAILURE, f.acknowledge(f.record, PAYLOAD))
        assertEquals(1, f.record.revision); assertEquals(0, f.reads)
    }

    @Test fun sameOrSkippedRevisionAndWrongPayloadReceiptsCannotAuthorizeReadback() = runTest {
        for (bad in listOf(SessionControlRecord(1, PAYLOAD), SessionControlRecord(3, PAYLOAD),
                SessionControlRecord(2, PrivateBytes("other".encodeToByteArray())))) {
            val f = Store(); f.afterWrite = { PortResult.Value(bad) }
            failure(FailureReason.STORAGE_FAILURE, f.acknowledge(f.record, PAYLOAD))
            assertEquals(0, f.reads)
        }
    }

    @Test fun matchingPayloadAtNewerReadbackRevisionIsConflictNotTheSameAcknowledgement() = runTest {
        val f = Store(); f.onRead = { PortResult.Value(SessionControlRecord(3, PAYLOAD)) }
        failure(FailureReason.CONFLICT, f.acknowledge(f.record, PAYLOAD))
    }

    @Test fun oldOrMismatchedReadbackCannotConfirmEvenSuccessfulReceipt() = runTest {
        for (bad in listOf(SessionControlRecord(1, PAYLOAD), SessionControlRecord(2, PrivateBytes(byteArrayOf(1))))) {
            val f = Store(); f.onRead = { PortResult.Value(bad) }
            failure(FailureReason.OUTCOME_UNKNOWN, f.acknowledge(f.record, PAYLOAD))
        }
    }

    @Test fun missingOrFailedReadbackRetainsFailure() = runTest {
        val f = Store(); f.onRead = { PortResult.Value(null) }
        failure(FailureReason.STORAGE_FAILURE, f.acknowledge(f.record, PAYLOAD))
        f.onRead = { PortResult.Failure(FailureReason.UNAVAILABLE) }
        failure(FailureReason.UNAVAILABLE, f.acknowledge(f.record, PAYLOAD))
    }

    @Test fun exhaustedRevisionCannotWriteOrWrap() = runTest {
        val f = Store(); f.record = SessionControlRecord(Long.MAX_VALUE, PAYLOAD)
        failure(FailureReason.STORAGE_FAILURE, f.acknowledge(f.record, PAYLOAD))
        assertEquals(0, f.writes); assertEquals(0, f.reads)
    }

    @Test fun thrownWriteOrReadExceptionsAreRedactedAndNeverPromoted() = runTest {
        val f = Store(); f.beforeWrite = { error("private-ledger-path") }
        val first = f.acknowledge(f.record, PAYLOAD)
        failure(FailureReason.STORAGE_FAILURE, first)
        assertFalse(first.toString().contains("private-ledger-path"))
        f.beforeWrite = { null }; f.onRead = { error("private-read-path") }
        failure(FailureReason.STORAGE_FAILURE, f.acknowledge(f.record, PAYLOAD))
    }

    @Test fun cancellationReturnedFromSuccessfulWriteStopsBeforeReadback() = runTest {
        val f = Store()
        f.afterWrite = { currentCoroutineContext().cancel(); PortResult.Value(it) }
        val job = async { f.acknowledge(f.record, PAYLOAD) }
        assertFailsWith<CancellationException> { job.await() }
        assertEquals(1, f.writes); assertEquals(0, f.reads)
    }

    @Test fun cancellationReturnedFromReadbackCannotReturnAuthority() = runTest {
        val f = Store()
        f.onRead = { currentCoroutineContext().cancel(); PortResult.Value(f.record) }
        val job = async { f.acknowledge(f.record, PAYLOAD) }
        assertFailsWith<CancellationException> { job.await() }
        assertEquals(1, f.reads)
    }

    @Test fun changedCallerFenceIsCheckedAfterWriteAndReadback() = runTest {
        for (atWrite in listOf(false, true)) {
            val f = Store(); var current = true
            if (atWrite) f.afterWrite = { current = false; PortResult.Value(it) }
            else f.onRead = { current = false; PortResult.Value(f.record) }
            assertFailsWith<IllegalStateException> {
                f.acknowledge(f.record, PAYLOAD) { check(current) { "Changed owner" } }
            }
            assertEquals(if (atWrite) 0 else 1, f.reads)
        }
    }

    private class Store : SessionControlStore {
        var record = SessionControlRecord(1, PAYLOAD)
        var reads = 0; var writes = 0
        var beforeWrite: suspend () -> PortResult<SessionControlRecord>? = { null }
        var afterWrite: suspend (SessionControlRecord) -> PortResult<SessionControlRecord> = { PortResult.Value(it) }
        var onRead: suspend () -> PortResult<SessionControlRecord?> = { PortResult.Value(record) }
        override suspend fun read(): PortResult<SessionControlRecord?> { reads++; return onRead() }
        override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> {
            writes++; beforeWrite()?.let { return it }
            if (expectedRevision != record.revision) return PortResult.Failure(FailureReason.CONFLICT)
            record = SessionControlRecord(record.revision + 1, payload)
            return afterWrite(record)
        }
    }

    companion object {
        private val PAYLOAD = PrivateBytes("exact-plan-not-a-token".encodeToByteArray())
        private fun <T> value(result: PortResult<T>) = assertIs<PortResult.Value<T>>(result).value
        private fun failure(reason: FailureReason, result: PortResult<*>) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
    }
}
