package com.feedme.development.progress

import android.os.Bundle
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feedme.core.ports.*
import com.feedme.kitchen.CookingTimerTiming
import com.feedme.mealflow.*
import com.feedme.mealflow.timers.*
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Select the stages separately, AFTER the host/timer UI suites acknowledged their final
 * close/SIGNED_OUT state on this isolated UID. Stage A intentionally never finishes: the runner
 * witnesses RETAINED_UNCLOSED, force-stops ONLY this package, verifies old PID absence, then runs
 * stage B in a new process. This is controlled process restart, NOT power loss or unknown COMMIT.
 * The cache markers contain public synthetic IDs and process identity only; never authority. */
@RunWith(AndroidJUnit4::class)
class AndroidProgressProcessTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val owner get() = (context.applicationContext as ProgressApplication).owner
    private fun <T> main(action: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main.immediate) { action() } }
    private fun <T> value(result: PortResult<T>): T = when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> error("Expected acknowledged progress stage: ${result.reason}")
    }
    private fun failure(result: PortResult<*>, expected: FailureReason) {
        assertTrue(result is PortResult.Failure); assertEquals(expected, (result as PortResult.Failure).reason)
    }

    @Test fun stageARetainAcknowledgedCookingThenAwaitExternalProcessStop(): Unit = main {
        assertEquals(ProgressNativeInventory.PACKAGE, context.packageName)
        assertEquals(ProgressHostPhase.NEW, owner.states.value.phase)
        // A failed/missing inventory is never permission to create or clear the app. The root
        // runner additionally pairs this with all six preceding host outcomes on its owned UID.
        assertEquals(ProgressInventoryKind.EXISTING, value(ProgressNativeInventory.inspect(context)))
        val directory = evidenceDirectory(create = true)
        assertTrue("Never overwrite an earlier process attempt", directory.list()!!.isEmpty())
        value(owner.inspectStartup()); assertEquals(ProgressHostPhase.RESUME_AVAILABLE, owner.states.value.phase)
        value(owner.resume()); assertEquals(ProgressHostPhase.START_AVAILABLE, owner.states.value.phase)
        assertNull(owner.boundary.current()); assertNull(owner.experience.value)
        value(owner.start()); val experience = checkNotNull(owner.experience.value)
        value(experience.restore()); value(experience.refreshContext())
        experience.edit { it.copy(mode = MealMode.ASSEMBLE, energy = MealEnergy.ASSEMBLE, servings = "1",
            totalMinutes = "5", ingredientIds = listOf(ProgressCatalog.ingredientId), equipmentIds = listOf("bowl"),
            tasteTags = listOf("crunch")) }
        value(experience.findMeal()); value(experience.recipe())
        val selected = checkNotNull(experience.meals.states.value.plan).plan.id.value
        value(experience.prepareCooking())
        value(experience.confirmCooking(checkNotNull(experience.cookingNavigation.value.confirmation)))
        val cooking = checkNotNull(experience.cooking.states.value.cooking)
        assertEquals(selected, cooking.plan.id.value)
        assertTrue(experience.cooking.states.value.serverAcknowledged)
        assertTrue(cooking.pendingCommandIds.isEmpty())
        assertEquals(CookingFlowPhase.COOKING, experience.cooking.states.value.phase)
        // There is no Activity in this stage. Attach the actual owner's foreground lifecycle
        // token explicitly, not a scheduler/policy replacement, and intentionally retain it.
        val foreground = owner.attachForegroundHost()
        value(owner.setForegroundHost(foreground, true))
        value(experience.openTimers()); value(experience.timerDuration("3600")); value(experience.startTimer())
        val timers = checkNotNull(experience.timers)
        val started = checkNotNull(timers.states.value.snapshot).timers.single()
        assertEquals("running", started.status); assertEquals(3600L, started.durationSeconds)
        assertTrue(started.alertAcknowledgedInThisOwner)
        assertEquals(CookingTimerDeliveryPhase.ENQUEUED, started.deliveryPhase)
        assertNull(timers.states.value.pendingAction); assertNull(timers.states.value.due)
        val command = checkNotNull(experience.cooking.states.value.cooking).pendingCommandIds.single()
        value(experience.backFromTimers())
        assertEquals(listOf(command), checkNotNull(experience.cooking.states.value.cooking).pendingCommandIds)
        // Explicitly send/apply the existing original timer progress command; local restoration
        // and opening TIMER never synchronize it automatically or allocate a replacement ID.
        value(experience.synchronizeCooking())
        assertTrue(checkNotNull(experience.cooking.states.value.cooking).pendingCommandIds.isEmpty())
        assertTrue(experience.cooking.states.value.serverAcknowledged)
        value(experience.openTimers()); value(experience.tickTimers())
        val acknowledged = checkNotNull(timers.states.value.snapshot).timers.single()
        assertEquals(started.timerId, acknowledged.timerId); assertEquals(started.generation, acknowledged.generation)
        assertTrue(acknowledged.generation > 0); assertTrue(acknowledged.alertAcknowledgedInThisOwner)
        assertEquals(CookingTimerDeliveryPhase.ENQUEUED, acknowledged.deliveryPhase)
        assertNull(timers.states.value.due)
        val observation = Observation(selected, cooking.id, acknowledged.timerId, acknowledged.generation,
            Process.myPid(), Process.getStartElapsedRealtime())
        writeNew(File(directory, "retained.json"), observation.json("retained-unclosed"))
        assertEquals(ProgressHostPhase.ACTIVE, owner.states.value.phase)
        assertNotNull(owner.boundary.current()); assertSame(experience, owner.experience.value)
        instrumentation.sendStatus(2, Bundle().apply {
            putString("feedme_progress_stage", "RETAINED_UNCLOSED")
            putString("feedme_progress_pid", observation.pid.toString())
            putString("feedme_progress_start_elapsed", observation.startElapsed.toString())
        })
        // No finally-close and no retirement: native owners remain open at the witnessed stop.
        // If the runner fails to act, timeout is a failure, not an accepted interruption.
        withTimeout<Unit>(120_000) { awaitCancellation() }
    }

    @Test fun stageBRestoreExactCookingAndExistingServiceThenExplicitlyReset() = main {
        assertEquals(ProgressNativeInventory.PACKAGE, context.packageName)
        assertEquals(ProgressHostPhase.NEW, owner.states.value.phase)
        val directory = evidenceDirectory(create = false)
        assertEquals(setOf("retained.json"), directory.list()!!.toSet())
        val expected = readObservation(File(directory, "retained.json"))
        assertNotEquals(expected.pid, Process.myPid())
        assertTrue(Process.getStartElapsedRealtime() > expected.startElapsed)
        assertEquals(ProgressInventoryKind.EXISTING, value(ProgressNativeInventory.inspect(context)))
        value(owner.inspectStartup()); assertEquals(ProgressHostPhase.RESUME_AVAILABLE, owner.states.value.phase)
        assertNull(owner.boundary.current()); assertNull(owner.experience.value)
        value(owner.resume()); assertEquals(ProgressHostPhase.ACTIVE, owner.states.value.phase)
        val experience = checkNotNull(owner.experience.value)
        value(experience.restore()) // Local read only; no prepare/confirm/automatic POST.
        val timerOwner = checkNotNull(experience.timers)
        val restoredTimer = checkNotNull(experience.cooking.states.value.cooking).progress.timers.single()
        assertEquals(expected.timerId, restoredTimer.timerId.value)
        assertEquals("running", restoredTimer.status); assertEquals("3600", restoredTimer.durationSeconds.jsonToken)
        val restoredTimerJson = Json.parseToJsonElement(restoredTimer.document.encodeUtf8().decodeToString()).toString()
        fun exactPin() {
            val current = checkNotNull(experience.cooking.states.value.cooking)
            assertEquals(expected.sessionId, current.id); assertEquals(expected.planId, current.plan.id.value)
            assertEquals(expected.planId, experience.cooking.states.value.plan!!.id.value)
            assertTrue(current.pendingCommandIds.isEmpty())
            assertEquals(restoredTimerJson,
                Json.parseToJsonElement(current.progress.timers.single().document.encodeUtf8().decodeToString()).toString())
        }
        fun unrestoredAlert() {
            val row = checkNotNull(timerOwner.states.value.snapshot).timers.single()
            assertEquals(expected.timerId, row.timerId); assertEquals(expected.timerGeneration, row.generation)
            assertEquals("running", row.status); assertEquals(CookingTimerAlertPhase.ARMED, row.alertPhase)
            assertFalse(row.alertAcknowledgedInThisOwner)
            assertEquals(CookingTimerTiming.UNCERTAIN, checkNotNull(row.timing).timing)
            assertNull(row.timing?.remainingMillis)
            assertEquals(CookingTimerDeliveryPhase.NOT_INSTALLED, row.deliveryPhase)
            assertNull(timerOwner.states.value.due); assertNull(timerOwner.states.value.pendingAction)
        }
        exactPin(); assertTrue(experience.cooking.states.value.historical)
        assertFalse(experience.cooking.states.value.serverAcknowledged)
        assertEquals(listOf(ProgressCatalog.ingredientId), experience.meals.states.value.draft!!.ingredientIds)
        value(experience.openTimers()); unrestoredAlert(); exactPin()
        value(experience.tickTimers()); unrestoredAlert(); exactPin()
        value(experience.backFromTimers()); assertFalse(timerOwner.states.value.visible)
        value(experience.tickTimers()); unrestoredAlert(); exactPin()
        value(experience.openTimers()); unrestoredAlert(); exactPin()
        value(experience.backFromTimers())
        // Restore used service.open(false). The old exact IDs must be served by its retained
        // encrypted ledger; a replacement fresh ledger has no such session/plan and must fail.
        value(experience.cooking.refresh()); exactPin()
        value(experience.openTimers()); unrestoredAlert(); value(experience.backFromTimers())
        assertFalse(experience.cooking.states.value.serverAcknowledged) // GET is not a new mutation ACK.
        failure(experience.cooking.retryStart(), FailureReason.CONFLICT)
        exactPin() // An attached start has no lost-ACK finalization proof in this new process;
                   // retry is refused, never translated into a new POST or invented receipt.
        value(experience.cooking.refresh()); exactPin()
        owner.requestReset(); val consent = checkNotNull(owner.states.value.confirmation)
        assertEquals(ProgressConfirmationKind.RESET, consent.kind)
        value(owner.confirmReset(consent)); assertEquals(ProgressHostPhase.CLOSED, owner.states.value.phase)
        assertNull(owner.boundary.current()); assertNull(owner.experience.value)
        assertNull(experience.forms.value.values); assertNull(experience.cooking.states.value.cooking)
        assertNull(timerOwner.states.value.snapshot); assertNull(timerOwner.states.value.due)
        // Confirm the real terminal control routes to SIGNED_OUT, not an inferred file absence.
        value(owner.inspectStartup()); assertEquals(ProgressHostPhase.RESUME_AVAILABLE, owner.states.value.phase)
        value(owner.resume()); assertEquals(ProgressHostPhase.START_AVAILABLE, owner.states.value.phase)
        assertNull(owner.boundary.current()); value(owner.close())
        writeNew(File(directory, "restored-and-reset.json"),
            Observation(expected.planId, expected.sessionId, expected.timerId, expected.timerGeneration,
                Process.myPid(), Process.getStartElapsedRealtime()).json("restored-and-reset"))
        instrumentation.sendStatus(2, Bundle().apply {
            putString("feedme_progress_stage", "RESTORED_AND_RESET")
            putString("feedme_progress_pid", Process.myPid().toString())
            putString("feedme_progress_start_elapsed", Process.getStartElapsedRealtime().toString())
        })
    }

    private data class Observation(val planId: String, val sessionId: String, val timerId: String,
        val timerGeneration: Long, val pid: Int, val startElapsed: Long) {
        fun json(stage: String) = buildJsonObject {
            put("version", 2); put("stage", stage); put("planId", planId); put("sessionId", sessionId)
            put("timerId", timerId); put("timerGeneration", timerGeneration)
            put("pid", pid); put("startElapsedRealtime", startElapsed)
        }
    }
    private fun evidenceDirectory(create: Boolean): File {
        check(context.packageName == ProgressNativeInventory.PACKAGE)
        val base = context.cacheDir
        val stat = Os.lstat(base.path)
        check(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Process.myUid())
        val directory = File(base.canonicalFile, "progress-process-evidence")
        if (create) { check(!directory.exists()); check(directory.mkdir()); Os.chmod(directory.path, 448) }
        val checked = Os.lstat(directory.path)
        check(OsConstants.S_ISDIR(checked.st_mode) && checked.st_uid == Process.myUid() && (checked.st_mode and 511) == 448)
        return directory
    }
    private fun writeNew(file: File, document: JsonObject) {
        check(!file.exists() && file.createNewFile()); Os.chmod(file.path, 384)
        val bytes = document.toString().encodeToByteArray(); check(bytes.size <= 512)
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    }
    private fun readObservation(file: File): Observation {
        val stat = Os.lstat(file.path)
        check(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Process.myUid() && stat.st_nlink == 1L &&
            (stat.st_mode and 511) == 384 && stat.st_size in 1..512)
        val raw = file.readBytes(); val document = Json.parseToJsonElement(raw.decodeToString()).jsonObject
        check(document.keys == setOf("version", "stage", "planId", "sessionId", "timerId", "timerGeneration", "pid", "startElapsedRealtime"))
        check(document.getValue("version") == JsonPrimitive(2))
        check(document.getValue("stage") == JsonPrimitive("retained-unclosed"))
        val plan = document.getValue("planId").jsonPrimitive.content
        val session = document.getValue("sessionId").jsonPrimitive.content
        val timer = document.getValue("timerId").jsonPrimitive.content
        val timerGeneration = document.getValue("timerGeneration").jsonPrimitive.long
        val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        check(uuid.matches(plan) && uuid.matches(session) && uuid.matches(timer) && timerGeneration > 0)
        val pid = document.getValue("pid").jsonPrimitive.int
        val start = document.getValue("startElapsedRealtime").jsonPrimitive.long
        check(pid > 0 && start >= 0)
        val value = Observation(plan, session, timer, timerGeneration, pid, start)
        check(raw.contentEquals(value.json("retained-unclosed").toString().encodeToByteArray()))
        return value
    }
}
