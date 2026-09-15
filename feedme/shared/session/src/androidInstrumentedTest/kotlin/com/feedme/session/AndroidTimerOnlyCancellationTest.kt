package com.feedme.session

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run separately: proves real timer cancellation without initializing the worker provider.
 * Uses only the isolated library test UID and exact tickets created by each method.
 */
@RunWith(AndroidJUnit4::class)
class AndroidTimerOnlyCancellationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val receiver get() = ComponentName(context, NativeCancellationTestReceiver::class.java)
    private val alarms get() = context.getSystemService(AlarmManager::class.java)
    private fun adapter() = value(AndroidNativeWorkCancellation.createTimerOnly(context, receiver))

    @Test fun timerOnlyConstructionDoesNotInitializeWorkManagerOrCreatePrivateFiles() {
        assertEquals("com.feedme.session.test", context.packageName)
        noWorkerProvider()
        val databases = context.databaseList().toSet()
        val files = context.noBackupFilesDir.list()?.toSet().orEmpty()
        adapter()
        noWorkerProvider()
        assertEquals(databases, context.databaseList().toSet())
        assertEquals(files, context.noBackupFilesDir.list()?.toSet().orEmpty())
    }

    @Test fun exactTimerCancellationRemovesOnlyItsAlarmPendingIntentAndRepeatsSafely() = runBlocking {
        val adapter = adapter(); val first = ticket(NativeWorkKind.TIMER); val sibling = ticket(NativeWorkKind.TIMER)
        val a = value(adapter.timerIdentity(first)); val b = value(adapter.timerIdentity(sibling))
        val firstPending = install(a); val siblingPending = install(b)
        try {
            value(adapter.cancel(first)); assertNull(find(a)); assertNotNull(find(b))
            try { firstPending.send(); fail("Exact cancelled token must not remain sendable") }
            catch (_: PendingIntent.CanceledException) { }
            value(adapter.cancel(first)); assertNotNull(find(b)); noWorkerProvider()
        } finally {
            // Explicit test-created targets only. No cancellation sweep or app-data deletion.
            alarms.cancel(firstPending); firstPending.cancel()
            alarms.cancel(siblingPending); siblingPending.cancel()
        }
    }

    @Test fun cancellingAbsentTimerDoesNotCreateItsPendingIntent() = runBlocking {
        val adapter = adapter(); val ticket = ticket(NativeWorkKind.TIMER)
        val identity = value(adapter.timerIdentity(ticket))
        assertNull(find(identity)); value(adapter.cancel(ticket)); value(adapter.cancel(ticket))
        assertNull(find(identity)); noWorkerProvider()
    }

    @Test fun workerCancellationFailsNotConfiguredWithoutProviderAccessOrDatabaseCreation() = runBlocking {
        noWorkerProvider(); val before = context.databaseList().toSet()
        val adapter = adapter(); val worker = ticket(NativeWorkKind.WORKER)
        repeat(2) { assertEquals(PortResult.Failure(FailureReason.NOT_CONFIGURED), adapter.cancel(worker)) }
        assertEquals(PortResult.Failure(FailureReason.INVALID_DATA), adapter.timerIdentity(worker))
        noWorkerProvider(); assertEquals(before, context.databaseList().toSet())
    }

    @Test fun foreignExportedAndMissingReceiversCannotConfigureTimerOnlyCancellation() {
        listOf(ComponentName("foreign.application", receiver.className),
            ComponentName(context, ExportedNativeCancellationTestReceiver::class.java),
            ComponentName(context.packageName, "com.feedme.session.MissingReceiver")).forEach {
            assertEquals(PortResult.Failure(FailureReason.NOT_CONFIGURED), AndroidNativeWorkCancellation.createTimerOnly(context, it))
        }
        noWorkerProvider()
    }

    @Test fun cancelledCallerDoesNotAcknowledgeOrRemoveItsScheduledToken() = runBlocking {
        val adapter = adapter(); val ticket = ticket(NativeWorkKind.TIMER)
        val identity = value(adapter.timerIdentity(ticket)); val pending = install(identity)
        try {
            var returned = false
            val job = launch { currentCoroutineContext().cancel(); adapter.cancel(ticket); returned = true }
            job.join(); assertTrue(job.isCancelled); assertFalse(returned); assertNotNull(find(identity))
        } finally { alarms.cancel(pending); pending.cancel() }
    }

    private fun noWorkerProvider() {
        try { WorkManager.getInstance(context); fail("This isolated invocation must not initialize WorkManager") }
        catch (_: IllegalStateException) { }
    }
    private fun ticket(kind: NativeWorkKind) = NativeWorkTicket(UUID.randomUUID().toString(), kind)
    private fun install(identity: AndroidNativeTimerIdentity): PendingIntent {
        val pending = checkNotNull(PendingIntent.getBroadcast(context, identity.requestCode, identity.intent(), identity.pendingIntentFlags))
        alarms.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 60_000, pending)
        return pending
    }
    private fun find(identity: AndroidNativeTimerIdentity): PendingIntent? = PendingIntent.getBroadcast(context,
        identity.requestCode, identity.intent(), identity.pendingIntentFlags or PendingIntent.FLAG_NO_CREATE)
    private fun <T> value(result: PortResult<T>): T = when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> error("Native timer-only operation failed: ${result.reason}")
    }
}
