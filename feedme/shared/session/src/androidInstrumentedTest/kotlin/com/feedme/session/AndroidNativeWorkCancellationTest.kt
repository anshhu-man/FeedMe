package com.feedme.session

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.await
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android services in the library's isolated test package; no FeedMe application state. */
@RunWith(AndroidJUnit4::class)
class AndroidNativeWorkCancellationTest {
    private val timers = mutableListOf<NativeWorkTicket>()
    private val workers = mutableListOf<NativeWorkTicket>()
    private val channels = mutableListOf<String>()
    private lateinit var adapter: AndroidNativeWorkCancellation

    @Before fun setUp() {
        adapter = AndroidNativeWorkCancellation.create(context, receiver, workManager).nativeValue()
    }

    @After fun tearDown() = runBlocking {
        // Every resource was created by this test. Never cancelAll, prune or reset WorkManager.
        for (ticket in timers) adapter.cancel(ticket).nativeValue()
        for (ticket in workers) adapter.cancel(ticket).nativeValue()
        for (channel in channels) notifications.deleteNotificationChannel(channel)
    }

    @Test fun workManagerInitializerIsDisabledAndTestCompositionInitializesExplicitly() {
        assertTrue("Library must not initialize WorkManager before the trusted startup gate", initializationWasExplicit)
        assertTrue(workManager === WorkManager.getInstance(context))
        val provider = context.packageManager.getProviderInfo(
            ComponentName(context, "androidx.startup.InitializationProvider"), 0,
        )
        assertFalse("Startup provider must be private to this application", provider.exported)
    }

    @Test fun timerIdentityIsOpaqueExplicitImmutableAndDefensivelyRecreated() {
        val ticket = timer()
        val identity = adapter.timerIdentity(ticket).nativeValue()
        val first = identity.intent()
        assertEquals(receiver, first.component)
        assertEquals(context.packageName, first.`package`)
        assertEquals("com.feedme.session.action.TIMER", first.action)
        assertEquals("feedme-native", first.data!!.scheme)
        assertEquals("timer", first.data!!.authority)
        assertEquals(listOf(ticket.id), first.data!!.pathSegments)
        assertNull(first.data!!.query)
        assertNull(first.data!!.fragment)
        assertNull(first.extras)
        assertNull(first.clipData)
        assertNull(first.selector)
        assertEquals(PendingIntent.FLAG_IMMUTABLE, identity.pendingIntentFlags)
        assertEquals(0, identity.requestCode)
        assertEquals("com.feedme.session.timer.${ticket.id}", identity.notificationTag)
        assertEquals(1, identity.notificationId)
        first.action = "modified-test-intent"
        first.putExtra("injected", "test-value")
        val fresh = identity.intent()
        assertEquals("com.feedme.session.action.TIMER", fresh.action)
        assertNull(fresh.extras)
        assertFalse(identity.toString().contains(ticket.id))
        assertFalse(ticket.toString().contains(ticket.id))
        assertFalse(adapter.toString().contains(context.packageName))
    }

    @Test fun timerCancellationInvalidatesOnlyExactPendingIntentAndIsIdempotent() = runBlocking {
        val first = timer()
        val sibling = timer()
        val firstIdentity = adapter.timerIdentity(first).nativeValue()
        val siblingIdentity = adapter.timerIdentity(sibling).nativeValue()
        assertFalse(firstIdentity.intent().filterEquals(siblingIdentity.intent()))
        val firstPending = schedule(firstIdentity)
        val siblingPending = schedule(siblingIdentity)
        assertNotEquals(firstPending, siblingPending)
        assertNotNull(findPending(firstIdentity))
        assertNotNull(findPending(siblingIdentity))

        adapter.cancel(first).nativeValue()
        assertNull(findPending(firstIdentity))
        assertNotNull(findPending(siblingIdentity))
        try {
            firstPending.send()
            fail("Canceled PendingIntent must not remain sendable")
        } catch (_: PendingIntent.CanceledException) { /* Expected native token invalidation. */ }
        adapter.cancel(first).nativeValue()
        assertNotNull(findPending(siblingIdentity))
    }

