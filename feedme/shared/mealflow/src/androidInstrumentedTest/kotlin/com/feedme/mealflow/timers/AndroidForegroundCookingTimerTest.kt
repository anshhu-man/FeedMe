package com.feedme.mealflow.timers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkManager
import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireField
import com.feedme.core.ports.*
import com.feedme.kitchen.*
import com.feedme.session.*
import kotlinx.coroutines.*
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

/** Actual public native stores/runtime/facade, Main Handler, wall/elapsed clock and exact native
 * cancellation. Authentication/HTTP content is synthetic. No notification or background delivery
 * is claimed; no production receiver, fake facade, reflection or storage fault API is installed.
 */
@RunWith(AndroidJUnit4::class)
class AndroidForegroundCookingTimerTest {
    @Test fun typedUiDueIdentityComesFromExactAcknowledgedFacadeInsideRuntimeEffect() = fixture(bind = false) { f ->
        val delivered = mutableListOf<CookingTimerDue>()
        f.timers = value(f.adapter.bindForUi(f.native.runtime, f.access, f.kitchen, f.native.boundary, f.policy) {
            delivered += it; PortResult.Value(Unit)
        })
        val attachment = value(f.adapter.attachForeground()); value(f.adapter.setForeground(attachment, true))
        val native = f.start(TIMER, 2, 10)
        val before = value(f.timers.observe(SESSION))
        assertEquals(CookingTimerDeliveryPhase.ENQUEUED, before.timers.single().deliveryPhase)
        assertEquals("step-one", before.timers.single().stepId)
        f.await(native, AndroidForegroundTimerPhase.DELIVERED)
        val due = delivered.single(); assertEquals(SESSION, due.sessionId); assertEquals(TIMER, due.timerId); assertEquals(1L, due.generation)
        assertFalse(due.toString().contains(native.id)); assertFalse(before.toString().contains(native.id))
        assertEquals(CookingTimerDeliveryPhase.DELIVERED, value(f.timers.observe(SESSION)).timers.single().deliveryPhase)
    }

    @Test fun staleActivityAttachmentCannotStopOrDetachItsReplacementForegroundOwner() = fixture { f ->
        val old = value(f.adapter.attachForeground()); value(f.adapter.setForeground(old, true))
        val native = f.start(TIMER, 2, 10)
        val replacement = value(f.adapter.attachForeground())
        assertEquals(AndroidForegroundTimerPhase.SUSPENDED, f.phase(native))
        value(f.adapter.setForeground(replacement, true))
        assertEquals(PortResult.Failure(FailureReason.STALE_SESSION), f.adapter.setForeground(old, false))
        assertEquals(PortResult.Failure(FailureReason.STALE_SESSION), f.adapter.detachForeground(old))
        assertEquals(AndroidForegroundTimerPhase.ENQUEUED, f.phase(native))
        f.await(native, AndroidForegroundTimerPhase.DELIVERED); assertEquals(listOf(native.id), f.delivered)
        value(f.adapter.detachForeground(replacement))
        assertEquals(PortResult.Failure(FailureReason.STALE_SESSION), f.adapter.setForeground(replacement, true))
    }

    @Test fun detachedForegroundObservationNeverClaimsPauseDeliveryOrNativeCleanup() = fixture { f ->
        val attachment = value(f.adapter.attachForeground()); value(f.adapter.setForeground(attachment, true))
        val native = f.start(TIMER, 2, 10); value(f.adapter.detachForeground(attachment))
        val observed = value(f.timers.observe(SESSION)).timers.single()
        assertEquals("running", observed.status); assertTrue(observed.alertAcknowledgedInThisOwner)
        assertEquals(CookingTimerDeliveryPhase.SUSPENDED, observed.deliveryPhase)
        assertTrue(f.delivered.isEmpty()); value(f.timers.cancelAlert(SESSION, TIMER))
        assertEquals(CookingTimerDeliveryPhase.NOT_INSTALLED, value(f.timers.observe(SESSION)).timers.single().deliveryPhase)
        assertIs<PortResult.Failure>(f.adapter.inspect(native))
    }

