package com.feedme.app.mealflow

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.kitchen.CookingStatus
import com.feedme.mealflow.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real fromSession host, native encrypted owner, controller and queue. The account verifier and
 * canonical service replies are SYNTHETIC, not provider/HTTP/reviewer/manifest acceptance. */
@RunWith(AndroidJUnit4::class)
class AndroidCookingFlowHostTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui get() = instrumentation.uiAutomation
    private fun <T> main(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main.immediate) { block() } }
    private inner class Fixture {
        val session = NativeMealFlowTestSession(instrumentation.targetContext)
        val transport = NativeCookingFlowTestTransport(session)
        var now = 1_850_000_000_000L
        var online = true
        lateinit var owner: NativeMealFlowHostOwner
        lateinit var scenario: ActivityScenario<NativeMealFlowTestActivity>
        val experience get() = owner.experience
        suspend fun createExperience() = MealFlowExperience.fromSession(checkNotNull(session.currentAccess()), transport,
            session.boundary, session.dispatcher, EpochClock { now }, ConnectivityPort { if (online) Connectivity.ONLINE else Connectivity.OFFLINE },
            MealOperationIds { UUID.randomUUID().toString() }, MealFlowPolicy(86_400_000, 86_400_000, 5, 100),
            IngredientPickerPolicy(20, 3, 100, 86_400_000), MealInputChoices(listOf(MealInputChoice("bowl", "Synthetic bowl")), emptyList()),
            KitchenInputPolicy(20, 3, 100, 20, 65_536), CookingFlowPolicy(300_000, 65_536, 65_536), CookbookPolicy(20, 60_000))
        fun meal(servings: String = "1.5000") {
            main {
                experience.edit { it.copy(mode = MealMode.AUTO, energy = MealEnergy.ASSEMBLE, servings = servings,
                    totalMinutes = "10", ingredientIds = listOf(NativeMealFlowTestTransport.INGREDIENT), equipmentIds = listOf("bowl")) }
                value(experience.findMeal()); value(experience.recipe())
            }; settled()
        }
        fun start() { meal(); click("Cook this plan"); waitText("Start this exact plan?"); click("Confirm cooking start"); settled()
            await { main { experience.cooking.states.value.phase == CookingFlowPhase.COOKING } } }
        fun settled() = await { main { !experience.forms.value.busy } }
        fun recreate() { scenario.recreate(); settled() }
    }
    private fun withHost(block: (Fixture) -> Unit) {
        val f = Fixture(); var opened = false; var launched = false; var failure: Throwable? = null
        try {
            main {
                check(retainedFixture == null && NativeMealFlowHostOwner.current == null)
                retainedFixture = f // Retain before native I/O. Never hide failed native cleanup.
                value(f.session.open()); opened = true
                f.owner = NativeMealFlowHostOwner(f.createExperience()); NativeMealFlowHostOwner.current = f.owner
            }
            f.scenario = ActivityScenario.launch(Intent(instrumentation.targetContext, NativeMealFlowTestActivity::class.java))
            launched = true; waitText("What’s the"); f.settled(); block(f)
        } catch (t: Throwable) { failure = t; throw t }
        finally {
            try {
                if (launched) f.scenario.close()
                main {
                    NativeMealFlowHostOwner.current?.let { value(it.experience.close()) }
                    value(if (opened) f.session.retireAndClose() else f.session.close())
                    NativeMealFlowHostOwner.current = null; retainedFixture = null
                }
            } catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }
    private fun value(result: PortResult<*>) { assertTrue("Acknowledged result required: $result", result is PortResult.Value) }
    private fun failure(result: PortResult<*>, reason: FailureReason) { assertTrue(result is PortResult.Failure); assertEquals(reason, (result as PortResult.Failure).reason) }
    private fun await(failureMessage: () -> String = { "Timed out waiting for synthetic cooking host transition" }, test: () -> Boolean) {
        repeat(60) { instrumentation.waitForIdleSync(); if (test()) return; SystemClock.sleep(100) }
        fail(failureMessage())
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        // A new root lookup can still reuse cached descendants after Compose scroll/reflow.
        // Event-stream quietness is not cache invalidation; require an actual fresh snapshot.
        if (Build.VERSION.SDK_INT >= 34) assertTrue("Accessibility cache cleared", ui.clearCache())
        val root = ui.rootInActiveWindow ?: return emptyList(); val found = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) { found += node; for (i in 0 until node.childCount) node.getChild(i)?.let(::walk) }
        walk(root); return found
    }
    private fun textNodes(text: String) = nodes().filter {
        fun normal(value: CharSequence?) = value?.toString()?.replace(Regex("\\s+"), " ")
        normal(it.text)?.contains(text) == true || normal(it.contentDescription)?.contains(text) == true
    }
    private fun scroll(forward: Boolean) {
        val bounds = Rect().also { ui.rootInActiveWindow?.getBoundsInScreen(it) }; check(bounds.width() > 0 && bounds.height() > 0)
        val x = bounds.left + bounds.width() * 0.92f
        val from = bounds.top + bounds.height() * if (forward) 0.72f else 0.40f
        val to = bounds.top + bounds.height() * if (forward) 0.40f else 0.72f
        val start = SystemClock.uptimeMillis()
        fun event(action: Int, y: Float) {
            val e = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0).also { it.source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(ui.injectInputEvent(e, true)) } finally { e.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, from)
        repeat(16) { i -> SystemClock.sleep(20); event(MotionEvent.ACTION_MOVE, from + (to - from) * (i + 1) / 16) }
        event(MotionEvent.ACTION_UP, to); SystemClock.sleep(150)
    }
    private fun top() { repeat(24) { if (textNodes("← Back").any { it.isVisibleToUser }) return; scroll(false) }; waitText("← Back") }
    private fun waitText(text: String, scroll: Boolean = false) {
        val seen = linkedSetOf<String>()
        repeat(40) { attempt ->
            instrumentation.waitForIdleSync(); if (textNodes(text).any { it.isVisibleToUser }) return
            seen += nodes().filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() ?: it.contentDescription?.toString() }
            if (scroll && attempt >= 4) scroll(true)
            SystemClock.sleep(100)
        }
        screenshot("failure-" + text.filter(Char::isLetterOrDigit).take(40))
        fail("Missing synthetic cooking text: $text; observed: ${seen.joinToString(" | ")}")
    }
    private fun click(text: String) {
        val observations = linkedSetOf<String>()
        var previousTarget: Triple<Int, String?, Rect>? = null
        try {
            repeat(40) { attempt ->
                instrumentation.waitForIdleSync()
                // One fresh tree for acquisition: waitText followed by a second .first could
                // lose a reflowing node. Controller settlement is not rendered settlement.
                val label = textNodes(text).firstOrNull { it.isVisibleToUser }
                var node = label
                while (node != null && !node.isClickable) node = node.parent
                val action = node
                if (action != null) {
                    val bounds = Rect().also { action.getBoundsInScreen(it) }
                    observations += "attempt=$attempt, visible=${action.isVisibleToUser}, enabled=${action.isEnabled}, bounds=$bounds"
                    if (action.isVisibleToUser && action.isClickable && action.isEnabled && !bounds.isEmpty) {
                        val target = Triple(action.windowId, action.className?.toString(), bounds)
                        if (target == previousTarget) {
                            // Use this iteration's fresh node, never the earlier snapshot.
                            // Once submitted, even a false return is a failure, not retry permission.
                            assertTrue("Enabled action required: $text", action.isEnabled)
                            assertTrue("Native ACTION_CLICK accepted: $text",
                                action.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                            instrumentation.waitForIdleSync()
                            return
                        }
                        previousTarget = target
                    } else previousTarget = null
                } else {
                    observations += if (label == null) "attempt=$attempt, No visible matching label"
                        else "attempt=$attempt, Visible label without clickable ancestor; enabled=${label.isEnabled}"
                    previousTarget = null
                    if (label == null && attempt >= 4) scroll(true)
                }
                SystemClock.sleep(100)
            }
            fail("No stable visible enabled action: $text; observed: ${observations.joinToString(" | ")}")
        } catch (failure: Throwable) {
            try { screenshot("failure-click-" + text.filter(Char::isLetterOrDigit).take(40)) }
            catch (capture: Throwable) { failure.addSuppressed(capture) }
            throw failure
        }
    }
    private fun back() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); instrumentation.waitForIdleSync() }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); SystemClock.sleep(650); instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(ui.takeScreenshot())
        val directory = File(instrumentation.targetContext.noBackupFilesDir, "cooking-host-ui-evidence").also { check(it.mkdirs() || it.isDirectory) }
        File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
    }

    @Test fun exactPreparedConsentSurvivesRecreationAndBackNeverStartsCooking() = withHost { f ->
        f.meal(); click("Cook this plan"); waitText("Start this exact plan?"); screenshot("cooking-confirmation")
        val ticket = main { checkNotNull(f.experience.cookingNavigation.value.confirmation) }
        main { assertTrue(f.transport.mutations().isEmpty()); assertEquals(f.experience.meals.states.value.plan!!.plan.id.value, ticket.planId) }
        f.recreate(); waitText("Start this exact plan?")
        back(); await { textNodes("Start this exact plan?").isEmpty() }
        main { assertTrue(f.transport.mutations().isEmpty()); assertNull(f.experience.cookingNavigation.value.confirmation) }
        click("Review prepared start"); click("Confirm cooking start"); f.settled()
        main {
            assertEquals(CookingFlowPhase.COOKING, f.experience.cooking.states.value.phase)
            val call = f.transport.mutations().single(); assertEquals("createCookSession", call.operationId); assertNull(call.ifMatch)
            assertEquals(ticket.planId, NativeCookingFlowTestTransport.json(call.body!!).getValue("planId").toString().trim('"'))
            failure(f.experience.confirmCooking(ticket), FailureReason.CONFLICT)
        }
        top(); waitText("One step at a time."); screenshot("cooking-progress")
    }

    @Test fun dirtyFormAndStaleDialogCannotConfirmAnotherPlanOrPriorCookingPin() = withHost { f ->
        f.start(); val first = main { f.experience.cooking.states.value.cooking!!.plan.id }
        main { f.experience.leaveCooking(); value(f.experience.back()); value(f.experience.back()) }; f.meal("2.000")
        main { f.experience.edit { it.copy(servings = "3") }; failure(f.experience.prepareCooking(), FailureReason.CONFLICT)
            f.experience.discardUnsavedEdits() }
        click("Cook this plan"); waitText("Start this exact plan?"); waitText("Synthetic cooking bowl B")
        val old = main { checkNotNull(f.experience.cookingNavigation.value.confirmation).also {
            assertNotEquals(first.value, it.planId); assertEquals(first, f.experience.cooking.states.value.cooking!!.plan.id)
        } }
        main { f.experience.dismissCookingConfirmation() }
        // Observe dismissal before presenting an identical-text dialog. A callback still
        // composed for the old ticket must remain rejected, never silently upgraded.
        await { textNodes("Start this exact plan?").isEmpty() }
        click("Review prepared start"); waitText("Start this exact plan?"); waitText("Synthetic cooking bowl B")
        main { assertNotSame(old, f.experience.cookingNavigation.value.confirmation)
            failure(f.experience.confirmCooking(old), FailureReason.CONFLICT); assertEquals(1, f.transport.mutations().size) }
        click("Confirm cooking start")
        await(failureMessage = { main { "Expected synthetic B selected after new consent with exactly two creates; " +
            "phase=${f.experience.cooking.states.value.phase}, failure=${f.experience.cooking.states.value.failureReason}, " +
            "formFailure=${f.experience.forms.value.failure}, creates=${f.transport.mutations().size}" } }) {
            main { f.experience.cooking.states.value.cooking?.plan?.id?.value == old.planId &&
                f.experience.cooking.states.value.phase == CookingFlowPhase.COOKING &&
                f.transport.mutations().size == 2 && !f.experience.forms.value.busy }
        }
        main { assertEquals(old.planId, f.experience.cooking.states.value.cooking!!.plan.id.value); assertEquals(2, f.transport.mutations().size) }
    }

    @Test fun lostCreateReplyKeepsOriginalBodyKeyAndExplicitRetryAcrossRecreation() = withHost { f ->
        f.meal(); main { f.transport.unknownCreate = true }; click("Cook this plan"); click("Confirm cooking start"); f.settled()
        val original = main { f.transport.mutations().single() }
        f.recreate(); main { assertEquals(1, f.transport.mutations().size); assertNull(f.experience.cooking.states.value.cooking)
            failure(f.experience.discardCookingStart(), FailureReason.CONFLICT); f.now += 60_000 }
        top(); click("Retry original cooking start"); f.settled()
        main {
            val retry = f.transport.mutations().last(); assertEquals(2, f.transport.mutations().size)
            assertEquals(original.idempotencyKey!!.use { it }, retry.idempotencyKey!!.use { it })
            assertArrayEquals(original.body!!.copyForCodec(), retry.body!!.copyForCodec()); assertEquals(CookingFlowPhase.COOKING, f.experience.cooking.states.value.phase)
        }
    }

    @Test fun retainedCreatedReceiptRetriesDownloadWithoutSecondPost() = withHost { f ->
        f.meal(); main { f.transport.failDownload = true }; click("Cook this plan"); click("Confirm cooking start"); f.settled()
        main { assertNull(f.experience.cooking.states.value.cooking); assertEquals(1, f.transport.mutations().size) }
        top(); waitText("Action not acknowledged"); screenshot("cooking-pending")
        f.recreate(); main { f.transport.failDownload = false }
        top(); click("Retry original cooking start"); f.settled()
        main { assertEquals(1, f.transport.mutations().size); assertEquals(CookingFlowPhase.COOKING, f.experience.cooking.states.value.phase) }
    }

    @Test fun explicitOfflineProgressAndCompletionUseHeadOnlySyncAndNeverAutoFinishLastStep() = withHost { f ->
        f.start(); main { f.online = false; assertTrue(f.experience.cooking.states.value.canEdit) }
        click("Mark this step complete"); f.settled()
        main {
            val current = f.experience.cooking.states.value
            assertTrue("first" in current.cooking!!.progress.completedStepIds)
            assertEquals("first", current.cooking!!.progress.currentStepId)
            assertTrue(current.canEdit)
        }
        click("Step 2"); f.settled()
        main { assertEquals("second", f.experience.cooking.states.value.cooking!!.progress.currentStepId)
            assertEquals(CookingStatus.ACTIVE, f.experience.cooking.states.value.cooking!!.progress.status); assertEquals(1, f.transport.mutations().size) }
        top(); click("Finish cooking"); waitText("Are you done cooking?"); back()
        main { assertEquals(CookingStatus.ACTIVE, f.experience.cooking.states.value.cooking!!.progress.status) }
        click("Finish cooking"); click("Confirm completion"); f.settled()
        top(); waitText("Done, on your terms."); waitText("Finished on this device", true); screenshot("cooking-done-pending")
        main { assertEquals(3, f.experience.cooking.states.value.cooking!!.pendingCommandIds.size); assertEquals(1, f.transport.mutations().size); f.online = true }
        repeat(3) { top(); click("Sync this cooking session"); f.settled() }
        main {
            val calls = f.transport.mutations(); assertEquals(listOf("createCookSession", "updateCookSession", "updateCookSession", "completeCookSession"), calls.map { it.operationId })
            assertEquals("\"1\"", calls[1].ifMatch); assertEquals("\"2\"", calls[2].ifMatch); assertNull(calls[3].ifMatch)
            assertEquals("false", NativeCookingFlowTestTransport.json(calls[3].body!!).getValue("makeAgain").toString())
        }
        top(); waitText("Completion acknowledged by the server", true); screenshot("cooking-done-acknowledged")
    }

    @Test fun pendingStricterPreferencesStopContinuedCookingButPermitExplicitSafeStop() = withHost { f ->
        f.start(); main {
            value(f.experience.editKitchenPreferences(WireDocument.parse("""{"hardExcludedIngredientIds":["${NativeMealFlowTestTransport.INGREDIENT}"]}""")))
            failure(f.experience.moveCooking("second"), FailureReason.CONFLICT)
            assertFalse(f.experience.cooking.states.value.canEdit); assertFalse(f.experience.cooking.states.value.canComplete)
        }
        top(); click("Pause cooking"); f.settled()
        main {
            val current = f.experience.cooking.states.value
            assertEquals(CookingStatus.PAUSED, current.cooking!!.progress.status)
            assertTrue("Safe stop remains permitted with pending preferences", current.canStop)
            assertFalse(current.canEdit); assertFalse(current.canComplete)
        }
        click("Stop this cooking session"); waitText("Stop this session?"); back()
        main { assertEquals(CookingStatus.PAUSED, f.experience.cooking.states.value.cooking!!.progress.status) }
        click("Stop this cooking session"); click("Confirm stop"); f.settled()
        main { assertEquals(CookingStatus.ABANDONED, f.experience.cooking.states.value.cooking!!.progress.status)
            assertEquals(1, f.transport.mutations().size); assertTrue(f.experience.kitchen.states.value.preferencesPending) }
    }

    @Test fun invalidationRedactsConsentAndNoncooperativeCreateCannotRevivePrivateScreen() = withHost { f ->
        f.meal(); click("Cook this plan"); val gate = CompletableDeferred<Unit>()
        main { f.transport.createGate = gate }; click("Confirm cooking start")
        await { main { f.transport.mutations().isNotEmpty() } }
        main { value(f.session.invalidate()); assertNull(f.experience.cooking.states.value.plan)
            assertNull(f.experience.cookingNavigation.value.confirmation); assertFalse(f.experience.cookingNavigation.value.visible); gate.complete(Unit) }
        top(); waitText("This kitchen is unavailable."); screenshot("cooking-unavailable")
        main { assertNull(f.experience.forms.value.values); assertNull(f.experience.cooking.states.value.cooking) }
        assertTrue(textNodes("Synthetic cooking bowl A").isEmpty())
    }

    @Test fun missingProviderAndExactRecallNeverCreateOrRetainEligiblePreview() = withHost { f ->
        f.meal(); main { f.transport.unavailablePlan = true; assertTrue(f.experience.prepareCooking() is PortResult.Failure)
            assertTrue(f.transport.mutations().isEmpty()); f.transport.unavailablePlan = false
            value(f.experience.prepareCooking()); f.transport.recalledPlan = f.experience.cooking.states.value.plan!!.id.value }
        waitText("Start this exact plan?"); click("Confirm cooking start"); f.settled()
        main { assertNull(f.experience.cooking.states.value.plan); assertTrue(f.transport.mutations().isEmpty()); assertFalse(f.experience.cooking.states.value.canEdit) }
        top(); waitText("Instructions unavailable", true)
        assertTrue(textNodes("Synthetic required instruction").isEmpty())
    }

    @Test fun immediateBackDuringDownloadAndReopenedOwnerKeepOriginalReceiptAndPinnedRecipe() = withHost { f ->
        f.meal(); val gate = CompletableDeferred<Unit>(); main { f.transport.downloadGate = gate }
        click("Cook this plan"); click("Confirm cooking start")
        await { main { f.transport.calls.any { it.operationId == "getCookSession" } } }
        back(); main { assertFalse(f.experience.cookingNavigation.value.visible); f.transport.downloadGate = null; gate.complete(Unit) }
        f.settled(); main { assertFalse(f.experience.cookingNavigation.value.visible); assertEquals(1, f.transport.mutations().size)
            f.experience.showCooking(); value(f.experience.retryCookingStart()) }
        main { assertEquals(1, f.transport.mutations().size) }
        back(); await { main { f.experience.cooking.states.value.screen == CookingFlowScreen.RECIPE } }
        top(); waitText("Your session’s recipe"); screenshot("cooking-retained-recipe")
        val id = main { f.experience.cooking.states.value.cooking!!.id }
        main { value(f.experience.close()); f.owner = NativeMealFlowHostOwner(f.createExperience()); NativeMealFlowHostOwner.current = f.owner }
        f.recreate(); main { assertEquals(id, f.experience.cooking.states.value.cooking!!.id); assertTrue(f.experience.cooking.states.value.historical)
            assertFalse(f.experience.cooking.states.value.serverAcknowledged); assertEquals(1, f.transport.mutations().size); f.experience.showCooking() }
        top(); waitText("Historical observation", true)
    }

    companion object { private var retainedFixture: Any? = null }
}
