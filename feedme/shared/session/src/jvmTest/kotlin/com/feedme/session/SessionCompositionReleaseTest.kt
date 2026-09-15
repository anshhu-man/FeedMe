@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.feedme.session

import com.feedme.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Test-only reflection gates the real private registry mutex; no production lock hook. */
class SessionCompositionReleaseTest {
    @Test fun failedOpeningCleanupWaitsForContentionAndReleasesItsExactRegistration() = runTest {
        val boundary = SessionBoundary(); val registration = ok(SessionCompositions.admitRuntime(boundary, Any(), null))
        val lock = registryMutex(); lock.lock()
        val cleanup = async { SessionCompositions.releaseRuntimeAfterClose(registration) }
        try { runCurrent(); assertFalse(cleanup.isCompleted) } finally { lock.unlock() }
        ok(cleanup.await())
        val root = ok(SessionApplicationComposition.create(boundary, StandardTestDispatcher(testScheduler), "a".repeat(64)))
        ok(root.close())
    }

    @Test fun cancelledCleanupStillReleasesButAnOldRegistrationCannotReleaseAReplacement() = runTest {
        val boundary = SessionBoundary(); val old = ok(SessionCompositions.admitRuntime(boundary, Any(), null))
        val lock = registryMutex(); lock.lock()
        val cleanup = async { SessionCompositions.releaseRuntimeAfterClose(old) }
        try { runCurrent(); cleanup.cancel(); runCurrent(); assertFalse(cleanup.isCompleted) } finally { lock.unlock() }
        runCurrent(); assertFailsWith<CancellationException> { cleanup.await() }
        val replacement = ok(SessionCompositions.admitRuntime(boundary, Any(), null))
        assertEquals(FailureReason.STALE_SESSION, assertIs<PortResult.Failure>(SessionCompositions.releaseRuntimeAfterClose(old)).reason)
        ok(SessionCompositions.checkRuntime(replacement)); ok(SessionCompositions.releaseRuntimeAfterClose(replacement))
    }
    private fun registryMutex(): Mutex = SessionCompositions::class.java.getDeclaredField("mutex").let {
        it.isAccessible = true; it.get(SessionCompositions) as Mutex
    }
    private fun <T> ok(result: PortResult<T>) = assertIs<PortResult.Value<T>>(result).value
}