    @Test fun bindingRequiresExactRuntimeCancellationAndCreatesOnlyOneMatchedFacade() = fixture(bind = false) { f ->
        assertTrue(f.native.runtime.usesCancellationPort(f.adapter))
        val other = value(AndroidForegroundCookingTimerAdapter.create(cancellation, f.clock))
        try {
            assertIs<PortResult.Failure>(other.bind(f.native.runtime, f.access, f.kitchen, f.native.boundary, f.policy) { error("foreign adapter") })
            assertIs<PortResult.Failure>(f.adapter.schedule(ticket(90), System.currentTimeMillis() + 1_000))
            f.bind()
            assertTrue(f.timers.matchesIntegration(f.clock, f.adapter))
            assertFalse(f.timers.matchesIntegration(AndroidProcessCookingTimerClock(), f.adapter))
            assertIs<PortResult.Failure>(f.adapter.bind(f.native.runtime, f.access, f.kitchen, f.native.boundary, f.policy) { error("second binding") })
            assertTrue(f.delivered.isEmpty()); assertNull(value(f.kitchen.cooking.readTimerMetadata(f.access.lease, SESSION)))
        } finally { other.close() }
    }

    @Test fun actualNativeMappingAndRuntimeGatePrecedeExactlyOneForegroundDueEffect() = fixture { f ->
        val native = f.start(TIMER, 2, 10)
        assertEquals(AndroidForegroundTimerPhase.ENQUEUED, f.phase(native))
        assertEquals("ARMED", f.slot(TIMER, "phase")); assertEquals(native.id, f.slot(TIMER, "ticket"))
        val snapshot = f.snapshot()
        assertEquals(listOf(id(10)), snapshot.pendingCommandIds)
        assertFalse(snapshot.progress.timers.single().document.encodeUtf8().decodeToString().contains(native.id))
        assertTrue(f.delivered.isEmpty())
        f.await(native, AndroidForegroundTimerPhase.DELIVERED); delay(100)
        assertEquals(listOf(native.id), f.delivered)
        assertEquals(CookingStatus.ACTIVE, f.snapshot().progress.status)
        assertTrue(f.snapshot().progress.completedStepIds.isEmpty())
    }

    @Test fun exactPauseCancelsHandlerAndAlarmWithoutRemovingSiblingIdentity() = fixture { f ->
        val first = f.start(TIMER, 3, 10); val sibling = f.start(OTHER, 2, 11)
        val firstPending = alarm(first); val siblingPending = alarm(sibling)
        try {
            value(f.timers.change(SESSION, f.snapshot().localRevision, id(12), CookingTimerAction.Pause(TIMER)))
            assertIs<PortResult.Failure>(f.adapter.inspect(first))
            assertNull(pending(first)); assertNotNull(pending(sibling))
            assertTrue(value(f.timers.inspect(SESSION)).single { it.timerId == OTHER }.alertAcknowledgedInThisOwner,
                "Exact sibling mapping must remain acknowledged after another timer pauses")
            f.await(sibling, AndroidForegroundTimerPhase.DELIVERED)
            assertEquals(listOf(sibling.id), f.delivered)
            assertEquals("paused", f.snapshot().progress.timers.single { it.timerId.value == TIMER }.status)
        } finally {
            alarms.cancel(firstPending); firstPending.cancel()
            alarms.cancel(siblingPending); siblingPending.cancel()
        }
    }

    @Test fun foregroundExitSuppressesDueEffectUntilSameLiveOwnerReturns() = fixture { f ->
        val native = f.start(TIMER, 2, 10); value(f.adapter.setForeground(false))
        delay(2_150); assertTrue(f.delivered.isEmpty())
        assertEquals(AndroidForegroundTimerPhase.SUSPENDED, f.phase(native))
        value(f.adapter.setForeground(true)); f.await(native, AndroidForegroundTimerPhase.DELIVERED)
        assertEquals(listOf(native.id), f.delivered)
    }

