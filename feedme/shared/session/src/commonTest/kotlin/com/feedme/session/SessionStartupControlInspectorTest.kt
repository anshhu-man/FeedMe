package com.feedme.session

import com.feedme.core.ports.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SessionStartupControlInspectorTest {
    @Test fun idleAndCompleteAreOnlyAdvisoryTerminalStates() = runTest {
        for (state in listOf(RetirementState.Idle, RetirementState.Complete("00000000-0000-4000-8000-000000000001"))) {
            val control = Control(SessionControlRecord(1, RetirementCodec.encode(state)))
            assertEquals(SessionStartupControlKind.TERMINAL, assertIs<PortResult.Value<SessionStartupControlKind>>(SessionStartupControlInspector.inspect(control)).value)
            assertEquals(2, control.reads); assertEquals(0, control.writes)
        }
    }
    @Test fun missingControlNeverBecomesFreshInstallPermission() = runTest {
        assertEquals(PortResult.Failure(FailureReason.NOT_FOUND), SessionStartupControlInspector.inspect(Control(null)))
    }
    @Test fun unknownStateAndMalformedControlFailWithoutWrites() = runTest {
        for (raw in listOf("{}", "{\"version\":1,\"state\":\"future\"}", "{\"version\":1,\"state\":\"idle\",\"extra\":true}")) {
            val control = Control(SessionControlRecord(1, PrivateBytes(raw.encodeToByteArray())))
            assertIs<PortResult.Failure>(SessionStartupControlInspector.inspect(control)); assertEquals(0, control.writes)
        }
    }
    @Test fun changedRevisionOrPayloadDuringInspectionRejectsStaleRoute() = runTest {
        val initial = SessionControlRecord(1, RetirementCodec.encode(RetirementState.Idle))
        for (changed in listOf(SessionControlRecord(2, initial.payload),
            SessionControlRecord(1, RetirementCodec.encode(RetirementState.Complete("00000000-0000-4000-8000-000000000001"))))) {
            val control = Control(initial).apply { second = changed }
            assertEquals(PortResult.Failure(FailureReason.CONFLICT), SessionStartupControlInspector.inspect(control))
        }
    }
    @Test fun readFailureRemainsFailureAndDoesNotInitialize() = runTest {
        val control = Control(null).apply { failure = FailureReason.STORAGE_FAILURE }
        assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE), SessionStartupControlInspector.inspect(control))
        assertEquals(0, control.writes)
    }
    @Test fun cancellationIsNotConvertedToAnOrdinaryStartupResult() = runTest {
        assertFailsWith<CancellationException> { SessionStartupControlInspector.inspect(Control(null).apply { cancelled = true }) }
    }
    private class Control(var first: SessionControlRecord?) : SessionControlStore {
        var second: SessionControlRecord? = null; var reads = 0; var writes = 0
        var failure: FailureReason? = null; var cancelled = false
        override suspend fun read(): PortResult<SessionControlRecord?> {
            reads++; if (cancelled) throw CancellationException("test")
            failure?.let { return PortResult.Failure(it) }
            return PortResult.Value(if (reads > 1) second ?: first else first)
        }
        override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> {
            writes++; error("Read-only inspector attempted mutation")
        }
    }
}
