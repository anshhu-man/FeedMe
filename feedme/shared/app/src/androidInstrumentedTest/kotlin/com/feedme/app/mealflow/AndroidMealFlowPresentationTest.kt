package com.feedme.app.mealflow

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feedme.mealflow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Compose layout/accessibility/events only. All model data remains synthetic test-only. */
@RunWith(AndroidJUnit4::class)
class AndroidMealFlowPresentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui get() = instrumentation.uiAutomation
    private fun launch() = ActivityScenario.launch<MealFlowUiTestActivity>(Intent(instrumentation.targetContext, MealFlowUiTestActivity::class.java))
    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = ui.rootInActiveWindow ?: return emptyList()
        val list = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) { list += node; for (i in 0 until node.childCount) node.getChild(i)?.let(::walk) }
        walk(root); return list
    }
    private fun textNodes(text: String) = nodes().filter { it.text?.toString()?.contains(text) == true || it.contentDescription?.toString()?.contains(text) == true }
    private fun scrollPage(forward: Boolean) {
        val bounds = Rect().also { ui.rootInActiveWindow?.getBoundsInScreen(it) }
        assertTrue("Visible fixture window", bounds.width() > 0 && bounds.height() > 0)
        val x = bounds.left + bounds.width() * 0.92f
        val from = bounds.top + bounds.height() * if (forward) 0.72f else 0.40f
        val to = bounds.top + bounds.height() * if (forward) 0.40f else 0.72f
        val start = SystemClock.uptimeMillis()
        fun event(action: Int, y: Float) {
            val motion = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
            motion.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(ui.injectInputEvent(motion, true)) } finally { motion.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, from)
        repeat(16) { index -> SystemClock.sleep(20); event(MotionEvent.ACTION_MOVE, from + (to - from) * (index + 1) / 16) }
        event(MotionEvent.ACTION_UP, to)
        SystemClock.sleep(150)
    }
    private fun waitText(text: String, scroll: Boolean = false) {
        val seen = linkedSetOf<String>()
        repeat(30) {
            instrumentation.waitForIdleSync()
            if (textNodes(text).any { it.isVisibleToUser }) return
            seen += nodes().filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() ?: it.contentDescription?.toString() }
            if (scroll) {
                // Overlapping user-sized gestures: a full accessibility page can skip a short row.
                scrollPage(forward = true)
            }
            SystemClock.sleep(100)
        }
        fail("Expected visible UI text: $text; observed synthetic fixture: ${seen.joinToString(" | ")}")
    }
    private fun click(text: String) {
        waitText(text, scroll = true)
        var node: AccessibilityNodeInfo? = textNodes(text).first { it.isVisibleToUser }
        while (node != null && !node.isClickable) node = node.parent
        assertNotNull("Clickable action for $text", node)
        assertTrue(node!!.isEnabled); assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun assertDisabled(text: String) {
        // Model mutation and an already-visible label do not prove that Compose has published
        // the new enabled semantics. Observe the nearest native action, not just its text.
        // Compose may export a button as android.view.View; never require a widget class.
        fun matches(node: AccessibilityNodeInfo) =
            node.text?.toString() == text || node.contentDescription?.toString() == text
        fun isAction(node: AccessibilityNodeInfo) = node.isClickable ||
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } ||
            node.className?.toString() == "android.widget.Button"
        val observations = ArrayDeque<String>()
        var previous: String? = null
        repeat(40) { attempt ->
            instrumentation.waitForIdleSync()
            val snapshot = nodes()
            val labels = snapshot.filter { it.isVisibleToUser && matches(it) }
            var disabled: String? = null
            for (label in labels) {
                val ancestry = mutableListOf<String>()
                var node: AccessibilityNodeInfo? = label
                var button: AccessibilityNodeInfo? = null
                var depth = 0
                while (node != null && depth++ < 64) {
                    val current = node
                    val bounds = Rect().also(current::getBoundsInScreen)
                    ancestry += "${current.className}: enabled=${current.isEnabled}, clickable=${current.isClickable}, visible=${current.isVisibleToUser}, bounds=${bounds.toShortString()}"
                    if (button == null && isAction(current)) button = current
                    node = current.parent
                }
                observations.addLast("poll $attempt: " + ancestry.joinToString(" <- "))
                while (observations.size > 12) observations.removeFirst()
                val candidate = button ?: continue
                if (label.refresh() && matches(label) && label.isVisibleToUser && candidate.refresh()) {
                    val bounds = Rect().also(candidate::getBoundsInScreen)
                    if (isAction(candidate) &&
                        candidate.isVisibleToUser && !candidate.isEnabled && !bounds.isEmpty) {
                        disabled = "${candidate.windowId}:${candidate.className}:${candidate.isClickable}:${bounds.toShortString()}"
                        break
                    }
                }
            }
            // Two fresh observations avoid accepting one transient/stale accessibility node.
            if (disabled != null && disabled == previous) return
            previous = disabled
            if (labels.isEmpty() && attempt % 3 == 2) scrollPage(forward = true)
            SystemClock.sleep(100)
        }
        val visible = nodes().filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() ?: it.contentDescription?.toString() }
        fail("$text must expose a visible disabled native action; ancestry: ${observations.joinToString(" | ")}; visible synthetic fixture: ${visible.joinToString(" | ")}")
    }
    private fun scrollToTop() {
        repeat(15) {
            if (textNodes("← Back").any { it.isVisibleToUser }) return
            scrollPage(forward = false)
        }
        waitText("← Back")
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        // Accessibility can publish the next state before its rendered frame/window transition.
        // Retain the settled native frame, not the preceding screen or launch fade.
        SystemClock.sleep(650)
        instrumentation.waitForIdleSync()
        val bitmap = ui.takeScreenshot() ?: error("Screenshot unavailable")
        val dir = File(instrumentation.targetContext.noBackupFilesDir, "meal-ui-evidence").also { check(it.mkdirs() || it.isDirectory) }
        File(dir, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    @Test fun requestShowsApprovedVisualControlsAndExplicitDraftAction() {
        launch().use { scenario ->
            waitText("What’s the"); screenshot("request")
            click("Save draft on this device")
            scenario.onActivity { assertEquals(listOf("save"), it.actionsSeen) }
            click("Find a meal")
            scenario.onActivity { assertEquals(listOf("save", "find"), it.actionsSeen) }
        }
    }
    @Test fun ingredientSelectionChangesOnlyTheExplicitForm() {
        launch().use { scenario ->
            waitText("What’s the")
            click("Synthetic cucumber")
            scenario.onActivity {
                assertEquals(listOf(MealFlowUiTestActivity.ingredient), it.form.value.values!!.ingredientIds)
                assertTrue(it.form.value.dirty); assertTrue(it.actionsSeen.isEmpty())
            }
        }
    }
    @Test fun recommendationsRouteRecipeExactlyOnceWithoutCookingOrSaving() {
        launch().use { scenario ->
            scenario.onActivity { it.meal.value = MealFlowUiTestActivity.testMeal(MealFlowScreen.RECOMMENDATIONS) }
            waitText("A meal that fits."); screenshot("recommendations")
            scenario.onActivity { it.form.value = MealFormState(it.form.value.values, true, null) }
            assertDisabled("View recipe"); assertDisabled("Show another option")
            scenario.onActivity { assertTrue(it.actionsSeen.isEmpty()); it.form.value = MealFormState(it.form.value.values, false, null) }
            scrollToTop()
            click("View recipe")
            scenario.onActivity { assertEquals(listOf("recipe"), it.actionsSeen); assertEquals(MealFlowScreen.RECIPE, it.meal.value.screen) }
        }
    }
    @Test fun recipeRendersExactAmountsAndMandatorySteps() {
        launch().use { scenario ->
            scenario.onActivity { it.meal.value = MealFlowUiTestActivity.testMeal(MealFlowScreen.RECIPE) }
            waitText("1.230000 cup", scroll = true); screenshot("recipe-ingredients")
            waitText("Required safety step", scroll = true); screenshot("recipe-safety-step")
            waitText("Suggested duration: 60 seconds · manage timers separately", scroll = true)
            scenario.onActivity { assertTrue(it.actionsSeen.isEmpty()) }
        }
    }
    @Test fun unavailableViewDoesNotExposeRetainedFixtureDetails() {
        launch().use { scenario ->
            scenario.onActivity {
                it.meal.value = MealFlowUiTestActivity.testMeal(MealFlowScreen.RECIPE, MealFlowPhase.UNAVAILABLE, MealFlowIssue.SESSION_UNAVAILABLE)
                it.form.value = MealFormState(null, false, com.feedme.core.ports.FailureReason.STALE_SESSION)
            }
            waitText("This kitchen is unavailable."); screenshot("unavailable")
            assertTrue(textNodes("Synthetic crunch bowl").isEmpty()); assertTrue(textNodes("Synthetic cucumber").isEmpty())
            click("← Back"); scenario.onActivity { assertEquals(listOf("back"), it.actionsSeen) }
        }
    }
    @Test fun uncertainRequestShowsOriginalRetryWithoutImplicitNewCommand() {
        launch().use { scenario ->
            scenario.onActivity { it.meal.value = MealFlowUiTestActivity.testMeal(MealFlowScreen.REQUEST, MealFlowPhase.RESOLVING, MealFlowIssue.REQUEST_UNRESOLVED) }
            waitText("original request and key", scroll = true)
            click("Retry original request")
            scenario.onActivity { assertEquals(listOf("retry"), it.actionsSeen) }
            scenario.onActivity { it.form.value = MealFormState(it.form.value.values, false, null, busy = true) }
            scrollToTop(); click("← Back")
            scenario.onActivity { assertEquals(listOf("retry", "back"), it.actionsSeen) }
        }
    }
}