    @Test fun actualPersistedRecallBlocksDeliveryButKeepsExactCancellationAvailable() = fixture { f ->
        val native = f.start(TIMER, 2, 10); f.recalled = true
        assertIs<PortResult.Failure>(f.kitchen.cooking.download(f.access.lease, SESSION))
        f.await(native, AndroidForegroundTimerPhase.BLOCKED); assertTrue(f.delivered.isEmpty())
        value(f.timers.cancelAlert(SESSION, TIMER)); assertIs<PortResult.Failure>(f.adapter.inspect(native))
    }

    @Test fun realLeaseInvalidationCannotDeliverAndKnownOwnedFixtureStillRetires() = fixture { f ->
        val native = f.start(TIMER, 2, 10); value(f.native.invalidate())
        assertNull(f.native.boundary.current())
        f.await(native, AndroidForegroundTimerPhase.BLOCKED); assertTrue(f.delivered.isEmpty())
        assertIs<PortResult.Failure>(f.timers.inspect(SESSION))
        // finally performs exact known-store synthetic restore, retirement and acknowledged close.
    }

    @Test fun closedAdapterRemovesCallbacksButRuntimeStillUsesItsExactCancellationOwner() = fixture { f ->
        val native = f.start(TIMER, 2, 10); f.adapter.close()
        assertEquals(AndroidForegroundTimerPhase.CANCELLING, f.phase(native))
        value(f.native.runtime.cancel(f.access, native))
        assertIs<PortResult.Failure>(f.adapter.inspect(native)); delay(2_100); assertTrue(f.delivered.isEmpty())
        assertIs<PortResult.Failure>(f.adapter.setForeground(true))
    }

    @Test fun parsedForeignTicketCanNeverBypassActualDomainAndWorkGate() = fixture { f ->
        val foreign = ticket(90)
        value(f.adapter.schedule(foreign, System.currentTimeMillis() + 150))
        assertIs<PortResult.Failure>(f.adapter.schedule(foreign, System.currentTimeMillis() + 150))
        f.await(foreign, AndroidForegroundTimerPhase.BLOCKED); assertTrue(f.delivered.isEmpty())
        assertNull(value(f.kitchen.cooking.readTimerMetadata(f.access.lease, SESSION)))
        value(f.adapter.cancel(foreign)); assertIs<PortResult.Failure>(f.adapter.inspect(foreign))
    }

    @Test fun closingBorrowedFacadeDeniesDeliveryWithoutClaimingNativeCancellation() = fixture { f ->
        val native = f.start(TIMER, 2, 10); f.timers.close()
        f.await(native, AndroidForegroundTimerPhase.BLOCKED); assertTrue(f.delivered.isEmpty())
        value(f.native.runtime.cancel(f.access, native))
        assertIs<PortResult.Failure>(f.adapter.inspect(native))
    }

