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
import com.feedme.core.ports.*
import com.feedme.mealflow.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Actual retained host + controller + public native session/store. Account verification and
 * transport are explicitly synthetic; these tests do not prove login, HTTP or recipe authority. */
@RunWith(AndroidJUnit4::class)
class AndroidMealFlowHostTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui get() = instrumentation.uiAutomation
    private fun <T> main(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main.immediate) { block() } }
    private inner class Fixture {
        val session = NativeMealFlowTestSession(instrumentation.targetContext)
        val transport = NativeMealFlowTestTransport(session)
        lateinit var owner: NativeMealFlowHostOwner
        lateinit var scenario: ActivityScenario<NativeMealFlowTestActivity>
        val experience get() = owner.experience
        suspend fun createExperience(): MealFlowExperience = MealFlowExperience.fromSession(
            checkNotNull(session.currentAccess()), transport, session.boundary, session.dispatcher, session.clock,
            ConnectivityPort { Connectivity.ONLINE }, MealOperationIds { UUID.randomUUID().toString() },
            MealFlowPolicy(86_400_000, 86_400_000, 5, 100), IngredientPickerPolicy(20, 3, 100, 86_400_000),
            MealInputChoices(listOf(MealInputChoice("bowl", "Synthetic bowl")), emptyList()), KitchenInputPolicy(20, 3, 100, 20, 65_536),
            CookingFlowPolicy(300_000, 65_536, 65_536), CookbookPolicy(20, 60_000))
        fun validEdit(servings: String = "1.5000") = main {
            experience.edit { it.copy(mode = MealMode.AUTO, energy = MealEnergy.ASSEMBLE,
                servings = servings, totalMinutes = "10", ingredientIds = listOf(NativeMealFlowTestTransport.INGREDIENT),
                equipmentIds = listOf("bowl")) }
        }
        fun recreate() { scenario.recreate(); settled() }
        fun settled() = await { main { !experience.forms.value.busy } }
    }
    private fun withHost(block: (Fixture) -> Unit) {
        val f = Fixture(); var opened = false; var launched = false; var failure: Throwable? = null
        try {
            main {
                check(retainedFixture == null) { "A failed native fixture still owns its resources" }
                retainedFixture = f // Retain before ANY native setup; preserve after failed cleanup.
                check(NativeMealFlowHostOwner.current == null)
                val openedResult = f.session.open()
                assertTrue("Native setup ${f.session.diagnosticStage}: $openedResult", openedResult is PortResult.Value)
                opened = true
                f.owner = NativeMealFlowHostOwner(f.createExperience()); NativeMealFlowHostOwner.current = f.owner
            }
            f.scenario = ActivityScenario.launch(Intent(instrumentation.targetContext, NativeMealFlowTestActivity::class.java))
            launched = true; waitText("What’s the"); f.settled(); block(f)
        } catch (t: Throwable) { failure = t; throw t }
        finally {
            try {
                if (launched) f.scenario.close()
                main {
                    NativeMealFlowHostOwner.current?.let { assertValue(it.experience.close()) }
                    assertValue(if (opened) f.session.retireAndClose() else f.session.close())
                    NativeMealFlowHostOwner.current = null
                    retainedFixture = null
                }
            } catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }
    private fun assertValue(result: PortResult<*>) = assertTrue("Acknowledged result required: $result", result is PortResult.Value)
    private fun await(test: () -> Boolean) {
        repeat(60) { instrumentation.waitForIdleSync(); if (test()) return; SystemClock.sleep(100) }
        fail("Timed out waiting for the explicitly requested host transition")
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
        fun normalized(value: CharSequence?) = value?.toString()?.replace(Regex("\\s+"), " ")
        normalized(it.text)?.contains(text) == true || normalized(it.contentDescription)?.contains(text) == true
    }
    private fun scroll(forward: Boolean) {
        val bounds = Rect().also { ui.rootInActiveWindow?.getBoundsInScreen(it) }
        check(bounds.width() > 0 && bounds.height() > 0)
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
        // This is a bounded drag, not a fling: stop the finger before release so inertia
        // cannot skip an entire control row between the next accessibility observations.
        repeat(8) { SystemClock.sleep(20); event(MotionEvent.ACTION_MOVE, to) }
        event(MotionEvent.ACTION_UP, to); SystemClock.sleep(150)
    }
    private fun top() {
        repeat(20) { if (textNodes("← Back").any { it.isVisibleToUser }) return; scroll(false) }
        waitText("← Back")
    }
    private fun waitText(text: String, scroll: Boolean = false) {
        val seen = linkedSetOf<String>()
        repeat(35) { attempt ->
            instrumentation.waitForIdleSync()
            if (textNodes(text).any { it.isVisibleToUser }) return
            seen += nodes().filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() ?: it.contentDescription?.toString() }
            // Do not swipe a transient opening/closing dialog before its accessible controls
            // attach: a gesture outside that modal can dismiss the action being tested.
            if (scroll && attempt >= 4) scroll(true)
            SystemClock.sleep(100)
        }
        screenshot("failure-" + text.filter(Char::isLetterOrDigit).take(40))
        fail("Expected visible synthetic host text: $text; observed test-only UI: ${seen.joinToString(" | ")}")
    }
    private fun click(text: String) {
        val observations = linkedSetOf<String>()
        var previousTarget: Triple<Int, String?, Rect>? = null
        try {
            repeat(40) { attempt ->
                instrumentation.waitForIdleSync()
                // Main-thread idle alone does not settle the asynchronous accessibility
                // event stream after Compose scroll/reflow. Bound that wait before reading.
                ui.waitForIdle(200, 1_000)
                // One fresh tree for acquisition: waitText followed by a second .first could
                // lose a reflowing node. Controller settlement is not rendered settlement.
                val label = textNodes(text).firstOrNull { it.isVisibleToUser }
                var node = label
                while (node != null && !node.isClickable) node = node.parent
                val action = node
                if (action != null) {
                    val bounds = Rect().also { action.getBoundsInScreen(it) }
                    observations += "visible=${action.isVisibleToUser}, enabled=${action.isEnabled}, bounds=$bounds"
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
                    observations += if (label == null) "No visible matching label"
                        else "Visible label without clickable ancestor; enabled=${label.isEnabled}"
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
        val dir = File(instrumentation.targetContext.noBackupFilesDir, "meal-host-ui-evidence").also { check(it.mkdirs() || it.isDirectory) }
        File(dir, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
    }

    @Test fun dirtyPartialTextSurvivesRecreationAndSystemBackDismissesOrDiscards() = withHost { f ->
        main { f.experience.edit { it.copy(servings = "0.") }; f.experience.searchText("synthetic private search") }
        f.recreate()
        main { assertEquals("0.", f.experience.forms.value.values!!.servings); assertTrue(f.experience.forms.value.dirty); assertTrue(f.transport.calls.isEmpty()) }
        back(); waitText("Keep your changes?"); screenshot("dirty-back")
        click("Keep editing"); await { textNodes("Keep your changes?").isEmpty() }; waitText("What’s the")
        main {
            assertEquals(0, f.owner.exitCount); assertEquals("0.", f.experience.forms.value.values!!.servings)
            assertTrue(f.experience.forms.value.dirty); assertTrue(f.transport.calls.isEmpty())
        }
        back(); waitText("Keep your changes?")
        click("Save draft and go back"); f.settled()
        main { assertEquals(0, f.owner.exitCount); assertTrue(f.experience.forms.value.dirty); assertEquals(FailureReason.INVALID_DATA, f.experience.forms.value.failure) }
        back(); await { textNodes("Keep your changes?").isEmpty() }; waitText("What’s the")
        main { assertEquals(0, f.owner.exitCount); assertEquals("0.", f.experience.forms.value.values!!.servings) }
        back(); click("Discard unsaved edits")
        await { main { f.owner.exitCount == 1 } }
        main { assertFalse(f.experience.forms.value.dirty); assertEquals("1", f.experience.forms.value.values!!.servings); assertTrue(f.transport.calls.isEmpty()) }
    }

    @Test fun saveAndBackAcknowledgesExactNativeDraftAndNewOwnerRestoresIt() = withHost { f ->
        f.validEdit(); back(); click("Save draft and go back")
        await { main { f.owner.exitCount == 1 && !f.experience.forms.value.busy } }
        main {
            assertFalse(f.experience.forms.value.dirty); assertTrue(f.transport.calls.isEmpty())
            assertValue(f.experience.close())
            f.owner = NativeMealFlowHostOwner(f.createExperience()); NativeMealFlowHostOwner.current = f.owner
        }
        f.recreate()
        main {
            assertEquals("1.5000", f.experience.forms.value.values!!.servings)
            assertEquals(listOf(NativeMealFlowTestTransport.INGREDIENT), f.experience.forms.value.values!!.ingredientIds)
            assertFalse(f.experience.forms.value.dirty); assertEquals(0, f.owner.exitCount); assertTrue(f.transport.calls.isEmpty())
        }
    }

    @Test fun actualPlanRecipeAndSystemBackStayOnExactCandidateAcrossRecreation() = withHost { f ->
        f.validEdit(); click("Find a meal")
        await { main { f.experience.meals.states.value.phase == MealFlowPhase.READY && !f.experience.forms.value.busy } }
        top(); waitText("A meal that fits."); screenshot("recommendations")
        val calls = main { f.transport.calls.size }
        click("View recipe"); await { main { f.experience.meals.states.value.screen == MealFlowScreen.RECIPE } }
        f.recreate()
        main { assertEquals(MealFlowScreen.RECIPE, f.experience.meals.states.value.screen); assertEquals(calls, f.transport.calls.size) }
        waitText("1.230000 g", true); screenshot("recipe-exact")
        back(); await { main { f.experience.meals.states.value.screen == MealFlowScreen.RECOMMENDATIONS } }
        main { assertEquals(NativeMealFlowTestTransport.PLAN, f.experience.meals.states.value.plan!!.plan.id.value) }
        back(); await { main { f.experience.meals.states.value.screen == MealFlowScreen.REQUEST } }
        main { assertEquals("1.5000", f.experience.forms.value.values!!.servings); assertEquals(calls, f.transport.calls.size); assertEquals(0, f.owner.exitCount) }
        back(); await { main { f.owner.exitCount == 1 } }
    }

    @Test fun unknownPlanOutcomeRetainsExactKeyAndBodyUntilExplicitUiRetry() = withHost { f ->
        f.validEdit(); main { f.transport.unknownNextPlan = true }; click("Find a meal")
        await { main { f.experience.meals.states.value.phase == MealFlowPhase.RESOLVING && !f.experience.forms.value.busy } }
        val first = main { f.transport.calls.single { it.operationId == "createPlan" } }
        f.recreate()
        main { assertEquals(1, f.transport.calls.count { it.operationId == "createPlan" }) }
        top(); click("Retry original request")
        await { main { f.experience.meals.states.value.phase == MealFlowPhase.READY && !f.experience.forms.value.busy } }
        main {
            val plans = f.transport.calls.filter { it.operationId == "createPlan" }; assertEquals(2, plans.size)
            assertEquals(first.idempotencyKey!!.use { it }, plans.last().idempotencyKey!!.use { it })
            assertArrayEquals(first.body!!.copyForCodec(), plans.last().body!!.copyForCodec())
            assertEquals(first.ifMatch, plans.last().ifMatch)
        }
    }

    @Test fun invalidatedRealSessionSynchronouslyRedactsAndLatePlanCannotReviveUi() = withHost { f ->
        f.validEdit(); val gate = CompletableDeferred<Unit>(); main { f.transport.planGate = gate }; click("Find a meal")
        await { main { f.transport.calls.any { it.operationId == "createPlan" } } }
        main {
            assertValue(f.session.invalidate()); assertNull(f.experience.forms.value.values)
            assertEquals(MealFlowPhase.UNAVAILABLE, f.experience.meals.states.value.phase)
            gate.complete(Unit)
        }
        top(); waitText("This kitchen is unavailable."); screenshot("unavailable")
        assertTrue(textNodes("Synthetic retained crunch bowl").isEmpty())
        main { assertNull(f.experience.forms.value.values); assertNull(f.experience.meals.states.value.plan) }
        back(); await { main { f.owner.exitCount == 1 } }
    }

    @Test fun recreationCancelsActiveUiRequestWithoutRotatingDurableRetryIntent() = withHost { f ->
        f.validEdit(); val gate = CompletableDeferred<Unit>(); main { f.transport.planGate = gate }; click("Find a meal")
        await { main { f.transport.calls.any { it.operationId == "createPlan" } } }
        val original = main { f.transport.calls.single { it.operationId == "createPlan" } }
        f.recreate()
        main { assertEquals(1, f.transport.calls.count { it.operationId == "createPlan" }); f.transport.planGate = null; gate.complete(Unit) }
        top(); click("Retry original request")
        await { main { f.experience.meals.states.value.phase == MealFlowPhase.READY && !f.experience.forms.value.busy } }
        main {
            val retry = f.transport.calls.last { it.operationId == "createPlan" }
            assertEquals(original.idempotencyKey!!.use { it }, retry.idempotencyKey!!.use { it })
            assertArrayEquals(original.body!!.copyForCodec(), retry.body!!.copyForCodec())
        }
    }

    @Test fun dirtyRecommendationSaveReturnsToRequestedDraftInsteadOfExitingJourney() = withHost { f ->
        f.validEdit(); click("Find a meal")
        await { main { f.experience.meals.states.value.screen == MealFlowScreen.RECOMMENDATIONS &&
            f.experience.meals.states.value.phase == MealFlowPhase.READY && !f.experience.forms.value.busy } }
        // This case starts from an acknowledged recommendation. An edit DURING its request
        // deliberately fences that old result and is covered independently below.
        main { f.experience.edit { it.copy(servings = "2.000") }; assertTrue(f.experience.forms.value.dirty) }
        back(); click("Save draft and go back"); f.settled()
        await { main { f.experience.meals.states.value.screen == MealFlowScreen.REQUEST } }
        main { assertEquals(0, f.owner.exitCount); assertFalse(f.experience.forms.value.dirty); assertEquals("2.000", f.experience.forms.value.values!!.servings) }
    }

    @Test fun editDuringPlanRequestRetainsNewerDirtyDraftAndExactOriginalWithoutStaleNavigation() = withHost { f ->
        f.validEdit()
        val gate = CompletableDeferred<Unit>()
        main { f.transport.planGate = gate }
        try {
            click("Find a meal")
            await { main { f.transport.calls.any { it.operationId == "createPlan" } } }
            val original = main { f.transport.calls.single { it.operationId == "createPlan" } }
            val before = main {
                assertTrue(f.transport.completedPlanCalls.isEmpty())
                val access = checkNotNull(f.session.currentAccess())
                val result = access.store.read(access.scope, RecordKey("mealflow.v1", access.originBinding.lowercase()))
                assertValue(result)
                checkNotNull((result as PortResult.Value).value)
            }
            val callsBeforeReply = main { f.transport.calls.size }
            main { f.experience.edit { it.copy(servings = "2.000") }; gate.complete(Unit) }
            // A released gate alone is not a returned response. The fixture records this exact
            // call only after constructing its reply, with no further await before return.
            await { main { f.transport.completedPlanCalls.singleOrNull() === original && !f.experience.forms.value.busy } }
            main {
                assertSame(original, f.transport.completedPlanCalls.single())
                val state = f.experience.meals.states.value
                assertEquals(MealFlowScreen.REQUEST, state.screen)
                assertEquals(MealFlowPhase.RESOLVING, state.phase)
                assertEquals(FailureReason.CONFLICT, state.failureReason)
                assertNull(state.plan); assertTrue(state.history.isEmpty())
                assertEquals("2.000", f.experience.forms.value.values!!.servings)
                assertTrue(f.experience.forms.value.dirty)
                assertEquals(FailureReason.CONFLICT, f.experience.forms.value.failure)
                assertEquals(0, f.owner.exitCount)
                assertEquals(callsBeforeReply, f.transport.calls.size)
                assertSame(original, f.transport.calls.single { it.operationId == "createPlan" })

                val access = checkNotNull(f.session.currentAccess())
                val result = access.store.read(access.scope, RecordKey("mealflow.v1", access.originBinding.lowercase()))
                assertValue(result)
                val after = checkNotNull((result as PortResult.Value).value)
                // Actual encrypted-store readback: no completion, replacement ID, retry, or
                // implicit save of the newer form changed the admitted original record.
                assertEquals(before.revision, after.revision)
                assertEquals(before.schemaVersion, after.schemaVersion)
                assertArrayEquals(before.payload.copyForCodec(), after.payload.copyForCodec())
                val command = Json.parseToJsonElement(after.payload.copyForCodec().decodeToString())
                    .jsonObject.getValue("command").jsonObject
                assertEquals(original.operationId, command.getValue("operation").jsonPrimitive.content)
                assertEquals(original.idempotencyKey!!.use { it }, command.getValue("id").jsonPrimitive.content)
                assertArrayEquals(original.body!!.copyForCodec(), command.getValue("body").jsonPrimitive.content.encodeToByteArray())
                assertEquals(1L, command.getValue("dispatches").jsonPrimitive.long)
            }
        } finally {
            main { gate.complete(Unit); f.transport.planGate = null }
        }
    }

    @Test fun actualPreferencePageRetainsDraftBlocksPlanningAndSavesOnlyOnExplicitAction() = withHost { f ->
        click("Food preferences"); top(); click("Load current preferences"); f.settled()
        main { assertNotNull(f.experience.kitchen.states.value.preferences); f.experience.searchText("Synthetic cucumber") }
        click("Search ingredients"); f.settled(); click("Hard exclude"); f.settled()
        main { assertNotNull(f.experience.kitchen.states.value.preferenceDraft); assertTrue(f.experience.kitchen.states.value.preferencesPending) }
        val calls = main { f.transport.calls.size }; f.recreate()
        main { assertEquals(KitchenInputPage.PREFERENCES, f.experience.kitchenPage.value); assertEquals(calls, f.transport.calls.size) }
        top(); screenshot("preferences-draft"); back()
        f.validEdit(); top(); click("Find a meal"); f.settled()
        main { assertTrue(f.transport.calls.none { it.operationId == "createPlan" }); assertTrue(f.experience.kitchen.states.value.preferencesPending) }
        top(); click("Food preferences"); click("Save preference change"); f.settled()
        main {
            val saved = f.experience.kitchen.states.value
            assertNull(saved.preferenceDraft); assertFalse(saved.preferencesPending); assertTrue(saved.pending.isEmpty())
            val preference = Json.parseToJsonElement(saved.preferences!!.encodeUtf8().decodeToString()).jsonObject
            assertEquals(listOf(NativeMealFlowTestTransport.INGREDIENT), preference.getValue("hardExcludedIngredientIds").jsonArray.map { it.jsonPrimitive.content })
            assertTrue(preference.getValue("dislikedIngredientIds").jsonArray.isEmpty())
            assertEquals(listOf(NativeMealFlowTestTransport.INGREDIENT), f.experience.forms.value.values!!.ingredientIds)
            assertEquals(1, f.transport.calls.count { it.operationId == "updatePreferences" })
            assertTrue(f.transport.calls.none { it.operationId == "createPlan" })
        }
    }

    @Test fun actualPantryPageSavesRoughReportAndConfirmsRemovalWithoutSelectingMealIngredients() = withHost { f ->
        click("Edit pantry"); top(); click("Load current pantry"); f.settled()
        main { f.experience.searchText("Synthetic cucumber") }
        click("Search ingredients"); f.settled(); click("Usually have · still uncertain"); f.settled()
        main { assertEquals(1, f.experience.kitchen.states.value.pantryDrafts.size); assertTrue(f.transport.calls.none { it.operationId == "upsertPantryItem" }) }
        top(); click("Save pantry change"); f.settled()
        main {
            assertEquals(1, f.experience.kitchen.states.value.pantryItems.size); assertTrue(f.experience.kitchen.states.value.pending.isEmpty())
            val item = Json.parseToJsonElement(f.experience.kitchen.states.value.pantryItems.single().encodeUtf8().decodeToString()).jsonObject
            assertEquals(JsonPrimitive("usuallyHave"), item["presence"]); assertEquals(JsonPrimitive("usual"), item["confirmationStatus"])
            assertEquals(JsonNull, item["confirmedAt"]); assertEquals(JsonPrimitive(false), item["staple"])
            assertTrue(f.experience.forms.value.values!!.ingredientIds.isEmpty())
            assertEquals(1, f.transport.calls.count { it.operationId == "upsertPantryItem" })
        }
        top(); screenshot("pantry-saved"); click("Remove pantry item…"); click("Keep pantry item")
        main { assertTrue(f.transport.calls.none { it.operationId == "removePantryItem" }) }
        click("Remove pantry item…"); click("Confirm pantry removal"); f.settled()
        main {
            assertTrue(f.experience.kitchen.states.value.pantryItems.isEmpty()); assertTrue(f.experience.kitchen.states.value.pending.isEmpty())
            assertEquals(1, f.transport.calls.count { it.operationId == "removePantryItem" }); assertTrue(f.experience.forms.value.values!!.ingredientIds.isEmpty())
        }
    }

    companion object { private var retainedFixture: Any? = null }
}