    @Test fun cancellationOfNeverInstalledTimerDoesNotCreatePendingIntent() = runBlocking {
        val ticket = timer()
        val identity = adapter.timerIdentity(ticket).nativeValue()
        assertNull(findPending(identity))
        adapter.cancel(ticket).nativeValue()
        adapter.cancel(ticket).nativeValue()
        assertNull(findPending(identity))
    }

    @Test fun realDelayedWorkCancellationWaitsForCancelledStateAndRetainsSibling() = runBlocking {
        val first = delayedWork()
        val sibling = delayedWork()
        workManager.enqueue(listOf(first, sibling)).await()
        assertEquals(WorkInfo.State.ENQUEUED, state(first.id))
        assertEquals(WorkInfo.State.ENQUEUED, state(sibling.id))
        val ticket = NativeWorkTicket(first.id.toString(), NativeWorkKind.WORKER)
        adapter.cancel(ticket).nativeValue()
        assertEquals(WorkInfo.State.CANCELLED, state(first.id))
        assertEquals(WorkInfo.State.ENQUEUED, state(sibling.id))
        adapter.cancel(ticket).nativeValue()
        assertEquals(WorkInfo.State.CANCELLED, state(first.id))
        assertEquals(WorkInfo.State.ENQUEUED, state(sibling.id))
        assertEquals(0, NativeCancellationTestWorker.executions.get())
    }

    @Test fun nonexistentWorkCancellationIsIdempotentAndDoesNotCreateWork() = runBlocking {
        val id = UUID.randomUUID()
        val ticket = NativeWorkTicket(id.toString(), NativeWorkKind.WORKER).also { workers += it }
        assertNull(state(id))
        adapter.cancel(ticket).nativeValue()
        adapter.cancel(ticket).nativeValue()
        assertNull(state(id))
    }

    @Test fun callerCancellationDoesNotBecomeSuccessfulNativeCancellation() = runBlocking {
        val work = delayedWork()
        workManager.enqueue(work).await()
        var returned = false
        val attempt = launch {
            currentCoroutineContext().cancel()
            adapter.cancel(NativeWorkTicket(work.id.toString(), NativeWorkKind.WORKER))
            returned = true
        }
        attempt.join()
        assertTrue(attempt.isCancelled)
        assertFalse(returned)
        assertEquals(WorkInfo.State.ENQUEUED, state(work.id))
    }

    @Test fun malformedTicketsAndNonTimerIdentityAreRejectedWithoutNativeInstallation() {
        val malformed = listOf("", "../timer", "not-a-uuid", "A0000000-0000-0000-0000-000000000000",
            "00000000-0000-0000-0000-000000000000-extra")
        for (id in malformed) {
            try {
                NativeWorkTicket(id, NativeWorkKind.TIMER)
                fail("Malformed native ticket must be rejected")
            } catch (_: IllegalArgumentException) { /* Immutable common model boundary. */ }
        }
        val worker = NativeWorkTicket(UUID.randomUUID().toString(), NativeWorkKind.WORKER)
        assertEquals(PortResult.Failure(FailureReason.INVALID_DATA), adapter.timerIdentity(worker))
    }

    @Test fun foreignExportedAndUnresolvableReceiversCannotConfigureAdapter() {
        val invalid = listOf(
            ComponentName("not.the.test.application", NativeCancellationTestReceiver::class.java.name),
            ComponentName(context, ExportedNativeCancellationTestReceiver::class.java),
            ComponentName(context.packageName, "com.feedme.session.NoSuchReceiver"),
        )
        invalid.forEach { component ->
            assertEquals(PortResult.Failure(FailureReason.NOT_CONFIGURED),
                AndroidNativeWorkCancellation.create(context, component, workManager))
        }
    }