    private fun fixture(bind: Boolean = true, block: suspend (Fixture) -> Unit) = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val f = Fixture()
            var failure: Throwable? = null
            try { withTimeout(30_000) { f.open(bind); block(f) } }
            catch (caught: Throwable) { failure = caught; throw caught }
            finally {
                try { withContext(NonCancellable) { f.close() } }
                catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
            }
        }
    }

    private class Fixture {
        val clock = AndroidProcessCookingTimerClock()
        val policy = CookingTimerExecutionPolicy()
        val adapter = value(AndroidForegroundCookingTimerAdapter.create(cancellation, clock))
        val native = NativeTimerTestSession(context, adapter, policy)
        lateinit var access: PrivateSessionAccess; lateinit var kitchen: PrivateKitchenSession; lateinit var timers: SessionCookingTimers
        val delivered = mutableListOf<String>(); var recalled = false
        private var opened = false
        suspend fun open(bind: Boolean) {
            value(native.open()); opened = true; access = checkNotNull(native.currentAccess())
            kitchen = PrivateKitchenSession(access.scope, access.store, native.boundary, native.dispatcher,
                EpochClock { clock.read().epochMillis }, object : AccountTransport {
                    override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> = when {
                        call.operationId == "getPlan" && recalled -> PortResult.Value(ApiReply(410,
                            PrivateBytes("""{"type":"https://example.test/problem","title":"Synthetic recall","status":410,"code":"RECIPE_RECALLED","traceId":"synthetic"}""".encodeToByteArray()), contentType = "application/problem+json"))
                        call.operationId == "getPlan" -> reply(PLAN_BODY)
                        call.operationId == "getCookSession" -> reply(SESSION_BODY)
                        else -> PortResult.Failure(FailureReason.NOT_CONFIGURED)
                    }
                }, access.originBinding)
            value(kitchen.cooking.download(access.lease, SESSION))
            if (bind) bind()
        }
        suspend fun bind() {
            timers = value(adapter.bind(native.runtime, access, kitchen, native.boundary, policy) { ticket ->
                delivered += ticket.id; PortResult.Value(Unit)
            })
            value(adapter.setForeground(true))
        }
        suspend fun snapshot() = checkNotNull(value(kitchen.cooking.read(access.lease, SESSION)))
        suspend fun start(timer: String, seconds: Long, command: Int): NativeWorkTicket {
            val before = snapshot()
            val result = value(timers.change(SESSION, before.localRevision, id(command),
                CookingTimerAction.Start(timer, "step-one", seconds)))
            assertTrue(result.single { it.timerId == timer }.alertAcknowledgedInThisOwner)
            return value(NativeWorkTicket.fromNativeIdentity(slot(timer, "ticket"), NativeWorkKind.TIMER))
        }
        suspend fun slot(timer: String, field: String): String {
            val document = WireDocument.decode(checkNotNull(value(kitchen.cooking.readTimerMetadata(access.lease, SESSION))).record.payload.copyForCodec())
            val slots = checkNotNull((document.field("slots") as WireField.Value<WireDocument>).value.elementsOrNull())
            val slot = slots.single { (it.field("id") as WireField.Value<WireDocument>).value.stringOrNull() == timer }
            return checkNotNull((slot.field(field) as WireField.Value<WireDocument>).value.stringOrNull())
        }
        fun phase(ticket: NativeWorkTicket) = value(adapter.inspect(ticket)).phase
        suspend fun await(ticket: NativeWorkTicket, target: AndroidForegroundTimerPhase) = withTimeout(8_000) {
            while (true) {
                val observed = phase(ticket)
                if (observed == target) break
                if (observed in setOf(AndroidForegroundTimerPhase.DELIVERED, AndroidForegroundTimerPhase.BLOCKED,
                        AndroidForegroundTimerPhase.CLOCK_UNCERTAIN, AndroidForegroundTimerPhase.OUTCOME_UNKNOWN,
                        AndroidForegroundTimerPhase.CANCELLING)) {
                    val views = timers.inspect(SESSION)
                    val summary = when (views) {
                        is PortResult.Failure -> "inspection=${views.reason}"
                        is PortResult.Value -> views.value.joinToString { "${it.alertPhase}/${it.timing?.timing}/${it.alertAcknowledgedInThisOwner}" }
                    }
                    fail("Foreground timer reached $observed instead of $target; $summary")
                }
                delay(20)
            }
        }
        suspend fun close() {
            adapter.close()
            if (opened) value(native.retireAndClose()) else value(native.close())
        }
    }

    companion object {
        private lateinit var context: Context
        private lateinit var cancellation: AndroidNativeWorkCancellation
        private lateinit var alarms: AlarmManager
        @JvmStatic @BeforeClass fun initializeOnlyExplicitIsolatedTestServices() {
            context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            assertEquals("com.feedme.mealflow.test", context.packageName)
            try { WorkManager.getInstance(context); fail("WorkManager must not auto-initialize") }
            catch (_: IllegalStateException) { /* Required absent initializer, not accepting policy. */ }
            WorkManager.initialize(context, Configuration.Builder().setMinimumLoggingLevel(Log.ERROR).build())
            cancellation = value(AndroidNativeWorkCancellation.create(context,
                ComponentName(context, NativeTimerTestReceiver::class.java), WorkManager.getInstance(context)))
            alarms = checkNotNull(context.getSystemService(AlarmManager::class.java))
        }
        private fun pending(ticket: NativeWorkTicket): PendingIntent? {
            val identity = value(cancellation.timerIdentity(ticket))
            return PendingIntent.getBroadcast(context, identity.requestCode, identity.intent(), identity.pendingIntentFlags or PendingIntent.FLAG_NO_CREATE)
        }
        private fun alarm(ticket: NativeWorkTicket): PendingIntent {
            val identity = value(cancellation.timerIdentity(ticket))
            val pending = checkNotNull(PendingIntent.getBroadcast(context, identity.requestCode, identity.intent(), identity.pendingIntentFlags))
            alarms.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 86_400_000, pending)
            return pending
        }
        private const val SESSION = "00000000-0000-4000-8000-000000000001"
        private const val PLAN = "00000000-0000-4000-8000-000000000002"
        private const val VERSION = "00000000-0000-4000-8000-000000000003"
        private const val INGREDIENT = "00000000-0000-4000-8000-000000000004"
        private const val TIMER = "00000000-0000-4000-8000-000000000005"
        private const val OTHER = "00000000-0000-4000-8000-000000000006"
        private const val SESSION_BODY = """{"id":"$SESSION","version":1,"createdAt":"2026-09-13T07:00:00Z","updatedAt":"2026-09-13T08:00:00Z","planId":"$PLAN","status":"active","currentStepId":"step-one","completedStepIds":[],"deviceSequence":0,"timers":[]}"""
        private const val PLAN_BODY = """{"id":"$PLAN","version":1,"createdAt":"2026-09-13T07:00:00Z","updatedAt":"2026-09-13T08:00:00Z","recipeVersionId":"$VERSION","mode":"cook","status":"ready","constraints":{"ingredientIds":["$INGREDIENT"],"energy":"little","equipmentIds":["bowl"],"servings":1,"hardExcludedIngredientIds":[]},"recipeSnapshot":{"id":"$VERSION","recipeId":"$VERSION","version":1,"createdAt":"2026-09-13T07:00:00Z","updatedAt":"2026-09-13T08:00:00Z","title":"Synthetic native timer fixture","reviewStatus":"published","servings":1,"activeMinutes":5,"totalMinutes":5,"utensilCount":1,"equipmentIds":["bowl"],"modes":["cook"],"tasteTags":[],"ingredients":[{"ingredientId":"$INGREDIENT","quantity":1,"unit":"g","optional":false}],"steps":[{"stepId":"step-one","position":1,"instruction":"Synthetic only","ingredientIds":["$INGREDIENT"],"requiredEquipmentIds":["bowl"],"mandatorySafetyStep":false}]},"missingIngredients":[],"changes":[],"reasons":[],"catalogRevision":"synthetic"}"""
        private fun id(n: Int) = "00000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
        private fun ticket(n: Int) = value(NativeWorkTicket.fromNativeIdentity(id(n), NativeWorkKind.TIMER))
        private fun reply(body: String): PortResult<ApiReply> = PortResult.Value(ApiReply(200, PrivateBytes(body.encodeToByteArray()), etag = "\"1\"", contentType = "application/json"))
        private fun <T> value(result: PortResult<T>): T = when (result) { is PortResult.Value -> result.value; is PortResult.Failure -> error("Expected value: ${result.reason}") }
    }
}

/** Exact alarm cancellation fixture only. No account/provider lookup or timer delivery. */
class NativeTimerTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
