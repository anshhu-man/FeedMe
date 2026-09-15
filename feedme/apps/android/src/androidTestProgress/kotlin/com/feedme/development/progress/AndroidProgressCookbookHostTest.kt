package com.feedme.development.progress

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feedme.app.mealflow.MealFlowExperience
import com.feedme.contracts.WireField
import com.feedme.core.ports.*
import com.feedme.kitchen.SavedRecipeAvailability
import com.feedme.mealflow.*
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Actual progress Application/owner/native storage/service ledger/Compose. Content and identity
 * remain explicitly synthetic. Run after the six-case host suite on an owned progress UID. Each
 * method proves existing SIGNED_OUT before Start; never clear, replace or reset unknown state.
 * No AndroidTest production imports, alternate queue, forged private access or live-provider claim.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AndroidProgressCookbookHostTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val owner get() = (context.applicationContext as ProgressApplication).owner
    private val ui get() = instrumentation.uiAutomation
    private fun <T> main(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main.immediate) { block() } }
    private fun <T> value(result: PortResult<T>): T = when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> error("Acknowledged progress cookbook action required: ${result.reason}")
    }
    private inner class Fixture {
        var admitted = false; var created = false
        var scenario: ActivityScenario<ProgressActivity>? = null
        lateinit var experience: MealFlowExperience
        fun open() {
            main {
                check(retained == null); check(Build.VERSION.SDK_INT >= 27)
                assertEquals(ProgressNativeInventory.PACKAGE, context.packageName)
                check(owner.states.value.phase in setOf(ProgressHostPhase.NEW, ProgressHostPhase.CLOSED))
                assertEquals(ProgressInventoryKind.EXISTING, value(ProgressNativeInventory.inspect(context)))
                retained = this@Fixture; admitted = true
                value(owner.inspectStartup()); assertEquals(ProgressHostPhase.RESUME_AVAILABLE, owner.states.value.phase)
                value(owner.resume()); assertEquals(ProgressHostPhase.START_AVAILABLE, owner.states.value.phase)
                assertNull(owner.boundary.current()); assertNull(owner.experience.value)
            }
            scenario = ActivityScenario.launch(Intent(context, ProgressActivity::class.java))
            click("Start preview")
            await { main { owner.states.value.phase == ProgressHostPhase.ACTIVE && owner.experience.value != null } }
            main { created = true; experience = checkNotNull(owner.experience.value) }
            waitText("What’s the"); settled()
            main {
                value(experience.refreshContext())
                experience.edit { it.copy(mode = MealMode.ASSEMBLE, energy = MealEnergy.ASSEMBLE, servings = "1",
                    totalMinutes = "5", ingredientIds = listOf(ProgressCatalog.ingredientId), equipmentIds = listOf("bowl"),
                    tasteTags = listOf("crunch")) }
                value(experience.findMeal()); value(experience.recipe())
            }
            waitText("Save this plan to cookbook", scroll = true)
        }
        fun settled() = await { main { !experience.forms.value.busy } }
        fun save(): String {
            click("Save this plan to cookbook")
            await { main { !experience.forms.value.busy && experience.cookbook.states.value.serverAcknowledged &&
                experience.cookbook.states.value.selected?.localRevision != null && experience.cookbook.states.value.pending == null } }
            waitText("Your saved copy.")
            return main { checkNotNull(experience.cookbook.states.value.selected).id }
        }
        fun close() {
            scenario?.close(); scenario = null
            main {
                if (created) {
                    assertSame(experience, owner.experience.value); check(owner.states.value.canReset)
                    owner.requestReset(); val consent = checkNotNull(owner.states.value.confirmation)
                    assertEquals(ProgressConfirmationKind.RESET, consent.kind)
                    value(owner.confirmReset(consent)); assertEquals(ProgressHostPhase.CLOSED, owner.states.value.phase)
                    assertNull(owner.boundary.current()); assertNull(owner.experience.value); assertNull(experience.forms.value.values)
                    value(owner.inspectStartup()); assertEquals(ProgressHostPhase.RESUME_AVAILABLE, owner.states.value.phase)
                    value(owner.resume()); assertEquals(ProgressHostPhase.START_AVAILABLE, owner.states.value.phase)
                    assertNull(owner.boundary.current()); value(owner.close())
                } else if (admitted) value(owner.close())
                if (admitted) retained = null
            }
        }
    }
    private fun withHost(block: (Fixture) -> Unit) {
        val f = Fixture(); var failure: Throwable? = null
        try { f.open(); block(f) } catch (error: Throwable) {
            failure = error
            try { failureDiagnostics() } catch (diagnosticFailure: Throwable) { error.addSuppressed(diagnosticFailure) }
            throw error
        }
        finally { try { f.close() } catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup } }
    }

    @Test fun aExplicitSaveShowsExactMaterializedRecipeThenDistinctRemotePage() = withHost { f ->
        val plan = main { checkNotNull(f.experience.meals.states.value.plan).plan }
        val id = f.save()
        main {
            val saved = checkNotNull(f.experience.cookbook.states.value.selected).savedRecipe!!
            val recipe = (plan.recipeSnapshot as WireField.Value).value
            assertArrayEquals(recipe.document.encodeUtf8(), saved.snapshot.document.encodeUtf8())
            assertEquals("ownPlan", saved.sourceType); assertNull(f.experience.cookbook.states.value.pending)
        }
        screenshot("progress-cookbook-saved")
        back(); waitText("Good meals, kept close.")
        click("Search my cookbook online")
        await { main { !f.experience.forms.value.busy && !f.experience.cookbook.states.value.localOnly &&
            f.experience.cookbook.states.value.items.any { it.id == id } } }
        main { assertNull(f.experience.cookbook.states.value.items.single { it.id == id }.localRevision) }
        waitText("Server observation", scroll = true); screenshot("progress-cookbook-page")
        click(main { f.experience.cookbook.states.value.items.single { it.id == id }.savedRecipe!!.title })
        await { main { f.experience.cookbook.states.value.selected?.id == id && f.experience.cookbook.states.value.screen == CookbookScreen.DETAIL } }
        assertNotNull(main { f.experience.cookbook.states.value.selected!!.localRevision })
    }
    @Test fun bActivityRecreationAndAcknowledgedNativeCloseResumeRetainSameDownloadedCopy() = withHost { f ->
        val id = f.save(); val retainedExperience = f.experience
        f.scenario!!.recreate(); f.settled()
        assertSame(retainedExperience, main { owner.experience.value })
        assertEquals(id, main { f.experience.cookbook.states.value.selected!!.id })
        f.scenario!!.close(); f.scenario = null
        main {
            value(owner.close()); assertEquals(ProgressHostPhase.CLOSED, owner.states.value.phase)
            assertNull(retainedExperience.forms.value.values)
            value(owner.inspectStartup()); assertEquals(ProgressHostPhase.RESUME_AVAILABLE, owner.states.value.phase)
            value(owner.resume()); assertEquals(ProgressHostPhase.ACTIVE, owner.states.value.phase)
            f.experience = checkNotNull(owner.experience.value); assertNotSame(retainedExperience, f.experience)
            value(f.experience.restore()); value(f.experience.openCookbook())
            assertTrue(f.experience.cookbook.states.value.items.any { it.id == id && it.localRevision != null })
            assertFalse(f.experience.cookbook.states.value.serverAcknowledged)
            value(f.experience.openSavedRecipe(id))
        }
        f.scenario = ActivityScenario.launch(Intent(context, ProgressActivity::class.java)); waitText("Your saved copy.")
        assertEquals(id, main { f.experience.cookbook.states.value.selected!!.id })
        screenshot("progress-cookbook-restored")
    }
    @Test fun cExactRemovalConsentDoesNotEraseExistingCookingPin() = withHost { f ->
        val id = f.save(); back(); back(); waitText("Cook this plan", scroll = true)
        click("Cook this plan"); waitText("Start this exact plan?"); click("Confirm cooking start")
        await { main { f.experience.cooking.states.value.phase == CookingFlowPhase.COOKING && !f.experience.forms.value.busy } }
        val pin = main { checkNotNull(f.experience.cooking.states.value.cooking) }
        click("Open my cookbook")
        await { main { f.experience.cookbook.states.value.screen == CookbookScreen.LIST } }
        main { value(f.experience.openSavedRecipe(id)) }
        click("Review removal from cookbook"); waitText("Remove this saved copy?")
        val old = main { checkNotNull(f.experience.cookbook.states.value.deleteConfirmation) }
        click("Keep saved copy")
        await { main { f.experience.cookbook.states.value.deleteConfirmation == null } }
        main { assertEquals(FailureReason.CONFLICT, (f.experience.confirmSavedRecipeDelete(old) as PortResult.Failure).reason) }
        click("Review removal from cookbook"); waitText("Remove this saved copy?"); screenshot("progress-cookbook-delete-consent")
        click("Confirm cookbook removal")
        await { main { !f.experience.forms.value.busy && f.experience.cookbook.states.value.pending == null &&
            f.experience.cookbook.states.value.selected == null && f.experience.cookbook.states.value.serverAcknowledged } }
        main {
            value(f.experience.searchDownloadedCookbook()); assertTrue(f.experience.cookbook.states.value.items.isEmpty())
            val after = checkNotNull(f.experience.cooking.states.value.cooking)
            assertEquals(pin.id, after.id); assertEquals(pin.progress.currentStepId, after.progress.currentStepId)
            assertEquals(pin.progress.status, after.progress.status)
            value(f.experience.searchCookbook()); assertTrue(f.experience.cookbook.states.value.items.isEmpty())
        }
    }
    @Test fun dUnsavedFormBlocksActualSaveAndBackNeverCreatesAnotherCopy() = withHost { f ->
        main {
            f.experience.edit { it.copy(servings = "0.") }
            val result = f.experience.saveSelectedRecipe()
            assertEquals(FailureReason.CONFLICT, (result as PortResult.Failure).reason)
            assertNull(f.experience.cookbook.states.value.pending)
        }
        waitText("Save this plan to cookbook", scroll = true)
        val button = awaitDisabledButton("Save this plan to cookbook")
        assertFalse("Dirty meal Save must remain disabled", button.isEnabled)
        assertTrue("Disabled Save must expose no click action", button.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        main { f.experience.discardUnsavedEdits() }
        val id = f.save(); back(); back()
        main { value(f.experience.openCookbook()); assertEquals(listOf(id), f.experience.cookbook.states.value.items.map { it.id }) }
        assertNull(main { f.experience.cookbook.states.value.pending })
    }
    @Test fun eRecalledDownloadedCopyRemovesWithRedactedConsentAndPreservesCooking() = withHost { f ->
        val id = f.save()
        val saved = main { checkNotNull(f.experience.cookbook.states.value.selected?.savedRecipe) }
        val oldTitle = saved.title
        val oldInstruction = saved.snapshot.steps.first().instruction
        back(); back(); waitText("Cook this plan", scroll = true)
        click("Cook this plan"); waitText("Start this exact plan?"); click("Confirm cooking start")
        await { main { f.experience.cooking.states.value.phase == CookingFlowPhase.COOKING && !f.experience.forms.value.busy } }
        val pin = main { checkNotNull(f.experience.cooking.states.value.cooking) }
        click("Open my cookbook")
        await { main { f.experience.cookbook.states.value.screen == CookbookScreen.LIST } }
        main {
            value(f.experience.openSavedRecipe(id))
            // This changes only the actual encrypted synthetic SERVICE ledger. The client must
            // learn the withdrawal through its subsequent canonical Refresh response.
            value(owner.withdrawSyntheticRecipe(ProgressCatalog.recipeVersionId))
            assertNotNull(f.experience.cookbook.states.value.selected?.savedRecipe)
        }
        click("Refresh saved recipe")
        await { main {
            val state = f.experience.cookbook.states.value
            !f.experience.forms.value.busy && state.failureReason == FailureReason.CONFLICT &&
                state.selected?.id == id && state.selected?.availability == SavedRecipeAvailability.RECALLED &&
                state.selected?.savedRecipe == null && state.selected?.localRevision != null
        } }
        main {
            val state = f.experience.cookbook.states.value
            assertFalse(state.serverAcknowledged); assertNull(state.pending); assertNull(state.deleteConfirmation)
            assertTrue(state.items.filter { it.id == id }.all { it.savedRecipe == null })
        }
        waitText("Instructions unavailable", scroll = true)
        assertFalse(hasText(oldTitle)); assertFalse(hasText(oldInstruction))
        click("Review removal from cookbook"); waitText("Remove this saved copy?")
        val dismissed = main { checkNotNull(f.experience.cookbook.states.value.deleteConfirmation).also {
            assertTrue(it.contentUnavailable); assertNull(it.title)
        } }
        waitText("Instructions and attribution stay hidden")
        assertFalse(hasText(oldTitle)); assertFalse(hasText(oldInstruction))
        click("Keep saved copy")
        await { main { f.experience.cookbook.states.value.deleteConfirmation == null } }
        main {
            assertEquals(FailureReason.CONFLICT,
                (f.experience.confirmSavedRecipeDelete(dismissed) as PortResult.Failure).reason)
            assertNull(f.experience.cookbook.states.value.pending)
        }
        val retainedExperience = f.experience
        f.scenario!!.recreate()
        await { main { owner.experience.value === retainedExperience && !f.experience.forms.value.busy &&
            f.experience.cookbook.states.value.selected?.id == id } }
        main {
            assertSame(retainedExperience, owner.experience.value)
            assertNull(f.experience.cookbook.states.value.selected!!.savedRecipe)
            assertNull(f.experience.cookbook.states.value.deleteConfirmation)
        }
        click("Review removal from cookbook"); waitText("Remove this saved copy?")
        main { checkNotNull(f.experience.cookbook.states.value.deleteConfirmation).also {
            assertNotSame(dismissed, it); assertTrue(it.contentUnavailable); assertNull(it.title)
        } }
        waitText("Instructions and attribution stay hidden")
        assertFalse(hasText(oldTitle)); assertFalse(hasText(oldInstruction))
        screenshot("progress-cookbook-recalled-delete-consent")
        click("Confirm cookbook removal")
        await { main { !f.experience.forms.value.busy && f.experience.cookbook.states.value.pending == null &&
            f.experience.cookbook.states.value.selected == null && f.experience.cookbook.states.value.serverAcknowledged } }
        main {
            // Public retained-controller isolation, not a new remote cooking authorization:
            // deletion must not change the previously observed cooking selection or progress.
            val after = checkNotNull(f.experience.cooking.states.value.cooking)
            assertEquals(pin.id, after.id); assertEquals(pin.localRevision, after.localRevision)
            assertEquals(pin.plan.id, after.plan.id)
            assertEquals(pin.progress.currentStepId, after.progress.currentStepId)
            assertEquals(pin.progress.status, after.progress.status)
            assertEquals(pin.pendingCommandIds, after.pendingCommandIds)
            value(f.experience.searchDownloadedCookbook()); assertTrue(f.experience.cookbook.states.value.items.isEmpty())
            // A fresh LOCAL observation consumes the shared negative recall fence. It must not
            // expose instructions or imply a post-withdrawal server GET/apply acknowledgement.
            val recalled = value(f.experience.readRetainedCooking())
            assertEquals(CookingFlowPhase.UNAVAILABLE, recalled.phase)
            assertNull(recalled.plan); assertNull(recalled.cooking)
            assertFalse(recalled.canEdit); assertFalse(recalled.canStop); assertFalse(recalled.canComplete)
            assertFalse(recalled.serverAcknowledged); assertTrue(recalled.historical)
            assertTrue(recalled.pending.isEmpty())
        }
    }

    private fun await(check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < end) { instrumentation.waitForIdleSync(); if (check()) return; SystemClock.sleep(100) }
        fail("Exact progress cookbook transition was not observed")
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { result += node; repeat(node.childCount) { node.getChild(it)?.let(::visit) } }
        ui.rootInActiveWindow?.let(::visit); return result
    }
    private fun text(value: CharSequence?) = value?.toString()?.replace(Regex("\\s+"), " ")
    private fun exact(node: AccessibilityNodeInfo, label: String) = text(node.text) == label || text(node.contentDescription) == label
    private fun hasText(label: String) = nodes().any { it.isVisibleToUser && (text(it.text)?.contains(label) == true || text(it.contentDescription)?.contains(label) == true) }
    private fun awaitDisabledButton(label: String): AccessibilityNodeInfo {
        var observed: AccessibilityNodeInfo? = null
        await {
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            for (node in nodes().filter { it.isVisibleToUser && exact(it, label) }) {
                if (!node.refresh() || !node.isVisibleToUser || !exact(node, label)) continue
                var candidate: AccessibilityNodeInfo? = node
                // The captured Compose tree places this label inside the clickable action View;
                // its decorative Button is a sibling. Follow only the label's ancestor chain.
                var depth = 0
                while (candidate != null && !candidate.isClickable &&
                    candidate.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK } && depth++ < 8)
                    candidate = candidate.parent
                if (candidate != null && candidate.refresh() && (candidate.isClickable ||
                    candidate.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) &&
                    candidate.isVisibleToUser && node.refresh() && exact(node, label) && node.isVisibleToUser) candidates += candidate
            }
            val button = candidates.distinct().singleOrNull()
            if (button != null && !button.isEnabled && button.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                observed = button; true
            } else false
        }
        return checkNotNull(observed)
    }
    private fun waitText(label: String, scroll: Boolean = false) {
        repeat(60) { n ->
            instrumentation.waitForIdleSync(); if (hasText(label)) return
            if (scroll && n % 3 == 2) nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(200)
        }; fail("Missing progress cookbook text: $label")
    }
    private fun top() { repeat(20) {
        if (hasText("← Back")) return
        nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); SystemClock.sleep(250)
    } }
    private fun click(label: String) {
        if (!hasText(label)) top()
        repeat(60) { n ->
            instrumentation.waitForIdleSync(); val snapshot = nodes()
            for (node in snapshot.filter { it.isVisibleToUser && exact(it, label) }) {
                var action: AccessibilityNodeInfo? = node
                while (action != null && !action.isClickable) action = action.parent
                val button = action ?: continue
                if (node.refresh() && exact(node, label) && node.isVisibleToUser && button.refresh() && button.isVisibleToUser && button.isEnabled) {
                    assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    instrumentation.waitForIdleSync(); SystemClock.sleep(250); return // Never redispatch.
                }
            }
            if (n % 3 == 2) snapshot.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(200)
        }; fail("Missing enabled progress cookbook action: $label")
    }
    private fun back() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); instrumentation.waitForIdleSync(); SystemClock.sleep(250) }
    private fun failureDiagnostics() {
        instrumentation.waitForIdleSync()
        val state = main {
            val current = owner.experience.value
            val book = current?.cookbook?.states?.value
            "host=${owner.states.value.phase}; meal=${current?.meals?.states?.value?.screen}; " +
                "busy=${current?.forms?.value?.busy}; dirty=${current?.forms?.value?.dirty}; formFailure=${current?.forms?.value?.failure}; " +
                "cookbook=${book?.screen}; phase=${book?.phase}; issue=${book?.issue}; failure=${book?.failureReason}; " +
                "selected=${book?.selected != null}; local=${book?.selected?.localRevision != null}; pending=${book?.pending?.operationId}; " +
                "pendingPhase=${book?.pending?.phase}; acknowledgement=${book?.serverAcknowledged}"
        }
        val tree = nodes().take(128).joinToString("\n") { node ->
            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
            "class=${node.className}; visible=${node.isVisibleToUser}; enabled=${node.isEnabled}; clickable=${node.isClickable}; " +
                "scrollable=${node.isScrollable}; actions=${node.actionList.map { it.id }}; bounds=$bounds; " +
                "text=${node.text?.toString()?.take(256)}; description=${node.contentDescription?.toString()?.take(256)}"
        }
        // Synthetic-fixture failure evidence only, captured before close/reset. Never overwrite
        // an earlier attempt or place diagnostic files in the accepted capture names.
        val base = context.cacheDir; val baseStat = Os.lstat(base.path)
        check(OsConstants.S_ISDIR(baseStat.st_mode) && baseStat.st_uid == Process.myUid())
        val directory = File(base.canonicalFile, "progress-cookbook-ui-diagnostics")
        check(!directory.exists() && directory.mkdir()); Os.chmod(directory.path, 448)
        val stat = Os.lstat(directory.path)
        check(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Process.myUid() && (stat.st_mode and 511) == 448)
        val evidence = File(directory, "failure.accessibility.txt")
        check(evidence.createNewFile()); Os.chmod(evidence.path, 384)
        FileOutputStream(evidence).use { it.write((state + "\n" + tree).take(64_000).encodeToByteArray()); it.fd.sync() }
        val bitmap = checkNotNull(ui.takeScreenshot())
        try {
            val target = File(directory, "failure.png"); check(target.createNewFile()); Os.chmod(target.path, 384)
            FileOutputStream(target).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.fd.sync() }
        } finally { bitmap.recycle() }
    }
    private fun screenshot(name: String) {
        check(name in CAPTURES); instrumentation.waitForIdleSync(); SystemClock.sleep(650); instrumentation.waitForIdleSync()
        val base = context.cacheDir; val baseStat = Os.lstat(base.path)
        check(OsConstants.S_ISDIR(baseStat.st_mode) && baseStat.st_uid == Process.myUid())
        val directory = File(base.canonicalFile, "progress-cookbook-ui-evidence")
        if (!captureClaimed) { check(!directory.exists()); check(directory.mkdir()); Os.chmod(directory.path, 448); captureClaimed = true }
        val stat = Os.lstat(directory.path)
        check(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Process.myUid() && (stat.st_mode and 511) == 448)
        check(directory.list()!!.all { it in CAPTURES.map { capture -> "$capture.png" } })
        val target = File(directory, "$name.png"); check(!target.exists() && target.createNewFile()); Os.chmod(target.path, 384)
        val bitmap = checkNotNull(ui.takeScreenshot())
        try { FileOutputStream(target).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.fd.sync() } } finally { bitmap.recycle() }
    }
    private companion object {
        var retained: Any? = null; var captureClaimed = false
        val CAPTURES = setOf("progress-cookbook-saved", "progress-cookbook-page", "progress-cookbook-restored", "progress-cookbook-delete-consent",
            "progress-cookbook-recalled-delete-consent")
    }
}
