package com.feedme.development.progress

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feedme.app.mealflow.MealFlowExperience
import com.feedme.core.ports.*
import com.feedme.kitchen.CookingStatus
import com.feedme.mealflow.*
import com.feedme.mealflow.timers.*
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Run on the explicitly owned progress UID AFTER the complete six-case host suite, before
 * controlled process stages. Every case independently requires real existing terminal control
 * to restore SIGNED_OUT before creating its own synthetic identity. No fresh fallback, app clear,
 * replacement native owner, direct ledger edit, scheduler fake or AndroidTest production import.
 * Real Activity/experience/runtime/native store/Handler, explicitly synthetic identity/service.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AndroidProgressTimerHostTest {
    @get:Rule val testName = TestName()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val owner get() = (context.applicationContext as ProgressApplication).owner
    private val ui get() = instrumentation.uiAutomation
    private fun <T> main(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main.immediate) { block() } }
    private fun <T> value(result: PortResult<T>): T = when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> error("Acknowledged progress timer result required: ${result.reason}")
    }

    private inner class Fixture {
        var admitted = false
        var created = false
        var scenario: ActivityScenario<ProgressActivity>? = null
        lateinit var experience: MealFlowExperience
        val timers get() = checkNotNull(experience.timers)
        fun snapshot() = main { checkNotNull(timers.states.value.snapshot) }
        fun rows() = snapshot().timers.filter { it.status != null }
        fun settled() = await {
            main { !experience.forms.value.busy && !timers.states.value.busy }
        }
        fun open() {
            main {
                check(retained == null) { "A previous failed native fixture is still retained" }
                check(Build.VERSION.SDK_INT >= 27)
                assertEquals(ProgressNativeInventory.PACKAGE, context.packageName)
                check(owner.states.value.phase in setOf(ProgressHostPhase.NEW, ProgressHostPhase.CLOSED))
                assertEquals(ProgressInventoryKind.EXISTING, value(ProgressNativeInventory.inspect(context)))
                // Retain before any owner I/O. Existing ACTIVE/PendingSetup/unknown state is not
                // a test fixture and is never reset, replaced or treated as a clean installation.
                retained = this@Fixture; admitted = true
                value(owner.inspectStartup()); assertEquals(ProgressHostPhase.RESUME_AVAILABLE, owner.states.value.phase)
                value(owner.resume()); assertEquals(ProgressHostPhase.START_AVAILABLE, owner.states.value.phase)
                assertNull(owner.boundary.current()); assertNull(owner.experience.value)
            }
            scenario = ActivityScenario.launch(Intent(context, ProgressActivity::class.java))
            click("Start synthetic preview")
            await { main { owner.states.value.phase == ProgressHostPhase.ACTIVE && owner.experience.value != null } }
            main { created = true; experience = checkNotNull(owner.experience.value); assertNotNull(experience.timers) }
            waitText("What’s the"); settled()
            main {
                value(experience.refreshContext())
                experience.edit { it.copy(mode = MealMode.ASSEMBLE, energy = MealEnergy.ASSEMBLE, servings = "1",
                    totalMinutes = "5", ingredientIds = listOf(ProgressCatalog.ingredientId), equipmentIds = listOf("bowl"),
                    tasteTags = listOf("crunch")) }
                value(experience.findMeal()); value(experience.recipe())
            }
            click("Cook this plan"); waitText("Start this exact plan?"); click("Confirm cooking start")
            await { main { experience.cooking.states.value.phase == CookingFlowPhase.COOKING && !experience.forms.value.busy } }
            waitText("One step at a time.")
            click("Timers for this step"); waitText("Time, kept in context."); settled()
            assertTrue(main { timers.states.value.visible }); assertTrue(snapshot().timers.isEmpty())
            assertNull(main { timers.states.value.pendingAction }); assertNull(main { timers.states.value.due })
        }
        fun start(seconds: String, expectedRows: Int): String {
            setDuration(seconds)
            assertEquals(seconds, main { timers.states.value.durationText })
            click("Start timer")
            await { main {
                !timers.states.value.busy && !experience.forms.value.busy && timers.states.value.pendingAction == null &&
                    timers.states.value.snapshot?.timers?.size == expectedRows
            } }
            val row = rows().last()
            assertEquals("running", row.status)
            assertTrue("Native installation acknowledgement required", row.alertAcknowledgedInThisOwner)
            return row.timerId
        }
        fun close() {
            scenario?.close(); scenario = null
            main {
                if (created) {
                    assertSame(experience, owner.experience.value)
                    check(owner.states.value.canReset) { "Only the test's verified created identity can be reset" }
                    val retainedTimers = timers
                    owner.requestReset(); val consent = checkNotNull(owner.states.value.confirmation)
                    assertEquals(ProgressConfirmationKind.RESET, consent.kind)
                    value(owner.confirmReset(consent)); assertEquals(ProgressHostPhase.CLOSED, owner.states.value.phase)
                    assertNull(owner.boundary.current()); assertNull(owner.experience.value)
                    assertNull(experience.forms.value.values); assertNull(retainedTimers.states.value.snapshot)
                    // Prove the next case gets actual SIGNED_OUT, not an inferred empty directory.
                    value(owner.inspectStartup()); assertEquals(ProgressHostPhase.RESUME_AVAILABLE, owner.states.value.phase)
                    value(owner.resume()); assertEquals(ProgressHostPhase.START_AVAILABLE, owner.states.value.phase)
                    assertNull(owner.boundary.current()); assertNull(owner.experience.value)
                    value(owner.close()); assertEquals(ProgressHostPhase.CLOSED, owner.states.value.phase)
                } else if (admitted) {
                    // A failed admission/open may own resources, but does not authorize erasure.
                    value(owner.close())
                }
                if (admitted) retained = null // Drop only after actual acknowledged close/reset.
            }
        }
    }
    private fun withHost(block: (Fixture) -> Unit) {
        val fixture = Fixture(); var failure: Throwable? = null
        try { fixture.open(); block(fixture) }
        catch (error: Throwable) {
            failure = error
            // Only this test's positively acquired synthetic identity can be captured. Unknown
            // pre-existing/admission-failed state is never inspected for diagnostics or erased.
            if (fixture.created) try { diagnostic(fixture) } catch (capture: Throwable) { error.addSuppressed(capture) }
            throw error
        }
        finally { try { fixture.close() } catch (cleanup: Throwable) {
            if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
        } }
    }

    @Test fun aVisibleControlsKeepTwoExactTimersAndRequireExplicitRemovalConsent() = withHost { f ->
        val first = f.start("120", 1); val second = f.start("180", 2)
        assertNotEquals(first, second); waitText("Timer 2", scroll = true); screenshot("progress-timer-running")
        click("Pause timer 1")
        await { f.rows().single { it.timerId == first }.status == "paused" && main { !f.timers.states.value.busy } }
        assertEquals("running", f.rows().single { it.timerId == second }.status)
        assertTrue(f.rows().single { it.timerId == second }.alertAcknowledgedInThisOwner)
        waitText("Paused", scroll = true); screenshot("progress-timer-paused")
        click("Resume timer 1")
        await { f.rows().single { it.timerId == first }.status == "running" && main { !f.timers.states.value.busy } }
        click("Reset timer 1")
        await { f.rows().single { it.timerId == first }.status == "paused" && main { !f.timers.states.value.busy } }
        assertEquals(120L, f.rows().single { it.timerId == first }.durationSeconds)
        assertEquals(120_000L, f.rows().single { it.timerId == first }.timing?.remainingMillis)
        click("Remove timer 1"); waitText("Remove this timer?")
        val oldConsent = main { checkNotNull(f.experience.timerConfirmation.value) }
        click("Keep timer"); await { textNodes("Remove this timer?").none { it.isVisibleToUser } }
        assertEquals(setOf(first, second), f.rows().map { it.timerId }.toSet())
        click("Remove timer 1"); waitText("Remove this timer?")
        main {
            assertNotSame(oldConsent, f.experience.timerConfirmation.value)
            val rejected = f.experience.confirmTimerRemoval(oldConsent)
            assertTrue(rejected is PortResult.Failure); assertEquals(FailureReason.CONFLICT, (rejected as PortResult.Failure).reason)
        }
        click("Confirm timer removal")
        await { f.rows().map { it.timerId } == listOf(second) && main { !f.timers.states.value.busy } }
        val cleanup = f.snapshot().timers.single { it.timerId == first }
        assertNull(cleanup.status); assertEquals(CookingTimerAlertPhase.QUIET, cleanup.alertPhase)
        assertFalse(cleanup.alertAcknowledgedInThisOwner)
        assertEquals(CookingTimerDeliveryPhase.NOT_INSTALLED, cleanup.deliveryPhase)
        assertNull(main { f.timers.states.value.pendingAction })
        await { textNodes("Timer 2").none { it.isVisibleToUser } }
        assertTrue(f.rows().single().alertAcknowledgedInThisOwner)
        main {
            val cooking = checkNotNull(f.experience.cooking.states.value.cooking)
            assertEquals(CookingStatus.ACTIVE, cooking.progress.status); assertTrue(cooking.progress.completedStepIds.isEmpty())
            assertTrue(cooking.pendingCommandIds.isNotEmpty()); assertFalse(f.experience.cooking.states.value.serverAcknowledged)
        }
        // Synchronization is a separate explicit action, using the service's actual retained
        // original-key receipts. Merely opening TIMER never drains this shared cooking journal.
        back(); await { main { !f.timers.states.value.visible } }
        repeat(12) {
            if (main { f.experience.cooking.states.value.cooking!!.pendingCommandIds.isNotEmpty() })
                main { value(f.experience.synchronizeCooking()) }
        }
        main { assertTrue(f.experience.cooking.states.value.cooking!!.pendingCommandIds.isEmpty())
            assertTrue(f.experience.cooking.states.value.serverAcknowledged) }
    }

    @Test fun bBackAndActivityRecreationRetainExactTimerOwnerWithoutPauseOrNewCommand() = withHost { f ->
        val id = f.start("120", 1)
        val owner = f.timers
        val before = f.snapshot()
        val pending = main { f.experience.cooking.states.value.cooking!!.pendingCommandIds.toList() }
        back(); await { main { !f.timers.states.value.visible } }
        waitText("One step at a time.")
        f.scenario!!.recreate(); waitText("One step at a time.")
        main { assertSame(owner, f.experience.timers); assertSame(f.experience, this.owner.experience.value) }
        click("Timers for this step"); waitText("Time, kept in context."); f.settled()
        val after = f.snapshot()
        assertEquals(before.sessionId, after.sessionId); assertEquals(before.planId, after.planId)
        assertEquals(before.localRevision, after.localRevision)
        assertEquals(id, after.timers.single().timerId); assertEquals("running", after.timers.single().status)
        assertEquals(pending, main { f.experience.cooking.states.value.cooking!!.pendingCommandIds })
        assertNull(main { f.timers.states.value.pendingAction }); screenshot("progress-timer-retained")
    }

    @Test fun cForegroundDueBannerIsNativeGatedAndDoesNotAdvanceOrCompleteCooking() = withHost { f ->
        val before = main { checkNotNull(f.experience.cooking.states.value.cooking) }
        // Native durable admission must finish while RUNNING. A three-second timer can expire
        // during real fsyncs and correctly retain a conflicted original instead of installing.
        val id = f.start("15", 1)
        val timerPin = f.snapshot()
        val pending = main { f.experience.cooking.states.value.cooking!!.pendingCommandIds.toList() }
        back(); await { main { !f.timers.states.value.visible } }
        await(timeoutMillis = 30_000) { main { f.timers.states.value.due?.timerId == id } }
        waitText("A timer reached its estimated end."); screenshot("progress-timer-due")
        main {
            val after = checkNotNull(f.experience.cooking.states.value.cooking)
            assertEquals(before.id, after.id); assertEquals(before.progress.currentStepId, after.progress.currentStepId)
            assertEquals(before.progress.completedStepIds, after.progress.completedStepIds)
            assertEquals(CookingStatus.ACTIVE, after.progress.status); assertEquals(pending, after.pendingCommandIds)
        }
        click("View timers"); waitText("Timer reached its estimated end")
        val delivered = awaitDelivered(f, timerPin, id)
        val deliveredGeneration = checkNotNull(delivered.snapshot).timers.single().generation
        click("Reset timer 1")
        await { main { !f.timers.states.value.busy && f.timers.states.value.due == null } && f.rows().single().status == "paused" }
        assertEquals(id, f.rows().single().timerId)
        assertTrue(f.rows().single().generation > deliveredGeneration)
        await { textNodes("Timer reached its estimated end").none { it.isVisibleToUser } }
        main {
            val after = checkNotNull(f.experience.cooking.states.value.cooking)
            assertEquals(before.progress.currentStepId, after.progress.currentStepId)
            assertEquals(before.progress.completedStepIds, after.progress.completedStepIds)
            assertEquals(CookingStatus.ACTIVE, after.progress.status)
        }
    }

    @Test fun dStoppedActivityCannotDeliverInBackgroundButSameOwnerMayDeliverOnReturn() = withHost { f ->
        val id = f.start("5", 1)
        assertNull(main { f.timers.states.value.due })
        val timerPin = f.snapshot()
        val currentStep = timerPin.currentStepId
        val timerOwner = f.timers
        f.scenario!!.moveToState(Lifecycle.State.CREATED)
        assertEquals(Lifecycle.State.CREATED, f.scenario!!.state)
        // Real wall/elapsed time and actual onStop foreground fence, not a replaced clock or
        // accepting policy. This proves this bounded stopped interval, not OS background alarms.
        SystemClock.sleep(5_250)
        main {
            assertSame(timerOwner, f.experience.timers)
            assertNull(f.timers.states.value.due)
            value(f.experience.tickTimers())
            assertNull(f.timers.states.value.due)
        }
        assertEquals(CookingTimerDeliveryPhase.SUSPENDED, f.rows().single().deliveryPhase)
        assertEquals(currentStep, f.snapshot().currentStepId)
        f.scenario!!.moveToState(Lifecycle.State.RESUMED)
        await { main { f.timers.states.value.due?.timerId == id } }
        top()
        waitText("Timer reached its estimated end")
        val delivered = awaitDelivered(f, timerPin, id)
        assertEquals(currentStep, checkNotNull(delivered.snapshot).currentStepId)
        assertEquals(CookingStatus.ACTIVE, main { f.experience.cooking.states.value.cooking!!.progress.status })
    }

    private fun awaitDelivered(f: Fixture, expected: CookingTimerSnapshot, timerId: String): CookingTimerFlowState {
        val generation = expected.timers.single { it.timerId == timerId }.generation
        var delivered: CookingTimerFlowState? = null
        await {
            main {
                // The due effect runs inside the native gate while delivery is still CHECKING;
                // DELIVERED follows only after the gate's suspending return is acknowledged.
                // A tick can also coalesce with the UI's in-flight read and return its cached
                // observation. Poll only reads, accepting one coherent terminal snapshot rather
                // than promoting due alone or joining fields from separate point-in-time reads.
                val state = value(f.experience.tickTimers())
                val snapshot = checkNotNull(state.snapshot)
                val row = snapshot.timers.single { it.timerId == timerId }
                assertEquals(expected.sessionId, snapshot.sessionId)
                assertEquals(expected.planId, snapshot.planId)
                assertEquals(expected.recipeVersionId, snapshot.recipeVersionId)
                assertEquals(expected.localRevision, snapshot.localRevision)
                assertEquals(expected.currentStepId, snapshot.currentStepId)
                assertEquals(expected.pendingCommandCount, snapshot.pendingCommandCount)
                assertEquals(CookingStatus.ACTIVE, snapshot.status)
                assertEquals(generation, row.generation)
                assertEquals("running", row.status)
                assertEquals(CookingTimerAlertPhase.ARMED, row.alertPhase)
                assertTrue(row.alertAcknowledgedInThisOwner)
                assertNull(state.pendingAction); assertNull(state.failureReason)
                assertTrue("No unknown/blocked delivery may become acknowledged", row.deliveryPhase in setOf(
                    CookingTimerDeliveryPhase.ENQUEUED, CookingTimerDeliveryPhase.SUSPENDED,
                    CookingTimerDeliveryPhase.CHECKING, CookingTimerDeliveryPhase.DELIVERED))
                if (row.deliveryPhase != CookingTimerDeliveryPhase.DELIVERED) false else {
                    val due = checkNotNull(state.due)
                    assertEquals(expected.sessionId, due.sessionId)
                    assertEquals(timerId, due.timerId); assertEquals(generation, due.generation)
                    delivered = state
                    true
                }
            }
        }
        return checkNotNull(delivered)
    }

    private fun await(timeoutMillis: Long = 12_000, test: () -> Boolean) {
        require(timeoutMillis in 1..30_000)
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync(); if (test()) return; SystemClock.sleep(100)
        }
        fail("Timed out waiting for exact progress timer transition")
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val values = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { values += node; repeat(node.childCount) { node.getChild(it)?.let(::visit) } }
        ui.rootInActiveWindow?.let(::visit); return values
    }
    private fun normalized(value: CharSequence?) = value?.toString()?.replace(Regex("\\s+"), " ")
    private fun textNodes(text: String) = nodes().filter {
        normalized(it.text)?.contains(text) == true || normalized(it.contentDescription)?.contains(text) == true
    }
    private fun exactLabel(node: AccessibilityNodeInfo, text: String) =
        normalized(node.text) == text || normalized(node.contentDescription) == text
    private fun waitText(text: String, scroll: Boolean = false) {
        repeat(60) { attempt ->
            instrumentation.waitForIdleSync()
            if (textNodes(text).any { it.isVisibleToUser }) return
            if (scroll && attempt > 2 && attempt % 3 == 2)
                nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(200)
        }
        fail("Missing progress timer UI: $text")
    }
    private fun top() {
        repeat(20) {
            if (textNodes("← Back").any { it.isVisibleToUser }) return
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            SystemClock.sleep(350)
        }
    }
    private fun click(text: String) {
        if (textNodes(text).none { it.isVisibleToUser }) top()
        repeat(60) { attempt ->
            instrumentation.waitForIdleSync()
            // Locate label and clickable ancestor in this one snapshot. Never return from a
            // text wait and assume a later recomposed tree contains the same node.
            val snapshot = nodes()
            val labels = snapshot.filter { it.isVisibleToUser && exactLabel(it, text) }
            for (label in labels) {
                var action: AccessibilityNodeInfo? = label
                while (action != null && !action.isClickable) action = action.parent
                val candidate = action ?: continue
                if (label.refresh() && exactLabel(label, text) && label.isVisibleToUser &&
                    candidate.refresh() && candidate.isVisibleToUser && candidate.isEnabled) {
                    // Once an action is dispatched it is never repeated, even if the next frame
                    // is late. The caller independently waits for its exact controller outcome.
                    assertTrue("Native click was not accepted: $text", candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    instrumentation.waitForIdleSync(); SystemClock.sleep(250); return
                }
            }
            if (labels.isEmpty() && attempt % 3 == 2)
                snapshot.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(200)
        }
        fail("No exact visible enabled progress timer action: $text")
    }
    private fun setDuration(text: String) {
        top(); waitText("Timer duration", scroll = true)
        val field = nodes().single { it.isEditable && it.isVisibleToUser }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
        await { main { owner.experience.value?.timers?.states?.value?.durationText == text && owner.experience.value?.forms?.value?.busy == false } }
    }
    private fun back() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); instrumentation.waitForIdleSync() }
    private fun diagnostic(fixture: Fixture) {
        val name = testName.methodName
        check(name in METHODS)
        val base = context.cacheDir
        val baseStat = Os.lstat(base.path)
        check(OsConstants.S_ISDIR(baseStat.st_mode) && baseStat.st_uid == Process.myUid())
        val directory = File(base.canonicalFile, "progress-timer-ui-diagnostics")
        if (!diagnosticsClaimed) {
            check(!directory.exists()) { "Never overwrite another diagnostic attempt" }
            check(directory.mkdir()); Os.chmod(directory.path, 448); diagnosticsClaimed = true
        }
        val stat = Os.lstat(directory.path)
        check(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Process.myUid() && (stat.st_mode and 511) == 448)
        val allowed = METHODS.flatMap { listOf("$it.png", "$it.accessibility.txt") }.toSet()
        check(directory.list()!!.all { it in allowed })
        val state = main {
            val timer = fixture.experience.timers?.states?.value
            "host=${owner.states.value.phase}; cooking=${fixture.experience.cooking.states.value.phase}; " +
                "screen=${fixture.experience.cooking.states.value.screen}; formBusy=${fixture.experience.forms.value.busy}; " +
                "timersVisible=${timer?.visible}; timerBusy=${timer?.busy}; pendingKind=${timer?.pendingAction?.kind}; " +
                "cleanupAcknowledged=${timer?.pendingAction?.cancellationAcknowledged}; issue=${timer?.issue}; failure=${timer?.failureReason}; " +
                "rows=${timer?.snapshot?.timers?.map { "${it.status}/${it.alertPhase}/${it.deliveryPhase}/ack=${it.alertAcknowledgedInThisOwner}" }}"
        }
        val text = buildString {
            appendLine("FAILURE-ONLY SYNTHETIC PROGRESS UI DIAGNOSTIC"); appendLine(state)
            for (node in nodes().take(256)) {
                append("visible=${node.isVisibleToUser}; enabled=${node.isEnabled}; clickable=${node.isClickable}; scrollable=${node.isScrollable}; ")
                appendLine(normalized(node.text)?.take(512) ?: normalized(node.contentDescription)?.take(512).orEmpty())
            }
        }.take(32_768)
        val description = File(directory, "$name.accessibility.txt")
        check(!description.exists() && description.createNewFile()); Os.chmod(description.path, 384)
        FileOutputStream(description).use { it.write(text.encodeToByteArray()); it.fd.sync() }
        val target = File(directory, "$name.png")
        check(!target.exists() && target.createNewFile()); Os.chmod(target.path, 384)
        val bitmap = checkNotNull(ui.takeScreenshot())
        try { FileOutputStream(target).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.fd.sync() } }
        finally { bitmap.recycle() }
    }
    private fun screenshot(name: String) {
        check(name in CAPTURES)
        instrumentation.waitForIdleSync(); SystemClock.sleep(650); instrumentation.waitForIdleSync()
        val base = context.cacheDir
        val baseStat = Os.lstat(base.path)
        check(OsConstants.S_ISDIR(baseStat.st_mode) && baseStat.st_uid == Process.myUid())
        val directory = File(base.canonicalFile, "progress-timer-ui-evidence")
        if (!captureClaimed) {
            check(!directory.exists()) { "Never overwrite another timer capture attempt" }
            check(directory.mkdir()); Os.chmod(directory.path, 448); captureClaimed = true
        }
        val stat = Os.lstat(directory.path)
        check(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Process.myUid() && (stat.st_mode and 511) == 448)
        check(directory.list()!!.all { it in CAPTURES.map { name -> "$name.png" } })
        val target = File(directory, "$name.png")
        check(!target.exists() && target.createNewFile()); Os.chmod(target.path, 384)
        val bitmap = checkNotNull(ui.takeScreenshot())
        try { FileOutputStream(target).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.fd.sync() } }
        finally { bitmap.recycle() }
    }
    private companion object {
        var retained: Any? = null
        var captureClaimed = false
        var diagnosticsClaimed = false
        val CAPTURES = setOf("progress-timer-running", "progress-timer-paused", "progress-timer-retained", "progress-timer-due")
        val METHODS = setOf("aVisibleControlsKeepTwoExactTimersAndRequireExplicitRemovalConsent",
            "bBackAndActivityRecreationRetainExactTimerOwnerWithoutPauseOrNewCommand",
            "cForegroundDueBannerIsNativeGatedAndDoesNotAdvanceOrCompleteCooking",
            "dStoppedActivityCannotDeliverInBackgroundButSameOwnerMayDeliverOnReturn")
    }
}