    @Test fun notificationCancellationIsExactWhenPermissionAvailableOtherwiseReportsGate() = runBlocking {
        val first = timer()
        val sibling = timer()
        val firstIdentity = adapter.timerIdentity(first).nativeValue()
        val siblingIdentity = adapter.timerIdentity(sibling).nativeValue()
        if (!notifications.areNotificationsEnabled() ||
            (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) {
            report("NATIVE_WORK_NOTIFICATION_PERMISSION_UNAVAILABLE")
            // Still exercise safe cancellation of an absent notification; do not claim UI proof.
            adapter.cancel(first).nativeValue()
            assertNull(findPending(firstIdentity))
            return@runBlocking
        }
        val channelId = "feedme-native-cancellation-test-${UUID.randomUUID()}".also { channels += it }
        notifications.createNotificationChannel(NotificationChannel(channelId, "Native cancellation test", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("FeedMe test timer")
            .setContentText("Native cancellation check")
            .build()
        notifications.notify(firstIdentity.notificationTag, firstIdentity.notificationId, notification)
        notifications.notify(siblingIdentity.notificationTag, siblingIdentity.notificationId, notification)
        awaitNotifications { active -> active.contains(firstIdentity.notificationTag) && active.contains(siblingIdentity.notificationTag) }
        // No alarm PI exists: cancellation must still remove an already-delivered notification.
        assertNull(findPending(firstIdentity))
        adapter.cancel(first).nativeValue()
        awaitNotifications { active -> !active.contains(firstIdentity.notificationTag) && active.contains(siblingIdentity.notificationTag) }
        report("NATIVE_WORK_NOTIFICATION_EXACT_CANCELLATION_VERIFIED")
    }

    private fun timer() = NativeWorkTicket(UUID.randomUUID().toString(), NativeWorkKind.TIMER).also { timers += it }

    private fun delayedWork(): OneTimeWorkRequest = OneTimeWorkRequest.Builder(NativeCancellationTestWorker::class.java)
        .setInitialDelay(1, TimeUnit.DAYS)
        .build().also { workers += NativeWorkTicket(it.id.toString(), NativeWorkKind.WORKER) }

    private fun schedule(identity: AndroidNativeTimerIdentity): PendingIntent {
        val pending = checkNotNull(PendingIntent.getBroadcast(context, identity.requestCode, identity.intent(), identity.pendingIntentFlags))
        alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + TimeUnit.DAYS.toMillis(1), pending)
        return pending
    }

    private fun findPending(identity: AndroidNativeTimerIdentity): PendingIntent? = PendingIntent.getBroadcast(
        context, identity.requestCode, identity.intent(), identity.pendingIntentFlags or PendingIntent.FLAG_NO_CREATE,
    )

    private suspend fun state(id: UUID): WorkInfo.State? = withContext(Dispatchers.IO) {
        workManager.getWorkInfoById(id).get(15, TimeUnit.SECONDS)?.state
    }

    private suspend fun awaitNotifications(predicate: (Set<String?>) -> Boolean) = withTimeout(5_000) {
        while (!predicate(notifications.activeNotifications.map { it.tag }.toSet())) delay(25)
    }

    companion object {
        private lateinit var context: Context
        private lateinit var workManager: WorkManager
        private lateinit var receiver: ComponentName
        private lateinit var alarms: AlarmManager
        private lateinit var notifications: NotificationManager
        private var initializationWasExplicit = false

        @JvmStatic @BeforeClass fun initializeIsolatedNativeServices() {
            context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            assertEquals("Tests must not configure the real FeedMe application", "com.feedme.session.test", context.packageName)
            try {
                WorkManager.getInstance(context)
                fail("WorkManager auto-initializer must be disabled")
            } catch (_: IllegalStateException) { initializationWasExplicit = true }
            WorkManager.initialize(context, Configuration.Builder().setMinimumLoggingLevel(Log.ERROR).build())
            workManager = WorkManager.getInstance(context)
            receiver = ComponentName(context, NativeCancellationTestReceiver::class.java)
            alarms = checkNotNull(context.getSystemService(AlarmManager::class.java))
            notifications = checkNotNull(context.getSystemService(NotificationManager::class.java))
        }

        private fun report(message: String) = InstrumentationRegistry.getInstrumentation().addResults(
            Bundle().apply { putString("native_work_notification", message) })
    }
}

/** Test-only explicit receivers; neither performs a private effect or follows Intent extras. */
class NativeCancellationTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}

class ExportedNativeCancellationTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}

/** All requests are delayed for one day and exactly canceled by their creating test. */
class NativeCancellationTestWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        executions.incrementAndGet()
        return Result.failure()
    }
    companion object { val executions = AtomicInteger(0) }
}

private fun <T> PortResult<T>.nativeValue(): T = when (this) {
    is PortResult.Value -> value
    is PortResult.Failure -> throw AssertionError("Expected native value, got $reason")
}
